#!/usr/bin/env python3
"""
Matched-duration comparison + publication plots for the explicit SINGLE-HEAD long-run density sweep:
  D. new 0.1 s single-head  vs  OLD single-head (Phase-C explicit-s2-l40, equilibrated)
  E. matched RAW-speed: single-head (heads/µm²) + HMM dimer (dimers/µm²), NO 2× factor  [+ supplemental head-equivalent]
  F. Uyeda-style normalized (v/vmax) comparison, with documented interaction-band-width convention.

Sources (all gitignored RUN_LOGS):
  NEW single-head : RUN_LOGS/single_head_density_sweep_long/density_summary.csv
  OLD single-head : RUN_LOGS/explicit_completemat/COMPLETEMAT_SWEEP.csv   (model=explicit-s2-l40)
  HMM dimer (0.1s): RUN_LOGS/hmm_density_sweep_long/density_summary.csv

Writes plots to <newdir>/plots/ and a COMPARISON.md to <newdir>/.
  python3 scripts/single_head_dimer_compare.py
"""
import os, csv, math
import numpy as np
from scipy.optimize import curve_fit
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

NEW = "RUN_LOGS/single_head_density_sweep_long"
OLD = "RUN_LOGS/explicit_completemat/COMPLETEMAT_SWEEP.csv"
DIM = "RUN_LOGS/hmm_density_sweep_long/density_summary.csv"
PLOTS = os.path.join(NEW, "plots"); os.makedirs(PLOTS, exist_ok=True)

# Okabe-Ito colorblind-safe palette
C_SH = "#0072B2"    # single-head (blue)
C_DIM = "#D55E00"   # dimer (vermillion)
C_OLD = "#009E73"   # old single-head (bluish green)
C_GREY = "#999999"
BAND = "#E69F0022"  # Uyeda band fill (orange, translucent)

W_BAND_UM = 0.30    # interaction band width = engageable y-strip |ay|<0.15 µm (shared mat convention; buildMat/buildGlide2D)

def hyperbolic(rho, vmax, rhalf): return vmax * rho / (rhalf + rho)
def hill(rho, vmax, rhalf, n): return vmax * rho**n / (rhalf**n + rho**n)

def fit(fn, x, y, p0, bounds):
    try:
        popt, pcov = curve_fit(fn, x, y, p0=p0, bounds=bounds, maxfev=200000)
        yhat = fn(np.asarray(x), *popt)
        ss_res = float(np.sum((np.asarray(y)-yhat)**2)); ss_tot = float(np.sum((np.asarray(y)-np.mean(y))**2))
        r2 = 1 - ss_res/ss_tot if ss_tot > 0 else float("nan")
        return popt, np.sqrt(np.diag(pcov)), r2
    except Exception as e:
        return None, None, str(e)

def load_new():
    rows = list(csv.DictReader(open(os.path.join(NEW, "density_summary.csv"))))
    d = np.array([float(r["density"]) for r in rows]); v = np.array([float(r["velProd_mean"]) for r in rows])
    se = np.array([float(r["SEM"]) for r in rows]); n = np.array([int(r["n"]) for r in rows])
    bh = np.array([float(r["boundHeads"]) for r in rows]); pe = np.array([float(r["velPostEquil_mean"]) for r in rows])
    life = np.array([float(r["lifetime_ms"]) for r in rows]); atp = np.array([float(r["ATPturn"]) for r in rows])
    o = np.argsort(d)
    return dict(d=d[o], v=v[o], se=se[o], n=n[o], bh=bh[o], pe=pe[o], life=life[o], atp=atp[o])

def load_dimer():
    rows = list(csv.DictReader(open(DIM)))
    d = np.array([float(r["density"]) for r in rows]); v = np.array([float(r["velProd_mean"]) for r in rows])
    se = np.array([float(r["SEM"]) for r in rows]); bh = np.array([float(r["boundHeads"]) for r in rows])
    o = np.argsort(d)
    return dict(d=d[o], v=v[o], se=se[o], bh=bh[o])

def load_old_singlehead():
    d, v, se, bh = [], [], [], []
    for r in csv.DictReader(open(OLD)):
        if r["model"] != "explicit-s2-l40": continue
        d.append(float(r["density"])); v.append(abs(float(r["vel_umPerS"]))); se.append(float(r["vel_se"])); bh.append(float(r["avgBound"]))
    d, v, se, bh = map(np.array, (d, v, se, bh)); o = np.argsort(d)
    return dict(d=d[o], v=v[o], se=se[o], bh=bh[o])

