package softbox;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.DoubleArray;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * VILFAN GRADED COMPETING-SITE ATTACHMENT on native discrete actin.
 *
 * <p><b>Noncanonical, flag-gated, DEFAULT-OFF.</b> This system changes exactly one thing: the rule by which
 * a chemically eligible unbound head assigns attachment hazards to actin sites. It adds no force law, no
 * stiffness to the bound state, no chemistry, no detachment rule and no geometry. Every post-attachment
 * force in SoftBox is untouched.
 *
 * <h2>The published law (Vilfan, Biophys J 97(4):1130-1137, 2009; arXiv:0906.0784)</h2>
 * The elastic energy cost of binding a head anchored at {@code x_M} to actin site {@code i} is Eq (1):
 * <pre>
 *   U_i = 1/2 K (X + i a - x_M)^2  +  1/2 K_theta (Theta + i theta_0 + 2 pi n)^2
 * </pre>
 * with {@code n} chosen so the angle falls in {@code [-pi, pi]}, and the binding rate is Eq (2):
 * <pre>
 *   k_A^i = k_A exp[ -U_i / (k_B T) ]
 * </pre>
 * Published parameters (Table I): {@code k_A = 50 /s}, {@code K = 0.5 pN/nm},
 * {@code K_theta = alpha k_B T} with {@code alpha = 4, 6, 8} ({@code alpha = 3.7} measured, used as a lower
 * estimate), {@code a = 2.75 nm}, {@code theta_0 = -167.14 deg}, {@code k_B T = 4.14e-21 J}.
 *
 * <p>Working in pN and nm makes the exponent parameter-free in {@code alpha}:
 * <pre>
 *   U_i / kT = (K / (2 kT)) * xi_i^2  +  (alpha / 2) * theta_i^2        [xi in nm, theta in rad]
 * </pre>
 *
 * <h2>The two mismatch coordinates, as SoftBox measures them</h2>
 * <ul>
 *   <li><b>Longitudinal</b> {@code xi_i} = the site's axial coordinate minus the motor's axial coordinate,
 *       both projected on the filament's own material tangent. Vilfan's {@code x_M} is the motor's
 *       ANCHORING POINT on the coverslip, so SoftBox uses the S2 emergence point {@code g4E[m]} — the
 *       point where that motor is attached to the surface. The actin radius does NOT enter this term, in
 *       Vilfan or here.</li>
 *   <li><b>Angular</b> {@code theta_i} = the signed angle, about the filament axis, from the LABORATORY
 *       SUBSTRATE-FACING direction to the site's outward material normal, wrapped to {@code (-pi, pi]}.
 *       Vilfan's zero-energy azimuth is "pointing towards the myosin-covered surface", which is a single
 *       laboratory direction shared by all motors — not a per-motor direction. That is what is used here.</li>
 * </ul>
 *
 * <h2>Competing hazards, and the event/site split</h2>
 * <pre>
 *   K_total      = sum_i k_A^i                       (over candidate, unoccupied sites)
 *   P_attach     = 1 - exp(-K_total * dt)            (exact competing-hazard probability for one step)
 *   P(i|attach)  = k_A^i / K_total                   (conditional site choice, cumulative inversion)
 * </pre>
 * Exactly two random draws per eligible head per step: one event draw and one conditional-site draw, each
 * from a private counter-based wang stream keyed {@code (motor, step, seed)}. No existing RNG stream is
 * read or shifted, so the canonical path is bit-identical when this system is not invoked.
 *
 * <p>The site loop is a fixed, symmetric index window and the cumulative sum is accumulated in a fixed
 * index order, so the result carries no dependence on iteration order or thread scheduling and a same-seed
 * CPU repeat is bit-identical.
 *
 * <h2>Modes ({@code gradP[13]})</h2>
 * <pre>
 *   0  OFF                      the kernel is never called
 *   1  VILFAN_ABSOLUTE          published k_A sets the clock  (PRIMARY)
 *   2  VILFAN_HYBRID            SoftBox's own eligibility sets the clock; Vilfan sets only the site weights
 *   3  ANGULAR_NEUTRAL          alpha := 0   (control: longitudinal term only)
 *   4  LONGITUDINAL_NEUTRAL     K := 0       (control: angular term only)
 * </pre>
 *
 * <h2>gradData layout</h2>
 * A single planar buffer of size {@code 8*N + nSiteFlags} so the kernel stays within the 15-argument task
 * limit. Per motor {@code m}: {@code [0..2]*N+m} = the motor's substrate anchor (x,y,z, µm);
 * {@code [3]*N+m} OUT {@code K_total} (1/s) at the last evaluation; {@code [4]*N+m} OUT {@code xi} (nm) of
 * the chosen site; {@code [5]*N+m} OUT {@code theta} (rad) of the chosen site; {@code [6]*N+m} OUT the
 * hazard fraction lying in the outermost ring of the window (the discarded-mass diagnostic);
 * {@code [7]*N+m} IN a no-bind flag. The tail {@code [8*N ...]} is a dense per-site occupancy flag array.
 */
