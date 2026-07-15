#!/usr/bin/env python3
"""Experiment 4A — canonical nucleotide cycle on the two-body motor: summary figure.
Usage: python3 scripts/twobody4a_analyze.py <csv_dir> <out.png>
Reads: state_trajectory.csv, dwell_stats.csv, adp_release_forceclamp.csv,
       dt_convergence.csv, mech_regression.csv
"""
import sys, csv, os
import numpy as np
import matplotlib; matplotlib.use("Agg")
import matplotlib.pyplot as plt

D, OUT = sys.argv[1], sys.argv[2]
def load(n):
    p = os.path.join(D, n)
    with open(p) as f: return list(csv.DictReader(f))
def col(rows, k): return np.array([float(r[k]) for r in rows])

fig, ax = plt.subplots(2, 3, figsize=(17, 9))
fig.suptitle("Experiment 4A — canonical Lymn-Taylor nucleotide cycle ported onto the two-body motor "
             "(non-canonical, default-off -exp4a; cycleLymnTaylor reused verbatim)",
             fontsize=12, fontweight="bold")

STATE_C = {"NONE": "0.5", "ATP": "tab:purple", "ADPPi": "tab:blue", "ADP": "tab:red"}

# (1) processive walking trajectory: actin displacement vs time, colored by nucleotide state
a = ax[0][0]
tr = load("state_trajectory.csv")
t = col(tr, "t_ms"); disp = col(tr, "actinDisp_nm"); states = [r["state"] for r in tr]
a.plot(t, disp, "-", color="0.3", lw=1.0, zorder=1)
for st, c in STATE_C.items():
    m = np.array([s == st for s in states])
    if m.any(): a.scatter(t[m], disp[m], s=18, color=c, label=st, zorder=2)
a.set_xlabel("time (ms)"); a.set_ylabel("actin displacement · b̂ (nm)")
a.set_title("(1) Processive cycling — the motor walks the\nfilament pointedward, ~5 nm per full cycle")
a.legend(fontsize=8, ncol=2); a.grid(alpha=0.3)

# (2) zero-load dwell: measured vs canonical 1/rate
a = ax[0][1]
dw = load("dwell_stats.csv")
labs, meas, canon, cols = [], [], [], []
for r in dw:
    b = "bound" if r["bound"] == "1" else "free"
    lab = f"{r['state']}\n({b})"
    cn = r["canonical_1overRate_ms"]
    if cn == "n/a" or cn == "NaN": continue
    labs.append(lab); meas.append(float(r["meanDwell_ms"])); canon.append(float(cn))
    cols.append(STATE_C.get(r["state"], "0.5"))
x = np.arange(len(labs))
a.bar(x - 0.2, meas, 0.4, label="measured", color=cols, alpha=0.85)
a.bar(x + 0.2, canon, 0.4, label="canonical 1/rate", color="k", alpha=0.4)
a.set_xticks(x); a.set_xticklabels(labs, fontsize=8); a.set_ylabel("mean dwell (ms)")
a.set_yscale("log")
a.set_title("(2) Zero-load state dwell vs canonical rate\n(ADP is load-modulated: catch prolongs it)")
a.legend(fontsize=8); a.grid(alpha=0.3, axis="y")

# (3) force-clamp catch-slip: release rate vs forceDotFil
a = ax[0][2]
fc = load("adp_release_forceclamp.csv")
F = col(fc, "measForceDotFil_pN"); rate = col(fc, "measRate_perS")
lo = col(fc, "ci_lo"); hi = col(fc, "ci_hi"); cg = col(fc, "canon_avgG_perS")
order = np.argsort(F)
a.errorbar(F[order], rate[order], yerr=[rate[order]-lo[order], hi[order]-rate[order]],
           fmt="o-", color="tab:red", capsize=3, label="measured (censoring-aware)")
