package softbox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

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
 * FIRST device-resident motor-mat SoA VERTICAL SLICE (calibrated-only, experimental).
 *
 * <p>Builds the calibrated gliding mat (`buildSupMat`) as the host oracle and validates each new device
 * kernel in ISOLATION (Part 5): identical flat SoA in → host stage (the FROZEN semantics in
 * `TwoBodyConverterMotor`) + GPU stage → compare every changed field, verify unrelated fields unchanged.
 * All GPU runs require `-Dtornado.recover.bailout=false` (a lowering failure throws — no silent CPU
 * fallback). Motor SoA stays device-resident; only compact filament state + scalar controls + reduced
 * outputs cross the bus. Does NOT flip {@code MotorGpuParams.DEVICE_VALIDATED} (experimental).
 *
 * <p>Stage 1 (matCull) implemented + gated here. Cull distance is computed in DOUBLE to match the host
 * `siteSegDist2` bit-for-decision (the active SET must be identical). The grid acceleration
 * (`initMatGrid`) is a pure optimization — the per-motor brute union gather here yields the IDENTICAL
 * active set (cullMode=1 grid ≡ cullMode=2 brute), and is race-free (per-motor gather, no scatter).
 */
public final class MatSoaSlice {
    private MatSoaSlice() {}

    static final String OUTDIR = "RUN_LOGS/matsoa";

    // ===============================================================================================
    // KERNEL — Stage 1: matCull.  active[m] = (boundSeg[m] >= 0) OR (site_m within queryR of ANY segment).
    //   Reproduces unionActive (TwoBodyConverterMotor.L4721) as a race-free per-motor gather.
    //   site: DoubleArray planar 2N (x=[m], y=[N+m]) — the fixed ideal head site (host G.siteX/Y are double).
    //   filCoord/filUVec/filSegLen: FilamentStore FloatArrays (planar: X=[s], Y=[nSeg+s]).
    //   cullParams[0] = queryR^2 (double, = G.queryR^2). counts = {N, t, seed, nSeg}. active[N] written.
    // ===============================================================================================
    public static void matCull(IntArray boundSeg, DoubleArray site,
                               FloatArray filCoord, FloatArray filUVec, FloatArray filSegLen,
                               DoubleArray cullParams, IntArray counts, IntArray active) {
        int N = counts.get(0);
        int nSeg = counts.get(3);
        double qr2 = cullParams.get(0);
        for (@Parallel int m = 0; m < N; m++) {
            int a;
            if (boundSeg.get(m) >= 0) {
                a = 1;
            } else {
                double sx = site.get(m), sy = site.get(N + m);
                a = 0;
                for (int s = 0; s < nSeg; s++) {
                    double half = 0.5 * (double) filSegLen.get(s);
                    double cx = filCoord.get(s), cy = filCoord.get(nSeg + s);
                    double ux = filUVec.get(s), uy = filUVec.get(nSeg + s);
                    double dx = sx - cx, dy = sy - cy;
                    double foot = dx * ux + dy * uy;
                    foot = foot < -half ? -half : (foot > half ? half : foot);   // == Math.max(-half,Math.min(half,foot)), reinterpret-free
                    double px = dx - foot * ux, py = dy - foot * uy;
                    double d2 = px * px + py * py;
                    if (d2 <= qr2) a = 1;
                }
            }
            active.set(m, a);
        }
    }

    // ===============================================================================================
    public static void main(String[] args) {
        Path dir = Path.of(OUTDIR);
        try { Files.createDirectories(dir); } catch (IOException ex) { throw new UncheckedIOException(ex); }
        boolean bailoutOff = "false".equals(System.getProperty("tornado.recover.bailout"));
        if (!bailoutOff) System.out.println("!! WARNING: run with -Dtornado.recover.bailout=false — else a lowering failure SILENTLY falls back to CPU.");
        System.out.println("=== MAT-SOA VERTICAL SLICE (calibrated) — Part 5 isolated stage gates ===");
        StringBuilder log = new StringBuilder();
        log.append("# MAT-SOA VERTICAL SLICE — isolated stage gates (calibrated, RTX 5070 / PTX)\n");
        log.append("# bailout disabled: ").append(bailoutOff).append("  | device mem start ").append(gpuMemUsed()).append(" MiB\n\n");

        boolean cullOk = gateCull(log);

        try { Files.writeString(dir.resolve("STAGE_GATES.md"), log.toString()); } catch (IOException ex) { throw new UncheckedIOException(ex); }
        System.out.println("\n=== SLICE STATUS ===");
        System.out.printf(Locale.US, "Stage 1 matCull (active-set identity): %s%n", cullOk ? "PASS" : "FAIL");
        System.out.println("# report: " + dir.resolve("STAGE_GATES.md").toAbsolutePath());
        System.exit(cullOk ? 0 : 1);
    }

