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
 * Increment 4b-iv: the gliding assay — the payoff. Assembles the validated pieces (4a binding, 4b-i
 * body, 4b-ii cross-bridge + gather, 4b-iii cycle + stroke + F-catch-slip, inc-2 chain filament) into
 * v1's gliding assay and measures gliding velocity + avgBound vs the v1 fixture (8.33/8.23 µm/s, 7.64/
 * 7.21 bound, glide −x). MATCH v1's CONFIG faithfully; do NOT tune the physics (all rates/forces are
 * the frozen 4a–4b-iii constants).
 *
 * v1 config (glidingAssay500_val): dt=1e-5, fixedMyosinDensity=500/µm², fixedMyosinZValue=−0.05, the
 * gliding filament is a ~11-segment chain (2 µm, 64-monomer segments) at z=0 along +x (plus-end +x),
 * chain params fracMove=0.5/fracR=0.1/fracMoveTorq=0.2; surface = the MyosinFixed tail anchor (4b-i) +
 * the filament held by its bound motors (no separate z-clamp). We use a density-faithful STRIP of bed
 * around the filament's path (not the full 14×2 box) — avgBound/velocity are local to the filament, so
 * the strip at 500/µm² is faithful and keeps the motor count tractable.
 *
 * This file builds the CPU pipeline + the cheap probe FIRST (does it glide −x, stably, at ~the right
 * avgBound?). The GPU plan + the converged ensemble follow only if the probe passes.
 */
public final class GlidingHarness {

    static final double DT = 1.0e-5;
    static final double ANCHOR_Z = -0.05;       // fixedMyosinZValue
    static final double FIL_Z = 0.0;            // gliding filament z (v1)
    static final double DENSITY = 500.0;        // motors / µm²
    static final int    FIL_SEGS = 11;          // ~2 µm of 64-monomer segments
    static final int    FIL_MONO = 64;          // filSegLength (gliding override)
    // bed geometry (default ≈6×2 box; -full → v1's 14×2 box / ~14000 motors for the fixture comparison)
    static double bX0 = 2.2, bXlo = -3.5, bYhalf = 1.0;
    static int SEED = 0x6111D;   // varied across an ensemble (placement + RNG)

    public static void main(String[] args) {
        int M = 2000;
        String viz = null;
        boolean diag = false;
        java.util.List<String> pos = new java.util.ArrayList<>();
        boolean gpu = false;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-3js")) viz = args[++i];
            else if (args[i].equals("-diag")) diag = true;
            else if (args[i].equals("-gpu")) gpu = true;
            else if (args[i].equals("-full")) { bX0 = 5.87; bXlo = -7.0; bYhalf = 1.0; }  // v1 14×2 box, ~13.4k motors
            else if (args[i].equals("-seed")) SEED = 0x6111D + 7919 * Integer.parseInt(args[++i]);
            else pos.add(args[i]);
        }
        if (!pos.isEmpty()) M = Integer.parseInt(pos.get(0));

        System.out.println("=== Soft Box increment 4b-iv — gliding assay (cheap probe) ===");
        Scene sc = buildScene();
        System.out.printf("config: %d-seg filament (%.2f µm) at z=%.3f, %d motors @ %.0f/µm² strip, dt=%.0e%n",
                sc.fil.n, sc.fil.n * sc.segL, FIL_Z, sc.mot.nMotors, DENSITY, DT);

