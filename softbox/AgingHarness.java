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
 * Increment 7 Aging build (A) VALIDATION — the per-segment nucleotide-composition proxy + nucleotide-dependent
 * depoly rates. Filaments AGE (per-segment ATP→ADP-Pi→ADP, watchable as a cascade along the filament) and the
 * aging DRIVES the pointed-end depoly rate (nucleotide-asymmetric treadmilling, a NEW C_c). Default-off overall;
 * this assay opts in. Run: ./run_aging.sh [-cpu]. No default flip.
 *
 * The proxy is a NEW v2 representation (v1 carries a per-monomer Monomer list; the inc-6 assays ran
 * noMonomersSimd=true ⇒ no monomers), faithful to v1's per-monomer cascade KINETICS in AGGREGATE, NOT a
 * per-monomer bit-match (recon §2 / CLAUDE.md §8). Adjudicate emergent behavior vs FIRST PRINCIPLES.
 *
 * Gates:
 *   A aging-kinetics — a freshly-ATP segment held STATIC ages ATP→ADP-Pi→ADP at the cascade rates; the aggregate
 *     composition trajectory matches the analytic two-step linear-ODE solution. CPU≡GPU (aging arithmetic).
 *   B asymmetric-C_c — the proxy-driven treadmill converges to a NEW critical concentration. PREDICTION (computed
 *     before measuring): the pointed segment is ≈100% ADP at steady state (transit ≫ aging time) ⇒ off-rate ≈
 *     kADPOff1 ⇒ C_c = kADPOff1/k_on (vs Stage-1 fixed kATPOff1/k_on); with the segment-granularity death-floor
 *     correction C_c_eff = (stdSeg/(stdSeg−(actinSeed−1)))·C_c. MEASURED + adjudicated (§8). Same across totals.
 *   C conservation — EXACT integer ledger through the aged grow/depoly/death churn.
 *   D CPU≡GPU — the full proxy pipeline: aging arithmetic + rate wiring (aggregate-within-SEM; the float32-last-bit
 *     rate feeds a wang-hash decision ⇒ chaotic decorrelation over a long horizon — the §8 standard).
 *   E fixed-rate baseline — with depoly in FIXED mode the proxy does NOT perturb the Stage-1 lifecycle
 *     (bit-identical: aging writes only nucFrac, which fixed-mode depoly never reads).
 */
public final class AgingHarness {

    static final int B = 64;
    static GridScheduler sched;
    static boolean cpu = false;
    static final int SEED = 0x47A61;                       // "AGE1"-ish
    static final double V_BOX = 1.0;                        // µm³ (closed pool)
    static final double K_ON  = Constants.kATPOn2WithFormin;   // 11.6 µM⁻¹s⁻¹
    static final double K_OFF_ATP = Constants.kATPOff1;        // 0.8 s⁻¹ (the Stage-1 fixed rate)
    static final double K_OFF_ADP = Constants.kADPOff1;        // 2.7 s⁻¹ (the steady pointed-end rate, proxy)
    static final double C_C_FIXED = K_OFF_ATP / K_ON;         // Stage-1 critical conc (for reference)
    static final double C_C   = K_OFF_ADP / K_ON;            // PROXY ideal critical conc (pointed≈100% ADP)
    static final double GRAN  = (double) Constants.stdSegLength / (Constants.stdSegLength - (Constants.actinSeed - 1));
    static final double C_C_EFF = GRAN * C_C;               // granularity-corrected (the measurement target)
    static final int FILCAP = 64;
    static double uMper;

