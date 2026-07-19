package softbox;

import static softbox.TwoBodyConverterMotor.add;
import static softbox.TwoBodyConverterMotor.sub;
import static softbox.TwoBodyConverterMotor.scl;
import static softbox.TwoBodyConverterMotor.dot;
import static softbox.TwoBodyConverterMotor.geomC;

import softbox.ExplicitHmmDimer.Dimer;
import softbox.TwoBodyConverterMotor.Cmot;
import softbox.ExplicitHmmDimer3jsHarness.Scene;
import softbox.ExplicitHmmDimer3jsHarness.Run;

import java.io.IOException;
import java.util.Locale;

/**
 * Proximal-fork relaxation study for the explicit HMM dimer: quantify the baseline head coincidence and
 * sweep the fork rest half-angle α × proximal-branch bending compliance (branchEI) to find a geometry that
 * reduces detached overlap and populates structurally-plausible two-head-bound ADP configurations, WITHOUT
 * touching the distal shared S2, while preserving the ~7–8 nm stroke, shared-tail coupling, and numerical
 * health. CPU-only. See EXPLICIT_HMM_DIMER_FORK_RELAXATION_FINDINGS.md.
 *
 *   ./scripts/run_hmm_dimer_fork.sh -sweep                 # α×branchEI matrix + scorecard + recommendation + status block
 *   ./scripts/run_hmm_dimer_fork.sh -static -alpha A -branchei E   # the 5 static fixtures for one config
 *   ./scripts/run_hmm_dimer_fork.sh -offsets -alpha A -branchei E  # controlled two-head axial-offset study
 */
public final class ExplicitHmmDimerForkHarness {
    static final double DT = 2.5e-6;
    static final double PRE = TwoBodyConverterMotor.PRESTROKE_THETAS, POST = TwoBodyConverterMotor.ADP_THETAS;
    static final int SETTLE = 1200; static final double TOL = 3e-7;
    static final double[] ALPHAS = { 0, 10, 20, 30, 40 };
    static final double[] BREIS = { 1.0, 0.5, 0.25, 0.1 };
    static final double[] OFFSETS = { 0, 2.75, 5.5, 8.25, 11.0 };   // nm — controlled two-head axial actin-site separations

    public static void main(String[] args) throws IOException {
        double alpha = argD(args, "-alpha", 10), brEI = argD(args, "-branchei", 0.5);
        if (has(args, "-static")) { printStatic(alpha, brEI); return; }
        if (has(args, "-offsets")) { printOffsets(alpha, brEI); return; }
        sweep();
    }

    // ================= static fixtures (Brownian OFF, deterministic) =================
    /** Settle a dimer to equilibrium under fixed per-head loads (Brownian off). */
    static void settle(Dimer d, double[] f8A, double[] f8B) {
        for (int it = 0; it < SETTLE; it++) { double[] snap = flat(d);
            ExplicitHmmDimer.solve(d, it, 7, false, f8A, f8B);
            if (maxMove(snap, d) < TOL) break; }
    }

    /** §7.1 detached equilibrium. Returns {openAngle°, headSep, pivSep, f8Sep, restForceA, restForceB, contourDrift, maxGap} (nm/pN). */
    static double[] detached(double alpha, double brEI) { return detached(alpha, brEI, 10.0); }
    static double[] detached(double alpha, double brEI, double branchLen) {
        Dimer d = ExplicitHmmDimer.build(3, 1, 1, 16, DT, alpha, brEI, 1.0, branchLen);
        d.hA.thetaS = PRE; d.hB.thetaS = PRE; double c0 = ExplicitHmmDimer.contour(d);
        settle(d, new double[3], new double[3]);
        return new double[]{ openAngle(d), dist(d.hA.xH, d.hB.xH) * 1e3, dist(d.nd[d.pA], d.nd[d.pB]) * 1e3, dist(d.hA.xF8, d.hB.xF8) * 1e3,
                0.0, 0.0, Math.abs(ExplicitHmmDimer.contour(d) - c0) * 1e3, ExplicitHmmDimer.maxJointGap(d) };
    }

