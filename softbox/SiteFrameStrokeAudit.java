package softbox;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * SITE-FRAME POWER-STROKE POLARITY AUDIT — why did the revised motor show ~50/50 pulling vs resisting?
 *
 * <p><b>AUDIT ONLY. Nothing is tuned and no physics is changed by any path in this file.</b> Head geometry,
 * {@code R_F8}/{@code R_CONV}, the site lattice, {@code n_site}, the 25° gate, the g6/g2 retirement, g3, g5,
 * {@code k_det}, {@code k_bind}, {@code xCatch}, the preload threshold, chemistry, the lever joint, S2
 * mechanics, viscosity, the detached rest pose and sterics are all untouched.
 * Report: {@code docs/motor/SITE_FRAME_POWER_STROKE_AUDIT.md}.
 *
 * <pre>
 *   -sign      PHASE 1   the axial sign convention, traced and verified numerically
 *   -fixture   PHASE 3/4/10/11/12  deterministic canonical bound pose -> real stroke, every azimuth
 *   -natural   PHASE 5/6/7/8/9     natural events, stroke-aligned, decomposed
 *   -all       everything
 * </pre>
 *
 * <p><b>Runner: the CPU sequential runner throughout.</b> No GPU work is launched.
 */
public final class SiteFrameStrokeAudit {
    private SiteFrameStrokeAudit() {}

    static String OUT = "RUN_LOGS/motor_audit/site_frame_power_stroke";
    static final double DT = 2.5e-6;
    static int SEED = 20260812;
    static int RELAX = 4000;          // deterministic relaxation steps per equilibrium
    static int AZIM = 7;              // consecutive every4 sites (7 x 54 deg spans the full circle)
    static int NAT_STEPS = 600_000;

    public static void main(String[] args) {
        String mode = args.length > 0 && args[0].startsWith("-") ? args[0] : "-all";
        for (int i = 0; i < args.length; i++) switch (args[i]) {
            case "-out" -> OUT = args[++i];
            case "-seed" -> SEED = Integer.parseInt(args[++i]);
            case "-relax" -> RELAX = Integer.parseInt(args[++i]);
            case "-azim" -> AZIM = Integer.parseInt(args[++i]);
            case "-nat-steps" -> NAT_STEPS = Integer.parseInt(args[++i]);
            case "-stroke-window" -> { String[] w = args[++i].split(",");   // data-only cockP override (2026-10-24)
                ExplicitCompleteMatHarness.STROKE_WINDOW_PRE_DEG = Double.parseDouble(w[0]);
                ExplicitCompleteMatHarness.STROKE_WINDOW_POST_DEG = Double.parseDouble(w[1]); }
            default -> { }
        }
        new java.io.File(OUT).mkdirs();
        System.out.println("\n=== SITE-FRAME POWER-STROKE POLARITY AUDIT (audit only; nothing is tuned) ===");
        System.out.println("  RUNNER: CPU sequential runner. No GPU work is launched.");
        switch (mode) {
            case "-sign"    -> phase1Sign();
            case "-fixture" -> phase3Fixture();
            case "-natural" -> phase5Natural();
            default         -> { phase1Sign(); phase3Fixture(); phase5Natural(); }
        }
        System.out.println("\n  output -> " + OUT);
    }

