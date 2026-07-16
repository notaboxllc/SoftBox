package softbox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.DoubleArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * CPU-ONLY deterministic REPLAY HARNESS + fixture generator for the SoftBox two-body canonical motor
 * ({@link TwoBodyConverterMotor}). This is the validation backbone for a forthcoming GPU port: it
 * captures fully-specified single-motor INPUT states, executes N isolated CPU steps, and stores the
 * EXACT CPU OUTPUT as a golden fixture. A GPU kernel's output will later be diffed against these same
 * fixtures via the identical "execute one motor step from a fully-specified input state" code path
 * ({@link #stepMotor}).
 *
 * <p>Determinism: the two-body step functions ({@code stepSup}/{@code stepS2}/{@code stepC}) are plain
 * sequential Java (no TornadoVM device execution — CPU only) and their RNG is stateless counter-based
 * ({@code brownTorque(gamma,dt,seed,t,salt)}), so the output after N steps is reproducible from
 * {@code (model, imposed input, seed, startStep, nSteps)} alone — NO stored RNG state. A fixture is
 * therefore stored as a REBUILD RECIPE (model + a documented field poke) + the recorded INPUT pose
 * (verified on replay) + the EXPECTED OUTPUT (verified on replay).
 *
 * <p>Usage:
 * <pre>
 *   java softbox.MotorReplayHarness -genfixtures [-outdir DIR]   # generate the golden fixture set + self-test
 *   java softbox.MotorReplayHarness -replay FIXTUREFILE          # replay one fixture, print PASS/FAIL per field
 * </pre>
 *
 * <p>Standalone: a brand-new file that only READS package-private statics of {@link TwoBodyConverterMotor}
 * (same {@code softbox} package). No existing file is modified.
 */
public final class MotorReplayHarness {
    private MotorReplayHarness() {}

    static final String DEFAULT_OUTDIR = "fixtures/gpu_port";   // TRACKED golden fixtures (small, essential — caught the eup bug)
    static final double DT = 2.5e-6;   // explicit requires 2.5e-6; used for all three for consistency

    // ------------------------------------------------------------------------------------------------
    // THE GPU-DIFF SEAM: execute one isolated CPU motor step from a fully-specified input Cmot state.
    // A GPU kernel will reproduce exactly this transition; its output is diffed against the fixture.
    // ------------------------------------------------------------------------------------------------
    static void stepMotor(MotorModel m, TwoBodyConverterMotor.Cmot cm, int t, int seed, boolean brownian) {
        switch (m) {
            case EXPLICIT_S2_L40 -> TwoBodyConverterMotor.stepS2(cm, t, seed, brownian);   // explicit beam solve
            default              -> TwoBodyConverterMotor.stepSup(cm, t, seed, brownian);  // FIXED (supOn=false→stepC) & CALIBRATED (supOn=true)
            // NB: FIXED_ANCHOR routes stepSup→stepC, which has NO Brownian term (athermal); the flag is inert there.
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Fixture specification (the rebuild recipe).
    // ------------------------------------------------------------------------------------------------
    enum Poke { NONE, NUC, UNBIND, AXIAL, TRANSVERSE, NODE_BEND, NODE_TAUT, NODE_MIXED }

    static final class Spec {
        final String name, regime, note;
        final MotorModel model;
        final int seed, startStep, nSteps;
        final boolean brownian;
        final Poke poke;
        final double pokeMagNm;   // nm magnitude for displacement/node pokes
        final int pokeNuc;        // nucleotide state for a NUC poke (else -1)
        Spec(String name, MotorModel model, String regime, int seed, int startStep, int nSteps,
             boolean brownian, Poke poke, double pokeMagNm, int pokeNuc, String note) {
            this.name = name; this.model = model; this.regime = regime; this.seed = seed;
            this.startStep = startStep; this.nSteps = nSteps; this.brownian = brownian;
            this.poke = poke; this.pokeMagNm = pokeMagNm; this.pokeNuc = pokeNuc; this.note = note;
        }
    }

    // Small local vector helpers (kept independent of TwoBodyConverterMotor's private ones).
    static double[] add(double[] a, double[] b) { return new double[]{a[0]+b[0], a[1]+b[1], a[2]+b[2]}; }
    static double[] sub(double[] a, double[] b) { return new double[]{a[0]-b[0], a[1]-b[1], a[2]-b[2]}; }
    static double[] scl(double[] a, double s)   { return new double[]{a[0]*s, a[1]*s, a[2]*s}; }
    static double   dot(double[] a, double[] b) { return a[0]*b[0]+a[1]*b[1]+a[2]*b[2]; }
    static double   nrm(double[] a)             { return Math.sqrt(dot(a,a)); }
    static double[] unit(double[] a)            { double n=nrm(a); return n>1e-30 ? scl(a,1.0/n) : new double[]{1,0,0}; }

    /**
     * Impose the fixture's regime on a freshly-built BOUND motor. Directions are taken from the built
     * motor's own frame (b̂/econv/ê_up, supUL/supUT1, or the beam end-to-end axis) so the poke is
     * fully reconstructable on replay. Returns after refreshing derived geometry.
     */
    static void applyPoke(Spec s, TwoBodyConverterMotor.Cmot cm) {
        double mag = s.pokeMagNm * 1e-3;   // nm → µm
        switch (s.poke) {
            case NONE -> { /* settled bound baseline */ }
            case NUC  -> cm.mot.nucleotideState.set(0, s.pokeNuc);
            case UNBIND -> cm.mot.boundSeg.set(0, -1);   // bondForces zeroes the bond ⇒ pivot diffuses (search)
            case AXIAL -> {
                if (s.model == MotorModel.CALIBRATED_S2_L40) { cm.P = add(cm.P, scl(cm.supUL, mag)); cm.A = cm.P.clone(); }
                else { cm.A = add(cm.A, scl(cm.bhat, mag)); }   // FIXED_ANCHOR: displace the rigid anchor along the load axis
            }
            case TRANSVERSE -> {
                if (s.model == MotorModel.CALIBRATED_S2_L40) { cm.P = add(cm.P, scl(cm.supUT1, mag)); cm.A = cm.P.clone(); }
                else { cm.A = add(cm.A, scl(cm.econv, mag)); }
            }
            case NODE_BEND -> {           // explicit: bow the interior nodes transversely (bending-dominated, contour ≈ rest)
                int M = cm.g4M;
                for (int j = 1; j < M; j++) {
                    double shape = Math.sin(Math.PI * j / M);
                    cm.g4Node[j] = add(cm.g4Node[j], scl(cm.eup, mag * shape));
                }
                cm.A = cm.g4Node[M].clone(); cm.P = cm.A.clone();
            }
            case NODE_TAUT -> {           // explicit: pull the distal pivot outward along the beam axis (stretch toward taut)
                int M = cm.g4M;
                double[] axis = unit(sub(cm.g4Node[M], cm.g4Node[0]));
                cm.g4Node[M] = add(cm.g4Node[M], scl(axis, mag));
                cm.A = cm.g4Node[M].clone(); cm.P = cm.A.clone();
            }
            case NODE_MIXED -> {          // explicit: simultaneous bend (interior) + extension (distal)
                int M = cm.g4M;
                for (int j = 1; j < M; j++) cm.g4Node[j] = add(cm.g4Node[j], scl(cm.eup, mag * Math.sin(Math.PI * j / M)));
                double[] axis = unit(sub(cm.g4Node[M], cm.g4Node[0]));
                cm.g4Node[M] = add(cm.g4Node[M], scl(axis, mag));
                cm.A = cm.g4Node[M].clone(); cm.P = cm.A.clone();
            }
        }
        TwoBodyConverterMotor.geomC(cm);
    }

    /** Build the motor for a spec and impose its regime. */
    static TwoBodyConverterMotor.Cmot buildForSpec(Spec s) {
        TwoBodyConverterMotor.Cmot cm = TwoBodyConverterMotor.buildBoundMotor(s.model, DT);
        applyPoke(s, cm);
        return cm;
    }

    // ------------------------------------------------------------------------------------------------
    // State capture (the fully-specified pose + reaction the GPU must reproduce).
    // ------------------------------------------------------------------------------------------------
    static Map<String,Double> capture(MotorModel m, TwoBodyConverterMotor.Cmot cm) {
        Map<String,Double> st = new LinkedHashMap<>();
        st.put("phi", cm.phi);
        st.put("psi", cm.psi);
        st.put("thetaS", cm.thetaS);
        st.put("psiActin", cm.psiActin);
        put3(st, "A", cm.A);
        put3(st, "C", cm.C);
        put3(st, "xH", cm.xH);
        put3(st, "xF8", cm.xF8);
        st.put("nucleotideState", (double) cm.mot.nucleotideState.get(0));
        st.put("boundSeg", (double) cm.mot.boundSeg.get(0));
        st.put("bindArc", (double) cm.mot.bindArc.get(0));
        for (int k = 0; k < CrossBridgeSystem.STRIDE; k++) st.put("bond" + k, (double) cm.bondData.get(k));
        double[] Fh = { cm.bondData.get(0), cm.bondData.get(1), cm.bondData.get(2) };
        st.put("bondForceMag", nrm(Fh));
        if (m == MotorModel.EXPLICIT_S2_L40 && cm.g4Node != null) {
            int M = cm.g4M;
            for (int j = 0; j <= M; j++) put3(st, "node" + j, cm.g4Node[j]);
            double endToEnd = nrm(sub(cm.g4Node[M], cm.g4Node[0]));
            st.put("endToEnd", endToEnd);
            st.put("contourL", cm.g4Lc);
            st.put("contourErr", endToEnd - cm.g4Lc);   // solver constraint residual (µm)
        }
        return st;
    }
    static void put3(Map<String,Double> st, String key, double[] v) {
        st.put(key + "x", v[0]); st.put(key + "y", v[1]); st.put(key + "z", v[2]);
    }

    // ------------------------------------------------------------------------------------------------
    // Fixture generation.
    // ------------------------------------------------------------------------------------------------
    static void generate(Spec s, Path dir) {
        TwoBodyConverterMotor.Cmot cm = buildForSpec(s);
        Map<String,Double> input = capture(s.model, cm);
        int nucBefore = cm.mot.nucleotideState.get(0);
        for (int i = 0; i < s.nSteps; i++) stepMotor(s.model, cm, s.startStep + i, s.seed, s.brownian);
        Map<String,Double> expected = capture(s.model, cm);
        int nucAfter = cm.mot.nucleotideState.get(0);

        StringBuilder sb = new StringBuilder();
        sb.append("# SoftBox two-body motor replay fixture (CPU golden) — GPU-port validation backbone\n");
        sb.append("# regime: ").append(s.regime).append('\n');
        if (s.note != null && !s.note.isEmpty()) sb.append("# note: ").append(s.note).append('\n');
        sb.append("[spec]\n");
        sb.append("name=").append(s.name).append('\n');
        sb.append("motorModel=").append(s.model.name()).append('\n');
        sb.append("motorModelId=").append(s.model.id()).append('\n');
        sb.append("serialize=").append(s.model.serialize()).append('\n');
        sb.append("dt=").append(Double.toString(DT)).append('\n');
        sb.append("seed=").append(s.seed).append('\n');
        sb.append("startStep=").append(s.startStep).append('\n');
        sb.append("nSteps=").append(s.nSteps).append('\n');
        sb.append("brownian=").append(s.brownian).append('\n');
        sb.append("poke=").append(s.poke.name()).append('\n');
        sb.append("pokeMagNm=").append(Double.toString(s.pokeMagNm)).append('\n');
        sb.append("pokeNuc=").append(s.pokeNuc).append('\n');
        sb.append("nucBefore=").append(nucBefore).append('\n');
        sb.append("nucAfter=").append(nucAfter).append('\n');
        writeSection(sb, "input", input);
        writeSection(sb, "expected", expected);

        Path f = dir.resolve("fixture_" + s.name + ".txt");
        try { Files.writeString(f, sb.toString()); }
        catch (IOException ex) { throw new UncheckedIOException(ex); }
    }
    static void writeSection(StringBuilder sb, String section, Map<String,Double> st) {
        sb.append('[').append(section).append("]\n");
        for (Map.Entry<String,Double> e : st.entrySet())
            sb.append(e.getKey()).append('=').append(Double.toString(e.getValue())).append('\n');
    }

    // ------------------------------------------------------------------------------------------------
    // Replay + compare (the harness self-test AND the future GPU diff target).
    // ------------------------------------------------------------------------------------------------
    static final class Result { int inputPass, inputFail, outPass, outFail; double maxInDelta, maxOutDelta;
        boolean ok() { return inputFail == 0 && outFail == 0; } }

    static Result replay(Path file, boolean verbose) {
        Map<String,String> spec = new LinkedHashMap<>();
        Map<String,Double> input = new LinkedHashMap<>(), expected = new LinkedHashMap<>();
        parse(file, spec, input, expected);

        MotorModel model = MotorModel.valueOf(spec.get("motorModel"));
        int seed = Integer.parseInt(spec.get("seed"));
        int startStep = Integer.parseInt(spec.get("startStep"));
        int nSteps = Integer.parseInt(spec.get("nSteps"));
        boolean brownian = Boolean.parseBoolean(spec.get("brownian"));
        Poke poke = Poke.valueOf(spec.get("poke"));
        double pokeMagNm = Double.parseDouble(spec.get("pokeMagNm"));
        int pokeNuc = Integer.parseInt(spec.get("pokeNuc"));
        Spec s = new Spec(spec.get("name"), model, spec.getOrDefault("regime", ""), seed, startStep, nSteps,
                          brownian, poke, pokeMagNm, pokeNuc, "");

        // 1) reconstruct the input from the recipe and verify it matches the stored input pose
        TwoBodyConverterMotor.Cmot cm = buildForSpec(s);
        Map<String,Double> rebuiltInput = capture(model, cm);
        Result r = new Result();
        System.out.println("# replay " + file.getFileName() + "  model=" + model.id()
                + " poke=" + poke + " nSteps=" + nSteps + " brownian=" + brownian);
        System.out.println("#   --- INPUT-CONSISTENCY (rebuilt recipe vs stored input) ---");
        for (Map.Entry<String,Double> e : input.entrySet()) {
            double got = rebuiltInput.getOrDefault(e.getKey(), Double.NaN);
            double d = Math.abs(got - e.getValue());
            boolean pass = (got == e.getValue().doubleValue());
            if (pass) r.inputPass++; else r.inputFail++;
            r.maxInDelta = Math.max(r.maxInDelta, d);
            if (verbose && (!pass || d != 0.0))
                System.out.printf(Locale.US, "#     %-16s stored=% .17e got=% .17e Δ=% .3e %s%n",
                        e.getKey(), e.getValue(), got, d, pass ? "PASS" : "FAIL");
        }
        // 2) execute the isolated steps and verify the output matches the golden expected
        for (int i = 0; i < nSteps; i++) stepMotor(model, cm, startStep + i, seed, brownian);
        Map<String,Double> got = capture(model, cm);
        System.out.println("#   --- OUTPUT-DETERMINISM (replayed CPU step(s) vs golden expected) ---");
        for (Map.Entry<String,Double> e : expected.entrySet()) {
            double g = got.getOrDefault(e.getKey(), Double.NaN);
            double d = Math.abs(g - e.getValue());
            boolean pass = (g == e.getValue().doubleValue());
            if (pass) r.outPass++; else r.outFail++;
            r.maxOutDelta = Math.max(r.maxOutDelta, d);
            if (verbose && (!pass || d != 0.0))
                System.out.printf(Locale.US, "#     %-16s expected=% .17e got=% .17e Δ=% .3e %s%n",
                        e.getKey(), e.getValue(), g, d, pass ? "PASS" : "FAIL");
        }
        System.out.printf(Locale.US,
                "#   RESULT %s  input %d/%d PASS (maxΔ=%.2e)  output %d/%d PASS (maxΔ=%.2e)%n%n",
                r.ok() ? "PASS ✓" : "FAIL ✗",
                r.inputPass, r.inputPass + r.inputFail, r.maxInDelta,
                r.outPass, r.outPass + r.outFail, r.maxOutDelta);
        return r;
    }

    static void parse(Path file, Map<String,String> spec, Map<String,Double> input, Map<String,Double> expected) {
        String section = "";
        try {
            for (String line : Files.readAllLines(file)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("[") && line.endsWith("]")) { section = line.substring(1, line.length() - 1); continue; }
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String key = line.substring(0, eq), val = line.substring(eq + 1);
                switch (section) {
                    case "spec"     -> spec.put(key, val);
                    case "input"    -> input.put(key, Double.parseDouble(val));
                    case "expected" -> expected.put(key, Double.parseDouble(val));
                    default -> { }
                }
            }
        } catch (IOException ex) { throw new UncheckedIOException(ex); }
    }

    // ------------------------------------------------------------------------------------------------
    // The fixture matrix.
    // ------------------------------------------------------------------------------------------------
    static List<Spec> matrix() {
        List<Spec> L = new ArrayList<>();
        int ADPPI = MotorStore.NUC_ADPPI, ADP = MotorStore.NUC_ADP;
        for (MotorModel m : new MotorModel[]{ MotorModel.FIXED_ANCHOR, MotorModel.CALIBRATED_S2_L40, MotorModel.EXPLICIT_S2_L40 }) {
            String id = m.id();
            boolean explicit = (m == MotorModel.EXPLICIT_S2_L40);
            // --- common regimes (all three models) ---
            L.add(new Spec(id + "_bound_baseline", m, "settled bound (ADP, post-stroke), single step", 101, 0, 1,
                    false, Poke.NONE, 0, -1, "the resting bound reference pose"));
            L.add(new Spec(id + "_bound_multistep", m, "settled bound, 25 deterministic steps (multi-step determinism)", 101, 0, 25,
                    false, Poke.NONE, 0, -1, "exercises multi-step counter-based reproducibility"));
            L.add(new Spec(id + "_prestroke_adppi", m, "pre-stroke: nucleotide=ADP·Pi (cocked rest angles)", 102, 0, 1,
                    false, Poke.NUC, 0, ADPPI, "F8 rest angle switches with nucleotide state"));
            L.add(new Spec(id + "_poststroke_adp", m, "post-stroke: nucleotide=ADP (explicit)", 102, 0, 1,
                    false, Poke.NUC, 0, ADP, "post-power-stroke rest angles"));
            L.add(new Spec(id + "_brownian", m, "bound + Brownian, 10 steps (RNG path)", 103, 0, 10,
                    true, Poke.NONE, 0, -1, explicit || m == MotorModel.CALIBRATED_S2_L40
                        ? "exercises brownTorque counter-RNG" : "FIXED routes to stepC (athermal): flag inert, deterministic"));
            L.add(new Spec(id + "_unbound_search", m, "unbound search: boundSeg=-1 (zero bond, pivot diffuses)", 104, 0, 10,
                    true, Poke.UNBIND, 0, -1, "approximates the search state; near-capture geometry not distinctly generated"));
            // --- strain regimes ---
            if (!explicit) {
                L.add(new Spec(id + "_axial_low", m, "low axial strain (2 nm along load axis)", 105, 0, 1,
                        false, Poke.AXIAL, 2.0, -1, "small cross-bridge extension"));
                L.add(new Spec(id + "_axial_high", m, "high axial strain (8 nm along load axis)", 105, 0, 1,
                        false, Poke.AXIAL, 8.0, -1, "large cross-bridge extension / load"));
                L.add(new Spec(id + "_transverse", m, "transverse strain (5 nm off the load axis)", 106, 0, 1,
                        false, Poke.TRANSVERSE, 5.0, -1, "off-axis bond displacement"));
                L.add(new Spec(id + "_near_detach", m, "near-detachment (15 nm axial, high load)", 107, 0, 1,
                        false, Poke.AXIAL, 15.0, -1, "high F8 load approaching detachment"));
            } else {
                L.add(new Spec(id + "_bend", m, "explicit-S2 bending-dominated (5 nm interior bow, contour≈rest)", 108, 0, 1,
                        false, Poke.NODE_BEND, 5.0, -1, "beam bent, end-to-end near contour length"));
                L.add(new Spec(id + "_taut", m, "explicit-S2 nearly-taut (4 nm distal pull along beam axis)", 108, 0, 1,
                        false, Poke.NODE_TAUT, 4.0, -1, "beam stretched toward straight/taut"));
                L.add(new Spec(id + "_mixed", m, "explicit-S2 mixed bending + extension", 109, 0, 1,
                        false, Poke.NODE_MIXED, 4.0, -1, "simultaneous bend + axial stretch"));
                L.add(new Spec(id + "_high_axial", m, "explicit-S2 high axial strain (10 nm distal pull)", 109, 0, 1,
                        false, Poke.NODE_TAUT, 10.0, -1, "large beam extension / high load"));
            }
        }
        return L;
    }

    // ------------------------------------------------------------------------------------------------
    // Inventory + main.
    // ------------------------------------------------------------------------------------------------
    static void writeInventory(List<Spec> specs, Map<String,Result> results, Path dir) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Fixture inventory — SoftBox two-body canonical motor replay fixtures\n\n");
        sb.append("Generated by `softbox.MotorReplayHarness -genfixtures` (CPU-only; dt=").append(DT).append(").\n");
        sb.append("Each fixture is a rebuild recipe (model + documented field poke) + recorded INPUT pose ")
          .append("+ golden EXPECTED output after N isolated CPU steps under a fixed (seed, startStep).\n");
        sb.append("The `-replay` self-test rebuilds from the recipe, verifies INPUT consistency, re-executes ")
          .append("the step(s), and diffs against EXPECTED — bit-identical (Δ=0) is PASS.\n\n");
        sb.append("| fixture | model | regime | steps | brownian | poke | self-test |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        for (Spec s : specs) {
            Result r = results.get(s.name);
            String verdict = r == null ? "SKIPPED" : (r.ok() ? "PASS" : "FAIL");
            sb.append("| ").append(s.name).append(" | ").append(s.model.id()).append(" | ")
              .append(s.regime).append(" | ").append(s.nSteps).append(" | ").append(s.brownian)
              .append(" | ").append(s.poke).append(s.poke == Poke.NUC ? ("=" + s.pokeNuc)
                  : (s.pokeMagNm != 0 ? ("=" + s.pokeMagNm + "nm") : "")).append(" | ").append(verdict).append(" |\n");
        }
        sb.append("\n## Fields exercised per fixture\n");
        sb.append("- All: `phi,psi,thetaS,psiActin`, pivot `A[3]`, derived `C/xH/xF8[3]`, `nucleotideState`, ")
          .append("`boundSeg`, `bindArc`, cross-bridge `bond[0..12]` (head F[0..2]/torque[3..5], seg F[6..8]/torque[9..11], ")
          .append("forceDotFil[12]), `bondForceMag`.\n");
        sb.append("- EXPLICIT_S2_L40 also: all 5 beam nodes `node0..node4[3]`, `endToEnd`, `contourL`, `contourErr` ")
          .append("(the beam constraint residual = end-to-end − reference contour).\n");
        sb.append("- Chemistry: `nucBefore→nucAfter` (constant across an isolated two-body mechanics step — there is ")
          .append("no NucleotideCycleSystem call in stepC/stepSup/stepS2; the nucleotide state only selects the F8 rest angle).\n");
        sb.append("\n## Regimes covered\n");
        sb.append("per model {fixed-anchor, calibrated-s2-l40, explicit-s2-l40}: bound-baseline, multistep-determinism, ")
          .append("pre-stroke (ADP·Pi), post-stroke (ADP), Brownian(RNG), unbound-search; ")
          .append("fixed/calibrated: low/high axial strain, transverse strain, near-detachment; ")
          .append("explicit: bending-dominated, nearly-taut, mixed bend+extension, high-axial.\n");
        sb.append("\n## Not distinctly generated (logged honestly)\n");
        sb.append("- **near-capture**: the binding-search/capture state machine (`buildUnbound`/`stepU`/gate acceptance) ")
          .append("is a separate 3E pathway; the unbound-search fixture (boundSeg=-1) approximates the pre-binding pivot ")
          .append("diffusion, but a mid-flight near-capture geometry is not reconstructed here.\n");
        sb.append("- **FIXED_ANCHOR Brownian/search** are athermal in practice: stepSup delegates to stepC which has no ")
          .append("Brownian term. The fixtures are still generated and deterministic; the brownian flag is inert for that model.\n");
        try { Files.writeString(dir.resolve("FIXTURE_INVENTORY.md"), sb.toString()); }
        catch (IOException ex) { throw new UncheckedIOException(ex); }
    }

    // ================================================================================================
    // -validatekernels : validate TwoBodyGpuKernels scalarized Step-7 arithmetic on the CPU RUNNER
    //   (a @Parallel kernel called as a plain loop IS the CPU runner) against the golden fixtures.
    //   T3 (fixed/calibrated FLOAT32 vs double golden): rel ≤ 1e-4 OR abs ≤ 1e-6 (µm/pN/rad).
    //   T4 (explicit-beam DOUBLE vs double golden):      rel ≤ 1e-6 OR abs ≤ 1e-12.
    // ================================================================================================
    static final double T3_REL = 1e-4, T3_ABS = 1e-6, T4_REL = 1e-6, T4_ABS = 1e-12;

    /** Advance a rebuilt motor to its pre-final-step state and run the matching Step-7 kernel ONCE.
     *  Returns the kernel's output fields keyed like {@link #capture} (subset the kernel produces). */
    static Map<String,Double> runKernelForSpec(Spec s) {
        TwoBodyConverterMotor.Cmot cm = buildForSpec(s);
        for (int i = 0; i < s.nSteps - 1; i++) stepMotor(s.model, cm, s.startStep + i, s.seed, s.brownian);
        int tFinal = s.startStep + s.nSteps - 1;
        int brown = s.brownian ? 1 : 0;

        // Mirror the step-start pre-solve to obtain F8h (== the golden final step's bondForces output).
        MotorStore mot = cm.mot; RigidRodBody b = mot.body; FilamentStore f = cm.fil;
        mot.setCounts(tFinal, s.seed, f.n); f.counts.set(1, tFinal); f.counts.set(2, s.seed);
        if (s.model == MotorModel.EXPLICIT_S2_L40) { cm.A = cm.g4Node[cm.g4M]; cm.P = cm.A; }
        else if (s.model == MotorModel.CALIBRATED_S2_L40) { cm.A = cm.P; }
        // FIXED_ANCHOR: A stays (the rigid anchor).
        TwoBodyConverterMotor.geomC(cm);
        TwoBodyConverterMotor.placeHead3c(cm);
        CrossBridgeSystem.bondForces(b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength,
                mot.boundSeg, mot.bindArc, mot.nucleotideState, cm.bondData, cm.xbParams);
        double f8x = cm.bondData.get(0), f8y = cm.bondData.get(1), f8z = cm.bondData.get(2);

        Map<String,Double> out = new LinkedHashMap<>();
        out.put("bond0", f8x); out.put("bond1", f8y); out.put("bond2", f8z);   // F8h passthrough (bondForces' output)
        out.put("bondForceMag", Math.sqrt(f8x*f8x + f8y*f8y + f8z*f8z));
        out.put("thetaS", cm.thetaS); out.put("psiActin", cm.psiActin);

        // ALL device-buffer packing goes through the single canonical registry→device packer (MotorGpuParams).
        if (s.model == MotorModel.EXPLICIT_S2_L40) {
            int M = cm.g4M;
            DoubleArray nodes = MotorGpuParams.packNodes(cm), frame = MotorGpuParams.packFrameExplicit(cm),
                        q = MotorGpuParams.packQDouble(cm), F8h = MotorGpuParams.packF8Double(cm),
                        params = MotorGpuParams.packExplicit(cm), outGeom = new DoubleArray(9);
            IntArray counts = new IntArray(5); counts.set(0,1); counts.set(1,tFinal); counts.set(2,s.seed); counts.set(3,brown); counts.set(4,M);
            TwoBodyGpuKernels.explicitBeamStep(nodes, frame, q, F8h, params, outGeom, counts);
            out.put("phi", q.get(0)); out.put("psi", q.get(1));
            out.put("Ax", nodes.get(3*M)); out.put("Ay", nodes.get(3*M+1)); out.put("Az", nodes.get(3*M+2));
            putGeom(out, outGeom);
            double[] n0 = {nodes.get(0), nodes.get(1), nodes.get(2)}, nM = {nodes.get(3*M), nodes.get(3*M+1), nodes.get(3*M+2)};
            for (int j = 0; j <= M; j++) { out.put("node"+j+"x", nodes.get(3*j)); out.put("node"+j+"y", nodes.get(3*j+1)); out.put("node"+j+"z", nodes.get(3*j+2)); }
            double e2e = nrm(sub(nM, n0)); out.put("endToEnd", e2e); out.put("contourL", cm.g4Lc); out.put("contourErr", e2e - cm.g4Lc);
        } else {
            FloatArray q = MotorGpuParams.packQFloat(cm), A = MotorGpuParams.packAFloat(cm),
                       frame = MotorGpuParams.packFrameFloat(cm), F8h = MotorGpuParams.packF8Float(cm), outGeom = new FloatArray(9);
            IntArray counts = new IntArray(4); counts.set(0,1); counts.set(1,tFinal); counts.set(2,s.seed); counts.set(3,brown);
            if (s.model == MotorModel.CALIBRATED_S2_L40) {
                FloatArray params = MotorGpuParams.packCalibrated(cm), supGeom = MotorGpuParams.packSupGeom(cm);
                TwoBodyGpuKernels.calibratedStep(q, A, frame, supGeom, F8h, params, outGeom, counts);
            } else {
                FloatArray params = MotorGpuParams.packFixed(cm);
                TwoBodyGpuKernels.fixedStep(q, A, frame, params, F8h, outGeom, counts);
            }
            out.put("phi", (double)q.get(0)); out.put("psi", (double)q.get(1));
            out.put("Ax", (double)A.get(0)); out.put("Ay", (double)A.get(1)); out.put("Az", (double)A.get(2));
            String[] gk = {"Cx","Cy","Cz","xHx","xHy","xHz","xF8x","xF8y","xF8z"};
            for (int k = 0; k < 9; k++) out.put(gk[k], (double)outGeom.get(k));
        }
        return out;
    }
    static void setVec(DoubleArray a, int off, double[] v){ a.set(off,v[0]); a.set(off+1,v[1]); a.set(off+2,v[2]); }
    static void setVecF(FloatArray a, int off, double[] v){ a.set(off,(float)v[0]); a.set(off+1,(float)v[1]); a.set(off+2,(float)v[2]); }
    static void putGeom(Map<String,Double> out, DoubleArray g){ String[] gk={"Cx","Cy","Cz","xHx","xHy","xHz","xF8x","xF8y","xF8z"};
        for (int k=0;k<9;k++) out.put(gk[k], g.get(k)); }

    static void validateKernels(Path dir) {
        List<Spec> specs = matrix();
        System.out.println("# ============================================================");
        System.out.println("# MotorReplayHarness -validatekernels  (CPU runner — TwoBodyGpuKernels vs golden fixtures)");
        System.out.println("# T3 fixed/calibrated FLOAT32: rel≤" + T3_REL + " OR abs≤" + T3_ABS
                + "   |   T4 explicit DOUBLE: rel≤" + T4_REL + " OR abs≤" + T4_ABS);
        System.out.println("# ============================================================");
        int nPass = 0, nFail = 0;
        StringBuilder rep = new StringBuilder();
        rep.append("# TwoBodyGpuKernels CPU-runner validation vs golden fixtures\n\n");
        rep.append("| fixture | model | tier | fields | worst field | maxRelΔ | maxAbsΔ | verdict |\n");
        rep.append("|---|---|---|---|---|---|---|---|\n");
        for (Spec s : specs) {
            boolean t4 = (s.model == MotorModel.EXPLICIT_S2_L40);
            double relTol = t4 ? T4_REL : T3_REL, absTol = t4 ? T4_ABS : T3_ABS;
            Map<String,String> spec = new LinkedHashMap<>();
            Map<String,Double> expected = new LinkedHashMap<>(), dummyIn = new LinkedHashMap<>();
            parse(dir.resolve("fixture_" + s.name + ".txt"), spec, dummyIn, expected);
            Map<String,Double> kout = runKernelForSpec(s);
            double maxRel = 0, maxAbs = 0; String worst = ""; int fields = 0, fieldFail = 0;
            for (Map.Entry<String,Double> e : kout.entrySet()) {
                Double exp = expected.get(e.getKey());
                if (exp == null) continue;
                fields++;
                double got = e.getValue(), d = Math.abs(got - exp);
                double rel = Math.abs(exp) > 1e-300 ? d / Math.abs(exp) : (d == 0 ? 0 : Double.POSITIVE_INFINITY);
                boolean pass = (d <= absTol) || (rel <= relTol);
                if (rel > maxRel && d > absTol) { maxRel = rel; worst = e.getKey(); }
                if (d > maxAbs) maxAbs = d;
                if (!pass) fieldFail++;
            }
            boolean ok = fieldFail == 0;
            if (ok) nPass++; else nFail++;
            System.out.printf(Locale.US, "#   %-42s %-16s %s  fields=%2d fail=%d maxRelΔ=%.2e maxAbsΔ=%.2e  worst=%s%n",
                    s.name, s.model.id(), t4 ? "T4" : "T3", fields, fieldFail, maxRel, maxAbs, worst.isEmpty() ? "-" : worst);
            rep.append("| ").append(s.name).append(" | ").append(s.model.id()).append(" | ").append(t4 ? "T4" : "T3")
               .append(" | ").append(fields).append(" | ").append(worst.isEmpty() ? "-" : worst).append(" | ")
               .append(String.format(Locale.US, "%.2e", maxRel)).append(" | ").append(String.format(Locale.US, "%.2e", maxAbs))
               .append(" | ").append(ok ? "PASS" : "FAIL").append(" |\n");
        }
        rep.append("\n**Result: ").append(nPass).append("/").append(specs.size()).append(" fixtures PASS, ").append(nFail).append(" FAIL.**\n");
        try { Files.writeString(dir.resolve("KERNEL_VALIDATION.md"), rep.toString()); } catch (IOException ex) { throw new UncheckedIOException(ex); }
        System.out.println("# ============================================================");
        System.out.printf(Locale.US, "# KERNEL VALIDATION COMPLETE: %d/%d fixtures PASS, %d FAIL%n", nPass, specs.size(), nFail);
        System.out.println("# report: " + dir.resolve("KERNEL_VALIDATION.md").toAbsolutePath());
        System.out.println("# ============================================================");
        System.exit(nFail == 0 ? 0 : 1);
    }

    public static void main(String[] args) {
        String outdir = DEFAULT_OUTDIR;
        String replayFile = null;
        boolean gen = false, validate = false, verifyParams = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-genfixtures" -> gen = true;
                case "-validatekernels" -> validate = true;
                case "-verifyparams" -> verifyParams = true;
                case "-outdir"      -> outdir = args[++i];
                case "-replay"      -> replayFile = args[++i];
                case "-gpu"         -> throw new IllegalArgumentException("MotorReplayHarness is CPU-only; -gpu not supported");
                default -> System.err.println("# ignoring unknown arg: " + args[i]);
            }
        }
        if (replayFile != null) {
            Result r = replay(Path.of(replayFile), true);
            System.exit(r.ok() ? 0 : 1);
            return;
        }
        if (verifyParams) {
            try { MotorGpuParams.verifyRegistrySourced(DT); System.out.println("T0 registry→device params: PASS"); }
            catch (RuntimeException ex) { System.out.println("T0 registry→device params: FAIL — " + ex.getMessage()); System.exit(1); }
            return;
        }
        if (validate) { validateKernels(Path.of(outdir)); return; }
        if (!gen) {
            System.out.println("usage: -genfixtures [-outdir DIR]  |  -replay FIXTUREFILE");
            return;
        }
        Path dir = Path.of(outdir);
        try { Files.createDirectories(dir); } catch (IOException ex) { throw new UncheckedIOException(ex); }
        List<Spec> specs = matrix();
        System.out.println("# ============================================================");
        System.out.println("# MotorReplayHarness -genfixtures  (CPU-only, dt=" + DT + ")");
        System.out.println("# outdir=" + dir.toAbsolutePath());
        System.out.println("# generating " + specs.size() + " fixtures across 3 models");
        System.out.println("# ============================================================");
        for (Spec s : specs) {
            generate(s, dir);
            System.out.println("#   wrote fixture_" + s.name + ".txt  [" + s.regime + "]");
        }
        // self-test: replay every fixture and confirm bit-identical determinism
        System.out.println("\n# ---------------- DETERMINISM SELF-TEST (replay every fixture) ----------------");
        Map<String,Result> results = new LinkedHashMap<>();
        int pass = 0, fail = 0;
        for (Spec s : specs) {
            Result r = replay(dir.resolve("fixture_" + s.name + ".txt"), false);
            results.put(s.name, r);
            if (r.ok()) pass++; else fail++;
        }
        writeInventory(specs, results, dir);
        System.out.println("# ============================================================");
        System.out.printf(Locale.US, "# SELF-TEST COMPLETE: %d/%d fixtures PASS, %d FAIL%n", pass, specs.size(), fail);
        System.out.println("# inventory: " + dir.resolve("FIXTURE_INVENTORY.md").toAbsolutePath());
        System.out.println("# ============================================================");
        System.exit(fail == 0 ? 0 : 1);
    }
}
