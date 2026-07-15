#!/usr/bin/env python3
"""Experiment 3C — topologically faithful two-body motor: summary figure."""
import sys, csv, os
import matplotlib; matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.patches as mp
import numpy as np
d = sys.argv[1] if len(sys.argv)>1 else "RUN_LOGS/twobody_topology/csv"
out = sys.argv[2] if len(sys.argv)>2 else "RUN_LOGS/twobody_topology/exp3c_summary.png"
def load(n):
    with open(os.path.join(d,n)) as f: return list(csv.DictReader(f))
def fn(x):
    try: return float(x)
    except: return np.nan
ad=load("angular_decomposition.csv"); pm=load("passive_map.csv"); al=load("active_load.csv")
lg=load("legacy_compare.csv"); tj=load("trajectory_audit.csv"); ld=load("ledger.csv"); pol=load("polarity_tests.csv")

fig,ax=plt.subplots(3,3,figsize=(17,13.5))
fig.suptitle("Experiment 3C — topologically faithful head–converter–lever motor  [NON-CANONICAL PROTOTYPE]  (Outcome B: viable after material-frame flip)",fontsize=13,fontweight="bold")

# (a) legacy cam vs corrected topology (schematic + numbers)
axp=ax[0,0]; axp.axis("off"); axp.set_title("(a) legacy cam vs corrected topology")
c=[r for r in lg if r["model"]=="corrected3C"][0]; l=[r for r in lg if r["model"]=="legacyCam"][0]
rows=[("neck–lever Δφ","0° (FROZEN)","%.0f° (SWINGS)"%fn(c["dPhi_deg"])),
      ("head Δψ","%.0f°"%fn(l["dPsi_deg"]),"%.1f°"%fn(c["dPsi_deg"])),
      ("k_ext (pN/nm)","%.2f"%fn(l["kExt_pNnm"]),"%.2f"%fn(c["kExt_pNnm"])),
      ("glide·p̂ (nm)","%.2f (BARBED)"%fn(l["glideDotP_nm"]),"%.2f (POINTED)"%fn(c["glideDotP_nm"])),
      ("moving bodies","1 (pinned cam)","2 (head+lever)")]
axp.text(0.02,0.88,"",fontsize=9); axp.text(0.02,0.9,"quantity",fontweight="bold",fontsize=9); axp.text(0.42,0.9,"legacy 3B",fontweight="bold",fontsize=9,color="#888"); axp.text(0.74,0.9,"corrected 3C",fontweight="bold",fontsize=9,color="#023047")
for i,(q,a,b) in enumerate(rows):
    y=0.78-i*0.15; axp.text(0.02,y,q,fontsize=9); axp.text(0.42,y,a,fontsize=8,color="#888"); axp.text(0.74,y,b,fontsize=8,color="#023047",fontweight="bold")

# (b) head vs neck motion vs kbind
axp=ax[0,1]
kb=[fn(r["kbind_pNnmrad2"]) if r["kbind_pNnmrad2"]!="inf" else 2000 for r in ad]
axp.plot(kb,[abs(fn(r["dPhi_deg"])) for r in ad],"o-",color="#2b7bba",label="|Δφ| neck–lever swing")
axp.plot(kb,[abs(fn(r["dPsi_deg"])) for r in ad],"s-",color="#d1495b",label="|Δψ| head rotation")
axp.set_xscale("symlog"); axp.set_xlabel("κ_bind (pN·nm/rad²)"); axp.set_ylabel("angular change (deg)")
axp.set_title("(b) strongly-bound head ⇒ motion is neck–lever swing"); axp.legend(fontsize=8); axp.grid(alpha=.3)

# (c) passive stiffness vs kbind (free→bound)
axp=ax[0,2]
r=[x for x in pm if fn(x["kF8_pNnm"])==1 and fn(x["kconv_pNnmrad2"])==1000]
kbp=[fn(x["kbind_pNnmrad2"]) if fn(x["kbind_pNnmrad2"])<1e8 else 2000 for x in r]
axp.plot(kbp,[fn(x["kMotor_pNnm"]) for x in r],"o-",color="#3d9970")
axp.axhline(1.0,ls="--",color="k",lw=1,label="k_F8=1")
axp.set_xscale("symlog"); axp.set_xlabel("κ_bind (pN·nm/rad²)"); axp.set_ylabel("k_ext (pN/nm)")
axp.set_title("(c) free-head (soft) → bound-head (skeletal)"); axp.legend(fontsize=8); axp.grid(alpha=.3)

# (d) pre/post trajectory: F8 point + filament + converter (b̂ vs ê_up plane)
axp=ax[0,2]  # overwrite? no -> use ax[1,0]
axp=ax[1,0]
for r in tj:
    if r["point"] in ("F8pt","converter","filamentCOM","motorCenter"):
        col={"F8pt":"#d1495b","converter":"#6a4c93","filamentCOM":"#2b7bba","motorCenter":"#e0a458"}[r["point"]]
        axp.annotate("",xy=(fn(r["postB"])*1e3,fn(r["postUp"])*1e3),xytext=(fn(r["preB"])*1e3,fn(r["preUp"])*1e3),arrowprops=dict(arrowstyle="->",color=col,lw=2))
        axp.plot(fn(r["preB"])*1e3,fn(r["preUp"])*1e3,"o",color=col,ms=4,label=r["point"])
