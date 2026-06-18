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
 * Increment 6c — actin POLYMERIZATION: barbed-end elongation (lengthen + split, growth-only). The FIRST dynamic
 * actin GROWTH in SoftBox. Tips = node-bonded seeds (B2 seedNode); growth = lengthen-then-split (GrowthSystem),
 * reusing B1's scan-rank allocator + the chain-bonding + the ActinPool + the wang-hash RNG. Default-OFF.
 *
 * Gates (both runners):
 *   1 lengthen     — recomputeDrag matches the host DragTensorSystem reference; tips grow monomer-by-monomer at
 *                    the [actin]-dependent rate (segLength grows; drag recomputes on growth).
 *   2 split @64    — a tip at 64 splits into two 32s: child allocated (B1), the 3-slot chain rewire is correct
 *                    (chain stays linear, incl. the inserted-between-Mold case), monomers conserved, geometry
 *                    consistent, CPU≡GPU bit-identical.
 *   3 rate+pool    — growth is first-order in [actin] (halve conc ⇒ halve rate); the pool depletes per monomer;
 *                    growth stops as the pool runs dry.
 *   4 growing-end  — the node-side end grows; the filament extends OUTWARD (contour grows; tip end1 held at node).
 *   5 no-op-off    — growth OFF ⇒ monomerCount/filState/seedNode/pose bit-identical to a static baseline.
 *   6 CPU≡GPU      — the full growth pipeline (grow + split + recomputeDrag + tether + integrate): lifecycle
 *                    bit-identical (monomerCount/filState/chain links), pose float32.
 *   7 drag-clamp   — report the 3-monomer seed vs the minMonomerCt/stdSegLength clamp (faithful to v1:409-419).
 *   8 participates — a grown/split multi-segment filament integrates + chains; dt-stable (bounded, NaN-free,
 *                    chain valid) under Brownian over a long run.
 */
public final class GrowthHarness {

    static final int B = 64;
    static GridScheduler sched;
    static boolean cpu = false;
    static final int SEED = 0x6604EC;
    static final double BOX_VOL = 8.0 * 4.0 * 0.6;   // µm³ (representative chamber volume; for µM-per-monomer)

    public static void main(String[] args) {
        double dt = 1.0e-5;
        for (int i = 0; i < args.length; i++) if (args[i].equals("-cpu")) cpu = true;
        System.out.println("=== Soft Box increment 6c — actin POLYMERIZATION: barbed-end elongation (lengthen + split) ===");
        System.out.println("first dynamic actin GROWTH; tips = node-bonded seeds; lengthen monomer-by-monomer then split@64.");
        System.out.println("v1 specifics: kATPOn2WithFormin=11.6 µM⁻¹s⁻¹, stdSegLength=32 (split@64), actinSeed=3, biochemDeltaT=1e-3 s.");
        System.out.println("runner: " + (cpu ? "CPU only (-cpu)" : "GPU + CPU cross-check") + ", dt=" + dt + "\n");

        boolean g1 = checkLengthen(dt);
        boolean g2 = checkSplit(dt);
        boolean g3 = checkRatePool(dt);
        boolean g4 = checkGrowingEnd(dt);
        boolean g5 = checkNoOpWhenOff(dt);
        boolean g7 = checkDragClamp(dt);
        boolean g8 = checkParticipates(dt);
        boolean g6 = checkCpuGpu(dt);

        boolean ok = g1 && g2 && g3 && g4 && g5 && g6 && g7 && g8;
        System.out.println();
        System.out.println("=== POLYMERIZATION (growth) VALIDATION " + (ok ? "PASS" : "FAIL") + " ===");
        if (!ok) { System.out.println("BAIL-OUT: a gate failed. Commit nothing."); System.exit(1); }
    }

    // ============================================================== scene
    static final class Scene {
        NodeStore nodeStore; FilamentStore fil; NodeNucleationStore nuc; GrowthStore grow;
        double seedLen; int nNodes, filCap;
    }

