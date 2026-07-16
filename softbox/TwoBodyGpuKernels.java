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

    // Softplus ramp — EXACT copies (used by the calibrated supForce law).
    static double softpos(double x, double s) {
        if (s <= 0) return Math.max(0, x);
        double z = x / s; if (z > 30) return x; if (z < -30) return s * Math.exp(z);
        return s * Math.log1p(Math.exp(z));
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
            // J row layout: J[k][0..4], rows k=0(B),1(E),2(U); cols 0..2 identity, col3=Jphi·axis, col4=Jpsi·axis
            float[][] J = {
                {1, 0, 0, J03, J04},
                {0, 1, 0, J13, J14},
                {0, 0, 1, J23, J24}};
            float kfSI = kF8Code * 1e6f;
            float[][] K = new float[5][5];
            for (int i = 0; i < 5; i++) for (int j = 0; j < 5; j++)
                K[i][j] = kfSI * (J[0][i] * J[0][j] + J[1][i] * J[1][j] + J[2][i] * J[2][j]);
            K[3][3] += kc; K[3][4] -= kc; K[4][3] -= kc; K[4][4] += kc + kb;

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
                    double k0 = ksoft + ktaut, kpost = kcompPost, acrit = buckleCrit / Math.max(1e-30, k0), sB = smoothBuck;
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

            K[0][0] += kAxTan; K[1][1] += kTrTan; K[2][2] += kTrTan + kFloorTan;
            float aP = gP / dt, aphi = gPhi / dt, apsi = gPsi / dt;
            // M = K + diag(aP,aP,aP,aphi,apsi)
            float[][] Msys = new float[5][6];   // TODO(GPU): per-thread scratch buffer, not a local heap array
            for (int i = 0; i < 5; i++) for (int j = 0; j < 5; j++) Msys[i][j] = K[i][j];
            Msys[0][0] += aP; Msys[1][1] += aP; Msys[2][2] += aP; Msys[3][3] += aphi; Msys[4][4] += apsi;

            // RHS F
            float th = psi - phi;
            float caF_x = cpy * f8z - cpz * f8y, caF_y = cpz * f8x - cpx * f8z, caF_z = cpx * f8y - cpy * f8x;
            float fcF_x = fcy * f8z - fcz * f8y, fcF_y = fcz * f8x - fcx * f8z, fcF_z = fcx * f8y - fcy * f8x;
            float QphiF8 = (ex * caF_x + ey * caF_y + ez * caF_z) * 1e-6f;
            float QpsiF8 = (ex * fcF_x + ey * fcF_y + ez * fcF_z) * 1e-6f;
            Msys[0][5] = (f8x * bx + f8y * by + f8z * bz) + (float) (Fsx * bx + Fsy * by + Fsz * bz);
            Msys[1][5] = (f8x * ex + f8y * ey + f8z * ez) + (float) (Fsx * ex + Fsy * ey + Fsz * ez);
            Msys[2][5] = (f8x * ux + f8y * uy + f8z * uz) + (float) (Fsx * ux + Fsy * uy + Fsz * uz);
            Msys[3][5] = QphiF8 + kc * (th - thetaS);
            Msys[4][5] = QpsiF8 - kc * (th - thetaS) - kb * (psi - psiActin);
            if (brown != 0) {
                Msys[0][5] += (float) brownTorqueD(gP, dt, seed, tt, 0x4F1L);
                Msys[1][5] += (float) brownTorqueD(gP, dt, seed, tt, 0x4F2L);
                Msys[2][5] += (float) brownTorqueD(gP, dt, seed, tt, 0x4F3L);
                Msys[3][5] += (float) brownTorqueD(gPhi, dt, seed, tt, 0x4F4L);
                Msys[4][5] += (float) brownTorqueD(gPsi, dt, seed, tt, 0x4F5L);
            }
            gaussJordan5(Msys);   // solve in place; solution in column 5
            float dqB = Msys[0][5], dqE = Msys[1][5], dqU = Msys[2][5], dqPhi = Msys[3][5], dqPsi = Msys[4][5];
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

    /** In-place float32 Gauss–Jordan with partial pivoting on the augmented [5][6] system (replicates
     *  TwoBodyConverterMotor.solveLin). Solution ends up in column 5 (Msys[i][5] = x[i]). */
    private static void gaussJordan5(float[][] Msys) {
        int n = 5;
        for (int c = 0; c < n; c++) {
            int p = c;
            for (int r = c + 1; r < n; r++) if (Math.abs(Msys[r][c]) > Math.abs(Msys[p][c])) p = r;
            float[] tmp = Msys[c]; Msys[c] = Msys[p]; Msys[p] = tmp;
            float piv = Msys[c][c];
            for (int r = 0; r < n; r++) {
                if (r == c) continue;
                float fac = Msys[r][c] / piv;
                for (int k = c; k <= n; k++) Msys[r][k] -= fac * Msys[c][k];
            }
        }
        for (int i = 0; i < n; i++) Msys[i][n] = Msys[i][n] / Msys[i][i];
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
            // NB: s2Solve uses E = eup (NOT econv) as the generalized-force rotation axis — a genuine
            // divergence from stepC/stepSup (which use econv). Faithful match ⇒ use eup=(ux,uy,uz) here.
            int pB = 3 * (M - 1), iPhi = nF, iPsi = nF + 1;
            double cpx = Cx - Px, cpy = Cy - Py, cpz = Cz - Pz;
            double fcx = xF8x - Cx, fcy = xF8y - Cy, fcz = xF8z - Cz;
            double Jphix = uy * cpz - uz * cpy, Jphiy = uz * cpx - ux * cpz, Jphiz = ux * cpy - uy * cpx;
            double Jpsix = uy * fcz - uz * fcy, Jpsiy = uz * fcx - ux * fcz, Jpsiz = ux * fcy - uy * fcx;
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
            double QphiF8 = (ux * caF_x + uy * caF_y + uz * caF_z) * 1e-6;   // E = eup (see note above)
            double QpsiF8 = (ux * fcF_x + uy * fcF_y + uz * fcF_z) * 1e-6;
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
