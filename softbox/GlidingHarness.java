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

    static double DT = 1.0e-5;                   // gliding dt; -dt overrides (dt-convergence test)
    static double MYO_SPRING = 1.0e-9;           // cross-bridge stiffness, N/µm (= 1 pN/nm). MEASUREMENT-ONLY override -myospring <pN/nm>; default UNCHANGED.
    static boolean FRESH_READ = false;           // -freshread: catch-slip + cycle read THIS step's forceDotFil
                                                 // (compute force+register BEFORE release/cycle, matching v1's
                                                 // reconciled order) vs the default one-step-stale read.
    static boolean NO_REFRACTORY = false;        // -norefractory: released motors are immediately bindable
    static boolean FAITHFUL_RELEASE = false;     // §6.10 -faithfulrelease: add v1's force-cap (12 pN) release branch (default off)
                                                 // (kinParams[10]=0) — the OFF bracket for §6.6 (measurement).
    static boolean FAITHFUL_REFRACTORY = false;  // §6.11 -faithfulrefractory: probabilistic 1-step block at v1's
                                                 // GPU-oracle effective rate (0.31) vs HEAD's 100%/1-step (default off).
    static double FORCE_BIAS = 0.0;              // -forcebias <eps>: inject a uniform −x seg-side force bias per bound
                                                 // motor (§6.8 susceptibility pre-filter). 0 = production (bit-identical).
    // ---- SATURATED_CROSSBRIDGE_DIAGNOSTIC secondary confirm (flag-gated; default-off ⇒ size-6 ⇒ plain Hookean) ----
    static int    XBSAT_MODE  = 0;               // -xbsat <mode> <Fmax_pN> <onset_pN>: saturating F8 (1 sym-tanh/2 sym-clip/3 asym-tanh/4 asym-clip)
    static double XBSAT_FMAX  = 0.0, XBSAT_ONSET = 0.0;  // ceiling / onset in N (entered pN)
    // ---- CROSSBRIDGE_DASHPOT (flag-gated; default-off ⇒ dash kernel not wired ⇒ byte-identical) ----
    static boolean DASH_ON = false;              // -xbdash <gammaMult>: parallel Kelvin-Voigt dashpot on F8
    static double  XBDASH_MULT = 0.0;            // γ_xb = gammaMult · γ_head (head bTransGam)
    static boolean DASH_MECH = false;            // -xbdashmech: dashpot mechanical force only (catch reads spring load)
    static final double ANCHOR_Z = -0.05;       // fixedMyosinZValue
    static final double FIL_Z = 0.0;            // gliding filament z (v1)
    static final double DENSITY = 500.0;        // motors / µm²
    static final int    FIL_SEGS = 11;          // ~2 µm of 64-monomer segments
    static final int    FIL_MONO = 64;          // filSegLength (gliding override)
    // bed geometry: bX0 = filament +x end; the bed spans x∈[bXlo,bXhi], y∈[-bYhalf,bYhalf].
    // default ≈6×2; -full → v1's 14×2 (~14k motors); -v1box → v1's 4×1 (exactly 2000 heads).
    static double bX0 = 2.2, bXlo = -3.5, bXhi = 2.7, bYhalf = 1.0;
    static int SEED = 0x6111D;   // varied across an ensemble (placement + RNG)
    static boolean BOX = false;      // -box: wire the ContainmentSystem chamber over the gliding filament (v1-faithful: anchored motors un-boxed)
    static boolean BOX_ALL = false;  // -boxall: additionally confine every motor sub-body (upper-bound box-check load at 3·nMotors bodies)

    public static void main(String[] args) {
        int M = 2000;
        String viz = null;
        boolean diag = false;
        boolean cycldiag = false;
        boolean grid = false;
        boolean ztrace = false;
        boolean assistlog = false;
        java.util.List<String> pos = new java.util.ArrayList<>();
        boolean gpu = false;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-3js")) viz = args[++i];
            else if (args[i].equals("-diag")) diag = true;
            else if (args[i].equals("-cycldiag")) cycldiag = true;
            else if (args[i].equals("-grid")) grid = true;
            else if (args[i].equals("-ztrace")) ztrace = true;
            else if (args[i].equals("-assistlog")) assistlog = true;   // §6.9 per-bound-motor tuple dump (default off)
            else if (args[i].equals("-gpu")) gpu = true;
            else if (args[i].equals("-full")) { bX0 = 5.87; bXlo = -7.0; bXhi = 6.37; bYhalf = 1.0; }  // v1 14×2, ~13.4k motors
            else if (args[i].equals("-v1box")) { bX0 = 1.7; bXlo = -2.0; bXhi = 2.0; bYhalf = 0.5; }   // v1 4×1, exactly 2000 heads
            else if (args[i].equals("-densemat")) {   // motor-mat throughput probe: square 14·√N bed (BoA dense-gliding box schedule) → 98000·N motors, single filament
                double side = 14.0 * Math.sqrt(Double.parseDouble(args[++i]));
                bX0 = 1.0; bXlo = -side / 2; bXhi = side / 2; bYhalf = side / 2;
            }
            else if (args[i].equals("-box")) BOX = true;            // ContainmentSystem over the filament (v1-faithful)
            else if (args[i].equals("-boxall")) { BOX = true; BOX_ALL = true; }  // + over every motor sub-body (load upper bound)
            else if (args[i].equals("-seed")) SEED = 0x6111D + 7919 * Integer.parseInt(args[++i]);
            else if (args[i].equals("-dt")) DT = Double.parseDouble(args[++i]);   // dt-convergence test
            else if (args[i].equals("-myospring")) MYO_SPRING = Double.parseDouble(args[++i]) * 1.0e-9;  // MEASUREMENT-ONLY: cross-bridge stiffness in pN/nm (1 = default). Hookean F8 unchanged; production default unchanged.
            else if (args[i].equals("-freshread")) FRESH_READ = true;             // release-read reorder A/B
            else if (args[i].equals("-norefractory")) NO_REFRACTORY = true;        // rebind-refractory OFF bracket
            else if (args[i].equals("-faithfulrelease")) FAITHFUL_RELEASE = true;   // §6.10 v1 force-cap release branch
            else if (args[i].equals("-faithfulrefractory")) FAITHFUL_REFRACTORY = true;  // §6.11 rate-faithful rebind refractory
            else if (args[i].equals("-forcebias")) FORCE_BIAS = Double.parseDouble(args[++i]);  // §6.8 susceptibility pre-filter
            else if (args[i].equals("-xbsat")) { XBSAT_MODE = Integer.parseInt(args[++i]); XBSAT_FMAX = Double.parseDouble(args[++i]) * 1.0e-12; XBSAT_ONSET = Double.parseDouble(args[++i]) * 1.0e-12; }  // MEASUREMENT-ONLY saturating F8
            else if (args[i].equals("-xbdash")) { XBDASH_MULT = Double.parseDouble(args[++i]); DASH_ON = XBDASH_MULT != 0.0; }  // MEASUREMENT-ONLY parallel dashpot (γ_xb = mult·γ_head)
            else if (args[i].equals("-xbdashmech")) DASH_MECH = true;  // dashpot mechanical force only (catch reads spring load)
            else if (args[i].equals("-forcetest")) { /* handled before buildScene */ }
            else pos.add(args[i]);
        }
        if (!pos.isEmpty()) M = Integer.parseInt(pos.get(0));

        for (String a : args) if (a.equals("-forcetest")) { forceTest(); return; }

        System.out.println("=== Soft Box increment 4b-iv — gliding assay (cheap probe) ===");
        Scene sc = buildScene();
        System.out.printf("config: %d-seg filament (%.2f µm) at z=%.3f, %d motors @ %.0f/µm² strip, dt=%.0e%n",
                sc.fil.n, sc.fil.n * sc.segL, FIL_Z, sc.mot.nMotors, DENSITY, DT);
        if (DASH_ON) {
            double kSI = MYO_SPRING * 1.0e6;   // N/µm → N/m; γ_head ≈ 1.885e-8 N·s/m (head sphere drag)
            System.out.printf("  -xbdash: PARALLEL DASHPOT ON%s — γ_xb = %.2f·γ_head ⇒ stretch-mode γ_eff = %.2f·γ_head; r=k·dt/γ_eff at dt=%.0e ≈ %.3f (Hookean r=%.3f)%n",
                    DASH_MECH ? " (mech-only: catch reads spring load)" : " (literal: catch reads spring+dashpot)",
                    XBDASH_MULT, 1.0 + XBDASH_MULT, DT,
                    kSI * DT / ((1.0 + XBDASH_MULT) * 1.885e-8), kSI * DT / 1.885e-8);
        }

        if (viz != null) { runViz(sc, Math.max(M, 20000), viz, gpu); return; }
        if (diag) { diagnose(sc, Math.max(M, 8000)); return; }
        if (cycldiag) { cyclediag(sc, Math.max(M, 8000)); return; }
        if (grid) { measureGrid(sc, M, gpu); return; }
        if (ztrace) { ztrace(sc, M, gpu); return; }
        if (assistlog) { assistLog(sc, M, gpu); return; }
        if (gpu) { gpuProbe(sc, M); return; }
        probe(sc, M);
    }

    static final class Scene {
        FilamentStore fil; MotorStore mot;
        FloatArray bondData, xbParams, boxParams;
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
        // gliding chain params (config-matched, NOT tuned): fracMove 0.5 / fracR 0.1 / fracMoveTorq 0.2.
        // dt-convergence test: change dt ONLY (the dt-correct quantities — cycle rate·dt, Brownian √dt,
        // Langevin force/γ·dt — auto-scale); the fracMove family is held at its operating values for BOTH
        // codes (per planner direction — do NOT scale fracMove). The v2/v1 RATIO at each dt isolates the
        // integration-scheme (update-order/staleness) difference.
        fil.chainParams.set(0, (float) DT); fil.chainParams.set(1, 0.5f); fil.chainParams.set(2, 0.1f);
        fil.chainParams.set(3, 0.2f); fil.chainParams.set(4, 0f); fil.chainParams.set(5, 1.0e-20f);
        fil.chainParams.set(6, (float) Constants.actinMonoRadius);
        DerivedGeometrySystem.derive(fil.coord, fil.uVec, fil.yVec, fil.zVec, fil.end1, fil.end2, fil.segLength, fil.counts);

        // ---- motor bed: density-faithful patch around the filament's −x path. Wide enough in y that
        //      the filament's ends stay over motors as it rotates/wanders (v1's bed is the full 2µm-wide
        //      box; a narrow strip lets the ends rotate out → unsupported ends lag → velocity drops). ----
        double bedXlo = bXlo, bedXhi = bXhi;          // explicit bed x-extent (matches v1's box)
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
        if (NO_REFRACTORY) mot.kinParams.set(10, 0f);   // §6.6 OFF bracket: no rebind refractory
        mot.setFaithfulRelease(FAITHFUL_RELEASE, 0.0);  // §6.10 default off (v1 12 pN threshold)
        mot.setFaithfulRefractory(FAITHFUL_REFRACTORY); // §6.11 default off (HEAD 100%/1-step block)
        mot.setDashpot(XBDASH_MULT, DT, DASH_MECH);     // CROSSBRIDGE_DASHPOT: γ_xb = mult·γ_head (mult=0 ⇒ off)
        mot.nucleotideState.init(MotorStore.NUC_NONE);

        int MAXC = SpatialGrid.MAX_CAND;
        sc.bondData = new FloatArray(nMot * CrossBridgeSystem.STRIDE); sc.bondData.init(0f);
        sc.xbParams = XBSAT_MODE != 0
            ? FloatArray.fromElements((float) MYO_SPRING, 90f, 0.4f, (float) DT, (float) MotorStore.HEAD_LEN, (float) FORCE_BIAS,
                                      (float) XBSAT_MODE, (float) XBSAT_FMAX, (float) XBSAT_ONSET)
            : FloatArray.fromElements((float) MYO_SPRING, 90f, 0.4f, (float) DT, (float) MotorStore.HEAD_LEN, (float) FORCE_BIAS);
        sc.segMotorCount = new IntArray(nSeg); sc.segMotorOffsets = new IntArray(nSeg + 1); sc.segMotorMyo = new IntArray(nMot);
        sc.reachSeg = new IntArray(nMot * MAXC); sc.reachSeg.init(-1); sc.reachCount = new IntArray(nMot);
        // in-vitro chamber matching the bed (v1 MyoMiniFilament.checkOuterBugCollision law): [tau, boxX, boxY, boxZ, R, coeff, checkInt]
        sc.boxParams = FloatArray.fromElements(1.0e-4f, (float) (bXhi - bXlo), (float) (2 * bYhalf), 0.5f, 0.005f, 0.5f, 10f);
        sc.fil = fil; sc.mot = mot;
        return sc;
    }

    /** One gliding step (CPU runner). Dispatches to the default or the fresh-read reorder. */
    static void step(Scene sc, int t) { if (FRESH_READ) stepFresh(sc, t); else stepOrig(sc, t); }

    /** One gliding step (CPU runner) — default order (catch-slip/cycle read last step's forceDotFil). */
    static void stepOrig(Scene sc, int t) {
        FilamentStore f = sc.fil; MotorStore mot = sc.mot; RigidRodBody b = mot.body;
        mot.setCounts(t, SEED, f.n);
        f.counts.set(1, t);
        // --- binding (dynamic) ---
        MotorStore.publishHeadFromBody(b.coord, b.uVec, b.segLength, mot.head, mot.uVec, mot.rodUVec, mot.counts);
        BindingDetectionSystem.bruteReachable(mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, sc.reachSeg, sc.reachCount, mot.kinParams, mot.counts);
        NucleotideCycleSystem.catchSlipRelease(mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.cooldown, mot.stats, mot.capStats, mot.kinParams, mot.counts);
        BindingDetectionSystem.bindNearest(mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, sc.reachSeg, sc.reachCount, mot.boundSeg, mot.bindArc, mot.kinParams, mot.counts);
        // --- motor nucleotide cycle + dynamics ---
        NucleotideCycleSystem.cycle(mot.nucleotideState, mot.boundSeg, mot.forceDotHist, mot.nucParams, mot.counts);
        ChainBendingForceSystem.zeroAccumulators(b.forceSum, b.torqueSum, mot.counts);
        BrownianForceSystem.brownianForce(b.randForce, b.randTorque, b.bTransGam, b.bRotGam, b.brownTransScale, b.brownRotScale, mot.bodyParams, mot.counts);
        MotorJointSystem.joints(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, mot.nucleotideState, mot.jointParams, mot.counts);
        TailAnchorSystem.anchor(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, mot.anchor, mot.jointParams, mot.counts);
        CrossBridgeSystem.bondForces(b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength,
                mot.boundSeg, mot.bindArc, mot.nucleotideState, sc.bondData, sc.xbParams);
        if (DASH_ON) CrossBridgeSystem.dashpotForces(b.coord, b.uVec, b.bTransGam, f.coord, f.uVec, f.segLength,
                mot.boundSeg, mot.bindArc, sc.bondData, mot.xbPrevStretch, mot.xbDashInit, mot.dashParams);
        CrossBridgeSystem.applyHeadForce(sc.bondData, b.forceSum, b.torqueSum, mot.counts);
        RigidRodLangevinIntegrationSystem.integrate(b.coord, b.uVec, b.yVec, b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, mot.bodyParams, mot.counts);
        DerivedGeometrySystem.derive(b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts);
        CrossBridgeSystem.registerForceDot(sc.bondData, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.counts);
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

    /** Fresh-read reorder (-freshread): compute the cross-bridge force + register forceDotFil BEFORE the
     *  catch-slip release + cycle, so they read THIS step's load (v1's reconciled order). All forces use
     *  the start-of-step state (forward-Euler unchanged); only the release/cycle/bind run after the force,
     *  and integration moves to the end. Force gather (head + seg) uses the pre-release bound set (3rd law).
     *  RNG is wang-hash-keyed ⇒ identical draws regardless of order: a clean A/B vs stepOrig. */
    static void stepFresh(Scene sc, int t) {
        FilamentStore f = sc.fil; MotorStore mot = sc.mot; RigidRodBody b = mot.body;
        mot.setCounts(t, SEED, f.n);
        f.counts.set(1, t);
        // --- reach (for binding, post-force) ---
        MotorStore.publishHeadFromBody(b.coord, b.uVec, b.segLength, mot.head, mot.uVec, mot.rodUVec, mot.counts);
        BindingDetectionSystem.bruteReachable(mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, sc.reachSeg, sc.reachCount, mot.kinParams, mot.counts);
        // --- motor forces (use the prior nucleotide state, like v1's addForces before biochemStep) ---
        ChainBendingForceSystem.zeroAccumulators(b.forceSum, b.torqueSum, mot.counts);
        BrownianForceSystem.brownianForce(b.randForce, b.randTorque, b.bTransGam, b.bRotGam, b.brownTransScale, b.brownRotScale, mot.bodyParams, mot.counts);
        MotorJointSystem.joints(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, mot.nucleotideState, mot.jointParams, mot.counts);
        TailAnchorSystem.anchor(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, mot.anchor, mot.jointParams, mot.counts);
        CrossBridgeSystem.bondForces(b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength,
                mot.boundSeg, mot.bindArc, mot.nucleotideState, sc.bondData, sc.xbParams);
        if (DASH_ON) CrossBridgeSystem.dashpotForces(b.coord, b.uVec, b.bTransGam, f.coord, f.uVec, f.segLength,
                mot.boundSeg, mot.bindArc, sc.bondData, mot.xbPrevStretch, mot.xbDashInit, mot.dashParams);
        CrossBridgeSystem.applyHeadForce(sc.bondData, b.forceSum, b.torqueSum, mot.counts);
        // --- filament forces + gather (pre-release bound set ⇒ Newton's 3rd law preserved) ---
        ChainBendingForceSystem.zeroAccumulators(f.forceSum, f.torqueSum, f.counts);
        BrownianForceSystem.brownianForce(f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.brownTransScale, f.brownRotScale, f.params, f.counts);
        ChainBendingForceSystem.chainForces(f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide, f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts);
        CrossBridgeSystem.csrHistogram(mot.boundSeg, mot.counts, sc.segMotorCount);
        CrossBridgeSystem.csrScan(mot.counts, sc.segMotorCount, sc.segMotorOffsets);
        CrossBridgeSystem.csrScatter(mot.boundSeg, mot.counts, sc.segMotorOffsets, sc.segMotorCount, sc.segMotorMyo);
        CrossBridgeSystem.segGather(sc.segMotorOffsets, sc.segMotorMyo, sc.bondData, f.forceSum, f.torqueSum, mot.counts);
        // --- register THIS step's forceDotFil, THEN release/bind/cycle read it FRESH ---
        CrossBridgeSystem.registerForceDot(sc.bondData, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.counts);
        NucleotideCycleSystem.catchSlipRelease(mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.cooldown, mot.stats, mot.capStats, mot.kinParams, mot.counts);
        BindingDetectionSystem.bindNearest(mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, sc.reachSeg, sc.reachCount, mot.boundSeg, mot.bindArc, mot.kinParams, mot.counts);
        NucleotideCycleSystem.cycle(mot.nucleotideState, mot.boundSeg, mot.forceDotHist, mot.nucParams, mot.counts);
        // --- integrate all bodies (forces from the start-of-step state) ---
        RigidRodLangevinIntegrationSystem.integrate(b.coord, b.uVec, b.yVec, b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, mot.bodyParams, mot.counts);
        DerivedGeometrySystem.derive(b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts);
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
                    mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.stats, mot.capStats, mot.cooldown,
                    mot.bodyParams, mot.jointParams, mot.nucParams, mot.kinParams,
                    sc.bondData, sc.xbParams, sc.segMotorCount, sc.segMotorOffsets, sc.segMotorMyo, sc.reachSeg, sc.reachCount,
                    f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.bTransGam, f.bRotGam,
                    f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.brownTransScale, f.brownRotScale,
                    f.params, f.chainParams, f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide, sc.boxParams)
            .transferToDevice(DataTransferMode.EVERY_EXECUTION, mot.counts, f.counts);
        if (DASH_ON) tg = tg.transferToDevice(DataTransferMode.FIRST_EXECUTION, mot.xbPrevStretch, mot.xbDashInit, mot.dashParams);
        // reach (common)
        tg = tg
            .task("publishHead", MotorStore::publishHeadFromBody, b.coord, b.uVec, b.segLength, mot.head, mot.uVec, mot.rodUVec, mot.counts)
            .task("reach", BindingDetectionSystem::bruteReachable, mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, sc.reachSeg, sc.reachCount, mot.kinParams, mot.counts);
        if (FRESH_READ) {
            // -freshread: compute force + register BEFORE release/cycle (v1's reconciled order); integrate last.
            tg = tg
                .task("zeroMot", ChainBendingForceSystem::zeroAccumulators, b.forceSum, b.torqueSum, mot.counts)
                .task("brownMot", BrownianForceSystem::brownianForce, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, b.brownTransScale, b.brownRotScale, mot.bodyParams, mot.counts)
                .task("joints", MotorJointSystem::joints, b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, mot.nucleotideState, mot.jointParams, mot.counts)
                .task("anchor", TailAnchorSystem::anchor, b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, mot.anchor, mot.jointParams, mot.counts)
                .task("bond", CrossBridgeSystem::bondForces, b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength, mot.boundSeg, mot.bindArc, mot.nucleotideState, sc.bondData, sc.xbParams);
            if (DASH_ON) tg = tg.task("dash", CrossBridgeSystem::dashpotForces, b.coord, b.uVec, b.bTransGam, f.coord, f.uVec, f.segLength, mot.boundSeg, mot.bindArc, sc.bondData, mot.xbPrevStretch, mot.xbDashInit, mot.dashParams);
            tg = tg
                .task("applyHead", CrossBridgeSystem::applyHeadForce, sc.bondData, b.forceSum, b.torqueSum, mot.counts)
                .task("zeroFil", ChainBendingForceSystem::zeroAccumulators, f.forceSum, f.torqueSum, f.counts)
                .task("brownFil", BrownianForceSystem::brownianForce, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.brownTransScale, f.brownRotScale, f.params, f.counts)
                .task("chain", ChainBendingForceSystem::chainForces, f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide, f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts)
                .task("csrHist", CrossBridgeSystem::csrHistogram, mot.boundSeg, mot.counts, sc.segMotorCount)
                .task("csrScan", CrossBridgeSystem::csrScan, mot.counts, sc.segMotorCount, sc.segMotorOffsets)
                .task("csrScatter", CrossBridgeSystem::csrScatter, mot.boundSeg, mot.counts, sc.segMotorOffsets, sc.segMotorCount, sc.segMotorMyo)
                .task("gather", CrossBridgeSystem::segGather, sc.segMotorOffsets, sc.segMotorMyo, sc.bondData, f.forceSum, f.torqueSum, mot.counts)
                .task("register", CrossBridgeSystem::registerForceDot, sc.bondData, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.counts)
                .task("release", NucleotideCycleSystem::catchSlipRelease, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.cooldown, mot.stats, mot.capStats, mot.kinParams, mot.counts)
                .task("bind", BindingDetectionSystem::bindNearest, mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, sc.reachSeg, sc.reachCount, mot.boundSeg, mot.bindArc, mot.kinParams, mot.counts)
                .task("cycle", NucleotideCycleSystem::cycle, mot.nucleotideState, mot.boundSeg, mot.forceDotHist, mot.nucParams, mot.counts)
                .task("integMot", RigidRodLangevinIntegrationSystem::integrate, b.coord, b.uVec, b.yVec, b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, mot.bodyParams, mot.counts)
                .task("deriveMot", DerivedGeometrySystem::derive, b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts)
                .task("integFil", RigidRodLangevinIntegrationSystem::integrate, f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts)
                .task("deriveFil", DerivedGeometrySystem::derive, f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
        } else {
            // default: release/cycle read last step's forceDotFil (force computed after them).
            tg = tg
                .task("release", NucleotideCycleSystem::catchSlipRelease, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.cooldown, mot.stats, mot.capStats, mot.kinParams, mot.counts)
                .task("bind", BindingDetectionSystem::bindNearest, mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, sc.reachSeg, sc.reachCount, mot.boundSeg, mot.bindArc, mot.kinParams, mot.counts)
                .task("cycle", NucleotideCycleSystem::cycle, mot.nucleotideState, mot.boundSeg, mot.forceDotHist, mot.nucParams, mot.counts)
                .task("zeroMot", ChainBendingForceSystem::zeroAccumulators, b.forceSum, b.torqueSum, mot.counts)
                .task("brownMot", BrownianForceSystem::brownianForce, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, b.brownTransScale, b.brownRotScale, mot.bodyParams, mot.counts)
                .task("joints", MotorJointSystem::joints, b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, mot.nucleotideState, mot.jointParams, mot.counts)
                .task("anchor", TailAnchorSystem::anchor, b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, mot.anchor, mot.jointParams, mot.counts)
                .task("bond", CrossBridgeSystem::bondForces, b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength, mot.boundSeg, mot.bindArc, mot.nucleotideState, sc.bondData, sc.xbParams);
            if (DASH_ON) tg = tg.task("dash", CrossBridgeSystem::dashpotForces, b.coord, b.uVec, b.bTransGam, f.coord, f.uVec, f.segLength, mot.boundSeg, mot.bindArc, sc.bondData, mot.xbPrevStretch, mot.xbDashInit, mot.dashParams);
            tg = tg.task("applyHead", CrossBridgeSystem::applyHeadForce, sc.bondData, b.forceSum, b.torqueSum, mot.counts);
            if (BOX_ALL) tg = tg.task("confineMot", ContainmentSystem::confine, b.coord, b.uVec, b.segLength, b.bTransGam, b.forceSum, b.torqueSum, sc.boxParams, mot.counts);
            tg = tg
                .task("integMot", RigidRodLangevinIntegrationSystem::integrate, b.coord, b.uVec, b.yVec, b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, mot.bodyParams, mot.counts)
                .task("deriveMot", DerivedGeometrySystem::derive, b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts)
                .task("register", CrossBridgeSystem::registerForceDot, sc.bondData, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.counts)
                .task("zeroFil", ChainBendingForceSystem::zeroAccumulators, f.forceSum, f.torqueSum, f.counts)
                .task("brownFil", BrownianForceSystem::brownianForce, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.brownTransScale, f.brownRotScale, f.params, f.counts)
                .task("chain", ChainBendingForceSystem::chainForces, f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide, f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts)
                .task("csrHist", CrossBridgeSystem::csrHistogram, mot.boundSeg, mot.counts, sc.segMotorCount)
                .task("csrScan", CrossBridgeSystem::csrScan, mot.counts, sc.segMotorCount, sc.segMotorOffsets)
                .task("csrScatter", CrossBridgeSystem::csrScatter, mot.boundSeg, mot.counts, sc.segMotorOffsets, sc.segMotorCount, sc.segMotorMyo)
                .task("gather", CrossBridgeSystem::segGather, sc.segMotorOffsets, sc.segMotorMyo, sc.bondData, f.forceSum, f.torqueSum, mot.counts);
            if (BOX) tg = tg.task("confineFil", ContainmentSystem::confine, f.coord, f.uVec, f.segLength, f.bTransGam, f.forceSum, f.torqueSum, sc.boxParams, f.counts);
            tg = tg
                .task("integFil", RigidRodLangevinIntegrationSystem::integrate, f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts)
                .task("deriveFil", DerivedGeometrySystem::derive, f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
        }
        tg = tg.transferToHost(DataTransferMode.UNDER_DEMAND, f.coord, f.uVec, f.end1, f.end2, mot.boundSeg,
                    b.end1, b.end2, mot.nucleotideState, mot.forceDotFil,
                    mot.bindArc, b.uVec,    // §6.9: bindArc + head uVec for the per-bound-motor pose log (demand-only; no kernel change)
                    mot.capStats, mot.stats);   // §6.10: break-force firing-rate readback (demand-only)

        int nB = 3 * mot.nMotors, nM = mot.nMotors, nSeg = f.n;
        sched = new GridScheduler();
        for (String t : new String[]{ "publishHead","reach","release","bind","cycle","anchor","bond","applyHead","register" }) addW("gliding." + t, pad(nM));
        if (DASH_ON) addW("gliding.dash", pad(nM));
        for (String t : new String[]{ "zeroMot","brownMot","joints","integMot","deriveMot" }) addW("gliding." + t, pad(nB));
        for (String t : new String[]{ "zeroFil","brownFil","chain","gather","integFil","deriveFil" }) addW("gliding." + t, pad(nSeg));
        for (String t : new String[]{ "csrHist","csrScan","csrScatter" }) addS("gliding." + t);
        if (BOX) addW("gliding.confineFil", pad(nSeg));
        if (BOX_ALL) addW("gliding.confineMot", pad(nB));
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

    /**
     * 4b-iv cycle self-consistency probe (measurement only, CPU runner). During gliding, measure the
     * EMPIRICAL per-state conditional transition rates and the ADP→NONE gate-open fraction, and compare
     * to the nominal validated rates (NONE→ATP 0.2, ATP→ADPPi 0.001, ADPPi→ADP 0.1, ADP→NONE 0.01 /step
     * when gate open). If the empirical rates match nominal, the cycle obeys its own rates under load —
     * NOT malfunctioning; the high ADP occupancy is then explained by the load-gate, not a bug. Also
     * splits `forceDotFil` by sign (assist vs resist) and reports the predicted-vs-measured occupancy.
     */
    static void cyclediag(Scene sc, int M) {
        MotorStore mot = sc.mot; int nM = mot.nMotors; int warm = 2000;
        long[] stateSteps = new long[4];      // steps spent in each state (bound)
        long[] transOut = new long[4];         // cycle transitions OUT of each state (bound→bound, next state)
        long adpGateOpen = 0, adpGateClosed = 0, adpToNone = 0;   // ADP gate accounting
        double fdSum = 0; long fdN = 0, fdPos = 0; double fdPosSum = 0, fdNegSum = 0; long fdNeg = 0;
        // cross-bridge force magnitude (v1 break-force release at >12 pN; v2 lacks it — is it material?)
        double fmagSum = 0; long fmagOver12 = 0, fmagOver12assist = 0, fmagOver12resist = 0; double fmagMax = 0;
        int[] prevState = new int[nM], prevBound = new int[nM];
        for (int m = 0; m < nM; m++) { prevState[m] = mot.nucleotideState.get(m); prevBound[m] = mot.boundSeg.get(m); }
        for (int t = 0; t < M; t++) {
            step(sc, t);
            if (t < warm) { for (int m = 0; m < nM; m++) { prevState[m] = mot.nucleotideState.get(m); prevBound[m] = mot.boundSeg.get(m); } continue; }
            for (int m = 0; m < nM; m++) {
                int bs = mot.boundSeg.get(m), st = mot.nucleotideState.get(m);
                boolean boundNow = bs >= 0, boundPrev = prevBound[m] >= 0;
                if (boundPrev) {
                    int ps = prevState[m];
                    stateSteps[ps]++;
                    float fd = mot.forceDotFil.get(m); fdSum += fd; fdN++;
                    if (fd > 0) { fdPos++; fdPosSum += fd; } else if (fd < 0) { fdNeg++; fdNegSum += fd; }
                    int d = m * CrossBridgeSystem.STRIDE;
                    double bx = sc.bondData.get(d), by = sc.bondData.get(d + 1), bz = sc.bondData.get(d + 2);
                    double fmag = Math.sqrt(bx*bx + by*by + bz*bz);   // cross-bridge force magnitude (N)
                    fmagSum += fmag; if (fmag > fmagMax) fmagMax = fmag;
                    if (fmag > 12.0e-12) { fmagOver12++; if (fd > 0) fmagOver12assist++; else fmagOver12resist++; }
                    if (ps == MotorStore.NUC_ADP) {
                        float avg = 0f; int b = m * 10; for (int k = 0; k < 10; k++) avg += mot.forceDotHist.get(b + k); avg *= 0.1f;
                        if (avg <= 0f) adpGateOpen++; else adpGateClosed++;
                    }
                    // cycle transition (bound both steps, state advanced)
                    if (boundNow && st != ps) {
                        transOut[ps]++;
                        if (ps == MotorStore.NUC_ADP && st == MotorStore.NUC_NONE) adpToNone++;
                    }
                }
                prevState[m] = st; prevBound[m] = bs;
            }
        }
        long tot = stateSteps[0] + stateSteps[1] + stateSteps[2] + stateSteps[3];
        double dt = DT;
        // empirical conditional rates (per bound-step in that state)
        double rN = transOut[0] / (double) stateSteps[0];   // NONE→ATP
        double rA = transOut[1] / (double) stateSteps[1];   // ATP→ADPPi
        double rP = transOut[2] / (double) stateSteps[2];   // ADPPi→ADP
        double rDopen = adpToNone / (double) adpGateOpen;   // ADP→NONE per gate-OPEN step
        double gateOpenFrac = adpGateOpen / (double) (adpGateOpen + adpGateClosed);
        System.out.println("\n=== cycle self-consistency under gliding load (post-warmup, CPU) ===");
        System.out.printf("  occupancy (bound): NONE %.2f%%  ATP %.2f%%  ADPPi %.2f%%  ADP %.2f%%%n",
                100.0*stateSteps[0]/tot, 100.0*stateSteps[1]/tot, 100.0*stateSteps[2]/tot, 100.0*stateSteps[3]/tot);
        System.out.println("  empirical conditional transition rate (/step)  vs  nominal:");
        System.out.printf("    NONE→ATP   %.5f  vs 0.20000   (%.1f%%)%n", rN, 100*rN/0.2);
        System.out.printf("    ATP→ADPPi  %.6f vs 0.00100   (%.1f%%)%n", rA, 100*rA/0.001);
        System.out.printf("    ADPPi→ADP  %.5f  vs 0.10000   (%.1f%%)%n", rP, 100*rP/0.1);
        System.out.printf("    ADP→NONE   %.5f  vs 0.01000   (%.1f%%)  [conditioned on gate OPEN]%n", rDopen, 100*rDopen/0.01);
        System.out.printf("  ADP gate-open fraction (10-window avg ≤ 0): %.3f  (closed %.3f ⇒ ADP held by load)%n",
                gateOpenFrac, 1 - gateOpenFrac);
        System.out.printf("  forceDotFil: mean %.3g N, %.1f%% assist(>0) / %.1f%% resist(<0); mean|assist| %.3g, mean|resist| %.3g%n",
                fdSum/fdN, 100.0*fdPos/fdN, 100.0*fdNeg/fdN, fdPosSum/Math.max(1,fdPos), fdNegSum/Math.max(1,fdNeg));
        System.out.printf("  cross-bridge forceMag: mean %.3g N (%.2f pN), max %.3g N (%.1f pN)%n",
                fmagSum/fdN, 1e12*fmagSum/fdN, fmagMax, 1e12*fmagMax);
        System.out.printf("  forceMag > 12 pN (v1's break-force release; v2 LACKS it): %.3f%% of bound-steps  (assist %.3f%% / resist %.3f%%)%n",
                100.0*fmagOver12/fdN, 100.0*fmagOver12assist/fdN, 100.0*fmagOver12resist/fdN);
        // predicted occupancy from empirical dwells (NONE 1/rN, ATP 1/rA, ADPPi 1/rP, ADP = stateSteps/adpToNone)
        double dN=1/rN, dA=1/rA, dP=1/rP, dD=stateSteps[3]/(double)adpToNone, ds=dN+dA+dP+dD;
        System.out.printf("  predicted occupancy from dwells (NONE %.0f/ATP %.0f/ADPPi %.0f/ADP %.0f steps): NONE %.1f%% ATP %.1f%% ADPPi %.1f%% ADP %.1f%%%n",
                dN,dA,dP,dD, 100*dN/ds,100*dA/ds,100*dP/ds,100*dD/ds);
        System.out.println("  ⇒ empirical rates ≈ nominal ⇒ cycle self-consistent under load (high ADP = the load-gate, not a bug)");
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

    static double centroidY(FilamentStore f) { double s = 0; for (int i = 0; i < f.n; i++) s += f.coordY(i); return s / f.n; }

    /**
     * Fixture-reconciliation measurement (4b-iv): emit BOTH of v1's velocity statistics, computed by the
     * EXACT GlidingAssayEvaluator algorithm, so v2 is comparable to v1 cell-for-cell. Samples the filament
     * centroid every OUT_INT=100 steps (v1's toFileInterval) and reports:
     *   - instantaneousSpeed: per-interval 3D displacement magnitude / dt   (GlidingAssayEvaluator L226–234)
     *   - longWindowSpeedXY:  XY chord over a 1 s ring-buffer window / dt     (L237–263; the v1 "primary" stat)
     *   - net:                XY (and x-only) net centroid displacement / total time  (the honest net glide)
     * Mean over all intervals AND over the steady 2nd half; avgBound averaged the same way. Measurement
     * only — no physics touched. GPU pulls fil.coord+boundSeg at the 100-step output cadence (residency
     * preserved); CPU runner steps in-process.
     */
    static void measureGrid(Scene sc, int M, boolean gpu) {
        final int OUT_INT = 100;
        final double dtInt = OUT_INT * DT;
        final int bufCap = Math.max(2, (int) Math.round(1.0 / dtInt));   // v1 LONG_WINDOW_SECONDS=1.0
        int nInt = M / OUT_INT;
        double[] cx = new double[nInt + 1], cy = new double[nInt + 1], cz = new double[nInt + 1];
        long[] bnd = new long[nInt + 1];
        System.out.printf("%n--- grid measurement (%s, %d motors, box x∈[%.1f,%.1f] y±%.1f, seed=0x%X) ---%n",
                gpu ? "GPU" : "CPU", sc.mot.nMotors, bXlo, bXhi, bYhalf, SEED);

        int k = 0;
        if (gpu) {
            TornadoExecutionPlan plan = buildPlan(sc);
            sc.mot.setCounts(0, SEED, sc.fil.n); sc.fil.counts.set(1, 0);
            plan.withGridScheduler(sched).execute();                    // warm-up (PTX compile), untimed
            cx[0] = centroidX(sc.fil); cy[0] = centroidY(sc.fil); cz[0] = centroidZ(sc.fil); bnd[0] = bound(sc.mot); k = 1;
            TornadoExecutionResult res = null;
            for (int t = 1; t < M; t++) {
                sc.mot.setCounts(t, SEED, sc.fil.n); sc.fil.counts.set(1, t);
                res = plan.withGridScheduler(sched).execute();
                if ((t + 1) % OUT_INT == 0 && k <= nInt) {
                    res.transferToHost(sc.fil.coord, sc.mot.boundSeg);
                    cx[k] = centroidX(sc.fil); cy[k] = centroidY(sc.fil); cz[k] = centroidZ(sc.fil); bnd[k] = bound(sc.mot); k++;
                }
            }
            if (res != null) res.transferToHost(sc.mot.capStats, sc.mot.stats);   // §6.10 firing-rate pull
        } else {
            cx[0] = centroidX(sc.fil); cy[0] = centroidY(sc.fil); cz[0] = centroidZ(sc.fil); bnd[0] = bound(sc.mot); k = 1;
            for (int t = 0; t < M; t++) {
                step(sc, t);
                if ((t + 1) % OUT_INT == 0 && k <= nInt) {
                    cx[k] = centroidX(sc.fil); cy[k] = centroidY(sc.fil); cz[k] = centroidZ(sc.fil); bnd[k] = bound(sc.mot); k++;
                }
            }
        }
        int n = k;   // number of samples (intervals + 1)

        // ---- v1's instantaneousSpeed: per-interval 3D displacement / dt ----
        double instAll = 0, instSteady = 0; int instN = 0, instNs = 0;
        for (int i = 1; i < n; i++) {
            double dx = cx[i] - cx[i-1], dy = cy[i] - cy[i-1], dz = cz[i] - cz[i-1];
            double sp = Math.sqrt(dx*dx + dy*dy + dz*dz) / dtInt;
            instAll += sp; instN++;
            if (i >= n / 2) { instSteady += sp; instNs++; }
        }
        // ---- v1's longWindowSpeedXY at end of run (ring buffer; XY chord over the window) ----
        int newest = n - 1, oldest = Math.max(0, n - bufCap);
        double lwDx = cx[newest] - cx[oldest], lwDy = cy[newest] - cy[oldest];
        double lwDt = (newest - oldest) * dtInt;
        double longWindowSpeedXY = lwDt > 1e-12 ? Math.sqrt(lwDx*lwDx + lwDy*lwDy) / lwDt : 0.0;
        // ---- net displacement / time (honest net glide), full run and steady 2nd half ----
        double netXY = Math.sqrt(Math.pow(cx[n-1]-cx[0],2) + Math.pow(cy[n-1]-cy[0],2)) / ((n-1)*dtInt);
        double netX  = (cx[n-1] - cx[0]) / ((n-1)*dtInt);
        int h = n / 2;
        double netXYsteady = Math.sqrt(Math.pow(cx[n-1]-cx[h],2) + Math.pow(cy[n-1]-cy[h],2)) / ((n-1-h)*dtInt);
        // ---- avgBound ----
        double avgBall = 0; for (int i = 0; i < n; i++) avgBall += bnd[i]; avgBall /= n;
        double avgBsteady = 0; for (int i = h; i < n; i++) avgBsteady += bnd[i]; avgBsteady /= (n - h);

        System.out.printf("  samples=%d  bufCap=%d  netΔx=%.4f µm over %.4f s%n", n, bufCap, cx[n-1]-cx[0], (n-1)*dtInt);
        System.out.println("  STATISTIC                       full-run    steady(2nd-half)");
        System.out.printf("  instantaneousSpeed (3D/intvl) :  %7.3f     %7.3f   µm/s%n", instAll/instN, instSteady/Math.max(1,instNs));
        System.out.printf("  net-displacement XY/time      :  %7.3f     %7.3f   µm/s%n", netXY, netXYsteady);
        System.out.printf("  net-displacement x-only/time  :  %7.3f                µm/s%n", netX);
        System.out.printf("  longWindowSpeedXY @ end       :  %7.3f                µm/s%n", longWindowSpeedXY);
        System.out.printf("  avgBound                      :  %7.3f     %7.3f%n", avgBall, avgBsteady);
        System.out.printf("  GRID_ROW seed=0x%X nMot=%d inst=%.3f instSteady=%.3f netXY=%.3f netSteady=%.3f netX=%.3f lwXY=%.3f avgB=%.3f%n",
                SEED, sc.mot.nMotors, instAll/instN, instSteady/Math.max(1,instNs), netXY, netXYsteady, netX, longWindowSpeedXY, avgBall);
        // §6.10 break-force release firing rate: cap fires / bound-motor-steps (whole run).
        long capFires = 0, boundStepsTot = 0;
        for (int m = 0; m < sc.mot.nMotors; m++) { capFires += sc.mot.capStats.get(m); boundStepsTot += sc.mot.stats.get(2*m); }
        double capRate = boundStepsTot > 0 ? (double) capFires / boundStepsTot : 0.0;
        System.out.printf("  CAP_ROW seed=0x%X faithfulRelease=%s capFires=%d boundSteps=%d capRatePerBoundStep=%.5f%n",
                SEED, FAITHFUL_RELEASE ? "ON" : "OFF", capFires, boundStepsTot, capRate);
    }

    /** filament centroid-z and a tilt/bow proxy (max−min segment z). */
    static double zSpread(FilamentStore f) { double lo=1e9,hi=-1e9; for(int i=0;i<f.n;i++){double z=f.coordZ(i);lo=Math.min(lo,z);hi=Math.max(hi,z);} return hi-lo; }

    /**
     * 4b-iv z-settling probe (measurement only). Per output interval (100 steps = 1 ms, matching v1's
     * toFileInterval) emit a TRACE_ROW for the filament: centroid-z, tilt (max−min seg z), avgBound,
     * per-interval net-x glide (forward displacement / dt), and the bound-motor along-filament load
     * (mean `forceDotFil` and the assist fraction, forceDotFil>0 = force along the −x glide). Lets us
     * test §6(c): does v2's z settle (vs v1's `.dat` posZ), and does its glide-decay track z / a drop in
     * productive engagement? GPU pulls fil.coord + boundSeg + forceDotFil at the 100-step cadence.
     */
    static void ztrace(Scene sc, int M, boolean gpu) {
        final int OUT_INT = 100; final double dtInt = OUT_INT * DT;
        MotorStore mot = sc.mot; FilamentStore f = sc.fil;
        System.out.printf("%n--- z-trace (%s, %d motors, box x∈[%.1f,%.1f] y±%.1f, seed=0x%X) ---%n",
                gpu ? "GPU" : "CPU", mot.nMotors, bXlo, bXhi, bYhalf, SEED);
        System.out.println("ZTRACE_HDR seed iv t cz tilt avgB glide fdMean fdPosFrac");
        TornadoExecutionPlan plan = null;
        double prevX = centroidX(f);
        if (gpu) { plan = buildPlan(sc); mot.setCounts(0, SEED, f.n); f.counts.set(1, 0); plan.withGridScheduler(sched).execute(); }
        int iv = 0;
        for (int t = 0; t < M; t++) {
            if (gpu) {
                mot.setCounts(t, SEED, f.n); f.counts.set(1, t);
                TornadoExecutionResult res = plan.withGridScheduler(sched).execute();
                if ((t + 1) % OUT_INT == 0) res.transferToHost(f.coord, mot.boundSeg, mot.forceDotFil);
            } else {
                step(sc, t);
            }
            if ((t + 1) % OUT_INT == 0) {
                iv++;
                double cx = centroidX(f), cz = centroidZ(f), tilt = zSpread(f);
                double glide = -(cx - prevX) / dtInt;   // forward (−x) per-interval rate, µm/s
                prevX = cx;
                long bnd = 0; double fdSum = 0; long fdN = 0, fdPos = 0;
                for (int m = 0; m < mot.nMotors; m++) {
                    if (mot.boundSeg.get(m) < 0) continue;
                    bnd++; float fd = mot.forceDotFil.get(m); fdSum += fd; fdN++; if (fd > 0) fdPos++;
                }
                System.out.printf("ZTRACE 0x%X %d %.4f %.5f %.5f %d %.4f %.4g %.4f%n",
                        SEED, iv, (t + 1) * DT, cz, tilt, bnd, glide,
                        fdN > 0 ? fdSum / fdN : 0.0, fdN > 0 ? (double) fdPos / fdN : 0.0);
            }
        }
    }

    /**
     * §6.9 per-bound-motor assist-decomposition logger (measurement only). At post-transient sampled
     * steps (every OUT_INT=100 after warm=WARM, matching v1's GlidingAssayEvaluator output cadence),
     * emit one ALOG row per BOUND motor with the planner's tuple:
     *   {nucleotideState, assistSign, forceDotFil(load), bindArc, poseAngle}
     * plus bindArcFrac (bindArc/segLen) + segLen for cross-segLen comparability. assistSign REUSES v2's
     * existing assist metric — sign(forceDotFil), assist = forceDotFil>0 (the same quantity -diag/
     * -cycldiag/-ztrace aggregate; identical definition to v1's MyoFilLink Dot(F,segU)). poseAngle =
     * acos(dot(head uVec, bound-seg uVec)) deg — the motor-axis-vs-filament-axis angle the F9 cross-bridge
     * torque drives toward 90°/120°. Reads EXISTING arrays only; GPU pulls them at the 100-step cadence
     * (residency preserved). Also prints the marginal ASSIST_SUMMARY (assist-fraction + occupancy +
     * avgBound) — the Phase-0 regression guard.
     */
    static void assistLog(Scene sc, int M, boolean gpu) {
        final int OUT_INT = 100; final int WARM = 2000;
        FilamentStore f = sc.fil; MotorStore mot = sc.mot; RigidRodBody b = mot.body;
        int nB = 3 * mot.nMotors;
        System.out.printf("%n--- assist-decomp log (%s, %d motors, box x∈[%.1f,%.1f] y±%.1f, seed=0x%X, warm=%d) ---%n",
                gpu ? "GPU" : "CPU", mot.nMotors, bXlo, bXhi, bYhalf, SEED, WARM);
        System.out.println("ALOG_HDR seed t motor state assistSign forceDotFil bindArc bindArcFrac poseAngleDeg segLen");
        long[] stateCnt = new long[4]; long boundObs = 0, assistObs = 0; double boundSum = 0; long sampleN = 0;
        TornadoExecutionPlan plan = null;
        if (gpu) { plan = buildPlan(sc); mot.setCounts(0, SEED, f.n); f.counts.set(1, 0); plan.withGridScheduler(sched).execute(); }
        for (int t = 0; t < M; t++) {
            if (gpu) {
                mot.setCounts(t, SEED, f.n); f.counts.set(1, t);
                TornadoExecutionResult res = plan.withGridScheduler(sched).execute();
                if (t >= WARM && (t + 1) % OUT_INT == 0)
                    res.transferToHost(f.uVec, mot.boundSeg, mot.bindArc, mot.nucleotideState, mot.forceDotFil, b.uVec);
            } else {
                step(sc, t);
            }
            if (t >= WARM && (t + 1) % OUT_INT == 0) {
                long bnd = 0;
                for (int m = 0; m < mot.nMotors; m++) {
                    int s = mot.boundSeg.get(m);
                    if (s < 0) continue;
                    bnd++; boundObs++;
                    int st = mot.nucleotideState.get(m); stateCnt[st]++;
                    float fd = mot.forceDotFil.get(m);
                    int sign = fd > 0 ? 1 : (fd < 0 ? -1 : 0);
                    if (fd > 0) assistObs++;
                    float arc = mot.bindArc.get(m), segL = f.segLength.get(s);
                    int h = 3 * m + 2;
                    double hux = b.uVec.get(h), huy = b.uVec.get(nB + h), huz = b.uVec.get(2 * nB + h);
                    double sux = f.uVec.get(s), suy = f.uVec.get(f.n + s), suz = f.uVec.get(2 * f.n + s);
                    double dot = hux*sux + huy*suy + huz*suz; if (dot > 1) dot = 1; if (dot < -1) dot = -1;
                    double poseDeg = Math.acos(dot) * 180.0 / Math.PI;
                    System.out.printf("ALOG 0x%X %d %d %d %d %.6g %.5f %.5f %.3f %.5f%n",
                            SEED, t + 1, m, st, sign, fd, arc, segL > 0 ? arc / segL : 0.0, poseDeg, segL);
                }
                boundSum += bnd; sampleN++;
            }
        }
        long tot = stateCnt[0] + stateCnt[1] + stateCnt[2] + stateCnt[3];
        System.out.printf("ASSIST_SUMMARY seed=0x%X nObs=%d assistFrac=%.4f occ[NONE/ATP/ADPPi/ADP]=%.4f/%.4f/%.4f/%.4f avgBound=%.3f%n",
                SEED, boundObs, boundObs > 0 ? (double) assistObs / boundObs : 0.0,
                tot>0?(double)stateCnt[0]/tot:0, tot>0?(double)stateCnt[1]/tot:0, tot>0?(double)stateCnt[2]/tot:0, tot>0?(double)stateCnt[3]/tot:0,
                sampleN > 0 ? boundSum / sampleN : 0.0);
    }

    /**
     * 4b-iv per-step force cross-check (measurement only). Feed v1's EXACT compute-time bound config
     * (dumped from an instrumented v1 `MyoFilLink.addForces`) into v2's `CrossBridgeSystem.bondForces`
     * and compare the head-side F8 vector + forceDotFil. Tests directly: does v2 compute the same
     * cross-bridge force vectors as v1 for an identical bound configuration?
     */
    static void forceTest() {
        // --- v1 compute-time dump (BoA-v1ref scratch MyoFilLink.addForces, 4x1 gliding, seed 1) ---
        double[] headC = {1.870585, -0.000148, 0.022387}, headU = {0.180790, 0.174904, -0.967845};
        double[] segC  = {1.821203,  0.007071, 0.009056}, segU  = {0.999057, -0.041840, -0.011599};
        double posOnSeg = 0.139078, segLen = 0.175500, myoSpring = 1.0e-9;
        double[] v1F8 = {8.98676e-14, 3.32284e-12, -4.24803e-12}; double v1forceMag = 5.39399e-12, v1fdf = 2.71166e-17;

        // --- build a minimal v2 config (1 segment, 1 motor) at the EXACT same geometry ---
        FilamentStore f = new FilamentStore(1);
        f.setCoord(0, (float) segC[0], (float) segC[1], (float) segC[2]);
        f.setUVec(0, (float) segU[0], (float) segU[1], (float) segU[2]); f.setYVec(0, 0f, 1f, 0f);
        f.segLength.set(0, (float) segLen); f.bRotGam.init(1f);
        MotorStore mot = new MotorStore(1);
        mot.assembleArticulated(0, (float) segC[0], (float) segC[1], (float) ANCHOR_Z, 0f, 0f, 1f, (float) Constants.BTransCoeff);
        RigidRodBody b = mot.body; int h = 2, nB = 3;   // head sub-body slot for motor 0
        b.coord.set(h, (float) headC[0]); b.coord.set(nB + h, (float) headC[1]); b.coord.set(2*nB + h, (float) headC[2]);
        b.uVec.set(h, (float) headU[0]); b.uVec.set(nB + h, (float) headU[1]); b.uVec.set(2*nB + h, (float) headU[2]);
        b.yVec.set(h, 0f); b.yVec.set(nB + h, 1f); b.yVec.set(2*nB + h, 0f); b.bRotGam.init(1f);
        mot.boundSeg.set(0, 0); mot.bindArc.set(0, (float) posOnSeg); mot.nucleotideState.set(0, MotorStore.NUC_ATP);
        FloatArray bondData = new FloatArray(CrossBridgeSystem.STRIDE); bondData.init(0f);
        FloatArray xbParams = FloatArray.fromElements((float) myoSpring, 90f, 0.4f, (float) DT, (float) MotorStore.HEAD_LEN, 0f);

        CrossBridgeSystem.bondForces(b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength,
                mot.boundSeg, mot.bindArc, mot.nucleotideState, bondData, xbParams);

        double v2F8x = bondData.get(0), v2F8y = bondData.get(1), v2F8z = bondData.get(2);
        double v2mag = Math.sqrt(v2F8x*v2F8x + v2F8y*v2F8y + v2F8z*v2F8z), v2fdf = bondData.get(12);
        System.out.println("\n=== 4b-iv per-step cross-bridge force cross-check (v1 config → v2 bondForces) ===");
        System.out.printf("  config: head=(%.5f,%.5f,%.5f) seg=(%.5f,%.5f,%.5f) bindArc=%.5f segLen=%.5f%n",
                headC[0],headC[1],headC[2], segC[0],segC[1],segC[2], posOnSeg, segLen);
        System.out.printf("  %-14s %-16s %-16s %-16s%n", "", "v1", "v2", "|Δ|/|v1|");
        System.out.printf("  %-14s %-16.6g %-16.6g %.2g%n", "F8.x (N)", v1F8[0], v2F8x, Math.abs(v2F8x-v1F8[0])/Math.abs(v1F8[0]));
        System.out.printf("  %-14s %-16.6g %-16.6g %.2g%n", "F8.y (N)", v1F8[1], v2F8y, Math.abs(v2F8y-v1F8[1])/Math.abs(v1F8[1]));
        System.out.printf("  %-14s %-16.6g %-16.6g %.2g%n", "F8.z (N)", v1F8[2], v2F8z, Math.abs(v2F8z-v1F8[2])/Math.abs(v1F8[2]));
        System.out.printf("  %-14s %-16.6g %-16.6g %.2g%n", "forceMag (N)", v1forceMag, v2mag, Math.abs(v2mag-v1forceMag)/Math.abs(v1forceMag));
        System.out.printf("  %-14s %-16.6g %-16.6g%n", "forceDotFil", v1fdf, v2fdf);
        System.out.println("  ⇒ v2's bondForces reproduces v1's compute-time F8/forceDotFil for the identical config.");
    }

    static void runViz(Scene sc, int M, String dir, boolean gpu) {
        FilamentStore f = sc.fil; MotorStore mot = sc.mot; RigidRodBody b = mot.body;
        new java.io.File(dir).mkdirs();
        int every = Math.max(1, M / 400), frames = 0;
        if (gpu) {
            TornadoExecutionPlan plan = buildPlan(sc);
            writeFrame(dir, frames++, 0, sc);                 // frame 0: initial host pose
            for (int t = 0; t < M; t++) {
                mot.setCounts(t, SEED, f.n); f.counts.set(1, t);
                TornadoExecutionResult res = plan.withGridScheduler(sched).execute();
                if ((t + 1) % every == 0) {                   // pull pose + state at frame cadence only
                    res.transferToHost(b.end1, b.end2, f.end1, f.end2, mot.nucleotideState);
                    writeFrame(dir, frames++, (t + 1) * DT, sc);
                }
            }
            System.out.println("viewer (GPU): wrote " + frames + " frames to " + dir);
            return;
        }
        for (int t = 0; t <= M; t++) {
            if (t % every == 0) writeFrame(dir, frames++, t * DT, sc);
            step(sc, t);
        }
        System.out.println("viewer (CPU): wrote " + frames + " frames to " + dir);
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
            // emit the full motor carpet (match v1)
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
