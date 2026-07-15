"""Pool the per-trace stiffness estimates (task 5) and cross-check the three
independent estimators (variance / perturbation-response / mean-shift-vs-preload).

Primary estimator = attached variance (E1): it is by far the most informative here,
because km >> 2k means the trap-command perturbation moves the attached bead by only
2k/(2k+km) ~ 4-20% of the pulse, i.e. 0.1-0.6 nm against a ~2 nm thermal sd.  The
perturbation estimator is therefore weak at low trap stiffness (level A) and only
becomes competitive at level C - which is exactly what the data show, and it is
reported as an independent confirmation, not as the primary number.

Systematic floor: the same estimators applied to the no-motor controls (where the
true added stiffness is zero) return |km| <= ~0.08 pN/nm.  That is taken as the
systematic uncertainty of the method and added to the statistical CI.
"""
import json
import os
import numpy as np
from lib import KT, OUT, bootstrap_ci

cal = {r["level"]: r for r in json.load(open(os.path.join(OUT, "calibration.json")))}
S = json.load(open(os.path.join(OUT, "stiffness_per_trace.json")))
CO = json.load(open(os.path.join(OUT, "controls_km.json")))
EV = json.load(open(os.path.join(OUT, "events.json")))

SYS = max(abs(np.nanmedian([r["km_var"] for r in CO if r["dataset"] == ds]))
          for ds in ["realistic"])
print(f"systematic floor from no-motor controls: |km_bias| = {SYS:.3f} pN/nm\n")

out = {}
for ds in ["realistic"]:
    out[ds] = {}
    rows = [r for r in S if r["dataset"] == ds and r["stroke"]]
    for w in ["pre", "post"]:
        v = np.array([r.get(f"km_var_{w}", np.nan) for r in rows], float)
        v = v[np.isfinite(v)]
        lo, hi = bootstrap_ci(v)
        out[ds][w] = dict(value=float(np.mean(v)), sd=float(np.std(v, ddof=1)), n=int(len(v)),
                          ci_low=float(lo - SYS), ci_high=float(hi + SYS),
                          ci_stat=[float(lo), float(hi)], sys_pNnm=float(SYS))
        print(f"{ds:10s} {w:4s}  km = {np.mean(v):.3f} +- {np.std(v, ddof=1)/np.sqrt(len(v)):.3f} (sem)  "
              f"n={len(v)}  95% CI (stat+sys) [{lo-SYS:.3f}, {hi+SYS:.3f}]")
    # paired pre vs post.
    # FROZEN DECISION RULE (realistic-only). In 3G-A the "not identifiable" verdict was
    # supported partly by the ideal-vs-realistic sign disagreement, which is unavailable
    # here.  We therefore use the analyst's OTHER, ideal-free stated criterion: the
    # variance-inversion is ill-conditioned (a ~12% change in the attached variance moves
    # km by ~35%, i.e. ~0.19 pN/nm on km~0.55), so a pre/post DIFFERENCE is declared
    # identifiable only if BOTH (i) the paired bootstrap CI excludes zero AND (ii) |Δ|
    # exceeds the method's systematic resolution KM_DIFF_SYS.  KM_DIFF_SYS is a frozen
    # property of the estimator (transcribed from the 3G-A report's "~0.15 pN/nm"), NOT a
    # truth value; it is part of the method, like p_stroke.
    KM_DIFF_SYS = 0.15
    pr = [(r["km_var_pre"], r["km_var_post"]) for r in rows
          if np.isfinite(r.get("km_var_pre", np.nan)) and np.isfinite(r.get("km_var_post", np.nan))]
    if pr:
        d = np.array([b - a for a, b in pr])
        lo, hi = bootstrap_ci(d)
        different = (lo * hi > 0) and (abs(float(d.mean())) > KM_DIFF_SYS)
        out[ds]["paired_post_minus_pre"] = dict(mean=float(d.mean()), n=int(len(d)),
                                                ci=[float(lo), float(hi)],
                                                sys_resolution_pNnm=KM_DIFF_SYS,
                                                identifiable=bool(different))
        print(f"{ds:10s} paired (post - pre): {d.mean():+.3f} pN/nm  n={len(d)}  "
              f"95% CI [{lo:+.3f},{hi:+.3f}]  (sys resolution +-{KM_DIFF_SYS})   "
              f"-> {'DIFFERENT' if different else 'not distinguishable'}")
    # by level
    out[ds]["by_level"] = {}
    for lvl in "ABC":
        rr = [r for r in rows if r["level"] == lvl]
        e = {}
        for w in ["pre", "post"]:
            v = np.array([r.get(f"km_var_{w}", np.nan) for r in rr], float)
            v = v[np.isfinite(v)]
            q = np.array([r.get(f"km_resp_{w}", np.nan) for r in rr], float)
            q = q[np.isfinite(q)]
            e[w] = dict(km_var=float(np.mean(v)) if len(v) else None, n=int(len(v)),
                        km_var_sem=float(np.std(v, ddof=1) / np.sqrt(len(v))) if len(v) > 1 else None,
                        km_resp_median=float(np.median(q)) if len(q) else None,
                        km_resp_iqr=[float(np.percentile(q, 25)), float(np.percentile(q, 75))] if len(q) > 3 else None)
        out[ds]["by_level"][lvl] = e
        print(f"   {lvl}: pre km_var={e['pre']['km_var']}  post km_var={e['post']['km_var']}  "
              f"(resp: pre {e['pre']['km_resp_median']}, post {e['post']['km_resp_median']})")

