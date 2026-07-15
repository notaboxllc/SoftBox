"""Tasks 7, 8, 10: uncertainty, parameter correlation, sensitivity.

(a) Zero-load step fitted on LEVEL A alone (95% of attached traces yield a detected
    stroke there, so the selection bias that contaminates B/C is minimal, and the
    compliance correction is smallest because 2k << km).
(b) Sensitivity of the step to: the assumed post-stroke stiffness (i.e. is the step
    correlated with the compliance?), the trap calibration (+-10%), the stroke
    significance threshold, and the attachment threshold (requires re-detection).
(c) instrument bias (realistic-only: the 3G-A paired ideal-vs-realistic check is removed).
"""
import json
import os
import sys
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from lib import KT, OUT, load_trace, index

cal = {r["level"]: r for r in json.load(open(os.path.join(OUT, "calibration.json")))}
EV = json.load(open(os.path.join(OUT, "events.json")))
KM = json.load(open(os.path.join(OUT, "stiffness_pooled.json")))
STEPS = json.load(open(os.path.join(OUT, "steps_corrected.json")))
res = {}


def d_from(app, k, c, xm, kpre, kpost):
    redist = 2 * k * (c - xm) * (1 / (2 * k + kpost) - 1 / (2 * k + kpre))
    return (app - redist) * (2 * k + kpost) / kpost


def fit_d0(F, d, nboot=4000, seed=5):
    A = np.column_stack([np.ones(len(F)), F])
    beta, *_ = np.linalg.lstsq(A, d, rcond=None)
    rng = np.random.default_rng(seed)
    bs = []
    for _ in range(nboot):
        i = rng.integers(0, len(F), len(F))
        b, *_ = np.linalg.lstsq(np.column_stack([np.ones(len(i)), F[i]]), d[i], rcond=None)
        bs.append(b)
    bs = np.array(bs)
    return dict(d0=float(beta[0]), slope=float(beta[1]),
                d0_ci=[float(np.percentile(bs[:, 0], 2.5)), float(np.percentile(bs[:, 0], 97.5))],
                slope_ci=[float(np.percentile(bs[:, 1], 2.5)), float(np.percentile(bs[:, 1], 97.5))],
                n=len(F))


# ---------- (a) primary: level A ----------
print("=== zero-load step, per stratum ===")
res["zero_load"] = {}
for ds in ["realistic"]:
    res["zero_load"][ds] = {}
    for lvls, name in [("A", "A_only"), ("AB", "A+B"), ("ABC", "all")]:
        sel = [r for r in STEPS if r["dataset"] == ds and r["level"] in lvls]
        F = np.array([r["F_pre_pN"] for r in sel])
        d = np.array([r["d_nm"] for r in sel])
        f = fit_d0(F, d)
        res["zero_load"][ds][name] = f
        print(f"{ds:10s} {name:6s} n={f['n']:3d}  d0 = {f['d0']:+.2f} nm "
              f"[{f['d0_ci'][0]:+.2f},{f['d0_ci'][1]:+.2f}]  "
              f"load-slope = {f['slope']:+.3f} nm/pN [{f['slope_ci'][0]:+.3f},{f['slope_ci'][1]:+.3f}]")

# ---------- (b1) correlation between the step and the assumed compliance ----------
print("\n=== is the step correlated with the inferred cross-bridge stiffness? ===")
res["step_vs_kpost"] = {}
for ds in ["realistic"]:
    kpre = KM[ds]["primary"]["pre"]["value"]
    tab = []
    for kpost in [0.35, 0.45, 0.55, 0.7, 0.9, 1.2, 2.0, 5.0]:
        for lvls, name in [("A", "A_only"), ("ABC", "all")]:
            sel = [r for r in STEPS if r["dataset"] == ds and r["level"] in lvls]
            d = np.array([d_from(r["apparent_nm"], r["k_trap"], r["preload_nm"], r["xm_nm"],
                                 kpre, kpost) for r in sel])
            F = np.array([r["F_pre_pN"] for r in sel])
            A = np.column_stack([np.ones(len(F)), F])
            b, *_ = np.linalg.lstsq(A, d, rcond=None)
            tab.append(dict(kpost=kpost, stratum=name, d0=float(b[0])))
    res["step_vs_kpost"][ds] = tab
    print(f"  {ds}:")
    for name in ["A_only", "all"]:
        s = [t for t in tab if t["stratum"] == name]
        print(f"    {name:7s}: " + "  ".join(f"kpost={t['kpost']:.2f}->d0={t['d0']:+.2f}" for t in s))

# ---------- (b2) trap calibration +-10% ----------
print("\n=== trap-calibration sensitivity (+-10%, the stated calibration uncertainty) ===")
res["calibration_sens"] = {}
for ds in ["realistic"]:
    kpre = KM[ds]["primary"]["pre"]["value"]
    kpost = KM[ds]["primary"]["post"]["value"]
    out = {}
    for scale in [0.9, 1.0, 1.1]:
        sel = [r for r in STEPS if r["dataset"] == ds and r["level"] == "A"]
        d = np.array([d_from(r["apparent_nm"], r["k_trap"] * scale, r["preload_nm"], r["xm_nm"],
                             kpre, kpost) for r in sel])
        F = np.array([2 * r["k_trap"] * scale * (r["preload_nm"] - (r["mean_pre_X"] if "mean_pre_X" in r else 0))
                      for r in sel]) if False else np.array([r["F_pre_pN"] * scale for r in sel])
        f = fit_d0(F, d, nboot=800)
        out[f"k x {scale}"] = f["d0"]
    res["calibration_sens"][ds] = out
    print(f"  {ds} (level A): " + "   ".join(f"{kk}: d0={vv:+.2f} nm" for kk, vv in out.items()))

