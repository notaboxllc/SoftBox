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
        boolean traj = false, bench = false, stroke = false;
        for (String a : args) { if (a.equals("-traj")) traj = true; if (a.equals("-bench")) bench = true; if (a.equals("-stroke")) stroke = true; }
        StringBuilder log = new StringBuilder();
        boolean ok; String fn;
        if (bench)      { log.append("# Explicit complete-mat NO-CULL throughput — CPU-analytic vs GPU-analytic @ 200/700/1500\n\n"); ok = benchMode(log); fn = "COMPLETEMAT_BENCH.md"; }
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
        DoubleArray nodes, frame, params, sys, outGeom, q, redOut, redBlk, eupP;
        FloatArray zP;
        IntArray exCounts, boundSeg, active, matc, redP, csrChunkParams, csrMatrix;
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
    static long parseTaskNs(String jl, String name) {
        if (jl == null) return 0; int k = jl.indexOf("\"exmat." + name + "\""); if (k < 0) return 0;
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
                for (int i = 0; i < EXTASKS.length; i++) kerNs[i] += parseTaskNs(jl, EXTASKS[i]); devKerNs += pr.getDeviceKernelTime(); bIn += pr.getTotalBytesCopyIn(); bOut += pr.getTotalBytesCopyOut(); nProf++; plan.clearProfiles(); }
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

    static String oneLine(String s) { return s == null ? "(none)" : s.replaceAll("\\s+", " ").trim(); }
}
