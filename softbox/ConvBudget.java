package softbox;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.ToDoubleFunction;

/**
 * FULL BOUND-CYCLE ANGULAR-IMPULSE BUDGET for the converter-skew twirling assay (host-side analysis only).
 *
 * <p><b>What this is.</b> §21/§22 of {@code docs/DISCRETE_ACTIN_SITE_CHIRAL_BINDING_AND_STROKE_FINDINGS.md}
 * established that the true local-frame converter stroke regenerates a chirally-signed axial impulse AT each
 * stroke ({@code J_odd} coherent across all 48 native / all 24 mirror seeds) while the population-mean roll
 * {@code Ω_odd} grows only sub-sin(ε). This class answers WHERE the impulse goes between the stroke event and
 * the population mean, by accounting for the axial angular impulse over the WHOLE bound episode rather than a
 * fixed post-stroke window:
 *
 * <pre>
 *   J_pre        = ∫ τ_ax dt   from attachment            .. the step before the stroke
 *   J_stroke     = ∫ τ_ax dt   over lags 0..STROKE_W      (the validated stroke-local window, 0-7 steps)
 *   J_post_early = ∫ τ_ax dt   over lags STROKE_W+1..31
 *   J_post_late  = ∫ τ_ax dt   over lags 32..detachment
 *   J_total      = J_pre + J_stroke + J_post_early + J_post_late
 *   J_recoil     = J_total − J_stroke
 *   f_retain     = J_total / J_stroke
 * </pre>
 *
 * <p><b>Sign safety.</b> Every quantity carries the huge ε-EVEN off-axis-bond baseline (the |τ| / |τ̄| cancel
 * ratio is 20-110 in this scene), so a raw per-arm ratio is meaningless. All conclusions are drawn in the
 * <b>ε-ODD</b> channel: {@code X_odd = ½[X(+ε) − X(−ε)]} on MATCHED seeds, which cancels the even baseline by
 * construction. Ratios (f_retain) are formed from the seed-paired ODD means, never from a per-event ratio, and
 * are suppressed when the denominator is not resolved.
 *
 * <p><b>Statistics.</b> The independent statistical unit is the SEED. Per-seed means over that seed's episodes
 * are the primitive; across-seed mean ± SEM, median, IQR, seed-sign fraction and a seed-bootstrap CI are
 * reported on top of them. Event-level distributions are reported ONLY as descriptive distributions and never
 * as a pseudo-SEM.
 *
 * <p>Nothing here is a kernel, a device task or a physics change: it consumes per-step telemetry the
 * CPU/GPU-equivalent graph already crosses back (plus, under the default-off {@code EPISODE_TELEM} flag, the
 * motor-internal {@code q}/{@code nodes}/{@code outGeom} buffers — transfers only).
 */
final class ConvBudget {
    private ConvBudget() {}

    /** Stroke-local window: lags 0..STROKE_W inclusive (8 steps) — the §22.7 shortest validated window. */
    static final int STROKE_W = 7;
    /** End of the early post-stroke window (lags STROKE_W+1..POST_EARLY_W). */
    static final int POST_EARLY_W = 31;

