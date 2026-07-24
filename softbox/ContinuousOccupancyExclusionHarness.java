package softbox;

import softbox.TwoBodyConverterMotor.Glide2D;
import uk.ac.manchester.tornado.api.GridScheduler;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.WorkerGrid;
import uk.ac.manchester.tornado.api.WorkerGrid1D;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.types.arrays.DoubleArray;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

import java.util.Locale;

/**
 * CONTINUOUS LOCAL ACTIN CO-OCCUPANCY EXCLUSION — deterministic unit + geometry tests and CPU/GPU equivalence for
 * the named noncanonical experimental extension ported into the canonical explicit-S2 single-head gliding path.
 *
 * <p>This is NOT a discrete-site model, NOT a monomer lattice, NOT a helical-site model: a continuous
 * minimum-separation rule ({@code |s_candidate − s_bound| < exclusion}) on the filament-GLOBAL material coordinate,
 * comparing a candidate against every already-bound head on the SAME filament (the GLOBAL rule — distinct from the
 * pre-existing sister-head-only {@code ExplicitHmmDimer3jsHarness.occupancyVeto}). See
 * docs/helical_binding/CONTINUOUS_OCCUPANCY_EXCLUSION_FINDINGS.md.
 *
 * <p>Kernels under test (both live on both runners, one implementation): {@link TwoBodyBeamAnalyticGpu#matBindGateOnly}
 * (parallel gate → candidate scratch), {@link TwoBodyBeamAnalyticGpu#matOccupancyResolve} (single-thread serial
 * commit with global exclusion, lowest-head-id-wins same-step conflict resolution), and
 * {@link TwoBodyBeamAnalyticGpu#computeMaterialMaps} (host segCumArc/segFilId precompute).
 *
 *   ./scripts/run_occupancy_exclusion.sh -fixtures   # the 12 deterministic unit + geometry tests (CPU-only, no GPU)
 *   ./scripts/run_occupancy_exclusion.sh -equiv      # single-head explicit-S2 CPU/GPU event-identity (needs GPU)
 *   ./scripts/run_occupancy_exclusion.sh             # both
 */
public final class ContinuousOccupancyExclusionHarness {
    static final double EXCL = 5.4;          // canonical experimental value (nm)
    static final double TOL  = ExplicitCompleteMatHarness.OCC_TOL_NM;   // 1e-3 nm boundary tolerance
    static final double DT    = ExplicitCompleteMatHarness.DT;          // 2.5e-6
    static int passCount = 0, failCount = 0;

    public static void main(String[] args) {
        boolean fx = has(args, "-fixtures"), eq = has(args, "-equiv");
        if (!fx && !eq) { fx = eq = true; }
        boolean fixturesOk = true;
        if (fx) fixturesOk = fixtures();
        if (eq) equivalence(args);
        System.out.printf(Locale.US, "%n=== SUMMARY: %d passed, %d failed ⇒ %s ===%n", passCount, failCount,
                failCount == 0 ? "ALL PASS" : "FAIL");
        System.exit(failCount == 0 ? 0 : 1);
    }

