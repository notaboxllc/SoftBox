#!/usr/bin/env python3
"""
Vilfan-like target-zone twirling — analysis and figures.

Reads the CPU fixture's outputs from RUN_LOGS/vilfan_target_zone/ and writes tidy tables plus the
diagnostic figures into ANALYSIS/vilfan_target_zone/.

The independent statistical unit is the SEED (or the starting azimuth, where that is the arm variable).
Nothing is filtered: null starting phases and non-twirling seeds are plotted with the rest.

Usage:  python3 ANALYSIS/vilfan_target_zone/analyze_target_zone.py [RUN_LOGS/vilfan_target_zone]
"""
import csv, glob, math, os, sys
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

RUN = sys.argv[1] if len(sys.argv) > 1 else "RUN_LOGS/vilfan_target_zone"
OUT = "ANALYSIS/vilfan_target_zone"
os.makedirs(OUT, exist_ok=True)

# a single restrained palette, readable in both light and dark
C_ON, C_OFF, C_MIR, C_NEU = "#2f6fb2", "#8a8f98", "#c2603a", "#4c4f56"
plt.rcParams.update({"figure.dpi": 130, "font.size": 9, "axes.grid": True,
                     "grid.alpha": 0.25, "axes.spines.top": False, "axes.spines.right": False})


def load(name):
    p = os.path.join(RUN, name)
    if not os.path.exists(p):
        return []
    with open(p) as f:
        rows = list(csv.DictReader(f))
    for r in rows:
        for k, v in list(r.items()):
            if k in ("arm", "zone_on", "hist_bins_-pi_to_pi"):
                continue
            try:
                r[k] = float(v)
            except (TypeError, ValueError):
                r[k] = float("nan")
    return rows


def msem(x):
    x = np.asarray([v for v in x if v == v], dtype=float)
    if x.size == 0:
        return float("nan"), float("nan"), 0
    return x.mean(), (x.std(ddof=1) / math.sqrt(x.size) if x.size > 1 else 0.0), x.size


def sigma(m, s):
    return abs(m / s) if s and s == s and s > 0 else float("nan")


def arms(rows):
    seen, out = set(), []
    for r in rows:
        if r["arm"] not in seen:
            seen.add(r["arm"]); out.append(r["arm"])
    return out


def sel(rows, **kw):
    out = []
    for r in rows:
        if all((r.get(k) == v if isinstance(v, str) else r.get(k) == v) for k, v in kw.items()):
            out.append(r)
    return out


def bar_with_sem(ax, labels, means, sems, colors, ylabel, title):
    x = np.arange(len(labels))
    ax.bar(x, means, yerr=sems, capsize=3, color=colors, edgecolor="none")
    ax.axhline(0, color=C_NEU, lw=0.8)
    ax.set_xticks(x); ax.set_xticklabels(labels, rotation=20, ha="right")
    ax.set_ylabel(ylabel); ax.set_title(title, loc="left")


def save(fig, name):
    fig.tight_layout()
    fig.savefig(os.path.join(OUT, name), bbox_inches="tight")
    plt.close(fig)
    print("  wrote", os.path.join(OUT, name))


# ----------------------------------------------------------------------------- events
def load_events():
    out = {}
    for p in sorted(glob.glob(os.path.join(RUN, "events", "*.csv"))):
        key = os.path.basename(p)
        with open(p) as f:
            rows = list(csv.DictReader(f))
        if rows:
            out[key] = rows
    return out


