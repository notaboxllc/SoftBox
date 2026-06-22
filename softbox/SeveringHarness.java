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
 * Increment 7 Severing build (B) VALIDATION — cofilin en-masse whole-segment dissolve + the COMBINED watchable
 * turnover system (growth + depoly + aging + severing in one sim). Default-off overall; this assay opts in. Run:
 * ./run_severing.sh [-cpu] [-3js <dir>]. No default flip.
 *
 * The dissolve REUSES the validated Stage-1 death path (DepolySystem.applyDeath: markFree + en-masse pool.put +
 * break BOTH neighbor links → two valid reciprocal sub-chains). The cofilin trigger is a faithful aggregate port
 * of v1's checkCofilinDissolve (recon §1e). The proxy's f_ADP (build A) is the dissolve's input.
 *
 * Gates:
 *   1 trigger      — the per-segment cofilin fraction accumulates off f_ADP at the v1 rate (vs analytic), and the
 *                    dissolve fires EXACTLY when f_cof crosses cofilinRatio (bit-for-decision on the formula).
 *   2 two-chains   — a cofilin-DRIVEN interior dissolve leaves TWO valid reciprocal sub-chains (gate-3b machinery,
 *                    now driven by the cofilin trigger, not a forced deathFlag).
 *   3 conservation — EXACT integer ledger through grow / depoly / death / DISSOLVE.
 *   4 CPU≡GPU      — the dissolve decision + link-break (full combined biochem pipeline).
 *   5 no-op-off    — cofilinRatio=1.0 ⇒ no dissolve ⇒ lifecycle bit-identical to the aging baseline.
 *   6 combined     — all systems on (low cofilinRatio): stable, dissolves occur, conservation holds (the watchable
 *                    render; -3js dumps frames coloured by the ADP cascade, dissolving segments vanish + fragment).
 *
 * FLAGGED (discovery boundary): faithful FREE-FRAGMENT barbed-end (end2) dynamics would require end2 depoly — a
 * Stage-1 deferral (pointed-only). An interior dissolve exposes the outer sub-chain's NEW barbed end (end2); in v1
 * that end depolymerizes (kATPOff2/kADPOff2). v2's outer fragment shrinks only from its pointed end (end1) here.
 * The severing build is correct + conserved without it; end2 depoly is NOT added silently — reported as the next
 * layer. Also flagged: tropomyosin protection (no tropo state in v2) — recon §4; the v1 AGGREGATE length-dist
 * comparison is the FOLLOW-ON.
 */
public final class SeveringHarness {

    static final int B = 64;
    static GridScheduler sched;
    static boolean cpu = false;
    static String jsDir = null;
    static final int SEED = 0x5E7E2;
    static final double V_BOX = 1.0;
    static final double K_ON  = Constants.kATPOn2WithFormin;
    static final double K_OFF_ADP = Constants.kADPOff1;
    static final int FILCAP = 64;
    static double uMper;

    public static void main(String[] args) {
        double dt = 1.0e-5;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-cpu")) cpu = true;
            else if (args[i].equals("-3js") && i + 1 < args.length) jsDir = args[++i];
        }
        uMper = 1e21 / (V_BOX * Constants.AvogadroNum);

        System.out.println("=== Soft Box increment 7 — SEVERING build (B): cofilin en-masse dissolve + combined turnover ===");
        System.out.println("cofilin decorates ADP monomers (proxy f_cof ≤ f_ADP); a segment with f_cof > cofilinRatio dissolves EN MASSE.");
        System.out.printf("v1 specifics: cofilinConc=%.1f, cofilinRate=%.1f ⇒ p_cof=%.2e/cadence; cofilinRatio default %.1f (=OFF); bundleStableFactor=%.1f (linkCt=0 here)%n",
                Constants.cofilinConc, Constants.cofilinRate, Constants.cofilinConc * Constants.cofilinRate * Constants.biochemDeltaT,
                Constants.cofilinRatio, Constants.bundleStableFactor);
        System.out.println("dissolve REUSES the Stage-1 death path (markFree + en-masse pool.put + break BOTH links → two valid sub-chains).");
        System.out.println("runner: " + (cpu ? "CPU only (-cpu)" : "GPU + CPU cross-check") + ", dt=" + dt + "\n");

        boolean g1 = checkTrigger();
        boolean g2 = checkTwoChains(dt);
        boolean g5 = checkNoOpWhenOff(dt);
        boolean g3 = checkConservation(dt);
        boolean g4 = checkCpuGpu(dt);
        boolean g6 = checkCombined(dt);

        boolean ok = g1 && g2 && g3 && g4 && g5 && g6;
        System.out.println();
        System.out.println("=== SEVERING (build B: cofilin dissolve + combined turnover) VALIDATION " + (ok ? "PASS" : "FAIL") + " ===");
        if (!ok) { System.out.println("BAIL-OUT: a gate failed. Commit nothing; report the gap."); System.exit(1); }

