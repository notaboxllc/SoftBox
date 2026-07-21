package softbox;

/**
 * ============ EXPLICIT-HMM-DIMER — flat, allocation-free forked-mechanics kernel (Phase G1a) ============
 * A device-compatible re-expression of {@link ExplicitHmmDimer#solve} for the FROZEN standing topology
 * (Ms=3, Ma=1, Mb=1 ⇒ NF=5, NDOF=19), specialized to {@code dirMech=0} (the gliding assay's only mode).
 * ONE work-item processes ONE dimer. All state is flat primitive {@code double[]}; NO object/list/stream/
 * boxing/dynamic-array allocation occurs inside {@link #solveOneDimer}. Scratch is caller-owned.
 *
 * This is the EXACT finite-difference solve of the CPU oracle — NOT an analytic simplification. Every term
 * of {@code nodeForces} (stretch analytic + FD bending + floor), the FD beam tangent (h=1e-5), the two F8/
 * converter head blocks, node drag, and the flat 19×20 Gauss–Jordan solve (`solveLin`) are ported term for
 * term, with 1/x-then-multiply ordering matched to the `scl()` helper so the CPU run is BIT-IDENTICAL to
 * {@link ExplicitHmmDimer#solve} (Brownian off). The only removals are the provably-inactive `dirMech≠0`
 * survey branches. `Math.acos`/`Math.cos`/`Math.sin` are used here for CPU bit-identity; the TornadoVM wrap
 * (Phase G1b) swaps `Math.acos`→the PTX-safe `dacos` poly and re-validates within Tier-1 tolerance.
 *
 * FROZEN TOPOLOGY (compile-time; a different Ms/Ma/Mb needs a recompiled kernel — task §2):
 *   nodes 0..5   : 0=clamped emergence (not a DOF), 1,2=shared interior, 3=fork, 4=pivot A, 5=pivot B
 *   segments (5) : {0,1}{1,2}{2,3} shared, {3,4} branch-A, {3,5} branch-B
 *   hinges  (4)  : {0,1,2}{1,2,3} shared-interior (rest 0), {2,3,4} fork-A (+α), {2,3,5} fork-B (−α)
 * ========================================================================================================
 */
public final class ExplicitHmmDimerGpu {

    // ---------------- frozen topology (task §2/§7) ----------------
    static final int NF = ExplicitHmmDimerGpuParams.NF;       // 5 free nodes
    static final int NDOF = ExplicitHmmDimerGpuParams.NDOF;   // 19
    static final int W = ExplicitHmmDimerGpuParams.W;         // 20 (augmented width)
    static final int PA = 4, PB = 5;                          // pivot node indices
    static final int[] SEG_LO   = { 0, 1, 2, 3, 3 };
    static final int[] SEG_HI   = { 1, 2, 3, 4, 5 };
    static final int[] HINGE_A  = { 0, 1, 2, 2 };
    static final int[] HINGE_M  = { 1, 2, 3, 3 };
    static final int[] HINGE_C  = { 2, 3, 4, 5 };
    static final int[] HINGE_FK = { 0, 0, 1, 1 };            // 1 = a ±α directional fork hinge
    static final double HH = 1e-5;                           // FD step (µm) — inner bending AND outer tangent

    // ---------------- per-dimer flat-state offsets (double[] D, DIM_STRIDE) — task §3 ----------------
    public static final int O_ND      = 0;   // 18 : node coords 0..5 (x,y,z), µm  (node 0 = clamped emergence)
    public static final int O_PHIA    = 18;  // head-A converter φ (rad)
    public static final int O_PSIA    = 19;  // head-A converter ψ (rad)
    public static final int O_PHIB    = 20;  // head-B φ
    public static final int O_PSIB    = 21;  // head-B ψ
    public static final int O_THSA    = 22;  // head-A converter rest target θ_s (rad) — per-step chemistry input
    public static final int O_THSB    = 23;  // head-B θ_s
    public static final int O_PSIACTA = 24;  // head-A ψ_actin (bind rest)
    public static final int O_PSIACTB = 25;  // head-B ψ_actin
    public static final int O_BEUP    = 26;  // 3 : beam up axis (floor + fork-plane)
    public static final int O_G4      = 29;  // 3 : clamped-emergence preferred tangent
    public static final int O_FLOORZ  = 32;  // 1 : floor plane coord (dot(E,eup)−0.05)
    public static final int O_HAEUP   = 33;  // 3 : head-A frame eup
    public static final int O_HABHAT  = 36;  // 3 : head-A frame bhat
    public static final int O_HAECONV = 39;  // 3 : head-A frame econv
    public static final int O_HBEUP   = 42;  // 3 : head-B frame eup
    public static final int O_HBBHAT  = 45;  // 3 : head-B frame bhat
    public static final int O_HBECONV = 48;  // 3 : head-B frame econv
    public static final int O_F8A     = 51;  // 3 : head-A cross-bridge force (world N) — per-step input
    public static final int O_F8B     = 54;  // 3 : head-B cross-bridge force (world N)
    public static final int O_HAC     = 57;  // 3 : head-A converter pivot C (derived)
    public static final int O_HAXF8   = 60;  // 3 : head-A F8 tip xF8 (derived)
    public static final int O_HAXH    = 63;  // 3 : head-A head-center xH (derived)
    public static final int O_HBC     = 66;  // 3 : head-B C
    public static final int O_HBXF8   = 69;  // 3 : head-B xF8
    public static final int O_HBXH    = 72;  // 3 : head-B xH
    // diagnostics (task §5/§20)
    public static final int O_STATUS  = 75;  // 0 ok / 1 solve failure
    public static final int O_MINPIV  = 76;  // min |pivot| in the dense solve
    public static final int O_RESPRE  = 77;  // ‖residual‖ before update
    public static final int O_RESPOST = 78;  // ‖residual‖ after update
    public static final int O_MAXUPD  = 79;  // max |node coordinate update| (nm)
    public static final int O_INVALID = 80;  // 1 if any NaN/Inf appeared
    public static final int DIM_STRIDE = 81;

    // ---------------- shared params (double[] sp) — identical across all dimers in a run ----------------
    public static final int SP_LB     = 0;   // head lever length lb (µm)
    public static final int SP_KF8    = 1;   // head kF8Code (N/µm)
    public static final int SP_KCONV  = 2;   // head kconvCode
    public static final int SP_KBIND  = 3;   // head kbindCode
    public static final int SP_GPHI   = 4;   // head gammaPhi
    public static final int SP_GPSI   = 5;   // head gammaPsi
    public static final int SP_RF8X   = 6;   // head rF8[0]
    public static final int SP_RF8Y   = 7;   // head rF8[1]
    public static final int SP_RCONVX = 8;   // head rConv[0]
    public static final int SP_RCONVY = 9;   // head rConv[1]
    public static final int SP_KBEMG  = 10;  // clamped-emergence bending stiffness (SI)
    public static final int SP_GNODE  = 11;  // node drag (SI)
    public static final int SP_KFLOOR = 12;  // floor stiffness (SI)
    public static final int SP_DT     = 13;  // timestep (s)
    public static final int SP_SEGKS  = 14;  // 5 : per-segment stretch stiffness (SI)
    public static final int SP_SEGL0  = 19;  // 5 : per-segment rest length (µm)
    public static final int SP_HINGEKB = 24; // 4 : per-hinge bending stiffness (SI)
    public static final int SP_HINGEREST = 28; // 4 : per-hinge rest angle (rad; ±α on fork hinges)
    // FDT Brownian force/torque amplitudes (scaled), = sqrt(2·kT·gamma/dt) pre-converted:
    public static final int SP_BRN_NODE = 32; // node force amplitude (pN)     = sqrt(2kT·gammaNode/dt)·1e12
    public static final int SP_BRN_PHI  = 33; // φ torque amplitude (pN·nm)    = sqrt(2kT·gammaPhi/dt)·1e21
    public static final int SP_BRN_PSI  = 34; // ψ torque amplitude (pN·nm)    = sqrt(2kT·gammaPsi/dt)·1e21
    public static final int SP_LEN    = 35;

