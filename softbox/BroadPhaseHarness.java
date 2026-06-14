package softbox;

import uk.ac.manchester.tornado.api.GridScheduler;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.TornadoExecutionResult;
import uk.ac.manchester.tornado.api.WorkerGrid;
import uk.ac.manchester.tornado.api.WorkerGrid1D;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * Increment-3 harness: entity-agnostic spatial grid + broad-phase, validated by
 * exact set-equality against an O(N²) brute-force reference, on BOTH the GPU
 * TaskGraph and the sequential -cpu runner, with the CSR proven bit-identical
 * CPU↔GPU and O(N) vs O(N²) scaling demonstrated.
 *
 * INFRASTRUCTURE ONLY — no forces are written this increment. Bodies (free rods)
 * diffuse translationally (the inc-1 brownian→integrate step); each step the grid
 * is rebuilt from scratch over the SpatialBodyView and the broad-phase emits
 * candidate pairs. The FDT / deflection / chain paths in DiffusionHarness are
 * untouched.
 *
 * Per step (one physics, two runners — same system methods, GPU TaskGraph vs CPU
 * sequential): brownian → integrate → publishToBodyView → bodyCell → gridZero →
 * gridHistogram → gridScanLocal → gridScanChunks → gridScanAdd → gridScatter →
 * broadPhase → bruteForce.
 */
public final class BroadPhaseHarness {

    static final int B = 64;                 // block size (the CUDA-701 lesson: never default)
    static final double TOL_NONE = 0.0;      // exact-match: no tolerance
    static boolean cpu = false;              // -cpu: run the comparison on the CPU runner only
    static GridScheduler sched;

    public static void main(String[] args) {
        double dt = Constants.deltaT;
        java.util.List<String> pos = new java.util.ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-cpu")) cpu = true;
            else if (args[i].equals("-dt")) dt = Double.parseDouble(args[++i]);
            else pos.add(args[i]);
        }
        int N = pos.size() >= 1 ? Integer.parseInt(pos.get(0)) : 512;
        int M = pos.size() >= 2 ? Integer.parseInt(pos.get(1)) : 2000;

        System.out.println("=== Soft Box increment 3 — spatial grid + broad-phase ===");
        System.out.println("INFRASTRUCTURE ONLY: no forces written this increment.");

        boolean ok;
        if (cpu) {
            // -cpu: run the whole comparison on the CPU runner only (triage mode).
            System.out.println("runner: CPU sequential (same system methods, no TaskGraph)");
            Snapshot[] snaps = runOnce(N, M, dt, true);
            ok = reportExactMatch("CPU", snaps);
        } else {
            // Default: run GPU and CPU on identical seeds; gate grid==brute on each AND
            // CSR/candidate bit-identity CPU↔GPU.
            System.out.println("runner: GPU TaskGraph (TornadoVM PTX)  +  CPU cross-check");
            Snapshot[] gpu = runOnce(N, M, dt, false);
            Snapshot[] cpuSnaps = runOnce(N, M, dt, true);
            boolean okGpu = reportExactMatch("GPU", gpu);
            boolean okCpu = reportExactMatch("CPU", cpuSnaps);
            boolean okIdent = reportCpuGpuIdentity(gpu, cpuSnaps);
            ok = okGpu && okCpu && okIdent;
        }

        System.out.println();
        runScaling(dt);