    // ------------------------------------------------------------------ episode record layout (flat double[])
    static final int F_SEED = 0, F_MOTOR = 1, F_ATTACH = 2, F_STROKE = 3, F_DETACH = 4, F_CENSORED = 5,
            F_SITE = 6, F_AZIM = 7, F_EPSSIGN = 8, F_PRELIFE = 9, F_POSTLIFE = 10,
            F_S2EXT = 11, F_S2BEND = 12, F_PHI = 13, F_PSI = 14,
            F_FAX = 15, F_FTAN = 16, F_FRAD = 17, F_TAUAX = 18,
            F_JPRE = 19, F_JSTROKE = 20, F_JEARLY = 21, F_JLATE = 22,
            F_NSTROKE = 23, F_NBOUND = 24, F_BASEAZ = 25, F_ANCHAZ = 26,
            F_WF8 = 27, F_WCHIRAL = 28, F_TRUNC = 29, F_FASTDET = 30,
    // ---- §25 ramp-specific episode telemetry (0 for the non-ramped arms) ------------------------------
            F_Q_ATT = 31, F_EPS_ATT = 32,      // qTheta / eps_eff at the observed attachment
            F_Q_PRE = 33, F_EPS_PRE = 34,      // ... immediately BEFORE the stroke (the waiting state)
            F_Q_L0  = 35, F_EPS_L0  = 36,      // ... at lag 0 (the stroke step)
            F_Q_L7  = 37, F_EPS_L7  = 38,      // ... at the end of the stroke window
            F_Q_MAX = 39, F_EPS_MAX = 40,      // episode maxima
            F_Q_FIN = 41, F_EPS_FIN = 42,      // ... final value before detachment
            F_EPS_INT = 43,                    // integral of eps_eff dt over the episode (rad·s)
            F_DEPS_ABS = 44, F_DEPS_PEAK = 45; // integral |d eps_eff| and the peak per-step |d eps_eff|
    static final int NF = 46;

    static double jTotal(double[] r) { return r[F_JPRE] + r[F_JSTROKE] + r[F_JEARLY] + r[F_JLATE]; }
    static double jRecoil(double[] r) { return jTotal(r) - r[F_JSTROKE]; }
    static double jPost(double[] r) { return r[F_JEARLY] + r[F_JLATE]; }

    // ------------------------------------------------------------------------------------ small statistics
    /** mean, SEM, n over the finite entries. */
    static double[] msn(double[] a) {
        double s = 0; int n = 0;
        for (double v : a) if (Double.isFinite(v)) { s += v; n++; }
        if (n == 0) return new double[]{ Double.NaN, Double.NaN, 0 };
        double mu = s / n, q = 0;
        for (double v : a) if (Double.isFinite(v)) q += (v - mu) * (v - mu);
        return new double[]{ mu, n > 1 ? Math.sqrt(q / (n - 1) / n) : Double.NaN, n };
    }
    static double median(double[] a) {
        double[] c = finite(a); if (c.length == 0) return Double.NaN;
        Arrays.sort(c); int n = c.length;
        return n % 2 == 1 ? c[n / 2] : 0.5 * (c[n / 2 - 1] + c[n / 2]);
    }
    /** {q25, q75} of the finite entries (linear interpolation). */
    static double[] iqr(double[] a) {
        double[] c = finite(a); if (c.length < 2) return new double[]{ Double.NaN, Double.NaN };
        Arrays.sort(c); return new double[]{ quant(c, 0.25), quant(c, 0.75) };
    }
    static double quant(double[] sorted, double p) {
        int n = sorted.length; if (n == 0) return Double.NaN; if (n == 1) return sorted[0];
        double x = p * (n - 1); int i = (int) Math.floor(x); double f = x - i;
        return i + 1 < n ? sorted[i] * (1 - f) + sorted[i + 1] * f : sorted[n - 1];
    }
    static double[] finite(double[] a) {
        int n = 0; for (double v : a) if (Double.isFinite(v)) n++;
        double[] o = new double[n]; int j = 0; for (double v : a) if (Double.isFinite(v)) o[j++] = v; return o;
    }
    /** fraction of finite entries with the SAME sign as the mean (the seed-level coherence). */
    static double signFrac(double[] a) {
        double mu = msn(a)[0]; if (!Double.isFinite(mu) || mu == 0) return Double.NaN;
        int n = 0, k = 0; for (double v : a) if (Double.isFinite(v)) { n++; if (v * mu > 0) k++; }
        return n > 0 ? (double) k / n : Double.NaN;
    }
    static double sigma(double[] ms) {
        return (Double.isFinite(ms[0]) && Double.isFinite(ms[1]) && ms[1] > 0) ? Math.abs(ms[0]) / ms[1] : Double.NaN;
    }
    /** Deterministic seed-bootstrap percentile CI (2.5 / 97.5) of the mean. B resamples, counter-based hash RNG. */
    static double[] bootCI(double[] a, int B, long salt) {
        double[] c = finite(a); int n = c.length;
        if (n < 3) return new double[]{ Double.NaN, Double.NaN };
        double[] mu = new double[B];
        for (int b = 0; b < B; b++) {
            double s = 0;
            for (int i = 0; i < n; i++) {
                long h = ((long) b * 2654435761L) ^ ((long) i * 40503L) ^ (salt * 0x9E3779B1L);
                h ^= (h >>> 13); h *= 0x9E3779B1L; h ^= (h >>> 16);
                s += c[(int) Math.floorMod(h, n)];
            }
            mu[b] = s / n;
        }
        Arrays.sort(mu); return new double[]{ quant(mu, 0.025), quant(mu, 0.975) };
    }