    // ==============================================================================================
    //  scene — the canonical site-normal scene with motor Brownian OFF (deterministic fixtures)
    // ==============================================================================================
    static final class S {
        TwoBodyConverterMotor.Glide2D G; ExplicitCompleteMatHarness.ExMat e;
        int m, N, M, nSeg; double rise, stepPhase, Ract;
        float[] fc, fu, fy, fz, f1, f2;
        boolean freeze = true;
        boolean axialOnly = false; double[] u0;
    }
    static S scene(int brownOn) {
        ExplicitCompleteMatHarness.resetChiral();
        ExplicitCompleteMatHarness.SITE_MODE = 3;              // every4 sparse long-pitch
        ExplicitCompleteMatHarness.SITE_PHASE_GLOBAL = true;
        ExplicitCompleteMatHarness.SITE_AWARE = true;
        ExplicitCompleteMatHarness.SITE_EXCLUSIVE = true;
        ExplicitCompleteMatHarness.Z_SLAB = true;
        ExplicitCompleteMatHarness.RAND_BASE_AZ = false;
        ExplicitCompleteMatHarness.HEAD_TILT_3D = true;
        ExplicitCompleteMatHarness.HEAD_TILT_AXIS_FIX = true;
        ExplicitCompleteMatHarness.KBIND_BOUND_ONLY = true;
        ExplicitCompleteMatHarness.K_DET_PNNM = 5.0;
        ExplicitCompleteMatHarness.SITE_NORMAL_BIND = true;
        ExplicitCompleteMatHarness.SITE_NORMAL_TOL_DEG = 25.0;
        S s = new S();
        s.G = TwoBodyConverterMotor.buildS2Mat(4.0, DT, 40.0, TwoBodyConverterMotor.EXPLICIT_GLIDE_SLACK_NM, SEED, false);
        s.e = ExplicitCompleteMatHarness.packExMat(s.G, brownOn);
        s.N = s.e.N; s.M = s.e.M; s.nSeg = s.e.nSeg;
        TwoBodyBeamAnalyticGpu.matBeamGeom(s.e.nodes, s.e.frame, s.e.params, s.e.q, s.e.exCounts, s.e.outGeom, s.e.convF);
        int mSel = -1; double best = 1e9;
        for (int m = 0; m < s.N; m++) {
            double d = SingleMotorMovieHarness.axisDist(s.G, s.nSeg,
                    s.e.outGeom.get(6*s.N+m), s.e.outGeom.get(7*s.N+m), s.e.outGeom.get(8*s.N+m));
            if (d < best) { best = d; mSel = m; }
        }
        s.m = mSel;
        for (int m = 0; m < s.N; m++) { boolean nb = m != mSel; s.G.noBind[m] = nb; s.e.noBind.set(m, nb ? 1 : 0); }
        var f = s.G.fil;
        s.fc = SingleMotorMovieHarness.snap(f.coord); s.fu = SingleMotorMovieHarness.snap(f.uVec);
        s.fy = SingleMotorMovieHarness.snap(f.yVec);  s.fz = SingleMotorMovieHarness.snap(f.zVec);
        s.f1 = SingleMotorMovieHarness.snap(f.end1);  s.f2 = SingleMotorMovieHarness.snap(f.end2);
        s.rise = ExplicitCompleteMatHarness.siteRise(3);
        double twist = ExplicitCompleteMatHarness.TWIST_PER_MON_DEG * Math.PI / 180.0 / Constants.actinMonoRadius;
        s.stepPhase = twist * s.rise;
        s.Ract = ExplicitCompleteMatHarness.R_ACTIN_NM * 1e-3;
        SingleMotorMovieHarness.SEGCUM = new float[s.nSeg];
        for (int q = 0; q < s.nSeg; q++) SingleMotorMovieHarness.SEGCUM[q] = s.e.segCumArc.get(q);
        return s;
    }
    // ==============================================================================================
    //  2026-10-24 (jba): the SAME deterministic latch-and-stroke fixture, run on an ARBITRARY pre-built scene
    //  (SiteNormalLongGlideHarness -stroke-fixture builds the real gliding scene, with all its defaults and flags,
    //  and hands it here). Brownian must already be off (caller: packExMat(G, 0) + filament channels off).
    //  Motor choice: the motor nearest the filament axis among those whose stroke axis (bhat) passes the polarity
    //  tolerance, because the gliding capture gate admits only those. Optional viewer frames centred on the motor.
    // ==============================================================================================
    static int MOTOR_RANK = 0;
    static double FIX_DT = Double.NaN;   // the scene's dt, for frame timestamps (set by the caller)
    static S wrapScene(TwoBodyConverterMotor.Glide2D G, double polTolDeg, boolean verbose) {
        S s = new S();
        s.G = G; s.e = ExplicitCompleteMatHarness.packExMat(G, 0);
        s.N = s.e.N; s.M = s.e.M; s.nSeg = s.e.nSeg;
        TwoBodyBeamAnalyticGpu.matBeamGeom(s.e.nodes, s.e.frame, s.e.params, s.e.q, s.e.exCounts, s.e.outGeom, s.e.convF);
        double[] u0 = { G.fil.uVec.get(0), G.fil.uVec.get(s.nSeg), G.fil.uVec.get(2*s.nSeg) };
        if (polTolDeg < 0) polTolDeg = ExplicitCompleteMatHarness.POLARITY_GATE && !ExplicitCompleteMatHarness.CAPTURE_LIT && !WeakBindSystem.ON ? ExplicitCompleteMatHarness.POLARITY_TOL_DEG : 180.0;
        double cTol = Math.cos(Math.toRadians(polTolDeg));
        int mSel = -1; double best = 1e9, bestCos = Double.NaN;
        java.util.List<double[]> ok = new java.util.ArrayList<>();
        for (int m = 0; m < s.N; m++) {
            double bx = s.e.frame.get(m), by = s.e.frame.get(s.N+m), bz = s.e.frame.get(2*s.N+m);
            double c = (bx*u0[0] + by*u0[1] + bz*u0[2]) / Math.sqrt(bx*bx + by*by + bz*bz);
            if (c < cTol) continue;
            double d = SingleMotorMovieHarness.axisDist(G, s.nSeg,
                    s.e.outGeom.get(6*s.N+m), s.e.outGeom.get(7*s.N+m), s.e.outGeom.get(8*s.N+m));
            ok.add(new double[]{ d, m, c });
            if (d < best) { best = d; mSel = m; bestCos = c; }
        }
        if (MOTOR_RANK > 0 && MOTOR_RANK < ok.size()) {   // the k-th nearest polarity-passing motor (0 = nearest)
            ok.sort((x, y) -> Double.compare(x[0], y[0]));
            double[] r = ok.get(MOTOR_RANK); best = r[0]; mSel = (int) r[1]; bestCos = r[2];
        }
        if (mSel < 0) throw new IllegalStateException("no motor passes the polarity tolerance");
        s.m = mSel;
        if (verbose) System.out.printf(Locale.US, "  FIXTURE MOTOR   = %d of %d  (F8 %.1f nm from the axis, cos(bhat, barbed) %.4f, tol %.0f deg)%n",
                mSel, s.N, best * 1e3, bestCos, polTolDeg);
        for (int m = 0; m < s.N; m++) { boolean nb = m != mSel; G.noBind[m] = nb; s.e.noBind.set(m, nb ? 1 : 0); }
        var f = G.fil;
        s.fc = SingleMotorMovieHarness.snap(f.coord); s.fu = SingleMotorMovieHarness.snap(f.uVec);
        s.fy = SingleMotorMovieHarness.snap(f.yVec);  s.fz = SingleMotorMovieHarness.snap(f.zVec);
        s.f1 = SingleMotorMovieHarness.snap(f.end1);  s.f2 = SingleMotorMovieHarness.snap(f.end2);
        s.rise = ExplicitCompleteMatHarness.siteRise(3);
        double twist = ExplicitCompleteMatHarness.TWIST_PER_MON_DEG * Math.PI / 180.0 / Constants.actinMonoRadius;
        s.stepPhase = twist * s.rise;
        s.Ract = ExplicitCompleteMatHarness.R_ACTIN_NM * 1e-3;
        SingleMotorMovieHarness.SEGCUM = new float[s.nSeg];
        for (int q = 0; q < s.nSeg; q++) SingleMotorMovieHarness.SEGCUM[q] = s.e.segCumArc.get(q);
        return s;
    }
    /** The scene is REBUILT per azimuth and per restraint (build is deterministic), so every stroke starts from the
     *  same as-built state with no carried-over hidden state. */
    static void fixtureOnScene(java.util.function.Supplier<TwoBodyConverterMotor.Glide2D> build, int seed, int relax,
                               int azim, double polTolDeg, String outDir, String jsDir, int jsStride) {
        SEED = seed;
        new java.io.File(outDir).mkdirs();
        StringBuilder tsv = new StringBuilder("mode\tazim_deg\tdsF8_nm\tfax_pre_pN\tfax_post_pN\ttheta_pre\ttheta_post\t"
                + "phi_pre\tphi_post\tdFil_stroke_nm\tdFil_total_nm\n");
        int[] frameNo = { 0 };
        int t = 0;
        boolean first = true;
        StringBuilder trace = new StringBuilder("azim_idx\tstate\tstep\tt_ms\tdFil_axial_nm\tfax_pN\ttheta_deg\tbound\tneck_vs_n_deg\tneck_dot_barbed\tneck_vs_head_deg\tchi_deg\n");
        for (int mode = FIX_FREE_ONLY ? 1 : 0; mode < 2; mode++) {
            boolean freeze = mode == 0;
            System.out.printf(Locale.US, "%n  ---- %s ----%n    %9s %8s %8s %8s %9s %9s %10s%n", freeze ? "ISOMETRIC (filament frozen)" : "FREE (filament mobile)",
                    "azim", "dsF8", "fax_pre", "fax_post", "theta", "->", "dFil_strk");
            List<double[]> rows = new ArrayList<>();
            int nRun = PLACE_BETA != null ? PLACE_BETA.length : azim;
            for (int j = 0; j < nRun; j++) {
                S s = wrapScene(build.get(), polTolDeg, first); first = false;
                int mSel = s.m; var G = s.G;
                if (FREEZE_CHEM) {   // hold the nucleotide state the fixture sets: no in-step transitions, no release
                    for (int k = 1; k <= 7; k++) G.mot.nucParams.set(k, 0f);
                    G.mot.kinParams.set(0, 0f);
                    if (j == 0) System.out.println("  FIXTURE CHEMISTRY FROZEN (nucParams[1..7] = 0, kOff = 0)");
                }
                double[] origin = { s.e.nodes.get(mSel), s.e.nodes.get(s.N + mSel), s.e.nodes.get(2*s.N + mSel) };   // S2 base
                s.freeze = freeze;
                int seg;
                if (PLACE_BETA != null) { if (j >= PLACE_BETA.length) break; seg = placeFilament(s, PLACE_BETA[j], first || j == 0); }
                else { rotateFilamentFrame(s, 2*Math.PI*j/azim); seg = latchNearestCanonical(s); }
                double[] sn = seg < 0 ? null : SiteNormalBindSystem.boundSiteNormal(G, s.nSeg, mSel, s.Ract, 1.0, 0.0);
                if (sn == null) { System.out.printf(Locale.US, "    azimuth %d: no reachable site%n", j); continue; }
                double[] pc = SiteNormalBindHarness.poseForTarget(s.e, mSel, new double[]{ -sn[0], -sn[1], -sn[2] });
                if (pc == null) { System.out.printf(Locale.US, "    azimuth %d: no pose%n", j); continue; }
                s.e.q.set(s.N + mSel, pc[0]); s.e.chiHead.set(mSel, pc[1]);
                G.mot.nucleotideState.set(mSel, MotorStore.NUC_ADPPI);
                s.e.noBind.set(mSel, 1);
                double[] filPre = filCentroid(s);
                boolean js = jsDir != null && !freeze;
                double[] uT = uOf(s);
                for (int k = 0; k < relax; k++) {
                    G.mot.nucleotideState.set(mSel, MotorStore.NUC_ADPPI); step(s, t++);
                    if (js && k % jsStride == 0) jsFrame(s, jsDir, frameNo, origin, t);
                    if (!freeze && k % TRACE_STRIDE == 0) traceRow(trace, j, "ADPPi", k, s, sn, filPre, uT);
                }
                double[] pre = observe(s, sn); double[] filPreEq = filCentroid(s);
                for (int k = 0; k < relax; k++) {
                    G.mot.nucleotideState.set(mSel, MotorStore.NUC_ADP); step(s, t++);
                    if (js && k % jsStride == 0) jsFrame(s, jsDir, frameNo, origin, t);
                    if (!freeze && k % TRACE_STRIDE == 0) traceRow(trace, j, "ADP", relax + k, s, sn, filPre, uT);
                }
                double[] post = observe(s, sn); double[] filPost = filCentroid(s);
                boolean stillBound = G.mot.boundSeg.get(mSel) >= 0;
                double dStroke = dot(sub(filPost, filPreEq), uOf(s)) * 1e3, dTot = dot(sub(filPost, filPre), uOf(s)) * 1e3;
                double az = physAzim(s, sn);
                System.out.printf(Locale.US, "    %9.1f %+8.2f %+8.2f %+8.2f %+9.1f %+9.1f %+10.2f%s%n",
                        az, post[0]-pre[0], pre[1], post[1], pre[3], post[3], dStroke, stillBound ? "" : "   ** DETACHED **");
                tsv.append(String.format(Locale.US, "%s\t%.2f\t%.4f\t%.4f\t%.4f\t%.2f\t%.2f\t%.2f\t%.2f\t%.4f\t%.4f%n",
                        freeze ? "isometric" : "free", az, post[0]-pre[0], pre[1], post[1], pre[3], post[3], pre[2], post[2], dStroke, dTot));
                rows.add(new double[]{ freeze ? post[1]-pre[1] : dStroke });
            }
            int neg = 0; double sum = 0; for (double[] r : rows) { sum += r[0]; if (r[0] < 0) neg++; }
            System.out.printf(Locale.US, "    %s: %d/%d azimuths %s, mean %+.2f %s%n", freeze ? "force change" : "filament stroke",
                    neg, rows.size(), freeze ? "more negative (toward pointed)" : "toward the pointed end",
                    rows.isEmpty() ? Double.NaN : sum / rows.size(), freeze ? "pN" : "nm");
        }
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(outDir, "stroke_trace.tsv"), trace.toString()); }
        catch (java.io.IOException ex) { throw new RuntimeException(ex); }
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(outDir, "stroke_fixture.tsv"), tsv.toString()); }
        catch (java.io.IOException ex) { throw new RuntimeException(ex); }
        if (jsDir != null) System.out.printf(Locale.US, "  -fix-3js: %d viewer frames (FREE runs, %d azimuths in sequence) -> %s%n", frameNo[0], azim, jsDir);
    }
    // ==============================================================================================
    //  2026-10-25 (jba): PLACED single-motor fixture. The filament is put where the motor's own pre-stroke head
    //  lands (zero preload by construction), with the bound site's outward normal n = -(head axis) rotated about
    //  the filament axis by beta. beta = 0 is the site facing the motor head-on (chi ~ 0); beta = 90 is a side
    //  site (chi ~ 90). The filament is then a rigid AXIAL SLIDER: height, sideways position, orientation and roll
    //  are held; only axial translation is free (total force / total axial drag). A fixture choice, not physics.
    // ==============================================================================================
    static boolean FREEZE_CHEM = false;
    static double[] PLACE_BETA = null;   // deg; null = the old azimuth-scan fixture
    static boolean AXIAL_ONLY = false;
    static void geomNow(S s) {
        if (HeadChainSystem.ON) {
            if (s.e.hc == null) ExplicitCompleteMatHarness.headChainInit(s.e);
            HeadChainSystem.geom(s.e.nodes, s.e.params, s.e.hc, s.e.exCounts, s.e.outGeom);
            SiteNormalBindSystem.headAxisStep(s.e.frame, s.e.params, s.e.q, s.e.chiHead, s.e.convF, s.e.outGeom, s.e.exCounts);
            return;
        }
        TwoBodyBeamAnalyticGpu.matBeamGeomTilt(s.e.nodes, s.e.frame, s.e.params, s.e.q, s.e.exCounts, s.e.outGeom, s.e.convF, s.e.chiHead);
        SiteNormalBindSystem.headAxisStep(s.e.frame, s.e.params, s.e.q, s.e.chiHead, s.e.convF, s.e.outGeom, s.e.exCounts);
    }
    static double[] g3(S s, int row) { int N = s.N, m = s.m; return new double[]{ s.e.outGeom.get(row*N+m), s.e.outGeom.get((row+1)*N+m), s.e.outGeom.get((row+2)*N+m) }; }
    static double[] unitV(double[] a) { double l = Math.sqrt(dot(a, a)); return new double[]{ a[0]/l, a[1]/l, a[2]/l }; }
    static double[] crossV(double[] a, double[] b) { return new double[]{ a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0] }; }
    static double[] rotAbout(double[] v, double[] k, double ang) {
        double c = Math.cos(ang), sn = Math.sin(ang), d = dot(k, v); double[] x = crossV(k, v);
        return new double[]{ v[0]*c + x[0]*sn + k[0]*d*(1-c), v[1]*c + x[1]*sn + k[1]*d*(1-c), v[2]*c + x[2]*sn + k[2]*d*(1-c) };
    }
    static void translateFil(S s, double[] v) {
        var f = s.G.fil; int n = s.nSeg;
        for (int q = 0; q < n; q++) for (int k = 0; k < 3; k++) {
            f.coord.set(k*n+q, (float)(f.coord.get(k*n+q) + v[k]));
            f.end1.set(k*n+q, (float)(f.end1.get(k*n+q) + v[k]));
            f.end2.set(k*n+q, (float)(f.end2.get(k*n+q) + v[k]));
        }
    }
    /** {angle(neck, n) deg, neck . u_barbed, angle(neck, head-outward) deg}; neck = C -> S2 tip P. */
    static double[] neckGeom(S s, double[] sn) {
        int N = s.N, m = s.m, M = s.M;
        double[] P = { s.e.nodes.get((3*M)*N+m), s.e.nodes.get((3*M+1)*N+m), s.e.nodes.get((3*M+2)*N+m) };
        double[] C = g3(s, 0), F8 = g3(s, 6);
        double[] neck = unitV(sub(P, C)), hOut = unitV(sub(C, F8)), u = uOf(s);
        double[] snNow = SiteNormalBindSystem.boundSiteNormal(s.G, s.nSeg, m, s.Ract, 1.0, 0.0);
        double[] n = snNow != null ? new double[]{ snNow[0], snNow[1], snNow[2] } : new double[]{ sn[0], sn[1], sn[2] };
        return new double[]{ Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot(neck, n))))), dot(neck, u),
                Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot(neck, hOut))))) };
    }
    static int PLACE_PRERELAX = 8000;   // steps the motor relaxes UNBOUND (cocked, Brownian off) before the filament is placed
    static int placeFilament(S s, double betaDeg, boolean verbose) {
        int N = s.N, m = s.m;
        double thPre = s.e.cockP.get(0);
        if (PLACE_PRERELAX > 0) {
            boolean fz = s.freeze; s.freeze = true;
            s.e.noBind.set(m, 1); s.G.noBind[m] = true;
            translateFil(s, new double[]{ 0, 0, 0.5 });          // filament out of the way while the motor relaxes
            var f0 = s.G.fil;
            s.fc = SingleMotorMovieHarness.snap(f0.coord); s.f1 = SingleMotorMovieHarness.snap(f0.end1); s.f2 = SingleMotorMovieHarness.snap(f0.end2);
            double[] h0 = null;
            for (int k = 0; k < PLACE_PRERELAX; k++) {
                s.G.mot.nucleotideState.set(m, MotorStore.NUC_ADPPI);
                step(s, 2_000_000 + k);
                if (k == PLACE_PRERELAX - 1000) h0 = g3(s, 6);
            }
            double[] h1 = g3(s, 6), dd = sub(h1, h0);
            System.out.printf(Locale.US, "  PRE-RELAX unbound %d steps: F8 moved %.3f nm over the last 1000 steps; chi %+.1f deg%n",
                    PLACE_PRERELAX, Math.sqrt(dot(dd, dd))*1e3, Math.toDegrees(s.e.chiHead.get(m)));
            s.e.noBind.set(m, 0); s.G.noBind[m] = false; s.freeze = fz;
        }
        s.e.q.set(N+m, s.e.q.get(m) + thPre); s.e.q.set(2*N+m, thPre); s.e.chiHead.set(m, 0.0);
        geomNow(s);
        double[] u = uOf(s);
        double[] a = unitV(sub(g3(s, 6), g3(s, 3)));               // head axis, centre -> F8 (toward actin)
        double au = dot(a, u);
        double[] ap = unitV(new double[]{ a[0]-au*u[0], a[1]-au*u[1], a[2]-au*u[2] });
        double[] n = rotAbout(new double[]{ -ap[0], -ap[1], -ap[2] }, u, Math.toRadians(betaDeg));
        double[] pc = SiteNormalBindHarness.poseForTarget(s.e, m, new double[]{ -n[0], -n[1], -n[2] });
        if (pc == null) return -1;
        s.e.q.set(N+m, pc[0]); s.e.chiHead.set(m, pc[1]); s.e.q.set(m, pc[0] - thPre);   // lever from the cocked converter
        geomNow(s);
        double[] xf = g3(s, 6);
        double[] foot = { xf[0]-s.Ract*n[0], xf[1]-s.Ract*n[1], xf[2]-s.Ract*n[2] };
        double[] c0 = { s.G.fil.coord.get(0), s.G.fil.coord.get(s.nSeg), s.G.fil.coord.get(2*s.nSeg) };
        double[] w = sub(foot, c0); double wu = dot(w, u);
        translateFil(s, new double[]{ w[0]-wu*u[0], w[1]-wu*u[1], w[2]-wu*u[2] });
        int seg = -1;
        for (int it = 0; it < 4; it++) {
            double[] ns = SingleMotorMovieHarness.nearestSite(s.G, s.nSeg, xf, s.rise, s.stepPhase);
            seg = (int) ns[0]; if (seg < 0) return -1;
            double[] pn = SingleMotorMovieHarness.sitePosNormal(s.G, s.nSeg, seg, ns[2], ns[3], s.Ract);
            double[] nn = { pn[3], pn[4], pn[5] };
            double ang = Math.atan2(dot(u, crossV(nn, n)), dot(nn, n));
            rotateFilamentFrame(s, ang);
            pn = SingleMotorMovieHarness.sitePosNormal(s.G, s.nSeg, seg, ns[2], ns[3], s.Ract);
            double d = dot(sub(xf, new double[]{ pn[0], pn[1], pn[2] }), u);
            translateFil(s, new double[]{ d*u[0], d*u[1], d*u[2] });
        }
        var f = s.G.fil;
        s.fc = SingleMotorMovieHarness.snap(f.coord); s.fu = SingleMotorMovieHarness.snap(f.uVec);
        s.fy = SingleMotorMovieHarness.snap(f.yVec);  s.fz = SingleMotorMovieHarness.snap(f.zVec);
        s.f1 = SingleMotorMovieHarness.snap(f.end1);  s.f2 = SingleMotorMovieHarness.snap(f.end2);
        s.u0 = u; s.axialOnly = AXIAL_ONLY;
        seg = latch(s, 0);
        if (seg < 0) return -1;
        double[] sn = SiteNormalBindSystem.boundSiteNormal(s.G, s.nSeg, m, s.Ract, 1.0, 0.0);
        pc = SiteNormalBindHarness.poseForTarget(s.e, m, new double[]{ -sn[0], -sn[1], -sn[2] });
        s.e.q.set(N+m, pc[0]); s.e.chiHead.set(m, pc[1]); s.e.q.set(m, pc[0] - thPre);
        geomNow(s);
        double[] gap = sub(g3(s, 6), new double[]{ sn[3], sn[4], sn[5] });
        double[] ng = neckGeom(s, sn);
        System.out.printf(Locale.US, "  PLACED beta %+6.1f: head.u_barbed %+.3f before placement | site gap %.2f nm | n.target %.4f | chi %+.1f deg | "
                + "filament axis z %.1f nm | neck vs n %.1f deg, neck.u %+.3f, neck vs head %.1f deg%n",
                betaDeg, au, Math.sqrt(dot(gap, gap))*1e3, dot(n, new double[]{ sn[0], sn[1], sn[2] }), Math.toDegrees(pc[1]),
                (s.fc[2*s.nSeg+seg] - s.e.nodes.get(2*N+m))*1e3, ng[0], ng[1], ng[2]);
        return seg;
    }
    /** Axial slider: keep only the mean axial displacement of the filament; restore everything else. */
    static void slide(S s) {
        var f = s.G.fil; int n = s.nSeg; double[] u = s.u0; double d = 0;
        for (int q = 0; q < n; q++) d += (f.coord.get(q)-s.fc[q])*u[0] + (f.coord.get(n+q)-s.fc[n+q])*u[1] + (f.coord.get(2*n+q)-s.fc[2*n+q])*u[2];
        d /= n;
        for (int q = 0; q < n; q++) for (int k = 0; k < 3; k++) {
            f.coord.set(k*n+q, (float)(s.fc[k*n+q] + d*u[k]));
            f.end1.set(k*n+q, (float)(s.f1[k*n+q] + d*u[k]));
            f.end2.set(k*n+q, (float)(s.f2[k*n+q] + d*u[k]));
        }
        SingleMotorMovieHarness.restore(f.uVec, s.fu); SingleMotorMovieHarness.restore(f.yVec, s.fy);
        SingleMotorMovieHarness.restore(f.zVec, s.fz);
    }
    // ==============================================================================================
    //  2026-10-25 (jba): SEARCH-AND-STROKE single-motor demonstration. One motor of the random-orientation lawn,
    //  NATURAL chemistry, natural capture. Filament Brownian OFF (it is still free to move under the bond force).
    //  Motor Brownian ON while it searches; at the capture step ALL motor Brownian goes OFF (matc[3] bit0 gates the
    //  bound motor on the capture step itself; matc[2] = 0 is then latched) so the power stroke is deterministic.
    //  Ends SEARCH_POST steps after the motor releases, or at the timeouts.
    // ==============================================================================================
    static int SEARCH_MAX = 200_000, SEARCH_BOUND_MAX = 200_000, SEARCH_POST = 4000, SEARCH_TRACE = 50, SEARCH_BOUND_STRIDE = 20;
    static void searchOnScene(java.util.function.Supplier<TwoBodyConverterMotor.Glide2D> build, int seed, double polTolDeg,
                              String outDir, String jsDir, int jsStride) {
        SEED = seed;
        new java.io.File(outDir).mkdirs();
        S s = wrapScene(build.get(), polTolDeg, true);
        var G = s.G; var e = s.e; int m = s.m, N = s.N;
        s.freeze = false; s.axialOnly = false;   // filament free (Brownian off), full rigid body
        ExplicitCompleteMatHarness.ONLY_MOTOR = m;   // the other motors are frozen and inert
        System.out.printf(Locale.US, "  OTHER MOTORS    = %d frozen and inert (not stepped, not bindable)%n", N - 1);
        double[] origin = { e.nodes.get(m), e.nodes.get(N + m), e.nodes.get(2*N + m) };
        double[] u = uOf(s);
        double[] bh = { e.frame.get(m), e.frame.get(N+m), e.frame.get(2*N+m) };
        System.out.printf(Locale.US, "  SEARCH MOTOR    = %d  stroke axis bhat = (%+.3f %+.3f %+.3f), angle to barbed %.1f deg, start nuc %s%n",
                m, bh[0], bh[1], bh[2], Math.toDegrees(Math.acos(dot(unitV(bh), u))),
                SingleMotorMovieHarness.nucName(G.mot.nucleotideState.get(m)));
        e.matc.set(2, 1); e.matc.set(3, e.matc.get(3) | 1);
        if (WeakBindSystem.ON) WeakBindSystem.ANG = new StringBuilder();
        System.out.println("  BROWNIAN        : motor ON while unbound, OFF from the capture step on; filament OFF");
        if (WeakBindSystem.ON) System.out.printf(Locale.US, "  WEAK BINDING ON : k_w %.2f pN/nm (strong F8 %.2f pN/nm) | k_off0 %.0f /s, Bell x %.2f nm, break > %.1f nm | k_ws %.0f /s, alpha %.2f (head + polarity) | contact %.1f nm%n", WeakBindSystem.K_W_PN_NM, e.sbP.get(24) * 1e9, WeakBindSystem.K_OFF0, WeakBindSystem.X_BELL_NM, WeakBindSystem.R_BREAK_NM, WeakBindSystem.K_WS, WeakBindSystem.ALPHA, WeakBindSystem.D_W_NM);
        int weakPrev = -1;
        if (ExplicitCompleteMatHarness.CAPTURE_LIT) System.out.printf(Locale.US, "  CAPTURE GATE    : LITERATURE (orientation charged at alpha = %.2f kT/rad^2, Steffen 2001; no capture polarity gate / dock energy)%n", ExplicitCompleteMatHarness.CAPTURE_ALPHA);
        StringBuilder tr = new StringBuilder("step\tt_ms\tphase\tnuc\tbound\tdFil_axial_nm\tfax_pN\ttheta_deg\tF8_site_gap_nm\t"
                + "neck_vs_n_deg\tneck_dot_barbed\tneck_vs_head_deg\tchi_deg\n");
        int[] frameNo = { 0 };
        double[] fil0 = filCentroid(s);
        int weakOn = 0, nWeakEp = 0; long weakDwell = 0;
        int nCand = 0, firstCand = -1; double minAx = 1e9; long[] gp = new long[GP_N];
        int capAt = -1, relAt = -1, strokeAt = -1, prevNuc = G.mot.nucleotideState.get(m);
        double[] snCap = null; double[] filCap = null, filStroke = null;
        for (int t = 0; ; t++) {
            step(s, t);
            geomNow(s);
            boolean bnd = G.mot.boundSeg.get(m) >= 0; int nu = G.mot.nucleotideState.get(m);
            double[] sn = bnd ? SiteNormalBindSystem.boundSiteNormal(G, s.nSeg, m, s.Ract, 1.0, 0.0) : null;
            if (bnd && capAt < 0) {
                capAt = t; e.matc.set(2, 0); snCap = sn; filCap = filCentroid(s);
                double[] ng = neckGeom(s, sn); double[] o = observe(s, sn);
                System.out.printf(Locale.US, "  CAPTURE  step %d (%.3f ms)  nuc %s  F8-site gap %.2f nm  fax %+.2f pN  theta %+.1f  chi %+.1f deg  neck vs n %.1f, neck.u %+.3f, neck vs head %.1f%n",
                        t, t*FIX_DT*1e3, SingleMotorMovieHarness.nucName(nu), gapNm(s, sn), o[1], o[3],
                        Math.toDegrees(e.chiHead.get(m)), ng[0], ng[1], ng[2]);
            }
            if (bnd && prevNuc == MotorStore.NUC_ADPPI && nu == MotorStore.NUC_ADP && strokeAt < 0) {
                strokeAt = t; filStroke = filCentroid(s);
                System.out.printf(Locale.US, "  PI RELEASE step %d (%.3f ms after capture)  filament moved %+.2f nm between capture and Pi release (pre-stroke swing check)%n", t, (t-capAt)*FIX_DT*1e3, dot(sub(filStroke, filCap), u)*1e3);
            }
            if (!bnd && capAt >= 0 && relAt < 0) {
                relAt = t;
                double[] ng = neckGeom(s, snCap);
                System.out.printf(Locale.US, "  RELEASE  step %d (bound %.3f ms)  nuc %s  filament moved %+.2f nm since capture%s  neck vs n %.1f, neck.u %+.3f, neck vs head %.1f%n",
                        t, (t-capAt)*FIX_DT*1e3, SingleMotorMovieHarness.nucName(nu), dot(sub(filCentroid(s), filCap), u)*1e3,
                        filStroke != null ? String.format(Locale.US, " (%+.2f nm since Pi release)", dot(sub(filCentroid(s), filStroke), u)*1e3) : "",
                        ng[0], ng[1], ng[2]);
            }
            String ph = capAt < 0 ? (WeakBindSystem.isWeak(m) ? "weak" : "search") : relAt < 0 ? (strokeAt < 0 ? "bound-pre" : "bound-post") : "released";
            if (jsDir != null && (t % ((capAt >= 0 && (relAt < 0 || t - relAt < 1000)) || WeakBindSystem.isWeak(m) ? SEARCH_BOUND_STRIDE : jsStride) == 0 || t == capAt || t == strokeAt || t == relAt)) jsFrame(s, jsDir, frameNo, origin, t);
            if (t % SEARCH_TRACE == 0 || t == capAt || t == strokeAt || t == relAt) {
                double[] snx = sn != null ? sn : snCap;
                double[] o = snx != null ? observe(s, snx) : null; double[] ng = snx != null ? neckGeom(s, snx) : null;
                tr.append(String.format(Locale.US, "%d\t%.4f\t%s\t%s\t%d\t%.4f\t%.4f\t%.2f\t%.4f\t%.2f\t%.4f\t%.2f\t%.2f%n", t, t*FIX_DT*1e3, ph,
                        SingleMotorMovieHarness.nucName(nu), bnd ? 1 : 0, dot(sub(filCentroid(s), fil0), u)*1e3,
                        o != null ? o[1] : Double.NaN, o != null ? o[3] : Double.NaN, sn != null ? gapNm(s, sn) : Double.NaN,
                        ng != null ? ng[0] : Double.NaN, ng != null ? ng[1] : Double.NaN, ng != null ? ng[2] : Double.NaN,
                        Math.toDegrees(e.chiHead.get(m))));
            }
            if (capAt < 0 && e.candInt.get(m) >= 0) { nCand++; if (firstCand < 0) firstCand = t; }
            if (capAt < 0 && G.mot.nucleotideState.get(m) == MotorStore.NUC_ADPPI) gateProbe(s, gp);
            if (capAt < 0) { double dz = minAxisNm(s); if (dz < minAx) minAx = dz; }
            if (WeakBindSystem.ON && capAt < 0) {
                int wn = WeakBindSystem.isWeak(m) ? WeakBindSystem.weakSeg[m] : -1;
                if (wn >= 0 && weakPrev < 0) { weakOn = t; nWeakEp++; }
                if (wn < 0 && weakPrev >= 0 && !bnd) { weakDwell += t - weakOn; }
                weakPrev = wn;
            }
            prevNuc = nu;
            if (capAt < 0 && t >= SEARCH_MAX) { System.out.printf(Locale.US, "  NO CAPTURE within %d steps (%.1f ms)%n", t, t*FIX_DT*1e3); break; }
            if (capAt >= 0 && relAt < 0 && t - capAt >= SEARCH_BOUND_MAX) { System.out.printf(Locale.US, "  STILL BOUND after %d steps%n", t - capAt); break; }
            if (relAt >= 0 && t - relAt >= SEARCH_POST) break;
        }
        System.out.printf(Locale.US, "  SEARCH GATE     : %d steps with a capture candidate (first at step %d); closest F8 approach to the filament axis %.2f nm%n", nCand, firstCand, minAx);
        System.out.println("  GATE BREAKDOWN (searching ADP.Pi steps; the real gate kernels re-run on scratch copies, one condition relaxed at a time)");
        for (int i = 0; i < GP_N; i++) System.out.printf(Locale.US, "    %-58s %8d%n", GP_NAME[i], gp[i]);
        if (WeakBindSystem.ON) System.out.println("  WEAK BINDING    : " + WeakBindSystem.stats());
        if (WeakBindSystem.ANG != null) write2(outDir, "weak_angles.tsv", "t\tm\tr_nm\tr8_nm\tfacing_deg\ttheta_deg\tmu_deg\tg3\tg4\n" + WeakBindSystem.ANG);
        if (WeakBindSystem.ON) System.out.printf(Locale.US, "  WEAK EPISODES   : %d before strong capture, mean dwell %.1f steps (%.3f ms)%n", nWeakEp, nWeakEp > 0 ? (double) weakDwell / nWeakEp : Double.NaN, nWeakEp > 0 ? weakDwell * FIX_DT * 1e3 / nWeakEp : Double.NaN);
        write2(outDir, "search_trace.tsv", tr.toString());
        if (jsDir != null) System.out.printf(Locale.US, "  -fix-3js: %d viewer frames -> %s%n", frameNo[0], jsDir);
    }
    static final String[] GP_NAME = {
        "steps searched (unbound, ADP.Pi)",
        "A  geometric candidate, all gates (g8 g0 g4 g1')",
        "A  ... orientation g1' relaxed",
        "A  ... preload g4 relaxed",
        "A  ... orientation + preload relaxed",
        "A  ... orientation + preload relaxed, distance 3 -> 6 nm",
        "B  commit passes (g3 g5 g9) on the all-gates candidate",
        "B  ... on the all-gates candidate, g3 (converter angle) relaxed",
        "B  ... on the all-gates candidate, g5 (energy budget) relaxed",
        "B  ... on the all-gates candidate, g9 (polarity) relaxed",
        "B  commit passes on the orient+preload-relaxed candidate",
        "B  ... on the all-gates candidate, g3 + g5 + g9 ALL relaxed" };
    static final int GP_N = GP_NAME.length;
    static uk.ac.manchester.tornado.api.types.arrays.DoubleArray dcopy(uk.ac.manchester.tornado.api.types.arrays.DoubleArray a) {
        var r = new uk.ac.manchester.tornado.api.types.arrays.DoubleArray(a.getSize()); for (int i = 0; i < a.getSize(); i++) r.set(i, a.get(i)); return r; }
    static uk.ac.manchester.tornado.api.types.arrays.IntArray icopy(uk.ac.manchester.tornado.api.types.arrays.IntArray a) {
        var r = new uk.ac.manchester.tornado.api.types.arrays.IntArray(a.getSize()); for (int i = 0; i < a.getSize(); i++) r.set(i, a.get(i)); return r; }
    static uk.ac.manchester.tornado.api.types.arrays.FloatArray fcopy(uk.ac.manchester.tornado.api.types.arrays.FloatArray a) {
        var r = new uk.ac.manchester.tornado.api.types.arrays.FloatArray(a.getSize()); for (int i = 0; i < a.getSize(); i++) r.set(i, a.get(i)); return r; }
    /** Gate A (siteGateA) on a modified sbP copy; returns {candInt, candArc, candAzim} scratch or null if no candidate for s.m. */
    static Object[] gateA(S s, java.util.function.Consumer<uk.ac.manchester.tornado.api.types.arrays.DoubleArray> mod) {
        var e = s.e; var f = s.G.fil; var sb = dcopy(e.sbP); mod.accept(sb);
        var ci = icopy(e.candInt); var ca = dcopy(e.candArc); var cz = dcopy(e.candAzim);
        ChiralSiteSystem.siteGateA(e.active, e.noBind, s.G.mot.boundSeg, s.G.mot.nucleotideState, e.outGeom,
                f.coord, f.uVec, f.yVec, f.segLength, e.segCumArc, sb, ci, ca, cz, e.exCounts);
        return ci.get(s.m) >= 0 ? new Object[]{ ci, ca, cz } : null;
    }
    static boolean gateB(S s, Object[] c, java.util.function.Consumer<uk.ac.manchester.tornado.api.types.arrays.DoubleArray> mod) {
        var e = s.e; var mot = s.G.mot; var sb = dcopy(e.sbP); mod.accept(sb);
        var bs = icopy(mot.boundSeg);
        ChiralSiteSystem.siteCommitB(bs, mot.nucleotideState, e.q, e.params, sb,
                (uk.ac.manchester.tornado.api.types.arrays.IntArray) c[0], (uk.ac.manchester.tornado.api.types.arrays.DoubleArray) c[1],
                (uk.ac.manchester.tornado.api.types.arrays.DoubleArray) c[2], fcopy(mot.bindArc), fcopy(mot.bindAzim),
                icopy(e.bindSite), icopy(e.prevBound), icopy(e.justBound), e.exCounts, s.G.fil.uVec);
        return bs.get(s.m) >= 0;
    }
    /** Prints the commit-stage quantities exactly as siteCommitB computes them, for one gate-A candidate. */
    static void commitValues(S s, Object[] c) {
        var e = s.e; int N = s.N, m = s.m; var sb = e.sbP; double DEG = 180.0 / Math.PI;
        double phi = e.q.get(m), psi = e.q.get(N + m), thetaS = e.q.get(2 * N + m);
        double kconv = e.params.get(6 * N + m), kT = sb.get(9), dth = (psi - phi) - thetaS;
        double thB = ((uk.ac.manchester.tornado.api.types.arrays.DoubleArray) c[1]).get(N + m);
        double eConv = 0.5 * kconv * dth * dth / kT, eOri = 0.5 * sb.get(29) * thB * thB / kT;
        int cs = ((uk.ac.manchester.tornado.api.types.arrays.IntArray) c[0]).get(m); var u = s.G.fil.uVec; int nS = s.nSeg;
        double pd = e.params.get(19 * N + m) * u.get(cs) + e.params.get(20 * N + m) * u.get(nS + cs) + e.params.get(21 * N + m) * u.get(2 * nS + cs);
        double mi = Math.acos(Math.max(-1, Math.min(1, pd))), eDock = sb.getSize() > 41 && sb.get(40) > 0.5 ? 0.5 * sb.get(41) * mi * mi / kT : 0;
        System.out.printf(Locale.US, "    COMMIT VALUES: thetaErr %.1f deg (tol %.1f) | energy conv %.1f + orient %.1f (thetaBind %.1f deg) + dock %.1f = %.1f kT (budget %.1f) | polarity %.1f deg (tol %.1f) | psi %.1f phi %.1f thetaS %.1f deg%n",
                Math.abs(dth) * DEG, sb.get(3), eConv, eOri, thB * DEG, eDock, eConv + eOri + eDock, sb.get(5),
                mi * DEG, sb.getSize() > 37 ? Math.acos(Math.max(-1, Math.min(1, sb.get(37)))) * DEG : Double.NaN,
                psi * DEG, phi * DEG, thetaS * DEG);
    }
    static void gateProbe(S s, long[] gp) {
        gp[0]++;
        Object[] a0 = gateA(s, sb -> {});
        Object[] a1 = gateA(s, sb -> sb.set(28, -2.0));
        Object[] a2 = gateA(s, sb -> sb.set(4, 1e9));
        Object[] a3 = gateA(s, sb -> { sb.set(28, -2.0); sb.set(4, 1e9); });
        Object[] a4 = gateA(s, sb -> { sb.set(28, -2.0); sb.set(4, 1e9); sb.set(0, 6.0); });
        if (a0 != null) gp[1]++; if (a1 != null) gp[2]++; if (a2 != null) gp[3]++; if (a3 != null) gp[4]++; if (a4 != null) gp[5]++;
        if (a0 != null) {
            if (gateB(s, a0, sb -> {})) gp[6]++;
            if (gateB(s, a0, sb -> sb.set(3, 1e9))) gp[7]++;
            if (gateB(s, a0, sb -> sb.set(5, 1e9))) gp[8]++;
            if (gateB(s, a0, sb -> { if (sb.getSize() > 37) sb.set(37, -2.0); })) gp[9]++;
        }
        if (a3 != null && gateB(s, a3, sb -> {})) gp[10]++;
        if (a0 != null && gateB(s, a0, sb -> { sb.set(3, 1e9); sb.set(5, 1e9); if (sb.getSize() > 37) sb.set(37, -2.0); })) gp[11]++;
        if (a0 != null) commitValues(s, a0);
    }
    static double minAxisNm(S s) {
        double[] f8 = g3(s, 6); return SingleMotorMovieHarness.axisDist(s.G, s.nSeg, f8[0], f8[1], f8[2]) * 1e3;
    }
    static double gapNm(S s, double[] sn) {
        double[] f8 = g3(s, 6); return Math.sqrt(dot(sub(f8, new double[]{ sn[3], sn[4], sn[5] }), sub(f8, new double[]{ sn[3], sn[4], sn[5] }))) * 1e3;
    }
    static void write2(String dir, String name, String body) {
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(dir, name), body); } catch (java.io.IOException ex) { throw new RuntimeException(ex); }
    }
    static int TRACE_STRIDE = 200; static boolean FIX_FREE_ONLY = false;
    /** Time trace of the FREE runs: filament axial displacement since the latch, axial bond force, converter angle. */
    static void traceRow(StringBuilder b, int j, String st, int k, S s, double[] sn, double[] filPre, double[] u) {
        double[] o = observe(s, sn);
        double d = dot(sub(filCentroid(s), filPre), u) * 1e3;
        double[] ng = neckGeom(s, sn);
        b.append(String.format(Locale.US, "%d\t%s\t%d\t%.4f\t%.4f\t%.4f\t%.2f\t%d\t%.2f\t%.4f\t%.2f\t%.2f%n", j, st, k, k * FIX_DT * 1e3, d, o[1], o[3],
                s.G.mot.boundSeg.get(s.m) >= 0 ? 1 : 0, ng[0], ng[1], ng[2], Math.toDegrees(s.e.chiHead.get(s.m))));
    }
    static float[] snapF(uk.ac.manchester.tornado.api.types.arrays.FloatArray a) { float[] r = new float[a.getSize()]; for (int i = 0; i < r.length; i++) r[i] = a.get(i); return r; }
    static void restoreF(uk.ac.manchester.tornado.api.types.arrays.FloatArray a, float[] r) { for (int i = 0; i < r.length; i++) a.set(i, r[i]); }
    /** One viewer frame (sim_viewer_boa.html array schema), coordinates shifted so the motor's S2 base is the origin. */
    static void jsFrame(S s, String dir, int[] frameNo, double[] o, int t) {
        var G = s.G; var e = s.e; var f = G.fil; int N = s.N, M = s.M, m = s.m, nSeg = s.nSeg;
        new java.io.File(dir).mkdirs();
        StringBuilder b = new StringBuilder(1 << 13);
        b.append(String.format(Locale.US, "{\"frame\":%d,\"t\":%.9g,", frameNo[0], t * FIX_DT));
        b.append("\"bounds\":{\"xDim\":0.12,\"yDim\":0.12,\"zDim\":0.08},");
        b.append("\"polarity\":{\"barbedEnd\":\"end2\",\"glideAxis\":\"pointed-leading\"},\"segments\":[");
        int sid = 0;
        for (int q = 0; q < nSeg; q++) {
            double[] a = new double[3], c = new double[3];
            for (int k = 0; k < 3; k++) { a[k] = f.end1.get(k*nSeg + q) - o[k]; c[k] = f.end2.get(k*nSeg + q) - o[k]; }
            b.append(SingleMotorMovieHarness.seg(sid++, a, c, s.Ract, false, 1.00, sid > 1));
        }
        for (int j = 0; j < M; j++) {
            double[] a = new double[3], c = new double[3];
            for (int k = 0; k < 3; k++) { a[k] = e.nodes.get((3*j+k)*N + m) - o[k]; c[k] = e.nodes.get((3*(j+1)+k)*N + m) - o[k]; }
            b.append(SingleMotorMovieHarness.seg(sid++, a, c, 0.0010, true, 0.55, true));
        }
        double[] sn = SiteNormalBindSystem.boundSiteNormal(G, nSeg, m, s.Ract, 1.0, 0.0);
        if (sn == null && WeakBindSystem.isWeak(m)) { double[] w = WeakBindSystem.site(e, m); sn = new double[]{ w[3], w[4], w[5], w[0], w[1], w[2] }; }
        double[] sp = null;
        if (sn != null) {
            sp = new double[]{ sn[3]-o[0], sn[4]-o[1], sn[5]-o[2] };
            double[] p1 = { sp[0]+0.0035*sn[0], sp[1]+0.0035*sn[1], sp[2]+0.0035*sn[2] };
            b.append(SingleMotorMovieHarness.seg(sid++, sp, p1, 0.0010, true, 0.00, true));
        }
        double[] P = new double[3], C = new double[3], H = new double[3], F8 = new double[3];
        for (int k = 0; k < 3; k++) {
            P[k] = e.nodes.get((3*M+k)*N + m) - o[k]; C[k] = e.outGeom.get(k*N+m) - o[k];
            H[k] = e.outGeom.get((3+k)*N+m) - o[k]; F8[k] = e.outGeom.get((6+k)*N+m) - o[k];
        }
        b.append("],\"myosins\":[");
        b.append(String.format(Locale.US,
                "{\"id\":0,\"rod\":{\"end1\":[0,0,0],\"end2\":[%.6f,%.6f,%.6f],\"r\":0.0008,\"invisible\":true},"
                + "\"lever\":{\"end1\":[%.6f,%.6f,%.6f],\"end2\":[%.6f,%.6f,%.6f],\"r\":0.0011},"
                + "\"motor\":{\"end1\":[%.6f,%.6f,%.6f],\"end2\":[%.6f,%.6f,%.6f],\"r\":0.0020,\"state\":\"%s\"}}",
                P[0], P[1], P[2], P[0], P[1], P[2], C[0], C[1], C[2], C[0], C[1], C[2], F8[0], F8[1], F8[2],   // head drawn converter pole -> F8 pole
                SingleMotorMovieHarness.nucName(G.mot.nucleotideState.get(m))));
        if (sp != null) b.append(String.format(Locale.US,
                ",{\"id\":1,\"rod\":{\"end1\":[%.6f,%.6f,%.6f],\"end2\":[%.6f,%.6f,%.6f],\"r\":0.0005,\"invisible\":true},"
                + "\"lever\":{\"end1\":[%.6f,%.6f,%.6f],\"end2\":[%.6f,%.6f,%.6f],\"r\":0.0006},"
                + "\"motor\":{\"end1\":[%.6f,%.6f,%.6f],\"end2\":[%.6f,%.6f,%.6f],\"r\":0.0012,\"state\":\"NONE\"}}",
                F8[0], F8[1], F8[2], F8[0], F8[1], F8[2], F8[0], F8[1], F8[2], sp[0], sp[1], sp[2],
                sp[0], sp[1], sp[2], sp[0], sp[1], sp[2]));
        b.append("]}");
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(dir, String.format(Locale.US, "frame_%06d.json", frameNo[0])), b.toString()); }
        catch (java.io.IOException ex) { throw new RuntimeException(ex); }
        frameNo[0]++;
    }
    /** One production step; the filament is held fixed when {@code freeze} (an infinitely stiff axial restraint). */
    static void step(S s, int t) {
        ExplicitCompleteMatHarness.stepGlidingCPU(s.e, t, SEED);
        if (s.axialOnly && !s.freeze) { slide(s); return; }
        if (!s.freeze) return;
        var f = s.G.fil;
        SingleMotorMovieHarness.restore(f.coord, s.fc); SingleMotorMovieHarness.restore(f.uVec, s.fu);
        SingleMotorMovieHarness.restore(f.yVec, s.fy);  SingleMotorMovieHarness.restore(f.zVec, s.fz);
        SingleMotorMovieHarness.restore(f.end1, s.f1);  SingleMotorMovieHarness.restore(f.end2, s.f2);
    }

    // ==============================================================================================
    //  PHASE 1 — the axial sign convention, traced through the executed code and verified numerically
    // ==============================================================================================
    static void phase1Sign() {
        hdr("PHASE 1 — AXIAL SIGN CONVENTION (traced, then verified numerically; nothing assumed from names)");
        S s = scene(0);
        var f = s.G.fil; int nSeg = s.nSeg;
        double[] u  = { f.uVec.get(0), f.uVec.get(nSeg), f.uVec.get(2*nSeg) };
        double[] bh = s.G.bhat, ph = s.G.phat;
        double[] e1 = { f.end1.get(0), f.end1.get(nSeg), f.end1.get(2*nSeg) };
        double[] e2 = { f.end2.get(0), f.end2.get(nSeg), f.end2.get(2*nSeg) };
        double dEnds = dot(sub(e2, e1), u);
        double dBU = dot(bh, u), dPU = dot(ph, u);

        System.out.printf(Locale.US, "%n  MEASURED on the built scene (segment 0):%n");
        System.out.printf(Locale.US, "    u_fil                = (%+.6f %+.6f %+.6f)%n", u[0], u[1], u[2]);
        System.out.printf(Locale.US, "    b_hat (barbed LABEL) = (%+.6f %+.6f %+.6f)   dot(b_hat, u_fil) = %+.6f%n", bh[0], bh[1], bh[2], dBU);
        System.out.printf(Locale.US, "    p_hat (pointed LABEL)= (%+.6f %+.6f %+.6f)   dot(p_hat, u_fil) = %+.6f%n", ph[0], ph[1], ph[2], dPU);
        System.out.printf(Locale.US, "    dot(end2 - end1, u_fil) = %+.6f um  ⇒ end2 lies at %s u_fil%n",
                dEnds, dEnds > 0 ? "+" : "-");
        // material arc direction: does bindArc increase toward +u ?
        double arcLo = 0.10 * f.segLength.get(0), arcHi = 0.90 * f.segLength.get(0);
        double[] pLo = SingleMotorMovieHarness.sitePosNormal(s.G, nSeg, 0, arcLo, 0.0, s.Ract);
        double[] pHi = SingleMotorMovieHarness.sitePosNormal(s.G, nSeg, 0, arcHi, 0.0, s.Ract);
        double dArc = dot(sub(new double[]{pHi[0],pHi[1],pHi[2]}, new double[]{pLo[0],pLo[1],pLo[2]}), u);
        System.out.printf(Locale.US, "    d(site position)/d(bindArc) . u_fil = %+.6f um  ⇒ bindArc increases toward %s u_fil%n",
                dArc, dArc > 0 ? "+" : "-");

        boolean barbedPlus = dBU > 0 && dEnds > 0;
        System.out.printf(Locale.US, "%n  ================== THE CONVENTION ==================%n");
        System.out.printf(Locale.US, "    +u_fil = %s%n", barbedPlus ? "BARBED   (= end2)" : "POINTED  (= end1)");
        System.out.printf(Locale.US, "    -u_fil = %s%n", barbedPlus ? "POINTED  (= end1)" : "BARBED   (= end2)");
        System.out.println("  ====================================================");
        System.out.println("\n  SOURCE (TwoBodyConverterMotor:676-680, the Exp-3B audited convention, verbatim):");
        System.out.println("    \"barbed = end2 = +uVec; pointed = end1 = -uVec; material coord (bindArc) increases");
        System.out.println("     pointed->barbed; the working stroke sweeps the F8 point toward the POINTED direction");
        System.out.println("     (-b_hat) => force on actin toward pointed, free filament glides pointed-first,");
        System.out.println("     motor reaction toward barbed.\"");

        System.out.printf(Locale.US, "%n  ================== WHAT THAT IMPLIES, SIGN BY SIGN ==================%n");
        System.out.println("    MOTOR force (on the head, bondData[m*13 + 0..2]) during a productive stroke:");
        System.out.printf(Locale.US, "        toward BARBED  ⇒  dot(F_motor, u_fil) > 0%n");
        System.out.println("    FILAMENT reaction (on actin, bondData[m*13 + 6..8] — the segGather channel):");
        System.out.printf(Locale.US, "        toward POINTED ⇒  dot(F_filament, u_fil) < 0   <-- THE POLARITY OBSERVABLE (`fax`)%n");
        System.out.println("    FREE-FILAMENT glide, measured by ChiralSiteHarness as centroidDot(f, b_hat):");
        System.out.printf(Locale.US, "        pointed-first  ⇒  glide < 0%n");
        System.out.println("\n    So: EXPECTED PRODUCTIVE SIGNS are  fax < 0  and  glide < 0.");

        System.out.printf(Locale.US, "%n  WARNING — `nPull`/`nDrag` are NOT a polarity observable.%n");
        System.out.println("    ChiralSiteHarness:2290  `double power = fax * vFil;`  classifies each bound sample by");
        System.out.println("    MECHANICAL POWER against the INSTANTANEOUS FILAMENT VELOCITY, not against the pointed");
        System.out.println("    direction. When vFil is dominated by Brownian motion its sign is ~random, so nPull/nDrag");
        System.out.println("    tends to 50/50 BY CONSTRUCTION whatever the sign of fax. The polarity observable is the");
        System.out.println("    signed mean `r.fax` (ChiralSiteHarness:2388), which the previous smoke test did not print.");

        write("phase1_sign_convention.txt", String.format(Locale.US,
                "u_fil\t%.9f\t%.9f\t%.9f%nbhat_dot_u\t%.9f%nphat_dot_u\t%.9f%nend2_minus_end1_dot_u\t%.9f%n"
                + "arc_increases_dot_u\t%.9f%nplus_u_is\t%s%nminus_u_is\t%s%n"
                + "expected_fax_sign\tNEGATIVE (force on filament toward pointed)%n"
                + "expected_glide_sign\tNEGATIVE (pointed-first)%n"
                + "expected_F_motor_axial_sign\tPOSITIVE (reaction toward barbed)%n",
                u[0], u[1], u[2], dBU, dPU, dEnds, dArc,
                barbedPlus ? "BARBED" : "POINTED", barbedPlus ? "POINTED" : "BARBED"));
    }

    // ==============================================================================================
    //  PHASE 3/4/10/11/12 — deterministic canonical bound pose, real stroke, every helical azimuth
    // ==============================================================================================
    static void phase3Fixture() {
        hdr("PHASE 3/4/10 — DETERMINISTIC CANONICAL STROKE FIXTURE across the helical circle");
        System.out.printf(Locale.US, "  Motor Brownian OFF, %d relaxation steps per equilibrium, real ADP.Pi->ADP transition%n", RELAX);
        System.out.println("  (the nucleotide state is set and matCock + the implicit solver do the rest — F8 is NEVER");
        System.out.println("   translated by hand). Two restraints per azimuth:");
        System.out.println("     ISOMETRIC  filament FROZEN (infinitely stiff axial restraint) -> measures FORCE polarity");
        System.out.println("     FREE       filament mobile, all Brownian off                  -> measures DISPLACEMENT polarity");

        StringBuilder tsv = new StringBuilder("mode\tsite\tazim_deg\tsF8_pre_nm\tsF8_post_nm\tdsF8_nm\t"
                + "fax_pre_pN\tfax_post_pN\tdfax_pN\tphi_pre\tphi_post\ttheta_pre\ttheta_post\ts2_pre_nm\ts2_post_nm\t"
                + "thetabind_pre\tthetabind_post\tdFil_axial_nm\twork_aJ\tdFil_stroke_nm\n");
        for (int mode = 0; mode < 2; mode++) {
            boolean freeze = mode == 0;
            System.out.printf(Locale.US, "%n  ---- %s restraint ----%n", freeze ? "ISOMETRIC (filament frozen)" : "FREE (filament mobile)");
            System.out.printf(Locale.US, "    %5s %9s %11s %11s %11s %11s %11s %11s %10s%n",
                    "site", "azim", "sF8_pre", "sF8_post", "d sF8", "fax_pre", "fax_post", "d fax", "dFil_ax");
            System.out.printf(Locale.US, "    %5s %9s %11s %11s %11s %11s %11s %11s %10s%n",
                    "", "deg", "nm", "nm", "nm", "pN", "pN", "pN", "nm");
            List<double[]> rows = new ArrayList<>();
            for (int j = 0; j < AZIM; j++) {
                S s = scene(0); s.freeze = freeze;
                // PHASE 4 covariance done CORRECTLY: rotate the filament's MATERIAL FRAME about its own axis,
                // which rotates every site normal while leaving the site's AXIAL coordinate untouched. Walking
                // along the every4 lattice instead would advance the site 10.8 nm axially per step and measure
                // an increasingly stretched bond rather than the same configuration at a new azimuth.
                double phiRot = 2*Math.PI*j/AZIM;
                rotateFilamentFrame(s, phiRot);
                int seg = latchNearestCanonical(s);
                if (seg < 0) continue;
                double[] sn = SiteNormalBindSystem.boundSiteNormal(s.G, s.nSeg, s.m, s.Ract, 1.0, 0.0);
                if (sn == null) continue;
                double[] pc = SiteNormalBindHarness.poseForTarget(s.e, s.m, new double[]{ -sn[0], -sn[1], -sn[2] });
                if (pc == null) continue;
                s.e.q.set(s.N + s.m, pc[0]); s.e.chiHead.set(s.m, pc[1]);
                // PRE-STROKE: the real ADP.Pi state; matCock sets thetaS = PRESTROKE_THETAS inside the step
                s.G.mot.nucleotideState.set(s.m, MotorStore.NUC_ADPPI);
                s.e.noBind.set(s.m, 1);                     // hold the latched bond; no re-capture during relaxation
                double[] filPre = filCentroid(s);
                for (int t = 0; t < RELAX; t++) { s.G.mot.nucleotideState.set(s.m, MotorStore.NUC_ADPPI); step(s, t); }
                double[] pre = observe(s, sn);
                double[] filPreEq = filCentroid(s);   // 2026-10-24: stroke-only displacement (pre_eq -> post_eq)
                // POWER STROKE: the production state change, nothing else
                for (int t = 0; t < RELAX; t++) { s.G.mot.nucleotideState.set(s.m, MotorStore.NUC_ADP); step(s, RELAX + t); }
                double[] post = observe(s, sn);
                double[] filPost = filCentroid(s);
                double dFil = dot(sub(filPost, filPre), uOf(s)) * 1e3;
                double dFilStroke = dot(sub(filPost, filPreEq), uOf(s)) * 1e3;
                double dsF8 = post[0] - pre[0];
                double work = -0.5 * (pre[1] + post[1]) * 1e-12 * dFil * 1e-9 * 1e18;   // aJ, F_fil . displacement
                // PHYSICAL azimuth of the (rotated) site normal about the filament axis — NOT the stored
                // bindAzim, which is the lattice phase and does not change when the material frame rotates.
                double az = physAzim(s, sn);
                System.out.printf(Locale.US, "    %5d %9.2f %11.4f %11.4f %11.4f %11.5f %11.5f %11.5f %10.4f%n",
                        s.e.bindSite.get(s.m), az, pre[0], post[0], dsF8, pre[1], post[1], post[1]-pre[1], dFil);
                tsv.append(String.format(Locale.US, "%s\t%d\t%.3f\t%.5f\t%.5f\t%.5f\t%.6f\t%.6f\t%.6f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.5f\t%.5f\t%.5f%n",
                        freeze ? "isometric" : "free", s.e.bindSite.get(s.m), az, pre[0], post[0], dsF8,
                        pre[1], post[1], post[1]-pre[1], pre[2], post[2], pre[3], post[3], pre[4], post[4],
                        pre[5], post[5], dFil, work, dFilStroke));
                rows.add(new double[]{ dsF8, pre[1], post[1], post[1]-pre[1], dFil });
            }
            summarise(freeze ? "ISOMETRIC" : "FREE", rows);
        }
        write("phase3_fixture.tsv", tsv.toString());
    }
    static void summarise(String tag, List<double[]> rows) {
        if (rows.isEmpty()) { System.out.println("    (no reachable azimuth)"); return; }
        int n = rows.size();
        double[] ds = new double[n], fp = new double[n], df = new double[n], dfil = new double[n];
        for (int i = 0; i < n; i++) { ds[i] = rows.get(i)[0]; fp[i] = rows.get(i)[2]; df[i] = rows.get(i)[3]; dfil[i] = rows.get(i)[4]; }
        System.out.printf(Locale.US, "%n    PHASE 4 — AZIMUTHAL COVARIANCE (%s), n = %d azimuths:%n", tag, n);
        rep("d sF8 (axial F8 displacement)", ds, "nm");
        rep("fax_post (axial force ON FILAMENT after the stroke)", fp, "pN");
        rep("d fax (force change through the stroke)", df, "pN");
        rep("d filament axial displacement", dfil, "nm");
        int sameDs = signAgree(ds), sameF = signAgree(fp);
        System.out.printf(Locale.US, "    SIGN CONSISTENCY: d sF8 %d/%d share a sign ; fax_post %d/%d share a sign%n",
                sameDs, n, sameF, n);
        System.out.printf(Locale.US, "    ⇒ axial stroke polarity is %s across the helical circle%n",
                sameDs == n ? "INVARIANT" : "NOT invariant");
        System.out.printf(Locale.US, "    ⇒ force on the filament after the stroke is %s (expected NEGATIVE = toward pointed)%n",
                sameF == n ? (fp[0] < 0 ? "consistently NEGATIVE — CORRECT polarity" : "consistently POSITIVE — REVERSED polarity")
                           : "MIXED in sign");
    }
    static void rep(String name, double[] v, String unit) {
        double mn = 1e30, mx = -1e30, s = 0;
        for (double x : v) { mn = Math.min(mn, x); mx = Math.max(mx, x); s += x; }
        System.out.printf(Locale.US, "      %-52s mean %+10.5f  range [%+.5f, %+.5f] %s%n", name, s/v.length, mn, mx, unit);
    }
    static int signAgree(double[] v) {
        int pos = 0, neg = 0;
        for (double x : v) { if (x > 0) pos++; else if (x < 0) neg++; }
        return Math.max(pos, neg);
    }
    /** {sF8_nm, fax_pN, phi_deg, theta_deg, s2ext_nm, thetaBind_deg} at the current state. */
    static double[] observe(S s, double[] sn) {
        int N = s.N, m = s.m;
        double[] u = uOf(s);
        double[] xf = { s.e.outGeom.get(6*N+m), s.e.outGeom.get(7*N+m), s.e.outGeom.get(8*N+m) };
        double sF8 = dot(sub(xf, new double[]{ sn[3], sn[4], sn[5] }), u) * 1e3;
        int d = m*13; int bs = s.G.mot.boundSeg.get(m);
        double fax = 0;
        if (bs >= 0) fax = (s.G.bondData.get(d+6)*s.G.fil.uVec.get(bs)
                          + s.G.bondData.get(d+7)*s.G.fil.uVec.get(s.nSeg+bs)
                          + s.G.bondData.get(d+8)*s.G.fil.uVec.get(2*s.nSeg+bs)) * 1e12;
        double phi = Math.toDegrees(s.e.q.get(m)), psi = Math.toDegrees(s.e.q.get(N+m));
        double s2 = 0;
        for (int j = 0; j < s.M; j++) {
            double dx = s.e.nodes.get((3*(j+1))*N+m) - s.e.nodes.get((3*j)*N+m);
            double dy = s.e.nodes.get((3*(j+1)+1)*N+m) - s.e.nodes.get((3*j+1)*N+m);
            double dz = s.e.nodes.get((3*(j+1)+2)*N+m) - s.e.nodes.get((3*j+2)*N+m);
            s2 += Math.sqrt(dx*dx+dy*dy+dz*dz);
        }
        double[] xh = { s.e.outGeom.get(9*N+m), s.e.outGeom.get(10*N+m), s.e.outGeom.get(11*N+m) };
        double[] snNow = SiteNormalBindSystem.boundSiteNormal(s.G, s.nSeg, m, s.Ract, 1.0, 0.0);
        double tb = snNow != null ? SiteNormalBindSystem.thetaBindDeg(xh, snNow) : Double.NaN;
        return new double[]{ sF8, fax, phi, psi - phi, s2*1e3, tb };
    }
    /** Signed azimuth of n_site about u_fil, referenced to the lab z-axis projected perpendicular to u. */
    static double physAzim(S s, double[] sn) {
        double[] u = uOf(s);
        double[] z = { 0, 0, 1 };
        double du = dot(z, u);
        double[] pr = { z[0]-du*u[0], z[1]-du*u[1], z[2]-du*u[2] };
        double pl = Math.sqrt(dot(pr, pr)); if (pl < 1e-12) return Double.NaN;
        pr[0] /= pl; pr[1] /= pl; pr[2] /= pl;
        double[] q = { u[1]*pr[2]-u[2]*pr[1], u[2]*pr[0]-u[0]*pr[2], u[0]*pr[1]-u[1]*pr[0] };
        return Math.toDegrees(Math.atan2(sn[0]*q[0]+sn[1]*q[1]+sn[2]*q[2], sn[0]*pr[0]+sn[1]*pr[1]+sn[2]*pr[2]));
    }
    static double[] uOf(S s) {
        int bs = Math.max(0, s.G.mot.boundSeg.get(s.m));
        return new double[]{ s.G.fil.uVec.get(bs), s.G.fil.uVec.get(s.nSeg+bs), s.G.fil.uVec.get(2*s.nSeg+bs) };
    }
    static double[] filCentroid(S s) {
        double x = 0, y = 0, z = 0;
        for (int q = 0; q < s.nSeg; q++) { x += s.G.fil.coord.get(q); y += s.G.fil.coord.get(s.nSeg+q); z += s.G.fil.coord.get(2*s.nSeg+q); }
        return new double[]{ x/s.nSeg, y/s.nSeg, z/s.nSeg };
    }
    /**
     * Rotate the filament's rolling material frame (yVec, zVec) about its own axis by {@code phi}. Every site
     * normal rotates with it — {@code n_site = cos(bindAzim) segY + sin(bindAzim) segZ} — while coord, uVec,
     * end1 and end2, hence every site's AXIAL coordinate, are untouched. This is the covariance operation
     * Phase 4 asks for.
     */
    static void rotateFilamentFrame(S s, double phi) {
        var f = s.G.fil; int nSeg = s.nSeg;
        for (int q = 0; q < nSeg; q++) {
            double ux = f.uVec.get(q), uy = f.uVec.get(nSeg+q), uz = f.uVec.get(2*nSeg+q);
            double[] y = { f.yVec.get(q), f.yVec.get(nSeg+q), f.yVec.get(2*nSeg+q) };
            double c = Math.cos(phi), sn2 = Math.sin(phi);
            double kx = uy*y[2]-uz*y[1], ky = uz*y[0]-ux*y[2], kz = ux*y[1]-uy*y[0];
            double d = ux*y[0]+uy*y[1]+uz*y[2];
            double ry = 0;
            double nyx = y[0]*c + kx*sn2 + ux*d*(1-c);
            double nyy = y[1]*c + ky*sn2 + uy*d*(1-c);
            double nyz = y[2]*c + kz*sn2 + uz*d*(1-c);
            f.yVec.set(q, (float) nyx); f.yVec.set(nSeg+q, (float) nyy); f.yVec.set(2*nSeg+q, (float) nyz);
            f.zVec.set(q, (float)(uy*nyz - uz*nyy)); f.zVec.set(nSeg+q, (float)(uz*nyx - ux*nyz));
            f.zVec.set(2*nSeg+q, (float)(ux*nyy - uy*nyx));
        }
        s.fy = SingleMotorMovieHarness.snap(f.yVec); s.fz = SingleMotorMovieHarness.snap(f.zVec);
    }
    /**
     * Latch to the site NEAREST the head's F8 point in the CANONICAL pose — iterated, because setting the
     * canonical orientation moves xF8, which can change which site is nearest. Two passes converge. This
     * keeps the pre-stroke bond at a realistic (few-nm) extension instead of the tens of nm a fixed
     * lattice-walk produces.
     */
    static int latchNearestCanonical(S s) {
        int seg = -1;
        for (int pass = 0; pass < 3; pass++) {
            seg = latch(s, 0);
            if (seg < 0) return -1;
            double[] sn = SiteNormalBindSystem.boundSiteNormal(s.G, s.nSeg, s.m, s.Ract, 1.0, 0.0);
            if (sn == null) return -1;
            double[] pc = SiteNormalBindHarness.poseForTarget(s.e, s.m, new double[]{ -sn[0], -sn[1], -sn[2] });
            if (pc == null) return -1;
            s.e.q.set(s.N + s.m, pc[0]); s.e.chiHead.set(s.m, pc[1]);
        }
        return seg;
    }
    /** Latch the motor to the (nearest + j)-th every4 site in the canonical bound configuration. */
    static int latch(S s, int j) {
        TwoBodyBeamAnalyticGpu.matBeamGeomTilt(s.e.nodes, s.e.frame, s.e.params, s.e.q, s.e.exCounts, s.e.outGeom, s.e.convF, s.e.chiHead);
        SiteNormalBindSystem.headAxisStep(s.e.frame, s.e.params, s.e.q, s.e.chiHead, s.e.convF, s.e.outGeom, s.e.exCounts);
        double[] xf = { s.e.outGeom.get(6*s.N+s.m), s.e.outGeom.get(7*s.N+s.m), s.e.outGeom.get(8*s.N+s.m) };
        double[] ns = SingleMotorMovieHarness.nearestSite(s.G, s.nSeg, xf, s.rise, s.stepPhase);
        int seg = (int) ns[0]; if (seg < 0) return -1;
        int k = (int) ns[1] + j;
        double cum = s.e.segCumArc.get(seg), L = s.G.fil.segLength.get(seg);
        double la = k * s.rise - cum;
        if (la < 0 || la > L) return -1;
        double tw = k * s.stepPhase, ph = tw - 2*Math.PI*Math.floor(tw/(2*Math.PI));
        s.G.mot.boundSeg.set(s.m, seg);
        s.G.mot.bindArc.set(s.m, (float) la);
        s.G.mot.bindAzim.set(s.m, (float) ph);
        s.e.bindSite.set(s.m, k);
        s.e.params.set(7*s.N + s.m, s.e.gateP.get(0));
        return seg;
    }

    // ==============================================================================================
    //  PHASE 5-9 — natural bound events, stroke-aligned and decomposed
    // ==============================================================================================
    static void phase5Natural() {
        hdr("PHASE 5/6/7/8/9 — NATURAL BOUND EVENTS: stroke alignment and the pull/resist decomposition");
        S s = scene(1); s.freeze = true;
        int N = s.N, nSeg = s.nSeg;
        for (int m = 0; m < N; m++) { s.G.noBind[m] = false; s.e.noBind.set(m, 0); }   // all motors bindable
        double[] u = { s.G.fil.uVec.get(0), s.G.fil.uVec.get(nSeg), s.G.fil.uVec.get(2*nSeg) };
        StringBuilder log = new StringBuilder("event\tmotor\tstep\trel\tnuc\tphase\tazim_deg\tsF8_nm\tfax_pN\t"
                + "theta_bind\tphi\tpsi\tchi\tanchor_site_nm\n");
        StringBuilder ev = new StringBuilder("event\tmotor\tcapStep\tsite\tazim_deg\tanchor_site_nm\t"
                + "fax_at_capture_pN\tsF8_at_capture_nm\tstroked\tdsF8_nm\tdfax_pN\tfax_mean_pre_pN\tfax_mean_post_pN\t"
                + "lifetime\tclass\n");
        final class Ev { int m, cap, site; double azim, anchor, fax0, s0; boolean stroked;
                         double sPre, sPost, fPre, fPost; int nPre, nPost, strokeAt = -1; int life = -1; }
        List<Ev> live = new ArrayList<>(), done = new ArrayList<>();
        boolean[] was = new boolean[N]; int[] prevNuc = new int[N];
        for (int m = 0; m < N; m++) { was[m] = s.G.mot.boundSeg.get(m) >= 0; prevNuc[m] = s.G.mot.nucleotideState.get(m); }
        for (int t = 0; t < NAT_STEPS; t++) {
            step(s, t);
            for (int m = 0; m < N; m++) {
                boolean now = s.G.mot.boundSeg.get(m) >= 0;
                int nu = s.G.mot.nucleotideState.get(m);
                if (!was[m] && now) {
                    Ev e2 = new Ev(); e2.m = m; e2.cap = t; e2.site = s.e.bindSite.get(m);
                    double[] sn = SiteNormalBindSystem.boundSiteNormal(s.G, nSeg, m, s.Ract, 1.0, 0.0);
                    e2.azim = Math.toDegrees(s.G.mot.bindAzim.get(m));
                    e2.anchor = sn != null ? anchorSite(s, m, sn, u) : Double.NaN;
                    e2.fax0 = faxOf(s, m); e2.s0 = sn != null ? sF8Of(s, m, sn, u) : Double.NaN;
                    live.add(e2);
                }
                if (now) for (Ev e2 : live) if (e2.m == m) {
                    double fx = faxOf(s, m);
                    if (prevNuc[m] == MotorStore.NUC_ADPPI && nu == MotorStore.NUC_ADP && !e2.stroked) {
                        e2.stroked = true; e2.strokeAt = t;
                        double[] sn = SiteNormalBindSystem.boundSiteNormal(s.G, nSeg, m, s.Ract, 1.0, 0.0);
                        e2.sPre = sn != null ? sF8Of(s, m, sn, u) : Double.NaN;
                    }
                    if (e2.stroked) { e2.fPost += fx; e2.nPost++; } else { e2.fPre += fx; e2.nPre++; }
                    if (e2.stroked && t == e2.strokeAt + 40) {
                        double[] sn = SiteNormalBindSystem.boundSiteNormal(s.G, nSeg, m, s.Ract, 1.0, 0.0);
                        e2.sPost = sn != null ? sF8Of(s, m, sn, u) : Double.NaN;
                    }
                    if (done.size() + live.size() <= 30 && (t - e2.cap) % 5 == 0) {
                        double[] sn = SiteNormalBindSystem.boundSiteNormal(s.G, nSeg, m, s.Ract, 1.0, 0.0);
                        double[] xh = { s.e.outGeom.get(9*N+m), s.e.outGeom.get(10*N+m), s.e.outGeom.get(11*N+m) };
                        log.append(String.format(Locale.US, "%d\t%d\t%d\t%d\t%s\t%s\t%.2f\t%.4f\t%.6f\t%.3f\t%.3f\t%.3f\t%.3f\t%.4f%n",
                                done.size()+live.indexOf(e2), m, t, t-e2.cap, SingleMotorMovieHarness.nucName(nu),
                                e2.stroked ? "post" : "pre", e2.azim, sn != null ? sF8Of(s,m,sn,u) : Double.NaN, fx,
                                sn != null ? SiteNormalBindSystem.thetaBindDeg(xh, sn) : Double.NaN,
                                Math.toDegrees(s.e.q.get(m)), Math.toDegrees(s.e.q.get(N+m)),
                                Math.toDegrees(s.e.chiHead.get(m)), e2.anchor));
                    }
                }
                if (was[m] && !now) for (java.util.Iterator<Ev> it = live.iterator(); it.hasNext(); ) {
                    Ev e2 = it.next(); if (e2.m != m) continue;
                    e2.life = t - e2.cap; done.add(e2); it.remove();
                }
                was[m] = now; prevNuc[m] = nu;
            }
        }
        for (Ev e2 : live) { e2.life = -1; done.add(e2); }
        // ---- report -----------------------------------------------------------------------------
        int nProd = 0, nResist = 0, nRev = 0, nLoad = 0, nNo = 0;
        int bornPull = 0, bornResist = 0;
        double sAnchorPull = 0, sAnchorResist = 0;
        System.out.printf(Locale.US, "%n  %d natural bound episodes over %d steps x %d motors%n", done.size(), NAT_STEPS, N);
        for (Ev e2 : done) {
            double fPre = e2.nPre > 0 ? e2.fPre/e2.nPre : Double.NaN;
            double fPost = e2.nPost > 0 ? e2.fPost/e2.nPost : Double.NaN;
            double dsF8 = e2.stroked ? (e2.sPost - e2.sPre) : Double.NaN;
            String cls;
            if (!e2.stroked) { cls = "NO-STROKE"; nNo++; }
            else if (fPost < 0 && dsF8 < 0) { cls = "PRODUCTIVE"; nProd++; }
            else if (fPost > 0 && dsF8 < 0) { cls = "LOAD-REVERSED"; nLoad++; }
            else if (dsF8 > 0) { cls = "POLARITY-REVERSED"; nRev++; }
            else { cls = "RESISTING"; nResist++; }
            if (e2.fax0 < 0) { bornPull++; sAnchorPull += e2.anchor; } else { bornResist++; sAnchorResist += e2.anchor; }
            ev.append(String.format(Locale.US, "%d\t%d\t%d\t%d\t%.2f\t%.4f\t%.6f\t%.4f\t%b\t%.4f\t%.6f\t%.6f\t%.6f\t%d\t%s%n",
                    done.indexOf(e2), e2.m, e2.cap, e2.site, e2.azim, e2.anchor, e2.fax0, e2.s0, e2.stroked,
                    dsF8, fPost - fPre, fPre, fPost, e2.life, cls));
        }
        System.out.printf(Locale.US, "%n  PHASE 6 — stroke-aligned classification:%n");
        System.out.printf(Locale.US, "    PRODUCTIVE %d | RESISTING %d | POLARITY-REVERSED %d | LOAD-REVERSED %d | NO-STROKE %d%n",
                nProd, nResist, nRev, nLoad, nNo);
        System.out.printf(Locale.US, "%n  PHASE 9 — CAPTURE PRELOAD SIGN (fax at the capture step):%n");
        System.out.printf(Locale.US, "    born PULLING (fax < 0) %d | born RESISTING (fax > 0) %d  ⇒ %.1f %% born pulling%n",
                bornPull, bornResist, 100.0*bornPull/Math.max(1, bornPull+bornResist));
        System.out.printf(Locale.US, "%n  PHASE 8 — ANCHOR-SITE AXIAL GEOMETRY dot(x_anchor - x_site, u_fil):%n");
        System.out.printf(Locale.US, "    mean for born-PULLING  %+.4f nm (n=%d)%n", bornPull > 0 ? sAnchorPull/bornPull : Double.NaN, bornPull);
        System.out.printf(Locale.US, "    mean for born-RESISTING %+.4f nm (n=%d)%n", bornResist > 0 ? sAnchorResist/bornResist : Double.NaN, bornResist);
        write("phase5_events.tsv", ev.toString());
        write("phase5_boundlog.tsv", log.toString());
    }
    static double faxOf(S s, int m) {
        int bs = s.G.mot.boundSeg.get(m); if (bs < 0) return Double.NaN;
        int d = m*13;
        return (s.G.bondData.get(d+6)*s.G.fil.uVec.get(bs)
              + s.G.bondData.get(d+7)*s.G.fil.uVec.get(s.nSeg+bs)
              + s.G.bondData.get(d+8)*s.G.fil.uVec.get(2*s.nSeg+bs)) * 1e12;
    }
    static double sF8Of(S s, int m, double[] sn, double[] u) {
        int N = s.N;
        double[] xf = { s.e.outGeom.get(6*N+m), s.e.outGeom.get(7*N+m), s.e.outGeom.get(8*N+m) };
        return dot(sub(xf, new double[]{ sn[3], sn[4], sn[5] }), u) * 1e3;
    }
    /** dot(x_anchor - x_site, u_fil) in nm; the anchor is the S2 clamp (beam node 0 = g4E). */
    static double anchorSite(S s, int m, double[] sn, double[] u) {
        int N = s.N;
        double[] a = { s.e.nodes.get(m), s.e.nodes.get(N+m), s.e.nodes.get(2*N+m) };
        return dot(sub(a, new double[]{ sn[3], sn[4], sn[5] }), u) * 1e3;
    }

    // ==============================================================================================
    static double[] sub(double[] a, double[] b) { return new double[]{ a[0]-b[0], a[1]-b[1], a[2]-b[2] }; }
    static double dot(double[] a, double[] b) { return a[0]*b[0]+a[1]*b[1]+a[2]*b[2]; }
    static void hdr(String t) { System.out.println("\n" + "=".repeat(100) + "\n=== " + t + "\n" + "=".repeat(100)); }
    static void write(String rel, String s) {
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(OUT, rel), s); }
        catch (java.io.IOException e) { throw new RuntimeException(e); } }
}