def fig_zone_coordinate(events):
    """(1) the target-zone coordinate itself: signed offset of every attachment vs time."""
    on = [k for k in events if "zoneon" in k and "mir+1" in k]
    if not on:
        return
    rows = events[on[0]]
    t = np.array([float(r["step"]) for r in rows])
    d = np.array([float(r["delta_rad"]) for r in rows])
    b = np.array([int(r["before(1)/after(-1)"]) for r in rows])
    fig, ax = plt.subplots(figsize=(6.4, 3.2))
    ax.scatter(t[b > 0], np.degrees(d[b > 0]), s=6, color=C_ON, label="BEFORE the zone centre")
    ax.scatter(t[b < 0], np.degrees(d[b < 0]), s=6, color=C_MIR, label="AFTER the zone centre")
    ax.axhline(0, color=C_NEU, lw=1.0)
    ax.set_xlabel("step"); ax.set_ylabel("signed zone offset δ (deg)")
    ax.set_title("(1) target-zone coordinate at every attachment  ·  " + on[0], loc="left", fontsize=8)
    ax.legend(frameon=False, fontsize=8)
    save(fig, "fig01_zone_coordinate.png")


def fig_offset_hist(events):
    """(2) attachment-offset histograms, zone ON vs OFF."""
    keys_on = [k for k in events if "zoneon" in k and "mir+1" in k]
    keys_off = [k for k in events if "zoneoff" in k and "mir+1" in k]
    if not (keys_on and keys_off):
        return
    fig, ax = plt.subplots(figsize=(6.4, 3.2))
    bins = np.linspace(-180, 180, 49)
    for keys, lab, col in ((keys_off, "target zone OFF", C_OFF), (keys_on, "target zone ON", C_ON)):
        d = np.degrees([float(r["delta_rad"]) for k in keys for r in events[k]])
        if d.size:
            ax.hist(d, bins=bins, histtype="stepfilled", alpha=0.55, color=col,
                    label=f"{lab}  (n={d.size})", density=True)
    ax.axvline(0, color=C_NEU, lw=1.0)
    ax.set_xlabel("signed zone offset δ at attachment (deg)"); ax.set_ylabel("density")
    ax.set_title("(2) attachment-offset distribution, zone ON vs OFF", loc="left")
    ax.legend(frameon=False, fontsize=8)
    save(fig, "fig02_offset_hist_on_off.png")


def fig_native_mirror_hist(events):
    """(3) native vs mirror attachment asymmetry, as distributions."""
    kn = [k for k in events if "zoneon" in k and "mir+1" in k]
    km = [k for k in events if "zoneon" in k and "mir-1" in k]
    if not (kn and km):
        return
    fig, ax = plt.subplots(figsize=(6.4, 3.2))
    bins = np.linspace(-180, 180, 49)
    for keys, lab, col in ((kn, "native lattice", C_ON), (km, "MIRRORED lattice", C_MIR)):
        d = np.degrees([float(r["delta_rad"]) for k in keys for r in events[k]])
        if d.size:
            ax.hist(d, bins=bins, histtype="step", lw=1.6, color=col,
                    label=f"{lab}  (n={d.size}, mean {d.mean():+.2f}°)", density=True)
    ax.axvline(0, color=C_NEU, lw=1.0)
    ax.set_xlabel("signed zone offset δ at attachment (deg)"); ax.set_ylabel("density")
    ax.set_title("(3) native vs mirrored attachment asymmetry", loc="left")
    ax.legend(frameon=False, fontsize=8)
    save(fig, "fig03_native_vs_mirror_hist.png")


def fig_delta_torque(events):
    """(5b) the causal link itself: instantaneous axial torque at attachment vs the signed zone offset."""
    keys = [k for k in events if "zoneon" in k]
    if not keys:
        return
    d = np.degrees([float(r["delta_rad"]) for k in keys for r in events[k]])
    t = np.array([float(r["tau0_Nm"]) for k in keys for r in events[k]])
    ok = np.isfinite(d) & np.isfinite(t)
    if ok.sum() < 3:
        return
    d, t = d[ok], t[ok]
    fig, ax = plt.subplots(figsize=(5.2, 3.4))
    ax.scatter(d, t, s=10, color=C_ON, alpha=0.65)
    k = np.polyfit(d, t, 1)
    xs = np.linspace(d.min(), d.max(), 30)
    r = np.corrcoef(d, t)[0, 1]
    ax.plot(xs, np.polyval(k, xs), color=C_MIR, lw=1.4,
            label=f"slope {k[0]:+.3e} N·m/deg\nr = {r:+.3f},  n = {d.size}")
    ax.axhline(0, color=C_NEU, lw=0.8); ax.axvline(0, color=C_NEU, lw=0.8)
    ax.set_xlabel("signed zone offset δ at attachment (deg)")
    ax.set_ylabel("axial torque at attachment (N·m)")
    ax.set_title("(5b) the causal link: offset → axial torque", loc="left")
    ax.legend(frameon=False, fontsize=8)
    save(fig, "fig08_delta_to_torque.png")


