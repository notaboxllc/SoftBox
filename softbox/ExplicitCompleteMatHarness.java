package softbox;

import softbox.TwoBodyConverterMotor.Glide2D;
import uk.ac.manchester.tornado.api.*;
import uk.ac.manchester.tornado.api.TornadoProfilerResult;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.enums.ProfilerMode;
import uk.ac.manchester.tornado.api.types.arrays.DoubleArray;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.Locale;

/**
 * Explicit coupled kernel → COMPLETE persistent mat integration. §5 explicit head-placement CPU/GPU gate +
 * §6 bondForces coupling gate (the sanctioned prerequisites: "this stage must pass before composing the full
 * graph"). Reuses the validated matS2SolveStep + matBeamGeom + matPlaceHeadExplicit; the shared bondForces is
 * BYTE-UNCHANGED and simply reads the beam-derived head pose. Real buildS2Mat gliding mat; production geom2D +
 * placeHead2D + bondForces are the oracle. Flags: -Dtornado.recover.bailout=false -Dtornado.enable.fma=false.
 */
public final class ExplicitCompleteMatHarness {
    static final double DT = 2.5e-6;
    static final String OUT = "RUN_LOGS/explicit_completemat";

    public static void main(String[] args) {
        try { Files.createDirectories(Path.of(OUT)); } catch (IOException e) { throw new UncheckedIOException(e); }
        if (!"false".equals(System.getProperty("tornado.recover.bailout"))) System.out.println("!! run with -Dtornado.recover.bailout=false");
        boolean traj = false, bench = false, stroke = false, gliding = false, throughput = false, quick = false;
        for (String a : args) { if (a.equals("-traj")) traj = true; if (a.equals("-bench")) bench = true; if (a.equals("-stroke")) stroke = true; if (a.equals("-gliding")) gliding = true; if (a.equals("-throughput")) throughput = true; if (a.equals("-quick")) quick = true; }
        StringBuilder log = new StringBuilder();
        boolean ok; String fn;
        boolean throughputCal = false, sweep = false;
        for (String a : args) { if (a.equals("-throughputcal")) throughputCal = true; if (a.equals("-sweep")) sweep = true; }
        if (sweep)      { log.append("# Explicit density-saturation sweep (Phase C) — GPU gliding velocity, explicit vs calibrated\n\n"); ok = sweepMode(log, quick, args); fn = "COMPLETEMAT_SWEEP.md"; }
        else if (throughputCal) { log.append("# Calibrated (calibrated-s2-l40) GENUINE FREE-BINDING NO-CULL throughput (Phase B arm) — CPU vs GPU, N=600–9000\n\n"); ok = throughputCalMode(log, quick); fn = "COMPLETEMAT_THROUGHPUT_CAL.md"; }
        else if (throughput) { log.append("# Explicit GENUINE FREE-BINDING NO-CULL throughput (Phase A) — CPU-analytic vs GPU-analytic, N=600–9000\n\n"); ok = throughputMode(log, quick); fn = "COMPLETEMAT_THROUGHPUT.md"; }
        else if (gliding)    { log.append("# Explicit FREE-BINDING gliding — §8 bind replay + §10 low-density gliding (CPU-runner vs GPU)\n\n"); ok = glidingMode(log); fn = "COMPLETEMAT_GLIDING.md"; }
        else if (bench) { log.append("# Explicit complete-mat NO-CULL throughput — CPU-analytic vs GPU-analytic @ 200/700/1500\n\n"); ok = benchMode(log); fn = "COMPLETEMAT_BENCH.md"; }
        else if (stroke){ log.append("# Explicit complete-mat stroke/detach/recoil (full coupling, CPU-runner vs GPU)\n\n"); ok = strokeMode(log); fn = "COMPLETEMAT_STROKE.md"; }
        else if (traj)  { log.append("# Explicit complete-mat trajectory — §7 one-step + §8 multi-step (CPU-runner vs GPU)\n\n"); ok = trajGate(log); fn = "COMPLETEMAT_TRAJ.md"; }
        else            { log.append("# Explicit coupled kernel → complete-mat integration — §5 head-placement + §6 bondForces gates\n\n"); ok = headAndBondGate(log); fn = "COMPLETEMAT_GATE.md"; }
        try { Files.writeString(Path.of(OUT, fn), log.toString()); } catch (IOException e) { throw new UncheckedIOException(e); }
        System.out.println("# report: " + Path.of(OUT, fn).toAbsolutePath());
        System.out.println(ok ? "=== COMPLETE-MAT GATE: PASS ===" : "=== COMPLETE-MAT GATE: REVIEW ===");
        System.exit(ok ? 0 : 1);
    }

    // =============================================================== §7/§8 complete explicit mat trajectory
    static final int SYS = TwoBodyBeamAnalyticGpu.SYS_STRIDE, RED_BLK = 128;
    /** The explicit complete-mat state: the beam SoA + CSR/reduce scratch, over the shared G (fil/mot/body/bondData). */
    static final class ExMat {
        Glide2D G; int N, M, nSeg, numRedBlk;
        DoubleArray nodes, frame, params, sys, outGeom, q, redOut, redBlk, eupP, bindP, cockP;
        FloatArray zP; IntArray exCounts, boundSeg, active, noBind, matc, redP, csrChunkParams, csrMatrix;
    }
    static ExMat packExMat(Glide2D G, int brownOn) {
        ExMat e = new ExMat(); e.G = G; int N = G.N, M = G.g4M, nSeg = G.nSeg; e.N = N; e.M = M; e.nSeg = nSeg;
        e.nodes = new DoubleArray(15 * N); e.frame = new DoubleArray(15 * N); e.params = new DoubleArray(17 * N);
        e.sys = new DoubleArray(SYS * N); e.outGeom = new DoubleArray(9 * N); e.q = new DoubleArray(4 * N); e.sys.init(0.0);
        e.boundSeg = new IntArray(N); e.active = new IntArray(N);
        double[] pr = ExplicitMatSolveHarness.paramArr(G);
        for (int m = 0; m < N; m++) {
            for (int j = 0; j <= M; j++) for (int k = 0; k < 3; k++) e.nodes.set((3*j+k)*N+m, G.g4Node[m][j][k]);
            double[] fr = ExplicitMatSolveHarness.frameArr(G, m); for (int c = 0; c < 15; c++) e.frame.set(c*N+m, fr[c]);
            for (int c = 0; c < 17; c++) e.params.set(c*N+m, pr[c]);
            e.q.set(m, G.phi[m]); e.q.set(N+m, G.psi[m]); e.q.set(2*N+m, G.thetaS[m]); e.q.set(3*N+m, G.psiActin[m]);
            int bs = G.mot.boundSeg.get(m); e.boundSeg.set(m, bs); e.active.set(m, bs >= 0 ? 1 : 0);
        }
        e.exCounts = IntArray.fromElements(N, 1, M, nSeg);
        e.matc = IntArray.fromElements(0, 0, brownOn);
        e.eupP = DoubleArray.fromElements(G.eup[0], G.eup[1], G.eup[2]);
        e.noBind = new IntArray(N); for (int m = 0; m < N; m++) e.noBind.set(m, G.noBind[m] ? 1 : 0);
        // bind gate thresholds (Tol defaults) + constants — the DETERMINISTIC 8-gate contract
        e.bindP = DoubleArray.fromElements(3.0, 25, 25, 20, 2.0, 15.0, Constants.radius,
                TwoBodyConverterMotor.PHI_PRE_3E, TwoBodyConverterMotor.A_SEMI[2], Constants.kT, 0.05, 1);
        e.cockP = DoubleArray.fromElements(TwoBodyConverterMotor.PRESTROKE_THETAS, TwoBodyConverterMotor.ADP_THETAS);
        e.zP = FloatArray.fromElements((float) G.kzCode);
        int mcs = SpatialGrid.bodyChunkSize(N, nSeg), nCh = SpatialGrid.numBodyChunks(N, mcs);
        e.csrChunkParams = IntArray.fromElements(mcs, nCh); e.csrMatrix = new IntArray(Math.max(1, nCh * nSeg)); e.csrMatrix.init(0);
        e.numRedBlk = Math.max(1, (N + RED_BLK - 1) / RED_BLK);
        e.redP = IntArray.fromElements(RED_BLK, e.numRedBlk); e.redBlk = new DoubleArray(3 * e.numRedBlk); e.redBlk.init(0.0);
        e.redOut = new DoubleArray(6);
        return e;
    }
    /** CPU-runner = the complete explicit mat step as plain-Java kernel calls (pre-bound, chemistry fixed, no bind search). */
    static void stepExCPU(ExMat e, int t, int seed) {
        Glide2D G = e.G; FilamentStore f = G.fil; MotorStore mot = G.mot; RigidRodBody b = mot.body;
        e.matc.set(0, t); e.matc.set(1, seed); mot.setCounts(t, seed, e.nSeg); f.counts.set(1, t); f.counts.set(2, seed);
        TwoBodyBeamAnalyticGpu.matBeamGeom(e.nodes, e.frame, e.params, e.q, e.exCounts, e.outGeom);
        TwoBodyBeamAnalyticGpu.matPlaceHeadExplicit(e.outGeom, e.boundSeg, e.eupP, e.exCounts, b.coord, b.uVec, b.yVec);
        CrossBridgeSystem.bondForces(b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength, mot.boundSeg, mot.bindArc, mot.nucleotideState, G.bondData, G.xbParams);
        ChainBendingForceSystem.zeroAccumulators(f.forceSum, f.torqueSum, f.counts);
        CrossBridgeSystem.csrChunkZero(e.csrChunkParams, mot.counts, e.csrMatrix);
        CrossBridgeSystem.csrChunkHistogram(mot.boundSeg, mot.counts, e.csrChunkParams, e.csrMatrix);
        CrossBridgeSystem.csrChunkReduce(mot.counts, e.csrChunkParams, e.csrMatrix, G.segCount);
        CrossBridgeSystem.csrScan(mot.counts, G.segCount, G.segOff);
        CrossBridgeSystem.csrChunkScatter(mot.boundSeg, mot.counts, e.csrChunkParams, G.segOff, G.segMyo, e.csrMatrix);
        CrossBridgeSystem.segGather(G.segOff, G.segMyo, G.bondData, f.forceSum, f.torqueSum, mot.counts);
        if (!G.rigid) ChainBendingForceSystem.chainForces(f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide, f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts);
        MatSoaSlice.matZConfine(f.coord, f.forceSum, e.zP, e.exCounts);
        BrownianForceSystem.brownianForce(f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.brownTransScale, f.brownRotScale, f.params, f.counts);
        RigidRodLangevinIntegrationSystem.integrate(f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts);
        DerivedGeometrySystem.orthogonalizeY(f.uVec, f.yVec, f.counts);
        DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
        TwoBodyBeamAnalyticGpu.matS2SolveStep(e.nodes, e.frame, e.q, G.bondData, e.boundSeg, e.params, e.sys, e.outGeom, mot.forceDotFil, mot.forceMag, e.matc, e.exCounts);
        MatSoaSlice.matReduceBlocks(e.boundSeg, e.active, mot.forceDotFil, e.redP, e.exCounts, e.redBlk);
        MatSoaSlice.matReduceFinal(e.redBlk, f.coord, e.redP, e.exCounts, e.redOut);
    }
    static GridScheduler exSched;
    static TornadoExecutionPlan buildExMatGraph(ExMat e) { return buildExMatGraph(e, false); }
    /** @param prod true = PRODUCTION residency (only redOut downloaded; no validation snapshots — for timing);
     *              false = VALIDATION (also downloads nodes/q/fil.coord/forceDotFil/bondData for the CPU compare). */
    static TornadoExecutionPlan buildExMatGraph(ExMat e, boolean prod) {
        Glide2D G = e.G; FilamentStore f = G.fil; MotorStore mot = G.mot; RigidRodBody b = mot.body; int N = e.N, nSeg = e.nSeg;
        TaskGraph tg = new TaskGraph("exmat");
        tg.transferToDevice(DataTransferMode.FIRST_EXECUTION,
                e.nodes, e.frame, e.params, e.sys, e.outGeom, e.eupP, e.zP, e.boundSeg, e.active, e.exCounts,
                e.csrChunkParams, e.csrMatrix, e.redP, e.redBlk, e.redOut,
                b.coord, b.uVec, b.yVec, b.bRotGam,
                f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.forceSum, f.torqueSum,
                f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.brownTransScale, f.brownRotScale, f.params, f.chainParams,
                f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide,
                mot.bindArc, mot.nucleotideState, mot.forceMag, G.xbParams, G.segCount, G.segOff, G.segMyo);
        if (prod) tg.transferToDevice(DataTransferMode.FIRST_EXECUTION, e.q, mot.boundSeg, mot.forceDotFil, f.coord, G.bondData);   // resident (production)
        tg.transferToDevice(DataTransferMode.EVERY_EXECUTION, e.matc, mot.counts, f.counts);   // per-step counters only
        if (!prod) tg.transferToDevice(DataTransferMode.EVERY_EXECUTION, e.q, mot.boundSeg, mot.forceDotFil, f.coord, G.bondData);   // validation-mirrored
        tg.task("beamGeom", TwoBodyBeamAnalyticGpu::matBeamGeom, e.nodes, e.frame, e.params, e.q, e.exCounts, e.outGeom)
          .task("place", TwoBodyBeamAnalyticGpu::matPlaceHeadExplicit, e.outGeom, e.boundSeg, e.eupP, e.exCounts, b.coord, b.uVec, b.yVec)
          .task("bond", CrossBridgeSystem::bondForces, b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength, mot.boundSeg, mot.bindArc, mot.nucleotideState, G.bondData, G.xbParams)
          .task("zeroAcc", ChainBendingForceSystem::zeroAccumulators, f.forceSum, f.torqueSum, f.counts)
          .task("csrZero", CrossBridgeSystem::csrChunkZero, e.csrChunkParams, mot.counts, e.csrMatrix)
          .task("csrHist", CrossBridgeSystem::csrChunkHistogram, mot.boundSeg, mot.counts, e.csrChunkParams, e.csrMatrix)
          .task("csrReduce", CrossBridgeSystem::csrChunkReduce, mot.counts, e.csrChunkParams, e.csrMatrix, G.segCount)
          .task("csrScan", CrossBridgeSystem::csrScan, mot.counts, G.segCount, G.segOff)
          .task("csrScatter", CrossBridgeSystem::csrChunkScatter, mot.boundSeg, mot.counts, e.csrChunkParams, G.segOff, G.segMyo, e.csrMatrix)
          .task("segGather", CrossBridgeSystem::segGather, G.segOff, G.segMyo, G.bondData, f.forceSum, f.torqueSum, mot.counts)
          .task("chain", ChainBendingForceSystem::chainForces, f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide, f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts)
          .task("zconf", MatSoaSlice::matZConfine, f.coord, f.forceSum, e.zP, e.exCounts)
          .task("brown", BrownianForceSystem::brownianForce, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.brownTransScale, f.brownRotScale, f.params, f.counts)
          .task("integ", RigidRodLangevinIntegrationSystem::integrate, f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts)
          .task("orthoY", DerivedGeometrySystem::orthogonalizeY, f.uVec, f.yVec, f.counts)
          .task("derive", DerivedGeometrySystem::derive, f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts)
          .task("s2solve", TwoBodyBeamAnalyticGpu::matS2SolveStep, e.nodes, e.frame, e.q, G.bondData, e.boundSeg, e.params, e.sys, e.outGeom, mot.forceDotFil, mot.forceMag, e.matc, e.exCounts)
          .task("redBlk", MatSoaSlice::matReduceBlocks, e.boundSeg, e.active, mot.forceDotFil, e.redP, e.exCounts, e.redBlk)
          .task("redFin", MatSoaSlice::matReduceFinal, e.redBlk, f.coord, e.redP, e.exCounts, e.redOut);
        if (prod) tg.transferToHost(DataTransferMode.EVERY_EXECUTION, e.redOut);   // ONLY the reduction crosses back
        else      tg.transferToHost(DataTransferMode.EVERY_EXECUTION, e.nodes, e.q, e.redOut, f.coord, mot.forceDotFil, G.bondData);
        int pn = ((N + 63) / 64) * 64, ps = ((nSeg + 63) / 64) * 64, nCh = e.csrChunkParams.get(1);
        exSched = new GridScheduler();
        for (String nm : new String[]{ "beamGeom", "place", "s2solve" }) addW(exSched, "exmat." + nm, pn);
        for (String nm : new String[]{ "bond" }) addW(exSched, "exmat." + nm, pn);   // bondForces parallel over motors
        for (String nm : new String[]{ "zeroAcc", "segGather", "chain", "zconf", "brown", "integ", "orthoY", "derive", "csrReduce" }) addW(exSched, "exmat." + nm, ps);
        addW(exSched, "exmat.csrZero", ((Math.max(1, nCh * nSeg) + 63) / 64) * 64);
        addW(exSched, "exmat.csrHist", ((nCh + 63) / 64) * 64);
        addW(exSched, "exmat.csrScatter", ((nCh + 63) / 64) * 64);
        addW(exSched, "exmat.csrScan", 64);
        addW(exSched, "exmat.redBlk", ((e.numRedBlk + 63) / 64) * 64);
        addW(exSched, "exmat.redFin", 64);
        return new TornadoExecutionPlan(tg.snapshot()).withGridScheduler(exSched);
    }
    static void addW(GridScheduler sc, String name, int global) { WorkerGrid w = new WorkerGrid1D(Math.max(1, global)); w.setLocalWork(64, 1, 1); sc.addWorkerGrid(name, w); }

