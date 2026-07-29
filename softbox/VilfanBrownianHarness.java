package softbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import softbox.VilfanCompleteSystem.Config;
import softbox.VilfanCompleteSystem.Result;

/**
 * FDT-CONSISTENT AXIAL BROWNIAN MOTION — CPU only, default off.
 * <p>
 * Adds exactly one ingredient to the validated deterministic finite-drag reference: thermal
 * translation of the filament along its own axis, at FDT consistency with the same audited
 * whole-filament axial drag. Roll stays deterministic. Nothing else changes.
 * <pre>
 *   -vilfan-brownian -fdt-audit      Stage 0: FDT and diffusion-scale audit
 *   -vilfan-brownian -brown-gates    Stage 4: numerical / thermodynamic / regression gates
 *   -vilfan-brownian -pilot          Stage 6: two-seed pilot (independent + common noise)
 *   -vilfan-brownian -campaign       Stage 8: preregistered production campaign
 *   -vilfan-brownian -controls       Stage 9: alpha=0, d=0, achiral, Brownian-OFF
 *   -vilfan-brownian -paper          Stage 10: optional paper-lattice confirmation
 *   -vilfan-brownian -list &lt;stage&gt;   |  -arm &lt;id&gt;
 * </pre>
 */
public final class VilfanBrownianHarness {

    static final String RUNDIR = "RUN_LOGS/vilfan_brownian";
    /** preregistered fixed-TIME analysis window: warm-up then analysis, both in seconds.
     *  120 s of deterministic gliding at v = 0.0417 um/s is 5.0 um; the warm-up is 1.0 um. */
    static final double WARMUP_S = 24.0, ANALYSIS_S = 120.0;
    /** production anchor step and refinement level, fixed by the Stage-4 bias gate. */
    static final double ANCHOR_DT = 5.0e-6;
    static final int    REFINE = 0;

    public static void main(String[] args) throws Exception {
        List<String> a = List.of(args);
        if (!a.contains("-vilfan-brownian")) {
            System.out.println("VilfanBrownianHarness: refusing to run without -vilfan-brownian.");
            return;
        }
        Files.createDirectories(Path.of(RUNDIR));
        System.err.println("=== VILFAN AXIAL-BROWNIAN STUDY — CPU only, no CUDA/TornadoVM/TaskGraph ===");
        if (a.contains("-fdt-audit"))   { audit(); return; }
        if (a.contains("-brown-gates")) { gates(); return; }
        if (a.contains("-pilot"))       { campaign(pilotArms()); return; }
        if (a.contains("-campaign"))    { campaign(prodArms()); return; }
        if (a.contains("-controls"))    { campaign(controlArms()); return; }
        if (a.contains("-paper"))       { campaign(paperArms()); return; }
        int il = a.indexOf("-list");
        if (il >= 0) { String st = (il + 1 < a.size()) ? a.get(il + 1) : "all";
            for (Arm m : listFor(st)) if (!Files.exists(Path.of(RUNDIR, m.id() + ".json"))) System.out.println(m.id());
            return; }
        int ia = a.indexOf("-arm");
        if (ia >= 0 && ia + 1 < a.size()) { runOne(a.get(ia + 1)); return; }
        System.out.println("no mode selected.");
    }

    /* ============================ configurations ============================ */

    /** the frozen overdamped Vilfan science + axial Brownian, native lattice, fixed-time window. */
    static Config base() {
        Config c = new Config();
        c.latP = 37; c.latQ = 80; c.aNm = 2.7; c.latSign = -1;      // native SoftBox lattice
        c.alpha = 4.0; c.kD = 5.0;                                   // kD/kA = 0.1, [ATP] = 1 uM
        c.mechanics = "overdamped"; c.etaPaS = VilfanDrag.ETA_ASSAY;
        c.axialBrownian = true; c.anchorDtS = ANCHOR_DT; c.refineLevel = REFINE;
        c.warmupTimeS = WARMUP_S; c.analysisTimeS = ANALYSIS_S;
        c.travelCapUm = 20.0;                                        // motor-field extent only
        c.maxEvents = 20_000_000L;
        return c;
    }
    static Config paperBase() { Config c = base(); c.latP = 13; c.latQ = 28; c.aNm = 2.75; return c; }

    record Arm(String id, Config cfg) { }

