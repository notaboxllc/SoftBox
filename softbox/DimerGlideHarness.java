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
 * Increment 6 glide integration (part 1): DIMER-GLIDE — the dimer as a FUNCTIONAL two-head motor.
 * Reuses the 4b-iii pinned-filament setup (CrossBridge binding + nucleotide cycle + stroke) but with
 * DIMERS (pairs of motors held by the 6a DimerCoupling) instead of anchored single motors, and the
 * dimer body FREE (no TailAnchor) so it translocates under the collective head force.
 *
 * What's genuinely new vs 6a/6b (which had FREE heads, the binding gate always-true):
 *   1. Heads bind/walk on actin via CrossBridge (unchanged — head self-write + seg gather, 4b-ii).
 *   2. BINDING-DEPENDENT coupling gate: the dimer lever-align (DimerCouplingSystem, now boundSeg-gated)
 *      is SUPPRESSED when BOTH heads are bound (v1 MyosinDimer.java:276: !onFil1 | !onFil2), else fires.
 *   3. The full force-transmission chain: a bound head's cross-bridge reaction → head body → J1 → lever
 *      → J2 → rod → dimer rod-coupling → the partner rod, end-to-end (only validated piecewise before).
 *
 * Per step (free dimer, gated coupling): [cycle] → zero → joints(J1/J2) → dimerCouple(boundSeg-gated)
 *   → bond(CrossBridge) → applyHead → integrate → derive → register → fil-gather. NO anchor.
 *
 * Gates: #1 force transmission (fil gather==brute bit-identical, momentum, CPU≡GPU); #2 binding gate
 * bit-for-decision vs v1 (both-free / one-bound / both-bound); #3 two-head translocation (−x glide,
 * emergent, vs a single-motor baseline); #4 CPU≡GPU; #5 all-OFF≡HEAD (dimer off ≡ single-motor path).
 */
public final class DimerGlideHarness {

    static final int B = 64;
    static final double ANCHOR_Z = -0.05, Z_OFFSET = 0.003;
    static final double MYO_SPRING = 1.0e-9, J1_FMT = 0.4;
    static GridScheduler sched;
    static boolean cpu = false;

