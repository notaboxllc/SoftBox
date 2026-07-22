#!/usr/bin/env python3
"""
Explicit SINGLE-HEAD (explicit-s2-l40) GPU density-sweep analysis, matched to the HMM-dimer campaign.
Reads per-cell JSON (cell_d<D>_s<S>.json written by ExplicitCompleteMatHarness.runProductionCell) and writes:
  - density_summary.csv        (per-density aggregates, one row/density)
  - per_seed.csv               (machine-readable per-(density,seed) table)
  - ANALYSIS.md                (A density response, B hyperbolic+Hill fits, C mechanism, health)

  python3 scripts/single_head_density_analysis.py RUN_LOGS/single_head_density_sweep_long
"""
import sys, os, json, glob, math, csv
import numpy as np
from scipy.optimize import curve_fit
from scipy import stats

OUT = sys.argv[1] if len(sys.argv) > 1 else "RUN_LOGS/single_head_density_sweep_long"

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
    half = (stats.t.ppf(0.975, n - 1) * sem) if n > 1 else 0.0
    return dict(n=n, mean=mean, sd=sd, sem=sem, ci=(mean - half, mean + half),
                median=float(np.median(a)), min=float(a.min()), max=float(a.max()))

def hyperbolic(rho, vmax, rhalf):  return vmax * rho / (rhalf + rho)
def hill(rho, vmax, rhalf, n):     return vmax * rho**n / (rhalf**n + rho**n)

def fit_curve(fn, x, y, p0, bounds):
    try:
        popt, pcov = curve_fit(fn, x, y, p0=p0, bounds=bounds, maxfev=200000)
        yhat = fn(x, *popt)
        ss_res = float(np.sum((y - yhat)**2)); ss_tot = float(np.sum((y - y.mean())**2))
        r2 = 1 - ss_res / ss_tot if ss_tot > 0 else float("nan")
        return popt, np.sqrt(np.diag(pcov)), r2, yhat
    except Exception as e:
        return None, None, None, str(e)