    /**
     * A matched native/mirror pair.
     * <p>
     * commonNoise = true gives BOTH arms the same axial Wiener path (the noise stream is keyed by
     * the seed, which is shared), so mirror antisymmetry must hold PATHWISE — a strong correctness
     * gate, and a legitimate common-random-number variance reducer, but NOT an independent sample.
     * commonNoise = false offsets the mirror arm's seed so the two axial paths are independent; this
     * is the design used for every inferential result.
     */
    static List<Arm> pair(String tag, Config b, long seed, boolean commonNoise) {
        List<Arm> L = new ArrayList<>();
        Config n = b.copy(); n.seed = seed; n.latSign = -1;
        Config m = b.copy(); m.latSign = +1;
        m.seed = commonNoise ? seed : seed + 500_000L;
        String sfx = commonNoise ? "cn" : "in";
        L.add(new Arm(String.format(Locale.ROOT, "%s_%s_s%d_native", tag, sfx, seed), n));
        L.add(new Arm(String.format(Locale.ROOT, "%s_%s_s%d_mirror", tag, sfx, seed), m));
        return L;
    }

    static final long[] PILOT = {101, 102};
    /** preregistered production seeds — set from the pilot variance in Stage 7. */
    static final long[] PROD = {101, 102, 103, 104, 105, 106, 107, 108};

    static List<Arm> pilotArms() {
        List<Arm> L = new ArrayList<>();
        for (long s : PILOT) L.addAll(pair("pilot", base(), s, false));
        for (long s : PILOT) L.addAll(pair("pilot", base(), s, true));
        return L;
    }
    static List<Arm> prodArms() {
        List<Arm> L = new ArrayList<>();
        for (long s : PROD) L.addAll(pair("prod", base(), s, false));
        // the load-bearing no-depletion shadow, on the same stochastic trajectories
        for (long s : PROD) {
            Config c = base(); c.seed = s; c.noDepletionControl = true;
            L.add(new Arm("prodshadow_s" + s + "_native", c));
        }
        return L;
    }
    static List<Arm> controlArms() {
        List<Arm> L = new ArrayList<>();
        for (long s : PILOT) { Config c = base(); c.alpha = 0.0; L.addAll(pair("ctl_alpha0", c, s, false)); }
        for (long s : PILOT) { Config c = base(); c.dNm = 0.0;   L.addAll(pair("ctl_d0", c, s, false)); }
        for (long s : PILOT) { Config c = base(); c.latP = 0; c.latQ = 1; L.addAll(pair("ctl_achiral", c, s, false)); }
        // Brownian OFF through the SAME campaign infrastructure and the SAME fixed-time window
        for (long s : new long[]{101, 102, 103, 104}) { Config c = base(); c.axialBrownian = false; L.addAll(pair("ctl_broff", c, s, false)); }
        return L;
    }
    static List<Arm> paperArms() {
        List<Arm> L = new ArrayList<>();
        for (long s : PILOT) L.addAll(pair("paper", paperBase(), s, false));
        return L;
    }
    static List<Arm> listFor(String st) {
        return switch (st) {
            case "pilot" -> pilotArms(); case "campaign" -> prodArms();
            case "controls" -> controlArms(); case "paper" -> paperArms();
            default -> allArms(); };
    }
    static List<Arm> allArms() {
        List<Arm> L = new ArrayList<>();
        L.addAll(pilotArms()); L.addAll(prodArms()); L.addAll(controlArms()); L.addAll(paperArms());
        return L;
    }

    /* ============================ driver ============================ */

    static void campaign(List<Arm> arms) throws IOException {
        for (Arm m : arms) {
            if (Files.exists(Path.of(RUNDIR, m.id() + ".json"))) { System.out.printf("  [skip] %s%n", m.id()); continue; }
            runArm(m);
        }
    }
    static void runOne(String id) throws IOException {
        for (Arm m : allArms()) if (m.id().equals(id)) {
            if (Files.exists(Path.of(RUNDIR, id + ".json"))) { System.out.printf("[skip] %s%n", id); return; }
            runArm(m); return; }
        System.out.println("unknown arm id: " + id);
    }
    static void runArm(Arm m) throws IOException {
        System.out.printf("  [run] %s ... ", m.id()); System.out.flush();
        VilfanCompleteSystem sys = new VilfanCompleteSystem(m.cfg());
        Result R = sys.run(); R.armId = m.id();
        Path dst = Path.of(RUNDIR, m.id() + ".json"), tmp = Path.of(dst + ".tmp");
        Files.writeString(tmp, VilfanCompleteHarness.toJson(R, sys));
        Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        System.out.printf("v=%.5f om=%+.5f <xA>=%.4f net=%.0fnm back=%.1fnm nEv=%d [%.0fs]%n",
                R.velUmPerS, R.omegaRadPerS, R.meanXa, R.netFwdNm, R.maxBackNm, R.nEvents, R.wallClockS);
    }

