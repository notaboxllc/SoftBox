#!/usr/bin/env python3
"""Static contact sheet + fine-time traces for the single-motor binding movie.

Reads ONLY the JSON/TSV the harness emits (which are read straight out of the running kernels).
"""
import json, os, math
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from mpl_toolkits.mplot3d import Axes3D  # noqa: F401

import sys
# Dataset selector: default = the ORIGINAL pre-repair movie; "repaired" = the movie re-run on the motor
# with the F8 virtual-work axis and the S2->lever joint repaired (report sections 13-16).
_DS = sys.argv[1] if len(sys.argv) > 1 else "prerepair"
if _DS == "repaired":
    RAW = "RUN_LOGS/motor_audit/mechanics_repair/single_motor_movie"
    FIG = "docs/motor/figures/mechanics_repair/single_motor_movie"
else:
    RAW = "RUN_LOGS/motor_audit/restored_3d_head_tilt/single_motor_movie"
    FIG = "docs/motor/figures/restored_3d_head_tilt/single_motor_movie"
os.makedirs(FIG, exist_ok=True)
S = 1000.0   # µm -> nm

ELEV, AZIM = 22, -62      # ONE camera for every panel


def load(arm):
    scn = json.load(open(f"{RAW}/scene_{arm}.json"))
    frm = json.load(open(f"{RAW}/frames_{arm}.json"))["frames"]
    return scn, frm


def panel(ax, scn, frm, i, title):
    f = frm[i]
    nd = np.array(f["nd"]).reshape(-1, 3) * S
    C, H, F = (np.array(f[k]) * S for k in ("C", "H", "F"))
    # actin surface (centreline + a radius band) and the sparse sites
    fx = [s["p"][0] * S for s in scn["sites"]]
    seg = np.array([[s["c"][0], s["c"][1], s["c"][2]] for s in scn["filament"]]) * S
    # actin drawn as its SURFACE band (R = 3.5 nm), not a bare centreline
    xr = np.linspace(seg[:, 0].min() - 90, seg[:, 0].max() + 90, 2)
    th = np.linspace(0, 2*np.pi, 40)
    XX, TT = np.meshgrid(xr, th)
    Ra = scn["Ractin"] * S
    ax.plot_surface(XX, Ra*np.cos(TT), Ra*np.sin(TT), color="#3d4d5e", alpha=.30,
                    linewidth=0, shade=False, zorder=1)
    ax.plot(seg[:, 0], seg[:, 1], seg[:, 2], color="#7f8fa0", lw=1.0, alpha=.6, zorder=2)
    for s in scn["sites"]:
        p = np.array(s["p"]) * S
        n = np.array(s["n"])
        c = "#33ff88" if s["k"] == scn["boundSite"] else "#7d8b9c"
        big = s["k"] == scn["boundSite"]
        ax.scatter(*p, s=44 if big else 13, color=c, depthshade=False, zorder=5)
        ax.plot(*np.array([p, p + n * 4.0]).T, color=c, lw=1.6 if big else .9, alpha=.9 if big else .55, zorder=5)
    # S2 beam
    ax.plot(nd[:, 0], nd[:, 1], nd[:, 2], "-o", color="#66aadd", lw=2.0, ms=3.0, zorder=4)
    # lever, head, F8
    ax.plot(*np.array([nd[-1], C]).T, color="#d99a1f", lw=4.0, zorder=5)
    ax.plot(*np.array([H, F]).T, color="#ee8855", lw=4.0, zorder=6)
    ax.scatter(*H, s=110, color="#ee8855", alpha=.45, depthshade=False, zorder=6)
    ax.scatter(*C, s=22, color="#ffffff", depthshade=False, zorder=7)
    ax.scatter(*F, s=26, color="#ffffff", edgecolor="k", linewidths=.4, depthshade=False, zorder=7)
    # eBind + rest
    eb = np.array(f["eB"]); ax.plot(*np.array([F, F + eb * 14]).T, color="#ffff33", lw=2.2, zorder=8)
    er = np.array(f["eR"])
    if np.dot(er, er) > 1e-6:
        ax.plot(*np.array([F, F + er * 10]).T, color="#ff8800", lw=1.4, ls="--", zorder=8)
    if "sp" in f:
        sp = np.array(f["sp"]) * S
        ax.plot(*np.array([F, sp]).T, color="#ffffff" if f["b"] else "#54606e",
                lw=1.6 if f["b"] else .9, zorder=8)
    ax.view_init(elev=ELEV, azim=AZIM)
    c0 = 0.5 * (F + H)
    R = 30
    ax.set_xlim(c0[0]-R, c0[0]+R); ax.set_ylim(c0[1]-R, c0[1]+R); ax.set_zlim(c0[2]-R, c0[2]+R)
    ax.set_xticks([]); ax.set_yticks([]); ax.set_zticks([])
    ax.set_facecolor("#0c1016")
    for pane in (ax.xaxis, ax.yaxis, ax.zaxis):
        pane.set_pane_color((0.047, 0.063, 0.086, 1.0))
        pane._axinfo["grid"]["color"] = (0.16, 0.20, 0.26, 1.0)
        pane.line.set_color((0.16, 0.20, 0.26, 1.0))
    rel = i - scn["bindFrame"]
    st = "BOUND" if f["b"] else "detached"
    ax.set_title(f"{title}\nframe {rel:+d}  ·  {st}  ·  d={f['d']:.2f} nm  ∠n={f['aS']:.0f}°",
                 fontsize=7.5, color="#dfe6f0", pad=2)