    // ------------------------------------------------------------------------------- per-seed reductions
    /** Per-seed MEAN over that seed's stroke-bearing episodes of the derived quantity g (NaN when the seed has none). */
    static double[] seedMean(List<double[]>[] arm, ToDoubleFunction<double[]> g) {
        double[] o = new double[arm.length];
        for (int i = 0; i < arm.length; i++) {
            double s = 0; int n = 0;
            for (double[] r : arm[i]) { double v = g.applyAsDouble(r); if (Double.isFinite(v)) { s += v; n++; } }
            o[i] = n > 0 ? s / n : Double.NaN;
        }
        return o;
    }
    /** Per-seed SUM over that seed's episodes (used for total impulse per seed, not a per-event mean). */
    static double[] seedSum(List<double[]>[] arm, ToDoubleFunction<double[]> g) {
        double[] o = new double[arm.length];
        for (int i = 0; i < arm.length; i++) {
            double s = 0; boolean any = false;
            for (double[] r : arm[i]) { double v = g.applyAsDouble(r); if (Double.isFinite(v)) { s += v; any = true; } }
            o[i] = any ? s : Double.NaN;
        }
        return o;
    }
    static double[] seedCount(List<double[]>[] arm) {
        double[] o = new double[arm.length];
        for (int i = 0; i < arm.length; i++) o[i] = arm[i].size();
        return o;
    }
    /** ε-ODD matched-seed difference ½[X(+ε) − X(−ε)]; NaN where either arm has no events for that seed. */
    static double[] odd(double[] p, double[] m) {
        int n = Math.min(p.length, m.length); double[] o = new double[n];
        for (int i = 0; i < n; i++) o[i] = (Double.isFinite(p[i]) && Double.isFinite(m[i])) ? 0.5 * (p[i] - m[i]) : Double.NaN;
        return o;
    }

    // =============================================================================== PHASE A — the budget table
    /** One reported impulse component: label + the per-seed ODD series. */
    static final class Comp {
        final String label; final double[] seedOdd, seedPlus, seedMinus;
        Comp(String label, double[] p, double[] m) { this.label = label; this.seedPlus = p; this.seedMinus = m; this.seedOdd = odd(p, m); }
    }

    static Comp comp(String label, List<double[]>[] ap, List<double[]>[] am, ToDoubleFunction<double[]> g) {
        return new Comp(label, seedMean(ap, g), seedMean(am, g));
    }

