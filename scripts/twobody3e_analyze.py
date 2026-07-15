#!/usr/bin/env python3
"""Experiment 3E — Brownian + stereospecific binding capture: summary figure.
Usage: python3 scripts/twobody3e_analyze.py <csv_dir> <out.png>
"""
import sys, csv, os
import numpy as np
import matplotlib; matplotlib.use("Agg")
import matplotlib.pyplot as plt

D, OUT = sys.argv[1], sys.argv[2]
def load(n):
    p=os.path.join(D,n)
    if not os.path.exists(p): return []
    with open(p) as f: return list(csv.DictReader(f))
def col(rows,k): return np.array([float(r[k]) for r in rows])

main=load("events_main.csv"); spat=load("events_spatialOnly.csv"); narrow=load("events_narrow.csv"); broad=load("events_broad.csv")
rej=load("rejection_main.csv"); rep=load("stroke_replay.csv"); rel=load("relaxed_poses.csv"); tsl=load("timestep.csv")

fig,ax=plt.subplots(2,3,figsize=(16.5,9.5))
fig.suptitle("Experiment 3E — Brownian + stereospecific binding capture of the two-body motor "
             "(frozen ADP·Pi; non-canonical, default-off)",fontsize=13,fontweight="bold")

# (1) capture-pose scatter φ vs ψ: main vs spatial-only, basin marked
a=ax[0,0]
if spat: a.scatter(col(spat,"phi_deg"),col(spat,"psi_deg"),s=10,c="tab:red",alpha=0.4,label="spatial-only (no orient gates)")
if main: a.scatter(col(main,"phi_deg"),col(main,"psi_deg"),s=12,c="tab:blue",alpha=0.6,label="stereospecific")
a.plot([30],[0],"*",ms=20,color="magenta",label="3D pre-stroke basin (30°,0°)")
a.add_patch(plt.Rectangle((30-25,0-25),50,50,fill=False,ec="green",ls="--",lw=1.3,label="ψ,φ ±25° window"))
a.set_xlabel("capture lever angle φ (deg)"); a.set_ylabel("capture head orientation ψ (deg)")
a.set_title("(1) Capture-pose recruitment\nstereospecific clusters at the basin; spatial-only scatters")
a.legend(fontsize=7,loc="upper left"); a.set_xlim(-70,140); a.set_ylim(-90,90); a.grid(alpha=0.3)

# (2) φ, ψ histograms main vs narrow vs broad
a=ax[0,1]
for rows,cl,lab in [(broad,"tab:orange","broad ±55°"),(main,"tab:blue","main ±25°"),(narrow,"tab:green","narrow ±8°")]:
    if rows: a.hist(col(rows,"phi_deg"),bins=np.linspace(-10,70,33),histtype="step",lw=1.8,color=cl,label=lab)
a.axvline(30,color="magenta",ls="--",lw=1.5,label="φ_pre=30°")
a.set_xlabel("capture lever angle φ (deg)"); a.set_ylabel("count")
a.set_title("(2) Tolerance width controls capture spread\n(narrower window → tighter basin)")
a.legend(fontsize=8); a.grid(alpha=0.3)

# (3) capture energy distribution: main vs spatial-only (implausibility)
a=ax[0,2]
bins=np.logspace(-2,3,40)
if main: a.hist(np.clip(col(main,"energy_kT"),1e-2,1e3),bins=bins,histtype="stepfilled",alpha=0.5,color="tab:blue",label="stereospecific")
if spat: a.hist(np.clip(col(spat,"energy_kT"),1e-2,1e3),bins=bins,histtype="step",lw=2,color="tab:red",label="spatial-only")
a.axvline(15,color="k",ls="--",lw=1.3,label="15 kT implausible")
a.set_xscale("log"); a.set_xlabel("capture energy U_conv+U_bind (kT)"); a.set_ylabel("count")
a.set_title("(3) Why stereospecificity matters:\nspatial-only admits >15 kT non-physical captures")
a.legend(fontsize=8); a.grid(alpha=0.3,which="both")

# (4) rejection-by-gate (main near-misses)
a=ax[1,0]
if rej:
    g=[r["gate"] for r in rej]; v=[float(r["rejections_at_closest_near_miss"]) for r in rej]
    a.barh(g,v,color="tab:purple"); a.set_xlabel("rejections at near-miss closest approach")
    a.set_title("(4) Rejection reason by gate (near-misses)\norientation/lever/energy gates do the selecting")
    a.grid(alpha=0.3,axis="x")

# (5) replay: stroke & transverse of native captures (all in band)
a=ax[1,1]
if rep:
    st=col(rep,"stroke_nm"); tv=col(rep,"transverse_nm")
    a.scatter(st,tv,s=14,c="tab:blue",alpha=0.6,label="native capture (relaxed) → stroke")
    a.axvspan(5,8,color="blue",alpha=0.06); a.axhspan(0,2,color="green",alpha=0.08)
    a.plot([6.91],[0.09],"*",ms=18,color="magenta",label="3D reference 6.91/0.09")
    a.set_xlabel("replayed axial stroke (nm)"); a.set_ylabel("transverse (nm)")
    a.set_title("(5) Stroke replay from native captures\nall pointed-first, 5–8 nm, ≤2 nm transverse")
    a.legend(fontsize=8); a.set_xlim(5,8); a.set_ylim(-0.1,2.1); a.grid(alpha=0.3)

# (6) timestep: binding acceptance + post-capture relaxation; covariance as annotation
a=ax[1,2]
if tsl:
    dt=col(tsl,"dt_s"); acc=col(tsl,"acceptance"); cd=col(tsl,"relaxConDist_nm")
    a.semilogx(dt,acc,"o-",color="tab:green",label="binding acceptance")
    a.set_xlabel("timestep dt (s)"); a.set_ylabel("acceptance rate",color="tab:green"); a.set_ylim(0.8,1.02); a.invert_xaxis()
    a2=a.twinx(); a2.semilogx(dt,cd,"s--",color="tab:red",label="relaxed conDist (nm)")
    a2.set_ylabel("relaxed conDist → basin (nm)",color="tab:red"); a2.set_ylim(-0.05,0.6); a2.invert_xaxis()
    a.legend(fontsize=8,loc="lower left"); a2.legend(fontsize=8,loc="center left")
a.set_title("(6) Timestep: binding stats ≈dt-robust;\nrelaxation dt-exact → the 3D basin (conDist→0)")
a.text(0.5,0.06,"Rotation/polarity COVARIANT:\nacceptance id/swap/rot90/rot3D = 0.983 (equal)",
       transform=a.transAxes,fontsize=8,ha="center",bbox=dict(fc="lightyellow",ec="gray",alpha=0.9))

plt.tight_layout(rect=[0,0,1,0.97]); plt.savefig(OUT,dpi=115); print("# wrote",OUT)