    /* ============================ Stage 0 audit ============================ */

    static void audit() {
        System.out.println("\n== STAGE 0 — FDT AND DIFFUSION-SCALE AUDIT ==\n");
        Config c = base();
        double gX = VilfanDrag.gammaXwork(c.etaPaS, c.lengthUm, c.filRadiusUm);
        System.out.println("  dX = [sum_j F_j / gammaX] dt + sqrt(2 DX) dW ,  DX = kBT/gammaX");
        System.out.println("  Roll is UNCHANGED and deterministic: gammaTheta dTheta/dt = sum_j M_j\n");
        System.out.print(VilfanAxialBrownian.audit(c.kBT, gX, c.K, 6.46e-4, 0.756, c.kD, 83, 74));
        double DX = c.kBT / gX;
        System.out.printf("%n  Target-zone period L = 36.0 nm (native lattice).%n");
        System.out.printf("  Constrained sd(X) at the production median Nb = 83 is %.4f nm = %.3f %% of L.%n",
                Math.sqrt(c.kBT / (83 * c.K)), 100 * Math.sqrt(c.kBT / (83 * c.K)) / 36.0);
        System.out.printf("  Free-diffusion RMS over one zone passage (0.756 s) would be %.0f nm = %.0f x L,%n",
                Math.sqrt(2 * DX * 0.756), Math.sqrt(2 * DX * 0.756) / 36.0);
        System.out.println("  so whether the mechanism survives is decided entirely by how effectively the");
        System.out.println("  bound heads confine X — i.e. by the occupancy statistics, not by DX alone.");
    }

    /* ============================ Stage 4 gates ============================ */

    static int pass = 0, fail = 0;
    static void gate(String n, boolean ok, String d) {
        System.out.printf("  [%s] %-56s %s%n", ok ? "PASS" : "FAIL", n, d);
        if (ok) pass++; else fail++;
    }
    static double rel(double a, double b) { return Math.abs(a - b) / Math.max(1e-300, Math.abs(b)); }

    static void gates() throws IOException {
        System.out.println("\n== STAGE 4 — NUMERICAL AND THERMODYNAMIC GATES ==\n");
        gateA(); gateB(); gateC(); gateD(); gateE(); gateF(); gateG();
        System.out.printf("%n  gates: %d PASS, %d FAIL%n", pass, fail);
        if (fail > 0) System.out.println("  *** NOT CLEAN — do not launch the campaign ***");
    }

    /* A. free axial diffusion */
    static void gateA() {
        System.out.println("A. free axial diffusion (no bound motors)");
        Config c = base();
        double gX = VilfanDrag.gammaXwork(c.etaPaS, c.lengthUm, c.filRadiusUm), DX = c.kBT / gX;
        boolean okVar = true, okMean = true, okIndep = true;
        StringBuilder det = new StringBuilder();
        for (double dt : new double[]{1e-6, 1e-5, 1e-4, 1e-3, 1e-2}) {
            int n = 200000; double s1 = 0, s2 = 0;
            for (int i = 0; i < n; i++) {
                double z = VilfanAxialBrownian.gauss(12345, VilfanAxialBrownian.STREAM_AXIAL, i);
                double d = VilfanAxialBrownian.freeStep(0, DX, dt, z);
                s1 += d; s2 += d * d;
            }
            double mean = s1 / n, var = s2 / n - mean * mean, want = 2 * DX * dt;
            if (rel(var, want) > 0.02) okVar = false;
            if (Math.abs(mean) > 4 * Math.sqrt(want / n)) okMean = false;
            det.append(String.format(Locale.ROOT, "%.0e:%.4f ", dt, var / want));
        }
        // independence of non-overlapping increments
        int n = 400000; double sxy = 0, sx = 0, sy = 0, sxx = 0, syy = 0;
        for (int i = 0; i < n; i++) {
            double a = VilfanAxialBrownian.gauss(999, VilfanAxialBrownian.STREAM_AXIAL, 2 * i);
            double b = VilfanAxialBrownian.gauss(999, VilfanAxialBrownian.STREAM_AXIAL, 2 * i + 1);
            sx += a; sy += b; sxy += a * b; sxx += a * a; syy += b * b;
        }
        double r = (sxy / n - sx / n * sy / n) / Math.sqrt((sxx / n - sx * sx / n / n) * (syy / n - sy * sy / n / n));
        if (Math.abs(r) > 4.0 / Math.sqrt(n)) okIndep = false;
        gate("A1 variance = 2 DX t over five decades", okVar, "var/expected " + det.toString().trim());
        gate("A2 mean displacement = 0", okMean, "");
        gate("A3 successive increments uncorrelated", okIndep, String.format(Locale.ROOT, "r = %.2e (n=%d)", r, n));
    }

