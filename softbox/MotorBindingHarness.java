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
 * Increment 4a harness: myosin motors as a second entity type + the first narrow-phase
 * consumer of the broad-phase (binding detection + bind/unbind kinetics). NO motion this
 * increment — no power stroke, no surface confinement, no gliding velocity (all 4b).
 * Bound motors apply NO force.
 *
 * Static gliding-assay-like config: a motor bed under a slab of filaments. Filaments
 * diffuse (Brownian, reduced amplitude — v1's bind reach myoColTol = 6 nm is tiny next
 * to a full-amplitude diffusion step, so a stable geometric reachable set needs gentle
 * motion; the off-rate is reach-INDEPENDENT in the faithful mechanism, so it is
 * unaffected). Each "reachable" motor sits at the centre of one filament (conDist ≈ 0,
 * robust under the filament's small rotation); "control" motors sit a z-offset above the
 * plane (conDist ≫ reach ⇒ a negative control that must NEVER be reachable).
 *
 * Per step (one physics, two runners — GPU TaskGraph vs CPU sequential, same methods):
 *   brownian → integrate → derive → fil.publish → motor.publish → grid build →
 *   broadPhase → invertCandidates → computeReachable → bruteReachable → bindKinetics.
 *
 * Gates:
 *   1. Reachable-set EXACTNESS (exact, no tolerance): computeReachable (grid path,
 *      consuming broad-phase candidates filtered by ownerStore) == bruteReachable
 *      (every motor×segment), on BOTH GPU and -cpu.
 *   2. Off-rate STATISTICS (tolerance): empirical per-step release probability
 *      totalReleases/totalBoundSteps == kOff·dt, and mean bond lifetime == 1/(kOff·dt)
 *      steps. Validates the stochastic release machinery + RNG keying.
 *   3. CPU≡GPU: bound-state (boundSeg) + stats bit-identical on both runners.
 */
public final class MotorBindingHarness {

    static final int B = 64;
    static boolean cpu = false;
    static GridScheduler sched;

    // ---- assay config (fixed; gliding-assay-like) ----
    static final int   ROWS = 10;            // filament rows in y
    static final int   COLS = 20;            // filaments per row along x
    static final int   N_CONTROL = 100;      // control motors (z-offset, never reachable)
    static final double ROW_DY = 0.08;       // µm between filament rows (≫ reach: no cross-row)
    static final double MOTOR_LEN = 0.020;   // µm (v1 myoMotorLength)
    static final double CONTROL_Z = 0.040;   // µm above the plane (≫ myoColTol 0.006)
    static final double MYO_COL_TOL = 0.006; // µm (v1 myoColTol, Env.java:755) — bind reach
    static final double ALIGN_TOL = -0.4;    // v1 myoMotorAlignWithFilTolerance (Env.java:149)

    public static void main(String[] args) {
        double dt = Constants.deltaT;
        double brownScale = 0.02;            // reduced Brownian (see class doc)
        int M = 3000;
        String vizDir = null;
        java.util.List<String> pos = new java.util.ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-cpu" -> cpu = true;
                case "-b"   -> brownScale = Double.parseDouble(args[++i]);
                case "-dt"  -> dt = Double.parseDouble(args[++i]);
                case "-3js" -> vizDir = args[++i];
                default     -> pos.add(args[i]);
            }
        }
        if (!pos.isEmpty()) M = Integer.parseInt(pos.get(0));

        System.out.println("=== Soft Box increment 4a — myosin motors + binding detection ===");
        System.out.println("NO force this increment: no power stroke / surface / gliding (all 4b).");
        System.out.printf("config: %d filaments, %d reachable + %d control motors; M=%d, brownScale=%.3g, dt=%.1e%n",
                ROWS * COLS, ROWS * COLS, N_CONTROL, M, brownScale, dt);

        if (vizDir != null) { runViz(M, dt, brownScale, vizDir); return; }

        boolean ok;
        if (cpu) {
            System.out.println("runner: CPU sequential (same system methods, no TaskGraph)");
            Result cpuR = runOnce(M, dt, brownScale, true);
            ok = reportExactness("CPU", cpuR) & reportOffRate("CPU", cpuR, dt);
        } else {
            System.out.println("runner: GPU TaskGraph (TornadoVM PTX)  +  CPU cross-check");
            Result gpu = runOnce(M, dt, brownScale, false);
            Result cpuR = runOnce(M, dt, brownScale, true);
            boolean okG = reportExactness("GPU", gpu) & reportOffRate("GPU", gpu, dt);
            boolean okC = reportExactness("CPU", cpuR) & reportOffRate("CPU", cpuR, dt);
            boolean okI = reportCpuGpuIdentity(gpu, cpuR);
            ok = okG && okC && okI;
        }

        System.out.println();
        System.out.println("=== MOTOR-BINDING VALIDATION " + (ok ? "PASS" : "FAIL") + " ===");
        if (!ok) {
            System.out.println("BAIL-OUT: reachable set != brute force, off-rate off analytic, or CPU!=GPU. "
                    + "Use -cpu to localize (predicate vs kinetics vs RNG). Commit nothing.");
            System.exit(1);
        }
    }

    // ============================================================== config + scratch
    static final class Scene {
        FilamentStore fil;
        MotorStore mot;
        SpatialBodyView view;
        // grid
        FloatArray gridParams; IntArray gridDims, gridCounts; int totalCells, cap;
        FloatArray viewParams;
        IntArray bodyCell, cellCount, chunkSum, gridCellOffsets, gridCellContents;
        IntArray candPartner, candCount;
        // consumer scratch
        IntArray motorCandSeg, motorCandCount;
        IntArray motorReachSeg, motorReachCount;
        IntArray bruteReachSeg, bruteReachCount;
        int nReachable;       // # on-filament (potentially reachable) motors
        double bx, by, bz;    // viewer bounds
    }

    static Scene buildScene(double dt, double brownScale) {
        int nFil = ROWS * COLS;
        int nReach = nFil;                     // one reachable motor per filament (its centre)
        int nMot = nReach + N_CONTROL;
        Scene sc = new Scene();
        sc.nReachable = nReach;

        FilamentStore fil = new FilamentStore(nFil);
        double L = (Constants.stdSegLength + 1) * Constants.actinMonoRadius;   // segment length µm
        double colDx = L;                                                      // contiguous along x
        double x0 = -0.5 * (COLS - 1) * colDx;
        double y0 = -0.5 * (ROWS - 1) * ROW_DY;
        java.util.Random rng = new java.util.Random(0xB0A5EEDL);
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                int f = r * COLS + c;
                fil.monomerCount.set(f, Constants.stdSegLength);
                fil.setUVec(f, 1f, 0f, 0f);                 // along x
                fil.setYVec(f, 0f, 1f, 0f);
                fil.setCoord(f, (float) (x0 + c * colDx), (float) (y0 + r * ROW_DY), 0f);
                fil.brownTransScale.set(f, (float) brownScale);
                fil.brownRotScale.set(f, (float) brownScale);
            }
        }
        DragTensorSystem.run(fil);
        fil.setParams(dt, Math.sqrt(2.0 * Constants.kT / dt));
        fil.setCounts(0, 0xF1A0);             // filament RNG seed (stepCount updated per step)

        MotorStore mot = new MotorStore(nMot);
        // reachable motors: head at each filament centre (conDist≈0, alpha=0.5), axis +z
        // Motor rod tilted toward +x (the filament axis) so the v1 rodDotFil≥0 gate clears
        // with margin (a vertical rod sits EXACTLY on the gate boundary for a horizontal
        // filament — a coin-flip on the filament's tiny z-tilt). normalize(0.3,0,1.0).
        for (int f = 0; f < nFil; f++) {
            float hx = fil.coordX(f), hy = fil.coordY(f), hz = fil.coordZ(f);
            mot.setHead(f, hx, hy, hz);
            mot.setUVec(f, 0f, 0f, 1f);
            mot.setRodUVec(f, 0.287f, 0f, 0.958f);
            mot.setAnchor(f, hx, hy, (float) (hz - MOTOR_LEN));
            mot.reach.set(f, (float) MYO_COL_TOL);
        }
        // control motors: a z-offset above the plane ⇒ conDist ≫ reach ⇒ never reachable
        double cxHalf = 0.5 * (COLS - 1) * colDx, cyHalf = 0.5 * (ROWS - 1) * ROW_DY;
        for (int k = 0; k < N_CONTROL; k++) {
            int m = nFil + k;
            float hx = (float) ((rng.nextDouble() - 0.5) * 2 * cxHalf);
            float hy = (float) ((rng.nextDouble() - 0.5) * 2 * cyHalf);
            mot.setHead(m, hx, hy, (float) CONTROL_Z);
            mot.setUVec(m, 0f, 0f, 1f);
            mot.setRodUVec(m, 0.287f, 0f, 0.958f);
            mot.setAnchor(m, hx, hy, (float) (CONTROL_Z - MOTOR_LEN));
            mot.reach.set(m, (float) MYO_COL_TOL);
        }
        mot.setKinParams(MYO_COL_TOL, ALIGN_TOL, dt);
        mot.setBaseSlot(nFil);                 // motors occupy body slots [nFil, nFil+nMot)

        // body view: filaments [0,nFil) + motors [nFil, nFil+nMot)
        int cap = nFil + nMot;
        SpatialBodyView view = new SpatialBodyView(cap);
        view.count = cap;

        // grid extent: cover the slab + motor bed + diffusion slack
        double segBoundR = 0.5 * L + Constants.radius;
        double cutoff = segBoundR;
        double cellSize = 2.0 * segBoundR + cutoff;
        double gx = cxHalf + 0.4, gy = cyHalf + 0.4, gz = CONTROL_Z + 0.3;
        int nX = 1 + (int) Math.ceil(2 * gx / cellSize);
        int nY = 1 + (int) Math.ceil(2 * gy / cellSize);
        int nZ = 1 + (int) Math.ceil(2 * gz / cellSize);

        sc.fil = fil; sc.mot = mot; sc.view = view;
        sc.cap = cap; sc.totalCells = nX * nY * nZ;
        sc.gridParams = FloatArray.fromElements((float) -gx, (float) -gy, (float) -gz,
                (float) cellSize, (float) (1.0 / cellSize), (float) cutoff);
        sc.gridDims = IntArray.fromElements(nX, nY, nZ, sc.totalCells);
        sc.gridCounts = new IntArray(4); sc.gridCounts.set(1, cap);
        sc.viewParams = FloatArray.fromElements((float) Constants.radius);
        sc.bodyCell = new IntArray(cap); sc.bodyCell.init(-1);
        sc.cellCount = new IntArray(sc.totalCells);
        sc.chunkSum = new IntArray((sc.totalCells + SpatialGrid.GRID_SCAN_CHUNK - 1) / SpatialGrid.GRID_SCAN_CHUNK + 1);
        sc.gridCellOffsets = new IntArray(sc.totalCells + 1);
        sc.gridCellContents = new IntArray(cap); sc.gridCellContents.init(-1);
        int MAXC = SpatialGrid.MAX_CAND;
        sc.candPartner = new IntArray(cap * MAXC); sc.candPartner.init(-1);
        sc.candCount = new IntArray(cap);
        sc.motorCandSeg = new IntArray(nMot * MAXC); sc.motorCandSeg.init(-1);
        sc.motorCandCount = new IntArray(nMot);
        sc.motorReachSeg = new IntArray(nMot * MAXC); sc.motorReachSeg.init(-1);
        sc.motorReachCount = new IntArray(nMot);
        sc.bruteReachSeg = new IntArray(nMot * MAXC); sc.bruteReachSeg.init(-1);
        sc.bruteReachCount = new IntArray(nMot);
        sc.bx = 2 * gx; sc.by = 2 * gy; sc.bz = 2 * gz;
        return sc;
    }

    // ============================================================== run + validate
    static final class ReachSnap {
        int step;
        long[] gridPairs, brutePairs;   // (motor<<32)|seg
        int maxReach, maxBrute, maxCand;
        boolean match, controlClean;    // grid==brute; no control motor reachable
        int reachableMotors;
        int[] boundSeg;                 // copy for CPU≡GPU
        long boundStepsSum, releasesSum;
    }
    static final class Result {
        ReachSnap[] snaps;
        long totalBoundSteps, totalReleases;
        int nReachable, M;
    }

    static Result runOnce(int M, double dt, double brownScale, boolean useCpu) {
        Scene sc = buildScene(dt, brownScale);
        int[] samples = dedup(new int[]{ 0, M / 8, M / 4, M / 2, (3 * M) / 4, Math.max(0, M - 1) });
        java.util.List<ReachSnap> out = new java.util.ArrayList<>();
        int last = samples[samples.length - 1];
        int si = 0;

        if (useCpu) {
            Runnable step = cpuStep(sc);
            for (int t = 0; t <= last; t++) {
                sc.fil.counts.set(1, t); sc.mot.setCounts(t, 0x4D0709, sc.fil.n);
                step.run();
                if (si < samples.length && t == samples[si]) { out.add(snap(t, sc)); si++; }
            }
        } else {
            TornadoExecutionPlan plan = buildPlan(sc);
            for (int t = 0; t <= last; t++) {
                sc.fil.counts.set(1, t); sc.mot.setCounts(t, 0x4D0709, sc.fil.n);
                TornadoExecutionResult res = plan.withGridScheduler(sched).execute();
                if (si < samples.length && t == samples[si]) {
                    res.transferToHost(sc.motorReachSeg, sc.motorReachCount,
                            sc.bruteReachSeg, sc.bruteReachCount,
                            sc.mot.boundSeg, sc.mot.stats, sc.candCount);
                    out.add(snap(t, sc)); si++;
                }
            }
            // last sample is t=M-1, whose transferToHost already pulled the final device stats.
        }

        Result r = new Result();
        r.snaps = out.toArray(new ReachSnap[0]);
        r.nReachable = sc.nReachable; r.M = M;
        long bs = 0, rel = 0;
        for (int m = 0; m < sc.mot.nMotors; m++) { bs += sc.mot.stats.get(2 * m); rel += sc.mot.stats.get(2 * m + 1); }
        r.totalBoundSteps = bs; r.totalReleases = rel;
        return r;
    }

    static ReachSnap snap(int t, Scene sc) {
        ReachSnap s = new ReachSnap();
        s.step = t;
        int nMot = sc.mot.nMotors, MAXC = SpatialGrid.MAX_CAND;
        s.gridPairs = reachPairs(sc.motorReachSeg, sc.motorReachCount, nMot);
        s.brutePairs = reachPairs(sc.bruteReachSeg, sc.bruteReachCount, nMot);
        s.maxReach = maxCount(sc.motorReachCount, nMot);
        s.maxBrute = maxCount(sc.bruteReachCount, nMot);
        s.maxCand = maxCount(sc.candCount, sc.cap);
        s.match = java.util.Arrays.equals(s.gridPairs, s.brutePairs);
        // negative control: no control motor (slot >= nReachable) may be reachable
        boolean clean = true;
        for (int m = sc.nReachable; m < nMot; m++) if (sc.bruteReachCount.get(m) > 0) { clean = false; break; }
        s.controlClean = clean;
        int rm = 0; for (int m = 0; m < sc.nReachable; m++) if (sc.bruteReachCount.get(m) > 0) rm++;
        s.reachableMotors = rm;
        s.boundSeg = new int[nMot];
        for (int m = 0; m < nMot; m++) s.boundSeg[m] = sc.mot.boundSeg.get(m);
        long bsum = 0, rsum = 0;
        for (int m = 0; m < nMot; m++) { bsum += sc.mot.stats.get(2 * m); rsum += sc.mot.stats.get(2 * m + 1); }
        s.boundStepsSum = bsum; s.releasesSum = rsum;
        return s;
    }

    // ============================================================== GPU plan + CPU step
    static TornadoExecutionPlan buildPlan(Scene sc) {
        FilamentStore f = sc.fil; MotorStore m = sc.mot; SpatialBodyView v = sc.view;
        TaskGraph tg = new TaskGraph("motorbind")
            .transferToDevice(DataTransferMode.FIRST_EXECUTION,
                    f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength,
                    f.forceSum, f.torqueSum, f.randForce, f.randTorque,
                    f.bTransGam, f.bRotGam, f.brownTransScale, f.brownRotScale, f.params,
                    m.head, m.uVec, m.rodUVec, m.reach, m.boundSeg, m.bindArc, m.stats,
                    m.kinParams, m.publishParams,
                    v.center, v.boundingRadius, v.ownerStore, v.ownerSlot,
                    sc.gridParams, sc.gridDims, sc.gridCounts, sc.viewParams,
                    sc.bodyCell, sc.cellCount, sc.chunkSum, sc.gridCellOffsets, sc.gridCellContents,
                    sc.candPartner, sc.candCount,
                    sc.motorCandSeg, sc.motorCandCount,
                    sc.motorReachSeg, sc.motorReachCount, sc.bruteReachSeg, sc.bruteReachCount)
            .transferToDevice(DataTransferMode.EVERY_EXECUTION, f.counts, m.counts)
            .task("brownian", BrownianForceSystem::brownianForce,
                    f.randForce, f.randTorque, f.bTransGam, f.bRotGam,
                    f.brownTransScale, f.brownRotScale, f.params, f.counts)
            .task("integrate", RigidRodLangevinIntegrationSystem::integrate,
                    f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum,
                    f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts)
            .task("derive", DerivedGeometrySystem::derive,
                    f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts)
            .task("filPublish", FilamentStore::publishToBodyView,
                    f.coord, f.segLength, v.center, v.boundingRadius, v.ownerStore, v.ownerSlot,
                    sc.viewParams, sc.gridCounts)
            .task("motPublish", MotorStore::publishToBodyView,
                    m.head, m.reach, v.center, v.boundingRadius, v.ownerStore, v.ownerSlot,
                    m.publishParams, m.counts)
            .task("bodyCell", SpatialGrid::bodyCell, v.center, sc.gridParams, sc.gridDims, sc.gridCounts, sc.bodyCell)
            .task("gridZero", SpatialGrid::gridZero, sc.gridDims, sc.cellCount)
            .task("gridHist", SpatialGrid::gridHistogram, sc.bodyCell, sc.gridCounts, sc.cellCount)
            .task("gridScanLocal", SpatialGrid::gridScanLocal, sc.gridDims, sc.cellCount, sc.gridCellOffsets, sc.chunkSum)
            .task("gridScanChunks", SpatialGrid::gridScanChunks, sc.gridDims, sc.chunkSum)
            .task("gridScanAdd", SpatialGrid::gridScanAdd, sc.gridDims, sc.gridCellOffsets, sc.gridCellContents, sc.cellCount, sc.chunkSum)
            .task("gridScatter", SpatialGrid::gridScatter, sc.bodyCell, sc.gridCounts, sc.gridCellOffsets, sc.gridCellContents, sc.cellCount)
            .task("broadPhase", SpatialGrid::broadPhase,
                    v.center, v.boundingRadius, sc.bodyCell, sc.gridCellOffsets, sc.gridCellContents,
                    sc.gridDims, sc.gridParams, sc.gridCounts, sc.candPartner, sc.candCount)
            .task("invert", BindingDetectionSystem::invertCandidates,
                    sc.candPartner, sc.candCount, v.ownerStore, v.ownerSlot,
                    sc.gridCounts, m.counts, sc.motorCandSeg, sc.motorCandCount)
            .task("reachable", BindingDetectionSystem::computeReachable,
                    m.head, m.uVec, m.rodUVec, f.end1, f.end2,
                    sc.motorCandSeg, sc.motorCandCount, sc.motorReachSeg, sc.motorReachCount,
                    m.kinParams, m.counts)
            .task("brute", BindingDetectionSystem::bruteReachable,
                    m.head, m.uVec, m.rodUVec, f.end1, f.end2,
                    sc.bruteReachSeg, sc.bruteReachCount, m.kinParams, m.counts)
            .task("bind", BindingDetectionSystem::bindKinetics,
                    m.head, m.uVec, m.rodUVec, f.end1, f.end2,
                    sc.motorCandSeg, sc.motorCandCount, m.boundSeg, m.bindArc, m.stats,
                    m.kinParams, m.counts)
            .transferToHost(DataTransferMode.UNDER_DEMAND,
                    sc.motorReachSeg, sc.motorReachCount, sc.bruteReachSeg, sc.bruteReachCount,
                    m.boundSeg, m.stats, sc.candCount);

        int cap = sc.cap, nMot = sc.mot.nMotors, totalCells = sc.totalCells;
        int numScanChunks = (totalCells + SpatialGrid.GRID_SCAN_CHUNK - 1) / SpatialGrid.GRID_SCAN_CHUNK;
        sched = new GridScheduler();
        addWorker("motorbind.brownian", pad(sc.fil.n));
        addWorker("motorbind.integrate", pad(sc.fil.n));
        addWorker("motorbind.derive", pad(sc.fil.n));
        addWorker("motorbind.filPublish", pad(sc.fil.n));
        addWorker("motorbind.motPublish", pad(nMot));
        addWorker("motorbind.bodyCell", pad(cap));
        addWorker("motorbind.gridZero", pad(totalCells));
        addWorkerSingle("motorbind.gridHist");
        addWorker("motorbind.gridScanLocal", pad(numScanChunks));
        addWorkerSingle("motorbind.gridScanChunks");
        addWorker("motorbind.gridScanAdd", pad(numScanChunks));
        addWorkerSingle("motorbind.gridScatter");
        addWorker("motorbind.broadPhase", pad(cap));
        addWorkerSingle("motorbind.invert");
        addWorker("motorbind.reachable", pad(nMot));
        addWorker("motorbind.brute", pad(nMot));
        addWorker("motorbind.bind", pad(nMot));
        return new TornadoExecutionPlan(tg.snapshot());
    }

    static int pad(int n) { return ((n + B - 1) / B) * B; }
    static void addWorker(String name, int global) {
        WorkerGrid w = new WorkerGrid1D(global); w.setLocalWork(B, 1, 1); sched.addWorkerGrid(name, w);
    }
    static void addWorkerSingle(String name) {
        WorkerGrid w = new WorkerGrid1D(1); w.setLocalWork(1, 1, 1); sched.addWorkerGrid(name, w);
    }

    /** CPU runner: the SAME system methods in the SAME order, sequential, host-resident. */
    static Runnable cpuStep(Scene sc) {
        FilamentStore f = sc.fil; MotorStore m = sc.mot; SpatialBodyView v = sc.view;
        return () -> {
            BrownianForceSystem.brownianForce(f.randForce, f.randTorque, f.bTransGam, f.bRotGam,
                    f.brownTransScale, f.brownRotScale, f.params, f.counts);
            RigidRodLangevinIntegrationSystem.integrate(f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum,
                    f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts);
            DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
            FilamentStore.publishToBodyView(f.coord, f.segLength, v.center, v.boundingRadius,
                    v.ownerStore, v.ownerSlot, sc.viewParams, sc.gridCounts);
            MotorStore.publishToBodyView(m.head, m.reach, v.center, v.boundingRadius,
                    v.ownerStore, v.ownerSlot, m.publishParams, m.counts);
            SpatialGrid.bodyCell(v.center, sc.gridParams, sc.gridDims, sc.gridCounts, sc.bodyCell);
            SpatialGrid.gridZero(sc.gridDims, sc.cellCount);
            SpatialGrid.gridHistogram(sc.bodyCell, sc.gridCounts, sc.cellCount);
            SpatialGrid.gridScanLocal(sc.gridDims, sc.cellCount, sc.gridCellOffsets, sc.chunkSum);
            SpatialGrid.gridScanChunks(sc.gridDims, sc.chunkSum);
            SpatialGrid.gridScanAdd(sc.gridDims, sc.gridCellOffsets, sc.gridCellContents, sc.cellCount, sc.chunkSum);
            SpatialGrid.gridScatter(sc.bodyCell, sc.gridCounts, sc.gridCellOffsets, sc.gridCellContents, sc.cellCount);
            SpatialGrid.broadPhase(v.center, v.boundingRadius, sc.bodyCell, sc.gridCellOffsets, sc.gridCellContents,
                    sc.gridDims, sc.gridParams, sc.gridCounts, sc.candPartner, sc.candCount);
            BindingDetectionSystem.invertCandidates(sc.candPartner, sc.candCount, v.ownerStore, v.ownerSlot,
                    sc.gridCounts, m.counts, sc.motorCandSeg, sc.motorCandCount);
            BindingDetectionSystem.computeReachable(m.head, m.uVec, m.rodUVec, f.end1, f.end2,
                    sc.motorCandSeg, sc.motorCandCount, sc.motorReachSeg, sc.motorReachCount, m.kinParams, m.counts);
            BindingDetectionSystem.bruteReachable(m.head, m.uVec, m.rodUVec, f.end1, f.end2,
                    sc.bruteReachSeg, sc.bruteReachCount, m.kinParams, m.counts);
            BindingDetectionSystem.bindKinetics(m.head, m.uVec, m.rodUVec, f.end1, f.end2,
                    sc.motorCandSeg, sc.motorCandCount, m.boundSeg, m.bindArc, m.stats, m.kinParams, m.counts);
        };
    }

    // ============================================================== reporting
    static boolean reportExactness(String label, Result r) {
        System.out.println("\n--- " + label + ": reachable set (grid consumer) == brute force (exact) ---");
        System.out.printf("  %-8s %-11s %-11s %-9s %-11s %-9s %-9s%n",
                "step", "gridPairs", "brutePairs", "match", "reachMot", "control", "maxCand");
        boolean all = true;
        for (ReachSnap s : r.snaps) {
            boolean ovf = s.maxReach >= SpatialGrid.MAX_CAND || s.maxBrute >= SpatialGrid.MAX_CAND
                    || s.maxCand >= SpatialGrid.MAX_CAND;
            boolean ok = s.match && s.controlClean && !ovf;
            all &= ok;
            System.out.printf("  %-8d %-11d %-11d %-9s %-11d %-9s %-9d %s%n",
                    s.step, s.gridPairs.length, s.brutePairs.length, s.match ? "EXACT" : "*MISMATCH*",
                    s.reachableMotors, s.controlClean ? "clean" : "*BOUND*", s.maxCand,
                    ovf ? "*OVERFLOW*" : "");
        }
        return all;
    }

    static boolean reportOffRate(String label, Result r, double dt) {
        double kOff = 100.0;
        double pAnalytic = kOff * dt;                       // per-step release prob (zero force)
        double pEmp = r.totalReleases / (double) r.totalBoundSteps;
        double lifeEmp = 1.0 / pEmp, lifeAnalytic = 1.0 / pAnalytic;
        double relErr = Math.abs(pEmp - pAnalytic) / pAnalytic;
        double meanBound = r.totalBoundSteps / (double) r.M;
        double boundFrac = meanBound / r.nReachable;
        double TOL = 0.05;
        boolean ok = relErr <= TOL;
        System.out.println("\n--- " + label + ": off-rate / bound-lifetime statistics (faithful v1 mechanism) ---");
        System.out.printf("  k_off=%.1f /s, dt=%.1e  ⇒  p_off(analytic)=k_off·dt=%.5f, lifetime=%.1f steps%n",
                kOff, dt, pAnalytic, lifeAnalytic);
        System.out.printf("  totalBoundSteps=%d, totalReleases=%d%n", r.totalBoundSteps, r.totalReleases);
        System.out.printf("  p_off(empirical)=%.5f  (rel err %.2f%%, tol %.0f%%)  lifetime=%.1f steps%n",
                pEmp, 100 * relErr, 100 * TOL, lifeEmp);
        System.out.printf("  meanBound=%.1f motors, boundFraction=%.3f of %d reachable  %s%n",
                meanBound, boundFrac, r.nReachable, ok ? "PASS" : "*FAIL*");
        System.out.println("  (note: not k_on/(k_on+k_off) — v1 binds deterministically; bound fraction is");
        System.out.println("   τ_on/(τ_on+τ_off). v1's avgBound≈7.6 needs the 4b power-stroke force, not gated here.)");
        return ok;
    }

    static boolean reportCpuGpuIdentity(Result gpu, Result cpu) {
        System.out.println("\n--- CPU↔GPU bit-identity (bound-state + reachable set + stats) ---");
        System.out.printf("  %-8s %-14s %-14s %-14s%n", "step", "reachableSet", "boundSeg", "stats");
        boolean all = true;
        for (int i = 0; i < gpu.snaps.length; i++) {
            ReachSnap g = gpu.snaps[i], c = cpu.snaps[i];
            boolean rs = java.util.Arrays.equals(g.gridPairs, c.gridPairs);
            boolean bd = java.util.Arrays.equals(g.boundSeg, c.boundSeg);
            boolean st = g.boundStepsSum == c.boundStepsSum && g.releasesSum == c.releasesSum;
            all &= rs && bd && st;
            System.out.printf("  %-8d %-14s %-14s %-14s%n", g.step,
                    rs ? "identical" : "*DIFFERS*", bd ? "identical" : "*DIFFERS*", st ? "identical" : "*DIFFERS*");
        }
        boolean fin = gpu.totalBoundSteps == cpu.totalBoundSteps && gpu.totalReleases == cpu.totalReleases;
        all &= fin;
        System.out.printf("  final totals: boundSteps %d/%d, releases %d/%d  %s%n",
                gpu.totalBoundSteps, cpu.totalBoundSteps, gpu.totalReleases, cpu.totalReleases,
                fin ? "identical" : "*DIFFERS*");
        return all;
    }

    // ============================================================== viewer (-3js)
    static void runViz(int M, double dt, double brownScale, String dir) {
        Scene sc = buildScene(dt, brownScale);
        FrameDump fw = new FrameDump(dir, sc.bx, sc.by, sc.bz);
        Runnable step = cpuStep(sc);
        int frameEvery = Math.max(1, M / 300);
        System.out.println("viewer: CPU runner, dumping ~" + (M / frameEvery) + " frames to " + dir);
        for (int t = 0; t <= M; t++) {
            sc.fil.counts.set(1, t); sc.mot.setCounts(t, 0x4D0709, sc.fil.n);
            if (t % frameEvery == 0) fw.write(sc, t * dt);
            step.run();
        }
        System.out.println("viewer: wrote " + fw.frames + " frames. Serve with sim_server.py + sim_viewer_boa.html.");
    }

    /** Minimal frame writer: segments + bound-motor links (myosins schema). Host-side only. */
    static final class FrameDump {
        final String dir; final double bx, by, bz; int frames = 0;
        FrameDump(String d, double bx, double by, double bz) {
            this.dir = d; this.bx = bx; this.by = by; this.bz = bz;
            new java.io.File(d).mkdirs();
        }
        void write(Scene sc, double t) {
            FilamentStore f = sc.fil; MotorStore m = sc.mot;
            StringBuilder sb = new StringBuilder(256 + 96 * (f.n + m.nMotors));
            sb.append(String.format(java.util.Locale.US, "{\"frame\":%d,\"t\":%.6g", frames, t));
            sb.append(String.format(java.util.Locale.US,
                    ",\"bounds\":{\"xDim\":%.5g,\"yDim\":%.5g,\"zDim\":%.5g}", bx, by, bz));
            sb.append(",\"segments\":[");
            for (int i = 0; i < f.n; i++) {
                if (i > 0) sb.append(',');
                double cx = f.coordX(i), cy = f.coordY(i), cz = f.coordZ(i);
                double ux = f.uVecX(i), uy = f.uVecY(i), uz = f.uVecZ(i);
                double h = f.segLength.get(i) * 0.5;
                sb.append(String.format(java.util.Locale.US,
                        "{\"id\":%d,\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.5g,\"notADPRatio\":1.0,\"cofilinCount\":0}",
                        i, cx - h * ux, cy - h * uy, cz - h * uz, cx + h * ux, cy + h * uy, cz + h * uz, Constants.radius));
            }
            sb.append("],\"myosins\":[");
            for (int mm = 0; mm < m.nMotors; mm++) {
                if (mm > 0) sb.append(',');
                double ax = m.anchorX(mm), ay = m.anchorY(mm), az = m.anchorZ(mm);
                double hx = m.headX(mm), hy = m.headY(mm), hz = m.headZ(mm);
                int bs = m.boundSeg.get(mm);
                double lx = hx, ly = hy, lz = hz;     // rod's far end (the "link")
                String state = "NONE";
                if (bs >= 0) {
                    // link to the bound site on the segment
                    double e1x = f.coordX(bs) - 0.5 * f.segLength.get(bs) * f.uVecX(bs);
                    double e1y = f.coordY(bs) - 0.5 * f.segLength.get(bs) * f.uVecY(bs);
                    double e1z = f.coordZ(bs) - 0.5 * f.segLength.get(bs) * f.uVecZ(bs);
                    double frac = m.bindArc.get(mm) / f.segLength.get(bs);
                    lx = e1x + frac * f.segLength.get(bs) * f.uVecX(bs);
                    ly = e1y + frac * f.segLength.get(bs) * f.uVecY(bs);
                    lz = e1z + frac * f.segLength.get(bs) * f.uVecZ(bs);
                    state = "ADP";   // red when bound
                }
                double mr = 0.004;
                sb.append(String.format(java.util.Locale.US,
                        "{\"id\":%d,\"rod\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":0.0015,\"invisible\":false},"
                        + "\"lever\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":0.0015},"
                        + "\"motor\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.5g,\"state\":\"%s\"}}",
                        mm, ax, ay, az, lx, ly, lz,
                        lx, ly, lz, lx, ly, lz,
                        lx, ly, lz - mr, lx, ly, lz + mr, mr, state));
            }
            sb.append("]}");
            try {
                java.nio.file.Files.writeString(java.nio.file.Path.of(dir,
                        String.format(java.util.Locale.US, "frame_%06d.json", frames)), sb.toString());
            } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
            frames++;
        }
    }

    // ============================================================== helpers
    static long[] reachPairs(IntArray seg, IntArray count, int nMot) {
        java.util.ArrayList<Long> list = new java.util.ArrayList<>();
        for (int mtr = 0; mtr < nMot; mtr++) {
            int cnt = Math.min(count.get(mtr), SpatialGrid.MAX_CAND);
            int base = mtr * SpatialGrid.MAX_CAND;
            for (int k = 0; k < cnt; k++) list.add(((long) mtr << 32) | (seg.get(base + k) & 0xffffffffL));
        }
        long[] arr = new long[list.size()];
        for (int k = 0; k < arr.length; k++) arr[k] = list.get(k);
        java.util.Arrays.sort(arr);
        return arr;
    }
    static int maxCount(IntArray count, int n) {
        int mx = 0; for (int i = 0; i < n; i++) mx = Math.max(mx, count.get(i)); return mx;
    }
    static int[] dedup(int[] a) {
        java.util.TreeSet<Integer> set = new java.util.TreeSet<>();
        for (int x : a) set.add(x);
        int[] out = new int[set.size()]; int i = 0; for (int x : set) out[i++] = x; return out;
    }
}
