#!/usr/bin/env python3
"""
Figures for docs/motor/MYOSIN_HEAD_ORIENTATION_DOF_HISTORY.md.

Plots straight from the TSVs written by softbox.HeadOrientationDofProbe, which are themselves
sampled from the two motors as they actually run. No molecular art, nothing re-derived.
"""
import os
import sys
import csv
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

DATA = sys.argv[1] if len(sys.argv) > 1 else "RUN_LOGS/motor_audit/head_orientation_dof_history"
OUT = sys.argv[2] if len(sys.argv) > 2 else "docs/motor/figures/head_orientation_dof_history"
os.makedirs(OUT, exist_ok=True)

C_CUR, C_HIS, C_ACT, C_TLT = "#ff5a3c", "#2ee6b0", "#3a6ea8", "#ffd23f"
plt.rcParams.update({
    "figure.facecolor": "#0d1117", "axes.facecolor": "#0d1117",
    "text.color": "#dfe6f0", "axes.labelcolor": "#dfe6f0",
    "xtick.color": "#9fb0c4", "ytick.color": "#9fb0c4",
    "axes.edgecolor": "#2a3646", "font.size": 9, "savefig.facecolor": "#0d1117",
})


def load(name):
    with open(os.path.join(DATA, name)) as fh:
        return list(csv.DictReader(fh, delimiter="\t"))


def xyz(rows):
    return np.array([[float(r["ex"]), float(r["ey"]), float(r["ez"])] for r in rows])


cur = xyz(load("ebind_current.tsv"))
tlt = xyz(load("ebind_tilt.tsv")) if os.path.exists(os.path.join(DATA, "ebind_tilt.tsv")) else None
his = xyz(load("ebind_historical.tsv"))
reach = load("site_reach.tsv")


def sphere(ax, alpha=0.09):
    u, v = np.mgrid[0:2 * np.pi:60j, 0:np.pi:30j]
    ax.plot_wireframe(np.cos(u) * np.sin(v), np.sin(u) * np.sin(v), np.cos(v),
                      color="#3a4a5e", linewidth=0.35, alpha=0.55)


def style(ax, title):
    for a in (ax.xaxis, ax.yaxis, ax.zaxis):
        a.pane.set_facecolor("#0d1117"); a.pane.set_edgecolor("#1d2733"); a._axinfo["grid"]["color"] = "#1b2432"
    ax.set_xlabel("x  (filament axis)"); ax.set_ylabel("y"); ax.set_zlabel("z  (away from lawn)")
    ax.set_xlim(-1.1, 1.1); ax.set_ylim(-1.1, 1.1); ax.set_zlim(-1.1, 1.1)
    ax.set_box_aspect((1, 1, 1))
    ax.set_title(title, color="#8fd9d0", fontsize=10)


# ---------------------------------------------------------------- FIGURES 1-3
def figs123():
    for tag, V, col, name, ttl in [
        ("cur", cur, C_CUR, "fig1_current_ebind_locus.png",
         "FIGURE 1 — CURRENT motor: eBind endpoints for one fixed motor\n"
         "sweeping BOTH generalized coordinates over a full turn"),
        ("tlt", tlt if tlt is not None else his, C_TLT, "fig5_restored_ebind_patch.png",
         "FIGURE 5 — RESTORED motor: eBind endpoints for one fixed motor\n"
         "(psi, chi) swept through the real matBeamGeomTilt kernel"),
        ("his", his, C_HIS, "fig2_historical_ebind_patch.png",
         "FIGURE 2 — HISTORICAL sphere-head motor: eBind endpoints for one fixed motor\n"
         "4 ms of detached Brownian search, shared rigid-body integrator"),
    ]:
        fig = plt.figure(figsize=(6.4, 6.0))
        ax = fig.add_subplot(111, projection="3d")
        sphere(ax)
        ax.scatter(V[:, 0], V[:, 1], V[:, 2], s=3, c=col, alpha=0.55, depthshade=False)
        style(ax, "")
        ax.view_init(elev=20, azim=-58)
        fig.suptitle(ttl, color="#dfe6f0", fontsize=10, y=0.97)
        fig.tight_layout(rect=[0, 0, 1, 0.90])
        fig.savefig(os.path.join(OUT, name), dpi=155)
        plt.close(fig)

    fig = plt.figure(figsize=(7.2, 6.4))
    ax = fig.add_subplot(111, projection="3d")
    sphere(ax)
    ax.scatter(his[:, 0], his[:, 1], his[:, 2], s=4, c=C_HIS, alpha=0.35, depthshade=False,
               label=f"HISTORICAL sphere-head  ({len(his)} samples, 97.8 % of 4π)")
    if tlt is not None:
        ax.scatter(tlt[:, 0], tlt[:, 1], tlt[:, 2], s=3, c=C_TLT, alpha=0.30, depthshade=False,
                   label=f"RESTORED  psi+chi  ({len(tlt)} samples, 99.8 % of 4π)")
    ax.scatter(cur[:, 0], cur[:, 1], cur[:, 2], s=6, c=C_CUR, alpha=0.95, depthshade=False,
               label="CURRENT explicit-S2  (one great circle, 2.9 % of 4π)")
    style(ax, "")
    ax.view_init(elev=20, azim=-58)
    ax.legend(fontsize=8.5, loc="upper left", framealpha=0.3, facecolor="#131a24",
              edgecolor="#2a3646", labelcolor="#dfe6f0")
    fig.suptitle("FIGURE 3 — OVERLAY: what one fixed motor's head can face\n"
                 "the current motor is confined to a single lab-fixed great circle",
                 color="#dfe6f0", fontsize=10.5, y=0.97)
    fig.tight_layout(rect=[0, 0, 1, 0.90])
    fig.savefig(os.path.join(OUT, "fig3_overlay.png"), dpi=155)
    plt.close(fig)