public final class VilfanGradedBindingSystem {
    private VilfanGradedBindingSystem() {}

    public static final int MODE_OFF = 0, MODE_ABSOLUTE = 1, MODE_HYBRID = 2,
                            MODE_ANGULAR_NEUTRAL = 3, MODE_LONGITUDINAL_NEUTRAL = 4;

    /** Published Vilfan Table I values. Not tunable, not fitted, and never changed after seeing a result. */
    public static final double VILFAN_KA_PER_S   = 50.0;      // maximum attachment rate
    public static final double VILFAN_K_PN_PER_NM = 0.5;      // longitudinal myosin stiffness
    public static final double VILFAN_ALPHA       = 4.0;      // K_theta / kT  (paper uses 4, 6, 8)
    public static final double KT_PN_NM           = 4.14e-21 * 1e21;   // 4.14 pN·nm

    /** Private counter-based streams. Neither collides with any existing SoftBox salt. */
    static final long SALT_EVENT = 0x56475F45L;   // "VG_E"
    static final long SALT_SITE  = 0x56475F53L;   // "VG_S"
    /** The shadow (no-depletion) sampler's own streams — deliberately disjoint from the physical motor's. */
    static final long SALT_SHADOW_EVENT = 0x56475F58L;   // "VG_X"
    static final long SALT_SHADOW_SITE  = 0x56475F59L;   // "VG_Y"

    /** PTX-safe uniform in (0,1); the identical construction the Brownian and target-zone kernels use. */
    static double u01(long ep, long t, long salt) {
        long h = ((ep * 2654435761L) ^ (t * 40503L) ^ (salt * 0x9E3779B1L));
        h ^= (h >>> 13); h *= 0x9E3779B1L; h ^= (h >>> 16);
        return ((h & 0xFFFFFF) + 1) / 16777217.0;
    }

    /**
     * Rebuild the dense per-site occupancy flag tail of {@code gradData} from the currently bound heads.
     * Single-threaded by construction (it writes a shared array), and must run immediately before
     * {@link #gradedAttach} so that stale claims from earlier steps cannot survive.
     */
    public static void refreshOccupancy(IntArray boundSeg, IntArray bindSite, DoubleArray gradData,
                                        DoubleArray gradP, IntArray counts) {
        int N = counts.get(0);
        int nFlags = (int) gradP.get(16);
        if ((int) gradP.get(13) == MODE_OFF) return;
        for (@Parallel int gid = 0; gid < 1; gid++) {
            for (int k = 0; k < nFlags; k++) gradData.set(8 * N + k, 0.0);
            for (int m = 0; m < N; m++) {
                if (boundSeg.get(m) < 0) continue;
                int k = bindSite.get(m);
                if (k >= 0 && k < nFlags) gradData.set(8 * N + k, 1.0);
            }
        }
    }

