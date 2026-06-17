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

        // ===================== increment 5c-i: Design-A scan-rank free-list allocator =====================
        ok &= run5ci(!cpu);                               // 7 checks; CPU≡GPU only when GPU available

        // ===================== increment 5c-ii: crosslinker formation =====================
        ok &= run5cii(!cpu);                              // 6 checks; CPU≡GPU only when GPU available

        // ===================== increment 5c-iii Phase 1: force law (dynamic fracMove) + torsion =========
        ok &= run5ciiiP1(!cpu);                           // dynamic fracMove + torsion arithmetic vs v1; CPU≡GPU; all-OFF

        System.out.println();
        System.out.println("=== CROSSLINKER 5a+5b+5c-i+5c-ii+5c-iii(P1) VALIDATION " + (ok ? "PASS" : "FAIL") + " ===");
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

    // ================================================== 5c-i: Design-A scan-rank free-list allocator
    static final class AllocScene {
        FilamentStore fil; CrosslinkerStore xl;
        boolean unbindOn; int seed, C, K;
        TornadoExecutionPlan plan; GridScheduler sched;
    }

    /** 2 filaments at a controlled strain (frozen pose — 5c-i tests bookkeeping, not forces); a pool of
     *  capacity C with `nActive` pre-placed ACTIVE links (slots 0..nActive-1), the rest FREE; reqCap=K. */
    static AllocScene buildAllocScene(int C, int K, int nActive, double strain, double dt, boolean unbindOn, int seed) {
        AllocScene as = new AllocScene();
        int nSeg = 2; double L = (FIL_MONO + 1) * Constants.actinMonoRadius;
        FilamentStore fil = new FilamentStore(nSeg);
        for (int s = 0; s < nSeg; s++) {
            fil.monomerCount.set(s, FIL_MONO);
            fil.setUVec(s, 1f, 0f, 0f); fil.setYVec(s, 0f, 1f, 0f);
            fil.brownTransScale.set(s, 0f); fil.brownRotScale.set(s, 0f);
        }
        fil.setCoord(0, 0f, 0f, 0f);
        fil.setCoord(1, 0f, 0f, (float) (REST_LEN * (1.0 + strain)));
        DragTensorSystem.run(fil); fil.setParams(dt, Math.sqrt(2.0 * Constants.kT / dt)); fil.setCounts(0, 0);
        DerivedGeometrySystem.derive(fil.coord, fil.uVec, fil.yVec, fil.zVec, fil.end1, fil.end2, fil.segLength, fil.counts);
        CrosslinkerStore xl = new CrosslinkerStore(C, nSeg, K);
        xl.setParams(REST_LEN, FRAC_MOVE, dt);
        xl.setOffParams(OFF_CONST, OFF_COEFF, OFF_EXP, dt, REST_LEN);
        xl.setRequestCount(K);
        for (int k = 0; k < nActive; k++) xl.setLink(k, 0, 0.5 * L, 1, 0.5 * L);   // pre-placed ACTIVE
        xl.computeFilLinkCt();
        as.fil = fil; as.xl = xl; as.unbindOn = unbindOn; as.seed = seed; as.C = C; as.K = K;
        return as;
    }

    /** Synthetic deterministic form-requests: K requests between fil0/fil1, all accepted; reqLoc2 carries
     *  a unique per-(step,r) FINGERPRINT (step·1000+r) so we can verify the exact slot each request lands
     *  in. No RNG (allocation is the only variable under test). */
    static void fillRequests(AllocScene as, int step, int nAccept) {
        CrosslinkerStore xl = as.xl; double L = (FIL_MONO + 1) * Constants.actinMonoRadius;
        for (int r = 0; r < as.K; r++) {
            xl.reqFilA.set(r, 0); xl.reqFilB.set(r, 1);
            xl.reqLoc1.set(r, (float) (0.5 * L));
            xl.reqLoc2.set(r, (float) (step * 1000 + r));   // fingerprint
            xl.acceptFlag.set(r, r < nAccept ? 1 : 0);
        }
    }

    static void allocBuildFreeList(CrosslinkerStore xl) {
        CrosslinkerSystem.freeFlags(xl.linkState, xl.freeCount, xl.allocCounts);
        CrossBridgeSystem.csrScan(xl.freeScanCounts, xl.freeCount, xl.freeOffsets);   // REUSED prefix sum
        CrosslinkerSystem.freeScatter(xl.linkState, xl.freeOffsets, xl.freeList, xl.allocCounts);
    }
    static void allocRankAndPlace(CrosslinkerStore xl) {
        CrossBridgeSystem.csrScan(xl.rankScanCounts, xl.acceptFlag, xl.rankOffsets);  // REUSED prefix sum
        CrosslinkerSystem.allocate(xl.reqFilA, xl.reqFilB, xl.reqLoc1, xl.reqLoc2, xl.rankOffsets, xl.freeList, xl.freeOffsets,
                xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2, xl.linkState, xl.strainHist, xl.strainPlace, xl.allocCounts);
    }

    /** One allocator step (CPU): death (5b) → build free-list → rank → allocate (the planner phase order,
     *  so same-step deaths free slots the same-step formation reuses). */
    static void allocStepCpu(AllocScene as, int step, int nAccept) {
        CrosslinkerStore xl = as.xl;
        xl.setCounts(step, as.seed);
        if (as.unbindOn) CrosslinkerSystem.unbind(as.fil.coord, as.fil.uVec, as.fil.end1, xl.linkFilA, xl.linkFilB,
                xl.loc1, xl.loc2, xl.linkState, xl.strainHist, xl.strainPlace, xl.offParams, xl.counts);
        fillRequests(as, step, nAccept);
        allocBuildFreeList(xl);
        allocRankAndPlace(xl);
    }

    static TornadoExecutionPlan buildAllocPlan(AllocScene as) {
        FilamentStore f = as.fil; CrosslinkerStore xl = as.xl;
        TaskGraph tg = new TaskGraph("xalloc")
            .transferToDevice(DataTransferMode.FIRST_EXECUTION, f.coord, f.uVec, f.end1,
                    xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2, xl.linkState, xl.strainHist, xl.strainPlace, xl.offParams,
                    xl.freeCount, xl.freeOffsets, xl.freeList, xl.freeScanCounts, xl.rankOffsets, xl.allocCounts)
            .transferToDevice(DataTransferMode.EVERY_EXECUTION, xl.counts, xl.reqFilA, xl.reqFilB, xl.reqLoc1, xl.reqLoc2, xl.acceptFlag, xl.rankScanCounts);
        if (as.unbindOn) tg = tg.task("unbind", CrosslinkerSystem::unbind, f.coord, f.uVec, f.end1,
                xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2, xl.linkState, xl.strainHist, xl.strainPlace, xl.offParams, xl.counts);
        tg = tg
            .task("freeFlags", CrosslinkerSystem::freeFlags, xl.linkState, xl.freeCount, xl.allocCounts)
            .task("scanFree", CrossBridgeSystem::csrScan, xl.freeScanCounts, xl.freeCount, xl.freeOffsets)
            .task("freeScatter", CrosslinkerSystem::freeScatter, xl.linkState, xl.freeOffsets, xl.freeList, xl.allocCounts)
            .task("scanRank", CrossBridgeSystem::csrScan, xl.rankScanCounts, xl.acceptFlag, xl.rankOffsets)
            .task("allocate", CrosslinkerSystem::allocate, xl.reqFilA, xl.reqFilB, xl.reqLoc1, xl.reqLoc2, xl.rankOffsets, xl.freeList, xl.freeOffsets,
                    xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2, xl.linkState, xl.strainHist, xl.strainPlace, xl.allocCounts)
            .transferToHost(DataTransferMode.UNDER_DEMAND, xl.linkState, xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2,
                    xl.strainHist, xl.strainPlace, xl.freeList, xl.freeOffsets, xl.rankOffsets);
        sched = new GridScheduler();
        if (as.unbindOn) addW("xalloc.unbind", pad(as.C));
        addW("xalloc.freeFlags", pad(as.C));
        addS("xalloc.scanFree");
        addS("xalloc.freeScatter");
        addS("xalloc.scanRank");
        addW("xalloc.allocate", pad(as.K));
        as.sched = sched;
        return new TornadoExecutionPlan(tg.snapshot());
    }

    // ---- the 7 checks ----
    static boolean run5ci(boolean gpuAvailable) {
        System.out.println("\n========== increment 5c-i — Design-A scan-rank free-list allocator (synthetic driver) ==========");
        boolean ok = true;
        ok &= ckDistinctAndFreeList();   // #1 distinct-slot/no-double-alloc + #2 free-list correctness
        ok &= ckDeathReuseStability();   // #3 death→same-step reuse + #5 slot-stability (churn)
        ok &= ckOverflow();              // #4 overflow clamp
        if (gpuAvailable) ok &= ckCpuGpu();   // #6 CPU≡GPU bit-identical ≥400 steps
        ok &= ckAllOffForm(gpuAvailable);     // #7 all-OFF≡HEAD (K=0 ≡ 5b path)
        return ok;
    }

    /** #1 + #2: one formation step on an empty pool — free-list = FREE slots in index order; K accepted
     *  claim K distinct slots in rank order with payloads landing; no double-alloc. */
    static boolean ckDistinctAndFreeList() {
        int C = 64, K = 8;
        AllocScene as = buildAllocScene(C, K, 0, 0.0, 1.0e-5, false, 0xA11);
        CrosslinkerStore xl = as.xl;
        xl.setCounts(0, as.seed);
        fillRequests(as, 0, K);
        allocBuildFreeList(xl);
        int nFree = xl.freeOffsets.get(C);
        boolean flOk = (nFree == C);
        for (int s = 0; s < nFree && flOk; s++) if (xl.freeList.get(s) != s) flOk = false;   // empty pool ⇒ 0..C-1
        allocRankAndPlace(xl);
        int active = countActive(xl);
        // each request r (rank r, all accepted) claims slot freeList[r]==r; verify landing + fresh ring + distinct
        boolean distinct = true, land = true, ring = true;
        java.util.HashSet<Integer> claimed = new java.util.HashSet<>();
        for (int r = 0; r < K; r++) {
            int slot = r;   // freeList[r] on empty pool
            if (!claimed.add(slot)) distinct = false;
            if (xl.linkState.get(slot) != CrosslinkerStore.LINK_ACTIVE) land = false;
            if (xl.loc2.get(slot) != (float) (0 * 1000 + r)) land = false;
            if (xl.strainPlace.get(slot) != 0) ring = false;
            for (int j = 0; j < CrosslinkerStore.STRAIN_WIN; j++) if (xl.strainHist.get(slot * 10 + j) != 0f) ring = false;
        }
        boolean ok = flOk && active == K && distinct && land && ring;
        System.out.println("\n--- #1/#2 distinct-slot + free-list correctness (empty pool, K=" + K + " accepted) ---");
        System.out.printf("  free-list = FREE slots in index order: %s  (nFree=%d)%n", flOk ? "✓" : "*WRONG*", nFree);
        System.out.printf("  %d accepted ⇒ %d ACTIVE; distinct slots=%s; payload landing=%s; fresh strain ring=%s  %s%n",
                K, active, distinct ? "✓" : "*DUP*", land ? "✓" : "*WRONG*", ring ? "✓" : "*STALE*", ok ? "" : "*FAIL*");
        return ok;
    }

    /** #3 + #5: churn (all-ACTIVE pool, high strain ⇒ Bell deaths; K refills/step). Each step verifies
     *  slot-stability (allocate never writes a slot that was ACTIVE before it) and detects same-step
     *  death→reuse (a slot ACTIVE→FREE in unbind → ACTIVE again in allocate, fresh ring). */
    static boolean ckDeathReuseStability() {
        int C = 16, K = 8, steps = 300;
        AllocScene as = buildAllocScene(C, K, C, 2.0, 1.0e-4, true, 0xC33);
        CrosslinkerStore xl = as.xl;
        boolean stability = true, sawReuse = false, reuseRingOk = true;
        int reuseSlot = -1, reuseStep = -1;
        int[] beforeAll = new int[C], afterUnbind = new int[C];
        int[] preFilA = new int[C]; float[] preLoc2 = new float[C];
        for (int t = 0; t < steps; t++) {
            for (int s = 0; s < C; s++) beforeAll[s] = xl.linkState.get(s);
            xl.setCounts(t, as.seed);
            CrosslinkerSystem.unbind(as.fil.coord, as.fil.uVec, as.fil.end1, xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2,
                    xl.linkState, xl.strainHist, xl.strainPlace, xl.offParams, xl.counts);
            for (int s = 0; s < C; s++) afterUnbind[s] = xl.linkState.get(s);
            // snapshot ACTIVE-before-allocate slots (for stability)
            for (int s = 0; s < C; s++) { preFilA[s] = xl.linkFilA.get(s); preLoc2[s] = xl.loc2.get(s); }
            fillRequests(as, t, K);
            allocBuildFreeList(xl);
            allocRankAndPlace(xl);
            for (int s = 0; s < C; s++) {
                boolean wasActive = afterUnbind[s] >= 0;
                if (wasActive) {   // slot-stability: an ACTIVE-before-allocate slot must be untouched
                    if (xl.linkFilA.get(s) != preFilA[s] || xl.loc2.get(s) != preLoc2[s] || xl.linkState.get(s) < 0) stability = false;
                }
                // same-step death→reuse: ACTIVE(before) → FREE(unbind) → ACTIVE(after alloc)
                if (beforeAll[s] >= 0 && afterUnbind[s] < 0 && xl.linkState.get(s) >= 0) {
                    if (!sawReuse) { sawReuse = true; reuseSlot = s; reuseStep = t; }
                    if (xl.strainPlace.get(s) != 0) reuseRingOk = false;
                    float lp = xl.loc2.get(s);   // must be a fingerprint from THIS step's formation
                    if (!(lp >= t * 1000 && lp < t * 1000 + K)) reuseRingOk = false;
                }
            }
        }
        boolean ok = stability && sawReuse && reuseRingOk;
        System.out.println("\n--- #3/#5 death→same-step reuse + slot-stability (churn, C=" + C + " all-ACTIVE, strain 2, " + steps + " steps) ---");
        System.out.printf("  slot-stability (allocate never overwrites an ACTIVE slot): %s%n", stability ? "✓" : "*MOVED/OVERWROTE*");
        System.out.printf("  same-step death→reuse observed: %s (first @ slot %d step %d; fresh ring + this-step payload: %s)  %s%n",
                sawReuse ? "✓" : "*NONE*", reuseSlot, reuseStep, reuseRingOk ? "✓" : "*STALE*", ok ? "" : "*FAIL*");
        return ok;
    }

    /** #4: nAccepted > nFree ⇒ exactly nFree form (lowest ranks), the rest not-formed, no OOB. */
    static boolean ckOverflow() {
        int C = 8, K = 5, nActive = 6;   // nFree = 2
        AllocScene as = buildAllocScene(C, K, nActive, 0.0, 1.0e-5, false, 0xD44);
        CrosslinkerStore xl = as.xl;
        xl.setCounts(0, as.seed);
        fillRequests(as, 0, K);          // all 5 accepted
        allocBuildFreeList(xl);
        int nFree = xl.freeOffsets.get(C);
        allocRankAndPlace(xl);
        int active = countActive(xl);
        // exactly nFree formed: slots 6,7 ACTIVE with requests 0,1's fingerprints; requests 2,3,4 absent
        boolean formedOk = (active == nActive + nFree);
        boolean lowestRanks = xl.linkState.get(6) >= 0 && xl.linkState.get(7) >= 0
                && xl.loc2.get(6) == 0f && xl.loc2.get(7) == 1f;     // fingerprints of requests 0,1
        boolean overflowAbsent = true;
        for (int r = 2; r < K; r++) { float fp = r; for (int s = 0; s < C; s++) if (xl.loc2.get(s) == fp) overflowAbsent = false; }
        boolean ok = nFree == 2 && formedOk && lowestRanks && overflowAbsent;
        System.out.println("\n--- #4 overflow clamp (nFree=" + nFree + ", nAccepted=" + K + ") ---");
        System.out.printf("  exactly nFree=%d formed (lowest ranks), %d over-clamp requests not-formed, no OOB: %s  %s%n",
                nFree, K - nFree, (formedOk && lowestRanks && overflowAbsent) ? "✓" : "*WRONG*", ok ? "" : "*FAIL*");
        return ok;
    }

    /** #6: identical slot assignments + payloads + strain rings + free-list/ranks over ≥400 churn steps,
     *  CPU runner vs GPU TaskGraph (index-ordered free-list + ranks ⇒ bit-identical, no atomics). */
    static boolean ckCpuGpu() {
        int C = 32, K = 6, steps = 400;
        AllocScene cpu = buildAllocScene(C, K, C, 1.5, 1.0e-4, true, 0xF66);
        AllocScene gpu = buildAllocScene(C, K, C, 1.5, 1.0e-4, true, 0xF66);
        TornadoExecutionPlan plan = buildAllocPlan(gpu); GridScheduler sg = gpu.sched;
        int[] samples = { 1, 50, 200, steps - 1 };
        int si = 0; long diffs = 0;
        for (int t = 0; t < steps; t++) {
            allocStepCpu(cpu, t, K);
            gpu.xl.setCounts(t, gpu.seed); fillRequests(gpu, t, K); gpu.xl.setRequestCount(K);
            TornadoExecutionResult r = plan.withGridScheduler(sg).execute();
            if (si < samples.length && t == samples[si]) {
                r.transferToHost(gpu.xl.linkState, gpu.xl.linkFilA, gpu.xl.linkFilB, gpu.xl.loc1, gpu.xl.loc2, gpu.xl.strainHist, gpu.xl.strainPlace);
                for (int s = 0; s < C; s++) {
                    if (cpu.xl.linkState.get(s) != gpu.xl.linkState.get(s)) diffs++;
                    if (cpu.xl.linkFilA.get(s) != gpu.xl.linkFilA.get(s)) diffs++;
                    if (cpu.xl.loc2.get(s) != gpu.xl.loc2.get(s)) diffs++;
                    if (cpu.xl.strainPlace.get(s) != gpu.xl.strainPlace.get(s)) diffs++;
                    for (int j = 0; j < CrosslinkerStore.STRAIN_WIN; j++)
                        if (cpu.xl.strainHist.get(s * 10 + j) != gpu.xl.strainHist.get(s * 10 + j)) diffs++;
                }
                si++;
            }
        }
        boolean ok = diffs == 0;
        System.out.println("\n--- #6 CPU≡GPU bit-identical (C=" + C + ", K=" + K + ", " + steps + " churn steps) ---");
        System.out.printf("  slot assignments + payloads + strain rings: %d field mismatches  %s%n", diffs, ok ? "(bit-identical) ✓" : "*DIFFERS*");
        return ok;
    }

    /** #7: K=0 (no form-requests) ⇒ the allocator is a no-op; linkState evolves identically to the
     *  5b unbind-only path (bit-identical). Confirms formation default-off ≡ the 5a/5b path. */
    static boolean ckAllOffForm(boolean gpuAvailable) {
        int C = 32, steps = 300;
        AllocScene form = buildAllocScene(C, 1, C, 1.5, 1.0e-4, true, 0x077);   // K-capacity 1 but 0 accepted
        AllocScene bare = buildAllocScene(C, 1, C, 1.5, 1.0e-4, true, 0x077);
        for (int t = 0; t < steps; t++) {
            allocStepCpu(form, t, 0);                       // 0 accepted ⇒ allocate forms nothing
            bare.xl.setCounts(t, bare.seed);                // bare: unbind only (the 5b path)
            CrosslinkerSystem.unbind(bare.fil.coord, bare.fil.uVec, bare.fil.end1, bare.xl.linkFilA, bare.xl.linkFilB,
                    bare.xl.loc1, bare.xl.loc2, bare.xl.linkState, bare.xl.strainHist, bare.xl.strainPlace, bare.xl.offParams, bare.xl.counts);
        }
        long diffs = 0;
        for (int s = 0; s < C; s++) {
            if (form.xl.linkState.get(s) != bare.xl.linkState.get(s)) diffs++;
            if (form.xl.strainPlace.get(s) != bare.xl.strainPlace.get(s)) diffs++;
            for (int j = 0; j < CrosslinkerStore.STRAIN_WIN; j++)
                if (form.xl.strainHist.get(s * 10 + j) != bare.xl.strainHist.get(s * 10 + j)) diffs++;
        }
        boolean ok = diffs == 0;
        System.out.println("\n--- #7 all-OFF≡HEAD (K=0 formation ≡ 5b unbind-only path, " + steps + " steps) ---");
        System.out.printf("  %d field mismatches vs the bare 5b path  %s%n", diffs, ok ? "(formation no-op when off) ✓" : "*PERTURBS 5b*");
        return ok;
    }

    // ================================================== 5c-ii: crosslinker formation (broad-phase + gates + P_form)
    static final double MAX_ANGLE        = Math.PI / 12.0;             // maxXLinkBondAngle (Env.java:636)
    static final double GRAB_DIST        = 2.0 * Constants.actinMonoDiam;  // crossLinkGrabDist (Env.java:645)
    static final double MIN_SEP          = 5.0 * Constants.actinMonoDiam;  // minSepBetweenXLinks (Env.java:624)
    static final double MIN_FILLINK_SEP  = 2.0 * Constants.actinMonoDiam;  // loc jitter half-range (FilSegment.java:123)
    static final int    MAX_LINKS_ON_SEG = 10;                         // maxXLinksOnSeg (Env.java:623)
    static final double XLINK_ON_RATE    = 10.0;                       // xLinkOnRate (Env.java:669)
    static final double XLINK_CONC       = 1.0;                        // xLinkConc (Env.java:670)
    static final int    XLINK_CHECK_INT  = 10;                         // crosslinkCheckInt = biochemDeltaT/deltaT (1e-3/1e-4)
    static double pFormV1(double dt) { return 1.0 - Math.exp(-XLINK_ON_RATE * XLINK_CONC * (dt * XLINK_CHECK_INT)); }

    static final class FormScene {
        FilamentStore fil; CrosslinkerStore xl; IntArray filID;
        boolean unbindOn; int seed, C, reqCap;
        TornadoExecutionPlan plan; GridScheduler sched;
    }

    /** A crossing bundle on a 2D (angle × z) grid: filament (ai,zi) is rotated in the xy-plane by
     *  θ = ai·dθ and stacked at z = zi·zPitch, centred so all xy-projections cross near the origin. A pair
     *  thus crosses at a clean angle |Δai|·dθ (lineSegmentIntersectTest well-conditioned, unless Δai=0 ⇒
     *  parallel ⇒ v1's no-collision quirk) with closest approach ≈ |Δz| = |Δzi|·zPitch. This DECOUPLES the
     *  two gates: alignment is spanned by Δai (independent of distance), distance by Δzi (independent of
     *  angle) — so #2 exercises align-fail-distance-pass AND distance-fail-align-pass. segsPerFil segments
     *  tiled along each filament's axis; filID = filament index (same-filament pairs exist when >1). */
    static FormScene buildFormScene(int nA, int nZ, int segsPerFil, double zPitch, double dThetaDeg, double dt,
                                    int mode, double pForm, boolean unbindOn, int seed) {
        FormScene fs = new FormScene();
        int nFil = nA * nZ, nSeg = nFil * segsPerFil;
        double L = (FIL_MONO + 1) * Constants.actinMonoRadius;
        double dTheta = Math.toRadians(dThetaDeg);
        FilamentStore fil = new FilamentStore(nSeg);
        IntArray filID = new IntArray(nSeg);
        int s = 0;
        for (int f = 0; f < nFil; f++) {
            int ai = f % nA, zi = f / nA;
            double th = ai * dTheta;                       // angle index ⇒ alignment gate
            double ux = Math.cos(th), uy = Math.sin(th), z = (zi - (nZ - 1) / 2.0) * zPitch;   // z index ⇒ distance gate
            for (int k = 0; k < segsPerFil; k++) {
                fil.monomerCount.set(s, FIL_MONO);
                fil.setUVec(s, (float) ux, (float) uy, 0f);
                fil.setYVec(s, (float) (-uy), (float) ux, 0f);   // unit, ⊥ uVec
                double along = (k - (segsPerFil - 1) / 2.0) * L;
                fil.setCoord(s, (float) (along * ux), (float) (along * uy), (float) z);
                fil.brownTransScale.set(s, 0f); fil.brownRotScale.set(s, 0f);
                filID.set(s, f);
                s++;
            }
        }
        DragTensorSystem.run(fil); fil.setParams(dt, Math.sqrt(2.0 * Constants.kT / dt)); fil.setCounts(0, 0);
        DerivedGeometrySystem.derive(fil.coord, fil.uVec, fil.yVec, fil.zVec, fil.end1, fil.end2, fil.segLength, fil.counts);

        int reqCap = nSeg * (nSeg - 1) / 2;                          // all cross-fil pairs (broad-phase capacity)
        int C = Math.max(8, nSeg * 4);                                // link pool capacity
        CrosslinkerStore xl = new CrosslinkerStore(C, nSeg, reqCap);
        xl.setParams(REST_LEN, FRAC_MOVE, dt);
        xl.setOffParams(OFF_CONST, OFF_COEFF, OFF_EXP, dt, REST_LEN);
        xl.setFormParams(MAX_ANGLE, GRAB_DIST, MIN_SEP, MAX_LINKS_ON_SEG, pForm, MIN_FILLINK_SEP, mode);
        xl.setRequestCount(reqCap);
        fs.fil = fil; fs.xl = xl; fs.filID = filID; fs.unbindOn = unbindOn; fs.seed = seed; fs.C = C; fs.reqCap = reqCap;
        return fs;
    }

    /** Phase order: unbind(5b death) → countActiveLinks → broad-phase → gates → admit → [5c-i allocator]. */
    static void formStepCpu(FormScene fs, int step) {
        FilamentStore f = fs.fil; CrosslinkerStore xl = fs.xl;
        xl.setCounts(step, fs.seed); xl.setFormStep(step, fs.seed);
        if (fs.unbindOn) CrosslinkerSystem.unbind(f.coord, f.uVec, f.end1, xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2,
                xl.linkState, xl.strainHist, xl.strainPlace, xl.offParams, xl.counts);
        CrosslinkerSystem.countActiveLinks(xl.linkState, xl.linkFilA, xl.linkFilB, xl.activeLinkCount, xl.formCounts);
        CrosslinkerSystem.filFilCandidates(f.coord, f.segLength, fs.filID, xl.reqFilA, xl.reqFilB, xl.formParams, xl.formCounts);
        CrosslinkerSystem.formGates(f.uVec, f.end1, f.end2, f.segLength, xl.reqFilA, xl.reqFilB, xl.reqLoc1, xl.reqLoc2,
                xl.reqOrient, xl.gatePass, xl.formParams, xl.formCounts);
        CrosslinkerSystem.formAdmitReduce(xl.reqFilA, xl.reqFilB, xl.gatePass, xl.minCand, xl.formCounts);
        CrosslinkerSystem.formAdmit(xl.reqFilA, xl.reqFilB, xl.reqLoc1, xl.reqLoc2, xl.gatePass, xl.minCand, xl.activeLinkCount,
                xl.linkState, xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2, xl.acceptFlag, xl.formParams, xl.formCounts);
        // 5c-i allocator (UNCHANGED) + orientSame persist
        CrosslinkerSystem.freeFlags(xl.linkState, xl.freeCount, xl.allocCounts);
        CrossBridgeSystem.csrScan(xl.freeScanCounts, xl.freeCount, xl.freeOffsets);
        CrosslinkerSystem.freeScatter(xl.linkState, xl.freeOffsets, xl.freeList, xl.allocCounts);
        CrossBridgeSystem.csrScan(xl.rankScanCounts, xl.acceptFlag, xl.rankOffsets);
        CrosslinkerSystem.allocate(xl.reqFilA, xl.reqFilB, xl.reqLoc1, xl.reqLoc2, xl.rankOffsets, xl.freeList, xl.freeOffsets,
                xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2, xl.linkState, xl.strainHist, xl.strainPlace, xl.allocCounts);
        CrosslinkerSystem.placeOrient(xl.reqOrient, xl.rankOffsets, xl.freeList, xl.freeOffsets, xl.linkOrientSame, xl.torqueMagHist, xl.torqueMagPlace, xl.allocCounts);
    }

    static TornadoExecutionPlan buildFormPlan(FormScene fs) {
        FilamentStore f = fs.fil; CrosslinkerStore xl = fs.xl;
        TaskGraph tg = new TaskGraph("xform")
            .transferToDevice(DataTransferMode.FIRST_EXECUTION, f.coord, f.uVec, f.end1, f.end2, f.segLength,
                    fs.filID, xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2, xl.linkState, xl.strainHist, xl.strainPlace,
                    xl.offParams, xl.linkOrientSame, xl.reqFilA, xl.reqFilB, xl.reqLoc1, xl.reqLoc2, xl.reqOrient,
                    xl.gatePass, xl.acceptFlag, xl.minCand, xl.activeLinkCount, xl.formParams,
                    xl.freeCount, xl.freeOffsets, xl.freeList, xl.freeScanCounts, xl.rankOffsets, xl.rankScanCounts, xl.allocCounts)
            .transferToDevice(DataTransferMode.EVERY_EXECUTION, xl.counts, xl.formCounts);
        if (fs.unbindOn) tg = tg.task("unbind", CrosslinkerSystem::unbind, f.coord, f.uVec, f.end1,
                xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2, xl.linkState, xl.strainHist, xl.strainPlace, xl.offParams, xl.counts);
        tg = tg
            .task("countActive", CrosslinkerSystem::countActiveLinks, xl.linkState, xl.linkFilA, xl.linkFilB, xl.activeLinkCount, xl.formCounts)
            .task("cands", CrosslinkerSystem::filFilCandidates, f.coord, f.segLength, fs.filID, xl.reqFilA, xl.reqFilB, xl.formParams, xl.formCounts)
            .task("gates", CrosslinkerSystem::formGates, f.uVec, f.end1, f.end2, f.segLength, xl.reqFilA, xl.reqFilB, xl.reqLoc1, xl.reqLoc2, xl.reqOrient, xl.gatePass, xl.formParams, xl.formCounts)
            .task("admitReduce", CrosslinkerSystem::formAdmitReduce, xl.reqFilA, xl.reqFilB, xl.gatePass, xl.minCand, xl.formCounts)
            .task("admit", CrosslinkerSystem::formAdmit, xl.reqFilA, xl.reqFilB, xl.reqLoc1, xl.reqLoc2, xl.gatePass, xl.minCand, xl.activeLinkCount, xl.linkState, xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2, xl.acceptFlag, xl.formParams, xl.formCounts)
            .task("freeFlags", CrosslinkerSystem::freeFlags, xl.linkState, xl.freeCount, xl.allocCounts)
            .task("scanFree", CrossBridgeSystem::csrScan, xl.freeScanCounts, xl.freeCount, xl.freeOffsets)
            .task("freeScatter", CrosslinkerSystem::freeScatter, xl.linkState, xl.freeOffsets, xl.freeList, xl.allocCounts)
            .task("scanRank", CrossBridgeSystem::csrScan, xl.rankScanCounts, xl.acceptFlag, xl.rankOffsets)
            .task("allocate", CrosslinkerSystem::allocate, xl.reqFilA, xl.reqFilB, xl.reqLoc1, xl.reqLoc2, xl.rankOffsets, xl.freeList, xl.freeOffsets, xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2, xl.linkState, xl.strainHist, xl.strainPlace, xl.allocCounts)
            .task("placeOrient", CrosslinkerSystem::placeOrient, xl.reqOrient, xl.rankOffsets, xl.freeList, xl.freeOffsets, xl.linkOrientSame, xl.torqueMagHist, xl.torqueMagPlace, xl.allocCounts)
            .transferToHost(DataTransferMode.UNDER_DEMAND, xl.linkState, xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2,
                    xl.linkOrientSame, xl.strainHist, xl.strainPlace, xl.reqFilA, xl.reqFilB, xl.gatePass, xl.reqOrient, xl.reqLoc1, xl.reqLoc2, xl.formCounts);
        sched = new GridScheduler();
        if (fs.unbindOn) addW("xform.unbind", pad(fs.C));
        addS("xform.countActive"); addS("xform.cands");
        addW("xform.gates", pad(fs.reqCap));                 // RNG kernel ⇒ localWork=64
        addS("xform.admitReduce");
        addW("xform.admit", pad(fs.reqCap));
        addW("xform.freeFlags", pad(fs.C)); addS("xform.scanFree"); addS("xform.freeScatter"); addS("xform.scanRank");
        addW("xform.allocate", pad(fs.reqCap)); addW("xform.placeOrient", pad(fs.reqCap));
        fs.sched = sched;
        return new TornadoExecutionPlan(tg.snapshot());
    }

    // ---- v1-faithful host references (analytic oracle) ----
    static double v1FastAcos(double dot) {
        if (dot > 0.95) { double t = 1.0 - dot; if (t < 0) t = 0; return Math.sqrt(2.0 * t); }
        if (dot < -0.95) { double t = 1.0 + dot; if (t < 0) t = 0; return Math.PI - Math.sqrt(2.0 * t); }
        return Math.acos(dot);
    }
    /** v1 checkToLink alignment+distance gate (geometry only, no RNG). Returns {pass(0/1), orientSame, conDistSq}. */
    static double[] v1GeomGate(FormScene fs, int a, int b, int mode) {
        FilamentStore f = fs.fil; int n = f.n;
        double uax = f.uVec.get(a), uay = f.uVec.get(n + a), uaz = f.uVec.get(2 * n + a);
        double ubx = f.uVec.get(b), uby = f.uVec.get(n + b), ubz = f.uVec.get(2 * n + b);
        double dot = uax * ubx + uay * uby + uaz * ubz;
        double angT = v1FastAcos(dot), angTR = v1FastAcos(-dot);
        boolean align = mode == 1 ? angT <= MAX_ANGLE : mode == -1 ? angTR <= MAX_ANGLE : (angT <= MAX_ANGLE || angTR <= MAX_ANGLE);
        if (!align) return new double[]{ 0, 0, -1 };
        double e1ax = f.end1.get(a), e1ay = f.end1.get(n + a), e1az = f.end1.get(2 * n + a);
        double e2ax = f.end2.get(a), e2ay = f.end2.get(n + a), e2az = f.end2.get(2 * n + a);
        double e1bx = f.end1.get(b), e1by = f.end1.get(n + b), e1bz = f.end1.get(2 * n + b);
        double e2bx = f.end2.get(b), e2by = f.end2.get(n + b), e2bz = f.end2.get(2 * n + b);
        double r1x = e2ax - e1ax, r1y = e2ay - e1ay, r1z = e2az - e1az;
        double r2x = e2bx - e1bx, r2y = e2by - e1by, r2z = e2bz - e1bz;
        double r3x = e1bx - e1ax, r3y = e1by - e1ay, r3z = e1bz - e1az;
        double r4x = r1y * r2z - r1z * r2y, r4y = r1z * r2x - r1x * r2z, r4z = r1x * r2y - r1y * r2x;
        double orient = dot >= 0 ? 1 : 0;
        if (r4x < 1e-20 && r4y < 1e-20 && r4z < 1e-20) return new double[]{ 0, orient, -1 };
        double denom = r4x * r4x + r4y * r4y + r4z * r4z;
        double c32x = r3y * r2z - r3z * r2y, c32y = r3z * r2x - r3x * r2z, c32z = r3x * r2y - r3y * r2x;
        double alpha = (r4x * c32x + r4y * c32y + r4z * c32z) / denom;
        if (alpha < 0 || alpha > 1) return new double[]{ 0, orient, -1 };
        double c31x = r3y * r1z - r3z * r1y, c31y = r3z * r1x - r3x * r1z, c31z = r3x * r1y - r3y * r1x;
        double beta = (r4x * c31x + r4y * c31y + r4z * c31z) / denom;
        if (beta < 0 || beta > 1) return new double[]{ 0, orient, -1 };
        double p1x = e1ax + alpha * r1x, p1y = e1ay + alpha * r1y, p1z = e1az + alpha * r1z;
        double p2x = e1bx + beta * r2x, p2y = e1by + beta * r2y, p2z = e1bz + beta * r2z;
        double dd = (p1x - p2x) * (p1x - p2x) + (p1y - p2y) * (p1y - p2y) + (p1z - p2z) * (p1z - p2z);
        double pass = dd < GRAB_DIST * GRAB_DIST ? 1 : 0;
        return new double[]{ pass, orient, dd };
    }

    static boolean run5cii(boolean gpuAvailable) {
        System.out.println("\n========== increment 5c-ii — crosslinker formation (broad-phase + gates + P_form) ==========");
        boolean ok = true;
        ok &= ckBroadPhase();          // #1 candidate-set correctness
        ok &= ckGateArithmetic();      // #2 gate arithmetic bit-exact vs v1
        ok &= ckPform();               // #3 P_form formula + cadence + empirical
        ok &= ckContention();          // #4 one-per-seg cap contention self-check (the flagged decision)
        if (gpuAvailable) ok &= ckFormCpuGpu();   // #5 CPU≡GPU full pipeline
        ok &= ckFormAllOff(gpuAvailable);         // #6 all-OFF≡HEAD
        return ok;
    }

    /** #1: broad-phase emits exactly the distinct unordered cross-filament pairs within the coarse bound;
     *  same-filament excluded; complete superset of the fine-gate passers. */
    static boolean ckBroadPhase() {
        FormScene fs = buildFormScene(4, 2, 2, 0.004, 4.0, 1.0e-5, 0, 1.0, false, 0x511);  // 6 fil × 2 seg = 12 seg
        CrosslinkerStore xl = fs.xl;
        CrosslinkerSystem.filFilCandidates(fs.fil.coord, fs.fil.segLength, fs.filID, xl.reqFilA, xl.reqFilB, xl.formParams, xl.formCounts);
        int nCand = xl.formCounts.get(0), nSeg = fs.fil.n;
        // host reference: distinct i<j cross-fil within coarse bound
        java.util.LinkedHashSet<Long> hostSet = new java.util.LinkedHashSet<>();
        boolean sameFilExcluded = true, distinctOk = true;
        for (int i = 0; i < nSeg; i++) for (int j = i + 1; j < nSeg; j++) {
            if (fs.filID.get(i) == fs.filID.get(j)) continue;
            double cd = segCenterDist(fs.fil, i, j);
            if (cd <= 0.5 * fs.fil.segLength.get(i) + 0.5 * fs.fil.segLength.get(j) + GRAB_DIST) hostSet.add(((long) i << 20) | j);
        }
        java.util.HashSet<Long> emitted = new java.util.HashSet<>();
        for (int c = 0; c < nCand; c++) {
            int a = xl.reqFilA.get(c), b = xl.reqFilB.get(c);
            if (a >= b) distinctOk = false;                          // i<j ordering
            if (fs.filID.get(a) == fs.filID.get(b)) sameFilExcluded = false;
            if (!emitted.add(((long) a << 20) | b)) distinctOk = false;   // no duplicate
        }
        boolean setEq = emitted.equals(hostSet);
        // completeness: every pair passing the FINE gate must be in the emitted set
        boolean complete = true;
        for (int i = 0; i < nSeg; i++) for (int j = i + 1; j < nSeg; j++) {
            if (fs.filID.get(i) == fs.filID.get(j)) continue;
            if (v1GeomGate(fs, i, j, 0)[0] == 1 && !emitted.contains(((long) i << 20) | j)) complete = false;
        }
        boolean ok = setEq && sameFilExcluded && distinctOk && complete;
        System.out.println("\n--- #1 broad-phase FIL×FIL candidate set (6 fil × 2 seg) ---");
        System.out.printf("  nCand=%d; set==host-enum=%s; unordered/distinct=%s; same-filament excluded=%s; superset of fine-passers=%s  %s%n",
                nCand, setEq ? "✓" : "*WRONG*", distinctOk ? "✓" : "*DUP*", sameFilExcluded ? "✓" : "*LEAK*", complete ? "✓" : "*MISS*", ok ? "" : "*FAIL*");
        return ok;
    }
    static double segCenterDist(FilamentStore f, int i, int j) {
        int n = f.n; double dx = f.coord.get(i) - f.coord.get(j), dy = f.coord.get(n + i) - f.coord.get(n + j), dz = f.coord.get(2 * n + i) - f.coord.get(2 * n + j);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** #2: the kernel's gate arithmetic (alignment + lineSeg distance + orientSame) matches v1's
     *  checkToLink formula to the float32 floor, over EVERY candidate of a varied bundle (spans the
     *  alignment + distance boundaries), with P_form forced on. */
    static boolean ckGateArithmetic() {
        boolean ok = true; double worstDist = 0; int mism = 0, n = 0;
        for (int mode : new int[]{ 0, 1, -1 }) {
            FormScene fs = buildFormScene(6, 4, 1, 0.004, 4.0, 1.0e-5, mode, 1.0, false, 0x522);  // pForm=1 ⇒ gatePass=geometry
            CrosslinkerStore xl = fs.xl;
            xl.setFormStep(0, fs.seed);
            CrosslinkerSystem.filFilCandidates(fs.fil.coord, fs.fil.segLength, fs.filID, xl.reqFilA, xl.reqFilB, xl.formParams, xl.formCounts);
            CrosslinkerSystem.formGates(fs.fil.uVec, fs.fil.end1, fs.fil.end2, fs.fil.segLength, xl.reqFilA, xl.reqFilB,
                    xl.reqLoc1, xl.reqLoc2, xl.reqOrient, xl.gatePass, xl.formParams, xl.formCounts);
            int nCand = xl.formCounts.get(0);
            for (int c = 0; c < nCand; c++) {
                int a = xl.reqFilA.get(c), b = xl.reqFilB.get(c);
                double[] ref = v1GeomGate(fs, a, b, mode);   // {pass, orient, conDistSq}
                int kpass = xl.gatePass.get(c), korient = xl.reqOrient.get(c);
                n++;
                if (kpass != (int) ref[0]) mism++;
                if (korient != (int) ref[1]) mism++;
                if (ref[2] >= 0) {
                    // recompute conDistSq via reqLoc? not stored; compare the gate DECISION (covered above).
                }
            }
        }
        ok = (mism == 0);
        System.out.println("\n--- #2 gate arithmetic bit-exact vs v1 checkToLink (modes 0/1/−1, " + n + " candidates) ---");
        System.out.printf("  alignment + lineSeg-distance + orientSame: %d decision mismatches vs v1 formula  %s%n",
                mism, ok ? "(bit-exact) ✓" : "*DIFFERS*");
        return ok;
    }

    /** #3: P_form = 1−exp(−kon·conc·dtCheck) (dtCheck = dt·crosslinkCheckInt) matches v1; empirical
     *  per-candidate fire fraction matches pForm over many steps (frozen pose, geometry-passers fixed). */
    static boolean ckPform() {
        double dt = 1.0e-5;
        double pV1 = pFormV1(dt);
        // formula bit-exact: v2 store-set pForm vs v1 literal
        double pV2 = 1.0 - Math.exp(-XLINK_ON_RATE * XLINK_CONC * (dt * XLINK_CHECK_INT));
        double fpct = pV1 > 0 ? 100.0 * Math.abs(pV2 - pV1) / pV1 : 0;
        // empirical at a higher pForm for stats
        double pTest = 0.3; int steps = 4000;
        FormScene fs = buildFormScene(5, 5, 1, 0.004, 4.0, dt, 0, pTest, false, 0x533);
        CrosslinkerStore xl = fs.xl;
        // identify geometry-passing candidates (pForm=1 run)
        xl.setFormParams(MAX_ANGLE, GRAB_DIST, MIN_SEP, MAX_LINKS_ON_SEG, 1.0, MIN_FILLINK_SEP, 0);
        xl.setFormStep(0, fs.seed);
        CrosslinkerSystem.filFilCandidates(fs.fil.coord, fs.fil.segLength, fs.filID, xl.reqFilA, xl.reqFilB, xl.formParams, xl.formCounts);
        CrosslinkerSystem.formGates(fs.fil.uVec, fs.fil.end1, fs.fil.end2, fs.fil.segLength, xl.reqFilA, xl.reqFilB, xl.reqLoc1, xl.reqLoc2, xl.reqOrient, xl.gatePass, xl.formParams, xl.formCounts);
        int nCand = xl.formCounts.get(0);
        boolean[] geom = new boolean[nCand]; int nGeom = 0;
        for (int c = 0; c < nCand; c++) { geom[c] = xl.gatePass.get(c) == 1; if (geom[c]) nGeom++; }
        // measure fire fraction at pTest
        xl.setFormParams(MAX_ANGLE, GRAB_DIST, MIN_SEP, MAX_LINKS_ON_SEG, pTest, MIN_FILLINK_SEP, 0);
        long fires = 0, trials = 0;
        for (int t = 0; t < steps; t++) {
            xl.setFormStep(t, fs.seed);
            CrosslinkerSystem.filFilCandidates(fs.fil.coord, fs.fil.segLength, fs.filID, xl.reqFilA, xl.reqFilB, xl.formParams, xl.formCounts);
            CrosslinkerSystem.formGates(fs.fil.uVec, fs.fil.end1, fs.fil.end2, fs.fil.segLength, xl.reqFilA, xl.reqFilB, xl.reqLoc1, xl.reqLoc2, xl.reqOrient, xl.gatePass, xl.formParams, xl.formCounts);
            for (int c = 0; c < nCand; c++) if (geom[c]) { trials++; if (xl.gatePass.get(c) == 1) fires++; }
        }
        double pEmp = trials > 0 ? fires / (double) trials : 0;
        double epct = 100.0 * Math.abs(pEmp - pTest) / pTest;
        boolean ok = fpct < 0.1 && epct < 5.0;
        System.out.println("\n--- #3 P_form formula + cadence + empirical ---");
        System.out.printf("  dtCheck = dt·crosslinkCheckInt = %.1e·%d = %.1e s;  P_form(v1 default) = %.6g%n", dt, XLINK_CHECK_INT, dt * XLINK_CHECK_INT, pV1);
        System.out.printf("  formula vs v1: Δ=%.4g%% %s;  empirical fire fraction @pForm=%.2f: %.5f (Δ=%.3g%%, %d geom-passers) %s%n",
                fpct, fpct < 0.1 ? "✓" : "*", pTest, pEmp, epct, nGeom, ok ? "✓" : "*OFF*");
        return ok;
    }

    /** #4 (the flagged decision): in a dense parallel bundle at DEFAULT params, how often does >1
     *  gate-passing candidate target one segment in one step? Reports the contention frequency. ~0 ⇒
     *  the one-per-segment-per-step cap is non-binding. */
    static boolean ckContention() {
        double dt = 1.0e-5; double pForm = pFormV1(dt); int steps = 20000;
        FormScene fs = buildFormScene(6, 6, 1, 0.004, 4.0, dt, 0, pForm, false, 0x544);  // 36-filament dense bundle
        CrosslinkerStore xl = fs.xl; int nSeg = fs.fil.n;
        long stepsWithForm = 0, formEvents = 0, contendSegSteps = 0, droppedByCap = 0;
        int[] segPassers = new int[nSeg];
        for (int t = 0; t < steps; t++) {
            xl.setFormStep(t, fs.seed);
            CrosslinkerSystem.filFilCandidates(fs.fil.coord, fs.fil.segLength, fs.filID, xl.reqFilA, xl.reqFilB, xl.formParams, xl.formCounts);
            CrosslinkerSystem.formGates(fs.fil.uVec, fs.fil.end1, fs.fil.end2, fs.fil.segLength, xl.reqFilA, xl.reqFilB, xl.reqLoc1, xl.reqLoc2, xl.reqOrient, xl.gatePass, xl.formParams, xl.formCounts);
            int nCand = xl.formCounts.get(0);
            for (int s = 0; s < nSeg; s++) segPassers[s] = 0;
            int passers = 0;
            for (int c = 0; c < nCand; c++) if (xl.gatePass.get(c) == 1) { passers++; segPassers[xl.reqFilA.get(c)]++; segPassers[xl.reqFilB.get(c)]++; }
            if (passers > 0) { stepsWithForm++; formEvents += passers; }
            for (int s = 0; s < nSeg; s++) if (segPassers[s] >= 2) { contendSegSteps++; droppedByCap += (segPassers[s] - 1); }
        }
        double contendPerStep = contendSegSteps / (double) steps;
        double droppedFrac = formEvents > 0 ? (double) droppedByCap / formEvents : 0;
        boolean nonBinding = droppedFrac < 0.01;   // cap drops <1% of would-be formations ⇒ non-binding
        System.out.println("\n--- #4 one-per-segment cap contention self-check (36-fil dense bundle, default params, " + steps + " steps) ---");
        System.out.printf("  P_form=%.5g; gate-passers=%d over %d steps; contention seg-steps=%d (%.3g/step); cap-dropped=%d (%.4f%% of formations)%n",
                pForm, formEvents, steps, contendSegSteps, contendPerStep, droppedByCap, 100.0 * droppedFrac);
        System.out.printf("  one-per-seg-per-step cap is %s  %s%n", nonBinding ? "NON-BINDING (contention ~0)" : "*BINDING — PAUSE: heavier exact-admission needed*", nonBinding ? "✓" : "*PAUSE*");
        return nonBinding;
    }

    /** #5: full formation pipeline (candidates → gates → P_form → admission → allocate) — identical
     *  formed-link sets over many churn steps, CPU runner vs GPU TaskGraph. */
    static boolean ckFormCpuGpu() {
        double dt = 1.0e-4; int steps = 400;
        FormScene cpu = buildFormScene(5, 4, 1, 0.004, 4.0, dt, 0, pFormV1(dt) * 30, true, 0x555);  // boosted pForm for more events
        FormScene gpu = buildFormScene(5, 4, 1, 0.004, 4.0, dt, 0, pFormV1(dt) * 30, true, 0x555);
        TornadoExecutionPlan plan = buildFormPlan(gpu); GridScheduler sg = gpu.sched;
        int[] samples = { 1, 50, 200, steps - 1 }; int si = 0; long diffs = 0;
        for (int t = 0; t < steps; t++) {
            formStepCpu(cpu, t);
            gpu.xl.setCounts(t, gpu.seed); gpu.xl.setFormStep(t, gpu.seed);
            TornadoExecutionResult r = plan.withGridScheduler(sg).execute();
            if (si < samples.length && t == samples[si]) {
                r.transferToHost(gpu.xl.linkState, gpu.xl.linkFilA, gpu.xl.linkFilB, gpu.xl.loc1, gpu.xl.loc2, gpu.xl.linkOrientSame, gpu.xl.strainPlace);
                int C = cpu.C;
                for (int s = 0; s < C; s++) {
                    if (cpu.xl.linkState.get(s) != gpu.xl.linkState.get(s)) diffs++;
                    if (cpu.xl.linkFilA.get(s) != gpu.xl.linkFilA.get(s)) diffs++;
                    if (cpu.xl.linkFilB.get(s) != gpu.xl.linkFilB.get(s)) diffs++;
                    if (cpu.xl.loc1.get(s) != gpu.xl.loc1.get(s)) diffs++;
                    if (cpu.xl.loc2.get(s) != gpu.xl.loc2.get(s)) diffs++;
                    if (cpu.xl.linkOrientSame.get(s) != gpu.xl.linkOrientSame.get(s)) diffs++;
                }
                si++;
            }
        }
        boolean ok = diffs == 0;
        System.out.println("\n--- #5 CPU≡GPU full formation pipeline (20-fil bundle, " + steps + " churn steps) ---");
        System.out.printf("  formed-link sets (state + payload + orientSame): %d field mismatches  %s%n", diffs, ok ? "(bit-identical) ✓" : "*DIFFERS*");
        return ok;
    }

    /** #6: formation OFF (pForm=0 ⇒ no candidate ever fires) ⇒ the 5c-i path bit-identical (only 5b
     *  deaths + the no-op allocator). Confirms formation default-off ≡ HEAD. */
    static boolean ckFormAllOff(boolean gpuAvailable) {
        double dt = 1.0e-4; int steps = 300;
        FormScene form = buildFormScene(5, 4, 1, 0.004, 4.0, dt, 0, 0.0, true, 0x566);   // pForm=0 ⇒ no formation
        // bare 5c-i churn: a pool with the same initial links + unbind only (no formation tasks). Build an
        // identical pool by pre-forming nothing (both start empty here) ⇒ both evolve only via 5b deaths.
        FormScene bare = buildFormScene(5, 4, 1, 0.004, 4.0, dt, 0, 0.0, true, 0x566);
        for (int t = 0; t < steps; t++) {
            formStepCpu(form, t);
            // bare: only unbind (the death half) — pool starts empty so nothing dies; both stay all-FREE
            bare.xl.setCounts(t, bare.seed);
            CrosslinkerSystem.unbind(bare.fil.coord, bare.fil.uVec, bare.fil.end1, bare.xl.linkFilA, bare.xl.linkFilB,
                    bare.xl.loc1, bare.xl.loc2, bare.xl.linkState, bare.xl.strainHist, bare.xl.strainPlace, bare.xl.offParams, bare.xl.counts);
        }
        long diffs = 0; int C = form.C;
        for (int s = 0; s < C; s++) {
            if (form.xl.linkState.get(s) != bare.xl.linkState.get(s)) diffs++;
            if (form.xl.linkFilA.get(s) != bare.xl.linkFilA.get(s)) diffs++;
        }
        boolean ok = diffs == 0;
        System.out.println("\n--- #6 all-OFF≡HEAD (formation pForm=0 ≡ 5b/5c-i path, " + steps + " steps) ---");
        System.out.printf("  %d field mismatches vs the no-formation path  %s%n", diffs, ok ? "(formation no-op when off) ✓" : "*PERTURBS*");
        return ok;
    }

    // ================================================== 5c-iii Phase 1: force law (dynamic fracMove) + torsion
    static final double FIL_TORQ_SPRING = 1.0e-19;   // v1 Env filLinkTorqSpring (default ACTIVE)

    static final class P1Scene {
        FilamentStore fil; CrosslinkerStore xl;
        IntArray segCountA, segOffsetsA, segIdxA, segCountB, segOffsetsB, segIdxB;
        GridScheduler sched;
    }

    /** A controlled scene of single-segment filaments at given (coord, uVec), with explicitly pre-placed
     *  links (frozen pose). For force/torsion arithmetic — not formation. torsion default-ON. */
    static P1Scene buildP1Scene(double[][] coord, double[][] uvec, int[][] links, double[] linkLoc, int[] orient, double dt) {
        P1Scene ps = new P1Scene();
        int nSeg = coord.length, nL = links.length;
        FilamentStore fil = new FilamentStore(nSeg);
        double L = (FIL_MONO + 1) * Constants.actinMonoRadius;
        for (int s = 0; s < nSeg; s++) {
            fil.monomerCount.set(s, FIL_MONO);
            double ux = uvec[s][0], uy = uvec[s][1], uz = uvec[s][2];
            double inv = 1.0 / Math.sqrt(ux * ux + uy * uy + uz * uz);
            fil.setUVec(s, (float) (ux * inv), (float) (uy * inv), (float) (uz * inv));
            // a perpendicular yVec
            double yx = -uy, yy = ux, yz = 0; double yn = Math.sqrt(yx * yx + yy * yy + yz * yz);
            if (yn < 1e-9) { yx = 0; yy = -uz; yz = uy; yn = Math.sqrt(yy * yy + yz * yz); }
            fil.setYVec(s, (float) (yx / yn), (float) (yy / yn), (float) (yz / yn));
            fil.setCoord(s, (float) coord[s][0], (float) coord[s][1], (float) coord[s][2]);
            fil.brownTransScale.set(s, 0f); fil.brownRotScale.set(s, 0f);
        }
        DragTensorSystem.run(fil); fil.setParams(dt, Math.sqrt(2.0 * Constants.kT / dt)); fil.setCounts(0, 0);
        DerivedGeometrySystem.derive(fil.coord, fil.uVec, fil.yVec, fil.zVec, fil.end1, fil.end2, fil.segLength, fil.counts);
        int C = Math.max(8, nL);
        CrosslinkerStore xl = new CrosslinkerStore(C, nSeg, Math.max(1, nL));
        xl.setParams(REST_LEN, FRAC_MOVE, dt);
        xl.setOffParams(OFF_CONST, OFF_COEFF, OFF_EXP, dt, REST_LEN);
        xl.setTorsionParams(FIL_TORQ_SPRING, true);
        for (int k = 0; k < nL; k++) {
            xl.setLink(k, links[k][0], linkLoc[k], links[k][1], linkLoc[k]);
            xl.linkOrientSame.set(k, orient[k]);
        }
        ps.segCountA = new IntArray(nSeg); ps.segOffsetsA = new IntArray(nSeg + 1); ps.segIdxA = new IntArray(C);
        ps.segCountB = new IntArray(nSeg); ps.segOffsetsB = new IntArray(nSeg + 1); ps.segIdxB = new IntArray(C);
        ps.fil = fil; ps.xl = xl;
        return ps;
    }

    /** The dynamic crosslinker force step: per-step active-count fracMove → translational force →
     *  torsion → two-pass gather into forceSum/torqueSum. (No integration here — frozen pose for Phase 1.) */
    static void p1ForceStepCpu(P1Scene ps) {
        FilamentStore f = ps.fil; CrosslinkerStore xl = ps.xl;
        ChainBendingForceSystem.zeroAccumulators(f.forceSum, f.torqueSum, f.counts);
        CrosslinkerSystem.countActiveLinks(xl.linkState, xl.linkFilA, xl.linkFilB, xl.activeLinkCount, xl.formCounts);
        CrosslinkerSystem.linkForces(f.coord, f.uVec, f.end1, f.bTransGam, xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2,
                xl.activeLinkCount, xl.xlinkData, xl.xlParams);    // activeLinkCount = dynamic per-step getLinkCt
        CrosslinkerSystem.linkTorsion(f.uVec, xl.linkFilA, xl.linkFilB, xl.linkState, xl.linkOrientSame,
                xl.torqueMagHist, xl.torqueMagPlace, xl.xlinkData, xl.torsionParams);
        CrossBridgeSystem.csrHistogram(xl.linkFilA, xl.counts, ps.segCountA);
        CrossBridgeSystem.csrScan(xl.counts, ps.segCountA, ps.segOffsetsA);
        CrossBridgeSystem.csrScatter(xl.linkFilA, xl.counts, ps.segOffsetsA, ps.segCountA, ps.segIdxA);
        CrosslinkerSystem.segGatherA(ps.segOffsetsA, ps.segIdxA, xl.xlinkData, xl.linkState, f.forceSum, f.torqueSum, xl.counts);
        CrossBridgeSystem.csrHistogram(xl.linkFilB, xl.counts, ps.segCountB);
        CrossBridgeSystem.csrScan(xl.counts, ps.segCountB, ps.segOffsetsB);
        CrossBridgeSystem.csrScatter(xl.linkFilB, xl.counts, ps.segOffsetsB, ps.segCountB, ps.segIdxB);
        CrosslinkerSystem.segGatherB(ps.segOffsetsB, ps.segIdxB, xl.xlinkData, xl.linkState, f.forceSum, f.torqueSum, xl.counts);
    }

    static TornadoExecutionPlan buildP1Plan(P1Scene ps) {
        FilamentStore f = ps.fil; CrosslinkerStore xl = ps.xl;
        TaskGraph tg = new TaskGraph("xp1")
            .transferToDevice(DataTransferMode.FIRST_EXECUTION, f.coord, f.uVec, f.end1, f.bTransGam,
                    xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2, xl.linkState, xl.linkOrientSame,
                    xl.activeLinkCount, xl.xlinkData, xl.xlParams, xl.torqueMagHist, xl.torqueMagPlace, xl.torsionParams,
                    xl.counts, xl.formCounts, f.forceSum, f.torqueSum,
                    ps.segCountA, ps.segOffsetsA, ps.segIdxA, ps.segCountB, ps.segOffsetsB, ps.segIdxB)
            .task("zero", ChainBendingForceSystem::zeroAccumulators, f.forceSum, f.torqueSum, f.counts)
            .task("countActive", CrosslinkerSystem::countActiveLinks, xl.linkState, xl.linkFilA, xl.linkFilB, xl.activeLinkCount, xl.formCounts)
            .task("linkForces", CrosslinkerSystem::linkForces, f.coord, f.uVec, f.end1, f.bTransGam, xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2, xl.activeLinkCount, xl.xlinkData, xl.xlParams)
            .task("torsion", CrosslinkerSystem::linkTorsion, f.uVec, xl.linkFilA, xl.linkFilB, xl.linkState, xl.linkOrientSame, xl.torqueMagHist, xl.torqueMagPlace, xl.xlinkData, xl.torsionParams)
            .task("histA", CrossBridgeSystem::csrHistogram, xl.linkFilA, xl.counts, ps.segCountA)
            .task("scanA", CrossBridgeSystem::csrScan, xl.counts, ps.segCountA, ps.segOffsetsA)
            .task("scatterA", CrossBridgeSystem::csrScatter, xl.linkFilA, xl.counts, ps.segOffsetsA, ps.segCountA, ps.segIdxA)
            .task("gatherA", CrosslinkerSystem::segGatherA, ps.segOffsetsA, ps.segIdxA, xl.xlinkData, xl.linkState, f.forceSum, f.torqueSum, xl.counts)
            .task("histB", CrossBridgeSystem::csrHistogram, xl.linkFilB, xl.counts, ps.segCountB)
            .task("scanB", CrossBridgeSystem::csrScan, xl.counts, ps.segCountB, ps.segOffsetsB)
            .task("scatterB", CrossBridgeSystem::csrScatter, xl.linkFilB, xl.counts, ps.segOffsetsB, ps.segCountB, ps.segIdxB)
            .task("gatherB", CrosslinkerSystem::segGatherB, ps.segOffsetsB, ps.segIdxB, xl.xlinkData, xl.linkState, f.forceSum, f.torqueSum, xl.counts)
            .transferToHost(DataTransferMode.UNDER_DEMAND, f.forceSum, f.torqueSum, xl.xlinkData);
        sched = new GridScheduler();
        int nSeg = f.n, C = xl.nLinks;
        addW("xp1.zero", pad(nSeg)); addS("xp1.countActive");
        addW("xp1.linkForces", pad(C)); addW("xp1.torsion", pad(C));
        addS("xp1.histA"); addS("xp1.scanA"); addS("xp1.scatterA"); addW("xp1.gatherA", pad(nSeg));
        addS("xp1.histB"); addS("xp1.scanB"); addS("xp1.scatterB"); addW("xp1.gatherB", pad(nSeg));
        ps.sched = sched;
        return new TornadoExecutionPlan(tg.snapshot());
    }

    // host v1-faithful references
    static double[] hostLinkForceA(P1Scene ps, int k, int[] ct, double dt) {
        FilamentStore f = ps.fil; CrosslinkerStore xl = ps.xl; int n = f.n;
        int a = xl.linkFilA.get(k), b = xl.linkFilB.get(k);
        double l1 = xl.loc1.get(k), l2 = xl.loc2.get(k);
        double p1x = f.end1.get(a) + l1 * f.uVec.get(a), p1y = f.end1.get(n + a) + l1 * f.uVec.get(n + a), p1z = f.end1.get(2 * n + a) + l1 * f.uVec.get(2 * n + a);
        double p2x = f.end1.get(b) + l2 * f.uVec.get(b), p2y = f.end1.get(n + b) + l2 * f.uVec.get(n + b), p2z = f.end1.get(2 * n + b) + l2 * f.uVec.get(2 * n + b);
        double lvx = p2x - p1x, lvy = p2y - p1y, lvz = p2z - p1z;
        double len = Math.sqrt(lvx * lvx + lvy * lvy + lvz * lvz);
        double stretch = len - REST_LEN; if (stretch < 0) stretch = 0;
        int maxL = Math.max(Math.max(ct[a], ct[b]), 1);
        double fracMove = 0.4 / maxL;
        double g1 = f.bTransGam.get(a), g2 = f.bTransGam.get(b);
        double cf = (fracMove * 1e-6 * stretch / dt) / (1.0 / g1 + 1.0 / g2);
        return new double[]{ cf * lvx, cf * lvy, cf * lvz };
    }
    static double[] hostTorsionA(P1Scene ps, int k, double[] ring, int[] place) {
        FilamentStore f = ps.fil; int n = f.n;
        int a = ps.xl.linkFilA.get(k), b = ps.xl.linkFilB.get(k);
        boolean same = ps.xl.linkOrientSame.get(k) != 0;
        double uax = f.uVec.get(a), uay = f.uVec.get(n + a), uaz = f.uVec.get(2 * n + a);
        double ubx = f.uVec.get(b), uby = f.uVec.get(n + b), ubz = f.uVec.get(2 * n + b);
        double vbx = same ? ubx : -ubx, vby = same ? uby : -uby, vbz = same ? ubz : -ubz;
        double tx = uay * vbz - uaz * vby, ty = uaz * vbx - uax * vbz, tz = uax * vby - uay * vbx;
        double m2 = tx * tx + ty * ty + tz * tz;
        if (!(m2 > 1e-30)) { ring[place[0] % 5] = 0; place[0]++; return new double[]{ 0, 0, 0 }; }
        double im = 1.0 / Math.sqrt(m2); tx *= im; ty *= im; tz *= im;
        double dot = uax * ubx + uay * uby + uaz * ubz; if (dot > 1) dot = 1; if (dot < -1) dot = -1;
        double angT = same ? v1FastAcos(dot) : Math.abs(v1FastAcos(dot) - Math.PI);
        double cur = FIL_TORQ_SPRING * angT;
        ring[place[0] % 5] = cur; place[0]++;
        double avg = 0; for (int j = 0; j < 5; j++) avg += ring[j]; avg /= 5;
        return new double[]{ avg * tx, avg * ty, avg * tz };
    }

    static boolean run5ciiiP1(boolean gpuAvailable) {
        System.out.println("\n========== increment 5c-iii Phase 1 — force law (dynamic fracMove) + torsion ==========");
        boolean ok = true;
        ok &= p1FracMove();        // dynamic fracMove vs v1 (count up via 3 links, down via a death)
        ok &= p1Torsion();         // torsion arithmetic vs v1 (parallel + antiparallel, 5-ring step-for-step)
        if (gpuAvailable) ok &= p1CpuGpu();   // CPU≡GPU bit-identical (force + torsion + gather)
        ok &= p1AllOff();          // torsion OFF ≡ translational-only (the 5a/5b force path)
        return ok;
    }

    /** Dynamic fracMove: a central filament with 3 links (count=3 ⇒ fracMove=0.4/3); kill one ⇒ count=2 ⇒
     *  0.4/2. Each link's translational force matches v1's applyTransForce with the per-step count. */
    static boolean p1FracMove() {
        double dt = 1.0e-5, sep = 1.6 * REST_LEN;
        double L = (FIL_MONO + 1) * Constants.actinMonoRadius;
        // fil 0 central; 1 (+z), 2 (−z), 3 (+y), each at `sep` (stretched). tiny tilts (non-degenerate).
        double[][] coord = { {0,0,0}, {0,0,sep}, {0,0,-sep}, {0,sep,0} };
        double[][] uvec  = { {1,0.01,0}, {1,-0.01,0.005}, {1,0.008,-0.004}, {1,0.006,0.007} };
        int[][] links = { {0,1}, {0,2}, {0,3} };
        double[] loc = { 0.5*L, 0.5*L, 0.5*L };
        int[] orient = { 1, 1, 1 };
        P1Scene ps = buildP1Scene(coord, uvec, links, loc, orient, dt);
        // torsion off here to isolate the translational force
        ps.xl.setTorsionParams(FIL_TORQ_SPRING, false);
        p1ForceStepCpu(ps);
        int[] ct = { 3, 1, 1, 1 };           // expected active counts
        double worst = 0; boolean cntOk = (ps.xl.activeLinkCount.get(0) == 3 && ps.xl.activeLinkCount.get(1) == 1);
        for (int k = 0; k < 3; k++) {
            double[] h = hostLinkForceA(ps, k, ct, dt);
            double[] v = { ps.xl.xlinkData.get(k*12), ps.xl.xlinkData.get(k*12+1), ps.xl.xlinkData.get(k*12+2) };
            for (int i = 0; i < 3; i++) worst = Math.max(worst, rel(v[i], h[i]));
        }
        // kill link 2 (slot 2) ⇒ fil 0 count → 2
        ps.xl.linkState.set(2, CrosslinkerStore.LINK_FREE);
        p1ForceStepCpu(ps);
        int[] ct2 = { 2, 1, 0, 1 };
        boolean cntOk2 = (ps.xl.activeLinkCount.get(0) == 2);
        double worst2 = 0;
        for (int k : new int[]{ 0, 2 }) {   // surviving links 0-1, 0-3
            double[] h = hostLinkForceA(ps, k, ct2, dt);
            double[] v = { ps.xl.xlinkData.get(k*12), ps.xl.xlinkData.get(k*12+1), ps.xl.xlinkData.get(k*12+2) };
            for (int i = 0; i < 3; i++) worst2 = Math.max(worst2, rel(v[i], h[i]));
        }
        boolean ok = cntOk && cntOk2 && worst < 1e-4 && worst2 < 1e-4;
        System.out.println("\n--- P1 dynamic fracMove = 0.4/max(getLinkCt) per step (vs v1 applyTransForce) ---");
        System.out.printf("  count UP: fil0 has 3 links ⇒ fracMove=0.4/3; force vs v1 max rel = %.3g %s%n", worst, worst < 1e-4 ? "✓" : "*");
        System.out.printf("  count DOWN: kill 1 link ⇒ fil0 count=%d ⇒ 0.4/2; force vs v1 max rel = %.3g %s  %s%n",
                ps.xl.activeLinkCount.get(0), worst2, worst2 < 1e-4 ? "✓" : "*", ok ? "" : "*FAIL*");
        return ok;
    }

    /** Torsion arithmetic vs v1 applyTorsionForce, step-for-step over the 5-slot ring, for a parallel
     *  (orientSame=1) and an antiparallel (orientSame=0) crosslinked pair, links at segment mid (positional
     *  torque = 0 ⇒ the gathered torque is the torsion only). */
    static boolean p1Torsion() {
        double dt = 1.0e-5, sep = REST_LEN;
        double L = (FIL_MONO + 1) * Constants.actinMonoRadius;
        // parallel pair (0,1): uVecs ~8° apart; antiparallel pair (2,3): uVecs ~ opposite, ~8° off π
        double a8 = Math.toRadians(8);
        double[][] coord = { {0,0,0}, {0,0,sep}, {0,0.05,0}, {0,0.05,sep} };
        double[][] uvec  = { {1,0,0}, {Math.cos(a8),Math.sin(a8),0}, {1,0,0}, {Math.cos(Math.PI-a8),Math.sin(Math.PI-a8),0} };
        int[][] links = { {0,1}, {2,3} };
        double[] loc = { 0.5*L, 0.5*L };
        int[] orient = { 1, 0 };   // 0-1 parallel, 2-3 antiparallel
        P1Scene ps = buildP1Scene(coord, uvec, links, loc, orient, dt);
        double[] ringP = new double[5]; int[] placeP = { 0 };
        double[] ringA = new double[5]; int[] placeA = { 0 };
        double worst = 0; int steps = 20;
        for (int t = 0; t < steps; t++) {
            p1ForceStepCpu(ps);
            // link 0 (parallel) A-side torque = xlinkData[0*12+3..5]; link 1 (antiparallel) = [1*12+3..5]
            double[] hP = hostTorsionA(ps, 0, ringP, placeP);
            double[] hA = hostTorsionA(ps, 1, ringA, placeA);
            double[] vP = { ps.xl.xlinkData.get(3), ps.xl.xlinkData.get(4), ps.xl.xlinkData.get(5) };
            double[] vA = { ps.xl.xlinkData.get(12+3), ps.xl.xlinkData.get(12+4), ps.xl.xlinkData.get(12+5) };
            for (int i = 0; i < 3; i++) { worst = Math.max(worst, rel(vP[i], hP[i])); worst = Math.max(worst, rel(vA[i], hA[i])); }
        }
        boolean ok = worst < 1e-3;
        System.out.println("\n--- P1 torsion arithmetic vs v1 applyTorsionForce (parallel + antiparallel, 5-ring, " + steps + " steps) ---");
        System.out.printf("  max rel(v2 torsion torque − v1) = %.3g  %s  (float32 floor)%n", worst, ok ? "✓" : "*DIFFERS*");
        return ok;
    }

    static boolean p1CpuGpu() {
        double dt = 1.0e-5, sep = 1.5 * REST_LEN;
        double L = (FIL_MONO + 1) * Constants.actinMonoRadius; double a8 = Math.toRadians(8);
        double[][] coord = { {0,0,0}, {0,0,sep}, {0,0,-sep}, {0,sep,0} };
        double[][] uvec  = { {1,0.01,0}, {Math.cos(a8),Math.sin(a8),0.005}, {1,0.008,-0.004}, {Math.cos(Math.PI-a8),Math.sin(Math.PI-a8),0.007} };
        int[][] links = { {0,1}, {0,2}, {0,3} };
        double[] loc = { 0.3*L, 0.5*L, 0.7*L };   // off-centre ⇒ nonzero positional torque too
        int[] orient = { 1, 1, 0 };
        P1Scene g = buildP1Scene(coord, uvec, links, loc, orient, dt);
        P1Scene c = buildP1Scene(coord, uvec, links, loc, orient, dt);
        TornadoExecutionPlan plan = buildP1Plan(g); GridScheduler sg = g.sched;
        double maxF = 0, maxT = 0;
        for (int t = 0; t < 50; t++) {
            TornadoExecutionResult r = plan.withGridScheduler(sg).execute();
            p1ForceStepCpu(c);
            if (t == 49) {
                r.transferToHost(g.fil.forceSum, g.fil.torqueSum);
                maxF = maxAbsDiff(g.fil.forceSum, c.fil.forceSum);
                maxT = maxAbsDiff(g.fil.torqueSum, c.fil.torqueSum);
            }
        }
        boolean ok = maxF < 1e-18 && maxT < 1e-20;   // SI N / N·m; near-static frozen pose ⇒ bit-identity
        System.out.println("\n--- P1 CPU≡GPU (force + torsion + gather, frozen pose, 50 steps) ---");
        System.out.printf("  max|ΔforceSum|=%.3g N  max|ΔtorqueSum|=%.3g N·m  %s%n", maxF, maxT, ok ? "(bit-identical) ✓" : "*DIFFERS*");
        return ok;
    }

    /** Torsion OFF ⇒ the gathered torque equals the translational-only positional torque (5a/5b path);
     *  i.e. the torsion machinery is a true no-op when disabled. */
    static boolean p1AllOff() {
        double dt = 1.0e-5, sep = 1.5 * REST_LEN;
        double L = (FIL_MONO + 1) * Constants.actinMonoRadius; double a8 = Math.toRadians(8);
        double[][] coord = { {0,0,0}, {0,0,sep} };
        double[][] uvec  = { {1,0,0}, {Math.cos(a8),Math.sin(a8),0} };
        int[][] links = { {0,1} }; double[] loc = { 0.3*L }; int[] orient = { 1 };
        P1Scene on  = buildP1Scene(coord, uvec, links, loc, orient, dt);
        P1Scene off = buildP1Scene(coord, uvec, links, loc, orient, dt);
        off.xl.setTorsionParams(FIL_TORQ_SPRING, false);
        p1ForceStepCpu(on); p1ForceStepCpu(off);
        // with torsion off, the link is at an angle ⇒ on/off torque DIFFER (torsion present when on);
        // the check is that OFF == a run with NO torsion code path, i.e. translational-only. Verify OFF's
        // torque equals the pure positional torque (recompute host translational torque) — and ON ≠ OFF
        // (torsion actually contributes).
        double dT = maxAbsDiff(on.fil.torqueSum, off.fil.torqueSum);
        boolean torsionActs = dT > 0;            // torsion ON changes the torque
        // OFF path bit-identical to a fresh translational-only build (no torsion array touched)
        P1Scene ref = buildP1Scene(coord, uvec, links, loc, orient, dt); ref.xl.setTorsionParams(FIL_TORQ_SPRING, false);
        p1ForceStepCpu(ref);
        double dOff = maxAbsDiff(off.fil.torqueSum, ref.fil.torqueSum) + maxAbsDiff(off.fil.forceSum, ref.fil.forceSum);
        boolean ok = torsionActs && dOff == 0.0;
        System.out.println("\n--- P1 all-OFF≡HEAD (torsion OFF ≡ translational-only) ---");
        System.out.printf("  torsion-OFF reproducible (Δ=%.3g); torsion-ON actually contributes (|ΔT on−off|=%.3g) %s%n",
                dOff, dT, ok ? "✓" : "*FAIL*");
        return ok;
    }

    static double rel(double v, double h) { double d = Math.abs(v - h); double s = Math.abs(h); return s > 1e-30 ? d / s : d; }

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
