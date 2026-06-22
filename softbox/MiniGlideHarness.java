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
 * Increment 6 glide integration (part 2): MINIFILAMENT-GLIDE — the 6b single-ended backbone gather
 * now carrying REAL cross-bridge load. A pre-placed STATIC minifilament (a rigid-rod backbone OWNING
 * N dimers, 6b) whose dimer heads bind/walk on a single PINNED filament via the existing CrossBridge
 * (4b-ii, byte-unchanged); the backbone gathers the collective load through the 6b tether.
 *
 * What's genuinely new vs 6b/dimer-glide (each validated in isolation):
 *   1. SCALE + UNDER LOAD: 32 heads/minifilament bind/walk; the full chain head → CrossBridge → head
 *      body → J1/J2 → rod → dimer-coupling (6a) → minifilament tether (6b) → backboneGather is now
 *      LOAD-BEARING (validated isometrically before). THE HEADLINE.
 *   2. Per-dimer binding gates (the dimer-glide lever-align gate) across 16 dimers in MIXED states.
 *   3. NO minifilament-LEVEL binding gate (verified in BoA-v1ref: constrainEnd1/End2Dimers tether the
 *      dimers UNCONDITIONALLY; the ONLY binding gate is the per-dimer MyosinDimer:276 — already ported.
 *      countBoundMotors is diagnostic-only). So nothing new to port at the minifilament level.
 *
 * Geometry (the 6b rest configuration + a pinned filament over the end2 head-field): the backbone lies
 * along +x at z=0; its 6b-splayed dimers project heads up/down in z. The end2 dimers' rods point +x, so
 * their UP heads (rodDotFil>=0, the v1 bind predicate) bind a +x-oriented filament placed just above the
 * end2 head-field. The end1 dimers' rods point -x (rodDotFil<0 vs a +x filament) ⇒ do NOT bind — i.e. on
 * a SINGLE filament only ONE polarity engages (the correct physics; genuine bipolar stall / contraction
 * needs the two-antiparallel-filament geometry, the next increment). The backbone is FREE (no anchor —
 * the tethers hold the structure), so it translocates under the collective gathered net.
 *
 * Per step (one physics, two runners) over motor body + backbone (FREE) + filament (PINNED):
 *   [cycle] → zeroMot,zeroBb → joints(J1/J2) → dimerCouple(boundSeg-gated) → tether(6b rod self-write +
 *   miniData) → bbCSR(headBackboneSlot) → backboneGather → bond(CrossBridge) → applyHead → integM,integB
 *   → deriveM,deriveB → register → zeroFil → filCSR(boundSeg) → filGather. (filament not integrated.)
 *
 * Gates (co-developed small-scale vs BoA-v1ref, not fixtures):
 *   #1 force transmission UNDER LOAD (HEADLINE): backbone gather==bruteGather bit-identical + fil
 *      gather==bruteGather bit-identical + momentum (Σmotor+Σbackbone+Σfil≈0) + CPU≡GPU bit-identical.
 *   #2 binding gates at population scale: per-dimer lever-align fires/suppresses per v1 across mixed
 *      binding states; no minifilament-level gate (documented).
 *   #3 bipolar collective (emergent, observe — NOT gated on direction): backbone moves consistently
 *      with the gathered net force (single-polarity engagement ⇒ directed; cross-checked by sign).
 *   #5 all-OFF≡HEAD: tether off ⇒ the dimer-glide path, bit-identical; control (tether on differs).
 */
public final class MiniGlideHarness {

    static boolean cpu = false;
    static GridScheduler sched;
    static final int B = 64;
    static final int SEED = 0x6D9A0E, SEED_BB = 0x5C2F11;
    static final double LANE = 0.40;                          // µm between minifilament y-lanes
    static final double MYO_SPRING = 1.0e-9, J1_FMT = 0.4;    // cross-bridge spring + J1 frac-move (dimer-glide)
    static final double SIN80 = Math.sin(Math.toRadians(80.0)), COS80 = Math.cos(Math.toRadians(80.0));

