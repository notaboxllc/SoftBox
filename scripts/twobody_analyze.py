#!/usr/bin/env python3
"""Experiment 3A — two-body converter motor: summary figure.
Usage: python3 scripts/twobody_analyze.py RUN_LOGS/twobody_converter/csv OUT.png
"""
import sys, csv, os
import matplotlib; matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

d = sys.argv[1] if len(sys.argv) > 1 else "RUN_LOGS/twobody_converter/csv"
out = sys.argv[2] if len(sys.argv) > 2 else "RUN_LOGS/twobody_converter/exp3a_summary.png"
def load(n):
    with open(os.path.join(d, n)) as f: return list(csv.DictReader(f))
def fn(x):
    try: return float(x)
    except: return np.nan

s2 = load("stage2_kappa_sweep.csv"); s3 = load("stage3_surface.csv"); s4 = load("stage4_stroke.csv")
s5 = load("stage5_load.csv"); led = load("ledger.csv"); trap = load("trap_robustness.csv"); ts = load("timestep.csv")

fig, ax = plt.subplots(2, 4, figsize=(19, 9))
fig.suptitle("Experiment 3A — [NON-CANONICAL TWO-BODY PROTOTYPE] head–converter–lever motor  (rigid anchor; ONE converter DOF θ; production F8)",
             fontsize=13, fontweight="bold")

# (a) k_ext vs κ  (Stage 2, k_F8=1) — series curve
axp = ax[0,0]
r = [x for x in s2 if fn(x["kF8_pNnm"])==1.0 and np.isfinite(fn(x["kappa_pNnmrad2"]))]
kap = [fn(x["kappa_pNnmrad2"]) for x in r]; km = [fn(x["kMotor_pNnm"]) for x in r]
axp.semilogx([k if k>0 else 0.3 for k in kap], km, "o-", color="#2b7bba")
axp.axhline(1.0, ls="--", color="k", lw=1, label="k$_{F8}$=1 (fixed-head ceiling)")
axp.set_xlabel("converter κ$_θ$ (pN·nm/rad²)"); axp.set_ylabel("blinded k$_{ext}$ (pN/nm)")
axp.set_title("(a) k$_{ext}$ vs κ$_θ$  (series: κ→∞ ⇒ fixed head)"); axp.legend(fontsize=8); axp.grid(alpha=.3)

# (b) k_ext vs k_F8 at κ=100,1000  (Stage 3) — the k_ext ≤ k_F8 ceiling
axp = ax[0,1]
for kp,c,m in [(100,"#219ebc","o"),(1000,"#023047","s")]:
    rr=[x for x in s3 if fn(x["kappa_pNnmrad2"])==kp]
    axp.plot([fn(x["kF8_pNnm"]) for x in rr],[fn(x["kMotor_pNnm"]) for x in rr],m+"-",color=c,label=f"κ={kp}")
kf=[0.5,1,1.5,2]; axp.plot(kf,kf,"k--",lw=1,label="k$_{ext}$=k$_{F8}$ (series ceiling)")
axp.set_xlabel("k$_{F8}$ (pN/nm)"); axp.set_ylabel("blinded k$_{ext}$ (pN/nm)")
axp.set_title("(b) k$_{ext}$ vs k$_{F8}$ (ceiling = k$_{F8}$)"); axp.legend(fontsize=8); axp.grid(alpha=.3)

# (c) 2-D calibration surface (series model vs measured)
axp = ax[0,2]
kf_vals = sorted(set(fn(x["kF8_pNnm"]) for x in s3)); kp_vals = sorted(set(fn(x["kappa_pNnmrad2"]) for x in s3))
Z = np.full((len(kp_vals),len(kf_vals)), np.nan)
for x in s3:
    i=kp_vals.index(fn(x["kappa_pNnmrad2"])); j=kf_vals.index(fn(x["kF8_pNnm"])); Z[i,j]=fn(x["kMotor_pNnm"])
im=axp.imshow(Z, origin="lower", aspect="auto", cmap="viridis",
              extent=[0,len(kf_vals),0,len(kp_vals)])
axp.set_xticks(np.arange(len(kf_vals))+0.5); axp.set_xticklabels([f"{v:g}" for v in kf_vals])
axp.set_yticks(np.arange(len(kp_vals))+0.5); axp.set_yticklabels([f"{v:g}" for v in kp_vals])
for i in range(len(kp_vals)):
    for j in range(len(kf_vals)):
        if np.isfinite(Z[i,j]): axp.text(j+0.5,i+0.5,f"{Z[i,j]:.2f}",ha="center",va="center",color="w",fontsize=7)
axp.set_xlabel("k$_{F8}$ (pN/nm)"); axp.set_ylabel("κ$_θ$ (pN·nm/rad²)")
axp.set_title("(c) 2-D calibration surface k$_{ext}$"); fig.colorbar(im,ax=axp,fraction=0.046)