    /**
     * One competing-hazard attachment step for every chemically eligible free head.
     *
     * <p>{@code counts}: [N, _, _, nSeg]. {@code matc}: [t, seed, …]. Established bonds are untouched:
     * this kernel only ever turns a FREE head into a bound one, never the reverse, and never re-snaps a
     * head that is already attached.
     */
    public static void gradedAttach(IntArray boundSeg, IntArray justBound, IntArray nuc,
            FloatArray filCoord, FloatArray filUVec, FloatArray filYVec, FloatArray filSegLength,
            FloatArray segCumArc, FloatArray bindArc, FloatArray bindAzim, IntArray bindSite,
            DoubleArray gradData, DoubleArray gradP, IntArray matc, IntArray counts) {
        int N = counts.get(0), nSeg = counts.get(3);
        int mode = (int) gradP.get(13);
        if (mode == MODE_OFF) return;
        double rise = gradP.get(0), twistRate = gradP.get(1), stairPhase = gradP.get(2);
        double Kpn = gradP.get(4), alpha = gradP.get(5), kA = gradP.get(6), dt = gradP.get(7), kT = gradP.get(8);
        int win = (int) gradP.get(9);
        double dnx = gradP.get(10), dny = gradP.get(11), dnz = gradP.get(12);   // substrate-facing lab direction
        int nFlags = (int) gradP.get(16);
        if (mode == MODE_ANGULAR_NEUTRAL) alpha = 0.0;
        if (mode == MODE_LONGITUDINAL_NEUTRAL) Kpn = 0.0;
        double cLong = 0.5 * Kpn / kT;          // U_long/kT = cLong * xi^2   (xi in nm)
        double cAng  = 0.5 * alpha;             // U_ang /kT = cAng  * theta^2
        long tt = matc.get(0), seed = matc.get(1);
        for (@Parallel int m = 0; m < N; m++) {
            if (mode != MODE_HYBRID) justBound.set(m, 0);
            if (gradData.get(7 * N + m) != 0.0) continue; // no-bind motor
            if (mode == MODE_HYBRID) {
                // HYBRID: matBindExplicit has already run and set boundSeg for the heads SoftBox's own
                // clock chose to attach this step. A freshly bound head is one with no latched site yet.
                // Vilfan's weights then decide only WHICH site it takes.
                if (boundSeg.get(m) < 0 || bindSite.get(m) >= 0) continue;
            } else {
                if (boundSeg.get(m) != -1) continue;      // already attached — never re-snapped
                if (nuc.get(m) != 2) continue;            // CHEMICAL ELIGIBILITY (SoftBox, unchanged)
            }
            double ax = gradData.get(m), ay = gradData.get(N + m), az = gradData.get(2 * N + m);
            // ---- locate the segment whose axis this motor sits under (clamped closest point) -------------
            int bs = -1; double bd = 1e30;
            for (int s = 0; s < nSeg; s++) {
                double half = 0.5 * filSegLength.get(s);
                double dx = ax - filCoord.get(s), dy = ay - filCoord.get(nSeg + s), dz = az - filCoord.get(2 * nSeg + s);
                double ux = filUVec.get(s), uy = filUVec.get(nSeg + s), uz = filUVec.get(2 * nSeg + s);
                double f = dx * ux + dy * uy + dz * uz;
                double fc = f < -half ? -half : (f > half ? half : f);
                double qx = dx - fc * ux, qy = dy - fc * uy, qz = dz - fc * uz;
                double d2 = qx * qx + qy * qy + qz * qz;
                if (d2 < bd) { bd = d2; bs = s; }
            }
            if (mode == MODE_HYBRID) bs = boundSeg.get(m);
            if (bs < 0) continue;
            int s = bs;
            double half = 0.5 * filSegLength.get(s), cum = segCumArc.get(s);
            double cx = filCoord.get(s), cy = filCoord.get(nSeg + s), cz = filCoord.get(2 * nSeg + s);
            double ux = filUVec.get(s), uy = filUVec.get(nSeg + s), uz = filUVec.get(2 * nSeg + s);
            double yx = filYVec.get(s), yy = filYVec.get(nSeg + s), yz = filYVec.get(2 * nSeg + s);
            double zx = uy * yz - uz * yy, zy = uz * yx - ux * yz, zz = ux * yy - uy * yx;
            double zl = zx * zx + zy * zy + zz * zz;
            if (zl > 1e-30) { double iz = 1.0 / Math.sqrt(zl); zx *= iz; zy *= iz; zz *= iz; }
            // substrate-facing direction projected perpendicular to the filament axis = Vilfan's zero azimuth
            double dd = dnx * ux + dny * uy + dnz * uz;
            double px = dnx - dd * ux, py = dny - dd * uy, pz = dnz - dd * uz;
            double pl = px * px + py * py + pz * pz;
            if (pl < 1e-24) continue;
            double ip = 1.0 / Math.sqrt(pl); px *= ip; py *= ip; pz *= ip;
            double phiRef = azimuthOf(px, py, pz, yx, yy, yz, zx, zy, zz);
            // motor axial coordinate relative to the segment centre (µm), and the site index at its foot
            double mAx = (ax - cx) * ux + (ay - cy) * uy + (az - cz) * uz;
            int k0 = (int) ((cum + mAx + half) / rise + 0.5);
            // ---- PASS 1: the competing-hazard sum over the candidate window --------------------------
            double kTot = 0.0, kRing = 0.0;
            for (int j = -win; j <= win; j++) {
                int k = k0 + j;
                if (k < 0) continue;
                double la = k * rise - cum;
                if (la < 0.0 || la > 2.0 * half) continue;                 // site not on this segment
                if (k < nFlags && gradData.get(8 * N + k) != 0.0) continue;   // site already occupied
                double ki = siteHazard(la, half, mAx, rise, twistRate, stairPhase, k, phiRef, cLong, cAng, kA);
                kTot += ki;
                if (j == -win || j == win) kRing += ki;                    // outermost-ring hazard mass
            }
            gradData.set(3 * N + m, kTot);
            gradData.set(6 * N + m, kTot > 0.0 ? kRing / kTot : 0.0);
            if (!(kTot > 0.0)) { if (mode == MODE_HYBRID) boundSeg.set(m, -1); continue; }
            // ---- the attachment event ------------------------------------------------------------------
            if (mode != MODE_HYBRID) {                    // published absolute clock
                double pAtt = 1.0 - Math.exp(-kTot * dt);
                if (u01(m, tt, SALT_EVENT + seed * 7919L) >= pAtt) continue;
            }
            // ---- PASS 2: conditional site choice by cumulative inversion (same fixed index order) -------
            double target = u01(m, tt, SALT_SITE + seed * 7919L) * kTot;
            double acc = 0.0; int pick = -1; double pickLa = 0.0, pickPh = 0.0, pickXi = 0.0, pickTh = 0.0;
            for (int j = -win; j <= win; j++) {
                int k = k0 + j;
                if (k < 0) continue;
                double la = k * rise - cum;
                if (la < 0.0 || la > 2.0 * half) continue;
                if (k < nFlags && gradData.get(8 * N + k) != 0.0) continue;
                double ph = (stairPhase != 0.0) ? (k * stairPhase) : (twistRate * (la - half));
                double xi = ((la - half) - mAx) * 1e3;
                double th = wrapPi(ph - phiRef);
                double ki = kA * Math.exp(-(cLong * xi * xi + cAng * th * th));
                acc += ki;
                if (pick < 0 && acc >= target) { pick = k; pickLa = la; pickPh = ph; pickXi = xi; pickTh = th; }
            }
            if (pick < 0) { if (mode == MODE_HYBRID) boundSeg.set(m, -1); continue; }
            boundSeg.set(m, s);
            bindSite.set(m, pick);
            bindArc.set(m, (float) pickLa);
            bindAzim.set(m, (float) pickPh);
            justBound.set(m, 1);
            gradData.set(4 * N + m, pickXi);
            gradData.set(5 * N + m, pickTh);
            if (pick < nFlags) gradData.set(8 * N + pick, 1.0);            // claim the site
        }
    }