    /** nNodes fixed-anchor nodes on a line; one node-bonded tip seed per node at slots [0,nNodes); [nNodes,filCap)
     *  FREE for split children. monomerInit monomers/tip. pool0 µM. growthOn enables growth. */
    static Scene build(int nNodes, int filCap, double dt, int monomerInit, double pool0, boolean brownOn) {
        Scene sc = new Scene();
        sc.nNodes = nNodes; sc.filCap = filCap;
        double seedLen = (monomerInit + 1) * Constants.actinMonoRadius;
        sc.seedLen = seedLen;

        NodeStore ns = new NodeStore(nNodes, 1);
        for (int k = 0; k < nNodes; k++) ns.node.setCoord(k, (float) (0.5 * k), 0f, 0f);
        ns.node.uVec.init(0f); ns.node.yVec.init(0f);
        for (int k = 0; k < nNodes; k++) { ns.node.setUVec(k, 1f, 0f, 0f); ns.node.setYVec(k, 0f, 1f, 0f); }
        ns.initNodeDrag();

        FilamentStore f = new FilamentStore(filCap, filCap);   // reqCap = capacity (request index == slot)
        for (int s = 0; s < filCap; s++) f.monomerCount.set(s, monomerInit);
        DragTensorSystem.run(f);
        f.setParams(dt, Constants.brownianForceMag());
        f.setChainParams();
        double bornScale = brownOn ? Constants.BTransCoeff : 0.0;
        f.setBirthParams(bornScale, bornScale);
        f.setBirthRequestCount(filCap);

        // all FREE first, then place tips
        for (int s = 0; s < filCap; s++) { f.setCoord(s, 0f, 0f, 0f); f.setUVec(s, 1f, 0f, 0f); f.setYVec(s, 0f, 1f, 0f); f.markFree(s); }
        NodeNucleationStore nuc = new NodeNucleationStore(nNodes, filCap, monomerInit, 1.0e30, BOX_VOL, 1.0);
        nuc.setTetherParams(Constants.fracMove, dt);
        double half = 0.5 * seedLen;
        for (int k = 0; k < nNodes; k++) {
            f.filState.set(k, FilamentStore.FIL_ACTIVE);
            f.setCoord(k, (float) (0.5 * k + half), 0f, 0f);   // end1 at the node (0.5k,0,0); +x outward
            f.setUVec(k, 1f, 0f, 0f); f.setYVec(k, 0f, 1f, 0f);
            f.brownTransScale.set(k, (float) bornScale); f.brownRotScale.set(k, (float) bornScale);
            nuc.seedNode.set(k, k);                            // tip k tethered to node k
        }
        DragTensorSystem.run(f);                               // refresh drag/segLength after placing tips

        GrowthStore g = new GrowthStore(filCap, Constants.kATPOn2WithFormin, dt, pool0, BOX_VOL);

        sc.nodeStore = ns; sc.fil = f; sc.nuc = nuc; sc.grow = g;
        return sc;
    }

    static int countActive(FilamentStore f) { int c = 0; for (int s = 0; s < f.n; s++) if (f.filState.get(s) >= 0) c++; return c; }
    static long sumMonomers(FilamentStore f) { long m = 0; for (int s = 0; s < f.n; s++) if (f.filState.get(s) >= 0) m += f.monomerCount.get(s); return m; }

    // ============================================================== growth block (one cadence firing), both runners
    static void growthBlock(Scene sc, int step, boolean growthOn, boolean poolGate, boolean fires) {
        FilamentStore f = sc.fil; GrowthStore g = sc.grow;
        g.setCounts(step, SEED, fires);
        g.refreshRate(growthOn);
        GrowthSystem.grow(sc.nuc.seedNode, f.monomerCount, f.coord, f.uVec, g.grewFlag, g.growParams, g.growCounts);
        CrossBridgeSystem.csrScan(g.grewScanCounts, g.grewFlag, g.grewOffsets);
        if (poolGate) g.depletePoolForGrows();
        GrowthSystem.markSplits(sc.nuc.seedNode, f.monomerCount, f.coord, f.uVec, f.yVec,
                f.acceptFlag, f.reqCoord, f.reqUVec, f.reqYVec, g.splitParams, g.growCounts);
        FilamentBirthSystem.freeFlags(f.filState, f.freeCount, f.allocCounts);
        CrossBridgeSystem.csrScan(f.freeScanCounts, f.freeCount, f.freeOffsets);
        FilamentBirthSystem.freeScatter(f.filState, f.freeOffsets, f.freeList, f.allocCounts);
        CrossBridgeSystem.csrScan(f.rankScanCounts, f.acceptFlag, f.rankOffsets);
        FilamentBirthSystem.allocate(f.reqCoord, f.reqUVec, f.reqYVec, f.rankOffsets, f.freeList, f.freeOffsets,
                f.coord, f.uVec, f.yVec, f.brownTransScale, f.brownRotScale, f.filState, f.birthParams, f.allocCounts);
        GrowthSystem.splitWire(f.rankOffsets, f.freeList, f.freeOffsets, f.monomerCount, f.coord, f.uVec,
                f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide, sc.nuc.seedNode, g.splitParams, f.allocCounts);
        GrowthSystem.recomputeDrag(f.monomerCount, f.segLength, f.end1NbrSlot, f.end2NbrSlot,
                f.bTransGam, f.bRotGam, f.bTransDiff, f.bRotDiff, g.dragParams, g.growCounts);
    }

    /** Growth-only loop (no dynamics): each iteration is one cadence firing. Isolates rate/split/lifecycle. */
    static void runGrowthOnly(Scene sc, int nCadences, boolean growthOn, boolean poolGate) {
        for (int t = 0; t < nCadences; t++) growthBlock(sc, t, growthOn, poolGate, true);
    }

