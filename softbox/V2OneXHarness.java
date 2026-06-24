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
 * THE "1× CONTRACTILITY STANDARD" — a v1↔v2 parity benchmark scene.
 *
 * Reproduces the specific v1 reference scene in a shallow square slab:
 *   - box 7.071 × 7.071 × 0.5 µm (= 25 µm³),
 *   - 400 protein NODES, each carrying 24 singlet myosins + 0 dimers (9600 myosins total), randomly
 *     placed; FREE (mobile, box-confined) node bodies,
 *   - 1000 STATIC IC filaments × 10 segments (segment ≈ 0.176 µm, contour ≈ 1.76 µm), randomly placed
 *     + randomly oriented, fixed length — NO growth/depoly/treadmilling/aging/severing/nucleation,
 *   - crosslinkers ON (CrosslinkerSystem formation + force + Bell unbinding; xLinkConc 1.0 µM, dense
 *     on-rate preset),
 *   - aeta = Constants.aeta = 0.1 (NO drag rescale — the harness leaves the drag at the default),
 *   - myosin–filament binding ACTIVE (the node singlet myosins bind + cross-bridge the IC filaments;
 *     the nucleotide cycle / power stroke runs ⇒ contractile),
 *   - containment confining all bodies to the box; dt = 1e-5 s.
 *
 * PURE COMPOSITION of validated subsystems — NO new force law / gather / shared-kernel edit. New file
 * only. The construction mines FullSystemDemoHarness (the node-shell + crosslinker + containment wiring)
 * and ProteinNodeHarness / NodeContractileHarness (the node motor-bundle), but DROPS turnover,
 * nucleation, and free minifilaments. There is ONE shared FilamentStore holding the 1000 IC filaments;
 * both (a) the node myosins bind it (broad-phase/binding/cross-bridge) AND (b) the crosslinkers link it.
 *
 * BINDING SCALE: 9600 motors × 10000 segments ⇒ the O(N²) node-aware brute is intractable on CPU, so
 * the node-shell myosins use the SAME parallel spatial-grid fused binding path the free minifilaments
 * use at dense scale (gridReachable + bindNearest). The IC filaments are NOT node-nucleated
 * (seedNode = -1 everywhere) ⇒ node-awareness is moot here; the plain grid path is the right one.
 *
 * PER-STEP ORDER (faithful, mirroring FullSystemDemoHarness / CrosslinkerBundleHarness):
 *   zero accumulators → Brownian → chain (F3/F4) on filaments
 *   → crosslinker formation (cadence) + force + unbind
 *   → node tether + myosin binding + cross-bridge (nucleotide cycle + stroke) → node gather
 *   → integrate (filaments + node bodies + myosin sub-bodies) → derive → containment.
 *
 * Reports the scene size + steps/s for comparison vs the v1 baseline (v1 CPU 29 / GPU 22 steps/s @ 1×).
 */
public final class V2OneXHarness {

    static final int B = 64;
    static GridScheduler sched;
    static final double GOLDEN = 2.399963229728653;
    static final double SIN80 = Math.sin(Math.toRadians(80.0)), COS80 = Math.cos(Math.toRadians(80.0));

    // ---- seeds ----
    static final int SEED = 0x310501, SEED_NODE = 0x315C2F;

    // ---- the 1× scene (the v1 contractility standard) ----
    static double BOX_XY = 7.071;            // square side (µm) ⇒ 7.071² × 0.5 ≈ 25 µm³
    static double BOX_Z  = 0.5;
    static int    N_NODES = 400;
    static int    N_SING  = 24;              // singlet myosins / node
    static int    N_DIM   = 0;               // dimers / node (motorsPerNode = N_SING + 2*N_DIM = 24)
    static int    N_FIL   = 1000;            // IC filaments
    static int    SEG_PER_FIL = 10;          // segments / filament
    static final int FIL_MONO = 64;          // monomers / segment ⇒ segLen = 65*actinMonoRadius ≈ 0.1755 µm

    // ---- myosin / binding (shared, v1 contractility defaults) ----
    static final double MYO_SPRING = 1.0e-9, J1_FMT = 0.4;
    static double REACH = 0.025, ALIGN_TOL = -0.4, KOFF = 100.0;
    static double BROWN_TRANS = 1.0, BROWN_ROT = 0.3;
    static double NODE_BROWN = 0.03;

    // ---- crosslinkers (O(N) formation, dense/v1 preset, identical to CrosslinkerBundleHarness / FullSystemDemo) ----
    static boolean XLINK_ON = true;
    static final double REST_LEN = 0.0125, FRAC_MOVE = 0.4;
    static final double OFF_CONST = 1.0, OFF_COEFF = 1.0, OFF_EXP = 2.0;
    static final double FIL_TORQ_SPRING = 1.0e-19;
    static final double GRAB_DIST = 2.0 * Constants.actinMonoDiam;
    static final double MIN_SEP = 5.0 * Constants.actinMonoDiam;
    static final double MIN_FILLINK_SEP = 2.0 * Constants.actinMonoDiam;
    static final int    MAX_LINKS_ON_SEG = 10;
    static final double XLINK_ON_RATE = 10.0;       // Env xLinkOnRate
    static double XLINK_CONC = 1.0;                 // µM (the v1 dense fixture; -xlconc)
    static final double XL_MAX_ANGLE = 0.6;         // rad (dense-config aperture)
    static int XL_CHECK_INT = 100;                  // formation cadence (steps) ⇒ pForm = 1-exp(-kon*conc*dt*checkInt)

    static boolean cpu = true;                      // CPU is the priority runner (v1 baseline was CPU)
    static int STEPS = 200;
    static String vizDir = null;                    // -3js <dir>: host-side Three.js frame output (off by default)

    static final String[] STATE_NAME = { "NONE", "ATP", "ADPPi", "ADP" };

    static double pForm(double conc, double dtCheck) { return 1.0 - Math.exp(-XLINK_ON_RATE * conc * dtCheck); }

