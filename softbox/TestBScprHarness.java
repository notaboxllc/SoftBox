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
                case "-3js" -> vizDir = args[++i];
                case "-gate0" -> gate0Only = true;
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
        boolean cg = (vizDir == null) ? checkCpuGpu(dt) : true;
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
                    double coeff = NodeStore.ATTN_FORCE / nSing;
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
        f.setParams(dt, 0); f.setChainParams();
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

    // ----------------------------------------------------------------- SEAM #3: formin-site placement
    // A node carries multiple formin sites (forminsPerNode); each site's RADIAL DIRECTION sets whether its
    // filament searches toward the partner. Test B is the first scene where placement matters behaviorally.
    // The placement is a PLUGGABLE function: RANDOM-radial today (biology TBD), a SPECIFIED arrangement later
    // (e.g. a defined inter-site geometry) plugs in here without a refactor. Test B stays on RANDOM (the default,
    // the same wang-hash draw the runtime nucleation emit uses) — specified placement is NOT built (jba's call).
    enum Placement { RANDOM /* , SPECIFIED — future: a defined inter-site arrangement */ }
    static Placement PLACEMENT = Placement.RANDOM;
    /** Unit radial direction for formin site `site` on node `nodeK`. The seam: swap this body for a specified
     *  arrangement to aim filaments; RANDOM (wang-hash, isotropic) is the faithful SCPR stochastic-search default. */
    static double[] forminSiteDir(int nodeK, int site) {
        switch (PLACEMENT) {
            case RANDOM -> {
                int base = ((nodeK * FORMINS + site) * 1000003) ^ (nodeK * 999983) ^ 0x53434252;   // "SCBR"
                double dx = ((wangHash(base ^ 0x9e3779b9) >>> 1) / 2147483647.0) * 2 - 1;
                double dy = ((wangHash(base ^ 0x85ebca6b) >>> 1) / 2147483647.0) * 2 - 1;
                double dz = ((wangHash(base ^ 0xc2b2ae35) >>> 1) / 2147483647.0) * 2 - 1;
                double m2 = dx*dx + dy*dy + dz*dz; if (m2 < 1e-9) { dx = 1; dy = 0; dz = 0; m2 = 1; }
                double inv = 1.0 / Math.sqrt(m2);
                return new double[]{ dx * inv, dy * inv, dz * inv };
            }
            default -> { return new double[]{ 1, 0, 0 }; }
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
        s.sh = buildShells(dt, new double[]{ -h, 0, 0 }, new double[]{ h, 0, 0 }, false);   // FREE nodes
        NodeStore nd = s.sh.node; MotorStore mot = s.sh.mot;
        int cap = FIL_CAP;

        FilamentStore f = new FilamentStore(cap, cap);                 // reqCap == capacity (request index == slot, growth)
        for (int sl = 0; sl < cap; sl++) f.monomerCount.set(sl, Constants.actinSeed);
        DragTensorSystem.run(f);
        f.setParams(dt, Constants.brownianForceMag());
        f.setChainParams();
        double bornScale = Constants.BTransCoeff / 30.0;               // damped seed (B2 dt-compensation; held near the node)
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
        double halfSeed = 0.5 * (SEED_MON + 1) * Constants.actinMonoRadius;
        for (int k = 0; k < 2; k++) {
            double ncx = nd.node.coord.get(k), ncy = nd.node.coord.get(nd.node.n + k), ncz = nd.node.coord.get(2 * nd.node.n + k);
            for (int j = 0; j < FORMINS && (k * FORMINS + j) < cap; j++) {
                int sl = k * FORMINS + j;
                double[] d = forminSiteDir(k, j);              // SEAM #3: pluggable formin-site placement
                double dx = d[0], dy = d[1], dz = d[2];
                double ex = (Math.abs(dx) < 0.9) ? 1 : 0, ey = (Math.abs(dx) < 0.9) ? 0 : 1, ez = 0;
                double yx = dy*ez - dz*ey, yy = dz*ex - dx*ez, yz = dx*ey - dy*ex;
                double ym = 1.0 / Math.sqrt(yx*yx + yy*yy + yz*yz); yx *= ym; yy *= ym; yz *= ym;
                f.monomerCount.set(sl, SEED_MON);
                f.setCoord(sl, (float) (ncx + halfSeed * dx), (float) (ncy + halfSeed * dy), (float) (ncz + halfSeed * dz));
                f.setUVec(sl, (float) dx, (float) dy, (float) dz);
                f.setYVec(sl, (float) yx, (float) yy, (float) yz);
                f.filState.set(sl, FilamentStore.FIL_ACTIVE);
                f.brownTransScale.set(sl, (float) bornScale); f.brownRotScale.set(sl, (float) bornScale);
                nuc.seedNode.set(sl, k);
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
        BindingDetectionSystem.bruteReachable(mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, sh.reachSeg, sh.reachCount, mot.kinParams, mot.counts);
        NucleotideCycleSystem.catchSlipRelease(mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.cooldown, mot.stats, mot.capStats, mot.kinParams, mot.counts);
        BindingDetectionSystem.bindNearest(mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, sh.reachSeg, sh.reachCount, mot.boundSeg, mot.bindArc, mot.kinParams, mot.counts);
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
        // node radial surface tether + the single-ended backbone-side CSR gather (motor reaction → node body)
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
        // filament: chain (F3/F4) + the seedNode pull bond (node-center↔tip) + the gathered cross-bridge reaction
        ChainBendingForceSystem.chainForces(f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide, f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts);
        NodeNucleationSystem.seedTether(f.coord, f.uVec, f.segLength, f.bTransGam, f.forceSum, f.torqueSum,
                nb.coord, nd.nodeInvTransY, nuc.seedNode, nuc.tetherParams);
        CrossBridgeSystem.csrHistogram(mot.boundSeg, mot.counts, sh.segMotorCount);
        CrossBridgeSystem.csrScan(mot.counts, sh.segMotorCount, sh.segMotorOffsets);
        CrossBridgeSystem.csrScatter(mot.boundSeg, mot.counts, sh.segMotorOffsets, sh.segMotorCount, sh.segMotorMyo);
        CrossBridgeSystem.segGather(sh.segMotorOffsets, sh.segMotorMyo, sh.bondData, f.forceSum, f.torqueSum, mot.counts);
        CrossBridgeSystem.registerForceDot(sh.bondData, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.counts);

        // === INTEGRATE (node body confined+integrated; motor body; filament) ===
        ContainmentSystem.confine(nb.coord, nb.uVec, nb.segLength, nb.bTransGam, nb.forceSum, nb.torqueSum, s.boxParams, nd.nodeBodyCounts);
        RigidRodLangevinIntegrationSystem.integrate(nb.coord, nb.uVec, nb.yVec, nb.forceSum, nb.torqueSum, nb.randForce, nb.randTorque, nb.bTransGam, nb.bRotGam, nd.nodeBodyParams, nd.nodeBodyCounts);
        DerivedGeometrySystem.derive(nb.coord, nb.uVec, nb.yVec, nb.zVec, nb.end1, nb.end2, nb.segLength, nd.nodeBodyCounts);
        RigidRodLangevinIntegrationSystem.integrate(b.coord, b.uVec, b.yVec, b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, mot.bodyParams, mot.counts);
        DerivedGeometrySystem.derive(b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts);
        RigidRodLangevinIntegrationSystem.integrate(f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts);
        DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
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
    /** A segment's owning node: walk the chain toward the barbed/node end (end1NbrSlot) until the node-bonded
     *  TIP (seedNode>=0) is reached. CRITICAL: a split sets the child's seedNode=-1, so only the tip carries the
     *  tag — the OUTER segments (which actually reach the partner) must be resolved via the chain. */
    static int filNodeOf(S1 s, int seg) {
        int cur = seg, guard = 0, cap = s.fil.n;
        while (cur >= 0 && guard < cap) {
            int tag = s.nuc.seedNode.get(cur);
            if (tag >= 0) return tag;
            int nxt = s.fil.end1NbrSlot.get(cur);
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
    static int activeFilaments(FilamentStore f) { int c = 0; for (int s = 0; s < f.n; s++) if (f.filState.get(s) >= 0) c++; return c; }
    static double contour(FilamentStore f) { double c = 0; for (int s = 0; s < f.n; s++) if (f.filState.get(s) >= 0) c += f.segLength.get(s); return c; }

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
        long crossStepSum = 0; long crossSteps = 0;
        double dMin = d0, dMax = d0;
        // approach detection: mean distance over the last 10% vs the first 10% (beyond Brownian noise)
        double earlySum = 0, lateSum = 0; long earlyN = 0, lateN = 0;
        System.out.printf("  %-8s %-9s %-7s %-7s %-7s %-7s %-8s%n", "step", "dist(µm)", "cross", "self", "bound", "active", "contour");
        for (int t = 0; t < M; t++) {
            cpuStepStage1(s, t);
            double d = nodeDist(nd);
            int cross = crossNodeCaptures(s), self = selfCaptures(s), bound = boundTotal(mot);
            if (cross > 0 && firstCapture < 0) firstCapture = t;
            if (cross > peakCross) peakCross = cross;
            dMin = Math.min(dMin, d); dMax = Math.max(dMax, d);
            if (t >= M - M / 10) { lateSum += d; lateN++; crossStepSum += cross; crossSteps++; }
            if (t < M / 10) { earlySum += d; earlyN++; }
            if (t % Math.max(1, M / 20) == 0 || t == M - 1)
                System.out.printf("  %-8d %-9.4f %-7d %-7d %-7d %-7d %-8.4f%n",
                        t, d, cross, self, bound, activeFilaments(s.fil), contour(s.fil));
        }
        double dEarly = earlyN > 0 ? earlySum / earlyN : d0;
        double dLate = lateN > 0 ? lateSum / lateN : nodeDist(nd);
        double approach = dEarly - dLate;
        double avgCross = crossSteps > 0 ? crossStepSum / (double) crossSteps : 0;
        boolean captured = firstCapture >= 0;
        // approach "beyond Brownian noise": compare to the node-body diffusive wander over the run.
        // Free node D_t = kT/bTransGam; RMS COM wander of the 2-node difference over time T ~ sqrt(4 D_t T).
        double Dt = NODE_BROWN * NODE_BROWN * Constants.kT / nd.node.bTransGam.get(0);   // m²/s (brownScale² scales D)
        double Tsec = M * dt;
        double rmsWander = Math.sqrt(4.0 * Dt * Tsec) * 1.0e6;  // µm (relative coordinate ⇒ 2× single-node var)
        boolean approached = approach > rmsWander && approach > 0.0;

        System.out.printf("%n  inter-node distance: start=%.4f µm, early-mean=%.4f, late-mean=%.4f µm (Δapproach=%.4f µm; min=%.4f)%n",
                d0, dEarly, dLate, approach, dMin);
        System.out.printf("  Brownian wander scale over %d steps (%.3f s): rms≈%.4f µm => approach %s noise%n",
                M, Tsec, rmsWander, approached ? "EXCEEDS" : "within");
        System.out.printf("  cross-node captures: first @ step %s, peak=%d, steady-avg(last 10%%)=%.2f; self-captures observed too%n",
                captured ? String.valueOf(firstCapture) : "NEVER", peakCross, avgCross);
        System.out.printf("  final: active filaments=%d, contour=%.4f µm (max per-fil ≈ contour/active)%n", activeFilaments(s.fil), contour(s.fil));
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
            .task("reach", BindingDetectionSystem::bruteReachable, mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, sh.reachSeg, sh.reachCount, mot.kinParams, mot.counts)
            .task("release", NucleotideCycleSystem::catchSlipRelease, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.cooldown, mot.stats, mot.capStats, mot.kinParams, mot.counts)
            .task("bind", BindingDetectionSystem::bindNearest, mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, sh.reachSeg, sh.reachCount, mot.boundSeg, mot.bindArc, mot.kinParams, mot.counts)
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
        for (String t : new String[]{ "zeroNode","brownNode","ndGather","confineNode","integNode","deriveNode" }) addW("scpr." + t, pad(nN));
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
