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
    static double REACH = 0.006, ALIGN_TOL = -0.4, KOFF = 100.0;   // PARITY: v1 Env.myoColTol = 0.006 µm (was 0.025 — 4.2× over-reach)
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
    static double XLINK_ON_RATE = 40.0;             // PARITY: v1 pf_1x xLinkOnRate = 40.0 (was 10.0 — 4× low pForm)
    static double XLINK_CONC = 1.0;                 // µM (the v1 dense fixture; -xlconc)
    static final double XL_MAX_ANGLE = 0.6;         // rad (dense-config aperture)
    static int XL_CHECK_INT = 100;                  // formation cadence (steps) ⇒ pForm = 1-exp(-kon*conc*dt*checkInt)

    static boolean cpu = true;                      // CPU is the priority runner (v1 baseline was CPU)
    static boolean cmpMode = false;                 // -cmp : run BOTH runners from the same IC, report Δ (CPU≡GPU gate)
    static boolean brownOff = false;                // -brownoff : zero all Brownian scales (deterministic bit-check IC)
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
                case "-cmp" -> cmpMode = true;
                case "-brownoff" -> brownOff = true;
                case "-devicecsr" -> CSRHOST = false;
                case "-steps" -> STEPS = Integer.parseInt(args[++i]);
                case "-nodes" -> N_NODES = Integer.parseInt(args[++i]);
                case "-nfil" -> N_FIL = Integer.parseInt(args[++i]);
                case "-nsing" -> N_SING = Integer.parseInt(args[++i]);
                case "-xlconc" -> XLINK_CONC = Double.parseDouble(args[++i]);
                case "-reach" -> REACH = Double.parseDouble(args[++i]);
                case "-xlonrate" -> XLINK_ON_RATE = Double.parseDouble(args[++i]);
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
        if (brownOff) zeroBrownian(s);
        System.out.printf("scene built: %d nodes, %d filament segments, %d myosins, %d crosslink slots%n%n",
                s.nNodes, activeSegments(s.fil), s.mot.nMotors, s.xl == null ? 0 : s.xl.nLinks);

        if (cmpMode) { runCmp(s, dt, STEPS); return; }
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

    // ====================================================================== SPLIT chained-graph device-resident GPU path
    // V2OneX is a clean SUBSET of FullSystemDemo: ONE motor population (the node-shell singlets, bound via the parallel
    // spatial GRID, NOT the brute node-aware path), crosslinkers ON, NO turnover/nucleation/free-minifilament. The
    // monolithic per-step sequence (cpuStep) exceeds TornadoVM's single-TaskGraph node capacity ("Graph resize not
    // implemented"), so it is cut into 5 chained TaskGraphs sharing the SoA on-device via persistOnDevice (producer) +
    // consumeFromDevice (consumer) under ONE TornadoExecutionPlan — a faithful PORT of FullSystemDemoHarness's
    // buildPlanSplit/stepSplit with the deletions noted in V2ONEX_GPU_FINDINGS.md.
    //   G0 fdBind   — grid binding (publish + grid build + reachable + release/bind + cycle)   (≈15 tasks)
    //   G1 fdStruct — zero/Brownian + node-shell structure (joints/dimer/tether/gather/bond)   (≈12 tasks)
    //   G2 fdFil    — chain F3/F4 + node-shell seg-gather + crosslinker force/torsion/2-pass   (≈19 tasks)
    //   G3 fdInteg  — containment + integrate + derive (node/mot/fil)                          (≈9 tasks)
    //   G4 fdXForm  — device filID + crosslinker FORMATION (cadence-gated SINK)                (≈26 tasks)
    static TornadoExecutionResult splitResBind, splitResFil, splitResInteg, splitResL;
    static int GI_BIND, GI_STRUCT, GI_FIL, GI_INTEG, GI_XFORM = -1;
    static int N_SPLIT;
    static String[] GNAME;
    static boolean CSRHOST = true;              // CSR-host default ON (-devicecsr ⇒ false): host-build the dynamic seg CSR
    static GridScheduler gsched;

    static Object[] cat(Object[] a, Object[] b) {
        Object[] r = new Object[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length); System.arraycopy(b, 0, r, a.length, b.length); return r;
    }

    /** Static node-attach CSR-inverse (attachNode never changes ⇒ host-compute ONCE; device skips ndHist/ndScan/ndScatter). */
    static void hostNodeCSR(Scene s) {
        NodeStore nd = s.node;
        CrossBridgeSystem.csrHistogram(nd.attachNode, nd.nodeCounts4, nd.nodeAttachCount);
        CrossBridgeSystem.csrScan(nd.nodeCounts4, nd.nodeAttachCount, nd.nodeAttachOffsets);
        CrossBridgeSystem.csrScatter(nd.attachNode, nd.nodeCounts4, nd.nodeAttachOffsets, nd.nodeAttachCount, nd.nodeAttachList);
    }
    /** Dynamic node-shell motor→segment CSR (keyed by boundSeg ⇒ host-build each step from the boundSeg pulled after
     *  fdBind; fdFil re-uploads segMotor* EVERY_EXECUTION before filGather. Bit-identical to the device single-thread csr*). */
    static void hostSegCSR(Scene s) {
        MotorStore mot = s.mot;
        CrossBridgeSystem.csrHistogram(mot.boundSeg, mot.counts, s.segMotorCount);
        CrossBridgeSystem.csrScan(mot.counts, s.segMotorCount, s.segMotorOffsets);
        CrossBridgeSystem.csrScatter(mot.boundSeg, mot.counts, s.segMotorOffsets, s.segMotorCount, s.segMotorMyo);
    }

    static TornadoExecutionPlan buildPlanSplit(Scene s) {
        MotorStore mot = s.mot; DimerStore dim = s.dim; NodeStore nd = s.node; FilamentStore f = s.fil;
        RigidRodBody b = mot.body, nb = nd.node; SpatialBodyView v = s.view;
        boolean xlDev = s.xl != null && XLINK_ON;

        // node-attach CSR is STATIC ⇒ host-precompute ONCE (device skips the 3 scans; nodeAttachOffsets/List uploaded
        // FIRST_EXECUTION from the host array).
        hostNodeCSR(s);

        // Tiny per-step counts/rates — re-uploaded EVERY_EXECUTION in EVERY graph (negligible bytes); NOT persisted.
        Object[] everyExec = { mot.counts, nd.nodeBodyCounts, f.counts };
        if (xlDev) everyExec = cat(everyExec, new Object[]{ s.xl.counts, s.xl.formCounts, s.fg.gridCounts });
        // Lever 2 (CSRHOST): the node-shell seg CSR is host-built each step, re-uploaded EVERY_EXECUTION into fdFil.
        Object[] segCsrHost = { s.segMotorCount, s.segMotorOffsets, s.segMotorMyo };

        // ---- per-graph first-USE buffer sets (deltas from FullSystemDemo's u1..uX; mot is the node-shell GRID motor) ----
        Object[] u0 = {   // G0 fdBind — grid binding of the node-shell heads against the IC filaments
                b.coord, b.uVec, b.segLength, mot.head, mot.uVec, mot.rodUVec, mot.boundSeg, mot.bindArc, mot.nucleotideState,
                mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.cooldown, mot.stats, mot.capStats, mot.kinParams, mot.nucParams, mot.publishParams,
                f.coord, f.segLength, f.end1, f.end2,
                s.reachSeg, s.reachCount, s.viewParams, s.gridParams, s.gridDims, s.gridCounts, s.bodyCell, s.chunkParams, s.chunkCellCount,
                s.cellCount, s.gridCellOffsets, s.chunkSum, s.gridCellContents,
                v.center, v.boundingRadius, v.ownerStore, v.ownerSlot };
        Object[] u1 = {   // G1 fdStruct — zero/Brownian + node-shell structure forces
                b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, b.brownTransScale, b.brownRotScale, b.coord, b.uVec, b.segLength, b.yVec,
                mot.bodyParams, mot.nucleotideState, mot.jointParams, mot.boundSeg, mot.bindArc,
                nb.forceSum, nb.torqueSum, nb.randForce, nb.randTorque, nb.bTransGam, nb.bRotGam, nb.brownTransScale, nb.brownRotScale, nb.coord, nb.uVec, nb.yVec,
                nd.nodeBodyParams, nd.nodeInvTransY, nd.attachKey, nd.radial, nd.attachCoeffK, nd.nodeData, nd.nodeParams,
                nd.nodeAttachOffsets, nd.nodeAttachList, nd.nodeCounts4,
                dim.motorA, dim.motorB, dim.parallel, dim.dimerParams,
                f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.brownTransScale, f.brownRotScale, f.params, f.coord, f.uVec, f.yVec, f.segLength,
                s.bondData, s.xbParams };
        Object[] u2 = {   // G2 fdFil — chain + node-shell seg-gather + crosslinker force/torsion/2-pass
                f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide, f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams,
                mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace,
                s.bondData };
        if (!CSRHOST) u2 = cat(u2, segCsrHost);   // baseline: node-shell seg CSR device-produced+persisted here
        if (xlDev) u2 = cat(u2, new Object[]{ s.segCountA, s.segOffsetsA, s.segIdxA, s.segCountB, s.segOffsetsB, s.segIdxB,
                s.xl.xlinkData, s.xl.xlParams, s.xl.offParams, s.xl.torsionParams,
                s.xl.linkState, s.xl.linkFilA, s.xl.linkFilB, s.xl.loc1, s.xl.loc2, s.xl.activeLinkCount,
                f.end1, s.xl.strainHist, s.xl.strainPlace, s.xl.linkOrientSame, s.xl.torqueMagHist, s.xl.torqueMagPlace });
        Object[] u3 = {   // G3 fdInteg — containment + integrate + derive
                nb.coord, nb.uVec, nb.segLength, nb.bTransGam, nb.forceSum, nb.torqueSum, nb.yVec, nb.randForce, nb.randTorque, nb.bRotGam, nb.zVec, nb.end1, nb.end2, nd.nodeBodyParams,
                b.coord, b.uVec, b.yVec, b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, b.zVec, b.end1, b.end2, b.segLength, mot.bodyParams,
                f.coord, f.uVec, f.segLength, f.bTransGam, f.forceSum, f.torqueSum, f.yVec, f.randForce, f.randTorque, f.bRotGam, f.params, f.zVec, f.end1, f.end2,
                s.boxParams };
        // G4 fdXForm (cadence-gated SINK) — device filID + the WHOLE crosslinker FORMATION pipeline. First-use = filID +
        // the FormationGrid's OWN grid + formation-only request/allocator scratch + formParams (NOT the shared link state,
        // uploaded by always-run fdFil; NOT f.end1/end2, uploaded by fdBind/fdFil).
        Object[] uX = !xlDev ? null : new Object[]{
                s.filID, s.filIDScratch,
                s.fg.view.center, s.fg.view.boundingRadius, s.fg.view.ownerStore, s.fg.view.ownerSlot, s.fg.viewParams,
                s.fg.gridParams, s.fg.gridDims, s.fg.bodyCell, s.fg.cellCount, s.fg.chunkSum, s.fg.chunkParams, s.fg.chunkCellCount,
                s.fg.gridCellOffsets, s.fg.gridCellContents, s.fg.candCountSeg, s.fg.candBaseSeg,
                s.xl.reqFilA, s.xl.reqFilB, s.xl.reqLoc1, s.xl.reqLoc2, s.xl.reqOrient, s.xl.gatePass, s.xl.minCand, s.xl.acceptFlag,
                s.xl.freeCount, s.xl.freeOffsets, s.xl.freeList, s.xl.freeScanCounts, s.xl.rankOffsets, s.xl.rankScanCounts, s.xl.allocCounts,
                s.xl.formParams };

        Object[][] U; String[] gname;
        if (xlDev) { U = new Object[][]{ u0, u1, u2, u3, uX }; gname = new String[]{ "fdBind", "fdStruct", "fdFil", "fdInteg", "fdXForm" }; }
        else       { U = new Object[][]{ u0, u1, u2, u3 };     gname = new String[]{ "fdBind", "fdStruct", "fdFil", "fdInteg" }; }
        N_SPLIT = gname.length; GNAME = gname;
        GI_BIND = GI_STRUCT = GI_FIL = GI_INTEG = -1; GI_XFORM = -1;
        for (int gi = 0; gi < N_SPLIT; gi++) switch (gname[gi]) {
            case "fdBind" -> GI_BIND = gi; case "fdStruct" -> GI_STRUCT = gi; case "fdFil" -> GI_FIL = gi;
            case "fdInteg" -> GI_INTEG = gi; case "fdXForm" -> GI_XFORM = gi;
        }

        // host pulls (UNDER_DEMAND): fdBind → boundSeg (CSR-host trigger) + nucleotideState (render); fdFil → crosslink
        // render state (always-run, current); fdInteg → derived geometry for the renderer.
        Object[] host0 = { mot.boundSeg, mot.nucleotideState };
        Object[] host2 = { nb.coord, f.coord, f.end1, f.end2, b.end1, b.end2 };
        Object[] hostXl = xlDev ? new Object[]{ s.xl.linkState, s.xl.linkFilA, s.xl.linkFilB, s.xl.loc1, s.xl.loc2, s.xl.linkOrientSame } : null;

        java.util.Set<Object> uploaded = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        TaskGraph[] tg = new TaskGraph[N_SPLIT];
        for (int gi = 0; gi < N_SPLIT; gi++) {
            java.util.List<Object> newBufs = new java.util.ArrayList<>();
            for (Object o : U[gi]) if (!uploaded.contains(o)) { newBufs.add(o); uploaded.add(o); }
            Object[] consumeSet = uploaded.stream().filter(o -> !newBufs.contains(o)).toArray();
            TaskGraph g = new TaskGraph(gname[gi]);
            if (gi > 0 && consumeSet.length > 0) g = g.consumeFromDevice(gname[gi - 1], consumeSet);
            if (!newBufs.isEmpty()) g = g.transferToDevice(DataTransferMode.FIRST_EXECUTION, newBufs.toArray());
            g = g.transferToDevice(DataTransferMode.EVERY_EXECUTION, everyExec);
            if (CSRHOST && gi == GI_FIL)   // re-upload the HOST-built node-shell seg CSR each step (filGather consumes it)
                g = g.transferToDevice(DataTransferMode.EVERY_EXECUTION, segCsrHost);
            g = switch (gname[gi]) {
                case "fdBind" -> blkBind(g, s); case "fdStruct" -> blkStruct(g, s);
                case "fdFil" -> blkFil(g, s); case "fdXForm" -> blkXForm(g, s); default -> blkInteg(g, s);
            };
            if (gi == GI_BIND) g = g.transferToHost(DataTransferMode.UNDER_DEMAND, host0);
            if (xlDev && gi == GI_FIL) g = g.transferToHost(DataTransferMode.UNDER_DEMAND, hostXl);
            if (gi == GI_INTEG) g = g.transferToHost(DataTransferMode.UNDER_DEMAND, host2);
            g = g.persistOnDevice(uploaded.toArray());
            tg[gi] = g;
        }

        buildSplitScheduler(s);
        uk.ac.manchester.tornado.api.ImmutableTaskGraph[] snaps = new uk.ac.manchester.tornado.api.ImmutableTaskGraph[N_SPLIT];
        for (int gi = 0; gi < N_SPLIT; gi++) snaps[gi] = tg[gi].snapshot();
        return new TornadoExecutionPlan(snaps);
    }

    // ---- the task blocks (verbatim methods + order from cpuStep) ----
    static TaskGraph blkBind(TaskGraph tg, Scene s) {
        MotorStore mot = s.mot; RigidRodBody b = mot.body; FilamentStore f = s.fil; SpatialBodyView v = s.view;
        return tg
            .task("publishHead", MotorStore::publishHeadFromBody, b.coord, b.uVec, b.segLength, mot.head, mot.uVec, mot.rodUVec, mot.counts)
            .task("filPublish", FilamentStore::publishToBodyView, f.coord, f.segLength, v.center, v.boundingRadius, v.ownerStore, v.ownerSlot, s.viewParams, s.gridCounts)
            .task("motPublish", MotorStore::publishToBodyView, mot.head, mot.reach, v.center, v.boundingRadius, v.ownerStore, v.ownerSlot, mot.publishParams, mot.counts)
            .task("bodyCell", SpatialGrid::bodyCell, v.center, s.gridParams, s.gridDims, s.gridCounts, s.bodyCell)
            .task("chunkZero", SpatialGrid::gridChunkZero, s.chunkParams, s.gridDims, s.chunkCellCount)
            .task("chunkHist", SpatialGrid::gridChunkHistogram, s.bodyCell, s.gridCounts, s.chunkParams, s.gridDims, s.chunkCellCount)
            .task("chunkReduce", SpatialGrid::gridChunkReduce, s.gridDims, s.chunkParams, s.chunkCellCount, s.cellCount)
            .task("gScanLocal", SpatialGrid::gridScanLocal, s.gridDims, s.cellCount, s.gridCellOffsets, s.chunkSum)
            .task("gScanChunks", SpatialGrid::gridScanChunks, s.gridDims, s.chunkSum)
            .task("gScanAdd", SpatialGrid::gridScanAdd, s.gridDims, s.gridCellOffsets, s.gridCellContents, s.cellCount, s.chunkSum)
            .task("chunkScatter", SpatialGrid::gridChunkScatter, s.bodyCell, s.gridCounts, s.chunkParams, s.gridDims, s.gridCellOffsets, s.gridCellContents, s.chunkCellCount)
            .task("gridReach", BindingDetectionSystem::gridReachable, mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, s.gridParams, s.gridDims, s.gridCellOffsets, s.gridCellContents, v.ownerStore, v.ownerSlot, s.reachSeg, s.reachCount, mot.kinParams, mot.counts)
            .task("release", NucleotideCycleSystem::catchSlipRelease, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.cooldown, mot.stats, mot.capStats, mot.kinParams, mot.counts)
            .task("bind", BindingDetectionSystem::bindNearest, mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, s.reachSeg, s.reachCount, mot.boundSeg, mot.bindArc, mot.kinParams, mot.counts)
            .task("cycle", NucleotideCycleSystem::cycle, mot.nucleotideState, mot.boundSeg, mot.forceDotHist, mot.nucParams, mot.counts);
    }

    static TaskGraph blkStruct(TaskGraph tg, Scene s) {
        MotorStore mot = s.mot; DimerStore dim = s.dim; NodeStore nd = s.node; FilamentStore f = s.fil;
        RigidRodBody b = mot.body, nb = nd.node;
        return tg
            .task("zeroMot", ChainBendingForceSystem::zeroAccumulators, b.forceSum, b.torqueSum, mot.counts)
            .task("zeroNode", ChainBendingForceSystem::zeroAccumulators, nb.forceSum, nb.torqueSum, nd.nodeBodyCounts)
            .task("zeroFil", ChainBendingForceSystem::zeroAccumulators, f.forceSum, f.torqueSum, f.counts)
            .task("brownMot", BrownianForceSystem::brownianForce, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, b.brownTransScale, b.brownRotScale, mot.bodyParams, mot.counts)
            .task("brownNode", BrownianForceSystem::brownianForce, nb.randForce, nb.randTorque, nb.bTransGam, nb.bRotGam, nb.brownTransScale, nb.brownRotScale, nd.nodeBodyParams, nd.nodeBodyCounts)
            .task("brownFil", BrownianForceSystem::brownianForce, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.brownTransScale, f.brownRotScale, f.params, f.counts)
            .task("joints", MotorJointSystem::joints, b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, mot.nucleotideState, mot.jointParams, mot.counts)
            .task("dimer", DimerCouplingSystem::couple, b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, dim.motorA, dim.motorB, dim.parallel, dim.dimerParams, mot.boundSeg)
            .task("tether", NodeSystem::tether, b.coord, b.uVec, b.segLength, b.bTransGam, b.forceSum, b.torqueSum, nb.coord, nb.uVec, nb.yVec, nd.nodeInvTransY, nd.attachKey, nd.radial, nd.attachCoeffK, nd.nodeData, nd.nodeParams)
            .task("ndGather", MiniFilamentSystem::backboneGather, nd.nodeAttachOffsets, nd.nodeAttachList, nd.nodeData, nb.forceSum, nb.torqueSum, nd.nodeCounts4)
            .task("bond", CrossBridgeSystem::bondForces, b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength, mot.boundSeg, mot.bindArc, mot.nucleotideState, s.bondData, s.xbParams)
            .task("applyHead", CrossBridgeSystem::applyHeadForce, s.bondData, b.forceSum, b.torqueSum, mot.counts);
    }

    static TaskGraph blkFil(TaskGraph tg, Scene s) {
        FilamentStore f = s.fil; MotorStore mot = s.mot;
        tg = tg
            .task("chain", ChainBendingForceSystem::chainForces, f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide, f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts);
        if (!CSRHOST)   // baseline: node-shell seg CSR device-produced (else host-built + EVERY_EXECUTION-uploaded)
            tg = tg
                .task("filHist", CrossBridgeSystem::csrHistogram, mot.boundSeg, mot.counts, s.segMotorCount)
                .task("filScan", CrossBridgeSystem::csrScan, mot.counts, s.segMotorCount, s.segMotorOffsets)
                .task("filScatter", CrossBridgeSystem::csrScatter, mot.boundSeg, mot.counts, s.segMotorOffsets, s.segMotorCount, s.segMotorMyo);
        tg = tg
            .task("filGather", CrossBridgeSystem::segGather, s.segMotorOffsets, s.segMotorMyo, s.bondData, f.forceSum, f.torqueSum, mot.counts)
            .task("register", CrossBridgeSystem::registerForceDot, s.bondData, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.counts);
        if (s.xl != null && XLINK_ON) {
            CrosslinkerStore xl = s.xl;
            tg = tg
                .task("xlUnbind", CrosslinkerSystem::unbind, f.coord, f.uVec, f.end1, xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2, xl.linkState, xl.strainHist, xl.strainPlace, xl.offParams, xl.counts)
                .task("xlCount", CrosslinkerSystem::countActiveLinks, xl.linkState, xl.linkFilA, xl.linkFilB, xl.activeLinkCount, xl.formCounts)
                .task("xlForce", CrosslinkerSystem::linkForces, f.coord, f.uVec, f.end1, f.bTransGam, xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2, xl.activeLinkCount, xl.xlinkData, xl.xlParams)
                .task("xlTorsion", CrosslinkerSystem::linkTorsion, f.uVec, xl.linkFilA, xl.linkFilB, xl.linkState, xl.linkOrientSame, xl.torqueMagHist, xl.torqueMagPlace, xl.xlinkData, xl.torsionParams)
                .task("xlHistA", CrossBridgeSystem::csrHistogram, xl.linkFilA, xl.counts, s.segCountA)
                .task("xlScanA", CrossBridgeSystem::csrScan, xl.counts, s.segCountA, s.segOffsetsA)
                .task("xlScatterA", CrossBridgeSystem::csrScatter, xl.linkFilA, xl.counts, s.segOffsetsA, s.segCountA, s.segIdxA)
                .task("xlGatherA", CrosslinkerSystem::segGatherA, s.segOffsetsA, s.segIdxA, xl.xlinkData, xl.linkState, f.forceSum, f.torqueSum, xl.counts)
                .task("xlHistB", CrossBridgeSystem::csrHistogram, xl.linkFilB, xl.counts, s.segCountB)
                .task("xlScanB", CrossBridgeSystem::csrScan, xl.counts, s.segCountB, s.segOffsetsB)
                .task("xlScatterB", CrossBridgeSystem::csrScatter, xl.linkFilB, xl.counts, s.segOffsetsB, s.segCountB, s.segIdxB)
                .task("xlGatherB", CrosslinkerSystem::segGatherB, s.segOffsetsB, s.segIdxB, xl.xlinkData, xl.linkState, f.forceSum, f.torqueSum, xl.counts);
        }
        return tg;
    }

    static TaskGraph blkInteg(TaskGraph tg, Scene s) {
        MotorStore mot = s.mot; NodeStore nd = s.node; FilamentStore f = s.fil;
        RigidRodBody b = mot.body, nb = nd.node;
        return tg
            .task("confineNode", ContainmentSystem::confine, nb.coord, nb.uVec, nb.segLength, nb.bTransGam, nb.forceSum, nb.torqueSum, s.boxParams, nd.nodeBodyCounts)
            .task("integNode", RigidRodLangevinIntegrationSystem::integrate, nb.coord, nb.uVec, nb.yVec, nb.forceSum, nb.torqueSum, nb.randForce, nb.randTorque, nb.bTransGam, nb.bRotGam, nd.nodeBodyParams, nd.nodeBodyCounts)
            .task("deriveNode", DerivedGeometrySystem::derive, nb.coord, nb.uVec, nb.yVec, nb.zVec, nb.end1, nb.end2, nb.segLength, nd.nodeBodyCounts)
            .task("integM", RigidRodLangevinIntegrationSystem::integrate, b.coord, b.uVec, b.yVec, b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, mot.bodyParams, mot.counts)
            .task("deriveM", DerivedGeometrySystem::derive, b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts)
            .task("confineFil", ContainmentSystem::confine, f.coord, f.uVec, f.segLength, f.bTransGam, f.forceSum, f.torqueSum, s.boxParams, f.counts)
            .task("integFil", RigidRodLangevinIntegrationSystem::integrate, f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts)
            .task("deriveFil", DerivedGeometrySystem::derive, f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
    }

    /** fdXForm — device filID (pointer-doubling) + the WHOLE crosslinker FORMATION pipeline. CADENCE-GATED SINK. */
    static TaskGraph blkXForm(TaskGraph tg, Scene s) {
        FilamentStore f = s.fil; CrosslinkerStore xl = s.xl; FormationGrid fg = s.fg;
        tg = tg.task("filidInit", FilIDSystem::init, f.filState, f.end2NbrSlot, s.filID, f.counts);
        IntArray a = s.filID, b = s.filIDScratch;
        for (int k = 0; k < s.filIDRounds; k++) { tg = tg.task("filidJump" + k, FilIDSystem::jump, a, b, f.filState, f.counts); IntArray tmp = a; a = b; b = tmp; }
        return tg
            .task("xfCount", CrosslinkerSystem::countActiveLinks, xl.linkState, xl.linkFilA, xl.linkFilB, xl.activeLinkCount, xl.formCounts)
            .task("xfPublish", FilamentStore::publishToBodyView, f.coord, f.segLength, fg.view.center, fg.view.boundingRadius, fg.view.ownerStore, fg.view.ownerSlot, fg.viewParams, fg.gridCounts)
            .task("xfBodyCell", SpatialGrid::bodyCell, fg.view.center, fg.gridParams, fg.gridDims, fg.gridCounts, fg.bodyCell)
            .task("xfChunkZero", SpatialGrid::gridChunkZero, fg.chunkParams, fg.gridDims, fg.chunkCellCount)
            .task("xfChunkHist", SpatialGrid::gridChunkHistogram, fg.bodyCell, fg.gridCounts, fg.chunkParams, fg.gridDims, fg.chunkCellCount)
            .task("xfChunkReduce", SpatialGrid::gridChunkReduce, fg.gridDims, fg.chunkParams, fg.chunkCellCount, fg.cellCount)
            .task("xfScanLocal", SpatialGrid::gridScanLocal, fg.gridDims, fg.cellCount, fg.gridCellOffsets, fg.chunkSum)
            .task("xfScanChunks", SpatialGrid::gridScanChunks, fg.gridDims, fg.chunkSum)
            .task("xfScanAdd", SpatialGrid::gridScanAdd, fg.gridDims, fg.gridCellOffsets, fg.gridCellContents, fg.cellCount, fg.chunkSum)
            .task("xfChunkScatter", SpatialGrid::gridChunkScatter, fg.bodyCell, fg.gridCounts, fg.chunkParams, fg.gridDims, fg.gridCellOffsets, fg.gridCellContents, fg.chunkCellCount)
            .task("xfFormCount", CrosslinkerSystem::gridFormCount, f.coord, f.segLength, s.filID, fg.gridParams, fg.gridDims, fg.gridCellOffsets, fg.gridCellContents, fg.candCountSeg, xl.formParams, xl.formCounts)
            .task("xfFormScan", CrosslinkerSystem::gridFormScan, fg.candCountSeg, fg.candBaseSeg, xl.reqFilA, xl.reqFilB, xl.formCounts)
            .task("xfFormEmit", CrosslinkerSystem::gridFormEmit, f.coord, f.segLength, s.filID, fg.gridParams, fg.gridDims, fg.gridCellOffsets, fg.gridCellContents, fg.candBaseSeg, fg.candCountSeg, xl.reqFilA, xl.reqFilB, xl.formParams, xl.formCounts)
            .task("xfGates", CrosslinkerSystem::formGates, f.uVec, f.end1, f.end2, f.segLength, xl.reqFilA, xl.reqFilB, xl.reqLoc1, xl.reqLoc2, xl.reqOrient, xl.gatePass, xl.formParams, xl.formCounts)
            .task("xfAdmitReduce", CrosslinkerSystem::formAdmitReduce, xl.reqFilA, xl.reqFilB, xl.gatePass, xl.minCand, xl.formCounts)
            .task("xfAdmit", CrosslinkerSystem::formAdmit, xl.reqFilA, xl.reqFilB, xl.reqLoc1, xl.reqLoc2, xl.gatePass, xl.minCand, xl.activeLinkCount, xl.linkState, xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2, xl.acceptFlag, xl.formParams, xl.formCounts)
            .task("xfFreeFlags", CrosslinkerSystem::freeFlags, xl.linkState, xl.freeCount, xl.allocCounts)
            .task("xfScanFree", CrossBridgeSystem::csrScan, xl.freeScanCounts, xl.freeCount, xl.freeOffsets)
            .task("xfFreeScatter", CrosslinkerSystem::freeScatter, xl.linkState, xl.freeOffsets, xl.freeList, xl.allocCounts)
            .task("xfScanRank", CrossBridgeSystem::csrScan, xl.rankScanCounts, xl.acceptFlag, xl.rankOffsets)
            .task("xfAllocate", CrosslinkerSystem::allocate, xl.reqFilA, xl.reqFilB, xl.reqLoc1, xl.reqLoc2, xl.rankOffsets, xl.freeList, xl.freeOffsets, xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2, xl.linkState, xl.strainHist, xl.strainPlace, xl.allocCounts)
            .task("xfPlaceOrient", CrosslinkerSystem::placeOrient, xl.reqOrient, xl.rankOffsets, xl.freeList, xl.freeOffsets, xl.linkOrientSame, xl.torqueMagHist, xl.torqueMagPlace, xl.allocCounts);
    }

    static int padW(int n) { return ((n + B - 1) / B) * B; }
    static void addW(String n, int g) { WorkerGrid w = new WorkerGrid1D(Math.max(B, g)); w.setLocalWork(B, 1, 1); gsched.addWorkerGrid(n, w); }
    static void addS(String n) { WorkerGrid w = new WorkerGrid1D(1); w.setLocalWork(1, 1, 1); gsched.addWorkerGrid(n, w); }

    /** Re-key every RNG/trig kernel's localWork=64 WorkerGrid under its NEW graph-name prefix (the CUDA-701 trap). */
    static void buildSplitScheduler(Scene s) {
        MotorStore mot = s.mot; NodeStore nd = s.node; FilamentStore f = s.fil; DimerStore dim = s.dim;
        RigidRodBody b = mot.body, nb = nd.node;
        int nM = mot.nMotors, nMB = b.n, nN = nb.n, C = f.n, nD = dim.nDimers, nA = nd.nAttach;
        int cap = s.viewCap, totalCells = s.totalCells, numScan = (totalCells + SpatialGrid.GRID_SCAN_CHUNK - 1) / SpatialGrid.GRID_SCAN_CHUNK;
        gsched = new GridScheduler();
        // G0 fdBind
        for (String t : new String[]{ "publishHead","gridReach","release","bind","cycle","motPublish" }) addW("fdBind." + t, padW(nM));
        addW("fdBind.filPublish", padW(C));
        addW("fdBind.bodyCell", padW(cap)); addW("fdBind.chunkZero", padW(s.numBodyChunks * totalCells));
        addW("fdBind.chunkHist", padW(s.numBodyChunks)); addW("fdBind.chunkReduce", padW(totalCells));
        addW("fdBind.chunkScatter", padW(s.numBodyChunks)); addW("fdBind.gScanLocal", padW(numScan)); addW("fdBind.gScanAdd", padW(numScan));
        addS("fdBind.gScanChunks");
        // G1 fdStruct
        for (String t : new String[]{ "zeroMot","brownMot","joints" }) addW("fdStruct." + t, padW(nMB));
        for (String t : new String[]{ "zeroNode","brownNode","ndGather" }) addW("fdStruct." + t, padW(nN));
        for (String t : new String[]{ "zeroFil","brownFil" }) addW("fdStruct." + t, padW(C));
        for (String t : new String[]{ "bond","applyHead" }) addW("fdStruct." + t, padW(nM));
        addW("fdStruct.dimer", padW(nD)); addW("fdStruct.tether", padW(nA));
        // G2 fdFil
        addW("fdFil.chain", padW(C)); addW("fdFil.filGather", padW(C)); addW("fdFil.register", padW(nM));
        if (!CSRHOST) for (String t : new String[]{ "filHist","filScan","filScatter" }) addS("fdFil." + t);
        // G3 fdInteg
        for (String t : new String[]{ "confineNode","integNode","deriveNode" }) addW("fdInteg." + t, padW(nN));
        for (String t : new String[]{ "integM","deriveM" }) addW("fdInteg." + t, padW(nMB));
        for (String t : new String[]{ "confineFil","integFil","deriveFil" }) addW("fdInteg." + t, padW(C));
        // crosslinker FORCE (fdFil) + FORMATION (fdXForm)
        if (s.xl != null && XLINK_ON) {
            FormationGrid fg = s.fg; int reqCap = s.xl.reqCap, nLk = s.xl.nLinks;
            int fgCells = fg.totalCells, fgChunks = fg.numBodyChunks, fgScan = (fgCells + SpatialGrid.GRID_SCAN_CHUNK - 1) / SpatialGrid.GRID_SCAN_CHUNK;
            addW("fdFil.xlUnbind", padW(nLk)); addS("fdFil.xlCount");
            addW("fdFil.xlForce", padW(nLk)); addW("fdFil.xlTorsion", padW(nLk));
            addS("fdFil.xlHistA"); addS("fdFil.xlScanA"); addS("fdFil.xlScatterA"); addW("fdFil.xlGatherA", padW(C));
            addS("fdFil.xlHistB"); addS("fdFil.xlScanB"); addS("fdFil.xlScatterB"); addW("fdFil.xlGatherB", padW(C));
            addW("fdXForm.filidInit", padW(C));
            for (int k = 0; k < s.filIDRounds; k++) addW("fdXForm.filidJump" + k, padW(C));
            addS("fdXForm.xfCount");
            addW("fdXForm.xfPublish", padW(C)); addW("fdXForm.xfBodyCell", padW(C));
            addW("fdXForm.xfChunkZero", padW(fgChunks * fgCells)); addW("fdXForm.xfChunkHist", padW(fgChunks));
            addW("fdXForm.xfChunkReduce", padW(fgCells)); addW("fdXForm.xfScanLocal", padW(fgScan));
            addS("fdXForm.xfScanChunks"); addW("fdXForm.xfScanAdd", padW(fgScan)); addW("fdXForm.xfChunkScatter", padW(fgChunks));
            addW("fdXForm.xfFormCount", padW(C)); addS("fdXForm.xfFormScan"); addW("fdXForm.xfFormEmit", padW(C));
            addW("fdXForm.xfGates", padW(reqCap)); addS("fdXForm.xfAdmitReduce"); addW("fdXForm.xfAdmit", padW(reqCap));
            addW("fdXForm.xfFreeFlags", padW(nLk)); addS("fdXForm.xfScanFree"); addS("fdXForm.xfFreeScatter"); addS("fdXForm.xfScanRank");
            addW("fdXForm.xfAllocate", padW(reqCap)); addW("fdXForm.xfPlaceOrient", padW(reqCap));
        }
    }

    /** One device-resident step across the chained graphs (mirrors cpuStep's task order + host bookkeeping). */
    static void stepSplit(Scene g, int t, double dt, TornadoExecutionPlan plan) {
        MotorStore mot = g.mot; NodeStore nd = g.node; FilamentStore f = g.fil;
        mot.setCounts(t, SEED, f.n); nd.setNodeBodyCounts(t, SEED_NODE); f.setCounts(t, SEED);
        boolean formFires = g.xl != null && XLINK_ON && t % XL_CHECK_INT == 0;
        if (formFires) g.xl.setFormStep(t, SEED);
        if (g.xl != null && XLINK_ON) g.xl.setCounts(t, SEED);
        for (int gi = 0; gi < N_SPLIT; gi++) {
            if (gi == GI_XFORM && !formFires) continue;   // CADENCE GATE — skip formation off-cadence (the SINK)
            TornadoExecutionResult r = plan.withGraph(gi).withGridScheduler(gsched).execute();
            if (gi == GI_BIND)      { splitResBind = r; if (CSRHOST) { r.transferToHost(mot.boundSeg); hostSegCSR(g); } }
            else if (gi == GI_FIL)  splitResFil = r;
            if (gi == GI_INTEG) splitResInteg = r;
            if (gi == N_SPLIT - 1) splitResL = r;
        }
    }

    static void pullRenderState(Scene g) {
        MotorStore mot = g.mot; NodeStore nd = g.node; FilamentStore f = g.fil; RigidRodBody b = mot.body, nb = nd.node;
        if (splitResBind != null) splitResBind.transferToHost(mot.boundSeg, mot.nucleotideState);
        if (splitResFil != null && g.xl != null && XLINK_ON)
            splitResFil.transferToHost(g.xl.linkState, g.xl.linkFilA, g.xl.linkFilB, g.xl.loc1, g.xl.loc2, g.xl.linkOrientSame);
        if (splitResInteg != null) splitResInteg.transferToHost(nb.coord, f.coord, f.end1, f.end2, b.end1, b.end2);
    }

    /** Zero ALL Brownian scales (deterministic IC for the CPU≡GPU bit-check). The Langevin scheme is then pure-force ⇒
     *  CPU and GPU must agree to float32 last-bit at short horizon (no chaotic decorrelation). */
    static void zeroBrownian(Scene s) {
        RigidRodBody b = s.mot.body, nb = s.node.node; FilamentStore f = s.fil;
        for (int i = 0; i < b.n; i++)  { b.brownTransScale.set(i, 0f);  b.brownRotScale.set(i, 0f); }
        for (int i = 0; i < nb.n; i++) { nb.brownTransScale.set(i, 0f); nb.brownRotScale.set(i, 0f); }
        for (int i = 0; i < f.n; i++)  { f.brownTransScale.set(i, 0f);  f.brownRotScale.set(i, 0f); }
        System.out.println("  -brownoff: all Brownian scales zeroed (deterministic bit-check IC)");
    }

    /** -cmp: run a CPU scene and an INDEPENDENT GPU scene (identical deterministic IC) in lockstep and report the
     *  max |Δ| in filament coord + the bound-head/link counts on each. Brownian-OFF ⇒ bit-exact; Brownian-ON ⇒
     *  aggregate-within-SEM (chaotic many-body decorrelation, the §8/CLAUDE CPU≡GPU standard). */
    static void runCmp(Scene cs, double dt, int M) {
        System.out.println("--- CPU≡GPU comparison (-cmp): two independent scenes, identical IC ---");
        Scene gs = build(dt);            // second scene, identical deterministic IC
        if (brownOff) zeroBrownian(gs);
        TornadoExecutionPlan plan;
        try { plan = buildPlanSplit(gs); }
        catch (Throwable e) { System.out.println("  GPU split build FAILED: " + e); e.printStackTrace(); return; }
        double worstCoord = 0; int worstStep = -1;
        for (int t = 0; t < M; t++) {
            cpuStep(cs, t);
            stepSplit(gs, t, dt, plan);
            // pull the GPU filament coord for the per-step diff (UNDER_DEMAND from fdInteg)
            if (splitResInteg != null) splitResInteg.transferToHost(gs.fil.coord);
            double d = maxDiff(cs.fil.coord, gs.fil.coord);
            if (d > worstCoord) { worstCoord = d; worstStep = t; }
            if (t % Math.max(1, M / 10) == 0 || t == M - 1) {
                pullRenderState(gs);
                System.out.printf("  step %-6d  CPU bound=%-5d links=%-4d | GPU bound=%-5d links=%-4d | bound-set Δ=%-4d | max|Δcoord|=%.3e µm (@%d)%n",
                        t, boundHeads(cs.mot), cs.xl == null ? 0 : activeLinks(cs.xl),
                        boundHeads(gs.mot), gs.xl == null ? 0 : activeLinks(gs.xl), boundSetDiff(cs.mot, gs.mot), worstCoord, worstStep);
            }
        }
        pullRenderState(gs);
        System.out.printf("%n  RESULT: max|Δ filament coord| over %d steps = %.3e µm (@step %d)%n", M, worstCoord, worstStep);
        System.out.printf("  CPU: bound=%d links=%d ; GPU: bound=%d links=%d%n",
                boundHeads(cs.mot), cs.xl == null ? 0 : activeLinks(cs.xl), boundHeads(gs.mot), gs.xl == null ? 0 : activeLinks(gs.xl));
        System.out.println(brownOff
                ? "  (Brownian OFF ⇒ deterministic: Δ should be float32 last-bit; bound/link sets should match exactly)"
                : "  (Brownian ON ⇒ chaotic many-body: Δ grows by Lyapunov decorrelation; aggregate counts agree within SEM)");
    }

    /** # of motors whose bound-vs-free state differs between the two runs (0 ⇒ bit-identical binding decisions). */
    static int boundSetDiff(MotorStore a, MotorStore b) {
        int n = a.nMotors, c = 0;
        for (int i = 0; i < n; i++) if ((a.boundSeg.get(i) >= 0) != (b.boundSeg.get(i) >= 0)) c++;
        return c;
    }

    static double maxDiff(FloatArray a, FloatArray b) {
        double m = 0; int n = Math.min(a.getSize(), b.getSize());
        for (int i = 0; i < n; i++) m = Math.max(m, Math.abs((double) a.get(i) - b.get(i)));
        return m;
    }

    static void runGpu(Scene s, double dt, int M) {
        System.out.println("--- GPU device-resident run (5 chained TaskGraphs: fdBind·fdStruct·fdFil·fdInteg·fdXForm[gated]) ---");
        System.out.println("    residency: persistOnDevice/consumeFromDevice; per-step host transfer = boundSeg (CSR-host) + render pulls; CSRHOST=" + CSRHOST);
        TornadoExecutionPlan plan;
        try { plan = buildPlanSplit(s); }
        catch (Throwable e) { System.out.println("  GPU split build FAILED: " + e); e.printStackTrace(); runCpu(s, dt, M); return; }

        boolean viz = vizDir != null;
        int vizEvery = Math.max(1, M / 300), frames = 0;
        if (viz) { new java.io.File(vizDir).mkdirs(); System.out.printf("  -3js: a frame every %d steps to %s%n", vizEvery, vizDir); }

        int warm = Math.min(20, M);
        try {
            for (int t = 0; t < warm; t++) stepSplit(s, t, dt, plan);
        } catch (Throwable e) { System.out.println("  *** GPU warmup FAILED at lowering/launch: " + e); e.printStackTrace(); throw new RuntimeException(e); }
        // pull state after warmup so the periodic reports are current
        long t0 = System.nanoTime();
        boolean stable = true; int every = Math.max(1, M / 10);
        for (int t = warm; t < M; t++) {
            stepSplit(s, t, dt, plan);
            if ((t % every == 0 || t == M - 1) || (viz && t % vizEvery == 0)) {
                pullRenderState(s);
                if (t % every == 0 || t == M - 1)
                    System.out.printf("  step %-7d  bound=%-6d  links=%-6d%n", t, boundHeads(s.mot), s.xl == null ? 0 : activeLinks(s.xl));
                if (viz && (t % vizEvery == 0 || t == M - 1)) writeFrame(vizDir, frames++, t, t * dt, s);
                if (!finite(s.fil) || !finite(s.mot.body) || !finite(s.node.node)) {
                    System.out.println("  *** NON-FINITE at step " + t + " — BLOW-UP ***"); stable = false; break;
                }
            }
        }
        double secs = (System.nanoTime() - t0) / 1e9;
        int measured = M - warm;
        pullRenderState(s);
        System.out.printf("%n  GPU: %d measured steps (warm %d excluded) in %.2fs = %.1f steps/s%n", measured, warm, secs, measured / secs);
        if (viz) System.out.printf("  -3js: wrote %d frames to %s%n", frames, vizDir);
        report(s, stable);
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
