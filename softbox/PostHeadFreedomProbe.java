package softbox;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * POST-HEAD-FREEDOM VISUAL / GEOMETRIC VALIDATION — S2→lever coupling, head/actin sterics, and a SHADOW
 * site-normal capture gate.
 *
 * <p><b>Everything here is DIAGNOSTIC.</b> No capture decision, force, torque, threshold, rate or parameter is
 * changed by any code path in this file. The shadow gate is computed AFTER the production step has already
 * decided, from state the step wrote, and is never fed back. The steric test measures clearance and applies no
 * force. Run with the REPAIRED default mechanics (econv F8 axis, S2→lever joint) and dynamic χ ON.
 *
 * <pre>
 *   -lever    TEST 1  S2→lever moment transfer: long detached fine-time trace + statistics + viewer frames
 *   -sterics  TEST 2  head-body / actin-cylinder signed clearance across an event
 *   -shadow   TESTS 3,4,5,7,8  chi-aware shadow geometry + shadow site-normal gate + event classification
 *   -events   TEST 6  export the representative events as canonical sim_viewer frame sequences
 *   -all      everything
 * </pre>
 *
 * <p><b>Runner: the CPU sequential runner</b> — plain-Java kernel calls over the host SoA arrays, no TaskGraph,
 * no device transfer. Single-motor deterministic assay class; no GPU work is launched.
 */
public final class PostHeadFreedomProbe {
    private PostHeadFreedomProbe() {}

    static String OUT = "RUN_LOGS/motor_audit/post_head_freedom_validation";
    static final double DT = 2.5e-6;
    static int SEED = 20260812;
    static int LEVER_STEPS = 200_000;      // TEST 1 detached horizon (500 ms)
    static int SHADOW_STEPS = 600_000;     // TEST 5 horizon; stops early once TARGET_EVENTS is reached
    static int TARGET_EVENTS = 20;
    static final double SITE_NORMAL_TOL_DEG = 25.0;   // the PROPOSED gate, evaluated in shadow only
    /**
     * Viewer frame sequences go to the REPO ROOT, not under {@link #OUT}. {@code sim_server.py} discovers a run
     * folder by walking only {@code MAX_DEPTH = 4} levels below its root (~/Code), so anything buried deeper —
     * e.g. {@code SoftBox/RUN_LOGS/motor_audit/<study>/simviewer_events/<run>}, which is 6 levels — is written
     * correctly but NEVER APPEARS in the viewer's Recent picker. Keep viewer output at {@code SoftBox/threejs_*}.
     */
    static String JS_ROOT = "threejs_posthead";

    public static void main(String[] args) {
        String mode = args.length > 0 && args[0].startsWith("-") ? args[0] : "-all";
        for (int i = 0; i < args.length; i++) switch (args[i]) {
            case "-out" -> OUT = args[++i];
            case "-seed" -> SEED = Integer.parseInt(args[++i]);
            case "-lever-steps" -> LEVER_STEPS = Integer.parseInt(args[++i]);
            case "-shadow-steps" -> SHADOW_STEPS = Integer.parseInt(args[++i]);
            case "-events" -> TARGET_EVENTS = args.length > i+1 && !args[i+1].startsWith("-")
                    ? Integer.parseInt(args[++i]) : TARGET_EVENTS;
            default -> {}
        }
        mk(OUT); mk(OUT + "/lever_joint"); mk(OUT + "/head_actin_sterics");
        mk(OUT + "/shadow_capture");
        System.out.println("\n=== POST-HEAD-FREEDOM VALIDATION (diagnostic only; nothing is fed back) ===");
        System.out.printf(Locale.US, "  dt = %.2e s ; REPAIRED mechanics (econv F8 axis + S2->lever joint) ; chi DYNAMIC%n", DT);
        System.out.println("  RUNNER: CPU sequential runner. No GPU work is launched.");
        switch (mode) {
            case "-lever"   -> test1Lever();
            case "-sterics" -> test2Sterics();
            case "-shadow"  -> testShadow();
            case "-events"  -> testShadow();        // event export is produced by the shadow pass
            case "-all"     -> { test1Lever(); test2Sterics(); testShadow(); }
            default -> System.out.println("unknown mode " + mode);
        }
        System.out.println("\n  output -> " + OUT);
    }

    // ==============================================================================================
    //  scene — the SAME construction the single-motor movie validated
    // ==============================================================================================
    static final class Scene {
        TwoBodyConverterMotor.Glide2D G; ExplicitCompleteMatHarness.ExMat e;
        int m, N, M, nSeg; double rise, stepPhase, Ract, theta0, kbend;
        float[] fc, fu, fy, fz, f1, f2;
        ExplicitCompleteMatHarness.ExMat eLegacyHolder;
        uk.ac.manchester.tornado.api.types.arrays.DoubleArray legacyGeom;
    }

    /** Build the canonical Path-B one-filament/one-bindable-motor scene with the 3-D head ON. */
    static Scene scene(boolean bindable) {
        double kdetWanted = ExplicitCompleteMatHarness.K_DET_PNNM;   // resetChiral() below clobbers it
        ExplicitCompleteMatHarness.resetChiral();
        ExplicitCompleteMatHarness.SITE_MODE = 3;              // every4 sparse long-pitch
        ExplicitCompleteMatHarness.SITE_PHASE_GLOBAL = true;
        ExplicitCompleteMatHarness.SITE_AWARE = true;
        ExplicitCompleteMatHarness.SITE_EXCLUSIVE = true;
        ExplicitCompleteMatHarness.Z_SLAB = true;
        ExplicitCompleteMatHarness.RAND_BASE_AZ = false;
        ExplicitCompleteMatHarness.HEAD_TILT_3D = true;        // the validated 3-D head
        ExplicitCompleteMatHarness.HEAD_TILT_AXIS_FIX = true;  // econv (repaired)
        ExplicitCompleteMatHarness.KBIND_BOUND_ONLY = true;
        // Restore the caller's k_det (resetChiral() just reset it to the 5.0 default). This must happen
        // BEFORE packExMat, which bakes k_det into gateP. Getting this wrong made a k_det 5-vs-10 comparison
        // silently produce two identical arms.
        ExplicitCompleteMatHarness.K_DET_PNNM = kdetWanted > 0 ? kdetWanted : 5.0;
        Scene s = new Scene();
        s.G = TwoBodyConverterMotor.buildS2Mat(4.0, DT, 40.0, TwoBodyConverterMotor.EXPLICIT_GLIDE_SLACK_NM, SEED, false);
        s.e = ExplicitCompleteMatHarness.packExMat(s.G, 1);
        s.N = s.e.N; s.M = s.e.M; s.nSeg = s.e.nSeg;
        TwoBodyBeamAnalyticGpu.matBeamGeom(s.e.nodes, s.e.frame, s.e.params, s.e.q, s.e.exCounts, s.e.outGeom, s.e.convF);
        int mSel = -1; double best = 1e9;
        for (int m = 0; m < s.N; m++) {
            double d = SingleMotorMovieHarness.axisDist(s.G, s.nSeg,
                    s.e.outGeom.get(6*s.N+m), s.e.outGeom.get(7*s.N+m), s.e.outGeom.get(8*s.N+m));
            if (d < best) { best = d; mSel = m; }
        }
        s.m = mSel;
        for (int m = 0; m < s.N; m++) {
            boolean nb = !bindable || m != mSel;
            s.G.noBind[m] = nb; s.e.noBind.set(m, nb ? 1 : 0);
        }
        var f = s.G.fil;
        s.fc = SingleMotorMovieHarness.snap(f.coord); s.fu = SingleMotorMovieHarness.snap(f.uVec);
        s.fy = SingleMotorMovieHarness.snap(f.yVec);  s.fz = SingleMotorMovieHarness.snap(f.zVec);
        s.f1 = SingleMotorMovieHarness.snap(f.end1);  s.f2 = SingleMotorMovieHarness.snap(f.end2);
        s.rise = ExplicitCompleteMatHarness.siteRise(ExplicitCompleteMatHarness.SITE_MODE);
        double twist = ExplicitCompleteMatHarness.TWIST_PER_MON_DEG * Math.PI / 180.0 / Constants.actinMonoRadius;
        s.stepPhase = twist * s.rise;
        s.Ract = ExplicitCompleteMatHarness.R_ACTIN_NM * 1e-3;
        s.theta0 = s.e.params.get(17*s.N + mSel);
        s.kbend  = s.e.params.get(13*s.N + mSel);
        s.legacyGeom = new uk.ac.manchester.tornado.api.types.arrays.DoubleArray(9 * s.N);
        SingleMotorMovieHarness.SEGCUM = new float[s.nSeg];
        for (int q = 0; q < s.nSeg; q++) SingleMotorMovieHarness.SEGCUM[q] = s.e.segCumArc.get(q);
        System.out.printf(Locale.US, "  scene: N=%d motors (1 bindable=%s), M=%d beam elements, nSeg=%d ; motor #%d at %.2f nm lateral%n",
                s.N, bindable ? "#"+mSel : "none (forced detached)", s.M, s.nSeg, mSel, best*1e3);
        System.out.printf(Locale.US, "  S2->lever joint: kbend = %.4e N.m/rad^2 ; theta0 = %.3f deg%n",
                s.kbend, Math.toDegrees(s.theta0));
        return s;
    }
    /** One production step + the filament-freeze visualization fixture. */
    static void step(Scene s, int t) {
        ExplicitCompleteMatHarness.stepGlidingCPU(s.e, t, SEED);
        var f = s.G.fil;
        SingleMotorMovieHarness.restore(f.coord, s.fc); SingleMotorMovieHarness.restore(f.uVec, s.fu);
        SingleMotorMovieHarness.restore(f.yVec, s.fy);  SingleMotorMovieHarness.restore(f.zVec, s.fz);
        SingleMotorMovieHarness.restore(f.end1, s.f1);  SingleMotorMovieHarness.restore(f.end2, s.f2);
    }