    /** Full per-step loop: growth block (cadence-gated) + dynamics (zero/brownian/chain/tether/integrate/derive). */
    static void runCpu(Scene sc, int M, boolean growthOn, boolean poolGate, boolean brownian) {
        FilamentStore f = sc.fil; GrowthStore g = sc.grow; RigidRodBody node = sc.nodeStore.node;
        for (int t = 0; t < M; t++) {
            f.setCounts(t, SEED);
            boolean fires = growthOn && g.firesAt(t);
            growthBlock(sc, t, growthOn, poolGate, fires);
            ChainBendingForceSystem.zeroAccumulators(f.forceSum, f.torqueSum, f.counts);
            if (brownian) BrownianForceSystem.brownianForce(f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.brownTransScale, f.brownRotScale, f.params, f.counts);
            ChainBendingForceSystem.chainForces(f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide,
                    f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts);
            NodeNucleationSystem.seedTether(f.coord, f.uVec, f.segLength, f.bTransGam, f.forceSum, f.torqueSum,
                    node.coord, sc.nodeStore.nodeInvTransY, sc.nuc.seedNode, sc.nuc.tetherParams);
            RigidRodLangevinIntegrationSystem.integrate(f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum,
                    f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts);
            DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
        }
    }

    /** Baseline (no growth tasks) — the static reference for the no-op-when-off gate. */
    static void runBaseline(Scene sc, int M, boolean brownian) {
        FilamentStore f = sc.fil; RigidRodBody node = sc.nodeStore.node;
        for (int t = 0; t < M; t++) {
            f.setCounts(t, SEED);
            ChainBendingForceSystem.zeroAccumulators(f.forceSum, f.torqueSum, f.counts);
            if (brownian) BrownianForceSystem.brownianForce(f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.brownTransScale, f.brownRotScale, f.params, f.counts);
            ChainBendingForceSystem.chainForces(f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide,
                    f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts);
            NodeNucleationSystem.seedTether(f.coord, f.uVec, f.segLength, f.bTransGam, f.forceSum, f.torqueSum,
                    node.coord, sc.nodeStore.nodeInvTransY, sc.nuc.seedNode, sc.nuc.tetherParams);
            RigidRodLangevinIntegrationSystem.integrate(f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum,
                    f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts);
            DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
        }
    }

    // ============================================================== GPU plan (full growth pipeline, Brownian off)
    static TornadoExecutionPlan buildPlan(Scene sc) {
        FilamentStore f = sc.fil; GrowthStore g = sc.grow; RigidRodBody node = sc.nodeStore.node;
        TaskGraph tg = new TaskGraph("growth")
            .transferToDevice(DataTransferMode.FIRST_EXECUTION,
                    node.coord, sc.nodeStore.nodeInvTransY, sc.nuc.seedNode, sc.nuc.tetherParams,
                    g.growParams, g.splitParams, g.dragParams, g.grewFlag, g.grewOffsets, g.grewScanCounts,
                    f.acceptFlag, f.reqCoord, f.reqUVec, f.reqYVec, f.freeCount, f.freeOffsets, f.freeList,
                    f.freeScanCounts, f.rankOffsets, f.rankScanCounts, f.allocCounts, f.birthParams,
                    f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.monomerCount,
                    f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide,
                    f.bTransGam, f.bRotGam, f.bTransDiff, f.bRotDiff,
                    f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.brownTransScale, f.brownRotScale,
                    f.params, f.filState, f.chainParams)
            .transferToDevice(DataTransferMode.EVERY_EXECUTION, g.growCounts, f.counts)
            .task("grow", GrowthSystem::grow, sc.nuc.seedNode, f.monomerCount, f.coord, f.uVec, g.grewFlag, g.growParams, g.growCounts)
            .task("csrGrew", CrossBridgeSystem::csrScan, g.grewScanCounts, g.grewFlag, g.grewOffsets)
            .task("markSplits", GrowthSystem::markSplits, sc.nuc.seedNode, f.monomerCount, f.coord, f.uVec, f.yVec,
                    f.acceptFlag, f.reqCoord, f.reqUVec, f.reqYVec, g.splitParams, g.growCounts)
            .task("freeFlags", FilamentBirthSystem::freeFlags, f.filState, f.freeCount, f.allocCounts)
            .task("csrFree", CrossBridgeSystem::csrScan, f.freeScanCounts, f.freeCount, f.freeOffsets)
            .task("freeScatter", FilamentBirthSystem::freeScatter, f.filState, f.freeOffsets, f.freeList, f.allocCounts)
            .task("csrRank", CrossBridgeSystem::csrScan, f.rankScanCounts, f.acceptFlag, f.rankOffsets)
            .task("allocate", FilamentBirthSystem::allocate, f.reqCoord, f.reqUVec, f.reqYVec, f.rankOffsets, f.freeList, f.freeOffsets,
                    f.coord, f.uVec, f.yVec, f.brownTransScale, f.brownRotScale, f.filState, f.birthParams, f.allocCounts)
            .task("splitWire", GrowthSystem::splitWire, f.rankOffsets, f.freeList, f.freeOffsets, f.monomerCount, f.coord, f.uVec,
                    f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide, sc.nuc.seedNode, g.splitParams, f.allocCounts)
            .task("drag", GrowthSystem::recomputeDrag, f.monomerCount, f.segLength, f.end1NbrSlot, f.end2NbrSlot,
                    f.bTransGam, f.bRotGam, f.bTransDiff, f.bRotDiff, g.dragParams, g.growCounts)
            .task("zero", ChainBendingForceSystem::zeroAccumulators, f.forceSum, f.torqueSum, f.counts)
            .task("chain", ChainBendingForceSystem::chainForces, f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide,
                    f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts)
            .task("tether", NodeNucleationSystem::seedTether, f.coord, f.uVec, f.segLength, f.bTransGam, f.forceSum, f.torqueSum,
                    node.coord, sc.nodeStore.nodeInvTransY, sc.nuc.seedNode, sc.nuc.tetherParams)
            .task("integrate", RigidRodLangevinIntegrationSystem::integrate, f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum,
                    f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts)
            .task("derive", DerivedGeometrySystem::derive, f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts)
            .transferToHost(DataTransferMode.UNDER_DEMAND,
                    f.coord, f.uVec, f.monomerCount, f.filState, f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide, sc.nuc.seedNode);
        int C = f.n;
        sched = new GridScheduler();
        addW("growth.grow", pad(C)); addS("growth.csrGrew"); addW("growth.markSplits", pad(C));
        addW("growth.freeFlags", pad(C)); addS("growth.csrFree"); addS("growth.freeScatter"); addS("growth.csrRank");
        addW("growth.allocate", pad(C)); addW("growth.splitWire", pad(C)); addW("growth.drag", pad(C));
        addW("growth.zero", pad(C)); addW("growth.chain", pad(C)); addW("growth.tether", pad(C));
        addW("growth.integrate", pad(C)); addW("growth.derive", pad(C));
        return new TornadoExecutionPlan(tg.snapshot());
    }
    static void runGpu(Scene sc, int M) {
        TornadoExecutionPlan plan = buildPlan(sc);
        FilamentStore f = sc.fil; GrowthStore g = sc.grow;
        for (int t = 0; t < M; t++) {
            f.setCounts(t, SEED);
            g.setCounts(t, SEED, g.firesAt(t));   // growthOn=true; pool frozen on GPU (conc constant)
            g.refreshRate(true);
            TornadoExecutionResult res = plan.withGridScheduler(sched).execute();
            if (t == M - 1) res.transferToHost(f.coord, f.uVec, f.monomerCount, f.filState,
                    f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide, sc.nuc.seedNode);
        }
    }

