package softbox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import uk.ac.manchester.tornado.api.GridScheduler;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.WorkerGrid;
import uk.ac.manchester.tornado.api.WorkerGrid1D;
import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.DoubleArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * EMPIRICAL GPU DEVICE PROBE (Phase-1). Determines whether the three merged two-body Step-7 kernels in
 * {@link TwoBodyGpuKernels} actually LOWER to PTX and EXECUTE on the GPU via TornadoVM. Those kernels
 * currently use local 2D-array scratch ({@code new float[5][6]}, {@code new double[n][n+1]},
 * {@code new double[M+1][3]}) with explicit {@code TODO(GPU)} markers and were only validated on the CPU
 * runner (called as sequential loops). This probe does NOT modify the kernel arithmetic — it wraps each
 * kernel unchanged in a real TornadoVM {@link TaskGraph} at N=1 and tries to execute it on the PTX device,
 * catching and reporting whatever TornadoVM actually does.
 *
 * <p>A trivial vector-add positive control runs first: if IT executes on the GPU, a failure of the motor
 * kernels is attributable to the kernel (its unsupported constructs), not the toolchain/harness.
 *
 * <p>Buffers come from the SAME registry→device packers ({@link MotorGpuParams}) that
 * {@link MotorReplayHarness#runKernelForSpec} feeds the CPU runner — so the device path sees identical inputs.
 */
public final class GpuDeviceProbe {
    private GpuDeviceProbe() {}

    static final String FIXDIR = "fixtures/gpu_port";
    static final String OUTDIR = "RUN_LOGS/gpu_device_probe";
    static final StringBuilder LOG = new StringBuilder();

    // ---- positive-control kernel: known-good, no local 2D arrays ----
    public static void vecAdd(FloatArray a, FloatArray b, FloatArray c) {
        for (@Parallel int i = 0; i < a.getSize(); i++) c.set(i, a.get(i) + b.get(i));
    }

    public static void main(String[] args) {
        Path dir = Path.of(OUTDIR);
        try { Files.createDirectories(dir); } catch (IOException ex) { throw new UncheckedIOException(ex); }
        log("# GPU DEVICE PROBE — TwoBodyGpuKernels PTX lowering/execution (Phase-1)\n");
        log("# device mem (used MiB) at start: " + gpuMemUsed() + "\n");
        System.out.println("=== GPU DEVICE PROBE (RTX 5070 / TornadoVM PTX) ===");

        // ---------- POSITIVE CONTROL ----------
        boolean controlOk = positiveControl();

        // ---------- THE THREE MERGED KERNELS ----------
        boolean fixedOk = probeFixed("fixed-anchor_bound_baseline");
        boolean calibOk = probeCalibrated("calibrated-s2-l40_bound_baseline");
        boolean explBaseOk = probeExplicit("explicit-s2-l40_bound_baseline");
        boolean explHighOk = probeExplicit("explicit-s2-l40_high_axial");

        log("\n# device mem (used MiB) at end: " + gpuMemUsed() + "\n");
        try { Files.writeString(dir.resolve("PROBE_LOG.md"), LOG.toString()); } catch (IOException ex) { throw new UncheckedIOException(ex); }

        System.out.println("\n=== VERDICT ===");
        System.out.printf(Locale.US, "positive-control (vecAdd) device path: %s%n", controlOk ? "WORKS" : "BROKEN");
        System.out.printf(Locale.US, "fixedStep       LOWERS+RUNS: %s%n", fixedOk ? "YES" : "NO");
        System.out.printf(Locale.US, "calibratedStep  LOWERS+RUNS: %s%n", calibOk ? "YES" : "NO");
        System.out.printf(Locale.US, "explicitBeamStep LOWERS+RUNS: %s (baseline) / %s (high_axial)%n",
                explBaseOk ? "YES" : "NO", explHighOk ? "YES" : "NO");
        boolean any = fixedOk || calibOk || explBaseOk || explHighOk;
        System.out.printf(Locale.US, "ANY merged kernel executable on GPU as written: %s%n", any ? "YES" : "NO");
        System.out.println("# full log: " + dir.resolve("PROBE_LOG.md").toAbsolutePath());
    }

    // ================================================================================================
    static boolean positiveControl() {
        System.out.println("\n--- positive control: vecAdd (N=4) ---");
        log("\n## Positive control: vecAdd (trivial, no local arrays)\n");
        String memBefore = gpuMemUsed();
        try {
            FloatArray a = new FloatArray(4), b = new FloatArray(4), c = new FloatArray(4);
            for (int i = 0; i < 4; i++) { a.set(i, i + 1f); b.set(i, 10f * (i + 1)); c.set(i, 0f); }
            TaskGraph tg = new TaskGraph("ctrl")
                    .transferToDevice(DataTransferMode.FIRST_EXECUTION, a, b)
                    .task("vadd", GpuDeviceProbe::vecAdd, a, b, c)
                    .transferToHost(DataTransferMode.EVERY_EXECUTION, c);
            GridScheduler sched = new GridScheduler();
            WorkerGrid w = new WorkerGrid1D(4); w.setLocalWork(1, 1, 1); sched.addWorkerGrid("ctrl.vadd", w);
            TornadoExecutionPlan plan = new TornadoExecutionPlan(tg.snapshot());
            long t0 = System.nanoTime();
            plan.withGridScheduler(sched).execute();
            long ns = System.nanoTime() - t0;
            boolean ok = true; for (int i = 0; i < 4; i++) ok &= (c.get(i) == 11f * (i + 1));
            System.out.printf(Locale.US, "  vecAdd executed on device: %s  [%.1f ms incl compile]  c=[%s]%n",
                    ok ? "OK" : "WRONG", ns / 1e6, dump(c, 4));
            log(String.format(Locale.US, "- lowered+ran: YES; result %s; runtime %.1f ms (incl first-launch compile); mem %s→%s MiB%n",
                    ok ? "correct" : "WRONG", ns / 1e6, memBefore, gpuMemUsed()));
            return ok;
        } catch (Throwable ex) {
            System.out.println("  vecAdd FAILED on device: " + ex.getClass().getName() + ": " + ex.getMessage());
            log("- lowered+ran: NO — " + ex.getClass().getName() + ": " + oneLine(ex.getMessage()) + "\n");
            return false;
        }
    }

    // ================================================================================================
    //  Rebuild the Cmot for a named fixture, run the step-start pre-solve to populate F8h (cm.bondData),
    //  matching MotorReplayHarness.runKernelForSpec exactly.
    // ================================================================================================
    static TwoBodyConverterMotor.Cmot presolvedCmot(MotorReplayHarness.Spec s, int[] tFinalOut) {
        TwoBodyConverterMotor.Cmot cm = MotorReplayHarness.buildForSpec(s);
        for (int i = 0; i < s.nSteps - 1; i++) MotorReplayHarness.stepMotor(s.model, cm, s.startStep + i, s.seed, s.brownian);
        int tFinal = s.startStep + s.nSteps - 1; tFinalOut[0] = tFinal;
        MotorStore mot = cm.mot; RigidRodBody b = mot.body; FilamentStore f = cm.fil;
        mot.setCounts(tFinal, s.seed, f.n); f.counts.set(1, tFinal); f.counts.set(2, s.seed);
        if (s.model == MotorModel.EXPLICIT_S2_L40) { cm.A = cm.g4Node[cm.g4M]; cm.P = cm.A; }
        else if (s.model == MotorModel.CALIBRATED_S2_L40) { cm.A = cm.P; }
        TwoBodyConverterMotor.geomC(cm);
        TwoBodyConverterMotor.placeHead3c(cm);
        CrossBridgeSystem.bondForces(b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength,
                mot.boundSeg, mot.bindArc, mot.nucleotideState, cm.bondData, cm.xbParams);
        return cm;
    }

    static MotorReplayHarness.Spec specByName(String name) {
        for (MotorReplayHarness.Spec s : MotorReplayHarness.matrix()) if (s.name.equals(name)) return s;
        throw new IllegalArgumentException("no fixture spec named " + name);
    }
    static Map<String,Double> loadExpected(String name) {
        Map<String,String> sp = new LinkedHashMap<>(); Map<String,Double> in = new LinkedHashMap<>(), exp = new LinkedHashMap<>();
        MotorReplayHarness.parse(Path.of(FIXDIR, "fixture_" + name + ".txt"), sp, in, exp); return exp;
    }

    // ================================================================================================
    static boolean probeFixed(String fixture) { return probeFloat("fixedStep", fixture, false); }
    static boolean probeCalibrated(String fixture) { return probeFloat("calibratedStep", fixture, true); }

    static boolean probeFloat(String kernel, String fixture, boolean calibrated) {
        System.out.printf(Locale.US, "%n--- %s  (fixture %s, float32) ---%n", kernel, fixture);
        log("\n## " + kernel + "  (fixture " + fixture + ", FLOAT32)\n");
        String memBefore = gpuMemUsed();
        try {
            MotorReplayHarness.Spec s = specByName(fixture);
            int[] tf = new int[1];
            TwoBodyConverterMotor.Cmot cm = presolvedCmot(s, tf);
            int brown = s.brownian ? 1 : 0;

            FloatArray q = MotorGpuParams.packQFloat(cm), A = MotorGpuParams.packAFloat(cm),
                       frame = MotorGpuParams.packFrameFloat(cm), F8h = MotorGpuParams.packF8Float(cm),
                       outGeom = new FloatArray(9);
            IntArray counts = new IntArray(4); counts.set(0, 1); counts.set(1, tf[0]); counts.set(2, s.seed); counts.set(3, brown);

            TaskGraph tg;
            if (calibrated) {
                FloatArray params = MotorGpuParams.packCalibrated(cm), supGeom = MotorGpuParams.packSupGeom(cm);
                tg = new TaskGraph(kernel)
                        .transferToDevice(DataTransferMode.FIRST_EXECUTION, q, A, frame, supGeom, F8h, params)
                        .transferToDevice(DataTransferMode.EVERY_EXECUTION, counts)
                        .task("step", TwoBodyGpuKernels::calibratedStep, q, A, frame, supGeom, F8h, params, outGeom, counts)
                        .transferToHost(DataTransferMode.EVERY_EXECUTION, q, A, outGeom);
            } else {
                FloatArray params = MotorGpuParams.packFixed(cm);
                tg = new TaskGraph(kernel)
                        .transferToDevice(DataTransferMode.FIRST_EXECUTION, q, A, frame, F8h, params)
                        .transferToDevice(DataTransferMode.EVERY_EXECUTION, counts)
                        .task("step", TwoBodyGpuKernels::fixedStep, q, A, frame, params, F8h, outGeom, counts)
                        .transferToHost(DataTransferMode.EVERY_EXECUTION, q, A, outGeom);
            }
            GridScheduler sched = new GridScheduler();
            WorkerGrid w = new WorkerGrid1D(1); w.setLocalWork(1, 1, 1); sched.addWorkerGrid(kernel + ".step", w);
            TornadoExecutionPlan plan = new TornadoExecutionPlan(tg.snapshot());

            long t0 = System.nanoTime();
            plan.withGridScheduler(sched).execute();
            long ns = System.nanoTime() - t0;

            // compare to golden CPU-runner oracle
            Map<String,Double> got = new LinkedHashMap<>();
            got.put("phi", (double) q.get(0)); got.put("psi", (double) q.get(1));
            got.put("Ax", (double) A.get(0)); got.put("Ay", (double) A.get(1)); got.put("Az", (double) A.get(2));
            String[] gk = {"Cx","Cy","Cz","xHx","xHy","xHz","xF8x","xF8y","xF8z"};
            for (int k = 0; k < 9; k++) got.put(gk[k], (double) outGeom.get(k));
            reportRun(kernel, fixture, ns, memBefore, got, loadExpected(fixture));
            return true;
        } catch (Throwable ex) {
            reportFail(kernel, ex);
            return false;
        }
    }

    static boolean probeExplicit(String fixture) {
        System.out.printf(Locale.US, "%n--- explicitBeamStep  (fixture %s, double) ---%n", fixture);
        log("\n## explicitBeamStep  (fixture " + fixture + ", DOUBLE)\n");
        String memBefore = gpuMemUsed();
        try {
            MotorReplayHarness.Spec s = specByName(fixture);
            int[] tf = new int[1];
            TwoBodyConverterMotor.Cmot cm = presolvedCmot(s, tf);
            int brown = s.brownian ? 1 : 0; int M = cm.g4M;

            DoubleArray nodes = MotorGpuParams.packNodes(cm), frame = MotorGpuParams.packFrameExplicit(cm),
                        q = MotorGpuParams.packQDouble(cm), F8h = MotorGpuParams.packF8Double(cm),
                        params = MotorGpuParams.packExplicit(cm), outGeom = new DoubleArray(9);
            IntArray counts = new IntArray(5);
            counts.set(0, 1); counts.set(1, tf[0]); counts.set(2, s.seed); counts.set(3, brown); counts.set(4, M);

            TaskGraph tg = new TaskGraph("explicitBeamStep")
                    .transferToDevice(DataTransferMode.FIRST_EXECUTION, frame, F8h, params)
                    .transferToDevice(DataTransferMode.EVERY_EXECUTION, nodes, q, counts)
                    .task("step", TwoBodyGpuKernels::explicitBeamStep, nodes, frame, q, F8h, params, outGeom, counts)
                    .transferToHost(DataTransferMode.EVERY_EXECUTION, nodes, q, outGeom);
            GridScheduler sched = new GridScheduler();
            WorkerGrid w = new WorkerGrid1D(1); w.setLocalWork(1, 1, 1); sched.addWorkerGrid("explicitBeamStep.step", w);
            TornadoExecutionPlan plan = new TornadoExecutionPlan(tg.snapshot());

            long t0 = System.nanoTime();
            plan.withGridScheduler(sched).execute();
            long ns = System.nanoTime() - t0;

            Map<String,Double> got = new LinkedHashMap<>();
            got.put("phi", q.get(0)); got.put("psi", q.get(1));
            got.put("Ax", nodes.get(3*M)); got.put("Ay", nodes.get(3*M+1)); got.put("Az", nodes.get(3*M+2));
            String[] gk = {"Cx","Cy","Cz","xHx","xHy","xHz","xF8x","xF8y","xF8z"};
            for (int k = 0; k < 9; k++) got.put(gk[k], outGeom.get(k));
            for (int j = 0; j <= M; j++) { got.put("node"+j+"x", nodes.get(3*j)); got.put("node"+j+"y", nodes.get(3*j+1)); got.put("node"+j+"z", nodes.get(3*j+2)); }
            reportRun("explicitBeamStep", fixture, ns, memBefore, got, loadExpected(fixture));
            return true;
        } catch (Throwable ex) {
            reportFail("explicitBeamStep", ex);
            return false;
        }
    }

    // ================================================================================================
    static void reportRun(String kernel, String fixture, long ns, String memBefore, Map<String,Double> got, Map<String,Double> exp) {
        double maxAbs = 0, maxRel = 0; String worst = "-"; int nNan = 0, nInf = 0, cmp = 0;
        for (Map.Entry<String,Double> e : got.entrySet()) {
            double g = e.getValue();
            if (Double.isNaN(g)) nNan++;
            if (Double.isInfinite(g)) nInf++;
            Double ev = exp.get(e.getKey());
            if (ev == null) continue;
            cmp++;
            double d = Math.abs(g - ev.doubleValue());
            double rel = Math.abs(ev) > 1e-300 ? d / Math.abs(ev) : (d == 0 ? 0 : Double.POSITIVE_INFINITY);
            if (d > maxAbs) { maxAbs = d; }
            if (rel > maxRel && d > 1e-12) { maxRel = rel; worst = e.getKey(); }
        }
        System.out.printf(Locale.US, "  LOWERS+RUNS: YES  [%.1f ms incl compile]  vs CPU-oracle: cmp=%d maxAbsΔ=%.2e maxRelΔ=%.2e NaN=%d Inf=%d worst=%s%n",
                ns / 1e6, cmp, maxAbs, maxRel, nNan, nInf, worst);
        log(String.format(Locale.US, "- LOWERS+RUNS: **YES** — runtime %.1f ms (incl first-launch compile); vs CPU-oracle: cmp=%d maxAbsΔ=%.2e maxRelΔ=%.2e NaN=%d Inf=%d (worst=%s); mem %s→%s MiB%n",
                ns / 1e6, cmp, maxAbs, maxRel, nNan, nInf, worst, memBefore, gpuMemUsed()));
    }
    static void reportFail(String kernel, Throwable ex) {
        Throwable root = ex; while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        System.out.printf(Locale.US, "  LOWERS+RUNS: NO%n    exception: %s%n    message  : %s%n", ex.getClass().getName(), oneLine(ex.getMessage()));
        if (root != ex) System.out.printf(Locale.US, "    root     : %s: %s%n", root.getClass().getName(), oneLine(root.getMessage()));
        log("- LOWERS+RUNS: **NO**\n");
        log("  - exception: `" + ex.getClass().getName() + "`\n");
        log("  - message: " + oneLine(ex.getMessage()) + "\n");
        if (root != ex) log("  - root cause: `" + root.getClass().getName() + "`: " + oneLine(root.getMessage()) + "\n");
    }

    static String oneLine(String s) { return s == null ? "(none)" : s.replaceAll("\\s+", " ").trim(); }
    static String dump(FloatArray a, int n) { StringBuilder b = new StringBuilder(); for (int i = 0; i < n; i++) { if (i>0) b.append(','); b.append(a.get(i)); } return b.toString(); }
    static void log(String s) { LOG.append(s); }

    static String gpuMemUsed() {
        try {
            Process p = new ProcessBuilder("nvidia-smi", "--query-gpu=memory.used", "--format=csv,noheader,nounits").start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            return out.isEmpty() ? "?" : out.split("\\R")[0].trim();
        } catch (Exception e) { return "?"; }
    }
}