    // ================================================================= §5 deterministic unit + geometry tests
    static boolean fixtures() {
        System.out.println("=== §5 DETERMINISTIC UNIT + GEOMETRY TESTS (excl = 5.4 nm; reject iff sep < excl − tol) ===");
        int ok0 = failCount;

        // Build a real single-filament gliding scene ⇒ real per-segment lengths + linear chain topology.
        Glide2D G = TwoBodyConverterMotor.buildS2Mat(300, DT, 40.0, TwoBodyConverterMotor.EXPLICIT_GLIDE_SLACK_NM, 101);
        FilamentStore f = G.fil; int nSeg = G.nSeg;
        FloatArray segCumArc = new FloatArray(nSeg); IntArray segFilId = new IntArray(nSeg);
        TwoBodyBeamAnalyticGpu.computeMaterialMaps(f, nSeg, segCumArc, segFilId);
        double segLenMid = f.segLength.get(nSeg / 2);

        // ---- 7. Half-open ownership: material coordinate is CONTINUOUS at every segment boundary (no gap/overlap) ----
        //    matCoord(k, segLen[k]) == matCoord(k+1, 0)  ⇒ the pointed-end of seg k+1 == the barbed-end of seg k.
        boolean cont = true; double maxGap = 0;
        for (int k = 0; k < nSeg - 1; k++) {
            double barbedK = segCumArc.get(k) + f.segLength.get(k);
            double pointedK1 = segCumArc.get(k + 1) + 0.0;
            maxGap = Math.max(maxGap, Math.abs(barbedK - pointedK1) * 1e3);
        }
        cont = maxGap < 1e-2;   // float-ULP at ~2 µm ≈ 2e-4 nm; the rule scale is 5.4 nm ⇒ 0.01 nm is negligible
        expect("7  half-open ownership: material coordinate continuous across all boundaries (maxGap " + fmt(maxGap) + " nm)", cont, true);

        // ---- 8. Polarity/material-coordinate consistency: monotone increasing along the chain (pointed→barbed) ----
        boolean mono = true;
        for (int k = 0; k < nSeg - 1; k++) if (segCumArc.get(k + 1) <= segCumArc.get(k)) mono = false;
        expect("8a material coordinate strictly increases along the chain (single filament id 0)", mono && segFilId.get(0) == 0, true);
        // reversed index-order chain: same physical filament, opposite index labelling ⇒ material order must follow
        //   TOPOLOGY (end2NbrSlot), not index. Build a 4-seg chain whose pointed terminal is the HIGHEST index.
        {
            FilamentStore fr = new FilamentStore(4);
            for (int k = 0; k < 4; k++) { fr.monomerCount.set(k, 64); fr.setUVec(k, 1f, 0f, 0f); fr.setYVec(k, 0f, 1f, 0f); fr.setCoord(k, k * 0.176f, 0f, 0f); }
            DragTensorSystem.run(fr); fr.setChainParams(DT);
            DerivedGeometrySystem.derive(fr.coord, fr.uVec, fr.yVec, fr.zVec, fr.end1, fr.end2, fr.segLength, fr.counts);
            // chain: 3 → 2 → 1 → 0 (index 3 is the pointed terminal; walk via end2NbrSlot toward 0)
            for (int k = 3; k >= 1; k--) { fr.end2NbrSlot.set(k, k - 1); fr.end2NbrSide.set(k, 0); fr.end1NbrSlot.set(k - 1, k); fr.end1NbrSide.set(k - 1, 1); }
            FloatArray rc = new FloatArray(4); IntArray rf = new IntArray(4);
            TwoBodyBeamAnalyticGpu.computeMaterialMaps(fr, 4, rc, rf);
            // material coordinate must DECREASE with index (pointed terminal = index 3 ⇒ arc 0)
            boolean revOk = rc.get(3) == 0f && rc.get(2) > rc.get(3) && rc.get(1) > rc.get(2) && rc.get(0) > rc.get(1)
                    && rf.get(0) == 0 && rf.get(3) == 0;
            expect("8b reversed-index chain: material order follows topology not index (pointed=seg3, arc 0)", revOk, true);
        }

        // ---- resolve-based decision tests (1–6, 9–12) on filament 0 ----
        int cSeg = nSeg / 2;                             // a mid segment on the single filament (id 0)
        double baseArc = 0.5 * segLenMid;                // partner at the segment mid
        double partnerMat = segCumArc.get(cSeg) + baseArc;   // µm

        // 1. Same coordinate → REJECT
        expect("1  same coordinate (0 nm) → REJECT", commits(segCumArc, segFilId, nSeg,
                new int[]{ cSeg }, new double[]{ baseArc },                 // pre-bound partner
                cSeg, baseArc, EXCL), false);
        // 2. Below threshold (5.4 − 0.1 nm) → REJECT
        expect("2  below threshold (5.3 nm) → REJECT", commits(segCumArc, segFilId, nSeg,
                new int[]{ cSeg }, new double[]{ baseArc }, cSeg, baseArc + 5.3e-3, EXCL), false);
        // 3. At threshold (exactly 5.4 nm) → ACCEPT (reject only when sep < excl − tol)
        expect("3  at threshold (5.4 nm) → ACCEPT", commits(segCumArc, segFilId, nSeg,
                new int[]{ cSeg }, new double[]{ baseArc }, cSeg, baseArc + EXCL * 1e-3, EXCL), true);
        // 4. Above threshold (5.5 nm) → ACCEPT
        expect("4  above threshold (5.5 nm) → ACCEPT", commits(segCumArc, segFilId, nSeg,
                new int[]{ cSeg }, new double[]{ baseArc }, cSeg, baseArc + 5.5e-3, EXCL), true);

        // 5. Different filament (identical material coordinate) → ACCEPT. Build a 2-filament scene.
        {
            FloatArray sc = new FloatArray(4); IntArray sf = new IntArray(4);
            // two 2-seg filaments: seg0,seg1 = fil 0 ; seg2,seg3 = fil 1 ; identical cum arc per filament
            sc.set(0, 0f); sc.set(1, 0.176f); sc.set(2, 0f); sc.set(3, 0.176f);
            sf.set(0, 0); sf.set(1, 0); sf.set(2, 1); sf.set(3, 1);
            // pre-bound head on fil 0 (seg 0, arc 0.05); candidate on fil 1 (seg 2, arc 0.05) — same material coord, other filament
            expect("5  different filament, identical material coord → ACCEPT", commits(sc, sf, 4,
                    new int[]{ 0 }, new double[]{ 0.05 }, 2, 0.05, EXCL), true);
            // sanity: SAME filament, same material coord → REJECT (pre-bound seg 0 arc 0.05 vs candidate seg 0 arc 0.05)
            expect("5b same filament, identical material coord → REJECT", commits(sc, sf, 4,
                    new int[]{ 0 }, new double[]{ 0.05 }, 0, 0.05, EXCL), false);
        }

        // 6. Segment-boundary continuity: pre-bound 1.5 nm from barbed end of seg k; candidate 1.5 nm into seg k+1 ⇒
        //    3 nm apart across the boundary → REJECT (global material coordinate, NOT segment index).  And 6 nm → ACCEPT.
        {
            int k = cSeg; double lenK = f.segLength.get(k);
            double preArc = lenK - 0.0015;                 // 1.5 nm from barbed end of seg k
            double cand3 = 0.0015;                          // 1.5 nm into seg k+1 ⇒ 3 nm apart
            double cand6 = 0.0045;                          // 4.5 nm into seg k+1 ⇒ 6 nm apart
            double sep3 = Math.abs((segCumArc.get(k + 1) + cand3) - (segCumArc.get(k) + preArc)) * 1e3;
            double sep6 = Math.abs((segCumArc.get(k + 1) + cand6) - (segCumArc.get(k) + preArc)) * 1e3;
            expect("6  cross-boundary continuous separations (" + fmt(sep3) + ", " + fmt(sep6) + " nm ≈ 3, 6)",
                    Math.abs(sep3 - 3.0) < 0.05 && Math.abs(sep6 - 6.0) < 0.05, true);
            expect("6a cross-boundary 3 nm → REJECT", commits(segCumArc, segFilId, nSeg,
                    new int[]{ k }, new double[]{ preArc }, k + 1, cand3, EXCL), false);
            expect("6b cross-boundary 6 nm → ACCEPT", commits(segCumArc, segFilId, nSeg,
                    new int[]{ k }, new double[]{ preArc }, k + 1, cand6, EXCL), true);
        }

        // 9. Candidate self-exclusion: a lone candidate (no other bound head) commits — it must not veto itself.
        expect("9  candidate does not veto itself (lone candidate commits)", commits(segCumArc, segFilId, nSeg,
                new int[]{}, new double[]{}, cSeg, baseArc, EXCL), true);

        // 10 + 11. Same-step conflict / dimer-sister semantics: two simultaneous candidates 2.7 nm apart on the same
        //    filament ⇒ exactly ONE commits (lower head-id wins); conflicts counter == 1. Then 6 nm apart ⇒ both.
        {
            int[] r = twoSimultaneous(segCumArc, segFilId, nSeg, cSeg, baseArc, cSeg, baseArc + 2.7e-3, EXCL);
            expect("10/11 two simultaneous candidates 2.7 nm apart: exactly one accepted", r[0] + r[1] == 1, true);
            expect("11 same-step conflict: LOWER head-id wins (head 0)", r[0] == 1 && r[1] == 0, true);
            expect("11 same-step conflict counter == 1", r[2] == 1, true);
            int[] rSwap = twoSimultaneous(segCumArc, segFilId, nSeg, cSeg, baseArc + 2.7e-3, cSeg, baseArc, EXCL);
            expect("11 A/B relabel: still exactly one, head 0 wins (deterministic, no RNG)", rSwap[0] == 1 && rSwap[1] == 0, true);
            int[] rFar = twoSimultaneous(segCumArc, segFilId, nSeg, cSeg, baseArc, cSeg, baseArc + 6.0e-3, EXCL);
            expect("10 two candidates 6 nm apart: BOTH accepted", rFar[0] == 1 && rFar[1] == 1 && rFar[2] == 0, true);
        }
        // 10b sister explicitly participates: a pre-bound sister (dimer partner) on the same filament DOES exclude
        //     the candidate under the GLOBAL rule (documented divergence from the sister-only legacy veto: global ⊇ sister).
        expect("10b bound sister on same filament participates (excludes within threshold)", commits(segCumArc, segFilId, nSeg,
                new int[]{ cSeg }, new double[]{ baseArc }, cSeg, baseArc + 2.0e-3, EXCL), false);

        // 12. OFF-path identity: gate-only + resolve(excl=0) commits EXACTLY the canonical matBindExplicit set.
        expect("12 OFF-path identity (excl=0): gateOnly+resolve ≡ matBindExplicit bit-identical binds", offPathIdentity(), true);

        boolean allOk = failCount == ok0;
        System.out.println(allOk ? "  §5 UNIT/GEOMETRY TESTS: ALL PASS\n" : "  §5 UNIT/GEOMETRY TESTS: FAIL\n");
        return allOk;
    }

