#!/usr/bin/env python3
"""Experiment 2B — fake-coupler stiffness-transfer ladder summary figure.
Usage: python3 scripts/lasertrap_fake_analyze.py RUN_LOGS/lasertrap_fake_coupler/csv OUT.png
Reads the CSV artifacts written by LaserTrapFakeCoupler and produces one compact panel figure.
"""
import sys, csv, os
from collections import defaultdict
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

csvdir = sys.argv[1] if len(sys.argv) > 1 else "RUN_LOGS/lasertrap_fake_coupler/csv"
out    = sys.argv[2] if len(sys.argv) > 2 else "RUN_LOGS/lasertrap_fake_coupler/exp2b_summary.png"

def load(name):
    p = os.path.join(csvdir, name)
    with open(p) as f:
        return list(csv.DictReader(f))
def fnum(x):
    try: return float(x)
    except: return np.nan

transfer = load("transfer_curve.csv")
sphere   = load("sphere_arms.csv")
inv      = load("native_orientation_inventory.csv")
f4       = load("f4_offaxis.csv")
ts       = load("timestep.csv")

fig, ax = plt.subplots(2, 4, figsize=(19, 9))
fig.suptitle("Experiment 2B — [NON-CANONICAL FAKE COUPLER] stiffness-transfer ladder  (F8 = production zero-rest Hookean spring)",
             fontsize=13, fontweight="bold")

# (a) measured (k_motor) vs assigned, trap=0.05: F1 & F7 & F9 reference
axp = ax[0,0]
f1 = [(fnum(r["assigned_pNnm"]), fnum(r["kMotor_pNnm"])) for r in transfer if r["arm"]=="F1" and fnum(r["trap_pNnm"])==0.05]
f7 = [(fnum(r["assigned_pNnm"]), fnum(r["kMotor_pNnm"])) for r in sphere if r["arm"]=="F7z"]
f6 = [(fnum(r["assigned_pNnm"]), fnum(r["kMotor_pNnm"])) for r in sphere if r["arm"]=="F6z"]
kk = np.array([p[0] for p in f1]); axp.plot(kk, kk, "k--", lw=1, label="ideal α=1")
axp.plot([p[0] for p in f1],[p[1] for p in f1],"o-",color="#2b7bba",label="F1 axial F8 / F6 fixed sphere")
axp.plot([p[0] for p in f7],[p[1] for p in f7],"s-",color="#d1495b",label="F7 rotating sphere")
axp.axhline(0.6534, color="#6a4c93", ls=":", lw=1.5, label="F9 canonical H8 = 0.65")
axp.set_xlabel("assigned k$_{F8}$ (pN/nm)"); axp.set_ylabel("blinded k$_{motor}$ (pN/nm)")
axp.set_title("(a) measured vs assigned (trap 0.05)"); axp.legend(fontsize=7); axp.grid(alpha=.3)

# (b) transfer ratio alpha vs assigned
axp = ax[0,1]
a1 = [(fnum(r["assigned_pNnm"]), fnum(r["alpha"])) for r in transfer if r["arm"]=="F1" and fnum(r["trap_pNnm"])==0.05]
a7 = [(fnum(r["assigned_pNnm"]), fnum(r["alpha"])) for r in sphere if r["arm"]=="F7z"]
axp.plot([p[0] for p in a1],[p[1] for p in a1],"o-",color="#2b7bba",label="F1/F6 fixed (α≈1)")
axp.plot([p[0] for p in a7],[p[1] for p in a7],"s-",color="#d1495b",label="F7 rotating (α↓, saturates)")
axp.axhline(1.0,color="k",ls="--",lw=1); axp.axhline(0.65,color="#6a4c93",ls=":",lw=1.5,label="0.65 (H8)")
axp.set_xlabel("assigned k$_{F8}$ (pN/nm)"); axp.set_ylabel("α = k$_{blinded}$/k$_{assigned}$")
axp.set_title("(b) transfer ratio α(k)"); axp.legend(fontsize=7); axp.grid(alpha=.3); axp.set_ylim(0,1.15)

# (c) localization bars: F1 F2 F3 (orientation, preload, rotation) vs F9
axp = ax[0,2]
labels=["F1\naxial","F2\norient","F2\n+preload","F3\n+rotation","F9\ncanonical"]
vals=[1.0,1.0,1.0,1.0,0.6534]
cols=["#2b7bba","#3d9970","#3d9970","#e0a458","#6a4c93"]
axp.bar(labels,vals,color=cols); axp.axhline(1.0,color="k",ls="--",lw=1)
axp.set_ylabel("k$_{motor}$ (pN/nm)"); axp.set_ylim(0,1.15)
axp.set_title("(c) orientation / rotation isolation (native, k=1)")
for i,v in enumerate(vals): axp.text(i,v+0.02,f"{v:.3f}",ha="center",fontsize=8)

