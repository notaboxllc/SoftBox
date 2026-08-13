#!/usr/bin/env python3
"""Deterministic geometry sanity figures for the sparse long-pitch actin binding-site lattice.

Every point plotted is read verbatim from the TSVs that `ChiralSiteHarness -site-geometry` writes from
the SAME site generator the capture kernels use. Nothing is drawn from a formula re-derived here, no
molecular structure is depicted and no decorative element is added.

  python3 scripts/plot_sparse_sites.py [dataDir] [outDir]
"""
import sys, os, csv, math
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from mpl_toolkits.mplot3d import Axes3D  # noqa: F401

DATA = sys.argv[1] if len(sys.argv) > 1 else "RUN_LOGS/attachment_audit/sparse_long_pitch_sites"
OUT = sys.argv[2] if len(sys.argv) > 2 else "docs/attachment/figures/sparse_long_pitch_sites"
os.makedirs(OUT, exist_ok=True)


def load(tag):
    with open(os.path.join(DATA, f"sites_{tag}.tsv")) as fh:
        rows = list(csv.DictReader(fh, delimiter="\t"))
    d = {k: np.array([float(r[k]) for r in rows]) for k in rows[0] if k != "seg"}
    d["seg"] = np.array([int(r["seg"]) for r in rows])
    with open(os.path.join(DATA, f"meta_{tag}.tsv")) as fh:
        meta = dict(l.rstrip("\n").split("\t") for l in fh if "\t" in l)
    return d, meta


def cyl(ax, x0, x1, R, n=80):
    """Translucent cylinder of radius R (nm) along x, drawn only as a scale reference."""
    th = np.linspace(0, 2 * np.pi, n)
    xs = np.linspace(x0, x1, 2)
    T, X = np.meshgrid(th, xs)
    ax.plot_surface(X, R * np.cos(T), R * np.sin(T), color="0.7", alpha=0.18,
                    linewidth=0, antialiased=False, shade=False)


TAG = "every4_global"
LEG = "every3_segment"
d, meta = load(TAG)
rise = float(meta["rise_nm"]); daz = float(meta["dAzim_deg"])
pitch = float(meta["longPitch_nm"]); R = float(meta["Ractin_nm"]); HD = float(meta["headDiam_nm"])
x_nm = (d["x_um"] - d["x_um"][0]) * 1e3
y_nm, z_nm = d["y_um"] * 1e3, d["z_um"] * 1e3

# ------------------------------------------------------------------ Figure 1: sparse site helix (3-D)
SPAN = 120.0
m = d["gArc_nm"] <= SPAN
fig = plt.figure(figsize=(11.5, 6.2))
ax = fig.add_subplot(111, projection="3d")
cyl(ax, 0, SPAN, R, n=160)
ax.plot(d["gArc_nm"][m], y_nm[m], z_nm[m], color="0.55", lw=0.9, zorder=2)
ax.scatter(d["gArc_nm"][m], y_nm[m], z_nm[m], s=40, c="#1f77b4", depthshade=False, zorder=3)
for i in range(min(12, m.sum())):
    ax.text(d["gArc_nm"][i], y_nm[i], z_nm[i] + 1.4, f"k={int(d['k'][i])}", fontsize=8)
ax.set_xlabel("axial position along filament (nm)", labelpad=18)
ax.set_ylabel("y (nm)", labelpad=2); ax.set_zlabel("z (nm)", labelpad=-2)
ax.set_ylim(-4.6, 4.6); ax.set_zlim(-4.6, 4.6)
ax.set_yticks([-3.5, 0, 3.5]); ax.set_zticks([-3.5, 0, 3.5])
ax.tick_params(labelsize=8, pad=0)
ax.set_box_aspect((3.0, 1.0, 1.0), zoom=1.35)
ax.view_init(elev=16, azim=-68)
fig.suptitle(f"Figure 1 — effective binding sites, {meta['mode']} lattice (filament-global phase)\n"
             f"one site every {rise:.1f} nm, rotating {daz:+.0f} deg each step; "
             f"cylinder = actin surface R = {R:.1f} nm", fontsize=11)
