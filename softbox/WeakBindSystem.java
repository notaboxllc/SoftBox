package softbox;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.DoubleArray;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * TWO-STAGE ACTIN BINDING: weak collision complex -> stereospecific strong bond (2026-10-25, jba).
 * Design + literature: docs/motor/WEAK_BINDING_DESIGN.md. Noncanonical, default OFF.
 *
 * <p>2026-10-26: rewritten as device kernels, ONE implementation for both runners (the CPU step calls the same
 * methods sequentially; the GPU graph runs them as tasks). Per-motor state lives in ExMat (wkI / wkD / wkG), not in
 * host statics, so two ExMats (CPU vs device equivalence) do not share it.
 *
 * <p>States per motor: FREE (boundSeg = -1, weakSeg = -1), WEAK (boundSeg = -1, weakSeg >= 0), STRONG
 * (boundSeg >= 0, the existing bound state, unchanged). A WEAK head is UNBOUND for every other system: its
 * orientation target, Brownian policy, kbind gate and chemistry are the unbound ones. The only thing it gains is
 * an isotropic zero-rest spring between its loop-2 point and the actin partner: +F on the head through
 * bondData[0..2] (which the solver reads when the weak flag in restC is set) and the EXACT reaction -F on the
 * segment through bondData[6..11], gathered by the existing segment gather (keyed by {@link #weakKey}).
 *
 * <ul>
 *   <li>FREE -> WEAK on contact: siteGateA with the orientation and preload conditions relaxed (sbW), then the
 *       loop-2 point within d_w of its actin partner. Non-stereospecific (Thomas-lab EPR; Geeves A-state).</li>
 *   <li>WEAK -> FREE at k_off,w(F) = k0 exp(F x / kT) (Bell; k0 = 4e3 /s, Schoenberg 1988 at physiological ionic
 *       strength; x = 0.75 nm, the Debye length, ASSUMED), immediately if the tether exceeds r_break (ASSUMED),
 *       or if the head leaves ADP.Pi.</li>
 *   <li>WEAK -> STRONG at k_ws exp(-alpha ang^2/2) (alpha = 3.7, Steffen 2001; ang per WEIGHT; k_ws unpinned),
 *       with the existing g4 preload and g3 converter conditions. theta = head axis vs -n_site; mu = the dock
 *       misalignment (stroke axis vs barbed, in the floor plane), i.e. the rotation the dock will then relax.</li>
 * </ul>
 *
 * Layouts (rows x N, motor-minor):
 *   wkI  0 weakSeg | 1 weakSiteK | 2 justEntered | 3 eligible | 4 nEnter | 5 nBreakRate | 6 nBreakRange |
 *        7 nBreakNuc | 8 nStrong | 9 weakSteps
 *   wkD  0 arc | 1 azim | 2 ph (site azimuth)
 *   wkG  0..2 actin anchor | 3..5 loop-2 offset o | 6..8 head point F8+o | 9 r8 | 10 theta | 11 mu | 12 facing |
 *        13 tether r | 14 g3 | 15 g4 | 16..18 site normal | 19..21 site point          (um, rad, flags)
 *   wkP  see {@link #prepare}
 */
final class WeakBindSystem {
    static boolean ON = false;
    static double K_W_PN_NM = 2.0;        // weak spring (no measurement): ideal-chain loop-2 tether, RMS reach 2.5 nm
    static double CONTACT_COS = 0.0;      // contact needs head axis within acos(CONTACT_COS) = 90 deg of -n (head body outside actin)
    static double K_OFF0 = 4.0e3;         // 1/s, Schoenberg 1988 (physiological ionic strength)
    static double X_BELL_NM = 0.75;       // Debye length at ~0.17 M (ASSUMED force sensitivity)
    static double D_W_NM = 3.0;           // contact distance = the existing g0
    static double R_BREAK_NM = 6.0;       // range cutoff (ASSUMED 2 d_w)
    // ENERGY CAP (2026-10-26, jba): the weak complex is a ~2.5-3 kT bond (K_w ~ 12; Mansson 2016, Duke 1999). It
    // cannot store more elastic energy than it binds with, so it breaks when 1/2 k_w r^2 exceeds E_BIND: r_c =
    // sqrt(2 E_BIND kT / k_w) (3.5 nm at 3 kT, 2 pN/nm => F <= 7 pN). 0 = use R_BREAK_NM.
    static double E_BIND_KT = 0.0;
    static double K_WS = 300.0;           // 1/s weak -> strong attempt rate (unpinned)
    static double ALPHA = 3.7;            // Steffen 2001 angular selectivity, kT/rad^2
    // LOOP-2 TETHER (2026-10-25, jba). The weak complex is loop 2 (K629/K632) on the actin N-terminus / D24-D25
    // (Klebl et al. 2025 Nature 642; PDB 8R9V primed actomyosin-5). Measured in 8R9V relative to the strong
    // contact centroid (= F8 / the site), in the docked frame: the head-side loop-2 point is 0.63 nm out (toward
    // the head centre) and 1.65 nm toward the pointed end; the actin-side partner (D24-D25 of the pointed-side
    // subunit) is 1.70 nm toward the pointed end. So in the docked pose the two anchors are ~0.8 nm apart, i.e.
    // the tether is relaxed exactly when the stereospecific interface is formed. The tether itself is the
    // ~30-residue disordered part of loop 2 plus the actin N-terminus, modelled as an ideal-chain (entropic,
    // zero-rest) spring: k_w = 3 kT / <R^2>, so k_w = 2 pN/nm <=> RMS reach 2.5 nm (ASSUMED, no measurement).
    static boolean LOOP2 = true;          // false (-weak-f8) = the earlier F8-to-site tether
    static double L2_OUT_NM = 0.63, L2_AX_NM = 1.65, A_AX_NM = 1.70;
    static double L2_SIGN = +1.0;         // sign of the body axis th = e0 x econv that points pointed-ward when docked
    static int WEIGHT = 0;                // weak->strong angular weight: 0 none, 1 Steffen facing, 2 facing+theta+mu, 3 theta+mu
    static StringBuilder ANG = null;      // fixture diagnostic: per weak step t m r_nm r8_nm facing theta mu g3 g4 (host, from wkG)

    static final int I_ROWS = 10, D_ROWS = 3, G_ROWS = 22;
    static final long SALT_BREAK = 0x57454B4FL, SALT_STRONG = 0x5354524EL;

    /** Allocate the per-motor state and the constant blocks. sbW is a relaxed copy of sbP, refreshed by prepare. */
    static void alloc(ExplicitCompleteMatHarness.ExMat e) {
        int N = e.N;
        e.wkI = new IntArray(I_ROWS * N); e.wkI.init(0);
        for (int m = 0; m < N; m++) { e.wkI.set(m, -1); e.wkI.set(N + m, -1); }
        e.wkD = new DoubleArray(D_ROWS * N); e.wkD.init(0.0);
        e.wkG = new DoubleArray(G_ROWS * N); e.wkG.init(0.0);
        e.gKey = new IntArray(N); e.gKey.init(-1);
        e.wkP = new DoubleArray(16); e.wkP.init(0.0);
        e.sbW = new DoubleArray(e.sbP.getSize()); e.sbW.init(0.0);
    }
    /** (Re)fill wkP and sbW from the static configuration and the current sbP / dt. Constants only. */
    static void prepare(ExplicitCompleteMatHarness.ExMat e) {
        DoubleArray p = e.wkP;
        p.set(0, K_W_PN_NM * 1e-9);   // N/um
        double rB = E_BIND_KT > 0 ? Math.sqrt(2.0 * E_BIND_KT * e.sbP.get(9) / (K_W_PN_NM * 1e-3)) * 1e9 : R_BREAK_NM;
        p.set(1, K_OFF0); p.set(2, X_BELL_NM); p.set(3, D_W_NM); p.set(4, rB); p.set(5, K_WS); p.set(6, ALPHA);
        p.set(7, LOOP2 ? 1.0 : 0.0); p.set(8, L2_OUT_NM); p.set(9, L2_AX_NM); p.set(10, A_AX_NM); p.set(11, L2_SIGN);
        p.set(12, WEIGHT); p.set(13, e.params.get(10 * e.N)); p.set(14, e.sbP.get(9));
        for (int i = 0; i < e.sbP.getSize(); i++) e.sbW.set(i, e.sbP.get(i));
        e.sbW.set(28, CONTACT_COS); e.sbW.set(4, 1e9); e.sbW.set(0, D_W_NM + (LOOP2 ? L2_AX_NM + A_AX_NM : 0.0));
    }
    static String describe() {
        return String.format(java.util.Locale.US, "k_w %.2f pN/nm | k_off0 %.0f /s, Bell x %.2f nm, break > %.2f nm" + (E_BIND_KT > 0 ? " (energy cap " + E_BIND_KT + " kT)" : "") + " | k_ws %.0f /s, alpha %.2f, weight %d (%s) | contact %.1f nm, %s tether",
                K_W_PN_NM, K_OFF0, X_BELL_NM, E_BIND_KT > 0 ? Math.sqrt(2.0 * E_BIND_KT * Constants.kT / (K_W_PN_NM * 1e-3)) * 1e9 : R_BREAK_NM, K_WS, ALPHA, WEIGHT,
                WEIGHT == 0 ? "none" : WEIGHT == 1 ? "facing" : WEIGHT == 2 ? "facing+theta+mu" : WEIGHT == 3 ? "theta+mu" : "F8 strain energy + theta, no g4", D_W_NM, LOOP2 ? "loop-2" : "F8");
    }

    // ---------------------------------------------------------------------------------------------------------
    // device-safe math: same arithmetic on both runners (Math.exp/cos on doubles run in f32 on PTX)
    /** exp(x), |x| < ~700: k = round(x/ln2), Taylor on |r| <= ln2/2, exact scaling by repeated 2 / 0.5. */
    static double dexp(double x) {
        double fk = x * 1.4426950408889634;
        int k = (int) (fk >= 0 ? fk + 0.5 : fk - 0.5);
        double r = (x - k * 6.93147180369123816490e-01) - k * 1.90821492927058770002e-10;
        double p = 1.0 + r*(1.0 + r*(0.5 + r*(1.0/6 + r*(1.0/24 + r*(1.0/120 + r*(1.0/720 + r*(1.0/5040
                 + r*(1.0/40320 + r*(1.0/362880 + r*(1.0/3628800 + r*(1.0/39916800 + r*(1.0/479001600))))))))))));
        for (int i = 0; i < 1100 && k > 0; i++) { p *= 2.0; k--; }
        for (int i = 0; i < 1100 && k < 0; i++) { p *= 0.5; k++; }
        return p;
    }
    /** 1 - exp(-y), y >= 0, accurate for small y (the per-step event probability). */
    static double pEvent(double y) {
        if (y < 1e-2) return y * (1.0 - 0.5 * y * (1.0 - y / 3.0 * (1.0 - 0.25 * y * (1.0 - 0.2 * y))));
        return 1.0 - dexp(-y);
    }
    /** Uniform [0,1) from (seed, t, m, salt): splitmix64 finaliser. */
    static double u01(long seed, long t, int m, long salt) {
        long z = seed * 0x9E3779B97F4A7C15L + t * 0xBF58476D1CE4E5B9L + (long) m * 0x94D049BB133111EBL + salt;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L; z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL; z ^= (z >>> 31);
        return (z >>> 11) * 0x1.0p-53;
    }

    // ---------------------------------------------------------------------------------------------------------
    /**
     * Task 1 (after the relaxed siteGateA with sbW): eligibility, and FREE -> tentative WEAK entry from the
     * contact candidate. The loop-2 contact distance is tested in {@link #weakDecide} once the geometry is built.
     */
    public static void weakEnter(IntArray active, IntArray noBind, IntArray boundSeg, IntArray nuc,
                                 IntArray candInt, DoubleArray candArc, DoubleArray candAzim, DoubleArray sbP,
                                 IntArray wkI, DoubleArray wkD, IntArray counts) {
        int N = counts.get(0);
        double eps = sbP.get(20) * sbP.get(21);
        for (@Parallel int m = 0; m < N; m++) {
            int bs = boundSeg.get(m);
            int el = (bs < 0 && active.get(m) == 1 && noBind.get(m) == 0) ? 1 : 0;
            wkI.set(2 * N + m, 0); wkI.set(3 * N + m, el);
            if (bs >= 0) { wkI.set(m, -1); continue; }
            if (el == 1 && wkI.get(m) < 0 && candInt.get(m) >= 0 && nuc.get(m) == MotorStore.NUC_ADPPI) {
                wkI.set(m, candInt.get(m)); wkI.set(N + m, candInt.get(N + m)); wkI.set(2 * N + m, 1);
                wkD.set(m, candArc.get(m)); wkD.set(N + m, candAzim.get(m)); wkD.set(2 * N + m, candAzim.get(m) - eps);
            }
        }
    }

    /** Task 2: the weak complex's geometry from the CURRENT segment pose and head (every weak motor). */
    public static void weakGeom(IntArray boundSeg, DoubleArray outGeom, FloatArray filCoord, FloatArray filUVec,
                                FloatArray filYVec, FloatArray filSegLength, DoubleArray sbP, DoubleArray frame,
                                DoubleArray convF, DoubleArray nodes, IntArray wkI, DoubleArray wkD, DoubleArray wkG,
                                DoubleArray wkP, IntArray counts) {
        int N = counts.get(0), nSeg = counts.get(3);
        double R = sbP.get(19);
        boolean loop2 = wkP.get(7) != 0.0;
        double l2Out = wkP.get(8) * 1e-3, l2Ax = wkP.get(11) * wkP.get(9) * 1e-3, aAx = loop2 ? wkP.get(10) * 1e-3 : 0.0;
        for (@Parallel int m = 0; m < N; m++) {
            int s = wkI.get(m);
            if (s < 0 || boundSeg.get(m) >= 0) continue;
            double half = 0.5 * filSegLength.get(s);
            double ux = filUVec.get(s), uy = filUVec.get(nSeg + s), uz = filUVec.get(2 * nSeg + s);
            double yx = filYVec.get(s), yy = filYVec.get(nSeg + s), yz = filYVec.get(2 * nSeg + s);
            double zx = uy * yz - uz * yy, zy = uz * yx - ux * yz, zz = ux * yy - uy * yx;
            double zl = Math.sqrt(zx * zx + zy * zy + zz * zz); if (zl > 1e-30) { zx /= zl; zy /= zl; zz /= zl; }
            double ph = wkD.get(2 * N + m), c = HeadChainSystem.dcos(ph), sn = HeadChainSystem.dsin(ph);
            double nx = c * yx + sn * zx, ny = c * yy + sn * zy, nz = c * yz + sn * zz;
            double a = wkD.get(m) - half;
            double sx = filCoord.get(s) + a * ux + R * nx, sy = filCoord.get(nSeg + s) + a * uy + R * ny, sz = filCoord.get(2 * nSeg + s) + a * uz + R * nz;
            double anx = sx - aAx * ux, any = sy - aAx * uy, anz = sz - aAx * uz;
            double fx = outGeom.get(6 * N + m), fy = outGeom.get(7 * N + m), fz = outGeom.get(8 * N + m);   // F8
            // loop-2 offset o: -L2_OUT along the head axis (converter -> F8) + L2_SIGN*L2_AX along th = e0 x econv
            double ox = 0, oy = 0, oz = 0;
            if (loop2) {
                boolean cv = convF.get(12 * N + m) != 0.0;
                double cx = cv ? convF.get(3 * N + m) : frame.get(3 * N + m), cy = cv ? convF.get(4 * N + m) : frame.get(4 * N + m),
                       cz = cv ? convF.get(5 * N + m) : frame.get(5 * N + m);
                double bx = fx - outGeom.get(m), by = fy - outGeom.get(N + m), bz = fz - outGeom.get(2 * N + m);
                double bl = Math.sqrt(bx * bx + by * by + bz * bz); bx /= bl; by /= bl; bz /= bl;
                double be = bx * cx + by * cy + bz * cz;
                double e0x = bx - be * cx, e0y = by - be * cy, e0z = bz - be * cz, el = Math.sqrt(e0x * e0x + e0y * e0y + e0z * e0z);
                e0x /= el; e0y /= el; e0z /= el;
                double tx = e0y * cz - e0z * cy, ty = e0z * cx - e0x * cz, tz = e0x * cy - e0y * cx;
                ox = -l2Out * bx + l2Ax * tx; oy = -l2Out * by + l2Ax * ty; oz = -l2Out * bz + l2Ax * tz;
            }
            double hpx = fx + ox, hpy = fy + oy, hpz = fz + oz;
            double rx = hpx - anx, ry = hpy - any, rz = hpz - anz;
            double qx = fx - sx, qy = fy - sy, qz = fz - sz;
            // theta: head axis vs -n_site
            double dh = -(outGeom.get(9 * N + m) * nx + outGeom.get(10 * N + m) * ny + outGeom.get(11 * N + m) * nz);
            double th = HeadChainSystem.dacosH(dh);
            // mu: the dock misalignment, stroke axis vs barbed, both projected into the plane normal to eup
            double ex = frame.get(6 * N + m), ey = frame.get(7 * N + m), ez = frame.get(8 * N + m);
            double vx = frame.get(m), vy = frame.get(N + m), vz = frame.get(2 * N + m);
            double ev = ex * vx + ey * vy + ez * vz, eu = ex * ux + ey * uy + ez * uz;
            double bbx = vx - ev * ex, bby = vy - ev * ey, bbz = vz - ev * ez, hhx = ux - eu * ex, hhy = uy - eu * ey, hhz = uz - eu * ez;
            double bn = Math.sqrt(bbx * bbx + bby * bby + bbz * bbz), hn = Math.sqrt(hhx * hhx + hhy * hhy + hhz * hhz);
            double mu = (bn < 1e-12 || hn < 1e-12) ? 0.0 : HeadChainSystem.dacosH((bbx * hhx + bby * hhy + bbz * hhz) / (bn * hn));
            // facing (Steffen 2001): site normal vs the direction site -> S2 base, both perpendicular to the filament
            double dx = nodes.get(m) - sx, dy = nodes.get(N + m) - sy, dz = nodes.get(2 * N + m) - sz;
            double du = dx * ux + dy * uy + dz * uz; dx -= du * ux; dy -= du * uy; dz -= du * uz;
            double nu = nx * ux + ny * uy + nz * uz, pnx = nx - nu * ux, pny = ny - nu * uy, pnz = nz - nu * uz;
            double la = Math.sqrt(dx * dx + dy * dy + dz * dz), lb = Math.sqrt(pnx * pnx + pny * pny + pnz * pnz);
            double fa = (la < 1e-12 || lb < 1e-12) ? 0.0 : HeadChainSystem.dacosH((dx * pnx + dy * pny + dz * pnz) / (la * lb));
            wkG.set(m, anx); wkG.set(N + m, any); wkG.set(2 * N + m, anz);
            wkG.set(3 * N + m, ox); wkG.set(4 * N + m, oy); wkG.set(5 * N + m, oz);
            wkG.set(6 * N + m, hpx); wkG.set(7 * N + m, hpy); wkG.set(8 * N + m, hpz);
            wkG.set(9 * N + m, Math.sqrt(qx * qx + qy * qy + qz * qz));
            wkG.set(10 * N + m, th); wkG.set(11 * N + m, mu); wkG.set(12 * N + m, fa);
            wkG.set(13 * N + m, Math.sqrt(rx * rx + ry * ry + rz * rz));
            wkG.set(16 * N + m, nx); wkG.set(17 * N + m, ny); wkG.set(18 * N + m, nz);
            wkG.set(19 * N + m, sx); wkG.set(20 * N + m, sy); wkG.set(21 * N + m, sz);
        }
    }

    /**
     * Task 3 (replaces siteCommitB): contact test for new entries; break / weak -> strong for existing weak
     * motors. Same bookkeeping contract as siteCommitB: boundSeg / bindArc / bindAzim / bindSite on a STRONG entry,
     * justBound, prevBound for every motor.
     */
    public static void weakDecide(IntArray boundSeg, IntArray nuc, DoubleArray q, DoubleArray sbP,
                                  IntArray wkI, DoubleArray wkD, DoubleArray wkG, DoubleArray wkP, IntArray matc,
                                  FloatArray bindArc, FloatArray bindAzim, IntArray bindSite, IntArray justBound,
                                  IntArray prevBound, IntArray counts) {
        int N = counts.get(0);
        long tt = matc.get(0), seed = matc.get(1);
        double kW = wkP.get(0), k0 = wkP.get(1), xB = wkP.get(2) * 1e-9, dW = wkP.get(3), rBrk = wkP.get(4);
        double kws = wkP.get(5), alpha = wkP.get(6), dt = wkP.get(13), kT = wkP.get(14);
        int weight = (int) wkP.get(12);
        double kF8 = sbP.get(24), g3tol = sbP.get(3), g4cap = sbP.get(4);
        for (@Parallel int m = 0; m < N; m++) {
            int jb = 0, bs = boundSeg.get(m);
            int ws = wkI.get(m);
            wkG.set(14 * N + m, 0.0); wkG.set(15 * N + m, 0.0);
            if (bs < 0 && wkI.get(3 * N + m) == 1 && ws >= 0) {
                double r = wkG.get(13 * N + m);
                if (wkI.get(2 * N + m) == 1) {   // new contact: the loop-2 point must lie within d_w of its partner
                    if (r * 1e3 > dW) { wkI.set(m, -1); wkI.set(2 * N + m, 0); }
                    else wkI.set(4 * N + m, wkI.get(4 * N + m) + 1);
                } else {
                    wkI.set(9 * N + m, wkI.get(9 * N + m) + 1);
                    double F = kW * r;   // N
                    boolean brk = false;
                    if (nuc.get(m) != MotorStore.NUC_ADPPI) { brk = true; wkI.set(7 * N + m, wkI.get(7 * N + m) + 1); }
                    else if (r * 1e3 > rBrk) { brk = true; wkI.set(6 * N + m, wkI.get(6 * N + m) + 1); }
                    else if (u01(seed, tt, m, SALT_BREAK) < pEvent(k0 * dexp(F * xB / kT) * dt)) {
                        brk = true; wkI.set(5 * N + m, wkI.get(5 * N + m) + 1);
                    }
                    if (brk) wkI.set(m, -1);
                    else {
                        double th = wkG.get(10 * N + m), mu = wkG.get(11 * N + m), fa = wkG.get(12 * N + m);
                        double dg = ((q.get(N + m) - q.get(m)) - q.get(2 * N + m)) * 57.29577951308232;
                        boolean g3 = (dg < 0 ? -dg : dg) < g3tol;
                        boolean g4 = kF8 * wkG.get(9 * N + m) * 1e12 < g4cap;   // the F8 interface itself must be formed
                        wkG.set(14 * N + m, g3 ? 1.0 : 0.0); wkG.set(15 * N + m, g4 ? 1.0 : 0.0);
                        double ang2 = weight == 0 ? 0.0 : (weight == 1 ? fa * fa : (weight == 3 ? th * th + mu * mu : (weight == 4 ? th * th : fa * fa + th * th + mu * mu)));
                        // weight 4 (2026-10-26): Boltzmann-weighted strong attachment (Huxley 1957; Duke 1999): the F8 strain
                        // energy the strong bond is born with, plus Steffen's orientation target on theta; no polarity term
                        // and no hard 2 nm preload window (g4 is then not required).
                        double r8 = wkG.get(9 * N + m);
                        double eStr = weight == 4 ? 0.5 * kF8 * r8 * r8 * 1e-6 / kT : 0.0;
                        double k = kws * dexp(-0.5 * alpha * ang2 - eStr);
                        if (g3 && (g4 || weight == 4) && u01(seed, tt, m, SALT_STRONG) < pEvent(k * dt)) {
                            bs = ws;
                            boundSeg.set(m, bs); bindArc.set(m, (float) wkD.get(m)); bindAzim.set(m, (float) wkD.get(N + m));
                            bindSite.set(m, wkI.get(N + m)); jb = 1;
                            wkI.set(8 * N + m, wkI.get(8 * N + m) + 1);
                            wkI.set(m, -1);
                        }
                    }
                }
            }
            if (bs < 0) bindSite.set(m, -1);
            justBound.set(m, jb);
            prevBound.set(m, bs);
        }
    }

    /**
     * Task 4 (after the bond kernel): the weak spring. +F on the head at F8 + o (bondData[0..2]; central, so no
     * couple about the tether line), the exact reaction on the segment (bondData[6..8] force, [9..11] torque about
     * the segment centre), and the solver's restC rows (o, and k_w as the weak flag). Clears the rows otherwise.
     */
    public static void weakForce(IntArray boundSeg, IntArray wkI, DoubleArray wkG, DoubleArray wkP, FloatArray filCoord,
                                 FloatArray bondData, DoubleArray restC, IntArray counts) {
        int N = counts.get(0), nSeg = counts.get(3), STRIDE = 13;
        int wRow = restC.getSize() / N - 1;
        double kW = wkP.get(0);
        for (@Parallel int m = 0; m < N; m++) {
            restC.set(wRow * N + m, 0.0);
            restC.set((wRow - 3) * N + m, 0.0); restC.set((wRow - 2) * N + m, 0.0); restC.set((wRow - 1) * N + m, 0.0);
            int s = wkI.get(m);
            if (s < 0 || boundSeg.get(m) >= 0) continue;
            double anx = wkG.get(m), any = wkG.get(N + m), anz = wkG.get(2 * N + m);
            double rx = wkG.get(6 * N + m) - anx, ry = wkG.get(7 * N + m) - any, rz = wkG.get(8 * N + m) - anz;
            double Fx = -kW * rx, Fy = -kW * ry, Fz = -kW * rz;   // on the head, N
            restC.set((wRow - 3) * N + m, wkG.get(3 * N + m)); restC.set((wRow - 2) * N + m, wkG.get(4 * N + m)); restC.set((wRow - 1) * N + m, wkG.get(5 * N + m));
            restC.set(wRow * N + m, kW);   // nonzero = weak; the value is the spring stiffness for the solver Hessian
            int d = m * STRIDE;
            for (int k = 0; k < STRIDE; k++) bondData.set(d + k, 0f);
            bondData.set(d, (float) Fx); bondData.set(d + 1, (float) Fy); bondData.set(d + 2, (float) Fz);
            double ax = (anx - filCoord.get(s)) * 1e-6, ay = (any - filCoord.get(nSeg + s)) * 1e-6, az = (anz - filCoord.get(2 * nSeg + s)) * 1e-6;
            double gx = -Fx, gy = -Fy, gz = -Fz;   // on the segment, at the actin anchor
            bondData.set(d + 6, (float) gx); bondData.set(d + 7, (float) gy); bondData.set(d + 8, (float) gz);
            bondData.set(d + 9, (float) (ay * gz - az * gy)); bondData.set(d + 10, (float) (az * gx - ax * gz)); bondData.set(d + 11, (float) (ax * gy - ay * gx));
        }
    }

    /** Task 5 (before the CSR build): the gather key, the strong segment or else the weak one, else -1. */
    public static void weakKey(IntArray boundSeg, IntArray wkI, IntArray gKey, IntArray counts) {
        int N = counts.get(0);
        for (@Parallel int m = 0; m < N; m++) {
            int bs = boundSeg.get(m);
            gKey.set(m, bs >= 0 ? bs : wkI.get(m));
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // host-side accessors (read the host copies; on the device path fetch wkI / wkG first)
    static boolean isWeak(ExplicitCompleteMatHarness.ExMat e, int m) { return ON && e.wkI != null && e.wkI.get(m) >= 0; }
    static int weakSeg(ExplicitCompleteMatHarness.ExMat e, int m) { return e.wkI.get(m); }
    static int weakCount(ExplicitCompleteMatHarness.ExMat e) { int c = 0; for (int m = 0; m < e.N; m++) if (e.wkI.get(m) >= 0) c++; return c; }
    /** site point (um) + normal of motor m's weak site, as last computed by weakGeom. */
    static double[] site(ExplicitCompleteMatHarness.ExMat e, int m) {
        int N = e.N; DoubleArray g = e.wkG;
        return new double[]{ g.get(19 * N + m), g.get(20 * N + m), g.get(21 * N + m), g.get(16 * N + m), g.get(17 * N + m), g.get(18 * N + m) };
    }
    /** Fixture diagnostic row for motor m (call after the step, on a weak, non-new motor). */
    static void appendAng(ExplicitCompleteMatHarness.ExMat e, int t, int m) {
        if (ANG == null || !isWeak(e, m) || e.wkI.get(2 * e.N + m) == 1) return;
        int N = e.N; DoubleArray g = e.wkG;
        ANG.append(String.format(java.util.Locale.US, "%d\t%d\t%.3f\t%.3f\t%.2f\t%.2f\t%.2f\t%d\t%d%n", t, m, g.get(13 * N + m) * 1e3, g.get(9 * N + m) * 1e3,
                Math.toDegrees(g.get(12 * N + m)), Math.toDegrees(g.get(10 * N + m)), Math.toDegrees(g.get(11 * N + m)),
                (int) g.get(14 * N + m), (int) g.get(15 * N + m)));
    }
    static long sumRow(ExplicitCompleteMatHarness.ExMat e, int row) { long s = 0; for (int m = 0; m < e.N; m++) s += e.wkI.get(row * e.N + m); return s; }
    static String stats(ExplicitCompleteMatHarness.ExMat e) {
        return String.format(java.util.Locale.US, "weak entries %d | breaks: rate %d, range %d, nucleotide %d | weak->strong %d | weak motor-steps %d",
                sumRow(e, 4), sumRow(e, 5), sumRow(e, 6), sumRow(e, 7), sumRow(e, 8), sumRow(e, 9));
    }
}
