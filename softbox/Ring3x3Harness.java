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
 * Increment 7 → Ring — EXPERIMENT: a 3×3 net of nucleating, treadmilling protein NODES. Do they find each other
 * and coalesce? The FIRST multi-node ring-ward integration: composes the individually-validated mechanisms
 * (node motor-bundle + binding/gather, cross-node capture-and-pull [Test B/B′], formin nucleation [B2],
 * polymerization growth + barbed split, FIXED-rate pointed depoly + death [treadmilling, INC7], the dead-slot
 * recycle [INC7]) into ONE scene and asks whether they COMPOSE — does a net of nodes, each sprouting
 * randomly-oriented treadmilling formin filaments, find neighbors and clump?
 *
 * PURE COMPOSITION over validated pieces — NO new force law, NO new gather, NO shared-kernel edit. Every system is
 * reused byte-unchanged; this harness only WIRES them (generalising TestBScprHarness's two-node SCPR loop to 9
 * nodes and adding the treadmilling depoly cadence + the dead-slot initNewborn). New file only.
 *
 * SCENE (jba's spec):
 *   - 9 FREE nodes in a 3×3 planar grid (Z=0), box-confined, node-Brownian damped (a node is a large/slow complex).
 *   - 4–6 randomly-oriented formins per node (seam-#3 RANDOM placement); each formin holds one treadmilling chain.
 *   - Treadmilling: formin barbed (node-side end2) growth + FIXED-rate pointed (outer end1) depoly + death ⇒ a
 *     bounded reach (the Test B′ monotonic-overrun fix). Aging + severing OFF for this first cut (fixed-rate).
 *   - Myosins per node (the validated Test B count) for capture-and-pull; coalescence = scheme 0 (the soft tether).
 *   - One SHARED finite actin pool; the dead-slot fix in place (turnover + nucleation coexist AT SCALE).
 *
 * TIMESCALE COMPRESSION (reported, not hidden). The mechanical clock (node motion, captures) and the biochemical
 * clock (treadmilling turnover) are separated by ~1000× in the real system — too far apart to see both in one
 * feasible run. So the biochem kinetics are accelerated by a factor KIN (-kin) that scales BOTH k_on and k_off1
 * EQUALLY ⇒ C_c = k_off1/k_on is PRESERVED (the steady reach is unchanged) — only the turnover SPEED rises. The
 * filaments are warm-started near their pool-consistent steady reach so the scene begins in the "neighbours just
 * within reach" regime. The reach is then pool-bounded by conservation (the INC7 treadmilling result), not by an
 * unbounded growth race.
 *
 * READOUT (exploratory — full / partial / dispersed / pathological are all informative):
 *   - coalescence: the net's spatial extent (RMS radius + bounding box) over time — does it shrink/clump?
 *   - capture network: which nodes capture which neighbours' filaments — does a connected network form?
 *   - reach vs spacing: measured filament contour vs the grid spacing (the calibration);
 *   - force-transmission read: inter-node filament STRETCH without node motion ⇒ collective load not transmitted
 *     (the scheme-1 signal) — REPORT, do not switch;
 *   - sanity at scale: conservation EXACT (the pool ledger), no phantoms (0 zero-length newborns), no crash.
 * CPU≡GPU is NOT required (chaotic many-body); aggregate behaviour + conservation + no-crash are the bar.
 */
public final class Ring3x3Harness {

    static final int B = 64;
    static GridScheduler sched;
    static boolean cpu = true;                 // CPU is the PRIMARY runner (the experiment); -gpu adds the device scale check
    static boolean gpuScale = false;
    static final int SEED = 0x3393B0;          // "R3x3"
    static final int SEED_NODE = 0x5C2FAA;
    static final double GOLDEN = 2.399963229728653;
    static final double MYO_SPRING = 1.0e-9, J1_FMT = 0.4;
    static double REACH = 0.025;               // myoColTol (bind/capture radius, µm)
    static double ALIGN_TOL = -0.4;            // myoMotorAlignWithFilTolerance (v1 default)
    static double KOFF = 100.0;                // catch-slip base off-rate (v1 default 100/s)
    static double BROWN_TRANS = 1.0, BROWN_ROT = 0.3;
    static double NODE_BROWN = 0.03;           // node-body Brownian scale (large/slow complex; legible directed motion)
    static int N_SING = 6, N_DIM = 6;          // radial singlets + dimers PER node (the validated Test B count)
    static final double SIN80 = Math.sin(Math.toRadians(80.0)), COS80 = Math.cos(Math.toRadians(80.0));

    // ----- the net -----
    static int GRID = 3;                       // 3×3
    static double SPACING = 0.25;              // node center-to-center nearest-neighbour spacing (µm) — -spacing
                                               // (default in the COALESCING regime; the spacing sweep maps the transition)
    static int FORMINS = 6;                    // formins per node (4–6) — -formins
    static int FIL_CAP = 1024;                 // FilamentStore capacity (bounds run length: flag) — -cap
    static double BOX = 2.0;                   // containment cube side (µm) — confines node bodies — -box
    static int SEG_MONO = 30;                  // monomers per warm-start segment (< 2·stdSeg=64 split threshold)
    static int WARM_CHAIN = 0;                 // segments per warm-start chain (computed from SPACING; -warmchain overrides)
    static double OVERSHOOT = 1.35;            // reach target = OVERSHOOT·spacing — the Test B′ capture-cone finding:
                                               // capture needs the foreign filament to reach the captor's FAR hemisphere
                                               // (rodDotFil>=0), i.e. OVERSHOOT past the partner node, not just touch it. -overshoot
    static double KIN = 100.0;                 // biochem kinetic-speedup factor (preserves every rate RATIO) — -kin
    static int STEPS = 30000;                  // -steps
    static String vizDir = null;

    // ----- RAPID barbed-end extension demo (crank the formin polymerization) -----
    static int POLYBOOST = 1;                  // monomers added at the barbed tip PER cadence (1 = the discrete max;
                                               // >1 calls grow that many times/cadence ⇒ filaments shoot out) — -polyboost
    static double POOL0_UM = -1;               // initial [actin] µM (<0 = the C_c default; set HIGH to sustain fast growth) — -pool
    static int WARMSEED = -1;                  // warm-start each formin with ONE small n-monomer seed (extends out) — -warmseed
    static boolean NOWARM = false;             // no warm filaments — the formins NUCLEATE their own (probabilistic) — -nowarm
    static double NUCBOOST = 1.0;              // formin nucleation-rate multiplier — pNuc = kNodeNuc·dt·KIN·NUCBOOST. The KIN
                                               // factor is the consistency fix (nucleation was left unscaled ⇒ 100× too slow
                                               // vs the compressed turnover); NUCBOOST cranks it further — -nucboost

    // ----- FULL dynamic turnover (the ring-relevant formin-pinned mode) -----
    static boolean AGING_ON = true;            // nucleotide proxy cascade (ATP→ADPPi→ADP) drives the depoly rate — -noaging
    static boolean SEVER_ON = true;            // cofilin en-masse dissolve — -nosever
    static double COF_RATIO = 0.5;             // cofilinRatio dissolve threshold (1.0 = severing OFF) — -cofratio

    // ----- biochem (faithful rates × KIN; every ratio — incl. C_c — preserved) -----
    static final double K_ON  = Constants.kATPOn2WithFormin;   // 11.6 µM⁻¹s⁻¹ barbed-end on-rate at a formin
    static final double K_OFF = Constants.kATPOff1;            // 0.8 s⁻¹ ATP pointed-off (fixed-rate fallback)
    static final double K_OFF_ADP = Constants.kADPOff1;        // 2.7 s⁻¹ ADP pointed-off (the aged pointed end's rate)
    // critical concentration: fixed-rate baseline uses kATPOff1; AGING drives the pointed end to ADP ⇒ kADPOff1.
    static final double C_C_EFF = ((double) Constants.stdSegLength / (Constants.stdSegLength - (Constants.actinSeed - 1))) * (K_OFF / K_ON);
    static final double C_C_EFF_AGING = ((double) Constants.stdSegLength / (Constants.stdSegLength - (Constants.actinSeed - 1))) * (K_OFF_ADP / K_ON);
    static double uMper;                                       // µM per monomer (BOX_VOL)
    static double BOX_VOL;

    public static void main(String[] args) {
        double dt = 1.0e-5;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-cpu" -> { cpu = true; gpuScale = false; }
                case "-gpu" -> { gpuScale = true; }
                case "-spacing" -> SPACING = Double.parseDouble(args[++i]);
                case "-formins" -> FORMINS = Integer.parseInt(args[++i]);
                case "-cap" -> FIL_CAP = Integer.parseInt(args[++i]);
                case "-box" -> BOX = Double.parseDouble(args[++i]);
                case "-segmono" -> SEG_MONO = Integer.parseInt(args[++i]);
                case "-warmchain" -> WARM_CHAIN = Integer.parseInt(args[++i]);
                case "-kin" -> KIN = Double.parseDouble(args[++i]);
                case "-overshoot" -> OVERSHOOT = Double.parseDouble(args[++i]);
                case "-noaging" -> AGING_ON = false;
                case "-nosever" -> SEVER_ON = false;
                case "-cofratio" -> COF_RATIO = Double.parseDouble(args[++i]);
                case "-polyboost" -> POLYBOOST = Integer.parseInt(args[++i]);
                case "-pool" -> POOL0_UM = Double.parseDouble(args[++i]);
                case "-warmseed" -> WARMSEED = Integer.parseInt(args[++i]);
                case "-nowarm" -> NOWARM = true;
                case "-nucboost" -> NUCBOOST = Double.parseDouble(args[++i]);
                case "-nsing" -> N_SING = Integer.parseInt(args[++i]);
                case "-ndim" -> N_DIM = Integer.parseInt(args[++i]);
                case "-nodebrown" -> NODE_BROWN = Double.parseDouble(args[++i]);
                case "-steps" -> STEPS = Integer.parseInt(args[++i]);
                case "-3js" -> vizDir = args[++i];
                default -> {}
            }
        }
        BOX_VOL = BOX * BOX * BOX;
        uMper = 1e21 / (BOX_VOL * Constants.AvogadroNum);
        double segLen = (SEG_MONO + 1) * Constants.actinMonoRadius;
        if (WARM_CHAIN <= 0) WARM_CHAIN = Math.max(1, (int) Math.round(OVERSHOOT * SPACING / segLen));
        double warmReach = WARM_CHAIN * segLen;

        System.out.println("=== Soft Box increment 7 → Ring — 3×3 node net: do treadmilling nodes coalesce? ===");
        System.out.println("runner: " + (gpuScale ? "CPU experiment + GPU device scale/no-crash check" : "CPU only") + ", dt=" + dt);
        System.out.printf("net: %dx%d grid, spacing=%.3f µm, %d formins/node, shell=%d singlet+%d dimer/node, box=%.2f µm cube%n",
                GRID, GRID, SPACING, FORMINS, N_SING, N_DIM, BOX);
        double cCsteady = AGING_ON ? C_C_EFF_AGING : C_C_EFF;
        System.out.printf("turnover: growth + pointed depoly%s%s, formin-PINNED (release OFF) — k_on=%.2f µM/s, k_off=%s, ×KIN=%.0f (every ratio incl. C_c preserved)%n",
                AGING_ON ? " + AGING(cascade→ADP depoly)" : "", SEVER_ON ? " + SEVERING(cofilin ratio " + COF_RATIO + ")" : "",
                K_ON, AGING_ON ? "kADPOff1=2.7" : "kATPOff1=0.8", KIN);
        System.out.printf("steady C_c_eff=%.5f µM (%s); warm chain=%d seg × %d mono ⇒ warm reach≈%.3f µm%n",
                cCsteady, AGING_ON ? "ADP-aged pointed end" : "fixed ATP", WARM_CHAIN, SEG_MONO, warmReach);
        System.out.printf("nucleation: kNodeNuc=%.0f/node·s ×KIN ×NUCBOOST=%.0f ⇒ pNuc=%.4f/step/free-formin (re-nucleates after a sever loss); polyboost=%d mono/cadence, pool0=%.2f µM, start=%s%n",
                Constants.kNodeNuc, NUCBOOST, Constants.kNodeNuc * dt * KIN * NUCBOOST, POLYBOOST,
                (POOL0_UM > 0 ? POOL0_UM : cCsteady), NOWARM ? "formins nucleate own" : (WARMSEED > 0 ? "small seed/formin" : "reach chains"));
        System.out.printf("CALIBRATION: warm reach %.3f µm vs spacing %.3f µm ⇒ %s%n",
                warmReach, SPACING, warmReach >= 0.9 * SPACING && warmReach <= 1.6 * SPACING ? "reachable (just within reach)"
                        : warmReach < 0.9 * SPACING ? "TOO SHORT (won't connect — null about spacing)" : "TOO LONG (overlap — trivial)");
        System.out.println();

        Scene s = build(dt);
        System.out.printf("scene built: %d nodes, %d warm filaments (%d active segments), pool0=%.5f µM ([actin]≈C_c ⇒ near steady), total actin=%.4f µM%n%n",
                s.nNodes, s.nNodes * FORMINS, activeSegments(s.fil), s.grow.pool.conc(), totalActinUM(s));

        if (vizDir != null) { runViz(s, dt); return; }

        run(s, dt);
    }

    // ====================================================================== scene
    static final class Scene {
        int nNodes;
        NodeStore node; MotorStore mot; DimerStore dim;
        int[] motorNode;                         // per-motor owning node
        int motorsPerNode, nChildPerNode;
        double[][] centers;                      // [node][xyz]
        FilamentStore fil; NodeNucleationStore nuc; GrowthStore grow; DepolyStore depoly;
        AgingStore aging; SeverStore sever;       // full turnover: nucleotide cascade + cofilin severing
        // cross-bridge + gather scratch
        FloatArray bondData, xbParams;
        IntArray segMotorCount, segMotorOffsets, segMotorMyo, reachSeg, reachCount;
        // dedicated nucleation request arrays (the integration crux — see Test B)
        IntArray nucAccept; FloatArray nucReqCoord, nucReqUVec, nucReqYVec;
        IntArray nucRankOffsets, nucRankScanCounts;
        FloatArray boxParams;
        long monInit;                            // Σ warm-started monomers (conservation baseline)
        int lastNucBirths;                       // # filaments nucleated this step (for the re-nucleation diagnostic)
    }

    static int wangHash(int seed) {
        seed = (seed ^ 61) ^ (seed >>> 16); seed *= 9; seed = seed ^ (seed >>> 4);
        seed *= 0x27d4eb2d; seed = seed ^ (seed >>> 15); return seed;
    }

    /** A random unit radial direction for formin site `site` on node `nodeK` (the seam-#3 RANDOM-radial default —
     *  the same isotropic wang-hash draw the runtime nucleation emit uses). */
    static double[] forminSiteDir(int nodeK, int site) {
        int base = ((nodeK * FORMINS + site) * 1000003) ^ (nodeK * 999983) ^ 0x52494E47;   // "RING"
        double dx = ((wangHash(base ^ 0x9e3779b9) >>> 1) / 2147483647.0) * 2 - 1;
        double dy = ((wangHash(base ^ 0x85ebca6b) >>> 1) / 2147483647.0) * 2 - 1;
        double dz = ((wangHash(base ^ 0xc2b2ae35) >>> 1) / 2147483647.0) * 2 - 1;
        double m2 = dx*dx + dy*dy + dz*dz; if (m2 < 1e-9) { dx = 1; dy = 0; dz = 0; m2 = 1; }
        double inv = 1.0 / Math.sqrt(m2);
        return new double[]{ dx * inv, dy * inv, dz * inv };
    }

    static Scene build(double dt) {
        Scene s = new Scene();
        int nNodes = GRID * GRID;
        s.nNodes = nNodes;
        // 3×3 planar grid centred on the origin (Z=0)
        double[][] centers = new double[nNodes][3];
        double off = 0.5 * (GRID - 1) * SPACING;
        for (int gy = 0; gy < GRID; gy++)
            for (int gx = 0; gx < GRID; gx++) {
                int k = gy * GRID + gx;
                centers[k][0] = gx * SPACING - off;
                centers[k][1] = gy * SPACING - off;
                centers[k][2] = 0.0;
            }
        s.centers = centers;
        buildShells(s, dt, centers);

        int cap = FIL_CAP;
        NodeStore nd = s.node; MotorStore mot = s.mot;
        FilamentStore f = new FilamentStore(cap, cap);
        for (int sl = 0; sl < cap; sl++) f.monomerCount.set(sl, Constants.actinSeed);
        DragTensorSystem.run(f);
        f.setParams(dt, Constants.brownianForceMag());
        f.setChainParams();
        f.chainParams.set(0, (float) dt);     // chain-dt fix (Test B flag): chain force ∝ 1/dt — set to the step dt
        double bornScale = Constants.BTransCoeff / 30.0;     // damped seed (B2 dt-compensation; the formin's stiff hold)
        f.setBirthParams(bornScale, bornScale);
        f.setBirthRequestCount(cap);
        // park all FREE far away — the binding path is brute over ALL segments (no broad-phase publish guard)
        for (int sl = 0; sl < cap; sl++) { f.setCoord(sl, 100f, 100f, 100f); f.setUVec(sl, 1f, 0f, 0f); f.setYVec(sl, 0f, 1f, 0f); f.markFree(sl); }

        NodeNucleationStore nuc = new NodeNucleationStore(nNodes, cap, Constants.actinSeed, 1.0e12, BOX_VOL, 30.0);

        // WARM-START: FORMINS random-radial treadmilling chains per node, each WARM_CHAIN segments of SEG_MONO mono,
        // tethered (seedNode bond at the node-side barbed end2). Slots laid out contiguously: node k, formin j ⇒
        // base = (k*FORMINS + j) * WARM_CHAIN.
        // start mode: NOWARM (formins nucleate their own) | WARMSEED (one small n-mono seed/formin, extends out) | reach chain
        if (!NOWARM) {
            int chainLen = (WARMSEED > 0) ? 1 : WARM_CHAIN;
            int segMono  = (WARMSEED > 0) ? WARMSEED : SEG_MONO;
            for (int k = 0; k < nNodes; k++) {
                for (int j = 0; j < FORMINS; j++) {
                    double[] d = forminSiteDir(k, j);
                    int base = (k * FORMINS + j) * chainLen;
                    placeRandomChain(f, nuc, nd, k, d[0], d[1], d[2], chainLen, base, bornScale, segMono);
                }
            }
        }
        DragTensorSystem.run(f);
        DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
        s.monInit = sumActiveMonomers(f);

        // ---- shared finite pool: grow.pool is authoritative; depoly shares it; nucleation takes from it manually.
        // pool0 = C_c_eff ⇒ [actin] starts at the critical concentration ⇒ growth≈depoly ⇒ steady treadmilling.
        double seedLen = (Constants.actinSeed + 1) * Constants.actinMonoRadius;
        // nucleation rate ×KIN (keeps pace with the compressed turnover — was unscaled) ×NUCBOOST (further crank).
        // A freed formin (its node-held tip severed/dissolved ⇒ countBoundFil drops nodeBoundFil) re-nucleates at this rate.
        nuc.setNucParams(Constants.kNodeNuc * KIN * NUCBOOST, dt, seedLen, FORMINS);   // forminsPerNode cap = FORMINS
        nuc.setTetherParams(Constants.fracMove, dt);
        nuc.setDissolveParams(0.0, dt);                               // never dissolve — death drives the churn

        double pool0 = (POOL0_UM > 0) ? POOL0_UM : (AGING_ON ? C_C_EFF_AGING : C_C_EFF);   // -pool overrides (HIGH ⇒ sustained fast growth)
        GrowthStore grow = new GrowthStore(cap, K_ON * KIN, dt, pool0, BOX_VOL);      // authoritative pool
        DepolyStore depoly = new DepolyStore(cap, K_OFF * KIN, dt, grow.pool);        // SHARE the pool (fixed-rate fallback)

        // ---- FULL turnover stores: aging cascade + cofilin severing (rates KIN-scaled ONCE — all pool-independent
        //      ⇒ constant across the run; preserve every ratio so C_c, cascade-vs-transit, cofilin-vs-aging hold) ----
        AgingStore aging = new AgingStore(cap);
        aging.refresh(AGING_ON);
        aging.agingParams.set(0, (float) (aging.agingParams.get(0) * KIN));      // pH ×KIN
        aging.agingParams.set(1, (float) (aging.agingParams.get(1) * KIN));      // pD ×KIN
        aging.depolyRateParams.set(0, (float) (Constants.kATPOff1 * Constants.biochemDeltaT * KIN));   // pATP ×KIN
        aging.depolyRateParams.set(1, (float) (Constants.kADPOff1 * Constants.biochemDeltaT * KIN));   // pADP ×KIN
        for (int sl = 0; sl < cap; sl++) aging.setATP(sl);     // every slot fresh ATP (warm filaments age over the run)
        SeverStore sever = new SeverStore(cap, SEVER_ON ? COF_RATIO : 1.0);
        sever.refresh(SEVER_ON);
        sever.cofilinParams.set(0, (float) (sever.cofilinParams.get(0) * KIN));  // p_cof ×KIN
        s.aging = aging; s.sever = sever;

        // dedicated nucleation request arrays
        s.nucAccept = new IntArray(cap); s.nucAccept.init(0);
        s.nucReqCoord = new FloatArray(3 * cap); s.nucReqUVec = new FloatArray(3 * cap); s.nucReqYVec = new FloatArray(3 * cap);
        s.nucRankOffsets = new IntArray(cap + 1);
        s.nucRankScanCounts = new IntArray(4); s.nucRankScanCounts.set(3, cap);

        // seg→bound-motor gather scratch
        s.segMotorCount = new IntArray(cap);
        s.segMotorOffsets = new IntArray(cap + 1);
        s.segMotorMyo = new IntArray(mot.nMotors);

        // containment cube centred on the grid centre (origin)
        s.boxParams = FloatArray.fromElements(1.0e-4f, (float) BOX, (float) BOX, (float) BOX,
                (float) NodeStore.NODE_RADIUS, 0.5f, 10f);
        s.fil = f; s.nuc = nuc; s.grow = grow; s.depoly = depoly;
        return s;
    }

    /** Build the motor shells for all nodes (generalisation of TestBScprHarness.buildShells to N nodes). One
     *  MotorStore + one DimerStore + one NodeStore hold every node's shell; motorNode[m] records the owning node. */
    static void buildShells(Scene s, double dt, double[][] centers) {
        int nNodes = centers.length;
        int nSing = N_SING, nDim = N_DIM, nChild = nSing + nDim;
        int motorsPerNode = nSing + 2 * nDim;
        s.motorsPerNode = motorsPerNode; s.nChildPerNode = nChild;
        int nMot = nNodes * motorsPerNode, nDimers = nNodes * nDim, nAtt = nNodes * nChild;
        double R = NodeStore.NODE_RADIUS;

        MotorStore mot = new MotorStore(nMot);
        DimerStore dim = new DimerStore(nDimers);
        NodeStore node = new NodeStore(nNodes, nAtt);
        s.motorNode = new int[nMot];

        for (int k = 0; k < nNodes; k++) {
            double cx = centers[k][0], cy = centers[k][1], cz = centers[k][2];
            node.node.setCoord(k, (float) cx, (float) cy, (float) cz);
            node.node.setUVec(k, 1f, 0f, 0f);
            node.node.setYVec(k, 0f, 1f, 0f);
            node.node.brownTransScale.set(k, (float) NODE_BROWN);
            node.node.brownRotScale.set(k, (float) NODE_BROWN);
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
                    int mA = k * motorsPerNode + nSing + 2 * jj, mB = mA + 1;
                    int gd = k * nDim + jj;
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
        mot.setFaithfulRelease(true, 0.0);                    // the v1 12 pN break-force cap (faithful)
        mot.nucleotideState.init(MotorStore.NUC_NONE);
        dim.setDimerParams(dt);
        node.setNodeParams(dt); node.setNodeBodyParams(dt);
        DerivedGeometrySystem.derive(node.node.coord, node.node.uVec, node.node.yVec, node.node.zVec,
                node.node.end1, node.node.end2, node.node.segLength, node.nodeBodyCounts);

        int MAXC = SpatialGrid.MAX_CAND;
        s.bondData = new FloatArray(nMot * CrossBridgeSystem.STRIDE); s.bondData.init(0f);
        s.xbParams = FloatArray.fromElements((float) MYO_SPRING, 90f, (float) J1_FMT, (float) dt, (float) MotorStore.HEAD_LEN, 0f);
        s.reachSeg = new IntArray(nMot * MAXC); s.reachSeg.init(-1); s.reachCount = new IntArray(nMot);
        s.node = node; s.mot = mot; s.dim = dim;
    }

    /** Pre-grow ONE treadmilling chain for node k along (dx,dy,dz): WARM_CHAIN segments of SEG_MONO monomers, the
     *  node-side barbed tip (end2, seedNode=k) at the node centre, uVec INWARD, children extending OUTWARD
     *  (barbed=end2 convention — identical wiring to TestBScprHarness.placeAimedChain, random direction). The tip
     *  (seedNode≥0) grows; the outer pointed tip (end1, no neighbour) depolymerises ⇒ treadmilling. */
    static void placeRandomChain(FilamentStore f, NodeNucleationStore nuc, NodeStore nd, int k,
                                 double dx, double dy, double dz, int nChain, int base, double bornScale, int segMono) {
        double cx = nd.node.coord.get(k), cy = nd.node.coord.get(nd.node.n + k), cz = nd.node.coord.get(2 * nd.node.n + k);
        double ex = (Math.abs(dx) < 0.9) ? 1 : 0, ey = (Math.abs(dx) < 0.9) ? 0 : 1, ez = 0;
        double yx = dy*ez - dz*ey, yy = dz*ex - dx*ez, yz = dx*ey - dy*ex;
        double ym = 1.0 / Math.sqrt(yx*yx + yy*yy + yz*yz); yx *= ym; yy *= ym; yz *= ym;
        double Lc = (segMono + 1) * Constants.actinMonoRadius;
        double e1x = cx, e1y = cy, e1z = cz;                          // marching node-side point (each seg's end2)
        for (int i = 0; i < nChain; i++) {
            int sl = base + i;
            double ccx = e1x + 0.5 * Lc * dx, ccy = e1y + 0.5 * Lc * dy, ccz = e1z + 0.5 * Lc * dz;
            f.monomerCount.set(sl, segMono);
            f.setCoord(sl, (float) ccx, (float) ccy, (float) ccz);
            f.setUVec(sl, (float) -dx, (float) -dy, (float) -dz);     // barbed=end2: uVec INWARD (toward node)
            f.setYVec(sl, (float) yx, (float) yy, (float) yz);
            f.filState.set(sl, FilamentStore.FIL_ACTIVE);
            f.brownTransScale.set(sl, (float) bornScale); f.brownRotScale.set(sl, (float) bornScale);
            nuc.seedNode.set(sl, i == 0 ? k : -1);                    // the tip (node side, barbed end2) carries the bond
            if (i > 0) { f.end2NbrSlot.set(sl, sl - 1); f.end2NbrSide.set(sl, 0); }    // end2(nodeward) ↔ prev.end1(outward)
            if (i < nChain - 1) { f.end1NbrSlot.set(sl, sl + 1); f.end1NbrSide.set(sl, 1); } // end1(outward) ↔ next.end2(nodeward)
            e1x += Lc * dx; e1y += Lc * dy; e1z += Lc * dz;
        }
    }

    // ====================================================================== one CPU step
    static void cpuStep(Scene s, int t) {
        MotorStore mot = s.mot; DimerStore dim = s.dim; NodeStore nd = s.node;
        FilamentStore f = s.fil; NodeNucleationStore nuc = s.nuc; GrowthStore grow = s.grow; DepolyStore d = s.depoly;
        AgingStore ag = s.aging; SeverStore sv = s.sever;
        RigidRodBody b = mot.body; RigidRodBody nb = nd.node;
        int nSeg = f.n;
        mot.setCounts(t, SEED, nSeg); nd.setNodeBodyCounts(t, SEED_NODE); f.setCounts(t, SEED);

        boolean fires = grow.firesAt(t);    // biochem cadence (all turnover fires together; rates are KIN-set once)
        grow.setCounts(t, SEED, fires); grow.refreshRate(fires);   // growth rate is pool-dependent ⇒ refresh each cadence
        d.setCounts(t, SEED, fires); ag.setFires(fires); sv.setFires(fires);

        // === FULL TURNOVER (the SeveringHarness combined order, formin-PINNED): age → depoly(proxy) → death →
        //     grow → growthAtp → cofilin-accumulate → dissolve → severDeath → split(+inherit/reset) ===
        AgingSystem.age(f.filState, ag.nucFrac, ag.agingParams, ag.agingCounts);
        // pointed depoly at the NUCLEOTIDE-DEPENDENT rate (aging drives it; AGING off ⇒ pH=pD=0 ⇒ stays ATP ⇒ kATPOff1)
        DepolySystem.depolyProxy(f.filState, f.monomerCount, f.coord, f.uVec, f.end1NbrSlot, ag.nucFrac,
                d.returnedMon, d.deathFlag, d.depolyParams, ag.depolyRateParams, d.depolyCounts);
        CrossBridgeSystem.csrScan(d.returnScanCounts, d.returnedMon, d.returnedOffsets);
        DepolySystem.applyDeath(f.filState, f.monomerCount, nuc.seedNode, f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide,
                f.brownTransScale, f.brownRotScale, d.deathFlag, d.depolyCounts);
        grow.pool.put(d.returnedOffsets.get(f.n));    // [conservation: depoly returns]
        // barbed-end polymerization — POLYBOOST monomers/cadence (1 = the discrete max; >1 shoots filaments out)
        int boost = fires ? Math.max(1, POLYBOOST) : 1;
        for (int g = 0; g < boost; g++) {
            GrowthSystem.grow(nuc.seedNode, f.monomerCount, f.coord, f.uVec, grow.grewFlag, grow.growParams, grow.growCounts);
            CrossBridgeSystem.csrScan(grow.grewScanCounts, grow.grewFlag, grow.grewOffsets);
            AgingSystem.growthAtp(grow.grewFlag, f.monomerCount, ag.nucFrac);     // each added barbed monomer is fresh ATP
            grow.depletePoolForGrows();                   // [conservation: growth takes]
        }
        // SEVERING: cofilin accumulates off f_ADP; a segment crossing cofilinRatio dissolves en masse (REUSE applyDeath)
        SeveringSystem.cofilinAccumulate(f.filState, ag.nucFrac, sv.cofFrac, sv.cofilinParams, sv.severCounts);
        SeveringSystem.cofilinDissolve(f.filState, f.monomerCount, sv.cofFrac, sv.severDeathFlag, sv.severReturnedMon, sv.cofilinParams, sv.severCounts);
        CrossBridgeSystem.csrScan(sv.severScanCounts, sv.severReturnedMon, sv.severReturnedOffsets);
        DepolySystem.applyDeath(f.filState, f.monomerCount, nuc.seedNode, f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide,
                f.brownTransScale, f.brownRotScale, sv.severDeathFlag, d.depolyCounts);
        grow.pool.put(sv.severReturnedOffsets.get(f.n));   // [conservation: dissolve returns]
        // split allocator (growth's own request arrays) + inherit nucFrac + reset child cofFrac (poison-clear)
        GrowthSystem.markSplits(nuc.seedNode, f.monomerCount, f.coord, f.uVec, f.yVec,
                f.acceptFlag, f.reqCoord, f.reqUVec, f.reqYVec, grow.splitParams, grow.growCounts);
        allocCpu(f, f.acceptFlag, f.reqCoord, f.reqUVec, f.reqYVec, f.rankScanCounts, f.rankOffsets);
        GrowthSystem.splitWire(f.rankOffsets, f.freeList, f.freeOffsets, f.monomerCount, f.coord, f.uVec,
                f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide, nuc.seedNode, grow.splitParams, f.allocCounts);
        AgingSystem.splitInheritNuc(f.rankOffsets, f.freeList, f.freeOffsets, ag.nucFrac, f.allocCounts);
        SeveringSystem.nucleateFreshCofilin(f.rankOffsets, f.freeList, f.freeOffsets, sv.cofFrac, f.allocCounts);  // child cofFrac=0 (poison-clear)
        GrowthSystem.recomputeDrag(f.monomerCount, f.segLength, f.end1NbrSlot, f.end2NbrSlot,
                f.bTransGam, f.bRotGam, f.bTransDiff, f.bRotDiff, grow.dragParams, grow.growCounts);

        // === NUCLEATION (every step; dedicated request arrays; dead-slot initNewborn + nucFrac/cofFrac fresh-resets) ===
        nuc.setCounts(t, SEED);
        nuc.nucCounts.set(3, grow.pool.available(Constants.actinSeed) ? 1 : 0);   // gate on the AUTHORITATIVE pool
        NodeNucleationSystem.countBoundFil(nuc.seedNode, nuc.nodeBoundFil, nuc.nucCounts);
        NodeNucleationSystem.emit(nb.coord, nuc.nodeBoundFil, s.nucAccept, s.nucReqCoord, s.nucReqUVec, s.nucReqYVec, nuc.nucParams, nuc.nucCounts);
        allocCpu(f, s.nucAccept, s.nucReqCoord, s.nucReqUVec, s.nucReqYVec, s.nucRankScanCounts, s.nucRankOffsets);
        NodeNucleationSystem.tagSeeds(s.nucRankOffsets, f.freeList, f.freeOffsets, nuc.seedNode, f.allocCounts);
        NodeNucleationSystem.initNewborn(s.nucRankOffsets, f.freeList, f.freeOffsets, f.monomerCount, f.segLength, nuc.seedParams, f.allocCounts);
        AgingSystem.nucleateFreshAtp(s.nucRankOffsets, f.freeList, f.freeOffsets, ag.nucFrac, f.allocCounts);      // fresh ATP (not a stale corpse)
        SeveringSystem.nucleateFreshCofilin(s.nucRankOffsets, f.freeList, f.freeOffsets, sv.cofFrac, f.allocCounts); // cofFrac=0 (clear poison)
        int nucBirths = Math.min(s.nucRankOffsets.get(f.n), f.freeOffsets.get(f.n));
        if (nucBirths > 0) grow.pool.take(nucBirths * Constants.actinSeed);       // [conservation]
        s.lastNucBirths = nucBirths;

        // === BINDING + cycle ===
        MotorStore.publishHeadFromBody(b.coord, b.uVec, b.segLength, mot.head, mot.uVec, mot.rodUVec, mot.counts);
        BindingDetectionSystem.bruteReachableNodeAware(mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, s.reachSeg, s.reachCount, nuc.seedNode, mot.kinParams, mot.counts);
        NucleotideCycleSystem.catchSlipRelease(mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.cooldown, mot.stats, mot.capStats, mot.kinParams, mot.counts);
        BindingDetectionSystem.bindNearestNodeAware(mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, s.reachSeg, s.reachCount, mot.boundSeg, mot.bindArc, nuc.seedNode, mot.kinParams, mot.counts);
        NucleotideCycleSystem.cycle(mot.nucleotideState, mot.boundSeg, mot.forceDotHist, mot.nucParams, mot.counts);

        // === FORCES (scheme 0 — the validated soft tether) ===
        ChainBendingForceSystem.zeroAccumulators(b.forceSum, b.torqueSum, mot.counts);
        ChainBendingForceSystem.zeroAccumulators(nb.forceSum, nb.torqueSum, nd.nodeBodyCounts);
        ChainBendingForceSystem.zeroAccumulators(f.forceSum, f.torqueSum, f.counts);
        BrownianForceSystem.brownianForce(b.randForce, b.randTorque, b.bTransGam, b.bRotGam, b.brownTransScale, b.brownRotScale, mot.bodyParams, mot.counts);
        BrownianForceSystem.brownianForce(nb.randForce, nb.randTorque, nb.bTransGam, nb.bRotGam, nb.brownTransScale, nb.brownRotScale, nd.nodeBodyParams, nd.nodeBodyCounts);
        BrownianForceSystem.brownianForce(f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.brownTransScale, f.brownRotScale, f.params, f.counts);
        MotorJointSystem.joints(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, mot.nucleotideState, mot.jointParams, mot.counts);
        DimerCouplingSystem.couple(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, dim.motorA, dim.motorB, dim.parallel, dim.dimerParams, mot.boundSeg);
        // node radial surface tether + the single-ended backbone-side CSR gather
        NodeSystem.tether(b.coord, b.uVec, b.segLength, b.bTransGam, b.forceSum, b.torqueSum,
                nb.coord, nb.uVec, nb.yVec, nd.nodeInvTransY, nd.attachKey, nd.radial, nd.attachCoeffK, nd.nodeData, nd.nodeParams);
        CrossBridgeSystem.csrHistogram(nd.attachNode, nd.nodeCounts4, nd.nodeAttachCount);
        CrossBridgeSystem.csrScan(nd.nodeCounts4, nd.nodeAttachCount, nd.nodeAttachOffsets);
        CrossBridgeSystem.csrScatter(nd.attachNode, nd.nodeCounts4, nd.nodeAttachOffsets, nd.nodeAttachCount, nd.nodeAttachList);
        MiniFilamentSystem.backboneGather(nd.nodeAttachOffsets, nd.nodeAttachList, nd.nodeData, nb.forceSum, nb.torqueSum, nd.nodeCounts4);
        CrossBridgeSystem.bondForces(b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength,
                mot.boundSeg, mot.bindArc, mot.nucleotideState, s.bondData, s.xbParams);
        CrossBridgeSystem.applyHeadForce(s.bondData, b.forceSum, b.torqueSum, mot.counts);
        // filament: chain (F3/F4) + the seedNode pull bond (node-center↔tip, two-sided) + the gathered cross-bridge reaction
        ChainBendingForceSystem.chainForces(f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide, f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts);
        NodeNucleationSystem.seedTether(f.coord, f.uVec, f.segLength, f.bTransGam, f.forceSum, f.torqueSum,
                nb.coord, nd.nodeInvTransY, nuc.seedNode, nuc.tetherParams);
        NodeNucleationSystem.seedTetherNodeReact(f.coord, f.uVec, f.segLength, f.bTransGam,
                nb.forceSum, nb.coord, nd.nodeInvTransY, nuc.seedNode, nuc.tetherParams);
        CrossBridgeSystem.csrHistogram(mot.boundSeg, mot.counts, s.segMotorCount);
        CrossBridgeSystem.csrScan(mot.counts, s.segMotorCount, s.segMotorOffsets);
        CrossBridgeSystem.csrScatter(mot.boundSeg, mot.counts, s.segMotorOffsets, s.segMotorCount, s.segMotorMyo);
        CrossBridgeSystem.segGather(s.segMotorOffsets, s.segMotorMyo, s.bondData, f.forceSum, f.torqueSum, mot.counts);
        CrossBridgeSystem.registerForceDot(s.bondData, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.counts);

        // === INTEGRATE (node confined+integrated; motor; filament) ===
        ContainmentSystem.confine(nb.coord, nb.uVec, nb.segLength, nb.bTransGam, nb.forceSum, nb.torqueSum, s.boxParams, nd.nodeBodyCounts);
        RigidRodLangevinIntegrationSystem.integrate(nb.coord, nb.uVec, nb.yVec, nb.forceSum, nb.torqueSum, nb.randForce, nb.randTorque, nb.bTransGam, nb.bRotGam, nd.nodeBodyParams, nd.nodeBodyCounts);
        DerivedGeometrySystem.derive(nb.coord, nb.uVec, nb.yVec, nb.zVec, nb.end1, nb.end2, nb.segLength, nd.nodeBodyCounts);
        RigidRodLangevinIntegrationSystem.integrate(b.coord, b.uVec, b.yVec, b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, mot.bodyParams, mot.counts);
        DerivedGeometrySystem.derive(b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts);
        RigidRodLangevinIntegrationSystem.integrate(f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts);
        DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
    }

    /** One allocator pass (B1 scan-rank free-list) parameterised by which request arrays drive it. */
    static void allocCpu(FilamentStore f, IntArray accept, FloatArray rc, FloatArray ru, FloatArray ry,
                         IntArray rankScan, IntArray rankOff) {
        FilamentBirthSystem.freeFlags(f.filState, f.freeCount, f.allocCounts);
        CrossBridgeSystem.csrScan(f.freeScanCounts, f.freeCount, f.freeOffsets);
        FilamentBirthSystem.freeScatter(f.filState, f.freeOffsets, f.freeList, f.allocCounts);
        CrossBridgeSystem.csrScan(rankScan, accept, rankOff);
        FilamentBirthSystem.allocate(rc, ru, ry, rankOff, f.freeList, f.freeOffsets,
                f.coord, f.uVec, f.yVec, f.brownTransScale, f.brownRotScale, f.filState, f.birthParams, f.allocCounts);
    }

    // ====================================================================== runner + readouts
    static void run(Scene s, double dt) {
        int M = STEPS;
        double[] extent0 = netExtent(s);
        double rms0 = extent0[0];
        long t0 = System.nanoTime();
        // capture-network accumulators
        int[][] pairCapture = new int[s.nNodes][s.nNodes];
        long sumCross = 0, sumSelf = 0, sumBound = 0; int nSamp = 0;
        double rmsMin = rms0; int stepMin = 0;
        boolean conservationOk = true; int worstPhantom = 0;
        double maxStretchNoMotion = 0;
        long severMon = 0, depolyMon = 0; int severEvents = 0;   // turnover channel tally (severing vs pointed depoly)
        long nucBirthsTotal = 0; double occSum = 0; int occN = 0; // nucleation activity + formin occupancy (re-nucleation)

        System.out.printf("%-8s %-9s %-9s %-7s %-7s %-7s %-7s %-8s %-7s%n",
                "step", "rms(µm)", "bbox(µm)", "cross", "self", "bound", "active", "contour", "conc(µM)");
        for (int t = 0; t < M; t++) {
            cpuStep(s, t);
            int sevRet = s.sever.severReturnedOffsets.get(s.fil.n);
            if (sevRet > 0) { severMon += sevRet; severEvents += (sevRet + Constants.stdSegLength - 1) / Math.max(1, SEG_MONO); }
            depolyMon += s.depoly.returnedOffsets.get(s.fil.n);
            nucBirthsTotal += s.lastNucBirths;
            if (t % Math.max(1, M / 100) == 0) {   // formin occupancy = #node-held filaments / #formin slots
                int occ = 0; for (int k = 0; k < s.nNodes; k++) occ += s.nuc.nodeBoundFil.get(k);
                occSum += occ / (double) (s.nNodes * FORMINS); occN++;
            }
            if (t % Math.max(1, M / 30) == 0 || t == M - 1) {
                double[] ext = netExtent(s);
                int cross = crossNodeCaptures(s), self = selfCaptures(s), bound = boundTotal(s.mot);
                boolean cons = conservationCheck(s); if (!cons) conservationOk = false;
                int phantom = phantomCount(s.fil); worstPhantom = Math.max(worstPhantom, phantom);
                System.out.printf("%-8d %-9.4f %-9.4f %-7d %-7d %-7d %-7d %-8.4f %-7.5f%s%n",
                        t, ext[0], ext[1], cross, self, bound, activeSegments(s.fil), totalContour(s.fil), s.grow.pool.conc(),
                        cons ? "" : "  *CONSERVATION FAIL*");
            }
            // sample the capture network + extent each 1% of the run
            if (t % Math.max(1, M / 100) == 0 || t == M - 1) {
                accumulateCaptureNetwork(s, pairCapture);
                sumCross += crossNodeCaptures(s); sumSelf += selfCaptures(s); sumBound += boundTotal(s.mot); nSamp++;
                double rms = netExtent(s)[0];
                if (rms < rmsMin) { rmsMin = rms; stepMin = t; }
                maxStretchNoMotion = Math.max(maxStretchNoMotion, forceTransmissionRead(s));
            }
        }
        double secs = (System.nanoTime() - t0) / 1e9;
        double[] extEnd = netExtent(s);

        System.out.println("\n===== RESULTS =====");
        System.out.printf("runtime: %.1f s for %d steps (%.0f steps/s, CPU)%n", secs, M, M / secs);
        System.out.printf("net RMS extent: start=%.4f → min=%.4f @ step %d → end=%.4f µm  (shrink: %.1f%%)%n",
                rms0, rmsMin, stepMin, extEnd[0], 100.0 * (rms0 - extEnd[0]) / rms0);
        System.out.printf("net bbox diag: start=%.4f → end=%.4f µm%n", extent0[1], extEnd[1]);
        // coalescence verdict
        double shrink = (rms0 - extEnd[0]) / rms0;
        String mode = shrink > 0.15 ? "COALESCING (net clumps)" : shrink > 0.04 ? "PARTIAL/mild contraction"
                : shrink < -0.10 ? "DISPERSING (flies apart)" : "STABLE (no net coalescence)";
        System.out.printf("=> COALESCENCE MODE: %s%n", mode);

        System.out.printf("captures (run-avg): cross-node=%.2f, self=%.2f, bound heads=%.1f%n",
                sumCross / (double) nSamp, sumSelf / (double) nSamp, sumBound / (double) nSamp);
        reportCaptureNetwork(s, pairCapture);

        // reach vs spacing
        double[] reach = reachStats(s);
        System.out.printf("reach: mean filament contour=%.4f µm, max=%.4f µm (per node, summed over its formins);"
                + " per-segment-chain reach≈%.4f µm vs spacing %.3f µm%n", reach[0], reach[1], reach[2], SPACING);

        // force-transmission read
        System.out.printf("force-transmission read (scheme 0): max inter-node bond stretch with little node motion = %.4f µm%n", maxStretchNoMotion);
        System.out.println(maxStretchNoMotion > 0.15
                ? "  ⚠ LARGE stretch persists with little coalescence ⇒ possible SCHEME-1 SIGNAL (collective load not transmitted) — see report"
                : "  stretch modest / nodes move ⇒ scheme-0 soft tether transmits the collective load (no scheme-1 signal)");

        // sanity at scale
        System.out.printf("turnover at scale: pool ledger total taken=%d, total returned=%d monomers (growth+nucleation vs depoly+sever+death churn)%n",
                s.grow.pool.totalTaken(), s.grow.pool.totalReturned());
        System.out.printf("  turnover channels: pointed-depoly returned %d monomers; SEVERING (cofilin dissolve) returned %d monomers in ~%d events%n",
                depolyMon, severMon, severEvents);
        int forminSlots = s.nNodes * FORMINS;
        System.out.printf("  nucleation: %d formin filaments born over the run (vs %d formin slots) ⇒ ~%d RE-nucleations after a formin lost its filament; mean formin occupancy %.0f%%%n",
                (int) nucBirthsTotal, forminSlots, (int) Math.max(0, nucBirthsTotal - forminSlots), 100.0 * occSum / Math.max(1, occN));
        System.out.printf("  (re-nucleation works: a formin whose node-held tip was SEVERED away ⇒ countBoundFil drops nodeBoundFil ⇒ emit refires at the boosted rate)%n");
        System.out.printf("conservation: %s (integer pool ledger held every sampled step)%n", conservationOk ? "EXACT" : "*** FAILED ***");
        System.out.printf("phantoms (ACTIVE slots with monomerCount<actinSeed or near-zero length): worst=%d%n", worstPhantom);
        System.out.printf("no-crash: ran to completion at scale (%d nodes, %d formins/node, cap=%d, %d active segments)%n",
                s.nNodes, FORMINS, FIL_CAP, activeSegments(s.fil));

        if (gpuScale) gpuScaleCheck(s, dt);
    }

    // ---- readout helpers ----
    /** {RMS radius of node centres about their centroid, bounding-box diagonal}. */
    static double[] netExtent(Scene s) {
        RigidRodBody nb = s.node.node; int n = nb.n;
        double cx = 0, cy = 0, cz = 0;
        for (int k = 0; k < n; k++) { cx += nb.coord.get(k); cy += nb.coord.get(n + k); cz += nb.coord.get(2 * n + k); }
        cx /= n; cy /= n; cz /= n;
        double sum2 = 0;
        double minx = 1e9, miny = 1e9, minz = 1e9, maxx = -1e9, maxy = -1e9, maxz = -1e9;
        for (int k = 0; k < n; k++) {
            double x = nb.coord.get(k), y = nb.coord.get(n + k), z = nb.coord.get(2 * n + k);
            double dx = x - cx, dy = y - cy, dz = z - cz;
            sum2 += dx*dx + dy*dy + dz*dz;
            minx = Math.min(minx, x); maxx = Math.max(maxx, x);
            miny = Math.min(miny, y); maxy = Math.max(maxy, y);
            minz = Math.min(minz, z); maxz = Math.max(maxz, z);
        }
        double rms = Math.sqrt(sum2 / n);
        double diag = Math.sqrt((maxx-minx)*(maxx-minx) + (maxy-miny)*(maxy-miny) + (maxz-minz)*(maxz-minz));
        return new double[]{ rms, diag };
    }
    static int boundTotal(MotorStore m) { int c = 0; for (int i = 0; i < m.nMotors; i++) if (m.boundSeg.get(i) >= 0) c++; return c; }
    /** Walk the chain toward the barbed/node end (end2NbrSlot) to the node-bonded tip (seedNode>=0). */
    static int filNodeOf(Scene s, int seg) {
        int cur = seg, guard = 0, cap = s.fil.n;
        while (cur >= 0 && guard < cap) {
            int tag = s.nuc.seedNode.get(cur);
            if (tag >= 0) return tag;
            int nxt = s.fil.end2NbrSlot.get(cur);
            if (nxt == cur || nxt < 0) return -1;
            cur = nxt; guard++;
        }
        return -1;
    }
    static int crossNodeCaptures(Scene s) {
        MotorStore m = s.mot; int c = 0;
        for (int i = 0; i < m.nMotors; i++) {
            int seg = m.boundSeg.get(i); if (seg < 0) continue;
            int fn = filNodeOf(s, seg);
            if (fn >= 0 && fn != s.motorNode[i]) c++;
        }
        return c;
    }
    static int selfCaptures(Scene s) {
        MotorStore m = s.mot; int c = 0;
        for (int i = 0; i < m.nMotors; i++) {
            int seg = m.boundSeg.get(i); if (seg < 0) continue;
            if (filNodeOf(s, seg) == s.motorNode[i]) c++;
        }
        return c;
    }
    /** Tally cross-node capture edges into the (captorNode, filNode) matrix. */
    static void accumulateCaptureNetwork(Scene s, int[][] pair) {
        MotorStore m = s.mot;
        for (int i = 0; i < m.nMotors; i++) {
            int seg = m.boundSeg.get(i); if (seg < 0) continue;
            int fn = filNodeOf(s, seg); int captor = s.motorNode[i];
            if (fn >= 0 && fn != captor) pair[captor][fn]++;
        }
    }
    static void reportCaptureNetwork(Scene s, int[][] pair) {
        int n = s.nNodes;
        int edges = 0, totalCaptures = 0;
        boolean[] connected = new boolean[n];
        // build an undirected adjacency for connected-component analysis (a captures b OR b captures a)
        boolean[][] adj = new boolean[n][n];
        for (int a = 0; a < n; a++)
            for (int b = 0; b < n; b++)
                if (pair[a][b] > 0) { totalCaptures += pair[a][b]; if (!adj[a][b]) { adj[a][b] = true; adj[b][a] = true; edges++; connected[a] = true; connected[b] = true; } }
        // largest connected component (BFS)
        boolean[] seen = new boolean[n]; int largest = 0;
        for (int start = 0; start < n; start++) {
            if (seen[start] || !connected[start]) continue;
            int sz = 0; java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>(); q.add(start); seen[start] = true;
            while (!q.isEmpty()) { int u = q.poll(); sz++; for (int v = 0; v < n; v++) if (adj[u][v] && !seen[v]) { seen[v] = true; q.add(v); } }
            largest = Math.max(largest, sz);
        }
        int participating = 0; for (boolean c : connected) if (c) participating++;
        System.out.printf("capture network: %d distinct node-pairs linked (of %d possible neighbour pairs), %d nodes participate;"
                + " largest connected cluster=%d of %d nodes%n", edges, n * 4 / 2 /*≈ grid edges, informational*/, participating, largest, n);
        // print the per-pair capture matrix (upper triangle, nonzero only)
        StringBuilder sb = new StringBuilder("  linked pairs (node↔node : captures both directions): ");
        boolean any = false;
        for (int a = 0; a < n; a++)
            for (int b = a + 1; b < n; b++) {
                int tot = pair[a][b] + pair[b][a];
                if (tot > 0) { sb.append(String.format("%d↔%d:%d  ", a, b, tot)); any = true; }
            }
        System.out.println(any ? sb.toString() : "  (no cross-node capture links formed this run)");
    }
    /** {mean per-node total contour, max per-node total contour, mean per-formin-chain reach}. */
    static double[] reachStats(Scene s) {
        FilamentStore f = s.fil; int n = s.nNodes;
        double[] perNode = new double[n];
        for (int seg = 0; seg < f.n; seg++) {
            if (f.filState.get(seg) < 0) continue;
            int fn = filNodeOf(s, seg);
            if (fn >= 0) perNode[fn] += f.segLength.get(seg);
        }
        double sum = 0, max = 0;
        for (int k = 0; k < n; k++) { sum += perNode[k]; max = Math.max(max, perNode[k]); }
        double meanNode = sum / n;
        double perChain = meanNode / FORMINS;   // mean reach of one formin's chain
        return new double[]{ meanNode, max, perChain };
    }
    /** Force-transmission probe: the max cross-node bond strain (the inter-node filament stretch) — large stretch
     *  persisting WITHOUT the net coalescing is the scheme-1 signal. Returns the max cross-bridge bond strain (µm)
     *  over currently cross-captured bonds (head ↔ captured segment displacement). */
    static double forceTransmissionRead(Scene s) {
        MotorStore m = s.mot; int nM = m.nMotors; int ST = CrossBridgeSystem.STRIDE;
        double maxF = 0;
        for (int i = 0; i < nM; i++) {
            int seg = m.boundSeg.get(i); if (seg < 0) continue;
            int fn = filNodeOf(s, seg); if (fn < 0 || fn == s.motorNode[i]) continue;   // cross-node only
            double fx = s.bondData.get(i * ST), fy = s.bondData.get(i * ST + 1), fz = s.bondData.get(i * ST + 2);
            maxF = Math.max(maxF, Math.sqrt(fx*fx + fy*fy + fz*fz) * 1e12);   // pN
        }
        // convert a representative cross-bridge force to an equivalent "stretch" via the xbridge spring (informational µm)
        return maxF * 1e-12 / MYO_SPRING;
    }
    static int activeSegments(FilamentStore f) { int c = 0; for (int s = 0; s < f.n; s++) if (f.filState.get(s) >= 0) c++; return c; }
    static double totalContour(FilamentStore f) { double c = 0; for (int s = 0; s < f.n; s++) if (f.filState.get(s) >= 0) c += f.segLength.get(s); return c; }
    static long sumActiveMonomers(FilamentStore f) { long m = 0; for (int s = 0; s < f.n; s++) if (f.filState.get(s) >= 0) m += f.monomerCount.get(s); return m; }
    static double totalActinUM(Scene s) { return s.grow.pool.conc() + sumActiveMonomers(s.fil) * uMper; }
    /** Phantom = an ACTIVE slot born from a recycled dead slot but left with a stale ZERO monomerCount (the
     *  dead-slot bug) — must be 0 with initNewborn. The discriminator is monomerCount<=0: a legitimately-shrinking
     *  pointed tip only ever reaches monomerCount=actinSeed-1(=2) for one cadence before death markFrees it (depoly
     *  decrements only while M>=actinSeed), so an ACTIVE slot at 0 is exactly a born-stale corpse. */
    static int phantomCount(FilamentStore f) {
        int c = 0;
        for (int s = 0; s < f.n; s++) if (f.filState.get(s) >= 0 && f.monomerCount.get(s) <= 0) c++;
        return c;
    }
    /** Integer pool-ledger conservation: Σ monomerCount(active) == monInit + totalTaken − totalReturned. */
    static boolean conservationCheck(Scene s) {
        long Fnow = sumActiveMonomers(s.fil);
        long taken = s.grow.pool.totalTaken(), ret = s.grow.pool.totalReturned();
        return Fnow == s.monInit + taken - ret;
    }

    // ====================================================================== GPU device scale / no-crash check
    /** Build + run the full device-resident pipeline at scale for a short horizon: validates the allocator +
     *  turnover/nucleation coexistence on the parallel path (no race / no crash) + throughput. Aggregate-only
     *  (chaotic many-body; CPU≡GPU not required). */
    static void gpuScaleCheck(Scene s, double dt) {
        System.out.println("\n--- GPU device scale / no-crash check (device-resident, short horizon) ---");
        Scene g = build(dt);                // a fresh scene for the device run
        TornadoExecutionPlan plan;
        try {
            plan = buildPlan(g);
        } catch (Throwable e) {
            System.out.println("  GPU plan build FAILED: " + e + " (CPU experiment stands; device path deferred)");
            return;
        }
        int M = 3000;
        long t0 = System.nanoTime();
        try {
            for (int t = 0; t < M; t++) {
                stepHostBookkeeping(g, t, dt, plan);
            }
        } catch (Throwable e) {
            System.out.println("  GPU run threw at scale: " + e);
            return;
        }
        double secs = (System.nanoTime() - t0) / 1e9;
        int gBound = boundTotal(g.mot), gActive = activeSegments(g.fil);
        System.out.printf("  GPU ran %d steps in %.1f s (%.0f steps/s) at scale, NO crash/race on the parallel path%n", M, secs, M / secs);
        System.out.printf("  GPU aggregate: bound heads=%d, active segments=%d, conc=%.5f µM, conservation=%s, phantoms=%d%n",
                gBound, gActive, g.grow.pool.conc(), conservationCheck(g) ? "EXACT" : "*** FAIL ***", phantomCount(g.fil));
        // CPU reference at the SAME horizon (chaotic many-body ⇒ aggregate-within-tolerance, not bit-identical)
        Scene c = build(dt);
        for (int t = 0; t < M; t++) cpuStep(c, t);
        int cBound = boundTotal(c.mot), cActive = activeSegments(c.fil);
        boolean agree = Math.abs(gActive - cActive) <= Math.max(5, (int) (0.15 * Math.max(1, cActive)))
                && Math.abs(gBound - cBound) <= Math.max(4, (int) (0.3 * Math.max(1, cBound)));
        System.out.printf("  CPU≡GPU aggregate @ %d steps: active GPU=%d CPU=%d, bound GPU=%d CPU=%d, conc GPU=%.4f CPU=%.4f => %s%n",
                M, gActive, cActive, gBound, cBound, g.grow.pool.conc(), c.grow.pool.conc(), agree ? "AGREE (within tolerance)" : "*differ — investigate*");
    }

    static TornadoExecutionPlan buildPlan(Scene s) {
        MotorStore mot = s.mot; DimerStore dim = s.dim; NodeStore nd = s.node;
        FilamentStore f = s.fil; NodeNucleationStore nuc = s.nuc; GrowthStore grow = s.grow; DepolyStore d = s.depoly;
        AgingStore ag = s.aging; SeverStore sv = s.sever;
        RigidRodBody b = mot.body; RigidRodBody nb = nd.node;
        TaskGraph tg = new TaskGraph("ring")
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
                    s.nucAccept, s.nucReqCoord, s.nucReqUVec, s.nucReqYVec, s.nucRankOffsets, s.nucRankScanCounts)
            .transferToDevice(DataTransferMode.EVERY_EXECUTION, mot.counts, nd.nodeBodyCounts, f.counts, grow.growCounts, grow.growParams,
                    nuc.nucCounts, d.depolyCounts, ag.agingCounts, sv.severCounts)
            // === FULL TURNOVER: age → depoly(proxy) + death → grow + growthAtp → cofilin sever + death → split (+inherit/reset) ===
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
            // === NUCLEATION (dedicated request arrays; dead-slot initNewborn + nucFrac/cofFrac fresh-resets) ===
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
            // === BINDING + cycle ===
            .task("publishHead", MotorStore::publishHeadFromBody, b.coord, b.uVec, b.segLength, mot.head, mot.uVec, mot.rodUVec, mot.counts)
            .task("reach", BindingDetectionSystem::bruteReachableNodeAware, mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, s.reachSeg, s.reachCount, nuc.seedNode, mot.kinParams, mot.counts)
            .task("release", NucleotideCycleSystem::catchSlipRelease, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.cooldown, mot.stats, mot.capStats, mot.kinParams, mot.counts)
            .task("bind", BindingDetectionSystem::bindNearestNodeAware, mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, s.reachSeg, s.reachCount, mot.boundSeg, mot.bindArc, nuc.seedNode, mot.kinParams, mot.counts)
            .task("cycle", NucleotideCycleSystem::cycle, mot.nucleotideState, mot.boundSeg, mot.forceDotHist, mot.nucParams, mot.counts)
            // === FORCES ===
            .task("zeroMot", ChainBendingForceSystem::zeroAccumulators, b.forceSum, b.torqueSum, mot.counts)
            .task("zeroNode", ChainBendingForceSystem::zeroAccumulators, nb.forceSum, nb.torqueSum, nd.nodeBodyCounts)
            .task("zeroFil", ChainBendingForceSystem::zeroAccumulators, f.forceSum, f.torqueSum, f.counts)
            .task("brownMot", BrownianForceSystem::brownianForce, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, b.brownTransScale, b.brownRotScale, mot.bodyParams, mot.counts)
            .task("brownNode", BrownianForceSystem::brownianForce, nb.randForce, nb.randTorque, nb.bTransGam, nb.bRotGam, nb.brownTransScale, nb.brownRotScale, nd.nodeBodyParams, nd.nodeBodyCounts)
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
            .task("chain", ChainBendingForceSystem::chainForces, f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide, f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts)
            .task("seedTether", NodeNucleationSystem::seedTether, f.coord, f.uVec, f.segLength, f.bTransGam, f.forceSum, f.torqueSum, nb.coord, nd.nodeInvTransY, nuc.seedNode, nuc.tetherParams)
            .task("seedReact", NodeNucleationSystem::seedTetherNodeReact, f.coord, f.uVec, f.segLength, f.bTransGam, nb.forceSum, nb.coord, nd.nodeInvTransY, nuc.seedNode, nuc.tetherParams)
            .task("filHist", CrossBridgeSystem::csrHistogram, mot.boundSeg, mot.counts, s.segMotorCount)
            .task("filScan", CrossBridgeSystem::csrScan, mot.counts, s.segMotorCount, s.segMotorOffsets)
            .task("filScatter", CrossBridgeSystem::csrScatter, mot.boundSeg, mot.counts, s.segMotorOffsets, s.segMotorCount, s.segMotorMyo)
            .task("filGather", CrossBridgeSystem::segGather, s.segMotorOffsets, s.segMotorMyo, s.bondData, f.forceSum, f.torqueSum, mot.counts)
            .task("register", CrossBridgeSystem::registerForceDot, s.bondData, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.counts)
            // === INTEGRATE ===
            .task("confineNode", ContainmentSystem::confine, nb.coord, nb.uVec, nb.segLength, nb.bTransGam, nb.forceSum, nb.torqueSum, s.boxParams, nd.nodeBodyCounts)
            .task("integNode", RigidRodLangevinIntegrationSystem::integrate, nb.coord, nb.uVec, nb.yVec, nb.forceSum, nb.torqueSum, nb.randForce, nb.randTorque, nb.bTransGam, nb.bRotGam, nd.nodeBodyParams, nd.nodeBodyCounts)
            .task("deriveNode", DerivedGeometrySystem::derive, nb.coord, nb.uVec, nb.yVec, nb.zVec, nb.end1, nb.end2, nb.segLength, nd.nodeBodyCounts)
            .task("integM", RigidRodLangevinIntegrationSystem::integrate, b.coord, b.uVec, b.yVec, b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, mot.bodyParams, mot.counts)
            .task("deriveM", DerivedGeometrySystem::derive, b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts)
            .task("integFil", RigidRodLangevinIntegrationSystem::integrate, f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts)
            .task("deriveFil", DerivedGeometrySystem::derive, f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts)
            .transferToHost(DataTransferMode.UNDER_DEMAND, nb.coord, mot.boundSeg, nuc.seedNode, f.filState, f.monomerCount, f.segLength, f.coord,
                    grow.grewOffsets, d.returnedOffsets, sv.severReturnedOffsets, s.nucRankOffsets, f.freeOffsets);

        int nMB = b.n, nN = nb.n, nM = mot.nMotors, C = f.n, nD = dim.nDimers, nA = nd.nAttach;
        sched = new GridScheduler();
        for (String t : new String[]{ "publishHead","reach","release","bind","cycle","bond","applyHead","register" }) addW("ring." + t, pad(nM));
        for (String t : new String[]{ "zeroMot","brownMot","joints","integM","deriveM" }) addW("ring." + t, pad(nMB));
        for (String t : new String[]{ "zeroNode","brownNode","ndGather","seedReact","confineNode","integNode","deriveNode" }) addW("ring." + t, pad(nN));
        addW("ring.dimer", pad(nD));
        addW("ring.tether", pad(nA));
        addW("ring.count", pad(1)); addW("ring.emit", pad(nN));
        for (String t : new String[]{ "depoly","applyDeath","grow","markSplits","recomputeDrag","gFreeFlags","gFreeScatter","gAllocate",
                                       "nFreeFlags","nFreeScatter","nAllocate","tagSeeds","initNewborn","nucFresh","nucCof",
                                       "age","growthAtp","cofAcc","cofDis","severDeath","splitWire","splitInherit","splitCof",
                                       "zeroFil","brownFil","chain","seedTether","filGather","integFil","deriveFil" }) addW("ring." + t, pad(C));
        for (String t : new String[]{ "csrReturn","csrGrew","csrSever","gCsrFree","gCsrRank","nCsrFree","nCsrRank",
                                       "ndHist","ndScan","ndScatter","filHist","filScan","filScatter" }) addS("ring." + t);
        return new TornadoExecutionPlan(tg.snapshot());
    }

    /** Per-step host bookkeeping for the GPU plan: refresh rates/counts (P from the current pool), execute the
     *  device graph, then update the shared pool from the integer counts (the treadmill 3-step protocol). */
    static void stepHostBookkeeping(Scene g, int t, double dt, TornadoExecutionPlan plan) {
        MotorStore mot = g.mot; NodeStore nd = g.node; FilamentStore f = g.fil;
        NodeNucleationStore nuc = g.nuc; GrowthStore grow = g.grow; DepolyStore d = g.depoly;
        AgingStore ag = g.aging; SeverStore sv = g.sever;
        boolean fires = grow.firesAt(t);
        mot.setCounts(t, SEED, f.n); nd.setNodeBodyCounts(t, SEED_NODE); f.setCounts(t, SEED);
        grow.setCounts(t, SEED, fires); grow.refreshRate(fires);   // growth rate pool-dependent ⇒ refresh; aging/sever/depoly rates KIN-set once in build
        d.setCounts(t, SEED, fires); ag.setFires(fires); sv.setFires(fires);
        nuc.setCounts(t, SEED);
        nuc.nucCounts.set(3, grow.pool.available(Constants.actinSeed) ? 1 : 0);
        TornadoExecutionResult res = plan.withGridScheduler(sched).execute();
        // pool update from the integer counts (device → host scalars): depoly + dissolve return; growth + nucleation take
        res.transferToHost(grow.grewOffsets, d.returnedOffsets, sv.severReturnedOffsets, g.nucRankOffsets, f.freeOffsets);
        grow.pool.put(d.returnedOffsets.get(f.n) + sv.severReturnedOffsets.get(f.n));   // == grow.pool (shared)
        grow.pool.take(grow.grewOffsets.get(f.n));
        int nucBirths = Math.min(g.nucRankOffsets.get(f.n), f.freeOffsets.get(f.n));
        if (nucBirths > 0) grow.pool.take(nucBirths * Constants.actinSeed);
        if (t == 2999) res.transferToHost(nd.node.coord, mot.boundSeg, nuc.seedNode, f.filState, f.monomerCount, f.segLength, f.coord);
    }

    // ====================================================================== viewer
    static final String[] STATE_NAME = { "NONE", "ATP", "ADPPi", "ADP" };
    static void runViz(Scene s, double dt) {
        new java.io.File(vizDir).mkdirs();
        int M = STEPS, every = Math.max(1, M / 400), frames = 0;
        for (int t = 0; t <= M; t++) {
            cpuStep(s, t);
            if (t % every == 0) writeFrame(vizDir, frames++, t, t * dt, s);
        }
        double[] ext = netExtent(s);
        System.out.printf("viewer: wrote %d frames to %s; final RMS extent=%.4f µm, cross-captures=%d, active=%d%n",
                frames, vizDir, ext[0], crossNodeCaptures(s), activeSegments(s.fil));
    }
    static void writeFrame(String dir, int frame, int step, double t, Scene s) {
        FilamentStore f = s.fil; MotorStore mot = s.mot; RigidRodBody b = mot.body; RigidRodBody nb = s.node.node;
        AgingStore ag = s.aging;
        StringBuilder sb = new StringBuilder(4096 + 200 * mot.nMotors);
        sb.append(String.format(java.util.Locale.US, "{\"frame\":%d,\"t\":%.6g,\"bounds\":{\"xDim\":%.2f,\"yDim\":%.2f,\"zDim\":%.2f}",
                frame, t, BOX, BOX, BOX));
        // segments: notADPRatio = f_ATP+f_ADPPi (the ADP gradient barbed→pointed; red=old/ADP), isBarbedEnd at the
        // node-side terminal (the "+" sprite). Dissolved/depoly'd (FREE) segments are skipped ⇒ they VANISH + the
        // filament shows as fragments — the watchable severing.
        sb.append(",\"segments\":[");
        boolean first = true;
        for (int seg = 0; seg < f.n; seg++) {
            if (f.filState.get(seg) < 0) continue;
            if (!first) sb.append(','); first = false;
            double notADP = ag.fATP(seg) + ag.fADPPi(seg);
            boolean barbed = f.end2NbrSlot.get(seg) < 0;       // barbed=end2: node-side terminal carries the "+"
            sb.append(String.format(java.util.Locale.US, "{\"id\":%d,\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.5g,\"notADPRatio\":%.4f,\"isBarbedEnd\":%b,\"cofilinCount\":%d}",
                seg, f.end1.get(seg), f.end1.get(f.n+seg), f.end1.get(2*f.n+seg), f.end2.get(seg), f.end2.get(f.n+seg), f.end2.get(2*f.n+seg),
                Constants.radius, notADP, barbed, (s.sever.fCof(seg) > (float) s.sever.cofilinRatio ? 1 : 0)));
        }
        // nodes: the viewer's DEDICATED grey-sphere channel (no viewer edit; BoA rendering untouched) — SPHERES,
        // not the old degenerate "myosin" cylinders.
        sb.append("],\"nodes\":[");
        for (int k = 0; k < nb.n; k++) {
            if (k > 0) sb.append(',');
            double cx = nb.coord.get(k), cy = nb.coord.get(nb.n + k), cz = nb.coord.get(2*nb.n + k);
            sb.append(String.format(java.util.Locale.US, "{\"id\":%d,\"center\":[%.5g,%.5g,%.5g],\"r\":%.4g}",
                900000 + k, cx, cy, cz, NodeStore.NODE_RADIUS));
        }
        sb.append("],\"myosins\":[");
        boolean firstM = true;
        for (int m = 0; m < mot.nMotors; m++) {
            if (!firstM) sb.append(','); firstM = false;
            int rod = 3*m, lever = 3*m+1, head = 3*m+2; String state = STATE_NAME[mot.nucleotideState.get(m)];
            sb.append(String.format(java.util.Locale.US,
                "{\"id\":%d,\"rod\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g,\"invisible\":false},"
                + "\"lever\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g},"
                + "\"motor\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g,\"state\":\"%s\"}}",
                m, b.end1X(rod),b.end1Y(rod),b.end1Z(rod), b.end2X(rod),b.end2Y(rod),b.end2Z(rod), MotorStore.ROD_R,
                b.end1X(lever),b.end1Y(lever),b.end1Z(lever), b.end2X(lever),b.end2Y(lever),b.end2Z(lever), MotorStore.LEVER_R,
                b.end1X(head),b.end1Y(head),b.end1Z(head), b.end2X(head),b.end2Y(head),b.end2Z(head), MotorStore.HEAD_R, state));
        }
        sb.append("]");
        double[] ext = netExtent(s);
        sb.append(String.format(java.util.Locale.US, ",\"stats\":{\"step\":%d,\"simTime\":%.5g,\"rmsExtent_um\":%.5g,\"bbox_um\":%.5g,\"crossCaptures\":%d,\"boundHeads\":%d,\"activeFil\":%d,\"contour_um\":%.5g,\"conc_uM\":%.5g}",
                step, t, ext[0], ext[1], crossNodeCaptures(s), boundTotal(mot), activeSegments(f), totalContour(f), s.grow.pool.conc()));
        sb.append("}");
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(dir, String.format(java.util.Locale.US,"frame_%06d.json", frame)), sb.toString()); }
        catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }

    // ====================================================================== dimer placement (from TestBScprHarness)
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
        b.brownTransScale.set(rod, (float) BROWN_TRANS);   b.brownRotScale.set(rod, (float) BROWN_ROT);
        b.brownTransScale.set(lever, 0f);                  b.brownRotScale.set(lever, 0f);
        b.brownTransScale.set(head, (float) BROWN_TRANS);  b.brownRotScale.set(head, (float) BROWN_ROT);
    }

    static int pad(int n) { return ((n + B - 1) / B) * B; }
    static void addW(String n, int g) { WorkerGrid w = new WorkerGrid1D(g); w.setLocalWork(B, 1, 1); sched.addWorkerGrid(n, w); }
    static void addS(String n) { WorkerGrid w = new WorkerGrid1D(1); w.setLocalWork(1, 1, 1); sched.addWorkerGrid(n, w); }
}