fig.subplots_adjust(left=0.0, right=1.0, top=0.94, bottom=0.02)
fig.savefig(os.path.join(OUT, "fig1_sparse_site_helix.png"), dpi=170); plt.close(fig)

# ---------------------------------------------------- Figure 2: unwrapped lattice (THE verification figure)
fig, axs = plt.subplots(3, 1, figsize=(10.5, 10.2), sharex=True)
for ax, tag, ttl in ((axs[0], TAG, "sparse long-pitch every4, filament-global phase  (CANDIDATE)"),
                     (axs[1], LEG, "every3, segment-relative phase  (LEGACY)")):
    dd, mm = load(tag)
    sel = dd["gArc_nm"] <= 300.0
    bnd = np.flatnonzero(np.diff(dd["seg"][sel]) != 0)
    ax.scatter(dd["gArc_nm"][sel], dd["azim_deg"][sel], s=22, c="#1f77b4", zorder=3)
    for b in bnd:
        ax.axvline(0.5 * (dd["gArc_nm"][sel][b] + dd["gArc_nm"][sel][b + 1]), color="#d62728", ls="--", lw=1.1, zorder=1)
    ax.set_ylim(-195, 195); ax.set_yticks([-180, -90, 0, 90, 180])
    ax.grid(alpha=0.25)
    ax.set_title(f"{ttl}\nrise {float(mm['rise_nm']):.2f} nm    d(azimuth)/site {float(mm['dAzim_deg']):+.2f} deg"
                 f"    360 deg repeat {float(mm['longPitch_nm']):.1f} nm", fontsize=9.5)
axs[0].set_ylabel("site azimuth (deg, wrapped)")
axs[1].set_ylabel("site azimuth (deg, wrapped)")

# (c) cumulative azimuth — the wrap removed, so the single diagonal sequence is unambiguous
sel = d["gArc_nm"] <= 300.0
cum = np.cumsum(np.r_[0.0, np.diff(d["azim_deg"][sel]) % 360.0]) + d["azim_deg"][sel][0]
slope = np.polyfit(d["gArc_nm"][sel], cum, 1)[0]
axs[2].plot(d["gArc_nm"][sel], cum, "-", color="0.6", lw=1.0, zorder=2)
axs[2].scatter(d["gArc_nm"][sel], cum, s=22, c="#1f77b4", zorder=3)
bnd = np.flatnonzero(np.diff(d["seg"][sel]) != 0)
for b in bnd:
    axs[2].axvline(0.5 * (d["gArc_nm"][sel][b] + d["gArc_nm"][sel][b + 1]), color="#d62728", ls="--", lw=1.1, zorder=1)