    /** Run matOccupancyResolve for ONE candidate head (id 1) against a set of pre-bound heads; return true iff the
     *  candidate committed (bound). Pre-bound heads occupy slots 2..; head 0 is unused. Deterministic serial pass. */
    static boolean commits(FloatArray segCumArc, IntArray segFilId, int nSeg,
                           int[] preSeg, double[] preArcUm, int candSeg, double candArcUm, double exclNm) {
        int N = 2 + preSeg.length;
        IntArray candInt = new IntArray(2 * N); candInt.init(0);
        DoubleArray candArc = new DoubleArray(N); candArc.init(0.0);
        IntArray boundSeg = new IntArray(N); FloatArray bindArc = new FloatArray(N);
        for (int m = 0; m < N; m++) boundSeg.set(m, -1);
        // candidate = head 1
        candInt.set(1, candSeg); candInt.set(N + 1, 1); candArc.set(1, candArcUm);
        // pre-bound heads = slots 2..N-1
        for (int i = 0; i < preSeg.length; i++) { int m = 2 + i; boundSeg.set(m, preSeg[i]); bindArc.set(m, (float) preArcUm[i]); }
        DoubleArray occP = DoubleArray.fromElements(exclNm, TOL); IntArray occStats = new IntArray(4);
        IntArray counts = IntArray.fromElements(N, 1, 0, nSeg);
        TwoBodyBeamAnalyticGpu.matOccupancyResolve(candInt, candArc, boundSeg, bindArc, segCumArc, segFilId, occP, occStats, counts);
        return boundSeg.get(1) >= 0;
    }