def fig_zone_centre(events):
    """diagnostic: is the zone centre the substrate-facing direction, over real attachments?"""
    keys = [k for k in events if "zoneon" in k]
    vals = [float(r.get("cHat_dot_down", "nan")) for k in keys for r in events[k]]
    vals = np.array([v for v in vals if v == v])
    if vals.size == 0:
        return
    fig, ax = plt.subplots(figsize=(5.0, 2.8))
    ax.hist(vals, bins=40, color=C_ON, alpha=0.8)
    ax.axvline(1.0, color=C_MIR, lw=1.2, label="exactly substrate-facing")
    ax.set_xlabel("cHat · (−eup)"); ax.set_ylabel("attachments")
    ax.set_title(f"zone-centre direction vs substrate normal  (mean {vals.mean():.4f})", loc="left")
    ax.legend(frameon=False, fontsize=8)
    save(fig, "fig09_zone_centre_check.png")


# ----------------------------------------------------------------------------- per-seed figures
def fig_summary_bars(seeds):
    """(3b/9/10) A_TZ, torque, Omega, turns/µm for the four control arms."""
    want = [a for a in arms(seeds) if a.startswith("zone ")]
    if not want:
        return
    quantities = [("A_TZ", "A_TZ", "attachment asymmetry"),
                  ("tau_Nm", "⟨τ⟩ (N·m)", "signed axial torque"),
                  ("omega_rad_s", "Ω (rad/s)", "axial angular velocity"),
                  ("turns_per_um", "turns/µm", "rotation per unit distance")]
    fig, axes = plt.subplots(1, 4, figsize=(13.0, 3.4))
    cols = [C_ON if "zone ON" in a else C_OFF for a in want]
    cols = [C_MIR if ("MIRROR" in a and "zone ON" in a) else c for a, c in zip(want, cols)]
    short = [a.replace("zone ", "").replace(" helix", "") for a in want]
    for ax, (key, ylab, title) in zip(axes, quantities):
        m, s = [], []
        for a in want:
            mm, ss, _ = msem([r[key] for r in seeds if r["arm"] == a])
            m.append(mm); s.append(ss)
        bar_with_sem(ax, short, m, s, cols, ylab, title)
    fig.suptitle("(3b/9/10) mirror reversal and the target-zone-OFF null  ·  error bars = SEM over seeds",
                 x=0.01, ha="left", fontsize=9)
    save(fig, "fig04_control_summary.png")


def fig_speed(seeds_ladder):
    """(4) A_TZ vs speed and (7) turns/µm and pitch vs speed."""
    rows = [r for r in seeds_ladder if "zone ON" in r["arm"] and "REVERSED" not in r["arm"]]
    if not rows:
        return
    vs = sorted({r["v_um_s"] for r in rows})
    fig, axes = plt.subplots(1, 3, figsize=(10.5, 3.2))
    for ax, key, ylab, title in ((axes[0], "A_TZ", "A_TZ", "(4) asymmetry vs speed"),
                                 (axes[1], "turns_per_um", "turns/µm", "(7) rotation per µm vs speed"),
                                 (axes[2], "omega_rad_s", "Ω (rad/s)", "angular velocity vs speed")):
        m = [msem([r[key] for r in rows if r["v_um_s"] == v]) for v in vs]
        ax.errorbar(vs, [x[0] for x in m], yerr=[x[1] for x in m], marker="o", ms=4,
                    lw=1.3, color=C_ON, capsize=3)
        ax.axhline(0, color=C_NEU, lw=0.8)
        ax.set_xscale("log"); ax.set_xlabel("prescribed speed (µm/s)")
        ax.set_ylabel(ylab); ax.set_title(title, loc="left")
    save(fig, "fig05_speed_ladder.png")