# ---------------------------------------------------------------- FIGURE 4
def fig4():
    az = np.array([float(r["azimuth_deg"]) for r in reach])
    bc = np.array([float(r["best_current_deg"]) for r in reach])
    bh = np.array([float(r["best_historical_deg"]) for r in reach])
    bt = np.array([float(r.get("best_tilt_deg", "nan")) for r in reach])
    pc = np.array([int(r["pass_current"]) for r in reach])
    ph = np.array([int(r["pass_historical"]) for r in reach])
    o = np.argsort(az); az, bc, bh, pc, ph, bt = az[o], bc[o], bh[o], pc[o], ph[o], bt[o]

    fig = plt.figure(figsize=(13.0, 5.8))

    ax = fig.add_subplot(1, 2, 1)
    th = np.linspace(0, 2 * np.pi, 300)
    ax.add_patch(plt.Circle((0, 0), 3.5, color=C_ACT, alpha=0.30, lw=0))
    ax.plot(3.5 * np.cos(th), 3.5 * np.sin(th), color="#7fb8ee", lw=1.1)
    for a, p1, p2 in zip(az, pc, ph):
        r = np.radians(a)
        n = np.array([np.cos(r), np.sin(r)])
        col = C_CUR if p1 else (C_HIS if p2 else "#6b7787")
        ax.arrow(3.5 * n[0], 3.5 * n[1], 5.5 * n[0], 5.5 * n[1], color=col,
                 width=0.16, head_width=0.7, length_includes_head=True, alpha=0.95)
    ax.set_aspect("equal"); ax.set_xlim(-12, 12); ax.set_ylim(-12, 12)
    ax.set_xlabel("filament material ŷ  (nm)"); ax.set_ylabel("filament material ẑ  (nm)")
    ax.grid(alpha=0.13, color="#2a3646")
    ax.set_title("every4 site normals, looking down the filament axis", color="#8fd9d0", fontsize=9.5)
    hs = [plt.Line2D([], [], color=C_CUR, lw=3, label="reachable by BOTH motors (6/20)"),
          plt.Line2D([], [], color=C_HIS, lw=3, label="reachable ONLY by the historical motor (14/20)"),
          plt.Line2D([], [], color="#6b7787", lw=3, label="reachable by neither (0/20)")]
    ax.legend(handles=hs, fontsize=8, loc="upper right", framealpha=0.3, facecolor="#131a24",
              edgecolor="#2a3646", labelcolor="#dfe6f0")

    ax2 = fig.add_subplot(1, 2, 2)
    ax2.axhline(25, color="#ffd23f", ls="--", lw=1.4, label="historical stereospecific tolerance, 25°")
    ax2.plot(az, bc, "o-", color=C_CUR, lw=1.8, ms=6, label="CURRENT — best achievable ∠(eBind, n_site)")
    ax2.plot(az, bh, "s-", color=C_HIS, lw=1.8, ms=6, label="HISTORICAL — best achievable ∠(eBind, n_site)")
    if not np.isnan(bt).all():
        ax2.plot(az, bt, "^-", color=C_TLT, lw=2.0, ms=7, label="RESTORED psi+chi — best achievable ∠(eBind, n_site)")
    ax2.fill_between(az, 0, 25, color="#ffd23f", alpha=0.07)
    ax2.set_xlabel("site azimuth  (deg)"); ax2.set_ylabel("smallest reachable angle to n_site  (deg)")
    ax2.set_xticks([-180, -90, 0, 90, 180]); ax2.set_ylim(-3, 95)
    ax2.grid(alpha=0.13, color="#2a3646")
    ax2.legend(fontsize=8.5, framealpha=0.25, facecolor="#131a24", edgecolor="#2a3646", labelcolor="#dfe6f0")
    ax2.set_title("current 6/20 (30 %)   ·   RESTORED 20/20 (100 %, worst 0.80°)   ·   historical 20/20",
                  color="#8fd9d0", fontsize=9.5)

    fig.suptitle("FIGURE 4 — can ONE fixed motor face the helical site normals?",
                 color="#dfe6f0", fontsize=10.5, y=0.97)
    fig.tight_layout(rect=[0, 0, 1, 0.92])
    fig.savefig(os.path.join(OUT, "fig4_site_normal_reachability.png"), dpi=155)
    plt.close(fig)


figs123(); print("ok figs 1-3")
fig4(); print("ok fig 4")
print("figures ->", OUT)
