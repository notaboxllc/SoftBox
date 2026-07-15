"""Tasks 3, 4, 7, 10: apparent step by condition, compliance correction, and the
zero-load step.

The apparent step is the shift of the common-mode bead coordinate.  With the
motor's rest position moving from xm to xm + d:

    X_pre  = (2k c + k_pre  xm)      /(2k + k_pre)
    X_post = (2k c + k_post (xm + d))/(2k + k_post)

    apparent = X_post - X_pre
             = d * k_post/(2k+k_post)                        <- compliance loss
               + 2k (c - xm) [1/(2k+k_post) - 1/(2k+k_pre)]  <- load-redistribution
                                                                term, non-zero only
                                                                if k_pre != k_post

So the *intrinsic* step is recovered as

    d = [ apparent - 2k (c-xm) (1/(2k+k_post) - 1/(2k+k_pre)) ] * (2k+k_post)/k_post

Everything on the right is measured: k from calibration, k_pre/k_post from the
attached-variance and perturbation estimators (04_stiffness), c from the trap
command, and xm from the pre-stroke dwell mean (xm_hat = X_pre + 2k(X_pre-c)/k_pre).

Load: the force borne by the motor in the pre-stroke dwell is
    F_pre = 2k (c - X_pre)          (what the traps pull with; = motor force)
which is the physically meaningful preload.  (The nominal 2*k*offset that the
task statement suggests is the infinite-motor-stiffness limit of this.)

d is then modelled against F_pre:  d(F) = d0 + s*F   ->  d0 = zero-load step.
"""
import json
import os
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from lib import KT, OUT, bootstrap_ci

cal = {r["level"]: r for r in json.load(open(os.path.join(OUT, "calibration.json")))}
EV = json.load(open(os.path.join(OUT, "events.json")))
KM = json.load(open(os.path.join(OUT, "stiffness_pooled.json")))   # from 04b
rows = []

for ds in ["realistic"]:
    for r in EV[ds]:
        if not r.get("stroke"):
            continue
        lvl = r["level"]
        k = cal[lvl]["k_equipartition"]
        c = r["preload_nm"]
        kpre = KM[ds]["primary"]["pre"]["value"]
        kpost = KM[ds]["primary"]["post"]["value"]
        Xpre = r["mean_pre"] + c            # y = X - c
        Xpost = r["mean_post"] + c
        # motor rest position implied by the pre-stroke dwell
        xm = Xpre + 2 * k * (Xpre - c) / kpre
        F_pre = 2 * k * (c - Xpre)          # pN, force the traps exert = motor force
        app = r["step_nm"]
        redist = 2 * k * (c - xm) * (1 / (2 * k + kpost) - 1 / (2 * k + kpre))
        d = (app - redist) * (2 * k + kpost) / kpost
        d_simple = app * (2 * k + kpost) / kpost      # ignoring the redistribution term
        rows.append(dict(trace_id=r["trace_id"], dataset=ds, level=lvl, preload_nm=c,
                         k_trap=k, apparent_nm=app, F_pre_pN=F_pre,
                         F_nominal_pN=2 * k * c, xm_nm=xm,
                         d_nm=d, d_simple_nm=d_simple,
                         var_pre=r.get("var_pre"), var_post=r.get("var_post")))

json.dump(rows, open(os.path.join(OUT, "steps_corrected.json"), "w"), indent=1, default=float)

# ---------------- tables ----------------
lines = ["dataset level preload_nm  F_nominal_pN  F_pre_pN(measured)  n  apparent_mean±sd  d_corrected_mean±sd"]
table = []
for ds in ["realistic"]:
    for lvl in "ABC":
        for c in sorted(set(r["preload_nm"] for r in rows if r["level"] == lvl)):
            sel = [r for r in rows if r["dataset"] == ds and r["level"] == lvl and r["preload_nm"] == c]
            if not sel:
                continue
            app = np.array([r["apparent_nm"] for r in sel])
            d = np.array([r["d_nm"] for r in sel])
            F = np.array([r["F_pre_pN"] for r in sel])
            Fn = np.array([r["F_nominal_pN"] for r in sel])
            row = dict(dataset=ds, level=lvl, preload_nm=c, n=len(sel),
                       F_nominal_pN=float(Fn.mean()), F_pre_pN=float(F.mean()),
                       F_pre_sd=float(F.std(ddof=1)) if len(F) > 1 else np.nan,
                       apparent_nm=float(app.mean()),
                       apparent_sd=float(app.std(ddof=1)) if len(app) > 1 else np.nan,
                       d_nm=float(d.mean()),
                       d_sd=float(d.std(ddof=1)) if len(d) > 1 else np.nan)
            table.append(row)
            lines.append(f"{ds:10s} {lvl} {c:+7.1f} {Fn.mean():+8.3f} {F.mean():+8.3f} {len(sel):4d} "
                         f"{app.mean():+7.2f}±{app.std(ddof=1) if len(app)>1 else 0:.2f}  "
                         f"{d.mean():+7.2f}±{d.std(ddof=1) if len(d)>1 else 0:.2f}")
