#!/usr/bin/env python3
"""Canonical gliding study figures (amendment) — speed/force-balance vs density.

Reads:
  RUN_LOGS/twobody_canonical_gliding/forcebalance/forcebalance_d*_q1.0_t0.15.csv   (calibrated FB curve)
  RUN_LOGS/twobody_canonical_gliding/denssweep/glide_{calibrated,fixed}-*_d*_L4_t0.15.csv (velocity/continuity)
Writes 7 PNGs + a saturating-vs-inhibition fit to RUN_LOGS/twobody_canonical_gliding/figures/.
"""
import csv, glob, os, sys
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from scipy.optimize import curve_fit  # if unavailable, fall back below

ROOT = "RUN_LOGS/twobody_canonical_gliding"
FIG = os.path.join(ROOT, "figures"); os.makedirs(FIG, exist_ok=True)

def load_csvs(pattern):
    rows = []
    for f in sorted(glob.glob(pattern)):
        with open(f) as fh:
            for r in csv.DictReader(fh):
                rows.append(r)
    return rows

def col(rows, k, filt=None):
    out = []
    for r in rows:
        if filt and not filt(r): continue
        try: out.append(float(r[k]))
        except (KeyError, ValueError): out.append(np.nan)
    return np.array(out)

# ---- force-balance curve (calibrated, q=1.0, dur 0.15) ----
fb = [r for r in load_csvs(os.path.join(ROOT, "forcebalance", "forcebalance_d*.csv"))
      if abs(float(r["queryScale"]) - 1.0) < 1e-6 and abs(float(r["dur_s"]) - 0.15) < 1e-6]
fb.sort(key=lambda r: float(r["density"]))
d   = col(fb, "density")
vel = col(fb, "vel_umPerS"); speed = np.abs(vel)
cont= col(fb, "continuity"); bound = col(fb, "avgBound")
prop= col(fb, "avgProp"); drag = col(fb, "avgDrag")
sump= col(fb, "sumProp_pN"); sumd = col(fb, "sumDrag_pN"); net = col(fb, "netAxial_pN")
posI= col(fb, "posImpPerCycle_pNs"); negI = col(fb, "negImpPerCycle_pNs")
atpum = col(fb, "atpPerUm")

# ---- fixed-anchor + calibrated velocity from the density sweep (for the model comparison overlay) ----
def sweep(model):
    rows = load_csvs(os.path.join(ROOT, "denssweep", f"glide_{model}_d*_L4_t0.15.csv"))
    rows.sort(key=lambda r: float(r["density"]))
    return col(rows, "density"), np.abs(col(rows, "vel_umPerS")), col(rows, "vel_sd"), col(rows, "continuity"), col(rows, "avgBound")
dc, vc, vcsd, cc, bc = sweep("calibrated-s2-l40")
df_, vf, vfsd, cf, bf = sweep("fixed-anchor")
def sweep_explicit():  # explicit density subset used dur 0.12
    rows = load_csvs(os.path.join(ROOT, "denssweep", "glide_explicit-s2-l40_d*_L4_t0.12.csv"))
    rows.sort(key=lambda r: float(r["density"]))
    return col(rows, "density"), np.abs(col(rows, "vel_umPerS")), col(rows, "vel_sd")
de, ve, vesd = sweep_explicit()

def save(fig, name):
    fig.tight_layout(); p = os.path.join(FIG, name); fig.savefig(p, dpi=130); plt.close(fig); print("wrote", p)

if len(d) == 0:
    print("no force-balance CSVs yet"); sys.exit(0)

# 1) speed vs density (calibrated FB curve + sweep overlays)
fig, ax = plt.subplots(figsize=(7,4.5))
ax.plot(d, speed, "o-", color="#2c7fb8", label="calibrated FB curve")
if len(dc): ax.errorbar(dc, vc, yerr=vcsd, fmt="s--", color="#31a354", alpha=.7, label="calibrated (sweep)")
if len(de): ax.errorbar(de, ve, yerr=vesd, fmt="D-.", color="#7b3294", alpha=.8, label="explicit (subset)")
if len(df_): ax.errorbar(df_, vf, yerr=vfsd, fmt="^:", color="#d95f0e", alpha=.7, label="fixed-anchor")
ax.set_xlabel("motor density (/µm²)"); ax.set_ylabel("glide speed |v| (µm/s)")
ax.set_title("1 · Speed vs density"); ax.legend(); ax.grid(alpha=.3)
# provisional saturating fit + saturating-plus-inhibition
def hill(x, vmax, k, n): return vmax * x**n / (k**n + x**n)
def sat_inhib(x, vmax, k, ki): return vmax * (x/(k+x)) / (1 + x/ki)
xs = np.linspace(d.min(), d.max(), 200); fitnote = ""
try:
    p1,_ = curve_fit(hill, d, speed, p0=[3, 400, 1.5], maxfev=20000)
    ax.plot(xs, hill(xs, *p1), "-", color="#2c7fb8", alpha=.4, lw=1)
    r1 = 1 - np.sum((speed-hill(d,*p1))**2)/np.sum((speed-speed.mean())**2)
    fitnote += f"Hill: Vmax={p1[0]:.2f} K={p1[1]:.0f} n={p1[2]:.2f} R²={r1:.3f}\n"
    p2,_ = curve_fit(sat_inhib, d, speed, p0=[3,400,5000], maxfev=20000)
    r2 = 1 - np.sum((speed-sat_inhib(d,*p2))**2)/np.sum((speed-speed.mean())**2)
    ax.plot(xs, sat_inhib(xs,*p2), "--", color="#7b3294", alpha=.5, lw=1, label="sat+inhibition")
    fitnote += f"sat+inhib: Vmax={p2[0]:.2f} K={p2[1]:.0f} Ki={p2[2]:.0f} R²={r2:.3f}"
    ax.legend()