    // ---------------- per-dimer scratch (double[] sc, SCRATCH_STRIDE) — task §4 ----------------
    public static final int SC_M     = 0;    // 380 : flat 19×20 augmented matrix (row-major, col 19 = RHS)
    public static final int SC_FBASE = 380;  // 18  : base node forces
    public static final int SC_FP    = 398;  // 18  : perturbed (+) node forces
    public static final int SC_FM    = 416;  // 18  : perturbed (−) node forces
    public static final int SC_DQ    = 434;  // 19  : update vector
    public static final int SC_JCOL  = 453;  // 15  : head-block J columns (5 columns × 3)
    public static final int SC_RES   = 468;  // 19  : residual reassembly scratch
    public static final int SCRATCH_STRIDE = 487;

    static double clamp(double c) { return Math.max(-1, Math.min(1, c)); }

    // ==================== bending energy (SI J) — dirMech=0 specialization ====================
    static double bendEnergyFlat(double[] D, int o, double[] sp) {
        int nd = o + O_ND;
        double E = 0;
        // clamped emergence: b0 = nd[1] − nd[0]; penalize angle vs the fixed preferred tangent g4Tan
        double b0x = D[nd + 3] - D[nd], b0y = D[nd + 4] - D[nd + 1], b0z = D[nd + 5] - D[nd + 2];
        double l0 = Math.sqrt(b0x * b0x + b0y * b0y + b0z * b0z);
        double g4x = D[o + O_G4], g4y = D[o + O_G4 + 1], g4z = D[o + O_G4 + 2];
        if (l0 > 1e-12) { double c = clamp((g4x * b0x + g4y * b0y + g4z * b0z) / l0); double th = Math.acos(c); E += 0.5 * sp[SP_KBEMG] * th * th; }
        double eux = D[o + O_BEUP], euy = D[o + O_BEUP + 1], euz = D[o + O_BEUP + 2];
        for (int hi = 0; hi < 4; hi++) {
            int ia = HINGE_A[hi], im = HINGE_M[hi], ic = HINGE_C[hi];
            double kb = sp[SP_HINGEKB + hi];
            double ax = D[nd + im * 3] - D[nd + ia * 3], ay = D[nd + im * 3 + 1] - D[nd + ia * 3 + 1], az = D[nd + im * 3 + 2] - D[nd + ia * 3 + 2];
            double bx = D[nd + ic * 3] - D[nd + im * 3], by = D[nd + ic * 3 + 1] - D[nd + im * 3 + 1], bz = D[nd + ic * 3 + 2] - D[nd + im * 3 + 2];
            double la = Math.sqrt(ax * ax + ay * ay + az * az), lb = Math.sqrt(bx * bx + by * by + bz * bz);
            if (la < 1e-12 || lb < 1e-12) continue;
            if (HINGE_FK[hi] == 1) {
                double invla = 1.0 / la; double ahx = ax * invla, ahy = ay * invla, ahz = az * invla;   // ah = scl(a, 1/la)
                double eDota = eux * ahx + euy * ahy + euz * ahz;
                double sHx = eux - ahx * eDota, sHy = euy - ahy * eDota, sHz = euz - ahz * eDota;
                double ls = Math.sqrt(sHx * sHx + sHy * sHy + sHz * sHz);
                double rest = sp[SP_HINGEREST + hi];
                double dpx, dpy, dpz;
                if (ls < 1e-9) { dpx = ahx; dpy = ahy; dpz = ahz; }
                else { double cr = Math.cos(rest), sr = Math.sin(rest); double invls = 1.0 / ls;         // scl(sHat, 1/ls) then scl(.,sin)
                    dpx = ahx * cr + (sHx * invls) * sr; dpy = ahy * cr + (sHy * invls) * sr; dpz = ahz * cr + (sHz * invls) * sr; }
                double th = Math.acos(clamp((bx * dpx + by * dpy + bz * dpz) / lb)); E += 0.5 * kb * th * th;
            } else {
                double c = clamp((ax * bx + ay * by + az * bz) / (la * lb)); double th = Math.acos(c); E += 0.5 * kb * th * th;
            }
        }
        return E;
    }

    // ==================== internal node forces (SI N) on nodes 0..5 into out[fb..fb+17] ====================
    static void nodeForcesFlat(double[] D, int o, double[] sp, double[] sc, int fb) {
        int nd = o + O_ND;
        for (int i = 0; i < 18; i++) sc[fb + i] = 0;
        // stretch (analytic)
        for (int si = 0; si < 5; si++) {
            int lo = SEG_LO[si], hi = SEG_HI[si]; double ks = sp[SP_SEGKS + si]; double l0m = sp[SP_SEGL0 + si] * 1e-6;
            double bx = D[nd + hi * 3] - D[nd + lo * 3], by = D[nd + hi * 3 + 1] - D[nd + lo * 3 + 1], bz = D[nd + hi * 3 + 2] - D[nd + lo * 3 + 2];
            double len = Math.sqrt(bx * bx + by * by + bz * bz); if (len < 1e-15) continue;
            double f = ks * (len * 1e-6 - l0m); double invlen = 1.0 / len; double ux = bx * invlen, uy = by * invlen, uz = bz * invlen;
            sc[fb + lo * 3] += f * ux; sc[fb + lo * 3 + 1] += f * uy; sc[fb + lo * 3 + 2] += f * uz;
            sc[fb + hi * 3] -= f * ux; sc[fb + hi * 3 + 1] -= f * uy; sc[fb + hi * 3 + 2] -= f * uz;
        }
        // bending (central FD of bendEnergy, h=1e-5) — perturb D's node coords in place and restore
        for (int j = 0; j < 6; j++) for (int k = 0; k < 3; k++) {
            int idx = nd + j * 3 + k; double sav = D[idx];
            D[idx] = sav + HH; double Ep = bendEnergyFlat(D, o, sp);
            D[idx] = sav - HH; double Em = bendEnergyFlat(D, o, sp);
            D[idx] = sav;
            sc[fb + j * 3 + k] += -((Ep - Em) / (2 * HH)) * 1e6;
        }
        // floor
        double eux = D[o + O_BEUP], euy = D[o + O_BEUP + 1], euz = D[o + O_BEUP + 2]; double floorZ = D[o + O_FLOORZ];
        for (int j = 0; j < 6; j++) {
            double z = D[nd + j * 3] * eux + D[nd + j * 3 + 1] * euy + D[nd + j * 3 + 2] * euz;
            if (z < floorZ) { double pen = (floorZ - z) * 1e-6; double fk = sp[SP_KFLOOR] * pen;
                sc[fb + j * 3] += fk * eux; sc[fb + j * 3 + 1] += fk * euy; sc[fb + j * 3 + 2] += fk * euz; }
        }
    }

