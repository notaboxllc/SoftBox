#!/usr/bin/env python3
"""Campaign summary plots: (1) timing vs N (both models, CPU+GPU), (2) velocity vs density (both models + fit)."""
import csv, os, math
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

D = "RUN_LOGS/explicit_completemat"
OUT = "docs/matsoa/plots"
os.makedirs(OUT, exist_ok=True)

def load(fn, key="N"):
    return list(csv.DictReader(open(os.path.join(D, fn))))

# ---- Plot 1: timing vs N (throughput CSVs) ----
exp = load("COMPLETEMAT_THROUGHPUT.csv"); cal = load("COMPLETEMAT_THROUGHPUT_CAL.csv")
fig, ax = plt.subplots(1, 2, figsize=(12, 4.5))
for rows, lab, mk in [(exp, "explicit", "o"), (cal, "calibrated", "s")]:
    N = [int(r["N"]) for r in rows]
    ax[0].plot(N, [float(r["gpu_ms_step_med"]) for r in rows], mk + "-", label=f"{lab} GPU")
    ax[0].plot(N, [float(r["cpu_ms_step_med"]) for r in rows], mk + "--", label=f"{lab} CPU")
ax[0].set_xlabel("N (motors)"); ax[0].set_ylabel("ms / step"); ax[0].set_yscale("log")
ax[0].set_title("Throughput vs N (no-cull free binding)"); ax[0].legend(fontsize=8); ax[0].grid(alpha=0.3)
# speedup panel
for rows, lab, mk in [(exp, "explicit", "o"), (cal, "calibrated", "s")]:
    N = [int(r["N"]) for r in rows]
    ax[1].plot(N, [float(r["speedup"]) for r in rows], mk + "-", label=lab)
ax[1].set_xlabel("N (motors)"); ax[1].set_ylabel("GPU speedup (CPU/GPU)")
ax[1].set_title("GPU speedup vs N"); ax[1].legend(fontsize=8); ax[1].grid(alpha=0.3)
fig.tight_layout(); fig.savefig(os.path.join(OUT, "timing_vs_N.png"), dpi=110); plt.close(fig)

# ---- Plot 2: velocity vs density (sweep CSV, full = main + explicit 3500) ----
sw = load("COMPLETEMAT_SWEEP_full.csv")
by = {}
for r in sw: by.setdefault(r["model"], []).append(r)
def mm(rho, v):
    v = [abs(x) for x in v]; vmax = max(v)*1.3; K = (max(rho)+min(rho))/2
    def sse(vm,k): return sum((vm*r/(k+r)-y)**2 for r,y in zip(rho,v))
    svm, sk = vmax*.5, K*.5
    for _ in range(4000):
        best=sse(vmax,K); imp=False
        for d in (svm,-svm):
            if vmax+d>0 and sse(vmax+d,K)<best: vmax+=d; best=sse(vmax,K); imp=True
        for d in (sk,-sk):
            if K+d>0 and sse(vmax,K+d)<best: K+=d; best=sse(vmax,K); imp=True
        if not imp: svm*=.7; sk*=.7
        if svm<1e-6 and sk<1e-6: break
    return vmax,K
fig, ax = plt.subplots(figsize=(7.5, 5))
for m, mk, col in [("explicit-s2-l40","o","C0"),("calibrated-s2-l40","s","C1")]:
    rows = sorted(by[m], key=lambda r: float(r["density"]))
    rho = [float(r["density"]) for r in rows]; v = [float(r["speed_umPerS"]) for r in rows]
    se = [float(r["vel_se"]) for r in rows]
    ax.errorbar(rho, v, yerr=se, fmt=mk, color=col, label=f"{m} (data)", capsize=3)
    vmax, K = mm(rho, v); xs = [i for i in range(50, int(max(rho))+200, 20)]
    ax.plot(xs, [vmax*x/(K+x) for x in xs], "-", color=col, alpha=0.6, label=f"{m} MM fit (v_max={vmax:.2f}, K={K:.0f})")
ax.set_xlabel("motor density (/µm²)"); ax.set_ylabel("|gliding speed| (µm/s)")
ax.set_title("Density-saturation: explicit vs calibrated (GPU, matched geometry)")
ax.legend(fontsize=8); ax.grid(alpha=0.3)
fig.tight_layout(); fig.savefig(os.path.join(OUT, "velocity_vs_density.png"), dpi=110); plt.close(fig)
print("wrote", os.path.join(OUT, "timing_vs_N.png"), "and velocity_vs_density.png")