        if (viz != null) { runViz(sc, Math.max(M, 20000), viz); return; }
        if (diag) { diagnose(sc, Math.max(M, 8000)); return; }
        if (gpu) { gpuProbe(sc, M); return; }
        probe(sc, M);
    }

    static final class Scene {
        FilamentStore fil; MotorStore mot;
        FloatArray bondData, xbParams;
        IntArray segMotorCount, segMotorOffsets, segMotorMyo;
        IntArray reachSeg; IntArray reachCount;
        double segL, x0;
    }

    static Scene buildScene() {
        Scene sc = new Scene();
        int nSeg = FIL_SEGS;
        double L = (FIL_MONO + 1) * Constants.actinMonoRadius;     // µm, 64-monomer segment
        sc.segL = L;
        // ---- filament: chain along +x at z=0, centered so it glides −x within the bed ----
        FilamentStore fil = new FilamentStore(nSeg);
        double x0 = bX0;               // filament +x end; glides −x with room for a long run
        sc.x0 = x0;
        for (int s = 0; s < nSeg; s++) {
            fil.monomerCount.set(s, FIL_MONO);
            fil.setUVec(s, 1f, 0f, 0f); fil.setYVec(s, 0f, 1f, 0f);     // plus-end +x
            fil.setCoord(s, (float) (x0 - (nSeg - 1 - s) * L), 0f, (float) FIL_Z);
            fil.brownTransScale.set(s, (float) Constants.BTransCoeff);   // 1.0
            boolean end = (s == 0 || s == nSeg - 1);
            fil.brownRotScale.set(s, (float) (end ? Constants.BRotCoeff : 0.0));   // inc-2b: ends only
            // linear chain topology: end2 of s ↔ end1 of s+1
            if (s < nSeg - 1) { fil.end2NbrSlot.set(s, s + 1); fil.end2NbrSide.set(s, 0); }
            if (s > 0)        { fil.end1NbrSlot.set(s, s - 1); fil.end1NbrSide.set(s, 1); }
        }
        DragTensorSystem.run(fil);
        fil.setParams(DT, Math.sqrt(2.0 * Constants.kT / DT));
        fil.setCounts(0, 0xF11A);
        // gliding chain params (config-matched, NOT tuned): fracMove 0.5 / fracR 0.1 / fracMoveTorq 0.2
        fil.chainParams.set(0, (float) DT); fil.chainParams.set(1, 0.5f); fil.chainParams.set(2, 0.1f);
        fil.chainParams.set(3, 0.2f); fil.chainParams.set(4, 0f); fil.chainParams.set(5, 1.0e-20f);
        fil.chainParams.set(6, (float) Constants.actinMonoRadius);
        DerivedGeometrySystem.derive(fil.coord, fil.uVec, fil.yVec, fil.zVec, fil.end1, fil.end2, fil.segLength, fil.counts);

        // ---- motor bed: density-faithful patch around the filament's −x path. Wide enough in y that
        //      the filament's ends stay over motors as it rotates/wanders (v1's bed is the full 2µm-wide
        //      box; a narrow strip lets the ends rotate out → unsupported ends lag → velocity drops). ----
        double bedXlo = bXlo, bedXhi = x0 + 0.5;     // long enough for the −x glide
        double bedYhalf = bYhalf;                     // y-width (boxYDim) → governs the finite-size effect
        double bedX = bedXhi - bedXlo, bedY = 2 * bedYhalf;
        int nMot = (int) Math.round(DENSITY * bedX * bedY);
        MotorStore mot = new MotorStore(nMot);
        java.util.Random rng = new java.util.Random(SEED);
        for (int m = 0; m < nMot; m++) {
            float ax = (float) (bedXlo + rng.nextDouble() * bedX);
            float ay = (float) (-bedYhalf + rng.nextDouble() * bedY);
            mot.assembleArticulated(m, ax, ay, (float) ANCHOR_Z, 0f, 0f, 1f, (float) Constants.BTransCoeff);
        }
        DragTensorSystem.run(mot);
        mot.setBodyParams(DT); mot.setJointParams(DT); mot.setKinParams(0.006, -0.4, DT); mot.setNucParams(DT);
        mot.nucleotideState.init(MotorStore.NUC_NONE);

        int MAXC = SpatialGrid.MAX_CAND;
        sc.bondData = new FloatArray(nMot * CrossBridgeSystem.STRIDE); sc.bondData.init(0f);
        sc.xbParams = FloatArray.fromElements((float) 1.0e-9, 90f, 0.4f, (float) DT, (float) MotorStore.HEAD_LEN);
        sc.segMotorCount = new IntArray(nSeg); sc.segMotorOffsets = new IntArray(nSeg + 1); sc.segMotorMyo = new IntArray(nMot);
        sc.reachSeg = new IntArray(nMot * MAXC); sc.reachSeg.init(-1); sc.reachCount = new IntArray(nMot);
        sc.fil = fil; sc.mot = mot;
        return sc;
    }

    /** One gliding step (CPU runner). */
    static void step(Scene sc, int t) {
        FilamentStore f = sc.fil; MotorStore mot = sc.mot; RigidRodBody b = mot.body;
        mot.setCounts(t, SEED, f.n);
        f.counts.set(1, t);
        // --- binding (dynamic) ---
        MotorStore.publishHeadFromBody(b.coord, b.uVec, b.segLength, mot.head, mot.uVec, mot.rodUVec, mot.counts);
        BindingDetectionSystem.bruteReachable(mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, sc.reachSeg, sc.reachCount, mot.kinParams, mot.counts);
        NucleotideCycleSystem.catchSlipRelease(mot.boundSeg, mot.forceDotFil, mot.stats, mot.kinParams, mot.counts);
        BindingDetectionSystem.bindNearest(mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, sc.reachSeg, sc.reachCount, mot.boundSeg, mot.bindArc, mot.kinParams, mot.counts);
        // --- motor nucleotide cycle + dynamics ---
        NucleotideCycleSystem.cycle(mot.nucleotideState, mot.boundSeg, mot.forceDotHist, mot.nucParams, mot.counts);
        ChainBendingForceSystem.zeroAccumulators(b.forceSum, b.torqueSum, mot.counts);
        BrownianForceSystem.brownianForce(b.randForce, b.randTorque, b.bTransGam, b.bRotGam, b.brownTransScale, b.brownRotScale, mot.bodyParams, mot.counts);
        MotorJointSystem.joints(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, mot.nucleotideState, mot.jointParams, mot.counts);
        TailAnchorSystem.anchor(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, mot.anchor, mot.jointParams, mot.counts);
        CrossBridgeSystem.bondForces(b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength,
                mot.boundSeg, mot.bindArc, mot.nucleotideState, sc.bondData, sc.xbParams);
        CrossBridgeSystem.applyHeadForce(sc.bondData, b.forceSum, b.torqueSum, mot.counts);
        RigidRodLangevinIntegrationSystem.integrate(b.coord, b.uVec, b.yVec, b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, mot.bodyParams, mot.counts);
        DerivedGeometrySystem.derive(b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts);
        CrossBridgeSystem.registerForceDot(sc.bondData, mot.boundSeg, mot.forceDotFil, mot.forceDotHist, mot.forceDotPlace, mot.counts);
        // --- filament dynamics: chain + Brownian + the gathered cross-bridge, then integrate ---
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

    // ===================== GPU TaskGraph (GpuStepper — same systems, device dispatch) =================
    static final int B = 64;
    static GridScheduler sched;
    /** The full gliding step as ONE device-resident TaskGraph (23 kernels) — mirrors `step()` exactly.
     *  No per-step host pull (residency test); host reads fil.coord + boundSeg at output cadence only. */
    static TornadoExecutionPlan buildPlan(Scene sc) {
        FilamentStore f = sc.fil; MotorStore mot = sc.mot; RigidRodBody b = mot.body;
        TaskGraph tg = new TaskGraph("gliding")
            .transferToDevice(DataTransferMode.FIRST_EXECUTION,
                    b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, b.bTransGam, b.bRotGam,
                    b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.brownTransScale, b.brownRotScale,
                    mot.head, mot.uVec, mot.rodUVec, mot.anchor, mot.boundSeg, mot.bindArc, mot.nucleotideState,
                    mot.forceDotFil, mot.forceDotHist, mot.forceDotPlace, mot.stats,
                    mot.bodyParams, mot.jointParams, mot.nucParams, mot.kinParams,
                    sc.bondData, sc.xbParams, sc.segMotorCount, sc.segMotorOffsets, sc.segMotorMyo, sc.reachSeg, sc.reachCount,
                    f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.bTransGam, f.bRotGam,
                    f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.brownTransScale, f.brownRotScale,
                    f.params, f.chainParams, f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide)
            .transferToDevice(DataTransferMode.EVERY_EXECUTION, mot.counts, f.counts)
            .task("publishHead", MotorStore::publishHeadFromBody, b.coord, b.uVec, b.segLength, mot.head, mot.uVec, mot.rodUVec, mot.counts)
            .task("reach", BindingDetectionSystem::bruteReachable, mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, sc.reachSeg, sc.reachCount, mot.kinParams, mot.counts)
            .task("release", NucleotideCycleSystem::catchSlipRelease, mot.boundSeg, mot.forceDotFil, mot.stats, mot.kinParams, mot.counts)
            .task("bind", BindingDetectionSystem::bindNearest, mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, sc.reachSeg, sc.reachCount, mot.boundSeg, mot.bindArc, mot.kinParams, mot.counts)
            .task("cycle", NucleotideCycleSystem::cycle, mot.nucleotideState, mot.boundSeg, mot.forceDotHist, mot.nucParams, mot.counts)
            .task("zeroMot", ChainBendingForceSystem::zeroAccumulators, b.forceSum, b.torqueSum, mot.counts)
            .task("brownMot", BrownianForceSystem::brownianForce, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, b.brownTransScale, b.brownRotScale, mot.bodyParams, mot.counts)
            .task("joints", MotorJointSystem::joints, b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, mot.nucleotideState, mot.jointParams, mot.counts)
            .task("anchor", TailAnchorSystem::anchor, b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, mot.anchor, mot.jointParams, mot.counts)
            .task("bond", CrossBridgeSystem::bondForces, b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength, mot.boundSeg, mot.bindArc, mot.nucleotideState, sc.bondData, sc.xbParams)
            .task("applyHead", CrossBridgeSystem::applyHeadForce, sc.bondData, b.forceSum, b.torqueSum, mot.counts)
            .task("integMot", RigidRodLangevinIntegrationSystem::integrate, b.coord, b.uVec, b.yVec, b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, mot.bodyParams, mot.counts)
            .task("deriveMot", DerivedGeometrySystem::derive, b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts)
            .task("register", CrossBridgeSystem::registerForceDot, sc.bondData, mot.boundSeg, mot.forceDotFil, mot.forceDotHist, mot.forceDotPlace, mot.counts)
            .task("zeroFil", ChainBendingForceSystem::zeroAccumulators, f.forceSum, f.torqueSum, f.counts)
            .task("brownFil", BrownianForceSystem::brownianForce, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.brownTransScale, f.brownRotScale, f.params, f.counts)
            .task("chain", ChainBendingForceSystem::chainForces, f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide, f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts)
            .task("csrHist", CrossBridgeSystem::csrHistogram, mot.boundSeg, mot.counts, sc.segMotorCount)
            .task("csrScan", CrossBridgeSystem::csrScan, mot.counts, sc.segMotorCount, sc.segMotorOffsets)
            .task("csrScatter", CrossBridgeSystem::csrScatter, mot.boundSeg, mot.counts, sc.segMotorOffsets, sc.segMotorCount, sc.segMotorMyo)
            .task("gather", CrossBridgeSystem::segGather, sc.segMotorOffsets, sc.segMotorMyo, sc.bondData, f.forceSum, f.torqueSum, mot.counts)
            .task("integFil", RigidRodLangevinIntegrationSystem::integrate, f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts)
            .task("deriveFil", DerivedGeometrySystem::derive, f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts)
            .transferToHost(DataTransferMode.UNDER_DEMAND, f.coord, f.uVec, f.end1, f.end2, mot.boundSeg);

        int nB = 3 * mot.nMotors, nM = mot.nMotors, nSeg = f.n;
        sched = new GridScheduler();
        for (String t : new String[]{ "publishHead","reach","release","bind","cycle","anchor","bond","applyHead","register" }) addW("gliding." + t, pad(nM));
        for (String t : new String[]{ "zeroMot","brownMot","joints","integMot","deriveMot" }) addW("gliding." + t, pad(nB));
        for (String t : new String[]{ "zeroFil","brownFil","chain","gather","integFil","deriveFil" }) addW("gliding." + t, pad(nSeg));
        for (String t : new String[]{ "csrHist","csrScan","csrScatter" }) addS("gliding." + t);
        return new TornadoExecutionPlan(tg.snapshot());
    }
    static int pad(int n) { return ((n + B - 1) / B) * B; }
    static void addW(String n, int g) { WorkerGrid w = new WorkerGrid1D(g); w.setLocalWork(B, 1, 1); sched.addWorkerGrid(n, w); }
    static void addS(String n) { WorkerGrid w = new WorkerGrid1D(1); w.setLocalWork(1, 1, 1); sched.addWorkerGrid(n, w); }

    static double centroidX(FilamentStore f) { double s = 0; for (int i = 0; i < f.n; i++) s += f.coordX(i); return s / f.n; }
    static double centroidZ(FilamentStore f) { double s = 0; for (int i = 0; i < f.n; i++) s += f.coordZ(i); return s / f.n; }
    /** filament end-to-end y excursion (a rotation/wander proxy): max−min segment y. */
    static double ySpread(FilamentStore f) {
        double lo = 1e9, hi = -1e9; for (int i = 0; i < f.n; i++) { double y = f.coordY(i); lo = Math.min(lo, y); hi = Math.max(hi, y); } return hi - lo;
    }
    static long bound(MotorStore m) { long c = 0; for (int i = 0; i < m.nMotors; i++) if (m.boundSeg.get(i) >= 0) c++; return c; }

    static void probe(Scene sc, int M) {
        System.out.println("\n--- cheap probe: does it glide −x, stably, at ~the right avgBound? ---");
        double cx0 = centroidX(sc.fil), cxHalf = cx0; double boundSumHalf = 0; long sampleHalf = 0;
        long sample = 0; double boundSum = 0; boolean nan = false;
        int report = Math.max(1, M / 10);
        System.out.printf("  %-8s %-12s %-12s %-10s%n", "step", "centroidX", "centroidZ", "avgBound(inst)");
        for (int t = 0; t < M; t++) {
            step(sc, t);
            boundSum += bound(sc.mot); sample++;
            if (t == M / 2) cxHalf = centroidX(sc.fil);
            if (t >= M / 2) { boundSumHalf += bound(sc.mot); sampleHalf++; }
            if (t % report == 0 || t == M - 1) {
                double cx = centroidX(sc.fil), cz = centroidZ(sc.fil);
                if (Double.isNaN(cx) || Double.isNaN(cz)) { nan = true; }
                System.out.printf("  %-8d %-12.5f %-12.5f %-10d%n", t, cx, cz, bound(sc.mot));
            }
        }
        // diagnostic: where are the heads relative to the filament line (y=0,z=FIL_Z)?
        RigidRodBody b = sc.mot.body; double minCon = 1e9, sumZ = 0, sumZcnt = 0;
        for (int m = 0; m < sc.mot.nMotors; m++) {
            if (Math.abs(sc.mot.anchorY(m)) > 0.006) continue;     // motors under the filament line
            int h = 3 * m + 2;
            double tz = b.coordZ(h) + 0.5 * MotorStore.HEAD_LEN * b.uVecZ(h);
            double ty = b.coordY(h) + 0.5 * MotorStore.HEAD_LEN * b.uVecY(h);
            double con = Math.sqrt((tz - FIL_Z) * (tz - FIL_Z) + ty * ty);
            minCon = Math.min(minCon, con); sumZ += tz; sumZcnt++;
        }
        System.out.printf("  [diag] heads under the filament line: mean tipZ=%.4f µm, min conDist=%.4f µm (reach 0.006)%n",
                sumZcnt > 0 ? sumZ / sumZcnt : 0, minCon);
        double cxN = centroidX(sc.fil);
        double vel = (cxN - cx0) / (M * DT);     // µm/s (expect negative)
        double avgB = boundSum / sample;
        double velSteady = (cxN - cxHalf) / ((M - M / 2) * DT);   // 2nd-half (settled) velocity
        double avgBHalf = sampleHalf > 0 ? boundSumHalf / sampleHalf : 0;
        System.out.printf("%n  net centroid Δx = %.4f µm over %.4f s  ⇒  velocity(net) = %.3f µm/s (%s)%n",
                cxN - cx0, M * DT, vel, vel < 0 ? "−x ✓" : "+x ?");
        System.out.printf("  STEADY (2nd half): velocity = %.3f µm/s, avgBound = %.2f  (v1: 8.33 µm/s, 7.6)%n", velSteady, avgBHalf);
        System.out.printf("  avgBound(all) = %.2f   filament z drift = %.4f µm   y-spread = %.3f µm (rotation)   NaN=%s%n",
                avgB, centroidZ(sc.fil) - FIL_Z, ySpread(sc.fil), nan);
        System.out.println("\n  [probe is diagnostic — not the fixture gate; the converged ensemble follows if this looks right]");
    }

    /** Localize the velocity shortfall: bound-state distribution, the forceDotFil load sign (catch vs
     *  slip), release + power-stroke rates, and the per-stroke filament advance. (Measurement only.) */
    static void diagnose(Scene sc, int M) {
        MotorStore mot = sc.mot; int nM = mot.nMotors;
        int warm = 2000;
        long[] stateCnt = new long[4]; long boundStepsTot = 0, releaseTot = 0, strokeTot = 0;
        double fdSum = 0; long fdPos = 0, fdN = 0;
        int[] prevBound = new int[nM], prevState = new int[nM];
        for (int m = 0; m < nM; m++) { prevBound[m] = mot.boundSeg.get(m); prevState[m] = mot.nucleotideState.get(m); }
        double cxStart = 0; int samp = 0;
        for (int t = 0; t < M; t++) {
            step(sc, t);
            if (t == warm) cxStart = centroidX(sc.fil);
            if (t >= warm) {
                samp++;
                for (int m = 0; m < nM; m++) {
                    int bs = mot.boundSeg.get(m), st = mot.nucleotideState.get(m);
                    if (bs >= 0) {
                        boundStepsTot++; stateCnt[st]++;
                        float fd = mot.forceDotFil.get(m); fdSum += fd; fdN++; if (fd > 0) fdPos++;
                        // power stroke = ADPPi→ADP while bound
                        if (prevState[m] == MotorStore.NUC_ADPPI && st == MotorStore.NUC_ADP) strokeTot++;
                    }
                    // release = bound → cooldown
                    if (prevBound[m] >= 0 && bs == MotorStore.FREE_COOLDOWN) releaseTot++;
                    prevBound[m] = bs; prevState[m] = st;
                }
            }
        }
        double cxEnd = centroidX(sc.fil);
        double vel = (cxEnd - cxStart) / (samp * DT);
        double avgB = boundStepsTot / (double) samp;
        double boundTimeSteps = boundStepsTot / (double) releaseTot;        // mean bound lifetime
        double strokeRatePerMotor = strokeTot / (double) boundStepsTot;      // power strokes per bound-motor-step
        double advancePerStroke = Math.abs(cxEnd - cxStart) / strokeTot;     // µm filament advance per power stroke
        System.out.println("\n=== gliding diagnostics (post-warmup) ===");
        System.out.printf("  velocity = %.3f µm/s, avgBound = %.2f%n", vel, avgB);
        System.out.printf("  bound-state distribution: NONE %.1f%%  ATP %.1f%%  ADPPi %.1f%%  ADP %.1f%%%n",
                100.0 * stateCnt[0] / boundStepsTot, 100.0 * stateCnt[1] / boundStepsTot,
                100.0 * stateCnt[2] / boundStepsTot, 100.0 * stateCnt[3] / boundStepsTot);
        System.out.printf("  forceDotFil (bound): mean = %.3g N, %.1f%% positive (catch/load-resisting)%n",
                fdSum / fdN, 100.0 * fdPos / fdN);
        System.out.printf("  mean bound-time = %.0f steps (%.3f ms);  catch-slip release rate = %.0f /s%n",
                boundTimeSteps, boundTimeSteps * DT * 1e3, 1.0 / (boundTimeSteps * DT));
        System.out.printf("  power strokes (ADPPi→ADP while bound) = %.4f /bound-motor-step  ⇒  %.0f /s per bound motor%n",
                strokeRatePerMotor, strokeRatePerMotor / DT);
        System.out.printf("  filament advance per power stroke = %.2f nm  (unloaded stroke ≈ 7 nm)%n", advancePerStroke * 1e3);
    }

    /** GPU probe: run the device-resident gliding TaskGraph; sample velocity/avgBound at output cadence
     *  (no per-step host pull — the residency test) and report throughput. */
    static void gpuProbe(Scene sc, int M) {
        System.out.println("\n--- GPU probe: device-resident gliding TaskGraph (23 kernels), " + sc.mot.nMotors + " motors ---");
        TornadoExecutionPlan plan = buildPlan(sc);
        double cx0 = centroidX(sc.fil);
        // warm-up execute (PTX compile) — untimed
        sc.mot.setCounts(0, SEED, sc.fil.n); sc.fil.counts.set(1, 0);
        plan.withGridScheduler(sched).execute();
        long sample = 0; double boundSum = 0; double cxHalf = cx0; boolean nan = false;
        int report = Math.max(1, M / 10);
        long t0 = System.nanoTime();
        for (int t = 1; t < M; t++) {
            sc.mot.setCounts(t, SEED, sc.fil.n); sc.fil.counts.set(1, t);
            TornadoExecutionResult res = plan.withGridScheduler(sched).execute();
            if (t % report == 0 || t == M - 1) {
                res.transferToHost(sc.fil.coord, sc.mot.boundSeg);
                double cx = centroidX(sc.fil);
                if (Double.isNaN(cx)) nan = true;
                if (t >= M / 2 && cxHalf == cx0) cxHalf = cx;
                boundSum += bound(sc.mot); sample++;
                System.out.printf("  step %-8d centroidX=%.5f  avgBound(inst)=%d%n", t, cx, bound(sc.mot));
            }
        }
        long t1 = System.nanoTime();
        double sec = (t1 - t0) / 1e9, stepsPerSec = (M - 1) / sec;
        double cxN = centroidX(sc.fil);
        double vel = (cxN - cx0) / (M * DT), velSteady = (cxN - cxHalf) / ((M - M / 2) * DT);
        double avgB = boundSum / sample;
        System.out.printf("%n  velocity(net) = %.3f µm/s (%s), STEADY = %.3f, avgBound = %.2f  (v1 fixture 8.33/7.6), NaN=%s%n",
                vel, vel < 0 ? "−x ✓" : "+x ?", velSteady, avgB, nan);
        System.out.printf("  GPU THROUGHPUT: %.0f steps/s (%.2f ms/step) at %d motors; device-resident, host pull only at output cadence%n",
                stepsPerSec, 1e3 / stepsPerSec, sc.mot.nMotors);
    }

    static void runViz(Scene sc, int M, String dir) {
        new java.io.File(dir).mkdirs();
        int every = Math.max(1, M / 400), frames = 0;
        for (int t = 0; t <= M; t++) {
            if (t % every == 0) writeFrame(dir, frames++, t * DT, sc);
            step(sc, t);
        }
        System.out.println("viewer: wrote " + frames + " frames to " + dir);
    }
    static final String[] SN = { "NONE", "ATP", "ADPPi", "ADP" };
    static void writeFrame(String dir, int frame, double t, Scene sc) {
        FilamentStore f = sc.fil; MotorStore mot = sc.mot; RigidRodBody b = mot.body;
        StringBuilder sb = new StringBuilder(1024);
        sb.append(String.format(java.util.Locale.US, "{\"frame\":%d,\"t\":%.6g,\"bounds\":{\"xDim\":5,\"yDim\":1,\"zDim\":0.4}", frame, t));
        sb.append(",\"segments\":[");
        for (int s = 0; s < f.n; s++) {
            if (s > 0) sb.append(',');
            sb.append(String.format(java.util.Locale.US, "{\"id\":%d,\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.5g,\"notADPRatio\":1.0,\"cofilinCount\":0}",
                s, f.end1.get(s), f.end1.get(f.n + s), f.end1.get(2 * f.n + s), f.end2.get(s), f.end2.get(f.n + s), f.end2.get(2 * f.n + s), Constants.radius));
        }
        sb.append("],\"myosins\":[");
        boolean first = true;
        for (int m = 0; m < mot.nMotors; m++) {
            // only emit motors near the filament (keep frames small)
            if (Math.abs(b.coordY(3 * m)) > 0.05) continue;
            if (!first) sb.append(','); first = false;
            int rod = 3 * m, lever = 3 * m + 1, head = 3 * m + 2;
            sb.append(String.format(java.util.Locale.US,
                "{\"id\":%d,\"rod\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g,\"invisible\":false},"
                + "\"lever\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g},"
                + "\"motor\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g,\"state\":\"%s\"}}",
                m, b.end1X(rod), b.end1Y(rod), b.end1Z(rod), b.end2X(rod), b.end2Y(rod), b.end2Z(rod), MotorStore.ROD_R,
                b.end1X(lever), b.end1Y(lever), b.end1Z(lever), b.end2X(lever), b.end2Y(lever), b.end2Z(lever), MotorStore.LEVER_R,
                b.end1X(head), b.end1Y(head), b.end1Z(head), b.end2X(head), b.end2Y(head), b.end2Z(head), MotorStore.HEAD_R, SN[mot.nucleotideState.get(m)]));
        }
        sb.append("]}");
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(dir, String.format(java.util.Locale.US, "frame_%06d.json", frame)), sb.toString()); }
        catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }
}