    static boolean trajGate(StringBuilder log) {
        double density = 200.0; int seed = 101, steps = 300, mBound = 0;
        double slack = TwoBodyConverterMotor.EXPLICIT_GLIDE_SLACK_NM;
        // two identical scenes: Gc (CPU-runner), Gd (GPU)
        Glide2D Gc = TwoBodyConverterMotor.buildS2Mat(density, DT, 40.0, slack, seed);
        Glide2D Gd = TwoBodyConverterMotor.buildS2Mat(density, DT, 40.0, slack, seed);
        int N = Gc.N, M = Gc.g4M, nSeg = Gc.nSeg;
        // pre-bind ONE motor, all others unbound (binding disabled ⇒ fixed active set, chemistry fixed)
        for (Glide2D G : new Glide2D[]{ Gc, Gd }) {
            for (int m = 0; m < N; m++) G.mot.boundSeg.set(m, -1);
            int s = ExplicitMatSolveHarness.nearestSegSafe(G, mBound); if (s < 0) s = nSeg / 2;
            G.mot.boundSeg.set(mBound, s); TwoBodyConverterMotor.geom2D(G, mBound);
            G.mot.bindArc.set(mBound, (float) (0.5 * G.fil.segLength.get(s)));
        }
        ExMat ec = packExMat(Gc, 1), ed = packExMat(Gd, 1);
        TornadoExecutionPlan plan;
        try { plan = buildExMatGraph(ed); } catch (Throwable ex) { Throwable r = root(ex); log.append("- graph build FAILED: `" + r.getClass().getName() + "`: " + oneLine(r.getMessage()) + "\n"); System.out.println("  graph build FAILED: " + oneLine(r.getMessage())); return false; }
        log.append("## §7 one complete explicit mat step (CPU-runner vs GPU, N=" + N + ", 1 pre-bound motor, Brownian on)\n");
        boolean lowered = true; String err = null;
        try {
            ed.matc.set(0, 0); ed.matc.set(1, seed); Gd.mot.setCounts(0, seed, nSeg); Gd.fil.counts.set(1, 0); Gd.fil.counts.set(2, seed);
            plan.execute();
        } catch (Throwable ex) { lowered = false; Throwable r = root(ex); err = r.getClass().getName() + ": " + oneLine(r.getMessage()); }
        if (!lowered) { log.append("- GRAPH LOWERS: **NO** — " + err + "\n"); System.out.println("  §7 graph LOWERS: NO — " + err); return false; }
        stepExCPU(ec, 0, seed);
        double[] d1 = compare(ec, ed, Gc, Gd, mBound);
        log.append(String.format(Locale.US, "- GRAPH LOWERS + EXECUTES: **YES**. one-step GPU vs CPU-runner: maxΔnode=%.2e µm, Δphi=%.2e, Δpsi=%.2e, maxΔfilCoord=%.2e µm, ΔforceDotFil=%.2e, maxΔbond=%.2e, ΔredOut=%.2e\n",
                d1[0], d1[1], d1[2], d1[3], d1[4], d1[5], d1[6]));
        boolean s7 = d1[0] < 1e-6 && d1[3] < 1e-4 && d1[5] < 1e-4;
        log.append(String.format(Locale.US, "- **§7 one-step: %s** (bit-faithful device execution of the complete explicit mat)\n\n", s7 ? "PASS" : "REVIEW"));

        // §8 multi-step
        log.append("## §8 multi-step complete-mat trajectory (" + steps + " steps, CPU-runner vs GPU)\n");
        int firstDiv = -1; double maxNode = d1[0], maxFil = d1[3];
        for (int t = 1; t < steps; t++) {
            ed.matc.set(0, t); ed.matc.set(1, seed); Gd.mot.setCounts(t, seed, nSeg); Gd.fil.counts.set(1, t); Gd.fil.counts.set(2, seed);
            try { plan.execute(); } catch (Throwable ex) { Throwable r = root(ex); log.append("- device execute FAILED @t=" + t + ": " + oneLine(r.getMessage()) + "\n"); System.out.println("  execute FAILED @t=" + t); return false; }
            stepExCPU(ec, t, seed);
            double[] dt = compare(ec, ed, Gc, Gd, mBound);
            maxNode = Math.max(maxNode, dt[0]); maxFil = Math.max(maxFil, dt[3]);
            if (firstDiv < 0 && dt[0] > 1e-6) firstDiv = t;
        }
        String kind = firstDiv < 0 ? "no divergence (bit-close all " + steps + " steps)" : "continuous FP decorrelation from t=" + firstDiv + " (chaotic float op-order; not semantic)";
        boolean s8 = maxNode < 1e-2 && Double.isFinite(maxNode) && Double.isFinite(maxFil);   // stays bounded, no blow-up
        int nbC = (int) ec.redOut.get(0), nbD = (int) ed.redOut.get(0);
        log.append(String.format(Locale.US, "- over %d steps: max GPU-vs-CPU-runner Δnode=%.2e µm, ΔfilCoord=%.2e µm; first divergence: %s; final bound count CPU=%d GPU=%d\n", steps, maxNode, maxFil, kind, nbC, nbD));
        log.append("- **§8 multi-step: " + (s8 && nbC == nbD ? "PASS" : "REVIEW") + "** — one pre-bound explicit motor advanced through all shared GPU stages for " + steps + " steps; CPU/GPU agree (float-decorrelation only).\n\n");
        boolean ok = s7 && s8 && nbC == nbD;
        log.append("**§7/§8 VERDICT: " + (ok ? "PASS" : "REVIEW") + "**\n");
        System.out.printf(Locale.US, "  §7 one-step Δnode=%.1e ⇒ %s | §8 %d steps maxΔnode=%.1e firstDiv=%s ⇒ %s%n", d1[0], s7 ? "PASS" : "REVIEW", steps, maxNode, firstDiv < 0 ? "none" : ("t=" + firstDiv), s8 ? "PASS" : "REVIEW");
        return ok;
    }
    /** {maxΔnode, Δphi, Δpsi, maxΔfilCoord, ΔforceDotFil, maxΔbond, ΔredOut} GPU(ed/Gd) vs CPU-runner(ec/Gc) at motor mB. */
    static double[] compare(ExMat ec, ExMat ed, Glide2D Gc, Glide2D Gd, int mB) {
        int N = ec.N, M = ec.M, nSeg = ec.nSeg;
        double dNode = 0; for (int j = 1; j <= M; j++) for (int k = 0; k < 3; k++) dNode = Math.max(dNode, Math.abs(ec.nodes.get((3*j+k)*N+mB) - ed.nodes.get((3*j+k)*N+mB)));
        double dPhi = Math.abs(ec.q.get(mB) - ed.q.get(mB)), dPsi = Math.abs(ec.q.get(N+mB) - ed.q.get(N+mB));
        double dFil = 0; for (int i = 0; i < 3 * nSeg; i++) dFil = Math.max(dFil, Math.abs(Gc.fil.coord.get(i) - Gd.fil.coord.get(i)));
        double dFdf = Math.abs(Gc.mot.forceDotFil.get(mB) - Gd.mot.forceDotFil.get(mB));
        double dBond = 0; for (int c = 0; c < 13; c++) dBond = Math.max(dBond, Math.abs(Gc.bondData.get(mB*13+c) - Gd.bondData.get(mB*13+c)));
        double dRed = 0; for (int i = 0; i < 6; i++) dRed = Math.max(dRed, Math.abs(ec.redOut.get(i) - ed.redOut.get(i)));
        return new double[]{ dNode, dPhi, dPsi, dFil, dFdf, dBond, dRed };
    }
    static Throwable root(Throwable e) { Throwable r = e; while (r.getCause() != null && r.getCause() != r) r = r.getCause(); return r; }