# (d) NO TRADEOFF: k_ext AND stroke completion both rise with κ
axp = ax[0,3]
# stroke completion (1-incompleteFrac) at 5nm target vs κ, and k_ext vs κ
kaps=[30,100,1000]
comp=[]; kext=[]
for kp in kaps:
    row=[x for x in s4 if fn(x["kappa_pNnmrad2"])==kp and fn(x["strokeTarget_nm"])==5.0][0]
    comp.append(1-fn(row["incompleteFrac"]))
    kr=[x for x in s2 if fn(x["kF8_pNnm"])==1.0 and fn(x["kappa_pNnmrad2"])==kp][0]
    kext.append(fn(kr["kMotor_pNnm"]))
axp.plot(kaps, comp, "o-", color="#d1495b", label="stroke completion")
axp.plot(kaps, kext, "s-", color="#2b7bba", label="k$_{ext}$ (pN/nm)")
axp.set_xscale("log"); axp.set_xlabel("κ$_θ$ (pN·nm/rad²)"); axp.set_ylabel("completion  /  k$_{ext}$")
axp.set_title("(d) NO tradeoff: stiff converter ⇒ BOTH ↑"); axp.legend(fontsize=8); axp.grid(alpha=.3); axp.set_ylim(0,1.05)

# (e) unloaded stroke vs target (Stage 4, κ=100 & 1000)
axp = ax[1,0]
for kp,c,m in [(100,"#e0a458","o"),(1000,"#3d9970","s")]:
    rr=[x for x in s4 if fn(x["kappa_pNnmrad2"])==kp]
    axp.plot([fn(x["strokeTarget_nm"]) for x in rr],[fn(x["strokeExt_nm"]) for x in rr],m+"-",color=c,label=f"κ={kp}")
axp.plot([3,9],[3,9],"k--",lw=1,label="geometric R$_A$sinθ")
axp.set_xlabel("target stroke (nm, rigid limit)"); axp.set_ylabel("external stroke (nm)")
axp.set_title("(e) unloaded stroke (no relatch)"); axp.legend(fontsize=8); axp.grid(alpha=.3)

# (f) stroke completion + generated force vs LOAD (Stage 5) — the stall
axp = ax[1,1]
ld=[fn(x["load_pN"]) for x in s5]; cp=[fn(x["completion"]) for x in s5]; gf=[abs(fn(x["genForce_pN"])) for x in s5]
axp.plot(ld,cp,"o-",color="#d1495b",label="converter completion")
axp2=axp.twinx(); axp2.plot(ld,gf,"s--",color="#6a4c93",label="generated force (pN)")
axp.set_xlabel("opposing load (pN)"); axp.set_ylabel("completion",color="#d1495b"); axp2.set_ylabel("gen. force (pN)",color="#6a4c93")
axp.set_title("(f) load → stall (completion↓, force↑; isometric ≈2.6 pN)"); axp.grid(alpha=.3)
axp.legend(fontsize=7,loc="lower left"); axp2.legend(fontsize=7,loc="upper right")

# (g) work-ledger closure (stacked)
axp = ax[1,2]
L=led[0]; parts=[fn(L["dU_conv_J"]),fn(L["dU_F8_J"]),fn(L["Wtrap_J"]),fn(L["diss_J"])]
labs=["ΔU$_{conv}$","ΔU$_{F8}$","ΔU$_{trap}$","dissip."]
tot=fn(L["dU_target_J"]); cols=["#8ecae6","#219ebc","#e0a458","#d1495b"]
bottom=0
for pv,lb,c in zip(parts,labs,cols): axp.bar(0,pv,bottom=bottom,color=c,label=lb); bottom+=pv
axp.bar(1,tot,color="#023047",label="ΔU$_{target}$ (injected)")
axp.set_xticks([0,1]); axp.set_xticklabels(["accounted","injected"])
axp.set_ylabel("energy (J)"); axp.set_title(f"(g) work closure (residual {fn(L['residual'])*100:.1f}%)"); axp.legend(fontsize=7)

# (h) timestep + trap robustness
axp = ax[1,3]
for cs,c,m in [("free(κ=0)","#8ecae6","o"),("inter(κ=100)","#219ebc","s"),("nearRigid(κ=1000)","#023047","^")]:
    rr=[x for x in ts if x["case"]==cs and np.isfinite(fn(x["kMotor_pNnm"]))]
    if rr: axp.plot([fn(x["dt_s"]) for x in rr],[fn(x["kMotor_pNnm"]) for x in rr],m+"-",color=c,label=cs)
axp.set_xscale("log"); axp.set_xlabel("dt (s)"); axp.set_ylabel("k$_{ext}$ (pN/nm)")
axp.set_title("(h) timestep stability (trap-robust: k flat)"); axp.legend(fontsize=7); axp.grid(alpha=.3)

fig.tight_layout(rect=[0,0,1,0.96])
fig.savefig(out, dpi=110)
print("wrote", out)
