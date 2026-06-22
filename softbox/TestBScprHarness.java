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
 * Increment 6c — Test B: the SCPR primitive (two nodes capture-and-pull). The FIRST emergent test:
 * two myosin protein NODES, each formin-nucleating and elongating actin, that capture one another's
 * filaments and walk together (the minimal fission-yeast Search-Capture-Pull-Release primitive).
 *
 * Pure COMPOSITION over validated pieces — NO new force law, NO new gather, NO shared-kernel edit:
 *   - the node motor-bundle (NodeStore radial tether + the single-ended backbone-side CSR gather),
 *   - binding/gather (BindingDetectionSystem + CrossBridgeSystem) — VERIFIED seedNode-agnostic,
 *   - the 12 pN break-force cap + faithful catch-slip release,
 *   - B2 nucleation (NodeNucleationSystem) + polymerization growth (GrowthSystem),
 *   - general containment (ContainmentSystem), Test A's free-node integration, the ActinPool, wang-hash RNG.
 *
 * Staged as the task dictates:
 *   Stage 0 (GATING) — cross-node capture probe. Deterministic, static, no ensemble: plant ONE filament
 *     segment tagged seedNode=A inside node B's capture range; confirm a node-B motor binds it
 *     (boundSeg → A's filament) and that binding does NOT reject a foreign-node segment. Bit-identical
 *     CPU≡GPU. Gate 0 PASS ⇒ Stage 1; FAIL ⇒ hard-bail (commit nothing), report the exclusion path.
 *   Stage 1 — the two-node SCPR assay (only if Gate 0 passes): two free, box-confined nodes that
 *     nucleate + grow + capture + pull; readout = inter-node distance vs time + cross-node capture count.
 *
 * No v1 assay to match — adjudicated by physics + the SCPR behavior itself (do the nodes approach?).
 * Growth-only (hydrolysis / severing / depolymerization stay deferred — monotonic growth is what bridging needs).
 */
public final class TestBScprHarness {

    static final int B = 64;
    static GridScheduler sched;
    static boolean cpu = false;
    static final int SEED = 0x7E57B0;                         // "TESTB0"
    static final int SEED_NODE_A = 0x5C2FA1, SEED_NODE_B = 0x5C2FB2;
    static final double GOLDEN = 2.399963229728653;           // golden angle (Fibonacci sphere)
    static final double MYO_SPRING = 1.0e-9, J1_FMT = 0.4;
    static double REACH = 0.025;                              // myoColTol (bind/capture radius, µm)
    static double ALIGN_TOL = -0.4;                          // myoMotorAlignWithFilTolerance (v1 default)
    static double KOFF = 100.0;                               // catch-slip base off-rate (v1 default 100/s)
    static double BROWN_TRANS = 1.0, BROWN_ROT = 0.3;
    static double NODE_BROWN = 0.05;    // node-body Brownian scale (a node is a large/slow complex in vivo;
                                        // damping the tiny-sphere thermal wander resolves the directed pull) — tunable -nodebrown
    static int N_SING = 6, N_DIM = 6;                         // radial singlets + dimers PER node (seam #3 default = random radial)
    static double POLY_RATE = 1.0;                            // polymerization-rate scale (1.0 = default; -polyrate dials elongation)
    // ---- load-transmission EXPERIMENT (physics deviation from v1, authorized; default OFF=0) ----
    static int SCHEME = 0;            // 0=current(soft tether) 1=direct inject 2=bound-stiff tether 3=global stiffen(baseline)
    static double BOUND_COEFF = 0.07; // scheme 2/3 stiff load coeff (≈ the minifilament fixed 0.07)
    static FloatArray node2Params;    // scheme-2 tether params [0]=dt [1]=BOUND_COEFF
    static boolean V1_PAIRS = false;       // match the v1 twoNodeFormin PAIRS coeffs (fracMove 0.0573, fracR 1.0, fracMoveTorq 0.01)
    static int SEG_MONO = Constants.stdSegLength;   // monomers per aimed-filament segment (v1 twoNodeFormin used 64; v2 default 32)
    static double AIM_TORQUE = 0.0;        // aim-holding torque coeff (v1 nodeTorqSpring analog); 0 = off
    static FloatArray AIM_DIR;             // 3*filCap planar: per-segment aim target uVec (set at aimed placement)
    static FloatArray AIM_PARAMS;          // [0]=AIM_TORQUE coeff [1]=dt
    static final double SIN80 = Math.sin(Math.toRadians(80.0)), COS80 = Math.cos(Math.toRadians(80.0));
    static final int FIL_MONO = 64;

    public static void main(String[] args) {
        double dt = 1.0e-5;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-cpu" -> cpu = true;
                case "-reach" -> REACH = Double.parseDouble(args[++i]);
                case "-nsing" -> N_SING = Integer.parseInt(args[++i]);
                case "-ndim" -> N_DIM = Integer.parseInt(args[++i]);
                case "-gap" -> GAP = Double.parseDouble(args[++i]);
                case "-formins" -> FORMINS = Integer.parseInt(args[++i]);
                case "-cap" -> FIL_CAP = Integer.parseInt(args[++i]);
                case "-box" -> BOX = Double.parseDouble(args[++i]);
                case "-steps" -> STEPS = Integer.parseInt(args[++i]);
                case "-seedmon" -> SEED_MON = Integer.parseInt(args[++i]);
                case "-nodebrown" -> NODE_BROWN = Double.parseDouble(args[++i]);
                case "-polyrate" -> POLY_RATE = Double.parseDouble(args[++i]);
                case "-nodediag" -> NODE_DIAG = true;    // per-node force-balance sanity print
                case "-scheme" -> SCHEME = Integer.parseInt(args[++i]);     // load-transmission experiment scheme
                case "-boundcoeff" -> BOUND_COEFF = Double.parseDouble(args[++i]);
                case "-v1pairs" -> V1_PAIRS = true;                         // match v1 twoNodeFormin PAIRS coeffs
                case "-segmono" -> SEG_MONO = Integer.parseInt(args[++i]);  // monomers/segment (v1=64, v2 default=32)
                case "-aimtorque" -> AIM_TORQUE = Double.parseDouble(args[++i]);  // aim-holding torque (nodeTorqSpring analog)
                case "-nogrow" -> GROWTH_ON = false;     // polymerization OFF (pre-grown filaments stay fixed length)
                case "-nonuc" -> NUC_ON = false;         // nucleation of new filaments OFF
                case "-3js" -> vizDir = args[++i];
                case "-gate0" -> gate0Only = true;
                case "-aimed" -> applyAimedPreset();
                default -> {}
            }
        }
        System.out.println("=== Soft Box increment 6c — Test B: the SCPR primitive (two nodes capture-and-pull) ===");
        System.out.println("runner: " + (cpu ? "CPU only (-cpu)" : "GPU + CPU cross-check") + ", dt=" + dt + "\n");

        boolean g0 = checkGate0(dt);
        if (!g0) {
            System.out.println("\n=== GATE 0 FAILED — HARD BAIL ===");
            System.out.println("Cross-node capture is EXCLUDED by the binding path. Commit nothing.");
            System.out.println("This is a binding-scope decision for the planner/jba, not an in-scope Test-B change.");
            System.exit(1);
        }
        System.out.println("\n=== GATE 0 PASS — cross-node capture works; Stage 1 is unblocked. ===");
        if (gate0Only) return;
        boolean cg = (vizDir == null && SCHEME == 0) ? checkCpuGpu(dt) : true;   // schemes measured on CPU first; GPU parity for the chosen one
        boolean s1 = runStage1(dt);
        System.out.println("\n=== TEST B SUMMARY: Gate0 PASS; CPU≡GPU " + (cg ? "agree" : "FAIL")
                + "; Stage 1 " + (s1 ? "SCPR capture-and-pull demonstrated" : "ran (capture an observation — see report)") + " ===");
    }
    static boolean gate0Only = false;

    // ====================================================================== shared scene: two node motor-bundles
    /** Two protein nodes (centers cA, cB), each owning N_SING radial singlets + N_DIM radial dimers
     *  (Fibonacci-sphere splay — the seam-#3 random-radial default placement). One MotorStore holds both
     *  shells; one NodeStore holds both nodes; motorNode[m] records which node owns motor m (for the
     *  cross-node-capture readout). FREE nodes by default (integrated + Brownian), unless `fixed`. */
    static final class Shells {
        NodeStore node; MotorStore mot; DimerStore dim;
        int[] motorNode;                                      // per-motor owning node (0=A, 1=B)
        int motorsPerNode, nChildPerNode;
        FloatArray bondData, xbParams;
        IntArray segMotorCount, segMotorOffsets, segMotorMyo, reachSeg, reachCount;
        boolean fixed;
    }

    static Shells buildShells(double dt, double[] cA, double[] cB, boolean fixed) {
        Shells sh = new Shells();
        sh.fixed = fixed;
        int nSing = N_SING, nDim = N_DIM, nChild = nSing + nDim;
        int motorsPerNode = nSing + 2 * nDim;
        sh.motorsPerNode = motorsPerNode; sh.nChildPerNode = nChild;
        int nMot = 2 * motorsPerNode, nDimers = 2 * nDim, nAtt = 2 * nChild;
        double R = NodeStore.NODE_RADIUS;

        MotorStore mot = new MotorStore(nMot);
        DimerStore dim = new DimerStore(nDimers);
        NodeStore node = new NodeStore(2, nAtt);
        sh.motorNode = new int[nMot];

        double[][] centers = { cA, cB };
        for (int k = 0; k < 2; k++) {
            double cx = centers[k][0], cy = centers[k][1], cz = centers[k][2];
            node.node.setCoord(k, (float) cx, (float) cy, (float) cz);
            node.node.setUVec(k, 1f, 0f, 0f);
            node.node.setYVec(k, 0f, 1f, 0f);
            node.node.brownTransScale.set(k, fixed ? 0f : (float) NODE_BROWN);
            node.node.brownRotScale.set(k, fixed ? 0f : (float) NODE_BROWN);
            for (int c = 0; c < nChild; c++) {
                double yy = 1.0 - 2.0 * (c + 0.5) / nChild;
                double rr = Math.sqrt(Math.max(0.0, 1.0 - yy * yy));
                double phi = c * GOLDEN;
                double ux = rr * Math.cos(phi), uy = yy, uz = rr * Math.sin(phi);
                double sx = cx + R * ux, sy = cy + R * uy, sz = cz + R * uz;     // surface point in world
                int att = k * nChild + c;
                if (c < nSing) {
                    int m = k * motorsPerNode + c;
                    mot.assembleArticulated(m, (float) sx, (float) sy, (float) sz, (float) ux, (float) uy, (float) uz, (float) BROWN_TRANS);
                    int rod = mot.rodIdx(m), head = mot.headIdx(m);
                    mot.body.brownRotScale.set(rod, (float) BROWN_ROT); mot.body.brownRotScale.set(head, (float) BROWN_ROT);
                    // scheme 3 (instructive baseline): stiffen ALL singlets to the fixed load coeff (expect the 120-myosin stiffness wall)
                    double coeff = (SCHEME == 3) ? BOUND_COEFF : NodeStore.ATTN_FORCE / nSing;
                    node.attach(att, k, m, R * ux, R * uy, R * uz, coeff, false);
                    sh.motorNode[m] = k;
                } else {
                    int j = c - nSing;
                    int mA = k * motorsPerNode + nSing + 2 * j, mB = mA + 1;
                    int gd = k * nDim + j;
                    placeDimerAlong(mot, mA, mB, sx, sy, sz, ux, uy, uz);
                    dim.pair(gd, mA, mB, true);
                    double coeff = NodeStore.ATTN_FORCE * NodeStore.DIMER_FRACMOVE;
                    node.attach(att, k, mA, R * ux, R * uy, R * uz, coeff, true);
                    sh.motorNode[mA] = k; sh.motorNode[mB] = k;
                }
            }
        }

        DragTensorSystem.run(mot);
        node.initNodeDrag();
        mot.setBodyParams(dt); mot.setJointParams(dt); mot.setKinParams(REACH, ALIGN_TOL, dt); mot.setNucParams(dt);
        mot.kinParams.set(0, (float) KOFF);
        mot.setFaithfulRelease(true, 0.0);                    // inherit the v1 12 pN break-force cap (faithful)
        mot.nucleotideState.init(MotorStore.NUC_NONE);
        dim.setDimerParams(dt);
        node.setNodeParams(dt); node.setNodeBodyParams(dt);
        node2Params = FloatArray.fromElements((float) dt, (float) BOUND_COEFF);   // scheme-2 tether [dt, boundCoeff]
        DerivedGeometrySystem.derive(node.node.coord, node.node.uVec, node.node.yVec, node.node.zVec,
                node.node.end1, node.node.end2, node.node.segLength, node.nodeBodyCounts);

        int MAXC = SpatialGrid.MAX_CAND;
        sh.bondData = new FloatArray(nMot * CrossBridgeSystem.STRIDE); sh.bondData.init(0f);
        sh.xbParams = FloatArray.fromElements((float) MYO_SPRING, 90f, (float) J1_FMT, (float) dt, (float) MotorStore.HEAD_LEN, 0f);
        sh.segMotorCount = null;                              // sized to the filament store by the caller
        sh.reachSeg = new IntArray(nMot * MAXC); sh.reachSeg.init(-1); sh.reachCount = new IntArray(nMot);
        sh.node = node; sh.mot = mot; sh.dim = dim;
        return sh;
    }

    // ====================================================================== STAGE 0 — cross-node capture probe (GATING)
    static boolean checkGate0(double dt) {
        System.out.println("--- STAGE 0 (GATING): cross-node capture probe ---");
        System.out.println("  Two nodes a gap apart; plant ONE filament tagged seedNode=A inside node B's capture range.");
        System.out.println("  Decisive question: does node B's myosin bind a foreign-node (A) segment? (binding is geometric, seedNode-agnostic)");

        int A = 0, B = 1;
        double[] cA = { -0.45, 0, 0 }, cB = { 0.45, 0, 0 };   // center-to-center 0.9 µm
        Shells sh = buildShells(dt, cA, cB, true);            // fixed nodes — static probe
        MotorStore mot = sh.mot; RigidRodBody b = mot.body;

        // publish all heads, then pick node B's first singlet motor and read its head pose
        mot.setCounts(0, SEED, 0);
        MotorStore.publishHeadFromBody(b.coord, b.uVec, b.segLength, mot.head, mot.uVec, mot.rodUVec, mot.counts);
        int mB0 = B * sh.motorsPerNode;                       // node B, local singlet 0
        int nM = mot.nMotors;
        float hx = mot.head.get(mB0), hy = mot.head.get(nM + mB0), hz = mot.head.get(2 * nM + mB0);
        float hux = mot.uVec.get(mB0), huy = mot.uVec.get(nM + mB0), huz = mot.uVec.get(2 * nM + mB0);

        // one ACTIVE filament centered on that head, axis = head axis (so conDist≈0, α≈0.5, motDotFil≈1).
        // Tag it seedNode = A: it is NODE A's filament, planted in NODE B's capture range.
        int cap = 8;
        FilamentStore f = new FilamentStore(cap, cap);
        for (int s = 0; s < cap; s++) f.monomerCount.set(s, FIL_MONO);
        DragTensorSystem.run(f);
        f.setParams(dt, 0); f.setChainParams(dt);
        for (int s = 0; s < cap; s++) { f.setCoord(s, 0f, 0f, 0f); f.setUVec(s, 1f, 0f, 0f); f.setYVec(s, 0f, 1f, 0f); f.markFree(s); }
        int seg = 0;
        f.filState.set(seg, FilamentStore.FIL_ACTIVE);
        f.setCoord(seg, hx, hy, hz);
        f.setUVec(seg, hux, huy, huz);
        // a perpendicular yVec
        float pxx = -huy, pyy = hux, pzz = 0f; float pm = (float) Math.sqrt(pxx*pxx+pyy*pyy+pzz*pzz);
        if (pm < 1e-4f) { pxx = 1f; pyy = 0f; pzz = 0f; pm = 1f; }
        f.setYVec(seg, pxx/pm, pyy/pm, pzz/pm);
        f.brownTransScale.set(seg, 0f); f.brownRotScale.set(seg, 0f);
        DragTensorSystem.run(f);
        DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);

        NodeNucleationStore nuc = new NodeNucleationStore(2, cap, Constants.actinSeed, 1.0, 1.0, 1.0);
        nuc.seedNode.set(seg, A);                              // <-- the foreign-node tag: this filament is node A's

        // --- CPU bind ---
        IntArray reachSeg = new IntArray(nM * SpatialGrid.MAX_CAND); reachSeg.init(-1);
        IntArray reachCount = new IntArray(nM);
        mot.boundSeg.init(MotorStore.FREE_BINDABLE);
        bindCpu(mot, f, reachSeg, reachCount);
        int capturedBy = -1, capturedMotor = -1;
        for (int m = 0; m < nM; m++) if (mot.boundSeg.get(m) == seg) { capturedMotor = m; capturedBy = sh.motorNode[m]; break; }
        int nBoundB = 0, nBoundA = 0;
        for (int m = 0; m < nM; m++) if (mot.boundSeg.get(m) == seg) { if (sh.motorNode[m] == B) nBoundB++; else nBoundA++; }

        boolean crossNode = (capturedBy == B);                // a NODE-B motor captured NODE-A's filament
        System.out.printf("  planted: node A's filament (seedNode=%d) at node B's head m=%d (world %.4f,%.4f,%.4f)%n", A, mB0, hx, hy, hz);
        System.out.printf("  capture: motor %d (owned by node %s) bound seg %d; #node-B captors=%d, #node-A captors=%d%n",
                capturedMotor, capturedBy == B ? "B" : (capturedBy == A ? "A" : "none"), seg, nBoundB, nBoundA);
        System.out.printf("  => cross-node capture (node-B motor binds node-A's filament): %s%n", crossNode ? "YES" : "*NO*");

        // --- CPU≡GPU bit-identity (binding is deterministic — no RNG) ---
        boolean cgOk = true;
        if (!cpu) {
            IntArray gpuBound = runBindGpu(sh, mot, f);
            int mism = 0;
            for (int m = 0; m < nM; m++) if (gpuBound.get(m) != mot.boundSeg.get(m)) mism++;
            cgOk = (mism == 0);
            System.out.printf("  CPU≡GPU: boundSeg mismatches=%d (deterministic bind ⇒ bit-identical) => %s%n", mism, cgOk ? "ok" : "*FAIL*");
        } else {
            System.out.println("  CPU≡GPU: skipped (-cpu)");
        }

        boolean ok = crossNode && cgOk;
        System.out.println("  => GATE 0 " + (ok ? "PASS" : "*FAIL*") + "\n");
        return ok;
    }

    /** Deterministic bind pipeline on the CPU runner: publish heads → reachable (brute) → bindNearest. */
    static void bindCpu(MotorStore mot, FilamentStore f, IntArray reachSeg, IntArray reachCount) {
        RigidRodBody b = mot.body;
        mot.setCounts(0, SEED, f.n);
        MotorStore.publishHeadFromBody(b.coord, b.uVec, b.segLength, mot.head, mot.uVec, mot.rodUVec, mot.counts);
        BindingDetectionSystem.bruteReachable(mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, reachSeg, reachCount, mot.kinParams, mot.counts);
        BindingDetectionSystem.bindNearest(mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, reachSeg, reachCount, mot.boundSeg, mot.bindArc, mot.kinParams, mot.counts);
    }

    /** Same deterministic bind pipeline on the GPU TaskGraph; returns the resulting boundSeg (host copy). */
    static IntArray runBindGpu(Shells sh, MotorStore mot, FilamentStore f) {
        RigidRodBody b = mot.body;
        mot.boundSeg.init(MotorStore.FREE_BINDABLE);
        IntArray reachSeg = new IntArray(mot.nMotors * SpatialGrid.MAX_CAND); reachSeg.init(-1);
        IntArray reachCount = new IntArray(mot.nMotors);
        mot.setCounts(0, SEED, f.n);
        TaskGraph tg = new TaskGraph("gate0bind")
            .transferToDevice(DataTransferMode.FIRST_EXECUTION,
                    b.coord, b.uVec, b.segLength, mot.head, mot.uVec, mot.rodUVec,
                    f.end1, f.end2, reachSeg, reachCount, mot.boundSeg, mot.bindArc, mot.kinParams)
            .transferToDevice(DataTransferMode.EVERY_EXECUTION, mot.counts)
            .task("publishHead", MotorStore::publishHeadFromBody, b.coord, b.uVec, b.segLength, mot.head, mot.uVec, mot.rodUVec, mot.counts)
            .task("reach", BindingDetectionSystem::bruteReachable, mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, reachSeg, reachCount, mot.kinParams, mot.counts)
            .task("bind", BindingDetectionSystem::bindNearest, mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, reachSeg, reachCount, mot.boundSeg, mot.bindArc, mot.kinParams, mot.counts)
            .transferToHost(DataTransferMode.UNDER_DEMAND, mot.boundSeg);
        sched = new GridScheduler();
        for (String t : new String[]{ "publishHead", "reach", "bind" }) addW("gate0bind." + t, pad(mot.nMotors));
        TornadoExecutionPlan plan = new TornadoExecutionPlan(tg.snapshot());
        TornadoExecutionResult res = plan.withGridScheduler(sched).execute();
        res.transferToHost(mot.boundSeg);
        IntArray out = new IntArray(mot.nMotors);
        for (int m = 0; m < mot.nMotors; m++) out.set(m, mot.boundSeg.get(m));
        return out;
    }

    // ====================================================================== STAGE 1 — the two-node SCPR assay
    // Defaults are tuned for a legible first-light cross-capture demo (NOT calibration; jba tunes). The task
    // suggested ~0.9 µm center-to-center; at n=2 with random-radial search a productive cross-capture is rare
    // at that spacing (the SCPR stochastic-search reality), so the demo default uses a closer spacing where the
    // capture cone is large enough to see cross-captures within a feasible run. All knobs are CLI-tunable.
    static double GAP = 0.25;            // node center-to-center (µm) — tunable -gap (task suggested 0.9)
    static int FORMINS = 14;            // forminsPerNode (seam #3 site count) — tunable -formins
    static int FIL_CAP = 512;           // FilamentStore capacity — tunable -cap (bounds run length: flag)
    static double BOX = 3.0;            // containment cube side (µm) — roomy for both filament aprons
    static final double BOX_VOL = 8.0 * 4.0 * 0.6;   // µm³ (ActinPool µM-per-monomer reference volume)
    static boolean GROWTH_ON = true, NUC_ON = true;
    static int SEED_MON = 45;           // warm-start seed length (monomers); growth extends from here — tunable -seedmon

    static int wangHash(int seed) {
        seed = (seed ^ 61) ^ (seed >>> 16); seed *= 9; seed = seed ^ (seed >>> 4);
        seed *= 0x27d4eb2d; seed = seed ^ (seed >>> 15); return seed;
    }

    static boolean AIMED = false;
    /** Test B′ — the CLEAN aimed scene (jba's design): SPECIFIED placement, ONE aimed filament per node, well
     *  SEPARATED start, over a real gap. Sparse + aimed + separated makes the SCPR signal legible, and the layout
     *  (a single filament aimed AWAY from its own shell toward the partner) precludes self-capture by GEOMETRY —
     *  no rule. Put -aimed FIRST; later flags override. */
    static void applyAimedPreset() {
        AIMED = true;
        PLACEMENT = Placement.SPECIFIED;
        FORMINS = 1;                 // sparse: one aimed filament per node
        GAP = 0.6;                   // well-separated (surface gap ~0.5 µm; shells do NOT overlap — not contact)
        SEED_MON = 64;               // warm-start near one segment-length; growth bridges the gap
        STEPS = 30000;               // long enough for a clear, legible approach (the pre-grown chain captures early)
        NODE_BROWN = 0.02;           // legible directed approach over the long aimed run (node = large/slow complex)
        BOX = 3.0;
    }

    // ----------------------------------------------------------------- SEAM #3: formin-site placement
    // A node carries multiple formin sites (forminsPerNode); each site's RADIAL DIRECTION sets whether its
    // filament searches toward the partner. Test B is the first scene where placement matters behaviorally.
    // The placement is a PLUGGABLE function: RANDOM-radial today (biology TBD), a SPECIFIED arrangement later
    // (e.g. a defined inter-site geometry) plugs in here without a refactor. Test B stays on RANDOM (the default,
    // the same wang-hash draw the runtime nucleation emit uses) — specified placement is NOT built (jba's call).
    enum Placement { RANDOM, SPECIFIED }
    static Placement PLACEMENT = Placement.RANDOM;
    // SPECIFIED placement state (set at scene build): per-node center + per-node TARGET (the site's aim point).
    // General seam: a specifiable aim/target per node. This test = aim-at-partner (NODE_TARGET[k] = the other node).
    static double[][] NODE_CENTERS;     // [node][xyz] world centers
    static int[] NODE_TARGET;           // [node] = target node index the site aims at
    /** Unit radial direction for formin site `site` on node `nodeK`. The SEAM: RANDOM (wang-hash, isotropic) is
     *  the faithful SCPR stochastic-search default; SPECIFIED aims the seed from the node toward its TARGET (here
     *  the partner node) — a real, general specifiable-aim body (multi-site spreads a small cone around the aim). */
    static double[] forminSiteDir(int nodeK, int site) {
        switch (PLACEMENT) {
            case SPECIFIED -> {
                int tgt = NODE_TARGET[nodeK];
                double dx = NODE_CENTERS[tgt][0] - NODE_CENTERS[nodeK][0];
                double dy = NODE_CENTERS[tgt][1] - NODE_CENTERS[nodeK][1];
                double dz = NODE_CENTERS[tgt][2] - NODE_CENTERS[nodeK][2];
                double m2 = dx*dx + dy*dy + dz*dz; if (m2 < 1e-12) { dx = 1; dy = 0; dz = 0; m2 = 1; }
                double inv = 1.0 / Math.sqrt(m2); dx *= inv; dy *= inv; dz *= inv;
                if (FORMINS > 1) {   // >1 aimed site ⇒ a small deterministic cone around the aim (stays a real seam)
                    double spread = 0.25;   // rad
                    double ang = (site - 0.5 * (FORMINS - 1)) * spread;
                    // rotate dir about an arbitrary perpendicular by `ang`
                    double ex = (Math.abs(dx) < 0.9) ? 1 : 0, ey = (Math.abs(dx) < 0.9) ? 0 : 1, ez = 0;
                    double px = dy*ez - dz*ey, py = dz*ex - dx*ez, pz = dx*ey - dy*ex;
                    double pm = 1.0 / Math.sqrt(px*px + py*py + pz*pz); px *= pm; py *= pm; pz *= pm;
                    double c = Math.cos(ang), sgn = Math.sin(ang);
                    dx = c*dx + sgn*px; dy = c*dy + sgn*py; dz = c*dz + sgn*pz;
                    double mm = 1.0 / Math.sqrt(dx*dx + dy*dy + dz*dz); dx *= mm; dy *= mm; dz *= mm;
                }
                return new double[]{ dx, dy, dz };
            }
            default -> {   // RANDOM (isotropic wang-hash — the Test B default)
                int base = ((nodeK * FORMINS + site) * 1000003) ^ (nodeK * 999983) ^ 0x53434252;   // "SCBR"
                double dx = ((wangHash(base ^ 0x9e3779b9) >>> 1) / 2147483647.0) * 2 - 1;
                double dy = ((wangHash(base ^ 0x85ebca6b) >>> 1) / 2147483647.0) * 2 - 1;
                double dz = ((wangHash(base ^ 0xc2b2ae35) >>> 1) / 2147483647.0) * 2 - 1;
                double m2 = dx*dx + dy*dy + dz*dz; if (m2 < 1e-9) { dx = 1; dy = 0; dz = 0; m2 = 1; }
                double inv = 1.0 / Math.sqrt(m2);
                return new double[]{ dx * inv, dy * inv, dz * inv };
            }
        }
    }

    static int AIMED_CHAIN = 0;          // pre-grown aimed-filament segment count (computed in buildStage1)

    /** Pre-grow ONE aimed filament for node k as a linear chain of nChain stdSegLength segments along (dx,dy,dz),
     *  the tip (node-bonded, seedNode=k, the barbed end2) at the node center, uVec INWARD, children extending OUTWARD
     *  toward the partner (barbed=end2 convention). Chain-wired nodeward=end2 / outward=end1, so growth's splitWire
     *  + the chain F3/F4 + filNodeOf all operate on it unchanged. Slots [base, base+nChain). */
    static void placeAimedChain(FilamentStore f, NodeNucleationStore nuc, NodeStore nd, int k,
                                double dx, double dy, double dz, int nChain, int base, double bornScale) {
        double cx = nd.node.coord.get(k), cy = nd.node.coord.get(nd.node.n + k), cz = nd.node.coord.get(2 * nd.node.n + k);
        double ex = (Math.abs(dx) < 0.9) ? 1 : 0, ey = (Math.abs(dx) < 0.9) ? 0 : 1, ez = 0;
        double yx = dy*ez - dz*ey, yy = dz*ex - dx*ez, yz = dx*ey - dy*ex;
        double ym = 1.0 / Math.sqrt(yx*yx + yy*yy + yz*yz); yx *= ym; yy *= ym; yz *= ym;
        double Lc = (SEG_MONO + 1) * Constants.actinMonoRadius;
        double e1x = cx, e1y = cy, e1z = cz;                           // marching node-side point (each seg's end2)
        for (int i = 0; i < nChain; i++) {
            int sl = base + i;
            double ccx = e1x + 0.5 * Lc * dx, ccy = e1y + 0.5 * Lc * dy, ccz = e1z + 0.5 * Lc * dz;
            f.monomerCount.set(sl, SEG_MONO);
            f.setCoord(sl, (float) ccx, (float) ccy, (float) ccz);     // coord UNCHANGED (centers along +dir)
            f.setUVec(sl, (float) -dx, (float) -dy, (float) -dz);      // barbed=end2: uVec INWARD (toward node)
            if (AIM_DIR != null) { int C = f.n; AIM_DIR.set(sl, (float) -dx); AIM_DIR.set(C + sl, (float) -dy); AIM_DIR.set(2 * C + sl, (float) -dz); }  // aim-torque target
            f.setYVec(sl, (float) yx, (float) yy, (float) yz);
            f.filState.set(sl, FilamentStore.FIL_ACTIVE);
            f.brownTransScale.set(sl, (float) bornScale); f.brownRotScale.set(sl, (float) bornScale);
            nuc.seedNode.set(sl, i == 0 ? k : -1);                     // the tip (node side, barbed end2) carries the node bond
            if (i > 0) { f.end2NbrSlot.set(sl, sl - 1); f.end2NbrSide.set(sl, 0); }     // end2(nodeward) ↔ prev.end1(outward)
            if (i < nChain - 1) { f.end1NbrSlot.set(sl, sl + 1); f.end1NbrSide.set(sl, 1); }  // end1(outward) ↔ next.end2(nodeward)
            e1x += Lc * dx; e1y += Lc * dy; e1z += Lc * dz;            // advance outward by one segment
        }
    }

    /** The assembled SCPR scene: two free box-confined nodes + their motor shells (Shells), a dynamic
     *  FilamentStore (born + grown), nucleation + growth state, and the DEDICATED nucleation request arrays
     *  (the integration crux — emit clears only [0,nNodes), growth's markSplits clears all [0,filCap), so they
     *  must NOT share acceptFlag/reqCoord/rankOffsets; growth uses FilamentStore's, nucleation gets its own). */
    static final class S1 {
        Shells sh; FilamentStore fil; NodeNucleationStore nuc; GrowthStore grow;
        IntArray nucAccept; FloatArray nucReqCoord, nucReqUVec, nucReqYVec;
        IntArray nucRankOffsets, nucRankScanCounts;
        FloatArray boxParams;
        int A = 0, B = 1;
    }

    static S1 buildStage1(double dt) {
        double h = 0.5 * GAP;
        S1 s = new S1();
        NODE_CENTERS = new double[][]{ { -h, 0, 0 }, { h, 0, 0 } };   // SEAM #3 SPECIFIED: aim points
        NODE_TARGET = new int[]{ 1, 0 };                              // each node aims at the partner
        s.sh = buildShells(dt, NODE_CENTERS[0], NODE_CENTERS[1], false);   // FREE nodes
        NodeStore nd = s.sh.node; MotorStore mot = s.sh.mot;
        int cap = FIL_CAP;

        FilamentStore f = new FilamentStore(cap, cap);                 // reqCap == capacity (request index == slot, growth)
        for (int sl = 0; sl < cap; sl++) f.monomerCount.set(sl, Constants.actinSeed);
        DragTensorSystem.run(f);
        f.setParams(dt, Constants.brownianForceMag(dt));
        f.setChainParams(dt);   // chainParams[0]=dt by construction (chain-dt class fix)
        if (V1_PAIRS) {                                                // match the v1 twoNodeFormin chain PAIRS coefficients
            f.chainParams.set(1, 0.0573f);   // fracMove
            f.chainParams.set(2, 1.0f);      // fracR
            f.chainParams.set(3, 0.01f);     // fracMoveTorq
        }
        AIM_DIR = new FloatArray(3 * cap); AIM_DIR.init(0f);           // aim-torque targets (set in placeAimedChain)
        AIM_PARAMS = FloatArray.fromElements((float) AIM_TORQUE, (float) dt);
        // Faithful to v1 (FilSegment.java:621-642, motherFil==null): formin/node-anchored filaments get the FULL
        // FDT Brownian (transScale=BTransCoeff); the node tether does the holding — NO per-seed damping. The old
        // BTransCoeff/30 hack was tuned against the wrong (10× cold) brownianForceMag — removed with the amplitude fix.
        double bornScale = Constants.BTransCoeff;
        f.setBirthParams(bornScale, bornScale);
        f.setBirthRequestCount(cap);
        // Park ALL slots FREE and FAR away (100,100,100): the binding path uses bruteReachable over ALL
        // segments (no broad-phase publish guard), so an unborn FREE slot must be geometrically unreachable.
        // Parking far keeps it off the candidate set WITHOUT any shared-kernel filState guard (allocate
        // overwrites the pose at birth; growth's splitWire writes child poses).
        for (int sl = 0; sl < cap; sl++) { f.setCoord(sl, 100f, 100f, 100f); f.setUVec(sl, 1f, 0f, 0f); f.setYVec(sl, 0f, 1f, 0f); f.markFree(sl); }

        NodeNucleationStore nuc = new NodeNucleationStore(2, cap, Constants.actinSeed, 1.0e12, BOX_VOL, 30.0);

        // WARM-START (scene IC, NOT aimed placement): pre-place FORMINS seeds per node in RANDOM-radial
        // directions (the seam-#3 default; same wang-hash draw the nucleation emit uses) at SEED_MON monomers,
        // tethered to their node (seedNode bond). kNodeNuc=10/node·s ⇒ ~80k steps to populate stochastically,
        // so the run starts already-searching (nucleation stays ON to top up). Slots [k·FORMINS,(k+1)·FORMINS).
        if (AIMED) {
            // Test B′: one AIMED filament per node, PRE-GROWN as a multi-segment chain that OVERSHOOTS the
            // partner (so the partner's rodDotFil≥0 / far-hemisphere heads capture it early — the polarity gate
            // requires the foreign filament to reach the captor's far side; INC6C_TESTB_SCPR_FINDINGS §capture
            // cone). Growth stays ON (the tip keeps extending). Node k's chain: slots [k·CHAIN,(k+1)·CHAIN).
            double Lc = (SEG_MONO + 1) * Constants.actinMonoRadius;                   // segment length (SEG_MONO mono)
            AIMED_CHAIN = Math.max(2, (int) Math.round(1.30 * GAP / Lc));             // reach ≈ 1.3·gap (overshoot)
            for (int k = 0; k < 2; k++) {
                double[] d = forminSiteDir(k, 0);              // SEAM #3 SPECIFIED: aim at the partner
                placeAimedChain(f, nuc, nd, k, d[0], d[1], d[2], AIMED_CHAIN, k * AIMED_CHAIN, bornScale);
            }
        } else {
            // WARM-START (scene IC): pre-place FORMINS seeds/node in RANDOM-radial directions (seam-#3 default)
            // at SEED_MON monomers, tethered. kNodeNuc=10/node·s ⇒ ~80k steps to populate stochastically, so the
            // run starts already-searching (nucleation stays ON). Slots [k·FORMINS,(k+1)·FORMINS).
            double halfSeed = 0.5 * (SEED_MON + 1) * Constants.actinMonoRadius;
            for (int k = 0; k < 2; k++) {
                double ncx = nd.node.coord.get(k), ncy = nd.node.coord.get(nd.node.n + k), ncz = nd.node.coord.get(2 * nd.node.n + k);
                for (int j = 0; j < FORMINS && (k * FORMINS + j) < cap; j++) {
                    int sl = k * FORMINS + j;
                    double[] d = forminSiteDir(k, j);          // SEAM #3: pluggable formin-site placement
                    double dx = d[0], dy = d[1], dz = d[2];
                    double ex = (Math.abs(dx) < 0.9) ? 1 : 0, ey = (Math.abs(dx) < 0.9) ? 0 : 1, ez = 0;
                    double yx = dy*ez - dz*ey, yy = dz*ex - dx*ez, yz = dx*ey - dy*ex;
                    double ym = 1.0 / Math.sqrt(yx*yx + yy*yy + yz*yz); yx *= ym; yy *= ym; yz *= ym;
                    f.monomerCount.set(sl, SEED_MON);
                    f.setCoord(sl, (float) (ncx + halfSeed * dx), (float) (ncy + halfSeed * dy), (float) (ncz + halfSeed * dz));
                    f.setUVec(sl, (float) -dx, (float) -dy, (float) -dz);   // barbed=end2: uVec inward (end2 at node)
                    f.setYVec(sl, (float) yx, (float) yy, (float) yz);
                    f.filState.set(sl, FilamentStore.FIL_ACTIVE);
                    f.brownTransScale.set(sl, (float) bornScale); f.brownRotScale.set(sl, (float) bornScale);
                    nuc.seedNode.set(sl, k);
                }
            }
        }
        DragTensorSystem.run(f);
        DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);

        double seedLen = (Constants.actinSeed + 1) * Constants.actinMonoRadius;
        nuc.setNucParams(Constants.kNodeNuc, dt, seedLen, NUC_ON ? FORMINS : 0);
        nuc.setTetherParams(Constants.fracMove, dt);
        nuc.setDissolveParams(0.0, dt);                                // NEVER dissolve — filaments stay tethered to their node (the SCPR pull bond)

        GrowthStore grow = new GrowthStore(cap, Constants.kATPOn2WithFormin, dt, 1.0e12, BOX_VOL);   // large pool ⇒ sustained cross-gap growth (not pool-limited)

        // dedicated nucleation request arrays (the integration crux)
        s.nucAccept = new IntArray(cap); s.nucAccept.init(0);
        s.nucReqCoord = new FloatArray(3 * cap); s.nucReqUVec = new FloatArray(3 * cap); s.nucReqYVec = new FloatArray(3 * cap);
        s.nucRankOffsets = new IntArray(cap + 1);
        s.nucRankScanCounts = new IntArray(4); s.nucRankScanCounts.set(3, cap);

        // seg→bound-motor gather scratch (cross-bridge reaction onto the captured filament)
        s.sh.segMotorCount = new IntArray(cap);
        s.sh.segMotorOffsets = new IntArray(cap + 1);
        s.sh.segMotorMyo = new IntArray(mot.nMotors);

        // the in-vitro chamber: a roomy cube centred on the midpoint (confines node bodies only; v1 box law)
        s.boxParams = FloatArray.fromElements(1.0e-4f, (float) BOX, (float) BOX, (float) BOX,
                (float) NodeStore.NODE_RADIUS, 0.5f, 10f);
        s.fil = f; s.nuc = nuc; s.grow = grow;
        return s;
    }

    /** One allocator pass (B1 scan-rank free-list) parameterised by which request arrays drive it — so growth
     *  (FilamentStore's own) and nucleation (the dedicated arrays) each get a clean, independent pass over the
     *  rebuilt free-list. */
    static void allocCpu(FilamentStore f, IntArray accept, FloatArray rc, FloatArray ru, FloatArray ry,
                         IntArray rankScan, IntArray rankOff) {
        FilamentBirthSystem.freeFlags(f.filState, f.freeCount, f.allocCounts);
        CrossBridgeSystem.csrScan(f.freeScanCounts, f.freeCount, f.freeOffsets);
        FilamentBirthSystem.freeScatter(f.filState, f.freeOffsets, f.freeList, f.allocCounts);
        CrossBridgeSystem.csrScan(rankScan, accept, rankOff);
        FilamentBirthSystem.allocate(rc, ru, ry, rankOff, f.freeList, f.freeOffsets,
                f.coord, f.uVec, f.yVec, f.brownTransScale, f.brownRotScale, f.filState, f.birthParams, f.allocCounts);
    }

    /** The full per-step SCPR loop on the CPU runner (the device-agnostic physics; the same systems run on the
     *  GPU TaskGraph). Order: growth(cadence) → nucleation → bind/cycle → forces(motor+node+filament incl. the
     *  seedNode pull bond) → integrate(node+motor+filament). NO new force law, NO new gather. */
    static void cpuStepStage1(S1 s, int t) {
        Shells sh = s.sh; MotorStore mot = sh.mot; DimerStore dim = sh.dim; NodeStore nd = sh.node;
        FilamentStore f = s.fil; NodeNucleationStore nuc = s.nuc; GrowthStore grow = s.grow;
        RigidRodBody b = mot.body; RigidRodBody nb = nd.node;
        int nSeg = f.n;
        mot.setCounts(t, SEED, nSeg); nd.setNodeBodyCounts(t, SEED_NODE_A ^ SEED_NODE_B); f.setCounts(t, SEED);

        // === GROWTH (cadence-gated; FilamentStore's own request arrays; markSplits clears all acceptFlag) ===
        boolean fires = GROWTH_ON && grow.firesAt(t);
        grow.setCounts(t, SEED, fires); grow.refreshRate(GROWTH_ON);
        if (POLY_RATE != 1.0) grow.growParams.set(0, (float) (Math.min(grow.growParams.get(0), 1.0) * POLY_RATE));  // -polyrate scale (cap then dial)
        GrowthSystem.grow(nuc.seedNode, f.monomerCount, f.coord, f.uVec, grow.grewFlag, grow.growParams, grow.growCounts);
        CrossBridgeSystem.csrScan(grow.grewScanCounts, grow.grewFlag, grow.grewOffsets);
        grow.depletePoolForGrows();
        GrowthSystem.markSplits(nuc.seedNode, f.monomerCount, f.coord, f.uVec, f.yVec,
                f.acceptFlag, f.reqCoord, f.reqUVec, f.reqYVec, grow.splitParams, grow.growCounts);
        allocCpu(f, f.acceptFlag, f.reqCoord, f.reqUVec, f.reqYVec, f.rankScanCounts, f.rankOffsets);
        GrowthSystem.splitWire(f.rankOffsets, f.freeList, f.freeOffsets, f.monomerCount, f.coord, f.uVec,
                f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide, nuc.seedNode, grow.splitParams, f.allocCounts);
        GrowthSystem.recomputeDrag(f.monomerCount, f.segLength, f.end1NbrSlot, f.end2NbrSlot,
                f.bTransGam, f.bRotGam, f.bTransDiff, f.bRotDiff, grow.dragParams, grow.growCounts);

        // === NUCLEATION (every step; DEDICATED request arrays so growth's stale split flags can't leak) ===
        nuc.setCounts(t, SEED); nuc.refreshPoolGate();
        NodeNucleationSystem.countBoundFil(nuc.seedNode, nuc.nodeBoundFil, nuc.nucCounts);
        NodeNucleationSystem.emit(nb.coord, nuc.nodeBoundFil, s.nucAccept, s.nucReqCoord, s.nucReqUVec, s.nucReqYVec, nuc.nucParams, nuc.nucCounts);
        allocCpu(f, s.nucAccept, s.nucReqCoord, s.nucReqUVec, s.nucReqYVec, s.nucRankScanCounts, s.nucRankOffsets);
        NodeNucleationSystem.tagSeeds(s.nucRankOffsets, f.freeList, f.freeOffsets, nuc.seedNode, f.allocCounts);

        // === MOTOR BINDING (dynamic; bruteReachable over ACTIVE segments — FREE slots parked far, unreachable) ===
        MotorStore.publishHeadFromBody(b.coord, b.uVec, b.segLength, mot.head, mot.uVec, mot.rodUVec, mot.counts);
        // v1-faithful node-held exclusion (MyoMotor.checkFilSegCollision:391): a node-held TIP (seedNode>=0) is
        // not a binding candidate — own myosins can't self-capture their held tip; outer (seedNode<0) stay bindable.
        BindingDetectionSystem.bruteReachableNodeAware(mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, sh.reachSeg, sh.reachCount, nuc.seedNode, mot.kinParams, mot.counts);
        NucleotideCycleSystem.catchSlipRelease(mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.cooldown, mot.stats, mot.capStats, mot.kinParams, mot.counts);
        BindingDetectionSystem.bindNearestNodeAware(mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, sh.reachSeg, sh.reachCount, mot.boundSeg, mot.bindArc, nuc.seedNode, mot.kinParams, mot.counts);
        NucleotideCycleSystem.cycle(mot.nucleotideState, mot.boundSeg, mot.forceDotHist, mot.nucParams, mot.counts);

        // === FORCES ===
        ChainBendingForceSystem.zeroAccumulators(b.forceSum, b.torqueSum, mot.counts);
        ChainBendingForceSystem.zeroAccumulators(nb.forceSum, nb.torqueSum, nd.nodeBodyCounts);
        ChainBendingForceSystem.zeroAccumulators(f.forceSum, f.torqueSum, f.counts);
        BrownianForceSystem.brownianForce(b.randForce, b.randTorque, b.bTransGam, b.bRotGam, b.brownTransScale, b.brownRotScale, mot.bodyParams, mot.counts);
        BrownianForceSystem.brownianForce(nb.randForce, nb.randTorque, nb.bTransGam, nb.bRotGam, nb.brownTransScale, nb.brownRotScale, nd.nodeBodyParams, nd.nodeBodyCounts);
        BrownianForceSystem.brownianForce(f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.brownTransScale, f.brownRotScale, f.params, f.counts);
        MotorJointSystem.joints(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, mot.nucleotideState, mot.jointParams, mot.counts);
        DimerCouplingSystem.couple(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, dim.motorA, dim.motorB, dim.parallel, dim.dimerParams, mot.boundSeg);
        // node radial surface tether + the single-ended backbone-side CSR gather (motor reaction → node body).
        // load-transmission EXPERIMENT (default SCHEME=0 ⇒ the exact original order/kernels, bit-identical):
        if (SCHEME == 1) {
            // scheme 1 — DIRECT INJECTION: compute cross-bridge first; tether (soft, retention) writes nodeData;
            // inject the bound heads' force onto the node (rigid lever); gather; SKIP applyHeadForce (head force
            // went to the node, not the motor ⇒ counted once). The filament still gets −F via segGather below.
            CrossBridgeSystem.bondForces(b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength,
                    mot.boundSeg, mot.bindArc, mot.nucleotideState, sh.bondData, sh.xbParams);
            NodeSystem.tether(b.coord, b.uVec, b.segLength, b.bTransGam, b.forceSum, b.torqueSum,
                    nb.coord, nb.uVec, nb.yVec, nd.nodeInvTransY, nd.attachKey, nd.radial, nd.attachCoeffK, nd.nodeData, nd.nodeParams);
            NodeSystem.xbridgeInject(sh.bondData, nb.coord, nb.uVec, nb.yVec, nd.attachKey, nd.radial, nd.attachCoeffK, mot.boundSeg, nd.nodeData, nd.nodeParams);
            CrossBridgeSystem.csrHistogram(nd.attachNode, nd.nodeCounts4, nd.nodeAttachCount);
            CrossBridgeSystem.csrScan(nd.nodeCounts4, nd.nodeAttachCount, nd.nodeAttachOffsets);
            CrossBridgeSystem.csrScatter(nd.attachNode, nd.nodeCounts4, nd.nodeAttachOffsets, nd.nodeAttachCount, nd.nodeAttachList);
            MiniFilamentSystem.backboneGather(nd.nodeAttachOffsets, nd.nodeAttachList, nd.nodeData, nb.forceSum, nb.torqueSum, nd.nodeCounts4);
        } else {
            if (SCHEME == 2)   // scheme 2 — STATE-DEPENDENT STIFF tether (bound myosins only)
                NodeSystem.tetherBoundStiffen(b.coord, b.uVec, b.segLength, b.bTransGam, b.forceSum, b.torqueSum,
                        nb.coord, nb.uVec, nb.yVec, nd.nodeInvTransY, nd.attachKey, nd.radial, nd.attachCoeffK, mot.boundSeg, nd.nodeData, node2Params);
            else               // SCHEME 0 (current) and 3 (global stiffen via the build-time coeff) — original tether
                NodeSystem.tether(b.coord, b.uVec, b.segLength, b.bTransGam, b.forceSum, b.torqueSum,
                        nb.coord, nb.uVec, nb.yVec, nd.nodeInvTransY, nd.attachKey, nd.radial, nd.attachCoeffK, nd.nodeData, nd.nodeParams);
            CrossBridgeSystem.csrHistogram(nd.attachNode, nd.nodeCounts4, nd.nodeAttachCount);
            CrossBridgeSystem.csrScan(nd.nodeCounts4, nd.nodeAttachCount, nd.nodeAttachOffsets);
            CrossBridgeSystem.csrScatter(nd.attachNode, nd.nodeCounts4, nd.nodeAttachOffsets, nd.nodeAttachCount, nd.nodeAttachList);
            MiniFilamentSystem.backboneGather(nd.nodeAttachOffsets, nd.nodeAttachList, nd.nodeData, nb.forceSum, nb.torqueSum, nd.nodeCounts4);
            // cross-bridge: head self-force + the seg-side reaction stored in bondData
            CrossBridgeSystem.bondForces(b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength,
                    mot.boundSeg, mot.bindArc, mot.nucleotideState, sh.bondData, sh.xbParams);
            CrossBridgeSystem.applyHeadForce(sh.bondData, b.forceSum, b.torqueSum, mot.counts);
        }
        // filament: chain (F3/F4) + the seedNode pull bond (node-center↔tip) + the gathered cross-bridge reaction
        ChainBendingForceSystem.chainForces(f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide, f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts);
        NodeNucleationSystem.seedTether(f.coord, f.uVec, f.segLength, f.bTransGam, f.forceSum, f.torqueSum,
                nb.coord, nd.nodeInvTransY, nuc.seedNode, nuc.tetherParams);
        // two-sided bond: the Newton reaction of the seed tether on the FREE node (un-pins the barbed end; without
        // it the node is never dragged by its own captured filament — the missing SCPR coalescence force)
        NodeNucleationSystem.seedTetherNodeReact(f.coord, f.uVec, f.segLength, f.bTransGam,
                nb.forceSum, nb.coord, nd.nodeInvTransY, nuc.seedNode, nuc.tetherParams);
        CrossBridgeSystem.csrHistogram(mot.boundSeg, mot.counts, sh.segMotorCount);
        CrossBridgeSystem.csrScan(mot.counts, sh.segMotorCount, sh.segMotorOffsets);
        CrossBridgeSystem.csrScatter(mot.boundSeg, mot.counts, sh.segMotorOffsets, sh.segMotorCount, sh.segMotorMyo);
        CrossBridgeSystem.segGather(sh.segMotorOffsets, sh.segMotorMyo, sh.bondData, f.forceSum, f.torqueSum, mot.counts);
        CrossBridgeSystem.registerForceDot(sh.bondData, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.counts);
        // EXPERIMENT: aim-holding torque (v1 nodeTorqSpring analog) — holds the aimed filaments pointed at the partner
        if (AIM_TORQUE > 0.0) NodeNucleationSystem.seedAimTorque(f.uVec, f.bRotGam, f.torqueSum, AIM_DIR, AIM_PARAMS);

        // node force-balance sanity check (read forceSum AFTER all accumulation, BEFORE confine/integrate)
        if (NODE_DIAG && (t % NODE_DIAG_EVERY == 0)) nodeForceDiag(s, t);

        // === INTEGRATE (node body confined+integrated; motor body; filament) ===
        ContainmentSystem.confine(nb.coord, nb.uVec, nb.segLength, nb.bTransGam, nb.forceSum, nb.torqueSum, s.boxParams, nd.nodeBodyCounts);
        RigidRodLangevinIntegrationSystem.integrate(nb.coord, nb.uVec, nb.yVec, nb.forceSum, nb.torqueSum, nb.randForce, nb.randTorque, nb.bTransGam, nb.bRotGam, nd.nodeBodyParams, nd.nodeBodyCounts);
        DerivedGeometrySystem.derive(nb.coord, nb.uVec, nb.yVec, nb.zVec, nb.end1, nb.end2, nb.segLength, nd.nodeBodyCounts);
        RigidRodLangevinIntegrationSystem.integrate(b.coord, b.uVec, b.yVec, b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, mot.bodyParams, mot.counts);
        DerivedGeometrySystem.derive(b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts);
        RigidRodLangevinIntegrationSystem.integrate(f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts);
        DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
    }

    static boolean NODE_DIAG = false;
    static int NODE_DIAG_EVERY = 5000;
    /** Force-balance sanity check: per node, compare the RAW cross-bridge head-force on its captured myosins (the
     *  big "stretch" force) vs the NET force actually in the node's forceSum (what the integrator moves it by),
     *  plus the implied per-step displacement. Reveals whether the cross-bridge force reaches the node body. */
    static void nodeForceDiag(S1 s, int t) {
        RigidRodBody nb = s.sh.node.node; int nN = nb.n;
        MotorStore mot = s.sh.mot; int nMot = mot.nMotors; int ST = CrossBridgeSystem.STRIDE;
        double dt = 1.0e-5;
        double[] rawx = new double[nN], rawmag = new double[nN]; int[] cap = new int[nN];
        for (int m = 0; m < nMot; m++) {
            if (mot.boundSeg.get(m) < 0) continue;
            int k = s.sh.motorNode[m];
            double hx = s.sh.bondData.get(m * ST), hy = s.sh.bondData.get(m * ST + 1), hz = s.sh.bondData.get(m * ST + 2);
            rawx[k] += hx; rawmag[k] += Math.sqrt(hx * hx + hy * hy + hz * hz); cap[k]++;
        }
        // retention: max tether strain (rod end1 ↔ its node surface point) — myosins drifting off the surface
        NodeStore nd = s.sh.node; int nA = nd.nAttach; int nMB = mot.body.coord.getSize() / 3;
        double maxStrain = 0;
        for (int a = 0; a < nA; a++) {
            int kk = nd.attachKey.get(a), rod = 3 * nd.attachKey.get(nA + a);
            double ncx = nb.coord.get(kk), ncy = nb.coord.get(nN + kk), ncz = nb.coord.get(2 * nN + kk);
            double nux = nb.uVec.get(kk), nuy = nb.uVec.get(nN + kk), nuz = nb.uVec.get(2 * nN + kk);
            double nyx = nb.yVec.get(kk), nyy = nb.yVec.get(nN + kk), nyz = nb.yVec.get(2 * nN + kk);
            double nzx = nuy*nyz - nuz*nyy, nzy = nuz*nyx - nux*nyz, nzz = nux*nyy - nuy*nyx;
            double ru = nd.radial.get(a), ry = nd.radial.get(nA + a), rz = nd.radial.get(2 * nA + a);
            double pax = ncx + ru*nux + ry*nyx + rz*nzx, pay = ncy + ru*nuy + ry*nyy + rz*nzy, paz = ncz + ru*nuz + ry*nyz + rz*nzz;
            double rlen = mot.body.segLength.get(rod);
            double e1x = mot.body.coord.get(rod) - 0.5*rlen*mot.body.uVec.get(rod);
            double e1y = mot.body.coord.get(nMB + rod) - 0.5*rlen*mot.body.uVec.get(nMB + rod);
            double e1z = mot.body.coord.get(2*nMB + rod) - 0.5*rlen*mot.body.uVec.get(2*nMB + rod);
            double sdx = pax-e1x, sdy = pay-e1y, sdz = paz-e1z;
            maxStrain = Math.max(maxStrain, Math.sqrt(sdx*sdx + sdy*sdy + sdz*sdz));
        }
        System.out.printf("  [nodeF t=%d scheme=%d] dist=%.4f  maxTetherStrain=%.4f µm (retention; rod≈%.3f µm off-surface ⇒ creep/fly-off)%n",
                t, SCHEME, nodeDist(s.sh.node), maxStrain, maxStrain);
        for (int k = 0; k < nN; k++) {
            double fx = nb.forceSum.get(k), fy = nb.forceSum.get(nN + k), fz = nb.forceSum.get(2 * nN + k);
            double fmag = Math.sqrt(fx * fx + fy * fy + fz * fz);
            double gam = nb.bTransGam.get(k);
            double dxStep = fx * dt / gam * 1e6;   // µm/step (x) the integrator applies
            double consv = Math.abs(rawx[k]) > 1e-30 ? fx / rawx[k] : 0;   // directed conservation NET/RAW (target ≈ 1)
            System.out.printf("    node %d @x=%+.4f: captured=%d  RAW xbridge |Σ|=%.3f pN (Σx=%+.3f pN)  ||  NET node |F|=%.4f pN (Fx=%+.4f pN)  CONSV(Fx/Σx)=%+.3f  ⇒ Δx≈%+.2e µm/step%n",
                    k, nb.coord.get(k), cap[k], rawmag[k] * 1e12, rawx[k] * 1e12, fmag * 1e12, fx * 1e12, consv, dxStep);
        }
    }

    // ---- readout helpers ----
    static double nodeDist(NodeStore nd) {
        RigidRodBody nb = nd.node; int n = nb.n;
        double dx = nb.coord.get(0) - nb.coord.get(1);
        double dy = nb.coord.get(n) - nb.coord.get(n + 1);
        double dz = nb.coord.get(2 * n) - nb.coord.get(2 * n + 1);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
    static int boundTotal(MotorStore m) { int c = 0; for (int i = 0; i < m.nMotors; i++) if (m.boundSeg.get(i) >= 0) c++; return c; }
    /** A segment's owning node: walk the chain toward the barbed/node end (end2NbrSlot, barbed=end2 convention)
     *  until the node-bonded TIP (seedNode>=0) is reached. CRITICAL: a split sets the child's seedNode=-1, so only
     *  the tip carries the tag — the OUTER segments (which actually reach the partner) resolve via the chain. */
    static int filNodeOf(S1 s, int seg) {
        int cur = seg, guard = 0, cap = s.fil.n;
        while (cur >= 0 && guard < cap) {
            int tag = s.nuc.seedNode.get(cur);
            if (tag >= 0) return tag;
            int nxt = s.fil.end2NbrSlot.get(cur);
            if (nxt == cur || nxt < 0) return -1;     // reached a free end with no tag (a dissolved/free filament)
            cur = nxt; guard++;
        }
        return -1;
    }
    /** A cross-node capture: motor m (owned by node X) bound to ANY segment of a filament nucleated by Y != X. */
    static int crossNodeCaptures(S1 s) {
        MotorStore m = s.sh.mot; int c = 0;
        for (int i = 0; i < m.nMotors; i++) {
            int seg = m.boundSeg.get(i);
            if (seg < 0) continue;
            int filNode = filNodeOf(s, seg);
            if (filNode >= 0 && filNode != s.sh.motorNode[i]) c++;
        }
        return c;
    }
    static int selfCaptures(S1 s) {
        MotorStore m = s.sh.mot; int c = 0;
        for (int i = 0; i < m.nMotors; i++) {
            int seg = m.boundSeg.get(i);
            if (seg < 0) continue;
            if (filNodeOf(s, seg) == s.sh.motorNode[i]) c++;
        }
        return c;
    }
    /** Transmitted cross-bridge force magnitude (pN, summed) over CROSS- (cross=true) or SELF- (cross=false)
     *  captured bonds — bondData head-force [d..d+2], the force that reaches the node body via the tether.
     *  jba's thesis: with the aimed layout, self-capture transmitted force ≈ 0 (precluded by geometry, no rule). */
    static double captureForcePN(S1 s, boolean cross) {
        MotorStore m = s.sh.mot; double sum = 0;
        for (int i = 0; i < m.nMotors; i++) {
            int seg = m.boundSeg.get(i); if (seg < 0) continue;
            int fn = filNodeOf(s, seg); if (fn < 0) continue;
            boolean isCross = (fn != s.sh.motorNode[i]);
            if (isCross != cross) continue;
            int d = i * CrossBridgeSystem.STRIDE;
            double fx = s.sh.bondData.get(d), fy = s.sh.bondData.get(d + 1), fz = s.sh.bondData.get(d + 2);
            sum += Math.sqrt(fx * fx + fy * fy + fz * fz);
        }
        return sum * 1e12;
    }
    static int activeFilaments(FilamentStore f) { int c = 0; for (int s = 0; s < f.n; s++) if (f.filState.get(s) >= 0) c++; return c; }
    static double contour(FilamentStore f) { double c = 0; for (int s = 0; s < f.n; s++) if (f.filState.get(s) >= 0) c += f.segLength.get(s); return c; }

    /** Characterize the residual self-capture (the geometry caveat): verify the v1 rule excludes node-held TIPs
     *  (must be 0 bound on a seedNode>=0 segment), and classify the remaining self-captures by whether the
     *  captured segment is the node-held tip (excluded) or an OUTER (seedNode<0) segment, + its distance from
     *  the owning node's centre (vs the own-myosin reach ≈ NODE_RADIUS + ROD+LEVER+HEAD + myoColTol). */
    static void diagnoseSelfCapture(S1 s) {
        MotorStore m = s.sh.mot; FilamentStore f = s.fil; RigidRodBody nb = s.sh.node.node; int nn = nb.n;
        int boundOnTip = 0, selfOuter = 0, crossOuter = 0; double selfOuterDistSum = 0, selfOuterDistMax = 0;
        double ownReach = NodeStore.NODE_RADIUS + MotorStore.ROD_LEN + MotorStore.LEVER_LEN + MotorStore.HEAD_LEN + REACH;
        for (int i = 0; i < m.nMotors; i++) {
            int seg = m.boundSeg.get(i); if (seg < 0) continue;
            if (s.nuc.seedNode.get(seg) >= 0) boundOnTip++;          // MUST be 0 with the rule
            int fn = filNodeOf(s, seg); int owner = s.sh.motorNode[i];
            boolean self = (fn == owner);
            // segment centre distance from the OWNING node (the node whose myosin this is)
            double dx = f.coordX(seg) - nb.coord.get(owner), dy = f.coordY(seg) - nb.coord.get(nn + owner), dz = f.coordZ(seg) - nb.coord.get(2 * nn + owner);
            double dist = Math.sqrt(dx*dx + dy*dy + dz*dz);
            if (self) { selfOuter++; selfOuterDistSum += dist; selfOuterDistMax = Math.max(selfOuterDistMax, dist); }
            else crossOuter++;
        }
        System.out.printf("  [diag] bound on node-held TIP (seedNode>=0; rule MUST exclude ⇒ 0): %d%n", boundOnTip);
        System.out.printf("  [diag] residual SELF-captures (all on OUTER seedNode<0 segments): %d; mean dist from own node=%.4f µm, max=%.4f (own-myosin reach≈%.4f µm)%n",
                selfOuter, selfOuter > 0 ? selfOuterDistSum / selfOuter : 0, selfOuterDistMax, ownReach);
        System.out.printf("  [diag] cross-captures (on the partner's outer segments): %d%n", crossOuter);
        System.out.println("  [diag] ⇒ POST convention-swap (barbed=end2 uniform): node-filament uVec points INWARD, so the gate's");
        System.out.println("         rodDotFil<0 rejects a node's own outward myosins ⇒ self-capture (tip AND outer) is eliminated at the root.");

        // ---- BIND-ORIENTATION LOG (inc 6c): the EMPIRICAL gate measurement. POST-SWAP (barbed=end2 uniform):
        // v2's node-filament uVec now points INWARD (toward the node) — the SAME convention as v1. So v1's gate
        // and v2's gate AGREE (no flip): both admit iff (motDot ≥ ALIGN_TOL) && (rodDot ≥ 0). A node's own outward
        // myosin · its own INWARD filament ⇒ rodDot < 0 ⇒ REJECTED ⇒ self-grab should be gone (selfN ≈ 0).
        MotorStore.publishHeadFromBody(m.body.coord, m.body.uVec, m.body.segLength, m.head, m.uVec, m.rodUVec, m.counts);
        int nM = m.nMotors;
        int selfN = 0, crossN = 0, selfV1Admit = 0, crossV1Admit = 0;
        double selfMotSum = 0, selfRodSum = 0, crossMotSum = 0, crossRodSum = 0;
        for (int i = 0; i < nM; i++) {
            int seg = m.boundSeg.get(i); if (seg < 0) continue;
            double hux = m.uVec.get(i), huy = m.uVec.get(nM + i), huz = m.uVec.get(2 * nM + i);
            double rux = m.rodUVec.get(i), ruy = m.rodUVec.get(nM + i), ruz = m.rodUVec.get(2 * nM + i);
            double fux = f.uVecX(seg), fuy = f.uVecY(seg), fuz = f.uVecZ(seg);
            double motDot = hux * fux + huy * fuy + huz * fuz;   // v2's gate value (uVec now inward = v1's convention)
            double rodDot = rux * fux + ruy * fuy + ruz * fuz;
            boolean v1Admit = (motDot >= ALIGN_TOL) && (rodDot >= 0.0);   // conventions AGREE now ⇒ v1 ≡ v2
            boolean self = (filNodeOf(s, seg) == s.sh.motorNode[i]);
            if (self) { selfN++; selfMotSum += motDot; selfRodSum += rodDot; if (v1Admit) selfV1Admit++; }
            else       { crossN++; crossMotSum += motDot; crossRodSum += rodDot; if (v1Admit) crossV1Admit++; }
        }
        System.out.printf("  [orient] gate thresholds: motDotFil >= ALIGN_TOL(%.2f), rodDotFil >= 0 (v1==v2 formula AND convention)%n", ALIGN_TOL);
        System.out.printf("  [orient] SELF  captures n=%d: mean motDotFil=%+.3f rodDotFil=%+.3f | would-v1-admit %d/%d ≡ would-v2-admit (post-swap, conventions agree)%n",
                selfN, selfN > 0 ? selfMotSum / selfN : 0, selfN > 0 ? selfRodSum / selfN : 0, selfV1Admit, selfN);
        System.out.printf("  [orient] CROSS captures n=%d: mean motDotFil=%+.3f rodDotFil=%+.3f | would-v1-admit %d/%d ≡ would-v2-admit%n",
                crossN, crossN > 0 ? crossMotSum / crossN : 0, crossN > 0 ? crossRodSum / crossN : 0, crossV1Admit, crossN);
        System.out.println("  [orient] ⇒ POST-SWAP: node-filament uVec is INWARD (= v1); a node's own outward myosin gives rodDotFil<0 on its own");
        System.out.println("           filament ⇒ v2's own (unmodified) gate REJECTS self-grab, as v1 does. Self-capture should be ≈0; cross-capture survives.");
    }

    // ====================================================================== Stage 1 runner + readout
    static int STEPS = 25000;
    static String vizDir = null;

    static boolean runStage1(double dt) {
        System.out.println("\n--- STAGE 1: the two-node SCPR assay (nucleate + grow + capture + pull) ---");
        System.out.printf("  geometry: gap(center-to-center)=%.3f µm, forminsPerNode=%d, shell=%d singlets+%d dimers/node, box=%.1f µm cube%n",
                GAP, FORMINS, N_SING, N_DIM, BOX);
        // capacity budget (flag): 2 nodes × forminsPerNode tips, each splitting into ~contour/seg32 children.
        System.out.printf("  filament-store capacity=%d slots (budget: 2×%d formins growing+splitting; bounds run length — flag)%n", FIL_CAP, FORMINS);
        S1 s = buildStage1(dt);
        if (vizDir != null) { runViz(s, dt); return true; }

        NodeStore nd = s.sh.node; MotorStore mot = s.sh.mot;
        double d0 = nodeDist(nd);
        int M = STEPS;
        int firstCapture = -1, peakCross = 0;
        double dMin = d0; int stepMin = 0;
        // capture-PHASE accumulators (steps where cross-capture is active — the approach, before any overrun)
        long capSteps = 0, crossCntSum = 0, selfCntSum = 0; double crossForceSum = 0, selfForceSum = 0;
        long dropoutSteps = 0;   // steps where a node has lost the partner filament (cross-capture <= 1)
        System.out.printf("  %-8s %-9s %-7s %-7s %-7s %-7s %-8s%n", "step", "dist(µm)", "cross", "self", "bound", "active", "contour");
        for (int t = 0; t < M; t++) {
            cpuStepStage1(s, t);
            double d = nodeDist(nd);
            int cross = crossNodeCaptures(s), self = selfCaptures(s), bound = boundTotal(mot);
            if (cross <= 1) dropoutSteps++;
            if (cross > 0 && firstCapture < 0) firstCapture = t;
            if (cross > peakCross) peakCross = cross;
            if (d < dMin) { dMin = d; stepMin = t; }
            if (cross > 0) { capSteps++; crossCntSum += cross; selfCntSum += self;
                             crossForceSum += captureForcePN(s, true); selfForceSum += captureForcePN(s, false); }
            if (t % Math.max(1, M / 20) == 0 || t == M - 1)
                System.out.printf("  %-8d %-9.4f %-7d %-7d %-7d %-7d %-8.4f%n",
                        t, d, cross, self, bound, activeFilaments(s.fil), contour(s.fil));
        }
        boolean captured = firstCapture >= 0;
        double dEnd = nodeDist(nd);
        // HEADLINE = the INITIAL approach signal: start → min (the capture-and-pull phase). Post-min drift
        // (the aimed filament OVERRUNS the closing gap — monotonic growth, no depoly) is OUT OF SCOPE (turnover).
        double approachToMin = d0 - dMin;
        // beyond Brownian noise: the node-body diffusive wander to the min-step (rel. coord ⇒ 4·D·T).
        double Dt = NODE_BROWN * NODE_BROWN * Constants.kT / nd.node.bTransGam.get(0);   // m²/s (brownScale² scales D)
        double Tmin = (stepMin + 1) * dt;
        double rmsWander = Math.sqrt(4.0 * Dt * Tmin) * 1.0e6;  // µm
        boolean approached = approachToMin > rmsWander && approachToMin > 0.02;   // beyond noise AND ≥20 nm

        System.out.printf("%n  inter-node distance: start=%.4f µm → MIN=%.4f µm @ step %d (initial approach Δ=%.4f µm); end=%.4f%n",
                d0, dMin, stepMin, approachToMin, dEnd);
        System.out.printf("  Brownian wander to min (%.3f s): rms≈%.4f µm => initial approach %s noise%n",
                Tmin, rmsWander, approached ? "EXCEEDS" : "within");
        if (dEnd > dMin + rmsWander)
            System.out.printf("  [post-min OVERRUN, OUT OF SCOPE] end %.4f > min %.4f: the aimed filament OVERRUNS the closed gap%n"
                    + "    (monotonic growth + no depoly ⇒ capture geometry breaks; needs turnover/treadmilling — deferred).%n", dEnd, dMin);
        double avgCross = capSteps > 0 ? crossCntSum / (double) capSteps : 0, avgSelf = capSteps > 0 ? selfCntSum / (double) capSteps : 0;
        double crossF = capSteps > 0 ? crossForceSum / capSteps : 0, selfF = capSteps > 0 ? selfForceSum / capSteps : 0;
        System.out.printf("  cross-node captures: first @ step %s, peak=%d, capture-phase avg=%.2f%n",
                captured ? String.valueOf(firstCapture) : "NEVER", peakCross, avgCross);
        System.out.printf("  FILAMENT-LOSS drop-outs (cross-capture<=1): %d of %d steps (%.1f%%)  [scheme=%d aimtorque=%.2f]%n",
                dropoutSteps, M, 100.0 * dropoutSteps / M, SCHEME, AIM_TORQUE);
        System.out.printf("  self-capture (jba's layout thesis): capture-phase avg count=%.2f; transmitted force self=%.3f pN vs cross=%.3f pN (self/cross=%.2f)%n",
                avgSelf, selfF, crossF, crossF > 1e-9 ? selfF / crossF : 0);
        System.out.printf("  final: active filaments=%d, contour=%.4f µm%n", activeFilaments(s.fil), contour(s.fil));
        if (!captured) {
            // ETA estimate: per-tip growth rate (mono/cadence) → contour velocity → time to bridge the surface gap.
            double Pgrow = Constants.kATPOn2WithFormin * Constants.actinConcInit * Constants.biochemDeltaT;
            double monoPerStep = Pgrow / s.grow.biochemCheckInt;
            double umPerStep = monoPerStep * Constants.actinMonoRadius;           // contour µm per step for one tip
            double surfaceGap = GAP - 2 * (NodeStore.NODE_RADIUS + MotorStore.ROD_LEN);
            double etaSteps = umPerStep > 0 ? surfaceGap / umPerStep : Double.POSITIVE_INFINITY;
            System.out.printf("  [observation] no cross-node capture this run (SCPR is a stochastic search; rare at n=2).%n");
            System.out.printf("  [ETA] a well-aimed tip bridges ~%.3f µm at ~%.2e µm/step ⇒ ~%.0f steps of aimed growth (× search inefficiency).%n",
                    surfaceGap, umPerStep, etaSteps);
        }
        diagnoseSelfCapture(s);   // the geometry caveat: verify the rule excludes tips + characterize the residual
        boolean ok = captured && approached;
        System.out.println("  => STAGE 1 " + (ok ? "demonstrates SCPR capture-and-pull (nodes approach beyond noise)"
                : captured ? "captured but approach not yet beyond noise (longer run / see ETA)"
                : "no capture this run (stochastic search — see ETA; observation, not a failure)"));
        return ok;
    }

    // ====================================================================== GPU TaskGraph (full SCPR pipeline)
    static TornadoExecutionPlan buildPlanStage1(S1 s) {
        Shells sh = s.sh; MotorStore mot = sh.mot; DimerStore dim = sh.dim; NodeStore nd = sh.node;
        FilamentStore f = s.fil; NodeNucleationStore nuc = s.nuc; GrowthStore grow = s.grow;
        RigidRodBody b = mot.body; RigidRodBody nb = nd.node;
        TaskGraph tg = new TaskGraph("scpr")
            .transferToDevice(DataTransferMode.FIRST_EXECUTION,
                    // motor body + kinetics
                    b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, b.bTransGam, b.bRotGam,
                    b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.brownTransScale, b.brownRotScale,
                    mot.head, mot.uVec, mot.rodUVec, mot.boundSeg, mot.bindArc, mot.nucleotideState,
                    mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.stats, mot.capStats, mot.cooldown,
                    mot.bodyParams, mot.jointParams, mot.nucParams, mot.kinParams,
                    dim.motorA, dim.motorB, dim.parallel, dim.dimerParams,
                    // node body + tether/gather
                    nb.coord, nb.uVec, nb.yVec, nb.zVec, nb.end1, nb.end2, nb.segLength, nb.bTransGam, nb.bRotGam,
                    nb.forceSum, nb.torqueSum, nb.randForce, nb.randTorque, nb.brownTransScale, nb.brownRotScale, nd.nodeBodyParams,
                    nd.nodeInvTransY, nd.attachNode, nd.attachKey, nd.radial, nd.attachCoeffK, nd.nodeData, nd.nodeParams,
                    nd.nodeAttachCount, nd.nodeAttachOffsets, nd.nodeAttachList, nd.nodeCounts4,
                    // cross-bridge scratch + box
                    sh.bondData, sh.xbParams, sh.segMotorCount, sh.segMotorOffsets, sh.segMotorMyo, sh.reachSeg, sh.reachCount, s.boxParams,
                    // filament + chain + growth + nucleation
                    f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.monomerCount, f.bTransGam, f.bRotGam, f.bTransDiff, f.bRotDiff,
                    f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.params, f.chainParams,
                    f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide, f.filState,
                    f.freeCount, f.freeOffsets, f.freeList, f.freeScanCounts, f.rankOffsets, f.rankScanCounts, f.allocCounts, f.birthParams,
                    f.acceptFlag, f.reqCoord, f.reqUVec, f.reqYVec,
                    grow.grewFlag, grow.grewOffsets, grow.grewScanCounts, grow.growParams, grow.splitParams, grow.dragParams,
                    nuc.seedNode, nuc.nodeBoundFil, nuc.nucParams, nuc.tetherParams, nuc.dissolveParams,
                    s.nucAccept, s.nucReqCoord, s.nucReqUVec, s.nucReqYVec, s.nucRankOffsets, s.nucRankScanCounts)
            .transferToDevice(DataTransferMode.EVERY_EXECUTION, mot.counts, nd.nodeBodyCounts, f.counts, grow.growCounts, nuc.nucCounts)
            // === GROWTH (FilamentStore request arrays) ===
            .task("grow", GrowthSystem::grow, nuc.seedNode, f.monomerCount, f.coord, f.uVec, grow.grewFlag, grow.growParams, grow.growCounts)
            .task("csrGrew", CrossBridgeSystem::csrScan, grow.grewScanCounts, grow.grewFlag, grow.grewOffsets)
            .task("markSplits", GrowthSystem::markSplits, nuc.seedNode, f.monomerCount, f.coord, f.uVec, f.yVec, f.acceptFlag, f.reqCoord, f.reqUVec, f.reqYVec, grow.splitParams, grow.growCounts)
            .task("gFreeFlags", FilamentBirthSystem::freeFlags, f.filState, f.freeCount, f.allocCounts)
            .task("gCsrFree", CrossBridgeSystem::csrScan, f.freeScanCounts, f.freeCount, f.freeOffsets)
            .task("gFreeScatter", FilamentBirthSystem::freeScatter, f.filState, f.freeOffsets, f.freeList, f.allocCounts)
            .task("gCsrRank", CrossBridgeSystem::csrScan, f.rankScanCounts, f.acceptFlag, f.rankOffsets)
            .task("gAllocate", FilamentBirthSystem::allocate, f.reqCoord, f.reqUVec, f.reqYVec, f.rankOffsets, f.freeList, f.freeOffsets, f.coord, f.uVec, f.yVec, f.brownTransScale, f.brownRotScale, f.filState, f.birthParams, f.allocCounts)
            .task("splitWire", GrowthSystem::splitWire, f.rankOffsets, f.freeList, f.freeOffsets, f.monomerCount, f.coord, f.uVec, f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide, nuc.seedNode, grow.splitParams, f.allocCounts)
            .task("recomputeDrag", GrowthSystem::recomputeDrag, f.monomerCount, f.segLength, f.end1NbrSlot, f.end2NbrSlot, f.bTransGam, f.bRotGam, f.bTransDiff, f.bRotDiff, grow.dragParams, grow.growCounts)
            // === NUCLEATION (dedicated request arrays; rebuild free-list excluding growth's children) ===
            .task("count", NodeNucleationSystem::countBoundFil, nuc.seedNode, nuc.nodeBoundFil, nuc.nucCounts)
            .task("emit", NodeNucleationSystem::emit, nb.coord, nuc.nodeBoundFil, s.nucAccept, s.nucReqCoord, s.nucReqUVec, s.nucReqYVec, nuc.nucParams, nuc.nucCounts)
            .task("nFreeFlags", FilamentBirthSystem::freeFlags, f.filState, f.freeCount, f.allocCounts)
            .task("nCsrFree", CrossBridgeSystem::csrScan, f.freeScanCounts, f.freeCount, f.freeOffsets)
            .task("nFreeScatter", FilamentBirthSystem::freeScatter, f.filState, f.freeOffsets, f.freeList, f.allocCounts)
            .task("nCsrRank", CrossBridgeSystem::csrScan, s.nucRankScanCounts, s.nucAccept, s.nucRankOffsets)
            .task("nAllocate", FilamentBirthSystem::allocate, s.nucReqCoord, s.nucReqUVec, s.nucReqYVec, s.nucRankOffsets, f.freeList, f.freeOffsets, f.coord, f.uVec, f.yVec, f.brownTransScale, f.brownRotScale, f.filState, f.birthParams, f.allocCounts)
            .task("tagSeeds", NodeNucleationSystem::tagSeeds, s.nucRankOffsets, f.freeList, f.freeOffsets, nuc.seedNode, f.allocCounts)
            // === BINDING + cycle ===
            .task("publishHead", MotorStore::publishHeadFromBody, b.coord, b.uVec, b.segLength, mot.head, mot.uVec, mot.rodUVec, mot.counts)
            .task("reach", BindingDetectionSystem::bruteReachableNodeAware, mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, sh.reachSeg, sh.reachCount, nuc.seedNode, mot.kinParams, mot.counts)
            .task("release", NucleotideCycleSystem::catchSlipRelease, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.cooldown, mot.stats, mot.capStats, mot.kinParams, mot.counts)
            .task("bind", BindingDetectionSystem::bindNearestNodeAware, mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, sh.reachSeg, sh.reachCount, mot.boundSeg, mot.bindArc, nuc.seedNode, mot.kinParams, mot.counts)
            .task("cycle", NucleotideCycleSystem::cycle, mot.nucleotideState, mot.boundSeg, mot.forceDotHist, mot.nucParams, mot.counts)
            // === FORCES ===
            .task("zeroMot", ChainBendingForceSystem::zeroAccumulators, b.forceSum, b.torqueSum, mot.counts)
            .task("zeroNode", ChainBendingForceSystem::zeroAccumulators, nb.forceSum, nb.torqueSum, nd.nodeBodyCounts)
            .task("zeroFil", ChainBendingForceSystem::zeroAccumulators, f.forceSum, f.torqueSum, f.counts)
            .task("brownMot", BrownianForceSystem::brownianForce, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, b.brownTransScale, b.brownRotScale, mot.bodyParams, mot.counts)
            .task("brownNode", BrownianForceSystem::brownianForce, nb.randForce, nb.randTorque, nb.bTransGam, nb.bRotGam, nb.brownTransScale, nb.brownRotScale, nd.nodeBodyParams, nd.nodeBodyCounts)
            .task("brownFil", BrownianForceSystem::brownianForce, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.brownTransScale, f.brownRotScale, f.params, f.counts)
            .task("joints", MotorJointSystem::joints, b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, mot.nucleotideState, mot.jointParams, mot.counts)
            .task("dimer", DimerCouplingSystem::couple, b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, dim.motorA, dim.motorB, dim.parallel, dim.dimerParams, mot.boundSeg)
            .task("tether", NodeSystem::tether, b.coord, b.uVec, b.segLength, b.bTransGam, b.forceSum, b.torqueSum, nb.coord, nb.uVec, nb.yVec, nd.nodeInvTransY, nd.attachKey, nd.radial, nd.attachCoeffK, nd.nodeData, nd.nodeParams)
            .task("ndHist", CrossBridgeSystem::csrHistogram, nd.attachNode, nd.nodeCounts4, nd.nodeAttachCount)
            .task("ndScan", CrossBridgeSystem::csrScan, nd.nodeCounts4, nd.nodeAttachCount, nd.nodeAttachOffsets)
            .task("ndScatter", CrossBridgeSystem::csrScatter, nd.attachNode, nd.nodeCounts4, nd.nodeAttachOffsets, nd.nodeAttachCount, nd.nodeAttachList)
            .task("ndGather", MiniFilamentSystem::backboneGather, nd.nodeAttachOffsets, nd.nodeAttachList, nd.nodeData, nb.forceSum, nb.torqueSum, nd.nodeCounts4)
            .task("bond", CrossBridgeSystem::bondForces, b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength, mot.boundSeg, mot.bindArc, mot.nucleotideState, sh.bondData, sh.xbParams)
            .task("applyHead", CrossBridgeSystem::applyHeadForce, sh.bondData, b.forceSum, b.torqueSum, mot.counts)
            .task("chain", ChainBendingForceSystem::chainForces, f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide, f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts)
            .task("seedTether", NodeNucleationSystem::seedTether, f.coord, f.uVec, f.segLength, f.bTransGam, f.forceSum, f.torqueSum, nb.coord, nd.nodeInvTransY, nuc.seedNode, nuc.tetherParams)
            .task("seedReact", NodeNucleationSystem::seedTetherNodeReact, f.coord, f.uVec, f.segLength, f.bTransGam, nb.forceSum, nb.coord, nd.nodeInvTransY, nuc.seedNode, nuc.tetherParams)
            .task("filHist", CrossBridgeSystem::csrHistogram, mot.boundSeg, mot.counts, sh.segMotorCount)
            .task("filScan", CrossBridgeSystem::csrScan, mot.counts, sh.segMotorCount, sh.segMotorOffsets)
            .task("filScatter", CrossBridgeSystem::csrScatter, mot.boundSeg, mot.counts, sh.segMotorOffsets, sh.segMotorCount, sh.segMotorMyo)
            .task("filGather", CrossBridgeSystem::segGather, sh.segMotorOffsets, sh.segMotorMyo, sh.bondData, f.forceSum, f.torqueSum, mot.counts)
            .task("register", CrossBridgeSystem::registerForceDot, sh.bondData, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.counts)
            // === INTEGRATE ===
            .task("confineNode", ContainmentSystem::confine, nb.coord, nb.uVec, nb.segLength, nb.bTransGam, nb.forceSum, nb.torqueSum, s.boxParams, nd.nodeBodyCounts)
            .task("integNode", RigidRodLangevinIntegrationSystem::integrate, nb.coord, nb.uVec, nb.yVec, nb.forceSum, nb.torqueSum, nb.randForce, nb.randTorque, nb.bTransGam, nb.bRotGam, nd.nodeBodyParams, nd.nodeBodyCounts)
            .task("deriveNode", DerivedGeometrySystem::derive, nb.coord, nb.uVec, nb.yVec, nb.zVec, nb.end1, nb.end2, nb.segLength, nd.nodeBodyCounts)
            .task("integM", RigidRodLangevinIntegrationSystem::integrate, b.coord, b.uVec, b.yVec, b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, mot.bodyParams, mot.counts)
            .task("deriveM", DerivedGeometrySystem::derive, b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts)
            .task("integFil", RigidRodLangevinIntegrationSystem::integrate, f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts)
            .task("deriveFil", DerivedGeometrySystem::derive, f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts)
            .transferToHost(DataTransferMode.UNDER_DEMAND, nb.coord, mot.boundSeg, nuc.seedNode, f.filState, f.coord);

        int nMB = b.n, nN = nb.n, nM = mot.nMotors, C = f.n, nD = dim.nDimers, nA = nd.nAttach;
        sched = new GridScheduler();
        for (String t : new String[]{ "publishHead","reach","release","bind","cycle","bond","applyHead","register" }) addW("scpr." + t, pad(nM));
        for (String t : new String[]{ "zeroMot","brownMot","joints","integM","deriveM" }) addW("scpr." + t, pad(nMB));
        for (String t : new String[]{ "zeroNode","brownNode","ndGather","seedReact","confineNode","integNode","deriveNode" }) addW("scpr." + t, pad(nN));
        addW("scpr.dimer", pad(nD));
        addW("scpr.tether", pad(nA));
        addW("scpr.emit", pad(nN));
        for (String t : new String[]{ "grow","markSplits","recomputeDrag","gFreeFlags","gAllocate","nFreeFlags","nAllocate","tagSeeds","splitWire",
                                       "zeroFil","brownFil","chain","seedTether","filGather","integFil","deriveFil" }) addW("scpr." + t, pad(C));
        for (String t : new String[]{ "csrGrew","gCsrFree","gFreeScatter","gCsrRank","count","nCsrFree","nFreeScatter","nCsrRank",
                                       "ndHist","ndScan","ndScatter","filHist","filScan","filScatter" }) addS("scpr." + t);
        return new TornadoExecutionPlan(tg.snapshot());
    }

    /** CPU≡GPU on the chaotic SCPR run: aggregate-within-tolerance (CLAUDE.md standard — float32 op-ordering
     *  decorrelates the microstate over a long horizon, so bit-identity is unattainable; the test is that the
     *  GPU TaskGraph reproduces the same aggregate behaviour — windowed bound-head count + active-filament count
     *  + the lifecycle decisions track). */
    static boolean checkCpuGpu(double dt) {
        if (cpu) { System.out.println("\n--- CPU≡GPU: skipped (-cpu) ---"); return true; }
        System.out.println("\n--- CPU≡GPU (chaotic SCPR; aggregate-within-tolerance) ---");
        int M = 3000, sampleEvery = 300;
        S1 g = buildStage1(dt), c = buildStage1(dt);
        TornadoExecutionPlan plan = buildPlanStage1(g);
        double gBound = 0, cBound = 0; int nSamp = 0;
        for (int t = 0; t < M; t++) {
            g.sh.mot.setCounts(t, SEED, g.fil.n); g.sh.node.setNodeBodyCounts(t, SEED_NODE_A ^ SEED_NODE_B); g.fil.setCounts(t, SEED);
            g.nuc.setCounts(t, SEED); g.nuc.refreshPoolGate();
            boolean fires = GROWTH_ON && g.grow.firesAt(t);
            g.grow.setCounts(t, SEED, fires); g.grow.refreshRate(GROWTH_ON);
            if (POLY_RATE != 1.0) g.grow.growParams.set(0, (float) (Math.min(g.grow.growParams.get(0), 1.0) * POLY_RATE));  // -polyrate scale
            TornadoExecutionResult res = plan.withGridScheduler(sched).execute();
            if (t % sampleEvery == sampleEvery - 1) { res.transferToHost(g.sh.mot.boundSeg); gBound += boundTotal(g.sh.mot); }
            if (t == M - 1) res.transferToHost(g.fil.filState);   // pull the lifecycle back for the active-fil count
        }
        for (int t = 0; t < M; t++) { cpuStepStage1(c, t); if (t % sampleEvery == sampleEvery - 1) { cBound += boundTotal(c.sh.mot); nSamp++; } }
        double gAvg = gBound / nSamp, cAvg = cBound / nSamp;
        int gAct = activeFilaments(g.fil), cAct = activeFilaments(c.fil);
        boolean ok = Math.abs(gAvg - cAvg) <= 3.0 && gAvg > 0.5 && cAvg > 0.5 && Math.abs(gAct - cAct) <= 2;
        System.out.printf("  %d steps: windowed avgBound GPU=%.2f CPU=%.2f (|Δ|≤3); active-fil GPU=%d CPU=%d (|Δ|≤2) => %s%n",
                M, gAvg, cAvg, gAct, cAct, ok ? "aggregate-agree" : "*FAIL*");
        return ok;
    }

    // ---- viewer (v1-style frames: segments + node spheres + motors) ----
    static final String[] STATE_NAME = { "NONE", "ATP", "ADPPi", "ADP" };
    static void runViz(S1 s, double dt) {
        new java.io.File(vizDir).mkdirs();
        int M = STEPS, every = Math.max(1, M / 400), frames = 0;
        for (int t = 0; t <= M; t++) {
            cpuStepStage1(s, t);
            if (t % every == 0) writeFrame(vizDir, frames++, t, t * dt, s);
        }
        System.out.printf("viewer: wrote %d frames to %s; final dist=%.4f µm, cross-captures=%d, active=%d%n",
                frames, vizDir, nodeDist(s.sh.node), crossNodeCaptures(s), activeFilaments(s.fil));
    }
    static void writeFrame(String dir, int frame, int step, double t, S1 s) {
        FilamentStore f = s.fil; MotorStore mot = s.sh.mot; RigidRodBody b = mot.body; RigidRodBody nb = s.sh.node.node;
        StringBuilder sb = new StringBuilder(2048 + 200 * mot.nMotors);
        sb.append(String.format(java.util.Locale.US, "{\"frame\":%d,\"t\":%.6g,\"bounds\":{\"xDim\":%.2f,\"yDim\":%.2f,\"zDim\":%.2f}",
                frame, t, BOX, BOX, BOX));
        sb.append(",\"segments\":[");
        boolean first = true;
        for (int seg = 0; seg < f.n; seg++) {
            if (f.filState.get(seg) < 0) continue;            // skip parked FREE slots
            if (!first) sb.append(','); first = false;
            sb.append(String.format(java.util.Locale.US, "{\"id\":%d,\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.5g,\"notADPRatio\":1.0,\"cofilinCount\":0}",
                seg, f.end1.get(seg), f.end1.get(f.n+seg), f.end1.get(2*f.n+seg), f.end2.get(seg), f.end2.get(f.n+seg), f.end2.get(2*f.n+seg), Constants.radius));
        }
        sb.append("],\"myosins\":[");
        // two node spheres (degenerate motors with state "node")
        for (int k = 0; k < 2; k++) {
            if (k > 0) sb.append(',');
            double cx = nb.coord.get(k), cy = nb.coord.get(nb.n + k), cz = nb.coord.get(2*nb.n + k);
            sb.append(String.format(java.util.Locale.US,
                "{\"id\":%d,\"rod\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g},"
                + "\"lever\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g},"
                + "\"motor\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g,\"state\":\"node\"}}",
                900000+k, cx,cy,cz, cx,cy,cz, NodeStore.NODE_RADIUS, cx,cy,cz, cx,cy,cz, NodeStore.NODE_RADIUS, cx,cy,cz, cx,cy,cz, NodeStore.NODE_RADIUS));
        }
        for (int m = 0; m < mot.nMotors; m++) {
            sb.append(',');
            int rod = 3*m, lever = 3*m+1, head = 3*m+2; String state = STATE_NAME[mot.nucleotideState.get(m)];
            sb.append(String.format(java.util.Locale.US,
                "{\"id\":%d,\"rod\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g,\"invisible\":false},"
                + "\"lever\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g},"
                + "\"motor\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g,\"state\":\"%s\"}}",
                m, b.end1X(rod),b.end1Y(rod),b.end1Z(rod), b.end2X(rod),b.end2Y(rod),b.end2Z(rod), MotorStore.ROD_R,
                b.end1X(lever),b.end1Y(lever),b.end1Z(lever), b.end2X(lever),b.end2Y(lever),b.end2Z(lever), MotorStore.LEVER_R,
                b.end1X(head),b.end1Y(head),b.end1Z(head), b.end2X(head),b.end2Y(head),b.end2Z(head), MotorStore.HEAD_R, state));
        }
        sb.append("]");
        sb.append(String.format(java.util.Locale.US, ",\"stats\":{\"step\":%d,\"simTime\":%.5g,\"nodeDist_um\":%.5g,\"crossCaptures\":%d,\"boundHeads\":%d,\"activeFil\":%d,\"contour_um\":%.5g}",
                step, t, nodeDist(s.sh.node), crossNodeCaptures(s), boundTotal(mot), activeFilaments(f), contour(f)));
        sb.append("}");
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(dir, String.format(java.util.Locale.US,"frame_%06d.json", frame)), sb.toString()); }
        catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }

    // ====================================================================== dimer placement (6b-splayed; from NodeContractileHarness)
    static void placeDimerAlong(MotorStore mot, int mA, int mB,
                                double e1x, double e1y, double e1z, double dx, double dy, double dz) {
        double dm = Math.sqrt(dx*dx+dy*dy+dz*dz); dx/=dm; dy/=dm; dz/=dm;
        double px = -dz, py = 0, pz = dx;
        double pm = Math.sqrt(px*px+py*py+pz*pz);
        if (pm < 1e-4) { px = 1; py = 0; pz = 0; pm = 1; }
        px/=pm; py/=pm; pz/=pm;
        double rl = MotorStore.ROD_LEN, ll = MotorStore.LEVER_LEN, hl = MotorStore.HEAD_LEN;
        double rcx=e1x+0.5*rl*dx, rcy=e1y+0.5*rl*dy, rcz=e1z+0.5*rl*dz;
        double e2x=e1x+rl*dx, e2y=e1y+rl*dy, e2z=e1z+rl*dz;
        placeArm(mot, mA, rcx,rcy,rcz, dx,dy,dz, px,py,pz, e2x,e2y,e2z,  +1, ll, hl);
        placeArm(mot, mB, rcx,rcy,rcz, dx,dy,dz, px,py,pz, e2x,e2y,e2z,  -1, ll, hl);
    }
    static void placeArm(MotorStore mot, int m, double rcx, double rcy, double rcz,
                         double dx, double dy, double dz, double px, double py, double pz,
                         double e2x, double e2y, double e2z, int splay, double ll, double hl) {
        int rod = mot.rodIdx(m), lever = mot.leverIdx(m), head = mot.headIdx(m);
        RigidRodBody b = mot.body;
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

    static int pad(int n) { return ((n + B - 1) / B) * B; }
    static void addW(String n, int g) { WorkerGrid w = new WorkerGrid1D(g); w.setLocalWork(B, 1, 1); sched.addWorkerGrid(n, w); }
    static void addS(String n) { WorkerGrid w = new WorkerGrid1D(1); w.setLocalWork(1, 1, 1); sched.addWorkerGrid(n, w); }
}