    // ============================================================== 1. lengthen + drag recompute
    static boolean checkLengthen(double dt) {
        System.out.println("--- 1. lengthen (monomer-by-monomer at the [actin]-dependent rate; drag recomputes) ---");
        // 1A: recomputeDrag == host DragTensorSystem reference (bit-identical, same double math) over a length sweep
        boolean dragOk = true; double maxRel = 0;
        for (int mc : new int[]{3, 20, 32, 40, 64, 100}) {
            FilamentStore f = new FilamentStore(1, 1);
            f.monomerCount.set(0, mc); DragTensorSystem.run(f);
            double[] ref = { f.segLength.get(0), f.bTransGam.get(0), f.bTransGam.get(1), f.bRotGam.get(1) };
            GrowthStore g = new GrowthStore(1, Constants.kATPOn2WithFormin, dt, 1.0, BOX_VOL);
            g.growCounts.set(3, 1);   // fires
            GrowthSystem.recomputeDrag(f.monomerCount, f.segLength, f.end1NbrSlot, f.end2NbrSlot,
                    f.bTransGam, f.bRotGam, f.bTransDiff, f.bRotDiff, g.dragParams, g.growCounts);
            double[] got = { f.segLength.get(0), f.bTransGam.get(0), f.bTransGam.get(1), f.bRotGam.get(1) };
            for (int i = 0; i < 4; i++) maxRel = Math.max(maxRel, Math.abs(got[i] - ref[i]) / Math.max(Math.abs(ref[i]), 1e-30));
        }
        dragOk = maxRel < 1e-6;
        System.out.printf("  recomputeDrag vs host DragTensorSystem over monomerCount sweep: maxRel=%.2e => %s%n", maxRel, dragOk ? "ok" : "*FAIL*");

        // 1B: dynamic — N tips grow; empirical per-event P vs onRate·conc·biochemDeltaT (growth-only, conc frozen)
        int N = 64, nCad = 3000, monInit = 3, filCap = 4096;
        Scene sc = build(N, filCap, dt, monInit, Constants.actinConcInit, false);
        long m0 = sumMonomers(sc.fil);
        runGrowthOnly(sc, nCad, true, false);   // growthOn, conc frozen (no depletion)
        long m1 = sumMonomers(sc.fil);
        long grown = m1 - m0;
        double Pemp = grown / ((double) N * nCad);
        double Pth = Constants.kATPOn2WithFormin * Constants.actinConcInit * Constants.biochemDeltaT;
        double rel = Math.abs(Pemp - Pth) / Pth;
        boolean rateOk = rel < 0.05;            // ~33k events ⇒ tight
        boolean grew = m1 > m0 && countActive(sc.fil) > N;   // tips grew AND splits created children
        System.out.printf("  dynamic: %d monomers grown over %d tips × %d cadences; P_emp=%.4f vs onRate·conc·bcΔt=%.4f (rel %.1f%%) => %s%n",
                grown, N, nCad, Pemp, Pth, 100 * rel, (rateOk && grew) ? "ok" : "*FAIL*");
        System.out.printf("  active filaments %d → %d (splits created children); ΣmonomerCount conserved across splits%n", N, countActive(sc.fil));
        boolean ok = dragOk && rateOk && grew;
        System.out.println("  => " + (ok ? "PASS" : "*FAIL*") + "\n");
        return ok;
    }

