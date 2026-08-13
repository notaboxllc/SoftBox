#!/usr/bin/env python3
"""Figures for docs/motor/RESTORED_3D_HEAD_TILT_DOF.md sections 13-15 (the mechanics repair).

Parses the probe's own text output — no numbers are re-derived here, so a figure can never disagree
with the report.

    python3 scripts/plot_mechanics_repair.py
"""
import os, re
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

SRC = "RUN_LOGS/motor_audit/mechanics_repair/mechanics_repair_all.txt"
OUT = "docs/motor/figures/mechanics_repair"
os.makedirs(OUT, exist_ok=True)
TXT = open(SRC).read()

C = dict(rep="#2a9d8f", leg="#e76f51", fd="#264653", grid="#d8dee4")


def fnum(s):
    return float(s)


# ---------------------------------------------------------------- fig 1: the F8 axis (gate C)
rows, lines = [], TXT.splitlines()
for i, ln in enumerate(lines):
    m = re.match(r"\s{4}(\S.*?)\s{2,}([+-]\d\.\d+e[+-]\d+)\s+([+-]\d\.\d+e[+-]\d+)\s+"
                 r"([+-]\d\.\d+e[+-]\d+)\s+([+-]\d\.\d+e[+-]\d+)\s*$", ln)
    if m and i + 1 < len(lines):
        t = re.search(r"([+-]\d\.\d+e[+-]\d+)\s+([+-]\d\.\d+e[+-]\d+)\s+<- FD", lines[i + 1])
        if t:
            rows.append((m.group(1).strip(), fnum(m.group(2)), fnum(m.group(3)),
                         fnum(m.group(4)), fnum(m.group(5)), fnum(t.group(1)), fnum(t.group(2))))

if rows:
    lab = [r[0] for r in rows]
    x = np.arange(len(rows)); w = 0.2
    fig, axes = plt.subplots(1, 2, figsize=(12.5, 4.6))
    for k, (ax, ttl, ie, il, itr) in enumerate(
            [(axes[0], r"generalized force on $\varphi$", 1, 3, 5),
             (axes[1], r"generalized force on $\psi$", 2, 4, 6)]):
        ax.bar(x - w, [abs(r[ie]) for r in rows], w, label="REPAIRED  $\\hat e_{conv}$", color=C["rep"])
        ax.bar(x,     [abs(r[il]) for r in rows], w, label="legacy  $\\hat e_{up}$", color=C["leg"])
        ax.bar(x + w, [abs(r[itr]) for r in rows], w, label="FD ground truth", color=C["fd"], alpha=.55)
        ax.set_xticks(x); ax.set_xticklabels(lab, rotation=18, ha="right", fontsize=8)
        ax.set_ylabel("|Q|  (N·m)"); ax.set_title(ttl, fontsize=11)
        ax.grid(axis="y", color=C["grid"], lw=.6); ax.set_axisbelow(True)
        ax.legend(fontsize=8, frameon=False)
    fig.suptitle("F8 virtual-work axis — an in-plane bond force fed EXACTLY ZERO load into the converter\n"
                 "the legacy axis responded only to the one load the true geometry ignores", fontsize=11)
    fig.tight_layout()
    fig.savefig(f"{OUT}/fig1_f8_axis_gateC.png", dpi=150)
    print("wrote fig1_f8_axis_gateC.png")

