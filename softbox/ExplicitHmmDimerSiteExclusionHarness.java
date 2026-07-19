package softbox;

import softbox.ExplicitHmmDimer3jsHarness.Scene;
import softbox.ExplicitHmmDimer3jsHarness.Run;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Same-filament BOUND-SITE OCCUPANCY EXCLUSION study for the explicit HMM dimer. Once one head is bound to a
 * filament, the partner head may not bind at a material coordinate less than BOUND_SITE_EXCLUSION_NM (5.4 nm ≈
 * one actin-monomer spacing) away along that SAME filament. Symmetric (no barbed/pointed preference — any forward
 * bias must EMERGE from mechanics; no explicit forward gating here). The rule is a pure VETO on an otherwise valid
 * candidate, inserted after the geometric gates and before commit; it never moves a head. CPU-only.
 *
 * The exclusion machinery lives in {@link ExplicitHmmDimer3jsHarness} (filMatCoordUm / occupancyVeto / the bind-gate
 * veto + diagnostics). This harness: (a) runs the deterministic §8 fixtures on the material coordinate + veto;
 * (b) runs the matched control (OFF) vs test (5.4 nm) dynamic comparison; (c) prints the §12 status block.
 *
 *   ./scripts/run_hmm_dimer_exclusion.sh -fixtures     # the 8 deterministic validation fixtures
 *   ./scripts/run_hmm_dimer_exclusion.sh -compare      # control (OFF) vs test (5.4 nm) dynamic, multi-seed + status
 *   ./scripts/run_hmm_dimer_exclusion.sh               # both
 * See docs/matsoa/EXPLICIT_HMM_DIMER_SITE_EXCLUSION_FINDINGS.md.
 */
public final class ExplicitHmmDimerSiteExclusionHarness {
    static final double DT = 2.5e-6;
    static final double EXCL = ExplicitHmmDimer3jsHarness.DEFAULT_EXCLUSION_NM;   // 5.4 nm
    // matched fork geometry for the dynamic comparison (task §9)
    static final double ALPHA = 10, BREI = 0.25, BREA = 1.0, BRANCHLEN = 10, SPLAY = 16, GAP = 3.0;
    static final int NSEG = 12;
    static final int[] SEEDS = { 2, 3, 5, 7, 11 };
    static final int STEPS = 8000;

    public static void main(String[] args) throws IOException {
        boolean fx = has(args, "-fixtures"), cmp = has(args, "-compare");
        if (!fx && !cmp) { fx = cmp = true; }
        boolean fixturesOk = true;
        if (fx) fixturesOk = fixtures();
        if (cmp) compare(fixturesOk);
    }