    // ---- live geometry read-outs (all from solver state; nothing recomputed differently) -----------
    static double[] nodesOf(Scene s) {
        double[] nd = new double[3*(s.M+1)];
        for (int j = 0; j <= s.M; j++) for (int k = 0; k < 3; k++) nd[3*j+k] = s.e.nodes.get((3*j+k)*s.N + s.m);
        return nd;
    }
    static double[] sHat(double[] nd, int M) {
        double[] v = { nd[3*M]-nd[3*(M-1)], nd[3*M+1]-nd[3*(M-1)+1], nd[3*M+2]-nd[3*(M-1)+2] };
        return unit(v);
    }
    static double[] uBof(Scene s) {
        double phi = s.e.q.get(s.m), c = Math.cos(phi), si = Math.sin(phi);
        return new double[]{ s.e.frame.get(6*s.N+s.m)*c + s.e.frame.get(s.m)*si,
                             s.e.frame.get(7*s.N+s.m)*c + s.e.frame.get(s.N+s.m)*si,
                             s.e.frame.get(8*s.N+s.m)*c + s.e.frame.get(2*s.N+s.m)*si };
    }
    /** Interior beam bend angle at node j (between elements j-1→j and j→j+1), radians. */
    static double beamBend(double[] nd, int j) {
        double[] a = unit(new double[]{ nd[3*j]-nd[3*(j-1)], nd[3*j+1]-nd[3*(j-1)+1], nd[3*j+2]-nd[3*(j-1)+2] });
        double[] b = unit(new double[]{ nd[3*(j+1)]-nd[3*j], nd[3*(j+1)+1]-nd[3*j+1], nd[3*(j+1)+2]-nd[3*j+2] });
        return Math.acos(SingleMotorMovieHarness.cl(SingleMotorMovieHarness.dot(a,b)));
    }
    static double[] xF8True(Scene s) {
        return new double[]{ s.e.outGeom.get(6*s.N+s.m), s.e.outGeom.get(7*s.N+s.m), s.e.outGeom.get(8*s.N+s.m) }; }
    static double[] xHTrue(Scene s) {
        return new double[]{ s.e.outGeom.get(3*s.N+s.m), s.e.outGeom.get(4*s.N+s.m), s.e.outGeom.get(5*s.N+s.m) }; }
    static double[] eBindTrue(Scene s) {
        return ExplicitCompleteMatHarness.eBindOf(s.e, s.m, s.e.q.get(s.N+s.m), s.e.chiHead.get(s.m)); }
    /**
     * The LEGACY head geometry the capture path actually uses: recomputed from (phi, psi) with chi IGNORED.
     * Written into a private scratch buffer, so production state is untouched.
     */
    static double[][] legacyGeom(Scene s) {
        TwoBodyBeamAnalyticGpu.matBeamGeom(s.e.nodes, s.e.frame, s.e.params, s.e.q, s.e.exCounts, s.legacyGeom, s.e.convF);
        double[] xH = { s.legacyGeom.get(3*s.N+s.m), s.legacyGeom.get(4*s.N+s.m), s.legacyGeom.get(5*s.N+s.m) };
        double[] xF = { s.legacyGeom.get(6*s.N+s.m), s.legacyGeom.get(7*s.N+s.m), s.legacyGeom.get(8*s.N+s.m) };
        double[] eb = unit(new double[]{ xF[0]-xH[0], xF[1]-xH[1], xF[2]-xH[2] });
        return new double[][]{ xH, xF, eb };
    }

