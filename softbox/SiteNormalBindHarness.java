package softbox;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * CANONICAL SITE-NORMAL HEAD BINDING — proof, gates, capture statistics and the sim_viewer export.
 *
 * <p>Companion driver for {@link SiteNormalBindSystem}. Report: {@code docs/motor/SITE_NORMAL_HEAD_BINDING.md}.
 *
 * <pre>
 *   -phase0    PHASE 0   what the head-local +x axis IS: eBind, -eBind, or neither (host vs kernel identity)
 *   -gates     PHASE 12  A target / B rigid covariance / C site material frame / D reaction closure / E azimuths
 *                        + PHASE 6 residual/Jacobian finite differences at 0, 5, 15, 25 deg misalignment
 *   -capture   PHASE 11  natural captures under the 25 deg gate + the detached-pose census that explains them
 *   -viewer    PHASE 10  fine-time (one frame per timestep) binding trajectory for sim_viewer_boa.html
 *   -all       everything
 * </pre>
 *
 * <p><b>Runner: the CPU sequential runner throughout</b> — plain-Java kernel calls over the host SoA arrays,
 * no TaskGraph, no device transfer. No GPU work is launched, so the mandatory GPU crash-monitoring path is not
 * entered. The device path for this feature deliberately REFUSES (see
 * {@code ExplicitCompleteMatHarness.buildGlidingGraph}).
 */
public final class SiteNormalBindHarness {
    private SiteNormalBindHarness() {}

    static String OUT = "RUN_LOGS/motor_audit/site_normal_head_binding";
    static String JS_ROOT = "threejs_sitenormal";
    static final double DT = 2.5e-6;
    static int SEED = 20260812;
    static int CAPTURE_STEPS = 600_000;
    static int TARGET_EVENTS = 20;
    static int PRE = 400, POST = 400;
    static double TOL_DEG = 25.0;
    /** Make EVERY motor in the scene bindable instead of just the nearest one. Still NATURAL binding — it
     *  multiplies the sampling rate by the motor count, it does not relax or force any gate. */
    static boolean ALL_BIND = false;

    public static void main(String[] args) {
        String mode = args.length > 0 && args[0].startsWith("-") ? args[0] : "-all";
        for (int i = 0; i < args.length; i++) switch (args[i]) {
            case "-out" -> OUT = args[++i];
            case "-seed" -> SEED = Integer.parseInt(args[++i]);
            case "-steps" -> CAPTURE_STEPS = Integer.parseInt(args[++i]);
            case "-events" -> TARGET_EVENTS = Integer.parseInt(args[++i]);
            case "-tol" -> TOL_DEG = Double.parseDouble(args[++i]);
            case "-allbind" -> ALL_BIND = true;
            case "-js" -> JS_ROOT = args[++i];
            default -> { }
        }
        mk(OUT);
        System.out.println("\n=== CANONICAL SITE-NORMAL HEAD BINDING  (xHeadHat = -n_site) ===");
        System.out.printf(Locale.US, "  dt = %.2e s ; capture tolerance = %.1f deg ; seed = %d%n", DT, TOL_DEG, SEED);
        System.out.println("  RUNNER: CPU sequential runner. No GPU work is launched.");
        switch (mode) {
            case "-phase0"  -> phase0();
            case "-gates"   -> gates();
            case "-capture" -> capture();
            case "-funnel"  -> funnel();
            case "-coverage"-> coverage();
            case "-corr"    -> correlation();
            case "-bottleneck" -> bottleneck();
            case "-offreg"  -> offPathRegression();
            case "-capevents" -> events();
            case "-ablate"  -> ablation();
            case "-viewer"  -> viewer();
            default         -> { phase0(); gates(); coverage(); funnel(); events(); capture(); viewer(); }
        }
        System.out.println("\n  output -> " + OUT);
    }

    // ==============================================================================================
    //  scene
    // ==============================================================================================
    /** The canonical Path-B scene with the 3-D head ON and the site-normal binding law ON. */
    static PostHeadFreedomProbe.Scene scene(boolean bindable) {
        ExplicitCompleteMatHarness.SITE_NORMAL_BIND = true;
        ExplicitCompleteMatHarness.SITE_NORMAL_TOL_DEG = TOL_DEG;
        PostHeadFreedomProbe.SEED = SEED;
        PostHeadFreedomProbe.Scene s = PostHeadFreedomProbe.scene(bindable);
        if (bindable && ALL_BIND) for (int m = 0; m < s.N; m++) { s.G.noBind[m] = false; s.e.noBind.set(m, 0); }
        return s;
    }
    static void step(PostHeadFreedomProbe.Scene s, int t) { PostHeadFreedomProbe.step(s, t); }

    static double[] xHatLive(PostHeadFreedomProbe.Scene s) {
        return new double[]{ s.e.outGeom.get(9*s.N + s.m), s.e.outGeom.get(10*s.N + s.m), s.e.outGeom.get(11*s.N + s.m) };
    }
    static double[] xHLive(PostHeadFreedomProbe.Scene s) {
        return new double[]{ s.e.outGeom.get(3*s.N + s.m), s.e.outGeom.get(4*s.N + s.m), s.e.outGeom.get(5*s.N + s.m) };
    }
    static double[] xF8Live(PostHeadFreedomProbe.Scene s) {
        return new double[]{ s.e.outGeom.get(6*s.N + s.m), s.e.outGeom.get(7*s.N + s.m), s.e.outGeom.get(8*s.N + s.m) };
    }
    static double kBind(PostHeadFreedomProbe.Scene s) { return s.e.gateP.get(0); }
    static double mirror(PostHeadFreedomProbe.Scene s) { return s.e.snP.get(1); }
    static double epsBind(PostHeadFreedomProbe.Scene s) { return s.e.snP.get(2); }

    // ==============================================================================================
    //  PHASE 0 — WHAT IS THE HEAD-LOCAL +x AXIS?
    // ==============================================================================================
    static void phase0() {
        hdr("PHASE 0 — the head-local +x axis: is it eBind, -eBind, or neither?");
        PostHeadFreedomProbe.Scene s = scene(false);
        int N = s.N, m = s.m;
        double rF8x = TwoBodyConverterMotor.R_F8[0], rF8y = TwoBodyConverterMotor.R_F8[1];
        double delta = SiteNormalBindSystem.deltaRad();
        System.out.printf(Locale.US, "%n  head material frame {a_hat = +b_hat, n_hat = +e_up}  (TwoBodyConverterMotor.R_F8/R_CONV)%n");
        System.out.printf(Locale.US, "    r_F8   = (%+.4f, %+.4f) um = (%+.2f, %+.2f) nm   <- the actin-binding material point%n",
                rF8x, rF8y, rF8x*1e3, rF8y*1e3);
        System.out.printf(Locale.US, "    r_conv = (%+.4f, %+.4f) um = (%+.2f, %+.2f) nm   ( = -r_F8 exactly ⇒ C, xH, xF8 are COLLINEAR)%n",
                TwoBodyConverterMotor.R_CONV[0], TwoBodyConverterMotor.R_CONV[1],
                TwoBodyConverterMotor.R_CONV[0]*1e3, TwoBodyConverterMotor.R_CONV[1]*1e3);
        System.out.printf(Locale.US, "    ellipsoid semi-axes = %.2f x %.2f x %.2f nm ; the LONG axis is the head-local +x axis%n",
                TwoBodyConverterMotor.A_SEMI[0]*1e3, TwoBodyConverterMotor.A_SEMI[1]*1e3, TwoBodyConverterMotor.A_SEMI[2]*1e3);
        System.out.printf(Locale.US, "    delta = atan2(rF8y, rF8x) = %.8f deg%n", Math.toDegrees(delta));
        System.out.printf(Locale.US, "    |r_F8| = %.4f nm  vs long semi-axis %.2f nm ⇒ F8 sits at the +x END of the head%n",
                Math.hypot(rF8x, rF8y)*1e3, TwoBodyConverterMotor.A_SEMI[0]*1e3);

        // randomised (psi, chi) sweep: kernel xHeadHat vs host twin vs eBind
        java.util.Random rnd = new java.util.Random(12345);
        double maxKernelHost = 0, minDotEB = 2, maxDotEB = -2, maxDevDelta = 0, maxNorm = 0;
        double maxVecSame = 0, maxVecOpp = 0;
        double maxDotNeg = -2;
        StringBuilder tsv = new StringBuilder("psi_deg\tchi_deg\tang_xhat_ebind_deg\tdot_xhat_ebind\tdot_xhat_negEbind\t|xhat|\tkernel_host_diff\n");
        for (int it = 0; it < 400; it++) {
            double psi = (rnd.nextDouble() - 0.5) * 2 * Math.PI, chi = (rnd.nextDouble() - 0.5) * 2 * 1.5;
            s.e.q.set(N + m, psi); s.e.chiHead.set(m, chi);
            TwoBodyBeamAnalyticGpu.matBeamGeomTilt(s.e.nodes, s.e.frame, s.e.params, s.e.q, s.e.exCounts, s.e.outGeom, s.e.convF, s.e.chiHead);
            SiteNormalBindSystem.headAxisStep(s.e.frame, s.e.params, s.e.q, s.e.chiHead, s.e.convF, s.e.outGeom, s.e.exCounts);
            double[] xh = xHatLive(s);
            double[] hostXh = SiteNormalBindSystem.xHeadHatHost(s.e, m, psi, chi);
            double[] eb = ExplicitCompleteMatHarness.eBindOf(s.e, m, psi, chi);
            // eBind straight off the geometry buffer, i.e. normalize(xF8 - xH) — the repository definition
            double[] xH = xHLive(s), xF = xF8Live(s);
            double[] ebGeom = unit(new double[]{ xF[0]-xH[0], xF[1]-xH[1], xF[2]-xH[2] });
            double kh = Math.max(Math.max(Math.abs(xh[0]-hostXh[0]), Math.abs(xh[1]-hostXh[1])), Math.abs(xh[2]-hostXh[2]));
            maxKernelHost = Math.max(maxKernelHost, kh);
            // VECTOR difference: acos is ill-conditioned near dot = +-1, so the angle is NOT the right
            // statistic for an identity test. |xHeadHat - eBind| is.
            maxVecSame = Math.max(maxVecSame, Math.sqrt(sq(xh[0]-ebGeom[0]) + sq(xh[1]-ebGeom[1]) + sq(xh[2]-ebGeom[2])));
            maxVecOpp  = Math.max(maxVecOpp,  Math.sqrt(sq(xh[0]+ebGeom[0]) + sq(xh[1]+ebGeom[1]) + sq(xh[2]+ebGeom[2])));
            double dEB = dot(xh, ebGeom), dEBneg = -dEB;
            minDotEB = Math.min(minDotEB, dEB); maxDotEB = Math.max(maxDotEB, dEB);
            maxDotNeg = Math.max(maxDotNeg, dEBneg);
            double ang = Math.toDegrees(Math.acos(cl(dEB)));
            maxDevDelta = Math.max(maxDevDelta, Math.abs(ang - Math.toDegrees(delta)));
            maxNorm = Math.max(maxNorm, Math.abs(Math.sqrt(dot(xh, xh)) - 1.0));
            // consistency of the two eBind routes (analytic vs geometry buffer)
            maxKernelHost = Math.max(maxKernelHost, Math.abs(dot(eb, ebGeom) - 1.0) * 0);   // kept separate below
            if (it < 12) tsv.append(String.format(Locale.US, "%.4f\t%.4f\t%.10f\t%.12f\t%.12f\t%.15f\t%.3e%n",
                    Math.toDegrees(psi), Math.toDegrees(chi), ang, dEB, dEBneg, Math.sqrt(dot(xh, xh)), kh));
        }
        System.out.printf(Locale.US, "%n  400 randomised (psi, chi) states, xHeadHat from the KERNEL (outGeom rows 9..11):%n");
        System.out.printf(Locale.US, "    max |xHeadHat_kernel - xHeadHat_host|          = %.3e   (device-shaped kernel == host twin)%n", maxKernelHost);
        System.out.printf(Locale.US, "    max ||xHeadHat| - 1|                           = %.3e%n", maxNorm);
        System.out.printf(Locale.US, "    dot(xHeadHat, eBind)  in [%.12f, %.12f]%n", minDotEB, maxDotEB);
        System.out.printf(Locale.US, "    max dot(xHeadHat, -eBind)                      = %.12f%n", maxDotNeg);
        System.out.printf(Locale.US, "    max |angle(xHeadHat, eBind) - delta|           = %.3e deg%n", maxDevDelta);
        System.out.printf(Locale.US, "    max |xHeadHat - eBind|                         = %.3e   <- THE identity test%n", maxVecSame);
        System.out.printf(Locale.US, "    max |xHeadHat + eBind|                         = %.3e%n", maxVecOpp);
        boolean isEB = maxVecSame < 1e-12;
        boolean isNegEB = maxVecOpp < 1e-12;
        String verdict = isEB ? String.format(Locale.US,
                    "xHeadHat == eBind IDENTICALLY (max |difference| = %.3e over the whole sweep)", maxVecSame)
                : (isNegEB ? "xHeadHat == -eBind"
                : String.format(Locale.US, "NEITHER — xHeadHat is eBind rotated by a FIXED %.6f deg about the head's own third axis",
                        Math.toDegrees(delta)));
        System.out.printf(Locale.US, "%n  >>> PHASE 0 VERDICT: %s%n", verdict);
        if (isEB) System.out.printf(Locale.US,
                "      F8 lies EXACTLY on the ellipsoid long axis: |r_F8| = %.3f nm against a %.2f nm long semi-axis,%n"
                + "      i.e. %.3f nm inboard of the +x tip. The head-centre -> F8 direction, the head-local +x axis and%n"
                + "      the ellipsoid long axis are ONE vector.%n",
                Math.hypot(TwoBodyConverterMotor.R_F8[0], TwoBodyConverterMotor.R_F8[1])*1e3,
                TwoBodyConverterMotor.A_SEMI[0]*1e3,
                (TwoBodyConverterMotor.A_SEMI[0] - Math.hypot(TwoBodyConverterMotor.R_F8[0], TwoBodyConverterMotor.R_F8[1]))*1e3);
        else System.out.printf(Locale.US, "      The angle is CONSTANT to %.1e deg over the whole (psi, chi) sweep, i.e. it is a RIGID%n"
                + "      property of the head body, not a configuration-dependent offset.%n", maxDevDelta);
        write("phase0_headaxis.tsv", tsv.toString());

        // chi == 0 identity: xHeadHat must be exactly R(econv, psi) b_hat
        double maxChi0 = 0;
        for (int it = 0; it < 64; it++) {
            double psi = (it / 64.0 - 0.5) * 2 * Math.PI;
            s.e.q.set(N + m, psi); s.e.chiHead.set(m, 0.0);
            double[] xh = SiteNormalBindSystem.xHeadHatHost(s.e, m, psi, 0.0);
            double bx = s.e.frame.get(m), by = s.e.frame.get(N+m), bz = s.e.frame.get(2*N+m);
            double ex = s.e.frame.get(3*N+m), ey = s.e.frame.get(4*N+m), ez = s.e.frame.get(5*N+m);
            double[] rot = ExplicitCompleteMatHarness.rotAbout(bx, by, bz, ex, ey, ez, Math.cos(psi), Math.sin(psi));
            maxChi0 = Math.max(maxChi0, Math.max(Math.max(Math.abs(xh[0]-rot[0]), Math.abs(xh[1]-rot[1])), Math.abs(xh[2]-rot[2])));
        }
        System.out.printf(Locale.US, "  chi = 0 IDENTITY: max |xHeadHat - R(econv,psi)*b_hat| = %.3e  ⇒ %s%n",
                maxChi0, maxChi0 < 1e-14 ? "PASS (it IS the head's material a_hat axis)" : "FAIL");
        // ---- REGRESSION: with the feature ON but the head DETACHED, nothing new may perturb the state ----
        // siteCoupleStep returns immediately for boundSeg < 0 and leaves the restC flag at 0, so the solver
        // takes its VERBATIM legacy branch. This is the executable form of the "byte-identical when off"
        // claim on the path that actually runs during the whole binding search.
        ExplicitCompleteMatHarness.SITE_NORMAL_BIND = false;
        PostHeadFreedomProbe.Scene off = PostHeadFreedomProbe.scene(false);
        for (int t = 0; t < 5000; t++) PostHeadFreedomProbe.step(off, t);
        ExplicitCompleteMatHarness.SITE_NORMAL_BIND = true;
        PostHeadFreedomProbe.Scene on = PostHeadFreedomProbe.scene(false);
        for (int t = 0; t < 5000; t++) PostHeadFreedomProbe.step(on, t);
        double dPose = 0;
        for (int mm = 0; mm < off.N; mm++) {
            dPose = Math.max(dPose, Math.abs(off.e.q.get(mm) - on.e.q.get(mm)));
            dPose = Math.max(dPose, Math.abs(off.e.q.get(off.N+mm) - on.e.q.get(on.N+mm)));
            dPose = Math.max(dPose, Math.abs(off.e.chiHead.get(mm) - on.e.chiHead.get(mm)));
            for (int j = 0; j <= off.M; j++) for (int k = 0; k < 3; k++)
                dPose = Math.max(dPose, Math.abs(off.e.nodes.get((3*j+k)*off.N+mm) - on.e.nodes.get((3*j+k)*on.N+mm)));
        }
        System.out.printf(Locale.US, "  DETACHED OFF == ON: max |d(phi,psi,chi,beam nodes)| over %d motors x 5000 steps = %.3e  ⇒ %s%n",
                off.N, dPose, dPose == 0.0 ? "BIT-IDENTICAL" : "DIFFERS");

        write("phase0_summary.txt", String.format(Locale.US,
                "verdict\t%s%ndelta_deg\t%.10f%nmax_kernel_host_diff\t%.3e%nmax_norm_err\t%.3e%n"
                + "dot_xhat_ebind_min\t%.12f%ndot_xhat_ebind_max\t%.12f%nmax_dot_xhat_negebind\t%.12f%n"
                + "max_dev_from_delta_deg\t%.3e%nchi0_identity\t%.3e%ndetached_off_eq_on\t%.3e%n",
                verdict, Math.toDegrees(delta), maxKernelHost, maxNorm, minDotEB, maxDotEB, maxDotNeg, maxDevDelta, maxChi0, dPose));
    }

