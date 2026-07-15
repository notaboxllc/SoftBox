#!/usr/bin/env python3
"""Experiment 0b + 1 — 3D trap + forced-bound canonical-motor compliance: multi-panel figure.
Usage: python3 scripts/lasertrap_motor_analyze.py RUN_LOGS/lasertrap_motor/csv RUN_LOGS/lasertrap_motor/exp1_summary.png
"""
import csv, sys, math
import numpy as np
import matplotlib; matplotlib.use("Agg")
import matplotlib.pyplot as plt

CSV = sys.argv[1] if len(sys.argv) > 1 else "RUN_LOGS/lasertrap_motor/csv"
OUT = sys.argv[2] if len(sys.argv) > 2 else "RUN_LOGS/lasertrap_motor/exp1_summary.png"

def rows(name):
    with open(f"{CSV}/{name}") as f: return list(csv.DictReader(f))

fd = rows("exp1_forcedisp.csv")
st = rows("stage0b_summary.csv")
states = ["ADP.Pi", "ADP"]
col = {"ADP.Pi": "C0", "ADP": "C3"}

fig, ax = plt.subplots(2, 3, figsize=(15, 8.5))
fig.suptitle("Experiment 0b + 1 — 3D optical-trap dumbbell + forced-bound canonical-motor compliance (CPU; passive, frozen chemistry)", fontsize=11)

# (a) Stage-0b calibration: measured vs predicted stiffness (axial/tilt) — from stage0b_summary
a = ax[0, 0]
labels, meas, pred = [], [], []
for r in st:
    if r["quantity"] in ("tension_N", "tau_s", "kTheta_Nmrad", "varAx_um2"):
        labels.append(r["quantity"].split("_")[0]); meas.append(float(r["value"])); pred.append(float(r["predicted"]))
ratios = [m/p if p else 0 for m, p in zip(meas, pred)]
a.bar(labels, ratios, color="C2", alpha=0.7)
a.axhline(1.0, color="k", ls="--", lw=1)
a.set_ylabel("measured / predicted"); a.set_title("(a) Stage-0b 3D-trap calibration")
a.set_ylim(0.8, 1.2); a.grid(alpha=0.3, axis="y")

# (b) force-displacement: trap force vs commanded, both states
b = ax[0, 1]
for s in states:
    d = [r for r in fd if r["state"] == s]
    x = [float(r["pert_nm"]) for r in d]; y = [float(r["trapDF_pN"]) for r in d]
    o = np.argsort(x); x = np.array(x)[o]; y = np.array(y)[o]
    b.plot(x, y, "o-", color=col[s], label=s)
b.axhline(0, color="0.8"); b.axvline(0, color="0.8")
b.set_xlabel("commanded axial displacement (nm)"); b.set_ylabel("trap force change (pN)")
b.set_title("(b) force–displacement (trap-observed)"); b.legend(fontsize=9); b.grid(alpha=0.3)

# (c) motor eff stiffness ADP.Pi vs ADP
c = ax[0, 2]
km = {}
for s in states:
    d = [float(r["kMotorEff_pNnm"]) for r in fd if r["state"] == s and abs(abs(float(r["pert_nm"]))-1) < 0.01]
    km[s] = np.mean(d) if d else 0
c.bar(states, [km[s] for s in states], color=[col[s] for s in states], alpha=0.8)
for i, s in enumerate(states): c.text(i, km[s], f"{km[s]:.4f}", ha="center", va="bottom", fontsize=9)
c.set_ylabel("k_motor,eff (pN/nm)"); c.set_title("(c) state-dependent motor stiffness")
c.axhline(0.5, color="0.5", ls=":", lw=1); c.text(0.5, 0.5, "skeletal ~0.5–2 pN/nm", fontsize=7, color="0.4")
c.grid(alpha=0.3, axis="y")

# (d) displacement partition (filX vs commanded — shows filament follows ~98%)
d = ax[1, 0]
for s in states:
    dd = [r for r in fd if r["state"] == s]
    x = [float(r["pert_nm"]) for r in dd]; y = [float(r["filDx_nm"]) for r in dd]
    o = np.argsort(x); x = np.array(x)[o]; y = np.array(y)[o]
    d.plot(x, y, "o-", color=col[s], label=s)
d.plot([-4, 4], [-4, 4], "k--", lw=1, label="follows fully")
d.set_xlabel("commanded (nm)"); d.set_ylabel("filament displacement (nm)")
d.set_title("(d) displacement partition (filament follows ~98%)"); d.legend(fontsize=8); d.grid(alpha=0.3)

# (e) J2 vs commanded — the free hinge accommodating
e = ax[1, 1]
for s in states:
    dd = [r for r in fd if r["state"] == s]
    x = [float(r["pert_nm"]) for r in dd]; y = [float(r["j2_deg"]) for r in dd]
    o = np.argsort(x); x = np.array(x)[o]; y = np.array(y)[o]
    e.plot(x, y, "o-", color=col[s], label=s)
e.set_xlabel("commanded (nm)"); e.set_ylabel("J2 angle (deg)")
e.set_title("(e) J2 hinge accommodates displacement"); e.legend(fontsize=8); e.grid(alpha=0.3)

# (f) f8 vs commanded
fp = ax[1, 2]
for s in states:
    dd = [r for r in fd if r["state"] == s]
    x = [float(r["pert_nm"]) for r in dd]; y = [float(r["f8_pN"]) for r in dd]
    o = np.argsort(x); x = np.array(x)[o]; y = np.array(y)[o]
    fp.plot(x, y, "o-", color=col[s], label=s)
fp.set_xlabel("commanded (nm)"); fp.set_ylabel("|F8| cross-bridge force (pN)")
fp.set_title("(f) F8 barely changes (head follows site)"); fp.legend(fontsize=8); fp.grid(alpha=0.3)

fig.tight_layout(rect=[0, 0, 1, 0.97])
fig.savefig(OUT, dpi=120)
print("wrote", OUT)