# ---------------------------------------------------------------- fig 2: the lever joint (gates A + C)
gA = re.findall(r"^\s+(-?\d+\.\d)\s+(-?\d+\.\d+)\s+([+-]\d\.\d+e[+-]\d+)\s+\w+", TXT, re.M)
gC = re.findall(r"^\s+(\d+\.\d)\s+(\d+\.\d+)\s+([+-]\d+\.\d+)\s+\S", TXT, re.M)
if gA or gC:
    fig, axes = plt.subplots(1, 2, figsize=(12.5, 4.4))
    if gA:
        d = [float(a) for a, _, _ in gA]; U = [float(b) for _, b, _ in gA]
        g = [abs(float(c)) for _, _, c in gA]
        axes[0].plot(d, U, "o-", color=C["rep"], lw=2, label="$U_{lever}$ (kT)")
        axes[0].axhline(0, color=C["leg"], ls="--", lw=1.6, label="legacy free hinge (identically 0)")
        a2 = axes[0].twinx(); a2.plot(d, g, "s--", color=C["fd"], lw=1.4, ms=4, label="|dU/d$\\varphi$|")
        a2.set_ylabel("|dU/d$\\varphi$|  (N·m)")
        axes[0].set_xlabel("common rotation $\\delta$  ($\\varphi,\\psi)\\to(\\varphi+\\delta,\\psi+\\delta$)  [deg]")
        axes[0].set_ylabel("$U_{lever}$  (kT)")
        axes[0].set_title("GATE A — the zero-energy lever mode is gone", fontsize=11)
        axes[0].grid(color=C["grid"], lw=.6); axes[0].set_axisbelow(True)
        axes[0].legend(fontsize=8, frameon=False, loc="upper left")
    if gC:
        b = [float(a) for a, _, _ in gC]; dp = [float(c) for _, _, c in gC]
        axes[1].plot(b, dp, "o-", color=C["rep"], lw=2, label="measured $\\Delta\\varphi_{min}$")
        axes[1].plot(b, b, ":", color=C["fd"], lw=1.4, label="1:1 (perfect moment continuity)")
        axes[1].set_xlabel("distal S2 bend  [deg]"); axes[1].set_ylabel("shift of the lever's rest angle  [deg]")
        axes[1].set_title("GATE C — bending the S2 reorients what it carries", fontsize=11)
        axes[1].grid(color=C["grid"], lw=.6); axes[1].set_axisbelow(True)
        axes[1].legend(fontsize=8, frameon=False)
    fig.suptitle("S2 → lever moment transfer — the beam's OWN kbend (EI/l0); no fitted stiffness", fontsize=11)
    fig.tight_layout()
    fig.savefig(f"{OUT}/fig2_lever_joint.png", dpi=150)
    print("wrote fig2_lever_joint.png")

# ---------------------------------------------------------------- fig 3: the corrected baseline
#  Parsed from the VALIDATED Phase-11 estimator (LiveNeckHeadProbe -reg), lever joint ON and OFF.
#  Rows are identified by CONFIGURATION, not by the probe's now-stale arm labels:
#     "legacy"  arm sets HEAD_TILT_AXIS_FIX = true  -> econv
#     "liveLeg" arm sets HEAD_TILT_AXIS_FIX = false -> eup (the pre-repair axis)
def phase11(path):
    out = {}
    try:
        t = open(path).read()
    except FileNotFoundError:
        return out
    for nm, st, pol, ke, th in re.findall(
            r"^\s{2}(legacy|liveLeg)\s+(-?\d+\.\d+)\s+(\w+)\s+(-?\d+\.\d+)\s+(-?\d+\.\d+)", t, re.M):
        out["econv" if nm == "legacy" else "eup"] = (float(st), pol, float(ke), float(th))
    return out

ON, OFF = phase11("RUN_LOGS/motor_audit/mechanics_repair/reg_lever_ON.txt"), \
          phase11("RUN_LOGS/motor_audit/mechanics_repair/reg_lever_OFF.txt")
cells = [("eup, no joint\n(pre-repair)", OFF.get("eup")),
         ("econv, no joint\n(fix 1 only)", OFF.get("econv")),
         ("eup + joint\n(fix 2 only)", ON.get("eup")),
         ("econv + joint\n(REPAIRED)", ON.get("econv"))]
cells = [(a, b) for a, b in cells if b]
if cells:
    lab = [c[0] for c in cells]
    stroke = [c[1][0] for c in cells]
    kext = [c[1][2] for c in cells]
    x = np.arange(len(cells))
    col = [C["leg"], "#e9c46a", "#8ab17d", C["rep"]][:len(cells)]
    fig, axes = plt.subplots(1, 2, figsize=(12.5, 4.6))
    axes[0].bar(x, stroke, .55, color=col)
    axes[0].set_ylabel("stroke  (nm along $\\hat b$)")
    axes[0].set_title("axial stroke — negative = pointed-first (correct polarity in every arm)", fontsize=10)
    axes[1].bar(x, kext, .55, color=col)
    axes[1].axhspan(0.60, 0.64, color=C["fd"], alpha=.16,
                    label="Cmot fixed-anchor reference 0.60–0.64\n(independent, always econv; NOT tuned to)")
    axes[1].set_ylabel("$k_{ext}$  (pN/nm)")
    axes[1].set_title("whole-cross-bridge stiffness", fontsize=10)
    axes[1].legend(fontsize=8, frameon=False)
    for ax in axes:
        ax.set_xticks(x); ax.set_xticklabels(lab, fontsize=8)
        ax.grid(axis="y", color=C["grid"], lw=.6); ax.set_axisbelow(True)
    fig.suptitle("Corrected core-motor baseline (validated Phase-11 estimator) — reported as measured", fontsize=11)
    fig.tight_layout()
    fig.savefig(f"{OUT}/fig3_corrected_baseline.png", dpi=150)
    print("wrote fig3_corrected_baseline.png")

print("figures ->", OUT)
