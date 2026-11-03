package softbox;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.DoubleArray;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * HEAD CHAIN (noncanonical, DEFAULT-OFF, CPU only; 2026-10-25, jba). Reorders the explicit-S2 motor's head kinematics
 * to the biological chain   motor domain -> converter -> pliant hinge -> lever -> S1-S2 junction -> S2.
 *
 * <h3>Why</h3>
 * The tilt kinematics (matS2SolveStepTilt) build the head as R(econv, psi) . R(t0, chi) with econv a direction of the
 * NECK/S2 frame. The converter hinge therefore belongs to the neck, and the out-of-plane freedom (chi) sits between the
 * converter and the motor domain. With the dock turning the stroke axis onto the filament, a head bound at a side site
 * needs chi = 90 deg, where the converter swing only spins the head about its own bound axis: no axial stroke
 * (placed fixture: -7.4 / -7.1 / -5.0 / -2.0 nm at site azimuth 0/30/60/90 deg; RUN_LOGS/motor_audit/placed_beta).
 * In myosin the converter is a subdomain of the motor domain, so its rotation axis is fixed in the head; the measured
 * compliance sits DISTAL to it, at the pliant region between converter and lever (Billington et al. 2014 J Mol Biol
 * 426:894, isotropic, kappa = 23.4 +- 4.2 pN.nm/rad^2 for detached S1), and the S1-S2 junction is soft (no number).
 *
 * <h3>State (hc, nM rows each)</h3>
 * <pre>
 *   0..2  a   head long axis (converter pole -> F8), world, unit
 *   3..5  c   converter rotation axis, FIXED IN THE HEAD, unit, c . a = 0
 *   6..8  L   lever direction P (S2 tip) -> C (converter pivot), unit
 *   9..11 aT, 12..14 cT  bound target frame, 15 target flag
 *   16 beta (pliant out-of-plane bend, rad), 17 theta (converter angle, rad)   [diagnostic copies]
 *   18..26 backup of a, c, L at the start of the trial solve (steric undo)
 *   27 sgnS: stroke axis s = sgnS (c x a)        28 sx: x = sx (a x c) is the theta = 0 lever direction
 * </pre>
 * No angles are stored, so there is no chart pole. Head points: C = P + lb L, xH = C - rCx a, xF8 = C + (rF8x - rCx) a
 * (the head axis passes through C, H and F8 since r_conv = -r_F8 on the long axis).
 *
 * <h3>Energies</h3>
 * <pre>
 *   converter : 1/2 kc (theta - thetaS)^2,  theta = atan2(-L.a, L.x)        (the lever's angle in the head's converter plane)
 *   pliant    : 1/2 kappaP beta^2,           beta = asin(L.c)                (the lever's bend out of that plane)
 *   bound     : kB (1 - cos phi_rot) = kB/2 (3 - a.aT - c.cT - s.sT)         (full stereospecific dock of the head frame)
 *   S1-S2 junction: the existing terminal bend joint (rest leverRest0, kbend)  -- UNCHANGED, flagged (literature: soft, no number)
 * </pre>
 * The detached head-orientation spring (k_det in the live neck frame) is DROPPED: the head's pose relative to the lever is
 * the converter + pliant hinge, and roll of the whole S1 about the lever axis is free (soft S1-S2 junction).
 *
 * <h3>Coordinates of the implicit step</h3>
 * S2 nodes 1..M (3M), lever rotation about P by lambda_1,2 about tau_1,2 (perpendicular to L; the head translates with C,
 * it does not rotate), head rotation about C by omega (3, world). n = 3M + 5. The 5x5 angular mobility is the exact
 * rigid-body one (lever drag gPhi, head sphere gamma_r about its centre + gamma_t through rho = xH - C), with correlated
 * FDT noise from its Cholesky factor.
 */
public final class HeadChainSystem {
    private HeadChainSystem() {}

    public static boolean ON = false;
    /** pliant-hinge stiffness, pN.nm/rad^2 (Billington 2014, detached S1). */
    public static double KAPPA_PN_NM = 23.4;
    public static final int ROWS = 29;

    // ------------------------------------------------------------------ small scalar helpers (host + kernel)
    // LOWERING-SAFE (2026-10-26, the GPU port): no array allocation, no array-returning helper, no Math.atan2 /
    // Math.asin / Math.IEEEremainder (none lowers on PTX). Angles come from TwoBodyBeamAnalyticGpu.tzAngle (the
    // validated sin/cos form, float-stable at both ends); the 2-pi wrap is explicit. Every kernel below runs
    // unchanged on the CPU runner, so CPU and GPU execute ONE implementation.

    // ---- DOUBLE-PRECISION sin / cos / log from + - * / only (2026-10-26).
    // TornadoVM's PTX backend lowers Math.sin/cos/log/exp on DOUBLES by converting to f32 and emitting
    // sin.approx.f32 / lg2.approx.f32 (PTXFPUnaryIntrinsicNode.shouldConvertInput), i.e. ~1e-7 accuracy on the
    // device and full double on the CPU. The chain has a zero-stiffness, high-mobility mode (head roll about the
    // lever), which amplifies that into O(0.1) CPU/GPU differences in ONE step. These polynomial forms (fdlibm
    // kernel coefficients, Cody-Waite reduction) are accurate to ~1 ulp and execute the SAME arithmetic on both
    // runners, so the CPU/GPU comparison is meaningful at the last-bit level again.
    static double polySin(double r) { double z = r*r;
        return r + r*z*(-1.66666666666666324348e-01 + z*(8.33333333332248946124e-03 + z*(-1.98412698298579493134e-04
                 + z*(2.75573137070700676789e-06 + z*(-2.50507602534068634195e-08 + z*1.58969099521155010221e-10))))); }
    static double polyCos(double r) { double z = r*r;
        return 1.0 - 0.5*z + z*z*(4.16666666666666019037e-02 + z*(-1.38888888888741095749e-03 + z*(2.48015872894767294178e-05
                 + z*(-2.75573143513906633035e-07 + z*(2.08757232129817482790e-09 + z*-1.13596475577881948265e-11))))); }
    static int quad(double x) { double fk = x * 6.36619772367581382433e-01; return (int) (fk >= 0 ? fk + 0.5 : fk - 0.5); }
    static double redq(double x, int k) { return (x - k * 1.57079632673412561417e+00) - k * 6.07710050650619224932e-11; }
    static double dsin(double x) { int k = quad(x); double r = redq(x, k); int q = k & 3;
        return q == 0 ? polySin(r) : (q == 1 ? polyCos(r) : (q == 2 ? -polySin(r) : -polyCos(r))); }
    static double dcos(double x) { int k = quad(x); double r = redq(x, k); int q = k & 3;
        return q == 0 ? polyCos(r) : (q == 1 ? -polySin(r) : (q == 2 ? -polyCos(r) : polySin(r))); }
    /** natural log, x > 0 (reduced to [1/sqrt2, sqrt2), atanh series; the arguments here lie in (1e-8, 1e8)). */
    static double dlog(double x) {
        int e = 0;
        for (int i = 0; i < 1100 && x < 0.7071067811865476; i++) { x *= 2.0; e--; }
        for (int i = 0; i < 1100 && x > 1.4142135623730951; i++) { x *= 0.5; e++; }
        double s = (x - 1.0) / (x + 1.0), z = s*s;
        double p = 1.0 + z*(1.0/3 + z*(1.0/5 + z*(1.0/7 + z*(1.0/9 + z*(1.0/11 + z*(1.0/13 + z*(1.0/15 + z*(1.0/17 + z*(1.0/19 + z*(1.0/21 + z/23))))))))));
        return e * 0.6931471805599453 + 2.0 * s * p;
    }
    /** fdlibm asin/acos rational kernel R(t), t = x^2 or (1-|x|)/2. Pure arithmetic: no trig, no Newton. */
    static double asinR(double t) {
        double p = t*(1.66666666666666657415e-01 + t*(-3.25565818622400915405e-01 + t*(2.01212532134862925881e-01
                 + t*(-4.00555345006794114027e-02 + t*(7.91534994289814532176e-04 + t*3.47933107596021167570e-05)))));
        double q = 1.0 + t*(-2.40339491173441421878e+00 + t*(2.02094576023350569471e+00 + t*(-6.88283971605453293030e-01 + t*7.70381505559019352791e-02)));
        return p / q;
    }
    /** asin(x), |x| <= 1. */
    static double asinH(double x) {
        if (x > 1.0) x = 1.0;
        if (x < -1.0) x = -1.0;
        double ax = x < 0.0 ? -x : x, r;
        if (ax < 0.5) r = ax + ax * asinR(ax * ax);
        else { double t = 0.5 * (1.0 - ax), sq = Math.sqrt(t); r = 1.5707963267948966 - 2.0 * (sq + sq * asinR(t)); }
        return x < 0.0 ? -r : r;
    }
    /** acos(x), |x| <= 1, without the cancellation of pi/2 - asin near |x| = 1. */
    static double dacosH(double x) {
        if (x > 1.0) x = 1.0;
        if (x < -1.0) x = -1.0;
        if (x >= 0.5) { double t = 0.5 * (1.0 - x), sq = Math.sqrt(t); return 2.0 * (sq + sq * asinR(t)); }
        if (x <= -0.5) { double t = 0.5 * (1.0 + x), sq = Math.sqrt(t); return 3.141592653589793 - 2.0 * (sq + sq * asinR(t)); }
        return 1.5707963267948966 - (x + x * asinR(x * x));
    }
    /** the angle with the given sin^2 and cos, accurate at both ends (TwoBodyBeamAnalyticGpu.tzAngle's branch rule). */
    static double angleH(double sin2, double cos) {
        double s = Math.sqrt(sin2); double ac = cos < 0.0 ? -cos : cos;
        if (s <= ac) { double base = asinH(s); return cos >= 0.0 ? base : (3.141592653589793 - base); }
        return dacosH(cos);
    }
    static double a1H(double th) { double t2 = th * th;
        if (th < 1e-3) return 1.0 + t2 / 6.0 + 7.0 * t2 * t2 / 360.0;
        return th / dsin(th); }
    static double a2H(double th) {
        if (th < 5e-2) { double t2 = th * th; return 1.0 / 3.0 + (2.0 / 15.0) * t2 + (2.0 / 63.0) * t2 * t2; }
        double s = dsin(th); return (1.0 - th * dcos(th) / s) / (s * s); }
    /** TwoBodyBeamAnalyticGpu.brownTorqueD with dlog/dcos: the FDT force sqrt(2 kT gamma/dt) g (N). */
    static double brownH(double gamma, double dt, long ep, long t, long salt) {
        return Math.sqrt(2 * Constants.kT * gamma / dt) * gaussD(ep, t, salt);
    }

    /** Component k of v rotated by the axis-angle vector w (Rodrigues). */
    static double rotC(double vx, double vy, double vz, double wx, double wy, double wz, int k) {
        double th = Math.sqrt(wx*wx + wy*wy + wz*wz);
        if (th < 1e-300) return k == 0 ? vx : (k == 1 ? vy : vz);
        double kx = wx/th, ky = wy/th, kz = wz/th, c = dcos(th), s = dsin(th), d = kx*vx + ky*vy + kz*vz;
        if (k == 0) return vx*c + (ky*vz - kz*vy)*s + kx*d*(1-c);
        if (k == 1) return vy*c + (kz*vx - kx*vz)*s + ky*d*(1-c);
        return vz*c + (kx*vy - ky*vx)*s + kz*d*(1-c);
    }
    static double gaussD(long ep, long t, long salt) {
        long h = ((ep * 2654435761L) ^ (t * 40503L) ^ (salt * 0x9E3779B1L)); h ^= (h >>> 13); h *= 0x9E3779B1L; h ^= (h >>> 16);
        double u1 = ((h & 0xFFFFFF) + 1) / 16777217.0; h ^= (h << 7); double u2 = (((h >>> 8) & 0xFFFFFF) + 1) / 16777217.0;
        return Math.sqrt(-2 * dlog(u1)) * dcos(6.283185307179586 * u2);
    }
    /** atan2(y, x) without Math.atan2: the angle from (normalised) sine and cosine, float-stable at both ends. */
    static double angSC(double y, double x) {
        double r = Math.sqrt(x*x + y*y);
        if (!(r > 1e-300)) return 0.0;
        double s = y / r, c = x / r;
        double base = angleH(s*s, c);
        return s < 0.0 ? -base : base;
    }
    /** d wrapped to [-pi, pi] (inputs here lie within +-4 pi). */
    static double wrapPi(double d) {
        double P = 3.141592653589793, P2 = 6.283185307179586;
        if (d > P) d -= P2;
        if (d > P) d -= P2;
        if (d < -P) d += P2;
        if (d < -P) d += P2;
        return d;
    }
    /** theta = atan2(-L.a, L.x), x = sx (a x c): the lever's angle in the head's converter plane. */
    static double thetaOf(double ax, double ay, double az, double cx, double cy, double cz,
                          double Lx, double Ly, double Lz, double sx) {
        double xx = sx*(ay*cz - az*cy), xy = sx*(az*cx - ax*cz), xz = sx*(ax*cy - ay*cx);
        return angSC(-(Lx*ax + Ly*ay + Lz*az), Lx*xx + Ly*xy + Lz*xz);
    }
    /** beta = asin(L.c): the lever's bend out of the converter plane. */
    static double betaOf(double cx, double cy, double cz, double Lx, double Ly, double Lz) {
        double sb = Lx*cx + Ly*cy + Lz*cz; if (sb > 1) sb = 1; if (sb < -1) sb = -1;
        double base = angleH(sb*sb, Math.sqrt(1.0 - sb*sb));
        return sb < 0.0 ? -base : base;
    }
    static double g(DoubleArray hc, int row, int nM, int m) { return hc.get(row * nM + m); }
    static void s3(DoubleArray hc, int row, int nM, int m, double x, double y, double z) {
        hc.set(row * nM + m, x); hc.set((row + 1) * nM + m, y); hc.set((row + 2) * nM + m, z);
    }
    /** Per-motor scratch appended to each motor's (n x n+1) system in sys: see solve(). */
    public static final int SCR = 128;
    public static int sysStride(int M) { int n = 3 * M + 5; return n * (n + 1) + SCR; }

    /** HOST ONLY (init check): {theta, beta}. */
    static double[] thetaBeta(double ax, double ay, double az, double cx, double cy, double cz,
                              double Lx, double Ly, double Lz, double sx) {
        return new double[]{ thetaOf(ax, ay, az, cx, cy, cz, Lx, Ly, Lz, sx), betaOf(cx, cy, cz, Lx, Ly, Lz) };
    }

    // ------------------------------------------------------------------ device upload guard
    /**
     * FIRST task of the device graph. TornadoVM 4.0.1-dev skips the FIRST_EXECUTION upload of any buffer whose FIRST
     * touching task only WRITES it (TornadoGraphBuilder: Access.WRITE_ONLY -> AllocateNode, no copy-in; reproduced by
     * TornadoWriteFirstProbe), leaving every element that task does not write at ZERO on the device. In this graph
     * hcSync writes q / frame first (q rows 2-3 = thetaS, psiActin; frame rows 6-14 = eup, g4E, g4Tan) and, with
     * -devicecull, matCullTag writes restC first (rows 0-2 = the detached rest target). Reading one element of each
     * here makes their first access READ_WRITE, so they are uploaded. No arithmetic effect.
     */
    public static void uploadGuard(DoubleArray q, DoubleArray frame, DoubleArray restC, DoubleArray outGeom,
                                   DoubleArray params, DoubleArray chiHead, IntArray counts) {
        for (@Parallel int i = 0; i < 1; i++) {
            q.set(0, q.get(0)); frame.set(0, frame.get(0)); restC.set(0, restC.get(0));
            outGeom.set(0, outGeom.get(0)); params.set(0, params.get(0)); chiHead.set(0, chiHead.get(0));
        }
    }

    // ------------------------------------------------------------------ geometry
    /** outGeom rows 0..8 (C, xH, xF8) from hc and the S2 tip. Replaces matBeamGeomTilt. */
    public static void geom(DoubleArray nodes, DoubleArray params, DoubleArray hc, IntArray counts, DoubleArray outGeom) {
        int nM = counts.get(0), M = counts.get(2);
        for (@Parallel int m = 0; m < nM; m++) {
            double lb = params.get(m), rF8x = params.get(nM + m), rCx = params.get(3 * nM + m);
            double Px = nodes.get((3*M)*nM+m), Py = nodes.get((3*M+1)*nM+m), Pz = nodes.get((3*M+2)*nM+m);
            double ax = g(hc,0,nM,m), ay = g(hc,1,nM,m), az = g(hc,2,nM,m);
            double Cx = Px + lb*g(hc,6,nM,m), Cy = Py + lb*g(hc,7,nM,m), Cz = Pz + lb*g(hc,8,nM,m);
            double dF = rF8x - rCx;
            outGeom.set(m, Cx); outGeom.set(nM+m, Cy); outGeom.set(2*nM+m, Cz);
            outGeom.set(3*nM+m, Cx - rCx*ax); outGeom.set(4*nM+m, Cy - rCx*ay); outGeom.set(5*nM+m, Cz - rCx*az);
            outGeom.set(6*nM+m, Cx + dF*ax); outGeom.set(7*nM+m, Cy + dF*ay); outGeom.set(8*nM+m, Cz + dF*az);
        }
    }

    /**
     * Mirror the chain into the arrays the rest of the step reads: frame rows 0..2 = stroke axis s, 3..5 = c (so the
     * polarity weight, the loop-2 offset and the dock diagnostics see the head's own frame), params 19..21 = s (g9 copy),
     * q[m] = 0, q[N+m] = theta (every consumer computes theta = q[N] - q[m]), chiHead = beta (diagnostic).
     */
    public static void sync(DoubleArray hc, DoubleArray frame, DoubleArray params, DoubleArray q, DoubleArray chiHead, IntArray counts) {
        int nM = counts.get(0);
        for (@Parallel int m = 0; m < nM; m++) {
            double ax = g(hc,0,nM,m), ay = g(hc,1,nM,m), az = g(hc,2,nM,m), cx = g(hc,3,nM,m), cy = g(hc,4,nM,m), cz = g(hc,5,nM,m);
            double sg = g(hc,27,nM,m);
            double sx = sg*(cy*az - cz*ay), sy = sg*(cz*ax - cx*az), sz = sg*(cx*ay - cy*ax);
            frame.set(m, sx); frame.set(nM+m, sy); frame.set(2*nM+m, sz);
            frame.set(3*nM+m, cx); frame.set(4*nM+m, cy); frame.set(5*nM+m, cz);
            params.set(19*nM+m, sx); params.set(20*nM+m, sy); params.set(21*nM+m, sz);
            double Lx = g(hc,6,nM,m), Ly = g(hc,7,nM,m), Lz = g(hc,8,nM,m);
            double th = thetaOf(ax, ay, az, cx, cy, cz, Lx, Ly, Lz, g(hc,28,nM,m)), be = betaOf(cx, cy, cz, Lx, Ly, Lz);
            hc.set(16*nM+m, be); hc.set(17*nM+m, th);
            q.set(m, 0.0); q.set(nM+m, th); chiHead.set(m, be);
        }
    }

    // ------------------------------------------------------------------ bound target + reaction
    /**
     * For bound motors: the target head frame from the LATCHED site (same reconstruction as siteCoupleStep):
     * aT = -n cos(tilt) + u sin(tilt), cT = sgnS normalize(aT x u) [so sT = u, the stroke axis docks barbed-ward],
     * optionally turned by the stroke skew eps about aT. The exact reaction of the dock torque (start of step) goes to the
     * segment torque slot bondData[d+9..11]. Replaces siteCoupleStep AND matHeadDock.
     * hp: [0] kappaP (N.m/rad^2) [1] kB (N.m/rad^2) [2] tilt (rad) [3] stroke skew (rad)
     */
    public static void siteCouple(IntArray boundSeg, FloatArray bindAzim, FloatArray filUVec, FloatArray filYVec,
                                  FloatArray bondData, DoubleArray hc, DoubleArray hp, DoubleArray snP, IntArray counts) {
        int N = counts.get(0), nSeg = counts.get(3);
        double mirror = snP.get(1), epsBind = snP.get(2), kB = hp.get(1), tilt = hp.get(2), eps = hp.get(3);
        for (@Parallel int m = 0; m < N; m++) {
            hc.set(15*N+m, 0.0);
            int s = boundSeg.get(m);
            if (s < 0) continue;
            double ux = filUVec.get(s), uy = filUVec.get(nSeg+s), uz = filUVec.get(2*nSeg+s);
            double yx = filYVec.get(s), yy = filYVec.get(nSeg+s), yz = filYVec.get(2*nSeg+s);
            double zx = uy*yz - uz*yy, zy = uz*yx - ux*yz, zz = ux*yy - uy*yx;
            double zl = Math.sqrt(zx*zx + zy*zy + zz*zz); if (!(zl > 1e-15)) continue;
            zx /= zl; zy /= zl; zz /= zl;
            double ph = bindAzim.get(m) - mirror*epsBind, cph = dcos(ph), sph = dsin(ph);
            double nx = cph*yx + sph*zx, ny = cph*yy + sph*zy, nz = cph*yz + sph*zz;
            double ct = dcos(tilt), st = dsin(tilt);
            double tx = -nx*ct + ux*st, ty = -ny*ct + uy*st, tz = -nz*ct + uz*st;
            double sg = g(hc,27,N,m);
            double kx = ty*uz - tz*uy, ky = tz*ux - tx*uz, kz = tx*uy - ty*ux;
            double kl = Math.sqrt(kx*kx + ky*ky + kz*kz); if (!(kl > 1e-12)) continue;
            kx = sg*kx/kl; ky = sg*ky/kl; kz = sg*kz/kl;
            if (eps != 0.0) {
                double rx = rotC(kx, ky, kz, eps*tx, eps*ty, eps*tz, 0), ry = rotC(kx, ky, kz, eps*tx, eps*ty, eps*tz, 1),
                       rz = rotC(kx, ky, kz, eps*tx, eps*ty, eps*tz, 2);
                kx = rx; ky = ry; kz = rz;
            }
            s3(hc, 9, N, m, tx, ty, tz); s3(hc, 12, N, m, kx, ky, kz); hc.set(15*N+m, 1.0);
            int d = m * 13;
            bondData.set(d+9,  (float) (bondData.get(d+9)  - dockTq(hc, N, m, kB, 0)));
            bondData.set(d+10, (float) (bondData.get(d+10) - dockTq(hc, N, m, kB, 1)));
            bondData.set(d+11, (float) (bondData.get(d+11) - dockTq(hc, N, m, kB, 2)));
        }
    }
    /** Component k of the torque on the head (N.m) from kB/2 (3 - a.aT - c.cT - s.sT) at the stored frame: kB/2 sum v x vT. */
    static double dockTq(DoubleArray hc, int nM, int m, double kB, int k) {
        double ax = g(hc,0,nM,m), ay = g(hc,1,nM,m), az = g(hc,2,nM,m), cx = g(hc,3,nM,m), cy = g(hc,4,nM,m), cz = g(hc,5,nM,m);
        double Ax = g(hc,9,nM,m), Ay = g(hc,10,nM,m), Az = g(hc,11,nM,m), Kx = g(hc,12,nM,m), Ky = g(hc,13,nM,m), Kz = g(hc,14,nM,m);
        double sx = ay*cz - az*cy, sy = az*cx - ax*cz, sz = ax*cy - ay*cx;
        double Sx = Ay*Kz - Az*Ky, Sy = Az*Kx - Ax*Kz, Sz = Ax*Ky - Ay*Kx;
        double h = 0.5 * kB;
        if (k == 0) return h*((ay*Az - az*Ay) + (cy*Kz - cz*Ky) + (sy*Sz - sz*Sy));
        if (k == 1) return h*((az*Ax - ax*Az) + (cz*Kx - cx*Kz) + (sz*Sx - sx*Sz));
        return h*((ax*Ay - ay*Ax) + (cx*Ky - cy*Kx) + (sx*Sy - sy*Sx));
    }

    /** After stericCheck: restore a, c, L of every motor marked for the redo (mode 2). Nodes are undone by stericCheck. */
    public static void stericUndo(DoubleArray hc, DoubleArray restC, IntArray counts) {
        int nM = counts.get(0);
        for (@Parallel int m = 0; m < nM; m++) {
            if (restC.get(45 * nM + m) == 2.0)
                for (int r = 0; r < 9; r++) hc.set(r * nM + m, hc.get((18 + r) * nM + m));
        }
    }

    // ------------------------------------------------------------------ initialisation from the legacy (phi, psi, chi) pose
    /**
     * Build hc from the current tilt geometry (matBeamGeomTilt must have run, so outGeom C/xF8 are current):
     * a = normalize(xF8 - C), c = econv with its a-component removed (the head-fixed converter axis carried by chi),
     * L = (C - P)/lb. sgnS from the legacy frame (s = bhat for a head pointing along eup); sx chosen so that the chain's
     * theta equals the legacy psi - phi. Returns the worst |theta_chain - (psi - phi)| (rad) for the caller to gate.
     */
    public static double initFromLegacy(ExplicitCompleteMatHarness.ExMat e) {
        int N = e.N, M = e.M;
        if (e.hc == null) { e.hc = new DoubleArray(ROWS * N); e.hc.init(0.0); }
        double worst = 0.0;
        for (int m = 0; m < N; m++) {
            DoubleArray fr = e.convF.get(12 * N + m) != 0.0 ? e.convF : e.frame;
            double bx = fr.get(m), by = fr.get(N+m), bz = fr.get(2*N+m);
            double ex = fr.get(3*N+m), ey = fr.get(4*N+m), ez = fr.get(5*N+m);
            double ux = fr.get(6*N+m), uy = fr.get(7*N+m), uz = fr.get(8*N+m);
            double Cx = e.outGeom.get(m), Cy = e.outGeom.get(N+m), Cz = e.outGeom.get(2*N+m);
            double ax = e.outGeom.get(6*N+m) - Cx, ay = e.outGeom.get(7*N+m) - Cy, az = e.outGeom.get(8*N+m) - Cz;
            double al = Math.sqrt(ax*ax + ay*ay + az*az); ax /= al; ay /= al; az /= al;
            double ea = ex*ax + ey*ay + ez*az;
            double cx = ex - ea*ax, cy = ey - ea*ay, cz = ez - ea*az, cl = Math.sqrt(cx*cx + cy*cy + cz*cz); cx /= cl; cy /= cl; cz /= cl;
            double Px = e.nodes.get((3*M)*N+m), Py = e.nodes.get((3*M+1)*N+m), Pz = e.nodes.get((3*M+2)*N+m);
            double Lx = Cx - Px, Ly = Cy - Py, Lz = Cz - Pz, Ll = Math.sqrt(Lx*Lx + Ly*Ly + Lz*Lz); Lx /= Ll; Ly /= Ll; Lz /= Ll;
            s3(e.hc, 0, N, m, ax, ay, az); s3(e.hc, 3, N, m, cx, cy, cz); s3(e.hc, 6, N, m, Lx, Ly, Lz);
            double sg = bx*(ey*uz - ez*uy) + by*(ez*ux - ex*uz) + bz*(ex*uy - ey*ux);   // bhat . (e x eup)
            e.hc.set(27*N+m, sg >= 0 ? 1.0 : -1.0);
            double thL = e.q.get(N+m) - e.q.get(m);
            double best = 1e9, bestS = 1.0;
            for (double sx = -1.0; sx <= 1.0; sx += 2.0) {
                double th = thetaBeta(ax, ay, az, cx, cy, cz, Lx, Ly, Lz, sx)[0];
                double dd = Math.abs(Math.IEEEremainder(th - thL, 2*Math.PI));
                if (dd < best) { best = dd; bestS = sx; }
            }
            e.hc.set(28*N+m, bestS);
            if (best > worst) worst = best;
        }
        return worst;
    }

    // ------------------------------------------------------------------ the implicit step
    /**
     * One linearly-implicit step of the S2 beam + lever + head (the chain analogue of matS2SolveStepTilt; the S2 stretch,
     * bend, floor, sterics and node blocks are copied verbatim, only the angular block differs). sys: per motor
     * (3M+5)(3M+6). hp as in siteCouple.
     */
    public static void solve(DoubleArray nodes, DoubleArray frame, DoubleArray q, FloatArray bondData,
                             IntArray boundSeg, DoubleArray params, DoubleArray sys, DoubleArray outGeom,
                             FloatArray forceDotFil, FloatArray forceMag, IntArray matc, IntArray counts,
                             DoubleArray hc, DoubleArray hp, DoubleArray restC) {
        int nM = counts.get(0), maxIt = counts.get(1), M = counts.get(2);
        int STRIDE = 13;
        int nF = 3 * M, n = nF + 5, W = n + 1;
        double tol = 3e-7;
        long tt = matc.get(0), seed = matc.get(1); int brownOn = matc.get(2);
        int mPolicy = matc.get(3);
        int myWorker = counts.get(4);
        double kapP = hp.get(0), kB = hp.get(1);
        double kT = Constants.kT;
        for (@Parallel int m = 0; m < nM; m++) {
            if (restC.get(8 * nM + m) != myWorker) continue;
            double ux = frame.get(6 * nM + m), uy = frame.get(7 * nM + m), uz = frame.get(8 * nM + m);   // eup
            double gTx = frame.get(12 * nM + m), gTy = frame.get(13 * nM + m), gTz = frame.get(14 * nM + m);
            double lb = params.get(m), rF8x = params.get(nM + m), rCx = params.get(3 * nM + m);
            double kF8Code = params.get(5 * nM + m), kc = params.get(6 * nM + m);
            double gPhi = params.get(8 * nM + m), gPsi = params.get(9 * nM + m), dt = params.get(10 * nM + m);
            double ks = params.get(11 * nM + m), l0um = params.get(12 * nM + m), kbend = params.get(13 * nM + m);
            double floorZ = params.get(14 * nM + m), kfloor = params.get(15 * nM + m), gNode = params.get(16 * nM + m);
            double gRot = params.get(18 * nM + m);
            double l0m = l0um * 1e-6, aN = gNode / dt;
            int bs = boundSeg.get(m); boolean bnd = bs >= 0; int dB = m * STRIDE;
            int brownM = brownOn;
            if (bnd) { if ((mPolicy & 1) != 0) brownM = 0; }
            else     { if ((mPolicy & 2) != 0) brownM = 0; }
            double kWk = (matc.getSize() > 7 && matc.get(7) != 0) ? restC.get((restC.getSize() / nM - 1) * nM + m) : 0.0;
            boolean wkB = kWk != 0.0;
            int wRowT = restC.getSize() / nM - 1;
            double owx = wkB ? restC.get((wRowT - 3) * nM + m) : 0.0, owy = wkB ? restC.get((wRowT - 2) * nM + m) : 0.0, owz = wkB ? restC.get((wRowT - 1) * nM + m) : 0.0;
            double f8x = (bnd || wkB) ? bondData.get(dB) : 0.0, f8y = (bnd || wkB) ? bondData.get(dB + 1) : 0.0, f8z = (bnd || wkB) ? bondData.get(dB + 2) : 0.0;
            double thetaS = q.get(2 * nM + m);
            double sxT = g(hc,28,nM,m);
            boolean tgt = bnd && hc.get(15 * nM + m) != 0.0;
            // sys per motor: the augmented (n x n+1) system, then SCR doubles of scratch at sc (no in-kernel arrays):
            //   G 0..24 | Lc 25..49 | xi 50..54 | dq 55..59 | jT 60..64 | jB 65..69 | vh 70..84 | JF8 85..108 | jv 109..116
            int base = m * (n * W + SCR), sc = base + n * W;
            boolean stOn = restC.getSize() >= 45 * nM;
            int stMode = stOn ? (int) restC.get(45 * nM + m) : 0;
            if (stMode == 1) continue;
            int stForced = stOn ? (int) restC.get(46 * nM + m) : 0, stAct = 0;
            if (!stOn || stMode == 0) for (int r = 0; r < 9; r++) hc.set((18 + r) * nM + m, hc.get(r * nM + m));
            // head drag split: gamma_t from gPsi = gamma_r + gamma_t |r_conv|^2 ; lever's own drag = gPhi - gamma_t lb^2
            double rhoM = Math.abs(rCx) * 1e-6, lbM = lb * 1e-6;
            double gT = rhoM > 0 ? (gPsi - gRot) / (rhoM * rhoM) : 0.0;
            for (int it = 0; it < maxIt; it++) {
                for (int i = 0; i < n; i++) for (int j = 0; j < W; j++) sys.set(base + i * W + j, 0.0);
                double Px = nodes.get((3 * M) * nM + m), Py = nodes.get((3 * M + 1) * nM + m), Pz = nodes.get((3 * M + 2) * nM + m);
                double ax = g(hc,0,nM,m), ay = g(hc,1,nM,m), az = g(hc,2,nM,m);
                double cx = g(hc,3,nM,m), cy = g(hc,4,nM,m), cz = g(hc,5,nM,m);
                double Lx = g(hc,6,nM,m), Ly = g(hc,7,nM,m), Lz = g(hc,8,nM,m);
                double lpx = lb*Lx, lpy = lb*Ly, lpz = lb*Lz;                         // C - P (um)
                double Cx = Px + lpx, Cy = Py + lpy, Cz = Pz + lpz;
                double dF = rF8x - rCx;
                double fcx = dF*ax, fcy = dF*ay, fcz = dF*az;                         // xF8 - C
                double rpx = -rCx*ax, rpy = -rCx*ay, rpz = -rCx*az;                   // xH - C
                double Hx = Cx + rpx, Hy = Cy + rpy, Hz = Cz + rpz;
                // tangent basis of the lever: tau1, tau2 perpendicular to L
                double hx0 = Math.abs(Lx) < 0.9 ? 1.0 : 0.0, hy0 = Math.abs(Lx) < 0.9 ? 0.0 : 1.0;
                double t1x = Ly*0.0 - Lz*hy0, t1y = Lz*hx0 - Lx*0.0, t1z = Lx*hy0 - Ly*hx0;
                double t1l = Math.sqrt(t1x*t1x + t1y*t1y + t1z*t1z); t1x /= t1l; t1y /= t1l; t1z /= t1l;
                double t2x = Ly*t1z - Lz*t1y, t2y = Lz*t1x - Lx*t1z, t2z = Lx*t1y - Ly*t1x;
                // d(point)/d lambda_k for anything carried rigidly by C: tau_k x (C - P)
                double v1x = t1y*lpz - t1z*lpy, v1y = t1z*lpx - t1x*lpz, v1z = t1x*lpy - t1y*lpx;
                double v2x = t2y*lpz - t2z*lpy, v2y = t2z*lpx - t2x*lpz, v2z = t2x*lpy - t2y*lpx;
                // STRETCH
                for (int i = 0; i < M; i++) {
                    double ex2 = nodes.get((3*(i+1))*nM+m) - nodes.get((3*i)*nM+m);
                    double ey2 = nodes.get((3*(i+1)+1)*nM+m) - nodes.get((3*i+1)*nM+m);
                    double ez2 = nodes.get((3*(i+1)+2)*nM+m) - nodes.get((3*i+2)*nM+m);
                    double len = Math.sqrt(ex2*ex2+ey2*ey2+ez2*ez2); if (len < 1e-15) continue;
                    double s = 1.0/len, uxx = ex2*s, uyy = ey2*s, uzz = ez2*s;
                    double lenM = len*1e-6, tS = ks*(lenM - l0m), tL = tS/lenM;
                    int ri = (i>=1)?(i-1)*3:-1000, rj = i*3;
                    addF(sys,base,W,n, ri+0, tS*uxx); addF(sys,base,W,n, ri+1, tS*uyy); addF(sys,base,W,n, ri+2, tS*uzz);
                    addF(sys,base,W,n, rj+0,-tS*uxx); addF(sys,base,W,n, rj+1,-tS*uyy); addF(sys,base,W,n, rj+2,-tS*uzz);
                    for (int p=0;p<3;p++) for(int qq=0;qq<3;qq++){
                        double up = (p==0)?uxx:((p==1)?uyy:uzz), uq=(qq==0)?uxx:((qq==1)?uyy:uzz);
                        double id=(p==qq)?1.0:0.0; double kb2 = ks*up*uq + tL*(id-up*uq);
                        addK(sys,base,W, ri+p, ri+qq, kb2); addK(sys,base,W, rj+p, rj+qq, kb2);
                        addK(sys,base,W, ri+p, rj+qq,-kb2); addK(sys,base,W, rj+p, ri+qq,-kb2);
                    }
                }
                // BEND clamped joint 0 (with the swivel / base-hinge options), verbatim
                {
                    double kbend0 = kbend * (matc.getSize() > 5 ? matc.get(5) * 1.0e-3 : 1.0);
                    double b0x = nodes.get(3*nM+m)-nodes.get(m), b0y = nodes.get((4)*nM+m)-nodes.get(nM+m), b0z = nodes.get(5*nM+m)-nodes.get(2*nM+m);
                    double lbb = Math.sqrt(b0x*b0x+b0y*b0y+b0z*b0z);
                    double swAz = 0.0, azx = 0.0, azy = 0.0, azz = 0.0;
                    double tgx = gTx, tgy = gTy, tgz = gTz;
                    if (matc.getSize() > 6 && matc.get(6) == 1 && lbb > 1e-12) {
                        double bu = b0x*ux + b0y*uy + b0z*uz;
                        double hx = b0x - bu*ux, hy = b0y - bu*uy, hz = b0z - bu*uz;
                        double hn = Math.sqrt(hx*hx + hy*hy + hz*hz);
                        if (hn > 1e-3 * lbb) {
                            tgx = hx/hn; tgy = hy/hn; tgz = hz/hn;
                            azx = uy*tgz - uz*tgy; azy = uz*tgx - ux*tgz; azz = ux*tgy - uy*tgx;
                            swAz = 1.0 / (hn * lbb);
                        }
                    }
                    if (lbb > 1e-12) {
                        double ilb=1.0/lbb, c = tgx*b0x+tgy*b0y+tgz*b0z; c*=ilb; if(c>1)c=1; if(c<-1)c=-1;
                        double th = dacosH(c), A1=a1H(th), A2=a2H(th); double clb2=c*ilb*ilb;
                        double g0x=tgx*ilb-clb2*b0x, g0y=tgy*ilb-clb2*b0y, g0z=tgz*ilb-clb2*b0z;
                        int r1=0;
                        addF(sys,base,W,n, r1+0, kbend0*A1*1e6*g0x); addF(sys,base,W,n, r1+1, kbend0*A1*1e6*g0y); addF(sys,base,W,n, r1+2, kbend0*A1*1e6*g0z);
                        double ilb2=ilb*ilb, ilb3=ilb2*ilb, ilb4=ilb2*ilb2;
                        for (int p=0;p<3;p++) for(int qq=0;qq<3;qq++){
                            double b0p=(p==0)?b0x:((p==1)?b0y:b0z), b0q=(qq==0)?b0x:((qq==1)?b0y:b0z);
                            double tp=(p==0)?tgx:((p==1)?tgy:tgz), tq=(qq==0)?tgx:((qq==1)?tgy:tgz);
                            double id=(p==qq)?1.0:0.0;
                            double ap=(p==0)?azx:((p==1)?azy:azz), aq=(qq==0)?azx:((qq==1)?azy:azz);
                            double Hc = -(b0p*tq+tp*b0q)*ilb3 - c*id*ilb2 + 3.0*c*b0p*b0q*ilb4 + swAz*ap*aq;
                            double gp=(p==0)?g0x:((p==1)?g0y:g0z), gq=(qq==0)?g0x:((qq==1)?g0y:g0z);
                            addK(sys,base,W, r1+p, r1+qq, 1e12*kbend0*(A2*gp*gq - A1*Hc));
                        }
                    }
                }
                // BEND interior joints, verbatim
                for (int j=1;j<M;j++){
                    double ex2 = nodes.get((3*j)*nM+m)-nodes.get((3*(j-1))*nM+m);
                    double ey2 = nodes.get((3*j+1)*nM+m)-nodes.get((3*(j-1)+1)*nM+m);
                    double ez2 = nodes.get((3*j+2)*nM+m)-nodes.get((3*(j-1)+2)*nM+m);
                    double bxx = nodes.get((3*(j+1))*nM+m)-nodes.get((3*j)*nM+m);
                    double byy = nodes.get((3*(j+1)+1)*nM+m)-nodes.get((3*j+1)*nM+m);
                    double bzz = nodes.get((3*(j+1)+2)*nM+m)-nodes.get((3*j+2)*nM+m);
                    double la = Math.sqrt(ex2*ex2+ey2*ey2+ez2*ez2), lbn = Math.sqrt(bxx*bxx+byy*byy+bzz*bzz);
                    if (la<1e-12 || lbn<1e-12) continue;
                    double ila=1.0/la, ilb=1.0/lbn, iab=ila*ilb;
                    double c = (ex2*bxx+ey2*byy+ez2*bzz)*iab; if(c>1)c=1; if(c<-1)c=-1;
                    double th=dacosH(c), A1=a1H(th), A2=a2H(th);
                    double cla2=c*ila*ila, clb2=c*ilb*ilb;
                    double Dx=bxx*iab-cla2*ex2, Dy=byy*iab-cla2*ey2, Dz=bzz*iab-cla2*ez2;
                    double Ex=ex2*iab-clb2*bxx, Ey=ey2*iab-clb2*byy, Ez=ez2*iab-clb2*bzz;
                    double gc0x=-Dx,gc0y=-Dy,gc0z=-Dz, gc1x=Dx-Ex,gc1y=Dy-Ey,gc1z=Dz-Ez, gc2x=Ex,gc2y=Ey,gc2z=Ez;
                    int rJm1=(j-1>=1)?(j-2)*3:-1000, rJ=(j-1)*3, rJp1=j*3;
                    addF(sys,base,W,n, rJm1+0,kbend*A1*1e6*gc0x); addF(sys,base,W,n, rJm1+1,kbend*A1*1e6*gc0y); addF(sys,base,W,n, rJm1+2,kbend*A1*1e6*gc0z);
                    addF(sys,base,W,n, rJ+0,  kbend*A1*1e6*gc1x); addF(sys,base,W,n, rJ+1,  kbend*A1*1e6*gc1y); addF(sys,base,W,n, rJ+2,  kbend*A1*1e6*gc1z);
                    addF(sys,base,W,n, rJp1+0,kbend*A1*1e6*gc2x); addF(sys,base,W,n, rJp1+1,kbend*A1*1e6*gc2y); addF(sys,base,W,n, rJp1+2,kbend*A1*1e6*gc2z);
                    double ila2=ila*ila, ila3=ila2*ila, ila4=ila2*ila2, ilb2=ilb*ilb, ilb3=ilb2*ilb, ilb4=ilb2*ilb2;
                    for (int ai=0; ai<3; ai++){
                        int rA = (ai==0)?rJm1:((ai==1)?rJ:rJp1);
                        double saA=(ai==0)?-1.0:((ai==1)?1.0:0.0), sbA=(ai==0)?0.0:((ai==1)?-1.0:1.0);
                        for (int bi=0; bi<3; bi++){
                            int rB = (bi==0)?rJm1:((bi==1)?rJ:rJp1);
                            double saB=(bi==0)?-1.0:((bi==1)?1.0:0.0), sbB=(bi==0)?0.0:((bi==1)?-1.0:1.0);
                            for (int p=0;p<3;p++){
                                double ap=(p==0)?ex2:((p==1)?ey2:ez2), bp=(p==0)?bxx:((p==1)?byy:bzz);
                                double gAp=(p==0)?((ai==0)?gc0x:((ai==1)?gc1x:gc2x)):((p==1)?((ai==0)?gc0y:((ai==1)?gc1y:gc2y)):((ai==0)?gc0z:((ai==1)?gc1z:gc2z)));
                                for (int qq=0;qq<3;qq++){
                                    double aq=(qq==0)?ex2:((qq==1)?ey2:ez2), bq=(qq==0)?bxx:((qq==1)?byy:bzz);
                                    double id=(p==qq)?1.0:0.0;
                                    double Haa=-(ap*bq+bp*aq)*ila3*ilb - c*id*ila2 + 3.0*c*ap*aq*ila4;
                                    double Hbb=-(bp*aq+ap*bq)*ilb3*ila - c*id*ilb2 + 3.0*c*bp*bq*ilb4;
                                    double Hab_pq= id*iab - bp*bq*ila*ilb3 - ap*aq*ila3*ilb + c*ap*bq*ila2*ilb2;
                                    double Hab_qp= id*iab - bq*bp*ila*ilb3 - aq*ap*ila3*ilb + c*aq*bp*ila2*ilb2;
                                    double Hc = saA*saB*Haa + sbA*sbB*Hbb + saA*sbB*Hab_pq + sbA*saB*Hab_qp;
                                    double gBq=(qq==0)?((bi==0)?gc0x:((bi==1)?gc1x:gc2x)):((qq==1)?((bi==0)?gc0y:((bi==1)?gc1y:gc2y)):((bi==0)?gc0z:((bi==1)?gc1z:gc2z)));
                                    addK(sys,base,W, rA+p, rB+qq, 1e12*kbend*(A2*gAp*gBq - A1*Hc));
                                }
                            }
                        }
                    }
                }
                // FLOOR (S2 nodes), verbatim
                for (int j=1;j<=M;j++){
                    double z = nodes.get((3*j)*nM+m)*ux + nodes.get((3*j+1)*nM+m)*uy + nodes.get((3*j+2)*nM+m)*uz;
                    if (z < floorZ){ double fk = kfloor*(floorZ - z)*1e-6; int r=(j-1)*3;
                        addF(sys,base,W,n, r+0, fk*ux); addF(sys,base,W,n, r+1, fk*uy); addF(sys,base,W,n, r+2, fk*uz);
                        for(int p=0;p<3;p++){ double ep=(p==0)?ux:((p==1)?uy:uz);
                            for(int qq=0;qq<3;qq++){ double eq=(qq==0)?ux:((qq==1)?uy:uz); addK(sys,base,W, r+p, r+qq, kfloor*ep*eq); } }
                    }
                }
                // the 8 chain DOFs a head/lever point depends on: P (sB..sB+2), lambda1,2, omega 1..3; jd(ii) = ii<3 ? sB+ii : nF+ii-3
                int sB = 3*(M-1), iL1 = nF, iW = nF + 2;
                int JV = sc + 109;
                // ---- MOTOR-ACTIN EXCLUDED VOLUME + HEAD/LEVER FLOOR (same contacts as the tilt solve, chain Jacobians) --
                if (stOn) {
                    double kSt = restC.get(27*nM+m), rSH = restC.get(24*nM+m), rSL = restC.get(25*nM+m), rSS = restC.get(26*nM+m);
                    double hfR = restC.get(28*nM+m), lfR = restC.get(29*nM+m);
                    int skipSlot = (int) restC.get(23*nM+m);
                    for (int slot = 0; slot < 2; slot++) {
                        int r0 = 9 + 7*slot;
                        double hA = restC.get((r0+6)*nM+m);
                        if (!(hA > 0.0) || !(kSt > 0.0)) continue;
                        double acx = restC.get(r0*nM+m), acy = restC.get((r0+1)*nM+m), acz = restC.get((r0+2)*nM+m);
                        double aux2 = restC.get((r0+3)*nM+m), auy2 = restC.get((r0+4)*nM+m), auz2 = restC.get((r0+5)*nM+m);
                        double a0x = acx - hA*aux2, a0y = acy - hA*auy2, a0z = acz - hA*auz2;
                        double d2x = 2.0*hA*aux2, d2y = 2.0*hA*auy2, d2z = 2.0*hA*auz2, eA = d2x*d2x + d2y*d2y + d2z*d2z;
                        for (int bI = 0; bI <= M + 1; bI++) {
                            if (bI == M + 1 && slot == skipSlot) continue;
                            int cbit = 1 << (slot*(M+2) + bI);
                            double p0x, p0y, p0z, p1x2, p1y2, p1z2, rSum;
                            if (bI < M) {
                                p0x = nodes.get((3*bI)*nM+m); p0y = nodes.get((3*bI+1)*nM+m); p0z = nodes.get((3*bI+2)*nM+m);
                                p1x2 = nodes.get((3*(bI+1))*nM+m); p1y2 = nodes.get((3*(bI+1)+1)*nM+m); p1z2 = nodes.get((3*(bI+1)+2)*nM+m);
                                rSum = rSS;
                            } else if (bI == M) { p0x = Px; p0y = Py; p0z = Pz; p1x2 = Cx; p1y2 = Cy; p1z2 = Cz; rSum = rSL; }
                            else { p0x = Hx; p0y = Hy; p0z = Hz; p1x2 = Hx; p1y2 = Hy; p1z2 = Hz; rSum = rSH; }
                            double d1x = p1x2 - p0x, d1y = p1y2 - p0y, d1z = p1z2 - p0z;
                            double wx = p0x - a0x, wy = p0y - a0y, wz = p0z - a0z;
                            double aa = d1x*d1x + d1y*d1y + d1z*d1z, bb = d1x*d2x + d1y*d2y + d1z*d2z;
                            double cc = d1x*wx + d1y*wy + d1z*wz, ff = d2x*wx + d2y*wy + d2z*wz;
                            double sb = 0.0, ta;
                            if (aa > 1e-24) {
                                double den = aa*eA - bb*bb;
                                if (den > 1e-30) { sb = (bb*ff - cc*eA)/den; if (sb < 0) sb = 0; if (sb > 1) sb = 1; }
                                ta = (bb*sb + ff)/eA;
                                if (ta < 0) { ta = 0; sb = -cc/aa; if (sb < 0) sb = 0; if (sb > 1) sb = 1; }
                                else if (ta > 1) { ta = 1; sb = (bb - cc)/aa; if (sb < 0) sb = 0; if (sb > 1) sb = 1; }
                            } else { ta = ff/eA; if (ta < 0) ta = 0; if (ta > 1) ta = 1; }
                            double nx = p0x + sb*d1x - (a0x + ta*d2x), ny = p0y + sb*d1y - (a0y + ta*d2y), nz = p0z + sb*d1z - (a0z + ta*d2z);
                            double dist = Math.sqrt(nx*nx + ny*ny + nz*nz);
                            if (dist < 1e-12) continue;
                            double del = rSum - dist;
                            double idn = 1.0/dist; nx *= idn; ny *= idn; nz *= idn;
                            if (del > 0.0) stAct |= cbit;
                            boolean act = del > 0.0 || (stMode == 2 && (stForced & cbit) != 0);
                            if (!act) continue;
                            double fm = kSt*del*1e-6, Fx = fm*nx, Fy = fm*ny, Fz = fm*nz;
                            if (bI < M) {
                                int ra = (bI >= 1) ? (bI-1)*3 : -1000, rb = bI*3;
                                double wa = 1.0 - sb, wb = sb;
                                addF(sys,base,W,n, ra+0, wa*Fx); addF(sys,base,W,n, ra+1, wa*Fy); addF(sys,base,W,n, ra+2, wa*Fz);
                                addF(sys,base,W,n, rb+0, wb*Fx); addF(sys,base,W,n, rb+1, wb*Fy); addF(sys,base,W,n, rb+2, wb*Fz);
                                for (int pp=0;pp<3;pp++){ double np=(pp==0)?nx:((pp==1)?ny:nz);
                                    for (int qq=0;qq<3;qq++){ double nq=(qq==0)?nx:((qq==1)?ny:nz); double kv = kSt*np*nq;
                                        addK(sys,base,W, ra+pp, ra+qq, wa*wa*kv); addK(sys,base,W, rb+pp, rb+qq, wb*wb*kv);
                                        addK(sys,base,W, ra+pp, rb+qq, wa*wb*kv); addK(sys,base,W, rb+pp, ra+qq, wa*wb*kv); } }
                            } else {
                                double jsb = (bI == M + 1) ? 1.0 : sb;
                                sys.set(JV, nx); sys.set(JV+1, ny); sys.set(JV+2, nz);
                                sys.set(JV+3, jsb*(v1x*nx + v1y*ny + v1z*nz)*1e-6); sys.set(JV+4, jsb*(v2x*nx + v2y*ny + v2z*nz)*1e-6);
                                if (bI == M + 1) {   // (e_j x rho) . n = (rho x n)_j
                                    sys.set(JV+5, (rpy*nz - rpz*ny)*1e-6); sys.set(JV+6, (rpz*nx - rpx*nz)*1e-6); sys.set(JV+7, (rpx*ny - rpy*nx)*1e-6);
                                } else { sys.set(JV+5, 0.0); sys.set(JV+6, 0.0); sys.set(JV+7, 0.0); }
                                for (int ii = 0; ii < 8; ii++) {
                                    int di = ii < 3 ? sB + ii : nF + ii - 3; double ji = sys.get(JV + ii);
                                    addF(sys,base,W,n, di, (ii < 3 ? (ii==0?Fx:(ii==1?Fy:Fz)) : fm*ji));
                                    for (int jj = 0; jj < 8; jj++) addK(sys,base,W, di, jj < 3 ? sB + jj : nF + jj - 3, kSt*ji*sys.get(JV + jj));
                                }
                            }
                        }
                    }
                    for (int fI = 0; fI < 2; fI++) {
                        double rF = (fI == 0) ? hfR : lfR;
                        if (!(rF > 0.0)) continue;
                        int cbit = 1 << (2*(M+2) + fI);
                        double xx = (fI == 0) ? Hx : Cx, xy = (fI == 0) ? Hy : Cy, xz = (fI == 0) ? Hz : Cz;
                        double zz = xx*ux + xy*uy + xz*uz;
                        double del = floorZ - (zz - rF);
                        if (del > 0.0) stAct |= cbit;
                        if (!(del > 0.0 || (stMode == 2 && (stForced & cbit) != 0))) continue;
                        double fm = kfloor*del*1e-6;
                        sys.set(JV, ux); sys.set(JV+1, uy); sys.set(JV+2, uz);
                        sys.set(JV+3, (v1x*ux + v1y*uy + v1z*uz)*1e-6); sys.set(JV+4, (v2x*ux + v2y*uy + v2z*uz)*1e-6);
                        if (fI == 0) { sys.set(JV+5, (rpy*uz - rpz*uy)*1e-6); sys.set(JV+6, (rpz*ux - rpx*uz)*1e-6); sys.set(JV+7, (rpx*uy - rpy*ux)*1e-6); }
                        else { sys.set(JV+5, 0.0); sys.set(JV+6, 0.0); sys.set(JV+7, 0.0); }
                        for (int ii = 0; ii < 8; ii++) {
                            int di = ii < 3 ? sB + ii : nF + ii - 3; double ji = sys.get(JV + ii);
                            addF(sys,base,W,n, di, fm*ji);
                            for (int jj = 0; jj < 8; jj++) addK(sys,base,W, di, jj < 3 ? sB + jj : nF + jj - 3, kfloor*ji*sys.get(JV + jj));
                        }
                    }
                    if (stMode == 0) restC.set(47*nM+m, (double) stAct);
                }
                // node drag + node Brownian, verbatim
                for (int r=0;r<nF;r++) addK(sys,base,W, r, r, aN);
                if (brownM != 0) for (int j=1;j<=M;j++){ int fb=(j-1)*3;
                    for (int k=0;k<3;k++){ long salt = 0x4811L + ((long)m*1009 + (long)j*131 + k)*7919L;
                        addF(sys,base,W,n, fb+k, brownH(gNode, dt, seed, tt, salt)); } }
                // ---- F8 (strong bond or weak tether) at the force point C + fc + o --------------------------
                {
                    double fwx = fcx + owx, fwy = fcy + owy, fwz = fcz + owz;
                    int J0 = sc + 85, J1 = sc + 93, J2 = sc + 101;   // rows x, y, z of d(F8 point)/d(8 DOFs)
                    sys.set(J0, 1.0); sys.set(J0+1, 0.0); sys.set(J0+2, 0.0); sys.set(J0+3, v1x*1e-6); sys.set(J0+4, v2x*1e-6); sys.set(J0+5, 0.0);       sys.set(J0+6, fwz*1e-6);  sys.set(J0+7, -fwy*1e-6);
                    sys.set(J1, 0.0); sys.set(J1+1, 1.0); sys.set(J1+2, 0.0); sys.set(J1+3, v1y*1e-6); sys.set(J1+4, v2y*1e-6); sys.set(J1+5, -fwz*1e-6); sys.set(J1+6, 0.0);       sys.set(J1+7, fwx*1e-6);
                    sys.set(J2, 0.0); sys.set(J2+1, 0.0); sys.set(J2+2, 1.0); sys.set(J2+3, v1z*1e-6); sys.set(J2+4, v2z*1e-6); sys.set(J2+5, fwy*1e-6);  sys.set(J2+6, -fwx*1e-6); sys.set(J2+7, 0.0);
                    double kfSI = (wkB ? kWk : kF8Code) * 1e6;
                    for (int ii = 0; ii < 8; ii++) {
                        int di = ii < 3 ? sB + ii : nF + ii - 3;
                        double a0 = sys.get(J0 + ii), a1 = sys.get(J1 + ii), a2 = sys.get(J2 + ii);
                        addF(sys,base,W,n, di, a0*f8x + a1*f8y + a2*f8z);
                        for (int jj = 0; jj < 8; jj++)
                            addK(sys,base,W, di, jj < 3 ? sB + jj : nF + jj - 3, kfSI*(a0*sys.get(J0 + jj) + a1*sys.get(J1 + jj) + a2*sys.get(J2 + jj)));
                    }
                }
                // ---- converter (theta) + pliant hinge (beta): gradients over (lambda1, lambda2, omega) ---------
                {
                    double xxT = sxT*(ay*cz - az*cy), xyT = sxT*(az*cx - ax*cz), xzT = sxT*(ax*cy - ay*cx);
                    double X = Lx*xxT + Ly*xyT + Lz*xzT, Y = -(Lx*ax + Ly*ay + Lz*az);
                    double r2 = X*X + Y*Y; if (r2 < 1e-24) r2 = 1e-24;
                    double th = angSC(Y, X);
                    double dTx = (-X*ax - Y*xxT)/r2, dTy = (-X*ay - Y*xyT)/r2, dTz = (-X*az - Y*xzT)/r2;   // d theta / d L
                    double be = betaOf(cx, cy, cz, Lx, Ly, Lz), cb = dcos(be); if (cb < 1e-3) cb = 1e-3;
                    double dBx = cx/cb, dBy = cy/cb, dBz = cz/cb;                                          // d beta / d L
                    double hTx = Ly*dTz - Lz*dTy, hTy = Lz*dTx - Lx*dTz, hTz = Lx*dTy - Ly*dTx;            // G = L x dL
                    double hBx = Ly*dBz - Lz*dBy, hBy = Lz*dBx - Lx*dBz, hBz = Lx*dBy - Ly*dBx;
                    double dth = wrapPi(th - thetaS);
                    int JT = sc + 60, JB = sc + 65;
                    sys.set(JT, hTx*t1x + hTy*t1y + hTz*t1z); sys.set(JT+1, hTx*t2x + hTy*t2y + hTz*t2z);
                    sys.set(JT+2, -hTx); sys.set(JT+3, -hTy); sys.set(JT+4, -hTz);
                    sys.set(JB, hBx*t1x + hBy*t1y + hBz*t1z); sys.set(JB+1, hBx*t2x + hBy*t2y + hBz*t2z);
                    sys.set(JB+2, -hBx); sys.set(JB+3, -hBy); sys.set(JB+4, -hBz);
                    for (int ii = 0; ii < 5; ii++) {
                        double ti = sys.get(JT + ii), bi = sys.get(JB + ii);
                        addF(sys,base,W,n, iL1 + ii, -kc*dth*ti - kapP*be*bi);
                        for (int jj = 0; jj < 5; jj++) addK(sys,base,W, iL1 + ii, iL1 + jj, kc*ti*sys.get(JT + jj) + kapP*bi*sys.get(JB + jj));
                    }
                }
                // ---- bound: full stereospecific dock of the head frame -------------------------------------
                if (tgt) {
                    for (int k = 0; k < 3; k++) { addF(sys,base,W,n, iW + k, dockTq(hc, nM, m, kB, k)); addK(sys,base,W, iW + k, iW + k, kB); }
                }
                // ---- S1-S2 junction: the existing terminal bend joint on the lever direction L --------------
                double thL0 = params.get(17 * nM + m);
                if (thL0 >= 0.0 && M >= 2) {
                    double aLx = Px - nodes.get((3*(M-1))*nM+m), aLy = Py - nodes.get((3*(M-1)+1)*nM+m), aLz = Pz - nodes.get((3*(M-1)+2)*nM+m);
                    double La = Math.sqrt(aLx*aLx + aLy*aLy + aLz*aLz);
                    if (La > 1e-12) {
                        double iL = 1.0/La, sxL = aLx*iL, syL = aLy*iL, szL = aLz*iL;
                        double cL = sxL*Lx + syL*Ly + szL*Lz; if (cL > 1) cL = 1; if (cL < -1) cL = -1;
                        double thJ = dacosH(cL), snJ = dsin(thJ);
                        if (snJ > 1e-6) {
                            double dthJ = thJ - thL0;
                            double A1L = dthJ/snJ, A2L = (1.0 - dthJ*cL/snJ)/(snJ*snJ);
                            double gxL = (Lx - cL*sxL)*iL, gyL = (Ly - cL*syL)*iL, gzL = (Lz - cL*szL)*iL;
                            // w_k = tau_k x L ; cph_k = s . w_k
                            double w1x = t1y*Lz - t1z*Ly, w1y = t1z*Lx - t1x*Lz, w1z = t1x*Ly - t1y*Lx;
                            double w2x = t2y*Lz - t2z*Ly, w2y = t2z*Lx - t2x*Lz, w2z = t2x*Ly - t2y*Lx;
                            double cp1 = sxL*w1x + syL*w1y + szL*w1z, cp2 = sxL*w2x + syL*w2y + szL*w2z;
                            int rM = (M-1)*3, rMm = (M-2)*3;
                            addF(sys,base,W,n, rM+0,  kbend*A1L*1e6*gxL); addF(sys,base,W,n, rM+1,  kbend*A1L*1e6*gyL); addF(sys,base,W,n, rM+2,  kbend*A1L*1e6*gzL);
                            addF(sys,base,W,n, rMm+0,-kbend*A1L*1e6*gxL); addF(sys,base,W,n, rMm+1,-kbend*A1L*1e6*gyL); addF(sys,base,W,n, rMm+2,-kbend*A1L*1e6*gzL);
                            addF(sys,base,W,n, iL1,   kbend*A1L*cp1);
                            addF(sys,base,W,n, iL1+1, kbend*A1L*cp2);
                            double iL2 = iL*iL;
                            for (int p=0;p<3;p++){
                                double upL=(p==0)?Lx:((p==1)?Ly:Lz), spL=(p==0)?sxL:((p==1)?syL:szL);
                                double gpL=(p==0)?gxL:((p==1)?gyL:gzL);
                                double w1p=(p==0)?w1x:((p==1)?w1y:w1z), w2p=(p==0)?w2x:((p==1)?w2y:w2z);
                                for (int qq=0;qq<3;qq++){
                                    double uqL=(qq==0)?Lx:((qq==1)?Ly:Lz), sqL=(qq==0)?sxL:((qq==1)?syL:szL);
                                    double gqL=(qq==0)?gxL:((qq==1)?gyL:gzL);
                                    double idL=(p==qq)?1.0:0.0;
                                    double HcL = (-(upL*sqL + spL*uqL) + 3.0*cL*spL*sqL - cL*idL)*iL2;
                                    double kv = 1e12*kbend*(A2L*gpL*gqL - A1L*HcL);
                                    addK(sys,base,W, rM+p,  rM+qq,  kv);  addK(sys,base,W, rMm+p, rMm+qq, kv);
                                    addK(sys,base,W, rM+p,  rMm+qq,-kv);  addK(sys,base,W, rMm+p, rM+qq, -kv);
                                }
                                double k1 = 1e6*kbend*(A2L*gpL*cp1 - A1L*(w1p - cp1*spL)*iL);
                                double k2 = 1e6*kbend*(A2L*gpL*cp2 - A1L*(w2p - cp2*spL)*iL);
                                addK(sys,base,W, rM+p,  iL1,  k1); addK(sys,base,W, iL1,  rM+p,  k1);
                                addK(sys,base,W, rMm+p, iL1, -k1); addK(sys,base,W, iL1,  rMm+p, -k1);
                                addK(sys,base,W, rM+p,  iL1+1,  k2); addK(sys,base,W, iL1+1, rM+p,  k2);
                                addK(sys,base,W, rMm+p, iL1+1, -k2); addK(sys,base,W, iL1+1, rMm+p, -k2);
                            }
                            addK(sys,base,W, iL1,   iL1,   kbend*(A2L*cp1*cp1 + A1L*cL));
                            addK(sys,base,W, iL1+1, iL1+1, kbend*(A2L*cp2*cp2 + A1L*cL));
                            addK(sys,base,W, iL1,   iL1+1, kbend*A2L*cp1*cp2);
                            addK(sys,base,W, iL1+1, iL1,   kbend*A2L*cp1*cp2);
                        }
                    }
                }
                // ---- angular mobility (exact rigid-body) + correlated FDT noise -----------------------------
                {
                    int GM = sc, LC = sc + 25, XI = sc + 50, VH = sc + 70;
                    double rhx = rpx*1e-6, rhy = rpy*1e-6, rhz = rpz*1e-6;
                    double gL = gPhi - gT*lbM*lbM; if (gL < 0) gL = 0;
                    // velocities of the head centre per unit coordinate (m/rad): lever k: tau_k x (C-P); head j: e_j x rho
                    sys.set(VH,    v1x*1e-6); sys.set(VH+1,  v1y*1e-6); sys.set(VH+2,  v1z*1e-6);
                    sys.set(VH+3,  v2x*1e-6); sys.set(VH+4,  v2y*1e-6); sys.set(VH+5,  v2z*1e-6);
                    sys.set(VH+6,  0.0);      sys.set(VH+7,  -rhz);     sys.set(VH+8,  rhy);
                    sys.set(VH+9,  rhz);      sys.set(VH+10, 0.0);      sys.set(VH+11, -rhx);
                    sys.set(VH+12, -rhy);     sys.set(VH+13, rhx);      sys.set(VH+14, 0.0);
                    for (int i = 0; i < 5; i++) for (int j = 0; j < 5; j++) {
                        double v = gT*(sys.get(VH+3*i)*sys.get(VH+3*j) + sys.get(VH+3*i+1)*sys.get(VH+3*j+1) + sys.get(VH+3*i+2)*sys.get(VH+3*j+2));
                        if (i >= 2 && j == i) v += gRot;   // head angular velocity e_j: w_i . w_j = delta_ij for i, j >= 2
                        if (i < 2 && j == i) v += gL;
                        sys.set(GM + i*5 + j, v);
                    }
                    for (int i = 0; i < 5; i++) for (int j = 0; j < 5; j++) addK(sys,base,W, iL1 + i, iL1 + j, sys.get(GM + i*5 + j) / dt);
                    if (brownM != 0) {
                        for (int k = 0; k < 25; k++) sys.set(LC + k, 0.0);   // Cholesky G = Lc Lc^T
                        for (int i = 0; i < 5; i++) for (int j = 0; j <= i; j++) {
                            double s = sys.get(GM + i*5 + j);
                            for (int k = 0; k < j; k++) s -= sys.get(LC + i*5 + k)*sys.get(LC + j*5 + k);
                            if (i == j) sys.set(LC + i*5 + i, Math.sqrt(s > 0 ? s : 0));
                            else { double dj = sys.get(LC + j*5 + j); sys.set(LC + i*5 + j, dj > 0 ? s / dj : 0); }
                        }
                        double amp = Math.sqrt(2*kT/dt);
                        for (int k = 0; k < 5; k++) sys.set(XI + k, gaussD(seed, tt, 0x4843C0L + ((long)m*7919L + k)*104729L));
                        for (int i = 0; i < 5; i++) {
                            double s = 0; for (int k = 0; k <= i; k++) s += sys.get(LC + i*5 + k)*sys.get(XI + k);
                            addF(sys,base,W,n, iL1 + i, amp*s);
                        }
                    }
                }
                // ---- solve (Gauss-Jordan, partial pivoting), verbatim ---------------------------------------
                for (int c0=0;c0<n;c0++){
                    int p=c0; double bestv=sys.get(base+c0*W+c0); if(bestv<0)bestv=-bestv;
                    for(int r=c0+1;r<n;r++){ double v=sys.get(base+r*W+c0); if(v<0)v=-v; if(v>bestv){bestv=v;p=r;} }
                    if(p!=c0){ for(int k=0;k<W;k++){ double tmp=sys.get(base+c0*W+k); sys.set(base+c0*W+k, sys.get(base+p*W+k)); sys.set(base+p*W+k, tmp); } }
                    double piv=sys.get(base+c0*W+c0);
                    for(int r=0;r<n;r++){ if(r==c0) continue; double fac=sys.get(base+r*W+c0)/piv;
                        for(int k=c0;k<W;k++) sys.set(base+r*W+k, sys.get(base+r*W+k)-fac*sys.get(base+c0*W+k)); }
                }
                double mv=0;
                for (int j=1;j<=M;j++){ int fb=(j-1)*3;
                    for(int k=0;k<3;k++){ double dqm=sys.get(base+(fb+k)*W+n)/sys.get(base+(fb+k)*W+(fb+k));
                        double dnode=dqm*1e6; nodes.set((3*j+k)*nM+m, nodes.get((3*j+k)*nM+m)+dnode);
                        if (stOn && stMode == 0) restC.set((48 + fb + k)*nM+m, dnode);
                        double amv=dnode<0?-dnode:dnode; if(amv>mv) mv=amv; } }
                double q0 = sys.get(base+(iL1)*W+n)/sys.get(base+(iL1)*W+(iL1));
                double q1 = sys.get(base+(iL1+1)*W+n)/sys.get(base+(iL1+1)*W+(iL1+1));
                double q2 = sys.get(base+(iL1+2)*W+n)/sys.get(base+(iL1+2)*W+(iL1+2));
                double q3 = sys.get(base+(iL1+3)*W+n)/sys.get(base+(iL1+3)*W+(iL1+3));
                double q4 = sys.get(base+(iL1+4)*W+n)/sys.get(base+(iL1+4)*W+(iL1+4));
                if (stOn && stMode == 0) { restC.set((48 + nF)*nM+m, 0.0); restC.set((49 + nF)*nM+m, 0.0); restC.set((50 + nF)*nM+m, 0.0); }
                double wLx = q0*t1x + q1*t2x, wLy = q0*t1y + q1*t2y, wLz = q0*t1z + q1*t2z;
                double Lnx = rotC(Lx, Ly, Lz, wLx, wLy, wLz, 0), Lny = rotC(Lx, Ly, Lz, wLx, wLy, wLz, 1), Lnz = rotC(Lx, Ly, Lz, wLx, wLy, wLz, 2);
                double anx = rotC(ax, ay, az, q2, q3, q4, 0), any = rotC(ax, ay, az, q2, q3, q4, 1), anz = rotC(ax, ay, az, q2, q3, q4, 2);
                double cnx = rotC(cx, cy, cz, q2, q3, q4, 0), cny = rotC(cx, cy, cz, q2, q3, q4, 1), cnz = rotC(cx, cy, cz, q2, q3, q4, 2);
                double ll = Math.sqrt(Lnx*Lnx + Lny*Lny + Lnz*Lnz);
                double al = Math.sqrt(anx*anx + any*any + anz*anz);
                anx /= al; any /= al; anz /= al;
                double ca = cnx*anx + cny*any + cnz*anz;
                cnx -= ca*anx; cny -= ca*any; cnz -= ca*anz;
                double cl = Math.sqrt(cnx*cnx + cny*cny + cnz*cnz);
                s3(hc, 0, nM, m, anx, any, anz); s3(hc, 3, nM, m, cnx/cl, cny/cl, cnz/cl);
                s3(hc, 6, nM, m, Lnx/ll, Lny/ll, Lnz/ll);
                if (mv < tol) break;
            }
            // final geometry -> outGeom, theta/beta mirrors, F8 diagnostics
            double Px2=nodes.get((3*M)*nM+m), Py2=nodes.get((3*M+1)*nM+m), Pz2=nodes.get((3*M+2)*nM+m);
            double ax = g(hc,0,nM,m), ay = g(hc,1,nM,m), az = g(hc,2,nM,m);
            double Cx = Px2 + lb*g(hc,6,nM,m), Cy = Py2 + lb*g(hc,7,nM,m), Cz = Pz2 + lb*g(hc,8,nM,m);
            double dF = rF8x - rCx;
            outGeom.set(m,Cx); outGeom.set(nM+m,Cy); outGeom.set(2*nM+m,Cz);
            outGeom.set(3*nM+m,Cx - rCx*ax); outGeom.set(4*nM+m,Cy - rCx*ay); outGeom.set(5*nM+m,Cz - rCx*az);
            outGeom.set(6*nM+m,Cx + dF*ax); outGeom.set(7*nM+m,Cy + dF*ay); outGeom.set(8*nM+m,Cz + dF*az);
            double thF = thetaOf(ax, ay, az, g(hc,3,nM,m), g(hc,4,nM,m), g(hc,5,nM,m), g(hc,6,nM,m), g(hc,7,nM,m), g(hc,8,nM,m), sxT);
            hc.set(16*nM+m, betaOf(g(hc,3,nM,m), g(hc,4,nM,m), g(hc,5,nM,m), g(hc,6,nM,m), g(hc,7,nM,m), g(hc,8,nM,m))); hc.set(17*nM+m, thF);
            q.set(m, 0.0); q.set(nM+m, thF);
            forceDotFil.set(m, bnd ? bondData.get(dB + 12) : 0.0f);
            forceMag.set(m, bnd ? (float) Math.sqrt(f8x*f8x + f8y*f8y + f8z*f8z) : 0.0f);
        }
    }

    static void addF(DoubleArray sys, int base, int W, int n, int r, double v) { if (r >= 0) sys.set(base + r * W + n, sys.get(base + r * W + n) + v); }
    static void addK(DoubleArray sys, int base, int W, int r, int c, double v) { if (r >= 0 && c >= 0) sys.set(base + r * W + c, sys.get(base + r * W + c) + v); }
}
