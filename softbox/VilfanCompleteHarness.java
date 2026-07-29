package softbox;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;

import softbox.VilfanCompleteSystem.Config;
import softbox.VilfanCompleteSystem.Result;

/**
 * VILFAN-COMPLETE REFERENCE HARNESS — CPU only, default off.
 * <p>
 * Entry point for the standalone Vilfan (2009) complete-model reference. It is selected explicitly
 * with {@code -vilfan-complete}; it shares no code path with any other SoftBox harness, so a run
 * that does not name it is unaffected (byte-identity is by construction — no existing file is
 * modified by this study).
 * <p>
 * Modes:
 * <pre>
 *   -vilfan-complete -gates                 Stage 3 unit and analytical gates A-G
 *   -vilfan-complete -landscape             static target-zone landscape diagnostic (both lattices)
 *   -vilfan-complete -paper                 Stage 4 paper-exact positive control (4 arms)
 *   -vilfan-complete -nodep                 Stage 5 no-depletion (shadow) control at kD/kA = 0.1
 *   -vilfan-complete -sweep                 Stage 5 preregistered kD/kA sweep
 *   -vilfan-complete -native                Stage 6 native SoftBox lattice transfer
 *   -vilfan-complete -alpha-check           Stage 7 optional alpha = 6, 8
 *   -vilfan-complete -arm &lt;id&gt;              run a single named arm (used by the launcher)
 *   -vilfan-complete -report                re-report from stored records (no simulation)
 * </pre>
 */
public final class VilfanCompleteHarness {

    static final String RUNDIR = "RUN_LOGS/vilfan_complete";
    static final double TWO_PI = 2.0 * Math.PI;
    static PrintStream out = System.out;

    /* =============================== main =============================== */

    public static void main(String[] args) throws Exception {
        List<String> a = List.of(args);
        if (!a.contains("-vilfan-complete")) {
            out.println("VilfanCompleteHarness: refusing to run without the explicit -vilfan-complete flag.");
            out.println("This reference mode is default-off by design. See docs/twirling/vilfan_target_zone/"
                      + "VILFAN_COMPLETE_REFERENCE_VALIDATION.md");
            return;
        }
        Files.createDirectories(Path.of(RUNDIR));
        banner();

        if (a.contains("-gates"))       { gates(); return; }
        if (a.contains("-landscape"))   { landscape(); return; }
        if (a.contains("-paper"))       { campaign(paperArms()); return; }
        if (a.contains("-nodep"))       { campaign(noDepArms()); return; }
        if (a.contains("-sweep"))       { campaign(sweepArms()); return; }
        if (a.contains("-native"))      { campaign(nativeArms()); return; }
        if (a.contains("-alpha-check")) { campaign(alphaArms()); return; }
        if (a.contains("-report"))      { report(); return; }
        int il = a.indexOf("-list");
        if (il >= 0) {
            String stage = (il + 1 < a.size()) ? a.get(il + 1) : "all";
            for (Arm arm : listFor(stage)) if (!Files.exists(Path.of(RUNDIR, arm.id() + ".json"))) out.println(arm.id());
            return;
        }
        int ia = a.indexOf("-arm");
        if (ia >= 0 && ia + 1 < a.size()) { runOneArm(a.get(ia + 1)); return; }
        out.println("no mode selected; see the class javadoc.");
    }

    /** banner goes to stderr so that -list emits a clean, pipe-safe arm list. */
    static void banner() {
        System.err.println("=========================================================================");
        System.err.println(" VILFAN-COMPLETE REFERENCE  (Vilfan 2009, Biophys J 97:1130; arXiv:0906.0784v1)");
        System.err.println(" CPU only. No CUDA / TornadoVM / TaskGraph / device context is touched.");
        System.err.println("=========================================================================");
    }

    /* =============================== arm definitions =============================== */

    /** one campaign arm: an id plus a fully-specified Config. */
    record Arm(String id, Config cfg) { }

    static Config paperBase() {
        Config c = new Config();
        c.latP = 13; c.latQ = 28; c.aNm = 2.75;   // Vilfan Table I lattice
        c.latSign = -1;                            // native (left-handed genetic helix)
        c.alpha = 4.0;
        c.kD = 5.0;                                // kD/kA = 0.1 (the favourable point), [ATP] = 1 uM
        return c;
    }
    static Config nativeBase() {
        Config c = paperBase();
        c.latP = 37; c.latQ = 80; c.aNm = 2.7;    // SoftBox native: theta0 = -166.5 deg, rise 2.7 nm
        return c;
    }

    static List<Arm> armsFor(String tag, Config base, double[] kdOverKa, double[] alphas, long[] seeds) {
        List<Arm> L = new ArrayList<>();
        for (double rk : kdOverKa) for (double al : alphas) for (long s : seeds) for (int sign : new int[]{-1, +1}) {
            Config c = base.copy();
            c.kD = rk * c.kA; c.alpha = al; c.seed = s; c.latSign = sign;
            String id = String.format(Locale.ROOT, "%s_kd%.4g_a%.3g_s%d_%s",
                    tag, rk, al, s, sign < 0 ? "native" : "mirror");
            L.add(new Arm(id, c));
        }
        return L;
    }

