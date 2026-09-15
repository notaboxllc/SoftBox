#!/usr/bin/env python3
"""ACROSS-SEED ensemble analysis for the long site-normal gliding assay.

The single-trajectory analysis (site_normal_long_glide_analysis.py) can only test a walk against its own
Brownian null, and it cannot distinguish a real motor bias from a systematic bias in ONE lawn realisation
(the motor sites are hash-placed per seed, so a density/geometry accident would persist across every time
window of that seed and would bias the force channel too). Independent seeds are the only cure.

This script treats EACH SEED as one independent statistical unit and reports:
  - per-seed forward velocity from DISJOINT time windows (no overlap, no look-elsewhere inflation)
  - the across-seed mean +- SEM and the seed-level sign count
  - the same for the mean axial force, which is an independent channel from the kinematics
  - a matched-horizon comparison, so seeds of different length are compared over the SAME t

Usage:  python3 scripts/site_normal_glide_ensemble.py [run_dir ...]
        (default: the primary plus any site_normal_long_glide_seed* directories)
"""
import csv, glob, json, math, os, sys

BASE = "RUN_LOGS/motor_audit"
D_PAR = {0.1: 0.0193, 0.01: 0.19289}


def lsq(ts, xs):
    n = len(ts)
    if n < 3:
        return float("nan")
    st, sx = sum(ts), sum(xs)
    stt = sum(a * a for a in ts)
    stx = sum(a * b for a, b in zip(ts, xs))
    d = n * stt - st * st
    return float("nan") if abs(d) < 1e-30 else (n * stx - st * sx) / d


def load(run):
    csvf = os.path.join(run, "trajectory_summary.csv")
    if not os.path.exists(csvf):
        return None
    rows = list(csv.DictReader(open(csvf), delimiter="\t"))
    if len(rows) < 3:
        return None
    cfg = os.path.join(run, "run_config.json")
    seed, eta = "?", 0.1
    if os.path.exists(cfg):
        j = json.load(open(cfg))
        seed, eta = j.get("seed", "?"), j.get("eta_Pa_s", 0.1)
    return {
        "run": run, "seed": seed, "eta": eta,
        "t": [float(r["t_s"]) for r in rows],
        "x": [float(r["fwd_um"]) for r in rows],
        "fax": [float(r["faxMean_pN"]) for r in rows],
        "avgB": [float(r["avgBound"]) for r in rows],
        "cap": [int(r["captures"]) for r in rows],
        "step": [int(r["step"]) for r in rows],
    }


def disjoint_windows(t, x, width):
    """Forward velocity over NON-OVERLAPPING windows of the given width (s)."""
    out, lo = [], t[0]
    while lo < t[-1]:
        hi = lo + width
        idx = [i for i, tt in enumerate(t) if lo <= tt <= hi]
        if len(idx) >= 3:
            out.append(lsq([t[i] for i in idx], [x[i] for i in idx]))
        lo = hi
    return [v for v in out if v == v]


def _betacf(a, b, x):
    MAXIT, EPS, FPMIN = 200, 3e-16, 1e-300
    qab, qap, qam = a + b, a + 1.0, a - 1.0
    c, d = 1.0, 1.0 - qab * x / qap
    if abs(d) < FPMIN:
        d = FPMIN
    d = 1.0 / d
    h = d
    for m in range(1, MAXIT + 1):
        m2 = 2 * m
        aa = m * (b - m) * x / ((qam + m2) * (a + m2))
        d = 1.0 + aa * d
        if abs(d) < FPMIN:
            d = FPMIN
        c = 1.0 + aa / c
        if abs(c) < FPMIN:
            c = FPMIN
        d = 1.0 / d
        h *= d * c
        aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2))
        d = 1.0 + aa * d
        if abs(d) < FPMIN:
            d = FPMIN
        c = 1.0 + aa / c
        if abs(c) < FPMIN:
            c = FPMIN
        d = 1.0 / d
        de = d * c
        h *= de
        if abs(de - 1.0) < EPS:
            break
    return h


def _betai(a, b, x):
    if x <= 0.0:
        return 0.0
    if x >= 1.0:
        return 1.0
    lbeta = math.lgamma(a + b) - math.lgamma(a) - math.lgamma(b) + a * math.log(x) + b * math.log(1.0 - x)
    if x < (a + 1.0) / (a + b + 2.0):
        return math.exp(lbeta) * _betacf(a, b, x) / a
    return 1.0 - math.exp(lbeta) * _betacf(b, a, 1.0 - x) / b


