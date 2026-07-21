#!/usr/bin/env python3
"""
Explicit-HMM dimer GPU density-sweep analysis (task §6–§9).
Reads per-cell JSON (cell_d<D>_s<S>.json) written by runProductionCell and produces:
  §6 per-density statistics across seeds (mean/SD/SEM/95%CI/median/min/max; NO seed values hidden)
  §7 density-response fits: hyperbolic saturation + Hill; onset, half-max, 90%-max; decline at 3000?
  §8 binding-density relationships + per-motor efficiency
  §9 high-density (1500, 3000) per-seed health hard-report
Writes <outdir>/ANALYSIS.md (+ echoes a summary to stdout). Pure numpy/scipy.

  python3 scripts/hmm_density_analysis.py RUN_LOGS/hmm_density_sweep
"""
import sys, os, json, glob, math
import numpy as np
from scipy.optimize import curve_fit
from scipy import stats

OUT = sys.argv[1] if len(sys.argv) > 1 else "RUN_LOGS/hmm_density_sweep"

def load():
    cells = []
    for f in sorted(glob.glob(os.path.join(OUT, "cell_d*_s*.json"))):
        try:
            d = json.load(open(f))
            if d.get("status") == "ok":
                cells.append(d)
            else:
                print(f"  (skip {os.path.basename(f)}: status={d.get('status')})")
        except Exception as e:
            print(f"  (unreadable {f}: {e})")
    return cells

def group_by_density(cells):
    g = {}
    for c in cells:
        g.setdefault(c["density"], []).append(c)
    return dict(sorted(g.items()))

def stat_block(vals):
    a = np.array([v for v in vals if v is not None and not (isinstance(v, float) and math.isnan(v))], float)
    n = len(a)
    if n == 0:
        return dict(n=0, mean=float("nan"), sd=float("nan"), sem=float("nan"),
                    ci=(float("nan"), float("nan")), median=float("nan"), min=float("nan"), max=float("nan"))
    mean = a.mean(); sd = a.std(ddof=1) if n > 1 else 0.0; sem = sd / math.sqrt(n) if n > 1 else 0.0
    if n > 1:
        tcrit = stats.t.ppf(0.975, n - 1); half = tcrit * sem
    else:
        half = 0.0
    return dict(n=n, mean=mean, sd=sd, sem=sem, ci=(mean - half, mean + half),
                median=float(np.median(a)), min=float(a.min()), max=float(a.max()))

def hyperbolic(rho, vmax, rhalf):
    return vmax * rho / (rhalf + rho)

def hill(rho, vmax, rhalf, n):
    return vmax * rho**n / (rhalf**n + rho**n)

def fit_curve(fn, x, y, p0, bounds):
    try:
        popt, pcov = curve_fit(fn, x, y, p0=p0, bounds=bounds, maxfev=100000)
        yhat = fn(x, *popt)
        ss_res = float(np.sum((y - yhat)**2)); ss_tot = float(np.sum((y - y.mean())**2))
        r2 = 1 - ss_res / ss_tot if ss_tot > 0 else float("nan")
        perr = np.sqrt(np.diag(pcov))
        return popt, perr, r2, yhat
    except Exception as e:
        return None, None, None, str(e)