    // ==================== derived head geometry (geomC over flat state) ====================
    /** isB selects head B; writes C, xF8, xH into D. A (anchor) = the pivot node coordinate. */
    static void geomCFlat(double[] D, int o, boolean isB, double[] sp) {
        int oPhi = isB ? O_PHIB : O_PHIA, oPsi = isB ? O_PSIB : O_PSIA;
        int oEup = isB ? O_HBEUP : O_HAEUP, oBhat = isB ? O_HBBHAT : O_HABHAT, oEcv = isB ? O_HBECONV : O_HAECONV;
        int oC = isB ? O_HBC : O_HAC, oXF8 = isB ? O_HBXF8 : O_HAXF8, oXH = isB ? O_HBXH : O_HAXH;
        int piv = isB ? PB : PA; int aOff = o + O_ND + piv * 3;
        double phi = D[o + oPhi], psi = D[o + oPsi];
        double eux = D[o + oEup], euy = D[o + oEup + 1], euz = D[o + oEup + 2];
        double bhx = D[o + oBhat], bhy = D[o + oBhat + 1], bhz = D[o + oBhat + 2];
        double ecx = D[o + oEcv], ecy = D[o + oEcv + 1], ecz = D[o + oEcv + 2];
        double lb = sp[SP_LB], rF8x = sp[SP_RF8X], rF8y = sp[SP_RF8Y], rCx = sp[SP_RCONVX], rCy = sp[SP_RCONVY];
        double cph = Math.cos(phi), sph = Math.sin(phi);
        double uBx = eux * cph + bhx * sph, uBy = euy * cph + bhy * sph, uBz = euz * cph + bhz * sph;    // uB
        double Cx = D[aOff] + uBx * lb, Cy = D[aOff + 1] + uBy * lb, Cz = D[aOff + 2] + uBz * lb;         // C = A + lb·uB
        // dworld0 = (rF8x−rCx)·bhat + (rF8y−rCy)·eup ; xF8 = C + rotConv(dworld0, psi, econv)
        double dwx = bhx * (rF8x - rCx) + eux * (rF8y - rCy), dwy = bhy * (rF8x - rCx) + euy * (rF8y - rCy), dwz = bhz * (rF8x - rCx) + euz * (rF8y - rCy);
        double cps = Math.cos(psi), sps = Math.sin(psi);
        double cxx = ecy * dwz - ecz * dwy, cxy = ecz * dwx - ecx * dwz, cxz = ecx * dwy - ecy * dwx;     // econv × dworld0
        double xf8x = Cx + dwx * cps + cxx * sps, xf8y = Cy + dwy * cps + cxy * sps, xf8z = Cz + dwz * cps + cxz * sps;
        // rconv0 = rCx·bhat + rCy·eup ; xH = C − rotConv(rconv0, psi, econv)
        double r0x = bhx * rCx + eux * rCy, r0y = bhy * rCx + euy * rCy, r0z = bhz * rCx + euz * rCy;
        double cx2x = ecy * r0z - ecz * r0y, cx2y = ecz * r0x - ecx * r0z, cx2z = ecx * r0y - ecy * r0x;
        double xHx = Cx - (r0x * cps + cx2x * sps), xHy = Cy - (r0y * cps + cx2y * sps), xHz = Cz - (r0z * cps + cx2z * sps);
        D[o + oC] = Cx; D[o + oC + 1] = Cy; D[o + oC + 2] = Cz;
        D[o + oXF8] = xf8x; D[o + oXF8 + 1] = xf8y; D[o + oXF8 + 2] = xf8z;
        D[o + oXH] = xHx; D[o + oXH + 1] = xHy; D[o + oXH + 2] = xHz;
    }