def style(ax):
    ax.grid(True, alpha=0.25, lw=0.6); ax.set_axisbelow(True)
    for s in ("top", "right"): ax.spines[s].set_visible(False)

def main():
    L = []; w = L.append
    sh = load_new(); dim = load_dimer(); old = load_old_singlehead()
    if len(sh["d"]) < 2:
        print("Not enough NEW single-head densities yet (need >=2). Have:", list(sh["d"]));
    # fits (need >=3 densities for hyperbolic, >=4 for Hill)
    def dofit(dd):
        if len(dd["d"]) < 3: return None, None, None, None
        ph, peh, r2h = fit(hyperbolic, dd["d"], dd["v"], [max(dd["v"].max(),0.1), 300], ([0,1],[50,1e5]))
        if len(dd["d"]) < 4:
            return ph, r2h, None, None
        pH, peH, r2H = fit(hill, dd["d"], dd["v"], [max(dd["v"].max(),0.1), 300, 1.0], ([0,1,0.2],[50,1e5,8]))
        return ph, r2h, pH, r2H
    sh_h, sh_r2h, sh_H, sh_r2H = dofit(sh)
    dm_h, dm_r2h, dm_H, dm_r2H = dofit(dim)
    ol_h, ol_r2h, ol_H, ol_r2H = dofit(old)

    w("# Single-head (explicit-s2-l40) long-run density sweep — matched comparison (D / E / F)")
    w("")
    w(f"- NEW single-head: 0.1 s (40000 steps) whole-window, {len(sh['d'])} densities, n up to {int(sh['n'].max()) if len(sh['n']) else 0}/density — `{NEW}`")
    w(f"- OLD single-head (Phase-C explicit-s2-l40): equilibrated (equil 20000 / meas 60000 = 0.15 s window), 3 seeds — `{OLD}`")
    w(f"- HMM dimer (0.1 s long-run): whole-window, `{DIM}`")
    w("")
    def fitline(name, ph, r2h, pH, r2H):
        if ph is None: return f"- {name}: hyperbolic fit FAILED"
        s = f"- **{name}**: hyperbolic vmax={ph[0]:.3f}, ρ½={ph[1]:.0f}, R²={r2h:.4f}"
        if pH is not None: s += f"  |  Hill vmax={pH[0]:.3f}, ρ½={pH[1]:.0f}, n={pH[2]:.2f}, R²={r2H:.4f}"
        return s

    # ===== D. old vs new single-head =====
    w("## D. Long-run (new) vs old single-head — is the old dataset transient-inflated?")
    w("")
    w(fitline("NEW single-head (0.1 s whole-window)", sh_h, sh_r2h, sh_H, sh_r2H))
    w(fitline("OLD single-head (Phase-C equilibrated)", ol_h, ol_r2h, ol_H, ol_r2H))
    w("")
    w("| ρ | NEW velProd (whole-window) | NEW postEquil | OLD |speed| (equilibrated) | Δ(new−old) |")
    w("|---|---|---|---|---|")
    oldmap = {int(d): (v, s) for d, v, s in zip(old["d"], old["v"], old["se"])}
    pemap = {int(d): p for d, p in zip(sh["d"], sh["pe"])}
    for d, v, se in zip(sh["d"], sh["v"], sh["se"]):
        od = oldmap.get(int(d))
        os_ = f"{od[0]:.3f}±{od[1]:.3f}" if od else "—"
        delta = f"{v-od[0]:+.3f}" if od else "—"
        w(f"| {int(d)} | {v:+.3f}±{se:.3f} | {pemap.get(int(d),float('nan')):+.3f} | {os_} | {delta} |")
    w("")
    # transient verdict
    common = [int(d) for d in sh["d"] if int(d) in oldmap]
    if common:
        newv = np.array([sh["v"][list(sh["d"]).index(d)] for d in common])
        oldv = np.array([oldmap[d][0] for d in common])
        pev  = np.array([pemap[d] for d in common])
        w(f"- On {len(common)} shared densities: NEW whole-window mean {newv.mean():.3f} vs OLD equilibrated mean {oldv.mean():.3f} "
          f"(NEW/OLD = {newv.mean()/oldv.mean() if oldv.mean() else float('nan'):.2f}×); NEW **post-equilibration** mean {pev.mean():.3f}.")
        w(f"  → The whole-window (matched-to-dimer) estimator {'is transient-INFLATED relative to' if newv.mean()>oldv.mean()*1.05 else 'agrees with'} "
          f"the equilibrated Phase-C estimator; the post-equilibration column isolates the steady value. Both single-head estimators "
          f"remain WELL BELOW the calibrated production surrogate only if that were the comparator — here the comparison is explicit↔explicit.")
    w("")

    # ===== E. matched raw-speed =====
    w("## E. Matched raw-speed: single-head (heads/µm²) vs dimer (dimers/µm²) — NO 2× factor")
    w("")
    w(fitline("single-head (heads/µm²)", sh_h, sh_r2h, sh_H, sh_r2H))
    w(fitline("HMM dimer (dimers/µm²)", dm_h, dm_r2h, dm_H, dm_r2H))
    w("")
    w("| ρ (objects/µm²) | single-head velProd | dimer velProd | dimer/single ratio |")
    w("|---|---|---|---|")
    dmap = {int(d): v for d, v in zip(dim["d"], dim["v"])}
    for d, v in zip(sh["d"], sh["v"]):
        dv = dmap.get(int(d))
        w(f"| {int(d)} | {v:+.3f} | {dv:+.3f} | {dv/v if (dv is not None and v) else float('nan'):.2f}× |" if dv is not None
          else f"| {int(d)} | {v:+.3f} | — | — |")
    w("")

    # ===== F. Uyeda =====
    w("## F. Uyeda-style normalized comparison")
    w("")
    shvm = f"{sh_h[0]:.3f}" if sh_h is not None else "n/a"; dmvm = f"{dm_h[0]:.3f}" if dm_h is not None else "n/a"
    w(f"- Normalization: v/vmax per model (single-head vmax={shvm}, dimer vmax={dmvm} µm/s from the hyperbolic fits).")
    w(f"- x-axis: surface density of MOTOR OBJECTS (heads/µm² for single-head, dimers/µm² for dimer — **no 2× factor**).")
    w(f"- Interaction-band width w_band = **{W_BAND_UM:.2f} µm** (the engageable y-strip |ay|<0.15 µm; shared by both mats — "
      f"`buildMat`/`buildGlide2D`). Motors interacting with a filament ≈ ρ · L_fil · w_band (L_fil ≈ 2.1 µm single-head / ≈2.0 µm dimer).")
    w(f"- **Uyeda reference is the qualitative literature band ~100–300 motors/µm² for continuous movement** (Uyeda, Kron & Spudich 1990); "
      f"no digitized Uyeda points are used here — the overlay is the approximate band only.")
    w("")

    # ---------------- PLOTS ----------------
    # 1. density response (new single head) + fit
    fig, ax = plt.subplots(figsize=(6.4, 4.6))
    ax.errorbar(sh["d"], sh["v"], yerr=sh["se"], fmt="o", color=C_SH, capsize=3, ms=6, label="single-head (0.1 s whole-window)")
    if sh_h is not None:
        xx = np.linspace(sh["d"].min(), sh["d"].max(), 300)
        ax.plot(xx, hyperbolic(xx, *sh_h), color=C_SH, lw=1.8, alpha=0.7,
                label=f"hyperbolic: vmax={sh_h[0]:.2f}, ρ½={sh_h[1]:.0f}")
    ax.set_xlabel("motor density (heads/µm²)"); ax.set_ylabel("productive glide speed (µm/s)")
    ax.set_title("Explicit single-head gliding density response (0.1 s/seed)")
    style(ax); ax.legend(frameon=False, fontsize=9); fig.tight_layout()
    fig.savefig(os.path.join(PLOTS, "density_response_singlehead.png"), dpi=150); plt.close(fig)

    # 2. old vs new single head
    fig, ax = plt.subplots(figsize=(6.4, 4.6))
    ax.errorbar(sh["d"], sh["v"], yerr=sh["se"], fmt="o-", color=C_SH, capsize=3, ms=6, label="NEW 0.1 s (whole-window)")
    ax.plot(sh["d"], sh["pe"], "s--", color=C_SH, alpha=0.5, ms=5, label="NEW post-equilibration (2nd half)")
    ax.errorbar(old["d"], old["v"], yerr=old["se"], fmt="^-", color=C_OLD, capsize=3, ms=6, label="OLD Phase-C (equilibrated, 0.15 s)")
    ax.set_xlabel("motor density (heads/µm²)"); ax.set_ylabel("productive glide speed (µm/s)")
    ax.set_title("Single-head: new long-run vs old equilibrated dataset")
    style(ax); ax.legend(frameon=False, fontsize=9); fig.tight_layout()
    fig.savefig(os.path.join(PLOTS, "old_vs_new_singlehead.png"), dpi=150); plt.close(fig)

    # 3. matched raw speed (PRIMARY): single (heads) + dimer (dimers), no 2x
    fig, ax = plt.subplots(figsize=(6.8, 4.8))
    ax.errorbar(sh["d"], sh["v"], yerr=sh["se"], fmt="o-", color=C_SH, capsize=3, ms=6, label="single-head (heads/µm²)")
    ax.errorbar(dim["d"], dim["v"], yerr=dim["se"], fmt="s-", color=C_DIM, capsize=3, ms=6, label="HMM dimer (dimers/µm²)")
    ax.set_xlabel("surface density of motor objects (per µm²)"); ax.set_ylabel("raw productive glide speed (µm/s)")
    ax.set_title("Matched 0.1 s raw-speed density response — no 2× factor")
    style(ax); ax.legend(frameon=False, fontsize=9); fig.tight_layout()
    fig.savefig(os.path.join(PLOTS, "matched_raw_speed.png"), dpi=150); plt.close(fig)

    # 4. supplemental head-equivalent (dimer x2), clearly labeled
    fig, ax = plt.subplots(figsize=(6.8, 4.8))
    ax.errorbar(sh["d"], sh["v"], yerr=sh["se"], fmt="o-", color=C_SH, capsize=3, ms=6, label="single-head (heads/µm²)")
    ax.errorbar(dim["d"]*2, dim["v"], yerr=dim["se"], fmt="s--", color=C_DIM, capsize=3, ms=6, label="HMM dimer (dimers×2 = HEAD-equivalent/µm²)")
    ax.set_xlabel("head-equivalent surface density (heads/µm²)")
    ax.set_ylabel("raw productive glide speed (µm/s)")
    ax.set_title("SUPPLEMENTAL: head-equivalent x-axis (dimer ×2) — NOT a molecular-density comparison")
    style(ax); ax.legend(frameon=False, fontsize=9); fig.tight_layout()
    fig.savefig(os.path.join(PLOTS, "headequiv_supplemental.png"), dpi=150); plt.close(fig)

    # 5. Uyeda normalized
    fig, ax = plt.subplots(figsize=(6.8, 4.8))
    ax.axvspan(100, 300, color="#E69F00", alpha=0.13, label="Uyeda ~100–300/µm² band (qualitative)")
    if sh_h is not None:
        ax.errorbar(sh["d"], sh["v"]/sh_h[0], yerr=sh["se"]/sh_h[0], fmt="o-", color=C_SH, capsize=3, ms=6,
                    label=f"single-head (÷vmax={sh_h[0]:.2f})")
    if dm_h is not None:
        ax.errorbar(dim["d"], dim["v"]/dm_h[0], yerr=dim["se"]/dm_h[0], fmt="s-", color=C_DIM, capsize=3, ms=6,
                    label=f"HMM dimer (÷vmax={dm_h[0]:.2f})")
    ax.axhline(0.5, color=C_GREY, lw=0.8, ls=":"); ax.set_ylim(-0.05, 1.1)
    ax.set_xlabel("surface density of motor objects (per µm²)"); ax.set_ylabel("v / vmax  (Uyeda-normalized)")
    ax.set_title("Uyeda-style normalized density response (no 2× factor)")
    style(ax); ax.legend(frameon=False, fontsize=9); fig.tight_layout()
    fig.savefig(os.path.join(PLOTS, "uyeda_normalized.png"), dpi=150); plt.close(fig)

    md = "\n".join(L) + "\n"
    open(os.path.join(NEW, "COMPARISON.md"), "w").write(md)
    print(md)
    print("Wrote", os.path.join(NEW, "COMPARISON.md"), "+ 5 plots to", PLOTS)
    return 0

if __name__ == "__main__":
    import sys; sys.exit(main())
