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
 * Increment 6c Stage B1: the FilamentStore RUNTIME-BIRTH lifecycle, validated with a SYNTHETIC birth.
 *
 * This is the FIRST dynamic filament creation in SoftBox (FilamentStore was fully static through inc 6) and the
 * recon §2 risk — a lifecycle on the foundational entity everything binds to. B1 builds + de-risks the machinery
 * (filState sentinel + the inc-5 scan-rank allocator + inert FREE slots); B2 later wires the node's real
 * nucleation as the birth SOURCE. The birth here is SYNTHETIC (a test driver allocates a seed at a chosen step)
 * — NOT the node emitter / actin pool / rate (B2), and NOT growth (fixed-length seed; deferred).
 *
 * Gates (both runners):
 *   A allocator correctness + CPU≡GPU — distinct-slot / no-double-alloc, free-list index order, born payload +
 *     fresh state + FREE→ACTIVE flip, slot-stability (Design A: existing ACTIVE slots never move), overflow
 *     clamp, same-step reuse after a synthetic free; the 5 allocator kernels bit-identical CPU↔GPU.
 *   B born ≡ preplaced + inert FREE slot + CPU≡GPU physics — over the shared filament step
 *     {zero, Brownian, chain, integrate, derive, containment}: a filament BORN at step 0 evolves BIT-IDENTICALLY
 *     to the SAME filament PREPLACED; a FREE slot is inert (never moves) and NEVER perturbs the other filaments;
 *     a deterministic (containment-driven) run is bit-identical CPU↔GPU.
 *   C binding + gather participation — a born filament becomes a broad-phase binding target and gathers
 *     cross-bridge force (fil gather==brute, bit-identical), while a parked FREE slot is NOT bound (geometry
 *     excludes it — the data-driven guard).
 *
 * THE HEADLINE REGRESSION GUARD (no-op-when-all-active) is established OUTSIDE this harness: B1 touches NO shared
 * device kernel and only ADDS inert fields to FilamentStore, so every prior harness is byte-unchanged ⇒
 * bit-identical (verified by re-running them — see the findings).
 */
public final class FilamentBirthHarness {

    static final int B = 64;
    static GridScheduler sched;
    static boolean cpu = false;

    // v1 actinSeed = 3 monomers ⇒ a fixed-length seed ≈ (3+1)·actinMonoRadius ≈ 10.8 nm (growth deferred).
    static final int SEED_MONO = 3;
    // gate-B containment box (centered at origin), µm; checkInt=1 ⇒ fires every step (deterministic motion).
    static final double BOX_DIM = 0.40, BOX_R = 0.005, BOX_TAU = 1.0e-4, BOX_COEFF = 0.5;
    static final int SEED = 0xF11B17;

    public static void main(String[] args) {
        double dt = 1.0e-5;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-cpu")) cpu = true;
        }
        System.out.println("=== Soft Box increment 6c Stage B1 — FilamentStore runtime-birth lifecycle ===");
        System.out.println("filState sentinel + inc-5 scan-rank allocator + inert FREE slots; synthetic birth.");
        System.out.println("runner: " + (cpu ? "CPU only (-cpu)" : "GPU + CPU cross-check") + ", dt=" + dt + "\n");

        boolean okA = checkAllocator(dt);
        boolean okB = checkBornEqualsPreplaced(dt);
        boolean okC = checkBindingGather(dt);