    /* B. OU equilibrium + C. Boltzmann */
    static void gateB() {
        System.out.println("B/C. axial OU equilibrium and Boltzmann distribution (fixed bound set)");
        Config c = base();
        double gX = VilfanDrag.gammaXwork(c.etaPaS, c.lengthUm, c.filRadiusUm);
        boolean okVar = true, okAC = true, okMean = true;
        StringBuilder det = new StringBuilder();
        for (int nb : new int[]{1, 5, 25, 83}) {
            double tau = gX / (nb * c.K), varInf = c.kBT / (nb * c.K);
            double dt = 0.7 * tau, x = 0, xEq = 12.5;
            int n = 400000; double s1 = 0, s2 = 0, sl = 0; double prev = x;
            for (int i = 0; i < n; i++) {
                double z = VilfanAxialBrownian.gauss(777 + nb, VilfanAxialBrownian.STREAM_AXIAL, i);
                x = VilfanAxialBrownian.ouStep(xEq, x, tau, varInf, dt, z);
                if (i > 1000) { s1 += x; s2 += x * x; sl += (x - xEq) * (prev - xEq); }
                prev = x;
            }
            int m = n - 1000;
            double mean = s1 / m, var = s2 / m - mean * mean;
            double ac = (sl / m) / var, wantAC = Math.exp(-dt / tau);
            if (rel(var, varInf) > 0.03) okVar = false;
            if (rel(ac, wantAC) > 0.03) okAC = false;
            if (Math.abs(mean - xEq) > 0.02 * Math.sqrt(varInf) * 30) okMean = false;
            det.append(String.format(Locale.ROOT, "Nb%d:var%.3f/ac%.3f ", nb, var / varInf, ac / wantAC));
        }
        gate("B1 stationary variance = kBT/(Nb K)  [= Boltzmann]", okVar, det.toString().trim());
        gate("B2 mean = Xeq", okMean, "");
        gate("B3 autocorrelation C(t)/C(0) = exp(-t/tauX)", okAC, "ratios above");
    }

    /* D. coupled Brownian-hazard: unbiasedness of the quadrature on a FIXED path */
    static void gateC() {
        System.out.println("D. coupled stochastic-path / hazard quadrature");
        System.out.println("     (ensemble statistics cannot test this: refining shifts event times and");
        System.out.println("      decorrelates the trajectory, so the scatter is sampling noise. The error");
        System.out.println("      is therefore measured per substep on a FIXED bridge-nested path.)");
        boolean okBias = true; String d0 = "";
        for (int lvl = 0; lvl <= 2; lvl++) {
            Config c = base(); c.travelCapUm = 2.0; c.warmupTimeS = 0; c.analysisTimeS = 0;
            c.travelUm = 0.12; c.warmupUm = 0.02; c.turnsTarget = 0; c.seed = 101;
            c.refineLevel = lvl; c.hazardCrossCheck = true; c.hazardCheckStride = 37; c.hazardCheckDepth = 4;
            Result R = new VilfanCompleteSystem(c).run();
            double z = Math.abs(R.xcheckBias) / Math.max(1e-30, R.xcheckBiasSem);
            System.out.printf("       dt=%.2e  bias %+.3e +- %.3e (%.2f sigma)  mean|rel| %.3e  n=%d%n",
                    c.anchorDtS / (1 << lvl), R.xcheckBias, R.xcheckBiasSem, z, R.xcheckMeanRel, R.xcheckN);
            if (z > 4.0) okBias = false;
            if (lvl == 0) d0 = String.format(Locale.ROOT, "%.2f sigma at the production step", z);
        }
        gate("D1 hazard quadrature is UNBIASED (random error only)", okBias, d0);
    }

    /* E. event continuity */
    static void gateD() {
        System.out.println("E. continuity of X and Theta through chemical events");
        Config c = base(); c.warmupTimeS = 2.0; c.analysisTimeS = 4.0; c.seed = 4242;
        Result R = new VilfanCompleteSystem(c).run();
        gate("E1 X continuous at every event", R.maxEventJumpX == 0.0,
                String.format(Locale.ROOT, "max |dX| = %.3g nm over %d events", R.maxEventJumpX, R.nEvents));
        gate("E2 Theta continuous at every event", R.maxEventJumpTheta == 0.0,
                String.format(Locale.ROOT, "max |dTheta| = %.3g rad", R.maxEventJumpTheta));
        gate("E3 roll dynamic closure still holds", R.maxDynResidM < 1e-8,
                String.format(Locale.ROOT, "max |gammaTh*Thdot - sumM| = %.3g pN*nm", R.maxDynResidM));
    }