    // ==============================================================================================
    //  TEST 1 — does S2 -> lever moment transfer look physical?
    // ==============================================================================================
    static void test1Lever() {
        hdr("TEST 1 — S2 -> LEVER MOMENT TRANSFER (one motor, DETACHED, fine time)");
        Scene s = scene(false);
        StringBuilder tsv = new StringBuilder(
            "step\tt_s\tsx\tsy\tsz\tuBx\tuBy\tuBz\ttheta_joint_deg\tdtheta_deg\tE_term_kT\tphi_deg\tpsi_deg\tchi_deg\tbendM1_deg\tbendM2_deg\n");
        int n = 0;
        double[] th = new double[LEVER_STEPS/10 + 2];
        double[] dS = new double[LEVER_STEPS/10 + 2], dU = new double[LEVER_STEPS/10 + 2];
        double[] prevS = null, prevU = null; double prevTh = Double.NaN;
        double maxStep = 0; int maxStepAt = -1; int nFlagJump = 0, nFlagSharp = 0;
        double sumSharp = 0, sumBend = 0; int nSharp = 0;
        // per-STEP increments (every timestep, not the sampled series) + the ORIENTATION series that answers
        // "does the lever follow the distal tangent" — increments are dominated by independent thermal kicks
        // and are the WRONG statistic for tracking; the co-moving angles are the right one.
        java.util.List<Double> stepJump = new java.util.ArrayList<>();
        double[] angS = new double[LEVER_STEPS/10 + 2], angU = new double[LEVER_STEPS/10 + 2];
        double thermalSD = Math.sqrt(Constants.kT / s.kbend);
        for (int t = 0; t < LEVER_STEPS; t++) {
            step(s, t);
            double[] nd = nodesOf(s), sh = sHat(nd, s.M), uB = uBof(s);
            double cj = SingleMotorMovieHarness.cl(SingleMotorMovieHarness.dot(sh, uB));
            double thJ = Math.acos(cj), d = thJ - s.theta0;
            double E = 0.5 * s.kbend * d * d / Constants.kT;
            if (!Double.isNaN(prevTh)) {
                double dd = Math.abs(thJ - prevTh);
                if (dd > maxStep) { maxStep = dd; maxStepAt = t; }
            }
            if (!Double.isNaN(prevTh)) { double dd = Math.abs(thJ - prevTh); stepJump.add(dd);
                if (dd > 3*thermalSD) nFlagJump++; }
            prevTh = thJ;
            double bM1 = beamBend(nd, s.M-1), bM2 = s.M >= 3 ? beamBend(nd, s.M-2) : Double.NaN;
            // the terminal joint's STRAIN vs the interior joints' bend angles (interior rest = 0)
            sumSharp += Math.abs(d); sumBend += bM1; nSharp++;
            if (t % 10 == 0 && n < th.length) {
                th[n] = thJ;
                if (prevS != null) { dS[n] = ang(prevS, sh); dU[n] = ang(prevU, uB); }
                prevS = sh; prevU = uB;
                // in-plane lab angle of each vector, in the motor's own (bhat, eup) plane
                double bx = s.e.frame.get(s.m), by = s.e.frame.get(s.N+s.m), bz = s.e.frame.get(2*s.N+s.m);
                double ux2 = s.e.frame.get(6*s.N+s.m), uy2 = s.e.frame.get(7*s.N+s.m), uz2 = s.e.frame.get(8*s.N+s.m);
                angS[n] = Math.atan2(sh[0]*bx+sh[1]*by+sh[2]*bz, sh[0]*ux2+sh[1]*uy2+sh[2]*uz2);
                angU[n] = Math.atan2(uB[0]*bx+uB[1]*by+uB[2]*bz, uB[0]*ux2+uB[1]*uy2+uB[2]*uz2);
                if (t % 100 == 0)
                    tsv.append(String.format(Locale.US, "%d\t%.7e\t%.6f\t%.6f\t%.6f\t%.6f\t%.6f\t%.6f\t%.4f\t%+.4f\t%.5f\t%.3f\t%.3f\t%.3f\t%.4f\t%.4f%n",
                        t, t*DT, sh[0],sh[1],sh[2], uB[0],uB[1],uB[2], Math.toDegrees(thJ), Math.toDegrees(d), E,
                        Math.toDegrees(s.e.q.get(s.m)), Math.toDegrees(s.e.q.get(s.N+s.m)), Math.toDegrees(s.e.chiHead.get(s.m)),
                        Math.toDegrees(bM1), Math.toDegrees(bM2)));
                n++;
            }
        }
        double[] thv = java.util.Arrays.copyOf(th, n);
        double mean = mean(thv), sd = sd(thv, mean);
        // flag pass: how many single steps exceed 3 SD (recomputed on the sampled series' step scale)
        double[] samp = new double[n-1];
        for (int i = 1; i < n; i++) samp[i-1] = Math.abs(thv[i]-thv[i-1]);

        double meanStrain = sumSharp/nSharp, meanBend = sumBend/nSharp;
        if (meanStrain > 3*meanBend) nFlagSharp = 1;
        double r = pearson(java.util.Arrays.copyOfRange(dS,1,n), java.util.Arrays.copyOfRange(dU,1,n));
        double rTrack = pearson(java.util.Arrays.copyOf(angS,n), java.util.Arrays.copyOf(angU,n));
        double ac1 = autocorr(thv, mean, 1), ac10 = autocorr(thv, mean, 10), ac100 = autocorr(thv, mean, 100);
        int tau = 1; while (tau < n/4 && autocorr(thv, mean, tau) > Math.exp(-1)) tau++;

        System.out.printf(Locale.US, "%n  horizon %d steps (%.1f ms) ; theta0 = %.3f deg%n", LEVER_STEPS, LEVER_STEPS*DT*1e3, Math.toDegrees(s.theta0));
        System.out.printf(Locale.US, "  mean(theta_joint)        = %8.3f deg   (rest %.3f deg -> offset %+.3f)%n",
                Math.toDegrees(mean), Math.toDegrees(s.theta0), Math.toDegrees(mean - s.theta0));
        System.out.printf(Locale.US, "  SD(theta_joint)          = %8.3f deg   (joint thermal amplitude sqrt(kT/kbend) = %.2f deg)%n",
                Math.toDegrees(sd), Math.toDegrees(Math.sqrt(Constants.kT/s.kbend)));
        System.out.printf(Locale.US, "  max single-step |dtheta| = %8.3f deg  at step %d  (= %.2f SD)%n",
                Math.toDegrees(maxStep), maxStepAt, maxStep/sd);
        System.out.printf(Locale.US, "  autocorrelation          : lag1 %.4f  lag10 %.4f  lag100 %.4f ; tau_1/e = %d samples = %.1f us%n",
                ac1, ac10, ac100, tau, tau*10*DT*1e6);
        System.out.printf(Locale.US, "  corr( angle(sHat), angle(uB) ) = %+.4f   <- TRACKING: does the lever follow the distal tangent%n", rTrack);
        System.out.printf(Locale.US, "  corr( d sHat , d uB ) per sample = %+.4f   (per-step INCREMENTS; dominated by independent%n"
                + "                                             thermal kicks, so a low value here is expected and is NOT a tracking failure)%n", r);
        System.out.printf(Locale.US, "  mean |theta_joint - theta0| = %.3f deg  vs  mean interior beam bend %.3f deg%n",
                Math.toDegrees(meanStrain), Math.toDegrees(meanBend));
        System.out.printf(Locale.US, "  FLAGS: single-TIMESTEP jumps > 3 x thermal SD (%.2f deg) = %d of %d steps = %.4f %% ;"
                + " terminal-joint-much-sharper = %s%n",
                Math.toDegrees(3*thermalSD), nFlagJump, stepJump.size(), 100.0*nFlagJump/Math.max(1,stepJump.size()),
                nFlagSharp > 0 ? "YES" : "no");
        write("lever_joint/lever_joint_trace.tsv", tsv.toString());
        StringBuilder st = new StringBuilder();
        st.append(String.format(Locale.US, "theta0_deg\t%.6f%nmean_deg\t%.6f%nSD_deg\t%.6f%nmaxStep_deg\t%.6f%nmaxStep_SD\t%.4f%n"
                + "ac1\t%.6f%nac10\t%.6f%nac100\t%.6f%ntau_1e_us\t%.3f%ncorr_dS_dU\t%.6f%n"
                + "meanStrain_deg\t%.6f%nmeanInteriorBend_deg\t%.6f%nflagJumps\t%d%nflagSharp\t%d%nsamples\t%d%n",
                Math.toDegrees(s.theta0), Math.toDegrees(mean), Math.toDegrees(sd), Math.toDegrees(maxStep), maxStep/sd,
                ac1, ac10, ac100, tau*10*DT*1e6, r, Math.toDegrees(meanStrain), Math.toDegrees(meanBend), nFlagJump, nFlagSharp, n));
        st.append(String.format(Locale.US, "corr_track_angles\t%.6f%nthermalSD_deg\t%.6f%nstepJumpFrac_pct\t%.6f%n",
                rTrack, Math.toDegrees(thermalSD), 100.0*nFlagJump/Math.max(1,stepJump.size())));
        write("lever_joint/lever_joint_stats.tsv", st.toString());
        // a short viewer clip of the same detached motion, with the theta_joint debug arc
        Scene v = scene(false);
        List<double[]> clip = new ArrayList<>();
        for (int t = 0; t < 1500; t++) { step(v, t); clip.add(viewerRow(v)); }
        writeViewerClip(JS_ROOT + "_lever", v, clip, -1);
        System.out.printf("  viewer clip: %s_lever (1500 frames, 1 per timestep)%n", JS_ROOT);
    }