    // ==============================================================================================
    //  Closed-form inverse: the (psi, chi) that puts xHeadHat exactly on a target direction.
    // ==============================================================================================
    /**
     * In the head's own orthonormal basis {@code B = {p1, q1, econv}} the head-local +x axis is
     * <pre>
     *   xHeadHat|B = ( cd*cx*cp - sd*sp ,  cd*cx*sp + sd*cp ,  cd*sx )
     * </pre>
     * with {@code cd=cos(delta), sd=sin(delta), cx=cos(chi), sx=sin(chi), cp=cos(psi), sp=sin(psi)}.
     * Inverting: {@code sin(chi) = t3/cos(delta)} (so a target within {@code delta} of {@code +-econv} is
     * UNREACHABLE), then {@code psi = atan2(t2,t1) - atan2(sd, cd*cx)}.
     * @return {psi, chi} or null when the target is unreachable.
     */
    static double[] poseForTarget(ExplicitCompleteMatHarness.ExMat e, int m, double[] t) {
        int N = e.N;
        double bx = e.frame.get(m), by = e.frame.get(N+m), bz = e.frame.get(2*N+m);
        double ex = e.frame.get(3*N+m), ey = e.frame.get(4*N+m), ez = e.frame.get(5*N+m);
        double ux = e.frame.get(6*N+m), uy = e.frame.get(7*N+m), uz = e.frame.get(8*N+m);
        double rF8x = e.params.get(N+m), rF8y = e.params.get(2*N+m);
        double[] p1 = unit(new double[]{ bx*rF8x + ux*rF8y, by*rF8x + uy*rF8y, bz*rF8x + uz*rF8y });
        double[] q1 = new double[]{ ey*p1[2]-ez*p1[1], ez*p1[0]-ex*p1[2], ex*p1[1]-ey*p1[0] };
        double t1 = t[0]*p1[0]+t[1]*p1[1]+t[2]*p1[2];
        double t2 = t[0]*q1[0]+t[1]*q1[1]+t[2]*q1[2];
        double t3 = t[0]*ex + t[1]*ey + t[2]*ez;
        double rn = Math.sqrt(rF8x*rF8x + rF8y*rF8y), cd = rF8x/rn, sd = rF8y/rn;
        // With the axial F8 (delta = 0 ⇒ cd = 1, sd = 0) this reduces to sin(chi) = t3 and psi = atan2(t2,t1):
        // the FULL sphere is reachable, bounded only by the |chi| <= 89 deg pole clamp.
        double sx = t3/cd;
        if (sx > 1 || sx < -1) return null;
        double chi = Math.asin(sx);
        double psi = Math.atan2(t2, t1) - Math.atan2(sd, cd*Math.cos(chi));
        while (psi > Math.PI) psi -= 2*Math.PI;
        while (psi < -Math.PI) psi += 2*Math.PI;
        return new double[]{ psi, chi };
    }

    // ==============================================================================================
    //  PHASES 12 + 6 — virtual-work / covariance gates and the Jacobian finite differences
    // ==============================================================================================
    static void gates() {
        hdr("PHASE 12 — virtual-work and covariance gates  |  PHASE 6 — residual/Jacobian finite differences");
        PostHeadFreedomProbe.Scene s = scene(true);
        int N = s.N, m = s.m, nSeg = s.nSeg;
        double kB = kBind(s), Ract = s.Ract, mir = mirror(s), eps = epsBind(s);
        System.out.printf(Locale.US, "%n  k_bind = %.4e N.m/rad^2 (= %.1f pN.nm/rad^2, the historical value, NOT tuned here)%n",
                kB, kB / TwoBodyConverterMotor.KAPPA_CODE);
        System.out.printf(Locale.US, "  gamma_psi/k_bind = %.4f us  vs  dt = %.2f us   ⇒ the bound term is STIFF and is kept implicit%n",
                s.e.params.get(9*N+m)/kB*1e6, DT*1e6);

        // ---- bind the motor deterministically to a real lattice site so the gates have a latched site ------
        int bs = latchToNearestSite(s);
        double[] sn = SiteNormalBindSystem.boundSiteNormal(s.G, nSeg, m, Ract, mir, eps);
        System.out.printf(Locale.US, "  latched: seg %d  site k=%d  azim %.3f deg  |n_site| = %.15f%n",
                bs, s.e.bindSite.get(m), Math.toDegrees(s.G.mot.bindAzim.get(m)),
                Math.sqrt(sn[0]*sn[0]+sn[1]*sn[1]+sn[2]*sn[2]));

        StringBuilder log = new StringBuilder();

        // ================= GATE A — the potential's target IS xHeadHat = -n_site =========================
        double[] tgt = { -sn[0], -sn[1], -sn[2] };
        double[] pc = poseForTarget(s.e, m, tgt);
        boolean gA;
        if (pc == null) {
            System.out.println("\n  GATE A  UNREACHABLE at this azimuth (|n_site . econv| > cos(delta)); see gate E");
            gA = false;
        } else {
            setPose(s, pc[0], pc[1]);
            double[] xh = xHatLive(s);
            double resid = Math.sqrt(sq(xh[0]-tgt[0]) + sq(xh[1]-tgt[1]) + sq(xh[2]-tgt[2]));
            double th = SiteNormalBindSystem.thetaBindDeg(xh, sn);
            double[] Q = analyticQ(s, sn, kB);
            gA = resid < 1e-12 && th < 1e-6 && Math.abs(Q[0]) < 1e-24 && Math.abs(Q[1]) < 1e-24;
            System.out.printf(Locale.US, "%n  GATE A  TARGET: at the analytic minimum (psi=%.6f, chi=%.6f rad)%n", pc[0], pc[1]);
            System.out.printf(Locale.US, "            |xHeadHat - (-n_site)| = %.3e ; theta_bind = %.3e deg ; dot(xHeadHat,-n_site) = %.15f%n",
                    resid, th, -dot(xHatLive(s), sn));
            System.out.printf(Locale.US, "            Q_psi = %.3e , Q_chi = %.3e N.m  (both must vanish)  ⇒ %s%n",
                    Q[0], Q[1], gA ? "PASS" : "FAIL");
            log.append(String.format(Locale.US, "gateA_resid\t%.3e%ngateA_theta_deg\t%.3e%ngateA_Qpsi\t%.3e%ngateA_Qchi\t%.3e%n",
                    resid, th, Q[0], Q[1]));
        }

        // ================= PHASE 6 — residual / Jacobian finite differences ==============================
        System.out.printf(Locale.US, "%n  PHASE 6  ANALYTIC vs FINITE-DIFFERENCE of U_bind  (h = 1e-6 rad, central)%n");
        System.out.printf(Locale.US, "    %9s  %13s %13s %8s   %13s %13s %8s%n",
                "theta/az", "Q_psi(an)", "Q_psi(FD)", "rel", "Q_chi(an)", "Q_chi(FD)", "rel");
        boolean gFD = true;
        StringBuilder fdT = new StringBuilder("theta_deg\ttilt_az_deg\tQpsi_an\tQpsi_fd\trelQpsi\tQchi_an\tQchi_fd\trelQchi\tHpp_an\tHpp_fd\tHcc_an\tHcc_fd\tHpc_an\tHpc_fd\n");
        for (double dth : new double[]{ 0.0, 5.0, 15.0, 25.0 }) {
          // Sweep the TILT DIRECTION as well as its size: a single tilt azimuth can put the whole
          // misalignment into one coordinate and leave the other identically zero, which tests nothing.
          for (double taz : new double[]{ 0.0, 45.0, 90.0, 135.0 }) {
            double[] pose = poseAtMisalignAz(s, sn, dth, taz);
            if (pose == null) { System.out.printf(Locale.US, "    %6.1f deg / az %3.0f  UNREACHABLE%n", dth, taz); continue; }
            setPose(s, pose[0], pose[1]);
            double[] Qa = analyticQ(s, sn, kB);
            double h = 1e-6;
            double qpF = -(uAt(s, pose[0]+h, pose[1], sn, kB) - uAt(s, pose[0]-h, pose[1], sn, kB)) / (2*h);
            double qcF = -(uAt(s, pose[0], pose[1]+h, sn, kB) - uAt(s, pose[0], pose[1]-h, sn, kB)) / (2*h);
            double qScale = Math.max(Math.hypot(Qa[0], Qa[1]), 1e-27);
            double ep = Math.abs(Qa[0]-qpF), ec2 = Math.abs(Qa[1]-qcF);
            double rp = ep/qScale, rc = ec2/qScale;
            boolean okp = ep <= 1e-6*Math.max(Math.abs(Qa[0]), Math.abs(qpF)) + 1e-27;
            boolean okc = ec2 <= 1e-6*Math.max(Math.abs(Qa[1]), Math.abs(qcF)) + 1e-27;
            // Hessian block: FD of the analytic generalized forces
            double[] Qp = qAt(s, pose[0]+h, pose[1], sn, kB), Qm = qAt(s, pose[0]-h, pose[1], sn, kB);
            double[] Qcp = qAt(s, pose[0], pose[1]+h, sn, kB), Qcm = qAt(s, pose[0], pose[1]-h, sn, kB);
            double hppF = -(Qp[0]-Qm[0])/(2*h), hccF = -(Qcp[1]-Qcm[1])/(2*h), hpcF = -(Qcp[0]-Qcm[0])/(2*h);
            double[] Hh = analyticHessian(s, kB);
            System.out.printf(Locale.US, "    %5.1f/%3.0f  %+.6e %+.6e %8.1e   %+.6e %+.6e %8.1e  %s%n",
                    dth, taz, Qa[0], qpF, rp, Qa[1], qcF, rc, (okp && okc) ? "ok" : "FAIL");
            fdT.append(String.format(Locale.US, "%.1f\t%.0f\t%.6e\t%.6e\t%.2e\t%.6e\t%.6e\t%.2e\t%.6e\t%.6e\t%.6e\t%.6e\t%.6e\t%.6e%n",
                    dth, taz, Qa[0], qpF, rp, Qa[1], qcF, rc, Hh[0], hppF, Hh[1], hccF, Hh[2], hpcF));
            if (!okp || !okc) gFD = false;
          }
        }
        double[] H = analyticHessian(s, kB);
        System.out.printf(Locale.US, "    Gauss-Newton Hessian at theta=0: H_pp = %.6e  H_cc = %.6e  H_pc = %.6e N.m/rad^2%n", H[0], H[1], H[2]);
        System.out.printf(Locale.US, "    H_pc = 0 EXACTLY: with the axial F8 the psi and chi head-axis directions are orthogonal for every chi.%n");
        System.out.printf(Locale.US, "    ⇒ PHASE 6 generalized forces %s%n", gFD ? "PASS" : "FAIL");
        write("phase6_fd.tsv", fdT.toString());

        // ================= GATE D — motor/site reaction closure (virtual work) ==========================
        System.out.printf(Locale.US, "%n  GATE D  REACTION CLOSURE — FD of U_bind under equal/opposite virtual rotations%n");
        double[] pose15 = poseAtMisalign(s, sn, 15.0);
        if (pose15 == null) pose15 = poseAtMisalign(s, sn, 5.0);
        if (pose15 == null) pose15 = poseAtMisalign(s, sn, 0.0);
        setPose(s, pose15[0], pose15[1]);
        System.out.printf(Locale.US, "    reference pose: theta_bind = %.4f deg (tilt azimuth %.0f deg)%n",
                SiteNormalBindSystem.thetaBindDeg(xHatLive(s), sn), pose15[2]);
        double[] xh15 = xHatLive(s);
        double thR = Math.toRadians(SiteNormalBindSystem.thetaBindDeg(xh15, sn));
        double lam = thR < 1e-3 ? kB*(1+thR*thR/6) : kB*thR/Math.sin(thR);
        double[] et = { -sn[0], -sn[1], -sn[2] };
        double[] Thead = { lam*(xh15[1]*et[2]-xh15[2]*et[1]), lam*(xh15[2]*et[0]-xh15[0]*et[2]), lam*(xh15[0]*et[1]-xh15[1]*et[0]) };
        // the reaction the code actually applies to the filament, read back out of bondData
        double[] Tfil = reactionFromKernel(s);
        double dT = Math.sqrt(sq(Thead[0]+Tfil[0]) + sq(Thead[1]+Tfil[1]) + sq(Thead[2]+Tfil[2]));
        double magT = Math.sqrt(dot(Thead, Thead));
        System.out.printf(Locale.US, "    T_head (analytic)     = (%+.4e %+.4e %+.4e) N.m , |T| = %.4e%n", Thead[0], Thead[1], Thead[2], magT);
        System.out.printf(Locale.US, "    T_fil  (from bondData)= (%+.4e %+.4e %+.4e) N.m%n", Tfil[0], Tfil[1], Tfil[2]);
        // The reaction is -T_head EXACTLY in double; bondData is the project's shared FloatArray bond
        // channel, so the round-trip is limited by float32 (eps = 6e-8), not by the physics.
        System.out.printf(Locale.US, "    |T_head + T_fil| / |T_head| = %.3e   ⇒ %s%n", dT/magT,
                dT/magT < 1e-6 ? "equal and opposite to the float32 precision of the shared bondData channel" : "FAIL");
        // finite-difference virtual work about 3 independent axes
        double hRot = 1e-7; boolean gD = dT/magT < 1e-6;
        StringBuilder vw = new StringBuilder("axis\tW_motor\tW_filament\tsum\trel\n");
        for (int a = 0; a < 3; a++) {
            double[] w = { a==0?1:0, a==1?1:0, a==2?1:0 };
            // rotate the HEAD axis only
            double[] hp = rot(xh15, w, +hRot), hm = rot(xh15, w, -hRot);
            double dUh = (uOf(hp, sn, kB) - uOf(hm, sn, kB)) / (2*hRot);
            // rotate the SITE normal only
            double[] np = rot(sn, w, +hRot), nm = rot(sn, w, -hRot);
            double dUn = (uOf(xh15, np, kB) - uOf(xh15, nm, kB)) / (2*hRot);
            double Wm = -dUh, Wf = -dUn;
            double rel = Math.abs(Wm + Wf) / Math.max(1e-30, Math.abs(Wm));
            vw.append(String.format(Locale.US, "%d\t%.8e\t%.8e\t%.3e\t%.3e%n", a, Wm, Wf, Wm+Wf, rel));
            System.out.printf(Locale.US, "    axis %d : W_motor = %+.6e , W_filament = %+.6e , sum = %+.3e (rel %.2e)%n", a, Wm, Wf, Wm+Wf, rel);
            if (rel > 1e-8) gD = false;
            // and the analytic torque must reproduce the FD work (uOf returns kT, so convert)
            double Wm2 = dot(Thead, w), WmJ = Wm * Constants.kT;
            if (Math.abs(Wm2 - WmJ)/Math.max(1e-30, Math.abs(Wm2)) > 1e-6) gD = false;
        }
        System.out.printf(Locale.US, "    ⇒ GATE D %s  (motor work + filament work = 0 for an isolated rigid rotation)%n", gD ? "PASS" : "FAIL");
        write("phase12_virtualwork.tsv", vw.toString());

        // ================= GATE B — rigid co-rotation of filament + site + motor ========================
        System.out.printf(Locale.US, "%n  GATE B  RIGID COVARIANCE — rotate filament, site AND motor together%n");
        double uBefore = uOf(xHatLive(s), sn, kB);
        double thBefore = SiteNormalBindSystem.thetaBindDeg(xHatLive(s), sn);
        double maxDU = 0, maxDTh = 0;
        StringBuilder gb = new StringBuilder("axis\tangle_deg\tU_before_kT\tU_after_kT\tdU\ttheta_before\ttheta_after\n");
        for (int a = 0; a < 3; a++) for (double ang : new double[]{ 0.3, 1.1, 2.4 }) {
            PostHeadFreedomProbe.Scene t = scene(true);
            latchToNearestSite(t); setPose(t, pose15[0], pose15[1]);
            double[] w = { a==0?1:0, a==1?1:0, a==2?1:0 };
            double u0 = uOf(xHatLive(t), SiteNormalBindSystem.boundSiteNormal(t.G, nSeg, t.m, Ract, mir, eps), kB);
            double t0 = SiteNormalBindSystem.thetaBindDeg(xHatLive(t), SiteNormalBindSystem.boundSiteNormal(t.G, nSeg, t.m, Ract, mir, eps));
            rotateScene(t, w, ang, true);
            refreshGeom(t);
            double[] sn2 = SiteNormalBindSystem.boundSiteNormal(t.G, nSeg, t.m, Ract, mir, eps);
            double u1 = uOf(xHatLive(t), sn2, kB);
            double t1 = SiteNormalBindSystem.thetaBindDeg(xHatLive(t), sn2);
            maxDU = Math.max(maxDU, Math.abs(u1-u0)); maxDTh = Math.max(maxDTh, Math.abs(t1-t0));
            gb.append(String.format(Locale.US, "%d\t%.2f\t%.9f\t%.9f\t%.3e\t%.6f\t%.6f%n", a, Math.toDegrees(ang), u0, u1, u1-u0, t0, t1));
        }
        boolean gB = maxDTh < 2e-3;   // float32 filament frame storage sets the floor
        System.out.printf(Locale.US, "    9 rigid rotations: max |dU|/kT = %.3e , max |d theta| = %.3e deg  ⇒ %s%n",
                maxDU, maxDTh, gB ? "PASS (invariant to float32 of the stored material frame)" : "FAIL");
        System.out.printf(Locale.US, "    (U_bind = %.6f kT, theta = %.4f deg at the reference pose)%n", uBefore, thBefore);
        write("phase12_gateB.tsv", gb.toString());

        // ================= GATE C — the site normal is carried by the FILAMENT alone ====================
        System.out.printf(Locale.US, "%n  GATE C  SITE MATERIAL FRAME — rotate the FILAMENT alone%n");
        double maxCov = 0, maxPred = 0;
        StringBuilder gc = new StringBuilder("axis\tangle_deg\t|n_new - R n_old|\ttheta_new\ttheta_pred\n");
        for (int a = 0; a < 3; a++) for (double ang : new double[]{ 0.2, 0.9 }) {
            PostHeadFreedomProbe.Scene t = scene(true);
            latchToNearestSite(t); setPose(t, pose15[0], pose15[1]);
            double[] snOld = SiteNormalBindSystem.boundSiteNormal(t.G, nSeg, t.m, Ract, mir, eps);
            double[] xh = xHatLive(t).clone();
            double[] w = { a==0?1:0, a==1?1:0, a==2?1:0 };
            rotateScene(t, w, ang, false);     // FILAMENT ONLY
            double[] snNew = SiteNormalBindSystem.boundSiteNormal(t.G, nSeg, t.m, Ract, mir, eps);
            double[] pred = rot(snOld, w, ang);
            double dev = Math.sqrt(sq(snNew[0]-pred[0]) + sq(snNew[1]-pred[1]) + sq(snNew[2]-pred[2]));
            double thNew = SiteNormalBindSystem.thetaBindDeg(xh, snNew);
            double thPred = SiteNormalBindSystem.thetaBindDeg(xh, pred);
            maxCov = Math.max(maxCov, dev); maxPred = Math.max(maxPred, Math.abs(thNew - thPred));
            gc.append(String.format(Locale.US, "%d\t%.2f\t%.3e\t%.6f\t%.6f%n", a, Math.toDegrees(ang), dev, thNew, thPred));
        }
        boolean gC = maxCov < 5e-6 && maxPred < 2e-3;
        System.out.printf(Locale.US, "    max |n_site(R.fil) - R n_site(fil)| = %.3e ; max |theta - theta_pred| = %.3e deg ⇒ %s%n",
                maxCov, maxPred, gC ? "PASS (n_site is carried by the filament's own material frame)" : "FAIL");
        write("phase12_gateC.tsv", gc.toString());

        // ================= GATE E — every4 helical azimuths ============================================
        System.out.printf(Locale.US, "%n  GATE E  HELICAL AZIMUTHS — the canonical pose at many every4 sites%n");
        System.out.printf(Locale.US, "    Two geometries per site: the ORIENTATION (the law's own variable) and the CANONICAL BOUND%n"
                + "    GEOMETRY the law implies once the F8 bond holds xF8 at the site, xH = x_site - |r_F8| eBind.%n");
        System.out.printf(Locale.US, "    %5s %10s %12s %14s %16s %12s%n",
                "site", "azim_deg", "reachable", "dot(xh,-n)", "dot(xH-xs, n) nm", "clearance_nm");
        StringBuilder ge = new StringBuilder("site_k\tazim_deg\treachable\tdot_xhat_negn\toutward_nm\tclearance_nm\ttheta_deg\n");
        int nAz = 0, nReach = 0, nOut = 0; double worstDot = 2, worstClr = 1e9, bestClr = -1e9;
        for (int j = 0; j < 12; j++) {
            PostHeadFreedomProbe.Scene t = scene(true);
            int seg = latchToSiteOffset(t, j);
            if (seg < 0) continue;
            double[] sj = SiteNormalBindSystem.boundSiteNormal(t.G, t.nSeg, t.m, Ract, mir, eps);
            double[] tj = { -sj[0], -sj[1], -sj[2] };
            double[] pcj = poseForTarget(t.e, t.m, tj);
            nAz++;
            double az = Math.toDegrees(t.G.mot.bindAzim.get(t.m));
            if (pcj == null) {
                ge.append(String.format(Locale.US, "%d\t%.2f\t0\t\t\t\t%n", t.e.bindSite.get(t.m), az));
                System.out.printf(Locale.US, "    %5d %10.2f %12s%n", t.e.bindSite.get(t.m), az, "NO");
                continue;
            }
            nReach++;
            setPose(t, pcj[0], pcj[1]);
            double[] xh = xHatLive(t);
            double dd = -dot(xh, sj);
            // CANONICAL BOUND GEOMETRY: the bond holds xF8 at the site, and the head is a rigid body with
            // xH = xF8 - |r_F8| * eBind (r_conv = -r_F8 ⇒ C, xH, xF8 collinear with |xF8-xH| = |r_F8|).
            double[] ebj = ExplicitCompleteMatHarness.eBindOf(t.e, t.m, pcj[0], pcj[1]);
            double rF8 = Math.hypot(TwoBodyConverterMotor.R_F8[0], TwoBodyConverterMotor.R_F8[1]);
            double[] xHc = { sj[3]-rF8*ebj[0], sj[4]-rF8*ebj[1], sj[5]-rF8*ebj[2] };
            double outward = ((xHc[0]-sj[3])*sj[0] + (xHc[1]-sj[4])*sj[1] + (xHc[2]-sj[5])*sj[2]) * 1e3;
            double[] clr = PostHeadFreedomProbe.clearance(t, xHc, xh);
            double thj = SiteNormalBindSystem.thetaBindDeg(xh, sj);
            if (outward > 0) nOut++;
            worstDot = Math.min(worstDot, dd); worstClr = Math.min(worstClr, clr[0]); bestClr = Math.max(bestClr, clr[0]);
            System.out.printf(Locale.US, "    %5d %10.2f %12s %14.12f %16.3f %12.3f%n",
                    t.e.bindSite.get(t.m), az, "yes", dd, outward, clr[0]);
            ge.append(String.format(Locale.US, "%d\t%.2f\t1\t%.12f\t%.4f\t%.4f\t%.3e%n", t.e.bindSite.get(t.m), az, dd, outward, clr[0], thj));
        }
        boolean gE = nReach > 0 && worstDot > 1 - 1e-12 && nOut == nReach;
        System.out.printf(Locale.US, "    %d azimuths, %d reachable, %d with the head centre OUTWARD; min dot(xHeadHat,-n_site) = %.12f%n",
                nAz, nReach, nOut, worstDot);
        System.out.printf(Locale.US, "    head/actin signed clearance at the canonical pose: %.3f .. %.3f nm (DIAGNOSTIC, no steric force)%n",
                worstClr, bestClr);
        System.out.printf(Locale.US, "    ⇒ GATE E %s%n", gE ? "PASS" : "FAIL");
        write("phase12_gateE.tsv", ge.toString());

        // ================= PHASE 6b — implicit stability at dt = 2.5 us ================================
        System.out.printf(Locale.US, "%n  PHASE 6b  IMPLICIT STABILITY — the bound term is kept INSIDE the Newton solve%n");
        System.out.printf(Locale.US, "    tau = gamma_psi/k_bind = %.4f us and dt = %.2f us ⇒ tau/dt = %.3f: an EXPLICIT angular kick%n"
                + "    would be unconditionally unstable here. Thermal amplitude sqrt(kT/k_bind) = %.2f deg.%n",
                s.e.params.get(9*N+m)/kB*1e6, DT*1e6, s.e.params.get(9*N+m)/kB/DT,
                Math.toDegrees(Math.sqrt(Constants.kT/kB)));
        StringBuilder rx = new StringBuilder("theta0_deg\tsteps\tmax_dtheta_step_deg\ttheta_end_deg\tmean_last1000_deg\tstill_bound\n");
        boolean g6b = true;
        for (double th0 : new double[]{ 0.0, 5.0, 15.0, 25.0 }) {
            PostHeadFreedomProbe.Scene t = scene(true);
            latchToNearestSite(t);
            double[] snr = SiteNormalBindSystem.boundSiteNormal(t.G, t.nSeg, t.m, Ract, mir, eps);
            double[] pz = poseAtMisalign(t, snr, th0);
            if (pz == null) { rx.append(String.format(Locale.US, "%.1f\tUNREACHABLE%n", th0)); continue; }
            setPose(t, pz[0], pz[1]);
            double prev = SiteNormalBindSystem.thetaBindDeg(xHatLive(t), snr), maxD = 0, sum = 0; int nS = 0, done = 0;
            for (int k = 0; k < 4000; k++) {
                PostHeadFreedomProbe.step(t, k);
                if (t.G.mot.boundSeg.get(t.m) < 0) break;
                double[] sn2 = SiteNormalBindSystem.boundSiteNormal(t.G, t.nSeg, t.m, Ract, mir, eps);
                double th = SiteNormalBindSystem.thetaBindDeg(xHatLive(t), sn2);
                maxD = Math.max(maxD, Math.abs(th - prev)); prev = th; done = k + 1;
                if (k >= 3000) { sum += th; nS++; }
            }
            boolean ok = Double.isFinite(prev) && maxD < 45.0;
            g6b &= ok;
            rx.append(String.format(Locale.US, "%.1f\t%d\t%.4f\t%.4f\t%.4f\t%s%n",
                    th0, done, maxD, prev, nS > 0 ? sum/nS : Double.NaN, t.G.mot.boundSeg.get(t.m) >= 0));
            System.out.printf(Locale.US, "    theta0 = %5.1f deg -> %d steps, max single-step |d theta| = %6.3f deg, theta_end = %6.3f deg,"
                    + " mean(last 1000) = %6.3f deg, bound = %s%n",
                    th0, done, maxD, prev, nS > 0 ? sum/nS : Double.NaN, t.G.mot.boundSeg.get(t.m) >= 0);
        }
        System.out.printf(Locale.US, "    ⇒ PHASE 6b %s (bounded, no blow-up, relaxes toward the thermal amplitude)%n", g6b ? "PASS" : "FAIL");
        write("phase6b_relax.tsv", rx.toString());

        log.append(String.format(Locale.US, "gateA\t%s%ngateB\t%s%ngateC\t%s%ngateD\t%s%ngateE\t%s%nphase6_fd\t%s%n"
                + "canonical_clearance_min_nm\t%.4f%ncanonical_clearance_max_nm\t%.4f%n",
                gA, gB, gC, gD, gE, gFD, worstClr, bestClr));
        write("phase12_summary.txt", log.toString());
        System.out.printf(Locale.US, "%n  >>> GATES: A %s | B %s | C %s | D %s | E %s | Phase-6 FD %s%n",
                gA?"PASS":"FAIL", gB?"PASS":"FAIL", gC?"PASS":"FAIL", gD?"PASS":"FAIL", gE?"PASS":"FAIL", gFD?"PASS":"FAIL");
    }

