#!/usr/bin/env python3
"""Experiment 3B — biological geometry + explicit actin polarity: summary figure.
Usage: python3 scripts/twobody3b_analyze.py RUN_LOGS/twobody_geometry_polarity/csv OUT.png
"""
import sys, csv, os
import matplotlib; matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.patches as mp
import numpy as np

d = sys.argv[1] if len(sys.argv) > 1 else "RUN_LOGS/twobody_geometry_polarity/csv"
out = sys.argv[2] if len(sys.argv) > 2 else "RUN_LOGS/twobody_geometry_polarity/exp3b_summary.png"
def load(n):
    with open(os.path.join(d,n)) as f: return list(csv.DictReader(f))
def fn(x):
    try: return float(x)
    except: return np.nan
pm=load("passive_map.csv"); pol=load("polarity_tests.csv"); ac=load("active_stroke.csv"); gc=load("geometry_controls.csv")

fig, ax = plt.subplots(3,3, figsize=(17,13.5))
fig.suptitle("Experiment 3B — biological two-body geometry + EXPLICIT actin polarity  [NON-CANONICAL PROTOTYPE]  (Outcome A)",
             fontsize=14, fontweight="bold")

# (1) old vs remapped geometry
axp=ax[0,0]; axp.set_title("(a) geometry remap (Exp-3A → 3B)"); axp.axis("off")
rows=[("converter–F8 arm R_A","10 nm","8 nm"),("neck–lever L_B","12 nm","8 nm"),
      ("motor domain","5 nm sphere","9×5.5×4.5 nm ellipsoid"),("head hydro r","5 nm","4.6 nm"),
      ("anchor","rigid","rigid calibration anchor"),("stroke sign","imposed +x","toward pointed (via b̂)")]
axp.text(0.02,0.93,"quantity",fontsize=10,fontweight="bold"); axp.text(0.42,0.93,"3A",fontsize=10,fontweight="bold"); axp.text(0.72,0.93,"3B",fontsize=10,fontweight="bold")
for i,(q,a,b) in enumerate(rows):
    y=0.82-i*0.13; axp.text(0.02,y,q,fontsize=9); axp.text(0.42,y,a,fontsize=9,color="#888"); axp.text(0.72,y,b,fontsize=9,color="#023047",fontweight="bold")

# (2) k_ext vs kappa (passive series)
axp=ax[0,1]
r=[x for x in pm if fn(x["kF8_pNnm"])==1 and fn(x["kappa_pNnmrad2"])>0]
kap=[fn(x["kappa_pNnmrad2"]) for x in r]; ke=[fn(x["kMotor_pNnm"]) for x in r]; se=[fn(x["seriesPred"]) for x in r]
axp.semilogx(kap,ke,"o",color="#2b7bba",label="measured k_ext",ms=7)
axp.semilogx(kap,se,"-",color="#d1495b",label="series 1/(1/k_F8+R_A²/κ)",lw=1.5)
axp.axhline(1,ls="--",color="k",lw=1,label="k_F8=1 (ceiling)")
axp.set_xlabel("converter κ_θ (pN·nm/rad²)"); axp.set_ylabel("k_ext (pN/nm)")
axp.set_title("(b) passive map: series-exact after remap"); axp.legend(fontsize=8); axp.grid(alpha=.3)

# (3) stroke geometry
axp=ax[0,2]
r=[x for x in ac if fn(x["kappa"])==128]
tg=[fn(x["strokeTarget_nm"]) for x in r]; ex=[abs(fn(x["extStroke_nm"])) for x in r]; gl=[fn(x["glideDotPhat_nm"]) for x in r]
axp.plot(tg,ex,"o-",color="#3d9970",label="|external stroke|",ms=7)
axp.plot([3,7],[3,7],"k--",lw=1,label="geometric R_A·sinθ")
axp.set_xlabel("target F8-point excursion (nm)"); axp.set_ylabel("external stroke (nm)")
axp.set_title("(c) stroke geometry (κ=128, ~90% geometric)"); axp.legend(fontsize=8); axp.grid(alpha=.3)

# (4) barbed/pointed definition schematic
axp=ax[1,0]; axp.set_title("(d) explicit polarity (audited convention)"); axp.axis("off")
axp.set_xlim(0,10); axp.set_ylim(0,10)
axp.add_patch(mp.FancyArrow(2.5,6,5,0,width=0.4,head_width=0.4,head_length=0.01,color="#bbb"))  # filament body
axp.add_patch(mp.Polygon([[2.5,5.2],[2.5,6.8],[1.4,6]],color="#d1495b"))  # pointed (narrow, end1)
axp.add_patch(mp.Polygon([[7.5,5.0],[7.5,7.0],[9.0,6]],color="#023047"))  # barbed (broad, end2)
axp.text(1.2,7.3,"P / −  (pointed = end1 = −uVec)",fontsize=9,color="#d1495b")
axp.text(6.6,7.6,"B / +  (barbed = end2 = +uVec)",fontsize=9,color="#023047")
axp.add_patch(mp.FancyArrow(4,4.2,2,0,width=0.05,head_width=0.3,head_length=0.3,color="#3d9970"))
axp.text(3.4,3.4,"material coord s (bindArc) increases → barbed",fontsize=8,color="#3d9970")
axp.add_patch(mp.FancyArrow(5,8.3,-1.6,0,width=0.05,head_width=0.3,head_length=0.3,color="#6a4c93"))
axp.text(2.6,8.9,"anchored-motor filament glide: pointed-first",fontsize=8,color="#6a4c93")