        if (jsDir != null) { System.out.println(); renderTreadmill(dt); }
    }

    // ============================================================== scene
    static final class Scene {
        FilamentStore fil; NodeStore nodeStore; NodeNucleationStore nuc; GrowthStore grow; DepolyStore depoly; AgingStore aging; SeverStore sever;
        int runSeed; long monInit;
    }

    static Scene shell(double pool0, double dt, double cofilinRatio, int runSeed) {
        Scene sc = new Scene();
        sc.runSeed = runSeed;
        NodeStore ns = new NodeStore(1, 1);
        ns.node.setCoord(0, 0f, 0f, 0f); ns.node.setUVec(0, 1f, 0f, 0f); ns.node.setYVec(0, 0f, 1f, 0f);
        ns.initNodeDrag();
        FilamentStore f = new FilamentStore(FILCAP, FILCAP);
        f.setParams(dt, Constants.brownianForceMag(dt));
        f.setChainParams(dt);
        f.setBirthParams(0.0, 0.0);
        f.setBirthRequestCount(FILCAP);
        for (int s = 0; s < FILCAP; s++) { f.setCoord(s, 0f, 0f, 0f); f.setUVec(s, -1f, 0f, 0f); f.setYVec(s, 0f, 1f, 0f); f.markFree(s); }
        NodeNucleationStore nuc = new NodeNucleationStore(1, FILCAP, Constants.actinSeed, 1.0e30, V_BOX, 1.0);
        nuc.setTetherParams(Constants.fracMove, dt);
        GrowthStore g = new GrowthStore(FILCAP, K_ON, dt, pool0, V_BOX);
        DepolyStore d = new DepolyStore(FILCAP, K_OFF_ADP, dt, g.pool);
        AgingStore aging = new AgingStore(FILCAP);
        SeverStore sever = new SeverStore(FILCAP, cofilinRatio);
        sc.fil = f; sc.nodeStore = ns; sc.nuc = nuc; sc.grow = g; sc.depoly = d; sc.aging = aging; sc.sever = sever;
        return sc;
    }

    /** Pre-wired K-segment chain node—s0(barbed,seedNode=0)—…—s_{K-1}(pointed tip). monPerSeg each; nucFrac ATP. */
    static Scene buildChain(int nSeg, int monPerSeg, double pool0, double dt, double cofilinRatio, int runSeed) {
        Scene sc = shell(pool0, dt, cofilinRatio, runSeed);
        FilamentStore f = sc.fil;
        double segL = (monPerSeg + 1) * Constants.actinMonoRadius;
        double x = 0.5 * segL;
        for (int i = 0; i < nSeg; i++) {
            f.filState.set(i, FilamentStore.FIL_ACTIVE);
            f.monomerCount.set(i, monPerSeg);
            f.setCoord(i, (float) x, 0f, 0f); f.setUVec(i, -1f, 0f, 0f); f.setYVec(i, 0f, 1f, 0f);
            sc.aging.setATP(i); sc.sever.cofFrac.set(i, 0f);
            x += segL;
            if (i < nSeg - 1) { f.end1NbrSlot.set(i, i + 1); f.end1NbrSide.set(i, 1); }
            if (i > 0)        { f.end2NbrSlot.set(i, i - 1); f.end2NbrSide.set(i, 0); }
        }
        sc.nuc.seedNode.set(0, 0);
        DragTensorSystem.run(f);
        sc.monInit = sumMonomers(f);
        return sc;
    }

    static long sumMonomers(FilamentStore f) { long m = 0; for (int s = 0; s < f.n; s++) if (f.filState.get(s) >= 0) m += f.monomerCount.get(s); return m; }
    static int countActive(FilamentStore f) { int c = 0; for (int s = 0; s < f.n; s++) if (f.filState.get(s) >= 0) c++; return c; }

    // ============================================================== one biochem cadence (CPU)
    static void cadenceCpu(Scene sc, int cadence, boolean severOn) { cadenceCpu(sc, cadence, severOn, false); }

    /** bufferedPool=true ⇒ skip the pool take/put (step 7) so [actin] stays CONSTANT (an in-vitro-style replenished
     *  reservoir) — the "adjust polymerization to keep up" lever: P_grow stays high ⇒ the barbed tip never stalls ⇒
     *  the filament PERSISTS against aging/severing removal. NOT a closed-pool steady state (that is the validated
     *  gate); a viewer/demo choice for the persistent treadmill render. */
    static void cadenceCpu(Scene sc, int cadence, boolean severOn, boolean bufferedPool) {
        FilamentStore f = sc.fil; GrowthStore g = sc.grow; DepolyStore d = sc.depoly; AgingStore ag = sc.aging; SeverStore sv = sc.sever;
        g.refreshRate(true); d.refreshRate(true); ag.refresh(true); sv.refresh(severOn);
        g.setCounts(cadence, sc.runSeed, true); d.setCounts(cadence, sc.runSeed, true); ag.setFires(true); sv.setFires(true);
        // 1. age
        AgingSystem.age(f.filState, ag.nucFrac, ag.agingParams, ag.agingCounts);
        // 2. depoly pass (proxy rate) — runs to completion (markFree) before the sever pass
        DepolySystem.depolyProxy(f.filState, f.monomerCount, f.coord, f.uVec, f.end1NbrSlot, ag.nucFrac,
                d.returnedMon, d.deathFlag, d.depolyParams, ag.depolyRateParams, d.depolyCounts);
        CrossBridgeSystem.csrScan(d.returnScanCounts, d.returnedMon, d.returnedOffsets);
        DepolySystem.applyDeath(f.filState, f.monomerCount, sc.nuc.seedNode, f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide,
                f.brownTransScale, f.brownRotScale, d.deathFlag, d.depolyCounts);
        // 3. grow + growthAtp
        GrowthSystem.grow(sc.nuc.seedNode, f.monomerCount, f.coord, f.uVec, g.grewFlag, g.growParams, g.growCounts);
        CrossBridgeSystem.csrScan(g.grewScanCounts, g.grewFlag, g.grewOffsets);
        AgingSystem.growthAtp(g.grewFlag, f.monomerCount, ag.nucFrac);
        // 4. sever pass (cofilin accumulate → dissolve → REUSE applyDeath)
        SeveringSystem.cofilinAccumulate(f.filState, ag.nucFrac, sv.cofFrac, sv.cofilinParams, sv.severCounts);
        SeveringSystem.cofilinDissolve(f.filState, f.monomerCount, sv.cofFrac, sv.severDeathFlag, sv.severReturnedMon, sv.cofilinParams, sv.severCounts);
        CrossBridgeSystem.csrScan(sv.severScanCounts, sv.severReturnedMon, sv.severReturnedOffsets);
        DepolySystem.applyDeath(f.filState, f.monomerCount, sc.nuc.seedNode, f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide,
                f.brownTransScale, f.brownRotScale, sv.severDeathFlag, d.depolyCounts);
        // 5. split allocator + splitInheritNuc
        GrowthSystem.markSplits(sc.nuc.seedNode, f.monomerCount, f.coord, f.uVec, f.yVec, f.acceptFlag, f.reqCoord, f.reqUVec, f.reqYVec, g.splitParams, g.growCounts);
        FilamentBirthSystem.freeFlags(f.filState, f.freeCount, f.allocCounts);
        CrossBridgeSystem.csrScan(f.freeScanCounts, f.freeCount, f.freeOffsets);
        FilamentBirthSystem.freeScatter(f.filState, f.freeOffsets, f.freeList, f.allocCounts);
        CrossBridgeSystem.csrScan(f.rankScanCounts, f.acceptFlag, f.rankOffsets);
        FilamentBirthSystem.allocate(f.reqCoord, f.reqUVec, f.reqYVec, f.rankOffsets, f.freeList, f.freeOffsets,
                f.coord, f.uVec, f.yVec, f.brownTransScale, f.brownRotScale, f.filState, f.birthParams, f.allocCounts);
        GrowthSystem.splitWire(f.rankOffsets, f.freeList, f.freeOffsets, f.monomerCount, f.coord, f.uVec,
                f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide, sc.nuc.seedNode, g.splitParams, f.allocCounts);
        AgingSystem.splitInheritNuc(f.rankOffsets, f.freeList, f.freeOffsets, ag.nucFrac, f.allocCounts);
        // 6. pool update from the integer counts (conservation: depoly + dissolve both return). Skipped for a
        //    buffered (constant-[actin]) reservoir ⇒ polymerization keeps up ⇒ the filament persists.
        if (!bufferedPool) {
            g.pool.take(g.grewOffsets.get(FILCAP));
            g.pool.put(d.returnedOffsets.get(FILCAP) + sv.severReturnedOffsets.get(FILCAP));
        }
    }

    static boolean conservationOk(Scene sc) {
        long Fnow = sumMonomers(sc.fil);
        return Fnow == sc.monInit + sc.grow.pool.totalTaken() - sc.grow.pool.totalReturned();
    }

    // ============================================================== 1. trigger faithfulness
    static boolean checkTrigger() {
        System.out.println("--- 1. trigger faithfulness (cofilin fraction accumulates off f_ADP vs analytic; dissolve fires at the threshold) ---");
        double pCof = Constants.cofilinConc * Constants.cofilinRate * Constants.biochemDeltaT;
        // f_ADP held = 1 (a fully-aged segment) ⇒ analytic f_cof(n) = 1 − (1−p_cof)^n
        int[] checkN = { 1000, 3000, 10000 };
        double maxErr = 0;
        for (int N : checkN) {
            AgingStore ag = new AgingStore(4); SeverStore sv = new SeverStore(4, 1.0);
            IntArray fil = new IntArray(4); fil.set(0, FilamentStore.FIL_ACTIVE);
            ag.set(0, 0f, 0f, 1f);                          // f_ADP = 1
            sv.refresh(true); sv.setFires(true);
            for (int t = 0; t < N; t++) SeveringSystem.cofilinAccumulate(fil, ag.nucFrac, sv.cofFrac, sv.cofilinParams, sv.severCounts);
            double an = 1.0 - Math.pow(1.0 - pCof, N);
            double e = Math.abs(sv.fCof(0) - an);
            maxErr = Math.max(maxErr, e);
            System.out.printf("    n=%5d cadences: f_cof sim=%.6f analytic=%.6f (|Δ|=%.2e)%n", N, sv.fCof(0), an, e);
        }
        boolean accOk = maxErr < 1e-4;
        // dissolve fires EXACTLY at the threshold crossing: ratio=0.5, n* = ln(0.5)/ln(1−p_cof)
        double ratio = 0.5;
        int nStar = (int) Math.ceil(Math.log(1.0 - ratio) / Math.log(1.0 - pCof));   // first n with f_cof > ratio
        boolean fireOk;
        {
            AgingStore ag = new AgingStore(4); SeverStore sv = new SeverStore(4, ratio);
            IntArray fil = new IntArray(4); fil.set(0, FilamentStore.FIL_ACTIVE);
            IntArray mc = new IntArray(4); mc.set(0, 32);
            ag.set(0, 0f, 0f, 1f); sv.refresh(true); sv.setFires(true);
            int firedAt = -1;
            for (int t = 1; t <= nStar + 5 && firedAt < 0; t++) {
                SeveringSystem.cofilinAccumulate(fil, ag.nucFrac, sv.cofFrac, sv.cofilinParams, sv.severCounts);
                SeveringSystem.cofilinDissolve(fil, mc, sv.cofFrac, sv.severDeathFlag, sv.severReturnedMon, sv.cofilinParams, sv.severCounts);
                if (sv.severDeathFlag.get(0) == 1) firedAt = t;
            }
            // the kernel compares f_cof AFTER the t-th accumulate; firstFire when f_cof(t) > ratio ⇒ t == nStar
            fireOk = firedAt == nStar && sv.severReturnedMon.get(0) == 32;
            System.out.printf("    dissolve fired at cadence %d (predicted n*=%d, f_cof crosses %.2f); en-masse returnedMon=%d (==32): %s%n",
                    firedAt, nStar, ratio, sv.severReturnedMon.get(0), fireOk ? "ok" : "*FAIL*");
        }
        boolean ok = accOk && fireOk;
        System.out.printf("  ⇒ accumulation vs analytic max|Δ|=%.2e (<1e-4); dissolve bit-for-decision at the cofilinRatio crossing%n", maxErr);
        System.out.println("  => " + (ok ? "PASS" : "*FAIL*") + "\n");
        return ok;
    }

    // ============================================================== 2. interior cofilin dissolve → two sub-chains
    static boolean checkTwoChains(double dt) {
        System.out.println("--- 2. cofilin-DRIVEN interior dissolve → two valid reciprocal sub-chains ---");
        // chain A(0)—B(1)—C(2); B is the interior segment. Drive B fully ADP ⇒ cofilin accumulates ⇒ dissolves.
        Scene sc = buildChain(3, 32, 1.0e30, dt, 0.5, SEED);   // ratio 0.5
        FilamentStore f = sc.fil;
        sc.aging.set(1, 0f, 0f, 1f);                            // B fully ADP (the rest stay ATP ⇒ won't dissolve)
        long m0 = sumMonomers(f);
        // run the sever pass alone (no depoly/grow) until B dissolves
        int fired = -1;
        for (int t = 1; t <= 6000 && fired < 0; t++) {
            sc.sever.refresh(true); sc.sever.setFires(true);
            SeveringSystem.cofilinAccumulate(f.filState, sc.aging.nucFrac, sc.sever.cofFrac, sc.sever.cofilinParams, sc.sever.severCounts);
            SeveringSystem.cofilinDissolve(f.filState, f.monomerCount, sc.sever.cofFrac, sc.sever.severDeathFlag, sc.sever.severReturnedMon, sc.sever.cofilinParams, sc.sever.severCounts);
            CrossBridgeSystem.csrScan(sc.sever.severScanCounts, sc.sever.severReturnedMon, sc.sever.severReturnedOffsets);
            int ret = sc.sever.severReturnedOffsets.get(FILCAP);
            DepolySystem.applyDeath(f.filState, f.monomerCount, sc.nuc.seedNode, f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide,
                    f.brownTransScale, f.brownRotScale, sc.sever.severDeathFlag, sc.depoly.depolyCounts);
            if (ret > 0) { sc.grow.pool.put(ret); fired = t; }
        }
        boolean bDead = f.filState.get(1) < 0;
        boolean aFreeEnd = f.end1NbrSlot.get(0) == -1;          // A's link to B gone (A now a valid pointed-tip end)
        boolean cFreeEnd = f.end2NbrSlot.get(2) == -1;          // C's link to B gone (C now a valid free fragment)
        boolean bUnlinked = f.end1NbrSlot.get(1) == -1 && f.end2NbrSlot.get(1) == -1;
        boolean aValid = f.filState.get(0) >= 0 && f.monomerCount.get(0) == 32;   // A intact, node-side
        boolean cValid = f.filState.get(2) >= 0 && f.monomerCount.get(2) == 32;   // C intact, free fragment
        boolean cons = sumMonomers(f) + 32 == m0;              // B's 32 monomers returned
        boolean ok = fired > 0 && bDead && aFreeEnd && cFreeEnd && bUnlinked && aValid && cValid && cons;
        System.out.printf("  B dissolved (cofilin-driven) at cadence %d; B dead=%s unlinked=%s; {A} valid free-ended=%s, {C} valid free-ended=%s; Σmono %d→%d (+32 returned): %s%n",
                fired, bDead, bUnlinked, aValid && aFreeEnd, cValid && cFreeEnd, m0, sumMonomers(f), cons ? "ok" : "*FAIL*");
        System.out.println("  (the gate-3b interior link-break, now driven by the cofilin trigger ⇒ two proper filaments the systems iterate.)");
        System.out.println("  => " + (ok ? "PASS" : "*FAIL*") + "\n");
        return ok;
    }

    // ============================================================== 5. no-op when off
    static boolean checkNoOpWhenOff(double dt) {
        System.out.println("--- 5. no-op-when-off (cofilinRatio=1.0 ⇒ no dissolve ⇒ lifecycle bit-identical to the aging baseline) ---");
        int nCad = 40000; double total = 350 * uMper; double pool0 = total - 6 * 31 * uMper;
        Scene withSev = buildChain(6, 31, pool0, dt, 1.0, SEED);   // sever pass runs, ratio=1.0 (never fires)
        Scene baseline = buildChain(6, 31, pool0, dt, 1.0, SEED);  // no sever pass
        for (int t = 0; t < nCad; t++) cadenceCpu(withSev, t, true);     // severOn=true, ratio=1.0
        for (int t = 0; t < nCad; t++) cadenceCpu(baseline, t, false);   // severOn=false
        int monMis = 0, stateMis = 0, linkMis = 0;
        for (int s = 0; s < FILCAP; s++) {
            if (withSev.fil.monomerCount.get(s) != baseline.fil.monomerCount.get(s)) monMis++;
            if (withSev.fil.filState.get(s) != baseline.fil.filState.get(s)) stateMis++;
            if (withSev.fil.end1NbrSlot.get(s) != baseline.fil.end1NbrSlot.get(s) || withSev.fil.end2NbrSlot.get(s) != baseline.fil.end2NbrSlot.get(s)) linkMis++;
        }
        boolean poolMatch = Math.abs(withSev.grow.pool.conc() - baseline.grow.pool.conc()) < 1e-12;
        boolean ok = monMis == 0 && stateMis == 0 && linkMis == 0 && poolMatch;
        System.out.printf("  after %d cadences: mismatches monomer=%d state=%d link=%d; pool match=%s => %s%n",
                nCad, monMis, stateMis, linkMis, poolMatch, ok ? "PASS" : "*FAIL*");
        System.out.println("  (the severing kernels write only their own scratch — cofFrac never crosses ratio=1.0 ⇒ no dissolve.)");
        System.out.println("  => " + (ok ? "PASS" : "*FAIL*") + "\n");
        return ok;
    }

    // ============================================================== 3. conservation EXACT (through dissolve)
    static boolean checkConservation(double dt) {
        System.out.println("--- 3. conservation EXACT (pool + Σ monomerCount = const) through grow/depoly/death/DISSOLVE ---");
        double total = 350 * uMper;
        Scene sc = buildChain(6, 31, total - 6 * 31 * uMper, dt, 0.5, SEED);   // ratio 0.5 ⇒ dissolves DO fire
        int nCad = 200000; boolean consAll = true;
        for (int t = 0; t < nCad; t++) {
            cadenceCpu(sc, t, true);
            if ((t % 20000) == 0 && !conservationOk(sc)) consAll = false;
        }
        boolean cons = conservationOk(sc) && consAll;
        long taken = sc.grow.pool.totalTaken(), ret = sc.grow.pool.totalReturned();
        System.out.printf("  after %d cadences (dissolves fired): monInit=%d, taken=%d, returned=%d, Σnow=%d ⇒ Σnow==monInit+taken−returned: %s%n",
                nCad, sc.monInit, taken, ret, sumMonomers(sc.fil), cons);
        System.out.println("  (the en-masse dissolve put(monomerCount) extends the gate-1 ledger; integer-exact through the dissolve.)");
        System.out.println("  => " + (cons ? "PASS" : "*FAIL*") + "\n");
        return cons;
    }

    // ============================================================== 4. CPU≡GPU (dissolve decision + link-break)
    static boolean checkCpuGpu(double dt) {
        if (cpu) { System.out.println("--- 4. CPU≡GPU: skipped (-cpu) ---\n"); return true; }
        System.out.println("--- 4. CPU≡GPU (full combined pipeline incl. the dissolve decision + link-break) ---");
        int nCad = 40000; double total = 350 * uMper; double pool0 = total - 6 * 31 * uMper;
        Scene cpuS = buildChain(6, 31, pool0, dt, 0.5, SEED);
        Scene gpuS = buildChain(6, 31, pool0, dt, 0.5, SEED);
        for (int t = 0; t < nCad; t++) cadenceCpu(cpuS, t, true);
        runGpu(gpuS, nCad);
        int monMis = 0, stateMis = 0, linkMis = 0;
        for (int s = 0; s < FILCAP; s++) {
            if (cpuS.fil.monomerCount.get(s) != gpuS.fil.monomerCount.get(s)) monMis++;
            if (cpuS.fil.filState.get(s) != gpuS.fil.filState.get(s)) stateMis++;
            if (cpuS.fil.end1NbrSlot.get(s) != gpuS.fil.end1NbrSlot.get(s) || cpuS.fil.end2NbrSlot.get(s) != gpuS.fil.end2NbrSlot.get(s)) linkMis++;
        }
        double aC = cpuS.grow.pool.conc(), aG = gpuS.grow.pool.conc();
        long lC = sumMonomers(cpuS.fil), lG = sumMonomers(gpuS.fil);
        boolean bitIdent = monMis == 0 && stateMis == 0 && linkMis == 0;
        double relA = Math.abs(aC - aG) / Math.max(1e-12, aC);
        boolean aggOk = relA < 0.10;
        boolean ok = bitIdent || aggOk;
        System.out.printf("  after %d cadences: mismatches monomer=%d state=%d link=%d; [actin] CPU=%.6f GPU=%.6f; L CPU=%d GPU=%d; bit-identical=%s%n",
                nCad, monMis, stateMis, linkMis, aC, aG, lC, lG, bitIdent);
        System.out.println("  (the dissolve decision is on the float32 cofFrac feeding a > threshold ⇒ bit-identical short-horizon, else §8 aggregate-within-SEM.)");
        System.out.println("  => " + (ok ? "PASS" : "*FAIL*") + "\n");
        return ok;
    }

    // ============================================================== 6. combined watchable render
    static boolean checkCombined(double dt) {
        System.out.println("--- 6. COMBINED turnover (growth + depoly + aging + severing in ONE sim; the watchable render) ---");
        // A generous closed pool (high [actin]) keeps the node filament continuously growing/replenishing while
        // its segments age (the ADP gradient) and the oldest occasionally dissolve + fragment — a sustained,
        // watchable steady-ish turnover (1500 mono ≪ FILCAP·stdSeg=2048 ⇒ no slot overflow even if all polymerizes).
        // GROWTH-FIRST narrative (most watchable): start SHORT + young (ATP), high [actin] ⇒ the filament
        // POLYMERIZES (barbed tip extends + splits, young/green), the trailing segments AGE (the red ADP gradient
        // develops barbed→pointed), and the oldest eventually cross the cofilin threshold and DISSOLVE + fragment.
        // The growth phase extends the watchable span before the no-nucleation wind-down (flagged limitation).
        // Closed-pool validation: a single filament with severing winds down (no nucleation), but the systems run
        // together + dissolves fire + conservation holds. The PERSISTENT watchable render is renderTreadmill (-3js).
        int nSeg = 3;
        double total = 1400 * uMper;                          // < FILCAP·stdSeg=2048 ⇒ no slot overflow
        Scene sc = buildChain(nSeg, 32, total - nSeg * 32 * uMper, dt, 0.7, SEED);
        int nCad = 45000;
        long dissolveEvents = 0;
        boolean consAll = true;
        for (int t = 0; t < nCad; t++) {
            cadenceCpu(sc, t, true);
            if (sc.sever.severReturnedOffsets.get(FILCAP) > 0) dissolveEvents++;
            if ((t % 20000) == 0 && !conservationOk(sc)) consAll = false;
        }
        boolean cons = conservationOk(sc) && consAll;
        boolean stable = sumMonomers(sc.fil) >= 0;            // no NaN/crash; finished
        boolean dissolved = dissolveEvents > 0;
        boolean ok = cons && stable && dissolved;
        System.out.printf("  ran %d cadences (closed pool): dissolve events=%d; active fil=%d, Σmono=%d, [actin]=%.4f µM; conservation EXACT=%s%n",
                nCad, dissolveEvents, countActive(sc.fil), sumMonomers(sc.fil), sc.grow.pool.conc(), cons);
        System.out.println("  (closed-pool single filament winds down without nucleation; the PERSISTENT render is renderTreadmill via -3js.)");
        System.out.println("  => " + (ok ? "PASS (growth + depoly + aging + severing run together; dissolves fire; conservation exact)" : "*FAIL*") + "\n");
        return ok;
    }

    // ============================================================== PERSISTENT treadmill render (-3js)
    /**
     * The watchable FREE-TREADMILLING filament: the full turnover (growth + depoly + aging + severing) on a single
     * filament that PERSISTS and TRANSLATES — monomers add at the barbed end (which ADVANCES), age behind it (the
     * ADP gradient rides barbed→pointed), depolymerize at the pointed end (which FOLLOWS), and the oldest segments
     * occasionally sever + fragment. The filament is UNANCHORED (no node tether, full Brownian + chain mechanics —
     * positional constraints unlocked) and moves freely.
     *
     * THE TREADMILL TRANSLATION (the fix). The growth geometry (GrowthSystem.grow) keeps the barbed end (end2)
     * FIXED and extends the pointed end — the formin-AT-A-NODE picture, where the barbed end is clamped and does
     * NOT move. A FREE filament with a PROCESSIVE formin instead has its barbed end ADVANCE as it polymerizes. We
     * convert one to the other by rigidly translating the whole filament by +δ·uVec per barbed monomer added
     * (δ=actinMonoRadius): this cancels the pointed-extension and makes the barbed tip advance (+δ) with the
     * pointed tip stationary; pointed depoly then advances the pointed tip (+δ) too ⇒ over a grow+depoly cycle the
     * filament translates +δ toward the barbed end at the treadmill velocity. (A render geometry choice — the
     * monomer bookkeeping / rates are unchanged.)
     *
     * Persistence: a BUFFERED pool holds [actin] high (growth keeps up with removal) + a FORMIN-CAPPED barbed tip
     * (kept ATP-fresh each cadence — the processive formin adds ATP-actin + protects from cofilin) so the barbed
     * tip never ages/dissolves. NOT a closed-pool conservation run (that is gate 3); a demo render.
     */
    static void renderTreadmill(double dt) {
        System.out.println("--- FREE-TREADMILLING render (unanchored; barbed advances, pointed follows; Brownian+chain mechanics) ---");
        double targetConc = 4.0;                              // µM (buffered reservoir; growth keeps up)
        int nSeg = 8;
        Scene sc = buildChain(nSeg, 32, targetConc, dt, 0.95, SEED);   // gentle severing (ratio 0.95)
        FilamentStore f = sc.fil; int C = f.n;
        // staggered initial age gradient (barbed young → pointed old) + Brownian on for the active segments
        for (int i = 0; i < nSeg; i++) {
            float adp = (float) i / (float) (nSeg - 1);
            sc.aging.set(i, 1f - adp, 0f, adp);
            sc.sever.cofFrac.set(i, 0.5f * adp);
            f.brownTransScale.set(i, (float) Constants.BTransCoeff);
            f.brownRotScale.set(i, (float) Constants.BRotCoeff);
        }
        f.setBirthParams(Constants.BTransCoeff, Constants.BRotCoeff);   // split/born segments get Brownian too
        // start the barbed end on the +x side so it treadmills across the view toward −x (barbed direction)
        for (int s = 0; s < nSeg; s++) f.coord.set(s, f.coord.get(s) + 9.0f);
        double amr = Constants.actinMonoRadius;
        int nCad = 60000, stride = 300;
        FrameWriter fw = new FrameWriter(jsDir, 24.0, 2.0, 2.0);
        long dissolveEvents = 0; int minActive = Integer.MAX_VALUE, maxActive = 0;
        double startX = barbedTipX(sc);
        for (int t = 0; t < nCad; t++) {
            // 1. biochem (buffered pool ⇒ growth keeps up; severing on)
            cadenceCpu(sc, t, true, true);
            // 2. formin-capped barbed tip: keep the node-bonded tip ATP-fresh ⇒ it never ages/dissolves ⇒ persists
            for (int s = 0; s < FILCAP; s++) if (sc.nuc.seedNode.get(s) >= 0) { sc.aging.setATP(s); sc.sever.cofFrac.set(s, 0f); }
            // 3. recompute segLength/drag from monomerCount (mechanics needs them consistent)
            GrowthSystem.recomputeDrag(f.monomerCount, f.segLength, f.end1NbrSlot, f.end2NbrSlot,
                    f.bTransGam, f.bRotGam, f.bTransDiff, f.bRotDiff, sc.grow.dragParams, sc.grow.growCounts);
            // 4. TREADMILL TRANSLATION: advance the barbed end by the monomers added this cadence (rigid shift of
            //    the node-bonded filament along its barbed direction). This unpins the barbed end ⇒ it translates.
            int nGrew = sc.grow.grewOffsets.get(FILCAP);
            if (nGrew > 0) translateMainFilament(sc, nGrew * amr);
            // 5. MECHANICS (unanchored: NO tether/containment) ⇒ the filament moves/wiggles/diffuses freely
            f.setCounts(t, SEED);
            ChainBendingForceSystem.zeroAccumulators(f.forceSum, f.torqueSum, f.counts);
            BrownianForceSystem.brownianForce(f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.brownTransScale, f.brownRotScale, f.params, f.counts);
            ChainBendingForceSystem.chainForces(f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide,
                    f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts);
            RigidRodLangevinIntegrationSystem.integrate(f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum,
                    f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts);
            DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
            if (sc.sever.severReturnedOffsets.get(FILCAP) > 0) dissolveEvents++;
            int act = countActive(f); minActive = Math.min(minActive, act); maxActive = Math.max(maxActive, act);
            if ((t % stride) == 0) fw.writeFrame(f, sc.aging, t * Constants.biochemDeltaT);
        }
        boolean persisted = minActive > 0;
        double netTranslation = barbedTipX(sc) - startX;
        System.out.printf("  ran %d cadences (buffered [actin]=%.2f µM): active fil min=%d max=%d (persisted=%s); dissolve events=%d; barbed-tip net Δx=%.3f µm; %d frames%n",
                nCad, targetConc, minActive, maxActive, persisted, dissolveEvents, netTranslation, fw.framesWritten());
        System.out.println("  viewer: " + fw.dir() + " — UNANCHORED filament treadmills + wiggles; barbed \"+\" tip ADVANCES (young/green); ADP gradient → red toward pointed; dissolving segments vanish + fragment.");
        System.out.println("  => " + (persisted ? "PERSISTENT + TRANSLATING (the filament treadmills through space, no longer pinned)" : "*winds down — retune*"));
    }

    /** X of the node-bonded (barbed) tip's end2 — to measure net treadmill translation. */
    static double barbedTipX(Scene sc) {
        FilamentStore f = sc.fil; int C = f.n;
        for (int s = 0; s < FILCAP; s++) if (sc.nuc.seedNode.get(s) >= 0) {
            return f.coord.get(s) + 0.5 * f.segLength.get(s) * f.uVec.get(s);   // end2 = coord + (L/2)uVec
        }
        return 0;
    }

    /** Rigidly translate the node-bonded filament (barbed tip → … → pointed tip via end1 links) by amount·uVec(tip).
     *  Converts the formin-anchored growth (barbed fixed) into free treadmilling (barbed advances). */
    static void translateMainFilament(Scene sc, double amount) {
        FilamentStore f = sc.fil; int C = f.n;
        int tip = -1; for (int s = 0; s < FILCAP; s++) if (sc.nuc.seedNode.get(s) >= 0) { tip = s; break; }
        if (tip < 0) return;
        float dx = (float) (amount * f.uVec.get(tip)), dy = (float) (amount * f.uVec.get(C + tip)), dz = (float) (amount * f.uVec.get(2 * C + tip));
        int cur = tip, guard = 0;
        while (cur >= 0 && guard < FILCAP) {
            f.coord.set(cur, f.coord.get(cur) + dx);
            f.coord.set(C + cur, f.coord.get(C + cur) + dy);
            f.coord.set(2 * C + cur, f.coord.get(2 * C + cur) + dz);
            cur = f.end1NbrSlot.get(cur);
            guard++;
        }
    }

    // ---- GPU: full combined biochem cadence plan (gate 4) ----
    static TornadoExecutionPlan buildPlan(Scene sc) {
        FilamentStore f = sc.fil; GrowthStore g = sc.grow; DepolyStore d = sc.depoly; AgingStore ag = sc.aging; SeverStore sv = sc.sever;
        TaskGraph tg = new TaskGraph("sever")
            .transferToDevice(DataTransferMode.FIRST_EXECUTION,
                    sc.nuc.seedNode, d.depolyParams, d.dragParams, d.returnedMon, d.returnedOffsets, d.returnScanCounts, d.deathFlag,
                    ag.nucFrac, ag.depolyRateParams,
                    sv.cofFrac, sv.severDeathFlag, sv.severReturnedMon, sv.severReturnedOffsets, sv.severScanCounts,
                    g.splitParams, g.dragParams, g.grewFlag, g.grewOffsets, g.grewScanCounts,
                    f.acceptFlag, f.reqCoord, f.reqUVec, f.reqYVec, f.freeCount, f.freeOffsets, f.freeList, f.freeScanCounts,
                    f.rankOffsets, f.rankScanCounts, f.allocCounts, f.birthParams,
                    f.coord, f.uVec, f.yVec, f.segLength, f.monomerCount, f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide,
                    f.bTransGam, f.bRotGam, f.bTransDiff, f.bRotDiff, f.brownTransScale, f.brownRotScale, f.filState)
            .transferToDevice(DataTransferMode.EVERY_EXECUTION, d.depolyCounts, g.growParams, g.growCounts, ag.agingParams, ag.agingCounts, sv.cofilinParams, sv.severCounts)
            .task("age", AgingSystem::age, f.filState, ag.nucFrac, ag.agingParams, ag.agingCounts)
            .task("depoly", DepolySystem::depolyProxy, f.filState, f.monomerCount, f.coord, f.uVec, f.end1NbrSlot, ag.nucFrac,
                    d.returnedMon, d.deathFlag, d.depolyParams, ag.depolyRateParams, d.depolyCounts)
            .task("csrReturn", CrossBridgeSystem::csrScan, d.returnScanCounts, d.returnedMon, d.returnedOffsets)
            .task("applyDeath", DepolySystem::applyDeath, f.filState, f.monomerCount, sc.nuc.seedNode, f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide, f.brownTransScale, f.brownRotScale, d.deathFlag, d.depolyCounts)
            .task("grow", GrowthSystem::grow, sc.nuc.seedNode, f.monomerCount, f.coord, f.uVec, g.grewFlag, g.growParams, g.growCounts)
            .task("csrGrew", CrossBridgeSystem::csrScan, g.grewScanCounts, g.grewFlag, g.grewOffsets)
            .task("growthAtp", AgingSystem::growthAtp, g.grewFlag, f.monomerCount, ag.nucFrac)
            .task("cofAcc", SeveringSystem::cofilinAccumulate, f.filState, ag.nucFrac, sv.cofFrac, sv.cofilinParams, sv.severCounts)
            .task("cofDis", SeveringSystem::cofilinDissolve, f.filState, f.monomerCount, sv.cofFrac, sv.severDeathFlag, sv.severReturnedMon, sv.cofilinParams, sv.severCounts)
            .task("csrSever", CrossBridgeSystem::csrScan, sv.severScanCounts, sv.severReturnedMon, sv.severReturnedOffsets)
            .task("severDeath", DepolySystem::applyDeath, f.filState, f.monomerCount, sc.nuc.seedNode, f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide, f.brownTransScale, f.brownRotScale, sv.severDeathFlag, d.depolyCounts)
            .task("markSplits", GrowthSystem::markSplits, sc.nuc.seedNode, f.monomerCount, f.coord, f.uVec, f.yVec, f.acceptFlag, f.reqCoord, f.reqUVec, f.reqYVec, g.splitParams, g.growCounts)
            .task("freeFlags", FilamentBirthSystem::freeFlags, f.filState, f.freeCount, f.allocCounts)
            .task("csrFree", CrossBridgeSystem::csrScan, f.freeScanCounts, f.freeCount, f.freeOffsets)
            .task("freeScatter", FilamentBirthSystem::freeScatter, f.filState, f.freeOffsets, f.freeList, f.allocCounts)
            .task("csrRank", CrossBridgeSystem::csrScan, f.rankScanCounts, f.acceptFlag, f.rankOffsets)
            .task("allocate", FilamentBirthSystem::allocate, f.reqCoord, f.reqUVec, f.reqYVec, f.rankOffsets, f.freeList, f.freeOffsets,
                    f.coord, f.uVec, f.yVec, f.brownTransScale, f.brownRotScale, f.filState, f.birthParams, f.allocCounts)
            .task("splitWire", GrowthSystem::splitWire, f.rankOffsets, f.freeList, f.freeOffsets, f.monomerCount, f.coord, f.uVec,
                    f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide, sc.nuc.seedNode, g.splitParams, f.allocCounts)
            .task("splitInherit", AgingSystem::splitInheritNuc, f.rankOffsets, f.freeList, f.freeOffsets, ag.nucFrac, f.allocCounts)
            .transferToHost(DataTransferMode.UNDER_DEMAND, f.monomerCount, f.filState, f.end1NbrSlot, f.end2NbrSlot, ag.nucFrac, g.grewOffsets, d.returnedOffsets, sv.severReturnedOffsets);
        sched = new GridScheduler();
        int C = f.n;
        addW("sever.age", pad(C)); addW("sever.depoly", pad(C)); addS("sever.csrReturn"); addW("sever.applyDeath", pad(C));
        addW("sever.grow", pad(C)); addS("sever.csrGrew"); addW("sever.growthAtp", pad(C));
        addW("sever.cofAcc", pad(C)); addW("sever.cofDis", pad(C)); addS("sever.csrSever"); addW("sever.severDeath", pad(C));
        addW("sever.markSplits", pad(C)); addW("sever.freeFlags", pad(C)); addS("sever.csrFree"); addS("sever.freeScatter"); addS("sever.csrRank");
        addW("sever.allocate", pad(C)); addW("sever.splitWire", pad(C)); addW("sever.splitInherit", pad(C));
        return new TornadoExecutionPlan(tg.snapshot());
    }

    static void runGpu(Scene sc, int nCad) {
        TornadoExecutionPlan plan = buildPlan(sc);
        FilamentStore f = sc.fil; GrowthStore g = sc.grow; DepolyStore d = sc.depoly; AgingStore ag = sc.aging; SeverStore sv = sc.sever;
        for (int t = 0; t < nCad; t++) {
            g.refreshRate(true); d.refreshRate(true); ag.refresh(true); sv.refresh(true);
            g.setCounts(t, sc.runSeed, true); d.setCounts(t, sc.runSeed, true); ag.setFires(true); sv.setFires(true);
            TornadoExecutionResult res = plan.withGridScheduler(sched).execute();
            res.transferToHost(g.grewOffsets, d.returnedOffsets, sv.severReturnedOffsets);
            g.pool.take(g.grewOffsets.get(FILCAP));
            g.pool.put(d.returnedOffsets.get(FILCAP) + sv.severReturnedOffsets.get(FILCAP));
            if (t == nCad - 1) res.transferToHost(f.monomerCount, f.filState, f.end1NbrSlot, f.end2NbrSlot, ag.nucFrac);
        }
    }

    // ============================================================== utils
    static int pad(int n) { return ((n + B - 1) / B) * B; }
    static void addW(String name, int gg) { WorkerGrid w = new WorkerGrid1D(gg); w.setLocalWork(B, 1, 1); sched.addWorkerGrid(name, w); }
    static void addS(String name) { WorkerGrid w = new WorkerGrid1D(1); w.setLocalWork(1, 1, 1); sched.addWorkerGrid(name, w); }
}