def fig_covariation(seeds):
    """(5) torque vs A_TZ and (6) Omega vs A_TZ, one point per seed."""
    rows = [r for r in seeds if r["A_TZ"] == r["A_TZ"]]
    if not rows:
        return
    fig, axes = plt.subplots(1, 2, figsize=(8.6, 3.4))
    for ax, key, ylab, title in ((axes[0], "tau_Nm", "⟨τ⟩ (N·m)", "(5) signed torque vs A_TZ"),
                                 (axes[1], "omega_rad_s", "Ω (rad/s)", "(6) angular velocity vs A_TZ")):
        for lab, col, pick in (("zone ON native", C_ON, lambda r: r["zone_on"] == "true" and r["mirror"] > 0),
                               ("zone ON mirror", C_MIR, lambda r: r["zone_on"] == "true" and r["mirror"] < 0),
                               ("zone OFF", C_OFF, lambda r: r["zone_on"] != "true")):
            g = [r for r in rows if pick(r)]
            if g:
                ax.scatter([r["A_TZ"] for r in g], [r[key] for r in g], s=18, color=col, label=lab, alpha=0.85)
        x = np.array([r["A_TZ"] for r in rows]); y = np.array([r[key] for r in rows])
        ok = (x == x) & (y == y)
        if ok.sum() >= 3 and x[ok].std() > 0:
            k = np.polyfit(x[ok], y[ok], 1)
            xs = np.linspace(x[ok].min(), x[ok].max(), 20)
            rr = np.corrcoef(x[ok], y[ok])[0, 1]
            ax.plot(xs, np.polyval(k, xs), lw=1.0, color=C_NEU, ls="--", label=f"r = {rr:+.3f}")
        ax.axhline(0, color=C_NEU, lw=0.8); ax.axvline(0, color=C_NEU, lw=0.8)
        ax.set_xlabel("A_TZ"); ax.set_ylabel(ylab); ax.set_title(title, loc="left")
        ax.legend(frameon=False, fontsize=7)
    save(fig, "fig06_covariation.png")


def fig_azimuth(seeds):
    """(8) starting-azimuth robustness."""
    rows = [r for r in seeds if r["arm"].startswith("az0=")]
    if not rows:
        return
    az = sorted({r["az0_deg"] for r in rows})
    fig, axes = plt.subplots(1, 3, figsize=(10.5, 3.2))
    for ax, key, ylab in ((axes[0], "A_TZ", "A_TZ"), (axes[1], "tau_Nm", "⟨τ⟩ (N·m)"),
                          (axes[2], "omega_rad_s", "Ω (rad/s)")):
        m = [msem([r[key] for r in rows if r["az0_deg"] == a]) for a in az]
        ax.errorbar(az, [x[0] for x in m], yerr=[x[1] for x in m], marker="o", ms=4, lw=1.2,
                    color=C_ON, capsize=3)
        for a in az:
            g = [r[key] for r in rows if r["az0_deg"] == a]
            ax.scatter([a] * len(g), g, s=8, color=C_OFF, alpha=0.6, zorder=0)
        ax.axhline(0, color=C_NEU, lw=0.8)
        ax.set_xlabel("starting filament azimuth (deg)"); ax.set_ylabel(ylab)
    axes[0].set_title("(8) starting-azimuth robustness  ·  grey = individual seeds", loc="left")
    save(fig, "fig07_starting_azimuth.png")


