#!/usr/bin/env python3
"""Experiment 4G — MD-informed explicit fixed-contour S2: summary figure from the CSV artifacts.

Usage: python3 scripts/twobody4g_analyze.py RUN_LOGS/twobody_md_informed_s2/csv [out.png]
Reads decoupling.csv / geometry.csv / capture_volume.csv and renders a 4-panel summary:
  (1) delivered stroke % and k_ext % vs free-S2 length L (the DECOUPLING);
  (2) capture footprint area vs L (the SEARCH gain);
  (3) emergent effective stiffness vs L — transverse (bending, soft) vs axial tension vs compression;
  (4) contour vs end-to-end under the buckling probe (the fixed-contour / bending-to-tension geometry).
The figure is a convenience; the numbers of record live in docs/TWOBODY_MD_INFORMED_S2.md.
"""
import sys, os, csv
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt


def load(path):
    if not os.path.exists(path):
        return []
    with open(path) as f:
        return list(csv.DictReader(f))


def fnum(x):
    try:
        return float(x)
    except (ValueError, TypeError):
        return float("nan")


def main():
    d = sys.argv[1] if len(sys.argv) > 1 else "RUN_LOGS/twobody_md_informed_s2/csv"
    out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(d, "exp4g_summary.png")
    dec = load(os.path.join(d, "decoupling.csv"))
    geo = load(os.path.join(d, "geometry.csv"))

    fig, ax = plt.subplots(2, 2, figsize=(12, 9))

    # (1) decoupling: stroke% / kext% vs L (explicit-S2 rows only)
    s2 = [r for r in dec if r.get("condition", "").startswith("S2_")]
    L = [fnum(r["Lnm"]) for r in s2]
    ax[0, 0].plot(L, [100 * fnum(r["strokeFrac"]) for r in s2], "o-", label="delivered stroke %")
    ax[0, 0].plot(L, [100 * fnum(r["kextFrac"]) for r in s2], "s-", label="k_ext %")
    ax[0, 0].axhline(70, ls=":", c="gray"); ax[0, 0].axhline(100, ls="--", c="k", lw=0.6)
    ax[0, 0].set_xlabel("free S2 length L (nm)"); ax[0, 0].set_ylabel("% of fixed-anchor")
    ax[0, 0].set_title("Load transmission vs L (decoupling)"); ax[0, 0].legend(); ax[0, 0].grid(alpha=0.3)

    # (2) capture area vs L
    ax[0, 1].plot(L, [fnum(r["captureArea_nm2"]) for r in s2], "o-", c="tab:green")
    ax[0, 1].set_xlabel("free S2 length L (nm)"); ax[0, 1].set_ylabel("capture footprint (nm²)")
    ax[0, 1].set_title("Search gain vs L"); ax[0, 1].grid(alpha=0.3)

    # (3) emergent stiffnesses vs L (geometry.csv)
    if geo:
        gL = [fnum(r["Lnm"]) for r in geo]
        ax[1, 0].plot(gL, [fnum(r["kTrans_pNnm"]) for r in geo], "o-", label="transverse (bending)")
        ax[1, 0].plot(gL, [fnum(r["kTens_pNnm"]) for r in geo], "s-", label="axial tension")
        ax[1, 0].plot(gL, [fnum(r["kComp_pNnm"]) for r in geo], "^-", label="axial compression")
        ax[1, 0].set_yscale("log"); ax[1, 0].set_xlabel("free S2 length L (nm)")
        ax[1, 0].set_ylabel("effective stiffness (pN/nm)")
        ax[1, 0].set_title("Emergent anisotropy + tension/compression asymmetry")
        ax[1, 0].legend(); ax[1, 0].grid(alpha=0.3, which="both")

        # (4) contour vs end-to-end under buckle
        ax[1, 1].plot(gL, [fnum(r["contour_rest_nm"]) for r in geo], "o-", label="contour (rest)")
        ax[1, 1].plot(gL, [fnum(r["trans20_contour_nm"]) for r in geo], "s--", label="contour (buckled)")
        ax[1, 1].plot(gL, [fnum(r["trans20_e2e_nm"]) for r in geo], "^-", label="end-to-end (buckled)")
        ax[1, 1].set_xlabel("free S2 length L (nm)"); ax[1, 1].set_ylabel("length (nm)")
        ax[1, 1].set_title("Fixed contour vs end-to-end (bending shortens end-to-end)")
        ax[1, 1].legend(); ax[1, 1].grid(alpha=0.3)

    fig.suptitle("Experiment 4G — MD-informed explicit fixed-contour S2 (non-canonical, CPU)", fontsize=13)
    fig.tight_layout(rect=[0, 0, 1, 0.97])
    fig.savefig(out, dpi=110)
    print("wrote", out)


if __name__ == "__main__":
    main()