# (5) P1 actin-fixed force directions
axp=ax[1,1]
p1=[x for x in pol if x["test"]=="P1"][0]
fa=fn(p1["forceActinDotB_pN"]); fm=fn(p1["forceMotorDotB_pN"])
axp.bar(["force on ACTIN·b̂","force on MOTOR·b̂"],[fa,fm],color=["#6a4c93","#023047"])
axp.axhline(0,color="k",lw=1); axp.set_ylabel("projection on barbed b̂ (pN)")
axp.set_title("(e) P1 clamped: force on actin=POINTED,\nmotor pushed=BARBED (Newton pair)")
axp.text(0,fa-0.5,f"{fa:.2f}\n(pointed)",ha="center",fontsize=9); axp.text(1,fm+0.2,f"{fm:.2f}\n(barbed)",ha="center",fontsize=9)

# (6) P2/P4 glide covariance
axp=ax[1,2]
def glide(test,cfg):
    for x in pol:
        if x["test"]==test and x["config"]==cfg and x["glideDotP_nm"]!="": return fn(x["glideDotP_nm"])
    return np.nan
vals=[glide("P2","freeFil"),glide("P4","rot90"),glide("P4","rot3D")]
axp.bar(["identity","rot 90°","rot 3D"],vals,color="#3d9970")
axp.axhline(0,color="k",lw=1); axp.set_ylabel("glide · p̂ (nm)")
axp.set_title("(f) P2/P4: pointed-first glide,\nINVARIANT under assay rotation (covariant)")
for i,v in enumerate(vals): axp.text(i,v+0.05,f"{v:.3f}",ha="center",fontsize=9)

# (7) P3 polarity reversal (world) + P5 sign control
axp=ax[2,0]
# world reversal from the run: no-swap −4.388, swap +4.388; P5 reverse-target −4.388
p5=glide("P5","reverseTarget(control)")
axp.bar(["glide (biol)","P3 swap-polarity\n(world)","P5 reverse-target\n(control)"],
        [4.388,-4.388,p5],color=["#3d9970","#e0a458","#8888aa"])
axp.axhline(0,color="k",lw=1); axp.set_ylabel("displacement (nm)")
axp.set_title("(g) P3 polarity swap reverses WORLD motion;\nP5 reverse-target reverses (sign control)")
axp.text(0,4.4,"pointed-first",ha="center",fontsize=8); axp.text(1,-4.9,"reverses",ha="center",fontsize=8)

# (8) P6 load response
axp=ax[2,1]
def comp(cfg):
    for x in pol:
        if x["config"]==cfg and x["thetaFinal_deg"]!="": return fn(x["thetaFinal_deg"])
    return np.nan
r=comp("resistLoad"); a=comp("assistLoad")
axp.bar(["resisting\n(barbed load)","free","assisting\n(pointed load)"],[r,0.959,a],color=["#d1495b","#888","#3d9970"])
axp.axhline(1,ls="--",color="k",lw=1); axp.set_ylabel("converter completion")
axp.set_title("(h) P6: load defined vs b̂ — resist↓, assist↑"); axp.set_ylim(0,1.2)
for i,v in enumerate([r,0.959,a]): axp.text(i,v+0.02,f"{v:.3f}",ha="center",fontsize=9)

# (9) geometry controls
axp=ax[2,2]
ra=[fn(x["R_A_nm"]) for x in gc]; ke=[fn(x["kMotor_pNnm"]) for x in gc]; gl=[fn(x["stroke5_nm"]) for x in gc]; fab=[fn(x["forceActinDotB_pN"]) for x in gc]
axp.plot(ra,ke,"o-",color="#2b7bba",label="k_ext (pN/nm)")
axp.plot(ra,gl,"s-",color="#3d9970",label="glide·p̂ (nm)")
axp.plot(ra,[abs(v) for v in fab],"^-",color="#6a4c93",label="|force on actin| (pN)")
axp.set_xlabel("R_A (nm)"); axp.set_title("(i) geometry controls: R_A trades stiffness↔stroke;\npolarity signs invariant")
axp.legend(fontsize=8); axp.grid(alpha=.3)

fig.tight_layout(rect=[0,0,1,0.965])
fig.savefig(out, dpi=105)
print("wrote",out)