    // ==================== one F8/converter head block into M (LHS) + RHS (col 19) ====================
    static void headBlockFlat(double[] D, int o, double[] sp, double[] sc, int mOff, int jcolOff, boolean isB) {
        int piv = isB ? PB : PA; int angBase = isB ? (3 * NF + 2) : (3 * NF);      // A:15, B:17
        int iPhi = angBase, iPsi = angBase + 1;
        int pB3 = 3 * (piv - 1);
        int oEup = isB ? O_HBEUP : O_HAEUP, oC = isB ? O_HBC : O_HAC, oXF8 = isB ? O_HBXF8 : O_HAXF8;
        int f8Off = o + (isB ? O_F8B : O_F8A);
        double Ex = D[o + oEup], Ey = D[o + oEup + 1], Ez = D[o + oEup + 2];
        double Cx = D[o + oC], Cy = D[o + oC + 1], Cz = D[o + oC + 2];
        double xf8x = D[o + oXF8], xf8y = D[o + oXF8 + 1], xf8z = D[o + oXF8 + 2];
        int aOff = o + O_ND + piv * 3; double Px = D[aOff], Py = D[aOff + 1], Pz = D[aOff + 2];
        double f8x = D[f8Off], f8y = D[f8Off + 1], f8z = D[f8Off + 2];
        // Jphi = E × (C−P) ; Jpsi = E × (xF8−C)  (µm)
        double cpx = Cx - Px, cpy = Cy - Py, cpz = Cz - Pz;
        double xcx = xf8x - Cx, xcy = xf8y - Cy, xcz = xf8z - Cz;
        double JphiX = Ey * cpz - Ez * cpy, JphiY = Ez * cpx - Ex * cpz, JphiZ = Ex * cpy - Ey * cpx;
        double JpsiX = Ey * xcz - Ez * xcy, JpsiY = Ez * xcx - Ex * xcz, JpsiZ = Ex * xcy - Ey * xcx;
        // J columns (3-vectors): col0=(1,0,0) col1=(0,1,0) col2=(0,0,1) col3=Jphi·1e-6 col4=Jpsi·1e-6
        sc[jcolOff + 0] = 1; sc[jcolOff + 1] = 0; sc[jcolOff + 2] = 0;
        sc[jcolOff + 3] = 0; sc[jcolOff + 4] = 1; sc[jcolOff + 5] = 0;
        sc[jcolOff + 6] = 0; sc[jcolOff + 7] = 0; sc[jcolOff + 8] = 1;
        sc[jcolOff + 9] = JphiX * 1e-6; sc[jcolOff + 10] = JphiY * 1e-6; sc[jcolOff + 11] = JphiZ * 1e-6;
        sc[jcolOff + 12] = JpsiX * 1e-6; sc[jcolOff + 13] = JpsiY * 1e-6; sc[jcolOff + 14] = JpsiZ * 1e-6;
        double kfSI = sp[SP_KF8] * 1e6, kc = sp[SP_KCONV], kb = sp[SP_KBIND];
        int m0 = pB3, m1 = pB3 + 1, m2 = pB3 + 2, m3 = iPhi, m4 = iPsi;
        int[] map = { m0, m1, m2, m3, m4 };
        // columns stored [col*3 + comp]; block entry(i,jj) = kfSI · Σ_comp col_i[comp]·col_jj[comp]  (= kfSI·JᵀJ)
        for (int i = 0; i < 5; i++) for (int jj = 0; jj < 5; jj++) {
            double ci0 = sc[jcolOff + i * 3], ci1 = sc[jcolOff + i * 3 + 1], ci2 = sc[jcolOff + i * 3 + 2];
            double cj0 = sc[jcolOff + jj * 3], cj1 = sc[jcolOff + jj * 3 + 1], cj2 = sc[jcolOff + jj * 3 + 2];
            double kij = kfSI * (ci0 * cj0 + ci1 * cj1 + ci2 * cj2);
            sc[mOff + map[i] * W + map[jj]] += kij;
        }
        sc[mOff + iPhi * W + iPhi] += kc; sc[mOff + iPhi * W + iPsi] -= kc; sc[mOff + iPsi * W + iPhi] -= kc; sc[mOff + iPsi * W + iPsi] += kc + kb;
        double aphi = sp[SP_GPHI] / sp[SP_DT], apsi = sp[SP_GPSI] / sp[SP_DT];
        sc[mOff + iPhi * W + iPhi] += aphi; sc[mOff + iPsi * W + iPsi] += apsi;
        double phi = D[o + (isB ? O_PHIB : O_PHIA)], psi = D[o + (isB ? O_PSIB : O_PSIA)];
        double thetaS = D[o + (isB ? O_THSB : O_THSA)], psiActin = D[o + (isB ? O_PSIACTB : O_PSIACTA)];
        double th = psi - phi;
        // QphiF8 = E · ((C−P) × F8h) ·1e-6 ; QpsiF8 = E · ((xF8−C) × F8h) ·1e-6
        double cr1x = cpy * f8z - cpz * f8y, cr1y = cpz * f8x - cpx * f8z, cr1z = cpx * f8y - cpy * f8x;
        double cr2x = xcy * f8z - xcz * f8y, cr2y = xcz * f8x - xcx * f8z, cr2z = xcx * f8y - xcy * f8x;
        double QphiF8 = (Ex * cr1x + Ey * cr1y + Ez * cr1z) * 1e-6;
        double QpsiF8 = (Ex * cr2x + Ey * cr2y + Ez * cr2z) * 1e-6;
        sc[mOff + m0 * W + NDOF] += f8x; sc[mOff + m1 * W + NDOF] += f8y; sc[mOff + m2 * W + NDOF] += f8z;
        sc[mOff + iPhi * W + NDOF] += QphiF8 + kc * (th - thetaS);
        sc[mOff + iPsi * W + NDOF] += QpsiF8 - kc * (th - thetaS) - kb * (psi - psiActin);
    }

    // ==================== flat 19×19 Gauss–Jordan dense solve (partial pivot) ====================
    /** In-place on the augmented matrix at sc[mOff..]; writes solution to sc[dqOff..dqOff+18];
     *  returns status (0 ok / 1 singular-or-nan); sc[minpivOut] gets min |pivot|. */
    static int solveLinFlat(double[] sc, int mOff, int dqOff) {
        double minPiv = Double.MAX_VALUE;
        int status = 0;
        for (int c = 0; c < NDOF; c++) {
            int p = c;
            for (int r = c + 1; r < NDOF; r++) if (Math.abs(sc[mOff + r * W + c]) > Math.abs(sc[mOff + p * W + c])) p = r;
            if (p != c) { for (int k = 0; k <= NDOF; k++) { double t = sc[mOff + c * W + k]; sc[mOff + c * W + k] = sc[mOff + p * W + k]; sc[mOff + p * W + k] = t; } }
            double piv = sc[mOff + c * W + c];
            double ap = Math.abs(piv); if (ap < minPiv) minPiv = ap;
            if (piv == 0.0 || Double.isNaN(piv) || Double.isInfinite(piv)) status = 1;
            for (int r = 0; r < NDOF; r++) { if (r == c) continue; double fac = sc[mOff + r * W + c] / piv; for (int k = c; k <= NDOF; k++) sc[mOff + r * W + k] -= fac * sc[mOff + c * W + k]; }
        }
        for (int i = 0; i < NDOF; i++) { double xi = sc[mOff + i * W + NDOF] / sc[mOff + i * W + i]; sc[dqOff + i] = xi; if (Double.isNaN(xi) || Double.isInfinite(xi)) status = 1; }
        lastMinPivot = minPiv;
        return status;
    }
    static double lastMinPivot = 0;   // side channel for the diagnostic (single-thread CPU use only)

    // ==================== residual reassembly (diagnostic; ‖beam+head generalized force‖) ====================
    static double residualNorm(double[] D, int o, double[] sp, double[] sc, int tmpOff) {
        nodeForcesFlat(D, o, sp, sc, SC_FP);   // beam forces into SC_FP
        for (int j = 1; j <= NF; j++) for (int k = 0; k < 3; k++) sc[tmpOff + 3 * (j - 1) + k] = sc[SC_FP + j * 3 + k];
        sc[tmpOff + 15] = 0; sc[tmpOff + 16] = 0; sc[tmpOff + 17] = 0; sc[tmpOff + 18] = 0;
        headResidual(D, o, sp, sc, tmpOff, false);
        headResidual(D, o, sp, sc, tmpOff, true);
        double s = 0; for (int i = 0; i < NDOF; i++) s += sc[tmpOff + i] * sc[tmpOff + i];
        return Math.sqrt(s);
    }
    static void headResidual(double[] D, int o, double[] sp, double[] sc, int tmpOff, boolean isB) {
        int piv = isB ? PB : PA; int angBase = isB ? (3 * NF + 2) : (3 * NF); int pB3 = 3 * (piv - 1);
        int oEup = isB ? O_HBEUP : O_HAEUP, oC = isB ? O_HBC : O_HAC, oXF8 = isB ? O_HBXF8 : O_HAXF8;
        int f8Off = o + (isB ? O_F8B : O_F8A);
        double Ex = D[o + oEup], Ey = D[o + oEup + 1], Ez = D[o + oEup + 2];
        double Cx = D[o + oC], Cy = D[o + oC + 1], Cz = D[o + oC + 2];
        double xf8x = D[o + oXF8], xf8y = D[o + oXF8 + 1], xf8z = D[o + oXF8 + 2];
        int aOff = o + O_ND + piv * 3; double Px = D[aOff], Py = D[aOff + 1], Pz = D[aOff + 2];
        double f8x = D[f8Off], f8y = D[f8Off + 1], f8z = D[f8Off + 2];
        double cpx = Cx - Px, cpy = Cy - Py, cpz = Cz - Pz, xcx = xf8x - Cx, xcy = xf8y - Cy, xcz = xf8z - Cz;
        double cr1x = cpy * f8z - cpz * f8y, cr1y = cpz * f8x - cpx * f8z, cr1z = cpx * f8y - cpy * f8x;
        double cr2x = xcy * f8z - xcz * f8y, cr2y = xcz * f8x - xcx * f8z, cr2z = xcx * f8y - xcy * f8x;
        double QphiF8 = (Ex * cr1x + Ey * cr1y + Ez * cr1z) * 1e-6, QpsiF8 = (Ex * cr2x + Ey * cr2y + Ez * cr2z) * 1e-6;
        double kc = sp[SP_KCONV], kb = sp[SP_KBIND];
        double phi = D[o + (isB ? O_PHIB : O_PHIA)], psi = D[o + (isB ? O_PSIB : O_PSIA)];
        double thetaS = D[o + (isB ? O_THSB : O_THSA)], psiActin = D[o + (isB ? O_PSIACTB : O_PSIACTA)];
        double th = psi - phi;
        sc[tmpOff + pB3] += f8x; sc[tmpOff + pB3 + 1] += f8y; sc[tmpOff + pB3 + 2] += f8z;
        sc[tmpOff + angBase] += QphiF8 + kc * (th - thetaS);
        sc[tmpOff + angBase + 1] += QpsiF8 - kc * (th - thetaS) - kb * (psi - psiActin);
    }

