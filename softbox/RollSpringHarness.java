package softbox;

import uk.ac.manchester.tornado.api.GridScheduler;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.WorkerGrid;
import uk.ac.manchester.tornado.api.WorkerGrid1D;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * Azimuthal-binding Increment 1 — the torsional-roll-spring PROTOTYPE (physical twist), in isolation.
 *
 * Answers the one route-deciding question BEFORE any binding is built:
 *   "Is there a torsional-roll stiffness that makes a multi-segment filament hold a COHERENT, visible helical
 *    twist AND stay numerically STABLE at production dt = 1e-5?"
 *
 * Single long filament, NO motors / NO binding / NO off-axis bond / NO turnover — isolate the spring
 * (RollSpringSystem) + its thermostat (RollSpringSystem.dampRoll). New files only; every existing file
 * (and thus canonical/production) is byte-identical by construction.
 *
 * Per-step device graph: zero → brownian → dampRoll → chain(F3/F4 bending) → rollspring → integrate → derived.
 * The chain keeps the filament connected + semiflexible (bending); the roll spring holds the coarse frame twist.
 *
 * Reports: the twist profile (per-joint roll about u vs contour), coherence (std across joints), the stiffness
 * sweep + stability verdict at 1e-5 (with the roll-mode α), CPU≡GPU parity on the deterministic relaxation, and
 * the handedness of the coarse twist. Route outcome (A) stable-coherent exists / (B) coherent only when raw-
 * unstable ⇒ implicit / (C) no coherent-and-stable regime ⇒ fall back to analytic φ₀.
 */
public final class RollSpringHarness {

    static final int BLOCK_SIZE = 64;
    static GridScheduler sched;

    // actin 13/6 genetic helix — per-monomer azimuthal advance magnitude (v1 helixAngInc = π/13.333 ⇒
    // advance = π − helixAngInc = 166.5°). Handedness DERIVED FRESH: LEFT-handed ⇒ NEGATIVE about pointed→barbed.
    static final double TWIST_PER_MON_DEG = -166.5;

    // config (flags)
    static boolean cpu = false;
    static String jsDir = null;
    static int  nSeg = 64;
    static int  M = 200000;
    static double dt = 1e-5;
    static double rollStiff = 0.5;     // f, the fraction-per-step stiffness (default form)
    static double rollDamp = 0.1;      // thermostat: fraction of the roll Brownian kick retained
    static double brot = 1.0;          // rotational Brownian scale on ALL segments (roll IS kicked — the hard test)
    static double btrans = 1.0;        // translational Brownian scale
    static int    mode = 2;            // DEFAULT = 2 springs-continuum (canonical, dt-convergent, Inc 1b). 0 fraction (-fraction), 1 raw Hookean (-rollhooke)
    static double kHooke = 1e-22;      // raw Hookean stiffness (N·m/rad) when mode 1
    static double refDt = 1e-5;        // springs-continuum reference dt (fixed stiffness k = f·γ_red/refDt)
    static boolean dtconv = false;     // -dtconv: sweep dt for fraction vs springs (the convergence demonstration)
    static boolean startStraight = false;   // start torsionally straight (watch it wind up) vs at twisted rest
    static int    seed = 12345;
    static boolean sweep = false;

