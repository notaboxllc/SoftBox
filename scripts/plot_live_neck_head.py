#!/usr/bin/env python3
"""Figures for docs/motor/RESTORED_3D_HEAD_TILT_DOF.md — the live neck frame + dynamic 3-D head.

Reads ONLY the TSVs emitted by softbox.LiveNeckHeadProbe (which are themselves read out of the running
kernels). No generative graphics, no molecular art.
"""
import os, math
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from mpl_toolkits.mplot3d import Axes3D  # noqa: F401

RAW = "RUN_LOGS/motor_audit/restored_3d_head_tilt"
FIG = "docs/motor/figures/restored_3d_head_tilt"
os.makedirs(FIG, exist_ok=True)


def tsv(name):
    path = os.path.join(RAW, name)
    if not os.path.exists(path):
        return None
    import csv
    with open(path) as f:
        return list(csv.DictReader(f, delimiter="\t"))


def sphere_ax(ax, title):
    u, v = np.mgrid[0:2*np.pi:60j, 0:np.pi:30j]
    ax.plot_wireframe(np.cos(u)*np.sin(v), np.sin(u)*np.sin(v), np.cos(v),
                      color="0.85", linewidth=0.3, rstride=3, cstride=3)
    ax.set_box_aspect((1, 1, 1)); ax.set_xticks([]); ax.set_yticks([]); ax.set_zticks([])
    ax.set_title(title, fontsize=9)
    for s in ("x", "y", "z"):
        getattr(ax, f"set_{s}lim")(-1.05, 1.05)


