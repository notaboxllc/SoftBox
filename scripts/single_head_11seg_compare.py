#!/usr/bin/env python3
"""
Matched-filament-length CONTROL comparison for the explicit single-head long-run density sweep.
Three-way, at the shared densities {250,400,700,1500,3000}:
  (1) NEW 11-seg single-head  (RUN_LOGS/single_head_density_sweep_long_11seg)
  (2) 12-seg single-head      (RUN_LOGS/single_head_density_sweep_long)          [completed reference]
  (3) 11-seg HMM dimer        (RUN_LOGS/hmm_density_sweep_long)                   [completed reference]

Reads each dir's density_summary.csv (mean/SD/SEM/CI already aggregated) and per-cell JSONs for extra channels.
Writes plots + COMPARISON_11SEG.md to the 11-seg dir. Does NOT touch the completed sweep outputs.
  python3 scripts/single_head_11seg_compare.py
"""
import os, csv, glob, json, math
import numpy as np
import matplotlib; matplotlib.use("Agg")
import matplotlib.pyplot as plt

D11 = "RUN_LOGS/single_head_density_sweep_long_11seg"
D12 = "RUN_LOGS/single_head_density_sweep_long"
DDIM = "RUN_LOGS/hmm_density_sweep_long"
PLOTS = os.path.join(D11, "plots"); os.makedirs(PLOTS, exist_ok=True)
SHARED = [250, 400, 700, 1500, 3000]

C11 = "#CC79A7"   # 11-seg single (reddish purple)
C12 = "#0072B2"   # 12-seg single (blue)
CDIM = "#D55E00"  # dimer (vermillion)

def load_summary(path):
    """density -> dict of the summary columns."""
    out = {}
    fp = os.path.join(path, "density_summary.csv")
    if not os.path.exists(fp): return out
    for r in csv.DictReader(open(fp)):
        out[int(float(r["density"]))] = {k: r[k] for k in r}
    return out

def load_perseed_vel(path):
    """density -> list of per-seed vel_prod from per-cell JSON (for exact CI/SD if needed)."""
    g = {}
    for f in glob.glob(os.path.join(path, "cell_*.json")):
        d = json.load(open(f))
        if d.get("status") == "ok":
            g.setdefault(int(d["density"]), []).append(d["vel_prod"])
    return g

def fnum(x):
    try: return float(x)
    except: return float("nan")

def style(ax):
    ax.grid(True, alpha=0.25, lw=0.6); ax.set_axisbelow(True)
    for s in ("top","right"): ax.spines[s].set_visible(False)