    public static void main(String[] args) {
        double dt = 1.0e-5;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-cpu" -> cpu = true;
                case "-gpu" -> cpu = false;
                case "-steps" -> STEPS = Integer.parseInt(args[++i]);
                case "-nodes" -> N_NODES = Integer.parseInt(args[++i]);
                case "-nfil" -> N_FIL = Integer.parseInt(args[++i]);
                case "-nsing" -> N_SING = Integer.parseInt(args[++i]);
                case "-xlconc" -> XLINK_CONC = Double.parseDouble(args[++i]);
                case "-noxlink" -> XLINK_ON = false;
                case "-box" -> BOX_XY = Double.parseDouble(args[++i]);
                case "-3js" -> vizDir = args[++i];
                default -> {}
            }
        }
        System.out.println("=== Soft Box — V2 1× CONTRACTILITY STANDARD (v1↔v2 parity benchmark) ===");
        System.out.printf("box: %.3f × %.3f × %.2f µm (= %.2f µm³); runner: %s; dt=%.0e%n",
                BOX_XY, BOX_XY, BOX_Z, BOX_XY * BOX_XY * BOX_Z, cpu ? "CPU sequential" : "GPU TaskGraph", dt);
        System.out.printf("nodes: %d × %d singlet + %d dimer myosins; filaments: %d × %d seg (%d mono/seg, segLen≈%.4f µm, contour≈%.3f µm)%n",
                N_NODES, N_SING, N_DIM, N_FIL, SEG_PER_FIL, FIL_MONO,
                (FIL_MONO + 1) * Constants.actinMonoRadius, SEG_PER_FIL * (FIL_MONO + 1) * Constants.actinMonoRadius);
        System.out.printf("crosslinkers: %s (xLinkConc=%.2g µM, pForm=%.5g, checkInt=%d); aeta=%.2g (default, no rescale)%n%n",
                XLINK_ON ? "ON" : "off", XLINK_CONC, pForm(XLINK_CONC, dt * XL_CHECK_INT), XL_CHECK_INT, Constants.aeta);

        Scene s = build(dt);
        System.out.printf("scene built: %d nodes, %d filament segments, %d myosins, %d crosslink slots%n%n",
                s.nNodes, activeSegments(s.fil), s.mot.nMotors, s.xl == null ? 0 : s.xl.nLinks);

        if (cpu) runCpu(s, dt, STEPS);
        else     runGpu(s, dt, STEPS);
    }

    // ====================================================================== scene
    static final class Scene {
        int nNodes;
        NodeStore node; MotorStore mot; DimerStore dim; int motorsPerNode;
        FloatArray bondData, xbParams;
        IntArray segMotorCount, segMotorOffsets, segMotorMyo;
        FilamentStore fil;
        // grid + body view (node-shell head binding against the filament network)
        SpatialBodyView view; FloatArray gridParams, viewParams; IntArray gridDims, gridCounts;
        IntArray bodyCell, cellCount, chunkSum, gridCellOffsets, gridCellContents, chunkParams, chunkCellCount;
        int numBodyChunks, totalCells, viewCap;
        IntArray reachSeg, reachCount;
        // crosslinkers
        CrosslinkerStore xl; FormationGrid fg; IntArray filID, filIDScratch; int filIDRounds;
        IntArray segCountA, segOffsetsA, segIdxA, segCountB, segOffsetsB, segIdxB;
        // containment
        FloatArray boxParams;
    }

    static Scene build(double dt) {
        Scene s = new Scene();
        s.nNodes = N_NODES;
        double half = 0.5 * BOX_XY, halfZ = 0.5 * BOX_Z;
        java.util.Random rng = new java.util.Random(0x31AC7E5L ^ (((long) N_NODES << 20) ^ N_FIL));

        // random node centres in the box (a margin off the walls so the radial brush sits inside)
        double[][] centers = new double[N_NODES][3];
        double m = NodeStore.NODE_RADIUS + MotorStore.ROD_LEN + (MotorStore.LEVER_LEN + MotorStore.HEAD_LEN) + 0.05;
        double mz = Math.min(halfZ - 0.02, m);
        for (int k = 0; k < N_NODES; k++) {
            centers[k][0] = (rng.nextDouble() - 0.5) * 2 * (half - m);
            centers[k][1] = (rng.nextDouble() - 0.5) * 2 * (half - m);
            centers[k][2] = (rng.nextDouble() - 0.5) * 2 * mz;
        }
        buildShells(s, dt, centers);

        // ---------------- 1000 static IC filaments, 10 segments each, random pose ----------------
        int nSeg = N_FIL * SEG_PER_FIL;
        FilamentStore f = new FilamentStore(nSeg, Math.max(1, nSeg));   // reqCap for crosslinker formation request arrays
        double L = (FIL_MONO + 1) * Constants.actinMonoRadius;          // segment length
        for (int fi = 0; fi < N_FIL; fi++) {
            // v1-faithful IC placement (FilSegment.makeRandomFilament, BoA-v1ref FilSegment.java:3242):
            // pick TWO random points in the box at chain-contour separation, axis = unit(p2-p1), centre =
            // midpoint. In the thin z-slab (halfZ=0.25) this biases the axis IN-PLANE exactly as v1 — the two
            // endpoints land inside the box, so the fixed-length chain FITS the slab (fixes the prior
            // uniform-orientation z-poke) and reproduces v1's filament-crossing statistics (→ crosslink work).
            double span = SEG_PER_FIL * L;
            double ux, uy, uz, cx, cy, cz;
            while (true) {
                double ax = (rng.nextDouble() - 0.5) * 2 * half, ay = (rng.nextDouble() - 0.5) * 2 * half, az = (rng.nextDouble() - 0.5) * 2 * halfZ;
                double bx = (rng.nextDouble() - 0.5) * 2 * half, by = (rng.nextDouble() - 0.5) * 2 * half, bz = (rng.nextDouble() - 0.5) * 2 * halfZ;
                double dx = bx - ax, dy = by - ay, dz = bz - az, d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (d < 0.90 * span || d > 1.10 * span) continue;   // reject until separation ≈ contour (v1's minL..maxL window)
                ux = dx / d; uy = dy / d; uz = dz / d;
                cx = 0.5 * (ax + bx); cy = 0.5 * (ay + by); cz = 0.5 * (az + bz);
                break;
            }
            double yx = -uy, yy = ux, yz = 0; double yn = Math.sqrt(yx * yx + yy * yy + yz * yz);
            if (yn < 1e-9) { yx = 0; yy = -uz; yz = uy; yn = Math.sqrt(yy * yy + yz * yz); }
            yx /= yn; yy /= yn; yz /= yn;
            double e1x = cx - 0.5 * span * ux, e1y = cy - 0.5 * span * uy, e1z = cz - 0.5 * span * uz;
            int base = fi * SEG_PER_FIL;
            for (int i = 0; i < SEG_PER_FIL; i++) {
                int sl = base + i;
                double ccx = e1x + (0.5 + i) * L * ux, ccy = e1y + (0.5 + i) * L * uy, ccz = e1z + (0.5 + i) * L * uz;
                f.monomerCount.set(sl, FIL_MONO);
                f.setCoord(sl, (float) ccx, (float) ccy, (float) ccz);
                f.setUVec(sl, (float) ux, (float) uy, (float) uz);
                f.setYVec(sl, (float) yx, (float) yy, (float) yz);
                f.brownTransScale.set(sl, (float) Constants.BTransCoeff);
                // rotational Brownian only on chain ends (interior set by F4 bending) — the standard chain convention
                boolean chainEnd = (i == 0) || (i == SEG_PER_FIL - 1);
                f.brownRotScale.set(sl, (float) (chainEnd ? Constants.BRotCoeff : 0.0));
                if (i > 0)               { f.end2NbrSlot.set(sl, sl - 1); f.end2NbrSide.set(sl, 0); }
                if (i < SEG_PER_FIL - 1) { f.end1NbrSlot.set(sl, sl + 1); f.end1NbrSide.set(sl, 1); }
            }
        }
        DragTensorSystem.run(f);
        // aeta = Constants.aeta (0.1) — NO rescale (the v1 1× reference uses the default viscosity).
        f.setParams(dt, Constants.brownianForceMag(dt));
        f.setChainParams(dt);
        DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
        s.fil = f;

        s.segMotorCount = new IntArray(nSeg); s.segMotorOffsets = new IntArray(nSeg + 1); s.segMotorMyo = new IntArray(s.mot.nMotors);

        // ---------------- grid + body view (node-shell head ↔ filament binding) ----------------
        buildGrid(s, dt, nSeg);

        // ---------------- crosslinkers (O(N) formation, dense preset) ----------------
        if (XLINK_ON) buildCrosslinkers(s, dt, nSeg);

        // ---------------- shallow containment box ----------------
        int checkInt = Math.max(1, (int) Math.round(1.0e-4 / dt));
        s.boxParams = FloatArray.fromElements(1.0e-4f, (float) BOX_XY, (float) BOX_XY, (float) BOX_Z,
                (float) Constants.radius, 0.5f, (float) checkInt, (float) dt);
        return s;
    }

    /** Node motor shells (Ring3x3 / FullSystemDemo buildShells, replicated; singlets only here). */
    static void buildShells(Scene s, double dt, double[][] centers) {
        int nNodes = centers.length;
        int nSing = N_SING, nDim = N_DIM, nChild = nSing + nDim;
        int motorsPerNode = nSing + 2 * nDim;
        s.motorsPerNode = motorsPerNode;
        int nMot = nNodes * motorsPerNode, nDimers = Math.max(1, nNodes * nDim), nAtt = nNodes * nChild;
        double R = NodeStore.NODE_RADIUS;

        MotorStore mot = new MotorStore(nMot);
        DimerStore dim = new DimerStore(nDimers);
        NodeStore node = new NodeStore(nNodes, nAtt);

        for (int k = 0; k < nNodes; k++) {
            double cx = centers[k][0], cy = centers[k][1], cz = centers[k][2];
            node.node.setCoord(k, (float) cx, (float) cy, (float) cz);
            node.node.setUVec(k, 1f, 0f, 0f); node.node.setYVec(k, 0f, 1f, 0f);
            node.node.brownTransScale.set(k, (float) NODE_BROWN); node.node.brownRotScale.set(k, (float) NODE_BROWN);
            for (int c = 0; c < nChild; c++) {
                double yy = 1.0 - 2.0 * (c + 0.5) / nChild;
                double rr = Math.sqrt(Math.max(0.0, 1.0 - yy * yy));
                double phi = c * GOLDEN;
                double ux = rr * Math.cos(phi), uy = yy, uz = rr * Math.sin(phi);
                double sx = cx + R * ux, sy = cy + R * uy, sz = cz + R * uz;
                int att = k * nChild + c;
                if (c < nSing) {
                    int mm = k * motorsPerNode + c;
                    mot.assembleArticulated(mm, (float) sx, (float) sy, (float) sz, (float) ux, (float) uy, (float) uz, (float) BROWN_TRANS);
                    int rod = mot.rodIdx(mm), head = mot.headIdx(mm);
                    mot.body.brownRotScale.set(rod, (float) BROWN_ROT); mot.body.brownRotScale.set(head, (float) BROWN_ROT);
                    double coeff = NodeStore.ATTN_FORCE / nSing;
                    node.attach(att, k, mm, R * ux, R * uy, R * uz, coeff, false);
                } else {
                    int jj = c - nSing;
                    int mA = k * motorsPerNode + nSing + 2 * jj, mB = mA + 1, gd = k * nDim + jj;
                    placeDimerAlong(mot, mA, mB, sx, sy, sz, ux, uy, uz);
                    dim.pair(gd, mA, mB, true);
                    double coeff = NodeStore.ATTN_FORCE * NodeStore.DIMER_FRACMOVE;
                    node.attach(att, k, mA, R * ux, R * uy, R * uz, coeff, true);
                }
            }
        }
        DragTensorSystem.run(mot);
        node.initNodeDrag();
        mot.setBodyParams(dt); mot.setJointParams(dt); mot.setKinParams(REACH, ALIGN_TOL, dt); mot.setNucParams(dt);
        mot.kinParams.set(0, (float) KOFF);
        mot.setFaithfulRelease(true, 0.0);             // inherit the v1 12 pN break-force cap (faithful)
        mot.nucleotideState.init(MotorStore.NUC_NONE);
        dim.setDimerParams(dt);
        node.setNodeParams(dt); node.setNodeBodyParams(dt);
        DerivedGeometrySystem.derive(node.node.coord, node.node.uVec, node.node.yVec, node.node.zVec,
                node.node.end1, node.node.end2, node.node.segLength, node.nodeBodyCounts);

        s.bondData = new FloatArray(nMot * CrossBridgeSystem.STRIDE); s.bondData.init(0f);
        s.xbParams = FloatArray.fromElements((float) MYO_SPRING, 90f, (float) J1_FMT, (float) dt, (float) MotorStore.HEAD_LEN, 0f);
        int MAXC = SpatialGrid.MAX_CAND;
        s.reachSeg = new IntArray(nMot * MAXC); s.reachSeg.init(-1); s.reachCount = new IntArray(nMot);
        s.node = node; s.mot = mot; s.dim = dim;
    }

    /** The spatial grid + body view for node-shell head binding against the filament network
     *  (FullSystemDemo.buildMinifilaments grid block, replicated; the dense parallel binding path). */
    static void buildGrid(Scene s, double dt, int nSeg) {
        MotorStore mot = s.mot;
        int nMot = mot.nMotors;
        mot.setBaseSlot(nSeg);                  // node-shell heads at view slots [nSeg, nSeg+nMot)
        int viewCap = nSeg + nMot;
        SpatialBodyView view = new SpatialBodyView(viewCap); view.count = viewCap;
        double half = 0.5 * BOX_XY;
        double segBoundR = 0.5 * (FIL_MONO + 1) * Constants.actinMonoRadius + Constants.radius;
        double cutoff = REACH + 0.5 * MotorStore.ROD_LEN;
        double cellSize = 2.0 * segBoundR + cutoff;
        double gx = half + 0.5, gy = gx, gz = 0.5 * BOX_Z + 0.3;
        int nX = 1 + (int) Math.ceil(2 * gx / cellSize), nY = 1 + (int) Math.ceil(2 * gy / cellSize), nZ = 1 + (int) Math.ceil(2 * gz / cellSize);
        int totalCells = nX * nY * nZ;
        s.gridParams = FloatArray.fromElements((float) -gx, (float) -gy, (float) -gz, (float) cellSize, (float) (1.0 / cellSize), (float) cutoff);
        s.gridDims = IntArray.fromElements(nX, nY, nZ, totalCells);
        s.gridCounts = new IntArray(4); s.gridCounts.set(1, viewCap);
        s.viewParams = FloatArray.fromElements((float) Constants.radius);
        s.bodyCell = new IntArray(viewCap); s.bodyCell.init(-1);
        s.cellCount = new IntArray(totalCells);
        s.chunkSum = new IntArray((totalCells + SpatialGrid.GRID_SCAN_CHUNK - 1) / SpatialGrid.GRID_SCAN_CHUNK + 1);
        int gbcs = SpatialGrid.bodyChunkSize(viewCap, totalCells);
        s.numBodyChunks = SpatialGrid.numBodyChunks(viewCap, gbcs);
        s.chunkParams = IntArray.fromElements(gbcs, s.numBodyChunks);
        s.chunkCellCount = new IntArray(s.numBodyChunks * totalCells); s.chunkCellCount.init(0);
        s.gridCellOffsets = new IntArray(totalCells + 1);
        s.gridCellContents = new IntArray(viewCap); s.gridCellContents.init(-1);
        s.view = view; s.viewCap = viewCap; s.totalCells = totalCells;
    }

    /** Crosslinker store + the O(N) formation grid (FullSystemDemo.buildCrosslinkers, replicated). */
    static void buildCrosslinkers(Scene s, double dt, int nSeg) {
        int C = Math.max(256, nSeg * 4);
        int reqCap = nSeg * CrosslinkerSystem.FORM_MAXC;
        CrosslinkerStore xl = new CrosslinkerStore(C, nSeg, reqCap);
        xl.setParams(REST_LEN, FRAC_MOVE, dt);
        xl.setOffParams(OFF_CONST, OFF_COEFF, OFF_EXP, dt, REST_LEN);
        xl.setFormParams(XL_MAX_ANGLE, GRAB_DIST, MIN_SEP, MAX_LINKS_ON_SEG, pForm(XLINK_CONC, dt * XL_CHECK_INT), MIN_FILLINK_SEP, 0);
        xl.setRequestCount(reqCap);
        xl.setTorsionParams(FIL_TORQ_SPRING, true);
        s.filID = new IntArray(nSeg);
        s.filIDScratch = new IntArray(nSeg);
        s.filIDRounds = FilIDSystem.rounds(nSeg);
        s.segCountA = new IntArray(nSeg); s.segOffsetsA = new IntArray(nSeg + 1); s.segIdxA = new IntArray(C);
        s.segCountB = new IntArray(nSeg); s.segOffsetsB = new IntArray(nSeg + 1); s.segIdxB = new IntArray(C);
        double maxSegLen = (FIL_MONO + 1) * Constants.actinMonoRadius;
        double cellSize = 2.0 * (0.5 * maxSegLen + Constants.radius) + GRAB_DIST;
        double gx = 0.5 * BOX_XY + cellSize, gz = 0.5 * BOX_Z + cellSize;
        s.fg = new FormationGrid(nSeg, gx, gx, gz, cellSize, GRAB_DIST, Constants.radius);
        s.xl = xl;
    }

    // ====================================================================== one CPU step
    static void cpuStep(Scene s, int t) {
        MotorStore mot = s.mot; DimerStore dim = s.dim; NodeStore nd = s.node;
        FilamentStore f = s.fil; CrosslinkerStore xl = s.xl;
        RigidRodBody b = mot.body, nb = nd.node;
        int nSeg = f.n;
        mot.setCounts(t, SEED, nSeg); nd.setNodeBodyCounts(t, SEED_NODE); f.setCounts(t, SEED);

        // === BINDING — node shell via the parallel spatial grid (IC fils are seedNode=-1 ⇒ plain grid path) ===
        MotorStore.publishHeadFromBody(b.coord, b.uVec, b.segLength, mot.head, mot.uVec, mot.rodUVec, mot.counts);
        FilamentStore.publishToBodyView(f.coord, f.segLength, s.view.center, s.view.boundingRadius, s.view.ownerStore, s.view.ownerSlot, s.viewParams, s.gridCounts);
        MotorStore.publishToBodyView(mot.head, mot.reach, s.view.center, s.view.boundingRadius, s.view.ownerStore, s.view.ownerSlot, mot.publishParams, mot.counts);
        SpatialGrid.bodyCell(s.view.center, s.gridParams, s.gridDims, s.gridCounts, s.bodyCell);
        SpatialGrid.gridChunkZero(s.chunkParams, s.gridDims, s.chunkCellCount);
        SpatialGrid.gridChunkHistogram(s.bodyCell, s.gridCounts, s.chunkParams, s.gridDims, s.chunkCellCount);
        SpatialGrid.gridChunkReduce(s.gridDims, s.chunkParams, s.chunkCellCount, s.cellCount);
        SpatialGrid.gridScanLocal(s.gridDims, s.cellCount, s.gridCellOffsets, s.chunkSum);
        SpatialGrid.gridScanChunks(s.gridDims, s.chunkSum);
        SpatialGrid.gridScanAdd(s.gridDims, s.gridCellOffsets, s.gridCellContents, s.cellCount, s.chunkSum);
        SpatialGrid.gridChunkScatter(s.bodyCell, s.gridCounts, s.chunkParams, s.gridDims, s.gridCellOffsets, s.gridCellContents, s.chunkCellCount);
        BindingDetectionSystem.gridReachable(mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, s.gridParams, s.gridDims,
                s.gridCellOffsets, s.gridCellContents, s.view.ownerStore, s.view.ownerSlot, s.reachSeg, s.reachCount, mot.kinParams, mot.counts);
        NucleotideCycleSystem.catchSlipRelease(mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.cooldown, mot.stats, mot.capStats, mot.kinParams, mot.counts);
        BindingDetectionSystem.bindNearest(mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, s.reachSeg, s.reachCount, mot.boundSeg, mot.bindArc, mot.kinParams, mot.counts);
        NucleotideCycleSystem.cycle(mot.nucleotideState, mot.boundSeg, mot.forceDotHist, mot.nucParams, mot.counts);

        // === CROSSLINKER FORMATION (O(N) grid, at the formation cadence) + UNBIND ===
        if (xl != null && t % XL_CHECK_INT == 0) formationCpu(s, t);
        if (xl != null) {
            xl.setCounts(t, SEED);
            CrosslinkerSystem.unbind(f.coord, f.uVec, f.end1, xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2,
                    xl.linkState, xl.strainHist, xl.strainPlace, xl.offParams, xl.counts);
            CrosslinkerSystem.countActiveLinks(xl.linkState, xl.linkFilA, xl.linkFilB, xl.activeLinkCount, xl.formCounts);
        }

        // === FORCES — zero all accumulators, then every coupling accumulates into f.forceSum ===
        ChainBendingForceSystem.zeroAccumulators(b.forceSum, b.torqueSum, mot.counts);
        ChainBendingForceSystem.zeroAccumulators(nb.forceSum, nb.torqueSum, nd.nodeBodyCounts);
        ChainBendingForceSystem.zeroAccumulators(f.forceSum, f.torqueSum, f.counts);
        BrownianForceSystem.brownianForce(b.randForce, b.randTorque, b.bTransGam, b.bRotGam, b.brownTransScale, b.brownRotScale, mot.bodyParams, mot.counts);
        BrownianForceSystem.brownianForce(nb.randForce, nb.randTorque, nb.bTransGam, nb.bRotGam, nb.brownTransScale, nb.brownRotScale, nd.nodeBodyParams, nd.nodeBodyCounts);
        BrownianForceSystem.brownianForce(f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.brownTransScale, f.brownRotScale, f.params, f.counts);

        // filament chain (F3/F4)
        ChainBendingForceSystem.chainForces(f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide, f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts);

        // node-shell structure: joints + dimer + radial tether + node gather + cross-bridge
        MotorJointSystem.joints(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, mot.nucleotideState, mot.jointParams, mot.counts);
        DimerCouplingSystem.couple(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, dim.motorA, dim.motorB, dim.parallel, dim.dimerParams, mot.boundSeg);
        NodeSystem.tether(b.coord, b.uVec, b.segLength, b.bTransGam, b.forceSum, b.torqueSum,
                nb.coord, nb.uVec, nb.yVec, nd.nodeInvTransY, nd.attachKey, nd.radial, nd.attachCoeffK, nd.nodeData, nd.nodeParams);
        CrossBridgeSystem.csrHistogram(nd.attachNode, nd.nodeCounts4, nd.nodeAttachCount);
        CrossBridgeSystem.csrScan(nd.nodeCounts4, nd.nodeAttachCount, nd.nodeAttachOffsets);
        CrossBridgeSystem.csrScatter(nd.attachNode, nd.nodeCounts4, nd.nodeAttachOffsets, nd.nodeAttachCount, nd.nodeAttachList);
        MiniFilamentSystem.backboneGather(nd.nodeAttachOffsets, nd.nodeAttachList, nd.nodeData, nb.forceSum, nb.torqueSum, nd.nodeCounts4);
        CrossBridgeSystem.bondForces(b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength,
                mot.boundSeg, mot.bindArc, mot.nucleotideState, s.bondData, s.xbParams);
        CrossBridgeSystem.applyHeadForce(s.bondData, b.forceSum, b.torqueSum, mot.counts);

        // node-shell motor → segment gather
        CrossBridgeSystem.csrHistogram(mot.boundSeg, mot.counts, s.segMotorCount);
        CrossBridgeSystem.csrScan(mot.counts, s.segMotorCount, s.segMotorOffsets);
        CrossBridgeSystem.csrScatter(mot.boundSeg, mot.counts, s.segMotorOffsets, s.segMotorCount, s.segMotorMyo);
        CrossBridgeSystem.segGather(s.segMotorOffsets, s.segMotorMyo, s.bondData, f.forceSum, f.torqueSum, mot.counts);

        // crosslinker force + torsion + 2-pass gather
        if (xl != null) {
            CrosslinkerSystem.linkForces(f.coord, f.uVec, f.end1, f.bTransGam, xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2, xl.activeLinkCount, xl.xlinkData, xl.xlParams);
            CrosslinkerSystem.linkTorsion(f.uVec, xl.linkFilA, xl.linkFilB, xl.linkState, xl.linkOrientSame, xl.torqueMagHist, xl.torqueMagPlace, xl.xlinkData, xl.torsionParams);
            CrossBridgeSystem.csrHistogram(xl.linkFilA, xl.counts, s.segCountA);
            CrossBridgeSystem.csrScan(xl.counts, s.segCountA, s.segOffsetsA);
            CrossBridgeSystem.csrScatter(xl.linkFilA, xl.counts, s.segOffsetsA, s.segCountA, s.segIdxA);
            CrosslinkerSystem.segGatherA(s.segOffsetsA, s.segIdxA, xl.xlinkData, xl.linkState, f.forceSum, f.torqueSum, xl.counts);
            CrossBridgeSystem.csrHistogram(xl.linkFilB, xl.counts, s.segCountB);
            CrossBridgeSystem.csrScan(xl.counts, s.segCountB, s.segOffsetsB);
            CrossBridgeSystem.csrScatter(xl.linkFilB, xl.counts, s.segOffsetsB, s.segCountB, s.segIdxB);
            CrosslinkerSystem.segGatherB(s.segOffsetsB, s.segIdxB, xl.xlinkData, xl.linkState, f.forceSum, f.torqueSum, xl.counts);
        }
        CrossBridgeSystem.registerForceDot(s.bondData, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.counts);

        // === CONTAINMENT + INTEGRATE + DERIVE ===
        ContainmentSystem.confine(nb.coord, nb.uVec, nb.segLength, nb.bTransGam, nb.forceSum, nb.torqueSum, s.boxParams, nd.nodeBodyCounts);
        RigidRodLangevinIntegrationSystem.integrate(nb.coord, nb.uVec, nb.yVec, nb.forceSum, nb.torqueSum, nb.randForce, nb.randTorque, nb.bTransGam, nb.bRotGam, nd.nodeBodyParams, nd.nodeBodyCounts);
        DerivedGeometrySystem.derive(nb.coord, nb.uVec, nb.yVec, nb.zVec, nb.end1, nb.end2, nb.segLength, nd.nodeBodyCounts);
        RigidRodLangevinIntegrationSystem.integrate(b.coord, b.uVec, b.yVec, b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, mot.bodyParams, mot.counts);
        DerivedGeometrySystem.derive(b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts);
        ContainmentSystem.confine(f.coord, f.uVec, f.segLength, f.bTransGam, f.forceSum, f.torqueSum, s.boxParams, f.counts);
        RigidRodLangevinIntegrationSystem.integrate(f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts);
        DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
    }

    /** O(N) crosslinker formation on the host (FullSystemDemo.formationCpu, replicated). */
    static void formationCpu(Scene s, int step) {
        FilamentStore f = s.fil; CrosslinkerStore xl = s.xl;
        computeFilID(s);
        xl.setFormStep(step, SEED);
        CrosslinkerSystem.countActiveLinks(xl.linkState, xl.linkFilA, xl.linkFilB, xl.activeLinkCount, xl.formCounts);
        s.fg.buildCpu(f);
        s.fg.formCandidatesCpu(f, s.filID, xl);
        CrosslinkerSystem.formGates(f.uVec, f.end1, f.end2, f.segLength, xl.reqFilA, xl.reqFilB, xl.reqLoc1, xl.reqLoc2,
                xl.reqOrient, xl.gatePass, xl.formParams, xl.formCounts);
        CrosslinkerSystem.formAdmitReduce(xl.reqFilA, xl.reqFilB, xl.gatePass, xl.minCand, xl.formCounts);
        CrosslinkerSystem.formAdmit(xl.reqFilA, xl.reqFilB, xl.reqLoc1, xl.reqLoc2, xl.gatePass, xl.minCand, xl.activeLinkCount,
                xl.linkState, xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2, xl.acceptFlag, xl.formParams, xl.formCounts);
        CrosslinkerSystem.freeFlags(xl.linkState, xl.freeCount, xl.allocCounts);
        CrossBridgeSystem.csrScan(xl.freeScanCounts, xl.freeCount, xl.freeOffsets);
        CrosslinkerSystem.freeScatter(xl.linkState, xl.freeOffsets, xl.freeList, xl.allocCounts);
        CrossBridgeSystem.csrScan(xl.rankScanCounts, xl.acceptFlag, xl.rankOffsets);
        CrosslinkerSystem.allocate(xl.reqFilA, xl.reqFilB, xl.reqLoc1, xl.reqLoc2, xl.rankOffsets, xl.freeList, xl.freeOffsets,
                xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2, xl.linkState, xl.strainHist, xl.strainPlace, xl.allocCounts);
        CrosslinkerSystem.placeOrient(xl.reqOrient, xl.rankOffsets, xl.freeList, xl.freeOffsets, xl.linkOrientSame,
                xl.torqueMagHist, xl.torqueMagPlace, xl.allocCounts);
    }

    static void computeFilID(Scene s) {
        FilamentStore f = s.fil;
        FilIDSystem.init(f.filState, f.end2NbrSlot, s.filID, f.counts);
        IntArray a = s.filID, b = s.filIDScratch;
        for (int k = 0; k < s.filIDRounds; k++) { FilIDSystem.jump(a, b, f.filState, f.counts); IntArray tt = a; a = b; b = tt; }
    }

    // ====================================================================== runners
    static void runCpu(Scene s, double dt, int M) {
        System.out.println("--- CPU sequential run (the v1-comparable runner) ---");
        // -3js: host-side Three.js frame output (off by default; the benchmark path is byte-unaffected).
        boolean viz = vizDir != null;
        int vizEvery = Math.max(1, M / 300), frames = 0;
        if (viz) {
            new java.io.File(vizDir).mkdirs();
            System.out.printf("  -3js: writing a frame every %d steps to %s%n", vizEvery, vizDir);
        }
        // a couple of warm-up steps (JIT) excluded from the rate
        int warm = Math.min(5, M);
        for (int t = 0; t < warm; t++) cpuStep(s, t);
        long t0 = System.nanoTime();
        boolean stable = true;
        int every = Math.max(1, M / 10);
        for (int t = warm; t < M; t++) {
            cpuStep(s, t);
            if (viz && (t % vizEvery == 0 || t == M - 1)) writeFrame(vizDir, frames++, t, t * dt, s);
            if (t % every == 0 || t == M - 1) {
                System.out.printf("  step %-7d  bound=%-6d  links=%-6d  maxF=%.3g N%n",
                        t, boundHeads(s.mot), s.xl == null ? 0 : activeLinks(s.xl), maxAbs(s.fil.forceSum));
            }
            if (!finite(s.fil) || !finite(s.mot.body) || !finite(s.node.node)) {
                System.out.println("  *** NON-FINITE at step " + t + " — BLOW-UP ***"); stable = false; break;
            }
        }
        double secs = (System.nanoTime() - t0) / 1e9;
        int measured = M - warm;
        System.out.printf("%n  CPU: %d measured steps in %.2fs = %.1f steps/s%n", measured, secs, measured / secs);
        if (viz) System.out.printf("  -3js: wrote %d frames to %s%n", frames, vizDir);
        report(s, stable);
    }

    // ============================================================== viewer frame output (-3js)
    /** Emit one Three.js viewer frame (sim_viewer_boa.html schema): segments + all myosins (articulated
     *  rod/lever/motor with nucleotide state) + node spheres + active crosslinks. Reads the already-pulled
     *  host pose (no device sync); reuses the NodeContractileHarness writeFrame schema. New code here only —
     *  no shared store/system touched. */
    static void writeFrame(String dir, int frame, int step, double t, Scene s) {
        FilamentStore f = s.fil; MotorStore mot = s.mot; RigidRodBody b = mot.body; RigidRodBody nb = s.node.node;
        int nSeg = f.n;
        StringBuilder sb = new StringBuilder(1 << 20);
        sb.append(String.format(java.util.Locale.US,
                "{\"frame\":%d,\"t\":%.6g,\"bounds\":{\"xDim\":%.4g,\"yDim\":%.4g,\"zDim\":%.4g}",
                frame, t, BOX_XY, BOX_XY, BOX_Z));

        // --- segments (the IC filaments) ---
        sb.append(",\"segments\":[");
        for (int i = 0; i < nSeg; i++) {
            if (i > 0) sb.append(',');
            sb.append(String.format(java.util.Locale.US,
                "{\"id\":%d,\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.5g,\"notADPRatio\":1.0,\"cofilinCount\":0}",
                i, f.end1.get(i), f.end1.get(nSeg + i), f.end1.get(2 * nSeg + i),
                f.end2.get(i), f.end2.get(nSeg + i), f.end2.get(2 * nSeg + i), Constants.radius));
        }

        // --- myosins (all 9600 articulated motors: rod / lever / motor head, colored by state) ---
        sb.append("],\"myosins\":[");
        for (int m = 0; m < mot.nMotors; m++) {
            if (m > 0) sb.append(',');
            int rod = 3 * m, lever = 3 * m + 1, head = 3 * m + 2;
            String state = STATE_NAME[mot.nucleotideState.get(m)];
            sb.append(String.format(java.util.Locale.US,
                "{\"id\":%d,\"rod\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g,\"invisible\":false},"
                + "\"lever\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g},"
                + "\"motor\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g,\"state\":\"%s\"}}",
                m, b.end1X(rod), b.end1Y(rod), b.end1Z(rod), b.end2X(rod), b.end2Y(rod), b.end2Z(rod), MotorStore.ROD_R,
                b.end1X(lever), b.end1Y(lever), b.end1Z(lever), b.end2X(lever), b.end2Y(lever), b.end2Z(lever), MotorStore.LEVER_R,
                b.end1X(head), b.end1Y(head), b.end1Z(head), b.end2X(head), b.end2Y(head), b.end2Z(head), MotorStore.HEAD_R, state));
        }

        // --- nodes (the 400 protein-node spheres, the viewer's grey-sphere channel) ---
        sb.append("],\"nodes\":[");
        int nN = nb.n;
        for (int j = 0; j < s.nNodes; j++) {
            if (j > 0) sb.append(',');
            sb.append(String.format(java.util.Locale.US,
                "{\"id\":%d,\"center\":[%.5g,%.5g,%.5g],\"r\":%.4g}",
                j, nb.coord.get(j), nb.coord.get(nN + j), nb.coord.get(2 * nN + j), NodeStore.NODE_RADIUS));
        }

        // --- crosslinks (active links only; linkFilA/B are DIRECT segment indices ⇒ segment centres) ---
        sb.append("],\"crosslinks\":[");
        if (s.xl != null) {
            CrosslinkerStore xl = s.xl; boolean firstXl = true;
            for (int k = 0; k < xl.nLinks; k++) {
                if (xl.linkState.get(k) < 0) continue;          // ACTIVE only
                int a = xl.linkFilA.get(k), bSeg = xl.linkFilB.get(k);
                if (a < 0 || bSeg < 0) continue;
                if (!firstXl) sb.append(','); firstXl = false;
                sb.append(String.format(java.util.Locale.US,
                    "{\"a\":[%.5g,%.5g,%.5g],\"b\":[%.5g,%.5g,%.5g]}",
                    f.coordX(a), f.coordY(a), f.coordZ(a), f.coordX(bSeg), f.coordY(bSeg), f.coordZ(bSeg)));
            }
        }
        sb.append("]}");

        try { java.nio.file.Files.writeString(java.nio.file.Path.of(dir, String.format(java.util.Locale.US, "frame_%06d.json", frame)), sb.toString()); }
        catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }

    static void runGpu(Scene s, double dt, int M) {
        // GPU TODO: a device-resident TaskGraph for this maximal composition would (per CLAUDE.md) exceed
        // TornadoVM's single-TaskGraph node capacity ("Graph resize not implemented") and require the
        // chained-split path (the open FullSystemDemo GPU blocker). The CPU runner is the priority + the
        // v1-comparable baseline; GPU is left as a follow-on. Run -cpu.
        System.out.println("GPU device-resident path NOT wired (the maximal-composition Graph-resize blocker — see");
        System.out.println("CLAUDE.md / FULL_SYSTEM_DEMO_FINDINGS). Use -cpu (the priority runner). Falling back to CPU.");
        runCpu(s, dt, M);
    }

    static void report(Scene s, boolean stable) {
        // box-containment sanity: split the IN-PLANE (x,y) extent from the slab-thin z. A 1.755 µm filament
        // tilted out of plane genuinely cannot fit a 0.5 µm-deep slab, so z pokes are the geometric reality
        // (the box pushes centres in, stiff chains resist) — NOT an escape. The real containment test is x/y.
        FilamentStore f = s.fil; int n = f.n; double half = 0.5 * BOX_XY + 0.05, halfZ = 0.5 * BOX_Z + 0.05;
        int outXY = 0, outZ = 0; double maxXY = 0, maxZ = 0;
        for (int i = 0; i < n; i++) {
            double x = f.coordX(i), y = f.coordY(i), z = f.coordZ(i);
            if (Math.abs(x) > half || Math.abs(y) > half) outXY++;
            if (Math.abs(z) > halfZ) outZ++;
            maxXY = Math.max(maxXY, Math.max(Math.abs(x), Math.abs(y)));
            maxZ = Math.max(maxZ, Math.abs(z));
        }
        System.out.printf("  STABILITY: %s; bound heads=%d, crosslinks=%d%n",
                stable ? "stable (finite, bounded)" : "*BLEW UP*", boundHeads(s.mot), s.xl == null ? 0 : activeLinks(s.xl));
        System.out.printf("  containment: in-plane %d/%d seg-centres outside ±%.3f µm (max |x,y| = %.3f µm); z-poke %d/%d outside ±%.3f µm (max |z| = %.3f µm — slab-thin geometry, bounded)%n",
                outXY, n, half, maxXY, outZ, n, halfZ, maxZ);
    }

    // ====================================================================== utilities
    static int boundHeads(MotorStore m) { int c = 0; for (int i = 0; i < m.nMotors; i++) if (m.boundSeg.get(i) >= 0) c++; return c; }
    static int activeLinks(CrosslinkerStore xl) { int c = 0; for (int k = 0; k < xl.nLinks; k++) if (xl.linkState.get(k) >= 0) c++; return c; }
    static int activeSegments(FilamentStore f) { int c = 0; for (int s = 0; s < f.n; s++) if (f.filState.get(s) >= 0) c++; return c; }
    static double maxAbs(FloatArray a) { double m = 0; for (int i = 0; i < a.getSize(); i++) m = Math.max(m, Math.abs(a.get(i))); return m; }
    static boolean finite(FilamentStore f) { return finite(f.coord); }
    static boolean finite(RigidRodBody b) { return finite(b.coord); }
    static boolean finite(FloatArray a) {
        for (int i = 0; i < a.getSize(); i++) { float v = a.get(i); if (Float.isNaN(v) || Float.isInfinite(v)) return false; }
        return true;
    }

    // ---- node-shell dimer placement (FullSystemDemo.placeDimerAlong / placeArm) ----
    static void placeDimerAlong(MotorStore mot, int mA, int mB, double e1x, double e1y, double e1z, double dx, double dy, double dz) {
        double dm = Math.sqrt(dx*dx+dy*dy+dz*dz); dx/=dm; dy/=dm; dz/=dm;
        double px = -dz, py = 0, pz = dx; double pm = Math.sqrt(px*px+py*py+pz*pz);
        if (pm < 1e-4) { px = 1; py = 0; pz = 0; pm = 1; } px/=pm; py/=pm; pz/=pm;
        double rl = MotorStore.ROD_LEN, ll = MotorStore.LEVER_LEN, hl = MotorStore.HEAD_LEN;
        double rcx=e1x+0.5*rl*dx, rcy=e1y+0.5*rl*dy, rcz=e1z+0.5*rl*dz;
        double e2x=e1x+rl*dx, e2y=e1y+rl*dy, e2z=e1z+rl*dz;
        placeArm(mot, mA, rcx,rcy,rcz, dx,dy,dz, px,py,pz, e2x,e2y,e2z, +1, ll, hl);
        placeArm(mot, mB, rcx,rcy,rcz, dx,dy,dz, px,py,pz, e2x,e2y,e2z, -1, ll, hl);
    }
    static void placeArm(MotorStore mot, int m, double rcx, double rcy, double rcz, double dx, double dy, double dz,
                         double px, double py, double pz, double e2x, double e2y, double e2z, int splay, double ll, double hl) {
        int rod = mot.rodIdx(m), lever = mot.leverIdx(m), head = mot.headIdx(m); RigidRodBody b = mot.body;
        b.setCoord(rod, (float) rcx, (float) rcy, (float) rcz);
        b.setUVec(rod, (float) dx, (float) dy, (float) dz); b.setYVec(rod, (float) px, (float) py, (float) pz);
        double lux = COS80*dx + splay*SIN80*px, luy = COS80*dy + splay*SIN80*py, luz = COS80*dz + splay*SIN80*pz;
        double nx = dy*pz - dz*py, ny = dz*px - dx*pz, nz = dx*py - dy*px;
        double lcx = e2x + 0.5*ll*lux, lcy = e2y + 0.5*ll*luy, lcz = e2z + 0.5*ll*luz;
        b.setCoord(lever, (float) lcx, (float) lcy, (float) lcz);
        b.setUVec(lever, (float) lux, (float) luy, (float) luz); b.setYVec(lever, (float) nx, (float) ny, (float) nz);
        double le2x = e2x + ll*lux, le2y = e2y + ll*luy, le2z = e2z + ll*luz;
        double hcx = le2x + 0.5*hl*lux, hcy = le2y + 0.5*hl*luy, hcz = le2z + 0.5*hl*luz;
        b.setCoord(head, (float) hcx, (float) hcy, (float) hcz);
        b.setUVec(head, (float) lux, (float) luy, (float) luz); b.setYVec(head, (float) nx, (float) ny, (float) nz);
        b.brownTransScale.set(rod, (float) BROWN_TRANS);   b.brownRotScale.set(rod, (float) BROWN_ROT);
        b.brownTransScale.set(lever, 0f);                  b.brownRotScale.set(lever, 0f);
        b.brownTransScale.set(head, (float) BROWN_TRANS);  b.brownRotScale.set(head, (float) BROWN_ROT);
    }
}