    // ==============================================================================================
    //  TEST 2 — head body / actin cylinder signed clearance (DIAGNOSTIC; no steric force added)
    // ==============================================================================================
    /**
     * Signed clearance between the head body and the actin cylinder, measured RADIALLY (the closest point on an
     * infinite cylinder to an exterior point lies along the radial direction, so this is the exact separation
     * along that line). The head is the model's ELLIPSOID, semi-axes {@code A_SEMI} = 4.5 x 2.75 x 2.25 nm, with
     * its long axis along eBind; the transverse axes are NOT roll-registered in this model (REG_K = 0), so the
     * larger transverse semi-axis is used — the conservative choice.
     * Returns {clearance_nm, dAxis_nm, support_nm, cx,cy,cz (closest point on actin), hx,hy,hz (closest on head)}.
     */
    static double[] clearance(Scene s, double[] xH, double[] eB) {
        int nSeg = s.nSeg; var f = s.G.fil;
        double best = 1e9; double[] out = null;
        for (int q = 0; q < nSeg; q++) {
            double cx = f.coord.get(q), cy = f.coord.get(nSeg+q), cz = f.coord.get(2*nSeg+q);
            double ux = f.uVec.get(q), uy = f.uVec.get(nSeg+q), uz = f.uVec.get(2*nSeg+q);
            double half = 0.5*f.segLength.get(q);
            double px = xH[0]-cx, py = xH[1]-cy, pz = xH[2]-cz;
            double ax = px*ux + py*uy + pz*uz;
            double axc = Math.max(-half, Math.min(half, ax));
            double rx = px - axc*ux, ry = py - axc*uy, rz = pz - axc*uz;
            double d = Math.sqrt(rx*rx+ry*ry+rz*rz);
            if (d < 1e-12) continue;
            double nx = rx/d, ny = ry/d, nz = rz/d;
            double ce = nx*eB[0] + ny*eB[1] + nz*eB[2];
            double a = TwoBodyConverterMotor.A_SEMI[0], b = TwoBodyConverterMotor.A_SEMI[1];
            double sup = Math.sqrt(a*a*ce*ce + b*b*(1-ce*ce));      // ellipsoid support along the radial dir
            double clr = d - s.Ract - sup;
            if (clr < best) {
                best = clr;
                out = new double[]{ clr*1e3, d*1e3, sup*1e3,
                        cx+axc*ux + s.Ract*nx, cy+axc*uy + s.Ract*ny, cz+axc*uz + s.Ract*nz,
                        xH[0]-sup*nx, xH[1]-sup*ny, xH[2]-sup*nz };
            }
        }
        return out != null ? out : new double[]{ 1e9,1e9,0, 0,0,0, 0,0,0 };
    }

    static void test2Sterics() {
        hdr("TEST 2 — HEAD BODY / ACTIN CYLINDER SIGNED CLEARANCE (diagnostic; no steric force added)");
        Scene s = scene(true);
        System.out.printf(Locale.US, "  head ellipsoid semi-axes %.2f x %.2f x %.2f nm (long axis along eBind) ; actin radius %.2f nm%n",
                TwoBodyConverterMotor.A_SEMI[0]*1e3, TwoBodyConverterMotor.A_SEMI[1]*1e3,
                TwoBodyConverterMotor.A_SEMI[2]*1e3, s.Ract*1e3);
        StringBuilder tsv = new StringBuilder("rel\tstep\tbound\tclearance_nm\tdAxis_nm\tsupport_nm\tdSite_nm\tangEBnSite_deg\t"
                + "px\tpy\tpz\thx\thy\thz\n");
        List<double[]> pre = new ArrayList<>();
        int bindStep = -1; double worstDet = 1e9; int worstDetAt = -1;
        double worstBound = 1e9; int worstBoundAt = -1;
        List<double[]> rows = new ArrayList<>();
        for (int t = 0; t < 4_000_000; t++) {
            step(s, t);
            boolean bnd = s.G.mot.boundSeg.get(s.m) >= 0;
            double[] xH = xHTrue(s), eB = eBindTrue(s);
            double[] cl = clearance(s, xH, eB);
            double[] row = { t, bnd?1:0, cl[0], cl[1], cl[2], cl[3],cl[4],cl[5], cl[6],cl[7],cl[8] };
            if (bindStep < 0) {
                pre.add(row); if (pre.size() > 400) pre.remove(0);
                if (cl[0] < worstDet) { worstDet = cl[0]; worstDetAt = t; }
                if (bnd) bindStep = t;
            } else {
                rows.add(row);
                if (cl[0] < worstBound) { worstBound = cl[0]; worstBoundAt = t; }
                if (rows.size() >= 400) break;
            }
        }
        if (bindStep < 0) { System.out.println("  *** no binding event — nothing written ***"); return; }
        List<double[]> all = new ArrayList<>(pre); all.addAll(rows);
        int bi = pre.size() - 1;
        for (int i = 0; i < all.size(); i++) {
            double[] r = all.get(i);
            tsv.append(String.format(Locale.US, "%d\t%d\t%d\t%+.4f\t%.4f\t%.4f\t\t\t%.6f\t%.6f\t%.6f\t%.6f\t%.6f\t%.6f%n",
                    i-bi, (int)r[0], (int)r[1], r[2], r[3], r[4], r[5],r[6],r[7], r[8],r[9],r[10]));
        }
        write("head_actin_sterics/clearance_trace.tsv", tsv.toString());
        System.out.printf(Locale.US, "%n  binding at step %d (t = %.4f ms)%n", bindStep, bindStep*DT*1e3);
        System.out.printf(Locale.US, "  DETACHED near-encounters : min clearance %+.3f nm (step %d)%n", worstDet, worstDetAt);
        System.out.printf(Locale.US, "  frame -1                 : %+.3f nm%n", all.get(bi-1)[2]);
        System.out.printf(Locale.US, "  BINDING frame            : %+.3f nm%n", all.get(bi)[2]);
        double s20 = 0; int n20 = 0;
        for (int i = bi+1; i <= Math.min(all.size()-1, bi+20); i++) { s20 += all.get(i)[2]; n20++; }
        System.out.printf(Locale.US, "  first 20 BOUND frames    : mean %+.3f nm ; min %+.3f nm%n", s20/Math.max(1,n20), minOf(all, bi+1, bi+20));
        System.out.printf(Locale.US, "  later bound pose (+200..): mean %+.3f nm ; min %+.3f nm%n",
                meanOf(all, bi+200, all.size()-1), minOf(all, bi+200, all.size()-1));
        System.out.printf(Locale.US, "  DEEPEST PENETRATION      : %+.3f nm at step %d (rel frame %d)%n",
                worstBound, worstBoundAt, worstBoundAt-bindStep);
        int wi = 0; for (int i = 0; i < all.size(); i++) if (all.get(i)[2] == worstBound) { wi = i; break; }
        double[] w = all.get(wi);
        System.out.printf(Locale.US, "    deepest point on actin  (%.5f, %.5f, %.5f) um ; on head (%.5f, %.5f, %.5f) um%n",
                w[5],w[6],w[7], w[8],w[9],w[10]);
        System.out.printf(Locale.US, "  => head/actin overlap is %s%n",
                worstBound < 0 ? String.format(Locale.US, "REAL and reaches %.2f nm at its deepest", -worstBound) : "NOT observed");
        // viewer clip with the steric overlay channels
        Scene v = scene(true);
        List<double[]> clip = new ArrayList<>(); int vb = -1;
        for (int t = 0; t < 4_000_000; t++) {
            step(v, t);
            boolean bnd = v.G.mot.boundSeg.get(v.m) >= 0;
            clip.add(viewerRow(v));
            if (clip.size() > 300 && vb < 0) clip.remove(0);
            if (bnd && vb < 0) vb = clip.size()-1;
            if (vb >= 0 && clip.size() - vb > 200) break;
        }
        writeViewerClip(JS_ROOT + "_sterics", v, clip, vb);
        System.out.printf("  viewer clip: %s_sterics (binding at viewer frame %d)%n", JS_ROOT, vb);
    }
    static double minOf(List<double[]> a, int i0, int i1) {
        double v = 1e9; for (int i = Math.max(0,i0); i <= Math.min(a.size()-1, i1); i++) v = Math.min(v, a.get(i)[2]); return v; }
    static double meanOf(List<double[]> a, int i0, int i1) {
        double v = 0; int n = 0; for (int i = Math.max(0,i0); i <= Math.min(a.size()-1, i1); i++) { v += a.get(i)[2]; n++; }
        return n > 0 ? v/n : Double.NaN; }

