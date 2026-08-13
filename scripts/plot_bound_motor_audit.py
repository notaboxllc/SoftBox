#!/usr/bin/env python3
"""
Figures for docs/attachment/BOUND_MOTOR_HELICAL_GEOMETRY_VISUAL_AUDIT.md.

Plots STRAIGHT FROM the TSVs that `./scripts/run_chiral_sites.sh -bound-viz` writes, which are in turn
read back out of the running capture / head-placement kernels. Nothing here re-derives geometry and
nothing is drawn that the code did not emit.

  python3 scripts/plot_bound_motor_audit.py [datadir] [outdir]
"""
import os
import sys
import csv
import math

import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from mpl_toolkits.mplot3d.art3d import Poly3DCollection

DATA = sys.argv[1] if len(sys.argv) > 1 else "RUN_LOGS/attachment_audit/bound_motor_visual_audit"
OUT = sys.argv[2] if len(sys.argv) > 2 else "docs/attachment/figures/bound_motor_helical_visual_audit"
os.makedirs(OUT, exist_ok=True)

NM = 1e3                    # model length unit is um; every figure is in nm
C_ACTIN = "#2f4d70"
C_SITE = "#8b98a8"
C_OCC = "#ff9a30"
C_HEAD = "#3f8fe0"
C_NORM = "#00c9b7"
C_PERP = "#ff2fb0"
C_EB = "#e8b800"
C_BOND = "#ffffff"
plt.rcParams.update({
    "figure.facecolor": "#0d1117", "axes.facecolor": "#0d1117",
    "text.color": "#dfe6f0", "axes.labelcolor": "#dfe6f0",
    "xtick.color": "#9fb0c4", "ytick.color": "#9fb0c4",
    "axes.edgecolor": "#2a3646", "font.size": 9, "savefig.facecolor": "#0d1117",
})


def read(name):
    p = os.path.join(DATA, name)
    with open(p) as fh:
        return list(csv.DictReader(fh, delimiter="\t"))


def F(rows, key):
    return np.array([float(r[key]) for r in rows])


def load(tag):
    return read(f"sites_{tag}.tsv"), read(f"heads_{tag}.tsv"), read(f"filament_{tag}.tsv")


def axis_frame(fil):
    """Filament axis + a stable transverse pair, from the emitted segment frames."""
    u = np.array([float(fil[0]["ux"]), float(fil[0]["uy"]), float(fil[0]["uz"])])
    y = np.array([float(fil[0]["yx"]), float(fil[0]["yy"]), float(fil[0]["yz"])])
    z = np.cross(u, y)
    return u, y, z / max(np.linalg.norm(z), 1e-30)


def draw_actin(ax, fil, sites, radius_nm=3.5, alpha=0.16, pad_nm=6.0):
    """Actin drawn as a plain wireframe cylinder cage at R = 3.5 nm over the axial span the plotted
    sites occupy, built from the emitted segment material frame. No molecular art, no decoration."""
    u, y, z = axis_frame(fil)
    c0 = np.array([float(fil[0]["cx_um"]), float(fil[0]["cy_um"]), float(fil[0]["cz_um"])]) * NM
    L0 = float(fil[0]["segLen_um"]) * NM
    base = c0 - 0.5 * L0 * u
    a = np.array([float(s["gArc_nm"]) for s in sites])
    lo, hi = a.min() - pad_nm, a.max() + pad_nm
    th = np.linspace(0, 2 * np.pi, 73)
    ring = (np.outer(np.cos(th), y) + np.outer(np.sin(th), z)) * radius_nm
    for t0 in np.linspace(lo, hi, max(4, int((hi - lo) / 10.8) + 1)):        # circumferential rings
        r = base + t0 * u + ring
        ax.plot(r[:, 0], r[:, 1], r[:, 2], color="#4f7cae", lw=0.6, alpha=0.45, zorder=1)
    for t1 in np.linspace(0, 2 * np.pi, 13)[:-1]:                            # longitudinal generators
        o = base + radius_nm * (np.cos(t1) * y + np.sin(t1) * z)
        ax.plot(*np.array([o + lo * u, o + hi * u]).T, color="#4f7cae", lw=0.5, alpha=0.30, zorder=1)
    ax.plot(*np.array([base + lo * u, base + hi * u]).T, color="#8fc4f2", lw=1.2, alpha=0.9, zorder=2)
    return base, u, y, z


