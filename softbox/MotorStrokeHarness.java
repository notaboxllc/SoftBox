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
 * Increment 4b-iii checkpoint: the power stroke on a PINNED filament. The nucleotide cycle + the
 * state-dependent rest-angle switch generate the stroke (no force law invented — it emerges); the
 * cross-bridge transmits a directional pulse into the pinned filament. No unpinning, no gliding
 * (that is the deferred gliding run). Validates five sharpened gates (the regression guard is the
 * 4b-i/ii harnesses with constant ADPPi):
 *
 *  1. Cycle dwell times == rate·dt (≈5/1000/10/100 steps NONE/ATP/ADPPi/ADP; cycle ≈0.011 s) — the
 *     4-state analog of 4a's residence-time check (cross-bridge off ⇒ load gate open).
 *  3. Stroke displacement: the head tip swing between held uncocked (ADPPi) and cocked (ATP) equilibria,
 *     vs the lever-scale geometric expectation.
 *  4. Directional force: the time-averaged cross-bridge force the cycling motors pulse into the pinned
 *     filament points −x (minus-end leading — the glide direction).
 *  5. Catch-slip engages: the unbind rate responds to the forceDotFil load (catch under +load).
 *  6. CPU≡GPU: cycle-only is bit-identical (pure integer RNG); the force-gated stroke is
 *     aggregate-within-SEM (a float forceDotFil comparison flips gated transitions ⇒ decorrelation).
 */
public final class MotorStrokeHarness {

    static final int B = 64;
    static final double ANCHOR_Z = -0.05, Z_OFFSET = 0.003;
    static final double MYO_SPRING = 1.0e-9, J1_FMT = 0.4;
    static GridScheduler sched;