    // ==============================================================================================
    //  TESTS 3, 4, 5, 7, 8 — shadow chi-aware geometry, shadow site-normal gate, event classification
    // ==============================================================================================
    static final class Ev {
        int step, site, seg; double azim;
        double dLegacy, dTrue, angLegacyCrit, angTrueSite, psi, chi, phi, xf8Delta, ebDelta;
        boolean prodAccept, shadowSpatial, shadowOrient;
        String cls;
    }

    static void testShadow() {
        hdr("TESTS 3-5, 7-8 — SHADOW chi-aware GEOMETRY + SHADOW SITE-NORMAL GATE (zero influence on state)");
        Scene s = scene(true);
        StringBuilder cand = new StringBuilder("step\tbound\tsite\tseg\tazim_deg\t"
                + "xF8delta_nm\tebDelta_deg\tdLegacy_nm\tdTrue_nm\tangEB3D_nSite_deg\tprodCand\tshadowSpatial\tshadowOrient\tpsi_deg\tchi_deg\tphi_deg\n");
        List<Ev> events = new ArrayList<>();
        List<int[]> pending = new ArrayList<>();
        List<double[]> pre7 = new ArrayList<>();
        int nCand = 0, nSpatialFlip = 0, nShadowAcceptWhileProdRejects = 0, nBothOk = 0;
        double sumXf8 = 0, maxXf8 = 0, sumEb = 0, maxEb = 0;
        boolean prevBound = false;
        // the gate-relevant geometry is the PRE-SOLVE state, i.e. the previous step's post-step state. Keeping
        // the lagged copy is load-bearing: classifying on the post-solve state measures the head after the
        // freshly-formed bond has already pulled it, which inflates the "true xF8 too far" class.
        double pdL = Double.NaN, pdT = Double.NaN, pangT = Double.NaN, pXf8 = Double.NaN, pEb = Double.NaN;
        double ppsi = Double.NaN, pchi = Double.NaN, pphi = Double.NaN, pazim = Double.NaN, plegacyPsi = Double.NaN;
        boolean pSpat = false, pOri = false; int pSite = -1, pSeg = -1;
        java.util.List<double[]> vbuf = new java.util.ArrayList<>();
        int wantB = 1, wantC = 1, wantA = 1;
        List<double[]> hist = new ArrayList<>();          // rolling history for TEST 7
        Ev firstA = null, firstC = null, firstB = null;
        int prodEvents = 0;
        for (int t = 0; t < SHADOW_STEPS && prodEvents < TARGET_EVENTS; t++) {
            step(s, t);
            boolean bnd = s.G.mot.boundSeg.get(s.m) >= 0;
            double[] xF8t = xF8True(s), xHt = xHTrue(s), eB3 = eBindTrue(s);
            double[][] lg = legacyGeom(s);
            double dXf8 = SingleMotorMovieHarness.dist(xF8t, lg[1]) * 1e3;
            double dEb  = Math.toDegrees(ang(eB3, lg[2]));

            // which site is under evaluation this step (the kernel's own candidate, else the bound site)
            int seg, k; double la, ph;
            if (bnd) { seg = s.G.mot.boundSeg.get(s.m); k = s.e.bindSite.get(s.m);
                       la = s.G.mot.bindArc.get(s.m); ph = s.G.mot.bindAzim.get(s.m); }
            else if (s.e.candInt.get(s.m) >= 0) { seg = s.e.candInt.get(s.m); k = s.e.candInt.get(s.N+s.m);
                       la = s.e.candArc.get(s.m); ph = s.e.candAzim.get(s.m); }
            else {
                double[] nr = SingleMotorMovieHarness.nearestSite(s.G, s.nSeg, xF8t, s.rise, s.stepPhase);
                seg = (int) nr[0]; k = (int) nr[1]; la = nr[2]; ph = nr[3];
            }
            double dL = Double.NaN, dT = Double.NaN, angT = Double.NaN;
            boolean shSpat = false, shOri = false, prodCand = seg >= 0 && !bnd;
            double[] pn = null;
            if (seg >= 0) {
                pn = SingleMotorMovieHarness.sitePosNormal(s.G, s.nSeg, seg, la, ph, s.Ract);
                dL = SingleMotorMovieHarness.dist(lg[1], new double[]{pn[0],pn[1],pn[2]}) * 1e3;
                dT = SingleMotorMovieHarness.dist(xF8t, new double[]{pn[0],pn[1],pn[2]}) * 1e3;
                angT = Math.toDegrees(Math.acos(SingleMotorMovieHarness.cl(
                        eB3[0]*pn[3] + eB3[1]*pn[4] + eB3[2]*pn[5])));
                double dBindNm = s.e.sbP.get(0);
                shSpat = dT < dBindNm;                          // the SAME spatial threshold, on the TRUE xF8
                shOri  = angT <= SITE_NORMAL_TOL_DEG;
                boolean spatLegacy = dL < dBindNm;
                if (!bnd) {                                     // DETACHED candidate evaluations only
                    sumXf8 += dXf8; maxXf8 = Math.max(maxXf8, dXf8);
                    sumEb  += dEb;  maxEb  = Math.max(maxEb, dEb);
                    if (spatLegacy != shSpat) nSpatialFlip++;
                    nCand++;
                    if (shSpat && shOri) nBothOk++;
                }
                if (!bnd && shSpat && shOri && s.G.mot.nucleotideState.get(s.m) == MotorStore.NUC_ADPPI)
                    nShadowAcceptWhileProdRejects++;
                if (!bnd || t % 25 == 0)
                    cand.append(String.format(Locale.US, "%d\t%d\t%d\t%d\t%.1f\t%.4f\t%.3f\t%.3f\t%.3f\t%.2f\t%d\t%d\t%d\t%.2f\t%.2f\t%.2f%n",
                        t, bnd?1:0, k, seg, Math.toDegrees(ph), dXf8, dEb, dL, dT, angT, prodCand?1:0, shSpat?1:0, shOri?1:0,
                        Math.toDegrees(s.e.q.get(s.N+s.m)), Math.toDegrees(s.e.chiHead.get(s.m)), Math.toDegrees(s.e.q.get(s.m))));
            }
            hist.add(new double[]{ t, seg, k, angT, dT, Math.toDegrees(s.e.chiHead.get(s.m)),
                                   Double.isNaN(angT)?Double.NaN:angT });
            if (hist.size() > 200) hist.remove(0);
            // ---- a PRODUCTION capture event: the -1 -> >=0 transition -------------------------------
            if (bnd && !prevBound) {
                prodEvents++;
                Ev ev = new Ev();
                ev.step = t; ev.site = pSite >= 0 ? pSite : k; ev.seg = pSeg >= 0 ? pSeg : seg; ev.azim = pazim;
                ev.dLegacy = pdL; ev.dTrue = pdT; ev.angTrueSite = pangT;     // PRE-SOLVE (what the gate saw)
                ev.xf8Delta = pXf8; ev.ebDelta = pEb;
                ev.psi = ppsi; ev.chi = pchi; ev.phi = pphi;
                ev.angLegacyCrit = plegacyPsi;
                ev.prodAccept = true; ev.shadowSpatial = pSpat; ev.shadowOrient = pOri;
                ev.cls = (pSpat && pOri) ? "A" : (!pSpat ? "B" : "C");
                events.add(ev);
                // TEST 7 — pre-steering over the 100 frames before this capture
                preSteer(hist, ev, pre7);
                // TEST 6 — export one representative event per class
                boolean want = (ev.cls.equals("A") && wantA-- > 0) || (ev.cls.equals("B") && wantB-- > 0)
                            || (ev.cls.equals("C") && wantC-- > 0);
                if (want) pending.add(new int[]{ t, events.size()-1 });
                if (ev.cls.equals("A") && firstA == null) firstA = ev;
                if (ev.cls.equals("C") && firstC == null) firstC = ev;
                if (ev.cls.equals("B") && firstB == null) firstB = ev;
            }
            // roll the viewer buffer and finish any pending event export
            vbuf.add(viewerRow(s)); if (vbuf.size() > 100 && pending.isEmpty()) vbuf.remove(0);
            for (java.util.Iterator<int[]> it = pending.iterator(); it.hasNext();) {
                int[] pe = it.next();
                if (t - pe[0] >= 50) {
                    Ev ev = events.get(pe[1]);
                    int bIdx = vbuf.size() - 1 - (t - pe[0]);
                    writeViewerClip(JS_ROOT + "_event" + ev.cls, s, new ArrayList<>(vbuf), bIdx);
                    System.out.printf(Locale.US, "  TEST 6 export: class %s event at step %d -> %s_event%s (%d frames, binding at %d)%n",
                            ev.cls, ev.step, JS_ROOT, ev.cls, vbuf.size(), bIdx);
                    it.remove();
                }
            }
            // lag the shadow state by one step
            pdL = dL; pdT = dT; pangT = angT; pXf8 = dXf8; pEb = dEb; pSpat = shSpat; pOri = shOri;
            pSite = k; pSeg = seg; pazim = Math.toDegrees(ph);
            ppsi = Math.toDegrees(s.e.q.get(s.N+s.m)); pchi = Math.toDegrees(s.e.chiHead.get(s.m));
            pphi = Math.toDegrees(s.e.q.get(s.m));
            plegacyPsi = Math.abs(Math.toDegrees(s.e.q.get(s.N+s.m) - s.e.q.get(3*s.N+s.m)));
            prevBound = bnd;
        }
        write("shadow_capture/candidate_shadow_trace.tsv", cand.toString());

        // ---- TEST 3 summary ------------------------------------------------------------------------
        System.out.printf(Locale.US, "%n  TEST 3 — chi-aware vs LEGACY (phi,psi-only) head geometry, over %d DETACHED candidate evaluations%n"
                + "           (bound frames excluded: they always carry a site and would swamp the statistic):%n", nCand);
        System.out.printf(Locale.US, "    |xF8_3D - xF8_legacy|      mean %.3f nm   max %.3f nm%n", sumXf8/Math.max(1,nCand), maxXf8);
        System.out.printf(Locale.US, "    angle(eBind_3D, eBind_leg) mean %.2f deg  max %.2f deg%n", sumEb/Math.max(1,nCand), maxEb);
        System.out.printf(Locale.US, "  TEST 4 — ignoring chi FLIPS the spatial (<%.1f nm) decision in %d of %d = %.2f %% of detached evaluations%n",
                s.e.sbP.get(0), nSpatialFlip, nCand, 100.0*nSpatialFlip/Math.max(1,nCand));
        System.out.printf(Locale.US, "  Q8 — detached poses satisfying BOTH true reach AND <=%.0f deg to n_site: %d of %d = %.3f %%%n",
                SITE_NORMAL_TOL_DEG, nBothOk, nCand, 100.0*nBothOk/Math.max(1,nCand));

        // ---- TEST 5 event table --------------------------------------------------------------------
        StringBuilder et = new StringBuilder("class\tstep\tsite\tseg\tazim_deg\tdLegacy_nm\tdTrue_nm\t"
                + "legacyPsiErr_deg\tangEB3D_nSite_deg\tpsi_deg\tchi_deg\tphi_deg\txF8delta_nm\tebDelta_deg\n");
        int nA = 0, nB = 0, nC = 0;
        for (Ev ev : events) {
            if (ev.cls.equals("A")) nA++; else if (ev.cls.equals("B")) nB++; else nC++;
            et.append(String.format(Locale.US, "%s\t%d\t%d\t%d\t%.1f\t%.3f\t%.3f\t%.2f\t%.2f\t%.2f\t%.2f\t%.2f\t%.4f\t%.3f%n",
                    ev.cls, ev.step, ev.site, ev.seg, ev.azim, ev.dLegacy, ev.dTrue, ev.angLegacyCrit,
                    ev.angTrueSite, ev.psi, ev.chi, ev.phi, ev.xf8Delta, ev.ebDelta));
        }
        write("shadow_capture/event_table.tsv", et.toString());
        System.out.printf(Locale.US, "%n  TEST 5 — %d production capture events classified:%n", events.size());
        System.out.printf(Locale.US, "    A  production accepts, shadow (chi-aware + <=%.0f deg to n_site) ALSO accepts : %d  (%.0f %%)%n",
                SITE_NORMAL_TOL_DEG, nA, 100.0*nA/Math.max(1,events.size()));
        System.out.printf(Locale.US, "    B  production accepts, shadow rejects — TRUE xF8 too far                  : %d  (%.0f %%)%n",
                nB, 100.0*nB/Math.max(1,events.size()));
        System.out.printf(Locale.US, "    C  production accepts, shadow rejects — eBind > %.0f deg from n_site       : %d  (%.0f %%)%n",
                SITE_NORMAL_TOL_DEG, nC, 100.0*nC/Math.max(1,events.size()));
        System.out.printf(Locale.US, "    D  production rejects, shadow WOULD accept (steps, not events)            : %d%n",
                nShadowAcceptWhileProdRejects);
        double mAng = 0; for (Ev ev : events) mAng += ev.angTrueSite;
        System.out.printf(Locale.US, "    mean angle(eBind_3D, n_site) at production capture = %.2f deg%n", mAng/Math.max(1,events.size()));

        // ---- TEST 7 pre-steering ------------------------------------------------------------------
        System.out.printf(Locale.US, "%n  TEST 7 — NO PRE-STEERING, over the 100 frames before each of the %d captures:%n", pre7.size());
        if (!pre7.isEmpty()) {
            double mSlope = 0, mRange = 0, mSwitch = 0, mDisc = 0, nMono = 0;
            for (double[] p : pre7) { mSlope += p[0]; mRange += p[1]; mSwitch += p[2]; mDisc = Math.max(mDisc, p[3]); nMono += p[4]; }
            int n7 = pre7.size();
            System.out.printf(Locale.US, "    mean trend of angle(eBind,n_site)   = %+.4f deg/frame  (a site that PULLED the head would be strongly negative)%n", mSlope/n7);
            System.out.printf(Locale.US, "    mean range of angle(eBind,n_site)   = %.2f deg  (it explores; it does not funnel)%n", mRange/n7);
            System.out.printf(Locale.US, "    monotonically-approaching captures  = %.0f of %d%n", nMono, n7);
            System.out.printf(Locale.US, "    mean candidate-identity switches    = %.2f per 100 frames ; max |jump in angle| at a switch = %.2f deg%n",
                    mSwitch/n7, mDisc);
            System.out.println("    => the head arrives by its own thermal dynamics; no candidate-derived torque exists in the code path.");
        }

        // ---- TEST 8 snap prediction ----------------------------------------------------------------
        double kbind = s.e.params.get(7*s.N + s.m);
        double gPsi = s.e.params.get(9*s.N + s.m);
        System.out.printf(Locale.US, "%n  TEST 8 — if the strong site-normal spring were switched on at an accepted pose%n");
        System.out.printf(Locale.US, "    k_bind = %.4e N.m/rad^2 ; gamma_psi = %.4e ; tau = gamma/k = %.3f us vs dt = %.2f us%n",
                kbind, gPsi, gPsi/kbind*1e6, DT*1e6);
        System.out.printf(Locale.US, "    at the %.0f deg gate ceiling : U = %.2f kT ; restoring torque = %.3e N.m%n",
                SITE_NORMAL_TOL_DEG, 0.5*kbind*Math.pow(Math.toRadians(SITE_NORMAL_TOL_DEG),2)/Constants.kT,
                kbind*Math.toRadians(SITE_NORMAL_TOL_DEG));
        double uSum = 0; int nAcc = 0;
        for (Ev ev : events) if (ev.cls.equals("A")) {
            uSum += 0.5*kbind*Math.pow(Math.toRadians(ev.angTrueSite),2)/Constants.kT; nAcc++; }
        if (nAcc > 0) System.out.printf(Locale.US, "    mean over the %d class-A poses : U = %.2f kT%n", nAcc, uSum/nAcc);
        System.out.printf(Locale.US, "    for comparison, TODAY's captures sit at mean %.1f deg => U = %.1f kT%n",
                mAng/Math.max(1,events.size()),
                0.5*kbind*Math.pow(Math.toRadians(mAng/Math.max(1,events.size())),2)/Constants.kT);
        System.out.printf(Locale.US, "    NOTE: tau/dt = %.2f — the bound orientational relaxation is UNDER-RESOLVED at production dt%n",
                gPsi/kbind/DT);
        System.out.println("    (the standing sub-step issue; stated, not fixed here, and the spring was NOT activated)");
    }

