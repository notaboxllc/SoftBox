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
 * Increment 7 Stage 1 VALIDATION — treadmilling STEADY STATE vs first-principles balance (MEASUREMENT only; no
 * physics change). Couples the validated growth (barbed elongation, first-order in [actin]: rate = k_on·[actin])
 * + depoly (pointed shrink, fixed k_off1) on ONE formin filament in a CLOSED finite pool, and tests the emergent
 * steady state against the first-principles prediction (CLAUDE.md §8 — adjudicate emergent behavior vs physics,
 * NOT v1 numbers).
 *
 * PREDICTION (computed before measuring):
 *   [actin]_ss = C_c = k_off1 / k_on   (the critical concentration — set by the RATE BALANCE, INDEPENDENT of
 *                                        total actin or starting length)
 *   L_ss set by conservation: L_ss·µMperMon = total_actin − C_c   (more total actin ⇒ longer L_ss, SAME C_c)
 * With the build constants k_on = kATPOn2WithFormin = 11.6 µM⁻¹s⁻¹, k_off1 = kATPOff1 = 0.8 s⁻¹:
 *   C_c = 0.8/11.6 = 0.068966 µM.
 *
 * Why it works: per biochem cadence the barbed tip adds ≤1 monomer at P_grow = k_on·[actin]·biochemDeltaT and the
 * pointed tip removes ≤1 at P_depoly = k_off1·biochemDeltaT. Growth depletes the pool, depoly returns to it
 * (conservation), so [actin] relaxes to where P_grow = P_depoly ⇒ [actin]_ss = k_off1/k_on. dn/dcadence =
 * −a·(n − n_ss) with a = k_on·µMperMon·biochemDeltaT ⇒ exponential approach, τ = 1/a.
 *
 * MEASUREMENT (cadence loop; the steady state is a property of the biochem balance, so the scalar [actin]/L need
 * only the grow+depoly+split+death blocks — the per-step mechanical dynamics don't touch monomerCount/pool):
 *   - convergence of [actin](t), L(t) to a plateau;
 *   - time-averaged steady-state [actin] vs predicted C_c — the HEADLINE — same across initial conditions?
 *   - C_c invariance across total-actin; L_ss scales with total;
 *   - conservation EXACT throughout (integer ledger);
 *   - CPU≡GPU (deterministic wang-hash ⇒ lifecycle bit-identical; coord float32).
 *
 * Closed pool: box volume V sets µMperMon = (1e5³·1e6)/(V·Avogadro). V=1.0 µm³ ⇒ p_ss ≈ 41.5 free monomers at
 * SS, τ ≈ 52000 cadences. Default rates (k_off1 = 0.8/s). The turnover flags are enabled for THIS assay only
 * (default-off elsewhere). No default flip.
 */
public final class TreadmillHarness {

    static final int B = 64;
    static GridScheduler sched;
    static boolean cpu = false;
    static final int SEED = 0x77EAD;
    static final double V_BOX = 1.0;                     // µm³ (closed pool; sets µMperMon)
    static final double K_ON  = Constants.kATPOn2WithFormin;   // 11.6 µM⁻¹s⁻¹
    static final double K_OFF = Constants.kATPOff1;            // 0.8 s⁻¹  (Stage-1 default)
    static final double C_C   = K_OFF / K_ON;            // predicted critical concentration (µM)
    // segment-granularity correction: a pointed segment born at stdSegLength(32) depolymerizes to actinSeed-1(2)
    // via (32-2)=30 rate-k_off1 events, then DIES returning the last 2 monomers en masse (not at rate k_off1) ⇒
    // the effective off-rate is (32/30)× faster ⇒ effective critical conc = (stdSegLength/(stdSegLength-(actinSeed-1)))·C_c.
    static final double C_C_EFF = ((double) Constants.stdSegLength / (Constants.stdSegLength - (Constants.actinSeed - 1))) * (K_OFF / K_ON);
    static final int FILCAP = 64;
    static double uMper;                                 // set in main (V_BOX)

    public static void main(String[] args) {
        double dt = 1.0e-5;
        for (int i = 0; i < args.length; i++) if (args[i].equals("-cpu")) cpu = true;
        uMper = 1e21 / (V_BOX * Constants.AvogadroNum);
        double tau = 1.0 / (K_ON * uMper * Constants.biochemDeltaT);

        System.out.println("=== Soft Box increment 7 — TREADMILLING STEADY STATE vs first-principles balance ===");
        System.out.println("ONE formin filament, growth (barbed, rate=k_on·[actin]) + depoly (pointed, fixed k_off1), CLOSED pool.");
        System.out.printf("PARAMS: k_on=%.4f µM⁻¹s⁻¹, k_off1=%.4f s⁻¹, V_box=%.2f µm³ ⇒ µMperMon=%.4e, biochemΔt=%.0e s%n",
                K_ON, K_OFF, V_BOX, uMper, Constants.biochemDeltaT);
        System.out.printf("PREDICTION (computed BEFORE measuring): C_c = k_off1/k_on = %.6f µM  [p_ss≈%.1f free mono, τ≈%.0f cadences]%n",
                C_C, C_C / uMper, tau);
        System.out.println("runner: " + (cpu ? "CPU only (-cpu)" : "GPU + CPU cross-check") + ", dt=" + dt + "\n");

        // cheap single-run confirmation FIRST (staging) — does a steady state emerge at all?
        boolean g0 = checkSingleConfirm(tau);
        if (!g0) { System.out.println("BAIL: no steady state emerged in the confirmation run."); System.exit(1); }

        boolean g1 = checkConvergenceBothDirections(tau);   // same total, short vs long start ⇒ same C_c, L_ss
        boolean g2 = checkCcInvariance(tau);                 // 3 totals ⇒ same C_c, L_ss scales
        boolean g3 = checkCpuGpu();                          // lifecycle bit-identical

        boolean ok = g1 && g2 && g3;
        System.out.println();
        System.out.println("=== TREADMILLING VALIDATION " + (ok ? "PASS" : "FAIL") + " ===");
        if (!ok) System.out.println("(see per-section verdicts; a non-converging C_c is a REAL finding, reported not tuned)");
    }

    // ============================================================== scene
    static final class Scene {
        FilamentStore fil; NodeStore nodeStore; NodeNucleationStore nuc; GrowthStore grow; DepolyStore depoly;
        int runSeed;
        long monInit;          // Σ monomerCount at build (active) — for conservation
    }

    /** Build the shared pieces (1 node at origin, shared pool). Filament placement done by the callers. */
    static Scene shell(double pool0, double dt, double offRate, int runSeed) {
        Scene sc = new Scene();
        sc.runSeed = runSeed;
        NodeStore ns = new NodeStore(1, 1);
        ns.node.setCoord(0, 0f, 0f, 0f); ns.node.setUVec(0, 1f, 0f, 0f); ns.node.setYVec(0, 0f, 1f, 0f);
        ns.initNodeDrag();
        FilamentStore f = new FilamentStore(FILCAP, FILCAP);
        f.setParams(dt, Constants.brownianForceMag());
        f.setChainParams();
        f.setBirthParams(0.0, 0.0);          // Brownian off for the scalar measurement
        f.setBirthRequestCount(FILCAP);
        for (int s = 0; s < FILCAP; s++) { f.setCoord(s, 0f, 0f, 0f); f.setUVec(s, -1f, 0f, 0f); f.setYVec(s, 0f, 1f, 0f); f.markFree(s); }
        NodeNucleationStore nuc = new NodeNucleationStore(1, FILCAP, Constants.actinSeed, 1.0e30, V_BOX, 1.0);
        nuc.setTetherParams(Constants.fracMove, dt);
        GrowthStore g = new GrowthStore(FILCAP, K_ON, dt, pool0, V_BOX);
        DepolyStore d = new DepolyStore(FILCAP, offRate, dt, g.pool);   // SHARE the pool
        sc.fil = f; sc.nodeStore = ns; sc.nuc = nuc; sc.grow = g; sc.depoly = d;
        return sc;
    }

    /** Single-segment filament (slot 0), monomerInit monomers, node-bonded (the barbed AND pointed tip). */
    static Scene buildSingle(int monomerInit, double pool0, double dt, double offRate, int runSeed) {
        Scene sc = shell(pool0, dt, offRate, runSeed);
        FilamentStore f = sc.fil;
        double half = 0.5 * (monomerInit + 1) * Constants.actinMonoRadius;
        f.filState.set(0, FilamentStore.FIL_ACTIVE);
        f.monomerCount.set(0, monomerInit);
        f.setCoord(0, (float) half, 0f, 0f);            // end2 (barbed) at node origin; uVec inward (−x)
        f.setUVec(0, -1f, 0f, 0f); f.setYVec(0, 0f, 1f, 0f);
        sc.nuc.seedNode.set(0, 0);
        DragTensorSystem.run(f);
        sc.monInit = sumMonomers(f);
        return sc;
    }

    /** Pre-wired K-segment chain node—s0(barbed,seedNode=0)—s1—…—s_{K-1}(pointed tip). monPerSeg monomers each. */
    static Scene buildChain(int nSeg, int monPerSeg, double pool0, double dt, double offRate, int runSeed) {
        Scene sc = shell(pool0, dt, offRate, runSeed);
        FilamentStore f = sc.fil;
        double segL = (monPerSeg + 1) * Constants.actinMonoRadius;
        double x = 0.5 * segL;                          // s0 center; end2 at origin (node)
        for (int i = 0; i < nSeg; i++) {
            f.filState.set(i, FilamentStore.FIL_ACTIVE);
            f.monomerCount.set(i, monPerSeg);
            f.setCoord(i, (float) x, 0f, 0f); f.setUVec(i, -1f, 0f, 0f); f.setYVec(i, 0f, 1f, 0f);
            x += segL;
            // chain wiring (barbed=end2): s_i.end1(outward) ↔ s_{i+1}.end2(inward)
            if (i < nSeg - 1) { f.end1NbrSlot.set(i, i + 1); f.end1NbrSide.set(i, 1); }
            if (i > 0)        { f.end2NbrSlot.set(i, i - 1); f.end2NbrSide.set(i, 0); }
        }
        // s0 end2 = node (no chain neighbor); s_{K-1} end1 = pointed tip (free) — already sentinel −1
        sc.nuc.seedNode.set(0, 0);
        DragTensorSystem.run(f);
        sc.monInit = sumMonomers(f);
        return sc;
    }

    static long sumMonomers(FilamentStore f) { long m = 0; for (int s = 0; s < f.n; s++) if (f.filState.get(s) >= 0) m += f.monomerCount.get(s); return m; }
    static int countActive(FilamentStore f) { int c = 0; for (int s = 0; s < f.n; s++) if (f.filState.get(s) >= 0) c++; return c; }

    // ============================================================== one cadence (CPU; 3-step protocol)
    /** P from the CURRENT pool (step 1) → the grow/depoly/split/death kernels (step 2, NO internal pool update,
     *  depoly FIRST so death frees slots before the split allocates) → pool update from the integer counts
     *  (step 3). The identical protocol runs on the GPU plan ⇒ deterministic wang-hash decisions ⇒ bit-identical
     *  lifecycle. */
    static void cadenceCpu(Scene sc, int cadence) {
        FilamentStore f = sc.fil; GrowthStore g = sc.grow; DepolyStore d = sc.depoly;
        // step 1: probabilities from the current pool snapshot
        g.refreshRate(true); d.refreshRate(true);
        g.setCounts(cadence, sc.runSeed, true); d.setCounts(cadence, sc.runSeed, true);
        // step 2a: depoly + death (frees slots)
        DepolySystem.depoly(f.filState, f.monomerCount, f.coord, f.uVec, f.end1NbrSlot, d.returnedMon, d.deathFlag, d.depolyParams, d.depolyCounts);
        CrossBridgeSystem.csrScan(d.returnScanCounts, d.returnedMon, d.returnedOffsets);
        DepolySystem.applyDeath(f.filState, f.monomerCount, sc.nuc.seedNode, f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide,
                f.brownTransScale, f.brownRotScale, d.deathFlag, d.depolyCounts);
        // step 2b: growth + split (reclaims freed slots same cadence)
        GrowthSystem.grow(sc.nuc.seedNode, f.monomerCount, f.coord, f.uVec, g.grewFlag, g.growParams, g.growCounts);
        CrossBridgeSystem.csrScan(g.grewScanCounts, g.grewFlag, g.grewOffsets);
        GrowthSystem.markSplits(sc.nuc.seedNode, f.monomerCount, f.coord, f.uVec, f.yVec, f.acceptFlag, f.reqCoord, f.reqUVec, f.reqYVec, g.splitParams, g.growCounts);
        FilamentBirthSystem.freeFlags(f.filState, f.freeCount, f.allocCounts);
        CrossBridgeSystem.csrScan(f.freeScanCounts, f.freeCount, f.freeOffsets);
        FilamentBirthSystem.freeScatter(f.filState, f.freeOffsets, f.freeList, f.allocCounts);
        CrossBridgeSystem.csrScan(f.rankScanCounts, f.acceptFlag, f.rankOffsets);
        FilamentBirthSystem.allocate(f.reqCoord, f.reqUVec, f.reqYVec, f.rankOffsets, f.freeList, f.freeOffsets,
                f.coord, f.uVec, f.yVec, f.brownTransScale, f.brownRotScale, f.filState, f.birthParams, f.allocCounts);
        GrowthSystem.splitWire(f.rankOffsets, f.freeList, f.freeOffsets, f.monomerCount, f.coord, f.uVec,
                f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide, sc.nuc.seedNode, g.splitParams, f.allocCounts);
        // step 3: pool update from the integer counts (conservation)
        int grew = g.grewOffsets.get(FILCAP), ret = d.returnedOffsets.get(FILCAP);
        g.pool.take(grew); g.pool.put(ret);
    }

    /** Run nCad cadences; return {time-avg [actin] over the last windowFrac, time-avg L (monomers), final L}. */
    static double[] runTreadmill(Scene sc, int nCad, double windowFrac) {
        int wStart = (int) (nCad * (1.0 - windowFrac));
        double sumA = 0, sumL = 0; int nW = 0;
        for (int t = 0; t < nCad; t++) {
            cadenceCpu(sc, t);
            if (t >= wStart) { sumA += sc.grow.pool.conc(); sumL += sumMonomers(sc.fil); nW++; }
        }
        return new double[]{ sumA / nW, sumL / nW, sumMonomers(sc.fil) };
    }

    static boolean conservationOk(Scene sc) {
        long Fnow = sumMonomers(sc.fil);
        long taken = sc.grow.pool.totalTaken(), ret = sc.grow.pool.totalReturned();
        return Fnow == sc.monInit + taken - ret;
    }

    // ============================================================== 0. single-run confirmation
    static boolean checkSingleConfirm(double tau) {
        System.out.println("--- 0. single-run confirmation (does a steady state emerge?) + long convergence trace ---");
        int nCad = (int) (28 * tau);
        int monInit = 10; double total = 350 * uMper; double pool0 = total - monInit * uMper;
        Scene sc = buildSingle(monInit, pool0, 1.0e-5, K_OFF, SEED);
        System.out.printf("  trace (cadence/τ : [actin] µM : L monomers), total=%.5f µM; C_c=%.6f, C_c_eff=%.6f:%n", total, C_C, C_C_EFF);
        double winSum = 0; int winN = 0; int wStart = (int)(20 * tau);  // running mean over the last ~8τ
        for (int t = 0; t < nCad; t++) {
            cadenceCpu(sc, t);
            if (t >= wStart) { winSum += sc.grow.pool.conc(); winN++; }
            for (int k = 0; k <= 28; k += 2) if (t == (int)(k * tau)) System.out.printf("    %5.1fτ : %.6f : %d%n", (double)k, sc.grow.pool.conc(), sumMonomers(sc.fil));
        }
        double meanA = winSum / winN;
        boolean cons = conservationOk(sc);
        System.out.printf("  steady ⟨[actin]⟩ over last 8τ = %.6f µM (C_c %.1f%%, C_c_eff %.1f%%); conservation=%s%n",
                meanA, 100*Math.abs(meanA-C_C)/C_C, 100*Math.abs(meanA-C_C_EFF)/C_C_EFF, cons);
        boolean ok = cons && meanA > 0.5 * C_C && meanA < 2.0 * C_C;   // a steady state in the C_c ballpark emerged
        System.out.println("  => " + (ok ? "PASS (steady state emerges; asymptote located)" : "*FAIL*") + "\n");
        return ok;
    }

    // ============================================================== 1. convergence from both directions
    static boolean checkConvergenceBothDirections(double tau) {
        System.out.println("--- 1. convergence from BOTH directions (same total actin; short vs long start ⇒ same C_c, L_ss) ---");
        int nCad = (int) (22 * tau), nSeed = 8; double win = 0.6;   // long run + last ~13τ window (damped oscillation)
        double total = 350 * uMper;                       // same total for both
        // short start: 1 seg × 10 mono (grows up); long start: 11 seg × 31 mono = 341 (shrinks down)
        double[] shortR = avgSteady(() -> buildSingle(10, total - 10 * uMper, 1.0e-5, K_OFF, 0), nCad, win, nSeed);
        double[] longR  = avgSteady(() -> buildChain(11, 31, total - 341 * uMper, 1.0e-5, K_OFF, 0), nCad, win, nSeed);
        double relAgree = Math.abs(shortR[0] - longR[0]) / C_C_EFF;          // initial-condition independence (the KEY)
        double relEffS = Math.abs(shortR[0]-C_C_EFF)/C_C_EFF, relEffL = Math.abs(longR[0]-C_C_EFF)/C_C_EFF;
        double relLss = Math.abs(shortR[1] - longR[1]) / shortR[1];
        // thresholds = the single-filament fluctuation floor (NOT tuned physics): C_c_eff is the first-principles
        // granularity-corrected prediction; the band reflects the ±sqrt(p_ss) free-monomer Poisson scatter.
        boolean agree = relAgree < 0.05;                  // same [actin]_ss from both directions
        boolean nearCc = relEffS < 0.07 && relEffL < 0.07;
        boolean sameLss = relLss < 0.05;
        boolean cons = shortR[2] > 0.5 && longR[2] > 0.5;
        boolean ok = agree && nearCc && sameLss && cons;
        System.out.printf("  short start (10 mono, grows):  [actin]_ss=%.6f µM (C_c %.1f%%, C_c_eff %.1f%%), L_ss=%.0f mono%n",
                shortR[0], 100*Math.abs(shortR[0]-C_C)/C_C, 100*relEffS, shortR[1]);
        System.out.printf("  long  start (341 mono, shrinks): [actin]_ss=%.6f µM (C_c %.1f%%, C_c_eff %.1f%%), L_ss=%.0f mono%n",
                longR[0], 100*Math.abs(longR[0]-C_C)/C_C, 100*relEffL, longR[1]);
        System.out.printf("  ⇒ same [actin]_ss from both directions (rel %.1f%%); L_ss agree (rel %.1f%%); conservation EXACT=%s (n=%d seeds, last 12τ)%n",
                100*relAgree, 100*relLss, cons, nSeed);
        System.out.printf("  [predicted C_c=%.6f µM; granularity-corrected C_c_eff=%.6f µM = (stdSeg/(stdSeg−2))·C_c]%n", C_C, C_C_EFF);
        System.out.println("  => " + (ok ? "PASS (both initial conditions converge to the SAME steady [actin] ≈ C_c_eff + same L_ss)" : "*FAIL*") + "\n");
        return ok;
    }

    // ============================================================== 2. C_c invariance across total actin
    static boolean checkCcInvariance(double tau) {
        System.out.println("--- 2. C_c invariance (3 total-actin values ⇒ same [actin]_ss=C_c_eff; L_ss scales with total) ---");
        int nCad = (int) (10 * tau), nSeed = 5; double win = 0.6;   // start NEAR L_ss (small transient) ⇒ shorter run
        int[] totals = { 200, 350, 500 };                 // monomers
        int pssEff = (int) Math.round(C_C_EFF / uMper);   // ≈44 free monomers at SS (start the chain near L_ss)
        double[][] res = new double[totals.length][];
        double meanCc = 0;
        boolean lssScales = true, consAll = true;
        for (int i = 0; i < totals.length; i++) {
            final int N = totals[i];
            final int nSeg = Math.max(1, (int) Math.round((N - pssEff) / 32.0));   // ~32 mono/seg near L_ss
            res[i] = avgSteady(() -> buildChain(nSeg, 32, (N - nSeg * 32) * uMper, 1.0e-5, K_OFF, 0), nCad, win, nSeed);
            meanCc += res[i][0];
            double LssPred = N - C_C_EFF / uMper;          // granularity-corrected L_ss = N − p_ss_eff
            double relLss = Math.abs(res[i][1] - LssPred) / LssPred;
            if (relLss >= 0.06) lssScales = false;
            if (res[i][2] < 0.5) consAll = false;
            System.out.printf("  total=%3d mono (%.5f µM): [actin]_ss=%.6f µM (C_c %.1f%%, C_c_eff %.1f%%); L_ss=%.0f vs predicted %.0f (rel %.1f%%)%n",
                    N, N * uMper, res[i][0], 100*Math.abs(res[i][0]-C_C)/C_C, 100*Math.abs(res[i][0]-C_C_EFF)/C_C_EFF,
                    res[i][1], LssPred, 100 * relLss);
        }
        meanCc /= totals.length;
        double spread = 0; for (double[] r : res) spread = Math.max(spread, Math.abs(r[0] - meanCc) / meanCc);
        boolean invariant = spread < 0.04;                // all 3 within 4% of their mean ⇒ INVARIANT
        boolean nearEff = Math.abs(meanCc - C_C_EFF) / C_C_EFF < 0.05;
        boolean ok = invariant && nearEff && lssScales && consAll;
        System.out.printf("  ⇒ [actin]_ss INVARIANT across total actin (spread %.1f%% about mean %.6f µM, vs C_c_eff %.6f); L_ss scales: %s; conservation EXACT: %s%n",
                100*spread, meanCc, C_C_EFF, lssScales, consAll);
        System.out.println("  => " + (ok ? "PASS (C_c set by the rate balance — independent of total; L_ss by conservation)" : "*FAIL*") + "\n");
        return ok;
    }

    interface Builder { Scene build(); }
    /** Average steady [actin] + L over nSeed seeds; returns {meanA, meanL, consFlag(1/0)}. */
    static double[] avgSteady(Builder b, int nCad, double win, int nSeed) {
        double sa = 0, sl = 0; boolean cons = true;
        for (int s = 0; s < nSeed; s++) {
            Scene sc = b.build(); sc.runSeed = SEED + 1009 * s;
            double[] m = runTreadmill(sc, nCad, win);
            sa += m[0]; sl += m[1];
            if (!conservationOk(sc)) cons = false;
        }
        return new double[]{ sa / nSeed, sl / nSeed, cons ? 1 : 0 };
    }

    // ============================================================== 3. CPU≡GPU (lifecycle bit-identical)
    static boolean checkCpuGpu() {
        if (cpu) { System.out.println("--- 3. CPU≡GPU: skipped (-cpu) ---\n"); return true; }
        System.out.println("--- 3. CPU≡GPU (treadmill cadence pipeline; deterministic ⇒ lifecycle bit-identical) ---");
        int nCad = 20000;                                  // moderate horizon (splits + deaths happen)
        int monInit = 10; double total = 350 * uMper; double pool0 = total - monInit * uMper;
        Scene cpuS = buildSingle(monInit, pool0, 1.0e-5, K_OFF, SEED);
        Scene gpuS = buildSingle(monInit, pool0, 1.0e-5, K_OFF, SEED);
        for (int t = 0; t < nCad; t++) cadenceCpu(cpuS, t);
        runGpu(gpuS, nCad);
        int monMis = 0, stateMis = 0, linkMis = 0;
        for (int s = 0; s < FILCAP; s++) {
            if (cpuS.fil.monomerCount.get(s) != gpuS.fil.monomerCount.get(s)) monMis++;
            if (cpuS.fil.filState.get(s) != gpuS.fil.filState.get(s)) stateMis++;
            if (cpuS.fil.end1NbrSlot.get(s) != gpuS.fil.end1NbrSlot.get(s) || cpuS.fil.end2NbrSlot.get(s) != gpuS.fil.end2NbrSlot.get(s)) linkMis++;
        }
        double aC = cpuS.grow.pool.conc(), aG = gpuS.grow.pool.conc();
        long lC = sumMonomers(cpuS.fil), lG = sumMonomers(gpuS.fil);
        boolean poolMatch = Math.abs(aC - aG) < 1e-9 && lC == lG;
        boolean ok = monMis == 0 && stateMis == 0 && linkMis == 0 && poolMatch;
        System.out.printf("  after %d cadences: mismatches monomer=%d state=%d link=%d; [actin] CPU=%.6f GPU=%.6f; L CPU=%d GPU=%d => %s%n",
                nCad, monMis, stateMis, linkMis, aC, aG, lC, lG, ok ? "PASS" : "*FAIL*");
        System.out.println("  (wang-hash grow/depoly + integer split/death/markFree ⇒ bit-identical lifecycle + pool; coord float32 only.)");
        System.out.println("  => " + (ok ? "PASS" : "*FAIL*") + "\n");
        return ok;
    }

    // ---- GPU cadence-only combined plan (depoly+death THEN growth+split; pool updated host-side per cadence) ----
    static TornadoExecutionPlan buildPlan(Scene sc) {
        FilamentStore f = sc.fil; GrowthStore g = sc.grow; DepolyStore d = sc.depoly;
        TaskGraph tg = new TaskGraph("treadmill")
            .transferToDevice(DataTransferMode.FIRST_EXECUTION,
                    sc.nuc.seedNode, d.depolyParams, d.dragParams, d.returnedMon, d.returnedOffsets, d.returnScanCounts, d.deathFlag,
                    g.splitParams, g.dragParams, g.grewFlag, g.grewOffsets, g.grewScanCounts,
                    f.acceptFlag, f.reqCoord, f.reqUVec, f.reqYVec, f.freeCount, f.freeOffsets, f.freeList, f.freeScanCounts,
                    f.rankOffsets, f.rankScanCounts, f.allocCounts, f.birthParams,
                    f.coord, f.uVec, f.yVec, f.segLength, f.monomerCount, f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide,
                    f.bTransGam, f.bRotGam, f.bTransDiff, f.bRotDiff, f.brownTransScale, f.brownRotScale, f.filState)
            .transferToDevice(DataTransferMode.EVERY_EXECUTION, d.depolyCounts, g.growParams, g.growCounts)
            .task("depoly", DepolySystem::depoly, f.filState, f.monomerCount, f.coord, f.uVec, f.end1NbrSlot, d.returnedMon, d.deathFlag, d.depolyParams, d.depolyCounts)
            .task("csrReturn", CrossBridgeSystem::csrScan, d.returnScanCounts, d.returnedMon, d.returnedOffsets)
            .task("applyDeath", DepolySystem::applyDeath, f.filState, f.monomerCount, sc.nuc.seedNode, f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide, f.brownTransScale, f.brownRotScale, d.deathFlag, d.depolyCounts)
            .task("grow", GrowthSystem::grow, sc.nuc.seedNode, f.monomerCount, f.coord, f.uVec, g.grewFlag, g.growParams, g.growCounts)
            .task("csrGrew", CrossBridgeSystem::csrScan, g.grewScanCounts, g.grewFlag, g.grewOffsets)
            .task("markSplits", GrowthSystem::markSplits, sc.nuc.seedNode, f.monomerCount, f.coord, f.uVec, f.yVec, f.acceptFlag, f.reqCoord, f.reqUVec, f.reqYVec, g.splitParams, g.growCounts)
            .task("freeFlags", FilamentBirthSystem::freeFlags, f.filState, f.freeCount, f.allocCounts)
            .task("csrFree", CrossBridgeSystem::csrScan, f.freeScanCounts, f.freeCount, f.freeOffsets)
            .task("freeScatter", FilamentBirthSystem::freeScatter, f.filState, f.freeOffsets, f.freeList, f.allocCounts)
            .task("csrRank", CrossBridgeSystem::csrScan, f.rankScanCounts, f.acceptFlag, f.rankOffsets)
            .task("allocate", FilamentBirthSystem::allocate, f.reqCoord, f.reqUVec, f.reqYVec, f.rankOffsets, f.freeList, f.freeOffsets,
                    f.coord, f.uVec, f.yVec, f.brownTransScale, f.brownRotScale, f.filState, f.birthParams, f.allocCounts)
            .task("splitWire", GrowthSystem::splitWire, f.rankOffsets, f.freeList, f.freeOffsets, f.monomerCount, f.coord, f.uVec,
                    f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide, sc.nuc.seedNode, g.splitParams, f.allocCounts)
            .transferToHost(DataTransferMode.UNDER_DEMAND, f.monomerCount, f.filState, f.end1NbrSlot, f.end2NbrSlot, g.grewOffsets, d.returnedOffsets);
        sched = new GridScheduler();
        int C = f.n;
        addW("treadmill.depoly", pad(C)); addS("treadmill.csrReturn"); addW("treadmill.applyDeath", pad(C));
        addW("treadmill.grow", pad(C)); addS("treadmill.csrGrew"); addW("treadmill.markSplits", pad(C));
        addW("treadmill.freeFlags", pad(C)); addS("treadmill.csrFree"); addS("treadmill.freeScatter"); addS("treadmill.csrRank");
        addW("treadmill.allocate", pad(C)); addW("treadmill.splitWire", pad(C));
        return new TornadoExecutionPlan(tg.snapshot());
    }

    static void runGpu(Scene sc, int nCad) {
        TornadoExecutionPlan plan = buildPlan(sc);
        FilamentStore f = sc.fil; GrowthStore g = sc.grow; DepolyStore d = sc.depoly;
        for (int t = 0; t < nCad; t++) {
            g.refreshRate(true); d.refreshRate(true);                 // P from current pool (step 1)
            g.setCounts(t, sc.runSeed, true); d.setCounts(t, sc.runSeed, true);
            TornadoExecutionResult res = plan.withGridScheduler(sched).execute();
            res.transferToHost(g.grewOffsets, d.returnedOffsets);     // step 3: counts → host pool
            g.pool.take(g.grewOffsets.get(FILCAP)); g.pool.put(d.returnedOffsets.get(FILCAP));
            if (t == nCad - 1) res.transferToHost(f.monomerCount, f.filState, f.end1NbrSlot, f.end2NbrSlot);
        }
    }

    // ============================================================== utils
    static int pad(int n) { return ((n + B - 1) / B) * B; }
    static void addW(String name, int gg) { WorkerGrid w = new WorkerGrid1D(gg); w.setLocalWork(B, 1, 1); sched.addWorkerGrid(name, w); }
    static void addS(String name) { WorkerGrid w = new WorkerGrid1D(1); w.setLocalWork(1, 1, 1); sched.addWorkerGrid(name, w); }
}