    /** Two simultaneous candidates (heads 0 and 1) on the same filament; return {accepted0, accepted1, conflicts}. */
    static int[] twoSimultaneous(FloatArray segCumArc, IntArray segFilId, int nSeg,
                                 int seg0, double arc0Um, int seg1, double arc1Um, double exclNm) {
        int N = 2;
        IntArray candInt = new IntArray(2 * N); candInt.init(0);
        DoubleArray candArc = new DoubleArray(N); candArc.init(0.0);
        IntArray boundSeg = new IntArray(N); FloatArray bindArc = new FloatArray(N);
        for (int m = 0; m < N; m++) boundSeg.set(m, -1);
        candInt.set(0, seg0); candInt.set(N + 0, 1); candArc.set(0, arc0Um);
        candInt.set(1, seg1); candInt.set(N + 1, 1); candArc.set(1, arc1Um);
        DoubleArray occP = DoubleArray.fromElements(exclNm, TOL); IntArray occStats = new IntArray(4);
        IntArray counts = IntArray.fromElements(N, 1, 0, nSeg);
        TwoBodyBeamAnalyticGpu.matOccupancyResolve(candInt, candArc, boundSeg, bindArc, segCumArc, segFilId, occP, occStats, counts);
        return new int[]{ boundSeg.get(0) >= 0 ? 1 : 0, boundSeg.get(1) >= 0 ? 1 : 0, occStats.get(3) };
    }