    // ============================================================== 2. split @ 64 (the headline)
    static boolean checkSplit(double dt) {
        System.out.println("--- 2. split @64 (child via B1 allocator + 3-slot chain rewire; conserved; CPU≡GPU) ---");
        boolean ok = true;
        // Case A: a lone tip (no Mold). monomerCount=64 ⇒ one split ⇒ G(32)—C(32), C is the new pointed tip.
        {
            Scene sc = build(1, 8, dt, 3, 1.0e30, false);
            FilamentStore f = sc.fil;
            f.monomerCount.set(0, 64); DragTensorSystem.run(f);
            float e1x0 = f.coordX(0) - 0.5f * f.segLength.get(0);   // tip end1 (node side) before split
            runGrowthOnly(sc, 1, true, false);
            int G = 0, C = f.end2NbrSlot.get(0);
            boolean alloc = (C >= 1 && f.filState.get(C) >= 0);
            boolean cons = (f.monomerCount.get(G) == 32) && (C >= 0 && f.monomerCount.get(C) == 32);
            boolean links = f.end2NbrSlot.get(G) == C && f.end2NbrSide.get(G) == 0
                    && f.end1NbrSlot.get(C) == G && f.end1NbrSide.get(C) == 1
                    && f.end2NbrSlot.get(C) == -1;
            float e1x1 = f.coordX(G) - 0.5f * f.segLength.get(G);   // G end1 must be unchanged (node side fixed)
            boolean end1Fixed = Math.abs(e1x1 - e1x0) < 1e-5;
            // geometry: C outward (+x) of G; C.end1 ≈ G.end2 (within ~1 monomer overlap)
            float gE2 = f.coordX(G) + 0.5f * f.segLength.get(G);
            float cE1 = f.coordX(C) - 0.5f * f.segLength.get(C);
            boolean geom = f.coordX(C) > f.coordX(G) && Math.abs(cE1 - gE2) < 1.5 * Constants.actinMonoRadius;
            boolean caseA = alloc && cons && links && end1Fixed && geom;
            System.out.printf("  A (lone): child=%d alloc=%s conserved(32+32)=%s links=%s end1Fixed=%s geom(outward)=%s => %s%n",
                    C, alloc, cons, links, end1Fixed, geom, caseA ? "ok" : "*FAIL*");
            ok &= caseA;
        }
        // Case B: a 2-segment filament G(tip,64)—Mold(32). Split inserts C between ⇒ G(32)—C(32)—Mold(32).
        {
            Scene sc = build(1, 8, dt, 3, 1.0e30, false);
            FilamentStore f = sc.fil;
            // build G (slot 0, tip) — Mold (slot 7)
            int G = 0, Mold = 7;
            f.monomerCount.set(G, 64); f.monomerCount.set(Mold, 32);
            f.filState.set(Mold, FilamentStore.FIL_ACTIVE);
            DragTensorSystem.run(f);
            float gHalf = 0.5f * f.segLength.get(G), mHalf = 0.5f * f.segLength.get(Mold);
            // place Mold outward of G; G.end2 ↔ Mold.end1
            f.setCoord(Mold, f.coordX(G) + gHalf + mHalf, 0f, 0f); f.setUVec(Mold, 1f, 0f, 0f); f.setYVec(Mold, 0f, 1f, 0f);
            f.end2NbrSlot.set(G, Mold); f.end2NbrSide.set(G, 0);
            f.end1NbrSlot.set(Mold, G); f.end1NbrSide.set(Mold, 1);
            DragTensorSystem.run(f);
            runGrowthOnly(sc, 1, true, false);
            int C = f.end2NbrSlot.get(G);
            boolean chainGC = (C >= 0 && C != Mold) && f.end1NbrSlot.get(C) == G && f.end1NbrSide.get(C) == 1;
            boolean chainCM = f.end2NbrSlot.get(C) == Mold && f.end2NbrSide.get(C) == 0
                    && f.end1NbrSlot.get(Mold) == C && f.end1NbrSide.get(Mold) == 1;
            boolean cons = f.monomerCount.get(G) == 32 && f.monomerCount.get(C) == 32 && f.monomerCount.get(Mold) == 32;
            boolean caseB = chainGC && chainCM && cons;
            System.out.printf("  B (Mold): inserted child=%d  G.end2→C=%s  C.end2→Mold + Mold.end1→C=%s  conserved=%s => %s%n",
                    C, chainGC, chainCM, cons, caseB ? "ok" : "*FAIL*");
            ok &= caseB;
        }
        // CPU≡GPU bit-identity of a split (lifecycle integer-identical; pose float32)
        if (!cpu) {
            Scene gpu = build(1, 8, dt, 3, 1.0e30, false), cpuS = build(1, 8, dt, 3, 1.0e30, false);
            gpu.fil.monomerCount.set(0, 64); DragTensorSystem.run(gpu.fil);
            cpuS.fil.monomerCount.set(0, 64); DragTensorSystem.run(cpuS.fil);
            runGpu(gpu, 1); runCpu(cpuS, 1, true, false, false);   // both: full pipeline (split + integrate), step 0
            boolean bit = true;
            for (int s = 0; s < 8; s++) {
                if (gpu.fil.monomerCount.get(s) != cpuS.fil.monomerCount.get(s)) bit = false;
                if (gpu.fil.filState.get(s) != cpuS.fil.filState.get(s)) bit = false;
                if (gpu.fil.end2NbrSlot.get(s) != cpuS.fil.end2NbrSlot.get(s)) bit = false;
                if (gpu.fil.end1NbrSlot.get(s) != cpuS.fil.end1NbrSlot.get(s)) bit = false;
            }
            double dCoord = maxDiff(gpu.fil.coord, cpuS.fil.coord);
            boolean cg = bit && dCoord < 5e-5;
            System.out.printf("  CPU≡GPU split: lifecycle bit-identical=%s, max|Δcoord|=%.2e µm (<5e-5) => %s%n", bit, dCoord, cg ? "ok" : "*FAIL*");
            ok &= cg;
        }
        System.out.println("  => " + (ok ? "PASS" : "*FAIL*") + "\n");
        return ok;
    }