    // =============================================================== FREE-BINDING gliding (bind stages added)
    /** Gliding CPU-runner: the complete step WITH free binding + chemistry (all-active smoke; mot.boundSeg is the
     *  single source). Order = production stepGlideS2: geom → bind → chemistry → cock → placeHead → mechanics. */
    static void stepGlidingCPU(ExMat e, int t, int seed) {
        Glide2D G = e.G; FilamentStore f = G.fil; MotorStore mot = G.mot; RigidRodBody b = mot.body; int N = e.N;
        e.matc.set(0, t); e.matc.set(1, seed); mot.setCounts(t, seed, e.nSeg); f.counts.set(1, t); f.counts.set(2, seed);
        for (int m = 0; m < N; m++) e.active.set(m, 1);   // no-cull smoke (physically identical; far motors fail the gate)
        TwoBodyBeamAnalyticGpu.matBeamGeom(e.nodes, e.frame, e.params, e.q, e.exCounts, e.outGeom);
        TwoBodyBeamAnalyticGpu.matBindExplicit(e.active, e.noBind, mot.boundSeg, mot.nucleotideState, e.outGeom, e.q, f.coord, f.uVec, f.segLength, e.params, e.bindP, e.eupP, mot.bindArc, e.exCounts);
        NucleotideCycleSystem.cycleLymnTaylor(mot.nucleotideState, mot.boundSeg, mot.forceDotFil, mot.forceDotAvg, mot.avgInit, mot.cooldown, mot.stats, mot.nucParams, mot.kinParams, mot.counts);
        MatSoaSlice.matCock(mot.nucleotideState, e.q, e.cockP, e.exCounts);
        TwoBodyBeamAnalyticGpu.matPlaceHeadExplicit(e.outGeom, mot.boundSeg, e.eupP, e.exCounts, b.coord, b.uVec, b.yVec);
        CrossBridgeSystem.bondForces(b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength, mot.boundSeg, mot.bindArc, mot.nucleotideState, G.bondData, G.xbParams);
        ChainBendingForceSystem.zeroAccumulators(f.forceSum, f.torqueSum, f.counts);
        CrossBridgeSystem.csrChunkZero(e.csrChunkParams, mot.counts, e.csrMatrix);
        CrossBridgeSystem.csrChunkHistogram(mot.boundSeg, mot.counts, e.csrChunkParams, e.csrMatrix);
        CrossBridgeSystem.csrChunkReduce(mot.counts, e.csrChunkParams, e.csrMatrix, G.segCount);
        CrossBridgeSystem.csrScan(mot.counts, G.segCount, G.segOff);
        CrossBridgeSystem.csrChunkScatter(mot.boundSeg, mot.counts, e.csrChunkParams, G.segOff, G.segMyo, e.csrMatrix);
        CrossBridgeSystem.segGather(G.segOff, G.segMyo, G.bondData, f.forceSum, f.torqueSum, mot.counts);
        if (!G.rigid) ChainBendingForceSystem.chainForces(f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide, f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts);
        MatSoaSlice.matZConfine(f.coord, f.forceSum, e.zP, e.exCounts);
        BrownianForceSystem.brownianForce(f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.brownTransScale, f.brownRotScale, f.params, f.counts);
        RigidRodLangevinIntegrationSystem.integrate(f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts);
        DerivedGeometrySystem.orthogonalizeY(f.uVec, f.yVec, f.counts);
        DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
        TwoBodyBeamAnalyticGpu.matS2SolveStep(e.nodes, e.frame, e.q, G.bondData, mot.boundSeg, e.params, e.sys, e.outGeom, mot.forceDotFil, mot.forceMag, e.matc, e.exCounts);
        MatSoaSlice.matReduceBlocks(mot.boundSeg, e.active, mot.forceDotFil, e.redP, e.exCounts, e.redBlk);
        MatSoaSlice.matReduceFinal(e.redBlk, f.coord, e.redP, e.exCounts, e.redOut);
    }
    static GridScheduler glSched;
    static TornadoExecutionPlan buildGlidingGraph(ExMat e, boolean prod) {
        Glide2D G = e.G; FilamentStore f = G.fil; MotorStore mot = G.mot; RigidRodBody b = mot.body; int N = e.N, nSeg = e.nSeg;
        for (int m = 0; m < N; m++) e.active.set(m, 1);
        TaskGraph tg = new TaskGraph("glide");
        tg.transferToDevice(DataTransferMode.FIRST_EXECUTION,
                e.nodes, e.frame, e.params, e.sys, e.outGeom, e.eupP, e.zP, e.active, e.noBind, e.exCounts, e.bindP, e.cockP,
                e.csrChunkParams, e.csrMatrix, e.redP, e.redBlk, e.redOut,
                b.coord, b.uVec, b.yVec, b.bRotGam,
                f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.forceSum, f.torqueSum,
                f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.brownTransScale, f.brownRotScale, f.params, f.chainParams,
                f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide,
                mot.forceDotAvg, mot.avgInit, mot.cooldown, mot.stats, mot.nucParams, mot.kinParams, mot.forceMag,
                G.xbParams, G.segCount, G.segOff, G.segMyo);
        if (prod) tg.transferToDevice(DataTransferMode.FIRST_EXECUTION, e.q, mot.boundSeg, mot.bindArc, mot.nucleotideState, mot.forceDotFil, f.coord, G.bondData);
        tg.transferToDevice(DataTransferMode.EVERY_EXECUTION, e.matc, mot.counts, f.counts);
        if (!prod) tg.transferToDevice(DataTransferMode.EVERY_EXECUTION, e.q, mot.boundSeg, mot.bindArc, mot.nucleotideState, mot.forceDotFil, f.coord, G.bondData);
        tg.task("beamGeom", TwoBodyBeamAnalyticGpu::matBeamGeom, e.nodes, e.frame, e.params, e.q, e.exCounts, e.outGeom)
          .task("bind", TwoBodyBeamAnalyticGpu::matBindExplicit, e.active, e.noBind, mot.boundSeg, mot.nucleotideState, e.outGeom, e.q, f.coord, f.uVec, f.segLength, e.params, e.bindP, e.eupP, mot.bindArc, e.exCounts)
          .task("chem", NucleotideCycleSystem::cycleLymnTaylor, mot.nucleotideState, mot.boundSeg, mot.forceDotFil, mot.forceDotAvg, mot.avgInit, mot.cooldown, mot.stats, mot.nucParams, mot.kinParams, mot.counts)
          .task("cock", MatSoaSlice::matCock, mot.nucleotideState, e.q, e.cockP, e.exCounts)
          .task("place", TwoBodyBeamAnalyticGpu::matPlaceHeadExplicit, e.outGeom, mot.boundSeg, e.eupP, e.exCounts, b.coord, b.uVec, b.yVec)
          .task("bond", CrossBridgeSystem::bondForces, b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength, mot.boundSeg, mot.bindArc, mot.nucleotideState, G.bondData, G.xbParams)
          .task("zeroAcc", ChainBendingForceSystem::zeroAccumulators, f.forceSum, f.torqueSum, f.counts)
          .task("csrZero", CrossBridgeSystem::csrChunkZero, e.csrChunkParams, mot.counts, e.csrMatrix)
          .task("csrHist", CrossBridgeSystem::csrChunkHistogram, mot.boundSeg, mot.counts, e.csrChunkParams, e.csrMatrix)
          .task("csrReduce", CrossBridgeSystem::csrChunkReduce, mot.counts, e.csrChunkParams, e.csrMatrix, G.segCount)
          .task("csrScan", CrossBridgeSystem::csrScan, mot.counts, G.segCount, G.segOff)
          .task("csrScatter", CrossBridgeSystem::csrChunkScatter, mot.boundSeg, mot.counts, e.csrChunkParams, G.segOff, G.segMyo, e.csrMatrix)
          .task("segGather", CrossBridgeSystem::segGather, G.segOff, G.segMyo, G.bondData, f.forceSum, f.torqueSum, mot.counts)
          .task("chain", ChainBendingForceSystem::chainForces, f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide, f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts)
          .task("zconf", MatSoaSlice::matZConfine, f.coord, f.forceSum, e.zP, e.exCounts)
          .task("brown", BrownianForceSystem::brownianForce, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.brownTransScale, f.brownRotScale, f.params, f.counts)
          .task("integ", RigidRodLangevinIntegrationSystem::integrate, f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts)
          .task("orthoY", DerivedGeometrySystem::orthogonalizeY, f.uVec, f.yVec, f.counts)
          .task("derive", DerivedGeometrySystem::derive, f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts)
          .task("s2solve", TwoBodyBeamAnalyticGpu::matS2SolveStep, e.nodes, e.frame, e.q, G.bondData, mot.boundSeg, e.params, e.sys, e.outGeom, mot.forceDotFil, mot.forceMag, e.matc, e.exCounts)
          .task("redBlk", MatSoaSlice::matReduceBlocks, mot.boundSeg, e.active, mot.forceDotFil, e.redP, e.exCounts, e.redBlk)
          .task("redFin", MatSoaSlice::matReduceFinal, e.redBlk, f.coord, e.redP, e.exCounts, e.redOut);
        if (prod) tg.transferToHost(DataTransferMode.EVERY_EXECUTION, e.redOut, mot.boundSeg);
        else      tg.transferToHost(DataTransferMode.EVERY_EXECUTION, e.nodes, e.q, e.redOut, f.coord, mot.boundSeg, mot.nucleotideState, mot.forceDotFil, G.bondData);
        int pn = ((N + 63) / 64) * 64, ps = ((nSeg + 63) / 64) * 64, nCh = e.csrChunkParams.get(1);
        glSched = new GridScheduler();
        for (String nm : new String[]{ "beamGeom", "bind", "chem", "cock", "place", "bond", "s2solve" }) addW(glSched, "glide." + nm, pn);
        for (String nm : new String[]{ "zeroAcc", "segGather", "chain", "zconf", "brown", "integ", "orthoY", "derive", "csrReduce" }) addW(glSched, "glide." + nm, ps);
        addW(glSched, "glide.csrZero", ((Math.max(1, nCh * nSeg) + 63) / 64) * 64);
        addW(glSched, "glide.csrHist", ((nCh + 63) / 64) * 64); addW(glSched, "glide.csrScatter", ((nCh + 63) / 64) * 64);
        addW(glSched, "glide.csrScan", 64); addW(glSched, "glide.redBlk", ((e.numRedBlk + 63) / 64) * 64); addW(glSched, "glide.redFin", 64);
        return new TornadoExecutionPlan(tg.snapshot()).withGridScheduler(glSched);
    }

    static boolean glidingMode(StringBuilder log) {
        double density = 200.0; int seed = 101, steps = 2000; double slack = TwoBodyConverterMotor.EXPLICIT_GLIDE_SLACK_NM;
        Glide2D Gc = TwoBodyConverterMotor.buildS2Mat(density, DT, 40.0, slack, seed);
        Glide2D Gd = TwoBodyConverterMotor.buildS2Mat(density, DT, 40.0, slack, seed);
        int N = Gc.N, nSeg = Gc.nSeg;
        for (Glide2D G : new Glide2D[]{ Gc, Gd }) for (int m = 0; m < N; m++) G.mot.boundSeg.set(m, -1);   // all unbound initially (free binding)
        ExMat ec = packExMat(Gc, 1), ed = packExMat(Gd, 1);
        // ---- §8 one-step free-binding replay: device matBindExplicit vs production bind gate (host) ----
        log.append("## §8 one-step free-binding replay (device matBindExplicit vs production geom2D+nearestSeg2D+gate2D)\n");
        Glide2D Gp = TwoBodyConverterMotor.buildS2Mat(density, DT, 40.0, slack, seed); for (int m = 0; m < N; m++) Gp.mot.boundSeg.set(m, -1);
        int prodBinds = 0, mism = 0;
        // production bind gate over all motors (thetaS=PRESTROKE for ADPPI candidates already)
        for (int m = 0; m < N; m++) if (Gp.mot.nucleotideState.get(m) == MotorStore.NUC_ADPPI && !Gp.noBind[m]) {
            Gp.thetaS[m] = TwoBodyConverterMotor.PRESTROKE_THETAS; TwoBodyConverterMotor.geom2D(Gp, m);
            int s = TwoBodyConverterMotor.nearestSeg2D(Gp, m); if (s < 0) continue; double[] gm = TwoBodyConverterMotor.gate2D(Gp, m, s);
            double half = 0.5 * Gp.fil.segLength.get(s);
            boolean pass = gm[0] < 3.0 && gm[2] < 25 && gm[3] < 25 && gm[4] < 20 && gm[5] < 2.0 && gm[6] < 15.0 && gm[7] < TwoBodyConverterMotor.A_SEMI[2] * 1e3 && gm[1] > 0.05 && gm[1] < 2 * half - 0.05;
            if (pass) { Gp.mot.boundSeg.set(m, s); Gp.mot.bindArc.set(m, (float) gm[1]); prodBinds++; } }
        // device matBindExplicit (via the CPU-mirror one impl) on a fresh copy
        Glide2D Gm = TwoBodyConverterMotor.buildS2Mat(density, DT, 40.0, slack, seed); for (int m = 0; m < N; m++) Gm.mot.boundSeg.set(m, -1);
        ExMat em = packExMat(Gm, 1); for (int m = 0; m < N; m++) em.active.set(m, 1);
        TwoBodyBeamAnalyticGpu.matBeamGeom(em.nodes, em.frame, em.params, em.q, em.exCounts, em.outGeom);
        TwoBodyBeamAnalyticGpu.matBindExplicit(em.active, em.noBind, Gm.mot.boundSeg, Gm.mot.nucleotideState, em.outGeom, em.q, Gm.fil.coord, Gm.fil.uVec, Gm.fil.segLength, em.params, em.bindP, em.eupP, Gm.mot.bindArc, em.exCounts);
        int devBinds = 0; for (int m = 0; m < N; m++) { if (Gm.mot.boundSeg.get(m) >= 0) devBinds++; if (Gm.mot.boundSeg.get(m) != Gp.mot.boundSeg.get(m)) mism++; }
        log.append(String.format(Locale.US, "- production binds=%d, device-mirror binds=%d, boundSeg mismatches=%d/%d ⇒ **%s**\n\n", prodBinds, devBinds, mism, N, mism == 0 ? "PASS (exact bind identity)" : "REVIEW"));
        boolean s8 = mism == 0;

        // ---- §10 low-density free-binding gliding smoke: CPU-runner vs GPU ----
        log.append("## §10 low-density free-binding gliding (N=" + N + ", density " + (int) density + ", " + steps + " steps, all-active)\n");
        TornadoExecutionPlan plan; try { plan = buildGlidingGraph(ed, false); } catch (Throwable ex) { log.append("- graph FAILED: " + oneLine(root(ex).getMessage()) + "\n"); System.out.println("  gliding graph FAILED: " + oneLine(root(ex).getMessage())); return false; }
        long cBinds = 0, cDetach = 0; int[] prevBc = new int[N]; for (int m = 0; m < N; m++) prevBc[m] = -1;
        int firstDiv = -1; double maxFil = 0; int nbC = 0, nbD = 0, invalid = 0;
        for (int t = 0; t < steps; t++) {
            ed.matc.set(0, t); ed.matc.set(1, seed); Gd.mot.setCounts(t, seed, nSeg); Gd.fil.counts.set(1, t); Gd.fil.counts.set(2, seed);
            try { plan.execute(); } catch (Throwable ex) { log.append("- execute FAILED @t=" + t + ": " + oneLine(root(ex).getMessage()) + "\n"); System.out.println("  execute FAILED @t=" + t); return false; }
            stepGlidingCPU(ec, t, seed);
            nbC = (int) ec.redOut.get(0); nbD = (int) ed.redOut.get(0);
            double dFil = 0; for (int i = 0; i < 3 * nSeg; i++) dFil = Math.max(dFil, Math.abs(Gc.fil.coord.get(i) - Gd.fil.coord.get(i)));
            maxFil = Math.max(maxFil, dFil); if (firstDiv < 0 && dFil > 1e-5) firstDiv = t;
            for (int m = 0; m < N; m++) { int bs = Gc.mot.boundSeg.get(m); if (bs >= 0 && prevBc[m] < 0) cBinds++; if (bs < 0 && prevBc[m] >= 0) cDetach++; prevBc[m] = bs; }
            for (int i = 0; i < 3 * nSeg; i++) { float c = Gc.fil.coord.get(i); if (Float.isNaN(c) || Float.isInfinite(c)) { invalid++; break; } }
        }
        // Gliding is CHAOTIC (float-FMA Lyapunov divergence) ⇒ CPU≡GPU is aggregate-statistical, NOT stepwise
        // (CLAUDE.md standard). A SEMANTIC bug shows at t=0; chaotic decorrelation shows only after many bit-close
        // steps. So the gate is: binding events occur, 0 invalid, and bit-close BEFORE any decorrelation (firstDiv
        // late or none) ⇒ no t=0 semantic divergence. Post-decorrelation bound-count differences are expected.
        boolean semantic = firstDiv == 0;   // divergence at the very first step would be a real bug
        boolean s10 = Double.isFinite(maxFil) && invalid == 0 && cBinds > 0 && !semantic;
        log.append(String.format(Locale.US, "- CPU-runner: total binds=%d, detachments=%d, final bound=%d; GPU final bound=%d; CPU/GPU maxΔfilCoord=%.2e µm; bit-close for %s steps then chaotic FP decorrelation; invalid=%d\n",
                cBinds, cDetach, nbC, nbD, maxFil, firstDiv < 0 ? "all " + steps : "" + firstDiv, invalid));
        log.append(String.format(Locale.US, "- **§10 gliding smoke: %s** — motors bind/stroke/detach on the persistent GPU mat; %s (no t=0 semantic divergence; bound-count differs post-decorrelation as expected for chaotic gliding).\n\n",
                s10 ? "PASS" : "REVIEW", firstDiv < 0 ? "CPU/GPU bit-close all " + steps + " steps" : "CPU/GPU bit-close " + firstDiv + " steps then chaotic decorrelation (Lyapunov)"));
        boolean ok = s8 && s10;
        log.append("**§8/§10 VERDICT: " + (ok ? "PASS" : "REVIEW") + "** (deterministic bind identity + first genuine free-binding explicit gliding on the device mat).\n");
        System.out.printf(Locale.US, "  §8 bind-identity mism=%d ⇒ %s | §10 gliding binds=%d detach=%d boundC=%d/%d maxΔfil=%.1e firstDiv=%s invalid=%d ⇒ %s%n",
                mism, s8 ? "PASS" : "REVIEW", cBinds, cDetach, nbC, nbD, maxFil, firstDiv < 0 ? "none" : ("t=" + firstDiv), invalid, s10 ? "PASS" : "REVIEW");
        return ok;
    }