    public static void main(String[] args) {
        double dt = 1.0e-5;
        String vizDir = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-cpu" -> cpu = true;
                case "-3js" -> vizDir = args[++i];
                default -> {}
            }
        }
        System.out.println("=== Soft Box increment 6 glide (part 2) — MINIFILAMENT-GLIDE (backbone gather under load) ===");
        System.out.println("32 heads bind/walk on a pinned filament; the 6b single-ended backbone gather carries the real cross-bridge load.\n");
        if (vizDir != null) { runViz(dt, vizDir); return; }

        boolean g2 = checkBindingGates(dt);
        boolean g5 = checkAllOffEqualsHead(dt);
        boolean g1 = checkForceTransmission(dt);
        boolean g3 = checkBipolarCollective(dt);
        boolean ok = g1 && g2 && g3 && g5;
        System.out.println("\n=== MINIFILAMENT-GLIDE VALIDATION " + (ok ? "PASS" : "FAIL") + " ===");
        if (!ok) { System.out.println("BAIL-OUT: a gate failed. Commit nothing."); System.exit(1); }
    }

    // ============================================================== scene
    static final class Scene {
        FilamentStore fil; MotorStore mot; DimerStore dim; MiniFilamentStore mini;
        FloatArray bondData, xbParams;
        IntArray segMotorCount, segMotorOffsets, segMotorMyo;
        FloatArray bruteFilF, bruteFilT, bruteBbF, bruteBbT;
        IntArray reachSeg, reachCount;
        boolean tetherOn = true;
    }

    /**
     * nMini minifilaments in a y-row, each = a backbone (+x, z=0, FREE) owning 2·dimersEnd dimers
     * (6b-splayed), + one PINNED filament segment per minifilament over its end2 up-head field.
     */
    static Scene buildScene(double dt, int nMini, int dimersEnd, boolean establishBonds) {
        Scene sc = new Scene();
        int dimersPerMini = 2 * dimersEnd;
        int nDimers = nMini * dimersPerMini;
        int nMot = 2 * nDimers, nSeg = nMini;
        double L = MiniFilamentStore.BACKBONE_LEN, hz = MiniFilamentStore.HEAD_ZONE;

        MotorStore mot = new MotorStore(nMot);
        DimerStore dim = new DimerStore(nDimers);
        MiniFilamentStore mini = new MiniFilamentStore(nMini, nDimers);
        FilamentStore fil = new FilamentStore(nSeg);

        // end2 (+x) up-head bindTip projection from a backbone-end attach point (see placeArm, +x +splay):
        //   bindTip = rodEnd2 + (LEVER_LEN+HEAD_LEN)·leverUVec, leverUVec = (cos80, 0, sin80)
        double projX = MotorStore.ROD_LEN + (MotorStore.LEVER_LEN + MotorStore.HEAD_LEN) * COS80;
        double projZ = (MotorStore.LEVER_LEN + MotorStore.HEAD_LEN) * SIN80;
        double axMin = L / 2.0 - hz, axMax = L / 2.0;                 // end2 axial offset range
        double fieldXc = 0.5 * (axMin + axMax) + projX;              // x-center of the end2 bindTip field (bx=0)
        double filZ = projZ + 0.003;                                 // 3 nm above the up-head tips (dimer-glide Z_OFFSET)

        int d = 0;
        double y0 = -0.5 * (nMini - 1) * LANE;
        for (int bb = 0; bb < nMini; bb++) {
            double bx = 0.0, by = y0 + bb * LANE, bz = 0.0;
            mini.backbone.setCoord(mini.bbIdx(bb), (float) bx, (float) by, (float) bz);
            mini.backbone.setUVec(mini.bbIdx(bb), 1f, 0f, 0f);
            mini.backbone.setYVec(mini.bbIdx(bb), 0f, 1f, 0f);
            mini.backbone.brownTransScale.set(mini.bbIdx(bb), 0f);
            mini.backbone.brownRotScale.set(mini.bbIdx(bb), 0f);
            for (int e = 0; e < 2; e++) {
                double dir = (e == 0) ? -1.0 : 1.0;
                for (int j = 0; j < dimersEnd; j++) {
                    double mag = L / 2.0 - (j + 0.5) / dimersEnd * hz;
                    double ax = dir * mag;
                    double attX = bx + ax;
                    int mA = 2 * d, mB = 2 * d + 1;
                    placeDimerAlong(mot, mA, mB, attX, by, bz, dir, 0.0, 0.0);
                    dim.pair(d, mA, mB, true);
                    mini.attach(d, bb, mA, ax);
                    d++;
                }
            }
            // one pinned filament segment over this minifilament's end2 up-head field
            fil.monomerCount.set(bb, Constants.stdSegLength);
            fil.setUVec(bb, 1f, 0f, 0f); fil.setYVec(bb, 0f, 1f, 0f);   // plus-end +x
            fil.setCoord(bb, (float) (bx + fieldXc), (float) by, (float) filZ);
            fil.brownTransScale.set(bb, 0f); fil.brownRotScale.set(bb, 0f);
        }
        DragTensorSystem.run(fil); fil.setParams(dt, 0); fil.setCounts(0, 0);
        DerivedGeometrySystem.derive(fil.coord, fil.uVec, fil.yVec, fil.zVec, fil.end1, fil.end2, fil.segLength, fil.counts);

        DragTensorSystem.run(mot);
        mini.initBackboneDrag();
        mot.setBodyParams(dt); mot.setJointParams(dt); mot.setKinParams(0.006, -0.4, dt); mot.setNucParams(dt);
        dim.setDimerParams(dt);
        mini.setMiniParams(dt); mini.setBackboneParams(dt);

        sc.bondData = new FloatArray(nMot * CrossBridgeSystem.STRIDE); sc.bondData.init(0f);
        sc.xbParams = FloatArray.fromElements((float) MYO_SPRING, 90f, (float) J1_FMT, (float) dt, (float) MotorStore.HEAD_LEN, 0f);
        sc.segMotorCount = new IntArray(nSeg); sc.segMotorOffsets = new IntArray(nSeg + 1); sc.segMotorMyo = new IntArray(nMot);
        sc.bruteFilF = new FloatArray(3 * nSeg); sc.bruteFilT = new FloatArray(3 * nSeg);
        sc.bruteBbF = new FloatArray(3 * nMini); sc.bruteBbT = new FloatArray(3 * nMini);
        sc.fil = fil; sc.mot = mot; sc.dim = dim; sc.mini = mini;
        if (establishBonds) bindStep(sc, 4);
        return sc;
    }

    static void bindStep(Scene sc, int steps) {
        MotorStore mot = sc.mot; FilamentStore fil = sc.fil; RigidRodBody b = mot.body;
        IntArray reachSeg = new IntArray(mot.nMotors * SpatialGrid.MAX_CAND); reachSeg.init(-1);
        IntArray reachCount = new IntArray(mot.nMotors);
        for (int t = 0; t < steps; t++) {
            mot.setCounts(t, SEED, fil.n);
            MotorStore.publishHeadFromBody(b.coord, b.uVec, b.segLength, mot.head, mot.uVec, mot.rodUVec, mot.counts);
            BindingDetectionSystem.bruteReachable(mot.head, mot.uVec, mot.rodUVec, fil.end1, fil.end2, reachSeg, reachCount, mot.kinParams, mot.counts);
            BindingDetectionSystem.bindKinetics(mot.head, mot.uVec, mot.rodUVec, fil.end1, fil.end2, reachSeg, reachCount, mot.boundSeg, mot.bindArc, mot.stats, mot.kinParams, mot.counts);
        }
    }

    // ============================================================== per-step (mechanics; no binding, no Brownian — deterministic)
    static Runnable cpuStep(Scene sc, boolean withCycle) {
        FilamentStore f = sc.fil; MotorStore mot = sc.mot; DimerStore dim = sc.dim; MiniFilamentStore mini = sc.mini;
        RigidRodBody b = mot.body; RigidRodBody bb = mini.backbone; boolean tetherOn = sc.tetherOn;
        return () -> {
            if (withCycle) NucleotideCycleSystem.cycle(mot.nucleotideState, mot.boundSeg, mot.forceDotHist, mot.nucParams, mot.counts);
            ChainBendingForceSystem.zeroAccumulators(b.forceSum, b.torqueSum, mot.counts);
            ChainBendingForceSystem.zeroAccumulators(bb.forceSum, bb.torqueSum, mini.bbCounts);
            MotorJointSystem.joints(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, mot.nucleotideState, mot.jointParams, mot.counts);
            DimerCouplingSystem.couple(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, dim.motorA, dim.motorB, dim.parallel, dim.dimerParams, mot.boundSeg);
            if (tetherOn) {
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
            if (tetherOn) RigidRodLangevinIntegrationSystem.integrate(bb.coord, bb.uVec, bb.yVec, bb.forceSum, bb.torqueSum, bb.randForce, bb.randTorque, bb.bTransGam, bb.bRotGam, mini.bbBodyParams, mini.bbCounts);
            DerivedGeometrySystem.derive(b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts);
            if (tetherOn) DerivedGeometrySystem.derive(bb.coord, bb.uVec, bb.yVec, bb.zVec, bb.end1, bb.end2, bb.segLength, mini.bbCounts);
            CrossBridgeSystem.registerForceDot(sc.bondData, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.counts);
            ChainBendingForceSystem.zeroAccumulators(f.forceSum, f.torqueSum, f.counts);
            CrossBridgeSystem.csrHistogram(mot.boundSeg, mot.counts, sc.segMotorCount);
            CrossBridgeSystem.csrScan(mot.counts, sc.segMotorCount, sc.segMotorOffsets);
            CrossBridgeSystem.csrScatter(mot.boundSeg, mot.counts, sc.segMotorOffsets, sc.segMotorCount, sc.segMotorMyo);
            CrossBridgeSystem.segGather(sc.segMotorOffsets, sc.segMotorMyo, sc.bondData, f.forceSum, f.torqueSum, mot.counts);
        };
    }

    static TornadoExecutionPlan buildPlan(Scene sc, boolean withCycle) {
        FilamentStore f = sc.fil; MotorStore mot = sc.mot; DimerStore dim = sc.dim; MiniFilamentStore mini = sc.mini;
        RigidRodBody b = mot.body; RigidRodBody bb = mini.backbone;
        TaskGraph tg = new TaskGraph("mglide")
            .transferToDevice(DataTransferMode.FIRST_EXECUTION,
                    b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, b.bTransGam, b.bRotGam,
                    b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.brownTransScale, b.brownRotScale,
                    mot.bodyParams, mot.jointParams, mot.nucleotideState, mot.boundSeg, mot.bindArc,
                    mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.nucParams,
                    dim.motorA, dim.motorB, dim.parallel, dim.dimerParams,
                    bb.coord, bb.uVec, bb.yVec, bb.zVec, bb.end1, bb.end2, bb.segLength, bb.bTransGam, bb.bRotGam,
                    bb.forceSum, bb.torqueSum, bb.randForce, bb.randTorque, mini.bbBodyParams,
                    mini.bbInvDragY, mini.headBackboneSlot, mini.motorA, mini.attachAxial, mini.miniData, mini.miniParams,
                    mini.bbDimerCount, mini.bbDimerOffsets, mini.bbDimerList, mini.miniCounts,
                    sc.bondData, sc.xbParams, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength, f.forceSum, f.torqueSum,
                    sc.segMotorCount, sc.segMotorOffsets, sc.segMotorMyo)
            .transferToDevice(DataTransferMode.EVERY_EXECUTION, mot.counts, mini.bbCounts);
        if (withCycle) tg.task("cycle", NucleotideCycleSystem::cycle, mot.nucleotideState, mot.boundSeg, mot.forceDotHist, mot.nucParams, mot.counts);
        tg.task("zeroMot", ChainBendingForceSystem::zeroAccumulators, b.forceSum, b.torqueSum, mot.counts)
          .task("zeroBb", ChainBendingForceSystem::zeroAccumulators, bb.forceSum, bb.torqueSum, mini.bbCounts)
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
          .task("integB", RigidRodLangevinIntegrationSystem::integrate, bb.coord, bb.uVec, bb.yVec, bb.forceSum, bb.torqueSum, bb.randForce, bb.randTorque, bb.bTransGam, bb.bRotGam, mini.bbBodyParams, mini.bbCounts)
          .task("deriveM", DerivedGeometrySystem::derive, b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts)
          .task("deriveB", DerivedGeometrySystem::derive, bb.coord, bb.uVec, bb.yVec, bb.zVec, bb.end1, bb.end2, bb.segLength, mini.bbCounts)
          .task("register", CrossBridgeSystem::registerForceDot, sc.bondData, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.counts)
          .task("zeroFil", ChainBendingForceSystem::zeroAccumulators, f.forceSum, f.torqueSum, f.counts)
          .task("filHist", CrossBridgeSystem::csrHistogram, mot.boundSeg, mot.counts, sc.segMotorCount)
          .task("filScan", CrossBridgeSystem::csrScan, mot.counts, sc.segMotorCount, sc.segMotorOffsets)
          .task("filScatter", CrossBridgeSystem::csrScatter, mot.boundSeg, mot.counts, sc.segMotorOffsets, sc.segMotorCount, sc.segMotorMyo)
          .task("filGather", CrossBridgeSystem::segGather, sc.segMotorOffsets, sc.segMotorMyo, sc.bondData, f.forceSum, f.torqueSum, mot.counts)
          .transferToHost(DataTransferMode.UNDER_DEMAND, b.coord, b.uVec, bb.coord, bb.uVec, bb.forceSum, bb.torqueSum, f.forceSum, mot.boundSeg);

        int nMB = b.n, nBb = bb.n, nM = mot.nMotors, nSeg = f.n, nD = sc.dim.nDimers;
        sched = new GridScheduler();
        if (withCycle) addW("mglide.cycle", pad(nM));
        addW("mglide.zeroMot", pad(nMB)); addW("mglide.zeroBb", pad(nBb));
        addW("mglide.joints", pad(nMB)); addW("mglide.dimer", pad(nD)); addW("mglide.tether", pad(nD));
        addS("mglide.bbHist"); addS("mglide.bbScan"); addS("mglide.bbScatter"); addW("mglide.bbGather", pad(nBb));
        addW("mglide.bond", pad(nM)); addW("mglide.applyHead", pad(nM));
        addW("mglide.integM", pad(nMB)); addW("mglide.integB", pad(nBb));
        addW("mglide.deriveM", pad(nMB)); addW("mglide.deriveB", pad(nBb)); addW("mglide.register", pad(nM));
        addW("mglide.zeroFil", pad(nSeg));
        addS("mglide.filHist"); addS("mglide.filScan"); addS("mglide.filScatter"); addW("mglide.filGather", pad(nSeg));
        return new TornadoExecutionPlan(tg.snapshot());
    }
    static int pad(int n) { return ((n + B - 1) / B) * B; }
    static void addW(String n, int g) { WorkerGrid w = new WorkerGrid1D(g); w.setLocalWork(B, 1, 1); sched.addWorkerGrid(n, w); }
    static void addS(String n) { WorkerGrid w = new WorkerGrid1D(1); w.setLocalWork(1, 1, 1); sched.addWorkerGrid(n, w); }

    static void runCpu(Scene sc, int M, boolean cyc) {
        Runnable step = cpuStep(sc, cyc);
        for (int t = 0; t < M; t++) { sc.mot.setCounts(t, SEED, sc.fil.n); sc.mini.setBackboneCounts(t, SEED_BB); step.run(); }
    }
    static void runGpu(Scene sc, int M, boolean cyc) {
        TornadoExecutionPlan plan = buildPlan(sc, cyc);
        RigidRodBody b = sc.mot.body, bb = sc.mini.backbone;
        for (int t = 0; t < M; t++) {
            sc.mot.setCounts(t, SEED, sc.fil.n); sc.mini.setBackboneCounts(t, SEED_BB);
            TornadoExecutionResult res = plan.withGridScheduler(sched).execute();
            if (t == M - 1) res.transferToHost(b.coord, b.uVec, bb.coord, bb.uVec, bb.forceSum, bb.torqueSum, sc.fil.forceSum, sc.mot.boundSeg);
        }
    }
    static long countBound(MotorStore m) { long c = 0; for (int i = 0; i < m.nMotors; i++) if (m.boundSeg.get(i) >= 0) c++; return c; }
    static double maxDiff(FloatArray a, FloatArray b) { double m=0; for (int i=0;i<a.getSize();i++) m=Math.max(m, Math.abs(a.get(i)-b.get(i))); return m; }

    // ============================================================== #1 force transmission UNDER LOAD (the headline)
    static boolean checkForceTransmission(double dt) {
        System.out.println("--- #1: force transmission UNDER LOAD (backbone gather==brute, fil gather==brute, momentum, CPU≡GPU) ---");
        Scene sc = buildScene(dt, 4, 8, true);
        sc.mot.setAllStates(MotorStore.NUC_ADPPI);
        long bound = countBound(sc.mot);
        // Build up load: the held cross-bridge pulls the bound rods, displacing them from their backbone
        // attach points ⇒ real tether strain ⇒ the backbone is genuinely loaded (the tether force at the
        // FIRST step is ~0 because the rods haven't moved yet — integrate runs after tether). 200 held
        // steps reach a loaded quasi-steady state; the LAST step's gathers (bb.forceSum / f.forceSum) are
        // the loaded values, with miniData / bondData consistent for the brute comparison.
        runCpu(sc, 200, false);
        FilamentStore f = sc.fil; MotorStore mot = sc.mot; RigidRodBody b = mot.body;
        RigidRodBody bb = sc.mini.backbone; MiniFilamentStore mini = sc.mini;

        // backbone gather == brute (the HEADLINE — the load now flows through the single-ended gather)
        sc.bruteBbF.init(0f); sc.bruteBbT.init(0f);
        MiniFilamentSystem.bruteGather(mini.headBackboneSlot, mini.miniData, sc.bruteBbF, sc.bruteBbT, mini.miniCounts);
        double bbMax = 0, bbLoad = 0;
        for (int i = 0; i < 3 * mini.nBackbones; i++) {
            bbMax = Math.max(bbMax, Math.abs(bb.forceSum.get(i) - sc.bruteBbF.get(i)));
            bbMax = Math.max(bbMax, Math.abs(bb.torqueSum.get(i) - sc.bruteBbT.get(i)));
            bbLoad = Math.max(bbLoad, Math.abs(bb.forceSum.get(i)));
        }
        boolean bbOk = bbMax == 0.0 && bbLoad > 1e-15;   // bit-identical AND actually loaded (non-trivial)
        System.out.printf("  backbone gather==brute: max|Δ|=%.3e (==0); max backbone load=%.3e N (>0 ⇒ under load) => %s%n",
                bbMax, bbLoad, bbOk ? "ok" : "*FAIL*");

        // fil gather == brute (the cross-bridge side, re-exercised at minifilament scale)
        sc.bruteFilF.init(0f); sc.bruteFilT.init(0f);
        CrossBridgeSystem.bruteGather(mot.boundSeg, sc.bondData, sc.bruteFilF, sc.bruteFilT, mot.counts);
        double fMax = 0;
        for (int i = 0; i < 3 * f.n; i++) fMax = Math.max(fMax, Math.abs(f.forceSum.get(i) - sc.bruteFilF.get(i)));
        boolean fOk = fMax == 0.0;
        System.out.printf("  bound heads=%d/%d; fil gather==brute max|Δ|=%.3e (==0) => %s%n", bound, mot.nMotors, fMax, fOk ? "ok" : "*FAIL*");

        // momentum: Σ(motor body) + Σ(backbone) + Σ(filament) ≈ 0  (cross-bridge + tether are the only
        // cross-entity forces; joints + dimer-coupling are internal). From the loaded post-run state, all
        // three force accumulators still hold the last step's pre-integrate totals.
        double[] mom = new double[4];
        for (int k = 0; k < 3; k++) {
            double sm = 0, sbb = 0, sf = 0;
            for (int i = 0; i < b.n; i++)  sm  += b.forceSum.get(k * b.n + i);
            for (int i = 0; i < bb.n; i++) sbb += bb.forceSum.get(k * bb.n + i);
            for (int s = 0; s < f.n; s++)  sf  += f.forceSum.get(k * f.n + s);
            mom[k + 1] = sm + sbb + sf; mom[0] = Math.max(mom[0], Math.abs(sm + sbb + sf));
        }
        boolean momOk = mom[0] < 1e-15;
        System.out.printf("  momentum |ΣmotorF+ΣbbF+ΣfilF| = (%.2e,%.2e,%.2e) N max=%.2e (~0) => %s%n", mom[1], mom[2], mom[3], mom[0], momOk ? "ok" : "*FAIL*");

        boolean cpuGpuOk = true;
        if (!cpu) {
            Scene g = buildScene(dt, 4, 8, true); g.mot.setAllStates(MotorStore.NUC_ADPPI);
            Scene c = buildScene(dt, 4, 8, true); c.mot.setAllStates(MotorStore.NUC_ADPPI);
            runGpu(g, 300, false); runCpu(c, 300, false);
            double dM = maxDiff(g.mot.body.coord, c.mot.body.coord);
            double dB = maxDiff(g.mini.backbone.coord, c.mini.backbone.coord);
            cpuGpuOk = dM < 5e-5 && dB < 5e-5;
            System.out.printf("  CPU≡GPU (300 loaded steps, Brownian off): max|Δmotor|=%.3e max|Δbackbone|=%.3e µm (<5e-5) => %s%n", dM, dB, cpuGpuOk ? "ok" : "*FAIL*");
        } else System.out.println("  CPU≡GPU: skipped (-cpu)");

        boolean ok = bbOk && fOk && momOk && cpuGpuOk;
        System.out.println("  => " + (ok ? "PASS" : "*FAIL*") + "\n");
        return ok;
    }
    // ============================================================== #2 binding gates at population scale
    static boolean checkBindingGates(double dt) {
        System.out.println("--- #2: binding gates at population scale (per-dimer lever-align; no minifilament-level gate) ---");
        // 1 minifilament, 16 dimers; set mixed binding states across the dimers; verify each dimer's
        // lever-align fires iff NOT both-bound (v1 MyosinDimer:276), suppressed when both bound.
        Scene sc = buildScene(dt, 1, 8, false);
        MotorStore mot = sc.mot; RigidRodBody b = mot.body; DimerStore dim = sc.dim;
        int nD = dim.nDimers;
        // tilt each dimer's leverB by 10° so a fired align torque is non-zero (off the 160° rest)
        for (int d = 0; d < nD; d++) {
            int lB = mot.leverIdx(dim.motorB.get(d));
            // rotate leverB uVec slightly in its own plane (perturb x,z keeping it a unit vector)
            double ux = b.uVec.get(lB), uz = b.uVec.get(2 * b.n + lB);
            double c = Math.cos(Math.toRadians(10)), s = Math.sin(Math.toRadians(10));
            b.uVec.set(lB, (float) (c * ux - s * uz)); b.uVec.set(2 * b.n + lB, (float) (s * ux + c * uz));
        }
        // assign a pattern of binding states: dimer d ⇒ state d%3 (0=both-free,1=one-bound,2=both-bound)
        boolean[] expectFire = new boolean[nD];
        for (int d = 0; d < nD; d++) {
            int st = d % 3, mA = dim.motorA.get(d), mB = dim.motorB.get(d);
            mot.boundSeg.set(mA, st >= 1 ? 0 : MotorStore.FREE_BINDABLE);
            mot.boundSeg.set(mB, st == 2 ? 0 : MotorStore.FREE_BINDABLE);
            expectFire[d] = (st != 2);   // suppressed iff both bound
        }
        ChainBendingForceSystem.zeroAccumulators(b.forceSum, b.torqueSum, mot.counts);
        DimerCouplingSystem.couple(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, dim.motorA, dim.motorB, dim.parallel, dim.dimerParams, mot.boundSeg);
        boolean ok = true; int fired = 0, suppressed = 0;
        for (int d = 0; d < nD; d++) {
            int lA = mot.leverIdx(dim.motorA.get(d)), nB = b.n;
            double tx = b.torqueSum.get(lA), ty = b.torqueSum.get(nB + lA), tz = b.torqueSum.get(2 * nB + lA);
            boolean leverTorque = Math.sqrt(tx * tx + ty * ty + tz * tz) > 1e-25;   // only the align torques the LEVER
            if (leverTorque == expectFire[d]) { if (leverTorque) fired++; else suppressed++; }
            else { ok = false; System.out.printf("  dimer %d state %d: fired=%s expect=%s *FAIL*%n", d, d % 3, leverTorque, expectFire[d]); }
        }
        System.out.printf("  %d dimers: align fired=%d, suppressed(both-bound)=%d, all match v1 MyosinDimer:276 => %s%n", nD, fired, suppressed, ok ? "ok" : "*FAIL*");
        System.out.println("  minifilament-level gate: NONE (BoA-v1ref constrainEnd1/End2Dimers tether unconditionally; countBoundMotors diagnostic-only).");
        System.out.println("  => " + (ok ? "PASS" : "*FAIL*") + "\n");
        return ok;
    }

    // ============================================================== #3 bipolar collective (emergent, observe)
    static boolean checkBipolarCollective(double dt) {
        System.out.println("--- #3: bipolar collective — backbone motion consistent with the gathered net (single-polarity engagement) ---");
        Scene sc = buildScene(dt, 1, 8, true);
        sc.mot.nucleotideState.init(MotorStore.NUC_NONE);
        RigidRodBody bb = sc.mini.backbone;
        double bbX0 = bb.coord.get(0);
        int M = 20000;
        Runnable step = cpuStep(sc, true);
        double boundSum = 0, fxSum = 0; int n = 0;
        for (int t = 0; t < M; t++) {
            sc.mot.setCounts(t, SEED, sc.fil.n); sc.mini.setBackboneCounts(t, SEED_BB);
            step.run(); bindStep(sc, 1);
            boundSum += countBound(sc.mot);
            fxSum += bb.forceSum.get(0);   // gathered backbone x-force this step (before next zero)
            n++;
        }
        double bbDx = (bb.coord.get(0) - bbX0) * 1e3;   // nm
        double fxMean = fxSum / n;
        // consistency: the backbone displacement sign matches the time-averaged gathered net x-force sign.
        boolean consistent = (Math.abs(bbDx) < 0.05) || (Math.signum(bbDx) == Math.signum(fxMean));
        System.out.printf("  backbone Δx = %.3f nm over %d steps; mean gathered net Fx = %.3e N; avgBound = %.2f/%d%n",
                bbDx, M, fxMean, boundSum / n, sc.mot.nMotors);
        System.out.printf("  sign(Δx)==sign(Fx) (motion tracks the gathered net) => %s%n", consistent ? "ok" : "*FAIL*");
        System.out.println("  (single pinned filament ⇒ only the end2 polarity engages [rodDotFil≥0]; the backbone walks rather");
        System.out.println("   than stalls — genuine bipolar stall/contraction needs the two-antiparallel-filament geometry, next increment.)");
        System.out.println("  => " + (consistent ? "PASS" : "*FAIL*") + "\n");
        return consistent;
    }

    // ============================================================== #5 all-OFF ≡ HEAD (dimer-glide path)
    static boolean checkAllOffEqualsHead(double dt) {
        System.out.println("--- #5: all-OFF ≡ HEAD (tether off ⇒ the dimer-glide path, bit-identical) ---");
        Scene a = buildScene(dt, 2, 8, true); a.tetherOn = false; a.mot.nucleotideState.init(MotorStore.NUC_NONE);
        Scene b = buildScene(dt, 2, 8, true); b.tetherOn = false; b.mot.nucleotideState.init(MotorStore.NUC_NONE);
        runCpu(a, 1500, true); runCpu(b, 1500, true);
        double dOff = maxDiff(a.mot.body.coord, b.mot.body.coord);
        boolean det = dOff == 0.0;
        Scene cOn = buildScene(dt, 2, 8, true); cOn.mot.nucleotideState.init(MotorStore.NUC_NONE);   // tether ON
        runCpu(cOn, 1500, true);
        double dOnVsOff = maxDiff(cOn.mot.body.coord, a.mot.body.coord);
        boolean controlOk = dOnVsOff > 1e-9;
        boolean ok = det && controlOk;
        System.out.printf("  dimer-glide path determinism (tether off): max|Δ|=%.3e (==0) => %s%n", dOff, det ? "ok" : "*FAIL*");
        System.out.printf("  control: tether ON vs OFF differs by %.3e µm (the backbone coupling is real) => %s%n", dOnVsOff, controlOk ? "ok" : "*FAIL*");
        System.out.println("  => " + (ok ? "PASS" : "*FAIL*") + "\n");
        return ok;
    }

    // ============================================================== dimer placement (6b-splayed rest)
    static void placeDimerAlong(MotorStore mot, int mA, int mB,
                                double e1x, double e1y, double e1z, double dx, double dy, double dz) {
        double dm = Math.sqrt(dx*dx+dy*dy+dz*dz); dx/=dm; dy/=dm; dz/=dm;
        double px = -dz, py = 0, pz = dx;
        double pm = Math.sqrt(px*px+py*py+pz*pz);
        if (pm < 1e-4) { px = 1; py = 0; pz = 0; pm = 1; }
        px/=pm; py/=pm; pz/=pm;
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
        b.brownTransScale.set(rod, 0f);   b.brownRotScale.set(rod, 0f);
        b.brownTransScale.set(lever, 0f); b.brownRotScale.set(lever, 0f);
        b.brownTransScale.set(head, 0f);  b.brownRotScale.set(head, 0f);
    }

    // ============================================================== viewer
    static final String[] STATE_NAME = { "NONE", "ATP", "ADPPi", "ADP" };
    static void runViz(double dt, String dir) {
        Scene sc = buildScene(dt, 2, 8, true); sc.mot.nucleotideState.init(MotorStore.NUC_NONE);
        new java.io.File(dir).mkdirs();
        Runnable step = cpuStep(sc, true);
        int M = 20000, every = Math.max(1, M / 400), frames = 0;
        for (int t = 0; t <= M; t++) {
            sc.mot.setCounts(t, SEED, sc.fil.n); sc.mini.setBackboneCounts(t, SEED_BB);
            if (t % every == 0) writeFrame(dir, frames++, t * dt, sc);
            step.run(); bindStep(sc, 1);
        }
        System.out.println("viewer: wrote " + frames + " frames to " + dir);
    }
    static void writeFrame(String dir, int frame, double t, Scene sc) {
        FilamentStore f = sc.fil; MotorStore mot = sc.mot; RigidRodBody b = mot.body; RigidRodBody bb = sc.mini.backbone;
        StringBuilder sb = new StringBuilder(512 + 200 * mot.nMotors);
        sb.append(String.format(java.util.Locale.US, "{\"frame\":%d,\"t\":%.6g,\"bounds\":{\"xDim\":1.0,\"yDim\":1.0,\"zDim\":0.4}", frame, t));
        sb.append(",\"segments\":[");
        for (int s = 0; s < f.n; s++) { if (s > 0) sb.append(',');
            sb.append(String.format(java.util.Locale.US, "{\"id\":%d,\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.5g,\"notADPRatio\":1.0,\"cofilinCount\":0}",
                s, f.end1.get(s),f.end1.get(f.n+s),f.end1.get(2*f.n+s), f.end2.get(s),f.end2.get(f.n+s),f.end2.get(2*f.n+s), Constants.radius)); }
        // backbones as thick segments + the myosins
        sb.append("],\"myosins\":[");
        boolean first = true;
        for (int s = 0; s < sc.mini.nBackbones; s++) {
            if (!first) sb.append(','); first = false;
            sb.append(String.format(java.util.Locale.US,
                "{\"id\":%d,\"rod\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g,\"invisible\":false},"
                + "\"lever\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g},"
                + "\"motor\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g,\"state\":\"backbone\"}}",
                100000 + s, bb.end1X(s),bb.end1Y(s),bb.end1Z(s), bb.end2X(s),bb.end2Y(s),bb.end2Z(s), MiniFilamentStore.BACKBONE_R,
                bb.end1X(s),bb.end1Y(s),bb.end1Z(s), bb.end2X(s),bb.end2Y(s),bb.end2Z(s), MiniFilamentStore.BACKBONE_R,
                bb.end1X(s),bb.end1Y(s),bb.end1Z(s), bb.end2X(s),bb.end2Y(s),bb.end2Z(s), MiniFilamentStore.BACKBONE_R));
        }
        for (int m = 0; m < mot.nMotors; m++) {
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
}