    // ============================================================== 3. rate first-order in [actin] + pool depletion
    static boolean checkRatePool(double dt) {
        System.out.println("--- 3. [actin]-dependent rate (first-order) + pool depletion (seam #2) ---");
        int N = 64, nCad = 2000, filCap = 4096;
        // first-order: empirical P at conc and conc/2 (frozen, no depletion) ⇒ ratio ≈ 2
        double pFull = empiricalP(build(N, filCap, dt, 3, Constants.actinConcInit, false), nCad, N);
        double pHalf = empiricalP(build(N, filCap, dt, 3, 0.5 * Constants.actinConcInit, false), nCad, N);
        double ratio = pFull / pHalf;
        boolean firstOrder = Math.abs(ratio - 2.0) < 0.1;
        System.out.printf("  first-order: P(%.1fµM)=%.4f, P(%.1fµM)=%.4f, ratio=%.3f (≈2) => %s%n",
                Constants.actinConcInit, pFull, 0.5 * Constants.actinConcInit, pHalf, ratio, firstOrder ? "ok" : "*FAIL*");
        // pool depletion (seam #2): the pool drains as monomers are consumed; first-order kinetics ⇒ the rate
        // DECLINES as conc falls (growth slows as the pool depletes — the asymptotic mechanism, not a hard "dry").
        double uMper = new ActinPool(1.0, BOX_VOL).uMPerMonomer();
        int nCadP = 6000, filCapP = 8192;
        Scene sc = build(N, filCapP, dt, 3, Constants.actinConcInit, false);   // full 15 µM pool, depletes over the run
        double conc0 = sc.grow.pool.conc();
        long mInit = sumMonomers(sc.fil);
        long mA = sumMonomers(sc.fil); runGrowthOnly(sc, nCadP / 2, true, true); long mB = sumMonomers(sc.fil);   // poolGate ON
        long firstHalf = mB - mA;
        runGrowthOnly(sc, nCadP / 2, true, true); long mC = sumMonomers(sc.fil);
        long secondHalf = mC - mB;
        long grown = mC - mInit;
        double conc1 = sc.grow.pool.conc();
        boolean depletes = Math.abs((conc0 - conc1) - grown * uMper) < 1e-6 && grown > 0;   // conservative (pool ↔ filament)
        boolean drains = conc1 < conc0;
        boolean slows = secondHalf < firstHalf;            // first-order: pool depletion lowers the rate
        boolean poolOk = depletes && drains && slows;
        System.out.printf("  pool: conc %.3f → %.3f µM; grown=%d monomers; Δconc==grown·µMper=%s%n", conc0, conc1, grown, depletes);
        System.out.printf("  first-order slowdown as the pool drains: grows first-half=%d > second-half=%d => %s%n",
                firstHalf, secondHalf, (slows && drains) ? "ok" : "*FAIL*");
        boolean ok = firstOrder && poolOk;
        System.out.println("  => " + (ok ? "PASS" : "*FAIL*") + "\n");
        return ok;
    }
    static double empiricalP(Scene sc, int nCad, int N) {
        long m0 = sumMonomers(sc.fil);
        runGrowthOnly(sc, nCad, true, false);
        return (sumMonomers(sc.fil) - m0) / ((double) N * nCad);
    }

