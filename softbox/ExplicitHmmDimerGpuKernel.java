package softbox;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * ============ EXPLICIT-HMM-DIMER — TornadoVM float device kernel (Phase G1b) ============
 * Device-lowerable, batched form of the validated scaled-float G1a solve
 * ({@link ExplicitHmmDimerGpu#solveOneDimerFloat}). ONE {@code @Parallel} work-item solves ONE dimer's
 * complete 19-DOF forked mechanics; identical scaled equations (nm / pN / rad), identical FD tangent, same
 * flat 19×20 Gauss–Jordan partial-pivot. Only implementation-level changes for PTX (task §7):
 *   - state / scratch / params are {@link FloatArray}; status/diagnostics are {@link IntArray}/{@link FloatArray};
 *   - the fixed topology is passed as an {@link IntArray} ({@code topo}) — no static-array access in the kernel;
 *   - LOOP BOUNDS come from {@code counts} (runtime values) so the compiler does NOT unroll the small fixed
 *     loops into thousands of IR nodes (the beamRelaxAnalytic technique — keeps the kernel under the node cap);
 *   - {@code Math.acos} → {@link #facos} (PTX-safe poly; Math.acos does not lower); {@code Math.abs} → {@link #fabs};
 *   - {@code Math.cos/sin/sqrt} DO lower on PTX (used verbatim by the production beam kernel) and are cast to float.
 * The MATH is unchanged (task: no separate mathematical implementation).
 *
 * INTERNAL UNITS (task §3): node coords nm; forces/residuals pN; torques pN·nm; angles rad; stiffness pN/nm
 * (translational) and pN·nm/rad² (torsional); tangent entries pN/(nm|rad); update vector nm (nodes) / rad (angles).
 * All µm→nm, N→pN, SI→scaled conversions are folded into {@code sp} at pack time; NO conversion factor appears here.
 *
 * SCRATCH ISOLATION (task §4): per-dimer base {@code so = m·SCRATCH_STRIDE}; disjoint across work-items ⇒ race-free.
 * ======================================================================================================
 */
public final class ExplicitHmmDimerGpuKernel {

    // counts[] layout (runtime loop bounds — passed, never compile-constant, to defeat unrolling)
    public static final int C_NDIM = 0, C_NF = 1, C_NDOF = 2, C_W = 3, C_NSEG = 4, C_NHINGE = 5, C_NODES = 6;
    // G5 Brownian control (counts slots 7-9): step, seed, brownianOn (0/1). ep = seed + persistentDimerId·101.
    public static final int C_STEP = 7, C_SEED = 8, C_BROWN = 9, COUNTS_LEN = 10;
    static final long SALT_NODE = 0x484D0000L, SALT_ANG = 0x484E0000L;   // == ExplicitHmmDimer.SALT_NODE/SALT_ANG

    /** Deterministic FDT Gaussian (the brownTorque wang-hash → Box–Muller cosine, amplitude factored out).
     *  Bit-for-decision identical hash to ExplicitHmmDimer/TwoBodyConverterMotor.brownTorque ⇒ matched draws CPU↔GPU. */
    static float brownGaussF(long ep, long t, long salt) {
        long h = ((ep * 2654435761L) ^ (t * 40503L) ^ (salt * 0x9E3779B1L)); h ^= (h >>> 13); h *= 0x9E3779B1L; h ^= (h >>> 16);
        float u1 = ((h & 0xFFFFFF) + 1) / 16777217.0f; h ^= (h << 7); float u2 = (((h >>> 8) & 0xFFFFFF) + 1) / 16777217.0f;
        return (float) (Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * 3.141592653589793 * u2));
    }
    // topo[] layout (fixed forked topology)
    public static final int T_SEGLO = 0, T_SEGHI = 5, T_HA = 10, T_HM = 14, T_HC = 18, T_HFK = 22, TOPO_LEN = 26;
    public static final float H_NM = 1e-2f;

    static float fabs(float x) { return x < 0f ? -x : x; }
    static float fclamp(float c) { return c < -1f ? -1f : (c > 1f ? 1f : c); }

    /** PTX-safe acos (Math.acos does not lower). Newton-refined poly (the dacos structure), float. */
    static float facos(float x) {
        if (x > 1f) x = 1f; if (x < -1f) x = -1f;
        float y;
        if (x > 0.95f) { float t = 1f - x; if (t < 0f) t = 0f; y = (float) Math.sqrt(2f * t); }
        else if (x < -0.95f) { float t = 1f + x; if (t < 0f) t = 0f; y = 3.14159265f - (float) Math.sqrt(2f * t); }
        else { float ax = x < 0f ? -x : x; float p = (-0.0187293f * ax + 0.0742610f) * ax - 0.2121144f;
               p = (p * ax + 1.5707963f); p = p * (float) Math.sqrt(1f - ax); y = x < 0f ? (3.14159265f - p) : p; }
        float s = (float) Math.sin(y);
        if (s > 1e-7f || s < -1e-7f) y = y + ((float) Math.cos(y) - x) / s;
        s = (float) Math.sin(y);
        if (s > 1e-7f || s < -1e-7f) y = y + ((float) Math.cos(y) - x) / s;
        return y;
    }

    // ---------------- bending energy (pN·nm), dirMech=0 ----------------
    static float bendEnergyK(FloatArray D, int o, FloatArray sp, IntArray topo, IntArray counts) {
        int nd = o + ExplicitHmmDimerGpu.O_ND; float E = 0f;
        float b0x = D.get(nd + 3) - D.get(nd), b0y = D.get(nd + 4) - D.get(nd + 1), b0z = D.get(nd + 5) - D.get(nd + 2);
        float l0 = (float) Math.sqrt(b0x * b0x + b0y * b0y + b0z * b0z);
        float g4x = D.get(o + ExplicitHmmDimerGpu.O_G4), g4y = D.get(o + ExplicitHmmDimerGpu.O_G4 + 1), g4z = D.get(o + ExplicitHmmDimerGpu.O_G4 + 2);
        if (l0 > 1e-9f) { float c = fclamp((g4x * b0x + g4y * b0y + g4z * b0z) / l0); float th = facos(c); E += 0.5f * sp.get(ExplicitHmmDimerGpu.SP_KBEMG) * th * th; }
        float eux = D.get(o + ExplicitHmmDimerGpu.O_BEUP), euy = D.get(o + ExplicitHmmDimerGpu.O_BEUP + 1), euz = D.get(o + ExplicitHmmDimerGpu.O_BEUP + 2);
        int nh = counts.get(C_NHINGE);
        for (int hi = 0; hi < nh; hi++) {
            int ia = topo.get(T_HA + hi), im = topo.get(T_HM + hi), ic = topo.get(T_HC + hi); float kb = sp.get(ExplicitHmmDimerGpu.SP_HINGEKB + hi);
            float ax = D.get(nd + im * 3) - D.get(nd + ia * 3), ay = D.get(nd + im * 3 + 1) - D.get(nd + ia * 3 + 1), az = D.get(nd + im * 3 + 2) - D.get(nd + ia * 3 + 2);
            float bx = D.get(nd + ic * 3) - D.get(nd + im * 3), by = D.get(nd + ic * 3 + 1) - D.get(nd + im * 3 + 1), bz = D.get(nd + ic * 3 + 2) - D.get(nd + im * 3 + 2);
            float la = (float) Math.sqrt(ax * ax + ay * ay + az * az), lb = (float) Math.sqrt(bx * bx + by * by + bz * bz);
            if (la < 1e-9f || lb < 1e-9f) continue;
            if (topo.get(T_HFK + hi) == 1) {
                float invla = 1f / la; float ahx = ax * invla, ahy = ay * invla, ahz = az * invla;
                float eDota = eux * ahx + euy * ahy + euz * ahz;
                float sHx = eux - ahx * eDota, sHy = euy - ahy * eDota, sHz = euz - ahz * eDota;
                float ls = (float) Math.sqrt(sHx * sHx + sHy * sHy + sHz * sHz); float rest = sp.get(ExplicitHmmDimerGpu.SP_HINGEREST + hi);
                float dpx, dpy, dpz;
                if (ls < 1e-7f) { dpx = ahx; dpy = ahy; dpz = ahz; }
                else { float cr = (float) Math.cos(rest), sr = (float) Math.sin(rest), invls = 1f / ls;
                    dpx = ahx * cr + (sHx * invls) * sr; dpy = ahy * cr + (sHy * invls) * sr; dpz = ahz * cr + (sHz * invls) * sr; }
                float th = facos(fclamp((bx * dpx + by * dpy + bz * dpz) / lb)); E += 0.5f * kb * th * th;
            } else {
                float c = fclamp((ax * bx + ay * by + az * bz) / (la * lb)); float th = facos(c); E += 0.5f * kb * th * th;
            }
        }
        return E;
    }

    // ---------------- internal node forces (pN) into sc[fb..fb+17] ----------------
    static void nodeForcesK(FloatArray D, int o, FloatArray sp, FloatArray sc, int fb, IntArray topo, IntArray counts) {
        int nd = o + ExplicitHmmDimerGpu.O_ND;
        for (int i = 0; i < 18; i++) sc.set(fb + i, 0f);
        int nseg = counts.get(C_NSEG);
        for (int si = 0; si < nseg; si++) {
            int lo = topo.get(T_SEGLO + si), hi = topo.get(T_SEGHI + si); float ks = sp.get(ExplicitHmmDimerGpu.SP_SEGKS + si); float l0n = sp.get(ExplicitHmmDimerGpu.SP_SEGL0 + si);
            float bx = D.get(nd + hi * 3) - D.get(nd + lo * 3), by = D.get(nd + hi * 3 + 1) - D.get(nd + lo * 3 + 1), bz = D.get(nd + hi * 3 + 2) - D.get(nd + lo * 3 + 2);
            float len = (float) Math.sqrt(bx * bx + by * by + bz * bz); if (len < 1e-9f) continue;
            float f = ks * (len - l0n); float invlen = 1f / len; float ux = bx * invlen, uy = by * invlen, uz = bz * invlen;
            sc.set(fb + lo * 3, sc.get(fb + lo * 3) + f * ux); sc.set(fb + lo * 3 + 1, sc.get(fb + lo * 3 + 1) + f * uy); sc.set(fb + lo * 3 + 2, sc.get(fb + lo * 3 + 2) + f * uz);
            sc.set(fb + hi * 3, sc.get(fb + hi * 3) - f * ux); sc.set(fb + hi * 3 + 1, sc.get(fb + hi * 3 + 1) - f * uy); sc.set(fb + hi * 3 + 2, sc.get(fb + hi * 3 + 2) - f * uz);
        }
        int nodes = counts.get(C_NODES);
        for (int j = 0; j < nodes; j++) for (int k = 0; k < 3; k++) {
            int idx = nd + j * 3 + k; float sav = D.get(idx);
            D.set(idx, sav + H_NM); float Ep = bendEnergyK(D, o, sp, topo, counts);
            D.set(idx, sav - H_NM); float Em = bendEnergyK(D, o, sp, topo, counts);
            D.set(idx, sav);
            sc.set(fb + j * 3 + k, sc.get(fb + j * 3 + k) + (-((Ep - Em) / (2f * H_NM))));
        }
        float eux = D.get(o + ExplicitHmmDimerGpu.O_BEUP), euy = D.get(o + ExplicitHmmDimerGpu.O_BEUP + 1), euz = D.get(o + ExplicitHmmDimerGpu.O_BEUP + 2); float floorZ = D.get(o + ExplicitHmmDimerGpu.O_FLOORZ);
        for (int j = 0; j < nodes; j++) {
            float z = D.get(nd + j * 3) * eux + D.get(nd + j * 3 + 1) * euy + D.get(nd + j * 3 + 2) * euz;
            if (z < floorZ) { float pen = floorZ - z; float fk = sp.get(ExplicitHmmDimerGpu.SP_KFLOOR) * pen;
                sc.set(fb + j * 3, sc.get(fb + j * 3) + fk * eux); sc.set(fb + j * 3 + 1, sc.get(fb + j * 3 + 1) + fk * euy); sc.set(fb + j * 3 + 2, sc.get(fb + j * 3 + 2) + fk * euz); }
        }
    }

    // ---------------- head geometry (geomC) ----------------
    static void geomCK(FloatArray D, int o, int isB, FloatArray sp) {
        int oPhi = isB == 1 ? ExplicitHmmDimerGpu.O_PHIB : ExplicitHmmDimerGpu.O_PHIA, oPsi = isB == 1 ? ExplicitHmmDimerGpu.O_PSIB : ExplicitHmmDimerGpu.O_PSIA;
        int oEup = isB == 1 ? ExplicitHmmDimerGpu.O_HBEUP : ExplicitHmmDimerGpu.O_HAEUP, oBhat = isB == 1 ? ExplicitHmmDimerGpu.O_HBBHAT : ExplicitHmmDimerGpu.O_HABHAT, oEcv = isB == 1 ? ExplicitHmmDimerGpu.O_HBECONV : ExplicitHmmDimerGpu.O_HAECONV;
        int oC = isB == 1 ? ExplicitHmmDimerGpu.O_HBC : ExplicitHmmDimerGpu.O_HAC, oXF8 = isB == 1 ? ExplicitHmmDimerGpu.O_HBXF8 : ExplicitHmmDimerGpu.O_HAXF8, oXH = isB == 1 ? ExplicitHmmDimerGpu.O_HBXH : ExplicitHmmDimerGpu.O_HAXH;
        int piv = isB == 1 ? ExplicitHmmDimerGpu.PB : ExplicitHmmDimerGpu.PA; int aOff = o + ExplicitHmmDimerGpu.O_ND + piv * 3;
        float phi = D.get(o + oPhi), psi = D.get(o + oPsi);
        float eux = D.get(o + oEup), euy = D.get(o + oEup + 1), euz = D.get(o + oEup + 2);
        float bhx = D.get(o + oBhat), bhy = D.get(o + oBhat + 1), bhz = D.get(o + oBhat + 2);
        float ecx = D.get(o + oEcv), ecy = D.get(o + oEcv + 1), ecz = D.get(o + oEcv + 2);
        float lb = sp.get(ExplicitHmmDimerGpu.SP_LB), rF8x = sp.get(ExplicitHmmDimerGpu.SP_RF8X), rF8y = sp.get(ExplicitHmmDimerGpu.SP_RF8Y), rCx = sp.get(ExplicitHmmDimerGpu.SP_RCONVX), rCy = sp.get(ExplicitHmmDimerGpu.SP_RCONVY);
        float cph = (float) Math.cos(phi), sph = (float) Math.sin(phi);
        float uBx = eux * cph + bhx * sph, uBy = euy * cph + bhy * sph, uBz = euz * cph + bhz * sph;
        float Cx = D.get(aOff) + uBx * lb, Cy = D.get(aOff + 1) + uBy * lb, Cz = D.get(aOff + 2) + uBz * lb;
        float dwx = bhx * (rF8x - rCx) + eux * (rF8y - rCy), dwy = bhy * (rF8x - rCx) + euy * (rF8y - rCy), dwz = bhz * (rF8x - rCx) + euz * (rF8y - rCy);
        float cps = (float) Math.cos(psi), sps = (float) Math.sin(psi);
        float cxx = ecy * dwz - ecz * dwy, cxy = ecz * dwx - ecx * dwz, cxz = ecx * dwy - ecy * dwx;
        float xf8x = Cx + dwx * cps + cxx * sps, xf8y = Cy + dwy * cps + cxy * sps, xf8z = Cz + dwz * cps + cxz * sps;
        float r0x = bhx * rCx + eux * rCy, r0y = bhy * rCx + euy * rCy, r0z = bhz * rCx + euz * rCy;
        float cx2x = ecy * r0z - ecz * r0y, cx2y = ecz * r0x - ecx * r0z, cx2z = ecx * r0y - ecy * r0x;
        float xHx = Cx - (r0x * cps + cx2x * sps), xHy = Cy - (r0y * cps + cx2y * sps), xHz = Cz - (r0z * cps + cx2z * sps);
        D.set(o + oC, Cx); D.set(o + oC + 1, Cy); D.set(o + oC + 2, Cz);
        D.set(o + oXF8, xf8x); D.set(o + oXF8 + 1, xf8y); D.set(o + oXF8 + 2, xf8z);
        D.set(o + oXH, xHx); D.set(o + oXH + 1, xHy); D.set(o + oXH + 2, xHz);
    }

    static int hbMap(int i, int pB3, int iPhi, int iPsi) { return i < 3 ? pB3 + i : (i == 3 ? iPhi : iPsi); }

    static void headBlockK(FloatArray D, int o, FloatArray sp, FloatArray sc, int mOff, int jcolOff, int isB, IntArray counts, long ep, long tStep, int brownOn) {
        int W = counts.get(C_W), NDOF = counts.get(C_NDOF), NF = counts.get(C_NF);
        int piv = isB == 1 ? ExplicitHmmDimerGpu.PB : ExplicitHmmDimerGpu.PA; int angBase = isB == 1 ? (3 * NF + 2) : (3 * NF); int iPhi = angBase, iPsi = angBase + 1; int pB3 = 3 * (piv - 1);
        int oEup = isB == 1 ? ExplicitHmmDimerGpu.O_HBEUP : ExplicitHmmDimerGpu.O_HAEUP, oC = isB == 1 ? ExplicitHmmDimerGpu.O_HBC : ExplicitHmmDimerGpu.O_HAC, oXF8 = isB == 1 ? ExplicitHmmDimerGpu.O_HBXF8 : ExplicitHmmDimerGpu.O_HAXF8;
        int f8Off = o + (isB == 1 ? ExplicitHmmDimerGpu.O_F8B : ExplicitHmmDimerGpu.O_F8A);
        float Ex = D.get(o + oEup), Ey = D.get(o + oEup + 1), Ez = D.get(o + oEup + 2);
        float Cx = D.get(o + oC), Cy = D.get(o + oC + 1), Cz = D.get(o + oC + 2);
        float xf8x = D.get(o + oXF8), xf8y = D.get(o + oXF8 + 1), xf8z = D.get(o + oXF8 + 2);
        int aOff = o + ExplicitHmmDimerGpu.O_ND + piv * 3; float Px = D.get(aOff), Py = D.get(aOff + 1), Pz = D.get(aOff + 2);
        float f8x = D.get(f8Off), f8y = D.get(f8Off + 1), f8z = D.get(f8Off + 2);
        float cpx = Cx - Px, cpy = Cy - Py, cpz = Cz - Pz, xcx = xf8x - Cx, xcy = xf8y - Cy, xcz = xf8z - Cz;
        float JphiX = Ey * cpz - Ez * cpy, JphiY = Ez * cpx - Ex * cpz, JphiZ = Ex * cpy - Ey * cpx;
        float JpsiX = Ey * xcz - Ez * xcy, JpsiY = Ez * xcx - Ex * xcz, JpsiZ = Ex * xcy - Ey * xcx;
        sc.set(jcolOff + 0, 1f); sc.set(jcolOff + 1, 0f); sc.set(jcolOff + 2, 0f);
        sc.set(jcolOff + 3, 0f); sc.set(jcolOff + 4, 1f); sc.set(jcolOff + 5, 0f);
        sc.set(jcolOff + 6, 0f); sc.set(jcolOff + 7, 0f); sc.set(jcolOff + 8, 1f);
        sc.set(jcolOff + 9, JphiX); sc.set(jcolOff + 10, JphiY); sc.set(jcolOff + 11, JphiZ);
        sc.set(jcolOff + 12, JpsiX); sc.set(jcolOff + 13, JpsiY); sc.set(jcolOff + 14, JpsiZ);
        float kfSI = sp.get(ExplicitHmmDimerGpu.SP_KF8), kc = sp.get(ExplicitHmmDimerGpu.SP_KCONV), kb = sp.get(ExplicitHmmDimerGpu.SP_KBIND);
        for (int i = 0; i < 5; i++) for (int jj = 0; jj < 5; jj++) {
            float ci0 = sc.get(jcolOff + i * 3), ci1 = sc.get(jcolOff + i * 3 + 1), ci2 = sc.get(jcolOff + i * 3 + 2);
            float cj0 = sc.get(jcolOff + jj * 3), cj1 = sc.get(jcolOff + jj * 3 + 1), cj2 = sc.get(jcolOff + jj * 3 + 2);
            int ri = hbMap(i, pB3, iPhi, iPsi), cj = hbMap(jj, pB3, iPhi, iPsi);
            int idx = mOff + ri * W + cj; sc.set(idx, sc.get(idx) + kfSI * (ci0 * cj0 + ci1 * cj1 + ci2 * cj2));
        }
        int dPhi = mOff + iPhi * W + iPhi, dPhiPsi = mOff + iPhi * W + iPsi, dPsiPhi = mOff + iPsi * W + iPhi, dPsi = mOff + iPsi * W + iPsi;
        sc.set(dPhi, sc.get(dPhi) + kc); sc.set(dPhiPsi, sc.get(dPhiPsi) - kc); sc.set(dPsiPhi, sc.get(dPsiPhi) - kc); sc.set(dPsi, sc.get(dPsi) + kc + kb);
        sc.set(dPhi, sc.get(dPhi) + sp.get(ExplicitHmmDimerGpu.SP_GPHI)); sc.set(dPsi, sc.get(dPsi) + sp.get(ExplicitHmmDimerGpu.SP_GPSI));
        float phi = D.get(o + (isB == 1 ? ExplicitHmmDimerGpu.O_PHIB : ExplicitHmmDimerGpu.O_PHIA)), psi = D.get(o + (isB == 1 ? ExplicitHmmDimerGpu.O_PSIB : ExplicitHmmDimerGpu.O_PSIA));
        float thetaS = D.get(o + (isB == 1 ? ExplicitHmmDimerGpu.O_THSB : ExplicitHmmDimerGpu.O_THSA)), psiActin = D.get(o + (isB == 1 ? ExplicitHmmDimerGpu.O_PSIACTB : ExplicitHmmDimerGpu.O_PSIACTA));
        float th = psi - phi;
        float cr1x = cpy * f8z - cpz * f8y, cr1y = cpz * f8x - cpx * f8z, cr1z = cpx * f8y - cpy * f8x;
        float cr2x = xcy * f8z - xcz * f8y, cr2y = xcz * f8x - xcx * f8z, cr2z = xcx * f8y - xcy * f8x;
        float QphiF8 = Ex * cr1x + Ey * cr1y + Ez * cr1z;
        float QpsiF8 = Ex * cr2x + Ey * cr2y + Ez * cr2z;
        int rP0 = mOff + pB3 * W + NDOF, rP1 = mOff + (pB3 + 1) * W + NDOF, rP2 = mOff + (pB3 + 2) * W + NDOF;
        sc.set(rP0, sc.get(rP0) + f8x); sc.set(rP1, sc.get(rP1) + f8y); sc.set(rP2, sc.get(rP2) + f8z);
        int rPhi = mOff + iPhi * W + NDOF, rPsi = mOff + iPsi * W + NDOF;
        sc.set(rPhi, sc.get(rPhi) + QphiF8 + kc * (th - thetaS));
        sc.set(rPsi, sc.get(rPsi) + QpsiF8 - kc * (th - thetaS) - kb * (psi - psiActin));
        if (brownOn == 1) {   // FDT angle noise (φ,ψ), salt SALT_ANG + hi·2 (+1 for ψ), hi = isB
            sc.set(rPhi, sc.get(rPhi) + sp.get(ExplicitHmmDimerGpu.SP_BRN_PHI) * brownGaussF(ep, tStep, SALT_ANG + isB * 2L));
            sc.set(rPsi, sc.get(rPsi) + sp.get(ExplicitHmmDimerGpu.SP_BRN_PSI) * brownGaussF(ep, tStep, SALT_ANG + isB * 2L + 1L));
        }
    }

    /** flat float Gauss–Jordan partial-pivot; writes dq to sc[dqOff..]; returns status code (see class doc). */
    static int solveLinK(FloatArray sc, int mOff, int dqOff, IntArray counts) {
        int NDOF = counts.get(C_NDOF), W = counts.get(C_W);
        float minPiv = Float.MAX_VALUE; int status = 0;
        for (int c = 0; c < NDOF; c++) {
            int p = c;
            for (int r = c + 1; r < NDOF; r++) if (fabs(sc.get(mOff + r * W + c)) > fabs(sc.get(mOff + p * W + c))) p = r;
            if (p != c) for (int k = 0; k <= NDOF; k++) { float t = sc.get(mOff + c * W + k); sc.set(mOff + c * W + k, sc.get(mOff + p * W + k)); sc.set(mOff + p * W + k, t); }
            float piv = sc.get(mOff + c * W + c); float ap = fabs(piv); if (ap < minPiv) minPiv = ap;
            if (piv == 0f) status = 2;
            for (int r = 0; r < NDOF; r++) { if (r == c) continue; float fac = sc.get(mOff + r * W + c) / piv; for (int k = c; k <= NDOF; k++) sc.set(mOff + r * W + k, sc.get(mOff + r * W + k) - fac * sc.get(mOff + c * W + k)); }
        }
        for (int i = 0; i < NDOF; i++) { float xi = sc.get(mOff + i * W + NDOF) / sc.get(mOff + i * W + i); sc.set(dqOff + i, xi); if (xi != xi || xi > 3.0e38f || xi < -3.0e38f) status = 3; }
        // stash min pivot into the last dq+something? no — return via a scratch slot handled by caller
        sc.set(dqOff + NDOF, minPiv);   // one extra slot after dq holds min pivot
        return status;
    }

    // ---------------- residual reassembly (health diagnostic) ----------------
    static float residualNormK(FloatArray D, int o, FloatArray sp, FloatArray sc, int so, int tmpOff, IntArray topo, IntArray counts) {
        int NF = counts.get(C_NF), NDOF = counts.get(C_NDOF);
        nodeForcesK(D, o, sp, sc, so + ExplicitHmmDimerGpu.SC_FP, topo, counts);
        for (int j = 1; j <= NF; j++) for (int k = 0; k < 3; k++) sc.set(tmpOff + 3 * (j - 1) + k, sc.get(so + ExplicitHmmDimerGpu.SC_FP + j * 3 + k));
        sc.set(tmpOff + 15, 0f); sc.set(tmpOff + 16, 0f); sc.set(tmpOff + 17, 0f); sc.set(tmpOff + 18, 0f);
        headResidualK(D, o, sp, sc, tmpOff, 0, counts); headResidualK(D, o, sp, sc, tmpOff, 1, counts);
        float s = 0f; for (int i = 0; i < NDOF; i++) { float v = sc.get(tmpOff + i); s += v * v; }
        return (float) Math.sqrt(s);
    }
    static void headResidualK(FloatArray D, int o, FloatArray sp, FloatArray sc, int tmpOff, int isB, IntArray counts) {
        int NF = counts.get(C_NF);
        int piv = isB == 1 ? ExplicitHmmDimerGpu.PB : ExplicitHmmDimerGpu.PA; int angBase = isB == 1 ? (3 * NF + 2) : (3 * NF); int pB3 = 3 * (piv - 1);
        int oEup = isB == 1 ? ExplicitHmmDimerGpu.O_HBEUP : ExplicitHmmDimerGpu.O_HAEUP, oC = isB == 1 ? ExplicitHmmDimerGpu.O_HBC : ExplicitHmmDimerGpu.O_HAC, oXF8 = isB == 1 ? ExplicitHmmDimerGpu.O_HBXF8 : ExplicitHmmDimerGpu.O_HAXF8;
        int f8Off = o + (isB == 1 ? ExplicitHmmDimerGpu.O_F8B : ExplicitHmmDimerGpu.O_F8A);
        float Ex = D.get(o + oEup), Ey = D.get(o + oEup + 1), Ez = D.get(o + oEup + 2);
        float Cx = D.get(o + oC), Cy = D.get(o + oC + 1), Cz = D.get(o + oC + 2);
        float xf8x = D.get(o + oXF8), xf8y = D.get(o + oXF8 + 1), xf8z = D.get(o + oXF8 + 2);
        int aOff = o + ExplicitHmmDimerGpu.O_ND + piv * 3; float Px = D.get(aOff), Py = D.get(aOff + 1), Pz = D.get(aOff + 2);
        float f8x = D.get(f8Off), f8y = D.get(f8Off + 1), f8z = D.get(f8Off + 2);
        float cpx = Cx - Px, cpy = Cy - Py, cpz = Cz - Pz, xcx = xf8x - Cx, xcy = xf8y - Cy, xcz = xf8z - Cz;
        float cr1x = cpy * f8z - cpz * f8y, cr1y = cpz * f8x - cpx * f8z, cr1z = cpx * f8y - cpy * f8x;
        float cr2x = xcy * f8z - xcz * f8y, cr2y = xcz * f8x - xcx * f8z, cr2z = xcx * f8y - xcy * f8x;
        float QphiF8 = Ex * cr1x + Ey * cr1y + Ez * cr1z, QpsiF8 = Ex * cr2x + Ey * cr2y + Ez * cr2z;
        float kc = sp.get(ExplicitHmmDimerGpu.SP_KCONV), kb = sp.get(ExplicitHmmDimerGpu.SP_KBIND);
        float phi = D.get(o + (isB == 1 ? ExplicitHmmDimerGpu.O_PHIB : ExplicitHmmDimerGpu.O_PHIA)), psi = D.get(o + (isB == 1 ? ExplicitHmmDimerGpu.O_PSIB : ExplicitHmmDimerGpu.O_PSIA));
        float thetaS = D.get(o + (isB == 1 ? ExplicitHmmDimerGpu.O_THSB : ExplicitHmmDimerGpu.O_THSA)), psiActin = D.get(o + (isB == 1 ? ExplicitHmmDimerGpu.O_PSIACTB : ExplicitHmmDimerGpu.O_PSIACTA));
        float th = psi - phi;
        sc.set(tmpOff + pB3, sc.get(tmpOff + pB3) + f8x); sc.set(tmpOff + pB3 + 1, sc.get(tmpOff + pB3 + 1) + f8y); sc.set(tmpOff + pB3 + 2, sc.get(tmpOff + pB3 + 2) + f8z);
        sc.set(tmpOff + angBase, sc.get(tmpOff + angBase) + QphiF8 + kc * (th - thetaS));
        sc.set(tmpOff + angBase + 1, sc.get(tmpOff + angBase + 1) + QpsiF8 - kc * (th - thetaS) - kb * (psi - psiActin));
    }

    // ---------------- one dimer (called per work-item) ----------------
    static void solveOneK(FloatArray D, int o, FloatArray sp, FloatArray sc, int so, IntArray topo, IntArray counts, IntArray status, int statusIdx) {
        int NF = counts.get(C_NF), NDOF = counts.get(C_NDOF), W = counts.get(C_W);
        int brownOn = counts.get(C_BROWN); long tStep = counts.get(C_STEP);
        long ep = (long) counts.get(C_SEED) + (long) (o / ExplicitHmmDimerGpu.DIM_STRIDE) * 101L;   // persistent-id RNG key
        int SC_M = so + ExplicitHmmDimerGpu.SC_M, SC_FBASE = so + ExplicitHmmDimerGpu.SC_FBASE, SC_FP = so + ExplicitHmmDimerGpu.SC_FP, SC_FM = so + ExplicitHmmDimerGpu.SC_FM,
            SC_DQ = so + ExplicitHmmDimerGpu.SC_DQ, SC_JCOL = so + ExplicitHmmDimerGpu.SC_JCOL, SC_RES = so + ExplicitHmmDimerGpu.SC_RES;
        geomCK(D, o, 0, sp); geomCK(D, o, 1, sp);
        for (int i = 0; i < NDOF * W; i++) sc.set(SC_M + i, 0f);
        nodeForcesK(D, o, sp, sc, SC_FBASE, topo, counts);
        for (int j = 1; j <= NF; j++) for (int k = 0; k < 3; k++) sc.set(SC_M + (3 * (j - 1) + k) * W + NDOF, sc.get(SC_FBASE + j * 3 + k));
        for (int jc = 1; jc <= NF; jc++) for (int kc = 0; kc < 3; kc++) {
            int col = 3 * (jc - 1) + kc; int idx = o + ExplicitHmmDimerGpu.O_ND + jc * 3 + kc; float sav = D.get(idx);
            D.set(idx, sav + H_NM); nodeForcesK(D, o, sp, sc, SC_FP, topo, counts);
            D.set(idx, sav - H_NM); nodeForcesK(D, o, sp, sc, SC_FM, topo, counts);
            D.set(idx, sav);
            for (int jr = 1; jr <= NF; jr++) for (int kr = 0; kr < 3; kr++) {
                int row = 3 * (jr - 1) + kr; int mi = SC_M + row * W + col;
                sc.set(mi, sc.get(mi) + (-((sc.get(SC_FP + jr * 3 + kr) - sc.get(SC_FM + jr * 3 + kr)) / (2f * H_NM))));
            }
        }
        float aN = sp.get(ExplicitHmmDimerGpu.SP_GNODE); float brnNode = sp.get(ExplicitHmmDimerGpu.SP_BRN_NODE);
        for (int j = 1; j <= NF; j++) { int fb = j - 1; for (int k = 0; k < 3; k++) { int di = SC_M + (3 * fb + k) * W + (3 * fb + k); sc.set(di, sc.get(di) + aN);
            if (brownOn == 1) { int rhs = SC_M + (3 * fb + k) * W + NDOF; sc.set(rhs, sc.get(rhs) + brnNode * brownGaussF(ep, tStep, SALT_NODE + ((long) j * 131 + k) * 7919L)); } } }
        headBlockK(D, o, sp, sc, SC_M, SC_JCOL, 0, counts, ep, tStep, brownOn);
        headBlockK(D, o, sp, sc, SC_M, SC_JCOL, 1, counts, ep, tStep, brownOn);
        float sPre = 0f; for (int i = 0; i < NDOF; i++) { float v = sc.get(SC_M + i * W + NDOF); sPre += v * v; }
        D.set(o + ExplicitHmmDimerGpu.O_RESPRE, (float) Math.sqrt(sPre));
        int st = solveLinK(sc, SC_M, SC_DQ, counts);
        D.set(o + ExplicitHmmDimerGpu.O_MINPIV, sc.get(SC_DQ + NDOF));
        float maxUpd = 0f; int invalid = 0;
        for (int j = 1; j <= NF; j++) { int fb = j - 1; for (int k = 0; k < 3; k++) { float du = sc.get(SC_DQ + 3 * fb + k);
            D.set(o + ExplicitHmmDimerGpu.O_ND + j * 3 + k, D.get(o + ExplicitHmmDimerGpu.O_ND + j * 3 + k) + du);
            float au = fabs(du); if (au > maxUpd) maxUpd = au; if (du != du || du > 3.0e38f || du < -3.0e38f) invalid = 1; } }
        D.set(o + ExplicitHmmDimerGpu.O_PHIA, D.get(o + ExplicitHmmDimerGpu.O_PHIA) + sc.get(SC_DQ + 3 * NF));
        D.set(o + ExplicitHmmDimerGpu.O_PSIA, D.get(o + ExplicitHmmDimerGpu.O_PSIA) + sc.get(SC_DQ + 3 * NF + 1));
        D.set(o + ExplicitHmmDimerGpu.O_PHIB, D.get(o + ExplicitHmmDimerGpu.O_PHIB) + sc.get(SC_DQ + 3 * NF + 2));
        D.set(o + ExplicitHmmDimerGpu.O_PSIB, D.get(o + ExplicitHmmDimerGpu.O_PSIB) + sc.get(SC_DQ + 3 * NF + 3));
        geomCK(D, o, 0, sp); geomCK(D, o, 1, sp);
        float rPost = residualNormK(D, o, sp, sc, so, SC_RES, topo, counts);
        D.set(o + ExplicitHmmDimerGpu.O_RESPOST, rPost);
        D.set(o + ExplicitHmmDimerGpu.O_MAXUPD, maxUpd);
        if (invalid == 1 && st == 0) st = 3;
        D.set(o + ExplicitHmmDimerGpu.O_STATUS, st); D.set(o + ExplicitHmmDimerGpu.O_INVALID, invalid);
        status.set(statusIdx, st);
    }

    /** G1b batched kernel: one work-item per dimer (dense, all dimers). */
    public static void solveBatchFloat(FloatArray D, FloatArray sp, FloatArray sc, IntArray topo, IntArray counts, IntArray status) {
        int nDim = counts.get(C_NDIM);
        for (@Parallel int m = 0; m < nDim; m++) {
            solveOneK(D, m * ExplicitHmmDimerGpu.DIM_STRIDE, sp, sc, m * ExplicitHmmDimerGpu.SCRATCH_STRIDE, topo, counts, status, m);
        }
    }

    /**
     * G2/G3 ACTIVE kernel: one work-item per ACTIVE dimer. Persistent state {@code D}/{@code sc} stay device-
     * resident and are indexed by the PERSISTENT dimer id ({@code activeIds[a]}), NEVER the compact slot {@code a}.
     * Per-step external inputs (F8 cross-bridge force, θ_s chemistry) arrive in COMPACT active-indexed arrays
     * ({@code inF8[a*6..]}, {@code inThs[a*2..]}) and are injected into the resident state before the solve.
     * counts[C_NDIM] = activeCount. Inactive dimers (not in the list) are never touched ⇒ their resident state
     * is preserved and their scratch is untouched (race-free: distinct active ids ⇒ disjoint state/scratch blocks).
     */
    public static void solveActiveFloat(FloatArray D, FloatArray sp, FloatArray sc, IntArray activeIds,
                                        FloatArray inF8, FloatArray inThs, IntArray topo, IntArray counts, IntArray status) {
        int activeCount = counts.get(C_NDIM);
        for (@Parallel int a = 0; a < activeCount; a++) {
            int id = activeIds.get(a);
            int o = id * ExplicitHmmDimerGpu.DIM_STRIDE;
            D.set(o + ExplicitHmmDimerGpu.O_F8A, inF8.get(a * 6)); D.set(o + ExplicitHmmDimerGpu.O_F8A + 1, inF8.get(a * 6 + 1)); D.set(o + ExplicitHmmDimerGpu.O_F8A + 2, inF8.get(a * 6 + 2));
            D.set(o + ExplicitHmmDimerGpu.O_F8B, inF8.get(a * 6 + 3)); D.set(o + ExplicitHmmDimerGpu.O_F8B + 1, inF8.get(a * 6 + 4)); D.set(o + ExplicitHmmDimerGpu.O_F8B + 2, inF8.get(a * 6 + 5));
            D.set(o + ExplicitHmmDimerGpu.O_THSA, inThs.get(a * 2)); D.set(o + ExplicitHmmDimerGpu.O_THSB, inThs.get(a * 2 + 1));
            solveOneK(D, o, sp, sc, id * ExplicitHmmDimerGpu.SCRATCH_STRIDE, topo, counts, status, a);
        }
    }

    // ================================================================================================
    // ===================== G4a — dimer binding search + gate + 5.4 nm exclusion =====================
    // Device port of the CPU dimer bind path (nearestSeg2D + gate2D + g0–g7 + intra-dimer occupancy
    // exclusion, D0). ONE work-item per dimer processes head A then head B SEQUENTIALLY, so B's partner-
    // exclusion sees A's fresh bind — race-free (no cross-work-item occupancy structure; exclusion is
    // intra-dimer only). Head geometry (xF8/xH) is read from the RESIDENT dimer state D in scaled nm and
    // converted ×1e-3 to µm so the gate math is identical to the CPU (µm/pN/rad, gate2D verbatim). The
    // filament is the shared FilamentStore (µm). Gate params `gp` carry the gate's native-unit constants.
    // gp: [0]FIL_R(µm) [1]PHI_PRE_3E(rad) [2]kF8Code [3]kconvCode [4]kbindCode [5]kT [6]A_SEMI2·1e3(nm)
    //     [7]dBindNm [8]psiDeg [9]phiDeg [10]thetaDeg [11]preloadPn [12]energyKt [13]exclNm [14]exclTol [15]bhatX
    // ================================================================================================
    static final float NM_PER_UM_F = 1000f;
    static float degF(float rad) { return rad * 57.29577951308232f; }

    /** Gate one head (motor slot `mot`, partner `part`) against the filament; commit boundSeg/bindArc if it passes. */
    static void gateHead(FloatArray D, int o, int xf8Off, int xhOff, int phiOff, int psiOff, int psiActOff, int thsOff, int eupOff,
                         int mot, int part, FloatArray filC, FloatArray filU, FloatArray filSeg, FloatArray cumLen,
                         IntArray boundSeg, FloatArray bindArc, IntArray nucState, FloatArray gp, int nSeg) {
        if (boundSeg.get(mot) >= 0) return;               // already bound
        if (nucState.get(mot) != 2) return;               // not ADP·Pi (FREE_BINDABLE + primed) ⇒ not bindable
        // head xF8/xH in µm (D is nm)
        float x8x = D.get(o + xf8Off) / NM_PER_UM_F, x8y = D.get(o + xf8Off + 1) / NM_PER_UM_F, x8z = D.get(o + xf8Off + 2) / NM_PER_UM_F;
        float xhx = D.get(o + xhOff) / NM_PER_UM_F, xhy = D.get(o + xhOff + 1) / NM_PER_UM_F, xhz = D.get(o + xhOff + 2) / NM_PER_UM_F;
        // nearestSeg2D (canonical: distance to clamped closest point)
        int best = -1; float bd = 1e18f;
        for (int s = 0; s < nSeg; s++) {
            float half = 0.5f * filSeg.get(s);
            float cx = filC.get(s), cy = filC.get(nSeg + s), cz = filC.get(2 * nSeg + s);
            float ux = filU.get(s), uy = filU.get(nSeg + s), uz = filU.get(2 * nSeg + s);
            float dx = x8x - cx, dy = x8y - cy, dz = x8z - cz; float foot = dx * ux + dy * uy + dz * uz;
            float footC = foot < -half ? -half : (foot > half ? half : foot);
            float qx = dx - footC * ux, qy = dy - footC * uy, qz = dz - footC * uz; float d2 = qx * qx + qy * qy + qz * qz;
            if (d2 < bd) { bd = d2; best = s; }
        }
        if (best < 0) return;
        int s = best; float half = 0.5f * filSeg.get(s);
        float cx = filC.get(s), cy = filC.get(nSeg + s), cz = filC.get(2 * nSeg + s);
        float ux = filU.get(s), uy = filU.get(nSeg + s), uz = filU.get(2 * nSeg + s);
        float foot = (x8x - cx) * ux + (x8y - cy) * uy + (x8z - cz) * uz;
        float footC = foot < -half ? -half : (foot > half ? half : foot);
        float apx = cx + footC * ux, apy = cy + footC * uy, apz = cz + footC * uz;
        float conDist = (float) Math.sqrt((x8x - apx) * (x8x - apx) + (x8y - apy) * (x8y - apy) + (x8z - apz) * (x8z - apz));
        float bindArcUm = footC + half;
        float surf = (conDist - gp.get(0)) * NM_PER_UM_F;
        float psi = D.get(o + psiOff), phi = D.get(o + phiOff), psiAct = D.get(o + psiActOff), thetaS = D.get(o + thsOff);
        float psiErr = fabs(degF(psi - psiAct));
        float phiErr = fabs(degF(phi - gp.get(1)));
        float thetaErr = fabs(degF((psi - phi) - thetaS));
        float preload = gp.get(2) * conDist * 1e12f;
        float thd = (psi - phi) - thetaS, psd = psi - psiAct;
        float eKt = (0.5f * gp.get(3) * thd * thd + 0.5f * gp.get(4) * psd * psd) / gp.get(5);
        float eupx = D.get(o + eupOff), eupy = D.get(o + eupOff + 1), eupz = D.get(o + eupOff + 2);
        float headSide = ((xhx - cx) * eupx + (xhy - cy) * eupy + (xhz - cz) * eupz) * NM_PER_UM_F;
        // g0–g7 (orientOn=true, D0)
        float marginUm = 1e-6f;   // canonical BIND_EPS (µm)
        boolean g0 = surf < gp.get(7), g1 = psiErr < gp.get(8), g2 = phiErr < gp.get(9), g3 = thetaErr < gp.get(10);
        boolean g4 = preload < gp.get(11), g5 = eKt < gp.get(12), g6 = headSide < gp.get(6);
        boolean g7 = bindArcUm > marginUm && bindArcUm < 2f * half - marginUm;
        if (!(g0 && g1 && g2 && g3 && g4 && g5 && g6 && g7)) return;
        // intra-dimer 5.4 nm occupancy exclusion (D0: no rear rule)
        if (boundSeg.get(part) >= 0) {
            float candMat = cumLen.get(s) + bindArcUm;
            int ps = boundSeg.get(part); float partMat = cumLen.get(ps) + bindArc.get(part);
            float signedNm = (candMat - partMat) * NM_PER_UM_F * gp.get(15);
            float a = signedNm < 0 ? -signedNm : signedNm;
            if (a < gp.get(13) - gp.get(14)) return;   // veto
        }
        boundSeg.set(mot, s); bindArc.set(mot, bindArcUm);
    }

    /** G4a bind kernel: one work-item per dimer, head A then head B (sequential ⇒ B sees A's bind). */
    public static void dimerBindGate(FloatArray D, FloatArray filC, FloatArray filU, FloatArray filSeg, FloatArray cumLen,
                                     IntArray boundSeg, FloatArray bindArc, IntArray nucState, FloatArray gp, IntArray bindCounts) {
        int nDim = bindCounts.get(0), nSeg = bindCounts.get(1);   // bindCounts = [nDim, filament nSeg]
        for (@Parallel int i = 0; i < nDim; i++) {
            int o = i * ExplicitHmmDimerGpu.DIM_STRIDE; int mA = 2 * i, mB = 2 * i + 1;
            gateHead(D, o, ExplicitHmmDimerGpu.O_HAXF8, ExplicitHmmDimerGpu.O_HAXH, ExplicitHmmDimerGpu.O_PHIA, ExplicitHmmDimerGpu.O_PSIA, ExplicitHmmDimerGpu.O_PSIACTA, ExplicitHmmDimerGpu.O_THSA, ExplicitHmmDimerGpu.O_HAEUP, mA, mB, filC, filU, filSeg, cumLen, boundSeg, bindArc, nucState, gp, nSeg);
            gateHead(D, o, ExplicitHmmDimerGpu.O_HBXF8, ExplicitHmmDimerGpu.O_HBXH, ExplicitHmmDimerGpu.O_PHIB, ExplicitHmmDimerGpu.O_PSIB, ExplicitHmmDimerGpu.O_PSIACTB, ExplicitHmmDimerGpu.O_THSB, ExplicitHmmDimerGpu.O_HBEUP, mB, mA, filC, filU, filSeg, cumLen, boundSeg, bindArc, nucState, gp, nSeg);
        }
    }

    // ================================================================================================
    // ===================== G4b — D ↔ MotorStore bridge (place + cock + force feedback) ==============
    // The reused device kernels (cycleLymnTaylor, bondForces, CSR gather) consume the MotorStore body pose +
    // MotorStore force buffers, while the forked mechanics owns the scaled-float dimer state D. Two small
    // bridge kernels reconcile them each step (no new physics):
    //   cockAndPlaceFromD — write the head sub-body pose (coord/uVec/yVec) into MotorStore.body from D's head
    //                       geometry (nm→µm), the placeHead2D analog; + set D.θ_s from the nucleotide state (cock).
    //   bondDataToD       — after bondForces, feed the head-side F8 force (N→pN) back into D.O_F8A/B (the next
    //                       mechanics input) and the along-filament load into MotorStore.forceDotFil (chemistry).
    // ================================================================================================
    static final float PRESTROKE_F = (float) TwoBodyConverterMotor.PRESTROKE_THETAS;   // −30° (ADP·Pi)
    static final float ADP_F = (float) ExplicitHmmDimer.THETAS_ADP;                    // +30° (ADP)

    static void placeOne(FloatArray D, int o, int xf8Off, int xhOff, FloatArray bc, FloatArray bu, FloatArray by, int nB, int h) {
        float x8x = D.get(o + xf8Off) / NM_PER_UM_F, x8y = D.get(o + xf8Off + 1) / NM_PER_UM_F, x8z = D.get(o + xf8Off + 2) / NM_PER_UM_F;
        float xhx = D.get(o + xhOff) / NM_PER_UM_F, xhy = D.get(o + xhOff + 1) / NM_PER_UM_F, xhz = D.get(o + xhOff + 2) / NM_PER_UM_F;
        float dx = x8x - xhx, dy = x8y - xhy, dz = x8z - xhz; float L = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        float uvx, uvy, uvz;
        if (L > 1e-9f) { float inv = 1f / L; uvx = dx * inv; uvy = dy * inv; uvz = dz * inv; } else { uvx = 0f; uvy = 0f; uvz = 1f; }
        // perp3(uv): a = |uvx|<0.9 ? (1,0,0) : (0,1,0); yv = normalize(a − (a·uv)uv)
        float ax = fabs(uvx) < 0.9f ? 1f : 0f, ay = fabs(uvx) < 0.9f ? 0f : 1f;
        float dd = ax * uvx + ay * uvy;
        float yx = ax - dd * uvx, yy = ay - dd * uvy, yz = -dd * uvz; float yl = (float) Math.sqrt(yx * yx + yy * yy + yz * yz);
        float yinv = yl > 1e-12f ? 1f / yl : 0f; yx *= yinv; yy *= yinv; yz *= yinv;
        bc.set(h, xhx); bc.set(nB + h, xhy); bc.set(2 * nB + h, xhz);
        bu.set(h, uvx); bu.set(nB + h, uvy); bu.set(2 * nB + h, uvz);
        by.set(h, yx); by.set(nB + h, yy); by.set(2 * nB + h, yz);
    }

    /** g4bCounts = [nDim, nB]. Writes head sub-body pose (slot 3·motor+2) from D + cocks D.θ_s from nucleotide. */
    public static void cockAndPlaceFromD(FloatArray D, FloatArray bodyCoord, FloatArray bodyUVec, FloatArray bodyYVec, IntArray nucState, IntArray g4bCounts) {
        int nDim = g4bCounts.get(0), nB = g4bCounts.get(1);
        for (@Parallel int i = 0; i < nDim; i++) {
            int o = i * ExplicitHmmDimerGpu.DIM_STRIDE; int mA = 2 * i, mB = 2 * i + 1;
            placeOne(D, o, ExplicitHmmDimerGpu.O_HAXF8, ExplicitHmmDimerGpu.O_HAXH, bodyCoord, bodyUVec, bodyYVec, nB, 3 * mA + 2);
            placeOne(D, o, ExplicitHmmDimerGpu.O_HBXF8, ExplicitHmmDimerGpu.O_HBXH, bodyCoord, bodyUVec, bodyYVec, nB, 3 * mB + 2);
            D.set(o + ExplicitHmmDimerGpu.O_THSA, nucState.get(mA) == 2 ? PRESTROKE_F : ADP_F);
            D.set(o + ExplicitHmmDimerGpu.O_THSB, nucState.get(mB) == 2 ? PRESTROKE_F : ADP_F);
        }
    }

    /** Feed the head-side F8 force (N→pN) into D.O_F8A/B and the along-fil load into forceDotFil. g4bCounts=[nDim,nB]. */
    public static void bondDataToD(FloatArray bondData, FloatArray D, IntArray boundSeg, FloatArray forceDotFil, IntArray g4bCounts) {
        int nDim = g4bCounts.get(0); int ST = 13;
        for (@Parallel int i = 0; i < nDim; i++) {
            int o = i * ExplicitHmmDimerGpu.DIM_STRIDE; int mA = 2 * i, mB = 2 * i + 1;
            D.set(o + ExplicitHmmDimerGpu.O_F8A, bondData.get(mA * ST) * 1e12f); D.set(o + ExplicitHmmDimerGpu.O_F8A + 1, bondData.get(mA * ST + 1) * 1e12f); D.set(o + ExplicitHmmDimerGpu.O_F8A + 2, bondData.get(mA * ST + 2) * 1e12f);
            D.set(o + ExplicitHmmDimerGpu.O_F8B, bondData.get(mB * ST) * 1e12f); D.set(o + ExplicitHmmDimerGpu.O_F8B + 1, bondData.get(mB * ST + 1) * 1e12f); D.set(o + ExplicitHmmDimerGpu.O_F8B + 2, bondData.get(mB * ST + 2) * 1e12f);
            forceDotFil.set(mA, boundSeg.get(mA) >= 0 ? bondData.get(mA * ST + 12) : 0f);
            forceDotFil.set(mB, boundSeg.get(mB) >= 0 ? bondData.get(mB * ST + 12) : 0f);
        }
    }

    // ================================================================================================
    // ===================== G4c — y/z confinement (Assay A) for the actin filament ===================
    // Adds the gliding-assay lateral confinement to the filament forceSum before Brownian+integrate
    // (harness: forceSum[iz]−=kz·z, forceSum[iy]−=kz·y, planar). confParams[0]=kzCode. counts[0]=nSeg.
    // ================================================================================================
    public static void yzConfine(FloatArray forceSum, FloatArray coord, FloatArray confParams, IntArray counts) {
        int nSeg = counts.get(0); float kz = confParams.get(0);
        for (@Parallel int s = 0; s < nSeg; s++) {
            forceSum.set(2 * nSeg + s, forceSum.get(2 * nSeg + s) - kz * coord.get(2 * nSeg + s));
            forceSum.set(nSeg + s, forceSum.get(nSeg + s) - kz * coord.get(nSeg + s));
        }
    }

    // ================================================================================================
    // ===================== G5 — active-list fold into the unified graph =============================
    // cullDimers writes a per-dimer active flag (either head xF8 within cullR perp of any segment — the
    // updateActive predicate). solveGuarded runs the forked mechanics ONLY on active dimers, indexed by
    // PERSISTENT dimer id (the loop index i, never a compact slot) ⇒ permutation-invariant + inactive state
    // preserved (untouched). Inactive dimers' mechanics is a no-op in the dense path too (F8=0 ⇒ dq≈0 at
    // equilibrium), so dense≡active to float tolerance; the fold's value is skipping the no-op solves.
    // ================================================================================================
    public static void cullDimers(FloatArray D, FloatArray filC, FloatArray filU, FloatArray filSeg, IntArray active, FloatArray cullParams, IntArray bindCounts) {
        int nDim = bindCounts.get(0), nSeg = bindCounts.get(1); float cr2 = cullParams.get(0) * cullParams.get(0);
        for (@Parallel int i = 0; i < nDim; i++) {
            int o = i * ExplicitHmmDimerGpu.DIM_STRIDE; float best = 1e18f;
            for (int hh = 0; hh < 2; hh++) {
                int xf8 = hh == 0 ? ExplicitHmmDimerGpu.O_HAXF8 : ExplicitHmmDimerGpu.O_HBXF8;
                float x = D.get(o + xf8) / NM_PER_UM_F, y = D.get(o + xf8 + 1) / NM_PER_UM_F, z = D.get(o + xf8 + 2) / NM_PER_UM_F;
                for (int s = 0; s < nSeg; s++) {
                    float half = 0.5f * filSeg.get(s); float cx = filC.get(s), cy = filC.get(nSeg + s), cz = filC.get(2 * nSeg + s);
                    float ux = filU.get(s), uy = filU.get(nSeg + s), uz = filU.get(2 * nSeg + s);
                    float dx = x - cx, dy = y - cy, dz = z - cz; float foot = dx * ux + dy * uy + dz * uz;
                    float fc = foot < -half ? -half : (foot > half ? half : foot);
                    float px = dx - fc * ux, py = dy - fc * uy, pz = dz - fc * uz; float d2 = px * px + py * py + pz * pz;
                    if (d2 < best) best = d2;
                }
            }
            active.set(i, best < cr2 ? 1 : 0);
        }
    }

    /** Forked mechanics on ACTIVE dimers only (persistent-id indexed). Inactive ⇒ untouched (state preserved). */
    public static void solveGuarded(FloatArray D, FloatArray sp, FloatArray sc, IntArray topo, IntArray counts, IntArray status, IntArray active) {
        int nDim = counts.get(C_NDIM);
        for (@Parallel int m = 0; m < nDim; m++) {
            if (active.get(m) == 0) { status.set(m, 0); }
            else solveOneK(D, m * ExplicitHmmDimerGpu.DIM_STRIDE, sp, sc, m * ExplicitHmmDimerGpu.SCRATCH_STRIDE, topo, counts, status, m);
        }
    }

    // ================================================================================================
    // ===================== High-strain bond-rupture failsafe (physical + emergency) =================
    // Runs AFTER actin motion + bound-site refresh, BEFORE the forked mechanical solve. For each bound head:
    //   B1 bondDisp = |actinSite(post-move) − headTip|  (nm)  — the cross-bridge stretch the solve must resolve
    //   B2 branchExt/strain = (branchLen − rest)/rest         — head A: seg {3,4}, head B: seg {3,5}
    //   B3 force = myoSpring·bondDisp                          — pre-solve predicted load (pN; R4 diagnostic)
    // Physical rupture R1/R2/R3/R4 releases each head that INDEPENDENTLY crosses its threshold (both only if both
    // qualify). R5 EMERGENCY (gap>threshold) releases the MOST-strained still-bound head (score-ranked), counted
    // separately. Release = FREE_BINDABLE + clear bindArc/forceDotFil/forceMag + nucleotide→NONE (no ATP invented).
    // rp: [0]mode [1]bondNm [2]branchNm [3]strain [4]forcePn [5]gapNm [6]emergencyOn [7]myoSpring [8]headLen(µm)
    // events: per-motor 0 none / 1 physical / 2 emergency.
    // ================================================================================================
    static float branchExtNm(FloatArray D, int o, int pivotNode, FloatArray sp, int segIdx) {
        int nd = o + ExplicitHmmDimerGpu.O_ND;
        float ex = D.get(nd + pivotNode * 3) - D.get(nd + 3 * 3), ey = D.get(nd + pivotNode * 3 + 1) - D.get(nd + 3 * 3 + 1), ez = D.get(nd + pivotNode * 3 + 2) - D.get(nd + 3 * 3 + 2);
        return (float) Math.sqrt(ex * ex + ey * ey + ez * ez) - sp.get(ExplicitHmmDimerGpu.SP_SEGL0 + segIdx);   // nm − nm
    }
    static float bondDispNm(FloatArray D, int m, FloatArray bodyCoord, FloatArray bodyUVec, int nB, FloatArray filC, FloatArray filU, FloatArray filSeg, int nSeg, int s, float arc, float headLen) {
        float half = 0.5f * filSeg.get(s);
        float apx = (filC.get(s) + (arc - half) * filU.get(s)) * NM_PER_UM_F, apy = (filC.get(nSeg + s) + (arc - half) * filU.get(nSeg + s)) * NM_PER_UM_F, apz = (filC.get(2 * nSeg + s) + (arc - half) * filU.get(2 * nSeg + s)) * NM_PER_UM_F;
        int h = 3 * m + 2;
        float htx = (bodyCoord.get(h) + 0.5f * headLen * bodyUVec.get(h)) * NM_PER_UM_F, hty = (bodyCoord.get(nB + h) + 0.5f * headLen * bodyUVec.get(nB + h)) * NM_PER_UM_F, htz = (bodyCoord.get(2 * nB + h) + 0.5f * headLen * bodyUVec.get(2 * nB + h)) * NM_PER_UM_F;
        float dx = apx - htx, dy = apy - hty, dz = apz - htz; return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
    static void releaseHead(IntArray boundSeg, FloatArray bindArc, IntArray nucState, FloatArray forceDotFil, int m, IntArray events, int kind) {
        boundSeg.set(m, -1); bindArc.set(m, 0f); forceDotFil.set(m, 0f); nucState.set(m, 0);   // FREE_BINDABLE, NUC_NONE (forceMag reset next-step)
        events.set(m, kind);
    }

    /** rc = [nDim, nSeg, nB]. */
    public static void ruptureCheck(FloatArray D, FloatArray bodyCoord, FloatArray bodyUVec, IntArray boundSeg, FloatArray bindArc, IntArray nucState,
                                    FloatArray forceDotFil, FloatArray filC, FloatArray filU, FloatArray filSeg,
                                    FloatArray sp, FloatArray rp, IntArray events, IntArray rc) {
        int mode = (int) rp.get(0); int nDim = rc.get(0), nSeg = rc.get(1), nB = rc.get(2);
        float bondRel = rp.get(1), brRel = rp.get(2), strainRel = rp.get(3), fRel = rp.get(4), gapRel = rp.get(5); int emergOn = (int) rp.get(6);
        float myoSpring = rp.get(7), headLen = rp.get(8);
        for (@Parallel int i = 0; i < nDim; i++) {
            int o = i * ExplicitHmmDimerGpu.DIM_STRIDE;
            events.set(2 * i, 0); events.set(2 * i + 1, 0);
            float scoreA = -1f, scoreB = -1f;   // >=0 ⇒ still-bound head's rupture score (for R5 ranking)
            for (int hh = 0; hh < 2; hh++) {
                int m = 2 * i + hh; if (boundSeg.get(m) < 0) continue;
                int s = boundSeg.get(m); float arc = bindArc.get(m);
                float bd = bondDispNm(D, m, bodyCoord, bodyUVec, nB, filC, filU, filSeg, nSeg, s, arc, headLen);
                int piv = hh == 0 ? 4 : 5, seg = hh == 0 ? 3 : 4;
                float brExt = branchExtNm(D, o, piv, sp, seg); float rest = sp.get(ExplicitHmmDimerGpu.SP_SEGL0 + seg); float brStrain = brExt / rest;
                float force = myoSpring * bd * 1e9f;   // pN (myoSpring N/µm · nm · 1e9)
                boolean phys = false;
                if (mode == 1) phys = bd > bondRel;
                else if (mode == 2) phys = brExt > brRel || brStrain > strainRel;
                else if (mode == 3) phys = bd > bondRel || brExt > brRel || brStrain > strainRel;
                else if (mode == 4) phys = force > fRel;
                if (phys) { releaseHead(boundSeg, bindArc, nucState, forceDotFil, m, events, 1); }
                else {
                    float pe = brExt > 0 ? brExt : 0f, ps = brStrain > 0 ? brStrain : 0f;
                    float score = bd / bondRel + pe / brRel + ps / strainRel;
                    if (hh == 0) scoreA = score; else scoreB = score;
                }
            }
            // R5 emergency: dimer max joint gap over segments; release most-strained still-bound head
            if (emergOn == 1 || mode == 5) {
                float gap = 0f;
                for (int si = 0; si < 5; si++) { int lo = si < 3 ? si : 3, hi = si < 3 ? si + 1 : (si == 3 ? 4 : 5);   // segs {0,1}{1,2}{2,3}{3,4}{3,5}
                    float bx = D.get(o + ExplicitHmmDimerGpu.O_ND + hi * 3) - D.get(o + ExplicitHmmDimerGpu.O_ND + lo * 3), by = D.get(o + ExplicitHmmDimerGpu.O_ND + hi * 3 + 1) - D.get(o + ExplicitHmmDimerGpu.O_ND + lo * 3 + 1), bz = D.get(o + ExplicitHmmDimerGpu.O_ND + hi * 3 + 2) - D.get(o + ExplicitHmmDimerGpu.O_ND + lo * 3 + 2);
                    float len = (float) Math.sqrt(bx * bx + by * by + bz * bz); float g = len - sp.get(ExplicitHmmDimerGpu.SP_SEGL0 + si); if (g < 0) g = -g; if (g > gap) gap = g; }
                if (gap > gapRel) {
                    int mA = 2 * i, mB = 2 * i + 1;
                    if (scoreA >= 0f && scoreA >= scoreB) releaseHead(boundSeg, bindArc, nucState, forceDotFil, mA, events, 2);
                    else if (scoreB >= 0f) releaseHead(boundSeg, bindArc, nucState, forceDotFil, mB, events, 2);
                }
            }
        }
    }

    private ExplicitHmmDimerGpuKernel() {}
}