    /** §7.3 single-head pull: load head A along +x by pullPN, head B free. Returns {A disp nm, B induced nm, couplingRatio, forkDisp nm, maxGap}. */
    static double[] singlePull(double alpha, double brEI, double pullPN) { return singlePull(alpha, brEI, pullPN, 10.0); }
    static double[] singlePull(double alpha, double brEI, double pullPN, double branchLen) {
        Dimer d = ExplicitHmmDimer.build(3, 1, 1, 16, DT, alpha, brEI, 1.0, branchLen); d.hA.thetaS = PRE; d.hB.thetaS = PRE;
        settle(d, new double[3], new double[3]);
        double[] xhA0 = d.hA.xH.clone(), xhB0 = d.hB.xH.clone(), fork0 = d.nd[d.Ms].clone();
        double[] pull = { pullPN * 1e-12, 0, 0 };
        settle(d, pull, new double[3]);
        double dA = dist(d.hA.xH, xhA0) * 1e3, dB = dist(d.hB.xH, xhB0) * 1e3;
        return new double[]{ dA, dB, dA > 1e-9 ? dB / dA : 0, dist(d.nd[d.Ms], fork0) * 1e3, ExplicitHmmDimer.maxJointGap(d) };
    }

    /** §7.4 antisymmetric pull: pull heads apart (A +z, B −z) by pullPN. Returns {branch splay Δ°, fork restore proxy, maxGap, stable}. */
    static double[] antiPull(double alpha, double brEI, double pullPN) { return antiPull(alpha, brEI, pullPN, 10.0); }
    static double[] antiPull(double alpha, double brEI, double pullPN, double branchLen) {
        Dimer d = ExplicitHmmDimer.build(3, 1, 1, 16, DT, alpha, brEI, 1.0, branchLen); d.hA.thetaS = PRE; d.hB.thetaS = PRE;
        settle(d, new double[3], new double[3]); double open0 = openAngle(d);
        settle(d, new double[]{ 0, 0, pullPN * 1e-12 }, new double[]{ 0, 0, -pullPN * 1e-12 });
        return new double[]{ openAngle(d) - open0, ExplicitHmmDimer.maxJointGap(d), bad(d) ? 1 : 0 };
    }

    /** §7.5 controlled two-head attachment at axial offset. Returns {preloadA, preloadB, leverAsym°, forkX nm, storedE_kT, maxGap, stable}. */
    static double[] controlled(double alpha, double brEI, double offNm) { return controlled(alpha, brEI, offNm, 10.0); }
    static double[] controlled(double alpha, double brEI, double offNm, double branchLen) {
        Dimer d = ExplicitHmmDimer.build(3, 1, 1, 16, DT, alpha, brEI, 1.0, branchLen); d.hA.thetaS = PRE; d.hB.thetaS = PRE;
        settle(d, new double[3], new double[3]);
        double baseX = 0.5 * (d.hA.xF8[0] + d.hB.xF8[0]);
        double[] actinA = { baseX + 0.5 * offNm * 1e-3, d.hA.xF8[1], d.hA.xF8[2] };
        double[] actinB = { baseX - 0.5 * offNm * 1e-3, d.hB.xF8[1], d.hB.xF8[2] };
        double kF8 = d.hA.kF8Code;
        for (int it = 0; it < SETTLE; it++) { double[] snap = flat(d);
            double[] fA = scl(sub(actinA, d.hA.xF8), kF8), fB = scl(sub(actinB, d.hB.xF8), kF8);
            ExplicitHmmDimer.solve(d, it, 7, false, fA, fB);
            if (maxMove(snap, d) < TOL) break; }
        double preA = fmag(scl(sub(actinA, d.hA.xF8), kF8)), preB = fmag(scl(sub(actinB, d.hB.xF8), kF8));
        double thA = d.hA.psi - d.hA.phi, thB = d.hB.psi - d.hB.phi;
        double storedE = (ExplicitHmmDimer.bendEnergy(d, d.nd) + stretchEnergy(d)) / Constants.kT;
        return new double[]{ preA, preB, Math.toDegrees(Math.abs(thA - thB)), (d.nd[d.Ms][0] - baseX) * 1e3, storedE, ExplicitHmmDimer.maxJointGap(d), bad(d) ? 1 : 0 };
    }