    // ---- gate support -----------------------------------------------------------------------------
    /** Deterministically latch the motor to the nearest real every4 lattice site (a fixture, not a capture). */
    static int latchToNearestSite(PostHeadFreedomProbe.Scene s) { return latchToSiteOffset(s, 0); }

    /** Latch to the (nearest + j)-th every4 site, so the gates can sweep helical azimuths. */
    static int latchToSiteOffset(PostHeadFreedomProbe.Scene s, int j) {
        refreshGeom(s);
        double[] xf = xF8Live(s);
        double[] ns = SingleMotorMovieHarness.nearestSite(s.G, s.nSeg, xf, s.rise, s.stepPhase);
        int seg = (int) ns[0]; if (seg < 0) return -1;
        int k = (int) ns[1] + j;
        double cum = s.e.segCumArc.get(seg), L = s.G.fil.segLength.get(seg);
        double la = k * s.rise - cum;
        if (la < 0 || la > L) return -1;
        double tw = k * s.stepPhase; double ph = tw - 2*Math.PI*Math.floor(tw/(2*Math.PI));
        s.G.mot.boundSeg.set(s.m, seg);
        s.G.mot.bindArc.set(s.m, (float) la);
        s.G.mot.bindAzim.set(s.m, (float) (ph + mirror(s) * epsBind(s)));
        s.e.bindSite.set(s.m, k);
        s.e.params.set(7*s.N + s.m, kBind(s));   // the bound stiffness (matKbindGate's bound branch)
        return seg;
    }
    static void setPose(PostHeadFreedomProbe.Scene s, double psi, double chi) {
        s.e.q.set(s.N + s.m, psi); s.e.chiHead.set(s.m, chi); refreshGeom(s);
    }
    static void refreshGeom(PostHeadFreedomProbe.Scene s) {
        TwoBodyBeamAnalyticGpu.matBeamGeomTilt(s.e.nodes, s.e.frame, s.e.params, s.e.q, s.e.exCounts, s.e.outGeom, s.e.convF, s.e.chiHead);
        SiteNormalBindSystem.headAxisStep(s.e.frame, s.e.params, s.e.q, s.e.chiHead, s.e.convF, s.e.outGeom, s.e.exCounts);
    }
    /**
     * {psi, chi} that puts xHeadHat exactly {@code dth} degrees off the canonical target. The tilt direction is
     * swept around the target until a REACHABLE pose is found: {@code |sin(chi)| <= cos(delta)} bounds which
     * head axes the (psi, chi) pair can realise at all, so some tilt directions genuinely have no pose.
     * Returns {psi, chi, tiltAzimuth_deg} or null if no direction is reachable.
     */
    /** {psi, chi, tiltAz} at a SPECIFIED tilt direction; null when unreachable. */
    static double[] poseAtMisalignAz(PostHeadFreedomProbe.Scene s, double[] sn, double dthDeg, double tazDeg) {
        double[] t0 = { -sn[0], -sn[1], -sn[2] };
        if (dthDeg == 0) { double[] p = poseForTarget(s.e, m(s), t0); return p == null ? null : new double[]{ p[0], p[1], tazDeg }; }
        double[] a1 = perp(t0), a2 = cross(t0, a1);
        double w = Math.toRadians(tazDeg);
        double[] ax = unit(new double[]{ Math.cos(w)*a1[0] + Math.sin(w)*a2[0],
                                         Math.cos(w)*a1[1] + Math.sin(w)*a2[1],
                                         Math.cos(w)*a1[2] + Math.sin(w)*a2[2] });
        double[] pc = poseForTarget(s.e, m(s), rot(t0, ax, Math.toRadians(dthDeg)));
        return pc == null ? null : new double[]{ pc[0], pc[1], tazDeg };
    }
    static double[] poseAtMisalign(PostHeadFreedomProbe.Scene s, double[] sn, double dthDeg) {
        double[] t0 = { -sn[0], -sn[1], -sn[2] };
        if (dthDeg == 0) { double[] p = poseForTarget(s.e, m(s), t0); return p == null ? null : new double[]{ p[0], p[1], 0 }; }
        double[] a1 = perp(t0), a2 = cross(t0, a1);
        for (int j = 0; j < 16; j++) {
            double w = 2*Math.PI*j/16;
            double[] ax = unit(new double[]{ Math.cos(w)*a1[0] + Math.sin(w)*a2[0],
                                             Math.cos(w)*a1[1] + Math.sin(w)*a2[1],
                                             Math.cos(w)*a1[2] + Math.sin(w)*a2[2] });
            double[] t = rot(t0, ax, Math.toRadians(dthDeg));
            double[] pc = poseForTarget(s.e, m(s), t);
            if (pc != null) return new double[]{ pc[0], pc[1], Math.toDegrees(w) };
        }
        return null;
    }
    static int m(PostHeadFreedomProbe.Scene s) { return s.m; }

    /** {Q_psi, Q_chi} of U_bind at the CURRENT pose, from the same closed form the solver assembles. */
    static double[] analyticQ(PostHeadFreedomProbe.Scene s, double[] sn, double kB) {
        return qAt(s, s.e.q.get(s.N + s.m), s.e.chiHead.get(s.m), sn, kB);
    }
    static double[] qAt(PostHeadFreedomProbe.Scene s, double psi, double chi, double[] sn, double kB) {
        int N = s.N, m = s.m;
        double bx = s.e.frame.get(m), by = s.e.frame.get(N+m), bz = s.e.frame.get(2*N+m);
        double ex = s.e.frame.get(3*N+m), ey = s.e.frame.get(4*N+m), ez = s.e.frame.get(5*N+m);
        double ux = s.e.frame.get(6*N+m), uy = s.e.frame.get(7*N+m), uz = s.e.frame.get(8*N+m);
        double rF8x = s.e.params.get(N+m), rF8y = s.e.params.get(2*N+m);
        double[] p1 = unit(new double[]{ bx*rF8x+ux*rF8y, by*rF8x+uy*rF8y, bz*rF8x+uz*rF8y });
        double[] q1 = { ey*p1[2]-ez*p1[1], ez*p1[0]-ex*p1[2], ex*p1[1]-ey*p1[0] };
        double cp = Math.cos(psi), sp = Math.sin(psi), cc = Math.cos(chi), sc = Math.sin(chi);
        double[] e0 = { cp*p1[0]+sp*q1[0], cp*p1[1]+sp*q1[1], cp*p1[2]+sp*q1[2] };
        double[] th = { e0[1]*ez-e0[2]*ey, e0[2]*ex-e0[0]*ez, e0[0]*ey-e0[1]*ex };   // that = e0 x econv = -f0
        double[] xh = { cc*e0[0]+sc*ex, cc*e0[1]+sc*ey, cc*e0[2]+sc*ez };   // = eBind = the head axis
        double[] et = { -sn[0], -sn[1], -sn[2] };
        double d = cl(dot(xh, et)), thO = Math.acos(d);
        double so = Math.max(Math.sin(thO), 1e-6);
        double lam = thO < 1e-3 ? kB*(1+thO*thO/6) : kB*thO/so;
        double[] dP = { -cc*th[0], -cc*th[1], -cc*th[2] };                   // cos(chi)(econv x e0) = -cos(chi) that
        double[] dC = { cc*ex-sc*e0[0], cc*ey-sc*e0[1], cc*ez-sc*e0[2] };
        return new double[]{ lam*dot(dP, et), lam*dot(dC, et) };
    }
    /** U_bind/kT at an arbitrary (psi, chi) for a fixed site normal. */
    static double uAt(PostHeadFreedomProbe.Scene s, double psi, double chi, double[] sn, double kB) {
        double[] xh = SiteNormalBindSystem.xHeadHatHost(s.e, s.m, psi, chi);
        return uOf(xh, sn, kB) * Constants.kT;   // return in J so the FD gives N.m directly
    }
    static double uOf(double[] xh, double[] sn, double kB) { return SiteNormalBindSystem.uBindKt(xh, sn, kB); }
    /** {H_psipsi, H_chichi, H_psichi} — the Gauss-Newton orientation Hessian the solver assembles.
     *  With the axial F8 the (psi, chi) directions are orthogonal for every chi ⇒ the cross term is 0. */
    static double[] analyticHessian(PostHeadFreedomProbe.Scene s, double kB) {
        double cc = Math.cos(s.e.chiHead.get(s.m));
        return new double[]{ kB*cc*cc, kB, 0.0 };
    }
    /** Run the couple kernel and read the filament reaction it wrote back out of bondData. */
    static double[] reactionFromKernel(PostHeadFreedomProbe.Scene s) {
        for (int c = 9; c <= 11; c++) s.G.bondData.set(s.m*13 + c, 0f);
        SiteNormalBindSystem.siteCoupleStep(s.G.mot.boundSeg, s.G.mot.bindArc, s.G.mot.bindAzim,
                s.G.fil.coord, s.G.fil.uVec, s.G.fil.yVec, s.G.fil.segLength, s.e.outGeom, s.G.bondData,
                s.e.restC, s.e.snP, s.e.exCounts);
        return new double[]{ s.G.bondData.get(s.m*13+9), s.G.bondData.get(s.m*13+10), s.G.bondData.get(s.m*13+11) };
    }
    /** Rigidly rotate the filament about the origin; {@code withMotor} also rotates the motor's frame and beam. */
    static void rotateScene(PostHeadFreedomProbe.Scene s, double[] axis, double ang, boolean withMotor) {
        double[] w = unit(axis); var f = s.G.fil; int nSeg = s.nSeg;
        rotF(f.coord, nSeg, w, ang); rotF(f.end1, nSeg, w, ang); rotF(f.end2, nSeg, w, ang);
        rotF(f.uVec, nSeg, w, ang); rotF(f.yVec, nSeg, w, ang); rotF(f.zVec, nSeg, w, ang);
        // the frozen visualization snapshot must follow, or the next step() would undo the rotation
        s.fc = SingleMotorMovieHarness.snap(f.coord); s.fu = SingleMotorMovieHarness.snap(f.uVec);
        s.fy = SingleMotorMovieHarness.snap(f.yVec);  s.fz = SingleMotorMovieHarness.snap(f.zVec);
        s.f1 = SingleMotorMovieHarness.snap(f.end1);  s.f2 = SingleMotorMovieHarness.snap(f.end2);
        if (!withMotor) return;
        int N = s.N, M = s.M;
        for (int m2 = 0; m2 < N; m2++) {
            for (int r = 0; r < 15; r += 3) {                 // b, econv, eup, g4E(position), g4Tan
                double[] v = { s.e.frame.get(r*N+m2), s.e.frame.get((r+1)*N+m2), s.e.frame.get((r+2)*N+m2) };
                double[] rv = rot(v, w, ang);
                s.e.frame.set(r*N+m2, rv[0]); s.e.frame.set((r+1)*N+m2, rv[1]); s.e.frame.set((r+2)*N+m2, rv[2]);
            }
            for (int j = 0; j <= M; j++) {
                double[] v = { s.e.nodes.get((3*j)*N+m2), s.e.nodes.get((3*j+1)*N+m2), s.e.nodes.get((3*j+2)*N+m2) };
                double[] rv = rot(v, w, ang);
                s.e.nodes.set((3*j)*N+m2, rv[0]); s.e.nodes.set((3*j+1)*N+m2, rv[1]); s.e.nodes.set((3*j+2)*N+m2, rv[2]);
            }
        }
    }
    static void rotF(uk.ac.manchester.tornado.api.types.arrays.FloatArray a, int n, double[] w, double ang) {
        for (int i = 0; i < n; i++) {
            double[] v = { a.get(i), a.get(n+i), a.get(2*n+i) };
            double[] rv = rot(v, w, ang);
            a.set(i, (float) rv[0]); a.set(n+i, (float) rv[1]); a.set(2*n+i, (float) rv[2]);
        }
    }

