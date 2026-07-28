#!/usr/bin/env python3
"""
Long-run steady-twirl discovery screen — nested-window analysis.

Reads the per-arm traces written by VilfanGradedBindingHarness -longrun and answers one question:
does the cumulative BODY-FIXED axial roll settle into a sustained, mirror-reversing linear drift once the
initial relaxation has decayed?

Deliberate methodological points:
  * slopes are taken over NESTED DISTANCE WINDOWS of the same trajectory, never over independent runs;
  * a single line is never fitted across the whole trajectory and called a twirl — the point is whether the
    slope CHANGES with distance;
  * the mirror decomposition is done on MATCHED seeds (even/odd), because the odd part is the only
    chirality-carrying component;
  * torque-rotation closure uses the WHOLE-FILAMENT roll drag.

Usage: python3 ANALYSIS/vilfan_graded_binding/analyze_longrun.py [RUN_DIR] [OUT_DIR]
"""
import csv, glob, math, os, re, sys
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

RUN = sys.argv[1] if len(sys.argv) > 1 else "RUN_LOGS/vilfan_graded_binding/longrun"
OUT = sys.argv[2] if len(sys.argv) > 2 else "ANALYSIS/vilfan_graded_binding"
os.makedirs(OUT, exist_ok=True)

C_N, C_M, C_G, C_0 = "#2f6fb2", "#c2603a", "#8a8f98", "#4c4f56"
plt.rcParams.update({"figure.dpi": 130, "font.size": 9, "axes.grid": True, "grid.alpha": 0.25,
                     "axes.spines.top": False, "axes.spines.right": False})

TWOPI = 2.0 * math.pi
WINDOWS = [(0.0, 0.5), (0.5, 1.0), (1.0, 2.0), (2.0, 3.0), (3.0, 4.0), (4.0, 5.0)]
NESTED = [0.25, 0.50, 1.00, 2.00, 3.00, 5.00]
FNAME = re.compile(r"trace_az(\d+)_mir([+-]\d)_seed(\d+)_([\d.]+)um(_recP[\d.]+)?\.csv")


def load_traces():
    arms = {}
    for p in sorted(glob.glob(os.path.join(RUN, "trace_*.csv"))):
        m = FNAME.match(os.path.basename(p))
        if not m:
            continue
        az, mir, seed, travel = int(m.group(1)), int(m.group(2)), int(m.group(3)), float(m.group(4))
        rec = m.group(5) is not None
        with open(p) as f:
            rows = list(csv.DictReader(f))
        if len(rows) < 10:
            continue
        d = dict(az=az, mir=mir, seed=seed, travel=travel, rec=rec,
                 t=np.array([float(r["time_s"]) for r in rows]),
                 x=np.array([float(r["travel_um"]) for r in rows]),
                 roll=np.array([float(r["roll_rad"]) for r in rows]),
                 tau=np.array([float(r["tau_Nm"]) for r in rows]),
                 bound=np.array([float(r["bound"]) for r in rows]),
                 att=np.array([float(r["attach_cum"]) for r in rows]))
        # keep the LONGEST trajectory for each (az, mir, seed) — the extension supersedes the shorter run
        key = (az, mir, seed)
        if key not in arms or travel > arms[key]["travel"]:
            arms[key] = d
    return arms


def gamma_roll():
    """Whole-filament axial roll drag, read from the arm summary the harness wrote."""
    for p in glob.glob(os.path.join(RUN, "arm_summary*.csv")):
        with open(p) as f:
            for r in csv.DictReader(f):
                return float(r["gamma_roll_Nms"])
    return float("nan")


def slope(x, y):
    """Least-squares slope with R^2. Returns (slope, r2, n)."""
    ok = np.isfinite(x) & np.isfinite(y)
    x, y = x[ok], y[ok]
    if x.size < 3 or x.std() == 0:
        return float("nan"), float("nan"), x.size
    k, c = np.polyfit(x, y, 1)
    yh = k * x + c
    ss = ((y - yh) ** 2).sum()
    st = ((y - y.mean()) ** 2).sum()
    return k, (1 - ss / st if st > 0 else float("nan")), x.size


def window_stats(a, lo, hi, gam):
    """Roll slope (rad/µm and rad/s), R², mean torque, and the torque-predicted Omega, over [lo, hi) µm."""
    sel = (a["x"] >= lo) & (a["x"] < hi)
    if sel.sum() < 5:
        return None
    kx, r2x, n = slope(a["x"][sel], a["roll"][sel])          # rad per µm
    kt, r2t, _ = slope(a["t"][sel], a["roll"][sel])          # rad per s  = Omega
    tau = a["tau"][sel].mean()
    return dict(lo=lo, hi=hi, n=n, slope_x=kx, r2=r2x, omega=kt, r2t=r2t, tau=tau,
                omega_pred=tau / gam if gam == gam and gam != 0 else float("nan"),
                turns_per_um=kx / TWOPI, bound=a["bound"][sel].mean(),
                d_roll=a["roll"][sel][-1] - a["roll"][sel][0])


