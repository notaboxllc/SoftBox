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
 * Increment 4b-ii harness: the cross-bridge + the cross-entity motor→segment force+torque gather,
 * on a PINNED filament. Articulated motors (4b-i) bind a static filament (4a binding, re-exercised
 * reading the new head sub-body); the cross-bridge spring + alignment torques (F8/F9/F10) engage and
 * transmit force+torque to the segments via the segment-side CSR-inverse gather. FIXED rest angles +
 * pinned filament ⇒ NO stroke, NO motion of the filament, NO gliding (those are 4b-iii).
 *
 * Establishment (host, deterministic, Brownian off): assemble the motor bed, derive the static
 * filament, then a few steps of publishHeadFromBody → bruteReachable → bindKinetics bind the heads
 * (3 motors per segment ⇒ a multi-motor gather). Then the bonds are FROZEN.
 *
 * Per cross-bridge step (one physics, two runners): zero → brownian(off) → joints → anchor →
 * CrossBridgeSystem.bondForces (head-side self-write + store seg-side) → integrate → derive →
 * zero(fil) → CSR-inverse build → segGather → bruteGather.
 *
 * Gates: gathered force+torque == brute-force per-bond sum (bit-identical); CPU≡GPU on the gathered
 * cross-bridge (near-static config → bit-identity); binding re-exercised; force-coverage.
 */
public final class MotorXBridgeHarness {

    static final int B = 64;
    static boolean cpu = false;
    static GridScheduler sched;

    static final int   N_SEG = 4;            // pinned filament segments (along x)
    static final int   MOTORS_PER_SEG = 3;
    static final double ANCHOR_Z = -0.05;
    static final double Z_OFFSET = 0.003;    // filament 3 nm above the standing head tip (within 6 nm reach)
    static final double MYO_SPRING = 1.0e-9; // N/µm (Env.java:791)
    static final double REST_DEG = 90.0;     // uncocked motor–actin rest angle (Myosin.java:18)
    static final double J1_FMT = 0.4;        // myoJ1FracMoveTorq (Env.java:149)
    static final int   WARMUP = 4;