    // ==================== the complete one-dimer 19-DOF mechanical step (task §1) ====================
    /**
     * @param D  per-dimer flat state (mutated in place); dimer at offset {@code o}.
     * @param sp shared params (SP_* offsets).
     * @param sc caller-owned scratch (SC_* offsets), length ≥ SCRATCH_STRIDE; sole per-dimer scratch.
     * Brownian is OFF in this path (deterministic fixtures / G1a). Returns solve status (0 ok / 1 fail).
     */
    static int solveOneDimer(double[] D, int o, double[] sp, double[] sc) {
        // 0. refresh head geometry consistent with current φ,ψ,pivot (== the object's stored C/xF8 after last pinHead)
        geomCFlat(D, o, false, sp); geomCFlat(D, o, true, sp);
        // 1. zero the augmented matrix
        for (int i = 0; i < NDOF * W; i++) sc[SC_M + i] = 0;
        // 2. beam RHS on free nodes (col NDOF)
        nodeForcesFlat(D, o, sp, sc, SC_FBASE);
        for (int j = 1; j <= NF; j++) for (int k = 0; k < 3; k++) sc[SC_M + (3 * (j - 1) + k) * W + NDOF] = sc[SC_FBASE + j * 3 + k];
        // 3. beam tangent K = −∂F/∂q (central FD over free-node DOF, h=1e-5)
        for (int jc = 1; jc <= NF; jc++) for (int kc = 0; kc < 3; kc++) {
            int col = 3 * (jc - 1) + kc; int idx = o + O_ND + jc * 3 + kc; double sav = D[idx];
            D[idx] = sav + HH; nodeForcesFlat(D, o, sp, sc, SC_FP);
            D[idx] = sav - HH; nodeForcesFlat(D, o, sp, sc, SC_FM);
            D[idx] = sav;
            for (int jr = 1; jr <= NF; jr++) for (int kr = 0; kr < 3; kr++) {
                int row = 3 * (jr - 1) + kr;
                sc[SC_M + row * W + col] += -((sc[SC_FP + jr * 3 + kr] - sc[SC_FM + jr * 3 + kr]) / (2 * HH)) * 1e6;
            }
        }
        // 4. node drag (implicit) on the diagonal
        double aN = sp[SP_GNODE] / sp[SP_DT];
        for (int j = 1; j <= NF; j++) { int fb = j - 1; for (int k = 0; k < 3; k++) sc[SC_M + (3 * fb + k) * W + (3 * fb + k)] += aN; }
        // 5. the two head blocks
        headBlockFlat(D, o, sp, sc, SC_M, SC_JCOL, false);
        headBlockFlat(D, o, sp, sc, SC_M, SC_JCOL, true);
        // 6. residual (pre) = ‖RHS column‖
        double s = 0; for (int i = 0; i < NDOF; i++) s += sc[SC_M + i * W + NDOF] * sc[SC_M + i * W + NDOF];
        D[o + O_RESPRE] = Math.sqrt(s);
        // 7. dense solve
        int status = solveLinFlat(sc, SC_M, SC_DQ);
        D[o + O_MINPIV] = lastMinPivot;
        // 8. update nodes + angles, track max node update + invalid
        double maxUpd = 0; int invalid = 0;
        for (int j = 1; j <= NF; j++) { int fb = j - 1; for (int k = 0; k < 3; k++) { double du = sc[SC_DQ + 3 * fb + k] * 1e6;
            D[o + O_ND + j * 3 + k] += du; double au = Math.abs(du) * 1e3; if (au > maxUpd) maxUpd = au;
            if (Double.isNaN(du) || Double.isInfinite(du)) invalid = 1; } }
        D[o + O_PHIA] += sc[SC_DQ + 3 * NF]; D[o + O_PSIA] += sc[SC_DQ + 3 * NF + 1];
        D[o + O_PHIB] += sc[SC_DQ + 3 * NF + 2]; D[o + O_PSIB] += sc[SC_DQ + 3 * NF + 3];
        // 9. re-pin head geometry (== pinHead) and finalize diagnostics
        geomCFlat(D, o, false, sp); geomCFlat(D, o, true, sp);
        D[o + O_RESPOST] = residualNorm(D, o, sp, sc, SC_RES);
        D[o + O_MAXUPD] = maxUpd;
        D[o + O_STATUS] = status; D[o + O_INVALID] = invalid;
        return status;
    }

    // ========================================================================================================
    // ===================== PRIMARY DEVICE PATH: scaled FLOAT kernel (nm, pN, rad) ============================
    // Same equations as the double oracle, re-expressed in PHYSICAL units so every matrix entry is O(1)–O(500)
    // and the 19×19 solve is float-safe (branch axial 12.6, shared axial 420 pN/nm; converter 128, bind 512
    // pN·nm/rad²; node drag 3.77 pN/nm; F8 spring 1 pN/nm). No 1e6/1e-6/1e12 conversion factors survive inside
    // the kernel — they are folded into the scaled `spf` params at pack time. State `Df` coords are in nm; F8 in
    // pN. Genuine float arithmetic throughout (incl. the Gauss–Jordan solve) so the precision is real, not proxied.
    // ========================================================================================================
    static final float H_NM = 1e-2f;   // FD step in nm (= the oracle's 1e-5 µm)
    static float clampF(float c) { return c < -1f ? -1f : (c > 1f ? 1f : c); }
    static float lastMinPivotF = 0;

