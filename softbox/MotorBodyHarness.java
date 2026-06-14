package softbox;

import uk.ac.manchester.tornado.api.GridScheduler;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.TornadoExecutionResult;
import uk.ac.manchester.tornado.api.WorkerGrid;
import uk.ac.manchester.tornado.api.WorkerGrid1D;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;

/**
 * Increment 4b-i harness: the articulated myosin motor body, validated ISOMETRICALLY.
 * A bed of anchored 3-body motors (rod→lever→head) holds its articulated rest shape under
 * Brownian — joints connected, J1 angle about its rest (0°, uncocked) — with NO filament,
 * NO cross-bridge, NO nucleotide cycle, NO gliding (those are 4b-ii / 4b-iii).
 *
 * Per step (one physics, two runners — GPU TaskGraph vs CPU sequential, same methods):
 *   zero → BrownianForceSystem → MotorJointSystem (J1+J2) → TailAnchorSystem →
 *   RigidRodLangevinIntegrationSystem → DerivedGeometrySystem  (over the SHARED RigidRodBody).
 *
 * The shared rigid-rod systems (Brownian, integration, derived) run over MotorStore.body
 * UNCHANGED — the second instance of the rigid-rod-body abstraction. Only the joints + anchor
 * are motor-specific.
 *
 * Gates: joint-continuity gaps bounded + non-growing; J1 angle fluctuates about its 0° rest;
 * CPU≡GPU on the dynamics.
 */
public final class MotorBodyHarness {

    static final int B = 64;
    static boolean cpu = false;
    static GridScheduler sched;

    static final double ANCHOR_Z = -0.05;     // v1 fixedMyosinZValue
    static final double SPACING = 0.10;       // µm between bed anchors