    public static void main(String[] args) {
        double dt = 1.0e-5;
        for (String a : args) if (a.equals("-cpu")) cpu = true;
        uMper = 1e21 / (V_BOX * Constants.AvogadroNum);

        System.out.println("=== Soft Box increment 7 — AGING build (A): per-segment nucleotide proxy + nucleotide-dependent depoly ===");
        System.out.println("3-component per-segment proxy (f_ATP, f_ADPPi, f_ADP); physics reads f_ADP; the cascade is watchable.");
        System.out.printf("cascade: kHydrolysis=%.2f/s (ATP→ADP-Pi), kDissociation=%.2f/s (ADP-Pi→ADP); biochemΔt=%.0e s%n",
                Constants.kHydrolysis, Constants.kDissociation, Constants.biochemDeltaT);
        System.out.printf("depoly: pointed off-rate interpolates kATPOff1=%.2f ↔ kADPOff1=%.2f /s by the pointed segment's f_ADP%n",
                K_OFF_ATP, K_OFF_ADP);
        System.out.printf("PREDICTION: proxy C_c = kADPOff1/k_on = %.6f µM (vs Stage-1 fixed %.6f); C_c_eff = (%d/%d)·C_c = %.6f µM%n",
                C_C, C_C_FIXED, Constants.stdSegLength, Constants.stdSegLength - (Constants.actinSeed - 1), C_C_EFF);
        System.out.println("runner: " + (cpu ? "CPU only (-cpu)" : "GPU + CPU cross-check") + ", dt=" + dt + "\n");

        boolean gA = checkAgingKinetics();
        boolean gE = checkFixedBaseline(dt);
        boolean gC = checkConservation(dt);
        boolean gB = checkAsymmetricCc(dt);
        boolean gD = checkCpuGpuPipeline(dt);

        boolean ok = gA && gB && gC && gD && gE;
        System.out.println();
        System.out.println("=== AGING (build A: proxy + nucleotide-dependent rates) VALIDATION " + (ok ? "PASS" : "FAIL") + " ===");
        if (!ok) { System.out.println("BAIL-OUT: a gate failed. Commit nothing; report the gap (do NOT tune)."); System.exit(1); }
    }

    // ============================================================== scene
    static final class Scene {
        FilamentStore fil; NodeStore nodeStore; NodeNucleationStore nuc; GrowthStore grow; DepolyStore depoly; AgingStore aging;
        int runSeed; long monInit;
    }

    static Scene shell(double pool0, double dt, double offRate, int runSeed) {
        Scene sc = new Scene();
        sc.runSeed = runSeed;
        NodeStore ns = new NodeStore(1, 1);
        ns.node.setCoord(0, 0f, 0f, 0f); ns.node.setUVec(0, 1f, 0f, 0f); ns.node.setYVec(0, 0f, 1f, 0f);
        ns.initNodeDrag();
        FilamentStore f = new FilamentStore(FILCAP, FILCAP);
        f.setParams(dt, Constants.brownianForceMag(dt));
        f.setChainParams(dt);
        f.setBirthParams(0.0, 0.0);            // Brownian off for the scalar measurement
        f.setBirthRequestCount(FILCAP);
        for (int s = 0; s < FILCAP; s++) { f.setCoord(s, 0f, 0f, 0f); f.setUVec(s, -1f, 0f, 0f); f.setYVec(s, 0f, 1f, 0f); f.markFree(s); }
        NodeNucleationStore nuc = new NodeNucleationStore(1, FILCAP, Constants.actinSeed, 1.0e30, V_BOX, 1.0);
        nuc.setTetherParams(Constants.fracMove, dt);
        GrowthStore g = new GrowthStore(FILCAP, K_ON, dt, pool0, V_BOX);
        DepolyStore d = new DepolyStore(FILCAP, offRate, dt, g.pool);   // SHARE the pool
        AgingStore aging = new AgingStore(FILCAP);
        sc.fil = f; sc.nodeStore = ns; sc.nuc = nuc; sc.grow = g; sc.depoly = d; sc.aging = aging;
        return sc;
    }