    static float bendEnergyF(float[] D, int o, float[] sp) {
        int nd = o + O_ND; float E = 0;
        float b0x = D[nd + 3] - D[nd], b0y = D[nd + 4] - D[nd + 1], b0z = D[nd + 5] - D[nd + 2];
        float l0 = (float) Math.sqrt(b0x * b0x + b0y * b0y + b0z * b0z);
        float g4x = D[o + O_G4], g4y = D[o + O_G4 + 1], g4z = D[o + O_G4 + 2];
        if (l0 > 1e-9f) { float c = clampF((g4x * b0x + g4y * b0y + g4z * b0z) / l0); float th = (float) Math.acos(c); E += 0.5f * sp[SP_KBEMG] * th * th; }
        float eux = D[o + O_BEUP], euy = D[o + O_BEUP + 1], euz = D[o + O_BEUP + 2];
        for (int hi = 0; hi < 4; hi++) {
            int ia = HINGE_A[hi], im = HINGE_M[hi], ic = HINGE_C[hi]; float kb = sp[SP_HINGEKB + hi];
            float ax = D[nd + im * 3] - D[nd + ia * 3], ay = D[nd + im * 3 + 1] - D[nd + ia * 3 + 1], az = D[nd + im * 3 + 2] - D[nd + ia * 3 + 2];
            float bx = D[nd + ic * 3] - D[nd + im * 3], by = D[nd + ic * 3 + 1] - D[nd + im * 3 + 1], bz = D[nd + ic * 3 + 2] - D[nd + im * 3 + 2];
            float la = (float) Math.sqrt(ax * ax + ay * ay + az * az), lb = (float) Math.sqrt(bx * bx + by * by + bz * bz);
            if (la < 1e-9f || lb < 1e-9f) continue;
            if (HINGE_FK[hi] == 1) {
                float invla = 1f / la; float ahx = ax * invla, ahy = ay * invla, ahz = az * invla;
                float eDota = eux * ahx + euy * ahy + euz * ahz;
                float sHx = eux - ahx * eDota, sHy = euy - ahy * eDota, sHz = euz - ahz * eDota;
                float ls = (float) Math.sqrt(sHx * sHx + sHy * sHy + sHz * sHz); float rest = sp[SP_HINGEREST + hi];
                float dpx, dpy, dpz;
                if (ls < 1e-7f) { dpx = ahx; dpy = ahy; dpz = ahz; }
                else { float cr = (float) Math.cos(rest), sr = (float) Math.sin(rest), invls = 1f / ls;
                    dpx = ahx * cr + (sHx * invls) * sr; dpy = ahy * cr + (sHy * invls) * sr; dpz = ahz * cr + (sHz * invls) * sr; }
                float th = (float) Math.acos(clampF((bx * dpx + by * dpy + bz * dpz) / lb)); E += 0.5f * kb * th * th;
            } else {
                float c = clampF((ax * bx + ay * by + az * bz) / (la * lb)); float th = (float) Math.acos(c); E += 0.5f * kb * th * th;
            }
        }
        return E;
    }

    static void nodeForcesF(float[] D, int o, float[] sp, float[] sc, int fb) {
        int nd = o + O_ND;
        for (int i = 0; i < 18; i++) sc[fb + i] = 0;
        for (int si = 0; si < 5; si++) {
            int lo = SEG_LO[si], hi = SEG_HI[si]; float ks = sp[SP_SEGKS + si]; float l0n = sp[SP_SEGL0 + si];
            float bx = D[nd + hi * 3] - D[nd + lo * 3], by = D[nd + hi * 3 + 1] - D[nd + lo * 3 + 1], bz = D[nd + hi * 3 + 2] - D[nd + lo * 3 + 2];
            float len = (float) Math.sqrt(bx * bx + by * by + bz * bz); if (len < 1e-9f) continue;
            float f = ks * (len - l0n); float invlen = 1f / len; float ux = bx * invlen, uy = by * invlen, uz = bz * invlen;
            sc[fb + lo * 3] += f * ux; sc[fb + lo * 3 + 1] += f * uy; sc[fb + lo * 3 + 2] += f * uz;
            sc[fb + hi * 3] -= f * ux; sc[fb + hi * 3 + 1] -= f * uy; sc[fb + hi * 3 + 2] -= f * uz;
        }
        for (int j = 0; j < 6; j++) for (int k = 0; k < 3; k++) {
            int idx = nd + j * 3 + k; float sav = D[idx];
            D[idx] = sav + H_NM; float Ep = bendEnergyF(D, o, sp);
            D[idx] = sav - H_NM; float Em = bendEnergyF(D, o, sp);
            D[idx] = sav;
            sc[fb + j * 3 + k] += -((Ep - Em) / (2 * H_NM));
        }
        float eux = D[o + O_BEUP], euy = D[o + O_BEUP + 1], euz = D[o + O_BEUP + 2]; float floorZ = D[o + O_FLOORZ];
        for (int j = 0; j < 6; j++) {
            float z = D[nd + j * 3] * eux + D[nd + j * 3 + 1] * euy + D[nd + j * 3 + 2] * euz;
            if (z < floorZ) { float pen = floorZ - z; float fk = sp[SP_KFLOOR] * pen;
                sc[fb + j * 3] += fk * eux; sc[fb + j * 3 + 1] += fk * euy; sc[fb + j * 3 + 2] += fk * euz; }
        }
    }

    static void geomCF(float[] D, int o, boolean isB, float[] sp) {
        int oPhi = isB ? O_PHIB : O_PHIA, oPsi = isB ? O_PSIB : O_PSIA;
        int oEup = isB ? O_HBEUP : O_HAEUP, oBhat = isB ? O_HBBHAT : O_HABHAT, oEcv = isB ? O_HBECONV : O_HAECONV;
        int oC = isB ? O_HBC : O_HAC, oXF8 = isB ? O_HBXF8 : O_HAXF8, oXH = isB ? O_HBXH : O_HAXH;
        int piv = isB ? PB : PA; int aOff = o + O_ND + piv * 3;
        float phi = D[o + oPhi], psi = D[o + oPsi];
        float eux = D[o + oEup], euy = D[o + oEup + 1], euz = D[o + oEup + 2];
        float bhx = D[o + oBhat], bhy = D[o + oBhat + 1], bhz = D[o + oBhat + 2];
        float ecx = D[o + oEcv], ecy = D[o + oEcv + 1], ecz = D[o + oEcv + 2];
        float lb = sp[SP_LB], rF8x = sp[SP_RF8X], rF8y = sp[SP_RF8Y], rCx = sp[SP_RCONVX], rCy = sp[SP_RCONVY];
        float cph = (float) Math.cos(phi), sph = (float) Math.sin(phi);
        float uBx = eux * cph + bhx * sph, uBy = euy * cph + bhy * sph, uBz = euz * cph + bhz * sph;
        float Cx = D[aOff] + uBx * lb, Cy = D[aOff + 1] + uBy * lb, Cz = D[aOff + 2] + uBz * lb;
        float dwx = bhx * (rF8x - rCx) + eux * (rF8y - rCy), dwy = bhy * (rF8x - rCx) + euy * (rF8y - rCy), dwz = bhz * (rF8x - rCx) + euz * (rF8y - rCy);
        float cps = (float) Math.cos(psi), sps = (float) Math.sin(psi);
        float cxx = ecy * dwz - ecz * dwy, cxy = ecz * dwx - ecx * dwz, cxz = ecx * dwy - ecy * dwx;
        float xf8x = Cx + dwx * cps + cxx * sps, xf8y = Cy + dwy * cps + cxy * sps, xf8z = Cz + dwz * cps + cxz * sps;
        float r0x = bhx * rCx + eux * rCy, r0y = bhy * rCx + euy * rCy, r0z = bhz * rCx + euz * rCy;
        float cx2x = ecy * r0z - ecz * r0y, cx2y = ecz * r0x - ecx * r0z, cx2z = ecx * r0y - ecy * r0x;
        float xHx = Cx - (r0x * cps + cx2x * sps), xHy = Cy - (r0y * cps + cx2y * sps), xHz = Cz - (r0z * cps + cx2z * sps);
        D[o + oC] = Cx; D[o + oC + 1] = Cy; D[o + oC + 2] = Cz;
        D[o + oXF8] = xf8x; D[o + oXF8 + 1] = xf8y; D[o + oXF8 + 2] = xf8z;
        D[o + oXH] = xHx; D[o + oXH + 1] = xHy; D[o + oXH + 2] = xHz;
    }