# ---------- (b3) stroke-significance threshold ----------
print("\n=== stroke significance threshold ===")
res["p_threshold"] = {}
for ds in ["realistic"]:
    out = {}
    for p in [0.05, 0.01, 0.002]:
        sel = [r for r in EV[ds] if r.get("attached") and "p_stroke" in r and r["p_stroke"] < p
               and r["level"] == "A"]
        a = np.array([r["step_nm"] for r in sel])
        out[str(p)] = dict(n=len(a), mean_apparent=float(a.mean()), sd=float(a.std(ddof=1)))
        print(f"  {ds:10s} p<{p:<6}: n={len(a):3d}  mean apparent step (level A) = "
              f"{a.mean():+.2f} +- {a.std(ddof=1):.2f} nm")
    res["p_threshold"][ds] = out

# ---------- (c) instrument bias ----------
# REALISTIC-ONLY HOLDOUT: the 3G-A paired ideal-vs-realistic instrument-bias
# cross-check is REMOVED here (no ideal twins).  Instrument bias on the STIFFNESS is
# still controlled by the modelled corrections (3 kHz low-pass, measured detector-noise
# floor, finite-window OU bias) that are applied inside 04_stiffness.py; instrument bias
# on the STEP can no longer be twin-validated, so it is reported as "not twin-assessable"
# and only its modelled magnitude is quoted.  This loss of a validation handle is exactly
# the quantity the holdout is designed to measure.
res["instrument"] = dict(
    twin_paired_check="REMOVED (realistic-only holdout: no ideal traces available)",
    step_bias="not twin-assessable without ideal traces",
    stiffness_corrections="modelled: 3 kHz 1st-order low-pass, measured white detector-noise "
                          "floor on X, finite-window OU windowing bias (applied in 04_stiffness)",
    attach_realistic=sum(1 for r in EV["realistic"] if r.get("attached")),
    stroke_realistic=sum(1 for r in EV["realistic"] if r.get("stroke")),
    kpre_realistic=KM["realistic"]["primary"]["pre"]["value"],
    kpost_realistic=KM["realistic"]["primary"]["post"]["value"])
print("\n=== instrument bias (realistic-only) ===")
print("  paired ideal-vs-realistic twin check REMOVED (no ideal twins in the holdout).")
print(f"  k_pre  realistic {KM['realistic']['primary']['pre']['value']:.3f} pN/nm")
print(f"  k_post realistic {KM['realistic']['primary']['post']['value']:.3f} pN/nm  "
      "(instrument-modelled corrections applied; not twin-validated)")

json.dump(res, open(os.path.join(OUT, "sensitivity.json"), "w"), indent=1, default=float)

# ---------- figure (realistic-only; the ideal-twin series/panel are removed) ----------
fig, axes = plt.subplots(1, 2, figsize=(10, 4))
ax = axes[0]
for ds, c in [("realistic", "C0")]:
    t = [x for x in res["step_vs_kpost"][ds] if x["stratum"] == "A_only"]
    ax.plot([x["kpost"] for x in t], [x["d0"] for x in t], "o-", color=c, label=f"{ds} (level A)")
    t = [x for x in res["step_vs_kpost"][ds] if x["stratum"] == "all"]
    ax.plot([x["kpost"] for x in t], [x["d0"] for x in t], "s--", color=c, alpha=.5,
            label=f"{ds} (all levels)")
ax.axvspan(KM["realistic"]["primary"]["post"]["ci_low"], KM["realistic"]["primary"]["post"]["ci_high"],
           color="0.8", alpha=.5, label="measured k_post range")
ax.set_xlabel("assumed post-stroke stiffness k_post (pN/nm)")
ax.set_ylabel("inferred zero-load step d0 (nm)")
ax.set_title("step vs compliance: correlation")
ax.legend(fontsize=7)

ax = axes[1]
for ds, c in [("realistic", "C0")]:
    for xi, (name, mk) in enumerate([("A_only", "o"), ("A+B", "s"), ("all", "^")]):
        f = res["zero_load"][ds][name]
        ax.errorbar(xi, f["d0"], yerr=[[f["d0"] - f["d0_ci"][0]], [f["d0_ci"][1] - f["d0"]]],
                    fmt=mk, color=c, capsize=3)
ax.set_xticks([0, 1, 2]); ax.set_xticklabels(["A only", "A+B", "all levels"])
ax.set_ylabel("zero-load step d0 (nm)")
ax.set_title("stratum sensitivity (realistic)")
plt.tight_layout()
plt.savefig(os.path.join(OUT, "fig7_sensitivity.png"), dpi=130)
print("\nwrote fig7_sensitivity.png")