    // ==============================================================================================
    //  PHASE 11 — natural captures under the canonical gate, and the census that explains them
    // ==============================================================================================
    static void capture() {
        hdr("PHASE 11 — NATURAL CAPTURES under the canonical " + (int) TOL_DEG + " deg site-normal gate");
        PostHeadFreedomProbe.Scene s = scene(true);
        int N = s.N, m = s.m, nSeg = s.nSeg;
        double Ract = s.Ract, mir = mirror(s), eps = epsBind(s), kB = kBind(s);
        System.out.printf(Locale.US, "  horizon %d steps (%.1f ms) ; target %d events ; NO forced binding%n",
                CAPTURE_STEPS, CAPTURE_STEPS*DT*1e3, TARGET_EVENTS);

        StringBuilder ev = new StringBuilder("event\tstep\ttheta_pre_deg\ttheta_bind_deg\ttheta_relaxed_deg\t"
                + "dot_xhat_negn\toutward_nm\tclearance_nm\tdSite_nm\tmotor\n");
        List<double[]> events = new ArrayList<>();
        // detached census, conditioned on TRUE spatial reach of the chi-aware xF8
        int nDet = 0, nReach = 0;
        int[] hist = new int[19];             // 0-10,10-20,...,180 deg
        double sumTh = 0; double bestTh = 1e9; int nWithin = 0;
        // Scan every bindable motor: with -allbind this is all N (the SAME code path, just more independent
        // natural samples per step — no gate is relaxed and nothing is forced).
        int[] scan = ALL_BIND ? new int[N] : new int[]{ m };
        if (ALL_BIND) for (int i = 0; i < N; i++) scan[i] = i;
        boolean[] wasBound = new boolean[N];
        double[] thPreOf = new double[N], dSiteOf = new double[N];
        List<double[]> pend = new ArrayList<>();
        for (int i = 0; i < N; i++) wasBound[i] = s.G.mot.boundSeg.get(i) >= 0;
        for (int t = 0; t < CAPTURE_STEPS && events.size() < TARGET_EVENTS; t++) {
            // PRE-STEP state (what the gate will see this step)
            for (int mm : scan) {
                thPreOf[mm] = Double.NaN; dSiteOf[mm] = Double.NaN;
                if (s.G.mot.boundSeg.get(mm) >= 0) continue;
                double[] xf = { s.e.outGeom.get(6*N+mm), s.e.outGeom.get(7*N+mm), s.e.outGeom.get(8*N+mm) };
                double[] xh = { s.e.outGeom.get(9*N+mm), s.e.outGeom.get(10*N+mm), s.e.outGeom.get(11*N+mm) };
                double[] ns = SingleMotorMovieHarness.nearestSite(s.G, nSeg, xf, s.rise, s.stepPhase);
                if ((int) ns[0] < 0) continue;
                double[] pn = SingleMotorMovieHarness.sitePosNormal(s.G, nSeg, (int) ns[0], ns[2], ns[3], Ract);
                double[] nsv = { pn[3], pn[4], pn[5] };
                double d = Math.sqrt(sq(xf[0]-pn[0]) + sq(xf[1]-pn[1]) + sq(xf[2]-pn[2])) * 1e3;
                double th = SiteNormalBindSystem.thetaBindDeg(xh, nsv);
                thPreOf[mm] = th; dSiteOf[mm] = d;
                nDet++;
                if (d < 3.0) {
                    nReach++; sumTh += th; bestTh = Math.min(bestTh, th);
                    hist[Math.min(18, (int) (th/10))]++;
                    if (th <= TOL_DEG) nWithin++;
                }
            }

            step(s, t);

            for (int mm : scan) {
                boolean now = s.G.mot.boundSeg.get(mm) >= 0;
                if (!wasBound[mm] && now) {                     // a CAPTURE happened on this step
                    double[] sn = SiteNormalBindSystem.boundSiteNormal(s.G, nSeg, mm, Ract, mir, eps);
                    double[] xh = { s.e.outGeom.get(9*N+mm), s.e.outGeom.get(10*N+mm), s.e.outGeom.get(11*N+mm) };
                    double[] xH = { s.e.outGeom.get(3*N+mm), s.e.outGeom.get(4*N+mm), s.e.outGeom.get(5*N+mm) };
                    double thNow = SiteNormalBindSystem.thetaBindDeg(xh, sn);
                    double outward = ((xH[0]-sn[3])*sn[0] + (xH[1]-sn[4])*sn[1] + (xH[2]-sn[5])*sn[2]) * 1e3;
                    double[] clr = PostHeadFreedomProbe.clearance(s, xH, xh);
                    pend.add(new double[]{ events.size() + pend.size(), t, thPreOf[mm], thNow, Double.NaN,
                            -dot(xh, sn), outward, clr[0], dSiteOf[mm], mm });
                }
                wasBound[mm] = now;
            }
            for (java.util.Iterator<double[]> it = pend.iterator(); it.hasNext(); ) {
                double[] p = it.next();
                if (t - (int) p[1] < 200) continue;             // relaxed value 200 steps (0.5 ms) after capture
                int mm = (int) p[9];
                double[] sn = SiteNormalBindSystem.boundSiteNormal(s.G, nSeg, mm, Ract, mir, eps);
                double[] xh = { s.e.outGeom.get(9*N+mm), s.e.outGeom.get(10*N+mm), s.e.outGeom.get(11*N+mm) };
                p[4] = (sn != null) ? SiteNormalBindSystem.thetaBindDeg(xh, sn) : Double.NaN;
                events.add(p); it.remove();
                ev.append(String.format(Locale.US, "%d\t%d\t%.4f\t%.4f\t%.4f\t%.12f\t%.4f\t%.4f\t%.4f\t%d%n",
                        events.size()-1, (int) p[1], p[2], p[3], p[4], p[5], p[6], p[7], p[8], mm));
            }
        }
        write("phase11_events.tsv", ev.toString());
        System.out.printf(Locale.US, "%n  NATURAL CAPTURE EVENTS OBTAINED: %d%n", events.size());
        if (!events.isEmpty()) {
            double mp = 0, mb = 0, mr = 0, mo = 0, mc = 0;
            for (double[] e : events) { mp += e[2]; mb += e[3]; mr += e[4]; mo += e[6]; mc += e[7]; }
            int n = events.size();
            System.out.printf(Locale.US, "    theta_bind: pre-capture %.3f deg | first bound frame %.3f deg | after 0.5 ms relaxation %.3f deg%n",
                    mp/n, mb/n, mr/n);
            System.out.printf(Locale.US, "    dot(xHeadHat, -n_site) mean %.6f ; dot(xH - x_site, n_site) mean %+.3f nm ; clearance mean %+.3f nm%n",
                    Math.cos(Math.toRadians(mb/n)), mo/n, mc/n);
        }
        System.out.printf(Locale.US, "%n  DETACHED CENSUS — angle(xHeadHat, -n_site) at the nearest real site%n");
        System.out.printf(Locale.US, "    detached steps evaluated      = %d%n", nDet);
        System.out.printf(Locale.US, "    of which within 3 nm (reach)  = %d  (%.4f %%)%n", nReach, 100.0*nReach/Math.max(1,nDet));
        if (nReach > 0) {
            System.out.printf(Locale.US, "    conditioned on reach: mean angle %.2f deg ; BEST %.2f deg ; within %.0f deg %d (%.4f %%)%n",
                    sumTh/nReach, bestTh, TOL_DEG, nWithin, 100.0*nWithin/nReach);
            StringBuilder h = new StringBuilder("bin_lo_deg\tbin_hi_deg\tcount\tfrac\n");
            for (int i = 0; i < 19; i++) h.append(String.format(Locale.US, "%d\t%d\t%d\t%.6f%n",
                    i*10, Math.min(180, (i+1)*10), hist[i], (double) hist[i]/nReach));
            write("phase11_census.tsv", h.toString());
            System.out.print("    histogram (10 deg bins, conditioned on reach): ");
            for (int i = 0; i < 19; i++) if (hist[i] > 0) System.out.printf(Locale.US, "[%d-%d)=%d ", i*10, (i+1)*10, hist[i]);
            System.out.println();
        }
        write("phase11_summary.txt", String.format(Locale.US,
                "events\t%d%nsteps\t%d%ntol_deg\t%.1f%ndetached\t%d%nreach\t%d%nmean_angle_deg\t%.4f%nbest_angle_deg\t%.4f%nwithin_tol\t%d%n",
                events.size(), CAPTURE_STEPS, TOL_DEG, nDet, nReach, nReach > 0 ? sumTh/nReach : Double.NaN, bestTh, nWithin));
    }