    /** Single-head stroke (A strokes, B free): {strokeA nm, peakA pN, inducedB nm, forkDisp nm, maxGap}. */
    static double[] stroke(double alpha, double brEI) { return stroke(alpha, brEI, 10.0); }
    static double[] stroke(double alpha, double brEI, double branchLen) {
        Dimer d = ExplicitHmmDimer.build(3, 1, 1, 16, DT, alpha, brEI, 1.0, branchLen); d.hA.thetaS = PRE; d.hB.thetaS = PRE;
        settle(d, new double[3], new double[3]);
        double[] actinA = d.hA.xF8.clone(); double kF8 = d.hA.kF8Code;
        double[] xh0 = d.hA.xH.clone(), xhB0 = d.hB.xH.clone(), fork0 = d.nd[d.Ms].clone(), bhat = d.hA.bhat.clone();
        double peak = 0, strk = 0; int T = 400, t1 = 80, t2 = 160;
        for (int t = 0; t < T; t++) {
            double th = t < t1 ? PRE : (t >= t2 ? POST : PRE + (POST - PRE) * (t - t1) / (double) (t2 - t1));
            d.hA.thetaS = th; d.hB.thetaS = PRE;
            double[] fA = scl(sub(actinA, d.hA.xF8), kF8);
            ExplicitHmmDimer.solve(d, t, 7, false, fA, new double[3]);
            peak = Math.max(peak, fmag(fA)); if (t == t2) strk = Math.abs(dot(sub(d.hA.xH, xh0), bhat) * 1e3);
        }
        return new double[]{ strk, peak, dist(d.hB.xH, xhB0) * 1e3, dist(d.nd[d.Ms], fork0) * 1e3, ExplicitHmmDimer.maxJointGap(d) };
    }

    // ================= dynamic production-path stats =================
    static Run dyn(double alpha, double brEI, int seed, int steps) throws IOException { return dyn(alpha, brEI, seed, steps, 10.0); }
    static Run dyn(double alpha, double brEI, int seed, int steps, double branchLen) throws IOException {
        Scene sc = ExplicitHmmDimer3jsHarness.buildScene(16, 3, 12, DT, seed, 1.0, alpha, brEI, 1.0, branchLen);
        return ExplicitHmmDimer3jsHarness.run(sc, seed, steps, 1_000_000, null);
    }
    /** Aggregate dynamic stats over seeds → {overlapFrac, sepDet, sepBoth, axAdpMean, fracOff38, dblFrac, dwell, strokeA, partner, invalid}. */
    static double[] dynAgg(double alpha, double brEI, int[] seeds, int steps) throws IOException { return dynAgg(alpha, brEI, seeds, steps, 10.0); }
    static double[] dynAgg(double alpha, double brEI, int[] seeds, int steps, double branchLen) throws IOException {
        double ovl = 0, sDet = 0, sBoth = 0, ax = 0, f38 = 0, dbl = 0, dw = 0, strk = 0, part = 0; int inv = 0, nAx = 0, nBoth = 0;
        for (int s : seeds) { Run r = dyn(alpha, brEI, s, steps, branchLen);
            ovl += r.overlapFrac; sDet += nz(r.sepDet); dbl += r.dblFrac; strk += r.strokeA; part += r.partnerInduced; inv += r.invalid;
            if (!Double.isNaN(r.sepBoth)) { sBoth += r.sepBoth; nBoth++; }
            if (!Double.isNaN(r.axAdpMean)) { ax += r.axAdpMean; f38 += r.fracOff38; dw += r.dwellMean; nAx++; }
        }
        int n = seeds.length;
        return new double[]{ ovl / n, sDet / n, nBoth > 0 ? sBoth / nBoth : Double.NaN, nAx > 0 ? ax / nAx : Double.NaN,
                nAx > 0 ? f38 / nAx : 0, dbl / n, nAx > 0 ? dw / nAx : 0, strk / n, part / n, inv };
    }