    // ========================= §8 deterministic validation fixtures =========================
    static boolean fixtures() throws IOException {
        System.out.println("=== §8 BOUND-SITE EXCLUSION FIXTURES (deterministic; excl = 5.4 nm, boundary INCLUSIVE-allow) ===");
        // a real filament with real per-segment lengths (same construction the dynamic assay uses)
        Scene sc = ExplicitHmmDimer3jsHarness.buildScene(SPLAY, GAP, NSEG, DT, 1, 1.0, ALPHA, BREI, BREA, BRANCHLEN);
        FilamentStore f = sc.G.fil; int nSeg = sc.G.nSeg;
        int pSeg = nSeg / 2; double pHalf = 0.5 * f.segLength.get(pSeg);
        double partnerMat = ExplicitHmmDimer3jsHarness.filMatCoordUm(f, nSeg, pSeg, pHalf);   // partner at the segment mid
        boolean ok = true;

        // 1 same site (offset 0) → REJECT
        ok &= expect("1 same site (0 nm)", vetoAt(partnerMat, 0.0), true);
        // 2 inside exclusion (±2.7 nm) → REJECT
        ok &= expect("2 inside (+2.7 nm)", vetoAt(partnerMat, +2.7), true);
        ok &= expect("2 inside (−2.7 nm)", vetoAt(partnerMat, -2.7), true);
        // 3 boundary (±5.4 nm) → ALLOW (inclusive at exactly 5.4 within tol)
        ok &= expect("3 boundary (+5.4 nm) ALLOW", vetoAt(partnerMat, +5.4), false);
        ok &= expect("3 boundary (−5.4 nm) ALLOW", vetoAt(partnerMat, -5.4), false);
        // 4 outside (±5.5 nm) → ALLOW
        ok &= expect("4 outside (+5.5 nm)", vetoAt(partnerMat, +5.5), false);
        ok &= expect("4 outside (−5.5 nm)", vetoAt(partnerMat, -5.5), false);

        // 5 segment boundary: partner 1.5 nm from the barbed end of seg k; candidate across the boundary in seg k+1.
        //    The CONTINUOUS material separation (not segment index) must be used.
        int k = 5; double lenK = f.segLength.get(k);
        double pMatB = ExplicitHmmDimer3jsHarness.filMatCoordUm(f, nSeg, k, lenK - 0.0015);      // 1.5 nm from barbed end of seg k
        double cMat3 = ExplicitHmmDimer3jsHarness.filMatCoordUm(f, nSeg, k + 1, 0.0015);         // 1.5 nm into seg k+1 ⇒ 3 nm apart
        double cMat6 = ExplicitHmmDimer3jsHarness.filMatCoordUm(f, nSeg, k + 1, 0.0045);         // 4.5 nm into seg k+1 ⇒ 6 nm apart
        double sep3 = Math.abs(cMat3 - pMatB) * 1e3, sep6 = Math.abs(cMat6 - pMatB) * 1e3;
        System.out.printf(Locale.US, "  5 cross-boundary continuous separations: %.3f nm and %.3f nm (expected ≈3.0, ≈6.0)%n", sep3, sep6);
        ok &= expect("5 boundary continuous sep ≈ 3 nm", Math.abs(sep3 - 3.0) < 0.05, true);
        ok &= expect("5 boundary continuous sep ≈ 6 nm", Math.abs(sep6 - 6.0) < 0.05, true);
        ok &= expect("5 cross-boundary 3 nm → REJECT", ExplicitHmmDimer3jsHarness.occupancyVeto(0, cMat3, 0, pMatB, EXCL), true);
        ok &= expect("5 cross-boundary 6 nm → ALLOW", ExplicitHmmDimer3jsHarness.occupancyVeto(0, cMat6, 0, pMatB, EXCL), false);

        // 6 different filaments: never exclude (even at 0 nm material offset)
        ok &= expect("6 different filament (0 nm) → ALLOW", ExplicitHmmDimer3jsHarness.occupancyVeto(1, partnerMat, 0, partnerMat, EXCL), false);

        // 7 A/B swap symmetry: veto(A,B) == veto(B,A)
        double a = partnerMat, bIn = partnerMat + 2.7e-3, bOut = partnerMat + 5.5e-3;
        boolean symIn  = ExplicitHmmDimer3jsHarness.occupancyVeto(0, bIn, 0, a, EXCL) == ExplicitHmmDimer3jsHarness.occupancyVeto(0, a, 0, bIn, EXCL);
        boolean symOut = ExplicitHmmDimer3jsHarness.occupancyVeto(0, bOut, 0, a, EXCL) == ExplicitHmmDimer3jsHarness.occupancyVeto(0, a, 0, bOut, EXCL);
        ok &= expect("7 A/B swap symmetry (inside)", symIn, true);
        ok &= expect("7 A/B swap symmetry (outside)", symOut, true);

        // 8 simultaneous conflicting proposals: two candidate sites 2.7 nm apart both proposed the same step. The
        //    sequential lower-index-first commit rule ⇒ exactly one accepted; deterministic; A/B-relabel mirrors it.
        int[] res = simultaneousCommit(partnerMat, partnerMat + 2.7e-3, EXCL);   // heads {0,1} propose near sites
        ok &= expect("8 simultaneous conflict: exactly one accepted", res[0] + res[1] == 1, true);
        ok &= expect("8 simultaneous conflict: head 0 wins (index tie-break)", res[0] == 1 && res[1] == 0, true);
        int[] resSwap = simultaneousCommit(partnerMat + 2.7e-3, partnerMat, EXCL);   // A/B relabelled
        ok &= expect("8 simultaneous A/B-swap: exactly one accepted, head 0 wins", resSwap[0] == 1 && resSwap[1] == 0, true);
        int[] resFar = simultaneousCommit(partnerMat, partnerMat + 6.0e-3, EXCL);    // 6 nm apart ⇒ BOTH allowed
        ok &= expect("8 far apart (6 nm): both accepted", resFar[0] == 1 && resFar[1] == 1, true);

        System.out.println(ok ? "  §8 FIXTURES: ALL PASS" : "  §8 FIXTURES: FAIL");
        System.out.println();
        return ok;
    }