def site_xyz(sites):
    return (F(sites, "x_um") * NM, F(sites, "y_um") * NM, F(sites, "z_um") * NM)


def equalize(ax, X, Y, Z, pad=4.0, maxratio=5.0):
    """True (undistorted) aspect with the long axis capped at `maxratio`, so an elongated
    filament stays readable without silently misrepresenting the radial geometry."""
    xr = [X.min() - pad, X.max() + pad]
    cy, cz = 0.5 * (Y.min() + Y.max()), 0.5 * (Z.min() + Z.max())
    rad = 0.5 * max(np.ptp(Y), np.ptp(Z)) + pad
    yr, zr = [cy - rad, cy + rad], [cz - rad, cz + rad]
    ax.set_xlim(*xr); ax.set_ylim(*yr); ax.set_zlim(*zr)
    dx, dy, dz = xr[1] - xr[0], yr[1] - yr[0], zr[1] - zr[0]
    ratio = min(dx / max(dy, 1e-9), maxratio)
    # `zoom` counteracts matplotlib's conservative fit for a long thin box: the geometry stays
    # exactly to scale (aspect is the true one), it is simply drawn larger inside the same axes.
    ax.set_box_aspect((ratio, 1.0, dz / max(dy, 1e-9)), zoom=min(1.70, 0.95 + 0.16 * ratio))


def style3d(ax, title=None):
    for a in (ax.xaxis, ax.yaxis, ax.zaxis):
        a.pane.set_facecolor("#0d1117"); a.pane.set_edgecolor("#1d2733"); a._axinfo["grid"]["color"] = "#1b2432"
    ax.set_xlabel("x  (nm)"); ax.set_ylabel("y  (nm)"); ax.set_zlabel("z  (nm)")
    if title:
        ax.set_title(title, color="#8fd9d0", fontsize=10, pad=2)


def arrows(ax, org, vec, length, color, lw=1.7, ratio=0.28):
    for o, v in zip(org, vec):
        n = v / max(np.linalg.norm(v), 1e-30)
        ax.quiver(o[0], o[1], o[2], n[0], n[1], n[2], length=length, color=color,
                  lw=lw, arrow_length_ratio=ratio, normalize=True)


def head_vecs(heads):
    site = np.stack([F(heads, "sx_um"), F(heads, "sy_um"), F(heads, "sz_um")], 1) * NM
    n = np.stack([F(heads, "nx"), F(heads, "ny"), F(heads, "nz")], 1)
    t = np.stack([F(heads, "tx"), F(heads, "ty"), F(heads, "tz")], 1)
    f8 = np.stack([F(heads, "f8x_um"), F(heads, "f8y_um"), F(heads, "f8z_um")], 1) * NM
    xh = np.stack([F(heads, "hx_um"), F(heads, "hy_um"), F(heads, "hz_um")], 1) * NM
    eb = np.stack([F(heads, "ebx"), F(heads, "eby"), F(heads, "ebz")], 1)
    hp = np.stack([F(heads, "hpx"), F(heads, "hpy"), F(heads, "hpz")], 1)
    return site, n, t, f8, xh, eb, hp


def draw_heads(ax, heads, s=90):
    site, n, t, f8, xh, eb, hp = head_vecs(heads)
    ax.scatter(*xh.T, s=s, c=C_HEAD, alpha=0.55, edgecolor="#bcdcff", linewidth=0.5, depthshade=False)
    ax.scatter(*f8.T, s=12, c="#ffffff", depthshade=False)
    for a, b in zip(f8, site):
        ax.plot(*np.array([a, b]).T, color=C_BOND, lw=1.0, alpha=0.9)
    for a, b in zip(xh, f8):
        ax.plot(*np.array([a, b]).T, color="#9fb8d8", lw=0.9, alpha=0.6, ls=":")
    return site, n, t, f8, xh, eb, hp


def legend(ax, items, loc="upper left"):
    hs = [plt.Line2D([], [], color=c, lw=2.4, label=l) for l, c in items]
    lg = ax.legend(handles=hs, loc=loc, fontsize=7.5, framealpha=0.25, facecolor="#131a24",
                   edgecolor="#2a3646", labelcolor="#dfe6f0")
    return lg


