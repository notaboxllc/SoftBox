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
 * Increment 7 DEAD-SLOT-REUSE FIX — the coexistence of TURNOVER (frees slots) and NUCLEATION (claims slots), the
 * ring precondition + the validation of the fix. THE BUG (recap, INC7_STAGE1_FINDINGS.md §"Reused-slot
 * monomerCount"): the STATIC-B1 invariant was "every slot pre-init to a fixed seed (monomerCount=actinSeed,
 * segLength, drag), so nucleation's allocate writes only pose+Brownian+state." Once turnover frees+recycles a
 * slot, that breaks — DepolySystem.applyDeath wipes monomerCount→0 (and depoly shrank segLength), and aging froze
 * the corpse's nucFrac at a mostly-ADP composition. A nucleation-reused dead slot therefore births a filament with
 * monomerCount=0 (a zero-length, conservation-violating phantom) and a stale-ADP nucleotide composition. Split
 * reuse was always fine (splitWire sets the child's monomerCount); ONLY nucleation assumed the pre-init value.
 *
 * THE FIX (additive, mirrors the split precedent splitWire+splitInheritNuc): two new post-allocate kernels run on
 * accepted nucleation requests (the SAME rank→slot iteration as tagSeeds, one writer per born slot ⇒ race-free):
 *   NodeNucleationSystem.initNewborn   — monomerCount = actinSeed, segLength = (actinSeed+1)·actinMonoRadius
 *   AgingSystem.nucleateFreshAtp       — nucFrac = (1,0,0) (fresh, unhydrolyzed ATP)
 * Everything else a newborn depends on is already correct: pose/uVec/yVec + brownTransScale/brownRotScale +
 * filState=ACTIVE (allocate), seedNode (tagSeeds), end1/end2 neighbors=-1 (applyDeath leaves a free rod; scene
 * build inits -1), forceSum/torqueSum (zeroed each step), drag (GrowthSystem.recomputeDrag re-derives it from
 * monomerCount each cadence — and an at-end seed and the at-end corpse both clamp to the SAME std drag, so even the
 * pre-recompute transient is std-correct). The AUDIT therefore finds exactly TWO stale-inherited newborn fields
 * (monomerCount, nucFrac) plus the geometry-derived segLength (set with monomerCount for the same-cadence
 * seedTether consumer) — NOT a broad newborn-init; two scattered sets suffice (see the findings).
 *
 * THE COEXISTENCE ASSAY. A single node nucleates seeds (forced churn: pNuc=1/cadence, forminsPerNode=filCap) while
 * aging + nucleotide-dependent depoly + death recycle those very slots, run long enough that freed slots ARE
 * reused by nucleation. The runner snapshots filState each cadence to detect dead-slot reuses (ACTIVE→FREE→ACTIVE)
 * and validates every reused newborn. TEST rates (fast aging + fast death) so corpses are clearly aged (a stale
 * nucFrac is mostly-ADP ⇒ the reset is observable) and reuse happens on a short horizon. Default-OFF discipline:
 * no production assay runs this; the fix's new kernels are no-ops where they are not wired (existing nucleation
 * harnesses, which never recycle a slot, stay byte-unchanged).
 *
 * Gates (both runners):
 *   1 newborn        — coexistence with the FIX: ≥1 dead-slot reuse occurs, and EVERY nucleation-born slot is a
 *                      correct fresh seed (monomerCount=actinSeed, segLength=seedLen, nucFrac=(1,0,0), seedNode≥0,
 *                      neighbors=-1, ACTIVE). No zero-length / stale-composition newborn; no ACTIVE monomerCount 0.
 *   2 conservation   — EXACT integer ledger (pool + Σ monomerCount over ACTIVE slots = const) through the whole
 *                      coexistence — with both nucleation TAKING and depoly RETURNING and the slot recycle.
 *   3 fix-OFF ctrl   — WITHOUT the fix, a dead-slot reuse births monomerCount=0 AND a stale (f_ADP>0) nucFrac, and
 *                      conservation BREAKS by actinSeed·(#reuse). Documents the bug the fix closes.
 *   4 CPU≡GPU        — the coexistence lifecycle bit-identical (filState/monomerCount/seedNode), nucFrac float32
 *                      last-bit; reuse-count + conservation + active-count equal.
 *   5 regression     — turnover-only (no nucleation) and nucleation-only (no turnover) sub-modes reproduce their
 *                      standalone behavior (the fix only ADDS sets on the nucleation allocate; it is inert otherwise).
 */
public final class DeadSlotReuseHarness {

    static final int B = 64;
    static GridScheduler sched;
    static boolean cpu = false;
    static final int SEED = 0x07DEAD;
    static final double BOX_VOL = 8.0 * 4.0 * 0.6;     // µm³ (µM-per-monomer; matches the turnover harnesses)
    // TEST rates (per biochem cadence): fast death (so slots churn) + fast aging (so corpses are clearly ADP-rich).
    static final float TEST_PDEPOLY = 0.05f;           // P_depoly/cadence (pATP=pADP ⇒ rate independent of f_ADP)
    static final float TEST_PH = 0.05f, TEST_PD = 0.05f; // aging pH/pD per cadence
    static final float PNUC = 1.0f;                    // force a birth every cadence with a free slot (under cap)

    public static void main(String[] args) {
        double dt = 1.0e-5;
        for (String a : args) if (a.equals("-cpu")) cpu = true;
        System.out.println("=== Soft Box increment 7 — DEAD-SLOT REUSE FIX: turnover + nucleation coexistence ===");
        System.out.println("nucleation FULLY initializes a recycled (dead) slot: monomerCount=actinSeed + nucFrac=fresh ATP");
        System.out.println("(+ segLength for the same-cadence seedTether); drag via recomputeDrag. The ring precondition.");
        System.out.println("v1 specifics: actinSeed=3, biochemDeltaT=1e-3 s; TEST rates (fast death/aging) for the short horizon.");
        System.out.println("runner: " + (cpu ? "CPU only (-cpu)" : "GPU + CPU cross-check") + ", dt=" + dt + "\n");

        boolean g1 = checkNewborn(dt);
        boolean g2 = checkConservation(dt);
        boolean g3 = checkFixOffControl(dt);
        boolean g5 = checkRegression(dt);
        boolean g4 = checkCpuGpu(dt);

        boolean ok = g1 && g2 && g3 && g4 && g5;
        System.out.println();
        System.out.println("=== DEAD-SLOT REUSE FIX VALIDATION " + (ok ? "PASS" : "FAIL") + " ===");
        if (!ok) { System.out.println("BAIL-OUT: a gate failed. Commit nothing."); System.exit(1); }
    }

    // ============================================================== scene
    static final class Scene {
        NodeStore nodeStore; FilamentStore fil; NodeNucleationStore nuc; DepolyStore depoly; AgingStore aging;
        double seedLen; int filCap; double dt;
    }

    /** Build a 1-node coexistence scene: filCap small slots, the node forced to nucleate into every free slot,
     *  aging + nucleotide-dependent depoly (TEST rates), a generous pool (never dry). All slots start FREE. */
    static Scene build(int filCap, double dt, double pool0) {
        Scene sc = new Scene();
        sc.filCap = filCap; sc.dt = dt;
        double seedLen = (Constants.actinSeed + 1) * Constants.actinMonoRadius;
        sc.seedLen = seedLen;

        NodeStore ns = new NodeStore(1, 1);
        ns.node.setCoord(0, 0f, 0f, 0f);
        ns.node.uVec.init(0f); ns.node.yVec.init(0f);
        ns.node.setUVec(0, 1f, 0f, 0f); ns.node.setYVec(0, 0f, 1f, 0f);
        ns.initNodeDrag();

        // FilamentStore: filCap slots, reqCap=1 (one node ⇒ one request/cadence). Every slot pre-init as a seed
        // (segLength+drag from monomerCount=actinSeed), then markFree (inert, FREE). The fix means a RECYCLED slot
        // is re-initialised regardless of what it carries; the pre-init only matters for never-recycled slots.
        FilamentStore f = new FilamentStore(filCap, 1);
        for (int s = 0; s < filCap; s++) f.monomerCount.set(s, Constants.actinSeed);
        DragTensorSystem.run(f);
        f.setParams(dt, Constants.brownianForceMag());
        f.setChainParams();
        f.setBirthParams(0.0, 0.0);   // Brownian off (deterministic conservation / reuse); seeds tethered to the node
        for (int s = 0; s < filCap; s++) { f.setCoord(s, 0f, 0f, 0f); f.setUVec(s, 1f, 0f, 0f); f.setYVec(s, 0f, 1f, 0f); f.markFree(s); }
        f.setBirthRequestCount(1);

        NodeNucleationStore nuc = new NodeNucleationStore(1, filCap, Constants.actinSeed, pool0, BOX_VOL, 1.0);
        nuc.setNucParams(Constants.kNodeNuc, dt, seedLen, filCap);   // forminsPerNode = filCap (fill every slot)
        nuc.nucParams.set(0, PNUC);                                  // force nucleation every cadence (TEST)
        nuc.setTetherParams(Constants.fracMove, dt);
        nuc.setDissolveParams(0.0, dt);                             // no dissolve — death drives the churn

        DepolyStore d = new DepolyStore(filCap, Constants.kATPOff1, dt, nuc.pool);  // share the pool (seam #2)

        AgingStore ag = new AgingStore(filCap);
        for (int s = 0; s < filCap; s++) ag.setATP(s);              // pre-init composition (irrelevant for FREE slots)

        sc.nodeStore = ns; sc.fil = f; sc.nuc = nuc; sc.depoly = d; sc.aging = ag;
        return sc;
    }

    static int countActive(FilamentStore f) { int c = 0; for (int s = 0; s < f.n; s++) if (f.filState.get(s) >= 0) c++; return c; }
    /** Σ monomerCount over ACTIVE slots only (a FREE slot's monomerCount is a phantom — its monomers are in the pool). */
    static long sumActiveMonomers(FilamentStore f) { long m = 0; for (int s = 0; s < f.n; s++) if (f.filState.get(s) >= 0) m += f.monomerCount.get(s); return m; }

    // ============================================================== CPU coexistence cadence
    /** One biochem cadence: age → depolyProxy → death(+return) → nucleation(emit→alloc→tag→[fix]→tether) → take →
     *  integrate → derive → recomputeDrag. fix=false skips the two fix kernels (the fix-OFF control). nucleationOn /
     *  turnoverOn gate the two halves (the regression sub-modes). */
    static void cadenceCpu(Scene sc, int t, boolean fix, boolean nucleationOn, boolean turnoverOn) {
        FilamentStore f = sc.fil; NodeNucleationStore nuc = sc.nuc; DepolyStore d = sc.depoly; AgingStore ag = sc.aging;
        RigidRodBody node = sc.nodeStore.node;

        // TEST-rate params (constant across the run): set directly (not from Constants) — agingParams/depolyRateParams.
        ag.agingParams.set(0, TEST_PH); ag.agingParams.set(1, TEST_PD); ag.setFires(true);
        ag.depolyRateParams.set(0, TEST_PDEPOLY); ag.depolyRateParams.set(1, TEST_PDEPOLY);
        d.setCounts(t, SEED, turnoverOn);
        nuc.setCounts(t, SEED);

        // --- aging + turnover (frees slots) ---
        if (turnoverOn) {
            AgingSystem.age(f.filState, ag.nucFrac, ag.agingParams, ag.agingCounts);
            DepolySystem.depolyProxy(f.filState, f.monomerCount, f.coord, f.uVec, f.end1NbrSlot, ag.nucFrac,
                    d.returnedMon, d.deathFlag, d.depolyParams, ag.depolyRateParams, d.depolyCounts);
            CrossBridgeSystem.csrScan(d.returnScanCounts, d.returnedMon, d.returnedOffsets);
            DepolySystem.applyDeath(f.filState, f.monomerCount, nuc.seedNode, f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide,
                    f.brownTransScale, f.brownRotScale, d.deathFlag, d.depolyCounts);
            d.returnPoolForDepoly();   // pool.put(nReturned)  [conservation: returns]
        }

        // --- nucleation (claims slots — same cadence, after death frees them) ---
        if (nucleationOn) {
            nuc.refreshPoolGate();
            NodeNucleationSystem.countBoundFil(nuc.seedNode, nuc.nodeBoundFil, nuc.nucCounts);
            NodeNucleationSystem.emit(node.coord, nuc.nodeBoundFil, f.acceptFlag, f.reqCoord, f.reqUVec, f.reqYVec, nuc.nucParams, nuc.nucCounts);
            FilamentBirthSystem.freeFlags(f.filState, f.freeCount, f.allocCounts);
            CrossBridgeSystem.csrScan(f.freeScanCounts, f.freeCount, f.freeOffsets);
            FilamentBirthSystem.freeScatter(f.filState, f.freeOffsets, f.freeList, f.allocCounts);
            CrossBridgeSystem.csrScan(f.rankScanCounts, f.acceptFlag, f.rankOffsets);
            FilamentBirthSystem.allocate(f.reqCoord, f.reqUVec, f.reqYVec, f.rankOffsets, f.freeList, f.freeOffsets,
                    f.coord, f.uVec, f.yVec, f.brownTransScale, f.brownRotScale, f.filState, f.birthParams, f.allocCounts);
            NodeNucleationSystem.tagSeeds(f.rankOffsets, f.freeList, f.freeOffsets, nuc.seedNode, f.allocCounts);
            if (fix) {  // THE DEAD-SLOT-REUSE FIX: fully initialise the newborn (never inherit a corpse)
                NodeNucleationSystem.initNewborn(f.rankOffsets, f.freeList, f.freeOffsets, f.monomerCount, f.segLength, nuc.seedParams, f.allocCounts);
                AgingSystem.nucleateFreshAtp(f.rankOffsets, f.freeList, f.freeOffsets, ag.nucFrac, f.allocCounts);
            }
            nuc.depletePoolForBirths(f);   // pool.take(births·actinSeed)  [conservation: takes]
        }

        // --- mechanics (a force step; validates the seedTether-reads-segLength path) ---
        ChainBendingForceSystem.zeroAccumulators(f.forceSum, f.torqueSum, f.counts);
        NodeNucleationSystem.seedTether(f.coord, f.uVec, f.segLength, f.bTransGam, f.forceSum, f.torqueSum,
                node.coord, sc.nodeStore.nodeInvTransY, nuc.seedNode, nuc.tetherParams);
        RigidRodLangevinIntegrationSystem.integrate(f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum,
                f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts);
        DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
        GrowthSystem.recomputeDrag(f.monomerCount, f.segLength, f.end1NbrSlot, f.end2NbrSlot,
                f.bTransGam, f.bRotGam, f.bTransDiff, f.bRotDiff, d.dragParams, d.depolyCounts);
    }

    /** A coexistence run on the CPU, with dead-slot-reuse detection + per-newborn validation. */
    static final class Result {
        int reuseCount;        // # of slots reborn-by-nucleation after having died at least once
        int births;            // total nucleation births
        int badNewborn;        // # of nucleation-born slots that were NOT a correct fresh seed (the bug count)
        boolean conservationOk; // integer ledger held EVERY sampled cadence
        long activeMon; int active; double conc;
    }

    static Result runCpu(Scene sc, int nCad, boolean fix, boolean nucleationOn, boolean turnoverOn) {
        FilamentStore f = sc.fil; NodeNucleationStore nuc = sc.nuc;
        int C = f.n;
        boolean[] everDied = new boolean[C];
        Result r = new Result(); r.conservationOk = true;
        long monInit = sumActiveMonomers(f);   // 0 (all FREE at build)
        for (int t = 0; t < nCad; t++) {
            cadenceCpu(sc, t, fix, nucleationOn, turnoverOn);
            recordCadence(sc, everDied, r, fix);
            // conservation (integer ledger) — sample every 200 cadences + the last
            if (t % 200 == 0 || t == nCad - 1) {
                long Fnow = sumActiveMonomers(f);
                long taken = nuc.pool.totalTaken(), ret = nuc.pool.totalReturned();
                if (Fnow != monInit + taken - ret) r.conservationOk = false;
            }
        }
        r.activeMon = sumActiveMonomers(f); r.active = countActive(f); r.conc = nuc.pool.conc();
        return r;
    }

    /** Per-cadence reuse detection from the kernel OUTPUTS (catches SAME-cadence death+rebirth, the 5c-i
     *  death→free-list→allocate pattern that a filState boundary-snapshot misses): (1) mark everDied from THIS
     *  cadence's deathFlag (still live until the next depoly zeroes it); (2) recover the slot(s) born this cadence
     *  from the allocator outputs (reqCap=1 ⇒ ≤1 birth: accepted request r ⇒ slot=freeList[rankOffsets[r]] when
     *  rank<nFree) and, since everDied now includes a same-cadence death, classify it as a reuse + validate. */
    static void recordCadence(Scene sc, boolean[] everDied, Result r, boolean fix) {
        FilamentStore f = sc.fil; DepolyStore d = sc.depoly; int C = f.n;
        for (int s = 0; s < C; s++) if (d.deathFlag.get(s) != 0) everDied[s] = true;
        int K = f.allocCounts.get(1), nFree = f.freeOffsets.get(C);
        for (int rr = 0; rr < K; rr++) {
            int rank = f.rankOffsets.get(rr);
            if (!(f.rankOffsets.get(rr + 1) > rank) || rank >= nFree) continue;   // not a birth this cadence
            int slot = f.freeList.get(rank);
            r.births++;
            if (everDied[slot]) r.reuseCount++;
            if (fix) { if (!newbornOk(sc, slot)) r.badNewborn++; }
            else if (f.monomerCount.get(slot) == 0) r.badNewborn++;   // fix-off: the monomerCount-0 phantom (the bug)
        }
    }

    /** A nucleation-born slot is a correct fresh seed: monomerCount=actinSeed, segLength=seedLen, nucFrac=(1,0,0),
     *  tethered to the node, a free rod (no chain neighbors), ACTIVE. */
    static boolean newbornOk(Scene sc, int s) {
        FilamentStore f = sc.fil; AgingStore ag = sc.aging; int C = f.n;
        boolean ok = f.monomerCount.get(s) == Constants.actinSeed
                && Math.abs(f.segLength.get(s) - sc.seedLen) < 1e-7
                && Math.abs(ag.nucFrac.get(s) - 1f) < 1e-6
                && ag.nucFrac.get(C + s) == 0f && ag.nucFrac.get(2 * C + s) == 0f
                && sc.nuc.seedNode.get(s) >= 0
                && f.end1NbrSlot.get(s) < 0 && f.end2NbrSlot.get(s) < 0
                && f.filState.get(s) >= 0;
        return ok;
    }

    // ============================================================== 1. newborn correctness
    static boolean checkNewborn(double dt) {
        System.out.println("--- 1. coexistence newborn correctness (FIX on; a dead slot reused by nucleation is a fresh seed) ---");
        Scene sc = build(8, dt, 1.0e6);
        Result r = runCpu(sc, 6000, true, true, true);
        boolean reuseOccurred = r.reuseCount > 0;
        long activeMonZeroBug = 0; for (int s = 0; s < sc.fil.n; s++) if (sc.fil.filState.get(s) >= 0 && sc.fil.monomerCount.get(s) == 0) activeMonZeroBug++;
        boolean ok = reuseOccurred && r.badNewborn == 0 && activeMonZeroBug == 0;
        System.out.printf("  births=%d, dead-slot reuses=%d, bad newborns=%d, ACTIVE-with-monomerCount-0=%d%n",
                r.births, r.reuseCount, r.badNewborn, activeMonZeroBug);
        System.out.printf("  final: active=%d, ΣmonomerCount(active)=%d, [actin]=%.4f µM%n", r.active, r.activeMon, r.conc);
        System.out.println("  => reuse occurred + every reused newborn a correct fresh seed (actinSeed, fresh ATP, valid links) => "
                + (ok ? "PASS" : "*FAIL*") + "\n");
        return ok;
    }

    // ============================================================== 2. conservation through coexistence
    static boolean checkConservation(double dt) {
        System.out.println("--- 2. conservation EXACT through the coexistence (pool + Σ monomerCount = const; take + return + recycle) ---");
        Scene sc = build(8, dt, 1.0e6);
        Result r = runCpu(sc, 6000, true, true, true);
        // explicit final ledger
        long Fnow = sumActiveMonomers(sc.fil);
        long taken = sc.nuc.pool.totalTaken(), ret = sc.nuc.pool.totalReturned();
        boolean ledger = (Fnow == taken - ret);   // monInit = 0
        boolean ok = r.conservationOk && ledger && r.reuseCount > 0;
        System.out.printf("  reuses=%d; final Σmon(active)=%d == taken(%d) − returned(%d) = %d => %s%n",
                r.reuseCount, Fnow, taken, ret, taken - ret, ledger ? "exact" : "*MISMATCH*");
        System.out.printf("  sampled-throughout ledger held: %s%n", r.conservationOk ? "yes" : "*NO*");
        System.out.println("  => " + (ok ? "PASS" : "*FAIL*") + "\n");
        return ok;
    }

    // ============================================================== 3. fix-OFF control (the bug exposed)
    static boolean checkFixOffControl(double dt) {
        System.out.println("--- 3. fix-OFF control: WITHOUT the fix, a reused dead slot is a broken newborn + conservation breaks ---");
        Scene sc = build(8, dt, 1.0e6);
        Result r = runCpu(sc, 6000, false, true, true);   // fix OFF
        // the ledger should now FAIL (Fnow short by actinSeed per dead-slot reuse that stayed monomerCount 0)
        long Fnow = sumActiveMonomers(sc.fil);
        long taken = sc.nuc.pool.totalTaken(), ret = sc.nuc.pool.totalReturned();
        boolean ledgerBroken = (Fnow != taken - ret);
        // and there should be ACTIVE slots with monomerCount 0 (zero-length phantoms) and/or stale ADP nucFrac
        int zeroLen = 0, staleAdp = 0; int C = sc.fil.n;
        for (int s = 0; s < C; s++) if (sc.fil.filState.get(s) >= 0) {
            if (sc.fil.monomerCount.get(s) == 0) zeroLen++;
            if (sc.aging.nucFrac.get(2 * C + s) > 1e-3f) staleAdp++;   // inherited ADP, not reset to fresh ATP
        }
        boolean bugVisible = (zeroLen > 0) || ledgerBroken || (staleAdp > 0);
        boolean ok = r.reuseCount > 0 && bugVisible && ledgerBroken;
        System.out.printf("  reuses=%d; ledger Σmon(active)=%d vs taken−returned=%d => %s (deficit %d, ≈actinSeed·broken-reuses)%n",
                r.reuseCount, Fnow, taken - ret, ledgerBroken ? "BROKEN (expected)" : "*held — bug not exposed*", (taken - ret) - Fnow);
        System.out.printf("  ACTIVE zero-length(monomerCount 0)=%d, stale-ADP(f_ADP>0) newborns=%d%n", zeroLen, staleAdp);
        System.out.println("  => the fix closes a real bug (broken newborn + conservation violation without it) => "
                + (ok ? "PASS" : "*FAIL*") + "\n");
        return ok;
    }

    // ============================================================== 5. regression (sub-modes unchanged)
    static boolean checkRegression(double dt) {
        System.out.println("--- 5. regression: turnover-only and nucleation-only sub-modes (the fix is inert without coexistence) ---");
        // (a) turnover-only: pre-place active seeds, NO nucleation. With/without the fix must be bit-identical
        //     (initNewborn/nucleateFreshAtp only fire on a nucleation birth ⇒ never called here).
        Scene a1 = build(8, dt, 1.0e6); seedActive(a1, 8);
        Scene a2 = build(8, dt, 1.0e6); seedActive(a2, 8);
        Result rNoFix = runCpu(a1, 3000, false, false, true);
        Result rFix   = runCpu(a2, 3000, true,  false, true);
        boolean turnoverSame = lifecycleEqual(a1.fil, a2.fil, a1.aging, a2.aging)
                && rNoFix.births == 0 && a1.nuc.pool.totalReturned() == a2.nuc.pool.totalReturned();
        System.out.printf("  (a) turnover-only fix-on≡fix-off: %s (births=%d, active=%d≡%d, returned=%d≡%d)%n",
                turnoverSame ? "bit-identical" : "*DIFFER*", rNoFix.births, rNoFix.active, rFix.active,
                a1.nuc.pool.totalReturned(), a2.nuc.pool.totalReturned());
        // (b) nucleation-only (turnover off): no slot ever frees ⇒ no reuse; every birth into a fresh pre-init slot
        //     is correct WITH or WITHOUT the fix (the pre-init already = the fix's values). Newborns all-correct, 0 reuse.
        Scene b = build(8, dt, 1.0e6);
        Result rb = runCpu(b, 3000, true, true, false);
        boolean nucOnly = rb.reuseCount == 0 && rb.badNewborn == 0 && rb.births > 0 && rb.conservationOk;
        System.out.printf("  (b) nucleation-only: births=%d, reuses=%d (expect 0), bad newborns=%d, conservation=%s%n",
                rb.births, rb.reuseCount, rb.badNewborn, rb.conservationOk ? "exact" : "*broken*");
        boolean ok = turnoverSame && nucOnly;
        System.out.println("  => " + (ok ? "PASS" : "*FAIL*") + "\n");
        return ok;
    }

    /** Pre-place n ACTIVE seeds (for the turnover-only sub-mode): each a node-tethered fresh seed. */
    static void seedActive(Scene sc, int n) {
        FilamentStore f = sc.fil; AgingStore ag = sc.aging; int C = f.n;
        for (int s = 0; s < n && s < C; s++) {
            f.filState.set(s, FilamentStore.FIL_ACTIVE);
            f.monomerCount.set(s, Constants.actinSeed);
            f.setCoord(s, (float) (0.5 * sc.seedLen), 0f, 0f); f.setUVec(s, -1f, 0f, 0f); f.setYVec(s, 0f, 1f, 0f);
            f.brownTransScale.set(s, 0f); f.brownRotScale.set(s, 0f);
            f.end1NbrSlot.set(s, -1); f.end1NbrSide.set(s, -1); f.end2NbrSlot.set(s, -1); f.end2NbrSide.set(s, -1);
            sc.nuc.seedNode.set(s, 0);
            ag.setATP(s);
        }
        DragTensorSystem.run(f);
    }

    static boolean lifecycleEqual(FilamentStore a, FilamentStore b, AgingStore aa, AgingStore ab) {
        int C = a.n;
        for (int s = 0; s < C; s++) {
            if (a.filState.get(s) != b.filState.get(s)) return false;
            if (a.monomerCount.get(s) != b.monomerCount.get(s)) return false;
            for (int k = 0; k < 3; k++) if (aa.nucFrac.get(k * C + s) != ab.nucFrac.get(k * C + s)) return false;
        }
        return true;
    }

    // ============================================================== 4. CPU≡GPU
    static boolean checkCpuGpu(double dt) {
        System.out.println("--- 4. CPU≡GPU coexistence lifecycle (bit-identical filState/monomerCount/seedNode; nucFrac float32) ---");
        if (cpu) { System.out.println("  (skipped: -cpu)\n"); return true; }
        int nCad = 4000;
        Scene cpuS = build(8, dt, 1.0e6);
        Scene gpuS = build(8, dt, 1.0e6);
        Result rc = runCpu(cpuS, nCad, true, true, true);
        Result rg = runGpu(gpuS, nCad, true);
        int C = cpuS.fil.n;
        int mstate = 0, mon = 0, node = 0; float maxNuc = 0f;
        for (int s = 0; s < C; s++) {
            if (cpuS.fil.filState.get(s) != gpuS.fil.filState.get(s)) mstate++;
            if (cpuS.fil.monomerCount.get(s) != gpuS.fil.monomerCount.get(s)) mon++;
            if (cpuS.nuc.seedNode.get(s) != gpuS.nuc.seedNode.get(s)) node++;
            for (int k = 0; k < 3; k++) maxNuc = Math.max(maxNuc, Math.abs(cpuS.aging.nucFrac.get(k * C + s) - gpuS.aging.nucFrac.get(k * C + s)));
        }
        boolean lifecycleBit = mstate == 0 && mon == 0 && node == 0;
        boolean aggr = rc.reuseCount == rg.reuseCount && rc.active == rg.active && rc.activeMon == rg.activeMon
                && cpuS.nuc.pool.totalTaken() == gpuS.nuc.pool.totalTaken()
                && cpuS.nuc.pool.totalReturned() == gpuS.nuc.pool.totalReturned();
        boolean ok = lifecycleBit && aggr && maxNuc < 1e-5f && rg.badNewborn == 0;
        System.out.printf("  lifecycle mismatches: state=%d mon=%d seedNode=%d ; max|ΔnucFrac|=%.2e%n", mstate, mon, node, maxNuc);
        System.out.printf("  reuse CPU=%d GPU=%d ; active CPU=%d GPU=%d ; Σmon CPU=%d GPU=%d ; taken/ret CPU=%d/%d GPU=%d/%d%n",
                rc.reuseCount, rg.reuseCount, rc.active, rg.active, rc.activeMon, rg.activeMon,
                cpuS.nuc.pool.totalTaken(), cpuS.nuc.pool.totalReturned(), gpuS.nuc.pool.totalTaken(), gpuS.nuc.pool.totalReturned());
        System.out.println("  => " + (ok ? "PASS" : "*FAIL*") + "\n");
        return ok;
    }

    // ============================================================== GPU plan (full coexistence cadence)
    static TornadoExecutionPlan buildPlan(Scene sc, boolean fix) {
        FilamentStore f = sc.fil; NodeNucleationStore nuc = sc.nuc; DepolyStore d = sc.depoly; AgingStore ag = sc.aging;
        RigidRodBody node = sc.nodeStore.node;
        TaskGraph tg = new TaskGraph("deadslot")
            .transferToDevice(DataTransferMode.FIRST_EXECUTION,
                    node.coord, sc.nodeStore.nodeInvTransY, nuc.seedNode, nuc.nodeBoundFil, nuc.nucParams,
                    nuc.tetherParams, nuc.seedParams,
                    ag.nucFrac, ag.depolyRateParams,
                    d.depolyParams, d.dragParams, d.returnedMon, d.returnedOffsets, d.returnScanCounts, d.deathFlag,
                    f.acceptFlag, f.reqCoord, f.reqUVec, f.reqYVec, f.freeCount, f.freeOffsets, f.freeList,
                    f.freeScanCounts, f.rankOffsets, f.rankScanCounts, f.allocCounts, f.birthParams,
                    f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.monomerCount,
                    f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide,
                    f.bTransGam, f.bRotGam, f.bTransDiff, f.bRotDiff, f.forceSum, f.torqueSum,
                    f.randForce, f.randTorque, f.brownTransScale, f.brownRotScale, f.params, f.filState)
            .transferToDevice(DataTransferMode.EVERY_EXECUTION, d.depolyCounts, nuc.nucCounts, ag.agingParams, ag.agingCounts, f.counts)
            .task("age", AgingSystem::age, f.filState, ag.nucFrac, ag.agingParams, ag.agingCounts)
            .task("depoly", DepolySystem::depolyProxy, f.filState, f.monomerCount, f.coord, f.uVec, f.end1NbrSlot, ag.nucFrac,
                    d.returnedMon, d.deathFlag, d.depolyParams, ag.depolyRateParams, d.depolyCounts)
            .task("csrReturn", CrossBridgeSystem::csrScan, d.returnScanCounts, d.returnedMon, d.returnedOffsets)
            .task("applyDeath", DepolySystem::applyDeath, f.filState, f.monomerCount, nuc.seedNode, f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide, f.brownTransScale, f.brownRotScale, d.deathFlag, d.depolyCounts)
            .task("count", NodeNucleationSystem::countBoundFil, nuc.seedNode, nuc.nodeBoundFil, nuc.nucCounts)
            .task("emit", NodeNucleationSystem::emit, node.coord, nuc.nodeBoundFil, f.acceptFlag, f.reqCoord, f.reqUVec, f.reqYVec, nuc.nucParams, nuc.nucCounts)
            .task("freeFlags", FilamentBirthSystem::freeFlags, f.filState, f.freeCount, f.allocCounts)
            .task("csrFree", CrossBridgeSystem::csrScan, f.freeScanCounts, f.freeCount, f.freeOffsets)
            .task("freeScatter", FilamentBirthSystem::freeScatter, f.filState, f.freeOffsets, f.freeList, f.allocCounts)
            .task("csrRank", CrossBridgeSystem::csrScan, f.rankScanCounts, f.acceptFlag, f.rankOffsets)
            .task("allocate", FilamentBirthSystem::allocate, f.reqCoord, f.reqUVec, f.reqYVec, f.rankOffsets, f.freeList, f.freeOffsets,
                    f.coord, f.uVec, f.yVec, f.brownTransScale, f.brownRotScale, f.filState, f.birthParams, f.allocCounts)
            .task("tag", NodeNucleationSystem::tagSeeds, f.rankOffsets, f.freeList, f.freeOffsets, nuc.seedNode, f.allocCounts);
        if (fix) {
            tg.task("initNewborn", NodeNucleationSystem::initNewborn, f.rankOffsets, f.freeList, f.freeOffsets, f.monomerCount, f.segLength, nuc.seedParams, f.allocCounts)
              .task("nucFresh", AgingSystem::nucleateFreshAtp, f.rankOffsets, f.freeList, f.freeOffsets, ag.nucFrac, f.allocCounts);
        }
        tg.task("zero", ChainBendingForceSystem::zeroAccumulators, f.forceSum, f.torqueSum, f.counts)
          .task("tether", NodeNucleationSystem::seedTether, f.coord, f.uVec, f.segLength, f.bTransGam, f.forceSum, f.torqueSum,
                    node.coord, sc.nodeStore.nodeInvTransY, nuc.seedNode, nuc.tetherParams)
          .task("integrate", RigidRodLangevinIntegrationSystem::integrate, f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum,
                    f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts)
          .task("derive", DerivedGeometrySystem::derive, f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts)
          .task("recomputeDrag", GrowthSystem::recomputeDrag, f.monomerCount, f.segLength, f.end1NbrSlot, f.end2NbrSlot,
                    f.bTransGam, f.bRotGam, f.bTransDiff, f.bRotDiff, d.dragParams, d.depolyCounts)
          .transferToHost(DataTransferMode.UNDER_DEMAND, f.monomerCount, f.filState, f.segLength,
                    f.end1NbrSlot, f.end2NbrSlot, nuc.seedNode, ag.nucFrac, d.returnedOffsets, f.rankOffsets, f.freeOffsets);
        sched = new GridScheduler();
        int C = f.n;
        addW("deadslot.age", pad(C)); addW("deadslot.depoly", pad(C)); addS("deadslot.csrReturn"); addW("deadslot.applyDeath", pad(C));
        addS("deadslot.count"); addW("deadslot.emit", pad(1));
        addW("deadslot.freeFlags", pad(C)); addS("deadslot.csrFree"); addS("deadslot.freeScatter"); addS("deadslot.csrRank");
        addW("deadslot.allocate", pad(1)); addW("deadslot.tag", pad(1));
        if (fix) { addW("deadslot.initNewborn", pad(1)); addW("deadslot.nucFresh", pad(1)); }
        addW("deadslot.zero", pad(C)); addW("deadslot.tether", pad(C));
        addW("deadslot.integrate", pad(C)); addW("deadslot.derive", pad(C)); addW("deadslot.recomputeDrag", pad(C));
        return new TornadoExecutionPlan(tg.snapshot());
    }

    /** GPU coexistence run, with the SAME per-cadence host bookkeeping (pool take/return + reuse detection). */
    static Result runGpu(Scene sc, int nCad, boolean fix) {
        TornadoExecutionPlan plan = buildPlan(sc, fix);
        FilamentStore f = sc.fil; NodeNucleationStore nuc = sc.nuc; DepolyStore d = sc.depoly; AgingStore ag = sc.aging;
        int C = f.n;
        boolean[] everDied = new boolean[C];
        Result r = new Result(); r.conservationOk = true;
        // TEST-rate params (constant): set once on the host buffers (transferred each cadence).
        ag.agingParams.set(0, TEST_PH); ag.agingParams.set(1, TEST_PD); ag.setFires(true);
        ag.depolyRateParams.set(0, TEST_PDEPOLY); ag.depolyRateParams.set(1, TEST_PDEPOLY);
        for (int t = 0; t < nCad; t++) {
            d.setCounts(t, SEED, true); nuc.setCounts(t, SEED); nuc.refreshPoolGate();
            TornadoExecutionResult res = plan.withGridScheduler(sched).execute();
            res.transferToHost(f.filState, f.monomerCount, nuc.seedNode, d.returnedOffsets, d.deathFlag,
                    f.rankOffsets, f.freeOffsets, f.freeList, f.segLength, ag.nucFrac, f.end1NbrSlot, f.end2NbrSlot);
            d.returnPoolForDepoly();          // pool.put(returnedOffsets[C])
            nuc.depletePoolForBirths(f);      // pool.take(births·actinSeed)
            recordCadence(sc, everDied, r, fix);
            if (t % 200 == 0 || t == nCad - 1) {
                long Fnow = sumActiveMonomers(f);
                if (Fnow != nuc.pool.totalTaken() - nuc.pool.totalReturned()) r.conservationOk = false;
            }
        }
        r.activeMon = sumActiveMonomers(f); r.active = countActive(f); r.conc = nuc.pool.conc();
        return r;
    }

    // ============================================================== utils
    static int pad(int n) { return ((n + B - 1) / B) * B; }
    static void addW(String name, int gg) { WorkerGrid w = new WorkerGrid1D(gg); w.setLocalWork(B, 1, 1); sched.addWorkerGrid(name, w); }
    static void addS(String name) { WorkerGrid w = new WorkerGrid1D(1); w.setLocalWork(1, 1, 1); sched.addWorkerGrid(name, w); }
}
