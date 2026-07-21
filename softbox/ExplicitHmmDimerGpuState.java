package softbox;

import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.TornadoExecutionResult;
import uk.ac.manchester.tornado.api.GridScheduler;
import uk.ac.manchester.tornado.api.WorkerGrid;
import uk.ac.manchester.tornado.api.WorkerGrid1D;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

import java.util.List;

/**
 * ============ EXPLICIT-HMM-DIMER — persistent device-resident mechanical state (Phase G2/G3) ============
 * Owns the full lawn's forked-dimer mechanical state on the GPU across many timesteps. The state buffers
 * ({@code D}, {@code sc}) are uploaded ONCE ({@link DataTransferMode#FIRST_EXECUTION}) and thereafter evolve
 * in place on the device — {@code step()} re-uploads only the compact per-step control (active id list +
 * external F8/θ_s inputs + counts), NOT the ~2 KB/dimer state. Full state is pulled to the host only on
 * demand (validation / health / report stride) via {@link DataTransferMode#UNDER_DEMAND}.
 *
 * OWNERSHIP (this milestone is HYBRID — GPU mechanics, CPU assay orchestration):
 *   GPU-authoritative after init: node coords, head angles, converter/F8 geometry, scratch, mechanical diagnostics.
 *   CPU-authoritative           : the active-cull decision, binding, chemistry, occupancy exclusion, gathering,
 *                                 actin — and the per-step F8/θ_s inputs it injects. (All of these are G4 targets.)
 *
 * PERSISTENT IDENTITY: every lawn dimer has a stable id 0..total-1. The active list holds PERSISTENT ids; the
 * kernel maps activeSlot→id→(id·DIM_STRIDE) resident offset. State is NEVER migrated/reordered when the active
 * set changes; an inactive dimer is simply not visited, so its resident state and scratch are preserved exactly.
 * =====================================================================================================
 */
public final class ExplicitHmmDimerGpuState {

    public final int total;
    static final int DS = ExplicitHmmDimerGpu.DIM_STRIDE, SS = ExplicitHmmDimerGpu.SCRATCH_STRIDE;
    static final int LOCAL = 64;

    public FloatArray D, sp, sc, inF8, inThs;   // D,sc resident; inF8/inThs compact per-step inputs (active-indexed)
    public IntArray activeIds, counts, status, topo;
    TornadoExecutionPlan plan; GridScheduler gs; WorkerGrid wg;
    public long residentStateBytes, scratchBytes, initTransferBytes; public double initMs;

    public ExplicitHmmDimerGpuState(int total) { this.total = total; }

    static int pad(int n) { int p = ((n + LOCAL - 1) / LOCAL) * LOCAL; return p < LOCAL ? LOCAL : p; }

    /** One-time init: pack all standing dimer state into resident float arrays + build the resident plan. */
    public void init(List<float[]> blocks, float[] spArr, IntArray topoArr) {
        long t0 = System.nanoTime();
        D = new FloatArray(total * DS);
        for (int m = 0; m < total; m++) { float[] b = blocks.get(m); for (int i = 0; i < DS; i++) D.set(m * DS + i, b[i]); }
        sp = new FloatArray(spArr.length); for (int i = 0; i < spArr.length; i++) sp.set(i, spArr[i]);
        sc = new FloatArray(total * SS); sc.init(0f);
        inF8 = new FloatArray(total * 6); inF8.init(0f);
        inThs = new FloatArray(total * 2); inThs.init(0f);
        activeIds = new IntArray(total); activeIds.init(0);
        status = new IntArray(total); status.init(0);
        counts = new IntArray(ExplicitHmmDimerGpuKernel.COUNTS_LEN);   // incl. Brownian control slots (default 0 = off)
        counts.set(ExplicitHmmDimerGpuKernel.C_NDIM, 0); counts.set(ExplicitHmmDimerGpuKernel.C_NF, 5); counts.set(ExplicitHmmDimerGpuKernel.C_NDOF, 19);
        counts.set(ExplicitHmmDimerGpuKernel.C_W, 20); counts.set(ExplicitHmmDimerGpuKernel.C_NSEG, 5); counts.set(ExplicitHmmDimerGpuKernel.C_NHINGE, 4); counts.set(ExplicitHmmDimerGpuKernel.C_NODES, 6);
        counts.set(ExplicitHmmDimerGpuKernel.C_STEP, 0); counts.set(ExplicitHmmDimerGpuKernel.C_SEED, 0); counts.set(ExplicitHmmDimerGpuKernel.C_BROWN, 0);
        topo = topoArr;

        TaskGraph tg = new TaskGraph("hmmDimerResident")
            .transferToDevice(DataTransferMode.FIRST_EXECUTION, D, sp, sc, topo)                 // uploaded ONCE, resident
            .transferToDevice(DataTransferMode.EVERY_EXECUTION, activeIds, inF8, inThs, counts)  // compact per-step control
            .task("solve", ExplicitHmmDimerGpuKernel::solveActiveFloat, D, sp, sc, activeIds, inF8, inThs, topo, counts, status)
            .transferToHost(DataTransferMode.UNDER_DEMAND, D, status);                            // pulled only when asked
        wg = new WorkerGrid1D(pad(total)); wg.setLocalWork(LOCAL, 1, 1);
        gs = new GridScheduler("hmmDimerResident.solve", wg);
        plan = new TornadoExecutionPlan(tg.snapshot());

        residentStateBytes = (long) total * DS * 4; scratchBytes = (long) total * SS * 4;
        initTransferBytes = residentStateBytes + scratchBytes + (long) sp.getSize() * 4 + (long) topo.getSize() * 4;
        initMs = (System.nanoTime() - t0) / 1e6;
    }

    /** Per-step CPU→GPU control bytes (compact: active ids + F8 + θ_s + counts). */
    public long perStepUploadBytes(int activeCount) { return (long) activeCount * 4 + (long) activeCount * 6 * 4 + (long) activeCount * 2 * 4 + 7 * 4; }

    /**
     * Advance one global step over the active list on the GPU (state stays resident).
     * @param ids       persistent dimer ids, first {@code n} valid.
     * @param n         active count.
     * @param f8flat    compact F8 inputs, {@code n·6} = [f8A xyz, f8B xyz] per active (pN).
     * @param thsflat   compact θ_s inputs, {@code n·2} per active (rad).
     * @param pullState if true, pull the full resident state back to the host (validation/report stride only).
     */
    public TornadoExecutionResult step(int[] ids, int n, float[] f8flat, float[] thsflat, boolean pullState) {
        for (int i = 0; i < n; i++) activeIds.set(i, ids[i]);
        for (int i = 0; i < n * 6; i++) inF8.set(i, f8flat[i]);
        for (int i = 0; i < n * 2; i++) inThs.set(i, thsflat[i]);
        counts.set(ExplicitHmmDimerGpuKernel.C_NDIM, n);
        wg.setGlobalWork(pad(n), 1, 1);
        TornadoExecutionResult res = plan.withGridScheduler(gs).execute();
        res.transferToHost(status);              // small, every step
        if (pullState) res.transferToHost(D);    // full resident state, on demand only
        return res;
    }
}
