#!/usr/bin/env python3
"""Experiment 2A figure: native-pose dynamic-compliance localization.
Reads the exp2a CSVs and produces a compact 6-panel summary.
Usage: python3 scripts/lasertrap_localization_analyze.py <csvdir> <out.png>
"""
import sys, csv, math
from collections import defaultdict
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

csvdir, outpng = sys.argv[1], sys.argv[2]

def rd(name):
    rows=[]
    try:
        with open(f"{csvdir}/{name}") as f:
            for r in csv.DictReader(f): rows.append(r)
    except FileNotFoundError:
        pass
    return rows

base = rd("exp2a_baseline_dynamic.csv")   # stage,trap,snap,time_s,kObs,kMotor,relaxFrac
holds= rd("exp2a_holds.csv")              # stage,hold,snap,trap,kObs_early,kObs_plateau,kMotor_plateau,dkPlateau,reactWork,Fmax,preload,unstable
s1   = rd("exp2a_stage1.csv")             # scene,trap,unused,time_s,kObs,kMotor,relax

def med(a): a=[x for x in a if x==x]; return float(np.median(a)) if a else float('nan')

fig, ax = plt.subplots(2, 3, figsize=(16, 9))
fig.suptitle("Experiment 2A — native-pose dynamic-compliance localization (canonical motor; measurement only)", fontsize=13, fontweight="bold")

# ---- panel 1: intact dynamic stiffness relaxation vs time, by stage (trap 0.05) ----
a=ax[0,0]
stages=["A-bind-ADPPi","B-eqADPPi","C-earlyADP","D-postStroke","E-plateau"]
for st in stages:
    byt=defaultdict(list)
    for r in base:
        if r["stage"]==st and abs(float(r["trap_pNnm"])-0.05)<1e-6:
            byt[float(r["time_s"])].append(float(r["kObs_pNnm"]))
    ts=sorted(byt);
    if ts: a.plot([t*1e3 for t in ts],[med(byt[t]) for t in ts],marker="o",ms=3,label=st)
a.set_xscale("log"); a.set_xlabel("time after step (ms)"); a.set_ylabel("blinded k_obs (pN/nm)")
a.set_title("(1) intact k_obs(t) relaxation by state\n(early≈trap 0.10 = instrument; decays to soft plateau)")
a.axhline(0.10,ls=":",c="grey",lw=0.8); a.legend(fontsize=7); a.grid(alpha=0.3)

# ---- panel 2: relaxation fraction (bandwidth) stage E ----
a=ax[0,1]
byt=defaultdict(list)
for r in base:
    if r["stage"]=="E-plateau" and abs(float(r["trap_pNnm"])-0.05)<1e-6:
        byt[float(r["time_s"])].append(float(r["relaxFrac"]))
ts=sorted(byt)
if ts: a.plot([t*1e3 for t in ts],[med(byt[t]) for t in ts],marker="s",c="purple")
a.set_xscale("log"); a.set_xlabel("time after step (ms)"); a.set_ylabel("ΔF(t)/ΔF(plateau)")
a.set_title("(2) force-relaxation fraction (stage E)\n(stiff transient = trap+filament, not a motor mode)")
a.grid(alpha=0.3)

# ---- panel 3: paired hold effect on k_obs plateau (stage E) ----
a=ax[0,2]
holdnames=["H0-intact","H1-anchorTrans","H2-rodRot","H3-J1","H4-J2","H5-J1J2","H6-headOrient","H7-rigidChain","H8-frozenMotor"]
def hstat(stage,col):
    out=[]
    for h in holdnames:
        v=[float(r[col]) for r in holds if r["stage"]==stage and r["hold"]==h and r["unstable"]=="0"]
        out.append(med(v))
    return out
kE=hstat("E-plateau","kObs_plateau_pNnm")
kB=hstat("B-eqADPPi","kObs_plateau_pNnm")
x=np.arange(len(holdnames))
a.bar(x-0.2,kB,0.4,label="B eq-ADP·Pi",color="#4C78A8")
a.bar(x+0.2,kE,0.4,label="E ADP-plateau",color="#F58518")
a.set_xticks(x); a.set_xticklabels([h.split("-")[0] for h in holdnames],rotation=0,fontsize=8)
a.set_ylabel("blinded k_obs plateau (pN/nm)"); a.set_title("(3) paired diagnostic-hold effect on k_obs\n(H2 rod-pivot leads; H8 F8-only = upper bound)")
a.legend(fontsize=8); a.grid(alpha=0.3,axis="y")

