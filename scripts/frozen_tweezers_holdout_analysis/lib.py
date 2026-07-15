"""Shared utilities for the blind dual-trap dumbbell analysis.

Physical model used throughout (derived from the data, see report):

  Two beads (x1 pointed-end, x2 barbed-end) held by traps of per-trap stiffness k,
  joined by a stiff actin link.  Trap centres are commanded as cL, cR; the trap
  SEPARATION is constant (1003 nm) in every trace, so both the preload and the
  perturbations are pure COMMON-MODE translations of the trap pair.

  Common-mode coordinates:   X = (x1+x2)/2 ,  c = (cL+cR)/2
  Differential:              u = x2 - x1  (stiff, ~ constant)

  Energy in X:   1/2 * (2k) * (X-c)^2   [+ 1/2 * km * (X - xm)^2 when attached]

  => detached:   var(X-c) = kT/(2k)                       (equipartition)
     attached:   var(X-c) = kT/(2k+km),  <X> = (2k c + km xm)/(2k+km)

  Perturbation response (trap common mode stepped by dc, motor rest position xm
  fixed):        dX/dc = 2k/(2k+km)  ==  r      =>  km = 2k (1/r - 1)

  Working stroke (xm: 0 -> d):
     X_pre  = 2k c /(2k+k_pre)
     X_post = (2k c + k_post d)/(2k+k_post)
     apparent step  = X_post - X_pre
                    = d * k_post/(2k+k_post)  +  2k c [1/(2k+k_post) - 1/(2k+k_pre)]
"""
import json
import os
import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
KT = 4.1                      # pN nm  (from instrument_metadata.json)
OUT = os.path.join(ROOT, "analysis", "out")
os.makedirs(OUT, exist_ok=True)

def reported_k():
    """Supplied per-trap stiffnesses, read from the package's own
    calibration/calibration_summary.json (NOT hardcoded, so the frozen pipeline
    is data-driven on whatever package it is pointed at).  This is only used for a
    diagnostic ratio print; the k actually used downstream is the equipartition
    estimate computed from the calibration trace."""
    s = json.load(open(os.path.join(ROOT, "calibration", "calibration_summary.json")))
    return {r["level"]: float(r["reported_trap_stiffness_pNnm_per_trap"]) for r in s["levels"]}


REPORTED_K = reported_k()


def load_csv(path):
    d = np.genfromtxt(path, delimiter=",", names=True)
    t = d["t_s"].astype(float)
    b1 = d["bead1_nm"].astype(float)
    b2 = d["bead2_nm"].astype(float)
    cL = d["trapL_cmd_nm"].astype(float)
    cR = d["trapR_cmd_nm"].astype(float)
    return dict(t=t, b1=b1, b2=b2, cL=cL, cR=cR,
                X=(b1 + b2) / 2.0, c=(cL + cR) / 2.0,
                u=b2 - b1, dt=float(np.median(np.diff(t))))


# FROZEN REALISTIC-ONLY HOLDOUT PIPELINE (3G-B).  Derived verbatim from the frozen
# 3G-A analyst pipeline; the ONLY changes remove the ideal-twin code paths (the
# holdout ships NO noise-free traces).  Every threshold, filter, exclusion,
# compliance equation, calibration method and primary estimator is unchanged.
# There is exactly one dataset here: the instrument-realistic traces.
DATASETS = ["realistic"]


def trace_path(tid, dataset="realistic"):
    # realistic-only: the ideal_observable_only branch is removed (no ideal twins).
    return os.path.join(ROOT, "instrument_realistic", "raw_traces", f"{tid}_realistic.csv")


def load_trace(tid, dataset="realistic"):
    return load_csv(trace_path(tid, dataset))


def index(dataset="realistic"):
    # realistic-only: the trace index always names the instrument_realistic subtree.
    return json.load(open(os.path.join(ROOT, "instrument_realistic", "index.json")))


def controls_index():
    return json.load(open(os.path.join(ROOT, "controls", "controls_index.json")))


def control_path(tid, dataset):
    suf = "_realistic" if dataset == "realistic" else ""
    return os.path.join(ROOT, "controls", f"{tid}{suf}.csv")


def rolling(a, w, fn):
    """Centred rolling statistic, w must be odd-ish; returns same length."""
    n = len(a)
    out = np.full(n, np.nan)
    h = w // 2
    for i in range(h, n - h):
        out[i] = fn(a[i - h:i + h + 1])
    return out


def rolling_var(a, w):
    """Fast centred rolling variance via cumulative sums."""
    n = len(a)
    cs = np.concatenate(([0.0], np.cumsum(a)))
    cs2 = np.concatenate(([0.0], np.cumsum(a * a)))
    s = cs[w:] - cs[:-w]
    s2 = cs2[w:] - cs2[:-w]
    v = s2 / w - (s / w) ** 2
    out = np.full(n, np.nan)
    h = w // 2
    out[h:h + len(v)] = v
    return out


def rolling_mean(a, w):
    n = len(a)
    cs = np.concatenate(([0.0], np.cumsum(a)))
    m = (cs[w:] - cs[:-w]) / w
    out = np.full(n, np.nan)
    h = w // 2
    out[h:h + len(m)] = m
    return out


def perturbation_segments(c, dt, min_len=4):
    """Find intervals where the trap common-mode command departs from its local
    baseline.  Returns (baseline_level_array, list of (i0,i1,amplitude))."""
    # baseline = the modal value of c over the whole trace (preload command)
    vals, counts = np.unique(np.round(c, 3), return_counts=True)
    base = vals[np.argmax(counts)]
    dev = np.round(c - base, 3)
    segs = []
    i = 0
    n = len(c)
    while i < n:
        if abs(dev[i]) > 1e-6:
            j = i
            while j < n and abs(dev[j] - dev[i]) < 1e-6:
                j += 1
            if (j - i) >= min_len:
                segs.append((i, j, float(dev[i])))
            i = j
        else:
            i += 1
    return base, segs


def km_from_ratio(r, k_trap):
    """km = 2k(1/r - 1)"""
    r = np.asarray(r, float)
    return 2 * k_trap * (1.0 / r - 1.0)


def bootstrap_ci(x, fn=np.mean, n=2000, seed=0, lo=2.5, hi=97.5):
    rng = np.random.default_rng(seed)
    x = np.asarray(x, float)
    x = x[np.isfinite(x)]
    if len(x) < 2:
        return (np.nan, np.nan)
    b = [fn(rng.choice(x, len(x), replace=True)) for _ in range(n)]
    return float(np.percentile(b, lo)), float(np.percentile(b, hi))