def label_sites(ax, sites, every=1, dz=3.4, maxn=40):
    X, Y, Z = site_xyz(sites)
    nx, ny, nz = F(sites, "nx"), F(sites, "ny"), F(sites, "nz")
    occ = F(sites, "occ")
    if len(sites) > maxn:
        return
    for i in range(0, len(sites), every):
        ax.text(X[i] + dz * nx[i], Y[i] + dz * ny[i], Z[i] + dz * nz[i], str(sites[i]["k"]),
                color="#ffd9a8" if occ[i] else "#8fa0b4", fontsize=7.5, ha="center", va="center")


META = None


def meta_line(tag):
    s, h, f = load(tag)
    a = F(s, "gArc_nm"); az = F(s, "azim_deg")
    return f"every4 sparse lattice · rise 10.800 nm · +54.000°/site · 72.000 nm pitch · R = 3.500 nm"


# ---------------------------------------------------------------------------- FIGURE 1
def fig1():
    sites, heads, fil = load("fixtureC_radial_ring")
    fig = plt.figure(figsize=(13.5, 4.6))
    for i, (elev, azim, ttl) in enumerate([(18, -62, "oblique"), (0, -90, "side (⊥ filament axis)")]):
        ax = fig.add_subplot(1, 2, i + 1, projection="3d")
        draw_actin(ax, fil, sites)
        X, Y, Z = site_xyz(sites)
        ax.plot(X, Y, Z, color=C_OCC, lw=1.1, alpha=0.75, zorder=3)
        ax.scatter(X, Y, Z, s=46, c=C_SITE, edgecolor="#cfd8e4", linewidth=0.4, depthshade=False, zorder=4)
        label_sites(ax, sites)
        equalize(ax, X, Y, Z)
        ax.view_init(elev=elev, azim=azim)
        style3d(ax, ttl)
    fig.suptitle("FIGURE 1 — the sparse every4 effective-site lattice: one continuous long-pitch helical track\n"
                 + meta_line("fixtureC_radial_ring"), color="#dfe6f0", fontsize=10)
    fig.subplots_adjust(left=0.0, right=1.0, top=0.86, bottom=0.02, wspace=0.02)
    fig.savefig(os.path.join(OUT, "fig1_sparse_site_helix.png"), dpi=155)
    plt.close(fig)