# ---- panel 4: compliance-corrected k_motor per hold vs skeletal band ----
a=ax[1,0]
mE=hstat("E-plateau","kMotor_plateau_pNnm")
a.bar(x,mE,0.6,color="#54A24B")
a.axhspan(0.5,2.0,color="red",alpha=0.12,label="skeletal 0.5–2")
a.set_yscale("log"); a.set_xticks(x); a.set_xticklabels([h.split("-")[0] for h in holdnames],fontsize=8)
a.set_ylabel("compliance-corrected k_motor (pN/nm)")
a.set_title("(4) k_motor per hold (stage E)\nH8 F8-only ≈ skeletal ⇒ F8 not the ceiling")
a.legend(fontsize=8); a.grid(alpha=0.3,axis="y")

# ---- panel 5: trap robustness (known-spring recovery vs native) ----
a=ax[1,1]
# known-spring: PLATEAU kMotor recovered vs trap (should be flat = k_spring). Use the max-time row per scene+trap.
for ks,kexp in [("spring0.02",0.02),("spring0.10",0.10),("spring1.00",1.0)]:
    tr=sorted({float(r["trap_pNnm"]) for r in s1 if r["scene"]==ks})
    vals=[]
    for t in tr:
        rows=[r for r in s1 if r["scene"]==ks and abs(float(r["trap_pNnm"])-t)<1e-9]
        if not rows: vals.append(float('nan')); continue
        tmax=max(float(r["time_s"]) for r in rows)
        pr=[r for r in rows if abs(float(r["time_s"])-tmax)<1e-9]
        v=[float(r["kMotor_pNnm"]) for r in pr if r["kMotor_pNnm"] not in ("","nan")]
        vals.append(med(v))
    if tr: a.plot(tr,vals,marker="o",label=f"k={kexp:g}")
    a.axhline(kexp,ls=":",c="grey",lw=0.7)
a.set_xlabel("trap stiffness (pN/nm)"); a.set_ylabel("recovered k_motor (pN/nm)")
a.set_yscale("log"); a.set_title("(5) GATE 2 known-spring recovery\n(flat vs trap ⇒ estimator sound; native ill-cond ≠ estimator fail)")
a.legend(fontsize=8); a.grid(alpha=0.3)

# ---- panel 6: early vs plateau k_obs by stage (bandwidth) ----
a=ax[1,2]
early=[]; plat=[]
for st in stages:
    e=[]; p=[]
    for r in base:
        if r["stage"]==st and abs(float(r["trap_pNnm"])-0.05)<1e-6:
            t=float(r["time_s"])
            if abs(t-1e-5)<3e-6: e.append(float(r["kObs_pNnm"]))
    # plateau = max time
    tmax=max(float(r["time_s"]) for r in base if r["stage"]==st and abs(float(r["trap_pNnm"])-0.05)<1e-6)
    for r in base:
        if r["stage"]==st and abs(float(r["trap_pNnm"])-0.05)<1e-6 and abs(float(r["time_s"])-tmax)<1e-9: p.append(float(r["kObs_pNnm"]))
    early.append(med(e)); plat.append(med(p))
xs=np.arange(len(stages))
a.bar(xs-0.2,early,0.4,label="k_obs @10µs (≈trap)",color="#B279A2")
a.bar(xs+0.2,plat,0.4,label="k_obs plateau (motor)",color="#72B7B2")
a.set_xticks(xs); a.set_xticklabels([s.split("-")[0] for s in stages],fontsize=8)
a.set_ylabel("k_obs (pN/nm)"); a.set_title("(6) early vs plateau by state\n(no genuine stiff-early MOTOR mode)")
a.legend(fontsize=8); a.grid(alpha=0.3,axis="y")

plt.tight_layout(rect=[0,0,1,0.96])
plt.savefig(outpng,dpi=110)
print("wrote",outpng)
