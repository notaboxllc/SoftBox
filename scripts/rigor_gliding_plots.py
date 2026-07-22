#!/usr/bin/env python3
"""
Part C required plots for the rigor-rupture gliding impact.

    python3 scripts/rigor_gliding_plots.py <singlehead_dir> [<dimer_dir>]

Reads rigor_gliding_paired.csv (single-head) and rigorrows.csv (dimer), writes PNGs into
<singlehead_dir>/plots/: velocity ON vs OFF, %velocity change vs density, bound-head change vs
density, rupture fraction vs density, force-at-rupture histogram, single-head vs dimer sensitivity.
"""
import sys, os, csv, glob, json
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

SH = sys.argv[1] if len(sys.argv) > 1 else "RUN_LOGS/motor_validation/rigor_gliding_singlehead"
DM = sys.argv[2] if len(sys.argv) > 2 else "RUN_LOGS/motor_validation/rigor_gliding_dimer"
PLT = os.path.join(SH, "plots"); os.makedirs(PLT, exist_ok=True)


def load_sh(d):
    p = os.path.join(d, "rigor_gliding_paired.csv")
    if not os.path.exists(p):
        return []
    return [dict((k, (float(v) if v not in ("", None) else float("nan"))) if k not in () else (k, v)
                 for k, v in r.items()) for r in csv.DictReader(open(p))]


def load_dimer(d):
    p = os.path.join(d, "rigorrows.csv"); rows = {}
    if not os.path.exists(p):
        return {}
    for line in open(p):
        if not line.startswith("RIGORROW"):
            continue
        f = line.strip().split(",")
        rows.setdefault((float(f[1]), int(f[2])), {})["on" if f[6] == "true" else "off"] = dict(
            vel=float(f[7]), bound=float(f[8]), rup=int(f[9]), frac=float(f[10]), fmax=float(f[13]))
    return rows


sh = load_sh(SH)
dm = load_dimer(DM)

if sh:
    dens = sorted({p["density"] for p in sh})
    # (1) velocity ON vs OFF
    plt.figure(figsize=(5, 5))
    voff = [p["vel_off"] for p in sh]; von = [p["vel_on"] for p in sh]
    plt.scatter(voff, von, c=[p["density"] for p in sh], cmap="viridis", s=60)
    lim = [min(voff + von) - 0.2, max(voff + von) + 0.2]
    plt.plot(lim, lim, "k--", lw=1, label="ON = OFF")
    plt.colorbar(label="density (heads/µm²)"); plt.xlabel("velocity OFF (µm/s)"); plt.ylabel("velocity ON (µm/s)")
    plt.title("Single-head gliding velocity: rigor rupture ON vs OFF"); plt.legend(); plt.tight_layout()
    plt.savefig(os.path.join(PLT, "velocity_on_vs_off.png"), dpi=110); plt.close()

    # (2) % velocity change vs density (per seed + ensemble mean)
    plt.figure(figsize=(6, 4))
    for p in sh:
        dvp = 100 * (p["vel_on"] - p["vel_off"]) / abs(p["vel_off"]) if p["vel_off"] else 0
        plt.scatter(p["density"], dvp, c="steelblue", alpha=0.6, s=45)
    ens = []
    for d in dens:
        dd = [100 * (p["vel_on"] - p["vel_off"]) / abs(p["vel_off"]) for p in sh if p["density"] == d and p["vel_off"]]
        ens.append(np.mean(dd)); plt.scatter(d, np.mean(dd), c="crimson", marker="D", s=80, zorder=3)
    plt.plot(dens, ens, "crimson", lw=1.5, label="ensemble mean")
    plt.axhspan(-2, 2, color="green", alpha=0.12, label="±2% (Negligible gate)")
    plt.axhline(0, color="k", lw=0.7); plt.xlabel("density (heads/µm²)"); plt.ylabel("% velocity change (ON−OFF)")
    plt.title("Velocity change vs density (per-seed scatter = chaotic basins)"); plt.legend(); plt.tight_layout()
    plt.savefig(os.path.join(PLT, "pct_velocity_change_vs_density.png"), dpi=110); plt.close()

    # (3) bound-head change vs density
    plt.figure(figsize=(6, 4))
    for p in sh:
        bpc = 100 * (p["bound_on"] - p["bound_off"]) / abs(p["bound_off"]) if p["bound_off"] else 0
        plt.scatter(p["density"], bpc, c="darkorange", alpha=0.7, s=45)
    plt.axhline(0, color="k", lw=0.7); plt.xlabel("density (heads/µm²)"); plt.ylabel("% mean-bound-heads change")
    plt.title("Bound-head change vs density"); plt.tight_layout()
    plt.savefig(os.path.join(PLT, "bound_change_vs_density.png"), dpi=110); plt.close()

    # (4) rupture fraction vs density
    plt.figure(figsize=(6, 4))
    for p in sh:
        plt.scatter(p["density"], 100 * p["rupture_frac"], c="purple", alpha=0.7, s=45)
    plt.axhline(2, color="green", ls="--", lw=1, label="2% (rare-rupture gate)")
    plt.xlabel("density (heads/µm²)"); plt.ylabel("rigor ruptures as % of all detachments")
    plt.title("Rigor-rupture fraction vs density (rare at saturating ATP)"); plt.legend(); plt.tight_layout()
    plt.savefig(os.path.join(PLT, "rupture_fraction_vs_density.png"), dpi=110); plt.close()

    # (5) force-at-rupture (mean/max per cell — the per-event histogram is sparse; show cell means)
    plt.figure(figsize=(6, 4))
    fm = [p["force_at_rupture_mean"] for p in sh if p.get("ruptures", 0)]
    if fm:
        plt.hist(fm, bins=15, color="teal", alpha=0.8)
    plt.xlabel("mean realized bond load at rupture (pN)"); plt.ylabel("cells")
    plt.title("Force-at-rupture (per-cell mean); rupture favors LOW load in gliding"); plt.tight_layout()
    plt.savefig(os.path.join(PLT, "force_at_rupture_hist.png"), dpi=110); plt.close()

# (6) single-head vs dimer sensitivity
plt.figure(figsize=(6, 4))
if sh:
    ds = sorted({p["density"] for p in sh})
    ev = [np.mean([100 * (p["vel_on"] - p["vel_off"]) / abs(p["vel_off"]) for p in sh if p["density"] == d and p["vel_off"]]) for d in ds]
    plt.plot(ds, ev, "o-", label="single-head (ensemble %Δv)", color="steelblue")
if dm:
    dd = {}
    for (dens_, seed), cc in dm.items():
        if "off" in cc and "on" in cc and cc["off"]["vel"]:
            dd.setdefault(dens_, []).append(100 * (cc["on"]["vel"] - cc["off"]["vel"]) / abs(cc["off"]["vel"]))
    if dd:
        xs = sorted(dd); plt.plot(xs, [np.mean(dd[x]) for x in xs], "s-", label="dimer (ensemble %Δv)", color="crimson")
plt.axhspan(-2, 2, color="green", alpha=0.12); plt.axhline(0, color="k", lw=0.7)
plt.xlabel("density (objects/µm²)"); plt.ylabel("ensemble % velocity change (ON−OFF)")
plt.title("Architecture sensitivity: single-head vs HMM dimer"); plt.legend(); plt.tight_layout()
plt.savefig(os.path.join(PLT, "singlehead_vs_dimer_sensitivity.png"), dpi=110); plt.close()

print(f"wrote plots to {PLT}:")
for f in sorted(glob.glob(os.path.join(PLT, "*.png"))):
    print("  " + os.path.basename(f))