    public static void main(String[] args) {
        double dt = 1.0e-5;
        String vizDir = null;
        String assayDir = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-cpu" -> cpu = true;
                case "-3js" -> vizDir = args[++i];
                case "-assay" -> assayDir = args[++i];
                default -> {}
            }
        }
        System.out.println("=== Soft Box increment 6 glide (part 1) — DIMER-GLIDE (two-head motor on a pinned filament) ===");
        System.out.println("CrossBridge binding + binding-gated dimer coupling + force transmission; free dimer translocates.\n");
        if (assayDir != null) { runAssayViz(dt, assayDir); return; }   // gliding assay: FIXED dimers, FREE filament
        if (vizDir != null) { runViz(dt, vizDir); return; }

        boolean g2 = checkBindingGate(dt);
        boolean g5 = checkAllOffEqualsHead(dt);
        boolean g1 = checkForceTransmission(dt);
        boolean g3 = checkTranslocation(dt);
        boolean ok = g1 && g2 && g3 && g5;
        System.out.println("\n=== DIMER-GLIDE VALIDATION " + (ok ? "PASS" : "FAIL") + " ===");
        if (!ok) { System.out.println("BAIL-OUT: a gate failed. Commit nothing."); System.exit(1); }
    }

    // ============================================================== scene
    static final class Scene {
        FilamentStore fil; MotorStore mot; DimerStore dim;
        FloatArray bondData, xbParams;
        IntArray segMotorCount, segMotorOffsets, segMotorMyo;
        FloatArray bruteFilF, bruteFilT;
        IntArray reachSeg, reachCount;       // dynamic-binding scratch (gliding assay)
        boolean anchored;
    }

    /** Pinned filament + nDimers dimers (2 motors each). anchored=false ⇒ free dimers (glide);
     *  anchored=true ⇒ bed-anchored single motors (the 4b-iii / single-motor path, for gate #5). */
    static Scene buildScene(double dt, int nDimers, boolean anchored, boolean establishBonds) {
        Scene sc = new Scene(); sc.anchored = anchored;
        int nMot = 2 * nDimers, nSeg = 2;
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
        DimerStore dim = new DimerStore(nDimers);
        double span = nSeg * L;
        // place each dimer's two motors close together (so the rod-couplings start near rest) under the filament
        for (int d = 0; d < nDimers; d++) {
            double fx = x0 - 0.5 * L + (d + 0.5) / nDimers * span;
            mot.assembleArticulated(2 * d,     (float) fx,           0f, (float) ANCHOR_Z, 0f, 0f, 1f, 0f);
            mot.assembleArticulated(2 * d + 1, (float) (fx + 0.002), 0f, (float) ANCHOR_Z, 0f, 0f, 1f, 0f);
            dim.pair(d, 2 * d, 2 * d + 1, true);
        }
        DragTensorSystem.run(mot);
        mot.setBodyParams(dt); mot.setJointParams(dt); mot.setKinParams(0.006, -0.4, dt); mot.setNucParams(dt);
        dim.setDimerParams(dt);

        int MAXC = SpatialGrid.MAX_CAND;
        sc.bondData = new FloatArray(nMot * CrossBridgeSystem.STRIDE); sc.bondData.init(0f);
        sc.xbParams = FloatArray.fromElements((float) MYO_SPRING, 90f, (float) J1_FMT, (float) dt, (float) MotorStore.HEAD_LEN, 0f);
        sc.segMotorCount = new IntArray(nSeg); sc.segMotorOffsets = new IntArray(nSeg + 1); sc.segMotorMyo = new IntArray(nMot);
        sc.bruteFilF = new FloatArray(3 * nSeg); sc.bruteFilT = new FloatArray(3 * nSeg);
        sc.fil = fil; sc.mot = mot; sc.dim = dim;
        if (establishBonds) bindStep(sc, 4);
        return sc;
    }

    static IntArray reachScratch(Scene sc) {
        IntArray reachSeg = new IntArray(sc.mot.nMotors * SpatialGrid.MAX_CAND); reachSeg.init(-1); return reachSeg;
    }
    /** Establish/refresh bonds geometrically (deterministic) for `steps` iterations. */
    static void bindStep(Scene sc, int steps) {
        MotorStore mot = sc.mot; FilamentStore fil = sc.fil;
        IntArray reachSeg = reachScratch(sc); IntArray reachCount = new IntArray(mot.nMotors);
        for (int t = 0; t < steps; t++) {
            mot.setCounts(t, 0x6D9A0E, fil.n);
            MotorStore.publishHeadFromBody(mot.body.coord, mot.body.uVec, mot.body.segLength, mot.head, mot.uVec, mot.rodUVec, mot.counts);
            BindingDetectionSystem.bruteReachable(mot.head, mot.uVec, mot.rodUVec, fil.end1, fil.end2, reachSeg, reachCount, mot.kinParams, mot.counts);
            BindingDetectionSystem.bindKinetics(mot.head, mot.uVec, mot.rodUVec, fil.end1, fil.end2, reachSeg, reachCount, mot.boundSeg, mot.bindArc, mot.stats, mot.kinParams, mot.counts);
        }
    }

    // ============================================================== per-step
    static Runnable cpuStep(Scene sc, boolean withCycle, boolean dimerOn) {
        FilamentStore f = sc.fil; MotorStore mot = sc.mot; DimerStore dim = sc.dim; RigidRodBody b = mot.body;
        return () -> {
            if (withCycle) NucleotideCycleSystem.cycle(mot.nucleotideState, mot.boundSeg, mot.forceDotHist, mot.nucParams, mot.counts);
            ChainBendingForceSystem.zeroAccumulators(b.forceSum, b.torqueSum, mot.counts);
            MotorJointSystem.joints(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, mot.nucleotideState, mot.jointParams, mot.counts);
            if (sc.anchored) TailAnchorSystem.anchor(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, mot.anchor, mot.jointParams, mot.counts);
            if (dimerOn) DimerCouplingSystem.couple(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam,
                    b.forceSum, b.torqueSum, dim.motorA, dim.motorB, dim.parallel, dim.dimerParams, mot.boundSeg);
            CrossBridgeSystem.bondForces(b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength,
                    mot.boundSeg, mot.bindArc, mot.nucleotideState, sc.bondData, sc.xbParams);
            CrossBridgeSystem.applyHeadForce(sc.bondData, b.forceSum, b.torqueSum, mot.counts);
            RigidRodLangevinIntegrationSystem.integrate(b.coord, b.uVec, b.yVec, b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, mot.bodyParams, mot.counts);
            DerivedGeometrySystem.derive(b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts);
            CrossBridgeSystem.registerForceDot(sc.bondData, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.counts);
            ChainBendingForceSystem.zeroAccumulators(f.forceSum, f.torqueSum, f.counts);
            CrossBridgeSystem.csrHistogram(mot.boundSeg, mot.counts, sc.segMotorCount);
            CrossBridgeSystem.csrScan(mot.counts, sc.segMotorCount, sc.segMotorOffsets);
            CrossBridgeSystem.csrScatter(mot.boundSeg, mot.counts, sc.segMotorOffsets, sc.segMotorCount, sc.segMotorMyo);
            CrossBridgeSystem.segGather(sc.segMotorOffsets, sc.segMotorMyo, sc.bondData, f.forceSum, f.torqueSum, mot.counts);
        };
    }

    static TornadoExecutionPlan buildPlan(Scene sc, boolean withCycle, boolean dimerOn) {
        FilamentStore f = sc.fil; MotorStore mot = sc.mot; DimerStore dim = sc.dim; RigidRodBody b = mot.body;
        TaskGraph tg = new TaskGraph("dglide")
            .transferToDevice(DataTransferMode.FIRST_EXECUTION,
                    b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, b.bTransGam, b.bRotGam,
                    b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.brownTransScale, b.brownRotScale,
                    mot.bodyParams, mot.jointParams, mot.anchor, mot.nucleotideState, mot.boundSeg, mot.bindArc,
                    mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.nucParams,
                    dim.motorA, dim.motorB, dim.parallel, dim.dimerParams,
                    sc.bondData, sc.xbParams, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength, f.forceSum, f.torqueSum,
                    sc.segMotorCount, sc.segMotorOffsets, sc.segMotorMyo)
            .transferToDevice(DataTransferMode.EVERY_EXECUTION, mot.counts);
        if (withCycle) tg.task("cycle", NucleotideCycleSystem::cycle, mot.nucleotideState, mot.boundSeg, mot.forceDotHist, mot.nucParams, mot.counts);
        tg.task("zero", ChainBendingForceSystem::zeroAccumulators, b.forceSum, b.torqueSum, mot.counts)
          .task("joints", MotorJointSystem::joints, b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, mot.nucleotideState, mot.jointParams, mot.counts);
        if (sc.anchored) tg.task("anchor", TailAnchorSystem::anchor, b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, mot.anchor, mot.jointParams, mot.counts);
        if (dimerOn) tg.task("dimer", DimerCouplingSystem::couple, b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam,
                b.forceSum, b.torqueSum, dim.motorA, dim.motorB, dim.parallel, dim.dimerParams, mot.boundSeg);
        tg.task("bond", CrossBridgeSystem::bondForces, b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength,
                    mot.boundSeg, mot.bindArc, mot.nucleotideState, sc.bondData, sc.xbParams)
          .task("applyHead", CrossBridgeSystem::applyHeadForce, sc.bondData, b.forceSum, b.torqueSum, mot.counts)
          .task("integrate", RigidRodLangevinIntegrationSystem::integrate, b.coord, b.uVec, b.yVec, b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, mot.bodyParams, mot.counts)
          .task("derive", DerivedGeometrySystem::derive, b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts)
          .task("register", CrossBridgeSystem::registerForceDot, sc.bondData, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.counts)
          .task("zeroFil", ChainBendingForceSystem::zeroAccumulators, f.forceSum, f.torqueSum, f.counts)
          .task("csrHist", CrossBridgeSystem::csrHistogram, mot.boundSeg, mot.counts, sc.segMotorCount)
          .task("csrScan", CrossBridgeSystem::csrScan, mot.counts, sc.segMotorCount, sc.segMotorOffsets)
          .task("csrScatter", CrossBridgeSystem::csrScatter, mot.boundSeg, mot.counts, sc.segMotorOffsets, sc.segMotorCount, sc.segMotorMyo)
          .task("gather", CrossBridgeSystem::segGather, sc.segMotorOffsets, sc.segMotorMyo, sc.bondData, f.forceSum, f.torqueSum, mot.counts)
          .transferToHost(DataTransferMode.UNDER_DEMAND, b.coord, b.uVec, f.forceSum, mot.boundSeg);

        int nB = 3 * mot.nMotors, nM = mot.nMotors, nSeg = f.n;
        sched = new GridScheduler();
        if (withCycle) addW("dglide.cycle", pad(nM));
        addW("dglide.zero", pad(nB)); addW("dglide.joints", pad(nB));
        if (sc.anchored) addW("dglide.anchor", pad(nM));
        if (dimerOn) addW("dglide.dimer", pad(sc.dim.nDimers));
        addW("dglide.bond", pad(nM)); addW("dglide.applyHead", pad(nM));
        addW("dglide.integrate", pad(nB)); addW("dglide.derive", pad(nB)); addW("dglide.register", pad(nM));
        addW("dglide.zeroFil", pad(nSeg)); addS("dglide.csrHist"); addS("dglide.csrScan"); addS("dglide.csrScatter");
        addW("dglide.gather", pad(nSeg));
        return new TornadoExecutionPlan(tg.snapshot());
    }
    static int pad(int n) { return ((n + B - 1) / B) * B; }
    static void addW(String n, int g) { WorkerGrid w = new WorkerGrid1D(g); w.setLocalWork(B, 1, 1); sched.addWorkerGrid(n, w); }
    static void addS(String n) { WorkerGrid w = new WorkerGrid1D(1); w.setLocalWork(1, 1, 1); sched.addWorkerGrid(n, w); }

    static long countBound(MotorStore m) { long c = 0; for (int i = 0; i < m.nMotors; i++) if (m.boundSeg.get(i) >= 0) c++; return c; }

    // ============================================================== #2 binding gate (bit-for-decision vs v1)
    static boolean checkBindingGate(double dt) {
        System.out.println("--- #2: binding-dependent lever-align gate (v1 MyosinDimer:276) bit-for-decision ---");
        // 1 dimer, off-160° levers so the align torque is non-zero when it fires. Three binding states.
        int[][] states = { {-1, -1}, {0, -1}, {0, 0} };   // both free / one bound / both bound
        String[] lbl = { "both-free", "one-bound", "both-bound" };
        boolean[] expectAlign = { true, true, false };     // v1: fire iff NOT both bound
        boolean ok = true;
        for (int s = 0; s < 3; s++) {
            Scene sc = buildScene(dt, 1, false, false);
            MotorStore mot = sc.mot; RigidRodBody b = mot.body;
            // tilt leverB by 10° so leverAng ≠ 160 ⇒ a real align torque if it fires
            int lB = mot.leverIdx(1);
            double a = Math.toRadians(80.0 - 10.0);
            b.setUVec(lB, (float)(-Math.sin(a)), 0f, (float)Math.cos(a));
            mot.boundSeg.set(0, states[s][0]); mot.boundSeg.set(1, states[s][1]);
            ChainBendingForceSystem.zeroAccumulators(b.forceSum, b.torqueSum, mot.counts);
            DimerCouplingSystem.couple(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam,
                    b.forceSum, b.torqueSum, sc.dim.motorA, sc.dim.motorB, sc.dim.parallel, sc.dim.dimerParams, mot.boundSeg);
            // reference: rod-coupling-only torque on leverA (the align contributes ONLY when it fires)
            double[] alignRef = refLeverAlignTorque(b, mot, 0, 1);   // v1 align torque on leverA (if it were to fire)
            int lA = mot.leverIdx(0), nB = b.n;
            double gotTx = b.torqueSum.get(lA), gotTy = b.torqueSum.get(nB+lA), gotTz = b.torqueSum.get(2*nB+lA);
            // The rod-coupling lever-arm torque is on the RODS, not levers; the ONLY torque on the LEVER is the align.
            double gotMag = Math.sqrt(gotTx*gotTx+gotTy*gotTy+gotTz*gotTz);
            double refMag = Math.sqrt(alignRef[0]*alignRef[0]+alignRef[1]*alignRef[1]+alignRef[2]*alignRef[2]);
            boolean fired = gotMag > 1e-25;
            boolean decisionOk = (fired == expectAlign[s]);
            double rel = fired ? Math.abs(gotMag - refMag)/Math.max(refMag,1e-30) : 0;
            boolean arithOk = !fired || rel < 1e-3;
            ok &= decisionOk && arithOk;
            System.out.printf("  %-11s boundSeg=(%2d,%2d): align fired=%-5s (expect %-5s) leverTorque=%.3e (v1 ref %.3e, rel %.2e) %s%n",
                    lbl[s], states[s][0], states[s][1], fired, expectAlign[s], gotMag, refMag, rel,
                    (decisionOk && arithOk) ? "" : "*FAIL*");
        }
        System.out.println("  (rod-couplings fire in ALL states — torque on the RODS; the LEVER torque is ONLY the align,");
        System.out.println("   so a non-zero lever torque ⟺ the gate let the align fire.)  => " + (ok ? "PASS" : "*FAIL*") + "\n");
        return ok;
    }
    /** v1 alignUVecLeversTorque on leverA (double ref): coeff·(π/180)·(ang−160)/((1/bRGy_A+1/bRGy_B)·dt)·(uA×uB). */
    static double[] refLeverAlignTorque(RigidRodBody b, MotorStore mot, int mA, int mB) {
        int nB = b.n, lA = 3*mA+1, lB = 3*mB+1;
        double u1x=b.uVec.get(lA),u1y=b.uVec.get(nB+lA),u1z=b.uVec.get(2*nB+lA);
        double u2x=b.uVec.get(lB),u2y=b.uVec.get(nB+lB),u2z=b.uVec.get(2*nB+lB);
        double tvx=u1y*u2z-u1z*u2y, tvy=u1z*u2x-u1x*u2z, tvz=u1x*u2y-u1y*u2x;
        double m2=tvx*tvx+tvy*tvy+tvz*tvz; if (m2<=1e-30) return new double[3];
        double im=1/Math.sqrt(m2); tvx*=im;tvy*=im;tvz*=im;
        double dot=u1x*u2x+u1y*u2y+u1z*u2z; if(dot>1)dot=1;if(dot<-1)dot=-1;
        double ang=Math.acos(dot)*180/Math.PI;
        double invBRG=1.0/b.bRotGam.get(nB+lA)+1.0/b.bRotGam.get(nB+lB);
        double tmag=DimerStore.LEVER_FRAC_MOVE_TORQ*(Math.PI/180)*(ang-DimerStore.LEVER_ANGLE_DEG)/(invBRG*mot.jointParams.get(0));
        return new double[]{ tvx*tmag, tvy*tmag, tvz*tmag };
    }

    // ============================================================== #1 force transmission (bit-identical + momentum)
    static boolean checkForceTransmission(double dt) {
        System.out.println("--- #1: force transmission through bound heads (gather==brute, momentum, CPU≡GPU) ---");
        // static-bound, held ADPPi (deterministic), Brownian off. Run the full deterministic step; check.
        Scene sc = buildScene(dt, 12, false, true);
        sc.mot.setAllStates(MotorStore.NUC_ADPPI);
        long bound = countBound(sc.mot);
        Runnable step = cpuStep(sc, false, true);
        sc.mot.setCounts(10, 0x6D9A0E, sc.fil.n);
        step.run();   // one step to populate forces (gather written)
        // gather == brute (fil side)
        sc.bruteFilF.init(0f); sc.bruteFilT.init(0f);
        CrossBridgeSystem.bruteGather(sc.mot.boundSeg, sc.bondData, sc.bruteFilF, sc.bruteFilT, sc.mot.counts);
        double gMax = 0;
        for (int i = 0; i < 3 * sc.fil.n; i++) gMax = Math.max(gMax, Math.abs(sc.fil.forceSum.get(i) - sc.bruteFilF.get(i)));
        boolean gatherOk = gMax == 0.0;
        System.out.printf("  bound heads=%d/%d; fil gather==brute max|Δ|=%.3e (==0) => %s%n", bound, sc.mot.nMotors, gMax, gatherOk ? "ok" : "*FAIL*");
        // momentum: Σ motor-body force + Σ fil force == 0 (cross-bridge is the only cross-entity force;
        // joints + dimer coupling are internal). Re-run a fresh step capturing the motor force BEFORE integrate.
        double[] mom = momentumResidual(dt);
        boolean momOk = mom[0] < 1e-16;
        System.out.printf("  momentum |ΣmotorF + ΣfilF| = (%.2e,%.2e,%.2e) N max=%.2e (~0) => %s%n", mom[1], mom[2], mom[3], mom[0], momOk ? "ok" : "*FAIL*");
        // CPU≡GPU: 300 deterministic steps (Brownian off, held state), compare motor + fil pose
        boolean cpuGpuOk = true;
        if (!cpu) {
            Scene g = buildScene(dt, 12, false, true); g.mot.setAllStates(MotorStore.NUC_ADPPI);
            Scene c = buildScene(dt, 12, false, true); c.mot.setAllStates(MotorStore.NUC_ADPPI);
            runGpu(g, 300, false, true); runCpu(c, 300, false, true);
            double dM = maxDiff(g.mot.body.coord, c.mot.body.coord);
            cpuGpuOk = dM < 5e-5;
            System.out.printf("  CPU≡GPU (300 steps, Brownian off, held): max|Δmotor pose|=%.3e µm (<5e-5) => %s%n", dM, cpuGpuOk ? "ok" : "*FAIL*");
        } else System.out.println("  CPU≡GPU: skipped (-cpu)");
        boolean ok = gatherOk && momOk && cpuGpuOk;
        System.out.println("  => " + (ok ? "PASS" : "*FAIL*") + "\n");
        return ok;
    }
    /** Run one deterministic step; return {maxAbs, residX, residY, residZ} of Σ(motor force)+Σ(fil force). */
    static double[] momentumResidual(double dt) {
        Scene sc = buildScene(dt, 12, false, true); sc.mot.setAllStates(MotorStore.NUC_ADPPI);
        FilamentStore f = sc.fil; MotorStore mot = sc.mot; RigidRodBody b = mot.body; DimerStore dim = sc.dim;
        mot.setCounts(10, 0x6D9A0E, f.n);
        // build forces up to (not including) integrate
        ChainBendingForceSystem.zeroAccumulators(b.forceSum, b.torqueSum, mot.counts);
        MotorJointSystem.joints(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, mot.nucleotideState, mot.jointParams, mot.counts);
        DimerCouplingSystem.couple(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, dim.motorA, dim.motorB, dim.parallel, dim.dimerParams, mot.boundSeg);
        CrossBridgeSystem.bondForces(b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength, mot.boundSeg, mot.bindArc, mot.nucleotideState, sc.bondData, sc.xbParams);
        CrossBridgeSystem.applyHeadForce(sc.bondData, b.forceSum, b.torqueSum, mot.counts);
        ChainBendingForceSystem.zeroAccumulators(f.forceSum, f.torqueSum, f.counts);
        CrossBridgeSystem.csrHistogram(mot.boundSeg, mot.counts, sc.segMotorCount);
        CrossBridgeSystem.csrScan(mot.counts, sc.segMotorCount, sc.segMotorOffsets);
        CrossBridgeSystem.csrScatter(mot.boundSeg, mot.counts, sc.segMotorOffsets, sc.segMotorCount, sc.segMotorMyo);
        CrossBridgeSystem.segGather(sc.segMotorOffsets, sc.segMotorMyo, sc.bondData, f.forceSum, f.torqueSum, mot.counts);
        double[] r = new double[4];
        for (int k = 0; k < 3; k++) {
            double sm = 0, sf = 0;
            for (int i = 0; i < b.n; i++) sm += b.forceSum.get(k*b.n + i);
            for (int s = 0; s < f.n; s++) sf += f.forceSum.get(k*f.n + s);
            r[k+1] = sm + sf; r[0] = Math.max(r[0], Math.abs(sm + sf));
        }
        return r;
    }

    // ============================================================== #3 translocation (emergent, −x)
    static boolean checkTranslocation(double dt) {
        System.out.println("--- #3: two-head translocation (free dimer, cycling) — emergent +x walk (toward actin plus-end) ---");
        Scene sc = buildScene(dt, 12, false, true);
        sc.mot.nucleotideState.init(MotorStore.NUC_NONE);
        double comX0 = motorComX(sc.mot);
        int M = 20000;
        Runnable step = cpuStep(sc, true, true);
        double boundSum = 0; int n = 0;
        for (int t = 0; t < M; t++) { sc.mot.setCounts(t, 0x6D9A0E, sc.fil.n); step.run(); bindStep(sc, 1); boundSum += countBound(sc.mot); n++; }
        double comX1 = motorComX(sc.mot);
        double dx = (comX1 - comX0) * 1e3;   // nm
        // Single-motor baseline (4b-iii gate 4): cycling motors pulse −x force INTO the pinned filament.
        // By Newton's 3rd law the head feels the +x REACTION, so a FREE dimer translocates +x — toward the
        // actin PLUS-end (+x here), the biological direction myosin II walks. (The −x is the FILAMENT's
        // glide in a surface assay; the free MOTOR walks the opposite way — same Newton pair.) Gate: +x drift.
        boolean drifts = dx > 0.5;   // moved at least 0.5 nm toward +x (the actin plus-end)
        System.out.printf("  dimer motor-COM Δx over %d steps = %.2f nm; avgBound=%.2f/%d  => %s%n",
                M, dx, boundSum/n, sc.mot.nMotors, drifts ? "+x walk (toward plus-end) PASS" : "*no +x drift*");
        System.out.println("  (emergent; the free dimer walks +x = the Newton reaction to 4b-iii's −x filament force; v1 informational.)");
        System.out.println("  => " + (drifts ? "PASS" : "*FAIL*") + "\n");
        return drifts;
    }
    static double motorComX(MotorStore mot) {
        RigidRodBody b = mot.body; double s = 0; for (int i = 0; i < b.n; i++) s += b.coord.get(i); return s / b.n;
    }

    // ============================================================== #5 all-OFF ≡ HEAD (single-motor path)
    static boolean checkAllOffEqualsHead(double dt) {
        System.out.println("--- #5: all-OFF ≡ HEAD (dimer coupling off ⇒ the single-motor / 4b-iii path) ---");
        // anchored motors, dimer coupling OFF ⇒ exactly the 4b-iii single-motor stroke path. Determinism +
        // the control that turning the dimer ON changes it (so the dimer coupling is real, not a silent no-op).
        Scene a = buildScene(dt, 6, true, true); a.mot.nucleotideState.init(MotorStore.NUC_NONE);
        Scene b = buildScene(dt, 6, true, true); b.mot.nucleotideState.init(MotorStore.NUC_NONE);
        runCpu(a, 1500, true, false); runCpu(b, 1500, true, false);
        double dOff = maxDiff(a.mot.body.coord, b.mot.body.coord);
        boolean det = dOff == 0.0;
        // control: dimer ON diverges (anchored, but the rod-coupling perturbs)
        Scene cOn = buildScene(dt, 6, true, true); cOn.mot.nucleotideState.init(MotorStore.NUC_NONE);
        runCpu(cOn, 1500, true, true);
        double dOnVsOff = maxDiff(cOn.mot.body.coord, a.mot.body.coord);
        boolean controlOk = dOnVsOff > 1e-9;
        boolean ok = det && controlOk;
        System.out.printf("  single-motor path determinism (dimer off): max|Δ|=%.3e (==0) => %s%n", dOff, det ? "ok" : "*FAIL*");
        System.out.printf("  control: dimer ON vs OFF differs by %.3e µm (coupling is real) => %s%n", dOnVsOff, controlOk ? "ok" : "*FAIL*");
        System.out.println("  => " + (ok ? "PASS" : "*FAIL*") + "\n");
        return ok;
    }

    // ============================================================== runners
    static void runCpu(Scene sc, int M, boolean cyc, boolean dimerOn) {
        Runnable step = cpuStep(sc, cyc, dimerOn);
        for (int t = 0; t < M; t++) { sc.mot.setCounts(t, 0x6D9A0E, sc.fil.n); step.run(); }
    }
    static void runGpu(Scene sc, int M, boolean cyc, boolean dimerOn) {
        TornadoExecutionPlan plan = buildPlan(sc, cyc, dimerOn);
        RigidRodBody b = sc.mot.body;
        for (int t = 0; t < M; t++) {
            sc.mot.setCounts(t, 0x6D9A0E, sc.fil.n);
            TornadoExecutionResult res = plan.withGridScheduler(sched).execute();
            if (t == M - 1) res.transferToHost(b.coord, b.uVec, sc.fil.forceSum, sc.mot.boundSeg);
        }
    }
    static double maxDiff(FloatArray a, FloatArray b) { double m=0; for (int i=0;i<a.getSize();i++) m=Math.max(m, Math.abs(a.get(i)-b.get(i))); return m; }

    // ---- dimer placement (splayed rest: rods coincident along dir, levers ±80° ⇒ 160° apart) ----
    static final double SIN80 = Math.sin(Math.toRadians(80.0)), COS80 = Math.cos(Math.toRadians(80.0));
    static void placeDimerAlong(MotorStore mot, int mA, int mB,
                                double e1x, double e1y, double e1z, double dx, double dy, double dz, float brownScale) {
        double dm = Math.sqrt(dx*dx+dy*dy+dz*dz); dx/=dm; dy/=dm; dz/=dm;
        double px = -dz, py = 0, pz = dx;
        double pm = Math.sqrt(px*px+py*py+pz*pz);
        if (pm < 1e-4) { px = 1; py = 0; pz = 0; pm = 1; }
        px/=pm; py/=pm; pz/=pm;
        double rl = MotorStore.ROD_LEN, ll = MotorStore.LEVER_LEN, hl = MotorStore.HEAD_LEN;
        double rcx=e1x+0.5*rl*dx, rcy=e1y+0.5*rl*dy, rcz=e1z+0.5*rl*dz;
        double e2x=e1x+rl*dx, e2y=e1y+rl*dy, e2z=e1z+rl*dz;
        placeArm(mot, mA, rcx,rcy,rcz, dx,dy,dz, px,py,pz, e2x,e2y,e2z,  +1, ll, hl, brownScale);
        placeArm(mot, mB, rcx,rcy,rcz, dx,dy,dz, px,py,pz, e2x,e2y,e2z,  -1, ll, hl, brownScale);
    }
    static void placeArm(MotorStore mot, int m, double rcx, double rcy, double rcz,
                         double dx, double dy, double dz, double px, double py, double pz,
                         double e2x, double e2y, double e2z, int splay, double ll, double hl, float brownScale) {
        int rod = mot.rodIdx(m), lever = mot.leverIdx(m), head = mot.headIdx(m);
        RigidRodBody b = mot.body;
        b.setCoord(rod, (float) rcx, (float) rcy, (float) rcz);
        b.setUVec(rod, (float) dx, (float) dy, (float) dz); b.setYVec(rod, (float) px, (float) py, (float) pz);
        double lux = COS80*dx + splay*SIN80*px, luy = COS80*dy + splay*SIN80*py, luz = COS80*dz + splay*SIN80*pz;
        double nx = dy*pz - dz*py, ny = dz*px - dx*pz, nz = dx*py - dy*px;
        double lcx = e2x + 0.5*ll*lux, lcy = e2y + 0.5*ll*luy, lcz = e2z + 0.5*ll*luz;
        b.setCoord(lever, (float) lcx, (float) lcy, (float) lcz);
        b.setUVec(lever, (float) lux, (float) luy, (float) luz); b.setYVec(lever, (float) nx, (float) ny, (float) nz);
        double le2x = e2x + ll*lux, le2y = e2y + ll*luy, le2z = e2z + ll*luz;
        double hcx = le2x + 0.5*hl*lux, hcy = le2y + 0.5*hl*luy, hcz = le2z + 0.5*hl*luz;
        b.setCoord(head, (float) hcx, (float) hcy, (float) hcz);
        b.setUVec(head, (float) lux, (float) luy, (float) luz); b.setYVec(head, (float) nx, (float) ny, (float) nz);
        b.brownTransScale.set(rod, brownScale);   b.brownRotScale.set(rod, brownScale);
        b.brownTransScale.set(lever, 0f);          b.brownRotScale.set(lever, 0f);
        b.brownTransScale.set(head, brownScale);   b.brownRotScale.set(head, brownScale);
    }

    // ============================================================== gliding assay: FIXED dimers + FREE filament
    /** A gliding assay where the FIXED elements are DIMERS (not single myosins): a bed of anchored
     *  two-head dimers + a FREE chain filament that glides over them (the GlidingHarness step + the
     *  binding-gated dimer coupling). Viewer only. */
    static Scene buildAssayScene(double dt) {
        Scene sc = new Scene(); sc.anchored = true;
        // GlidingHarness-scale geometry: 64-monomer segments, filament at z=0, anchors at −0.05 (heads flop
        // down to bind). 8 µm × 4 µm mat of dimers @ 500 heads/µm²; a ~2 µm filament starting with its
        // barbed (plus) end at the mat's right edge, gliding −x across the mat.
        int nSeg = 11, filMono = 64;                    // 11 × 65 monomers ≈ 1.93 µm filament
        double L = (filMono + 1) * Constants.actinMonoRadius;
        double zFil = 0.0;
        double matXhalf = 4.0, matYhalf = 2.0;          // 8 µm (glide/x) × 4 µm (width/y)

        // ---- free chain filament along +x, centered, glides −x over the bed ----
        FilamentStore fil = new FilamentStore(nSeg);
        for (int s = 0; s < nSeg; s++) {
            fil.monomerCount.set(s, filMono);
            fil.setUVec(s, 1f, 0f, 0f); fil.setYVec(s, 0f, 1f, 0f);
            // plus-end (end2 of the last segment) at the right edge (x = +matXhalf); step back by L
            fil.setCoord(s, (float) (matXhalf - 0.5 * L - (nSeg - 1 - s) * L), 0f, (float) zFil);
            fil.brownTransScale.set(s, (float) Constants.BTransCoeff);
            boolean end = (s == 0 || s == nSeg - 1);
            fil.brownRotScale.set(s, (float) (end ? Constants.BRotCoeff : 0.0));
            if (s < nSeg - 1) { fil.end2NbrSlot.set(s, s + 1); fil.end2NbrSide.set(s, 0); }
            if (s > 0)        { fil.end1NbrSlot.set(s, s - 1); fil.end1NbrSide.set(s, 1); }
        }
        DragTensorSystem.run(fil); fil.setParams(dt, Math.sqrt(2.0 * Constants.kT / dt)); fil.setCounts(0, 0xF11A);
        fil.chainParams.set(0, (float) dt); fil.chainParams.set(1, 0.5f); fil.chainParams.set(2, 0.1f);
        fil.chainParams.set(3, 0.2f); fil.chainParams.set(4, 0f); fil.chainParams.set(5, 1.0e-20f);
        fil.chainParams.set(6, (float) Constants.actinMonoRadius);
        DerivedGeometrySystem.derive(fil.coord, fil.uVec, fil.yVec, fil.zVec, fil.end1, fil.end2, fil.segLength, fil.counts);

        // ---- a density bed of FIXED dimers around the filament path (the gliding-assay strip) ----
        // Binding is intrinsically sparse (heads flop down to z=0 only occasionally), so — like the
        // single-motor assay (6200 motors → ~7.6 bound) — we need a wide, dense bed so the wandering
        // filament always overlies a populated strip. nDimers from a density; two coupled motors per dimer.
        double bedLo = -matXhalf, bedHi = matXhalf, bedYh = matYhalf;                 // the full 8 × 4 µm mat
        int nDimers = (int) Math.round(250.0 * (bedHi - bedLo) * (2 * bedYh));        // 250 dimers/µm² = 500 heads/µm²
        MotorStore mot = new MotorStore(2 * nDimers);
        DimerStore dim = new DimerStore(nDimers);
        java.util.Random rng = new java.util.Random(12345);
        for (int d = 0; d < nDimers; d++) {
            double ax = bedLo + rng.nextDouble() * (bedHi - bedLo);
            double ay = -bedYh + rng.nextDouble() * (2 * bedYh);
            // two coupled motors standing +z, tails 2 nm apart (a fixed dimer); anchored by assembleArticulated
            mot.assembleArticulated(2 * d,     (float) ax,           (float) ay, (float) ANCHOR_Z, 0f, 0f, 1f, (float) Constants.BTransCoeff);
            mot.assembleArticulated(2 * d + 1, (float) (ax + 0.002), (float) ay, (float) ANCHOR_Z, 0f, 0f, 1f, (float) Constants.BTransCoeff);
            dim.pair(d, 2 * d, 2 * d + 1, true);
        }
        DragTensorSystem.run(mot);
        mot.setBodyParams(dt); mot.setJointParams(dt); mot.setKinParams(0.006, -0.4, dt); mot.setNucParams(dt);
        mot.nucleotideState.init(MotorStore.NUC_NONE);
        dim.setDimerParams(dt);   // faithful coupling (lever-align 0.4)
        DerivedGeometrySystem.derive(mot.body.coord, mot.body.uVec, mot.body.yVec, mot.body.zVec,
                mot.body.end1, mot.body.end2, mot.body.segLength, mot.counts);   // init end1/end2 for the t=0 frame

        int MAXC = SpatialGrid.MAX_CAND;
        sc.bondData = new FloatArray(2 * nDimers * CrossBridgeSystem.STRIDE); sc.bondData.init(0f);
        sc.xbParams = FloatArray.fromElements((float) MYO_SPRING, 90f, (float) J1_FMT, (float) dt, (float) MotorStore.HEAD_LEN, 0f);
        sc.segMotorCount = new IntArray(nSeg); sc.segMotorOffsets = new IntArray(nSeg + 1); sc.segMotorMyo = new IntArray(2 * nDimers);
        sc.reachSeg = new IntArray(2 * nDimers * MAXC); sc.reachSeg.init(-1); sc.reachCount = new IntArray(2 * nDimers);
        sc.fil = fil; sc.mot = mot; sc.dim = dim;
        return sc;
    }

    /** One gliding-assay step: GlidingHarness.stepOrig + the binding-gated dimer coupling (anchored dimers,
     *  free filament). CPU runner (viewer). */
    static void assayStep(Scene sc, int t) {
        FilamentStore f = sc.fil; MotorStore mot = sc.mot; DimerStore dim = sc.dim; RigidRodBody b = mot.body;
        mot.setCounts(t, 0x6D9A0E, f.n); f.counts.set(1, t);
        // dynamic binding
        MotorStore.publishHeadFromBody(b.coord, b.uVec, b.segLength, mot.head, mot.uVec, mot.rodUVec, mot.counts);
        BindingDetectionSystem.bruteReachable(mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, sc.reachSeg, sc.reachCount, mot.kinParams, mot.counts);
        NucleotideCycleSystem.catchSlipRelease(mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.cooldown, mot.stats, mot.capStats, mot.kinParams, mot.counts);
        BindingDetectionSystem.bindNearest(mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, sc.reachSeg, sc.reachCount, mot.boundSeg, mot.bindArc, mot.kinParams, mot.counts);
        NucleotideCycleSystem.cycle(mot.nucleotideState, mot.boundSeg, mot.forceDotHist, mot.nucParams, mot.counts);
        // motor + dimer dynamics
        ChainBendingForceSystem.zeroAccumulators(b.forceSum, b.torqueSum, mot.counts);
        BrownianForceSystem.brownianForce(b.randForce, b.randTorque, b.bTransGam, b.bRotGam, b.brownTransScale, b.brownRotScale, mot.bodyParams, mot.counts);
        MotorJointSystem.joints(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, mot.nucleotideState, mot.jointParams, mot.counts);
        TailAnchorSystem.anchor(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, mot.anchor, mot.jointParams, mot.counts);
        DimerCouplingSystem.couple(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, dim.motorA, dim.motorB, dim.parallel, dim.dimerParams, mot.boundSeg);
        CrossBridgeSystem.bondForces(b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength, mot.boundSeg, mot.bindArc, mot.nucleotideState, sc.bondData, sc.xbParams);
        CrossBridgeSystem.applyHeadForce(sc.bondData, b.forceSum, b.torqueSum, mot.counts);
        RigidRodLangevinIntegrationSystem.integrate(b.coord, b.uVec, b.yVec, b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, mot.bodyParams, mot.counts);
        DerivedGeometrySystem.derive(b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts);
        CrossBridgeSystem.registerForceDot(sc.bondData, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.counts);
        // filament dynamics: chain + Brownian + the gathered cross-bridge, then integrate (the filament GLIDES)
        ChainBendingForceSystem.zeroAccumulators(f.forceSum, f.torqueSum, f.counts);
        BrownianForceSystem.brownianForce(f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.brownTransScale, f.brownRotScale, f.params, f.counts);
        ChainBendingForceSystem.chainForces(f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide, f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts);
        CrossBridgeSystem.csrHistogram(mot.boundSeg, mot.counts, sc.segMotorCount);
        CrossBridgeSystem.csrScan(mot.counts, sc.segMotorCount, sc.segMotorOffsets);
        CrossBridgeSystem.csrScatter(mot.boundSeg, mot.counts, sc.segMotorOffsets, sc.segMotorCount, sc.segMotorMyo);
        CrossBridgeSystem.segGather(sc.segMotorOffsets, sc.segMotorMyo, sc.bondData, f.forceSum, f.torqueSum, mot.counts);
        RigidRodLangevinIntegrationSystem.integrate(f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts);
        DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
    }

    /** GPU TaskGraph for the assay — GlidingHarness's device-resident gliding step + the binding-gated
     *  dimer coupling (inserted after the anchor). Default order (release/cycle read last step's load). */
    static TornadoExecutionPlan buildAssayPlan(Scene sc) {
        FilamentStore f = sc.fil; MotorStore mot = sc.mot; DimerStore dim = sc.dim; RigidRodBody b = mot.body;
        TaskGraph tg = new TaskGraph("dassay")
            .transferToDevice(DataTransferMode.FIRST_EXECUTION,
                    b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, b.bTransGam, b.bRotGam,
                    b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.brownTransScale, b.brownRotScale,
                    mot.head, mot.uVec, mot.rodUVec, mot.anchor, mot.boundSeg, mot.bindArc, mot.nucleotideState,
                    mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.stats, mot.capStats, mot.cooldown,
                    mot.bodyParams, mot.jointParams, mot.nucParams, mot.kinParams,
                    dim.motorA, dim.motorB, dim.parallel, dim.dimerParams,
                    sc.bondData, sc.xbParams, sc.segMotorCount, sc.segMotorOffsets, sc.segMotorMyo, sc.reachSeg, sc.reachCount,
                    f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.bTransGam, f.bRotGam,
                    f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.brownTransScale, f.brownRotScale,
                    f.params, f.chainParams, f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide)
            .transferToDevice(DataTransferMode.EVERY_EXECUTION, mot.counts, f.counts)
            .task("publishHead", MotorStore::publishHeadFromBody, b.coord, b.uVec, b.segLength, mot.head, mot.uVec, mot.rodUVec, mot.counts)
            .task("reach", BindingDetectionSystem::bruteReachable, mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, sc.reachSeg, sc.reachCount, mot.kinParams, mot.counts)
            .task("release", NucleotideCycleSystem::catchSlipRelease, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.cooldown, mot.stats, mot.capStats, mot.kinParams, mot.counts)
            .task("bind", BindingDetectionSystem::bindNearest, mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, sc.reachSeg, sc.reachCount, mot.boundSeg, mot.bindArc, mot.kinParams, mot.counts)
            .task("cycle", NucleotideCycleSystem::cycle, mot.nucleotideState, mot.boundSeg, mot.forceDotHist, mot.nucParams, mot.counts)
            .task("zeroMot", ChainBendingForceSystem::zeroAccumulators, b.forceSum, b.torqueSum, mot.counts)
            .task("brownMot", BrownianForceSystem::brownianForce, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, b.brownTransScale, b.brownRotScale, mot.bodyParams, mot.counts)
            .task("joints", MotorJointSystem::joints, b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, mot.nucleotideState, mot.jointParams, mot.counts)
            .task("anchor", TailAnchorSystem::anchor, b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, mot.anchor, mot.jointParams, mot.counts)
            .task("dimer", DimerCouplingSystem::couple, b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, dim.motorA, dim.motorB, dim.parallel, dim.dimerParams, mot.boundSeg)
            .task("bond", CrossBridgeSystem::bondForces, b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength, mot.boundSeg, mot.bindArc, mot.nucleotideState, sc.bondData, sc.xbParams)
            .task("applyHead", CrossBridgeSystem::applyHeadForce, sc.bondData, b.forceSum, b.torqueSum, mot.counts)
            .task("integMot", RigidRodLangevinIntegrationSystem::integrate, b.coord, b.uVec, b.yVec, b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, mot.bodyParams, mot.counts)
            .task("deriveMot", DerivedGeometrySystem::derive, b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts)
            .task("register", CrossBridgeSystem::registerForceDot, sc.bondData, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.counts)
            .task("zeroFil", ChainBendingForceSystem::zeroAccumulators, f.forceSum, f.torqueSum, f.counts)
            .task("brownFil", BrownianForceSystem::brownianForce, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.brownTransScale, f.brownRotScale, f.params, f.counts)
            .task("chain", ChainBendingForceSystem::chainForces, f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide, f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts)
            .task("csrHist", CrossBridgeSystem::csrHistogram, mot.boundSeg, mot.counts, sc.segMotorCount)
            .task("csrScan", CrossBridgeSystem::csrScan, mot.counts, sc.segMotorCount, sc.segMotorOffsets)
            .task("csrScatter", CrossBridgeSystem::csrScatter, mot.boundSeg, mot.counts, sc.segMotorOffsets, sc.segMotorCount, sc.segMotorMyo)
            .task("gather", CrossBridgeSystem::segGather, sc.segMotorOffsets, sc.segMotorMyo, sc.bondData, f.forceSum, f.torqueSum, mot.counts)
            .task("integFil", RigidRodLangevinIntegrationSystem::integrate, f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts)
            .task("deriveFil", DerivedGeometrySystem::derive, f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts)
            .transferToHost(DataTransferMode.UNDER_DEMAND, b.end1, b.end2, mot.boundSeg, mot.nucleotideState, f.end1, f.end2, f.coord);

        int nB = 3 * mot.nMotors, nM = mot.nMotors, nSeg = f.n, nD = sc.dim.nDimers;
        sched = new GridScheduler();
        for (String t : new String[]{ "publishHead","reach","release","bind","cycle","anchor","bond","applyHead","register" }) addW("dassay." + t, pad(nM));
        for (String t : new String[]{ "zeroMot","brownMot","joints","integMot","deriveMot" }) addW("dassay." + t, pad(nB));
        addW("dassay.dimer", pad(nD));
        for (String t : new String[]{ "zeroFil","brownFil","chain","gather","integFil","deriveFil" }) addW("dassay." + t, pad(nSeg));
        for (String t : new String[]{ "csrHist","csrScan","csrScatter" }) addS("dassay." + t);
        return new TornadoExecutionPlan(tg.snapshot());
    }

    static void runAssayViz(double dt, String dir) {
        System.out.println("Gliding assay with FIXED DIMERS (not single myosins): a free filament glides over an anchored dimer bed.");
        Scene sc = buildAssayScene(dt);
        MotorStore mot = sc.mot; RigidRodBody b = mot.body; FilamentStore f = sc.fil;
        new java.io.File(dir).mkdirs();
        int M = 100000, every = Math.max(1, M / 400), frames = 0, stride = Math.max(1, mot.nMotors / 1500);
        System.out.printf("config: %d dimers (%d motors) over 8×4 µm @ 500 heads/µm²; %d-seg filament (~%.2f µm), barbed-end at right edge.%n",
                sc.dim.nDimers, mot.nMotors, f.n, f.n * 65.0 * Constants.actinMonoRadius);
        System.out.printf("running %d GPU steps (~%.2f s sim time); rendering ~%d sampled motors + the filament.%n", M, M * dt, mot.nMotors / stride);
        TornadoExecutionPlan plan = buildAssayPlan(sc);
        double filX0 = filComX(f), boundSum = 0; int nb = 0;
        writeAssayFrame(dir, frames++, 0.0, sc, stride);            // initial state (host)
        long t0 = System.currentTimeMillis();
        for (int t = 0; t < M; t++) {
            mot.setCounts(t, 0x6D9A0E, f.n); f.counts.set(1, t);
            TornadoExecutionResult res = plan.withGridScheduler(sched).execute();
            if ((t + 1) % every == 0) {
                res.transferToHost(b.end1, b.end2, mot.boundSeg, mot.nucleotideState, f.end1, f.end2, f.coord);
                writeAssayFrame(dir, frames++, (t + 1) * dt, sc, stride);
                boundSum += countBound(mot); nb++;
            }
        }
        double filDx = (filComX(f) - filX0);
        double secs = (System.currentTimeMillis() - t0) / 1000.0;
        System.out.printf("viewer: wrote %d frames to %s  (%.0f steps/s)%n", frames, dir, M / secs);
        System.out.printf("  avgBound ≈ %.1f / %d heads;  filament COM Δx = %.2f µm over %d steps (glides −x %.2f µm/s)%n",
                boundSum / nb, mot.nMotors, filDx, M, Math.abs(filDx) / (M * dt));
    }

    /** Sampled frame: the whole filament + every `stride`-th motor + ALL bound motors (the active ones). */
    static void writeAssayFrame(String dir, int frame, double t, Scene sc, int stride) {
        FilamentStore f = sc.fil; MotorStore mot = sc.mot; RigidRodBody b = mot.body;
        StringBuilder sb = new StringBuilder(1 << 20);
        sb.append(String.format(java.util.Locale.US, "{\"frame\":%d,\"t\":%.6g,\"bounds\":{\"xDim\":8.0,\"yDim\":4.0,\"zDim\":0.4}", frame, t));
        sb.append(",\"segments\":[");
        for (int s = 0; s < f.n; s++) { if (s > 0) sb.append(',');
            sb.append(String.format(java.util.Locale.US, "{\"id\":%d,\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.5g,\"notADPRatio\":1.0,\"cofilinCount\":0}",
                s, f.end1.get(s),f.end1.get(f.n+s),f.end1.get(2*f.n+s), f.end2.get(s),f.end2.get(f.n+s),f.end2.get(2*f.n+s), Constants.radius)); }
        sb.append("],\"myosins\":[");
        boolean first = true;
        for (int m = 0; m < mot.nMotors; m++) {
            boolean bound = mot.boundSeg.get(m) >= 0;
            if (!(m % stride == 0 || bound)) continue;
            if (!first) sb.append(','); first = false;
            int rod = 3*m, lever = 3*m+1, head = 3*m+2; String st = STATE_NAME[mot.nucleotideState.get(m)];
            sb.append(String.format(java.util.Locale.US,
                "{\"id\":%d,\"rod\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g,\"invisible\":false},"
                + "\"lever\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g},"
                + "\"motor\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g,\"state\":\"%s\"}}",
                m, b.end1X(rod),b.end1Y(rod),b.end1Z(rod), b.end2X(rod),b.end2Y(rod),b.end2Z(rod), MotorStore.ROD_R,
                b.end1X(lever),b.end1Y(lever),b.end1Z(lever), b.end2X(lever),b.end2Y(lever),b.end2Z(lever), MotorStore.LEVER_R,
                b.end1X(head),b.end1Y(head),b.end1Z(head), b.end2X(head),b.end2Y(head),b.end2Z(head), MotorStore.HEAD_R, st)); }
        sb.append("]}");
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(dir, String.format(java.util.Locale.US,"frame_%06d.json", frame)), sb.toString()); }
        catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }
    static double filComX(FilamentStore f) { double s = 0; for (int i = 0; i < f.n; i++) s += f.coord.get(i); return s / f.n; }

    // ============================================================== viewer
    static final String[] STATE_NAME = { "NONE", "ATP", "ADPPi", "ADP" };
    static void runViz(double dt, String dir) {
        Scene sc = buildScene(dt, 12, false, true); sc.mot.nucleotideState.init(MotorStore.NUC_NONE);
        new java.io.File(dir).mkdirs();
        Runnable step = cpuStep(sc, true, true);
        int M = 20000, every = Math.max(1, M / 400), frames = 0;
        for (int t = 0; t <= M; t++) {
            sc.mot.setCounts(t, 0x6D9A0E, sc.fil.n);
            if (t % every == 0) writeFrame(dir, frames++, t * dt, sc);
            step.run(); bindStep(sc, 1);
        }
        System.out.println("viewer: wrote " + frames + " frames to " + dir);
    }
    static void writeFrame(String dir, int frame, double t, Scene sc) {
        FilamentStore f = sc.fil; MotorStore mot = sc.mot; RigidRodBody b = mot.body;
        StringBuilder sb = new StringBuilder(512 + 200 * mot.nMotors);
        sb.append(String.format(java.util.Locale.US, "{\"frame\":%d,\"t\":%.6g,\"bounds\":{\"xDim\":0.8,\"yDim\":0.4,\"zDim\":0.2}", frame, t));
        sb.append(",\"segments\":[");
        for (int s = 0; s < f.n; s++) { if (s > 0) sb.append(',');
            sb.append(String.format(java.util.Locale.US, "{\"id\":%d,\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.5g,\"notADPRatio\":1.0,\"cofilinCount\":0}",
                s, f.end1.get(s),f.end1.get(f.n+s),f.end1.get(2*f.n+s), f.end2.get(s),f.end2.get(f.n+s),f.end2.get(2*f.n+s), Constants.radius)); }
        sb.append("],\"myosins\":[");
        for (int m = 0; m < mot.nMotors; m++) { if (m > 0) sb.append(',');
            int rod = 3*m, lever = 3*m+1, head = 3*m+2; String st = STATE_NAME[mot.nucleotideState.get(m)];
            sb.append(String.format(java.util.Locale.US,
                "{\"id\":%d,\"rod\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g,\"invisible\":false},"
                + "\"lever\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g},"
                + "\"motor\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g,\"state\":\"%s\"}}",
                m, b.end1X(rod),b.end1Y(rod),b.end1Z(rod), b.end2X(rod),b.end2Y(rod),b.end2Z(rod), MotorStore.ROD_R,
                b.end1X(lever),b.end1Y(lever),b.end1Z(lever), b.end2X(lever),b.end2Y(lever),b.end2Z(lever), MotorStore.LEVER_R,
                b.end1X(head),b.end1Y(head),b.end1Z(head), b.end2X(head),b.end2Y(head),b.end2Z(head), MotorStore.HEAD_R, st)); }
        sb.append("]}");
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(dir, String.format(java.util.Locale.US,"frame_%06d.json", frame)), sb.toString()); }
        catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }
}