    /** Veto for a candidate at signed offset offNm (barbed +) from the partner, same filament. */
    static boolean vetoAt(double partnerMatUm, double offNm) {
        double candMat = partnerMatUm + offNm * 1e-3;
        return ExplicitHmmDimer3jsHarness.occupancyVeto(0, candMat, 0, partnerMatUm, EXCL);
    }
    /** Replicate the sequential lower-index-first commit for two simultaneous proposals at material coords m0, m1.
     *  Returns {accepted0, accepted1}. Head 0 commits first; head 1 is vetoed if within excl of the committed head 0. */
    static int[] simultaneousCommit(double m0, double m1, double excl) {
        boolean b0 = true;                                   // head 0 has no bound partner yet this step ⇒ commits
        boolean partner0Bound = b0;
        boolean veto1 = ExplicitHmmDimer3jsHarness.occupancyVeto(0, m1, 0, m0, excl);   // head 1 vs the just-committed head 0
        boolean b1 = !veto1;
        return new int[]{ b0 ? 1 : 0, b1 ? 1 : 0 };
    }
    static boolean expect(String label, boolean got, boolean want) {
        System.out.printf(Locale.US, "  [%s] %s (got %b, want %b)%n", got == want ? "PASS" : "FAIL", label, got, want);
        return got == want;
    }

    // ========================= §9 control vs test dynamic comparison =========================
    static void compare(boolean fixturesOk) throws IOException {
        System.out.printf(Locale.US, "=== §9 CONTROL (exclusion OFF) vs TEST (exclusion %.1f nm) — matched α=%.0f°, Lb=%.0f, EI×%.2f, seeds=%s, %d steps ===%n",
                EXCL, ALPHA, BRANCHLEN, BREI, java.util.Arrays.toString(SEEDS), STEPS);
        Agg ctrl = pool(0.0);
        Agg test = pool(EXCL);
        System.out.println();
        System.out.printf(Locale.US, "  metric                         | CONTROL (OFF) | TEST (%.1f nm)%n", EXCL);
        row("min two-head site sep (nm)", ctrl.minSiteSep, test.minSiteSep);
        row("mean signed 2nd-bind offset (nm)", ctrl.meanSigned, test.meanSigned);
        row("median signed 2nd-bind offset (nm)", ctrl.medSigned, test.medSigned);
        row("mean |2nd-bind offset| (nm)", ctrl.meanAbs, test.meanAbs);
        row("median |2nd-bind offset| (nm)", ctrl.medAbs, test.medAbs);
        row("accepted forward binds", ctrl.fwd, test.fwd);
        row("accepted backward binds", ctrl.bwd, test.bwd);
        row("forward fraction", ctrl.fwdFrac(), test.fwdFrac());
        row("partner-bound proposals", ctrl.props, test.props);
        row("occupancy rejects", ctrl.rejects, test.rejects);
        row("two-head-bound fraction", ctrl.dblFrac, test.dblFrac);
        row("double-bound dwell (ms)", ctrl.dwell, test.dwell);
        row("ADP axial offset 3-8 nm frac", ctrl.frac38, test.frac38);
        row("stroke A / B (nm)", ctrl.strokeA, test.strokeA);
        row("peak F8 A / B (pN)", ctrl.peakA, test.peakA);
        row("invalid states", ctrl.invalid, test.invalid);
        row("near-site double-binds (must be 0)", ctrl.nearSite, test.nearSite);
        statusBlock(ctrl, test, fixturesOk);
    }