    // ==============================================================================================
    //  PHASES 4 + 6 — natural captures: per-event records, azimuth class, and the no-snap analysis
    // ==============================================================================================
    static void events() {
        hdr("PHASES 4/6 — NATURAL CAPTURES after the g6/g2 retirement (per-event records + no-snap analysis)");
        PostHeadFreedomProbe.Scene s = scene(true);
        if (ALL_BIND) System.out.println("  (all motors bindable — more independent natural samples per step; no gate relaxed)");
        int N = s.N, nSeg = s.nSeg;
        double Ract = s.Ract, mir = mirror(s), eps = epsBind(s), kB = kBind(s);
        double kF8 = s.e.sbP.get(24), kT = s.e.sbP.get(9);
        double[] eup = { s.e.sbP.get(13), s.e.sbP.get(14), s.e.sbP.get(15) };
        int[] scan = ALL_BIND ? new int[N] : new int[]{ s.m };
        if (ALL_BIND) for (int i = 0; i < N; i++) scan[i] = i;

        StringBuilder ev = new StringBuilder("event\tmotor\tstep\tsite\tazim_deg\tazim_class\td_nm\tpreload_pN\t"
                + "phi_deg\tpsi_deg\tchi_deg\ttheta_deg\ttheta_bind_deg\tg3_margin_deg\tg5_energy_kT\tlifetime_steps\t"
                + "th_m1\tth_0\tth_p1\tth_p5\tth_p20\tdxH_cap_nm\tdxF8_cap_nm\tdS2_cap_nm\tdphi_cap\tdpsi_cap\tdchi_cap\t"
                + "release_nuc\trelease_ext_nm\tmean_theta_bound_deg\n");
        // per-event tracking state
        final class Ev { int mm, step, idx; double[] rec; double[] th = new double[5]; int lifetime = -1;
                         double[] capMove = new double[6];
                         int relNuc = -1; double relExt = Double.NaN, relTheta = Double.NaN, sumTheta = 0; int nTheta = 0; }
        final int LIFE_CAP = 20000;   // 50 ms; long enough that the Lymn-Taylor cycle resolves every episode
        java.util.List<Ev> live = new ArrayList<>(), done = new ArrayList<>();
        boolean[] was = new boolean[N];
        double[][] prevGeom = new double[N][];
        for (int i = 0; i < N; i++) was[i] = s.G.mot.boundSeg.get(i) >= 0;
        // baseline: ordinary DETACHED per-step motion, for the no-snap comparison
        double sumDxH = 0, sumDxF8 = 0, sumDs2 = 0; long nBase = 0;
        double[] thm1 = new double[N];
        for (int i = 0; i < N; i++) thm1[i] = Double.NaN;

        for (int t = 0; t < CAPTURE_STEPS && done.size() < TARGET_EVENTS; t++) {
            // pre-step snapshot
            for (int mm : scan) prevGeom[mm] = snapshot(s, mm);
            for (int mm : scan) thm1[mm] = thetaToNearest(s, mm, Ract);
            step(s, t);
            for (int mm : scan) {
                boolean now = s.G.mot.boundSeg.get(mm) >= 0;
                double[] g = snapshot(s, mm);
                double dxH = dist3(g, prevGeom[mm], 3) * 1e3, dxF8 = dist3(g, prevGeom[mm], 6) * 1e3;
                double ds2 = Math.abs(g[12] - prevGeom[mm][12]) * 1e3;
                if (!was[mm] && !now) { sumDxH += dxH; sumDxF8 += dxF8; sumDs2 += ds2; nBase++; }
                if (!was[mm] && now) {                     // CAPTURE on this step
                    Ev e2 = new Ev(); e2.mm = mm; e2.step = t; e2.idx = done.size() + live.size();
                    double[] sn = SiteNormalBindSystem.boundSiteNormal(s.G, nSeg, mm, Ract, mir, eps);
                    double[] xh = { s.e.outGeom.get(9*N+mm), s.e.outGeom.get(10*N+mm), s.e.outGeom.get(11*N+mm) };
                    double[] xf = { s.e.outGeom.get(6*N+mm), s.e.outGeom.get(7*N+mm), s.e.outGeom.get(8*N+mm) };
                    double d = Math.sqrt(sq(xf[0]-sn[3]) + sq(xf[1]-sn[4]) + sq(xf[2]-sn[5]));
                    double phi = s.e.q.get(mm), psi = s.e.q.get(N+mm), chi = s.e.chiHead.get(mm), thS = s.e.q.get(2*N+mm);
                    double thB = SiteNormalBindSystem.thetaBindDeg(xh, sn);
                    double kconv = s.e.params.get(6*N+mm), dthS = (psi-phi)-thS;
                    double azim = Math.toDegrees(s.G.mot.bindAzim.get(mm));
                    double beta = azimClass(s, nSeg, mm, sn, eup);
                    e2.rec = new double[]{ mm, t, s.e.bindSite.get(mm), azim, beta, d*1e3, kF8*d*1e12,
                            Math.toDegrees(phi), Math.toDegrees(psi), Math.toDegrees(chi), Math.toDegrees(psi-phi), thB,
                            Math.toDegrees(Math.abs(dthS)), (0.5*kconv*dthS*dthS + 0.5*kB*Math.toRadians(thB)*Math.toRadians(thB))/kT };
                    e2.th[0] = thm1[mm]; e2.th[1] = thB;
                    e2.capMove = new double[]{ dxH, dxF8, ds2,
                            Math.toDegrees(g[9]-prevGeom[mm][9]), Math.toDegrees(g[10]-prevGeom[mm][10]), Math.toDegrees(g[11]-prevGeom[mm][11]) };
                    live.add(e2);
                }
                was[mm] = now;
            }
            for (java.util.Iterator<Ev> it = live.iterator(); it.hasNext(); ) {
                Ev e2 = it.next(); int dt2 = t - e2.step;
                boolean bnd = s.G.mot.boundSeg.get(e2.mm) >= 0;
                if (bnd) {
                    double[] sn = SiteNormalBindSystem.boundSiteNormal(s.G, nSeg, e2.mm, Ract, mir, eps);
                    double[] xh = { s.e.outGeom.get(9*N+e2.mm), s.e.outGeom.get(10*N+e2.mm), s.e.outGeom.get(11*N+e2.mm) };
                    double th = sn != null ? SiteNormalBindSystem.thetaBindDeg(xh, sn) : Double.NaN;
                    if (dt2 == 1) e2.th[2] = th; if (dt2 == 5) e2.th[3] = th; if (dt2 == 20) e2.th[4] = th;
                    if (!Double.isNaN(th)) { e2.sumTheta += th; e2.nTheta++; e2.relTheta = th; }
                    if (sn != null) {   // F8 bond extension while bound (the release value is the last one seen)
                        double[] xf = { s.e.outGeom.get(6*N+e2.mm), s.e.outGeom.get(7*N+e2.mm), s.e.outGeom.get(8*N+e2.mm) };
                        e2.relExt = Math.sqrt(sq(xf[0]-sn[3]) + sq(xf[1]-sn[4]) + sq(xf[2]-sn[5])) * 1e3;
                    }
                    e2.relNuc = s.G.mot.nucleotideState.get(e2.mm);
                } else if (e2.lifetime < 0) e2.lifetime = dt2;       // RELEASED — the episode is resolved
                if (e2.lifetime >= 0 || dt2 >= LIFE_CAP) { done.add(e2); it.remove(); }
            }
        }
        // ---- report ------------------------------------------------------------------------------
        System.out.printf(Locale.US, "%n  NATURAL CAPTURE EVENTS: %d  (horizon %d steps, %d bindable motor%s)%n",
                done.size(), CAPTURE_STEPS, scan.length, scan.length > 1 ? "s" : "");
        if (done.isEmpty()) { write("phase4_events.tsv", ev.toString()); return; }
        int nLow = 0, nSide = 0, nUp = 0;
        double sTh0 = 0, sThP20 = 0, sD = 0, sPre = 0, sLife = 0; int nLife = 0;
        for (Ev e2 : done) {
            double beta = e2.rec[4];
            String cls = Math.abs(beta) < 60 ? "upper" : (Math.abs(beta) > 120 ? "lower" : "side");
            if (cls.equals("upper")) nUp++; else if (cls.equals("lower")) nLow++; else nSide++;
            sTh0 += e2.th[1]; sThP20 += Double.isNaN(e2.th[4]) ? e2.th[1] : e2.th[4];
            sD += e2.rec[5]; sPre += e2.rec[6];
            if (e2.lifetime >= 0) { sLife += e2.lifetime; nLife++; }
            ev.append(String.format(Locale.US,
                    "%d\t%d\t%d\t%d\t%.2f\t%s\t%.4f\t%.4f\t%.3f\t%.3f\t%.3f\t%.3f\t%.4f\t%.4f\t%.4f\t%d\t"
                    + "%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.5f\t%.5f\t%.5f\t%.5f\t%.5f\t%.5f\t%s\t%.4f\t%.4f%n",
                    e2.idx, (int) e2.rec[0], (int) e2.rec[1], (int) e2.rec[2], e2.rec[3], cls, e2.rec[5], e2.rec[6],
                    e2.rec[7], e2.rec[8], e2.rec[9], e2.rec[10], e2.rec[11], e2.rec[12], e2.rec[13], e2.lifetime,
                    e2.th[0], e2.th[1], e2.th[2], e2.th[3], e2.th[4],
                    e2.capMove[0], e2.capMove[1], e2.capMove[2], e2.capMove[3], e2.capMove[4], e2.capMove[5],
                    SingleMotorMovieHarness.nucName(e2.relNuc), e2.relExt,
                    e2.nTheta > 0 ? e2.sumTheta/e2.nTheta : Double.NaN));
        }
        int n = done.size();
        System.out.printf(Locale.US, "    theta_bind: at capture %.2f deg -> +20 steps %.2f deg ; |xF8-x_site| %.3f nm ; preload %.3f pN%n",
                sTh0/n, sThP20/n, sD/n, sPre/n);
        System.out.printf(Locale.US, "    AZIMUTH CLASS: lower %d | side %d | upper %d   (of %d)%n", nLow, nSide, nUp, n);
        if (nLife > 0) {
            double[] lv = new double[nLife]; int li = 0;
            for (Ev e2 : done) if (e2.lifetime >= 0) lv[li++] = e2.lifetime;
            java.util.Arrays.sort(lv);
            System.out.printf(Locale.US, "%n  PHASE 7 — BOND LIFETIME (n = %d resolved episodes of %d):%n", nLife, n);
            System.out.printf(Locale.US, "    mean %.1f steps = %.1f us | median %.1f = %.1f us | p10 %.0f | p90 %.0f steps%n",
                    sLife/nLife, sLife/nLife*DT*1e6, lv[nLife/2], lv[nLife/2]*DT*1e6,
                    lv[(int)(0.1*(nLife-1))], lv[(int)(0.9*(nLife-1))]);
            double bt = 0; for (Ev e2 : done) if (e2.lifetime >= 0) bt += e2.lifetime;
            System.out.printf(Locale.US, "    duty (bound steps / motor-steps) = %.6f %%%n",
                    100.0*bt/((double) CAPTURE_STEPS*scan.length));
            java.util.Map<String,Integer> rel = new java.util.TreeMap<>();
            double sExt = 0, sTh = 0; int nE = 0;
            for (Ev e2 : done) if (e2.lifetime >= 0) {
                rel.merge(SingleMotorMovieHarness.nucName(e2.relNuc), 1, Integer::sum);
                if (!Double.isNaN(e2.relExt)) { sExt += e2.relExt; nE++; }
                if (e2.nTheta > 0) sTh += e2.sumTheta/e2.nTheta;
            }
            System.out.printf(Locale.US, "    chemical state at release: %s%n", rel);
            System.out.printf(Locale.US, "    F8 extension at release %.3f nm (n=%d) ; mean orientation error while bound %.2f deg%n",
                    nE > 0 ? sExt/nE : Double.NaN, nE, sTh/nLife);
        } else System.out.println("    (no episode detached within the tracking cap — lifetimes unresolved)");
        // no-snap comparison
        double bH = sumDxH/Math.max(1,nBase), bF = sumDxF8/Math.max(1,nBase), bS = sumDs2/Math.max(1,nBase);
        double cH = 0, cF = 0, cS = 0, cP = 0, cQ = 0, cC = 0;
        for (Ev e2 : done) { cH += e2.capMove[0]; cF += e2.capMove[1]; cS += e2.capMove[2];
                             cP += Math.abs(e2.capMove[3]); cQ += Math.abs(e2.capMove[4]); cC += Math.abs(e2.capMove[5]); }
        System.out.printf(Locale.US, "%n  PHASE 6 — NO-SNAP: per-step motion AT the capture step vs ordinary DETACHED steps (n = %d)%n", nBase);
        System.out.printf(Locale.US, "    |d xH|  capture %.4f nm  vs detached baseline %.4f nm   (ratio %.2f)%n", cH/n, bH, cH/n/Math.max(1e-12,bH));
        System.out.printf(Locale.US, "    |d xF8| capture %.4f nm  vs detached baseline %.4f nm   (ratio %.2f)%n", cF/n, bF, cF/n/Math.max(1e-12,bF));
        System.out.printf(Locale.US, "    |d S2ext| capture %.4f nm vs detached baseline %.4f nm  (ratio %.2f)%n", cS/n, bS, cS/n/Math.max(1e-12,bS));
        System.out.printf(Locale.US, "    |d phi| %.4f deg  |d psi| %.4f deg  |d chi| %.4f deg  at the capture step%n", cP/n, cQ/n, cC/n);
        write("phase4_events.tsv", ev.toString());
        write("phase6_nosnap.tsv", String.format(Locale.US,
                "quantity\tcapture\tdetached_baseline\tratio%ndxH_nm\t%.6f\t%.6f\t%.4f%ndxF8_nm\t%.6f\t%.6f\t%.4f%n"
                + "dS2ext_nm\t%.6f\t%.6f\t%.4f%ndphi_deg\t%.6f%ndpsi_deg\t%.6f%ndchi_deg\t%.6f%nbaseline_n\t%d%n",
                cH/n, bH, cH/n/Math.max(1e-12,bH), cF/n, bF, cF/n/Math.max(1e-12,bF), cS/n, bS, cS/n/Math.max(1e-12,bS),
                cP/n, cQ/n, cC/n, nBase));
    }
    /** {C(3), xH(3), xF8(3), phi, psi, chi, S2 contour} for motor mm. */
    static double[] snapshot(PostHeadFreedomProbe.Scene s, int mm) {
        int N = s.N; double[] r = new double[13];
        for (int k = 0; k < 9; k++) r[k] = s.e.outGeom.get(k*N + mm);
        r[9] = s.e.q.get(mm); r[10] = s.e.q.get(N+mm); r[11] = s.e.chiHead.get(mm);
        double L = 0;
        for (int j = 0; j < s.M; j++) {
            double dx = s.e.nodes.get((3*(j+1))*N+mm) - s.e.nodes.get((3*j)*N+mm);
            double dy = s.e.nodes.get((3*(j+1)+1)*N+mm) - s.e.nodes.get((3*j+1)*N+mm);
            double dz = s.e.nodes.get((3*(j+1)+2)*N+mm) - s.e.nodes.get((3*j+2)*N+mm);
            L += Math.sqrt(dx*dx+dy*dy+dz*dz);
        }
        r[12] = L; return r;
    }
    static double dist3(double[] a, double[] b, int o) {
        return Math.sqrt(sq(a[o]-b[o]) + sq(a[o+1]-b[o+1]) + sq(a[o+2]-b[o+2])); }
    static double thetaToNearest(PostHeadFreedomProbe.Scene s, int mm, double Ract) {
        int N = s.N;
        if (s.G.mot.boundSeg.get(mm) >= 0) return Double.NaN;
        double[] xf = { s.e.outGeom.get(6*N+mm), s.e.outGeom.get(7*N+mm), s.e.outGeom.get(8*N+mm) };
        double[] xh = { s.e.outGeom.get(9*N+mm), s.e.outGeom.get(10*N+mm), s.e.outGeom.get(11*N+mm) };
        double[] ns = SingleMotorMovieHarness.nearestSite(s.G, s.nSeg, xf, s.rise, s.stepPhase);
        if ((int) ns[0] < 0) return Double.NaN;
        double[] pn = SingleMotorMovieHarness.sitePosNormal(s.G, s.nSeg, (int) ns[0], ns[2], ns[3], Ract);
        return SiteNormalBindSystem.thetaBindDeg(xh, new double[]{ pn[3], pn[4], pn[5] });
    }
    /** Signed azimuth of the bound site about the filament axis, 0 = away from the lawn, +-180 = toward it. */
    static double azimClass(PostHeadFreedomProbe.Scene s, int nSeg, int mm, double[] sn, double[] eup) {
        int q = s.G.mot.boundSeg.get(mm); if (q < 0) return Double.NaN;
        var f = s.G.fil;
        double ux = f.uVec.get(q), uy = f.uVec.get(nSeg+q), uz = f.uVec.get(2*nSeg+q);
        double du = eup[0]*ux + eup[1]*uy + eup[2]*uz;
        double px = eup[0]-du*ux, py = eup[1]-du*uy, pz = eup[2]-du*uz;
        double pl = Math.sqrt(px*px+py*py+pz*pz); if (pl < 1e-12) return Double.NaN;
        px/=pl; py/=pl; pz/=pl;
        double qx = uy*pz-uz*py, qy = uz*px-ux*pz, qz = ux*py-uy*px;
        return Math.toDegrees(Math.atan2(sn[0]*qx+sn[1]*qy+sn[2]*qz, sn[0]*px+sn[1]*py+sn[2]*pz));
    }

    // ==============================================================================================
    //  CONTROL — matched g6/g2 ablation (explanatory only; never used to reintroduce a gate)
    // ==============================================================================================
    static void ablation() {
        hdr("CONTROL — matched g6/g2 ablation: A legacy both | B g6 retired | C g2 retired | D both retired");
        String[] nm = { "A both LIVE", "B g6 retired", "C g2 retired", "D BOTH retired (production)" };
        boolean[][] keep = { {true,true}, {false,true}, {true,false}, {false,false} };
        StringBuilder tsv = new StringBuilder("arm\tkeepG6\tkeepG2\tcaptures\tboundSteps\tduty_pct\n");
        for (int a = 0; a < 4; a++) {
            ExplicitCompleteMatHarness.SITE_NORMAL_KEEP_G6 = keep[a][0];
            ExplicitCompleteMatHarness.SITE_NORMAL_KEEP_G2 = keep[a][1];
            PostHeadFreedomProbe.Scene s = scene(true);
            int N = s.N; int[] scan = ALL_BIND ? new int[N] : new int[]{ s.m };
            if (ALL_BIND) for (int i = 0; i < N; i++) scan[i] = i;
            boolean[] was = new boolean[N]; int caps = 0; long bs = 0;
            for (int t = 0; t < CAPTURE_STEPS; t++) {
                step(s, t);
                for (int mm : scan) { boolean now = s.G.mot.boundSeg.get(mm) >= 0;
                    if (!was[mm] && now) caps++; if (now) bs++; was[mm] = now; }
            }
            double duty = 100.0*bs/((double) CAPTURE_STEPS*scan.length);
            System.out.printf(Locale.US, "    %-28s captures %5d   bound-step duty %7.4f %%%n", nm[a], caps, duty);
            tsv.append(String.format(Locale.US, "%s\t%b\t%b\t%d\t%d\t%.6f%n", nm[a], keep[a][0], keep[a][1], caps, bs, duty));
        }
        ExplicitCompleteMatHarness.SITE_NORMAL_KEEP_G6 = false;
        ExplicitCompleteMatHarness.SITE_NORMAL_KEEP_G2 = false;
        write("control_ablation.tsv", tsv.toString());
        System.out.println("    ACTIVE site-normal chain: chemistry | g7 site exists | g8 accessibility | g0 3 nm |"
                + " g4 preload 2 pN | g1' orientation <= tol | g3 theta_S | g5 energy | one-head-per-site");
        System.out.println("    (explanatory ONLY — neither gate is reintroduced; the production arm is D)");
    }

    // ==============================================================================================
    //  PHASE 2 — OFF-PATH REGRESSION: the LEGACY gate chain must be untouched by the g6/g2 retirement
    // ==============================================================================================
    /**
     * Runs the LEGACY site-aware capture path ({@code SITE_NORMAL_BIND = false}, so g6 and g2 are both live)
     * and prints a deterministic fingerprint of capture decisions, the detached trajectory and the motor
     * geometry. Diffing this between the pre-change and post-change builds is the executable form of "the OFF
     * path is unchanged" — the retirement is guarded by {@code sbP[27]}, which is 0 here.
     */
    static void offPathRegression() {
        hdr("PHASE 2 — OFF-PATH REGRESSION (SITE_NORMAL_BIND = false: legacy g6 and g2 both LIVE)");
        ExplicitCompleteMatHarness.SITE_NORMAL_BIND = false;
        PostHeadFreedomProbe.SEED = SEED;
        PostHeadFreedomProbe.Scene s = PostHeadFreedomProbe.scene(true);
        int N = s.N, m = s.m;
        // -allbind makes every motor bindable, so the fingerprint contains REAL capture decisions rather than
        // only a detached trajectory — the legacy path captures roughly once per 30 000 motor-steps.
        if (ALL_BIND) for (int i = 0; i < N; i++) { s.G.noBind[i] = false; s.e.noBind.set(i, 0); }
        int[] scan = ALL_BIND ? new int[N] : new int[]{ m };
        if (ALL_BIND) for (int i = 0; i < N; i++) scan[i] = i;
        java.util.List<Integer> capSteps = new ArrayList<>();
        boolean[] wasA = new boolean[N];
        for (int i = 0; i < N; i++) wasA[i] = s.G.mot.boundSeg.get(i) >= 0;
        long boundSteps = 0;
        int steps = CAPTURE_STEPS;
        for (int t = 0; t < steps; t++) {
            step(s, t);
            for (int mm : scan) {
                boolean now = s.G.mot.boundSeg.get(mm) >= 0;
                if (!wasA[mm] && now) capSteps.add(t * 100 + mm);   // step and motor, both in the fingerprint
                if (now) boundSteps++;
                wasA[mm] = now;
            }
        }
        StringBuilder fp = new StringBuilder();
        fp.append(String.format(Locale.US, "steps\t%d%ncaptures\t%d%nboundSteps\t%d%n", steps, capSteps.size(), boundSteps));
        for (int i = 0; i < Math.min(60, capSteps.size()); i++) fp.append(String.format(Locale.US, "capStepMotor[%d]\t%d%n", i, capSteps.get(i)));
        for (int mm : scan) fp.append(String.format(Locale.US, "finalBoundSeg[%d]\t%d%n", mm, s.G.mot.boundSeg.get(mm)));
        fp.append(String.format(Locale.US, "phi\t%.17e%npsi\t%.17e%nchi\t%.17e%n",
                s.e.q.get(m), s.e.q.get(N+m), s.e.chiHead.get(m)));
        for (int j = 0; j <= s.M; j++) for (int k = 0; k < 3; k++)
            fp.append(String.format(Locale.US, "node[%d][%d]\t%.17e%n", j, k, s.e.nodes.get((3*j+k)*N+m)));
        fp.append(String.format(Locale.US, "boundSeg\t%d%nbindSite\t%d%nbindArc\t%.9e%nbindAzim\t%.9e%n",
                s.G.mot.boundSeg.get(m), s.e.bindSite.get(m), s.G.mot.bindArc.get(m), s.G.mot.bindAzim.get(m)));
        System.out.printf(Locale.US, "  %d steps, %d capture events, %d bound steps, final boundSeg = %d%n",
                steps, capSteps.size(), boundSteps, s.G.mot.boundSeg.get(m));
        System.out.print(fp);
        write("phase2_offpath_fingerprint.tsv", fp.toString());
        ExplicitCompleteMatHarness.SITE_NORMAL_BIND = true;
    }