    /**
     * Wrap an angle into {@code (-pi, pi]} — this IS Vilfan's "{@code n} chosen such that
     * {@code Theta + i theta_0 + 2 pi n} falls into {@code [-pi, pi]}", implemented as the arithmetic it is.
     */
    static double wrapPi(double d) {
        double t = d * 0.15915494309189535;             // d / (2 pi)
        return d - 6.283185307179586 * Math.floor(t + 0.5);
    }

    /**
     * The azimuth, in the segment's own {@code (yVec, zVec)} material basis, of a unit direction already
     * projected perpendicular to the axis. One call per motor per step replaces a transcendental per SITE:
     * the site azimuths are analytic ({@code phi_k = twistRate * arc}), so the angular mismatch is the
     * WRAPPED SCALAR DIFFERENCE {@code wrapPi(phi_k - phi_ref)} — algebraically identical to the signed
     * angle about the axis, and exactly the form Vilfan writes.
     */
    static double azimuthOf(double px, double py, double pz, double yx, double yy, double yz,
                            double zx, double zy, double zz) {
        double cy = px * yx + py * yy + pz * yz, cz = px * zx + py * zy + pz * zz;
        double a = TwoBodyBeamAnalyticGpu.tzAngle(cz * cz, cy);
        return cz < 0.0 ? -a : a;
    }