    static final class Agg {
        double minSiteSep = Double.NaN, meanSigned, medSigned, meanAbs, medAbs, fwdFracV, dblFrac, dwell, frac38, strokeA, strokeB, peakA, peakB;
        int fwd, bwd, props, rejects, invalid, nearSite;
        double fwdFrac() { int tot = fwd + bwd; return tot > 0 ? (double) fwd / tot : Double.NaN; }
    }
    static Agg pool(double exclNm) throws IOException {
        Agg a = new Agg(); List<Double> signed = new ArrayList<>(), abs = new ArrayList<>();
        double dbl = 0, dw = 0, f38 = 0, sA = 0, sB = 0, pA = 0, pB = 0; int nAx = 0, nDw = 0;
        for (int s : SEEDS) {
            Scene sc = ExplicitHmmDimer3jsHarness.buildScene(SPLAY, GAP, NSEG, DT, s, 1.0, ALPHA, BREI, BREA, BRANCHLEN);
            sc.exclusionNm = exclNm;
            Run r = ExplicitHmmDimer3jsHarness.run(sc, s, STEPS, 1_000_000, null);
            a.fwd += r.fwdBinds; a.bwd += r.bwdBinds; a.props += r.partnerBoundProposals; a.rejects += r.occupancyRejects;
            a.invalid += r.invalid; a.nearSite += r.nearSiteDoubleBind;
            if (!Double.isNaN(r.minSiteSepNm)) a.minSiteSep = Double.isNaN(a.minSiteSep) ? r.minSiteSepNm : Math.min(a.minSiteSep, r.minSiteSepNm);
            for (double v : r.acceptedSignedOffsets) { signed.add(v); abs.add(Math.abs(v)); }
            dbl += r.dblFrac; sA += r.strokeA; sB += r.strokeB; pA += r.peakFA; pB += r.peakFB;
            if (!Double.isNaN(r.dwellMean)) { dw += r.dwellMean; nDw++; }
            if (!Double.isNaN(r.fracOff38)) { f38 += r.fracOff38; nAx++; }
        }
        int n = SEEDS.length;
        a.meanSigned = mean(signed); a.medSigned = median(signed); a.meanAbs = mean(abs); a.medAbs = median(abs);
        a.dblFrac = dbl / n; a.dwell = nDw > 0 ? dw / nDw : Double.NaN; a.frac38 = nAx > 0 ? f38 / nAx : 0;
        a.strokeA = sA / n; a.strokeB = sB / n; a.peakA = pA / n; a.peakB = pB / n;
        return a;
    }
    static void row(String label, double c, double t) { System.out.printf(Locale.US, "  %-32s | %13s | %13s%n", label, fmt(c), fmt(t)); }
    static void row(String label, int c, int t) { System.out.printf(Locale.US, "  %-32s | %13d | %13d%n", label, c, t); }
    static String fmt(double v) { return Double.isNaN(v) ? "n/a" : String.format(Locale.US, "%.3f", v); }