    /** Pre-wired K-segment chain node—s0(barbed,seedNode=0)—…—s_{K-1}(pointed tip). monPerSeg each; nucFrac init ATP. */
    static Scene buildChain(int nSeg, int monPerSeg, double pool0, double dt, double offRate, int runSeed) {
        Scene sc = shell(pool0, dt, offRate, runSeed);
        FilamentStore f = sc.fil;
        double segL = (monPerSeg + 1) * Constants.actinMonoRadius;
        double x = 0.5 * segL;
        for (int i = 0; i < nSeg; i++) {
            f.filState.set(i, FilamentStore.FIL_ACTIVE);
            f.monomerCount.set(i, monPerSeg);
            f.setCoord(i, (float) x, 0f, 0f); f.setUVec(i, -1f, 0f, 0f); f.setYVec(i, 0f, 1f, 0f);
            sc.aging.setATP(i);                 // fresh; ages into the steady gradient
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

    // ============================================================== one cadence (CPU)
    /** proxy: true ⇒ nucleotide-dependent depoly (depolyProxy reads f_ADP); false ⇒ FIXED-rate depoly (Stage 1).
     *  agingOn: true ⇒ run age + growthAtp + splitInheritNuc (write nucFrac); false ⇒ skip them. */
    static void cadenceCpu(Scene sc, int cadence, boolean proxy, boolean agingOn) {
        FilamentStore f = sc.fil; GrowthStore g = sc.grow; DepolyStore d = sc.depoly; AgingStore ag = sc.aging;
        g.refreshRate(true); d.refreshRate(true); ag.refresh(agingOn);
        g.setCounts(cadence, sc.runSeed, true); d.setCounts(cadence, sc.runSeed, true); ag.setFires(true);
        // 1. age (before depoly: monomers age then the pointed terminal depolymerizes)
        if (agingOn) AgingSystem.age(f.filState, ag.nucFrac, ag.agingParams, ag.agingCounts);
        // 2. depoly (+ death frees slots) — proxy or fixed rate
        if (proxy)
            DepolySystem.depolyProxy(f.filState, f.monomerCount, f.coord, f.uVec, f.end1NbrSlot, ag.nucFrac,
                    d.returnedMon, d.deathFlag, d.depolyParams, ag.depolyRateParams, d.depolyCounts);
        else
            DepolySystem.depoly(f.filState, f.monomerCount, f.coord, f.uVec, f.end1NbrSlot,
                    d.returnedMon, d.deathFlag, d.depolyParams, d.depolyCounts);
        CrossBridgeSystem.csrScan(d.returnScanCounts, d.returnedMon, d.returnedOffsets);
        DepolySystem.applyDeath(f.filState, f.monomerCount, sc.nuc.seedNode, f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide,
                f.brownTransScale, f.brownRotScale, d.deathFlag, d.depolyCounts);
        // 3. grow (barbed tip)
        GrowthSystem.grow(sc.nuc.seedNode, f.monomerCount, f.coord, f.uVec, g.grewFlag, g.growParams, g.growCounts);
        CrossBridgeSystem.csrScan(g.grewScanCounts, g.grewFlag, g.grewOffsets);
        // 4. growthAtp (the grown tip gained a fresh ATP monomer ⇒ reweight toward ATP)
        if (agingOn) AgingSystem.growthAtp(g.grewFlag, f.monomerCount, ag.nucFrac);
        // 5. split allocator
        GrowthSystem.markSplits(sc.nuc.seedNode, f.monomerCount, f.coord, f.uVec, f.yVec, f.acceptFlag, f.reqCoord, f.reqUVec, f.reqYVec, g.splitParams, g.growCounts);
        FilamentBirthSystem.freeFlags(f.filState, f.freeCount, f.allocCounts);
        CrossBridgeSystem.csrScan(f.freeScanCounts, f.freeCount, f.freeOffsets);
        FilamentBirthSystem.freeScatter(f.filState, f.freeOffsets, f.freeList, f.allocCounts);
        CrossBridgeSystem.csrScan(f.rankScanCounts, f.acceptFlag, f.rankOffsets);
        FilamentBirthSystem.allocate(f.reqCoord, f.reqUVec, f.reqYVec, f.rankOffsets, f.freeList, f.freeOffsets,
                f.coord, f.uVec, f.yVec, f.brownTransScale, f.brownRotScale, f.filState, f.birthParams, f.allocCounts);
        GrowthSystem.splitWire(f.rankOffsets, f.freeList, f.freeOffsets, f.monomerCount, f.coord, f.uVec,
                f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide, sc.nuc.seedNode, g.splitParams, f.allocCounts);
        // 6. splitInheritNuc (the child inherits the parent composition)
        if (agingOn) AgingSystem.splitInheritNuc(f.rankOffsets, f.freeList, f.freeOffsets, ag.nucFrac, f.allocCounts);
        // 7. pool update from the integer counts (conservation)
        g.pool.take(g.grewOffsets.get(FILCAP)); g.pool.put(d.returnedOffsets.get(FILCAP));
    }

    static double[] runTreadmill(Scene sc, int nCad, double windowFrac) {
        int wStart = (int) (nCad * (1.0 - windowFrac));
        double sumA = 0, sumL = 0; int nW = 0;
        for (int t = 0; t < nCad; t++) {
            cadenceCpu(sc, t, true, true);
            if (t >= wStart) { sumA += sc.grow.pool.conc(); sumL += sumMonomers(sc.fil); nW++; }
        }
        return new double[]{ sumA / nW, sumL / nW, sumMonomers(sc.fil) };
    }

    static boolean conservationOk(Scene sc) {
        long Fnow = sumMonomers(sc.fil);
        return Fnow == sc.monInit + sc.grow.pool.totalTaken() - sc.grow.pool.totalReturned();
    }

    // ============================================================== A. aging kinetics (vs analytic) + CPU≡GPU
    static boolean checkAgingKinetics() {
        System.out.println("--- A. aging kinetics (a freshly-ATP segment ages ATP→ADP-Pi→ADP vs the analytic two-step ODE) ---");
        double kH = Constants.kHydrolysis, kD = Constants.kDissociation, bc = Constants.biochemDeltaT;
        // CPU: a single static ATP segment aged N cadences; compare composition at several times to the analytic.
        double[] checkT = { 1.0, 3.0, 5.0, 10.0 };           // seconds
        double maxErr = 0;
        System.out.println("    t(s) :   f_ATP (sim/an)   f_ADPPi (sim/an)   f_ADP (sim/an)   maxAbsErr");
        for (double T : checkT) {
            int N = (int) Math.round(T / bc);
            AgingStore ag = new AgingStore(8);
            IntArray fil = new IntArray(8); fil.set(0, FilamentStore.FIL_ACTIVE);
            for (int s = 1; s < 8; s++) fil.set(s, FilamentStore.FIL_FREE);
            ag.setATP(0); ag.refresh(true); ag.setFires(true);
            for (int t = 0; t < N; t++) AgingSystem.age(fil, ag.nucFrac, ag.agingParams, ag.agingCounts);
            double fATPan = Math.exp(-kH * T);
            double fADPPian = kH / (kD - kH) * (Math.exp(-kH * T) - Math.exp(-kD * T));
            double fADPan = 1.0 - fATPan - fADPPian;
            double e = Math.max(Math.abs(ag.fATP(0) - fATPan), Math.max(Math.abs(ag.fADPPi(0) - fADPPian), Math.abs(ag.fADP(0) - fADPan)));
            maxErr = Math.max(maxErr, e);
            System.out.printf("   %5.1f : %.5f/%.5f   %.5f/%.5f   %.5f/%.5f   %.2e%n",
                    T, ag.fATP(0), fATPan, ag.fADPPi(0), fADPPian, ag.fADP(0), fADPan, e);
        }
        boolean kinOk = maxErr < 0.01;                       // forward-Euler error ≪ 1% (small per-cadence p)
        // sum-conservation through aging
        boolean sumOk;
        {
            AgingStore ag = new AgingStore(4); IntArray fil = new IntArray(4); fil.set(0, FilamentStore.FIL_ACTIVE);
            ag.set(0, 0.4f, 0.35f, 0.25f); ag.refresh(true); ag.setFires(true);
            for (int t = 0; t < 5000; t++) AgingSystem.age(fil, ag.nucFrac, ag.agingParams, ag.agingCounts);
            double sum = ag.fATP(0) + ag.fADPPi(0) + ag.fADP(0);
            sumOk = Math.abs(sum - 1.0) < 1e-5;
            System.out.printf("    composition-sum after 5000 cadences = %.7f (==1): %s%n", sum, sumOk ? "ok" : "*FAIL*");
        }
        // CPU≡GPU on the aging arithmetic
        boolean cgOk = true; double cgDiff = 0;
        if (!cpu) {
            int N = 10000; AgingStore cpuA = new AgingStore(8), gpuA = new AgingStore(8);
            IntArray fil = new IntArray(8); fil.set(0, FilamentStore.FIL_ACTIVE);
            cpuA.setATP(0); gpuA.setATP(0); cpuA.refresh(true); gpuA.refresh(true); cpuA.setFires(true); gpuA.setFires(true);
            for (int t = 0; t < N; t++) AgingSystem.age(fil, cpuA.nucFrac, cpuA.agingParams, cpuA.agingCounts);
            runAgeGpu(fil, gpuA, N);
            cgDiff = Math.max(Math.abs(cpuA.fATP(0) - gpuA.fATP(0)), Math.max(Math.abs(cpuA.fADPPi(0) - gpuA.fADPPi(0)), Math.abs(cpuA.fADP(0) - gpuA.fADP(0))));
            cgOk = cgDiff < 1e-5;
            System.out.printf("    CPU≡GPU aging (10000 cadences): max|Δ composition|=%.2e (<1e-5): %s%n", cgDiff, cgOk ? "ok" : "*FAIL*");
        } else System.out.println("    CPU≡GPU aging: skipped (-cpu)");
        boolean ok = kinOk && sumOk && cgOk;
        System.out.printf("  ⇒ max aggregate error vs analytic = %.2e (<1e-2); sum conserved; CPU≡GPU%n", maxErr);
        System.out.println("  => " + (ok ? "PASS (the proxy reproduces the cascade kinetics in aggregate)" : "*FAIL*") + "\n");
        return ok;
    }

    // ============================================================== E. fixed-rate baseline preserved bit-identical
    static boolean checkFixedBaseline(double dt) {
        System.out.println("--- E. fixed-rate baseline preserved (aging present but depoly FIXED ⇒ lifecycle bit-identical to Stage-1) ---");
        int nCad = 30000;
        double total = 350 * uMper;
        Scene withAging = buildChain(6, 31, total - 6 * 31 * uMper, dt, K_OFF_ATP, SEED);
        Scene stage1    = buildChain(6, 31, total - 6 * 31 * uMper, dt, K_OFF_ATP, SEED);
        for (int t = 0; t < nCad; t++) cadenceCpu(withAging, t, false, true);    // FIXED depoly + aging ON
        for (int t = 0; t < nCad; t++) cadenceCpu(stage1,    t, false, false);   // FIXED depoly, no aging (Stage-1)
        int monMis = 0, stateMis = 0, linkMis = 0;
        for (int s = 0; s < FILCAP; s++) {
            if (withAging.fil.monomerCount.get(s) != stage1.fil.monomerCount.get(s)) monMis++;
            if (withAging.fil.filState.get(s) != stage1.fil.filState.get(s)) stateMis++;
            if (withAging.fil.end1NbrSlot.get(s) != stage1.fil.end1NbrSlot.get(s) || withAging.fil.end2NbrSlot.get(s) != stage1.fil.end2NbrSlot.get(s)) linkMis++;
        }
        boolean poolMatch = Math.abs(withAging.grow.pool.conc() - stage1.grow.pool.conc()) < 1e-12;
        boolean ok = monMis == 0 && stateMis == 0 && linkMis == 0 && poolMatch;
        System.out.printf("  after %d cadences: mismatches monomer=%d state=%d link=%d; pool match=%s => %s%n",
                nCad, monMis, stateMis, linkMis, poolMatch, ok ? "PASS" : "*FAIL*");
        System.out.println("  (the proxy writes ONLY nucFrac, which the FIXED-rate depoly never reads ⇒ the Stage-1 path is untouched.)");
        System.out.println("  => " + (ok ? "PASS" : "*FAIL*") + "\n");
        return ok;
    }

    // ============================================================== C. conservation EXACT
    static boolean checkConservation(double dt) {
        System.out.println("--- C. conservation EXACT (pool + Σ monomerCount = const) through the aged grow/depoly/death churn ---");
        double total = 350 * uMper;
        Scene sc = buildChain(6, 31, total - 6 * 31 * uMper, dt, K_OFF_ADP, SEED);
        int nCad = 200000;
        boolean consAll = true;
        for (int t = 0; t < nCad; t++) {
            cadenceCpu(sc, t, true, true);
            if ((t % 20000) == 0 && !conservationOk(sc)) consAll = false;
        }
        boolean cons = conservationOk(sc) && consAll;
        long taken = sc.grow.pool.totalTaken(), ret = sc.grow.pool.totalReturned();
        System.out.printf("  after %d aged cadences: monInit=%d, taken=%d, returned=%d, Σnow=%d ⇒ Σnow==monInit+taken−returned: %s%n",
                nCad, sc.monInit, taken, ret, sumMonomers(sc.fil), cons);
        System.out.printf("      (both directions exercised: growth took %d, turnover returned %d monomers; integer-exact ledger)%n", taken, ret);
        System.out.println("  => " + (cons ? "PASS" : "*FAIL*") + "\n");
        return cons;
    }

    // ============================================================== B. nucleotide-asymmetric C_c (predict + measure)
    static boolean checkAsymmetricCc(double dt) {
        System.out.println("--- B. nucleotide-asymmetric treadmilling C_c (PREDICTED before measuring; adjudicated vs first principles §8) ---");
        double tau = 1.0 / (K_ON * uMper * Constants.biochemDeltaT);
        int nCad = (int) (14 * tau), nSeed = 6; double win = 0.5;
        int pssEff = (int) Math.round(C_C_EFF / uMper);
        System.out.printf("  reasoning: at steady state the pointed segment is ≈100%% ADP (transit ≫ ~%.1f s aging) ⇒ off-rate ≈ kADPOff1%n",
                1.0 / Constants.kHydrolysis + 1.0 / Constants.kDissociation);
        System.out.printf("  ⇒ C_c = kADPOff1/k_on = %.6f µM; C_c_eff (granularity) = %.6f µM [p_ss_eff≈%d free mono]; τ≈%.0f cadences, %d seeds, last %.0f%%%n",
                C_C, C_C_EFF, pssEff, tau, nSeed, 100 * win);
        int[] totals = { 350, 500 };
        double meanCc = 0; boolean nearEff = true, consAll = true; double[] meas = new double[totals.length];
        for (int i = 0; i < totals.length; i++) {
            final int N = totals[i];
            final int nSeg = Math.max(1, (int) Math.round((N - pssEff) / 32.0));
            double[] r = avgSteady(() -> buildChain(nSeg, 32, (N - nSeg * 32) * uMper, dt, K_OFF_ADP, 0), nCad, win, nSeed);
            meas[i] = r[0]; meanCc += r[0];
            double LssPred = N - C_C_EFF / uMper;
            double relEff = Math.abs(r[0] - C_C_EFF) / C_C_EFF;
            double relFixed = Math.abs(r[0] - C_C_FIXED) / C_C_FIXED;
            if (relEff >= 0.08) nearEff = false;
            if (r[2] < 0.5) consAll = false;
            System.out.printf("  total=%3d mono: [actin]_ss=%.6f µM (vs C_c_eff %.1f%%, vs Stage-1 fixed C_c %.0f%% — clearly the ADP rate); L_ss=%.0f vs %.0f%n",
                    N, r[0], 100 * relEff, 100 * relFixed, r[1], LssPred);
        }
        meanCc /= totals.length;
        double spread = 0; for (double m : meas) spread = Math.max(spread, Math.abs(m - meanCc) / meanCc);
        boolean invariant = spread < 0.06;
        boolean clearlyADP = Math.abs(meanCc - C_C_EFF) < Math.abs(meanCc - GRAN * C_C_FIXED);  // closer to ADP than ATP
        boolean ok = nearEff && invariant && consAll && clearlyADP;
        System.out.printf("  ⇒ mean [actin]_ss=%.6f µM vs C_c_eff %.6f (%.1f%%); invariant across totals (spread %.1f%%); the new C_c is the kADPOff1 rate (≈%.1f× the Stage-1 fixed C_c)%n",
                meanCc, C_C_EFF, 100 * Math.abs(meanCc - C_C_EFF) / C_C_EFF, 100 * spread, C_C / C_C_FIXED);
        System.out.println("  (the aging drives the pointed end to ADP ⇒ the asymmetric off-rate ⇒ a NEW critical concentration, matched to first principles — NOT tuned.)");
        System.out.println("  => " + (ok ? "PASS" : "*FAIL* (a gap is a REAL finding — report, do NOT tune)") + "\n");
        return ok;
    }

    interface Builder { Scene build(); }
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

    // ============================================================== D. CPU≡GPU full proxy pipeline (aggregate-within-SEM)
    static boolean checkCpuGpuPipeline(double dt) {
        if (cpu) { System.out.println("--- D. CPU≡GPU full proxy pipeline: skipped (-cpu) ---\n"); return true; }
        System.out.println("--- D. CPU≡GPU full proxy pipeline (aging + nucleotide-rate wiring; §8 aggregate-within-SEM) ---");
        int nCad = 40000;
        double total = 350 * uMper; double pool0 = total - 6 * 31 * uMper;
        Scene cpuS = buildChain(6, 31, pool0, dt, K_OFF_ADP, SEED);
        Scene gpuS = buildChain(6, 31, pool0, dt, K_OFF_ADP, SEED);
        for (int t = 0; t < nCad; t++) cadenceCpu(cpuS, t, true, true);
        runProxyGpu(gpuS, nCad);
        // bit-identicality (likely decorrelates at the float-last-bit→decision flip; report)
        int monMis = 0, stateMis = 0;
        for (int s = 0; s < FILCAP; s++) {
            if (cpuS.fil.monomerCount.get(s) != gpuS.fil.monomerCount.get(s)) monMis++;
            if (cpuS.fil.filState.get(s) != gpuS.fil.filState.get(s)) stateMis++;
        }
        double aC = cpuS.grow.pool.conc(), aG = gpuS.grow.pool.conc();
        long lC = sumMonomers(cpuS.fil), lG = sumMonomers(gpuS.fil);
        double relA = Math.abs(aC - aG) / C_C_EFF;
        double relL = lC + lG > 0 ? Math.abs((double) (lC - lG)) / (0.5 * (lC + lG)) : 0;
        boolean bitIdent = monMis == 0 && stateMis == 0;
        boolean aggOk = relA < 0.10 && relL < 0.15;          // within the single-filament fluctuation band
        boolean ok = aggOk;                                  // the §8 standard (bit-identicality is a bonus, reported)
        System.out.printf("  after %d cadences: [actin] CPU=%.6f GPU=%.6f (rel %.1f%% of C_c_eff); L CPU=%d GPU=%d (rel %.1f%%); bit-identical lifecycle=%s%n",
                nCad, aC, aG, 100 * relA, lC, lG, 100 * relL, bitIdent);
        System.out.println("  (the proxy depoly rate is a float32 that feeds a wang-hash decision ⇒ chaotic decorrelation over a long horizon — §8 aggregate-within-SEM, like gliding.)");
        System.out.println("  => " + (ok ? "PASS (aggregate-within-SEM)" : "*FAIL*") + "\n");
        return ok;
    }

    // ---- GPU: aging-only plan (gate A) ----
    static void runAgeGpu(IntArray fil, AgingStore ag, int N) {
        TaskGraph tg = new TaskGraph("age")
            .transferToDevice(DataTransferMode.FIRST_EXECUTION, fil, ag.nucFrac)
            .transferToDevice(DataTransferMode.EVERY_EXECUTION, ag.agingParams, ag.agingCounts)
            .task("age", AgingSystem::age, fil, ag.nucFrac, ag.agingParams, ag.agingCounts)
            .transferToHost(DataTransferMode.UNDER_DEMAND, ag.nucFrac);
        GridScheduler gs = new GridScheduler();
        WorkerGrid w = new WorkerGrid1D(pad(fil.getSize())); w.setLocalWork(B, 1, 1); gs.addWorkerGrid("age.age", w);
        TornadoExecutionPlan plan = new TornadoExecutionPlan(tg.snapshot());
        for (int t = 0; t < N; t++) {
            TornadoExecutionResult res = plan.withGridScheduler(gs).execute();
            if (t == N - 1) res.transferToHost(ag.nucFrac);
        }
    }

    // ---- GPU: full proxy treadmill cadence plan (gate D) ----
    static TornadoExecutionPlan buildProxyPlan(Scene sc) {
        FilamentStore f = sc.fil; GrowthStore g = sc.grow; DepolyStore d = sc.depoly; AgingStore ag = sc.aging;
        TaskGraph tg = new TaskGraph("proxy")
            .transferToDevice(DataTransferMode.FIRST_EXECUTION,
                    sc.nuc.seedNode, d.depolyParams, d.dragParams, d.returnedMon, d.returnedOffsets, d.returnScanCounts, d.deathFlag,
                    ag.nucFrac, ag.depolyRateParams,
                    g.splitParams, g.dragParams, g.grewFlag, g.grewOffsets, g.grewScanCounts,
                    f.acceptFlag, f.reqCoord, f.reqUVec, f.reqYVec, f.freeCount, f.freeOffsets, f.freeList, f.freeScanCounts,
                    f.rankOffsets, f.rankScanCounts, f.allocCounts, f.birthParams,
                    f.coord, f.uVec, f.yVec, f.segLength, f.monomerCount, f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide,
                    f.bTransGam, f.bRotGam, f.bTransDiff, f.bRotDiff, f.brownTransScale, f.brownRotScale, f.filState)
            .transferToDevice(DataTransferMode.EVERY_EXECUTION, d.depolyCounts, g.growParams, g.growCounts, ag.agingParams, ag.agingCounts)
            .task("age", AgingSystem::age, f.filState, ag.nucFrac, ag.agingParams, ag.agingCounts)
            .task("depoly", DepolySystem::depolyProxy, f.filState, f.monomerCount, f.coord, f.uVec, f.end1NbrSlot, ag.nucFrac,
                    d.returnedMon, d.deathFlag, d.depolyParams, ag.depolyRateParams, d.depolyCounts)
            .task("csrReturn", CrossBridgeSystem::csrScan, d.returnScanCounts, d.returnedMon, d.returnedOffsets)
            .task("applyDeath", DepolySystem::applyDeath, f.filState, f.monomerCount, sc.nuc.seedNode, f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide, f.brownTransScale, f.brownRotScale, d.deathFlag, d.depolyCounts)
            .task("grow", GrowthSystem::grow, sc.nuc.seedNode, f.monomerCount, f.coord, f.uVec, g.grewFlag, g.growParams, g.growCounts)
            .task("csrGrew", CrossBridgeSystem::csrScan, g.grewScanCounts, g.grewFlag, g.grewOffsets)
            .task("growthAtp", AgingSystem::growthAtp, g.grewFlag, f.monomerCount, ag.nucFrac)
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
            .transferToHost(DataTransferMode.UNDER_DEMAND, f.monomerCount, f.filState, f.end1NbrSlot, f.end2NbrSlot, ag.nucFrac, g.grewOffsets, d.returnedOffsets);
        sched = new GridScheduler();
        int C = f.n;
        addW("proxy.age", pad(C)); addW("proxy.depoly", pad(C)); addS("proxy.csrReturn"); addW("proxy.applyDeath", pad(C));
        addW("proxy.grow", pad(C)); addS("proxy.csrGrew"); addW("proxy.growthAtp", pad(C)); addW("proxy.markSplits", pad(C));
        addW("proxy.freeFlags", pad(C)); addS("proxy.csrFree"); addS("proxy.freeScatter"); addS("proxy.csrRank");
        addW("proxy.allocate", pad(C)); addW("proxy.splitWire", pad(C)); addW("proxy.splitInherit", pad(C));
        return new TornadoExecutionPlan(tg.snapshot());
    }

    static void runProxyGpu(Scene sc, int nCad) {
        TornadoExecutionPlan plan = buildProxyPlan(sc);
        FilamentStore f = sc.fil; GrowthStore g = sc.grow; DepolyStore d = sc.depoly; AgingStore ag = sc.aging;
        for (int t = 0; t < nCad; t++) {
            g.refreshRate(true); d.refreshRate(true); ag.refresh(true);
            g.setCounts(t, sc.runSeed, true); d.setCounts(t, sc.runSeed, true); ag.setFires(true);
            TornadoExecutionResult res = plan.withGridScheduler(sched).execute();
            res.transferToHost(g.grewOffsets, d.returnedOffsets);
            g.pool.take(g.grewOffsets.get(FILCAP)); g.pool.put(d.returnedOffsets.get(FILCAP));
            if (t == nCad - 1) res.transferToHost(f.monomerCount, f.filState, f.end1NbrSlot, f.end2NbrSlot, ag.nucFrac);
        }
    }

    // ============================================================== utils
    static int pad(int n) { return ((n + B - 1) / B) * B; }
    static void addW(String name, int gg) { WorkerGrid w = new WorkerGrid1D(gg); w.setLocalWork(B, 1, 1); sched.addWorkerGrid(name, w); }
    static void addS(String name) { WorkerGrid w = new WorkerGrid1D(1); w.setLocalWork(1, 1, 1); sched.addWorkerGrid(name, w); }
}