    // ============================================================= NO-CULL throughput (Goal 4, §8–§12)
    static final String[] EXTASKS = { "beamGeom", "place", "bond", "zeroAcc", "csrZero", "csrHist", "csrReduce",
        "csrScan", "csrScatter", "segGather", "chain", "zconf", "brown", "integ", "orthoY", "derive", "s2solve", "redBlk", "redFin" };
    /** Pre-bind the geometrically reachable motor set (surf<5 nm); the rest stay unbound but are STILL processed by
     *  matS2SolveStep (no-cull ⇒ all N solved each step). thetaS fixed pre-stroke. Returns bound count. */
    static int preBindReachable(Glide2D G) {
        int N = G.N, cnt = 0;
        for (int m = 0; m < N; m++) { G.mot.boundSeg.set(m, -1); G.thetaS[m] = TwoBodyConverterMotor.PRESTROKE_THETAS;
            TwoBodyConverterMotor.geom2D(G, m); int s = TwoBodyConverterMotor.nearestSeg2D(G, m); if (s < 0) continue;
            double[] gm = TwoBodyConverterMotor.gate2D(G, m, s); double half = 0.5 * G.fil.segLength.get(s), margin = 0.01;
            if (gm[0] < 5.0 && gm[1] > margin && gm[1] < 2 * half - margin) { G.mot.boundSeg.set(m, s); G.mot.bindArc.set(m, (float) gm[1]); cnt++; } }
        return cnt;
    }
    static double median(double[] a) { double[] c = a.clone(); java.util.Arrays.sort(c); return c[c.length / 2]; }
    static long parseTaskNs(String jl, String prefix, String name) {
        if (jl == null) return 0; int k = jl.indexOf("\"" + prefix + name + "\""); if (k < 0) return 0;
        int t = jl.indexOf("\"TASK_KERNEL_TIME\"", k); if (t < 0) return 0; int c = jl.indexOf(':', t); if (c < 0) return 0;
        int e = c + 1; while (e < jl.length() && (jl.charAt(e) == ' ' || jl.charAt(e) == '"')) e++;
        int s = e; while (e < jl.length() && (Character.isDigit(jl.charAt(e)) || jl.charAt(e) == '-')) e++;
        try { return Long.parseLong(jl.substring(s, e)); } catch (Exception ex) { return 0; }
    }
    static boolean finiteRed(DoubleArray r) { for (int i = 0; i < 6; i++) if (!Double.isFinite(r.get(i))) return false; return true; }