a.plot(F[order], cg[order], "s--", color="k", alpha=0.6, label="canonical onADP·⟨g(F)⟩")
a.axvline(0, color="0.6", lw=0.6)
a.set_xlabel("forceDotFil = Dot(F8, seg.uVec)  (pN)"); a.set_ylabel("ADP→NONE release rate (/s)")
a.annotate("slip\n(assisting, F<0)", (F[order][0], rate[order][0]), fontsize=8, color="tab:green")
a.annotate("catch\n(opposing, F>0)", (F[order][-1], rate[order][-1]), fontsize=8, color="tab:blue")
a.set_title("(3) Signed-load ADP release = canonical g(F)\ncatch (F>0) slows, slip (F<0) speeds")
a.legend(fontsize=8); a.grid(alpha=0.3)

# (4) dt-convergence of the ADP release rate
a = ax[1][0]
dt = load("dt_convergence.csv")
dts = col(dt, "dt_s"); rr = col(dt, "adpReleaseRate_perS")
lo = col(dt, "ci_lo"); hi = col(dt, "ci_hi")
a.errorbar(dts, rr, yerr=[rr-lo, hi-rr], fmt="o-", color="tab:blue", capsize=3)
a.set_xscale("log"); a.invert_xaxis()
a.set_xlabel("dt (s)"); a.set_ylabel("zero-load ADP release rate (/s)")
a.set_title("(4) dt-convergence — the ADP dwell\nconverges as dt→0 (rates scale with dt)")
a.grid(alpha=0.3)

# (5) mechanical regression vs 3E/3F baseline
a = ax[1][1]
mr = load("mech_regression.csv")
names, base, now = [], [], []
keep = {"axial stroke nm": "stroke\n(nm)", "transverse nm": "trans\n(nm)",
        "k_ext(ADP) pN/nm": "k_ext\n(pN/nm)", "relaxed preload pN": "relax\npreload",
        "capture preload pN": "cap\npreload"}
for r in mr:
    if r["observable"] in keep:
        try:
            b = float(r["baseline_3E3F"]); n = float(r["exp4a_mean"])
            names.append(keep[r["observable"]]); base.append(b); now.append(n)
        except ValueError:
            pass
x = np.arange(len(names))
a.bar(x - 0.2, base, 0.4, label="3E/3F baseline", color="0.6")
a.bar(x + 0.2, now, 0.4, label="4A (cycle ON)", color="tab:green", alpha=0.8)
a.set_xticks(x); a.set_xticklabels(names, fontsize=8)
a.set_title("(5) Mechanical regression — 3E/3F\nobservables preserved with the cycle ON")
a.legend(fontsize=8); a.grid(alpha=0.3, axis="y")

# (6) the state machine as text
a = ax[1][2]; a.axis("off")
txt = (
    "CANONICAL STATE MACHINE (reused verbatim)\n"
    "NucleotideCycleSystem.cycleLymnTaylor\n\n"
    "  NONE ──atpOn(2e4)──▶ ATP\n"
    "       (bound: ATP-binding = DETACH)\n\n"
    "  ATP ──off-fil offATP(100)──▶ ADP·Pi\n"
    "       (hydrolysis recovery; re-primes lever)\n\n"
    "  ADP·Pi ──onPi(1e4)──▶ ADP\n"
    "       (Pi release = POWER STROKE;\n"
    "        θ_s: −30° → +30°, +60° swing)\n\n"
    "  ADP ──onADP(1e3)·g(F)──▶ NONE\n"
    "       (ADP release; g = catch·e^(−F·xC/kT)\n"
    "                        + slip·e^(+F·xS/kT))\n\n"
    "  bind ONLY in ADP·Pi (3E gate; live pose)\n"
    "  F = forceDotFil = Dot(F8_head, seg.uVec)\n"
    "  isCocked() = !isADPPi  (fixed handedness)"
)
a.text(0.02, 0.98, txt, fontsize=9, family="monospace", va="top", ha="left")

fig.tight_layout(rect=[0, 0, 1, 0.96])
fig.savefig(OUT, dpi=110)
print("wrote", OUT)