    /**
     * The headline Phase-A table: per-seed mean of each impulse component, then the ε-ODD across-seed statistics.
     * Every column is per-STROKE-EPISODE (N·m·s per episode). Seed is the independent unit.
     */
    static void budgetTable(String name, List<double[]>[] ap, List<double[]>[] am) {
        List<Comp> cs = new ArrayList<>();
        cs.add(comp("J_pre        (attach → stroke-1)", ap, am, r -> r[F_JPRE]));
        cs.add(comp("J_stroke     (lags 0-" + STROKE_W + ")", ap, am, r -> r[F_JSTROKE]));
        cs.add(comp("J_post_early (lags " + (STROKE_W + 1) + "-" + POST_EARLY_W + ")", ap, am, r -> r[F_JEARLY]));
        cs.add(comp("J_post_late  (lags " + (POST_EARLY_W + 1) + " → detach)", ap, am, r -> r[F_JLATE]));
        cs.add(comp("J_total      (attach → detach)", ap, am, ConvBudget::jTotal));
        cs.add(comp("J_recoil     (J_total − J_stroke)", ap, am, ConvBudget::jRecoil));
        cs.add(comp("J_post       (all lags > " + STROKE_W + ")", ap, am, ConvBudget::jPost));
        System.out.printf("%n  --- %s : ε-ODD BOUND-CYCLE IMPULSE BUDGET (per stroke-bearing episode, N·m·s; seed = unit) ---%n", name);
        System.out.printf("    %-34s %14s %10s %7s %14s %10s %8s%n",
                "component", "ODD mean", "SEM", "sigma", "median", "IQR width", "same-sgn");
        for (Comp c : cs) {
            double[] ms = msn(c.seedOdd); double[] q = iqr(c.seedOdd);
            System.out.printf(Locale.US, "    %-34s %+14.4e %10.2e %7.2f %+14.4e %10.2e %7.0f%%%n",
                    c.label, ms[0], ms[1], sigma(ms), median(c.seedOdd), q[1] - q[0], 100 * signFrac(c.seedOdd));
        }
        double[] jsOdd = odd(seedMean(ap, r -> r[F_JSTROKE]), seedMean(am, r -> r[F_JSTROKE]));
        double[] jtOdd = odd(seedMean(ap, ConvBudget::jTotal), seedMean(am, ConvBudget::jTotal));
        double[] ciS = bootCI(jsOdd, 4000, 0x4A53L), ciT = bootCI(jtOdd, 4000, 0x4A54L);
        System.out.printf(Locale.US, "    seed-bootstrap 95%% CI:  J_stroke [%+.3e, %+.3e]   J_total [%+.3e, %+.3e]%n",
                ciS[0], ciS[1], ciT[0], ciT[1]);
        // f_retain from the seed-paired ODD means (never a per-event ratio); also per-seed ratios where safe.
        double[] msS = msn(jsOdd), msT = msn(jtOdd);
        if (Double.isFinite(msS[0]) && Math.abs(msS[0]) > 3 * (Double.isFinite(msS[1]) ? msS[1] : 0) && msS[0] != 0) {
            double f = msT[0] / msS[0];
            // delta-method SEM of the ratio, treating the two ODD means as (correlated) seed-level series
            double[] perSeed = new double[jsOdd.length];
            for (int i = 0; i < perSeed.length; i++)
                perSeed[i] = (Double.isFinite(jsOdd[i]) && Math.abs(jsOdd[i]) > 1e-30) ? jtOdd[i] / jsOdd[i] : Double.NaN;
            double[] msR = msn(perSeed);
            System.out.printf(Locale.US, "    f_retain = J_total_odd / J_stroke_odd = %+.4f    "
                    + "(per-seed ratio median %+.4f, mean %+.4f ± %.3f, n=%.0f)%n",
                    f, median(perSeed), msR[0], msR[1], msR[2]);
            System.out.printf(Locale.US, "    → %.1f%% of the stroke-window chiral impulse survives the full bound cycle; "
                    + "recoil cancels %.1f%%.%n", 100 * f, 100 * (1 - f));
        } else {
            System.out.println("    f_retain SUPPRESSED — J_stroke_odd is not resolved at >3σ; raw impulses above are the report.");
        }
    }