def main():
    cells = load()
    if not cells:
        print("No completed cells in", OUT); return 1
    g = group_by_density(cells)

    # ---- machine-readable per-seed table ----
    keys = ["density","seed","vel_prod","vel_postequil","vel_full_run","mean_bound_heads","bound_frac","continuity",
            "lifetime_mean_ms","atp_turnover","vel_per_bound_head","net_force_pn","peak_load_pn","invalid_states",
            "solver_failures","max_bound","reversals","steps","dt","heads","nSeg","fil_contour_um","steps_per_s","code_rev"]
    with open(os.path.join(OUT, "per_seed.csv"), "w", newline="") as fh:
        w = csv.writer(fh); w.writerow(keys)
        for rho in sorted(g.keys()):
            for c in sorted(g[rho], key=lambda c: c["seed"]):
                w.writerow([c.get(k) for k in keys])

    dens = sorted(g.keys())
    def col(rho, k): return [c.get(k) for c in g[rho]]
    velblk = {r: stat_block(col(r, "vel_prod")) for r in dens}

    # ---- density_summary.csv ----
    with open(os.path.join(OUT, "density_summary.csv"), "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["density","n","velProd_mean","SD","SEM","ci95_lo","ci95_hi","median","min","max",
                    "velPostEquil_mean","boundHeads","boundFrac","continuity","lifetime_ms","ATPturn",
                    "vel_per_boundHead","netForce_pN","peakLoad_pN","invalid","solveFail"])
        for r in dens:
            s = velblk[r]; f = lambda k: float(np.mean([x for x in col(r, k) if x is not None]))
            bh = f("mean_bound_heads")
            w.writerow([r, s["n"], f4(s["mean"]), f4(s["sd"]), f4(s["sem"]), f4(s["ci"][0]), f4(s["ci"][1]),
                        f4(s["median"]), f4(s["min"]), f4(s["max"]), f4(f("vel_postequil")), f4(bh),
                        f5(f("bound_frac")), f4(f("continuity")), f4(f("lifetime_mean_ms")), int(round(f("atp_turnover"))),
                        f4(s["mean"]/bh if bh > 0 else float('nan')), f4(f("net_force_pn")), f4(f("peak_load_pn")),
                        int(sum(col(r,"invalid_states"))), int(sum(col(r,"solver_failures")))])

    # ---- ANALYSIS.md ----
    L = []; w = L.append
    revs = sorted(set(c.get("code_rev","?") for c in cells))
    w("# Explicit SINGLE-HEAD (explicit-s2-l40) — GPU long-run density-sweep analysis")
    w("")
    w(f"- Source: `{OUT}` — {len(cells)} completed cells over densities {dens} (heads/µm²)")
    w(f"- Backend: gpu device-resident | code_rev {revs} | steps {sorted(set(c['steps'] for c in cells))} | dt {sorted(set(c['dt'] for c in cells))}")
    w(f"- Model: explicit-s2-l40 | L={sorted(set(c.get('beam_L_nm') for c in cells))} nm | actin {sorted(set(c.get('nSeg') for c in cells))}-seg "
      f"(contour {sorted(set(round(c.get('fil_contour_um',0),3) for c in cells))} µm) | lawn {sorted(set(c.get('area_um2') for c in cells))} µm²")
    w(f"- Estimator: whole-window LS slope of filament centroid·b̂ over all steps (equil=0, matched to the dimer campaign); "
      f"velProd>0 = productive (pointed-first) glide.")
    w("")

    # ===== A. density response =====
    w("## A. Density response (velProd µm/s, whole-window; n seeds/density)")
    w("")
    w("| ρ (heads/µm²) | n | velProd mean | SD | SEM | 95% CI | median | min | max | boundHeads | vel/boundHead | postEquil |")
    w("|---|---|---|---|---|---|---|---|---|---|---|---|")
    for r in dens:
        s = velblk[r]; bh = float(np.mean(col(r,"mean_bound_heads"))); pe = float(np.mean(col(r,"vel_postequil")))
        w(f"| {r} | {s['n']} | **{s['mean']:+.3f}** | {s['sd']:.3f} | {s['sem']:.3f} | "
          f"[{s['ci'][0]:+.3f},{s['ci'][1]:+.3f}] | {s['median']:+.3f} | {s['min']:+.3f} | {s['max']:+.3f} | "
          f"{bh:.2f} | {s['mean']/bh if bh>0 else float('nan'):+.3f} | {pe:+.3f} |")
    w("")
    for r in dens:
        w(f"- ρ{r} per-seed velProd: " + ", ".join(f"s{c['seed']}={c['vel_prod']:+.3f}" for c in sorted(g[r], key=lambda c:c['seed'])))
    w("")

    # ===== B. fits =====
    xd = np.array(dens, float)
    yv = np.array([velblk[r]["mean"] for r in dens])
    w("## B. Density-response fits")
    w("")
    vmax0 = max(yv.max(), 0.1); rhalf0 = float(xd[np.argmin(np.abs(yv - vmax0/2))]) or 300.0
    ph, peh, r2h, yhh = fit_curve(hyperbolic, xd, yv, [vmax0, rhalf0], ([0,1],[10*vmax0+1,1e5]))
    if ph is not None:
        w(f"- **Hyperbolic** v=vmax·ρ/(ρ½+ρ): vmax=**{ph[0]:.3f}**±{peh[0]:.3f} µm/s, ρ½=**{ph[1]:.0f}**±{peh[1]:.0f} heads/µm², R²={r2h:.4f}")
    else:
        w(f"- Hyperbolic fit FAILED: {yhh}")
    pH, peH, r2H, yhH = fit_curve(hill, xd, yv, [vmax0, rhalf0, 1.0], ([0,1,0.2],[10*vmax0+1,1e5,8.0]))
    if pH is not None:
        w(f"- **Hill** v=vmax·ρⁿ/(ρ½ⁿ+ρⁿ): vmax=**{pH[0]:.3f}**±{peH[0]:.3f}, ρ½=**{pH[1]:.0f}**±{peH[1]:.0f}, n=**{pH[2]:.2f}**±{peH[2]:.2f}, R²={r2H:.4f}")
        if r2h is not None:
            dR2 = r2H - r2h
            w(f"  - ΔR² (Hill − hyperbolic) = **{dR2:+.4f}** → Hill {'materially improves' if dR2>0.02 else 'does NOT materially improve'} the fit")
    else:
        w(f"- Hill fit FAILED: {yhH}")
    if ph is not None:
        vmax, rhalf = ph
        w(f"- ρ½ = **{rhalf:.0f}** heads/µm²; ρ at 90% of fitted max = **{rhalf*0.9/0.1:.0f}**")
        vpeak = yv.max(); rpeak = xd[np.argmax(yv)]; v3 = velblk[3000]["mean"] if 3000 in velblk else None
        if v3 is not None:
            decl = v3 < vpeak - velblk[3000]["sem"]
            w(f"- peak velProd {vpeak:+.3f} at ρ{int(rpeak)}; ρ3000 {v3:+.3f} → **{'DECLINES (high-density suppression)' if decl else 'no significant decline (pure saturation)'}**")
    w("")

    # ===== C. mechanism =====
    w("## C. Mechanism vs density")
    w("")
    w("| ρ | boundHeads | boundFrac | continuity | lifetime ms | ATPturn | velProd | vel/boundHead |")
    w("|---|---|---|---|---|---|---|---|")
    for r in dens:
        f = lambda k: float(np.mean([x for x in col(r,k) if x is not None]))
        bh = f("mean_bound_heads"); vp = velblk[r]["mean"]
        w(f"| {r} | {bh:.2f} | {f('bound_frac'):.5f} | {f('continuity'):.4f} | {f('lifetime_mean_ms'):.4f} | "
          f"{f('atp_turnover'):.0f} | {vp:+.3f} | {vp/bh if bh>0 else float('nan'):+.4f} |")
    w("")
    bh_arr = np.array([float(np.mean(col(r,"mean_bound_heads"))) for r in dens])
    eff = np.array([yv[i]/bh_arr[i] if bh_arr[i]>0 else np.nan for i in range(len(dens))])
    w(f"- bound heads vs ρ: {'monotonic-increasing' if np.all(np.diff(bh_arr)>-0.5) else 'non-monotonic'} (ρ{dens[0]}={bh_arr[0]:.2f} → ρ{dens[-1]}={bh_arr[-1]:.2f})")
    w(f"- vel/boundHead (efficiency): " + ", ".join(f"ρ{r}={e:+.3f}" for r,e in zip(dens,eff)))
    w(f"  → efficiency {'FALLS' if np.nanmean(eff[-2:])<np.nanmean(eff[:2]) else 'flat/rises'} with ρ ⇒ "
      f"saturation is a {'transport-efficiency ceiling (co-bound tug-of-war), not an attachment ceiling' if np.nanmean(eff[-2:])<np.nanmean(eff[:2]) else 'binding-limited regime'}.")
    w("")

    # ===== health =====
    w("## Health (invalid / solver-failure / high-load tail)")
    w("")
    w("| ρ | Σinvalid | ΣsolveFail | max peakLoad pN | max maxBound |")
    w("|---|---|---|---|---|")
    for r in dens:
        w(f"| {r} | {int(sum(col(r,'invalid_states')))} | {int(sum(col(r,'solver_failures')))} | "
          f"{max(col(r,'peak_load_pn')):.0f} | {max(col(r,'max_bound'))} |")
    w("")
    w("| ρ | mean steps/s | mean wall s | warm ms |")
    w("|---|---|---|---|")
    for r in dens:
        w(f"| {r} | {np.mean(col(r,'steps_per_s')):.1f} | {np.mean([c.get('wall_s',0) for c in g[r]]):.0f} | {np.mean([c.get('warm_compile_ms',0) for c in g[r]]):.0f} |")
    w("")
    w("_solver_failures on this device path == invalid_states (redOut-finiteness is the only solve-health signal that "
      "crosses the bus in production residency; no separate per-motor pivot/residual buffer). Single head has no fork "
      "⇒ no joint-gap/branch-force metric (that is a dimer-only channel)._")

    md = "\n".join(L) + "\n"
    open(os.path.join(OUT, "ANALYSIS.md"), "w").write(md)
    print(md)
    print("Wrote", os.path.join(OUT, "ANALYSIS.md"), "+ density_summary.csv + per_seed.csv")
    return 0

def f4(x):
    try: return f"{float(x):.4f}"
    except: return ""
def f5(x):
    try: return f"{float(x):.5f}"
    except: return ""

if __name__ == "__main__":
    sys.exit(main())
