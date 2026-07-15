#!/usr/bin/env python3
"""Experiment 3D — axial-stroke geometry remapping: summary figure.

Usage: python3 scripts/twobody3d_analyze.py <csv_dir> <out.png>

Panels: (1) kinematic search heatmap + Pareto/symmetric line; (2) old 3C vs improved 3D trajectory;
(3) axial vs transverse (3C vs 3D candidates); (4) stiffness bracket + load response; (5) optional
transverse-registration (η T1) effect; (6) timestep + work-ledger convergence.
"""
import sys, csv, os
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

D, OUT = sys.argv[1], sys.argv[2]

def load(name):
    p = os.path.join(D, name)
    if not os.path.exists(p): return []
    with open(p) as f: return list(csv.DictReader(f))

def col(rows, k): return np.array([float(r[k]) for r in rows])

inv   = load("geometry_inventory.csv")
par   = load("pareto.csv")
traj  = load("trajectory_refinedBest.csv")
summ  = load("candidate_summary.csv")
load_r= load("load_response_refinedBest.csv")
full  = load("full_mechanics.csv")
eta   = load("eta_T1_passive.csv")
tsl   = load("timestep_ledger.csv")

# 3C baseline (measured, hard-coded from trajectory_audit)
C3C = dict(axial=3.62, trans=6.02, kext=0.639, stroke=3.62)

fig, ax = plt.subplots(2, 3, figsize=(16.5, 9.5))
fig.suptitle("Experiment 3D — axial-stroke geometry remapping of the 3C two-body motor "
             "(non-canonical, default-off)", fontsize=13, fontweight="bold")

# ---- (1) kinematic search: transverse heatmap over phiPre×dTheta + symmetric line + Pareto ----
a = ax[0,0]
if inv:
    phi = col(inv,"phiPre_deg"); dth = col(inv,"dTheta_deg"); tr = np.abs(col(inv,"transverse_nm"))
    P = sorted(set(phi)); T = sorted(set(dth))
    Z = np.full((len(T),len(P)), np.nan)
    for r in inv:
        i=T.index(float(r["dTheta_deg"])); j=P.index(float(r["phiPre_deg"])); Z[i,j]=abs(float(r["transverse_nm"]))
    im=a.pcolormesh(P,T,Z,shading="auto",cmap="viridis",vmax=6)
    fig.colorbar(im,ax=a,label="|transverse| (nm), kinematic")
    # symmetric prediction line phiPre = dTheta/2
    tt=np.array(T); a.plot(tt/2, tt, "w--", lw=1.6, label="φ_pre = Δθ/2 (predicted min)")
    if par:
        a.plot(col(par,"phiPre_deg"), col(par,"dTheta_deg"), "r*", ms=15, label="Pareto (feasible)")
    a.plot([30],[60],"o",mfc="none",mec="magenta",ms=16,mew=2.2,label="leader 30°/60°")
    a.plot([-30],[60],"x",color="red",ms=12,mew=2.5,label="3C (φ_pre=−30°)")
    a.set_xlabel("pre-stroke lever angle φ_pre (deg)"); a.set_ylabel("converter target Δθ (deg)")
    a.set_title("(1) Kinematic search: transverse vs geometry\nsymmetric swing zeros the arc sagitta")
    a.legend(fontsize=7, loc="upper left")

# ---- (2) trajectory: 3C vs 3D ----
a = ax[0,1]
if traj:
    names=[r["point"] for r in traj]; db=col(traj,"dDotB_nm"); tv=col(traj,"transverse_nm")
    # 3D filament & F8 vectors in (axial=−dDotB along pointed, transverse)
    def pt(nm):
        i=names.index(nm); return db[i], tv[i]
    for nm,cl in [("filamentCOM","tab:blue"),("F8pt","tab:green"),("converter","tab:orange")]:
        x,y=pt(nm); a.annotate("", xy=(x,y), xytext=(0,0), arrowprops=dict(arrowstyle="->",color=cl,lw=2))
        a.plot([],[],color=cl,lw=2,label="3D "+nm)
    # 3C filament (measured): axial −3.62 along b̂, transverse 6.02
    a.annotate("", xy=(-3.62,6.02), xytext=(0,0), arrowprops=dict(arrowstyle="->",color="red",lw=2,ls="--"))
    a.plot([],[],color="red",lw=2,ls="--",label="3C filament (−3.62, 6.02)")
    a.axhline(0,color="k",lw=0.5); a.axvline(0,color="k",lw=0.5)
    a.set_xlim(-8.6, 0.8); a.set_ylim(-0.8, 6.8)
    a.set_xlabel("displacement · b̂  (nm)   [pointed = negative]"); a.set_ylabel("transverse (nm)")
    a.set_title("(2) Pre→post trajectory: 3C (large arc drop)\nvs 3D leader (near-axial)")
    a.legend(fontsize=8, loc="upper left"); a.grid(alpha=0.3)