    static void headBlockF(float[] D, int o, float[] sp, float[] sc, int mOff, int jcolOff, boolean isB) {
        int piv = isB ? PB : PA; int angBase = isB ? (3 * NF + 2) : (3 * NF); int iPhi = angBase, iPsi = angBase + 1; int pB3 = 3 * (piv - 1);
        int oEup = isB ? O_HBEUP : O_HAEUP, oC = isB ? O_HBC : O_HAC, oXF8 = isB ? O_HBXF8 : O_HAXF8; int f8Off = o + (isB ? O_F8B : O_F8A);
        float Ex = D[o + oEup], Ey = D[o + oEup + 1], Ez = D[o + oEup + 2];
        float Cx = D[o + oC], Cy = D[o + oC + 1], Cz = D[o + oC + 2];
        float xf8x = D[o + oXF8], xf8y = D[o + oXF8 + 1], xf8z = D[o + oXF8 + 2];
        int aOff = o + O_ND + piv * 3; float Px = D[aOff], Py = D[aOff + 1], Pz = D[aOff + 2];
        float f8x = D[f8Off], f8y = D[f8Off + 1], f8z = D[f8Off + 2];
        float cpx = Cx - Px, cpy = Cy - Py, cpz = Cz - Pz, xcx = xf8x - Cx, xcy = xf8y - Cy, xcz = xf8z - Cz;
        float JphiX = Ey * cpz - Ez * cpy, JphiY = Ez * cpx - Ex * cpz, JphiZ = Ex * cpy - Ey * cpx;   // nm/rad
        float JpsiX = Ey * xcz - Ez * xcy, JpsiY = Ez * xcx - Ex * xcz, JpsiZ = Ex * xcy - Ey * xcx;
        sc[jcolOff + 0] = 1; sc[jcolOff + 1] = 0; sc[jcolOff + 2] = 0;
        sc[jcolOff + 3] = 0; sc[jcolOff + 4] = 1; sc[jcolOff + 5] = 0;
        sc[jcolOff + 6] = 0; sc[jcolOff + 7] = 0; sc[jcolOff + 8] = 1;
        sc[jcolOff + 9] = JphiX; sc[jcolOff + 10] = JphiY; sc[jcolOff + 11] = JphiZ;
        sc[jcolOff + 12] = JpsiX; sc[jcolOff + 13] = JpsiY; sc[jcolOff + 14] = JpsiZ;
        float kfSI = sp[SP_KF8], kc = sp[SP_KCONV], kb = sp[SP_KBIND];
        int[] map = { pB3, pB3 + 1, pB3 + 2, iPhi, iPsi };
        for (int i = 0; i < 5; i++) for (int jj = 0; jj < 5; jj++) {
            float ci0 = sc[jcolOff + i * 3], ci1 = sc[jcolOff + i * 3 + 1], ci2 = sc[jcolOff + i * 3 + 2];
            float cj0 = sc[jcolOff + jj * 3], cj1 = sc[jcolOff + jj * 3 + 1], cj2 = sc[jcolOff + jj * 3 + 2];
            sc[mOff + map[i] * W + map[jj]] += kfSI * (ci0 * cj0 + ci1 * cj1 + ci2 * cj2);
        }
        sc[mOff + iPhi * W + iPhi] += kc; sc[mOff + iPhi * W + iPsi] -= kc; sc[mOff + iPsi * W + iPhi] -= kc; sc[mOff + iPsi * W + iPsi] += kc + kb;
        sc[mOff + iPhi * W + iPhi] += sp[SP_GPHI]; sc[mOff + iPsi * W + iPsi] += sp[SP_GPSI];   // angle drag (already /dt scaled)
        float phi = D[o + (isB ? O_PHIB : O_PHIA)], psi = D[o + (isB ? O_PSIB : O_PSIA)];
        float thetaS = D[o + (isB ? O_THSB : O_THSA)], psiActin = D[o + (isB ? O_PSIACTB : O_PSIACTA)];
        float th = psi - phi;
        float cr1x = cpy * f8z - cpz * f8y, cr1y = cpz * f8x - cpx * f8z, cr1z = cpx * f8y - cpy * f8x;
        float cr2x = xcy * f8z - xcz * f8y, cr2y = xcz * f8x - xcx * f8z, cr2z = xcx * f8y - xcy * f8x;
        float QphiF8 = Ex * cr1x + Ey * cr1y + Ez * cr1z;    // pN·nm (nm × pN)
        float QpsiF8 = Ex * cr2x + Ey * cr2y + Ez * cr2z;
        sc[mOff + pB3 * W + NDOF] += f8x; sc[mOff + (pB3 + 1) * W + NDOF] += f8y; sc[mOff + (pB3 + 2) * W + NDOF] += f8z;
        sc[mOff + iPhi * W + NDOF] += QphiF8 + kc * (th - thetaS);
        sc[mOff + iPsi * W + NDOF] += QpsiF8 - kc * (th - thetaS) - kb * (psi - psiActin);
    }

    static int solveLinF(float[] sc, int mOff, int dqOff) {
        float minPiv = Float.MAX_VALUE; int status = 0;
        for (int c = 0; c < NDOF; c++) {
            int p = c;
            for (int r = c + 1; r < NDOF; r++) if (Math.abs(sc[mOff + r * W + c]) > Math.abs(sc[mOff + p * W + c])) p = r;
            if (p != c) { for (int k = 0; k <= NDOF; k++) { float t = sc[mOff + c * W + k]; sc[mOff + c * W + k] = sc[mOff + p * W + k]; sc[mOff + p * W + k] = t; } }
            float piv = sc[mOff + c * W + c]; float ap = Math.abs(piv); if (ap < minPiv) minPiv = ap;
            if (piv == 0f || Float.isNaN(piv) || Float.isInfinite(piv)) status = 1;
            for (int r = 0; r < NDOF; r++) { if (r == c) continue; float fac = sc[mOff + r * W + c] / piv; for (int k = c; k <= NDOF; k++) sc[mOff + r * W + k] -= fac * sc[mOff + c * W + k]; }
        }
        for (int i = 0; i < NDOF; i++) { float xi = sc[mOff + i * W + NDOF] / sc[mOff + i * W + i]; sc[dqOff + i] = xi; if (Float.isNaN(xi) || Float.isInfinite(xi)) status = 1; }
        lastMinPivotF = minPiv; return status;
    }

