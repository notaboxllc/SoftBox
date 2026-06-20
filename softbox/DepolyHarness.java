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
 * Increment 7 Stage 0 + Stage 1 — actin TURNOVER: pool-return + conservation gate + pointed-end (end1)
 * depolymerization + filament DEATH + slot-recycle. The reverse-of-growth foundation (DepolySystem; recon
 * INC7_TURNOVER_RECON.md). Default-OFF. Run: ./run_depoly.sh [-cpu].
 *
 * Stage 1 uses a FIXED depoly rate (default Constants.kATPOff1 = 0.8/s; nucleotide-dependent rates are Stage 3).
 * Filaments shrink at the pointed end (end1) and DIE at monomerCount < actinSeed (=3): the slot is markFree'd
 * (Design A, no swap-compaction), all remaining monomers return to the pool en masse (conservation), the chain
 * links break (valid sub-chains), and the node tether clears. A freed slot re-enters the scan-rank free-list the
 * SAME cadence ⇒ reclaimable by a split / nucleation (the 5c-i death→free-list→allocate order).
 *
 * Gates (both runners):
 *   0 behavior     — filaments SHRINK at the pointed end and DIE (contour ↓, active ↓, slots freed).
 *   1 conservation — EXACT integer (pool + Σ monomerCount = const): depoly-only AND grow+depoly combined.
 *   2 slot-recycle — a slot freed by death this cadence is RECLAIMED same-step by a growth split.
 *   3 link-break   — pointed-tip death shortens the chain (new valid tip); INTERIOR death leaves TWO valid
 *                    reciprocal sub-chains (applyDeath's general link-break — what Stage 2 dissolve relies on).
 *   4 CPU≡GPU      — depoly + death + recomputeDrag + integrate: lifecycle bit-identical, pose float32.
 *   5 no-op-off    — depoly OFF ⇒ bit-identical to a no-depoly (growth-style) baseline.
 *   6 rate-wiring  — empirical per-event P ≈ offRate·biochemDeltaT at the DEFAULT rate (derived-probability /
 *                    biochem-cadence lock-step check).
 */
public final class DepolyHarness {

    static final int B = 64;
    static GridScheduler sched;
    static boolean cpu = false;
    static final int SEED = 0x07DEC0;
    static final double BOX_VOL = 8.0 * 4.0 * 0.6;       // µm³ (matches GrowthHarness; for µM-per-monomer)
    static final double TEST_OFFRATE = 100.0;            // /s — a TEST rate (P=0.1/fire) to exercise death on a
                                                         //      short horizon; the DEFAULT is Constants.kATPOff1.

    public static void main(String[] args) {
        double dt = 1.0e-5;
        for (int i = 0; i < args.length; i++) if (args[i].equals("-cpu")) cpu = true;
        System.out.println("=== Soft Box increment 7 Stage 0+1 — actin TURNOVER: pointed-end depoly + filament death ===");
        System.out.println("reverse-of-growth: filaments shrink at end1 (pointed) and DIE at monomerCount<actinSeed(=3);");
        System.out.println("slot markFree'd (Design A, no compaction) + monomers returned to the pool en masse (conservation).");
        System.out.println("v1 specifics: kATPOff1=0.8/s (default, pointed ATP-off), actinSeed=3, biochemDeltaT=1e-3 s.");
        System.out.println("runner: " + (cpu ? "CPU only (-cpu)" : "GPU + CPU cross-check") + ", dt=" + dt
                + " (test rate " + TEST_OFFRATE + "/s for death gates)\n");

        boolean g0 = checkBehavior(dt);
        boolean g1 = checkConservation(dt);
        boolean g2 = checkSlotRecycle(dt);
        boolean g3 = checkLinkBreak(dt);
        boolean g5 = checkNoOpWhenOff(dt);
        boolean g6 = checkRateWiring(dt);
        boolean g4 = checkCpuGpu(dt);

        boolean ok = g0 && g1 && g2 && g3 && g4 && g5 && g6;
        System.out.println();
        System.out.println("=== TURNOVER (Stage 0+1: depoly + death) VALIDATION " + (ok ? "PASS" : "FAIL") + " ===");
        if (!ok) { System.out.println("BAIL-OUT: a gate failed. Commit nothing."); System.exit(1); }
    }

    // ============================================================== scene
    static final class Scene {
        NodeStore nodeStore; FilamentStore fil; NodeNucleationStore nuc; GrowthStore grow; DepolyStore depoly;
        int nNodes, filCap, nActive;
    }

    /**
     * nActive ACTIVE filaments at slots [0,nActive); [nActive,filCap) FREE (for recycling). monomerInit
     * monomers each. If tether, slots [0,nNodes) are node-bonded tips (seedNode=k, barbed end2 at node k);
     * otherwise all ACTIVE filaments are FREE rods (seedNode=-1) — pointed tips on end1. The Depoly + Growth
     * stores SHARE one ActinPool (grow.pool) so conservation crosses both directions.
     */
    static Scene build(int nNodes, int nActive, int filCap, double dt, int monomerInit, double pool0,
                       boolean tether, boolean brownOn, double offRate) {
        Scene sc = new Scene();
        sc.nNodes = nNodes; sc.filCap = filCap; sc.nActive = nActive;

        NodeStore ns = new NodeStore(Math.max(1, nNodes), 1);
        for (int k = 0; k < ns.node.n; k++) ns.node.setCoord(k, (float) (0.5 * k), 0f, 0f);
        ns.node.uVec.init(0f); ns.node.yVec.init(0f);
        for (int k = 0; k < ns.node.n; k++) { ns.node.setUVec(k, 1f, 0f, 0f); ns.node.setYVec(k, 0f, 1f, 0f); }
        ns.initNodeDrag();

        FilamentStore f = new FilamentStore(filCap, filCap);   // reqCap = capacity (request index == slot)
        for (int s = 0; s < filCap; s++) f.monomerCount.set(s, monomerInit);
        DragTensorSystem.run(f);
        f.setParams(dt, Constants.brownianForceMag());
        f.setChainParams();
        double bornScale = brownOn ? Constants.BTransCoeff : 0.0;
        f.setBirthParams(bornScale, bornScale);
        f.setBirthRequestCount(filCap);

        NodeNucleationStore nuc = new NodeNucleationStore(Math.max(1, nNodes), filCap, monomerInit, 1.0e30, BOX_VOL, 1.0);
        nuc.setTetherParams(Constants.fracMove, dt);

        // all FREE first, then place the ACTIVE filaments
        for (int s = 0; s < filCap; s++) { f.setCoord(s, 0f, 0f, 0f); f.setUVec(s, 1f, 0f, 0f); f.setYVec(s, 0f, 1f, 0f); f.markFree(s); }
        double seedLen = (monomerInit + 1) * Constants.actinMonoRadius;
        double half = 0.5 * seedLen;
        for (int a = 0; a < nActive; a++) {
            f.filState.set(a, FilamentStore.FIL_ACTIVE);
            // place along x with a stride so spheres don't overlap; uVec inward (-x); end1 (pointed) outward (+x)
            f.setCoord(a, (float) (2.0 * a + half), 0f, 0f);
            f.setUVec(a, -1f, 0f, 0f); f.setYVec(a, 0f, 1f, 0f);
            f.brownTransScale.set(a, (float) bornScale); f.brownRotScale.set(a, (float) bornScale);
            if (tether && a < nNodes) {
                // barbed end2 at the node; place so end2 = node.coord (node at (0.5k,0,0))
                f.setCoord(a, (float) (0.5 * a + half), 0f, 0f);
                nuc.seedNode.set(a, a);
            }
        }
        DragTensorSystem.run(f);

        GrowthStore g = new GrowthStore(filCap, Constants.kATPOn2WithFormin, dt, pool0, BOX_VOL);
        DepolyStore d = new DepolyStore(filCap, offRate, dt, g.pool);   // SHARE the pool

        sc.nodeStore = ns; sc.fil = f; sc.nuc = nuc; sc.grow = g; sc.depoly = d;
        return sc;
    }

    static int countActive(FilamentStore f) { int c = 0; for (int s = 0; s < f.n; s++) if (f.filState.get(s) >= 0) c++; return c; }
    static long sumMonomers(FilamentStore f) { long m = 0; for (int s = 0; s < f.n; s++) if (f.filState.get(s) >= 0) m += f.monomerCount.get(s); return m; }
    static double contour(FilamentStore f) { double c = 0; for (int s = 0; s < f.n; s++) if (f.filState.get(s) >= 0) c += f.segLength.get(s); return c; }

    // ============================================================== blocks
    /** One depoly cadence firing: depoly → csrScan → pool.put → applyDeath → recomputeDrag. */
    static void depolyBlock(Scene sc, int step, boolean depolyOn, boolean fires, boolean poolGate) {
        FilamentStore f = sc.fil; DepolyStore d = sc.depoly;
        d.setCounts(step, SEED, fires);
        d.refreshRate(depolyOn);
        DepolySystem.depoly(f.filState, f.monomerCount, f.coord, f.uVec, f.end1NbrSlot,
                d.returnedMon, d.deathFlag, d.depolyParams, d.depolyCounts);
        CrossBridgeSystem.csrScan(d.returnScanCounts, d.returnedMon, d.returnedOffsets);
        if (poolGate) d.returnPoolForDepoly();
        DepolySystem.applyDeath(f.filState, f.monomerCount, sc.nuc.seedNode,
                f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide,
                f.brownTransScale, f.brownRotScale, d.deathFlag, d.depolyCounts);
        GrowthSystem.recomputeDrag(f.monomerCount, f.segLength, f.end1NbrSlot, f.end2NbrSlot,
                f.bTransGam, f.bRotGam, f.bTransDiff, f.bRotDiff, d.dragParams, d.depolyCounts);
    }

    /** One growth cadence firing (split allocator) — reused from the growth pipeline; here only to demonstrate
     *  same-step slot reuse + combined conservation. */
    static void growthBlock(Scene sc, int step, boolean growthOn, boolean fires) {
        FilamentStore f = sc.fil; GrowthStore g = sc.grow;
        g.setCounts(step, SEED, fires);
        g.refreshRate(growthOn);
        GrowthSystem.grow(sc.nuc.seedNode, f.monomerCount, f.coord, f.uVec, g.grewFlag, g.growParams, g.growCounts);
        CrossBridgeSystem.csrScan(g.grewScanCounts, g.grewFlag, g.grewOffsets);
        g.depletePoolForGrows();
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

    /** Depoly-only cadence loop (each iteration = one firing) — isolates shrink/death/conservation. */
    static void runDepolyOnly(Scene sc, int nCad, boolean depolyOn, boolean poolGate) {
        for (int t = 0; t < nCad; t++) depolyBlock(sc, t, depolyOn, true, poolGate);
    }

    /** Full per-step CPU loop: depoly (cadence-gated, frees slots) → growth (reclaims) → dynamics. */
    static void runCpu(Scene sc, int M, boolean depolyOn, boolean growthOn, boolean brownian, boolean firesEvery) {
        FilamentStore f = sc.fil; RigidRodBody node = sc.nodeStore.node;
        for (int t = 0; t < M; t++) {
            f.setCounts(t, SEED);
            boolean dFires = depolyOn && (firesEvery || sc.depoly.firesAt(t));
            boolean gFires = growthOn && (firesEvery || sc.grow.firesAt(t));
            depolyBlock(sc, t, depolyOn, dFires, true);            // death frees slots FIRST
            if (growthOn) growthBlock(sc, t, growthOn, gFires);    // then growth reclaims them
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

    /** Baseline (no depoly tasks) — the static reference for no-op-when-off. */
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

    // ============================================================== GPU plan (depoly + death + dynamics, Brownian off)
    static TornadoExecutionPlan buildPlan(Scene sc) {
        FilamentStore f = sc.fil; DepolyStore d = sc.depoly; RigidRodBody node = sc.nodeStore.node;
        TaskGraph tg = new TaskGraph("depoly")
            .transferToDevice(DataTransferMode.FIRST_EXECUTION,
                    node.coord, sc.nodeStore.nodeInvTransY, sc.nuc.seedNode, sc.nuc.tetherParams,
                    d.depolyParams, d.dragParams, d.returnedMon, d.returnedOffsets, d.returnScanCounts, d.deathFlag,
                    f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.monomerCount,
                    f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide,
                    f.bTransGam, f.bRotGam, f.bTransDiff, f.bRotDiff,
                    f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.brownTransScale, f.brownRotScale,
                    f.params, f.filState, f.chainParams)
            .transferToDevice(DataTransferMode.EVERY_EXECUTION, d.depolyCounts, f.counts)
            .task("depoly", DepolySystem::depoly, f.filState, f.monomerCount, f.coord, f.uVec, f.end1NbrSlot,
                    d.returnedMon, d.deathFlag, d.depolyParams, d.depolyCounts)
            .task("csrReturn", CrossBridgeSystem::csrScan, d.returnScanCounts, d.returnedMon, d.returnedOffsets)
            .task("applyDeath", DepolySystem::applyDeath, f.filState, f.monomerCount, sc.nuc.seedNode,
                    f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide, f.brownTransScale, f.brownRotScale, d.deathFlag, d.depolyCounts)
            .task("drag", GrowthSystem::recomputeDrag, f.monomerCount, f.segLength, f.end1NbrSlot, f.end2NbrSlot,
                    f.bTransGam, f.bRotGam, f.bTransDiff, f.bRotDiff, d.dragParams, d.depolyCounts)
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
        addW("depoly.depoly", pad(C)); addS("depoly.csrReturn"); addW("depoly.applyDeath", pad(C)); addW("depoly.drag", pad(C));
        addW("depoly.zero", pad(C)); addW("depoly.chain", pad(C)); addW("depoly.tether", pad(C));
        addW("depoly.integrate", pad(C)); addW("depoly.derive", pad(C));
        return new TornadoExecutionPlan(tg.snapshot());
    }
    static void runGpu(Scene sc, int M, boolean firesEvery) {
        TornadoExecutionPlan plan = buildPlan(sc);
        FilamentStore f = sc.fil; DepolyStore d = sc.depoly;
        for (int t = 0; t < M; t++) {
            f.setCounts(t, SEED);
            d.setCounts(t, SEED, firesEvery || d.firesAt(t));
            d.refreshRate(true);
            TornadoExecutionResult res = plan.withGridScheduler(sched).execute();
            if (t == M - 1) res.transferToHost(f.coord, f.uVec, f.monomerCount, f.filState,
                    f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide, sc.nuc.seedNode);
        }
    }

    // ============================================================== 0. behavior — shrink + die
    static boolean checkBehavior(double dt) {
        System.out.println("--- 0. behavior (filaments shrink at the pointed end and DIE; slots freed) ---");
        int N = 64, filCap = 128;
        Scene sc = build(0, N, filCap, dt, 12, 1.0e30, false, false, TEST_OFFRATE);   // free pointed-tip filaments
        int act0 = countActive(sc.fil); double con0 = contour(sc.fil); long m0 = sumMonomers(sc.fil);
        runDepolyOnly(sc, 400, true, true);   // each iter = one cadence; P=0.1 ⇒ ~40 removals ⇒ all die (12→<3)
        int act1 = countActive(sc.fil); double con1 = contour(sc.fil); long m1 = sumMonomers(sc.fil);
        boolean shrankThenDied = act1 < act0 && con1 < con0 && m1 < m0;
        boolean freed = (sc.fil.n - act1) > (sc.fil.n - act0);   // FREE slots increased
        boolean ok = shrankThenDied && freed && act1 == 0;        // all 64 died
        System.out.printf("  active %d → %d; contour %.4f → %.4f µm; Σmonomer %d → %d; FREE slots %d → %d => %s%n",
                act0, act1, con0, con1, m0, m1, sc.fil.n - act0, sc.fil.n - act1, ok ? "PASS" : "*FAIL*");
        System.out.println("  (pointed-end depoly removes monomers; at monomerCount<3 the filament dies and its slot is freed.)");
        System.out.println("  => " + (ok ? "PASS" : "*FAIL*") + "\n");
        return ok;
    }

    // ============================================================== 1. conservation EXACT (the hard gate)
    static boolean checkConservation(double dt) {
        System.out.println("--- 1. conservation EXACT (pool + Σ monomerCount = const), integer monomer units ---");
        boolean ok = true;
        // (a) depoly-only: Σmonomer(now) + totalReturned == Σmonomer(init); pool conc rose by returned·µMper
        {
            int N = 64, filCap = 128;
            Scene sc = build(0, N, filCap, dt, 12, Constants.actinConcInit, false, false, TEST_OFFRATE);
            long F0 = sumMonomers(sc.fil);
            double conc0 = sc.depoly.pool.conc();
            runDepolyOnly(sc, 400, true, true);
            long Fnow = sumMonomers(sc.fil);
            long ret = sc.depoly.pool.totalReturned();
            double uMper = sc.depoly.pool.uMPerMonomer();
            boolean intExact = (Fnow + ret == F0) && ret > 0;
            boolean concExact = Math.abs((sc.depoly.pool.conc() - conc0) - ret * uMper) < 1e-9;
            System.out.printf("  (a) depoly-only: F0=%d, Fnow=%d, returned=%d ⇒ Fnow+returned==F0: %s; Δconc==returned·µMper: %s%n",
                    F0, Fnow, ret, intExact, concExact);
            ok &= intExact && concExact;
        }
        // (b) grow + depoly combined: Fnow == F0 + totalTaken − totalReturned (both directions active)
        {
            int nNodes = 16, filCap = 4096;
            Scene sc = build(nNodes, nNodes, filCap, dt, 10, Constants.actinConcInit, true, false, TEST_OFFRATE);
            long F0 = sumMonomers(sc.fil);
            // full per-step loop, both growth + depoly on, Brownian off, cadence-gated
            runCpu(sc, 6000, true, true, false, false);
            long Fnow = sumMonomers(sc.fil);
            long taken = sc.grow.pool.totalTaken(), ret = sc.grow.pool.totalReturned();
            boolean intExact = (Fnow == F0 + taken - ret) && taken > 0 && ret > 0;
            System.out.printf("  (b) grow+depoly: F0=%d, taken=%d, returned=%d, Fnow=%d ⇒ Fnow==F0+taken−returned: %s%n",
                    F0, taken, ret, Fnow, intExact);
            System.out.printf("      (both directions exercised: growth took %d, turnover returned %d monomers)%n", taken, ret);
            ok &= intExact;
        }
        System.out.println("  => " + (ok ? "PASS" : "*FAIL*") + "\n");
        return ok;
    }

    // ============================================================== 2. slot recycle (death → same-step realloc)
    static boolean checkSlotRecycle(double dt) {
        System.out.println("--- 2. slot-recycle (a slot freed by death this cadence is RECLAIMED same-step by a split) ---");
        // filCap=4, ALL ACTIVE (no free slots): slot 0 = a free filament at monomerCount=2 (dies this cadence);
        // slot 1 = a node tip at monomerCount=64 with end1 bonded to slot 2 (so depoly skips it ⇒ it SPLITS, needs
        // 1 free slot); slots 2,3 = ACTIVE pointed tips (shrink but survive, occupy the array).
        Scene sc = build(2, 4, 4, dt, 32, 1.0e30, true, false, TEST_OFFRATE);
        FilamentStore f = sc.fil;
        // slot 0: a free pointed-tip filament at M=2 (will die this cadence)
        sc.nuc.seedNode.set(0, -1);
        f.end1NbrSlot.set(0, -1); f.end1NbrSide.set(0, -1); f.end2NbrSlot.set(0, -1); f.end2NbrSide.set(0, -1);
        f.monomerCount.set(0, 2);
        // slot 1: node tip at M=64; end1 ↔ slot 2.end2 (interior on end1 ⇒ depoly skips it; markSplits fires)
        f.monomerCount.set(1, 64);
        f.end1NbrSlot.set(1, 2); f.end1NbrSide.set(1, 1);   // slot1.end1 → slot2.end2
        f.end2NbrSlot.set(2, 1); f.end2NbrSide.set(2, 0);   // slot2.end2 → slot1.end1 (reciprocal)
        f.monomerCount.set(2, 32); f.monomerCount.set(3, 32);
        DragTensorSystem.run(f);
        int freeBefore = f.n - countActive(f);            // 0 (all ACTIVE)
        // one cadence: depoly (slot 0 dies → freed) THEN growth split (slot 1 needs a free slot → reclaims slot 0)
        depolyBlock(sc, 0, true, true, true);
        boolean slot0Dead = f.filState.get(0) < 0;
        growthBlock(sc, 0, true, true);
        int child = f.end1NbrSlot.get(1);                 // slot 1's child after the split
        boolean reclaimed = slot0Dead && child == 0 && f.filState.get(0) >= 0 && f.monomerCount.get(0) == 32;
        System.out.printf("  free slots before=%d; slot0 died=%s; slot1 split child landed in slot %d (== the freed slot 0): %s%n",
                freeBefore, slot0Dead, child, reclaimed ? "ok" : "*FAIL*");
        System.out.println("  (death markFree → the scan-rank free-list (rebuilt same cadence) → the split allocate reclaims it.)");
        System.out.println("  => " + (reclaimed ? "PASS" : "*FAIL*") + "\n");
        return reclaimed;
    }

    // ============================================================== 3. link-break (pointed-tip + interior death)
    static boolean checkLinkBreak(double dt) {
        System.out.println("--- 3. death link-break (pointed-tip shortens chain; INTERIOR death ⇒ two valid sub-chains) ---");
        boolean ok = true;
        // Build a linear 3-segment chain A(0)—B(1)—C(2): A.end1↔B.end2, B.end1↔C.end2 (C is the pointed tip).
        // (a) pointed-tip death: kill C ⇒ B becomes the new pointed tip (B.end1 free), A—B valid.
        {
            Scene sc = chain3(dt);
            FilamentStore f = sc.fil;
            sc.depoly.deathFlag.set(2, 1);   // mark C dead directly (exercise applyDeath)
            DepolySystem.applyDeath(f.filState, f.monomerCount, sc.nuc.seedNode,
                    f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide,
                    f.brownTransScale, f.brownRotScale, sc.depoly.deathFlag, sc.depoly.depolyCounts);
            boolean cDead = f.filState.get(2) < 0;
            boolean bNewTip = f.end1NbrSlot.get(1) == -1;                 // B's end1 (was C) now free ⇒ new pointed tip
            boolean abIntact = f.end1NbrSlot.get(0) == 1 && f.end2NbrSlot.get(1) == 0;  // A—B still reciprocal
            boolean a = cDead && bNewTip && abIntact;
            System.out.printf("  (a) pointed-tip death (C): C dead=%s, B new pointed tip=%s, A—B intact=%s => %s%n",
                    cDead, bNewTip, abIntact, a ? "ok" : "*FAIL*");
            ok &= a;
        }
        // (b) INTERIOR death: kill B ⇒ two valid sub-chains {A} and {C}, no dangling pointer to B.
        {
            Scene sc = chain3(dt);
            FilamentStore f = sc.fil;
            sc.depoly.deathFlag.set(1, 1);   // mark interior B dead
            DepolySystem.applyDeath(f.filState, f.monomerCount, sc.nuc.seedNode,
                    f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide,
                    f.brownTransScale, f.brownRotScale, sc.depoly.deathFlag, sc.depoly.depolyCounts);
            boolean bDead = f.filState.get(1) < 0;
            boolean aFree = f.end1NbrSlot.get(0) == -1;                   // A's link to B gone (A a valid free end)
            boolean cFree = f.end2NbrSlot.get(2) == -1;                   // C's link to B gone (C a valid free end)
            boolean noDangle = f.end2NbrSlot.get(1) == -1 && f.end1NbrSlot.get(1) == -1;  // B fully unlinked
            boolean b = bDead && aFree && cFree && noDangle;
            System.out.printf("  (b) interior death (B): B dead=%s ⇒ {A} free-ended=%s, {C} free-ended=%s, B unlinked=%s => %s%n",
                    bDead, aFree, cFree, noDangle, b ? "ok" : "*FAIL*");
            System.out.println("      (two valid reciprocal sub-chains — the general link-break Stage 2's whole-segment dissolve relies on.)");
            ok &= b;
        }
        System.out.println("  => " + (ok ? "PASS" : "*FAIL*") + "\n");
        return ok;
    }
    /** A linear 3-segment chain A(0)—B(1)—C(2) (end-to-end via end1↔end2), all ACTIVE; C the pointed tip. */
    static Scene chain3(double dt) {
        Scene sc = build(0, 3, 8, dt, 32, 1.0e30, false, false, TEST_OFFRATE);
        FilamentStore f = sc.fil;
        // A.end1 ↔ B.end2 ; B.end1 ↔ C.end2   (side: 0=neighbor.end1, 1=neighbor.end2)
        f.end1NbrSlot.set(0, 1); f.end1NbrSide.set(0, 1);   // A.end1 → B.end2
        f.end2NbrSlot.set(1, 0); f.end2NbrSide.set(1, 0);   // B.end2 → A.end1
        f.end1NbrSlot.set(1, 2); f.end1NbrSide.set(1, 1);   // B.end1 → C.end2
        f.end2NbrSlot.set(2, 1); f.end2NbrSide.set(2, 0);   // C.end2 → B.end1
        return sc;
    }

    // ============================================================== 5. no-op when off
    static boolean checkNoOpWhenOff(double dt) {
        System.out.println("--- 5. no-op-when-off (depoly OFF ⇒ bit-identical to a no-depoly baseline) ---");
        int N = 8, M = 5000, filCap = 64;
        Scene a = build(N, N, filCap, dt, 20, Constants.actinConcInit, true, false, TEST_OFFRATE);  // depoly pipeline, OFF
        Scene b = build(N, N, filCap, dt, 20, Constants.actinConcInit, true, false, TEST_OFFRATE);  // baseline, no depoly tasks
        runCpu(a, M, false, false, false, false);    // depolyOn=false ⇒ fires always 0 ⇒ depoly kernels no-op
        runBaseline(b, M, false);
        boolean bit = true;
        for (int s = 0; s < filCap; s++) {
            if (a.fil.monomerCount.get(s) != b.fil.monomerCount.get(s)) bit = false;
            if (a.fil.filState.get(s) != b.fil.filState.get(s)) bit = false;
            if (a.nuc.seedNode.get(s) != b.nuc.seedNode.get(s)) bit = false;
            if (a.fil.end1NbrSlot.get(s) != b.fil.end1NbrSlot.get(s)) bit = false;
            if (a.fil.end2NbrSlot.get(s) != b.fil.end2NbrSlot.get(s)) bit = false;
        }
        double dCoord = maxDiff(a.fil.coord, b.fil.coord);
        boolean ok = bit && dCoord == 0.0;
        System.out.printf("  depoly-OFF vs baseline after %d steps: lifecycle bit-identical=%s, max|Δcoord|=%.2e (==0) => %s%n",
                M, bit, dCoord, ok ? "PASS" : "*FAIL*");
        System.out.println("  => " + (ok ? "PASS" : "*FAIL*") + "\n");
        return ok;
    }

    // ============================================================== 6. rate wiring (derived probability)
    static boolean checkRateWiring(double dt) {
        System.out.println("--- 6. rate-wiring (empirical P ≈ offRate·biochemDeltaT at the DEFAULT rate; cadence lock-step) ---");
        // DEFAULT rate kATPOff1=0.8/s ⇒ P=8e-4/fire. monomerInit high so no death contaminates the count.
        int N = 256, nCad = 20000, filCap = 256;
        Scene sc = build(0, N, filCap, dt, 60, 1.0e30, false, false, Constants.kATPOff1);
        long m0 = sumMonomers(sc.fil);
        runDepolyOnly(sc, nCad, true, false);          // no death (60 ≫ removals), no pool gate
        long removed = m0 - sumMonomers(sc.fil);
        double Pemp = removed / ((double) N * nCad);
        double Pth = Constants.kATPOff1 * Constants.biochemDeltaT;
        double rel = Math.abs(Pemp - Pth) / Pth;
        boolean noDeath = countActive(sc.fil) == N;    // none died ⇒ clean count
        boolean ok = rel < 0.05 && noDeath;
        System.out.printf("  %d monomers removed over %d tips × %d cadences; P_emp=%.5f vs offRate·bcΔt=%.5f (rel %.1f%%), noDeath=%s => %s%n",
                removed, N, nCad, Pemp, Pth, 100 * rel, noDeath, ok ? "PASS" : "*FAIL*");
        System.out.println("  (the per-event probability is DERIVED P=offRate·biochemDeltaT each cadence — no stale copy; biochem cadence = round(biochemDeltaT/dt).)");
        System.out.println("  => " + (ok ? "PASS" : "*FAIL*") + "\n");
        return ok;
    }

    // ============================================================== 4. CPU≡GPU
    static boolean checkCpuGpu(double dt) {
        if (cpu) { System.out.println("--- 4. CPU≡GPU: skipped (-cpu) ---\n"); return true; }
        System.out.println("--- 4. CPU≡GPU (depoly + death + recomputeDrag + integrate; Brownian off ⇒ lifecycle bit-identical) ---");
        int N = 64, M = 300, filCap = 128;
        // free pointed-tip filaments at monomerInit=5; fire every step (P=0.1) ⇒ many shrink + die over 300 steps
        Scene g = build(0, N, filCap, dt, 5, 1.0e30, false, false, TEST_OFFRATE);
        Scene c = build(0, N, filCap, dt, 5, 1.0e30, false, false, TEST_OFFRATE);
        runGpu(g, M, true);
        runCpu(c, M, true, false, false, true);        // depoly on, growth off, Brownian off, fires every step
        int monMis = 0, stateMis = 0, linkMis = 0;
        for (int s = 0; s < filCap; s++) {
            if (g.fil.monomerCount.get(s) != c.fil.monomerCount.get(s)) monMis++;
            if (g.fil.filState.get(s) != c.fil.filState.get(s)) stateMis++;
            if (g.fil.end1NbrSlot.get(s) != c.fil.end1NbrSlot.get(s) || g.fil.end2NbrSlot.get(s) != c.fil.end2NbrSlot.get(s)) linkMis++;
        }
        double dCoord = maxDiff(g.fil.coord, c.fil.coord);
        int actG = countActive(g.fil), actC = countActive(c.fil);
        boolean died = actG < N;                        // confirm deaths actually happened (exercises the death path)
        boolean ok = monMis == 0 && stateMis == 0 && linkMis == 0 && dCoord < 5e-5 && actG == actC && died;
        System.out.printf("  active GPU=%d CPU=%d (died: %s); mismatches monomer=%d state=%d link=%d (all 0 ⇒ bit-identical); max|Δcoord|=%.2e µm (<5e-5) => %s%n",
                actG, actC, died, monMis, stateMis, linkMis, dCoord, ok ? "PASS" : "*FAIL*");
        System.out.println("  (wang-hash depoly decisions + the integer death/markFree/link-break are deterministic ⇒ bit-identical; coord float32 last-bit.)");
        System.out.println("  => " + (ok ? "PASS" : "*FAIL*") + "\n");
        return ok;
    }

    // ============================================================== utils
    static double maxDiff(FloatArray a, FloatArray b) { double m = 0; for (int i = 0; i < a.getSize(); i++) m = Math.max(m, Math.abs(a.get(i) - b.get(i))); return m; }
    static int pad(int n) { return ((n + B - 1) / B) * B; }
    static void addW(String name, int gg) { WorkerGrid w = new WorkerGrid1D(gg); w.setLocalWork(B, 1, 1); sched.addWorkerGrid(name, w); }
    static void addS(String name) { WorkerGrid w = new WorkerGrid1D(1); w.setLocalWork(1, 1, 1); sched.addWorkerGrid(name, w); }
}