def sheet(arm):
    scn, frm = load(arm)
    b = scn["bindFrame"]
    lastB = max((i for i, f in enumerate(frm) if f["b"]), default=b)
    rej = next((i for i in range(b-1, max(0, b-400), -1) if "g" in frm[i]["g"] and "PASS" not in frm[i]["g"]), b-60)
    picks = [(0, "early detached search"), (150, "search"), (330, "search, another pose"),
             (520, "approach"), (b-160, "near approach"), (rej, "candidate REJECTED"),
             (b-8, "8 steps before"), (b-1, "1 step before"), (b, "BINDING STEP"),
             (b+3, "3 steps after"), (b+40, "early bound relaxation"),
             (lastB, "last bound frame" if lastB > b else "settled bound pose")]
    picks = [(max(0, min(len(frm)-1, i)), t) for i, t in picks]
    fig = plt.figure(figsize=(15.5, 11.6), facecolor="#080b10")
    for j, (i, t) in enumerate(picks):
        ax = fig.add_subplot(3, 4, j+1, projection="3d", facecolor="#0c1016")
        panel(ax, scn, frm, i, t)
    fig.suptitle(f"Single-motor fine-time binding — arm {arm.upper()}  ·  1 frame = 1 timestep (2.5 µs)  ·  same camera in every panel\n"
                 "blue = explicit S2 · yellow = lever(neck) · orange = head+F8 · yellow arrow = eBind · dashed orange = eBind_rest · green = the site it takes",
                 color="#dfe6f0", fontsize=10)
    fig.tight_layout(rect=(0, 0, 1, 0.93))
    fig.savefig(f"{FIG}/contact_sheet_{arm}.png", dpi=135, facecolor="#080b10")
    plt.close(fig)
    print("ok contact sheet", arm)


def traces():
    fig, axes = plt.subplots(2, 3, figsize=(15.0, 6.4))
    for arm, col in (("off", "#4c9be8"), ("on", "#e8654c")):
        scn, frm = load(arm)
        b = scn["bindFrame"]
        rel = np.arange(len(frm)) - b
        d = np.array([f["d"] for f in frm])
        aS = np.array([f["aS"] for f in frm])
        aR = np.array([f["aR"] for f in frm])
        chi = np.degrees([f["chi"] for f in frm])
        psi = np.degrees([f["psi"] for f in frm])
        s2 = np.array([f["s2"] for f in frm])
        H = np.array([f["H"] for f in frm]) * S
        step = np.r_[0, np.linalg.norm(np.diff(H, axis=0), axis=1)]
        for ax, y, ttl, yl in ((axes[0, 0], d, "F8 → site distance", "nm"),
                               (axes[0, 1], aS, "angle(eBind, n_site)", "deg"),
                               (axes[0, 2], aR, "angle(eBind, eBind_rest)", "deg"),
                               (axes[1, 0], psi, "ψ", "deg"),
                               (axes[1, 1], chi, "χ", "deg"),
                               (axes[1, 2], step, "head-centre displacement PER TIMESTEP", "nm")):
            ax.plot(rel, y, color=col, lw=.8, label=f"arm {arm}")
            ax.set_title(ttl, fontsize=9); ax.set_ylabel(yl); ax.set_xlabel("frame relative to binding")
            ax.axvline(0, color="#33aa66", lw=1.2, ls="--")
            ax.grid(alpha=.3)
    axes[0, 0].axhline(3.0, color="#aa4444", ls=":", lw=1.0)
    axes[0, 0].text(-780, 3.2, "g0 capture distance 3 nm", fontsize=7, color="#aa4444")
    axes[0, 1].axhline(25.0, color="#aa4444", ls=":", lw=1.0)
    axes[0, 1].text(-780, 26, "25° (NOT gated on n_site today)", fontsize=7, color="#aa4444")
    axes[0, 0].legend(fontsize=8)
    fig.suptitle("Fine-time traces, one point per integration timestep; frame 0 = the natural binding step", fontsize=10)
    fig.tight_layout(rect=(0, 0, 1, 0.94))
    fig.savefig(f"{FIG}/fine_time_traces.png", dpi=140)
    plt.close(fig)
    print("ok traces")


for a in ("off", "on"):
    try:
        sheet(a)
    except Exception as ex:
        print("skip sheet", a, ex)
try:
    traces()
except Exception as ex:
    print("skip traces", ex)
print("figures ->", FIG)
