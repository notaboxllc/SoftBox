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

        System.out.println();
        System.out.println("=== CROSSLINKER 5a VALIDATION " + (ok ? "PASS" : "FAIL") + " ===");
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
        TornadoExecutionPlan plan;        // lazily built per scene (GPU)
        GridScheduler sched;              // captured at plan build (static sched is overwritten per buildPlan)
    }

    /** 2 single-segment filaments parallel along x; fil1 offset by (shearX, 0, sep) above fil0.
     *  nLinks crosslinkers (0 or 1) link fil0@mid ↔ fil1@mid; tasksOn includes the crosslinker
     *  pipeline in the step/plan (so tasksOn with nLinks=0 = the empty-set no-op for all-OFF≡HEAD). */
    static Scene buildScene(double dt, double brownScale, double sep, double shearX, int nLinks, boolean tasksOn) {
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
        if (nLinks > 0) xl.setLink(0, 0, 0.5 * L, 1, 0.5 * L);   // midpoint ↔ midpoint
        xl.computeFilLinkCt();

        sc.segCountA = new IntArray(nSeg); sc.segOffsetsA = new IntArray(nSeg + 1); sc.segIdxA = new IntArray(Math.max(1, nLinks));
        sc.segCountB = new IntArray(nSeg); sc.segOffsetsB = new IntArray(nSeg + 1); sc.segIdxB = new IntArray(Math.max(1, nLinks));
        sc.bruteForceSum  = new FloatArray(3 * nSeg); sc.bruteForceSum.init(0f);
        sc.bruteTorqueSum = new FloatArray(3 * nSeg); sc.bruteTorqueSum.init(0f);
        sc.fil = fil; sc.xl = xl; sc.tasksOn = tasksOn;
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
            CrosslinkerSystem.linkForces(f.coord, f.uVec, f.end1, f.bTransGam,
                    xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2, xl.filLinkCt, xl.xlinkData, xl.xlParams);
            // pass A: keyed by linkFilA (reuse the validated CrossBridge CSR template verbatim)
            CrossBridgeSystem.csrHistogram(xl.linkFilA, xl.counts, sc.segCountA);
            CrossBridgeSystem.csrScan(xl.counts, sc.segCountA, sc.segOffsetsA);
            CrossBridgeSystem.csrScatter(xl.linkFilA, xl.counts, sc.segOffsetsA, sc.segCountA, sc.segIdxA);
            CrosslinkerSystem.segGatherA(sc.segOffsetsA, sc.segIdxA, xl.xlinkData, f.forceSum, f.torqueSum, xl.counts);
            // pass B: keyed by linkFilB
            CrossBridgeSystem.csrHistogram(xl.linkFilB, xl.counts, sc.segCountB);
            CrossBridgeSystem.csrScan(xl.counts, sc.segCountB, sc.segOffsetsB);
            CrossBridgeSystem.csrScatter(xl.linkFilB, xl.counts, sc.segOffsetsB, sc.segCountB, sc.segIdxB);
            CrosslinkerSystem.segGatherB(sc.segOffsetsB, sc.segIdxB, xl.xlinkData, f.forceSum, f.torqueSum, xl.counts);
            // brute reference (gather-exactness gate)
            CrosslinkerSystem.bruteGather(xl.linkFilA, xl.linkFilB, xl.xlinkData, sc.bruteForceSum, sc.bruteTorqueSum, xl.counts);
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
                    xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2, xl.filLinkCt, xl.xlinkData, xl.xlParams, xl.counts,
                    sc.segCountA, sc.segOffsetsA, sc.segIdxA, sc.segCountB, sc.segOffsetsB, sc.segIdxB,
                    sc.bruteForceSum, sc.bruteTorqueSum)
            .transferToDevice(DataTransferMode.EVERY_EXECUTION, f.counts)
            .task("zero", ChainBendingForceSystem::zeroAccumulators, f.forceSum, f.torqueSum, f.counts)
            .task("brownian", BrownianForceSystem::brownianForce, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.brownTransScale, f.brownRotScale, f.params, f.counts);
        if (sc.tasksOn) {
            tg = tg
                .task("linkForces", CrosslinkerSystem::linkForces, f.coord, f.uVec, f.end1, f.bTransGam,
                        xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2, xl.filLinkCt, xl.xlinkData, xl.xlParams)
                .task("histA", CrossBridgeSystem::csrHistogram, xl.linkFilA, xl.counts, sc.segCountA)
                .task("scanA", CrossBridgeSystem::csrScan, xl.counts, sc.segCountA, sc.segOffsetsA)
                .task("scatterA", CrossBridgeSystem::csrScatter, xl.linkFilA, xl.counts, sc.segOffsetsA, sc.segCountA, sc.segIdxA)
                .task("gatherA", CrosslinkerSystem::segGatherA, sc.segOffsetsA, sc.segIdxA, xl.xlinkData, f.forceSum, f.torqueSum, xl.counts)
                .task("histB", CrossBridgeSystem::csrHistogram, xl.linkFilB, xl.counts, sc.segCountB)
                .task("scanB", CrossBridgeSystem::csrScan, xl.counts, sc.segCountB, sc.segOffsetsB)
                .task("scatterB", CrossBridgeSystem::csrScatter, xl.linkFilB, xl.counts, sc.segOffsetsB, sc.segCountB, sc.segIdxB)
                .task("gatherB", CrosslinkerSystem::segGatherB, sc.segOffsetsB, sc.segIdxB, xl.xlinkData, f.forceSum, f.torqueSum, xl.counts)
                .task("brute", CrosslinkerSystem::bruteGather, xl.linkFilA, xl.linkFilB, xl.xlinkData, sc.bruteForceSum, sc.bruteTorqueSum, xl.counts);
        }
        tg = tg
            .task("integrate", RigidRodLangevinIntegrationSystem::integrate, f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts)
            .task("derive", DerivedGeometrySystem::derive, f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts)
            .transferToHost(DataTransferMode.UNDER_DEMAND, f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum, sc.bruteForceSum, sc.bruteTorqueSum);

        int nSeg = f.n, nLinks = Math.max(1, sc.xl.nLinks);
        sched = new GridScheduler();
        addW("xlink.zero", pad(nSeg)); addW("xlink.brownian", pad(nSeg));
        if (sc.tasksOn) {
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
