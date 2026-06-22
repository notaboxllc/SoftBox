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
 * FAITHFUL dense gliding-assay COMPUTE benchmark — the directly-matching harness for BoA's
 * BENCHMARK_dense.md weak-scaling sweep (1× ≈ 98 000 motors / 400 filaments in a 14×14×0.5 µm bed,
 * density 500 motors/µm², box schedule boxXY = 14·√scale). Unlike {@link GlidingHarness} (a single
 * filament + a brute-reachable binding probe), this places 400·scale filaments over the bed and uses
 * the inc-3 device-resident GRID broad-phase + the inc-4a binding consumer (NOT O(motors×segments)
 * brute) so it scales to the full multi-filament bed. Everything downstream of the reachable set is
 * the validated {@link GlidingHarness} gliding force chain (release/bind/cycle → joints/anchor/
 * cross-bridge → integrate → chain → CSR gather → integrate).
 *
 * One physics, two runners (GPU TaskGraph vs CPU sequential, identical system methods). The merge is
 * minimal: the grid pipeline (publishers → grid build → broadPhase → invertCandidates →
 * computeReachable) fills the SAME reachSeg/reachCount that {@code bindNearest} consumes; the binding
 * physics is the validated inc-4a path (grid == brute, gated below).
 *
 * Modes:
 *   -gridcheck         small-scale CPU gate: computeReachable (grid) == bruteReachable (every pair), exact.
 *   -scale N [-cpu] M  timing probe: warmup-windowed ms/step + steps/s, avgBound, max candidate count.
 *
 * NOT a velocity/biology validation — a COMPUTE benchmark. Faithful in: motor count (density × bed
 * area), filament count (400·scale), the full per-step binding+force+integrate workload, and the
 * device-resident no-per-step-host-pull residency model. Filaments are oriented +x (maximally
 * bindable ⇒ a CONSERVATIVE, heavier cross-bridge load than BoA's randomly-oriented bed).
 */
public final class DenseGlidingHarness {

    static final int    B          = 64;
    static final double DENSITY    = 500.0;   // motors / µm²   (BoA dense-gliding density)
    static final int    FIL_PER_1X = 400;     // filaments at scale 1× (BoA dense-gliding)
    static final int    FIL_SEGS   = 6;       // ~1.05 µm filament (≈ BoA mean 0.2–1.5 µm draw)
    static final int    FIL_MONO   = 64;      // monomers/segment (gliding override)
    static final double DT         = 1e-5;    // gliding dt (GlidingHarness)
    static final double ANCHOR_Z   = -0.05;   // fixedMyosinZValue
    static final double MYO_COL_TOL = 0.006;  // v1 myoColTol — bind reach
    static final double ALIGN_TOL   = -0.4;   // v1 myoMotorAlignWithFilTolerance
    static int SEED = 0x6111D;
    static boolean PROF = false;   // -prof: per-task kernel-time breakdown (diagnostic)
    static boolean SERIALGRID = false;  // -serialgrid: old single-threaded grid build (A/B regression + speedup measurement)
    static boolean OLDBIND = false;     // -oldbind: old broadPhase+invertCandidates+computeReachable (the single-threaded invert; A/B)
    static boolean SERIALCSR = false;   // -serialcsr: old single-threaded csrHistogram+csrScatter (A/B)
    static boolean BRUTE = false;   // -brute: GPU-parallel brute reachable instead of the grid (bottleneck diagnostic; GPU path only)
    static GridScheduler sched;

    public static void main(String[] args) {
        double scale = 1.0;
        int M = 600;
        boolean cpu = false, gridcheck = false;
        java.util.List<String> pos = new java.util.ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-scale"     -> scale = Double.parseDouble(args[++i]);
                case "-cpu"       -> cpu = true;
                case "-gridcheck" -> gridcheck = true;
                case "-brute"     -> BRUTE = true;
                case "-prof"      -> PROF = true;
                case "-serialgrid" -> SERIALGRID = true;
                case "-oldbind"    -> OLDBIND = true;
                case "-serialcsr"  -> SERIALCSR = true;
                case "-seed"      -> SEED = 0x6111D + 7919 * Integer.parseInt(args[++i]);
                default           -> pos.add(args[i]);
            }
        }
        if (!pos.isEmpty()) M = Integer.parseInt(pos.get(0));

        System.out.println("=== Soft Box — FAITHFUL dense gliding COMPUTE benchmark (grid broad-phase) ===");
        if (gridcheck) { gridCheck(); return; }

        Scene sc = buildScene(scale, false);
        System.out.printf("config: scale=%.3g  box %.2f×%.2f×0.5 µm  filaments=%d (%d segs)  motors=%d  bodies(view)=%d  dt=%.0e%n",
                scale, sc.side, sc.side, sc.nFil, sc.fil.n, sc.mot.nMotors, sc.cap, DT);
        if (cpu) cpuProbe(sc, M);
        else     gpuProbe(sc, M);
    }

    // ============================================================== scene
    static final class Scene {
        FilamentStore fil; MotorStore mot; SpatialBodyView view;
        FloatArray bondData, xbParams;
        IntArray segMotorCount, segMotorOffsets, segMotorMyo;
        IntArray csrChunkParams, csrMatrix; int numMotorChunks;   // parallel CSR-inverse
        // grid
        FloatArray gridParams, viewParams; IntArray gridDims, gridCounts;
        IntArray bodyCell, cellCount, chunkSum, gridCellOffsets, gridCellContents;
        IntArray chunkParams, chunkCellCount; int numBodyChunks;   // parallel counting-sort build
        IntArray candPartner, candCount, motorCandSeg, motorCandCount, reachSeg, reachCount;
        IntArray bruteReachSeg, bruteReachCount;   // gridcheck only
        int cap, totalCells, nFil; double side;
    }

    static Scene buildScene(double scale, boolean allocBrute) {
        Scene sc = new Scene();
        double side = 14.0 * Math.sqrt(scale);                 // BoA box schedule boxXY = 14·√scale
        int nFil = (int) Math.round(FIL_PER_1X * scale);
        int nMot = (int) Math.round(DENSITY * side * side);    // density × bed area
        int nSeg = nFil * FIL_SEGS;
        double L = (FIL_MONO + 1) * Constants.actinMonoRadius; // µm per segment
        sc.side = side; sc.nFil = nFil;

        // ---- filaments: nFil rods of FIL_SEGS segments, random center in the bed, oriented +x ----
        FilamentStore fil = new FilamentStore(nSeg);
        java.util.Random rng = new java.util.Random(SEED);
        for (int p = 0; p < nFil; p++) {
            double cx = (rng.nextDouble() - 0.5) * side;
            double cy = (rng.nextDouble() - 0.5) * side;
            int g0 = p * FIL_SEGS;
            for (int s = 0; s < FIL_SEGS; s++) {
                int g = g0 + s;
                fil.monomerCount.set(g, FIL_MONO);
                fil.setUVec(g, 1f, 0f, 0f); fil.setYVec(g, 0f, 1f, 0f);
                fil.setCoord(g, (float) (cx + (s - 0.5 * (FIL_SEGS - 1)) * L), (float) cy, 0f);
                fil.brownTransScale.set(g, (float) Constants.BTransCoeff);
                boolean end = (s == 0 || s == FIL_SEGS - 1);
                fil.brownRotScale.set(g, (float) (end ? Constants.BRotCoeff : 0.0));
                if (s < FIL_SEGS - 1) { fil.end2NbrSlot.set(g, g + 1); fil.end2NbrSide.set(g, 0); }
                if (s > 0)            { fil.end1NbrSlot.set(g, g - 1); fil.end1NbrSide.set(g, 1); }
            }
        }
        DragTensorSystem.run(fil);
        fil.setParams(DT, Math.sqrt(2.0 * Constants.kT / DT));
        fil.setCounts(0, 0xF11A);
        fil.chainParams.set(0, (float) DT); fil.chainParams.set(1, 0.5f); fil.chainParams.set(2, 0.1f);
        fil.chainParams.set(3, 0.2f); fil.chainParams.set(4, 0f); fil.chainParams.set(5, 1.0e-20f);
        fil.chainParams.set(6, (float) Constants.actinMonoRadius);
        DerivedGeometrySystem.derive(fil.coord, fil.uVec, fil.yVec, fil.zVec, fil.end1, fil.end2, fil.segLength, fil.counts);

        // ---- motors: articulated bed, random (x,y) in the bed, anchored at z=ANCHOR_Z ----
        MotorStore mot = new MotorStore(nMot);
        for (int m = 0; m < nMot; m++) {
            float ax = (float) ((rng.nextDouble() - 0.5) * side);
            float ay = (float) ((rng.nextDouble() - 0.5) * side);
            mot.assembleArticulated(m, ax, ay, (float) ANCHOR_Z, 0f, 0f, 1f, (float) Constants.BTransCoeff);
            mot.reach.set(m, (float) MYO_COL_TOL);
        }
        DragTensorSystem.run(mot);
        mot.setBodyParams(DT); mot.setJointParams(DT); mot.setKinParams(MYO_COL_TOL, ALIGN_TOL, DT); mot.setNucParams(DT);
        mot.nucleotideState.init(MotorStore.NUC_NONE);
        mot.setBaseSlot(nSeg);                                 // motors at view slots [nSeg, nSeg+nMot)

        // ---- cross-bridge scratch ----
        int MAXC = SpatialGrid.MAX_CAND;
        sc.bondData = new FloatArray(nMot * CrossBridgeSystem.STRIDE); sc.bondData.init(0f);
        sc.xbParams = FloatArray.fromElements((float) 1.0e-9, 90f, 0.4f, (float) DT, (float) MotorStore.HEAD_LEN, 0f);
        sc.segMotorCount = new IntArray(nSeg); sc.segMotorOffsets = new IntArray(nSeg + 1); sc.segMotorMyo = new IntArray(nMot);
        int mcs = SpatialGrid.bodyChunkSize(nMot, nSeg);
        sc.numMotorChunks = SpatialGrid.numBodyChunks(nMot, mcs);
        sc.csrChunkParams = IntArray.fromElements(mcs, sc.numMotorChunks);
        sc.csrMatrix = new IntArray(sc.numMotorChunks * nSeg); sc.csrMatrix.init(0);

        // ---- grid + body view (inc-3) ----
        int cap = nSeg + nMot;
        SpatialBodyView view = new SpatialBodyView(cap); view.count = cap;
        double segBoundR = 0.5 * L + Constants.radius;
        double cutoff = segBoundR;
        double cellSize = 2.0 * segBoundR + cutoff;
        double gx = 0.5 * side + 1.0, gy = gx, gz = 0.4;       // bed half-extent + drift slack; thin z slab
        int nX = 1 + (int) Math.ceil(2 * gx / cellSize);
        int nY = 1 + (int) Math.ceil(2 * gy / cellSize);
        int nZ = 1 + (int) Math.ceil(2 * gz / cellSize);
        sc.totalCells = nX * nY * nZ;
        sc.gridParams = FloatArray.fromElements((float) -gx, (float) -gy, (float) -gz,
                (float) cellSize, (float) (1.0 / cellSize), (float) cutoff);
        sc.gridDims = IntArray.fromElements(nX, nY, nZ, sc.totalCells);
        sc.gridCounts = new IntArray(4); sc.gridCounts.set(1, cap);
        sc.viewParams = FloatArray.fromElements((float) Constants.radius);
        sc.bodyCell = new IntArray(cap); sc.bodyCell.init(-1);
        sc.cellCount = new IntArray(sc.totalCells);
        sc.chunkSum = new IntArray((sc.totalCells + SpatialGrid.GRID_SCAN_CHUNK - 1) / SpatialGrid.GRID_SCAN_CHUNK + 1);
        int bcs = SpatialGrid.bodyChunkSize(cap, sc.totalCells);
        sc.numBodyChunks = SpatialGrid.numBodyChunks(cap, bcs);
        sc.chunkParams = IntArray.fromElements(bcs, sc.numBodyChunks);
        sc.chunkCellCount = new IntArray(sc.numBodyChunks * sc.totalCells); sc.chunkCellCount.init(0);
        sc.gridCellOffsets = new IntArray(sc.totalCells + 1);
        sc.gridCellContents = new IntArray(cap); sc.gridCellContents.init(-1);
        sc.candPartner = new IntArray(cap * MAXC); sc.candPartner.init(-1);
        sc.candCount = new IntArray(cap);
        sc.motorCandSeg = new IntArray(nMot * MAXC); sc.motorCandSeg.init(-1);
        sc.motorCandCount = new IntArray(nMot);
        sc.reachSeg = new IntArray(nMot * MAXC); sc.reachSeg.init(-1);
        sc.reachCount = new IntArray(nMot);
        if (allocBrute) {
            sc.bruteReachSeg = new IntArray(nMot * MAXC); sc.bruteReachSeg.init(-1);
            sc.bruteReachCount = new IntArray(nMot);
        }
        sc.fil = fil; sc.mot = mot; sc.view = view; sc.cap = cap;
        return sc;
    }

    // ============================================================== GPU plan (merged grid-bind + gliding force chain)
    static TornadoExecutionPlan buildPlan(Scene sc) {
        FilamentStore f = sc.fil; MotorStore mot = sc.mot; RigidRodBody b = mot.body; SpatialBodyView v = sc.view;
        TaskGraph tg = new TaskGraph("densegliding")
            .transferToDevice(DataTransferMode.FIRST_EXECUTION,
                    b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, b.bTransGam, b.bRotGam,
                    b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.brownTransScale, b.brownRotScale,
                    mot.head, mot.uVec, mot.rodUVec, mot.anchor, mot.boundSeg, mot.bindArc, mot.nucleotideState, mot.reach,
                    mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.stats, mot.capStats, mot.cooldown,
                    mot.bodyParams, mot.jointParams, mot.nucParams, mot.kinParams, mot.publishParams,
                    sc.bondData, sc.xbParams, sc.segMotorCount, sc.segMotorOffsets, sc.segMotorMyo,
                    sc.csrChunkParams, sc.csrMatrix,
                    f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.bTransGam, f.bRotGam,
                    f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.brownTransScale, f.brownRotScale,
                    f.params, f.chainParams, f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide,
                    v.center, v.boundingRadius, v.ownerStore, v.ownerSlot,
                    sc.gridParams, sc.gridDims, sc.gridCounts, sc.viewParams,
                    sc.bodyCell, sc.cellCount, sc.chunkSum, sc.gridCellOffsets, sc.gridCellContents,
                    sc.chunkParams, sc.chunkCellCount,
                    sc.candPartner, sc.candCount, sc.motorCandSeg, sc.motorCandCount, sc.reachSeg, sc.reachCount)
            .transferToDevice(DataTransferMode.EVERY_EXECUTION, mot.counts, f.counts)
            // --- publish + grid build + broad-phase + binding reach (replaces brute reachable) ---
            .task("publishHead", MotorStore::publishHeadFromBody, b.coord, b.uVec, b.segLength, mot.head, mot.uVec, mot.rodUVec, mot.counts);
        if (BRUTE) {   // GPU-parallel brute reachable (diagnostic: isolates the single-threaded grid-build cost)
            tg = tg.task("brute", BindingDetectionSystem::bruteReachable, mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, sc.reachSeg, sc.reachCount, mot.kinParams, mot.counts);
        } else {
            tg = tg
            .task("filPublish", FilamentStore::publishToBodyView, f.coord, f.segLength, v.center, v.boundingRadius, v.ownerStore, v.ownerSlot, sc.viewParams, sc.gridCounts)
            .task("motPublish", MotorStore::publishToBodyView, mot.head, mot.reach, v.center, v.boundingRadius, v.ownerStore, v.ownerSlot, mot.publishParams, mot.counts)
            .task("bodyCell", SpatialGrid::bodyCell, v.center, sc.gridParams, sc.gridDims, sc.gridCounts, sc.bodyCell);
        if (SERIALGRID) {   // A/B: the old single-threaded inc-3 build (regression + speedup baseline)
            tg = tg
            .task("gridZero", SpatialGrid::gridZero, sc.gridDims, sc.cellCount)
            .task("gridHist", SpatialGrid::gridHistogram, sc.bodyCell, sc.gridCounts, sc.cellCount)
            .task("gridScanLocal", SpatialGrid::gridScanLocal, sc.gridDims, sc.cellCount, sc.gridCellOffsets, sc.chunkSum)
            .task("gridScanChunks", SpatialGrid::gridScanChunks, sc.gridDims, sc.chunkSum)
            .task("gridScanAdd", SpatialGrid::gridScanAdd, sc.gridDims, sc.gridCellOffsets, sc.gridCellContents, sc.cellCount, sc.chunkSum)
            .task("gridScatter", SpatialGrid::gridScatter, sc.bodyCell, sc.gridCounts, sc.gridCellOffsets, sc.gridCellContents, sc.cellCount);
        } else {
            tg = tg
            .task("chunkZero", SpatialGrid::gridChunkZero, sc.chunkParams, sc.gridDims, sc.chunkCellCount)
            .task("chunkHist", SpatialGrid::gridChunkHistogram, sc.bodyCell, sc.gridCounts, sc.chunkParams, sc.gridDims, sc.chunkCellCount)
            .task("chunkReduce", SpatialGrid::gridChunkReduce, sc.gridDims, sc.chunkParams, sc.chunkCellCount, sc.cellCount)
            .task("gridScanLocal", SpatialGrid::gridScanLocal, sc.gridDims, sc.cellCount, sc.gridCellOffsets, sc.chunkSum)
            .task("gridScanChunks", SpatialGrid::gridScanChunks, sc.gridDims, sc.chunkSum)
            .task("gridScanAdd", SpatialGrid::gridScanAdd, sc.gridDims, sc.gridCellOffsets, sc.gridCellContents, sc.cellCount, sc.chunkSum)
            .task("chunkScatter", SpatialGrid::gridChunkScatter, sc.bodyCell, sc.gridCounts, sc.chunkParams, sc.gridDims, sc.gridCellOffsets, sc.gridCellContents, sc.chunkCellCount);
        }
        if (OLDBIND) {   // A/B: old broadPhase + single-threaded invertCandidates + computeReachable
            tg = tg
            .task("broadPhase", SpatialGrid::broadPhase, v.center, v.boundingRadius, sc.bodyCell, sc.gridCellOffsets, sc.gridCellContents, sc.gridDims, sc.gridParams, sc.gridCounts, sc.candPartner, sc.candCount)
            .task("invert", BindingDetectionSystem::invertCandidates, sc.candPartner, sc.candCount, v.ownerStore, v.ownerSlot, sc.gridCounts, mot.counts, sc.motorCandSeg, sc.motorCandCount)
            .task("reachable", BindingDetectionSystem::computeReachable, mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, sc.motorCandSeg, sc.motorCandCount, sc.reachSeg, sc.reachCount, mot.kinParams, mot.counts);
        } else {         // FUSED per-motor grid query (parallel; retires the single-threaded invert)
            tg = tg
            .task("gridReachable", BindingDetectionSystem::gridReachable, mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2,
                    sc.gridParams, sc.gridDims, sc.gridCellOffsets, sc.gridCellContents, v.ownerStore, v.ownerSlot,
                    sc.reachSeg, sc.reachCount, mot.kinParams, mot.counts);
        }
        }
        tg = tg
            // --- gliding force chain (GlidingHarness default order; bindNearest consumes reachSeg/reachCount) ---
            .task("release", NucleotideCycleSystem::catchSlipRelease, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.cooldown, mot.stats, mot.capStats, mot.kinParams, mot.counts)
            .task("bind", BindingDetectionSystem::bindNearest, mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, sc.reachSeg, sc.reachCount, mot.boundSeg, mot.bindArc, mot.kinParams, mot.counts)
            .task("cycle", NucleotideCycleSystem::cycle, mot.nucleotideState, mot.boundSeg, mot.forceDotHist, mot.nucParams, mot.counts)
            .task("zeroMot", ChainBendingForceSystem::zeroAccumulators, b.forceSum, b.torqueSum, mot.counts)
            .task("brownMot", BrownianForceSystem::brownianForce, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, b.brownTransScale, b.brownRotScale, mot.bodyParams, mot.counts)
            .task("joints", MotorJointSystem::joints, b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, mot.nucleotideState, mot.jointParams, mot.counts)
            .task("anchor", TailAnchorSystem::anchor, b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, mot.anchor, mot.jointParams, mot.counts)
            .task("bond", CrossBridgeSystem::bondForces, b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength, mot.boundSeg, mot.bindArc, mot.nucleotideState, sc.bondData, sc.xbParams)
            .task("applyHead", CrossBridgeSystem::applyHeadForce, sc.bondData, b.forceSum, b.torqueSum, mot.counts)
            .task("integMot", RigidRodLangevinIntegrationSystem::integrate, b.coord, b.uVec, b.yVec, b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, mot.bodyParams, mot.counts)
            .task("deriveMot", DerivedGeometrySystem::derive, b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts)
            .task("register", CrossBridgeSystem::registerForceDot, sc.bondData, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.counts)
            .task("zeroFil", ChainBendingForceSystem::zeroAccumulators, f.forceSum, f.torqueSum, f.counts)
            .task("brownFil", BrownianForceSystem::brownianForce, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.brownTransScale, f.brownRotScale, f.params, f.counts)
            .task("chain", ChainBendingForceSystem::chainForces, f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide, f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts);
        if (SERIALCSR) {   // A/B: old single-threaded CSR-inverse
            tg = tg
            .task("csrHist", CrossBridgeSystem::csrHistogram, mot.boundSeg, mot.counts, sc.segMotorCount)
            .task("csrScan", CrossBridgeSystem::csrScan, mot.counts, sc.segMotorCount, sc.segMotorOffsets)
            .task("csrScatter", CrossBridgeSystem::csrScatter, mot.boundSeg, mot.counts, sc.segMotorOffsets, sc.segMotorCount, sc.segMotorMyo);
        } else {           // parallel counting-sort CSR-inverse (retires the single-threaded csr*)
            tg = tg
            .task("csrChunkZero", CrossBridgeSystem::csrChunkZero, sc.csrChunkParams, mot.counts, sc.csrMatrix)
            .task("csrChunkHist", CrossBridgeSystem::csrChunkHistogram, mot.boundSeg, mot.counts, sc.csrChunkParams, sc.csrMatrix)
            .task("csrChunkReduce", CrossBridgeSystem::csrChunkReduce, mot.counts, sc.csrChunkParams, sc.csrMatrix, sc.segMotorCount)
            .task("csrScan", CrossBridgeSystem::csrScan, mot.counts, sc.segMotorCount, sc.segMotorOffsets)
            .task("csrChunkScatter", CrossBridgeSystem::csrChunkScatter, mot.boundSeg, mot.counts, sc.csrChunkParams, sc.segMotorOffsets, sc.segMotorMyo, sc.csrMatrix);
        }
        tg = tg
            .task("gather", CrossBridgeSystem::segGather, sc.segMotorOffsets, sc.segMotorMyo, sc.bondData, f.forceSum, f.torqueSum, mot.counts)
            .task("integFil", RigidRodLangevinIntegrationSystem::integrate, f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts)
            .task("deriveFil", DerivedGeometrySystem::derive, f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
        // candCount is only written by broadPhase (OLDBIND); transferring an untouched buffer out
        // fails with "invalid variable" at streamOut (the journal's broken-`-brute` symptom).
        if (OLDBIND) tg = tg.transferToHost(DataTransferMode.UNDER_DEMAND, f.coord, mot.boundSeg, sc.candCount, sc.reachCount);
        else         tg = tg.transferToHost(DataTransferMode.UNDER_DEMAND, f.coord, mot.boundSeg, sc.reachCount);

        int nB = 3 * mot.nMotors, nM = mot.nMotors, nSeg = f.n, cap = sc.cap, totalCells = sc.totalCells;
        int numScanChunks = (totalCells + SpatialGrid.GRID_SCAN_CHUNK - 1) / SpatialGrid.GRID_SCAN_CHUNK;
        sched = new GridScheduler();
        for (String t : new String[]{ "publishHead","motPublish","release","bind","cycle","anchor","bond","applyHead","register","reachable","invert","gridReachable" }) addW("densegliding." + t, pad(nM));
        for (String t : new String[]{ "zeroMot","brownMot","joints","integMot","deriveMot" }) addW("densegliding." + t, pad(nB));
        for (String t : new String[]{ "filPublish","zeroFil","brownFil","chain","gather","integFil","deriveFil" }) addW("densegliding." + t, pad(nSeg));
        addW("densegliding.bodyCell", pad(cap));
        addW("densegliding.broadPhase", pad(cap));
        if (SERIALGRID) {
            addW("densegliding.gridZero", pad(totalCells));
            for (String t : new String[]{ "gridHist","gridScatter" }) addS("densegliding." + t);
        } else {
            addW("densegliding.chunkZero",   pad(sc.numBodyChunks * totalCells));
            addW("densegliding.chunkHist",   pad(sc.numBodyChunks));
            addW("densegliding.chunkReduce", pad(totalCells));
            addW("densegliding.chunkScatter", pad(sc.numBodyChunks));
        }
        addW("densegliding.gridScanLocal", pad(numScanChunks));
        addW("densegliding.gridScanAdd", pad(numScanChunks));
        if (SERIALCSR) {
            for (String t : new String[]{ "csrHist","csrScatter" }) addS("densegliding." + t);
        } else {
            addW("densegliding.csrChunkZero",  pad(sc.numMotorChunks * nSeg));
            addW("densegliding.csrChunkHist",  pad(sc.numMotorChunks));
            addW("densegliding.csrChunkReduce", pad(nSeg));
            addW("densegliding.csrChunkScatter", pad(sc.numMotorChunks));
        }
        for (String t : new String[]{ "gridScanChunks","csrScan" }) addS("densegliding." + t);
        if (BRUTE) addW("densegliding.brute", pad(nM));
        return new TornadoExecutionPlan(tg.snapshot());
    }

    static int pad(int n) { return ((n + B - 1) / B) * B; }
    static void addW(String n, int g) { WorkerGrid w = new WorkerGrid1D(Math.max(B, g)); w.setLocalWork(B, 1, 1); sched.addWorkerGrid(n, w); }
    static void addS(String n) { WorkerGrid w = new WorkerGrid1D(1); w.setLocalWork(1, 1, 1); sched.addWorkerGrid(n, w); }

    /** Binding-detection prefix (publish → grid build → broad-phase → invert → computeReachable),
     *  on the CURRENT positions, no forces/integrate. Used by cpuStep and the grid==brute gate. */
    static void reachStep(Scene sc, int t) {
        FilamentStore f = sc.fil; MotorStore mot = sc.mot; RigidRodBody b = mot.body; SpatialBodyView v = sc.view;
        mot.setCounts(t, SEED, f.n); f.counts.set(1, t);
        MotorStore.publishHeadFromBody(b.coord, b.uVec, b.segLength, mot.head, mot.uVec, mot.rodUVec, mot.counts);
        FilamentStore.publishToBodyView(f.coord, f.segLength, v.center, v.boundingRadius, v.ownerStore, v.ownerSlot, sc.viewParams, sc.gridCounts);
        MotorStore.publishToBodyView(mot.head, mot.reach, v.center, v.boundingRadius, v.ownerStore, v.ownerSlot, mot.publishParams, mot.counts);
        SpatialGrid.bodyCell(v.center, sc.gridParams, sc.gridDims, sc.gridCounts, sc.bodyCell);
        SpatialGrid.gridChunkZero(sc.chunkParams, sc.gridDims, sc.chunkCellCount);
        SpatialGrid.gridChunkHistogram(sc.bodyCell, sc.gridCounts, sc.chunkParams, sc.gridDims, sc.chunkCellCount);
        SpatialGrid.gridChunkReduce(sc.gridDims, sc.chunkParams, sc.chunkCellCount, sc.cellCount);
        SpatialGrid.gridScanLocal(sc.gridDims, sc.cellCount, sc.gridCellOffsets, sc.chunkSum);
        SpatialGrid.gridScanChunks(sc.gridDims, sc.chunkSum);
        SpatialGrid.gridScanAdd(sc.gridDims, sc.gridCellOffsets, sc.gridCellContents, sc.cellCount, sc.chunkSum);
        SpatialGrid.gridChunkScatter(sc.bodyCell, sc.gridCounts, sc.chunkParams, sc.gridDims, sc.gridCellOffsets, sc.gridCellContents, sc.chunkCellCount);
        if (OLDBIND) {
            SpatialGrid.broadPhase(v.center, v.boundingRadius, sc.bodyCell, sc.gridCellOffsets, sc.gridCellContents, sc.gridDims, sc.gridParams, sc.gridCounts, sc.candPartner, sc.candCount);
            BindingDetectionSystem.invertCandidates(sc.candPartner, sc.candCount, v.ownerStore, v.ownerSlot, sc.gridCounts, mot.counts, sc.motorCandSeg, sc.motorCandCount);
            BindingDetectionSystem.computeReachable(mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, sc.motorCandSeg, sc.motorCandCount, sc.reachSeg, sc.reachCount, mot.kinParams, mot.counts);
        } else {
            BindingDetectionSystem.gridReachable(mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2,
                    sc.gridParams, sc.gridDims, sc.gridCellOffsets, sc.gridCellContents, v.ownerStore, v.ownerSlot,
                    sc.reachSeg, sc.reachCount, mot.kinParams, mot.counts);
        }
    }

    /** CPU runner — the SAME system methods in the SAME order, sequential. */
    static void cpuStep(Scene sc, int t) {
        FilamentStore f = sc.fil; MotorStore mot = sc.mot; RigidRodBody b = mot.body; SpatialBodyView v = sc.view;
        reachStep(sc, t);
        NucleotideCycleSystem.catchSlipRelease(mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.cooldown, mot.stats, mot.capStats, mot.kinParams, mot.counts);
        BindingDetectionSystem.bindNearest(mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, sc.reachSeg, sc.reachCount, mot.boundSeg, mot.bindArc, mot.kinParams, mot.counts);
        NucleotideCycleSystem.cycle(mot.nucleotideState, mot.boundSeg, mot.forceDotHist, mot.nucParams, mot.counts);
        ChainBendingForceSystem.zeroAccumulators(b.forceSum, b.torqueSum, mot.counts);
        BrownianForceSystem.brownianForce(b.randForce, b.randTorque, b.bTransGam, b.bRotGam, b.brownTransScale, b.brownRotScale, mot.bodyParams, mot.counts);
        MotorJointSystem.joints(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, mot.nucleotideState, mot.jointParams, mot.counts);
        TailAnchorSystem.anchor(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, mot.anchor, mot.jointParams, mot.counts);
        CrossBridgeSystem.bondForces(b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength, mot.boundSeg, mot.bindArc, mot.nucleotideState, sc.bondData, sc.xbParams);
        CrossBridgeSystem.applyHeadForce(sc.bondData, b.forceSum, b.torqueSum, mot.counts);
        RigidRodLangevinIntegrationSystem.integrate(b.coord, b.uVec, b.yVec, b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, mot.bodyParams, mot.counts);
        DerivedGeometrySystem.derive(b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts);
        CrossBridgeSystem.registerForceDot(sc.bondData, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.counts);
        ChainBendingForceSystem.zeroAccumulators(f.forceSum, f.torqueSum, f.counts);
        BrownianForceSystem.brownianForce(f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.brownTransScale, f.brownRotScale, f.params, f.counts);
        ChainBendingForceSystem.chainForces(f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide, f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts);
        if (SERIALCSR) {
            CrossBridgeSystem.csrHistogram(mot.boundSeg, mot.counts, sc.segMotorCount);
            CrossBridgeSystem.csrScan(mot.counts, sc.segMotorCount, sc.segMotorOffsets);
            CrossBridgeSystem.csrScatter(mot.boundSeg, mot.counts, sc.segMotorOffsets, sc.segMotorCount, sc.segMotorMyo);
        } else {
            CrossBridgeSystem.csrChunkZero(sc.csrChunkParams, mot.counts, sc.csrMatrix);
            CrossBridgeSystem.csrChunkHistogram(mot.boundSeg, mot.counts, sc.csrChunkParams, sc.csrMatrix);
            CrossBridgeSystem.csrChunkReduce(mot.counts, sc.csrChunkParams, sc.csrMatrix, sc.segMotorCount);
            CrossBridgeSystem.csrScan(mot.counts, sc.segMotorCount, sc.segMotorOffsets);
            CrossBridgeSystem.csrChunkScatter(mot.boundSeg, mot.counts, sc.csrChunkParams, sc.segMotorOffsets, sc.segMotorMyo, sc.csrMatrix);
        }
        CrossBridgeSystem.segGather(sc.segMotorOffsets, sc.segMotorMyo, sc.bondData, f.forceSum, f.torqueSum, mot.counts);
        RigidRodLangevinIntegrationSystem.integrate(f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts);
        DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
    }

    // ============================================================== timing probes
    static void gpuProbe(Scene sc, int M) {
        int warm = Math.max(50, M / 3);
        System.out.printf("--- GPU probe: device-resident merged TaskGraph, warmup=%d window=%d ---%n", warm, M - warm);
        TornadoExecutionPlan plan = buildPlan(sc);
        for (int t = 0; t < warm; t++) { sc.mot.setCounts(t, SEED, sc.fil.n); sc.fil.counts.set(1, t); plan.withGridScheduler(sched).execute(); }
        long t0 = System.nanoTime();
        for (int t = warm; t < M; t++) { sc.mot.setCounts(t, SEED, sc.fil.n); sc.fil.counts.set(1, t); plan.withGridScheduler(sched).execute(); }
        long t1 = System.nanoTime();
        TornadoExecutionResult res = plan.withGridScheduler(sched).execute();
        if (OLDBIND) res.transferToHost(sc.mot.boundSeg, sc.candCount, sc.reachCount, sc.fil.coord);
        else         res.transferToHost(sc.mot.boundSeg, sc.reachCount, sc.fil.coord);
        report("GPU", sc, M - warm, (t1 - t0) / 1e9);
        if (PROF) profile(sc, plan, 30);
    }

    /** Per-task kernel-time breakdown (requires -Dtornado.profiler=True). Sums TASK_KERNEL_TIME
     *  over `iters` executions, groups grid-build vs force-chain, and prints the dominant tasks. */
    static final String[] GRID_TASKS = { "filPublish","motPublish","publishHead","bodyCell",
            "chunkZero","chunkHist","chunkReduce","gridScanLocal","gridScanChunks","gridScanAdd",
            "chunkScatter","broadPhase","invert","reachable","gridReachable" };
    static final String[] ALL_TASKS = { "publishHead","filPublish","motPublish","bodyCell",
            "chunkZero","chunkHist","chunkReduce","gridScanLocal","gridScanChunks","gridScanAdd",
            "chunkScatter","broadPhase","invert","reachable","gridReachable","release","bind","cycle","zeroMot",
            "brownMot","joints","anchor","bond","applyHead","integMot","deriveMot","register",
            "zeroFil","brownFil","chain","csrHist","csrScan","csrScatter",
            "csrChunkZero","csrChunkHist","csrChunkReduce","csrChunkScatter","gather","integFil","deriveFil" };

    static void profile(Scene sc, TornadoExecutionPlan plan, int iters) {
        plan = plan.withProfiler(uk.ac.manchester.tornado.api.enums.ProfilerMode.SILENT);
        java.util.Map<String,Long> acc = new java.util.LinkedHashMap<>();
        for (String t : ALL_TASKS) acc.put(t, 0L);
        long total = 0;
        for (int k = 0; k < iters; k++) {
            int t = 9000 + k; sc.mot.setCounts(t, SEED, sc.fil.n); sc.fil.counts.set(1, t);
            TornadoExecutionResult res = plan.withGridScheduler(sched).execute();
            String json;
            try { json = res.getProfilerResult().getProfileLog(); } catch (Exception e) { json = null; }
            if (json == null || json.isEmpty()) continue;
            for (String name : ALL_TASKS) {
                long ns = extractTaskKernelTime(json, "densegliding." + name);
                acc.put(name, acc.get(name) + ns);
                total += ns;
            }
        }
        if (total == 0) { System.out.println("  [prof] no profiler data — run with -Dtornado.profiler=True"); return; }
        final long totalF = total;
        long gridNs = 0; for (String t : GRID_TASKS) gridNs += acc.getOrDefault(t, 0L);
        System.out.printf("--- per-task kernel time (sum/%d execs, ms/step) ---%n", iters);
        acc.entrySet().stream()
           .sorted((a,b) -> Long.compare(b.getValue(), a.getValue()))
           .limit(14)
           .forEach(e -> System.out.printf("  %-16s %8.3f ms/step  (%4.1f%%)%n",
                   e.getKey(), e.getValue() / 1e6 / iters, 100.0 * e.getValue() / totalF));
        System.out.printf("  GRID-BUILD subtotal: %.3f ms/step (%.1f%%)   force-chain: %.3f ms/step (%.1f%%)%n",
                gridNs / 1e6 / iters, 100.0 * gridNs / total,
                (total - gridNs) / 1e6 / iters, 100.0 * (total - gridNs) / total);
    }

    static long extractTaskKernelTime(String json, String taskName) {
        if (json == null || json.isEmpty()) return 0L;
        int keyIdx = json.indexOf("\"" + taskName + "\"");
        if (keyIdx < 0) return 0L;
        int braceIdx = json.indexOf('{', keyIdx);
        if (braceIdx < 0) return 0L;
        int endBlock = json.indexOf('}', braceIdx);
        if (endBlock < 0) endBlock = json.length();
        String block = json.substring(braceIdx, endBlock);
        int tktIdx = block.indexOf("TASK_KERNEL_TIME");
        if (tktIdx < 0) return 0L;
        int colonIdx = block.indexOf(':', tktIdx);
        int q1 = block.indexOf('"', colonIdx);
        int q2 = block.indexOf('"', q1 + 1);
        if (colonIdx < 0 || q1 < 0 || q2 < 0) return 0L;
        try { return Long.parseLong(block.substring(q1 + 1, q2).trim()); } catch (NumberFormatException e) { return 0L; }
    }

    static void cpuProbe(Scene sc, int M) {
        int warm = Math.max(20, M / 3);
        System.out.printf("--- CPU probe: sequential runner, warmup=%d window=%d ---%n", warm, M - warm);
        for (int t = 0; t < warm; t++) cpuStep(sc, t);
        long t0 = System.nanoTime();
        for (int t = warm; t < M; t++) cpuStep(sc, t);
        long t1 = System.nanoTime();
        report("CPU", sc, M - warm, (t1 - t0) / 1e9);
    }

    static void report(String label, Scene sc, int steps, double sec) {
        long bound = 0; for (int m = 0; m < sc.mot.nMotors; m++) if (sc.mot.boundSeg.get(m) >= 0) bound++;
        int maxCand = 0, maxReach = 0;
        for (int i = 0; i < sc.cap; i++) maxCand = Math.max(maxCand, sc.candCount.get(i));
        for (int m = 0; m < sc.mot.nMotors; m++) maxReach = Math.max(maxReach, sc.reachCount.get(m));
        boolean nan = false; for (int i = 0; i < Math.min(sc.fil.n, 3 * sc.fil.n); i++) if (Float.isNaN(sc.fil.coordX(i))) { nan = true; break; }
        double msStep = 1000.0 * sec / steps;
        System.out.printf("  %s THROUGHPUT: %.1f steps/s (%.2f ms/step) at %d motors / %d filaments%n",
                label, steps / sec, msStep, sc.mot.nMotors, sc.nFil);
        System.out.printf("  avgBound=%d (%.3f/motor)  maxCandCount=%d  maxReachCount=%d  MAX_CAND=%d %s  NaN=%b%n",
                bound, bound / (double) sc.mot.nMotors, maxCand, maxReach, SpatialGrid.MAX_CAND,
                ((maxCand >= SpatialGrid.MAX_CAND || maxReach >= SpatialGrid.MAX_CAND) ? "*OVERFLOW*" : "(ok)"), nan);
    }

    // ============================================================== grid==brute gate (small scale, CPU)
    static void gridCheck() {
        System.out.println("--- GATE: grid broad-phase reachable set == brute force (every motor×segment), exact ---");
        Scene sc = buildScene(0.05, true);   // small bed: ~20 filaments, a few thousand motors
        System.out.printf("  scale 0.05: %d filaments (%d segs), %d motors, %d view bodies%n", sc.nFil, sc.fil.n, sc.mot.nMotors, sc.cap);
        boolean all = true;
        java.util.Set<Integer> samples = new java.util.HashSet<>(java.util.Arrays.asList(0, 50, 150, 300, 500));
        for (int t = 0; t <= 500; t++) {
            if (samples.contains(t)) {
                // compare grid vs brute on IDENTICAL (start-of-step) positions — no integrate between
                reachStep(sc, t);
                BindingDetectionSystem.bruteReachable(sc.mot.head, sc.mot.uVec, sc.mot.rodUVec, sc.fil.end1, sc.fil.end2,
                        sc.bruteReachSeg, sc.bruteReachCount, sc.mot.kinParams, sc.mot.counts);
                long[] grid = reachPairs(sc.reachSeg, sc.reachCount, sc.mot.nMotors);
                long[] brute = reachPairs(sc.bruteReachSeg, sc.bruteReachCount, sc.mot.nMotors);
                int maxCand = 0; for (int i = 0; i < sc.cap; i++) maxCand = Math.max(maxCand, sc.candCount.get(i));
                boolean ovf = maxCand >= SpatialGrid.MAX_CAND;
                boolean match = java.util.Arrays.equals(grid, brute) && !ovf;
                all &= match;
                System.out.printf("  step %-5d gridPairs=%-6d brutePairs=%-6d maxCand=%-4d  %s%n",
                        t, grid.length, brute.length, maxCand, match ? "EXACT" : (ovf ? "*OVERFLOW*" : "*MISMATCH*"));
                if (!match && !ovf) diagnoseMiss(sc);
            }
            cpuStep(sc, t);   // advance the dynamics one step
        }
        System.out.println(all ? "\n=== GRID==BRUTE GATE PASS — the dense binding path is faithful ===" : "\n=== GRID==BRUTE GATE FAIL — commit nothing ===");
        if (!all) System.exit(1);
    }

    /** On a grid≠brute mismatch, find missed (motor,seg) pairs and classify the cause. */
    static void diagnoseMiss(Scene sc) {
        int nMot = sc.mot.nMotors, nSeg = sc.fil.n, MAXC = SpatialGrid.MAX_CAND;
        int shown = 0;
        for (int m = 0; m < nMot && shown < 4; m++) {
            int gc = Math.min(sc.reachCount.get(m), MAXC), bc = Math.min(sc.bruteReachCount.get(m), MAXC);
            if (gc == bc) continue;
            // brute segs for this motor
            for (int kb = 0; kb < bc; kb++) {
                int seg = sc.bruteReachSeg.get(m * MAXC + kb);
                boolean inGrid = false; for (int kg = 0; kg < gc; kg++) if (sc.reachSeg.get(m * MAXC + kg) == seg) { inGrid = true; break; }
                if (inGrid) continue;
                // missed: was seg in the motor's broad-phase candidate list?
                int cc = Math.min(sc.motorCandCount.get(m), MAXC);
                boolean inCand = false; for (int kc = 0; kc < cc; kc++) if (sc.motorCandSeg.get(m * MAXC + kc) == seg) { inCand = true; break; }
                double hx = sc.mot.headX(m), hy = sc.mot.headY(m), hz = sc.mot.headZ(m);
                double sx = sc.fil.coordX(seg), sy = sc.fil.coordY(seg), sz = sc.fil.coordZ(seg);
                double cd = Math.sqrt((hx-sx)*(hx-sx)+(hy-sy)*(hy-sy)+(hz-sz)*(hz-sz));
                int mb = sc.bodyCell.get(nSeg + m), sbCell = sc.bodyCell.get(seg);
                System.out.printf("    MISS motor=%d seg=%d  headCtrDist=%.4f  motorCandCount=%d  inCandList=%b  motorCell=%d segCell=%d%n",
                        m, seg, cd, sc.motorCandCount.get(m), inCand, mb, sbCell);
                shown++;
                if (shown >= 4) break;
            }
        }
    }

    static long[] reachPairs(IntArray seg, IntArray count, int nMot) {
        java.util.ArrayList<Long> list = new java.util.ArrayList<>();
        for (int m = 0; m < nMot; m++) {
            int cnt = Math.min(count.get(m), SpatialGrid.MAX_CAND);
            int base = m * SpatialGrid.MAX_CAND;
            for (int k = 0; k < cnt; k++) list.add(((long) m << 32) | (seg.get(base + k) & 0xffffffffL));
        }
        long[] arr = new long[list.size()];
        for (int k = 0; k < arr.length; k++) arr[k] = list.get(k);
        java.util.Arrays.sort(arr);
        return arr;
    }
}