# ----------------------------------------------------------------------------- tidy tables
def tidy(seeds, name):
    if not seeds:
        return
    keys = ["A_TZ", "mean_delta", "tau_Nm", "omega_rad_s", "turns_per_um", "avg_bound",
            "attach_rate_hz", "tau_per_attach", "impulse_per_attach"]
    p = os.path.join(OUT, name)
    with open(p, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["arm", "n_seeds", "attach_total"] +
                   sum([[k, k + "_sem", k + "_sigma"] for k in keys], []) +
                   ["sign_agree_tau", "sign_agree_omega", "r_ATZ_tau", "r_ATZ_omega"])
        for a in arms(seeds):
            g = [r for r in seeds if r["arm"] == a]
            row = [a, len(g), int(sum(r["attach"] for r in g))]
            for k in keys:
                m, s, _ = msem([r[k] for r in g])
                row += [f"{m:.6g}", f"{s:.6g}", f"{sigma(m, s):.3g}"]
            st = sum(1 if r["tau_Nm"] > 0 else -1 if r["tau_Nm"] < 0 else 0 for r in g)
            so = sum(1 if r["omega_rad_s"] > 0 else -1 if r["omega_rad_s"] < 0 else 0 for r in g)
            x = np.array([r["A_TZ"] for r in g]);
            def rr(key):
                y = np.array([r[key] for r in g]); ok = (x == x) & (y == y)
                return np.corrcoef(x[ok], y[ok])[0, 1] if ok.sum() >= 3 and x[ok].std() > 0 and y[ok].std() > 0 else float("nan")
            row += [st, so, f"{rr('tau_Nm'):.4f}", f"{rr('omega_rad_s'):.4f}"]
            w.writerow(row)
    print("  wrote", p)


# analytic axial (roll) drag of the fixture filament: gamma = 4*pi*eta*R^2*L
ETA_PA_S, R_ACTIN_M, L_FIL_M = 0.1, 3.5e-9, 1.76e-6
GAMMA_ROLL_ANALYTIC = 4 * math.pi * ETA_PA_S * R_ACTIN_M ** 2 * L_FIL_M


def consistency(seeds, name):
    """Is the rotation actually the gathered torque divided by the roll drag?

    Omega = tau / gamma_roll is the overdamped statement. Recovering gamma_roll from the measured
    (tau, Omega) pair and comparing it to the analytic 4*pi*eta*R^2*L is a strong internal check that the
    rotation is torque-driven and that the fixture leaves the roll coordinate genuinely free.
    """
    if not seeds:
        return
    p = os.path.join(OUT, name)
    with open(p, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["arm", "tau_Nm", "omega_rad_s", "gamma_from_ratio_Nms",
                    "gamma_analytic_Nms", "ratio_measured_over_analytic"])
        for a in arms(seeds):
            g = [r for r in seeds if r["arm"] == a]
            t, _, _ = msem([r["tau_Nm"] for r in g])
            o, _, _ = msem([r["omega_rad_s"] for r in g])
            gam = t / o if o and abs(o) > 1e-12 else float("nan")
            w.writerow([a, f"{t:.6e}", f"{o:.6e}", f"{gam:.6e}",
                        f"{GAMMA_ROLL_ANALYTIC:.6e}", f"{gam / GAMMA_ROLL_ANALYTIC:.4f}"])
    print("  wrote", p)


def main():
    print("reading", RUN)
    camp = load("campaign_seeds.csv")
    lad = load("ladder_seeds.csv")
    sens = load("sensitivity_seeds.csv")
    ev = load_events()
    print(f"  campaign rows={len(camp)}  ladder rows={len(lad)}  sensitivity rows={len(sens)}  "
          f"event files={len(ev)}")
    tidy(camp, "tidy_campaign.csv")
    consistency(camp, "consistency_campaign.csv")
    tidy(lad, "tidy_ladder.csv")
    tidy(sens, "tidy_sensitivity.csv")
    if ev:
        fig_zone_coordinate(ev); fig_offset_hist(ev); fig_native_mirror_hist(ev)
        fig_delta_torque(ev); fig_zone_centre(ev)
    if camp:
        fig_summary_bars(camp); fig_covariation(camp); fig_azimuth(camp)
    if lad:
        fig_speed(lad)
    print("done")


if __name__ == "__main__":
    main()