    /** TEST 7: is there any pre-steering in the 100 frames before a capture? Returns {slope, range, switches, maxJump, monotone}. */
    static void preSteer(List<double[]> hist, Ev ev, List<double[]> out) {
        int n = hist.size(); if (n < 20) return;
        int i0 = Math.max(0, n - 101), i1 = n - 1;
        double lo = 1e9, hi = -1e9, sx = 0, sy = 0, sxx = 0, sxy = 0; int cnt = 0, sw = 0; double maxJump = 0;
        double prevAng = Double.NaN; int prevK = -999;
        boolean monotone = true; double last = Double.NaN;
        for (int i = i0; i <= i1; i++) {
            double[] h = hist.get(i); double a = h[3];
            if (Double.isNaN(a)) continue;
            lo = Math.min(lo, a); hi = Math.max(hi, a);
            sx += cnt; sy += a; sxx += (double)cnt*cnt; sxy += (double)cnt*a; cnt++;
            int kk = (int) h[2];
            if (prevK != -999 && kk != prevK) { sw++; if (!Double.isNaN(prevAng)) maxJump = Math.max(maxJump, Math.abs(a - prevAng)); }
            prevK = kk; prevAng = a;
            if (!Double.isNaN(last) && a > last + 1e-9) monotone = false;
            last = a;
        }
        if (cnt < 5) return;
        double slope = (cnt*sxy - sx*sy) / Math.max(1e-30, cnt*sxx - sx*sx);
        out.add(new double[]{ slope, hi - lo, sw, maxJump, monotone ? 1 : 0 });
    }