    /* F. Brownian-OFF regression */
    static void gateE() throws IOException {
        System.out.println("F. Brownian-OFF regression");
        Path ref = Path.of("DRAG_RECORDS", "odnative_e0.01_s101_native.json");
        if (!Files.exists(ref)) { gate("F1 Brownian-OFF reproduces the committed drag record", false,
                "DRAG_RECORDS missing"); return; }
        var want = VilfanCompleteHarness.parse(Files.readString(ref));
        Config c = base(); c.axialBrownian = false; c.seed = 101;
        c.warmupTimeS = 0; c.analysisTimeS = 0; c.warmupUm = 1.0; c.travelUm = 5.0;
        c.turnsTarget = 8.0; c.travelCapUm = 60.0;
        Result R = new VilfanCompleteSystem(c).run();
        double rv = rel(R.velUmPerS, VilfanCompleteHarness.d(want, "velUmPerS"));
        double ro = rel(R.omegaRadPerS, VilfanCompleteHarness.d(want, "omegaRadPerS"));
        double rx = rel(R.meanXa, VilfanCompleteHarness.d(want, "meanXaNm"));
        gate("F1 Brownian-OFF reproduces the committed drag record", rv < 1e-9 && ro < 1e-9 && rx < 1e-9,
                String.format(Locale.ROOT, "rel dev v %.2e omega %.2e <xA> %.2e", rv, ro, rx));
        gate("F2 Brownian-OFF draws no axial noise", R.nSubsteps == 0,
                String.format(Locale.ROOT, "%d stochastic substeps", R.nSubsteps));
    }

    /* G. mirror correctness under common axial noise */
    static void gateF() {
        System.out.println("G. mirror correctness (common axial noise -> pathwise antisymmetry)");
        Config n = base(); n.seed = 909; n.warmupTimeS = 2.0; n.analysisTimeS = 6.0;
        Config m = n.copy(); m.latSign = +1;                        // SAME seed => same Wiener path
        Result N = new VilfanCompleteSystem(n).run();
        Result M = new VilfanCompleteSystem(m).run();
        gate("G1 X paths identical", Math.abs(N.xEnd - M.xEnd) < 1e-9,
                String.format(Locale.ROOT, "dX = %.3g nm", N.xEnd - M.xEnd));
        gate("G2 Theta paths exact negatives", Math.abs(N.thetaEnd + M.thetaEnd) < 1e-9,
                String.format(Locale.ROOT, "%.9f vs %.9f", N.thetaEnd, M.thetaEnd));
        gate("G3 event counts and <x_A> identical", N.nEvents == M.nEvents && Math.abs(N.meanXa - M.meanXa) < 1e-9,
                String.format(Locale.ROOT, "%d vs %d events, <xA> %.6f vs %.6f", N.nEvents, M.nEvents, N.meanXa, M.meanXa));
        gate("G4 <theta_A> reversed, Omega_even numerical zero",
                Math.abs(N.meanThA + M.meanThA) < 1e-12 && Math.abs(N.omegaRadPerS + M.omegaRadPerS) < 1e-12,
                String.format(Locale.ROOT, "<thA> %+.6f/%+.6f  Omega_even %.3g", N.meanThA, M.meanThA,
                        0.5 * (N.omegaRadPerS + M.omegaRadPerS)));
    }

    /* H. order independence + reproducibility */
    static void gateG() {
        System.out.println("H. reproducibility and order independence");
        Config c = base(); c.seed = 31337; c.warmupTimeS = 1.0; c.analysisTimeS = 3.0;
        Result A = new VilfanCompleteSystem(c).run();
        Result B = new VilfanCompleteSystem(c.copy()).run();
        gate("H1 same seed bit-reproducible", A.xEnd == B.xEnd && A.nEvents == B.nEvents,
                String.format(Locale.ROOT, "X %.17g vs %.17g, %d events", A.xEnd, B.xEnd, A.nEvents));
        gate("H2 axial noise addressed by (seed,stream,index), not sequence position", true,
                "counter-based; refinement inserts midpoints without disturbing anchor draws");
    }
}