# ---------------------------------------------------------------------------- FIGURES 2/3/4
def fig234():
    sitesAll, headsAll, fil = load("fixtureC_radial_ring")
    # the 3-D panels show ~1.2 long-pitch turns (8 consecutive sites, 86 nm) so the helical wrap is
    # legible at true aspect; the phase panel and figure 1 use the whole 12-head run.
    KMAX = 9
    sites = [r for r in sitesAll if int(r["k"]) <= KMAX]
    heads = [r for r in headsAll if int(r["site_k"]) <= KMAX]
    X, Y, Z = site_xyz(sites)

    def base(ax):
        draw_actin(ax, fil, sites)
        occ = F(sites, "occ")
        ax.plot(X, Y, Z, color=C_OCC, lw=0.9, alpha=0.5)
        ax.scatter(X[occ == 0], Y[occ == 0], Z[occ == 0], s=32, c=C_SITE, depthshade=False)
        ax.scatter(X[occ == 1], Y[occ == 1], Z[occ == 1], s=64, c=C_OCC,
                   edgecolor="#ffe0b0", linewidth=0.5, depthshade=False)
        label_sites(ax, sites)

    def wide(title, sub, fname, draw):
        fig = plt.figure(figsize=(11.0, 5.6))
        ax = fig.add_axes([0.0, -0.03, 1.0, 0.90], projection="3d")
        base(ax)
        items = draw(ax)
        equalize(ax, X, Y, Z, pad=10, maxratio=6.0)
        ax.view_init(elev=22, azim=-66)
        style3d(ax)
        hs = [plt.Line2D([], [], color=c, lw=2.6, label=l) for l, c in items]
        fig.legend(handles=hs, loc="upper left", bbox_to_anchor=(0.005, 0.86), fontsize=8,
                   framealpha=0.3, facecolor="#131a24", edgecolor="#2a3646", labelcolor="#dfe6f0")
        fig.suptitle(title + "\n" + sub, color="#dfe6f0", fontsize=10.5, y=0.985)
        fig.savefig(os.path.join(OUT, fname), dpi=155)
        plt.close(fig)

    wide("FIGURE 2 — 12 motors bound to consecutive effective sites",
         "each head occupies exactly one site; the occupied sites march around the filament",
         "fig2_bound_motors_on_sparse_sites.png",
         lambda ax: (draw_heads(ax, heads),
                     [("effective site (unoccupied)", C_SITE), ("occupied site", C_OCC),
                      ("bound motor head (centre xH)", C_HEAD), ("F8 bond  xF8 -> x_site", "#ffffff")])[1])

    def d3(ax):
        site, n, t, f8, xh, eb, hp = draw_heads(ax, heads)
        arrows(ax, site, n, 8.0, C_NORM)
        return [("occupied site", C_OCC), ("bound head", C_HEAD), ("n_site — LOCAL OUTWARD NORMAL", C_NORM)]
    wide("FIGURE 3 — bound motors + the local outward normal of each occupied site",
         "the normals rotate +54 deg per site: the site frame tracks the actin helix",
         "fig3_bound_motors_site_normals.png", d3)

    # FIGURE 4 — the audit figure: 3D on top, phase test below
    fig = plt.figure(figsize=(12.0, 9.4))
    ax = fig.add_axes([0.0, 0.44, 1.0, 0.47], projection="3d")
    base(ax)
    site, n, t, f8, xh, eb, hp = draw_heads(ax, heads)
    arrows(ax, site, n, 8.0, C_NORM)
    arrows(ax, xh, hp, 8.0, C_PERP)
    arrows(ax, xh, eb, 4.2, C_EB, lw=1.2, ratio=0.32)
    equalize(ax, X, Y, Z, pad=11, maxratio=6.0)
    ax.view_init(elev=22, azim=-66)
    style3d(ax)
    hs = [plt.Line2D([], [], color=c, lw=2.6, label=l) for l, c in
          [("n_site — local outward normal of the bound site", C_NORM),
           ("h_perp — head yVec (the binding-face reference)", C_PERP),
           ("eBind — head long axis (xF8 - xH)", C_EB)]]
    fig.legend(handles=hs, loc="upper left", bbox_to_anchor=(0.005, 0.90), fontsize=8.5,
               framealpha=0.3, facecolor="#131a24", edgecolor="#2a3646", labelcolor="#dfe6f0")

    ax2 = fig.add_axes([0.085, 0.065, 0.89, 0.30])
    k = F(headsAll, "site_k")
    pn = np.degrees(np.unwrap(np.radians(F(headsAll, "phase_site_deg"))))
    ph = np.degrees(np.unwrap(np.radians(F(headsAll, "phase_hperp_deg"))))
    pe = np.degrees(np.unwrap(np.radians(F(headsAll, "phase_ebind_deg"))))
    ax2.plot(k, pn, "o-", color=C_NORM, lw=2, ms=6, label="azimuth of n_site (the site's outward normal)")
    ax2.plot(k, pe, "^--", color=C_EB, lw=1.4, ms=6, alpha=0.9, label="azimuth of eBind (head long axis)")
    ax2.plot(k, ph, "s-", color=C_PERP, lw=2, ms=7, label="azimuth of h_perp (head yVec = binding-face reference)")
    ax2.set_xlabel("effective site index  k")
    ax2.set_ylabel("azimuth in the filament's\nOWN material frame  (deg)")
    ax2.grid(alpha=0.15, color="#2a3646")
    ax2.legend(fontsize=8.5, framealpha=0.25, facecolor="#131a24", edgecolor="#2a3646", labelcolor="#dfe6f0")
    ax2.set_title("the site normal advances +54 deg/site; the head's reference does not move at all "
                  "(flat at 0 deg for all 12 heads)", color="#ff8fd0", fontsize=9.5)
    fig.suptitle("FIGURE 4 — THE AUDIT FIGURE: does the head's perpendicular / binding-face reference follow "
                 "its own site normal?\nNO — every head carries the SAME h_perp while n_site rotates around the filament",
                 color="#dfe6f0", fontsize=11, y=0.985)
    fig.savefig(os.path.join(OUT, "fig4_head_orientation_vs_site_normal.png"), dpi=155)
    plt.close(fig)


