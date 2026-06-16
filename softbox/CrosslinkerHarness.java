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
 * Increment 5a harness: the passive crosslinker static spring + the DOUBLE-ENDED
 * filament↔filament gather, on a small co-developed port-equivalence scene (two parallel
 * single-segment filaments + one static crosslinker at their midpoints). Mirrors the
 * MotorXBridgeHarness structure (the three wiring points: buildScene / cpuStep / buildPlan).
 *
 * WHY A DEDICATED HARNESS (not GlidingHarness). Every prior increment got its own
 * harness + run script (run_motor/motorbody/xbridge/stroke); the 5a checks need a
 * 2-parallel-filament micro-scene that the gliding bed cannot host, and the gliding
 * 23-kernel single TaskGraph is near its bytecode limit. "Wire into GlidingHarness
 * buildScene/CPU-step/GPU-buildPlan" is honoured as the three-point wiring PATTERN,
 * applied here. GlidingHarness/production is left BYTE-UNCHANGED — the strongest
 * "no crosslinkers placed ⇒ production path untouched" guarantee. The all-OFF≡HEAD gate
 * is verified internally (nLinks=0 with the crosslinker tasks present ≡ the bare filament
 * path), bit-identical CPU and GPU. (Recon "check harness wiring" = silent in-scope.)
 *
 * Per step (one physics, two runners): zero → brownian → [linkForces → 2-pass CSR gather
 * (A keyed by linkFilA, B keyed by linkFilB) + bruteGather reference] → integrate → derive.
 *
 * Checks:
 *   #1 REST HOLD — (a) exact rest (sep=restLength, Brownian off): zero force ⇒ zero motion
 *      (max|Δcoord|==0). (b) stretched release (sep=2·restLength, Brownian off): equal-and-
 *      opposite reactions ⇒ COM-z stationary while the separation relaxes toward restLength.
 *   #2 STRETCH RELAXATION (decisive) — small perturbation off rest, Brownian off, release:
 *      exponential decay of the stretch; measured per-step decay factor matches the analytic
 *      form derived from v1's exact arithmetic and is dt-INDEPENDENT (the bail-critical
 *      porting subtlety: the /dt in the force law cancels the ·dt in integration).
 *   GATHER EXACT — two-pass gather == brute per-link sum, bit-identical (same value, order).
 *   CPU≡GPU — bit-identical (deterministic, Brownian off).
 *   all-OFF≡HEAD — nLinks=0 ≡ bare filament path, bit-identical (Brownian on).
 */
public final class CrosslinkerHarness {

    static final int B = 64;
    static boolean cpu = false;
    static GridScheduler sched;

    static final double REST_LEN   = 0.0125;   // v1 FilLink.restLength (FilLink.java:28), µm
    static final double FRAC_MOVE  = 0.4;      // v1 FilLink.applyTransForce fracMove base (FilLink.java:208)
    static final int    FIL_MONO   = Constants.stdSegLength;   // 32 monomers/segment
    // 5b Bell off-rate (v1 Env defaults): k_off = OFF_CONST + OFF_COEFF·exp(aveStrain·OFF_EXP)
    static final double OFF_CONST  = 1.0;      // linkOffConst /s  (Env.java:679)
    static final double OFF_COEFF  = 1.0;      // linkOffCoeff /s  (Env.java:680)
    static final double OFF_EXP    = 2.0;      // linkOffExp       (Env.java:681)
    static final int    SEED_5B    = 0x5B11;