def t_pvalue(t, df):
    """Two-sided p for Student's t. With n=3 seeds (df=2) a |mean|/SEM of 3.86 is p~0.06, NOT 3.9 sigma --
    quoting the ratio as a Gaussian sigma would badly overstate a 3-seed ensemble."""
    if df <= 0 or t != t:
        return float("nan")
    return _betai(0.5 * df, 0.5, df / (df + t * t))


def mean_sem(v):
    if not v:
        return float("nan"), float("nan")
    m = sum(v) / len(v)
    if len(v) < 2:
        return m, float("nan")
    sd = math.sqrt(sum((q - m) ** 2 for q in v) / (len(v) - 1))
    return m, sd / math.sqrt(len(v))


def main():
    runs = sys.argv[1:]
    if not runs:
        runs = [os.path.join(BASE, "site_normal_long_glide")] + sorted(
            glob.glob(os.path.join(BASE, "site_normal_long_glide_seed*")))
    data = [d for d in (load(r) for r in runs) if d]
    if not data:
        sys.exit("no usable runs found")

    tmin = min(d["t"][-1] for d in data)
    print("ACROSS-SEED ENSEMBLE — site-normal long gliding assay")
    print("seeds: %d   matched horizon t = %.4f s (the shortest run)\n" % (len(data), tmin))

    print("%-12s %8s %10s %10s %10s %9s %8s %7s" %
          ("seed", "t_end_s", "fwd_um", "v_full", "v_matched", "ratio", "fax_pN", "avgB"))
    vfull, vmatch, faxs, ratios = [], [], [], []
    for d in data:
        D = D_PAR.get(round(d["eta"], 4), D_PAR[0.1])
        v1 = lsq(d["t"], d["x"])
        im = [i for i, tt in enumerate(d["t"]) if tt <= tmin + 1e-12]
        v2 = lsq([d["t"][i] for i in im], [d["x"][i] for i in im])
        xm = d["x"][im[-1]]
        fl = math.sqrt(2 * D * tmin)
        print("%-12s %8.4f %10.5f %+10.4f %+10.4f %9.2f %+8.4f %7.3f" %
              (d["seed"], d["t"][-1], d["x"][-1], v1, v2, xm / fl, d["fax"][-1], d["avgB"][-1]))
        vfull.append(v1); vmatch.append(v2); faxs.append(d["fax"][-1]); ratios.append(xm / fl)

    print()
    for label, vals in (("full-run velocity (um/s)", vfull),
                        ("matched-horizon velocity (um/s)", vmatch),
                        ("mean axial force (pN)", faxs),
                        ("matched displacement / floor", ratios)):
        m, s = mean_sem(vals)
        pos = sum(1 for q in vals if q > 0)
        sig = abs(m) / s if s == s and s > 0 else float("nan")
        pv = t_pvalue(sig, len(vals) - 1)
        print("%-34s mean %+8.4f  SEM %7.4f  t %5.2f (df %d, p %.3f)  positive %d/%d"
              % (label, m, s, sig, len(vals) - 1, pv, pos, len(vals)))

    print("\nDISJOINT-window velocities per seed (independent within a seed; 0.15 s windows)")
    allw = []
    for d in data:
        w = disjoint_windows(d["t"], d["x"], 0.15)
        allw.append(w)
        pos = sum(1 for q in w if q > 0)
        m, s = mean_sem(w)
        print("  seed %-10s n=%2d  mean %+7.4f  SEM %6.4f  positive %d/%d   %s"
              % (d["seed"], len(w), m, s, pos, len(w),
                 " ".join("%+.2f" % q for q in w)))
    flat = [q for w in allw for q in w]
    if flat:
        m, s = mean_sem(flat)
        pos = sum(1 for q in flat if q > 0)
        print("  POOLED   n=%2d  mean %+7.4f  SEM %6.4f  |mean|/SEM %5.2f  positive %d/%d"
              % (len(flat), m, s, abs(m) / s if s else float("nan"), pos, len(flat)))

    print("\nSign test across seeds (distribution-free): with %d seeds, all-same-sign has p = %.3f two-sided."
          % (len(data), 2.0 ** (1 - len(data))))
    print("\nNOTE: the seed-level statistics above are the ones that bound the claim. Windows within a seed"
          "\n      share one lawn realisation, so they cannot exclude a systematic bias in that scene;"
          "\n      only the across-seed row can. Report the seed-level mean +- SEM and sign count.")


if __name__ == "__main__":
    main()