    /** One site's Vilfan hazard, Eqs (1)+(2). Factored so the two passes are arithmetically identical. */
    static double siteHazard(double la, double half, double mAx, double rise, double twistRate,
            double stairPhase, int k, double phiRef, double cLong, double cAng, double kA) {
        double ph = (stairPhase != 0.0) ? (k * stairPhase) : (twistRate * (la - half));
        double xi = ((la - half) - mAx) * 1e3;             // nm, Vilfan's (X + i a - x_M)
        double th = wrapPi(ph - phiRef);                   // rad, Vilfan's (Theta + i theta_0 + 2 pi n)
        return kA * Math.exp(-(cLong * xi * xi + cAng * th * th));
    }

    /** Signed angle from a to b about axis u, wrapped to (-pi, pi] — the validated float-stable form. */
    static double signedAngle(double axx, double ayy, double azz, double bx, double by, double bz,
                              double ux, double uy, double uz) {
        double dot = axx * bx + ayy * by + azz * bz;
        double qx = ayy * bz - azz * by, qy = azz * bx - axx * bz, qz = axx * by - ayy * bx;
        double mag = TwoBodyBeamAnalyticGpu.tzAngle(qx * qx + qy * qy + qz * qz, dot);
        return (qx * ux + qy * uy + qz * uz) < 0.0 ? -mag : mag;
    }