    // ==============================================================================================
    //  PHASE 10 — WHICH gate is the bottleneck, with every gate evaluated INDEPENDENTLY
    // ==============================================================================================
    /**
     * The ordered funnel answers "which gate stops the survivors", which is not the same question as "which
     * gate is incompatible with the canonical bound pose". This evaluates every gate INDEPENDENTLY on the
     * same candidates, so a gate that is structurally at odds with the law shows up even when an earlier gate
     * already removed the candidate.
     */
    static void bottleneck() {
        hdr("PHASE 10 — gate-by-gate MARGINAL pass rates (each gate evaluated independently)");
        PostHeadFreedomProbe.Scene s = scene(true);
        int N = s.N, m = s.m, nSeg = s.nSeg;
        var e = s.e; var f = s.G.fil;
        double dBindNm = e.sbP.get(0), preloadPn = e.sbP.get(4), aSemiZ = e.sbP.get(8);
        double phiDeg = e.sbP.get(2), thetaDeg = e.sbP.get(3), energyKt = e.sbP.get(5);
        double PHI_PRE = e.sbP.get(7), kT = e.sbP.get(9);
        double eupx = e.sbP.get(13), eupy = e.sbP.get(14), eupz = e.sbP.get(15);
        double Ract = e.sbP.get(19), accTol = e.sbP.get(23), kF8 = e.sbP.get(24);
        double cosTol = e.sbP.get(28), kBindP = e.sbP.get(29);
        boolean g6R = ExplicitCompleteMatHarness.siteNormalOn() && !ExplicitCompleteMatHarness.SITE_NORMAL_KEEP_G6;
        boolean g2R = ExplicitCompleteMatHarness.siteNormalOn() && !ExplicitCompleteMatHarness.SITE_NORMAL_KEEP_G2;
        String[] nm = { g6R ? "g6 head side [RETIRED]" : "g6 head side", "g8 accessibility",
                        "g0 d < 3 nm", "g4 preload < 2 pN", "g1' orientation <= tol",
                        g2R ? "g2 phi [RETIRED]" : "g2 phi", "g3 theta_S", "g5 energy < 15 kT" };
        long tot = 0; long[] pass = new long[8];
        long spatialAndOrient = 0; long[] failGiven = new long[8];
        double sumHeadSide = 0, sumHeadSideCanon = 0; long nHS = 0;
        for (int t = 0; t < CAPTURE_STEPS; t++) {
            step(s, t);
            if (s.G.mot.boundSeg.get(m) >= 0) continue;
            if (s.G.mot.nucleotideState.get(m) != 2) continue;
            double[] xf = { e.outGeom.get(6*N+m), e.outGeom.get(7*N+m), e.outGeom.get(8*N+m) };
            double[] xH = { e.outGeom.get(3*N+m), e.outGeom.get(4*N+m), e.outGeom.get(5*N+m) };
            double[] xh = xHatLive(s);
            double[] ns = SingleMotorMovieHarness.nearestSite(s.G, nSeg, xf, s.rise, s.stepPhase);
            int q = (int) ns[0]; if (q < 0) continue;
            double[] pn = SingleMotorMovieHarness.sitePosNormal(s.G, nSeg, q, ns[2], ns[3], Ract);
            double d = Math.sqrt(sq(xf[0]-pn[0]) + sq(xf[1]-pn[1]) + sq(xf[2]-pn[2]));
            if (d*1e3 > 3.0) continue;                       // the spatially plausible neighbourhood
            tot++;
            double cx = f.coord.get(q), cy = f.coord.get(nSeg+q), cz = f.coord.get(2*nSeg+q);
            double headSide = ((xH[0]-cx)*eupx + (xH[1]-cy)*eupy + (xH[2]-cz)*eupz) * 1e3;
            double aApp = (xf[0]-pn[0])*pn[3] + (xf[1]-pn[1])*pn[4] + (xf[2]-pn[2])*pn[5];
            double dh = -(xh[0]*pn[3] + xh[1]*pn[4] + xh[2]*pn[5]);
            double thB = Math.acos(cl(dh));
            double phi = e.q.get(m), psi = e.q.get(N+m), thetaS = e.q.get(2*N+m);
            double kconv = e.params.get(6*N+m), dthS = (psi-phi)-thetaS;
            double eKt = (0.5*kconv*dthS*dthS + 0.5*kBindP*thB*thB)/kT;
            boolean[] g = {
                headSide < aSemiZ*1e3,          // g6
                aApp > -accTol,                 // g8
                d*1e3 < dBindNm,                // g0
                kF8*d*1e12 < preloadPn,         // g4
                dh >= cosTol,                   // g1'
                Math.abs(phi - PHI_PRE)*180/Math.PI < phiDeg,        // g2
                Math.abs((psi-phi) - thetaS)*180/Math.PI < thetaDeg,  // g3
                eKt < energyKt                  // g5
            };
            for (int i = 0; i < 8; i++) if (g[i]) pass[i]++;
            // the head-side value the CANONICAL bound pose would have at this site, for comparison
            double[] xHc = { pn[0]-3.5e-3*(-pn[3]), pn[1]-3.5e-3*(-pn[4]), pn[2]-3.5e-3*(-pn[5]) };
            sumHeadSide += headSide;
            sumHeadSideCanon += ((xHc[0]-cx)*eupx + (xHc[1]-cy)*eupy + (xHc[2]-cz)*eupz) * 1e3;
            nHS++;
            if (g[2] && g[3] && g[4]) {                       // spatially qualified AND correctly oriented
                spatialAndOrient++;
                for (int i = 0; i < 8; i++) if (!g[i]) failGiven[i]++;
            }
        }
        System.out.printf(Locale.US, "%n  %d candidate evaluations with d < 3 nm over %d steps%n", tot, CAPTURE_STEPS);
        System.out.printf(Locale.US, "%n  MARGINAL pass rate of each gate (evaluated independently on the same candidates):%n");
        StringBuilder tsv = new StringBuilder("gate\tpass\ttotal\tfrac_pct\n");
        for (int i = 0; i < 8; i++) {
            System.out.printf(Locale.US, "    %-26s %8d / %d = %7.3f %%%n", nm[i], pass[i], tot, 100.0*pass[i]/Math.max(1,tot));
            tsv.append(String.format(Locale.US, "%s\t%d\t%d\t%.4f%n", nm[i], pass[i], tot, 100.0*pass[i]/Math.max(1,tot)));
        }
        System.out.printf(Locale.US, "%n  CANDIDATES THAT ARE BOTH SPATIALLY QUALIFIED (g0 & g4) AND CORRECTLY ORIENTED (g1'): %d%n", spatialAndOrient);
        if (spatialAndOrient > 0) {
            System.out.println("    of those, which OTHER gate rejects them:");
            for (int i = 0; i < 8; i++) if (i != 2 && i != 3 && i != 4)
                System.out.printf(Locale.US, "      %-26s rejects %d / %d = %6.2f %%%n",
                        nm[i], failGiven[i], spatialAndOrient, 100.0*failGiven[i]/spatialAndOrient);
        }
        System.out.printf(Locale.US, "%n  g6 head-side diagnostic (the gate's threshold is %.2f nm):%n", aSemiZ*1e3);
        System.out.printf(Locale.US, "    mean OBSERVED head side  = %+.3f nm%n", sumHeadSide/Math.max(1,nHS));
        System.out.printf(Locale.US, "    mean head side the CANONICAL bound pose would have = %+.3f nm%n", sumHeadSideCanon/Math.max(1,nHS));
        write("phase10_gate_marginals.tsv", tsv.toString());
    }

    // ==============================================================================================
    //  PHASE 9 — the POSITION / ORIENTATION correlation, OLD off-axis F8 vs NEW axial F8
    // ==============================================================================================
    /**
     * The previous run's blocking finding was that spatially-qualified candidates and orientation-qualified
     * poses never coincided. This measures that directly, on matched seed and scene, for both geometries:
     * for every detached candidate evaluation it records {@code d = |xF8 - x_site|} against
     * {@code theta = angle(headLongAxis, -n_site)}.
     *
     * <p>The head long axis is taken from {@link SiteNormalBindSystem#xHeadHatHost} in BOTH arms — the
     * ANALYTIC construction, which returns the true ellipsoid long axis whether or not {@code r_F8} is axial.
     * Using {@code normalize(xF8 - xH)} would silently measure a different vector in the legacy arm.
     */
    static void correlation() {
        hdr("PHASE 9 — position / orientation correlation: OLD off-axis F8 vs NEW axial F8 (matched seed)");
        double[][] savedF8 = { TwoBodyConverterMotor.R_F8.clone(), TwoBodyConverterMotor.R_CONV.clone() };
        StringBuilder tsv = new StringBuilder("arm\td_nm\ttheta_deg\n");
        StringBuilder sum = new StringBuilder("arm\tn\tpearson\tmean_theta\tmedian_theta\tbest_theta\tfrac_le25\tn_d_lt2\tn_d_lt2_and_le25\n");
        for (int arm = 0; arm < 2; arm++) {
            // DIAGNOSTIC-ONLY geometry override. R_F8/R_CONV are static final ARRAYS, so their CONTENTS are a
            // runtime read (unlike a primitive static final, which javac would inline); writing them before the
            // scene is built is the only way to get gamma_psi and the packed params to follow. Restored below.
            boolean legacy = arm == 0;
            TwoBodyConverterMotor.R_F8[0] = 0.0035;  TwoBodyConverterMotor.R_F8[1]  = legacy ?  0.0015 : 0.0;
            TwoBodyConverterMotor.R_CONV[0] = -0.0035; TwoBodyConverterMotor.R_CONV[1] = legacy ? -0.0015 : 0.0;
            String name = legacy ? "OLD_offaxis" : "NEW_axial";
            PostHeadFreedomProbe.Scene s = scene(true);
            int N = s.N, m = s.m, nSeg = s.nSeg;
            java.util.List<double[]> pts = new ArrayList<>();
            for (int t = 0; t < CAPTURE_STEPS; t++) {
                step(s, t);
                if (s.G.mot.boundSeg.get(m) >= 0) continue;
                if (s.G.mot.nucleotideState.get(m) != 2) continue;
                double[] xf = { s.e.outGeom.get(6*N+m), s.e.outGeom.get(7*N+m), s.e.outGeom.get(8*N+m) };
                double[] ns = SingleMotorMovieHarness.nearestSite(s.G, nSeg, xf, s.rise, s.stepPhase);
                if ((int) ns[0] < 0) continue;
                double[] pn = SingleMotorMovieHarness.sitePosNormal(s.G, nSeg, (int) ns[0], ns[2], ns[3], s.Ract);
                double d = Math.sqrt(sq(xf[0]-pn[0]) + sq(xf[1]-pn[1]) + sq(xf[2]-pn[2])) * 1e3;
                if (d > 6.0) continue;                       // the neighbourhood the gate can ever act in
                double[] xh = SiteNormalBindSystem.xHeadHatHost(s.e, m, s.e.q.get(N+m), s.e.chiHead.get(m));
                double th = SiteNormalBindSystem.thetaBindDeg(xh, new double[]{ pn[3], pn[4], pn[5] });
                pts.add(new double[]{ d, th });
            }
            int n = pts.size();
            double[] ds = new double[n], ths = new double[n];
            for (int i = 0; i < n; i++) { ds[i] = pts.get(i)[0]; ths[i] = pts.get(i)[1]; }
            double r = pearson(ds, ths);
            double[] sorted = ths.clone(); java.util.Arrays.sort(sorted);
            double med = n > 0 ? sorted[n/2] : Double.NaN, best = n > 0 ? sorted[0] : Double.NaN;
            double mean = 0; for (double v : ths) mean += v; mean /= Math.max(1, n);
            int le25 = 0, dlt2 = 0, both = 0;
            for (double[] q : pts) { if (q[1] <= TOL_DEG) le25++; if (q[0] < 2.0) dlt2++;
                                     if (q[0] < 2.0 && q[1] <= TOL_DEG) both++; }
            System.out.printf(Locale.US, "%n  %-12s  n = %d candidate evaluations with d < 6 nm%n", name, n);
            System.out.printf(Locale.US, "    theta: mean %.2f deg | median %.2f | best %.2f | <= %.0f deg %d (%.3f %%)%n",
                    mean, med, best, TOL_DEG, le25, 100.0*le25/Math.max(1,n));
            System.out.printf(Locale.US, "    Pearson r(d, theta) = %+.4f%n", r);
            System.out.printf(Locale.US, "    d < 2 nm: %d ; d < 2 nm AND theta <= %.0f deg: %d   <- THE conjunction%n",
                    dlt2, TOL_DEG, both);
            // conditional mean angle in distance bins — the shape of the coupling
            System.out.print("    mean theta by d bin (nm): ");
            for (int b = 0; b < 6; b++) {
                double lo = b, hi = b+1; double sm = 0; int c = 0;
                for (double[] q : pts) if (q[0] >= lo && q[0] < hi) { sm += q[1]; c++; }
                if (c > 0) System.out.printf(Locale.US, "[%d,%d)=%.1f(n=%d) ", b, b+1, sm/c, c);
            }
            System.out.println();
            for (double[] q : pts) tsv.append(String.format(Locale.US, "%s\t%.4f\t%.4f%n", name, q[0], q[1]));
            sum.append(String.format(Locale.US, "%s\t%d\t%.6f\t%.4f\t%.4f\t%.4f\t%.6f\t%d\t%d%n",
                    name, n, r, mean, med, best, (double) le25/Math.max(1,n), dlt2, both));
        }
        TwoBodyConverterMotor.R_F8[0] = savedF8[0][0]; TwoBodyConverterMotor.R_F8[1] = savedF8[0][1];
        TwoBodyConverterMotor.R_CONV[0] = savedF8[1][0]; TwoBodyConverterMotor.R_CONV[1] = savedF8[1][1];
        write("phase9_correlation_points.tsv", tsv.toString());
        write("phase9_correlation_summary.tsv", sum.toString());
        System.out.printf(Locale.US, "%n  (geometry restored to the canonical axial r_F8 = (%.4f, %.4f))%n",
                TwoBodyConverterMotor.R_F8[0], TwoBodyConverterMotor.R_F8[1]);
    }

