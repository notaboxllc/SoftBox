#!/usr/bin/env python3
"""Schematic of the config-1 motor bound-state geometry (uncocked vs cocked) in the x-z plane (the swing plane),
with the filament axis and the lever-load-end displacement. From bound_geometry.csv (units µm -> nm)."""
import csv, numpy as np
import matplotlib; matplotlib.use("Agg")
import matplotlib.pyplot as plt

G = {}
for r in csv.reader(open("bound_geometry.csv")):
    if r[0].startswith("#"): continue
    body, state = r[0], r[1]
    e1 = np.array([float(r[2]), float(r[3]), float(r[4])]) * 1e3   # µm -> nm
    e2 = np.array([float(r[5]), float(r[6]), float(r[7])]) * 1e3
    G[(body, state)] = (e1, e2)

def seg(ax, body, state, color, lw, label=None, z=False):
    e1, e2 = G[(body, state)]
    ax.plot([e1[0], e2[0]], [e1[2], e2[2]], '-', color=color, lw=lw, label=label, solid_capstyle='round')

fig, (ax, axz) = plt.subplots(1, 2, figsize=(13, 6))

# ---- Panel 1: full motor, x-z side view ----
fil1, fil2 = G[("filament", "both")]
ax.plot([fil1[0], fil2[0]], [0, 0], '-', color='black', lw=6, alpha=0.5, solid_capstyle='round', label='actin filament (x̂ axis)')
anc = G[("anchor", "both")][0]
ax.plot(anc[0], anc[2], 's', color='saddlebrown', ms=14, label='surface anchor (tail)')
for body, c, lw in [("rod", '#7fb3d5', 6), ("lever", '#5dade2', 7), ("head", '#2874a6', 9)]:
    seg(ax, body, "uncocked", c, lw, label=("uncocked (J1 0°)" if body=="rod" else None))
for body, c, lw in [("rod", '#f1948a', 6), ("lever", '#ec7063', 7), ("head", '#cb4335', 9)]:
    seg(ax, body, "cocked", c, lw, label=("cocked (J1 60°)" if body=="rod" else None))
# lever load-end displacement arrow (the axial sweep -> goes to the anchor side)
lu = G[("lever","uncocked")][0]; lc = G[("lever","cocked")][0]
ax.annotate("", xy=(lc[0], lc[2]), xytext=(lu[0], lu[2]),
            arrowprops=dict(arrowstyle='-|>', color='green', lw=2.5))
ax.text(lc[0]-1, lc[2]-9, f"lever LOAD end\nΔx={lc[0]-lu[0]:+.1f} nm (axial)\nΔz={lc[2]-lu[2]:+.1f} nm\n→ to the ANCHOR, not actin",
        color='green', fontsize=9, ha='center')
# head tip (site A) and rear pin (site B = J1 pivot)
ht = G[("head","cocked")][1]; hr = G[("head","cocked")][0]
ax.plot(ht[0], ht[2], 'o', color='gold', ms=11, mec='k', label='head tip = site A (pin)')
ax.plot(hr[0], hr[2], 'o', color='orange', ms=11, mec='k', label='rear = site B = J1 pivot (pin)')
ax.text(0, 6, "head two-point-pinned on actin (∠x̂≈0°, Δtip≈0):\nCANNOT slide along the filament", ha='center', fontsize=9, color='#b9770e')
ax.text(-15, -45, "lever swings 20°→62°\nin the x–z plane\n(plane CONTAINS x̂)", fontsize=9, color='#7d3c98')
ax.set_xlabel("x  (nm)  — filament / transport axis"); ax.set_ylabel("z  (nm)  — surface normal")
ax.set_title("Config-1 motor bound pose, x–z swing plane\n(swing plane contains x̂, yet the head can't transmit axial force)")
ax.legend(loc='lower right', fontsize=7.5); ax.grid(alpha=0.3); ax.set_aspect('equal'); ax.set_ylim(-95, 18)

# ---- Panel 2: zoom on the head + pins (why the axial swing doesn't reach actin) ----
axz.plot([fil1[0], fil2[0]], [0, 0], '-', color='black', lw=8, alpha=0.4, solid_capstyle='round')
for st, c in [("uncocked", '#2874a6'), ("cocked", '#cb4335')]:
    seg(axz, "head", st, c, 11, label=f'head {st}')
    seg(axz, "lever", st, c, 5)
for st, c in [("uncocked", '#2874a6'), ("cocked", '#cb4335')]:
    e1, e2 = G[("head", st)]
    axz.plot(e2[0], e2[2], 'o', color='gold', ms=12, mec=c, mew=2)
    axz.plot(e1[0], e1[2], 'o', color='orange', ms=12, mec=c, mew=2)
axz.axhline(0, color='gray', lw=0.5)
axz.text(0, 2.2, "site A (tip)", ha='center', fontsize=9, color='#b9770e')
axz.text(-10, 2.2, "site B (rear / J1 pivot)", ha='center', fontsize=9, color='#b9770e')
axz.annotate("J1 torque axis tv = lever×head ≈ +ŷ (out of page)\n⇒ reacted by the x-separated pins as a ±z (transverse) couple\n⇒ BENDS actin, no axial push",
             xy=(0, -1.5), xytext=(-9, -10), fontsize=8.5, color='#7d3c98',
             arrowprops=dict(arrowstyle='-|>', color='#7d3c98', lw=1.5))
axz.set_xlabel("x (nm)"); axz.set_ylabel("z (nm)")
axz.set_title("Zoom: the two-point head pin\n(head fixed along x̂; converter torque → transverse z-force on actin)")
axz.legend(loc='lower left', fontsize=8); axz.grid(alpha=0.3); axz.set_aspect('equal')
axz.set_xlim(-14, 14); axz.set_ylim(-12, 5)

fig.suptitle("Why the config-1 powerstroke bends actin instead of transporting it — the two-point head pin decouples the axial swing", fontsize=12)
fig.tight_layout(rect=[0,0,1,0.96])
fig.savefig("bound_geometry_schematic.png", dpi=130)
print("wrote bound_geometry_schematic.png")