    /**
     * SHADOW (no-depletion) SAMPLER — a diagnostic that never touches the physical motor.
     *
     * <p>It evaluates the SAME hazard landscape for every motor on every step regardless of whether that
     * motor is actually bound, and records the site it would have chosen. Because it is always available it
     * cannot experience first-passage depletion, so the difference between its attachment-position
     * distribution and the real head's isolates the depletion-driven before/after bias.
     *
     * <p>It writes ONLY into {@code shadow} and draws from its own dedicated streams, so forces, chemistry,
     * real site occupancy and every physical RNG stream are untouched. {@code shadow} is planar, stride 4:
     * {@code [0]} = 1 if it attached this step, {@code [1]} = xi (nm), {@code [2]} = theta (rad),
     * {@code [3]} = K_total (1/s).
     */
    public static void shadowSample(IntArray nuc, FloatArray filCoord, FloatArray filUVec, FloatArray filYVec,
            FloatArray filSegLength, FloatArray segCumArc, DoubleArray gradData, DoubleArray gradP,
            DoubleArray shadow, IntArray matc, IntArray counts) {
        int N = counts.get(0), nSeg = counts.get(3);
        int mode = (int) gradP.get(13);
        if (mode == MODE_OFF || gradP.get(15) == 0.0) return;
        double rise = gradP.get(0), twistRate = gradP.get(1), stairPhase = gradP.get(2);
        double Kpn = gradP.get(4), alpha = gradP.get(5), kA = gradP.get(6), dt = gradP.get(7), kT = gradP.get(8);
        int win = (int) gradP.get(9);
        double dnx = gradP.get(10), dny = gradP.get(11), dnz = gradP.get(12);
        if (mode == MODE_ANGULAR_NEUTRAL) alpha = 0.0;
        if (mode == MODE_LONGITUDINAL_NEUTRAL) Kpn = 0.0;
        double cLong = 0.5 * Kpn / kT, cAng = 0.5 * alpha;
        long tt = matc.get(0), seed = matc.get(1);
        for (@Parallel int m = 0; m < N; m++) {
            shadow.set(m, 0.0);
            if (gradData.get(7 * N + m) != 0.0) continue;
            double ax = gradData.get(m), ay = gradData.get(N + m), az = gradData.get(2 * N + m);
            int bs = -1; double bd = 1e30;
            for (int s = 0; s < nSeg; s++) {
                double half = 0.5 * filSegLength.get(s);
                double dx = ax - filCoord.get(s), dy = ay - filCoord.get(nSeg + s), dz = az - filCoord.get(2 * nSeg + s);
                double ux = filUVec.get(s), uy = filUVec.get(nSeg + s), uz = filUVec.get(2 * nSeg + s);
                double f = dx * ux + dy * uy + dz * uz;
                double fc = f < -half ? -half : (f > half ? half : f);
                double qx = dx - fc * ux, qy = dy - fc * uy, qz = dz - fc * uz;
                double d2 = qx * qx + qy * qy + qz * qz;
                if (d2 < bd) { bd = d2; bs = s; }
            }
            if (bs < 0) continue;
            int s = bs;
            double half = 0.5 * filSegLength.get(s), cum = segCumArc.get(s);
            double cx = filCoord.get(s), cy = filCoord.get(nSeg + s), cz = filCoord.get(2 * nSeg + s);
            double ux = filUVec.get(s), uy = filUVec.get(nSeg + s), uz = filUVec.get(2 * nSeg + s);
            double yx = filYVec.get(s), yy = filYVec.get(nSeg + s), yz = filYVec.get(2 * nSeg + s);
            double zx = uy * yz - uz * yy, zy = uz * yx - ux * yz, zz = ux * yy - uy * yx;
            double zl = zx * zx + zy * zy + zz * zz;
            if (zl > 1e-30) { double iz = 1.0 / Math.sqrt(zl); zx *= iz; zy *= iz; zz *= iz; }
            double dd = dnx * ux + dny * uy + dnz * uz;
            double px = dnx - dd * ux, py = dny - dd * uy, pz = dnz - dd * uz;
            double pl = px * px + py * py + pz * pz;
            if (pl < 1e-24) continue;
            double ip = 1.0 / Math.sqrt(pl); px *= ip; py *= ip; pz *= ip;
            double phiRef = azimuthOf(px, py, pz, yx, yy, yz, zx, zy, zz);
            double mAx = (ax - cx) * ux + (ay - cy) * uy + (az - cz) * uz;
            int k0 = (int) ((cum + mAx + half) / rise + 0.5);
            double kTot = 0.0;
            for (int j = -win; j <= win; j++) {
                int k = k0 + j; if (k < 0) continue;
                double la = k * rise - cum; if (la < 0.0 || la > 2.0 * half) continue;
                kTot += siteHazard(la, half, mAx, rise, twistRate, stairPhase, k, phiRef, cLong, cAng, kA);
            }
            shadow.set(3 * N + m, kTot);
            if (!(kTot > 0.0)) continue;
            double pAtt = 1.0 - Math.exp(-kTot * dt);
            if (u01(m, tt, SALT_SHADOW_EVENT + seed * 7919L) >= pAtt) continue;
            double target = u01(m, tt, SALT_SHADOW_SITE + seed * 7919L) * kTot;
            double acc = 0.0;
            for (int j = -win; j <= win; j++) {
                int k = k0 + j; if (k < 0) continue;
                double la = k * rise - cum; if (la < 0.0 || la > 2.0 * half) continue;
                double ph = (stairPhase != 0.0) ? (k * stairPhase) : (twistRate * (la - half));
                double xi = ((la - half) - mAx) * 1e3;
                double th = wrapPi(ph - phiRef);
                acc += kA * Math.exp(-(cLong * xi * xi + cAng * th * th));
                if (acc >= target) {
                    shadow.set(m, 1.0); shadow.set(N + m, xi); shadow.set(2 * N + m, th);
                    break;
                }
            }
        }
    }
}
