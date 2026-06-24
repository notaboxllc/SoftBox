package softbox;

import uk.ac.manchester.tornado.api.GridScheduler;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.TornadoExecutionResult;
import uk.ac.manchester.tornado.api.WorkerGrid;
import uk.ac.manchester.tornado.api.WorkerGrid1D;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.enums.ProfilerMode;
import uk.ac.manchester.tornado.api.TornadoProfilerResult;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * FULL-SYSTEM DEMONSTRATION — a mid-sized, biochemically-active contractile network in a shallow in-vitro
 * chamber, the MAXIMAL composition of every validated SoftBox subsystem, run to WATCH + HUNT FOR ABERRATIONS
 * before the ring. NOT a precise validation; an integration demo + a pathology sweep.
 *
 * THE SCENE (all in one shallow square box — wide x,y, shallow z — a thin slab viewed top-down):
 *   - A distribution of protein NODES (a planar grid in xy), each radial myosin shell + 4–6 random FORMINS.
 *   - The formin filaments are BIOCHEMICALLY ACTIVE + formin-PINNED: growth + pointed depoly + AGING (the
 *     ATP→ADP cascade) + cofilin SEVERING. Faithful (KIN=1) rates by default.
 *   - Free myosin MINIFILAMENTS (tens–low-hundreds): bipolar backbones owning splayed dimers, binding +
 *     contracting the filament network (parallel-grid fused binding + cross-bridge + the single-ended gather).
 *   - CROSSLINKERS: the O(N) grid FORMATION (the 5d fix) + force + torsion + 2-pass gather — bundling.
 *   - The general in-vitro CHAMBER (ContainmentSystem) confines node bodies, minifilament backbones, filaments.
 *   - The dead-slot family (initNewborn + nucleateFreshAtp + nucleateFreshCofilin) keeps the slot recycle clean.
 *
 * PURE COMPOSITION — NO new force law / gather / shared-kernel edit. Every system reused byte-unchanged; this
 * harness only WIRES them (the Ring3x3 node+turnover+nucleation+containment loop + the DenseContractile free-
 * minifilament block + the O(N) crosslinker formation from CrosslinkerBundleHarness, all onto ONE shared
 * FilamentStore whose forceSum every coupling accumulates into). New file only.
 *
 * BINDING — the two myosin populations bind the SAME filament network two ways:
 *   - the NODE shell uses the node-AWARE brute (v1 excludes a node-held tip seedNode>=0 from ALL myosin binding);
 *   - the FREE minifilaments use the parallel-grid fused per-head query (the dense-scale path; NOT node-aware —
 *     a free minifilament may bind a node-held tip, a minor faithfulness gap flagged in the findings).
 *
 * KIN=1 (faithful) by default — the aberration hunt wants faithful dynamics. At KIN=1, per
 * INC7_RING_3x3_TURNOVER §11, turnover is slow vs the mechanical clock: growth + contraction + crosslinking
 * show in ~0.3–1 s; aging by ~4 s; severing ~6–7 s. -kin K (e.g. 3–5) is a viewing-speed knob.
 *
 * HUNT (reported, never silent): NaN/blow-up, conservation drift, phantoms (zero-length/stale newborns),
 * runaway clustering/wind-down, crosslinker over-forming/spanning, filaments through walls, node clipping,
 * binding anomalies. SANITY: conservation EXACT (the integer pool ledger), 0 phantoms, no crash/race
 * (CPU + the device-resident GPU graph), CPU≡GPU aggregate-agree (the §8 chaotic-many-body standard).
 */
public final class FullSystemDemoHarness {

    static final int B = 64;
    static GridScheduler sched;
    static final double GOLDEN = 2.399963229728653;
    static final double SIN80 = Math.sin(Math.toRadians(80.0)), COS80 = Math.cos(Math.toRadians(80.0));

    // ---- seeds (distinct per store) ----
    static final int SEED      = 0x0F0501;   // node-shell motors / filaments
    static final int SEED_NODE = 0x5C2FAA;   // node bodies
    static final int SEED_MINI = 0x4D1217;   // free-minifilament motors
    static final int SEED_BB   = 0x5C2F11;   // minifilament backbones

    // ---- myosin / binding (shared) ----
    static final double MYO_SPRING = 1.0e-9, J1_FMT = 0.4;
    static final double REACH = 0.025, ALIGN_TOL = -0.4, KOFF = 100.0;
    static final double BROWN_TRANS = 1.0, BROWN_ROT = 0.3;
    static double NODE_BROWN = 0.03;

    // ---- the shallow chamber (a thin slab viewed top-down) ----
    static double BOX_XY = 3.6;              // wide square side (µm) — -boxxy (node brushes sit off the walls)
    static double BOX_Z  = 0.5;              // shallow depth (µm)    — -boxz
    static double PLANE_BIAS = 0.18;         // formin out-of-plane (z) compression — keeps the brush in the slab

    // ---- the node net (planar grid in xy) ----
    static int GX = 4, GY = 4;               // node grid (GX*GY nodes) — -gx -gy
    static double SPACING = 0.6;             // node nearest-neighbour spacing (µm) — -spacing
    static int FORMINS = 6;                  // formins/node (4–6) — -formins
    static int N_SING = 6, N_DIM = 6;        // node shell radial singlets + dimers
    static int FIL_CAP = 1536;               // FilamentStore capacity (bounds run length) — -cap
    static int SEG_MONO = 30;                // monomers per warm-start segment (< 64 split threshold)
    static int WARM_CHAIN = 0;               // segments per warm chain (computed from reach; -warmchain overrides)
    static double OVERSHOOT = 1.0;           // warm reach target = OVERSHOOT*spacing (filament fields overlap neighbours)

    // ---- free minifilaments ----
    static int N_MINI = 60;                  // free minifilament backbones (tens–low-hundreds) — -mini
    static final int DIMERS_END = MiniFilamentStore.DIMERS_EACH_END;   // 8 ⇒ 16 dimers / 32 heads each

    // ---- crosslinkers (O(N) formation) ----
    static boolean XLINK_ON = true;          // -noxlink
    static final double REST_LEN = 0.0125, FRAC_MOVE = 0.4;
    static final double OFF_CONST = 1.0, OFF_COEFF = 1.0, OFF_EXP = 2.0;
    static final double FIL_TORQ_SPRING = 1.0e-19;
    static final double GRAB_DIST = 2.0 * Constants.actinMonoDiam;
    static final double MIN_SEP = 5.0 * Constants.actinMonoDiam;
    static final double MIN_FILLINK_SEP = 2.0 * Constants.actinMonoDiam;
    static final int    MAX_LINKS_ON_SEG = 10;
    static final double XLINK_ON_RATE = 10.0;
    static double XLINK_CONC = 1.0;          // -xlconc
    static final double XL_MAX_ANGLE = 0.6;  // rad (dense-config aperture)
    static int XL_CHECK_INT = 100;           // formation cadence (steps) — pForm = 1-exp(-kon*conc*dt*checkInt)
    static float XL_PFORM = 0;               // the real per-cadence P_form (set in buildCrosslinkers); device fdXForm toggles formParams[4] between this (on cadence) and 0 (off)

    // ---- turnover (the ring-relevant formin-pinned mode) ----
    static boolean AGING_ON = true;          // -noaging
    static boolean SEVER_ON = true;          // -nosever
    static double COF_RATIO = 0.5;           // cofilinRatio dissolve threshold — -cofratio
    static double KIN = 1.0;                 // biochem kinetic-speedup (1 = faithful) — -kin
    static int POLYBOOST = 1;                // monomers/cadence at the barbed tip — -polyboost
    static double POOL0_UM = -1;             // initial [actin] µM (<0 = C_c default) — -pool
    static double NUCBOOST = 1.0;            // formin nucleation-rate multiplier — -nucboost

    static final double AETA = 1.0;          // crowded-cytoplasm viscosity (dense fixture; drag-scaled, FDT-consistent)

    static final double K_ON  = Constants.kATPOn2WithFormin;   // barbed on-rate at a formin
    static final double K_OFF = Constants.kATPOff1;            // ATP pointed-off
    static final double K_OFF_ADP = Constants.kADPOff1;        // ADP pointed-off
    static final double C_C_EFF       = ((double) Constants.stdSegLength / (Constants.stdSegLength - (Constants.actinSeed - 1))) * (K_OFF / K_ON);
    static final double C_C_EFF_AGING = ((double) Constants.stdSegLength / (Constants.stdSegLength - (Constants.actinSeed - 1))) * (K_OFF_ADP / K_ON);

    static double uMper, BOX_VOL;
    static boolean gpuScale = false;
    static int STEPS = 30000;
    static String vizDir = null;
    static boolean smoke = false;
    static boolean filidCheck = false;   // -filidcheck : gate-1 — FilIDSystem ≡ reference chain-walk across turnover
    static int gpuSteps = 2000;               // -gpusteps : device-resident SPLIT probe/validation horizon
    static int N_SPLIT = 6;                    // chained device graphs (fdTurnFire·fdNuc·fdBind·fdStruct·fdFil·fdInteg)
    static boolean overnight = false;          // -overnight : Stage 2 device-resident milestone + scale-up run
    static String overnightViz = null;         // -overnight render dir (frames dumped from the device-resident run)

    // ---- profiling (MEASUREMENT-ONLY; additive, removable; production path untouched) ----
    static boolean profile = false;            // -profile : per-step time/transfer budget via the TornadoVM profiler
    static boolean frozen  = false;            // -frozen  : growth-cap control — freeze turnover (fires=0) + nuc rate 0
    static int profWarm  = 100;                // -profwarm  : warmup steps (FIRST_EXECUTION uploads + JIT) before measuring
    static int profSteps = 500;                // -profsteps : measured steps (Stage A: 500; Stage C: large for the decay slope)
    static int profLogEvery = 2000;            // -proflog   : per-graph snapshot cadence (Stage C decay triangulation)
    static boolean noprof = false;             // -noprof : profiler OFF (pure per-graph nanoTime, matches production stepSplit;
                                               //   required for LONG decay runs — ProfilerMode.SILENT accumulates a result/execution
                                               //   on the Java heap → GC pressure + OOM over ~10^5 steps, contaminating the decay)
    static int planResetEvery = 0;             // -planreset N : MEASUREMENT probe — periodically flush the per-execute() creep
                                               //   (0=off). mode A = plan.resetDevice() (cheap: clean streams/events/codecache,
                                               //   buffers survive); mode B = full TornadoExecutionPlan rebuild w/ state round-trip.
    static String planResetMode = "device";    // -planresetmode device|rebuild
    static long planResetCostNs = 0; static int planResetCount = 0;   // amortized per-reset cost accounting

    // ---- density/scale sweep (MEASUREMENT-ONLY; additive — scales the scene at ~constant density) ----
    static double SCALE = 1.0;                  // -scale F : size-scaling factor over the (possibly -dense) baseline.
                                                //   Constant density: nodes ×F (GX,GY ×√F), minifils ×F, FIL_CAP ×F,
                                                //   box AREA ×F (BOX_XY ×√F, BOX_Z + spacing fixed). Applied after the arg loop.
    static boolean sweep = false;               // -sweep : one short GPU window (profiler-on for kernel%) + VRAM + sanity + capped CPU
    static int cpuCap = 400;                    // -cpucap N : CPU comparison window (0 ⇒ skip CPU; small at large scale, extrapolate)

    static double pForm(double conc, double dtCheck) { return 1.0 - Math.exp(-XLINK_ON_RATE * conc * dtCheck); }