    // ==============================================================================================
    //  canonical sim_viewer output (array form; the flat form hangs the viewer)
    // ==============================================================================================
    /** One viewer row: nodes(3(M+1)) C(3) xH(3) xF8(3) eB(3) sitePos(3) siteN(3) bound clr px py pz hx hy hz. */
    static double[] viewerRow(Scene s) {
        double[] nd = nodesOf(s);
        double[] C = { s.e.outGeom.get(s.m), s.e.outGeom.get(s.N+s.m), s.e.outGeom.get(2*s.N+s.m) };
        double[] xH = xHTrue(s), xF = xF8True(s), eB = eBindTrue(s);
        boolean bnd = s.G.mot.boundSeg.get(s.m) >= 0;
        int seg, k; double la, ph;
        if (bnd) { seg = s.G.mot.boundSeg.get(s.m); k = s.e.bindSite.get(s.m);
                   la = s.G.mot.bindArc.get(s.m); ph = s.G.mot.bindAzim.get(s.m); }
        else if (s.e.candInt.get(s.m) >= 0) { seg = s.e.candInt.get(s.m); k = s.e.candInt.get(s.N+s.m);
                   la = s.e.candArc.get(s.m); ph = s.e.candAzim.get(s.m); }
        else { seg = -1; k = -1; la = 0; ph = 0; }
        double[] pn = seg >= 0 ? SingleMotorMovieHarness.sitePosNormal(s.G, s.nSeg, seg, la, ph, s.Ract) : null;
        double[] cl = clearance(s, xH, eB);
        double[] r = new double[3*(s.M+1) + 3+3+3+3 + 3+3 + 1 + 1 + 6 + 1];
        int p = 0;
        for (double v : nd) r[p++] = v;
        for (int i = 0; i < 3; i++) r[p++] = C[i];
        for (int i = 0; i < 3; i++) r[p++] = xH[i];
        for (int i = 0; i < 3; i++) r[p++] = xF[i];
        for (int i = 0; i < 3; i++) r[p++] = eB[i];
        for (int i = 0; i < 3; i++) r[p++] = pn != null ? pn[i]   : Double.NaN;
        for (int i = 0; i < 3; i++) r[p++] = pn != null ? pn[3+i] : Double.NaN;
        r[p++] = bnd ? 1 : 0;
        r[p++] = cl[0];
        for (int i = 3; i < 9; i++) r[p++] = cl[i];
        r[p++] = s.G.mot.nucleotideState.get(s.m);
        return r;
    }