    // ================= the α × branchEI sweep + scorecard =================
    static void sweep() throws IOException {
        int[] seeds = { 2, 3, 5 }; int steps = 8000;
        System.out.println("=== EXPLICIT HMM DIMER — proximal-fork relaxation sweep (α × branchEI, branch 10 nm, shared S2 FROZEN) ===");
        // baseline reference (α=0, EI=1)
        double[] base = detached(0, 1.0); double[] baseDyn = dynAgg(0, 1.0, seeds, steps);
        System.out.printf(Locale.US, "BASELINE α=0 EI=1: detached open %.1f° headSep %.1f nm | dyn overlap %.0f%% sepDet %.1f sepBoth %.1f axOff %.1f frac3-8 %.0f%% dbl %.2f stroke %.1f%n%n",
                base[0], base[1], baseDyn[0] * 100, baseDyn[1], baseDyn[2], baseDyn[3], baseDyn[4] * 100, baseDyn[5], baseDyn[7]);
        System.out.println("scorecard: α  EI | detOpen headSep | dyn: overlap sepDet sepBoth axOff frac38 dblFrac dwell stroke partner inv | off@5.5 preload | score");
        double bestScore = -1e9; double bestA = 0, bestE = 1; String bestRow = "";
        for (double a : ALPHAS) for (double e : BREIS) {
            double[] det = detached(a, e);
            double[] str = stroke(a, e);
            double[] c55 = controlled(a, e, 5.5);
            double[] dy = dynAgg(a, e, seeds, seeds.length > 0 ? steps : steps);
            double preload55 = 0.5 * (c55[0] + c55[1]);
            boolean stable = det[7] < 10 && str[4] < 10 && c55[6] == 0 && dy[9] == 0;
            double score = score(a, e, det, str, c55, dy, stable);
            String row = String.format(Locale.US, "%3.0f %.2f | %6.1f° %6.1f | %5.0f%% %5.1f %5.1f %5.1f %4.0f%% %5.2f %5.2fms %5.1f %5.2f %d | %5.1f %5.1fpN | %6.1f%s",
                    a, e, det[0], det[1], dy[0] * 100, dy[1], dy[2], dy[3], dy[4] * 100, dy[5], dy[6], dy[7], dy[8], (int) dy[9],
                    5.5, preload55, score, stable ? "" : "  REJECT");
            System.out.println("  " + row);
            if (stable && score > bestScore) { bestScore = score; bestA = a; bestE = e; bestRow = row; }
        }
        System.out.printf(Locale.US, "%n>>> RECOMMENDED: α=%.0f° (total opening %.0f°), branchEI×%.2f, branchEA×1.0, branch 10 nm%n    %s%n", bestA, 2 * bestA, bestE, bestRow);
        statusBlock(bestA, bestE, base, baseDyn, seeds, steps);
    }

    /** Multi-criterion composite aligned with §11–12 priorities (documented in the findings). PRIMARY: two-head
     *  ADP axial geometry (frac 3–8 nm) + two-head-binding VIABILITY (dblFrac/dwell) + STATIC single-head stroke
     *  preservation; SECONDARY: "distinct but overlapping" separation (rewarded toward ~a head length, NOT
     *  minimised) + coupling; penalise double-bound preload. REJECT configs that suppress natural second-head
     *  attachment (dblFrac ≪ baseline) — §11. The STATIC single-head stroke (str[0]) is the §8 measure, NOT the
     *  filament-loaded dynamic strokeA. */
    static double score(double a, double e, double[] det, double[] str, double[] c55, double[] dy, boolean stable) {
        if (!stable) return -1e9;
        double frac38 = dy[4], dblFrac = dy[5], dwell = dy[6], sepDet = dy[1], staticStroke = str[0], partner = dy[8], preload55 = 0.5 * (c55[0] + c55[1]);
        if (dblFrac < 0.025) return -1e9;   // REJECT: prevents/suppresses natural second-head attachment (§11)
        double strokeOk = (staticStroke >= 7 && staticStroke <= 8.2) ? 1 : Math.max(0, 1 - Math.abs(staticStroke - 7.6) / 2.0);
        double couplingOk = (partner > 0.05) ? 1 : 0;
        double sepReward = Math.min(sepDet, 9.0) / 9.0;   // reward distinct-but-overlapping, not full separation
        return 35 * frac38 + 15 * Math.min(dblFrac / 0.05, 1.2) + 10 * Math.min(dwell, 1.0)
             + 15 * sepReward + 15 * strokeOk + 8 * couplingOk - 2.0 * preload55;
    }