def main():
    cells = load()
    if not cells:
        print("No completed cells found in", OUT); return 1
    g = group_by_density(cells)
    densities = np.array(sorted(g.keys()), float)

    L = []
    def w(s=""): L.append(s)

    w(f"# Explicit-HMM dimer — GPU-only density-sweep analysis")
    w("")
    w(f"- Source: `{OUT}` — {len(cells)} completed cells over densities {sorted(g.keys())}")
    revs = sorted(set(c.get("code_rev", "?") for c in cells))
    w(f"- Backend: {sorted(set(c.get('backend','?') for c in cells))} | code_rev {revs} | steps {sorted(set(c['steps'] for c in cells))} | dt {sorted(set(c['dt'] for c in cells))}")
    w(f"- Frozen: branchEA {sorted(set(c.get('branchEA') for c in cells))}, rupture_mode {sorted(set(c.get('rupture_mode') for c in cells))}, dirMech {sorted(set(c.get('dirMech') for c in cells))}")
    w("")

    # ---- §6 per-density statistics (velProd + key channels), seed-level shown ----
    w("## §6 Per-density statistics (across seeds — seed values NOT hidden)")
    w("")
    metrics = [("vel_prod", "velProd µm/s"), ("mean_bound_heads", "boundHeads"),
               ("mean_bound_dimers", "boundDimers"), ("two_head_frac_among_bound", "twoFrac"),
               ("continuity", "continuity"), ("lifetime_mean_ms", "attachLife ms"),
               ("atp_turnover", "ATPturn"), ("max_gap_nm", "maxGap nm"), ("peak_branch_force_pn", "peakBrF pN")]
    dens_stats = {}
    for rho in sorted(g.keys()):
        cs = sorted(g[rho], key=lambda c: c["seed"])
        w(f"### ρ = {rho} dimers/µm²  (n={len(cs)} seeds: {[c['seed'] for c in cs]})")
        vp = [c["vel_prod"] for c in cs]
        sb = stat_block(vp)
        dens_stats[rho] = {m: stat_block([c.get(m) for c in cs]) for m, _ in metrics}
        w(f"- velProd: mean **{sb['mean']:+.3f}** ± {sb['sd']:.3f} (SEM {sb['sem']:.3f}), "
          f"95%CI [{sb['ci'][0]:+.3f}, {sb['ci'][1]:+.3f}], median {sb['median']:+.3f}, "
          f"range [{sb['min']:+.3f}, {sb['max']:+.3f}]")
        w(f"  - per-seed velProd: " + ", ".join(f"s{c['seed']}={c['vel_prod']:+.3f}" for c in cs))
        w(f"  - per-seed boundHeads: " + ", ".join(f"s{c['seed']}={c['mean_bound_heads']:.2f}" for c in cs))
        w(f"  - fwd/bwd 2nd binds: " + ", ".join(f"s{c['seed']}={c['fwd_second_binds']}/{c['bwd_second_binds']}" for c in cs))
        w(f"  - maxGap nm: " + ", ".join(f"s{c['seed']}={c['max_gap_nm']:.1f}" for c in cs)
          + f" | exc>50/>100: " + ", ".join(f"s{c['seed']}={c['exc_gt50_nm']}/{c['exc_gt100_nm']}" for c in cs))
        w(f"  - invalid/solveFail totals: {sum(c['invalid_states'] for c in cs)}/{sum(c['solver_failures'] for c in cs)}")
        w("")

    # compact table
    w("### Summary table (velProd across seeds)")
    w("")
    w("| ρ | n | velProd mean | SD | SEM | 95% CI | median | min | max | boundHeads | twoFrac |")
    w("|---|---|---|---|---|---|---|---|---|---|---|")
    for rho in sorted(g.keys()):
        s = dens_stats[rho]["vel_prod"]; bh = dens_stats[rho]["mean_bound_heads"]; tf = dens_stats[rho]["two_head_frac_among_bound"]
        w(f"| {rho} | {s['n']} | {s['mean']:+.3f} | {s['sd']:.3f} | {s['sem']:.3f} | "
          f"[{s['ci'][0]:+.3f},{s['ci'][1]:+.3f}] | {s['median']:+.3f} | {s['min']:+.3f} | {s['max']:+.3f} | "
          f"{bh['mean']:.2f} | {tf['mean']:.3f} |")
    w("")

    # ---- §7 density-response fits ----
    w("## §7 Density-response fits")
    w("")
    xd = np.array(sorted(g.keys()), float)
    yv = np.array([dens_stats[r]["vel_prod"]["mean"] for r in sorted(g.keys())])
    ye = np.array([max(dens_stats[r]["vel_prod"]["sem"], 1e-6) for r in sorted(g.keys())])
    vmax0 = max(yv.max(), 0.1); rhalf0 = float(xd[np.argmin(np.abs(yv - vmax0/2))]) or 500.0

    ph, peh, r2h, yhh = fit_curve(hyperbolic, xd, yv, [vmax0, rhalf0],
                                  ([0, 1], [10*vmax0+1, 1e5]))
    if ph is not None:
        w(f"**Hyperbolic** v(ρ)=vmax·ρ/(ρ½+ρ): vmax=**{ph[0]:.3f}** ± {peh[0]:.3f} µm/s, "
          f"ρ½=**{ph[1]:.0f}** ± {peh[1]:.0f} dimers/µm², R²={r2h:.4f}")
        resid = yv - yhh
        w("  - residuals (ρ:Δ): " + ", ".join(f"{int(r)}:{d:+.3f}" for r, d in zip(xd, resid)))
    else:
        w(f"**Hyperbolic** fit FAILED: {yhh}")

    pH, peH, r2H, yhH = fit_curve(hill, xd, yv, [vmax0, rhalf0, 1.0],
                                  ([0, 1, 0.2], [10*vmax0+1, 1e5, 8.0]))
    if pH is not None:
        w(f"**Hill** v(ρ)=vmax·ρⁿ/(ρ½ⁿ+ρⁿ): vmax=**{pH[0]:.3f}** ± {peH[0]:.3f}, "
          f"ρ½=**{pH[1]:.0f}** ± {peH[1]:.0f}, n=**{pH[2]:.2f}** ± {peH[2]:.2f}, R²={r2H:.4f}")
        if r2h is not None:
            improved = (r2H - r2h)
            w(f"  - Hill vs hyperbolic ΔR² = {improved:+.4f} → extra parameter "
              f"{'materially improves' if improved > 0.02 else 'does NOT materially improve'} the fit")
    else:
        w(f"**Hill** fit FAILED: {yhH}")

    # onset / half-max / 90%-max / decline
    if ph is not None:
        vmax, rhalf = ph[0], ph[1]
        r90 = rhalf * 0.9 / 0.1
        w("")
        w(f"- density at half-maximal speed (ρ½): **{rhalf:.0f}** dimers/µm²")
        w(f"- density at 90% of fitted max: **{r90:.0f}** dimers/µm²")
        onset = xd[np.argmax(yv > 0.1*vmax)] if np.any(yv > 0.1*vmax) else xd[0]
        w(f"- apparent onset (first ρ with v>10% vmax): ~{onset:.0f} dimers/µm²")
    # decline at 3000?
    if 3000 in dens_stats and len(xd) >= 2:
        v3000 = dens_stats[3000]["vel_prod"]["mean"]
        vpeak = yv.max(); rpeak = xd[np.argmax(yv)]
        declines = v3000 < vpeak - dens_stats[3000]["vel_prod"]["sem"]
        w(f"- velocity peak: {vpeak:+.3f} µm/s at ρ{int(rpeak)}; at ρ3000: {v3000:+.3f} µm/s → "
          f"**{'DECLINES (saturation + high-density suppression)' if declines else 'no significant decline (pure saturation)'}**")
    w("")

    # ---- §8 binding-density relationships + efficiency ----
    w("## §8 Binding-density relationships & per-motor efficiency")
    w("")
    w("| ρ | boundHeads | boundDim | twoFrac | continuity | attachLife ms | ATPturn | velProd | vel/boundHead | vel/boundDim |")
    w("|---|---|---|---|---|---|---|---|---|---|")
    for rho in sorted(g.keys()):
        cs = g[rho]
        f = lambda k: float(np.mean([c[k] for c in cs]))
        bh = f("mean_bound_heads"); bd = f("mean_bound_dimers"); vp = f("vel_prod")
        w(f"| {rho} | {bh:.2f} | {bd:.2f} | {f('two_head_frac_among_bound'):.3f} | {f('continuity'):.4f} | "
          f"{f('lifetime_mean_ms'):.3f} | {f('atp_turnover'):.0f} | {vp:+.3f} | "
          f"{vp/bh if bh>0 else float('nan'):+.4f} | {vp/bd if bd>0 else float('nan'):+.4f} |")
    w("")
    # saturation adjudication
    bh_arr = np.array([float(np.mean([c["mean_bound_heads"] for c in g[r]])) for r in sorted(g.keys())])
    tf_arr = np.array([float(np.mean([c["two_head_frac_among_bound"] for c in g[r]])) for r in sorted(g.keys())])
    eff = np.array([yv[i]/bh_arr[i] if bh_arr[i] > 0 else np.nan for i in range(len(xd))])
    if len(xd) >= 2:
        slope_top = (bh_arr[-1]-bh_arr[-2])/(xd[-1]-xd[-2])
        w(f"- bound heads vs ρ: {'monotonic-increasing' if np.all(np.diff(bh_arr) > -0.5) else 'non-monotonic'} "
          f"(ρ{int(xd[0])}={bh_arr[0]:.2f} → ρ{int(xd[-1])}={bh_arr[-1]:.2f}); saturating? "
          f"Δ(boundHeads)/Δρ at top = {slope_top:.4f}")
        w(f"- velocity-per-bound-head (efficiency): " + ", ".join(f"ρ{int(x)}={e:+.4f}" for x, e in zip(xd, eff)))
        w(f"- two-head fraction vs ρ: " + ", ".join(f"ρ{int(x)}={t:.3f}" for x, t in zip(xd, tf_arr)))
        w("  → speed saturation associated with: "
          + ("bound-head saturation " if slope_top < 0.005 else "")
          + ("declining per-motor efficiency " if len(eff) > 2 and np.nanmean(eff[-2:]) < np.nanmean(eff[:2]) else "")
          + ("(see table)"))
    else:
        w("- (need ≥2 densities for the saturation/efficiency trend — partial run)")
    w("")

    # ---- §9 high-density health (1500, 3000) per-seed ----
    w("## §9 High-density health — per-seed hard report")
    w("")
    for rho in (1500, 3000):
        if rho not in g:
            continue
        w(f"### ρ = {rho}")
        w("| seed | maxGap nm | gapP99.9 | peakBrF pN | exc>50 | exc>100 | solveFail | invalid | meanBound |")
        w("|---|---|---|---|---|---|---|---|---|")
        for c in sorted(g[rho], key=lambda c: c["seed"]):
            w(f"| {c['seed']} | {c['max_gap_nm']:.1f} | {c['gap_p999_nm']:.1f} | {c['peak_branch_force_pn']:.0f} | "
              f"{c['exc_gt50_nm']} | {c['exc_gt100_nm']} | {c['solver_failures']} | {c['invalid_states']} | {c['mean_bound_heads']:.1f} |")
        blow = [c for c in g[rho] if c["max_gap_nm"] > 50]
        if blow:
            w(f"- **>50 nm event(s)** at ρ{rho}: seeds " + ", ".join(f"s{c['seed']}(maxGap {c['max_gap_nm']:.0f} nm)" for c in blow)
              + " — recorded, NOT auto-ruptured (R0 retained per campaign spec).")
        else:
            w(f"- No >50 nm joint-gap event at ρ{rho} on the device path (all seeds under the gate).")
        w("")

    # ---- performance ----
    w("## Performance")
    w("")
    w("| ρ | mean steps/s | mean wall s | mean active (%) | warm-compile ms |")
    w("|---|---|---|---|---|")
    for rho in sorted(g.keys()):
        cs = g[rho]
        sps = float(np.mean([c["steps_per_s"] for c in cs]))
        wall = float(np.mean([c["wall_s"] for c in cs]))
        act = float(np.mean([100.0*c["mean_active"]/max(1, c["nDim"]) for c in cs]))
        wc = float(np.mean([c.get("warm_compile_ms", 0) for c in cs]))
        w(f"| {rho} | {sps:.1f} | {wall:.0f} | {act:.1f} | {wc:.0f} |")
    w("")
    w("_Note: min_solver_pivot, max_residual, and per-cell GPU util/mem are not host-instrumented "
      "(solver_failures + invalid_states cover solve health). Mechanical-health metrics sampled every healthStride steps._")

    md = "\n".join(L) + "\n"
    with open(os.path.join(OUT, "ANALYSIS.md"), "w") as fh:
        fh.write(md)
    print(md)
    print("Wrote", os.path.join(OUT, "ANALYSIS.md"))
    return 0

if __name__ == "__main__":
    sys.exit(main())