    /** Episode-population descriptors (descriptive only — never a SEM source). */
    static void episodeTable(String name, List<double[]>[] ap, List<double[]>[] am) {
        System.out.printf("%n  --- %s : episode population (descriptive; per-seed counts, event-level distributions) ---%n", name);
        System.out.printf("    %-22s %10s %10s %10s %10s %10s%n", "quantity", "+eps mean", "-eps mean", "median+", "p25+", "p75+");
        row("stroke episodes/seed", seedCount(ap), seedCount(am));
        row("pre-life  (steps)", seedMean(ap, r -> r[F_PRELIFE]), seedMean(am, r -> r[F_PRELIFE]));
        row("post-life (steps)", seedMean(ap, r -> r[F_POSTLIFE]), seedMean(am, r -> r[F_POSTLIFE]));
        row("strokes / episode", seedMean(ap, r -> r[F_NSTROKE]), seedMean(am, r -> r[F_NSTROKE]));
        row("frac window-truncated", seedMean(ap, r -> r[F_TRUNC]), seedMean(am, r -> r[F_TRUNC]));
        row("frac fast-detach", seedMean(ap, r -> r[F_FASTDET]), seedMean(am, r -> r[F_FASTDET]));
        row("nBound at stroke", seedMean(ap, r -> r[F_NBOUND]), seedMean(am, r -> r[F_NBOUND]));
        row("S2 end-to-end (nm)", seedMean(ap, r -> r[F_S2EXT]), seedMean(am, r -> r[F_S2EXT]));
        row("S2 bend energy (kT)", seedMean(ap, r -> r[F_S2BEND] / Constants.kT), seedMean(am, r -> r[F_S2BEND] / Constants.kT));
        row("phi at stroke (deg)", seedMean(ap, r -> Math.toDegrees(r[F_PHI])), seedMean(am, r -> Math.toDegrees(r[F_PHI])));
        row("psi at stroke (deg)", seedMean(ap, r -> Math.toDegrees(r[F_PSI])), seedMean(am, r -> Math.toDegrees(r[F_PSI])));
        row("F_axial at stroke (pN)", seedMean(ap, r -> r[F_FAX] * 1e12), seedMean(am, r -> r[F_FAX] * 1e12));
        row("F_tang  at stroke (pN)", seedMean(ap, r -> r[F_FTAN] * 1e12), seedMean(am, r -> r[F_FTAN] * 1e12));
    }
    private static void row(String lbl, double[] p, double[] m) {
        double[] pooledP = finite(p); Arrays.sort(pooledP);
        System.out.printf(Locale.US, "    %-22s %10.4g %10.4g %10.4g %10.4g %10.4g%n",
                lbl, msn(p)[0], msn(m)[0], median(p), quant(pooledP, 0.25), quant(pooledP, 0.75));
    }