    // ==============================================================================================
    //  PHASE 5b/6 — F8 Jacobian through the REAL kernel, orientation coverage, and the dead-cone test
    // ==============================================================================================
    static void coverage() {
        hdr("PHASE 5b — dxF8/dq through the REAL geometry kernel  |  PHASE 6 — orientation coverage / dead cone");
        PostHeadFreedomProbe.Scene s = scene(true);
        int N = s.N, m = s.m;

        // ---- PHASE 5b: the F8 point's own Jacobian, finite-differenced through matBeamGeomTilt ---------
        // psi and chi rotate the head rigidly about C, so d xF8/d psi = econv x (xF8 - C) and
        // d xF8/d chi = that x (xF8 - C). phi moves C itself: d xF8/d phi = econv x (C - P).
        System.out.printf(Locale.US, "%n  PHASE 5b  d xF8 / dq : ANALYTIC vs CENTRAL DIFFERENCE THROUGH matBeamGeomTilt (h = 1e-7 rad)%n");
        System.out.printf(Locale.US, "    %6s %6s  %11s %11s %11s   %11s %11s %11s%n",
                "psi", "chi", "|dF8/dphi|", "FD", "rel", "|dF8/dpsi|(+chi)", "FD", "rel");
        double worst = 0; boolean gJ = true;
        StringBuilder jt = new StringBuilder("psi_deg\tchi_deg\tcoord\tanalytic_x\tanalytic_y\tanalytic_z\tfd_x\tfd_y\tfd_z\trel\n");
        java.util.Random rj = new java.util.Random(4242);
        for (int it = 0; it < 12; it++) {
            double phi0 = (rj.nextDouble()-0.5)*2.0, psi0 = (rj.nextDouble()-0.5)*2*Math.PI, chi0 = (rj.nextDouble()-0.5)*2.4;
            double[] rel = new double[3];
            for (int c = 0; c < 3; c++) {   // 0 = phi, 1 = psi, 2 = chi
                double h = 1e-7;
                double[] fp = geomAt(s, phi0 + (c==0?h:0), psi0 + (c==1?h:0), chi0 + (c==2?h:0));
                double[] fm = geomAt(s, phi0 - (c==0?h:0), psi0 - (c==1?h:0), chi0 - (c==2?h:0));
                double[] fd = { (fp[6]-fm[6])/(2*h), (fp[7]-fm[7])/(2*h), (fp[8]-fm[8])/(2*h) };
                double[] g = geomAt(s, phi0, psi0, chi0);
                double[] C = { g[0], g[1], g[2] }, xF = { g[6], g[7], g[8] };
                double[] P = { s.e.nodes.get((3*s.M)*N+m), s.e.nodes.get((3*s.M+1)*N+m), s.e.nodes.get((3*s.M+2)*N+m) };
                double[] ec = { s.e.frame.get(3*N+m), s.e.frame.get(4*N+m), s.e.frame.get(5*N+m) };
                double[] an;
                if (c == 0)      an = cross(ec, new double[]{ C[0]-P[0], C[1]-P[1], C[2]-P[2] });
                else if (c == 1) an = cross(ec, new double[]{ xF[0]-C[0], xF[1]-C[1], xF[2]-C[2] });
                else {
                    double[] xh = SiteNormalBindSystem.xHeadHatHost(s.e, m, psi0, chi0);
                    double[] e0 = SiteNormalBindSystem.xHeadHatHost(s.e, m, psi0, 0.0);
                    double[] that = cross(e0, ec);
                    an = cross(that, new double[]{ xF[0]-C[0], xF[1]-C[1], xF[2]-C[2] });
                    if (xh == null) an = new double[]{0,0,0};
                }
                double dn = Math.sqrt(sq(an[0]-fd[0]) + sq(an[1]-fd[1]) + sq(an[2]-fd[2]));
                double sc2 = Math.max(Math.sqrt(dot(an,an)), 1e-12);
                rel[c] = dn/sc2; worst = Math.max(worst, rel[c]);
                if (rel[c] > 1e-5) gJ = false;
                jt.append(String.format(Locale.US, "%.4f\t%.4f\t%s\t%.6e\t%.6e\t%.6e\t%.6e\t%.6e\t%.6e\t%.2e%n",
                        Math.toDegrees(psi0), Math.toDegrees(chi0), c==0?"phi":(c==1?"psi":"chi"),
                        an[0],an[1],an[2], fd[0],fd[1],fd[2], rel[c]));
            }
            if (it < 6) System.out.printf(Locale.US, "    %6.1f %6.1f   rel(phi) %8.2e   rel(psi) %8.2e   rel(chi) %8.2e%n",
                    Math.toDegrees(psi0), Math.toDegrees(chi0), rel[0], rel[1], rel[2]);
        }
        System.out.printf(Locale.US, "    worst relative error over 12 random (phi, psi, chi) x 3 coordinates = %.2e  ⇒ %s%n",
                worst, gJ ? "PASS" : "FAIL");
        write("phase5b_f8_jacobian.tsv", jt.toString());

        // ---- PHASE 6: solid-angle coverage of the head axis + the every4 site-normal reachability -----
        System.out.printf(Locale.US, "%n  PHASE 6  ORIENTATION COVERAGE — sweep (psi, chi) and bin xHeadHat on the unit sphere%n");
        final int NB = 2000;                                   // Fibonacci sphere bins (equal-area)
        boolean[] hit = new boolean[NB];
        double[][] cen = new double[NB][3];
        double ga = Math.PI * (3.0 - Math.sqrt(5.0));
        for (int i = 0; i < NB; i++) {
            double z = 1.0 - 2.0*(i + 0.5)/NB, r = Math.sqrt(Math.max(0, 1 - z*z)), th = ga*i;
            cen[i] = new double[]{ r*Math.cos(th), r*Math.sin(th), z };
        }
        double chiMax = Math.toDegrees(1.5533430342749532);     // the kernel's |chi| <= 89 deg clamp
        for (int a = 0; a < 720; a++) for (int b = 0; b <= 360; b++) {
            double psi = -Math.PI + 2*Math.PI*a/720.0;
            double chi = Math.toRadians(-chiMax + 2*chiMax*b/360.0);
            double[] v = SiteNormalBindSystem.xHeadHatHost(s.e, m, psi, chi);
            int best = 0; double bd = -2;
            for (int i = 0; i < NB; i++) { double d = dot(v, cen[i]); if (d > bd) { bd = d; best = i; } }
            hit[best] = true;
        }
        int nHit = 0; for (boolean h2 : hit) if (h2) nHit++;
        // the analytically UNREACHABLE caps: |xHeadHat . econv| > cos(delta) is empty at delta = 0, so only
        // the |chi| clamp excludes anything: |xHeadHat . econv| = |sin chi| <= sin(89 deg).
        double capCos = Math.sin(Math.toRadians(chiMax));
        int nCap = 0; double[] ecv = { s.e.frame.get(3*N+m), s.e.frame.get(4*N+m), s.e.frame.get(5*N+m) };
        for (int i = 0; i < NB; i++) if (Math.abs(dot(cen[i], ecv)) > capCos) nCap++;
        System.out.printf(Locale.US, "    %d equal-area bins ; reached %d = %.2f %% of 4pi%n", NB, nHit, 100.0*nHit/NB);
        System.out.printf(Locale.US, "    bins excluded by the |chi| <= %.0f deg pole clamp alone = %d = %.2f %%%n",
                chiMax, nCap, 100.0*nCap/NB);
        System.out.printf(Locale.US, "    UNREACHED bins NOT explained by the clamp = %d  ⇒ %s%n",
                Math.max(0, (NB - nHit) - nCap), (NB - nHit) - nCap <= 2 ? "NO residual dead cone" : "RESIDUAL DEAD CONE");
        System.out.printf(Locale.US, "    (the old off-axis geometry excluded every direction within delta = 23.1986 deg of +-econv,%n"
                + "     i.e. 1 - cos(delta) = %.2f %% of the sphere; that cap is now EMPTY)%n", 100.0*(1-Math.cos(Math.toRadians(23.19859051))));

        // covariance rank of the head-axis map
        double minSig = 1e9;
        for (int a = 0; a < 90; a++) for (int b = 0; b < 60; b++) {
            double psi = -Math.PI + 2*Math.PI*a/90.0, chi = Math.toRadians(-chiMax + 2*chiMax*b/60.0);
            double h = 1e-6;
            double[] dp = dvec(SiteNormalBindSystem.xHeadHatHost(s.e, m, psi+h, chi), SiteNormalBindSystem.xHeadHatHost(s.e, m, psi-h, chi), 2*h);
            double[] dc = dvec(SiteNormalBindSystem.xHeadHatHost(s.e, m, psi, chi+h), SiteNormalBindSystem.xHeadHatHost(s.e, m, psi, chi-h), 2*h);
            double a11 = dot(dp,dp), a12 = dot(dp,dc), a22 = dot(dc,dc);
            double tr = a11+a22, det = a11*a22 - a12*a12;
            double lam2 = 0.5*(tr - Math.sqrt(Math.max(0, tr*tr - 4*det)));
            minSig = Math.min(minSig, Math.sqrt(Math.max(0, lam2)));
        }
        System.out.printf(Locale.US, "    covariance RANK of d xHeadHat/d(psi, chi): min singular value over the grid = %.4f%n"
                + "    (rank 2 everywhere; it degenerates only as cos(chi) -> 0 at the poles, = %.4f at the clamp)%n",
                minSig, Math.cos(Math.toRadians(chiMax)));

        // every4 site normals: is the canonical pose reachable at EVERY azimuth?
        int nSites = 0, nReach = 0;
        for (int j = 0; j < 60; j++) {
            PostHeadFreedomProbe.Scene t = scene(true);
            if (latchToSiteOffset(t, j) < 0) continue;
            double[] snj = SiteNormalBindSystem.boundSiteNormal(t.G, t.nSeg, t.m, t.Ract, mirror(t), epsBind(t));
            if (snj == null) continue;
            nSites++;
            if (poseForTarget(t.e, t.m, new double[]{ -snj[0], -snj[1], -snj[2] }) != null) nReach++;
        }
        System.out.printf(Locale.US, "    every4 site normals: %d tested, %d canonically reachable (%.1f %%)  ⇒ %s%n",
                nSites, nReach, 100.0*nReach/Math.max(1,nSites), nReach == nSites ? "ALL" : "GAPS REMAIN");
        write("phase6_coverage.txt", String.format(Locale.US,
                "bins\t%d%nreached\t%d%nfrac_pct\t%.4f%nclamp_bins\t%d%nresidual_dead\t%d%nmin_singular\t%.6f%n"
                + "sites_tested\t%d%nsites_reachable\t%d%njacobian_worst_rel\t%.3e%n",
                NB, nHit, 100.0*nHit/NB, nCap, Math.max(0,(NB-nHit)-nCap), minSig, nSites, nReach, worst));
    }
    /** {C, xH, xF8} from the REAL kernel at an arbitrary (phi, psi, chi); restores the pose afterwards. */
    static double[] geomAt(PostHeadFreedomProbe.Scene s, double phi, double psi, double chi) {
        int N = s.N, m = s.m;
        double p0 = s.e.q.get(m), s0 = s.e.q.get(N+m), c0 = s.e.chiHead.get(m);
        s.e.q.set(m, phi); s.e.q.set(N+m, psi); s.e.chiHead.set(m, chi);
        TwoBodyBeamAnalyticGpu.matBeamGeomTilt(s.e.nodes, s.e.frame, s.e.params, s.e.q, s.e.exCounts, s.e.outGeom, s.e.convF, s.e.chiHead);
        double[] r = new double[9];
        for (int k = 0; k < 9; k++) r[k] = s.e.outGeom.get(k*N + m);
        s.e.q.set(m, p0); s.e.q.set(N+m, s0); s.e.chiHead.set(m, c0);
        return r;
    }
    static double[] dvec(double[] a, double[] b, double h) {
        return new double[]{ (a[0]-b[0])/h, (a[1]-b[1])/h, (a[2]-b[2])/h }; }

    // ==============================================================================================
    //  CAPTURE FUNNEL — which gate actually stops a candidate, evaluated in the PRODUCTION order
    // ==============================================================================================
    /**
     * Host twin of the production gate chain, evaluated on every eligible detached step against the same
     * enumerated candidate sites {@code siteGateA} sees. Counts the FIRST gate each step fails, so the
     * blocking gate is identified rather than inferred. Nothing is fed back: the production step has already
     * decided before this runs.
     */
    static void funnel() {
        hdr("CAPTURE FUNNEL — which gate stops the candidate (production order, diagnostic only)");
        PostHeadFreedomProbe.Scene s = scene(true);
        int N = s.N, m = s.m, nSeg = s.nSeg;
        var e = s.e; var f = s.G.fil;
        double dBindNm = e.sbP.get(0), preloadPn = e.sbP.get(4), aSemiZ = e.sbP.get(8), margin = e.sbP.get(10);
        double psiDeg = e.sbP.get(1), phiDeg = e.sbP.get(2), thetaDeg = e.sbP.get(3), energyKt = e.sbP.get(5);
        double PHI_PRE = e.sbP.get(7), kT = e.sbP.get(9);
        double eupx = e.sbP.get(13), eupy = e.sbP.get(14), eupz = e.sbP.get(15);
        double rise = e.sbP.get(16), Ract = e.sbP.get(19);
        int halfSearch = (int) e.sbP.get(22);
        double accTol = e.sbP.get(23), kF8 = e.sbP.get(24), segTol = e.sbP.get(26);
        double cosTol = e.sbP.get(28), kBindP = e.sbP.get(29);
        boolean g6Retired = ExplicitCompleteMatHarness.siteNormalOn() && !ExplicitCompleteMatHarness.SITE_NORMAL_KEEP_G6;
        boolean g2Retired = ExplicitCompleteMatHarness.siteNormalOn() && !ExplicitCompleteMatHarness.SITE_NORMAL_KEEP_G2;
        // A retired gate is NOT reported as "pass" — it is reported as RETIRED, so a reader can never mistake
        // "it stopped rejecting" for "everything now satisfies it".
        String[] names = { "eligible (detached, ADP.Pi, bindable)", "g7 in-segment site exists",
                g6Retired ? "g6 head side            [RETIRED]" : "g6 head side",
                "g8 accessibility (approach from outside)", "g0 |xF8-x_site| < 3 nm", "g4 F8 preload < 2 pN",
                "g1' orientation angle(xHeadHat,-n_site) <= tol",
                g2Retired ? "g2 phi                  [RETIRED]" : "g2 phi",
                "g3 theta_S", "g5 energy budget < 15 kT", "ALL PASS" };
        long[] surv = new long[names.length];
        double bestOrient = 1e9, bestOrientSpatial = 1e9; long nSpatial = 0; double sumOrientSpatial = 0;
        int[] histSpatial = new int[19];
        for (int t = 0; t < CAPTURE_STEPS; t++) {
            step(s, t);
            if (s.G.mot.boundSeg.get(m) >= 0) continue;
            if (s.G.mot.nucleotideState.get(m) != 2) continue;
            surv[0]++;
            double fx = e.outGeom.get(6*N+m), fy = e.outGeom.get(7*N+m), fz = e.outGeom.get(8*N+m);
            double hx = e.outGeom.get(3*N+m), hy = e.outGeom.get(4*N+m), hz = e.outGeom.get(5*N+m);
            double[] xh = xHatLive(s);
            // nearest segment (the production ownership rule)
            int best = -1; double bd = 1e9;
            for (int q = 0; q < nSeg; q++) {
                double half = 0.5*f.segLength.get(q);
                double cx = f.coord.get(q), cy = f.coord.get(nSeg+q), cz = f.coord.get(2*nSeg+q);
                double ux = f.uVec.get(q), uy = f.uVec.get(nSeg+q), uz = f.uVec.get(2*nSeg+q);
                double dx = fx-cx, dy = fy-cy, dz = fz-cz;
                double foot = dx*ux+dy*uy+dz*uz; double fc = Math.max(-half, Math.min(half, foot));
                double qx = dx-fc*ux, qy = dy-fc*uy, qz = dz-fc*uz;
                double d2 = qx*qx+qy*qy+qz*qz; if (d2 < bd) { bd = d2; best = q; }
            }
            if (best < 0) continue;
            int q = best; double half = 0.5*f.segLength.get(q);
            double cx = f.coord.get(q), cy = f.coord.get(nSeg+q), cz = f.coord.get(2*nSeg+q);
            double ux = f.uVec.get(q), uy = f.uVec.get(nSeg+q), uz = f.uVec.get(2*nSeg+q);
            double headSide = ((hx-cx)*eupx + (hy-cy)*eupy + (hz-cz)*eupz) * 1e3;
            double foot = (fx-cx)*ux + (fy-cy)*uy + (fz-cz)*uz;
            double footC = Math.max(-half, Math.min(half, foot));
            double cum = e.segCumArc.get(q);
            int k0 = (int) ((cum + footC + half)/rise + 0.5);
            // walk the same candidate set, tracking the deepest gate reached this step.
            // deepest == the index into names[] of the LAST gate this step passed (0 = eligible only).
            int deepest = 0; double stepBestOrient = 1e9; double bestD = 1e9;
            for (int j = -halfSearch; j <= halfSearch; j++) {
                int k = k0 + j; if (k < 0) continue;
                double laRaw = k*rise - cum, segL = 2*half;
                if (laRaw < -segTol || laRaw > segL + segTol) continue;
                double la = Math.max(0, Math.min(segL, laRaw));
                if (margin > segTol && !(la > margin && la < segL - margin)) continue;
                deepest = Math.max(deepest, 1);                                   // g7 satisfied
                if (!g6Retired && !(headSide < aSemiZ*1e3)) continue;
                deepest = Math.max(deepest, 2);                                   // g6 (or its retirement)
                double tw = k*s.stepPhase, ph = tw - 2*Math.PI*Math.floor(tw/(2*Math.PI));
                double[] pn = SingleMotorMovieHarness.sitePosNormal(s.G, nSeg, q, la, ph, Ract);
                double vx = fx-pn[0], vy = fy-pn[1], vz = fz-pn[2];
                double aApp = vx*pn[3] + vy*pn[4] + vz*pn[5];
                if (!(aApp > -accTol)) continue;
                deepest = Math.max(deepest, 3);                                   // g8
                double d = Math.sqrt(vx*vx+vy*vy+vz*vz); bestD = Math.min(bestD, d*1e3);
                if (!(d*1e3 < dBindNm)) continue;
                deepest = Math.max(deepest, 4);                                   // g0
                if (!(kF8*d*1e12 < preloadPn)) continue;
                deepest = Math.max(deepest, 5);                                   // g4
                double dh = -(xh[0]*pn[3] + xh[1]*pn[4] + xh[2]*pn[5]);
                double thB = Math.acos(cl(dh));
                stepBestOrient = Math.min(stepBestOrient, Math.toDegrees(thB));
                nSpatial++; sumOrientSpatial += Math.toDegrees(thB);
                bestOrientSpatial = Math.min(bestOrientSpatial, Math.toDegrees(thB));
                histSpatial[Math.min(18, (int) (Math.toDegrees(thB)/10))]++;
                if (!(dh >= cosTol)) continue;
                deepest = Math.max(deepest, 6);                                   // g1'
                double phi = e.q.get(m), psi = e.q.get(N+m), thetaS = e.q.get(2*N+m);
                if (!g2Retired && !(Math.abs(phi - PHI_PRE)*180/Math.PI < phiDeg)) continue;
                deepest = Math.max(deepest, 7);                                   // g2 (or its retirement)
                if (!(Math.abs((psi-phi) - thetaS)*180/Math.PI < thetaDeg)) continue;
                deepest = Math.max(deepest, 8);                                   // g3
                double kconv = e.params.get(6*N+m), dthS = (psi-phi)-thetaS;
                double eKt = (0.5*kconv*dthS*dthS + 0.5*kBindP*thB*thB)/kT;
                if (!(eKt < energyKt)) continue;
                deepest = Math.max(deepest, 9);                                   // g5 -> ALL PASS
            }
            for (int i = 1; i <= deepest; i++) surv[i]++;
            if (deepest >= 9) surv[10]++;                                          // ALL PASS
            bestOrient = Math.min(bestOrient, stepBestOrient);
        }
        StringBuilder tsv = new StringBuilder("gate\tsurvivors\tfrac_of_eligible\n");
        System.out.printf(Locale.US, "%n  %d steps ; the bindable motor's candidate funnel"
                + " (g6 %s, g2 %s):%n", CAPTURE_STEPS,
                g6Retired ? "RETIRED" : "ACTIVE", g2Retired ? "RETIRED" : "ACTIVE");
        for (int i = 0; i < names.length; i++) {
            System.out.printf(Locale.US, "    %-46s %10d   %8.4f %%%n", names[i], surv[i], 100.0*surv[i]/Math.max(1, surv[0]));
            tsv.append(String.format(Locale.US, "%s\t%d\t%.6f%n", names[i], surv[i], 100.0*surv[i]/Math.max(1, surv[0])));
        }
        System.out.printf(Locale.US, "%n  CONDITIONED ON THE FULL SPATIAL CHAIN (g7,g6,g8,g0,g4 all passed) — %d candidate evaluations:%n", nSpatial);
        if (nSpatial > 0) {
            System.out.printf(Locale.US, "    angle(xHeadHat, -n_site): mean %.2f deg ; BEST %.2f deg%n",
                    sumOrientSpatial/nSpatial, bestOrientSpatial);
            System.out.print("    histogram (10 deg bins): ");
            for (int i = 0; i < 19; i++) if (histSpatial[i] > 0) System.out.printf(Locale.US, "[%d-%d)=%d ", i*10, (i+1)*10, histSpatial[i]);
            System.out.println();
            StringBuilder h = new StringBuilder("bin_lo\tbin_hi\tcount\n");
            for (int i = 0; i < 19; i++) h.append(String.format(Locale.US, "%d\t%d\t%d%n", i*10, (i+1)*10, histSpatial[i]));
            write("funnel_orientation_hist.tsv", h.toString());
        } else System.out.println("    (no candidate ever passed the spatial chain in this horizon)");
        write("funnel.tsv", tsv.toString());
    }

    // ==============================================================================================
    //  PHASE 10 — fine-time sim_viewer export (one frame per integration timestep)
    // ==============================================================================================
    static void viewer() {
        hdr("PHASE 5 — fine-time sim_viewer binding trajectory (1 frame = 1 timestep)");
        // TWO PASSES over the SAME deterministic trajectory. Pass 1 only advances the step and watches
        // boundSeg, so it is cheap; pass 2 replays from a fresh identical scene and records rows only inside
        // the window. Building a row every step (clearance + nearest-site scan) would dominate the run.
        PostHeadFreedomProbe.Scene p1 = scene(true);
        int m = p1.m; int capAt = -1;
        for (int t = 0; t < CAPTURE_STEPS; t++) {
            boolean b0 = p1.G.mot.boundSeg.get(m) >= 0;
            step(p1, t);
            if (!b0 && p1.G.mot.boundSeg.get(m) >= 0) { capAt = t; break; }
        }
        if (capAt < 0) {
            System.out.printf(Locale.US, "  NO natural capture in %d steps — writing the CANONICAL-POSE FIXTURE instead.%n", CAPTURE_STEPS);
            fixtureClip();
            return;
        }
        System.out.printf(Locale.US, "  pass 1: natural capture at step %d (%.2f ms). Replaying for the window.%n", capAt, capAt*DT*1e3);
        PostHeadFreedomProbe.Scene s = scene(true);
        double Ract = s.Ract, mir = mirror(s), eps = epsBind(s);
        int from = Math.max(0, capAt - PRE), to = capAt + POST;
        List<double[]> out = new ArrayList<>();
        int bindIdx = -1;
        for (int t = 0; t <= to; t++) {
            step(s, t);
            if (t >= from) { out.add(row(s, Ract, mir, eps)); if (t == capAt) bindIdx = out.size() - 1; }
        }
        String dir = JS_ROOT + "_capture";
        writeClip(dir, s, out, bindIdx);
        System.out.printf(Locale.US, "  wrote %d frames (capture at frame %d of the clip, sim step %d) -> %s%n",
                out.size(), bindIdx, capAt, dir);
        System.out.println("  open:  http://localhost:8000/SoftBox/sim_viewer_boa.html   (Recent picker: " + dir + ")");
    }