def main():
    arms = load_traces()
    gam = gamma_roll()
    print(f"reading {RUN}\n  arms = {len(arms)}   gammaRoll = {gam:.6e} N·m·s")
    if not arms:
        print("  no traces yet"); return

    # ------------------------------------------------------------------ per-arm nested windows
    rows = [["az0_deg", "lattice", "mirror", "seed", "travel_um", "win_lo_um", "win_hi_um", "n_pts",
             "slope_rad_per_um", "R2", "omega_rad_s", "R2_t", "turns_per_um", "mean_tau_Nm",
             "omega_pred_tau_over_gamma", "closure_meas_over_pred", "mean_bound", "delta_roll_rad"]]
    for (az, mir, seed), a in sorted(arms.items()):
        for lo, hi in WINDOWS:
            if hi > a["travel"] + 1e-9:
                continue
            w = window_stats(a, lo, hi, gam)
            if w is None:
                continue
            clo = w["omega"] / w["omega_pred"] if w["omega_pred"] not in (0.0,) and w["omega_pred"] == w["omega_pred"] else float("nan")
            rows.append([az, "native" if mir > 0 else "mirror", mir, seed, a["travel"], lo, hi, w["n"],
                         f"{w['slope_x']:.6g}", f"{w['r2']:.4f}", f"{w['omega']:.5g}", f"{w['r2t']:.4f}",
                         f"{w['turns_per_um']:.6g}", f"{w['tau']:.6e}", f"{w['omega_pred']:.5g}",
                         f"{clo:.4f}", f"{w['bound']:.3f}", f"{w['d_roll']:.6g}"])
    with open(os.path.join(OUT, "longrun_windows.csv"), "w", newline="") as f:
        csv.writer(f).writerows(rows)
    print("  wrote", os.path.join(OUT, "longrun_windows.csv"))

    # ------------------------------------------------------------------ nested cumulative readout
    nest = [["az0_deg", "lattice", "seed", "upto_um", "roll_rad", "turns", "slope_0_to_x_rad_per_um", "R2"]]
    for (az, mir, seed), a in sorted(arms.items()):
        for u in NESTED:
            if u > a["travel"] + 1e-9:
                continue
            sel = a["x"] <= u
            k, r2, _ = slope(a["x"][sel], a["roll"][sel])
            nest.append([az, "native" if mir > 0 else "mirror", seed, u,
                         f"{a['roll'][sel][-1]:.6g}", f"{a['roll'][sel][-1]/TWOPI:.6g}",
                         f"{k:.6g}", f"{r2:.4f}"])
    with open(os.path.join(OUT, "longrun_nested.csv"), "w", newline="") as f:
        csv.writer(f).writerows(nest)
    print("  wrote", os.path.join(OUT, "longrun_nested.csv"))

    # ------------------------------------------------------------------ mirror even/odd on matched seeds
    dec = [["az0_deg", "seed", "win_lo_um", "win_hi_um", "omega_native", "omega_mirror",
            "omega_even", "omega_odd", "tau_native", "tau_mirror", "tau_even", "tau_odd"]]
    azs = sorted({k[0] for k in arms})
    for az in azs:
        seeds = sorted({k[2] for k in arms if k[0] == az})
        for sd in seeds:
            an, am = arms.get((az, 1, sd)), arms.get((az, -1, sd))
            if an is None or am is None:
                continue
            for lo, hi in WINDOWS:
                if hi > min(an["travel"], am["travel"]) + 1e-9:
                    continue
                wn, wm = window_stats(an, lo, hi, gam), window_stats(am, lo, hi, gam)
                if wn is None or wm is None:
                    continue
                dec.append([az, sd, lo, hi, f"{wn['omega']:.5g}", f"{wm['omega']:.5g}",
                            f"{0.5*(wn['omega']+wm['omega']):.5g}", f"{0.5*(wn['omega']-wm['omega']):.5g}",
                            f"{wn['tau']:.5e}", f"{wm['tau']:.5e}",
                            f"{0.5*(wn['tau']+wm['tau']):.5e}", f"{0.5*(wn['tau']-wm['tau']):.5e}"])
    with open(os.path.join(OUT, "longrun_mirror_decomposition.csv"), "w", newline="") as f:
        csv.writer(f).writerows(dec)
    print("  wrote", os.path.join(OUT, "longrun_mirror_decomposition.csv"))

    # ------------------------------------------------------------------ figures
    for az in azs:
        fig, ax = plt.subplots(1, 2, figsize=(10.4, 3.6))
        for (a_az, mir, seed), a in sorted(arms.items()):
            if a_az != az:
                continue
            col = C_N if mir > 0 else C_M
            ls = "-" if seed % 2 == 1 else "--"
            lab = f"{'native' if mir>0 else 'mirror'} seed {seed}"
            ax[0].plot(a["x"], a["roll"], color=col, ls=ls, lw=1.3, label=lab)
            ax[1].plot(a["x"], a["roll"] / TWOPI, color=col, ls=ls, lw=1.3, label=lab)
        for m in (0.25, 0.5, 1, 2, 3, 4, 5):
            for k in (0, 1):
                ax[k].axvline(m, color=C_G, lw=0.7, ls=":")
        for k, yl in ((0, "cumulative body-fixed roll (rad)"), (1, "cumulative roll (turns)")):
            ax[k].axhline(0, color=C_0, lw=0.8)
            ax[k].set_xlabel("prescribed travel (µm)"); ax[k].set_ylabel(yl)
        ax[0].set_title(f"cumulative transported body-fixed roll vs travel — az0 = {az}°", loc="left")
        ax[0].legend(frameon=False, fontsize=8)
        fig.tight_layout(); fig.savefig(os.path.join(OUT, f"fig20_roll_vs_travel_az{az}.png"), bbox_inches="tight")
        plt.close(fig)
        print("  wrote", os.path.join(OUT, f"fig20_roll_vs_travel_az{az}.png"))

        # window-slope evolution
        fig, ax = plt.subplots(figsize=(6.2, 3.4))
        for (a_az, mir, seed), a in sorted(arms.items()):
            if a_az != az:
                continue
            xs, ys = [], []
            for lo, hi in WINDOWS:
                if hi > a["travel"] + 1e-9:
                    continue
                w = window_stats(a, lo, hi, gam)
                if w:
                    xs.append(0.5 * (lo + hi)); ys.append(w["omega"])
            ax.plot(xs, ys, marker="o", ms=4, color=C_N if mir > 0 else C_M,
                    ls="-" if seed % 2 == 1 else "--",
                    label=f"{'native' if mir>0 else 'mirror'} seed {seed}")
        ax.axhline(0, color=C_0, lw=0.9)
        ax.set_xlabel("window centre (µm)"); ax.set_ylabel("$\\Omega$ over window (rad/s)")
        ax.set_title(f"window-resolved angular slope — startup vs late drift (az0 = {az}°)", loc="left")
        ax.legend(frameon=False, fontsize=8)
        fig.tight_layout(); fig.savefig(os.path.join(OUT, f"fig21_window_slopes_az{az}.png"), bbox_inches="tight")
        plt.close(fig)
        print("  wrote", os.path.join(OUT, f"fig21_window_slopes_az{az}.png"))

    # ------------------------------------------------------------------ console verdict aid
    print("\n  window-resolved Omega (rad/s):")
    hdr = f"  {'arm':<22}" + "".join(f"{f'{lo}-{hi}':>12}" for lo, hi in WINDOWS)
    print(hdr)
    for (az, mir, seed), a in sorted(arms.items()):
        cells = ""
        for lo, hi in WINDOWS:
            w = window_stats(a, lo, hi, gam) if hi <= a["travel"] + 1e-9 else None
            cells += f"{w['omega']:>12.2f}" if w else f"{'-':>12}"
        print(f"  az{az} {'native' if mir>0 else 'mirror'} s{seed:<8}" + cells)
    print("\n  matched-seed mirror-ODD Omega (rad/s)  [chirality-carrying component]:")
    print(hdr)
    for az in azs:
        for sd in sorted({k[2] for k in arms if k[0] == az}):
            an, am = arms.get((az, 1, sd)), arms.get((az, -1, sd))
            if an is None or am is None:
                continue
            cells = ""
            for lo, hi in WINDOWS:
                if hi > min(an["travel"], am["travel"]) + 1e-9:
                    cells += f"{'-':>12}"; continue
                wn, wm = window_stats(an, lo, hi, gam), window_stats(am, lo, hi, gam)
                cells += f"{0.5*(wn['omega']-wm['omega']):>12.2f}" if (wn and wm) else f"{'-':>12}"
            print(f"  az{az} odd seed {sd:<9}" + cells)
    print("done")


if __name__ == "__main__":
    main()