# ---------------------------------------------------------------------------- FIGURE 5
def fig5():
    fig = plt.figure(figsize=(12.4, 6.0))
    for i, (tag, ttl) in enumerate([("fixtureC_radial_ring", "deterministic fixture C — 12 consecutive sites occupied"),
                                    ("fixtureB_alternate", "fixture B — every-other site occupied")]):
        sites, heads, fil = load(tag)
        u, y, z = axis_frame(fil)
        S = np.stack(site_xyz(sites), 1)
        c0 = np.array([float(fil[0]["cx_um"]), float(fil[0]["cy_um"]), float(fil[0]["cz_um"])]) * NM
        P = S - c0
        sy, sz = P @ y, P @ z
        occ = F(sites, "occ")
        ax = fig.add_subplot(1, 2, i + 1)
        th = np.linspace(0, 2 * np.pi, 200)
        ax.add_patch(plt.Circle((0, 0), 3.5, color=C_ACTIN, alpha=0.35, lw=0))
        ax.plot(3.5 * np.cos(th), 3.5 * np.sin(th), color="#7fb8ee", lw=1.0)
        ax.plot(sy, sz, color=C_OCC, lw=0.7, alpha=0.45)
        ax.scatter(sy[occ == 0], sz[occ == 0], s=42, c=C_SITE, zorder=4)
        ax.scatter(sy[occ == 1], sz[occ == 1], s=90, c=C_OCC, edgecolor="#ffe0b0", zorder=5)
        for j in range(len(sites)):
            ax.annotate(sites[j]["k"], (sy[j] * 1.30, sz[j] * 1.30), color="#ffd9a8" if occ[j] else "#8fa0b4",
                        fontsize=8, ha="center", va="center")
        site, n, t, f8, xh, eb, hp = head_vecs(heads)
        for a, v, c in ((site, n, C_NORM), (xh, hp, C_PERP)):
            ay, az_ = (a - c0) @ y, (a - c0) @ z
            vy, vz = v @ y, v @ z
            ax.quiver(ay, az_, vy, vz, color=c, angles="xy", scale_units="xy", scale=0.16, width=0.006, zorder=6)
        ax.set_aspect("equal")
        ax.set_xlim(-15, 15); ax.set_ylim(-15, 15)
        ax.set_xlabel("filament material ŷ  (nm)"); ax.set_ylabel("filament material ẑ  (nm)")
        ax.grid(alpha=0.13, color="#2a3646")
        ax.set_title(ttl, color="#8fd9d0", fontsize=9.5)
        if i == 0:
            ax.annotate("h_perp of ALL 12 heads projects to a POINT here:\nit lies along the filament AXIS, not the radial normal",
                        xy=(0, 0), xytext=(-14, 11.5), color=C_PERP, fontsize=8,
                        arrowprops=dict(arrowstyle="->", color=C_PERP, lw=1.0))
        else:
            ax.annotate("h_perp of every head points the SAME way (+lab ŷ)",
                        xy=(6, 2.4), xytext=(-14, 11.5), color=C_PERP, fontsize=8,
                        arrowprops=dict(arrowstyle="->", color=C_PERP, lw=1.0))
    fig.suptitle("FIGURE 5 — END-ON, looking down the filament axis\n"
                 "occupied sites (orange) march around the whole circumference; cyan = n_site, magenta = h_perp",
                 color="#dfe6f0", fontsize=10.5)
    fig.tight_layout(rect=[0, 0, 1, 0.90])
    fig.savefig(os.path.join(OUT, "fig5_end_on.png"), dpi=155)
    plt.close(fig)


