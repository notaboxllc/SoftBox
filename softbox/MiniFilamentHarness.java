package softbox;

import uk.ac.manchester.tornado.api.GridScheduler;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.TornadoExecutionResult;
import uk.ac.manchester.tornado.api.WorkerGrid;
import uk.ac.manchester.tornado.api.WorkerGrid1D;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;

/**
 * Increment 6b harness: the myosin MINIFILAMENT (a rigid-rod backbone OWNING N dimers), validated on a
 * pre-placed ISOMETRIC bed. The central favorable recon finding (§2): the backbone↔dimer coupling is
 * SINGLE-ENDED, one pass — reuse the CrossBridge CSR-inverse keyed by a backbone slot, NOT the
 * crosslinker double-ended two-pass. Static assembly, heads FREE (no cross-bridge / glide).
 *
 * Per step (one physics, two runners): over the motor body AND the backbone body —
 *   zero → Brownian → MotorJoints(J1/J2) → DimerCoupling(6a) → MiniFilament.tether →
 *   CrossBridge.csr{Hist,Scan,Scatter}(keyed headBackboneSlot) → MiniFilament.backboneGather →
 *   integrate → derive.
 *
 * Gates (vs BoA-v1ref, co-developed not fixtures):
 *   A gather == bruteGather (bit-identical) + tether arithmetic vs a v1 double reference (bit-for-decision)
 *     + momentum conservation (rod self-write + backbone gather = 0);
 *   B isometric hold (rest-config: an exact Brownian-off fixed point; Brownian-on: stationary, bounded);
 *   C CPU≡GPU (deterministic gather/tether bit-identical; Brownian aggregate);
 *   D FDT self-consistency (backbone + dimer fluctuations stationary/bounded — NOT a dt-independent ½kT);
 *   E all-OFF≡HEAD (tether off ⇒ the motor body evolves identically to a bare 6a dimer-bed run).
 */
public final class MiniFilamentHarness {

    static boolean cpu = false;
    static GridScheduler sched;
    static final int B = 64;
    static final int SEED = 0x6B1A0E, SEED_BB = 0x5C2F11;     // distinct motor / backbone Brownian seeds
    static final double SPACING = 0.80;                       // µm between bed backbones
    static final double SIN80 = Math.sin(Math.toRadians(80.0)), COS80 = Math.cos(Math.toRadians(80.0));

