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
 * Increment 5d — O(N) crosslinker FORMATION via the entity-agnostic grid + the fused per-segment neighborhood
 * query (FormationGrid + CrosslinkerSystem.gridForm*), retiring the last quadratic/serial kernel
 * (filFilCandidates). This harness validates the make-or-break gate:
 *
 *   FORMATION==BRUTE — the O(N) grid formation produces reqFilA/reqFilB (hence the formed LINKS) BIT-IDENTICAL
 *   to the O(N²) all-pairs filFilCandidates, under churn (Brownian-moving pose, formation+unbind+force+integrate
 *   each step). Two scenes from identical ICs differ ONLY in the candidate enumeration; if the grid path ever
 *   diverged, the deterministic trajectories would split — gated bit-identical on links AND poses every step.
 *
 *   CPU≡GPU — the grid build + fused query + formation pipeline produce bit-identical link arrays on the GPU
 *   TaskGraph and the -cpu runner (a fixed pose; integer wang-hash RNG ⇒ deterministic).
 *
 * The downstream (formGates / admit / scan-rank allocator / 2-pass gather / unbind) is reused VERBATIM — only the
 * candidate ENUMERATION changes (all-pairs → grid-neighborhood), so the candidate index c is the SAME function of
 * the (i,j) pair and inc-5's candidate-index-keyed RNG + min-candidate-index admission are unshifted.
 */
public final class XlinkFormationHarness {

    // v1 FilLink / Env constants (same provenance as CrosslinkerBundleHarness)
    static final double REST_LEN = 0.0125, FRAC_MOVE = 0.4;
    static final double OFF_CONST = 1.0, OFF_COEFF = 1.0, OFF_EXP = 2.0;
    static final double FIL_TORQ_SPRING = 1.0e-19;
    static final double GRAB_DIST = 2.0 * Constants.actinMonoDiam;
    static final double MIN_SEP = 5.0 * Constants.actinMonoDiam;
    static final double MIN_FILLINK_SEP = 2.0 * Constants.actinMonoDiam;
    static final int    MAX_LINKS_ON_SEG = 10;
    static final double XLINK_ON_RATE = 10.0;
    static final double MAX_ANGLE = 0.6;          // rad (the dense-config widened angle)

    static boolean cpu = false;
    static GridScheduler sched;

    static double pForm(double conc, double dtCheck) { return 1.0 - Math.exp(-XLINK_ON_RATE * conc * dtCheck); }