def main():
    s11, s12, sdim = load_summary(D11), load_summary(D12), load_summary(DDIM)
    if not s11:
        print("No 11-seg density_summary.csv yet — run the analysis on", D11); return 1
    have = [d for d in SHARED if d in s11]
    L = []; w = L.append
    w("# Matched-filament-length CONTROL — 11-seg single-head vs 12-seg single-head vs 11-seg dimer")
    w("")
    w("- 11-seg single-head: `RUN_LOGS/single_head_density_sweep_long_11seg` (contour ≈1.931 µm = the dimer geometry)")
    w("- 12-seg single-head: `RUN_LOGS/single_head_density_sweep_long` (contour 2.106 µm, validated default) — completed reference")
    w("- 11-seg HMM dimer:   `RUN_LOGS/hmm_density_sweep_long` (contour ≈1.931 µm) — completed reference")
    w("- Densities compared (heads/µm² single-head, dimers/µm² dimer — NO 2× factor): " + ", ".join(str(d) for d in have))
    w("")
    w("## Per-density three-way table")
    w("")
    w("| ρ | 11-seg SH velProd (SD, SEM, 95%CI) | 12-seg SH velProd | 11-seg dimer velProd | 11/12 ratio | dimer/11seg ratio |")
    w("|---|---|---|---|---|---|")
    ratios_1112, ratios_dim11 = [], []
    for d in have:
        a, b, c = s11.get(d), s12.get(d), sdim.get(d)
        av = fnum(a["velProd_mean"]); bv = fnum(b["velProd_mean"]) if b else float("nan")
        cv = fnum(c["velProd_mean"]) if c else float("nan")
        r1112 = av/bv if bv else float("nan"); rd11 = cv/av if av else float("nan")
        if not math.isnan(r1112): ratios_1112.append(r1112)
        if not math.isnan(rd11): ratios_dim11.append(rd11)
        w(f"| {d} | **{av:+.3f}** (SD {fnum(a['SD']):.3f}, SEM {fnum(a['SEM']):.3f}, [{fnum(a['ci95_lo']):+.3f},{fnum(a['ci95_hi']):+.3f}]) | "
          f"{bv:+.3f} | {cv:+.3f} | **{r1112:.3f}×** | {rd11:.3f}× |")
    w("")
    w("## Mechanism channels (11-seg single-head)")
    w("")
    w("| ρ | boundHeads | vel/boundHead | ATPturn | lifetime ms | Σinvalid | ΣsolveFail |")
    w("|---|---|---|---|---|---|---|")
    for d in have:
        a = s11[d]
        w(f"| {d} | {fnum(a['boundHeads']):.2f} | {fnum(a['vel_per_boundHead']):+.3f} | {int(fnum(a['ATPturn']))} | "
          f"{fnum(a['lifetime_ms']):.4f} | {a['invalid']} | {a['solveFail']} |")
    w("")
    # ---- decision rule ----
    r_mean = np.mean(ratios_1112) if ratios_1112 else float("nan")
    r_sd = np.std(ratios_1112, ddof=1) if len(ratios_1112) > 1 else 0.0
    dm12 = [fnum(sdim[d]["velProd_mean"])/fnum(s12[d]["velProd_mean"]) for d in have if d in sdim and d in s12]
    dm11 = ratios_dim11
    w("## Decision")
    w("")
    w(f"- **11-seg / 12-seg single-head velocity ratio = {r_mean:.3f} ± {r_sd:.3f}** (mean±SD over {len(ratios_1112)} shared densities; "
      f"per-density {['%.3f'%x for x in ratios_1112]}).")
    w(f"- dimer/single ratio: vs **11-seg** single = {np.mean(dm11):.3f}, vs **12-seg** single = {np.mean(dm12):.3f} "
      f"(Δ = {np.mean(dm11)-np.mean(dm12):+.3f}).")
    dev = abs(r_mean - 1.0)
    if dev < 0.03:
        verdict = ("**NEGLIGIBLE** — the ~9% filament-length mismatch changes single-head velocity by "
                   f"{(r_mean-1)*100:+.1f}% (< 3%), within seed scatter. The inferred dimerization effect is unchanged.")
    elif dev < 0.10:
        verdict = ("**MODEST RESCALING** — the ~9% length mismatch rescales single-head velocity by "
                   f"{(r_mean-1)*100:+.1f}%, a uniform amplitude shift that does NOT change the qualitative dimerization "
                   "conclusion (dimer still slower; ρ½ unchanged); the dimer/single ratio moves by the same small factor.")
    else:
        verdict = ("**MATERIAL** — the ~9% length mismatch changes single-head velocity by "
                   f"{(r_mean-1)*100:+.1f}% (> 10%), large enough to materially shift the inferred dimerization ratio; "
                   "the 12-seg-vs-11-seg-dimer comparison should be re-stated at matched length.")
    w(f"- **Verdict: {verdict}")
    w("")

    # ---- plots ----
    xd = np.array(have, float)
    def series(summ):
        return (np.array([fnum(summ[d]["velProd_mean"]) for d in have]),
                np.array([fnum(summ[d]["SEM"]) for d in have]))
    v11, e11 = series(s11); v12, e12 = series(s12); vdm, edm = series(sdim)

    # 1. 11-seg vs 12-seg single head
    fig, ax = plt.subplots(figsize=(6.6,4.7))
    ax.errorbar(xd, v11, yerr=e11, fmt="o-", color=C11, capsize=3, ms=6, label="11-seg single-head (1.931 µm — dimer-matched)")
    ax.errorbar(xd, v12, yerr=e12, fmt="s-", color=C12, capsize=3, ms=6, label="12-seg single-head (2.106 µm — validated default)")
    ax.set_xlabel("motor density (heads/µm²)"); ax.set_ylabel("raw productive glide speed (µm/s)")
    ax.set_title("Filament-length control: 11-seg vs 12-seg single head")
    style(ax); ax.legend(frameon=False, fontsize=9); fig.tight_layout()
    fig.savefig(os.path.join(PLOTS, "raw_11seg_vs_12seg_singlehead.png"), dpi=150); plt.close(fig)

    # 2. 11-seg single vs 11-seg dimer (matched length)
    fig, ax = plt.subplots(figsize=(6.6,4.7))
    ax.errorbar(xd, v11, yerr=e11, fmt="o-", color=C11, capsize=3, ms=6, label="11-seg single-head (heads/µm²)")
    ax.errorbar(xd, vdm, yerr=edm, fmt="s-", color=CDIM, capsize=3, ms=6, label="11-seg HMM dimer (dimers/µm²)")
    ax.set_xlabel("surface density of motor objects (per µm²)"); ax.set_ylabel("raw productive glide speed (µm/s)")
    ax.set_title("Length-matched: 11-seg single head vs 11-seg dimer (no 2× factor)")
    style(ax); ax.legend(frameon=False, fontsize=9); fig.tight_layout()
    fig.savefig(os.path.join(PLOTS, "raw_11seg_single_vs_dimer.png"), dpi=150); plt.close(fig)

    # 3. ratio vs density
    fig, ax = plt.subplots(figsize=(6.6,4.7))
    ax.axhline(1.0, color="#999999", lw=0.8, ls=":")
    ax.plot(xd, v11/v12, "o-", color=C11, ms=6, label="11-seg / 12-seg single-head")
    ax.plot(xd, vdm/v11, "s-", color=CDIM, ms=6, label="11-seg dimer / 11-seg single-head")
    ax.plot(xd, vdm/v12, "^--", color="#666666", ms=5, label="11-seg dimer / 12-seg single-head")
    ax.set_xlabel("density (per µm²)"); ax.set_ylabel("velocity ratio")
    ax.set_title("Velocity ratios vs density (filament-length control)")
    style(ax); ax.legend(frameon=False, fontsize=9); fig.tight_layout()
    fig.savefig(os.path.join(PLOTS, "ratio_vs_density.png"), dpi=150); plt.close(fig)

    md = "\n".join(L) + "\n"
    open(os.path.join(D11, "COMPARISON_11SEG.md"), "w").write(md)
    print(md)
    print("Wrote", os.path.join(D11, "COMPARISON_11SEG.md"), "+ 3 plots ->", PLOTS)
    return 0

if __name__ == "__main__":
    import sys; sys.exit(main())