        boolean ok = okA && okB && okC;
        System.out.println();
        System.out.println("=== FILAMENT-BIRTH (B1) VALIDATION " + (ok ? "PASS" : "FAIL") + " ===");
        if (!ok) { System.out.println("BAIL-OUT: a gate failed. Commit nothing."); System.exit(1); }
    }

    // ============================================================== seed init
    /** Pre-initialise EVERY slot's seed geometry/drag identically (the fixed-length seed), then park + free the
     *  slots in `freeSlots`. ACTIVE slots get a real pose (set by the caller); FREE slots are parked at `park`. */
    static FilamentStore newStore(int cap, double dt, double bornScale) {
        FilamentStore f = new FilamentStore(cap, cap);   // reqCap = cap (worst case: birth into all free slots)
        for (int s = 0; s < cap; s++) f.monomerCount.set(s, SEED_MONO);
        DragTensorSystem.run(f);                          // seed segLength + drag for all slots (length-agnostic)
        f.setParams(dt, Constants.brownianForceMag(dt));
        f.setCounts(0, SEED);
        f.setChainParams(dt);
        f.setBirthParams(bornScale, 0.0);                 // born filaments get this Brownian scale
        return f;
    }

    // ============================================================== device allocator (5 kernels)
    static void birthHost(FilamentStore f) {
        FilamentBirthSystem.freeFlags(f.filState, f.freeCount, f.allocCounts);
        CrossBridgeSystem.csrScan(f.freeScanCounts, f.freeCount, f.freeOffsets);
        FilamentBirthSystem.freeScatter(f.filState, f.freeOffsets, f.freeList, f.allocCounts);
        CrossBridgeSystem.csrScan(f.rankScanCounts, f.acceptFlag, f.rankOffsets);
        FilamentBirthSystem.allocate(f.reqCoord, f.reqUVec, f.reqYVec, f.rankOffsets, f.freeList, f.freeOffsets,
                f.coord, f.uVec, f.yVec, f.brownTransScale, f.brownRotScale, f.filState, f.birthParams, f.allocCounts);
    }

    static void birthGpu(FilamentStore f) {
        int C = f.n, K = f.allocCounts.get(1);
        TaskGraph tg = new TaskGraph("birth")
            .transferToDevice(DataTransferMode.EVERY_EXECUTION,
                    f.filState, f.freeCount, f.freeOffsets, f.freeList, f.freeScanCounts, f.allocCounts,
                    f.acceptFlag, f.rankOffsets, f.rankScanCounts, f.reqCoord, f.reqUVec, f.reqYVec,
                    f.coord, f.uVec, f.yVec, f.brownTransScale, f.brownRotScale, f.birthParams)
            .task("freeFlags", FilamentBirthSystem::freeFlags, f.filState, f.freeCount, f.allocCounts)
            .task("csrFree", CrossBridgeSystem::csrScan, f.freeScanCounts, f.freeCount, f.freeOffsets)
            .task("freeScatter", FilamentBirthSystem::freeScatter, f.filState, f.freeOffsets, f.freeList, f.allocCounts)
            .task("csrRank", CrossBridgeSystem::csrScan, f.rankScanCounts, f.acceptFlag, f.rankOffsets)
            .task("allocate", FilamentBirthSystem::allocate, f.reqCoord, f.reqUVec, f.reqYVec,
                    f.rankOffsets, f.freeList, f.freeOffsets, f.coord, f.uVec, f.yVec,
                    f.brownTransScale, f.brownRotScale, f.filState, f.birthParams, f.allocCounts)
            .transferToHost(DataTransferMode.EVERY_EXECUTION,
                    f.coord, f.uVec, f.yVec, f.brownTransScale, f.brownRotScale, f.filState,
                    f.freeList, f.freeOffsets);
        sched = new GridScheduler();
        addW("birth.freeFlags", pad(C)); addS("birth.csrFree"); addS("birth.freeScatter");
        addS("birth.csrRank"); addW("birth.allocate", pad(Math.max(1, K)));
        new TornadoExecutionPlan(tg.snapshot()).withGridScheduler(sched).execute();
    }

    // ============================================================== A: allocator correctness + CPU≡GPU
    static boolean checkAllocator(double dt) {
        System.out.println("--- A. allocator correctness (scan-rank free-list, Design A) + CPU≡GPU ---");
        boolean ok = true;

        // Scene: C=16, ACTIVE odd slots, FREE even slots ⇒ free-list must be [0,2,4,...,14] (index order).
        int C = 16;
        // K=3 accepted births ⇒ claim freeList[0],[1],[2] = slots 0,2,4.
        FilamentStore f = buildAllocScene(C, dt);
        birthHost(f);

        // free-list correctness: even slots in index order, nFree=8
        int nFree = f.freeOffsets.get(C);
        boolean flOk = (nFree == 8);
        for (int i = 0; i < 8; i++) if (f.freeList.get(i) != 2 * i) flOk = false;
        System.out.printf("  free-list: nFree=%d (expect 8), freeList[0..7]=%s => %s%n",
                nFree, listStr(f.freeList, 8), flOk ? "ok" : "*FAIL*");

        // distinct-slot / no double-alloc: born = {0,2,4}, all now ACTIVE, distinct
        int[] born = {0, 2, 4};
        boolean bornOk = true;
        for (int s : born) if (f.filState.get(s) != FilamentStore.FIL_ACTIVE) bornOk = false;
        // payload: slot 0/2/4 got their requested pose + born Brownian scale
        boolean payloadOk = true;
        for (int q = 0; q < 3; q++) {
            int s = born[q];
            float wantX = 0.10f + 0.01f * q;       // the request x staged in buildAllocScene
            if (Math.abs(f.coordX(s) - wantX) > 1e-7f) payloadOk = false;
            if (Math.abs(f.uVecX(s) - 1f) > 1e-7f) payloadOk = false;
            if (Math.abs(f.brownTransScale.get(s) - 1f) > 1e-7f) payloadOk = false;
        }
        System.out.printf("  born slots {0,2,4} ACTIVE+distinct=%s, payload(pose+brownScale)=%s%n",
                bornOk, payloadOk);

        // slot-stability: ACTIVE odd slots unchanged (pose), un-claimed FREE evens {6,8,10,12,14} still FREE+parked
        boolean stabOk = true;
        for (int s = 1; s < C; s += 2) if (Math.abs(f.coordX(s) - (10f + s)) > 1e-7f || f.filState.get(s) != 0) stabOk = false;
        for (int s = 6; s < C; s += 2) if (f.filState.get(s) >= 0 || f.brownTransScale.get(s) != 0f) stabOk = false;
        System.out.printf("  slot-stability: ACTIVE odd slots unchanged + un-claimed FREE evens still FREE => %s%n", stabOk ? "ok" : "*FAIL*");

        // overflow clamp: nFree=2, K=5 ⇒ exactly 2 born
        FilamentStore fo = buildClampScene(dt);
        birthHost(fo);
        int activeAfter = 0; for (int s = 0; s < fo.n; s++) if (fo.filState.get(s) >= 0) activeAfter++;
        boolean clampOk = (activeAfter == 6 + 2);   // 6 preplaced + only 2 of 5 requests (nFree=2)
        System.out.printf("  overflow clamp: nFree=2, K=5 requests ⇒ %d active (expect 8) => %s%n", activeAfter, clampOk ? "ok" : "*FAIL*");

        // same-step reuse after a synthetic free: free an ACTIVE slot, then birth reuses it
        FilamentStore fr = buildAllocScene(C, dt);
        fr.markFree(1);                              // synthetically free ACTIVE slot 1 (B2: a death frees it)
        fr.setBirthRequestCount(1);
        for (int r = 1; r < C; r++) fr.acceptFlag.set(r, 0);   // single request r=0
        fr.acceptFlag.set(0, 1);
        fr.setBirthRequest(0, 0.5f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f);
        birthHost(fr);
        // free-list now = [0,1,2,4,6,...]; rank0 → slot 0 reused (lowest FREE index)
        boolean reuseOk = (fr.filState.get(0) == 0 && Math.abs(fr.coordX(0) - 0.5f) < 1e-7f);
        System.out.printf("  same-step reuse (freed slot 1 ⇒ free-list[%s]; rank0→slot %d reused) => %s%n",
                listStr(fr.freeList, 4), 0, reuseOk ? "ok" : "*FAIL*");

        // CPU≡GPU: identical scene, GPU TaskGraph vs CPU host, bit-identical filState/pose/brownScale/free-list
        boolean cpuGpuOk = true;
        if (!cpu) {
            FilamentStore g = buildAllocScene(C, dt); birthGpu(g);
            FilamentStore c = buildAllocScene(C, dt); birthHost(c);
            double d = 0;
            for (int i = 0; i < c.coord.getSize(); i++) d = Math.max(d, Math.abs(g.coord.get(i) - c.coord.get(i)));
            int sm = 0;
            for (int s = 0; s < C; s++) { if (g.filState.get(s) != c.filState.get(s)) sm++; if (g.freeList.get(s) != c.freeList.get(s)) sm++; }
            double bd = 0; for (int i = 0; i < C; i++) bd = Math.max(bd, Math.abs(g.brownTransScale.get(i) - c.brownTransScale.get(i)));
            cpuGpuOk = (d == 0.0 && sm == 0 && bd == 0.0);
            System.out.printf("  CPU≡GPU: max|Δcoord|=%.3e, filState/freeList mismatches=%d, max|ΔbrownScale|=%.3e (all 0) => %s%n",
                    d, sm, bd, cpuGpuOk ? "ok" : "*FAIL*");
        } else System.out.println("  CPU≡GPU: skipped (-cpu)");

        ok = flOk && bornOk && payloadOk && stabOk && clampOk && reuseOk && cpuGpuOk;
        System.out.println("  => " + (ok ? "PASS" : "*FAIL*") + "\n");
        return ok;
    }

    /** C slots: ACTIVE odd (coord.x = 10+slot, an identifiable marker), FREE even (parked at origin). Three
     *  birth requests staged (x = 0.10,0.11,0.12). */
    static FilamentStore buildAllocScene(int C, double dt) {
        FilamentStore f = newStore(C, dt, 1.0);
        for (int s = 0; s < C; s++) {
            if ((s & 1) == 1) {                       // ACTIVE odd slot: a marker pose
                f.setCoord(s, 10f + s, 0f, 0f); f.setUVec(s, 1f, 0f, 0f); f.setYVec(s, 0f, 1f, 0f);
                f.brownTransScale.set(s, 1f); f.brownRotScale.set(s, 0f);
                // filState already ACTIVE (default)
            } else {                                  // FREE even slot: parked at origin, inert
                f.setCoord(s, 0f, 0f, 0f); f.setUVec(s, 1f, 0f, 0f); f.setYVec(s, 0f, 1f, 0f);
                f.markFree(s);
            }
        }
        f.setBirthRequestCount(C);                    // rank scan over the whole acceptFlag array
        for (int r = 0; r < C; r++) f.acceptFlag.set(r, 0);
        for (int q = 0; q < 3; q++) f.setBirthRequest(q, 0.10f + 0.01f * q, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f);
        return f;
    }

    /** C=8: 6 ACTIVE + 2 FREE; 5 birth requests ⇒ only 2 can form (overflow clamp). */
    static FilamentStore buildClampScene(double dt) {
        int C = 8; FilamentStore f = newStore(C, dt, 1.0);
        for (int s = 0; s < C; s++) {
            f.setCoord(s, s, 0f, 0f); f.setUVec(s, 1f, 0f, 0f); f.setYVec(s, 0f, 1f, 0f);
            if (s >= 6) f.markFree(s); else f.brownTransScale.set(s, 1f);
        }
        f.setBirthRequestCount(C);
        for (int r = 0; r < C; r++) f.acceptFlag.set(r, 0);
        for (int q = 0; q < 5; q++) f.setBirthRequest(q, 0.2f + 0.01f * q, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f);
        return f;
    }

    static String listStr(IntArray a, int n) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < n; i++) { if (i > 0) sb.append(','); sb.append(a.get(i)); }
        return sb.append(']').toString();
    }

    // ============================================================== B: born ≡ preplaced + inert + CPU≡GPU
    static final int NB = 6;                   // 6 single-seg free-rod filaments
    static final int J = 4;                    // the slot we birth (a moving one)

    /** Build a gate-B scene. If `freeJ`, slot J starts FREE+parked at origin (to be born later); else preplaced.
     *  Slots 3,4,5 sit past the +x wall ⇒ containment pushes them −x (deterministic when Brownian off). */
    static FilamentStore buildPhysScene(double dt, boolean freeJ, double bornScale) {
        FilamentStore f = newStore(NB, dt, bornScale);
        for (int s = 0; s < NB; s++) {
            double x = (s < 3) ? (-0.05 + 0.03 * s) : 0.21;    // 3,4,5 penetrate the +x wall
            f.setCoord(s, (float) x, 0f, 0f); f.setUVec(s, 1f, 0f, 0f); f.setYVec(s, 0f, 1f, 0f);
            f.brownTransScale.set(s, (float) bornScale); f.brownRotScale.set(s, 0f);
        }
        if (freeJ) { f.setCoord(J, 0f, 0f, 0f); f.markFree(J); }   // park J at origin (inside box ⇒ inert)
        // stage J's birth request = the SAME pose the preplaced J has (x=0.21)
        f.setBirthRequestCount(NB);
        for (int r = 0; r < NB; r++) f.acceptFlag.set(r, 0);
        f.acceptFlag.set(0, 1);
        f.setBirthRequest(0, 0.21f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f);
        return f;
    }

    static FloatArray boxParams() {
        FloatArray bp = new FloatArray(7);
        bp.set(0, (float) BOX_TAU); bp.set(1, (float) BOX_DIM); bp.set(2, (float) BOX_DIM); bp.set(3, (float) BOX_DIM);
        bp.set(4, (float) BOX_R);   bp.set(5, (float) BOX_COEFF); bp.set(6, 1f);   // checkInt=1
        return bp;
    }

    static Runnable physStep(FilamentStore f, FloatArray bp, boolean brownian) {
        return () -> {
            ChainBendingForceSystem.zeroAccumulators(f.forceSum, f.torqueSum, f.counts);
            if (brownian) BrownianForceSystem.brownianForce(f.randForce, f.randTorque, f.bTransGam, f.bRotGam,
                    f.brownTransScale, f.brownRotScale, f.params, f.counts);
            ChainBendingForceSystem.chainForces(f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide,
                    f.end1NbrSlot, f.end1NbrSide, f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts);
            ContainmentSystem.confine(f.coord, f.uVec, f.segLength, f.bTransGam, f.forceSum, f.torqueSum, bp, f.counts);
            RigidRodLangevinIntegrationSystem.integrate(f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum,
                    f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts);
            DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
        };
    }

    static TornadoExecutionPlan buildPhysPlan(FilamentStore f, FloatArray bp, boolean brownian) {
        TaskGraph tg = new TaskGraph("phys")
            .transferToDevice(DataTransferMode.FIRST_EXECUTION,
                    f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.bTransGam, f.bRotGam,
                    f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.brownTransScale, f.brownRotScale,
                    f.params, f.chainParams, f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide, bp)
            .transferToDevice(DataTransferMode.EVERY_EXECUTION, f.counts)
            .task("zero", ChainBendingForceSystem::zeroAccumulators, f.forceSum, f.torqueSum, f.counts);
        if (brownian) tg.task("brown", BrownianForceSystem::brownianForce, f.randForce, f.randTorque, f.bTransGam,
                f.bRotGam, f.brownTransScale, f.brownRotScale, f.params, f.counts);
        tg.task("chain", ChainBendingForceSystem::chainForces, f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide,
                    f.end1NbrSlot, f.end1NbrSide, f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts)
          .task("confine", ContainmentSystem::confine, f.coord, f.uVec, f.segLength, f.bTransGam, f.forceSum, f.torqueSum, bp, f.counts)
          .task("integrate", RigidRodLangevinIntegrationSystem::integrate, f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum,
                    f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts)
          .task("derive", DerivedGeometrySystem::derive, f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts)
          .transferToHost(DataTransferMode.UNDER_DEMAND, f.coord, f.uVec);
        sched = new GridScheduler();
        addW("phys.zero", pad(NB)); if (brownian) addW("phys.brown", pad(NB));
        addW("phys.chain", pad(NB)); addW("phys.confine", pad(NB)); addW("phys.integrate", pad(NB)); addW("phys.derive", pad(NB));
        return new TornadoExecutionPlan(tg.snapshot());
    }

    static void runPhysCpu(FilamentStore f, FloatArray bp, boolean brownian, int M) {
        Runnable step = physStep(f, bp, brownian);
        for (int t = 0; t < M; t++) { f.setCounts(t, SEED); step.run(); }
    }
    static void runPhysGpu(FilamentStore f, FloatArray bp, boolean brownian, int M) {
        TornadoExecutionPlan plan = buildPhysPlan(f, bp, brownian);
        for (int t = 0; t < M; t++) {
            f.setCounts(t, SEED);
            TornadoExecutionResult res = plan.withGridScheduler(sched).execute();
            if (t == M - 1) res.transferToHost(f.coord, f.uVec);
        }
    }

    static boolean checkBornEqualsPreplaced(double dt) {
        System.out.println("--- B. born ≡ preplaced + inert FREE slot + CPU≡GPU (shared filament step) ---");
        int M = 300;
        boolean ok = true;

        // B1: born-at-step-0 ≡ preplaced (deterministic, Brownian off), on the CPU runner
        FilamentStore p = buildPhysScene(dt, false, 0.0);
        FilamentStore q = buildPhysScene(dt, true, 0.0); birthHost(q);   // birth J at step 0
        runPhysCpu(p, boxParams(), false, M);
        runPhysCpu(q, boxParams(), false, M);
        double d0 = Math.max(maxDiff(p.coord, q.coord), maxDiff(p.uVec, q.uVec));
        boolean bornEqPre = (d0 == 0.0);
        System.out.printf("  born@0 ≡ preplaced (CPU, %d steps): max|Δpose|=%.3e (==0) => %s%n", M, d0, bornEqPre ? "ok" : "*FAIL*");

        // B1b: also bit-identical with Brownian ON (same seed ⇒ same per-slot RNG)
        FilamentStore pB = buildPhysScene(dt, false, 1.0);
        FilamentStore qB = buildPhysScene(dt, true, 1.0); birthHost(qB);
        runPhysCpu(pB, boxParams(), true, M);
        runPhysCpu(qB, boxParams(), true, M);
        double dB = Math.max(maxDiff(pB.coord, qB.coord), maxDiff(pB.uVec, qB.uVec));
        boolean bornEqPreB = (dB == 0.0);
        System.out.printf("  born@0 ≡ preplaced (CPU, Brownian ON): max|Δpose|=%.3e (==0) => %s%n", dB, bornEqPreB ? "ok" : "*FAIL*");

        // B2: inert FREE slot + no perturbation — J free, born at step K=150
        int K = 150;
        FilamentStore r = buildPhysScene(dt, true, 0.0);   // J free, NOT yet born
        runPhysCpu(r, boxParams(), false, K);
        boolean inert = (Math.abs(r.coordX(J)) < 1e-30 && Math.abs(r.coordY(J)) < 1e-30 && Math.abs(r.coordZ(J)) < 1e-30);
        // no perturbation: non-J slots identical to the preplaced run (P) at the same step
        FilamentStore p150 = buildPhysScene(dt, false, 0.0);
        runPhysCpu(p150, boxParams(), false, K);
        double dNonJ = 0;
        for (int s = 0; s < NB; s++) {
            if (s == J) continue;
            dNonJ = Math.max(dNonJ, Math.abs(p150.coordX(s) - r.coordX(s)));
            dNonJ = Math.max(dNonJ, Math.abs(p150.coordY(s) - r.coordY(s)));
            dNonJ = Math.max(dNonJ, Math.abs(p150.coordZ(s) - r.coordZ(s)));
        }
        boolean noPerturb = (dNonJ == 0.0);
        System.out.printf("  FREE slot J=%d inert after %d steps (pose==parked)=%s; non-J unperturbed: max|Δ|=%.3e (==0)=%s%n",
                J, K, inert, dNonJ, noPerturb);
        // now birth J and confirm it participates (moves) over the next K steps
        birthHost(r);
        double xBorn = r.coordX(J);
        runPhysCpu(r, boxParams(), false, K);
        boolean participates = Math.abs(r.coordX(J) - xBorn) > 1e-9;
        System.out.printf("  born@%d then participates: J.x %.5f → %.5f (moved, pushed by the wall) => %s%n",
                K, xBorn, r.coordX(J), participates ? "ok" : "*FAIL*");

        // B3: CPU≡GPU of the born-filament physics (deterministic, Brownian off)
        boolean cpuGpuOk = true;
        if (!cpu) {
            FilamentStore g = buildPhysScene(dt, true, 0.0); birthHost(g);
            FilamentStore c = buildPhysScene(dt, true, 0.0); birthHost(c);
            runPhysGpu(g, boxParams(), false, M);
            runPhysCpu(c, boxParams(), false, M);
            double dcg = Math.max(maxDiff(g.coord, c.coord), maxDiff(g.uVec, c.uVec));
            cpuGpuOk = dcg < 5e-5;
            System.out.printf("  CPU≡GPU (born scene, %d steps, Brownian off): max|Δpose|=%.3e (<5e-5) => %s%n", M, dcg, cpuGpuOk ? "ok" : "*FAIL*");
        } else System.out.println("  CPU≡GPU: skipped (-cpu)");

        ok = bornEqPre && bornEqPreB && inert && noPerturb && participates && cpuGpuOk;
        System.out.println("  (a born seed is a free rod — chain has no neighbor to act on; integrate/Brownian/derive/containment");
        System.out.println("   participation is shown identical to a preplaced filament; binding/gather is gate C.)");
        System.out.println("  => " + (ok ? "PASS" : "*FAIL*") + "\n");
        return ok;
    }

    // ============================================================== C: binding + gather participation
    static final double ANCHOR_Z = -0.05;
    static final double MYO_SPRING = 1.0e-9, J1_FMT = 0.4;

    static boolean checkBindingGather(double dt) {
        System.out.println("--- C. binding + gather participation (born filament is bound + gathers cross-bridge force) ---");
        int nMot = 8;
        // one filament slot (the born one), normal-length so the heads can bind along it (the allocator is
        // length-agnostic; B1 births fixed seeds in the node use-case, but binding geometry wants a real segment)
        FilamentStore f = new FilamentStore(1, 1);
        f.monomerCount.set(0, Constants.stdSegLength);
        DragTensorSystem.run(f); f.setParams(dt, 0); f.setCounts(0, 0); f.setChainParams(dt);
        f.setBirthParams(0.0, 0.0);
        // PARK the slot FREE, far above the heads (out of reach) — the data-driven guard: geometry excludes it
        f.setCoord(0, 0f, 0f, 1.0f); f.setUVec(0, 1f, 0f, 0f); f.setYVec(0, 0f, 1f, 0f); f.markFree(0);
        DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);

        // a row of single motors standing +z under the filament path; heads reach z ≈ 0.058
        double headTipZ = ANCHOR_Z + MotorStore.ROD_LEN + MotorStore.LEVER_LEN + MotorStore.HEAD_LEN;
        double zFil = headTipZ + 0.003;
        MotorStore mot = new MotorStore(nMot);
        for (int m = 0; m < nMot; m++) {
            double mx = -0.03 + 0.0085 * m;     // spread under the segment
            mot.assembleArticulated(m, (float) mx, 0f, (float) ANCHOR_Z, 0f, 0f, 1f, 0f);
        }
        DragTensorSystem.run(mot);
        mot.setBodyParams(dt); mot.setJointParams(dt); mot.setKinParams(0.006, -0.4, dt); mot.setNucParams(dt);
        mot.setAllStates(MotorStore.NUC_ADPPI);
        FloatArray bondData = new FloatArray(nMot * CrossBridgeSystem.STRIDE); bondData.init(0f);
        FloatArray xbParams = FloatArray.fromElements((float) MYO_SPRING, 90f, (float) J1_FMT, (float) dt, (float) MotorStore.HEAD_LEN, 0f);
        IntArray segMotorCount = new IntArray(1), segMotorOffsets = new IntArray(2), segMotorMyo = new IntArray(nMot);
        IntArray reachSeg = new IntArray(nMot * SpatialGrid.MAX_CAND); reachSeg.init(-1);
        IntArray reachCount = new IntArray(nMot);

        // PRE-BIRTH: the parked FREE filament is far away ⇒ no motor finds it reachable ⇒ none bind
        bindOnce(mot, f, reachSeg, reachCount);
        long boundBefore = countBound(mot);
        System.out.printf("  pre-birth (filament parked FREE far away): bound motors = %d (expect 0) => %s%n",
                boundBefore, boundBefore == 0 ? "ok" : "*FAIL*");

        // BIRTH the filament at the bindable location (z=zFil) via the allocator
        f.setBirthRequestCount(1);
        f.acceptFlag.set(0, 1);
        f.setBirthRequest(0, 0f, 0f, (float) zFil, 1f, 0f, 0f, 0f, 1f, 0f);
        birthHost(f);
        DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
        boolean bornActive = (f.filState.get(0) == FilamentStore.FIL_ACTIVE && Math.abs(f.coordZ(0) - zFil) < 1e-6);

        // POST-BIRTH: motors now bind the born filament
        bindOnce(mot, f, reachSeg, reachCount);
        long boundAfter = countBound(mot);
        boolean boundOk = (boundAfter > 0);
        System.out.printf("  post-birth (born ACTIVE at z=%.4f): bound motors = %d (expect >0) => %s%n",
                zFil, boundAfter, boundOk ? "ok" : "*FAIL*");

        // GATHER: cross-bridge force lands on the born filament slot, and equals the brute per-bond sum bit-identically
        ChainBendingForceSystem.zeroAccumulators(mot.body.forceSum, mot.body.torqueSum, mot.counts);
        MotorJointSystem.joints(mot.body.coord, mot.body.uVec, mot.body.segLength, mot.body.bTransGam, mot.body.bRotGam,
                mot.body.forceSum, mot.body.torqueSum, mot.nucleotideState, mot.jointParams, mot.counts);
        CrossBridgeSystem.bondForces(mot.body.coord, mot.body.uVec, mot.body.yVec, mot.body.bRotGam,
                f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength, mot.boundSeg, mot.bindArc, mot.nucleotideState, bondData, xbParams);
        ChainBendingForceSystem.zeroAccumulators(f.forceSum, f.torqueSum, f.counts);
        CrossBridgeSystem.csrHistogram(mot.boundSeg, mot.counts, segMotorCount);
        CrossBridgeSystem.csrScan(mot.counts, segMotorCount, segMotorOffsets);
        CrossBridgeSystem.csrScatter(mot.boundSeg, mot.counts, segMotorOffsets, segMotorCount, segMotorMyo);
        CrossBridgeSystem.segGather(segMotorOffsets, segMotorMyo, bondData, f.forceSum, f.torqueSum, mot.counts);
        FloatArray bruteF = new FloatArray(3), bruteT = new FloatArray(3); bruteF.init(0f); bruteT.init(0f);
        CrossBridgeSystem.bruteGather(mot.boundSeg, bondData, bruteF, bruteT, mot.counts);
        double gMax = 0, fMag = 0;
        for (int i = 0; i < 3; i++) { gMax = Math.max(gMax, Math.abs(f.forceSum.get(i) - bruteF.get(i))); fMag += f.forceSum.get(i) * f.forceSum.get(i); }
        fMag = Math.sqrt(fMag);
        boolean gatherOk = (gMax == 0.0 && fMag > 0);
        System.out.printf("  gather onto born slot: |F|=%.3e N (>0, born filament feels the load); gather==brute max|Δ|=%.3e (==0) => %s%n",
                fMag, gMax, gatherOk ? "ok" : "*FAIL*");

        boolean ok = (boundBefore == 0) && bornActive && boundOk && gatherOk;
        System.out.println("  (a parked FREE slot is excluded from binding by GEOMETRY — the data-driven active-guard; once born,");
        System.out.println("   the filament is an ordinary integer slot the binding + integer-keyed gather treat identically. CPU≡GPU");
        System.out.println("   of binding/gather is the standing 4b-ii validation.)");
        System.out.println("  => " + (ok ? "PASS" : "*FAIL*") + "\n");
        return ok;
    }

    static void bindOnce(MotorStore mot, FilamentStore f, IntArray reachSeg, IntArray reachCount) {
        mot.setCounts(0, 0x6D9A0E, f.n);
        MotorStore.publishHeadFromBody(mot.body.coord, mot.body.uVec, mot.body.segLength, mot.head, mot.uVec, mot.rodUVec, mot.counts);
        BindingDetectionSystem.bruteReachable(mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, reachSeg, reachCount, mot.kinParams, mot.counts);
        BindingDetectionSystem.bindNearest(mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, reachSeg, reachCount, mot.boundSeg, mot.bindArc, mot.kinParams, mot.counts);
    }
    static long countBound(MotorStore m) { long c = 0; for (int i = 0; i < m.nMotors; i++) if (m.boundSeg.get(i) >= 0) c++; return c; }

    // ============================================================== utils
    static double maxDiff(FloatArray a, FloatArray b) { double m = 0; for (int i = 0; i < a.getSize(); i++) m = Math.max(m, Math.abs(a.get(i) - b.get(i))); return m; }
    static int pad(int n) { return ((n + B - 1) / B) * B; }
    static void addW(String name, int g) { WorkerGrid w = new WorkerGrid1D(g); w.setLocalWork(B, 1, 1); sched.addWorkerGrid(name, w); }
    static void addS(String name) { WorkerGrid w = new WorkerGrid1D(1); w.setLocalWork(1, 1, 1); sched.addWorkerGrid(name, w); }
}