# ---- (3) axial vs transverse: 3C vs 3D candidates ----
a = ax[1,0]
a.axhspan(0,2,color="green",alpha=0.08,label="|trans| target ≤2 nm")
a.axvspan(5,8,color="blue",alpha=0.06,label="axial 5–8 nm band")
if summ:
    for r in summ:
        s=float(r["stroke_ref_nm"]); t=float(r["trans_ref_nm"]); nm=r["cand"]
        a.plot(s,t,"o",ms=9,color="tab:blue")
        a.annotate(nm, (s,t), fontsize=6.5, xytext=(3,3), textcoords="offset points")
a.plot(C3C["stroke"], C3C["trans"], "rX", ms=14, label="3C baseline")
a.plot(6.91, 0.09, "*", ms=18, color="magenta", label="3D leader 30°/60°")
a.set_xlabel("axial (pointedward) filament stroke (nm)"); a.set_ylabel("transverse displacement (nm)")
a.set_title("(3) Axial vs transverse: 3C → 3D\nboth requirements met by geometry alone")
a.legend(fontsize=7.5); a.grid(alpha=0.3)

# ---- (4) stiffness bracket + load response ----
a = ax[1,1]
if load_r:
    L=col(load_r,"load_pN"); g=col(load_r,"glideP_nm"); comp=col(load_r,"completion"); gf=col(load_r,"genForce_pN")
    a.plot(L,g,"o-",color="tab:blue",label="delivered stroke (nm)")
    a.plot(L,gf,"s--",color="tab:red",label="generated force (pN)")
    a.set_xlabel("opposing load (pN)"); a.set_ylabel("stroke (nm) / force (pN)")
    a2=a.twinx(); a2.plot(L,comp,"^:",color="tab:green",label="completion"); a2.set_ylabel("converter completion",color="tab:green")
    a2.tick_params(axis="y",labelcolor="tab:green")
    a.set_title("(4) Load response (leader): stroke↓, force↑,\ncompletion↓ under opposing load — no servo")
    a.legend(fontsize=8, loc="center left")
    # annotate stiffness
    if full:
        kext=col(full,"kExt_pNnm"); kext=kext[np.isfinite(kext)]
        a.text(0.02,0.03,f"k_ext bracket span {kext.min():.2f}–{kext.max():.2f} pN/nm (ref 0.645)",
               transform=a.transAxes,fontsize=7.5,bbox=dict(fc="white",alpha=0.7))

# ---- (5) optional transverse-registration η (T1 passive) ----
a = ax[0,2]
if eta:
    ke=col(eta,"kEta_pNnm"); tf=col(eta,"transFinal_nm"); ax_=col(eta,"axial_nm")
    m=ke>0
    a.semilogx(ke[m], tf[m], "o-", color="tab:purple", label="transverse (nm)")
    a.axhline(tf[~m][0] if (~m).any() else np.nan, color="gray", ls="--", label="η-off baseline")
    a.set_xlabel("k_η  (pN/nm)"); a.set_ylabel("transverse (nm)")
    a.set_title("(5) Optional η (T1 passive) on the 3C geometry\nlateral compliance reduces transverse — illustrative, NOT needed")
    a.legend(fontsize=8); a.grid(alpha=0.3, which="both")
    a.invert_xaxis()

# ---- (6) timestep + work ledger convergence ----
a = ax[1,2]
if tsl:
    dtv=col(tsl,"dt_s"); st=np.abs(col(tsl,"extStroke_nm")); pf=col(tsl,"peakF_pN"); wr=col(tsl,"work_residual")
    a.semilogx(dtv, st, "o-", color="tab:blue", label="external stroke (nm)")
    a.semilogx(dtv, pf, "s-", color="tab:red", label="plateau force (pN)")
    a.set_xlabel("timestep dt (s)"); a.set_ylabel("stroke (nm) / force (pN)")
    a2=a.twinx(); a2.semilogx(dtv, wr, "^--", color="tab:gray", label="work residual")
    a2.set_ylabel("work-ledger residual", color="tab:gray"); a2.tick_params(axis="y",labelcolor="tab:gray")
    a.set_title("(6) Timestep: stroke & force dt-invariant;\nwork residual converges O(dt) (fast-lever artifact)")
    a.legend(fontsize=8, loc="center right"); a.invert_xaxis()

plt.tight_layout(rect=[0,0,1,0.97])
plt.savefig(OUT, dpi=115)
print("# wrote", OUT)