    // =========================================================================== PHASE A2 — stratified losses
    /**
     * Stratify the ODD impulse budget by a per-episode covariate, using QUANTILE bins computed on the POOLED
     * (both arms, all seeds) covariate so the +ε and −ε arms are binned identically. Per bin the per-seed mean is
     * taken first and the ODD difference formed across matched seeds — the seed stays the statistical unit.
     */
    static void stratify(String name, String covLabel, List<double[]>[] ap, List<double[]>[] am,
                         ToDoubleFunction<double[]> cov, int nBin) {
        List<Double> pool = new ArrayList<>();
        for (List<double[]> s : ap) for (double[] r : s) { double v = cov.applyAsDouble(r); if (Double.isFinite(v)) pool.add(v); }
        for (List<double[]> s : am) for (double[] r : s) { double v = cov.applyAsDouble(r); if (Double.isFinite(v)) pool.add(v); }
        if (pool.size() < 10 * nBin) { System.out.printf("    %-26s (too few episodes to stratify)%n", covLabel); return; }
        double[] c = new double[pool.size()]; for (int i = 0; i < c.length; i++) c[i] = pool.get(i);
        Arrays.sort(c);
        double[] edge = new double[nBin + 1];
        for (int b = 0; b <= nBin; b++) edge[b] = quant(c, (double) b / nBin);
        edge[0] = Double.NEGATIVE_INFINITY; edge[nBin] = Double.POSITIVE_INFINITY;
        System.out.printf("%n    --- %s : ODD budget stratified by %s (%d quantile bins) ---%n", name, covLabel, nBin);
        System.out.printf("      %-24s %6s %13s %13s %13s %9s %8s%n",
                "bin", "n/seed", "J_stroke_odd", "J_recoil_odd", "J_total_odd", "f_retain", "sigma_T");
        for (int b = 0; b < nBin; b++) {
            final double lo = edge[b], hi = edge[b + 1];
            List<double[]>[] bp = filter(ap, cov, lo, hi), bm = filter(am, cov, lo, hi);
            double[] js = odd(seedMean(bp, r -> r[F_JSTROKE]), seedMean(bm, r -> r[F_JSTROKE]));
            double[] jr = odd(seedMean(bp, ConvBudget::jRecoil), seedMean(bm, ConvBudget::jRecoil));
            double[] jt = odd(seedMean(bp, ConvBudget::jTotal), seedMean(bm, ConvBudget::jTotal));
            double[] msS = msn(js), msT = msn(jt);
            double nps = 0; for (List<double[]> s : bp) nps += s.size(); nps /= Math.max(1, bp.length);
            String bin = String.format(Locale.US, "[%s, %s)",
                    b == 0 ? "-inf" : fmt(edge[b]), b == nBin - 1 ? "+inf" : fmt(edge[b + 1]));
            double fr = (Double.isFinite(msS[0]) && Math.abs(msS[0]) > 3 * nz(msS[1])) ? msT[0] / msS[0] : Double.NaN;
            System.out.printf(Locale.US, "      %-24s %6.1f %+13.4e %+13.4e %+13.4e %9s %8.2f%n",
                    bin, nps, msS[0], msn(jr)[0], msT[0],
                    Double.isFinite(fr) ? String.format(Locale.US, "%+.3f", fr) : "   --", sigma(msT));
        }
    }
    private static double nz(double x) { return Double.isFinite(x) ? x : 0; }
    private static String fmt(double v) {
        double a = Math.abs(v);
        return (a >= 1e4 || (a > 0 && a < 1e-3)) ? String.format(Locale.US, "%.2e", v) : String.format(Locale.US, "%.3g", v);
    }
    @SuppressWarnings("unchecked")
    static List<double[]>[] filter(List<double[]>[] arm, ToDoubleFunction<double[]> cov, double lo, double hi) {
        List<double[]>[] o = new List[arm.length];
        for (int i = 0; i < arm.length; i++) {
            o[i] = new ArrayList<>();
            for (double[] r : arm[i]) { double v = cov.applyAsDouble(r); if (Double.isFinite(v) && v >= lo && v < hi) o[i].add(r); }
        }
        return o;
    }

    // ======================================================================= PHASE A4 — atlas classification
    /**
     * Map the measured full-cycle behaviour onto the reduced twirling atlas (§ "reduced local-frame mechanism
     * atlas"). The discriminator is the relation between J_stroke, J_recoil and the detachment timing.
     */
    static String atlasClass(double jStroke, double jRecoil, double jTotal, double fracTruncated, double medPostLife) {
        if (!Double.isFinite(jStroke) || jStroke == 0) return "UNDETERMINED (J_stroke not resolved)";
        double f = jTotal / jStroke, rr = jRecoil / jStroke;
        if (f > 0.85) return "A — direct chiral stroke with RETAINED bound displacement (recoil negligible)";
        if (f < -0.15) return "B* — recoil OVERSHOOTS: cycle-integrated impulse REVERSES the stroke sign";
        if (Math.abs(f) <= 0.15) return "B — direct chiral stroke followed by (near-)CONSERVATIVE recoil: cycle integral ≈ 0";
        if (fracTruncated > 0.35 || medPostLife < 2 * STROKE_W)
            return "C — direct chiral stroke TRUNCATED by detachment (finite duty cycle preserves the transient)";
        return String.format(Locale.US,
                "B/C mixture — partial conservative recoil (recoil/stroke = %+.2f) with a surviving remainder f_retain = %+.2f", rr, f);
    }