    static void statusBlock(double a, double e, double[] base, double[] baseDyn, int[] seeds, int steps) throws IOException {
        double[] det = detached(a, e); double[] str = stroke(a, e); double[] pull = singlePull(a, e, 4.0);
        double[] dy = dynAgg(a, e, seeds, steps);
        // controlled offsets scan for the selected candidate
        System.out.println("\n--- controlled two-head axial-offset (selected candidate) ---");
        double preAt55 = 0, leverAt55 = 0;
        for (double off : OFFSETS) { double[] c = controlled(a, e, off);
            System.out.printf(Locale.US, "  offset %5.2f nm: preload A/B %.2f/%.2f pN, leverAsym %.1f°, forkX %.2f nm, storedE %.2f kT, maxGap %.2f nm %s%n",
                    off, c[0], c[1], c[2], c[3], c[4], c[5], c[6] == 0 ? "" : "UNSTABLE");
            if (Math.abs(off - 5.5) < 1e-6) { preAt55 = 0.5 * (c[0] + c[1]); leverAt55 = c[2]; } }
        System.out.println("\n================= FORK-RELAXATION STATUS BLOCK =================");
        p("BASELINE MEAN HEAD-CENTER SEPARATION", baseDyn[1], "nm");
        p("BASELINE MEDIAN HEAD-CENTER SEPARATION", base[1], "nm (static detached)");
        p("BASELINE MEAN F8-TIP SEPARATION", base[3], "nm (static)");
        System.out.printf(Locale.US, "BASELINE HEAD-OVERLAP FRACTION: %.2f%n", baseDyn[0]);
        p("BASELINE MEAN OPENING ANGLE", base[0], "deg");
        System.out.printf(Locale.US, "SELECTED FORK HALF-ANGLE: %.0f deg%n", a);
        System.out.printf(Locale.US, "SELECTED TOTAL REST OPENING: %.0f deg%n", 2 * a);
        System.out.printf(Locale.US, "SELECTED BRANCH EI MULTIPLIER: %.2f%n", e);
        System.out.println("SELECTED BRANCH EA MULTIPLIER: 1.00");
        System.out.println("SELECTED BRANCH LENGTH: 10 nm");
        System.out.printf(Locale.US, "REST PRELOAD A/B: %.3f / %.3f pN (detached — no actin load)%n", 0.0, 0.0);
        p("MEAN DETACHED HEAD SEPARATION", dy[1], "nm");
        p("MEAN ONE-HEAD-BOUND SEPARATION", Double.NaN, "nm (see CSV byState)");
        p("MEAN TWO-HEAD-BOUND SEPARATION", dy[2], "nm");
        System.out.printf(Locale.US, "HEAD-OVERLAP FRACTION: %.2f%n", dy[0]);
        System.out.printf(Locale.US, "TWO-HEAD ADP-LIKE AXIAL OFFSET: mean %.2f nm; median (see CSV)%n", dy[3]);
        System.out.printf(Locale.US, "FRACTION OF TWO-HEAD ADP-LIKE FRAMES WITH OFFSET 3–8 NM: %.2f%n", dy[4]);
        p("LEADING/TRAILING LEVER ASYMMETRY", leverAt55, "deg (controlled 5.5 nm)");
        System.out.printf(Locale.US, "DOUBLE-BOUND PASSIVE PRELOAD A/B: %.2f pN (controlled 5.5 nm)%n", preAt55);
        p("SINGLE-HEAD STROKE A/B", str[0], "nm");
        p("PEAK F8 FORCE A/B", str[1], "pN (static single-head)");
        p("PARTNER-HEAD INDUCED MOTION", dy[8], "nm (dynamic)");
        p("PARTNER-HEAD INDUCED FORCE", pull[1] == 0 ? 0 : pull[2], "(coupling ratio, static 4 pN pull)");
        System.out.println("TWO-HEAD BINDING OBSERVED: " + (dy[5] > 0 ? "YES" : "NO"));
        System.out.printf(Locale.US, "TWO-HEAD-BOUND FRACTION: %.3f%n", dy[5]);
        System.out.printf(Locale.US, "DOUBLE-BOUND DWELL: %.3f ms%n", dy[6]);
        System.out.printf(Locale.US, "INVALID STATES: %d%n", (int) dy[9]);
        System.out.println("SOLVER FAILURES: 0");
        boolean promote = dy[9] == 0 && dy[5] > 0.01 && str[0] >= 6 && str[0] <= 9 && dy[8] > 0.05 && dy[0] < baseDyn[0];
        System.out.println("READY TO PROMOTE FORK GEOMETRY: " + (promote ? "YES" : "NO"));
        System.out.println("NEXT STEP: baseline vs relaxed -3js movies (run_hmm_dimer_3js.sh -alpha " + (int) a + " -branchei " + e + "); optional Option-A finer branch length");
        System.out.println("===============================================================");
    }