    static List<Arm> paperArms()  { return armsFor("paper",  paperBase(),  new double[]{0.1}, new double[]{4}, new long[]{101, 102, 103, 104}); }
    static List<Arm> nativeArms() { return armsFor("native", nativeBase(), new double[]{0.1}, new double[]{4}, new long[]{101, 102, 103, 104}); }
    static List<Arm> sweepArms()  {
        return armsFor("sweep", paperBase(), new double[]{0.03, 0.10, 0.30, 1.0, 3.0, 10.0, 18.2},
                       new double[]{4}, new long[]{101, 102, 103, 104});
    }
    static List<Arm> alphaArms()  { return armsFor("alpha", paperBase(), new double[]{0.1}, new double[]{6, 8}, new long[]{101, 102, 103, 104}); }
    static List<Arm> noDepArms()  {
        List<Arm> L = new ArrayList<>();
        for (long s : new long[]{101, 102, 103, 104}) {
            Config c = paperBase(); c.seed = s; c.noDepletionControl = true;
            L.add(new Arm(String.format(Locale.ROOT, "nodep_kd0.1_a4_s%d_native", s), c));
        }
        return L;
    }
    static List<Arm> listFor(String stage) {
        return switch (stage) {
            case "paper"  -> paperArms();  case "nodep"  -> noDepArms();
            case "sweep"  -> sweepArms();  case "native" -> nativeArms();
            case "alpha"  -> alphaArms();  default       -> allArms();
        };
    }
    static List<Arm> allArms() {
        List<Arm> L = new ArrayList<>();
        L.addAll(paperArms()); L.addAll(noDepArms()); L.addAll(sweepArms());
        L.addAll(nativeArms()); L.addAll(alphaArms());
        return L;
    }

    /* =============================== campaign driver =============================== */

    static void campaign(List<Arm> arms) throws IOException {
        out.printf("campaign: %d arms%n", arms.size());
        for (Arm arm : arms) {
            Path rec = Path.of(RUNDIR, arm.id() + ".json");
            if (Files.exists(rec)) { out.printf("  [skip, done] %s%n", arm.id()); continue; }
            runArm(arm);
        }
        out.println("campaign complete.");
        report();
    }

    static void runOneArm(String id) throws IOException {
        for (Arm arm : allArms()) if (arm.id().equals(id)) {
            Path rec = Path.of(RUNDIR, id + ".json");
            if (Files.exists(rec)) { out.printf("[skip, done] %s%n", id); return; }
            runArm(arm); return;
        }
        out.println("unknown arm id: " + id);
    }

    static void runArm(Arm arm) throws IOException {
        out.printf("  [run] %s ... ", arm.id()); out.flush();
        VilfanCompleteSystem sys = new VilfanCompleteSystem(arm.cfg());
        Result R = sys.run();
        R.armId = arm.id();
        writeAtomic(Path.of(RUNDIR, arm.id() + ".json"), toJson(R, sys));
        out.printf("v=%.4g um/s  omega=%.4g rad/s  pitch=%.4g um  <xA>=%.3f nm  turns=%.1f  [%.1fs]%n",
                R.velUmPerS, R.omegaRadPerS, R.pitchUmPerTurn, R.meanXa, R.turns, R.wallClockS);
    }

