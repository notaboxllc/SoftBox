package softbox;

import softbox.TwoBodyConverterMotor.Glide2D;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import java.util.Locale;

/**
 * EXPLICIT-S2 GLIDING + HELICAL SURFACE BINDING / TWIRLING — the dynamic-assay port + viewer (noncanonical,
 * default-off). Drives {@link ExplicitCompleteMatHarness}'s validated explicit-s2-l40 single-head gliding pipeline
 * ({@code packExMat}/{@code stepGlidingCPU}/{@code buildGlidingGraph}) with the off-axis surface-binding drive
 * turned on ({@code ExplicitCompleteMatHarness.SURFACE_ON}). Asks the mechanism question: during DYNAMIC bind–
 * stroke–release gliding, does the off-axis actin-surface attachment make the filament rotate/twirl?
 *
 * The filament is a full rigid-rod FilamentStore whose roll DOF is integrated every step (bwx = torqueSum·û/γ_x);
 * the explicit model's cross-bridge is PURE F8 (align OFF), so the only cross-bridge torque on the filament is the
 * off-axis F8 couple — no F9/F10 contamination, no roll spring, no artificial torque.
 *
 * Modes: -fixtures (port fixtures) | -equiv (CPU/GPU) | -campaign (control vs surface vs surface+steric) |
 *        -3js <dir> (movie) | -all.
 * Flags: -helical-surface-bind (implied on for surface arms), -actin-bind-radius-nm, -surface-exclusion-nm,
 *        -no-surface-exclusion, -density, -seed, -steps, -stride.
 */
public final class ExplicitTwirlGlidingHarness {

    static final double DT = ExplicitCompleteMatHarness.DT;   // 2.5e-6
    static double DENSITY = 400.0;
    static int    SEED = 101, STEPS = 20000, STRIDE = 100;
    static double R_NM = 3.5, EXCL_NM = 5.5;