    /** OFF-path identity over a REAL multi-step binding trajectory: the gate-only→serial-resolve pipeline at
     *  exclusion 0 must reproduce canonical matBindExplicit binding decisions BIT-IDENTICALLY every step. Twin A runs
     *  the canonical bind (matBindExplicit); twin B runs gate-only+resolve(0) (via the OCC_FORCE_ON test hook). Both
     *  CPU, same seed ⇒ identical arithmetic and, since resolve(0) never vetoes, boundSeg must match every step. */
    static boolean offPathIdentity() {
        int density = 400, steps = 600, seed = 202;
        Glide2D Ga = TwoBodyConverterMotor.buildS2Mat(density, DT, 40.0, TwoBodyConverterMotor.EXPLICIT_GLIDE_SLACK_NM, seed);
        Glide2D Gb = TwoBodyConverterMotor.buildS2Mat(density, DT, 40.0, TwoBodyConverterMotor.EXPLICIT_GLIDE_SLACK_NM, seed);
        int N = Ga.N, nSeg = Ga.nSeg;
        for (Glide2D G : new Glide2D[]{ Ga, Gb }) for (int m = 0; m < N; m++) G.mot.boundSeg.set(m, -1);
        // twin A: canonical (matBindExplicit)
        ExplicitCompleteMatHarness.OCC_EXCL_NM = 0.0; ExplicitCompleteMatHarness.OCC_FORCE_ON = false;
        ExplicitCompleteMatHarness.ExMat ea = ExplicitCompleteMatHarness.packExMat(Ga, 1);
        // twin B: gate-only + resolve(excl=0) via the test hook
        ExplicitCompleteMatHarness.OCC_FORCE_ON = true;
        ExplicitCompleteMatHarness.ExMat eb = ExplicitCompleteMatHarness.packExMat(Gb, 1);
        int mism = 0, firstDiv = -1; long binds = 0;
        for (int t = 0; t < steps; t++) {
            ExplicitCompleteMatHarness.OCC_FORCE_ON = false; ExplicitCompleteMatHarness.stepGlidingCPU(ea, t, seed);
            ExplicitCompleteMatHarness.OCC_FORCE_ON = true;  ExplicitCompleteMatHarness.stepGlidingCPU(eb, t, seed);
            int nb = 0;
            for (int m = 0; m < N; m++) {
                int bA = Ga.mot.boundSeg.get(m), bB = Gb.mot.boundSeg.get(m);
                if (bA >= 0) nb++;
                if (bA != bB) { mism++; if (firstDiv < 0) firstDiv = t; }
                else if (bA >= 0 && Ga.mot.bindArc.get(m) != Gb.mot.bindArc.get(m)) { mism++; if (firstDiv < 0) firstDiv = t; }
            }
            binds = Math.max(binds, nb);
        }
        ExplicitCompleteMatHarness.OCC_FORCE_ON = false;
        System.out.printf(Locale.US, "     (OFF-path: %d steps, peak bound %d; boundSeg/bindArc mismatches = %d, firstDiv=%s)%n",
                steps, binds, mism, firstDiv < 0 ? "none" : "" + firstDiv);
        return mism == 0 && binds > 0;
    }

    // ================================================================= §6 CPU/GPU equivalence
    static void equivalence(String[] args) {
        deterministicResolveEquivalence();
        pipelineEquivalence(args);
    }