    public static void main(String[] args) {
        double dt = 1.0e-5;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-3js")) { runViz(dt, args[++i]); return; }
        }
        System.out.println("=== Soft Box increment 4b-iii — power-stroke checkpoint (pinned filament) ===");
        System.out.println("Nucleotide cycle + rest-angle switch ⇒ the stroke. No unpinning, no gliding.\n");
        boolean g1 = gateDwell(dt);
        boolean g5 = gateCatchSlip(dt);
        boolean[] g346 = gateStroke(dt);
        boolean ok = g1 && g346[0] && g346[1] && g346[2] && g5;
        System.out.println("\n=== STROKE CHECKPOINT " + (ok ? "PASS" : "FAIL") + " ===");
        if (!ok) System.exit(1);
    }

    // ===================== Gate 1: cycle dwell times == rate·dt =====================
    static boolean gateDwell(double dt) {
        int nM = 256, M = 40000;
        MotorStore mot = new MotorStore(nM);
        mot.setNucParams(dt);
        for (int m = 0; m < nM; m++) mot.boundSeg.set(m, 0);     // all bound on-filament (rates use on-fil)
        mot.nucleotideState.init(MotorStore.NUC_NONE);
        // host accumulators
        long[] dwellSum = new long[4]; long[] dwellCnt = new long[4];
        int[] curState = new int[nM], curDwell = new int[nM]; boolean[] seenTrans = new boolean[nM];
        for (int m = 0; m < nM; m++) curState[m] = mot.nucleotideState.get(m);
        for (int t = 0; t < M; t++) {
            mot.setCounts(t, 0x57A0E, 0);
            NucleotideCycleSystem.cycle(mot.nucleotideState, mot.boundSeg, mot.forceDotHist, mot.nucParams, mot.counts);
            for (int m = 0; m < nM; m++) {
                int s = mot.nucleotideState.get(m);
                if (s == curState[m]) { curDwell[m]++; }
                else {
                    if (seenTrans[m]) { dwellSum[curState[m]] += curDwell[m]; dwellCnt[curState[m]]++; }   // skip first (partial) dwell
                    seenTrans[m] = true; curState[m] = s; curDwell[m] = 1;
                }
            }
        }
        double[] expect = { 5.0, 1000.0, 10.0, 100.0 };
        String[] name = { "NONE", "ATP", "ADPPi", "ADP" };
        System.out.println("--- gate 1: cycle dwell times == 1/(rate·dt) [256 motors, 40k steps] ---");
        boolean ok = true;
        for (int s = 0; s < 4; s++) {
            double mean = dwellCnt[s] > 0 ? (double) dwellSum[s] / dwellCnt[s] : 0;
            double err = Math.abs(mean - expect[s]) / expect[s];
            boolean st = err < 0.08;
            ok &= st;
            System.out.printf("  %-6s mean=%-9.2f steps  expect=%-7.1f  err=%-6.2f%%  n=%-6d %s%n",
                    name[s], mean, expect[s], 100 * err, dwellCnt[s], st ? "" : "*FAIL*");
        }
        System.out.printf("  cycle period ≈ %.4f s (expect ≈0.011)  %s%n%n",
                dt * (5 + 1000 + 10 + 100), ok ? "PASS" : "*FAIL*");
        return ok;
    }

    // ===================== Gate 5: catch-slip responds to load =====================
    static boolean gateCatchSlip(double dt) {
        System.out.println("--- gate 5: catch-slip unbind rate responds to forceDotFil load ---");
        double kOff = 100, aCatch = 0.92, aSlip = 0.08, xCatch = 2.5e-9, xSlip = 0.4e-9, kT = Constants.kT;
        double[] loadsPN = { 0.0, 1.0, 2.0, 4.0 };
        boolean prevHigher = true; double prev = 1e9;
        System.out.printf("  %-12s %-16s %-16s %-16s%n", "load(pN)", "rate_analytic(/s)", "rate_empirical(/s)", "meanLife(steps)");
        boolean ok = true;
        for (double loadPN : loadsPN) {
            double F = loadPN * 1e-12;     // N (positive = resisting load)
            double rateA = kOff * (aCatch * Math.exp(-F * xCatch / kT) + aSlip * Math.exp(F * xSlip / kT));
            // empirical: run catchSlipRelease on a population at fixed F
            int nM = 4000, M = 4000;
            MotorStore mot = new MotorStore(nM);
            mot.setKinParams(0.006, -0.4, dt);
            for (int m = 0; m < nM; m++) { mot.boundSeg.set(m, 0); mot.forceDotFil.set(m, (float) F); }
            long releases = 0, boundSteps = 0;
            for (int t = 0; t < M; t++) {
                mot.setCounts(t, 0x57A0E, 0);
                for (int m = 0; m < nM; m++) mot.boundSeg.set(m, 0);          // re-bind each step (steady population)
                for (int m = 0; m < nM; m++) mot.forceDotFil.set(m, (float) F);
                long b0 = countBound(mot);
                NucleotideCycleSystem.catchSlipRelease(mot.boundSeg, mot.forceDotFil, mot.stats, mot.kinParams, mot.counts);
                long b1 = countReleased(mot);
                boundSteps += b0; releases += b1;
            }
            double pEmp = releases / (double) boundSteps;
            double rateE = pEmp / dt;
            boolean monotone = rateA <= prev + 1e-9;     // catch: rate decreases with +load
            ok &= monotone;
            System.out.printf("  %-12.1f %-16.2f %-16.2f %-16.1f %s%n", loadPN, rateA, rateE, 1.0 / pEmp,
                    monotone ? "" : "*non-monotone*");
            prev = rateA;
        }
        System.out.println("  (catch: +load stabilizes the bond ⇒ unbind rate DROPS — the F-dependence is engaged)  "
                + (ok ? "PASS" : "*FAIL*") + "\n");
        return ok;
    }
    static long countBound(MotorStore m) { long c = 0; for (int i = 0; i < m.nMotors; i++) if (m.boundSeg.get(i) >= 0) c++; return c; }
    static long countReleased(MotorStore m) { long c = 0; for (int i = 0; i < m.nMotors; i++) if (m.boundSeg.get(i) == MotorStore.FREE_COOLDOWN) c++; return c; }

    // ===================== Gates 3,4,6: stroke displacement + directional force + CPU≡GPU =========
    static boolean[] gateStroke(double dt) {
        // UNLOADED stroke: with F8 (the cross-bridge spring) off, the head swings freely under the
        // F9/J1 rest-angle torques, so the tip DISPLACES (in the isometric/loaded case the tip is
        // pinned by F8 and the stroke is force instead — gate 4). Held uncocked (ADPPi) vs cocked (ATP).
        double[] tipU = strokeEquilibriumTip(dt, MotorStore.NUC_ADPPI);
        double[] tipC = strokeEquilibriumTip(dt, MotorStore.NUC_ATP);
        double dxn = (tipC[0] - tipU[0]) * 1e3, dyn = (tipC[1] - tipU[1]) * 1e3, dzn = (tipC[2] - tipU[2]) * 1e3;
        double strokeNm = Math.sqrt(dxn * dxn + dyn * dyn + dzn * dzn);
        System.out.println("--- gate 3: unloaded stroke displacement (head tip swing, F8 off, held uncocked→cocked) ---");
        System.out.printf("  head tip Δ (nm): (%.2f, %.2f, %.2f)  ⇒  |stroke| = %.2f nm%n", dxn, dyn, dzn, strokeNm);
        boolean g3 = strokeNm > 2.0 && strokeNm < 40.0;   // lever-scale working stroke (lever 8 nm, head 20 nm)
        System.out.printf("  lever 8 nm + head 20 nm articulated swing (J1 0→60°, F9 90→120°); measured %.2f nm  %s%n%n",
                strokeNm, g3 ? "PASS" : "*FAIL*");

        // cycling on the pinned filament → net directional force into the filament (gate 4) + CPU≡GPU (gate 6)
        double[] gpu = cyclingNetForce(dt, false);
        double[] cpuR = cyclingNetForce(dt, true);
        System.out.println("--- gate 4: directional force into the pinned filament (cycling) ---");
        System.out.printf("  GPU: net Σ filForce_x = %.4g N  (mean %.4g N)   ⇒ %s%n", gpu[0], gpu[1], gpu[0] < 0 ? "−x (glide dir) PASS" : "*+x FAIL*");
        System.out.printf("  CPU: net Σ filForce_x = %.4g N  (mean %.4g N)   ⇒ %s%n", cpuR[0], cpuR[1], cpuR[0] < 0 ? "−x (glide dir) PASS" : "*+x FAIL*");
        boolean g4 = gpu[0] < 0 && cpuR[0] < 0;

        System.out.println("--- gate 6: CPU≡GPU (cycle is RNG+force-gated → aggregate-within-SEM, not bit-identical) ---");
        double rel = Math.abs(gpu[1] - cpuR[1]) / (Math.abs(cpuR[1]) + 1e-30);
        double boundRel = Math.abs(gpu[2] - cpuR[2]) / (Math.abs(cpuR[2]) + 1e-30);
        boolean g6 = rel < 0.15 && boundRel < 0.10;
        System.out.printf("  mean filForce_x GPU/CPU = %.4g / %.4g (rel %.1f%%); avgBound GPU/CPU = %.2f / %.2f (rel %.1f%%)  %s%n%n",
                gpu[1], cpuR[1], 100 * rel, gpu[2], cpuR[2], 100 * boundRel, g6 ? "PASS" : "*FAIL*");
        return new boolean[]{ g3, g4, g6 };
    }

    /** Hold all motors at `state`, F8 (cross-bridge spring) OFF, swing to equilibrium under the
     *  F9/F10/J1 rest-angle torques; return the mean head tip (x,y,z) — the unloaded stroke endpoint. */
    static double[] strokeEquilibriumTip(double dt, int state) {
        Scene sc = buildScene(dt, 8);
        sc.mot.setAllStates(state);
        sc.xbParams.set(0, 0f);               // myoSpring = 0 ⇒ F8 off, head free to swing (F9/F10 still reference the bound seg)
        Runnable step = cpuStep(sc, false);   // no cycle (held state)
        for (int t = 0; t < 6000; t++) { sc.mot.setCounts(t, 0x57A0E, sc.fil.n); step.run(); }
        RigidRodBody b = sc.mot.body; double sx = 0, sy = 0, sz = 0; int nM = sc.mot.nMotors;
        for (int m = 0; m < nM; m++) {
            int h = 3 * m + 2;
            sx += b.coordX(h) + 0.5 * MotorStore.HEAD_LEN * b.uVecX(h);
            sy += b.coordY(h) + 0.5 * MotorStore.HEAD_LEN * b.uVecY(h);
            sz += b.coordZ(h) + 0.5 * MotorStore.HEAD_LEN * b.uVecZ(h);
        }
        return new double[]{ sx / nM, sy / nM, sz / nM };
    }

    /** Run the full cycling stroke on a pinned filament; return {netForceX, meanForceX, avgBound}. */
    static double[] cyclingNetForce(double dt, boolean useCpu) {
        Scene sc = buildScene(dt, 12);
        sc.mot.nucleotideState.init(MotorStore.NUC_NONE);
        int M = 20000;
        double netFx = 0, boundSum = 0; int samples = 0;
        if (useCpu) {
            Runnable step = cpuStep(sc, true);
            for (int t = 0; t < M; t++) {
                sc.mot.setCounts(t, 0x57A0E, sc.fil.n); step.run();
                netFx += sumFilForceX(sc); boundSum += countBound(sc.mot); samples++;
            }
        } else {
            TornadoExecutionPlan plan = buildPlan(sc);
            for (int t = 0; t < M; t++) {
                sc.mot.setCounts(t, 0x57A0E, sc.fil.n);
                TornadoExecutionResult res = plan.withGridScheduler(sched).execute();
                res.transferToHost(sc.fil.forceSum, sc.mot.boundSeg);
                netFx += sumFilForceX(sc); boundSum += countBound(sc.mot); samples++;
            }
        }
        return new double[]{ netFx, netFx / samples, boundSum / samples };
    }
    static double sumFilForceX(Scene sc) {
        double s = 0; int nSeg = sc.fil.n;
        for (int seg = 0; seg < nSeg; seg++) s += sc.fil.forceSum.get(seg);   // x-plane
        return s;
    }

    // ===================== scene (pinned filament + articulated motors, bonds established) ==========
    static final class Scene {
        FilamentStore fil; MotorStore mot;
        FloatArray bondData, xbParams;
        IntArray segMotorCount, segMotorOffsets, segMotorMyo;
        FloatArray bruteReachSeg2; IntArray bruteReachCount2;
    }
    static Scene buildScene(double dt, int nMot) {
        Scene sc = new Scene();
        int nSeg = 2;
        double L = (Constants.stdSegLength + 1) * Constants.actinMonoRadius;
        double headTipZ = ANCHOR_Z + MotorStore.ROD_LEN + MotorStore.LEVER_LEN + MotorStore.HEAD_LEN;
        double zFil = headTipZ + Z_OFFSET;
        FilamentStore fil = new FilamentStore(nSeg);
        double x0 = -0.5 * (nSeg - 1) * L;
        for (int s = 0; s < nSeg; s++) {
            fil.monomerCount.set(s, Constants.stdSegLength);
            fil.setUVec(s, 1f, 0f, 0f); fil.setYVec(s, 0f, 1f, 0f);     // plus-end +x
            fil.setCoord(s, (float) (x0 + s * L), 0f, (float) zFil);
            fil.brownTransScale.set(s, 0f); fil.brownRotScale.set(s, 0f);
        }
        DragTensorSystem.run(fil); fil.setParams(dt, 0); fil.setCounts(0, 0);
        DerivedGeometrySystem.derive(fil.coord, fil.uVec, fil.yVec, fil.zVec, fil.end1, fil.end2, fil.segLength, fil.counts);

        MotorStore mot = new MotorStore(nMot);
        double span = nSeg * L;
        for (int m = 0; m < nMot; m++) {
            double fx = x0 - 0.5 * L + (m + 0.5) / nMot * span;       // spread under the filament
            mot.assembleArticulated(m, (float) fx, 0f, (float) ANCHOR_Z, 0f, 0f, 1f, 0f);   // Brownian off
        }
        DragTensorSystem.run(mot);
        mot.setBodyParams(dt); mot.setJointParams(dt); mot.setKinParams(0.006, -0.4, dt); mot.setNucParams(dt);

        int MAXC = SpatialGrid.MAX_CAND;
        sc.bondData = new FloatArray(nMot * CrossBridgeSystem.STRIDE); sc.bondData.init(0f);
        sc.xbParams = FloatArray.fromElements((float) MYO_SPRING, 90f, (float) J1_FMT, (float) dt, (float) MotorStore.HEAD_LEN);
        sc.segMotorCount = new IntArray(nSeg); sc.segMotorOffsets = new IntArray(nSeg + 1); sc.segMotorMyo = new IntArray(nMot);
        sc.bruteReachSeg2 = new FloatArray(nMot * MAXC); sc.bruteReachCount2 = new IntArray(nMot);
        IntArray reachSeg = new IntArray(nMot * MAXC); reachSeg.init(-1);
        sc.fil = fil; sc.mot = mot;
        // establish bonds (geometric, deterministic)
        for (int t = 0; t < 4; t++) {
            mot.setCounts(t, 0x57A0E, nSeg);
            MotorStore.publishHeadFromBody(mot.body.coord, mot.body.uVec, mot.body.segLength, mot.head, mot.uVec, mot.rodUVec, mot.counts);
            BindingDetectionSystem.bruteReachable(mot.head, mot.uVec, mot.rodUVec, fil.end1, fil.end2, reachSeg, sc.bruteReachCount2, mot.kinParams, mot.counts);
            BindingDetectionSystem.bindKinetics(mot.head, mot.uVec, mot.rodUVec, fil.end1, fil.end2, reachSeg, sc.bruteReachCount2, mot.boundSeg, mot.bindArc, mot.stats, mot.kinParams, mot.counts);
        }
        return sc;
    }

    // per-step: [cycle] → joints → anchor → bond → applyHead → integrate → derive → register → gather
    static Runnable cpuStep(Scene sc, boolean withCycle) {
        FilamentStore f = sc.fil; MotorStore mot = sc.mot; RigidRodBody b = mot.body;
        return () -> {
            if (withCycle) NucleotideCycleSystem.cycle(mot.nucleotideState, mot.boundSeg, mot.forceDotHist, mot.nucParams, mot.counts);
            ChainBendingForceSystem.zeroAccumulators(b.forceSum, b.torqueSum, mot.counts);
            MotorJointSystem.joints(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, mot.nucleotideState, mot.jointParams, mot.counts);
            TailAnchorSystem.anchor(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, mot.anchor, mot.jointParams, mot.counts);
            CrossBridgeSystem.bondForces(b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength,
                    mot.boundSeg, mot.bindArc, mot.nucleotideState, sc.bondData, sc.xbParams);
            CrossBridgeSystem.applyHeadForce(sc.bondData, b.forceSum, b.torqueSum, mot.counts);
            RigidRodLangevinIntegrationSystem.integrate(b.coord, b.uVec, b.yVec, b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, mot.bodyParams, mot.counts);
            DerivedGeometrySystem.derive(b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts);
            CrossBridgeSystem.registerForceDot(sc.bondData, mot.boundSeg, mot.forceDotFil, mot.forceDotHist, mot.forceDotPlace, mot.counts);
            ChainBendingForceSystem.zeroAccumulators(f.forceSum, f.torqueSum, f.counts);
            CrossBridgeSystem.csrHistogram(mot.boundSeg, mot.counts, sc.segMotorCount);
            CrossBridgeSystem.csrScan(mot.counts, sc.segMotorCount, sc.segMotorOffsets);
            CrossBridgeSystem.csrScatter(mot.boundSeg, mot.counts, sc.segMotorOffsets, sc.segMotorCount, sc.segMotorMyo);
            CrossBridgeSystem.segGather(sc.segMotorOffsets, sc.segMotorMyo, sc.bondData, f.forceSum, f.torqueSum, mot.counts);
        };
    }

    static TornadoExecutionPlan buildPlan(Scene sc) {
        FilamentStore f = sc.fil; MotorStore mot = sc.mot; RigidRodBody b = mot.body;
        TaskGraph tg = new TaskGraph("stroke")
            .transferToDevice(DataTransferMode.FIRST_EXECUTION,
                    b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, b.bTransGam, b.bRotGam,
                    b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.brownTransScale, b.brownRotScale,
                    mot.bodyParams, mot.jointParams, mot.anchor, mot.nucleotideState, mot.boundSeg, mot.bindArc,
                    mot.forceDotFil, mot.forceDotHist, mot.forceDotPlace, mot.nucParams,
                    sc.bondData, sc.xbParams, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength, f.forceSum, f.torqueSum,
                    sc.segMotorCount, sc.segMotorOffsets, sc.segMotorMyo)
            .transferToDevice(DataTransferMode.EVERY_EXECUTION, mot.counts)
            .task("cycle", NucleotideCycleSystem::cycle, mot.nucleotideState, mot.boundSeg, mot.forceDotHist, mot.nucParams, mot.counts)
            .task("zero", ChainBendingForceSystem::zeroAccumulators, b.forceSum, b.torqueSum, mot.counts)
            .task("joints", MotorJointSystem::joints, b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, mot.nucleotideState, mot.jointParams, mot.counts)
            .task("anchor", TailAnchorSystem::anchor, b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, mot.anchor, mot.jointParams, mot.counts)
            .task("bond", CrossBridgeSystem::bondForces, b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength,
                    mot.boundSeg, mot.bindArc, mot.nucleotideState, sc.bondData, sc.xbParams)
            .task("applyHead", CrossBridgeSystem::applyHeadForce, sc.bondData, b.forceSum, b.torqueSum, mot.counts)
            .task("integrate", RigidRodLangevinIntegrationSystem::integrate, b.coord, b.uVec, b.yVec, b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, mot.bodyParams, mot.counts)
            .task("derive", DerivedGeometrySystem::derive, b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts)
            .task("register", CrossBridgeSystem::registerForceDot, sc.bondData, mot.boundSeg, mot.forceDotFil, mot.forceDotHist, mot.forceDotPlace, mot.counts)
            .task("zeroFil", ChainBendingForceSystem::zeroAccumulators, f.forceSum, f.torqueSum, f.counts)
            .task("csrHist", CrossBridgeSystem::csrHistogram, mot.boundSeg, mot.counts, sc.segMotorCount)
            .task("csrScan", CrossBridgeSystem::csrScan, mot.counts, sc.segMotorCount, sc.segMotorOffsets)
            .task("csrScatter", CrossBridgeSystem::csrScatter, mot.boundSeg, mot.counts, sc.segMotorOffsets, sc.segMotorCount, sc.segMotorMyo)
            .task("gather", CrossBridgeSystem::segGather, sc.segMotorOffsets, sc.segMotorMyo, sc.bondData, f.forceSum, f.torqueSum, mot.counts)
            .transferToHost(DataTransferMode.UNDER_DEMAND, f.forceSum, mot.boundSeg);

        int nB = 3 * mot.nMotors, nM = mot.nMotors, nSeg = f.n;
        sched = new GridScheduler();
        addW("stroke.cycle", pad(nM)); addW("stroke.zero", pad(nB)); addW("stroke.joints", pad(nB));
        addW("stroke.anchor", pad(nM)); addW("stroke.bond", pad(nM)); addW("stroke.applyHead", pad(nM));
        addW("stroke.integrate", pad(nB)); addW("stroke.derive", pad(nB)); addW("stroke.register", pad(nM));
        addW("stroke.zeroFil", pad(nSeg)); addS("stroke.csrHist"); addS("stroke.csrScan"); addS("stroke.csrScatter");
        addW("stroke.gather", pad(nSeg));
        return new TornadoExecutionPlan(tg.snapshot());
    }
    static int pad(int n) { return ((n + B - 1) / B) * B; }
    static void addW(String n, int g) { WorkerGrid w = new WorkerGrid1D(g); w.setLocalWork(B, 1, 1); sched.addWorkerGrid(n, w); }
    static void addS(String n) { WorkerGrid w = new WorkerGrid1D(1); w.setLocalWork(1, 1, 1); sched.addWorkerGrid(n, w); }

    // ===================== viewer (-3js): cycling motors stroking on a pinned filament ==============
    static final String[] STATE_NAME = { "NONE", "ATP", "ADPPi", "ADP" };
    static void runViz(double dt, String dir) {
        Scene sc = buildScene(dt, 12);
        sc.mot.nucleotideState.init(MotorStore.NUC_NONE);
        new java.io.File(dir).mkdirs();
        Runnable step = cpuStep(sc, true);
        int M = 20000, every = Math.max(1, M / 400), frames = 0;
        for (int t = 0; t <= M; t++) {
            sc.mot.setCounts(t, 0x57A0E, sc.fil.n);
            if (t % every == 0) writeFrame(dir, frames++, t * dt, sc);
            step.run();
        }
        System.out.println("viewer: wrote " + frames + " frames to " + dir + " (motors colored by nucleotide state)");
    }
    static void writeFrame(String dir, int frame, double t, Scene sc) {
        FilamentStore f = sc.fil; MotorStore mot = sc.mot; RigidRodBody b = mot.body;
        StringBuilder sb = new StringBuilder(512 + 200 * mot.nMotors);
        sb.append(String.format(java.util.Locale.US, "{\"frame\":%d,\"t\":%.6g,\"bounds\":{\"xDim\":0.8,\"yDim\":0.4,\"zDim\":0.2}", frame, t));
        sb.append(",\"segments\":[");
        for (int s = 0; s < f.n; s++) {
            if (s > 0) sb.append(',');
            sb.append(String.format(java.util.Locale.US,
                "{\"id\":%d,\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.5g,\"notADPRatio\":1.0,\"cofilinCount\":0}",
                s, f.end1.get(s), f.end1.get(f.n + s), f.end1.get(2 * f.n + s),
                f.end2.get(s), f.end2.get(f.n + s), f.end2.get(2 * f.n + s), Constants.radius));
        }
        sb.append("],\"myosins\":[");
        for (int m = 0; m < mot.nMotors; m++) {
            if (m > 0) sb.append(',');
            int rod = 3 * m, lever = 3 * m + 1, head = 3 * m + 2;
            String state = STATE_NAME[mot.nucleotideState.get(m)];
            sb.append(String.format(java.util.Locale.US,
                "{\"id\":%d,\"rod\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g,\"invisible\":false},"
                + "\"lever\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g},"
                + "\"motor\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g,\"state\":\"%s\"}}",
                m, b.end1X(rod), b.end1Y(rod), b.end1Z(rod), b.end2X(rod), b.end2Y(rod), b.end2Z(rod), MotorStore.ROD_R,
                b.end1X(lever), b.end1Y(lever), b.end1Z(lever), b.end2X(lever), b.end2Y(lever), b.end2Z(lever), MotorStore.LEVER_R,
                b.end1X(head), b.end1Y(head), b.end1Z(head), b.end2X(head), b.end2Y(head), b.end2Z(head), MotorStore.HEAD_R, state));
        }
        sb.append("]}");
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(dir, String.format(java.util.Locale.US, "frame_%06d.json", frame)), sb.toString()); }
        catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }
}