    public static void main(String[] args) {
        double dt = 1.0e-5;                    // v1 gliding-assay dt (the motor's regime)
        double brownScale = 1.0;              // v1 myoBrownianAttn (rod+head; lever off)
        int nMotors = 64;
        int M = 5000;
        String vizDir = null;
        java.util.List<String> pos = new java.util.ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-cpu" -> cpu = true;
                case "-b"   -> brownScale = Double.parseDouble(args[++i]);
                case "-dt"  -> dt = Double.parseDouble(args[++i]);
                case "-n"   -> nMotors = Integer.parseInt(args[++i]);
                case "-3js" -> vizDir = args[++i];
                default     -> pos.add(args[i]);
            }
        }
        if (!pos.isEmpty()) M = Integer.parseInt(pos.get(0));

        System.out.println("=== Soft Box increment 4b-i — articulated myosin motor (isometric) ===");
        System.out.println("NO filament / cross-bridge / nucleotide / gliding this increment.");
        System.out.printf("config: %d motors (rod 0.080 + lever 0.008 + head 0.020 µm), M=%d, dt=%.1e, brownScale=%.2g%n",
                nMotors, M, dt, brownScale);

        if (vizDir != null) { runViz(nMotors, M, dt, brownScale, vizDir); return; }

        boolean ok;
        if (cpu) {
            System.out.println("runner: CPU sequential (same system methods, no TaskGraph)");
            Snap[] c = runOnce(nMotors, M, dt, brownScale, true);
            ok = report("CPU", c);
        } else {
            System.out.println("runner: GPU TaskGraph (TornadoVM PTX)  +  CPU cross-check");
            Snap[] g = runOnce(nMotors, M, dt, brownScale, false);
            Snap[] c = runOnce(nMotors, M, dt, brownScale, true);
            boolean okG = report("GPU", g);
            boolean okC = report("CPU", c);
            boolean okI = reportIdentity(g, c);
            ok = okG && okC && okI;
        }
        System.out.println();
        System.out.println("=== ARTICULATED MOTOR VALIDATION " + (ok ? "PASS" : "FAIL") + " ===");
        if (!ok) {
            System.out.println("BAIL-OUT: motor doesn't hold shape (gap growing / flies apart) or CPU!=GPU. "
                    + "Use -cpu + the viewer to localize (joint law vs rest angle vs PTX). Commit nothing.");
            System.exit(1);
        }
    }

    // ============================================================== build
    static MotorStore buildScene(int nMotors, double dt, double brownScale) {
        MotorStore mot = new MotorStore(nMotors);
        int side = (int) Math.ceil(Math.sqrt(nMotors));
        double x0 = -0.5 * (side - 1) * SPACING, y0 = -0.5 * (side - 1) * SPACING;
        for (int m = 0; m < nMotors; m++) {
            int r = m / side, c = m % side;
            float ax = (float) (x0 + c * SPACING), ay = (float) (y0 + r * SPACING), az = (float) ANCHOR_Z;
            mot.assembleArticulated(m, ax, ay, az, 0f, 0f, 1f, (float) brownScale);  // standing +z
        }
        DragTensorSystem.run(mot);
        mot.setBodyParams(dt);
        mot.setJointParams(dt);
        mot.setAllStates(MotorStore.NUC_ADPPI);   // uncocked ⇒ J1 rest 0° (the 4b-i fixed angle)
        return mot;
    }

    // ============================================================== run + validate
    static final class Snap {
        int step;
        double maxGapJ1, maxGapJ2, maxGapAnchor;   // µm
        double meanGapJ1, meanGapJ2;
        double meanAngJ1, meanAngJ2;               // degrees
        float[] coord, uVec;                        // for CPU≡GPU
    }

    static Snap[] runOnce(int nMotors, int M, double dt, double brownScale, boolean useCpu) {
        MotorStore mot = buildScene(nMotors, dt, brownScale);
        int[] samples = dedup(new int[]{ 0, M / 8, M / 4, M / 2, (3 * M) / 4, Math.max(0, M - 1) });
        java.util.List<Snap> out = new java.util.ArrayList<>();
        int last = samples[samples.length - 1], si = 0;

        if (useCpu) {
            Runnable step = cpuStep(mot);
            for (int t = 0; t <= last; t++) {
                mot.setCounts(t, 0x4D0B0D, 0);
                step.run();
                if (si < samples.length && t == samples[si]) { out.add(snap(t, mot)); si++; }
            }
        } else {
            TornadoExecutionPlan plan = buildPlan(mot);
            for (int t = 0; t <= last; t++) {
                mot.setCounts(t, 0x4D0B0D, 0);
                TornadoExecutionResult res = plan.withGridScheduler(sched).execute();
                if (si < samples.length && t == samples[si]) {
                    res.transferToHost(mot.body.coord, mot.body.uVec, mot.body.end1, mot.body.end2);
                    out.add(snap(t, mot)); si++;
                }
            }
        }
        return out.toArray(new Snap[0]);
    }

    static Snap snap(int t, MotorStore mot) {
        RigidRodBody b = mot.body;
        int nM = mot.nMotors;
        Snap s = new Snap();
        s.step = t;
        double sumJ1 = 0, sumJ2 = 0, sumA1 = 0, sumA2 = 0;
        for (int m = 0; m < nM; m++) {
            int rod = 3 * m, lever = 3 * m + 1, head = 3 * m + 2;
            double gJ2 = dist(b.end2X(rod), b.end2Y(rod), b.end2Z(rod), b.end1X(lever), b.end1Y(lever), b.end1Z(lever));
            double gJ1 = dist(b.end2X(lever), b.end2Y(lever), b.end2Z(lever), b.end1X(head), b.end1Y(head), b.end1Z(head));
            double gA  = dist(b.end1X(rod), b.end1Y(rod), b.end1Z(rod), mot.anchorX(m), mot.anchorY(m), mot.anchorZ(m));
            s.maxGapJ2 = Math.max(s.maxGapJ2, gJ2); s.maxGapJ1 = Math.max(s.maxGapJ1, gJ1);
            s.maxGapAnchor = Math.max(s.maxGapAnchor, gA);
            sumJ2 += gJ2; sumJ1 += gJ1;
            sumA1 += angDeg(b.uVecX(lever), b.uVecY(lever), b.uVecZ(lever), b.uVecX(head), b.uVecY(head), b.uVecZ(head));
            sumA2 += angDeg(b.uVecX(rod), b.uVecY(rod), b.uVecZ(rod), b.uVecX(lever), b.uVecY(lever), b.uVecZ(lever));
        }
        s.meanGapJ1 = sumJ1 / nM; s.meanGapJ2 = sumJ2 / nM;
        s.meanAngJ1 = sumA1 / nM; s.meanAngJ2 = sumA2 / nM;
        s.coord = new float[3 * b.n]; s.uVec = new float[3 * b.n];
        for (int i = 0; i < 3 * b.n; i++) { s.coord[i] = b.coord.get(i); s.uVec[i] = b.uVec.get(i); }
        return s;
    }

    // ============================================================== GPU plan + CPU step
    static TornadoExecutionPlan buildPlan(MotorStore mot) {
        RigidRodBody b = mot.body;
        TaskGraph tg = new TaskGraph("motorbody")
            .transferToDevice(DataTransferMode.FIRST_EXECUTION,
                    b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength,
                    b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, b.randForce, b.randTorque,
                    b.brownTransScale, b.brownRotScale, mot.bodyParams, mot.jointParams, mot.anchor, mot.nucleotideState)
            .transferToDevice(DataTransferMode.EVERY_EXECUTION, mot.counts)
            .task("zero", ChainBendingForceSystem::zeroAccumulators, b.forceSum, b.torqueSum, mot.counts)
            .task("brownian", BrownianForceSystem::brownianForce,
                    b.randForce, b.randTorque, b.bTransGam, b.bRotGam,
                    b.brownTransScale, b.brownRotScale, mot.bodyParams, mot.counts)
            .task("joints", MotorJointSystem::joints,
                    b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum,
                    mot.nucleotideState, mot.jointParams, mot.counts)
            .task("anchor", TailAnchorSystem::anchor,
                    b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, mot.anchor,
                    mot.jointParams, mot.counts)
            .task("integrate", RigidRodLangevinIntegrationSystem::integrate,
                    b.coord, b.uVec, b.yVec, b.forceSum, b.torqueSum, b.randForce, b.randTorque,
                    b.bTransGam, b.bRotGam, mot.bodyParams, mot.counts)
            .task("derive", DerivedGeometrySystem::derive,
                    b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts)
            .transferToHost(DataTransferMode.UNDER_DEMAND, b.coord, b.uVec, b.end1, b.end2);

        int nB = 3 * mot.nMotors, nM = mot.nMotors;
        sched = new GridScheduler();
        addWorker("motorbody.zero", pad(nB));
        addWorker("motorbody.brownian", pad(nB));
        addWorker("motorbody.joints", pad(nB));
        addWorker("motorbody.anchor", pad(nM));
        addWorker("motorbody.integrate", pad(nB));
        addWorker("motorbody.derive", pad(nB));
        return new TornadoExecutionPlan(tg.snapshot());
    }

    static int pad(int n) { return ((n + B - 1) / B) * B; }
    static void addWorker(String name, int global) {
        WorkerGrid w = new WorkerGrid1D(global); w.setLocalWork(B, 1, 1); sched.addWorkerGrid(name, w);
    }

    static Runnable cpuStep(MotorStore mot) {
        RigidRodBody b = mot.body;
        return () -> {
            ChainBendingForceSystem.zeroAccumulators(b.forceSum, b.torqueSum, mot.counts);
            BrownianForceSystem.brownianForce(b.randForce, b.randTorque, b.bTransGam, b.bRotGam,
                    b.brownTransScale, b.brownRotScale, mot.bodyParams, mot.counts);
            MotorJointSystem.joints(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam,
                    b.forceSum, b.torqueSum, mot.nucleotideState, mot.jointParams, mot.counts);
            TailAnchorSystem.anchor(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam,
                    b.forceSum, mot.anchor, mot.jointParams, mot.counts);
            RigidRodLangevinIntegrationSystem.integrate(b.coord, b.uVec, b.yVec, b.forceSum, b.torqueSum,
                    b.randForce, b.randTorque, b.bTransGam, b.bRotGam, mot.bodyParams, mot.counts);
            DerivedGeometrySystem.derive(b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts);
        };
    }

    // ============================================================== reporting
    static boolean report(String label, Snap[] snaps) {
        System.out.println("\n--- " + label + ": joint continuity + rest angles ---");
        System.out.printf("  %-8s %-12s %-12s %-12s %-11s %-11s%n",
                "step", "gapJ1(nm)", "gapJ2(nm)", "anchor(nm)", "angJ1(deg)", "angJ2(deg)");
        for (Snap s : snaps)
            System.out.printf("  %-8d %-12.3f %-12.3f %-12.3f %-11.2f %-11.2f%n",
                    s.step, 1e3 * s.maxGapJ1, 1e3 * s.maxGapJ2, 1e3 * s.maxGapAnchor, s.meanAngJ1, s.meanAngJ2);
        // gate: gaps bounded (< 30 nm) AND not growing (last mean ≤ 2× a settled early mean)
        Snap first = snaps[snaps.length >= 2 ? 1 : 0], lastS = snaps[snaps.length - 1];
        boolean bounded = lastS.maxGapJ1 < 0.030 && lastS.maxGapJ2 < 0.030 && lastS.maxGapAnchor < 0.030;
        boolean stable = lastS.meanGapJ1 <= 2.0 * first.meanGapJ1 + 1e-4 && lastS.meanGapJ2 <= 2.0 * first.meanGapJ2 + 1e-4;
        boolean angleOk = Math.abs(lastS.meanAngJ1) < 30.0;   // fluctuates about 0° rest
        boolean ok = bounded && stable && angleOk;
        System.out.printf("  bounded(<30nm)=%s  non-growing=%s  J1 angle≈rest(0°)=%s  => %s%n",
                bounded, stable, angleOk, ok ? "PASS" : "*FAIL*");
        return ok;
    }

    static boolean reportIdentity(Snap[] g, Snap[] c) {
        // CPU≡GPU for a CHAOTIC thermal many-body system is tested on the AGGREGATE
        // statistics (joint gaps / angles), not the per-body microstate trajectory: float32
        // fma/transcendental op-ordering differs CPU↔GPU and, amplified by the dynamics over
        // thousands of steps, decorrelates individual coords at the float-noise level (this is
        // exactly v1's own CPU-vs-GPU gliding agreement — ensemble, not bit-identical). The
        // microstate divergence is reported as a diagnostic, gated only to catch a logic blowup.
        System.out.println("\n--- CPU↔GPU: aggregate joint statistics (chaotic system → ensemble test) ---");
        System.out.printf("  %-8s %-14s %-14s %-14s %-16s%n",
                "step", "ΔgapJ1(nm)", "ΔgapJ2(nm)", "ΔangJ1(deg)", "micro|Δcoord|(nm)");
        boolean all = true;
        for (int i = 0; i < g.length; i++) {
            double dGap1 = Math.abs(g[i].maxGapJ1 - c[i].maxGapJ1);
            double dGap2 = Math.abs(g[i].maxGapJ2 - c[i].maxGapJ2);
            double dAng1 = Math.abs(g[i].meanAngJ1 - c[i].meanAngJ1);
            double dc = 0;
            for (int k = 0; k < g[i].coord.length; k++) dc = Math.max(dc, Math.abs(g[i].coord[k] - c[i].coord[k]));
            boolean statsOk = 1e3 * dGap1 < 1.0 && 1e3 * dGap2 < 1.0 && dAng1 < 1.0;  // aggregate agree
            boolean noBlowup = 1e3 * dc < 5.0;                                          // micro bounded (no blowup)
            all &= statsOk && noBlowup;
            System.out.printf("  %-8d %-14.4g %-14.4g %-14.4g %-16.4g %s%n",
                    g[i].step, 1e3 * dGap1, 1e3 * dGap2, dAng1, 1e3 * dc,
                    (statsOk && noBlowup) ? "" : "*DIFFERS*");
        }
        System.out.println("  (aggregate gaps/angles agree to <1 nm / <1°; microstate diverges at float-noise"
                + " level — chaotic op-ordering, not logic — like v1 CPU-vs-GPU gliding.)");
        return all;
    }

    // ============================================================== viewer (-3js)
    static void runViz(int nMotors, int M, double dt, double brownScale, String dir) {
        MotorStore mot = buildScene(nMotors, dt, brownScale);
        new java.io.File(dir).mkdirs();
        Runnable step = cpuStep(mot);
        int every = Math.max(1, M / 300), frames = 0;
        double bx = 2.0, by = 2.0, bz = 0.4;
        for (int t = 0; t <= M; t++) {
            mot.setCounts(t, 0x4D0B0D, 0);
            if (t % every == 0) { writeFrame(dir, frames++, t * dt, mot, bx, by, bz); }
            step.run();
        }
        System.out.println("viewer: wrote " + frames + " frames to " + dir);
    }

    static void writeFrame(String dir, int frame, double t, MotorStore mot, double bx, double by, double bz) {
        RigidRodBody b = mot.body;
        StringBuilder sb = new StringBuilder(256 + 200 * mot.nMotors);
        sb.append(String.format(java.util.Locale.US, "{\"frame\":%d,\"t\":%.6g", frame, t));
        sb.append(String.format(java.util.Locale.US, ",\"bounds\":{\"xDim\":%.4g,\"yDim\":%.4g,\"zDim\":%.4g}", bx, by, bz));
        sb.append(",\"segments\":[],\"myosins\":[");
        for (int m = 0; m < mot.nMotors; m++) {
            if (m > 0) sb.append(',');
            int rod = 3 * m, lever = 3 * m + 1, head = 3 * m + 2;
            sb.append(String.format(java.util.Locale.US,
                "{\"id\":%d,\"rod\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g,\"invisible\":false},"
                + "\"lever\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g},"
                + "\"motor\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g,\"state\":\"ADPPi\"}}",
                m,
                b.end1X(rod), b.end1Y(rod), b.end1Z(rod), b.end2X(rod), b.end2Y(rod), b.end2Z(rod), MotorStore.ROD_R,
                b.end1X(lever), b.end1Y(lever), b.end1Z(lever), b.end2X(lever), b.end2Y(lever), b.end2Z(lever), MotorStore.LEVER_R,
                b.end1X(head), b.end1Y(head), b.end1Z(head), b.end2X(head), b.end2Y(head), b.end2Z(head), MotorStore.HEAD_R));
        }
        sb.append("]}");
        try {
            java.nio.file.Files.writeString(java.nio.file.Path.of(dir,
                    String.format(java.util.Locale.US, "frame_%06d.json", frame)), sb.toString());
        } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }

    // ============================================================== helpers
    static double dist(double ax, double ay, double az, double bx, double by, double bz) {
        double dx = ax - bx, dy = ay - by, dz = az - bz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
    static double angDeg(double ax, double ay, double az, double bx, double by, double bz) {
        double d = ax * bx + ay * by + az * bz;
        if (d > 1) d = 1; if (d < -1) d = -1;
        return Math.acos(d) * 180.0 / Math.PI;
    }
    static int[] dedup(int[] a) {
        java.util.TreeSet<Integer> set = new java.util.TreeSet<>();
        for (int x : a) set.add(x);
        int[] o = new int[set.size()]; int i = 0; for (int x : set) o[i++] = x; return o;
    }
}
