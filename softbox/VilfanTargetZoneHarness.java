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
                default -> { }
            }
        }
        System.out.println("######## Vilfan target-zone stereospecific binding — explicit-S2 gliding (noncanonical, default-off) ########");
        System.out.printf(Locale.US, "dt=%.2e  density=%.0f heads/µm²  seed=%d  steps=%d  seeds=%d  Ractin=%.2f nm  excl=%.2f nm  twistRate=%.1f rad/µm%n",
                DT, DENSITY, SEED, STEPS, NSEEDS, R_NM, EXCL_NM, TWIST);
        boolean ok = true;
        if (jsDir != null) { makeMovies(jsDir); return; }
        if (all)          { ok &= runFixtures(); ok &= runEquiv(); runStageA(); runDtCheck(); }
        else if (fixtures) ok = runFixtures();
        else if (equiv)    ok = runEquiv();
        else if (stageA)   runStageA();
        else if (dtChk)    runDtCheck();
        else               ok = runFixtures();
        System.out.println("====================================================================================================");
        if (fixtures || equiv || all) System.out.println(ok ? "ALL GATED CHECKS PASS" : "*** SOME CHECKS FAILED ***");
        if (!ok) System.exit(1);
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
        double phaseStep, phaseAxial, driftStep; long phasePairs;
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
        System.out.printf(Locale.US, "%-30s glide %+7.3f±%.3f | avgB %5.2f | accFrac %.3f | ⟨Δψ⟩acc %+.4f±%.4f | ⟨Δψ⟩cand %+.4f | leadAcc−leadCand %+.4f±%.4f (%d/%d seeds agree) | τnet %+.2e±%.1e | cancel %.1f%n",
                label, o.glide, o.semGlide, o.avgBound, o.accFrac, o.meanDpsi, o.semDpsi, o.meanDpsiCand,
                o.bias, o.semBias, o.nSignAgree, o.nSeeds, o.tauNet, o.semTau, o.cancel);
    }
    static void row(String label, Arm o) {
        System.out.printf(Locale.US, "%-30s %+10.3f %9.2f %8.3f %+10.4f %+10.4f %9.3f %9.3f %+10.2e %8.1f %+9.4f%n",
                label, o.glide, o.avgBound, o.accFrac, o.meanDpsi, o.meanDpsiCand, o.leadFrac, o.leadFracCand, o.tauNet, o.cancel, o.meanTurns);
    }
    static Arm mean(int[] seeds, boolean tzOn, double alpha, boolean surface, boolean steric) {
        Arm a = new Arm(); int n = seeds.length; a.nSeeds = n;
        double[] vD = new double[n], vL = new double[n], vB = new double[n], vT = new double[n], vG = new double[n];
        int idx = 0;
        for (int s : seeds) {
            Arm o = runArm(tzOn, alpha, surface, steric, s, STEPS);
            vD[idx] = o.meanDpsi; vL[idx] = o.leadFrac; vB[idx] = o.leadFrac - o.leadFracCand;
            vT[idx] = o.tauNet; vG[idx] = o.glide; idx++;
            if (VERBOSE) System.out.printf(Locale.US, "      seed %-5d glide=%+7.3f avgB=%6.2f cand=%6d acc=%6d ⟨Δψ⟩acc=%+.4f ⟨Δψ⟩cand=%+.4f leadAcc=%.4f leadCand=%.4f τnet=%+.2e%n",
                    s, o.glide, o.avgBound, o.cand, o.acc, o.meanDpsi, o.meanDpsiCand, o.leadFrac, o.leadFracCand, o.tauNet);
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
        Glide2D G = build(SEED); var e = ExplicitCompleteMatHarness.packExMat(G, 1);
        new java.io.File(dir).mkdirs();
        double R = R_NM*1e-3; int frames = 0;
        for (int t = 0; t <= STEPS; t++) {
            if (t % STRIDE == 0) writeFrame(dir, frames++, t*DT, G, e, R, surface);
            if (t < STEPS) ExplicitCompleteMatHarness.stepGlidingCPU(e, t, SEED);
        }
        System.out.printf("  %-40s wrote %d frames%n", dir, frames);
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