    /** §6A DETERMINISTIC resolve-kernel CPU↔GPU bit-identity on a FIXED constructed input (no chaotic evolution) —
     *  a mix engineered to exercise rejects, accepts, AND a same-step conflict. Runs matOccupancyResolve on the CPU
     *  runner and on a single-task device graph over IDENTICAL inputs; boundSeg + occStats must be bit-identical. */
    static void deterministicResolveEquivalence() {
        System.out.println("=== §6A DETERMINISTIC RESOLVE-KERNEL CPU↔GPU BIT-IDENTITY (constructed fixture) ===");
        int nSeg = 4, N = 6;
        FloatArray segCumArc = new FloatArray(nSeg); IntArray segFilId = new IntArray(nSeg);
        segCumArc.set(0, 0f); segCumArc.set(1, 0.176f); segCumArc.set(2, 0f); segCumArc.set(3, 0.176f);   // two 2-seg filaments
        segFilId.set(0, 0); segFilId.set(1, 0); segFilId.set(2, 1); segFilId.set(3, 1);
        // head 2 pre-bound on fil0 seg1 arc0.05 (mat 0.226); head 4 pre-bound on fil1 seg3 arc0.05 (mat 0.226 fil1).
        // candidates: h0 fil0 mat 0.228 (2 nm from h2 → REJECT); h1 fil1 mat 0.234 (8 nm from h4 → ACCEPT);
        //             h3 fil0 mat 0.2295 (~1.5nm from h2 AND, if h1 bound... different fil; vs h0 if h0 bound: h0 rejected so no);
        //             h5 fil0 mat 0.2280 (same as h0 → same-step conflict with h0 if h0 accepted; but h0 rejected by h2).
        double[] cand = new double[N]; int[] cseg = new int[N]; int[] cacc = new int[N];
        int[] pbSeg = new int[N]; double[] pbArc = new double[N];
        for (int m = 0; m < N; m++) { pbSeg[m] = -1; cacc[m] = 0; }
        pbSeg[2] = 1; pbArc[2] = 0.05;                 // pre-bound fil0
        pbSeg[4] = 3; pbArc[4] = 0.05;                 // pre-bound fil1
        cseg[0] = 1; cand[0] = 0.052; cacc[0] = 1;     // fil0 mat 0.228 → 2 nm from h2 (0.226) → REJECT
        cseg[1] = 3; cand[1] = 0.058; cacc[1] = 1;     // fil1 mat 0.234 → 8 nm from h4 → ACCEPT
        cseg[3] = 0; cand[3] = 0.170; cacc[3] = 1;     // fil0 mat 0.170 → far from h2 → ACCEPT
        cseg[5] = 0; cand[5] = 0.171; cacc[5] = 1;     // fil0 mat 0.171 → 1 nm from h3(0.170) accepted this step → same-step conflict → REJECT
        // build CPU inputs
        IntArray candInt = new IntArray(2 * N), boundSeg = new IntArray(N); FloatArray bindArc = new FloatArray(N);
        DoubleArray candArc = new DoubleArray(N);
        for (int m = 0; m < N; m++) { candInt.set(m, cseg[m]); candInt.set(N + m, cacc[m]); candArc.set(m, cand[m]);
            boundSeg.set(m, pbSeg[m]); bindArc.set(m, (float) pbArc[m]); }
        DoubleArray occP = DoubleArray.fromElements(EXCL, TOL); IntArray occStats = new IntArray(4);
        IntArray counts = IntArray.fromElements(N, 1, 0, nSeg);
        // CPU
        TwoBodyBeamAnalyticGpu.matOccupancyResolve(candInt, candArc, boundSeg, bindArc, segCumArc, segFilId, occP, occStats, counts);
        int[] cpuBound = new int[N]; float[] cpuArc = new float[N]; int[] cpuStat = new int[4];
        for (int m = 0; m < N; m++) { cpuBound[m] = boundSeg.get(m); cpuArc[m] = bindArc.get(m); }
        for (int k = 0; k < 4; k++) cpuStat[k] = occStats.get(k);
        // GPU: identical fresh inputs, one-task device graph, single-thread resolve
        IntArray gCand = new IntArray(2 * N), gBound = new IntArray(N), gStat = new IntArray(4); FloatArray gArc = new FloatArray(N);
        DoubleArray gCandArc = new DoubleArray(N), gOccP = DoubleArray.fromElements(EXCL, TOL);
        FloatArray gCum = new FloatArray(nSeg); IntArray gFil = new IntArray(nSeg), gCounts = IntArray.fromElements(N, 1, 0, nSeg);
        for (int m = 0; m < N; m++) { gCand.set(m, cseg[m]); gCand.set(N + m, cacc[m]); gCandArc.set(m, cand[m]);
            gBound.set(m, pbSeg[m]); gArc.set(m, (float) pbArc[m]); }
        for (int s = 0; s < nSeg; s++) { gCum.set(s, segCumArc.get(s)); gFil.set(s, segFilId.get(s)); }
        TaskGraph tg = new TaskGraph("occeq");
        tg.transferToDevice(DataTransferMode.FIRST_EXECUTION, gCand, gCandArc, gBound, gArc, gCum, gFil, gOccP, gStat, gCounts);
        tg.task("occResolve", TwoBodyBeamAnalyticGpu::matOccupancyResolve, gCand, gCandArc, gBound, gArc, gCum, gFil, gOccP, gStat, gCounts);
        tg.transferToHost(DataTransferMode.EVERY_EXECUTION, gBound, gArc, gStat);
        GridScheduler sc = new GridScheduler(); WorkerGrid w = new WorkerGrid1D(64); w.setLocalWork(64, 1, 1); sc.addWorkerGrid("occeq.occResolve", w);
        try (TornadoExecutionPlan plan = new TornadoExecutionPlan(tg.snapshot()).withGridScheduler(sc)) {
            plan.execute();
        } catch (Throwable e) { System.out.println("  GPU resolve fixture ERROR: " + e); expect("§6A GPU resolve executed", false, true); return; }
        int mism = 0;
        for (int m = 0; m < N; m++) { if (cpuBound[m] != gBound.get(m)) mism++; else if (cpuBound[m] >= 0 && cpuArc[m] != gArc.get(m)) mism++; }
        for (int k = 0; k < 4; k++) if (cpuStat[k] != gStat.get(k)) mism++;
        System.out.printf(Locale.US, "  CPU commits: h0=%d h1=%d h3=%d h5=%d | stats cand=%d rej=%d acc=%d conf=%d%n",
                cpuBound[0], cpuBound[1], cpuBound[3], cpuBound[5], cpuStat[0], cpuStat[1], cpuStat[2], cpuStat[3]);
        System.out.printf(Locale.US, "  GPU commits: h0=%d h1=%d h3=%d h5=%d | stats cand=%d rej=%d acc=%d conf=%d%n",
                gBound.get(0), gBound.get(1), gBound.get(3), gBound.get(5), gStat.get(0), gStat.get(1), gStat.get(2), gStat.get(3));
        // expected: h0 REJECT(<0), h1 ACCEPT, h3 ACCEPT, h5 REJECT(same-step conflict); cand=4 rej=2 acc=2 conf=1
        boolean sem = cpuBound[0] < 0 && cpuBound[1] >= 0 && cpuBound[3] >= 0 && cpuBound[5] < 0
                && cpuStat[0] == 4 && cpuStat[1] == 2 && cpuStat[2] == 2 && cpuStat[3] == 1;
        expect("§6A resolve semantics correct (2 rejects incl. 1 same-step conflict, 2 accepts)", sem, true);
        expect("§6A resolve kernel bit-identical CPU↔GPU (boundSeg + occStats, 0 mismatches)", mism == 0, true);
        System.out.println();
    }

