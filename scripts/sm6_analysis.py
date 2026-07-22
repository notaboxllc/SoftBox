#!/usr/bin/env python3
"""
SM6 force-extension analysis: tangent stiffness, tension/compression asymmetry, buckling onset,
hysteresis, energy partition, and the single-head vs dimer comparisons.

    python3 scripts/sm6_analysis.py RUN_LOGS/motor_validation/sm6_force_extension

Writes tangent_stiffness.csv, plots/*.png, ANALYSIS.md.
"""
import sys, os, csv, math, json
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

OUT = sys.argv[1] if len(sys.argv) > 1 else "RUN_LOGS/motor_validation/sm6_force_extension"
KT = 4.141947e-21


def load(path):
    if not os.path.exists(path):
        return []
    return list(csv.DictReader(open(path)))


def fnum(r, k, d=float("nan")):
    try:
        return float(r[k])
    except (KeyError, ValueError, TypeError):
        return d


def tangent(d, F):
    """Central-difference tangent stiffness k = -dF/dd (pN/nm), restoring-positive."""
    d = np.asarray(d, float); F = np.asarray(F, float)
    k = np.full(d.size, np.nan)
    if d.size >= 3:
        k[1:-1] = -(F[2:] - F[:-2]) / (d[2:] - d[:-2])
    return k


def smallsig(d, F, win=1.0):
    d = np.asarray(d, float); F = np.asarray(F, float)
    m = np.abs(d) <= win
    if m.sum() < 3:
        return float("nan")
    return float(-np.polyfit(d[m], F[m], 1)[0])


