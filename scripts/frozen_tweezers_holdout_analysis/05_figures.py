"""Diagnostic figures: example traces with the detected events, the attachment
statistic and its control distribution, and the step histograms."""
import json
import os
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from lib import OUT, load_trace, load_csv, control_path, controls_index, index
import importlib.util

spec = importlib.util.spec_from_file_location("det", os.path.join(os.path.dirname(__file__), "03_detect.py"))
det = importlib.util.module_from_spec(spec)
spec.loader.exec_module(det)

EV = json.load(open(os.path.join(OUT, "events.json")))
TH = json.load(open(os.path.join(OUT, "att_threshold.json")))
R = {ds: {r["trace_id"]: r for r in EV[ds]} for ds in EV}
idx = index("realistic")

# ---- fig 3: example traces (realistic-only: one stroke, one no-stroke) ----
strokes = [r["trace_id"] for r in EV["realistic"] if r.get("stroke")]
nostroke = [r["trace_id"] for r in EV["realistic"] if r.get("attached") and not r.get("stroke")]
examples = [(strokes[0], "realistic"), (nostroke[0], "realistic")]

fig, axes = plt.subplots(3, 1, figsize=(11, 9), sharex=True)
for ax, (tid, ds) in zip(axes, examples):
    d = load_trace(tid, ds)
    r = R[ds][tid]
    y = d["X"] - d["c"]
    ax.plot(d["t"] * 1e3, y, lw=.4, color="0.5", label="y = X - c (common mode)")
    rv, sm, w = det.att_statistic(y, d["c"], d["dt"], det.P)
    ax.plot(d["t"] * 1e3, np.sqrt(np.abs(rv)), lw=1, color="C2", label="sd in 6 ms window (settled)")
    ax.axhline(np.sqrt(TH[f"{ds}_{r['level']}"]), color="C2", ls=":", lw=1, label="attachment threshold")
    if r.get("attached"):
        ax.axvspan(r["attach_time_s"] * 1e3, r["detach_time_s"] * 1e3, color="C0", alpha=.08)
        ax.axvline(r["attach_time_s"] * 1e3, color="C0", lw=1.2, label="attach")
    if r.get("stroke"):
        ax.axvline(r["step_time_s"] * 1e3, color="C3", lw=1.2, label="stroke")
        ax.annotate(f"step = {r['step_nm']:+.1f} nm", (r["step_time_s"] * 1e3 + 1, y.min() + 2),
                    color="C3")
    ax.plot(d["t"] * 1e3, d["c"] - d["c"][0], lw=.8, color="C1", alpha=.7,
            label="trap common-mode command (rel.)")
    lvl = idx[tid]["calibration_level"]
    ax.set_title(f"{tid}  [{ds}]  level {lvl}, preload {idx[tid]['preload_command_nm']:+.1f} nm  "
                 f"-> stroke={bool(r.get('stroke'))}", fontsize=10)
    ax.set_ylabel("nm")
axes[0].legend(fontsize=7, ncol=3, loc="upper left")
axes[-1].set_xlabel("time (ms)")
plt.tight_layout()
plt.savefig(os.path.join(OUT, "fig3_example_traces.png"), dpi=130)

# ---- fig 4: attachment statistic, controls vs traces ----
fig, axes = plt.subplots(1, 2, figsize=(11, 4))
for ds in ["realistic"]:
    ax = axes[0]
    CI = controls_index()
    pool_ctrl, pool_att = [], []
    for tid, m in CI.items():
        if m["condition"] == "motor_present":
            continue
        d = load_csv(control_path(tid, ds))
        rv, _, _ = det.att_statistic(d["X"] - d["c"], d["c"], d["dt"], det.P)
        pool_ctrl.append(rv[np.isfinite(rv)] / TH[f"{ds}_{m['calibration_level']}"])
    for tid in list(idx)[:60]:
        d = load_trace(tid, ds)
        rv, _, _ = det.att_statistic(d["X"] - d["c"], d["c"], d["dt"], det.P)
        pool_att.append(rv[np.isfinite(rv)] / TH[f"{ds}_{idx[tid]['calibration_level']}"])
    pc = np.concatenate(pool_ctrl)
    pa = np.concatenate(pool_att)
    b = np.logspace(-1.2, 1.6, 60)
    ax.hist(pc, bins=b, alpha=.6, label="no-motor controls (detached)", density=True, color="C1")
    ax.hist(pa, bins=b, alpha=.6, label="raw traces (mixed)", density=True, color="C0")
    ax.axvline(1.0, color="k", ls="--", label="threshold")
    ax.set_xscale("log")
    ax.set_xlabel("attachment statistic / threshold")
    ax.set_title(ds)
    ax.legend(fontsize=8)
plt.tight_layout()
plt.savefig(os.path.join(OUT, "fig4_attachment_statistic.png"), dpi=130)

# ---- fig 5: step histograms by level ----
fig, axes = plt.subplots(1, 2, figsize=(11, 4))
for ds in ["realistic"]:
    ax = axes[0]
    for lvl, col in zip("ABC", ["C0", "C1", "C2"]):
        s = [r["step_nm"] for r in EV[ds] if r.get("stroke") and r["level"] == lvl]
        if s:
            ax.hist(s, bins=np.arange(-14, 8, 1.0), alpha=.55, label=f"{lvl} (n={len(s)})", color=col)
    ax.axvline(0, color="k", lw=.8)
    ax.set_xlabel("apparent step (nm, + = toward barbed-end bead)")
    ax.set_title(ds)
    ax.legend()
plt.tight_layout()
plt.savefig(os.path.join(OUT, "fig5_step_histograms.png"), dpi=130)
print("figures written")