    // ----------------------------------------------------------------- §6B real-pipeline event-identity
    static void pipelineEquivalence(String[] args) {
        System.out.println("=== §6B CPU/GPU SINGLE-HEAD PIPELINE EVENT-IDENTITY (explicit-s2-l40, occupancy 5.4 nm ON) ===");
        int density = ExplicitCompleteMatHarness.argI(args, "-density", 700);
        int seed = ExplicitCompleteMatHarness.argI(args, "-seed", 101);
        int steps = ExplicitCompleteMatHarness.argI(args, "-eqsteps", 400);
        ExplicitCompleteMatHarness.OCC_EXCL_NM = EXCL;

        Glide2D Gc = TwoBodyConverterMotor.buildS2Mat(density, DT, 40.0, TwoBodyConverterMotor.EXPLICIT_GLIDE_SLACK_NM, seed);
        Glide2D Gd = TwoBodyConverterMotor.buildS2Mat(density, DT, 40.0, TwoBodyConverterMotor.EXPLICIT_GLIDE_SLACK_NM, seed);
        int N = Gc.N, nSeg = Gc.nSeg;
        for (Glide2D G : new Glide2D[]{ Gc, Gd }) for (int m = 0; m < N; m++) G.mot.boundSeg.set(m, -1);
        ExplicitCompleteMatHarness.ExMat ec = ExplicitCompleteMatHarness.packExMat(Gc, 1);
        ExplicitCompleteMatHarness.ExMat ed = ExplicitCompleteMatHarness.packExMat(Gd, 1);

        TornadoExecutionPlan plan = ExplicitCompleteMatHarness.buildGlidingGraph(ed, false);   // validation residency (mirrors boundSeg)

        int firstDiv = -1, invalid = 0; long cRejRun = 0, gRejRun = 0, cAccRun = 0, gAccRun = 0;
        long inWinCand = 0, inWinRej = 0, inWinAcc = 0, occStatDiverge = 0;
        for (int t = 0; t < steps; t++) {
            ExplicitCompleteMatHarness.stepGlidingCPU(ec, t, seed);
            ExplicitCompleteMatHarness.setCounters(0, ed, null, Gd, t, seed, nSeg);
            plan.execute();
            cRejRun += ec.occStats.get(1); gRejRun += ed.occStats.get(1);
            cAccRun += ec.occStats.get(2); gAccRun += ed.occStats.get(2);
            // event-identity while geometry is bit-close: boundSeg exact + occStats exact
            int mism = 0;
            for (int m = 0; m < N; m++) if (Gc.mot.boundSeg.get(m) != Gd.mot.boundSeg.get(m)) { mism++; }
            if (mism > 0 && firstDiv < 0) firstDiv = t;
            if (firstDiv < 0) {   // still bit-close ⇒ geometry identical ⇒ occupancy decisions MUST match exactly
                inWinCand += ec.occStats.get(0); inWinRej += ec.occStats.get(1); inWinAcc += ec.occStats.get(2);
                for (int k = 0; k < 4; k++) if (ec.occStats.get(k) != ed.occStats.get(k)) occStatDiverge++;
            }
            for (int i = 0; i < 3 * nSeg; i++) { float c = Gc.fil.coord.get(i); if (Float.isNaN(c) || Float.isInfinite(c)) { invalid++; break; } }
        }
        System.out.printf(Locale.US, "  N=%d nSeg=%d density=%d seed=%d steps=%d%n", N, nSeg, density, seed, steps);
        System.out.printf(Locale.US, "  boundSeg bit-identical CPU↔GPU for %s steps then chaotic FP decorrelation%n",
                firstDiv < 0 ? "all " + steps : "" + firstDiv);
        System.out.printf(Locale.US, "  in-window (boundSeg bit-close) occupancy decisions: candidates=%d rejects=%d accepted=%d | CPU↔GPU occStat divergences=%d%n",
                inWinCand, inWinRej, inWinAcc, occStatDiverge);
        System.out.printf(Locale.US, "  whole-run occupancy: CPU rejects=%d accepted=%d | GPU rejects=%d accepted=%d | invalid=%d%n",
                cRejRun, cAccRun, gRejRun, gAccRun, invalid);
        // In the real gliding pipeline the two runners share ONE physics implementation but decorrelate by float32
        // op-ordering on a chaotic trajectory. The rigorous kernel bit-identity is §6A (constructed, chaos-free);
        // here we confirm the ON pipeline (a) integrates on the device (no CPU fallback), (b) excludes over the run,
        // (c) is bind-event-identical while the microstate is bit-close (firstDiv > 0), (d) stays numerically healthy.
        expect("§6B occupancy exclusion mechanism fires in the real pipeline (CPU rejects > 0)", cRejRun > 0, true);
        expect("§6B GPU accepts binds device-resident (no silent CPU fallback)", gAccRun > 0, true);
        expect("§6B bind trajectory bit-identical CPU↔GPU for ≥1 step before chaotic decorrelation", firstDiv != 0, true);
        expect("§6B no invalid states / solver failures", invalid == 0, true);
        ExplicitCompleteMatHarness.OCC_EXCL_NM = 0.0;   // reset
    }

    // ================================================================= helpers
    static void expect(String label, boolean got, boolean want) {
        boolean pass = got == want; if (pass) passCount++; else failCount++;
        System.out.printf(Locale.US, "  [%s] %s%n", pass ? "PASS" : "FAIL", label);
    }
    static String fmt(double v) { return String.format(Locale.US, "%.3f", v); }
    static boolean has(String[] a, String f) { for (String s : a) if (s.equals(f)) return true; return false; }
    private ContinuousOccupancyExclusionHarness() {}
}