    // ---------- Part 5.1 — cull active-set IDENTITY ----------
    static boolean gateCull(StringBuilder log) {
        System.out.println("\n--- Part 5.1: matCull vs host unionActive (active-set identity) ---");
        log.append("## Stage 1 — matCull (active-set identity vs unionActive)\n");
        double dt = 2.5e-6;
        boolean allPass = true;
        // scenarios: small deterministic mats at several densities/seeds; exercises active/inactive/bound/boundary.
        int[] seeds = { 11, 12, 13 };
        double[] densities = { 200, 700 };
        for (double density : densities) {
            for (int seed : seeds) {
                TwoBodyConverterMotor.Glide2D G = TwoBodyConverterMotor.buildSupMat(density, dt, 0.0, seed);
                int N = G.N, nSeg = G.nSeg;
                // exercise the bound branch too: deterministically pre-bind ~1/8 of motors to a valid segment
                for (int m = 0; m < N; m += 8) G.mot.boundSeg.set(m, m % nSeg);
                // --- host oracle ---
                TwoBodyConverterMotor.unionActive(G);
                // --- pack flat SoA (motor state stays here after this; only counts re-upload per step) ---
                DoubleArray site = new DoubleArray(2 * N);
                IntArray boundSeg = new IntArray(N);
                for (int m = 0; m < N; m++) { site.set(m, G.siteX[m]); site.set(N + m, G.siteY[m]); boundSeg.set(m, G.mot.boundSeg.get(m)); }
                FilamentStore f = G.fil;
                DoubleArray cullParams = DoubleArray.fromElements(G.queryR * G.queryR);
                IntArray counts = new IntArray(4); counts.set(0, N); counts.set(1, 0); counts.set(2, seed); counts.set(3, nSeg);
                IntArray active = new IntArray(N); active.init(0);

                // --- device: real TaskGraph, motor SoA persistent, filament small, only counts EVERY_EXECUTION ---
                long dev0 = System.nanoTime();
                try {
                    TaskGraph tg = new TaskGraph("cull")
                            .transferToDevice(DataTransferMode.FIRST_EXECUTION, boundSeg, site, f.coord, f.uVec, f.segLength, cullParams)
                            .transferToDevice(DataTransferMode.EVERY_EXECUTION, counts)
                            .task("matCull", MatSoaSlice::matCull, boundSeg, site, f.coord, f.uVec, f.segLength, cullParams, counts, active)
                            .transferToHost(DataTransferMode.EVERY_EXECUTION, active);
                    GridScheduler sched = new GridScheduler();
                    WorkerGrid w = new WorkerGrid1D(N); w.setLocalWork(1, 1, 1); sched.addWorkerGrid("cull.matCull", w);
                    TornadoExecutionPlan plan = new TornadoExecutionPlan(tg.snapshot());
                    plan.withGridScheduler(sched).execute();
                } catch (Throwable ex) {
                    Throwable root = ex; while (root.getCause() != null && root.getCause() != root) root = root.getCause();
                    System.out.printf(Locale.US, "  density=%.0f seed=%d N=%d: LOWERS=NO — %s: %s%n", density, seed, N, ex.getClass().getName(), oneLine(ex.getMessage()));
                    log.append(String.format(Locale.US, "- density=%.0f seed=%d N=%d: **LOWERS=NO** — `%s`: %s\n", density, seed, N, root.getClass().getName(), oneLine(root.getMessage())));
                    return false;
                }
                double devMs = (System.nanoTime() - dev0) / 1e6;

                int nActiveHost = 0, mism = 0, firstMism = -1;
                for (int m = 0; m < N; m++) {
                    int h = G.active[m] ? 1 : 0;
                    if (h == 1) nActiveHost++;
                    if (active.get(m) != h) { mism++; if (firstMism < 0) firstMism = m; }
                }
                boolean pass = (mism == 0);
                allPass &= pass;
                System.out.printf(Locale.US, "  density=%.0f seed=%d N=%d nSeg=%d: activeHost=%d mismatches=%d %s [%.1f ms]%n",
                        density, seed, N, nSeg, nActiveHost, mism, pass ? "PASS" : ("FAIL@" + firstMism), devMs);
                log.append(String.format(Locale.US, "- density=%.0f seed=%d N=%d nSeg=%d: activeHost=%d, mismatches=%d → %s (bytes/step: counts=16 up, active=%d down; no full mat transfer)\n",
                        density, seed, N, nSeg, nActiveHost, mism, pass ? "PASS" : "FAIL", 4 * N));
            }
        }
        log.append("- Residency: boundSeg/site/fil.coord/fil.uVec/fil.segLength/cullParams uploaded FIRST_EXECUTION (once); ")
           .append("only `counts` (16 B) re-uploads EVERY_EXECUTION and `active` (4N B) is read back — NO full per-motor state transfer.\n\n");
        return allPass;
    }

    static String oneLine(String s) { return s == null ? "(none)" : s.replaceAll("\\s+", " ").trim(); }
    static String gpuMemUsed() {
        try { Process p = new ProcessBuilder("nvidia-smi", "--query-gpu=memory.used", "--format=csv,noheader,nounits").start();
            String out = new String(p.getInputStream().readAllBytes()).trim(); p.waitFor();
            return out.isEmpty() ? "?" : out.split("\\R")[0].trim(); } catch (Exception e) { return "?"; }
    }
}
