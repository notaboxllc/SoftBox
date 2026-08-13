#!/usr/bin/env python3
"""Figures for the Path-B site-aware capture implementation.

Plots the REAL exported lattice (ChiralSiteHarness -site-dump), not a re-derivation.

Usage: python3 scripts/plot_site_aware.py <dump_dir> <out_dir>
"""
import csv, sys, os, math
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.lines import Line2D

CLS_COLOR = {"NEAR": "#2a7fb8", "SIDE": "#8a8f98", "FAR": "#d1495b"}
CLS_LABEL = {"NEAR": "NEAR — normal faces the lawn", "SIDE": "SIDE — normal lateral",
             "FAR": "FAR — normal faces away from the lawn"}


def read(path):
    with open(path) as fh:
        rows = list(csv.DictReader((l for l in fh if not l.startswith("#")), delimiter="\t"))
    return rows


def hdr(path, key):
    for l in open(path):
        if l.startswith("#") and key in l:
            for tok in l.replace("#", "").split():
                if tok.startswith(key + "="):
                    return float(tok.split("=")[1])
    return float("nan")


def style(ax):
    ax.spines[["top", "right"]].set_visible(False)
    ax.grid(alpha=0.25, lw=0.5)


def main(dump, out):
    os.makedirs(out, exist_ok=True)
    sites = read(f"{dump}/sites.tsv")
    fil = read(f"{dump}/filament.tsv")
    R = hdr(f"{dump}/sites.tsv", "Ractin")
    rise = hdr(f"{dump}/sites.tsv", "rise")
    lawn = hdr(f"{dump}/sites.tsv", "lawn_z")

    x = np.array([float(r["x"]) for r in sites]) * 1e3      # nm
    y = np.array([float(r["y"]) for r in sites]) * 1e3
    z = np.array([float(r["z"]) for r in sites]) * 1e3
    k = np.array([int(r["k"]) for r in sites])
    cls = [r["class"] for r in sites]
    cb = np.array([float(r["cosBeta"]) for r in sites])
    Rn = R * 1e3

    # a window of a few helical turns, centred on the filament, for legibility
    xc = 0.5 * (x.min() + x.max())
    win = 90.0                                              # nm half-window ≈ 11 sites ≈ 4.4 turns
    sel = np.abs(x - xc) < win

    # ---------------------------------------------------------------- FIGURE 1: helical site geometry
    fig = plt.figure(figsize=(13, 7.2))
    gs = fig.add_gridspec(2, 2, width_ratios=[2.5, 1], height_ratios=[1, 1], hspace=0.42, wspace=0.24)

    # (a) side view: z vs x, with the filament cylinder outline
    ax = fig.add_subplot(gs[0, 0])
    ax.axhspan(-Rn, Rn, color="#cfe6f5", alpha=0.55, zorder=0)
    ax.axhline(0, color="#4b6b80", lw=1.1, ls="--", zorder=1, label="filament centreline")
    for lbl in ("NEAR", "SIDE", "FAR"):
        m = sel & np.array([c == lbl for c in cls])
        ax.scatter(x[m] - xc, z[m], s=44, c=CLS_COLOR[lbl], edgecolors="white", lw=0.6, zorder=3)
    # the underlying CONTINUOUS 13/6 helix the every3 sites are sampled from — drawn as a guide so the
    # helical wrap is visible (consecutive every3 sites jump -139.5deg, so the marker sequence zig-zags)
    tw = hdr(f"{dump}/sites.tsv", "twistRate")
    arc0 = np.array([float(r["arc"]) for r in sites])
    afine = np.linspace(arc0[sel].min(), arc0[sel].max(), 4000)
    xfine = np.interp(afine, arc0, x)
    zfine = Rn * np.sin(tw * afine)
    yfine = Rn * np.cos(tw * afine)
    ax.plot(xfine - xc, zfine, color="#7fb3d5", lw=0.8, alpha=0.9, zorder=1.5,
            label="continuous 13/6 helix (2.70 nm rise)")
    ax.plot(x[sel] - xc, z[sel], color="#555", lw=0.7, alpha=0.45, ls=":", zorder=2)
    ax.legend(loc="lower right", fontsize=7.5, frameon=False)
    ax.axhline(lawn * 1e3, color="#7a5c3e", lw=2.0, zorder=1)
    ax.text(-win * 0.98, lawn * 1e3 - 1.1, "myosin lawn plane (S2 emergence)", fontsize=8, color="#7a5c3e", va="top")
    ax.set_xlim(-win, win); ax.set_ylim(lawn * 1e3 - 3, Rn + 4)
    ax.set_xlabel("axial position along the filament  (nm, relative to centre)")
    ax.set_ylabel("height z  (nm)")
    ax.set_title("(a) SIDE VIEW — discrete binding sites spiral around the actin surface", loc="left", fontsize=11)
    style(ax)

    # (b) end / axial view: the helix seen down the filament axis
    ax = fig.add_subplot(gs[0, 1])
    th = np.linspace(0, 2 * np.pi, 256)
    ax.plot(Rn * np.cos(th), Rn * np.sin(th), color="#4b6b80", lw=1.3)
    ax.fill(Rn * np.cos(th), Rn * np.sin(th), color="#cfe6f5", alpha=0.55)
    for lbl in ("NEAR", "SIDE", "FAR"):
        m = sel & np.array([c == lbl for c in cls])
        ax.scatter(y[m], z[m], s=52, c=CLS_COLOR[lbl], edgecolors="white", lw=0.6, zorder=3)
    ax.axhline(lawn * 1e3, color="#7a5c3e", lw=2.0)
    ax.set_aspect("equal"); ax.set_xlim(-9, 9); ax.set_ylim(lawn * 1e3 - 1.5, 8)
    ax.set_xlabel("y  (nm)"); ax.set_ylabel("z  (nm)")
    ax.set_title("(b) END VIEW — sites wrap the\ncircumference; lawn is below", loc="left", fontsize=11)
    style(ax)

    # (c) unrolled: azimuth vs axial coordinate — the helix as a straight line
    ax = fig.add_subplot(gs[1, 0])
    beta = np.degrees(np.arctan2(y, z * 0 + 1) * 0)         # placeholder, recomputed below
    beta = np.degrees(np.arctan2(np.array([float(r["ny"]) for r in sites]),
                                 np.array([float(r["nz"]) for r in sites])))
    # angle measured from "away-from-lawn" (+z): 0 = FAR pole, +/-180 = NEAR pole
    beta = np.degrees(np.arctan2(np.array([float(r["ny"]) for r in sites]),
                                 np.array([float(r["nz"]) for r in sites])))
    for lbl in ("NEAR", "SIDE", "FAR"):
        m = sel & np.array([c == lbl for c in cls])
        ax.scatter(x[m] - xc, beta[m], s=40, c=CLS_COLOR[lbl], edgecolors="white", lw=0.6, zorder=3)
    ax.axhspan(-60, 60, color=CLS_COLOR["FAR"], alpha=0.10)
    ax.axhspan(120, 180, color=CLS_COLOR["NEAR"], alpha=0.10)
    ax.axhspan(-180, -120, color=CLS_COLOR["NEAR"], alpha=0.10)
    ax.set_xlim(-win, win); ax.set_ylim(-185, 185); ax.set_yticks([-180, -120, -60, 0, 60, 120, 180])
    ax.set_xlabel("axial position  (nm, relative to centre)")
    ax.set_ylabel("site azimuth β  (deg)\n0 = away from lawn")
    ax.set_title("(c) UNROLLED — each site advances −139.5° per 8.1 nm step (actin 13/6, left-handed)",
                 loc="left", fontsize=11)
    style(ax)

    # (d) site index annotation panel
    ax = fig.add_subplot(gs[1, 1])
    ax.axis("off")
    txt = (
        "Path-B discrete lattice (every3)\n"
        f"  axial rise            {rise*1e3:.2f} nm  (3 × 2.70 nm monomer)\n"
        f"  azimuthal advance     −139.5° per site\n"
        f"  surface radius        {Rn:.2f} nm  (= actin radius)\n"
        f"  sites shown           {int(sel.sum())} of {len(sites)}\n"
        f"  site index k          global, k = round(arc/rise)\n"
        "\n"
        "Accessibility class (site normal n̂ vs the\n"
        "away-from-lawn direction p̂, |β| bins of 120°):\n"
        "  NEAR  cosβ < −0.5      SIDE |cosβ| ≤ 0.5\n"
        "  FAR   cosβ > +0.5\n"
        "\n"
        "Capture rule (new): a head may bind a site only\n"
        "if it approaches from OUTSIDE the filament,\n"
        "        n̂·(x_F8 − x_site) > 0."
    )
    ax.text(0, 1, txt, va="top", ha="left", fontsize=9, family="monospace")

    handles = [Line2D([], [], marker="o", ls="", color=CLS_COLOR[c], markeredgecolor="white",
                      label=CLS_LABEL[c]) for c in ("NEAR", "SIDE", "FAR")]
    fig.legend(handles=handles, loc="lower center", ncol=3, frameon=False, fontsize=9.5,
               bbox_to_anchor=(0.5, -0.005))
    fig.suptitle("Path-B actin binding sites are a discrete helical lattice on the filament SURFACE",
                 fontsize=13, y=0.985)
    fig.savefig(f"{out}/fig1_helical_site_geometry.png", dpi=170, bbox_inches="tight")
    plt.close(fig)

    # ---------------------------------------------------------------- FIGURE 2: 3-D helical wrap
    fig = plt.figure(figsize=(12.5, 4.6))
    for i, (elev, azim, ttl) in enumerate([(18, -62, "(a) oblique — the lattice spirals along the surface"),
                                           (6, -78, "(b) near side-on"),
                                           (0, 0, "(c) down the filament axis")]):
        ax = fig.add_subplot(1, 3, i + 1, projection="3d")
        xs = np.linspace((x[sel] - xc).min(), (x[sel] - xc).max(), 2)
        th = np.linspace(0, 2 * np.pi, 60)
        XX, TT = np.meshgrid(xs, th)
        ax.plot_surface(XX, Rn * np.cos(TT), Rn * np.sin(TT), color="#cfe6f5", alpha=0.30,
                        linewidth=0, shade=False)
        tw = hdr(f"{dump}/sites.tsv", "twistRate")
        arc0 = np.array([float(r["arc"]) for r in sites])
        afine = np.linspace(arc0[sel].min(), arc0[sel].max(), 4000)
        ax.plot(np.interp(afine, arc0, x) - xc, Rn * np.cos(tw * afine), Rn * np.sin(tw * afine),
                color="#2f7fb8", lw=0.9, alpha=0.95)
        for lbl in ("NEAR", "SIDE", "FAR"):
            m = sel & np.array([c == lbl for c in cls])
            ax.scatter(x[m] - xc, y[m], z[m], s=26, c=CLS_COLOR[lbl], depthshade=False,
                       edgecolors="white", linewidths=0.4)
        ax.view_init(elev=elev, azim=azim)
        ax.set_title(ttl, fontsize=9.5, loc="left", pad=-2)
        ax.tick_params(labelsize=6.5, pad=-2)
        if i == 2:                       # axial axis is edge-on here; its ticks/label only collide
            ax.set_xticks([]); ax.set_xlabel("")
            ax.set_ylabel("y (nm)", fontsize=8); ax.set_zlabel("z (nm)", fontsize=8)
            try: ax.set_box_aspect((0.6, 1, 1))
            except Exception: pass
        else:
            ax.set_xlabel("axial (nm)", fontsize=8); ax.set_ylabel("y (nm)", fontsize=8)
            ax.set_zlabel("z (nm)", fontsize=8)
            try: ax.set_box_aspect((3, 1, 1))
            except Exception: pass
    fig.suptitle("The same sites in 3-D — the lattice genuinely wraps helically around the actin surface",
                 fontsize=12.5, y=0.99)
    fig.legend(handles=handles, loc="lower center", ncol=3, frameon=False, fontsize=9, bbox_to_anchor=(0.5, 0.0))
    fig.subplots_adjust(left=0.02, right=0.98, top=0.90, bottom=0.10, wspace=0.02)
    fig.savefig(f"{out}/fig2_helical_wrap_3d.png", dpi=170, bbox_inches="tight")
    plt.close(fig)

    # ---------------------------------------------------------------- FIGURE 3: bound heads
    bh = read(f"{dump}/bound_heads.tsv")
    fig = plt.figure(figsize=(13, 5.6))
    gs = fig.add_gridspec(1, 2, width_ratios=[2.4, 1], wspace=0.22)

    bx = np.array([float(r["sx"]) for r in bh]) * 1e3
    by = np.array([float(r["sy"]) for r in bh]) * 1e3
    bz = np.array([float(r["sz"]) for r in bh]) * 1e3
    hx = np.array([float(r["hx"]) for r in bh]) * 1e3
    hy = np.array([float(r["hy"]) for r in bh]) * 1e3
    hz = np.array([float(r["hz"]) for r in bh]) * 1e3
    bcls = [r["class"] for r in bh]

    ax = fig.add_subplot(gs[0, 0])
    ax.axhspan(-Rn, Rn, color="#cfe6f5", alpha=0.5, zorder=0)
    ax.axhline(0, color="#4b6b80", lw=1.0, ls="--", zorder=1)
    ax.scatter(x - xc, z, s=9, c="#c9ced6", zorder=2, label="unoccupied sites")
    for lbl in ("NEAR", "SIDE", "FAR"):
        m = np.array([c == lbl for c in bcls])
        if m.any():
            ax.scatter(bx[m] - xc, bz[m], s=110, c=CLS_COLOR[lbl], edgecolors="black", lw=0.8,
                       zorder=5, marker="o")
    for i in range(len(bh)):
        ax.plot([hx[i] - xc, bx[i] - xc], [hz[i], bz[i]], color="#444", lw=1.0, alpha=0.8, zorder=4)
    ax.scatter(hx - xc, hz, s=26, marker="^", c="#333", zorder=5, label="head F8 anchor")
    ax.axhline(lawn * 1e3, color="#7a5c3e", lw=2.2, zorder=1)
    ax.text((x - xc).min(), lawn * 1e3 - 1.4, "myosin lawn plane", fontsize=8, color="#7a5c3e", va="top")
    ax.set_xlabel("axial position along the filament  (nm)")
    ax.set_ylabel("height z  (nm)")
    ax.set_title(f"(a) SIDE VIEW — {len(bh)} bound heads on real helical sites after a short site-aware run",
                 loc="left", fontsize=11)
    ax.set_ylim(lawn * 1e3 - 4, Rn + 8)
    style(ax)

    # END VIEW in LOCAL radial coordinates: the filament is a bent chain, so absolute lab y/z would smear
    # the cross-section. Each site is plotted at R*n_hat, and each head at its offset from that segment's axis.
    ax = fig.add_subplot(gs[0, 1])
    bnx = np.array([float(r["nx"]) for r in bh]); bny = np.array([float(r["ny"]) for r in bh])
    bnz = np.array([float(r["nz"]) for r in bh])
    axy = by - Rn * bny; axz = bz - Rn * bnz            # the segment axis point, in nm
    ry, rz = Rn * bny, Rn * bnz                          # site radial offset
    hry, hrz = hy - axy, hz - axz                        # head radial offset from the same axis point
    th = np.linspace(0, 2 * np.pi, 256)
    ax.plot(Rn * np.cos(th), Rn * np.sin(th), color="#4b6b80", lw=1.3)
    ax.fill(Rn * np.cos(th), Rn * np.sin(th), color="#cfe6f5", alpha=0.5)
    sny = np.array([float(r["ny"]) for r in sites]); snz = np.array([float(r["nz"]) for r in sites])
    ax.scatter(Rn * sny, Rn * snz, s=7, c="#c9ced6", zorder=2)
    for i in range(len(bh)):
        ax.plot([hry[i], ry[i]], [hrz[i], rz[i]], color="#444", lw=1.0, alpha=0.8, zorder=4)
    for lbl in ("NEAR", "SIDE", "FAR"):
        m = np.array([c == lbl for c in bcls])
        if m.any():
            ax.scatter(ry[m], rz[m], s=130, c=CLS_COLOR[lbl], edgecolors="black", lw=0.8, zorder=5)
    ax.scatter(hry, hrz, s=30, marker="^", c="#333", zorder=5)
    lim = max(9.0, float(np.max(np.abs(np.concatenate([hry, hrz])))) + 1.5)
    ax.set_aspect("equal"); ax.set_xlim(-lim, lim); ax.set_ylim(-lim, lim)
    ax.set_xlabel("radial y from the local filament axis  (nm)")
    ax.set_ylabel("radial z  (nm)")
    ax.set_title("(b) END VIEW (local radial frame)\nheads approach from BELOW the axis", loc="left", fontsize=11)
    ax.annotate("lawn side", xy=(0, -lim + 0.8), ha="center", fontsize=8, color="#7a5c3e")
    style(ax)

    fig.legend(handles=handles + [Line2D([], [], marker="^", ls="", color="#333", label="head F8 anchor"),
                                  Line2D([], [], marker="o", ls="", color="#c9ced6", label="unoccupied site")],
               loc="lower center", ncol=5, frameon=False, fontsize=9, bbox_to_anchor=(0.5, -0.03))
    fig.suptitle("Bound myosin heads occupy real off-axis helical sites, not a centreline abstraction",
                 fontsize=12.5)
    fig.savefig(f"{out}/fig3_bound_heads_snapshot.png", dpi=170, bbox_inches="tight")
    plt.close(fig)
    print(f"wrote {out}/fig1_helical_site_geometry.png")
    print(f"wrote {out}/fig2_helical_wrap_3d.png")
    print(f"wrote {out}/fig3_bound_heads_snapshot.png  ({len(bh)} bound heads)")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