json.dump(table, open(os.path.join(OUT, "step_by_condition.json"), "w"), indent=1, default=float)
print("\n".join(lines))

# ---------------- zero-load model ----------------
fits = {}
for ds in ["realistic"]:
    sel = [r for r in rows if r["dataset"] == ds]
    F = np.array([r["F_pre_pN"] for r in sel])
    d = np.array([r["d_nm"] for r in sel])
    app = np.array([r["apparent_nm"] for r in sel])
    A = np.column_stack([np.ones(len(F)), F])
    beta, *_ = np.linalg.lstsq(A, d, rcond=None)
    # bootstrap over traces
    rng = np.random.default_rng(3)
    bs = []
    for _ in range(4000):
        i = rng.integers(0, len(F), len(F))
        b, *_ = np.linalg.lstsq(np.column_stack([np.ones(len(i)), F[i]]), d[i], rcond=None)
        bs.append(b)
    bs = np.array(bs)
    # same for the *apparent* step (uncorrected), to show the compliance effect
    beta_app, *_ = np.linalg.lstsq(A, app, rcond=None)
    fits[ds] = dict(d0_nm=float(beta[0]), slope_nm_per_pN=float(beta[1]),
                    d0_ci=[float(np.percentile(bs[:, 0], 2.5)), float(np.percentile(bs[:, 0], 97.5))],
                    slope_ci=[float(np.percentile(bs[:, 1], 2.5)), float(np.percentile(bs[:, 1], 97.5))],
                    apparent0_nm=float(beta_app[0]), apparent_slope=float(beta_app[1]),
                    n=len(F))
    print(f"\n{ds}: zero-load intrinsic step d0 = {beta[0]:+.2f} nm "
          f"[{fits[ds]['d0_ci'][0]:+.2f},{fits[ds]['d0_ci'][1]:+.2f}]  "
          f"slope {beta[1]:+.3f} nm/pN [{fits[ds]['slope_ci'][0]:+.3f},{fits[ds]['slope_ci'][1]:+.3f}]")
    print(f"{ds}: (uncorrected) apparent step at zero load = {beta_app[0]:+.2f} nm, "
          f"slope {beta_app[1]:+.3f} nm/pN")
json.dump(fits, open(os.path.join(OUT, "zero_load_fit.json"), "w"), indent=1)

# ---------------- figure ----------------
fig, axes = plt.subplots(1, 3, figsize=(14, 4.2))
ds = "realistic"
sel = [r for r in rows if r["dataset"] == ds]
cols = {"A": "C0", "B": "C1", "C": "C2"}
ax = axes[0]
for lvl in "ABC":
    s = [r for r in sel if r["level"] == lvl]
    ax.scatter([r["F_pre_pN"] for r in s], [r["apparent_nm"] for r in s], s=12, alpha=.6,
               color=cols[lvl], label=f"level {lvl} (k={cal[lvl]['k_equipartition']:.3f})")
ax.set_xlabel("pre-stroke motor force F (pN)")
ax.set_ylabel("apparent step (nm)")
ax.set_title("apparent step vs load and trap stiffness")
ax.legend(fontsize=8)
ax.axhline(0, color="k", lw=.5)

ax = axes[1]
kk = np.array([r["k_trap"] for r in sel])
app = np.array([r["apparent_nm"] for r in sel])
for lvl in "ABC":
    s = [r for r in sel if r["level"] == lvl]
    a = np.array([r["apparent_nm"] for r in s])
    ax.errorbar(cal[lvl]["k_equipartition"], a.mean(), yerr=a.std(ddof=1) / np.sqrt(len(a)),
                fmt="o", color=cols[lvl], capsize=3)
kg = np.linspace(0.005, 0.12, 50)
kpost = KM[ds]["primary"]["post"]["value"]
d0 = fits[ds]["d0_nm"]
ax.plot(kg, d0 * kpost / (2 * kg + kpost), "k--", lw=1,
        label=f"d0*kpost/(2k+kpost), d0={d0:.1f}, kpost={kpost:.2f}")
ax.set_xlabel("per-trap stiffness k (pN/nm)")
ax.set_ylabel("mean apparent step (nm)")
ax.set_title("compliance loss vs trap stiffness")
ax.legend(fontsize=7)

ax = axes[2]
for lvl in "ABC":
    s = [r for r in sel if r["level"] == lvl]
    ax.scatter([r["F_pre_pN"] for r in s], [r["d_nm"] for r in s], s=12, alpha=.6, color=cols[lvl])
F = np.array([r["F_pre_pN"] for r in sel])
xs = np.linspace(F.min(), F.max(), 10)
ax.plot(xs, fits[ds]["d0_nm"] + fits[ds]["slope_nm_per_pN"] * xs, "k-", lw=1.5)
ax.axvline(0, color="k", lw=.5)
ax.set_xlabel("pre-stroke motor force F (pN)")
ax.set_ylabel("compliance-corrected step d (nm)")
ax.set_title(f"zero-load step d0 = {fits[ds]['d0_nm']:.2f} nm")
plt.tight_layout()
plt.savefig(os.path.join(OUT, "fig6_step_model.png"), dpi=130)
print("\nwrote fig6_step_model.png")
