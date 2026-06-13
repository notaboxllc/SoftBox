package softbox;

import uk.ac.manchester.tornado.api.GridScheduler;
import uk.ac.manchester.tornado.api.ImmutableTaskGraph;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.TornadoExecutionResult;
import uk.ac.manchester.tornado.api.WorkerGrid;
import uk.ac.manchester.tornado.api.WorkerGrid1D;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * Increment-1 diffusion harness + FDT (Einstein) validation.
 *
 * Runs N independent free rods device-resident for M steps (no per-step host pose
 * pull — pose is FIRST_EXECUTION on device, pulled UNDER_DEMAND only at output
 * cadence) and measures diffusion two ways, then checks each measured D against the
 * value implied by the SAME drag tensors the kernel used (D = kT/gamma):
 *
 *  Config T (translational anisotropy): rotational Brownian OFF, orientation frozen
 *    along lab-x. Body axes == lab axes for all steps, so lab-frame MSD per axis
 *    isolates D_par (along uVec) and D_perp. MSD_axis(t) = 2 D_axis t.
 *
 *  Config R (rotational): translational Brownian OFF. Orientational autocorrelation
 *    C(t) = <uVec(t).uVec(0)> = <uVec_x(t)> (since uVec(0)=(1,0,0)) decays as
 *    exp(-2 D_rot t), giving the transverse rotational diffusion D_rot,perp.
 *
 * Both B-coefficients are 1.0 here so the bare FDT relation D = kT/gamma holds (v1's
 * production BTransCoeff=1/BRotCoeff=0.5 are biological tuning knobs, out of scope for
 * the amplitude-coupling check). Pass tolerance: 5% (covers float32 + ~1/sqrt(N)
 * ensemble noise + O(D dt) Euler bias; tight enough to catch a wrong amplitude factor,
 * which would be tens of percent off).
 */
public final class DiffusionHarness {

    // run sizing (tunable; defaults give ~2.2% ensemble statistical error)
    static int N = 8192;
    static int M_TRANS = 20000;   static int CAD_TRANS = 200;
    static int M_ROT   = 4000;    static int CAD_ROT   = 20;
    static int MONOMER_CT = 64;
    static final double TOL = 0.05;
    // explicit block size — the TornadoVM default overflows the register file for these
    // register-heavy kernels (CUDA_ERROR_LAUNCH_OUT_OF_RESOURCES / 701). Matches v1's
    // MOVE_KERNEL_BLOCK_SIZE (GPUMoveThing.java:985).
    static final int BLOCK_SIZE = 64;
    static GridScheduler sched;
    // chain-coefficient overrides for sign/behavior diagnosis (NaN = use v1 default)
    static double fracROverride = Double.NaN, fmtOverride = Double.NaN;
    static boolean deflect = false;

    public static void main(String[] args) {
        // parse flags: "-3js <dir>" (free-rod viz), "-chain <dir>" (free Brownian chain, inc 2a);
        // remaining args are positional N [M].
        String jsDir = null, chainDir = null;
        double dt = Constants.deltaT;   // overridable with "-dt <seconds>" (chain run)
        java.util.List<String> pos = new java.util.ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-3js")) { jsDir = args[++i]; }
            else if (args[i].equals("-chain")) { chainDir = args[++i]; }
            else if (args[i].equals("-dt")) { dt = Double.parseDouble(args[++i]); }
            else if (args[i].equals("-fracR")) { fracROverride = Double.parseDouble(args[++i]); }
            else if (args[i].equals("-fmt")) { fmtOverride = Double.parseDouble(args[++i]); }
            else if (args[i].equals("-deflect")) { deflect = true; }
            else pos.add(args[i]);
        }
        if (deflect) {
            int nSeg = pos.size() >= 1 ? Integer.parseInt(pos.get(0)) : 11;   // v1 benchmarkNSegs
            int defM = pos.size() >= 2 ? Integer.parseInt(pos.get(1)) : 60000;
            runDeflection(nSeg, defM, dt);
            return;
        }
        if (chainDir != null) {
            int nSeg = pos.size() >= 1 ? Integer.parseInt(pos.get(0)) : 16;
            int chainM = pos.size() >= 2 ? Integer.parseInt(pos.get(1)) : 40000;
            runChain(chainDir, nSeg, chainM, dt);
            return;
        }
        if (jsDir != null) {
            int vizN = pos.size() >= 1 ? Integer.parseInt(pos.get(0)) : 200;
            int vizM = pos.size() >= 2 ? Integer.parseInt(pos.get(1)) : 20000;
            runViz(jsDir, vizN, vizM);
            return;
        }
        // --- FDT path: byte-for-byte unchanged from inc 1 when -3js is absent ---
        if (pos.size() >= 1) N = Integer.parseInt(pos.get(0));
        if (pos.size() >= 2) { M_TRANS = Integer.parseInt(pos.get(1)); M_ROT = M_TRANS / 5; }