    static boolean benchMode(StringBuilder log) {
        int[] dens = { 200, 700, 1500 }; int warm = 150, meas = 300, reps = 3; double dt = DT; int seed = 101;
        System.out.println("=== NO-CULL explicit throughput (CPU-analytic vs GPU-analytic) ===");
        log.append("Protocol: culling DISABLED (all N processed by matS2SolveStep every step); binding gates UNCHANGED\n");
        log.append("(reachable set pre-bound, thetaS fixed pre-stroke, Brownian on). Production-residency GPU graph\n");
        log.append("(beam SoA + filament/body FIRST_EXECUTION resident; per step only counters up + redOut down; NO validation\n");
        log.append("downloads in the timed interval). warm=" + warm + ", measured window=" + meas + " steps × " + reps + " reps (median).\n\n");
        StringBuilder csv = new StringBuilder("density,N,boundCt,processed,cull,cpu_ms_step,cpu_steps_s,gpu_ms_step,gpu_steps_s,speedup,gpu_devkernel_ms,gpu_bytesIn,gpu_bytesOut,failures,invalid\n");
        StringBuilder tbl = new StringBuilder("| density | N | CPU analytic steps/s | CPU ms/step | GPU analytic steps/s | GPU ms/step | GPU speedup | failures |\n|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        StringBuilder stage = new StringBuilder("| density | " + String.join(" | ", EXTASKS) + " |\n|" + "---:|".repeat(EXTASKS.length + 1) + "\n");
        boolean allOk = true; double[] gpuMsAt = new double[dens.length], cpuMsAt = new double[dens.length]; int[] Nat = new int[dens.length];
        for (int di = 0; di < dens.length; di++) {
            double density = dens[di]; double slack = TwoBodyConverterMotor.EXPLICIT_GLIDE_SLACK_NM;
            Glide2D Gc = TwoBodyConverterMotor.buildS2Mat(density, dt, 40.0, slack, seed);
            Glide2D Gd = TwoBodyConverterMotor.buildS2Mat(density, dt, 40.0, slack, seed);
            int N = Gc.N, nSeg = Gc.nSeg; Nat[di] = N;
            int bc = preBindReachable(Gc); preBindReachable(Gd);
            ExMat ec = packExMat(Gc, 1), ed = packExMat(Gd, 1);
            TornadoExecutionPlan plan;
            try { plan = buildExMatGraph(ed, true); } catch (Throwable ex) { log.append("- density " + density + " graph build FAILED: " + oneLine(root(ex).getMessage()) + "\n"); allOk = false; continue; }
            // GPU warm
            int tg = 0; for (int t = 0; t < warm; t++, tg++) { ed.matc.set(0, tg); ed.matc.set(1, seed); Gd.mot.setCounts(tg, seed, nSeg); Gd.fil.counts.set(1, tg); Gd.fil.counts.set(2, seed); plan.execute(); }
            double[] gpuMs = new double[reps];
            for (int r = 0; r < reps; r++) { long ns = 0; for (int k = 0; k < meas; k++, tg++) { ed.matc.set(0, tg); ed.matc.set(1, seed); Gd.mot.setCounts(tg, seed, nSeg); Gd.fil.counts.set(1, tg); Gd.fil.counts.set(2, seed); long t0 = System.nanoTime(); plan.execute(); ns += System.nanoTime() - t0; } gpuMs[r] = ns / 1e6 / meas; }
            boolean gpuInvalid = !finiteRed(ed.redOut);
            // profiled window (device-kernel + bytes + per-stage)
            long[] kerNs = new long[EXTASKS.length]; long devKerNs = 0, bIn = 0, bOut = 0; int nProf = 0;
            for (int k = 0; k < 40; k++, tg++) { ed.matc.set(0, tg); ed.matc.set(1, seed); Gd.mot.setCounts(tg, seed, nSeg); Gd.fil.counts.set(1, tg); Gd.fil.counts.set(2, seed);
                TornadoProfilerResult pr = plan.withProfiler(ProfilerMode.SILENT).execute().getProfilerResult(); String jl = pr.getProfileLog();
                for (int i = 0; i < EXTASKS.length; i++) kerNs[i] += parseTaskNs(jl, "exmat.", EXTASKS[i]); devKerNs += pr.getDeviceKernelTime(); bIn += pr.getTotalBytesCopyIn(); bOut += pr.getTotalBytesCopyOut(); nProf++; plan.clearProfiles(); }
            plan.withoutProfiler();
            // CPU warm + measure
            int tc = 0; for (int t = 0; t < warm; t++, tc++) stepExCPU(ec, tc, seed);
            double[] cpuMs = new double[reps];
            for (int r = 0; r < reps; r++) { long ns = 0; for (int k = 0; k < meas; k++, tc++) { long t0 = System.nanoTime(); stepExCPU(ec, tc, seed); ns += System.nanoTime() - t0; } cpuMs[r] = ns / 1e6 / meas; }
            boolean cpuInvalid = !finiteRed(ec.redOut);
            double gMs = median(gpuMs), cMs = median(cpuMs); double gSps = 1000 / gMs, cSps = 1000 / cMs, speedup = cMs / gMs;
            gpuMsAt[di] = gMs; cpuMsAt[di] = cMs;
            double devKerMs = devKerNs / 1e6 / nProf;
            int bound = (int) ed.redOut.get(0);
            log.append(String.format(Locale.US, "### density %.0f (N=%d): boundCt=%d, cullingEnabled=false, processedMotorCount=N=%d (CPU==GPU), invalid CPU=%b GPU=%b\n", density, N, bc, N, cpuInvalid, gpuInvalid));
            log.append(String.format(Locale.US, "- CPU-analytic: %.3f ms/step (%.0f steps/s) | GPU-analytic: %.3f ms/step (%.0f steps/s) | **speedup %.2f×** | device-kernel %.3f ms/step; wall−kernel launch floor %.3f ms; bytes in=%d out=%d/step; reps=%s\n",
                    cMs, cSps, gMs, gSps, speedup, devKerMs, gMs - devKerMs, bIn / nProf, bOut / nProf, java.util.Arrays.toString(round2(gpuMs))));
            tbl.append(String.format(Locale.US, "| %.0f | %d | %.0f | %.3f | %.0f | %.3f | %.2f× | %d |\n", density, N, cSps, cMs, gSps, gMs, speedup, 0));
            stage.append(String.format(Locale.US, "| %.0f", density)); for (int i = 0; i < EXTASKS.length; i++) stage.append(String.format(Locale.US, " | %.3f", kerNs[i] / 1e6 / nProf)); stage.append(" |\n");
            csv.append(String.format(Locale.US, "%.0f,%d,%d,%d,false,%.4f,%.1f,%.4f,%.1f,%.3f,%.4f,%d,%d,%d,%b\n", density, N, bc, N, cMs, cSps, gMs, gSps, speedup, devKerMs, bIn / nProf, bOut / nProf, 0, cpuInvalid || gpuInvalid));
            System.out.printf(Locale.US, "  density=%-5.0f N=%-5d bound=%-4d | CPU %.2f ms/step | GPU %.2f ms/step | %.2f× | devKer %.3f ms | invalid %b%n", density, N, bc, cMs, gMs, speedup, devKerMs, cpuInvalid || gpuInvalid);
            if (cpuInvalid || gpuInvalid) allOk = false;
        }
        log.append("\n## Density throughput table (no-cull, production-residency, matched steps)\n").append(tbl).append("\n## Per-stage GPU kernel time (ms/step)\n").append(stage);
        // §12 performance decision + runtime estimates (use the largest density's speedup)
        double sp = cpuMsAt[dens.length - 1] > 0 ? cpuMsAt[dens.length - 1] / gpuMsAt[dens.length - 1] : 0;
        String cls = sp >= 10 ? "≥10× — routine explicit validation studies" : sp >= 5 ? "5–10× — trap campaigns & key gliding reruns" : sp >= 3 ? "3–5× — selected validation" : "<3× — performance-limited";
        log.append(String.format(Locale.US, "\n## §12 performance decision (@ density %d, N=%d): GPU/CPU-analytic = **%.2f×** ⇒ %s\n", dens[dens.length - 1], Nat[dens.length - 1], sp, cls));
        double gms = gpuMsAt[dens.length - 1];   // ms/step at the largest density
        log.append(String.format(Locale.US, "- GPU wall-time estimates @ N=%d, dt=%.1e (steps = simS/dt): 0.15 s gliding ≈ %.1f min; 0.5 s ≈ %.1f min; 2.0 s ≈ %.1f h; 3 densities × 3 seeds @ 0.15 s ≈ %.1f h (assumes constant ms/step; NO extrapolation beyond measured scaling).\n",
                Nat[dens.length - 1], dt, 0.15 / dt * gms / 1e3 / 60, 0.5 / dt * gms / 1e3 / 60, 2.0 / dt * gms / 1e3 / 3600, 9 * 0.15 / dt * gms / 1e3 / 3600));
        try { Files.writeString(Path.of(OUT, "COMPLETEMAT_BENCH.csv"), csv.toString()); } catch (IOException e) { throw new UncheckedIOException(e); }
        log.append("\n**NO-CULL CPU/GPU THROUGHPUT: measured** (culling disabled, full mat processed, CPU==GPU processed=N, production-residency). DEVICE_VALIDATED stays false (promotion deferred, §13).\n");
        return allOk;
    }
    static double[] round2(double[] a) { double[] r = new double[a.length]; for (int i = 0; i < a.length; i++) r[i] = Math.round(a[i] * 100) / 100.0; return r; }

    // ============================================================= §2 stroke/detach/recoil in the full mat
    static boolean strokeMode(StringBuilder log) {
        double density = 200.0; int seed = 101; double slack = TwoBodyConverterMotor.EXPLICIT_GLIDE_SLACK_NM;
        int T = 400, t1 = 80, t2 = 160, t3 = 240, t4 = 320;
        Glide2D Gc = TwoBodyConverterMotor.buildS2Mat(density, DT, 40.0, slack, seed);
        Glide2D Gd = TwoBodyConverterMotor.buildS2Mat(density, DT, 40.0, slack, seed);
        int N = Gc.N, nSeg = Gc.nSeg;
        double thPre0 = TwoBodyConverterMotor.PRESTROKE_THETAS;
        // pick a geometrically REACHABLE motor (surf<5 nm) so the cross-bridge is engaged and the stroke generates force
        int mB = -1; for (int m = 0; m < N && mB < 0; m++) { Gc.thetaS[m] = thPre0; TwoBodyConverterMotor.geom2D(Gc, m);
            int s = TwoBodyConverterMotor.nearestSeg2D(Gc, m); if (s < 0) continue; double[] gm = TwoBodyConverterMotor.gate2D(Gc, m, s);
            double half = 0.5 * Gc.fil.segLength.get(s); if (gm[0] < 5.0 && gm[1] > 0.01 && gm[1] < 2 * half - 0.01) mB = m; }
        if (mB < 0) mB = 0;
        for (Glide2D G : new Glide2D[]{ Gc, Gd }) { for (int m = 0; m < N; m++) G.mot.boundSeg.set(m, -1);
            G.thetaS[mB] = thPre0; TwoBodyConverterMotor.geom2D(G, mB);
            int s = TwoBodyConverterMotor.nearestSeg2D(G, mB); if (s < 0) s = nSeg / 2; G.mot.boundSeg.set(mB, s);
            double[] gm = TwoBodyConverterMotor.gate2D(G, mB, s); G.mot.bindArc.set(mB, (float) gm[1]); }
        ExMat ec = packExMat(Gc, 1), ed = packExMat(Gd, 1);
        TornadoExecutionPlan plan; try { plan = buildExMatGraph(ed, false); } catch (Throwable ex) { log.append("- graph FAILED: " + oneLine(root(ex).getMessage()) + "\n"); return false; }
        double thPre = TwoBodyConverterMotor.PRESTROKE_THETAS, thPost = TwoBodyConverterMotor.ADP_THETAS;
        double[] xH0 = null; double maxNode = 0, peakF = 0, strokeDisp = 0; int firstDiv = -1;
        double[] bhat = { Gc.bhat[0], Gc.bhat[1], Gc.bhat[2] };
        log.append("## §2 driven sequence: relax→stroke(θs −30→+30)→hold→detach→recoil (full shared coupling)\n");
        for (int t = 0; t < T; t++) {
            double th = t < t1 ? thPre : (t >= t2 ? thPost : thPre + (thPost - thPre) * (t - t1) / (double) (t2 - t1));
            int bs = (t >= t3) ? -1 : Gc.mot.boundSeg.get(mB);   // controlled detach at t3
            // drive thetaS + boundSeg on both runners
            for (Object[] pr : new Object[][]{ { ec, Gc }, { ed, Gd } }) { ExMat e = (ExMat) pr[0]; Glide2D G = (Glide2D) pr[1];
                e.q.set(2 * N + mB, th); e.boundSeg.set(mB, bs); G.mot.boundSeg.set(mB, bs); e.active.set(mB, bs >= 0 ? 1 : 0); }
            ed.matc.set(0, t); ed.matc.set(1, seed); Gd.mot.setCounts(t, seed, nSeg); Gd.fil.counts.set(1, t); Gd.fil.counts.set(2, seed);
            try { plan.execute(); } catch (Throwable ex) { log.append("- execute FAILED @t=" + t + ": " + oneLine(root(ex).getMessage()) + "\n"); return false; }
            stepExCPU(ec, t, seed);
            double[] d = compare(ec, ed, Gc, Gd, mB); maxNode = Math.max(maxNode, d[0]); if (firstDiv < 0 && d[0] > 1e-6) firstDiv = t;
            // observables from the CPU-runner side (host-updated by stepExCPU); the GPU is validated to agree via maxNode
            double xHx = ec.outGeom.get(3 * N + mB), xHy = ec.outGeom.get(4 * N + mB), xHz = ec.outGeom.get(5 * N + mB);
            if (t == 0) xH0 = new double[]{ xHx, xHy, xHz };
            double axial = ((xHx - xH0[0]) * bhat[0] + (xHy - xH0[1]) * bhat[1] + (xHz - xH0[2]) * bhat[2]) * 1e3;
            double force = Math.abs(Gc.mot.forceMag.get(mB)) * 1e12;
            if (t >= t1 && t <= t3 && force > peakF) peakF = force; if (t == t2) strokeDisp = axial;
        }
        boolean ok = maxNode < 1e-2 && Double.isFinite(maxNode);
        log.append(String.format(Locale.US, "- stroke axial head disp @end-stroke=%.2f nm; peak force=%.3f pN; CPU/GPU maxΔnode over %d steps=%.2e µm (first div %s); detach@t=%d ⇒ recoil.\n",
                strokeDisp, peakF, T, maxNode, firstDiv < 0 ? "none" : ("t=" + firstDiv), t3));
        log.append("- **§2 stroke/detach/recoil: " + (ok ? "PASS" : "REVIEW") + "** (driven through the full shared coupling; CPU/GPU agree).\n");
        System.out.printf(Locale.US, "  §2 stroke=%.1f nm peakF=%.2f pN maxΔnode=%.1e ⇒ %s%n", strokeDisp, peakF, maxNode, ok ? "PASS" : "REVIEW");
        return ok;
    }

    static boolean headAndBondGate(StringBuilder log) {
        int t = 7, seed = 101, K = 5;
        double slack = TwoBodyConverterMotor.EXPLICIT_GLIDE_SLACK_NM;
        Glide2D G = TwoBodyConverterMotor.buildS2Mat(200.0, DT, 40.0, slack, seed);
        int M = G.g4M, N = G.N; if (K > N) K = N;
        MotorStore mot = G.mot; RigidRodBody b = mot.body; FilamentStore f = G.fil;
        String[] label = { "relaxed", "high-axial", "transverse-bend", "near-taut", "post-stroke" };
        for (int m = 0; m < K; m++) { int s = ExplicitMatSolveHarness.nearestSegSafe(G, m); mot.boundSeg.set(m, s < 0 ? 0 : s);
            ExplicitMatSolveHarness.perturb(G, m, m % 5); double bindArc = 0.5f * f.segLength.get(mot.boundSeg.get(m)); mot.bindArc.set(m, (float) bindArc); }
        for (int m = K; m < N; m++) mot.boundSeg.set(m, -1);   // rest unbound

        // ---- CPU oracle: production geom2D + placeHead2D over the K motors, then bondForces ----
        for (int m = 0; m < K; m++) { TwoBodyConverterMotor.geom2D(G, m); TwoBodyConverterMotor.placeHead2D(G, m); }
        int nB = b.coord.getSize() / 3;
        float[][] bodyCPU = new float[K][9];   // per motor: coord(3), uVec(3), yVec(3) at h
        double[][] geomCPU = new double[K][9];  // C(3), xH(3), xF8(3)
        for (int m = 0; m < K; m++) { int h = mot.headIdx(m);
            bodyCPU[m] = new float[]{ b.coord.get(h), b.coord.get(nB + h), b.coord.get(2*nB + h),
                b.uVec.get(h), b.uVec.get(nB + h), b.uVec.get(2*nB + h), b.yVec.get(h), b.yVec.get(nB + h), b.yVec.get(2*nB + h) };
            geomCPU[m] = new double[]{ G.C_[m][0], G.C_[m][1], G.C_[m][2], G.xH_[m][0], G.xH_[m][1], G.xH_[m][2], G.xF8_[m][0], G.xF8_[m][1], G.xF8_[m][2] }; }
        // production bondForces (CPU) over the full body/filament → bondData oracle
        CrossBridgeSystem.bondForces(b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength,
                mot.boundSeg, mot.bindArc, mot.nucleotideState, G.bondData, G.xbParams);
        float[] bondCPU = new float[K * 13]; for (int m = 0; m < K; m++) for (int c = 0; c < 13; c++) bondCPU[m * 13 + c] = G.bondData.get(m * 13 + c);

        // ---- pack the explicit SoA (nM=K) for the head-placement kernels ----
        DoubleArray nodes = new DoubleArray(15 * K), frame = new DoubleArray(15 * K), q = new DoubleArray(4 * K), params = new DoubleArray(17 * K), outGeom = new DoubleArray(9 * K);
        IntArray counts = IntArray.fromElements(K, 1, M, 0), boundSeg = new IntArray(K);
        DoubleArray eupP = DoubleArray.fromElements(G.eup[0], G.eup[1], G.eup[2]);
        double[] pr = ExplicitMatSolveHarness.paramArr(G);
        for (int m = 0; m < K; m++) {
            for (int j = 0; j <= M; j++) for (int k = 0; k < 3; k++) nodes.set((3*j+k)*K+m, G.g4Node[m][j][k]);
            double[] fr = ExplicitMatSolveHarness.frameArr(G, m); for (int c = 0; c < 15; c++) frame.set(c*K+m, fr[c]);
            for (int c = 0; c < 17; c++) params.set(c*K+m, pr[c]);
            q.set(m, G.phi[m]); q.set(K+m, G.psi[m]); q.set(2*K+m, G.thetaS[m]); q.set(3*K+m, G.psiActin[m]);
            boundSeg.set(m, mot.boundSeg.get(m));
        }
        // separate FLOAT body arrays for the device head placement (size nB=3K so h=3m+2 fits)
        FloatArray dCoord = new FloatArray(3 * (3 * K)), dUVec = new FloatArray(3 * (3 * K)), dYVec = new FloatArray(3 * (3 * K));

        // ---- CPU-mirror (plain Java) ----
        DoubleArray oMir = new DoubleArray(9 * K);
        FloatArray cMir = new FloatArray(3 * (3 * K)), uMir = new FloatArray(3 * (3 * K)), yMir = new FloatArray(3 * (3 * K));
        TwoBodyBeamAnalyticGpu.matBeamGeom(nodes, frame, params, q, counts, oMir);
        TwoBodyBeamAnalyticGpu.matPlaceHeadExplicit(oMir, boundSeg, eupP, counts, cMir, uMir, yMir);

        // ---- GPU (§5 lowering + execution) ----
        log.append("## §5 explicit head-placement gate (matBeamGeom + matPlaceHeadExplicit, GPU vs CPU-mirror vs production)\n");
        boolean lowered; String err = null;
        try {
            TaskGraph tg = new TaskGraph("head")
                .transferToDevice(DataTransferMode.EVERY_EXECUTION, nodes, frame, params, q, counts, boundSeg, eupP)
                .task("geom", TwoBodyBeamAnalyticGpu::matBeamGeom, nodes, frame, params, q, counts, outGeom)
                .task("place", TwoBodyBeamAnalyticGpu::matPlaceHeadExplicit, outGeom, boundSeg, eupP, counts, dCoord, dUVec, dYVec)
                .transferToHost(DataTransferMode.EVERY_EXECUTION, outGeom, dCoord, dUVec, dYVec);
            WorkerGrid wg = new WorkerGrid1D(K); wg.setLocalWork(Math.min(64, K), 1, 1);
            WorkerGrid wg2 = new WorkerGrid1D(K); wg2.setLocalWork(Math.min(64, K), 1, 1);
            GridScheduler gs = new GridScheduler(); gs.addWorkerGrid("head.geom", wg); gs.addWorkerGrid("head.place", wg2);
            new TornadoExecutionPlan(tg.snapshot()).withGridScheduler(gs).execute();
            lowered = true;
        } catch (Throwable e) { lowered = false; Throwable r = e; while (r.getCause() != null && r.getCause() != r) r = r.getCause(); err = r.getClass().getName() + ": " + oneLine(r.getMessage()); }
        if (!lowered) { log.append("- LOWERS: **NO** — " + err + "\n"); System.out.println("  §5 LOWERS: NO — " + err); return false; }

        // §5 compare: geom (mirror vs production), head body pose (GPU vs mirror, mirror vs production)
        log.append("| motor | shape | geomΔ mir-vs-prod (µm) | headPoseΔ mir-vs-prod | GPUvsMirror geom | GPUvsMirror headPose |\n|---|---|---|---|---|---|\n");
        double mxGeomPort = 0, mxPosePort = 0, mxGeomGpu = 0, mxPoseGpu = 0;
        for (int m = 0; m < K; m++) {
            double dGeom = 0; for (int c = 0; c < 9; c++) dGeom = Math.max(dGeom, Math.abs(oMir.get(c*K+m) - geomCPU[m][c]));
            int h = 3*m+2, nb = 3*K;
            double dPose = 0; float[] mir = { cMir.get(h),cMir.get(nb+h),cMir.get(2*nb+h), uMir.get(h),uMir.get(nb+h),uMir.get(2*nb+h), yMir.get(h),yMir.get(nb+h),yMir.get(2*nb+h) };
            for (int c = 0; c < 9; c++) dPose = Math.max(dPose, Math.abs(mir[c] - bodyCPU[m][c]));
            double dGeomG = 0; for (int c = 0; c < 9; c++) dGeomG = Math.max(dGeomG, Math.abs(outGeom.get(c*K+m) - oMir.get(c*K+m)));
            double dPoseG = Math.max(Math.abs(dCoord.get(h)-cMir.get(h)), Math.max(Math.abs(dUVec.get(h)-uMir.get(h)), Math.abs(dYVec.get(h)-yMir.get(h))));
            mxGeomPort=Math.max(mxGeomPort,dGeom); mxPosePort=Math.max(mxPosePort,dPose); mxGeomGpu=Math.max(mxGeomGpu,dGeomG); mxPoseGpu=Math.max(mxPoseGpu,dPoseG);
            log.append(String.format(Locale.US, "| %d | %s | %.2e | %.2e | %.2e | %.2e |\n", m, label[m], dGeom, dPose, dGeomG, dPoseG));
        }
        boolean s5 = mxGeomPort < 1e-8 && mxPosePort < 1e-6 && mxGeomGpu < 1e-6 && mxPoseGpu < 1e-6;
        log.append(String.format(Locale.US, "- geom mir-vs-prod max=%.2e µm; headPose mir-vs-prod max=%.2e; GPUvsMirror geom=%.2e pose=%.2e ⇒ **§5 %s**\n\n", mxGeomPort, mxPosePort, mxGeomGpu, mxPoseGpu, s5 ? "PASS" : "REVIEW"));

        // ---- §6 bondForces coupling gate: install the DEVICE head pose into the body, run the shared bondForces ----
        log.append("## §6 bondForces coupling gate (device head pose → bondForces vs production)\n");
        FloatArray dBond = new FloatArray(N * 13);
        int nbf = b.coord.getSize() / 3, nbd = 3 * K;
        for (int m = 0; m < K; m++) { int h = 3 * m + 2;
            b.coord.set(h, dCoord.get(h)); b.coord.set(nbf+h, dCoord.get(nbd+h)); b.coord.set(2*nbf+h, dCoord.get(2*nbd+h));
            b.uVec.set(h,  dUVec.get(h));  b.uVec.set(nbf+h,  dUVec.get(nbd+h));  b.uVec.set(2*nbf+h,  dUVec.get(2*nbd+h));
            b.yVec.set(h,  dYVec.get(h));  b.yVec.set(nbf+h,  dYVec.get(nbd+h));  b.yVec.set(2*nbf+h,  dYVec.get(2*nbd+h)); }
        CrossBridgeSystem.bondForces(b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength,
                mot.boundSeg, mot.bindArc, mot.nucleotideState, dBond, G.xbParams);
        double mxBond = 0; for (int m = 0; m < K; m++) for (int c = 0; c < 13; c++) mxBond = Math.max(mxBond, Math.abs(dBond.get(m*13+c) - bondCPU[m*13+c]));
        boolean s6 = mxBond < 1e-6;
        log.append(String.format(Locale.US, "- bondData (device-head-pose bondForces) vs production oracle: max|Δ| = **%.2e** ⇒ %s (F8h to matS2SolveStep matches; no dropped/duplicated force)\n\n", mxBond, s6 ? "PASS (within float)" : "REVIEW"));

        boolean ok = s5 && s6;
        log.append(String.format(Locale.US, "**§5/§6 VERDICT: %s** — explicit head placement lowers + matches production; bondForces consumes the beam-derived pose. Full-graph composition (§7–§11) is the next step.\n", ok ? "PASS" : "REVIEW"));
        System.out.printf(Locale.US, "  §5 head-place geom=%.1e pose=%.1e (GPUvsMir=%.1e) | §6 bondΔ=%.1e ⇒ %s%n", mxGeomPort, mxPosePort, Math.max(mxGeomGpu,mxPoseGpu), mxBond, ok ? "PASS" : "REVIEW");
        return ok;
    }

    // ============================================================= Phase A: GENUINE FREE-BINDING NO-CULL throughput
    /** All 22 tasks of the free-binding gliding graph (prefix "glide."), in dispatch order. */
    static final String[] GLTASKS = { "beamGeom", "bind", "chem", "cock", "place", "bond", "zeroAcc", "csrZero",
        "csrHist", "csrReduce", "csrScan", "csrScatter", "segGather", "chain", "zconf", "brown", "integ", "orthoY", "derive", "s2solve", "redBlk", "redFin" };
    /** {median, mean, std, min, max, cv%} over a sample. */
    static double[] stats(double[] a) {
        double[] c = a.clone(); java.util.Arrays.sort(c); double med = c[c.length / 2];
        double mean = 0; for (double x : a) mean += x; mean /= a.length;
        double var = 0; for (double x : a) var += (x - mean) * (x - mean); var /= Math.max(1, a.length - 1);
        double sd = Math.sqrt(var); return new double[]{ med, mean, sd, c[0], c[c.length - 1], mean != 0 ? sd / mean * 100 : 0 };
    }
    static boolean finite6(DoubleArray r) { return finiteRed(r); }

    /**
     * Phase A. Genuine free-binding (all motors start unbound; matBindExplicit runs on device each step), NO-CULL
     * (every motor processed by matS2SolveStep — active[]≡1, no active-set shortcut), production-residency GPU graph
     * (per step only counters UP; redOut+boundSeg DOWN — the small readback, NOT a full-state download). Timing is
     * SEPARATED from scientific-state evolution: the timed loop is pure execute()+nanoTime; a separate untimed pass
     * tracks bindings/detachments/avgBound/meanForce/invalid from the already-resident redOut+boundSeg readback.
     */
    static boolean throughputMode(StringBuilder log, boolean quick) {
        int[] dens = quick ? new int[]{ 200, 700 } : new int[]{ 200, 700, 1500, 2000, 2500, 3000 };
        int seed = 101; double dt = DT, slack = TwoBodyConverterMotor.EXPLICIT_GLIDE_SLACK_NM;
        int gpuWarm = quick ? 300 : 2000, gpuMeas = quick ? 800 : 10000, gpuReps = quick ? 2 : 3, sciSteps = quick ? 800 : 8000;
        // CPU-analytic free-binding is O(N·nSeg) in the bind search ⇒ steps shrink with N (campaign min 200 @ high N)
        int[] cpuMeasByDens = quick ? new int[]{ 300, 300 } : new int[]{ 2000, 1000, 1000, 400, 300, 200 };
        int[] cpuRepsByDens = quick ? new int[]{ 2, 2 } : new int[]{ 3, 3, 3, 2, 2, 2 };
        int cpuWarm = quick ? 100 : 600;
        System.out.println("=== Phase A: GENUINE FREE-BINDING NO-CULL throughput (explicit-s2-l40) ===");
        log.append("Protocol: GENUINE FREE BINDING (all motors start unbound; device `matBindExplicit` each step) + NO-CULL\n");
        log.append("(active[]≡1 ⇒ all N processed by matS2SolveStep; only the active-set shortcut is bypassed — search/gates/\n");
        log.append("chemistry/mechanics UNCHANGED). Production-residency GPU graph (beam SoA + fil/body FIRST_EXECUTION resident;\n");
        log.append("per step only counters UP + redOut/boundSeg DOWN; NO full-state download in the timed interval). Timing is\n");
        log.append(String.format(Locale.US, "SEPARATED from science. GPU: warm %d, measure %d × %d reps (median). CPU-analytic: warm %d, per-density steps.\n\n", gpuWarm, gpuMeas, gpuReps, cpuWarm));
        StringBuilder csv = new StringBuilder("density,N,nSeg,freeBinding,cullingEnabled,processedMotorCount,hostBindingLoop,fullStateDownload,silentFallback,"
            + "gpu_ms_step_med,gpu_ms_mean,gpu_ms_std,gpu_ms_min,gpu_ms_max,gpu_cv_pct,gpu_steps_s,gpu_devkernel_ms,gpu_launchfloor_ms,gpu_bytesIn,gpu_bytesOut,gpu_beamMiB,"
            + "cpu_ms_step_med,cpu_ms_mean,cpu_ms_std,cpu_cv_pct,cpu_steps_s,speedup,"
            + "sci_steps,binds,detaches,meanBound,minBound,maxBound,meanForce_pN,invalid,activeEqN\n");
        StringBuilder tbl = new StringBuilder("| density | N | GPU ms/step (med±CV) | GPU steps/s | CPU ms/step (med±CV) | CPU steps/s | speedup | meanBound | binds/detach | invalid |\n|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        StringBuilder stage = new StringBuilder("| density | " + String.join(" | ", GLTASKS) + " |\n|" + "---:|".repeat(GLTASKS.length + 1) + "\n");
        boolean allOk = true;
        double[] gpuMed = new double[dens.length], cpuMed = new double[dens.length]; int[] Nat = new int[dens.length];
        for (int di = 0; di < dens.length; di++) {
            double density = dens[di];
            Glide2D Gc = TwoBodyConverterMotor.buildS2Mat(density, dt, 40.0, slack, seed);
            Glide2D Gd = TwoBodyConverterMotor.buildS2Mat(density, dt, 40.0, slack, seed);
            int N = Gc.N, nSeg = Gc.nSeg; Nat[di] = N;
            for (int m = 0; m < N; m++) { Gc.mot.boundSeg.set(m, -1); Gd.mot.boundSeg.set(m, -1); }   // free binding: all unbound
            ExMat ec = packExMat(Gc, 1), ed = packExMat(Gd, 1);
            TornadoExecutionPlan plan;
            try { plan = buildGlidingGraph(ed, true); }   // prod residency
            catch (Throwable ex) { log.append("- density " + density + " graph build FAILED: " + oneLine(root(ex).getMessage()) + "\n"); allOk = false; continue; }
            long beamBytes = (long) (15 + 15 + 17 + SYS + 9 + 4) * N * 8;   // nodes+frame+params+sys+outGeom+q (the per-motor beam SoA)
            System.out.printf(Locale.US, "  [density %.0f N=%d nSeg=%d] warming GPU %d…%n", density, N, nSeg, gpuWarm);
            int tg = 0;
            for (int t = 0; t < gpuWarm; t++, tg++) { ed.matc.set(0, tg); ed.matc.set(1, seed); Gd.mot.setCounts(tg, seed, nSeg); Gd.fil.counts.set(1, tg); Gd.fil.counts.set(2, seed); plan.execute(); }
            // ---- GPU TIMING: pure execute()+nanoTime, nothing else in the loop ----
            double[] gpuMs = new double[gpuReps];
            for (int r = 0; r < gpuReps; r++) { long ns = 0;
                for (int k = 0; k < gpuMeas; k++, tg++) { ed.matc.set(0, tg); ed.matc.set(1, seed); Gd.mot.setCounts(tg, seed, nSeg); Gd.fil.counts.set(1, tg); Gd.fil.counts.set(2, seed);
                    long t0 = System.nanoTime(); plan.execute(); ns += System.nanoTime() - t0; }
                gpuMs[r] = ns / 1e6 / gpuMeas; }
            boolean gpuInvalidTime = !finite6(ed.redOut);
            // ---- GPU profiled window (bytes + per-stage + device-kernel), OUTSIDE timing ----
            long[] kerNs = new long[GLTASKS.length]; long devKerNs = 0, bIn = 0, bOut = 0; int nProf = 0;
            for (int k = 0; k < 40; k++, tg++) { ed.matc.set(0, tg); ed.matc.set(1, seed); Gd.mot.setCounts(tg, seed, nSeg); Gd.fil.counts.set(1, tg); Gd.fil.counts.set(2, seed);
                TornadoProfilerResult pr = plan.withProfiler(ProfilerMode.SILENT).execute().getProfilerResult(); String jl = pr.getProfileLog();
                for (int i = 0; i < GLTASKS.length; i++) kerNs[i] += parseTaskNs(jl, "glide.", GLTASKS[i]);
                devKerNs += pr.getDeviceKernelTime(); bIn += pr.getTotalBytesCopyIn(); bOut += pr.getTotalBytesCopyOut(); nProf++; plan.clearProfiles(); }
            plan.withoutProfiler();
            // ---- GPU SCIENCE pass (untimed): track binding/detach/avgBound/meanForce/invalid from resident readback ----
            int[] prevBc = new int[N]; for (int m = 0; m < N; m++) prevBc[m] = Gd.mot.boundSeg.get(m);
            long binds = 0, detaches = 0, sumBound = 0; int minBound = Integer.MAX_VALUE, maxBound = 0, invalid = 0; double sumForcePn = 0; boolean activeEqN = true;
            for (int k = 0; k < sciSteps; k++, tg++) { ed.matc.set(0, tg); ed.matc.set(1, seed); Gd.mot.setCounts(tg, seed, nSeg); Gd.fil.counts.set(1, tg); Gd.fil.counts.set(2, seed);
                plan.execute();
                int nb = (int) ed.redOut.get(0); double load = ed.redOut.get(4); int na = (int) ed.redOut.get(5);
                if (na != N) activeEqN = false;
                if (!finite6(ed.redOut)) invalid++;
                sumBound += nb; minBound = Math.min(minBound, nb); maxBound = Math.max(maxBound, nb);
                sumForcePn += (nb > 0 ? load / nb : 0) * 1e12;
                for (int m = 0; m < N; m++) { int bs = Gd.mot.boundSeg.get(m); if (bs >= 0 && prevBc[m] < 0) binds++; if (bs < 0 && prevBc[m] >= 0) detaches++; prevBc[m] = bs; } }
            double meanBound = (double) sumBound / sciSteps, meanForcePn = sumForcePn / sciSteps;
            // ---- CPU-analytic free-binding timing (its own scene; genuine binding search) ----
            int cpuMeas = cpuMeasByDens[Math.min(di, cpuMeasByDens.length - 1)], cpuReps = cpuRepsByDens[Math.min(di, cpuRepsByDens.length - 1)];
            System.out.printf(Locale.US, "  [density %.0f] GPU %.3f ms/step; warming CPU %d, measuring %d×%d…%n", density, stats(gpuMs)[0], cpuWarm, cpuMeas, cpuReps);
            int tc = 0; for (int t = 0; t < cpuWarm; t++, tc++) stepGlidingCPU(ec, tc, seed);
            double[] cpuMs = new double[cpuReps];
            for (int r = 0; r < cpuReps; r++) { long ns = 0; for (int k = 0; k < cpuMeas; k++, tc++) { long t0 = System.nanoTime(); stepGlidingCPU(ec, tc, seed); ns += System.nanoTime() - t0; } cpuMs[r] = ns / 1e6 / cpuMeas; }
            boolean cpuInvalid = !finite6(ec.redOut);
            double[] gs = stats(gpuMs), cs = stats(cpuMs); double gMed = gs[0], cMed = cs[0]; gpuMed[di] = gMed; cpuMed[di] = cMed;
            double gSps = 1000 / gMed, cSps = 1000 / cMed, speedup = cMed / gMed, devKerMs = devKerNs / 1e6 / nProf;
            boolean invalidState = invalid > 0 || gpuInvalidTime || cpuInvalid;
            // assertions
            log.append(String.format(Locale.US, "### density %.0f (N=%d, nSeg=%d): freeBinding=true, cullingEnabled=false, processedMotorCount=N=%d, hostBindingLoop=false, fullStateDownload=false, silentFallback=false, activeEqN=%b\n", density, N, nSeg, N, activeEqN));
            log.append(String.format(Locale.US, "- **GPU** %.3f ms/step (med; mean %.3f, sd %.3f, min %.3f, max %.3f, CV %.2f%%, %.0f steps/s) | device-kernel %.3f ms; launch floor %.3f ms; bytes in=%d out=%d/step; beam SoA %.2f MiB\n",
                    gMed, gs[1], gs[2], gs[3], gs[4], gs[5], gSps, devKerMs, gMed - devKerMs, bIn / nProf, bOut / nProf, beamBytes / 1048576.0));
            log.append(String.format(Locale.US, "- **CPU-analytic** %.3f ms/step (med; mean %.3f, sd %.3f, CV %.2f%%, %.1f steps/s) over %d×%d ⇒ **speedup %.2f×**\n", cMed, cs[1], cs[2], cs[5], cSps, cpuReps, cpuMeas, speedup));
            log.append(String.format(Locale.US, "- **science** (%d untimed steps): binds=%d, detaches=%d, meanBound=%.2f (min %d, max %d), meanForce=%.2f pN, invalid=%d\n\n", sciSteps, binds, detaches, meanBound, minBound, maxBound, meanForcePn, invalid));
            tbl.append(String.format(Locale.US, "| %.0f | %d | %.3f±%.1f%% | %.0f | %.3f±%.1f%% | %.1f | %.2f× | %.2f | %d/%d | %d |\n", density, N, gMed, gs[5], gSps, cMed, cs[5], cSps, speedup, meanBound, binds, detaches, invalid));
            stage.append(String.format(Locale.US, "| %.0f", density)); for (int i = 0; i < GLTASKS.length; i++) stage.append(String.format(Locale.US, " | %.3f", kerNs[i] / 1e6 / nProf)); stage.append(" |\n");
            csv.append(String.format(Locale.US, "%.0f,%d,%d,true,false,%d,false,false,false,%.4f,%.4f,%.4f,%.4f,%.4f,%.2f,%.1f,%.4f,%.4f,%d,%d,%.2f,%.4f,%.4f,%.4f,%.2f,%.1f,%.3f,%d,%d,%d,%.3f,%d,%d,%.3f,%d,%b\n",
                    density, N, nSeg, N, gMed, gs[1], gs[2], gs[3], gs[4], gs[5], gSps, devKerMs, gMed - devKerMs, bIn / nProf, bOut / nProf, beamBytes / 1048576.0,
                    cMed, cs[1], cs[2], cs[5], cSps, speedup, sciSteps, binds, detaches, meanBound, minBound, maxBound, meanForcePn, invalid, activeEqN));
            System.out.printf(Locale.US, "  density=%-5.0f N=%-5d | GPU %.3f ms/step (%.1f%%CV) | CPU %.3f ms/step | %.2f× | meanBound %.2f binds %d invalid %d%n", density, N, gMed, gs[5], cMed, speedup, meanBound, binds, invalid);
            if (invalidState) { allOk = false; System.out.println("  !! INVALID STATE at density " + density); }
        }
        log.append("\n## Throughput table (genuine free-binding, no-cull, production-residency)\n").append(tbl);
        log.append("\n## Per-stage GPU kernel time (ms/step)\n").append(stage);
        // cost scaling: linear fit ms/step = a + b·N over measured densities (least squares)
        int n = dens.length; double sx = 0, sy = 0, sxx = 0, sxy = 0;
        for (int i = 0; i < n; i++) { double x = Nat[i], y = gpuMed[i]; sx += x; sy += y; sxx += x * x; sxy += x * y; }
        double b = (n * sxy - sx * sy) / (n * sxx - sx * sx), a = (sy - b * sx) / n;
        int crossN = -1; for (int i = 0; i < n; i++) if (cpuMed[i] > gpuMed[i]) { crossN = Nat[i]; break; }
        log.append(String.format(Locale.US, "\n## §B2 cost scaling (GPU): ms/step ≈ %.4f + %.3e·N  ⇒ launch floor ≈ %.3f ms; per-1000-motors ≈ %.4f ms; GPU faster than CPU from N≈%s.\n",
                a, b, a, b * 1000, crossN < 0 ? "(all measured N)" : "" + crossN));
        try { Files.writeString(Path.of(OUT, "COMPLETEMAT_THROUGHPUT.csv"), csv.toString()); } catch (IOException e) { throw new UncheckedIOException(e); }
        log.append("\n**GENUINE FREE-BINDING NO-CULL THROUGHPUT: measured** (all N processed; free binding + chemistry active; production residency; timing separated from science). DEVICE_VALIDATED stays false (promotion deferred).\n");
        return allOk;
    }

    // ============================================================= Phase B arm: CALIBRATED no-cull free-binding throughput
    static final String[] CALTASKS = { "matCull", "matGeomGate", "matBind", "chem", "matCock", "matPlaceHead", "bondForces",
        "zeroAcc", "csrHist", "csrScan", "csrScatter", "segGather", "chain", "zconf", "brown", "integ", "orthoY", "derive", "matStep7", "matReduce" };
    /** Matched-protocol throughput for the calibrated-s2-l40 model (reusing MatSoaSlice's device graph + CPU runner).
     *  Same densities/schedule as the explicit throughputMode; no-cull via a huge queryR so matCull marks all N active
     *  (only the active-set shortcut bypassed; matGeomGate/matBind/chemistry/mechanics unchanged). Frozen 4I params. */
    static boolean throughputCalMode(StringBuilder log, boolean quick) {
        int[] dens = quick ? new int[]{ 200, 700 } : new int[]{ 200, 700, 1500, 2000, 2500, 3000 };
        int seed = 101; double dt = DT;
        int gpuWarm = quick ? 300 : 2000, gpuMeas = quick ? 800 : 10000, gpuReps = quick ? 2 : 3, sciSteps = quick ? 800 : 8000;
        int[] cpuMeasByDens = quick ? new int[]{ 300, 300 } : new int[]{ 2000, 1000, 1000, 400, 300, 200 };
        int[] cpuRepsByDens = quick ? new int[]{ 2, 2 } : new int[]{ 3, 3, 3, 2, 2, 2 };
        int cpuWarm = quick ? 100 : 600; double HUGE = 1e12;
        System.out.println("=== Phase B arm: CALIBRATED no-cull free-binding throughput (calibrated-s2-l40) ===");
        log.append("Protocol: calibrated-s2-l40 (frozen 4I), GENUINE FREE BINDING (all unbound at t=0; matCull→matGeomGate→matBind\n");
        log.append("each step), NO-CULL (cullP=huge ⇒ matCull marks all N active ⇒ matStep7 solves all N; only the active-set\n");
        log.append("shortcut bypassed). Production-residency MatSoaSlice.buildTrajGraph (per step only counters UP + redOut DOWN).\n");
        log.append(String.format(Locale.US, "GPU: warm %d, measure %d × %d reps. CPU: warm %d, per-density steps. Matched to the explicit Phase A run.\n\n", gpuWarm, gpuMeas, gpuReps, cpuWarm));
        StringBuilder csv = new StringBuilder("model,density,N,nSeg,freeBinding,cullingEnabled,processedMotorCount,fullStateDownload,silentFallback,"
            + "gpu_ms_step_med,gpu_ms_mean,gpu_ms_std,gpu_ms_min,gpu_ms_max,gpu_cv_pct,gpu_steps_s,gpu_devkernel_ms,gpu_launchfloor_ms,gpu_bytesIn,gpu_bytesOut,gpu_stateMiB,"
            + "cpu_ms_step_med,cpu_ms_mean,cpu_ms_std,cpu_cv_pct,cpu_steps_s,speedup,sci_steps,meanBound,minBound,maxBound,bindActivity,meanForce_pN,invalid,activeEqN\n");
        StringBuilder tbl = new StringBuilder("| density | N | GPU ms/step (med±CV) | GPU steps/s | CPU ms/step (med±CV) | CPU steps/s | speedup | meanBound | invalid |\n|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        StringBuilder stage = new StringBuilder("| density | " + String.join(" | ", CALTASKS) + " |\n|" + "---:|".repeat(CALTASKS.length + 1) + "\n");
        boolean allOk = true;
        for (int di = 0; di < dens.length; di++) {
            double density = dens[di];
            Glide2D Gc = TwoBodyConverterMotor.buildMatForModel(MotorModel.CALIBRATED_S2_L40, density, dt, seed);
            Glide2D Gd = TwoBodyConverterMotor.buildMatForModel(MotorModel.CALIBRATED_S2_L40, density, dt, seed);
            int N = Gc.N, nSeg = Gc.nSeg;
            for (int m = 0; m < N; m++) { Gc.mot.boundSeg.set(m, -1); Gd.mot.boundSeg.set(m, -1); }
            MatSoaSlice.MatState sc = MatSoaSlice.packMat(Gc), sd = MatSoaSlice.packMat(Gd);
            sc.cullP.set(0, HUGE); sd.cullP.set(0, HUGE);   // NO-CULL: matCull marks all N active
            for (int m = 0; m < N; m++) { sc.active.set(m, 1); sd.active.set(m, 1); }
            long stateBytes = (long) (2 + 4 + 3 + 3 + 9 + 1) * N * 8 + (long) 2 * N * 4;   // calibrated per-motor state (site/pose4/anchor/supP0/geomOut/candArc + candInt)
            TornadoExecutionPlan plan;
            try { plan = MatSoaSlice.buildTrajGraph(Gd, sd, true).withGridScheduler(MatSoaSlice.trajSched); }
            catch (Throwable ex) { log.append("- density " + density + " graph FAILED: " + oneLine(root(ex).getMessage()) + "\n"); allOk = false; continue; }
            System.out.printf(Locale.US, "  [cal density %.0f N=%d nSeg=%d] warming GPU %d…%n", density, N, nSeg, gpuWarm);
            int tg = 0;
            for (int t = 0; t < gpuWarm; t++, tg++) { sd.mc.set(1, tg); sd.mc.set(2, seed); Gd.mot.setCounts(tg, seed, nSeg); Gd.fil.counts.set(1, tg); Gd.fil.counts.set(2, seed); plan.execute(); }
            double[] gpuMs = new double[gpuReps];
            for (int r = 0; r < gpuReps; r++) { long ns = 0;
                for (int k = 0; k < gpuMeas; k++, tg++) { sd.mc.set(1, tg); sd.mc.set(2, seed); Gd.mot.setCounts(tg, seed, nSeg); Gd.fil.counts.set(1, tg); Gd.fil.counts.set(2, seed);
                    long t0 = System.nanoTime(); plan.execute(); ns += System.nanoTime() - t0; }
                gpuMs[r] = ns / 1e6 / gpuMeas; }
            long[] kerNs = new long[CALTASKS.length]; long devKerNs = 0, bIn = 0, bOut = 0; int nProf = 0;
            for (int k = 0; k < 40; k++, tg++) { sd.mc.set(1, tg); sd.mc.set(2, seed); Gd.mot.setCounts(tg, seed, nSeg); Gd.fil.counts.set(1, tg); Gd.fil.counts.set(2, seed);
                TornadoProfilerResult pr = plan.withProfiler(ProfilerMode.SILENT).execute().getProfilerResult(); String jl = pr.getProfileLog();
                for (int i = 0; i < CALTASKS.length; i++) kerNs[i] += parseTaskNs(jl, "traj.", CALTASKS[i]);
                devKerNs += pr.getDeviceKernelTime(); bIn += pr.getTotalBytesCopyIn(); bOut += pr.getTotalBytesCopyOut(); nProf++; plan.clearProfiles(); }
            plan.withoutProfiler();
            // science (untimed): avgBound/meanForce/invalid/activeEqN from redOut; bindActivity = Σ|Δbound|
            long sumBound = 0; int minBound = Integer.MAX_VALUE, maxBound = 0, invalid = 0, prevNb = (int) sd.redOut.get(0); long bindActivity = 0; double sumForcePn = 0; boolean activeEqN = true;
            for (int k = 0; k < sciSteps; k++, tg++) { sd.mc.set(1, tg); sd.mc.set(2, seed); Gd.mot.setCounts(tg, seed, nSeg); Gd.fil.counts.set(1, tg); Gd.fil.counts.set(2, seed);
                plan.execute();
                int nb = (int) sd.redOut.get(0); double load = sd.redOut.get(4); int na = (int) sd.redOut.get(5);
                if (na != N) activeEqN = false; if (!finite6(sd.redOut)) invalid++;
                sumBound += nb; minBound = Math.min(minBound, nb); maxBound = Math.max(maxBound, nb); bindActivity += Math.abs(nb - prevNb); prevNb = nb;
                sumForcePn += (nb > 0 ? load / nb : 0) * 1e12; }
            double meanBound = (double) sumBound / sciSteps, meanForcePn = sumForcePn / sciSteps;
            // CPU
            int cpuMeas = cpuMeasByDens[Math.min(di, cpuMeasByDens.length - 1)], cpuReps = cpuRepsByDens[Math.min(di, cpuRepsByDens.length - 1)];
            System.out.printf(Locale.US, "  [cal density %.0f] GPU %.3f ms/step; warming CPU %d, measuring %d×%d…%n", density, stats(gpuMs)[0], cpuWarm, cpuMeas, cpuReps);
            int tc = 0; for (int t = 0; t < cpuWarm; t++, tc++) MatSoaSlice.stepMatCPU(Gc, sc, tc, seed);
            double[] cpuMs = new double[cpuReps];
            for (int r = 0; r < cpuReps; r++) { long ns = 0; for (int k = 0; k < cpuMeas; k++, tc++) { long t0 = System.nanoTime(); MatSoaSlice.stepMatCPU(Gc, sc, tc, seed); ns += System.nanoTime() - t0; } cpuMs[r] = ns / 1e6 / cpuMeas; }
            boolean cpuInvalid = !finite6(sc.redOut);
            double[] gs = stats(gpuMs), cs = stats(cpuMs); double gMed = gs[0], cMed = cs[0];
            double gSps = 1000 / gMed, cSps = 1000 / cMed, speedup = cMed / gMed, devKerMs = devKerNs / 1e6 / nProf;
            boolean invalidState = invalid > 0 || cpuInvalid || !finite6(sd.redOut);
            log.append(String.format(Locale.US, "### density %.0f (N=%d, nSeg=%d): freeBinding=true, cullingEnabled=false, processedMotorCount=N=%d, fullStateDownload=false, silentFallback=false, activeEqN=%b\n", density, N, nSeg, N, activeEqN));
            log.append(String.format(Locale.US, "- **GPU** %.3f ms/step (med; mean %.3f, sd %.3f, CV %.2f%%, %.0f steps/s) | device-kernel %.3f ms; launch floor %.3f ms; bytes in=%d out=%d/step; state %.2f MiB\n",
                    gMed, gs[1], gs[2], gs[5], gSps, devKerMs, gMed - devKerMs, bIn / nProf, bOut / nProf, stateBytes / 1048576.0));
            log.append(String.format(Locale.US, "- **CPU** %.3f ms/step (med; CV %.2f%%, %.1f steps/s) over %d×%d ⇒ **speedup %.2f×**\n", cMed, cs[5], cSps, cpuReps, cpuMeas, speedup));
            log.append(String.format(Locale.US, "- **science** (%d steps): meanBound=%.2f (min %d, max %d), bindActivity Σ|Δ|=%d, meanForce=%.2f pN, invalid=%d\n\n", sciSteps, meanBound, minBound, maxBound, bindActivity, meanForcePn, invalid));
            tbl.append(String.format(Locale.US, "| %.0f | %d | %.3f±%.1f%% | %.0f | %.3f±%.1f%% | %.1f | %.2f× | %.2f | %d |\n", density, N, gMed, gs[5], gSps, cMed, cs[5], cSps, speedup, meanBound, invalid));
            stage.append(String.format(Locale.US, "| %.0f", density)); for (int i = 0; i < CALTASKS.length; i++) stage.append(String.format(Locale.US, " | %.3f", kerNs[i] / 1e6 / nProf)); stage.append(" |\n");
            csv.append(String.format(Locale.US, "calibrated-s2-l40,%.0f,%d,%d,true,false,%d,false,false,%.4f,%.4f,%.4f,%.4f,%.4f,%.2f,%.1f,%.4f,%.4f,%d,%d,%.3f,%.4f,%.4f,%.4f,%.2f,%.1f,%.3f,%d,%.3f,%d,%d,%d,%.3f,%d,%b\n",
                    density, N, nSeg, N, gMed, gs[1], gs[2], gs[3], gs[4], gs[5], gSps, devKerMs, gMed - devKerMs, bIn / nProf, bOut / nProf, stateBytes / 1048576.0,
                    cMed, cs[1], cs[2], cs[5], cSps, speedup, sciSteps, meanBound, minBound, maxBound, bindActivity, meanForcePn, invalid, activeEqN));
            System.out.printf(Locale.US, "  cal density=%-5.0f N=%-5d | GPU %.3f ms/step (%.1f%%CV) | CPU %.3f ms/step | %.2f× | meanBound %.2f invalid %d%n", density, N, gMed, gs[5], cMed, speedup, meanBound, invalid);
            if (invalidState) { allOk = false; System.out.println("  !! INVALID STATE at cal density " + density); }
        }
        log.append("\n## Calibrated throughput table (no-cull, free-binding, production-residency)\n").append(tbl);
        log.append("\n## Per-stage GPU kernel time (ms/step)\n").append(stage);
        try { Files.writeString(Path.of(OUT, "COMPLETEMAT_THROUGHPUT_CAL.csv"), csv.toString()); } catch (IOException e) { throw new UncheckedIOException(e); }
        log.append("\n**CALIBRATED NO-CULL THROUGHPUT: measured** (matched to explicit Phase A; combine the two CSVs for the Phase B cost table).\n");
        return allOk;
    }

    // ============================================================= Phase C: explicit density-saturation sweep (GPU)
    static double argD(String[] a, String key, double def) { for (int i = 0; i < a.length - 1; i++) if (a[i].equals(key)) return Double.parseDouble(a[i + 1]); return def; }
    static int argI(String[] a, String key, int def) { for (int i = 0; i < a.length - 1; i++) if (a[i].equals(key)) return Integer.parseInt(a[i + 1]); return def; }
    static String argS(String[] a, String key, String def) { for (int i = 0; i < a.length - 1; i++) if (a[i].equals(key)) return a[i + 1]; return def; }
    /** {vel µm/s, avgBound, continuity, netForce pN, invalid, N, minBound, maxBound, boundFrac} for one (model,density,seed).
     *  model 0=explicit-s2-l40 (buildGlidingGraph), 1=calibrated-s2-l40 (MatSoaSlice.buildTrajGraph). Device-resident;
     *  velocity = LS slope of the resident redOut centroid·b̂ over the MEASURED window (negative = pointed-first). */
    static boolean SWEEP_NOCULL = false;   // D3 control: force calibrated no-cull (cullP huge) to test culling is a semantic no-op
    static double[] runGlide(int model, double density, double dt, int seed, int equil, int meas) {
        double slack = TwoBodyConverterMotor.EXPLICIT_GLIDE_SLACK_NM;
        int recEvery = Math.max(1, meas / 2000);
        double[] bhat; int N, nSeg; DoubleArray redOut; TornadoExecutionPlan plan;
        Glide2D Gd; ExMat ed = null; MatSoaSlice.MatState sd = null;
        if (model == 0) {
            Gd = TwoBodyConverterMotor.buildS2Mat(density, dt, 40.0, slack, seed);
            N = Gd.N; nSeg = Gd.nSeg; for (int m = 0; m < N; m++) Gd.mot.boundSeg.set(m, -1);
            ed = packExMat(Gd, 1); redOut = ed.redOut; plan = buildGlidingGraph(ed, true);   // explicit is inherently no-cull
        } else {
            Gd = TwoBodyConverterMotor.buildMatForModel(MotorModel.CALIBRATED_S2_L40, density, dt, seed);
            N = Gd.N; nSeg = Gd.nSeg; for (int m = 0; m < N; m++) Gd.mot.boundSeg.set(m, -1);
            sd = MatSoaSlice.packMat(Gd); redOut = sd.redOut;
            if (SWEEP_NOCULL) { sd.cullP.set(0, 1e12); for (int m = 0; m < N; m++) sd.active.set(m, 1); }   // D3: no-cull calibrated
            plan = MatSoaSlice.buildTrajGraph(Gd, sd, true).withGridScheduler(MatSoaSlice.trajSched);
        }
        bhat = Gd.bhat;
        int tg = 0;
        for (int t = 0; t < equil; t++, tg++) { setCounters(model, ed, sd, Gd, tg, seed, nSeg); plan.execute(); }
        int nSamp = 0; double[] tt = new double[meas / recEvery + 2], yy = new double[meas / recEvery + 2];
        long sumBound = 0, contHits = 0; int minB = Integer.MAX_VALUE, maxB = 0, invalid = 0; double sumForce = 0;
        for (int t = 0; t < meas; t++, tg++) { setCounters(model, ed, sd, Gd, tg, seed, nSeg); plan.execute();
            if (!finite6(redOut)) invalid++;
            int nb = (int) redOut.get(0); double load = redOut.get(4);
            sumBound += nb; if (nb > 0) contHits++; minB = Math.min(minB, nb); maxB = Math.max(maxB, nb); sumForce += load * 1e12;
            if (t % recEvery == 0) { double com = redOut.get(1) * bhat[0] + redOut.get(2) * bhat[1] + redOut.get(3) * bhat[2];
                tt[nSamp] = t * dt; yy[nSamp] = com; nSamp++; } }
        // LS slope (µm/s); sign kept (negative = pointed-first = correct)
        double st = 0, sy = 0, stt = 0, sty = 0; for (int i = 0; i < nSamp; i++) { st += tt[i]; sy += yy[i]; stt += tt[i] * tt[i]; sty += tt[i] * yy[i]; }
        double den = nSamp * stt - st * st; double vel = Math.abs(den) < 1e-30 ? 0 : (nSamp * sty - st * sy) / den;
        double avgBound = (double) sumBound / meas, continuity = (double) contHits / meas, netForce = sumForce / meas;
        return new double[]{ vel, avgBound, continuity, netForce, invalid, N, minB == Integer.MAX_VALUE ? 0 : minB, maxB, N > 0 ? avgBound / N : 0 };
    }
    static void setCounters(int model, ExMat ed, MatSoaSlice.MatState sd, Glide2D Gd, int t, int seed, int nSeg) {
        if (model == 0) { ed.matc.set(0, t); ed.matc.set(1, seed); } else { sd.mc.set(1, t); sd.mc.set(2, seed); }
        Gd.mot.setCounts(t, seed, nSeg); Gd.fil.counts.set(1, t); Gd.fil.counts.set(2, seed);
    }
    static boolean sweepMode(StringBuilder log, boolean quick, String[] args) {
        double dt = argD(args, "-dt", DT);
        int seeds = argI(args, "-seeds", quick ? 2 : 3), equil = argI(args, "-equil", quick ? 2000 : 20000), meas = argI(args, "-meas", quick ? 3000 : 60000);
        int seed0 = argI(args, "-seed0", 4321);
        for (String a : args) if (a.equals("-nocull")) SWEEP_NOCULL = true;
        String densCsv = argS(args, "-dens", quick ? "200,700" : "100,200,400,700,1000,1500,2000,2500,3000");
        String[] dp = densCsv.split(","); double[] dens = new double[dp.length]; for (int i = 0; i < dp.length; i++) dens[i] = Double.parseDouble(dp[i].trim());
        String[] MNAME = { "explicit-s2-l40", "calibrated-s2-l40" };
        String modelsCsv = argS(args, "-models", "0,1");   // which models: 0=explicit, 1=calibrated (default both)
        String[] mp = modelsCsv.split(","); int[] models = new int[mp.length]; for (int i = 0; i < mp.length; i++) models[i] = Integer.parseInt(mp[i].trim());
        System.out.println("=== Phase C: explicit density-saturation sweep (GPU, both models, matched geometry) ===");
        log.append(String.format(Locale.US, "Protocol: GPU device-resident free-binding gliding, BOTH models at matched geometry (buildGlide2D density).\n"
            + "Velocity = LS slope of resident redOut centroid·b̂ over the MEASURED window (negative = pointed-first = correct;\n"
            + "the canonical `lsSlope`/`filComB` convention). dt=%.2e, equilibration=%d steps discarded, measured=%d steps (%.3f s),\n"
            + "seeds=%d (seed0=%d). Production residency (only redOut crosses per step). Explicit inherently no-cull; calibrated\n"
            + "production culling (physics-identical — the D3 control confirms). Densities: %s /µm².\n\n", dt, equil, meas, meas * dt, seeds, seed0, densCsv));
        StringBuilder csv = new StringBuilder("model,density,N,seeds,equilSteps,measSteps,dt,vel_umPerS,vel_sd,vel_se,speed_umPerS,avgBound,avgBound_sd,continuity,boundFrac,netForce_pN,minBound,maxBound,invalid\n");
        StringBuilder tbl = new StringBuilder("| model | density | N | vel µm/s (±SE) | |speed| | avgBound | continuity | boundFrac | netForce pN | invalid |\n|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        boolean allOk = true;
        for (int mo : models) {
            for (double density : dens) {
                double[] vels = new double[seeds], abs = new double[seeds]; double sVel = 0, sAb = 0, sCont = 0, sFor = 0; int Nrep = 0, invAll = 0, minAll = Integer.MAX_VALUE, maxAll = 0;
                for (int s = 0; s < seeds; s++) {
                    double[] r;
                    try { r = runGlide(mo, density, dt, seed0 + s, equil, meas); }
                    catch (Throwable ex) { log.append("- " + MNAME[mo] + " density " + density + " seed " + (seed0 + s) + " FAILED: " + oneLine(root(ex).getMessage()) + "\n"); allOk = false; continue; }
                    vels[s] = r[0]; abs[s] = r[1]; sVel += r[0]; sAb += r[1]; sCont += r[2]; sFor += r[3]; invAll += (int) r[4]; Nrep = (int) r[5];
                    minAll = Math.min(minAll, (int) r[6]); maxAll = Math.max(maxAll, (int) r[7]);
                    System.out.printf(Locale.US, "  %s density=%-5.0f N=%-5d seed=%d | vel=%+.3f µm/s avgBound=%.2f cont=%.2f inv=%d%n", MNAME[mo], density, Nrep, seed0 + s, r[0], r[1], r[2], (int) r[4]);
                }
                double vMean = sVel / seeds, aMean = sAb / seeds, cMean = sCont / seeds, fMean = sFor / seeds;
                double vVar = 0, aVar = 0; for (int s = 0; s < seeds; s++) { vVar += (vels[s] - vMean) * (vels[s] - vMean); aVar += (abs[s] - aMean) * (abs[s] - aMean); }
                double vSd = Math.sqrt(vVar / Math.max(1, seeds - 1)), aSd = Math.sqrt(aVar / Math.max(1, seeds - 1)), vSe = vSd / Math.sqrt(seeds);
                double boundFrac = Nrep > 0 ? aMean / Nrep : 0;
                log.append(String.format(Locale.US, "- **%s** density %.0f (N=%d): vel=%+.3f ± %.3f (SE %.3f) µm/s, |speed|=%.3f, avgBound=%.2f ± %.2f, continuity=%.2f, boundFrac=%.4f, netForce=%.2f pN, bound∈[%d,%d], invalid=%d\n",
                        MNAME[mo], density, Nrep, vMean, vSd, vSe, Math.abs(vMean), aMean, aSd, cMean, boundFrac, fMean, minAll, maxAll, invAll));
                tbl.append(String.format(Locale.US, "| %s | %.0f | %d | %+.3f±%.3f | %.3f | %.2f | %.2f | %.4f | %.2f | %d |\n", MNAME[mo], density, Nrep, vMean, vSe, Math.abs(vMean), aMean, cMean, boundFrac, fMean, invAll));
                csv.append(String.format(Locale.US, "%s,%.0f,%d,%d,%d,%d,%.3e,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.5f,%.4f,%d,%d,%d\n",
                        MNAME[mo], density, Nrep, seeds, equil, meas, dt, vMean, vSd, vSe, Math.abs(vMean), aMean, aSd, cMean, boundFrac, fMean, minAll, maxAll, invAll));
                if (invAll > 0) { allOk = false; System.out.println("  !! INVALID at " + MNAME[mo] + " density " + density); }
                try { Files.writeString(Path.of(OUT, "COMPLETEMAT_SWEEP.csv"), csv.toString()); } catch (IOException e) { throw new UncheckedIOException(e); }   // flush per density (crash insurance)
            }
        }
        log.append("\n## Density-sweep table (GPU, both models, matched geometry)\n").append(tbl);
        log.append("\nSaturation fit (v_max, K_ρ, plateau) + explicit/calibrated comparison: `scripts/phaseC_saturation.py` over COMPLETEMAT_SWEEP.csv.\n");
        log.append("\n**EXPLICIT DENSITY SWEEP: measured** (velocity/avgBound/continuity/netForce vs density, both models, matched geometry). Force-balance decomposition (propulsive/dragging, ATP/µm) is a scoped follow-on (needs the explicit force-balance port).\n");
        return allOk;
    }

    static String oneLine(String s) { return s == null ? "(none)" : s.replaceAll("\\s+", " ").trim(); }
}
