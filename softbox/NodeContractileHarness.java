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
 * Increment 6 — the NODE in the MINIMAL CONTRACTILE ASSAY (swap for the minifilament; QUALITATIVE).
 *
 * The minimal contractile assay (ContractileAssayHarness) pulls two anti-parallel pinned filament
 * chains together with a central bipolar MINIFILAMENT and reads contractile tension at the pins. This
 * harness SWAPS the free minifilament for a free PROTEIN NODE (the Stage-A motor-bundle: a sphere body
 * owning radially-splayed singlet + dimer myosins) at the overlap centre (0,0,0). The node's radial
 * heads bind the two anti-parallel filaments — the +x hemisphere binds filament A (+x polarity), the
 * −x hemisphere binds filament B (−x polarity) per the v1 rodDotFil≥0 predicate — and pull each
 * toward its minus (inner) end ⇒ contraction ⇒ tension at the filament-end pins.
 *
 * This is "see the node do contractile work" — exercising the node's MOTOR-function, NOT nucleation
 * (nucleation OFF: this harness carries no NodeNucleationSystem). It is a harness COMPOSITION over
 * already-validated pieces: the contractile scene (PinSystem pins + the chamber box + the 12 pN
 * break-force cap + the chain-inclusive pre-snap tension read) with the node (NodeStore tether LAW +
 * single-ended CSR gather, byte-unchanged) standing in for the minifilament. NO new force law, NO new
 * gather, NO shared-kernel change ⇒ prior harnesses are bit-identical by construction.
 *
 * FREE node (default, the faithful swap for the *free* minifilament): the node body is INTEGRATED
 * under all forces + Brownian and CONFINED by the chamber box exactly as the minifilament was. A
 * FIXED-anchor node (the ring's mode; -anchor) is a valid alternative — flagged, not the default.
 *
 * Posture (§6 of the contractile findings + §8 CLAUDE.md): v1's assay used a MINIFILAMENT, so there is
 * NO v1 numeric target for a node here — validation is qualitative (jba's viewer eye is final) +
 * physical plausibility; the minifilament's ~1.84 pN is a SANITY BALLPARK (the node is a different
 * geometry, so a same-regime value is expected, not an exact match).
 */
public final class NodeContractileHarness {

    static boolean cpu = false;
    static GridScheduler sched;
    static final int B = 64;
    static final int SEED = 0xC04711, SEED_NODE = 0x5C2F11;
    static final double GOLDEN = 2.399963229728653;          // golden angle (Fibonacci sphere)
    static final double MYO_SPRING = 1.0e-9, J1_FMT = 0.4;
    static double YOFF = 0.05;                                // filament ±Y offset (v1 contractFilYOffset)
    static double REACH = 0.025;                              // myoColTol (bind/capture radius, µm)
    static double ALIGN_TOL = -0.4;                          // myoMotorAlignWithFilTolerance (v1 default)
    static double KOFF = 100.0;                               // catch-slip base off-rate (v1 default 100/s)
    static double BROWN_TRANS = 1.0;                          // BTransCoeff — translational thermal search
    static double BROWN_ROT = 0.3;                            // BRotCoeff (v1 contractility pf)
    static int N_SING = 12, N_DIM = 12;                       // radial singlets + dimers on the node
    static final double SIN80 = Math.sin(Math.toRadians(80.0)), COS80 = Math.cos(Math.toRadians(80.0));
    static final int FIL_MONO = 64;
    static boolean anchorNode = false;                        // -anchor ⇒ fixed-anchor node (ring mode)

    public static void main(String[] args) {
        double dt = 1.0e-5;
        String vizDir = null;
        int M = 6000;
        boolean diag = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-cpu" -> cpu = true;
                case "-3js" -> vizDir = args[++i];
                case "-steps" -> M = Integer.parseInt(args[++i]);
                case "-reach" -> REACH = Double.parseDouble(args[++i]);
                case "-koff" -> KOFF = Double.parseDouble(args[++i]);
                case "-yoff" -> YOFF = Double.parseDouble(args[++i]);
                case "-nsing" -> N_SING = Integer.parseInt(args[++i]);
                case "-ndim" -> N_DIM = Integer.parseInt(args[++i]);
                case "-anchor" -> anchorNode = true;
                case "-diag" -> diag = true;
                default -> {}
            }
        }
        if (diag) { diagnose(dt, M); return; }
        System.out.println("=== Soft Box increment 6 — NODE in the MINIMAL CONTRACTILE ASSAY (node ⇄ minifilament swap) ===");
        System.out.println("A " + (anchorNode ? "FIXED-ANCHOR" : "FREE (box-confined)") + " protein node at the overlap centre; its radial myosins");
        System.out.println("bind the two anti-parallel pinned filaments and pull them together. Nucleation OFF.\n");
        if (vizDir != null) { runViz(dt, vizDir, M); return; }

        boolean g4 = checkNoMotorControl(dt);          // no-motor control + baseline
        boolean g5 = checkContainmentConfinesNode(dt); // the chamber box confines the free node
        boolean g2 = checkItContracts(dt, M);          // the headline: it contracts (both poles engage) + instrumentation
        boolean g3 = checkCpuGpu(dt);                  // CPU≡GPU
        boolean ok = g2 && g3 && g4 && g5;
        System.out.println("\n=== NODE-CONTRACTILE VALIDATION " + (ok ? "PASS" : "FAIL") + " ===");
        if (!ok) { System.out.println("BAIL-OUT: a gate failed. Commit nothing."); System.exit(1); }
    }

    // ============================================================== scene
    static final class Scene {
        FilamentStore fil; MotorStore mot; DimerStore dim; NodeStore node;
        FloatArray bondData, xbParams;
        IntArray segMotorCount, segMotorOffsets, segMotorMyo;
        IntArray reachSeg, reachCount;
        IntArray pinSeg, pinCounts; FloatArray pinPt;
        int pinSegA, pinSegB;
        double bdAx = -1, bdBx = 1;                    // inward buildDir.x for A (−x) / B (+x)
        int filA0, filB0, segPerFil;
        boolean tetherOn = true, motorOn = true;
        boolean dynamicBind = true;
        boolean nodeFixed = false;                     // mirror anchorNode (set in buildScene)
        double tA = 0, tB = 0;
        boolean boxOn = true;
        FloatArray boxParams;
    }

    /** Build one filament chain into `fil` segments [base, base+K), plus-end (end2) pinned at pinTipX.
     *  plusSign p=+1 ⇒ plus end at +x; p=−1 ⇒ plus end at −x. Segments march inward (toward −p·x). */
    static void buildChain(FilamentStore fil, int base, int K, double pinTipX, double p, double yOff, double L) {
        for (int i = 0; i < K; i++) {
            int s = base + i;
            double cx = pinTipX - p * (0.5 * L + i * L);
            fil.monomerCount.set(s, FIL_MONO);
            fil.setCoord(s, (float) cx, (float) yOff, 0f);
            fil.setUVec(s, (float) p, 0f, 0f);
            fil.setYVec(s, 0f, 0f, 1f);
            fil.brownTransScale.set(s, 0f); fil.brownRotScale.set(s, 0f);
            if (i < K - 1) { fil.end1NbrSlot.set(s, s + 1); fil.end1NbrSide.set(s, 1); }
            if (i > 0)     { fil.end2NbrSlot.set(s, s - 1); fil.end2NbrSide.set(s, 0); }
        }
    }

    /** One node at the origin (FREE by default, box-confined) owning nSing radial singlets + nDim radial
     *  dimers (Fibonacci-sphere splay), + two anti-parallel filament chains straddling it in ±Y, pinned
     *  at opposite outer (+x / −x) plus-ends. The node's +x-hemisphere heads bind filament A, the
     *  −x-hemisphere heads bind filament B (v1 rodDotFil polarity sort) ⇒ both poles pull inward. */
    static Scene buildScene(double dt, int K, boolean establishBonds) {
        Scene sc = new Scene();
        sc.nodeFixed = anchorNode;
        int nSing = N_SING, nDim = N_DIM, nChild = nSing + nDim;
        int motorsPerNode = nSing + 2 * nDim;
        int nMot = motorsPerNode, nDimers = nDim, nAtt = nChild;
        int nSeg = 2 * K;
        double L = (FIL_MONO + 1) * Constants.actinMonoRadius;
        double R = NodeStore.NODE_RADIUS;

        MotorStore mot = new MotorStore(nMot);
        DimerStore dim = new DimerStore(nDimers);
        NodeStore node = new NodeStore(1, nAtt);
        FilamentStore fil = new FilamentStore(nSeg);

        // node body at origin, identity frame, FREE + Brownian (the thermal search enabler) unless anchored
        node.node.setCoord(0, 0f, 0f, 0f);
        node.node.setUVec(0, 1f, 0f, 0f);
        node.node.setYVec(0, 0f, 1f, 0f);
        node.node.brownTransScale.set(0, anchorNode ? 0f : (float) BROWN_TRANS);
        node.node.brownRotScale.set(0, anchorNode ? 0f : (float) BROWN_ROT);

        // radial children over the sphere surface (Fibonacci), each tethered to the node center radially
        for (int c = 0; c < nChild; c++) {
            double yy = 1.0 - 2.0 * (c + 0.5) / nChild;
            double rr = Math.sqrt(Math.max(0.0, 1.0 - yy * yy));
            double phi = c * GOLDEN;
            double ux = rr * Math.cos(phi), uy = yy, uz = rr * Math.sin(phi);
            double sx = R * ux, sy = R * uy, sz = R * uz;     // surface point (node at origin, identity frame)
            if (c < nSing) {
                int m = c;
                mot.assembleArticulated(m, (float) sx, (float) sy, (float) sz, (float) ux, (float) uy, (float) uz, (float) BROWN_TRANS);
                // assembleArticulated set rod+head rot scale = BROWN_TRANS; use the v1 BRotCoeff instead
                int rod = mot.rodIdx(m), head = mot.headIdx(m);
                mot.body.brownRotScale.set(rod, (float) BROWN_ROT); mot.body.brownRotScale.set(head, (float) BROWN_ROT);
                double coeff = NodeStore.ATTN_FORCE / nSing;  // v1 keepMyosinsOnSurface attnForce/numNodeMyos
                node.attach(c, 0, m, R * ux, R * uy, R * uz, coeff, false);   // singlet: center, no torque
            } else {
                int j = c - nSing;
                int mA = nSing + 2 * j, mB = mA + 1;
                placeDimerAlong(mot, mA, mB, sx, sy, sz, ux, uy, uz);
                dim.pair(j, mA, mB, true);
                double coeff = NodeStore.ATTN_FORCE * NodeStore.DIMER_FRACMOVE;   // v1 keepMyosinDimersOnSurface
                node.attach(c, 0, mA, R * ux, R * uy, R * uz, coeff, true);   // dimer: end1, torque
            }
        }

        // two anti-parallel filament chains, offset in ±Y, pinned at opposite outer plus-ends. The inner
        // (minus-end) segments sit in the node's radial head shell so the +x / −x hemisphere heads engage.
        double fieldXc = R + MotorStore.ROD_LEN + (MotorStore.LEVER_LEN + MotorStore.HEAD_LEN) * COS80;  // ~0.135 µm
        int nOut = Math.max(2, K / 2);
        double pinTipA = fieldXc + nOut * L + 0.5 * L;
        double pinTipB = -(fieldXc + nOut * L + 0.5 * L);
        sc.filA0 = 0; sc.filB0 = K; sc.segPerFil = K;
        buildChain(fil, 0, K, pinTipA, +1.0, +YOFF, L);
        buildChain(fil, K, K, pinTipB, -1.0, -YOFF, L);

        DragTensorSystem.run(fil);
        fil.setParams(dt, 0); fil.setCounts(0, 0xF11A);
        fil.chainParams.set(0, (float) dt); fil.chainParams.set(1, 0.5f); fil.chainParams.set(2, 0.1f);
        fil.chainParams.set(3, 0.2f); fil.chainParams.set(4, 0f); fil.chainParams.set(5, 1.0e-20f);
        fil.chainParams.set(6, (float) Constants.actinMonoRadius);
        DerivedGeometrySystem.derive(fil.coord, fil.uVec, fil.yVec, fil.zVec, fil.end1, fil.end2, fil.segLength, fil.counts);

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

        int MAXC = SpatialGrid.MAX_CAND;
        sc.bondData = new FloatArray(nMot * CrossBridgeSystem.STRIDE); sc.bondData.init(0f);
        sc.xbParams = FloatArray.fromElements((float) MYO_SPRING, 90f, (float) J1_FMT, (float) dt, (float) MotorStore.HEAD_LEN, 0f);
        sc.segMotorCount = new IntArray(nSeg); sc.segMotorOffsets = new IntArray(nSeg + 1); sc.segMotorMyo = new IntArray(nMot);
        sc.reachSeg = new IntArray(nMot * MAXC); sc.reachSeg.init(-1); sc.reachCount = new IntArray(nMot);

        sc.pinSegA = 0; sc.pinSegB = K;
        sc.pinSeg = IntArray.fromElements(sc.pinSegA, sc.pinSegB);
        sc.pinPt = FloatArray.fromElements((float) pinTipA, (float) YOFF, 0f, (float) pinTipB, (float) -YOFF, 0f);
        sc.pinCounts = IntArray.fromElements(2);
        sc.bdAx = -1.0; sc.bdBx = +1.0;

        // the in-vitro chamber box (v1 contractility scene 4.0 × 0.3 × 0.2 µm; nodeFracMove 0.5;
        // collisionDeltaT 1e-4 / dt 1e-5 ⇒ checkInt 10; inset R = node radius 0.05 µm).
        sc.boxParams = FloatArray.fromElements(1.0e-4f, 4.0f, 0.3f, 0.2f,
                (float) NodeStore.NODE_RADIUS, 0.5f, 10f);

        sc.fil = fil; sc.mot = mot; sc.dim = dim; sc.node = node;
        if (establishBonds) for (int t = 0; t < 4; t++) bindOnly(sc, t);
        return sc;
    }

    static void bindOnly(Scene sc, int t) {
        MotorStore mot = sc.mot; FilamentStore f = sc.fil; RigidRodBody b = mot.body;
        mot.setCounts(t, SEED, f.n);
        MotorStore.publishHeadFromBody(b.coord, b.uVec, b.segLength, mot.head, mot.uVec, mot.rodUVec, mot.counts);
        BindingDetectionSystem.bruteReachable(mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, sc.reachSeg, sc.reachCount, mot.kinParams, mot.counts);
        BindingDetectionSystem.bindNearest(mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, sc.reachSeg, sc.reachCount, mot.boundSeg, mot.bindArc, mot.kinParams, mot.counts);
    }

    // ============================================================== per-step (CPU runner)
    static void cpuStep(Scene sc, int t, boolean withCycle) {
        FilamentStore f = sc.fil; MotorStore mot = sc.mot; DimerStore dim = sc.dim; NodeStore nd = sc.node;
        RigidRodBody b = mot.body; RigidRodBody nb = nd.node;
        mot.setCounts(t, SEED, f.n); nd.setNodeBodyCounts(t, SEED_NODE); f.counts.set(1, t);

        if (sc.motorOn) {
            if (sc.dynamicBind) {
                MotorStore.publishHeadFromBody(b.coord, b.uVec, b.segLength, mot.head, mot.uVec, mot.rodUVec, mot.counts);
                BindingDetectionSystem.bruteReachable(mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, sc.reachSeg, sc.reachCount, mot.kinParams, mot.counts);
                NucleotideCycleSystem.catchSlipRelease(mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.cooldown, mot.stats, mot.capStats, mot.kinParams, mot.counts);
                BindingDetectionSystem.bindNearest(mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, sc.reachSeg, sc.reachCount, mot.boundSeg, mot.bindArc, mot.kinParams, mot.counts);
            }
            if (withCycle) NucleotideCycleSystem.cycle(mot.nucleotideState, mot.boundSeg, mot.forceDotHist, mot.nucParams, mot.counts);
            ChainBendingForceSystem.zeroAccumulators(b.forceSum, b.torqueSum, mot.counts);
            ChainBendingForceSystem.zeroAccumulators(nb.forceSum, nb.torqueSum, nd.nodeBodyCounts);
            BrownianForceSystem.brownianForce(b.randForce, b.randTorque, b.bTransGam, b.bRotGam, b.brownTransScale, b.brownRotScale, mot.bodyParams, mot.counts);
            if (!sc.nodeFixed) BrownianForceSystem.brownianForce(nb.randForce, nb.randTorque, nb.bTransGam, nb.bRotGam, nb.brownTransScale, nb.brownRotScale, nd.nodeBodyParams, nd.nodeBodyCounts);
            MotorJointSystem.joints(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, mot.nucleotideState, mot.jointParams, mot.counts);
            DimerCouplingSystem.couple(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, dim.motorA, dim.motorB, dim.parallel, dim.dimerParams, mot.boundSeg);
            if (sc.tetherOn) {
                NodeSystem.tether(b.coord, b.uVec, b.segLength, b.bTransGam, b.forceSum, b.torqueSum,
                        nb.coord, nb.uVec, nb.yVec, nd.nodeInvTransY,
                        nd.attachKey, nd.radial, nd.attachCoeffK, nd.nodeData, nd.nodeParams);
                CrossBridgeSystem.csrHistogram(nd.attachNode, nd.nodeCounts4, nd.nodeAttachCount);
                CrossBridgeSystem.csrScan(nd.nodeCounts4, nd.nodeAttachCount, nd.nodeAttachOffsets);
                CrossBridgeSystem.csrScatter(nd.attachNode, nd.nodeCounts4, nd.nodeAttachOffsets, nd.nodeAttachCount, nd.nodeAttachList);
                MiniFilamentSystem.backboneGather(nd.nodeAttachOffsets, nd.nodeAttachList, nd.nodeData, nb.forceSum, nb.torqueSum, nd.nodeCounts4);
            }
            CrossBridgeSystem.bondForces(b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength,
                    mot.boundSeg, mot.bindArc, mot.nucleotideState, sc.bondData, sc.xbParams);
            CrossBridgeSystem.applyHeadForce(sc.bondData, b.forceSum, b.torqueSum, mot.counts);
            RigidRodLangevinIntegrationSystem.integrate(b.coord, b.uVec, b.yVec, b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, mot.bodyParams, mot.counts);
            // node body: free ⇒ confine (box) + integrate + derive; anchored ⇒ stationary (never integrated)
            if (!sc.nodeFixed) {
                if (sc.boxOn) ContainmentSystem.confine(nb.coord, nb.uVec, nb.segLength, nb.bTransGam, nb.forceSum, nb.torqueSum, sc.boxParams, nd.nodeBodyCounts);
                RigidRodLangevinIntegrationSystem.integrate(nb.coord, nb.uVec, nb.yVec, nb.forceSum, nb.torqueSum, nb.randForce, nb.randTorque, nb.bTransGam, nb.bRotGam, nd.nodeBodyParams, nd.nodeBodyCounts);
                DerivedGeometrySystem.derive(nb.coord, nb.uVec, nb.yVec, nb.zVec, nb.end1, nb.end2, nb.segLength, nd.nodeBodyCounts);
            }
            DerivedGeometrySystem.derive(b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts);
            CrossBridgeSystem.registerForceDot(sc.bondData, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.counts);
        }

        // filament: chain + the gathered cross-bridge, capture tension (pre-integrate), integrate, pin
        ChainBendingForceSystem.zeroAccumulators(f.forceSum, f.torqueSum, f.counts);
        ChainBendingForceSystem.chainForces(f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide, f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts);
        if (sc.motorOn) {
            CrossBridgeSystem.csrHistogram(mot.boundSeg, mot.counts, sc.segMotorCount);
            CrossBridgeSystem.csrScan(mot.counts, sc.segMotorCount, sc.segMotorOffsets);
            CrossBridgeSystem.csrScatter(mot.boundSeg, mot.counts, sc.segMotorOffsets, sc.segMotorCount, sc.segMotorMyo);
            CrossBridgeSystem.segGather(sc.segMotorOffsets, sc.segMotorMyo, sc.bondData, f.forceSum, f.torqueSum, mot.counts);
        }
        captureTension(sc);
        RigidRodLangevinIntegrationSystem.integrate(f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts);
        DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
        PinSystem.snap(f.coord, f.end1, f.end2, sc.pinSeg, sc.pinPt, sc.pinCounts);
    }

    static void captureTension(Scene sc) {
        FilamentStore f = sc.fil;
        sc.tA = (double) f.forceSum.get(sc.pinSegA) * sc.bdAx * 1e12;
        sc.tB = (double) f.forceSum.get(sc.pinSegB) * sc.bdBx * 1e12;
    }

    // ============================================================== GPU TaskGraph
    static TornadoExecutionPlan buildPlan(Scene sc, boolean withCycle) {
        FilamentStore f = sc.fil; MotorStore mot = sc.mot; DimerStore dim = sc.dim; NodeStore nd = sc.node;
        RigidRodBody b = mot.body; RigidRodBody nb = nd.node;
        TaskGraph tg = new TaskGraph("nodeContract")
            .transferToDevice(DataTransferMode.FIRST_EXECUTION,
                    b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, b.bTransGam, b.bRotGam,
                    b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.brownTransScale, b.brownRotScale,
                    mot.head, mot.uVec, mot.rodUVec, mot.boundSeg, mot.bindArc, mot.nucleotideState,
                    mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.stats, mot.capStats, mot.cooldown,
                    mot.bodyParams, mot.jointParams, mot.nucParams, mot.kinParams,
                    dim.motorA, dim.motorB, dim.parallel, dim.dimerParams,
                    nb.coord, nb.uVec, nb.yVec, nb.zVec, nb.end1, nb.end2, nb.segLength, nb.bTransGam, nb.bRotGam,
                    nb.forceSum, nb.torqueSum, nb.randForce, nb.randTorque, nb.brownTransScale, nb.brownRotScale, nd.nodeBodyParams,
                    nd.nodeInvTransY, nd.attachNode, nd.attachKey, nd.radial, nd.attachCoeffK, nd.nodeData, nd.nodeParams,
                    nd.nodeAttachCount, nd.nodeAttachOffsets, nd.nodeAttachList, nd.nodeCounts4,
                    sc.bondData, sc.xbParams, sc.segMotorCount, sc.segMotorOffsets, sc.segMotorMyo, sc.reachSeg, sc.reachCount,
                    f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.bTransGam, f.bRotGam,
                    f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.params, f.chainParams,
                    f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide,
                    sc.pinSeg, sc.pinPt, sc.pinCounts, sc.boxParams)
            .transferToDevice(DataTransferMode.EVERY_EXECUTION, mot.counts, nd.nodeBodyCounts, f.counts);
        if (sc.dynamicBind) {
            tg.task("publishHead", MotorStore::publishHeadFromBody, b.coord, b.uVec, b.segLength, mot.head, mot.uVec, mot.rodUVec, mot.counts)
              .task("reach", BindingDetectionSystem::bruteReachable, mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, sc.reachSeg, sc.reachCount, mot.kinParams, mot.counts)
              .task("release", NucleotideCycleSystem::catchSlipRelease, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.cooldown, mot.stats, mot.capStats, mot.kinParams, mot.counts)
              .task("bind", BindingDetectionSystem::bindNearest, mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, sc.reachSeg, sc.reachCount, mot.boundSeg, mot.bindArc, mot.kinParams, mot.counts);
        }
        if (withCycle) tg.task("cycle", NucleotideCycleSystem::cycle, mot.nucleotideState, mot.boundSeg, mot.forceDotHist, mot.nucParams, mot.counts);
        tg.task("zeroMot", ChainBendingForceSystem::zeroAccumulators, b.forceSum, b.torqueSum, mot.counts)
          .task("zeroNode", ChainBendingForceSystem::zeroAccumulators, nb.forceSum, nb.torqueSum, nd.nodeBodyCounts)
          .task("brownMot", BrownianForceSystem::brownianForce, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, b.brownTransScale, b.brownRotScale, mot.bodyParams, mot.counts);
        if (!sc.nodeFixed)
          tg.task("brownNode", BrownianForceSystem::brownianForce, nb.randForce, nb.randTorque, nb.bTransGam, nb.bRotGam, nb.brownTransScale, nb.brownRotScale, nd.nodeBodyParams, nd.nodeBodyCounts);
        tg.task("joints", MotorJointSystem::joints, b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, mot.nucleotideState, mot.jointParams, mot.counts)
          .task("dimer", DimerCouplingSystem::couple, b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, dim.motorA, dim.motorB, dim.parallel, dim.dimerParams, mot.boundSeg)
          .task("tether", NodeSystem::tether, b.coord, b.uVec, b.segLength, b.bTransGam, b.forceSum, b.torqueSum,
                    nb.coord, nb.uVec, nb.yVec, nd.nodeInvTransY, nd.attachKey, nd.radial, nd.attachCoeffK, nd.nodeData, nd.nodeParams)
          .task("ndHist", CrossBridgeSystem::csrHistogram, nd.attachNode, nd.nodeCounts4, nd.nodeAttachCount)
          .task("ndScan", CrossBridgeSystem::csrScan, nd.nodeCounts4, nd.nodeAttachCount, nd.nodeAttachOffsets)
          .task("ndScatter", CrossBridgeSystem::csrScatter, nd.attachNode, nd.nodeCounts4, nd.nodeAttachOffsets, nd.nodeAttachCount, nd.nodeAttachList)
          .task("ndGather", MiniFilamentSystem::backboneGather, nd.nodeAttachOffsets, nd.nodeAttachList, nd.nodeData, nb.forceSum, nb.torqueSum, nd.nodeCounts4)
          .task("bond", CrossBridgeSystem::bondForces, b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength, mot.boundSeg, mot.bindArc, mot.nucleotideState, sc.bondData, sc.xbParams)
          .task("applyHead", CrossBridgeSystem::applyHeadForce, sc.bondData, b.forceSum, b.torqueSum, mot.counts)
          .task("integM", RigidRodLangevinIntegrationSystem::integrate, b.coord, b.uVec, b.yVec, b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, mot.bodyParams, mot.counts);
        if (!sc.nodeFixed) {
            if (sc.boxOn) tg.task("confineNode", ContainmentSystem::confine, nb.coord, nb.uVec, nb.segLength, nb.bTransGam, nb.forceSum, nb.torqueSum, sc.boxParams, nd.nodeBodyCounts);
            tg.task("integNode", RigidRodLangevinIntegrationSystem::integrate, nb.coord, nb.uVec, nb.yVec, nb.forceSum, nb.torqueSum, nb.randForce, nb.randTorque, nb.bTransGam, nb.bRotGam, nd.nodeBodyParams, nd.nodeBodyCounts)
              .task("deriveNode", DerivedGeometrySystem::derive, nb.coord, nb.uVec, nb.yVec, nb.zVec, nb.end1, nb.end2, nb.segLength, nd.nodeBodyCounts);
        }
        tg.task("deriveM", DerivedGeometrySystem::derive, b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts)
          .task("register", CrossBridgeSystem::registerForceDot, sc.bondData, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.counts)
          .task("zeroFil", ChainBendingForceSystem::zeroAccumulators, f.forceSum, f.torqueSum, f.counts)
          .task("chain", ChainBendingForceSystem::chainForces, f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide, f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts)
          .task("filHist", CrossBridgeSystem::csrHistogram, mot.boundSeg, mot.counts, sc.segMotorCount)
          .task("filScan", CrossBridgeSystem::csrScan, mot.counts, sc.segMotorCount, sc.segMotorOffsets)
          .task("filScatter", CrossBridgeSystem::csrScatter, mot.boundSeg, mot.counts, sc.segMotorOffsets, sc.segMotorCount, sc.segMotorMyo)
          .task("filGather", CrossBridgeSystem::segGather, sc.segMotorOffsets, sc.segMotorMyo, sc.bondData, f.forceSum, f.torqueSum, mot.counts)
          .task("integFil", RigidRodLangevinIntegrationSystem::integrate, f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts)
          .task("deriveFil", DerivedGeometrySystem::derive, f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts)
          .task("pin", PinSystem::snap, f.coord, f.end1, f.end2, sc.pinSeg, sc.pinPt, sc.pinCounts)
          .transferToHost(DataTransferMode.UNDER_DEMAND, f.coord, f.uVec, f.end1, f.end2, f.forceSum,
                    b.coord, b.uVec, nb.coord, nb.uVec, mot.boundSeg, mot.nucleotideState);

        int nMB = b.n, nN = nb.n, nM = mot.nMotors, nSeg = f.n, nD = dim.nDimers, nA = nd.nAttach;
        sched = new GridScheduler();
        for (String t : new String[]{ "publishHead","reach","release","bind","bond","applyHead","register" }) addW("nodeContract." + t, pad(nM));
        if (withCycle) addW("nodeContract.cycle", pad(nM));
        for (String t : new String[]{ "zeroMot","brownMot","joints","integM","deriveM" }) addW("nodeContract." + t, pad(nMB));
        for (String t : new String[]{ "zeroNode","ndGather" }) addW("nodeContract." + t, pad(nN));
        if (!sc.nodeFixed) { for (String t : new String[]{ "brownNode","integNode","deriveNode" }) addW("nodeContract." + t, pad(nN)); if (sc.boxOn) addW("nodeContract.confineNode", pad(nN)); }
        for (String t : new String[]{ "dimer" }) addW("nodeContract." + t, pad(nD));
        addW("nodeContract.tether", pad(nA));
        for (String t : new String[]{ "zeroFil","chain","filGather","integFil","deriveFil" }) addW("nodeContract." + t, pad(nSeg));
        for (String t : new String[]{ "ndHist","ndScan","ndScatter","filHist","filScan","filScatter","pin" }) addS("nodeContract." + t);
        return new TornadoExecutionPlan(tg.snapshot());
    }

    /** Filament-only device plan (no-motor): zero → chain → integrate → derive → PIN (the PinSystem bit-id check). */
    static TornadoExecutionPlan buildFilPlan(Scene sc) {
        FilamentStore f = sc.fil;
        TaskGraph tg = new TaskGraph("ncFil")
            .transferToDevice(DataTransferMode.FIRST_EXECUTION,
                    f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.bTransGam, f.bRotGam,
                    f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.params, f.chainParams,
                    f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide, sc.pinSeg, sc.pinPt, sc.pinCounts)
            .transferToDevice(DataTransferMode.EVERY_EXECUTION, f.counts)
            .task("zeroFil", ChainBendingForceSystem::zeroAccumulators, f.forceSum, f.torqueSum, f.counts)
            .task("chain", ChainBendingForceSystem::chainForces, f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide, f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts)
            .task("integFil", RigidRodLangevinIntegrationSystem::integrate, f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts)
            .task("deriveFil", DerivedGeometrySystem::derive, f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts)
            .task("pin", PinSystem::snap, f.coord, f.end1, f.end2, sc.pinSeg, sc.pinPt, sc.pinCounts)
            .transferToHost(DataTransferMode.UNDER_DEMAND, f.coord, f.forceSum, f.end1, f.end2);
        sched = new GridScheduler();
        for (String t : new String[]{ "zeroFil","chain","integFil","deriveFil" }) addW("ncFil." + t, pad(f.n));
        addS("ncFil.pin");
        return new TornadoExecutionPlan(tg.snapshot());
    }
    static int pad(int n) { return ((n + B - 1) / B) * B; }
    static void addW(String n, int g) { WorkerGrid w = new WorkerGrid1D(g); w.setLocalWork(B, 1, 1); sched.addWorkerGrid(n, w); }
    static void addS(String n) { WorkerGrid w = new WorkerGrid1D(1); w.setLocalWork(1, 1, 1); sched.addWorkerGrid(n, w); }

    // ============================================================== running statistics (v1 port)
    static final class Stats {
        long n = 0; double sumTension = 0, sumBound = 0; double ewmaBound = 0, ewmaTension = 0;
        boolean ewmaInit = false; double peakTension = 0; int peakBound = 0; int firstBindStep = -1;
        static final double ALPHA = 0.005;
        void accumulate(double tA, double tB, int bound, int step) {
            double meanTension = 0.5 * (Math.abs(tA) + Math.abs(tB));
            n++; sumTension += meanTension; sumBound += bound;
            if (!ewmaInit) { ewmaBound = bound; ewmaTension = meanTension; ewmaInit = true; }
            else { ewmaBound += ALPHA * (bound - ewmaBound); ewmaTension += ALPHA * (meanTension - ewmaTension); }
            if (meanTension > peakTension) peakTension = meanTension;
            if (bound > peakBound) peakBound = bound;
            if (firstBindStep < 0 && bound > 0) firstBindStep = step;
        }
        double avgTension() { return n > 0 ? sumTension / n : 0; }
        double avgBound()   { return n > 0 ? sumBound / n : 0; }
    }

    static int boundHeads(MotorStore m) { int c = 0; for (int i = 0; i < m.nMotors; i++) if (m.boundSeg.get(i) >= 0) c++; return c; }
    static int boundOn(Scene sc, int base, int K) {
        int c = 0; for (int i = 0; i < sc.mot.nMotors; i++) { int s = sc.mot.boundSeg.get(i); if (s >= base && s < base + K) c++; } return c;
    }
    static double maxDiff(FloatArray a, FloatArray b) { double m = 0; for (int i = 0; i < a.getSize(); i++) m = Math.max(m, Math.abs(a.get(i) - b.get(i))); return m; }

    // ============================================================== #4 no-motor control + baseline
    static double baselineTension = 0;
    static boolean checkNoMotorControl(double dt) {
        System.out.println("--- #4: no-motor control (filaments hold at the pins, tension relaxes to ≈ 0) ---");
        Scene sc = buildScene(dt, 8, false);
        sc.motorOn = false;
        int M = 2000; double sum2 = 0; long n2 = 0; double peak = 0;
        for (int t = 0; t < M; t++) {
            cpuStep(sc, t, false);
            double meanT = 0.5 * (Math.abs(sc.tA) + Math.abs(sc.tB));
            if (meanT > peak) peak = meanT;
            if (t >= M / 2) { sum2 += meanT; n2++; }
        }
        baselineTension = sum2 / n2;
        double tipAerr = Math.abs(sc.fil.end2.get(sc.pinSegA) - sc.pinPt.get(0));
        double tipBerr = Math.abs(sc.fil.end2.get(sc.pinSegB) - sc.pinPt.get(3));
        boolean held = tipAerr < 1e-6 && tipBerr < 1e-6;
        boolean relaxed = baselineTension < 0.05;
        System.out.printf("  pinned tips held exactly: |Δtip A|=%.2e |Δtip B|=%.2e µm => %s%n", tipAerr, tipBerr, held ? "ok" : "*FAIL*");
        System.out.printf("  no-motor tension: initial peak=%.4f pN, STEADY baseline=%.5f pN (→0) => %s%n", peak, baselineTension, relaxed ? "ok" : "*FAIL*");
        boolean ok = held && relaxed;
        System.out.println("  => " + (ok ? "PASS" : "*FAIL*") + "\n");
        return ok;
    }

    // ============================================================== #5 the chamber confines the free node
    static boolean checkContainmentConfinesNode(double dt) {
        System.out.println("--- #5: the chamber box confines the free NODE body (entity-agnostic, positions not classes) ---");
        Scene sc = buildScene(dt, 8, false);
        NodeStore nd = sc.node; RigidRodBody nb = nd.node;
        FloatArray boxParams = FloatArray.fromElements(1.0e-4f, 0.3f, 0.3f, 0.2f, (float) NodeStore.NODE_RADIUS, 0.5f, 1f);
        // (a) node fully inside ⇒ accumulators untouched (no-op safety)
        nd.setNodeBodyCounts(0, SEED_NODE);
        ChainBendingForceSystem.zeroAccumulators(nb.forceSum, nb.torqueSum, nd.nodeBodyCounts);
        ContainmentSystem.confine(nb.coord, nb.uVec, nb.segLength, nb.bTransGam, nb.forceSum, nb.torqueSum, boxParams, nd.nodeBodyCounts);
        double insideMax = 0; for (int k = 0; k < nb.forceSum.getSize(); k++) insideMax = Math.max(insideMax, Math.abs(nb.forceSum.get(k)) + Math.abs(nb.torqueSum.get(k)));
        boolean insideNoOp = insideMax == 0.0;
        // (b) node pushed past +y wall ⇒ inward (−y) force + moves inward after integrate
        nb.setCoord(0, 0f, 0.13f, 0f);
        double y0 = nb.coord.get(nb.n);
        nb.randForce.init(0f); nb.randTorque.init(0f);
        ChainBendingForceSystem.zeroAccumulators(nb.forceSum, nb.torqueSum, nd.nodeBodyCounts);
        ContainmentSystem.confine(nb.coord, nb.uVec, nb.segLength, nb.bTransGam, nb.forceSum, nb.torqueSum, boxParams, nd.nodeBodyCounts);
        double fy = nb.forceSum.get(nb.n);
        RigidRodLangevinIntegrationSystem.integrate(nb.coord, nb.uVec, nb.yVec, nb.forceSum, nb.torqueSum, nb.randForce, nb.randTorque, nb.bTransGam, nb.bRotGam, nd.nodeBodyParams, nd.nodeBodyCounts);
        double y1 = nb.coord.get(nb.n);
        boolean pushesIn = fy < 0.0 && y1 < y0;
        System.out.printf("  (a) inside ⇒ |force|+|torque| max = %.3e (exactly 0 ⇒ untouched): %s%n", insideMax, insideNoOp ? "ok" : "*FAIL*");
        System.out.printf("  (b) node past +y wall: y=%.4f → %.4f µm (Fy=%.2e N, inward) => %s%n", y0, y1, fy, pushesIn ? "ok" : "*FAIL*");
        boolean ok = insideNoOp && pushesIn;
        System.out.println("  => " + (ok ? "PASS" : "*FAIL*") + "\n");
        return ok;
    }

    // ============================================================== #2 it contracts (the headline) + instrumentation
    static boolean checkItContracts(double dt, int M) {
        System.out.println("--- #2 (HEADLINE): the node contracts — both poles engage, both filaments pulled INWARD ---");
        int Mc = Math.max(M, 50000);
        Scene sc = buildScene(dt, 8, true);
        sc.mot.nucleotideState.init(MotorStore.NUC_NONE);
        MotorStore mot = sc.mot; Stats st = new Stats();
        int K = sc.segPerFil;
        long boundAsum = 0, boundBsum = 0; long sn = 0;
        double tAsum = 0, tBsum = 0; double sgxA = 0, sgxB = 0; long warm = Mc / 2;
        System.out.printf("  %-8s %-10s %-10s %-8s %-8s%n", "step", "tensionA", "tensionB", "boundA", "boundB");
        for (int t = 0; t < Mc; t++) {
            cpuStep(sc, t, true);
            int bA = boundOn(sc, sc.filA0, K), bB = boundOn(sc, sc.filB0, K);
            st.accumulate(sc.tA, sc.tB, bA + bB, t);
            if (t >= warm) {
                boundAsum += bA; boundBsum += bB; sn++; tAsum += sc.tA; tBsum += sc.tB;
                for (int m = 0; m < mot.nMotors; m++) {
                    int s = mot.boundSeg.get(m); int dseg = m * CrossBridgeSystem.STRIDE;
                    if (s >= sc.filA0 && s < sc.filA0 + K) sgxA += sc.bondData.get(dseg + 6);
                    else if (s >= sc.filB0 && s < sc.filB0 + K) sgxB += sc.bondData.get(dseg + 6);
                }
            }
            if (t % Math.max(1, Mc / 10) == 0 || t == Mc - 1)
                System.out.printf("  %-8d %-10.4f %-10.4f %-8d %-8d%n", t, sc.tA, sc.tB, bA, bB);
        }
        double mA = tAsum / sn, mB = tBsum / sn;
        double meanSteady = 0.5 * (mA + mB);
        double bAavg = boundAsum / (double) sn, bBavg = boundBsum / (double) sn;
        double fxA = sgxA / sn, fxB = sgxB / sn;
        boolean bothEngage = bAavg > 0.5 && bBavg > 0.5;
        boolean contractile = mA > 0 && mB > 0;
        boolean aboveBaseline = meanSteady > 10.0 * baselineTension + 1e-4;
        System.out.printf("%n  steady anchor tension: A = %.4f pN, B = %.4f pN (both positive ⇒ contractile) => %s%n", mA, mB, contractile ? "ok" : "*not both +*");
        System.out.printf("  steady bound heads: on A = %.2f, on B = %.2f  (both poles engage: %s)%n", bAavg, bBavg, bothEngage ? "YES" : "NO");
        System.out.printf("  mean steady tension = %.4f pN  vs  no-motor baseline %.5f pN (%.0f× above) => %s%n",
                meanSteady, baselineTension, meanSteady / Math.max(1e-9, baselineTension), aboveBaseline ? "ok" : "*FAIL*");
        System.out.printf("  peak = %.4f pN, avgBound = %.2f, peakBound = %d, first bind @ step %d (sanity ballpark vs minifilament ~1.84 pN)%n",
                st.peakTension, st.avgBound(), st.peakBound, st.firstBindStep);
        System.out.printf("  [info] instantaneous seg-side Fx (near-cancels, not the readout): A=%.2e B=%.2e N%n", fxA, fxB);
        boolean nan = Double.isNaN(mA) || Double.isNaN(mB);
        boolean ok = bothEngage && contractile && aboveBaseline && !nan;
        if (!bothEngage) System.out.println("  [SURFACE] both poles did NOT engage — reporting (not force-fitting). See the geometry note / try -diag.");
        System.out.println("  => " + (ok ? "PASS" : "*FAIL*") + "\n");
        return ok;
    }

    // ============================================================== #3 CPU≡GPU
    static boolean checkCpuGpu(double dt) {
        System.out.println("--- #3: CPU≡GPU ---");
        if (cpu) { System.out.println("  skipped (-cpu)\n"); return true; }
        int M = 600;
        Scene g = buildScene(dt, 8, false); g.motorOn = false;
        Scene c = buildScene(dt, 8, false); c.motorOn = false;
        g.fil.coord.set(g.fil.n + 4, g.fil.coord.get(g.fil.n + 4) + 0.004f);
        c.fil.coord.set(c.fil.n + 4, c.fil.coord.get(c.fil.n + 4) + 0.004f);
        TornadoExecutionPlan plan = buildFilPlan(g);
        for (int t = 0; t < M; t++) {
            g.fil.counts.set(1, t);
            TornadoExecutionResult res = plan.withGridScheduler(sched).execute();
            if (t == M - 1) res.transferToHost(g.fil.coord, g.fil.forceSum, g.fil.end1, g.fil.end2);
        }
        for (int t = 0; t < M; t++) cpuStep(c, t, false);
        double dFil = maxDiff(g.fil.coord, c.fil.coord);
        double dForce = maxDiff(g.fil.forceSum, c.fil.forceSum);
        double dEnd2 = maxDiff(g.fil.end2, c.fil.end2);
        boolean detOk = dFil < 5e-5 && dEnd2 < 5e-5;
        System.out.printf("  (a) deterministic chain+PIN (no-motor, %d steps): max|Δ| coord=%.3e end2(pin)=%.3e µm; forceSum Δ=%.3e N => %s%n",
                M, dFil, dEnd2, dForce, detOk ? "bit-identical (float32)" : "*FAIL*");

        // (b) the CHAOTIC dynamic-binding path: float32 op-ordering decorrelates the microstate (Lyapunov)
        //     ⇒ bit-identity is unattainable (CLAUDE.md standard) — agreement is AGGREGATE. Sample the bound
        //     count over a window on BOTH runners and compare the windowed means (within float-noise / SEM).
        int Md = 3000, sampEvery = 300;
        Scene gd = buildScene(dt, 8, true); gd.mot.nucleotideState.init(MotorStore.NUC_NONE);
        Scene cd = buildScene(dt, 8, true); cd.mot.nucleotideState.init(MotorStore.NUC_NONE);
        TornadoExecutionPlan pd = buildPlan(gd, true);
        double gSum = 0, cSum = 0; int nSamp = 0;
        for (int t = 0; t < Md; t++) {
            gd.mot.setCounts(t, SEED, gd.fil.n); gd.node.setNodeBodyCounts(t, SEED_NODE); gd.fil.counts.set(1, t);
            TornadoExecutionResult res = pd.withGridScheduler(sched).execute();
            if (t % sampEvery == sampEvery - 1) { res.transferToHost(gd.mot.boundSeg); gSum += boundHeads(gd.mot); }
        }
        for (int t = 0; t < Md; t++) { cpuStep(cd, t, true); if (t % sampEvery == sampEvery - 1) { cSum += boundHeads(cd.mot); nSamp++; } }
        double gAvg = gSum / nSamp, cAvg = cSum / nSamp;
        boolean aggOk = Math.abs(gAvg - cAvg) <= 2.0 && gAvg > 0.5 && cAvg > 0.5;
        System.out.printf("  (b) chaotic dynamic-bind (%d steps, windowed avgBound over %d samples): GPU=%.2f CPU=%.2f (|Δ|≤2, both>0) => %s%n",
                Md, nSamp, gAvg, cAvg, aggOk ? "aggregate-agree" : "*FAIL*");
        boolean ok = detOk && aggOk;
        System.out.println("  => " + (ok ? "PASS" : "*FAIL*") + "\n");
        return ok;
    }

    // ============================================================== diagnostic (geometry + per-pole engagement)
    static void diagnose(double dt, int M) {
        System.out.printf("--- diagnostic: node-contractile per-pole engagement (nSing=%d nDim=%d reach=%.4f yoff=%.4f, %d steps) ---%n",
                N_SING, N_DIM, REACH, YOFF, M);
        Scene sc = buildScene(dt, 8, true);
        sc.mot.nucleotideState.init(MotorStore.NUC_NONE);
        MotorStore mot = sc.mot; int K = sc.segPerFil;
        RigidRodBody nb = sc.node.node;
        int warm = M / 2; long sn = 0; double tA = 0, tB = 0; long bA = 0, bB = 0;
        double sgxA = 0, sgxB = 0;
        double nxMin=1e9,nxMax=-1e9,nyMin=1e9,nyMax=-1e9,nzMin=1e9,nzMax=-1e9;
        for (int t = 0; t < M; t++) {
            cpuStep(sc, t, true);
            double x=nb.coord.get(0), y=nb.coord.get(1), z=nb.coord.get(2);
            nxMin=Math.min(nxMin,x); nxMax=Math.max(nxMax,x); nyMin=Math.min(nyMin,y); nyMax=Math.max(nyMax,y); nzMin=Math.min(nzMin,z); nzMax=Math.max(nzMax,z);
            if (t >= warm) {
                sn++; tA += sc.tA; tB += sc.tB;
                for (int m = 0; m < mot.nMotors; m++) {
                    int s = mot.boundSeg.get(m); int dseg = m * CrossBridgeSystem.STRIDE;
                    if (s >= sc.filA0 && s < sc.filA0 + K) { bA++; sgxA += sc.bondData.get(dseg + 6); }
                    else if (s >= sc.filB0 && s < sc.filB0 + K) { bB++; sgxB += sc.bondData.get(dseg + 6); }
                }
            }
        }
        System.out.printf("  node excursion (free+Brownian): x∈[%.4f,%.4f] y∈[%.4f,%.4f] z∈[%.4f,%.4f] µm%n", nxMin,nxMax,nyMin,nyMax,nzMin,nzMax);
        System.out.printf("  steady tension: A=%.4f pN  B=%.4f pN (both >0 ⇒ contractile)%n", tA/sn, tB/sn);
        System.out.printf("  avgBound: A=%.2f  B=%.2f%n", bA/(double)sn, bB/(double)sn);
        System.out.printf("  mean seg-side Fx (force ON filament): A=%.3e N  B=%.3e N (A should be −x, B +x ⇒ both inward)%n", sgxA/Math.max(1,bA), sgxB/Math.max(1,bB));
        // one-shot reach diagnostic: count heads reachable to each filament after the initial bind passes
        FilamentStore f = sc.fil; RigidRodBody b = mot.body;
        int rA=0,rB=0;
        for (int m=0;m<mot.nMotors;m++){ int s=mot.boundSeg.get(m); if (s>=sc.filA0 && s<sc.filA0+K) rA++; else if (s>=sc.filB0 && s<sc.filB0+K) rB++; }
        System.out.printf("  final bound count: A=%d B=%d (of %d motors)%n", rA, rB, mot.nMotors);
        System.out.printf("  filament A inner x≈%.3f outer(pin) x≈%.3f y=%.3f ; B inner x≈%.3f outer x≈%.3f y=%.3f%n",
                f.coordX(sc.filA0+K-1), f.coordX(sc.filA0), f.coordY(sc.filA0),
                f.coordX(sc.filB0+K-1), f.coordX(sc.filB0), f.coordY(sc.filB0));
    }

    // ============================================================== dimer placement (6b-splayed; from MiniGlideHarness)
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

    // ============================================================== viewer (v1 contractility panel)
    static final String[] STATE_NAME = { "NONE", "ATP", "ADPPi", "ADP" };
    static void runViz(double dt, String dir, int M) {
        Scene sc = buildScene(dt, 8, true); sc.mot.nucleotideState.init(MotorStore.NUC_NONE);
        new java.io.File(dir).mkdirs();
        Stats st = new Stats();
        int every = Math.max(1, M / 400), frames = 0;
        for (int t = 0; t <= M; t++) {
            cpuStep(sc, t, true);
            int K = sc.segPerFil;
            st.accumulate(sc.tA, sc.tB, boundOn(sc, sc.filA0, K) + boundOn(sc, sc.filB0, K), t);
            if (t % every == 0) writeFrame(dir, frames++, t, t * dt, sc, st);
        }
        System.out.println("viewer: wrote " + frames + " frames to " + dir + " (contractility panel embedded)");
        System.out.printf("final: avgTension=%.4f pN, peakTension=%.4f pN, avgBound=%.2f, firstBind@%d%n",
                st.avgTension(), st.peakTension, st.avgBound(), st.firstBindStep);
    }
    static void writeFrame(String dir, int frame, int step, double t, Scene sc, Stats st) {
        FilamentStore f = sc.fil; MotorStore mot = sc.mot; RigidRodBody b = mot.body; RigidRodBody nb = sc.node.node;
        int K = sc.segPerFil; int bA = boundOn(sc, sc.filA0, K), bB = boundOn(sc, sc.filB0, K);
        double meanT = 0.5 * (Math.abs(sc.tA) + Math.abs(sc.tB));
        StringBuilder sb = new StringBuilder(1024 + 200 * mot.nMotors);
        sb.append(String.format(java.util.Locale.US, "{\"frame\":%d,\"t\":%.6g,\"bounds\":{\"xDim\":4.0,\"yDim\":1.0,\"zDim\":0.4}", frame, t));
        sb.append(",\"segments\":[");
        for (int s = 0; s < f.n; s++) { if (s > 0) sb.append(',');
            sb.append(String.format(java.util.Locale.US, "{\"id\":%d,\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.5g,\"notADPRatio\":1.0,\"cofilinCount\":0}",
                s, f.end1.get(s),f.end1.get(f.n+s),f.end1.get(2*f.n+s), f.end2.get(s),f.end2.get(f.n+s),f.end2.get(2*f.n+s), Constants.radius)); }
        sb.append("],\"myosins\":[");
        // node as a sphere (a degenerate motor with state "node")
        double cx=nb.coord.get(0), cy=nb.coord.get(nb.n), cz=nb.coord.get(2*nb.n);
        sb.append(String.format(java.util.Locale.US,
            "{\"id\":%d,\"rod\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g},"
            + "\"lever\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g},"
            + "\"motor\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g,\"state\":\"node\"}}",
            100000, cx,cy,cz, cx,cy,cz, NodeStore.NODE_RADIUS, cx,cy,cz, cx,cy,cz, NodeStore.NODE_RADIUS, cx,cy,cz, cx,cy,cz, NodeStore.NODE_RADIUS));
        for (int m = 0; m < mot.nMotors; m++) {
            sb.append(',');
            int rod = 3*m, lever = 3*m+1, head = 3*m+2; String state = STATE_NAME[mot.nucleotideState.get(m)];
            sb.append(String.format(java.util.Locale.US,
                "{\"id\":%d,\"rod\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g,\"invisible\":false},"
                + "\"lever\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g},"
                + "\"motor\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g,\"state\":\"%s\"}}",
                m, b.end1X(rod),b.end1Y(rod),b.end1Z(rod), b.end2X(rod),b.end2Y(rod),b.end2Z(rod), MotorStore.ROD_R,
                b.end1X(lever),b.end1Y(lever),b.end1Z(lever), b.end2X(lever),b.end2Y(lever),b.end2Z(lever), MotorStore.LEVER_R,
                b.end1X(head),b.end1Y(head),b.end1Z(head), b.end2X(head),b.end2Y(head),b.end2Z(head), MotorStore.HEAD_R, state)); }
        sb.append("]");
        sb.append(String.format(java.util.Locale.US,
            ",\"contractility\":{\"tensionA_pN\":%.5g,\"tensionB_pN\":%.5g,"
            + "\"anchorA\":{\"x\":%.5g,\"y\":%.5g,\"z\":%.5g},\"anchorB\":{\"x\":%.5g,\"y\":%.5g,\"z\":%.5g}}",
            sc.tA, sc.tB, sc.pinPt.get(0), sc.pinPt.get(1), sc.pinPt.get(2), sc.pinPt.get(3), sc.pinPt.get(4), sc.pinPt.get(5)));
        sb.append(String.format(java.util.Locale.US,
            ",\"stats\":{\"step\":%d,\"simTime\":%.5g,\"boundHeads\":%d,\"peakBound\":%d,\"avgBound\":%.4g,\"ewmaBound\":%.4g,"
            + "\"meanTension_pN\":%.5g,\"avgTension_pN\":%.5g,\"ewmaTension_pN\":%.5g,\"peakTension_pN\":%.5g,\"firstBindStep\":%d,\"hasMotor\":true}",
            step, t, bA + bB, st.peakBound, st.avgBound(), st.ewmaBound,
            meanT, st.avgTension(), st.ewmaTension, st.peakTension, st.firstBindStep));
        sb.append("}");
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(dir, String.format(java.util.Locale.US,"frame_%06d.json", frame)), sb.toString()); }
        catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }
}
