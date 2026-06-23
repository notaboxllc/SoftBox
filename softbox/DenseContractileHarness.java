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
 * DENSE CONTRACTILE COMPUTE BENCHMARK — v2 vs BoA GPU (the deferred 2026-06-17 target).
 *
 * The realistic future workload: a dense network of free filaments + free bipolar minifilaments that
 * bind and contract the network + crosslinkers, in an in-vitro chamber box, matched to BoA's dense
 * contractile v5 scene (boxXY=10·√scale, initialFilaments=initialMyoMiniFils=4000·scale, 16 dimers/
 * minifil, crosslinkers grab=0.05/k_on=400/conc=1, turnover ON). The number to beat = BoA's GPU column
 * (86/134/246/494/1030 ms/step over 0.5–8×).
 *
 * This is a COMPUTE benchmark (ms/step at matched counts), NOT a contractile-physics re-validation —
 * the structures are each validated in isolation (ContractileAssay / MiniGlide / CrosslinkerBundle /
 * the parallel grid binding). It reuses those systems VERBATIM and is the FIRST full-system composition
 * at dense scale (minifilament binding + cross-bridge contraction + crosslinker force + containment +
 * the parallel-grid fused per-motor binding + integration, all device-resident, no per-step host pull).
 *
 * SCENE-MATCH POSTURE (two v2 gaps, documented — see DENSE_CONTRACTILE_BENCHMARK_FINDINGS.md):
 *   (1) Crosslinker FORMATION is O(N²) in v2 (filFilCandidates single-threaded, reqCap=nSeg²/2; the
 *       STORE_CROSSLINKER SpatialGrid publisher is unwired — "5d"). It cannot run at dense scale.
 *   (2) Random (non-node) nucleation does not exist (kRdmNuc undefined; only node-driven nucleation).
 *   ⇒ The scene is PRE-PLACED at BoA's matched counts (filaments pre-grown to BoA's segs/fil, crosslinkers
 *   pre-formed at the active-link count); the per-step MECHANICS — which dominate BoA's step (exec/pack/
 *   gather/brown/integrate ≈ 95 %; biochem ≈ 3 %, xlink-formation ≈ 0) — run device-resident at scale.
 *   Crosslinkers run FORCE + the 2-pass gather (static topology; Bell-unbind via -xlunbind). Turnover biochem
 *   (aging/sever/depoly/growth) is NOT wired here — it is ≤3.4 % of BoA's own step and, lacking the two gaps
 *   above (formation, random nucleation), a faithful *dynamic* turnover loop is incomplete; flagged for follow-up.
 *
 * Modes: -scale N [-cpu] [M]  timing probe (warmup-windowed ms/step);  -check  small-scale assembly+sanity.
 */
public final class DenseContractileHarness {

    static final int    B          = 64;
    static final int    FIL_PER_1X = 4000;     // BoA initialFilaments at 1× (dense v5)
    static final int    MINI_PER_1X = 4000;    // BoA initialMyoMiniFils at 1×
    static final int    FIL_SEGS   = 6;        // ≈ BoA segs/fil at the grown steady state (23779/4000 ≈ 5.9)
    static final int    FIL_MONO   = 64;       // 64-monomer segments (v1 stdSegLength override)
    static final int    DIMERS_END = 8;        // numMyoDimersEachEnd ⇒ 16 dimers / 32 heads per minifilament
    static final double XLINK_PER_FIL = 0.84;  // BoA active links/fil (≈3363/4000 at 1×, scales ∝ N)
    static double DT          = 1e-4;          // BoA deltaT (fall back to 1e-5 if unstable; flagged)
    static final double DENSITY_BOX = 10.0;    // BoA box schedule boxXY = 10·√scale
    static final double BOX_Z   = 0.5;         // box depth
    static final double MYO_COL_TOL = 0.025;   // bind reach (ContractileAssay REACH)
    static final double ALIGN_TOL   = -0.4;
    static final double KOFF        = 100.0;
    static final double MYO_SPRING  = 1.0e-9, J1_FMT = 0.4;
    static final double BROWN_TRANS = 1.0, BROWN_ROT = 0.3;
    static final double AETA = 1.0;            // crowded-cytoplasm viscosity (dense fixture; applied to all stores)
    static int SEED = 0x0CDE5;
    static int SEED_BB = 0x5C2F11;
    static boolean XLINK = true;               // crosslinker force layer (pre-placed; no formation)
    static boolean XL_UNBIND = false;          // Bell unbind (off ⇒ static links hold the matched count; formation is 5d-gated)
    static GridScheduler sched;

