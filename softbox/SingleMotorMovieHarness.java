package softbox;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * SINGLE-MOTOR FINE-TIME MECHANICAL SANITY MOVIE — a VISUAL model-integrity gate.
 *
 * <p>ONE anchored motor, ONE actin filament, the sparse {@code every4} filament-global helical lattice, and
 * the REAL site-aware capture path. The harness runs until the motor binds a site <b>naturally</b>, then
 * writes an event-centred window at <b>one recorded frame per integration timestep</b>, plus a per-frame
 * numeric trace. Nothing is tuned, no gate is modified, no physics is added.
 *
 * <h3>The production step is reused VERBATIM</h3>
 * Each step is {@link ExplicitCompleteMatHarness#stepGlidingCPU} — the same kernel sequence, the same gates,
 * the same chemistry. The only fixture is applied <b>after</b> the step: the filament pose is restored from a
 * snapshot, i.e. <b>the filament is frozen</b> so head motion is visually interpretable. The motor sees the
 * real filament, the real sites and the real bond; only the filament's own response is suppressed. No step is
 * re-implemented here, so there is no step-order to drift out of sync with production.
 *
 * <h3>Scene construction (stated, because it is a choice)</h3>
 * <ul>
 *   <li>The lawn is built by the production {@code buildS2Mat} and then <b>exactly one motor is left able to
 *       bind</b> ({@code noBind = true} for every other motor). Motors do not interact — they couple only
 *       through the filament, which is frozen — so the reduced lawn cannot influence the capture.</li>
 *   <li>The bindable motor is chosen by the <b>smallest lateral distance from the filament axis</b>, i.e. "the
 *       motor under the filament". This is scene construction, not outcome selection: it is fixed before the
 *       run and no site, seed or time window is chosen afterwards.</li>
 *   <li>The filament is frozen at its <b>as-built centreline height</b>. The +11 nm emergent height of the
 *       z-slab study is a 1200-motor collective result and is not reproducible in a one-motor scene; the slab
 *       is configured but inert while the filament is frozen. Stated, not hidden.</li>
 * </ul>
 *
 * <h3>Arms</h3>
 * <b>A ({@code off})</b> the current validated two-body motor: {@code matS2SolveStep}, no chi, base-frame
 * {@code k_bind}. <b>B ({@code on})</b> the 3-D-head candidate: {@code matS2SolveStepTilt}, chi dynamic, the
 * live neck-frame detached rest at {@code k_det}. Same anchor, filament, site phase, seed, dt and camera.
 */
public final class SingleMotorMovieHarness {
    private SingleMotorMovieHarness() {}

    static String OUT = "RUN_LOGS/motor_audit/restored_3d_head_tilt/single_motor_movie";
    static final double DT = 2.5e-6;
    static int    SEED = 20260812;
    static int    PRE  = 800;        // frames before the binding step  (800 x 2.5 us = 2.0 ms)
    static int    POST = 400;        // frames after                    (400 x 2.5 us = 1.0 ms)
    static int    MAXSTEPS = 4_000_000;
    static double KDET = 5.0;
    /** `-3js` output root for the PROJECT viewer (sim_viewer_boa.html); one directory per arm. */
    static String JS_DIR = null;
    static int    JS_STRIDE = 1;       // 1 = one viewer frame per integration timestep

    public static void main(String[] args) {
        String arm = "both";
        for (int i = 0; i < args.length; i++) switch (args[i]) {
            case "-out"  -> OUT = args[++i];
            case "-arm"  -> arm = args[++i];
            case "-seed" -> SEED = Integer.parseInt(args[++i]);
            case "-pre"  -> PRE = Integer.parseInt(args[++i]);
            case "-post" -> POST = Integer.parseInt(args[++i]);
            case "-max"  -> MAXSTEPS = Integer.parseInt(args[++i]);
            case "-kdet" -> KDET = Double.parseDouble(args[++i]);
            case "-3js"  -> JS_DIR = args[++i];
            case "-3js-stride" -> JS_STRIDE = Math.max(1, Integer.parseInt(args[++i]));
            default -> {}
        }
        new java.io.File(OUT).mkdirs();
        System.out.println("\n=== SINGLE-MOTOR FINE-TIME MECHANICAL SANITY MOVIE ===");
        System.out.printf(Locale.US, "  dt = %.2e s ; one recorded frame per timestep ; window = %d before + %d after%n",
                DT, PRE, POST);
        System.out.println("  RUNNER: CPU sequential runner (plain-Java kernel calls). No GPU work is launched.");
        if (arm.equals("both") || arm.equals("off")) run(false);
        if (arm.equals("both") || arm.equals("on"))  run(true);
        System.out.println("\n  data -> " + OUT);
    }

    // ===============================================================================================
    static void run(boolean tilt) {
        String arm = tilt ? "on" : "off";
        System.out.printf("%n--- ARM %s (%s) ---%n", arm.toUpperCase(Locale.US),
                tilt ? "3-D head candidate: chi dynamic + live neck-frame detached rest" : "control: current validated two-body motor");
        cfgPathB(tilt);
        var G = TwoBodyConverterMotor.buildS2Mat(4.0, DT, 40.0, TwoBodyConverterMotor.EXPLICIT_GLIDE_SLACK_NM, SEED, false);
        var e = ExplicitCompleteMatHarness.packExMat(G, 1);
        int N = G.N, M = G.g4M, nSeg = G.nSeg;

        // ---- pick the one bindable motor: smallest lateral distance from the filament axis ------------
        TwoBodyBeamAnalyticGpu.matBeamGeom(e.nodes, e.frame, e.params, e.q, e.exCounts, e.outGeom, e.convF);
        int mSel = -1; double bestD = 1e9;
        for (int m = 0; m < N; m++) {
            double d = axisDist(G, nSeg, e.outGeom.get(6*N+m), e.outGeom.get(7*N+m), e.outGeom.get(8*N+m));
            if (d < bestD) { bestD = d; mSel = m; }
        }
        for (int m = 0; m < N; m++) { G.noBind[m] = (m != mSel); e.noBind.set(m, m != mSel ? 1 : 0); }
        System.out.printf(Locale.US, "  lawn N = %d ; bindable motor = #%d (lateral distance to the filament axis %.2f nm)%n",
                N, mSel, bestD * 1e3);

        // ---- freeze the filament (the ONE fixture) -----------------------------------------------------
        var f = G.fil;
        float[] fc = snap(f.coord), fu = snap(f.uVec), fy = snap(f.yVec), fz = snap(f.zVec),
                f1 = snap(f.end1), f2 = snap(f.end2);
        SEGCUM = new float[nSeg]; for (int s = 0; s < nSeg; s++) SEGCUM[s] = e.segCumArc.get(s);
        double rise = ExplicitCompleteMatHarness.siteRise(ExplicitCompleteMatHarness.SITE_MODE);
        double twist = ExplicitCompleteMatHarness.TWIST_PER_MON_DEG * Math.PI / 180.0 / Constants.actinMonoRadius;
        double stepPhase = twist * rise;
        double Ract = ExplicitCompleteMatHarness.R_ACTIN_NM * 1e-3;

        ArrayDeque<Frame> ring = new ArrayDeque<>();
        List<Frame> post = new ArrayList<>();
        long bindStep = -1; int nBindAttempts = 0;
        for (int t = 0; t < MAXSTEPS; t++) {
            ExplicitCompleteMatHarness.stepGlidingCPU(e, t, SEED);
            restore(f.coord, fc); restore(f.uVec, fu); restore(f.yVec, fy);
            restore(f.zVec, fz); restore(f.end1, f1); restore(f.end2, f2);
            Frame fr = capture(G, e, mSel, t, rise, stepPhase, Ract);
            if (bindStep < 0) {
                ring.addLast(fr); if (ring.size() > PRE) ring.removeFirst();
                if (G.mot.boundSeg.get(mSel) >= 0) { bindStep = t; nBindAttempts++; }
            } else {
                post.add(fr);
                if (post.size() >= POST) break;
            }
        }
        if (bindStep < 0) {
            System.out.println("  *** NO NATURAL BINDING EVENT within " + MAXSTEPS + " steps — nothing written for this arm ***");
            return;
        }
        List<Frame> all = new ArrayList<>(ring); all.addAll(post);
        int bindIdx = ring.size() - 1;                       // the LAST pre-frame is the binding step itself
        System.out.printf(Locale.US, "  NATURAL BINDING at step %d (t = %.4f ms) ; window = %d frames (%d before, binding, %d after)%n",
                bindStep, bindStep * DT * 1e3, all.size(), bindIdx, all.size() - bindIdx - 1);
        Frame b = all.get(bindIdx);
        System.out.printf(Locale.US, "  bound site k = %d on segment %d ; F8-site distance at capture %.3f nm ; angle(eBind, n_site) %.2f deg%n",
                b.site, b.seg, b.dSite, b.angSite);

        writeScene(arm, G, e, mSel, all, bindIdx, rise, stepPhase, Ract, bestD);
        writeFrames(arm, all, bindIdx);
        writeTrace(arm, all, bindIdx);
        if (JS_DIR != null) writeViewer3js(JS_DIR + "_" + arm, G, e, mSel, all, bindIdx);
        captureChecks(arm, all, bindIdx);
        ExplicitCompleteMatHarness.HEAD_TILT_3D = false;
        ExplicitCompleteMatHarness.KBIND_BOUND_ONLY = false;
    }

    /** The canonical Path-B configuration + the arm's head model. Everything else is default-off. */
    static void cfgPathB(boolean tilt) {
        ExplicitCompleteMatHarness.resetChiral();
        ExplicitCompleteMatHarness.SITE_MODE = 3;               // every4, sparse long-pitch
        ExplicitCompleteMatHarness.SITE_PHASE_GLOBAL = true;    // filament-global helical phase
        ExplicitCompleteMatHarness.SITE_AWARE = true;           // site-first capture (no post-hoc snap)
        ExplicitCompleteMatHarness.SITE_EXCLUSIVE = true;
        ExplicitCompleteMatHarness.Z_SLAB = true;               // configured; inert while the filament is frozen
        ExplicitCompleteMatHarness.RAND_BASE_AZ = false;
        ExplicitCompleteMatHarness.HEAD_TILT_3D = tilt;
        ExplicitCompleteMatHarness.HEAD_TILT_AXIS_FIX = true;
        ExplicitCompleteMatHarness.KBIND_BOUND_ONLY = tilt;
        ExplicitCompleteMatHarness.K_DET_PNNM = KDET;
    }

    // ===============================================================================================
    static final class Frame {
        int t; boolean bound; int site = -1, seg = -1, nuc;
        double[] nodes;                              // 3*(M+1)
        double[] C = new double[3], xH = new double[3], xF8 = new double[3];
        double[] eB = new double[3], eR = new double[3];
        double[] n1 = new double[3], n2 = new double[3], n3 = new double[3];
        double[] sitePos, siteN;                     // the CURRENT candidate (or the bound site), null if none
        int candK = -1; boolean isCand;
        double psi, chi, phi, theta, s2ext, dSite = Double.NaN, angRest, angSite = Double.NaN, bondExt = Double.NaN;
        String gate = "";
    }

    static Frame capture(TwoBodyConverterMotor.Glide2D G, ExplicitCompleteMatHarness.ExMat e, int m, int t,
                         double rise, double stepPhase, double Ract) {
        int N = e.N, M = e.M, nSeg = e.nSeg;
        Frame r = new Frame();
        r.t = t;
        r.nuc = G.mot.nucleotideState.get(m);
        r.bound = G.mot.boundSeg.get(m) >= 0;
        r.nodes = new double[3 * (M + 1)];
        for (int j = 0; j <= M; j++) for (int k = 0; k < 3; k++) r.nodes[3*j+k] = e.nodes.get((3*j+k)*N + m);
        for (int k = 0; k < 3; k++) { r.C[k] = e.outGeom.get(k*N+m); r.xH[k] = e.outGeom.get((3+k)*N+m); r.xF8[k] = e.outGeom.get((6+k)*N+m); }
        r.psi = e.q.get(N+m); r.phi = e.q.get(m); r.chi = e.chiHead.get(m); r.theta = r.psi - r.phi;
        double[] eb = ExplicitCompleteMatHarness.eBindOf(e, m, r.psi, r.chi);
        System.arraycopy(eb, 0, r.eB, 0, 3);
        double[] nf = ExplicitCompleteMatHarness.neckFrame(e, m);
        System.arraycopy(nf, 0, r.n1, 0, 3); System.arraycopy(nf, 3, r.n2, 0, 3); System.arraycopy(nf, 6, r.n3, 0, 3);
        double c1 = e.restC.get(m), c2 = e.restC.get(N+m), c3 = e.restC.get(2*N+m);
        for (int k = 0; k < 3; k++) r.eR[k] = c1*nf[k] + c2*nf[3+k] + c3*nf[6+k];
        r.angRest = Math.toDegrees(Math.acos(cl(dot(r.eB, r.eR))));
        // S2 extension = |node M - node 0|
        r.s2ext = Math.sqrt(sq(r.nodes[3*M]-r.nodes[0]) + sq(r.nodes[3*M+1]-r.nodes[1]) + sq(r.nodes[3*M+2]-r.nodes[2])) * 1e3;
        // --- the site of interest: the bound site if bound, else this step's candidate, else the nearest ---
        int s, k; double la, ph;
        if (r.bound) { s = G.mot.boundSeg.get(m); k = e.bindSite.get(m); la = G.mot.bindArc.get(m); ph = G.mot.bindAzim.get(m); }
        else if (e.candInt.get(m) >= 0) { s = e.candInt.get(m); k = e.candInt.get(N+m); la = e.candArc.get(m); ph = e.candAzim.get(m); }
        else { double[] nr = nearestSite(G, nSeg, r.xF8, rise, stepPhase); s = (int) nr[0]; k = (int) nr[1]; la = nr[2]; ph = nr[3]; }
        r.seg = s; r.site = r.bound ? k : -1; r.candK = k;
        r.isCand = !r.bound && e.candInt.get(m) >= 0;
        if (s >= 0) {
            double[] pn = sitePosNormal(G, nSeg, s, la, ph, Ract);
            r.sitePos = new double[]{ pn[0], pn[1], pn[2] }; r.siteN = new double[]{ pn[3], pn[4], pn[5] };
            r.dSite = Math.sqrt(sq(r.xF8[0]-pn[0]) + sq(r.xF8[1]-pn[1]) + sq(r.xF8[2]-pn[2])) * 1e3;
            r.angSite = Math.toDegrees(Math.acos(cl(r.eB[0]*pn[3] + r.eB[1]*pn[4] + r.eB[2]*pn[5])));
            if (r.bound) r.bondExt = r.dSite;
        }
        r.gate = gateStatus(G, e, m, r, s, la, ph, Ract);
        return r;
    }

    /**
     * HOST read-out of the SAME gate quantities the kernels evaluate ({@code siteGateA} actin-side g0/g4/g6/g8,
     * {@code siteCommitB} motor-side g1/g2/g3/g5), for display only. It never feeds the mechanics; the
     * accept/reject decision shown in the movie is always the kernel's own {@code boundSeg}/{@code candInt}.
     * The capture-frame check below asserts that this read-out agrees with the kernel at the binding step.
     */
    static String gateStatus(TwoBodyConverterMotor.Glide2D G, ExplicitCompleteMatHarness.ExMat e, int m,
                             Frame r, int s, double la, double ph, double Ract) {
        if (r.bound) return "BOUND";
        if (G.mot.nucleotideState.get(m) != MotorStore.NUC_ADPPI) return "not ADP.Pi (chemistry gate)";
        if (s < 0 || r.sitePos == null) return "no site in reach";
        int N = e.N;
        double dBindNm = e.sbP.get(0), psiDeg = e.sbP.get(1), phiDeg = e.sbP.get(2), thetaDeg = e.sbP.get(3);
        double preloadPn = e.sbP.get(4), energyKt = e.sbP.get(5), PHI_PRE = e.sbP.get(7), aSemiZ = e.sbP.get(8), kT = e.sbP.get(9);
        double kF8u = e.sbP.get(24);
        double[] eup = { G.eup[0], G.eup[1], G.eup[2] };
        int nSeg = e.nSeg;
        double cx = G.fil.coord.get(s), cy = G.fil.coord.get(nSeg+s), cz = G.fil.coord.get(2*nSeg+s);
        double headSide = ((r.xH[0]-cx)*eup[0] + (r.xH[1]-cy)*eup[1] + (r.xH[2]-cz)*eup[2]) * 1e3;
        double aApp = (r.xF8[0]-r.sitePos[0])*r.siteN[0] + (r.xF8[1]-r.sitePos[1])*r.siteN[1] + (r.xF8[2]-r.sitePos[2])*r.siteN[2];
        double preload = kF8u * (r.dSite * 1e-3) * 1e12;
        double psiErr = Math.abs(r.psi - e.q.get(3*N+m)) * 180/Math.PI;
        double phiErr = Math.abs(r.phi - PHI_PRE) * 180/Math.PI;
        double thErr  = Math.abs(r.theta - e.q.get(2*N+m)) * 180/Math.PI;
        double kconv = e.params.get(6*N+m), kbind = e.params.get(7*N+m);
        double dth = r.theta - e.q.get(2*N+m), dpa = r.psi - e.q.get(3*N+m);
        double eKt = (0.5*kconv*dth*dth + 0.5*kbind*dpa*dpa) / kT;
        StringBuilder sb = new StringBuilder();
        if (!(headSide < aSemiZ*1e3)) sb.append("g6 head-height ").append(String.format(Locale.US, "%.2f>%.2f nm; ", headSide, aSemiZ*1e3));
        if (!(aApp > -1e-9))          sb.append("g8 approach-from-inside; ");
        if (!(r.dSite < dBindNm))     sb.append(String.format(Locale.US, "g0 dist %.2f>%.1f nm; ", r.dSite, dBindNm));
        if (!(preload < preloadPn))   sb.append(String.format(Locale.US, "g4 preload %.2f>%.1f pN; ", preload, preloadPn));
        if (!(psiErr < psiDeg))       sb.append(String.format(Locale.US, "g1 psi %.1f>%.0f deg; ", psiErr, psiDeg));
        if (!(phiErr < phiDeg))       sb.append(String.format(Locale.US, "g2 phi %.1f>%.0f deg; ", phiErr, phiDeg));
        if (!(thErr < thetaDeg))      sb.append(String.format(Locale.US, "g3 theta %.1f>%.0f deg; ", thErr, thetaDeg));
        if (!(eKt < energyKt))        sb.append(String.format(Locale.US, "g5 energy %.1f>%.0f kT; ", eKt, energyKt));
        return sb.length() == 0 ? "ALL GATES PASS (awaiting commit)" : sb.toString().trim();
    }

    // ===============================================================================================
    static void captureChecks(String arm, List<Frame> all, int bi) {
        System.out.printf("%n  CAPTURE-MOMENT HARD VISUAL CHECKS (arm %s), frames -1 / 0 / +1:%n", arm);
        Frame a = all.get(Math.max(0, bi-1)), b = all.get(bi), c = all.get(Math.min(all.size()-1, bi+1));
        double jumpH = dist(a.xH, b.xH)*1e3, jumpF = dist(a.xF8, b.xF8)*1e3, jumpC = dist(a.C, b.C)*1e3;
        double jumpP = dist(new double[]{a.nodes[a.nodes.length-3],a.nodes[a.nodes.length-2],a.nodes[a.nodes.length-1]},
                            new double[]{b.nodes[b.nodes.length-3],b.nodes[b.nodes.length-2],b.nodes[b.nodes.length-1]})*1e3;
        double rot = Math.toDegrees(Math.acos(cl(dot(a.eB, b.eB))));
        // typical detached step sizes, for scale
        double medH = 0, medR = 0; int n = 0;
        for (int i = Math.max(1, bi-200); i < bi; i++) { medH += dist(all.get(i-1).xH, all.get(i).xH)*1e3;
            medR += Math.toDegrees(Math.acos(cl(dot(all.get(i-1).eB, all.get(i).eB)))); n++; }
        medH /= Math.max(1,n); medR /= Math.max(1,n);
        System.out.printf(Locale.US, "   1/6 head-centre step at capture %.4f nm  (mean detached step %.4f nm, ratio %.2f)%n", jumpH, medH, jumpH/medH);
        System.out.printf(Locale.US, "   2   eBind rotation at capture %.3f deg   (mean detached %.3f deg, ratio %.2f)%n", rot, medR, rot/medR);
        System.out.printf(Locale.US, "   10  xF8 step %.4f nm ; pivot C step %.4f nm ; beam tip P step %.4f nm ; S2 ext %.4f -> %.4f nm%n",
                jumpF, jumpC, jumpP, a.s2ext, b.s2ext);
        System.out.printf(Locale.US, "   3   F8-to-site distance BEFORE capture %.3f nm (gate is < %.1f nm)%n", a.dSite, 3.0);
        System.out.printf(Locale.US, "   4   angle(eBind, n_site) BEFORE capture %.2f deg ; AT capture %.2f deg ; AFTER %.2f deg%n",
                a.angSite, b.angSite, c.angSite);
        System.out.printf(Locale.US, "   5   angle(eBind, eBind_rest) over the 50 frames before capture: %s%n", trendStr(all, bi));
        System.out.printf(Locale.US, "   7/8 latched site k = %d on segment %d ; the candidate at frame -1 was k = %d on segment %d  -> %s%n",
                b.site, b.seg, a.candK, a.seg, (b.site == a.candK && b.seg == a.seg) ? "SAME SITE" : "*** DIFFERENT ***");
        double mxH = 0, mxR = 0;
        for (int i = Math.max(1, bi-400); i < bi; i++) { mxH = Math.max(mxH, dist(all.get(i-1).xH, all.get(i).xH)*1e3);
            mxR = Math.max(mxR, Math.toDegrees(Math.acos(cl(dot(all.get(i-1).eB, all.get(i).eB))))); }
        System.out.printf(Locale.US, "   FINE-TIME MOTION SCALE (detached, per SINGLE %.1f us timestep): head centre mean %.3f / max %.3f nm ;"
                + " eBind mean %.2f / max %.2f deg%n", DT*1e6, medH, mxH, medR, mxR);
        System.out.printf(Locale.US, "   9   host gate read-out at frame -1: %s%n", a.gate);
        System.out.printf(Locale.US, "       host gate read-out at frame  0: %s%n", b.gate);
    }
    static String trendStr(List<Frame> all, int bi) {
        int i0 = Math.max(0, bi-50); double lo = 1e9, hi = -1e9;
        for (int i = i0; i <= bi; i++) { lo = Math.min(lo, all.get(i).angRest); hi = Math.max(hi, all.get(i).angRest); }
        return String.format(Locale.US, "range %.2f .. %.2f deg (a site that 'pulled' the head would show a monotone collapse)", lo, hi);
    }

    // =============================================================================== output
    static void writeScene(String arm, TwoBodyConverterMotor.Glide2D G, ExplicitCompleteMatHarness.ExMat e, int m,
                           List<Frame> all, int bi, double rise, double stepPhase, double Ract, double latNm) {
        int nSeg = e.nSeg;
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append(String.format(Locale.US, "\"arm\":\"%s\",\"dt\":%.6e,\"motor\":%d,\"bindFrame\":%d,\"nFrames\":%d,\n",
                arm, DT, m, bi, all.size()));
        sb.append(String.format(Locale.US, "\"lateralNm\":%.4f,\"Ractin\":%.6f,\"rise\":%.6f,\"kdet\":%.2f,\"boundSite\":%d,\"boundSeg\":%d,\n",
                latNm*1e3, Ract, rise, ExplicitCompleteMatHarness.K_DET_PNNM, all.get(bi).site, all.get(bi).seg));
        sb.append("\"filament\":[");
        for (int s = 0; s < nSeg; s++) {
            if (s > 0) sb.append(",");
            sb.append(String.format(Locale.US, "{\"c\":[%.6f,%.6f,%.6f],\"u\":[%.6f,%.6f,%.6f],\"len\":%.6f}",
                    G.fil.coord.get(s), G.fil.coord.get(nSeg+s), G.fil.coord.get(2*nSeg+s),
                    G.fil.uVec.get(s), G.fil.uVec.get(nSeg+s), G.fil.uVec.get(2*nSeg+s), G.fil.segLength.get(s)));
        }
        sb.append("],\n\"sites\":[");
        // enumerate the whole sparse lattice, then keep the ones near the event for the viewer
        double xf = all.get(bi).xF8[0];
        boolean first = true;
        for (int k = 0; k < 400; k++) {
            double gArc = k * rise;
            int s = -1; double la = 0;
            for (int q = 0; q < nSeg; q++) {
                double cum = e.segCumArc.get(q), L = G.fil.segLength.get(q);
                if (gArc >= cum - 1e-5 && gArc <= cum + L + 1e-5) { s = q; la = Math.max(0, Math.min(L, gArc - cum)); break; }
            }
            if (s < 0) continue;
            double tw = k * stepPhase; double ph = tw - 2*Math.PI*Math.floor(tw/(2*Math.PI));
            double[] pn = sitePosNormal(G, nSeg, s, la, ph, Ract);
            if (Math.abs(pn[0] - xf) > 0.09) continue;                     // +-90 nm of the event, for readability
            if (!first) sb.append(","); first = false;
            sb.append(String.format(Locale.US, "{\"k\":%d,\"seg\":%d,\"p\":[%.6f,%.6f,%.6f],\"n\":[%.5f,%.5f,%.5f]}",
                    k, s, pn[0], pn[1], pn[2], pn[3], pn[4], pn[5]));
        }
        sb.append("],\n");
        double[] a0 = { G.g4E[m][0], G.g4E[m][1], G.g4E[m][2] };
        sb.append(String.format(Locale.US, "\"anchor\":[%.6f,%.6f,%.6f]\n}\n", a0[0], a0[1], a0[2]));
        write("scene_" + arm + ".json", sb.toString());
    }

    /**
     * `-3js` — the PROJECT viewer's frame sequence (`sim_viewer_boa.html`), one `frame_%06d.json` per recorded
     * timestep, written from the SAME captured frames as the bespoke movie. No second run, no extra physics.
     *
     * <p><b>Schema note (load-bearing).</b> The unified viewer needs the ARRAY form — a segment is
     * {@code {id,end1:[x,y,z],end2:[x,y,z],r,motorSeg,...}} and a myosin is
     * {@code {id,rod:{end1,end2,r,invisible},lever:{...},motor:{...,state}}}. The legacy flat
     * {@code {x1,y1,z1,...}} form makes {@code applyFrameData} throw inside {@code loadFrame}'s swallowed
     * {@code .catch}, and the HUD sits on "loading…" forever.
     *
     * <p><b>Channel map.</b> ONE motor is rendered — the single bindable one — so the scene is literally one
     * filament and one surface-anchored motor:
     * <pre>
     *   segments  actin filament (r = 3.5 nm)
     *             the EXPLICIT S2 beam, element by element (motorSeg) — so its real bending is visible
     *             a stub at the S2 emergence node, marking the SURFACE ATTACHMENT below
     *             the current candidate / bound site, as a short outward radial stub
     *   myosin 0  rod = invisible (the true bent S2 is drawn above) · lever = P->C (the lever/neck)
     *             motor = xH->xF8 (the head), coloured by nucleotide state
     *   myosin 1  present only while BOUND: lever = xF8 -> x_site (the F8 bond), motor = the site marker
     * </pre>
     * The other lawn motors are {@code noBind} and form no bond, so they apply nothing to the filament and are
     * mechanically decoupled from the rendered one; omitting them changes no physics, and it is stated here
     * rather than left implicit.
     */
    static void writeViewer3js(String dir, TwoBodyConverterMotor.Glide2D G,
                               ExplicitCompleteMatHarness.ExMat e, int m, List<Frame> all, int bi) {
        var f = G.fil; int nSeg = e.nSeg, M = e.M;
        java.io.File d = new java.io.File(dir); d.mkdirs();
        double Ract = ExplicitCompleteMatHarness.R_ACTIN_NM * 1e-3;
        double[] up = G.eup;
        // frozen filament: capture the segment endpoints once
        double[][] s1 = new double[nSeg][3], s2 = new double[nSeg][3];
        for (int s = 0; s < nSeg; s++) {
            double half = 0.5 * f.segLength.get(s);
            for (int k = 0; k < 3; k++) {
                double c = f.coord.get(k*nSeg + s), u = f.uVec.get(k*nSeg + s);
                s1[s][k] = c - half*u; s2[s][k] = c + half*u;
            }
        }
        // scene bounds from the filament + the motor's anchor, padded
        double[] lo = { 1e9, 1e9, 1e9 }, hi = { -1e9, -1e9, -1e9 };
        for (int s = 0; s < nSeg; s++) for (int k = 0; k < 3; k++) {
            lo[k] = Math.min(lo[k], Math.min(s1[s][k], s2[s][k]));
            hi[k] = Math.max(hi[k], Math.max(s1[s][k], s2[s][k]));
        }
        for (int k = 0; k < 3; k++) { lo[k] = Math.min(lo[k], G.g4E[m][k]); hi[k] = Math.max(hi[k], G.g4E[m][k]); }
        double pad = 0.02;
        int written = 0;
        for (int i = 0; i < all.size(); i += JS_STRIDE) {
            Frame r = all.get(i);
            StringBuilder b = new StringBuilder(1 << 14);
            b.append(String.format(Locale.US, "{\"frame\":%d,\"t\":%.9g,", written, r.t * DT));
            b.append(String.format(Locale.US, "\"bounds\":{\"xDim\":%.5g,\"yDim\":%.5g,\"zDim\":%.5g},",
                    hi[0]-lo[0]+2*pad, hi[1]-lo[1]+2*pad, hi[2]-lo[2]+2*pad));
            b.append("\"segments\":[");
            int sid = 0;
            for (int s = 0; s < nSeg; s++) b.append(seg(sid++, s1[s], s2[s], Ract, false, 1.00, sid > 1));   // YELLOW actin
            for (int j = 0; j < M; j++) {                       // the explicit S2, element by element
                double[] a = { r.nodes[3*j], r.nodes[3*j+1], r.nodes[3*j+2] };
                double[] c = { r.nodes[3*(j+1)], r.nodes[3*(j+1)+1], r.nodes[3*(j+1)+2] };
                b.append(seg(sid++, a, c, 0.0010, true, 0.55, true));                                        // ORANGE S2
            }
            double[] a0 = { r.nodes[0], r.nodes[1], r.nodes[2] };                       // SURFACE ATTACHMENT
            double[] a1 = { a0[0]-0.004*up[0], a0[1]-0.004*up[1], a0[2]-0.004*up[2] };
            b.append(seg(sid++, a0, a1, 0.0022, true, 0.55, true));                                          // ORANGE anchor
            if (r.sitePos != null) {                                                    // candidate / bound site
                double sl = r.bound ? 0.0035 : 0.0018;
                double[] p1 = { r.sitePos[0]+sl*r.siteN[0], r.sitePos[1]+sl*r.siteN[1], r.sitePos[2]+sl*r.siteN[2] };
                b.append(seg(sid++, r.sitePos, p1, r.bound ? 0.0010 : 0.0005, true, 0.00, true));            // RED site
            }
            b.append("],\"myosins\":[");
            double[] P = { r.nodes[3*M], r.nodes[3*M+1], r.nodes[3*M+2] };
            b.append(String.format(Locale.US,
                    "{\"id\":0,\"rod\":{\"end1\":[%.6f,%.6f,%.6f],\"end2\":[%.6f,%.6f,%.6f],\"r\":0.0008,\"invisible\":true},"
                    + "\"lever\":{\"end1\":[%.6f,%.6f,%.6f],\"end2\":[%.6f,%.6f,%.6f],\"r\":0.0011},"
                    + "\"motor\":{\"end1\":[%.6f,%.6f,%.6f],\"end2\":[%.6f,%.6f,%.6f],\"r\":0.0020,\"state\":\"%s\"}}",
                    a0[0], a0[1], a0[2], P[0], P[1], P[2],
                    P[0], P[1], P[2], r.C[0], r.C[1], r.C[2],
                    r.xH[0], r.xH[1], r.xH[2], r.xF8[0], r.xF8[1], r.xF8[2], nucName(r.nuc)));
            if (r.bound && r.sitePos != null) b.append(String.format(Locale.US,
                    ",{\"id\":1,\"rod\":{\"end1\":[%.6f,%.6f,%.6f],\"end2\":[%.6f,%.6f,%.6f],\"r\":0.0005,\"invisible\":true},"
                    + "\"lever\":{\"end1\":[%.6f,%.6f,%.6f],\"end2\":[%.6f,%.6f,%.6f],\"r\":0.0006},"
                    + "\"motor\":{\"end1\":[%.6f,%.6f,%.6f],\"end2\":[%.6f,%.6f,%.6f],\"r\":0.0012,\"state\":\"NONE\"}}",
                    r.xF8[0], r.xF8[1], r.xF8[2], r.xF8[0], r.xF8[1], r.xF8[2],
                    r.xF8[0], r.xF8[1], r.xF8[2], r.sitePos[0], r.sitePos[1], r.sitePos[2],
                    r.sitePos[0], r.sitePos[1], r.sitePos[2], r.sitePos[0], r.sitePos[1], r.sitePos[2]));
            b.append("]}");
            try { java.nio.file.Files.writeString(
                    java.nio.file.Path.of(dir, String.format(Locale.US, "frame_%06d.json", written)), b.toString()); }
            catch (java.io.IOException ex) { throw new RuntimeException(ex); }
            written++;
        }
        System.out.printf(Locale.US, "  -3js: %d viewer frames (stride %d, binding at viewer frame %d) -> %s%n",
                written, JS_STRIDE, bi / JS_STRIDE, dir);
    }

    /**
     * One `segments` entry. {@code age} is the viewer's ONLY per-segment colour channel: with "age colour" on
     * (its default) it renders {@code rgb(1, age, 0)}, so the red-to-yellow ramp is what separates the channels
     * here — 1.0 YELLOW actin, 0.55 ORANGE motor beam/anchor, 0.0 RED the binding site. Without this every
     * segment came out the same yellow and only size told them apart.
     */
    static String seg(int id, double[] p, double[] q, double r, boolean motorSeg, double age, boolean comma) {
        return String.format(Locale.US, "%s{\"id\":%d,\"end1\":[%.6f,%.6f,%.6f],\"end2\":[%.6f,%.6f,%.6f],"
                + "\"r\":%.5f,\"motorSeg\":%s,\"notADPRatio\":%.2f,\"cofilinCount\":0}",
                comma ? "," : "", id, p[0], p[1], p[2], q[0], q[1], q[2], r, motorSeg ? "true" : "false", age);
    }
    /** The viewer's motor palette keys (NONE purple / ATP yellow / ADPPi orange / ADP red). */
    static String nucName(int n) {
        return n == MotorStore.NUC_ATP ? "ATP" : n == MotorStore.NUC_ADPPI ? "ADPPi"
             : n == MotorStore.NUC_ADP ? "ADP" : "NONE";
    }

    static void writeFrames(String arm, List<Frame> all, int bi) {
        StringBuilder sb = new StringBuilder("{\"frames\":[\n");
        for (int i = 0; i < all.size(); i++) {
            Frame r = all.get(i);
            if (i > 0) sb.append(",\n");
            sb.append("{\"t\":").append(r.t).append(",\"b\":").append(r.bound ? 1 : 0)
              .append(",\"site\":").append(r.site).append(",\"cand\":").append(r.candK)
              .append(",\"nuc\":").append(r.nuc).append(",\"nd\":").append(arr(r.nodes))
              .append(",\"C\":").append(arr(r.C)).append(",\"H\":").append(arr(r.xH)).append(",\"F\":").append(arr(r.xF8))
              .append(",\"eB\":").append(arr(r.eB)).append(",\"eR\":").append(arr(r.eR))
              .append(",\"n1\":").append(arr(r.n1)).append(",\"n2\":").append(arr(r.n2)).append(",\"n3\":").append(arr(r.n3));
            if (r.sitePos != null) sb.append(",\"sp\":").append(arr(r.sitePos)).append(",\"sn\":").append(arr(r.siteN));
            sb.append(String.format(Locale.US,
                ",\"psi\":%.5f,\"chi\":%.5f,\"phi\":%.5f,\"th\":%.5f,\"s2\":%.4f,\"d\":%.4f,\"aR\":%.3f,\"aS\":%.3f,\"g\":\"%s\"}",
                r.psi, r.chi, r.phi, r.theta, r.s2ext, r.dSite, r.angRest, r.angSite, r.gate.replace("\"", "'")));
        }
        sb.append("\n]}\n");
        write("frames_" + arm + ".json", sb.toString());
    }

    static void writeTrace(String arm, List<Frame> all, int bi) {
        StringBuilder sb = new StringBuilder(
            "frame\trel\tt_s\tbound\tsiteID\tcandK\tseg\tnuc\txF8x\txF8y\txF8z\txHx\txHy\txHz\t"
          + "eBx\teBy\teBz\teRx\teRy\teRz\tnSx\tnSy\tnSz\tpsi_rad\tchi_rad\tphi_rad\ttheta_rad\t"
          + "s2ext_nm\tdF8site_nm\tangEBind_eRest_deg\tangEBind_nSite_deg\tbondExt_nm\tgate\n");
        for (int i = 0; i < all.size(); i++) {
            Frame r = all.get(i);
            double[] ns = r.siteN != null ? r.siteN : new double[]{ Double.NaN, Double.NaN, Double.NaN };
            sb.append(String.format(Locale.US,
                "%d\t%d\t%.9f\t%d\t%d\t%d\t%d\t%d\t%.7f\t%.7f\t%.7f\t%.7f\t%.7f\t%.7f\t"
              + "%.6f\t%.6f\t%.6f\t%.6f\t%.6f\t%.6f\t%.6f\t%.6f\t%.6f\t%.6f\t%.6f\t%.6f\t%.6f\t"
              + "%.4f\t%.4f\t%.3f\t%.3f\t%.4f\t%s%n",
                i, i - bi, r.t * DT, r.bound ? 1 : 0, r.site, r.candK, r.seg, r.nuc,
                r.xF8[0], r.xF8[1], r.xF8[2], r.xH[0], r.xH[1], r.xH[2],
                r.eB[0], r.eB[1], r.eB[2], r.eR[0], r.eR[1], r.eR[2], ns[0], ns[1], ns[2],
                r.psi, r.chi, r.phi, r.theta, r.s2ext, r.dSite, r.angRest, r.angSite, r.bondExt, r.gate));
        }
        write("trace_" + arm + ".tsv", sb.toString());
    }

    // =============================================================================== small helpers
    static double axisDist(TwoBodyConverterMotor.Glide2D G, int nSeg, double x, double y, double z) {
        double bd = 1e9;
        for (int s = 0; s < nSeg; s++) {
            double half = 0.5 * G.fil.segLength.get(s);
            double cx = G.fil.coord.get(s), cy = G.fil.coord.get(nSeg+s), cz = G.fil.coord.get(2*nSeg+s);
            double ux = G.fil.uVec.get(s), uy = G.fil.uVec.get(nSeg+s), uz = G.fil.uVec.get(2*nSeg+s);
            double dx = x-cx, dy = y-cy, dz = z-cz;
            double foot = dx*ux + dy*uy + dz*uz; foot = Math.max(-half, Math.min(half, foot));
            double qx = dx-foot*ux, qy = dy-foot*uy, qz = dz-foot*uz;
            bd = Math.min(bd, Math.sqrt(qx*qx+qy*qy+qz*qz));
        }
        return bd;
    }
    /**
     * {seg, k, arc, phase} of the NEAREST real lattice site to p, by full 3-D distance. Same enumeration the
     * capture kernel uses (global index k, global arc k*rise, filament-global phase) — display only, so that
     * "how far is the head from the site it will eventually take" is meaningful on every frame, including
     * frames where no site passes the gates and the kernel therefore publishes no candidate.
     */
    static double[] nearestSite(TwoBodyConverterMotor.Glide2D G, int nSeg, double[] p, double rise, double stepPhase) {
        double Ract = ExplicitCompleteMatHarness.R_ACTIN_NM * 1e-3;
        int bs = -1, bk = -1; double bd = 1e9, bla = 0, bph = 0;
        for (int s = 0; s < nSeg; s++) {
            double L = G.fil.segLength.get(s), cum = SEGCUM[s];
            int k0 = (int) ((cum + 0.5*L) / rise);
            for (int k = Math.max(0, k0 - 4); k <= k0 + 4; k++) {
                double la = k * rise - cum;
                if (la < -1e-5 || la > L + 1e-5) continue;
                la = Math.max(0, Math.min(L, la));
                double tw = k * stepPhase; double ph = tw - 2*Math.PI*Math.floor(tw/(2*Math.PI));
                double[] pn = sitePosNormal(G, nSeg, s, la, ph, Ract);
                double d2 = sq(p[0]-pn[0]) + sq(p[1]-pn[1]) + sq(p[2]-pn[2]);
                if (d2 < bd) { bd = d2; bs = s; bk = k; bla = la; bph = ph; }
            }
        }
        return new double[]{ bs, bk, bla, bph };
    }
    static float[] SEGCUM;
    static double[] sitePosNormal(TwoBodyConverterMotor.Glide2D G, int nSeg, int s, double la, double ph, double Ract) {
        double half = 0.5 * G.fil.segLength.get(s);
        double cx = G.fil.coord.get(s), cy = G.fil.coord.get(nSeg+s), cz = G.fil.coord.get(2*nSeg+s);
        double ux = G.fil.uVec.get(s), uy = G.fil.uVec.get(nSeg+s), uz = G.fil.uVec.get(2*nSeg+s);
        double yx = G.fil.yVec.get(s), yy = G.fil.yVec.get(nSeg+s), yz = G.fil.yVec.get(2*nSeg+s);
        double zx = uy*yz-uz*yy, zy = uz*yx-ux*yz, zz = ux*yy-uy*yx;
        double zl = Math.sqrt(zx*zx+zy*zy+zz*zz); if (zl > 1e-15) { zx/=zl; zy/=zl; zz/=zl; }
        double c = Math.cos(ph), s2 = Math.sin(ph);
        double nx = c*yx + s2*zx, ny = c*yy + s2*zy, nz = c*yz + s2*zz;
        double aOff = la - half;
        return new double[]{ cx + aOff*ux + Ract*nx, cy + aOff*uy + Ract*ny, cz + aOff*uz + Ract*nz, nx, ny, nz };
    }
    static float[] snap(uk.ac.manchester.tornado.api.types.arrays.FloatArray a) {
        float[] o = new float[a.getSize()]; for (int i = 0; i < o.length; i++) o[i] = a.get(i); return o; }
    static void restore(uk.ac.manchester.tornado.api.types.arrays.FloatArray a, float[] o) {
        for (int i = 0; i < o.length; i++) a.set(i, o[i]); }
    static String arr(double[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) { if (i > 0) sb.append(","); sb.append(String.format(Locale.US, "%.6f", v[i])); }
        return sb.append("]").toString();
    }
    static double dot(double[] a, double[] b) { return a[0]*b[0]+a[1]*b[1]+a[2]*b[2]; }
    static double sq(double x) { return x*x; }
    static double cl(double x) { return x > 1 ? 1 : (x < -1 ? -1 : x); }
    static double dist(double[] a, double[] b) { return Math.sqrt(sq(a[0]-b[0])+sq(a[1]-b[1])+sq(a[2]-b[2])); }
    static void write(String name, String s) {
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(OUT, name), s); }
        catch (java.io.IOException ex) { throw new RuntimeException(ex); }
    }
}