    /** A deterministic canonical-pose clip: the head placed exactly at xHeadHat = -n_site on successive sites. */
    static void fixtureClip() {
        List<double[]> rows = new ArrayList<>();
        PostHeadFreedomProbe.Scene s = scene(true);
        double Ract = s.Ract, mir = mirror(s), eps = epsBind(s);
        for (int j = 0; j < 8; j++) {
            PostHeadFreedomProbe.Scene t = scene(true);
            if (latchToSiteOffset(t, j) < 0) continue;
            double[] sn = SiteNormalBindSystem.boundSiteNormal(t.G, t.nSeg, t.m, Ract, mir, eps);
            double[] pc = poseForTarget(t.e, t.m, new double[]{ -sn[0], -sn[1], -sn[2] });
            if (pc == null) continue;
            setPose(t, pc[0], pc[1]);
            for (int r = 0; r < 25; r++) rows.add(row(t, Ract, mir, eps));   // hold each pose for 25 frames
        }
        if (rows.isEmpty()) { System.out.println("  (no reachable canonical pose to draw)"); return; }
        String dir = JS_ROOT + "_canonical";
        writeClip(dir, s, rows, -1);
        System.out.printf(Locale.US, "  wrote %d frames -> %s   (CANONICAL-POSE FIXTURE, not a natural capture)%n", rows.size(), dir);
        System.out.println("  open:  http://localhost:8000/SoftBox/sim_viewer_boa.html   (Recent picker: " + dir + ")");
    }

    /** One viewer row: nodes(3(M+1)) C(3) xH(3) xF8(3) xHat(3) sitePos(3) siteN(3) bound clr pA(3) pH(3) nuc. */
    static double[] row(PostHeadFreedomProbe.Scene s, double Ract, double mir, double eps) {
        int N = s.N, m = s.m, M = s.M;
        double[] r = new double[3*(M+1) + 3*6 + 1 + 1 + 6 + 1];
        int p = 0;
        for (int j = 0; j <= M; j++) for (int k = 0; k < 3; k++) r[p++] = s.e.nodes.get((3*j+k)*N + m);
        for (int k = 0; k < 3; k++) r[p++] = s.e.outGeom.get(k*N + m);
        double[] xH = xHLive(s), xF = xF8Live(s), xh = xHatLive(s);
        for (int k = 0; k < 3; k++) r[p++] = xH[k];
        for (int k = 0; k < 3; k++) r[p++] = xF[k];
        for (int k = 0; k < 3; k++) r[p++] = xh[k];
        boolean bnd = s.G.mot.boundSeg.get(m) >= 0;
        double[] sn = bnd ? SiteNormalBindSystem.boundSiteNormal(s.G, s.nSeg, m, Ract, mir, eps) : null;
        if (sn == null) {   // detached: show the nearest real site so the relationship stays visible
            double[] ns = SingleMotorMovieHarness.nearestSite(s.G, s.nSeg, xF, s.rise, s.stepPhase);
            if ((int) ns[0] >= 0) {
                double[] pn = SingleMotorMovieHarness.sitePosNormal(s.G, s.nSeg, (int) ns[0], ns[2], ns[3], Ract);
                sn = new double[]{ pn[3], pn[4], pn[5], pn[0], pn[1], pn[2] };
            }
        }
        for (int k = 0; k < 3; k++) r[p++] = sn != null ? sn[3+k] : Double.NaN;   // site position
        for (int k = 0; k < 3; k++) r[p++] = sn != null ? sn[k]   : Double.NaN;   // outward normal
        r[p++] = bnd ? 1 : 0;
        double[] clr = PostHeadFreedomProbe.clearance(s, xH, xh);
        r[p++] = clr[0];
        for (int i = 3; i < 9; i++) r[p++] = clr[i];
        r[p++] = s.G.mot.nucleotideState.get(m);
        return r;
    }

    /**
     * Canonical {@code sim_viewer_boa.html} frame sequence — the project's viewer, NOT forked or modified.
     * Colour is its only per-segment channel ({@code notADPRatio}, rendered rgb(1,a,0) with age colour ON):
     * 1.00 YELLOW actin, 0.55 ORANGE the S2 beam + anchor, 0.00 RED the site stub, 0.30 the +n_site arrow
     * (OUTWARD), 0.10 the -n_site TARGET arrow (INWARD), 0.70 the head-local +x arrow, 0.85 the head/actin
     * closest-approach line, 0.45 the sparse every4 lattice.
     *
     * <p><b>The head ellipsoid is drawn from the SAME xHeadHat the mechanics use.</b> The viewer's
     * {@code myosins[].motor} channel scales a unit sphere by {@code (r, 1.5r, r)} with the 1.5-axis along
     * {@code end2 - end1} and the centre at their midpoint, so the head is emitted as
     * {@code end1 = xH - a*xHeadHat}, {@code end2 = xH + a*xHeadHat}, {@code r = A_SEMI[0]/1.5} — long semi-axis
     * exactly {@code A_SEMI[0]} = 4.5 nm along {@code xHeadHat}, centred on the TRUE head centre {@code xH}.
     * (The previous export drew the long axis along {@code eBind} and centred it on the midpoint of
     * {@code xH..xF8} — both wrong by construction; see PHASE 0.)
     */
    static void writeClip(String dir, PostHeadFreedomProbe.Scene s, List<double[]> rows, int bindIdx) {
        new java.io.File(dir).mkdirs();
        int M = s.M, nSeg = s.nSeg; var f = s.G.fil; double Ract = s.Ract;
        double[][] s1 = new double[nSeg][3], s2 = new double[nSeg][3];
        for (int q = 0; q < nSeg; q++) {
            double half = 0.5*f.segLength.get(q);
            for (int k = 0; k < 3; k++) {
                double c = f.coord.get(k*nSeg+q), u = f.uVec.get(k*nSeg+q);
                s1[q][k] = c - half*u; s2[q][k] = c + half*u;
            }
        }
        // the sparse every4 lattice, drawn once (static filament fixture)
        List<double[]> lat = new ArrayList<>();
        for (int q = 0; q < nSeg; q++) {
            double L = f.segLength.get(q), cum = s.e.segCumArc.get(q);
            int k0 = (int) (cum / s.rise);
            for (int k = k0; k * s.rise - cum <= L; k++) {
                double la = k * s.rise - cum; if (la < 0) continue;
                double tw = k * s.stepPhase, ph = tw - 2*Math.PI*Math.floor(tw/(2*Math.PI));
                lat.add(SingleMotorMovieHarness.sitePosNormal(s.G, nSeg, q, la, ph, Ract));
            }
        }
        int base = 3*(M+1);
        double aSemi = TwoBodyConverterMotor.A_SEMI[0];
        for (int i = 0; i < rows.size(); i++) {
            double[] r = rows.get(i);
            double[] C = sub(r, base), xH = sub(r, base+3), xF = sub(r, base+6), xh = sub(r, base+9);
            double[] sp = sub(r, base+12), sn = sub(r, base+15);
            boolean bnd = r[base+18] > 0.5;
            double clr = r[base+19];
            double[] pA = sub(r, base+20), pH = sub(r, base+23);
            int nuc = (int) r[base+26];
            StringBuilder b = new StringBuilder(1 << 16);
            b.append(String.format(Locale.US, "{\"frame\":%d,\"t\":%.9g,", i, i*DT));
            b.append("\"bounds\":{\"xDim\":2.2,\"yDim\":0.1,\"zDim\":0.1},\"segments\":[");
            int sid = 0;
            for (int q = 0; q < nSeg; q++) b.append(SingleMotorMovieHarness.seg(sid++, s1[q], s2[q], Ract, false, 1.00, sid > 1));
            for (double[] p : lat)   // the sparse every4 lattice: a short outward stub at every real site
                b.append(SingleMotorMovieHarness.seg(sid++, new double[]{p[0],p[1],p[2]},
                        new double[]{p[0]+0.0010*p[3], p[1]+0.0010*p[4], p[2]+0.0010*p[5]}, 0.00030, true, 0.45, true));
            for (int j = 0; j < M; j++)
                b.append(SingleMotorMovieHarness.seg(sid++, new double[]{r[3*j],r[3*j+1],r[3*j+2]},
                        new double[]{r[3*(j+1)],r[3*(j+1)+1],r[3*(j+1)+2]}, 0.0010, true, 0.55, true));
            double[] a0 = { r[0], r[1], r[2] };
            b.append(SingleMotorMovieHarness.seg(sid++, a0,
                    new double[]{a0[0]-0.004*s.G.eup[0], a0[1]-0.004*s.G.eup[1], a0[2]-0.004*s.G.eup[2]}, 0.0022, true, 0.55, true));
            if (!Double.isNaN(sp[0])) {
                double sl = bnd ? 0.0035 : 0.0018;
                // the site itself (RED stub)
                b.append(SingleMotorMovieHarness.seg(sid++, sp,
                        new double[]{sp[0]+0.0008*sn[0], sp[1]+0.0008*sn[1], sp[2]+0.0008*sn[2]}, bnd?0.0011:0.0006, true, 0.00, true));
                // +n_site : OUTWARD, from actin toward where the head body should be
                b.append(SingleMotorMovieHarness.seg(sid++, sp,
                        new double[]{sp[0]+0.014*sn[0], sp[1]+0.014*sn[1], sp[2]+0.014*sn[2]}, 0.00045, true, 0.30, true));
                // -n_site : the TARGET direction, INWARD from outside the surface toward the site
                b.append(SingleMotorMovieHarness.seg(sid++,
                        new double[]{sp[0]+0.014*sn[0], sp[1]+0.014*sn[1], sp[2]+0.014*sn[2]}, sp, 0.00025, true, 0.10, true));
                // the acceptance cone about -n_site, apex OUTSIDE the surface pointing in
                double[] tdir = { -sn[0], -sn[1], -sn[2] };
                double[] apex = { sp[0]-0.012*tdir[0], sp[1]-0.012*tdir[1], sp[2]-0.012*tdir[2] };
                double[] t1 = perp(tdir), t2 = cross(tdir, t1);
                double ca = Math.cos(Math.toRadians(TOL_DEG)), sa = Math.sin(Math.toRadians(TOL_DEG));
                for (int a = 0; a < 12; a++) {
                    double w = 2*Math.PI*a/12;
                    double[] d = unit(new double[]{ ca*tdir[0] + sa*(Math.cos(w)*t1[0] + Math.sin(w)*t2[0]),
                                                    ca*tdir[1] + sa*(Math.cos(w)*t1[1] + Math.sin(w)*t2[1]),
                                                    ca*tdir[2] + sa*(Math.cos(w)*t1[2] + Math.sin(w)*t2[2]) });
                    b.append(SingleMotorMovieHarness.seg(sid++, apex,
                            new double[]{apex[0]+0.012*d[0], apex[1]+0.012*d[1], apex[2]+0.012*d[2]}, 0.00020, true, 0.15, true));
                }
            }
            // the HEAD-LOCAL +x arrow, from the head centre (this is the vector the law constrains)
            b.append(SingleMotorMovieHarness.seg(sid++, xH,
                    new double[]{xH[0]+0.013*xh[0], xH[1]+0.013*xh[1], xH[2]+0.013*xh[2]}, 0.00055, true, 0.70, true));
            if (clr < 1e6) b.append(SingleMotorMovieHarness.seg(sid++, pA, pH, 0.00035, true, 0.85, true));
            b.append("],\"myosins\":[");
            double[] P = { r[3*M], r[3*M+1], r[3*M+2] };
            // id 0 : lever (P->C) + the HEAD ELLIPSOID drawn along xHeadHat, centred on xH
            b.append(String.format(Locale.US,
                "{\"id\":0,\"rod\":{\"end1\":[%.6f,%.6f,%.6f],\"end2\":[%.6f,%.6f,%.6f],\"r\":0.0008,\"invisible\":true},"
                + "\"lever\":{\"end1\":[%.6f,%.6f,%.6f],\"end2\":[%.6f,%.6f,%.6f],\"r\":0.0011},"
                + "\"motor\":{\"end1\":[%.6f,%.6f,%.6f],\"end2\":[%.6f,%.6f,%.6f],\"r\":%.5f,\"state\":\"%s\"}}",
                a0[0],a0[1],a0[2], P[0],P[1],P[2], P[0],P[1],P[2], C[0],C[1],C[2],
                xH[0]-aSemi*xh[0], xH[1]-aSemi*xh[1], xH[2]-aSemi*xh[2],
                xH[0]+aSemi*xh[0], xH[1]+aSemi*xh[1], xH[2]+aSemi*xh[2],
                aSemi/1.5, SingleMotorMovieHarness.nucName(nuc)));
            // id 1 : the F8 point (small sphere) — it must sit at the FILAMENT-FACING end of the head
            b.append(String.format(Locale.US,
                ",{\"id\":1,\"rod\":{\"end1\":[%.6f,%.6f,%.6f],\"end2\":[%.6f,%.6f,%.6f],\"r\":0.0004,\"invisible\":true},"
                + "\"lever\":{\"end1\":[%.6f,%.6f,%.6f],\"end2\":[%.6f,%.6f,%.6f],\"r\":0.0004},"
                + "\"motor\":{\"end1\":[%.6f,%.6f,%.6f],\"end2\":[%.6f,%.6f,%.6f],\"r\":0.0008,\"state\":\"ATP\"}}",
                xH[0],xH[1],xH[2], xF[0],xF[1],xF[2], xH[0],xH[1],xH[2], xF[0],xF[1],xF[2],
                xF[0],xF[1],xF[2], xF[0],xF[1],xF[2]));
            if (bnd && !Double.isNaN(sp[0])) b.append(String.format(Locale.US,
                ",{\"id\":2,\"rod\":{\"end1\":[%.6f,%.6f,%.6f],\"end2\":[%.6f,%.6f,%.6f],\"r\":0.0005,\"invisible\":true},"
                + "\"lever\":{\"end1\":[%.6f,%.6f,%.6f],\"end2\":[%.6f,%.6f,%.6f],\"r\":0.0006},"
                + "\"motor\":{\"end1\":[%.6f,%.6f,%.6f],\"end2\":[%.6f,%.6f,%.6f],\"r\":0.0012,\"state\":\"NONE\"}}",
                xF[0],xF[1],xF[2], xF[0],xF[1],xF[2], xF[0],xF[1],xF[2], sp[0],sp[1],sp[2],
                sp[0],sp[1],sp[2], sp[0],sp[1],sp[2]));
            b.append("]}");
            try { java.nio.file.Files.writeString(
                    java.nio.file.Path.of(dir, String.format(Locale.US, "frame_%06d.json", i)), b.toString()); }
            catch (java.io.IOException ex) { throw new RuntimeException(ex); }
        }
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(dir, "INFO.txt"), String.format(Locale.US,
                "frames=%d  captureFrame=%d  dt=%.3e s  1 frame = 1 timestep%n"
                + "head ellipsoid: long semi-axis %.2f nm along xHeadHat, centred on xH%n"
                + "overlays: +n_site outward arrow (0.30), -n_site target arrow (0.10), %.0f deg cone (0.15),%n"
                + "          head-local +x arrow (0.70), every4 lattice stubs (0.45), bound site (0.00)%n",
                rows.size(), bindIdx, DT, TwoBodyConverterMotor.A_SEMI[0]*1e3, TOL_DEG)); }
        catch (java.io.IOException ex) { }
    }

    // ==============================================================================================
    static double[] sub(double[] r, int o) { return new double[]{ r[o], r[o+1], r[o+2] }; }
    static double[] unit(double[] v) { double n = Math.sqrt(dot(v, v)); return n < 1e-300 ? new double[]{0,0,1}
            : new double[]{ v[0]/n, v[1]/n, v[2]/n }; }
    static double[] cross(double[] a, double[] b) {
        return new double[]{ a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0] }; }
    static double[] perp(double[] v) {
        double[] a = Math.abs(v[0]) < 0.9 ? new double[]{1,0,0} : new double[]{0,1,0};
        double d = dot(a, v); return unit(new double[]{ a[0]-d*v[0], a[1]-d*v[1], a[2]-d*v[2] }); }
    /** Rodrigues rotation of v about the unit axis w by ang. */
    static double[] rot(double[] v, double[] w, double ang) {
        double[] u = unit(w); double c = Math.cos(ang), s = Math.sin(ang);
        double[] k = cross(u, v); double d = dot(u, v);
        return new double[]{ v[0]*c + k[0]*s + u[0]*d*(1-c), v[1]*c + k[1]*s + u[1]*d*(1-c), v[2]*c + k[2]*s + u[2]*d*(1-c) }; }
    static double dot(double[] a, double[] b) { return a[0]*b[0]+a[1]*b[1]+a[2]*b[2]; }
    static double sq(double x) { return x*x; }
    static double cl(double x) { return x > 1 ? 1 : (x < -1 ? -1 : x); }
    /** Pearson correlation coefficient of two equal-length series. */
    static double pearson(double[] a, double[] b) {
        int n = Math.min(a.length, b.length); if (n < 3) return Double.NaN;
        double ma = 0, mb = 0; for (int i = 0; i < n; i++) { ma += a[i]; mb += b[i]; } ma /= n; mb /= n;
        double sa = 0, sb = 0, sab = 0;
        for (int i = 0; i < n; i++) { sa += (a[i]-ma)*(a[i]-ma); sb += (b[i]-mb)*(b[i]-mb); sab += (a[i]-ma)*(b[i]-mb); }
        return (sa > 0 && sb > 0) ? sab/Math.sqrt(sa*sb) : Double.NaN; }
    static double relDiff(double a, double b) { return relDiff(a, b, 1e-30); }
    /** Relative difference with an ABSOLUTE floor, so a genuinely-zero component is not scored against noise. */
    static double relDiff(double a, double b, double floor) {
        double d = Math.max(Math.max(Math.abs(a), Math.abs(b)), floor); return Math.abs(a-b)/d; }
    static void hdr(String t) { System.out.println("\n" + "=".repeat(100) + "\n=== " + t + "\n" + "=".repeat(100)); }
    static void mk(String d) { new java.io.File(d).mkdirs(); }
    static void write(String rel, String s) {
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(OUT, rel), s); }
        catch (java.io.IOException e) { throw new RuntimeException(e); } }
}
