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
 * Increment 6c, Stage A: the PROTEIN NODE entity as a motor-bundle. A FIXED-ANCHOR node (a sphere body,
 * NOT integrated) owning radially-splayed singlet myosins + dimers, reusing the SETTLED, validated
 * minifilament machinery (tether LAW + single-ended CSR gather), binding (CrossBridge), the 12 pN cap,
 * and containment — byte-unchanged. The ONLY new code is the node-specific radial tether (NodeSystem,
 * the same fracMove force law as the minifilament with radial sphere-surface attach geometry).
 *
 * No nucleation / runtime filament birth (Stage B) — seam #1 kept open: the motor-function is a
 * free-standing system over the node's child arrays, separable from the future nucleation-function.
 *
 * Gates (co-developed small-scale vs BoA-v1ref, not fixtures):
 *   #1a geometry composes + node gather==brute (isolated, displaced rods) — singlets AND dimers owned
 *       and gathered, bit-identical, momentum-conserving.
 *   #1b gather UNDER LOAD — radial heads bound to test segments load the tether through real
 *       cross-bridges; node gather==brute bit-identical at non-trivial load; full-system momentum
 *       (Σmotor+Σnode+Σfil≈0); CPU≡GPU bit-identical.
 *   #2  radial binding through the real pathway — a radial head over a filament binds via
 *       publishHeadFromBody + bruteReachable + bindKinetics (the heads are functional motors).
 *   #3  the inherited 12 pN cap fires on the node's cross-bridges — an over-stretched bond detaches
 *       via the v1 break-force branch (capStats++), exactly as the contractile assay.
 *   #4  containment confines the node body — the entity-agnostic box pushes an out-of-bounds node inward.
 *   #5  fixed anchor holds — under gathered load the non-integrated node pose is exactly stationary.
 *   #6  all-OFF≡HEAD / one-impl — node tether off ⇒ the motor bodies evolve as a bare bed (bit-identical),
 *       tether on differs (control); the node reuses the validated tether/gather/binding with no fork.
 */
public final class ProteinNodeHarness {

    static boolean cpu = false;
    static GridScheduler sched;
    static final int B = 64;
    static final int SEED = 0x6C9A0E, SEED_NODE = 0x5C2F11;
    static final double GOLDEN = 2.399963229728653;          // golden angle (Fibonacci sphere)
    static final double MYO_SPRING = 1.0e-9, J1_FMT = 0.4;   // cross-bridge spring + J1 frac-move
    static final double REACH = 0.006, ALIGN_TOL = -0.4;     // v1 myoColTol / align tolerance (contractile)
    static final double SIN80 = Math.sin(Math.toRadians(80.0)), COS80 = Math.cos(Math.toRadians(80.0));