    static float residualNormF(float[] D, int o, float[] sp, float[] sc, int tmpOff) {
        nodeForcesF(D, o, sp, sc, SC_FP);
        for (int j = 1; j <= NF; j++) for (int k = 0; k < 3; k++) sc[tmpOff + 3 * (j - 1) + k] = sc[SC_FP + j * 3 + k];
        sc[tmpOff + 15] = 0; sc[tmpOff + 16] = 0; sc[tmpOff + 17] = 0; sc[tmpOff + 18] = 0;
        headResidualF(D, o, sp, sc, tmpOff, false); headResidualF(D, o, sp, sc, tmpOff, true);
        float s = 0; for (int i = 0; i < NDOF; i++) s += sc[tmpOff + i] * sc[tmpOff + i];
        return (float) Math.sqrt(s);
    }
    static void headResidualF(float[] D, int o, float[] sp, float[] sc, int tmpOff, boolean isB) {
        int piv = isB ? PB : PA; int angBase = isB ? (3 * NF + 2) : (3 * NF); int pB3 = 3 * (piv - 1);
        int oEup = isB ? O_HBEUP : O_HAEUP, oC = isB ? O_HBC : O_HAC, oXF8 = isB ? O_HBXF8 : O_HAXF8; int f8Off = o + (isB ? O_F8B : O_F8A);
        float Ex = D[o + oEup], Ey = D[o + oEup + 1], Ez = D[o + oEup + 2];
        float Cx = D[o + oC], Cy = D[o + oC + 1], Cz = D[o + oC + 2];
        float xf8x = D[o + oXF8], xf8y = D[o + oXF8 + 1], xf8z = D[o + oXF8 + 2];
        int aOff = o + O_ND + piv * 3; float Px = D[aOff], Py = D[aOff + 1], Pz = D[aOff + 2];
        float f8x = D[f8Off], f8y = D[f8Off + 1], f8z = D[f8Off + 2];
        float cpx = Cx - Px, cpy = Cy - Py, cpz = Cz - Pz, xcx = xf8x - Cx, xcy = xf8y - Cy, xcz = xf8z - Cz;
        float cr1x = cpy * f8z - cpz * f8y, cr1y = cpz * f8x - cpx * f8z, cr1z = cpx * f8y - cpy * f8x;
        float cr2x = xcy * f8z - xcz * f8y, cr2y = xcz * f8x - xcx * f8z, cr2z = xcx * f8y - xcy * f8x;
        float QphiF8 = Ex * cr1x + Ey * cr1y + Ez * cr1z, QpsiF8 = Ex * cr2x + Ey * cr2y + Ez * cr2z;
        float kc = sp[SP_KCONV], kb = sp[SP_KBIND];
        float phi = D[o + (isB ? O_PHIB : O_PHIA)], psi = D[o + (isB ? O_PSIB : O_PSIA)];
        float thetaS = D[o + (isB ? O_THSB : O_THSA)], psiActin = D[o + (isB ? O_PSIACTB : O_PSIACTA)];
        float th = psi - phi;
        sc[tmpOff + pB3] += f8x; sc[tmpOff + pB3 + 1] += f8y; sc[tmpOff + pB3 + 2] += f8z;
        sc[tmpOff + angBase] += QphiF8 + kc * (th - thetaS);
        sc[tmpOff + angBase + 1] += QpsiF8 - kc * (th - thetaS) - kb * (psi - psiActin);
    }

    /** PRIMARY device-path one-dimer 19-DOF step in scaled float (nm, pN, rad). Brownian off. Returns status. */
    static int solveOneDimerFloat(float[] D, int o, float[] sp, float[] sc) {
        geomCF(D, o, false, sp); geomCF(D, o, true, sp);
        for (int i = 0; i < NDOF * W; i++) sc[SC_M + i] = 0;
        nodeForcesF(D, o, sp, sc, SC_FBASE);
        for (int j = 1; j <= NF; j++) for (int k = 0; k < 3; k++) sc[SC_M + (3 * (j - 1) + k) * W + NDOF] = sc[SC_FBASE + j * 3 + k];
        for (int jc = 1; jc <= NF; jc++) for (int kc = 0; kc < 3; kc++) {
            int col = 3 * (jc - 1) + kc; int idx = o + O_ND + jc * 3 + kc; float sav = D[idx];
            D[idx] = sav + H_NM; nodeForcesF(D, o, sp, sc, SC_FP);
            D[idx] = sav - H_NM; nodeForcesF(D, o, sp, sc, SC_FM);
            D[idx] = sav;
            for (int jr = 1; jr <= NF; jr++) for (int kr = 0; kr < 3; kr++) {
                int row = 3 * (jr - 1) + kr;
                sc[SC_M + row * W + col] += -((sc[SC_FP + jr * 3 + kr] - sc[SC_FM + jr * 3 + kr]) / (2 * H_NM));
            }
        }
        float aN = sp[SP_GNODE];   // node drag already scaled to pN/nm (/dt folded in)
        for (int j = 1; j <= NF; j++) { int fb = j - 1; for (int k = 0; k < 3; k++) sc[SC_M + (3 * fb + k) * W + (3 * fb + k)] += aN; }
        headBlockF(D, o, sp, sc, SC_M, SC_JCOL, false);
        headBlockF(D, o, sp, sc, SC_M, SC_JCOL, true);
        float s = 0; for (int i = 0; i < NDOF; i++) s += sc[SC_M + i * W + NDOF] * sc[SC_M + i * W + NDOF];
        D[o + O_RESPRE] = (float) Math.sqrt(s);
        int status = solveLinF(sc, SC_M, SC_DQ);
        D[o + O_MINPIV] = lastMinPivotF;
        float maxUpd = 0; int invalid = 0;
        for (int j = 1; j <= NF; j++) { int fb = j - 1; for (int k = 0; k < 3; k++) { float du = sc[SC_DQ + 3 * fb + k];   // nm (no 1e6)
            D[o + O_ND + j * 3 + k] += du; float au = Math.abs(du); if (au > maxUpd) maxUpd = au;
            if (Float.isNaN(du) || Float.isInfinite(du)) invalid = 1; } }
        D[o + O_PHIA] += sc[SC_DQ + 3 * NF]; D[o + O_PSIA] += sc[SC_DQ + 3 * NF + 1];
        D[o + O_PHIB] += sc[SC_DQ + 3 * NF + 2]; D[o + O_PSIB] += sc[SC_DQ + 3 * NF + 3];
        geomCF(D, o, false, sp); geomCF(D, o, true, sp);
        D[o + O_RESPOST] = residualNormF(D, o, sp, sc, SC_RES);
        D[o + O_MAXUPD] = maxUpd; D[o + O_STATUS] = status; D[o + O_INVALID] = invalid;
        return status;
    }

    private ExplicitHmmDimerGpu() {}
}
