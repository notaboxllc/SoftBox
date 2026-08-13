package softbox;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.DoubleArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * INITIAL GPU kernel source for the two-body canonical motor Step-7 (the model-divergent per-motor
 * overdamped-implicit solve). Written in the SoftBox TornadoVM idiom — {@code static void} kernel
 * methods over planar-SoA buffers with a {@code for (@Parallel int m=0; m<nM; m++)} loop — so that,
 * called directly as a plain Java loop, the method IS the CPU runner ("one implementation, two
 * runners"). Device execution (a TaskGraph + WorkerGrid localWork=64) is deferred to post-sweep; this
 * file's job today is to carry the SCALARIZED arithmetic and be validated on the CPU runner against
 * the golden replay fixtures (see {@link MotorReplayHarness} {@code -validatekernels}).
 *
 * <p>Three kernels, matching the three motor models' Step-7:
 * <ul>
 *   <li>{@link #fixedStep}      — FLOAT32, FIXED_ANCHOR (supOn=false ⇒ {@code stepC}'s rigid 2×2 φ,ψ solve; pivot pinned).
 *   <li>{@link #calibratedStep} — FLOAT32, CALIBRATED_S2_L40 ({@code stepSup}'s analytic 5×5 movable-pivot solve).
 *   <li>{@link #explicitBeamStep} — DOUBLE, EXPLICIT_S2_L40 ({@code stepS2}/{@code s2Solve}'s 14-DOF (3M+2, M=4) beam Newton step).
 * </ul>
 *
 * <p>The F8 head force (F8h) is taken as an INPUT buffer: it is the output of {@code CrossBridgeSystem.bondForces}
 * — a separate, already CPU≡GPU-validated system — not part of Step-7's solve. The solve consumes F8h.
 *
 * <h3>Heap / scratch policy</h3>
 * The kernel BODIES allocate no heap for the physics vectors (all vec3 ops are scalarized to locals).
 * The linear solves use a FIXED-SIZE local scratch array ({@code float[5][6]} / {@code double[14][15]}).
 * <b>TODO(GPU):</b> a real device launch needs those scratch matrices as per-thread slices of a
 * caller-provided buffer (TornadoVM forbids in-kernel heap allocation); the local-array form here is
 * the sanctioned CPU-runner representation. The nested finite-difference beam tangent likewise uses a
 * local {@code double[5][3]} node scratch — TODO: per-thread buffer on device.
 *
 * <p>RNG: the counter-based wang-hash is reproduced BIT-FOR-BIT from {@code TwoBodyConverterMotor.brownTorque}
 * (64-bit integer hashing; double transcendental). For the FLOAT32 kernels the draw is computed in double
 * then cast to float only when combined into the float RHS (minimizes RNG-related float error; the eventual
 * device kernel may use float transcendentals — documented divergence, within the T3 tolerance).
 */
public final class TwoBodyGpuKernels {
    private TwoBodyGpuKernels() {}

    // ===============================================================================================
    // Counter-based RNG — EXACT copy of TwoBodyConverterMotor.brownTorque (bit-for-bit integer hash).
    // ===============================================================================================
    static double brownTorqueD(double gamma, double dt, long ep, long t, long salt) {
        long h = ((ep * 2654435761L) ^ (t * 40503L) ^ (salt * 0x9E3779B1L));
        h ^= (h >>> 13); h *= 0x9E3779B1L; h ^= (h >>> 16);
        double u1 = ((h & 0xFFFFFF) + 1) / 16777217.0;
        h ^= (h << 7);
        double u2 = (((h >>> 8) & 0xFFFFFF) + 1) / 16777217.0;
        double g = Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
        return Math.sqrt(2 * Constants.kT * gamma / dt) * g;
    }

    /** Reinterpret-free |x| for float (bit-identical to JDK Math.abs(float) for all finite/NaN inputs). JDK 21's
     *  Math.abs(float) lowers to a float↔int bit-reinterpret the PTX backend cannot emit; this ternary does not. */
    static float fabs(float x) { return (x <= 0.0f) ? 0.0f - x : x; }

    /** PTX-lowerable compensated log1p (Kahan/Goldberg): ln(1+x) to ~1 ulp everywhere WITHOUT the fdlibm
     *  bit-reinterpret that JDK Math.log1p lowers to (PTX has no LOG1P intrinsic). Uses Math.log (a PTX
     *  intrinsic). Approved expression-form substitution for lowering; universally ~1 ulp (no small-x bias). */
    static double log1pC(double x) { double u = 1.0 + x; return (u == 1.0) ? x : Math.log(u) * (x / (u - 1.0)); }

    // Softplus ramp — EXACT copies (used by the calibrated supForce law).
    static double softpos(double x, double s) {
        if (s <= 0) return (0.0 >= x ? 0.0 : x);   // == Math.max(0.0,x); reinterpret-free (JDK Math.max uses doubleToRawLongBits)
        double z = x / s; if (z > 30) return x; if (z < -30) return s * Math.exp(z);
        return s * log1pC(Math.exp(z));            // compensated log1p (device-lowerable; ~1 ulp, no reinterpret)
    }
    static double softpos_d(double x, double s) {
        if (s <= 0) return x > 0 ? 1 : 0;
        double z = x / s; if (z > 30) return 1; if (z < -30) return Math.exp(z);
        return 1.0 / (1.0 + Math.exp(-z));
    }

    // ===============================================================================================
    // KERNEL 1: FIXED_ANCHOR (float32). Rigid 2×2 (φ,ψ) solve — the stepC path (pivot A pinned, no Brownian).
    //   q:      [phi, psi, thetaS, psiActin]           (comp*N+m)   phi,psi updated
    //   A:      pivot/anchor position (planar x,y,z)   (comp*N+m)   read-only (fixed)
    //   frame:  bhat(0..2), econv(3..5), eup(6..8)     (comp*N+m)
    //   params: lb,rF8x,rF8y,rConvx,rConvy,kF8Code,kconvCode,kbindCode,gammaP,gammaPhi,gammaPsi,dt  (comp*N+m)
    //   F8h:    head force from bondForces (planar x,y,z)
    //   outGeom: C(0..2), xH(3..5), xF8(6..8)          (comp*N+m)   written
    //   counts: [nM, t, seed, brownian]
    // ===============================================================================================
    public static void fixedStep(FloatArray q, FloatArray A, FloatArray frame,
                                 FloatArray params, FloatArray F8h, FloatArray outGeom, IntArray counts) {
        int nM = counts.get(0);
        for (@Parallel int m = 0; m < nM; m++) {
            float phi = q.get(m), psi = q.get(nM + m), thetaS = q.get(2 * nM + m), psiActin = q.get(3 * nM + m);
            float Ax = A.get(m), Ay = A.get(nM + m), Az = A.get(2 * nM + m);
            float bx = frame.get(m),        by = frame.get(nM + m),      bz = frame.get(2 * nM + m);
            float ex = frame.get(3 * nM + m), ey = frame.get(4 * nM + m), ez = frame.get(5 * nM + m);
            float ux = frame.get(6 * nM + m), uy = frame.get(7 * nM + m), uz = frame.get(8 * nM + m);
            float lb = params.get(m);
            float rF8x = params.get(nM + m), rF8y = params.get(2 * nM + m);
            float rCx  = params.get(3 * nM + m), rCy = params.get(4 * nM + m);
            float kF8 = params.get(5 * nM + m), kc = params.get(6 * nM + m), kb = params.get(7 * nM + m);
            float gPhi = params.get(9 * nM + m), gPsi = params.get(10 * nM + m), dt = params.get(11 * nM + m);
            float f8x = F8h.get(m), f8y = F8h.get(nM + m), f8z = F8h.get(2 * nM + m);

            // --- geometry (geomC) at the pre-step pose: C, xF8 (xH not needed for the solve) ---
            float cphi = (float) Math.cos(phi), sphi = (float) Math.sin(phi);
            float uBx = ux * cphi + bx * sphi, uBy = uy * cphi + by * sphi, uBz = uz * cphi + bz * sphi;
            float Cx = Ax + uBx * lb, Cy = Ay + uBy * lb, Cz = Az + uBz * lb;
            // dworld0 = bhat*(rF8x-rCx) + eup*(rF8y-rCy) ; xF8 = C + rotConv(dworld0, psi, econv)
            float d0x = bx * (rF8x - rCx) + ux * (rF8y - rCy);
            float d0y = by * (rF8x - rCx) + uy * (rF8y - rCy);
            float d0z = bz * (rF8x - rCx) + uz * (rF8y - rCy);
            float cpsi = (float) Math.cos(psi), spsi = (float) Math.sin(psi);
            // rotConv(V,psi,econv) = V*cos + (econv×V)*sin
            float xF8x = Cx + (d0x * cpsi + (ey * d0z - ez * d0y) * spsi);
            float xF8y = Cy + (d0y * cpsi + (ez * d0x - ex * d0z) * spsi);
            float xF8z = Cz + (d0z * cpsi + (ex * d0y - ey * d0x) * spsi);

            // --- 2×2 (φ,ψ) generalized-force solve (stepC, non-legacy) ---
            // Jphi = econv × (C-A) ; Jpsi = econv × (xF8-C)   (µm)
            float cax = Cx - Ax, cay = Cy - Ay, caz = Cz - Az;
            float fcx = xF8x - Cx, fcy = xF8y - Cy, fcz = xF8z - Cz;
            float Jphix = ey * caz - ez * cay, Jphiy = ez * cax - ex * caz, Jphiz = ex * cay - ey * cax;
            float Jpsix = ey * fcz - ez * fcy, Jpsiy = ez * fcx - ex * fcz, Jpsiz = ex * fcy - ey * fcx;
            // QphiF8 = dot(econv, (C-A)×F8h)*1e-6 ; QpsiF8 = dot(econv, (xF8-C)×F8h)*1e-6
            float caF_x = cay * f8z - caz * f8y, caF_y = caz * f8x - cax * f8z, caF_z = cax * f8y - cay * f8x;
            float fcF_x = fcy * f8z - fcz * f8y, fcF_y = fcz * f8x - fcx * f8z, fcF_z = fcx * f8y - fcy * f8x;
            float QphiF8 = (ex * caF_x + ey * caF_y + ez * caF_z) * 1e-6f;
            float QpsiF8 = (ex * fcF_x + ey * fcF_y + ez * fcF_z) * 1e-6f;
            float aphi = gPhi / dt, apsi = gPsi / dt;
            float th = psi - phi;
            float Fphi = QphiF8 + kc * (th - thetaS);
            float Fpsi = QpsiF8 - kc * (th - thetaS) - kb * (psi - psiActin);
            float Kff = kF8 * (Jphix * Jphix + Jphiy * Jphiy + Jphiz * Jphiz) * 1e-6f;
            float Kpp = kF8 * (Jpsix * Jpsix + Jpsiy * Jpsiy + Jpsiz * Jpsiz) * 1e-6f;
            float Kfp = kF8 * (Jphix * Jpsix + Jphiy * Jpsiy + Jphiz * Jpsiz) * 1e-6f;
            float M00 = aphi + Kff + kc, M01 = Kfp - kc, M10 = Kfp - kc, M11 = apsi + Kpp + kc + kb;
            float det = M00 * M11 - M01 * M10;
            phi += (Fphi * M11 - M01 * Fpsi) / det;
            psi += (M00 * Fpsi - M10 * Fphi) / det;

            writePoseAndGeom(q, A, outGeom, m, nM, phi, psi, Ax, Ay, Az,
                    bx, by, bz, ex, ey, ez, ux, uy, uz, lb, rF8x, rF8y, rCx, rCy);
        }
    }

    // ===============================================================================================
    // KERNEL 2: CALIBRATED_S2_L40 (float32). Analytic 5×5 movable-pivot solve (stepSup + supForce).
    //   Buffers as fixedStep, plus:
    //   supGeom: supP0(0..2), supUL(3..5), supUT1(6..8), supUT2(9..11)   (comp*N+m)
    //   params extends: 12..25 = supKsoftAx,supKtautAx,supDelta,supKsoftTr,supKfeTr,supRmax,supKfloor,
    //                            supSmoothAx,supSmoothTr,supCompFrac,supFloorZ,supBuckleCrit,supKcompPost,supSmoothBuck
    //   A is the movable pivot P (updated).
    // ===============================================================================================
    public static void calibratedStep(FloatArray q, FloatArray A, FloatArray frame, FloatArray supGeom,
                                      FloatArray F8h, FloatArray params, FloatArray outGeom, IntArray counts) {
        int nM = counts.get(0);
        int tt = counts.get(1), seed = counts.get(2), brown = counts.get(3);
        for (@Parallel int m = 0; m < nM; m++) {
            float phi = q.get(m), psi = q.get(nM + m), thetaS = q.get(2 * nM + m), psiActin = q.get(3 * nM + m);
            float Px = A.get(m), Py = A.get(nM + m), Pz = A.get(2 * nM + m);   // A == P (pivot) at step start
            float bx = frame.get(m),        by = frame.get(nM + m),      bz = frame.get(2 * nM + m);
            float ex = frame.get(3 * nM + m), ey = frame.get(4 * nM + m), ez = frame.get(5 * nM + m);
            float ux = frame.get(6 * nM + m), uy = frame.get(7 * nM + m), uz = frame.get(8 * nM + m);
            float lb = params.get(m);
            float rF8x = params.get(nM + m), rF8y = params.get(2 * nM + m);
            float rCx  = params.get(3 * nM + m), rCy = params.get(4 * nM + m);
            float kF8Code = params.get(5 * nM + m), kc = params.get(6 * nM + m), kb = params.get(7 * nM + m);
            float gP = params.get(8 * nM + m), gPhi = params.get(9 * nM + m), gPsi = params.get(10 * nM + m), dt = params.get(11 * nM + m);
            float f8x = F8h.get(m), f8y = F8h.get(nM + m), f8z = F8h.get(2 * nM + m);

            // --- pre-step geometry (geomC with A=P): C, xF8 ---
            float cphi = (float) Math.cos(phi), sphi = (float) Math.sin(phi);
            float uBx = ux * cphi + bx * sphi, uBy = uy * cphi + by * sphi, uBz = uz * cphi + bz * sphi;
            float Cx = Px + uBx * lb, Cy = Py + uBy * lb, Cz = Pz + uBz * lb;
            float d0x = bx * (rF8x - rCx) + ux * (rF8y - rCy);
            float d0y = by * (rF8x - rCx) + uy * (rF8y - rCy);
            float d0z = bz * (rF8x - rCx) + uz * (rF8y - rCy);
            float cpsi = (float) Math.cos(psi), spsi = (float) Math.sin(psi);
            float xF8x = Cx + (d0x * cpsi + (ey * d0z - ez * d0y) * spsi);
            float xF8y = Cy + (d0y * cpsi + (ez * d0x - ex * d0z) * spsi);
            float xF8z = Cz + (d0z * cpsi + (ex * d0y - ey * d0x) * spsi);

            // --- Jacobians (µm): Jphi = econv×(C-P), Jpsi = econv×(xF8-C) ---
            float cpx = Cx - Px, cpy = Cy - Py, cpz = Cz - Pz;
            float fcx = xF8x - Cx, fcy = xF8y - Cy, fcz = xF8z - Cz;
            float Jphix = ey * cpz - ez * cpy, Jphiy = ez * cpx - ex * cpz, Jphiz = ex * cpy - ey * cpx;
            float Jpsix = ey * fcz - ez * fcy, Jpsiy = ez * fcx - ex * fcz, Jpsiz = ex * fcy - ey * fcx;
            // J columns 3,4 (m/rad): dot(Jphi/Jpsi, {B,E,U})*1e-6
            float J03 = (Jphix * bx + Jphiy * by + Jphiz * bz) * 1e-6f, J04 = (Jpsix * bx + Jpsiy * by + Jpsiz * bz) * 1e-6f;
            float J13 = (Jphix * ex + Jphiy * ey + Jphiz * ez) * 1e-6f, J14 = (Jpsix * ex + Jpsiy * ey + Jpsiz * ez) * 1e-6f;
            float J23 = (Jphix * ux + Jphiy * uy + Jphiz * uz) * 1e-6f, J24 = (Jpsix * ux + Jpsiy * uy + Jpsiz * uz) * 1e-6f;
            // K = kfSI · JᵀJ, J rows [1,0,0,J03,J04]/[0,1,0,J13,J14]/[0,0,1,J23,J24]. SCALARIZED (was float[5][5]):
            // the identity columns make every mixed term an exact 1·x / 0·x float op ⇒ the reduced forms below are
            // bit-identical to the original loop (K00=kfSI·1, K0j=kfSI·J0j, K33/K34/K44 = kfSI·(Σ products, same order)).
            // The kc/kb converter/bind increments (K[3][3]+=kc, K[3][4]-=kc, K[4][3]-=kc, K[4][4]+=kc+kb) and the
            // kAxTan/kTrTan tangent increments are folded into the augmented-matrix assembly below (disjoint entries).
            float kfSI = kF8Code * 1e6f;
            float K00 = kfSI, K11 = kfSI, K22 = kfSI;
            float K03 = kfSI * J03, K04 = kfSI * J04, K13 = kfSI * J13, K14 = kfSI * J14, K23 = kfSI * J23, K24 = kfSI * J24;
            float K33 = kfSI * (J03 * J03 + J13 * J13 + J23 * J23);
            float K34 = kfSI * (J03 * J04 + J13 * J14 + J23 * J24);
            float K44 = kfSI * (J04 * J04 + J14 * J14 + J24 * J24);

            // --- supForce (anisotropic softplus tail law): F(3), kAxTan, kTrTan, kFloorTan ---
            float sp0x = supGeom.get(m),        sp0y = supGeom.get(nM + m),      sp0z = supGeom.get(2 * nM + m);
            float uLx = supGeom.get(3 * nM + m), uLy = supGeom.get(4 * nM + m),  uLz = supGeom.get(5 * nM + m);
            float uT1x = supGeom.get(6 * nM + m), uT1y = supGeom.get(7 * nM + m), uT1z = supGeom.get(8 * nM + m);
            float uT2x = supGeom.get(9 * nM + m), uT2y = supGeom.get(10 * nM + m), uT2z = supGeom.get(11 * nM + m);
            float ksoftAx = params.get(12 * nM + m), ktautAx = params.get(13 * nM + m), supDelta = params.get(14 * nM + m);
            float ksoftTr = params.get(15 * nM + m), kfeTr = params.get(16 * nM + m), supRmax = params.get(17 * nM + m);
            float supKfloor = params.get(18 * nM + m), smoothAx = params.get(19 * nM + m), smoothTr = params.get(20 * nM + m);
            float compFrac = params.get(21 * nM + m), floorZ = params.get(22 * nM + m);
            float buckleCrit = params.get(23 * nM + m), kcompPost = params.get(24 * nM + m), smoothBuck = params.get(25 * nM + m);
            // d = P - supP0
            float dx = Px - sp0x, dy = Py - sp0y, dz = Pz - sp0z;
            double qL = (double) dx * uLx + (double) dy * uLy + (double) dz * uLz;
            double dTx = dx - uLx * qL, dTy = dy - uLy * qL, dTz = dz - uLz * qL;
            double rT = Math.sqrt(dTx * dTx + dTy * dTy + dTz * dTz);
            double ksoft = ksoftAx * 1e6, ktaut = ktautAx * 1e6, ksoftTrD = ksoftTr * 1e6, kfeTrD = kfeTr * 1e6, kfloor = supKfloor * 1e6;
            double dm = supDelta * 1e-6, smA = smoothAx * 1e-6, smT = smoothTr * 1e-6, rMaxm = supRmax * 1e-6;
            double qm = qL * 1e-6;
            double Frest, kAxTan;
            if (qm >= 0) { Frest = ksoft * qm + ktaut * softpos(qm - dm, smA); kAxTan = ksoft + ktaut * softpos_d(qm - dm, smA); }
            else {
                double a = -qm;
                if (buckleCrit > 0) {
                    double k0 = ksoft + ktaut, kpost = kcompPost, acrit = buckleCrit / (1e-30 >= k0 ? 1e-30 : k0), sB = smoothBuck;
                    Frest = -(k0 * a - (k0 - kpost) * softpos(a - acrit, sB)); kAxTan = k0 - (k0 - kpost) * softpos_d(a - acrit, sB);
                } else {
                    double kcc = compFrac; Frest = -(ksoft * kcc * a + ktaut * kcc * softpos(a - dm, smA));
                    kAxTan = ksoft * kcc + ktaut * kcc * softpos_d(a - dm, smA);
                }
            }
            double Fsx = uLx * (-Frest), Fsy = uLy * (-Frest), Fsz = uLz * (-Frest);
            double kTrTan;
            if (rT > 1e-9) {
                double rTm = rT * 1e-6; double Frad = ksoftTrD * rTm + kfeTrD * softpos(rTm - rMaxm, smT);
                kTrTan = ksoftTrD + kfeTrD * softpos_d(rTm - rMaxm, smT);
                Fsx += dTx * (-Frad / rT); Fsy += dTy * (-Frad / rT); Fsz += dTz * (-Frad / rT);
            } else kTrTan = ksoftTrD;
            double zoff = (double) dx * uT2x + (double) dy * uT2y + (double) dz * uT2z;
            double kFloorTan = 0;
            if (zoff < -floorZ) { double pen = (-floorZ - zoff) * 1e-6; Fsx += uT2x * (kfloor * pen); Fsy += uT2y * (kfloor * pen); Fsz += uT2z * (kfloor * pen); kFloorTan = kfloor; }

            // ---- assemble the augmented 5×6 system as 30 NAMED SCALARS (was float[5][6] Msys) ----
            // Diagonal = K + anisotropic tangent (kAxTan/kTrTan/kFloorTan; float+=double compound-narrowing) + γ/dt.
            // Off-diagonals include the kc/kb converter/bind couplings on the [3][4]/[4][3] pair. Bit-identical to the
            // original build: each entry's own float op sequence is preserved; disjoint entries add in any order.
            float aP = gP / dt, aphi = gPhi / dt, apsi = gPsi / dt;
            float a00 = (float) ((double) K00 + kAxTan) + aP;
            float a01 = 0f, a02 = 0f, a03 = K03, a04 = K04;
            float a10 = 0f, a11 = (float) ((double) K11 + kTrTan) + aP, a12 = 0f, a13 = K13, a14 = K14;
            float a20 = 0f, a21 = 0f, a22 = (float) ((double) K22 + (kTrTan + kFloorTan)) + aP, a23 = K23, a24 = K24;
            float a30 = K03, a31 = K13, a32 = K23, a33 = (K33 + kc) + aphi, a34 = K34 - kc;
            float a40 = K04, a41 = K14, a42 = K24, a43 = K34 - kc, a44 = (K44 + (kc + kb)) + apsi;
            // RHS (column 5)
            float th = psi - phi;
            float caF_x = cpy * f8z - cpz * f8y, caF_y = cpz * f8x - cpx * f8z, caF_z = cpx * f8y - cpy * f8x;
            float fcF_x = fcy * f8z - fcz * f8y, fcF_y = fcz * f8x - fcx * f8z, fcF_z = fcx * f8y - fcy * f8x;
            float QphiF8 = (ex * caF_x + ey * caF_y + ez * caF_z) * 1e-6f;
            float QpsiF8 = (ex * fcF_x + ey * fcF_y + ez * fcF_z) * 1e-6f;
            float a05 = (f8x * bx + f8y * by + f8z * bz) + (float) (Fsx * bx + Fsy * by + Fsz * bz);
            float a15 = (f8x * ex + f8y * ey + f8z * ez) + (float) (Fsx * ex + Fsy * ey + Fsz * ez);
            float a25 = (f8x * ux + f8y * uy + f8z * uz) + (float) (Fsx * ux + Fsy * uy + Fsz * uz);
            float a35 = QphiF8 + kc * (th - thetaS);
            float a45 = QpsiF8 - kc * (th - thetaS) - kb * (psi - psiActin);
            if (brown != 0) {
                a05 += (float) brownTorqueD(gP, dt, seed, tt, 0x4F1L);
                a15 += (float) brownTorqueD(gP, dt, seed, tt, 0x4F2L);
                a25 += (float) brownTorqueD(gP, dt, seed, tt, 0x4F3L);
                a35 += (float) brownTorqueD(gPhi, dt, seed, tt, 0x4F4L);
                a45 += (float) brownTorqueD(gPsi, dt, seed, tt, 0x4F5L);
            }

            // ---- HAND-UNROLLED 5×5 Gauss–Jordan with partial pivoting (bit-faithful to gaussJordan5/solveLin):
            //      same pivot rule (first strict-max |·|), same row swaps, same k=c..5 elimination order. ----
            int p; float best, tv, piv, fac, s;
            // column 0
            p = 0; best = fabs(a00);
            tv = fabs(a10); if (tv > best) { best = tv; p = 1; }
            tv = fabs(a20); if (tv > best) { best = tv; p = 2; }
            tv = fabs(a30); if (tv > best) { best = tv; p = 3; }
            tv = fabs(a40); if (tv > best) { best = tv; p = 4; }
            if (p == 1)      { s=a00;a00=a10;a10=s; s=a01;a01=a11;a11=s; s=a02;a02=a12;a12=s; s=a03;a03=a13;a13=s; s=a04;a04=a14;a14=s; s=a05;a05=a15;a15=s; }
            else if (p == 2) { s=a00;a00=a20;a20=s; s=a01;a01=a21;a21=s; s=a02;a02=a22;a22=s; s=a03;a03=a23;a23=s; s=a04;a04=a24;a24=s; s=a05;a05=a25;a25=s; }
            else if (p == 3) { s=a00;a00=a30;a30=s; s=a01;a01=a31;a31=s; s=a02;a02=a32;a32=s; s=a03;a03=a33;a33=s; s=a04;a04=a34;a34=s; s=a05;a05=a35;a35=s; }
            else if (p == 4) { s=a00;a00=a40;a40=s; s=a01;a01=a41;a41=s; s=a02;a02=a42;a42=s; s=a03;a03=a43;a43=s; s=a04;a04=a44;a44=s; s=a05;a05=a45;a45=s; }
            piv = a00;
            fac = a10 / piv; a10 -= fac*a00; a11 -= fac*a01; a12 -= fac*a02; a13 -= fac*a03; a14 -= fac*a04; a15 -= fac*a05;
            fac = a20 / piv; a20 -= fac*a00; a21 -= fac*a01; a22 -= fac*a02; a23 -= fac*a03; a24 -= fac*a04; a25 -= fac*a05;
            fac = a30 / piv; a30 -= fac*a00; a31 -= fac*a01; a32 -= fac*a02; a33 -= fac*a03; a34 -= fac*a04; a35 -= fac*a05;
            fac = a40 / piv; a40 -= fac*a00; a41 -= fac*a01; a42 -= fac*a02; a43 -= fac*a03; a44 -= fac*a04; a45 -= fac*a05;
            // column 1
            p = 1; best = fabs(a11);
            tv = fabs(a21); if (tv > best) { best = tv; p = 2; }
            tv = fabs(a31); if (tv > best) { best = tv; p = 3; }
            tv = fabs(a41); if (tv > best) { best = tv; p = 4; }
            if (p == 2)      { s=a10;a10=a20;a20=s; s=a11;a11=a21;a21=s; s=a12;a12=a22;a22=s; s=a13;a13=a23;a23=s; s=a14;a14=a24;a24=s; s=a15;a15=a25;a25=s; }
            else if (p == 3) { s=a10;a10=a30;a30=s; s=a11;a11=a31;a31=s; s=a12;a12=a32;a32=s; s=a13;a13=a33;a33=s; s=a14;a14=a34;a34=s; s=a15;a15=a35;a35=s; }
            else if (p == 4) { s=a10;a10=a40;a40=s; s=a11;a11=a41;a41=s; s=a12;a12=a42;a42=s; s=a13;a13=a43;a43=s; s=a14;a14=a44;a44=s; s=a15;a15=a45;a45=s; }
            piv = a11;
            fac = a01 / piv; a01 -= fac*a11; a02 -= fac*a12; a03 -= fac*a13; a04 -= fac*a14; a05 -= fac*a15;
            fac = a21 / piv; a21 -= fac*a11; a22 -= fac*a12; a23 -= fac*a13; a24 -= fac*a14; a25 -= fac*a15;
            fac = a31 / piv; a31 -= fac*a11; a32 -= fac*a12; a33 -= fac*a13; a34 -= fac*a14; a35 -= fac*a15;
            fac = a41 / piv; a41 -= fac*a11; a42 -= fac*a12; a43 -= fac*a13; a44 -= fac*a14; a45 -= fac*a15;
            // column 2
            p = 2; best = fabs(a22);
            tv = fabs(a32); if (tv > best) { best = tv; p = 3; }
            tv = fabs(a42); if (tv > best) { best = tv; p = 4; }
            if (p == 3)      { s=a20;a20=a30;a30=s; s=a21;a21=a31;a31=s; s=a22;a22=a32;a32=s; s=a23;a23=a33;a33=s; s=a24;a24=a34;a34=s; s=a25;a25=a35;a35=s; }
            else if (p == 4) { s=a20;a20=a40;a40=s; s=a21;a21=a41;a41=s; s=a22;a22=a42;a42=s; s=a23;a23=a43;a43=s; s=a24;a24=a44;a44=s; s=a25;a25=a45;a45=s; }
            piv = a22;
            fac = a02 / piv; a02 -= fac*a22; a03 -= fac*a23; a04 -= fac*a24; a05 -= fac*a25;
            fac = a12 / piv; a12 -= fac*a22; a13 -= fac*a23; a14 -= fac*a24; a15 -= fac*a25;
            fac = a32 / piv; a32 -= fac*a22; a33 -= fac*a23; a34 -= fac*a24; a35 -= fac*a25;
            fac = a42 / piv; a42 -= fac*a22; a43 -= fac*a23; a44 -= fac*a24; a45 -= fac*a25;
            // column 3
            p = 3; best = fabs(a33);
            tv = fabs(a43); if (tv > best) { best = tv; p = 4; }
            if (p == 4)      { s=a30;a30=a40;a40=s; s=a31;a31=a41;a41=s; s=a32;a32=a42;a42=s; s=a33;a33=a43;a43=s; s=a34;a34=a44;a44=s; s=a35;a35=a45;a45=s; }
            piv = a33;
            fac = a03 / piv; a03 -= fac*a33; a04 -= fac*a34; a05 -= fac*a35;
            fac = a13 / piv; a13 -= fac*a33; a14 -= fac*a34; a15 -= fac*a35;
            fac = a23 / piv; a23 -= fac*a33; a24 -= fac*a34; a25 -= fac*a35;
            fac = a43 / piv; a43 -= fac*a33; a44 -= fac*a34; a45 -= fac*a35;
            // column 4 (no pivot search / swap: only row 4 remains)
            piv = a44;
            fac = a04 / piv; a04 -= fac*a44; a05 -= fac*a45;
            fac = a14 / piv; a14 -= fac*a44; a15 -= fac*a45;
            fac = a24 / piv; a24 -= fac*a44; a25 -= fac*a45;
            fac = a34 / piv; a34 -= fac*a44; a35 -= fac*a45;
            // back-substitution: x[i] = M[i][5] / M[i][i]
            float dqB = a05 / a00, dqE = a15 / a11, dqU = a25 / a22, dqPhi = a35 / a33, dqPsi = a45 / a44;
            // P += B*dqB*1e6 + E*dqE*1e6 + U*dqU*1e6 ; A = P
            Px += (bx * dqB + ex * dqE + ux * dqU) * 1e6f;
            Py += (by * dqB + ey * dqE + uy * dqU) * 1e6f;
            Pz += (bz * dqB + ez * dqE + uz * dqU) * 1e6f;
            phi += dqPhi; psi += dqPsi;

            writePoseAndGeom(q, A, outGeom, m, nM, phi, psi, Px, Py, Pz,
                    bx, by, bz, ex, ey, ez, ux, uy, uz, lb, rF8x, rF8y, rCx, rCy);
        }
    }

    /** Recompute geomC {C,xH,xF8} from the post-step pose and write pose + geometry back to the buffers. */
    private static void writePoseAndGeom(FloatArray q, FloatArray A, FloatArray outGeom, int m, int nM,
            float phi, float psi, float Ax, float Ay, float Az,
            float bx, float by, float bz, float ex, float ey, float ez, float ux, float uy, float uz,
            float lb, float rF8x, float rF8y, float rCx, float rCy) {
        float cphi = (float) Math.cos(phi), sphi = (float) Math.sin(phi);
        float uBx = ux * cphi + bx * sphi, uBy = uy * cphi + by * sphi, uBz = uz * cphi + bz * sphi;
        float Cx = Ax + uBx * lb, Cy = Ay + uBy * lb, Cz = Az + uBz * lb;
        float cpsi = (float) Math.cos(psi), spsi = (float) Math.sin(psi);
        // xF8 = C + rotConv(bhat*(rF8x-rCx)+eup*(rF8y-rCy))
        float d0x = bx * (rF8x - rCx) + ux * (rF8y - rCy), d0y = by * (rF8x - rCx) + uy * (rF8y - rCy), d0z = bz * (rF8x - rCx) + uz * (rF8y - rCy);
        float xF8x = Cx + (d0x * cpsi + (ey * d0z - ez * d0y) * spsi);
        float xF8y = Cy + (d0y * cpsi + (ez * d0x - ex * d0z) * spsi);
        float xF8z = Cz + (d0z * cpsi + (ex * d0y - ey * d0x) * spsi);
        // xH = C - rotConv(bhat*rCx + eup*rCy)
        float rcx = bx * rCx + ux * rCy, rcy = by * rCx + uy * rCy, rcz = bz * rCx + uz * rCy;
        float xHx = Cx - (rcx * cpsi + (ey * rcz - ez * rcy) * spsi);
        float xHy = Cy - (rcy * cpsi + (ez * rcx - ex * rcz) * spsi);
        float xHz = Cz - (rcz * cpsi + (ex * rcy - ey * rcx) * spsi);
        q.set(m, phi); q.set(nM + m, psi);
        A.set(m, Ax); A.set(nM + m, Ay); A.set(2 * nM + m, Az);
        outGeom.set(m, Cx); outGeom.set(nM + m, Cy); outGeom.set(2 * nM + m, Cz);
        outGeom.set(3 * nM + m, xHx); outGeom.set(4 * nM + m, xHy); outGeom.set(5 * nM + m, xHz);
        outGeom.set(6 * nM + m, xF8x); outGeom.set(7 * nM + m, xF8y); outGeom.set(8 * nM + m, xF8z);
    }

    // ===============================================================================================
    // KERNEL 3: EXPLICIT_S2_L40 (double). 14-DOF (3M+2, M=4) linearly-implicit beam Newton step (s2Solve).
    //   nodes:  planar 3*(M+1)=15 comps (node j comp k at (3j+k)*N+m)      updated (nodes 1..M; node0 re-pinned)
    //   frame:  bhat(0..2), econv(3..5), eup(6..8), g4E(9..11), g4Tan(12..14)   (comp*N+m)
    //   q:      [phi, psi, thetaS, psiActin]     phi,psi updated
    //   F8h:    head force (planar x,y,z)
    //   params: lb,rF8x,rF8y,rConvx,rConvy,kF8Code,kconvCode,kbindCode,gammaPhi,gammaPsi,dt,g4ks,g4l0,g4kb,g4floorZ,g4kfloor,g4gammaNode
    //   outGeom: C(0..2), xH(3..5), xF8(6..8)    written
    //   counts: [nM, t, seed, brownian, M]
    //   TODO(GPU): the local double[5][3] node scratch and double[14][15] system scratch must become
    //              per-thread slices of caller-provided buffers (no in-kernel heap on device).
    // ===============================================================================================
    public static void explicitBeamStep(DoubleArray nodes, DoubleArray frame, DoubleArray q, DoubleArray F8h,
                                        DoubleArray params, DoubleArray outGeom, IntArray counts) {
        int nM = counts.get(0);
        int tt = counts.get(1), seed = counts.get(2), brown = counts.get(3), M = counts.get(4);
        for (@Parallel int m = 0; m < nM; m++) {
            double phi = q.get(m), psi = q.get(nM + m), thetaS = q.get(2 * nM + m), psiActin = q.get(3 * nM + m);
            double bx = frame.get(m),        by = frame.get(nM + m),      bz = frame.get(2 * nM + m);
            double ex = frame.get(3 * nM + m), ey = frame.get(4 * nM + m), ez = frame.get(5 * nM + m);
            double ux = frame.get(6 * nM + m), uy = frame.get(7 * nM + m), uz = frame.get(8 * nM + m);
            double gEx = frame.get(9 * nM + m), gEy = frame.get(10 * nM + m), gEz = frame.get(11 * nM + m);
            double gTx = frame.get(12 * nM + m), gTy = frame.get(13 * nM + m), gTz = frame.get(14 * nM + m);
            double lb = params.get(m), rF8x = params.get(nM + m), rF8y = params.get(2 * nM + m);
            double rCx = params.get(3 * nM + m), rCy = params.get(4 * nM + m);
            double kF8Code = params.get(5 * nM + m), kc = params.get(6 * nM + m), kb = params.get(7 * nM + m);
            double gPhi = params.get(8 * nM + m), gPsi = params.get(9 * nM + m), dt = params.get(10 * nM + m);
            double ks = params.get(11 * nM + m), l0 = params.get(12 * nM + m), kbend = params.get(13 * nM + m);
            double floorZ = params.get(14 * nM + m), kfloor = params.get(15 * nM + m), gNode = params.get(16 * nM + m);
            double f8x = F8h.get(m), f8y = F8h.get(nM + m), f8z = F8h.get(2 * nM + m);

            // local node scratch (M+1 nodes) — TODO(GPU): per-thread buffer
            double[][] nd = new double[M + 1][3];
            for (int j = 0; j <= M; j++) { nd[j][0] = nodes.get((3 * j) * nM + m); nd[j][1] = nodes.get((3 * j + 1) * nM + m); nd[j][2] = nodes.get((3 * j + 2) * nM + m); }
            double Px = nd[M][0], Py = nd[M][1], Pz = nd[M][2];

            // geomC with A = node[M] : C, xF8 (used in the F8 coupling block)
            double cphi = Math.cos(phi), sphi = Math.sin(phi);
            double uBx = ux * cphi + bx * sphi, uBy = uy * cphi + by * sphi, uBz = uz * cphi + bz * sphi;
            double Cx = Px + uBx * lb, Cy = Py + uBy * lb, Cz = Pz + uBz * lb;
            double d0x = bx * (rF8x - rCx) + ux * (rF8y - rCy), d0y = by * (rF8x - rCx) + uy * (rF8y - rCy), d0z = bz * (rF8x - rCx) + uz * (rF8y - rCy);
            double cpsi = Math.cos(psi), spsi = Math.sin(psi);
            double xF8x = Cx + (d0x * cpsi + (ey * d0z - ez * d0y) * spsi);
            double xF8y = Cy + (d0y * cpsi + (ez * d0x - ex * d0z) * spsi);
            double xF8z = Cz + (d0z * cpsi + (ex * d0y - ey * d0x) * spsi);

            int nF = 3 * M, n = nF + 2;
            double[][] Msys = new double[n][n + 1];   // TODO(GPU): per-thread scratch

            // beam RHS (internal force on free nodes 1..M) + numeric tangent (central FD of s2NodeForces)
            double[][] Fn = s2NodeForcesK(nd, M, ks, l0, kbend, floorZ, kfloor, ux, uy, uz, gTx, gTy, gTz);
            for (int j = 1; j <= M; j++) for (int k = 0; k < 3; k++) Msys[3 * (j - 1) + k][n] = Fn[j][k];
            double hh = 1e-5;
            for (int jc = 1; jc <= M; jc++) for (int kc2 = 0; kc2 < 3; kc2++) {
                int col = 3 * (jc - 1) + kc2; double sav = nd[jc][kc2];
                nd[jc][kc2] = sav + hh; double[][] Fp = s2NodeForcesK(nd, M, ks, l0, kbend, floorZ, kfloor, ux, uy, uz, gTx, gTy, gTz);
                nd[jc][kc2] = sav - hh; double[][] Fm = s2NodeForcesK(nd, M, ks, l0, kbend, floorZ, kfloor, ux, uy, uz, gTx, gTy, gTz);
                nd[jc][kc2] = sav;
                for (int jr = 1; jr <= M; jr++) for (int kr = 0; kr < 3; kr++)
                    Msys[3 * (jr - 1) + kr][col] += -((Fp[jr][kr] - Fm[jr][kr]) / (2 * hh)) * 1e6;
            }
            // node drag (implicit) + Brownian on free nodes
            double aN = gNode / dt;
            for (int j = 1; j <= M; j++) { int fb = j - 1; for (int k = 0; k < 3; k++) {
                Msys[3 * fb + k][3 * fb + k] += aN;
                if (brown != 0) Msys[3 * fb + k][n] += brownTorqueD(gNode, dt, seed, tt, 0x4711L + ((long) j * 131 + k) * 7919L);
            } }
            // F8 + converter/bind coupling on P=node M and φ,ψ.
            // E = econv, the geometry's own rotation axis (uB = R_econv(phi)*eup, xF8-C = R_econv(psi)*d0).
            // REPAIRED 2026-08-12 together with s2Solve, which had used eup here since the explicit-S2 model was
            // introduced. See docs/motor/RESTORED_3D_HEAD_TILT_DOF.md, "F8 VIRTUAL-WORK AXIS REPAIR".
            int pB = 3 * (M - 1), iPhi = nF, iPsi = nF + 1;
            double cpx = Cx - Px, cpy = Cy - Py, cpz = Cz - Pz;
            double fcx = xF8x - Cx, fcy = xF8y - Cy, fcz = xF8z - Cz;
            double Jphix = ey * cpz - ez * cpy, Jphiy = ez * cpx - ex * cpz, Jphiz = ex * cpy - ey * cpx;
            double Jpsix = ey * fcz - ez * fcy, Jpsiy = ez * fcx - ex * fcz, Jpsiz = ex * fcy - ey * fcx;
            double J03 = Jphix * 1e-6, J04 = Jpsix * 1e-6, J13 = Jphiy * 1e-6, J14 = Jpsiy * 1e-6, J23 = Jphiz * 1e-6, J24 = Jpsiz * 1e-6;
            double kfSI = kF8Code * 1e6;
            double[][] J = {{1, 0, 0, J03, J04}, {0, 1, 0, J13, J14}, {0, 0, 1, J23, J24}};
            int[] map = {pB, pB + 1, pB + 2, iPhi, iPsi};
            for (int i = 0; i < 5; i++) for (int jj = 0; jj < 5; jj++) {
                double kij = kfSI * (J[0][i] * J[0][jj] + J[1][i] * J[1][jj] + J[2][i] * J[2][jj]);
                Msys[map[i]][map[jj]] += kij;
            }
            Msys[iPhi][iPhi] += kc; Msys[iPhi][iPsi] -= kc; Msys[iPsi][iPhi] -= kc; Msys[iPsi][iPsi] += kc + kb;
            double aphi = gPhi / dt, apsi = gPsi / dt; Msys[iPhi][iPhi] += aphi; Msys[iPsi][iPsi] += apsi;
            double th = psi - phi;
            double caF_x = cpy * f8z - cpz * f8y, caF_y = cpz * f8x - cpx * f8z, caF_z = cpx * f8y - cpy * f8x;
            double fcF_x = fcy * f8z - fcz * f8y, fcF_y = fcz * f8x - fcx * f8z, fcF_z = fcx * f8y - fcy * f8x;
            double QphiF8 = (ex * caF_x + ey * caF_y + ez * caF_z) * 1e-6;   // E = econv (see note above)
            double QpsiF8 = (ex * fcF_x + ey * fcF_y + ez * fcF_z) * 1e-6;
            Msys[pB][n] += f8x; Msys[pB + 1][n] += f8y; Msys[pB + 2][n] += f8z;
            Msys[iPhi][n] += QphiF8 + kc * (th - thetaS);
            Msys[iPsi][n] += QpsiF8 - kc * (th - thetaS) - kb * (psi - psiActin);
            if (brown != 0) { Msys[iPhi][n] += brownTorqueD(gPhi, dt, seed, tt, 0x4741L); Msys[iPsi][n] += brownTorqueD(gPsi, dt, seed, tt, 0x4742L); }

            double[] dq = solveN(Msys, n);
            for (int j = 1; j <= M; j++) { int fb = j - 1; for (int k = 0; k < 3; k++) nd[j][k] += dq[3 * fb + k] * 1e6; }
            phi += dq[iPhi]; psi += dq[iPsi];
            nd[0][0] = gEx; nd[0][1] = gEy; nd[0][2] = gEz;   // re-pin the clamped emergence node

            // A = node[M] ; recompute geomC {C,xH,xF8}
            double Ax = nd[M][0], Ay = nd[M][1], Az = nd[M][2];
            double c2 = Math.cos(phi), s2 = Math.sin(phi);
            double uBx2 = ux * c2 + bx * s2, uBy2 = uy * c2 + by * s2, uBz2 = uz * c2 + bz * s2;
            double Cx2 = Ax + uBx2 * lb, Cy2 = Ay + uBy2 * lb, Cz2 = Az + uBz2 * lb;
            double cp2 = Math.cos(psi), sp2 = Math.sin(psi);
            double e0x = bx * (rF8x - rCx) + ux * (rF8y - rCy), e0y = by * (rF8x - rCx) + uy * (rF8y - rCy), e0z = bz * (rF8x - rCx) + uz * (rF8y - rCy);
            double xF8x2 = Cx2 + (e0x * cp2 + (ey * e0z - ez * e0y) * sp2);
            double xF8y2 = Cy2 + (e0y * cp2 + (ez * e0x - ex * e0z) * sp2);
            double xF8z2 = Cz2 + (e0z * cp2 + (ex * e0y - ey * e0x) * sp2);
            double rcx = bx * rCx + ux * rCy, rcy = by * rCx + uy * rCy, rcz = bz * rCx + uz * rCy;
            double xHx = Cx2 - (rcx * cp2 + (ey * rcz - ez * rcy) * sp2);
            double xHy = Cy2 - (rcy * cp2 + (ez * rcx - ex * rcz) * sp2);
            double xHz = Cz2 - (rcz * cp2 + (ex * rcy - ey * rcx) * sp2);

            for (int j = 0; j <= M; j++) { nodes.set((3 * j) * nM + m, nd[j][0]); nodes.set((3 * j + 1) * nM + m, nd[j][1]); nodes.set((3 * j + 2) * nM + m, nd[j][2]); }
            q.set(m, phi); q.set(nM + m, psi);
            outGeom.set(m, Cx2); outGeom.set(nM + m, Cy2); outGeom.set(2 * nM + m, Cz2);
            outGeom.set(3 * nM + m, xHx); outGeom.set(4 * nM + m, xHy); outGeom.set(5 * nM + m, xHz);
            outGeom.set(6 * nM + m, xF8x2); outGeom.set(7 * nM + m, xF8y2); outGeom.set(8 * nM + m, xF8z2);
        }
    }

    /** Bending energy (SI J) — EXACT replica of s2BendEnergy on a local node array. */
    private static double s2BendEnergyK(double[][] nd, int M, double kbend, double gTx, double gTy, double gTz) {
        double E = 0;
        double b0x = nd[1][0] - nd[0][0], b0y = nd[1][1] - nd[0][1], b0z = nd[1][2] - nd[0][2];
        double l0 = Math.sqrt(b0x * b0x + b0y * b0y + b0z * b0z);
        if (l0 > 1e-12) { double c = Math.max(-1, Math.min(1, (gTx * b0x + gTy * b0y + gTz * b0z) / l0)); double th = Math.acos(c); E += 0.5 * kbend * th * th; }
        for (int j = 1; j < M; j++) {
            double ax = nd[j][0] - nd[j - 1][0], ay = nd[j][1] - nd[j - 1][1], az = nd[j][2] - nd[j - 1][2];
            double bx = nd[j + 1][0] - nd[j][0], by = nd[j + 1][1] - nd[j][1], bz = nd[j + 1][2] - nd[j][2];
            double la = Math.sqrt(ax * ax + ay * ay + az * az), lb = Math.sqrt(bx * bx + by * by + bz * bz);
            if (la < 1e-12 || lb < 1e-12) continue;
            double c = Math.max(-1, Math.min(1, (ax * bx + ay * by + az * bz) / (la * lb))); double th = Math.acos(c);
            E += 0.5 * kbend * th * th;
        }
        return E;
    }

    /** Internal beam forces (SI N) on nodes 0..M — EXACT replica of s2NodeForces. Allocates a local
     *  [M+1][3] result (TODO(GPU): per-thread scratch). */
    private static double[][] s2NodeForcesK(double[][] nd, int M, double ks, double l0um, double kbend,
            double floorZ, double kfloor, double ux, double uy, double uz, double gTx, double gTy, double gTz) {
        double l0m = l0um * 1e-6; double[][] F = new double[M + 1][3];
        for (int i = 0; i < M; i++) {
            double bx = nd[i + 1][0] - nd[i][0], by = nd[i + 1][1] - nd[i][1], bz = nd[i + 1][2] - nd[i][2];
            double len = Math.sqrt(bx * bx + by * by + bz * bz); if (len < 1e-15) continue;
            double f = ks * (len * 1e-6 - l0m); double s = 1.0 / len;
            double uxx = bx * s, uyy = by * s, uzz = bz * s;
            F[i][0] += f * uxx; F[i][1] += f * uyy; F[i][2] += f * uzz;
            F[i + 1][0] -= f * uxx; F[i + 1][1] -= f * uyy; F[i + 1][2] -= f * uzz;
        }
        double h = 1e-5;
        for (int j = 0; j <= M; j++) for (int k = 0; k < 3; k++) {
            double sav = nd[j][k];
            nd[j][k] = sav + h; double Ep = s2BendEnergyK(nd, M, kbend, gTx, gTy, gTz);
            nd[j][k] = sav - h; double Em = s2BendEnergyK(nd, M, kbend, gTx, gTy, gTz);
            nd[j][k] = sav; F[j][k] += -((Ep - Em) / (2 * h)) * 1e6;
        }
        for (int j = 0; j <= M; j++) {
            double z = nd[j][0] * ux + nd[j][1] * uy + nd[j][2] * uz;
            if (z < floorZ) { double pen = (floorZ - z) * 1e-6; double fk = kfloor * pen; F[j][0] += fk * ux; F[j][1] += fk * uy; F[j][2] += fk * uz; }
        }
        return F;
    }

    /** Double Gauss–Jordan with partial pivoting — EXACT replica of solveLin (n×(n+1) augmented). */
    private static double[] solveN(double[][] A, int n) {
        double[][] Mm = new double[n][n + 1];
        for (int i = 0; i < n; i++) System.arraycopy(A[i], 0, Mm[i], 0, n + 1);
        for (int c = 0; c < n; c++) {
            int p = c; for (int r = c + 1; r < n; r++) if (Math.abs(Mm[r][c]) > Math.abs(Mm[p][c])) p = r;
            double[] tmp = Mm[c]; Mm[c] = Mm[p]; Mm[p] = tmp; double piv = Mm[c][c];
            for (int r = 0; r < n; r++) { if (r == c) continue; double fac = Mm[r][c] / piv; for (int k = c; k <= n; k++) Mm[r][k] -= fac * Mm[c][k]; }
        }
        double[] x = new double[n]; for (int i = 0; i < n; i++) x[i] = Mm[i][n] / Mm[i][i]; return x;
    }
}
