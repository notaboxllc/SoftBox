#!/usr/bin/env python3
"""Experiment 3F — native Pi-release stroke: summary figure.
Usage: python3 scripts/twobody3f_analyze.py <csv_dir> <out.png>
"""
import sys, csv, os
import numpy as np
import matplotlib; matplotlib.use("Agg")
import matplotlib.pyplot as plt

D, OUT = sys.argv[1], sys.argv[2]
def load(n):
    p=os.path.join(D,n)
    with open(p) as f: return list(csv.DictReader(f))
def col(rows,k): return np.array([float(r[k]) for r in rows])

pd=load("preload_distinction.csv"); ev=load("pistroke_events.csv")
fig,ax=plt.subplots(1,3,figsize=(16,4.8))
fig.suptitle("Experiment 3F — native Pi-release power stroke on naturally captured motors (non-canonical, default-off)",
             fontsize=12,fontweight="bold")

# (1) the three preloads
a=ax[0]
cap=col(pd,"capturePreload_pN"); rel=col(pd,"relaxedPreload_pN"); tf=col(pd,"preTransitionTrapForce_pN")
data=[cap,rel,tf]; labels=["capture\npreload","relaxed\npreload","pre-transition\nTRAP force"]
bp=a.boxplot(data,labels=labels,patch_artist=True,showfliers=False)
for p,c in zip(bp["boxes"],["tab:red","tab:orange","tab:green"]): p.set_facecolor(c); p.set_alpha(0.5)
a.axhline(0,color="k",lw=0.6)
a.set_ylabel("force (pN)")
a.set_title("(1) Preload distinction\n1.49 → 0.10 pN (bond relaxes) → ~0 (axial trap = perpendicular bond)")
a.grid(alpha=0.3,axis="y")

# (2) stroke: axial vs transverse
a=ax[1]
st=col(ev,"stroke_nm"); gl=col(ev,"glideP_nm"); tv=col(ev,"transFinal_nm")
a.scatter(gl,tv,s=14,c="tab:blue",alpha=0.6,label="native capture → Pi-stroke")
a.axvspan(5,8,color="blue",alpha=0.06); a.axhspan(0,2,color="green",alpha=0.08)
a.plot([6.91],[0.09],"*",ms=18,color="magenta",label="3D reference")
a.set_xlabel("pointedward axial stroke (nm)"); a.set_ylabel("transverse (nm)")
a.set_title("(2) Stroke: all pointed-first, 5–8 nm, ≤2 nm transverse\n(118/118)")
a.set_xlim(5,8); a.set_ylim(-0.1,2.1); a.legend(fontsize=8); a.grid(alpha=0.3)

# (3) force on actin (pointed) + stiffness
a=ax[2]
fA=col(ev,"forceActinB_pN"); k=col(ev,"kExt_ADP_pNnm")
a.scatter(k,fA,s=14,c="tab:purple",alpha=0.6)
a.axhline(0,color="k",lw=0.6,ls="--"); a.axvspan(0.5,2,color="green",alpha=0.08,label="skeletal stiffness 0.5–2")
a.set_xlabel("incremental attached stiffness k_ext (pN/nm)")
a.set_ylabel("force on actin · b̂ (pN)   [pointed < 0]")
a.set_title("(3) Pointed-directed force + skeletal stiffness\nforce-on-actin −0.66±0.15 pN, k_ext 0.624±0.004")
a.legend(fontsize=8); a.grid(alpha=0.3)

plt.tight_layout(rect=[0,0,1,0.93]); plt.savefig(OUT,dpi=115); print("# wrote",OUT)
