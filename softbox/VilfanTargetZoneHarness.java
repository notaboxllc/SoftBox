package softbox;

import softbox.TwoBodyConverterMotor.Glide2D;
import uk.ac.manchester.tornado.api.GridScheduler;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.WorkerGrid1D;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.types.arrays.DoubleArray;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;
import java.util.Locale;

/**
 * VILFAN-STYLE STEREOSPECIFIC TARGET-ZONE BINDING in the explicit-S2 gliding assay (noncanonical, default-off).
 *
 * <p>Stage A only in this harness: a CONTINUOUS angular-mismatch hazard that modifies the attachment rate before
 * commitment ({@link TwoBodyBeamAnalyticGpu#matTargetZone}). No bound torsional registry (Stage B), no roll
 * spring (Stage C) — those are separately switchable and gated on the Stage-A result.
 *
 * <p>Primary question: does a continuous, stereospecific actomyosin orientation constraint generate a biased
 * attachment flux and a nonzero mean axial torque during explicit-S2 gliding?
 *
 * <p>Modes: {@code -fixtures} (deterministic Stage-A fixtures incl. the kinematic target-zone rig) |
 * {@code -equiv} (full device-resident target-zone graph + kernel bit-identity) | {@code -stageA} (the dynamic
 * alphaPsi experiment + symmetry controls) | {@code -dt} (timestep check) | {@code -3js <dir>} | {@code -all}.
 * Flags: {@code -target-zone-alpha <v>} {@code -density <v>} {@code -seed <n>} {@code -steps <n>}
 * {@code -stride <n>} {@code -actin-bind-radius-nm <v>} {@code -surface-exclusion-nm <v>} {@code -gpu}.
 *
 * <p>See docs/VILFAN_TARGET_ZONE_BINDING_AND_TWIRLING_FINDINGS.md.
 */
public final class VilfanTargetZoneHarness {

    static final double DT = ExplicitCompleteMatHarness.DT;      // 2.5e-6 s
    static double DENSITY = 200.0;
    static int    SEED = 101, STEPS = 4000, STRIDE = 40, NSEEDS = 4;
    static double R_NM = 3.5, EXCL_NM = 5.5;
    static double ALPHA = 6.0;
    static boolean USE_GPU = false;
    /** actin 13/6 LEFT-handed helical twist rate (rad/µm) — the same constant packExMat derives. */
    static final double TWIST = ExplicitCompleteMatHarness.TWIST_PER_MON_DEG * Math.PI / 180.0 / Constants.actinMonoRadius;