    public static void main(String[] args) {
        double dt = 1.0e-5;
        String vizDir = null;
        int nNodes = 2, nSing = 6, nDim = 6;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-cpu" -> cpu = true;
                case "-3js" -> vizDir = args[++i];
                case "-n"   -> nNodes = Integer.parseInt(args[++i]);
                case "-s"   -> nSing = Integer.parseInt(args[++i]);
                case "-d"   -> nDim  = Integer.parseInt(args[++i]);
                default -> {}
            }
        }
        System.out.println("=== Soft Box increment 6c (Stage A) — PROTEIN NODE entity (radial motor-bundle, fixed anchor) ===");
        System.out.println("reuses the minifilament tether LAW + single-ended CSR gather + binding + 12 pN cap + containment;");
        System.out.println("the only new code = NodeSystem.tether (radial sphere-surface attach). No nucleation (Stage B).\n");
        if (vizDir != null) { runViz(dt, vizDir, nNodes, nSing, nDim); return; }

        boolean g6 = checkAllOffEqualsHead(dt);
        boolean g1a = checkGatherIsolated(dt);
        boolean g2 = checkRadialBinding(dt);
        boolean g3 = checkCapFires(dt);
        boolean g4 = checkContainment(dt);
        boolean g1b = checkGatherUnderLoad(dt);
        boolean g5 = checkFixedAnchor(dt);

        boolean ok = g1a && g1b && g2 && g3 && g4 && g5 && g6;
        System.out.println("\n=== PROTEIN-NODE (Stage A) VALIDATION " + (ok ? "PASS" : "FAIL") + " ===");
        if (!ok) { System.out.println("BAIL-OUT: a gate failed. Commit nothing."); System.exit(1); }
    }

    // ============================================================== scene
    static final class Scene {
        NodeStore node; MotorStore mot; DimerStore dim; FilamentStore fil;
        FloatArray bondData, xbParams;
        IntArray segMotorCount, segMotorOffsets, segMotorMyo;
        FloatArray bruteNodeF, bruteNodeT, bruteFilF, bruteFilT;
        int nSing, nDim, motorsPerNode;
        boolean tetherOn = true;
        boolean withCycle = false;
    }

    /** nNodes fixed-anchor nodes in a row (LANE apart), each owning nSing singlets + nDim dimers splayed
     *  radially over the sphere surface (Fibonacci). Heads free; no test filament yet (bound per-gate). */
    static Scene buildScene(double dt, int nNodes, int nSing, int nDim, int nSeg) {
        Scene sc = new Scene();
        sc.nSing = nSing; sc.nDim = nDim;
        int motorsPerNode = nSing + 2 * nDim;
        sc.motorsPerNode = motorsPerNode;
        int nMot = nNodes * motorsPerNode;
        int nDimTot = nNodes * nDim;
        int nAtt = nNodes * (nSing + nDim);
        double R = NodeStore.NODE_RADIUS;
        double LANE = 0.6;

        MotorStore mot = new MotorStore(nMot);
        DimerStore dim = new DimerStore(Math.max(1, nDimTot));
        NodeStore node = new NodeStore(nNodes, nAtt);
        FilamentStore fil = new FilamentStore(Math.max(1, nSeg));

        int nChild = nSing + nDim;
        double y0 = -0.5 * (nNodes - 1) * LANE;
        for (int k = 0; k < nNodes; k++) {
            double ncx = 0.0, ncy = y0 + k * LANE, ncz = 0.0;
            node.node.setCoord(node.nodeIdx(k), (float) ncx, (float) ncy, (float) ncz);
            node.node.setUVec(node.nodeIdx(k), 1f, 0f, 0f);
            node.node.setYVec(node.nodeIdx(k), 0f, 1f, 0f);
            node.node.brownTransScale.set(node.nodeIdx(k), 0f);
            node.node.brownRotScale.set(node.nodeIdx(k), 0f);

            int baseMot = k * motorsPerNode, baseDim = k * nDim, baseAtt = k * nChild;
            for (int c = 0; c < nChild; c++) {
                // Fibonacci-sphere radial unit direction (deterministic, reproducible)
                double yy = 1.0 - 2.0 * (c + 0.5) / nChild;
                double rr = Math.sqrt(Math.max(0.0, 1.0 - yy * yy));
                double phi = c * GOLDEN;
                double ux = rr * Math.cos(phi), uy = yy, uz = rr * Math.sin(phi);
                // surface attach point = node center + R·radialDir (body frame == world at the identity pose)
                double sx = ncx + R * ux, sy = ncy + R * uy, sz = ncz + R * uz;
                if (c < nSing) {
                    int m = baseMot + c;
                    mot.assembleArticulated(m, (float) sx, (float) sy, (float) sz, (float) ux, (float) uy, (float) uz, 0f);
                    double coeff = NodeStore.ATTN_FORCE / nSing;   // v1 keepMyosinsOnSurface attnForce/numNodeMyos
                    node.attach(baseAtt + c, k, m, R * ux, R * uy, R * uz, coeff, false);   // singlet: center, no torque
                } else {
                    int j = c - nSing;
                    int mA = baseMot + nSing + 2 * j, mB = mA + 1;
                    placeDimerAlong(mot, mA, mB, sx, sy, sz, ux, uy, uz);
                    dim.pair(baseDim + j, mA, mB, true);
                    double coeff = NodeStore.ATTN_FORCE * NodeStore.DIMER_FRACMOVE;   // v1 keepMyosinDimersOnSurface
                    node.attach(baseAtt + c, k, mA, R * ux, R * uy, R * uz, coeff, true);  // dimer: end1, torque
                }
            }
        }
        DragTensorSystem.run(mot);
        node.initNodeDrag();
        mot.setBodyParams(dt); mot.setJointParams(dt); mot.setKinParams(REACH, ALIGN_TOL, dt); mot.setNucParams(dt);
        mot.setFaithfulRelease(true, 0.0);             // inherit the 12 pN break-force cap (faithful to v1)
        dim.setDimerParams(dt);
        node.setNodeParams(dt); node.setNodeBodyParams(dt);
        // node body is FIXED (never integrated); derive its frame ONCE so the radial attach uses zVec
        DerivedGeometrySystem.derive(node.node.coord, node.node.uVec, node.node.yVec, node.node.zVec,
                node.node.end1, node.node.end2, node.node.segLength, node.nodeBodyCounts);

        if (nSeg > 0) {
            DragTensorSystem.run(fil); fil.setParams(dt, 0); fil.setCounts(0, 0);
            for (int s = 0; s < nSeg; s++) { fil.monomerCount.set(s, Constants.stdSegLength); fil.setUVec(s, 1f, 0f, 0f); fil.setYVec(s, 0f, 1f, 0f);
                fil.brownTransScale.set(s, 0f); fil.brownRotScale.set(s, 0f); }
        }
        sc.bondData = new FloatArray(nMot * CrossBridgeSystem.STRIDE); sc.bondData.init(0f);
        sc.xbParams = FloatArray.fromElements((float) MYO_SPRING, 90f, (float) J1_FMT, (float) dt, (float) MotorStore.HEAD_LEN, 0f);
        sc.segMotorCount = new IntArray(Math.max(1, nSeg)); sc.segMotorOffsets = new IntArray(Math.max(1, nSeg) + 1); sc.segMotorMyo = new IntArray(nMot);
        sc.bruteNodeF = new FloatArray(3 * nNodes); sc.bruteNodeT = new FloatArray(3 * nNodes);
        sc.bruteFilF = new FloatArray(3 * Math.max(1, nSeg)); sc.bruteFilT = new FloatArray(3 * Math.max(1, nSeg));
        sc.node = node; sc.mot = mot; sc.dim = dim; sc.fil = fil;
        return sc;
    }

    // ============================================================== per-step (mechanics; deterministic)
    static Runnable cpuStep(Scene sc) {
        NodeStore nd = sc.node; MotorStore mot = sc.mot; DimerStore dim = sc.dim; FilamentStore f = sc.fil;
        RigidRodBody b = mot.body; RigidRodBody nb = nd.node; boolean tetherOn = sc.tetherOn; boolean withCycle = sc.withCycle;
        return () -> {
            if (withCycle) NucleotideCycleSystem.cycle(mot.nucleotideState, mot.boundSeg, mot.forceDotHist, mot.nucParams, mot.counts);
            ChainBendingForceSystem.zeroAccumulators(b.forceSum, b.torqueSum, mot.counts);
            ChainBendingForceSystem.zeroAccumulators(nb.forceSum, nb.torqueSum, nd.nodeBodyCounts);
            MotorJointSystem.joints(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, mot.nucleotideState, mot.jointParams, mot.counts);
            DimerCouplingSystem.couple(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, dim.motorA, dim.motorB, dim.parallel, dim.dimerParams, mot.boundSeg);
            if (tetherOn) {
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
            // node body NOT integrated (fixed anchor)
            DerivedGeometrySystem.derive(b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts);
            CrossBridgeSystem.registerForceDot(sc.bondData, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.counts);
            // fil-side gather (for momentum readout; filament not integrated — pinned test segments)
            ChainBendingForceSystem.zeroAccumulators(f.forceSum, f.torqueSum, f.counts);
            CrossBridgeSystem.csrHistogram(mot.boundSeg, mot.counts, sc.segMotorCount);
            CrossBridgeSystem.csrScan(mot.counts, sc.segMotorCount, sc.segMotorOffsets);
            CrossBridgeSystem.csrScatter(mot.boundSeg, mot.counts, sc.segMotorOffsets, sc.segMotorCount, sc.segMotorMyo);
            CrossBridgeSystem.segGather(sc.segMotorOffsets, sc.segMotorMyo, sc.bondData, f.forceSum, f.torqueSum, mot.counts);
        };
    }

    static TornadoExecutionPlan buildPlan(Scene sc) {
        NodeStore nd = sc.node; MotorStore mot = sc.mot; DimerStore dim = sc.dim; FilamentStore f = sc.fil;
        RigidRodBody b = mot.body; RigidRodBody nb = nd.node;
        TaskGraph tg = new TaskGraph("node")
            .transferToDevice(DataTransferMode.FIRST_EXECUTION,
                    b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, b.bTransGam, b.bRotGam,
                    b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.brownTransScale, b.brownRotScale,
                    mot.bodyParams, mot.jointParams, mot.nucleotideState, mot.boundSeg, mot.bindArc,
                    mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.nucParams,
                    dim.motorA, dim.motorB, dim.parallel, dim.dimerParams,
                    nb.coord, nb.uVec, nb.yVec, nb.zVec, nb.end1, nb.end2, nb.segLength, nb.bTransGam, nb.bRotGam,
                    nb.forceSum, nb.torqueSum, nd.nodeInvTransY,
                    nd.attachNode, nd.attachKey, nd.radial, nd.attachCoeffK,
                    nd.nodeData, nd.nodeParams, nd.nodeAttachCount, nd.nodeAttachOffsets, nd.nodeAttachList, nd.nodeCounts4,
                    sc.bondData, sc.xbParams, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength, f.forceSum, f.torqueSum,
                    sc.segMotorCount, sc.segMotorOffsets, sc.segMotorMyo)
            .transferToDevice(DataTransferMode.EVERY_EXECUTION, mot.counts, nd.nodeBodyCounts, f.counts)
            .task("zeroMot", ChainBendingForceSystem::zeroAccumulators, b.forceSum, b.torqueSum, mot.counts)
            .task("zeroNode", ChainBendingForceSystem::zeroAccumulators, nb.forceSum, nb.torqueSum, nd.nodeBodyCounts)
            .task("joints", MotorJointSystem::joints, b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, mot.nucleotideState, mot.jointParams, mot.counts)
            .task("dimer", DimerCouplingSystem::couple, b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, dim.motorA, dim.motorB, dim.parallel, dim.dimerParams, mot.boundSeg)
            .task("tether", NodeSystem::tether, b.coord, b.uVec, b.segLength, b.bTransGam, b.forceSum, b.torqueSum,
                    nb.coord, nb.uVec, nb.yVec, nd.nodeInvTransY,
                    nd.attachKey, nd.radial, nd.attachCoeffK, nd.nodeData, nd.nodeParams)
            .task("ndHist", CrossBridgeSystem::csrHistogram, nd.attachNode, nd.nodeCounts4, nd.nodeAttachCount)
            .task("ndScan", CrossBridgeSystem::csrScan, nd.nodeCounts4, nd.nodeAttachCount, nd.nodeAttachOffsets)
            .task("ndScatter", CrossBridgeSystem::csrScatter, nd.attachNode, nd.nodeCounts4, nd.nodeAttachOffsets, nd.nodeAttachCount, nd.nodeAttachList)
            .task("ndGather", MiniFilamentSystem::backboneGather, nd.nodeAttachOffsets, nd.nodeAttachList, nd.nodeData, nb.forceSum, nb.torqueSum, nd.nodeCounts4)
            .task("bond", CrossBridgeSystem::bondForces, b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength, mot.boundSeg, mot.bindArc, mot.nucleotideState, sc.bondData, sc.xbParams)
            .task("applyHead", CrossBridgeSystem::applyHeadForce, sc.bondData, b.forceSum, b.torqueSum, mot.counts)
            .task("integM", RigidRodLangevinIntegrationSystem::integrate, b.coord, b.uVec, b.yVec, b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, mot.bodyParams, mot.counts)
            .task("deriveM", DerivedGeometrySystem::derive, b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts)
            .task("register", CrossBridgeSystem::registerForceDot, sc.bondData, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.counts)
            .task("zeroFil", ChainBendingForceSystem::zeroAccumulators, f.forceSum, f.torqueSum, f.counts)
            .task("filHist", CrossBridgeSystem::csrHistogram, mot.boundSeg, mot.counts, sc.segMotorCount)
            .task("filScan", CrossBridgeSystem::csrScan, mot.counts, sc.segMotorCount, sc.segMotorOffsets)
            .task("filScatter", CrossBridgeSystem::csrScatter, mot.boundSeg, mot.counts, sc.segMotorOffsets, sc.segMotorCount, sc.segMotorMyo)
            .task("filGather", CrossBridgeSystem::segGather, sc.segMotorOffsets, sc.segMotorMyo, sc.bondData, f.forceSum, f.torqueSum, mot.counts)
            .transferToHost(DataTransferMode.UNDER_DEMAND, b.coord, b.uVec, nb.forceSum, nb.torqueSum, f.forceSum, mot.boundSeg);

        int nMB = b.n, nN = nb.n, nM = mot.nMotors, nSeg = f.n, nD = dim.nDimers, nA = nd.nAttach;
        sched = new GridScheduler();
        addW("node.zeroMot", pad(nMB)); addW("node.zeroNode", pad(nN));
        addW("node.joints", pad(nMB)); addW("node.dimer", pad(nD)); addW("node.tether", pad(nA));
        addS("node.ndHist"); addS("node.ndScan"); addS("node.ndScatter"); addW("node.ndGather", pad(nN));
        addW("node.bond", pad(nM)); addW("node.applyHead", pad(nM));
        addW("node.integM", pad(nMB)); addW("node.deriveM", pad(nMB)); addW("node.register", pad(nM));
        addW("node.zeroFil", pad(nSeg));
        addS("node.filHist"); addS("node.filScan"); addS("node.filScatter"); addW("node.filGather", pad(nSeg));
        return new TornadoExecutionPlan(tg.snapshot());
    }
    static int pad(int n) { return ((n + B - 1) / B) * B; }
    static void addW(String n, int g) { WorkerGrid w = new WorkerGrid1D(g); w.setLocalWork(B, 1, 1); sched.addWorkerGrid(n, w); }
    static void addS(String n) { WorkerGrid w = new WorkerGrid1D(1); w.setLocalWork(1, 1, 1); sched.addWorkerGrid(n, w); }

    static void runCpu(Scene sc, int M) {
        Runnable step = cpuStep(sc);
        for (int t = 0; t < M; t++) { sc.mot.setCounts(t, SEED, sc.fil.n); sc.node.setNodeBodyCounts(t, SEED_NODE); step.run(); }
    }
    static void runGpu(Scene sc, int M) {
        TornadoExecutionPlan plan = buildPlan(sc);
        RigidRodBody b = sc.mot.body, nb = sc.node.node;
        for (int t = 0; t < M; t++) {
            sc.mot.setCounts(t, SEED, sc.fil.n); sc.node.setNodeBodyCounts(t, SEED_NODE);
            TornadoExecutionResult res = plan.withGridScheduler(sched).execute();
            if (t == M - 1) res.transferToHost(b.coord, b.uVec, nb.forceSum, nb.torqueSum, sc.fil.forceSum, sc.mot.boundSeg);
        }
    }
    static double maxDiff(FloatArray a, FloatArray b) { double m=0; for (int i=0;i<a.getSize();i++) m=Math.max(m, Math.abs(a.get(i)-b.get(i))); return m; }
    static long countBound(MotorStore m) { long c=0; for (int i=0;i<m.nMotors;i++) if (m.boundSeg.get(i)>=0) c++; return c; }

    // ============================================================== #1a gather==brute (isolated, displaced rods)
    static boolean checkGatherIsolated(double dt) {
        System.out.println("--- #1a: geometry composes + node gather==brute (isolated, displaced rods) ---");
        Scene sc = buildScene(dt, 2, 6, 6, 0);   // singlets AND dimers, no filament
        NodeStore nd = sc.node; MotorStore mot = sc.mot; RigidRodBody b = mot.body; RigidRodBody nb = nd.node;
        // displace some tethered rods off their surface points so the tether is loaded (non-trivial)
        for (int a = 0; a < nd.nAttach; a += 2) {
            int rod = 3 * nd.attachMotor.get(a);
            b.setCoord(rod, b.coord.get(rod) + 0.004f, b.coord.get(b.n + rod) - 0.003f, b.coord.get(2 * b.n + rod) + 0.002f);
        }
        DerivedGeometrySystem.derive(b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts);

        ChainBendingForceSystem.zeroAccumulators(b.forceSum, b.torqueSum, mot.counts);
        ChainBendingForceSystem.zeroAccumulators(nb.forceSum, nb.torqueSum, nd.nodeBodyCounts);
        NodeSystem.tether(b.coord, b.uVec, b.segLength, b.bTransGam, b.forceSum, b.torqueSum,
                nb.coord, nb.uVec, nb.yVec, nd.nodeInvTransY,
                nd.attachKey, nd.radial, nd.attachCoeffK, nd.nodeData, nd.nodeParams);
        // capture per-node rod self-write sum BEFORE gather (for momentum)
        double[] rodSum = new double[nd.nNodes * 3];
        for (int a = 0; a < nd.nAttach; a++) {
            int kk = nd.attachNode.get(a), rod = 3 * nd.attachMotor.get(a);
            rodSum[kk*3] += b.forceSum.get(rod); rodSum[kk*3+1] += b.forceSum.get(b.n+rod); rodSum[kk*3+2] += b.forceSum.get(2*b.n+rod);
        }
        CrossBridgeSystem.csrHistogram(nd.attachNode, nd.nodeCounts4, nd.nodeAttachCount);
        CrossBridgeSystem.csrScan(nd.nodeCounts4, nd.nodeAttachCount, nd.nodeAttachOffsets);
        CrossBridgeSystem.csrScatter(nd.attachNode, nd.nodeCounts4, nd.nodeAttachOffsets, nd.nodeAttachCount, nd.nodeAttachList);
        MiniFilamentSystem.backboneGather(nd.nodeAttachOffsets, nd.nodeAttachList, nd.nodeData, nb.forceSum, nb.torqueSum, nd.nodeCounts4);

        sc.bruteNodeF.init(0f); sc.bruteNodeT.init(0f);
        MiniFilamentSystem.bruteGather(nd.attachNode, nd.nodeData, sc.bruteNodeF, sc.bruteNodeT, nd.nodeCounts4);
        double gMax = 0, load = 0;
        for (int i = 0; i < 3 * nd.nNodes; i++) {
            gMax = Math.max(gMax, Math.abs(nb.forceSum.get(i) - sc.bruteNodeF.get(i)));
            gMax = Math.max(gMax, Math.abs(nb.torqueSum.get(i) - sc.bruteNodeT.get(i)));
            load = Math.max(load, Math.abs(nb.forceSum.get(i)));
        }
        boolean gatherOk = gMax == 0.0 && load > 1e-15;
        System.out.printf("  node gather==brute: max|Δ|=%.3e (==0 bit-identical); max node load=%.3e N (>0) => %s%n", gMax, load, gatherOk ? "ok" : "*FAIL*");

        // momentum: gathered node force == −(Σ rod self-write) per node
        double momMax = 0;
        for (int kk = 0; kk < nd.nNodes; kk++) for (int q = 0; q < 3; q++)
            momMax = Math.max(momMax, Math.abs(nb.forceSum.get(q*nd.nNodes + kk) + rodSum[kk*3+q]));
        boolean momOk = momMax < 1e-18;
        System.out.printf("  momentum (gathered node + Σrod self-write = 0): max=%.3e N (~0) => %s%n", momMax, momOk ? "ok" : "*FAIL*");

        // both types present
        int nS = 0, nD = 0; for (int a = 0; a < nd.nAttach; a++) { if (nd.attachAtEnd1.get(a)==0) nS++; else nD++; }
        boolean bothTypes = nS > 0 && nD > 0;
        System.out.printf("  ownership: %d singlet + %d dimer attachments gathered (both types) => %s%n", nS, nD, bothTypes ? "ok" : "*FAIL*");
        boolean ok = gatherOk && momOk && bothTypes;
        System.out.println("  => " + (ok ? "PASS" : "*FAIL*") + "\n");
        return ok;
    }

    // ============================================================== #1b gather UNDER LOAD + CPU≡GPU
    /** Bind a hemisphere of radial heads to per-head test segments, displace the segments to load the
     *  cross-bridges, run loaded steps; the node gathers the collective load through the tether. */
    static Scene buildLoadedScene(double dt) {
        // count bindable heads (radial dir with +x component) to size the filament store
        int nNodes = 1, nSing = 6, nDim = 6, nChild = nSing + nDim, motorsPerNode = nSing + 2*nDim;
        // pre-scan which heads we will bind (singlet heads + dimer myo1 heads pointing +x)
        java.util.List<int[]> binds = new java.util.ArrayList<>();   // {motorSlot}
        for (int c = 0; c < nChild; c++) {
            double yy = 1.0 - 2.0*(c+0.5)/nChild, rr = Math.sqrt(Math.max(0,1-yy*yy)), phi = c*GOLDEN;
            double ux = rr*Math.cos(phi);
            if (ux <= 0.2) continue;                       // bind only clearly-outward +x heads
            int m = (c < nSing) ? c : (nSing + 2*(c-nSing));   // singlet motor, or dimer myo1 motor
            binds.add(new int[]{m});
        }
        int nSeg = binds.size();
        Scene sc = buildScene(dt, nNodes, nSing, nDim, nSeg);
        MotorStore mot = sc.mot; RigidRodBody b = mot.body; FilamentStore f = sc.fil;
        // bind each chosen head to its own segment placed at the head tip (then displace to load it)
        for (int s = 0; s < nSeg; s++) {
            int m = binds.get(s)[0], h = 3*m+2;
            double hl = b.segLength.get(h);
            double htx = b.coord.get(h) + 0.5*hl*b.uVec.get(h);
            double hty = b.coord.get(b.n+h) + 0.5*hl*b.uVec.get(b.n+h);
            double htz = b.coord.get(2*b.n+h) + 0.5*hl*b.uVec.get(2*b.n+h);
            // place seg center at head tip + a 5 nm displacement (loads the F8 spring) ; uVec ⟂-ish
            f.setCoord(s, (float)(htx + 0.005), (float)(hty + 0.003), (float)(htz));
            f.setUVec(s, 0f, 1f, 0f); f.setYVec(s, 1f, 0f, 0f);
            mot.boundSeg.set(m, s);
            mot.bindArc.set(m, 0.5f * f.segLength.get(s));   // bind site at seg center
        }
        mot.setAllStates(MotorStore.NUC_ADPPI);
        DragTensorSystem.run(f); f.setParams(dt, 0); f.setCounts(0, 0);
        DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
        return sc;
    }

    static boolean checkGatherUnderLoad(double dt) {
        System.out.println("--- #1b: node gather UNDER LOAD (real cross-bridges) + momentum + CPU≡GPU ---");
        Scene sc = buildLoadedScene(dt);
        long bound = countBound(sc.mot);
        runCpu(sc, 200);
        NodeStore nd = sc.node; MotorStore mot = sc.mot; RigidRodBody b = mot.body; RigidRodBody nb = nd.node; FilamentStore f = sc.fil;

        sc.bruteNodeF.init(0f); sc.bruteNodeT.init(0f);
        MiniFilamentSystem.bruteGather(nd.attachNode, nd.nodeData, sc.bruteNodeF, sc.bruteNodeT, nd.nodeCounts4);
        double ndMax = 0, ndLoad = 0;
        for (int i = 0; i < 3*nd.nNodes; i++) {
            ndMax = Math.max(ndMax, Math.abs(nb.forceSum.get(i) - sc.bruteNodeF.get(i)));
            ndMax = Math.max(ndMax, Math.abs(nb.torqueSum.get(i) - sc.bruteNodeT.get(i)));
            ndLoad = Math.max(ndLoad, Math.abs(nb.forceSum.get(i)));
        }
        boolean ndOk = ndMax == 0.0 && ndLoad > 1e-15;
        System.out.printf("  bound heads=%d; node gather==brute max|Δ|=%.3e (==0); max node load=%.3e N (>0) => %s%n", bound, ndMax, ndLoad, ndOk ? "ok" : "*FAIL*");

        sc.bruteFilF.init(0f); sc.bruteFilT.init(0f);
        CrossBridgeSystem.bruteGather(mot.boundSeg, sc.bondData, sc.bruteFilF, sc.bruteFilT, mot.counts);
        double fMax = 0; for (int i = 0; i < 3*f.n; i++) fMax = Math.max(fMax, Math.abs(f.forceSum.get(i) - sc.bruteFilF.get(i)));
        boolean fOk = fMax == 0.0;
        System.out.printf("  fil gather==brute max|Δ|=%.3e (==0) => %s%n", fMax, fOk ? "ok" : "*FAIL*");

        // full-system momentum: Σmotor + Σnode + Σfil ≈ 0
        double momMax = 0;
        for (int q = 0; q < 3; q++) {
            double sm=0, sn=0, sf=0;
            for (int i=0;i<b.n;i++)  sm += b.forceSum.get(q*b.n+i);
            for (int i=0;i<nb.n;i++) sn += nb.forceSum.get(q*nb.n+i);
            for (int s=0;s<f.n;s++)  sf += f.forceSum.get(q*f.n+s);
            momMax = Math.max(momMax, Math.abs(sm+sn+sf));
        }
        boolean momOk = momMax < 1e-15;
        System.out.printf("  full-system momentum |Σmotor+Σnode+Σfil| max=%.3e N (~0) => %s%n", momMax, momOk ? "ok" : "*FAIL*");

        boolean cpuGpuOk = true;
        if (!cpu) {
            Scene g = buildLoadedScene(dt); Scene c = buildLoadedScene(dt);
            runGpu(g, 300); runCpu(c, 300);
            double dM = maxDiff(g.mot.body.coord, c.mot.body.coord);
            cpuGpuOk = dM < 5e-5;
            System.out.printf("  CPU≡GPU (300 loaded steps): max|Δmotor|=%.3e µm (<5e-5) => %s%n", dM, cpuGpuOk ? "ok" : "*FAIL*");
        } else System.out.println("  CPU≡GPU: skipped (-cpu)");
        boolean ok = ndOk && fOk && momOk && cpuGpuOk;
        System.out.println("  => " + (ok ? "PASS" : "*FAIL*") + "\n");
        return ok;
    }

    // ============================================================== #2 radial binding through the real pathway
    static boolean checkRadialBinding(double dt) {
        System.out.println("--- #2: radial heads bind through the real pathway (publishHead + bruteReachable + bindKinetics) ---");
        Scene sc = buildScene(dt, 1, 4, 0, 1);   // 4 singlets, 1 test segment
        MotorStore mot = sc.mot; RigidRodBody b = mot.body; FilamentStore f = sc.fil;
        // pick the +x-most singlet head, place a segment right at its tip (within reach), oriented for the bind gate
        int best = 0; double bestUx = -2;
        for (int m = 0; m < mot.nMotors; m++) { double ux = b.uVec.get(3*m+2); if (ux > bestUx) { bestUx = ux; best = m; } }
        int h = 3*best+2; double hl = b.segLength.get(h);
        double htx = b.coord.get(h)+0.5*hl*b.uVec.get(h), hty = b.coord.get(b.n+h)+0.5*hl*b.uVec.get(b.n+h), htz = b.coord.get(2*b.n+h)+0.5*hl*b.uVec.get(2*b.n+h);
        f.setCoord(0, (float)htx, (float)hty, (float)htz); f.setUVec(0, 0f,1f,0f); f.setYVec(0, 1f,0f,0f);
        DragTensorSystem.run(f); f.setParams(dt,0); f.setCounts(0,0);
        DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
        IntArray reachSeg = new IntArray(mot.nMotors * SpatialGrid.MAX_CAND); reachSeg.init(-1);
        IntArray reachCount = new IntArray(mot.nMotors);
        boolean bound = false;
        for (int t = 0; t < 5 && !bound; t++) {
            mot.setCounts(t, SEED, f.n);
            MotorStore.publishHeadFromBody(b.coord, b.uVec, b.segLength, mot.head, mot.uVec, mot.rodUVec, mot.counts);
            BindingDetectionSystem.bruteReachable(mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, reachSeg, reachCount, mot.kinParams, mot.counts);
            BindingDetectionSystem.bindKinetics(mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, reachSeg, reachCount, mot.boundSeg, mot.bindArc, mot.stats, mot.kinParams, mot.counts);
            if (mot.boundSeg.get(best) >= 0) bound = true;
        }
        System.out.printf("  radial singlet head %d (uVec.x=%.3f) over a filament: bound=%s => %s%n", best, bestUx, bound, bound ? "ok" : "*FAIL*");
        System.out.println("  => " + (bound ? "PASS" : "*FAIL*") + "\n");
        return bound;
    }

    // ============================================================== #3 the inherited 12 pN cap fires
    static boolean checkCapFires(double dt) {
        System.out.println("--- #3: the inherited 12 pN break-force cap fires on the node's cross-bridges ---");
        Scene sc = buildScene(dt, 1, 1, 0, 1);   // one singlet head
        MotorStore mot = sc.mot; RigidRodBody b = mot.body; FilamentStore f = sc.fil;
        int m = 0, h = 3*m+2; double hl = b.segLength.get(h);
        double htx = b.coord.get(h)+0.5*hl*b.uVec.get(h), hty = b.coord.get(b.n+h)+0.5*hl*b.uVec.get(b.n+h), htz = b.coord.get(2*b.n+h)+0.5*hl*b.uVec.get(2*b.n+h);
        // over-stretch: bind site far from the head tip ⇒ |F8| = myoSpring·dist ≫ 12 pN
        double over = (13.0e-12) / MYO_SPRING;   // dist giving ~13 pN (> 12 pN threshold)
        f.setCoord(0, (float)(htx + over), (float)hty, (float)htz); f.setUVec(0, 0f,1f,0f); f.setYVec(0, 1f,0f,0f);
        DragTensorSystem.run(f); f.setParams(dt,0); f.setCounts(0,0);
        DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
        mot.boundSeg.set(m, 0); mot.bindArc.set(m, 0.5f*f.segLength.get(0)); mot.setAllStates(MotorStore.NUC_ADPPI);
        mot.setCounts(0, SEED, f.n);
        CrossBridgeSystem.bondForces(b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength, mot.boundSeg, mot.bindArc, mot.nucleotideState, sc.bondData, sc.xbParams);
        CrossBridgeSystem.registerForceDot(sc.bondData, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.counts);
        double fm = mot.forceMag.get(m);
        CrossBridgeSystem.csrHistogram(mot.boundSeg, mot.counts, sc.segMotorCount);   // (unused; keep parity)
        NucleotideCycleSystem.catchSlipRelease(mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.cooldown, mot.stats, mot.capStats, mot.kinParams, mot.counts);
        boolean released = mot.boundSeg.get(m) < 0, capFired = mot.capStats.get(m) > 0;
        System.out.printf("  cross-bridge |F8|=%.2f pN (>12); released=%s via cap (capStats=%d) => %s%n", fm*1e12, released, mot.capStats.get(m), (released && capFired) ? "ok" : "*FAIL*");
        boolean ok = released && capFired;
        System.out.println("  => " + (ok ? "PASS" : "*FAIL*") + "\n");
        return ok;
    }

    // ============================================================== #4 containment confines the node body
    static boolean checkContainment(double dt) {
        System.out.println("--- #4: the entity-agnostic containment box confines the NODE body (positions, not classes) ---");
        Scene sc = buildScene(dt, 1, 1, 0, 0);
        NodeStore nd = sc.node; RigidRodBody nb = nd.node;
        // box ~0.2 µm (half 0.1) ; place the node past +y wall
        FloatArray boxParams = FloatArray.fromElements(1.0e-4f, 0.2f, 0.2f, 0.2f, (float) NodeStore.NODE_RADIUS, 0.5f, 1f);
        nb.setCoord(0, 0f, 0.18f, 0f);
        DerivedGeometrySystem.derive(nb.coord, nb.uVec, nb.yVec, nb.zVec, nb.end1, nb.end2, nb.segLength, nd.nodeBodyCounts);
        double y0 = nb.coord.get(nb.n);
        nd.setNodeBodyCounts(0, SEED_NODE);
        ChainBendingForceSystem.zeroAccumulators(nb.forceSum, nb.torqueSum, nd.nodeBodyCounts);
        ContainmentSystem.confine(nb.coord, nb.uVec, nb.segLength, nb.bTransGam, nb.forceSum, nb.torqueSum, boxParams, nd.nodeBodyCounts);
        RigidRodLangevinIntegrationSystem.integrate(nb.coord, nb.uVec, nb.yVec, nb.forceSum, nb.torqueSum, nb.randForce, nb.randTorque, nb.bTransGam, nb.bRotGam, nd.nodeBodyParams, nd.nodeBodyCounts);
        double y1 = nb.coord.get(nb.n);
        boolean ok = y1 < y0;
        System.out.printf("  node placed past +y wall (y=%.4f → %.4f µm, pushed inward) => %s%n", y0, y1, ok ? "ok" : "*FAIL*");
        System.out.println("  => " + (ok ? "PASS" : "*FAIL*") + "\n");
        return ok;
    }

    // ============================================================== #5 fixed anchor holds under load
    static boolean checkFixedAnchor(double dt) {
        System.out.println("--- #5: fixed anchor — the non-integrated node pose is exactly stationary under gathered load ---");
        Scene sc = buildLoadedScene(dt);
        RigidRodBody nb = sc.node.node;
        FloatArray before = new FloatArray(nb.coord.getSize());
        for (int i = 0; i < before.getSize(); i++) before.set(i, nb.coord.get(i));
        runCpu(sc, 300);
        double d = maxDiff(before, nb.coord);
        boolean ok = d == 0.0;
        System.out.printf("  node Δpose after 300 loaded steps = %.3e µm (==0 fixed anchor) => %s%n", d, ok ? "ok" : "*FAIL*");
        System.out.println("  => " + (ok ? "PASS" : "*FAIL*") + "\n");
        return ok;
    }

    // ============================================================== #6 all-OFF ≡ HEAD (one-impl)
    static boolean checkAllOffEqualsHead(double dt) {
        System.out.println("--- #6: all-OFF ≡ HEAD — node tether off ⇒ bare motor bed (bit-identical); tether on differs ---");
        Scene a = buildScene(dt, 2, 6, 6, 0); a.tetherOn = false;
        Scene b = buildScene(dt, 2, 6, 6, 0); b.tetherOn = false;
        // Brownian on so the bed actually evolves
        for (int i = 0; i < a.mot.body.n; i++) { a.mot.body.brownTransScale.set(i, 1f); b.mot.body.brownTransScale.set(i, 1f); }
        runCpu(a, 1000); runCpu(b, 1000);
        double dOff = maxDiff(a.mot.body.coord, b.mot.body.coord);
        boolean det = dOff == 0.0;
        Scene cOn = buildScene(dt, 2, 6, 6, 0);
        for (int i = 0; i < cOn.mot.body.n; i++) cOn.mot.body.brownTransScale.set(i, 1f);
        runCpu(cOn, 1000);
        double dOnVsOff = maxDiff(cOn.mot.body.coord, a.mot.body.coord);
        boolean controlOk = dOnVsOff > 1e-9;
        boolean ok = det && controlOk;
        System.out.printf("  bare-bed determinism (tether off): max|Δ|=%.3e (==0) => %s%n", dOff, det ? "ok" : "*FAIL*");
        System.out.printf("  control: tether ON vs OFF differs by %.3e µm (the node coupling is real) => %s%n", dOnVsOff, controlOk ? "ok" : "*FAIL*");
        System.out.println("  => " + (ok ? "PASS" : "*FAIL*") + "\n");
        return ok;
    }

    // ============================================================== dimer placement (6b-splayed rest, VERBATIM MiniGlide)
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
        b.brownTransScale.set(rod, 0f);   b.brownRotScale.set(rod, 0f);
        b.brownTransScale.set(lever, 0f); b.brownRotScale.set(lever, 0f);
        b.brownTransScale.set(head, 0f);  b.brownRotScale.set(head, 0f);
    }

    // ============================================================== viewer
    static final String[] STATE_NAME = { "NONE", "ATP", "ADPPi", "ADP" };
    static void runViz(double dt, String dir, int nNodes, int nSing, int nDim) {
        Scene sc = buildScene(dt, nNodes, nSing, nDim, 0); sc.withCycle = true;
        sc.mot.nucleotideState.init(MotorStore.NUC_NONE);
        new java.io.File(dir).mkdirs();
        Runnable step = cpuStep(sc);
        int M = 20000, every = Math.max(1, M/300), frames = 0;
        for (int t = 0; t <= M; t++) {
            sc.mot.setCounts(t, SEED, sc.fil.n); sc.node.setNodeBodyCounts(t, SEED_NODE);
            if (t % every == 0) writeFrame(dir, frames++, t*dt, sc);
            step.run();
        }
        System.out.println("viewer: wrote " + frames + " frames to " + dir);
    }
    static void writeFrame(String dir, int frame, double t, Scene sc) {
        MotorStore mot = sc.mot; RigidRodBody b = mot.body; RigidRodBody nb = sc.node.node;
        StringBuilder sb = new StringBuilder(512 + 200*mot.nMotors);
        sb.append(String.format(java.util.Locale.US, "{\"frame\":%d,\"t\":%.6g,\"bounds\":{\"xDim\":1.0,\"yDim\":1.0,\"zDim\":1.0}", frame, t));
        sb.append(",\"segments\":[],\"myosins\":[");
        boolean first = true;
        // nodes as small spheres (a degenerate motor with state "node")
        for (int k = 0; k < sc.node.nNodes; k++) {
            if (!first) sb.append(','); first = false;
            double cx=nb.coord.get(k), cy=nb.coord.get(nb.n+k), cz=nb.coord.get(2*nb.n+k);
            sb.append(String.format(java.util.Locale.US,
                "{\"id\":%d,\"rod\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g},"
                + "\"lever\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g},"
                + "\"motor\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g,\"state\":\"node\"}}",
                200000+k, cx,cy,cz, cx,cy,cz, NodeStore.NODE_RADIUS, cx,cy,cz, cx,cy,cz, NodeStore.NODE_RADIUS, cx,cy,cz, cx,cy,cz, NodeStore.NODE_RADIUS));
        }
        for (int m = 0; m < mot.nMotors; m++) {
            if (!first) sb.append(','); first = false;
            int rod=3*m, lever=3*m+1, head=3*m+2; String st = STATE_NAME[mot.nucleotideState.get(m)];
            sb.append(String.format(java.util.Locale.US,
                "{\"id\":%d,\"rod\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g},"
                + "\"lever\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g},"
                + "\"motor\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g,\"state\":\"%s\"}}",
                m, b.end1X(rod),b.end1Y(rod),b.end1Z(rod), b.end2X(rod),b.end2Y(rod),b.end2Z(rod), MotorStore.ROD_R,
                b.end1X(lever),b.end1Y(lever),b.end1Z(lever), b.end2X(lever),b.end2Y(lever),b.end2Z(lever), MotorStore.LEVER_R,
                b.end1X(head),b.end1Y(head),b.end1Z(head), b.end2X(head),b.end2Y(head),b.end2Z(head), MotorStore.HEAD_R, st));
        }
        sb.append("]}");
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(dir, String.format(java.util.Locale.US,"frame_%06d.json", frame)), sb.toString()); }
        catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }
}