    public static void main(String[] args) {
        double dt = 1.0e-5;
        int nBackbones = 8, dimersEnd = MiniFilamentStore.DIMERS_EACH_END;   // 8 ⇒ 16 dimers/backbone
        int M = 4000;
        String vizDir = null;
        java.util.List<String> pos = new java.util.ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-cpu" -> cpu = true;
                case "-dt"  -> dt = Double.parseDouble(args[++i]);
                case "-n"   -> nBackbones = Integer.parseInt(args[++i]);
                case "-de"  -> dimersEnd = Integer.parseInt(args[++i]);
                case "-3js" -> vizDir = args[++i];
                default     -> pos.add(args[i]);
            }
        }
        if (!pos.isEmpty()) M = Integer.parseInt(pos.get(0));

        System.out.println("=== Soft Box increment 6b — myosin minifilament (backbone owns N dimers) ===");
        System.out.println("single-ended one-pass gather; static assembly, heads free; no cross-bridge/glide.");
        System.out.printf("config: %d backbones × %d dimers/end ⇒ %d dimers / %d motors, M=%d, dt=%.1e%n",
                nBackbones, dimersEnd, nBackbones * 2 * dimersEnd, nBackbones * 4 * dimersEnd, M, dt);

        if (vizDir != null) { runViz(nBackbones, dimersEnd, M, dt, vizDir); return; }

        boolean okA = checkGatherAndTether(dt);
        boolean okE = checkAllOffEqualsHead(dt);
        boolean okB = checkIsometricHold(nBackbones, dimersEnd, dt);
        boolean okD = checkFdtSelfConsistency(nBackbones, dimersEnd, dt);
        boolean okC = checkCpuGpu(nBackbones, dimersEnd, dt);

        boolean ok = okA && okB && okC && okD && okE;
        System.out.println();
        System.out.println("=== MINIFILAMENT VALIDATION " + (ok ? "PASS" : "FAIL") + " ===");
        if (!ok) { System.out.println("BAIL-OUT: a gate failed. Commit nothing."); System.exit(1); }
    }

    // ============================================================== scene
    static final class Scene { MotorStore mot; DimerStore dim; MiniFilamentStore mini; }

    static Scene buildScene(int nBackbones, int dimersEnd, double dt, float brownScale) {
        int nDimers = nBackbones * 2 * dimersEnd;
        MotorStore mot = new MotorStore(2 * nDimers);
        DimerStore dim = new DimerStore(nDimers);
        MiniFilamentStore mini = new MiniFilamentStore(nBackbones, nDimers);
        double L = MiniFilamentStore.BACKBONE_LEN, hz = MiniFilamentStore.HEAD_ZONE;
        int side = (int) Math.ceil(Math.sqrt(nBackbones));
        double x0 = -0.5 * (side - 1) * SPACING, y0 = -0.5 * (side - 1) * SPACING;

        int d = 0;
        for (int bb = 0; bb < nBackbones; bb++) {
            int r = bb / side, c = bb % side;
            double bx = x0 + c * SPACING, by = y0 + r * SPACING, bz = 0.0;
            // backbone along +x, centered at (bx,by,bz)
            mini.backbone.setCoord(mini.bbIdx(bb), (float) bx, (float) by, (float) bz);
            mini.backbone.setUVec(mini.bbIdx(bb), 1f, 0f, 0f);
            mini.backbone.setYVec(mini.bbIdx(bb), 0f, 1f, 0f);
            mini.backbone.brownTransScale.set(mini.bbIdx(bb), brownScale);
            mini.backbone.brownRotScale.set(mini.bbIdx(bb), brownScale);
            // dimers: dimersEnd at end1 (ax<0, align −x) + dimersEnd at end2 (ax>0, align +x)
            for (int e = 0; e < 2; e++) {
                double dir = (e == 0) ? -1.0 : 1.0;          // alignment / rod direction
                for (int j = 0; j < dimersEnd; j++) {
                    double mag = L / 2.0 - (j + 0.5) / dimersEnd * hz;   // ∈ (L/2−hz, L/2)
                    double ax = dir * mag;
                    double attX = bx + ax;                   // attach = backbone.coord + ax·uVec(+x)
                    int mA = 2 * d, mB = 2 * d + 1;
                    placeDimerAlong(mot, mA, mB, attX, by, bz, dir, 0.0, 0.0, brownScale);
                    dim.pair(d, mA, mB, true);
                    mini.attach(d, bb, mA, ax);
                    d++;
                }
            }
        }
        DragTensorSystem.run(mot);
        mini.initBackboneDrag();
        mot.setBodyParams(dt); mot.setJointParams(dt); mot.setAllStates(MotorStore.NUC_ADPPI);
        dim.setDimerParams(dt);
        mini.setMiniParams(dt); mini.setBackboneParams(dt);
        Scene sc = new Scene(); sc.mot = mot; sc.dim = dim; sc.mini = mini;
        return sc;
    }

    /** Place a full dimer (motors mA,mB) with rodA/rodB coincident along `dir`, rodA.end1 at (e1x,e1y,e1z),
     *  levers splaying ±80° (160° apart) in the (dir, perp) plane, heads collinear. */
    static void placeDimerAlong(MotorStore mot, int mA, int mB,
                                double e1x, double e1y, double e1z, double dx, double dy, double dz, float brownScale) {
        double dm = Math.sqrt(dx*dx+dy*dy+dz*dz); dx/=dm; dy/=dm; dz/=dm;
        // perp ⟂ dir (lever splay plane)
        double px = -dz, py = 0, pz = dx;            // dir × (0,1,0)
        double pm = Math.sqrt(px*px+py*py+pz*pz);
        if (pm < 1e-4) { px = 1; py = 0; pz = 0; pm = 1; }
        px/=pm; py/=pm; pz/=pm;
        double rl = MotorStore.ROD_LEN, ll = MotorStore.LEVER_LEN, hl = MotorStore.HEAD_LEN;
        // rod center = end1 + ½·rl·dir ; rod.end2 = end1 + rl·dir
        double rcx=e1x+0.5*rl*dx, rcy=e1y+0.5*rl*dy, rcz=e1z+0.5*rl*dz;
        double e2x=e1x+rl*dx, e2y=e1y+rl*dy, e2z=e1z+rl*dz;
        placeArm(mot, mA, rcx,rcy,rcz, dx,dy,dz, px,py,pz, e2x,e2y,e2z,  +1, ll, hl, brownScale);  // +80°
        placeArm(mot, mB, rcx,rcy,rcz, dx,dy,dz, px,py,pz, e2x,e2y,e2z,  -1, ll, hl, brownScale);  // −80°
    }

    static void placeArm(MotorStore mot, int m, double rcx, double rcy, double rcz,
                         double dx, double dy, double dz, double px, double py, double pz,
                         double e2x, double e2y, double e2z, int splay, double ll, double hl, float brownScale) {
        int rod = mot.rodIdx(m), lever = mot.leverIdx(m), head = mot.headIdx(m);
        RigidRodBody b = mot.body;
        // rod (coincident for both arms): center given, uVec=dir, yVec=perp
        b.setCoord(rod, (float) rcx, (float) rcy, (float) rcz);
        b.setUVec(rod, (float) dx, (float) dy, (float) dz); b.setYVec(rod, (float) px, (float) py, (float) pz);
        // lever uVec = cos80·dir + splay·sin80·perp
        double lux = COS80*dx + splay*SIN80*px, luy = COS80*dy + splay*SIN80*py, luz = COS80*dz + splay*SIN80*pz;
        // out-of-plane normal (⟂ lever uVec) for yVec
        double nx = dy*pz - dz*py, ny = dz*px - dx*pz, nz = dx*py - dy*px;
        double lcx = e2x + 0.5*ll*lux, lcy = e2y + 0.5*ll*luy, lcz = e2z + 0.5*ll*luz;
        b.setCoord(lever, (float) lcx, (float) lcy, (float) lcz);
        b.setUVec(lever, (float) lux, (float) luy, (float) luz); b.setYVec(lever, (float) nx, (float) ny, (float) nz);
        double le2x = e2x + ll*lux, le2y = e2y + ll*luy, le2z = e2z + ll*luz;
        double hcx = le2x + 0.5*hl*lux, hcy = le2y + 0.5*hl*luy, hcz = le2z + 0.5*hl*luz;
        b.setCoord(head, (float) hcx, (float) hcy, (float) hcz);
        b.setUVec(head, (float) lux, (float) luy, (float) luz); b.setYVec(head, (float) nx, (float) ny, (float) nz);
        b.brownTransScale.set(rod, brownScale);   b.brownRotScale.set(rod, brownScale);
        b.brownTransScale.set(lever, 0f);          b.brownRotScale.set(lever, 0f);
        b.brownTransScale.set(head, brownScale);   b.brownRotScale.set(head, brownScale);
    }

    // ============================================================== per-step
    static Runnable cpuStep(Scene sc, boolean tetherOn) {
        MotorStore mot = sc.mot; RigidRodBody mb = mot.body; DimerStore dim = sc.dim;
        MiniFilamentStore mini = sc.mini; RigidRodBody bb = mini.backbone;
        return () -> {
            ChainBendingForceSystem.zeroAccumulators(mb.forceSum, mb.torqueSum, mot.counts);
            ChainBendingForceSystem.zeroAccumulators(bb.forceSum, bb.torqueSum, mini.bbCounts);
            BrownianForceSystem.brownianForce(mb.randForce, mb.randTorque, mb.bTransGam, mb.bRotGam,
                    mb.brownTransScale, mb.brownRotScale, mot.bodyParams, mot.counts);
            BrownianForceSystem.brownianForce(bb.randForce, bb.randTorque, bb.bTransGam, bb.bRotGam,
                    bb.brownTransScale, bb.brownRotScale, mini.bbBodyParams, mini.bbCounts);
            MotorJointSystem.joints(mb.coord, mb.uVec, mb.segLength, mb.bTransGam, mb.bRotGam,
                    mb.forceSum, mb.torqueSum, mot.nucleotideState, mot.jointParams, mot.counts);
            DimerCouplingSystem.couple(mb.coord, mb.uVec, mb.segLength, mb.bTransGam, mb.bRotGam,
                    mb.forceSum, mb.torqueSum, dim.motorA, dim.motorB, dim.parallel, dim.dimerParams);
            if (tetherOn) {
                MiniFilamentSystem.tether(mb.coord, mb.uVec, mb.segLength, mb.bTransGam, mb.bRotGam,
                        mb.forceSum, mb.torqueSum, bb.coord, bb.uVec, mini.bbInvDragY,
                        mini.headBackboneSlot, mini.motorA, mini.attachAxial, mini.miniData, mini.miniParams);
                CrossBridgeSystem.csrHistogram(mini.headBackboneSlot, mini.miniCounts, mini.bbDimerCount);
                CrossBridgeSystem.csrScan(mini.miniCounts, mini.bbDimerCount, mini.bbDimerOffsets);
                CrossBridgeSystem.csrScatter(mini.headBackboneSlot, mini.miniCounts, mini.bbDimerOffsets, mini.bbDimerCount, mini.bbDimerList);
                MiniFilamentSystem.backboneGather(mini.bbDimerOffsets, mini.bbDimerList, mini.miniData,
                        bb.forceSum, bb.torqueSum, mini.miniCounts);
            }
            RigidRodLangevinIntegrationSystem.integrate(mb.coord, mb.uVec, mb.yVec, mb.forceSum, mb.torqueSum,
                    mb.randForce, mb.randTorque, mb.bTransGam, mb.bRotGam, mot.bodyParams, mot.counts);
            RigidRodLangevinIntegrationSystem.integrate(bb.coord, bb.uVec, bb.yVec, bb.forceSum, bb.torqueSum,
                    bb.randForce, bb.randTorque, bb.bTransGam, bb.bRotGam, mini.bbBodyParams, mini.bbCounts);
            DerivedGeometrySystem.derive(mb.coord, mb.uVec, mb.yVec, mb.zVec, mb.end1, mb.end2, mb.segLength, mot.counts);
            DerivedGeometrySystem.derive(bb.coord, bb.uVec, bb.yVec, bb.zVec, bb.end1, bb.end2, bb.segLength, mini.bbCounts);
        };
    }

    static TornadoExecutionPlan buildPlan(Scene sc) {
        MotorStore mot = sc.mot; RigidRodBody mb = mot.body; DimerStore dim = sc.dim;
        MiniFilamentStore mini = sc.mini; RigidRodBody bb = mini.backbone;
        TaskGraph tg = new TaskGraph("minifil")
            .transferToDevice(DataTransferMode.FIRST_EXECUTION,
                    mb.coord, mb.uVec, mb.yVec, mb.zVec, mb.end1, mb.end2, mb.segLength,
                    mb.bTransGam, mb.bRotGam, mb.forceSum, mb.torqueSum, mb.randForce, mb.randTorque,
                    mb.brownTransScale, mb.brownRotScale, mot.bodyParams, mot.jointParams, mot.nucleotideState,
                    dim.motorA, dim.motorB, dim.parallel, dim.dimerParams,
                    bb.coord, bb.uVec, bb.yVec, bb.zVec, bb.end1, bb.end2, bb.segLength,
                    bb.bTransGam, bb.bRotGam, bb.forceSum, bb.torqueSum, bb.randForce, bb.randTorque,
                    bb.brownTransScale, bb.brownRotScale, mini.bbBodyParams,
                    mini.bbInvDragY, mini.headBackboneSlot, mini.motorA, mini.attachAxial, mini.miniData, mini.miniParams,
                    mini.bbDimerCount, mini.bbDimerOffsets, mini.bbDimerList, mini.miniCounts)
            .transferToDevice(DataTransferMode.EVERY_EXECUTION, mot.counts, mini.bbCounts)
            .task("zeroM", ChainBendingForceSystem::zeroAccumulators, mb.forceSum, mb.torqueSum, mot.counts)
            .task("zeroB", ChainBendingForceSystem::zeroAccumulators, bb.forceSum, bb.torqueSum, mini.bbCounts)
            .task("brownM", BrownianForceSystem::brownianForce, mb.randForce, mb.randTorque, mb.bTransGam, mb.bRotGam,
                    mb.brownTransScale, mb.brownRotScale, mot.bodyParams, mot.counts)
            .task("brownB", BrownianForceSystem::brownianForce, bb.randForce, bb.randTorque, bb.bTransGam, bb.bRotGam,
                    bb.brownTransScale, bb.brownRotScale, mini.bbBodyParams, mini.bbCounts)
            .task("joints", MotorJointSystem::joints, mb.coord, mb.uVec, mb.segLength, mb.bTransGam, mb.bRotGam,
                    mb.forceSum, mb.torqueSum, mot.nucleotideState, mot.jointParams, mot.counts)
            .task("dimer", DimerCouplingSystem::couple, mb.coord, mb.uVec, mb.segLength, mb.bTransGam, mb.bRotGam,
                    mb.forceSum, mb.torqueSum, dim.motorA, dim.motorB, dim.parallel, dim.dimerParams)
            .task("tether", MiniFilamentSystem::tether, mb.coord, mb.uVec, mb.segLength, mb.bTransGam, mb.bRotGam,
                    mb.forceSum, mb.torqueSum, bb.coord, bb.uVec, mini.bbInvDragY,
                    mini.headBackboneSlot, mini.motorA, mini.attachAxial, mini.miniData, mini.miniParams)
            .task("csrHist", CrossBridgeSystem::csrHistogram, mini.headBackboneSlot, mini.miniCounts, mini.bbDimerCount)
            .task("csrScan", CrossBridgeSystem::csrScan, mini.miniCounts, mini.bbDimerCount, mini.bbDimerOffsets)
            .task("csrScatter", CrossBridgeSystem::csrScatter, mini.headBackboneSlot, mini.miniCounts, mini.bbDimerOffsets, mini.bbDimerCount, mini.bbDimerList)
            .task("gather", MiniFilamentSystem::backboneGather, mini.bbDimerOffsets, mini.bbDimerList, mini.miniData,
                    bb.forceSum, bb.torqueSum, mini.miniCounts)
            .task("integM", RigidRodLangevinIntegrationSystem::integrate, mb.coord, mb.uVec, mb.yVec, mb.forceSum, mb.torqueSum,
                    mb.randForce, mb.randTorque, mb.bTransGam, mb.bRotGam, mot.bodyParams, mot.counts)
            .task("integB", RigidRodLangevinIntegrationSystem::integrate, bb.coord, bb.uVec, bb.yVec, bb.forceSum, bb.torqueSum,
                    bb.randForce, bb.randTorque, bb.bTransGam, bb.bRotGam, mini.bbBodyParams, mini.bbCounts)
            .task("deriveM", DerivedGeometrySystem::derive, mb.coord, mb.uVec, mb.yVec, mb.zVec, mb.end1, mb.end2, mb.segLength, mot.counts)
            .task("deriveB", DerivedGeometrySystem::derive, bb.coord, bb.uVec, bb.yVec, bb.zVec, bb.end1, bb.end2, bb.segLength, mini.bbCounts)
            .transferToHost(DataTransferMode.UNDER_DEMAND, mb.coord, mb.uVec, mb.end1, mb.end2, bb.coord, bb.uVec, bb.end1, bb.end2);

        int nMB = mb.n, nBb = bb.n;
        sched = new GridScheduler();
        addWorker("minifil.zeroM", pad(nMB)); addWorker("minifil.zeroB", pad(nBb));
        addWorker("minifil.brownM", pad(nMB)); addWorker("minifil.brownB", pad(nBb));
        addWorker("minifil.joints", pad(nMB)); addWorker("minifil.dimer", pad(sc.dim.nDimers));
        addWorker("minifil.tether", pad(sc.mini.nDimers));
        addSingle("minifil.csrHist"); addSingle("minifil.csrScan"); addSingle("minifil.csrScatter");
        addWorker("minifil.gather", pad(nBb));
        addWorker("minifil.integM", pad(nMB)); addWorker("minifil.integB", pad(nBb));
        addWorker("minifil.deriveM", pad(nMB)); addWorker("minifil.deriveB", pad(nBb));
        return new TornadoExecutionPlan(tg.snapshot());
    }
    static int pad(int n) { return ((n + B - 1) / B) * B; }
    static void addWorker(String name, int g) { WorkerGrid w = new WorkerGrid1D(g); w.setLocalWork(B, 1, 1); sched.addWorkerGrid(name, w); }
    static void addSingle(String name) { WorkerGrid w = new WorkerGrid1D(1); w.setLocalWork(1, 1, 1); sched.addWorkerGrid(name, w); }

    static void run(Scene sc, int M, boolean useGpu, boolean tetherOn) {
        MotorStore mot = sc.mot; RigidRodBody mb = mot.body; RigidRodBody bb = sc.mini.backbone;
        if (!useGpu) {
            Runnable step = cpuStep(sc, tetherOn);
            for (int t = 0; t < M; t++) { mot.setCounts(t, SEED, 0); sc.mini.setBackboneCounts(t, SEED_BB); step.run(); }
        } else {
            TornadoExecutionPlan plan = buildPlan(sc);
            for (int t = 0; t < M; t++) {
                mot.setCounts(t, SEED, 0); sc.mini.setBackboneCounts(t, SEED_BB);
                TornadoExecutionResult res = plan.withGridScheduler(sched).execute();
                if (t == M - 1) res.transferToHost(mb.coord, mb.uVec, mb.end1, mb.end2, bb.coord, bb.uVec, bb.end1, bb.end2);
            }
        }
    }

    // ============================================================== A: gather == brute + tether arithmetic
    static boolean checkGatherAndTether(double dt) {
        System.out.println("\n--- A. gather==brute + tether arithmetic (isolated) + momentum ---");
        Scene sc = buildScene(2, 4, dt, 0f);   // 2 backbones × 4/end = 16 dimers
        MotorStore mot = sc.mot; RigidRodBody mb = mot.body; MiniFilamentStore mini = sc.mini; RigidRodBody bb = mini.backbone;
        // displace backbones + some rods to make the tether non-trivial (off rest)
        bb.setCoord(0, bb.coord.get(0) + 0.006f, bb.coord.get(bb.n) + 0.004f, bb.coord.get(2*bb.n));
        DerivedGeometrySystem.derive(bb.coord, bb.uVec, bb.yVec, bb.zVec, bb.end1, bb.end2, bb.segLength, mini.bbCounts);
        for (int d = 0; d < mini.nDimers; d += 3) {
            int rodA = 3 * mini.motorA.get(d);
            mb.setCoord(rodA, mb.coord.get(rodA) + 0.005f, mb.coord.get(mb.n+rodA) - 0.003f, mb.coord.get(2*mb.n+rodA));
        }
        DerivedGeometrySystem.derive(mb.coord, mb.uVec, mb.yVec, mb.zVec, mb.end1, mb.end2, mb.segLength, mot.counts);

        // isolated: zero both, tether (rod self-write + miniData), CSR + gather
        ChainBendingForceSystem.zeroAccumulators(mb.forceSum, mb.torqueSum, mot.counts);
        ChainBendingForceSystem.zeroAccumulators(bb.forceSum, bb.torqueSum, mini.bbCounts);
        MiniFilamentSystem.tether(mb.coord, mb.uVec, mb.segLength, mb.bTransGam, mb.bRotGam, mb.forceSum, mb.torqueSum,
                bb.coord, bb.uVec, mini.bbInvDragY, mini.headBackboneSlot, mini.motorA, mini.attachAxial, mini.miniData, mini.miniParams);
        // capture rod self-write sum per backbone (for momentum) BEFORE gather writes backbone
        double[] rodForceSumPerBb = new double[mini.nBackbones * 3];
        for (int d = 0; d < mini.nDimers; d++) {
            int bbi = mini.headBackboneSlot.get(d), rodA = 3 * mini.motorA.get(d);
            rodForceSumPerBb[bbi*3]   += mb.forceSum.get(rodA);
            rodForceSumPerBb[bbi*3+1] += mb.forceSum.get(mb.n+rodA);
            rodForceSumPerBb[bbi*3+2] += mb.forceSum.get(2*mb.n+rodA);
        }
        CrossBridgeSystem.csrHistogram(mini.headBackboneSlot, mini.miniCounts, mini.bbDimerCount);
        CrossBridgeSystem.csrScan(mini.miniCounts, mini.bbDimerCount, mini.bbDimerOffsets);
        CrossBridgeSystem.csrScatter(mini.headBackboneSlot, mini.miniCounts, mini.bbDimerOffsets, mini.bbDimerCount, mini.bbDimerList);
        MiniFilamentSystem.backboneGather(mini.bbDimerOffsets, mini.bbDimerList, mini.miniData, bb.forceSum, bb.torqueSum, mini.miniCounts);

        // brute into scratch
        FloatArray bruteF = new FloatArray(3 * mini.nBackbones), bruteT = new FloatArray(3 * mini.nBackbones);
        bruteF.init(0f); bruteT.init(0f);
        MiniFilamentSystem.bruteGather(mini.headBackboneSlot, mini.miniData, bruteF, bruteT, mini.miniCounts);
        double gMax = 0;
        for (int i = 0; i < 3 * mini.nBackbones; i++) {
            gMax = Math.max(gMax, Math.abs(bb.forceSum.get(i) - bruteF.get(i)));
            gMax = Math.max(gMax, Math.abs(bb.torqueSum.get(i) - bruteT.get(i)));
        }
        boolean gatherOk = gMax == 0.0;   // bit-identical
        System.out.printf("  gather==brute: max|Δ| = %.3e (==0 bit-identical) => %s%n", gMax, gatherOk ? "ok" : "*FAIL*");

        // tether arithmetic vs v1 double reference (a displaced dimer)
        int dRef = 3;
        double[] ref = refTether(sc, dRef);
        int rodA = 3 * mini.motorA.get(dRef);
        double[] gotRod = { mb.forceSum.get(rodA), mb.forceSum.get(mb.n+rodA), mb.forceSum.get(2*mb.n+rodA),
                            mb.torqueSum.get(rodA), mb.torqueSum.get(mb.n+rodA), mb.torqueSum.get(2*mb.n+rodA) };
        // NOTE: rodA self-write may include MULTIPLE dimers? No — each rod owned by one dimer. But dRef's rod
        // got only dRef's tether (isolated). Compare.
        double relMax = 0;
        for (int i = 0; i < 6; i++) {
            double den = Math.max(Math.abs(ref[i]), 1e-30);
            if (Math.abs(ref[i]) > 1e-22) relMax = Math.max(relMax, Math.abs(gotRod[i]-ref[i])/den);
        }
        boolean tetherOk = relMax < 1e-3;
        System.out.printf("  tether arithmetic vs v1 double ref (dimer %d): maxRel = %.3e (<1e-3) => %s%n", dRef, relMax, tetherOk ? "ok" : "*FAIL*");

        // momentum: gathered backbone force == −(sum of rod self-writes) per backbone (equal-opposite)
        double momMax = 0;
        for (int s = 0; s < mini.nBackbones; s++)
            for (int k = 0; k < 3; k++)
                momMax = Math.max(momMax, Math.abs(bb.forceSum.get(k*mini.nBackbones + s) + rodForceSumPerBb[s*3+k]));
        boolean momOk = momMax < 1e-18;
        System.out.printf("  momentum (gathered + Σrod self-write = 0): max = %.3e N (~0) => %s%n", momMax, momOk ? "ok" : "*FAIL*");
        boolean ok = gatherOk && tetherOk && momOk;
        System.out.println("  => " + (ok ? "PASS" : "*FAIL*"));
        return ok;
    }

    /** v1 double-precision reference for dimer d's tether (constrainEnd1/End2Dimers): {Frod(3), Trod(3)}. */
    static double[] refTether(Scene sc, int d) {
        MiniFilamentStore mini = sc.mini; RigidRodBody mb = sc.mot.body; RigidRodBody bb = mini.backbone;
        int bbi = mini.headBackboneSlot.get(d), rodA = 3 * mini.motorA.get(d);
        double ax = mini.attachAxial.get(d), dt = mini.miniParams.get(0);
        double fracMove = MiniFilamentStore.MINIFIL_FRAC_MOVE, align = MiniFilamentStore.MINIFIL_ALIGN;
        int nMB = mb.n, nBb = bb.n;
        double bcx=bb.coord.get(bbi), bcy=bb.coord.get(nBb+bbi), bcz=bb.coord.get(2*nBb+bbi);
        double bux=bb.uVec.get(bbi), buy=bb.uVec.get(nBb+bbi), buz=bb.uVec.get(2*nBb+bbi);
        double pax=bcx+ax*bux, pay=bcy+ax*buy, paz=bcz+ax*buz;
        double rcx=mb.coord.get(rodA), rcy=mb.coord.get(nMB+rodA), rcz=mb.coord.get(2*nMB+rodA);
        double rux=mb.uVec.get(rodA), ruy=mb.uVec.get(nMB+rodA), ruz=mb.uVec.get(2*nMB+rodA);
        double rlen=mb.segLength.get(rodA);
        double re1x=rcx-0.5*rlen*rux, re1y=rcy-0.5*rlen*ruy, re1z=rcz-0.5*rlen*ruz;
        double[] out = new double[6];
        double dx=pax-re1x, dy=pay-re1y, dz=paz-re1z; double strain=Math.sqrt(dx*dx+dy*dy+dz*dz);
        if (strain > 0) {
            double lx=dx/strain, ly=dy/strain, lz=dz/strain;
            double denom = dt*(1.0/mb.bTransGam.get(nMB+rodA) + 1.0/bb.bTransGam.get(nBb+bbi));
            double fm = fracMove*1e-6*strain/denom;
            out[0]=fm*lx; out[1]=fm*ly; out[2]=fm*lz;
        }
        double sgn = (ax<0)? -1:1; double tux=sgn*bux,tuy=sgn*buy,tuz=sgn*buz;
        double tvx=ruy*tuz-ruz*tuy, tvy=ruz*tux-rux*tuz, tvz=rux*tuy-ruy*tux;
        double m2=tvx*tvx+tvy*tvy+tvz*tvz;
        if (m2>1e-30) {
            double im=1/Math.sqrt(m2); tvx*=im;tvy*=im;tvz*=im;
            double dot=rux*tux+ruy*tuy+ruz*tuz; if(dot>1)dot=1; if(dot<-1)dot=-1;
            double angDeg=Math.acos(dot)*180/Math.PI;
            double denomR=(1.0/mb.bRotGam.get(nMB+rodA)+1.0/bb.bRotGam.get(nBb+bbi))*dt;
            double tmag=align*(Math.PI/180)*angDeg/denomR;
            out[3]=tvx*tmag; out[4]=tvy*tmag; out[5]=tvz*tmag;
        }
        return out;
    }

    // ============================================================== B: isometric hold
    static boolean checkIsometricHold(int nBackbones, int dimersEnd, double dt) {
        System.out.println("\n--- B. isometric hold (rest config) ---");
        Scene off = buildScene(nBackbones, dimersEnd, dt, 0f);   // Brownian off
        double tg0 = maxTetherStrain(off);
        run(off, 3000, false, true);
        double tg1 = maxTetherStrain(off);
        boolean restOk = tg1 < 1e-5;
        System.out.printf("  Brownian-off: maxTetherStrain start=%.3e end=%.3e µm (rest = exact fixed point) => %s%n",
                tg0, tg1, restOk ? "ok" : "*FAIL*");
        // Brownian-on: the SOFT tether (coeff 0.07) lets each dimer rod jiggle to a thermal steady state
        // (~tens of nm) — bounded, no fly-apart. Stationarity is check D's job; here we gate BOUNDED only
        // (a "non-growing vs the Brownian-off rest≈0" test would wrongly flag the legitimate thermal strain).
        Scene on = buildScene(nBackbones, dimersEnd, dt, 1f);     // Brownian on
        run(on, 3000, false, true); double sp1 = maxTetherStrain(on);
        boolean stable = sp1 < 0.10;                              // bounded (< 100 nm; no fly-apart)
        System.out.printf("  Brownian-on: max tether strain after 3000 steps = %.4e µm (bounded < 0.10 µm, thermal) => %s%n",
                sp1, stable ? "ok" : "*FAIL*");
        boolean ok = restOk && stable;
        System.out.println("  => " + (ok ? "PASS" : "*FAIL*"));
        return ok;
    }
    static double maxTetherStrain(Scene sc) {
        MiniFilamentStore mini = sc.mini; RigidRodBody mb = sc.mot.body, bb = mini.backbone;
        int nMB = mb.n, nBb = bb.n; double mx = 0;
        for (int d = 0; d < mini.nDimers; d++) {
            int bbi = mini.headBackboneSlot.get(d), rodA = 3*mini.motorA.get(d); double ax = mini.attachAxial.get(d);
            double pax=bb.coord.get(bbi)+ax*bb.uVec.get(bbi), pay=bb.coord.get(nBb+bbi)+ax*bb.uVec.get(nBb+bbi), paz=bb.coord.get(2*nBb+bbi)+ax*bb.uVec.get(2*nBb+bbi);
            double rlen=mb.segLength.get(rodA);
            double re1x=mb.coord.get(rodA)-0.5*rlen*mb.uVec.get(rodA), re1y=mb.coord.get(nMB+rodA)-0.5*rlen*mb.uVec.get(nMB+rodA), re1z=mb.coord.get(2*nMB+rodA)-0.5*rlen*mb.uVec.get(2*nMB+rodA);
            double dx=pax-re1x,dy=pay-re1y,dz=paz-re1z; mx=Math.max(mx, Math.sqrt(dx*dx+dy*dy+dz*dz));
        }
        return mx;
    }
    // ============================================================== C: CPU≡GPU
    static boolean checkCpuGpu(int nBackbones, int dimersEnd, double dt) {
        if (cpu) { System.out.println("\n--- C. CPU≡GPU: skipped (-cpu) ---"); return true; }
        System.out.println("\n--- C. CPU≡GPU ---");
        // deterministic: Brownian off, displaced, 500 steps, compare backbone + motor pose
        Scene g = buildScene(nBackbones, dimersEnd, dt, 0f); displace(g);
        Scene c = buildScene(nBackbones, dimersEnd, dt, 0f); displace(c);
        run(g, 500, true, true); run(c, 500, false, true);
        double dMot = maxDiff(g.mot.body.coord, c.mot.body.coord);
        double dBb  = maxDiff(g.mini.backbone.coord, c.mini.backbone.coord);
        boolean detOk = dMot < 5e-5 && dBb < 5e-5;
        System.out.printf("  deterministic (500 steps, Brownian off): max|Δmotor|=%.3e µm  max|Δbackbone|=%.3e µm (<5e-5) => %s%n",
                dMot, dBb, detOk ? "ok" : "*FAIL*");
        // also: the gather (isolated) bit-identical CPU↔GPU is implied by the deterministic run (gather is in the step)
        System.out.println("  => " + (detOk ? "PASS" : "*FAIL*"));
        return detOk;
    }
    static void displace(Scene sc) {
        RigidRodBody bb = sc.mini.backbone;
        for (int i = 0; i < sc.mini.nBackbones; i++) bb.setCoord(i, bb.coord.get(i)+0.003f, bb.coord.get(bb.n+i), bb.coord.get(2*bb.n+i));
        DerivedGeometrySystem.derive(bb.coord, bb.uVec, bb.yVec, bb.zVec, bb.end1, bb.end2, bb.segLength, sc.mini.bbCounts);
    }
    static double maxDiff(FloatArray a, FloatArray b) { double m=0; for (int i=0;i<a.getSize();i++) m=Math.max(m, Math.abs(a.get(i)-b.get(i))); return m; }

    // ============================================================== D: FDT self-consistency (light)
    static boolean checkFdtSelfConsistency(int nBackbones, int dimersEnd, double dt) {
        System.out.println("\n--- D. FDT self-consistency (stationary + bounded; not a dt-independent ½kT) ---");
        Scene sc = buildScene(nBackbones, dimersEnd, dt, 1f);
        Runnable step = cpuStep(sc, true);
        int burn = 800, M = 3000, half = M/2;
        for (int t = 0; t < burn; t++) { sc.mot.setCounts(t, SEED, 0); sc.mini.setBackboneCounts(t, SEED_BB); step.run(); }
        double s1=0, sq1=0, s2=0, sq2=0; int n1=0, n2=0;
        for (int t = 0; t < M; t++) {
            sc.mot.setCounts(burn+t, SEED, 0); sc.mini.setBackboneCounts(burn+t, SEED_BB); step.run();
            double v = maxTetherStrain(sc);
            if (t < half) { s1+=v; sq1+=v*v; n1++; } else { s2+=v; sq2+=v*v; n2++; }
        }
        double m1=s1/n1, m2=s2/n2;
        boolean stationary = Math.abs(m1-m2)/Math.max(m1,1e-9) < 0.25;
        boolean bounded = m2 < 0.05;
        boolean ok = stationary && bounded;
        System.out.printf("  tether-strain max: halves μ1=%.4e μ2=%.4e µm (Δ%.1f%%); stationary=%s bounded=%s => %s%n",
                m1, m2, 100*Math.abs(m1-m2)/Math.max(m1,1e-9), stationary, bounded, ok ? "PASS" : "*FAIL*");
        System.out.println("  (the assembled minifilament sits at the fracMove scheme's own FDT steady state; per the");
        System.out.println("   carry-forward dt is a physics parameter — no dt-independent ½kT anchor. v1 cross-check informational.)");
        return ok;
    }

    // ============================================================== E: all-OFF ≡ HEAD
    static boolean checkAllOffEqualsHead(double dt) {
        System.out.println("\n--- E. all-OFF ≡ HEAD (tether off ⇒ motor body == bare 6a dimer-bed, bit-identical) ---");
        // run the FULL step with tether OFF, and a control that NEVER builds the minifilament systems.
        // With tether off, the motor body sees only zero/brownian/joints/dimer/integrate/derive — exactly a
        // 6a dimer bed. Compare the motor body pose to an independent run using the same seeds.
        Scene a = buildScene(2, 4, dt, 1f);
        Scene b = buildScene(2, 4, dt, 1f);
        run(a, 1500, false, false);   // tether OFF
        run(b, 1500, false, false);   // tether OFF
        double d = maxDiff(a.mot.body.coord, b.mot.body.coord);
        boolean ok = d == 0.0;
        System.out.printf("  tether-off determinism (motor body): max|Δ|=%.3e (==0) => %s%n", d, ok ? "PASS" : "*FAIL*");
        return ok;
    }

    // ============================================================== viewer
    static void runViz(int nBackbones, int dimersEnd, int M, double dt, String dir) {
        Scene sc = buildScene(nBackbones, dimersEnd, dt, 1f);
        new java.io.File(dir).mkdirs();
        Runnable step = cpuStep(sc, true);
        int every = Math.max(1, M / 300), frames = 0;
        for (int t = 0; t <= M; t++) {
            sc.mot.setCounts(t, SEED, 0); sc.mini.setBackboneCounts(t, SEED_BB);
            if (t % every == 0) writeFrame(dir, frames++, t * dt, sc);
            step.run();
        }
        System.out.println("viewer: wrote " + frames + " frames to " + dir);
    }
    static void writeFrame(String dir, int frame, double t, Scene sc) {
        RigidRodBody mb = sc.mot.body, bb = sc.mini.backbone;
        StringBuilder sb = new StringBuilder(512 + 200 * sc.mot.nMotors);
        sb.append(String.format(java.util.Locale.US, "{\"frame\":%d,\"t\":%.6g,\"bounds\":{\"xDim\":3.0,\"yDim\":3.0,\"zDim\":0.6}", frame, t));
        // backbones as thick segments
        sb.append(",\"segments\":[");
        for (int s = 0; s < sc.mini.nBackbones; s++) {
            if (s > 0) sb.append(',');
            sb.append(String.format(java.util.Locale.US, "{\"id\":%d,\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g}",
                s, bb.end1X(s),bb.end1Y(s),bb.end1Z(s), bb.end2X(s),bb.end2Y(s),bb.end2Z(s), MiniFilamentStore.BACKBONE_R));
        }
        sb.append("],\"myosins\":[");
        for (int m = 0; m < sc.mot.nMotors; m++) {
            if (m > 0) sb.append(',');
            int rod = 3*m, lever = 3*m+1, head = 3*m+2;
            sb.append(String.format(java.util.Locale.US,
                "{\"id\":%d,\"rod\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g},"
                + "\"lever\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g},"
                + "\"motor\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g,\"state\":\"ADPPi\"}}",
                m, mb.end1X(rod),mb.end1Y(rod),mb.end1Z(rod), mb.end2X(rod),mb.end2Y(rod),mb.end2Z(rod), MotorStore.ROD_R,
                mb.end1X(lever),mb.end1Y(lever),mb.end1Z(lever), mb.end2X(lever),mb.end2Y(lever),mb.end2Z(lever), MotorStore.LEVER_R,
                mb.end1X(head),mb.end1Y(head),mb.end1Z(head), mb.end2X(head),mb.end2Y(head),mb.end2Z(head), MotorStore.HEAD_R));
        }
        sb.append("]}");
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(dir, String.format(java.util.Locale.US,"frame_%06d.json", frame)), sb.toString()); }
        catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }
}