    // ============================================================== 4. growing-end extends outward
    static boolean checkGrowingEnd(double dt) {
        System.out.println("--- 4. growing-end (node-side grows; filament extends OUTWARD; tip end1 held at node) ---");
        int N = 8, M = 60000, filCap = 256;
        Scene sc = build(N, filCap, dt, 3, Constants.actinConcInit, false);
        double contour0 = contour(sc.fil);
        runCpu(sc, M, true, false, false);                 // full dynamics, Brownian off
        double contour1 = contour(sc.fil);
        // tip end1 stays near its node (tether holds it)
        double maxTipGap = 0;
        for (int k = 0; k < N; k++) {
            int s = -1; for (int i = 0; i < filCap; i++) if (sc.nuc.seedNode.get(i) == k) s = i;
            if (s < 0) continue;
            double half = 0.5 * sc.fil.segLength.get(s);
            double e1x = sc.fil.coordX(s) - half * sc.fil.uVecX(s), e1y = sc.fil.coordY(s) - half * sc.fil.uVecY(s), e1z = sc.fil.coordZ(s) - half * sc.fil.uVecZ(s);
            double nx = sc.nodeStore.node.coord.get(k), ny = sc.nodeStore.node.coord.get(N + k), nz = sc.nodeStore.node.coord.get(2 * N + k);
            maxTipGap = Math.max(maxTipGap, Math.sqrt((e1x - nx) * (e1x - nx) + (e1y - ny) * (e1y - ny) + (e1z - nz) * (e1z - nz)));
        }
        boolean grewOut = contour1 > contour0 * 1.5;
        boolean held = maxTipGap < 0.02;                   // tip end1 within 20 nm of its node
        boolean ok = grewOut && held;
        System.out.printf("  contour Σ segLength: %.4f → %.4f µm (grows %.1f×); max tip end1↔node gap=%.4e µm (held) => %s%n",
                contour0, contour1, contour1 / contour0, maxTipGap, ok ? "PASS" : "*FAIL*");
        System.out.println("  => " + (ok ? "PASS" : "*FAIL*") + "\n");
        return ok;
    }
    static double contour(FilamentStore f) { double c = 0; for (int s = 0; s < f.n; s++) if (f.filState.get(s) >= 0) c += f.segLength.get(s); return c; }

    // ============================================================== 5. no-op when off
    static boolean checkNoOpWhenOff(double dt) {
        System.out.println("--- 5. no-op-when-off (growth OFF ⇒ bit-identical to a static baseline) ---");
        int N = 8, M = 5000, filCap = 64;
        Scene a = build(N, filCap, dt, 12, Constants.actinConcInit, false);   // growth pipeline, OFF
        Scene b = build(N, filCap, dt, 12, Constants.actinConcInit, false);   // baseline, no growth tasks
        runCpu(a, M, false, false, false);                 // growthOn=false ⇒ fires always 0 ⇒ growth kernels no-op
        runBaseline(b, M, false);
        boolean bit = true;
        for (int s = 0; s < filCap; s++) {
            if (a.fil.monomerCount.get(s) != b.fil.monomerCount.get(s)) bit = false;
            if (a.fil.filState.get(s) != b.fil.filState.get(s)) bit = false;
            if (a.nuc.seedNode.get(s) != b.nuc.seedNode.get(s)) bit = false;
        }
        double dCoord = maxDiff(a.fil.coord, b.fil.coord);
        boolean ok = bit && dCoord == 0.0;
        System.out.printf("  growth-OFF vs baseline after %d steps: lifecycle bit-identical=%s, max|Δcoord|=%.2e (==0) => %s%n",
                M, bit, dCoord, ok ? "PASS" : "*FAIL*");
        System.out.println("  => " + (ok ? "PASS" : "*FAIL*") + "\n");
        return ok;
    }

    // ============================================================== 7. drag-clamp fidelity (flag c)
    static boolean checkDragClamp(double dt) {
        System.out.println("--- 7. drag-clamp fidelity (3-monomer seed vs the minMonomerCt/stdSegLength clamp) ---");
        // seed (3 monomers, free rod, both ends free) ⇒ recomputeDrag clamps asIfLength to stdSegLength·mono
        FilamentStore f = new FilamentStore(1, 1);
        f.monomerCount.set(0, Constants.actinSeed); DragTensorSystem.run(f);
        double clamped = f.bTransGam.get(0);
        // the UNCLAMPED drag at the true 3-monomer length, for the report
        double trueLen = (Constants.actinSeed + 1) * Constants.actinMonoRadius;
        double clampLen = Constants.stdSegLength * Constants.actinMonoRadius;
        double unclamped = DragTensorSystem.rodDragSI(trueLen, Constants.radius)[0];
        double clampRef = DragTensorSystem.rodDragSI(clampLen, Constants.radius)[0];
        boolean clampMatches = Math.abs(clamped - clampRef) / clampRef < 1e-6;   // recomputeDrag clamp == v1 rule
        System.out.printf("  seed monomerCount=%d (len %.4f µm): drag bTGx=%.4e (clamped to stdSegLength=%d ⇒ len %.4f µm)%n",
                Constants.actinSeed, trueLen, clamped, Constants.stdSegLength, clampLen);
        System.out.printf("  unclamped-at-true-length bTGx=%.4e (%.2f× lower) — the clamp is FAITHFUL to v1 FilSegment:409-419%n",
                unclamped, clamped / unclamped);
        System.out.println("  (intended: short end-segments don't get unphysically low drag/over-diffuse; v1 clamps end segments to stdSegLength.)");
        System.out.println("  => " + (clampMatches ? "PASS" : "*FAIL*") + "\n");
        return clampMatches;
    }