def main():
    sh = load(os.path.join(OUT, "force_extension_singlehead.csv"))
    dm = load(os.path.join(OUT, "force_extension_dimer.csv"))
    os.makedirs(os.path.join(OUT, "plots"), exist_ok=True)
    md = ["# SM6 — quasistatic force-extension: analysis\n"]
    tan_rows = []

    if sh:
        rev = sh[0].get("code_rev", "?"); bid = sh[0].get("build_id", "?")
        md.append(f"- code revision `{rev}` · build `{bid}` · CPU-only · Brownian OFF · chemistry FROZEN")
        md.append("- sign: **+d = toward BARBED (tension, the SM4 'opposing' sense); "
                  "−d = COMPRESSION (the SM4 'assisting' sense)**\n")

        md.append("## A. Single-head axial force-extension\n")
        md.append("| fixture | state | k(0) pN/nm | F(+20nm) pN | F(−20nm) pN | buckling onset (nm) | max bend (deg) |")
        md.append("|---|---|---:|---:|---:|---:|---:|")
        curves = {}
        for fx in sorted({r["fixture"] for r in sh}):
            for st in sorted({r["state"] for r in sh if r["fixture"] == fx}):
                r = [x for x in sh if x["fixture"] == fx and x["state"] == st
                     and x["axis"] == "axial" and x["mode"] == "fresh"]
                if not r:
                    continue
                r.sort(key=lambda x: fnum(x, "disp_nm"))
                d = np.array([fnum(x, "disp_nm") for x in r])
                F = np.array([fnum(x, "force_pn") for x in r])
                bend = np.array([fnum(x, "bend_deg") for x in r])
                k0 = smallsig(d, F)
                kt = tangent(d, F)
                curves[(fx, st)] = (d, F, kt, bend)
                # buckling onset: first (most negative-going) point where bend exceeds 5 deg
                onset = float("nan")
                bm = np.where(bend > 5.0)[0]
                if bm.size:
                    onset = float(d[bm[np.argmax(d[bm])]])   # least-compressed buckled point
                fp = F[np.argmin(np.abs(d - 20))] if d.size else float("nan")
                fm = F[np.argmin(np.abs(d + 20))] if d.size else float("nan")
                md.append(f"| {fx} | {st} | {k0:.4f} | {fp:.3f} | {fm:.3f} | "
                          f"{'%.2f' % onset if np.isfinite(onset) else 'none'} | {bend.max():.2f} |")
                for i in range(len(d)):
                    tan_rows.append(dict(fixture=fx, state=st, axis="axial", disp_nm=d[i],
                                         force_pn=F[i], tangent_pn_per_nm=kt[i], bend_deg=bend[i]))
        md.append("")

        # transverse
        md.append("## B. Transverse stiffness (the load-path anisotropy)\n")
        md.append("| fixture | axis | k(0) pN/nm |")
        md.append("|---|---|---:|")
        aniso = {}
        for fx in sorted({r["fixture"] for r in sh}):
            for ax in ["axial", "transverse_econv", "transverse_eup"]:
                r = [x for x in sh if x["fixture"] == fx and x["state"] == "adp"
                     and x["axis"] == ax and x["mode"] == "fresh"]
                if not r:
                    continue
                r.sort(key=lambda x: fnum(x, "disp_nm"))
                d = np.array([fnum(x, "disp_nm") for x in r]); F = np.array([fnum(x, "force_pn") for x in r])
                k0 = smallsig(d, F)
                aniso.setdefault(fx, {})[ax] = k0
                md.append(f"| {fx} | {ax} | {k0:.4f} |")
                if ax != "axial":
                    for i in range(len(d)):
                        tan_rows.append(dict(fixture=fx, state="adp", axis=ax, disp_nm=d[i],
                                             force_pn=F[i], tangent_pn_per_nm=tangent(d, F)[i],
                                             bend_deg=float("nan")))
        md.append("")
        md.append("| fixture | axial/transverse anisotropy |")
        md.append("|---|---:|")
        for fx, a in aniso.items():
            tr = [v for k, v in a.items() if k.startswith("transverse")]
            if tr and a.get("axial"):
                md.append(f"| {fx} | {a['axial'] / np.mean(tr):.2f}x |")
        md.append("")

        # hysteresis
        cyc = [x for x in sh if x["mode"] == "cycle"]
        if cyc:
            md.append("## C. Hysteresis and recovery (0 → +20 → −20 → 0)\n")
            md.append("| fixture | max |F| gap between branches (pN) | residual force at return-to-0 (pN) |")
            md.append("|---|---:|---:|")
            for fx in sorted({x["fixture"] for x in cyc}):
                r = [x for x in cyc if x["fixture"] == fx]
                r.sort(key=lambda x: int(x["sweep_index"]))
                d = np.array([fnum(x, "disp_nm") for x in r]); F = np.array([fnum(x, "force_pn") for x in r])
                # compare the down-branch to the up-branch on the shared +d range
                n = len(d)
                up = [(d[i], F[i]) for i in range(n) if i <= np.argmax(d)]
                dn = [(d[i], F[i]) for i in range(n) if i > np.argmax(d) and d[i] >= 0]
                gap = float("nan")
                if up and dn:
                    ud = np.array([p[0] for p in up]); uf = np.array([p[1] for p in up])
                    gg = [abs(f - np.interp(x, ud, uf)) for x, f in dn]
                    gap = float(np.max(gg)) if gg else float("nan")
                resid = float(F[-1])
                md.append(f"| {fx} | {gap:.4g} | {resid:.4g} |")
            md.append("")

        # plots
        plt.figure(figsize=(11, 4.2))
        plt.subplot(1, 2, 1)
        for (fx, st), (d, F, kt, bend) in curves.items():
            if st != "adp":
                continue
            plt.plot(d, F, lw=1.6, label=fx)
        plt.axvline(0, color="0.7", lw=0.8); plt.axhline(0, color="0.7", lw=0.8)
        plt.xlabel("imposed displacement (nm)   [+ = tension]"); plt.ylabel("reaction force on filament (pN)")
        plt.title("Single-head axial force-extension (ADP)"); plt.legend(fontsize=7); plt.grid(alpha=.3)
        plt.subplot(1, 2, 2)
        for (fx, st), (d, F, kt, bend) in curves.items():
            if st != "adp":
                continue
            plt.plot(d, bend, lw=1.6, label=fx)
        plt.xlabel("imposed displacement (nm)"); plt.ylabel("total beam bend (deg)")
        plt.title("Buckling: beam bending vs displacement"); plt.legend(fontsize=7); plt.grid(alpha=.3)
        plt.tight_layout(); plt.savefig(os.path.join(OUT, "plots", "singlehead_axial.png"), dpi=140)
        plt.close()

    if dm:
        md.append("## D. Dimer force-extension\n")
        md.append("Displacement is imposed on the ACTIN ANCHOR, so every dimer curve includes the F8 "
                  "spring (1 pN/nm) in series. Stiffnesses below are therefore SERIES stiffnesses.\n")
        md.append("| binding | mode | sep (nm) | axis | k(0) pN/nm | max fork (deg) | max joint gap (nm) | solver fails |")
        md.append("|---|---|---:|---|---:|---:|---:|---:|")
        for key in sorted({(r["binding"], r["mode"], fnum(r, "sep_nm"), r["axis"]) for r in dm
                           if r["state"] == "adp"}):
            b, mo, sep, ax = key
            r = [x for x in dm if x["binding"] == b and x["mode"] == mo
                 and fnum(x, "sep_nm") == sep and x["axis"] == ax and x["state"] == "adp"]
            if not r:
                continue
            r.sort(key=lambda x: fnum(x, "disp_nm"))
            d = np.array([fnum(x, "disp_nm") for x in r])
            F = np.array([fnum(x, "force_axial_pn") for x in r])
            k0 = smallsig(d, F)
            fk = max(fnum(x, "fork_deg") for x in r)
            jg = max(fnum(x, "joint_gap_nm") for x in r)
            sf = sum(int(fnum(x, "solver_failures", 0)) for x in r)
            md.append(f"| {b} | {mo} | {sep:.2f} | {ax} | {k0:.4f} | {fk:.2f} | {jg:.3f} | {sf} |")
            for i in range(len(d)):
                tan_rows.append(dict(fixture=f"dimer:{b}:{mo}:sep{sep:g}", state="adp", axis=ax,
                                     disp_nm=d[i], force_pn=F[i],
                                     tangent_pn_per_nm=tangent(d, F)[i], bend_deg=float("nan")))
        md.append("")

        # head-label exchange symmetry
        a = [x for x in dm if x["binding"] == "two-head" and x["mode"] == "asym" and fnum(x, "sep_nm") == 5.5
             and x["axis"] == "axial"]
        b = [x for x in dm if x["binding"] == "two-head-swapped" and x["mode"] == "asymB"
             and x["axis"] == "axial"]
        if a and b:
            a.sort(key=lambda x: fnum(x, "disp_nm")); b.sort(key=lambda x: fnum(x, "disp_nm"))
            fa = np.array([fnum(x, "force_axial_pn") for x in a])
            fb = np.array([fnum(x, "force_axial_pn") for x in b])
            n = min(fa.size, fb.size)
            dev = float(np.max(np.abs(fa[:n] - fb[:n])))
            rel = dev / max(1e-12, float(np.max(np.abs(fa[:n]))))
            md.append("### Head-label exchange symmetry\n")
            md.append(f"Loading head A only vs head B only (sep 5.5 nm, axial): max |ΔF| = **{dev:.4g} pN** "
                      f"({100*rel:.2f}% of peak). The dimer is built with a directional fork rest angle "
                      f"(+α on branch A, −α on branch B), so exact label symmetry is NOT expected; this "
                      f"quantifies the built-in asymmetry.\n")

        plt.figure(figsize=(11, 4.2))
        plt.subplot(1, 2, 1)
        for sep in sorted({fnum(r, "sep_nm") for r in dm if r["binding"] == "two-head"}):
            r = [x for x in dm if x["binding"] == "two-head" and x["mode"] == "sym"
                 and fnum(x, "sep_nm") == sep and x["axis"] == "axial"]
            r.sort(key=lambda x: fnum(x, "disp_nm"))
            if r:
                plt.plot([fnum(x, "disp_nm") for x in r], [fnum(x, "force_axial_pn") for x in r],
                         lw=1.4, label=f"sep {sep:g} nm")
        r1 = [x for x in dm if x["binding"] == "one-head" and x["axis"] == "axial" and x["state"] == "adp"]
        r1.sort(key=lambda x: fnum(x, "disp_nm"))
        if r1:
            plt.plot([fnum(x, "disp_nm") for x in r1], [fnum(x, "force_axial_pn") for x in r1],
                     "k--", lw=1.8, label="one-head")
        plt.xlabel("anchor displacement (nm)"); plt.ylabel("axial force (pN)")
        plt.title("Dimer: two-head (symmetric) vs one-head"); plt.legend(fontsize=7); plt.grid(alpha=.3)
        plt.subplot(1, 2, 2)
        for sep in sorted({fnum(r, "sep_nm") for r in dm if r["binding"] == "two-head"}):
            r = [x for x in dm if x["binding"] == "two-head" and x["mode"] == "sym"
                 and fnum(x, "sep_nm") == sep and x["axis"] == "axial"]
            r.sort(key=lambda x: fnum(x, "disp_nm"))
            if r:
                plt.plot([fnum(x, "disp_nm") for x in r],
                         [fnum(x, "u_total_kT") for x in r], lw=1.4, label=f"sep {sep:g} nm")
        plt.xlabel("anchor displacement (nm)"); plt.ylabel("stored elastic energy (kT)")
        plt.title("Dimer stored energy"); plt.legend(fontsize=7); plt.grid(alpha=.3)
        plt.tight_layout(); plt.savefig(os.path.join(OUT, "plots", "dimer_axial.png"), dpi=140)
        plt.close()

    # energy partition
    ce = load(os.path.join(OUT, "component_energy.csv"))
    if ce:
        md.append("## E. Energy partition (single head, ADP, axial)\n")
        md.append("| fixture | d=+15 nm: F8 / conv / bind / stretch / bend (kT) | d=−15 nm (kT) |")
        md.append("|---|---|---|")
        for fx in sorted({r["fixture"] for r in ce}):
            def at(dv):
                r = [x for x in ce if x["fixture"] == fx and x["state"] == "adp"
                     and x["axis"] == "axial" and abs(fnum(x, "disp_nm") - dv) < 0.26]
                if not r:
                    return "-"
                x = r[0]
                return " / ".join(f"{fnum(x, k)/KT:.2f}" for k in
                                  ["u_f8_J", "u_conv_J", "u_bind_J", "u_stretch_J", "u_bend_J"])
            md.append(f"| {fx} | {at(15)} | {at(-15)} |")
        md.append("")

    if tan_rows:
        keys = ["fixture", "state", "axis", "disp_nm", "force_pn", "tangent_pn_per_nm", "bend_deg"]
        with open(os.path.join(OUT, "tangent_stiffness.csv"), "w", newline="") as fh:
            w = csv.DictWriter(fh, fieldnames=keys, extrasaction="ignore")
            w.writeheader()
            for r in tan_rows:
                w.writerow(r)

    open(os.path.join(OUT, "ANALYSIS.md"), "w").write("\n".join(md) + "\n")
    print(f"wrote tangent_stiffness.csv ({len(tan_rows)} rows), plots/, ANALYSIS.md")


if __name__ == "__main__":
    raise SystemExit(main())