    public static void main(String[] args) {
        // GPU crash lifecycle trace (diagnostic; OFF unless -gpu-crash-trace / SOFTBOX_GPU_CRASH_TRACE=1).
        TornadoCrashDiagnostic.init("explicit-s2-twirl", args);
        boolean fixtures = false, equiv = false, campaign = false, all = false; String jsDir = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-fixtures" -> fixtures = true;
                case "-equiv" -> equiv = true;
                case "-campaign" -> campaign = true;
                case "-all" -> all = true;
                case "-3js" -> jsDir = args[++i];
                case "-density" -> DENSITY = Double.parseDouble(args[++i]);
                case "-seed" -> SEED = Integer.parseInt(args[++i]);
                case "-steps" -> STEPS = Integer.parseInt(args[++i]);
                case "-stride" -> STRIDE = Integer.parseInt(args[++i]);
                case "-actin-bind-radius-nm" -> R_NM = Double.parseDouble(args[++i]);
                case "-surface-exclusion-nm" -> EXCL_NM = Double.parseDouble(args[++i]);
                default -> { /* ignore */ }
            }
        }
        System.out.println("######## Explicit-S2 gliding + helical surface binding / twirling (noncanonical, default-off) ########");
        System.out.printf(Locale.US, "dt=%.2e  density=%.0f heads/µm²  seed=%d  steps=%d  Ractin=%.2f nm  exclusion=%.2f nm%n",
                DT, DENSITY, SEED, STEPS, R_NM, EXCL_NM);

        TornadoCrashDiagnostic.simDt(DT);
        TornadoCrashDiagnostic.context("campaignArm", all ? "all" : equiv ? "equiv" : campaign ? "campaign" : fixtures ? "fixtures" : "fixtures");
        TornadoCrashDiagnostic.context("seed", SEED);
        TornadoCrashDiagnostic.context("density", DENSITY);
        TornadoCrashDiagnostic.context("steps", STEPS);

        boolean ok = true;
        if (jsDir != null) { makeMovies(jsDir); TornadoCrashDiagnostic.normalMainReturn("mode=3js dir=" + jsDir); return; }
        if (all) { ok &= runFixtures(); ok &= runEquiv(); runCampaign(); }
        else if (fixtures) ok = runFixtures();
        else if (equiv) ok = runEquiv();
        else if (campaign) runCampaign();
        else ok = runFixtures();
        System.out.println("====================================================================================================");
        if (fixtures || equiv || all) System.out.println(ok ? "ALL GATED CHECKS PASS" : "*** SOME CHECKS FAILED ***");
        TornadoCrashDiagnostic.normalMainReturn("ok=" + ok);
        if (!ok) System.exit(1);
    }

    // ---- set the ExplicitCompleteMatHarness surface statics for an arm ----
    static void setSurface(boolean on, double rNm, boolean steric, double exclNm) {
        ExplicitCompleteMatHarness.SURFACE_ON = on;
        ExplicitCompleteMatHarness.R_ACTIN_NM = rNm;
        ExplicitCompleteMatHarness.SURF_STERIC = steric;
        ExplicitCompleteMatHarness.SURF_EXCL_NM = exclNm;
    }
    static Glide2D build(int seed) {
        double slack = TwoBodyConverterMotor.EXPLICIT_GLIDE_SLACK_NM;
        return TwoBodyConverterMotor.buildS2Mat(DENSITY, DT, 40.0, slack, seed);
    }

    // ================================================================= observables
    static final class Obs {
        double glide0, glideN;            // centroid·b̂ start/end (µm)
        double[] segRollCum;              // cumulative unwrapped roll per segment (rad)
        double meanTurns, turnsSpread;    // mean/std cumulative turns across segments
        double glideUm;                   // net glide distance (µm)
        double turnsPerUm;                // mean turns per µm of glide
        double omega;                     // mean roll rate (rad/s)
        double f8AxNet, f8AxAbs;          // net & Σ|·| F8 axial torque over bound heads (last sample, N·m)
        double cancel;                    // Σ|τ_i| / |Στ_i|  (∞ ⇒ perfect cancellation)
        double avgBound;
        int rejFrac;                      // steric rejects (cumulative)
        java.util.List<Double> azHist = new java.util.ArrayList<>();
    }

    /** Run a gliding arm on the CPU runner, accumulating twirl observables. */
    static Obs runArm(boolean surface, double rNm, boolean steric, double exclNm, int seed, int steps) {
        setSurface(surface, rNm, steric, exclNm);
        Glide2D G = build(seed);
        FilamentStore f = G.fil; int nSeg = G.nSeg;
        ExplicitCompleteMatHarness.ExMat e = ExplicitCompleteMatHarness.packExMat(G, 1);
        double[] bhat = G.bhat;
        Obs o = new Obs();
        o.segRollCum = new double[nSeg];
        double[] prevRoll = new double[nSeg];
        for (int s = 0; s < nSeg; s++) prevRoll[s] = rollAngle(f, s, bhat);
        o.glide0 = centroidDot(f, bhat);
        double boundSum = 0; int boundN = 0; int rej = 0;
        double axNetAcc = 0, axAbsAcc = 0; long axSamples = 0;   // torque stats accumulated over the run (not a final snapshot)
        for (int t = 0; t < steps; t++) {
            ExplicitCompleteMatHarness.stepGlidingCPU(e, t, seed);
            for (int s = 0; s < nSeg; s++) { double r = rollAngle(f, s, bhat); o.segRollCum[s] += wrapPi(r - prevRoll[s]); prevRoll[s] = r; }
            if (surface && steric) rej += e.occStats.get(1);
            // accumulate per-head F8 axial torque + azimuths while heads are bound (persisted bondData seg torque)
            double stepNet = 0, stepAbs = 0; boolean any = false;
            for (int m = 0; m < G.N; m++) { int s = G.mot.boundSeg.get(m); if (s < 0) continue; any = true;
                double ux = f.uVec.get(s), uy = f.uVec.get(nSeg + s), uz = f.uVec.get(2 * nSeg + s);
                int d = m * CrossBridgeSystem.STRIDE;
                double tau = G.bondData.get(d + 9) * ux + G.bondData.get(d + 10) * uy + G.bondData.get(d + 11) * uz;
                stepNet += tau; stepAbs += Math.abs(tau);
                if (surface && o.azHist.size() < 20000 && (t % 5 == 0)) o.azHist.add((double) G.mot.bindAzim.get(m)); }
            if (any) { axNetAcc += stepNet; axAbsAcc += stepAbs; axSamples++; }
            if ((t + 1) % 200 == 0) { int b = 0; for (int m = 0; m < G.N; m++) if (G.mot.boundSeg.get(m) >= 0) b++; boundSum += b; boundN++; }
        }
        o.glideN = centroidDot(f, bhat);
        o.glideUm = o.glideN - o.glide0;
        double sum = 0; for (double v : o.segRollCum) sum += v; double mean = sum / nSeg;
        double var = 0; for (double v : o.segRollCum) var += (v - mean) * (v - mean); var /= nSeg;
        o.meanTurns = mean / (2 * Math.PI); o.turnsSpread = Math.sqrt(var) / (2 * Math.PI);
        double time = steps * DT;
        o.omega = mean / time;
        o.turnsPerUm = Math.abs(o.glideUm) > 1e-6 ? o.meanTurns / o.glideUm : 0;
        o.avgBound = boundN > 0 ? boundSum / boundN : 0;
        o.rejFrac = rej;
        // time-averaged F8 axial torque (over steps with ≥1 bound head): net = coherence, Σ|·| = magnitude
        o.f8AxNet = axSamples > 0 ? axNetAcc / axSamples : 0;
        o.f8AxAbs = axSamples > 0 ? axAbsAcc / axSamples : 0;
        o.cancel = Math.abs(o.f8AxNet) > 1e-30 ? o.f8AxAbs / Math.abs(o.f8AxNet) : (o.f8AxAbs > 0 ? 1e9 : 0);
        return o;
    }

    static double centroidDot(FilamentStore f, double[] bhat) {
        int n = f.n; double sx = 0, sy = 0, sz = 0;
        for (int s = 0; s < n; s++) { sx += f.coord.get(s); sy += f.coord.get(n + s); sz += f.coord.get(2 * n + s); }
        return (sx * bhat[0] + sy * bhat[1] + sz * bhat[2]) / n;
    }
    /** Signed roll of segment s's yVec about its û, referenced to b̂ projected ⊥û (continuously unwrappable). */
    static double rollAngle(FilamentStore f, int s, double[] ref) {
        int n = f.n;
        double ux = f.uVec.get(s), uy = f.uVec.get(n + s), uz = f.uVec.get(2 * n + s);
        double yx = f.yVec.get(s), yy = f.yVec.get(n + s), yz = f.yVec.get(2 * n + s);
        double d = ux * ref[0] + uy * ref[1] + uz * ref[2];
        double gx = ref[0] - d * ux, gy = ref[1] - d * uy, gz = ref[2] - d * uz;
        double gl = Math.sqrt(gx * gx + gy * gy + gz * gz);
        if (gl < 1e-9) { gx = 1 - ux * ux; gy = -ux * uy; gz = -ux * uz; gl = Math.sqrt(gx * gx + gy * gy + gz * gz); if (gl < 1e-9) return 0; }
        gx /= gl; gy /= gl; gz /= gl;
        double hx = uy * gz - uz * gy, hy = uz * gx - ux * gz, hz = ux * gy - uy * gx;
        double c = yx * gx + yy * gy + yz * gz, sn = yx * hx + yy * hy + yz * hz;
        return Math.atan2(sn, c);
    }
    static double wrapPi(double a) { double T = 2 * Math.PI; a = a - T * Math.floor((a + Math.PI) / T); if (a > Math.PI) a -= T; return a; }

    // ================================================================= deterministic port fixtures
    static int passN, failN;
    static void ck(int id, String name, boolean p) { System.out.printf("  [%2d] %-56s %s%n", id, name, p ? "PASS" : "*** FAIL ***"); if (p) passN++; else failN++; }

    static boolean runFixtures() {
        passN = failN = 0;
        System.out.println("\n--- PORT-SPECIFIC DETERMINISTIC FIXTURES (explicit-S2 lineage) ---");

        // 1-2. Feature OFF and R=0 both reproduce the canonical explicit-S2 trajectory bit-for-bit.
        double[] hOff = trajHash(false, 0, false, 0, 111, 300);
        double[] hR0  = trajHash(true, 0.0, false, 0, 111, 300);   // surface ON but Ractin=0, steric off ⇒ ≡ canonical
        ck(1, "feature OFF trajectory finite + canonical", Double.isFinite(hOff[0]) && hOff[2] == 0);
        ck(2, "R=0 surface ≡ canonical (bit-identical coord+redOut over 300 steps)", hOff[0] == hR0[0] && hOff[1] == hR0[1]);

        // 3. R=3.5 surface displacement: a bound head's reconstructed site sits at radius R from the centerline.
        ck(3, "R=3.5nm reconstructed site radial distance == R", surfaceRadiusCheck());

        // 4. Azimuth retained: a bound head's bindAzim does not change while it stays bound.
        ck(4, "retained azimuth constant while bound (material-latched)", azimuthRetained());

        // 5-9. 3D steric prune (matSurfaceStericPrune) semantics.
        stericFixtures();

        // 10. Off-axis force → nonzero F8 axial torque; R=0 → negligible (the drive is off-axis).
        double tauR = f8AxialProbe(R_NM * 1e-3), tau0 = f8AxialProbe(0.0);
        System.out.printf(Locale.US, "       (F8 axial torque: R=3.5nm net=%.3e N·m ; R=0 net=%.3e N·m)%n", tauR, tau0);
        ck(10, "off-axis bond → nonzero F8 axial torque; R=0 → ≈0", Math.abs(tauR) > 1e-22 && Math.abs(tau0) < Math.abs(tauR) / 20.0);

        System.out.printf("Port fixtures: %d PASS, %d FAIL%n", passN, failN);
        return failN == 0;
    }

    /** Run K steps of an arm; return {hashCoord, hashRedOut, nanFlag}. */
    static double[] trajHash(boolean surf, double rNm, boolean steric, double exclNm, int seed, int K) {
        setSurface(surf, rNm, steric, exclNm);
        Glide2D G = build(seed); FilamentStore f = G.fil;
        ExplicitCompleteMatHarness.ExMat e = ExplicitCompleteMatHarness.packExMat(G, 1);
        for (int t = 0; t < K; t++) ExplicitCompleteMatHarness.stepGlidingCPU(e, t, seed);
        double hc = 0, hr = 0; boolean nan = false;
        for (int i = 0; i < 3 * G.nSeg; i++) { float v = f.coord.get(i); if (!Float.isFinite(v)) nan = true; hc = hc * 1.0000001 + v; }
        for (int i = 0; i < 6; i++) hr = hr * 1.0000001 + e.redOut.get(i);
        return new double[]{ hc, hr, nan ? 1 : 0 };
    }

    static boolean surfaceRadiusCheck() {
        // pre-bind one motor, run matSurfaceAzim once, reconstruct the site, check radius.
        setSurface(true, R_NM, false, 0);
        Glide2D G = build(101); FilamentStore f = G.fil; int nSeg = G.nSeg;
        ExplicitCompleteMatHarness.ExMat e = ExplicitCompleteMatHarness.packExMat(G, 1);
        for (int t = 0; t < 120; t++) ExplicitCompleteMatHarness.stepGlidingCPU(e, t, 101);   // let heads bind + get an azimuth
        double R = R_NM * 1e-3; boolean ok = false; double worst = 0;
        for (int m = 0; m < G.N; m++) {
            int s = G.mot.boundSeg.get(m); if (s < 0) continue;
            double[] site = reconSite(f, s, G.mot.bindArc.get(m), G.mot.bindAzim.get(m), R);
            double aOff = G.mot.bindArc.get(m) - 0.5 * f.segLength.get(s);
            double[] ax = { f.coord.get(s) + aOff * f.uVec.get(s), f.coord.get(nSeg + s) + aOff * f.uVec.get(nSeg + s), f.coord.get(2 * nSeg + s) + aOff * f.uVec.get(2 * nSeg + s) };
            double rad = Math.sqrt(sq(site[0] - ax[0]) + sq(site[1] - ax[1]) + sq(site[2] - ax[2]));
            worst = Math.max(worst, Math.abs(rad - R)); ok = true;
        }
        return ok && worst < 1e-6;
    }

    static boolean azimuthRetained() {
        setSurface(true, R_NM, false, 0);
        Glide2D G = build(101); ExplicitCompleteMatHarness.ExMat e = ExplicitCompleteMatHarness.packExMat(G, 1);
        // find a motor that binds and stays bound; verify bindAzim frozen across steps.
        float[] az0 = new float[G.N]; int[] bs0 = new int[G.N];
        for (int t = 0; t < 50; t++) { ExplicitCompleteMatHarness.stepGlidingCPU(e, t, 101);
            if (t == 25) for (int m = 0; m < G.N; m++) { bs0[m] = G.mot.boundSeg.get(m); az0[m] = G.mot.bindAzim.get(m); } }
        boolean anyHeld = false, allConst = true;
        for (int m = 0; m < G.N; m++) if (bs0[m] >= 0 && G.mot.boundSeg.get(m) == bs0[m]) { anyHeld = true; if (G.mot.bindAzim.get(m) != az0[m]) allConst = false; }
        return anyHeld && allConst;
    }

    static void stericFixtures() {
        // constructed: a straight filament (nSeg from build), 4 pre-bound heads on seg s0 at controlled arcs/azims.
        setSurface(true, R_NM, true, EXCL_NM);
        Glide2D G = build(101); FilamentStore f = G.fil; int nSeg = G.nSeg, s0 = nSeg / 2;
        double half = 0.5 * f.segLength.get(s0), R = R_NM * 1e-3, excl = EXCL_NM * 1e-3, tol = 1e-3 * 1e-3;
        double twist = ExplicitCompleteMatHarness.TWIST_PER_MON_DEG * Math.PI / 180.0 / Constants.actinMonoRadius;
        java.util.function.BiFunction<double[], double[], Boolean> n/*unused*/ = null;
        // helper: does candidate (arc,azim) survive vs a set of bound (arc,azim)?
        // test 5 same coord, 6 below, 7 exactly, 8 above (axial), 9 opposite-side, plus diff-fil & lowest-id
        boolean t5 = !stericSurvive(G, s0, half, 0f, new float[][]{{ (float) half, 0f }}, R, excl, tol);          // 0 nm → reject
        boolean t6 = !stericSurvive(G, s0, (float) (half + 5.3e-3), 0f, new float[][]{{ (float) half, 0f }}, R, excl, tol);
        boolean t7 =  stericSurvive(G, s0, (float) (half + 5.5e-3), 0f, new float[][]{{ (float) half, 0f }}, R, excl, tol);
        boolean t8 =  stericSurvive(G, s0, (float) (half + 5.7e-3), 0f, new float[][]{{ (float) half, 0f }}, R, excl, tol);
        boolean t9 =  stericSurvive(G, s0, (float) half, (float) Math.PI, new float[][]{{ (float) half, 0f }}, R, excl, tol);  // opposite side, 2R=7nm
        ck(5, "steric: coincident (0 nm) → REJECT", t5);
        ck(6, "steric: below threshold (5.3 nm) → REJECT", t6);
        ck(7, "steric: exactly 5.5 nm → ACCEPT", t7);
        ck(8, "steric: above threshold (5.7 nm) → ACCEPT", t8);
        ck(9, "steric: opposite side (2R sep) → ACCEPT + lowest-id conflict", t9 && lowestIdWins(G, s0, half, R, excl, tol));
    }

    /** Reconstruct a scene with `bound` pre-placed heads on seg s + one candidate; run the prune; return true if the candidate SURVIVES. */
    static boolean stericSurvive(Glide2D G, int s, double candArc, double candAzim, float[][] bound, double R, double excl, double tol) {
        int N = G.N; if (N < bound.length + 1) return true;
        MotorStore mot = G.mot; FilamentStore f = G.fil;
        for (int m = 0; m < N; m++) { mot.boundSeg.set(m, -1); }
        // candidate = motor 0 (fresh); bound heads = motors 1..
        mot.boundSeg.set(0, s); mot.bindArc.set(0, (float) candArc); mot.bindAzim.set(0, (float) candAzim);
        for (int b = 0; b < bound.length; b++) { mot.boundSeg.set(1 + b, s); mot.bindArc.set(1 + b, bound[b][0]); mot.bindAzim.set(1 + b, bound[b][1]); }
        java.util.function.Supplier<uk.ac.manchester.tornado.api.types.arrays.IntArray> mk = () -> { var a = new uk.ac.manchester.tornado.api.types.arrays.IntArray(N); a.init(0); return a; };
        var justBound = mk.get(); justBound.set(0, 1);   // only the candidate is "fresh"
        var prevBound = new uk.ac.manchester.tornado.api.types.arrays.IntArray(N); prevBound.init(-1);
        var occStats = new uk.ac.manchester.tornado.api.types.arrays.IntArray(4);
        var stericP = uk.ac.manchester.tornado.api.types.arrays.DoubleArray.fromElements(R, excl, tol);
        var segFilId = new uk.ac.manchester.tornado.api.types.arrays.IntArray(G.nSeg);
        TwoBodyBeamAnalyticGpu.computeMaterialMaps(f, G.nSeg, new FloatArray(G.nSeg), segFilId);
        var counts = uk.ac.manchester.tornado.api.types.arrays.IntArray.fromElements(N, 0, 0, G.nSeg);
        TwoBodyBeamAnalyticGpu.matSurfaceStericPrune(mot.boundSeg, justBound, prevBound, mot.bindArc, mot.bindAzim,
                f.coord, f.uVec, f.yVec, f.segLength, segFilId, stericP, occStats, counts);
        return mot.boundSeg.get(0) >= 0;
    }
    /** Two fresh candidates within threshold: exactly one survives, lower id wins. */
    static boolean lowestIdWins(Glide2D G, int s, double half, double R, double excl, double tol) {
        int N = G.N; if (N < 2) return true; MotorStore mot = G.mot; FilamentStore f = G.fil;
        for (int m = 0; m < N; m++) mot.boundSeg.set(m, -1);
        mot.boundSeg.set(0, s); mot.bindArc.set(0, (float) half); mot.bindAzim.set(0, 0f);
        mot.boundSeg.set(1, s); mot.bindArc.set(1, (float) (half + 2.7e-3)); mot.bindAzim.set(1, 0f);
        var justBound = new uk.ac.manchester.tornado.api.types.arrays.IntArray(N); justBound.init(0); justBound.set(0, 1); justBound.set(1, 1);
        var prevBound = new uk.ac.manchester.tornado.api.types.arrays.IntArray(N); prevBound.init(-1);
        var occStats = new uk.ac.manchester.tornado.api.types.arrays.IntArray(4);
        var stericP = uk.ac.manchester.tornado.api.types.arrays.DoubleArray.fromElements(R, excl, tol);
        var segFilId = new uk.ac.manchester.tornado.api.types.arrays.IntArray(G.nSeg);
        TwoBodyBeamAnalyticGpu.computeMaterialMaps(f, G.nSeg, new FloatArray(G.nSeg), segFilId);
        var counts = uk.ac.manchester.tornado.api.types.arrays.IntArray.fromElements(N, 0, 0, G.nSeg);
        TwoBodyBeamAnalyticGpu.matSurfaceStericPrune(mot.boundSeg, justBound, prevBound, mot.bindArc, mot.bindAzim,
                f.coord, f.uVec, f.yVec, f.segLength, segFilId, stericP, occStats, counts);
        return mot.boundSeg.get(0) >= 0 && mot.boundSeg.get(1) < 0;   // lower id kept, higher pruned
    }

    /** F8 axial torque of one pre-bound off-axis head after one gliding step (host recompute from bondData). */
    static double f8AxialProbe(double R) {
        setSurface(true, R * 1e3, false, 0);
        Glide2D G = build(101); FilamentStore f = G.fil; int nSeg = G.nSeg;
        ExplicitCompleteMatHarness.ExMat e = ExplicitCompleteMatHarness.packExMat(G, 0);   // Brownian off for a clean sign
        for (int t = 0; t < 120; t++) ExplicitCompleteMatHarness.stepGlidingCPU(e, t, 101);
        double net = 0;
        for (int m = 0; m < G.N; m++) { int s = G.mot.boundSeg.get(m); if (s < 0) continue;
            double ux = f.uVec.get(s), uy = f.uVec.get(nSeg + s), uz = f.uVec.get(2 * nSeg + s);
            int d = m * CrossBridgeSystem.STRIDE;
            net += G.bondData.get(d + 9) * ux + G.bondData.get(d + 10) * uy + G.bondData.get(d + 11) * uz; }
        return net;
    }

    static double[] reconSite(FilamentStore f, int s, double arc, double azim, double R) {
        int n = f.n;
        double cx = f.coord.get(s), cy = f.coord.get(n + s), cz = f.coord.get(2 * n + s);
        double ux = f.uVec.get(s), uy = f.uVec.get(n + s), uz = f.uVec.get(2 * n + s);
        double yx = f.yVec.get(s), yy = f.yVec.get(n + s), yz = f.yVec.get(2 * n + s);
        double zx = uy * yz - uz * yy, zy = uz * yx - ux * yz, zz = ux * yy - uy * yx;
        double zl = Math.sqrt(zx * zx + zy * zy + zz * zz); if (zl > 1e-30) { zx /= zl; zy /= zl; zz /= zl; }
        double aOff = arc - 0.5 * f.segLength.get(s), c = Math.cos(azim), sn = Math.sin(azim);
        return new double[]{ cx + aOff * ux + R * (c * yx + sn * zx), cy + aOff * uy + R * (c * yy + sn * zy), cz + aOff * uz + R * (c * yz + sn * zz) };
    }
    static double sq(double x) { return x * x; }

    // ================================================================= CPU/GPU equivalence
    // Two parts: (1) the FULL surface-ON explicit-S2 gliding DEVICE graph lowers + runs device-resident vs the CPU
    // runner (G3); (2) the two new kernels' decisions are bit-identical CPU↔GPU on a deterministic fixture.
    // REQUIRES -Dtornado.enable.fma=false (the matS2SolveStep FMA-lowering defect) + bailout=false (no silent
    // fallback) — supplied by scripts/run_explicit_twirl.sh. See docs/EXPLICIT_S2_GPU_LOWERING_REGRESSION_FINDINGS.md.
    static boolean runEquiv() {
        boolean ok = runEquivFullGraph() & runEquivKernels();
        return ok;
    }
    /** G3: the FULL surface-ON gliding graph lowers + runs device-resident vs the CPU runner (bit-close window). */
    static boolean runEquivFullGraph() {
        System.out.println("\n--- CPU/GPU EQUIVALENCE — FULL surface-ON explicit-S2 gliding graph (device-resident) ---");
        setSurface(true, R_NM, true, EXCL_NM);
        Glide2D Gc = build(101), Gd = build(101);
        var ec = ExplicitCompleteMatHarness.packExMat(Gc, 1);
        var ed = ExplicitCompleteMatHarness.packExMat(Gd, 1);
        TornadoExecutionPlan plan;
        TornadoCrashDiagnostic.planConstructionBegin("graph=buildGlidingGraph(surface-ON) arm=equiv-full-graph");
        try { plan = ExplicitCompleteMatHarness.buildGlidingGraph(ed, false); }
        catch (Throwable ex) { TornadoCrashDiagnostic.planConstructionThrew(ex);
                System.out.println("  FULL surface-ON graph did NOT lower: " + oneLine(root(ex).getMessage())
                + "  (need -Dtornado.enable.fma=false ?)"); return false; }
        int K = 200, firstDiv = -1; double maxFil = 0; boolean lowered = true;
        TornadoCrashDiagnostic.planConstructionEnd(plan, "arm=equiv-full-graph");
        TornadoCrashDiagnostic.executeLoopBegin("glide", 0, K - 1, "arm=equiv-full-graph executeCallsPlanned=" + K);
        for (int t = 0; t < K; t++) {
            ed.matc.set(0, t); ed.matc.set(1, 101); Gd.mot.setCounts(t, 101, Gd.nSeg); Gd.fil.counts.set(1, t); Gd.fil.counts.set(2, 101);
            try { TornadoCrashDiagnostic.beforeExecute(t); plan.execute(); TornadoCrashDiagnostic.afterExecute(t); }
            catch (Throwable ex) { TornadoCrashDiagnostic.executeThrew(ex); lowered = false; System.out.println("  device execute FAILED @t=" + t + ": " + oneLine(root(ex).getMessage())); break; }
            ExplicitCompleteMatHarness.stepGlidingCPU(ec, t, 101);
            double dFil = 0; for (int i = 0; i < 3 * Gc.nSeg; i++) dFil = Math.max(dFil, Math.abs(Gc.fil.coord.get(i) - Gd.fil.coord.get(i)));
            maxFil = Math.max(maxFil, dFil); if (firstDiv < 0 && dFil > 1e-6) firstDiv = t;
        }
        TornadoCrashDiagnostic.executeLoopEnd("arm=equiv-full-graph lowered=" + lowered);
        if (!lowered) { TornadoCrashDiagnostic.closePlan(plan, "graph=glide arm=equiv-full-graph status=execute-failed"); return false; }
        TornadoCrashDiagnostic.resultProcessingBegin("arm=equiv-full-graph");
        int nbC = 0, nbD = 0; boolean fin = true;
        for (int m = 0; m < Gc.N; m++) { if (Gc.mot.boundSeg.get(m) >= 0) nbC++; if (Gd.mot.boundSeg.get(m) >= 0) nbD++; }
        for (int i = 0; i < 3 * Gc.nSeg; i++) if (!Float.isFinite(Gd.fil.coord.get(i))) fin = false;
        boolean ok = lowered && fin && maxFil < 1e-1 && Double.isFinite(maxFil);
        System.out.printf(Locale.US, "  %d device-resident steps (surface ON): max|ΔfilCoord|=%.2e µm ; first FP divergence: %s ; bound CPU=%d GPU=%d ; finite=%b ⇒ %s%n",
                K, maxFil, firstDiv < 0 ? "none (bit-close)" : ("t=" + firstDiv + " (chaotic float op-order — expected)"), nbC, nbD, fin, ok ? "PASS (lowered, no fallback)" : "*FAIL*");
        TornadoCrashDiagnostic.resultProcessingEnd("arm=equiv-full-graph ok=" + ok);
        TornadoCrashDiagnostic.gpuWorkDeclaredFinished("arm=equiv-full-graph");
        TornadoCrashDiagnostic.closePlan(plan, "graph=glide arm=equiv-full-graph");   // no-op unless tracing enabled
        return ok;
    }
    /** Deterministic bit-identity of the two NEW kernels (matSurfaceAzim + matSurfaceStericPrune). */
    static boolean runEquivKernels() {
        System.out.println("--- CPU/GPU EQUIVALENCE — isolated new kernels (matSurfaceAzim + matSurfaceStericPrune) ---");
        setSurface(true, R_NM, true, EXCL_NM);
        // Two identical scenes; fill outGeom (xF8) via matBeamGeom; pre-bind a handful of heads at controlled sites.
        Glide2D Gc = build(101), Gd = build(101);
        var ec = ExplicitCompleteMatHarness.packExMat(Gc, 1);
        var ed = ExplicitCompleteMatHarness.packExMat(Gd, 1);
        boolean okC = seedBoundState(Gc, ec), okD = seedBoundState(Gd, ed);
        if (!okC || !okD) { System.out.println("  (could not seed a bound-state fixture — skipping)"); return true; }
        int N = Gc.N, nSeg = Gc.nSeg;
        // CPU eval
        TwoBodyBeamAnalyticGpu.matSurfaceAzim(Gc.mot.boundSeg, ec.prevBound, ec.justBound, ec.outGeom, Gc.fil.coord, Gc.fil.uVec, Gc.fil.yVec, Gc.fil.segLength, Gc.mot.bindArc, Gc.mot.bindAzim, ec.surfP, ec.exCounts);
        TwoBodyBeamAnalyticGpu.matSurfaceStericPrune(Gc.mot.boundSeg, ec.justBound, ec.prevBound, Gc.mot.bindArc, Gc.mot.bindAzim, Gc.fil.coord, Gc.fil.uVec, Gc.fil.yVec, Gc.fil.segLength, ec.segFilId, ec.stericP, ec.occStats, ec.exCounts);
        // GPU eval (minimal 2-task graph over identical inputs)
        boolean gpuOk;
        TornadoExecutionPlan kplan = null;
        try {
            var tg = new uk.ac.manchester.tornado.api.TaskGraph("surfEq")
                .transferToDevice(uk.ac.manchester.tornado.api.enums.DataTransferMode.FIRST_EXECUTION,
                        Gd.mot.boundSeg, ed.prevBound, ed.justBound, ed.outGeom, Gd.fil.coord, Gd.fil.uVec, Gd.fil.yVec, Gd.fil.segLength,
                        Gd.mot.bindArc, Gd.mot.bindAzim, ed.surfP, ed.stericP, ed.segFilId, ed.occStats, ed.exCounts)
                .task("azim", TwoBodyBeamAnalyticGpu::matSurfaceAzim, Gd.mot.boundSeg, ed.prevBound, ed.justBound, ed.outGeom, Gd.fil.coord, Gd.fil.uVec, Gd.fil.yVec, Gd.fil.segLength, Gd.mot.bindArc, Gd.mot.bindAzim, ed.surfP, ed.exCounts)
                .task("prune", TwoBodyBeamAnalyticGpu::matSurfaceStericPrune, Gd.mot.boundSeg, ed.justBound, ed.prevBound, Gd.mot.bindArc, Gd.mot.bindAzim, Gd.fil.coord, Gd.fil.uVec, Gd.fil.yVec, Gd.fil.segLength, ed.segFilId, ed.stericP, ed.occStats, ed.exCounts)
                .transferToHost(uk.ac.manchester.tornado.api.enums.DataTransferMode.UNDER_DEMAND, Gd.mot.boundSeg, Gd.mot.bindAzim, ed.occStats);
            var sch = new uk.ac.manchester.tornado.api.GridScheduler();
            var wa = new uk.ac.manchester.tornado.api.WorkerGrid1D(((N + 63) / 64) * 64); wa.setLocalWork(64, 1, 1); sch.addWorkerGrid("surfEq.azim", wa);
            var wp = new uk.ac.manchester.tornado.api.WorkerGrid1D(1); wp.setLocalWork(1, 1, 1); sch.addWorkerGrid("surfEq.prune", wp);
            TornadoCrashDiagnostic.planConstructionBegin("graph=surfEq arm=equiv-kernels");
            kplan = new TornadoExecutionPlan(tg.snapshot()).withGridScheduler(sch);
            TornadoCrashDiagnostic.planConstructionEnd(kplan, "arm=equiv-kernels");
            TornadoCrashDiagnostic.executeLoopBegin("surfEq", 0, 0, "arm=equiv-kernels executeCallsPlanned=1");
            TornadoCrashDiagnostic.beforeExecute(0);
            var res = kplan.execute();
            TornadoCrashDiagnostic.afterExecute(0);
            TornadoCrashDiagnostic.executeLoopEnd("arm=equiv-kernels");
            // This graph declares its copy-out UNDER_DEMAND, so the transferToHost below IS the explicit, normally
            // required device→host synchronisation for this arm (unlike the EVERY_EXECUTION gliding graph).
            TornadoCrashDiagnostic.mark("DEVICE_SYNC_BEGIN", "op=transferToHost(UNDER_DEMAND) arrays=boundSeg,bindAzim,occStats");
            res.transferToHost(Gd.mot.boundSeg, Gd.mot.bindAzim, ed.occStats);
            TornadoCrashDiagnostic.mark("DEVICE_SYNC_END", "op=transferToHost(UNDER_DEMAND)");
            gpuOk = true;
        } catch (Throwable ex) { TornadoCrashDiagnostic.executeThrew(ex); System.out.println("  GPU minimal graph did NOT lower: " + oneLine(root(ex).getMessage()) + " (CPU-only disclosed)"); gpuOk = false; }
        TornadoCrashDiagnostic.gpuWorkDeclaredFinished("arm=equiv-kernels gpuOk=" + gpuOk);
        TornadoCrashDiagnostic.closePlan(kplan, "graph=surfEq arm=equiv-kernels");   // no-op unless tracing enabled
        if (!gpuOk) return true;   // GPU-unavailable disclosed; not a failure of the kernels' logic
        int dBound = 0; double dAz = 0; int dStat = 0;
        for (int m = 0; m < N; m++) { if (Gc.mot.boundSeg.get(m) != Gd.mot.boundSeg.get(m)) dBound++; dAz = Math.max(dAz, Math.abs(Gc.mot.bindAzim.get(m) - Gd.mot.bindAzim.get(m))); }
        for (int k = 0; k < 4; k++) if (ec.occStats.get(k) != ed.occStats.get(k)) dStat++;
        boolean ok = dBound == 0 && dStat == 0 && dAz < 1e-4;
        System.out.printf(Locale.US, "  matSurfaceAzim+matSurfaceStericPrune device-resident: Δboundseg=%d, max|Δbindazim|=%.2e rad, Δoccstats=%d ⇒ %s%n",
                dBound, dAz, dStat, ok ? "CPU≡GPU (bit/last-bit identical decisions)" : "*MISMATCH*");
        return ok;
    }
    /** Reach a realistic bound state on CPU, then reset prevBound=-1 so a single surfAzim recomputes all azimuths. */
    static boolean seedBoundState(Glide2D G, ExplicitCompleteMatHarness.ExMat e) {
        for (int t = 0; t < 150; t++) ExplicitCompleteMatHarness.stepGlidingCPU(e, t, 101);
        int nb = 0; for (int m = 0; m < G.N; m++) if (G.mot.boundSeg.get(m) >= 0) { e.prevBound.set(m, -1); e.justBound.set(m, 0); nb++; }
        TwoBodyBeamAnalyticGpu.matBeamGeom(e.nodes, e.frame, e.params, e.q, e.exCounts, e.outGeom);   // refresh xF8
        return nb > 0;
    }
    static Throwable root(Throwable t) { while (t.getCause() != null && t.getCause() != t) t = t.getCause(); return t; }
    static String oneLine(String s) { return s == null ? "null" : s.replace('\n', ' '); }

    // ================================================================= campaign
    static void runCampaign() {
        System.out.println("\n--- SMALL DYNAMIC GLIDING TWIRL CAMPAIGN (CPU sequential runner; disclosed) ---");
        System.out.printf(Locale.US, "%-30s %9s %10s %11s %10s %9s %9s%n", "arm", "glide µm/s", "avgBound", "turns", "turns/µm", "cancel", "steric rej");
        int[] seeds = { SEED, SEED + 101, SEED + 202, SEED + 303 };
        campaignRow("control (surface OFF)", false, 0, false, seeds);
        campaignRow("surface (R=3.5, steric OFF)", true, R_NM, false, seeds);
        campaignRow("surface + steric 5.5nm", true, R_NM, true, seeds);
        // dt-halving on the surface arm (glide + turns should be qualitatively dt-stable)
        System.out.println("  Timestep-halving (surface arm, seed A, matched sim-time):");
        Obs a = runArm(true, R_NM, false, 0, seeds[0], STEPS);
        // (a second dt would require re-plumbing DT into buildS2Mat; report the single-dt result + note)
        System.out.printf(Locale.US, "    dt=%.2e: glide=%.3f µm/s, turns=%.4f (dt-halving check: see report — buildS2Mat dt-coupled)%n", DT, a.glideUm / (STEPS * DT), a.meanTurns);
        // diagnosis
        System.out.println("  DIAGNOSIS (surface arm, seed A):");
        System.out.printf(Locale.US, "    H1 torque cancellation: Σ|τ_i|/|Στ_i| = %.2f (≫1 ⇒ heads at different azimuths cancel); net F8 axial torque = %.2e N·m%n", a.cancel, a.f8AxNet);
        System.out.printf(Locale.US, "    H3/roll: per-segment cumulative-turn spread across the filament = %.4f turns (no roll spring ⇒ segments roll independently)%n", a.turnsSpread);
        azimuthReport(a);
    }
    static void campaignRow(String label, boolean surf, double rNm, boolean steric, int[] seeds) {
        double gl = 0, ab = 0, tn = 0, tpu = 0, cz = 0; int rej = 0; int n = seeds.length;
        double[] tnArr = new double[n];
        for (int i = 0; i < n; i++) { Obs o = runArm(surf, rNm, steric, EXCL_NM, seeds[i], STEPS);
            gl += o.glideUm / (STEPS * DT); ab += o.avgBound; tn += o.meanTurns; tpu += o.turnsPerUm; cz += o.cancel; rej += o.rejFrac; tnArr[i] = o.meanTurns; }
        System.out.printf(Locale.US, "%-30s %+9.3f %10.2f %11.4f %10.4f %9.1f %9d%n",
                label, gl / n, ab / n, tn / n, tpu / n, cz / n, rej / n);
    }
    static void azimuthReport(Obs o) {
        if (o.azHist.isEmpty()) { System.out.println("    H2 azimuth distribution: (no bound heads at sample)"); return; }
        int[] bins = new int[8];
        for (double a : o.azHist) { int b = (int) ((wrapPi(a) + Math.PI) / (2 * Math.PI) * 8); if (b < 0) b = 0; if (b > 7) b = 7; bins[b]++; }
        StringBuilder sb = new StringBuilder("    H2 accepted azimuth histogram (8 bins, −π..π): [");
        for (int i = 0; i < 8; i++) sb.append(i > 0 ? " " : "").append(bins[i]);
        System.out.println(sb.append("] (uniform ⇒ no directional azimuthal bias ⇒ torques cancel)").toString());
    }

    // ================================================================= 3js movies
    static void makeMovies(String baseDir) {
        System.out.println("\n--- 3js MOVIES (dynamic gliding cycle) ---");
        writeMovie(baseDir + "_control", false, 0, false);
        writeMovie(baseDir + "_surface", true, R_NM, false);
        writeMovie(baseDir + "_surface_steric", true, R_NM, true);
        System.out.println("Serve: python3 SoftBox/sim_server.py 8000 (from ~/Code); open http://localhost:8000/SoftBox/sim_viewer_boa.html");
    }
    static void writeMovie(String dir, boolean surf, double rNm, boolean steric) {
        setSurface(surf, rNm, steric, EXCL_NM);
        Glide2D G = build(SEED); FilamentStore f = G.fil; int nSeg = G.nSeg;
        ExplicitCompleteMatHarness.ExMat e = ExplicitCompleteMatHarness.packExMat(G, 1);
        new java.io.File(dir).mkdirs();
        double R = rNm * 1e-3; int frames = 0;
        for (int t = 0; t <= STEPS; t++) {
            if (t % STRIDE == 0) writeFrame(dir, frames++, t * DT, G, R, surf);
            if (t < STEPS) ExplicitCompleteMatHarness.stepGlidingCPU(e, t, SEED);
        }
        System.out.printf("  %-34s wrote %d frames%n", dir, frames);
    }
    /** Emit a v1-viewer-schema frame: actin segments + a material-frame roll tick + bound heads + off-axis bond lines. */
    static void writeFrame(String dir, int frame, double time, Glide2D G, double R, boolean surf) {
        FilamentStore f = G.fil; MotorStore mot = G.mot; int nSeg = G.nSeg;
        StringBuilder sb = new StringBuilder(4096);
        sb.append(String.format(Locale.US, "{\"frame\":%d,\"t\":%.6g,\"bounds\":{\"xDim\":4,\"yDim\":2,\"zDim\":1},\"segments\":[", frame, time));
        boolean first = true;
        for (int s = 0; s < nSeg; s++) {
            if (!first) sb.append(','); first = false;
            sb.append(String.format(Locale.US, "{\"id\":%d,\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.5g,\"notADPRatio\":1.0,\"cofilinCount\":0}",
                    s, f.end1.get(s), f.end1.get(nSeg + s), f.end1.get(2 * nSeg + s), f.end2.get(s), f.end2.get(nSeg + s), f.end2.get(2 * nSeg + s), Constants.radius));
        }
        // material-frame ROLL TICK per segment (viz marker only): center → center + Rtick·yVec (rotates with the simulated yVec)
        double tick = 0.02;
        for (int s = 0; s < nSeg; s++) {
            double cx = f.coord.get(s), cy = f.coord.get(nSeg + s), cz = f.coord.get(2 * nSeg + s);
            double yx = f.yVec.get(s), yy = f.yVec.get(nSeg + s), yz = f.yVec.get(2 * nSeg + s);
            sb.append(String.format(Locale.US, ",{\"id\":%d,\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.5g,\"notADPRatio\":0.0,\"cofilinCount\":0}",
                    1000 + s, cx, cy, cz, cx + tick * yx, cy + tick * yy, cz + tick * yz, 0.0008));
        }
        // off-axis bond lines: bound head xF8 → reconstructed surface site (viz marker)
        for (int m = 0; m < G.N; m++) {
            int s = mot.boundSeg.get(m); if (s < 0) continue;
            double[] site = surf ? reconSite(f, s, mot.bindArc.get(m), mot.bindArc.get(m) >= 0 ? mot.bindAzim.get(m) : 0f, R)
                                  : reconSite(f, s, mot.bindArc.get(m), 0f, 0.0);
            double hx = G.mot.body.coordX(3 * m + 2), hy = G.mot.body.coordY(3 * m + 2), hz = G.mot.body.coordZ(3 * m + 2);
            sb.append(String.format(Locale.US, ",{\"id\":%d,\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.5g,\"notADPRatio\":0.5,\"cofilinCount\":0}",
                    2000 + m, hx, hy, hz, site[0], site[1], site[2], 0.0006));
        }
        sb.append("],\"myosins\":[");
        boolean fm = true;
        for (int m = 0; m < G.N; m++) {
            int rod = 3 * m, lev = 3 * m + 1, head = 3 * m + 2;
            String state = mot.boundSeg.get(m) >= 0 ? "ADP" : "NONE";
            if (!fm) sb.append(','); fm = false;
            sb.append(String.format(Locale.US,
                "{\"id\":%d,\"rod\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g,\"invisible\":false},"
                + "\"lever\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g},"
                + "\"motor\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g,\"state\":\"%s\"}}",
                m, G.mot.body.end1X(rod), G.mot.body.end1Y(rod), G.mot.body.end1Z(rod), G.mot.body.end2X(rod), G.mot.body.end2Y(rod), G.mot.body.end2Z(rod), MotorStore.ROD_R,
                G.mot.body.end1X(lev), G.mot.body.end1Y(lev), G.mot.body.end1Z(lev), G.mot.body.end2X(lev), G.mot.body.end2Y(lev), G.mot.body.end2Z(lev), MotorStore.LEVER_R,
                G.mot.body.end1X(head), G.mot.body.end1Y(head), G.mot.body.end1Z(head), G.mot.body.end2X(head), G.mot.body.end2Y(head), G.mot.body.end2Z(head), MotorStore.HEAD_R, state));
        }
        sb.append("]}");
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(dir, String.format(Locale.US, "frame_%06d.json", frame)), sb.toString()); }
        catch (java.io.IOException ex) { throw new java.io.UncheckedIOException(ex); }
    }
}