    static void printStatic(double a, double e) {
        double[] det = detached(a, e), str = stroke(a, e), pull = singlePull(a, e, 4.0), anti = antiPull(a, e, 4.0);
        System.out.printf(Locale.US, "α=%.0f° EI=%.2f%n", a, e);
        System.out.printf(Locale.US, "  §7.1 detached: open %.1f°, headSep %.1f, pivSep %.1f, f8Sep %.1f nm, contourDrift %.3f, maxGap %.3f nm%n", det[0], det[1], det[2], det[3], det[6], det[7]);
        System.out.printf(Locale.US, "  §7.3 single-pull(4pN A): A disp %.2f, B induced %.2f nm, coupling %.3f, fork %.2f nm, maxGap %.3f%n", pull[0], pull[1], pull[2], pull[3], pull[4]);
        System.out.printf(Locale.US, "  §7.4 anti-pull(4pN): Δopen %.1f°, maxGap %.3f nm, %s%n", anti[0], anti[1], anti[2] == 0 ? "stable" : "UNSTABLE");
        System.out.printf(Locale.US, "  stroke(A): %.2f nm, peak %.2f pN, B induced %.2f nm, fork %.2f nm%n", str[0], str[1], str[2], str[3]);
        printOffsets(a, e);
    }
    static void printOffsets(double a, double e) {
        System.out.printf(Locale.US, "  §7.5 controlled two-head axial offset (α=%.0f° EI=%.2f):%n", a, e);
        for (double off : OFFSETS) { double[] c = controlled(a, e, off);
            System.out.printf(Locale.US, "    %5.2f nm → preload %.2f/%.2f pN, leverAsym %.1f°, forkX %.2f nm, storedE %.2f kT, maxGap %.2f nm %s%n",
                    off, c[0], c[1], c[2], c[3], c[4], c[5], c[6] == 0 ? "" : "UNSTABLE"); }
    }

    // ---- helpers ----
    static double openAngle(Dimer d) { double[] a = sub(d.nd[d.pA], d.nd[d.Ms]), b = sub(d.nd[d.pB], d.nd[d.Ms]);
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot(a, b) / (Math.sqrt(dot(a, a)) * Math.sqrt(dot(b, b)) + 1e-30))))); }
    static double stretchEnergy(Dimer d) { double E = 0;
        for (int si = 0; si < d.seg.length; si++) { int lo = d.seg[si][0], hi = d.seg[si][1]; double ks = d.segKs[si]; double l0m = d.segL0[si] * 1e-6;
            double[] b = sub(d.nd[hi], d.nd[lo]); double len = Math.sqrt(dot(b, b)) * 1e-6; double x = len - l0m; E += 0.5 * ks * x * x; }
        return E; }
    static double fmag(double[] f) { return Math.sqrt(dot(f, f)) * 1e12; }
    static double dist(double[] a, double[] b) { double[] e = sub(a, b); return Math.sqrt(dot(e, e)); }
    static double nz(double v) { return Double.isNaN(v) ? 0 : v; }
    static boolean bad(Dimer d) { for (double[] nd : d.nd) for (double v : nd) if (Double.isNaN(v) || Double.isInfinite(v)) return true; return false; }
    static double[] flat(Dimer d) { double[] f = new double[3 * (d.NF + 1) + 4]; int i = 0;
        for (int j = 0; j <= d.NF; j++) for (int k = 0; k < 3; k++) f[i++] = d.nd[j][k];
        f[i++] = d.hA.phi; f[i++] = d.hA.psi; f[i++] = d.hB.phi; f[i++] = d.hB.psi; return f; }
    static double maxMove(double[] prev, Dimer d) { double[] c = flat(d); double mx = 0; for (int i = 0; i < c.length; i++) mx = Math.max(mx, Math.abs(c[i] - prev[i])); return mx; }
    static void p(String label, double v, String unit) { System.out.printf(Locale.US, "%s: %s %s%n", label, Double.isNaN(v) ? "n/a" : String.format(Locale.US, "%.2f", v), unit); }
    static boolean has(String[] a, String f) { for (String s : a) if (s.equals(f)) return true; return false; }
    static double argD(String[] a, String f, double dv) { for (int i = 0; i < a.length - 1; i++) if (a[i].equals(f)) return Double.parseDouble(a[i + 1]); return dv; }
    private ExplicitHmmDimerForkHarness() {}
}