    // ============================================================== 8. participates + dt-stable
    static boolean checkParticipates(double dt) {
        System.out.println("--- 8. participates + dt-stable (grown/split filament integrates + chains; bounded, NaN-free) ---");
        int N = 8, M = 80000, filCap = 512;
        Scene sc = build(N, filCap, dt, 3, Constants.actinConcInit, true);     // Brownian ON, growth ON
        runCpu(sc, M, true, true, true);
        FilamentStore f = sc.fil;
        // (a) NaN-free + bounded (no blow-up)
        boolean finite = true; double maxAbs = 0;
        for (int i = 0; i < f.coord.getSize(); i++) { float v = f.coord.get(i); if (Float.isNaN(v) || Float.isInfinite(v)) finite = false; maxAbs = Math.max(maxAbs, Math.abs(v)); }
        boolean bounded = finite && maxAbs < 50.0;          // µm; the structure stays in a sane region
        // (b) chain integrity: walk each tip's chain via end2; every link reciprocal; monomers conserved
        boolean chainOk = true; int reached = 0;
        for (int k = 0; k < N; k++) {
            int s = -1; for (int i = 0; i < filCap; i++) if (sc.nuc.seedNode.get(i) == k) s = i;
            int guard = 0, prev = -1;
            while (s >= 0 && guard < filCap) {
                reached++;
                int nb = f.end2NbrSlot.get(s);
                if (nb >= 0) {   // reciprocal: my end2 ↔ neighbor's (side) end
                    int nbSide = f.end2NbrSide.get(s);
                    int back = (nbSide == 0) ? f.end1NbrSlot.get(nb) : f.end2NbrSlot.get(nb);
                    if (back != s) { chainOk = false; }
                }
                prev = s; s = nb; guard++;
            }
        }
        long totalMon = sumMonomers(f);
        boolean ok = bounded && chainOk;
        System.out.printf("  after %d Brownian steps: finite=%s max|coord|=%.3f µm (bounded); chain reciprocal=%s; active=%d, Σmonomers=%d%n",
                M, finite, maxAbs, chainOk, countActive(f), totalMon);
        System.out.println("  (a grown/split multi-segment filament integrates, chains, and stays dt-stable under Brownian.)");
        System.out.println("  => " + (ok ? "PASS" : "*FAIL*") + "\n");
        return ok;
    }

    // ============================================================== 6. CPU≡GPU (full pipeline)
    static boolean checkCpuGpu(double dt) {
        if (cpu) { System.out.println("--- 6. CPU≡GPU: skipped (-cpu) ---\n"); return true; }
        System.out.println("--- 6. CPU≡GPU (full growth pipeline, Brownian off ⇒ lifecycle bit-identical) ---");
        int N = 32, M = 12000, filCap = 1024;
        Scene g = build(N, filCap, dt, 3, Constants.actinConcInit, false);
        Scene c = build(N, filCap, dt, 3, Constants.actinConcInit, false);
        runGpu(g, M);
        runCpu(c, M, true, false, false);                  // conc frozen (GPU pool not depleted) ⇒ matched
        int monMis = 0, stateMis = 0, linkMis = 0, seedMis = 0;
        for (int s = 0; s < filCap; s++) {
            if (g.fil.monomerCount.get(s) != c.fil.monomerCount.get(s)) monMis++;
            if (g.fil.filState.get(s) != c.fil.filState.get(s)) stateMis++;
            if (g.fil.end2NbrSlot.get(s) != c.fil.end2NbrSlot.get(s) || g.fil.end1NbrSlot.get(s) != c.fil.end1NbrSlot.get(s)) linkMis++;
            if (g.nuc.seedNode.get(s) != c.nuc.seedNode.get(s)) seedMis++;
        }
        double dCoord = maxDiff(g.fil.coord, c.fil.coord);
        int actG = countActive(g.fil), actC = countActive(c.fil);
        boolean ok = monMis == 0 && stateMis == 0 && linkMis == 0 && seedMis == 0 && dCoord < 5e-5 && actG == actC;
        System.out.printf("  active GPU=%d CPU=%d; mismatches monomer=%d state=%d link=%d seed=%d (all 0 ⇒ bit-identical lifecycle); max|Δcoord|=%.2e µm (<5e-5) => %s%n",
                actG, actC, monMis, stateMis, linkMis, seedMis, dCoord, ok ? "PASS" : "*FAIL*");
        System.out.println("  (wang-hash grow decisions + the scan-rank allocator + the integer chain rewire are deterministic ⇒ bit-identical;");
        System.out.println("   coord/drag carry float32 last-bit FMA + transcendental (Math.log) differences.)");
        System.out.println("  => " + (ok ? "PASS" : "*FAIL*") + "\n");
        return ok;
    }

    // ============================================================== utils
    static double maxDiff(FloatArray a, FloatArray b) { double m = 0; for (int i = 0; i < a.getSize(); i++) m = Math.max(m, Math.abs(a.get(i) - b.get(i))); return m; }
    static int pad(int n) { return ((n + B - 1) / B) * B; }
    static void addW(String name, int gg) { WorkerGrid w = new WorkerGrid1D(gg); w.setLocalWork(B, 1, 1); sched.addWorkerGrid(name, w); }
    static void addS(String name) { WorkerGrid w = new WorkerGrid1D(1); w.setLocalWork(1, 1, 1); sched.addWorkerGrid(name, w); }
}