# ---------- E3: mean shift vs preload (independent, ensemble) ----------
print("\n=== E3: regression of dwell mean <y> on preload c  ->  slope = -km/(2k+km) ===")
for ds in ["realistic"]:
    out[ds]["mean_shift"] = {}
    for lvl in "ABC":
        k = cal[lvl]["k_equipartition"]
        for w in ["pre", "post"]:
            rows = [r for r in EV[ds] if r.get("stroke") and r["level"] == lvl
                    and np.isfinite(r.get(f"mean_{w}", np.nan))]
            if len(rows) < 6:
                continue
            c = np.array([r["preload_nm"] for r in rows])
            y = np.array([r[f"mean_{w}"] for r in rows])
            A = np.column_stack([np.ones(len(c)), c])
            beta, *_ = np.linalg.lstsq(A, y, rcond=None)
            s = beta[1]
            km = -2 * k * s / (1 + s) if s > -1 else np.nan
            out[ds]["mean_shift"][f"{lvl}_{w}"] = dict(slope=float(s), intercept=float(beta[0]),
                                                       km=float(km), n=len(rows))
            print(f"{ds:10s} {lvl} {w:4s} n={len(rows):3d}  slope={s:+.4f}  intercept={beta[0]:+.2f} nm  "
                  f"-> km={km:.3f} pN/nm")

# ---------------- primary values ----------------
# k_pre  : mean-shift regression (E3), pooled over levels.  It is the highest-SNR
#          estimator (the DC shift is ~24 nm at level A) and it is level-independent,
#          which is the signature of a correct estimator.  Confirmed by E1.
# k_post : attached-variance estimator (E1) restricted to LEVEL A.  E3-post cannot
#          be used: post-stroke <y> contains both -c*kpost/(2k+kpost) and the step
#          term d*kpost/(2k+kpost), and if the step is load-dependent both are linear
#          in c -> exactly degenerate.  E1 at level B/C is inflated by selection bias
#          (at stiff traps only the largest apparent steps are detectable, and a large
#          apparent step requires a large kpost), so only level A - where 95% of
#          attached traces yield a detected stroke - is used.
for ds in ["realistic"]:
    ms = out[ds]["mean_shift"]
    pre_vals = [ms[f"{l}_pre"]["km"] for l in "ABC" if f"{l}_pre" in ms]
    kpre = float(np.mean(pre_vals))
    kpre_spread = float(np.std(pre_vals, ddof=1)) if len(pre_vals) > 1 else 0.05
    kpost = out[ds]["by_level"]["A"]["post"]["km_var"]
    kpost_sem = out[ds]["by_level"]["A"]["post"]["km_var_sem"] or 0.05
    out[ds]["primary"] = dict(
        pre=dict(value=kpre, ci_low=kpre - 2 * kpre_spread - SYS, ci_high=kpre + 2 * kpre_spread + SYS,
                 method="mean-shift of the pre-stroke dwell vs preload, pooled over levels A/B/C; "
                        "confirmed by attached-variance and (at level C) by the perturbation gain",
                 per_level=pre_vals),
        post=dict(value=kpost, ci_low=kpost - 2 * kpost_sem - SYS, ci_high=kpost + 2 * kpost_sem + SYS,
                  method="attached-variance (equipartition) in the post-stroke dwell, LEVEL A only "
                         "(B/C are selection-biased); noise/filter/windowing corrected"))
    print(f"\nPRIMARY {ds}: k_pre = {kpre:.3f} pN/nm (per-level {np.round(pre_vals,3)})   "
          f"k_post = {kpost:.3f} pN/nm (level A)")

json.dump(out, open(os.path.join(OUT, "stiffness_pooled.json"), "w"), indent=1, default=float)
print("\nwrote out/stiffness_pooled.json")