    // ================================================================= PHASE A3 — twirling-efficiency metrics
    /**
     * @param omegaOdd      ε-ODD body-fixed roll rate (rad/s)
     * @param vEven         ε-EVEN glide speed (µm/s)
     * @param tauOdd        ε-ODD population-mean axial torque (N·m)
     * @param strokeRate    strokes per SECOND over the whole population (1/s)
     * @param jStrokeOdd    ε-ODD mean angular impulse per stroke over the stroke window (N·m·s)
     * @param jTotalOdd     ε-ODD mean angular impulse per full bound cycle (N·m·s)
     * @param wChiralOdd    ε-ODD chiral work per stroke window (J)
     * @param wF8Even       mean |F8 work| per stroke window (J) — the ε-EVEN mechanical stroke work
     * @param drTotalNm     unloaded total stroke magnitude (nm)
     * @param drTangNm      unloaded tangential stroke component at this ε (nm)
     */
    static void efficiencyTable(String name, double omegaOdd, double vEven, double tauOdd, double strokeRate,
                                double jStrokeOdd, double jTotalOdd, double wChiralOdd, double wF8Even,
                                double drTotalNm, double drTangNm) {
        System.out.printf("%n  --- %s : TWIRLING-EFFICIENCY METRICS ---%n", name);
        double etaGeom = Math.abs(drTotalNm) > 0 ? Math.abs(drTangNm) / Math.abs(drTotalNm) : Double.NaN;
        double etaRetain = (Double.isFinite(jStrokeOdd) && jStrokeOdd != 0) ? jTotalOdd / jStrokeOdd : Double.NaN;
        double etaPop = (Double.isFinite(jStrokeOdd) && strokeRate * jStrokeOdd != 0) ? tauOdd / (strokeRate * jStrokeOdd) : Double.NaN;
        double etaEnergy = (Double.isFinite(wF8Even) && wF8Even != 0) ? wChiralOdd / Math.abs(wF8Even) : Double.NaN;
        double etaTG = Math.abs(vEven) > 0 ? Math.abs(omegaOdd) / Math.abs(vEven) : Double.NaN;
        System.out.printf(Locale.US, "    eta_geom      = |Δr_tang| / |Δr_total|           = %8.4f            "
                + "(unloaded geometric stroke fraction: %.3f / %.3f nm)%n", etaGeom, drTangNm, drTotalNm);
        System.out.printf(Locale.US, "    eta_J         = |J_stroke_odd|                    = %.4e N·m·s   "
                + "(chiral angular impulse per stroke, stroke window)%n", Math.abs(jStrokeOdd));
        System.out.printf(Locale.US, "    eta_cycle     = J_total_odd (signed)              = %+.4e N·m·s   "
                + "(chiral impulse retained over the FULL bound cycle)%n", jTotalOdd);
        System.out.printf(Locale.US, "    eta_retain    = J_total_odd / J_stroke_odd        = %+8.4f            "
                + "(1 = nothing lost after the stroke window; 0 = fully cancelled)%n", etaRetain);
        System.out.printf(Locale.US, "    eta_pop       = tau_odd / (strokeRate·J_stroke_odd) = %+6.4f          "
                + "(population survival: the mean torque a population of strokes of this size WOULD give is%n"
                + "                                                                      strokeRate·J_stroke_odd = %+.4e N·m; "
                + "measured tau_odd = %+.4e N·m)%n", etaPop, strokeRate * jStrokeOdd, tauOdd);
        System.out.printf(Locale.US, "    eta_energy    = W_chiral_odd / |W_F8|             = %+.4e        "
                + "(PROXY: chiral work %+.3e J vs stroke-window F8 work %.3e J;%n"
                + "                                                                      NOT a thermodynamic efficiency — "
                + "no chemical free-energy accounting)%n", etaEnergy, wChiralOdd, wF8Even);
        System.out.printf(Locale.US, "    eta_twirl/glide = |Ω_odd| / |v_even|              = %8.4f rad/µm  "
                + "= %.4f turn/µm   (DIAGNOSTIC ONLY — this Brownian-off one-segment scene)%n",
                etaTG, etaTG / (2 * Math.PI));
        System.out.printf(Locale.US, "    inputs: Ω_odd=%+.4f rad/s  v_even=%+.4f µm/s  tau_odd=%+.4e N·m  "
                + "strokeRate=%.4g /s%n", omegaOdd, vEven, tauOdd, strokeRate);
    }
}