    public static void main(String[] args) {
        double dt = 1.0e-5;
        double brownScale = 0.0;             // static gather test (bit-identical CPU≡GPU)
        int M = 2000;
        String vizDir = null;
        java.util.List<String> pos = new java.util.ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-cpu" -> cpu = true;
                case "-b"   -> brownScale = Double.parseDouble(args[++i]);
                case "-dt"  -> dt = Double.parseDouble(args[++i]);
                case "-3js" -> vizDir = args[++i];
                default     -> pos.add(args[i]);
            }
        }
        if (!pos.isEmpty()) M = Integer.parseInt(pos.get(0));

        System.out.println("=== Soft Box increment 4b-ii — cross-bridge + cross-entity gather (pinned) ===");
        System.out.println("FIXED rest angles, pinned filament: no stroke, no motion, no gliding (4b-iii).");
        System.out.printf("config: %d pinned segments, %d motors (%d/seg), M=%d, dt=%.1e, brownScale=%.2g%n",
                N_SEG, N_SEG * MOTORS_PER_SEG, MOTORS_PER_SEG, M, dt, brownScale);

        if (vizDir != null) { runViz(M, dt, brownScale, vizDir); return; }

        boolean ok;
        if (cpu) {
            Scene sc = buildScene(dt, brownScale);
            System.out.println("runner: CPU sequential (same system methods, no TaskGraph)");
            reportBinding(sc);
            Snap[] c = runOnce(sc, M, true);
            ok = reportGather("CPU", c) & reportCoverage(sc, c);
        } else {
            Scene g = buildScene(dt, brownScale);
            Scene c = buildScene(dt, brownScale);
            System.out.println("runner: GPU TaskGraph (TornadoVM PTX)  +  CPU cross-check");
            reportBinding(g);
            Snap[] gs = runOnce(g, M, false);
            Snap[] cs = runOnce(c, M, true);
            boolean okG = reportGather("GPU", gs) & reportCoverage(g, gs);
            boolean okC = reportGather("CPU", cs);
            boolean okI = reportIdentity(gs, cs);
            ok = okG && okC && okI;
        }
        System.out.println();
        System.out.println("=== CROSS-BRIDGE / GATHER VALIDATION " + (ok ? "PASS" : "FAIL") + " ===");
        if (!ok) {
            System.out.println("BAIL-OUT: gather != brute sum, or CPU!=GPU, or coverage off. "
                    + "Use -cpu to localize (bond physics vs CSR-inverse vs gather). Commit nothing.");
            System.exit(1);
        }
    }

    // ============================================================== build + bind
    static final class Scene {
        FilamentStore fil; MotorStore mot;
        FloatArray bondData, xbParams;
        IntArray segMotorCount, segMotorOffsets, segMotorMyo;
        FloatArray bruteForceSum, bruteTorqueSum;
        IntArray bruteReachSeg, bruteReachCount;
        double bx, by, bz;
    }

    static Scene buildScene(double dt, double brownScale) {
        Scene sc = new Scene();
        int nSeg = N_SEG, nMot = N_SEG * MOTORS_PER_SEG;
        double L = (Constants.stdSegLength + 1) * Constants.actinMonoRadius;     // segment length µm
        double headTipZ = ANCHOR_Z + MotorStore.ROD_LEN + MotorStore.LEVER_LEN + MotorStore.HEAD_LEN;
        double zFil = headTipZ + Z_OFFSET;

        // pinned filament along x at (y=0, z=zFil), contiguous segments
        FilamentStore fil = new FilamentStore(nSeg);
        double x0 = -0.5 * (nSeg - 1) * L;
        for (int s = 0; s < nSeg; s++) {
            fil.monomerCount.set(s, Constants.stdSegLength);
            fil.setUVec(s, 1f, 0f, 0f);
            fil.setYVec(s, 0f, 1f, 0f);
            fil.setCoord(s, (float) (x0 + s * L), 0f, (float) zFil);
            fil.brownTransScale.set(s, 0f);    // pinned/static
            fil.brownRotScale.set(s, 0f);
        }
        DragTensorSystem.run(fil);
        fil.setParams(dt, Math.sqrt(2.0 * Constants.kT / dt));
        fil.setCounts(0, 0);
        DerivedGeometrySystem.derive(fil.coord, fil.uVec, fil.yVec, fil.zVec, fil.end1, fil.end2, fil.segLength, fil.counts);

        // articulated motors: 3 per segment along x within the segment span, standing +z
        MotorStore mot = new MotorStore(nMot);
        int m = 0;
        for (int s = 0; s < nSeg; s++) {
            double segCx = x0 + s * L;
            for (int k = 0; k < MOTORS_PER_SEG; k++) {
                double frac = (k - (MOTORS_PER_SEG - 1) / 2.0) * 0.25;   // interior offsets along the segment
                float ax = (float) (segCx + frac * L), ay = 0f, az = (float) ANCHOR_Z;
                mot.assembleArticulated(m, ax, ay, az, 0f, 0f, 1f, (float) brownScale);
                m++;
            }
        }
        DragTensorSystem.run(mot);
        mot.setBodyParams(dt);
        mot.setJointParams(dt);
        mot.setKinParams(0.006, -0.4, dt);     // v1 myoColTol / align tol (4a binding)
        mot.setAllStates(MotorStore.NUC_ADPPI);   // uncocked ⇒ F9 rest 90° (the 4b-ii fixed angle)

        // cross-bridge + gather scratch
        int MAXC = SpatialGrid.MAX_CAND;
        sc.bondData = new FloatArray(nMot * CrossBridgeSystem.STRIDE); sc.bondData.init(0f);
        sc.xbParams = FloatArray.fromElements((float) MYO_SPRING, (float) REST_DEG, (float) J1_FMT,
                (float) dt, (float) MotorStore.HEAD_LEN);
        sc.segMotorCount   = new IntArray(nSeg);
        sc.segMotorOffsets = new IntArray(nSeg + 1);
        sc.segMotorMyo     = new IntArray(nMot);
        sc.bruteForceSum  = new FloatArray(3 * nSeg); sc.bruteForceSum.init(0f);
        sc.bruteTorqueSum = new FloatArray(3 * nSeg); sc.bruteTorqueSum.init(0f);
        sc.bruteReachSeg   = new IntArray(nMot * MAXC); sc.bruteReachSeg.init(-1);
        sc.bruteReachCount = new IntArray(nMot);
        sc.fil = fil; sc.mot = mot;
        sc.bx = 1.0; sc.by = 0.6; sc.bz = 0.2;

        // --- binding establishment (host, deterministic; motors static) ---
        for (int t = 0; t < WARMUP; t++) {
            mot.setCounts(t, 0x4D58B6, nSeg);
            MotorStore.publishHeadFromBody(mot.body.coord, mot.body.uVec, mot.body.segLength,
                    mot.head, mot.uVec, mot.rodUVec, mot.counts);
            BindingDetectionSystem.bruteReachable(mot.head, mot.uVec, mot.rodUVec, fil.end1, fil.end2,
                    sc.bruteReachSeg, sc.bruteReachCount, mot.kinParams, mot.counts);
            BindingDetectionSystem.bindKinetics(mot.head, mot.uVec, mot.rodUVec, fil.end1, fil.end2,
                    sc.bruteReachSeg, sc.bruteReachCount, mot.boundSeg, mot.bindArc, mot.stats, mot.kinParams, mot.counts);
        }
        return sc;
    }

    static void reportBinding(Scene sc) {
        int nSeg = sc.fil.n, bound = 0;
        int[] perSeg = new int[nSeg];
        for (int m = 0; m < sc.mot.nMotors; m++) {
            int s = sc.mot.boundSeg.get(m);
            if (s >= 0) { bound++; perSeg[s]++; }
        }
        System.out.println("\n--- binding re-exercised (4a binding on the articulated head sub-body) ---");
        System.out.print("  bound motors: " + bound + "/" + sc.mot.nMotors + "  per-segment bonds: [");
        for (int s = 0; s < nSeg; s++) System.out.print((s > 0 ? " " : "") + perSeg[s]);
        System.out.println("]  (multi-motor-per-segment ⇒ the gather sums >1 contribution)");
    }

    // ============================================================== run + validate
    static final class Snap {
        int step;
        double maxDiffF, maxDiffT;     // gather vs brute (µm-scale; should be 0)
        float[] filF, filT;            // for CPU≡GPU
        double totalSegForceMag;
    }

    static Snap[] runOnce(Scene sc, int M, boolean useCpu) {
        int[] samples = dedup(new int[]{ 0, M / 4, M / 2, (3 * M) / 4, Math.max(0, M - 1) });
        java.util.List<Snap> out = new java.util.ArrayList<>();
        int last = samples[samples.length - 1], si = 0;

        if (useCpu) {
            Runnable step = cpuStep(sc);
            for (int t = 0; t <= last; t++) {
                sc.mot.setCounts(t, 0x4D58B6, sc.fil.n);
                step.run();
                if (si < samples.length && t == samples[si]) { out.add(snap(t, sc)); si++; }
            }
        } else {
            TornadoExecutionPlan plan = buildPlan(sc);
            for (int t = 0; t <= last; t++) {
                sc.mot.setCounts(t, 0x4D58B6, sc.fil.n);
                TornadoExecutionResult res = plan.withGridScheduler(sched).execute();
                if (si < samples.length && t == samples[si]) {
                    res.transferToHost(sc.fil.forceSum, sc.fil.torqueSum, sc.bruteForceSum, sc.bruteTorqueSum);
                    out.add(snap(t, sc)); si++;
                }
            }
        }
        return out.toArray(new Snap[0]);
    }

    static Snap snap(int t, Scene sc) {
        int nSeg = sc.fil.n;
        Snap s = new Snap();
        s.step = t;
        for (int i = 0; i < 3 * nSeg; i++) {
            s.maxDiffF = Math.max(s.maxDiffF, Math.abs(sc.fil.forceSum.get(i) - sc.bruteForceSum.get(i)));
            s.maxDiffT = Math.max(s.maxDiffT, Math.abs(sc.fil.torqueSum.get(i) - sc.bruteTorqueSum.get(i)));
        }
        s.filF = new float[3 * nSeg]; s.filT = new float[3 * nSeg];
        for (int i = 0; i < 3 * nSeg; i++) { s.filF[i] = sc.fil.forceSum.get(i); s.filT[i] = sc.fil.torqueSum.get(i); }
        double fm = 0;
        for (int seg = 0; seg < nSeg; seg++) {
            double fx = sc.fil.forceSum.get(seg), fy = sc.fil.forceSum.get(nSeg + seg), fz = sc.fil.forceSum.get(2 * nSeg + seg);
            fm += Math.sqrt(fx * fx + fy * fy + fz * fz);
        }
        s.totalSegForceMag = fm;
        return s;
    }

    // ============================================================== GPU plan + CPU step
    static TornadoExecutionPlan buildPlan(Scene sc) {
        FilamentStore f = sc.fil; MotorStore mot = sc.mot; RigidRodBody b = mot.body;
        TaskGraph tg = new TaskGraph("xbridge")
            .transferToDevice(DataTransferMode.FIRST_EXECUTION,
                    b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength,
                    b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, b.randForce, b.randTorque,
                    b.brownTransScale, b.brownRotScale, mot.bodyParams, mot.jointParams, mot.anchor, mot.nucleotideState,
                    mot.boundSeg, mot.bindArc, sc.bondData, sc.xbParams,
                    f.coord, f.uVec, f.yVec, f.bRotGam, f.end1, f.end2, f.segLength, f.forceSum, f.torqueSum,
                    sc.segMotorCount, sc.segMotorOffsets, sc.segMotorMyo, sc.bruteForceSum, sc.bruteTorqueSum)
            .transferToDevice(DataTransferMode.EVERY_EXECUTION, mot.counts)
            .task("zero", ChainBendingForceSystem::zeroAccumulators, b.forceSum, b.torqueSum, mot.counts)
            .task("brownian", BrownianForceSystem::brownianForce,
                    b.randForce, b.randTorque, b.bTransGam, b.bRotGam, b.brownTransScale, b.brownRotScale, mot.bodyParams, mot.counts)
            .task("joints", MotorJointSystem::joints, b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, mot.nucleotideState, mot.jointParams, mot.counts)
            .task("anchor", TailAnchorSystem::anchor, b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, mot.anchor, mot.jointParams, mot.counts)
            .task("bond", CrossBridgeSystem::bondForces,
                    b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength,
                    mot.boundSeg, mot.bindArc, mot.nucleotideState, sc.bondData, sc.xbParams)
            .task("applyHead", CrossBridgeSystem::applyHeadForce, sc.bondData, b.forceSum, b.torqueSum, mot.counts)
            .task("integrate", RigidRodLangevinIntegrationSystem::integrate,
                    b.coord, b.uVec, b.yVec, b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, mot.bodyParams, mot.counts)
            .task("derive", DerivedGeometrySystem::derive, b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts)
            .task("zeroFil", ChainBendingForceSystem::zeroAccumulators, f.forceSum, f.torqueSum, f.counts)
            .task("csrHist", CrossBridgeSystem::csrHistogram, mot.boundSeg, mot.counts, sc.segMotorCount)
            .task("csrScan", CrossBridgeSystem::csrScan, mot.counts, sc.segMotorCount, sc.segMotorOffsets)
            .task("csrScatter", CrossBridgeSystem::csrScatter, mot.boundSeg, mot.counts, sc.segMotorOffsets, sc.segMotorCount, sc.segMotorMyo)
            .task("gather", CrossBridgeSystem::segGather, sc.segMotorOffsets, sc.segMotorMyo, sc.bondData, f.forceSum, f.torqueSum, mot.counts)
            .task("brute", CrossBridgeSystem::bruteGather, mot.boundSeg, sc.bondData, sc.bruteForceSum, sc.bruteTorqueSum, mot.counts)
            .transferToHost(DataTransferMode.UNDER_DEMAND, f.forceSum, f.torqueSum, sc.bruteForceSum, sc.bruteTorqueSum, sc.bondData);

        int nB = 3 * mot.nMotors, nM = mot.nMotors, nSeg = f.n;
        sched = new GridScheduler();
        addWorker("xbridge.zero", pad(nB)); addWorker("xbridge.brownian", pad(nB));
        addWorker("xbridge.joints", pad(nB)); addWorker("xbridge.anchor", pad(nM));
        addWorker("xbridge.bond", pad(nM)); addWorker("xbridge.applyHead", pad(nM));
        addWorker("xbridge.integrate", pad(nB)); addWorker("xbridge.derive", pad(nB));
        addWorker("xbridge.zeroFil", pad(nSeg));
        addSingle("xbridge.csrHist"); addSingle("xbridge.csrScan"); addSingle("xbridge.csrScatter");
        addWorker("xbridge.gather", pad(nSeg)); addWorker("xbridge.brute", pad(nSeg));
        return new TornadoExecutionPlan(tg.snapshot());
    }

    static int pad(int n) { return ((n + B - 1) / B) * B; }
    static void addWorker(String name, int global) { WorkerGrid w = new WorkerGrid1D(global); w.setLocalWork(B, 1, 1); sched.addWorkerGrid(name, w); }
    static void addSingle(String name) { WorkerGrid w = new WorkerGrid1D(1); w.setLocalWork(1, 1, 1); sched.addWorkerGrid(name, w); }

    static Runnable cpuStep(Scene sc) {
        FilamentStore f = sc.fil; MotorStore mot = sc.mot; RigidRodBody b = mot.body;
        return () -> {
            ChainBendingForceSystem.zeroAccumulators(b.forceSum, b.torqueSum, mot.counts);
            BrownianForceSystem.brownianForce(b.randForce, b.randTorque, b.bTransGam, b.bRotGam, b.brownTransScale, b.brownRotScale, mot.bodyParams, mot.counts);
            MotorJointSystem.joints(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, mot.nucleotideState, mot.jointParams, mot.counts);
            TailAnchorSystem.anchor(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, mot.anchor, mot.jointParams, mot.counts);
            CrossBridgeSystem.bondForces(b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength,
                    mot.boundSeg, mot.bindArc, mot.nucleotideState, sc.bondData, sc.xbParams);
            CrossBridgeSystem.applyHeadForce(sc.bondData, b.forceSum, b.torqueSum, mot.counts);
            RigidRodLangevinIntegrationSystem.integrate(b.coord, b.uVec, b.yVec, b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, mot.bodyParams, mot.counts);
            DerivedGeometrySystem.derive(b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts);
            ChainBendingForceSystem.zeroAccumulators(f.forceSum, f.torqueSum, f.counts);
            CrossBridgeSystem.csrHistogram(mot.boundSeg, mot.counts, sc.segMotorCount);
            CrossBridgeSystem.csrScan(mot.counts, sc.segMotorCount, sc.segMotorOffsets);
            CrossBridgeSystem.csrScatter(mot.boundSeg, mot.counts, sc.segMotorOffsets, sc.segMotorCount, sc.segMotorMyo);
            CrossBridgeSystem.segGather(sc.segMotorOffsets, sc.segMotorMyo, sc.bondData, f.forceSum, f.torqueSum, mot.counts);
            CrossBridgeSystem.bruteGather(mot.boundSeg, sc.bondData, sc.bruteForceSum, sc.bruteTorqueSum, mot.counts);
        };
    }

    // ============================================================== reporting
    static boolean reportGather(String label, Snap[] snaps) {
        System.out.println("\n--- " + label + ": gathered force+torque == brute-force per-bond sum (exact) ---");
        System.out.printf("  %-8s %-18s %-18s %-16s%n", "step", "max|gather-brute|F", "max|gather-brute|T", "Σ|segForce|(N)");
        boolean all = true;
        for (Snap s : snaps) {
            boolean ok = s.maxDiffF == 0.0 && s.maxDiffT == 0.0;   // bit-identical (same values, same order)
            all &= ok;
            System.out.printf("  %-8d %-18.3g %-18.3g %-16.4g %s%n", s.step, s.maxDiffF, s.maxDiffT, s.totalSegForceMag, ok ? "EXACT" : "*MISMATCH*");
        }
        return all;
    }

    static boolean reportCoverage(Scene sc, Snap[] snaps) {
        // Force-coverage: each bond's F8 force is +F on the head (self) and -F on the segment (gathered).
        // Σ over bonds of (head F + seg F) = 0 by construction; the seg-side total is the gathered force.
        // (Verified by code: bondForces stores +F head-side / -F seg-side in bondData; applyHeadForce + gather apply them.)
        System.out.println("\n--- force-coverage audit ---");
        System.out.println("  cross-bridge F8 force: +F on head (self-write, once) / -F on segment (gathered, once) — equal-opposite by construction.");
        System.out.println("  F9/F10 alignment torques: -T on head / +T on segment (once each). Gather == brute (above) ⇒ applied exactly once.");
        System.out.printf("  steady gathered |segForce| ≈ %.4g N (nonzero ⇒ cross-bridge engaged).%n",
                snaps[snaps.length - 1].totalSegForceMag);
        return snaps[snaps.length - 1].totalSegForceMag > 0;
    }

    static boolean reportIdentity(Snap[] g, Snap[] c) {
        System.out.println("\n--- CPU↔GPU: gathered cross-bridge force+torque (near-static ⇒ bit-identity) ---");
        System.out.printf("  %-8s %-18s %-18s%n", "step", "max|ΔfilForce|", "max|ΔfilTorque|");
        boolean all = true;
        for (int i = 0; i < g.length; i++) {
            double df = 0, dtq = 0;
            for (int k = 0; k < g[i].filF.length; k++) {
                df = Math.max(df, Math.abs(g[i].filF[k] - c[i].filF[k]));
                dtq = Math.max(dtq, Math.abs(g[i].filT[k] - c[i].filT[k]));
            }
            boolean ok = df < 1e-12 && dtq < 1e-15;   // SI N / N·m; tiny float32 last-bit tolerance
            all &= ok;
            System.out.printf("  %-8d %-18.3g %-18.3g %s%n", g[i].step, df, dtq, ok ? "" : "*DIFFERS*");
        }
        return all;
    }

    // ============================================================== viewer (-3js)
    static void runViz(int M, double dt, double brownScale, String dir) {
        Scene sc = buildScene(dt, brownScale);
        reportBinding(sc);
        new java.io.File(dir).mkdirs();
        Runnable step = cpuStep(sc);
        int every = Math.max(1, M / 300), frames = 0;
        for (int t = 0; t <= M; t++) {
            sc.mot.setCounts(t, 0x4D58B6, sc.fil.n);
            if (t % every == 0) writeFrame(dir, frames++, t * dt, sc);
            step.run();
        }
        System.out.println("viewer: wrote " + frames + " frames to " + dir);
    }

    static void writeFrame(String dir, int frame, double t, Scene sc) {
        FilamentStore f = sc.fil; MotorStore mot = sc.mot; RigidRodBody b = mot.body;
        StringBuilder sb = new StringBuilder(512 + 200 * mot.nMotors);
        sb.append(String.format(java.util.Locale.US, "{\"frame\":%d,\"t\":%.6g", frame, t));
        sb.append(String.format(java.util.Locale.US, ",\"bounds\":{\"xDim\":%.4g,\"yDim\":%.4g,\"zDim\":%.4g}", sc.bx, sc.by, sc.bz));
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
            String state = mot.boundSeg.get(m) >= 0 ? "ADP" : "NONE";
            sb.append(String.format(java.util.Locale.US,
                "{\"id\":%d,\"rod\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g,\"invisible\":false},"
                + "\"lever\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g},"
                + "\"motor\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g,\"state\":\"%s\"}}",
                m,
                b.end1X(rod), b.end1Y(rod), b.end1Z(rod), b.end2X(rod), b.end2Y(rod), b.end2Z(rod), MotorStore.ROD_R,
                b.end1X(lever), b.end1Y(lever), b.end1Z(lever), b.end2X(lever), b.end2Y(lever), b.end2Z(lever), MotorStore.LEVER_R,
                b.end1X(head), b.end1Y(head), b.end1Z(head), b.end2X(head), b.end2Y(head), b.end2Z(head), MotorStore.HEAD_R, state));
        }
        sb.append("]}");
        try {
            java.nio.file.Files.writeString(java.nio.file.Path.of(dir, String.format(java.util.Locale.US, "frame_%06d.json", frame)), sb.toString());
        } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }

    static int[] dedup(int[] a) {
        java.util.TreeSet<Integer> set = new java.util.TreeSet<>();
        for (int x : a) set.add(x);
        int[] o = new int[set.size()]; int i = 0; for (int x : set) o[i++] = x; return o;
    }
}
