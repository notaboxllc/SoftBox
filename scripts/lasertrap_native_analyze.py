#!/usr/bin/env python3
"""Experiment 1b — native-pose blinded optical-trap stiffness: multi-panel figure.
Usage: python3 scripts/lasertrap_native_analyze.py RUN_LOGS/lasertrap_native/csv RUN_LOGS/lasertrap_native/exp1b_summary.png
"""
import csv, sys
import numpy as np
import matplotlib; matplotlib.use("Agg")
import matplotlib.pyplot as plt

CSV = sys.argv[1] if len(sys.argv) > 1 else "RUN_LOGS/lasertrap_native/csv"
OUT = sys.argv[2] if len(sys.argv) > 2 else "RUN_LOGS/lasertrap_native/exp1b_summary.png"
rows = list(csv.DictReader(open(f"{CSV}/exp1b_blinded.csv")))
stages = ["A-bind-ADPPi","B-eqADPPi","C-earlyADP","D-postStroke","E-plateau"]
byst = {s: [float(r["kMotorEff_pNnm"]) for r in rows if r["stage"]==s] for s in stages}
j2 = np.array([float(r["j2nat"]) for r in rows if r["stage"]=="E-plateau"])
anc = np.array([float(r["anchor_nm"]) for r in rows if r["stage"]=="E-plateau"])
kE = np.array([float(r["kMotorEff_pNnm"]) for r in rows if r["stage"]=="E-plateau"])

# values from the run log (secondary; not in the per-event CSV)
trap_k = [0.02,0.05,0.10]; k_by_trap = [0.0172,0.0246,0.0293]           # GATE 7 (trap-dependent)
dt_us = [10,5,2.5]; k_by_dt = [0.0132,0.0132,0.0132]                    # GATE 8 (dt-invariant)
synth = {"ADP.Pi":0.0019,"ADP":0.0040}

fig, ax = plt.subplots(2,3, figsize=(15,8.5))
fig.suptitle("Experiment 1b — native-pose BLINDED optical-trap stiffness (CPU; canonical Lymn-Taylor poses, 60/stage)", fontsize=11)

# (a) distribution by stage (blinded compliance-corrected k)
a=ax[0,0]; data=[byst[s] for s in stages]
a.boxplot(data, labels=["A","B","C","D","E"], showfliers=True, medianprops=dict(color="C0"))
a.axhline(synth["ADP.Pi"],color="C1",ls="--",lw=1,label="synthetic ADP·Pi 0.0019")
a.axhline(synth["ADP"],color="C3",ls="--",lw=1,label="synthetic ADP 0.0040")
a.axhspan(0.5,2.0,color="0.85",label="skeletal 0.5–2")
a.set_yscale("log"); a.set_ylabel("k_motor,eff (pN/nm, log)"); a.set_title("(a) blinded stiffness by attachment stage")
a.legend(fontsize=7,loc="upper left"); a.grid(alpha=0.3,which="both")

# (b) native vs synthetic
b=ax[0,1]; labels=["synth\nADP·Pi","native\nA/ADP·Pi","synth\nADP","native\nE/plateau"]
meds=[synth["ADP.Pi"],np.median(byst["A-bind-ADPPi"]),synth["ADP"],np.median(byst["E-plateau"])]
cols=["C1","C0","C3","C0"]
b.bar(labels,meds,color=cols,alpha=0.8)
for i,v in enumerate(meds): b.text(i,v,f"{v:.4f}",ha="center",va="bottom",fontsize=8)
b.axhspan(0.5,2.0,color="0.85"); b.set_yscale("log"); b.set_ylabel("k (pN/nm, log)")
b.set_title("(b) native ~8× synthetic, still ≪ skeletal"); b.grid(alpha=0.3,axis="y",which="both")

# (c) GATE 7 trap-stiffness dependence (Outcome D)
c=ax[0,2]; c.plot(trap_k,k_by_trap,"s-",color="C3")
c.set_xlabel("trap stiffness per trap (pN/nm)"); c.set_ylabel("compliance-corrected k_motor (pN/nm)")
c.set_title("(c) GATE 7 FAIL: k depends on trap ⇒ Outcome D"); c.grid(alpha=0.3)
c.text(0.03,0.018,"49% spread\n(not uniquely\nidentifiable)",fontsize=8,color="C3")

# (d) GATE 8 timestep (invariant)
d=ax[1,0]; d.plot(dt_us,k_by_dt,"o-",color="C2"); d.invert_xaxis()
d.set_xlabel("dt (µs)"); d.set_ylabel("k_motor (pN/nm)"); d.set_ylim(0.010,0.016)
d.set_title("(d) GATE 8 PASS: dt-invariant (0.3%)"); d.grid(alpha=0.3)

# (e) secondary: k vs J2nat (unblinded predictor)
e=ax[1,1]; e.scatter(j2,kE,s=18,color="C4",alpha=0.7)
e.set_xlabel("native J2 angle (deg)"); e.set_ylabel("k_motor,eff (pN/nm)")
e.set_title(f"(e) SECONDARY: k vs J2  (r={np.corrcoef(j2,kE)[0,1]:.2f})"); e.grid(alpha=0.3)

# (f) secondary: k vs anchor extension
f=ax[1,2]; f.scatter(anc,kE,s=18,color="C5",alpha=0.7)
f.set_xlabel("anchor extension (nm)"); f.set_ylabel("k_motor,eff (pN/nm)")
f.set_title(f"(f) SECONDARY: k vs anchor ext  (r={np.corrcoef(anc,kE)[0,1]:.2f})"); f.grid(alpha=0.3)

fig.tight_layout(rect=[0,0,1,0.97]); fig.savefig(OUT,dpi=120); print("wrote",OUT)