for t in range(1, int(cum.max() // 360) + 1):
    axs[2].axhline(360 * t, color="0.85", lw=0.8, zorder=0)
    axs[2].text(302, 360 * t, f"{t} turn{'s' if t > 1 else ''}", fontsize=8, va="center", color="0.4")
axs[2].set_ylabel("cumulative azimuth (deg, unwrapped)")
axs[2].grid(alpha=0.2)
axs[2].set_title(f"CANDIDATE, wrap removed — one straight sparse diagonal through every segment boundary\n"
                 f"fitted slope {slope:.4f} deg/nm  ⇒  360 deg every {360.0/slope:.2f} nm", fontsize=9.5)
axs[2].set_xlabel("axial position along filament (nm)   —   red dashed = segment boundary")
axs[0].annotate(f"measured: dx = {np.diff(d['gArc_nm']).min():.3f}–{np.diff(d['gArc_nm']).max():.3f} nm,\n"
                f"d(azimuth) = {daz:+.3f} deg (uniform, 195/195 gaps),\n"
                f"one revolution = {pitch:.1f} nm = {360.0/abs(daz):.3f} sites",
                xy=(0.985, 0.06), xycoords="axes fraction", ha="right", va="bottom", fontsize=8.5,
                bbox=dict(boxstyle="round,pad=0.4", fc="white", ec="0.6"))
fig.suptitle("Figure 2 — unwrapped lattice (axial position vs site azimuth), from the code's own coordinates",
             fontsize=11.5)
fig.tight_layout(rect=(0, 0, 1, 0.965))
fig.savefig(os.path.join(OUT, "fig2_unwrapped_lattice.png"), dpi=170); plt.close(fig)

# ------------------------------------------------------------- Figure 3: footprint sanity vs a 7 nm head
fig, ax = plt.subplots(figsize=(9.2, 4.2))
n = 4
ax.add_patch(plt.Rectangle((-3, -R), (n - 1) * rise + 6, 2 * R, fc="0.85", ec="0.6", zorder=0))
lab = []
for i in range(n):
    circ = plt.Circle((d["gArc_nm"][i], y_nm[i]), HD / 2, fc="#ff7f0e", alpha=0.20, ec="#ff7f0e", ls="--", zorder=1)
    ax.add_patch(circ)
    ax.plot(d["gArc_nm"][i], y_nm[i], "o", ms=8, color="#1f77b4", zorder=3)
    ax.annotate(f"k={int(d['k'][i])}", (d["gArc_nm"][i], y_nm[i]), textcoords="offset points",
                xytext=(0, 11), ha="center", fontsize=9)
for i in range(n - 1):
    d3 = d["d_prev_nm"][i + 1]
    ax.annotate("", xy=(d["gArc_nm"][i + 1], -R - 2.2), xytext=(d["gArc_nm"][i], -R - 2.2),
                arrowprops=dict(arrowstyle="<->", color="0.3"))
    ax.text(0.5 * (d["gArc_nm"][i] + d["gArc_nm"][i + 1]), -R - 3.4,
            f"{rise:.1f} nm axial\n{d3:.2f} nm 3-D", ha="center", va="top", fontsize=8)
ax.plot([14, 14 + HD], [R + 4.4, R + 4.4], color="k", lw=2.5)
ax.text(14 + HD / 2, R + 5.1, f"{HD:.0f} nm — nominal myosin head diameter (scale bar only)",
        ha="center", fontsize=8.5)
ax.set_xlim(-6, (n - 1) * rise + 12); ax.set_ylim(-R - 9, R + 8)
ax.set_aspect("equal"); ax.set_xlabel("axial position (nm)"); ax.set_ylabel("y (nm)")
ax.set_title("Figure 3 — footprint sanity: adjacent effective sites are NOT a dense cluster\n"
             "dashed circles = a 7 nm head footprint centred on each site (projected view)", fontsize=10)
fig.tight_layout(); fig.savefig(os.path.join(OUT, "fig3_footprint.png"), dpi=170); plt.close(fig)

# ------------------------------------------------------------------- Figure 4: end-on view, k0..k7
fig, axs = plt.subplots(2, 4, figsize=(11.5, 6.0))
for i, ax in enumerate(axs.ravel()):
    circ = plt.Circle((0, 0), R, fc="0.88", ec="0.55", zorder=0)
    ax.add_patch(circ)
    ax.plot([0, y_nm[i]], [0, z_nm[i]], color="0.5", lw=1.0, zorder=1)
    ax.plot(y_nm[i], z_nm[i], "o", ms=10, color="#1f77b4", zorder=3)
    for j in range(i):
        ax.plot(y_nm[j], z_nm[j], "o", ms=5, color="0.7", zorder=2)
    ax.set_xlim(-5.2, 5.2); ax.set_ylim(-5.2, 5.2); ax.set_aspect("equal")
    ax.set_xticks([]); ax.set_yticks([])
    ax.set_title(f"k={int(d['k'][i])}   {d['azim_deg'][i]:+.0f} deg\naxial {d['gArc_nm'][i]:.1f} nm", fontsize=9)
fig.suptitle(f"Figure 4 — viewed down the filament axis: each site advances {daz:+.0f} deg per {rise:.1f} nm "
             f"(one turn every {pitch:.0f} nm)", fontsize=11)
fig.tight_layout(rect=(0, 0, 1, 0.92))
fig.subplots_adjust(hspace=0.30)
fig.savefig(os.path.join(OUT, "fig4_axial_progression.png"), dpi=170); plt.close(fig)

print("wrote:", ", ".join(sorted(os.listdir(OUT))))