except Exception as e:
    fitnote = f"fit skipped: {e}"
ax.text(0.02,0.02, fitnote, transform=ax.transAxes, fontsize=7, va="bottom",
        bbox=dict(boxstyle="round", fc="w", alpha=.7))
save(fig,"fig1_speed_vs_density.png")
open(os.path.join(FIG,"speed_density_fit.txt"),"w").write(fitnote+"\n")

# 2) continuity + bound vs density
fig, ax = plt.subplots(figsize=(7,4.5)); ax2 = ax.twinx()
ax.plot(d, cont, "o-", color="#2c7fb8", label="continuity")
ax2.plot(d, bound, "s--", color="#e6550d", label="avg bound")
ax.set_xlabel("density (/µm²)"); ax.set_ylabel("continuity", color="#2c7fb8"); ax2.set_ylabel("avg chemically bound", color="#e6550d")
ax.set_title("2 · Continuity & bound count vs density"); ax.grid(alpha=.3); ax.set_ylim(0,1.05)
save(fig,"fig2_continuity_bound_vs_density.png")

# 3) summed propulsive / dragging / net force vs density
fig, ax = plt.subplots(figsize=(7,4.5))
ax.plot(d, sump, "o-", color="#31a354", label="Σ propulsive")
ax.plot(d, sumd, "s-", color="#d95f0e", label="Σ dragging")
ax.plot(d, net, "^-", color="#252525", label="net axial")
ax.set_xlabel("density (/µm²)"); ax.set_ylabel("force (pN)")
ax.set_title("3 · Summed propulsive / dragging / net force vs density"); ax.legend(); ax.grid(alpha=.3)
save(fig,"fig3_force_vs_density.png")

# 4) propulsive & dragging motor counts vs density
fig, ax = plt.subplots(figsize=(7,4.5))
ax.plot(d, prop, "o-", color="#31a354", label="propulsive")
ax.plot(d, drag, "s-", color="#d95f0e", label="dragging")
ax.plot(d, bound, "^--", color="#666", alpha=.6, label="total bound")
ax.set_xlabel("density (/µm²)"); ax.set_ylabel("motor count")
ax.set_title("4 · Propulsive & dragging counts vs density"); ax.legend(); ax.grid(alpha=.3)
save(fig,"fig4_counts_vs_density.png")

# 5) positive & negative impulse per ATP cycle vs density
fig, ax = plt.subplots(figsize=(7,4.5))
ax.plot(d, posI, "o-", color="#31a354", label="+impulse / cycle")
ax.plot(d, negI, "s-", color="#d95f0e", label="−impulse / cycle")
ax.plot(d, posI+negI, "^-", color="#252525", label="net / cycle")
ax.set_xlabel("density (/µm²)"); ax.set_ylabel("axial impulse per bound episode (pN·s)")
ax.set_title("5 · Impulse per cycle vs density"); ax.legend(); ax.grid(alpha=.3)
save(fig,"fig5_impulse_vs_density.png")

# 6) speed vs net propulsive force
fig, ax = plt.subplots(figsize=(7,4.5))
sc = ax.scatter(net, speed, c=d, cmap="viridis", s=60)
for i in range(len(d)): ax.annotate(f"{int(d[i])}", (net[i], speed[i]), fontsize=6, xytext=(3,3), textcoords="offset points")
fig.colorbar(sc, label="density (/µm²)")
ax.set_xlabel("net axial motor force (pN)"); ax.set_ylabel("glide speed |v| (µm/s)")
ax.set_title("6 · Speed vs net propulsive force"); ax.grid(alpha=.3)
save(fig,"fig6_speed_vs_netforce.png")

# 7) ATP cycles per micron vs density
fig, ax = plt.subplots(figsize=(7,4.5))
ax.plot(d, atpum, "o-", color="#7b3294")
ax.set_xlabel("density (/µm²)"); ax.set_ylabel("ATP cycles / µm")
ax.set_title("7 · ATP cost per µm vs density (efficiency)"); ax.grid(alpha=.3)
save(fig,"fig7_atp_per_um_vs_density.png")

print("\n".join(["", "FIT SUMMARY:", fitnote]))