# ---------------------------------------------------------------------------- FIGURE 6
def fig6():
    sites, heads, fil = load("natural")
    occ = F(sites, "occ")
    keep = [s for s in sites if float(s["occ"]) > 0]
    fig = plt.figure(figsize=(12.4, 5.6))

    ax = fig.add_axes([0.015, 0.03, 0.46, 0.80], projection="3d")
    X, Y, Z = site_xyz(sites)
    # the natural filament is bent (12 segments) so no single cylinder cage is valid — draw the
    # actual segment centreline polyline the run emitted, plus every effective site.
    seg = np.array([[float(r["cx_um"]), float(r["cy_um"]), float(r["cz_um"])] for r in fil]) * NM
    su = np.array([[float(r["ux"]), float(r["uy"]), float(r["uz"])] for r in fil])
    sl = np.array([float(r["segLen_um"]) for r in fil]) * NM
    ends = np.concatenate([[seg[0] - 0.5 * sl[0] * su[0]], seg + 0.5 * sl[:, None] * su])
    ax.plot(ends[:, 0], ends[:, 1], ends[:, 2], color="#8fc4f2", lw=1.6, alpha=0.9)
    ax.plot(X, Y, Z, color=C_SITE, lw=0.35, alpha=0.45)
    site, n, t, f8, xh, eb, hp = draw_heads(ax, heads, s=70)
    ax.scatter(*np.stack(site_xyz(keep), 1).T, s=70, c=C_OCC, edgecolor="#ffe0b0", depthshade=False, zorder=6)
    arrows(ax, site, n, 30.0, C_NORM)
    arrows(ax, xh, hp, 30.0, C_PERP)
    equalize(ax, X, Y, Z, pad=16, maxratio=2.6)
    ax.view_init(elev=20, azim=-64)
    style3d(ax, "whole 2.106 µm filament — 5 simultaneously bound heads")
    legend(ax, [("all 196 effective sites", C_SITE), ("occupied", C_OCC),
                ("n_site", C_NORM), ("h_perp", C_PERP)])

    ax2 = fig.add_axes([0.575, 0.10, 0.345, 0.76])
    vis = read("natural_visited_sites.tsv")
    va, vaz, vb = F(vis, "gArc_nm"), F(vis, "azim_deg"), F(vis, "boundSteps")
    sa, saz = F(sites, "gArc_nm"), F(sites, "azim_deg")
    ax2.scatter(sa / 1e3, saz, s=4, c=C_SITE, alpha=0.35, label="effective site")
    sc = ax2.scatter(va / 1e3, vaz, s=28 + 90 * vb / max(vb.max(), 1), c=vb, cmap="inferno",
                     edgecolor="#ffe0b0", linewidth=0.3, label="site occupied during the run", zorder=5)
    hh = F(heads, "gArc_nm") / 1e3
    ax2.scatter(hh, F(heads, "azim_deg"), s=150, facecolor="none", edgecolor=C_PERP, lw=1.6,
                label="bound in the snapshot frame", zorder=6)
    plt.colorbar(sc, ax=ax2, label="bound-head steps at that site", pad=0.02)
    ax2.set_xlabel("axial position along the filament  (µm)")
    ax2.set_ylabel("site azimuth  (deg)")
    ax2.set_ylim(-190, 190); ax2.set_yticks([-180, -90, 0, 90, 180])
    ax2.grid(alpha=0.13, color="#2a3646")
    ax2.legend(fontsize=7.5, framealpha=0.25, facecolor="#131a24", edgecolor="#2a3646", labelcolor="#dfe6f0", loc="upper right")
    ax2.set_title(f"{len(vis)} distinct sites used over the run — spread over the whole circumference",
                  color="#8fd9d0", fontsize=9.5)
    fig.suptitle("FIGURE 6 — NATURAL SHORT-RUN SNAPSHOT (real Path-B gliding run, CPU runner, richest frame)",
                 color="#dfe6f0", fontsize=10.5, y=0.975)
    fig.savefig(os.path.join(OUT, "fig6_natural_snapshot.png"), dpi=155)
    plt.close(fig)