# (d) off-axis (F4) + two-bond (F5): torque cost
axp = ax[0,3]
fr=[fnum(r["attach_fracFromEnd"]) for r in f4 if r["rotation"]=="on"]
km=[fnum(r["kMotor_pNnm"]) for r in f4 if r["rotation"]=="on"]
rot=[fnum(r["filRot_deg"]) for r in f4 if r["rotation"]=="on"]
axp.bar([f"{x:.3f}L" for x in fr],km,color="#3d9970"); axp.axhline(1.0,color="k",ls="--",lw=1)
axp.set_ylim(0.9,1.05); axp.set_ylabel("k$_{motor}$ (pN/nm)")
axp.set_title("(d) off-axis torque (F4 z-bond, rot ON)")
for i,(v,rr) in enumerate(zip(km,rot)): axp.text(i,v+0.005,f"{v:.4f}\n{rr:.2f}°",ha="center",fontsize=7)

# (e) fixed vs rotating vs restrained sphere (k ladder)
axp = ax[1,0]
for arm,c,m in [("F6z","#2b7bba","o"),("F7z","#d1495b","s"),("F8z","#6a4c93","^")]:
    d=[(fnum(r["assigned_pNnm"]),fnum(r["kMotor_pNnm"])) for r in sphere if r["arm"]==arm]
    axp.plot([p[0] for p in d],[p[1] for p in d],m+"-",color=c,label=arm)
axp.set_xlabel("assigned k$_{F8}$ (pN/nm)"); axp.set_ylabel("k$_{motor}$ (pN/nm)")
axp.set_title("(e) F6 fixed · F7 rotating · F8 restrained sphere"); axp.legend(fontsize=8); axp.grid(alpha=.3)

# (f) native bond-angle distribution (why orientation does NOT matter)
axp = ax[1,1]
ang=[fnum(r["bondAngle_deg"]) for r in inv]
axp.hist(ang,bins=18,color="#3d9970",alpha=0.8,edgecolor="k")
axp.axvline(90,color="k",ls="--",lw=1,label="perpendicular")
axp.set_xlabel("native F8 bond angle vs f̂ (deg)"); axp.set_ylabel("count")
axp.set_title(f"(f) native bond orientations (n={len(ang)}, span 13–157°)\nyet F2/F3 α=1.0 (zero-rest isotropy)")
axp.legend(fontsize=8)

# (g) trap robustness: k_obs vs assigned across trap bracket (F1)
axp = ax[1,2]
for tp,c in [(0.02,"#8ecae6"),(0.05,"#219ebc"),(0.10,"#023047")]:
    d=[(fnum(r["assigned_pNnm"]),fnum(r["kObs_pNnm"])) for r in transfer if r["arm"]=="F1" and fnum(r["trap_pNnm"])==tp]
    axp.plot([p[0] for p in d],[p[1] for p in d],"o-",color=c,label=f"trap {tp}")
axp.set_xlabel("assigned k$_{F8}$ (pN/nm)"); axp.set_ylabel("k$_{obs}$ (pN/nm)")
axp.set_title("(g) trap robustness: k$_{obs}$ saturates → k$_{trap}$\n(k$_{motor}$ unidentifiable at high k)")
axp.legend(fontsize=8); axp.grid(alpha=.3)

# (h) timestep stability
axp = ax[1,3]
d1=[(fnum(r["dt_s"]),fnum(r["kMotor_pNnm"])) for r in ts if r["arm"]=="F1"]
d4=[(fnum(r["dt_s"]),fnum(r["kObs_pNnm"])) for r in ts if r["arm"]=="F6"]
axp.plot([p[0] for p in d1],[p[1] for p in d1],"o-",color="#2b7bba",label="F1 k=1 (k$_{motor}$)")
axp.plot([p[0] for p in d4],[p[1] for p in d4],"s-",color="#d1495b",label="F6 k=4 (k$_{obs}$)")
axp.set_xscale("log"); axp.set_xlabel("dt (s)"); axp.set_ylabel("stiffness (pN/nm)")
axp.set_title("(h) timestep stability"); axp.legend(fontsize=8); axp.grid(alpha=.3)

fig.tight_layout(rect=[0,0,1,0.96])
fig.savefig(out, dpi=110)
print("wrote", out)