    public static void main(String[] args) {
        double scale = 1.0; int M = 650; boolean cpu = false, check = false;
        java.util.List<String> pos = new java.util.ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-scale" -> scale = Double.parseDouble(args[++i]);
                case "-cpu"   -> cpu = true;
                case "-check" -> check = true;
                case "-noxlink" -> XLINK = false;
                case "-xlunbind" -> XL_UNBIND = true;
                case "-dt"    -> DT = Double.parseDouble(args[++i]);
                case "-seed"  -> SEED = 0x0CDE5 + 7919 * Integer.parseInt(args[++i]);
                default       -> pos.add(args[i]);
            }
        }
        if (!pos.isEmpty()) M = Integer.parseInt(pos.get(0));

        System.out.println("=== Soft Box — DENSE CONTRACTILE COMPUTE benchmark (v2 vs BoA GPU) ===");
        if (check) { sanityCheck(); return; }

        Scene sc = buildScene(scale);
        System.out.printf("config: scale=%.3g  box %.2f×%.2f×%.1f µm  filaments=%d (%d segs)  minifils=%d  heads=%d  xlinks=%d  dt=%.0e  aeta=%.2g%n",
                scale, sc.side, sc.side, BOX_Z, sc.nFil, sc.fil.n, sc.nMini, sc.mot.nMotors, sc.nLinks, DT, AETA);
        if (cpu) cpuProbe(sc, M);
        else     gpuProbe(sc, M);
    }

    // ============================================================== scene
    static final class Scene {
        FilamentStore fil; MotorStore mot; DimerStore dim; MiniFilamentStore mini; CrosslinkerStore xl;
        FloatArray bondData, xbParams;
        // motor→seg gather (parallel CSR)
        IntArray segMotorCount, segMotorOffsets, segMotorMyo, csrChunkParams, csrMatrix; int numMotorChunks;
        // backbone gather (parallel CSR)
        IntArray bbChunkParams, bbMatrix; int numBbChunks;
        // crosslinker 2-pass gather (parallel CSR, A + B)
        IntArray segCountA, segOffsetsA, segIdxA, xlChunkParamsA, xlMatrixA;
        IntArray segCountB, segOffsetsB, segIdxB, xlChunkParamsB, xlMatrixB; int numXlChunks;
        // grid
        FloatArray gridParams, viewParams; IntArray gridDims, gridCounts;
        IntArray bodyCell, cellCount, chunkSum, gridCellOffsets, gridCellContents, chunkParams, chunkCellCount; int numBodyChunks;
        IntArray reachSeg, reachCount;
        SpatialBodyView view;
        FloatArray boxParams;
        int cap, totalCells, nFil, nMini, nLinks; double side;
    }

    static Scene buildScene(double scale) {
        Scene sc = new Scene();
        double side = DENSITY_BOX * Math.sqrt(scale);
        int nFil  = (int) Math.round(FIL_PER_1X  * scale);
        int nMini = (int) Math.round(MINI_PER_1X * scale);
        int nSeg  = nFil * FIL_SEGS;
        int nDimers = nMini * 2 * DIMERS_END;
        int nMot    = 2 * nDimers;                          // 2 heads/dimer
        int nLinks  = (int) Math.round(XLINK_PER_FIL * nFil);
        double L = (FIL_MONO + 1) * Constants.actinMonoRadius;
        sc.side = side; sc.nFil = nFil; sc.nMini = nMini; sc.nLinks = nLinks;

        java.util.Random rng = new java.util.Random(SEED);
        double half = 0.5 * side, hz = 0.5 * BOX_Z;
        double margin = 0.5 * (FIL_SEGS * L);               // keep whole filaments inside the box

        // ---- filaments: free multi-segment chains, random center + orientation in the box ----
        FilamentStore fil = new FilamentStore(nSeg);
        for (int p = 0; p < nFil; p++) {
            double cx = (rng.nextDouble() - 0.5) * (side - 2 * margin);
            double cy = (rng.nextDouble() - 0.5) * (side - 2 * margin);
            double cz = (rng.nextDouble() - 0.5) * (BOX_Z - 0.1);
            // random in-plane orientation (thin slab ⇒ keep ~in xy)
            double th = rng.nextDouble() * Math.PI * 2;
            double ux = Math.cos(th), uy = Math.sin(th), uz = 0;
            // a perpendicular yVec
            double yx = -uy, yy = ux, yz = 0;
            int g0 = p * FIL_SEGS;
            for (int s = 0; s < FIL_SEGS; s++) {
                int g = g0 + s;
                double off = (s - 0.5 * (FIL_SEGS - 1)) * L;
                fil.monomerCount.set(g, FIL_MONO);
                fil.setUVec(g, (float) ux, (float) uy, (float) uz);
                fil.setYVec(g, (float) yx, (float) yy, (float) yz);
                fil.setCoord(g, (float) (cx + off * ux), (float) (cy + off * uy), (float) (cz + off * uz));
                fil.brownTransScale.set(g, (float) Constants.BTransCoeff);
                boolean end = (s == 0 || s == FIL_SEGS - 1);
                fil.brownRotScale.set(g, (float) (end ? Constants.BRotCoeff : 0.0));
                if (s < FIL_SEGS - 1) { fil.end2NbrSlot.set(g, g + 1); fil.end2NbrSide.set(g, 0); }
                if (s > 0)            { fil.end1NbrSlot.set(g, g - 1); fil.end1NbrSide.set(g, 1); }
            }
        }
        DragTensorSystem.run(fil);
        CrosslinkerBundleHarnessAeta(fil, AETA);            // crowded-cytoplasm drag (FDT-consistent)
        fil.setParams(DT, Math.sqrt(2.0 * Constants.kT / DT));
        fil.setCounts(0, 0xF11A);
        fil.chainParams.set(0, (float) DT); fil.chainParams.set(1, 0.5f); fil.chainParams.set(2, 0.1f);
        fil.chainParams.set(3, 0.2f); fil.chainParams.set(4, 0f); fil.chainParams.set(5, 1.0e-20f);
        fil.chainParams.set(6, (float) Constants.actinMonoRadius);
        DerivedGeometrySystem.derive(fil.coord, fil.uVec, fil.yVec, fil.zVec, fil.end1, fil.end2, fil.segLength, fil.counts);

        // ---- minifilaments: free backbones (random pos/orient) each owning 16 splayed dimers ----
        MotorStore mot = new MotorStore(nMot);
        DimerStore dim = new DimerStore(nDimers);
        MiniFilamentStore mini = new MiniFilamentStore(nMini, nDimers);
        double bbLen = MiniFilamentStore.BACKBONE_LEN, headZone = MiniFilamentStore.HEAD_ZONE;
        int d = 0;
        for (int mfi = 0; mfi < nMini; mfi++) {
            double bx = (rng.nextDouble() - 0.5) * (side - 2 * margin);
            double by = (rng.nextDouble() - 0.5) * (side - 2 * margin);
            double bz = (rng.nextDouble() - 0.5) * (BOX_Z - 0.1);
            double th = rng.nextDouble() * Math.PI * 2;
            double ux = Math.cos(th), uy = Math.sin(th), uz = 0;
            double yx = -uy, yy = ux, yz = 0;
            mini.backbone.setCoord(mfi, (float) bx, (float) by, (float) bz);
            mini.backbone.setUVec(mfi, (float) ux, (float) uy, (float) uz);
            mini.backbone.setYVec(mfi, (float) yx, (float) yy, (float) yz);
            mini.backbone.brownTransScale.set(mfi, (float) BROWN_TRANS);
            mini.backbone.brownRotScale.set(mfi, (float) BROWN_ROT);
            for (int e = 0; e < 2; e++) {
                double dir = (e == 0) ? -1.0 : 1.0;
                for (int j = 0; j < DIMERS_END; j++) {
                    double mag = bbLen / 2.0 - (j + 0.5) / DIMERS_END * headZone;
                    double ax = dir * mag;
                    // dimer attach point in WORLD coords (backbone-local axial offset along uVec)
                    double e1x = bx + ax * ux, e1y = by + ax * uy, e1z = bz + ax * uz;
                    double phi = (j + 0.5) / DIMERS_END * Math.PI;
                    // splay azimuth around the backbone axis: a 3D radial direction perpendicular to uVec
                    double px = Math.cos(phi) * yx + Math.sin(phi) * (uy * yz - uz * yy);
                    double py = Math.cos(phi) * yy + Math.sin(phi) * (uz * yx - ux * yz);
                    double pz = Math.cos(phi) * yz + Math.sin(phi) * (ux * yy - uy * yx);
                    int mA = 2 * d, mB = 2 * d + 1;
                    ContractileAssayHarness.placeDimerAlong(mot, mA, mB, e1x, e1y, e1z, dir * ux, dir * uy, dir * uz, px, py, pz);
                    dim.pair(d, mA, mB, true);
                    mini.attach(d, mfi, mA, ax);
                    mot.reach.set(mA, (float) MYO_COL_TOL); mot.reach.set(mB, (float) MYO_COL_TOL);
                    d++;
                }
            }
        }
        DragTensorSystem.run(mot);
        mini.initBackboneDrag();
        mot.setBodyParams(DT); mot.setJointParams(DT); mot.setKinParams(MYO_COL_TOL, ALIGN_TOL, DT); mot.setNucParams(DT);
        mot.kinParams.set(0, (float) KOFF);
        mot.setFaithfulRelease(true, 0.0);                  // v1 12 pN break-force cap (stability)
        mot.nucleotideState.init(MotorStore.NUC_NONE);
        mot.setBaseSlot(nSeg);                              // heads at view slots [nSeg, nSeg+nMot)
        dim.setDimerParams(DT);
        mini.setMiniParams(DT); mini.setBackboneParams(DT);

        // ---- cross-bridge scratch ----
        int MAXC = SpatialGrid.MAX_CAND;
        sc.bondData = new FloatArray(nMot * CrossBridgeSystem.STRIDE); sc.bondData.init(0f);
        sc.xbParams = FloatArray.fromElements((float) MYO_SPRING, 90f, (float) J1_FMT, (float) DT, (float) MotorStore.HEAD_LEN, 0f);

        // ---- motor→seg gather (parallel CSR) ----
        sc.segMotorCount = new IntArray(nSeg); sc.segMotorOffsets = new IntArray(nSeg + 1); sc.segMotorMyo = new IntArray(nMot);
        int mcs = SpatialGrid.bodyChunkSize(nMot, nSeg);
        sc.numMotorChunks = SpatialGrid.numBodyChunks(nMot, mcs);
        sc.csrChunkParams = IntArray.fromElements(mcs, sc.numMotorChunks);
        sc.csrMatrix = new IntArray(sc.numMotorChunks * nSeg); sc.csrMatrix.init(0);

        // ---- backbone gather (parallel CSR over dimers keyed by backbone) ----
        int bcs = SpatialGrid.bodyChunkSize(nDimers, nMini);
        sc.numBbChunks = SpatialGrid.numBodyChunks(nDimers, bcs);
        sc.bbChunkParams = IntArray.fromElements(bcs, sc.numBbChunks);
        sc.bbMatrix = new IntArray(sc.numBbChunks * nMini); sc.bbMatrix.init(0);

        // ---- grid + body view (filaments + heads) ----
        int cap = nSeg + nMot;
        SpatialBodyView view = new SpatialBodyView(cap); view.count = cap;
        double segBoundR = 0.5 * L + Constants.radius;
        double cutoff = MYO_COL_TOL + 0.5 * MotorStore.ROD_LEN; // head reach + a slack
        double cellSize = 2.0 * segBoundR + cutoff;
        double gx = half + 1.0, gy = gx, gz = hz + 0.3;
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
        int gbcs = SpatialGrid.bodyChunkSize(cap, sc.totalCells);
        sc.numBodyChunks = SpatialGrid.numBodyChunks(cap, gbcs);
        sc.chunkParams = IntArray.fromElements(gbcs, sc.numBodyChunks);
        sc.chunkCellCount = new IntArray(sc.numBodyChunks * sc.totalCells); sc.chunkCellCount.init(0);
        sc.gridCellOffsets = new IntArray(sc.totalCells + 1);
        sc.gridCellContents = new IntArray(cap); sc.gridCellContents.init(-1);
        sc.reachSeg = new IntArray(nMot * MAXC); sc.reachSeg.init(-1);
        sc.reachCount = new IntArray(nMot);
        sc.view = view; sc.cap = cap;

        // ---- crosslinkers: pre-place ~nLinks active links between nearby segments on distinct filaments ----
        CrosslinkerStore xl = buildCrosslinkers(fil, nSeg, nFil, nLinks, rng);
        sc.xl = xl; sc.nLinks = xl == null ? 0 : countActive(xl);
        if (xl != null) {
            sc.segCountA = new IntArray(nSeg); sc.segOffsetsA = new IntArray(nSeg + 1); sc.segIdxA = new IntArray(xl.nLinks);
            sc.segCountB = new IntArray(nSeg); sc.segOffsetsB = new IntArray(nSeg + 1); sc.segIdxB = new IntArray(xl.nLinks);
            int xcs = SpatialGrid.bodyChunkSize(xl.nLinks, nSeg);
            sc.numXlChunks = SpatialGrid.numBodyChunks(xl.nLinks, xcs);
            sc.xlChunkParamsA = IntArray.fromElements(xcs, sc.numXlChunks);
            sc.xlMatrixA = new IntArray(sc.numXlChunks * nSeg); sc.xlMatrixA.init(0);
            sc.xlChunkParamsB = IntArray.fromElements(xcs, sc.numXlChunks);
            sc.xlMatrixB = new IntArray(sc.numXlChunks * nSeg); sc.xlMatrixB.init(0);
        }

        // ---- containment box (the in-vitro chamber): BoA box, collisionDeltaT 1e-4 ⇒ checkInt = 1e-4/dt ----
        int checkInt = Math.max(1, (int) Math.round(1.0e-4 / DT));
        sc.boxParams = FloatArray.fromElements(1.0e-4f, (float) side, (float) side, (float) BOX_Z,
                (float) Constants.radius, 0.5f, (float) checkInt);

        sc.fil = fil; sc.mot = mot; sc.dim = dim; sc.mini = mini;
        return sc;
    }

    /** FDT-consistent crowded-cytoplasm drag scaling (CrosslinkerBundleHarness.applyAeta, inlined). */
    static void CrosslinkerBundleHarnessAeta(FilamentStore f, double aeta) {
        double r = aeta / Constants.aeta;
        for (int i = 0; i < f.bTransGam.getSize(); i++) f.bTransGam.set(i, (float) (f.bTransGam.get(i) * r));
        for (int i = 0; i < f.bRotGam.getSize(); i++)   f.bRotGam.set(i, (float) (f.bRotGam.get(i) * r));
        for (int i = 0; i < f.bTransDiff.getSize(); i++) f.bTransDiff.set(i, (float) (f.bTransDiff.get(i) / r));
        for (int i = 0; i < f.bRotDiff.getSize(); i++)   f.bRotDiff.set(i, (float) (f.bRotDiff.get(i) / r));
    }

    static int countActive(CrosslinkerStore xl) {
        int c = 0; for (int k = 0; k < xl.nLinks; k++) if (xl.linkState.get(k) >= 0) c++; return c;
    }

    /** Pre-place crosslinkers between nearby segments on DISTINCT filaments (setup-time neighbor search,
     *  O(N·k) via a coarse cell hash). Static topology at the matched active-link count; force + Bell-unbind
     *  run per step (formation is the O(N²) v2 gap — excluded, flagged). */
    static CrosslinkerStore buildCrosslinkers(FilamentStore fil, int nSeg, int nFil, int nLinks, java.util.Random rng) {
        if (nLinks <= 0) return null;
        CrosslinkerStore xl = new CrosslinkerStore(nLinks, nSeg, 1);
        double grab = 0.05;                                 // BoA crossLinkGrabDist
        double rest = 0.0125;                               // FilLink.restLength
        // coarse spatial hash of segment centers for neighbor finding
        double cell = grab; int placed = 0; int attempts = 0;
        java.util.HashMap<Long, java.util.ArrayList<Integer>> grid = new java.util.HashMap<>();
        for (int s = 0; s < nSeg; s++) {
            long key = cellKey(fil.coordX(s), fil.coordY(s), fil.coordZ(s), cell);
            grid.computeIfAbsent(key, x -> new java.util.ArrayList<>()).add(s);
        }
        while (placed < nLinks && attempts < nLinks * 200) {
            attempts++;
            int s = rng.nextInt(nSeg);
            int filS = s / FIL_SEGS;
            double sx = fil.coordX(s), sy = fil.coordY(s), sz = fil.coordZ(s);
            // scan the 27 neighbor cells for a segment on a different filament within grab
            int best = -1; double bestD = grab * grab;
            int cx = (int) Math.floor(sx / cell), cy = (int) Math.floor(sy / cell), cz = (int) Math.floor(sz / cell);
            for (int dx = -1; dx <= 1 && best < 0; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                java.util.ArrayList<Integer> bucket = grid.get(packCell(cx + dx, cy + dy, cz + dz));
                if (bucket == null) continue;
                for (int t : bucket) {
                    if (t / FIL_SEGS == filS) continue;
                    double ddx = fil.coordX(t) - sx, ddy = fil.coordY(t) - sy, ddz = fil.coordZ(t) - sz;
                    double dd = ddx * ddx + ddy * ddy + ddz * ddz;
                    if (dd < bestD) { bestD = dd; best = t; }
                }
            }
            if (best < 0) continue;
            double half = 0.5 * fil.segLength.get(s);
            xl.setLink(placed, s, half, best, 0.5 * fil.segLength.get(best));
            xl.linkState.set(placed, CrosslinkerStore.LINK_ACTIVE);
            xl.linkOrientSame.set(placed, 1);
            placed++;
        }
        xl.setParams(rest, 0.4, DT);
        xl.setOffParams(1.0, 1.0, 2.0, DT, rest);
        xl.setTorsionParams(1.0e-19, true);
        xl.computeFilLinkCt();
        System.out.printf("  crosslinkers: placed %d / target %d (%.2f/fil) in %d attempts%n", placed, nLinks, placed / (double) nFil, attempts);
        return xl;
    }
    static long cellKey(double x, double y, double z, double cell) {
        return packCell((int) Math.floor(x / cell), (int) Math.floor(y / cell), (int) Math.floor(z / cell));
    }
    static long packCell(int x, int y, int z) {
        return (((long) (x & 0x1FFFFF)) << 42) | (((long) (y & 0x1FFFFF)) << 21) | (z & 0x1FFFFF);
    }

    // ============================================================== CPU per-step (mechanics)
    static void cpuStep(Scene sc, int t) {
        FilamentStore f = sc.fil; MotorStore mot = sc.mot; DimerStore dim = sc.dim; MiniFilamentStore mini = sc.mini;
        RigidRodBody b = mot.body; RigidRodBody bb = mini.backbone; CrosslinkerStore xl = sc.xl;
        mot.setCounts(t, SEED, f.n); mini.setBackboneCounts(t, SEED_BB); f.counts.set(1, t);

        // --- binding (parallel grid + fused per-motor query) ---
        MotorStore.publishHeadFromBody(b.coord, b.uVec, b.segLength, mot.head, mot.uVec, mot.rodUVec, mot.counts);
        FilamentStore.publishToBodyView(f.coord, f.segLength, sc.view.center, sc.view.boundingRadius, sc.view.ownerStore, sc.view.ownerSlot, sc.viewParams, sc.gridCounts);
        MotorStore.publishToBodyView(mot.head, mot.reach, sc.view.center, sc.view.boundingRadius, sc.view.ownerStore, sc.view.ownerSlot, mot.publishParams, mot.counts);
        SpatialGrid.bodyCell(sc.view.center, sc.gridParams, sc.gridDims, sc.gridCounts, sc.bodyCell);
        SpatialGrid.gridChunkZero(sc.chunkParams, sc.gridDims, sc.chunkCellCount);
        SpatialGrid.gridChunkHistogram(sc.bodyCell, sc.gridCounts, sc.chunkParams, sc.gridDims, sc.chunkCellCount);
        SpatialGrid.gridChunkReduce(sc.gridDims, sc.chunkParams, sc.chunkCellCount, sc.cellCount);
        SpatialGrid.gridScanLocal(sc.gridDims, sc.cellCount, sc.gridCellOffsets, sc.chunkSum);
        SpatialGrid.gridScanChunks(sc.gridDims, sc.chunkSum);
        SpatialGrid.gridScanAdd(sc.gridDims, sc.gridCellOffsets, sc.gridCellContents, sc.cellCount, sc.chunkSum);
        SpatialGrid.gridChunkScatter(sc.bodyCell, sc.gridCounts, sc.chunkParams, sc.gridDims, sc.gridCellOffsets, sc.gridCellContents, sc.chunkCellCount);
        BindingDetectionSystem.gridReachable(mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, sc.gridParams, sc.gridDims,
                sc.gridCellOffsets, sc.gridCellContents, sc.view.ownerStore, sc.view.ownerSlot, sc.reachSeg, sc.reachCount, mot.kinParams, mot.counts);
        NucleotideCycleSystem.catchSlipRelease(mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.cooldown, mot.stats, mot.capStats, mot.kinParams, mot.counts);
        BindingDetectionSystem.bindNearest(mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, sc.reachSeg, sc.reachCount, mot.boundSeg, mot.bindArc, mot.kinParams, mot.counts);
        NucleotideCycleSystem.cycle(mot.nucleotideState, mot.boundSeg, mot.forceDotHist, mot.nucParams, mot.counts);

        // --- minifilament structure mechanics ---
        ChainBendingForceSystem.zeroAccumulators(b.forceSum, b.torqueSum, mot.counts);
        ChainBendingForceSystem.zeroAccumulators(bb.forceSum, bb.torqueSum, mini.bbCounts);
        BrownianForceSystem.brownianForce(b.randForce, b.randTorque, b.bTransGam, b.bRotGam, b.brownTransScale, b.brownRotScale, mot.bodyParams, mot.counts);
        BrownianForceSystem.brownianForce(bb.randForce, bb.randTorque, bb.bTransGam, bb.bRotGam, bb.brownTransScale, bb.brownRotScale, mini.bbBodyParams, mini.bbCounts);
        MotorJointSystem.joints(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, mot.nucleotideState, mot.jointParams, mot.counts);
        DimerCouplingSystem.couple(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, dim.motorA, dim.motorB, dim.parallel, dim.dimerParams, mot.boundSeg);
        MiniFilamentSystem.tether(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum,
                bb.coord, bb.uVec, mini.bbInvDragY, mini.headBackboneSlot, mini.motorA, mini.attachAxial, mini.miniData, mini.miniParams);
        CrossBridgeSystem.csrChunkZero(sc.bbChunkParams, mini.miniCounts, sc.bbMatrix);
        CrossBridgeSystem.csrChunkHistogram(mini.headBackboneSlot, mini.miniCounts, sc.bbChunkParams, sc.bbMatrix);
        CrossBridgeSystem.csrChunkReduce(mini.miniCounts, sc.bbChunkParams, sc.bbMatrix, mini.bbDimerCount);
        CrossBridgeSystem.csrScan(mini.miniCounts, mini.bbDimerCount, mini.bbDimerOffsets);
        CrossBridgeSystem.csrChunkScatter(mini.headBackboneSlot, mini.miniCounts, sc.bbChunkParams, mini.bbDimerOffsets, mini.bbDimerList, sc.bbMatrix);
        MiniFilamentSystem.backboneGather(mini.bbDimerOffsets, mini.bbDimerList, mini.miniData, bb.forceSum, bb.torqueSum, mini.miniCounts);
        CrossBridgeSystem.bondForces(b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength, mot.boundSeg, mot.bindArc, mot.nucleotideState, sc.bondData, sc.xbParams);
        CrossBridgeSystem.applyHeadForce(sc.bondData, b.forceSum, b.torqueSum, mot.counts);
        RigidRodLangevinIntegrationSystem.integrate(b.coord, b.uVec, b.yVec, b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, mot.bodyParams, mot.counts);
        ContainmentSystem.confine(bb.coord, bb.uVec, bb.segLength, bb.bTransGam, bb.forceSum, bb.torqueSum, sc.boxParams, mini.bbCounts);
        RigidRodLangevinIntegrationSystem.integrate(bb.coord, bb.uVec, bb.yVec, bb.forceSum, bb.torqueSum, bb.randForce, bb.randTorque, bb.bTransGam, bb.bRotGam, mini.bbBodyParams, mini.bbCounts);
        DerivedGeometrySystem.derive(b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts);
        DerivedGeometrySystem.derive(bb.coord, bb.uVec, bb.yVec, bb.zVec, bb.end1, bb.end2, bb.segLength, mini.bbCounts);
        CrossBridgeSystem.registerForceDot(sc.bondData, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.counts);

        // --- filament: chain + crosslinker force + motor gather + containment + integrate ---
        ChainBendingForceSystem.zeroAccumulators(f.forceSum, f.torqueSum, f.counts);
        BrownianForceSystem.brownianForce(f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.brownTransScale, f.brownRotScale, f.params, f.counts);
        ChainBendingForceSystem.chainForces(f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide, f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts);
        if (XLINK && xl != null) {
            xl.setCounts(t, SEED);
            if (XL_UNBIND) CrosslinkerSystem.unbind(f.coord, f.uVec, f.end1, xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2, xl.linkState, xl.strainHist, xl.strainPlace, xl.offParams, xl.counts);
            CrosslinkerSystem.countActiveLinks(xl.linkState, xl.linkFilA, xl.linkFilB, xl.activeLinkCount, xl.formCounts);
            CrosslinkerSystem.linkForces(f.coord, f.uVec, f.end1, f.bTransGam, xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2, xl.activeLinkCount, xl.xlinkData, xl.xlParams);
            CrosslinkerSystem.linkTorsion(f.uVec, xl.linkFilA, xl.linkFilB, xl.linkState, xl.linkOrientSame, xl.torqueMagHist, xl.torqueMagPlace, xl.xlinkData, xl.torsionParams);
            CrossBridgeSystem.csrChunkZero(sc.xlChunkParamsA, xl.counts, sc.xlMatrixA);
            CrossBridgeSystem.csrChunkHistogram(xl.linkFilA, xl.counts, sc.xlChunkParamsA, sc.xlMatrixA);
            CrossBridgeSystem.csrChunkReduce(xl.counts, sc.xlChunkParamsA, sc.xlMatrixA, sc.segCountA);
            CrossBridgeSystem.csrScan(xl.counts, sc.segCountA, sc.segOffsetsA);
            CrossBridgeSystem.csrChunkScatter(xl.linkFilA, xl.counts, sc.xlChunkParamsA, sc.segOffsetsA, sc.segIdxA, sc.xlMatrixA);
            CrosslinkerSystem.segGatherA(sc.segOffsetsA, sc.segIdxA, xl.xlinkData, xl.linkState, f.forceSum, f.torqueSum, xl.counts);
            CrossBridgeSystem.csrChunkZero(sc.xlChunkParamsB, xl.counts, sc.xlMatrixB);
            CrossBridgeSystem.csrChunkHistogram(xl.linkFilB, xl.counts, sc.xlChunkParamsB, sc.xlMatrixB);
            CrossBridgeSystem.csrChunkReduce(xl.counts, sc.xlChunkParamsB, sc.xlMatrixB, sc.segCountB);
            CrossBridgeSystem.csrScan(xl.counts, sc.segCountB, sc.segOffsetsB);
            CrossBridgeSystem.csrChunkScatter(xl.linkFilB, xl.counts, sc.xlChunkParamsB, sc.segOffsetsB, sc.segIdxB, sc.xlMatrixB);
            CrosslinkerSystem.segGatherB(sc.segOffsetsB, sc.segIdxB, xl.xlinkData, xl.linkState, f.forceSum, f.torqueSum, xl.counts);
        }
        CrossBridgeSystem.csrChunkZero(sc.csrChunkParams, mot.counts, sc.csrMatrix);
        CrossBridgeSystem.csrChunkHistogram(mot.boundSeg, mot.counts, sc.csrChunkParams, sc.csrMatrix);
        CrossBridgeSystem.csrChunkReduce(mot.counts, sc.csrChunkParams, sc.csrMatrix, sc.segMotorCount);
        CrossBridgeSystem.csrScan(mot.counts, sc.segMotorCount, sc.segMotorOffsets);
        CrossBridgeSystem.csrChunkScatter(mot.boundSeg, mot.counts, sc.csrChunkParams, sc.segMotorOffsets, sc.segMotorMyo, sc.csrMatrix);
        CrossBridgeSystem.segGather(sc.segMotorOffsets, sc.segMotorMyo, sc.bondData, f.forceSum, f.torqueSum, mot.counts);
        ContainmentSystem.confine(f.coord, f.uVec, f.segLength, f.bTransGam, f.forceSum, f.torqueSum, sc.boxParams, f.counts);
        RigidRodLangevinIntegrationSystem.integrate(f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts);
        DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
    }

    // ============================================================== timing probes (placeholders — GPU plan next)
    static void cpuProbe(Scene sc, int M) {
        int warm = Math.max(20, M / 3);
        System.out.printf("--- CPU probe: sequential runner, warmup=%d window=%d ---%n", warm, M - warm);
        for (int t = 0; t < warm; t++) cpuStep(sc, t);
        long t0 = System.nanoTime();
        for (int t = warm; t < M; t++) cpuStep(sc, t);
        long t1 = System.nanoTime();
        report("CPU", sc, M - warm, (t1 - t0) / 1e9);
    }

    static void gpuProbe(Scene sc, int M) {
        int warm = Math.max(50, M / 3);
        System.out.printf("--- GPU probe: device-resident merged TaskGraph, warmup=%d window=%d ---%n", warm, M - warm);
        TornadoExecutionPlan plan = buildPlan(sc);
        for (int t = 0; t < warm; t++) { stepCounts(sc, t); plan.withGridScheduler(sched).execute(); }
        long t0 = System.nanoTime();
        for (int t = warm; t < M; t++) { stepCounts(sc, t); plan.withGridScheduler(sched).execute(); }
        long t1 = System.nanoTime();
        TornadoExecutionResult res = plan.withGridScheduler(sched).execute();
        res.transferToHost(sc.fil.coord, sc.mot.boundSeg, sc.xl == null ? sc.reachCount : sc.xl.linkState, sc.reachCount);
        report("GPU", sc, M - warm, (t1 - t0) / 1e9);
    }

    static void stepCounts(Scene sc, int t) {
        sc.mot.setCounts(t, SEED, sc.fil.n); sc.mini.setBackboneCounts(t, SEED_BB); sc.fil.counts.set(1, t);
        if (sc.xl != null) sc.xl.setCounts(t, SEED);
    }

    static TornadoExecutionPlan buildPlan(Scene sc) {
        FilamentStore f = sc.fil; MotorStore mot = sc.mot; DimerStore dim = sc.dim; MiniFilamentStore mini = sc.mini;
        RigidRodBody b = mot.body; RigidRodBody bb = mini.backbone; SpatialBodyView v = sc.view; CrosslinkerStore xl = sc.xl;
        TaskGraph tg = new TaskGraph("densecontract")
            .transferToDevice(DataTransferMode.FIRST_EXECUTION,
                    b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, b.bTransGam, b.bRotGam,
                    b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.brownTransScale, b.brownRotScale,
                    mot.head, mot.uVec, mot.rodUVec, mot.boundSeg, mot.bindArc, mot.nucleotideState, mot.reach,
                    mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.stats, mot.capStats, mot.cooldown,
                    mot.bodyParams, mot.jointParams, mot.nucParams, mot.kinParams, mot.publishParams,
                    dim.motorA, dim.motorB, dim.parallel, dim.dimerParams,
                    bb.coord, bb.uVec, bb.yVec, bb.zVec, bb.end1, bb.end2, bb.segLength, bb.bTransGam, bb.bRotGam,
                    bb.forceSum, bb.torqueSum, bb.randForce, bb.randTorque, bb.brownTransScale, bb.brownRotScale, mini.bbBodyParams,
                    mini.bbInvDragY, mini.headBackboneSlot, mini.motorA, mini.attachAxial, mini.miniData, mini.miniParams,
                    mini.bbDimerCount, mini.bbDimerOffsets, mini.bbDimerList, mini.miniCounts,
                    sc.bondData, sc.xbParams, sc.segMotorCount, sc.segMotorOffsets, sc.segMotorMyo, sc.csrChunkParams, sc.csrMatrix,
                    sc.bbChunkParams, sc.bbMatrix, sc.reachSeg, sc.reachCount,
                    f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.bTransGam, f.bRotGam,
                    f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.params, f.chainParams, f.brownTransScale, f.brownRotScale,
                    f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide,
                    v.center, v.boundingRadius, v.ownerStore, v.ownerSlot,
                    sc.gridParams, sc.gridDims, sc.gridCounts, sc.viewParams,
                    sc.bodyCell, sc.cellCount, sc.chunkSum, sc.gridCellOffsets, sc.gridCellContents, sc.chunkParams, sc.chunkCellCount,
                    sc.boxParams)
            .transferToDevice(DataTransferMode.EVERY_EXECUTION, mot.counts, mini.bbCounts, f.counts);
        // --- binding: publish + parallel grid build + fused per-motor query + bind/cycle ---
        tg = tg
            .task("publishHead", MotorStore::publishHeadFromBody, b.coord, b.uVec, b.segLength, mot.head, mot.uVec, mot.rodUVec, mot.counts)
            .task("filPublish", FilamentStore::publishToBodyView, f.coord, f.segLength, v.center, v.boundingRadius, v.ownerStore, v.ownerSlot, sc.viewParams, sc.gridCounts)
            .task("motPublish", MotorStore::publishToBodyView, mot.head, mot.reach, v.center, v.boundingRadius, v.ownerStore, v.ownerSlot, mot.publishParams, mot.counts)
            .task("bodyCell", SpatialGrid::bodyCell, v.center, sc.gridParams, sc.gridDims, sc.gridCounts, sc.bodyCell)
            .task("chunkZero", SpatialGrid::gridChunkZero, sc.chunkParams, sc.gridDims, sc.chunkCellCount)
            .task("chunkHist", SpatialGrid::gridChunkHistogram, sc.bodyCell, sc.gridCounts, sc.chunkParams, sc.gridDims, sc.chunkCellCount)
            .task("chunkReduce", SpatialGrid::gridChunkReduce, sc.gridDims, sc.chunkParams, sc.chunkCellCount, sc.cellCount)
            .task("gScanLocal", SpatialGrid::gridScanLocal, sc.gridDims, sc.cellCount, sc.gridCellOffsets, sc.chunkSum)
            .task("gScanChunks", SpatialGrid::gridScanChunks, sc.gridDims, sc.chunkSum)
            .task("gScanAdd", SpatialGrid::gridScanAdd, sc.gridDims, sc.gridCellOffsets, sc.gridCellContents, sc.cellCount, sc.chunkSum)
            .task("chunkScatter", SpatialGrid::gridChunkScatter, sc.bodyCell, sc.gridCounts, sc.chunkParams, sc.gridDims, sc.gridCellOffsets, sc.gridCellContents, sc.chunkCellCount)
            .task("gridReach", BindingDetectionSystem::gridReachable, mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, sc.gridParams, sc.gridDims, sc.gridCellOffsets, sc.gridCellContents, v.ownerStore, v.ownerSlot, sc.reachSeg, sc.reachCount, mot.kinParams, mot.counts)
            .task("release", NucleotideCycleSystem::catchSlipRelease, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.cooldown, mot.stats, mot.capStats, mot.kinParams, mot.counts)
            .task("bind", BindingDetectionSystem::bindNearest, mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, sc.reachSeg, sc.reachCount, mot.boundSeg, mot.bindArc, mot.kinParams, mot.counts)
            .task("cycle", NucleotideCycleSystem::cycle, mot.nucleotideState, mot.boundSeg, mot.forceDotHist, mot.nucParams, mot.counts)
        // --- minifilament structure mechanics ---
            .task("zeroMot", ChainBendingForceSystem::zeroAccumulators, b.forceSum, b.torqueSum, mot.counts)
            .task("zeroBb", ChainBendingForceSystem::zeroAccumulators, bb.forceSum, bb.torqueSum, mini.bbCounts)
            .task("brownMot", BrownianForceSystem::brownianForce, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, b.brownTransScale, b.brownRotScale, mot.bodyParams, mot.counts)
            .task("brownBb", BrownianForceSystem::brownianForce, bb.randForce, bb.randTorque, bb.bTransGam, bb.bRotGam, bb.brownTransScale, bb.brownRotScale, mini.bbBodyParams, mini.bbCounts)
            .task("joints", MotorJointSystem::joints, b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, mot.nucleotideState, mot.jointParams, mot.counts)
            .task("dimer", DimerCouplingSystem::couple, b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, dim.motorA, dim.motorB, dim.parallel, dim.dimerParams, mot.boundSeg)
            .task("tether", MiniFilamentSystem::tether, b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, bb.coord, bb.uVec, mini.bbInvDragY, mini.headBackboneSlot, mini.motorA, mini.attachAxial, mini.miniData, mini.miniParams)
            .task("bbCsrZero", CrossBridgeSystem::csrChunkZero, sc.bbChunkParams, mini.miniCounts, sc.bbMatrix)
            .task("bbCsrHist", CrossBridgeSystem::csrChunkHistogram, mini.headBackboneSlot, mini.miniCounts, sc.bbChunkParams, sc.bbMatrix)
            .task("bbCsrReduce", CrossBridgeSystem::csrChunkReduce, mini.miniCounts, sc.bbChunkParams, sc.bbMatrix, mini.bbDimerCount)
            .task("bbScan", CrossBridgeSystem::csrScan, mini.miniCounts, mini.bbDimerCount, mini.bbDimerOffsets)
            .task("bbCsrScatter", CrossBridgeSystem::csrChunkScatter, mini.headBackboneSlot, mini.miniCounts, sc.bbChunkParams, mini.bbDimerOffsets, mini.bbDimerList, sc.bbMatrix)
            .task("bbGather", MiniFilamentSystem::backboneGather, mini.bbDimerOffsets, mini.bbDimerList, mini.miniData, bb.forceSum, bb.torqueSum, mini.miniCounts)
            .task("bond", CrossBridgeSystem::bondForces, b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength, mot.boundSeg, mot.bindArc, mot.nucleotideState, sc.bondData, sc.xbParams)
            .task("applyHead", CrossBridgeSystem::applyHeadForce, sc.bondData, b.forceSum, b.torqueSum, mot.counts)
            .task("integM", RigidRodLangevinIntegrationSystem::integrate, b.coord, b.uVec, b.yVec, b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, mot.bodyParams, mot.counts)
            .task("confineBb", ContainmentSystem::confine, bb.coord, bb.uVec, bb.segLength, bb.bTransGam, bb.forceSum, bb.torqueSum, sc.boxParams, mini.bbCounts)
            .task("integB", RigidRodLangevinIntegrationSystem::integrate, bb.coord, bb.uVec, bb.yVec, bb.forceSum, bb.torqueSum, bb.randForce, bb.randTorque, bb.bTransGam, bb.bRotGam, mini.bbBodyParams, mini.bbCounts)
            .task("deriveM", DerivedGeometrySystem::derive, b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts)
            .task("deriveB", DerivedGeometrySystem::derive, bb.coord, bb.uVec, bb.yVec, bb.zVec, bb.end1, bb.end2, bb.segLength, mini.bbCounts)
            .task("register", CrossBridgeSystem::registerForceDot, sc.bondData, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.counts)
        // --- filament: chain + crosslinker force + motor gather + containment + integrate ---
            .task("zeroFil", ChainBendingForceSystem::zeroAccumulators, f.forceSum, f.torqueSum, f.counts)
            .task("brownFil", BrownianForceSystem::brownianForce, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.brownTransScale, f.brownRotScale, f.params, f.counts)
            .task("chain", ChainBendingForceSystem::chainForces, f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide, f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts);
        if (XLINK && xl != null) {
            tg = tg
              .transferToDevice(DataTransferMode.FIRST_EXECUTION,
                    xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2, xl.linkState, xl.linkOrientSame, xl.activeLinkCount,
                    xl.xlinkData, xl.torqueMagHist, xl.torqueMagPlace, xl.xlParams, xl.torsionParams, xl.counts, xl.formCounts,
                    sc.segCountA, sc.segOffsetsA, sc.segIdxA, sc.xlChunkParamsA, sc.xlMatrixA,
                    sc.segCountB, sc.segOffsetsB, sc.segIdxB, sc.xlChunkParamsB, sc.xlMatrixB)
              .task("xlCount", CrosslinkerSystem::countActiveLinks, xl.linkState, xl.linkFilA, xl.linkFilB, xl.activeLinkCount, xl.formCounts)
              .task("xlForce", CrosslinkerSystem::linkForces, f.coord, f.uVec, f.end1, f.bTransGam, xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2, xl.activeLinkCount, xl.xlinkData, xl.xlParams)
              .task("xlTorsion", CrosslinkerSystem::linkTorsion, f.uVec, xl.linkFilA, xl.linkFilB, xl.linkState, xl.linkOrientSame, xl.torqueMagHist, xl.torqueMagPlace, xl.xlinkData, xl.torsionParams)
              .task("xlZeroA", CrossBridgeSystem::csrChunkZero, sc.xlChunkParamsA, xl.counts, sc.xlMatrixA)
              .task("xlHistA", CrossBridgeSystem::csrChunkHistogram, xl.linkFilA, xl.counts, sc.xlChunkParamsA, sc.xlMatrixA)
              .task("xlReduceA", CrossBridgeSystem::csrChunkReduce, xl.counts, sc.xlChunkParamsA, sc.xlMatrixA, sc.segCountA)
              .task("xlScanA", CrossBridgeSystem::csrScan, xl.counts, sc.segCountA, sc.segOffsetsA)
              .task("xlScatterA", CrossBridgeSystem::csrChunkScatter, xl.linkFilA, xl.counts, sc.xlChunkParamsA, sc.segOffsetsA, sc.segIdxA, sc.xlMatrixA)
              .task("xlGatherA", CrosslinkerSystem::segGatherA, sc.segOffsetsA, sc.segIdxA, xl.xlinkData, xl.linkState, f.forceSum, f.torqueSum, xl.counts)
              .task("xlZeroB", CrossBridgeSystem::csrChunkZero, sc.xlChunkParamsB, xl.counts, sc.xlMatrixB)
              .task("xlHistB", CrossBridgeSystem::csrChunkHistogram, xl.linkFilB, xl.counts, sc.xlChunkParamsB, sc.xlMatrixB)
              .task("xlReduceB", CrossBridgeSystem::csrChunkReduce, xl.counts, sc.xlChunkParamsB, sc.xlMatrixB, sc.segCountB)
              .task("xlScanB", CrossBridgeSystem::csrScan, xl.counts, sc.segCountB, sc.segOffsetsB)
              .task("xlScatterB", CrossBridgeSystem::csrChunkScatter, xl.linkFilB, xl.counts, sc.xlChunkParamsB, sc.segOffsetsB, sc.segIdxB, sc.xlMatrixB)
              .task("xlGatherB", CrosslinkerSystem::segGatherB, sc.segOffsetsB, sc.segIdxB, xl.xlinkData, xl.linkState, f.forceSum, f.torqueSum, xl.counts);
        }
        tg = tg
            .task("csrZero", CrossBridgeSystem::csrChunkZero, sc.csrChunkParams, mot.counts, sc.csrMatrix)
            .task("csrHist", CrossBridgeSystem::csrChunkHistogram, mot.boundSeg, mot.counts, sc.csrChunkParams, sc.csrMatrix)
            .task("csrReduce", CrossBridgeSystem::csrChunkReduce, mot.counts, sc.csrChunkParams, sc.csrMatrix, sc.segMotorCount)
            .task("csrScan", CrossBridgeSystem::csrScan, mot.counts, sc.segMotorCount, sc.segMotorOffsets)
            .task("csrScatter", CrossBridgeSystem::csrChunkScatter, mot.boundSeg, mot.counts, sc.csrChunkParams, sc.segMotorOffsets, sc.segMotorMyo, sc.csrMatrix)
            .task("filGather", CrossBridgeSystem::segGather, sc.segMotorOffsets, sc.segMotorMyo, sc.bondData, f.forceSum, f.torqueSum, mot.counts)
            .task("confineFil", ContainmentSystem::confine, f.coord, f.uVec, f.segLength, f.bTransGam, f.forceSum, f.torqueSum, sc.boxParams, f.counts)
            .task("integFil", RigidRodLangevinIntegrationSystem::integrate, f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts)
            .task("deriveFil", DerivedGeometrySystem::derive, f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts)
            .transferToHost(DataTransferMode.UNDER_DEMAND, f.coord, mot.boundSeg, sc.reachCount);

        // ---- worker grids ----
        int nM = mot.nMotors, nMB = b.n, nBb = bb.n, nD = dim.nDimers, nSeg = f.n, cap = sc.cap, totalCells = sc.totalCells;
        int numScanChunks = (totalCells + SpatialGrid.GRID_SCAN_CHUNK - 1) / SpatialGrid.GRID_SCAN_CHUNK;
        sched = new GridScheduler();
        for (String t : new String[]{ "publishHead","motPublish","gridReach","release","bind","cycle","bond","applyHead","register" }) addW("densecontract." + t, pad(nM));
        for (String t : new String[]{ "zeroMot","brownMot","joints","integM","deriveM" }) addW("densecontract." + t, pad(nMB));
        for (String t : new String[]{ "zeroBb","brownBb","confineBb","integB","deriveB","bbGather" }) addW("densecontract." + t, pad(nBb));
        for (String t : new String[]{ "dimer","tether" }) addW("densecontract." + t, pad(nD));
        for (String t : new String[]{ "filPublish","zeroFil","brownFil","chain","filGather","confineFil","integFil","deriveFil" }) addW("densecontract." + t, pad(nSeg));
        addW("densecontract.bodyCell", pad(cap));
        addW("densecontract.chunkZero", pad(sc.numBodyChunks * totalCells));
        addW("densecontract.chunkHist", pad(sc.numBodyChunks));
        addW("densecontract.chunkReduce", pad(totalCells));
        addW("densecontract.chunkScatter", pad(sc.numBodyChunks));
        addW("densecontract.gScanLocal", pad(numScanChunks));
        addW("densecontract.gScanAdd", pad(numScanChunks));
        addW("densecontract.csrZero", pad(sc.numMotorChunks * nSeg));
        addW("densecontract.csrHist", pad(sc.numMotorChunks));
        addW("densecontract.csrReduce", pad(nSeg));
        addW("densecontract.csrScatter", pad(sc.numMotorChunks));
        addW("densecontract.bbCsrZero", pad(sc.numBbChunks * mini.nBackbones));
        addW("densecontract.bbCsrHist", pad(sc.numBbChunks));
        addW("densecontract.bbCsrReduce", pad(mini.nBackbones));
        addW("densecontract.bbCsrScatter", pad(sc.numBbChunks));
        for (String t : new String[]{ "gScanChunks","csrScan","bbScan" }) addS("densecontract." + t);
        if (XLINK && xl != null) {
            addW("densecontract.xlForce", pad(xl.nLinks));
            addW("densecontract.xlTorsion", pad(xl.nLinks));
            addW("densecontract.xlZeroA", pad(sc.numXlChunks * nSeg));
            addW("densecontract.xlHistA", pad(sc.numXlChunks));
            addW("densecontract.xlReduceA", pad(nSeg));
            addW("densecontract.xlScatterA", pad(sc.numXlChunks));
            addW("densecontract.xlGatherA", pad(nSeg));
            addW("densecontract.xlZeroB", pad(sc.numXlChunks * nSeg));
            addW("densecontract.xlHistB", pad(sc.numXlChunks));
            addW("densecontract.xlReduceB", pad(nSeg));
            addW("densecontract.xlScatterB", pad(sc.numXlChunks));
            addW("densecontract.xlGatherB", pad(nSeg));
            addS("densecontract.xlCount");
            addS("densecontract.xlScanA");
            addS("densecontract.xlScanB");
        }
        return new TornadoExecutionPlan(tg.snapshot());
    }
    static int pad(int n) { return ((n + B - 1) / B) * B; }
    static void addW(String n, int g) { WorkerGrid w = new WorkerGrid1D(Math.max(B, g)); w.setLocalWork(B, 1, 1); sched.addWorkerGrid(n, w); }
    static void addS(String n) { WorkerGrid w = new WorkerGrid1D(1); w.setLocalWork(1, 1, 1); sched.addWorkerGrid(n, w); }

    static void report(String label, Scene sc, int steps, double sec) {
        int bound = 0; for (int m = 0; m < sc.mot.nMotors; m++) if (sc.mot.boundSeg.get(m) >= 0) bound++;
        boolean nan = false;
        for (int i = 0; i < 3 * sc.fil.n && !nan; i++) if (Float.isNaN(sc.fil.coord.get(i))) nan = true;
        int activeXl = sc.xl == null ? 0 : countActive(sc.xl);
        System.out.printf("  %s THROUGHPUT: %.1f steps/s (%.2f ms/step) at %d heads / %d filaments / %d minifils%n",
                label, steps / sec, 1000.0 * sec / steps, sc.mot.nMotors, sc.nFil, sc.nMini);
        System.out.printf("  avgBound=%d (%.3f/head)  activeXlinks=%d  NaN=%b%n", bound, bound / (double) sc.mot.nMotors, activeXl, nan);
    }

    // ============================================================== sanity (small scale)
    static void sanityCheck() {
        System.out.println("--- sanity: assemble + run a small scene (0.02×), check no-NaN + binding + xlinks ---");
        Scene sc = buildScene(0.02);
        System.out.printf("  %d filaments (%d segs), %d minifils, %d heads, %d xlinks%n", sc.nFil, sc.fil.n, sc.nMini, sc.mot.nMotors, sc.nLinks);
        for (int t = 0; t < 200; t++) cpuStep(sc, t);
        report("CPU", sc, 1, 1.0);
    }
}