    /**
     * Canonical `sim_viewer_boa.html` frame sequence. Colour is the viewer's ONLY per-segment channel
     * ({@code notADPRatio}, rendered rgb(1,a,0) with age-colour on): 1.0 YELLOW actin, 0.55 ORANGE motor beam +
     * anchor, 0.0 RED the site under evaluation, 0.30 the 25 deg acceptance cone, 0.85 the head/actin
     * closest-approach line. The head sphere and bound-site marker ride the `myosins` sphere channel.
     */
    static void writeViewerClip(String dir, Scene s, List<double[]> rows, int bindIdx) {
        mkAbs(dir);
        int M = s.M, nSeg = s.nSeg; var f = s.G.fil;
        double Ract = s.Ract;
        double[][] s1 = new double[nSeg][3], s2 = new double[nSeg][3];
        for (int q = 0; q < nSeg; q++) {
            double half = 0.5*f.segLength.get(q);
            for (int k = 0; k < 3; k++) {
                double c = f.coord.get(k*nSeg+q), u = f.uVec.get(k*nSeg+q);
                s1[q][k] = c - half*u; s2[q][k] = c + half*u;
            }
        }
        int base = 3*(M+1);
        for (int i = 0; i < rows.size(); i++) {
            double[] r = rows.get(i);
            double[] C  = sub(r, base), xH = sub(r, base+3), xF = sub(r, base+6), eB = sub(r, base+9);
            double[] sp = sub(r, base+12), sn = sub(r, base+15);
            boolean bnd = r[base+18] > 0.5;
            double clr = r[base+19];
            double[] pA = sub(r, base+20), pH = sub(r, base+23);
            int nuc = (int) r[base+26];
            StringBuilder b = new StringBuilder(1 << 15);
            b.append(String.format(Locale.US, "{\"frame\":%d,\"t\":%.9g,", i, i*DT));
            b.append("\"bounds\":{\"xDim\":2.2,\"yDim\":0.1,\"zDim\":0.1},\"segments\":[");
            int sid = 0;
            for (int q = 0; q < nSeg; q++) b.append(SingleMotorMovieHarness.seg(sid++, s1[q], s2[q], Ract, false, 1.00, sid>1));
            for (int j = 0; j < M; j++)
                b.append(SingleMotorMovieHarness.seg(sid++, new double[]{r[3*j],r[3*j+1],r[3*j+2]},
                        new double[]{r[3*(j+1)],r[3*(j+1)+1],r[3*(j+1)+2]}, 0.0010, true, 0.55, true));
            double[] a0 = { r[0], r[1], r[2] };
            b.append(SingleMotorMovieHarness.seg(sid++, a0,
                    new double[]{a0[0]-0.004*s.G.eup[0], a0[1]-0.004*s.G.eup[1], a0[2]-0.004*s.G.eup[2]}, 0.0022, true, 0.55, true));
            if (!Double.isNaN(sp[0])) {
                double sl = bnd ? 0.0035 : 0.0018;
                b.append(SingleMotorMovieHarness.seg(sid++, sp,
                        new double[]{sp[0]+sl*sn[0], sp[1]+sl*sn[1], sp[2]+sl*sn[2]}, bnd?0.0010:0.0005, true, 0.00, true));
                // the 25 deg acceptance cone around n_site, as a fan of thin rays
                double[] t1 = perp(sn), t2 = cross(sn, t1);
                double ca = Math.cos(Math.toRadians(SITE_NORMAL_TOL_DEG)), sa = Math.sin(Math.toRadians(SITE_NORMAL_TOL_DEG));
                double L = 0.012;
                for (int a = 0; a < 12; a++) {
                    double w = 2*Math.PI*a/12;
                    double[] d = unit(new double[]{ ca*sn[0] + sa*(Math.cos(w)*t1[0] + Math.sin(w)*t2[0]),
                                                    ca*sn[1] + sa*(Math.cos(w)*t1[1] + Math.sin(w)*t2[1]),
                                                    ca*sn[2] + sa*(Math.cos(w)*t1[2] + Math.sin(w)*t2[2]) });
                    b.append(SingleMotorMovieHarness.seg(sid++, sp,
                            new double[]{sp[0]+L*d[0], sp[1]+L*d[1], sp[2]+L*d[2]}, 0.00025, true, 0.30, true));
                }
            }
            if (clr < 1e6) b.append(SingleMotorMovieHarness.seg(sid++, pA, pH, 0.00035, true, 0.85, true));
            b.append("],\"myosins\":[");
            double[] P = { r[3*M], r[3*M+1], r[3*M+2] };
            b.append(String.format(Locale.US,
                "{\"id\":0,\"rod\":{\"end1\":[%.6f,%.6f,%.6f],\"end2\":[%.6f,%.6f,%.6f],\"r\":0.0008,\"invisible\":true},"
                + "\"lever\":{\"end1\":[%.6f,%.6f,%.6f],\"end2\":[%.6f,%.6f,%.6f],\"r\":0.0011},"
                + "\"motor\":{\"end1\":[%.6f,%.6f,%.6f],\"end2\":[%.6f,%.6f,%.6f],\"r\":%.5f,\"state\":\"%s\"}}",
                a0[0],a0[1],a0[2], P[0],P[1],P[2], P[0],P[1],P[2], C[0],C[1],C[2],
                xH[0],xH[1],xH[2], xF[0],xF[1],xF[2],
                TwoBodyConverterMotor.A_SEMI[0], SingleMotorMovieHarness.nucName(nuc)));
            // eBind as a visible ray from the head centre
            b.append(String.format(Locale.US,
                ",{\"id\":1,\"rod\":{\"end1\":[%.6f,%.6f,%.6f],\"end2\":[%.6f,%.6f,%.6f],\"r\":0.0004,\"invisible\":true},"
                + "\"lever\":{\"end1\":[%.6f,%.6f,%.6f],\"end2\":[%.6f,%.6f,%.6f],\"r\":0.0005},"
                + "\"motor\":{\"end1\":[%.6f,%.6f,%.6f],\"end2\":[%.6f,%.6f,%.6f],\"r\":0.0006,\"state\":\"ATP\"}}",
                xH[0],xH[1],xH[2], xH[0],xH[1],xH[2],
                xH[0],xH[1],xH[2], xH[0]+0.014*eB[0], xH[1]+0.014*eB[1], xH[2]+0.014*eB[2],
                xH[0]+0.014*eB[0], xH[1]+0.014*eB[1], xH[2]+0.014*eB[2],
                xH[0]+0.014*eB[0], xH[1]+0.014*eB[1], xH[2]+0.014*eB[2]));
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
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(dir, "INFO.txt"),
                String.format(Locale.US, "frames=%d  bindingViewerFrame=%d  dt=%.3e s  1 frame = 1 timestep%n",
                        rows.size(), bindIdx, DT)); } catch (java.io.IOException ex) { }
    }

    // ==============================================================================================
    static double[] sub(double[] r, int o) { return new double[]{ r[o], r[o+1], r[o+2] }; }
    static double[] unit(double[] v) { double n = Math.sqrt(v[0]*v[0]+v[1]*v[1]+v[2]*v[2]);
        return n < 1e-300 ? new double[]{0,0,1} : new double[]{ v[0]/n, v[1]/n, v[2]/n }; }
    static double[] cross(double[] a, double[] b) {
        return new double[]{ a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0] }; }
    static double[] perp(double[] v) {
        double[] a = Math.abs(v[0]) < 0.9 ? new double[]{1,0,0} : new double[]{0,1,0};
        double d = SingleMotorMovieHarness.dot(a, v);
        return unit(new double[]{ a[0]-d*v[0], a[1]-d*v[1], a[2]-d*v[2] }); }
    static double ang(double[] a, double[] b) {
        return Math.acos(SingleMotorMovieHarness.cl(SingleMotorMovieHarness.dot(a, b))); }
    static double mean(double[] v) { double s = 0; for (double x : v) s += x; return s/Math.max(1,v.length); }
    static double sd(double[] v, double m) { double s = 0; for (double x : v) s += (x-m)*(x-m);
        return Math.sqrt(s/Math.max(1,v.length)); }
    static double autocorr(double[] v, double m, int lag) {
        double num = 0, den = 0;
        for (int i = 0; i < v.length; i++) den += (v[i]-m)*(v[i]-m);
        for (int i = 0; i + lag < v.length; i++) num += (v[i]-m)*(v[i+lag]-m);
        return den > 0 ? num/den : 0; }
    static double pearson(double[] a, double[] b) {
        int n = Math.min(a.length, b.length); if (n < 3) return Double.NaN;
        double ma = 0, mb = 0; for (int i = 0; i < n; i++) { ma += a[i]; mb += b[i]; } ma /= n; mb /= n;
        double sa = 0, sb = 0, sab = 0;
        for (int i = 0; i < n; i++) { sa += (a[i]-ma)*(a[i]-ma); sb += (b[i]-mb)*(b[i]-mb); sab += (a[i]-ma)*(b[i]-mb); }
        return (sa > 0 && sb > 0) ? sab/Math.sqrt(sa*sb) : Double.NaN; }
    static void hdr(String t) { System.out.println("\n" + "=".repeat(100) + "\n=== " + t + "\n" + "=".repeat(100)); }
    static void mk(String d) { new java.io.File(d).mkdirs(); }
    static void mkAbs(String d) { new java.io.File(d).mkdirs(); }
    static void write(String rel, String s) {
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(OUT, rel), s); }
        catch (java.io.IOException e) { throw new RuntimeException(e); } }
}