axp.axvline(0,color="#aaa",lw=0.5); axp.set_xlabel("along barbed b̂ (nm)"); axp.set_ylabel("along ê_up (nm)")
axp.set_title("(d) pre→post trajectories (corrected/forward: all move POINTED −b̂)"); axp.legend(fontsize=7); axp.grid(alpha=.3)

# (e) stroke vs load
axp=ax[1,1]
lo=[fn(r["load_pN"]) for r in al]; gl=[fn(r["glideDotP_nm"]) for r in al]; cp=[fn(r["completion"]) for r in al]
axp.plot(lo,gl,"o-",color="#2b7bba",label="glide·p̂ (external stroke)")
axp2=axp.twinx(); axp2.plot(lo,cp,"s--",color="#d1495b",label="converter completion")
axp.set_xlabel("opposing load (pN)"); axp.set_ylabel("glide·p̂ (nm)",color="#2b7bba"); axp2.set_ylabel("completion",color="#d1495b")
axp.set_title("(e) load reduces external stroke (converter still completes)"); axp.grid(alpha=.3)
axp.legend(fontsize=7,loc="upper right"); axp2.legend(fontsize=7,loc="lower left"); axp2.set_ylim(0,1.1)

# (f) polarity reversal + covariance (glide·p̂ across conditions)
axp=ax[1,2]
def gval(cfg):
    for r in pol:
        if r["config"]==cfg and r["glideDotP_nm"] not in ("","NaN"): return fn(r["glideDotP_nm"])
    return np.nan
labels=["identity\n(P2)","swap\npolarity(P3)","rot90\n(P4)","rot3D\n(P4)","reverse\ntarget(P5)"]
vals=[gval("freeFil"),gval("swapPolarity"),gval("rot90"),gval("rot3D"),gval("reverseTarget(control)")]
cols=["#3d9970","#e0a458","#2b7bba","#219ebc","#8888aa"]
axp.bar(labels,vals,color=cols); axp.axhline(0,color="k",lw=1)
axp.set_ylabel("glide·p̂ (nm, polarity-relative)"); axp.set_title("(f) P3 swap/P4 rotate: relative-preserved; P5 reverses(control)")
for i,v in enumerate(vals): axp.text(i,v+(0.1 if v>=0 else -0.3),f"{v:.2f}",ha="center",fontsize=8)

# (g) work-ledger dt convergence
axp=ax[2,0]
dts=[fn(r["dt_s"]) for r in ld]; res=[fn(r["residual"]) for r in ld]
axp.loglog(dts,res,"o-",color="#d1495b")
axp.axhline(0.05,ls="--",color="k",lw=1,label="5% tol")
axp.set_xlabel("dt (s)"); axp.set_ylabel("work-closure residual")
axp.set_title("(g) ledger converges O(dt) (fast lever/converter mode)"); axp.legend(fontsize=8); axp.grid(alpha=.3,which="both")

# (h) k_ext(kconv) at a few kbind
axp=ax[2,1]
for kbv,c in [(32,"#8ecae6"),(128,"#219ebc"),(512,"#023047")]:
    r=[x for x in pm if fn(x["kF8_pNnm"])==1 and fn(x["kbind_pNnmrad2"])==kbv]
    if r: axp.plot([fn(x["kconv_pNnmrad2"]) for x in r],[fn(x["kMotor_pNnm"]) for x in r],"o-",color=c,label=f"κ_bind={kbv}")
axp.set_xscale("log"); axp.set_xlabel("κ_conv (pN·nm/rad²)"); axp.set_ylabel("k_ext (pN/nm)")
axp.set_title("(h) passive map k_ext(κ_conv, κ_bind)"); axp.legend(fontsize=8); axp.grid(alpha=.3)

# (i) handedness: original vs corrected (F8·b̂)
axp=ax[2,2]; axp.axis("off"); axp.set_title("(i) material-frame handedness (measured, non-tautological)")
txt=["Fixed motor-frame Δθ; b̂ used ONLY in binding pose + analysis","(access audit: postTargetSel=0, converterTorque=0, routing=0)","",
     "original Δθ=−60°:  F8·b̂ = +7.60 nm  → BARBEDWARD (reversed)","corrected Δθ=+60°:  F8·b̂ = −3.98 nm  → POINTEDWARD ✓","",
     "⇒ the 3B/legacy stroke direction was biologically BACKWARD;","   corrected by flipping the material-frame pre/post ordering","   (Outcome B), NOT by negating force/displacement.","",
     "Corrected: pointed-first glide, pointed force on actin,","strongly-bound head → neck–lever swing, all 26 gates PASS."]
for i,t in enumerate(txt): axp.text(0.02,0.94-i*0.083,t,fontsize=9,color="#023047" if "✓" in t or "Outcome" in t or "PASS" in t else "#333")

fig.tight_layout(rect=[0,0,1,0.965])
fig.savefig(out,dpi=105); print("wrote",out)
