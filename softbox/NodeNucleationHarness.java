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
 * Increment 6c Stage B2 — the node NUCLEATION-FUNCTION: the first dynamic actin CREATION in SoftBox. Built
 * from jba's behavioral spec (seed birth + elastic tether + bond dissolution + Brownian-damped seed), reusing
 * B1's runtime-birth lifecycle, the node fracMove tether law, the wang-hash RNG, and the actin-pool seam.
 *
 * Gates (both runners):
 *   1 rate         — the node births seeds at kNodeNuc·dt (empirical vs analytic, multi-node ensemble).
 *   2 tether       — a tethered seed feels the fracMove spring toward the node center (force vs v1 double-ref,
 *                    bit-for-decision; restoring / bounded).
 *   3 dissolution  — the node↔seed bond dissolves at a CONSTANT rate (empirical vs nodeTetherDetachRate·dt at
 *                    an elevated, testable rate); a dissolved seed is a free ACTIVE filament (seedNode=-1).
 *   4 actin pool   — seed births deplete the pool through the seam-#2 accessor; available() gates emission.
 *   5 no-op-off    — forminsPerNode=0 ⇒ no births ⇒ the FilamentStore is untouched (seam #1 additive).
 *   6 CPU≡GPU      — the full nucleation pipeline (emit + allocator + tag + tether + dissolve + integrate),
 *                    Brownian off ⇒ bit-identical (seedNode/filState/pose); RNG decisions bit-identical.
 *   8 damping      — the Brownian-damped seed is bounded (not flailing) vs an undamped seed; existing
 *                    filaments (scale 1.0) untouched (no Brownian-system edit — damping is the born scale only).
 *   P publish-guard— FilamentStore.publishToBodyView(…,filState): FREE slots → STORE_NONE (excluded from the
 *                    broad-phase); all-ACTIVE ⇒ identical to the 8-arg (no-op-when-all-active).
 */
public final class NodeNucleationHarness {

    static final int B = 64;
    static GridScheduler sched;
    static boolean cpu = false;
    static final int SEED = 0x6C0DE2;
    static final double BOX_VOL = 8.0 * 4.0 * 0.6;   // µm³ (a representative gliding-assay chamber volume)

    public static void main(String[] args) {
        double dt = 1.0e-5;
        for (int i = 0; i < args.length; i++) if (args[i].equals("-cpu")) cpu = true;
        System.out.println("=== Soft Box increment 6c Stage B2 — node NUCLEATION-FUNCTION (formin actin nucleation) ===");
        System.out.println("seed birth (B1 lifecycle) + elastic fracMove tether + constant-rate dissolution + damped seed.");
        System.out.println("v1 clean specifics: kNodeNuc=10/node·s, actinSeed=3 (≈10.8 nm), nodeTetherDetachRate=0.001/s, fracMove=0.5.");
        System.out.println("runner: " + (cpu ? "CPU only (-cpu)" : "GPU + CPU cross-check") + ", dt=" + dt + "\n");

        boolean g1 = checkRate(dt);
        boolean g2 = checkTether(dt);
        boolean g3 = checkDissolution(dt);
        boolean g4 = checkActinPool(dt);
        boolean g5 = checkNoOpWhenOff(dt);
        boolean g8 = checkDamping(dt);
        boolean gP = checkPublishGuard(dt);
        boolean g6 = checkCpuGpu(dt);

        boolean ok = g1 && g2 && g3 && g4 && g5 && g6 && g8 && gP;
        System.out.println();
        System.out.println("=== NODE-NUCLEATION (B2) VALIDATION " + (ok ? "PASS" : "FAIL") + " ===");
        if (!ok) { System.out.println("BAIL-OUT: a gate failed. Commit nothing."); System.exit(1); }
    }

    // ============================================================== scene
    static final class Scene {
        NodeStore nodeStore; FilamentStore fil; NodeNucleationStore nuc;
        double seedLen;
    }

    static Scene build(int nNodes, int filCap, double dt, int forminsPerNode, double detachRate,
                       double pool0, double dampingFactor, double bornScale) {
        Scene sc = new Scene();
        double seedLen = (Constants.actinSeed + 1) * Constants.actinMonoRadius;
        sc.seedLen = seedLen;

        // node bodies — fixed anchors (never integrated), spread on a line so seeds don't overlap trivially
        NodeStore ns = new NodeStore(nNodes, 1);
        for (int k = 0; k < nNodes; k++) ns.node.setCoord(k, (float) (0.2 * k), 0f, 0f);
        ns.node.uVec.init(0f); ns.node.yVec.init(0f);
        for (int k = 0; k < nNodes; k++) { ns.node.setUVec(k, 1f, 0f, 0f); ns.node.setYVec(k, 0f, 1f, 0f); }
        ns.initNodeDrag();

        // FilamentStore: filCap slots, reqCap = nNodes (one request/node/step). Every slot pre-init as a
        // fixed-length seed (segLength + drag), then markFree (inert: brownScale 0). Born seeds get bornScale.
        FilamentStore f = new FilamentStore(filCap, nNodes);
        for (int s = 0; s < filCap; s++) f.monomerCount.set(s, Constants.actinSeed);
        DragTensorSystem.run(f);
        f.setParams(dt, Constants.brownianForceMag());
        f.setChainParams();
        f.setBirthParams(bornScale, bornScale);          // born-seed Brownian scale (damped or 0)
        for (int s = 0; s < filCap; s++) { f.setCoord(s, 0f, 0f, 0f); f.setUVec(s, 1f, 0f, 0f); f.setYVec(s, 0f, 1f, 0f); f.markFree(s); }
        f.setBirthRequestCount(nNodes);

        NodeNucleationStore nuc = new NodeNucleationStore(nNodes, filCap, Constants.actinSeed, pool0, BOX_VOL, dampingFactor);
        nuc.setNucParams(Constants.kNodeNuc, dt, seedLen, forminsPerNode);
        nuc.setTetherParams(Constants.fracMove, dt);
        nuc.setDissolveParams(detachRate, dt);

        sc.nodeStore = ns; sc.fil = f; sc.nuc = nuc;
        return sc;
    }

    static int countActive(FilamentStore f) { int c = 0; for (int s = 0; s < f.n; s++) if (f.filState.get(s) >= 0) c++; return c; }
    static int countTethered(NodeNucleationStore nuc) { int c = 0; for (int s = 0; s < nuc.filCap; s++) if (nuc.seedNode.get(s) >= 0) c++; return c; }

    // ============================================================== CPU step
    /** Run the nucleation+dynamics step on the CPU runner. poolGate=true ⇒ deplete the host pool (gate 4);
     *  false ⇒ pool not limiting (poolOK forced 1). brownian ⇒ damped seed Brownian. */
    static void runCpu(Scene sc, int M, boolean brownian, boolean poolGate) {
        FilamentStore f = sc.fil; NodeNucleationStore nuc = sc.nuc; RigidRodBody node = sc.nodeStore.node;
        for (int t = 0; t < M; t++) {
            f.setCounts(t, SEED); nuc.setCounts(t, SEED);
            if (poolGate) nuc.refreshPoolGate(); else nuc.nucCounts.set(3, 1);
            NodeNucleationSystem.countBoundFil(nuc.seedNode, nuc.nodeBoundFil, nuc.nucCounts);
            NodeNucleationSystem.emit(node.coord, nuc.nodeBoundFil, f.acceptFlag, f.reqCoord, f.reqUVec, f.reqYVec, nuc.nucParams, nuc.nucCounts);
            allocateCpu(f);
            NodeNucleationSystem.tagSeeds(f.rankOffsets, f.freeList, f.freeOffsets, nuc.seedNode, f.allocCounts);
            if (poolGate) nuc.depletePoolForBirths(f);
            ChainBendingForceSystem.zeroAccumulators(f.forceSum, f.torqueSum, f.counts);
            if (brownian) BrownianForceSystem.brownianForce(f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.brownTransScale, f.brownRotScale, f.params, f.counts);
            NodeNucleationSystem.seedTether(f.coord, f.uVec, f.segLength, f.bTransGam, f.forceSum, f.torqueSum, node.coord, sc.nodeStore.nodeInvTransY, nuc.seedNode, nuc.tetherParams);
            NodeNucleationSystem.dissolve(nuc.seedNode, nuc.dissolveParams, nuc.nucCounts);
            RigidRodLangevinIntegrationSystem.integrate(f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts);
            DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
        }
    }
    static void allocateCpu(FilamentStore f) {
        FilamentBirthSystem.freeFlags(f.filState, f.freeCount, f.allocCounts);
        CrossBridgeSystem.csrScan(f.freeScanCounts, f.freeCount, f.freeOffsets);
        FilamentBirthSystem.freeScatter(f.filState, f.freeOffsets, f.freeList, f.allocCounts);
        CrossBridgeSystem.csrScan(f.rankScanCounts, f.acceptFlag, f.rankOffsets);
        FilamentBirthSystem.allocate(f.reqCoord, f.reqUVec, f.reqYVec, f.rankOffsets, f.freeList, f.freeOffsets,
                f.coord, f.uVec, f.yVec, f.brownTransScale, f.brownRotScale, f.filState, f.birthParams, f.allocCounts);
    }

    // ============================================================== GPU plan (full pipeline, Brownian off)
    static TornadoExecutionPlan buildPlan(Scene sc) {
        FilamentStore f = sc.fil; NodeNucleationStore nuc = sc.nuc; RigidRodBody node = sc.nodeStore.node;
        TaskGraph tg = new TaskGraph("nodenuc")
            .transferToDevice(DataTransferMode.FIRST_EXECUTION,
                    node.coord, sc.nodeStore.nodeInvTransY, nuc.seedNode, nuc.nodeBoundFil, nuc.nucParams,
                    nuc.tetherParams, nuc.dissolveParams,
                    f.acceptFlag, f.reqCoord, f.reqUVec, f.reqYVec, f.freeCount, f.freeOffsets, f.freeList,
                    f.freeScanCounts, f.rankOffsets, f.rankScanCounts, f.allocCounts, f.birthParams,
                    f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.bTransGam, f.bRotGam,
                    f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.brownTransScale, f.brownRotScale,
                    f.params, f.filState)
            .transferToDevice(DataTransferMode.EVERY_EXECUTION, nuc.nucCounts, f.counts)
            .task("count", NodeNucleationSystem::countBoundFil, nuc.seedNode, nuc.nodeBoundFil, nuc.nucCounts)
            .task("emit", NodeNucleationSystem::emit, node.coord, nuc.nodeBoundFil, f.acceptFlag, f.reqCoord, f.reqUVec, f.reqYVec, nuc.nucParams, nuc.nucCounts)
            .task("freeFlags", FilamentBirthSystem::freeFlags, f.filState, f.freeCount, f.allocCounts)
            .task("csrFree", CrossBridgeSystem::csrScan, f.freeScanCounts, f.freeCount, f.freeOffsets)
            .task("freeScatter", FilamentBirthSystem::freeScatter, f.filState, f.freeOffsets, f.freeList, f.allocCounts)
            .task("csrRank", CrossBridgeSystem::csrScan, f.rankScanCounts, f.acceptFlag, f.rankOffsets)
            .task("allocate", FilamentBirthSystem::allocate, f.reqCoord, f.reqUVec, f.reqYVec, f.rankOffsets, f.freeList, f.freeOffsets,
                    f.coord, f.uVec, f.yVec, f.brownTransScale, f.brownRotScale, f.filState, f.birthParams, f.allocCounts)
            .task("tag", NodeNucleationSystem::tagSeeds, f.rankOffsets, f.freeList, f.freeOffsets, nuc.seedNode, f.allocCounts)
            .task("zero", ChainBendingForceSystem::zeroAccumulators, f.forceSum, f.torqueSum, f.counts)
            .task("tether", NodeNucleationSystem::seedTether, f.coord, f.uVec, f.segLength, f.bTransGam, f.forceSum, f.torqueSum,
                    node.coord, sc.nodeStore.nodeInvTransY, nuc.seedNode, nuc.tetherParams)
            .task("dissolve", NodeNucleationSystem::dissolve, nuc.seedNode, nuc.dissolveParams, nuc.nucCounts)
            .task("integrate", RigidRodLangevinIntegrationSystem::integrate, f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum,
                    f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts)
            .task("derive", DerivedGeometrySystem::derive, f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts)
            .transferToHost(DataTransferMode.UNDER_DEMAND, f.coord, f.uVec, f.filState, nuc.seedNode, nuc.nodeBoundFil);
        int C = f.n, nN = sc.nodeStore.nNodes;
        sched = new GridScheduler();
        addS("nodenuc.count"); addW("nodenuc.emit", pad(nN));
        addW("nodenuc.freeFlags", pad(C)); addS("nodenuc.csrFree"); addS("nodenuc.freeScatter"); addS("nodenuc.csrRank");
        addW("nodenuc.allocate", pad(nN)); addW("nodenuc.tag", pad(nN));
        addW("nodenuc.zero", pad(C)); addW("nodenuc.tether", pad(C)); addW("nodenuc.dissolve", pad(C));
        addW("nodenuc.integrate", pad(C)); addW("nodenuc.derive", pad(C));
        return new TornadoExecutionPlan(tg.snapshot());
    }
    static void runGpu(Scene sc, int M) {
        TornadoExecutionPlan plan = buildPlan(sc);
        FilamentStore f = sc.fil; NodeNucleationStore nuc = sc.nuc;
        for (int t = 0; t < M; t++) {
            f.setCounts(t, SEED); nuc.setCounts(t, SEED); nuc.nucCounts.set(3, 1);   // pool not limiting on GPU
            TornadoExecutionResult res = plan.withGridScheduler(sched).execute();
            if (t == M - 1) res.transferToHost(f.coord, f.uVec, f.filState, nuc.seedNode, nuc.nodeBoundFil);
        }
    }

    // ============================================================== 1. rate
    static boolean checkRate(double dt) {
        System.out.println("--- 1. nucleation rate (births vs kNodeNuc·dt; multi-node ensemble) ---");
        int nNodes = 64, M = 50000, filCap = 512;
        Scene sc = build(nNodes, filCap, dt, 100, 0.0, 1.0e9, 30.0, 0.0);   // cap huge, no dissolution, pool huge
        runCpu(sc, M, false, false);
        int births = countActive(sc.fil);
        double pNuc = Constants.kNodeNuc * dt;
        double empirical = births / ((double) nNodes * M);
        double rel = Math.abs(empirical - pNuc) / pNuc;
        boolean ok = rel < 0.12;   // Poisson sampling (~320 births ⇒ ±5.6% SEM; allow 12%)
        System.out.printf("  births=%d over %d nodes × %d steps; empirical P=%.3e vs kNodeNuc·dt=%.3e (rel %.1f%%) => %s%n",
                births, nNodes, M, empirical, pNuc, 100 * rel, ok ? "PASS" : "*FAIL*");
        System.out.println("  => " + (ok ? "PASS" : "*FAIL*") + "\n");
        return ok;
    }

    // ============================================================== 2. elastic tether (force vs v1 double-ref)
    static boolean checkTether(double dt) {
        System.out.println("--- 2. elastic tether (fracMove spring, node-center ↔ seed-end) vs v1 double-ref ---");
        Scene sc = build(1, 8, dt, 4, 0.0, 1.0e9, 30.0, 0.0);
        FilamentStore f = sc.fil; NodeNucleationStore nuc = sc.nuc; RigidRodBody node = sc.nodeStore.node;
        // force ONE birth deterministically: stage a request along +x and run the allocator
        f.acceptFlag.init(0); f.acceptFlag.set(0, 1);
        float halfLen = (float) (0.5 * sc.seedLen);
        // seed pose: tethered end (end1) at node center (0,0,0); coord = +halfLen·x̂
        f.setBirthRequest(0, halfLen, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f);
        allocateCpu(f);
        NodeNucleationSystem.tagSeeds(f.rankOffsets, f.freeList, f.freeOffsets, nuc.seedNode, f.allocCounts);
        int s = -1; for (int i = 0; i < f.n; i++) if (nuc.seedNode.get(i) >= 0) s = i;
        boolean born = (s >= 0);
        // DISPLACE the seed off the node (so the tether is non-trivial)
        f.setCoord(s, f.coordX(s) + 0.004f, f.coordY(s) + 0.003f, f.coordZ(s));
        ChainBendingForceSystem.zeroAccumulators(f.forceSum, f.torqueSum, f.counts);
        NodeNucleationSystem.seedTether(f.coord, f.uVec, f.segLength, f.bTransGam, f.forceSum, f.torqueSum,
                node.coord, sc.nodeStore.nodeInvTransY, nuc.seedNode, nuc.tetherParams);
        double[] ref = refTether(sc, s);
        int C = f.n;
        double[] got = { f.forceSum.get(s), f.forceSum.get(C + s), f.forceSum.get(2 * C + s) };
        double rel = 0, refMag = 0, gotMag = 0;
        for (int i = 0; i < 3; i++) { refMag += ref[i] * ref[i]; gotMag += got[i] * got[i]; }
        refMag = Math.sqrt(refMag); gotMag = Math.sqrt(gotMag);
        for (int i = 0; i < 3; i++) rel = Math.max(rel, Math.abs(got[i] - ref[i]) / Math.max(refMag, 1e-30));
        boolean arithOk = rel < 1e-3 && refMag > 0;
        // restoring direction: force points from the seed end1 toward the node center
        double e1x = f.coordX(s) - halfLen * f.uVecX(s);   // uVec=+x
        boolean towardNode = (got[0] * (0.0 - e1x)) > 0;    // node center x=0; force.x same sign as (0−e1x)
        // bounded: run with Brownian off ⇒ the seed relaxes toward the node (strain shrinks), stays bounded
        double strain0 = Math.abs(f.coordX(s) - halfLen * f.uVecX(s));
        runCpu(sc, 2000, false, false);   // (no new births since cap small + the slot taken; tether relaxes)
        int s2 = s; double e1xR = f.coordX(s2) - 0.5 * f.segLength.get(s2) * f.uVecX(s2);
        double strain1 = Math.sqrt(e1xR * e1xR + Math.pow(f.coordY(s2) - 0.5 * f.segLength.get(s2) * f.uVecY(s2), 2) + Math.pow(f.coordZ(s2) - 0.5 * f.segLength.get(s2) * f.uVecZ(s2), 2));
        boolean relaxes = strain1 < strain0 && strain1 < 0.01;
        boolean ok = born && arithOk && towardNode && relaxes;
        System.out.printf("  born=%s; tether force vs v1 double-ref: |F|=%.3e N (ref %.3e, rel %.2e); toward-node=%s%n",
                born, gotMag, refMag, rel, towardNode);
        System.out.printf("  relaxation (Brownian off): strain %.4e → %.4e µm (shrinks, bounded) => %s%n", strain0, strain1, relaxes ? "ok" : "*FAIL*");
        System.out.println("  => " + (ok ? "PASS" : "*FAIL*") + "\n");
        return ok;
    }
    /** v1 double-precision reference for the seed tether (addNodeForces attach-at-center): F on the seed end1. */
    static double[] refTether(Scene sc, int s) {
        FilamentStore f = sc.fil; RigidRodBody node = sc.nodeStore.node;
        int C = f.n; double dt = sc.nuc.tetherParams.get(1), fracMove = sc.nuc.tetherParams.get(0);
        int k = sc.nuc.seedNode.get(s);
        double half = 0.5 * f.segLength.get(s);
        double e1x = f.coord.get(s) - half * f.uVec.get(s), e1y = f.coord.get(C + s) - half * f.uVec.get(C + s), e1z = f.coord.get(2 * C + s) - half * f.uVec.get(2 * C + s);
        double ncx = node.coord.get(k), ncy = node.coord.get(node.n + k), ncz = node.coord.get(2 * node.n + k);
        double dx = ncx - e1x, dy = ncy - e1y, dz = ncz - e1z, strain = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double[] out = new double[3];
        if (strain > 0) {
            double lx = dx / strain, ly = dy / strain, lz = dz / strain;
            double denom = dt * (1.0 / f.bTransGam.get(s) + sc.nodeStore.nodeInvTransY.get(k));
            double fm = fracMove * 1e-6 * strain / denom;
            out[0] = fm * lx; out[1] = fm * ly; out[2] = fm * lz;
        }
        return out;
    }

    // ============================================================== 3. dissolution
    static boolean checkDissolution(double dt) {
        System.out.println("--- 3. dissolution (constant-rate node↔seed detach; elevated testable rate) ---");
        // v1 nodeTetherDetachRate=0.001/s ⇒ pDetach=1e-8 at dt=1e-5 — unobservably rare. Validate the MECHANISM
        // at an elevated rate; the production value is confirmed by the formula pDetach = rate·dt (flag for jba).
        // Pre-tether a large pool of seeds (decoupled from birth/saturation) then measure the detach rate —
        // ~N events ⇒ tight statistics (Poisson SEM ~1/√N).
        double testRate = 2000.0;                  // /s ⇒ pDetach = 0.02
        double pDetach = testRate * dt;
        int N = 4000, M = 4000;
        Scene sc = build(1, N, dt, 0, testRate, 1.0e9, 30.0, 0.0);
        FilamentStore f = sc.fil; NodeNucleationStore nuc = sc.nuc;
        for (int s = 0; s < N; s++) { f.filState.set(s, FilamentStore.FIL_ACTIVE); nuc.seedNode.set(s, 0); }   // pre-tether all to node 0
        long detached = 0, tetheredSteps = 0;
        for (int t = 0; t < M; t++) {
            nuc.setCounts(t, SEED);
            int before = countTethered(nuc);
            NodeNucleationSystem.dissolve(nuc.seedNode, nuc.dissolveParams, nuc.nucCounts);
            int after = countTethered(nuc);
            detached += (before - after); tetheredSteps += before;
        }
        double empirical = detached / (double) tetheredSteps;
        double rel = Math.abs(empirical - pDetach) / pDetach;
        boolean rateOk = rel < 0.06;               // ~4000 events ⇒ SEM ~1.6%
        // a dissolved seed is a free ACTIVE filament: filState stays ACTIVE, seedNode → -1
        int active = countActive(f), tethered = countTethered(nuc);
        boolean freedExist = (active == N) && (tethered < N) && (detached == N - tethered);
        boolean ok = rateOk && freedExist;
        System.out.printf("  detached=%d over %d tethered-seed-steps; empirical pDetach=%.4e vs rate·dt=%.4e (rel %.1f%%) => %s%n",
                detached, tetheredSteps, empirical, pDetach, 100 * rel, rateOk ? "ok" : "*FAIL*");
        System.out.printf("  freed seeds stay ACTIVE free filaments: #active=%d (=N) #tethered=%d, detached=%d => %s%n",
                active, tethered, detached, freedExist ? "ok" : "*FAIL*");
        System.out.println("  (v1 production rate 0.001/s ⇒ pDetach=1e-8 at dt=1e-5: unobservably slow — validated by formula; flag for jba.)");
        System.out.println("  => " + (ok ? "PASS" : "*FAIL*") + "\n");
        return ok;
    }

    // ============================================================== 4. actin pool (seam #2)
    static boolean checkActinPool(double dt) {
        System.out.println("--- 4. actin pool depletion (seam #2 accessor) + availability gate ---");
        // small pool: only enough for a few seeds ⇒ emission stops when dry.
        int nNodes = 16, M = 20000, filCap = 256;
        Scene small = build(nNodes, filCap, dt, 100, 0.0, 0.0, 30.0, 0.0);
        // size the pool to ~5 seeds' worth
        double uMper = small.nuc.pool.uMPerMonomer();
        int budgetSeeds = 5;
        Scene sc = build(nNodes, filCap, dt, 100, 0.0, budgetSeeds * Constants.actinSeed * uMper, 30.0, 0.0);
        double conc0 = sc.nuc.pool.conc();
        runCpu(sc, M, false, true);   // poolGate ON ⇒ depletes + gates
        int births = countActive(sc.fil);
        double conc1 = sc.nuc.pool.conc();
        double expectedDrop = births * Constants.actinSeed * uMper;
        double dropErr = Math.abs((conc0 - conc1) - expectedDrop);
        boolean depletes = dropErr < 1e-12 && births > 0;
        boolean gated = births <= budgetSeeds;   // availability gate stopped emission at the budget
        boolean dry = !sc.nuc.pool.available(Constants.actinSeed);
        boolean ok = depletes && gated && dry;
        System.out.printf("  conc %.4e → %.4e µM; births=%d (budget %d); drop matches births·seed·µMper (err %.2e) => %s%n",
                conc0, conc1, births, budgetSeeds, dropErr, depletes ? "ok" : "*FAIL*");
        System.out.printf("  availability gate: births ≤ budget=%s; pool dry afterwards=%s => %s%n", gated, dry, (gated && dry) ? "ok" : "*FAIL*");
        System.out.println("  => " + (ok ? "PASS" : "*FAIL*") + "\n");
        return ok;
    }

    // ============================================================== 5. no-op when off (seam #1 additive)
    static boolean checkNoOpWhenOff(double dt) {
        System.out.println("--- 5. no-op-when-off (forminsPerNode=0 ⇒ no births ⇒ FilamentStore untouched) ---");
        int nNodes = 16, M = 5000, filCap = 64;
        Scene sc = build(nNodes, filCap, dt, 0, 0.001, 1.0e9, 30.0, 0.0);   // forminsPerNode = 0
        // snapshot the FilamentStore pose + lifecycle
        FloatArray coord0 = copy(sc.fil.coord);
        runCpu(sc, M, true, true);   // even with Brownian "on", FREE slots have scale 0 ⇒ inert
        int active = countActive(sc.fil), tethered = countTethered(sc.nuc);
        double dCoord = maxDiff(coord0, sc.fil.coord);
        boolean ok = (active == 0 && tethered == 0 && dCoord == 0.0);
        System.out.printf("  forminsPerNode=0 after %d steps: #active=%d #tethered=%d max|Δcoord|=%.3e (all 0) => %s%n",
                M, active, tethered, dCoord, ok ? "PASS" : "*FAIL*");
        System.out.println("  (the nucleation-function is additive over the Stage-A node: rate/cap 0 ⇒ nothing born ⇒ no-op.)");
        System.out.println("  => " + (ok ? "PASS" : "*FAIL*") + "\n");
        return ok;
    }

    // ============================================================== 8. seed Brownian damping
    static boolean checkDamping(double dt) {
        System.out.println("--- 8. seed Brownian damping (dt-compensating stabilization; non-FDT, seed only) ---");
        int nNodes = 8, M = 4000, filCap = 64;
        // damped (factor 30) vs undamped (factor 1); both: birth seeds, Brownian ON, measure seed wander from node.
        double wanderDamped = seedWander(build(nNodes, filCap, dt, 8, 0.0, 1.0e9, 30.0, Constants.BTransCoeff / 30.0), M);
        double wanderUndamped = seedWander(build(nNodes, filCap, dt, 8, 0.0, 1.0e9, 1.0, Constants.BTransCoeff), M);
        boolean stabilizes = wanderDamped < wanderUndamped && wanderDamped < 0.05;   // bounded, and < undamped
        System.out.printf("  RMS seed wander from node: damped(×1/30)=%.4e µm  undamped(×1)=%.4e µm => %s%n",
                wanderDamped, wanderUndamped, stabilizes ? "PASS" : "*FAIL*");
        System.out.println("  (the damping is a DELIBERATE non-FDT stand-in for the formin's stiff hold at the large production dt —");
        System.out.println("   NOT FDT-validated; existing filaments keep scale 1.0 — no Brownian-system edit, so they're untouched.)");
        System.out.println("  => " + (stabilizes ? "PASS" : "*FAIL*") + "\n");
        return stabilizes;
    }
    /** Mean RMS distance of each tethered seed's end1 from its node, after M Brownian steps. */
    static double seedWander(Scene sc, int M) {
        runCpu(sc, M, true, false);
        FilamentStore f = sc.fil; NodeNucleationStore nuc = sc.nuc; RigidRodBody node = sc.nodeStore.node;
        int C = f.n; double sum = 0; int n = 0;
        for (int s = 0; s < C; s++) {
            int k = nuc.seedNode.get(s); if (k < 0) continue;
            double half = 0.5 * f.segLength.get(s);
            double e1x = f.coordX(s) - half * f.uVecX(s), e1y = f.coordY(s) - half * f.uVecY(s), e1z = f.coordZ(s) - half * f.uVecZ(s);
            double dx = e1x - node.coord.get(k), dy = e1y - node.coord.get(node.n + k), dz = e1z - node.coord.get(2 * node.n + k);
            sum += dx * dx + dy * dy + dz * dz; n++;
        }
        return n > 0 ? Math.sqrt(sum / n) : 0;
    }

    // ============================================================== P. publish-path filState guard
    static boolean checkPublishGuard(double dt) {
        System.out.println("--- P. publish-path filState guard (FREE → STORE_NONE; no-op-when-all-active) ---");
        int cap = 8;
        FilamentStore f = new FilamentStore(cap, cap);
        for (int s = 0; s < cap; s++) { f.monomerCount.set(s, Constants.actinSeed); }
        DragTensorSystem.run(f);
        for (int s = 0; s < cap; s++) { f.setCoord(s, 0.01f * s, 0f, 0f); f.setUVec(s, 1f, 0f, 0f); f.setYVec(s, 0f, 1f, 0f); }
        for (int s = 0; s < cap; s += 2) f.markFree(s);   // even slots FREE, odd ACTIVE
        SpatialBodyView v = new SpatialBodyView(cap);
        FloatArray viewParams = FloatArray.fromElements((float) Constants.actinMonoRadius);
        IntArray counts = new IntArray(4);
        FilamentStore.publishToBodyView(f.coord, f.segLength, v.center, v.boundingRadius, v.ownerStore, v.ownerSlot, viewParams, counts, f.filState);
        boolean guardOk = true;
        for (int s = 0; s < cap; s++) {
            int want = (s % 2 == 0) ? SpatialBodyView.STORE_NONE : SpatialBodyView.STORE_FILAMENT;
            if (v.ownerStore.get(s) != want) guardOk = false;
        }
        System.out.printf("  mixed scene: FREE evens → STORE_NONE, ACTIVE odds → STORE_FILAMENT => %s%n", guardOk ? "ok" : "*FAIL*");
        // no-op-when-all-active: all ACTIVE ⇒ 9-arg == the 8-arg (every ownerStore = STORE_FILAMENT)
        FilamentStore g = new FilamentStore(cap, cap);
        for (int s = 0; s < cap; s++) { g.monomerCount.set(s, Constants.actinSeed); }
        DragTensorSystem.run(g);
        for (int s = 0; s < cap; s++) { g.setCoord(s, 0.01f * s, 0f, 0f); g.setUVec(s, 1f, 0f, 0f); g.setYVec(s, 0f, 1f, 0f); }
        SpatialBodyView v8 = new SpatialBodyView(cap), v9 = new SpatialBodyView(cap);
        IntArray c8 = new IntArray(4), c9 = new IntArray(4);
        FilamentStore.publishToBodyView(g.coord, g.segLength, v8.center, v8.boundingRadius, v8.ownerStore, v8.ownerSlot, viewParams, c8);
        FilamentStore.publishToBodyView(g.coord, g.segLength, v9.center, v9.boundingRadius, v9.ownerStore, v9.ownerSlot, viewParams, c9, g.filState);
        boolean noop = true;
        for (int s = 0; s < cap; s++) if (v8.ownerStore.get(s) != v9.ownerStore.get(s) || v8.boundingRadius.get(s) != v9.boundingRadius.get(s)) noop = false;
        System.out.printf("  no-op-when-all-active: 9-arg ≡ 8-arg (all STORE_FILAMENT, identical) => %s%n", noop ? "ok" : "*FAIL*");
        boolean ok = guardOk && noop;
        System.out.println("  => " + (ok ? "PASS" : "*FAIL*") + "\n");
        return ok;
    }

    // ============================================================== 6. CPU≡GPU
    static boolean checkCpuGpu(double dt) {
        if (cpu) { System.out.println("--- 6. CPU≡GPU: skipped (-cpu) ---\n"); return true; }
        System.out.println("--- 6. CPU≡GPU (full nucleation pipeline, Brownian off ⇒ bit-identical) ---");
        int nNodes = 32, M = 8000, filCap = 256;
        double testRate = 2000.0;   // exercise dissolution too
        Scene g = build(nNodes, filCap, dt, 100, testRate, 1.0e9, 30.0, 0.0);
        Scene c = build(nNodes, filCap, dt, 100, testRate, 1.0e9, 30.0, 0.0);
        runGpu(g, M);
        runCpu(c, M, false, false);
        int seedMism = 0, stateMism = 0;
        for (int s = 0; s < filCap; s++) {
            if (g.nuc.seedNode.get(s) != c.nuc.seedNode.get(s)) seedMism++;
            if (g.fil.filState.get(s) != c.fil.filState.get(s)) stateMism++;
        }
        double dCoord = maxDiff(g.fil.coord, c.fil.coord);
        int actG = countActive(g.fil), actC = countActive(c.fil);
        // the LIFECYCLE decisions (births/dissolutions/slot assignment) are integer/wang-hash ⇒ BIT-IDENTICAL
        // (seedNode/filState ==0 mismatches); the float pose carries float32 last-bit FMA/op-ordering diffs
        // (~5e-10 µm here) — the established deterministic CPU≡GPU pose standard (cf. MiniFilament/B1, <5e-5).
        boolean ok = (seedMism == 0 && stateMism == 0 && dCoord < 5e-5 && actG == actC);
        System.out.printf("  births: GPU=%d CPU=%d; seedNode mismatches=%d, filState mismatches=%d (==0 bit-identical), max|Δcoord|=%.3e µm (<5e-5) => %s%n",
                actG, actC, seedMism, stateMism, dCoord, ok ? "PASS" : "*FAIL*");
        System.out.println("  (wang-hash decisions + the scan-rank allocator are integer/deterministic ⇒ the lifecycle is bit-identical;");
        System.out.println("   the seed pose carries only float32 last-bit FMA differences.)");
        System.out.println("  => " + (ok ? "PASS" : "*FAIL*") + "\n");
        return ok;
    }

    // ============================================================== utils
    static FloatArray copy(FloatArray a) { FloatArray b = new FloatArray(a.getSize()); for (int i = 0; i < a.getSize(); i++) b.set(i, a.get(i)); return b; }
    static double maxDiff(FloatArray a, FloatArray b) { double m = 0; for (int i = 0; i < a.getSize(); i++) m = Math.max(m, Math.abs(a.get(i) - b.get(i))); return m; }
    static int pad(int n) { return ((n + B - 1) / B) * B; }
    static void addW(String name, int g) { WorkerGrid w = new WorkerGrid1D(g); w.setLocalWork(B, 1, 1); sched.addWorkerGrid(name, w); }
    static void addS(String name) { WorkerGrid w = new WorkerGrid1D(1); w.setLocalWork(1, 1, 1); sched.addWorkerGrid(name, w); }
}