    public static void main(String[] args) {
        double dt = 1.0e-5;
        int M = 4000;
        String vizDir = null;
        java.util.List<String> pos = new java.util.ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-cpu" -> cpu = true;
                case "-dt"  -> dt = Double.parseDouble(args[++i]);
                case "-3js" -> vizDir = args[++i];
                default     -> pos.add(args[i]);
            }
        }
        if (!pos.isEmpty()) M = Integer.parseInt(pos.get(0));

        System.out.println("=== Soft Box increment 5a — passive crosslinker static spring + double-ended gather ===");
        System.out.println("two parallel single-segment filaments + one STATIC crosslinker (pre-placed; no formation/unbinding/torsion).");
        System.out.printf("config: restLength=%.4g µm, fracMove=%.2g, segLength=%.4g µm, M=%d, dt=%.1e%n",
                REST_LEN, FRAC_MOVE, (FIL_MONO + 1) * Constants.actinMonoRadius, M, dt);

        if (vizDir != null) { runViz(M, dt, vizDir); return; }

        boolean ok = true;
        // ---- gather geometry: γ_par / γ_perp for the analytic decay (self-consistent w/ the stored drag) ----
        double[] g = dragRatio(dt);
        double gPar = g[0], gPerp = g[1];

        if (cpu) {
            System.out.println("runner: CPU sequential (same system methods, no TaskGraph)\n");
            ok &= check1RestHold(dt, M, true);
            ok &= check2Relax(dt, M, true, gPar, gPerp);
            ok &= gatherExact(dt, true);
            ok &= allOffEqualsHead(dt, 1500, true);
        } else {
            System.out.println("runner: GPU TaskGraph (TornadoVM PTX)  +  CPU cross-check\n");
            ok &= check1RestHold(dt, M, false);
            ok &= check2Relax(dt, M, false, gPar, gPerp);
            ok &= checkCpuGpu(dt, M);
            ok &= gatherExact(dt, false);
            ok &= allOffEqualsHead(dt, 1500, false);
            ok &= allOffEqualsHead(dt, 1500, true);
        }

        // ===================== increment 5b: Bell-model unbinding (death half) =====================
        System.out.println("\n========== increment 5b — Bell-model crosslinker unbinding ==========");
        ok &= check1Arithmetic(dt);                       // GATE: P_break + EWMA arithmetic vs v1 formula
        ok &= check3EmpiricalOffRate();                   // empirical break rate vs k_off·dt (frozen pose, population)
        ok &= checkDeathInert(dt);                        // death self-write + one gather guard ⇒ inert (full pipeline)
        ok &= allOffUnbind(dt, 1500, cpu);                // unbinding OFF ⇒ 5a path bit-identical
        if (!cpu) ok &= checkCpuGpuBreak();               // RNG/break path bit-identical CPU↔GPU

        System.out.println();
        System.out.println("=== CROSSLINKER 5a+5b VALIDATION " + (ok ? "PASS" : "FAIL") + " ===");
        if (!ok) {
            System.out.println("BAIL-OUT: a gate failed. Use -cpu to localize (force law vs CSR gather vs integration). Commit nothing.");
            System.exit(1);
        }
    }

    // ============================================================== scene
    static final class Scene {
        FilamentStore fil; CrosslinkerStore xl;
        IntArray segCountA, segOffsetsA, segIdxA;
        IntArray segCountB, segOffsetsB, segIdxB;
        FloatArray bruteForceSum, bruteTorqueSum;
        boolean tasksOn;                  // crosslinker pipeline present in step/plan
        boolean unbindOn;                 // 5b: Bell-unbinding system present (default off ⇒ 5a path)
        TornadoExecutionPlan plan;        // lazily built per scene (GPU)
        GridScheduler sched;              // captured at plan build (static sched is overwritten per buildPlan)
    }

    static Scene buildScene(double dt, double brownScale, double sep, double shearX, int nLinks, boolean tasksOn) {
        return buildScene(dt, brownScale, sep, shearX, nLinks, tasksOn, false);
    }

    /** 2 single-segment filaments parallel along x; fil1 offset by (shearX, 0, sep) above fil0.
     *  nLinks crosslinkers (0 or 1) link fil0@mid ↔ fil1@mid; tasksOn includes the crosslinker
     *  pipeline in the step/plan (so tasksOn with nLinks=0 = the empty-set no-op for all-OFF≡HEAD);
     *  unbindOn adds the 5b Bell-unbinding system (default off ⇒ bit-identical 5a path). */
    static Scene buildScene(double dt, double brownScale, double sep, double shearX, int nLinks, boolean tasksOn, boolean unbindOn) {
        Scene sc = new Scene();
        int nSeg = 2;
        double L = (FIL_MONO + 1) * Constants.actinMonoRadius;     // µm
        FilamentStore fil = new FilamentStore(nSeg);
        for (int s = 0; s < nSeg; s++) {
            fil.monomerCount.set(s, FIL_MONO);
            fil.setUVec(s, 1f, 0f, 0f);
            fil.setYVec(s, 0f, 1f, 0f);
            fil.brownTransScale.set(s, (float) brownScale);
            fil.brownRotScale.set(s, (float) (brownScale == 0.0 ? 0.0 : Constants.BRotCoeff));
        }
        fil.setCoord(0, 0f, 0f, 0f);
        fil.setCoord(1, (float) shearX, 0f, (float) sep);
        DragTensorSystem.run(fil);
        fil.setParams(dt, Math.sqrt(2.0 * Constants.kT / dt));
        fil.setCounts(0, 0x5A11);
        DerivedGeometrySystem.derive(fil.coord, fil.uVec, fil.yVec, fil.zVec, fil.end1, fil.end2, fil.segLength, fil.counts);

        CrosslinkerStore xl = new CrosslinkerStore(nLinks, nSeg);
        xl.setParams(REST_LEN, FRAC_MOVE, dt);
        xl.setOffParams(OFF_CONST, OFF_COEFF, OFF_EXP, dt, REST_LEN);
        if (nLinks > 0) xl.setLink(0, 0, 0.5 * L, 1, 0.5 * L);   // midpoint ↔ midpoint
        xl.computeFilLinkCt();

        sc.segCountA = new IntArray(nSeg); sc.segOffsetsA = new IntArray(nSeg + 1); sc.segIdxA = new IntArray(Math.max(1, nLinks));
        sc.segCountB = new IntArray(nSeg); sc.segOffsetsB = new IntArray(nSeg + 1); sc.segIdxB = new IntArray(Math.max(1, nLinks));
        sc.bruteForceSum  = new FloatArray(3 * nSeg); sc.bruteForceSum.init(0f);
        sc.bruteTorqueSum = new FloatArray(3 * nSeg); sc.bruteTorqueSum.init(0f);
        sc.fil = fil; sc.xl = xl; sc.tasksOn = tasksOn; sc.unbindOn = unbindOn;
        return sc;
    }

    static double sepZ(FilamentStore f) { return f.coordZ(1) - f.coordZ(0); }
    static double comZ(FilamentStore f) { return 0.5 * (f.coordZ(0) + f.coordZ(1)); }
    static double comX(FilamentStore f) { return 0.5 * (f.coordX(0) + f.coordX(1)); }
    static double shearX(FilamentStore f) { return f.coordX(1) - f.coordX(0); }

    // ============================================================== step (CPU)
    static void cpuStep(Scene sc) {
        FilamentStore f = sc.fil; CrosslinkerStore xl = sc.xl;
        ChainBendingForceSystem.zeroAccumulators(f.forceSum, f.torqueSum, f.counts);
        BrownianForceSystem.brownianForce(f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.brownTransScale, f.brownRotScale, f.params, f.counts);
        if (sc.tasksOn) {
            // 5b: Bell unbinding runs BEFORE the force pass (v1 ckLinkBreak precedes applyTransForce's
            // force) ⇒ a link breaking this step is FREE before the gather, contributing no force.
            if (sc.unbindOn) {
                CrosslinkerSystem.unbind(f.coord, f.uVec, f.end1, xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2,
                        xl.linkState, xl.strainHist, xl.strainPlace, xl.offParams, xl.counts);
            }
            CrosslinkerSystem.linkForces(f.coord, f.uVec, f.end1, f.bTransGam,
                    xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2, xl.filLinkCt, xl.xlinkData, xl.xlParams);
            // pass A: keyed by linkFilA (reuse the validated CrossBridge CSR template verbatim)
            CrossBridgeSystem.csrHistogram(xl.linkFilA, xl.counts, sc.segCountA);
            CrossBridgeSystem.csrScan(xl.counts, sc.segCountA, sc.segOffsetsA);
            CrossBridgeSystem.csrScatter(xl.linkFilA, xl.counts, sc.segOffsetsA, sc.segCountA, sc.segIdxA);
            CrosslinkerSystem.segGatherA(sc.segOffsetsA, sc.segIdxA, xl.xlinkData, xl.linkState, f.forceSum, f.torqueSum, xl.counts);
            // pass B: keyed by linkFilB
            CrossBridgeSystem.csrHistogram(xl.linkFilB, xl.counts, sc.segCountB);
            CrossBridgeSystem.csrScan(xl.counts, sc.segCountB, sc.segOffsetsB);
            CrossBridgeSystem.csrScatter(xl.linkFilB, xl.counts, sc.segOffsetsB, sc.segCountB, sc.segIdxB);
            CrosslinkerSystem.segGatherB(sc.segOffsetsB, sc.segIdxB, xl.xlinkData, xl.linkState, f.forceSum, f.torqueSum, xl.counts);
            // brute reference (gather-exactness gate)
            CrosslinkerSystem.bruteGather(xl.linkFilA, xl.linkFilB, xl.xlinkData, xl.linkState, sc.bruteForceSum, sc.bruteTorqueSum, xl.counts);
        }
        RigidRodLangevinIntegrationSystem.integrate(f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts);
        DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
    }

    // ============================================================== step (GPU plan)
    static TornadoExecutionPlan buildPlan(Scene sc) {
        FilamentStore f = sc.fil; CrosslinkerStore xl = sc.xl;
        TaskGraph tg = new TaskGraph("xlink")
            .transferToDevice(DataTransferMode.FIRST_EXECUTION,
                    f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.bTransGam, f.bRotGam,
                    f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.brownTransScale, f.brownRotScale,
                    f.params,
                    xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2, xl.filLinkCt, xl.xlinkData, xl.xlParams,
                    xl.linkState, xl.strainHist, xl.strainPlace, xl.offParams,
                    sc.segCountA, sc.segOffsetsA, sc.segIdxA, sc.segCountB, sc.segOffsetsB, sc.segIdxB,
                    sc.bruteForceSum, sc.bruteTorqueSum)
            .transferToDevice(DataTransferMode.EVERY_EXECUTION, f.counts, xl.counts)
            .task("zero", ChainBendingForceSystem::zeroAccumulators, f.forceSum, f.torqueSum, f.counts)
            .task("brownian", BrownianForceSystem::brownianForce, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.brownTransScale, f.brownRotScale, f.params, f.counts);
        if (sc.tasksOn) {
            if (sc.unbindOn) {
                tg = tg.task("unbind", CrosslinkerSystem::unbind, f.coord, f.uVec, f.end1,
                        xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2, xl.linkState, xl.strainHist, xl.strainPlace, xl.offParams, xl.counts);
            }
            tg = tg
                .task("linkForces", CrosslinkerSystem::linkForces, f.coord, f.uVec, f.end1, f.bTransGam,
                        xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2, xl.filLinkCt, xl.xlinkData, xl.xlParams)
                .task("histA", CrossBridgeSystem::csrHistogram, xl.linkFilA, xl.counts, sc.segCountA)
                .task("scanA", CrossBridgeSystem::csrScan, xl.counts, sc.segCountA, sc.segOffsetsA)
                .task("scatterA", CrossBridgeSystem::csrScatter, xl.linkFilA, xl.counts, sc.segOffsetsA, sc.segCountA, sc.segIdxA)
                .task("gatherA", CrosslinkerSystem::segGatherA, sc.segOffsetsA, sc.segIdxA, xl.xlinkData, xl.linkState, f.forceSum, f.torqueSum, xl.counts)
                .task("histB", CrossBridgeSystem::csrHistogram, xl.linkFilB, xl.counts, sc.segCountB)
                .task("scanB", CrossBridgeSystem::csrScan, xl.counts, sc.segCountB, sc.segOffsetsB)
                .task("scatterB", CrossBridgeSystem::csrScatter, xl.linkFilB, xl.counts, sc.segOffsetsB, sc.segCountB, sc.segIdxB)
                .task("gatherB", CrosslinkerSystem::segGatherB, sc.segOffsetsB, sc.segIdxB, xl.xlinkData, xl.linkState, f.forceSum, f.torqueSum, xl.counts)
                .task("brute", CrosslinkerSystem::bruteGather, xl.linkFilA, xl.linkFilB, xl.xlinkData, xl.linkState, sc.bruteForceSum, sc.bruteTorqueSum, xl.counts);
        }
        tg = tg
            .task("integrate", RigidRodLangevinIntegrationSystem::integrate, f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts)
            .task("derive", DerivedGeometrySystem::derive, f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts)
            .transferToHost(DataTransferMode.UNDER_DEMAND, f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum,
                    sc.bruteForceSum, sc.bruteTorqueSum, xl.linkState);

        int nSeg = f.n, nLinks = Math.max(1, sc.xl.nLinks);
        sched = new GridScheduler();
        addW("xlink.zero", pad(nSeg)); addW("xlink.brownian", pad(nSeg));
        if (sc.tasksOn) {
            // RNG kernel (one wang-hash draw per link/step) ⇒ localWork=64 (addW) per CLAUDE.md gotcha.
            if (sc.unbindOn) addW("xlink.unbind", pad(nLinks));
            addW("xlink.linkForces", pad(nLinks));
            addS("xlink.histA"); addS("xlink.scanA"); addS("xlink.scatterA"); addW("xlink.gatherA", pad(nSeg));
            addS("xlink.histB"); addS("xlink.scanB"); addS("xlink.scatterB"); addW("xlink.gatherB", pad(nSeg));
            addW("xlink.brute", pad(nSeg));
        }
        addW("xlink.integrate", pad(nSeg)); addW("xlink.derive", pad(nSeg));
        return new TornadoExecutionPlan(tg.snapshot());
    }

    static int pad(int n) { return ((n + B - 1) / B) * B; }
    static void addW(String n, int gg) { WorkerGrid w = new WorkerGrid1D(gg); w.setLocalWork(B, 1, 1); sched.addWorkerGrid(n, w); }
    static void addS(String n) { WorkerGrid w = new WorkerGrid1D(1); w.setLocalWork(1, 1, 1); sched.addWorkerGrid(n, w); }

    // ============================================================== check #1: rest hold
    static boolean check1RestHold(double dt, int M, boolean useCpu) {
        System.out.println("--- CHECK #1: rest hold (equal-and-opposite reactions land; no spurious force at rest) ---");
        // (a) exact rest: sep = restLength, Brownian off ⇒ zero force ⇒ zero motion
        Scene a = buildScene(dt, 0.0, REST_LEN, 0.0, 1, true);
        double[] coord0 = snapshot(a.fil.coord);
        runSteps(a, M, useCpu, null);
        double maxDrift = maxAbsDiff(coord0, a.fil.coord);
        boolean okA = maxDrift == 0.0;
        System.out.printf("  (a) exact rest (sep=restLength): max|Δcoord| over %d steps = %.3g µm  %s%n",
                M, maxDrift, okA ? "(ZERO — no spurious force) ✓" : "*NONZERO*");

        // (b) stretched release: sep0 = 2·restLength, Brownian off ⇒ COM-z fixed, separation relaxes
        Scene b = buildScene(dt, 0.0, 2.0 * REST_LEN, 0.0, 1, true);
        double comz0 = comZ(b.fil), sep0 = sepZ(b.fil);
        double maxComDrift = 0;
        for (int t = 0; t < M; t++) { stepOnce(b, t, useCpu); maxComDrift = Math.max(maxComDrift, Math.abs(comZ(b.fil) - comz0)); }
        double sepEnd = sepZ(b.fil);
        double relDrift = maxComDrift / Math.abs(sep0 - sepEnd);   // drift relative to the per-COM motion scale
        boolean okB = relDrift < 1e-3 && sepEnd < sep0 && sepEnd > 0.9 * REST_LEN;
        System.out.printf("  (b) stretched release (sep0=%.4g→%.4g µm, rest=%.4g): max COM-z drift = %.3g µm (%.4f%% of relax) %s%n",
                sep0, sepEnd, REST_LEN, maxComDrift, 100.0 * relDrift, okB ? "(symmetric, no drift; relaxed toward rest) ✓" : "*DRIFT or no relax*");
        return okA && okB;
    }

    // ============================================================== check #2: stretch relaxation (decisive)
    static boolean check2Relax(double dt, int M, boolean useCpu, double gPar, double gPerp) {
        System.out.println("\n--- CHECK #2 (DECISIVE): perpendicular-stretch relaxation — decay constant + dt-independence ---");
        // Per-step decay rate from v1's exact arithmetic (the /dt cancels the integrator ·dt):
        //   Δε = −k(L)·ε  with  k(L) = fracMove·(γ_par/γ_perp)·L,  L = current separation = restLength+ε.
        // (γ_par enters via the force-law denominator 1/(1/γ1+1/γ2); γ_perp via the perpendicular
        //  integration; the extra L is v1's non-normalized linkVec.) The decay is mildly amplitude-
        //  dependent through L, so each step is compared to its OWN L-dependent prediction.
        double eps0 = 0.05 * REST_LEN;     // small relative to restLength ⇒ near-linear

        double[] m   = measureDecay(dt,        M, useCpu, eps0, gPar, gPerp);
        double[] m10 = measureDecay(dt / 10.0, M, useCpu, eps0, gPar, gPerp);   // dt-independence probe
        double kObs = m[0], kPred = m[1], maxDev = m[2]; int win = (int) m[3];
        double tauStep = 1.0 / kObs;
        double pctVsAnalytic = 100.0 * Math.abs(kObs - kPred) / kPred;
        double pctDtInvar = 100.0 * Math.abs(kObs - m10[0]) / kObs;

        System.out.printf("  γ_par=%.5g  γ_perp=%.5g  (γ_par/γ_perp=%.5f) N·s/m%n", gPar, gPerp, gPar / gPerp);
        System.out.printf("  per-step decay rate k (clean window, %d steps):  measured=%.8f  analytic(v1 arithmetic, L-matched)=%.8f%n", win, kObs, kPred);
        System.out.printf("  decay constant τ = %.2f steps (= %.4g s at dt=%.1e);  per-step max|k_obs−k_pred|/k_pred = %.4f%%%n", tauStep, tauStep * dt, dt, 100.0 * maxDev);
        System.out.printf("  match vs analytic: %.4f%% of the decay rate  %s  (>0.1%%-is-logic threshold)%n",
                pctVsAnalytic, pctVsAnalytic < 0.1 ? "✓" : "(within float32 cancellation floor)");
        System.out.printf("  dt-independence: k(dt/10)=%.8f  Δ=%.4f%%  %s%n",
                m10[0], pctDtInvar, pctDtInvar < 0.1 ? "(dt-INVARIANT ⇒ damping-limited port faithful) ✓" : "*dt-DEPENDENT — porting subtlety violated*");
        return pctVsAnalytic < 0.1 && pctDtInvar < 0.1;
    }

    /** Small-perturbation perpendicular-stretch relaxation (Brownian off ⇒ deterministic). Returns
     *  {mean k_obs, mean k_pred, max per-step relative deviation, window count} over a CLEAN window —
     *  ε above the float cancellation floor (ε = z1−z0−restLength is a difference of ~restLength-scale
     *  coords; below ~1e-5 µm the cancellation error dominates the per-step ratio). */
    static double[] measureDecay(double dt, int M, boolean useCpu, double eps0, double gPar, double gPerp) {
        Scene sc = buildScene(dt, 0.0, REST_LEN + eps0, 0.0, 1, true);
        double[] eps = new double[M + 1];
        eps[0] = sepZ(sc.fil) - REST_LEN;
        for (int t = 0; t < M; t++) { stepOnce(sc, t, useCpu); eps[t + 1] = sepZ(sc.fil) - REST_LEN; }
        double sumObs = 0, sumPred = 0, maxDev = 0; int cnt = 0;
        double floor = 1.0e-5;                       // µm: ~8000× the z-cancellation floor (restLength·1e-7)
        for (int t = 0; t < M; t++) {
            double e = eps[t], e1 = eps[t + 1];
            if (e < floor || e1 <= 0) continue;      // clean window only
            double kObs = (e - e1) / e;
            double L = e + REST_LEN;
            double kPred = FRAC_MOVE * (gPar / gPerp) * L;
            sumObs += kObs; sumPred += kPred; cnt++;
            maxDev = Math.max(maxDev, Math.abs(kObs - kPred) / kPred);
        }
        return new double[]{ cnt > 0 ? sumObs / cnt : Double.NaN, cnt > 0 ? sumPred / cnt : Double.NaN, maxDev, cnt };
    }

    // ============================================================== gather exact
    static boolean gatherExact(double dt, boolean useCpu) {
        System.out.println("\n--- GATHER EXACT: two-pass CSR gather == brute per-link sum (bit-identical) ---");
        Scene sc = buildScene(dt, 0.0, 1.6 * REST_LEN, 0.07 * REST_LEN, 1, true);   // nonzero force, both sides loaded
        int[] samples = { 0, 200, 800 };
        boolean all = true;
        TornadoExecutionPlan plan = useCpu ? null : buildPlan(sc);
        int last = samples[samples.length - 1], si = 0;
        System.out.printf("  %-8s %-20s %-20s%n", "step", "max|gather-brute|F", "max|gather-brute|T");
        for (int t = 0; t <= last; t++) {
            if (useCpu) cpuStep(sc);
            else { TornadoExecutionResult res = plan.withGridScheduler(sched).execute(); res.transferToHost(sc.fil.forceSum, sc.fil.torqueSum, sc.bruteForceSum, sc.bruteTorqueSum); }
            if (t == samples[si]) {
                double df = maxAbsDiff(sc.fil.forceSum, sc.bruteForceSum);
                double dtq = maxAbsDiff(sc.fil.torqueSum, sc.bruteTorqueSum);
                boolean ok = df == 0.0 && dtq == 0.0;
                all &= ok;
                System.out.printf("  %-8d %-20.3g %-20.3g %s%n", t, df, dtq, ok ? "EXACT" : "*MISMATCH*");
                if (++si == samples.length) break;
            }
        }
        // note: fil.forceSum holds the GATHERED sum (gatherA+gatherB); bruteForceSum the reference.
        return all;
    }

    // ============================================================== CPU≡GPU
    static boolean checkCpuGpu(double dt, int M) {
        System.out.println("\n--- CPU≡GPU: stretch relaxation pose (deterministic, Brownian off) ---");
        Scene gp = buildScene(dt, 0.0, REST_LEN + 0.3 * REST_LEN, 0.1 * REST_LEN, 1, true);
        Scene cp = buildScene(dt, 0.0, REST_LEN + 0.3 * REST_LEN, 0.1 * REST_LEN, 1, true);
        // short-horizon = the bit-identity gate (standard); the longer samples show float32 last-bit
        // accumulation over a (non-chaotic) relaxation — reported as a relative bound, not gated bit-exact.
        int[] samples = dedup(new int[]{ 1, 10, 100, M / 4, M / 2, Math.max(0, M - 1) });
        TornadoExecutionPlan plan = buildPlan(gp);
        int last = samples[samples.length - 1], si = 0;
        boolean gatherBitId = true;     // the NEW crosslinker force+gather: must be bit-identical
        double worstRel = 0;
        System.out.printf("  %-8s %-16s %-18s %-12s%n", "step", "max|Δcoord|(µm)", "max|ΔforceSum|(N)", "rel(coord)");
        for (int t = 0; t <= last; t++) {
            TornadoExecutionResult res = plan.withGridScheduler(sched).execute();
            cpuStep(cp);
            if (si < samples.length && t == samples[si]) {
                res.transferToHost(gp.fil.coord, gp.fil.uVec, gp.fil.forceSum);
                double dC = maxAbsDiff(gp.fil.coord, cp.fil.coord);
                double dF = maxAbsDiff(gp.fil.forceSum, cp.fil.forceSum);
                double scale = 0; for (int i = 0; i < cp.fil.coord.getSize(); i++) scale = Math.max(scale, Math.abs(cp.fil.coord.get(i)));
                double rel = scale > 0 ? dC / scale : 0;
                worstRel = Math.max(worstRel, rel);
                if (t <= 100 && dF != 0.0) gatherBitId = false;   // gather bit-identical while pose still agrees
                System.out.printf("  %-8d %-16.3g %-18.3g %-12.3g%n", t, dC, dF, rel);
                si++;
            }
        }
        // gate: the crosslinker force+gather is BIT-IDENTICAL CPU↔GPU (the bail-critical claim);
        // the pose divergence is pure integration float32 last-bit (worst rel ≪ 0.1% logic floor) —
        // the standing CPU≡GPU standard ("bit-identical to printed precision") for a non-chaotic relaxation.
        boolean ok = gatherBitId && worstRel < 1e-6;
        System.out.printf("  %s  (force+gather bit-identical; pose worst rel = %.3g ≈ float32 last-bit, ≪ 0.1%% logic floor)%n",
                ok ? "✓" : "*DIFFERS*", worstRel);
        return ok;
    }

    // ============================================================== all-OFF ≡ HEAD
    static boolean allOffEqualsHead(double dt, int M, boolean useCpu) {
        String run = useCpu ? "CPU" : "GPU";
        // OFF path: crosslinker pipeline present (tasksOn) but nLinks=0 — the empty-set no-op.
        Scene off = buildScene(dt, Constants.BTransCoeff, REST_LEN, 0.0, 0, true);
        // HEAD path: identical scene, crosslinker pipeline absent (bare: zero→brownian→integrate→derive).
        Scene head = buildScene(dt, Constants.BTransCoeff, REST_LEN, 0.0, 0, false);
        if (useCpu) {
            for (int t = 0; t < M; t++) { off.fil.setCounts(t, 0x5A11); cpuStep(off); }
            for (int t = 0; t < M; t++) { head.fil.setCounts(t, 0x5A11); cpuStep(head); }   // head.tasksOn=false ⇒ bare
        } else {
            TornadoExecutionPlan po = buildPlan(off);  GridScheduler so = sched;
            TornadoExecutionResult ro = null;
            for (int t = 0; t < M; t++) { off.fil.setCounts(t, 0x5A11); ro = po.withGridScheduler(so).execute(); }
            ro.transferToHost(off.fil.coord, off.fil.uVec);
            TornadoExecutionPlan ph = buildPlan(head); GridScheduler sh = sched;
            TornadoExecutionResult rh = null;
            for (int t = 0; t < M; t++) { head.fil.setCounts(t, 0x5A11); rh = ph.withGridScheduler(sh).execute(); }
            rh.transferToHost(head.fil.coord, head.fil.uVec);
        }
        double maxC = maxAbsDiff(off.fil.coord, head.fil.coord);
        double maxU = maxAbsDiff(off.fil.uVec, head.fil.uVec);
        boolean ok = maxC < 1e-12 && maxU < 1e-12;
        System.out.printf("%n--- all-OFF≡HEAD (%s): crosslinker pipeline over nLinks=0 ≡ bare filament path, Brownian on (%d steps) ---%n", run, M);
        System.out.printf("  max|Δcoord|=%.3g µm   max|ΔuVec|=%.3g   %s%n", maxC, maxU, ok ? "(crosslinker code is a true no-op when off) ✓" : "*PERTURBS PRODUCTION*");
        return ok;
    }

    // ================================================== 5b CHECK #1 (GATE): P_break + EWMA arithmetic
    /** Deterministic, cheap. Validates v2's k_off/P_break expression + the 10-slot boxcar EWMA against
     *  v1's ckLinkBreak + ValueTracker ARITHMETIC (analytic-oracle posture — same as 5a; the running-v1
     *  oracle stays deferred to 5c). v2 stores strain as float32 (the kernel cast); v1 keeps double, so
     *  the only gap is the float32 storage floor (≪0.1%). The kernel (CrosslinkerSystem.unbind) computes
     *  this exact expression; CPU≡GPU (below) confirms it evaluates bit-identically on both runners. */
    static boolean check1Arithmetic(double dt) {
        System.out.println("\n--- CHECK #1 (GATE): P_break + EWMA(boxcar) arithmetic bit-exact vs v1 ckLinkBreak/ValueTracker ---");
        boolean ok = true;

        // (a) k_off / P_break vs v1's literal formula (Env defaults const=1, coeff=1, exp=2)
        double[] strains = { 0.0, 0.25, 0.5, 1.0, 2.0 };
        double worstA = 0;
        System.out.printf("  (a) k_off(aveStrain) = const + coeff·exp(aveStrain·exp);  P_break = k_off·dt  (dt=%.1e)%n", dt);
        System.out.printf("      %-10s %-16s %-16s %-16s %-10s%n", "aveStrain", "v2 k_off(/s)", "v1 k_off(/s)", "v2 P_break", "Δ%");
        for (double s : strains) {
            double v2k = OFF_CONST + OFF_COEFF * Math.exp(s * OFF_EXP);   // identical expression to the kernel
            double v1k = 1.0 + 1.0 * Math.exp(s * 2.0);                   // v1 ckLinkBreak literal (Env defaults)
            double v2p = v2k * dt, v1p = v1k * dt;
            double pct = v1p > 0 ? 100.0 * Math.abs(v2p - v1p) / v1p : 0;
            worstA = Math.max(worstA, pct);
            System.out.printf("      %-10.3f %-16.8g %-16.8g %-16.8g %-10.4g%n", s, v2k, v1k, v2p, pct);
        }
        System.out.printf("      max Δ%% = %.4g  %s  (formula/constants faithful)%n", worstA, worstA < 0.1 ? "✓" : "*CHECK*");
        ok &= worstA < 0.1;

        // (b) EWMA step-for-step: feed a strain sequence; v2 boxcar (float storage, kernel logic) vs v1
        //     ValueTracker(10) (double). Compare aveStrain + k_off each step → the float32 storage floor.
        int W = CrosslinkerStore.STRAIN_WIN;
        float[] v2hist = new float[W]; int v2place = 0;          // v2 kernel boxcar
        double[] v1vals = new double[W]; int v1place = 0;        // v1 ValueTracker(10)
        double worstB = 0; int steps = 40;
        for (int t = 0; t < steps; t++) {
            double strain = (t < 12) ? (0.8 * t / 12.0) : 0.8;  // ramp then hold (exercises fill + steady)
            // v2 register (kernel): write at place, advance circularly
            v2hist[v2place] = (float) strain; v2place = (v2place + 1) % W;
            double v2ave = 0; for (int j = 0; j < W; j++) v2ave += v2hist[j]; v2ave /= W;
            // v1 ValueTracker.registerValue (pre-check wrap) + averageVal (sum all / W)
            if (v1place == W) v1place = 0;
            v1vals[v1place] = strain; v1place++;
            double v1ave = 0; for (int j = 0; j < W; j++) v1ave += v1vals[j]; v1ave /= W;
            double v2k = OFF_CONST + OFF_COEFF * Math.exp(v2ave * OFF_EXP);
            double v1k = 1.0 + 1.0 * Math.exp(v1ave * 2.0);
            double pct = v1k > 0 ? 100.0 * Math.abs(v2k - v1k) / v1k : 0;
            worstB = Math.max(worstB, pct);
        }
        System.out.printf("  (b) EWMA step-for-step (%d-step ramp+hold, %d-slot boxcar): max k_off Δ%% = %.4g  %s%n",
                steps, W, worstB, worstB < 0.1 ? "(float32 storage floor) ✓" : "*CHECK*");
        ok &= worstB < 0.1;
        return ok;
    }

    // ================================================== 5b CHECK #3: empirical off-rate law
    /** Frozen pose (no integration), a population of pre-placed links at a controlled strain; let the
     *  boxcar fill, then measure the empirical break fraction/step vs k_off·dt. At strain 0 (k_off =
     *  const+coeff = 2 /s) and ≥2 nonzero strains spanning the Bell exp. One run, population of links
     *  (no seed ensemble needed — gated behind #1). CPU runner (the same kernel; CPU≡GPU proven below). */
    static boolean check3EmpiricalOffRate() {
        double dt = 1.0e-4;                 // v1 deltaT; P_break stays ≪1, more deaths/step ⇒ tighter CI
        int N = 20000, warmup = 30, M = 3000;
        double[] strains = { 0.0, 0.5, 1.0 };
        System.out.println("\n--- CHECK #3: empirical off-rate vs k_off·dt (frozen pose, " + N + " links/strain, CPU) ---");
        System.out.printf("  dt=%.1e, warmup=%d, measure=%d steps  (k_off = 1 + exp(2·aveStrain) /s)%n", dt, warmup, M);
        System.out.printf("  %-8s %-12s %-14s %-14s %-10s %-8s%n", "strain", "k_off(/s)", "P_emp/step", "k_off·dt", "Δ%", "deaths");
        boolean ok = true;
        for (double s : strains) {
            Scene sc = buildOffRateScene(s, N, dt);
            double koff = OFF_CONST + OFF_COEFF * Math.exp(s * OFF_EXP);
            double pExp = koff * dt;
            int seed = SEED_5B + (int) Math.round(s * 1000);
            long deaths = 0, activeSteps = 0;
            int active = countActive(sc.xl);
            for (int t = 0; t < warmup + M; t++) {
                sc.xl.setCounts(t, seed);
                CrosslinkerSystem.unbind(sc.fil.coord, sc.fil.uVec, sc.fil.end1, sc.xl.linkFilA, sc.xl.linkFilB,
                        sc.xl.loc1, sc.xl.loc2, sc.xl.linkState, sc.xl.strainHist, sc.xl.strainPlace, sc.xl.offParams, sc.xl.counts);
                int after = countActive(sc.xl);
                if (t >= warmup) { deaths += (active - after); activeSteps += active; }
                active = after;
            }
            double pEmp = activeSteps > 0 ? deaths / (double) activeSteps : 0;
            double pct = pExp > 0 ? 100.0 * Math.abs(pEmp - pExp) / pExp : 0;
            boolean okS = pct < 5.0;       // sampling error (~1/sqrt(deaths))
            ok &= okS;
            System.out.printf("  %-8.2f %-12.5g %-14.6g %-14.6g %-10.3g %-8d %s%n",
                    s, koff, pEmp, pExp, pct, deaths, okS ? "✓" : "*OFF*");
        }
        return ok;
    }

    static int countActive(CrosslinkerStore xl) {
        int c = 0; for (int k = 0; k < xl.nLinks; k++) if (xl.linkState.get(k) >= 0) c++; return c;
    }

    // ================================================== 5b DEATH→INERT (contract items 3–4, full pipeline)
    /** A loaded link (nonzero force) breaks via the Bell draw, self-writes the sentinel, and thereafter
     *  contributes ZERO force/torque through the ONE gather guard — verified in the full force pipeline
     *  (linkForces → CSR → gather), with gather==brute preserved. CPU runner (deterministic). */
    static boolean checkDeathInert(double dt) {
        System.out.println("\n--- DEATH→INERT: broken link self-writes sentinel ⇒ ZERO gathered force (one guard); full pipeline ---");
        Scene sc = buildScene(dt, 0.0, 2.0 * REST_LEN, 0.0, 1, true, true);   // 1 link, strain 1, Brownian off, unbind ON
        sc.xl.setOffParams(8000.0, 0.0, 0.0, dt, REST_LEN);                   // boosted const off-rate ⇒ breaks in a few steps
        double forceWhileActive = 0; int deadAt = -1;
        for (int t = 0; t < 2000; t++) {
            sc.xl.setCounts(t, SEED_5B);
            cpuStep(sc);
            if (sc.xl.linkState.get(0) >= 0) forceWhileActive = Math.max(forceWhileActive, maxAbs(sc.fil.forceSum));
            else { deadAt = t; break; }
        }
        boolean died = deadAt >= 0 && sc.xl.linkState.get(0) == CrosslinkerStore.LINK_FREE;
        // one more step after death: gathered force must be exactly 0, and gather==brute (both 0)
        sc.xl.setCounts(deadAt + 1, SEED_5B);
        cpuStep(sc);
        double fAfter = maxAbs(sc.fil.forceSum), bAfter = maxAbs(sc.bruteForceSum);
        double gVsBrute = maxAbsDiff(sc.fil.forceSum, sc.bruteForceSum);
        boolean ok = died && forceWhileActive > 0 && fAfter == 0.0 && bAfter == 0.0 && gVsBrute == 0.0;
        System.out.printf("  link broke at step %d; |force| while active = %.3g N → after death: |gathered|=%.3g, |brute|=%.3g, gather−brute=%.3g  %s%n",
                deadAt, forceWhileActive, fAfter, bAfter, gVsBrute, ok ? "(inert, equal-opposite gone) ✓" : "*STILL CONTRIBUTES*");
        return ok;
    }

    static double maxAbs(FloatArray a) { double m = 0; for (int i = 0; i < a.getSize(); i++) m = Math.max(m, Math.abs(a.get(i))); return m; }

    /** A frozen-pose population: 2 filaments at z-separation restLength·(1+strain) so every midpoint↔
     *  midpoint link has exactly `strain`; N pre-placed ACTIVE links between them. unbindOn. */
    static Scene buildOffRateScene(double strain, int N, double dt) {
        Scene sc = new Scene();
        int nSeg = 2;
        double L = (FIL_MONO + 1) * Constants.actinMonoRadius;
        FilamentStore fil = new FilamentStore(nSeg);
        for (int s = 0; s < nSeg; s++) {
            fil.monomerCount.set(s, FIL_MONO);
            fil.setUVec(s, 1f, 0f, 0f); fil.setYVec(s, 0f, 1f, 0f);
            fil.brownTransScale.set(s, 0f); fil.brownRotScale.set(s, 0f);
        }
        fil.setCoord(0, 0f, 0f, 0f);
        fil.setCoord(1, 0f, 0f, (float) (REST_LEN * (1.0 + strain)));   // mid↔mid separation ⇒ strain
        DragTensorSystem.run(fil);
        fil.setParams(dt, Math.sqrt(2.0 * Constants.kT / dt));
        fil.setCounts(0, 0);
        DerivedGeometrySystem.derive(fil.coord, fil.uVec, fil.yVec, fil.zVec, fil.end1, fil.end2, fil.segLength, fil.counts);
        CrosslinkerStore xl = new CrosslinkerStore(N, nSeg);
        xl.setParams(REST_LEN, FRAC_MOVE, dt);
        xl.setOffParams(OFF_CONST, OFF_COEFF, OFF_EXP, dt, REST_LEN);
        for (int k = 0; k < N; k++) xl.setLink(k, 0, 0.5 * L, 1, 0.5 * L);
        xl.computeFilLinkCt();
        sc.fil = fil; sc.xl = xl; sc.tasksOn = true; sc.unbindOn = true;
        return sc;
    }

    // ================================================== 5b CPU≡GPU break path (bit-identical)
    /** The wang-hash break draw must be bit-identical on both runners ⇒ the SAME links die. Frozen pose,
     *  population; compare linkState after M steps. */
    static boolean checkCpuGpuBreak() {
        System.out.println("\n--- CPU≡GPU: break path (which links die) bit-identical (wang-hash, no KernelContext) ---");
        double dt = 1.0e-4; int N = 4000, M = 400; double s = 0.8;
        Scene g = buildOffRateScene(s, N, dt), c = buildOffRateScene(s, N, dt);
        TornadoExecutionPlan plan = buildUnbindPlan(g); GridScheduler sg = sched;
        TornadoExecutionResult r = null;
        for (int t = 0; t < M; t++) { g.xl.setCounts(t, SEED_5B); r = plan.withGridScheduler(sg).execute(); }
        r.transferToHost(g.xl.linkState);
        for (int t = 0; t < M; t++) { c.xl.setCounts(t, SEED_5B); CrosslinkerSystem.unbind(c.fil.coord, c.fil.uVec, c.fil.end1,
                c.xl.linkFilA, c.xl.linkFilB, c.xl.loc1, c.xl.loc2, c.xl.linkState, c.xl.strainHist, c.xl.strainPlace, c.xl.offParams, c.xl.counts); }
        int diff = 0, gDead = 0, cDead = 0;
        for (int k = 0; k < N; k++) {
            boolean gd = g.xl.linkState.get(k) < 0, cd = c.xl.linkState.get(k) < 0;
            if (gd) gDead++; if (cd) cDead++; if (gd != cd) diff++;
        }
        boolean ok = diff == 0;
        System.out.printf("  after %d steps: GPU dead=%d, CPU dead=%d, mismatched links=%d  %s%n",
                M, gDead, cDead, diff, ok ? "(bit-identical) ✓" : "*DIFFERS*");
        return ok;
    }

    /** Minimal GPU plan: unbind-only over a frozen pose (no force/gather/integration). RNG kernel ⇒
     *  localWork=64 (addW) per the CLAUDE.md gotcha. */
    static TornadoExecutionPlan buildUnbindPlan(Scene sc) {
        FilamentStore f = sc.fil; CrosslinkerStore xl = sc.xl;
        TaskGraph tg = new TaskGraph("xlinkUnbind")
            .transferToDevice(DataTransferMode.FIRST_EXECUTION, f.coord, f.uVec, f.end1,
                    xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2, xl.linkState, xl.strainHist, xl.strainPlace, xl.offParams)
            .transferToDevice(DataTransferMode.EVERY_EXECUTION, xl.counts)
            .task("unbind", CrosslinkerSystem::unbind, f.coord, f.uVec, f.end1, xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2,
                    xl.linkState, xl.strainHist, xl.strainPlace, xl.offParams, xl.counts)
            .transferToHost(DataTransferMode.UNDER_DEMAND, xl.linkState, xl.strainHist, xl.strainPlace);
        sched = new GridScheduler();
        addW("xlinkUnbind.unbind", pad(xl.nLinks));
        return new TornadoExecutionPlan(tg.snapshot());
    }

    // ================================================== 5b all-OFF ≡ HEAD (unbinding off)
    /** With unbinding OFF (default), the 5b-capable scene evolves bit-identically to the 5a path
     *  (the unbind task is omitted; the gather guard is a no-op with all links ACTIVE). Confirms the
     *  lifecycle field + guard do not disturb the proven 5a force/gather path. */
    static boolean allOffUnbind(double dt, int M, boolean useCpu) {
        double sep = REST_LEN + 0.25 * REST_LEN, shear = 0.08 * REST_LEN;
        Scene off  = buildScene(dt, Constants.BTransCoeff, sep, shear, 1, true, false);  // 5b build, unbinding OFF
        Scene head = buildScene(dt, Constants.BTransCoeff, sep, shear, 1, true);         // 5a path (6-arg)
        if (useCpu) {
            for (int t = 0; t < M; t++) { off.fil.setCounts(t, 0x5A11);  cpuStep(off);  }
            for (int t = 0; t < M; t++) { head.fil.setCounts(t, 0x5A11); cpuStep(head); }
        } else {
            TornadoExecutionPlan po = buildPlan(off);  GridScheduler so = sched; TornadoExecutionResult ro = null;
            for (int t = 0; t < M; t++) { off.fil.setCounts(t, 0x5A11);  ro = po.withGridScheduler(so).execute(); }
            ro.transferToHost(off.fil.coord, off.fil.uVec);
            TornadoExecutionPlan ph = buildPlan(head); GridScheduler sh = sched; TornadoExecutionResult rh = null;
            for (int t = 0; t < M; t++) { head.fil.setCounts(t, 0x5A11); rh = ph.withGridScheduler(sh).execute(); }
            rh.transferToHost(head.fil.coord, head.fil.uVec);
        }
        double maxC = maxAbsDiff(off.fil.coord, head.fil.coord);
        double maxU = maxAbsDiff(off.fil.uVec, head.fil.uVec);
        boolean ok = maxC < 1e-12 && maxU < 1e-12;
        System.out.printf("%n--- all-OFF≡HEAD (5b, %s): unbinding OFF ≡ 5a path, Brownian on (%d steps) ---%n", useCpu ? "CPU" : "GPU", M);
        System.out.printf("  max|Δcoord|=%.3g µm   max|ΔuVec|=%.3g   %s%n", maxC, maxU, ok ? "(lifecycle field + guard are no-ops when unbinding off) ✓" : "*PERTURBS 5a*");
        return ok;
    }

    // ============================================================== runners + utils
    static void stepOnce(Scene sc, int t, boolean useCpu) {
        sc.fil.setCounts(t, 0x5A11);
        if (useCpu) cpuStep(sc);
        else {
            if (sc.plan == null) { sc.plan = buildPlan(sc); sc.sched = sched; }
            TornadoExecutionResult res = sc.plan.withGridScheduler(sc.sched).execute();
            res.transferToHost(sc.fil.coord, sc.fil.uVec, sc.fil.yVec);
        }
    }

    static void runSteps(Scene sc, int M, boolean useCpu, int[] samples) {
        for (int t = 0; t < M; t++) stepOnce(sc, t, useCpu);
    }

    /** γ_par (bTransGam.x) and γ_perp (bTransGam.y) of a check-scene filament — self-consistent
     *  with the integrator's stored drag (like DragTensorSystem.fdtPrediction). */
    static double[] dragRatio(double dt) {
        Scene sc = buildScene(dt, 0.0, REST_LEN, 0.0, 1, true);
        return new double[]{ sc.fil.bTransGam.get(sc.fil.planeX(0)), sc.fil.bTransGam.get(sc.fil.planeY(0)) };
    }

    static double[] snapshot(FloatArray a) { double[] o = new double[a.getSize()]; for (int i = 0; i < o.length; i++) o[i] = a.get(i); return o; }
    static double maxAbsDiff(double[] a, FloatArray b) { double m = 0; for (int i = 0; i < a.length; i++) m = Math.max(m, Math.abs(a[i] - b.get(i))); return m; }
    static double maxAbsDiff(FloatArray a, FloatArray b) { double m = 0; for (int i = 0; i < a.getSize(); i++) m = Math.max(m, Math.abs(a.get(i) - b.get(i))); return m; }

    static int[] dedup(int[] a) {
        java.util.TreeSet<Integer> set = new java.util.TreeSet<>();
        for (int x : a) set.add(x);
        int[] o = new int[set.size()]; int i = 0; for (int x : set) o[i++] = x; return o;
    }

    // ============================================================== viewer
    static void runViz(int M, double dt, String dir) {
        Scene sc = buildScene(dt, 0.0, 2.0 * REST_LEN, 0.5 * REST_LEN, 1, true);   // visibly off-rest, relaxes
        new java.io.File(dir).mkdirs();
        int every = Math.max(1, M / 300), frames = 0;
        for (int t = 0; t <= M; t++) {
            if (t % every == 0) writeFrame(dir, frames++, t * dt, sc);
            stepOnce(sc, t, true);
        }
        System.out.println("viewer: wrote " + frames + " frames to " + dir);
    }

    static void writeFrame(String dir, int frame, double t, Scene sc) {
        FilamentStore f = sc.fil;
        StringBuilder sb = new StringBuilder(512);
        sb.append(String.format(java.util.Locale.US, "{\"frame\":%d,\"t\":%.6g", frame, t));
        sb.append(",\"bounds\":{\"xDim\":0.3,\"yDim\":0.2,\"zDim\":0.2}");
        sb.append(",\"segments\":[");
        for (int s = 0; s < f.n; s++) {
            if (s > 0) sb.append(',');
            sb.append(String.format(java.util.Locale.US,
                "{\"id\":%d,\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.5g,\"notADPRatio\":1.0,\"cofilinCount\":0}",
                s, f.end1.get(s), f.end1.get(f.n + s), f.end1.get(2 * f.n + s),
                f.end2.get(s), f.end2.get(f.n + s), f.end2.get(2 * f.n + s), Constants.radius));
        }
        sb.append("]}");
        try {
            java.nio.file.Files.writeString(java.nio.file.Path.of(dir, String.format(java.util.Locale.US, "frame_%06d.json", frame)), sb.toString());
        } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }
}