# ---------------------------------------------------------------- Fig 1: the detached search cloud
def fig1():
    clouds = {}
    for k in ("0", "5", "10", "512"):
        d = tsv(f"ebind_cloud_k{k}.tsv")
        if d:
            clouds[k] = np.array([[float(r["ex"]), float(r["ey"]), float(r["ez"])] for r in d])
    if not clouds:
        return
    rest = tsv("rest_frame.tsv")
    fig = plt.figure(figsize=(4.0*len(clouds), 4.4))
    for i, (k, c) in enumerate(clouds.items()):
        ax = fig.add_subplot(1, len(clouds), i+1, projection="3d")
        lbl = "k_det = 0 (free tumbling, control)" if k == "0" else (
              "k_bind = 512 (today: almost locked)" if k == "512" else f"k_det = {k} pN·nm/rad²")
        sphere_ax(ax, lbl)
        s = max(1, len(c)//6000)
        ax.scatter(c[::s, 0], c[::s, 1], c[::s, 2], s=1.2, alpha=0.28,
                   color={"0": "0.45", "5": "#1f77b4", "10": "#d62728", "512": "#2ca02c"}[k], linewidths=0)
        if rest:
            r0 = rest[0]
            for nm, col, w in (("n1", "#111111", 1.8), ("n2", "#777777", 1.2), ("n3", "#777777", 1.2)):
                v = [float(r0[f"{nm}{a}"]) for a in "xyz"]
                ax.plot([0, v[0]], [0, v[1]], [0, v[2]], color=col, lw=w)
                ax.text(v[0]*1.15, v[1]*1.15, v[2]*1.15, nm, fontsize=7, color=col)
            v = [float(r0[f"rest{a}"]) for a in "xyz"]
            ax.plot([0, v[0]], [0, v[1]], [0, v[2]], color="#ff7f0e", lw=2.6)
            ax.text(v[0]*1.2, v[1]*1.2, v[2]*1.2, "eBind_rest", fontsize=7, color="#ff7f0e")
        ax.view_init(elev=18, azim=32)
    fig.suptitle("Detached head orientation search — eBind sampled from the real thermal trajectory of ONE fixed motor\n"
                 "black/grey = the LIVE neck frame (n1 lever, n2 S2-roll reference, n3), orange = the native rest direction",
                 fontsize=9)
    fig.tight_layout(rect=(0, 0, 1, 0.90))
    fig.savefig(os.path.join(FIG, "fig1_detached_search_cloud.png"), dpi=155)
    plt.close(fig)


# ---------------------------------------------------------------- Fig 2: theta_det statistics
def fig2():
    d = tsv("head3d_kdet.tsv")
    if not d:
        return
    k = np.array([float(r["k_det_pNnm"]) for r in d])
    fig, axes = plt.subplots(1, 3, figsize=(12.5, 3.7))
    for ax, cols, ttl, yl in (
            (axes[0], ("rms_theta_deg", "median_deg", "p90_deg", "p95_deg"),
             "misalignment from the native pose", "angle(eBind, eBind_rest)  [deg]"),
            (axes[1], ("sd_psi_deg", "sd_chi_deg"), "the two head coordinates", "SD  [deg]"),
            (axes[2], ("solid_frac",), "solid-angle coverage of one fixed motor", "fraction of 4π")):
        for c in cols:
            y = np.array([float(r[c]) for r in d])
            ax.plot(k, y, "o-", label=c.replace("_deg", "").replace("_", " "))
        ax.set_xscale("log"); ax.set_xlabel("k_det  [pN·nm/rad²]"); ax.set_ylabel(yl)
        ax.set_title(ttl, fontsize=9); ax.grid(alpha=0.3); ax.legend(fontsize=7)
        for kk in (5, 10):
            ax.axvline(kk, color="0.7", ls=":", lw=0.9)
    axes[0].axhspan(0, 25, color="#ffe9b0", alpha=0.5, zorder=0)
    axes[0].text(0.6, 26, "25° stereospecific capture tolerance", fontsize=7, color="#8a6d00")
    fig.suptitle("Dynamic 3-D head: the k_det ladder (χ dynamic, live-frame rest direction). "
                 "Shaded band on the left = the historical capture tolerance.", fontsize=9)
    fig.tight_layout(rect=(0, 0, 1, 0.90))
    fig.savefig(os.path.join(FIG, "fig2_kdet_ladder.png"), dpi=155)
    plt.close(fig)


# ---------------------------------------------------------------- Fig 3: dynamic site accessibility
def fig3():
    d = tsv("dynamic_site_access.tsv")
    if not d:
        return
    ks = sorted({float(r["k_det_pNnm"]) for r in d})
    fig, axes = plt.subplots(1, 2, figsize=(12.0, 4.0))
    for kk in ks:
        rows = sorted([r for r in d if float(r["k_det_pNnm"]) == kk], key=lambda r: float(r["azimuth_deg"]))
        az = np.array([float(r["azimuth_deg"]) for r in rows])
        fr = np.array([float(r["frac_time_within25"]) for r in rows])
        en = np.array([float(r["encounters_per_s"]) for r in rows])
        lbl = f"k_bind = {kk:.0f} (today)" if kk >= 100 else f"k_det = {kk:.0f}"
        axes[0].plot(az, fr, "o-", ms=3.5, label=lbl)
        axes[1].plot(az, en, "o-", ms=3.5, label=lbl)
    for ax, yl, ttl in ((axes[0], "fraction of time within 25° of n_site", "dwell"),
                        (axes[1], "cone entries per second", "encounter rate")):
        ax.set_xlabel("every4 site azimuth  [deg]"); ax.set_ylabel(yl); ax.grid(alpha=0.3)
        ax.set_title(ttl, fontsize=9); ax.legend(fontsize=7)
        ax.set_yscale("symlog", linthresh=1e-4 if ttl == "dwell" else 1.0)
        for a in (-90, 90):
            ax.axvline(a, color="0.8", ls=":", lw=0.9)
    fig.suptitle("DYNAMIC helical site-normal accessibility for ONE fixed motor — real thermal trajectory,\n"
                 "no base-azimuth randomisation, no projection of n_site. ±90° = the two poles the planar motor could already face.",
                 fontsize=9)
    fig.tight_layout(rect=(0, 0, 1, 0.88))
    fig.savefig(os.path.join(FIG, "fig3_dynamic_site_access.png"), dpi=155)
    plt.close(fig)


# ---------------------------------------------------------------- Fig 4: covariance fixture
def fig4():
    d = tsv("covariance_s2roll.tsv")
    if not d:
        return
    a = np.array([float(r["alpha_deg"]) for r in d])
    ul = np.array([float(r["U_live_J"]) for r in d])
    ub = np.array([float(r["dU_base_kT"]) for r in d])
    fig, ax = plt.subplots(figsize=(6.2, 4.0))
    ax.plot(a, ub, "o-", color="#d62728", label="base-frame law  ½k(ψ−ψ_actin)²")
    ax.plot(a, ul/1.380649e-23/298.0, "s-", color="#1f77b4", label="live neck frame  ½k_det·θ_det²")
    ax.set_xlabel("roll of the distal S2 element about the lever axis  [deg]")
    ax.set_ylabel("stored orientational energy  [kT]")
    ax.set_title("Covariance fixture B — the head keeps its native pose relative to the neck that carries it", fontsize=9)
    ax.grid(alpha=0.3); ax.legend(fontsize=8)
    fig.tight_layout()
    fig.savefig(os.path.join(FIG, "fig4_covariance_s2roll.png"), dpi=155)
    plt.close(fig)


# ---------------------------------------------------------------- Fig 5: compliance decomposition
def fig5():
    d = tsv("axial_compliance.tsv")
    if not d:
        return
    keys = ["s2_stretch_nm", "s2_bend_nm", "phi_nm", "psi_nm", "chi_nm"]
    lbl = ["S2 stretch", "S2 bend", "φ lever", "ψ converter", "χ head tilt"]
    fig, ax = plt.subplots(figsize=(7.6, 3.9))
    x = np.arange(len(d)); w = 0.16
    for i, (k, l) in enumerate(zip(keys, lbl)):
        v = np.array([float(r[k]) for r in d])
        ax.bar(x + (i - 2)*w, v, w, label=l)
    ax.set_xticks(x); ax.set_xticklabels([r["state"] for r in d], fontsize=8)
    ax.set_ylabel("contribution to the F8 displacement along b̂  [nm]")
    ax.axhline(0, color="0.4", lw=0.8)
    ax.set_title("Axial compliance decomposition under a 0.5 nm axial site displacement", fontsize=9)
    ax.grid(alpha=0.3, axis="y"); ax.legend(fontsize=7, ncol=3)
    fig.tight_layout()
    fig.savefig(os.path.join(FIG, "fig5_axial_compliance.png"), dpi=155)
    plt.close(fig)


for f in (fig1, fig2, fig3, fig4, fig5):
    try:
        f()
        print("ok", f.__name__)
    except Exception as ex:      # a missing arm must not kill the rest
        print("skip", f.__name__, ex)
print("figures ->", FIG)