    public static void main(String[] args) {
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-cpu": cpu = true; break;
                case "-3js": jsDir = args[++i]; break;
                case "-n": nSeg = Integer.parseInt(args[++i]); break;
                case "-M": M = Integer.parseInt(args[++i]); break;
                case "-dt": dt = Double.parseDouble(args[++i]); break;
                case "-rollstiff": rollStiff = Double.parseDouble(args[++i]); break;
                case "-rolldamp": rollDamp = Double.parseDouble(args[++i]); break;
                case "-brot": brot = Double.parseDouble(args[++i]); break;
                case "-btrans": btrans = Double.parseDouble(args[++i]); break;
                case "-rollhooke": mode = 1; kHooke = Double.parseDouble(args[++i]); break;
                case "-springs": mode = 2; break;
                case "-fraction": mode = 0; break;   // opt into the OLD dt-dependent fraction form (contrast only)
                case "-refdt": refDt = Double.parseDouble(args[++i]); break;
                case "-straight": startStraight = true; break;
                case "-seed": seed = Integer.parseInt(args[++i]); break;
                case "-sweep": sweep = true; break;
                case "-dtconv": dtconv = true; break;
                default: /* ignore */ break;
            }
        }

        double restRad = wrapPi(nSeg > 0 ? Constants.stdSegLength * TWIST_PER_MON_DEG * Math.PI / 180.0 : 0.0);
        // monomers between adjacent equal-segment centers = one segment's worth (stdSegLength).
        System.out.println("########## Azimuthal-binding Inc 1 — torsional-roll-spring prototype ##########");
        System.out.printf("runner=%s  nSeg=%d  dt=%.1e  M=%d  seed=%d%n", cpu ? "CPU" : "GPU", nSeg, dt, M, seed);
        System.out.printf("twistPerMon=%.2f deg (LEFT-handed, derived)  restPerJoint(wrapped)=%.2f deg  monBetweenFrames=%d%n",
                TWIST_PER_MON_DEG, Math.toDegrees(restRad), Constants.stdSegLength);
        System.out.printf("spring: %s  f=%.3g  rolldamp=%.3g  brot=%.3g  btrans=%.3g  start=%s%n",
                mode == 1 ? ("RAW-HOOKE k=" + kHooke) : mode == 2 ? ("SPRINGS-continuum refDt=" + refDt) : "fraction-per-step",
                rollStiff, rollDamp, brot, btrans, startStraight ? "straight" : "twisted-rest");

        if (dtconv) { runDtConv(); return; }
        if (sweep) { runSweep(restRad); return; }

        // ---- headline coherence + stability run ----
        double[] prof = runCoherence(restRad, M, seed, jsDir, /*report=*/true);

        // ---- CPU≡GPU parity on the DETERMINISTIC relaxation (Brownian off, start straight) ----
        runParity(restRad);

        // ---- verdict ----
        System.out.println("=============================================================================");
        boolean nan = prof[3] > 0.5;
        double coherStd = prof[1];               // std of per-joint twist (deg)
        double meanTwist = prof[0];              // mean per-joint twist (deg)
        boolean coherent = !nan && coherStd < 25.0;   // frames hold a consistent net twist (std well under a wrap)
        boolean stable = !nan && prof[4] < 1.5;       // coherence-std bounded (late/mid ratio ~1); ≫1 = growing
        double alpha = (mode == 1) ? prof[5] : rollStiff;   // roll-mode α: fraction form ⇒ α=f; hooke ⇒ measured k·dt/γ
        System.out.printf("ROLL-MODE alpha = %.4g  (fraction-per-step: alpha=f, stable iff <2; raw-hooke: k*dt/gamma_roll)%n", alpha);
        System.out.printf("COHERENCE: mean per-joint twist=%.2f deg  std across joints=%.2f deg  ⇒ %s%n",
                meanTwist, coherStd, coherent ? "COHERENT" : "INCOHERENT");
        System.out.printf("STABILITY @ dt=%.1e: NaN=%b  late/mid coherence-std ratio=%.3f  ⇒ %s%n",
                dt, nan, prof[4] < 0 ? 0.0 : prof[4], stable ? "STABLE" : "UNSTABLE");
        System.out.printf("HANDEDNESS: coarse per-joint twist sign = %s (set: LEFT-handed actin, rest %.1f deg)%n",
                meanTwist < 0 ? "negative" : "positive", Math.toDegrees(restRad));
        String outcome = (coherent && stable) ? "A"
                : (mode == 1 && coherent && !stable) ? "B"
                : (!coherent) ? "C" : "A";
        System.out.println("ROUTE OUTCOME: (" + outcome + ") — "
                + (outcome.equals("A") ? "stable coherent regime exists at 1e-5 ⇒ PHYSICAL ROUTE VIABLE ⇒ proceed to Inc 2 (off-axis bond)"
                :  outcome.equals("B") ? "coherent only where explicitly unstable ⇒ needs implicit/heavier damping"
                :  "no coherent-and-stable regime ⇒ fall back to analytic φ₀"));
        System.out.println("#############################################################################");
    }

    // ------------------------------------------------------------------ coherence run
    /** Returns {meanTwistDeg, stdTwistDeg, -, nanFlag, late/mid std ratio, measuredAlpha}. */
    private static double[] runCoherence(double restRad, int steps, int runSeed, String dir, boolean report) {
        FilamentStore s = buildChain(restRad, !startStraight, brot);
        FloatArray rollParams = mkRollParams(restRad);
        Stepper stepper = cpu ? new CpuStepper(cpuStep(s, rollParams)) : new GpuStepper(buildPlan(s, rollParams), sched);
        s.setCounts(0, runSeed);

        FrameWriter fw = (dir != null) ? new FrameWriter(dir, 3.0, 3.0, 3.0) : null;
        int cad = Math.max(1, steps / 200);
        if (fw != null) fw.writeFrame(s, 0.0);

        boolean nan = false;
        java.util.List<Double> stdSeries = new java.util.ArrayList<>();
        double lastMean = 0, lastStd = 0;
        for (int step = 0; step < steps; step++) {
            s.counts.set(1, step);
            stepper.execute();
            if ((step + 1) % cad == 0) {
                stepper.pull(s.uVec, s.yVec);   // BOTH — twistProfile/jointRoll read yVec (stale on GPU if unpulled)
                if (hasNaN(s)) { nan = true; break; }
                double[] p = twistProfile(s);   // {mean, std}
                lastMean = p[0]; lastStd = p[1];
                stdSeries.add(p[1]);
                if (fw != null) { stepper.pull(s.coord, s.uVec, s.yVec, s.zVec, s.end1, s.end2); fw.writeFrame(s, (step + 1) * dt); }
            }
        }
        // stability: is the coherence-std bounded (not growing)? late window vs mid window.
        int ns = stdSeries.size();
        double mid = windowMean(stdSeries, ns / 4, ns / 2);
        double late = windowMean(stdSeries, 3 * ns / 4, ns);
        double ratio = (mid > 1e-9) ? late / mid : (late < 1e-9 ? 1.0 : 99.0);
        double measuredAlpha = mode == 1 ? kHooke * dt / rollDragX(s) : rollStiff;

        if (report) {
            System.out.println("---- coherence run ----");
            printProfile(s, restRad);
            System.out.printf("  coherence-std series: mid=%.3f late=%.3f deg  (ratio %.3f: ~1 bounded/stable, ≫1 growing)%n",
                    mid, late, ratio);
            if (fw != null) System.out.printf("  wrote %d frames to %s%n", fw.framesWritten(), fw.dir());
        }
        return new double[]{ lastMean, lastStd, 0, nan ? 1 : 0, nan ? 99 : ratio, measuredAlpha, nan ? 999 : late };
    }

    // ------------------------------------------------------------------ dt-convergence (Inc 1b payoff)
    /** Sweep dt for the fraction form (mode 0) vs the springs form (mode 2); report the late-window coherence
     *  std at each. Springs std should be dt-STABLE (equipartition rolldamp·√(kT/k_roll)); fraction std shrinks
     *  ∝√dt (freezes). Each dt runs the same SIM TIME so the steady-state is equally equilibrated. */
    private static void runDtConv() {
        double restRad = wrapPi(Constants.stdSegLength * TWIST_PER_MON_DEG * Math.PI / 180.0);
        double simTime = 0.10;   // s per point (roll relaxation ~ few steps ⇒ well-equilibrated)
        double[] dts = { 1e-5, 5e-6, 2.5e-6, 1e-6 };
        System.out.printf("---- dt-CONVERGENCE (late-window coherence std vs dt; f=%.2f rolldamp=%.2f refDt=%.1e) ----%n",
                rollStiff, rollDamp, refDt);
        System.out.printf("%10s | %6s | %16s | %16s%n", "dt", "steps", "FRACTION std(deg)", "SPRINGS std(deg)");
        for (double d : dts) {
            dt = d;
            int steps = (int) Math.round(simTime / d);
            mode = 0; double[] pf = runCoherence(restRad, steps, seed, null, false);
            mode = 2; double[] ps = runCoherence(restRad, steps, seed, null, false);
            System.out.printf("%10.2e | %6d | %14.2f%s | %14.2f%s%n", d, steps,
                    pf[6], pf[3] > 0.5 ? " NaN" : "", ps[6], ps[3] > 0.5 ? " NaN" : "");
        }
        System.out.println("  EXPECT: FRACTION std ∝ √dt (SHRINKS ~halving per 4× finer dt — freezes as dt→0);");
        System.out.println("          SPRINGS std dt-STABLE (converges to the equipartition value — dt-honest, like the canonical model).");
        mode = 0;
    }

    // ------------------------------------------------------------------ stiffness sweep
    private static void runSweep(double restRad) {
        System.out.println("---- STIFFNESS SWEEP (fraction-per-step form; coherence + stability @ dt=" + dt + ") ----");
        System.out.printf("%8s | %10s | %10s | %8s | %s%n", "f", "meanTwist", "stdJoints", "stdRatio", "verdict");
        double[] fs = { 0.1, 0.2, 0.3, 0.5, 0.8, 1.0, 1.5 };
        int steps = Math.min(M, 60000);
        for (double f : fs) {
            rollStiff = f; mode = 0;
            double[] p = runCoherence(restRad, steps, seed, null, false);
            boolean coh = p[3] < 0.5 && p[1] < 25.0;
            boolean stab = p[3] < 0.5 && p[4] < 2.0;
            System.out.printf("%8.2f | %10.2f | %10.2f | %8.3f | %s%n",
                    f, p[0], p[1], p[4] > 90 ? 99 : p[4], (coh && stab) ? "coherent+stable" : coh ? "coherent" : p[3] > 0.5 ? "NaN/BLOWUP" : "incoherent");
        }
        System.out.println("---- RAW-HOOKE contrast (mode 1; sweeping k to find the explicit blow-up) ----");
        System.out.printf("%12s | %10s | %10s | %s%n", "k(N·m/rad)", "alpha", "stdJoints", "verdict");
        double gamRoll = rollDragX(buildChain(restRad, true, brot));
        double[] ks = { 1e-21, 1e-20, 1e-19, 2.7e-19, 5e-19, 1e-18 };   // α=k·dt/γ_roll spans ~0.007→7 (blow-up >2)
        int hsteps = Math.min(M, 20000);
        for (double k : ks) {
            mode = 1; kHooke = k;
            double[] p = runCoherence(restRad, hsteps, seed, null, false);
            double alpha = k * dt / gamRoll;
            System.out.printf("%12.1e | %10.3g | %10.2f | %s%n",
                    k, alpha, p[3] > 0.5 ? Double.NaN : p[1], p[3] > 0.5 ? "BLOWUP (alpha>threshold)" : alpha > 2 ? "over-threshold (survived by luck)" : "stable");
        }
        mode = 0;
    }

    // ------------------------------------------------------------------ CPU≡GPU deterministic parity
    private static void runParity(double restRad) {
        System.out.println("---- CPU≡GPU parity (deterministic perturb-and-relax from twisted rest, Brownian OFF, 2000 steps) ----");
        debugOneStep(restRad);
        int K = 2000;
        double[] gp = deterministicRelax(restRad, K, /*useCpu=*/false);
        double[] cp = deterministicRelax(restRad, K, /*useCpu=*/true);
        double maxAbs = 0; int kmax = 0;
        for (int k = 0; k < gp.length; k++) { double d = Math.abs(gp[k] - cp[k]); if (d > maxAbs) { maxAbs = d; kmax = k; } }
        System.out.printf("  GPU joints[0..4]=%.2f %.2f %.2f %.2f %.2f  CPU=%.2f %.2f %.2f %.2f %.2f%n",
                gp[0], gp[1], gp[2], gp[3], gp[4], cp[0], cp[1], cp[2], cp[3], cp[4]);
        System.out.printf("  worst joint k=%d: GPU=%.3f CPU=%.3f%n", kmax, gp[kmax], cp[kmax]);
        System.out.printf("  max|Δ per-joint twist| GPU vs CPU = %.3e deg  (float32 last-bit expected) ⇒ %s%n",
                maxAbs, maxAbs < 1e-2 ? "CPU≡GPU PASS" : "*** MISMATCH ***");
    }

    /** One-step torque debug: seg-0 perturbed, print torqueSum_x after ONE step on GPU vs CPU. */
    private static void debugOneStep(double restRad) {
        for (int r = 0; r < 2; r++) {
            boolean useCpu = (r == 1);
            FilamentStore s = buildChain(restRad, true, 0.0);
            for (int k = 0; k < nSeg; k++) { s.brownTransScale.set(k, 0f); s.brownRotScale.set(k, 0f); }
            double a0 = 0.5;
            s.setYVec(0, 0f, (float) Math.cos(a0), (float) Math.sin(a0));
            DerivedGeometrySystem.derive(s.coord, s.uVec, s.yVec, s.zVec, s.end1, s.end2, s.segLength, s.counts);
            FloatArray rp = mkRollParams(restRad);
            Stepper st = useCpu ? new CpuStepper(cpuStep(s, rp)) : new GpuStepper(buildPlan(s, rp), sched);
            s.setCounts(0, 777); s.counts.set(1, 0);
            st.execute();
            st.pull(s.torqueSum, s.uVec, s.yVec);
            System.out.printf("  [%s] after 1 step: torqueSum_x[0]=%.3e [1]=%.3e ; yVec[0]=(%.4f,%.4f,%.4f) joint0=%.3f deg%n",
                    useCpu ? "CPU" : "GPU", s.torqueSum.get(0), s.torqueSum.get(1),
                    s.uVec.get(0), s.yVec.get(0), s.yVec.get(nSeg + 0), Math.toDegrees(jointRoll(s, 0)));
        }
    }

    /** Brownian-off perturb-and-relax: start at the twisted rest (coherent), rotate seg-0 yVec +0.5 rad about x
     *  beyond rest, relax K steps. Well-conditioned near the coherent fixed point ⇒ CPU≡GPU float32 last-bit.
     *  (A torsionally-STRAIGHT start is a degenerate saddle — uniform error ⇒ interior joint-torques cancel ⇒
     *  float-sensitive outcome — so it is deliberately NOT the parity config.) Returns per-joint twist (deg). */
    private static double[] deterministicRelax(double restRad, int K, boolean useCpu) {
        FilamentStore s = buildChain(restRad, /*twisted=*/true, /*brot=*/0.0);
        for (int k = 0; k < nSeg; k++) { s.brownTransScale.set(k, 0f); s.brownRotScale.set(k, 0f); }  // Brownian OFF
        double a0 = 0 * restRad + 0.5;   // seg 0 rest azimuth is 0; add +0.5 rad perturbation
        s.setYVec(0, 0f, (float) Math.cos(a0), (float) Math.sin(a0));
        DerivedGeometrySystem.derive(s.coord, s.uVec, s.yVec, s.zVec, s.end1, s.end2, s.segLength, s.counts);
        FloatArray rp = mkRollParams(restRad);
        Stepper st = useCpu ? new CpuStepper(cpuStep(s, rp)) : new GpuStepper(buildPlan(s, rp), sched);
        s.setCounts(0, 777);
        for (int step = 0; step < K; step++) { s.counts.set(1, step); st.execute(); }
        st.pull(s.uVec, s.yVec);
        double[] phi = new double[nSeg - 1];
        for (int k = 0; k < nSeg - 1; k++) phi[k] = Math.toDegrees(jointRoll(s, k));
        return phi;
    }

    // ------------------------------------------------------------------ scene / params
    private static FilamentStore buildChain(double restRad, boolean twisted, double brotScale) {
        FilamentStore s = new FilamentStore(nSeg);
        double L = (Constants.stdSegLength + 1) * Constants.actinMonoRadius;
        double x0 = -0.5 * (nSeg - 1) * L;
        for (int k = 0; k < nSeg; k++) {
            s.monomerCount.set(k, Constants.stdSegLength);
            s.setUVec(k, 1f, 0f, 0f);                       // all point +x (slot order = pointed→barbed contour)
            double a = twisted ? k * restRad : 0.0;         // yVec twisted about x by the cumulative rest (relaxed start)
            s.setYVec(k, 0f, (float) Math.cos(a), (float) Math.sin(a));
            s.setCoord(k, (float) (x0 + k * L), 0f, 0f);
            s.brownTransScale.set(k, (float) btrans);
            s.brownRotScale.set(k, (float) brotScale);      // roll IS kicked on ALL segments (the hard test)
            // slot 0 = pointed (end1 free), slot n−1 = barbed (end2 free); end2→next.end1 (side 0), end1→prev.end2 (side 1)
            if (k < nSeg - 1) { s.end2NbrSlot.set(k, k + 1); s.end2NbrSide.set(k, 0); }
            if (k > 0)        { s.end1NbrSlot.set(k, k - 1); s.end1NbrSide.set(k, 1); }
        }
        DragTensorSystem.run(s);
        s.setParams(dt, Constants.brownianForceMag(dt));
        s.setChainParams(dt);
        s.chainParams.set(0, (float) dt);
        DerivedGeometrySystem.derive(s.coord, s.uVec, s.yVec, s.zVec, s.end1, s.end2, s.segLength, s.counts);
        return s;
    }

    private static FloatArray mkRollParams(double restRad) {
        FloatArray rp = new FloatArray(7);
        rp.set(0, (float) dt);
        rp.set(1, (float) rollStiff);
        rp.set(2, (float) restRad);
        rp.set(3, (float) mode);
        rp.set(4, (float) kHooke);
        rp.set(5, (float) rollDamp);
        rp.set(6, (float) refDt);
        return rp;
    }

    // ------------------------------------------------------------------ device / cpu steppers
    interface Stepper { void execute(); void pull(FloatArray... a); }
    static final class GpuStepper implements Stepper {
        private final TornadoExecutionPlan plan; private final GridScheduler grid;
        private uk.ac.manchester.tornado.api.TornadoExecutionResult res;
        GpuStepper(TornadoExecutionPlan p, GridScheduler g) { plan = p; grid = g; }
        public void execute() { res = plan.withGridScheduler(grid).execute(); }
        public void pull(FloatArray... a) { if (res != null) res.transferToHost(a); }
    }
    static final class CpuStepper implements Stepper {
        private final Runnable step; CpuStepper(Runnable r) { step = r; }
        public void execute() { step.run(); }
        public void pull(FloatArray... a) { /* host holds truth */ }
    }

    private static Runnable cpuStep(FilamentStore s, FloatArray rp) {
        return () -> {
            ChainBendingForceSystem.zeroAccumulators(s.forceSum, s.torqueSum, s.counts);
            BrownianForceSystem.brownianForce(s.randForce, s.randTorque, s.bTransGam, s.bRotGam,
                    s.brownTransScale, s.brownRotScale, s.params, s.counts);
            RollSpringSystem.dampRoll(s.randTorque, rp, s.counts);
            ChainBendingForceSystem.chainForces(s.coord, s.uVec, s.segLength, s.end2NbrSlot, s.end2NbrSide,
                    s.end1NbrSlot, s.end1NbrSide, s.bTransGam, s.bRotGam, s.forceSum, s.torqueSum, s.chainParams, s.counts);
            RollSpringSystem.rollForces(s.uVec, s.yVec, s.end2NbrSlot, s.end1NbrSlot, s.bRotGam, s.torqueSum, rp, s.counts);
            RigidRodLangevinIntegrationSystem.integrate(s.coord, s.uVec, s.yVec, s.forceSum, s.torqueSum,
                    s.randForce, s.randTorque, s.bTransGam, s.bRotGam, s.params, s.counts);
            DerivedGeometrySystem.derive(s.coord, s.uVec, s.yVec, s.zVec, s.end1, s.end2, s.segLength, s.counts);
        };
    }

    private static TornadoExecutionPlan buildPlan(FilamentStore s, FloatArray rp) {
        TaskGraph tg = new TaskGraph("rollSpring")
            .transferToDevice(DataTransferMode.FIRST_EXECUTION,
                    s.coord, s.uVec, s.yVec, s.zVec, s.end1, s.end2, s.segLength,
                    s.forceSum, s.torqueSum, s.randForce, s.randTorque,
                    s.bTransGam, s.bRotGam, s.brownTransScale, s.brownRotScale,
                    s.params, s.chainParams, rp,
                    s.end1NbrSlot, s.end1NbrSide, s.end2NbrSlot, s.end2NbrSide)
            .transferToDevice(DataTransferMode.EVERY_EXECUTION, s.counts)
            .task("zero", ChainBendingForceSystem::zeroAccumulators, s.forceSum, s.torqueSum, s.counts)
            .task("brownian", BrownianForceSystem::brownianForce,
                    s.randForce, s.randTorque, s.bTransGam, s.bRotGam,
                    s.brownTransScale, s.brownRotScale, s.params, s.counts)
            .task("dampRoll", RollSpringSystem::dampRoll, s.randTorque, rp, s.counts)
            .task("chain", ChainBendingForceSystem::chainForces,
                    s.coord, s.uVec, s.segLength, s.end2NbrSlot, s.end2NbrSide,
                    s.end1NbrSlot, s.end1NbrSide, s.bTransGam, s.bRotGam,
                    s.forceSum, s.torqueSum, s.chainParams, s.counts)
            .task("rollspring", RollSpringSystem::rollForces,
                    s.uVec, s.yVec, s.end2NbrSlot, s.end1NbrSlot, s.bRotGam, s.torqueSum, rp, s.counts)
            .task("integrate", RigidRodLangevinIntegrationSystem::integrate,
                    s.coord, s.uVec, s.yVec, s.forceSum, s.torqueSum,
                    s.randForce, s.randTorque, s.bTransGam, s.bRotGam, s.params, s.counts)
            .task("derived", DerivedGeometrySystem::derive,
                    s.coord, s.uVec, s.yVec, s.zVec, s.end1, s.end2, s.segLength, s.counts)
            .transferToHost(DataTransferMode.UNDER_DEMAND, s.coord, s.uVec, s.yVec, s.zVec, s.end1, s.end2);

        int lw = Math.min(BLOCK_SIZE, s.n);
        sched = new GridScheduler();
        for (String t : new String[]{"zero", "brownian", "dampRoll", "chain", "rollspring", "integrate", "derived"}) {
            WorkerGrid w = new WorkerGrid1D(s.n); w.setLocalWork(lw, 1, 1);
            sched.addWorkerGrid("rollSpring." + t, w);
        }
        return new TornadoExecutionPlan(tg.snapshot());
    }

    // ------------------------------------------------------------------ measurement
    /** Roll of segment (k+1)'s yVec about segment k's axis, relative to k's yVec — signed radians in (−π,π]. */
    private static double jointRoll(FilamentStore s, int k) {
        double oux = s.uVecX(k), ouy = s.uVecY(k), ouz = s.uVecZ(k);
        double oyx = s.yVec.get(k), oyy = s.yVec.get(nSeg + k), oyz = s.yVec.get(2 * nSeg + k);
        double ozx = ouy * oyz - ouz * oyy, ozy = ouz * oyx - oux * oyz, ozz = oux * oyy - ouy * oyx;
        double zl = Math.sqrt(ozx * ozx + ozy * ozy + ozz * ozz);
        if (zl > 1e-30) { ozx /= zl; ozy /= zl; ozz /= zl; }
        int j = k + 1;
        double fyx = s.yVec.get(j), fyy = s.yVec.get(nSeg + j), fyz = s.yVec.get(2 * nSeg + j);
        double cosp = fyx * oyx + fyy * oyy + fyz * oyz;
        double sinp = fyx * ozx + fyy * ozy + fyz * ozz;
        return Math.atan2(sinp, cosp);   // host — atan2 fine
    }

    /** {mean per-joint twist (deg), std across joints (deg, circularly-referenced to rest)}. */
    private static double[] twistProfile(FilamentStore s) {
        int nj = nSeg - 1;
        double sum = 0; double[] phi = new double[nj];
        for (int k = 0; k < nj; k++) { phi[k] = Math.toDegrees(jointRoll(s, k)); sum += phi[k]; }
        double mean = sum / nj;
        double v = 0; for (int k = 0; k < nj; k++) { double d = phi[k] - mean; v += d * d; }
        return new double[]{ mean, Math.sqrt(v / nj) };
    }

    private static void printProfile(FilamentStore s, double restRad) {
        double[] p = twistProfile(s);
        System.out.printf("  twist profile: mean per-joint=%.2f deg  std across joints=%.2f deg  (rest %.2f deg)%n",
                p[0], p[1], Math.toDegrees(restRad));
        // sample a few joints along the contour
        int nj = nSeg - 1; StringBuilder sb = new StringBuilder("  sample joints [");
        for (int k = 0; k < nj; k += Math.max(1, nj / 8)) sb.append(String.format("%.0f ", Math.toDegrees(jointRoll(s, k))));
        System.out.println(sb.append("] deg").toString());
    }

    private static double rollDragX(FilamentStore s) { return s.bRotGam.get(s.planeX(0)); }
    private static boolean hasNaN(FilamentStore s) {
        for (int k = 0; k < 3 * nSeg; k++) if (Float.isNaN(s.uVec.get(k)) || Float.isNaN(s.yVec.get(k))) return true;
        return false;
    }
    private static double windowMean(java.util.List<Double> v, int a, int b) {
        if (b <= a) return 0; double s = 0; for (int i = a; i < b; i++) s += v.get(i); return s / (b - a);
    }
    private static double wrapPi(double a) {
        double TWO_PI = 2 * Math.PI;
        a = a - TWO_PI * Math.floor((a + Math.PI) / TWO_PI);
        if (a > Math.PI) a -= TWO_PI;
        return a;
    }
}