        System.out.println("=== Soft Box increment 1 — filament rigid-rod Langevin FDT validation ===");
        System.out.printf("N=%d rods, monomerCt=%d, dt=%.1e s, aeta=%.3g Pa-s, kT=%.4e J%n",
                N, MONOMER_CT, Constants.deltaT, Constants.aeta, Constants.kT);

        boolean okT = runTranslational();
        boolean okR = runRotational();

        System.out.println();
        System.out.println("=== FDT VALIDATION " + (okT && okR ? "PASS" : "FAIL") + " (tolerance "
                + (int) (TOL * 100) + "%) ===");
        if (!(okT && okR)) {
            System.out.println("BAIL-OUT: measured D outside FDT tolerance — integration or Brownian-"
                    + "amplitude coupling is wrong. Commit nothing; report.");
            System.exit(1);
        }
    }

    /** Build the per-step device graph: brownian -> integrate -> derived, pose resident. */
    private static TornadoExecutionPlan buildPlan(FilamentStore s) {
        TaskGraph tg = new TaskGraph("rodLangevin")
            // canonical pose + constant drag/length/scales/params: upload once, stay resident
            .transferToDevice(DataTransferMode.FIRST_EXECUTION,
                    s.coord, s.uVec, s.yVec, s.zVec, s.end1, s.end2, s.segLength,
                    s.forceSum, s.torqueSum, s.randForce, s.randTorque,
                    s.bTransGam, s.bRotGam, s.brownTransScale, s.brownRotScale, s.params)
            // step counter changes every step
            .transferToDevice(DataTransferMode.EVERY_EXECUTION, s.counts)
            .task("brownian", BrownianForceSystem::brownianForce,
                    s.randForce, s.randTorque, s.bTransGam, s.bRotGam,
                    s.brownTransScale, s.brownRotScale, s.params, s.counts)
            .task("integrate", RigidRodLangevinIntegrationSystem::integrate,
                    s.coord, s.uVec, s.yVec, s.forceSum, s.torqueSum,
                    s.randForce, s.randTorque, s.bTransGam, s.bRotGam, s.params, s.counts)
            .task("derived", DerivedGeometrySystem::derive,
                    s.coord, s.uVec, s.yVec, s.zVec, s.end1, s.end2, s.segLength, s.counts)
            // pose pulled only on explicit demand (output cadence)
            .transferToHost(DataTransferMode.UNDER_DEMAND,
                    s.coord, s.uVec, s.yVec, s.zVec, s.end1, s.end2);

        // explicit per-task worker grid (block size 64) — see BLOCK_SIZE note
        WorkerGrid wB = new WorkerGrid1D(s.n); wB.setLocalWork(BLOCK_SIZE, 1, 1);
        WorkerGrid wI = new WorkerGrid1D(s.n); wI.setLocalWork(BLOCK_SIZE, 1, 1);
        WorkerGrid wD = new WorkerGrid1D(s.n); wD.setLocalWork(BLOCK_SIZE, 1, 1);
        sched = new GridScheduler("rodLangevin.brownian", wB);
        sched.addWorkerGrid("rodLangevin.integrate", wI);
        sched.addWorkerGrid("rodLangevin.derived", wD);

        ImmutableTaskGraph itg = tg.snapshot();
        return new TornadoExecutionPlan(itg);
    }

    // ----------------------------------------------------------------- deflection (v1 compare)
    // tiny side-channel arrays for the two pins (created per run)
    static IntArray   pinSeg, pinEnd;
    static FloatArray pinAnchor;

    /** Per-step deflection graph: seed(extForce load) -> chain -> integrate -> pin -> derived.
     *  No Brownian (deflection chain is Brownian-off in v1). Matches v1's loop order. */
    private static TornadoExecutionPlan buildDeflectionPlan(FilamentStore s) {
        TaskGraph tg = new TaskGraph("deflect")
            .transferToDevice(DataTransferMode.FIRST_EXECUTION,
                    s.coord, s.uVec, s.yVec, s.zVec, s.end1, s.end2, s.segLength,
                    s.extForce, s.forceSum, s.torqueSum, s.randForce, s.randTorque,
                    s.bTransGam, s.bRotGam, s.params, s.chainParams,
                    s.end1NbrSlot, s.end1NbrSide, s.end2NbrSlot, s.end2NbrSide,
                    pinSeg, pinEnd, pinAnchor)
            .transferToDevice(DataTransferMode.EVERY_EXECUTION, s.counts)
            .task("seed", DeflectionSupport::seedAccumulators, s.forceSum, s.torqueSum, s.extForce, s.counts)
            .task("chain", ChainBendingForceSystem::chainForces,
                    s.coord, s.uVec, s.segLength, s.end2NbrSlot, s.end2NbrSide,
                    s.end1NbrSlot, s.end1NbrSide, s.bTransGam, s.bRotGam,
                    s.forceSum, s.torqueSum, s.chainParams, s.counts)
            .task("integrate", RigidRodLangevinIntegrationSystem::integrate,
                    s.coord, s.uVec, s.yVec, s.forceSum, s.torqueSum,
                    s.randForce, s.randTorque, s.bTransGam, s.bRotGam, s.params, s.counts)
            .task("pin", DeflectionSupport::pinEndpoints,
                    s.coord, s.uVec, s.segLength, pinSeg, pinEnd, pinAnchor, s.counts)
            .task("derived", DerivedGeometrySystem::derive,
                    s.coord, s.uVec, s.yVec, s.zVec, s.end1, s.end2, s.segLength, s.counts)
            .transferToHost(DataTransferMode.UNDER_DEMAND, s.coord, s.uVec, s.yVec, s.zVec, s.end1, s.end2);

        int lw = Math.min(BLOCK_SIZE, s.n);
        sched = new GridScheduler();
        for (String t : new String[]{"seed", "chain", "integrate", "derived"}) {
            WorkerGrid w = new WorkerGrid1D(s.n); w.setLocalWork(lw, 1, 1);
            sched.addWorkerGrid("deflect." + t, w);
        }
        WorkerGrid wp = new WorkerGrid1D(pinSeg.getSize()); wp.setLocalWork(pinSeg.getSize(), 1, 1);
        sched.addWorkerGrid("deflect.pin", wp);
        return new TornadoExecutionPlan(tg.snapshot());
    }

    /**
     * Deflection benchmark replicating v1's -bmDiag setup EXACTLY (BoxOfActin / FilSegment
     * makeBenchmarkChain + applyBenchmarkPins): nSeg segments of stdSegLength monomers,
     * straight along x centered, end2->next.end1 wired, ends pinned (free rotation),
     * Brownian OFF, transverse load F=48*EI*frac/span^2 on the midpoint segment's center.
     * Measures obs = perpendicular distance of the midpoint center from the anchor line;
     * ratio = obs / (frac*span). v1 gives ratio≈0.998 at fracR=0.1; this lets us compare
     * v2's deflection directly to v1's (a strong identical-physics check).
     */
    private static void runDeflection(int nSeg, int defM, double dt) {
        final int monomerCt = Constants.stdSegLength;        // 32, matching v1 benchmark
        final double frac = 0.01;                            // benchmarkForceFrac
        double segLen = (monomerCt + 1) * Constants.actinMonoRadius;   // microns (=0.0891)
        double totalLen = nSeg * segLen;                     // span (microns)
        double spanM = totalLen * 1.0e-6;
        double forceN = 48.0 * Constants.EI * frac / (spanM * spanM);
        double analyticDefl = frac * totalLen;               // microns (= frac*span)
        int mid = nSeg / 2;

        System.out.println("=== Soft Box deflection benchmark (replicating v1 -bmDiag) ===");
        System.out.printf("nSeg=%d, monomerCt=%d, segLen=%.4f um, span=%.4f um, EI=%.4e, F=%.4e N, analyticDefl=%.6f um%n",
                nSeg, monomerCt, segLen, totalLen, Constants.EI, forceN, analyticDefl);

        FilamentStore s = new FilamentStore(nSeg);
        double x0 = -0.5 * totalLen + 0.5 * segLen;          // center of seg 0
        for (int k = 0; k < nSeg; k++) {
            s.monomerCount.set(k, monomerCt);
            s.setUVec(k, 1f, 0f, 0f);
            s.setYVec(k, 0f, 1f, 0f);
            s.setCoord(k, (float) (x0 + k * segLen), 0f, 0f);
            s.brownTransScale.set(k, 0f);   // Brownian OFF (deflection chain)
            s.brownRotScale.set(k, 0f);
            if (k < nSeg - 1) { s.end2NbrSlot.set(k, k + 1); s.end2NbrSide.set(k, 0); }
            if (k > 0)        { s.end1NbrSlot.set(k, k - 1); s.end1NbrSide.set(k, 1); }
        }
        DragTensorSystem.run(s);
        s.setParams(dt, Math.sqrt(2.0 * Constants.kT / dt));
        s.setChainParams();
        s.chainParams.set(0, (float) dt);
        if (!Double.isNaN(fracROverride)) s.chainParams.set(2, (float) fracROverride);
        if (!Double.isNaN(fmtOverride))   s.chainParams.set(3, (float) fmtOverride);
        System.out.printf("  coeffs: fracMove=%.4g fracR=%.4g fracMoveTorq=%.4g%n",
                s.chainParams.get(1), s.chainParams.get(2), s.chainParams.get(3));

        // external load on the midpoint segment center: (0, -forceN, 0)
        s.extForce.set(s.planeY(mid), (float) (-forceN));

        // pins: seg0.end1 -> anchor1 (=(-span/2,0,0)); seg(last).end2 -> anchor2 (=(+span/2,0,0))
        pinSeg = IntArray.fromElements(0, nSeg - 1);
        pinEnd = IntArray.fromElements(1, 2);
        pinAnchor = FloatArray.fromElements(
                (float) (-0.5 * totalLen), 0f, 0f,
                (float) ( 0.5 * totalLen), 0f, 0f);

        TornadoExecutionPlan plan = buildDeflectionPlan(s);
        s.setCounts(0, 1);

        TornadoExecutionResult res = null;
        int half = defM / 2, stride = 2;     // average/jitter over the converged 2nd half
        double sum = 0, sumsq = 0, mn = 1e30, mx = -1e30; int cnt = 0;
        for (int step = 0; step < defM; step++) {
            s.counts.set(1, step);
            res = plan.withGridScheduler(sched).execute();
            if (step + 1 > half && (step + 1) % stride == 0) {
                res.transferToHost(s.coord);
                double obs = Math.sqrt(s.coordY(mid) * s.coordY(mid) + s.coordZ(mid) * s.coordZ(mid));
                sum += obs; sumsq += obs * obs; cnt++;
                if (obs < mn) mn = obs; if (obs > mx) mx = obs;
            }
        }
        double mean = sum / cnt, var = sumsq / cnt - mean * mean;
        double std = var > 0 ? Math.sqrt(var) : 0;
        System.out.printf("=== v2 deflection (avg over %d samples, 2nd half): obs=%.6f +/- %.6f um  (min=%.6f max=%.6f, jitter=%.2f%% pk-pk)%n",
                cnt, mean, std, mn, mx, 100 * (mx - mn) / mean);
        System.out.printf("    mean ratio=%.5f +/- %.5f   (analyticDefl=%.6f um) ===%n",
                mean / analyticDefl, std / analyticDefl, analyticDefl);
    }

    // ----------------------------------------------------------------- chain (inc 2a)
    /** Per-step chain graph: zero accumulators -> brownian + chain (fill) -> integrate -> derived. */
    private static TornadoExecutionPlan buildChainPlan(FilamentStore s) {
        TaskGraph tg = new TaskGraph("freeChain")
            .transferToDevice(DataTransferMode.FIRST_EXECUTION,
                    s.coord, s.uVec, s.yVec, s.zVec, s.end1, s.end2, s.segLength,
                    s.forceSum, s.torqueSum, s.randForce, s.randTorque,
                    s.bTransGam, s.bRotGam, s.brownTransScale, s.brownRotScale,
                    s.params, s.chainParams,
                    s.end1NbrSlot, s.end1NbrSide, s.end2NbrSlot, s.end2NbrSide)
            .transferToDevice(DataTransferMode.EVERY_EXECUTION, s.counts)
            .task("zero", ChainBendingForceSystem::zeroAccumulators, s.forceSum, s.torqueSum, s.counts)
            .task("brownian", BrownianForceSystem::brownianForce,
                    s.randForce, s.randTorque, s.bTransGam, s.bRotGam,
                    s.brownTransScale, s.brownRotScale, s.params, s.counts)
            .task("chain", ChainBendingForceSystem::chainForces,
                    s.coord, s.uVec, s.segLength, s.end2NbrSlot, s.end2NbrSide,
                    s.end1NbrSlot, s.end1NbrSide, s.bTransGam, s.bRotGam,
                    s.forceSum, s.torqueSum, s.chainParams, s.counts)
            .task("integrate", RigidRodLangevinIntegrationSystem::integrate,
                    s.coord, s.uVec, s.yVec, s.forceSum, s.torqueSum,
                    s.randForce, s.randTorque, s.bTransGam, s.bRotGam, s.params, s.counts)
            .task("derived", DerivedGeometrySystem::derive,
                    s.coord, s.uVec, s.yVec, s.zVec, s.end1, s.end2, s.segLength, s.counts)
            .transferToHost(DataTransferMode.UNDER_DEMAND, s.coord, s.uVec, s.yVec, s.zVec, s.end1, s.end2);

        int lw = Math.min(BLOCK_SIZE, s.n);
        sched = new GridScheduler();
        for (String t : new String[]{"zero", "brownian", "chain", "integrate", "derived"}) {
            WorkerGrid w = new WorkerGrid1D(s.n); w.setLocalWork(lw, 1, 1);
            sched.addWorkerGrid("freeChain." + t, w);
        }
        return new TornadoExecutionPlan(tg.snapshot());
    }

    /**
     * Free Brownian chain (inc 2a): single straight chain wired head-to-tail, free
     * boundary conditions, Brownian on, chain forces on. No pins, no applied force, no
     * deflection assay (deferred to 2b). Validates connectivity via the joint-continuity
     * gap + frame output for the visual gate.
     */
    private static void runChain(String dir, int nSeg, int chainM, double dt) {
        int cad = Math.max(1, chainM / 200);
        double L = (MONOMER_CT + 1) * Constants.actinMonoRadius;   // segLength (microns)
        System.out.println("=== Soft Box increment 2a — free Brownian filament chain ===");
        System.out.printf("nSeg=%d, monomerCt=%d, segLen=%.4f um, M=%d, cadence=%d, dt=%.1e s%n",
                nSeg, MONOMER_CT, L, chainM, cad, dt);

        FilamentStore s = new FilamentStore(nSeg);
        double spacing = L;
        double x0 = -0.5 * (nSeg - 1) * spacing;   // center the straight chain at the origin
        for (int k = 0; k < nSeg; k++) {
            s.monomerCount.set(k, MONOMER_CT);
            s.setUVec(k, 1f, 0f, 0f);   // all segments point +x (head-to-tail, normal orientation)
            s.setYVec(k, 0f, 1f, 0f);
            s.setCoord(k, (float) (x0 + k * spacing), 0f, 0f);
            s.brownTransScale.set(k, 1.0f);
            // Rotational Brownian ONLY on chain-end segments (>=1 free end), matching v1:
            //   rScale = (filAtEnd1 && filAtEnd2) ? 0 : rs  ("only apply brownian torques to end
            //   filaments.. best matches expected angular correlations"). Kicking interior segments
            //   rotationally makes adjacent joints zigzag -> a jagged, non-smooth bend.
            boolean interior = (k > 0 && k < nSeg - 1);
            s.brownRotScale.set(k, interior ? 0f : 1f);
            // wire topology (no storage reshape): my end2 -> next.end1 (side 0); my end1 -> prev.end2 (side 1)
            if (k < nSeg - 1) { s.end2NbrSlot.set(k, k + 1); s.end2NbrSide.set(k, 0); }
            if (k > 0)        { s.end1NbrSlot.set(k, k - 1); s.end1NbrSide.set(k, 1); }
        }
        DragTensorSystem.run(s);    // segLength + drag (topology already wired -> filAtEnd correct)
        s.setParams(dt, Math.sqrt(2.0 * Constants.kT / dt));   // brownianForceMag = sqrt(2kT/dt)
        s.setChainParams();
        s.chainParams.set(0, (float) dt);   // override chain dt with the requested timestep
        if (!Double.isNaN(fracROverride)) s.chainParams.set(2, (float) fracROverride);
        if (!Double.isNaN(fmtOverride))   s.chainParams.set(3, (float) fmtOverride);
        System.out.printf("  chain coeffs: fracMove=%.4g fracR=%.4g fracMoveTorq=%.4g%n",
                s.chainParams.get(1), s.chainParams.get(2), s.chainParams.get(3));

        // --- side-decode code check (must match v1 FilSegment.setEnd*Links) ---
        boolean sideOK = true;
        for (int k = 0; k < nSeg; k++) {
            if (k < nSeg - 1 && !(s.end2NbrSlot.get(k) == k + 1 && s.end2NbrSide.get(k) == 0)) sideOK = false;
            if (k > 0        && !(s.end1NbrSlot.get(k) == k - 1 && s.end1NbrSide.get(k) == 1)) sideOK = false;
        }
        if (nSeg >= 1 && (s.end1NbrSlot.get(0) != -1 || s.end2NbrSlot.get(nSeg - 1) != -1)) sideOK = false;
        System.out.println("  side-decode code check (end2->next.end1=side0, end1->prev.end2=side1, ends=-1): "
                + (sideOK ? "OK" : "*** MISMATCH ***"));

        TornadoExecutionPlan plan = buildChainPlan(s);
        s.setCounts(0, 909090);
        FrameWriter fw = new FrameWriter(dir, 4.0, 4.0, 4.0);

        double maxGap = 0;     // max over interior joints, over the whole run
        boolean nan = false;
        java.util.List<Double> meanGaps = new java.util.ArrayList<>();   // per-frame mean joint gap (stationarity)
        double bendSumSq = 0; int bendCnt = 0;   // bending stiffness: RMS adjacent-segment angle (equilibrated)
        fw.writeFrame(s, 0.0);

        TornadoExecutionResult res = null;
        int frame = 0;
        for (int step = 0; step < chainM; step++) {
            s.counts.set(1, step);
            res = plan.withGridScheduler(sched).execute();
            if ((step + 1) % cad == 0) {
                res.transferToHost(s.coord, s.uVec);
                if (hasNaN(s, nSeg)) { nan = true; break; }
                maxGap = Math.max(maxGap, jointGap(s, nSeg, L, true));
                meanGaps.add(jointGap(s, nSeg, L, false));
                frame++;
                if (frame > (chainM / cad) / 4) {   // skip straight->equilibrium transient
                    for (int k = 0; k < nSeg - 1; k++) { double a = bendAngleDeg(s, k); bendSumSq += a * a; bendCnt++; }
                }
                fw.writeFrame(s, (step + 1) * dt);
            }
        }
        double bendRms = bendCnt > 0 ? Math.sqrt(bendSumSq / bendCnt) : 0;

        // Stationarity: late-window mean gap must not exceed the post-transient middle window
        // (a slow detachment grows; a healthy thermal joint is stationary). Skip the first quarter
        // (the straight->equilibrium transient). Bounded: a wrong side-decode pulls toward a tip
        // ~segLength away, so maxGap would approach segLength — gate well below that (0.5*segLen).
        int ng = meanGaps.size();
        double midMean  = windowMean(meanGaps, ng / 4, ng / 2);
        double lateMean = windowMean(meanGaps, 3 * ng / 4, ng);
        double e2e = endToEnd(s, nSeg, L), contour = nSeg * L;
        boolean stationary = ng < 8 || lateMean <= 1.5 * midMean + 1e-9;
        boolean boundedGap = !nan && maxGap < 0.5 * L;

        System.out.printf("  joint-continuity gap: max=%.5f um  mean(mid->late)=%.5f->%.5f um  (eq breathing ~%.4f; 0.5*segLen=%.4f)%n",
                maxGap, midMean, lateMean, Constants.actinMonoRadius, 0.5 * L);
        System.out.printf("  end-to-end=%.3f um / contour=%.3f um (ratio %.3f: higher=stiffer)%n",
                e2e, contour, e2e / contour);
        System.out.printf("  STIFFNESS: RMS adjacent-segment bend angle = %.2f deg  (higher=softer; WLC Lp=15um => %.2f)%n",
                bendRms, Math.toDegrees(Math.sqrt(2 * L / 15.0)));
        System.out.println("  NaN: " + nan + " ;  segment count conserved: " + s.n + " == " + nSeg
                + " ;  gap bounded<0.5segLen: " + boundedGap + " ;  stationary: " + stationary);
        System.out.printf("wrote %d frames to %s%n", fw.framesWritten(), fw.dir());
        System.out.println("view: cd ~/Code/SoftBox && python3 sim_server.py 8000  ->  sim_viewer_boa.html (Recent, newest)");

        boolean ok = sideOK && boundedGap && stationary;
        System.out.println("=== CHAIN CONNECTIVITY " + (ok ? "PASS" : "FAIL") + " (visual gate: watch it in the viewer) ===");
        if (!ok) {
            System.out.println("BAIL-OUT: connectivity check failed — suspect the end*NbrSide decode (A1 trap). Commit nothing.");
            System.exit(1);
        }
    }

    private static double windowMean(java.util.List<Double> v, int from, int to) {
        if (to <= from) return 0;
        double s = 0; for (int i = from; i < to; i++) s += v.get(i);
        return s / (to - from);
    }

    private static double endToEnd(FilamentStore s, int nSeg, double L) {
        double half = L * 0.5;
        double ax = s.coordX(0) - half * s.uVecX(0), ay = s.coordY(0) - half * s.uVecY(0), az = s.coordZ(0) - half * s.uVecZ(0);
        int j = nSeg - 1;
        double bx = s.coordX(j) + half * s.uVecX(j), by = s.coordY(j) + half * s.uVecY(j), bz = s.coordZ(j) + half * s.uVecZ(j);
        return Math.sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay) + (bz - az) * (bz - az));
    }

    /** Over interior joints, |k.end2 - (k+1).end1| (the endpoints the topology joins).
     *  max=true -> max over joints; max=false -> mean over joints. */
    private static double jointGap(FilamentStore s, int nSeg, double L, boolean max) {
        double half = L * 0.5, mx = 0, sum = 0;
        for (int k = 0; k < nSeg - 1; k++) {
            double e2x = s.coordX(k) + half * s.uVecX(k);
            double e2y = s.coordY(k) + half * s.uVecY(k);
            double e2z = s.coordZ(k) + half * s.uVecZ(k);
            double e1x = s.coordX(k + 1) - half * s.uVecX(k + 1);
            double e1y = s.coordY(k + 1) - half * s.uVecY(k + 1);
            double e1z = s.coordZ(k + 1) - half * s.uVecZ(k + 1);
            double d = Math.sqrt((e2x - e1x) * (e2x - e1x) + (e2y - e1y) * (e2y - e1y) + (e2z - e1z) * (e2z - e1z));
            if (d > mx) mx = d;
            sum += d;
        }
        return max ? mx : (nSeg > 1 ? sum / (nSeg - 1) : 0);
    }

    /** Angle (deg) between segment k's and k+1's long axes (uVec). Bigger => softer filament. */
    private static double bendAngleDeg(FilamentStore s, int k) {
        double d = s.uVecX(k) * s.uVecX(k + 1) + s.uVecY(k) * s.uVecY(k + 1) + s.uVecZ(k) * s.uVecZ(k + 1);
        if (d > 1) d = 1; if (d < -1) d = -1;
        return Math.toDegrees(Math.acos(d));
    }

    private static boolean hasNaN(FilamentStore s, int nSeg) {
        for (int k = 0; k < nSeg; k++) {
            if (!Float.isFinite(s.coordX(k)) || !Float.isFinite(s.coordY(k)) || !Float.isFinite(s.coordZ(k))
                    || !Float.isFinite(s.uVecX(k)) || !Float.isFinite(s.uVecY(k)) || !Float.isFinite(s.uVecZ(k)))
                return true;
        }
        return false;
    }

    // ----------------------------------------------------------------- viz (frame dump)
    /**
     * Viz run (increment 1.5): low rod count in a compact cluster, both Brownian
     * components ON (bare FDT amplitude), frames written at output cadence for the v1
     * viewer. Separate from the FDT validation; reuses the same device graph + the
     * existing output-cadence UNDER_DEMAND pose pull (no new sync, no per-step pull).
     */
    private static void runViz(String dir, int vizN, int vizM) {
        int cad = Math.max(1, vizM / 200);   // aim for ~200 frames
        System.out.println("=== Soft Box increment 1.5 — Three.js frame dump (viz run) ===");
        System.out.printf("N=%d rods, M=%d steps, cadence=%d, dt=%.1e s%n",
                vizN, vizM, cad, Constants.deltaT);

        FilamentStore s = new FilamentStore(vizN);
        java.util.Random rng = new java.util.Random(20260613L);
        final double clusterHalf = 0.3;   // microns — compact starting cluster
        for (int i = 0; i < vizN; i++) {
            s.monomerCount.set(i, MONOMER_CT);
            // random unit uVec (uniform on sphere)
            double z = 2 * rng.nextDouble() - 1, phi = 2 * Math.PI * rng.nextDouble();
            double rr = Math.sqrt(Math.max(0.0, 1 - z * z));
            double ux = rr * Math.cos(phi), uy = rr * Math.sin(phi), uz = z;
            // yVec: any unit vector perpendicular to uVec
            double ax, ay, az;
            if (Math.abs(uz) < 0.9) { ax = 0; ay = 0; az = 1; } else { ax = 1; ay = 0; az = 0; }
            double dot = ax * ux + ay * uy + az * uz;
            double yx = ax - dot * ux, yy = ay - dot * uy, yz = az - dot * uz;
            double yn = Math.sqrt(yx * yx + yy * yy + yz * yz);
            yx /= yn; yy /= yn; yz /= yn;
            s.setUVec(i, (float) ux, (float) uy, (float) uz);
            s.setYVec(i, (float) yx, (float) yy, (float) yz);
            s.setCoord(i,
                    (float) ((rng.nextDouble() - 0.5) * 2 * clusterHalf),
                    (float) ((rng.nextDouble() - 0.5) * 2 * clusterHalf),
                    (float) ((rng.nextDouble() - 0.5) * 2 * clusterHalf));
            s.brownTransScale.set(i, 1.0f);
            s.brownRotScale.set(i, 1.0f);
        }
        DragTensorSystem.run(s);
        s.setParams(Constants.deltaT, Constants.brownianForceMag());

        // View box: FIXED, sized to ~5 sigma of the expected diffusive spread over the run.
        // Framing only (no walls; not physics). The viewer builds the box from frame 0.
        double Dpar = DragTensorSystem.fdtPrediction(s, 0)[0] * 1e12;   // um^2/s
        double rms = Math.sqrt(2 * Dpar * vizM * Constants.deltaT);
        double dim = 2 * (clusterHalf + 5 * rms);
        System.out.printf("  view box %.3g um cube (clusterHalf %.2f + 5*sqrt(2 Dpar T) %.2f); framing only%n",
                dim, clusterHalf, 5 * rms);

        TornadoExecutionPlan plan = buildPlan(s);
        s.setCounts(0, 4242);
        FrameWriter fw = new FrameWriter(dir, dim, dim, dim);

        fw.writeFrame(s, 0.0);   // frame 0 = initial pose (host already holds coord/uVec)
        TornadoExecutionResult res = null;
        for (int step = 0; step < vizM; step++) {
            s.counts.set(1, step);
            res = plan.withGridScheduler(sched).execute();
            if ((step + 1) % cad == 0) {
                res.transferToHost(s.coord, s.uVec);   // existing output-cadence pull
                fw.writeFrame(s, (step + 1) * Constants.deltaT);
            }
        }
        System.out.printf("wrote %d frames to %s%n", fw.framesWritten(), fw.dir());
        System.out.println("view: cd ~/Code/SoftBox && python3 sim_server.py 8000");
        System.out.println("then open http://localhost:8000/sim_viewer_boa.html  (Recent picker -> newest)");
    }

    private static FilamentStore freshStore(double transScale, double rotScale) {
        FilamentStore s = new FilamentStore(N);
        for (int i = 0; i < N; i++) {
            s.monomerCount.set(i, MONOMER_CT);
            s.setCoord(i, 0f, 0f, 0f);
            s.setUVec(i, 1f, 0f, 0f);   // long axis along lab-x
            s.setYVec(i, 0f, 1f, 0f);
            s.brownTransScale.set(i, (float) transScale);
            s.brownRotScale.set(i, (float) rotScale);
        }
        DragTensorSystem.run(s);                 // fill gamma/diff + segLength (host, once)
        s.setParams(Constants.deltaT, Constants.brownianForceMag());
        return s;
    }

    // ----------------------------------------------------------------- translational
    private static boolean runTranslational() {
        System.out.println("\n--- Config T: translational (rot Brownian OFF, orientation frozen +x) ---");
        FilamentStore s = freshStore(Constants.BTransCoeff /*=1*/, 0.0);
        // force the FDT-bare amplitude: trans scale = 1 exactly
        for (int i = 0; i < N; i++) s.brownTransScale.set(i, 1.0f);

        TornadoExecutionPlan plan = buildPlan(s);
        s.setCounts(0, 12345);

        int samples = M_TRANS / CAD_TRANS + 1;
        double[] t = new double[samples];
        double[] msdX = new double[samples], msdY = new double[samples], msdZ = new double[samples];
        int rec = 0;
        TornadoExecutionResult res = null;

        for (int step = 0; step <= M_TRANS; step++) {
            if (step % CAD_TRANS == 0) {
                if (step > 0) res.transferToHost(s.coord);  // UNDER_DEMAND pose pull (cadence only)
                double sx = 0, sy = 0, sz = 0;
                for (int i = 0; i < N; i++) {
                    double x = s.coordX(i), y = s.coordY(i), z = s.coordZ(i);
                    sx += x * x; sy += y * y; sz += z * z;
                }
                t[rec] = step * Constants.deltaT;
                msdX[rec] = sx / N; msdY[rec] = sy / N; msdZ[rec] = sz / N;
                rec++;
            }
            if (step == M_TRANS) break;
            s.counts.set(1, step);
            res = plan.withGridScheduler(sched).execute();
        }

        // MSD_axis = 2 D_axis t  -> slope through origin / 2
        double Dx = slopeThroughOrigin(t, msdX) / 2.0;   // D_parallel  (microns^2/s)
        double Dy = slopeThroughOrigin(t, msdY) / 2.0;   // D_perp
        double Dz = slopeThroughOrigin(t, msdZ) / 2.0;   // D_perp

        double[] fdt = DragTensorSystem.fdtPrediction(s, 0);  // SI m^2/s
        double predPar  = fdt[0] * 1e12;   // -> microns^2/s
        double predPerp = fdt[1] * 1e12;

        System.out.printf("  gamma_par=%.4e  gamma_perp=%.4e  (N s/m)%n",
                s.bTransGam.get(s.planeX(0)), s.bTransGam.get(s.planeY(0)));
        boolean ok = true;
        ok &= report("D_trans_parallel (x)", Dx, predPar, "um^2/s");
        ok &= report("D_trans_perp (y)",     Dy, predPerp, "um^2/s");
        ok &= report("D_trans_perp (z)",     Dz, predPerp, "um^2/s");
        return ok;
    }

    // ----------------------------------------------------------------- rotational
    private static boolean runRotational() {
        System.out.println("\n--- Config R: rotational (trans Brownian OFF) ---");
        FilamentStore s = freshStore(0.0, 1.0);  // rot scale = 1 exactly (bare FDT)

        TornadoExecutionPlan plan = buildPlan(s);
        s.setCounts(0, 67890);

        int samples = M_ROT / CAD_ROT + 1;
        double[] t = new double[samples];
        double[] c = new double[samples];   // C(t) = <uVec_x>
        int rec = 0;
        TornadoExecutionResult res = null;

        for (int step = 0; step <= M_ROT; step++) {
            if (step % CAD_ROT == 0) {
                if (step > 0) res.transferToHost(s.uVec);
                double su = 0;
                for (int i = 0; i < N; i++) su += s.uVecX(i);
                t[rec] = step * Constants.deltaT;
                c[rec] = su / N;
                rec++;
            }
            if (step == M_ROT) break;
            s.counts.set(1, step);
            res = plan.withGridScheduler(sched).execute();
        }

        // C(t) = exp(-2 D_rot t) -> fit ln C vs t over the resolved window
        int cnt = 0;
        for (int k = 0; k < rec; k++) if (c[k] > 0.2 && c[k] < 0.95) cnt++;
        double[] tw = new double[cnt], lw = new double[cnt];
        int j = 0;
        for (int k = 0; k < rec; k++) {
            if (c[k] > 0.2 && c[k] < 0.95) { tw[j] = t[k]; lw[j] = Math.log(c[k]); j++; }
        }
        double slope = olsSlope(tw, lw);     // = -2 D_rot
        double Drot = -slope / 2.0;          // rad^2/s

        double[] fdt = DragTensorSystem.fdtPrediction(s, 0);
        double predRot = fdt[3];             // kT/bRotGam.y, rad^2/s

        System.out.printf("  gamma_rot_perp=%.4e (N m s/rad);  fit window: %d/%d samples, C in (0.2,0.95)%n",
                s.bRotGam.get(s.planeY(0)), cnt, rec);
        return report("D_rot_perp", Drot, predRot, "rad^2/s");
    }

    // ----------------------------------------------------------------- helpers
    private static double slopeThroughOrigin(double[] x, double[] y) {
        double sxy = 0, sxx = 0;
        for (int i = 0; i < x.length; i++) { sxy += x[i] * y[i]; sxx += x[i] * x[i]; }
        return sxy / sxx;
    }

    private static double olsSlope(double[] x, double[] y) {
        int n = x.length;
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        for (int i = 0; i < n; i++) { sx += x[i]; sy += y[i]; sxx += x[i] * x[i]; sxy += x[i] * y[i]; }
        return (n * sxy - sx * sy) / (n * sxx - sx * sx);
    }

    private static boolean report(String name, double measured, double predicted, String unit) {
        double relErr = Math.abs(measured - predicted) / predicted;
        boolean ok = relErr <= TOL;
        System.out.printf("  %-22s measured=%.5e  FDT=%.5e  %s  relErr=%+.2f%%  %s%n",
                name, measured, predicted, unit, 100 * (measured - predicted) / predicted,
                ok ? "OK" : "*** OUT OF TOLERANCE ***");
        return ok;
    }
}
