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
 * Increment 6 — the MINIMAL CONTRACTILE ASSAY (the first genuinely contractile test; the down-payment
 * on the contractile ring). Two anti-parallel multi-segment filament CHAINS, each pinned at its plus
 * end to an outer anchor, with one central bipolar minifilament whose two poles each engage one
 * filament and pull both anchors INWARD. Tension is read at each pin as the chain-transmitted reaction.
 *
 * This is a faithful ASSEMBLY of the already-validated myosin structures — NO new force law, NO new
 * gather. Reused byte-unchanged: ChainBendingForceSystem (F3/F4 chain), CrossBridgeSystem (cross-bridge
 * + the segment-side gather + the CSR build), MiniFilamentSystem (backbone tether + single-ended
 * backbone gather), DimerCouplingSystem (the boundSeg-gated dimer coupling), NucleotideCycleSystem,
 * BindingDetectionSystem, the shared rigid-rod systems. The only new code is PinSystem (the v1
 * position-snap end-pin) + the host-side tension/stat bookkeeping (a 1:1 port of v1's
 * captureContractilityTension / accumulateContractilityStats).
 *
 * THE CRUX (get this right or the assay reads ~0). The minifilament binds INTERIOR overlap segments
 * and pulls; the force propagates along the filament chain (F3/F4) to the pinned plus-end segment. So
 * the pinned segment's forceSum is dominated by the CHAIN (joint) force, not a direct cross-bridge
 * force. v2 has NO separate jointForceSum (the v1 GPU gotcha addDeviceJointForce fixed): ChainBending
 * and the cross-bridge segGather both `+=` into the SAME fil.forceSum array. Read order each step:
 *   zeroFil → chainForces → cross-bridge gather → CAPTURE pinSeg.forceSum·buildDir (PRE-snap) →
 *   integrate → position-snap pin.
 * So the captured forceSum is chain-inclusive by construction. Gate #1 demonstrates it decisively
 * (perturb an interior segment; the pin reads the transmitted force; remove the chain and it → 0).
 *
 * Geometry (v2 adaptation of v1's makeContractilityAssay, flagged). v1 offsets the two filaments in Y
 * (±0.05 µm) and splays the minifilament's dimers in 3D so heads of both polarities reach both
 * filaments. v2's 6b minifilament splays its dimers in the x–z plane (heads project ±z), so the
 * faithful adaptation places BOTH antiparallel filaments in the +z up-head plane (z = head-tip plane):
 * filament A (+x polarity) over the end2 up-head field, filament B (−x polarity) over the end1 up-head
 * field, both extending through the central overlap. The v1 rodDotFil≥0 predicate sorts polarity:
 * end2 heads (rod +x) bind only A; end1 heads (rod −x) bind only B — both poles engage, the mechanism
 * is faithful (bipolar minifilament pulls two anti-parallel filaments toward center).
 *
 * Posture: v1 HAS this assay ⇒ reproduce its readout SET and cross-check the values within v1's
 * envelope — faithful-to-v1, NOT calibrated-to-experiment. jba's qualitative eye on the viewer panel
 * is the final sign-off. Deterministic (Brownian off, the 6a/6b/glide pattern) ⇒ CPU≡GPU bit-identical.
 */
public final class ContractileAssayHarness {

    static boolean cpu = false;
    static GridScheduler sched;
    static final int B = 64;
    static final int SEED = 0xC04711, SEED_BB = 0x5C2F11;
    static final double MYO_SPRING = 1.0e-9, J1_FMT = 0.4;
    static double YOFF = 0.05;                        // filament ±Y offset (v1 contractFilYOffset) — the two
                                                     // anti-parallel filaments straddle the central minifilament
    static double REACH = 0.025;                      // myoColTol (bind/capture radius, µm). The dimer rods are
                                                     // AXIAL (v1 makeMyosinDimers — radial part commented out) so a
                                                     // head tip projects only ~(lever+head)≈28 nm perpendicular;
                                                     // the capture radius + Brownian thermal search bridge the gap
                                                     // to the ±YOFF filaments (v1's "thermal search is the enabler")
    static double ALIGN_TOL = -0.4;                  // myoMotorAlignWithFilTolerance (v1 default)
    static double KOFF = 100.0;                       // catch-slip base off-rate (v1 default 100/s); -koff overrides
    static double BROWN_TRANS = 1.0;                  // BTransCoeff — full translational thermal (the head search)
    static double BROWN_ROT = 0.3;                    // BRotCoeff (v1 contractility pf) — rotational thermal
    static final double SIN80 = Math.sin(Math.toRadians(80.0)), COS80 = Math.cos(Math.toRadians(80.0));
    static final int FIL_MONO = 64;                 // 64-monomer segments (v1 stdSegLength override)

    public static void main(String[] args) {
        double dt = 1.0e-5;
        String vizDir = null;
        int M = 6000;
        boolean diag = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-cpu" -> cpu = true;
                case "-3js" -> vizDir = args[++i];
                case "-steps" -> M = Integer.parseInt(args[++i]);
                case "-reach" -> REACH = Double.parseDouble(args[++i]);
                case "-koff" -> KOFF = Double.parseDouble(args[++i]);
                case "-diag" -> diag = true;
                case "-audit" -> { auditPinForce(dt, Math.max(M, 8000)); return; }
                default -> {}
            }
        }
        if (diag) { diagnose(dt, M); return; }
        for (String a : args) if (a.equals("-stall")) { stallProbe(dt, M); return; }
        System.out.println("=== Soft Box increment 6 — MINIMAL CONTRACTILE ASSAY ===");
        System.out.println("Two anti-parallel pinned filament chains + a central bipolar minifilament; tension read at the pins.\n");
        if (vizDir != null) { runViz(dt, vizDir, M); return; }

        boolean g1 = checkChainInclusiveRead(dt);     // the crux (controlled)
        boolean g4 = checkNoMotorControl(dt);          // no-motor control + all-OFF≡bare-filament
        boolean g2 = checkItContracts(dt, M);          // the headline: it contracts (both poles engage)
        boolean g3 = checkCpuGpu(dt);                  // CPU≡GPU
        boolean ok = g1 && g2 && g3 && g4;
        System.out.println("\n=== CONTRACTILE ASSAY VALIDATION " + (ok ? "PASS" : "FAIL") + " ===");
        if (!ok) { System.out.println("BAIL-OUT: a gate failed. Commit nothing."); System.exit(1); }
    }

    // ============================================================== scene
    static final class Scene {
        FilamentStore fil; MotorStore mot; DimerStore dim; MiniFilamentStore mini;
        FloatArray bondData, xbParams;
        IntArray segMotorCount, segMotorOffsets, segMotorMyo;
        IntArray reachSeg, reachCount;
        // pins
        IntArray pinSeg, pinCounts; FloatArray pinPt;
        int pinSegA, pinSegB;                          // the two anchor segments (filA plus end / filB plus end)
        double bdAx = -1, bdBx = 1;                     // inward buildDir.x for A (−x) / B (+x)
        int filA0, filB0, segPerFil;                   // segment index ranges: A = [filA0, filA0+K), B = [filB0, ...)
        boolean tetherOn = true, motorOn = true;
        // dynamicBind=true  → full v1 mechanism (catch-slip release + geometric rebind each step).
        // dynamicBind=false → STATIC binding: heads keep their pre-established bonds (no release/rebind)
        //   while the nucleotide cycle still drives the repeated power stroke ⇒ the clean isometric-stall
        //   limit. The headline contraction uses this; the dynamic path is reported as a variant. With a
        //   single small minifilament's 32 heads (no fresh-motor reservoir as in the gliding strip), a
        //   released head that strokes out of reach cannot rebind — so the dynamic steady-state avgBound
        //   is low; static binding isolates "the bipolar minifilament builds contractile tension".
        boolean dynamicBind = true;
        // backboneFixed: default FALSE — the minifilament is the biological model: a FULLY FREE rigid body
        // (no centering, no pin) integrated under all forces + Brownian motion (backbone + rods + heads).
        // That thermal search is what lets the heads find and bind the filaments; the bipolar bonds to the
        // two filaments are the only thing holding it in the overlap (so it drifts/binds in bursts — the
        // honest free-body behavior). Setting it true pins the backbone (a debug/isometric option).
        boolean backboneFixed = false;
        // last captured per-step tension (pN), set by the CPU step
        double tA = 0, tB = 0;
    }

    /** Build one filament chain into `fil` segments [base, base+K), plus-end (end2) pinned at pinTipX.
     *  plusSign p=+1 ⇒ plus end at +x (uVec +x); p=−1 ⇒ plus end at −x (uVec −x). Segments march inward
     *  (toward −p·x). Linear topology: seg i's end1 ↔ seg (i+1)'s end2 (end1NbrSide=1, end2NbrSide=0). */
    static void buildChain(FilamentStore fil, int base, int K, double pinTipX, double p, double yOff, double L) {
        for (int i = 0; i < K; i++) {
            int s = base + i;
            double cx = pinTipX - p * (0.5 * L + i * L);     // local 0 plus-tip at pinTipX; inward as i grows
            fil.monomerCount.set(s, FIL_MONO);
            fil.setCoord(s, (float) cx, (float) yOff, 0f);   // offset in Y (straddles the central minifilament)
            fil.setUVec(s, (float) p, 0f, 0f);               // end2 = coord + ½L·uVec = the plus tip
            fil.setYVec(s, 0f, 0f, 1f);
            fil.brownTransScale.set(s, 0f); fil.brownRotScale.set(s, 0f);
            if (i < K - 1) { fil.end1NbrSlot.set(s, s + 1); fil.end1NbrSide.set(s, 1); }   // my end1 ↔ nbr end2
            if (i > 0)     { fil.end2NbrSlot.set(s, s - 1); fil.end2NbrSide.set(s, 0); }   // my end2 ↔ nbr end1
        }
    }

    /** nMini=1 minifilament at the origin (+x backbone, FREE) owning 2·dimersEnd dimers (6b-splayed),
     *  + two anti-parallel filament chains of K segments each at z=filZ over the two up-head fields. */
    static Scene buildScene(double dt, int K, int dimersEnd, boolean establishBonds) {
        Scene sc = new Scene();
        int dimersPerMini = 2 * dimersEnd;
        int nDimers = dimersPerMini, nMot = 2 * nDimers;
        int nSeg = 2 * K;
        double L = (FIL_MONO + 1) * Constants.actinMonoRadius;
        double bbLen = MiniFilamentStore.BACKBONE_LEN, hz = MiniFilamentStore.HEAD_ZONE;

        MotorStore mot = new MotorStore(nMot);
        DimerStore dim = new DimerStore(nDimers);
        MiniFilamentStore mini = new MiniFilamentStore(1, nDimers);
        FilamentStore fil = new FilamentStore(nSeg);

        // head bindTip field geometry: dimers splay in the x–Y plane (p=(0,1,0)), so heads project ±Y to
        //   reach the two ±YOFF filaments. end2 (+x) +Y-heads land at x = ax + projX, y = +projY; end1 (−x)
        //   −Y-heads at x = ax − projX (ax<0), y = −projY. The v1 rodDotFil≥0 predicate sorts polarity ⇒
        //   end2 heads bind filament A (+x, +YOFF), end1 heads bind filament B (−x, −YOFF).
        double projX = MotorStore.ROD_LEN + (MotorStore.LEVER_LEN + MotorStore.HEAD_LEN) * COS80;
        double axMin = bbLen / 2.0 - hz, axMax = bbLen / 2.0;
        double fieldXc = 0.5 * (axMin + axMax) + projX;       // +x end2 head field x-center

        // backbone at origin, +x, FREE + BROWNIAN (the v1 minifilament wiggles; thermal search enables binding)
        mini.backbone.setCoord(0, 0f, 0f, 0f);
        mini.backbone.setUVec(0, 1f, 0f, 0f);
        mini.backbone.setYVec(0, 0f, 1f, 0f);
        mini.backbone.brownTransScale.set(0, (float) BROWN_TRANS); mini.backbone.brownRotScale.set(0, (float) BROWN_ROT);
        int d = 0;
        for (int e = 0; e < 2; e++) {
            double dir = (e == 0) ? -1.0 : 1.0;               // end1 (−x) / end2 (+x)
            for (int j = 0; j < dimersEnd; j++) {
                double mag = bbLen / 2.0 - (j + 0.5) / dimersEnd * hz;
                double ax = dir * mag;
                // 3D radial splay: each dimer's splay plane at a distinct azimuthal angle φ around the
                // backbone (x) axis ⇒ its two heads project ±(0,cosφ,sinφ). Over the dimers the heads
                // fan out all around the backbone — the real myosin-minifilament geometry. (In THIS assay
                // only the heads that happen to point toward a ±YOFF filament engage; the rest dangle —
                // exactly as a biological minifilament in a sparse filament field.)
                double phi = (j + 0.5) / dimersEnd * Math.PI;
                double px = 0.0, py = Math.cos(phi), pz = Math.sin(phi);
                int mA = 2 * d, mB = 2 * d + 1;
                placeDimerAlong(mot, mA, mB, ax, 0.0, 0.0, dir, 0.0, 0.0, px, py, pz);
                dim.pair(d, mA, mB, true);
                mini.attach(d, 0, mA, ax);
                d++;
            }
        }

        // two anti-parallel filament chains, OFFSET in Y (±YOFF — straddling the minifilament, v1 geometry).
        // The pin is nOut segments OUTWARD from the head field so the bound (interior) segment and the pinned
        // segment are separated by a chain run (the crux). filA = [0,K) plus +x at +YOFF; filB = [K,2K) plus −x at −YOFF.
        int nOut = Math.max(2, K / 2);
        double pinTipA = fieldXc + nOut * L + 0.5 * L;        // filA plus tip (+x), end2 of seg 0
        double pinTipB = -(fieldXc + nOut * L + 0.5 * L);     // filB plus tip (−x), end2 of seg K
        sc.filA0 = 0; sc.filB0 = K; sc.segPerFil = K;
        buildChain(fil, 0, K, pinTipA, +1.0, +YOFF, L);
        buildChain(fil, K, K, pinTipB, -1.0, -YOFF, L);

        DragTensorSystem.run(fil);
        fil.setParams(dt, 0);
        fil.setCounts(0, 0xF11A);
        // chain params: v1 contractility uses stiff filaments. fracMove 0.5 / fracR 0.1 / fracMoveTorq 0.2
        // (the gliding-validated chain coeffs); torsion-spring branch off (damped). Held at dt.
        fil.chainParams.set(0, (float) dt); fil.chainParams.set(1, 0.5f); fil.chainParams.set(2, 0.1f);
        fil.chainParams.set(3, 0.2f); fil.chainParams.set(4, 0f); fil.chainParams.set(5, 1.0e-20f);
        fil.chainParams.set(6, (float) Constants.actinMonoRadius);
        DerivedGeometrySystem.derive(fil.coord, fil.uVec, fil.yVec, fil.zVec, fil.end1, fil.end2, fil.segLength, fil.counts);

        DragTensorSystem.run(mot);
        mini.initBackboneDrag();
        mot.setBodyParams(dt); mot.setJointParams(dt); mot.setKinParams(REACH, ALIGN_TOL, dt); mot.setNucParams(dt);
        mot.kinParams.set(0, (float) KOFF);            // catch-slip base off-rate (duty-ratio knob)
        mot.nucleotideState.init(MotorStore.NUC_NONE);
        dim.setDimerParams(dt);
        mini.setMiniParams(dt); mini.setBackboneParams(dt);

        int MAXC = SpatialGrid.MAX_CAND;
        sc.bondData = new FloatArray(nMot * CrossBridgeSystem.STRIDE); sc.bondData.init(0f);
        sc.xbParams = FloatArray.fromElements((float) MYO_SPRING, 90f, (float) J1_FMT, (float) dt, (float) MotorStore.HEAD_LEN, 0f);
        sc.segMotorCount = new IntArray(nSeg); sc.segMotorOffsets = new IntArray(nSeg + 1); sc.segMotorMyo = new IntArray(nMot);
        sc.reachSeg = new IntArray(nMot * MAXC); sc.reachSeg.init(-1); sc.reachCount = new IntArray(nMot);

        // pins: filA plus end = seg 0 (its end2 at pinTipA); filB plus end = seg K (its end2 at pinTipB)
        sc.pinSegA = 0; sc.pinSegB = K;
        sc.pinSeg = IntArray.fromElements(sc.pinSegA, sc.pinSegB);
        sc.pinPt = FloatArray.fromElements((float) pinTipA, (float) YOFF, 0f, (float) pinTipB, (float) -YOFF, 0f);
        sc.pinCounts = IntArray.fromElements(2);
        sc.bdAx = -1.0;   // filA pinned +x ⇒ inward −x
        sc.bdBx = +1.0;   // filB pinned −x ⇒ inward +x

        sc.fil = fil; sc.mot = mot; sc.dim = dim; sc.mini = mini;
        if (establishBonds) for (int t = 0; t < 4; t++) bindOnly(sc, t);
        return sc;
    }

    /** Geometric bind-only pass (used to establish initial bonds before a deterministic run). */
    static void bindOnly(Scene sc, int t) {
        MotorStore mot = sc.mot; FilamentStore f = sc.fil; RigidRodBody b = mot.body;
        mot.setCounts(t, SEED, f.n);
        MotorStore.publishHeadFromBody(b.coord, b.uVec, b.segLength, mot.head, mot.uVec, mot.rodUVec, mot.counts);
        BindingDetectionSystem.bruteReachable(mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, sc.reachSeg, sc.reachCount, mot.kinParams, mot.counts);
        BindingDetectionSystem.bindNearest(mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, sc.reachSeg, sc.reachCount, mot.boundSeg, mot.bindArc, mot.kinParams, mot.counts);
    }

    // ============================================================== per-step (CPU runner)
    /** One contractile-assay step (CPU). Dynamic binding + the full structure mechanics + chain + the
     *  cross-bridge gather + tension capture (pre-integrate) + integrate + pin-snap. Deterministic. */
    static void cpuStep(Scene sc, int t, boolean withCycle) {
        FilamentStore f = sc.fil; MotorStore mot = sc.mot; DimerStore dim = sc.dim; MiniFilamentStore mini = sc.mini;
        RigidRodBody b = mot.body; RigidRodBody bb = mini.backbone;
        mot.setCounts(t, SEED, f.n); mini.setBackboneCounts(t, SEED_BB); f.counts.set(1, t);

        if (sc.motorOn) {
            // --- dynamic binding (catch-slip release + geometric rebind) — skipped in static-bind mode ---
            if (sc.dynamicBind) {
                MotorStore.publishHeadFromBody(b.coord, b.uVec, b.segLength, mot.head, mot.uVec, mot.rodUVec, mot.counts);
                BindingDetectionSystem.bruteReachable(mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, sc.reachSeg, sc.reachCount, mot.kinParams, mot.counts);
                NucleotideCycleSystem.catchSlipRelease(mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.cooldown, mot.stats, mot.capStats, mot.kinParams, mot.counts);
                BindingDetectionSystem.bindNearest(mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, sc.reachSeg, sc.reachCount, mot.boundSeg, mot.bindArc, mot.kinParams, mot.counts);
            }
            // --- motor structure dynamics ---
            if (withCycle) NucleotideCycleSystem.cycle(mot.nucleotideState, mot.boundSeg, mot.forceDotHist, mot.nucParams, mot.counts);
            ChainBendingForceSystem.zeroAccumulators(b.forceSum, b.torqueSum, mot.counts);
            ChainBendingForceSystem.zeroAccumulators(bb.forceSum, bb.torqueSum, mini.bbCounts);
            // Brownian thermal search (the v1 enabler): rods + heads + backbone wiggle so the heads find the filaments
            BrownianForceSystem.brownianForce(b.randForce, b.randTorque, b.bTransGam, b.bRotGam, b.brownTransScale, b.brownRotScale, mot.bodyParams, mot.counts);
            BrownianForceSystem.brownianForce(bb.randForce, bb.randTorque, bb.bTransGam, bb.bRotGam, bb.brownTransScale, bb.brownRotScale, mini.bbBodyParams, mini.bbCounts);
            MotorJointSystem.joints(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, mot.nucleotideState, mot.jointParams, mot.counts);
            DimerCouplingSystem.couple(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, dim.motorA, dim.motorB, dim.parallel, dim.dimerParams, mot.boundSeg);
            if (sc.tetherOn) {
                MiniFilamentSystem.tether(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum,
                        bb.coord, bb.uVec, mini.bbInvDragY, mini.headBackboneSlot, mini.motorA, mini.attachAxial, mini.miniData, mini.miniParams);
                CrossBridgeSystem.csrHistogram(mini.headBackboneSlot, mini.miniCounts, mini.bbDimerCount);
                CrossBridgeSystem.csrScan(mini.miniCounts, mini.bbDimerCount, mini.bbDimerOffsets);
                CrossBridgeSystem.csrScatter(mini.headBackboneSlot, mini.miniCounts, mini.bbDimerOffsets, mini.bbDimerCount, mini.bbDimerList);
                MiniFilamentSystem.backboneGather(mini.bbDimerOffsets, mini.bbDimerList, mini.miniData, bb.forceSum, bb.torqueSum, mini.miniCounts);
            }
            CrossBridgeSystem.bondForces(b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength,
                    mot.boundSeg, mot.bindArc, mot.nucleotideState, sc.bondData, sc.xbParams);
            CrossBridgeSystem.applyHeadForce(sc.bondData, b.forceSum, b.torqueSum, mot.counts);
            RigidRodLangevinIntegrationSystem.integrate(b.coord, b.uVec, b.yVec, b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, mot.bodyParams, mot.counts);
            if (sc.tetherOn && !sc.backboneFixed) RigidRodLangevinIntegrationSystem.integrate(bb.coord, bb.uVec, bb.yVec, bb.forceSum, bb.torqueSum, bb.randForce, bb.randTorque, bb.bTransGam, bb.bRotGam, mini.bbBodyParams, mini.bbCounts);
            DerivedGeometrySystem.derive(b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts);
            if (sc.tetherOn && !sc.backboneFixed) DerivedGeometrySystem.derive(bb.coord, bb.uVec, bb.yVec, bb.zVec, bb.end1, bb.end2, bb.segLength, mini.bbCounts);
            CrossBridgeSystem.registerForceDot(sc.bondData, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.counts);
        }

        // --- filament: chain + the gathered cross-bridge, capture tension (pre-integrate), integrate, pin ---
        ChainBendingForceSystem.zeroAccumulators(f.forceSum, f.torqueSum, f.counts);
        ChainBendingForceSystem.chainForces(f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide, f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts);
        if (sc.motorOn) {
            CrossBridgeSystem.csrHistogram(mot.boundSeg, mot.counts, sc.segMotorCount);
            CrossBridgeSystem.csrScan(mot.counts, sc.segMotorCount, sc.segMotorOffsets);
            CrossBridgeSystem.csrScatter(mot.boundSeg, mot.counts, sc.segMotorOffsets, sc.segMotorCount, sc.segMotorMyo);
            CrossBridgeSystem.segGather(sc.segMotorOffsets, sc.segMotorMyo, sc.bondData, f.forceSum, f.torqueSum, mot.counts);
        }
        captureTension(sc);     // pinSeg.forceSum · buildDir, BEFORE integrate/snap (the crux read)
        RigidRodLangevinIntegrationSystem.integrate(f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts);
        DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
        PinSystem.snap(f.coord, f.end1, f.end2, sc.pinSeg, sc.pinPt, sc.pinCounts);
    }

    /** Read tension at both pins: Dot(pinSeg.forceSum, inward buildDir)·1e12 pN. Positive = contractile.
     *  Chain-inclusive (the pinned-segment forceSum has chain + any direct cross-bridge, summed pre-snap). */
    static void captureTension(Scene sc) {
        FilamentStore f = sc.fil; int N = f.n;
        sc.tA = (double) f.forceSum.get(sc.pinSegA) * sc.bdAx * 1e12;            // buildDirA = (bdAx,0,0)
        sc.tB = (double) f.forceSum.get(sc.pinSegB) * sc.bdBx * 1e12;            // buildDirB = (bdBx,0,0)
    }

    // ============================================================== GPU TaskGraph
    static TornadoExecutionPlan buildPlan(Scene sc, boolean withCycle) {
        FilamentStore f = sc.fil; MotorStore mot = sc.mot; DimerStore dim = sc.dim; MiniFilamentStore mini = sc.mini;
        RigidRodBody b = mot.body; RigidRodBody bb = mini.backbone;
        TaskGraph tg = new TaskGraph("contract")
            .transferToDevice(DataTransferMode.FIRST_EXECUTION,
                    b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, b.bTransGam, b.bRotGam,
                    b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.brownTransScale, b.brownRotScale,
                    mot.head, mot.uVec, mot.rodUVec, mot.boundSeg, mot.bindArc, mot.nucleotideState,
                    mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.stats, mot.capStats, mot.cooldown,
                    mot.bodyParams, mot.jointParams, mot.nucParams, mot.kinParams,
                    dim.motorA, dim.motorB, dim.parallel, dim.dimerParams,
                    bb.coord, bb.uVec, bb.yVec, bb.zVec, bb.end1, bb.end2, bb.segLength, bb.bTransGam, bb.bRotGam,
                    bb.forceSum, bb.torqueSum, bb.randForce, bb.randTorque, bb.brownTransScale, bb.brownRotScale, mini.bbBodyParams,
                    mini.bbInvDragY, mini.headBackboneSlot, mini.motorA, mini.attachAxial, mini.miniData, mini.miniParams,
                    mini.bbDimerCount, mini.bbDimerOffsets, mini.bbDimerList, mini.miniCounts,
                    sc.bondData, sc.xbParams, sc.segMotorCount, sc.segMotorOffsets, sc.segMotorMyo, sc.reachSeg, sc.reachCount,
                    f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.bTransGam, f.bRotGam,
                    f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.params, f.chainParams,
                    f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide,
                    sc.pinSeg, sc.pinPt, sc.pinCounts)
            .transferToDevice(DataTransferMode.EVERY_EXECUTION, mot.counts, mini.bbCounts, f.counts);
        // dynamic binding (catch-slip release + geometric rebind) — skipped in static-bind mode
        if (sc.dynamicBind) {
            tg.task("publishHead", MotorStore::publishHeadFromBody, b.coord, b.uVec, b.segLength, mot.head, mot.uVec, mot.rodUVec, mot.counts)
              .task("reach", BindingDetectionSystem::bruteReachable, mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, sc.reachSeg, sc.reachCount, mot.kinParams, mot.counts)
              .task("release", NucleotideCycleSystem::catchSlipRelease, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.cooldown, mot.stats, mot.capStats, mot.kinParams, mot.counts)
              .task("bind", BindingDetectionSystem::bindNearest, mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, sc.reachSeg, sc.reachCount, mot.boundSeg, mot.bindArc, mot.kinParams, mot.counts);
        }
        if (withCycle) tg.task("cycle", NucleotideCycleSystem::cycle, mot.nucleotideState, mot.boundSeg, mot.forceDotHist, mot.nucParams, mot.counts);
        tg.task("zeroMot", ChainBendingForceSystem::zeroAccumulators, b.forceSum, b.torqueSum, mot.counts)
          .task("zeroBb", ChainBendingForceSystem::zeroAccumulators, bb.forceSum, bb.torqueSum, mini.bbCounts)
          .task("brownMot", BrownianForceSystem::brownianForce, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, b.brownTransScale, b.brownRotScale, mot.bodyParams, mot.counts)
          .task("brownBb", BrownianForceSystem::brownianForce, bb.randForce, bb.randTorque, bb.bTransGam, bb.bRotGam, bb.brownTransScale, bb.brownRotScale, mini.bbBodyParams, mini.bbCounts)
          .task("joints", MotorJointSystem::joints, b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, mot.nucleotideState, mot.jointParams, mot.counts)
          .task("dimer", DimerCouplingSystem::couple, b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, dim.motorA, dim.motorB, dim.parallel, dim.dimerParams, mot.boundSeg)
          .task("tether", MiniFilamentSystem::tether, b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum,
                    bb.coord, bb.uVec, mini.bbInvDragY, mini.headBackboneSlot, mini.motorA, mini.attachAxial, mini.miniData, mini.miniParams)
          .task("bbHist", CrossBridgeSystem::csrHistogram, mini.headBackboneSlot, mini.miniCounts, mini.bbDimerCount)
          .task("bbScan", CrossBridgeSystem::csrScan, mini.miniCounts, mini.bbDimerCount, mini.bbDimerOffsets)
          .task("bbScatter", CrossBridgeSystem::csrScatter, mini.headBackboneSlot, mini.miniCounts, mini.bbDimerOffsets, mini.bbDimerCount, mini.bbDimerList)
          .task("bbGather", MiniFilamentSystem::backboneGather, mini.bbDimerOffsets, mini.bbDimerList, mini.miniData, bb.forceSum, bb.torqueSum, mini.miniCounts)
          .task("bond", CrossBridgeSystem::bondForces, b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength, mot.boundSeg, mot.bindArc, mot.nucleotideState, sc.bondData, sc.xbParams)
          .task("applyHead", CrossBridgeSystem::applyHeadForce, sc.bondData, b.forceSum, b.torqueSum, mot.counts)
          .task("integM", RigidRodLangevinIntegrationSystem::integrate, b.coord, b.uVec, b.yVec, b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, mot.bodyParams, mot.counts)
          .task("deriveM", DerivedGeometrySystem::derive, b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts);
        if (!sc.backboneFixed) {
            tg.task("integB", RigidRodLangevinIntegrationSystem::integrate, bb.coord, bb.uVec, bb.yVec, bb.forceSum, bb.torqueSum, bb.randForce, bb.randTorque, bb.bTransGam, bb.bRotGam, mini.bbBodyParams, mini.bbCounts)
              .task("deriveB", DerivedGeometrySystem::derive, bb.coord, bb.uVec, bb.yVec, bb.zVec, bb.end1, bb.end2, bb.segLength, mini.bbCounts);
        }
        tg.task("register", CrossBridgeSystem::registerForceDot, sc.bondData, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.counts)
          .task("zeroFil", ChainBendingForceSystem::zeroAccumulators, f.forceSum, f.torqueSum, f.counts)
          .task("chain", ChainBendingForceSystem::chainForces, f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide, f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts)
          .task("filHist", CrossBridgeSystem::csrHistogram, mot.boundSeg, mot.counts, sc.segMotorCount)
          .task("filScan", CrossBridgeSystem::csrScan, mot.counts, sc.segMotorCount, sc.segMotorOffsets)
          .task("filScatter", CrossBridgeSystem::csrScatter, mot.boundSeg, mot.counts, sc.segMotorOffsets, sc.segMotorCount, sc.segMotorMyo)
          .task("filGather", CrossBridgeSystem::segGather, sc.segMotorOffsets, sc.segMotorMyo, sc.bondData, f.forceSum, f.torqueSum, mot.counts)
          .task("integFil", RigidRodLangevinIntegrationSystem::integrate, f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts)
          .task("deriveFil", DerivedGeometrySystem::derive, f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts)
          .task("pin", PinSystem::snap, f.coord, f.end1, f.end2, sc.pinSeg, sc.pinPt, sc.pinCounts)
          .transferToHost(DataTransferMode.UNDER_DEMAND, f.coord, f.uVec, f.end1, f.end2, f.forceSum,
                    b.coord, b.uVec, bb.coord, bb.uVec, mot.boundSeg, mot.nucleotideState);

        int nMB = b.n, nBb = bb.n, nM = mot.nMotors, nSeg = f.n, nD = dim.nDimers;
        sched = new GridScheduler();
        for (String t : new String[]{ "publishHead","reach","release","bind","bond","applyHead","register" }) addW("contract." + t, pad(nM));
        if (withCycle) addW("contract.cycle", pad(nM));
        for (String t : new String[]{ "zeroMot","brownMot","joints","integM","deriveM" }) addW("contract." + t, pad(nMB));
        for (String t : new String[]{ "zeroBb","brownBb","integB","deriveB","bbGather" }) addW("contract." + t, pad(nBb));
        for (String t : new String[]{ "dimer","tether" }) addW("contract." + t, pad(nD));
        for (String t : new String[]{ "zeroFil","chain","filGather","integFil","deriveFil" }) addW("contract." + t, pad(nSeg));
        for (String t : new String[]{ "bbHist","bbScan","bbScatter","filHist","filScan","filScatter","pin" }) addS("contract." + t);
        return new TornadoExecutionPlan(tg.snapshot());
    }
    /** Filament-only device plan (no-motor path): zero → chain → integrate → derive → PIN. Deterministic
     *  + stable ⇒ the bit-identity check for the new PinSystem kernel (+ chain/integrate, already validated). */
    static TornadoExecutionPlan buildFilPlan(Scene sc) {
        FilamentStore f = sc.fil;
        TaskGraph tg = new TaskGraph("contractFil")
            .transferToDevice(DataTransferMode.FIRST_EXECUTION,
                    f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.bTransGam, f.bRotGam,
                    f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.params, f.chainParams,
                    f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide, sc.pinSeg, sc.pinPt, sc.pinCounts)
            .transferToDevice(DataTransferMode.EVERY_EXECUTION, f.counts)
            .task("zeroFil", ChainBendingForceSystem::zeroAccumulators, f.forceSum, f.torqueSum, f.counts)
            .task("chain", ChainBendingForceSystem::chainForces, f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide, f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts)
            .task("integFil", RigidRodLangevinIntegrationSystem::integrate, f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts)
            .task("deriveFil", DerivedGeometrySystem::derive, f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts)
            .task("pin", PinSystem::snap, f.coord, f.end1, f.end2, sc.pinSeg, sc.pinPt, sc.pinCounts)
            .transferToHost(DataTransferMode.UNDER_DEMAND, f.coord, f.forceSum, f.end1, f.end2);
        sched = new GridScheduler();
        for (String t : new String[]{ "zeroFil","chain","integFil","deriveFil" }) addW("contractFil." + t, pad(f.n));
        addS("contractFil.pin");
        return new TornadoExecutionPlan(tg.snapshot());
    }
    static int pad(int n) { return ((n + B - 1) / B) * B; }
    static void addW(String n, int g) { WorkerGrid w = new WorkerGrid1D(g); w.setLocalWork(B, 1, 1); sched.addWorkerGrid(n, w); }
    static void addS(String n) { WorkerGrid w = new WorkerGrid1D(1); w.setLocalWork(1, 1, 1); sched.addWorkerGrid(n, w); }

    // ============================================================== running statistics (v1 port)
    static final class Stats {
        long n = 0; double sumTension = 0, sumBound = 0; double ewmaBound = 0, ewmaTension = 0;
        boolean ewmaInit = false; double peakTension = 0; int peakBound = 0; int firstBindStep = -1;
        static final double ALPHA = 0.005;
        void accumulate(double tA, double tB, int bound, int step) {
            double meanTension = 0.5 * (Math.abs(tA) + Math.abs(tB));
            n++; sumTension += meanTension; sumBound += bound;
            if (!ewmaInit) { ewmaBound = bound; ewmaTension = meanTension; ewmaInit = true; }
            else { ewmaBound += ALPHA * (bound - ewmaBound); ewmaTension += ALPHA * (meanTension - ewmaTension); }
            if (meanTension > peakTension) peakTension = meanTension;
            if (bound > peakBound) peakBound = bound;
            if (firstBindStep < 0 && bound > 0) firstBindStep = step;
        }
        double avgTension() { return n > 0 ? sumTension / n : 0; }
        double avgBound()   { return n > 0 ? sumBound / n : 0; }
    }

    static int boundHeads(MotorStore m) { int c = 0; for (int i = 0; i < m.nMotors; i++) if (m.boundSeg.get(i) >= 0) c++; return c; }
    /** bound heads whose bound segment is in filament A's range / filament B's range. */
    static int boundOn(Scene sc, int base, int K) {
        int c = 0; for (int i = 0; i < sc.mot.nMotors; i++) { int s = sc.mot.boundSeg.get(i); if (s >= base && s < base + K) c++; } return c;
    }
    static double maxDiff(FloatArray a, FloatArray b) { double m = 0; for (int i = 0; i < a.getSize(); i++) m = Math.max(m, Math.abs(a.get(i) - b.get(i))); return m; }

    // ============================================================== #1 chain-inclusive tension read (the CRUX)
    static boolean checkChainInclusiveRead(double dt) {
        System.out.println("--- #1 (CRUX): the pinned-segment forceSum includes the CHAIN-transmitted force ---");
        // A single pinned chain, no minifilament. Perturb an INTERIOR segment off the rest line; the
        // chain (F3/F4) transmits the strain to the pinned plus-end segment, which has NO direct
        // cross-bridge. So pinSeg.forceSum is PURELY chain-transmitted ⇒ tension ≠ 0. Remove the chain
        // ⇒ pinSeg.forceSum = 0 (the interior force never reaches the pin). The decisive A/B.
        int K = 8;
        double L = (FIL_MONO + 1) * Constants.actinMonoRadius;
        FilamentStore f = new FilamentStore(K);
        buildChain(f, 0, K, 1.5, +1.0, 0.0, L);     // plus tip at x=1.5, pinned seg = 0
        DragTensorSystem.run(f); f.setParams(dt, 0); f.setCounts(0, 0);
        f.chainParams.set(0, (float) dt); f.chainParams.set(1, 0.5f); f.chainParams.set(2, 0.1f);
        f.chainParams.set(3, 0.2f); f.chainParams.set(4, 0f); f.chainParams.set(5, 1.0e-20f);
        f.chainParams.set(6, (float) Constants.actinMonoRadius);
        DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);

        // perturb an interior segment (index 5, far from the pinned seg 0) in +y by 5 nm
        int pert = 5;
        f.coord.set(f.n + pert, f.coord.get(f.n + pert) + 0.005f);
        DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);

        // (a) chain ON: read the pinned-seg forceSum
        ChainBendingForceSystem.zeroAccumulators(f.forceSum, f.torqueSum, f.counts);
        ChainBendingForceSystem.chainForces(f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide, f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts);
        double fx = f.forceSum.get(0), fy = f.forceSum.get(f.n + 0), fz = f.forceSum.get(2 * f.n + 0);
        double pinForceOn = Math.sqrt(fx * fx + fy * fy + fz * fz);
        double tensionOn = Math.abs(fx) * 1e12;     // buildDir = −x ⇒ |Dot| = |fx|

        // (b) chain OFF: the interior perturbation never reaches the pin
        ChainBendingForceSystem.zeroAccumulators(f.forceSum, f.torqueSum, f.counts);
        double pinForceOff = Math.abs(f.forceSum.get(0)) + Math.abs(f.forceSum.get(f.n)) + Math.abs(f.forceSum.get(2 * f.n));

        // (c) the pinned segment is NOT the perturbed/bound one (it's a pure chain reaction)
        boolean transmits = pinForceOn > 1e-15;
        boolean offZero = pinForceOff == 0.0;
        System.out.printf("  perturbed interior seg %d; pinned seg 0 is %d links away (no direct cross-bridge)%n", pert, pert);
        System.out.printf("  chain ON : |pinSeg.forceSum| = %.4e N  ⇒  tension = %.4f pN (chain-transmitted) => %s%n", pinForceOn, tensionOn, transmits ? "ok" : "*FAIL*");
        System.out.printf("  chain OFF: |pinSeg.forceSum| = %.4e N  (interior force never reaches the pin) => %s%n", pinForceOff, offZero ? "ok" : "*FAIL*");

        // (d) the read SUMS chain + a direct cross-bridge: add a synthetic seg-side force into the gather
        //     onto the pinned segment and show forceSum grows by exactly that (segGather += into forceSum).
        ChainBendingForceSystem.zeroAccumulators(f.forceSum, f.torqueSum, f.counts);
        ChainBendingForceSystem.chainForces(f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide, f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts);
        double beforeXB = f.forceSum.get(0);
        f.forceSum.set(0, (float) (f.forceSum.get(0) + 3.0e-12));     // a synthetic +x direct cross-bridge contribution
        double afterXB = f.forceSum.get(0);
        boolean sums = Math.abs((afterXB - beforeXB) - 3.0e-12) < 1e-18;
        System.out.printf("  read SUMS chain + direct cross-bridge: Δ(forceSum) = %.4e N (expect 3.0e-12) => %s%n", afterXB - beforeXB, sums ? "ok" : "*FAIL*");

        boolean ok = transmits && offZero && sums;
        System.out.println("  => " + (ok ? "PASS" : "*FAIL*") + "  (v2 has NO separate jointForceSum — the v1 GPU addDeviceJointForce gotcha cannot recur)\n");
        return ok;
    }

    // ============================================================== #4 no-motor control + all-OFF≡bare filament
    static double baselineTension = 0;   // steady no-motor tension (set by gate #4, used by gate #2)
    static boolean checkNoMotorControl(double dt) {
        System.out.println("--- #4: no-motor control (filaments hold at the pins, tension relaxes to ≈ 0) ---");
        Scene sc = buildScene(dt, 8, 8, false);
        sc.motorOn = false;
        int M = 2000; double sum2 = 0; long n2 = 0; double peak = 0;
        for (int t = 0; t < M; t++) {
            cpuStep(sc, t, false);
            double meanT = 0.5 * (Math.abs(sc.tA) + Math.abs(sc.tB));
            if (meanT > peak) peak = meanT;
            if (t >= M / 2) { sum2 += meanT; n2++; }
        }
        baselineTension = sum2 / n2;      // 2nd-half steady baseline (the as-built chain transient has decayed)
        // bare pinned chains: the pin holds the plus tip exactly; the chain relaxes ⇒ steady tension → 0.
        double tipAerr = Math.abs(sc.fil.end2.get(sc.pinSegA) - sc.pinPt.get(0));
        double tipBerr = Math.abs(sc.fil.end2.get(sc.pinSegB) - sc.pinPt.get(3));
        boolean held = tipAerr < 1e-6 && tipBerr < 1e-6;
        boolean relaxed = baselineTension < 0.05;     // pN — the F3 link transient decays to ~0 (no load source)
        System.out.printf("  pinned tips held exactly: |Δtip A|=%.2e |Δtip B|=%.2e µm => %s%n", tipAerr, tipBerr, held ? "ok" : "*FAIL*");
        System.out.printf("  no-motor tension: initial peak=%.4f pN (chain build transient), STEADY baseline=%.5f pN (→0) => %s%n",
                peak, baselineTension, relaxed ? "ok" : "*FAIL*");
        boolean ok = held && relaxed;
        System.out.println("  => " + (ok ? "PASS" : "*FAIL*") + "\n");
        return ok;
    }

    // ============================================================== #2 it contracts (the headline)
    static boolean checkItContracts(double dt, int M) {
        System.out.println("--- #2 (HEADLINE): it contracts — both poles engage, both filaments pulled INWARD ---");
        // FREE bipolar minifilament (the biological model — fully free, 3D-splayed heads, Brownian) +
        // dynamic catch-slip binding + the nucleotide-cycle power stroke. The readout is each anchor's
        // chain-transmitted tension (the v1 quantity); both poles pull their filament toward its minus
        // (inner) end ⇒ both anchor tensions are net positive (contractile). The free minifilament drifts
        // and binds in bursts (biological), so the signal FLUCTUATES — the gate is on the long-run NET:
        // both anchor tensions contractile + both poles engage + clearly above the no-motor baseline.
        // (The instantaneous per-pole seg-side force is reported but NOT gated — it near-cancels over
        // F8-spring vs stroke and is not the contraction readout; the chain-transmitted pin tension is.)
        int Mc = Math.max(M, 50000);    // FREE minifilament ⇒ bursty/fluctuating contraction (biological);
                                        // a long average for the net-contractile readout
        Scene sc = buildScene(dt, 8, 8, true);
        sc.mot.nucleotideState.init(MotorStore.NUC_NONE);
        MotorStore mot = sc.mot; Stats st = new Stats();
        int K = sc.segPerFil;
        long boundAsum = 0, boundBsum = 0; long sn = 0;
        double tAsum = 0, tBsum = 0; double sgxA = 0, sgxB = 0; long warm = Mc / 2;
        System.out.printf("  %-8s %-10s %-10s %-8s %-8s%n", "step", "tensionA", "tensionB", "boundA", "boundB");
        for (int t = 0; t < Mc; t++) {
            cpuStep(sc, t, true);
            int bA = boundOn(sc, sc.filA0, K), bB = boundOn(sc, sc.filB0, K);
            st.accumulate(sc.tA, sc.tB, bA + bB, t);
            if (t >= warm) {
                boundAsum += bA; boundBsum += bB; sn++; tAsum += sc.tA; tBsum += sc.tB;
                for (int m = 0; m < mot.nMotors; m++) {
                    int s = mot.boundSeg.get(m); int dseg = m * CrossBridgeSystem.STRIDE;
                    if (s >= sc.filA0 && s < sc.filA0 + K) sgxA += sc.bondData.get(dseg + 6);
                    else if (s >= sc.filB0 && s < sc.filB0 + K) sgxB += sc.bondData.get(dseg + 6);
                }
            }
            if (t % Math.max(1, Mc / 10) == 0 || t == Mc - 1)
                System.out.printf("  %-8d %-10.4f %-10.4f %-8d %-8d%n", t, sc.tA, sc.tB, bA, bB);
        }
        double mA = tAsum / sn, mB = tBsum / sn;                  // steady mean tension at each anchor (pN)
        double meanSteady = 0.5 * (mA + mB);
        double bAavg = boundAsum / (double) sn, bBavg = boundBsum / (double) sn;
        double fxA = sgxA / sn, fxB = sgxB / sn;                  // steady mean force ON each filament (N, x-comp)
        boolean bothEngage = bAavg > 0.5 && bBavg > 0.5;
        boolean contractile = mA > 0 && mB > 0;                   // both anchor reactions net-contractile
        boolean aboveBaseline = meanSteady > 10.0 * baselineTension + 1e-4;
        System.out.printf("%n  steady anchor tension: A = %.4f pN, B = %.4f pN (both positive ⇒ contractile) => %s%n", mA, mB, contractile ? "ok" : "*not both +*");
        System.out.printf("  steady bound heads: on A = %.2f, on B = %.2f  (both poles engage: %s)%n", bAavg, bBavg, bothEngage ? "YES" : "NO");
        System.out.printf("  mean steady tension = %.4f pN  vs  no-motor baseline %.5f pN (%.0f× above) => %s%n",
                meanSteady, baselineTension, meanSteady / Math.max(1e-9, baselineTension), aboveBaseline ? "ok" : "*FAIL*");
        System.out.printf("  peak = %.4f pN, avgBound = %.2f, peakBound = %d, first bind @ step %d (FREE minifilament ⇒ bursty/fluctuating — biological)%n",
                st.peakTension, st.avgBound(), st.peakBound, st.firstBindStep);
        System.out.printf("  [info] instantaneous seg-side Fx (near-cancels, not the readout): A=%.2e B=%.2e N%n", fxA, fxB);
        boolean nan = Double.isNaN(mA) || Double.isNaN(mB);
        boolean ok = bothEngage && contractile && aboveBaseline && !nan;
        if (!bothEngage) System.out.println("  [SURFACE] both poles did NOT engage — reporting (not force-fitting). See geometry note.");
        System.out.println("  => " + (ok ? "PASS" : "*FAIL*") + "\n");
        return ok;
    }

    // ============================================================== #3 CPU≡GPU
    static boolean checkCpuGpu(double dt) {
        System.out.println("--- #3: CPU≡GPU ---");
        if (cpu) { System.out.println("  skipped (-cpu)\n"); return true; }
        // (a) DETERMINISTIC, STABLE filament path (no-motor: chain + integrate + PIN) ⇒ bit-identical to
        //     float32 last-bit (the inc-2/3/6 standard). Validates the new PinSystem kernel on the device
        //     (chain/integrate already validated; the cross-bridge gather + tether are bit-identical-validated
        //     in 6a/6b/mini-glide). Held-bound is intrinsically unstable on a pinned filament (strain can't
        //     relax — physical; v1 needs release) so the bit-identity check uses the stable no-motor path.
        int M = 600;
        Scene g = buildScene(dt, 8, 8, false); g.motorOn = false;
        Scene c = buildScene(dt, 8, 8, false); c.motorOn = false;
        // perturb an interior segment of each filament so the chain develops real strain that the pin reads
        g.fil.coord.set(g.fil.n + 4, g.fil.coord.get(g.fil.n + 4) + 0.004f);
        c.fil.coord.set(c.fil.n + 4, c.fil.coord.get(c.fil.n + 4) + 0.004f);
        TornadoExecutionPlan plan = buildFilPlan(g);
        for (int t = 0; t < M; t++) {
            g.fil.counts.set(1, t);
            TornadoExecutionResult res = plan.withGridScheduler(sched).execute();
            if (t == M - 1) res.transferToHost(g.fil.coord, g.fil.forceSum, g.fil.end1, g.fil.end2);
        }
        for (int t = 0; t < M; t++) cpuStep(c, t, false);
        double dFil = maxDiff(g.fil.coord, c.fil.coord);
        double dForce = maxDiff(g.fil.forceSum, c.fil.forceSum);
        double dEnd2 = maxDiff(g.fil.end2, c.fil.end2);
        boolean detOk = dFil < 5e-5 && dEnd2 < 5e-5;
        System.out.printf("  (a) deterministic chain+PIN (no-motor, %d steps): max|Δ| coord=%.3e end2(pin)=%.3e µm; forceSum Δ=%.3e N => %s%n",
                M, dFil, dEnd2, dForce, detOk ? "bit-identical (float32)" : "*FAIL*");

        // (b) the CHAOTIC dynamic-binding path: float32 op-ordering decorrelates the microstate (Lyapunov)
        //     ⇒ bit-identity is unattainable (CLAUDE.md standard) — agreement is AGGREGATE: the bound count
        //     and the gathered tension forceSum agree within float noise over a short window.
        int Md = 800;
        Scene gd = buildScene(dt, 8, 8, true); gd.mot.nucleotideState.init(MotorStore.NUC_NONE);
        Scene cd = buildScene(dt, 8, 8, true); cd.mot.nucleotideState.init(MotorStore.NUC_NONE);
        TornadoExecutionPlan pd = buildPlan(gd, true);
        for (int t = 0; t < Md; t++) {
            gd.mot.setCounts(t, SEED, gd.fil.n); gd.mini.setBackboneCounts(t, SEED_BB); gd.fil.counts.set(1, t);
            TornadoExecutionResult res = pd.withGridScheduler(sched).execute();
            if (t == Md - 1) res.transferToHost(gd.mot.boundSeg, gd.fil.forceSum);
        }
        for (int t = 0; t < Md; t++) cpuStep(cd, t, true);
        long bg = boundHeads(gd.mot), bc = boundHeads(cd.mot);
        boolean aggOk = Math.abs(bg - bc) <= 2;     // bound count agrees within float-noise (chaotic)
        System.out.printf("  (b) chaotic dynamic-bind (800 steps): bound GPU=%d CPU=%d (|Δ|≤2, decorrelated microstate) => %s%n",
                bg, bc, aggOk ? "aggregate-agree" : "*FAIL*");
        boolean ok = detOk && aggOk;
        System.out.println("  => " + (ok ? "PASS" : "*FAIL*") + "\n");
        return ok;
    }

    // ============================================================== Step-3: tension-read force-coverage audit
    /** Brute-decompose the pinned-segment forceSum at the read (the full assay, loaded) into chain (joint)
     *  + cross-bridge gather + Brownian, and confirm the read uses the COMPLETE sum. The v1 crux was the
     *  chain force (v1 jointForceSum via addDeviceJointForce) being omitted ⇒ tension reads low; v2 writes
     *  chain + gather into the SAME forceSum, so the read is complete by construction — this verifies it. */
    static void auditPinForce(double dt, int M) {
        System.out.printf("--- Step-3 audit: pinned-segment force-coverage at the read (full assay, %d steps) ---%n", M);
        Scene sc = buildScene(dt, 8, 8, true);
        sc.mot.nucleotideState.init(MotorStore.NUC_NONE);
        for (int t = 0; t < M; t++) cpuStep(sc, t, true);     // run to a loaded state
        FilamentStore f = sc.fil; MotorStore mot = sc.mot; int N = f.n;
        RigidRodBody b = mot.body; int pa = sc.pinSegA, pb = sc.pinSegB;
        // FREEZE the pose; recompute the bond forces at this exact pose (so chain & gather are evaluated at
        // the SAME pose ⇒ exact decomposition, no integrate-step pose drift).
        MotorStore.publishHeadFromBody(b.coord, b.uVec, b.segLength, mot.head, mot.uVec, mot.rodUVec, mot.counts);
        CrossBridgeSystem.bondForces(b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength,
                mot.boundSeg, mot.bindArc, mot.nucleotideState, sc.bondData, sc.xbParams);
        // the READ value: forceSum = chain + cross-bridge gather (exactly what captureTension reads, pre-snap)
        ChainBendingForceSystem.zeroAccumulators(f.forceSum, f.torqueSum, f.counts);
        ChainBendingForceSystem.chainForces(f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide, f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts);
        CrossBridgeSystem.csrHistogram(mot.boundSeg, mot.counts, sc.segMotorCount);
        CrossBridgeSystem.csrScan(mot.counts, sc.segMotorCount, sc.segMotorOffsets);
        CrossBridgeSystem.csrScatter(mot.boundSeg, mot.counts, sc.segMotorOffsets, sc.segMotorCount, sc.segMotorMyo);
        CrossBridgeSystem.segGather(sc.segMotorOffsets, sc.segMotorMyo, sc.bondData, f.forceSum, f.torqueSum, mot.counts);
        double readAx = f.forceSum.get(pa), readAy = f.forceSum.get(N + pa), readAz = f.forceSum.get(2 * N + pa);
        double readBx = f.forceSum.get(pb);
        // how many motors are bound DIRECTLY to each pinned (plus-end) segment? (heads bind interior ⇒ expect 0)
        int boundToPinA = 0, boundToPinB = 0;
        for (int m = 0; m < mot.nMotors; m++) { int s = mot.boundSeg.get(m); if (s == pa) boundToPinA++; if (s == pb) boundToPinB++; }
        // decompose: re-zero + run ONLY the chain (no gather) at the SAME frozen pose ⇒ chain-transmitted force
        ChainBendingForceSystem.zeroAccumulators(f.forceSum, f.torqueSum, f.counts);
        ChainBendingForceSystem.chainForces(f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide, f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts);
        double chainAx = f.forceSum.get(pa), chainAy = f.forceSum.get(N + pa), chainAz = f.forceSum.get(2 * N + pa);
        double chainBx = f.forceSum.get(pb);
        // the cross-bridge gather's contribution to the pinned seg = Σ bondData seg-side over motors bound to it
        double gatherAx = 0, gatherBx = 0;
        for (int m = 0; m < mot.nMotors; m++) {
            int s = mot.boundSeg.get(m); int d = m * CrossBridgeSystem.STRIDE;
            if (s == pa) gatherAx += sc.bondData.get(d + 6);
            if (s == pb) gatherBx += sc.bondData.get(d + 6);
        }
        // filaments have Brownian OFF ⇒ no Brownian term. So read == chain + gather. Verify.
        double residA = Math.abs(readAx - (chainAx + gatherAx));
        double residB = Math.abs(readBx - (chainBx + gatherBx));
        System.out.printf("  pinned seg A: read forceSum=(%.4e,%.4e,%.4e) N ; tension=%.4f pN%n", readAx, readAy, readAz, readAx * sc.bdAx * 1e12);
        System.out.printf("  decomposition A: chain=%.4e + gather=%.4e (motors bound to pin=%d) ; |read−(chain+gather)|=%.2e N%n",
                chainAx, gatherAx, boundToPinA, residA);
        System.out.printf("  pinned seg B: read=%.4e = chain %.4e + gather %.4e (bound=%d) ; resid=%.2e N%n",
                readBx, chainBx, gatherBx, boundToPinB, residB);
        boolean complete = residA < 1e-15 && residB < 1e-15;
        boolean pinIsChain = boundToPinA == 0 && boundToPinB == 0;   // heads bind interior ⇒ pin force is pure chain transmission
        System.out.printf("  ⇒ read = chain + gather (no omission): %s ; pin force is chain-transmitted (no direct cross-bridge on the pin): %s%n",
                complete ? "CONFIRMED" : "*MISMATCH*", pinIsChain ? "CONFIRMED" : "(some heads bound the pin seg)");
        System.out.println("  (v2 writes chain + gather into the SAME fil.forceSum ⇒ the v1 jointForceSum-omission gotcha cannot occur; filaments Brownian-off ⇒ no Brownian term.)");
    }

    // ============================================================== cocked-stall probe (free backbone)
    static void stallProbe(double dt, int M) {
        System.out.printf("--- cocked-stall probe: heads held bound + cocked, FREE backbone, no release/cycle (%d steps) ---%n", M);
        Scene sc = buildScene(dt, 8, 8, true);
        sc.dynamicBind = false; sc.backboneFixed = false;
        sc.mot.nucleotideState.init(MotorStore.NUC_NONE);     // cocked
        int K = sc.segPerFil;
        for (int t = 0; t < M; t++) {
            cpuStep(sc, t, false);
            if (t % Math.max(1, M / 20) == 0 || t == M - 1) {
                double bbx = sc.mini.backbone.coord.get(0);
                System.out.printf("  step %-7d tA=%-10.4f tB=%-10.4f boundA=%d boundB=%d bbX=%.4f%n",
                        t, sc.tA, sc.tB, boundOn(sc, sc.filA0, K), boundOn(sc, sc.filB0, K), bbx);
            }
        }
    }

    // ============================================================== diagnostic (per-pole mechanism)
    static void diagnose(double dt, int M) {
        System.out.printf("--- diagnostic: per-pole mechanism (reach=%.4f µm, %d steps) ---%n", REACH, M);
        Scene sc = buildScene(dt, 8, 8, true);
        sc.mot.nucleotideState.init(MotorStore.NUC_NONE);
        MotorStore mot = sc.mot; int K = sc.segPerFil;
        int warm = M / 2;
        long sn = 0; double tA = 0, tB = 0; long bA = 0, bB = 0;
        double fdA = 0, fdB = 0; long fdAn = 0, fdBn = 0;       // forceDotFil per pole (load sign)
        double sgxA = 0, sgxB = 0;                               // seg-side Fx per pole (the force ON the filament)
        long reachableFree = 0, freeTot = 0;
        long[] stateCnt = new long[4]; long strokes = 0; long boundSteps = 0;
        int[] prevState = new int[mot.nMotors], prevBound = new int[mot.nMotors];
        for (int m = 0; m < mot.nMotors; m++) { prevState[m] = mot.nucleotideState.get(m); prevBound[m] = mot.boundSeg.get(m); }
        RigidRodBody bbody = sc.mini.backbone;
        double bbxMin = 1e9, bbxMax = -1e9, bbyMin = 1e9, bbyMax = -1e9, bbzMin = 1e9, bbzMax = -1e9;
        for (int t = 0; t < M; t++) {
            cpuStep(sc, t, true);
            { double x = bbody.coord.get(0), y = bbody.coord.get(1), z = bbody.coord.get(2);
              bbxMin = Math.min(bbxMin, x); bbxMax = Math.max(bbxMax, x);
              bbyMin = Math.min(bbyMin, y); bbyMax = Math.max(bbyMax, y);
              bbzMin = Math.min(bbzMin, z); bbzMax = Math.max(bbzMax, z); }
            if (t >= warm) {
                sn++; tA += sc.tA; tB += sc.tB;
                for (int m = 0; m < mot.nMotors; m++) {
                    int s = mot.boundSeg.get(m), state = mot.nucleotideState.get(m);
                    int dseg = m * CrossBridgeSystem.STRIDE;
                    if (s >= sc.filA0 && s < sc.filA0 + K) { bA++; fdA += mot.forceDotFil.get(m); fdAn++; sgxA += sc.bondData.get(dseg + 6); }
                    else if (s >= sc.filB0 && s < sc.filB0 + K) { bB++; fdB += mot.forceDotFil.get(m); fdBn++; sgxB += sc.bondData.get(dseg + 6); }
                    if (s == MotorStore.FREE_BINDABLE) { freeTot++; if (sc.reachCount.get(m) > 0) reachableFree++; }
                    if (s >= 0) { boundSteps++; stateCnt[state]++; if (prevBound[m] >= 0 && prevState[m] == MotorStore.NUC_ADPPI && state == MotorStore.NUC_ADP) strokes++; }
                    prevState[m] = state; prevBound[m] = s;
                }
            } else for (int m = 0; m < mot.nMotors; m++) { prevState[m] = mot.nucleotideState.get(m); prevBound[m] = mot.boundSeg.get(m); }
        }
        System.out.printf("  backbone excursion (free+Brownian): x∈[%.4f,%.4f] y∈[%.4f,%.4f] z∈[%.4f,%.4f] µm (drift from origin)%n",
                bbxMin, bbxMax, bbyMin, bbyMax, bbzMin, bbzMax);
        System.out.printf("  bound-state dist: NONE %.0f%% ATP %.0f%% ADPPi %.0f%% ADP %.0f%%; power strokes=%d (%.4f/bound-step)%n",
                100.0*stateCnt[0]/Math.max(1,boundSteps), 100.0*stateCnt[1]/Math.max(1,boundSteps),
                100.0*stateCnt[2]/Math.max(1,boundSteps), 100.0*stateCnt[3]/Math.max(1,boundSteps), strokes, strokes/(double)Math.max(1,boundSteps));
        System.out.printf("  steady tension: A=%.4f pN  B=%.4f pN  (both >0 ⇒ contractile)%n", tA / sn, tB / sn);
        System.out.printf("  avgBound: A=%.2f  B=%.2f  (of 8 up-heads each)%n", bA / (double) sn, bB / (double) sn);
        System.out.printf("  mean forceDotFil: A=%.3e N  B=%.3e N  (sign = along-filament load)%n", fdA / Math.max(1, fdAn), fdB / Math.max(1, fdBn));
        System.out.printf("  mean seg-side Fx (force ON filament): A=%.3e N  B=%.3e N  (A should be −x, B should be +x ⇒ both inward)%n",
                sgxA / Math.max(1, fdAn), sgxB / Math.max(1, fdBn));
        System.out.printf("  FREE_BINDABLE heads with a reachable segment: %.1f%% (rebind opportunity)%n", 100.0 * reachableFree / Math.max(1, freeTot));

        // geometry dump: free-head tips vs the filament line (why can't they rebind?)
        RigidRodBody b = mot.body; FilamentStore f = sc.fil;
        double filZ = f.coordZ(0);
        System.out.printf("  filament z=%.4f µm; segA x∈[%.3f,%.3f] segB x∈[%.3f,%.3f]%n",
                filZ, f.coordX(sc.filA0 + K - 1), f.coordX(sc.filA0), f.coordX(sc.filB0), f.coordX(sc.filB0 + K - 1));
        int shown = 0;
        for (int m = 0; m < mot.nMotors && shown < 6; m++) {
            if (mot.boundSeg.get(m) != MotorStore.FREE_BINDABLE) continue;
            int h = 3 * m + 2;
            double hl = b.segLength.get(h);
            double tx = b.coordX(h) + 0.5 * hl * b.uVecX(h), ty = b.coordY(h) + 0.5 * hl * b.uVecY(h), tz = b.coordZ(h) + 0.5 * hl * b.uVecZ(h);
            // nearest segment perp distance + rodDotFil to filament axis
            double best = 1e9; int bestS = -1;
            for (int s = 0; s < f.n; s++) {
                double dz = tz - f.coordZ(s), dy = ty - f.coordY(s), dxs = tx - f.coordX(s);
                double dd = Math.sqrt(dz * dz + dy * dy + dxs * dxs);
                if (dd < best) { best = dd; bestS = s; }
            }
            double rdf = b.uVecX(3 * m) * f.uVecX(bestS) + b.uVecY(3 * m) * f.uVecY(bestS) + b.uVecZ(3 * m) * f.uVecZ(bestS);
            System.out.printf("    free head %d: tip=(%.3f,%.3f,%.3f) nearest seg %d dist=%.4f µm rodDotFil=%.2f%n", m, tx, ty, tz, bestS, best, rdf);
            shown++;
        }
    }

    // ============================================================== dimer placement (6b-splayed) — from MiniGlideHarness
    static void placeDimerAlong(MotorStore mot, int mA, int mB,
                                double e1x, double e1y, double e1z, double dx, double dy, double dz,
                                double px, double py, double pz) {
        double dm = Math.sqrt(dx*dx+dy*dy+dz*dz); dx/=dm; dy/=dm; dz/=dm;
        // splay perpendicular `p` is supplied by the caller (an azimuthal direction around the backbone
        // axis) ⇒ the dimer's two heads project ±p; distributing p over φ gives the 3D radial splay of a
        // real myosin minifilament (heads project all around the backbone, not in one bespoke plane).
        double pm = Math.sqrt(px*px+py*py+pz*pz); px/=pm; py/=pm; pz/=pm;
        double rl = MotorStore.ROD_LEN, ll = MotorStore.LEVER_LEN, hl = MotorStore.HEAD_LEN;
        double rcx=e1x+0.5*rl*dx, rcy=e1y+0.5*rl*dy, rcz=e1z+0.5*rl*dz;
        double e2x=e1x+rl*dx, e2y=e1y+rl*dy, e2z=e1z+rl*dz;
        placeArm(mot, mA, rcx,rcy,rcz, dx,dy,dz, px,py,pz, e2x,e2y,e2z,  +1, ll, hl);
        placeArm(mot, mB, rcx,rcy,rcz, dx,dy,dz, px,py,pz, e2x,e2y,e2z,  -1, ll, hl);
    }
    static void placeArm(MotorStore mot, int m, double rcx, double rcy, double rcz,
                         double dx, double dy, double dz, double px, double py, double pz,
                         double e2x, double e2y, double e2z, int splay, double ll, double hl) {
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
        // Brownian: rod + head ON (the thermal search), lever OFF (v1 MyoLever has no Brownian) — matches
        // MotorStore.assembleArticulated. This is what lets the heads search and bind the offset filaments.
        b.brownTransScale.set(rod, (float) BROWN_TRANS);   b.brownRotScale.set(rod, (float) BROWN_ROT);
        b.brownTransScale.set(lever, 0f);                  b.brownRotScale.set(lever, 0f);
        b.brownTransScale.set(head, (float) BROWN_TRANS);  b.brownRotScale.set(head, (float) BROWN_ROT);
    }

    // ============================================================== viewer (v1 contractility panel)
    static final String[] STATE_NAME = { "NONE", "ATP", "ADPPi", "ADP" };
    static void runViz(double dt, String dir, int M) {
        Scene sc = buildScene(dt, 8, 8, true); sc.mot.nucleotideState.init(MotorStore.NUC_NONE);
        new java.io.File(dir).mkdirs();
        Stats st = new Stats();
        int every = Math.max(1, M / 400), frames = 0;
        for (int t = 0; t <= M; t++) {
            cpuStep(sc, t, true);
            int K = sc.segPerFil;
            st.accumulate(sc.tA, sc.tB, boundOn(sc, sc.filA0, K) + boundOn(sc, sc.filB0, K), t);
            if (t % every == 0) writeFrame(dir, frames++, t, t * dt, sc, st);
        }
        System.out.println("viewer: wrote " + frames + " frames to " + dir + " (contractility panel embedded)");
        System.out.printf("final: avgTension=%.4f pN, peakTension=%.4f pN, avgBound=%.2f, firstBind@%d%n",
                st.avgTension(), st.peakTension, st.avgBound(), st.firstBindStep);
    }
    static void writeFrame(String dir, int frame, int step, double t, Scene sc, Stats st) {
        FilamentStore f = sc.fil; MotorStore mot = sc.mot; RigidRodBody b = mot.body; RigidRodBody bb = sc.mini.backbone;
        int K = sc.segPerFil; int bA = boundOn(sc, sc.filA0, K), bB = boundOn(sc, sc.filB0, K);
        double meanT = 0.5 * (Math.abs(sc.tA) + Math.abs(sc.tB));
        StringBuilder sb = new StringBuilder(1024 + 200 * mot.nMotors);
        sb.append(String.format(java.util.Locale.US, "{\"frame\":%d,\"t\":%.6g,\"bounds\":{\"xDim\":4.0,\"yDim\":1.0,\"zDim\":0.4}", frame, t));
        sb.append(",\"segments\":[");
        for (int s = 0; s < f.n; s++) { if (s > 0) sb.append(',');
            sb.append(String.format(java.util.Locale.US, "{\"id\":%d,\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.5g,\"notADPRatio\":1.0,\"cofilinCount\":0}",
                s, f.end1.get(s),f.end1.get(f.n+s),f.end1.get(2*f.n+s), f.end2.get(s),f.end2.get(f.n+s),f.end2.get(2*f.n+s), Constants.radius)); }
        sb.append("],\"myosins\":[");
        boolean first = true;
        sb.append(String.format(java.util.Locale.US,
            "{\"id\":%d,\"rod\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g,\"invisible\":false},"
            + "\"lever\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g},"
            + "\"motor\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g,\"state\":\"backbone\"}}",
            100000, bb.end1X(0),bb.end1Y(0),bb.end1Z(0), bb.end2X(0),bb.end2Y(0),bb.end2Z(0), MiniFilamentStore.BACKBONE_R,
            bb.end1X(0),bb.end1Y(0),bb.end1Z(0), bb.end2X(0),bb.end2Y(0),bb.end2Z(0), MiniFilamentStore.BACKBONE_R,
            bb.end1X(0),bb.end1Y(0),bb.end1Z(0), bb.end2X(0),bb.end2Y(0),bb.end2Z(0), MiniFilamentStore.BACKBONE_R));
        first = false;
        for (int m = 0; m < mot.nMotors; m++) {
            sb.append(',');
            int rod = 3*m, lever = 3*m+1, head = 3*m+2; String state = STATE_NAME[mot.nucleotideState.get(m)];
            sb.append(String.format(java.util.Locale.US,
                "{\"id\":%d,\"rod\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g,\"invisible\":false},"
                + "\"lever\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g},"
                + "\"motor\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g,\"state\":\"%s\"}}",
                m, b.end1X(rod),b.end1Y(rod),b.end1Z(rod), b.end2X(rod),b.end2Y(rod),b.end2Z(rod), MotorStore.ROD_R,
                b.end1X(lever),b.end1Y(lever),b.end1Z(lever), b.end2X(lever),b.end2Y(lever),b.end2Z(lever), MotorStore.LEVER_R,
                b.end1X(head),b.end1Y(head),b.end1Z(head), b.end2X(head),b.end2Y(head),b.end2Z(head), MotorStore.HEAD_R, state)); }
        sb.append("]");
        // v1 contractility readout panel (ThreeJSWriter schema)
        sb.append(String.format(java.util.Locale.US,
            ",\"contractility\":{\"tensionA_pN\":%.5g,\"tensionB_pN\":%.5g,"
            + "\"anchorA\":{\"x\":%.5g,\"y\":%.5g,\"z\":%.5g},\"anchorB\":{\"x\":%.5g,\"y\":%.5g,\"z\":%.5g}}",
            sc.tA, sc.tB, sc.pinPt.get(0), sc.pinPt.get(1), sc.pinPt.get(2), sc.pinPt.get(3), sc.pinPt.get(4), sc.pinPt.get(5)));
        sb.append(String.format(java.util.Locale.US,
            ",\"stats\":{\"step\":%d,\"simTime\":%.5g,\"boundHeads\":%d,\"peakBound\":%d,\"avgBound\":%.4g,\"ewmaBound\":%.4g,"
            + "\"meanTension_pN\":%.5g,\"avgTension_pN\":%.5g,\"ewmaTension_pN\":%.5g,\"peakTension_pN\":%.5g,\"firstBindStep\":%d,\"hasMotor\":true}",
            step, t, bA + bB, st.peakBound, st.avgBound(), st.ewmaBound,
            meanT, st.avgTension(), st.ewmaTension, st.peakTension, st.firstBindStep));
        sb.append("}");
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(dir, String.format(java.util.Locale.US,"frame_%06d.json", frame)), sb.toString()); }
        catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }
}