    static void writeAtomic(Path dst, String body) throws IOException {
        Path tmp = Path.of(dst.toString() + ".tmp");
        Files.writeString(tmp, body);
        Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static String toJson(Result R, VilfanCompleteSystem sys) {
        Config c = R.cfg;
        StringBuilder b = new StringBuilder();
        b.append("{\n");
        kv(b, "armId", "\"" + R.armId + "\"");
        kv(b, "latP", c.latP); kv(b, "latQ", c.latQ); kv(b, "latSign", c.latSign);
        kv(b, "theta0deg", c.theta0Deg()); kv(b, "aNm", c.aNm);
        kv(b, "alpha", c.alpha); kv(b, "kA", c.kA); kv(b, "kD", c.kD);
        kv(b, "kDoverKA", c.kD / c.kA); kv(b, "kPS", c.kPS); kv(b, "kADP", c.kADP);
        kv(b, "dNm", c.dNm); kv(b, "K", c.K); kv(b, "kBT", c.kBT); kv(b, "polarity", c.polarity);
        kv(b, "seed", c.seed); kv(b, "window", c.window);
        kv(b, "singleOccupancy", c.singleOccupancy); kv(b, "placement", "\"" + c.placement + "\"");
        kv(b, "lengthUm", c.lengthUm); kv(b, "densityPerUm", c.densityPerUm);
        kv(b, "nSites", R.nSites); kv(b, "nMotors", R.nMotors);
        kv(b, "zonePeriodNm", R.LNm); kv(b, "grooveSlopeRadPerNm", R.grooveSlope);
        kv(b, "tWarm", R.tWarm); kv(b, "tEnd", R.tEnd);
        kv(b, "xWarmNm", R.xWarm); kv(b, "xEndNm", R.xEnd);
        kv(b, "thetaWarmRad", R.thetaWarm); kv(b, "thetaEndRad", R.thetaEnd);
        kv(b, "velUmPerS", R.velUmPerS); kv(b, "omegaRadPerS", R.omegaRadPerS);
        kv(b, "velLsqUmPerS", R.velLsq); kv(b, "omegaLsqRadPerS", R.omegaLsq);
        kv(b, "velFirstHalf", R.velFirstHalf); kv(b, "velSecondHalf", R.velSecondHalf);
        kv(b, "omegaFirstHalf", R.omegaFirstHalf); kv(b, "omegaSecondHalf", R.omegaSecondHalf);
        kv(b, "meanAttachTorquePnNm", R.meanAttachTorque);
        kv(b, "turns", R.turns); kv(b, "pitchUmPerTurn", R.pitchUmPerTurn);
        kv(b, "invPitchPerUm", R.invPitchPerUm);
        kv(b, "nEvents", R.nEvents); kv(b, "nAttach", R.nAttach); kv(b, "nStroke", R.nStroke);
        kv(b, "nAdpRelease", R.nAdpRelease); kv(b, "nDetach", R.nDetach);
        kv(b, "meanBound", R.meanBound); kv(b, "dutyRatio", R.dutyRatio);
        kv(b, "occDetached", R.stateOccupancy[0]); kv(b, "occPrePS", R.stateOccupancy[1]);
        kv(b, "occPostPS", R.stateOccupancy[2]); kv(b, "occRigor", R.stateOccupancy[3]);
        kv(b, "meanXaNm", R.meanXa); kv(b, "sdXaNm", R.sdXa);
        kv(b, "meanXiANm", R.meanXiA); kv(b, "sdXiANm", R.sdXiA);
        kv(b, "meanThARad", R.meanThA); kv(b, "sdThARad", R.sdThA);
        kv(b, "meanSiteForcePn", R.meanSiteForce); kv(b, "meanSiteTorquePnNm", R.meanSiteTorque);
        kv(b, "meanTotalForcePn", R.meanTotalForce); kv(b, "meanTotalTorquePnNm", R.meanTotalTorque);
        kv(b, "maxForceResidPn", R.maxForceResid); kv(b, "maxTorqueResidPnNm", R.maxTorqueResid);
        kv(b, "equilIterTotal", R.equilIterTotal); kv(b, "equilBranchChanges", R.equilBranchChanges);
        kv(b, "equilNonConverged", R.equilNonConverged); kv(b, "nbZeroEvents", R.nbZeroEvents);
        kv(b, "haveShadow", R.haveShadow); kv(b, "shadowMeanXaNm", R.shadowMeanXa);
        kv(b, "travelCapHit", R.travelCapHit); kv(b, "wallClockS", R.wallClockS);
        kv(b, "mechanics", "\"" + R.mechanics + "\""); kv(b, "etaPaS", R.etaPaS);
        kv(b, "dragScale", c.dragScale); kv(b, "filRadiusUm", c.filRadiusUm);
        kv(b, "gammaXpNsPerNm", R.gammaX); kv(b, "gammaThetapNnmSPerRad", R.gammaTheta);
        kv(b, "tauXmedS", R.tauXmed); kv(b, "tauThetaMedS", R.tauThetaMed); kv(b, "medianNb", R.medianNb);
        kv(b, "meanInterEventS", R.meanInterEventS); kv(b, "zonePassageS", R.zonePassageS);
        kv(b, "maxDynResidFpN", R.maxDynResidF); kv(b, "maxDynResidMpNnm", R.maxDynResidM);
        kv(b, "hazEvals", R.hazEvals); kv(b, "rootIters", R.rootIters);
        kv(b, "branchCrossings", R.branchCrossings); kv(b, "degenerateBranch", R.degenerateBranch);
        kv(b, "transientRootEvents", R.transientRootEvents); kv(b, "nEventsAnalysed", R.nEventsAnalysed);
        kv(b, "maxEventJumpX", R.maxEventJumpX); kv(b, "maxEventJumpTheta", R.maxEventJumpTheta);
        kv(b, "xcheckN", R.xcheckN); kv(b, "xcheckMaxRel", R.xcheckMaxRel);
        kv(b, "note", "\"" + R.note.replace("\"", "'") + "\"");
        b.append("  \"xaHist\": ").append(java.util.Arrays.toString(R.xaHist)).append(",\n");
        b.append("  \"shadowHist\": ").append(java.util.Arrays.toString(R.shadowHist)).append("\n");
        b.append("}\n");
        return b.toString();
    }
    static void kv(StringBuilder b, String k, Object v) {
        String s = (v instanceof Double d)
                ? (d.isNaN() || d.isInfinite() ? "null" : String.format(Locale.ROOT, "%.10g", d))
                : String.valueOf(v);
        b.append("  \"").append(k).append("\": ").append(s).append(",\n");
    }

    /* =============================== reporting =============================== */

    static void report() throws IOException {
        List<Path> recs = new ArrayList<>();
        try (var st = Files.list(Path.of(RUNDIR))) {
            st.filter(p -> p.toString().endsWith(".json")).sorted().forEach(recs::add);
        }
        if (recs.isEmpty()) { out.println("no records."); return; }
        out.println();
        out.println("---- records ----------------------------------------------------------------------------");
        out.printf("%-34s %10s %10s %10s %9s %8s %7s %7s%n",
                "arm", "v(um/s)", "om(rad/s)", "pitch(um)", "<xA>nm", "<thA>", "duty", "turns");
        for (Path p : recs) {
            var m = parse(Files.readString(p));
            out.printf("%-34s %10.4g %10.4g %10.4g %9.3f %8.4f %7.3f %7.1f%n",
                    m.get("armId").replace("\"", ""), d(m, "velUmPerS"), d(m, "omegaRadPerS"),
                    d(m, "pitchUmPerTurn"), d(m, "meanXaNm"), d(m, "meanThARad"),
                    d(m, "dutyRatio"), d(m, "turns"));
        }
        mirrorDecomposition(recs);
    }

    /** Omega_even/odd for every matched (native, mirror) pair. */
    static void mirrorDecomposition(List<Path> recs) throws IOException {
        out.println();
        out.println("---- mirror decomposition (matched seeds) -----------------------------------------------");
        out.printf("%-30s %12s %12s %12s %12s %10s%n",
                "pair", "Om_native", "Om_mirror", "Om_even", "Om_odd", "|odd/even|");
        for (Path p : recs) {
            String s = p.getFileName().toString();
            if (!s.endsWith("_native.json")) continue;
            Path q = Path.of(RUNDIR, s.replace("_native.json", "_mirror.json"));
            if (!Files.exists(q)) continue;
            var mn = parse(Files.readString(p));
            var mm = parse(Files.readString(q));
            double on = d(mn, "omegaRadPerS"), om = d(mm, "omegaRadPerS");
            double even = 0.5 * (on + om), odd = 0.5 * (on - om);
            out.printf("%-30s %12.5g %12.5g %12.5g %12.5g %10s%n",
                    s.replace("_native.json", ""), on, om, even, odd,
                    even == 0 ? "inf" : String.format(Locale.ROOT, "%.3g", Math.abs(odd / even)));
        }
    }

    static java.util.Map<String, String> parse(String json) {
        var m = new java.util.LinkedHashMap<String, String>();
        for (String line : json.split("\n")) {
            int c = line.indexOf("\":");
            if (c < 0 || !line.trim().startsWith("\"")) continue;
            String k = line.trim().substring(1, line.trim().indexOf("\":"));
            String v = line.substring(c + 2).trim();
            if (v.endsWith(",")) v = v.substring(0, v.length() - 1);
            m.put(k, v.trim());
        }
        return m;
    }
    static double d(java.util.Map<String, String> m, String k) {
        String v = m.get(k);
        if (v == null || v.equals("null")) return Double.NaN;
        try { return Double.parseDouble(v); } catch (Exception e) { return Double.NaN; }
    }

    /* =============================== static landscape diagnostic =============================== */

    static void landscape() {
        out.println("\n== STATIC TARGET-ZONE LANDSCAPE (X = Theta = 0, no dynamics) ==\n");
        for (String which : new String[]{"paper", "native"}) {
            Config c = which.equals("paper") ? paperBase() : nativeBase();
            VilfanCompleteSystem s = new VilfanCompleteSystem(c);
            out.printf("-- %s lattice: theta0 = %.4f deg (= %s%d/%d turn), a = %.3f nm, exact repeat %d subunits%n",
                    which, c.theta0Deg(), c.latSign < 0 ? "-" : "+", c.latP, c.latQ, c.aNm, c.latQ);
            out.printf("   effective groove slope %.6f rad/nm  ->  target-zone period L = %.3f nm (%.2f subunits)%n",
                    s.grooveSlopeRadPerNm(), s.targetZonePeriodNm(), s.targetZonePeriodNm() / c.aNm);
            // per-site angle over one exact repeat
            out.print("   |theta_i| (deg) for i = 0..: ");
            for (int i = 0; i <= Math.min(30, c.latQ); i++)
                out.printf("%.1f ", Math.toDegrees(Math.abs(VilfanCompleteSystem.wrapPi(s.baseAzim(i)))));
            out.println();
            // scan the total hazard for a motor across a full zone period
            double best = 0, worst = Double.MAX_VALUE; double sum = 0; int n = 400;
            double lo = s.targetZonePeriodNm() * -1.5;
            StringBuilder prof = new StringBuilder();
            for (int k = 0; k < n; k++) {
                double xm = lo + 3.0 * s.targetZonePeriodNm() * k / n;
                double h = s.landscapeHazard(xm + 1500.0, false);      // mid-filament motor
                best = Math.max(best, h); worst = Math.min(worst, h); sum += h;
            }
            out.printf("   total hazard over 3 zone periods: max %.4g /s, min %.4g /s, mean %.4g /s,"
                     + " modulation (max-min)/(max+min) = %.4f%n%n",
                    best, worst, sum / n, (best - worst) / (best + worst));
        }
    }

    /* =============================== Stage 3 gates =============================== */

    static int gatesPassed = 0, gatesFailed = 0;
    static void gate(String name, boolean ok, String detail) {
        out.printf("  [%s] %-58s %s%n", ok ? "PASS" : "FAIL", name, detail);
        if (ok) gatesPassed++; else gatesFailed++;
    }

    static void gates() {
        out.println("\n== STAGE 3 — UNIT AND ANALYTICAL GATES ==\n");
        gateA(); gateB(); gateC(); gateD(); gateE(); gateF(); gateG();
        out.printf("%n  gates: %d PASS, %d FAIL%n", gatesPassed, gatesFailed);
        if (gatesFailed > 0) out.println("  *** STAGE 3 NOT CLEAN — do not proceed to Stage 4 ***");
    }

    /* ---- A. attachment energy ---- */
    static void gateA() {
        out.println("A. attachment energy (Eq 1)");
        Config c = paperBase();
        VilfanCompleteSystem s = new VilfanCompleteSystem(c);
        SplittableRandom r = new SplittableRandom(7);
        boolean fin = true, nonneg = true, exact = true;
        double maxRel = 0;
        for (int k = 0; k < 200000; k++) {
            double X = r.nextDouble(-500, 500), Th = r.nextDouble(-50, 50), xm = r.nextDouble(-500, 500);
            int i = r.nextInt(0, s.nSites());
            double U = s.bindEnergy(X, Th, xm, i);
            if (!Double.isFinite(U)) fin = false;
            if (U < 0) nonneg = false;
            // independent direct evaluation
            double xi = X + i * c.aNm - xm;
            double raw = Th + c.latSign * ((double) ((13L * i) % 28) / 28.0) * TWO_PI;   // paper lattice, literal
            double th = raw - TWO_PI * Math.rint(raw / TWO_PI);
            double Uref = 0.5 * c.K * xi * xi + 0.5 * c.alpha * c.kBT * th * th;
            double rel = Math.abs(U - Uref) / Math.max(1e-30, Math.abs(Uref));
            maxRel = Math.max(maxRel, rel);
            if (rel > 1e-12) exact = false;
        }
        gate("A1 energy finite and non-negative", fin && nonneg, "200k random states");
        gate("A2 energy == direct equation evaluation", exact, String.format(Locale.ROOT, "max rel dev %.3g", maxRel));
        // units: U at 1 nm longitudinal mismatch = 0.5*0.5*1 = 0.25 pN nm = 0.0604 kBT
        double u1 = s.bindEnergy(0, 0, -c.aNm * 0 - 1.0, 0);
        gate("A3 units pN*nm and kBT", Math.abs(u1 - 0.25) < 1e-12,
                String.format(Locale.ROOT, "U(1 nm axial, 0 rad) = %.6f pN*nm = %.6f kBT", u1, u1 / c.kBT));
    }

    /* ---- B. attachment hazards ---- */
    static void gateB() {
        out.println("B. attachment hazards (Eq 2)");
        Config c = paperBase();
        VilfanCompleteSystem s = new VilfanCompleteSystem(c);
        SplittableRandom r = new SplittableRandom(11);
        boolean ok = true, cap = true;
        for (int k = 0; k < 200000; k++) {
            double X = r.nextDouble(-500, 500), Th = r.nextDouble(-50, 50), xm = r.nextDouble(0, 5000);
            int i = r.nextInt(0, s.nSites());
            double h = s.siteHazard(X, Th, xm, i);
            if (!Double.isFinite(h) || h < 0) ok = false;
            if (h > c.kA * (1 + 1e-12)) cap = false;
        }
        gate("B1 site hazard finite and non-negative", ok, "200k random states");
        gate("B2 no site hazard exceeds kA", cap, String.format(Locale.ROOT, "kA = %.1f /s", c.kA));

        // convergence with candidate range
        out.println("     candidate-window convergence (total hazard for one mid-filament motor):");
        int[] W = {2, 4, 6, 8, 12, 16, 24, 32, 48};
        double ref = 0; boolean conv = true; double relAt12 = 0;
        double xm = 1500.3;
        for (int w : W) {
            Config cw = c.copy(); cw.window = w; cw.singleOccupancy = false;
            VilfanCompleteSystem sw = new VilfanCompleteSystem(cw);
            double h = sw.landscapeHazard(xm, false);
            if (w == 48) ref = h;
        }
        for (int w : W) {
            Config cw = c.copy(); cw.window = w; cw.singleOccupancy = false;
            VilfanCompleteSystem sw = new VilfanCompleteSystem(cw);
            double h = sw.landscapeHazard(xm, false);
            double rel = Math.abs(h - ref) / ref;
            out.printf("       W=%-3d  K_total = %.12g /s   rel dev vs W=48: %.3g%n", w, h, rel);
            if (w == 12) relAt12 = rel;
            if (w >= 12 && rel > 1e-12) conv = false;
        }
        gate("B3 total hazard converged at the production window W=12", conv,
                String.format(Locale.ROOT, "rel dev vs W=48 = %.3g", relAt12));

        // conditional site probabilities sum to one, both enumeration orders
        double[] pf = s.siteProbabilities(0, 0.3, xm, 540, 566, false);
        double[] pr = s.siteProbabilities(0, 0.3, xm, 540, 566, true);
        double sf = 0, sr = 0, maxd = 0;
        for (int k = 0; k < pf.length; k++) { sf += pf[k]; sr += pr[k]; maxd = Math.max(maxd, Math.abs(pf[k] - pr[k])); }
        gate("B4 conditional site probabilities sum to 1", Math.abs(sf - 1) < 1e-12 && Math.abs(sr - 1) < 1e-12,
                String.format(Locale.ROOT, "sum fwd %.15f, rev %.15f", sf, sr));
        gate("B5 site probabilities independent of enumeration order", maxd < 1e-15,
                String.format(Locale.ROOT, "max |p_fwd - p_rev| = %.3g", maxd));
    }

    /* ---- C. Gillespie sampler ---- */
    static void gateC() {
        out.println("C. Gillespie sampler");
        // C1 exponential waiting times at constant rate
        SplittableRandom r = new SplittableRandom(21);
        double kt = 37.5; int n = 4000000; double s1 = 0, s2 = 0;
        for (int k = 0; k < n; k++) { double dt = VilfanCompleteSystem.expWait(r, kt); s1 += dt; s2 += dt * dt; }
        double mean = s1 / n, var = s2 / n - mean * mean;
        double relM = Math.abs(mean - 1 / kt) * kt, relV = Math.abs(var - 1 / (kt * kt)) * kt * kt;
        gate("C1 waiting times exponential (mean and variance)", relM < 2e-3 && relV < 5e-3,
                String.format(Locale.ROOT, "mean %.6g vs %.6g (%.2g), var rel dev %.2g", mean, 1 / kt, relM, relV));

        // C2 bit reproducibility of a full run
        Config c = paperBase(); c.travelUm = 0.30; c.warmupUm = 0.05; c.turnsTarget = 0;
        c.travelCapUm = 2.0; c.seed = 555;
        Result A = new VilfanCompleteSystem(c).run();
        Result B = new VilfanCompleteSystem(c.copy()).run();
        boolean same = A.xEnd == B.xEnd && A.thetaEnd == B.thetaEnd && A.tEnd == B.tEnd && A.nEvents == B.nEvents;
        gate("C2 same seed is bit-reproducible", same,
                String.format(Locale.ROOT, "X %.17g vs %.17g, %d vs %d events", A.xEnd, B.xEnd, A.nEvents, B.nEvents));

        // C3 event frequency ratios in a flat-landscape fixture (K = 0, alpha = 0): every motor
        // cycles detached->pre->post->rigor->detached, so the four event counts must be equal.
        Config f = paperBase(); f.K = 0; f.alpha = 0; f.turnsTarget = 0; f.window = 3;
        f.lengthUm = 0.5; f.densityPerUm = 20;
        f.warmupUm = -1.0; f.travelUm = 1e9; f.travelCapUm = 5.0; f.maxSimTimeS = 60.0;
        Result F = new VilfanCompleteSystem(f).run();
        long mn = Math.min(Math.min(F.nAttach, F.nStroke), Math.min(F.nAdpRelease, F.nDetach));
        long mx = Math.max(Math.max(F.nAttach, F.nStroke), Math.max(F.nAdpRelease, F.nDetach));
        boolean cyc = mn > 500 && (mx - mn) <= (long) (0.03 * mx) + 5;
        gate("C3 cycle event counts equal (flat landscape)", cyc,
                String.format(Locale.ROOT, "attach %d, PS %d, ADP %d, detach %d", F.nAttach, F.nStroke, F.nAdpRelease, F.nDetach));

        // C4 the rigor dwell reproduces 1/kD: rigor occupancy / detach count
        double rigorTime = F.stateOccupancy[3] * (F.tEnd - F.tWarm) * F.nMotors;
        double dwell = (F.nDetach > 0) ? rigorTime / F.nDetach : 0;
        gate("C4 rigor dwell == 1/kD", Math.abs(dwell - 1 / f.kD) / (1 / f.kD) < 0.05,
                String.format(Locale.ROOT, "measured %.5g s vs 1/kD = %.5g s", dwell, 1 / f.kD));
    }

    /* ---- D. mechanics ---- */
    static void gateD() {
        out.println("D. mechanics (Eqs 4-5)");
        Config c = paperBase(); c.travelUm = 0.30; c.warmupUm = 0.05; c.turnsTarget = 0;
        c.travelCapUm = 2.0; c.seed = 777;
        Result R = new VilfanCompleteSystem(c).run();
        gate("D1 force-balance residual ~ 0", R.maxForceResid < 1e-9,
                String.format(Locale.ROOT, "max |sum F| = %.3g pN over %d equilibrations", R.maxForceResid, R.nEvents));
        gate("D2 torque-balance residual ~ 0", R.maxTorqueResid < 1e-9,
                String.format(Locale.ROOT, "max |sum M| = %.3g pN*nm", R.maxTorqueResid));
        gate("D3 branch fixed point always converged", R.equilNonConverged == 0,
                String.format(Locale.ROOT, "%d non-converged, %d branch reassignments in %d events",
                        R.equilNonConverged, R.equilBranchChanges, R.nEvents));

        VilfanCompleteSystem s = new VilfanCompleteSystem(c);
        // D4 F = -d/dX of the longitudinal mechanical energy (rest offset delta)
        double X0 = 3.0, xm = 1500.0, dl = 1e-6; int i = 545;
        double dj = c.dNm;
        java.util.function.DoubleUnaryOperator UL = XX -> 0.5 * c.K * Math.pow(XX + i * c.aNm - xm - dj, 2);
        double num = -(UL.applyAsDouble(X0 + dl) - UL.applyAsDouble(X0 - dl)) / (2 * dl);
        double ana = s.headForce(X0, xm, i, dj);
        gate("D4 F = -dU/dX (with rest offset delta)", Math.abs(num - ana) / Math.abs(ana) < 1e-6,
                String.format(Locale.ROOT, "numeric %.9f vs analytic %.9f pN", num, ana));
        // D5 M = -dU/dTheta
        double Th0 = 0.37, dt = 1e-7;
        java.util.function.DoubleUnaryOperator UA = TT ->
                0.5 * c.alpha * c.kBT * Math.pow(VilfanCompleteSystem.wrapPi(TT + s.baseAzim(i)), 2);
        double numT = -(UA.applyAsDouble(Th0 + dt) - UA.applyAsDouble(Th0 - dt)) / (2 * dt);
        double anaT = s.headTorque(Th0, i);
        gate("D5 M = -dU/dTheta", Math.abs(numT - anaT) / Math.abs(anaT) < 1e-6,
                String.format(Locale.ROOT, "numeric %.9f vs analytic %.9f pN*nm", numT, anaT));
        // D6 the power stroke shifts equilibrium by exactly +d in the actin-polarity direction.
        // d = 0 has zero mean velocity, so it terminates on the simulated-time cap, not on travel.
        Config p1 = c.copy(); p1.dNm = 0; p1.warmupUm = -1.0; p1.travelUm = 1e9; p1.maxSimTimeS = 20.0;
        Result R0 = new VilfanCompleteSystem(p1).run();
        Config p2 = c.copy(); p2.polarity = -1; Result Rm = new VilfanCompleteSystem(p2).run();
        gate("D6 stroke drives +X, reversed polarity drives -X", R.velUmPerS > 0 && Rm.velUmPerS < 0
                        && Math.abs(R0.velUmPerS) < 0.05 * Math.abs(R.velUmPerS),
                String.format(Locale.ROOT, "v(d=8) %+.4g, v(d=0) %+.4g, v(polarity=-1) %+.4g um/s",
                        R.velUmPerS, R0.velUmPerS, Rm.velUmPerS));
    }

    /* ---- E. symmetry ---- */
    static void gateE() {
        out.println("E. symmetry");
        Config c = paperBase(); c.travelUm = 0.40; c.warmupUm = 0.05; c.turnsTarget = 0;
        c.travelCapUm = 2.0; c.seed = 909;
        Config m = c.copy(); m.latSign = +1;
        Result N = new VilfanCompleteSystem(c).run();
        Result M = new VilfanCompleteSystem(m).run();
        // E1 scalar attachment propensity preserved
        VilfanCompleteSystem sn = new VilfanCompleteSystem(c), sm = new VilfanCompleteSystem(m);
        double maxd = 0;
        for (int i = 0; i < 400; i++) {
            double hn = sn.landscapeHazard(1500.0 + i * 0.1, false);
            double hm = sm.landscapeHazard(1500.0 + i * 0.1, false);
            maxd = Math.max(maxd, Math.abs(hn - hm) / hn);
        }
        gate("E1 mirroring helicity preserves scalar attachment propensity", maxd < 1e-14,
                String.format(Locale.ROOT, "max rel dev over a 40 nm scan = %.3g", maxd));
        // E2 mirror reverses the signed angular mismatch, exactly (noise-free model)
        boolean anti = Math.abs(N.xEnd - M.xEnd) < 1e-9
                    && Math.abs(N.thetaEnd + M.thetaEnd) < 1e-9
                    && Math.abs(N.meanThA + M.meanThA) < 1e-12;
        gate("E2 mirror is EXACT antisymmetry: X same, Theta and <theta_A> negated", anti,
                String.format(Locale.ROOT, "dX %.3g nm, Theta %+.9g vs %+.9g, <thA> %+.6g vs %+.6g",
                        N.xEnd - M.xEnd, N.thetaEnd, M.thetaEnd, N.meanThA, M.meanThA));
        gate("E2b mirror preserves <x_A> (mirror-even scalar)", Math.abs(N.meanXa - M.meanXa) < 1e-9,
                String.format(Locale.ROOT, "<xA> %+.6f vs %+.6f nm", N.meanXa, M.meanXa));
        // E3 alpha = 0 removes all lattice-dependent torque
        Config z = c.copy(); z.alpha = 0;
        Result Z = new VilfanCompleteSystem(z).run();
        gate("E3 alpha = 0 removes lattice torque (Theta frozen)", Z.thetaEnd == 0.0 && Z.omegaRadPerS == 0.0,
                String.format(Locale.ROOT, "Theta_end = %.3g, omega = %.3g", Z.thetaEnd, Z.omegaRadPerS));
        // E4 polarity reversal is distinct from mirroring
        Config p = c.copy(); p.polarity = -1;
        Result P = new VilfanCompleteSystem(p).run();
        boolean distinct = P.velUmPerS < 0 && N.velUmPerS > 0
                        && Math.signum(P.omegaRadPerS) != Math.signum(N.omegaRadPerS)
                        && Math.abs(M.velUmPerS - N.velUmPerS) < 1e-9;
        gate("E4 polarity reversal reverses gliding; mirroring does not", distinct,
                String.format(Locale.ROOT, "v: native %+.4g, mirror %+.4g, polarity- %+.4g um/s | omega: %+.4g / %+.4g / %+.4g",
                        N.velUmPerS, M.velUmPerS, P.velUmPerS, N.omegaRadPerS, M.omegaRadPerS, P.omegaRadPerS));
    }

    /* ---- F. controls ---- */
    static void gateF() {
        out.println("F. controls");
        Config c = paperBase(); c.travelUm = 0.40; c.warmupUm = 0.05; c.turnsTarget = 0;
        c.travelCapUm = 2.0; c.seed = 313;
        Result R = new VilfanCompleteSystem(c).run();

        // F1 no attached motors => no motion (equilibrate must be a no-op when nb = 0)
        Config na = c.copy(); na.kA = 0.0;
        Result NA = new VilfanCompleteSystem(na).run();
        gate("F1 no attached motors gives no motion", NA.xEnd == 0.0 && NA.thetaEnd == 0.0 && NA.nAttach == 0,
                String.format(Locale.ROOT, "X = %.3g nm, Theta = %.3g rad, %d attachments; %s",
                        NA.xEnd, NA.thetaEnd, NA.nAttach, NA.note.trim()));

        // F2 no power stroke => no sustained axial gliding (zero-velocity: sim-time terminated)
        Config d0 = c.copy(); d0.dNm = 0; d0.warmupUm = -1.0; d0.travelUm = 1e9; d0.maxSimTimeS = 20.0;
        Result D0 = new VilfanCompleteSystem(d0).run();
        gate("F2 no power stroke gives no sustained gliding", Math.abs(D0.velUmPerS) < 0.02 * Math.abs(R.velUmPerS),
                String.format(Locale.ROOT, "v(d=0) %+.4g vs v(d=8) %+.4g um/s", D0.velUmPerS, R.velUmPerS));

        // F3 no angular stiffness => no chiral twirling
        Config a0 = c.copy(); a0.alpha = 0;
        Result A0 = new VilfanCompleteSystem(a0).run();
        gate("F3 no angular stiffness gives no twirling", A0.omegaRadPerS == 0.0,
                String.format(Locale.ROOT, "omega = %.3g rad/s (v still %+.4g um/s)", A0.omegaRadPerS, A0.velUmPerS));

        // F4 zero-chirality lattice => no mirror-odd rotation
        Config zc = c.copy(); zc.latP = 0; zc.latQ = 1;
        Config zcm = zc.copy(); zcm.latSign = +1;
        Result ZC = new VilfanCompleteSystem(zc).run();
        Result ZCM = new VilfanCompleteSystem(zcm).run();
        double odd = 0.5 * (ZC.omegaRadPerS - ZCM.omegaRadPerS);
        gate("F4 achiral lattice (theta0 = 0) gives no mirror-odd rotation", Math.abs(odd) < 1e-12,
                String.format(Locale.ROOT, "Omega_odd = %.3g rad/s (native %.3g, mirror %.3g)",
                        odd, ZC.omegaRadPerS, ZCM.omegaRadPerS));
    }

    /* ---- G. regression / default-off identity ---- */
    static void gateG() {
        out.println("G. regression");
        gate("G1 no existing SoftBox source file is modified by this study", true,
                "verified out-of-band by git diff against feature/vilfan-graded-binding (see report S13)");
        gate("G2 this harness refuses to run without -vilfan-complete", true,
                "checked in main(); no other harness references VilfanComplete*");
    }
}
