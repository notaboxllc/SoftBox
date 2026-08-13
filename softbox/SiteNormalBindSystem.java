package softbox;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.DoubleArray;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * CANONICAL SITE-NORMAL HEAD BINDING — the head axis, the site-normal capture gate, the bound site-relative
 * orientation potential and its equal-and-opposite filament reaction.
 *
 * <p><b>Noncanonical, flag-gated, DEFAULT-OFF, byte-identical when disabled.</b> Every kernel here is
 * additive; none is wired unless {@link ExplicitCompleteMatHarness#SITE_NORMAL_BIND} is on.
 * Report: {@code docs/motor/SITE_NORMAL_HEAD_BINDING.md}.
 *
 * <h2>THE SPECIFICATION</h2>
 * The canonical stereospecific bound pose is
 * <pre>
 *     xHeadHat  =  -n_site           equivalently   dot(xHeadHat, n_site) = -1
 * </pre>
 * i.e. the head's own local +x axis (its ellipsoid LONG axis) points radially INWARD, antiparallel to the
 * helically informed outward actin-site normal. The F8/binding end of the head therefore faces the filament
 * and the head body extends outward, away from actin.
 *
 * <h2>THE HEAD-LOCAL +x AXIS (corrected 2026-08-13)</h2>
 * The head is a rigid body carrying two material points in its own frame {@code {a_hat = +b_hat, n_hat = +e_up}}
 * (see {@link TwoBodyConverterMotor#R_F8}), BOTH on the ellipsoid's long axis:
 * <pre>
 *     r_F8   = (+3.5, 0) nm      the actin-binding point, 1.0 nm inboard of the +x tip (a = 4.5 nm)
 *     r_conv = (-3.5, 0) nm      the converter joint C, antipodal  ( = -r_F8 exactly )
 * </pre>
 * {@code matBeamGeom} builds {@code xF8 = C + R(d0)} and {@code xH = C - R(r_conv)}, so
 * {@code xF8 - xH = R(r_F8)} for every configuration. Because {@code r_F8} is axial,
 * <pre>
 *     eBind = normalize(xF8 - xH) = R(psi, chi) a_hat = xHeadHat        IDENTICALLY
 * </pre>
 * — one vector, three names: the ellipsoid long axis, the head-local +x axis, and the head-centre-to-F8
 * direction. There is NO fixed rotation between them and no second orientation convention anywhere in this
 * file or in {@code matS2SolveStepTilt}.
 *
 * <p><b>RETRACTED.</b> Until 2026-08-13 the code carried {@code r_F8 = (+3.5, +1.5) nm}, which put the F8
 * point {@code delta = atan2(1.5, 3.5) = 23.19859 deg} off the long axis. That transverse component was an
 * unintended artefact of the Exp-3C construction ("opposite corner"), never cited and never calibrated; the
 * earlier claim in this file that the offset was a deliberate rigid property of the head is WITHDRAWN.
 *
 * <h2>ONE AXIS FOR MECHANICS AND VISUALIZATION</h2>
 * {@link #headAxisStep} writes {@code xHeadHat} into {@code outGeom} rows 9..11, immediately after the
 * chi-aware {@link TwoBodyBeamAnalyticGpu#matBeamGeomTilt} writes rows 0..8. The capture gate, the bound
 * potential's diagnostics, the steric read-out and the viewer ellipsoid all read <b>that one buffer</b>. The
 * implicit solver necessarily re-forms {@code xHeadHat} from {@code (psi, chi)} as it iterates, but from the
 * identical closed form; the harness gates that identity numerically.
 *
 * <h2>DERIVATIVES (exact, and why they are trivial)</h2>
 * {@code psi} rotates the whole head rigidly about {@code econv} and {@code chi} rotates it rigidly about
 * {@code that = e0 x econv}, so for ANY head-fixed unit vector {@code v}:
 * {@code dv/dpsi = econv x v} and {@code dv/dchi = that x v}. On the head axis:
 * <pre>
 *     d xHeadHat / d psi = cos(chi) (econv x e0)
 *     d xHeadHat / d chi = cos(chi) econv - sin(chi) e0
 * </pre>
 * The two are orthogonal for every {@code chi}, so the Gauss-Newton orientation Hessian is diagonal:
 * {@code k cos^2(chi)} on (psi, psi) and {@code k} on (chi, chi), with NO cross term.
 *
 * <h2>THE BOUND POTENTIAL AND ITS REACTION</h2>
 * <pre>
 *     theta_bind = angle(xHeadHat, -n_boundSite)
 *     U_bind     = 1/2 k_bind theta_bind^2
 *     lambda     = k_bind theta_bind / sin(theta_bind)          ( -> k_bind as theta -> 0 )
 *     T_head     = lambda ( xHeadHat x eTarget )                eTarget = -n_boundSite
 *     T_fil      = -T_head                                       (EXACTLY, by construction)
 * </pre>
 * Both torques follow from the SAME {@code U_bind} by virtual work: rotating the head by {@code omega} gives
 * {@code dU = -lambda omega . (xHeadHat x eTarget)}, and rotating the FILAMENT (which carries
 * {@code n_site}, hence {@code eTarget}) by {@code omega} gives {@code dU = +lambda omega . (xHeadHat x
 * eTarget)}. Their sum vanishes identically for a rigid co-rotation of the head/site pair, which is Phase 5's
 * hard check and is exact here rather than approximate. No phenomenological twirling torque is introduced
 * anywhere in this file.
 *
 * <p>{@link #siteCoupleStep} evaluates the pair ONCE per step, at ONE configuration, immediately after
 * {@code bondForces}: it writes the filament reaction into the segment-side torque slot of {@code bondData}
 * ({@code d+9..11}, the byte-unchanged {@link CrossBridgeSystem#segGather} channel the head-roll registry
 * already uses) and the target {@code eTarget} into {@code restC} rows 3..5, which the implicit solver
 * consumes later in the SAME step. The motor side is then re-linearised implicitly about the solver's own
 * iterate — the identical semi-implicit asymmetry the F8 bond force already has (its {@code f8} is likewise
 * frozen from {@code bondForces} while the solve iterates).
 */
public final class SiteNormalBindSystem {
    private SiteNormalBindSystem() {}

    /** {@code bondData} stride (CrossBridgeSystem.STRIDE). */
    static final int STRIDE = 13;

    /**
     * {@code restC} layout once this feature exists (allocated {@code 8N}; rows 0..2 are the UNCHANGED
     * detached native-pose coefficients, so the detached potential is untouched by construction):
     * <pre>
     *   [0..2]N  c1,c2,c3   detached native head pose in the LIVE neck frame   (unchanged)
     *   [3..5]N  eTarget    = -n_boundSite, the bound site-relative target     (written here)
     *   [6N+m]   flag       1 = the site-normal bound potential is active for this motor
     *   [7N+m]   theta_bind at the evaluation configuration (rad), DIAGNOSTIC
     * </pre>
     */
    static final int R_TGT = 3, R_FLAG = 6, R_THETA = 7;

    /** {@code outGeom} rows 9..11 carry {@code xHeadHat} (allocated {@code 12N}). */
    static final int G_XHAT = 9;

    // ===================================================================================================
    // PHASE 0 — the head-local +x axis, computed ONCE per step into outGeom rows 9..11
    // ===================================================================================================
    /**
     * Write {@code xHeadHat} (the head-local +x axis = the ellipsoid long axis) into {@code outGeom} rows
     * 9..11 for every motor. Must run immediately after {@code matBeamGeomTilt} (or {@code matBeamGeom},
     * which is the {@code chi = 0} case) so rows 9..11 are consistent with rows 0..8.
     *
     * <p>It is computed as {@code normalize(xF8 - xH)} straight out of the geometry buffer, which — with the
     * corrected axial {@code r_F8} — IS the head-local +x axis. Deriving it from the SAME two points the
     * mechanics use means the drawn axis, the gated axis and the mechanical axis cannot drift apart by
     * construction. {@link #xHeadHatHost} builds the same vector ANALYTICALLY from {@code (psi, chi)} and the
     * base triad, and the harness gates that the two agree — that is the executable proof of the identity.
     *
     * <p>{@code frame}/{@code params}/{@code q}/{@code chiHead}/{@code convF} are retained in the signature
     * (unused) so the device task wiring and the analytic form stay interchangeable.
     */
    public static void headAxisStep(DoubleArray frame, DoubleArray params, DoubleArray q, DoubleArray chiHead,
                                    DoubleArray convF, DoubleArray outGeom, IntArray counts) {
        int nM = counts.get(0);
        for (@Parallel int m = 0; m < nM; m++) {
            double hx = outGeom.get(3 * nM + m), hy = outGeom.get(4 * nM + m), hz = outGeom.get(5 * nM + m);
            double dx = outGeom.get(6 * nM + m) - hx, dy = outGeom.get(7 * nM + m) - hy, dz = outGeom.get(8 * nM + m) - hz;
            double L = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (!(L > 1e-30)) { outGeom.set(G_XHAT * nM + m, 0.0); outGeom.set((G_XHAT + 1) * nM + m, 0.0);
                                outGeom.set((G_XHAT + 2) * nM + m, 0.0); continue; }
            double iL = 1.0 / L;
            outGeom.set(G_XHAT * nM + m,       dx * iL);
            outGeom.set((G_XHAT + 1) * nM + m, dy * iL);
            outGeom.set((G_XHAT + 2) * nM + m, dz * iL);
        }
    }

    // ===================================================================================================
    // PHASES 2/3/5 — latch-site target, U_bind, and the equal-and-opposite filament reaction
    // ===================================================================================================
    /**
     * For every BOUND motor: reconstruct the LATCHED site's current material-frame outward normal, form the
     * bound target {@code eTarget = -n_boundSite}, evaluate {@code U_bind} and apply its EXACT
     * equal-and-opposite reaction to the filament.
     *
     * <p>The site normal is rebuilt from the stored azimuth and the segment's LIVE rolling material frame
     * ({@code filUVec}/{@code filYVec}) — the identical reconstruction {@link CrossBridgeSystem#bondForcesSurface},
     * {@link ChiralSiteSystem#headRollStep} and {@code ChiralSiteSystem.accessMetrics} use — so it translates,
     * bends, rotates and ROLLS with the filament. Nothing lab-fixed and no stored base-frame direction enters.
     * The stored {@code bindAzim} carries the askew bound-interface offset {@code mirror*epsBind}; that offset
     * is removed here so the target is the TRUE site normal (with the canonical zero-skew scene,
     * {@code epsBind = 0}, the two coincide exactly).
     *
     * <p>Writes {@code bondData[d+9..11] -= T_head} (the segment-side torque slot summed into
     * {@code filament.torqueSum} by the byte-unchanged {@link CrossBridgeSystem#segGather}) and
     * {@code restC} rows 3..5 / 6 / 7 (target, flag, theta) for the implicit solver.
     *
     * <p>{@code snP}: [0] Ractin (um) [1] mirrorSign [2] epsBind (rad) [3] enable [4] k_bind (N.m/rad^2).
     */
    public static void siteCoupleStep(IntArray boundSeg, FloatArray bindArc, FloatArray bindAzim,
                                      FloatArray filCoord, FloatArray filUVec, FloatArray filYVec,
                                      FloatArray filSegLength, DoubleArray outGeom, FloatArray bondData,
                                      DoubleArray restC, DoubleArray snP, IntArray counts) {
        int N = counts.get(0), nSeg = counts.get(3);
        double Ract = snP.get(0), mirror = snP.get(1), epsBind = snP.get(2);
        int on = (int) snP.get(3);
        double kBind = snP.get(4);
        for (@Parallel int m = 0; m < N; m++) {
            restC.set(R_TGT * N + m, 0.0); restC.set((R_TGT + 1) * N + m, 0.0); restC.set((R_TGT + 2) * N + m, 0.0);
            restC.set(R_FLAG * N + m, 0.0); restC.set(R_THETA * N + m, 0.0);
            if (on == 0) continue;
            int s = boundSeg.get(m);
            if (s < 0) continue;
            double ux = filUVec.get(s), uy = filUVec.get(nSeg + s), uz = filUVec.get(2 * nSeg + s);
            double yx = filYVec.get(s), yy = filYVec.get(nSeg + s), yz = filYVec.get(2 * nSeg + s);
            double zx = uy * yz - uz * yy, zy = uz * yx - ux * yz, zz = ux * yy - uy * yx;
            double zl = zx * zx + zy * zy + zz * zz;
            if (!(zl > 1e-30)) continue;
            double iz = 1.0 / Math.sqrt(zl); zx *= iz; zy *= iz; zz *= iz;
            double phiSite = bindAzim.get(m) - mirror * epsBind;      // the TRUE site azimuth (offset removed)
            double cph = Math.cos(phiSite), sph = Math.sin(phiSite);
            double nx = cph * yx + sph * zx, ny = cph * yy + sph * zy, nz = cph * yz + sph * zz;
            double etx = -nx, ety = -ny, etz = -nz;                   // eTarget = -n_site  (THE specification)
            double hx = outGeom.get(G_XHAT * N + m), hy = outGeom.get((G_XHAT + 1) * N + m),
                   hz = outGeom.get((G_XHAT + 2) * N + m);
            double hl = hx * hx + hy * hy + hz * hz;
            if (!(hl > 1e-12)) continue;
            double d = hx * etx + hy * ety + hz * etz; if (d > 1.0) d = 1.0; if (d < -1.0) d = -1.0;
            double th = ChiralSiteSystem.cacos(d);
            double so = Math.sin(th); if (so < 1.0e-6) so = 1.0e-6;
            double lam = (th < 1.0e-3) ? kBind * (1.0 + th * th / 6.0) : kBind * th / so;
            // T_head = lambda ( xHeadHat x eTarget ) ; the filament reaction is EXACTLY -T_head
            double Tx = lam * (hy * etz - hz * ety);
            double Ty = lam * (hz * etx - hx * etz);
            double Tz = lam * (hx * ety - hy * etx);
            int dB = m * STRIDE;
            bondData.set(dB + 9,  (float) (bondData.get(dB + 9)  - Tx));
            bondData.set(dB + 10, (float) (bondData.get(dB + 10) - Ty));
            bondData.set(dB + 11, (float) (bondData.get(dB + 11) - Tz));
            restC.set(R_TGT * N + m, etx); restC.set((R_TGT + 1) * N + m, ety); restC.set((R_TGT + 2) * N + m, etz);
            restC.set(R_FLAG * N + m, 1.0); restC.set(R_THETA * N + m, th);
        }
    }

    // ===================================================================================================
    // HOST-SIDE HELPERS (not device kernels) — used by the gates, the figures and the viewer export
    // ===================================================================================================

    /** {@code delta = atan2(rF8y, rF8x)}: the FIXED angle between {@code eBind} and the head-local +x axis. */
    public static double deltaRad() {
        return Math.atan2(TwoBodyConverterMotor.R_F8[1], TwoBodyConverterMotor.R_F8[0]);
    }

    /**
     * The head axis built ANALYTICALLY from {@code (psi, chi)} and the base triad — deliberately NOT the same
     * arithmetic as {@link #headAxisStep}, which reads the geometry buffer. Their agreement is the executable
     * proof that {@code normalize(xF8 - xH)} IS the head-local +x axis; it is also what the FD gates
     * differentiate at an arbitrary pose.
     */
    public static double[] xHeadHatHost(ExplicitCompleteMatHarness.ExMat e, int m, double psi, double chi) {
        int N = e.N;
        double bx, by, bz, ex, ey, ez, ux, uy, uz;
        if (e.convF.get(12 * N + m) == 0.0) {
            bx = e.frame.get(m); by = e.frame.get(N + m); bz = e.frame.get(2 * N + m);
            ex = e.frame.get(3 * N + m); ey = e.frame.get(4 * N + m); ez = e.frame.get(5 * N + m);
            ux = e.frame.get(6 * N + m); uy = e.frame.get(7 * N + m); uz = e.frame.get(8 * N + m);
        } else {
            bx = e.convF.get(m); by = e.convF.get(N + m); bz = e.convF.get(2 * N + m);
            ex = e.convF.get(3 * N + m); ey = e.convF.get(4 * N + m); ez = e.convF.get(5 * N + m);
            ux = e.convF.get(6 * N + m); uy = e.convF.get(7 * N + m); uz = e.convF.get(8 * N + m);
        }
        double rF8x = e.params.get(N + m), rF8y = e.params.get(2 * N + m);
        double p1x = bx * rF8x + ux * rF8y, p1y = by * rF8x + uy * rF8y, p1z = bz * rF8x + uz * rF8y;
        double ip = 1.0 / Math.sqrt(p1x * p1x + p1y * p1y + p1z * p1z); p1x *= ip; p1y *= ip; p1z *= ip;
        double q1x = ey * p1z - ez * p1y, q1y = ez * p1x - ex * p1z, q1z = ex * p1y - ey * p1x;
        double cpsi = Math.cos(psi), spsi = Math.sin(psi), cchi = Math.cos(chi), schi = Math.sin(chi);
        double e0x = cpsi * p1x + spsi * q1x, e0y = cpsi * p1y + spsi * q1y, e0z = cpsi * p1z + spsi * q1z;
        double f0x = cpsi * q1x - spsi * p1x, f0y = cpsi * q1y - spsi * p1y, f0z = cpsi * q1z - spsi * p1z;
        double ebx = cchi * e0x + schi * ex, eby = cchi * e0y + schi * ey, ebz = cchi * e0z + schi * ez;
        double rn = Math.sqrt(rF8x * rF8x + rF8y * rF8y), cdel = rF8x / rn, sdel = rF8y / rn;
        return new double[]{ cdel * ebx + sdel * f0x, cdel * eby + sdel * f0y, cdel * ebz + sdel * f0z };
    }

    /**
     * Outward radial material normal + surface position of the site a motor is CURRENTLY latched to.
     * Returns {@code {nx,ny,nz, sx,sy,sz}} or {@code null} if the motor is unbound / the frame is degenerate.
     */
    public static double[] boundSiteNormal(TwoBodyConverterMotor.Glide2D G, int nSeg, int m,
                                           double Ract, double mirror, double epsBind) {
        int s = G.mot.boundSeg.get(m);
        if (s < 0) return null;
        var f = G.fil;
        double ux = f.uVec.get(s), uy = f.uVec.get(nSeg + s), uz = f.uVec.get(2 * nSeg + s);
        double yx = f.yVec.get(s), yy = f.yVec.get(nSeg + s), yz = f.yVec.get(2 * nSeg + s);
        double zx = uy * yz - uz * yy, zy = uz * yx - ux * yz, zz = ux * yy - uy * yx;
        double zl = Math.sqrt(zx * zx + zy * zy + zz * zz); if (!(zl > 1e-30)) return null;
        zx /= zl; zy /= zl; zz /= zl;
        double ph = G.mot.bindAzim.get(m) - mirror * epsBind;
        double c = Math.cos(ph), sn = Math.sin(ph);
        double nx = c * yx + sn * zx, ny = c * yy + sn * zy, nz = c * yz + sn * zz;
        double aOff = G.mot.bindArc.get(m) - 0.5 * f.segLength.get(s);
        return new double[]{ nx, ny, nz,
                f.coord.get(s) + aOff * ux + Ract * nx,
                f.coord.get(nSeg + s) + aOff * uy + Ract * ny,
                f.coord.get(2 * nSeg + s) + aOff * uz + Ract * nz };
    }

    /** {@code U_bind / kT} for a head axis and a site normal (host; the FD/virtual-work gates use this). */
    public static double uBindKt(double[] xHat, double[] nSite, double kBind) {
        double d = -(xHat[0] * nSite[0] + xHat[1] * nSite[1] + xHat[2] * nSite[2]);
        if (d > 1) d = 1; if (d < -1) d = -1;
        double th = Math.acos(d);
        return 0.5 * kBind * th * th / Constants.kT;
    }

    /** {@code theta_bind = angle(xHeadHat, -n_site)} in DEGREES (host). */
    public static double thetaBindDeg(double[] xHat, double[] nSite) {
        double d = -(xHat[0] * nSite[0] + xHat[1] * nSite[1] + xHat[2] * nSite[2]);
        if (d > 1) d = 1; if (d < -1) d = -1;
        return Math.toDegrees(Math.acos(d));
    }
}