    public static void main(String[] args) {
        double dt = 1.0e-5;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-dt" -> dt = Double.parseDouble(args[++i]);
                case "-dense" -> {   // a denser scene: more nodes + minifilaments + crosslinker concentration
                    GX = 5; GY = 5; SPACING = 0.6; BOX_XY = 4.0; FORMINS = 6;
                    N_MINI = 160; FIL_CAP = 2560; XLINK_CONC = 6.0;
                }
                case "-cpu" -> gpuScale = false;
                case "-gpu" -> gpuScale = true;
                case "-boxxy" -> BOX_XY = Double.parseDouble(args[++i]);
                case "-boxz" -> BOX_Z = Double.parseDouble(args[++i]);
                case "-gx" -> GX = Integer.parseInt(args[++i]);
                case "-gy" -> GY = Integer.parseInt(args[++i]);
                case "-spacing" -> SPACING = Double.parseDouble(args[++i]);
                case "-formins" -> FORMINS = Integer.parseInt(args[++i]);
                case "-cap" -> FIL_CAP = Integer.parseInt(args[++i]);
                case "-segmono" -> SEG_MONO = Integer.parseInt(args[++i]);
                case "-warmchain" -> WARM_CHAIN = Integer.parseInt(args[++i]);
                case "-overshoot" -> OVERSHOOT = Double.parseDouble(args[++i]);
                case "-mini" -> N_MINI = Integer.parseInt(args[++i]);
                case "-noxlink" -> XLINK_ON = false;
                case "-xlconc" -> XLINK_CONC = Double.parseDouble(args[++i]);
                case "-xlcheck" -> XL_CHECK_INT = Integer.parseInt(args[++i]);
                case "-noaging" -> AGING_ON = false;
                case "-nosever" -> SEVER_ON = false;
                case "-cofratio" -> COF_RATIO = Double.parseDouble(args[++i]);
                case "-kin" -> KIN = Double.parseDouble(args[++i]);
                case "-polyboost" -> POLYBOOST = Integer.parseInt(args[++i]);
                case "-pool" -> POOL0_UM = Double.parseDouble(args[++i]);
                case "-nucboost" -> NUCBOOST = Double.parseDouble(args[++i]);
                case "-nodebrown" -> NODE_BROWN = Double.parseDouble(args[++i]);
                case "-steps" -> STEPS = Integer.parseInt(args[++i]);
                case "-gpusteps" -> gpuSteps = Integer.parseInt(args[++i]);
                case "-overnight" -> { overnight = true; gpuScale = true; }
                case "-overnightviz" -> overnightViz = args[++i];
                case "-profile" -> { profile = true; gpuScale = true; }
                case "-frozen" -> frozen = true;
                case "-profwarm" -> profWarm = Integer.parseInt(args[++i]);
                case "-profsteps" -> profSteps = Integer.parseInt(args[++i]);
                case "-proflog" -> profLogEvery = Integer.parseInt(args[++i]);
                case "-noprof" -> noprof = true;
                case "-planreset" -> planResetEvery = Integer.parseInt(args[++i]);
                case "-planresetmode" -> planResetMode = args[++i];
                case "-filidcheck" -> filidCheck = true;
                case "-scale" -> SCALE = Double.parseDouble(args[++i]);
                case "-sweep" -> { sweep = true; gpuScale = true; }
                case "-cpucap" -> cpuCap = Integer.parseInt(args[++i]);
                case "-smoke" -> { smoke = true; STEPS = 1500; }
                case "-3js" -> vizDir = args[++i];
                default -> {}
            }
        }
        // ---- density/scale sweep: clean SIZE-scaling at ~constant density (box law: AREA ∝ F, depth + node spacing fixed)
        if (SCALE != 1.0) {
            double lin = Math.sqrt(SCALE);
            GX = Math.max(1, (int) Math.round(GX * lin));
            GY = Math.max(1, (int) Math.round(GY * lin));
            N_MINI = Math.max(1, (int) Math.round(N_MINI * SCALE));
            FIL_CAP = Math.max(64, (int) Math.round(FIL_CAP * SCALE));
            BOX_XY *= lin;                       // node grid extent ∝√F and the box ∝√F ⇒ wall margin preserved
            System.out.printf("[scale] ×%.2f size-scaling (constant density): %dx%d nodes, %d minifils, FIL_CAP %d, box %.2f µm%n",
                    SCALE, GX, GY, N_MINI, FIL_CAP, BOX_XY);
        }
        BOX_VOL = BOX_XY * BOX_XY * BOX_Z;
        uMper = 1e21 / (BOX_VOL * Constants.AvogadroNum);
        double segLen = (SEG_MONO + 1) * Constants.actinMonoRadius;
        if (WARM_CHAIN <= 0) WARM_CHAIN = Math.max(1, (int) Math.round(OVERSHOOT * SPACING / segLen));

        int nNodes = GX * GY;
        System.out.println("=== Soft Box — FULL-SYSTEM DEMONSTRATION (mid-sized biochemically-active contractile network) ===");
        System.out.printf("box: %.1f×%.1f×%.2f µm shallow slab; runner: %s; dt=%.0e; KIN=%.0f (%s)%n",
                BOX_XY, BOX_XY, BOX_Z, gpuScale ? "CPU + GPU device scale check" : "CPU", dt, KIN, KIN == 1 ? "faithful" : "compressed");
        System.out.printf("nodes: %d (%dx%d grid, spacing %.2f µm), %d formins/node, shell %d singlet+%d dimer; warm chain %d seg × %d mono (reach≈%.3f µm)%n",
                nNodes, GX, GY, SPACING, FORMINS, N_SING, N_DIM, WARM_CHAIN, SEG_MONO, WARM_CHAIN * segLen);
        System.out.printf("turnover: growth%s%s formin-PINNED; nucleation kNodeNuc×KIN×%.0f; minifilaments: %d free (%d heads); crosslinkers: %s (O(N) formation, conc=%.2g pForm=%.4g)%n",
                AGING_ON ? "+AGING" : "", SEVER_ON ? "+SEVERING(cof " + COF_RATIO + ")" : "", NUCBOOST, N_MINI, N_MINI * 2 * DIMERS_END * 2,
                XLINK_ON ? "ON" : "off", XLINK_CONC, pForm(XLINK_CONC, dt * XL_CHECK_INT));
        System.out.println();

        Scene s = build(dt);
        System.out.printf("scene built: %d nodes, %d warm filaments (%d active segments), %d minifilaments (%d heads), %d crosslink slots; pool0=%.4f µM, total actin=%.3f µM%n%n",
                s.nNodes, s.nNodes * FORMINS, activeSegments(s.fil), s.nMini, s.mot2.nMotors, s.xl == null ? 0 : s.xl.nLinks, s.grow.pool.conc(), totalActinUM(s));

        if (filidCheck) { filIDCheck(s, dt); return; }
        if (sweep) { sweepRun(s, dt); return; }
        if (profile) { profileRun(s, dt); return; }
        if (overnight) { overnightRun(s, dt); return; }
        if (vizDir != null) { runViz(s, dt); return; }
        run(s, dt);
    }

    // ====================================================================== scene
    static final class Scene {
        int nNodes, nMini;
        // node shell
        NodeStore node; MotorStore mot; DimerStore dim; int[] motorNode; int motorsPerNode;
        FloatArray bondData; FloatArray xbParams;
        IntArray reachSeg, reachCount, segMotorCount, segMotorOffsets, segMotorMyo;
        // filaments + turnover
        FilamentStore fil; NodeNucleationStore nuc; GrowthStore grow; DepolyStore depoly; AgingStore aging; SeverStore sever;
        IntArray nucAccept; FloatArray nucReqCoord, nucReqUVec, nucReqYVec; IntArray nucRankOffsets, nucRankScanCounts;
        long monInit; int lastNucBirths;
        // free minifilaments
        MotorStore mot2; DimerStore dim2; MiniFilamentStore mini;
        FloatArray bondData2; IntArray reachSeg2, reachCount2, segMotorCount2, segMotorOffsets2, segMotorMyo2;
        // grid (free-minifil binding)
        SpatialBodyView view; FloatArray gridParams, viewParams; IntArray gridDims, gridCounts;
        IntArray bodyCell, cellCount, chunkSum, gridCellOffsets, gridCellContents, chunkParams, chunkCellCount; int numBodyChunks, totalCells, viewCap;
        // crosslinkers
        CrosslinkerStore xl; FormationGrid fg; IntArray filID, filIDScratch; int filIDRounds;
        IntArray segCountA, segOffsetsA, segIdxA, segCountB, segOffsetsB, segIdxB;
        // containment
        FloatArray boxParams;
    }

    static int wangHash(int seed) {
        seed = (seed ^ 61) ^ (seed >>> 16); seed *= 9; seed = seed ^ (seed >>> 4);
        seed *= 0x27d4eb2d; seed = seed ^ (seed >>> 15); return seed;
    }
    static double[] forminSiteDir(int nodeK, int site) {
        int base = ((nodeK * FORMINS + site) * 1000003) ^ (nodeK * 999983) ^ 0x46554C4C;  // "FULL"
        double dx = ((wangHash(base ^ 0x9e3779b9) >>> 1) / 2147483647.0) * 2 - 1;
        double dy = ((wangHash(base ^ 0x85ebca6b) >>> 1) / 2147483647.0) * 2 - 1;
        double dz = ((wangHash(base ^ 0xc2b2ae35) >>> 1) / 2147483647.0) * 2 - 1;
        dz *= PLANE_BIAS;                    // compress out-of-plane: the brush stays in the thin slab (top-down view)
        double m2 = dx*dx + dy*dy + dz*dz; if (m2 < 1e-9) { dx = 1; dy = 0; dz = 0; m2 = 1; }
        double inv = 1.0 / Math.sqrt(m2);
        return new double[]{ dx * inv, dy * inv, dz * inv };
    }

    static Scene build(double dt) {
        Scene s = new Scene();
        int nNodes = GX * GY;
        s.nNodes = nNodes;
        // planar grid in xy, centred on origin, z=0
        double[][] centers = new double[nNodes][3];
        double offx = 0.5 * (GX - 1) * SPACING, offy = 0.5 * (GY - 1) * SPACING;
        for (int gy = 0; gy < GY; gy++)
            for (int gx = 0; gx < GX; gx++) {
                int k = gy * GX + gx;
                centers[k][0] = gx * SPACING - offx;
                centers[k][1] = gy * SPACING - offy;
                centers[k][2] = 0.0;
            }
        buildShells(s, dt, centers);

        // ---------------- filaments + turnover (Ring3x3 build, verbatim logic) ----------------
        int cap = FIL_CAP;
        FilamentStore f = new FilamentStore(cap, cap);
        for (int sl = 0; sl < cap; sl++) f.monomerCount.set(sl, Constants.actinSeed);
        DragTensorSystem.run(f);
        f.setParams(dt, Constants.brownianForceMag(dt));
        f.setChainParams(dt);
        double bornScale = Constants.BTransCoeff;
        f.setBirthParams(bornScale, Constants.BRotCoeff);   // born seed = a single-segment chain END ⇒ BRotCoeff rotational
        f.setBirthRequestCount(cap);
        for (int sl = 0; sl < cap; sl++) { f.setCoord(sl, 100f, 100f, 100f); f.setUVec(sl, 1f, 0f, 0f); f.setYVec(sl, 0f, 1f, 0f); f.markFree(sl); }

        NodeNucleationStore nuc = new NodeNucleationStore(nNodes, cap, Constants.actinSeed, 1.0e12, BOX_VOL, 30.0);

        for (int k = 0; k < nNodes; k++)
            for (int j = 0; j < FORMINS; j++) {
                double[] d = forminSiteDir(k, j);
                int base = (k * FORMINS + j) * WARM_CHAIN;
                placeRandomChain(f, nuc, s.node, k, d[0], d[1], d[2], WARM_CHAIN, base, bornScale, SEG_MONO);
            }
        // Rotational Brownian (thermal TORQUE) only on chain ENDS — an interior segment is constrained by its two
        // chain neighbours, so it carries NO rotational Brownian (the bending modes are set by the F4 bending force
        // + the end rotational kicks). Translational Brownian stays full-FDT on every segment. (The standard chain
        // convention, e.g. DenseContractileHarness:149; placeRandomChain had set every segment ⇒ FIXED here.)
        for (int sl = 0; sl < cap; sl++) {
            if (f.filState.get(sl) < 0) continue;
            boolean chainEnd = f.end1NbrSlot.get(sl) < 0 || f.end2NbrSlot.get(sl) < 0;
            f.brownRotScale.set(sl, (float) (chainEnd ? Constants.BRotCoeff : 0.0));
        }
        DragTensorSystem.run(f);
        applyAeta(f, AETA);                  // crowded-cytoplasm drag (FDT-consistent), as the dense fixture
        DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
        s.monInit = sumActiveMonomers(f);

        double seedLen = (Constants.actinSeed + 1) * Constants.actinMonoRadius;
        nuc.setNucParams(Constants.kNodeNuc * KIN * NUCBOOST, dt, seedLen, FORMINS);
        nuc.setTetherParams(Constants.fracMove, dt);
        nuc.setDissolveParams(0.0, dt);

        double pool0 = (POOL0_UM > 0) ? POOL0_UM : (AGING_ON ? C_C_EFF_AGING : C_C_EFF);
        GrowthStore grow = new GrowthStore(cap, K_ON * KIN, dt, pool0, BOX_VOL);
        DepolyStore depoly = new DepolyStore(cap, K_OFF * KIN, dt, grow.pool);

        AgingStore aging = new AgingStore(cap);
        aging.refresh(AGING_ON);
        aging.agingParams.set(0, (float) (aging.agingParams.get(0) * KIN));
        aging.agingParams.set(1, (float) (aging.agingParams.get(1) * KIN));
        aging.depolyRateParams.set(0, (float) (Constants.kATPOff1 * Constants.biochemDeltaT * KIN));
        aging.depolyRateParams.set(1, (float) (Constants.kADPOff1 * Constants.biochemDeltaT * KIN));
        for (int sl = 0; sl < cap; sl++) aging.setATP(sl);
        SeverStore sever = new SeverStore(cap, SEVER_ON ? COF_RATIO : 1.0);
        sever.refresh(SEVER_ON);
        sever.cofilinParams.set(0, (float) (sever.cofilinParams.get(0) * KIN));
        s.aging = aging; s.sever = sever;

        s.nucAccept = new IntArray(cap); s.nucAccept.init(0);
        s.nucReqCoord = new FloatArray(3 * cap); s.nucReqUVec = new FloatArray(3 * cap); s.nucReqYVec = new FloatArray(3 * cap);
        s.nucRankOffsets = new IntArray(cap + 1);
        s.nucRankScanCounts = new IntArray(4); s.nucRankScanCounts.set(3, cap);

        s.segMotorCount = new IntArray(cap); s.segMotorOffsets = new IntArray(cap + 1); s.segMotorMyo = new IntArray(s.mot.nMotors);
        s.fil = f; s.nuc = nuc; s.grow = grow; s.depoly = depoly;

        // ---------------- free minifilaments (DenseContractile build, verbatim logic) ----------------
        buildMinifilaments(s, dt, cap);

        // ---------------- crosslinkers (O(N) formation) ----------------
        if (XLINK_ON) buildCrosslinkers(s, dt, cap);

        // ---------------- shallow containment box ----------------
        int checkInt = Math.max(1, (int) Math.round(1.0e-4 / dt));
        s.boxParams = FloatArray.fromElements(1.0e-4f, (float) BOX_XY, (float) BOX_XY, (float) BOX_Z,
                (float) Constants.radius, 0.5f, (float) checkInt);
        return s;
    }

    /** Node motor shells (Ring3x3.buildShells, replicated). */
    static void buildShells(Scene s, double dt, double[][] centers) {
        int nNodes = centers.length;
        int nSing = N_SING, nDim = N_DIM, nChild = nSing + nDim;
        int motorsPerNode = nSing + 2 * nDim;
        s.motorsPerNode = motorsPerNode;
        int nMot = nNodes * motorsPerNode, nDimers = nNodes * nDim, nAtt = nNodes * nChild;
        double R = NodeStore.NODE_RADIUS;

        MotorStore mot = new MotorStore(nMot);
        DimerStore dim = new DimerStore(nDimers);
        NodeStore node = new NodeStore(nNodes, nAtt);
        s.motorNode = new int[nMot];

        for (int k = 0; k < nNodes; k++) {
            double cx = centers[k][0], cy = centers[k][1], cz = centers[k][2];
            node.node.setCoord(k, (float) cx, (float) cy, (float) cz);
            node.node.setUVec(k, 1f, 0f, 0f); node.node.setYVec(k, 0f, 1f, 0f);
            node.node.brownTransScale.set(k, (float) NODE_BROWN); node.node.brownRotScale.set(k, (float) NODE_BROWN);
            for (int c = 0; c < nChild; c++) {
                double yy = 1.0 - 2.0 * (c + 0.5) / nChild;
                double rr = Math.sqrt(Math.max(0.0, 1.0 - yy * yy));
                double phi = c * GOLDEN;
                double ux = rr * Math.cos(phi), uy = yy, uz = rr * Math.sin(phi);
                double sx = cx + R * ux, sy = cy + R * uy, sz = cz + R * uz;
                int att = k * nChild + c;
                if (c < nSing) {
                    int m = k * motorsPerNode + c;
                    mot.assembleArticulated(m, (float) sx, (float) sy, (float) sz, (float) ux, (float) uy, (float) uz, (float) BROWN_TRANS);
                    int rod = mot.rodIdx(m), head = mot.headIdx(m);
                    mot.body.brownRotScale.set(rod, (float) BROWN_ROT); mot.body.brownRotScale.set(head, (float) BROWN_ROT);
                    double coeff = NodeStore.ATTN_FORCE / nSing;
                    node.attach(att, k, m, R * ux, R * uy, R * uz, coeff, false);
                    s.motorNode[m] = k;
                } else {
                    int jj = c - nSing;
                    int mA = k * motorsPerNode + nSing + 2 * jj, mB = mA + 1, gd = k * nDim + jj;
                    placeDimerAlong(mot, mA, mB, sx, sy, sz, ux, uy, uz);
                    dim.pair(gd, mA, mB, true);
                    double coeff = NodeStore.ATTN_FORCE * NodeStore.DIMER_FRACMOVE;
                    node.attach(att, k, mA, R * ux, R * uy, R * uz, coeff, true);
                    s.motorNode[mA] = k; s.motorNode[mB] = k;
                }
            }
        }
        DragTensorSystem.run(mot);
        node.initNodeDrag();
        mot.setBodyParams(dt); mot.setJointParams(dt); mot.setKinParams(REACH, ALIGN_TOL, dt); mot.setNucParams(dt);
        mot.kinParams.set(0, (float) KOFF);
        mot.setFaithfulRelease(true, 0.0);
        mot.nucleotideState.init(MotorStore.NUC_NONE);
        dim.setDimerParams(dt);
        node.setNodeParams(dt); node.setNodeBodyParams(dt);
        DerivedGeometrySystem.derive(node.node.coord, node.node.uVec, node.node.yVec, node.node.zVec,
                node.node.end1, node.node.end2, node.node.segLength, node.nodeBodyCounts);

        s.bondData = new FloatArray(nMot * CrossBridgeSystem.STRIDE); s.bondData.init(0f);
        s.xbParams = FloatArray.fromElements((float) MYO_SPRING, 90f, (float) J1_FMT, (float) dt, (float) MotorStore.HEAD_LEN, 0f);
        int MAXC = SpatialGrid.MAX_CAND;
        s.reachSeg = new IntArray(nMot * MAXC); s.reachSeg.init(-1); s.reachCount = new IntArray(nMot);
        s.node = node; s.mot = mot; s.dim = dim;
    }

    /** Free bipolar minifilaments (DenseContractile.buildScene minifil block, replicated). */
    static void buildMinifilaments(Scene s, double dt, int nSeg) {
        int nMini = N_MINI, nDimers = nMini * 2 * DIMERS_END, nMot = 2 * nDimers;
        double half = 0.5 * BOX_XY, margin = 0.3;
        // co-locate the free minifilaments with the filament-rich node footprint (else they sit in empty space,
        // never within bind reach of any filament — the 0/N-bound smoke finding).
        double segLen = (SEG_MONO + 1) * Constants.actinMonoRadius;
        double warmReach = WARM_CHAIN * segLen;
        double fp = Math.min(half - margin, 0.5 * (Math.max(GX, GY) - 1) * SPACING + 0.7 * warmReach);
        double fpz = Math.max(0.05, 0.5 * BOX_Z - 0.06);
        java.util.Random rng = new java.util.Random(0x4D696E69F11L ^ (long) nMini);
        MotorStore mot = new MotorStore(nMot);
        DimerStore dim = new DimerStore(nDimers);
        MiniFilamentStore mini = new MiniFilamentStore(nMini, nDimers);
        double bbLen = MiniFilamentStore.BACKBONE_LEN, headZone = MiniFilamentStore.HEAD_ZONE;
        int d = 0;
        for (int mfi = 0; mfi < nMini; mfi++) {
            double bx = (rng.nextDouble() - 0.5) * 2 * fp;
            double by = (rng.nextDouble() - 0.5) * 2 * fp;
            double bz = (rng.nextDouble() - 0.5) * 2 * fpz;
            double th = rng.nextDouble() * Math.PI * 2;
            double ux = Math.cos(th), uy = Math.sin(th), uz = 0;
            double yx = -uy, yy = ux, yz = 0;
            mini.backbone.setCoord(mfi, (float) bx, (float) by, (float) bz);
            mini.backbone.setUVec(mfi, (float) ux, (float) uy, (float) uz);
            mini.backbone.setYVec(mfi, (float) yx, (float) yy, (float) yz);
            mini.backbone.brownTransScale.set(mfi, (float) BROWN_TRANS);
            mini.backbone.brownRotScale.set(mfi, (float) BROWN_ROT);
            for (int e = 0; e < 2; e++) {
                double dir = (e == 0) ? -1.0 : 1.0;
                for (int j = 0; j < DIMERS_END; j++) {
                    double mag = bbLen / 2.0 - (j + 0.5) / DIMERS_END * headZone;
                    double ax = dir * mag;
                    double e1x = bx + ax * ux, e1y = by + ax * uy, e1z = bz + ax * uz;
                    double phi = (j + 0.5) / DIMERS_END * Math.PI;
                    double px = Math.cos(phi) * yx + Math.sin(phi) * (uy * yz - uz * yy);
                    double py = Math.cos(phi) * yy + Math.sin(phi) * (uz * yx - ux * yz);
                    double pz = Math.cos(phi) * yz + Math.sin(phi) * (ux * yy - uy * yx);
                    int mA = 2 * d, mB = 2 * d + 1;
                    ContractileAssayHarness.placeDimerAlong(mot, mA, mB, e1x, e1y, e1z, dir * ux, dir * uy, dir * uz, px, py, pz);
                    dim.pair(d, mA, mB, true);
                    mini.attach(d, mfi, mA, ax);
                    mot.reach.set(mA, (float) REACH); mot.reach.set(mB, (float) REACH);
                    d++;
                }
            }
        }
        DragTensorSystem.run(mot);
        mini.initBackboneDrag();
        mot.setBodyParams(dt); mot.setJointParams(dt); mot.setKinParams(REACH, ALIGN_TOL, dt); mot.setNucParams(dt);
        mot.kinParams.set(0, (float) KOFF);
        mot.setFaithfulRelease(true, 0.0);
        mot.nucleotideState.init(MotorStore.NUC_NONE);
        mot.setBaseSlot(nSeg);                  // free-minifil heads at view slots [nSeg, nSeg+nMot)
        dim.setDimerParams(dt);
        mini.setMiniParams(dt); mini.setBackboneParams(dt);

        s.bondData2 = new FloatArray(nMot * CrossBridgeSystem.STRIDE); s.bondData2.init(0f);
        int MAXC = SpatialGrid.MAX_CAND;
        s.reachSeg2 = new IntArray(nMot * MAXC); s.reachSeg2.init(-1); s.reachCount2 = new IntArray(nMot);
        s.segMotorCount2 = new IntArray(nSeg); s.segMotorOffsets2 = new IntArray(nSeg + 1); s.segMotorMyo2 = new IntArray(nMot);
        s.mot2 = mot; s.dim2 = dim; s.mini = mini; s.nMini = nMini;

        // grid + body view (filaments + free-minifil heads), for the parallel-grid fused per-head binding
        int viewCap = nSeg + nMot;
        SpatialBodyView view = new SpatialBodyView(viewCap); view.count = viewCap;
        double L = (SEG_MONO + 1) * Constants.actinMonoRadius;
        double segBoundR = 0.5 * (Constants.stdSegLength * 2 + 1) * Constants.actinMonoRadius + Constants.radius;  // bound the grown 64-mono case
        double cutoff = REACH + 0.5 * MotorStore.ROD_LEN;
        double cellSize = 2.0 * segBoundR + cutoff;
        double gx = half + 1.0, gy = gx, gz = 0.5 * BOX_Z + 0.3;
        int nX = 1 + (int) Math.ceil(2 * gx / cellSize), nY = 1 + (int) Math.ceil(2 * gy / cellSize), nZ = 1 + (int) Math.ceil(2 * gz / cellSize);
        int totalCells = nX * nY * nZ;
        s.gridParams = FloatArray.fromElements((float) -gx, (float) -gy, (float) -gz, (float) cellSize, (float) (1.0 / cellSize), (float) cutoff);
        s.gridDims = IntArray.fromElements(nX, nY, nZ, totalCells);
        s.gridCounts = new IntArray(4); s.gridCounts.set(1, viewCap);
        s.viewParams = FloatArray.fromElements((float) Constants.radius);
        s.bodyCell = new IntArray(viewCap); s.bodyCell.init(-1);
        s.cellCount = new IntArray(totalCells);
        s.chunkSum = new IntArray((totalCells + SpatialGrid.GRID_SCAN_CHUNK - 1) / SpatialGrid.GRID_SCAN_CHUNK + 1);
        int gbcs = SpatialGrid.bodyChunkSize(viewCap, totalCells);
        s.numBodyChunks = SpatialGrid.numBodyChunks(viewCap, gbcs);
        s.chunkParams = IntArray.fromElements(gbcs, s.numBodyChunks);
        s.chunkCellCount = new IntArray(s.numBodyChunks * totalCells); s.chunkCellCount.init(0);
        s.gridCellOffsets = new IntArray(totalCells + 1);
        s.gridCellContents = new IntArray(viewCap); s.gridCellContents.init(-1);
        s.view = view; s.viewCap = viewCap; s.totalCells = totalCells;
    }

    /** Crosslinker store + the dedicated O(N) formation grid (CrosslinkerBundleHarness, replicated). */
    static void buildCrosslinkers(Scene s, double dt, int nSeg) {
        int C = Math.max(256, nSeg * 4);          // link pool capacity
        int reqCap = nSeg * CrosslinkerSystem.FORM_MAXC;   // O(N) request capacity (grid emits ≤ FORM_MAXC/seg)
        CrosslinkerStore xl = new CrosslinkerStore(C, nSeg, reqCap);
        xl.setParams(REST_LEN, FRAC_MOVE, dt);
        xl.setOffParams(OFF_CONST, OFF_COEFF, OFF_EXP, dt, REST_LEN);
        XL_PFORM = (float) pForm(XLINK_CONC, dt * XL_CHECK_INT);   // the real on-cadence P_form (device toggles formParams[4] to this/0)
        xl.setFormParams(XL_MAX_ANGLE, GRAB_DIST, MIN_SEP, MAX_LINKS_ON_SEG, XL_PFORM, MIN_FILLINK_SEP, 0);
        xl.setRequestCount(reqCap);
        xl.setTorsionParams(FIL_TORQ_SPRING, true);
        s.filID = new IntArray(nSeg);   // CHAIN id (the connected-component terminal), recomputed each formation
                                        // step by FilIDSystem pointer-doubling — so two segments of the SAME
                                        // filament are NOT crosslinked (they always touch at their shared joint ⇒
                                        // a spurious "crosslink on one filament"). v1 filID = the filament's stable id.
        s.filIDScratch = new IntArray(nSeg);   // pointer-doubling ping-pong partner of s.filID
        s.filIDRounds = FilIDSystem.rounds(nSeg);   // ceil(log2(nSeg)) rounded to even ⇒ init→jumps ends in s.filID
        s.segCountA = new IntArray(nSeg); s.segOffsetsA = new IntArray(nSeg + 1); s.segIdxA = new IntArray(C);
        s.segCountB = new IntArray(nSeg); s.segOffsetsB = new IntArray(nSeg + 1); s.segIdxB = new IntArray(C);
        // formation grid sized to the shallow box (segments span the whole slab)
        double maxSegLen = (Constants.stdSegLength * 2 + 1) * Constants.actinMonoRadius;
        double cellSize = 2.0 * (0.5 * maxSegLen + Constants.radius) + GRAB_DIST;
        double gx = 0.5 * BOX_XY + cellSize, gz = 0.5 * BOX_Z + cellSize;
        s.fg = new FormationGrid(nSeg, gx, gx, gz, cellSize, GRAB_DIST, Constants.radius);
        s.xl = xl;
    }

    static void applyAeta(FilamentStore f, double aeta) {
        double r = aeta / Constants.aeta; if (r == 1.0) return;
        scale(f.bTransGam, r); scale(f.bRotGam, r); scale(f.bTransDiff, 1.0 / r); scale(f.bRotDiff, 1.0 / r);
    }
    static void scale(FloatArray a, double r) { for (int i = 0; i < a.getSize(); i++) a.set(i, (float) (a.get(i) * r)); }

    static void placeRandomChain(FilamentStore f, NodeNucleationStore nuc, NodeStore nd, int k,
                                 double dx, double dy, double dz, int nChain, int base, double bornScale, int segMono) {
        double cx = nd.node.coord.get(k), cy = nd.node.coord.get(nd.node.n + k), cz = nd.node.coord.get(2 * nd.node.n + k);
        double ex = (Math.abs(dx) < 0.9) ? 1 : 0, ey = (Math.abs(dx) < 0.9) ? 0 : 1, ez = 0;
        double yx = dy*ez - dz*ey, yy = dz*ex - dx*ez, yz = dx*ey - dy*ex;
        double ym = 1.0 / Math.sqrt(yx*yx + yy*yy + yz*yz); yx *= ym; yy *= ym; yz *= ym;
        double Lc = (segMono + 1) * Constants.actinMonoRadius;
        double e1x = cx, e1y = cy, e1z = cz;
        for (int i = 0; i < nChain; i++) {
            int sl = base + i;
            double ccx = e1x + 0.5 * Lc * dx, ccy = e1y + 0.5 * Lc * dy, ccz = e1z + 0.5 * Lc * dz;
            f.monomerCount.set(sl, segMono);
            f.setCoord(sl, (float) ccx, (float) ccy, (float) ccz);
            f.setUVec(sl, (float) -dx, (float) -dy, (float) -dz);   // barbed=end2: uVec INWARD (toward node)
            f.setYVec(sl, (float) yx, (float) yy, (float) yz);
            f.filState.set(sl, FilamentStore.FIL_ACTIVE);
            f.brownTransScale.set(sl, (float) bornScale); f.brownRotScale.set(sl, (float) bornScale);
            nuc.seedNode.set(sl, i == 0 ? k : -1);
            if (i > 0) { f.end2NbrSlot.set(sl, sl - 1); f.end2NbrSide.set(sl, 0); }
            if (i < nChain - 1) { f.end1NbrSlot.set(sl, sl + 1); f.end1NbrSide.set(sl, 1); }
            e1x += Lc * dx; e1y += Lc * dy; e1z += Lc * dz;
        }
    }

    // ---- node-shell dimer placement (Ring3x3.placeDimerAlong / placeArm) ----
    static void placeDimerAlong(MotorStore mot, int mA, int mB, double e1x, double e1y, double e1z, double dx, double dy, double dz) {
        double dm = Math.sqrt(dx*dx+dy*dy+dz*dz); dx/=dm; dy/=dm; dz/=dm;
        double px = -dz, py = 0, pz = dx; double pm = Math.sqrt(px*px+py*py+pz*pz);
        if (pm < 1e-4) { px = 1; py = 0; pz = 0; pm = 1; } px/=pm; py/=pm; pz/=pm;
        double rl = MotorStore.ROD_LEN, ll = MotorStore.LEVER_LEN, hl = MotorStore.HEAD_LEN;
        double rcx=e1x+0.5*rl*dx, rcy=e1y+0.5*rl*dy, rcz=e1z+0.5*rl*dz;
        double e2x=e1x+rl*dx, e2y=e1y+rl*dy, e2z=e1z+rl*dz;
        placeArm(mot, mA, rcx,rcy,rcz, dx,dy,dz, px,py,pz, e2x,e2y,e2z, +1, ll, hl);
        placeArm(mot, mB, rcx,rcy,rcz, dx,dy,dz, px,py,pz, e2x,e2y,e2z, -1, ll, hl);
    }
    static void placeArm(MotorStore mot, int m, double rcx, double rcy, double rcz, double dx, double dy, double dz,
                         double px, double py, double pz, double e2x, double e2y, double e2z, int splay, double ll, double hl) {
        int rod = mot.rodIdx(m), lever = mot.leverIdx(m), head = mot.headIdx(m); RigidRodBody b = mot.body;
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
        b.brownTransScale.set(rod, (float) BROWN_TRANS);  b.brownRotScale.set(rod, (float) BROWN_ROT);
        b.brownTransScale.set(lever, 0f);                 b.brownRotScale.set(lever, 0f);
        b.brownTransScale.set(head, (float) BROWN_TRANS); b.brownRotScale.set(head, (float) BROWN_ROT);
    }

    // ====================================================================== one CPU step
    static void cpuStep(Scene s, int t) {
        MotorStore mot = s.mot; DimerStore dim = s.dim; NodeStore nd = s.node;
        MotorStore mot2 = s.mot2; DimerStore dim2 = s.dim2; MiniFilamentStore mini = s.mini;
        FilamentStore f = s.fil; NodeNucleationStore nuc = s.nuc; GrowthStore grow = s.grow; DepolyStore d = s.depoly;
        AgingStore ag = s.aging; SeverStore sv = s.sever; CrosslinkerStore xl = s.xl;
        RigidRodBody b = mot.body, nb = nd.node, b2 = mot2.body, bb = mini.backbone;
        int nSeg = f.n;
        mot.setCounts(t, SEED, nSeg); nd.setNodeBodyCounts(t, SEED_NODE); f.setCounts(t, SEED);
        mot2.setCounts(t, SEED_MINI, nSeg); mini.setBackboneCounts(t, SEED_BB);

        boolean fires = grow.firesAt(t);
        grow.setCounts(t, SEED, fires); grow.refreshRate(fires);
        d.setCounts(t, SEED, fires); ag.setFires(fires); sv.setFires(fires);

        // === FULL TURNOVER (formin-PINNED; the SeveringHarness combined order) ===
        AgingSystem.age(f.filState, ag.nucFrac, ag.agingParams, ag.agingCounts);
        DepolySystem.depolyProxy(f.filState, f.monomerCount, f.coord, f.uVec, f.end1NbrSlot, ag.nucFrac,
                d.returnedMon, d.deathFlag, d.depolyParams, ag.depolyRateParams, d.depolyCounts);
        CrossBridgeSystem.csrScan(d.returnScanCounts, d.returnedMon, d.returnedOffsets);
        DepolySystem.applyDeath(f.filState, f.monomerCount, nuc.seedNode, f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide,
                f.brownTransScale, f.brownRotScale, d.deathFlag, d.depolyCounts);
        grow.pool.put(d.returnedOffsets.get(f.n));
        int boost = fires ? Math.max(1, POLYBOOST) : 1;
        for (int g = 0; g < boost; g++) {
            GrowthSystem.grow(nuc.seedNode, f.monomerCount, f.coord, f.uVec, grow.grewFlag, grow.growParams, grow.growCounts);
            CrossBridgeSystem.csrScan(grow.grewScanCounts, grow.grewFlag, grow.grewOffsets);
            AgingSystem.growthAtp(grow.grewFlag, f.monomerCount, ag.nucFrac);
            grow.depletePoolForGrows();
        }
        SeveringSystem.cofilinAccumulate(f.filState, ag.nucFrac, sv.cofFrac, sv.cofilinParams, sv.severCounts);
        SeveringSystem.cofilinDissolve(f.filState, f.monomerCount, sv.cofFrac, sv.severDeathFlag, sv.severReturnedMon, sv.cofilinParams, sv.severCounts);
        CrossBridgeSystem.csrScan(sv.severScanCounts, sv.severReturnedMon, sv.severReturnedOffsets);
        DepolySystem.applyDeath(f.filState, f.monomerCount, nuc.seedNode, f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide,
                f.brownTransScale, f.brownRotScale, sv.severDeathFlag, d.depolyCounts);
        grow.pool.put(sv.severReturnedOffsets.get(f.n));
        GrowthSystem.markSplits(nuc.seedNode, f.monomerCount, f.coord, f.uVec, f.yVec,
                f.acceptFlag, f.reqCoord, f.reqUVec, f.reqYVec, grow.splitParams, grow.growCounts);
        allocCpu(f, f.acceptFlag, f.reqCoord, f.reqUVec, f.reqYVec, f.rankScanCounts, f.rankOffsets);
        GrowthSystem.splitWire(f.rankOffsets, f.freeList, f.freeOffsets, f.monomerCount, f.coord, f.uVec,
                f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide, nuc.seedNode, grow.splitParams, f.allocCounts);
        AgingSystem.splitInheritNuc(f.rankOffsets, f.freeList, f.freeOffsets, ag.nucFrac, f.allocCounts);
        SeveringSystem.nucleateFreshCofilin(f.rankOffsets, f.freeList, f.freeOffsets, sv.cofFrac, f.allocCounts);
        GrowthSystem.recomputeDrag(f.monomerCount, f.segLength, f.end1NbrSlot, f.end2NbrSlot,
                f.bTransGam, f.bRotGam, f.bTransDiff, f.bRotDiff, grow.dragParams, grow.growCounts);

        // === NUCLEATION ===
        nuc.setCounts(t, SEED);
        nuc.nucCounts.set(3, grow.pool.available(Constants.actinSeed) ? 1 : 0);
        NodeNucleationSystem.countBoundFil(nuc.seedNode, nuc.nodeBoundFil, nuc.nucCounts);
        NodeNucleationSystem.emit(nb.coord, nuc.nodeBoundFil, s.nucAccept, s.nucReqCoord, s.nucReqUVec, s.nucReqYVec, nuc.nucParams, nuc.nucCounts);
        allocCpu(f, s.nucAccept, s.nucReqCoord, s.nucReqUVec, s.nucReqYVec, s.nucRankScanCounts, s.nucRankOffsets);
        NodeNucleationSystem.tagSeeds(s.nucRankOffsets, f.freeList, f.freeOffsets, nuc.seedNode, f.allocCounts);
        NodeNucleationSystem.initNewborn(s.nucRankOffsets, f.freeList, f.freeOffsets, f.monomerCount, f.segLength, nuc.seedParams, f.allocCounts);
        AgingSystem.nucleateFreshAtp(s.nucRankOffsets, f.freeList, f.freeOffsets, ag.nucFrac, f.allocCounts);
        SeveringSystem.nucleateFreshCofilin(s.nucRankOffsets, f.freeList, f.freeOffsets, sv.cofFrac, f.allocCounts);
        int nucBirths = Math.min(s.nucRankOffsets.get(f.n), f.freeOffsets.get(f.n));
        if (nucBirths > 0) grow.pool.take(nucBirths * Constants.actinSeed);
        s.lastNucBirths = nucBirths;

        // === BINDING — node shell (node-aware brute) ===
        MotorStore.publishHeadFromBody(b.coord, b.uVec, b.segLength, mot.head, mot.uVec, mot.rodUVec, mot.counts);
        BindingDetectionSystem.bruteReachableNodeAware(mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, s.reachSeg, s.reachCount, nuc.seedNode, mot.kinParams, mot.counts);
        NucleotideCycleSystem.catchSlipRelease(mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.cooldown, mot.stats, mot.capStats, mot.kinParams, mot.counts);
        BindingDetectionSystem.bindNearestNodeAware(mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, s.reachSeg, s.reachCount, mot.boundSeg, mot.bindArc, nuc.seedNode, mot.kinParams, mot.counts);
        NucleotideCycleSystem.cycle(mot.nucleotideState, mot.boundSeg, mot.forceDotHist, mot.nucParams, mot.counts);

        // === BINDING — free minifilaments (parallel-grid fused per-head query) ===
        MotorStore.publishHeadFromBody(b2.coord, b2.uVec, b2.segLength, mot2.head, mot2.uVec, mot2.rodUVec, mot2.counts);
        FilamentStore.publishToBodyView(f.coord, f.segLength, s.view.center, s.view.boundingRadius, s.view.ownerStore, s.view.ownerSlot, s.viewParams, s.gridCounts);
        MotorStore.publishToBodyView(mot2.head, mot2.reach, s.view.center, s.view.boundingRadius, s.view.ownerStore, s.view.ownerSlot, mot2.publishParams, mot2.counts);
        SpatialGrid.bodyCell(s.view.center, s.gridParams, s.gridDims, s.gridCounts, s.bodyCell);
        SpatialGrid.gridChunkZero(s.chunkParams, s.gridDims, s.chunkCellCount);
        SpatialGrid.gridChunkHistogram(s.bodyCell, s.gridCounts, s.chunkParams, s.gridDims, s.chunkCellCount);
        SpatialGrid.gridChunkReduce(s.gridDims, s.chunkParams, s.chunkCellCount, s.cellCount);
        SpatialGrid.gridScanLocal(s.gridDims, s.cellCount, s.gridCellOffsets, s.chunkSum);
        SpatialGrid.gridScanChunks(s.gridDims, s.chunkSum);
        SpatialGrid.gridScanAdd(s.gridDims, s.gridCellOffsets, s.gridCellContents, s.cellCount, s.chunkSum);
        SpatialGrid.gridChunkScatter(s.bodyCell, s.gridCounts, s.chunkParams, s.gridDims, s.gridCellOffsets, s.gridCellContents, s.chunkCellCount);
        BindingDetectionSystem.gridReachable(mot2.head, mot2.uVec, mot2.rodUVec, f.end1, f.end2, s.gridParams, s.gridDims,
                s.gridCellOffsets, s.gridCellContents, s.view.ownerStore, s.view.ownerSlot, s.reachSeg2, s.reachCount2, mot2.kinParams, mot2.counts);
        NucleotideCycleSystem.catchSlipRelease(mot2.boundSeg, mot2.forceDotFil, mot2.forceMag, mot2.cooldown, mot2.stats, mot2.capStats, mot2.kinParams, mot2.counts);
        BindingDetectionSystem.bindNearest(mot2.head, mot2.uVec, mot2.rodUVec, f.end1, f.end2, s.reachSeg2, s.reachCount2, mot2.boundSeg, mot2.bindArc, mot2.kinParams, mot2.counts);
        NucleotideCycleSystem.cycle(mot2.nucleotideState, mot2.boundSeg, mot2.forceDotHist, mot2.nucParams, mot2.counts);

        // === CROSSLINKER FORMATION (O(N) grid, at the formation cadence) + UNBIND ===
        if (xl != null && t % XL_CHECK_INT == 0) formationCpu(s, t);
        if (xl != null) {
            xl.setCounts(t, SEED);
            CrosslinkerSystem.unbind(f.coord, f.uVec, f.end1, xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2,
                    xl.linkState, xl.strainHist, xl.strainPlace, xl.offParams, xl.counts);
            CrosslinkerSystem.countActiveLinks(xl.linkState, xl.linkFilA, xl.linkFilB, xl.activeLinkCount, xl.formCounts);
        }

        // === FORCES — zero all accumulators, then every coupling accumulates into f.forceSum ===
        ChainBendingForceSystem.zeroAccumulators(b.forceSum, b.torqueSum, mot.counts);
        ChainBendingForceSystem.zeroAccumulators(nb.forceSum, nb.torqueSum, nd.nodeBodyCounts);
        ChainBendingForceSystem.zeroAccumulators(b2.forceSum, b2.torqueSum, mot2.counts);
        ChainBendingForceSystem.zeroAccumulators(bb.forceSum, bb.torqueSum, mini.bbCounts);
        ChainBendingForceSystem.zeroAccumulators(f.forceSum, f.torqueSum, f.counts);
        BrownianForceSystem.brownianForce(b.randForce, b.randTorque, b.bTransGam, b.bRotGam, b.brownTransScale, b.brownRotScale, mot.bodyParams, mot.counts);
        BrownianForceSystem.brownianForce(nb.randForce, nb.randTorque, nb.bTransGam, nb.bRotGam, nb.brownTransScale, nb.brownRotScale, nd.nodeBodyParams, nd.nodeBodyCounts);
        BrownianForceSystem.brownianForce(b2.randForce, b2.randTorque, b2.bTransGam, b2.bRotGam, b2.brownTransScale, b2.brownRotScale, mot2.bodyParams, mot2.counts);
        BrownianForceSystem.brownianForce(bb.randForce, bb.randTorque, bb.bTransGam, bb.bRotGam, bb.brownTransScale, bb.brownRotScale, mini.bbBodyParams, mini.bbCounts);
        BrownianForceSystem.brownianForce(f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.brownTransScale, f.brownRotScale, f.params, f.counts);

        // node-shell structure: joints + dimer + radial tether + node gather + cross-bridge
        MotorJointSystem.joints(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, mot.nucleotideState, mot.jointParams, mot.counts);
        DimerCouplingSystem.couple(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, dim.motorA, dim.motorB, dim.parallel, dim.dimerParams, mot.boundSeg);
        NodeSystem.tether(b.coord, b.uVec, b.segLength, b.bTransGam, b.forceSum, b.torqueSum,
                nb.coord, nb.uVec, nb.yVec, nd.nodeInvTransY, nd.attachKey, nd.radial, nd.attachCoeffK, nd.nodeData, nd.nodeParams);
        CrossBridgeSystem.csrHistogram(nd.attachNode, nd.nodeCounts4, nd.nodeAttachCount);
        CrossBridgeSystem.csrScan(nd.nodeCounts4, nd.nodeAttachCount, nd.nodeAttachOffsets);
        CrossBridgeSystem.csrScatter(nd.attachNode, nd.nodeCounts4, nd.nodeAttachOffsets, nd.nodeAttachCount, nd.nodeAttachList);
        MiniFilamentSystem.backboneGather(nd.nodeAttachOffsets, nd.nodeAttachList, nd.nodeData, nb.forceSum, nb.torqueSum, nd.nodeCounts4);
        CrossBridgeSystem.bondForces(b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength,
                mot.boundSeg, mot.bindArc, mot.nucleotideState, s.bondData, s.xbParams);
        CrossBridgeSystem.applyHeadForce(s.bondData, b.forceSum, b.torqueSum, mot.counts);

        // free-minifilament structure: joints + dimer + axial tether + backbone gather + cross-bridge
        MotorJointSystem.joints(b2.coord, b2.uVec, b2.segLength, b2.bTransGam, b2.bRotGam, b2.forceSum, b2.torqueSum, mot2.nucleotideState, mot2.jointParams, mot2.counts);
        DimerCouplingSystem.couple(b2.coord, b2.uVec, b2.segLength, b2.bTransGam, b2.bRotGam, b2.forceSum, b2.torqueSum, dim2.motorA, dim2.motorB, dim2.parallel, dim2.dimerParams, mot2.boundSeg);
        MiniFilamentSystem.tether(b2.coord, b2.uVec, b2.segLength, b2.bTransGam, b2.bRotGam, b2.forceSum, b2.torqueSum,
                bb.coord, bb.uVec, mini.bbInvDragY, mini.headBackboneSlot, mini.motorA, mini.attachAxial, mini.miniData, mini.miniParams);
        CrossBridgeSystem.csrHistogram(mini.headBackboneSlot, mini.miniCounts, mini.bbDimerCount);
        CrossBridgeSystem.csrScan(mini.miniCounts, mini.bbDimerCount, mini.bbDimerOffsets);
        CrossBridgeSystem.csrScatter(mini.headBackboneSlot, mini.miniCounts, mini.bbDimerOffsets, mini.bbDimerCount, mini.bbDimerList);
        MiniFilamentSystem.backboneGather(mini.bbDimerOffsets, mini.bbDimerList, mini.miniData, bb.forceSum, bb.torqueSum, mini.miniCounts);
        CrossBridgeSystem.bondForces(b2.coord, b2.uVec, b2.yVec, b2.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength,
                mot2.boundSeg, mot2.bindArc, mot2.nucleotideState, s.bondData2, s.xbParams);
        CrossBridgeSystem.applyHeadForce(s.bondData2, b2.forceSum, b2.torqueSum, mot2.counts);

        // filament: chain + the node seed-tether bond + both motor seg-gathers + crosslinker force/gather
        ChainBendingForceSystem.chainForces(f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide, f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts);
        NodeNucleationSystem.seedTether(f.coord, f.uVec, f.segLength, f.bTransGam, f.forceSum, f.torqueSum, nb.coord, nd.nodeInvTransY, nuc.seedNode, nuc.tetherParams);
        NodeNucleationSystem.seedTetherNodeReact(f.coord, f.uVec, f.segLength, f.bTransGam, nb.forceSum, nb.coord, nd.nodeInvTransY, nuc.seedNode, nuc.tetherParams);
        // node-shell motor → segment gather
        CrossBridgeSystem.csrHistogram(mot.boundSeg, mot.counts, s.segMotorCount);
        CrossBridgeSystem.csrScan(mot.counts, s.segMotorCount, s.segMotorOffsets);
        CrossBridgeSystem.csrScatter(mot.boundSeg, mot.counts, s.segMotorOffsets, s.segMotorCount, s.segMotorMyo);
        CrossBridgeSystem.segGather(s.segMotorOffsets, s.segMotorMyo, s.bondData, f.forceSum, f.torqueSum, mot.counts);
        // free-minifil motor → segment gather
        CrossBridgeSystem.csrHistogram(mot2.boundSeg, mot2.counts, s.segMotorCount2);
        CrossBridgeSystem.csrScan(mot2.counts, s.segMotorCount2, s.segMotorOffsets2);
        CrossBridgeSystem.csrScatter(mot2.boundSeg, mot2.counts, s.segMotorOffsets2, s.segMotorCount2, s.segMotorMyo2);
        CrossBridgeSystem.segGather(s.segMotorOffsets2, s.segMotorMyo2, s.bondData2, f.forceSum, f.torqueSum, mot2.counts);
        // crosslinker force + torsion + 2-pass gather
        if (xl != null) {
            CrosslinkerSystem.linkForces(f.coord, f.uVec, f.end1, f.bTransGam, xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2, xl.activeLinkCount, xl.xlinkData, xl.xlParams);
            CrosslinkerSystem.linkTorsion(f.uVec, xl.linkFilA, xl.linkFilB, xl.linkState, xl.linkOrientSame, xl.torqueMagHist, xl.torqueMagPlace, xl.xlinkData, xl.torsionParams);
            CrossBridgeSystem.csrHistogram(xl.linkFilA, xl.counts, s.segCountA);
            CrossBridgeSystem.csrScan(xl.counts, s.segCountA, s.segOffsetsA);
            CrossBridgeSystem.csrScatter(xl.linkFilA, xl.counts, s.segOffsetsA, s.segCountA, s.segIdxA);
            CrosslinkerSystem.segGatherA(s.segOffsetsA, s.segIdxA, xl.xlinkData, xl.linkState, f.forceSum, f.torqueSum, xl.counts);
            CrossBridgeSystem.csrHistogram(xl.linkFilB, xl.counts, s.segCountB);
            CrossBridgeSystem.csrScan(xl.counts, s.segCountB, s.segOffsetsB);
            CrossBridgeSystem.csrScatter(xl.linkFilB, xl.counts, s.segOffsetsB, s.segCountB, s.segIdxB);
            CrosslinkerSystem.segGatherB(s.segOffsetsB, s.segIdxB, xl.xlinkData, xl.linkState, f.forceSum, f.torqueSum, xl.counts);
        }
        CrossBridgeSystem.registerForceDot(s.bondData, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.counts);
        CrossBridgeSystem.registerForceDot(s.bondData2, mot2.boundSeg, mot2.forceDotFil, mot2.forceMag, mot2.forceDotHist, mot2.forceDotPlace, mot2.counts);

        // === CONTAINMENT + INTEGRATE ===
        ContainmentSystem.confine(nb.coord, nb.uVec, nb.segLength, nb.bTransGam, nb.forceSum, nb.torqueSum, s.boxParams, nd.nodeBodyCounts);
        RigidRodLangevinIntegrationSystem.integrate(nb.coord, nb.uVec, nb.yVec, nb.forceSum, nb.torqueSum, nb.randForce, nb.randTorque, nb.bTransGam, nb.bRotGam, nd.nodeBodyParams, nd.nodeBodyCounts);
        DerivedGeometrySystem.derive(nb.coord, nb.uVec, nb.yVec, nb.zVec, nb.end1, nb.end2, nb.segLength, nd.nodeBodyCounts);
        RigidRodLangevinIntegrationSystem.integrate(b.coord, b.uVec, b.yVec, b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, mot.bodyParams, mot.counts);
        DerivedGeometrySystem.derive(b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts);
        RigidRodLangevinIntegrationSystem.integrate(b2.coord, b2.uVec, b2.yVec, b2.forceSum, b2.torqueSum, b2.randForce, b2.randTorque, b2.bTransGam, b2.bRotGam, mot2.bodyParams, mot2.counts);
        DerivedGeometrySystem.derive(b2.coord, b2.uVec, b2.yVec, b2.zVec, b2.end1, b2.end2, b2.segLength, mot2.counts);
        ContainmentSystem.confine(bb.coord, bb.uVec, bb.segLength, bb.bTransGam, bb.forceSum, bb.torqueSum, s.boxParams, mini.bbCounts);
        RigidRodLangevinIntegrationSystem.integrate(bb.coord, bb.uVec, bb.yVec, bb.forceSum, bb.torqueSum, bb.randForce, bb.randTorque, bb.bTransGam, bb.bRotGam, mini.bbBodyParams, mini.bbCounts);
        DerivedGeometrySystem.derive(bb.coord, bb.uVec, bb.yVec, bb.zVec, bb.end1, bb.end2, bb.segLength, mini.bbCounts);
        ContainmentSystem.confine(f.coord, f.uVec, f.segLength, f.bTransGam, f.forceSum, f.torqueSum, s.boxParams, f.counts);
        RigidRodLangevinIntegrationSystem.integrate(f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts);
        DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
    }

    /** O(N) crosslinker formation on the host (the grid build + fused per-segment query + the pipeline). */
    /** Per-segment CHAIN id = the end2NbrSlot-terminal slot of its chain. Segments of the same filament share it ⇒
     *  formation excludes same-filament pairs (the v1 filID semantics). Recomputed each formation step because
     *  growth/split/death/nucleation mutate the chain topology. DEVICE-AGNOSTIC: the SAME FilIDSystem pointer-
     *  doubling kernels run here on the host runner and (unrolled) in the device formation graph; init writes
     *  s.filID, then an even # of jumps ping-pong filID↔scratch and land back in s.filID. */
    static void computeFilID(Scene s) {
        FilamentStore f = s.fil;
        FilIDSystem.init(f.filState, f.end2NbrSlot, s.filID, f.counts);
        IntArray a = s.filID, b = s.filIDScratch;
        for (int k = 0; k < s.filIDRounds; k++) { FilIDSystem.jump(a, b, f.filState, f.counts); IntArray t = a; a = b; b = t; }
        // filIDRounds is EVEN ⇒ after the swaps the latest result is back in s.filID (a == s.filID)
    }

    /** Reference per-segment filID by the explicit one-step-at-a-time chain walk (the pre-port host algorithm) —
     *  the gate-1 oracle FilIDSystem pointer-doubling must reproduce exactly. */
    static void refFilIDWalk(Scene s, IntArray out) {
        FilamentStore f = s.fil; int n = f.n;
        for (int seg = 0; seg < n; seg++) {
            if (f.filState.get(seg) < 0) { out.set(seg, -seg - 2); continue; }
            int cur = seg, guard = 0;
            while (guard++ < n) {
                int nxt = f.end2NbrSlot.get(cur);
                if (nxt < 0 || nxt == cur || f.filState.get(nxt) < 0) break;
                cur = nxt;
            }
            out.set(seg, cur);
        }
    }

    /** GATE 1 — the device-agnostic FilIDSystem (pointer-doubling) ≡ the reference chain-walk, as a PARTITION (and,
     *  for chains, value-identical), across a turnover-active horizon exercising grow/depoly/sever/split/nucleation
     *  (the events that mutate chain membership). Runs on the host runner (the device path runs the identical
     *  kernels); the device==host bit-identity is covered by the formation CPU≡GPU gate once wired. */
    static void filIDCheck(Scene s, double dt) {
        FilamentStore f = s.fil; int n = f.n;
        IntArray ref = new IntArray(n);
        int M = STEPS > 0 ? STEPS : 15000;
        System.out.printf("%n=== GATE 1 — FilIDSystem (pointer-doubling, %d rounds) ≡ reference chain-walk over %d turnover-active steps ===%n", s.filIDRounds, M);
        int worstValue = 0, worstPartition = 0, checks = 0, minActive = n, maxActive = 0;
        long births = 0; int splits = 0, prevActive = activeSegments(f);
        for (int t = 0; t < M; t++) {
            cpuStep(s, t);   // turnover + nucleation (mutate chain) + formation (computeFilID = FilIDSystem) on cadence
            births += s.lastNucBirths;
            int actNow = activeSegments(f); if (actNow > prevActive) splits += (actNow - prevActive); prevActive = actNow;
            if (t % XL_CHECK_INT == 0) {
                refFilIDWalk(s, ref);
                int vmm = 0;
                for (int seg = 0; seg < n; seg++) if (s.filID.get(seg) != ref.get(seg)) vmm++;
                int pmm = (vmm > 0) ? partitionMismatch(s.filID, ref, n) : 0;   // value-identical ⟹ partition-identical
                worstValue = Math.max(worstValue, vmm); worstPartition = Math.max(worstPartition, pmm);
                int act = activeSegments(f); minActive = Math.min(minActive, act); maxActive = Math.max(maxActive, act);
                checks++;
            }
        }
        long grown = s.grow.pool.totalTaken(), returned = s.grow.pool.totalReturned();
        System.out.printf("  checks=%d (every %d steps); active segments %d→%d (split/death/nucleation churn ⇒ membership changed)%n",
                checks, XL_CHECK_INT, minActive, maxActive);
        System.out.printf("  turnover exercised: %d monomers grown / %d returned (depoly+sever); %d nucleation births; %d split/birth active-count increases%n", grown, returned, births, splits);
        System.out.printf("  worst per-check VALUE mismatches = %d ; worst PARTITION mismatches = %d%n", worstValue, worstPartition);
        System.out.printf("  GATE 1: %s%n", (worstPartition == 0 && worstValue == 0) ? "PASS — FilIDSystem ≡ reference walk (value-identical) every check" :
                worstPartition == 0 ? "PASS (partition) — value differs but same partition (acceptable)" : "*** FAIL — partition mismatch ***");
    }

    /** Count segments whose (filID-partition) membership disagrees between two labelings: i mismatches if any j has
     *  (a[i]==a[j]) != (b[i]==b[j]). O(n²) — probe-only. Returns the number of mismatching segments. */
    static int partitionMismatch(IntArray a, IntArray b, int n) {
        int bad = 0;
        for (int i = 0; i < n; i++) {
            boolean ok = true;
            for (int j = 0; j < n && ok; j++) if ((a.get(i) == a.get(j)) != (b.get(i) == b.get(j))) ok = false;
            if (!ok) bad++;
        }
        return bad;
    }

    static void formationCpu(Scene s, int step) {
        FilamentStore f = s.fil; CrosslinkerStore xl = s.xl;
        computeFilID(s);             // chain ids (exclude same-filament pairs) — must precede candidate generation
        xl.setFormStep(step, SEED);
        CrosslinkerSystem.countActiveLinks(xl.linkState, xl.linkFilA, xl.linkFilB, xl.activeLinkCount, xl.formCounts);
        s.fg.buildCpu(f);
        s.fg.formCandidatesCpu(f, s.filID, xl);
        CrosslinkerSystem.formGates(f.uVec, f.end1, f.end2, f.segLength, xl.reqFilA, xl.reqFilB, xl.reqLoc1, xl.reqLoc2,
                xl.reqOrient, xl.gatePass, xl.formParams, xl.formCounts);
        CrosslinkerSystem.formAdmitReduce(xl.reqFilA, xl.reqFilB, xl.gatePass, xl.minCand, xl.formCounts);
        CrosslinkerSystem.formAdmit(xl.reqFilA, xl.reqFilB, xl.reqLoc1, xl.reqLoc2, xl.gatePass, xl.minCand, xl.activeLinkCount,
                xl.linkState, xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2, xl.acceptFlag, xl.formParams, xl.formCounts);
        CrosslinkerSystem.freeFlags(xl.linkState, xl.freeCount, xl.allocCounts);
        CrossBridgeSystem.csrScan(xl.freeScanCounts, xl.freeCount, xl.freeOffsets);
        CrosslinkerSystem.freeScatter(xl.linkState, xl.freeOffsets, xl.freeList, xl.allocCounts);
        CrossBridgeSystem.csrScan(xl.rankScanCounts, xl.acceptFlag, xl.rankOffsets);
        CrosslinkerSystem.allocate(xl.reqFilA, xl.reqFilB, xl.reqLoc1, xl.reqLoc2, xl.rankOffsets, xl.freeList, xl.freeOffsets,
                xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2, xl.linkState, xl.strainHist, xl.strainPlace, xl.allocCounts);
        CrosslinkerSystem.placeOrient(xl.reqOrient, xl.rankOffsets, xl.freeList, xl.freeOffsets, xl.linkOrientSame,
                xl.torqueMagHist, xl.torqueMagPlace, xl.allocCounts);
    }

    static void allocCpu(FilamentStore f, IntArray accept, FloatArray rc, FloatArray ru, FloatArray ry, IntArray rankScan, IntArray rankOff) {
        FilamentBirthSystem.freeFlags(f.filState, f.freeCount, f.allocCounts);
        CrossBridgeSystem.csrScan(f.freeScanCounts, f.freeCount, f.freeOffsets);
        FilamentBirthSystem.freeScatter(f.filState, f.freeOffsets, f.freeList, f.allocCounts);
        CrossBridgeSystem.csrScan(rankScan, accept, rankOff);
        FilamentBirthSystem.allocate(rc, ru, ry, rankOff, f.freeList, f.freeOffsets,
                f.coord, f.uVec, f.yVec, f.brownTransScale, f.brownRotScale, f.filState, f.birthParams, f.allocCounts);
    }

    // ====================================================================== runner + the aberration hunt
    static void run(Scene s, double dt) {
        int M = STEPS;
        filRms0 = filRms(s.fil);
        double[] ext0 = netExtent(s); double rms0 = ext0[0];
        long t0 = System.nanoTime();
        boolean conservationOk = true; int worstPhantom = 0; boolean nanSeen = false; int nanStep = -1;
        double icPeakForceN = 0, steadyMaxForceN = 0, maxLinkN = 0; int peakLinks = 0; double rmsMin = rms0; int stepMin = 0;
        int warmup = Math.max(200, M / 50);   // settle the warm-start IC before recording the steady operating force
        long severMon = 0, depolyMon = 0;
        java.io.File statusFile = new java.io.File(".last_run_status");

        System.out.printf("%-8s %-9s %-7s %-7s %-7s %-7s %-7s %-8s %-9s %-9s%n",
                "step", "rms(µm)", "nBnd1", "nBnd2", "xlinks", "active", "births", "conc(µM)", "maxF(pN)", "fil-rms");
        for (int t = 0; t < M; t++) {
            cpuStep(s, t);
            depolyMon += s.depoly.returnedOffsets.get(s.fil.n);
            int sevRet = s.sever.severReturnedOffsets.get(s.fil.n); if (sevRet > 0) severMon += sevRet;
            int al = s.xl == null ? 0 : activeLinks(s.xl); peakLinks = Math.max(peakLinks, al);
            if (t % Math.max(1, M / 60) == 0 || t == M - 1) {
                // hunt: NaN, conservation, phantoms, force magnitudes
                boolean nan = anyNaN(s); if (nan && !nanSeen) { nanSeen = true; nanStep = t; }
                boolean cons = conservationCheck(s); if (!cons) conservationOk = false;
                int phantom = phantomCount(s.fil); worstPhantom = Math.max(worstPhantom, phantom);
                double mf = maxAbs(s.fil.forceSum) * 1e12;                    // pN on filaments
                icPeakForceN = Math.max(icPeakForceN, mf);
                if (t >= warmup) steadyMaxForceN = Math.max(steadyMaxForceN, mf);
                if (s.xl != null) maxLinkN = Math.max(maxLinkN, maxLinkForcePN(s));
                double[] ext = netExtent(s); double fr = filRms(s.fil);
                if (ext[0] < rmsMin) { rmsMin = ext[0]; stepMin = t; }
                System.out.printf("%-8d %-9.4f %-7d %-7d %-7d %-7d %-7d %-8.4f %-9.3g %-9.4f%s%s%n",
                        t, ext[0], boundTotal(s.mot), boundTotal(s.mot2), al, activeSegments(s.fil), s.lastNucBirths,
                        s.grow.pool.conc(), mf, fr,
                        cons ? "" : "  *CONSERVATION FAIL*", nan ? "  *NaN*" : "");
                writeStatus(statusFile, t, M, ext[0], al, activeSegments(s.fil), cons, phantom, nan, (System.nanoTime() - t0) / 1e9);
            }
            if (anyNaN(s)) { nanSeen = true; if (nanStep < 0) nanStep = t; System.out.println("  *** NON-FINITE at step " + t + " — BLOW-UP — aborting ***"); break; }
        }
        double secs = (System.nanoTime() - t0) / 1e9;
        double[] extEnd = netExtent(s); double shrink = (rms0 - extEnd[0]) / rms0;

        System.out.println("\n===== ABERRATION HUNT + SANITY =====");
        System.out.printf("runtime: %.1f s for %d steps (%.0f steps/s, CPU)%n", secs, M, M / secs);
        System.out.printf("node-net RMS extent: start=%.4f → min=%.4f @ %d → end=%.4f µm (%.1f%% shrink ⇒ %s)%n",
                rms0, rmsMin, stepMin, extEnd[0], 100 * shrink,
                shrink > 0.15 ? "COALESCING" : shrink > 0.04 ? "mild contraction" : shrink < -0.10 ? "DISPERSING" : "STABLE");
        System.out.printf("contraction: filament-network RMS start=%.4f → end=%.4f µm%n", filRms0, filRms(s.fil));
        System.out.printf("binding: node-shell bound=%d/%d, free-minifil bound=%d/%d; crosslinks peak=%d end=%d%n",
                boundTotal(s.mot), s.mot.nMotors, boundTotal(s.mot2), s.mot2.nMotors, peakLinks, s.xl == null ? 0 : activeLinks(s.xl));
        System.out.printf("turnover: pool taken=%d returned=%d monomers; pointed-depoly=%d, severing=%d monomers%n",
                s.grow.pool.totalTaken(), s.grow.pool.totalReturned(), depolyMon, severMon);
        System.out.println("--- HUNT verdict ---");
        System.out.printf("  NaN / blow-up:     %s%n", nanSeen ? "*** FOUND at step " + nanStep + " ***" : "none (finite throughout)");
        System.out.printf("  conservation:      %s (integer pool ledger every sampled step)%n", conservationOk ? "EXACT" : "*** DRIFT/FAIL ***");
        System.out.printf("  phantoms:          worst=%d (ACTIVE slots with monomerCount<=0; must be 0)%n", worstPhantom);
        System.out.printf("  max filament force: IC-peak(t=0)=%.3g pN ⇒ steady(t>%d)=%.3g pN (12 pN cross-bridge cap ON; the t=0 peak is a one-step warm-start relaxation transient, decays in <~66 steps)%n", icPeakForceN, warmup, steadyMaxForceN);
        System.out.printf("  max crosslink force:%.3g pN (Bell unbind + dynamic fracMove bound it)%n", maxLinkN);
        System.out.printf("  wall escapes:      %d filament endpoints outside the box (should be 0)%n", wallEscapes(s));
        System.out.printf("  node clipping:     min node-node center distance %.4f µm (2R=%.4f)%n", minNodeDist(s), 2 * NodeStore.NODE_RADIUS);
        System.out.printf("  same-chain links:  %d of %d active (a crosslink whose two segments are on the SAME filament — should be 0)%n",
                sameChainLinks(s), s.xl == null ? 0 : activeLinks(s.xl));

        if (gpuScale) gpuScaleCheck(s, dt);
    }

    static void writeStatus(java.io.File fp, int t, int M, double rms, int links, int active, boolean cons, int phantom, boolean nan, double secs) {
        try { java.nio.file.Files.writeString(fp.toPath(), String.format(java.util.Locale.US,
                "FULL_SYSTEM_DEMO step %d/%d (%.0f%%)  rms=%.4f  links=%d  active=%d  conservation=%s  phantoms=%d  nan=%b  elapsed=%.0fs%n",
                t, M, 100.0 * t / M, rms, links, active, cons ? "EXACT" : "FAIL", phantom, nan, secs)); }
        catch (java.io.IOException e) { /* status file is best-effort */ }
    }

    // ---- readout helpers ----
    static double filRms0 = 0;
    static double[] netExtent(Scene s) {
        RigidRodBody nb = s.node.node; int n = nb.n;
        double cx = 0, cy = 0, cz = 0;
        for (int k = 0; k < n; k++) { cx += nb.coord.get(k); cy += nb.coord.get(n + k); cz += nb.coord.get(2 * n + k); }
        cx /= n; cy /= n; cz /= n;
        double s2 = 0, minx=1e9,miny=1e9,minz=1e9,maxx=-1e9,maxy=-1e9,maxz=-1e9;
        for (int k = 0; k < n; k++) {
            double x = nb.coord.get(k), y = nb.coord.get(n + k), z = nb.coord.get(2 * n + k);
            double dx = x - cx, dy = y - cy, dz = z - cz; s2 += dx*dx + dy*dy + dz*dz;
            minx=Math.min(minx,x);maxx=Math.max(maxx,x);miny=Math.min(miny,y);maxy=Math.max(maxy,y);minz=Math.min(minz,z);maxz=Math.max(maxz,z);
        }
        double diag = Math.sqrt((maxx-minx)*(maxx-minx)+(maxy-miny)*(maxy-miny)+(maxz-minz)*(maxz-minz));
        return new double[]{ Math.sqrt(s2 / n), diag };
    }
    static double filRms(FilamentStore f) {
        int n = f.n, cnt = 0; double cx = 0, cy = 0, cz = 0;
        for (int i = 0; i < n; i++) { if (f.filState.get(i) < 0) continue; cx += f.coordX(i); cy += f.coordY(i); cz += f.coordZ(i); cnt++; }
        if (cnt == 0) return 0; cx /= cnt; cy /= cnt; cz /= cnt;
        double s2 = 0;
        for (int i = 0; i < n; i++) { if (f.filState.get(i) < 0) continue;
            double dx = f.coordX(i) - cx, dy = f.coordY(i) - cy, dz = f.coordZ(i) - cz; s2 += dx*dx + dy*dy + dz*dz; }
        return Math.sqrt(s2 / cnt);
    }
    static int boundTotal(MotorStore m) { int c = 0; for (int i = 0; i < m.nMotors; i++) if (m.boundSeg.get(i) >= 0) c++; return c; }
    static int activeLinks(CrosslinkerStore xl) { int c = 0; for (int k = 0; k < xl.nLinks; k++) if (xl.linkState.get(k) >= 0) c++; return c; }
    /** Count active crosslinks whose two segments belong to the same filament chain (the bug signature; should be 0). */
    static int sameChainLinks(Scene s) {
        if (s.xl == null) return 0;
        computeFilID(s);
        int c = 0;
        for (int k = 0; k < s.xl.nLinks; k++) {
            if (s.xl.linkState.get(k) < 0) continue;
            int a = s.xl.linkFilA.get(k), b = s.xl.linkFilB.get(k);
            if (a < 0 || b < 0 || s.fil.filState.get(a) < 0 || s.fil.filState.get(b) < 0) continue;
            if (s.filID.get(a) == s.filID.get(b)) c++;
        }
        return c;
    }
    static int activeSegments(FilamentStore f) { int c = 0; for (int s = 0; s < f.n; s++) if (f.filState.get(s) >= 0) c++; return c; }
    static long sumActiveMonomers(FilamentStore f) { long m = 0; for (int s = 0; s < f.n; s++) if (f.filState.get(s) >= 0) m += f.monomerCount.get(s); return m; }
    static double totalActinUM(Scene s) { return s.grow.pool.conc() + sumActiveMonomers(s.fil) * uMper; }
    static int phantomCount(FilamentStore f) { int c = 0; for (int s = 0; s < f.n; s++) if (f.filState.get(s) >= 0 && f.monomerCount.get(s) <= 0) c++; return c; }
    static boolean conservationCheck(Scene s) {
        long Fnow = sumActiveMonomers(s.fil);
        return Fnow == s.monInit + s.grow.pool.totalTaken() - s.grow.pool.totalReturned();
    }
    static double maxAbs(FloatArray a) { double m = 0; for (int i = 0; i < a.getSize(); i++) m = Math.max(m, Math.abs(a.get(i))); return m; }
    static boolean anyNaN(Scene s) {
        for (int i = 0; i < s.fil.coord.getSize(); i++) { float v = s.fil.coord.get(i); if (Float.isNaN(v) || Float.isInfinite(v)) return true; }
        for (int i = 0; i < s.node.node.coord.getSize(); i++) { float v = s.node.node.coord.get(i); if (Float.isNaN(v) || Float.isInfinite(v)) return true; }
        for (int i = 0; i < s.mini.backbone.coord.getSize(); i++) { float v = s.mini.backbone.coord.get(i); if (Float.isNaN(v) || Float.isInfinite(v)) return true; }
        return false;
    }
    static double maxLinkForcePN(Scene s) {
        CrosslinkerStore xl = s.xl; int ST = 6; double m = 0;
        for (int k = 0; k < xl.nLinks; k++) { if (xl.linkState.get(k) < 0) continue;
            double fx = xl.xlinkData.get(k*ST), fy = xl.xlinkData.get(k*ST+1), fz = xl.xlinkData.get(k*ST+2);
            m = Math.max(m, Math.sqrt(fx*fx + fy*fy + fz*fz) * 1e12); }
        return m;
    }
    static int wallEscapes(Scene s) {
        FilamentStore f = s.fil; int n = f.n, esc = 0;
        double hx = 0.5 * BOX_XY + 0.05, hz = 0.5 * BOX_Z + 0.05;
        for (int i = 0; i < n; i++) { if (f.filState.get(i) < 0) continue;
            for (int e = 0; e < 2; e++) {
                double x = (e==0?f.end1:f.end2).get(i), y = (e==0?f.end1:f.end2).get(n+i), z = (e==0?f.end1:f.end2).get(2*n+i);
                if (Math.abs(x) > hx || Math.abs(y) > hx || Math.abs(z) > hz) esc++;
            } }
        return esc;
    }
    static double minNodeDist(Scene s) {
        RigidRodBody nb = s.node.node; int n = nb.n; double mn = 1e9;
        for (int a = 0; a < n; a++) for (int bk = a + 1; bk < n; bk++) {
            double dx = nb.coord.get(a)-nb.coord.get(bk), dy = nb.coord.get(n+a)-nb.coord.get(n+bk), dz = nb.coord.get(2*n+a)-nb.coord.get(2*n+bk);
            mn = Math.min(mn, Math.sqrt(dx*dx+dy*dy+dz*dz));
        }
        return mn;
    }

    // ====================================================================== GPU device-resident SPLIT check
    /** The maximal composition made device-resident by SPLITTING the ~106-task per-step sequence into N chained
     *  TaskGraphs that share SoA buffers on the device (persistOnDevice / consumeFromDevice) under one
     *  TornadoExecutionPlan — no per-step host round-trip between sub-graphs. Replaces the monolithic single-graph
     *  path that hit TornadoVM's "Graph resize not implemented" single-TaskGraph capacity limit (§6 finding). */
    static void gpuScaleCheck(Scene s, double dt) {
        System.out.printf("%n--- GPU device-resident SPLIT check (maximal composition across %d chained TaskGraphs) ---%n", N_SPLIT);
        System.out.println("  persistOnDevice/consumeFromDevice keep the SoA buffers resident across sub-graphs; host pulls only the");
        System.out.println("  integer pool-ledger offsets per step (the same UNDER_DEMAND pulls the monolith used). No kernel/order edits.");
        Scene g = build(dt);
        filRms0gpu = filRms(g.fil);
        TornadoExecutionPlan plan;
        try { plan = buildPlanSplit(g); }
        catch (Throwable e) { System.out.println("  GPU SPLIT plan build FAILED: " + e); e.printStackTrace(); return; }
        int M = gpuSteps;
        long t0 = System.nanoTime();
        try { for (int t = 0; t < M; t++) stepSplit(g, t, dt, plan); }
        catch (Throwable e) {
            String msg = String.valueOf(e);
            if (msg.contains("Graph resize"))
                System.out.println("  *** Graph-resize on the SPLIT path — a sub-graph STILL exceeds capacity; partition finer (raise N_SPLIT). ***");
            else System.out.println("  GPU SPLIT run threw in the step loop: " + e);
            e.printStackTrace();
            return;
        }
        double secs = (System.nanoTime() - t0) / 1e9;
        pullRenderState(g);   // pull the device-resident binding/geometry state for the aggregate comparison
        System.out.printf("  GPU SPLIT (%d chained graphs) ran %d steps in %.1f s (%.0f steps/s) device-resident, NO crash/race%n", N_SPLIT, M, secs, M / secs);
        System.out.printf("  GPU aggregate: node-bound=%d, minifil-bound=%d, active=%d, xlinks=%d, conc=%.4f µM, conservation=%s, phantoms=%d, wall-escapes=%d, same-chain-links=%d%n",
                boundTotal(g.mot), boundTotal(g.mot2), activeSegments(g.fil), g.xl == null ? 0 : activeLinks(g.xl), g.grow.pool.conc(),
                conservationCheck(g) ? "EXACT" : "*** FAIL ***", phantomCount(g.fil), wallEscapes(g), sameChainLinks(g));
        double gpuShrink = 100 * (filRms0gpu - filRms(g.fil)) / filRms0gpu;
        Scene c = build(dt); double cRms0 = filRms(c.fil); long c0 = System.nanoTime(); for (int t = 0; t < M; t++) cpuStep(c, t); double csecs = (System.nanoTime() - c0) / 1e9;
        double cpuShrink = 100 * (cRms0 - filRms(c.fil)) / cRms0;
        boolean agree = Math.abs(activeSegments(g.fil) - activeSegments(c.fil)) <= Math.max(8, (int)(0.2 * activeSegments(c.fil)))
                && Math.abs(boundTotal(g.mot2) - boundTotal(c.mot2)) <= Math.max(12, (int)(0.4 * Math.max(1, boundTotal(c.mot2))))
                && Math.abs(boundTotal(g.mot) - boundTotal(c.mot)) <= Math.max(10, (int)(0.5 * Math.max(1, boundTotal(c.mot))));
        System.out.printf("  CPU ran %d steps in %.1f s (%.0f steps/s) ⇒ device-resident split is %.1fx the CPU runner%n",
                M, csecs, M / csecs, (M / secs) / (M / csecs));
        System.out.printf("  CPU≡GPU aggregate @ %d steps: active GPU=%d CPU=%d, node-bound GPU=%d CPU=%d, minifil-bound GPU=%d CPU=%d ⇒ %s%n",
                M, activeSegments(g.fil), activeSegments(c.fil), boundTotal(g.mot), boundTotal(c.mot), boundTotal(g.mot2), boundTotal(c.mot2),
                agree ? "AGREE (chaotic-many-body, within tolerance)" : "*DIFFER — investigate*");
        if (g.xl != null)
            System.out.printf("  *** PAYOFF (live bundling on device) @ %d steps: xlinks GPU=%d CPU=%d ; filament-RMS shrink GPU=%.2f%% CPU=%.2f%% (same-chain-links GPU=%d) ***%n",
                    M, activeLinks(g.xl), activeLinks(c.xl), gpuShrink, cpuShrink, sameChainLinks(g));
    }
    static double filRms0gpu = 0;

    static int vramUsedMB() {
        try { return Integer.parseInt(vramMB().replace("MiB", "").trim()); } catch (Throwable e) { return -1; }
    }

    /** Density/scale-sweep point (MEASUREMENT-ONLY). One SHORT GPU window on the device-resident split — profiler-on
     *  (SILENT + clearProfiles, so kernel-compute fraction is read AND the production-faithful per-step device wall
     *  pStepWall = Σ exec-wall + host is reported, EXCLUDING the profiler-read overhead) — plus VRAM, physical-sanity,
     *  and a capped CPU comparison window. Prints one parseable SWEEP_ROW. Short window keeps the §8 per-execute creep
     *  out of the per-step rate (this is the rate AT this fixed scale, not a long run). No physics/kernel/order edit. */
    static void sweepRun(Scene s, double dt) {
        int nNodes = s.nNodes, nMini = s.nMini, heads = s.mot.nMotors + s.mot2.nMotors;
        System.out.printf("%n=== SCALE-SWEEP POINT (scale ×%.2f) — short device-resident window ===%n", SCALE);
        System.out.printf("  scene: %d nodes, %d minifils, FIL_CAP %d, %d active segs, %d heads, %d xlink slots, box %.2f×%.2f×%.2f µm%n",
                nNodes, nMini, FIL_CAP, activeSegments(s.fil), heads, s.xl == null ? 0 : s.xl.nLinks, BOX_XY, BOX_XY, BOX_Z);

        Scene g = build(dt);                       // fresh scene for the device run (build() mutates store device buffers)
        double rms0 = filRms(g.fil);
        TornadoExecutionPlan plan;
        try { plan = buildPlanSplit(g); }
        catch (Throwable e) {
            String msg = String.valueOf(e);
            if (msg.contains("Graph resize"))
                System.out.println("  *** CAPACITY EVENT: Graph-resize at scale ×" + SCALE + " — a sub-graph exceeds TornadoVM capacity (a scaling finding). ***");
            else System.out.println("  GPU SPLIT plan build FAILED at scale ×" + SCALE + ": " + e);
            e.printStackTrace();
            return;
        }
        plan = plan.withProfiler(ProfilerMode.SILENT);
        int nG = N_SPLIT, W = profWarm, M = gpuSteps;

        // warmup (FIRST_EXECUTION uploads + PTX JIT), then peak VRAM baseline
        try { for (int t = 0; t < W; t++) profStep(g, t, dt, plan, null); }
        catch (Throwable e) {
            String msg = String.valueOf(e);
            if (msg.contains("memory") || msg.contains("OOM") || msg.contains("CL_OUT") || msg.contains("CUDA"))
                System.out.println("  *** CAPACITY EVENT: device-memory/launch failure at scale ×" + SCALE + " during warmup (a scaling finding): " + e);
            else System.out.println("  GPU SPLIT warmup threw at scale ×" + SCALE + ": " + e);
            e.printStackTrace(); return;
        }
        int vramWarm = vramUsedMB();

        // measured window — profiler-on for the kernel fraction; pStepWall excludes the profiler reads (faithful)
        Acc a = new Acc(nG);
        int vramPeak = vramWarm;
        long t0 = System.nanoTime();
        try {
            for (int i = 0; i < M; i++) {
                profStep(g, W + i, dt, plan, a);
                if ((i + 1) % Math.max(1, M / 3) == 0) vramPeak = Math.max(vramPeak, vramUsedMB());
            }
        } catch (Throwable e) { System.out.println("  GPU SPLIT measured window threw at scale ×" + SCALE + ": " + e); e.printStackTrace(); return; }
        double gpuLoopSecs = (System.nanoTime() - t0) / 1e9;

        long sumKern=0, sumWall=0, sumIn=0, sumOut=0;
        for (int gi=0; gi<nG; gi++) { sumKern+=a.gKern[gi]; sumWall+=a.gWall[gi]; sumIn+=a.gIn[gi]; sumOut+=a.gOut[gi]; }
        double pStepWall = (sumWall + a.hostBkkp + a.hostPull) / 1e6 / M;   // ms — production-faithful device step
        double pKern = sumKern / 1e6 / M;                                   // ms — GPU kernel-compute
        double gpuSps = 1000.0 / pStepWall;
        double kernPct = 100.0 * pKern / pStepWall;
        double copyKB = (sumIn + sumOut) / 1024.0 / M;

        pullRenderState(g);
        boolean cons = conservationCheck(g); int phantom = phantomCount(g.fil); int esc = wallEscapes(g); boolean nan = anyNaN(g);
        double gpuShrink = 100.0 * (rms0 - filRms(g.fil)) / rms0;
        int active = activeSegments(g.fil), xlinks = g.xl == null ? 0 : activeLinks(g.xl);
        boolean broken = !cons || phantom > 0 || nan;

        // capped CPU comparison (serial ∝ N; small window at large scale, extrapolate the linear curve)
        double cpuSps = Double.NaN;
        if (cpuCap > 0 && !broken) {
            Scene c = build(dt);
            long c0 = System.nanoTime();
            for (int t = 0; t < cpuCap; t++) cpuStep(c, t);
            double csecs = (System.nanoTime() - c0) / 1e9;
            cpuSps = cpuCap / csecs;
        }
        double ratio = Double.isNaN(cpuSps) ? Double.NaN : gpuSps / cpuSps;

        System.out.printf("  GPU window: %d warmup + %d measured steps in %.1f s (raw loop %.0f steps/s incl. profiler reads)%n", W, M, gpuLoopSecs, M / gpuLoopSecs);
        System.out.printf("  VRAM: warmup %d MiB, peak %d MiB%n", vramWarm, vramPeak);
        System.out.printf("  sanity: conservation=%s phantoms=%d wall-escapes=%d NaN=%b active=%d xlinks=%d shrink=%.2f%%%n",
                cons ? "EXACT" : "*** FAIL ***", phantom, esc, nan, active, xlinks, gpuShrink);
        if (broken) System.out.println("  *** HARD BAIL: physically broken at this scale — perf numbers NOT reported for a broken trajectory. ***");
        else {
            System.out.printf("  GPU %.1f steps/s (faithful per-step device wall %.3f ms); kernel-compute %.1f%% of step; per-step copy %.2f KB%n",
                    gpuSps, pStepWall, kernPct, copyKB);
            if (cpuCap > 0) System.out.printf("  CPU %.1f steps/s (%d-step cap) ⇒ GPU/CPU ratio %.2fx%n", cpuSps, cpuCap, ratio);
        }
        // one machine-parseable row for the sweep table
        System.out.printf("SWEEP_ROW scale=%.2f nodes=%d minifils=%d cap=%d active=%d heads=%d boxxy=%.2f gpuSPS=%.1f cpuSPS=%.1f ratio=%.2f kernPct=%.1f vramMiB=%d copyKB=%.2f cons=%s phantom=%d esc=%d nan=%b xlinks=%d shrinkPct=%.2f broken=%b%n",
                SCALE, nNodes, nMini, FIL_CAP, active, heads, BOX_XY, gpuSps, cpuSps, ratio, kernPct, vramPeak, copyKB,
                cons ? "EXACT" : "FAIL", phantom, esc, nan, xlinks, gpuShrink, broken);
    }

    /** Device-resident graph: turnover + nucleation + node shell + free minifilaments (xlinks: CPU-side formation,
     *  device force omitted from this probe — the xlink device path is validated in DenseContractile/XlinkFormation).
     *  Mirrors cpuStep's task order; built best-effort. */
    static TornadoExecutionPlan buildPlan(Scene s) {
        MotorStore mot = s.mot; DimerStore dim = s.dim; NodeStore nd = s.node;
        MotorStore mot2 = s.mot2; DimerStore dim2 = s.dim2; MiniFilamentStore mini = s.mini;
        FilamentStore f = s.fil; NodeNucleationStore nuc = s.nuc; GrowthStore grow = s.grow; DepolyStore d = s.depoly;
        AgingStore ag = s.aging; SeverStore sv = s.sever;
        RigidRodBody b = mot.body, nb = nd.node, b2 = mot2.body, bb = mini.backbone; SpatialBodyView v = s.view;
        TaskGraph tg = new TaskGraph("fulldemo")
            .transferToDevice(DataTransferMode.FIRST_EXECUTION,
                    b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, b.bTransGam, b.bRotGam,
                    b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.brownTransScale, b.brownRotScale,
                    mot.head, mot.uVec, mot.rodUVec, mot.boundSeg, mot.bindArc, mot.nucleotideState,
                    mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.stats, mot.capStats, mot.cooldown,
                    mot.bodyParams, mot.jointParams, mot.nucParams, mot.kinParams,
                    dim.motorA, dim.motorB, dim.parallel, dim.dimerParams,
                    nb.coord, nb.uVec, nb.yVec, nb.zVec, nb.end1, nb.end2, nb.segLength, nb.bTransGam, nb.bRotGam,
                    nb.forceSum, nb.torqueSum, nb.randForce, nb.randTorque, nb.brownTransScale, nb.brownRotScale, nd.nodeBodyParams,
                    nd.nodeInvTransY, nd.attachNode, nd.attachKey, nd.radial, nd.attachCoeffK, nd.nodeData, nd.nodeParams,
                    nd.nodeAttachCount, nd.nodeAttachOffsets, nd.nodeAttachList, nd.nodeCounts4,
                    s.bondData, s.xbParams, s.segMotorCount, s.segMotorOffsets, s.segMotorMyo, s.reachSeg, s.reachCount, s.boxParams,
                    f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.monomerCount, f.bTransGam, f.bRotGam, f.bTransDiff, f.bRotDiff,
                    f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.params, f.chainParams,
                    f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide, f.filState,
                    f.freeCount, f.freeOffsets, f.freeList, f.freeScanCounts, f.rankOffsets, f.rankScanCounts, f.allocCounts, f.birthParams,
                    f.acceptFlag, f.reqCoord, f.reqUVec, f.reqYVec,
                    grow.grewFlag, grow.grewOffsets, grow.grewScanCounts, grow.splitParams, grow.dragParams,
                    d.depolyParams, d.returnedMon, d.returnedOffsets, d.returnScanCounts, d.deathFlag,
                    ag.nucFrac, ag.agingParams, ag.depolyRateParams,
                    sv.cofFrac, sv.cofilinParams, sv.severDeathFlag, sv.severReturnedMon, sv.severReturnedOffsets, sv.severScanCounts,
                    nuc.seedNode, nuc.nodeBoundFil, nuc.nucParams, nuc.tetherParams, nuc.seedParams,
                    s.nucAccept, s.nucReqCoord, s.nucReqUVec, s.nucReqYVec, s.nucRankOffsets, s.nucRankScanCounts,
                    b2.coord, b2.uVec, b2.yVec, b2.zVec, b2.end1, b2.end2, b2.segLength, b2.bTransGam, b2.bRotGam,
                    b2.forceSum, b2.torqueSum, b2.randForce, b2.randTorque, b2.brownTransScale, b2.brownRotScale,
                    mot2.head, mot2.uVec, mot2.rodUVec, mot2.boundSeg, mot2.bindArc, mot2.nucleotideState, mot2.reach,
                    mot2.forceDotFil, mot2.forceMag, mot2.forceDotHist, mot2.forceDotPlace, mot2.stats, mot2.capStats, mot2.cooldown,
                    mot2.bodyParams, mot2.jointParams, mot2.nucParams, mot2.kinParams, mot2.publishParams,
                    dim2.motorA, dim2.motorB, dim2.parallel, dim2.dimerParams,
                    bb.coord, bb.uVec, bb.yVec, bb.zVec, bb.end1, bb.end2, bb.segLength, bb.bTransGam, bb.bRotGam,
                    bb.forceSum, bb.torqueSum, bb.randForce, bb.randTorque, bb.brownTransScale, bb.brownRotScale, mini.bbBodyParams,
                    mini.bbInvDragY, mini.headBackboneSlot, mini.motorA, mini.attachAxial, mini.miniData, mini.miniParams,
                    mini.bbDimerCount, mini.bbDimerOffsets, mini.bbDimerList, mini.miniCounts,
                    s.bondData2, s.segMotorCount2, s.segMotorOffsets2, s.segMotorMyo2, s.reachSeg2, s.reachCount2,
                    v.center, v.boundingRadius, v.ownerStore, v.ownerSlot, s.gridParams, s.gridDims, s.gridCounts, s.viewParams,
                    s.bodyCell, s.cellCount, s.chunkSum, s.gridCellOffsets, s.gridCellContents, s.chunkParams, s.chunkCellCount)
            .transferToDevice(DataTransferMode.EVERY_EXECUTION, mot.counts, nd.nodeBodyCounts, f.counts, grow.growCounts, grow.growParams,
                    nuc.nucCounts, d.depolyCounts, ag.agingCounts, sv.severCounts, mot2.counts, mini.bbCounts);
        // turnover + nucleation (Ring3x3 task order)
        tg = tg
            .task("age", AgingSystem::age, f.filState, ag.nucFrac, ag.agingParams, ag.agingCounts)
            .task("depoly", DepolySystem::depolyProxy, f.filState, f.monomerCount, f.coord, f.uVec, f.end1NbrSlot, ag.nucFrac, d.returnedMon, d.deathFlag, d.depolyParams, ag.depolyRateParams, d.depolyCounts)
            .task("csrReturn", CrossBridgeSystem::csrScan, d.returnScanCounts, d.returnedMon, d.returnedOffsets)
            .task("applyDeath", DepolySystem::applyDeath, f.filState, f.monomerCount, nuc.seedNode, f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide, f.brownTransScale, f.brownRotScale, d.deathFlag, d.depolyCounts)
            .task("grow", GrowthSystem::grow, nuc.seedNode, f.monomerCount, f.coord, f.uVec, grow.grewFlag, grow.growParams, grow.growCounts)
            .task("csrGrew", CrossBridgeSystem::csrScan, grow.grewScanCounts, grow.grewFlag, grow.grewOffsets)
            .task("growthAtp", AgingSystem::growthAtp, grow.grewFlag, f.monomerCount, ag.nucFrac)
            .task("cofAcc", SeveringSystem::cofilinAccumulate, f.filState, ag.nucFrac, sv.cofFrac, sv.cofilinParams, sv.severCounts)
            .task("cofDis", SeveringSystem::cofilinDissolve, f.filState, f.monomerCount, sv.cofFrac, sv.severDeathFlag, sv.severReturnedMon, sv.cofilinParams, sv.severCounts)
            .task("csrSever", CrossBridgeSystem::csrScan, sv.severScanCounts, sv.severReturnedMon, sv.severReturnedOffsets)
            .task("severDeath", DepolySystem::applyDeath, f.filState, f.monomerCount, nuc.seedNode, f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide, f.brownTransScale, f.brownRotScale, sv.severDeathFlag, d.depolyCounts)
            .task("markSplits", GrowthSystem::markSplits, nuc.seedNode, f.monomerCount, f.coord, f.uVec, f.yVec, f.acceptFlag, f.reqCoord, f.reqUVec, f.reqYVec, grow.splitParams, grow.growCounts)
            .task("gFreeFlags", FilamentBirthSystem::freeFlags, f.filState, f.freeCount, f.allocCounts)
            .task("gCsrFree", CrossBridgeSystem::csrScan, f.freeScanCounts, f.freeCount, f.freeOffsets)
            .task("gFreeScatter", FilamentBirthSystem::freeScatter, f.filState, f.freeOffsets, f.freeList, f.allocCounts)
            .task("gCsrRank", CrossBridgeSystem::csrScan, f.rankScanCounts, f.acceptFlag, f.rankOffsets)
            .task("gAllocate", FilamentBirthSystem::allocate, f.reqCoord, f.reqUVec, f.reqYVec, f.rankOffsets, f.freeList, f.freeOffsets, f.coord, f.uVec, f.yVec, f.brownTransScale, f.brownRotScale, f.filState, f.birthParams, f.allocCounts)
            .task("splitWire", GrowthSystem::splitWire, f.rankOffsets, f.freeList, f.freeOffsets, f.monomerCount, f.coord, f.uVec, f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide, nuc.seedNode, grow.splitParams, f.allocCounts)
            .task("splitInherit", AgingSystem::splitInheritNuc, f.rankOffsets, f.freeList, f.freeOffsets, ag.nucFrac, f.allocCounts)
            .task("splitCof", SeveringSystem::nucleateFreshCofilin, f.rankOffsets, f.freeList, f.freeOffsets, sv.cofFrac, f.allocCounts)
            .task("recomputeDrag", GrowthSystem::recomputeDrag, f.monomerCount, f.segLength, f.end1NbrSlot, f.end2NbrSlot, f.bTransGam, f.bRotGam, f.bTransDiff, f.bRotDiff, grow.dragParams, grow.growCounts)
            .task("count", NodeNucleationSystem::countBoundFil, nuc.seedNode, nuc.nodeBoundFil, nuc.nucCounts)
            .task("emit", NodeNucleationSystem::emit, nb.coord, nuc.nodeBoundFil, s.nucAccept, s.nucReqCoord, s.nucReqUVec, s.nucReqYVec, nuc.nucParams, nuc.nucCounts)
            .task("nFreeFlags", FilamentBirthSystem::freeFlags, f.filState, f.freeCount, f.allocCounts)
            .task("nCsrFree", CrossBridgeSystem::csrScan, f.freeScanCounts, f.freeCount, f.freeOffsets)
            .task("nFreeScatter", FilamentBirthSystem::freeScatter, f.filState, f.freeOffsets, f.freeList, f.allocCounts)
            .task("nCsrRank", CrossBridgeSystem::csrScan, s.nucRankScanCounts, s.nucAccept, s.nucRankOffsets)
            .task("nAllocate", FilamentBirthSystem::allocate, s.nucReqCoord, s.nucReqUVec, s.nucReqYVec, s.nucRankOffsets, f.freeList, f.freeOffsets, f.coord, f.uVec, f.yVec, f.brownTransScale, f.brownRotScale, f.filState, f.birthParams, f.allocCounts)
            .task("tagSeeds", NodeNucleationSystem::tagSeeds, s.nucRankOffsets, f.freeList, f.freeOffsets, nuc.seedNode, f.allocCounts)
            .task("initNewborn", NodeNucleationSystem::initNewborn, s.nucRankOffsets, f.freeList, f.freeOffsets, f.monomerCount, f.segLength, nuc.seedParams, f.allocCounts)
            .task("nucFresh", AgingSystem::nucleateFreshAtp, s.nucRankOffsets, f.freeList, f.freeOffsets, ag.nucFrac, f.allocCounts)
            .task("nucCof", SeveringSystem::nucleateFreshCofilin, s.nucRankOffsets, f.freeList, f.freeOffsets, sv.cofFrac, f.allocCounts)
        // node-shell binding (brute node-aware)
            .task("publishHead", MotorStore::publishHeadFromBody, b.coord, b.uVec, b.segLength, mot.head, mot.uVec, mot.rodUVec, mot.counts)
            .task("reach", BindingDetectionSystem::bruteReachableNodeAware, mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, s.reachSeg, s.reachCount, nuc.seedNode, mot.kinParams, mot.counts)
            .task("release", NucleotideCycleSystem::catchSlipRelease, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.cooldown, mot.stats, mot.capStats, mot.kinParams, mot.counts)
            .task("bind", BindingDetectionSystem::bindNearestNodeAware, mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, s.reachSeg, s.reachCount, mot.boundSeg, mot.bindArc, nuc.seedNode, mot.kinParams, mot.counts)
            .task("cycle", NucleotideCycleSystem::cycle, mot.nucleotideState, mot.boundSeg, mot.forceDotHist, mot.nucParams, mot.counts)
        // free-minifil binding (parallel grid)
            .task("publishHead2", MotorStore::publishHeadFromBody, b2.coord, b2.uVec, b2.segLength, mot2.head, mot2.uVec, mot2.rodUVec, mot2.counts)
            .task("filPublish", FilamentStore::publishToBodyView, f.coord, f.segLength, v.center, v.boundingRadius, v.ownerStore, v.ownerSlot, s.viewParams, s.gridCounts)
            .task("motPublish", MotorStore::publishToBodyView, mot2.head, mot2.reach, v.center, v.boundingRadius, v.ownerStore, v.ownerSlot, mot2.publishParams, mot2.counts)
            .task("bodyCell", SpatialGrid::bodyCell, v.center, s.gridParams, s.gridDims, s.gridCounts, s.bodyCell)
            .task("chunkZero", SpatialGrid::gridChunkZero, s.chunkParams, s.gridDims, s.chunkCellCount)
            .task("chunkHist", SpatialGrid::gridChunkHistogram, s.bodyCell, s.gridCounts, s.chunkParams, s.gridDims, s.chunkCellCount)
            .task("chunkReduce", SpatialGrid::gridChunkReduce, s.gridDims, s.chunkParams, s.chunkCellCount, s.cellCount)
            .task("gScanLocal", SpatialGrid::gridScanLocal, s.gridDims, s.cellCount, s.gridCellOffsets, s.chunkSum)
            .task("gScanChunks", SpatialGrid::gridScanChunks, s.gridDims, s.chunkSum)
            .task("gScanAdd", SpatialGrid::gridScanAdd, s.gridDims, s.gridCellOffsets, s.gridCellContents, s.cellCount, s.chunkSum)
            .task("chunkScatter", SpatialGrid::gridChunkScatter, s.bodyCell, s.gridCounts, s.chunkParams, s.gridDims, s.gridCellOffsets, s.gridCellContents, s.chunkCellCount)
            .task("gridReach", BindingDetectionSystem::gridReachable, mot2.head, mot2.uVec, mot2.rodUVec, f.end1, f.end2, s.gridParams, s.gridDims, s.gridCellOffsets, s.gridCellContents, v.ownerStore, v.ownerSlot, s.reachSeg2, s.reachCount2, mot2.kinParams, mot2.counts)
            .task("release2", NucleotideCycleSystem::catchSlipRelease, mot2.boundSeg, mot2.forceDotFil, mot2.forceMag, mot2.cooldown, mot2.stats, mot2.capStats, mot2.kinParams, mot2.counts)
            .task("bind2", BindingDetectionSystem::bindNearest, mot2.head, mot2.uVec, mot2.rodUVec, f.end1, f.end2, s.reachSeg2, s.reachCount2, mot2.boundSeg, mot2.bindArc, mot2.kinParams, mot2.counts)
            .task("cycle2", NucleotideCycleSystem::cycle, mot2.nucleotideState, mot2.boundSeg, mot2.forceDotHist, mot2.nucParams, mot2.counts)
        // forces
            .task("zeroMot", ChainBendingForceSystem::zeroAccumulators, b.forceSum, b.torqueSum, mot.counts)
            .task("zeroNode", ChainBendingForceSystem::zeroAccumulators, nb.forceSum, nb.torqueSum, nd.nodeBodyCounts)
            .task("zeroMot2", ChainBendingForceSystem::zeroAccumulators, b2.forceSum, b2.torqueSum, mot2.counts)
            .task("zeroBb", ChainBendingForceSystem::zeroAccumulators, bb.forceSum, bb.torqueSum, mini.bbCounts)
            .task("zeroFil", ChainBendingForceSystem::zeroAccumulators, f.forceSum, f.torqueSum, f.counts)
            .task("brownMot", BrownianForceSystem::brownianForce, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, b.brownTransScale, b.brownRotScale, mot.bodyParams, mot.counts)
            .task("brownNode", BrownianForceSystem::brownianForce, nb.randForce, nb.randTorque, nb.bTransGam, nb.bRotGam, nb.brownTransScale, nb.brownRotScale, nd.nodeBodyParams, nd.nodeBodyCounts)
            .task("brownMot2", BrownianForceSystem::brownianForce, b2.randForce, b2.randTorque, b2.bTransGam, b2.bRotGam, b2.brownTransScale, b2.brownRotScale, mot2.bodyParams, mot2.counts)
            .task("brownBb", BrownianForceSystem::brownianForce, bb.randForce, bb.randTorque, bb.bTransGam, bb.bRotGam, bb.brownTransScale, bb.brownRotScale, mini.bbBodyParams, mini.bbCounts)
            .task("brownFil", BrownianForceSystem::brownianForce, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.brownTransScale, f.brownRotScale, f.params, f.counts)
            .task("joints", MotorJointSystem::joints, b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, mot.nucleotideState, mot.jointParams, mot.counts)
            .task("dimer", DimerCouplingSystem::couple, b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, dim.motorA, dim.motorB, dim.parallel, dim.dimerParams, mot.boundSeg)
            .task("tether", NodeSystem::tether, b.coord, b.uVec, b.segLength, b.bTransGam, b.forceSum, b.torqueSum, nb.coord, nb.uVec, nb.yVec, nd.nodeInvTransY, nd.attachKey, nd.radial, nd.attachCoeffK, nd.nodeData, nd.nodeParams)
            .task("ndHist", CrossBridgeSystem::csrHistogram, nd.attachNode, nd.nodeCounts4, nd.nodeAttachCount)
            .task("ndScan", CrossBridgeSystem::csrScan, nd.nodeCounts4, nd.nodeAttachCount, nd.nodeAttachOffsets)
            .task("ndScatter", CrossBridgeSystem::csrScatter, nd.attachNode, nd.nodeCounts4, nd.nodeAttachOffsets, nd.nodeAttachCount, nd.nodeAttachList)
            .task("ndGather", MiniFilamentSystem::backboneGather, nd.nodeAttachOffsets, nd.nodeAttachList, nd.nodeData, nb.forceSum, nb.torqueSum, nd.nodeCounts4)
            .task("bond", CrossBridgeSystem::bondForces, b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength, mot.boundSeg, mot.bindArc, mot.nucleotideState, s.bondData, s.xbParams)
            .task("applyHead", CrossBridgeSystem::applyHeadForce, s.bondData, b.forceSum, b.torqueSum, mot.counts)
            .task("joints2", MotorJointSystem::joints, b2.coord, b2.uVec, b2.segLength, b2.bTransGam, b2.bRotGam, b2.forceSum, b2.torqueSum, mot2.nucleotideState, mot2.jointParams, mot2.counts)
            .task("dimer2", DimerCouplingSystem::couple, b2.coord, b2.uVec, b2.segLength, b2.bTransGam, b2.bRotGam, b2.forceSum, b2.torqueSum, dim2.motorA, dim2.motorB, dim2.parallel, dim2.dimerParams, mot2.boundSeg)
            .task("tether2", MiniFilamentSystem::tether, b2.coord, b2.uVec, b2.segLength, b2.bTransGam, b2.bRotGam, b2.forceSum, b2.torqueSum, bb.coord, bb.uVec, mini.bbInvDragY, mini.headBackboneSlot, mini.motorA, mini.attachAxial, mini.miniData, mini.miniParams)
            .task("bbHist", CrossBridgeSystem::csrHistogram, mini.headBackboneSlot, mini.miniCounts, mini.bbDimerCount)
            .task("bbScan", CrossBridgeSystem::csrScan, mini.miniCounts, mini.bbDimerCount, mini.bbDimerOffsets)
            .task("bbScatter", CrossBridgeSystem::csrScatter, mini.headBackboneSlot, mini.miniCounts, mini.bbDimerOffsets, mini.bbDimerCount, mini.bbDimerList)
            .task("bbGather", MiniFilamentSystem::backboneGather, mini.bbDimerOffsets, mini.bbDimerList, mini.miniData, bb.forceSum, bb.torqueSum, mini.miniCounts)
            .task("bond2", CrossBridgeSystem::bondForces, b2.coord, b2.uVec, b2.yVec, b2.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength, mot2.boundSeg, mot2.bindArc, mot2.nucleotideState, s.bondData2, s.xbParams)
            .task("applyHead2", CrossBridgeSystem::applyHeadForce, s.bondData2, b2.forceSum, b2.torqueSum, mot2.counts)
            .task("chain", ChainBendingForceSystem::chainForces, f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide, f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts)
            .task("seedTether", NodeNucleationSystem::seedTether, f.coord, f.uVec, f.segLength, f.bTransGam, f.forceSum, f.torqueSum, nb.coord, nd.nodeInvTransY, nuc.seedNode, nuc.tetherParams)
            .task("seedReact", NodeNucleationSystem::seedTetherNodeReact, f.coord, f.uVec, f.segLength, f.bTransGam, nb.forceSum, nb.coord, nd.nodeInvTransY, nuc.seedNode, nuc.tetherParams)
            .task("filHist", CrossBridgeSystem::csrHistogram, mot.boundSeg, mot.counts, s.segMotorCount)
            .task("filScan", CrossBridgeSystem::csrScan, mot.counts, s.segMotorCount, s.segMotorOffsets)
            .task("filScatter", CrossBridgeSystem::csrScatter, mot.boundSeg, mot.counts, s.segMotorOffsets, s.segMotorCount, s.segMotorMyo)
            .task("filGather", CrossBridgeSystem::segGather, s.segMotorOffsets, s.segMotorMyo, s.bondData, f.forceSum, f.torqueSum, mot.counts)
            .task("filHist2", CrossBridgeSystem::csrHistogram, mot2.boundSeg, mot2.counts, s.segMotorCount2)
            .task("filScan2", CrossBridgeSystem::csrScan, mot2.counts, s.segMotorCount2, s.segMotorOffsets2)
            .task("filScatter2", CrossBridgeSystem::csrScatter, mot2.boundSeg, mot2.counts, s.segMotorOffsets2, s.segMotorCount2, s.segMotorMyo2)
            .task("filGather2", CrossBridgeSystem::segGather, s.segMotorOffsets2, s.segMotorMyo2, s.bondData2, f.forceSum, f.torqueSum, mot2.counts)
            .task("register", CrossBridgeSystem::registerForceDot, s.bondData, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.counts)
            .task("register2", CrossBridgeSystem::registerForceDot, s.bondData2, mot2.boundSeg, mot2.forceDotFil, mot2.forceMag, mot2.forceDotHist, mot2.forceDotPlace, mot2.counts)
        // containment + integrate
            .task("confineNode", ContainmentSystem::confine, nb.coord, nb.uVec, nb.segLength, nb.bTransGam, nb.forceSum, nb.torqueSum, s.boxParams, nd.nodeBodyCounts)
            .task("integNode", RigidRodLangevinIntegrationSystem::integrate, nb.coord, nb.uVec, nb.yVec, nb.forceSum, nb.torqueSum, nb.randForce, nb.randTorque, nb.bTransGam, nb.bRotGam, nd.nodeBodyParams, nd.nodeBodyCounts)
            .task("deriveNode", DerivedGeometrySystem::derive, nb.coord, nb.uVec, nb.yVec, nb.zVec, nb.end1, nb.end2, nb.segLength, nd.nodeBodyCounts)
            .task("integM", RigidRodLangevinIntegrationSystem::integrate, b.coord, b.uVec, b.yVec, b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, mot.bodyParams, mot.counts)
            .task("deriveM", DerivedGeometrySystem::derive, b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts)
            .task("integM2", RigidRodLangevinIntegrationSystem::integrate, b2.coord, b2.uVec, b2.yVec, b2.forceSum, b2.torqueSum, b2.randForce, b2.randTorque, b2.bTransGam, b2.bRotGam, mot2.bodyParams, mot2.counts)
            .task("deriveM2", DerivedGeometrySystem::derive, b2.coord, b2.uVec, b2.yVec, b2.zVec, b2.end1, b2.end2, b2.segLength, mot2.counts)
            .task("confineBb", ContainmentSystem::confine, bb.coord, bb.uVec, bb.segLength, bb.bTransGam, bb.forceSum, bb.torqueSum, s.boxParams, mini.bbCounts)
            .task("integBb", RigidRodLangevinIntegrationSystem::integrate, bb.coord, bb.uVec, bb.yVec, bb.forceSum, bb.torqueSum, bb.randForce, bb.randTorque, bb.bTransGam, bb.bRotGam, mini.bbBodyParams, mini.bbCounts)
            .task("deriveBb", DerivedGeometrySystem::derive, bb.coord, bb.uVec, bb.yVec, bb.zVec, bb.end1, bb.end2, bb.segLength, mini.bbCounts)
            .task("confineFil", ContainmentSystem::confine, f.coord, f.uVec, f.segLength, f.bTransGam, f.forceSum, f.torqueSum, s.boxParams, f.counts)
            .task("integFil", RigidRodLangevinIntegrationSystem::integrate, f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts)
            .task("deriveFil", DerivedGeometrySystem::derive, f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts)
            .transferToHost(DataTransferMode.UNDER_DEMAND, nb.coord, mot.boundSeg, mot2.boundSeg, nuc.seedNode, f.filState, f.monomerCount, f.segLength, f.coord,
                    grow.grewOffsets, d.returnedOffsets, sv.severReturnedOffsets, s.nucRankOffsets, f.freeOffsets);

        int nMB = b.n, nN = nb.n, nM = mot.nMotors, C = f.n, nD = dim.nDimers;
        int nMB2 = b2.n, nM2 = mot2.nMotors, nD2 = dim2.nDimers, nBb = bb.n, nA = nd.nAttach, cap = s.viewCap, totalCells = s.totalCells;
        int numScan = (totalCells + SpatialGrid.GRID_SCAN_CHUNK - 1) / SpatialGrid.GRID_SCAN_CHUNK;
        sched = new GridScheduler();
        for (String t : new String[]{ "publishHead","reach","release","bind","cycle","bond","applyHead","register" }) addW("fulldemo." + t, pad(nM));
        for (String t : new String[]{ "zeroMot","brownMot","joints","integM","deriveM" }) addW("fulldemo." + t, pad(nMB));
        for (String t : new String[]{ "publishHead2","gridReach","release2","bind2","cycle2","bond2","applyHead2","register2" }) addW("fulldemo." + t, pad(nM2));
        for (String t : new String[]{ "zeroMot2","brownMot2","joints2","integM2","deriveM2" }) addW("fulldemo." + t, pad(nMB2));
        for (String t : new String[]{ "zeroNode","brownNode","ndGather","seedReact","confineNode","integNode","deriveNode" }) addW("fulldemo." + t, pad(nN));
        for (String t : new String[]{ "zeroBb","brownBb","bbGather","confineBb","integBb","deriveBb" }) addW("fulldemo." + t, pad(nBb));
        addW("fulldemo.dimer", pad(nD)); addW("fulldemo.dimer2", pad(nD2)); addW("fulldemo.tether", pad(nA)); addW("fulldemo.tether2", pad(nD2));
        addW("fulldemo.count", pad(1)); addW("fulldemo.emit", pad(nN));
        addW("fulldemo.bodyCell", pad(cap)); addW("fulldemo.chunkZero", pad(s.numBodyChunks * totalCells));
        addW("fulldemo.chunkHist", pad(s.numBodyChunks)); addW("fulldemo.chunkReduce", pad(totalCells));
        addW("fulldemo.chunkScatter", pad(s.numBodyChunks)); addW("fulldemo.gScanLocal", pad(numScan)); addW("fulldemo.gScanAdd", pad(numScan));
        for (String t : new String[]{ "filPublish","chain","seedTether","filGather","filGather2" }) addW("fulldemo." + t, pad(C));
        addW("fulldemo.motPublish", pad(nM2));
        for (String t : new String[]{ "depoly","applyDeath","grow","markSplits","recomputeDrag","gFreeFlags","gFreeScatter","gAllocate",
                                       "nFreeFlags","nFreeScatter","nAllocate","tagSeeds","initNewborn","nucFresh","nucCof",
                                       "age","growthAtp","cofAcc","cofDis","severDeath","splitWire","splitInherit","splitCof",
                                       "zeroFil","brownFil","confineFil","integFil","deriveFil" }) addW("fulldemo." + t, pad(C));
        for (String t : new String[]{ "csrReturn","csrGrew","csrSever","gCsrFree","gCsrRank","nCsrFree","nCsrRank",
                                       "ndHist","ndScan","ndScatter","filHist","filScan","filScatter","filHist2","filScan2","filScatter2",
                                       "bbHist","bbScan","bbScatter","gScanChunks" }) addS("fulldemo." + t);
        return new TornadoExecutionPlan(tg.snapshot());
    }

    /** Host bookkeeping for the device graph: counts/rates + xlink formation on the host each cadence, then the
     *  device graph; the integer pool ledger updated from the device counts. Crosslinker FORCE is omitted from the
     *  GPU probe graph (its device path is validated separately) — the probe targets the heavy mechanics. */
    static void stepHostBookkeeping(Scene g, int t, double dt, TornadoExecutionPlan plan) {
        MotorStore mot = g.mot; MotorStore mot2 = g.mot2; NodeStore nd = g.node; MiniFilamentStore mini = g.mini;
        FilamentStore f = g.fil; NodeNucleationStore nuc = g.nuc; GrowthStore grow = g.grow; DepolyStore d = g.depoly;
        AgingStore ag = g.aging; SeverStore sv = g.sever;
        boolean fires = grow.firesAt(t);
        mot.setCounts(t, SEED, f.n); nd.setNodeBodyCounts(t, SEED_NODE); f.setCounts(t, SEED);
        mot2.setCounts(t, SEED_MINI, f.n); mini.setBackboneCounts(t, SEED_BB);
        grow.setCounts(t, SEED, fires); grow.refreshRate(fires);
        d.setCounts(t, SEED, fires); ag.setFires(fires); sv.setFires(fires);
        nuc.setCounts(t, SEED);
        nuc.nucCounts.set(3, grow.pool.available(Constants.actinSeed) ? 1 : 0);
        TornadoExecutionResult res = plan.withGridScheduler(sched).execute();
        res.transferToHost(grow.grewOffsets, d.returnedOffsets, sv.severReturnedOffsets, g.nucRankOffsets, f.freeOffsets);
        grow.pool.put(d.returnedOffsets.get(f.n) + sv.severReturnedOffsets.get(f.n));
        grow.pool.take(grow.grewOffsets.get(f.n));
        int nucBirths = Math.min(g.nucRankOffsets.get(f.n), f.freeOffsets.get(f.n));
        if (nucBirths > 0) grow.pool.take(nucBirths * Constants.actinSeed);
        if (t == 1999) res.transferToHost(nd.node.coord, mot.boundSeg, mot2.boundSeg, nuc.seedNode, f.filState, f.monomerCount, f.segLength, f.coord);
    }

    // ====================================================================== SPLIT chained-graph device-resident path
    // The monolithic buildPlan above merges the whole ~106-task per-step sequence into ONE TaskGraph, which exceeds
    // TornadoVM's single-TaskGraph node capacity ("Graph resize not implemented"). buildPlanSplit cuts the SAME task
    // sequence (identical methods, identical order) into N=5 chained TaskGraphs that share the SoA buffers on the
    // device via persistOnDevice (producer) + consumeFromDevice (consumer) under ONE TornadoExecutionPlan. No host
    // round-trip between sub-graphs; the host pulls only the integer pool-ledger offsets per step (the same
    // UNDER_DEMAND pulls the monolith used). Cut points = the validated constituent boundaries, in faithful order:
    //   G0 fdTurn   — turnover + nucleation (Ring3x3 task order)            (≈32 tasks)
    //   G1 fdBind   — node-shell + free-minifil binding (incl. the grid)    (≈20 tasks)
    //   G2 fdStruct — zero/Brownian + node-shell + free-minifil structure   (≈28 tasks)
    //   G3 fdFil    — filament chain + seed-tether + both seg-gathers       (≈13 tasks)
    //   G4 fdInteg  — containment + integrate + derive                      (≈13 tasks)
    // (crosslinker formation/force stays CPU-side here exactly as the monolith probe did — its filID is host-computed;
    //  the xlink device path is validated in DenseContractile/XlinkFormation. Flagged in the findings.)
    // fdTurnFire result (turnover offsets, fire steps only — may be stale on a non-fire check), fdNuc result (nuc
    // offsets + filament render state, always-run), fdBind result (binding state), last graph (derived geometry).
    static TornadoExecutionResult splitResTurn, splitResNuc, splitResBind, splitResFil, splitResInteg, splitResL;
    // graph indices (computed in buildPlanSplit; xl-present ⇒ 7 graphs with fdXForm at G2, else 6). GI_XFORM=-1 when off.
    static int GI_TURN, GI_NUC, GI_XFORM = -1, GI_BIND, GI_STRUCT, GI_FIL, GI_INTEG;
    static Object[] fullPersistSet;   // the full persisted SoA set (for the -planreset rebuild-mode full host round-trip)

    static Object[] cat(Object[] a, Object[] b) {
        Object[] r = new Object[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length); System.arraycopy(b, 0, r, a.length, b.length); return r;
    }

    static TornadoExecutionPlan buildPlanSplit(Scene s) {
        MotorStore mot = s.mot; DimerStore dim = s.dim; NodeStore nd = s.node;
        MotorStore mot2 = s.mot2; DimerStore dim2 = s.dim2; MiniFilamentStore mini = s.mini;
        FilamentStore f = s.fil; NodeNucleationStore nuc = s.nuc; GrowthStore grow = s.grow; DepolyStore d = s.depoly;
        AgingStore ag = s.aging; SeverStore sv = s.sever;
        RigidRodBody b = mot.body, nb = nd.node, b2 = mot2.body, bb = mini.backbone; SpatialBodyView v = s.view;

        // Every SoA buffer the per-step sequence touches (= the monolith's two transferToDevice lists, verbatim).
        Object[] firstExec = {
                b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, b.bTransGam, b.bRotGam,
                b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.brownTransScale, b.brownRotScale,
                mot.head, mot.uVec, mot.rodUVec, mot.boundSeg, mot.bindArc, mot.nucleotideState,
                mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.stats, mot.capStats, mot.cooldown,
                mot.bodyParams, mot.jointParams, mot.nucParams, mot.kinParams,
                dim.motorA, dim.motorB, dim.parallel, dim.dimerParams,
                nb.coord, nb.uVec, nb.yVec, nb.zVec, nb.end1, nb.end2, nb.segLength, nb.bTransGam, nb.bRotGam,
                nb.forceSum, nb.torqueSum, nb.randForce, nb.randTorque, nb.brownTransScale, nb.brownRotScale, nd.nodeBodyParams,
                nd.nodeInvTransY, nd.attachNode, nd.attachKey, nd.radial, nd.attachCoeffK, nd.nodeData, nd.nodeParams,
                nd.nodeAttachCount, nd.nodeAttachOffsets, nd.nodeAttachList, nd.nodeCounts4,
                s.bondData, s.xbParams, s.segMotorCount, s.segMotorOffsets, s.segMotorMyo, s.reachSeg, s.reachCount, s.boxParams,
                f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.monomerCount, f.bTransGam, f.bRotGam, f.bTransDiff, f.bRotDiff,
                f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.params, f.chainParams,
                f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide, f.filState,
                f.freeCount, f.freeOffsets, f.freeList, f.freeScanCounts, f.rankOffsets, f.rankScanCounts, f.allocCounts, f.birthParams,
                f.acceptFlag, f.reqCoord, f.reqUVec, f.reqYVec,
                grow.grewFlag, grow.grewOffsets, grow.grewScanCounts, grow.splitParams, grow.dragParams,
                d.depolyParams, d.returnedMon, d.returnedOffsets, d.returnScanCounts, d.deathFlag,
                ag.nucFrac, ag.agingParams, ag.depolyRateParams,
                sv.cofFrac, sv.cofilinParams, sv.severDeathFlag, sv.severReturnedMon, sv.severReturnedOffsets, sv.severScanCounts,
                nuc.seedNode, nuc.nodeBoundFil, nuc.nucParams, nuc.tetherParams, nuc.seedParams,
                s.nucAccept, s.nucReqCoord, s.nucReqUVec, s.nucReqYVec, s.nucRankOffsets, s.nucRankScanCounts,
                b2.coord, b2.uVec, b2.yVec, b2.zVec, b2.end1, b2.end2, b2.segLength, b2.bTransGam, b2.bRotGam,
                b2.forceSum, b2.torqueSum, b2.randForce, b2.randTorque, b2.brownTransScale, b2.brownRotScale,
                mot2.head, mot2.uVec, mot2.rodUVec, mot2.boundSeg, mot2.bindArc, mot2.nucleotideState, mot2.reach,
                mot2.forceDotFil, mot2.forceMag, mot2.forceDotHist, mot2.forceDotPlace, mot2.stats, mot2.capStats, mot2.cooldown,
                mot2.bodyParams, mot2.jointParams, mot2.nucParams, mot2.kinParams, mot2.publishParams,
                dim2.motorA, dim2.motorB, dim2.parallel, dim2.dimerParams,
                bb.coord, bb.uVec, bb.yVec, bb.zVec, bb.end1, bb.end2, bb.segLength, bb.bTransGam, bb.bRotGam,
                bb.forceSum, bb.torqueSum, bb.randForce, bb.randTorque, bb.brownTransScale, bb.brownRotScale, mini.bbBodyParams,
                mini.bbInvDragY, mini.headBackboneSlot, mini.motorA, mini.attachAxial, mini.miniData, mini.miniParams,
                mini.bbDimerCount, mini.bbDimerOffsets, mini.bbDimerList, mini.miniCounts,
                s.bondData2, s.segMotorCount2, s.segMotorOffsets2, s.segMotorMyo2, s.reachSeg2, s.reachCount2,
                v.center, v.boundingRadius, v.ownerStore, v.ownerSlot, s.gridParams, s.gridDims, s.gridCounts, s.viewParams,
                s.bodyCell, s.cellCount, s.chunkSum, s.gridCellOffsets, s.gridCellContents, s.chunkParams, s.chunkCellCount };
        // Tiny per-step counts/rates (step number, RNG seed, fires-flag, availability). Re-uploaded EVERY_EXECUTION
        // in EVERY graph (negligible bytes) — NOT persisted/consumed (EVERY_EXECUTION buffers are re-uploaded each
        // execution, not held resident; persisting them corrupts their device state).
        Object[] everyExec = { mot.counts, nd.nodeBodyCounts, f.counts, grow.growCounts, grow.growParams,
                nuc.nucCounts, d.depolyCounts, ag.agingCounts, sv.severCounts, mot2.counts, mini.bbCounts };
        // Crosslinker per-step counts re-uploaded EVERY_EXECUTION (used by fdXForm formation + fdFil force/unbind):
        // xl.counts (unbind/gather bounds + RNG), xl.formCounts (formation RNG + candidate counter), fg.gridCounts
        // (formation-grid body count). NOT persisted (counts buffers are re-uploaded, not held). xl off ⇒ unchanged.
        boolean xlDev = s.xl != null;
        // Crosslinker per-step counts (used every step by fdFil force/unbind + the formation cadence by fdXForm).
        if (xlDev) everyExec = cat(everyExec, new Object[]{ s.xl.counts, s.xl.formCounts, s.fg.gridCounts });

        // --- per-graph SoA-buffer USAGE sets (the distinct persistent firstExec buffers each block's tasks reference).
        // A buffer is uploaded (FIRST_EXECUTION) by the FIRST graph that USES it (so it is actually allocated there;
        // TornadoVM does not allocate a transferred buffer no task uses → persisting that leaves a null device buffer,
        // the executeAlloc NPE). Then every later graph consumes the running uploaded set from its predecessor and
        // persists it forward, keeping the whole state device-resident across sub-graphs AND across steps. No host
        // round-trip; only the pool-ledger offsets are pulled UNDER_DEMAND each step.
        // CADENCE-GATE SPLIT (Stage 0 case 1): fdTurn split into fdTurnFire (turnover, fire-gated, the UPLOADER) +
        // fdNuc (nucleation, ALWAYS-run — nucleation draws every step at kNodeNuc·dt, NOT on the biochem cadence).
        // The shared SoA buffers are first-used (uploaded FIRST_EXECUTION) by fdTurnFire at step 0 (a fire step) and
        // consumed by fdNuc; fdNuc lists ONLY the nucleation-unique buffers as its first-use set.
        Object[] u0 = {   // G0 fdTurnFire — turnover (age/depoly/death/grow/sever/split + allocator + recomputeDrag)
                f.filState, f.monomerCount, f.coord, f.uVec, f.yVec, f.segLength, f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide,
                f.brownTransScale, f.brownRotScale, f.acceptFlag, f.reqCoord, f.reqUVec, f.reqYVec, f.freeCount, f.freeScanCounts, f.freeOffsets,
                f.freeList, f.rankScanCounts, f.rankOffsets, f.allocCounts, f.birthParams, f.bTransGam, f.bRotGam, f.bTransDiff, f.bRotDiff,
                ag.nucFrac, ag.agingParams, ag.depolyRateParams,
                d.returnedMon, d.deathFlag, d.depolyParams, d.returnScanCounts, d.returnedOffsets,
                grow.grewFlag, grow.grewScanCounts, grow.grewOffsets, grow.splitParams, grow.dragParams,
                sv.cofFrac, sv.cofilinParams, sv.severDeathFlag, sv.severReturnedMon, sv.severScanCounts, sv.severReturnedOffsets,
                nuc.seedNode };
        Object[] uNuc = {   // G1 fdNuc — node nucleation (count/emit + the B1 allocator path); nuc-unique buffers only
                nuc.nodeBoundFil, nuc.nucParams, nuc.seedParams, nb.coord,
                s.nucAccept, s.nucReqCoord, s.nucReqUVec, s.nucReqYVec, s.nucRankScanCounts, s.nucRankOffsets };
        Object[] u1 = {   // G2 binding (node-shell brute + free-minifil grid)
                b.coord, b.uVec, b.segLength, mot.head, mot.uVec, mot.rodUVec, mot.boundSeg, mot.bindArc, mot.nucleotideState,
                mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.cooldown, mot.stats, mot.capStats, mot.kinParams, mot.nucParams,
                f.end1, f.end2, f.coord, f.segLength, nuc.seedNode,
                s.reachSeg, s.reachCount, s.viewParams, s.gridParams, s.gridDims, s.gridCounts, s.bodyCell, s.chunkParams, s.chunkCellCount,
                s.cellCount, s.gridCellOffsets, s.chunkSum, s.gridCellContents, s.reachSeg2, s.reachCount2,
                v.center, v.boundingRadius, v.ownerStore, v.ownerSlot,
                b2.coord, b2.uVec, b2.segLength, mot2.head, mot2.uVec, mot2.rodUVec, mot2.reach, mot2.publishParams, mot2.boundSeg, mot2.bindArc,
                mot2.nucleotideState, mot2.forceDotFil, mot2.forceMag, mot2.forceDotHist, mot2.cooldown, mot2.stats, mot2.capStats, mot2.kinParams, mot2.nucParams };
        Object[] u2 = {   // G2 zero/Brownian + node-shell + free-minifil structure forces
                b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, b.brownTransScale, b.brownRotScale, b.coord, b.uVec, b.segLength, b.yVec,
                mot.bodyParams, mot.nucleotideState, mot.jointParams, mot.boundSeg, mot.bindArc,
                nb.forceSum, nb.torqueSum, nb.randForce, nb.randTorque, nb.bTransGam, nb.bRotGam, nb.brownTransScale, nb.brownRotScale, nb.coord, nb.uVec, nb.yVec,
                nd.nodeBodyParams, nd.nodeInvTransY, nd.attachKey, nd.radial, nd.attachCoeffK, nd.nodeData, nd.nodeParams,
                nd.attachNode, nd.nodeCounts4, nd.nodeAttachCount, nd.nodeAttachOffsets, nd.nodeAttachList,
                b2.forceSum, b2.torqueSum, b2.randForce, b2.randTorque, b2.bTransGam, b2.bRotGam, b2.brownTransScale, b2.brownRotScale, b2.coord, b2.uVec, b2.segLength, b2.yVec,
                mot2.bodyParams, mot2.nucleotideState, mot2.jointParams, mot2.boundSeg, mot2.bindArc,
                bb.forceSum, bb.torqueSum, bb.randForce, bb.randTorque, bb.bTransGam, bb.bRotGam, bb.brownTransScale, bb.brownRotScale, bb.coord, bb.uVec,
                mini.bbBodyParams, mini.bbInvDragY, mini.headBackboneSlot, mini.motorA, mini.attachAxial, mini.miniData, mini.miniParams, mini.miniCounts,
                mini.bbDimerCount, mini.bbDimerOffsets, mini.bbDimerList,
                dim.motorA, dim.motorB, dim.parallel, dim.dimerParams, dim2.motorA, dim2.motorB, dim2.parallel, dim2.dimerParams,
                f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.brownTransScale, f.brownRotScale, f.params, f.coord, f.uVec, f.yVec, f.segLength,
                s.bondData, s.xbParams, s.bondData2 };
        Object[] u3 = {   // G3 filament chain + seed-tether + both seg-gathers
                f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide, f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum,
                nb.coord, nb.forceSum, nd.nodeInvTransY, nuc.seedNode,
                mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace,
                mot2.boundSeg, mot2.forceDotFil, mot2.forceMag, mot2.forceDotHist, mot2.forceDotPlace,
                s.segMotorCount, s.segMotorOffsets, s.segMotorMyo, s.bondData, s.segMotorCount2, s.segMotorOffsets2, s.segMotorMyo2, s.bondData2 };
        // The crosslinker 2-pass seg-gather CSR-inverse arrays are first-USED by blkFil's appended xlGatherA/B tasks,
        // so they are uploaded FIRST_EXECUTION here (the first graph whose tasks reference them — the executeAlloc-NPE
        // rule of §1: a persisted buffer no task uses is elided → null device buffer. NOT in fdXForm's set, which never
        // touches them). All other crosslinker force buffers are uploaded by fdXForm and consumed here.
        // fdFil (always-run) is the UPLOADER of the shared crosslinker link state (it uses it in the every-step force +
        // unbind); fdXForm (the cadence-gated LAST graph) consumes it. Uploading the link state in an always-run graph
        // is what makes the gated fdXForm skip-safe (it would otherwise be a skipped sole-uploader of buffers a later
        // always-run graph needs). + the 2-pass seg-gather CSR arrays + the force-only params/data.
        if (xlDev) u3 = cat(u3, new Object[]{ s.segCountA, s.segOffsetsA, s.segIdxA, s.segCountB, s.segOffsetsB, s.segIdxB,
                s.xl.xlinkData, s.xl.xlParams, s.xl.offParams, s.xl.torsionParams,
                s.xl.linkState, s.xl.linkFilA, s.xl.linkFilB, s.xl.loc1, s.xl.loc2, s.xl.activeLinkCount,
                s.xl.strainHist, s.xl.strainPlace, s.xl.linkOrientSame, s.xl.torqueMagHist, s.xl.torqueMagPlace });
        Object[] u4 = {   // G4 containment + integrate + derive
                nb.coord, nb.uVec, nb.segLength, nb.bTransGam, nb.forceSum, nb.torqueSum, nb.yVec, nb.randForce, nb.randTorque, nb.bRotGam, nb.zVec, nb.end1, nb.end2,
                nd.nodeBodyParams,
                b.coord, b.uVec, b.yVec, b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, b.zVec, b.end1, b.end2, b.segLength, mot.bodyParams,
                b2.coord, b2.uVec, b2.yVec, b2.forceSum, b2.torqueSum, b2.randForce, b2.randTorque, b2.bTransGam, b2.bRotGam, b2.zVec, b2.end1, b2.end2, b2.segLength, mot2.bodyParams,
                bb.coord, bb.uVec, bb.segLength, bb.bTransGam, bb.forceSum, bb.torqueSum, bb.yVec, bb.randForce, bb.randTorque, bb.bRotGam, bb.zVec, bb.end1, bb.end2, mini.bbBodyParams,
                f.coord, f.uVec, f.segLength, f.bTransGam, f.forceSum, f.torqueSum, f.yVec, f.randForce, f.randTorque, f.bRotGam, f.params, f.zVec, f.end1, f.end2,
                s.boxParams };
        // fdXForm (CADENCE-GATED, the LAST graph) — device filID (pointer-doubling) + the WHOLE crosslinker formation
        // pipeline. As the LAST graph it has NO successor consuming its sole-uploaded (formation-scratch) buffers, so
        // skipping it 99/100 steps is residency-safe; the SHARED link state it reads/mutates is uploaded by the
        // always-run fdFil (u3) and consumed here. First-use set = filID buffers + the FormationGrid's OWN grid (distinct
        // cell size, can't reuse the binding grid) + the formation-ONLY request/allocator scratch + formParams. NOT
        // f.end1/f.end2 (uploaded by fdBind) and NOT the shared link buffers (uploaded by fdFil). gridCounts/formCounts
        // are EVERY_EXECUTION (above). xl.filLinkCt is build-only (no device task) ⇒ omitted (else executeAlloc NPE).
        Object[] uX = !xlDev ? null : new Object[]{
                s.filID, s.filIDScratch,
                s.fg.view.center, s.fg.view.boundingRadius, s.fg.view.ownerStore, s.fg.view.ownerSlot, s.fg.viewParams,
                s.fg.gridParams, s.fg.gridDims, s.fg.bodyCell, s.fg.cellCount, s.fg.chunkSum, s.fg.chunkParams, s.fg.chunkCellCount,
                s.fg.gridCellOffsets, s.fg.gridCellContents, s.fg.candCountSeg, s.fg.candBaseSeg,
                s.xl.reqFilA, s.xl.reqFilB, s.xl.reqLoc1, s.xl.reqLoc2, s.xl.reqOrient, s.xl.gatePass, s.xl.minCand, s.xl.acceptFlag,
                s.xl.freeCount, s.xl.freeOffsets, s.xl.freeList, s.xl.freeScanCounts, s.xl.rankOffsets, s.xl.rankScanCounts, s.xl.allocCounts,
                s.xl.formParams };
        Object[][] U; String[] gname;
        if (xlDev) {
            U = new Object[][]{ u0, uNuc, u1, u2, u3, u4, uX };
            gname = new String[]{ "fdTurnFire", "fdNuc", "fdBind", "fdStruct", "fdFil", "fdInteg", "fdXForm" };
        } else {
            U = new Object[][]{ u0, uNuc, u1, u2, u3, u4 };
            gname = new String[]{ "fdTurnFire", "fdNuc", "fdBind", "fdStruct", "fdFil", "fdInteg" };
        }
        N_SPLIT = gname.length;
        GI_TURN = GI_NUC = GI_BIND = GI_STRUCT = GI_FIL = GI_INTEG = -1; GI_XFORM = -1;
        for (int gi = 0; gi < N_SPLIT; gi++) switch (gname[gi]) {
            case "fdTurnFire" -> GI_TURN = gi; case "fdNuc" -> GI_NUC = gi; case "fdXForm" -> GI_XFORM = gi;
            case "fdBind" -> GI_BIND = gi; case "fdStruct" -> GI_STRUCT = gi; case "fdFil" -> GI_FIL = gi; case "fdInteg" -> GI_INTEG = gi;
        }
        GNAME = gname;

        // host-pull buffers per graph (UNDER_DEMAND). G0 fdTurnFire: the turnover pool-ledger offsets (pulled inline
        // ONLY on fire steps). G1 fdNuc (always-run): the nuc pool-ledger offsets (every step) + the filament render
        // state (filState/monomerCount/segLength/nucFrac/cofFrac/seedNode — fully current after fdNuc births). G2
        // fdBind: binding state. G5 fdInteg: the derived geometry the renderer reads. NB: the render state is pulled
        // from fdNuc (always-run), NOT fdTurnFire (skipped on non-fire steps).
        Object[] host0 = { grow.grewOffsets, d.returnedOffsets, sv.severReturnedOffsets };
        Object[] hostNuc = { s.nucRankOffsets, f.freeOffsets, f.monomerCount, f.filState, f.segLength, nuc.seedNode, ag.nucFrac, sv.cofFrac };
        Object[] host1 = { mot.boundSeg, mot2.boundSeg, mot.nucleotideState, mot2.nucleotideState };
        Object[] host4 = { nb.coord, f.coord, f.end1, f.end2, b.end1, b.end2, b2.end1, b2.end2, bb.coord, bb.end1, bb.end2 };
        // crosslink render/count state (active links + endpoints) — pulled from fdFil (always-run; unbind/force mutate
        // linkState there) so a non-formation check step never reads the skipped fdXForm graph.
        Object[] hostXl = xlDev ? new Object[]{ s.xl.linkState, s.xl.linkFilA, s.xl.linkFilB, s.xl.loc1, s.xl.loc2, s.xl.linkOrientSame } : null;

        java.util.Set<Object> uploaded = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        TaskGraph[] tg = new TaskGraph[N_SPLIT];
        for (int gi = 0; gi < N_SPLIT; gi++) {
            // newBufs = first-use here; consumeSet = everything uploaded by earlier graphs (all allocated ⇒ no NPE).
            // Consume from the immediate predecessor (the proven §8 pattern): every graph EXCEPT fdTurnFire is always-run
            // and re-persists the full running set under its own tag each step, so the predecessor always has them; the
            // sole gated graph (fdTurnFire) is the GENUINE uploader of what its consumer (fdNuc) needs, which §8.1 proved
            // is skip-safe. fdXForm is kept ALWAYS-RUN (formation is gated internally by P_form, below) precisely so it
            // never becomes a skipped non-uploader in this chain — a skipped MIDDLE graph breaks the consume forward-link.
            java.util.List<Object> newBufs = new java.util.ArrayList<>();
            for (Object o : U[gi]) if (!uploaded.contains(o)) { newBufs.add(o); uploaded.add(o); }
            Object[] consumeSet = uploaded.stream().filter(o -> !newBufs.contains(o)).toArray();
            TaskGraph g = new TaskGraph(gname[gi]);
            if (gi > 0 && consumeSet.length > 0) g = g.consumeFromDevice(gname[gi - 1], consumeSet);
            if (!newBufs.isEmpty()) g = g.transferToDevice(DataTransferMode.FIRST_EXECUTION, newBufs.toArray());
            g = g.transferToDevice(DataTransferMode.EVERY_EXECUTION, everyExec);
            g = switch (gname[gi]) {
                case "fdTurnFire" -> blkTurnFire(g, s); case "fdNuc" -> blkNuc(g, s); case "fdXForm" -> blkXForm(g, s);
                case "fdBind" -> blkBind(g, s); case "fdStruct" -> blkStruct(g, s); case "fdFil" -> blkFil(g, s); default -> blkInteg(g, s);
            };
            if (gi == GI_TURN) g = g.transferToHost(DataTransferMode.UNDER_DEMAND, host0);
            else if (gi == GI_NUC) g = g.transferToHost(DataTransferMode.UNDER_DEMAND, hostNuc);
            else if (gi == GI_BIND) g = g.transferToHost(DataTransferMode.UNDER_DEMAND, host1);
            if (xlDev && gi == GI_FIL) g = g.transferToHost(DataTransferMode.UNDER_DEMAND, hostXl);
            if (gi == GI_INTEG) g = g.transferToHost(DataTransferMode.UNDER_DEMAND, host4);   // derived geometry for the renderer (fdInteg produces it)
            if (gi == N_SPLIT - 1 && planResetEvery > 0 && planResetMode.equals("rebuild"))
                // -planreset rebuild mode: register the FULL persisted set UNDER_DEMAND on the LAST graph so a reset
                // boundary can pull the entire device state to host before tearing the plan down (probe-only).
                g = g.transferToHost(DataTransferMode.UNDER_DEMAND, uploaded.toArray());
            // persist the full running state (keeps it device-resident for later sub-graphs AND the next step).
            g = g.persistOnDevice(uploaded.toArray());
            tg[gi] = g;
        }
        fullPersistSet = uploaded.toArray();

        buildSplitScheduler(s);
        uk.ac.manchester.tornado.api.ImmutableTaskGraph[] snaps = new uk.ac.manchester.tornado.api.ImmutableTaskGraph[N_SPLIT];
        for (int gi = 0; gi < N_SPLIT; gi++) snaps[gi] = tg[gi].snapshot();
        return new TornadoExecutionPlan(snaps);
    }

    // ---- the six task blocks (verbatim methods + order from the monolith buildPlan; fdTurn split fdTurnFire+fdNuc) ----
    static TaskGraph blkTurnFire(TaskGraph tg, Scene s) {
        FilamentStore f = s.fil; NodeNucleationStore nuc = s.nuc; GrowthStore grow = s.grow; DepolyStore d = s.depoly;
        AgingStore ag = s.aging; SeverStore sv = s.sever; NodeStore nd = s.node; RigidRodBody nb = nd.node;
        return tg
            .task("age", AgingSystem::age, f.filState, ag.nucFrac, ag.agingParams, ag.agingCounts)
            .task("depoly", DepolySystem::depolyProxy, f.filState, f.monomerCount, f.coord, f.uVec, f.end1NbrSlot, ag.nucFrac, d.returnedMon, d.deathFlag, d.depolyParams, ag.depolyRateParams, d.depolyCounts)
            .task("csrReturn", CrossBridgeSystem::csrScan, d.returnScanCounts, d.returnedMon, d.returnedOffsets)
            .task("applyDeath", DepolySystem::applyDeath, f.filState, f.monomerCount, nuc.seedNode, f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide, f.brownTransScale, f.brownRotScale, d.deathFlag, d.depolyCounts)
            .task("grow", GrowthSystem::grow, nuc.seedNode, f.monomerCount, f.coord, f.uVec, grow.grewFlag, grow.growParams, grow.growCounts)
            .task("csrGrew", CrossBridgeSystem::csrScan, grow.grewScanCounts, grow.grewFlag, grow.grewOffsets)
            .task("growthAtp", AgingSystem::growthAtp, grow.grewFlag, f.monomerCount, ag.nucFrac)
            .task("cofAcc", SeveringSystem::cofilinAccumulate, f.filState, ag.nucFrac, sv.cofFrac, sv.cofilinParams, sv.severCounts)
            .task("cofDis", SeveringSystem::cofilinDissolve, f.filState, f.monomerCount, sv.cofFrac, sv.severDeathFlag, sv.severReturnedMon, sv.cofilinParams, sv.severCounts)
            .task("csrSever", CrossBridgeSystem::csrScan, sv.severScanCounts, sv.severReturnedMon, sv.severReturnedOffsets)
            .task("severDeath", DepolySystem::applyDeath, f.filState, f.monomerCount, nuc.seedNode, f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide, f.brownTransScale, f.brownRotScale, sv.severDeathFlag, d.depolyCounts)
            .task("markSplits", GrowthSystem::markSplits, nuc.seedNode, f.monomerCount, f.coord, f.uVec, f.yVec, f.acceptFlag, f.reqCoord, f.reqUVec, f.reqYVec, grow.splitParams, grow.growCounts)
            .task("gFreeFlags", FilamentBirthSystem::freeFlags, f.filState, f.freeCount, f.allocCounts)
            .task("gCsrFree", CrossBridgeSystem::csrScan, f.freeScanCounts, f.freeCount, f.freeOffsets)
            .task("gFreeScatter", FilamentBirthSystem::freeScatter, f.filState, f.freeOffsets, f.freeList, f.allocCounts)
            .task("gCsrRank", CrossBridgeSystem::csrScan, f.rankScanCounts, f.acceptFlag, f.rankOffsets)
            .task("gAllocate", FilamentBirthSystem::allocate, f.reqCoord, f.reqUVec, f.reqYVec, f.rankOffsets, f.freeList, f.freeOffsets, f.coord, f.uVec, f.yVec, f.brownTransScale, f.brownRotScale, f.filState, f.birthParams, f.allocCounts)
            .task("splitWire", GrowthSystem::splitWire, f.rankOffsets, f.freeList, f.freeOffsets, f.monomerCount, f.coord, f.uVec, f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide, nuc.seedNode, grow.splitParams, f.allocCounts)
            .task("splitInherit", AgingSystem::splitInheritNuc, f.rankOffsets, f.freeList, f.freeOffsets, ag.nucFrac, f.allocCounts)
            .task("splitCof", SeveringSystem::nucleateFreshCofilin, f.rankOffsets, f.freeList, f.freeOffsets, sv.cofFrac, f.allocCounts)
            .task("recomputeDrag", GrowthSystem::recomputeDrag, f.monomerCount, f.segLength, f.end1NbrSlot, f.end2NbrSlot, f.bTransGam, f.bRotGam, f.bTransDiff, f.bRotDiff, grow.dragParams, grow.growCounts);
    }

    /** fdNuc — node nucleation (the ALWAYS-run G1: nucleation draws every step at kNodeNuc·dt, NOT on the biochem
     *  cadence). Runs AFTER fdTurnFire on fire steps (same turnover→nucleation order as cpuStep) and ALONE on non-fire
     *  steps. The B1 free-list is rebuilt fresh here (nFreeFlags/nCsrFree/nFreeScatter from filState) ⇒ nucleation is
     *  self-contained regardless of whether the (skipped) turnover allocator ran this step. */
    static TaskGraph blkNuc(TaskGraph tg, Scene s) {
        FilamentStore f = s.fil; NodeNucleationStore nuc = s.nuc; AgingStore ag = s.aging; SeverStore sv = s.sever;
        NodeStore nd = s.node; RigidRodBody nb = nd.node;
        return tg
            .task("count", NodeNucleationSystem::countBoundFil, nuc.seedNode, nuc.nodeBoundFil, nuc.nucCounts)
            .task("emit", NodeNucleationSystem::emit, nb.coord, nuc.nodeBoundFil, s.nucAccept, s.nucReqCoord, s.nucReqUVec, s.nucReqYVec, nuc.nucParams, nuc.nucCounts)
            .task("nFreeFlags", FilamentBirthSystem::freeFlags, f.filState, f.freeCount, f.allocCounts)
            .task("nCsrFree", CrossBridgeSystem::csrScan, f.freeScanCounts, f.freeCount, f.freeOffsets)
            .task("nFreeScatter", FilamentBirthSystem::freeScatter, f.filState, f.freeOffsets, f.freeList, f.allocCounts)
            .task("nCsrRank", CrossBridgeSystem::csrScan, s.nucRankScanCounts, s.nucAccept, s.nucRankOffsets)
            .task("nAllocate", FilamentBirthSystem::allocate, s.nucReqCoord, s.nucReqUVec, s.nucReqYVec, s.nucRankOffsets, f.freeList, f.freeOffsets, f.coord, f.uVec, f.yVec, f.brownTransScale, f.brownRotScale, f.filState, f.birthParams, f.allocCounts)
            .task("tagSeeds", NodeNucleationSystem::tagSeeds, s.nucRankOffsets, f.freeList, f.freeOffsets, nuc.seedNode, f.allocCounts)
            .task("initNewborn", NodeNucleationSystem::initNewborn, s.nucRankOffsets, f.freeList, f.freeOffsets, f.monomerCount, f.segLength, nuc.seedParams, f.allocCounts)
            .task("nucFresh", AgingSystem::nucleateFreshAtp, s.nucRankOffsets, f.freeList, f.freeOffsets, ag.nucFrac, f.allocCounts)
            .task("nucCof", SeveringSystem::nucleateFreshCofilin, s.nucRankOffsets, f.freeList, f.freeOffsets, sv.cofFrac, f.allocCounts);
    }

    /** fdXForm — device filID (pointer-doubling to the chain terminal) + the WHOLE crosslinker FORMATION pipeline
     *  (FormationGrid build + fused per-segment query + gates + one-per-seg admission + the scan-rank allocator +
     *  placeOrient). CADENCE-GATED in stepSplit (executes only on the formation cadence, like fdTurnFire — proven safe
     *  across a skipped producer, §8.1). filID must be current AFTER fdTurnFire+fdNuc (chain mutations) and BEFORE
     *  formation reads it (§9.0). countActiveLinks here is the start-of-formation count (the formAdmit saturation
     *  reference, exactly as formationCpu line 851). Mirrors XlinkFormation GATE-B (bit-identical CPU↔GPU) verbatim. */
    static TaskGraph blkXForm(TaskGraph tg, Scene s) {
        FilamentStore f = s.fil; CrosslinkerStore xl = s.xl; FormationGrid fg = s.fg;
        // filID: pointer-doubling — init (each seg → its end2 successor / self at terminal; FREE → -seg-2) then an EVEN
        // number of jumps ping-ponging s.filID↔s.filIDScratch, landing the result back in s.filID (filIDRounds even).
        tg = tg.task("filidInit", FilIDSystem::init, f.filState, f.end2NbrSlot, s.filID, f.counts);
        IntArray a = s.filID, b = s.filIDScratch;
        for (int k = 0; k < s.filIDRounds; k++) { tg = tg.task("filidJump" + k, FilIDSystem::jump, a, b, f.filState, f.counts); IntArray tmp = a; a = b; b = tmp; }
        return tg
            .task("xfCount", CrosslinkerSystem::countActiveLinks, xl.linkState, xl.linkFilA, xl.linkFilB, xl.activeLinkCount, xl.formCounts)
            .task("xfPublish", FilamentStore::publishToBodyView, f.coord, f.segLength, fg.view.center, fg.view.boundingRadius, fg.view.ownerStore, fg.view.ownerSlot, fg.viewParams, fg.gridCounts)
            .task("xfBodyCell", SpatialGrid::bodyCell, fg.view.center, fg.gridParams, fg.gridDims, fg.gridCounts, fg.bodyCell)
            .task("xfChunkZero", SpatialGrid::gridChunkZero, fg.chunkParams, fg.gridDims, fg.chunkCellCount)
            .task("xfChunkHist", SpatialGrid::gridChunkHistogram, fg.bodyCell, fg.gridCounts, fg.chunkParams, fg.gridDims, fg.chunkCellCount)
            .task("xfChunkReduce", SpatialGrid::gridChunkReduce, fg.gridDims, fg.chunkParams, fg.chunkCellCount, fg.cellCount)
            .task("xfScanLocal", SpatialGrid::gridScanLocal, fg.gridDims, fg.cellCount, fg.gridCellOffsets, fg.chunkSum)
            .task("xfScanChunks", SpatialGrid::gridScanChunks, fg.gridDims, fg.chunkSum)
            .task("xfScanAdd", SpatialGrid::gridScanAdd, fg.gridDims, fg.gridCellOffsets, fg.gridCellContents, fg.cellCount, fg.chunkSum)
            .task("xfChunkScatter", SpatialGrid::gridChunkScatter, fg.bodyCell, fg.gridCounts, fg.chunkParams, fg.gridDims, fg.gridCellOffsets, fg.gridCellContents, fg.chunkCellCount)
            .task("xfFormCount", CrosslinkerSystem::gridFormCount, f.coord, f.segLength, s.filID, fg.gridParams, fg.gridDims, fg.gridCellOffsets, fg.gridCellContents, fg.candCountSeg, xl.formParams, xl.formCounts)
            .task("xfFormScan", CrosslinkerSystem::gridFormScan, fg.candCountSeg, fg.candBaseSeg, xl.reqFilA, xl.reqFilB, xl.formCounts)
            .task("xfFormEmit", CrosslinkerSystem::gridFormEmit, f.coord, f.segLength, s.filID, fg.gridParams, fg.gridDims, fg.gridCellOffsets, fg.gridCellContents, fg.candBaseSeg, fg.candCountSeg, xl.reqFilA, xl.reqFilB, xl.formParams, xl.formCounts)
            .task("xfGates", CrosslinkerSystem::formGates, f.uVec, f.end1, f.end2, f.segLength, xl.reqFilA, xl.reqFilB, xl.reqLoc1, xl.reqLoc2, xl.reqOrient, xl.gatePass, xl.formParams, xl.formCounts)
            .task("xfAdmitReduce", CrosslinkerSystem::formAdmitReduce, xl.reqFilA, xl.reqFilB, xl.gatePass, xl.minCand, xl.formCounts)
            .task("xfAdmit", CrosslinkerSystem::formAdmit, xl.reqFilA, xl.reqFilB, xl.reqLoc1, xl.reqLoc2, xl.gatePass, xl.minCand, xl.activeLinkCount, xl.linkState, xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2, xl.acceptFlag, xl.formParams, xl.formCounts)
            .task("xfFreeFlags", CrosslinkerSystem::freeFlags, xl.linkState, xl.freeCount, xl.allocCounts)
            .task("xfScanFree", CrossBridgeSystem::csrScan, xl.freeScanCounts, xl.freeCount, xl.freeOffsets)
            .task("xfFreeScatter", CrosslinkerSystem::freeScatter, xl.linkState, xl.freeOffsets, xl.freeList, xl.allocCounts)
            .task("xfScanRank", CrossBridgeSystem::csrScan, xl.rankScanCounts, xl.acceptFlag, xl.rankOffsets)
            .task("xfAllocate", CrosslinkerSystem::allocate, xl.reqFilA, xl.reqFilB, xl.reqLoc1, xl.reqLoc2, xl.rankOffsets, xl.freeList, xl.freeOffsets, xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2, xl.linkState, xl.strainHist, xl.strainPlace, xl.allocCounts)
            .task("xfPlaceOrient", CrosslinkerSystem::placeOrient, xl.reqOrient, xl.rankOffsets, xl.freeList, xl.freeOffsets, xl.linkOrientSame, xl.torqueMagHist, xl.torqueMagPlace, xl.allocCounts);
    }

    static TaskGraph blkBind(TaskGraph tg, Scene s) {
        MotorStore mot = s.mot; RigidRodBody b = mot.body; FilamentStore f = s.fil; NodeNucleationStore nuc = s.nuc;
        MotorStore mot2 = s.mot2; RigidRodBody b2 = mot2.body; SpatialBodyView v = s.view;
        return tg
            .task("publishHead", MotorStore::publishHeadFromBody, b.coord, b.uVec, b.segLength, mot.head, mot.uVec, mot.rodUVec, mot.counts)
            .task("reach", BindingDetectionSystem::bruteReachableNodeAware, mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, s.reachSeg, s.reachCount, nuc.seedNode, mot.kinParams, mot.counts)
            .task("release", NucleotideCycleSystem::catchSlipRelease, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.cooldown, mot.stats, mot.capStats, mot.kinParams, mot.counts)
            .task("bind", BindingDetectionSystem::bindNearestNodeAware, mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, s.reachSeg, s.reachCount, mot.boundSeg, mot.bindArc, nuc.seedNode, mot.kinParams, mot.counts)
            .task("cycle", NucleotideCycleSystem::cycle, mot.nucleotideState, mot.boundSeg, mot.forceDotHist, mot.nucParams, mot.counts)
            .task("publishHead2", MotorStore::publishHeadFromBody, b2.coord, b2.uVec, b2.segLength, mot2.head, mot2.uVec, mot2.rodUVec, mot2.counts)
            .task("filPublish", FilamentStore::publishToBodyView, f.coord, f.segLength, v.center, v.boundingRadius, v.ownerStore, v.ownerSlot, s.viewParams, s.gridCounts)
            .task("motPublish", MotorStore::publishToBodyView, mot2.head, mot2.reach, v.center, v.boundingRadius, v.ownerStore, v.ownerSlot, mot2.publishParams, mot2.counts)
            .task("bodyCell", SpatialGrid::bodyCell, v.center, s.gridParams, s.gridDims, s.gridCounts, s.bodyCell)
            .task("chunkZero", SpatialGrid::gridChunkZero, s.chunkParams, s.gridDims, s.chunkCellCount)
            .task("chunkHist", SpatialGrid::gridChunkHistogram, s.bodyCell, s.gridCounts, s.chunkParams, s.gridDims, s.chunkCellCount)
            .task("chunkReduce", SpatialGrid::gridChunkReduce, s.gridDims, s.chunkParams, s.chunkCellCount, s.cellCount)
            .task("gScanLocal", SpatialGrid::gridScanLocal, s.gridDims, s.cellCount, s.gridCellOffsets, s.chunkSum)
            .task("gScanChunks", SpatialGrid::gridScanChunks, s.gridDims, s.chunkSum)
            .task("gScanAdd", SpatialGrid::gridScanAdd, s.gridDims, s.gridCellOffsets, s.gridCellContents, s.cellCount, s.chunkSum)
            .task("chunkScatter", SpatialGrid::gridChunkScatter, s.bodyCell, s.gridCounts, s.chunkParams, s.gridDims, s.gridCellOffsets, s.gridCellContents, s.chunkCellCount)
            .task("gridReach", BindingDetectionSystem::gridReachable, mot2.head, mot2.uVec, mot2.rodUVec, f.end1, f.end2, s.gridParams, s.gridDims, s.gridCellOffsets, s.gridCellContents, v.ownerStore, v.ownerSlot, s.reachSeg2, s.reachCount2, mot2.kinParams, mot2.counts)
            .task("release2", NucleotideCycleSystem::catchSlipRelease, mot2.boundSeg, mot2.forceDotFil, mot2.forceMag, mot2.cooldown, mot2.stats, mot2.capStats, mot2.kinParams, mot2.counts)
            .task("bind2", BindingDetectionSystem::bindNearest, mot2.head, mot2.uVec, mot2.rodUVec, f.end1, f.end2, s.reachSeg2, s.reachCount2, mot2.boundSeg, mot2.bindArc, mot2.kinParams, mot2.counts)
            .task("cycle2", NucleotideCycleSystem::cycle, mot2.nucleotideState, mot2.boundSeg, mot2.forceDotHist, mot2.nucParams, mot2.counts);
    }

    static TaskGraph blkStruct(TaskGraph tg, Scene s) {
        MotorStore mot = s.mot; DimerStore dim = s.dim; NodeStore nd = s.node;
        MotorStore mot2 = s.mot2; DimerStore dim2 = s.dim2; MiniFilamentStore mini = s.mini; FilamentStore f = s.fil;
        RigidRodBody b = mot.body, nb = nd.node, b2 = mot2.body, bb = mini.backbone;
        return tg
            .task("zeroMot", ChainBendingForceSystem::zeroAccumulators, b.forceSum, b.torqueSum, mot.counts)
            .task("zeroNode", ChainBendingForceSystem::zeroAccumulators, nb.forceSum, nb.torqueSum, nd.nodeBodyCounts)
            .task("zeroMot2", ChainBendingForceSystem::zeroAccumulators, b2.forceSum, b2.torqueSum, mot2.counts)
            .task("zeroBb", ChainBendingForceSystem::zeroAccumulators, bb.forceSum, bb.torqueSum, mini.bbCounts)
            .task("zeroFil", ChainBendingForceSystem::zeroAccumulators, f.forceSum, f.torqueSum, f.counts)
            .task("brownMot", BrownianForceSystem::brownianForce, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, b.brownTransScale, b.brownRotScale, mot.bodyParams, mot.counts)
            .task("brownNode", BrownianForceSystem::brownianForce, nb.randForce, nb.randTorque, nb.bTransGam, nb.bRotGam, nb.brownTransScale, nb.brownRotScale, nd.nodeBodyParams, nd.nodeBodyCounts)
            .task("brownMot2", BrownianForceSystem::brownianForce, b2.randForce, b2.randTorque, b2.bTransGam, b2.bRotGam, b2.brownTransScale, b2.brownRotScale, mot2.bodyParams, mot2.counts)
            .task("brownBb", BrownianForceSystem::brownianForce, bb.randForce, bb.randTorque, bb.bTransGam, bb.bRotGam, bb.brownTransScale, bb.brownRotScale, mini.bbBodyParams, mini.bbCounts)
            .task("brownFil", BrownianForceSystem::brownianForce, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.brownTransScale, f.brownRotScale, f.params, f.counts)
            .task("joints", MotorJointSystem::joints, b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, mot.nucleotideState, mot.jointParams, mot.counts)
            .task("dimer", DimerCouplingSystem::couple, b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, dim.motorA, dim.motorB, dim.parallel, dim.dimerParams, mot.boundSeg)
            .task("tether", NodeSystem::tether, b.coord, b.uVec, b.segLength, b.bTransGam, b.forceSum, b.torqueSum, nb.coord, nb.uVec, nb.yVec, nd.nodeInvTransY, nd.attachKey, nd.radial, nd.attachCoeffK, nd.nodeData, nd.nodeParams)
            .task("ndHist", CrossBridgeSystem::csrHistogram, nd.attachNode, nd.nodeCounts4, nd.nodeAttachCount)
            .task("ndScan", CrossBridgeSystem::csrScan, nd.nodeCounts4, nd.nodeAttachCount, nd.nodeAttachOffsets)
            .task("ndScatter", CrossBridgeSystem::csrScatter, nd.attachNode, nd.nodeCounts4, nd.nodeAttachOffsets, nd.nodeAttachCount, nd.nodeAttachList)
            .task("ndGather", MiniFilamentSystem::backboneGather, nd.nodeAttachOffsets, nd.nodeAttachList, nd.nodeData, nb.forceSum, nb.torqueSum, nd.nodeCounts4)
            .task("bond", CrossBridgeSystem::bondForces, b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength, mot.boundSeg, mot.bindArc, mot.nucleotideState, s.bondData, s.xbParams)
            .task("applyHead", CrossBridgeSystem::applyHeadForce, s.bondData, b.forceSum, b.torqueSum, mot.counts)
            .task("joints2", MotorJointSystem::joints, b2.coord, b2.uVec, b2.segLength, b2.bTransGam, b2.bRotGam, b2.forceSum, b2.torqueSum, mot2.nucleotideState, mot2.jointParams, mot2.counts)
            .task("dimer2", DimerCouplingSystem::couple, b2.coord, b2.uVec, b2.segLength, b2.bTransGam, b2.bRotGam, b2.forceSum, b2.torqueSum, dim2.motorA, dim2.motorB, dim2.parallel, dim2.dimerParams, mot2.boundSeg)
            .task("tether2", MiniFilamentSystem::tether, b2.coord, b2.uVec, b2.segLength, b2.bTransGam, b2.bRotGam, b2.forceSum, b2.torqueSum, bb.coord, bb.uVec, mini.bbInvDragY, mini.headBackboneSlot, mini.motorA, mini.attachAxial, mini.miniData, mini.miniParams)
            .task("bbHist", CrossBridgeSystem::csrHistogram, mini.headBackboneSlot, mini.miniCounts, mini.bbDimerCount)
            .task("bbScan", CrossBridgeSystem::csrScan, mini.miniCounts, mini.bbDimerCount, mini.bbDimerOffsets)
            .task("bbScatter", CrossBridgeSystem::csrScatter, mini.headBackboneSlot, mini.miniCounts, mini.bbDimerOffsets, mini.bbDimerCount, mini.bbDimerList)
            .task("bbGather", MiniFilamentSystem::backboneGather, mini.bbDimerOffsets, mini.bbDimerList, mini.miniData, bb.forceSum, bb.torqueSum, mini.miniCounts)
            .task("bond2", CrossBridgeSystem::bondForces, b2.coord, b2.uVec, b2.yVec, b2.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength, mot2.boundSeg, mot2.bindArc, mot2.nucleotideState, s.bondData2, s.xbParams)
            .task("applyHead2", CrossBridgeSystem::applyHeadForce, s.bondData2, b2.forceSum, b2.torqueSum, mot2.counts);
    }

    static TaskGraph blkFil(TaskGraph tg, Scene s) {
        FilamentStore f = s.fil; NodeNucleationStore nuc = s.nuc; NodeStore nd = s.node; RigidRodBody nb = nd.node;
        MotorStore mot = s.mot; MotorStore mot2 = s.mot2;
        tg = tg
            .task("chain", ChainBendingForceSystem::chainForces, f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide, f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts)
            .task("seedTether", NodeNucleationSystem::seedTether, f.coord, f.uVec, f.segLength, f.bTransGam, f.forceSum, f.torqueSum, nb.coord, nd.nodeInvTransY, nuc.seedNode, nuc.tetherParams)
            .task("seedReact", NodeNucleationSystem::seedTetherNodeReact, f.coord, f.uVec, f.segLength, f.bTransGam, nb.forceSum, nb.coord, nd.nodeInvTransY, nuc.seedNode, nuc.tetherParams)
            .task("filHist", CrossBridgeSystem::csrHistogram, mot.boundSeg, mot.counts, s.segMotorCount)
            .task("filScan", CrossBridgeSystem::csrScan, mot.counts, s.segMotorCount, s.segMotorOffsets)
            .task("filScatter", CrossBridgeSystem::csrScatter, mot.boundSeg, mot.counts, s.segMotorOffsets, s.segMotorCount, s.segMotorMyo)
            .task("filGather", CrossBridgeSystem::segGather, s.segMotorOffsets, s.segMotorMyo, s.bondData, f.forceSum, f.torqueSum, mot.counts)
            .task("filHist2", CrossBridgeSystem::csrHistogram, mot2.boundSeg, mot2.counts, s.segMotorCount2)
            .task("filScan2", CrossBridgeSystem::csrScan, mot2.counts, s.segMotorCount2, s.segMotorOffsets2)
            .task("filScatter2", CrossBridgeSystem::csrScatter, mot2.boundSeg, mot2.counts, s.segMotorOffsets2, s.segMotorCount2, s.segMotorMyo2)
            .task("filGather2", CrossBridgeSystem::segGather, s.segMotorOffsets2, s.segMotorMyo2, s.bondData2, f.forceSum, f.torqueSum, mot2.counts)
            .task("register", CrossBridgeSystem::registerForceDot, s.bondData, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.counts)
            .task("register2", CrossBridgeSystem::registerForceDot, s.bondData2, mot2.boundSeg, mot2.forceDotFil, mot2.forceMag, mot2.forceDotHist, mot2.forceDotPlace, mot2.counts);
        // EVERY-STEP crosslinker FORCE (appended after the motor seg-gathers, before fdInteg — cpuStep's force-phase
        // order, lines 680-685 + 740-750): unbind · countActiveLinks · linkForces · linkTorsion · the 2-pass seg-gather
        // into f.forceSum/torqueSum. Runs every step (unlike formation, which is cadence-gated in fdXForm). f.forceSum
        // was zeroed in fdStruct; this accumulates onto it like the chain + motor gathers above.
        if (s.xl != null) {
            CrosslinkerStore xl = s.xl;
            tg = tg
                .task("xlUnbind", CrosslinkerSystem::unbind, f.coord, f.uVec, f.end1, xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2, xl.linkState, xl.strainHist, xl.strainPlace, xl.offParams, xl.counts)
                .task("xlCount", CrosslinkerSystem::countActiveLinks, xl.linkState, xl.linkFilA, xl.linkFilB, xl.activeLinkCount, xl.formCounts)
                .task("xlForce", CrosslinkerSystem::linkForces, f.coord, f.uVec, f.end1, f.bTransGam, xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2, xl.activeLinkCount, xl.xlinkData, xl.xlParams)
                .task("xlTorsion", CrosslinkerSystem::linkTorsion, f.uVec, xl.linkFilA, xl.linkFilB, xl.linkState, xl.linkOrientSame, xl.torqueMagHist, xl.torqueMagPlace, xl.xlinkData, xl.torsionParams)
                .task("xlHistA", CrossBridgeSystem::csrHistogram, xl.linkFilA, xl.counts, s.segCountA)
                .task("xlScanA", CrossBridgeSystem::csrScan, xl.counts, s.segCountA, s.segOffsetsA)
                .task("xlScatterA", CrossBridgeSystem::csrScatter, xl.linkFilA, xl.counts, s.segOffsetsA, s.segCountA, s.segIdxA)
                .task("xlGatherA", CrosslinkerSystem::segGatherA, s.segOffsetsA, s.segIdxA, xl.xlinkData, xl.linkState, f.forceSum, f.torqueSum, xl.counts)
                .task("xlHistB", CrossBridgeSystem::csrHistogram, xl.linkFilB, xl.counts, s.segCountB)
                .task("xlScanB", CrossBridgeSystem::csrScan, xl.counts, s.segCountB, s.segOffsetsB)
                .task("xlScatterB", CrossBridgeSystem::csrScatter, xl.linkFilB, xl.counts, s.segOffsetsB, s.segCountB, s.segIdxB)
                .task("xlGatherB", CrosslinkerSystem::segGatherB, s.segOffsetsB, s.segIdxB, xl.xlinkData, xl.linkState, f.forceSum, f.torqueSum, xl.counts);
        }
        return tg;
    }

    static TaskGraph blkInteg(TaskGraph tg, Scene s) {
        MotorStore mot = s.mot; NodeStore nd = s.node; MotorStore mot2 = s.mot2; MiniFilamentStore mini = s.mini; FilamentStore f = s.fil;
        RigidRodBody b = mot.body, nb = nd.node, b2 = mot2.body, bb = mini.backbone;
        return tg
            .task("confineNode", ContainmentSystem::confine, nb.coord, nb.uVec, nb.segLength, nb.bTransGam, nb.forceSum, nb.torqueSum, s.boxParams, nd.nodeBodyCounts)
            .task("integNode", RigidRodLangevinIntegrationSystem::integrate, nb.coord, nb.uVec, nb.yVec, nb.forceSum, nb.torqueSum, nb.randForce, nb.randTorque, nb.bTransGam, nb.bRotGam, nd.nodeBodyParams, nd.nodeBodyCounts)
            .task("deriveNode", DerivedGeometrySystem::derive, nb.coord, nb.uVec, nb.yVec, nb.zVec, nb.end1, nb.end2, nb.segLength, nd.nodeBodyCounts)
            .task("integM", RigidRodLangevinIntegrationSystem::integrate, b.coord, b.uVec, b.yVec, b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, mot.bodyParams, mot.counts)
            .task("deriveM", DerivedGeometrySystem::derive, b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts)
            .task("integM2", RigidRodLangevinIntegrationSystem::integrate, b2.coord, b2.uVec, b2.yVec, b2.forceSum, b2.torqueSum, b2.randForce, b2.randTorque, b2.bTransGam, b2.bRotGam, mot2.bodyParams, mot2.counts)
            .task("deriveM2", DerivedGeometrySystem::derive, b2.coord, b2.uVec, b2.yVec, b2.zVec, b2.end1, b2.end2, b2.segLength, mot2.counts)
            .task("confineBb", ContainmentSystem::confine, bb.coord, bb.uVec, bb.segLength, bb.bTransGam, bb.forceSum, bb.torqueSum, s.boxParams, mini.bbCounts)
            .task("integBb", RigidRodLangevinIntegrationSystem::integrate, bb.coord, bb.uVec, bb.yVec, bb.forceSum, bb.torqueSum, bb.randForce, bb.randTorque, bb.bTransGam, bb.bRotGam, mini.bbBodyParams, mini.bbCounts)
            .task("deriveBb", DerivedGeometrySystem::derive, bb.coord, bb.uVec, bb.yVec, bb.zVec, bb.end1, bb.end2, bb.segLength, mini.bbCounts)
            .task("confineFil", ContainmentSystem::confine, f.coord, f.uVec, f.segLength, f.bTransGam, f.forceSum, f.torqueSum, s.boxParams, f.counts)
            .task("integFil", RigidRodLangevinIntegrationSystem::integrate, f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts)
            .task("deriveFil", DerivedGeometrySystem::derive, f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
    }

    /** Re-key every RNG/trig kernel's localWork=64 WorkerGrid under its NEW graph-name prefix (GridScheduler keys are
     *  "<graphName>.<task>"). Identical worker sizes to the monolith; only the graph prefix changes per the split. */
    static void buildSplitScheduler(Scene s) {
        MotorStore mot = s.mot; NodeStore nd = s.node; MotorStore mot2 = s.mot2; MiniFilamentStore mini = s.mini; FilamentStore f = s.fil;
        DimerStore dim = s.dim; DimerStore dim2 = s.dim2;
        RigidRodBody b = mot.body, nb = nd.node, b2 = mot2.body, bb = mini.backbone;
        int nM = mot.nMotors, nMB = b.n, nN = nb.n, C = f.n, nD = dim.nDimers;
        int nM2 = mot2.nMotors, nMB2 = b2.n, nD2 = dim2.nDimers, nBb = bb.n, nA = nd.nAttach, cap = s.viewCap, totalCells = s.totalCells;
        int numScan = (totalCells + SpatialGrid.GRID_SCAN_CHUNK - 1) / SpatialGrid.GRID_SCAN_CHUNK;
        sched = new GridScheduler();
        // G0 fdTurnFire — turnover (fire-gated; all C-sized except the single-thread CSR scans)
        for (String t : new String[]{ "age","depoly","applyDeath","grow","growthAtp","cofAcc","cofDis","severDeath","markSplits",
                "gFreeFlags","gFreeScatter","gAllocate","splitWire","splitInherit","splitCof","recomputeDrag" }) addW("fdTurnFire." + t, pad(C));
        for (String t : new String[]{ "csrReturn","csrGrew","csrSever","gCsrFree","gCsrRank" }) addS("fdTurnFire." + t);
        // G1 fdNuc — node nucleation (always-run; C-sized except count/emit and the single-thread CSR scans)
        for (String t : new String[]{ "nFreeFlags","nFreeScatter","nAllocate","tagSeeds","initNewborn","nucFresh","nucCof" }) addW("fdNuc." + t, pad(C));
        addW("fdNuc.count", pad(1)); addW("fdNuc.emit", pad(nN));
        for (String t : new String[]{ "nCsrFree","nCsrRank" }) addS("fdNuc." + t);
        // G1 fdBind
        for (String t : new String[]{ "publishHead","reach","release","bind","cycle" }) addW("fdBind." + t, pad(nM));
        for (String t : new String[]{ "publishHead2","gridReach","release2","bind2","cycle2","motPublish" }) addW("fdBind." + t, pad(nM2));
        addW("fdBind.filPublish", pad(C));
        addW("fdBind.bodyCell", pad(cap)); addW("fdBind.chunkZero", pad(s.numBodyChunks * totalCells));
        addW("fdBind.chunkHist", pad(s.numBodyChunks)); addW("fdBind.chunkReduce", pad(totalCells));
        addW("fdBind.chunkScatter", pad(s.numBodyChunks)); addW("fdBind.gScanLocal", pad(numScan)); addW("fdBind.gScanAdd", pad(numScan));
        addS("fdBind.gScanChunks");
        // G2 fdStruct
        for (String t : new String[]{ "zeroMot","brownMot","joints" }) addW("fdStruct." + t, pad(nMB));
        for (String t : new String[]{ "bond","applyHead" }) addW("fdStruct." + t, pad(nM));
        for (String t : new String[]{ "zeroNode","brownNode","ndGather" }) addW("fdStruct." + t, pad(nN));
        for (String t : new String[]{ "zeroMot2","brownMot2","joints2" }) addW("fdStruct." + t, pad(nMB2));
        for (String t : new String[]{ "bond2","applyHead2" }) addW("fdStruct." + t, pad(nM2));
        for (String t : new String[]{ "zeroBb","brownBb","bbGather" }) addW("fdStruct." + t, pad(nBb));
        for (String t : new String[]{ "zeroFil","brownFil" }) addW("fdStruct." + t, pad(C));
        addW("fdStruct.dimer", pad(nD)); addW("fdStruct.tether", pad(nA)); addW("fdStruct.dimer2", pad(nD2)); addW("fdStruct.tether2", pad(nD2));
        for (String t : new String[]{ "ndHist","ndScan","ndScatter","bbHist","bbScan","bbScatter" }) addS("fdStruct." + t);
        // G3 fdFil
        for (String t : new String[]{ "chain","seedTether","filGather","filGather2" }) addW("fdFil." + t, pad(C));
        addW("fdFil.seedReact", pad(nN)); addW("fdFil.register", pad(nM)); addW("fdFil.register2", pad(nM2));
        for (String t : new String[]{ "filHist","filScan","filScatter","filHist2","filScan2","filScatter2" }) addS("fdFil." + t);
        // G4 fdInteg
        for (String t : new String[]{ "confineNode","integNode","deriveNode" }) addW("fdInteg." + t, pad(nN));
        for (String t : new String[]{ "integM","deriveM" }) addW("fdInteg." + t, pad(nMB));
        for (String t : new String[]{ "integM2","deriveM2" }) addW("fdInteg." + t, pad(nMB2));
        for (String t : new String[]{ "confineBb","integBb","deriveBb" }) addW("fdInteg." + t, pad(nBb));
        for (String t : new String[]{ "confineFil","integFil","deriveFil" }) addW("fdInteg." + t, pad(C));
        // fdXForm (filID + crosslinker formation) + fdFil's appended crosslinker FORCE — only when xlinkers are wired.
        // FormationGrid grid kernels are keyed to ITS OWN dims (fg.totalCells/numBodyChunks, NOT the binding grid — the
        // GridScheduler trap). filID jumps + the RNG/trig formation kernels get localWork=64 (addW); CSR scans single.
        if (s.xl != null) {
            FormationGrid fg = s.fg; int reqCap = s.xl.reqCap, nLk = s.xl.nLinks;
            int fgCells = fg.totalCells, fgChunks = fg.numBodyChunks;
            int fgScan = (fgCells + SpatialGrid.GRID_SCAN_CHUNK - 1) / SpatialGrid.GRID_SCAN_CHUNK;
            addW("fdXForm.filidInit", pad(C));
            for (int k = 0; k < s.filIDRounds; k++) addW("fdXForm.filidJump" + k, pad(C));
            addS("fdXForm.xfCount");
            addW("fdXForm.xfPublish", pad(C)); addW("fdXForm.xfBodyCell", pad(C));
            addW("fdXForm.xfChunkZero", pad(fgChunks * fgCells)); addW("fdXForm.xfChunkHist", pad(fgChunks));
            addW("fdXForm.xfChunkReduce", pad(fgCells)); addW("fdXForm.xfScanLocal", pad(fgScan));
            addS("fdXForm.xfScanChunks"); addW("fdXForm.xfScanAdd", pad(fgScan)); addW("fdXForm.xfChunkScatter", pad(fgChunks));
            addW("fdXForm.xfFormCount", pad(C)); addS("fdXForm.xfFormScan"); addW("fdXForm.xfFormEmit", pad(C));
            addW("fdXForm.xfGates", pad(reqCap)); addS("fdXForm.xfAdmitReduce"); addW("fdXForm.xfAdmit", pad(reqCap));
            addW("fdXForm.xfFreeFlags", pad(nLk)); addS("fdXForm.xfScanFree"); addS("fdXForm.xfFreeScatter"); addS("fdXForm.xfScanRank");
            addW("fdXForm.xfAllocate", pad(reqCap)); addW("fdXForm.xfPlaceOrient", pad(reqCap));
            // fdFil crosslinker force (per-link tasks pad(nLinks); seg-gather pad(nSeg=C); CSR scans single-thread)
            addW("fdFil.xlUnbind", pad(nLk)); addS("fdFil.xlCount");
            addW("fdFil.xlForce", pad(nLk)); addW("fdFil.xlTorsion", pad(nLk));
            addS("fdFil.xlHistA"); addS("fdFil.xlScanA"); addS("fdFil.xlScatterA"); addW("fdFil.xlGatherA", pad(C));
            addS("fdFil.xlHistB"); addS("fdFil.xlScanB"); addS("fdFil.xlScatterB"); addW("fdFil.xlGatherB", pad(C));
        }
    }

    /** One device-resident step across the chained graphs (mirrors stepHostBookkeeping's host bookkeeping +
     *  cpuStep's task order). Host pulls only the pool-ledger offsets (from G0) per step. */
    static void stepSplit(Scene g, int t, double dt, TornadoExecutionPlan plan) {
        MotorStore mot = g.mot; MotorStore mot2 = g.mot2; NodeStore nd = g.node; MiniFilamentStore mini = g.mini;
        FilamentStore f = g.fil; NodeNucleationStore nuc = g.nuc; GrowthStore grow = g.grow; DepolyStore d = g.depoly;
        AgingStore ag = g.aging; SeverStore sv = g.sever;
        boolean fires = grow.firesAt(t);
        mot.setCounts(t, SEED, f.n); nd.setNodeBodyCounts(t, SEED_NODE); f.setCounts(t, SEED);
        mot2.setCounts(t, SEED_MINI, f.n); mini.setBackboneCounts(t, SEED_BB);
        grow.setCounts(t, SEED, fires); grow.refreshRate(fires);
        d.setCounts(t, SEED, fires); ag.setFires(fires); sv.setFires(fires);
        nuc.setCounts(t, SEED);
        nuc.nucCounts.set(3, grow.pool.available(Constants.actinSeed) ? 1 : 0);
        boolean formFires = g.xl != null && t % XL_CHECK_INT == 0;
        if (g.xl != null && formFires) g.xl.setFormStep(t, SEED);
        if (g.xl != null) g.xl.setCounts(t, SEED);                 // every step (fdFil force/unbind RNG + CSR bounds)
        // CADENCE GATE: fdTurnFire (a skipped SOURCE graph) launches turnover ONLY on fire steps; fdXForm (the LAST
        // graph — a skipped graph with no successor consuming its sole-uploaded scratch) launches the formation pipeline
        // ONLY on the formation cadence. Both skips are residency-safe (§8.1). fdNuc + binding/structure/force/integrate
        // run EVERY step. Formation at end-of-step N ⇒ its new links are forced from step N+1 (a ≤1-step shift, §5c-i).
        for (int gi = 0; gi < N_SPLIT; gi++) {
            if (gi == GI_TURN && !fires) continue;                 // skip turnover off-cadence
            if (gi == GI_XFORM && !formFires) continue;            // skip crosslinker formation off-cadence
            TornadoExecutionResult r = plan.withGraph(gi).withGridScheduler(sched).execute();
            if (gi == GI_TURN)      { r.transferToHost(grow.grewOffsets, d.returnedOffsets, sv.severReturnedOffsets); splitResTurn = r; }
            else if (gi == GI_NUC)  { r.transferToHost(g.nucRankOffsets, f.freeOffsets); splitResNuc = r; }
            else if (gi == GI_BIND) splitResBind = r;
            else if (gi == GI_FIL)  splitResFil = r;
            if (gi == GI_INTEG) splitResInteg = r;          // always-run; the derived-geometry render pull
            if (gi == N_SPLIT - 1) splitResL = r;
        }
        // turnover pool bookkeeping: ONLY on fire steps (on non-fire steps the device did no depoly/sever/grow ⇒ those
        // offsets would be 0; the host arrays hold stale fire-step values, so the add must be skipped, not zeroed).
        if (fires) {
            grow.pool.put(d.returnedOffsets.get(f.n) + sv.severReturnedOffsets.get(f.n));
            grow.pool.take(grow.grewOffsets.get(f.n));
        }
        // nucleation pool bookkeeping: every step (fdNuc always runs).
        int nucBirths = Math.min(g.nucRankOffsets.get(f.n), f.freeOffsets.get(f.n));
        if (nucBirths > 0) grow.pool.take(nucBirths * Constants.actinSeed);
        g.lastNucBirths = nucBirths;
    }

    /** Pull the host-side render + hunt state from the chained-graph results (device-resident; an UNDER_DEMAND pull,
     *  only at the frame/check cadence — NOT per step). */
    static void pullRenderState(Scene g) {
        MotorStore mot = g.mot, mot2 = g.mot2; NodeStore nd = g.node; MiniFilamentStore mini = g.mini; FilamentStore f = g.fil;
        AgingStore ag = g.aging; SeverStore sv = g.sever; RigidRodBody b = mot.body, nb = nd.node, b2 = mot2.body, bb = mini.backbone;
        // render state from the ALWAYS-run graphs: filament state from fdNuc (current after births), binding from
        // fdBind, derived geometry from the last graph. (NOT fdTurnFire — skipped on non-fire check steps.)
        if (splitResNuc != null) splitResNuc.transferToHost(f.filState, f.monomerCount, f.segLength, ag.nucFrac, sv.cofFrac);
        if (splitResBind != null) splitResBind.transferToHost(mot.boundSeg, mot2.boundSeg, mot.nucleotideState, mot2.nucleotideState);
        // crosslink state from fdFil (always-run; unbind/force mutate linkState there ⇒ current after every step).
        if (splitResFil != null && g.xl != null)
            splitResFil.transferToHost(g.xl.linkState, g.xl.linkFilA, g.xl.linkFilB, g.xl.loc1, g.xl.loc2, g.xl.linkOrientSame);
        // derived geometry from fdInteg (always-run; host4 is registered there) — NOT splitResL, which is the
        // cadence-gated fdXForm when crosslinkers are wired (stale on non-formation steps).
        if (splitResInteg != null) splitResInteg.transferToHost(nb.coord, f.coord, f.end1, f.end2, b.end1, b.end2, b2.end1, b2.end2, bb.coord, bb.end1, bb.end2);
    }

    // ====================================================================== STAGE 2 — device-resident overnight run
    /** The first device-resident execution of the MAXIMAL composition over a long horizon (the split GPU path).
     *  Runs the split chained graphs, dumps a watchable render, logs status + 5-min progress, and FAILS SAFE on any
     *  NaN/conservation/phantom/wall-escape (does NOT auto-restart). Device-resident throughout — never CPU fallback. */
    static void overnightRun(Scene s, double dt) {
        System.out.println("\n=== STAGE 2 — device-resident OVERNIGHT run (split chained TaskGraphs; KIN=" + (int) KIN + ") ===");
        TornadoExecutionPlan plan;
        try { plan = buildPlanSplit(s); }
        catch (Throwable e) { System.out.println("  STAGE2 split build FAILED: " + e); e.printStackTrace(); return; }
        int M = STEPS;
        long wallCapMs = 5L * 3600 * 1000 + 30L * 60 * 1000;   // ~5.5 h — leave margin
        int frameEvery = Math.max(1, M / 300);                 // ~300 frames
        int checkEvery = Math.max(1, Math.min(frameEvery, M / 600));
        String vd = (overnightViz != null) ? overnightViz : "threejs_fulldemo_overnight";
        new java.io.File(vd).mkdirs();
        java.io.File statusFile = new java.io.File(".last_run_status");
        double[] ext0 = netExtent(s); double rms0 = ext0[0];
        long t0 = System.nanoTime(); long lastPrint = t0;
        int frames = 0; boolean ok = true; String fail = null; int doneSteps = 0;
        System.out.printf("  steps=%d, dt=%.0e (sim %.3f s), frames≈%d (every %d), check every %d; render→%s%n",
                M, dt, M * dt, M / frameEvery + 1, frameEvery, checkEvery, vd);
        for (int t = 0; t < M; t++) {
            try { stepSplit(s, t, dt, plan); }
            catch (Throwable e) { ok = false; fail = "step threw @ " + t + ": " + e; e.printStackTrace(); break; }
            doneSteps = t + 1;
            boolean frame = (t % frameEvery == 0) || (t == M - 1);
            boolean check = frame || (t % checkEvery == 0);
            if (check) {
                pullRenderState(s);
                boolean nan = anyNaN(s); boolean cons = conservationCheck(s);
                int phantom = phantomCount(s.fil); int esc = wallEscapes(s);
                // wall-escape bail is for a true RUNAWAY only — a handful of warm-start IC tips just past the edge are
                // corrected by containment over the next steps (the §3 transient), NOT a blow-up. Threshold scales with N.
                int escBail = Math.max(40, activeSegments(s.fil) / 40);
                if (nan)      { ok = false; fail = "NaN/blow-up @ " + t; }
                if (!cons)    { ok = false; fail = "conservation-ledger violation @ " + t; }
                if (phantom > 0) { ok = false; fail = "phantom (" + phantom + ") @ " + t; }
                if (esc > escBail) { ok = false; fail = "wall-escape RUNAWAY (" + esc + " > " + escBail + ") @ " + t; }
                double[] ext = netExtent(s); double secs = (System.nanoTime() - t0) / 1e9;
                writeStatus(statusFile, t, M, ext[0], 0, activeSegments(s.fil), cons, phantom, nan, secs);
                if ((System.nanoTime() - lastPrint) / 1e6 > 5 * 60 * 1000 || t == 0) {
                    lastPrint = System.nanoTime();
                    double sps = t > 0 ? t / secs : 0; long etaS = sps > 0 ? (long) ((M - t) / sps) : 0;
                    System.out.printf("[progress] step %d/%d (%.0f%%), %.0f steps/s, sim %.3f s, rms=%.4f, active=%d, nodeBnd=%d, miniBnd=%d, conc=%.3f, vram=%s, ETA %02d:%02d%n",
                            t, M, 100.0 * t / M, sps, t * dt, ext[0], activeSegments(s.fil), boundTotal(s.mot), boundTotal(s.mot2),
                            s.grow.pool.conc(), vramMB(), etaS / 3600, (etaS % 3600) / 60);
                }
                if (frame && ok) writeFrame(vd, frames++, t, t * dt, s);
                if (!ok) { System.out.println("  *** STAGE2 BAIL — " + fail + " — flushing render + status, stopping (no auto-restart) ***"); break; }
            }
            if ((System.nanoTime() - t0) / 1e6 > wallCapMs) { System.out.println("  wall-clock cap (~5.5h) reached @ step " + t); break; }
        }
        double secs = (System.nanoTime() - t0) / 1e9;
        pullRenderState(s);   // refresh host state to the final device step (the pool ledger is per-step; monomerCount
                              // is only pulled at the check cadence ⇒ a final pull keeps the conservation summary valid)
        double[] extEnd = netExtent(s); double shrink = (rms0 - extEnd[0]) / rms0;
        System.out.println("\n===== STAGE 2 RESULT =====");
        System.out.printf("  ran %d steps in %.1f s (%.0f steps/s, device-resident split) ⇒ sim %.3f s; %d frames → %s%n",
                doneSteps, secs, doneSteps / secs, doneSteps * dt, frames, vd);
        System.out.printf("  node-net RMS: start=%.4f → end=%.4f µm (%.1f%% ⇒ %s)%n", rms0, extEnd[0], 100 * shrink,
                shrink > 0.15 ? "COALESCING" : shrink > 0.04 ? "mild contraction" : shrink < -0.10 ? "DISPERSING" : "STABLE");
        System.out.printf("  binding: node-shell=%d/%d, free-minifil=%d/%d; turnover taken=%d returned=%d monomers%n",
                boundTotal(s.mot), s.mot.nMotors, boundTotal(s.mot2), s.mot2.nMotors, s.grow.pool.totalTaken(), s.grow.pool.totalReturned());
        System.out.printf("  SANITY: conservation=%s, phantoms=%d, wall-escapes=%d, NaN=%s ⇒ %s; peak VRAM≈%s%n",
                conservationCheck(s) ? "EXACT" : "*** FAIL ***", phantomCount(s.fil), wallEscapes(s), anyNaN(s) ? "*** YES ***" : "none",
                ok ? "CLEAN" : "*** BAILED: " + fail + " ***", vramMB());
    }

    // ====================================================================== PROFILE — per-step time/transfer budget
    // MEASUREMENT-ONLY. Mirrors stepSplit EXACTLY (same host bookkeeping, same per-graph execute order, same host
    // pull) but wraps each section in System.nanoTime() and reads the TornadoVM per-graph profiler result
    // (getDeviceKernelTime / getKernelDispatchTime / getDataTransfersTime / getTotalBytesCopyIn|Out). No kernel /
    // force-law / ordering / default edit; the production stepSplit/overnightRun/gpuScaleCheck paths are untouched.
    /** Per-graph + host timing accumulators (ns / bytes), summed over the measured steps. */
    static final class Acc {
        final long[] gWall, gKern, gDisp, gXfer, gIn, gOut;
        long hostBkkp, hostPull;
        Acc(int n) { gWall=new long[n]; gKern=new long[n]; gDisp=new long[n]; gXfer=new long[n]; gIn=new long[n]; gOut=new long[n]; }
    }

    /** One device-resident step, identical to stepSplit, with optional timing into {@code a} (null ⇒ warmup, untimed). */
    static void profStep(Scene g, int t, double dt, TornadoExecutionPlan plan, Acc a) {
        MotorStore mot = g.mot, mot2 = g.mot2; NodeStore nd = g.node; MiniFilamentStore mini = g.mini;
        FilamentStore f = g.fil; NodeNucleationStore nuc = g.nuc; GrowthStore grow = g.grow; DepolyStore d = g.depoly;
        AgingStore ag = g.aging; SeverStore sv = g.sever;
        long h0 = System.nanoTime();
        boolean fires = !frozen && grow.firesAt(t);   // -frozen ⇒ no growth/depoly/aging/sever events (segment count flat)
        mot.setCounts(t, SEED, f.n); nd.setNodeBodyCounts(t, SEED_NODE); f.setCounts(t, SEED);
        mot2.setCounts(t, SEED_MINI, f.n); mini.setBackboneCounts(t, SEED_BB);
        grow.setCounts(t, SEED, fires); grow.refreshRate(fires);
        d.setCounts(t, SEED, fires); ag.setFires(fires); sv.setFires(fires);
        nuc.setCounts(t, SEED);
        nuc.nucCounts.set(3, grow.pool.available(Constants.actinSeed) ? 1 : 0);
        boolean formFires = g.xl != null && t % XL_CHECK_INT == 0;
        if (g.xl != null && formFires) g.xl.setFormStep(t, SEED);
        if (g.xl != null) g.xl.setCounts(t, SEED);
        long h1 = System.nanoTime();
        for (int gi = 0; gi < N_SPLIT; gi++) {
            if (gi == GI_TURN && !fires) continue;            // CADENCE GATE — mirror stepSplit: skip fdTurnFire off-cadence
            if (gi == GI_XFORM && !formFires) continue;       // skip crosslinker formation off-cadence (the LAST graph)
            long d0 = System.nanoTime();
            TornadoExecutionResult r = plan.withGraph(gi).withGridScheduler(sched).execute();
            long d1 = System.nanoTime();
            if (gi == GI_TURN)      { r.transferToHost(grow.grewOffsets, d.returnedOffsets, sv.severReturnedOffsets); splitResTurn = r; }
            else if (gi == GI_NUC)  { r.transferToHost(g.nucRankOffsets, f.freeOffsets); splitResNuc = r; }
            else if (gi == GI_BIND) splitResBind = r;
            else if (gi == GI_FIL)  splitResFil = r;
            if (gi == GI_INTEG) splitResInteg = r;          // always-run; the derived-geometry render pull
            if (gi == N_SPLIT - 1) splitResL = r;
            long d2 = System.nanoTime();
            if (a != null) {
                a.gWall[gi] += d1 - d0;
                a.hostPull += d2 - d1;                 // the UNDER_DEMAND pool-ledger pull after G0/G1; ~0 elsewhere
                if (!noprof) {
                    TornadoProfilerResult pr = r.getProfilerResult();   // read OUTSIDE the timed regions (measurement overhead, unattributed)
                    a.gKern[gi] += pr.getDeviceKernelTime();
                    a.gDisp[gi] += pr.getKernelDispatchTime();
                    a.gXfer[gi] += pr.getDataTransfersTime();
                    a.gIn[gi]   += pr.getTotalBytesCopyIn();
                    a.gOut[gi]  += pr.getTotalBytesCopyOut();
                }
            }
        }
        if (!noprof) plan.clearProfiles();   // bound the per-execution profiler-result accumulation on the Java heap (else OOM over 10^5 steps)
        long h2 = System.nanoTime();
        if (fires) {
            grow.pool.put(d.returnedOffsets.get(f.n) + sv.severReturnedOffsets.get(f.n));
            grow.pool.take(grow.grewOffsets.get(f.n));
        }
        int nucBirths = Math.min(g.nucRankOffsets.get(f.n), f.freeOffsets.get(f.n));
        if (nucBirths > 0) grow.pool.take(nucBirths * Constants.actinSeed);
        g.lastNucBirths = nucBirths;
        long h3 = System.nanoTime();
        if (a != null) a.hostBkkp += (h1 - h0) + (h3 - h2);
    }

    static String[] GNAME = { "fdTurnFire", "fdNuc", "fdBind", "fdStruct", "fdFil", "fdInteg" };   // reassigned in buildPlanSplit (7 graphs when xlinkers are wired)

    /** MEASUREMENT probe — periodically flush the chained-split per-execute() creep (PROFILE §4 / SPLIT §8).
     *  mode "device": plan.resetDevice() — cleans the PTX streams/events/code-cache + kernel stack frame (the
     *  per-execution accumulation), data buffers survive (residency intact); forces a one-time PTX recompile on the
     *  next execute. mode "rebuild": tear down + rebuild the TornadoExecutionPlan from a full host state round-trip.
     *  Returns the (possibly new) plan; both re-apply the GridScheduler per-execute in profStep. */
    /** Pull EVERY persisted SoA buffer device→host (the full state), so a rebuild's FIRST_EXECUTION restores it
     *  bit-exactly. Uses the last graph's result (the full set is registered UNDER_DEMAND there in rebuild mode). */
    static void pullFullState(Scene s) {
        if (splitResL != null && fullPersistSet != null) splitResL.transferToHost(fullPersistSet);
    }

    static TornadoExecutionPlan planReset(TornadoExecutionPlan plan, Scene s) {
        long t0 = System.nanoTime();
        TornadoExecutionPlan np;
        if (planResetMode.equals("rebuild")) {
            pullFullState(s);                 // device→host: every persisted buffer (so the rebuild's FIRST_EXECUTION restores it)
            try { plan.close(); } catch (Throwable e) { /* freeDeviceMemory under the hood */ }
            np = buildPlanSplit(s);           // fresh plan; FIRST_EXECUTION re-uploads the just-pulled host state
            if (!noprof) np = np.withProfiler(ProfilerMode.SILENT);
        } else {
            np = plan.resetDevice();          // cheap in-place flush; buffers survive
        }
        long t1 = System.nanoTime();
        planResetCostNs += (t1 - t0); planResetCount++;
        return np;
    }

    static void profileRun(Scene s, double dt) {
        System.out.printf("%n=== PROFILE (MEASUREMENT-ONLY) — per-step time + transfer budget across %d chained TaskGraphs ===%n", N_SPLIT);
        System.out.printf("  scene: %d active segments, %d node-shell heads, %d free-minifil heads; turnover=%s%n",
                activeSegments(s.fil), s.mot.nMotors, s.mot2.nMotors, frozen ? "FROZEN (growth-cap control: fires=0, nuc rate 0)" : "LIVE");
        TornadoExecutionPlan plan;
        try { plan = buildPlanSplit(s); }
        catch (Throwable e) { System.out.println("  profile plan build FAILED: " + e); e.printStackTrace(); return; }
        if (!noprof) plan = plan.withProfiler(ProfilerMode.SILENT);
        else System.out.println("  PROFILER OFF (-noprof): per-graph nanoTime only (matches production stepSplit; no heap accumulation) — devKernel/xfer/bytes will read 0.");
        if (frozen) s.nuc.nucParams.set(0, 0f);

        int nG = N_SPLIT;
        System.out.printf("  warmup %d steps (FIRST_EXECUTION uploads + PTX JIT), then measure %d steps; nvidia: %s%n",
                profWarm, profSteps, nvidiaStat());
        for (int t = 0; t < profWarm; t++) profStep(s, t, dt, plan, null);
        pullRenderState(s);
        System.out.printf("  post-warmup: active=%d, node-bound=%d, minifil-bound=%d, conc=%.4f µM%n",
                activeSegments(s.fil), boundTotal(s.mot), boundTotal(s.mot2), s.grow.pool.conc());

        Acc a = new Acc(nG);
        // rolling window for the Stage-C decay slope
        long[] prevWall = new long[nG]; long prevClock = System.nanoTime(); long winStart = prevClock; int prevTotalSeg = activeSegments(s.fil);
        long t0 = System.nanoTime();
        if (planResetEvery > 0)
            System.out.printf("  PLAN-RESET probe: mode=%s every %d steps (flush the per-execute() creep)%n", planResetMode, planResetEvery);
        for (int i = 0; i < profSteps; i++) {
            int t = profWarm + i;
            profStep(s, t, dt, plan, a);
            if (planResetEvery > 0 && (i + 1) % planResetEvery == 0) plan = planReset(plan, s);
            if (profLogEvery > 0 && (i + 1) % profLogEvery == 0) {
                long now = System.nanoTime();
                double winSecs = (now - prevClock) / 1e9;
                double sps = profLogEvery / winSecs;
                StringBuilder pg = new StringBuilder();
                for (int gi = 0; gi < nG; gi++) { pg.append(String.format("%s=%.2f ", GNAME[gi], (a.gWall[gi]-prevWall[gi])/1e6/profLogEvery)); prevWall[gi]=a.gWall[gi]; }
                pullRenderState(s);
                int seg = activeSegments(s.fil);
                System.out.printf("  [decay] step %d  %.0f steps/s  per-graph ms/step{ %s}  active=%d (Δ%+d)  conc=%.4f  nvidia=%s%n",
                        t+1, sps, pg.toString(), seg, seg-prevTotalSeg, s.grow.pool.conc(), nvidiaStat());
                prevClock = now; prevTotalSeg = seg;
            }
        }
        double measSecs = (System.nanoTime() - t0) / 1e9;
        int M = profSteps;
        if (planResetCount > 0)
            System.out.printf("  PLAN-RESET: %d resets, mean cost %.1f ms each (pull+close+rebuild; re-JIT amortizes into the next step) ⇒ %.4f ms/step amortized%n",
                    planResetCount, planResetCostNs/1e6/planResetCount, planResetCostNs/1e6/M);

        // ---- aggregate (per-step averages) ----
        long sumKern=0,sumDisp=0,sumXfer=0,sumWall=0,sumIn=0,sumOut=0;
        for (int gi=0; gi<nG; gi++){ sumKern+=a.gKern[gi]; sumDisp+=a.gDisp[gi]; sumXfer+=a.gXfer[gi]; sumWall+=a.gWall[gi]; sumIn+=a.gIn[gi]; sumOut+=a.gOut[gi]; }
        double NS=1e6;   // ns→ms divisor per the running total; per-step = /M
        double pStepWall = (sumWall + a.hostBkkp + a.hostPull) / NS / M;   // composed real step (excl. profiler overhead)
        double pKern = sumKern/NS/M, pDisp=sumDisp/NS/M, pXfer=sumXfer/NS/M, pGWall=sumWall/NS/M;
        double pHostB = a.hostBkkp/NS/M, pHostP = a.hostPull/NS/M;
        double pIdle = pGWall - (pKern + pDisp + pXfer);   // GPU-idle/launch gap inside the dispatch wall
        double rawSps = M / measSecs;

        System.out.println("\n  ---- per-graph (averaged over " + M + " measured steps) ----");
        System.out.println("  graph      exec-wall   devKernel  kDispatch  xfer-time   copyIn     copyOut");
        for (int gi=0; gi<nG; gi++)
            System.out.printf("  %-9s  %7.3f ms %7.3f ms %7.3f ms %7.3f ms %8.2f KB %8.2f KB%n",
                GNAME[gi], a.gWall[gi]/NS/M, a.gKern[gi]/NS/M, a.gDisp[gi]/NS/M, a.gXfer[gi]/NS/M, a.gIn[gi]/1024.0/M, a.gOut[gi]/1024.0/M);

        System.out.println("\n  ---- per-step BUDGET (ms and % of the composed step) ----");
        System.out.printf("  composed step wall (Σ exec-wall + host) ......... %7.3f ms  (raw measured loop %.3f ms/step ⇒ %.0f steps/s)%n", pStepWall, 1000.0/rawSps, rawSps);
        System.out.printf("  1. GPU kernel-compute (Σ devKernelTime) ......... %7.3f ms  (%4.1f%%)%n", pKern, 100*pKern/pStepWall);
        System.out.printf("  2. per-graph dispatch wall (Σ exec-wall) ........ %7.3f ms  (%4.1f%%)   [%d execute() calls/fire-step]%n", pGWall, 100*pGWall/pStepWall, nG);
        System.out.printf("       of which kernel-dispatch (launch) .......... %7.3f ms  (%4.1f%%)%n", pDisp, 100*pDisp/pStepWall);
        System.out.printf("       of which data-transfer time ................ %7.3f ms  (%4.1f%%)%n", pXfer, 100*pXfer/pStepWall);
        System.out.printf("       of which SYNC/GPU-idle gap ................. %7.3f ms  (%4.1f%%)%n", pIdle, 100*pIdle/pStepWall);
        System.out.printf("  3. host bookkeeping (counts + pool ledger) ...... %7.3f ms  (%4.1f%%)%n", pHostB, 100*pHostB/pStepWall);
        System.out.printf("  4. host pull (G0 pool-ledger transferToHost) .... %7.3f ms  (%4.1f%%)%n", pHostP, 100*pHostP/pStepWall);
        System.out.printf("  5. transfer per step: copyIn=%.2f KB  copyOut=%.2f KB  (Σ over the 5 graphs)%n", sumIn/1024.0/M, sumOut/1024.0/M);
        System.out.printf("  6. GPU utilization (sample): %s%n", nvidiaStat());

        if (noprof) {
            System.out.println("\n  ---- REGIME VERDICT ----");
            System.out.printf("  (profiler OFF — kernel/transfer not measured; per-graph wall + steps/s only, production-faithful)%n");
            StringBuilder pgw = new StringBuilder();
            for (int gi = 0; gi < nG; gi++) pgw.append(String.format("%s=%.2f ", GNAME[gi], a.gWall[gi]/NS/M));
            System.out.printf("  composed step %.3f ms ⇒ %.0f steps/s; per-graph wall ms/step: %s%n", pStepWall, M/measSecs, pgw.toString());
            return;
        }
        // ---- regime verdict ----
        double kernPct = 100*pKern/pStepWall, idlePct=100*(pIdle+pDisp)/pStepWall, hostPct=100*(pHostB+pHostP)/pStepWall, xferPct=100*pXfer/pStepWall;
        long bigXferKB = (sumIn+sumOut)/1024/M;
        String verdict;
        if (bigXferKB > 1024) verdict = "TRANSFER-BOUND (per-step copy ≫ pool-ledger — a hidden full-state copy; see §1 check)";
        else if (kernPct >= 50) verdict = "COMPUTE-BOUND (kernels are the majority of the step)";
        else if (idlePct >= hostPct && idlePct >= kernPct) verdict = "LAUNCH/OCCUPANCY-BOUND (dispatch+idle dominate; kernels a minority — starved GPU)";
        else verdict = "HOST-SERIAL-BOUND (host bookkeeping/pull dominates)";
        System.out.println("\n  ---- REGIME VERDICT ----");
        System.out.printf("  kernel=%.1f%%  launch+idle=%.1f%%  host=%.1f%%  xfer=%.1f%%  per-step copy=%d KB%n", kernPct, idlePct, hostPct, xferPct, bigXferKB);
        System.out.printf("  ⇒ %s%n", verdict);
        System.out.printf("  §1 residency check: per-step copyIn+copyOut = %d KB ⇒ %s (only the EVERY_EXECUTION counts re-upload + the 5 pool-ledger IntArrays should cross; a full-state copy would be MB)%n",
                bigXferKB, bigXferKB <= 1024 ? "CONSISTENT with §1 (no hidden full-state copy)" : "*** CONTRADICTS §1 — full-state copy detected ***");
    }

    /** Best-effort nvidia-smi {memMiB, tempC, smClockMHz, util%} one-liner; "?" if unavailable. */
    static String nvidiaStat() {
        try {
            Process p = new ProcessBuilder("nvidia-smi",
                    "--query-gpu=memory.used,temperature.gpu,clocks.sm,utilization.gpu",
                    "--format=csv,noheader,nounits").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            String[] v = out.split("\n")[0].split(",");
            return String.format("mem=%sMiB temp=%sC smClk=%sMHz util=%s%%", v[0].trim(),
                    v.length>1?v[1].trim():"?", v.length>2?v[2].trim():"?", v.length>3?v[3].trim():"?");
        } catch (Throwable e) { return "?"; }
    }

    /** Best-effort GPU memory.used (MiB) via nvidia-smi; "?" if unavailable. */
    static String vramMB() {
        try {
            Process p = new ProcessBuilder("nvidia-smi", "--query-gpu=memory.used", "--format=csv,noheader,nounits").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            String first = out.split("\\s+")[0];
            return first + "MiB";
        } catch (Throwable e) { return "?"; }
    }

    // ====================================================================== viewer
    static final String[] STATE_NAME = { "NONE", "ATP", "ADPPi", "ADP" };
    static void runViz(Scene s, double dt) {
        new java.io.File(vizDir).mkdirs();
        int M = STEPS, every = Math.max(1, M / 400), frames = 0;
        filRms0 = filRms(s.fil);
        long t0 = System.nanoTime();
        for (int t = 0; t <= M; t++) {
            cpuStep(s, t);
            if (t % every == 0) writeFrame(vizDir, frames++, t, t * dt, s);
            if (anyNaN(s)) { System.out.println("  *** NON-FINITE at step " + t + " ***"); break; }
        }
        double[] ext = netExtent(s);
        System.out.printf("viewer: wrote %d frames to %s in %.0fs; final node-RMS=%.4f µm, fil-RMS=%.4f, active=%d, links=%d, conservation=%s%n",
                frames, vizDir, (System.nanoTime() - t0) / 1e9, ext[0], filRms(s.fil), activeSegments(s.fil),
                s.xl == null ? 0 : activeLinks(s.xl), conservationCheck(s) ? "EXACT" : "FAIL");
    }
    static void writeFrame(String dir, int frame, int step, double t, Scene s) {
        FilamentStore f = s.fil; AgingStore ag = s.aging; RigidRodBody nb = s.node.node;
        StringBuilder sb = new StringBuilder(8192);
        sb.append(String.format(java.util.Locale.US, "{\"frame\":%d,\"t\":%.6g,\"bounds\":{\"xDim\":%.2f,\"yDim\":%.2f,\"zDim\":%.2f}",
                frame, t, BOX_XY, BOX_XY, BOX_Z));
        // segments (ADP gradient + barbed "+"); free/dead skipped ⇒ vanish (severing visible)
        sb.append(",\"segments\":[");
        boolean first = true;
        for (int seg = 0; seg < f.n; seg++) {
            if (f.filState.get(seg) < 0) continue;
            if (!first) sb.append(','); first = false;
            double notADP = ag.fATP(seg) + ag.fADPPi(seg);
            boolean barbed = f.end2NbrSlot.get(seg) < 0;
            sb.append(String.format(java.util.Locale.US, "{\"id\":%d,\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.5g,\"notADPRatio\":%.4f,\"isBarbedEnd\":%b,\"cofilinCount\":%d}",
                seg, f.end1.get(seg), f.end1.get(f.n+seg), f.end1.get(2*f.n+seg), f.end2.get(seg), f.end2.get(f.n+seg), f.end2.get(2*f.n+seg),
                Constants.radius, notADP, barbed, (s.sever.fCof(seg) > (float) s.sever.cofilinRatio ? 1 : 0)));
        }
        // nodes (grey spheres) + node-shell myosins + free-minifilament myosins (crosslinks rendered as thin links)
        sb.append("],\"nodes\":[");
        for (int k = 0; k < nb.n; k++) {
            if (k > 0) sb.append(',');
            sb.append(String.format(java.util.Locale.US, "{\"id\":%d,\"center\":[%.5g,%.5g,%.5g],\"r\":%.4g}",
                900000 + k, nb.coord.get(k), nb.coord.get(nb.n + k), nb.coord.get(2*nb.n + k), NodeStore.NODE_RADIUS));
        }
        sb.append("],\"myosins\":[");
        appendMyosins(sb, s.mot, true);
        appendMyosins(sb, s.mot2, false);
        sb.append("]");
        // free-minifilament BACKBONES — the viewer's dedicated minifilament channel (white cylinder end1→end2);
        // the heads above are the dimer myosins, this is the rigid backbone that makes the minifilament visible.
        sb.append(",\"minifilaments\":[");
        RigidRodBody mbb = s.mini.backbone;
        for (int k = 0; k < mbb.n; k++) {
            if (k > 0) sb.append(',');
            sb.append(String.format(java.util.Locale.US, "{\"id\":%d,\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g}",
                700000 + k, mbb.end1.get(k), mbb.end1.get(mbb.n + k), mbb.end1.get(2*mbb.n + k),
                mbb.end2.get(k), mbb.end2.get(mbb.n + k), mbb.end2.get(2*mbb.n + k), MiniFilamentStore.BACKBONE_R));
        }
        sb.append("]");
        // crosslinks as a dedicated channel (the viewer ignores unknown keys; informational)
        if (s.xl != null) {
            sb.append(",\"crosslinks\":[");
            boolean fx = true;
            for (int k = 0; k < s.xl.nLinks; k++) {
                if (s.xl.linkState.get(k) < 0) continue;
                int a = s.xl.linkFilA.get(k), bk = s.xl.linkFilB.get(k);
                if (a < 0 || bk < 0 || f.filState.get(a) < 0 || f.filState.get(bk) < 0) continue;
                if (!fx) sb.append(','); fx = false;
                sb.append(String.format(java.util.Locale.US, "{\"a\":[%.5g,%.5g,%.5g],\"b\":[%.5g,%.5g,%.5g]}",
                        f.coordX(a), f.coordY(a), f.coordZ(a), f.coordX(bk), f.coordY(bk), f.coordZ(bk)));
            }
            sb.append("]");
        }
        double[] ext = netExtent(s);
        sb.append(String.format(java.util.Locale.US, ",\"stats\":{\"step\":%d,\"simTime\":%.5g,\"nodeRms_um\":%.5g,\"filRms_um\":%.5g,\"nodeBound\":%d,\"miniBound\":%d,\"crosslinks\":%d,\"activeFil\":%d,\"conc_uM\":%.5g}",
                step, t, ext[0], filRms(f), boundTotal(s.mot), boundTotal(s.mot2), s.xl == null ? 0 : activeLinks(s.xl), activeSegments(f), s.grow.pool.conc()));
        sb.append("}");
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(dir, String.format(java.util.Locale.US, "frame_%06d.json", frame)), sb.toString()); }
        catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }
    static void appendMyosins(StringBuilder sb, MotorStore mot, boolean firstSet) {
        RigidRodBody b = mot.body;
        for (int m = 0; m < mot.nMotors; m++) {
            if (sb.charAt(sb.length() - 1) != '[') sb.append(',');
            int rod = 3*m, lever = 3*m+1, head = 3*m+2; String state = STATE_NAME[mot.nucleotideState.get(m)];
            sb.append(String.format(java.util.Locale.US,
                "{\"id\":%d,\"rod\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g,\"invisible\":false},"
                + "\"lever\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g},"
                + "\"motor\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g,\"state\":\"%s\"}}",
                (firstSet ? 0 : 500000) + m,
                b.end1X(rod),b.end1Y(rod),b.end1Z(rod), b.end2X(rod),b.end2Y(rod),b.end2Z(rod), MotorStore.ROD_R,
                b.end1X(lever),b.end1Y(lever),b.end1Z(lever), b.end2X(lever),b.end2Y(lever),b.end2Z(lever), MotorStore.LEVER_R,
                b.end1X(head),b.end1Y(head),b.end1Z(head), b.end2X(head),b.end2Y(head),b.end2Z(head), MotorStore.HEAD_R, state));
        }
    }

    static int pad(int n) { return ((n + B - 1) / B) * B; }
    static void addW(String n, int g) { WorkerGrid w = new WorkerGrid1D(Math.max(B, g)); w.setLocalWork(B, 1, 1); sched.addWorkerGrid(n, w); }
    static void addS(String n) { WorkerGrid w = new WorkerGrid1D(1); w.setLocalWork(1, 1, 1); sched.addWorkerGrid(n, w); }
}