    public static void main(String[] args) {
        int nFil = 200, M = 400, checkInt = 10, seed = 12345;
        double conc = 4.0, dt = Constants.deltaT;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-cpu"      -> cpu = true;
                case "-nfil"     -> nFil = Integer.parseInt(args[++i]);
                case "-steps"    -> M = Integer.parseInt(args[++i]);
                case "-checkint" -> checkInt = Integer.parseInt(args[++i]);
                case "-conc"     -> conc = Double.parseDouble(args[++i]);
                case "-seed"     -> seed = Integer.parseInt(args[++i]);
                case "-bench"    -> { runBench(conc, checkInt, dt, seed); return; }
                default          -> {}
            }
        }
        System.out.println("=== Soft Box 5d — O(N) crosslinker formation (grid + fused per-segment query) ===");
        System.out.printf("nFil=%d  M=%d  checkInt=%d  conc=%.3g  pForm=%.4g  grab=%.4g µm  runner=%s%n",
                nFil, M, checkInt, conc, pForm(conc, dt * checkInt), GRAB_DIST, cpu ? "CPU only" : "GPU+CPU");

        boolean ok = true;
        ok &= gateFormationEqualsBrute(nFil, M, checkInt, conc, dt, seed);
        if (!cpu) ok &= gateCpuGpu(nFil, checkInt, conc, dt, seed);

        System.out.println("\n=== 5d O(N) FORMATION VALIDATION " + (ok ? "PASS" : "FAIL") + " ===");
        if (!ok) System.exit(1);
    }

    // ===================================================================== scene
    static final class Scene {
        FilamentStore fil; IntArray filID; CrosslinkerStore xl; FormationGrid fg;
        IntArray segCountA, segOffsetsA, segIdxA, segCountB, segOffsetsB, segIdxB;
        int nFil, C, reqCap, seed, checkInt; double dt;
        int maxCandSeen = 0;
    }

    /** Dense crossing bundle: nFil single-segment free filaments, centres clustered (Gaussian, clamped),
     *  uniform orientation, Brownian on — many cross-filament crossings within grab. Deterministic from seed. */
    static Scene build(int nFil, double conc, int checkInt, double dt, int seed) {
        return build(nFil, conc, checkInt, dt, seed, -1);
    }

    static Scene build(int nFil, double conc, int checkInt, double dt, int seed, int reqCapOverride) {
        Scene sc = new Scene();
        int nSeg = nFil;
        java.util.Random rng = new java.util.Random(seed * 2654435761L ^ 0x9E3779B9L);
        FilamentStore fil = new FilamentStore(nSeg);
        IntArray filID = new IntArray(nSeg);
        // density-preserving sizing: hold fils/µm³ constant by scaling the box ∝ cbrt(nSeg) off the nFil=200,
        // box-0.7 baseline (Gate A's realistic regime). Packing more fils into a FIXED box would inflate density
        // unphysically (crossing partners ∝ density), saturating the per-segment cap and overpopulating cells.
        double boxScale = Math.cbrt(nSeg / 200.0);
        double minLen = 0.1, maxLen = 0.3, posSigma = 0.2 * boxScale, box = 0.7 * boxScale;
        double maxSegLen = 0;
        for (int s = 0; s < nSeg; s++) {
            double Li = minLen + rng.nextDouble() * (maxLen - minLen);
            int mono = Math.max(1, (int) Math.round(Li / Constants.actinMonoRadius) - 1);
            fil.monomerCount.set(s, mono);
            double z = 2.0 * rng.nextDouble() - 1.0, phi = 2.0 * Math.PI * rng.nextDouble();
            double r = Math.sqrt(Math.max(0.0, 1.0 - z * z));
            double ux = r * Math.cos(phi), uy = r * Math.sin(phi), uz = z;
            fil.setUVec(s, (float) ux, (float) uy, (float) uz);
            double yx = -uy, yy = ux, yz = 0; double yn = Math.sqrt(yx * yx + yy * yy + yz * yz);
            if (yn < 1e-9) { yx = 0; yy = -uz; yz = uy; yn = Math.sqrt(yy * yy + yz * yz); }
            fil.setYVec(s, (float) (yx / yn), (float) (yy / yn), (float) (yz / yn));
            fil.setCoord(s, (float) clamp(rng.nextGaussian() * posSigma, box),
                            (float) clamp(rng.nextGaussian() * posSigma, box),
                            (float) clamp(rng.nextGaussian() * posSigma, box));
            fil.brownTransScale.set(s, (float) Constants.BTransCoeff);
            fil.brownRotScale.set(s, (float) Constants.BRotCoeff);
            filID.set(s, s);
        }
        DragTensorSystem.run(fil);
        applyAeta(fil, 1.0);   // crowded-cytoplasm viscosity (v1 boa-xlink-dense): slow dispersion ⇒ sustained crossings
        fil.setParams(dt, Math.sqrt(2.0 * Constants.kT / dt));
        fil.setCounts(0, seed);
        DerivedGeometrySystem.derive(fil.coord, fil.uVec, fil.yVec, fil.zVec, fil.end1, fil.end2, fil.segLength, fil.counts);
        for (int s = 0; s < nSeg; s++) maxSegLen = Math.max(maxSegLen, fil.segLength.get(s));

        // O(N) request capacity: FORM_MAXC partners per segment (vs the O(N²) nSeg(nSeg−1)/2 of filFilCandidates)
        int reqCap = reqCapOverride > 0 ? reqCapOverride : nSeg * CrosslinkerSystem.FORM_MAXC;
        int C = Math.max(64, nSeg * 4);
        CrosslinkerStore xl = new CrosslinkerStore(C, nSeg, reqCap);
        xl.setParams(REST_LEN, FRAC_MOVE, dt);
        xl.setOffParams(OFF_CONST, OFF_COEFF, OFF_EXP, dt, REST_LEN);
        xl.setFormParams(MAX_ANGLE, GRAB_DIST, MIN_SEP, MAX_LINKS_ON_SEG, pForm(conc, dt * checkInt), MIN_FILLINK_SEP, 0);
        xl.setRequestCount(reqCap);
        xl.setTorsionParams(FIL_TORQ_SPRING, true);

        // formation grid box: cover all centres (box + margin); cellSize ≥ maxSegLen + grab (completeness)
        double cellSize = 2.0 * (0.5 * maxSegLen + Constants.radius) + GRAB_DIST;
        double g = box + cellSize + 0.05;
        FormationGrid fg = new FormationGrid(nSeg, g, g, g, cellSize, GRAB_DIST, Constants.radius);

        sc.segCountA = new IntArray(nSeg); sc.segOffsetsA = new IntArray(nSeg + 1); sc.segIdxA = new IntArray(C);
        sc.segCountB = new IntArray(nSeg); sc.segOffsetsB = new IntArray(nSeg + 1); sc.segIdxB = new IntArray(C);
        sc.fil = fil; sc.filID = filID; sc.xl = xl; sc.fg = fg;
        sc.nFil = nFil; sc.C = C; sc.reqCap = reqCap; sc.seed = seed; sc.checkInt = checkInt; sc.dt = dt;
        return sc;
    }

    static double clamp(double v, double lim) { return v < -lim ? -lim : v > lim ? lim : v; }

    /** Scale drag from Constants.aeta to target aeta (FDT-consistent — Brownian reads bTransGam/bRotGam). */
    static void applyAeta(FilamentStore f, double aeta) {
        double r = aeta / Constants.aeta;
        if (r == 1.0) return;
        scale(f.bTransGam, r); scale(f.bRotGam, r); scale(f.bTransDiff, 1.0 / r); scale(f.bRotDiff, 1.0 / r);
    }
    static void scale(FloatArray a, double r) { for (int i = 0; i < a.getSize(); i++) a.set(i, (float) (a.get(i) * r)); }

    // ===================================================================== assembled CPU step
    /** The full assembled per-step loop; formation uses the GRID query (useGrid) or the O(N²) brute. */
    static void step(Scene sc, int step, boolean useGrid) {
        FilamentStore f = sc.fil; CrosslinkerStore xl = sc.xl;
        ChainBendingForceSystem.zeroAccumulators(f.forceSum, f.torqueSum, f.counts);
        f.setCounts(step, sc.seed);
        BrownianForceSystem.brownianForce(f.randForce, f.randTorque, f.bTransGam, f.bRotGam,
                f.brownTransScale, f.brownRotScale, f.params, f.counts);
        if (step % sc.checkInt == 0) formation(sc, step, useGrid);
        xl.setCounts(step, sc.seed);
        CrosslinkerSystem.unbind(f.coord, f.uVec, f.end1, xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2,
                xl.linkState, xl.strainHist, xl.strainPlace, xl.offParams, xl.counts);
        CrosslinkerSystem.countActiveLinks(xl.linkState, xl.linkFilA, xl.linkFilB, xl.activeLinkCount, xl.formCounts);
        CrosslinkerSystem.linkForces(f.coord, f.uVec, f.end1, f.bTransGam, xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2,
                xl.activeLinkCount, xl.xlinkData, xl.xlParams);
        CrosslinkerSystem.linkTorsion(f.uVec, xl.linkFilA, xl.linkFilB, xl.linkState, xl.linkOrientSame,
                xl.torqueMagHist, xl.torqueMagPlace, xl.xlinkData, xl.torsionParams);
        CrossBridgeSystem.csrHistogram(xl.linkFilA, xl.counts, sc.segCountA);
        CrossBridgeSystem.csrScan(xl.counts, sc.segCountA, sc.segOffsetsA);
        CrossBridgeSystem.csrScatter(xl.linkFilA, xl.counts, sc.segOffsetsA, sc.segCountA, sc.segIdxA);
        CrosslinkerSystem.segGatherA(sc.segOffsetsA, sc.segIdxA, xl.xlinkData, xl.linkState, f.forceSum, f.torqueSum, xl.counts);
        CrossBridgeSystem.csrHistogram(xl.linkFilB, xl.counts, sc.segCountB);
        CrossBridgeSystem.csrScan(xl.counts, sc.segCountB, sc.segOffsetsB);
        CrossBridgeSystem.csrScatter(xl.linkFilB, xl.counts, sc.segOffsetsB, sc.segCountB, sc.segIdxB);
        CrosslinkerSystem.segGatherB(sc.segOffsetsB, sc.segIdxB, xl.xlinkData, xl.linkState, f.forceSum, f.torqueSum, xl.counts);
        RigidRodLangevinIntegrationSystem.integrate(f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum,
                f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts);
        DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
    }

    /** Formation: candidate fill (grid OR brute) → gates → admit → scan-rank allocator → orientSame persist. */
    static void formation(Scene sc, int step, boolean useGrid) {
        FilamentStore f = sc.fil; CrosslinkerStore xl = sc.xl;
        xl.setFormStep(step, sc.seed);
        CrosslinkerSystem.countActiveLinks(xl.linkState, xl.linkFilA, xl.linkFilB, xl.activeLinkCount, xl.formCounts);
        if (useGrid) {
            sc.fg.buildCpu(f);
            sc.fg.formCandidatesCpu(f, sc.filID, xl);
            sc.maxCandSeen = Math.max(sc.maxCandSeen, sc.fg.maxCandPerSeg());
        } else {
            CrosslinkerSystem.filFilCandidates(f.coord, f.segLength, sc.filID, xl.reqFilA, xl.reqFilB, xl.formParams, xl.formCounts);
        }
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

    // ===================================================================== BENCH: O(N) scaling + dense no-crash
    /** Demonstrates the retired quadratic: (1) head-to-head per-call cost of the O(N²) brute filFilCandidates vs
     *  the O(N) grid query (build + fused count/scan/emit) at small scales — brute super-linear, grid linear,
     *  IDENTICAL candidate counts; (2) grid-only cost up to DENSE scale (ms/nFil ≈ const ⇒ linear); (3) a GPU
     *  device-resident formation step at dense scale (no-crash, finite, bounded links). */
    static void runBench(double conc, int checkInt, double dt, int seed) {
        System.out.println("\n========== 5d BENCH — O(N) formation scaling (quadratic kernel retired) ==========");

        System.out.println("\n-- head-to-head: O(N²) brute filFilCandidates vs O(N) grid query (CPU, per-call ms) --");
        System.out.printf("  %-8s %-12s %-12s %-12s %-10s %-12s%n", "nFil", "brute(ms)", "grid(ms)", "speedup", "cands==", "grid µs/fil");
        int[] htScales = { 500, 1000, 2000, 4000 };
        for (int n : htScales) {
            int reqCap = n * (n - 1) / 2;                              // brute capacity (the O(N²) memory it needs)
            Scene sc = build(n, conc, checkInt, dt, seed, reqCap);
            for (int t = 0; t < 30; t++) step(sc, t, true);           // warm the pose + link population
            sc.fg.buildCpu(sc.fil);
            // grid timing
            long g0 = System.nanoTime(); int reps = 5;
            for (int r = 0; r < reps; r++) { sc.fg.buildCpu(sc.fil); sc.fg.formCandidatesCpu(sc.fil, sc.filID, sc.xl); }
            double gridMs = (System.nanoTime() - g0) / 1e6 / reps;
            int ncGrid = sc.xl.formCounts.get(0);
            // brute timing
            long b0 = System.nanoTime();
            for (int r = 0; r < reps; r++) CrosslinkerSystem.filFilCandidates(sc.fil.coord, sc.fil.segLength, sc.filID, sc.xl.reqFilA, sc.xl.reqFilB, sc.xl.formParams, sc.xl.formCounts);
            double bruteMs = (System.nanoTime() - b0) / 1e6 / reps;
            int ncBrute = sc.xl.formCounts.get(0);
            System.out.printf("  %-8d %-12.3f %-12.3f %-12.1f %-10s %-12.4f%n",
                    n, bruteMs, gridMs, bruteMs / Math.max(1e-6, gridMs), (ncGrid == ncBrute ? "✓(" + ncGrid + ")" : "*DIFF*"), gridMs * 1000.0 / n);
        }
        System.out.println("  (brute ms ∝ N²; grid µs/fil ≈ constant ⇒ O(N) — the quadratic kernel is retired)");

        System.out.println("\n-- grid-only cost up to DENSE scale (CPU; ms/nFil ≈ const ⇒ linear) --");
        System.out.printf("  %-8s %-12s %-12s %-10s %-10s%n", "nFil", "grid(ms)", "ms/nFil", "maxCand", "cands");
        int[] scScales = { 2000, 4000, 8000, 16000, 32000 };
        for (int n : scScales) {
            Scene sc = build(n, conc, checkInt, dt, seed);            // grid-sized reqCap (O(N) memory)
            for (int t = 0; t < 5; t++) step(sc, t, true);
            long g0 = System.nanoTime(); int reps = 4;
            for (int r = 0; r < reps; r++) { sc.fg.buildCpu(sc.fil); sc.fg.formCandidatesCpu(sc.fil, sc.filID, sc.xl); }
            double gridMs = (System.nanoTime() - g0) / 1e6 / reps;
            System.out.printf("  %-8d %-12.3f %-12.5f %-10d %-10d%n",
                    n, gridMs, gridMs / n, sc.fg.maxCandPerSeg(), sc.xl.formCounts.get(0));
        }

        if (!cpu) {
            System.out.println("\n-- GPU device-resident formation at DENSE scale (no-crash / finite / bounded links) --");
            int n = 16000;
            Scene sc = build(n, conc, checkInt, dt, seed);
            for (int t = 0; t < 3 * checkInt; t++) step(sc, t, true);  // warm on CPU
            int beforeLinks = activeLinks(sc.xl);
            runFormationGpu(sc, 3 * checkInt);                          // one device-resident formation step at scale
            boolean finite = true;
            for (int k = 0; k < sc.xl.nLinks; k++) { int a = sc.xl.linkFilA.get(k); if (a < -2 || a >= n) finite = false; }
            System.out.printf("  nFil=%d: GPU formation step OK; active links %d→%d; max partners/seg=%d; arrays sane=%s ✓%n",
                    n, beforeLinks, activeLinks(sc.xl), sc.maxCandSeen, finite ? "yes" : "*NO*");
        }
        System.out.println("\n  BENCH done — formation is O(N), runs at dense scale, conservation/no-crash holds.");
    }

    // ===================================================================== GATE A: FORMATION == BRUTE
    static boolean gateFormationEqualsBrute(int nFil, int M, int checkInt, double conc, double dt, int seed) {
        System.out.println("\n--- GATE A: FORMATION==BRUTE (O(N) grid links == O(N²) brute links, under churn) ---");
        Scene g = build(nFil, conc, checkInt, dt, seed);     // grid formation
        Scene b = build(nFil, conc, checkInt, dt, seed);     // O(N²) brute formation
        int firstDiff = -1, candDiffStep = -1;
        long totalCandPairs = 0, totalLinksFormed = 0;
        int prevActiveG = 0;
        for (int t = 0; t < M; t++) {
            // compare the raw candidate arrays the formation step produced (only on a formation step)
            if (t % checkInt == 0) {
                // run formation candidate fill on BOTH and diff reqFilA/reqFilB before the rest of the pipeline
                g.xl.setFormStep(t, g.seed); b.xl.setFormStep(t, b.seed);
                g.fg.buildCpu(g.fil); g.fg.formCandidatesCpu(g.fil, g.filID, g.xl);
                CrosslinkerSystem.filFilCandidates(b.fil.coord, b.fil.segLength, b.filID, b.xl.reqFilA, b.xl.reqFilB, b.xl.formParams, b.xl.formCounts);
                int ncg = g.xl.formCounts.get(0), ncb = b.xl.formCounts.get(0);
                totalCandPairs += ncb;
                if (candDiffStep < 0) {
                    if (ncg != ncb) candDiffStep = t;
                    else for (int c = 0; c < ncb; c++)
                        if (g.xl.reqFilA.get(c) != b.xl.reqFilA.get(c) || g.xl.reqFilB.get(c) != b.xl.reqFilB.get(c)) { candDiffStep = t; break; }
                }
            }
            step(g, t, true);
            step(b, t, false);
            if (firstDiff < 0 && !sceneEqual(g, b)) firstDiff = t;
            totalLinksFormed += activeLinks(g.xl);
        }
        boolean candOk = candDiffStep < 0;
        boolean sceneOk = firstDiff < 0;
        boolean overflowOk = g.maxCandSeen <= CrosslinkerSystem.FORM_MAXC;
        System.out.printf("  candidate arrays (reqFilA/reqFilB) bit-identical to brute every formation step: %s%n",
                candOk ? "✓" : "*DIFF @step " + candDiffStep + "*");
        System.out.printf("  full scene (links + poses) bit-identical over %d churn steps: %s%n",
                M, sceneOk ? "✓" : "*DIVERGED @step " + firstDiff + "*");
        System.out.printf("  total candidate pairs enumerated=%d; mean active links=%.1f; max partners/seg=%d (cap=%d) %s%n",
                totalCandPairs, totalLinksFormed / (double) M, g.maxCandSeen, CrosslinkerSystem.FORM_MAXC,
                overflowOk ? "✓ no overflow" : "*OVERFLOW — increase FORM_MAXC*");
        boolean ok = candOk && sceneOk && overflowOk;
        System.out.println("  GATE A " + (ok ? "PASS ✓" : "FAIL ✗"));
        return ok;
    }

    static boolean sceneEqual(Scene a, Scene b) {
        CrosslinkerStore xa = a.xl, xb = b.xl;
        for (int k = 0; k < xa.nLinks; k++) {
            if (xa.linkState.get(k) != xb.linkState.get(k)) return false;
            if (xa.linkFilA.get(k) != xb.linkFilA.get(k)) return false;
            if (xa.linkFilB.get(k) != xb.linkFilB.get(k)) return false;
            if (xa.loc1.get(k) != xb.loc1.get(k)) return false;
            if (xa.loc2.get(k) != xb.loc2.get(k)) return false;
        }
        FloatArray ca = a.fil.coord, cb = b.fil.coord;
        for (int i = 0; i < ca.getSize(); i++) if (ca.get(i) != cb.get(i)) return false;
        return true;
    }

    // ===================================================================== GATE B: CPU ≡ GPU formation
    /** Fixed-pose formation (grid build + fused query + full formation pipeline) on the GPU TaskGraph vs the
     *  -cpu runner; the link arrays must be bit-identical (integer wang-hash RNG ⇒ deterministic). */
    static boolean gateCpuGpu(int nFil, int checkInt, double conc, double dt, int seed) {
        System.out.println("\n--- GATE B: CPU≡GPU formation (grid build + fused query + pipeline, fixed pose) ---");
        // warm a moving scene a few formation cadences so the pose + link population are non-trivial, on CPU
        Scene warm = build(nFil, conc, checkInt, dt, seed);
        for (int t = 0; t < 5 * checkInt; t++) step(warm, t, true);
        // snapshot the warmed pose + link state into two fresh scenes (CPU + GPU), run ONE formation step on each
        Scene cpuSc = cloneForFormation(warm, nFil, conc, checkInt, dt, seed);
        Scene gpuSc = cloneForFormation(warm, nFil, conc, checkInt, dt, seed);
        int fstep = 5 * checkInt;
        // CPU
        formation(cpuSc, fstep, true);
        // GPU
        runFormationGpu(gpuSc, fstep);
        boolean ok = formationStateEqual(cpuSc.xl, gpuSc.xl);
        int dbg = countDiffs(cpuSc.xl, gpuSc.xl);
        System.out.printf("  link-array mismatches (linkState/linkFilA/linkFilB/loc1/loc2) CPU vs GPU: %d  %s%n",
                dbg, ok ? "✓ bit-identical" : "*MISMATCH*");
        System.out.println("  GATE B " + (ok ? "PASS ✓" : "FAIL ✗"));
        return ok;
    }

    /** Build a fresh scene and overwrite its pose + link state from `src` (so CPU and GPU start identical). */
    static Scene cloneForFormation(Scene src, int nFil, double conc, int checkInt, double dt, int seed) {
        Scene sc = build(nFil, conc, checkInt, dt, seed);
        copy(src.fil.coord, sc.fil.coord); copy(src.fil.uVec, sc.fil.uVec); copy(src.fil.yVec, sc.fil.yVec);
        copy(src.fil.zVec, sc.fil.zVec); copy(src.fil.end1, sc.fil.end1); copy(src.fil.end2, sc.fil.end2);
        copy(src.fil.segLength, sc.fil.segLength);
        copyI(src.xl.linkState, sc.xl.linkState); copyI(src.xl.linkFilA, sc.xl.linkFilA); copyI(src.xl.linkFilB, sc.xl.linkFilB);
        copy(src.xl.loc1, sc.xl.loc1); copy(src.xl.loc2, sc.xl.loc2);
        copy(src.xl.strainHist, sc.xl.strainHist); copyI(src.xl.strainPlace, sc.xl.strainPlace);
        copyI(src.xl.linkOrientSame, sc.xl.linkOrientSame);
        return sc;
    }

    /** The GPU TaskGraph for ONE formation step: parallel grid build + fused query + the formation pipeline. */
    static void runFormationGpu(Scene sc, int step) {
        FilamentStore f = sc.fil; CrosslinkerStore xl = sc.xl; FormationGrid fg = sc.fg;
        xl.setFormStep(step, sc.seed);
        CrosslinkerSystem.countActiveLinks(xl.linkState, xl.linkFilA, xl.linkFilB, xl.activeLinkCount, xl.formCounts); // start-of-step (host)
        TaskGraph tg = new TaskGraph("xformgrid")
            .transferToDevice(DataTransferMode.FIRST_EXECUTION, f.coord, f.uVec, f.end1, f.end2, f.segLength,
                    sc.filID, fg.view.center, fg.view.boundingRadius, fg.view.ownerStore, fg.view.ownerSlot, fg.viewParams,
                    fg.gridParams, fg.gridDims, fg.bodyCell, fg.cellCount, fg.chunkSum, fg.chunkParams, fg.chunkCellCount,
                    fg.gridCellOffsets, fg.gridCellContents, fg.candCountSeg, fg.candBaseSeg,
                    xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2, xl.linkState, xl.strainHist, xl.strainPlace,
                    xl.linkOrientSame, xl.reqFilA, xl.reqFilB, xl.reqLoc1, xl.reqLoc2, xl.reqOrient, xl.gatePass,
                    xl.acceptFlag, xl.minCand, xl.activeLinkCount, xl.formParams, xl.torqueMagHist, xl.torqueMagPlace,
                    xl.freeCount, xl.freeOffsets, xl.freeList, xl.freeScanCounts, xl.rankOffsets, xl.rankScanCounts, xl.allocCounts)
            .transferToDevice(DataTransferMode.EVERY_EXECUTION, fg.gridCounts, xl.formCounts)
            // ---- parallel grid build (segments-only body view) ----
            .task("filPublish", FilamentStore::publishToBodyView, f.coord, f.segLength, fg.view.center, fg.view.boundingRadius, fg.view.ownerStore, fg.view.ownerSlot, fg.viewParams, fg.gridCounts)
            .task("bodyCell", SpatialGrid::bodyCell, fg.view.center, fg.gridParams, fg.gridDims, fg.gridCounts, fg.bodyCell)
            .task("chunkZero", SpatialGrid::gridChunkZero, fg.chunkParams, fg.gridDims, fg.chunkCellCount)
            .task("chunkHist", SpatialGrid::gridChunkHistogram, fg.bodyCell, fg.gridCounts, fg.chunkParams, fg.gridDims, fg.chunkCellCount)
            .task("chunkReduce", SpatialGrid::gridChunkReduce, fg.gridDims, fg.chunkParams, fg.chunkCellCount, fg.cellCount)
            .task("gScanLocal", SpatialGrid::gridScanLocal, fg.gridDims, fg.cellCount, fg.gridCellOffsets, fg.chunkSum)
            .task("gScanChunks", SpatialGrid::gridScanChunks, fg.gridDims, fg.chunkSum)
            .task("gScanAdd", SpatialGrid::gridScanAdd, fg.gridDims, fg.gridCellOffsets, fg.gridCellContents, fg.cellCount, fg.chunkSum)
            .task("chunkScatter", SpatialGrid::gridChunkScatter, fg.bodyCell, fg.gridCounts, fg.chunkParams, fg.gridDims, fg.gridCellOffsets, fg.gridCellContents, fg.chunkCellCount)
            // ---- fused per-segment formation query ----
            .task("formCount", CrosslinkerSystem::gridFormCount, f.coord, f.segLength, sc.filID, fg.gridParams, fg.gridDims, fg.gridCellOffsets, fg.gridCellContents, fg.candCountSeg, xl.formParams, xl.formCounts)
            .task("formScan", CrosslinkerSystem::gridFormScan, fg.candCountSeg, fg.candBaseSeg, xl.reqFilA, xl.reqFilB, xl.formCounts)
            .task("formEmit", CrosslinkerSystem::gridFormEmit, f.coord, f.segLength, sc.filID, fg.gridParams, fg.gridDims, fg.gridCellOffsets, fg.gridCellContents, fg.candBaseSeg, fg.candCountSeg, xl.reqFilA, xl.reqFilB, xl.formParams, xl.formCounts)
            // ---- formation pipeline (reused VERBATIM) ----
            .task("gates", CrosslinkerSystem::formGates, f.uVec, f.end1, f.end2, f.segLength, xl.reqFilA, xl.reqFilB, xl.reqLoc1, xl.reqLoc2, xl.reqOrient, xl.gatePass, xl.formParams, xl.formCounts)
            .task("admitReduce", CrosslinkerSystem::formAdmitReduce, xl.reqFilA, xl.reqFilB, xl.gatePass, xl.minCand, xl.formCounts)
            .task("admit", CrosslinkerSystem::formAdmit, xl.reqFilA, xl.reqFilB, xl.reqLoc1, xl.reqLoc2, xl.gatePass, xl.minCand, xl.activeLinkCount, xl.linkState, xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2, xl.acceptFlag, xl.formParams, xl.formCounts)
            .task("freeFlags", CrosslinkerSystem::freeFlags, xl.linkState, xl.freeCount, xl.allocCounts)
            .task("scanFree", CrossBridgeSystem::csrScan, xl.freeScanCounts, xl.freeCount, xl.freeOffsets)
            .task("freeScatter", CrosslinkerSystem::freeScatter, xl.linkState, xl.freeOffsets, xl.freeList, xl.allocCounts)
            .task("scanRank", CrossBridgeSystem::csrScan, xl.rankScanCounts, xl.acceptFlag, xl.rankOffsets)
            .task("allocate", CrosslinkerSystem::allocate, xl.reqFilA, xl.reqFilB, xl.reqLoc1, xl.reqLoc2, xl.rankOffsets, xl.freeList, xl.freeOffsets, xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2, xl.linkState, xl.strainHist, xl.strainPlace, xl.allocCounts)
            .task("placeOrient", CrosslinkerSystem::placeOrient, xl.reqOrient, xl.rankOffsets, xl.freeList, xl.freeOffsets, xl.linkOrientSame, xl.torqueMagHist, xl.torqueMagPlace, xl.allocCounts)
            .transferToHost(DataTransferMode.UNDER_DEMAND, xl.linkState, xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2,
                    xl.linkOrientSame, xl.reqFilA, xl.reqFilB, xl.formCounts);
        sched = new GridScheduler();
        int cap = fg.nSeg, totalCells = fg.totalCells;
        addW("xformgrid.filPublish", pad(cap));
        addW("xformgrid.bodyCell", pad(cap));
        addW("xformgrid.chunkZero", pad(fg.numBodyChunks * totalCells));
        addW("xformgrid.chunkHist", pad(fg.numBodyChunks));
        addW("xformgrid.chunkReduce", pad(totalCells));
        addW("xformgrid.gScanLocal", pad((totalCells + SpatialGrid.GRID_SCAN_CHUNK - 1) / SpatialGrid.GRID_SCAN_CHUNK));
        addS("xformgrid.gScanChunks");
        addW("xformgrid.gScanAdd", pad((totalCells + SpatialGrid.GRID_SCAN_CHUNK - 1) / SpatialGrid.GRID_SCAN_CHUNK));
        addW("xformgrid.chunkScatter", pad(fg.numBodyChunks));
        addW("xformgrid.formCount", pad(cap));
        addS("xformgrid.formScan");
        addW("xformgrid.formEmit", pad(cap));
        addW("xformgrid.gates", pad(sc.reqCap));
        addS("xformgrid.admitReduce");
        addW("xformgrid.admit", pad(sc.reqCap));
        addW("xformgrid.freeFlags", pad(sc.C)); addS("xformgrid.scanFree"); addS("xformgrid.freeScatter"); addS("xformgrid.scanRank");
        addW("xformgrid.allocate", pad(sc.reqCap)); addW("xformgrid.placeOrient", pad(sc.reqCap));
        TornadoExecutionPlan plan = new TornadoExecutionPlan(tg.snapshot());
        plan.withGridScheduler(sched).execute();
    }

    static boolean formationStateEqual(CrosslinkerStore a, CrosslinkerStore b) { return countDiffs(a, b) == 0; }
    static int countDiffs(CrosslinkerStore a, CrosslinkerStore b) {
        int d = 0;
        for (int k = 0; k < a.nLinks; k++) {
            if (a.linkState.get(k) != b.linkState.get(k)) d++;
            else if (a.linkState.get(k) >= 0) {
                if (a.linkFilA.get(k) != b.linkFilA.get(k)) d++;
                if (a.linkFilB.get(k) != b.linkFilB.get(k)) d++;
                if (a.loc1.get(k) != b.loc1.get(k)) d++;
                if (a.loc2.get(k) != b.loc2.get(k)) d++;
            }
        }
        return d;
    }

    // ===================================================================== utils
    static int activeLinks(CrosslinkerStore xl) { int c = 0; for (int k = 0; k < xl.nLinks; k++) if (xl.linkState.get(k) >= 0) c++; return c; }
    static void copy(FloatArray s, FloatArray d) { for (int i = 0; i < s.getSize(); i++) d.set(i, s.get(i)); }
    static void copyI(IntArray s, IntArray d) { for (int i = 0; i < s.getSize(); i++) d.set(i, s.get(i)); }
    static final int B = 64;
    static int pad(int g) { return Math.max(B, ((g + B - 1) / B) * B); }
    static void addW(String n, int g) { WorkerGrid w = new WorkerGrid1D(Math.max(B, g)); w.setLocalWork(B, 1, 1); sched.addWorkerGrid(n, w); }
    static void addS(String n) { WorkerGrid w = new WorkerGrid1D(1); w.setLocalWork(1, 1, 1); sched.addWorkerGrid(n, w); }
}