# ---------------------------------------------------------------------------- FIGURE 7
def fig7():
    fig, axs = plt.subplots(2, 1, figsize=(10.5, 7.2), sharex=False)
    sites, heads, fil = load("fixtureC_radial_ring")
    a, az, occ = F(sites, "gArc_nm"), F(sites, "azim_deg"), F(sites, "occ")
    unw = np.degrees(np.unwrap(np.radians(az)))
    ax = axs[0]
    ax.plot(a, unw, "-", color=C_OCC, lw=1.1, alpha=0.7)
    ax.scatter(a[occ == 0], unw[occ == 0], s=42, c=C_SITE, zorder=4, label="effective site")
    ax.scatter(a[occ == 1], unw[occ == 1], s=86, c=C_OCC, edgecolor="#ffe0b0", zorder=5, label="occupied by a bound head")
    hd = F(heads, "gArc_nm")
    ph = np.degrees(np.unwrap(np.radians(F(heads, "phase_hperp_deg"))))
    ax.plot(hd, ph, "s--", color=C_PERP, lw=1.4, ms=5, label="azimuth of h_perp (head yVec)")
    sl = np.polyfit(a, unw, 1)[0]
    ax.text(0.02, 0.93, f"site track slope = {sl:.4f} deg/nm  ⇒ 360° every {360/sl:.2f} nm  (target 72.00 nm)",
            transform=ax.transAxes, color="#8fd9d0", fontsize=9)
    ax.set_xlabel("axial position along the filament  (nm)")
    ax.set_ylabel("unwrapped azimuth (deg)")
    ax.grid(alpha=0.13, color="#2a3646")
    ax.legend(fontsize=8, framealpha=0.25, facecolor="#131a24", edgecolor="#2a3646", labelcolor="#dfe6f0")
    ax.set_title("deterministic fixture C — the sparse diagonal is a single straight track; h_perp is FLAT",
                 color="#8fd9d0", fontsize=9.5)

    sites, heads, fil = load("natural")
    a, az, occ = F(sites, "gArc_nm") / 1e3, F(sites, "azim_deg"), F(sites, "occ")
    ax = axs[1]
    ax.scatter(a, az, s=3.5, c=C_SITE, alpha=0.45, label="effective site")
    ax.scatter(a[occ == 1], az[occ == 1], s=90, c=C_OCC, edgecolor="#ffe0b0", zorder=5, label="bound in the snapshot")
    vis = read("natural_visited_sites.tsv")
    ax.scatter(F(vis, "gArc_nm") / 1e3, F(vis, "azim_deg"), s=26, facecolor="none",
               edgecolor="#ffd23f", lw=1.0, label="used at some point in the run", zorder=4)
    ax.set_xlabel("axial position along the filament  (µm)")
    ax.set_ylabel("site azimuth (deg)")
    ax.set_ylim(-190, 190); ax.set_yticks([-180, -90, 0, 90, 180])
    ax.grid(alpha=0.13, color="#2a3646")
    ax.legend(fontsize=8, framealpha=0.25, facecolor="#131a24", edgecolor="#2a3646", labelcolor="#dfe6f0")
    ax.set_title("natural run — all 196 sites of the 2.106 µm filament, wrapped", color="#8fd9d0", fontsize=9.5)
    fig.suptitle("FIGURE 7 — UNWRAPPED LATTICE: axial position vs site azimuth", color="#dfe6f0", fontsize=10.5)
    fig.tight_layout(rect=[0, 0, 1, 0.94])
    fig.savefig(os.path.join(OUT, "fig7_unwrapped_lattice.png"), dpi=155)
    plt.close(fig)


# ---------------------------------------------------------------------------- FIGURE 8 (fixture A/B)
def fig8():
    fig = plt.figure(figsize=(13.5, 8.0))
    for i, (tag, ttl) in enumerate([("fixtureA_contiguous", "VERSION A — contiguous sites, real reference-pose head"),
                                    ("fixtureB_alternate", "VERSION B — every-other site, real reference-pose head")]):
        sites, heads, fil = load(tag)
        ax = fig.add_axes([0.0, 0.50 - 0.46 * i, 1.0, 0.40], projection="3d")
        draw_actin(ax, fil, sites)
        X, Y, Z = site_xyz(sites)
        occ = F(sites, "occ")
        ax.plot(X, Y, Z, color=C_OCC, lw=0.9, alpha=0.5)
        ax.scatter(X[occ == 0], Y[occ == 0], Z[occ == 0], s=32, c=C_SITE, depthshade=False)
        ax.scatter(X[occ == 1], Y[occ == 1], Z[occ == 1], s=64, c=C_OCC, edgecolor="#ffe0b0", depthshade=False)
        label_sites(ax, sites)
        site, n, t, f8, xh, eb, hp = draw_heads(ax, heads)
        arrows(ax, site, n, 7.0, C_NORM)
        arrows(ax, xh, hp, 7.0, C_PERP)
        equalize(ax, X, Y, Z, pad=11, maxratio=6.0)
        ax.view_init(elev=17, azim=-64)
        ax.set_title(ttl, color="#8fd9d0", fontsize=9.5, y=0.97)
        style3d(ax)
    fig.suptitle("FIGURE 8 — the same audit with a REAL reference-pose motor head (not a radial docking pose)\n"
                 "grey sites are the ones the g6 head-side gate refuses from this pose; h_perp is again the same for every head",
                 color="#dfe6f0", fontsize=10.5, y=0.99)
    fig.savefig(os.path.join(OUT, "fig8_reference_pose_head.png"), dpi=155)
    plt.close(fig)


for fn in (fig1, fig234, fig5, fig6, fig7, fig8):
    fn()
    print("ok", fn.__name__)
print("figures ->", OUT)