        System.out.println();
        System.out.println("=== BROAD-PHASE VALIDATION " + (ok ? "PASS" : "FAIL") + " (exact set-equality, no tolerance) ===");
        if (!ok) {
            System.out.println("BAIL-OUT: broad-phase set != brute force, or CSR/candidate CPU!=GPU. "
                    + "Use the CPU runner to localize (scan/CSR vs stencil/cutoff). Commit nothing.");
            System.exit(1);
        }
    }

    // ============================================================== scratch + setup
    /** All device-resident grid/broad-phase working buffers + constants. */
    static final class GridScratch {
        final int cap, totalCells;
        final FloatArray gridParams;   // [xMin,yMin,zMin,cellSize,invCellSize,cutoff]
        final IntArray   gridDims;     // [nX,nY,nZ,totalCells]
        final IntArray   gridCounts;   // [_, S]
        final FloatArray viewParams;   // [actinRadius]
        final IntArray   bodyCell;     // cap
        final IntArray   cellCount;    // totalCells
        final IntArray   chunkSum;     // ceil(totalCells/CHUNK)+1
        final IntArray   gridCellOffsets;   // totalCells+1
        final IntArray   gridCellContents;  // cap (center binning: one entry per body)
        final IntArray   candPartner, candCount;     // cap*MAX_CAND, cap
        final IntArray   brutePartner, bruteCount;   // cap*MAX_CAND, cap

        GridScratch(int cap, int nX, int nY, int nZ,
                    double xMin, double yMin, double zMin, double cellSize, double cutoff) {
            this.cap = cap;
            this.totalCells = nX * nY * nZ;
            gridParams = FloatArray.fromElements(
                    (float) xMin, (float) yMin, (float) zMin,
                    (float) cellSize, (float) (1.0 / cellSize), (float) cutoff);
            gridDims = IntArray.fromElements(nX, nY, nZ, totalCells);
            gridCounts = new IntArray(4);
            viewParams = FloatArray.fromElements((float) Constants.radius);
            bodyCell   = new IntArray(cap);
            cellCount  = new IntArray(totalCells);
            chunkSum   = new IntArray((totalCells + SpatialGrid.GRID_SCAN_CHUNK - 1) / SpatialGrid.GRID_SCAN_CHUNK + 1);
            gridCellOffsets  = new IntArray(totalCells + 1);
            gridCellContents = new IntArray(cap);
            candPartner = new IntArray(cap * SpatialGrid.MAX_CAND);
            candCount   = new IntArray(cap);
            brutePartner = new IntArray(cap * SpatialGrid.MAX_CAND);
            bruteCount   = new IntArray(cap);
            bodyCell.init(-1); cellCount.init(0); chunkSum.init(0);
            gridCellOffsets.init(0); gridCellContents.init(-1);
            candPartner.init(-1); candCount.init(0); brutePartner.init(-1); bruteCount.init(0);
        }
    }

    /** Build a cluster of N free rods + a body view + grid scratch sized for it.
     *  clusterHalf grows ∝ N^(1/3) so density (hence neighbors/body) is N-independent
     *  → grid work O(N), brute work O(N²). cutoff = boundingRadius (interaction slack). */
    static Object[] buildRun(int N, double dt, long seed) {
        final int MONOMER_CT = Constants.stdSegLength;   // 32
        FilamentStore s = new FilamentStore(N);
        double segLen = (MONOMER_CT + 1) * Constants.actinMonoRadius;       // µm
        double boundR = 0.5 * segLen + Constants.radius;                    // bounding sphere radius
        double cutoff = boundR;                                             // interaction slack
        double cellSize = 2.0 * boundR + cutoff;                           // 27-stencil completeness

        // density-fixed cluster: half-size scales with N^(1/3) off a 512-body / 0.4µm baseline
        double clusterHalf = 0.4 * Math.cbrt(N / 512.0);

        java.util.Random rng = new java.util.Random(seed);
        for (int i = 0; i < N; i++) {
            s.monomerCount.set(i, MONOMER_CT);
            s.setUVec(i, 1f, 0f, 0f);
            s.setYVec(i, 0f, 1f, 0f);
            s.setCoord(i,
                    (float) ((rng.nextDouble() - 0.5) * 2 * clusterHalf),
                    (float) ((rng.nextDouble() - 0.5) * 2 * clusterHalf),
                    (float) ((rng.nextDouble() - 0.5) * 2 * clusterHalf));
            s.brownTransScale.set(i, 1.0f);
            s.brownRotScale.set(i, 0f);   // sphere binning: orientation irrelevant; freeze it
        }
        DragTensorSystem.run(s);
        s.setParams(dt, Math.sqrt(2.0 * Constants.kT / dt));

        // Grid extent: cluster + generous diffusion slack. Bodies must stay strictly
        // interior (a clamped edge body could break 27-stencil completeness); the
        // per-sample interior check flags any escape rather than silently missing a pair.
        // Translational rms over a long run is ~0.2 µm; 1 µm slack each side is ample.
        double half = clusterHalf + 1.0;
        int nBins = 1 + (int) Math.ceil((2 * half) / cellSize);
        GridScratch g = new GridScratch(N, nBins, nBins, nBins, -half, -half, -half, cellSize, cutoff);
        g.gridCounts.set(1, N);
        s.setCounts(0, (int) (seed & 0x7fffffff));
        SpatialBodyView view = new SpatialBodyView(N);
        view.count = N;
        return new Object[]{ s, view, g, clusterHalf, cellSize };
    }

    // ============================================================== one run (GPU or CPU)
    static final class Snapshot {
        int step;
        long[] candPairs;    // sorted (i<<32)|j, i<j
        long[] brutePairs;   // sorted
        int[]  csrOffsets;   // totalCells+1
        int[]  csrContents;  // [0,S)
        int    maxCand, maxBrute;
        boolean interiorOK;  // no body clamped to a grid edge cell
        boolean match;       // candPairs == brutePairs (exact)
    }

    /** Run N bodies for M steps on the selected runner; snapshot + validate at sampled steps. */
    static Snapshot[] runOnce(int N, int M, double dt, boolean useCpu) {
        Object[] r = buildRun(N, dt, 0xB0A5EEDL);
        FilamentStore s = (FilamentStore) r[0];
        SpatialBodyView view = (SpatialBodyView) r[1];
        GridScratch g = (GridScratch) r[2];

        // sample steps (densest cluster first): 0, M/4, M/2, 3M/4, M-1
        int[] samples = dedup(new int[]{ 0, M / 4, M / 2, (3 * M) / 4, Math.max(0, M - 1) });
        java.util.List<Snapshot> out = new java.util.ArrayList<>();

        int last = samples[samples.length - 1];
        if (useCpu) {
            Runnable step = cpuGridStep(s, view, g);
            int si = 0;
            for (int stepIdx = 0; stepIdx <= last; stepIdx++) {
                s.counts.set(1, stepIdx);
                step.run();   // brownian→integrate→publish→grid→broadPhase→bruteForce
                if (si < samples.length && stepIdx == samples[si]) {
                    out.add(snapshot(stepIdx, s, view, g));  // grid now reflects this step's pose
                    si++;
                }
            }
        } else {
            TornadoExecutionPlan plan = buildGridPlan(s, view, g);
            int si = 0;
            for (int stepIdx = 0; stepIdx <= last; stepIdx++) {
                s.counts.set(1, stepIdx);
                TornadoExecutionResult res = plan.withGridScheduler(sched).execute();
                if (si < samples.length && stepIdx == samples[si]) {
                    res.transferToHost(g.candPartner, g.candCount, g.brutePartner, g.bruteCount,
                            g.gridCellOffsets, g.gridCellContents, g.bodyCell);
                    out.add(snapshot(stepIdx, s, view, g));
                    si++;
                }
            }
        }
        return out.toArray(new Snapshot[0]);
    }

    /** Pull the candidate/brute/CSR state into a host Snapshot and validate grid==brute. */
    static Snapshot snapshot(int step, FilamentStore s, SpatialBodyView view, GridScratch g) {
        int S = view.count;
        Snapshot snap = new Snapshot();
        snap.step = step;
        snap.candPairs  = pairs(g.candPartner, g.candCount, S);
        snap.brutePairs = pairs(g.brutePartner, g.bruteCount, S);
        snap.maxCand  = maxCount(g.candCount, S);
        snap.maxBrute = maxCount(g.bruteCount, S);
        snap.csrOffsets = new int[g.totalCells + 1];
        for (int c = 0; c <= g.totalCells; c++) snap.csrOffsets[c] = g.gridCellOffsets.get(c);
        snap.csrContents = new int[S];
        for (int k = 0; k < S; k++) snap.csrContents[k] = g.gridCellContents.get(k);
        // interior check: a clamped body (cell on the grid boundary) could break completeness.
        int nX = g.gridDims.get(0), nY = g.gridDims.get(1), nZ = g.gridDims.get(2), nXY = nX * nY;
        boolean interior = true;
        for (int i = 0; i < S; i++) {
            int c = g.bodyCell.get(i);
            if (c < 0) { interior = false; break; }
            int cz = c / nXY, rem = c - cz * nXY, cy = rem / nX, cx = rem - cy * nX;
            if (cx == 0 || cx == nX - 1 || cy == 0 || cy == nY - 1 || cz == 0 || cz == nZ - 1) { interior = false; break; }
        }
        snap.interiorOK = interior;
        snap.match = java.util.Arrays.equals(snap.candPairs, snap.brutePairs);
        return snap;
    }

    // ============================================================== GPU plan + CPU step
    private static TornadoExecutionPlan buildGridPlan(FilamentStore s, SpatialBodyView v, GridScratch g) {
        TaskGraph tg = new TaskGraph("broadphase")
            .transferToDevice(DataTransferMode.FIRST_EXECUTION,
                    s.coord, s.uVec, s.yVec, s.forceSum, s.torqueSum, s.randForce, s.randTorque,
                    s.bTransGam, s.bRotGam, s.brownTransScale, s.brownRotScale, s.params, s.segLength,
                    v.center, v.boundingRadius, v.ownerStore, v.ownerSlot,
                    g.gridParams, g.gridDims, g.gridCounts, g.viewParams,
                    g.bodyCell, g.cellCount, g.chunkSum, g.gridCellOffsets, g.gridCellContents,
                    g.candPartner, g.candCount, g.brutePartner, g.bruteCount)
            .transferToDevice(DataTransferMode.EVERY_EXECUTION, s.counts)
            .task("brownian", BrownianForceSystem::brownianForce,
                    s.randForce, s.randTorque, s.bTransGam, s.bRotGam,
                    s.brownTransScale, s.brownRotScale, s.params, s.counts)
            .task("integrate", RigidRodLangevinIntegrationSystem::integrate,
                    s.coord, s.uVec, s.yVec, s.forceSum, s.torqueSum,
                    s.randForce, s.randTorque, s.bTransGam, s.bRotGam, s.params, s.counts)
            .task("publish", FilamentStore::publishToBodyView,
                    s.coord, s.segLength, v.center, v.boundingRadius, v.ownerStore, v.ownerSlot,
                    g.viewParams, g.gridCounts)
            .task("bodyCell", SpatialGrid::bodyCell, v.center, g.gridParams, g.gridDims, g.gridCounts, g.bodyCell)
            .task("gridZero", SpatialGrid::gridZero, g.gridDims, g.cellCount)
            .task("gridHist", SpatialGrid::gridHistogram, g.bodyCell, g.gridCounts, g.cellCount)
            .task("gridScanLocal", SpatialGrid::gridScanLocal, g.gridDims, g.cellCount, g.gridCellOffsets, g.chunkSum)
            .task("gridScanChunks", SpatialGrid::gridScanChunks, g.gridDims, g.chunkSum)
            .task("gridScanAdd", SpatialGrid::gridScanAdd, g.gridDims, g.gridCellOffsets, g.gridCellContents, g.cellCount, g.chunkSum)
            .task("gridScatter", SpatialGrid::gridScatter, g.bodyCell, g.gridCounts, g.gridCellOffsets, g.gridCellContents, g.cellCount)
            .task("broadPhase", SpatialGrid::broadPhase,
                    v.center, v.boundingRadius, g.bodyCell, g.gridCellOffsets, g.gridCellContents,
                    g.gridDims, g.gridParams, g.gridCounts, g.candPartner, g.candCount)
            .task("bruteForce", SpatialGrid::bruteForce,
                    v.center, v.boundingRadius, g.gridParams, g.gridCounts, g.brutePartner, g.bruteCount)
            .transferToHost(DataTransferMode.UNDER_DEMAND,
                    g.candPartner, g.candCount, g.brutePartner, g.bruteCount,
                    g.gridCellOffsets, g.gridCellContents, g.bodyCell);

        int cap = g.cap, totalCells = g.totalCells;
        int numScanChunks = (totalCells + SpatialGrid.GRID_SCAN_CHUNK - 1) / SpatialGrid.GRID_SCAN_CHUNK;
        sched = new GridScheduler();
        addWorker("broadphase.brownian",  pad(cap));
        addWorker("broadphase.integrate", pad(cap));
        addWorker("broadphase.publish",   pad(cap));
        addWorker("broadphase.bodyCell",  pad(cap));
        addWorker("broadphase.gridZero",  pad(totalCells));
        addWorkerSingle("broadphase.gridHist");
        addWorker("broadphase.gridScanLocal", pad(numScanChunks));
        addWorkerSingle("broadphase.gridScanChunks");
        addWorker("broadphase.gridScanAdd",   pad(numScanChunks));
        addWorkerSingle("broadphase.gridScatter");
        addWorker("broadphase.broadPhase", pad(cap));
        addWorker("broadphase.bruteForce", pad(cap));
        return new TornadoExecutionPlan(tg.snapshot());
    }

    private static int pad(int n) { return ((n + B - 1) / B) * B; }
    private static void addWorker(String name, int global) {
        WorkerGrid w = new WorkerGrid1D(global); w.setLocalWork(B, 1, 1);
        sched.addWorkerGrid(name, w);
    }
    private static void addWorkerSingle(String name) {
        WorkerGrid w = new WorkerGrid1D(1); w.setLocalWork(1, 1, 1);
        sched.addWorkerGrid(name, w);
    }

    /** CPU runner: the SAME system methods in the SAME order, sequential, host-resident. */
    private static Runnable cpuGridStep(FilamentStore s, SpatialBodyView v, GridScratch g) {
        return () -> {
            BrownianForceSystem.brownianForce(s.randForce, s.randTorque, s.bTransGam, s.bRotGam,
                    s.brownTransScale, s.brownRotScale, s.params, s.counts);
            RigidRodLangevinIntegrationSystem.integrate(s.coord, s.uVec, s.yVec, s.forceSum, s.torqueSum,
                    s.randForce, s.randTorque, s.bTransGam, s.bRotGam, s.params, s.counts);
            FilamentStore.publishToBodyView(s.coord, s.segLength, v.center, v.boundingRadius,
                    v.ownerStore, v.ownerSlot, g.viewParams, g.gridCounts);
            SpatialGrid.bodyCell(v.center, g.gridParams, g.gridDims, g.gridCounts, g.bodyCell);
            SpatialGrid.gridZero(g.gridDims, g.cellCount);
            SpatialGrid.gridHistogram(g.bodyCell, g.gridCounts, g.cellCount);
            SpatialGrid.gridScanLocal(g.gridDims, g.cellCount, g.gridCellOffsets, g.chunkSum);
            SpatialGrid.gridScanChunks(g.gridDims, g.chunkSum);
            SpatialGrid.gridScanAdd(g.gridDims, g.gridCellOffsets, g.gridCellContents, g.cellCount, g.chunkSum);
            SpatialGrid.gridScatter(g.bodyCell, g.gridCounts, g.gridCellOffsets, g.gridCellContents, g.cellCount);
            SpatialGrid.broadPhase(v.center, v.boundingRadius, g.bodyCell, g.gridCellOffsets, g.gridCellContents,
                    g.gridDims, g.gridParams, g.gridCounts, g.candPartner, g.candCount);
            SpatialGrid.bruteForce(v.center, v.boundingRadius, g.gridParams, g.gridCounts, g.brutePartner, g.bruteCount);
        };
    }

    /** Diffusion only (brownian→integrate) — advances the pose. */
    private static Runnable cpuDiffuseStep(FilamentStore s) {
        return () -> {
            BrownianForceSystem.brownianForce(s.randForce, s.randTorque, s.bTransGam, s.bRotGam,
                    s.brownTransScale, s.brownRotScale, s.params, s.counts);
            RigidRodLangevinIntegrationSystem.integrate(s.coord, s.uVec, s.yVec, s.forceSum, s.torqueSum,
                    s.randForce, s.randTorque, s.bTransGam, s.bRotGam, s.params, s.counts);
        };
    }

    /** Grid build + broad-phase only (publish→…→broadPhase), excluding brute — for timing. */
    private static Runnable cpuGridBuildStep(FilamentStore s, SpatialBodyView v, GridScratch g) {
        return () -> {
            FilamentStore.publishToBodyView(s.coord, s.segLength, v.center, v.boundingRadius,
                    v.ownerStore, v.ownerSlot, g.viewParams, g.gridCounts);
            SpatialGrid.bodyCell(v.center, g.gridParams, g.gridDims, g.gridCounts, g.bodyCell);
            SpatialGrid.gridZero(g.gridDims, g.cellCount);
            SpatialGrid.gridHistogram(g.bodyCell, g.gridCounts, g.cellCount);
            SpatialGrid.gridScanLocal(g.gridDims, g.cellCount, g.gridCellOffsets, g.chunkSum);
            SpatialGrid.gridScanChunks(g.gridDims, g.chunkSum);
            SpatialGrid.gridScanAdd(g.gridDims, g.gridCellOffsets, g.gridCellContents, g.cellCount, g.chunkSum);
            SpatialGrid.gridScatter(g.bodyCell, g.gridCounts, g.gridCellOffsets, g.gridCellContents, g.cellCount);
            SpatialGrid.broadPhase(v.center, v.boundingRadius, g.bodyCell, g.gridCellOffsets, g.gridCellContents,
                    g.gridDims, g.gridParams, g.gridCounts, g.candPartner, g.candCount);
        };
    }

    // ============================================================== reporting
    static boolean reportExactMatch(String label, Snapshot[] snaps) {
        System.out.println("\n--- " + label + ": broad-phase candidate set == brute-force set (exact) ---");
        System.out.printf("  %-8s %-10s %-10s %-10s %-9s %-9s %s%n",
                "step", "candPairs", "brutePairs", "match", "maxCand", "interior", "");
        boolean all = true;
        for (Snapshot s : snaps) {
            boolean ok = s.match && s.interiorOK && s.maxCand < SpatialGrid.MAX_CAND && s.maxBrute < SpatialGrid.MAX_CAND;
            all &= ok;
            System.out.printf("  %-8d %-10d %-10d %-10s %-9d %-9s %s%n",
                    s.step, s.candPairs.length, s.brutePairs.length, s.match ? "EXACT" : "*MISMATCH*",
                    s.maxCand, s.interiorOK ? "ok" : "*EDGE*",
                    (s.maxCand >= SpatialGrid.MAX_CAND || s.maxBrute >= SpatialGrid.MAX_CAND) ? "*OVERFLOW*" : "");
        }
        return all;
    }

    static boolean reportCpuGpuIdentity(Snapshot[] gpu, Snapshot[] cpu) {
        System.out.println("\n--- CPU↔GPU bit-identity (CSR + candidate set) ---");
        System.out.printf("  %-8s %-14s %-14s%n", "step", "CSR", "candidateSet");
        boolean all = true;
        for (int i = 0; i < gpu.length; i++) {
            boolean csr = java.util.Arrays.equals(gpu[i].csrOffsets, cpu[i].csrOffsets)
                    && java.util.Arrays.equals(gpu[i].csrContents, cpu[i].csrContents);
            boolean cand = java.util.Arrays.equals(gpu[i].candPairs, cpu[i].candPairs);
            all &= csr && cand;
            System.out.printf("  %-8d %-14s %-14s%n", gpu[i].step,
                    csr ? "bit-identical" : "*DIFFERS*", cand ? "identical" : "*DIFFERS*");
        }
        return all;
    }

    // ============================================================== scaling
    static void runScaling(double dt) {
        System.out.println("--- scaling: grid O(N) vs brute O(N²) at fixed density (CPU runner, work + timing) ---");
        System.out.printf("  %-7s %-12s %-14s %-14s %-11s %-11s%n",
                "N", "candPairs", "bruteTests", "gridTests", "grid(ms)", "brute(ms)");
        int[] Ns = { 512, 2048 };
        final int TIMED = 30;
        long prevBrute = 0, prevGrid = 0; double prevGridMs = 0, prevBruteMs = 0; int prevN = 0;
        for (int N : Ns) {
            Object[] r = buildRun(N, dt, 0xB0A5EEDL);
            FilamentStore s = (FilamentStore) r[0];
            SpatialBodyView v = (SpatialBodyView) r[1];
            GridScratch g = (GridScratch) r[2];
            Runnable diffuse = cpuDiffuseStep(s);
            Runnable gridBuild = cpuGridBuildStep(s, v, g);
            // one build at the pristine (fixed-density) cluster for the work counts
            s.counts.set(1, 0); diffuse.run(); gridBuild.run();
            long bruteTests = (long) N * (N - 1) / 2;
            long gridTests  = countGridTests(v, g);
            long candPairs  = pairs(g.candPartner, g.candCount, N).length;
            // timing: grid build vs brute pass, TIMED iters each (warm up once)
            SpatialGrid.bruteForce(v.center, v.boundingRadius, g.gridParams, g.gridCounts, g.brutePartner, g.bruteCount);
            long t0 = System.nanoTime();
            for (int k = 0; k < TIMED; k++) gridBuild.run();
            long t1 = System.nanoTime();
            for (int k = 0; k < TIMED; k++)
                SpatialGrid.bruteForce(v.center, v.boundingRadius, g.gridParams, g.gridCounts, g.brutePartner, g.bruteCount);
            long t2 = System.nanoTime();
            double gridMs = (t1 - t0) / 1e6 / TIMED, bruteMs = (t2 - t1) / 1e6 / TIMED;
            System.out.printf("  %-7d %-12d %-14d %-14d %-11.3f %-11.3f%n",
                    N, candPairs, bruteTests, gridTests, gridMs, bruteMs);
            if (prevN > 0) {
                double nR = (double) N / prevN;
                System.out.printf("      ×N=%.0f:  bruteTests ×%.1f (N²→%.1f)  gridTests ×%.1f (N→%.1f)   brute(ms) ×%.1f  grid(ms) ×%.1f%n",
                        nR, (double) bruteTests / prevBrute, nR * nR, (double) gridTests / prevGrid, nR,
                        bruteMs / prevBruteMs, gridMs / prevGridMs);
            }
            prevBrute = bruteTests; prevGrid = gridTests; prevGridMs = gridMs; prevBruteMs = bruteMs; prevN = N;
        }
    }

    /** Count the broad-phase distance tests (the grid's actual pairwise work proxy):
     *  sum over bodies of the occupancy of their 27-cell neighborhood. */
    static long countGridTests(SpatialBodyView v, GridScratch g) {
        int S = v.count;
        int nX = g.gridDims.get(0), nY = g.gridDims.get(1), nZ = g.gridDims.get(2), nXY = nX * nY;
        long tests = 0;
        for (int i = 0; i < S; i++) {
            int c = g.bodyCell.get(i);
            if (c < 0) continue;
            int cz = c / nXY, rem = c - cz * nXY, cy = rem / nX, cx = rem - cy * nX;
            int x0 = Math.max(0, cx - 1), x1 = Math.min(nX - 1, cx + 1);
            int y0 = Math.max(0, cy - 1), y1 = Math.min(nY - 1, cy + 1);
            int z0 = Math.max(0, cz - 1), z1 = Math.min(nZ - 1, cz + 1);
            for (int zz = z0; zz <= z1; zz++)
                for (int yy = y0; yy <= y1; yy++)
                    for (int xx = x0; xx <= x1; xx++) {
                        int cc = xx + yy * nX + zz * nXY;
                        tests += g.gridCellOffsets.get(cc + 1) - g.gridCellOffsets.get(cc);
                    }
        }
        return tests;
    }

    // ============================================================== helpers
    static long[] pairs(IntArray partner, IntArray count, int S) {
        java.util.ArrayList<Long> list = new java.util.ArrayList<>();
        for (int i = 0; i < S; i++) {
            int cnt = count.get(i);
            int n = Math.min(cnt, SpatialGrid.MAX_CAND);
            int base = i * SpatialGrid.MAX_CAND;
            for (int k = 0; k < n; k++) {
                int j = partner.get(base + k);
                if (j > i) list.add(((long) i << 32) | (j & 0xffffffffL));
            }
        }
        long[] arr = new long[list.size()];
        for (int k = 0; k < arr.length; k++) arr[k] = list.get(k);
        java.util.Arrays.sort(arr);
        return arr;
    }

    static int maxCount(IntArray count, int S) {
        int m = 0;
        for (int i = 0; i < S; i++) m = Math.max(m, count.get(i));
        return m;
    }

    static int[] dedup(int[] a) {
        java.util.TreeSet<Integer> set = new java.util.TreeSet<>();
        for (int x : a) set.add(x);
        int[] out = new int[set.size()];
        int i = 0; for (int x : set) out[i++] = x;
        return out;
    }
}