    public static void main(String[] args) {
        boolean fixtures = false, equiv = false, stageA = false, dtChk = false, all = false; String jsDir = null;
        boolean brFix = false, brEq = false, ablation = false; String policyName = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-fixtures" -> fixtures = true;
                case "-equiv" -> equiv = true;
                case "-stageA", "-stagea" -> stageA = true;
                case "-dt" -> dtChk = true;
                case "-all" -> all = true;
                case "-gpu" -> USE_GPU = true;
                case "-3js" -> jsDir = args[++i];
                case "-target-zone-alpha" -> ALPHA = Double.parseDouble(args[++i]);
                case "-density" -> DENSITY = Double.parseDouble(args[++i]);
                case "-seed" -> SEED = Integer.parseInt(args[++i]);
                case "-seeds" -> NSEEDS = Integer.parseInt(args[++i]);
                case "-steps" -> STEPS = Integer.parseInt(args[++i]);
                case "-stride" -> STRIDE = Integer.parseInt(args[++i]);
                case "-actin-bind-radius-nm" -> R_NM = Double.parseDouble(args[++i]);
                case "-surface-exclusion-nm" -> EXCL_NM = Double.parseDouble(args[++i]);
                // ---- Brownian-noise ablation (noncanonical, default-off) ----
                case "-brownian-fixtures" -> brFix = true;
                case "-brownian-equiv" -> brEq = true;
                case "-ablation" -> ablation = true;
                case "-decompose" -> { ablation = true; DECOMPOSE = true; }
                case "-brownian-policy" -> policyName = args[++i];
                case "-filament-brownian-axial" -> OV_FAX = onOff(args[++i]);
                case "-filament-brownian-transverse" -> OV_FTR = onOff(args[++i]);
                case "-filament-brownian-roll" -> OV_FROLL = onOff(args[++i]);
                case "-filament-brownian-other-rotation" -> OV_FOTH = onOff(args[++i]);
                case "-motor-brownian-unbound" -> OV_MUNB = onOff(args[++i]);
                case "-motor-brownian-bound" -> OV_MBND = onOff(args[++i]);
                default -> { }
            }
        }
        System.out.println("######## Vilfan target-zone stereospecific binding — explicit-S2 gliding (noncanonical, default-off) ########");
        System.out.printf(Locale.US, "dt=%.2e  density=%.0f heads/µm²  seed=%d  steps=%d  seeds=%d  Ractin=%.2f nm  excl=%.2f nm  twistRate=%.1f rad/µm%n",
                DT, DENSITY, SEED, STEPS, NSEEDS, R_NM, EXCL_NM, TWIST);
        // A named policy expands into EXPLICIT channel settings, which are logged; explicit flags override it.
        Pol single = null;
        if (policyName != null || OV_FAX != null || OV_FTR != null || OV_FROLL != null || OV_FOTH != null || OV_MUNB != null || OV_MBND != null) {
            single = applyOverrides(policyName == null ? POL_FULL : namedPolicy(policyName));
            System.out.printf("BROWNIAN POLICY '%s' expands to: %s%n", single.name(), single.spec());
            applyPolicy(single);
            System.out.println("   ⇒ " + ExplicitCompleteMatHarness.brownianPolicyString());
            ExplicitCompleteMatHarness.resetBrownianPolicy();
            CLI_POL = single;
        }
        boolean ok = true;
        if (jsDir != null) { makeMovies(jsDir); return; }
        if (all)          { ok &= runFixtures(); ok &= runEquiv(); runStageA(); runDtCheck(); }
        else if (fixtures) ok = runFixtures();
        else if (equiv)    ok = runEquiv();
        else if (stageA)   runStageA();
        else if (dtChk)    runDtCheck();
        else if (brFix)    ok = runBrFixtures();
        else if (brEq)     ok = runBrEquiv();
        else if (ablation) { if (single != null) runSinglePolicy(single); else runAblation(); }
        else               ok = runFixtures();
        System.out.println("====================================================================================================");
        if (fixtures || equiv || all || brFix || brEq) System.out.println(ok ? "ALL GATED CHECKS PASS" : "*** SOME CHECKS FAILED ***");
        if (!ok) System.exit(1);
    }
    static Boolean onOff(String s) {
        if (s.equalsIgnoreCase("on") || s.equals("1") || s.equalsIgnoreCase("true")) return Boolean.TRUE;
        if (s.equalsIgnoreCase("off") || s.equals("0") || s.equalsIgnoreCase("false")) return Boolean.FALSE;
        throw new IllegalArgumentException("expected on|off, got " + s);
    }
    /** A single explicitly-specified Brownian policy (for spot runs / one-off arms). */
    static void runSinglePolicy(Pol pol) {
        System.out.printf("\n--- SINGLE BROWNIAN POLICY ARM (%s runner) ---%n", USE_GPU ? "GPU device-resident" : "CPU sequential");
        int[] seeds = new int[NSEEDS]; for (int i = 0; i < NSEEDS; i++) seeds[i] = SEED + 101*i;
        BrArm a = meanBr("single: " + pol.name(), pol, ALPHA, seeds, STEPS);
        brRow(a); brPhaseRow(a); brHists(a);
    }

    // ================================================================= configuration helpers
    static void setTZ(boolean on, double alpha) {
        ExplicitCompleteMatHarness.TZ_ON = on;
        ExplicitCompleteMatHarness.TZ_ALPHA = alpha;
        ExplicitCompleteMatHarness.TZ_HARD_RAD = 0.0;
    }
    static void setSurface(boolean on, double rNm, boolean steric, double exclNm) {
        ExplicitCompleteMatHarness.SURFACE_ON = on;
        ExplicitCompleteMatHarness.R_ACTIN_NM = rNm;
        ExplicitCompleteMatHarness.SURF_STERIC = steric;
        ExplicitCompleteMatHarness.SURF_EXCL_NM = exclNm;
    }
    static Glide2D build(int seed) {
        return TwoBodyConverterMotor.buildS2Mat(DENSITY, DT, 40.0, TwoBodyConverterMotor.EXPLICIT_GLIDE_SLACK_NM, seed);
    }

    // ================================================================= unit rig for matTargetZone
    /**
     * Evaluate the kernel's SIGNED angular mismatch on a fully constructed single-motor state (alphaPsi = 0 ⇒
     * always accepted ⇒ the diagnostic reports the raw mismatch). u/y = the segment axis + material reference,
     * c = segment centre, mhat = the motor binding direction (the direction the compatible actin surface normal
     * must face); the head is synthesised as xF8 − xH = −L·mhat so the kernel's −normalize(xF8−xH) == mhat.
     * @return {deltaPsi, wPsi, accept, retainedAzimuth}
     */
    static double[] probe(double[] u, double[] y, double[] c, double slen, double arc, double[] mhat,
                          double alpha, double twist) {
        IntArray boundSeg = IntArray.fromElements(0), prevBound = IntArray.fromElements(-1), justBound = IntArray.fromElements(0);
        DoubleArray outGeom = new DoubleArray(9);
        double[] xH = { 0, 0, 0 };
        for (int k = 0; k < 3; k++) { outGeom.set(3 + k, xH[k]); outGeom.set(6 + k, xH[k] - 0.01 * mhat[k]); }
        FloatArray filCoord = FloatArray.fromElements((float) c[0], (float) c[1], (float) c[2]);
        FloatArray filUVec  = FloatArray.fromElements((float) u[0], (float) u[1], (float) u[2]);
        FloatArray filYVec  = FloatArray.fromElements((float) y[0], (float) y[1], (float) y[2]);
        FloatArray segLen   = FloatArray.fromElements((float) slen);
        FloatArray bindArc  = FloatArray.fromElements((float) arc);
        FloatArray bindAzim = FloatArray.fromElements(0f), bindPsi0 = FloatArray.fromElements(0f);
        DoubleArray tzP = DoubleArray.fromElements(twist, alpha, 0.0, 1.0);
        FloatArray tzDiag = new FloatArray(4); tzDiag.init(0f);
        IntArray matc = IntArray.fromElements(0, 7, 0), counts = IntArray.fromElements(1, 0, 0, 1);
        TwoBodyBeamAnalyticGpu.matTargetZone(boundSeg, prevBound, justBound, outGeom, filCoord, filUVec, filYVec,
                segLen, bindArc, bindAzim, bindPsi0, tzP, tzDiag, matc, counts);
        return new double[]{ tzDiag.get(0), tzDiag.get(1), tzDiag.get(2), bindAzim.get(0) };
    }
    /** deltaPsi for a head whose ⊥ binding direction is rotated by theta from the presented normal about +u. */
    static double dpsiRot(double[] u, double[] y, double theta, double arc, double slen, double twist) {
        double[] z = cross(u, y);
        double[] m = { Math.cos(theta) * y[0] + Math.sin(theta) * z[0],
                       Math.cos(theta) * y[1] + Math.sin(theta) * z[1],
                       Math.cos(theta) * y[2] + Math.sin(theta) * z[2] };
        return probe(u, y, new double[]{ 0, 0, 0 }, slen, arc, m, 0.0, twist)[0];
    }

    static double[] cross(double[] a, double[] b) { return new double[]{ a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0] }; }
    static double[] rot(double[] v, double[] axis, double th) {   // Rodrigues
        double c = Math.cos(th), s = Math.sin(th); double[] k = cross(axis, v);
        double d = axis[0]*v[0]+axis[1]*v[1]+axis[2]*v[2];
        return new double[]{ v[0]*c + k[0]*s + axis[0]*d*(1-c), v[1]*c + k[1]*s + axis[1]*d*(1-c), v[2]*c + k[2]*s + axis[2]*d*(1-c) };
    }
    static double[] mirrorZ(double[] v) { return new double[]{ v[0], v[1], -v[2] }; }
    static double wrapPi(double a) { double T = 2*Math.PI; a = a - T*Math.floor((a+Math.PI)/T); if (a > Math.PI) a -= T; return a; }

    // ================================================================= Stage-A deterministic fixtures
    static int passN, failN;
    static void ck(int id, String name, boolean p) {
        System.out.printf("  [%2d] %-64s %s%n", id, name, p ? "PASS" : "*** FAIL ***"); if (p) passN++; else failN++;
    }

    static boolean runFixtures() {
        passN = failN = 0;
        System.out.println("\n--- STAGE A DETERMINISTIC FIXTURES (target-zone mismatch, weight, symmetry, kinetics) ---");
        double[] ux = { 1, 0, 0 }, uy = { 0, 1, 0 };
        double slen = 0.175, half = 0.5 * slen, arc0 = half;   // arc = half ⇒ φ = 0 ⇒ nActin == segY

        // 1 + 12 — alphaPsi = 0 identity against the canonical (surface-OFF) path.
        double[] hCanon = trajHash(false, 0.0, false, 300);
        double[] hTZ0   = trajHash(true,  0.0, false, 300);
        ck(1, "alphaPsi=0 ⇒ trajectory bit-identical to the canonical surface-OFF path",
                hCanon[0] == hTZ0[0] && hCanon[1] == hTZ0[1] && hTZ0[2] == 0);
        ck(12, "alphaPsi=0 ⇒ identical binding decisions (bound-set hash over 300 steps)", hCanon[3] == hTZ0[3]);

        // 2 — perfectly aligned frames give deltaPsi = 0
        double d0 = dpsiRot(ux, uy, 0.0, arc0, slen, TWIST);
        ck(2, String.format(Locale.US, "aligned frames ⇒ deltaPsi = 0 (got %.3e rad)", d0), Math.abs(d0) < 1e-6);

        // 3 — signed opposite for ± imposed rotations
        double dp = dpsiRot(ux, uy, +0.7, arc0, slen, TWIST), dm = dpsiRot(ux, uy, -0.7, arc0, slen, TWIST);
        ck(3, String.format(Locale.US, "±0.7 rad ⇒ signed opposite mismatch (%.4f / %.4f)", dp, dm),
                Math.abs(dp - 0.7) < 2e-3 && Math.abs(dm + 0.7) < 2e-3);

        // 4 — wrapping near ±π is continuous
        double eps = 1e-3;
        double a = dpsiRot(ux, uy, Math.PI - eps, arc0, slen, TWIST);
        double b = dpsiRot(ux, uy, Math.PI + eps, arc0, slen, TWIST);
        ck(4, String.format(Locale.US, "wrap near ±π continuous (%.5f → %.5f, |Δ| via wrap = %.2e)", a, b, Math.abs(wrapPi(b - a))),
                Math.abs(wrapPi(b - a)) < 5e-3 && a > 3.0 && b < -3.0);

        // 5 — rigid laboratory rotation leaves deltaPsi unchanged
        double[] axis = nrm(new double[]{ 0.31, -0.72, 0.62 }); double th = 1.234;
        double ref = dpsiRot(ux, uy, 0.55, arc0, slen, TWIST);
        double[] uR = rot(ux, axis, th), yR = rot(uy, axis, th);
        double rotd = dpsiRot(uR, yR, 0.55, arc0, slen, TWIST);
        ck(5, String.format(Locale.US, "rigid lab rotation ⇒ deltaPsi invariant (%.6f vs %.6f)", ref, rotd), Math.abs(ref - rotd) < 2e-4);

        // 6 — filament ROLL changes the target-zone phase through the material frame: y → rot(y,u,δ) ⇒ Δψ → Δψ − δ
        double delta = 0.4; double[] yRoll = rot(uy, ux, delta);
        double dRoll = dpsiRot2(ux, yRoll, uy, 0.55, arc0, slen, TWIST);   // head direction held FIXED in the lab
        ck(6, String.format(Locale.US, "filament roll δ=0.4 ⇒ deltaPsi shifts by −δ (%.4f → %.4f)", ref, dRoll),
                Math.abs(wrapPi(dRoll - (ref - delta))) < 2e-3);

        // 7 — polarity reversal (û → −û) flips the sign of the mismatch coordinate
        double[] uNeg = { -1, 0, 0 };
        double dPol = dpsiRot2(uNeg, uy, uy, 0.55, arc0, slen, TWIST);
        ck(7, String.format(Locale.US, "polarity reversal û→−û ⇒ deltaPsi flips sign (%.4f vs %.4f)", ref, dPol),
                Math.abs(dPol + ref) < 2e-3);

        // 8 — mirroring the scene reverses handedness ⇒ deltaPsi flips sign
        double[] zAx = cross(ux, uy);
        double[] mDir = { Math.cos(0.55)*uy[0] + Math.sin(0.55)*zAx[0], Math.cos(0.55)*uy[1] + Math.sin(0.55)*zAx[1], Math.cos(0.55)*uy[2] + Math.sin(0.55)*zAx[2] };
        double dMir = probe(mirrorZ(ux), mirrorZ(uy), new double[]{0,0,0}, slen, arc0, mirrorZ(mDir), 0.0, TWIST)[0];
        ck(8, String.format(Locale.US, "mirror (z→−z) ⇒ deltaPsi flips sign (%.4f vs %.4f)", ref, dMir), Math.abs(dMir + ref) < 2e-3);

        // 9/10 — angular weight symmetric in |deltaPsi| and monotone decreasing
        double[] ths = { 0.0, 0.2, 0.4, 0.8, 1.2, 1.8, 2.6 };
        boolean sym = true, mono = true; double prev = 2;
        StringBuilder wp = new StringBuilder();
        for (double t : ths) {
            double wPos = probeW(ux, uy, +t, arc0, slen, 6.0), wNeg = probeW(ux, uy, -t, arc0, slen, 6.0);
            if (Math.abs(wPos - wNeg) > 1e-6) sym = false;
            if (wPos > prev + 1e-9) mono = false; prev = wPos;
            wp.append(String.format(Locale.US, " %.3f", wPos));
        }
        System.out.println("       (alphaPsi=6 weight profile at |Δψ| = 0,0.2,0.4,0.8,1.2,1.8,2.6 rad:" + wp + ")");
        ck(9,  "angular weight symmetric in mismatch magnitude", sym);
        ck(10, "angular weight decreases monotonically with |deltaPsi|", mono);

        // 11 — CPU vs GPU on the mismatch + weight (device-resident batch)
        ck(11, "CPU ≡ GPU: decisions EXACT, mismatch/weight at float32 last-bit (device)", cpuGpuMismatchBatch());

        // 13 — large alphaPsi narrows the zone WITHOUT an absolute-side preference
        ck(13, "large alphaPsi narrows the target zone; no absolute-lab-azimuth preference", noAbsoluteSidePreference());

        // 14/15/16 — the kinematic moving-target-zone rig (flux asymmetry, translation reversal, depletion control)
        kinematicFixtures();

        System.out.printf("Stage-A fixtures: %d PASS, %d FAIL%n", passN, failN);
        return failN == 0;
    }

    /** As dpsiRot but with the head direction built from an INDEPENDENT reference frame (yRef), so a change of the
     *  filament material frame (roll) or polarity does NOT move the head. */
    static double dpsiRot2(double[] u, double[] y, double[] yRef, double theta, double arc, double slen, double twist) {
        double[] zRef = cross(new double[]{1,0,0}, yRef);
        double[] m = { Math.cos(theta)*yRef[0] + Math.sin(theta)*zRef[0],
                       Math.cos(theta)*yRef[1] + Math.sin(theta)*zRef[1],
                       Math.cos(theta)*yRef[2] + Math.sin(theta)*zRef[2] };
        return probe(u, y, new double[]{0,0,0}, slen, arc, m, 0.0, twist)[0];
    }
    static double probeW(double[] u, double[] y, double theta, double arc, double slen, double alpha) {
        double[] z = cross(u, y);
        double[] m = { Math.cos(theta)*y[0] + Math.sin(theta)*z[0], Math.cos(theta)*y[1] + Math.sin(theta)*z[1], Math.cos(theta)*y[2] + Math.sin(theta)*z[2] };
        return probe(u, y, new double[]{0,0,0}, slen, arc, m, alpha, TWIST)[1];
    }
    static double[] nrm(double[] v) { double l = Math.sqrt(v[0]*v[0]+v[1]*v[1]+v[2]*v[2]); return new double[]{ v[0]/l, v[1]/l, v[2]/l }; }

    /** Rotationally-covariant check: the accept/reject decision for a FIXED RELATIVE configuration must be
     *  identical under every rigid rotation of the whole scene (an absolute-azimuth rule would flip). */
    static boolean noAbsoluteSidePreference() {
        double slen = 0.175, half = 0.5*slen;
        double[] axis = nrm(new double[]{ 0.2, 0.5, -0.84 });
        double narrowW = probeW(new double[]{1,0,0}, new double[]{0,1,0}, 0.9, half, slen, 8.0);
        double wideW   = probeW(new double[]{1,0,0}, new double[]{0,1,0}, 0.9, half, slen, 2.0);
        if (!(narrowW < wideW)) return false;                       // larger alpha ⇒ narrower zone
        double ref = -1; boolean allSame = true;
        for (int i = 0; i < 12; i++) {
            double th = i * Math.PI / 6.0;
            double[] u = rot(new double[]{1,0,0}, axis, th), y = rot(new double[]{0,1,0}, axis, th);
            double w = probeW(u, y, 0.9, half, slen, 8.0);
            if (ref < 0) ref = w; else if (Math.abs(w - ref) > 1e-4) allSame = false;
        }
        return allSame;
    }

    /** Device-resident batch evaluation of matTargetZone vs the CPU runner on identical constructed inputs. */
    static boolean cpuGpuMismatchBatch() {
        int N = 64, nSeg = 1; double slen = 0.175, half = 0.5*slen;
        IntArray bsC = new IntArray(N), pbC = new IntArray(N), jbC = new IntArray(N);
        IntArray bsD = new IntArray(N), pbD = new IntArray(N), jbD = new IntArray(N);
        DoubleArray ogC = new DoubleArray(9*N), ogD = new DoubleArray(9*N);
        FloatArray arcC = new FloatArray(N), arcD = new FloatArray(N);
        FloatArray azC = new FloatArray(N), azD = new FloatArray(N), p0C = new FloatArray(N), p0D = new FloatArray(N);
        FloatArray dgC = new FloatArray(4*N), dgD = new FloatArray(4*N);
        for (int m = 0; m < N; m++) {
            bsC.set(m, 0); bsD.set(m, 0); pbC.set(m, -1); pbD.set(m, -1);
            double th = -Math.PI + 2*Math.PI*(m + 0.5)/N;
            double[] mh = { 0, Math.cos(th), Math.sin(th) };
            for (int k = 0; k < 3; k++) { ogC.set((3+k)*N+m, 0.0); ogC.set((6+k)*N+m, -0.01*mh[k]);
                                          ogD.set((3+k)*N+m, 0.0); ogD.set((6+k)*N+m, -0.01*mh[k]); }
            float ar = (float) (half + 0.0004*(m - N/2.0));
            arcC.set(m, ar); arcD.set(m, ar);
        }
        FloatArray fc = FloatArray.fromElements(0f,0f,0f), fu = FloatArray.fromElements(1f,0f,0f), fy = FloatArray.fromElements(0f,1f,0f);
        FloatArray sl = FloatArray.fromElements((float) slen);
        DoubleArray tzP = DoubleArray.fromElements(TWIST, 6.0, 0.0, 1.0);
        IntArray matc = IntArray.fromElements(3, 11, 0), counts = IntArray.fromElements(N, 0, 0, nSeg);
        TwoBodyBeamAnalyticGpu.matTargetZone(bsC, pbC, jbC, ogC, fc, fu, fy, sl, arcC, azC, p0C, tzP, dgC, matc, counts);
        try {
            TaskGraph tg = new TaskGraph("tzEq")
                .transferToDevice(DataTransferMode.FIRST_EXECUTION, bsD, pbD, jbD, ogD, fc, fu, fy, sl, arcD, azD, p0D, tzP, dgD, matc, counts)
                .task("tz", TwoBodyBeamAnalyticGpu::matTargetZone, bsD, pbD, jbD, ogD, fc, fu, fy, sl, arcD, azD, p0D, tzP, dgD, matc, counts)
                .transferToHost(DataTransferMode.UNDER_DEMAND, bsD, azD, p0D, dgD);
            GridScheduler sc = new GridScheduler();
            WorkerGrid1D w = new WorkerGrid1D(((N + 63)/64)*64); w.setLocalWork(64,1,1); sc.addWorkerGrid("tzEq.tz", w);
            new TornadoExecutionPlan(tg.snapshot()).withGridScheduler(sc).execute().transferToHost(bsD, azD, p0D, dgD);
        } catch (Throwable ex) {
            System.out.println("       (device graph did not lower: " + oneLine(root(ex).getMessage()) + ")"); return false;
        }
        int dB = 0, dA = 0; double dD = 0, dW = 0, dZ = 0, dP0 = 0;
        for (int m = 0; m < N; m++) {
            if (bsC.get(m) != bsD.get(m)) dB++;
            if (dgC.get(4*m+2) != dgD.get(4*m+2)) dA++;
            dD = Math.max(dD, Math.abs(dgC.get(4*m)   - dgD.get(4*m)));
            dW = Math.max(dW, Math.abs(dgC.get(4*m+1) - dgD.get(4*m+1)));
            dZ = Math.max(dZ, Math.abs(azC.get(m) - azD.get(m)));
            dP0 = Math.max(dP0, Math.abs(p0C.get(m) - p0D.get(m)));
        }
        System.out.printf(Locale.US, "       (N=%d candidates spanning the full (−π,π] mismatch circle: Δbound=%d Δaccept=%d"
                + " max|ΔdeltaPsi|=%.2e max|Δregistry|=%.2e max|Δw|=%.2e max|Δazim|=%.2e)%n", N, dB, dA, dD, dP0, dW, dZ);
        // GATE (the project's documented CPU/GPU standard for a transcendental-bearing kernel): the DECISIONS —
        // which candidate binds, which is accepted, and the retained material azimuth — must be EXACTLY identical;
        // the continuous mismatch/weight agree to float32 last-bit. deltaPsi is refined by a sin/cos Newton step
        // (tzAngle), whose host-JIT and PTX libm differ in the last bits, so literal bit-identity of the ANGLE is
        // not attainable on this toolchain and has never been the standard (cf. bondForcesSurface ΔF 4.3e-19).
        return dB == 0 && dA == 0 && dZ == 0 && dD < 1e-5 && dP0 < 1e-5 && dW < 1e-5;
    }

    // ---------------- kinematic moving-target-zone rig (fixtures 14/15/16) ----------------
    /**
     * A deterministic, dynamics-free rig that isolates the target-zone kinetics: a straight filament is
     * TRANSLATED at a constant imposed velocity past a fixed bed of motors whose head geometry is fixed. Every
     * step each free motor is offered as a geometric candidate (the canonical gate is emulated by direct
     * assignment), matTargetZone applies the angular hazard, and an accepted head is held for a fixed dwell
     * (depletion ON) or released immediately (depletion OFF).
     * @return {meanDeltaPsiAtAttachment, leadingFrac, accepted, candidates}
     */
    static double[] kinematicRun(double vUmPerS, double alpha, boolean deplete, int steps, int nMot) {
        double slen = 0.6, half = 0.5*slen, dt = DT;
        int dwell = 40;
        IntArray boundSeg = new IntArray(nMot), prevBound = new IntArray(nMot), justBound = new IntArray(nMot);
        int[] holdUntil = new int[nMot];
        DoubleArray outGeom = new DoubleArray(9*nMot);
        FloatArray bindArc = new FloatArray(nMot), bindAzim = new FloatArray(nMot), bindPsi0 = new FloatArray(nMot);
        FloatArray tzDiag = new FloatArray(4*nMot);
        DoubleArray tzP = DoubleArray.fromElements(TWIST, alpha, 0.0, 1.0);
        IntArray counts = IntArray.fromElements(nMot, 0, 0, 1), matc = IntArray.fromElements(0, 5, 0);
        FloatArray filCoord = FloatArray.fromElements(0f,0f,0f), filU = FloatArray.fromElements(1f,0f,0f),
                   filY = FloatArray.fromElements(0f,1f,0f), segLen = FloatArray.fromElements((float) slen);
        // motors: evenly spaced along x under the filament, head axis pointing +y (so mHat = +y ⇒ preferred
        // presentation is the +y face) — a single shared motor orientation, as in the real explicit-S2 scene.
        double[] mh = { 0, 1, 0 };
        double[] motX = new double[nMot];
        for (int m = 0; m < nMot; m++) {
            motX[m] = -0.25 + 0.5*(m + 0.5)/nMot;
            for (int k = 0; k < 3; k++) { outGeom.set((3+k)*nMot+m, 0.0); outGeom.set((6+k)*nMot+m, -0.01*mh[k]); }
            boundSeg.set(m, -1); prevBound.set(m, -1); holdUntil[m] = -1;
        }
        double sumD = 0; int nAcc = 0, nCand = 0, nLead = 0;
        double D = TWIST * vUmPerS;                                    // drift rate of deltaPsi (rad/s)
        double sgnD = D > 0 ? 1 : (D < 0 ? -1 : 0);
        for (int t = 0; t < steps; t++) {
            double shift = vUmPerS * t * dt;                            // filament translation along +x
            filCoord.set(0, (float) shift);
            matc.set(0, t);
            for (int m = 0; m < nMot; m++) {
                if (boundSeg.get(m) >= 0) { if (t >= holdUntil[m]) { boundSeg.set(m, -1); prevBound.set(m, -1); } continue; }
                double foot = motX[m] - shift;                          // (xF8 − c)·û with xF8 at x = motX[m]
                if (foot < -half || foot > half) continue;
                boundSeg.set(m, 0); prevBound.set(m, -1); bindArc.set(m, (float) (foot + half));
            }
            TwoBodyBeamAnalyticGpu.matTargetZone(boundSeg, prevBound, justBound, outGeom, filCoord, filU, filY,
                    segLen, bindArc, bindAzim, bindPsi0, tzP, tzDiag, matc, counts);
            for (int m = 0; m < nMot; m++) {
                if (tzDiag.get(4*m+3) != 1f) continue;
                nCand++;
                if (tzDiag.get(4*m+2) == 1f) {
                    double d = tzDiag.get(4*m);
                    sumD += d; nAcc++;
                    if (sgnD * d < 0) nLead++;                          // leading = approaching registry
                    holdUntil[m] = deplete ? t + dwell : t;             // depletion OFF ⇒ free again next step
                }
            }
        }
        return new double[]{ nAcc > 0 ? sumD/nAcc : 0, nAcc > 0 ? (double) nLead/nAcc : 0, nAcc, nCand };
    }

    static void kinematicFixtures() {
        int steps = 60000, nMot = 64; double v = 2.5;                   // µm/s, the gliding scale
        double[] fwd  = kinematicRun(+v, 6.0, true,  steps, nMot);
        double[] rev  = kinematicRun(-v, 6.0, true,  steps, nMot);
        double[] zero = kinematicRun(0.0, 6.0, true, steps, nMot);
        double[] nodep = kinematicRun(+v, 6.0, false, steps, nMot);
        System.out.printf(Locale.US, "       (kinematic rig, alphaPsi=6, v=%.1f µm/s: ⟨Δψ⟩ fwd=%+.4f rev=%+.4f v0=%+.4f nodeplete=%+.4f rad ;"
                + " leading frac fwd=%.3f rev=%.3f v0=%.3f nodeplete=%.3f ; accepted %d/%d/%d/%d)%n",
                v, fwd[0], rev[0], zero[0], nodep[0], fwd[1], rev[1], zero[1], nodep[1],
                (int) fwd[2], (int) rev[2], (int) zero[2], (int) nodep[2]);
        ck(14, String.format(Locale.US, "no translation ⇒ zero mean signed attachment phase (⟨Δψ⟩=%+.4f)", zero[0]),
                Math.abs(zero[0]) < 0.02);
        ck(15, String.format(Locale.US, "reversing translation reverses the flux asymmetry (⟨Δψ⟩ %+.4f → %+.4f)", fwd[0], rev[0]),
                Math.abs(fwd[0]) > 0.05 && fwd[0]*rev[0] < 0 && Math.abs(Math.abs(fwd[0]) - Math.abs(rev[0])) < 0.4*Math.abs(fwd[0]));
        ck(16, String.format(Locale.US, "disabling depletion removes the flux asymmetry (|⟨Δψ⟩| %.4f → %.4f)", Math.abs(fwd[0]), Math.abs(nodep[0])),
                Math.abs(nodep[0]) < 0.35*Math.abs(fwd[0]));
    }

    /** Run K steps of an arm on the CPU runner; return {coordHash, redOutHash, nanFlag, boundHash}. */
    static double[] trajHash(boolean tzOn, double alpha, boolean surface, int K) {
        setTZ(tzOn, alpha); setSurface(surface, R_NM, false, 0);
        Glide2D G = build(111); FilamentStore f = G.fil;
        ExplicitCompleteMatHarness.ExMat e = ExplicitCompleteMatHarness.packExMat(G, 1);
        double hb = 0;
        for (int t = 0; t < K; t++) { ExplicitCompleteMatHarness.stepGlidingCPU(e, t, 111);
            for (int m = 0; m < G.N; m++) hb = hb*1.0000001 + G.mot.boundSeg.get(m); }
        double hc = 0, hr = 0; boolean nan = false;
        for (int i = 0; i < 3*G.nSeg; i++) { float v = f.coord.get(i); if (!Float.isFinite(v)) nan = true; hc = hc*1.0000001 + v; }
        for (int i = 0; i < 6; i++) hr = hr*1.0000001 + e.redOut.get(i);
        setTZ(false, 0);
        return new double[]{ hc, hr, nan ? 1 : 0, hb };
    }

    // ================================================================= GPU residency + equivalence
    static boolean runEquiv() {
        System.out.println("\n--- FULL TARGET-ZONE GLIDING GRAPH — DEVICE RESIDENCY + CPU/GPU EQUIVALENCE ---");
        System.out.println("  (requires -Dtornado.enable.fma=false and -Dtornado.recover.bailout=false ⇒ no silent fallback)");
        boolean ok = true;
        ok &= fullGraphEquiv(false, 0.0, "surface OFF, alphaPsi=0 (canonical identity)");
        ok &= fullGraphEquiv(true, ALPHA, String.format(Locale.US, "surface ON, alphaPsi=%.1f (target zone)", ALPHA));
        return ok;
    }
    static boolean fullGraphEquiv(boolean surface, double alpha, String label) {
        setTZ(true, alpha); setSurface(surface, R_NM, surface, EXCL_NM);
        Glide2D Gc = build(101), Gd = build(101);
        var ec = ExplicitCompleteMatHarness.packExMat(Gc, 1);
        var ed = ExplicitCompleteMatHarness.packExMat(Gd, 1);
        TornadoExecutionPlan plan;
        try { plan = ExplicitCompleteMatHarness.buildGlidingGraph(ed, false); }
        catch (Throwable ex) { System.out.println("  " + label + ": graph did NOT lower: " + oneLine(root(ex).getMessage())); setTZ(false,0); return false; }
        int K = 200, firstDiv = -1, bindMism = 0, accMism = 0; double maxFil = 0, maxPsi = 0;
        for (int t = 0; t < K; t++) {
            ed.matc.set(0, t); ed.matc.set(1, 101); Gd.mot.setCounts(t, 101, Gd.nSeg); Gd.fil.counts.set(1, t); Gd.fil.counts.set(2, 101);
            try { plan.execute(); } catch (Throwable ex) { System.out.println("  " + label + ": device execute FAILED @t=" + t + ": " + oneLine(root(ex).getMessage())); setTZ(false,0); return false; }
            ExplicitCompleteMatHarness.stepGlidingCPU(ec, t, 101);
            double dFil = 0; for (int i = 0; i < 3*Gc.nSeg; i++) dFil = Math.max(dFil, Math.abs(Gc.fil.coord.get(i) - Gd.fil.coord.get(i)));
            if (firstDiv < 0 && dFil > 1e-6) firstDiv = t;
            maxFil = Math.max(maxFil, dFil);
            if (firstDiv < 0) {   // bit-close window: attachment events + retained registry must be IDENTICAL
                for (int m = 0; m < Gc.N; m++) {
                    if (Gc.mot.boundSeg.get(m) != Gd.mot.boundSeg.get(m)) bindMism++;
                    if (ec.tzDiag.get(4*m+2) != ed.tzDiag.get(4*m+2)) accMism++;
                    maxPsi = Math.max(maxPsi, Math.abs(Gc.mot.bindPsi0.get(m) - Gd.mot.bindPsi0.get(m)));
                }
            }
        }
        boolean fin = true; for (int i = 0; i < 3*Gc.nSeg; i++) if (!Float.isFinite(Gd.fil.coord.get(i))) fin = false;
        int nbC = 0, nbD = 0; for (int m = 0; m < Gc.N; m++) { if (Gc.mot.boundSeg.get(m) >= 0) nbC++; if (Gd.mot.boundSeg.get(m) >= 0) nbD++; }
        // GATE: within the bit-close window the ATTACHMENT EVENTS and the accept decisions must be EXACTLY
        // identical CPU↔GPU (bindMism/acceptMism = 0) and the retained registry must agree to float32 last-bit
        // (the sin/cos Newton refinement in tzAngle differs in the last bits between host JIT and PTX libm).
        // Beyond firstDiv the trajectory decorrelates by float op-order — the documented explicit-S2 standard.
        boolean ok = fin && bindMism == 0 && accMism == 0 && maxPsi < 1e-5 && Double.isFinite(maxFil);
        System.out.printf(Locale.US, "  %-42s %d device-resident steps: bindMism=%d acceptMism=%d max|Δpsi0|=%.1e ; max|ΔfilCoord|=%.2e µm ; firstDiv=%s ; bound CPU=%d GPU=%d ⇒ %s%n",
                label, K, bindMism, accMism, maxPsi, maxFil,
                firstDiv < 0 ? "none (bit-close)" : ("t=" + firstDiv + " (chaotic float op-order)"), nbC, nbD, ok ? "PASS" : "*FAIL*");
        setTZ(false, 0);
        return ok;
    }
    static Throwable root(Throwable t) { while (t.getCause() != null && t.getCause() != t) t = t.getCause(); return t; }
    static String oneLine(String s) { return s == null ? "null" : s.replace('\n', ' '); }

    // ================================================================= Stage-A dynamic experiment
    static final int NB = 12;   // phase bins over (−π,π]

    static final class Arm {
        double glide, avgBound, meanTurns, turnsSpread, turnsPerUm;
        double meanDpsi, leadFrac, accFrac;
        double meanDpsiCand, leadFracCand, meanDpsiHaz;
        double semDpsi, semLead, semBias, bias, semTau, semGlide; int nSignAgree, nSeeds;
        double phaseStep, phaseAxial, driftStep; long phasePairs; double semTurns, semTpu;
        double tauNet, tauAbs, cancel;
        long cand, acc;
        int[] binCand = new int[NB], binAcc = new int[NB];
        double[] binW = new double[NB];
        int[] azHist = new int[8];
        double sgnD;
    }

    /** One dynamic gliding arm with the full target-zone + twirl observable set (CPU runner unless -gpu). */
    static Arm runArm(boolean tzOn, double alpha, boolean surface, boolean steric, int seed, int steps) {
        setTZ(tzOn, alpha); setSurface(surface, R_NM, steric, EXCL_NM);
        ExplicitCompleteMatHarness.TELEMETRY = true;   // measurement-only readback (no kernel/physics change)
        Glide2D G = build(seed); FilamentStore f = G.fil; int nSeg = G.nSeg;
        var e = ExplicitCompleteMatHarness.packExMat(G, 1);
        double[] bhat = G.bhat;
        TornadoExecutionPlan plan = null;
        if (USE_GPU) {
            try { plan = ExplicitCompleteMatHarness.buildGlidingGraph(e, true); }
            catch (Throwable ex) { System.out.println("  (GPU graph did not lower — falling back is NOT permitted; aborting arm) " + oneLine(root(ex).getMessage())); throw new IllegalStateException(ex); }
        }
        Arm o = new Arm();
        // PHASE-DECORRELATION diagnostic: for a head that is a geometric candidate on two CONSECUTIVE steps,
        // how much does its target-zone phase move per step, and how much of that is the axial-position jitter
        // (phi = twistRate·(bindArc−½segLen) ⇒ 1 nm of axial jitter is ~1.08 rad of phase)? Compared against the
        // DETERMINISTIC sliding drift |D|·dt = |twistRate·v|·dt. If noise ≫ drift the moving target zone is
        // sampled at random phase and no leading/trailing asymmetry can survive.
        double[] prevD = new double[G.N], prevArc = new double[G.N];
        boolean[] prevCand = new boolean[G.N];
        double phaseAcc = 0, axialAcc = 0; long phaseN = 0;
        double[] segRoll = new double[nSeg], prevRoll = new double[nSeg];
        for (int s = 0; s < nSeg; s++) prevRoll[s] = rollAngle(f, s, bhat);
        // least-squares glide slope on centroid·b̂
        double sT = 0, sY = 0, sTT = 0, sTY = 0; long nS = 0;
        double boundSum = 0; int boundN = 0;
        double tauNetAcc = 0, tauAbsAcc = 0; long tauSamp = 0;
        for (int t = 0; t < steps; t++) {
            if (plan != null) {
                e.matc.set(0, t); e.matc.set(1, seed); G.mot.setCounts(t, seed, nSeg); f.counts.set(1, t); f.counts.set(2, seed);
                plan.execute();
            } else ExplicitCompleteMatHarness.stepGlidingCPU(e, t, seed);
            for (int s = 0; s < nSeg; s++) { double r = rollAngle(f, s, bhat); segRoll[s] += wrapPi(r - prevRoll[s]); prevRoll[s] = r; }
            if (t % 10 == 0) { double y = centroidDot(f, bhat), x = t*DT; sT += x; sY += y; sTT += x*x; sTY += x*y; nS++; }
            if (tzOn) for (int m = 0; m < G.N; m++) {
                boolean isCand = e.tzDiag.get(4*m+3) == 1f;
                if (isCand) {
                    double dNow = e.tzDiag.get(4*m), aNow = G.mot.bindArc.get(m);
                    if (prevCand[m]) { phaseAcc += Math.abs(wrapPi(dNow - prevD[m]));
                                       axialAcc += Math.abs(wrapPi(TWIST*(aNow - prevArc[m]))); phaseN++; }
                    prevD[m] = dNow; prevArc[m] = aNow;
                }
                prevCand[m] = isCand;
                if (!isCand) continue;
                double d = e.tzDiag.get(4*m); int b = bin(d);
                o.cand++; o.binCand[b]++; o.binW[b] += e.tzDiag.get(4*m+1); o.meanDpsiCand += d;
                if (e.tzDiag.get(4*m+2) == 1f) { o.acc++; o.binAcc[b]++; o.meanDpsi += d; }
            }
            double stepNet = 0, stepAbs = 0; boolean any = false;
            for (int m = 0; m < G.N; m++) { int s = G.mot.boundSeg.get(m); if (s < 0) continue; any = true;
                double ux = f.uVec.get(s), uy = f.uVec.get(nSeg+s), uz = f.uVec.get(2*nSeg+s);
                int d = m*CrossBridgeSystem.STRIDE;
                double tau = G.bondData.get(d+9)*ux + G.bondData.get(d+10)*uy + G.bondData.get(d+11)*uz;
                stepNet += tau; stepAbs += Math.abs(tau);
                if (surface && (t % 5 == 0)) { int b = (int) ((wrapPi(G.mot.bindAzim.get(m)) + Math.PI)/(2*Math.PI)*8); o.azHist[Math.max(0, Math.min(7, b))]++; } }
            if (any) { tauNetAcc += stepNet; tauAbsAcc += stepAbs; tauSamp++; }
            if ((t+1) % 200 == 0) { int b = 0; for (int m = 0; m < G.N; m++) if (G.mot.boundSeg.get(m) >= 0) b++; boundSum += b; boundN++; }
        }
        double den = nS*sTT - sT*sT;
        o.glide = den != 0 ? (nS*sTY - sT*sY)/den : 0;
        double sum = 0; for (double v : segRoll) sum += v; double mean = sum/nSeg;
        double var = 0; for (double v : segRoll) var += (v-mean)*(v-mean); var /= nSeg;
        o.meanTurns = mean/(2*Math.PI); o.turnsSpread = Math.sqrt(var)/(2*Math.PI);
        double glideUm = o.glide*steps*DT;
        o.turnsPerUm = Math.abs(glideUm) > 1e-6 ? o.meanTurns/glideUm : 0;
        o.avgBound = boundN > 0 ? boundSum/boundN : 0;
        o.tauNet = tauSamp > 0 ? tauNetAcc/tauSamp : 0;
        o.tauAbs = tauSamp > 0 ? tauAbsAcc/tauSamp : 0;
        o.cancel = Math.abs(o.tauNet) > 1e-30 ? o.tauAbs/Math.abs(o.tauNet) : (o.tauAbs > 0 ? 1e9 : 0);
        o.meanDpsi = o.acc > 0 ? o.meanDpsi/o.acc : 0;
        o.meanDpsiCand = o.cand > 0 ? o.meanDpsiCand/o.cand : 0;
        o.accFrac = o.cand > 0 ? (double) o.acc/o.cand : 0;
        double D = TWIST*o.glide; o.sgnD = D > 0 ? 1 : (D < 0 ? -1 : 0);
        long lead = 0, trail = 0, leadC = 0, trailC = 0; double hz = 0, hzW = 0;
        for (int b = 0; b < NB; b++) {
            double c = binCentre(b);
            if (o.sgnD*c < 0) { lead += o.binAcc[b]; leadC += o.binCand[b]; } else { trail += o.binAcc[b]; trailC += o.binCand[b]; }
            hz += o.binW[b]*c; hzW += o.binW[b];       // hazard-PREDICTED flux = Σ (per-candidate w) — symmetric by construction
        }
        o.leadFrac = (lead + trail) > 0 ? (double) lead/(lead + trail) : 0;
        o.leadFracCand = (leadC + trailC) > 0 ? (double) leadC/(leadC + trailC) : 0;
        o.meanDpsiHaz = hzW > 0 ? hz/hzW : 0;
        o.phasePairs = phaseN; o.phaseStep = phaseN > 0 ? phaseAcc/phaseN : 0; o.phaseAxial = phaseN > 0 ? axialAcc/phaseN : 0;
        o.driftStep = Math.abs(TWIST*o.glide)*DT;
        ExplicitCompleteMatHarness.TELEMETRY = false;
        setTZ(false, 0); setSurface(false, R_NM, false, 0);
        return o;
    }
    static int bin(double d) { int b = (int) ((wrapPi(d) + Math.PI)/(2*Math.PI)*NB); return Math.max(0, Math.min(NB-1, b)); }
    static double binCentre(int b) { return -Math.PI + 2*Math.PI*(b + 0.5)/NB; }

    static double centroidDot(FilamentStore f, double[] bhat) {
        int n = f.n; double sx = 0, sy = 0, sz = 0;
        for (int s = 0; s < n; s++) { sx += f.coord.get(s); sy += f.coord.get(n+s); sz += f.coord.get(2*n+s); }
        return (sx*bhat[0] + sy*bhat[1] + sz*bhat[2])/n;
    }
    static double rollAngle(FilamentStore f, int s, double[] ref) {
        int n = f.n;
        double ux = f.uVec.get(s), uy = f.uVec.get(n+s), uz = f.uVec.get(2*n+s);
        double yx = f.yVec.get(s), yy = f.yVec.get(n+s), yz = f.yVec.get(2*n+s);
        double d = ux*ref[0] + uy*ref[1] + uz*ref[2];
        double gx = ref[0]-d*ux, gy = ref[1]-d*uy, gz = ref[2]-d*uz;
        double gl = Math.sqrt(gx*gx+gy*gy+gz*gz);
        if (gl < 1e-9) return 0;
        gx /= gl; gy /= gl; gz /= gl;
        double hx = uy*gz-uz*gy, hy = uz*gx-ux*gz, hz = ux*gy-uy*gx;
        return Math.atan2(yx*hx+yy*hy+yz*hz, yx*gx+yy*gy+yz*gz);
    }

    static void runStageA() {
        System.out.printf("\n--- STAGE A DYNAMIC EXPERIMENT (%s runner) ---%n", USE_GPU ? "GPU device-resident" : "CPU sequential");
        int[] seeds = new int[NSEEDS]; for (int i = 0; i < NSEEDS; i++) seeds[i] = SEED + 101*i;
        double[] alphas = { 0, 4, 6, 8 };
        System.out.printf(Locale.US, "%-30s %10s %9s %8s %10s %10s %9s %9s %10s %8s %9s%n",
                "arm", "glide µm/s", "avgBound", "accFrac", "⟨Δψ⟩acc", "⟨Δψ⟩cand", "leadAcc", "leadCand", "τnet N·m", "cancel", "turns");
        Arm base = mean(seeds, false, 0, false, false);
        row("baseline: no surface, no target zone", base);
        Arm surfOnly = mean(seeds, false, 0, true, false);
        row("surface only (azimuth-blind)", surfOnly);
        Arm[] tz = new Arm[alphas.length];
        for (int i = 0; i < alphas.length; i++) {
            tz[i] = mean(seeds, true, alphas[i], true, false);
            row(String.format(Locale.US, "target zone alphaPsi=%.0f", alphas[i]), tz[i]);
        }
        Arm tzSteric = mean(seeds, true, ALPHA, true, true);
        row(String.format(Locale.US, "target zone alphaPsi=%.0f + steric", ALPHA), tzSteric);

        System.out.println("\n  PAIRED statistics (mean ± SEM over seeds; leadAcc−leadCand is the depletion-flux asymmetry):");
        row2("baseline (no surface/no TZ)", base);
        row2("surface only (azimuth-blind)", surfOnly);
        for (int i = 0; i < alphas.length; i++) row2(String.format(Locale.US, "target zone alphaPsi=%.0f", alphas[i]), tz[i]);
        row2(String.format(Locale.US, "target zone alphaPsi=%.0f + steric", ALPHA), tzSteric);

        System.out.println("\n  Per-phase attachment kinetics (alphaPsi=" + ALPHA + ", pooled over seeds; bins over Δψ ∈ (−π,π]):");
        Arm ref = null; for (int i = 0; i < alphas.length; i++) if (alphas[i] == ALPHA) ref = tz[i];
        if (ref == null) ref = tz[tz.length-1];
        phaseTable(ref);
        System.out.printf(Locale.US, "  drift sign sgn(D)=sgn(twistRate·v)=%+.0f  ⇒  leading (approaching registry) = sgn(D)·Δψ < 0%n", ref.sgnD);
        System.out.printf(Locale.US, "  accepted-azimuth histogram (8 bins, −π..π): %s%n", java.util.Arrays.toString(ref.azHist));

        System.out.println("\n  PHASE-DECORRELATION diagnostic (per-step motion of a persisting candidate's target-zone phase):");
        System.out.printf(Locale.US, "    %-26s %14s %16s %16s %12s%n", "arm", "drift |D|·dt", "observed |ΔΔψ|", "axial-jitter part", "pairs");
        for (int i = 0; i < alphas.length; i++)
            System.out.printf(Locale.US, "    alphaPsi=%-17.0f %14.5f %16.5f %16.5f %12d%n",
                    alphas[i], tz[i].driftStep, tz[i].phaseStep, tz[i].phaseAxial, tz[i].phasePairs);
        System.out.println("    (rad/step; the deterministic sliding drift must DOMINATE for a leading/trailing bias to survive)");

        System.out.println("\n  SYMMETRY CONTROLS (alphaPsi=" + ALPHA + "):");
        symmetryControls(seeds[0]);
    }
    static void row2(String label, Arm o) {
        System.out.printf(Locale.US, "%-30s glide %+7.3f±%.3f | avgB %5.2f | accFrac %.3f | ⟨Δψ⟩acc %+.4f±%.4f | ⟨Δψ⟩cand %+.4f | leadAcc−leadCand %+.4f±%.4f (%d/%d seeds agree) | τnet %+.2e±%.1e | cancel %.1f | turns %+.4f±%.4f | turns/µm %+.2f±%.2f%n",
                label, o.glide, o.semGlide, o.avgBound, o.accFrac, o.meanDpsi, o.semDpsi, o.meanDpsiCand,
                o.bias, o.semBias, o.nSignAgree, o.nSeeds, o.tauNet, o.semTau, o.cancel, o.meanTurns, o.semTurns,
                o.turnsPerUm, o.semTpu);
    }
    static void row(String label, Arm o) {
        System.out.printf(Locale.US, "%-30s %+10.3f %9.2f %8.3f %+10.4f %+10.4f %9.3f %9.3f %+10.2e %8.1f %+9.4f%n",
                label, o.glide, o.avgBound, o.accFrac, o.meanDpsi, o.meanDpsiCand, o.leadFrac, o.leadFracCand, o.tauNet, o.cancel, o.meanTurns);
    }
    static Arm mean(int[] seeds, boolean tzOn, double alpha, boolean surface, boolean steric) {
        Arm a = new Arm(); int n = seeds.length; a.nSeeds = n;
        double[] vD = new double[n], vL = new double[n], vB = new double[n], vT = new double[n], vG = new double[n];
        double[] vTu = new double[n], vTp = new double[n];
        int idx = 0;
        for (int s : seeds) {
            Arm o = runArm(tzOn, alpha, surface, steric, s, STEPS);
            vD[idx] = o.meanDpsi; vL[idx] = o.leadFrac; vB[idx] = o.leadFrac - o.leadFracCand;
            vT[idx] = o.tauNet; vG[idx] = o.glide; vTu[idx] = o.meanTurns; vTp[idx] = o.turnsPerUm; idx++;
            if (VERBOSE) System.out.printf(Locale.US, "      seed %-5d glide=%+7.3f avgB=%6.2f cand=%6d acc=%6d ⟨Δψ⟩acc=%+.4f ⟨Δψ⟩cand=%+.4f leadAcc=%.4f leadCand=%.4f τnet=%+.2e%n",
                    s, o.glide, o.avgBound, o.cand, o.acc, o.meanDpsi, o.meanDpsiCand, o.leadFrac, o.leadFracCand, o.tauNet)
                    ;
            a.glide += o.glide/n; a.avgBound += o.avgBound/n; a.meanTurns += o.meanTurns/n; a.turnsSpread += o.turnsSpread/n;
            a.turnsPerUm += o.turnsPerUm/n; a.meanDpsi += o.meanDpsi/n; a.leadFrac += o.leadFrac/n; a.accFrac += o.accFrac/n;
            a.meanDpsiCand += o.meanDpsiCand/n; a.leadFracCand += o.leadFracCand/n; a.meanDpsiHaz += o.meanDpsiHaz/n;
            a.phaseStep += o.phaseStep/n; a.phaseAxial += o.phaseAxial/n; a.driftStep += o.driftStep/n; a.phasePairs += o.phasePairs;
            a.tauNet += o.tauNet/n; a.tauAbs += o.tauAbs/n; a.cand += o.cand; a.acc += o.acc; a.sgnD = o.sgnD;
            for (int b = 0; b < NB; b++) { a.binCand[b] += o.binCand[b]; a.binAcc[b] += o.binAcc[b]; a.binW[b] += o.binW[b]; }
            for (int b = 0; b < 8; b++) a.azHist[b] += o.azHist[b];
        }
        a.cancel = Math.abs(a.tauNet) > 1e-30 ? a.tauAbs/Math.abs(a.tauNet) : 0;
        a.semDpsi = sem(vD); a.semLead = sem(vL); a.semBias = sem(vB); a.semTau = sem(vT); a.semGlide = sem(vG);
        a.semTurns = sem(vTu); a.semTpu = sem(vTp);
        a.bias = 0; for (double v : vB) a.bias += v/n;
        int agree = 0; double mref = a.bias;
        for (double v : vB) if (mref != 0 && v*mref > 0) agree++;
        a.nSignAgree = agree;
        return a;
    }
    static double sem(double[] v) {
        int n = v.length; if (n < 2) return Double.NaN;
        double m = 0; for (double x : v) m += x/n;
        double s2 = 0; for (double x : v) s2 += (x-m)*(x-m);
        return Math.sqrt(s2/(n-1)/n);
    }
    static boolean VERBOSE = true;
    static void phaseTable(Arm o) {
        System.out.printf(Locale.US, "    %8s %10s %10s %10s %10s %10s%n", "Δψ bin", "candidates", "mean w", "accepted", "flux", "lead/trail");
        for (int b = 0; b < NB; b++) {
            double c = binCentre(b);
            System.out.printf(Locale.US, "    %+8.2f %10d %10.4f %10d %10.4f %10s%n",
                    c, o.binCand[b], o.binCand[b] > 0 ? o.binW[b]/o.binCand[b] : 0, o.binAcc[b],
                    o.cand > 0 ? (double) o.binAcc[b]/o.cand : 0, o.sgnD*c < 0 ? "LEAD" : "TRAIL");
        }
    }

    /** Symmetry controls: polarity, lab rotation, no-translation, alpha=0, Brownian-off determinism. */
    static void symmetryControls(int seed) {
        // (a) alphaPsi = 0 ⇒ no bias
        Arm a0 = runArm(true, 0.0, true, false, seed, Math.min(STEPS, 2000));
        System.out.printf(Locale.US, "    alphaPsi=0 control        : ⟨Δψ⟩=%+.4f leadFrac=%.3f accFrac=%.3f τnet=%+.2e%n", a0.meanDpsi, a0.leadFrac, a0.accFrac, a0.tauNet);
        // (b) mirrored helical handedness (twistRate sign flipped) — a diagnostic; expects mirrored Δψ bias
        double save = ExplicitCompleteMatHarness.TWIST_PER_MON_DEG;
        Arm mir = runArmTwist(-save, ALPHA, seed, Math.min(STEPS, 2000));
        Arm nor = runArmTwist(save, ALPHA, seed, Math.min(STEPS, 2000));
        System.out.printf(Locale.US, "    helix handedness LEFT     : ⟨Δψ⟩=%+.4f leadFrac=%.3f τnet=%+.2e%n", nor.meanDpsi, nor.leadFrac, nor.tauNet);
        System.out.printf(Locale.US, "    helix handedness MIRRORED : ⟨Δψ⟩=%+.4f leadFrac=%.3f τnet=%+.2e%n", mir.meanDpsi, mir.leadFrac, mir.tauNet);
        // (c) kinematic no-translation + reversal (deterministic, from the fixture rig)
        double[] z = kinematicRun(0.0, ALPHA, true, 40000, 64);
        double[] p = kinematicRun(+2.5, ALPHA, true, 40000, 64);
        double[] m = kinematicRun(-2.5, ALPHA, true, 40000, 64);
        System.out.printf(Locale.US, "    kinematic v=0 / +v / −v   : ⟨Δψ⟩ = %+.4f / %+.4f / %+.4f rad (leadFrac %.3f / %.3f / %.3f)%n",
                z[0], p[0], m[0], z[1], p[1], m[1]);
    }
    /** An arm with an overridden helical twist rate (the mirrored-handedness diagnostic). */
    static Arm runArmTwist(double twistPerMonDeg, double alpha, int seed, int steps) {
        double twist = twistPerMonDeg*Math.PI/180.0/Constants.actinMonoRadius;
        setTZ(true, alpha); setSurface(true, R_NM, false, 0);
        Glide2D G = build(seed); FilamentStore f = G.fil; int nSeg = G.nSeg;
        var e = ExplicitCompleteMatHarness.packExMat(G, 1);
        e.tzP.set(0, twist); e.surfP.set(1, twist);
        Arm o = new Arm();
        double sT=0,sY=0,sTT=0,sTY=0; long nS=0; double tauNetAcc=0, tauAbsAcc=0; long tauSamp=0;
        for (int t = 0; t < steps; t++) {
            ExplicitCompleteMatHarness.stepGlidingCPU(e, t, seed);
            if (t % 10 == 0) { double y = centroidDot(f, G.bhat), x = t*DT; sT+=x; sY+=y; sTT+=x*x; sTY+=x*y; nS++; }
            for (int mm = 0; mm < G.N; mm++) {
                if (e.tzDiag.get(4*mm+3) != 1f) continue;
                double d = e.tzDiag.get(4*mm); int b = bin(d); o.cand++; o.binCand[b]++;
                if (e.tzDiag.get(4*mm+2) == 1f) { o.acc++; o.binAcc[b]++; o.meanDpsi += d; } }
            double sn=0, sa=0; boolean any=false;
            for (int mm = 0; mm < G.N; mm++) { int s = G.mot.boundSeg.get(mm); if (s < 0) continue; any=true;
                double ux=f.uVec.get(s), uy=f.uVec.get(nSeg+s), uz=f.uVec.get(2*nSeg+s); int d=mm*CrossBridgeSystem.STRIDE;
                double tau = G.bondData.get(d+9)*ux + G.bondData.get(d+10)*uy + G.bondData.get(d+11)*uz; sn+=tau; sa+=Math.abs(tau); }
            if (any) { tauNetAcc+=sn; tauAbsAcc+=sa; tauSamp++; }
        }
        double den = nS*sTT - sT*sT; o.glide = den != 0 ? (nS*sTY - sT*sY)/den : 0;
        o.meanDpsi = o.acc > 0 ? o.meanDpsi/o.acc : 0;
        o.accFrac = o.cand > 0 ? (double) o.acc/o.cand : 0;
        o.tauNet = tauSamp > 0 ? tauNetAcc/tauSamp : 0; o.tauAbs = tauSamp > 0 ? tauAbsAcc/tauSamp : 0;
        double D = twist*o.glide; o.sgnD = D > 0 ? 1 : (D < 0 ? -1 : 0);
        long lead=0, trail=0; for (int b=0;b<NB;b++){ double c=binCentre(b); if (o.sgnD*c<0) lead+=o.binAcc[b]; else trail+=o.binAcc[b]; }
        o.leadFrac = (lead+trail)>0 ? (double) lead/(lead+trail) : 0;
        setTZ(false, 0); setSurface(false, R_NM, false, 0);
        return o;
    }

    // ================================================================= timestep check
    static void runDtCheck() {
        System.out.println("\n--- TIMESTEP CHECK (physical geometry + mechanics held fixed; only dt changed) ---");
        System.out.println("  NOTE: buildS2Mat derives the beam node layout from dt-independent geometry, but the per-step");
        System.out.println("  drag/relaxation coefficients (aphi/apsi/aN) are dt-scaled inside the solver — a dt HALVING at");
        System.out.println("  MATCHED SIM TIME is therefore the correct comparison; the scene is constructed identically.");
        int stepsA = Math.min(STEPS, 4000);
        double[] r1 = dtArm(DT, stepsA, SEED);
        double[] r2 = dtArm(DT/2, stepsA*2, SEED);
        System.out.printf(Locale.US, "  dt=%.2e (%d steps): glide=%+.3f µm/s  binds=%d  ⟨Δψ⟩=%+.4f  τnet=%+.2e  turns=%+.4f%n", DT, stepsA, r1[0], (long) r1[1], r1[2], r1[3], r1[4]);
        System.out.printf(Locale.US, "  dt=%.2e (%d steps): glide=%+.3f µm/s  binds=%d  ⟨Δψ⟩=%+.4f  τnet=%+.2e  turns=%+.4f%n", DT/2, stepsA*2, r2[0], (long) r2[1], r2[2], r2[3], r2[4]);
        System.out.printf(Locale.US, "  ratios (fine/coarse): glide %.3f  binds %.3f  ⟨Δψ⟩ %.3f  τnet %.3f  turns %.3f%n",
                safeRatio(r2[0], r1[0]), safeRatio(r2[1], r1[1]), safeRatio(r2[2], r1[2]), safeRatio(r2[3], r1[3]), safeRatio(r2[4], r1[4]));
        System.out.printf(Locale.US, "  phase decorrelation: dt=%.2e drift/step=%.5f obs|ΔΔψ|=%.5f axial=%.5f ratio(noise/drift)=%.1f accFrac=%.3f%n",
                DT,   r1[5], r1[6], r1[7], safeRatio(r1[6], r1[5]), r1[8]);
        System.out.printf(Locale.US, "  phase decorrelation: dt=%.2e drift/step=%.5f obs|ΔΔψ|=%.5f axial=%.5f ratio(noise/drift)=%.1f accFrac=%.3f%n",
                DT/2, r2[5], r2[6], r2[7], safeRatio(r2[6], r2[5]), r2[8]);
    }
    static double safeRatio(double a, double b) { return Math.abs(b) > 1e-30 ? a/b : Double.NaN; }
    static double[] dtArm(double dt, int steps, int seed) {
        setTZ(true, ALPHA); setSurface(true, R_NM, false, 0);
        ExplicitCompleteMatHarness.TELEMETRY = true;
        Glide2D G = TwoBodyConverterMotor.buildS2Mat(DENSITY, dt, 40.0, TwoBodyConverterMotor.EXPLICIT_GLIDE_SLACK_NM, seed);
        FilamentStore f = G.fil; int nSeg = G.nSeg;
        var e = ExplicitCompleteMatHarness.packExMat(G, 1);
        TornadoExecutionPlan plan = null;
        if (USE_GPU) plan = ExplicitCompleteMatHarness.buildGlidingGraph(e, true);
        double sT=0,sY=0,sTT=0,sTY=0; long nS=0, acc=0, cnd=0; double sumD=0, tauAcc=0; long tauSamp=0;
        double[] prevD = new double[G.N], prevArc = new double[G.N]; boolean[] prevCand = new boolean[G.N];
        double phaseAcc = 0, axialAcc = 0; long phaseN = 0;
        double[] segRoll = new double[nSeg], prevRoll = new double[nSeg];
        for (int s = 0; s < nSeg; s++) prevRoll[s] = rollAngle(f, s, G.bhat);
        for (int t = 0; t < steps; t++) {
            if (plan != null) { e.matc.set(0, t); e.matc.set(1, seed); G.mot.setCounts(t, seed, nSeg);
                                f.counts.set(1, t); f.counts.set(2, seed); plan.execute(); }
            else ExplicitCompleteMatHarness.stepGlidingCPU(e, t, seed);
            for (int m = 0; m < G.N; m++) {
                boolean isCand = e.tzDiag.get(4*m+3) == 1f;
                if (isCand) { cnd++; double dNow = e.tzDiag.get(4*m), aNow = G.mot.bindArc.get(m);
                    if (prevCand[m]) { phaseAcc += Math.abs(wrapPi(dNow - prevD[m]));
                                       axialAcc += Math.abs(wrapPi(TWIST*(aNow - prevArc[m]))); phaseN++; }
                    prevD[m] = dNow; prevArc[m] = aNow; }
                prevCand[m] = isCand;
            }
            for (int s = 0; s < nSeg; s++) { double r = rollAngle(f, s, G.bhat); segRoll[s] += wrapPi(r - prevRoll[s]); prevRoll[s] = r; }
            if (t % 10 == 0) { double y = centroidDot(f, G.bhat), x = t*dt; sT+=x; sY+=y; sTT+=x*x; sTY+=x*y; nS++; }
            for (int m = 0; m < G.N; m++) if (e.tzDiag.get(4*m+2) == 1f) { acc++; sumD += e.tzDiag.get(4*m); }
            double sn=0; boolean any=false;
            for (int m = 0; m < G.N; m++) { int s = G.mot.boundSeg.get(m); if (s<0) continue; any=true;
                double ux=f.uVec.get(s), uy=f.uVec.get(nSeg+s), uz=f.uVec.get(2*nSeg+s); int d=m*CrossBridgeSystem.STRIDE;
                sn += G.bondData.get(d+9)*ux + G.bondData.get(d+10)*uy + G.bondData.get(d+11)*uz; }
            if (any) { tauAcc += sn; tauSamp++; }
        }
        double den = nS*sTT - sT*sT; double glide = den != 0 ? (nS*sTY - sT*sY)/den : 0;
        double sum = 0; for (double v : segRoll) sum += v;
        ExplicitCompleteMatHarness.TELEMETRY = false;
        setTZ(false, 0); setSurface(false, R_NM, false, 0);
        return new double[]{ glide, acc, acc > 0 ? sumD/acc : 0, tauSamp > 0 ? tauAcc/tauSamp : 0, sum/nSeg/(2*Math.PI),
                Math.abs(TWIST*glide)*dt, phaseN > 0 ? phaseAcc/phaseN : 0, phaseN > 0 ? axialAcc/phaseN : 0,
                acc > 0 ? (double) acc/Math.max(1, cnd) : 0 };
    }

    // ================================================================= 3js
    static void makeMovies(String baseDir) {
        System.out.println("\n--- 3js MOVIES ---");
        writeMovie(baseDir + "_blind",   false, 0,     true);
        writeMovie(baseDir + "_targetzone", true, ALPHA, true);
        System.out.println("Serve: python3 SoftBox/sim_server.py 8000 (from ~/Code); open http://localhost:8000/SoftBox/sim_viewer_boa.html");
    }
    static void writeMovie(String dir, boolean tzOn, double alpha, boolean surface) {
        setTZ(tzOn, alpha); setSurface(surface, R_NM, false, 0);
        if (CLI_POL != null) applyPolicy(CLI_POL);   // render under the selected Brownian policy (default: canonical)
        Glide2D G = build(SEED); var e = ExplicitCompleteMatHarness.packExMat(G, 1);
        new java.io.File(dir).mkdirs();
        double R = R_NM*1e-3; int frames = 0;
        for (int t = 0; t <= STEPS; t++) {
            if (t % STRIDE == 0) writeFrame(dir, frames++, t*DT, G, e, R, surface);
            if (t < STEPS) ExplicitCompleteMatHarness.stepGlidingCPU(e, t, SEED);
        }
        System.out.printf("  %-40s wrote %d frames%s%n", dir, frames,
                CLI_POL == null ? "" : ("  [Brownian policy: " + CLI_POL.spec() + "]"));
        ExplicitCompleteMatHarness.resetBrownianPolicy();
        setTZ(false, 0); setSurface(false, R_NM, false, 0);
    }
    /** v1-viewer-schema frame: actin + material-frame roll ticks + the local actin surface-normal marker at each
     *  bound site + the motor-side binding-direction marker + the off-axis cross-bridge line + motors. All frame
     *  markers are VISUALISATION ONLY (never force-bearing). */
    static void writeFrame(String dir, int frame, double time, Glide2D G, ExplicitCompleteMatHarness.ExMat e, double R, boolean surf) {
        FilamentStore f = G.fil; MotorStore mot = G.mot; int nSeg = G.nSeg;
        StringBuilder sb = new StringBuilder(8192);
        sb.append(String.format(Locale.US, "{\"frame\":%d,\"t\":%.6g,\"bounds\":{\"xDim\":4,\"yDim\":2,\"zDim\":1},\"segments\":[", frame, time));
        boolean first = true;
        for (int s = 0; s < nSeg; s++) {
            if (!first) sb.append(','); first = false;
            sb.append(String.format(Locale.US, "{\"id\":%d,\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.5g,\"notADPRatio\":1.0,\"cofilinCount\":0}",
                    s, f.end1.get(s), f.end1.get(nSeg+s), f.end1.get(2*nSeg+s), f.end2.get(s), f.end2.get(nSeg+s), f.end2.get(2*nSeg+s), Constants.radius));
        }
        for (int s = 0; s < nSeg; s++) {   // material-frame roll tick
            double cx = f.coord.get(s), cy = f.coord.get(nSeg+s), cz = f.coord.get(2*nSeg+s);
            double yx = f.yVec.get(s), yy = f.yVec.get(nSeg+s), yz = f.yVec.get(2*nSeg+s);
            sb.append(String.format(Locale.US, ",{\"id\":%d,\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.5g,\"notADPRatio\":0.0,\"cofilinCount\":0}",
                    1000+s, cx, cy, cz, cx+0.02*yx, cy+0.02*yy, cz+0.02*yz, 0.0008));
        }
        for (int m = 0; m < G.N; m++) {
            int s = mot.boundSeg.get(m); if (s < 0) continue;
            double az = surf ? mot.bindAzim.get(m) : 0f;
            double[] site = reconSite(f, s, mot.bindArc.get(m), az, surf ? R : 0.0);
            double[] axis = reconSite(f, s, mot.bindArc.get(m), az, 0.0);
            double hx = mot.body.coordX(3*m+2), hy = mot.body.coordY(3*m+2), hz = mot.body.coordZ(3*m+2);
            // cross-bridge line (head → attachment site)
            sb.append(String.format(Locale.US, ",{\"id\":%d,\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.5g,\"notADPRatio\":0.5,\"cofilinCount\":0}",
                    2000+m, hx, hy, hz, site[0], site[1], site[2], 0.0006));
            // local actin surface-NORMAL marker (axis → 3× the surface point) — the presented binding face
            sb.append(String.format(Locale.US, ",{\"id\":%d,\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.5g,\"notADPRatio\":0.25,\"cofilinCount\":0}",
                    4000+m, axis[0], axis[1], axis[2],
                    axis[0] + 3*(site[0]-axis[0]), axis[1] + 3*(site[1]-axis[1]), axis[2] + 3*(site[2]-axis[2]), 0.0005));
            // motor-side binding-direction marker (head centre along its own long axis) + mismatch colouring
            double ux = mot.body.uVec.get(3*m+2), uy = mot.body.uVec.get(mot.body.n + 3*m+2), uz = mot.body.uVec.get(2*mot.body.n + 3*m+2);
            double dpsi = Math.abs(wrapPi(mot.bindPsi0.get(m)));
            sb.append(String.format(Locale.US, ",{\"id\":%d,\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.5g,\"notADPRatio\":%.3f,\"cofilinCount\":0}",
                    5000+m, hx, hy, hz, hx+0.015*ux, hy+0.015*uy, hz+0.015*uz, 0.0005, Math.max(0.0, 1.0 - dpsi/Math.PI)));
        }
        sb.append("],\"myosins\":[");
        boolean fm = true;
        for (int m = 0; m < G.N; m++) {
            int rod = 3*m, lev = 3*m+1, head = 3*m+2;
            String state = mot.boundSeg.get(m) >= 0 ? "ADP" : "NONE";
            if (!fm) sb.append(','); fm = false;
            sb.append(String.format(Locale.US,
                "{\"id\":%d,\"rod\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g,\"invisible\":false},"
              + "\"lever\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g},"
              + "\"motor\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g,\"state\":\"%s\"}}",
                m, mot.body.end1X(rod), mot.body.end1Y(rod), mot.body.end1Z(rod), mot.body.end2X(rod), mot.body.end2Y(rod), mot.body.end2Z(rod), MotorStore.ROD_R,
                mot.body.end1X(lev), mot.body.end1Y(lev), mot.body.end1Z(lev), mot.body.end2X(lev), mot.body.end2Y(lev), mot.body.end2Z(lev), MotorStore.LEVER_R,
                mot.body.end1X(head), mot.body.end1Y(head), mot.body.end1Z(head), mot.body.end2X(head), mot.body.end2Y(head), mot.body.end2Z(head), MotorStore.HEAD_R, state));
        }
        sb.append("]}");
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(dir, String.format(Locale.US, "frame_%06d.json", frame)), sb.toString()); }
        catch (java.io.IOException ex) { throw new java.io.UncheckedIOException(ex); }
    }
    // ================================================================= BROWNIAN-NOISE ABLATION (noncanonical)
    // Which Brownian forcing channels destroy the moving-target-zone phase coherence? Controlled by (1) physical
    // body/subsystem, (2) MOTOR BINDING STATE, (3) force vs torque channel. NO new force law, torsional registry,
    // roll spring, lateral stroke, binding-axis preference, axial confinement spring, or fitted parameter is added:
    // the ONLY change is that named stochastic thermal terms are scaled to zero. See
    // docs/VILFAN_BROWNIAN_NOISE_ABLATION_FINDINGS.md.

    /** An explicit Brownian policy: four filament channels (body frame) + two motor binding-state channels. */
    record Pol(String name, boolean fAx, boolean fTr, boolean fRoll, boolean fOth, boolean mUnb, boolean mBnd) {
        String spec() {
            return String.format("fil[ax=%s tr=%s roll=%s othRot=%s] mot[unbound=%s bound=%s]",
                    fAx?"on":"off", fTr?"on":"off", fRoll?"on":"off", fOth?"on":"off", mUnb?"on":"off", mBnd?"on":"off");
        }
    }
    static final Pol POL_FULL      = new Pol("full",                true,  true,  true,  true,  true,  true);
    static final Pol POL_FILOFF    = new Pol("filament-off",        false, false, false, false, true,  true);
    static final Pol POL_SEARCHONLY= new Pol("unbound-search-only", false, false, false, false, true,  false);
    static final Pol POL_BOUNDQUIET= new Pol("bound-motor-quiet",   true,  true,  true,  true,  true,  false);
    static final Pol POL_ALLOFF    = new Pol("all-off",             false, false, false, false, false, false);
    static final Pol POL_FIL_AXIAL = new Pol("fil-axial-only",      true,  false, false, false, true,  false);
    static final Pol POL_FIL_ROLL  = new Pol("fil-roll-only",       false, false, true,  false, true,  false);
    static final Pol POL_FIL_OTHER = new Pol("fil-transverse-bend", false, true,  false, true,  true,  false);

    static Pol namedPolicy(String s) {
        return switch (s) {
            case "full" -> POL_FULL;
            case "filament-off" -> POL_FILOFF;
            case "unbound-search-only" -> POL_SEARCHONLY;
            case "bound-motor-quiet" -> POL_BOUNDQUIET;
            case "all-off" -> POL_ALLOFF;
            default -> throw new IllegalArgumentException("unknown -brownian-policy " + s
                    + " (full | filament-off | unbound-search-only | bound-motor-quiet | all-off)");
        };
    }
    /** Command-line overrides applied ON TOP of a named policy (null = not specified). */
    static Boolean OV_FAX, OV_FTR, OV_FROLL, OV_FOTH, OV_MUNB, OV_MBND;
    static Pol applyOverrides(Pol p) {
        return new Pol(p.name(), OV_FAX == null ? p.fAx() : OV_FAX, OV_FTR == null ? p.fTr() : OV_FTR,
                OV_FROLL == null ? p.fRoll() : OV_FROLL, OV_FOTH == null ? p.fOth() : OV_FOTH,
                OV_MUNB == null ? p.mUnb() : OV_MUNB, OV_MBND == null ? p.mBnd() : OV_MBND);
    }
    static void applyPolicy(Pol p) {
        ExplicitCompleteMatHarness.setBrownianPolicy(p.fAx(), p.fTr(), p.fRoll(), p.fOth(), p.mUnb(), p.mBnd());
    }

    static final int NRES = 8;   // candidate-residence histogram bins (1,2,3,4,5,6,7,>=8 steps)
    /** A consecutive-candidate pair counts as the SAME attachment site if its bindArc moved less than this.
     *  20 nm ≫ the ~1 nm/step thermal wander and ≪ the ~87 nm jump produced when the candidate's nearest-segment /
     *  perpendicular-foot ownership switches — so it separates "the same target zone was tracked" from "a
     *  different site was sampled" without tuning anything physical. */
    static final double ARC_SITE_UM = 0.020;

    /** One arm's full observable set (phase coherence + attachment flux + torque/twirl + health). */
    static final class BrArm {
        String label; Pol pol; double alpha; int nSeeds;
        double glide, semGlide, avgBound, detachRate, accFrac;
        double meanTurns, semTurns, turnsSpread, coherentRoll, turnsPerUm, semTpu;
        double meanDpsi, semDpsi, meanDpsiCand, leadAcc, leadCand, bias, semBias; int nSignAgree;
        long cand, acc, leadN, trailN, detach;
        int[] binCand = new int[NB], binAcc = new int[NB]; double[] binW = new double[NB];
        int[] azHist = new int[8];
        double sgnD;
        double tauNet, semTau, tauAbs, cancel, fracHeadPos; int nTauSignAgree;
        // phase coherence budget — "all" = every consecutive-candidate pair (comparable to the Stage-A report);
        // "site" = the subset that stayed on the SAME attachment site (|Δ bindArc| < ARC_SITE_UM), i.e. excluding
        // the pairs where the candidate's nearest-segment/perpendicular-foot ownership jumped.
        double driftStep, phAbs, phRms, phSigned, phAxial, phRoll, phResid, phResidAlt, ac1;
        double phAbsS, phRmsS, phSignedS, phAxialS, phRollS, phResidS;
        long phasePairs, phasePairsSite;
        long[] residHist = new long[NRES]; long residRuns; double residMean;
        double dAxial, dAxialMed, arcMedian, siteFrac, tCohSteps, residOverTcoh, driftOverRms, pMono;
        long monoRuns, monoOk;
        long invalid;
    }

    /**
     * One dynamic gliding arm under an explicit Brownian policy, with the full phase-coherence budget.
     * Physics identical to {@link #runArm} apart from the Brownian masks; all extra output is measurement-only.
     */
    static BrArm runBrArm(Pol pol, double alpha, int seed, int steps) {
        applyPolicy(pol);
        setTZ(true, alpha); setSurface(true, R_NM, false, EXCL_NM);
        ExplicitCompleteMatHarness.TELEMETRY = true;
        BrArm o = new BrArm(); o.pol = pol; o.alpha = alpha; o.nSeeds = 1;
        Glide2D G = build(seed); FilamentStore f = G.fil; int nSeg = G.nSeg; int N = G.N;
        var e = ExplicitCompleteMatHarness.packExMat(G, 1);
        e.matc.set(3, ExplicitCompleteMatHarness.motorBrownPolicy());
        double twistEff = TWIST;
        if (TWIST_OVERRIDE != 0) { twistEff = TWIST_OVERRIDE; e.tzP.set(0, twistEff); e.surfP.set(1, twistEff); }
        double[] bhat = G.bhat;
        TornadoExecutionPlan plan = null;
        if (USE_GPU) {
            try { plan = ExplicitCompleteMatHarness.buildGlidingGraph(e, true); }
            catch (Throwable ex) { ExplicitCompleteMatHarness.resetBrownianPolicy(); setTZ(false,0); setSurface(false,R_NM,false,0);
                throw new IllegalStateException("GPU graph did not lower (fallback NOT permitted): " + oneLine(root(ex).getMessage()), ex); }
        }
        double[] prevD = new double[N], prevArc = new double[N];
        boolean[] prevCand = new boolean[N], prevBound = new boolean[N];
        int[] runLen = new int[N]; int[] runSign = new int[N]; boolean[] runMono = new boolean[N];
        double[] segRoll = new double[nSeg], prevRoll = new double[nSeg];
        for (int s = 0; s < nSeg; s++) prevRoll[s] = rollAngle(f, s, bhat);
        double phAbs = 0, phSq = 0, phSigned = 0, phAx = 0, phRoll = 0, residA = 0, residB = 0;
        double phAbsS = 0, phSqS = 0, phSignedS = 0, phAxS = 0, phRollS = 0, residAS = 0;
        double[] prevInc = new double[N]; boolean[] hasPrevInc = new boolean[N];   // PER-MOTOR lag-1 autocorrelation
        double acNum = 0, acDen = 0;
        double arcSq = 0, arcSqS = 0; long phaseN = 0, phaseNS = 0;
        java.util.ArrayList<Double> arcMag = new java.util.ArrayList<>();
        double sT = 0, sY = 0, sTT = 0, sTY = 0; long nS = 0;
        double boundSum = 0; int boundN = 0;
        double tauNetAcc = 0, tauAbsAcc = 0; long tauSamp = 0, headPos = 0, headTot = 0;
        for (int t = 0; t < steps; t++) {
            if (plan != null) {
                e.matc.set(0, t); e.matc.set(1, seed); e.matc.set(3, ExplicitCompleteMatHarness.motorBrownPolicy());
                G.mot.setCounts(t, seed, nSeg); f.counts.set(1, t); f.counts.set(2, seed);
                plan.execute();
            } else ExplicitCompleteMatHarness.stepGlidingCPU(e, t, seed);
            // whole-filament material-frame roll increment (the roll channel of the phase budget)
            double rollInc = 0;
            for (int s = 0; s < nSeg; s++) { double r = rollAngle(f, s, bhat); double dr = wrapPi(r - prevRoll[s]);
                segRoll[s] += dr; rollInc += dr; prevRoll[s] = r; }
            rollInc /= nSeg;
            if (t % 10 == 0) { double y = centroidDot(f, bhat), x = t*DT; sT += x; sY += y; sTT += x*x; sTY += x*y; nS++; }
            for (int m = 0; m < N; m++) {
                boolean isCand = e.tzDiag.get(4*m+3) == 1f;
                if (isCand) {
                    double d = e.tzDiag.get(4*m), arc = G.mot.bindArc.get(m);
                    int b = bin(d);
                    o.cand++; o.binCand[b]++; o.binW[b] += e.tzDiag.get(4*m+1); o.meanDpsiCand += d;
                    if (e.tzDiag.get(4*m+2) == 1f) { o.acc++; o.binAcc[b]++; o.meanDpsi += d; }
                    if (prevCand[m]) {
                        double dd = wrapPi(d - prevD[m]);
                        double dArc = arc - prevArc[m];
                        double ax = -twistEff*dArc;       // axial contribution under sign convention A (verified below)
                        double ro = -rollInc;             // roll contribution under the same convention
                        phAbs += Math.abs(dd); phSq += dd*dd; phSigned += dd;
                        phAx += Math.abs(wrapPi(twistEff*dArc)); phRoll += Math.abs(ro);
                        residA += Math.abs(wrapPi(dd - ax - ro));
                        residB += Math.abs(wrapPi(dd + ax + ro));
                        arcSq += dArc*dArc; phaseN++; arcMag.add(Math.abs(dArc));
                        if (Math.abs(dArc) < ARC_SITE_UM) {   // SAME attachment site (no ownership jump)
                            phAbsS += Math.abs(dd); phSqS += dd*dd; phSignedS += dd;
                            phAxS += Math.abs(wrapPi(twistEff*dArc)); phRollS += Math.abs(ro);
                            residAS += Math.abs(wrapPi(dd - ax - ro));
                            arcSqS += dArc*dArc; phaseNS++;
                        }
                        if (hasPrevInc[m]) { acNum += dd*prevInc[m]; acDen += prevInc[m]*prevInc[m]; }
                        prevInc[m] = dd; hasPrevInc[m] = true;
                        int sg = dd > 0 ? 1 : (dd < 0 ? -1 : 0);
                        if (runLen[m] == 1) runSign[m] = sg; else if (sg != runSign[m]) runMono[m] = false;
                        runLen[m]++;
                    } else { runLen[m] = 1; runMono[m] = true; runSign[m] = 0; hasPrevInc[m] = false; }
                    prevD[m] = d; prevArc[m] = arc;
                } else if (prevCand[m]) {
                    closeRun(o, runLen[m], runMono[m]); runLen[m] = 0; hasPrevInc[m] = false;
                }
                prevCand[m] = isCand;
            }
            double stepNet = 0, stepAbs = 0; boolean any = false;
            for (int m = 0; m < N; m++) {
                int s = G.mot.boundSeg.get(m);
                boolean nowBound = s >= 0;
                if (prevBound[m] && !nowBound) o.detach++;
                prevBound[m] = nowBound;
                if (!nowBound) continue;
                any = true;
                double ux = f.uVec.get(s), uy = f.uVec.get(nSeg+s), uz = f.uVec.get(2*nSeg+s);
                int d = m*CrossBridgeSystem.STRIDE;
                double tau = G.bondData.get(d+9)*ux + G.bondData.get(d+10)*uy + G.bondData.get(d+11)*uz;
                stepNet += tau; stepAbs += Math.abs(tau);
                headTot++; if (tau > 0) headPos++;
                if (t % 5 == 0) { int b = (int) ((wrapPi(G.mot.bindAzim.get(m)) + Math.PI)/(2*Math.PI)*8); o.azHist[Math.max(0, Math.min(7, b))]++; }
            }
            if (any) { tauNetAcc += stepNet; tauAbsAcc += stepAbs; tauSamp++; }
            if ((t+1) % 200 == 0) { int b = 0; for (int m = 0; m < N; m++) if (G.mot.boundSeg.get(m) >= 0) b++; boundSum += b; boundN++; }
        }
        for (int m = 0; m < N; m++) if (runLen[m] > 0) closeRun(o, runLen[m], runMono[m]);
        for (int i = 0; i < 3*nSeg; i++) if (!Float.isFinite(f.coord.get(i))) { o.invalid++; break; }
        double den = nS*sTT - sT*sT;
        o.glide = den != 0 ? (nS*sTY - sT*sY)/den : 0;
        double sum = 0; for (double v : segRoll) sum += v; double mean = sum/nSeg;
        double var = 0; for (double v : segRoll) var += (v-mean)*(v-mean); var /= nSeg;
        o.meanTurns = mean/(2*Math.PI); o.turnsSpread = Math.sqrt(var)/(2*Math.PI);
        o.coherentRoll = o.turnsSpread > 1e-12 ? Math.abs(o.meanTurns)/o.turnsSpread : 0;
        double glideUm = o.glide*steps*DT;
        o.turnsPerUm = Math.abs(glideUm) > 1e-6 ? o.meanTurns/glideUm : 0;
        o.avgBound = boundN > 0 ? boundSum/boundN : 0;
        o.detachRate = o.detach/(double) steps;
        o.tauNet = tauSamp > 0 ? tauNetAcc/tauSamp : 0;
        o.tauAbs = tauSamp > 0 ? tauAbsAcc/tauSamp : 0;
        o.cancel = Math.abs(o.tauNet) > 1e-30 ? o.tauAbs/Math.abs(o.tauNet) : 0;
        o.fracHeadPos = headTot > 0 ? (double) headPos/headTot : 0;
        o.meanDpsi = o.acc > 0 ? o.meanDpsi/o.acc : 0;
        o.meanDpsiCand = o.cand > 0 ? o.meanDpsiCand/o.cand : 0;
        o.accFrac = o.cand > 0 ? (double) o.acc/o.cand : 0;
        double D = twistEff*o.glide; o.sgnD = D > 0 ? 1 : (D < 0 ? -1 : 0);
        long lead = 0, trail = 0, leadC = 0, trailC = 0;
        for (int b = 0; b < NB; b++) { double c = binCentre(b);
            if (o.sgnD*c < 0) { lead += o.binAcc[b]; leadC += o.binCand[b]; } else { trail += o.binAcc[b]; trailC += o.binCand[b]; } }
        o.leadN = lead; o.trailN = trail;
        o.leadAcc = (lead + trail) > 0 ? (double) lead/(lead + trail) : 0;
        o.leadCand = (leadC + trailC) > 0 ? (double) leadC/(leadC + trailC) : 0;
        o.bias = o.leadAcc - o.leadCand;
        o.phasePairs = phaseN;
        o.driftStep = Math.abs(twistEff*o.glide)*DT;
        o.phasePairsSite = phaseNS;
        if (phaseN > 0) {
            o.phAbs = phAbs/phaseN; o.phRms = Math.sqrt(phSq/phaseN); o.phSigned = phSigned/phaseN;
            o.phAxial = phAx/phaseN; o.phRoll = phRoll/phaseN;
            o.phResid = residA/phaseN; o.phResidAlt = residB/phaseN;
            o.ac1 = acDen > 0 ? acNum/acDen : 0;
            o.siteFrac = (double) phaseNS/phaseN;
            java.util.Collections.sort(arcMag);
            o.arcMedian = arcMag.get(arcMag.size()/2);
            // ROBUST axial diffusivity: for a Gaussian increment, median|Δ| = 0.6745·σ ⇒ σ = median/0.6745.
            // The mean-square estimate below is reported too, but it is dominated by the rare attachment-site
            // ownership jumps (|Δarc| ~ half a segment), so the median form is the one to read.
            double sig = o.arcMedian/0.6744897501960817;
            o.dAxialMed = sig*sig/(2*DT);
            double v2 = o.glide*o.glide;
            o.tCohSteps = v2 > 1e-12 ? (2*o.dAxialMed/v2)/DT : Double.POSITIVE_INFINITY;
            o.driftOverRms = o.phRms > 1e-12 ? o.driftStep/o.phRms : 0;
        }
        if (phaseNS > 0) {
            o.phAbsS = phAbsS/phaseNS; o.phRmsS = Math.sqrt(phSqS/phaseNS); o.phSignedS = phSignedS/phaseNS;
            o.phAxialS = phAxS/phaseNS; o.phRollS = phRollS/phaseNS; o.phResidS = residAS/phaseNS;
            o.dAxial = (arcSqS/phaseNS)/(2*DT);                     // same-site mean-square estimate
        }
        if (o.residRuns > 0) { o.residMean /= o.residRuns; o.residOverTcoh = o.tCohSteps > 0 ? o.residMean/o.tCohSteps : 0; }
        o.pMono = o.monoRuns > 0 ? (double) o.monoOk/o.monoRuns : 0;
        ExplicitCompleteMatHarness.TELEMETRY = false;
        ExplicitCompleteMatHarness.resetBrownianPolicy();
        setTZ(false, 0); setSurface(false, R_NM, false, 0);
        return o;
    }
    static void closeRun(BrArm o, int len, boolean mono) {
        if (len <= 0) return;
        o.residRuns++; o.residMean += len;
        o.residHist[Math.min(NRES-1, len-1)]++;
        if (len >= 3) { o.monoRuns++; if (mono) o.monoOk++; }
    }

    /** Seed-ensemble mean + SEM of an arm. */
    static BrArm meanBr(String label, Pol pol, double alpha, int[] seeds, int steps) {
        int n = seeds.length;
        BrArm a = new BrArm(); a.label = label; a.pol = pol; a.alpha = alpha; a.nSeeds = n;
        double[] vD = new double[n], vB = new double[n], vT = new double[n], vG = new double[n], vTu = new double[n], vTp = new double[n];
        int i = 0;
        for (int s : seeds) {
            BrArm o = runBrArm(pol, alpha, s, steps);
            vD[i] = o.meanDpsi; vB[i] = o.bias; vT[i] = o.tauNet; vG[i] = o.glide; vTu[i] = o.meanTurns; vTp[i] = o.turnsPerUm; i++;
            if (VERBOSE) System.out.printf(Locale.US, "      seed %-5d glide=%+7.3f avgB=%5.2f cand=%6d acc=%6d accF=%.3f ⟨Δψ⟩acc=%+.4f lead−cand=%+.4f τnet=%+.2e turns=%+.4f |ΔΔψ|=%.4f pairs=%d%n",
                    s, o.glide, o.avgBound, o.cand, o.acc, o.accFrac, o.meanDpsi, o.bias, o.tauNet, o.meanTurns, o.phAbs, o.phasePairs);
            a.glide += o.glide/n; a.avgBound += o.avgBound/n; a.accFrac += o.accFrac/n; a.detachRate += o.detachRate/n;
            a.meanTurns += o.meanTurns/n; a.turnsSpread += o.turnsSpread/n; a.coherentRoll += o.coherentRoll/n;
            a.turnsPerUm += o.turnsPerUm/n; a.meanDpsi += o.meanDpsi/n; a.meanDpsiCand += o.meanDpsiCand/n;
            a.leadAcc += o.leadAcc/n; a.leadCand += o.leadCand/n;
            a.tauNet += o.tauNet/n; a.tauAbs += o.tauAbs/n; a.fracHeadPos += o.fracHeadPos/n;
            a.cand += o.cand; a.acc += o.acc; a.leadN += o.leadN; a.trailN += o.trailN; a.detach += o.detach;
            a.sgnD = o.sgnD; a.invalid += o.invalid;
            a.driftStep += o.driftStep/n; a.phAbs += o.phAbs/n; a.phRms += o.phRms/n; a.phSigned += o.phSigned/n;
            a.phAxial += o.phAxial/n; a.phRoll += o.phRoll/n; a.phResid += o.phResid/n; a.phResidAlt += o.phResidAlt/n;
            a.ac1 += o.ac1/n; a.phasePairs += o.phasePairs; a.dAxial += o.dAxial/n;
            a.phAbsS += o.phAbsS/n; a.phRmsS += o.phRmsS/n; a.phSignedS += o.phSignedS/n;
            a.phAxialS += o.phAxialS/n; a.phRollS += o.phRollS/n; a.phResidS += o.phResidS/n;
            a.phasePairsSite += o.phasePairsSite; a.dAxialMed += o.dAxialMed/n;
            a.arcMedian += o.arcMedian/n; a.siteFrac += o.siteFrac/n;
            a.tCohSteps += o.tCohSteps/n; a.driftOverRms += o.driftOverRms/n;
            a.residMean += o.residMean/n; a.residRuns += o.residRuns; a.residOverTcoh += o.residOverTcoh/n;
            a.pMono += o.pMono/n; a.monoRuns += o.monoRuns; a.monoOk += o.monoOk;
            for (int b = 0; b < NB; b++) { a.binCand[b] += o.binCand[b]; a.binAcc[b] += o.binAcc[b]; a.binW[b] += o.binW[b]; }
            for (int b = 0; b < 8; b++) a.azHist[b] += o.azHist[b];
            for (int b = 0; b < NRES; b++) a.residHist[b] += o.residHist[b];
        }
        a.cancel = Math.abs(a.tauNet) > 1e-30 ? a.tauAbs/Math.abs(a.tauNet) : 0;
        a.semDpsi = sem(vD); a.semBias = sem(vB); a.semTau = sem(vT); a.semGlide = sem(vG);
        a.semTurns = sem(vTu); a.semTpu = sem(vTp);
        a.bias = 0; for (double v : vB) a.bias += v/n;
        int agree = 0; for (double v : vB) if (a.bias != 0 && v*a.bias > 0) agree++;
        a.nSignAgree = agree;
        int tAgree = 0; for (double v : vT) if (a.tauNet != 0 && v*a.tauNet > 0) tAgree++;
        a.nTauSignAgree = tAgree;
        return a;
    }

    static void brRow(BrArm a) {
        System.out.printf(Locale.US, "%-34s %-46s a=%.0f | glide %+7.3f±%.3f | avgB %5.2f | detach/step %.3f | accF %.3f | ⟨Δψ⟩acc %+.4f±%.4f | ⟨Δψ⟩cand %+.4f | leadAcc−leadCand %+.4f±%.4f (%d/%d) | lead/trail %d/%d | τnet %+.2e±%.1e (%d/%d) | cancel %.1f | turns %+.4f±%.4f | inv %d%n",
                a.label, a.pol.spec(), a.alpha, a.glide, a.semGlide, a.avgBound, a.detachRate, a.accFrac,
                a.meanDpsi, a.semDpsi, a.meanDpsiCand, a.bias, a.semBias, a.nSignAgree, a.nSeeds,
                a.leadN, a.trailN, a.tauNet, a.semTau, a.nTauSignAgree, a.nSeeds, a.cancel,
                a.meanTurns, a.semTurns, a.invalid);
    }
    static void brPhaseRow(BrArm a) {
        System.out.printf(Locale.US, "%-34s ALL  drift %.5f | ⟨|ΔΔψ|⟩ %.5f | RMS %.5f | signed %+.5f | axial %.5f | roll %.5f | resid(A) %.5f | resid(B) %.5f | ac1(per-motor lag1) %+.3f | drift/RMS %.5f | pairs %d | same-site frac %.3f%n",
                a.label, a.driftStep, a.phAbs, a.phRms, a.phSigned, a.phAxial, a.phRoll, a.phResid, a.phResidAlt,
                a.ac1, a.driftOverRms, a.phasePairs, a.siteFrac);
        System.out.printf(Locale.US, "%-34s SITE ⟨|ΔΔψ|⟩ %.5f | RMS %.5f | signed %+.5f | axial %.5f | roll %.5f | resid(A) %.5f | median|Δarc| %.4f nm | D_ax(med) %.3e µm²/s | D_ax(ms,site) %.3e | t_c %.1f steps | ⟨residence⟩ %.2f steps | res/t_c %.4f | P(mono) %.3f | drift/⟨|ΔΔψ|⟩ %.4f | pairs %d runs %d%n",
                "", a.phAbsS, a.phRmsS, a.phSignedS, a.phAxialS, a.phRollS, a.phResidS, a.arcMedian*1e3,
                a.dAxialMed, a.dAxial, a.tCohSteps, a.residMean, a.residOverTcoh, a.pMono,
                a.phAbsS > 1e-12 ? a.driftStep/a.phAbsS : 0, a.phasePairsSite, a.residRuns);
    }
    static void brHists(BrArm a) {
        System.out.printf(Locale.US, "    %-34s mismatch/weight histogram (Δψ bins over (−π,π]):%n", a.label);
        System.out.printf(Locale.US, "    %8s %11s %10s %10s %10s%n", "Δψ bin", "candidates", "mean w", "accepted", "lead/trail");
        for (int b = 0; b < NB; b++) System.out.printf(Locale.US, "    %+8.2f %11d %10.4f %10d %10s%n",
                binCentre(b), a.binCand[b], a.binCand[b] > 0 ? a.binW[b]/a.binCand[b] : 0, a.binAcc[b],
                a.sgnD*binCentre(b) < 0 ? "LEAD" : "TRAIL");
        System.out.printf(Locale.US, "    accepted-azimuth histogram (8 bins): %s%n", java.util.Arrays.toString(a.azHist));
        System.out.printf(Locale.US, "    candidate-residence histogram (1..7,>=8 steps): %s%n", java.util.Arrays.toString(a.residHist));
    }

    // ---------------- deterministic Brownian-mask fixtures ----------------
    static DoubleArray cpD(DoubleArray a) { DoubleArray b = new DoubleArray(a.getSize()); for (int i = 0; i < a.getSize(); i++) b.set(i, a.get(i)); return b; }
    static FloatArray  cpF(FloatArray a)  { FloatArray  b = new FloatArray(a.getSize());  for (int i = 0; i < a.getSize(); i++) b.set(i, a.get(i)); return b; }
    static IntArray    cpI(IntArray a)    { IntArray    b = new IntArray(a.getSize());    for (int i = 0; i < a.getSize(); i++) b.set(i, a.get(i)); return b; }

    /** Run matS2SolveStep on private copies with a given (brownOn, policy); return the per-motor state hash inputs. */
    static double[][] s2Replay(ExplicitCompleteMatHarness.ExMat e, Glide2D G, int t, int seed, int brownOn, int policy) {
        DoubleArray nodes = cpD(e.nodes), q = cpD(e.q), sys = cpD(e.sys), outGeom = cpD(e.outGeom);
        FloatArray fdf = cpF(G.mot.forceDotFil), fm = cpF(G.mot.forceMag);
        IntArray matc = IntArray.fromElements(t, seed, brownOn, policy, TwoBodyConverterMotor.F8_AXIS_LEGACY ? 0 : 1);
        TwoBodyBeamAnalyticGpu.matS2SolveStep(nodes, e.frame, q, G.bondData, G.mot.boundSeg, e.params, sys, outGeom, fdf, fm, matc, e.exCounts, e.convF);
        int N = e.N;
        double[][] out = new double[N][3];
        for (int m = 0; m < N; m++) { out[m][0] = q.get(m); out[m][1] = q.get(N+m); out[m][2] = nodes.get(m) + nodes.get(3*N+m) + nodes.get((3*e.M)*N+m); }
        return out;
    }
    static boolean sameRow(double[] a, double[] b) { return a[0] == b[0] && a[1] == b[1] && a[2] == b[2]; }

    static boolean runBrFixtures() {
        passN = failN = 0;
        System.out.println("\n--- BROWNIAN-ABLATION DETERMINISTIC FIXTURES ---");

        // BR1 — the filament channel-mask kernel is an exact identity at mask = (1,1,1,1), and exactly zeroes a
        // disabled channel while leaving the enabled ones bit-unchanged.
        int NB0 = 37;
        FloatArray rf0 = new FloatArray(3*NB0), rt0 = new FloatArray(3*NB0);
        for (int i = 0; i < 3*NB0; i++) { rf0.set(i, (float) Math.sin(0.7*i + 0.3)); rt0.set(i, (float) Math.cos(0.11*i - 1.1)); }
        IntArray cn = IntArray.fromElements(NB0, 0, 0, NB0);
        FloatArray rf = cpF(rf0), rt = cpF(rt0);
        BrownianForceSystem.brownChannelMask(rf, rt, FloatArray.fromElements(1f,1f,1f,1f), cn);
        boolean id = true; for (int i = 0; i < 3*NB0; i++) if (rf.get(i) != rf0.get(i) || rt.get(i) != rt0.get(i)) id = false;
        ck(101, "mask (1,1,1,1) is an EXACT identity on randForce/randTorque", id);
        // selective: axial force only
        rf = cpF(rf0); rt = cpF(rt0);
        BrownianForceSystem.brownChannelMask(rf, rt, FloatArray.fromElements(1f,0f,0f,0f), cn);
        boolean sel = true;
        for (int i = 0; i < NB0; i++) {
            if (rf.get(i) != rf0.get(i)) sel = false;                                  // axial force retained exactly
            if (rf.get(NB0+i) != 0f || rf.get(2*NB0+i) != 0f) sel = false;              // transverse force exactly 0
            if (rt.get(i) != 0f || rt.get(NB0+i) != 0f || rt.get(2*NB0+i) != 0f) sel = false;   // all torque exactly 0
        }
        ck(102, "mask (1,0,0,0): axial force bit-unchanged, every other channel EXACTLY zero", sel);
        // selective: roll torque only
        rf = cpF(rf0); rt = cpF(rt0);
        BrownianForceSystem.brownChannelMask(rf, rt, FloatArray.fromElements(0f,0f,1f,0f), cn);
        boolean sel2 = true;
        for (int i = 0; i < NB0; i++) {
            if (rf.get(i) != 0f || rf.get(NB0+i) != 0f || rf.get(2*NB0+i) != 0f) sel2 = false;
            if (rt.get(i) != rt0.get(i)) sel2 = false;
            if (rt.get(NB0+i) != 0f || rt.get(2*NB0+i) != 0f) sel2 = false;
        }
        ck(103, "mask (0,0,1,0): body-axial ROLL torque bit-unchanged, every other channel EXACTLY zero", sel2);

        // BR2 — policy-off identity: the FULL Brownian policy reproduces the canonical trajectory bit-for-bit.
        applyPolicy(POL_FULL);
        double[] hRef = trajHash(true, 6.0, true, 200);
        applyPolicy(POL_FULL);
        double[] hSame = trajHash(true, 6.0, true, 200);
        ExplicitCompleteMatHarness.resetBrownianPolicy();
        double[] hCanon = trajHash(true, 6.0, true, 200);
        ck(104, "policy 'full' ⇒ trajectory bit-identical to the canonical (unmasked) path",
                hRef[0] == hCanon[0] && hRef[1] == hCanon[1] && hRef[3] == hCanon[3] && hSame[0] == hCanon[0]);

        // BR3/BR4 — the binding-state motor mask, audited at EVERY step of a real trajectory.
        boolean okState = motorMaskStateAudit();

        // BR5 — Arm-B integrity: filament Brownian exactly zero, deterministic mechanics/chemistry/hazard alive.
        boolean okArmB = armBIntegrity();

        System.out.printf("Brownian-ablation fixtures: %d PASS, %d FAIL%n", passN, failN);
        return failN == 0 && okState && okArmB;
    }

    /**
     * Per-step audit of the binding-state-dependent motor Brownian mask over a REAL trajectory. At every step,
     * matS2SolveStep is replayed on private copies three ways from the identical post-step state:
     *   (i) brownOn=1, policy=0 (canonical) | (ii) brownOn=1, policy=1 (bound-motor Brownian off) | (iii) brownOn=0.
     * Requirements, checked per motor per step: a BOUND motor must satisfy (ii) == (iii) EXACTLY and (ii) != (i);
     * an UNBOUND motor must satisfy (ii) == (i) EXACTLY and (ii) != (iii). Because boundSeg at replay time is the
     * one the real solver saw in that same step, this also fixes the TRANSITION TIMING: the step on which a head
     * goes FREE→bound is itself audited, as is the step on which it detaches.
     */
    static boolean motorMaskStateAudit() {
        applyPolicy(POL_SEARCHONLY);   // Arm-B policy, so the audited trajectory is the primary arm's
        setTZ(true, 6.0); setSurface(true, R_NM, false, EXCL_NM);
        ExplicitCompleteMatHarness.TELEMETRY = true;
        int seed = 101, K = 400;
        Glide2D G = build(seed);
        var e = ExplicitCompleteMatHarness.packExMat(G, 1);
        e.matc.set(3, ExplicitCompleteMatHarness.motorBrownPolicy());
        int N = e.N;
        long boundChecked = 0, unboundChecked = 0, bad = 0, binds = 0, detaches = 0, noiseSeenBound = 0, noiseSeenUnbound = 0;
        boolean[] wasBound = new boolean[N];
        for (int t = 0; t < K; t++) {
            ExplicitCompleteMatHarness.stepGlidingCPU(e, t, seed);
            double[][] rCanon = s2Replay(e, G, t, seed, 1, 0);
            double[][] rMask  = s2Replay(e, G, t, seed, 1, 1);
            double[][] rNone  = s2Replay(e, G, t, seed, 0, 0);
            for (int m = 0; m < N; m++) {
                boolean b = G.mot.boundSeg.get(m) >= 0;
                if (b && !wasBound[m]) binds++;
                if (!b && wasBound[m]) detaches++;
                wasBound[m] = b;
                if (b) {
                    boundChecked++;
                    if (!sameRow(rMask[m], rNone[m])) bad++;                 // masked bound motor MUST equal no-Brownian
                    if (!sameRow(rCanon[m], rNone[m])) noiseSeenBound++;     // and the canonical one MUST differ (noise present)
                } else {
                    unboundChecked++;
                    if (!sameRow(rMask[m], rCanon[m])) bad++;                // unbound motor MUST keep its search noise
                    if (!sameRow(rCanon[m], rNone[m])) noiseSeenUnbound++;
                }
            }
        }
        ExplicitCompleteMatHarness.TELEMETRY = false;
        ExplicitCompleteMatHarness.resetBrownianPolicy(); setTZ(false, 0); setSurface(false, R_NM, false, 0);
        System.out.printf(Locale.US, "       (per-step mask audit over %d steps, N=%d: bound-motor samples %d, unbound %d, "
                + "FREE→bound transitions %d, bound→FREE %d; canonical-noise detected on %d/%d bound and %d/%d unbound samples)%n",
                K, N, boundChecked, unboundChecked, binds, detaches, noiseSeenBound, boundChecked, noiseSeenUnbound, unboundChecked);
        ck(105, "BOUND motor gets EXACTLY zero Brownian; UNBOUND motor keeps it (every step, every motor)", bad == 0);
        ck(106, "the mask is not vacuous: canonical Brownian measurably perturbs both bound and unbound motors",
                noiseSeenBound == boundChecked && noiseSeenUnbound == unboundChecked && boundChecked > 0 && unboundChecked > 0);
        ck(107, "FREE→bound and bound→FREE transitions occur inside the audited window (timing covered)",
                binds > 0 && detaches > 0);
        return bad == 0 && binds > 0 && detaches > 0;
    }

    /** Arm-B integrity: the stochastic thermal terms are gone, everything deterministic still runs. */
    static boolean armBIntegrity() {
        applyPolicy(POL_SEARCHONLY);
        setTZ(true, 6.0); setSurface(true, R_NM, false, EXCL_NM);
        ExplicitCompleteMatHarness.TELEMETRY = true;
        int seed = 101, K = 400;
        Glide2D G = build(seed); FilamentStore f = G.fil;
        var e = ExplicitCompleteMatHarness.packExMat(G, 1);
        e.matc.set(3, ExplicitCompleteMatHarness.motorBrownPolicy());
        int N = e.N, nSeg = G.nSeg;
        double maxRand = 0, maxForce = 0, maxBond = 0; long chemChanges = 0, cand = 0, rej = 0, bound = 0;
        int[] prevNuc = new int[N]; for (int m = 0; m < N; m++) prevNuc[m] = G.mot.nucleotideState.get(m);
        for (int t = 0; t < K; t++) {
            ExplicitCompleteMatHarness.stepGlidingCPU(e, t, seed);
            for (int i = 0; i < 3*nSeg; i++) { maxRand = Math.max(maxRand, Math.abs(f.randForce.get(i)));
                                               maxRand = Math.max(maxRand, Math.abs(f.randTorque.get(i)));
                                               maxForce = Math.max(maxForce, Math.abs(f.forceSum.get(i))); }
            for (int m = 0; m < N; m++) {
                int nu = G.mot.nucleotideState.get(m); if (nu != prevNuc[m]) chemChanges++; prevNuc[m] = nu;
                if (e.tzDiag.get(4*m+3) == 1f) { cand++; if (e.tzDiag.get(4*m+2) != 1f) rej++; }
                if (G.mot.boundSeg.get(m) >= 0) { bound++;
                    for (int c = 0; c < 3; c++) maxBond = Math.max(maxBond, Math.abs(G.bondData.get(m*CrossBridgeSystem.STRIDE+c))); }
            }
        }
        ExplicitCompleteMatHarness.TELEMETRY = false;
        ExplicitCompleteMatHarness.resetBrownianPolicy(); setTZ(false, 0); setSurface(false, R_NM, false, 0);
        System.out.printf(Locale.US, "       (Arm-B %d steps: max|randForce|,|randTorque| = %.3e ; max|filament forceSum| = %.3e N ;"
                + " max|F8| = %.3e N ; chemistry transitions = %d ; candidates = %d (rejected %d) ; bound-motor samples = %d)%n",
                K, maxRand, maxForce, maxBond, chemChanges, cand, rej, bound);
        ck(108, "Arm B: filament Brownian force AND torque are EXACTLY zero on every segment, every step", maxRand == 0.0);
        ck(109, "Arm B: deterministic filament force and cross-bridge force remain nonzero", maxForce > 0 && maxBond > 0);
        ck(110, "Arm B: chemistry (Lymn–Taylor) continues to fire", chemChanges > 0);
        ck(111, "Arm B: the target-zone hazard continues to run (candidates offered AND rejected)", cand > 0 && rej > 0);
        // alphaPsi = 0 remains canonical with respect to the target-zone path under the ablation policy
        applyPolicy(POL_SEARCHONLY);
        BrArm a0 = runBrArm(POL_SEARCHONLY, 0.0, 101, 600);
        ck(112, String.format(Locale.US, "Arm B with alphaPsi=0: every canonical bind is kept (accFrac=%.4f)", a0.accFrac),
                a0.cand > 0 && a0.accFrac == 1.0);
        return maxRand == 0.0 && maxForce > 0 && chemChanges > 0 && cand > 0 && rej > 0;
    }

    /** Device residency + CPU/GPU equivalence for a given Brownian policy (the FULL target-zone gliding graph). */
    static boolean brEquiv(Pol pol, double alpha) {
        applyPolicy(pol);
        setTZ(true, alpha); setSurface(true, R_NM, true, EXCL_NM);
        Glide2D Gc = build(101), Gd = build(101);
        var ec = ExplicitCompleteMatHarness.packExMat(Gc, 1);
        var ed = ExplicitCompleteMatHarness.packExMat(Gd, 1);
        ec.matc.set(3, ExplicitCompleteMatHarness.motorBrownPolicy());
        ed.matc.set(3, ExplicitCompleteMatHarness.motorBrownPolicy());
        TornadoExecutionPlan plan;
        try { plan = ExplicitCompleteMatHarness.buildGlidingGraph(ed, false); }
        catch (Throwable ex) { System.out.println("  " + pol.name() + ": graph did NOT lower: " + oneLine(root(ex).getMessage()));
            ExplicitCompleteMatHarness.resetBrownianPolicy(); setTZ(false,0); setSurface(false,R_NM,false,0); return false; }
        int K = 200, firstDiv = -1, bindMism = 0, accMism = 0; double maxFil = 0, maxPsi = 0, maxRand = 0;
        for (int t = 0; t < K; t++) {
            ed.matc.set(0, t); ed.matc.set(1, 101); ed.matc.set(3, ExplicitCompleteMatHarness.motorBrownPolicy());
            Gd.mot.setCounts(t, 101, Gd.nSeg); Gd.fil.counts.set(1, t); Gd.fil.counts.set(2, 101);
            try { plan.execute(); }
            catch (Throwable ex) { System.out.println("  " + pol.name() + ": device execute FAILED @t=" + t + ": " + oneLine(root(ex).getMessage()));
                ExplicitCompleteMatHarness.resetBrownianPolicy(); setTZ(false,0); setSurface(false,R_NM,false,0); return false; }
            ExplicitCompleteMatHarness.stepGlidingCPU(ec, t, 101);
            double dFil = 0; for (int i = 0; i < 3*Gc.nSeg; i++) dFil = Math.max(dFil, Math.abs(Gc.fil.coord.get(i) - Gd.fil.coord.get(i)));
            if (firstDiv < 0 && dFil > 1e-6) firstDiv = t;
            maxFil = Math.max(maxFil, dFil);
            for (int i = 0; i < 3*Gc.nSeg; i++) { if (!pol.fAx() && !pol.fTr()) maxRand = Math.max(maxRand, Math.abs(Gc.fil.randForce.get(i)));
                                                  if (!pol.fRoll() && !pol.fOth()) maxRand = Math.max(maxRand, Math.abs(Gc.fil.randTorque.get(i))); }
            if (firstDiv < 0) for (int m = 0; m < Gc.N; m++) {
                if (Gc.mot.boundSeg.get(m) != Gd.mot.boundSeg.get(m)) bindMism++;
                if (ec.tzDiag.get(4*m+2) != ed.tzDiag.get(4*m+2)) accMism++;
                maxPsi = Math.max(maxPsi, Math.abs(Gc.mot.bindPsi0.get(m) - Gd.mot.bindPsi0.get(m)));
            }
        }
        boolean fin = true; for (int i = 0; i < 3*Gc.nSeg; i++) if (!Float.isFinite(Gd.fil.coord.get(i))) fin = false;
        int nbC = 0, nbD = 0; for (int m = 0; m < Gc.N; m++) { if (Gc.mot.boundSeg.get(m) >= 0) nbC++; if (Gd.mot.boundSeg.get(m) >= 0) nbD++; }
        boolean ok = fin && bindMism == 0 && accMism == 0 && maxPsi < 1e-5 && Double.isFinite(maxFil) && maxRand == 0.0;
        System.out.printf(Locale.US, "  %-22s alpha=%.0f  %d device-resident steps: bindMism=%d acceptMism=%d max|Δpsi0|=%.1e ;"
                + " max|ΔfilCoord|=%.2e µm ; masked-channel |rand|=%.1e ; firstDiv=%s ; bound CPU=%d GPU=%d ⇒ %s%n",
                pol.name(), alpha, K, bindMism, accMism, maxPsi, maxFil, maxRand,
                firstDiv < 0 ? "none (bit-close)" : ("t=" + firstDiv + " (chaotic float op-order)"), nbC, nbD, ok ? "PASS" : "*FAIL*");
        ExplicitCompleteMatHarness.resetBrownianPolicy(); setTZ(false, 0); setSurface(false, R_NM, false, 0);
        return ok;
    }

    static boolean runBrEquiv() {
        System.out.println("\n--- BROWNIAN-ABLATION: FULL GLIDING GRAPH DEVICE RESIDENCY + CPU/GPU EQUIVALENCE ---");
        System.out.println("  (-Dtornado.enable.fma=false, -Dtornado.recover.bailout=false ⇒ a lowering failure THROWS; no silent fallback)");
        boolean ok = true;
        ok &= brEquiv(POL_FULL, ALPHA);
        ok &= brEquiv(POL_SEARCHONLY, ALPHA);
        ok &= brEquiv(POL_FILOFF, ALPHA);
        ok &= brEquiv(POL_ALLOFF, ALPHA);
        return ok;
    }

    // ---------------- the ablation campaign ----------------
    static boolean DECOMPOSE = false;
    static void runAblation() {
        System.out.printf("\n--- BROWNIAN-NOISE ABLATION CAMPAIGN (%s runner) — alphaPsi=%.0f, density=%.0f, %d seeds × %d steps ---%n",
                USE_GPU ? "GPU device-resident" : "CPU sequential", ALPHA, DENSITY, NSEEDS, STEPS);
        int[] seeds = new int[NSEEDS]; for (int i = 0; i < NSEEDS; i++) seeds[i] = SEED + 101*i;
        java.util.List<BrArm> arms = new java.util.ArrayList<>();
        arms.add(meanBr("A  full Brownian baseline",  POL_FULL,       ALPHA, seeds, STEPS));
        arms.add(meanBr("B  PRIMARY clean test",      POL_SEARCHONLY, ALPHA, seeds, STEPS));
        arms.add(meanBr("C  filament off, motors on", POL_FILOFF,     ALPHA, seeds, STEPS));
        arms.add(meanBr("D  bound quiet, filament on",POL_BOUNDQUIET, ALPHA, seeds, STEPS));
        arms.add(meanBr("E  fully deterministic",     POL_ALLOFF,     ALPHA, seeds, STEPS));
        arms.add(meanBr("F1 target-zone OFF (arm A)", POL_FULL,       0.0,   seeds, STEPS));
        arms.add(meanBr("F2 target-zone OFF (arm B)", POL_SEARCHONLY, 0.0,   seeds, STEPS));
        if (DECOMPOSE) {
            arms.add(meanBr("G  filament AXIAL noise only", POL_FIL_AXIAL, ALPHA, seeds, STEPS));
            arms.add(meanBr("H  filament ROLL noise only",  POL_FIL_ROLL,  ALPHA, seeds, STEPS));
            arms.add(meanBr("I  filament transverse+bend",  POL_FIL_OTHER, ALPHA, seeds, STEPS));
        }
        System.out.println("\n  ===== ATTACHMENT-FLUX / ENGAGEMENT / TORQUE (mean ± SEM over seeds) =====");
        for (BrArm a : arms) brRow(a);
        System.out.println("\n  ===== PHASE-COHERENCE BUDGET (per-step motion of a persisting candidate's target-zone phase, rad/step) =====");
        System.out.println("  (resid(A) uses  ΔΔψ − [−twistRate·ΔbindArc] − [−Δroll] ; resid(B) the opposite sign convention;");
        System.out.println("   the SMALLER of the two identifies the correct convention — do not read the larger one)");
        for (BrArm a : arms) brPhaseRow(a);
        System.out.println("\n  ===== TORQUE / TWIRL DIAGNOSTICS =====");
        for (BrArm a : arms) System.out.printf(Locale.US,
                "%-34s τnet %+.3e±%.1e N·m (%d/%d seeds same sign) | Σ|τ| %.3e | cancel %.1f | frac heads τ>0 %.4f | turns %+.4f±%.4f | per-seg roll SD %.4f turns | coherentRoll |mean|/SD %.3f | turns/µm %+.2f±%.2f | glide %+.3f µm/s%n",
                a.label, a.tauNet, a.semTau, a.nTauSignAgree, a.nSeeds, a.tauAbs, a.cancel, a.fracHeadPos,
                a.meanTurns, a.semTurns, a.turnsSpread, a.coherentRoll, a.turnsPerUm, a.semTpu, a.glide);
        System.out.println("\n  ===== HISTOGRAMS =====");
        for (BrArm a : arms) if (a.label.startsWith("A ") || a.label.startsWith("B ")) brHists(a);
        System.out.println("\n  ===== SYMMETRY CONTROLS on the primary arm (B) =====");
        brSymmetry(seeds[0]);
    }

    /** Mirrored-handedness + no-translation + alpha=0 controls on the primary (Arm-B) policy. */
    static void brSymmetry(int seed) {
        int st = Math.min(STEPS, 3000);
        double save = ExplicitCompleteMatHarness.TWIST_PER_MON_DEG;
        BrArm nor = runBrArmTwist(POL_SEARCHONLY, ALPHA, seed, st, save);
        BrArm mir = runBrArmTwist(POL_SEARCHONLY, ALPHA, seed, st, -save);
        System.out.printf(Locale.US, "    helix LEFT (canonical)   : ⟨Δψ⟩acc=%+.4f leadAcc=%.3f leadCand=%.3f τnet=%+.2e cand=%d acc=%d%n",
                nor.meanDpsi, nor.leadAcc, nor.leadCand, nor.tauNet, nor.cand, nor.acc);
        System.out.printf(Locale.US, "    helix MIRRORED           : ⟨Δψ⟩acc=%+.4f leadAcc=%.3f leadCand=%.3f τnet=%+.2e cand=%d acc=%d%n",
                mir.meanDpsi, mir.leadAcc, mir.leadCand, mir.tauNet, mir.cand, mir.acc);
        double[] z = kinematicRun(0.0, ALPHA, true, 40000, 64);
        double[] p = kinematicRun(+2.5, ALPHA, true, 40000, 64);
        double[] m = kinematicRun(-2.5, ALPHA, true, 40000, 64);
        System.out.printf(Locale.US, "    kinematic v=0 / +v / −v  : ⟨Δψ⟩ = %+.4f / %+.4f / %+.4f rad (leading frac %.3f / %.3f / %.3f)%n",
                z[0], p[0], m[0], z[1], p[1], m[1]);
        System.out.println("    (polarity reversal, rigid lab rotation and mirroring are exact deterministic fixtures 5/7/8/13 —");
        System.out.println("     unchanged by this increment, since matTargetZone is byte-unchanged)");
    }
    static BrArm runBrArmTwist(Pol pol, double alpha, int seed, int steps, double twistPerMonDeg) {
        TWIST_OVERRIDE = twistPerMonDeg*Math.PI/180.0/Constants.actinMonoRadius;
        try { return runBrArm(pol, alpha, seed, steps); } finally { TWIST_OVERRIDE = 0; }
    }
    static double TWIST_OVERRIDE = 0;
    /** The Brownian policy selected on the command line, applied to -3js renders (null ⇒ canonical). */
    static Pol CLI_POL = null;

    static double[] reconSite(FilamentStore f, int s, double arc, double azim, double R) {
        int n = f.n;
        double cx = f.coord.get(s), cy = f.coord.get(n+s), cz = f.coord.get(2*n+s);
        double ux = f.uVec.get(s), uy = f.uVec.get(n+s), uz = f.uVec.get(2*n+s);
        double yx = f.yVec.get(s), yy = f.yVec.get(n+s), yz = f.yVec.get(2*n+s);
        double zx = uy*yz-uz*yy, zy = uz*yx-ux*yz, zz = ux*yy-uy*yx;
        double zl = Math.sqrt(zx*zx+zy*zy+zz*zz); if (zl > 1e-30) { zx/=zl; zy/=zl; zz/=zl; }
        double aOff = arc - 0.5*f.segLength.get(s), c = Math.cos(azim), sn = Math.sin(azim);
        return new double[]{ cx+aOff*ux + R*(c*yx+sn*zx), cy+aOff*uy + R*(c*yy+sn*zy), cz+aOff*uz + R*(c*yz+sn*zz) };
    }
}