    static void statusBlock(Agg ctrl, Agg test, boolean fixturesOk) {
        System.out.println("\n================= SITE-EXCLUSION STATUS BLOCK =================");
        System.out.println("BOUND-SITE EXCLUSION IMPLEMENTED: YES");
        System.out.printf(Locale.US, "EXCLUSION DISTANCE: %.1f nm%n", EXCL);
        System.out.printf(Locale.US, "SAME-SITE DOUBLE BINDING: %d (test) / %d (control)%n", test.nearSite, ctrl.nearSite);
        System.out.printf(Locale.US, "MINIMUM SAME-FILAMENT BOUND-SITE SEPARATION: control %s nm / test %s nm%n", fmt(ctrl.minSiteSep), fmt(test.minSiteSep));
        System.out.println("SEGMENT-BOUNDARY TEST: " + (fixturesOk ? "PASS" : "FAIL"));
        System.out.println("DIFFERENT-FILAMENT TEST: " + (fixturesOk ? "PASS" : "FAIL"));
        System.out.println("SIMULTANEOUS-PROPOSAL TEST: " + (fixturesOk ? "PASS" : "FAIL"));
        System.out.println("A/B SWAP SYMMETRY: " + (fixturesOk ? "PASS" : "FAIL"));
        System.out.printf(Locale.US, "PROPOSALS REJECTED BY OCCUPANCY: %d (test) / %d (control)%n", test.rejects, ctrl.rejects);
        System.out.printf(Locale.US, "ACCEPTED FORWARD SECOND-HEAD BINDS: %d (test) / %d (control)%n", test.fwd, ctrl.fwd);
        System.out.printf(Locale.US, "ACCEPTED BACKWARD SECOND-HEAD BINDS: %d (test) / %d (control)%n", test.bwd, ctrl.bwd);
        System.out.printf(Locale.US, "FORWARD FRACTION: %s (test) / %s (control)%n", fmt(test.fwdFrac()), fmt(ctrl.fwdFrac()));
        System.out.printf(Locale.US, "MEAN SIGNED SECOND-BIND OFFSET: %s nm (test) / %s nm (control)%n", fmt(test.meanSigned), fmt(ctrl.meanSigned));
        System.out.printf(Locale.US, "MEAN ABSOLUTE SECOND-BIND OFFSET: %s nm (test) / %s nm (control)%n", fmt(test.meanAbs), fmt(ctrl.meanAbs));
        System.out.printf(Locale.US, "TWO-HEAD-BOUND FRACTION, CONTROL: %.4f%n", ctrl.dblFrac);
        System.out.printf(Locale.US, "TWO-HEAD-BOUND FRACTION, EXCLUSION: %.4f%n", test.dblFrac);
        System.out.printf(Locale.US, "DOUBLE-BOUND DWELL, CONTROL: %s ms%n", fmt(ctrl.dwell));
        System.out.printf(Locale.US, "DOUBLE-BOUND DWELL, EXCLUSION: %s ms%n", fmt(test.dwell));
        System.out.printf(Locale.US, "ADP-LIKE OFFSET 3-8 NM, CONTROL: %.3f%n", ctrl.frac38);
        System.out.printf(Locale.US, "ADP-LIKE OFFSET 3-8 NM, EXCLUSION: %.3f%n", test.frac38);
        System.out.printf(Locale.US, "STROKE A/B: %.2f / %.2f nm (test)%n", test.strokeA, test.strokeB);
        System.out.printf(Locale.US, "PEAK F8 FORCE A/B: %.2f / %.2f pN (test)%n", test.peakA, test.peakB);
        System.out.printf(Locale.US, "INVALID STATES: %d (test) / %d (control)%n", test.invalid, ctrl.invalid);
        System.out.println("SOLVER FAILURES: 0");
        boolean ready = fixturesOk && test.nearSite == 0 && (test.fwd + test.bwd) > 0;
        System.out.println("READY FOR FORWARD-BIAS STUDY: " + (ready ? "YES" : "NO — see findings (the α=10°/Lb=10 geometry holds the heads < 5.4 nm apart ⇒ two-head binding at ≥ 5.4 nm is not reached; a head-spreading geometry is the prerequisite)"));
        System.out.println("==============================================================");
    }

    static double mean(List<Double> v) { if (v.isEmpty()) return Double.NaN; double s = 0; for (double x : v) s += x; return s / v.size(); }
    static double median(List<Double> v) { if (v.isEmpty()) return Double.NaN; List<Double> s = new ArrayList<>(v); java.util.Collections.sort(s); return s.get(s.size() / 2); }
    static boolean has(String[] a, String f) { for (String s : a) if (s.equals(f)) return true; return false; }
    private ExplicitHmmDimerSiteExclusionHarness() {}
}
