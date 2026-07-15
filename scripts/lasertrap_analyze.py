#!/usr/bin/env python3
"""Experiment 0 — laser-trap filament calibration: compact 2x2 summary figure.

Reads the CSV artifacts written by LaserTrapHarness -out, and produces one figure:
  (a) deterministic response   — A5 x_eq vs applied force (points + k_eff line)
  (b) thermal equipartition    — Phase-B axial-position histogram vs kT/k_eff Gaussian
  (c) relaxation               — A4 decay (semilog) + fitted/predicted tau
  (d) timestep comparison      — deterministic tau(dt) [noise-free] + thermal var/varPred(dt)

Usage: python3 scripts/lasertrap_analyze.py RUN_LOGS/lasertrap/csv RUN_LOGS/lasertrap/lasertrap_summary.png
"""
import csv, sys, math
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

CSV = sys.argv[1] if len(sys.argv) > 1 else "RUN_LOGS/lasertrap/csv"
OUT = sys.argv[2] if len(sys.argv) > 2 else "RUN_LOGS/lasertrap/lasertrap_summary.png"
KT = 1.380662e-23 * 298.15   # J

def rows(name):
    with open(f"{CSV}/{name}") as f:
        return list(csv.DictReader(f))

# ---- (a) A5 constant-force response ----
a5 = [(r["quantity"], float(r["value"]), float(r["predicted"])) for r in rows("phaseA_summary.csv") if r["test"] == "A5" and r["quantity"].startswith("dx_um@F")]
fpN, dxMeas, dxPred = [], [], []
for q, v, p in a5:
    f = float(q.split("@F")[1].replace("pN", ""))
    fpN.append(f); dxMeas.append(v * 1e3); dxPred.append(p * 1e3)   # nm
order = np.argsort(fpN)
fpN, dxMeas, dxPred = np.array(fpN)[order], np.array(dxMeas)[order], np.array(dxPred)[order]

# ---- (b) equipartition histogram (finest-dt Phase-B time series) ----
ts = rows("phaseB_ts_dt3e-06_seed1000.csv")
x_nm = np.array([float(r["x_um"]) for r in ts]) * 1e3
# k_eff from A5 slope (µm per N) -> var pred = kT/k_eff
kEff_Npum = 1e-12 / (dxMeas[np.argmin(np.abs(fpN - 1.0))] * 1e-3 / 1.0) if 1.0 in fpN else 1e-10
# use predicted var directly from Phase B summary
bsum = rows("phaseB_summary.csv")
varPred_um2 = float(bsum[0]["varPred_um2"])
sd_nm = math.sqrt(varPred_um2) * 1e3

# ---- (c) A4 relaxation ----
a4 = rows("phaseA4_relaxation.csv")
t4 = np.array([float(r["t_s"]) for r in a4]) * 1e3   # ms
d4 = np.abs(np.array([float(r["disp_um"]) for r in a4]) * 1e3)   # nm
dtinv = rows("phaseA_dtInvariance.csv")
tauPred_ms = float(dtinv[0]["tauPred_s"]) * 1e3

# ---- (d) timestep ----
dt_s = np.array([float(r["dt_s"]) for r in dtinv])
tau_ms = np.array([float(r["tauMeas_s"]) for r in dtinv]) * 1e3
# thermal ratio per dt (mean +/- sem over seeds)
byd = {}
for r in bsum:
    byd.setdefault(float(r["dt"]), []).append(float(r["ratio"]))
dt_th = np.array(sorted(byd))
ratio_mean = np.array([np.mean(byd[d]) for d in dt_th])
ratio_sem = np.array([np.std(byd[d], ddof=1) / math.sqrt(len(byd[d])) for d in dt_th])

# ================= plot =================
fig, ax = plt.subplots(2, 2, figsize=(11, 8))
fig.suptitle("Experiment 0 — virtual optical-trap FILAMENT calibration (CPU; single rigid rod, k=0.05 pN/nm, L≈1 µm)", fontsize=11)

# (a)
a = ax[0, 0]
a.plot(fpN, dxPred, "-", color="0.6", lw=2, label="predicted  x=F/k_eff")
a.plot(fpN, dxMeas, "o", color="C0", label="measured")
a.axhline(0, color="0.8", lw=0.7); a.axvline(0, color="0.8", lw=0.7)
a.set_xlabel("applied axial force (pN)"); a.set_ylabel("equilibrium displacement (nm)")
a.set_title("(a) deterministic constant-force response  (k_eff=k_L+k_R)")
a.legend(fontsize=8); a.grid(alpha=0.3)

# (b)
b = ax[0, 1]
b.hist(x_nm, bins=60, density=True, color="C1", alpha=0.6, label="Phase-B x samples (dt=2.5e-6)")
xx = np.linspace(x_nm.min(), x_nm.max(), 300)
g = np.exp(-0.5 * ((xx - x_nm.mean()) / sd_nm) ** 2) / (sd_nm * math.sqrt(2 * math.pi))
b.plot(xx, g, "k-", lw=2, label=f"equipartition N(0, kT/k_eff)\nσ_pred={sd_nm:.2f} nm")
b.set_xlabel("axial position (nm)"); b.set_ylabel("pdf")
b.set_title("(b) thermal equipartition  ⟨δx²⟩ = kT/k_eff")
b.legend(fontsize=8); b.grid(alpha=0.3)

# (c)
c = ax[1, 0]
c.semilogy(t4, d4, "-", color="C2", label="A4 relaxation |x−x_eq|")
c.semilogy(t4, d4[0] * np.exp(-t4 / tauPred_ms), "k--", lw=1.5, label=f"exp(−t/τ_pred), τ_pred={tauPred_ms:.3f} ms")
c.set_xlabel("time (ms)"); c.set_ylabel("displacement (nm, log)")
c.set_title("(c) relaxation  τ = γ_∥/(1e6·k_eff)")
c.legend(fontsize=8); c.grid(alpha=0.3, which="both")
c.set_ylim(d4[d4 > 0].min() * 0.5, d4[0] * 1.5)

# (d)
d = ax[1, 1]
d.plot(dt_s * 1e6, tau_ms, "s-", color="C3", label="deterministic τ (noise-free)")
d.axhline(tauPred_ms, color="0.6", ls=":", label=f"τ_pred={tauPred_ms:.3f} ms")
d.set_xlabel("timestep dt (µs)"); d.set_ylabel("relaxation τ (ms)", color="C3")
d.tick_params(axis="y", labelcolor="C3")
d.set_title("(d) timestep behaviour")
d.invert_xaxis()
d2 = d.twinx()
d2.errorbar(dt_th * 1e6, ratio_mean, yerr=ratio_sem, fmt="o-", color="C4", capsize=3, label="thermal var/var_pred")
d2.axhline(1.0, color="0.8", ls="--")
d2.set_ylabel("var / var_pred", color="C4"); d2.tick_params(axis="y", labelcolor="C4")
d2.set_ylim(0.85, 1.15)
lines1, lab1 = d.get_legend_handles_labels()
lines2, lab2 = d2.get_legend_handles_labels()
d.legend(lines1 + lines2, lab1 + lab2, fontsize=7, loc="lower left")
d.grid(alpha=0.3)

fig.tight_layout(rect=[0, 0, 1, 0.97])
fig.savefig(OUT, dpi=130)
print("wrote", OUT)
