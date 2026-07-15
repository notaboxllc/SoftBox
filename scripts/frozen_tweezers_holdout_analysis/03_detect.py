"""Tasks 2 & 9: blind attachment + stroke detection; polarity.

ATTACHMENT
  y = X - c  (common-mode bead position minus common-mode trap command).
  Statistic: variance of y over the SETTLED samples in a centred 6 ms window
  (the perturbation pulses must be excluded - during a pulse y is deterministically
  modulated by ~ -amplitude, which would swamp the thermal variance).
  Attached  <=>  statistic < f_att x (its median on the no-motor controls), held
  for >= min_dwell (8 ms), with gaps <= close_ms (1.5 ms) bridged.
  W, f_att, min_dwell and close_ms were chosen (06_sensitivity) as an operating
  point with ZERO false positives on all 15 no-motor control traces in BOTH
  datasets.

STROKE
  Inside the attached dwell, on settled samples only, the statistic is
      max_j | mean(next nside settled samples) - mean(previous nside settled) |
  divided by the attached sd.  A fixed sample COUNT is used because the fast
  +-2 nm burst leaves a settled-sample desert exactly where many strokes fall.
  Windows stay local (a few ms) so the (measured: ~0.2 nm within a trace) drift is
  irrelevant.  The null distribution of the same statistic comes from a parametric
  OU bootstrap using the trace's own attached variance, correlation time and
  sample mask; a stroke is called at p < 0.01.  Step size = the D at the argmax.
"""
import json
import os
import numpy as np
from lib import (KT, OUT, load_trace, load_csv, control_path, controls_index,
                 index, perturbation_segments, rolling_var)

cal = {r["level"]: r for r in json.load(open(os.path.join(OUT, "calibration.json")))}

P = dict(win_ms=6.0, f_att=0.30, min_dwell_ms=8.0, settle_ms=0.6, close_ms=1.5, merge_ms=6.0,
         skip_ms=1.0,          # skip after attachment onset before using samples
         p_stroke=0.01, fit_ms=6.0, max_span_ms=100.0, nsim=400)
# nside: number of settled samples averaged either side of a candidate step
# (~3 ms of contiguous data; more when the perturbation block thins the samples)
NSIDE = {"realistic": 50}          # realistic-only (ideal-twin entry removed)
NSIDE_FLOOR = {"realistic": 24}

# thresholds are calibrated on the no-motor controls (see calibrate_threshold)
THRESH = json.load(open(os.path.join(OUT, "att_threshold.json"))) \
    if os.path.exists(os.path.join(OUT, "att_threshold.json")) else None


def g_ou(r):
    return 1 - 2 / r + 2 / r ** 2 * (1 - np.exp(-r))


def settled_mask(c, dt, settle_ms):
    n = len(c)
    m = np.ones(n, bool)
    base, segs = perturbation_segments(c, dt)
    ns = int(round(settle_ms * 1e-3 / dt))
    for (i0, i1, a) in segs:
        m[max(0, i0 - 1):min(n, i1 + ns)] = False
    return m


def masked_rolling_var(y, m, w, nmin=20):
    """Variance of the settled samples inside a centred window of w samples.
    The perturbation pulses must be excluded or they dominate the variance."""
    ym = np.where(m, y, 0.0)
    mm = m.astype(float)
    c1 = np.concatenate(([0.0], np.cumsum(ym)))
    c2 = np.concatenate(([0.0], np.cumsum(ym * ym)))
    cn = np.concatenate(([0.0], np.cumsum(mm)))
    s1 = c1[w:] - c1[:-w]
    s2 = c2[w:] - c2[:-w]
    n = cn[w:] - cn[:-w]
    with np.errstate(invalid="ignore", divide="ignore"):
        v = s2 / n - (s1 / n) ** 2
    v[n < nmin] = np.nan
    out = np.full(len(y), np.nan)
    h = w // 2
    out[h:h + len(v)] = v
    # windows that fall inside the fast-pulse burst contain too few settled samples;
    # interpolate across those short gaps so they do not break an attached run
    ok = np.isfinite(out)
    if ok.sum() > 2:
        i = np.arange(len(out))
        lo, hi = i[ok][0], i[ok][-1]
        seg = (i >= lo) & (i <= hi)
        out[seg] = np.interp(i[seg], i[ok], out[ok])
    return out


def att_statistic(y, c, dt, par):
    w = max(5, int(round(par["win_ms"] * 1e-3 / dt)))
    sm = settled_mask(c, dt, par["settle_ms"])
    return masked_rolling_var(y, sm, w), sm, w


def _runs(a):
    out, i = [], 0
    while i < len(a):
        if a[i]:
            j = i
            while j < len(a) and a[j]:
                j += 1
            out.append((i, j))
            i = j
        else:
            i += 1
    return out


def find_attachment(y, c, dt, thresh, par):
    rv, sm, w = att_statistic(y, c, dt, par)
    a = np.isfinite(rv) & (rv < thresh)
    # close short gaps: a brief variance excursion (e.g. the step itself, or the
    # fast-pulse burst where few settled samples survive) must not split a dwell
    gap = int(round(par["close_ms"] * 1e-3 / dt))
    for (i, j) in _runs(~a):
        if i > 0 and j < len(a) and (j - i) <= gap:
            a[i:j] = True
    runs = [r for r in _runs(a) if (r[1] - r[0]) * dt >= par["min_dwell_ms"] * 1e-3]
    if not runs:
        return None
    # A stroke inflates the variance of the window straddling it, which can split
    # one dwell into two qualifying runs.  Merge consecutive qualifying runs whose
    # separation is short; this cannot create a false positive (a FP needs a
    # qualifying run to exist in the first place).
    i0, i1 = runs[0]
    for (j0, j1) in runs[1:]:
        if (j0 - i1) * dt <= par["merge_ms"] * 1e-3:
            i1 = j1
        else:
            break
    # centred window -> refine the onset/end with a short (2 ms) window
    ws = max(5, int(round(2e-3 / dt)))
    rvs = masked_rolling_var(y, sm, ws, nmin=10)
    onset = min(len(y) - 1, i0 + w // 2)
    for i in range(max(ws, i0 - w), min(len(y) - ws - 1, i0 + w + 1)):
        if np.isfinite(rvs[i]) and rvs[i] < thresh:
            nn = int(round(4e-3 / dt))
            seg = rvs[i:i + nn]
            seg = seg[np.isfinite(seg)]
            if len(seg) and np.mean(seg < thresh) > 0.8:
                onset = i
                break
    end = min(len(y) - 1, i1 + w // 2)
    return int(onset), int(end)


def step_scan(tt, yy, par, sd=None, nside=None):
    """Local two-window step statistic on the SETTLED samples (tt, yy).

    For each candidate boundary j (in settled-sample index space):
        D(j) = mean(next nside settled samples) - mean(previous nside settled samples)
    A fixed sample COUNT (rather than a fixed time window) is used because the
    perturbation blocks leave very few settled samples in places - notably the
    fast +-2 nm burst, which sits right where many strokes occur.  The windows
    stay local in time (a few ms), so slow drift contributes little.
    Returns (|D|max/sd, t_step, D)."""
    n = len(yy)
    ns = nside or par["nside"]
    if n < 2 * ns + 4:
        return None
    cs = np.concatenate(([0.0], np.cumsum(yy)))
    j = np.arange(ns, n - ns)
    mpre = (cs[j] - cs[j - ns]) / ns
    mpost = (cs[j + ns] - cs[j]) / ns
    D = mpost - mpre
    # keep candidates whose windows do not span an unreasonably long time
    span = tt[np.minimum(j + ns - 1, n - 1)] - tt[np.maximum(j - ns, 0)]
    ok = span <= par["max_span_ms"] * 1e-3
    if not ok.any():
        return None
    D = np.where(ok, D, np.nan)
    m = int(np.nanargmax(np.abs(D)))
    s = sd if sd else 1.0
    return float(abs(D[m]) / s), float(tt[j[m]]), float(D[m])


def ou_null(mask, dt, tau, par, seed, t_full=None, nside=None):
    """Null distribution of the step statistic: OU noise of unit variance, the
    trace's own settled-sample mask and correlation time."""
    from scipy.signal import lfilter
    rng = np.random.default_rng(seed)
    n = len(mask)
    a = np.exp(-dt / tau)
    t_full = np.arange(n) * dt if t_full is None else t_full
    tt = t_full[mask]
    out = []
    for _ in range(par["nsim"]):
        e = rng.normal(0, np.sqrt(1 - a ** 2), n)
        x = lfilter([1.0], [1.0, -a], e)
        b = step_scan(tt, x[mask], par, sd=1.0, nside=nside)
        if b:
            out.append(b[0])
    return np.array(out)


NULL_CACHE = {}


def calibrate_threshold(par=P, f=None):
    """Threshold = f_att x (median of the attachment statistic on the NO-MOTOR
    controls, i.e. the detached state), per level and dataset.  f_att was chosen
    (06_sensitivity) as the largest value giving zero false positives on all 15
    no-motor control traces in both datasets."""
    f = par["f_att"] if f is None else f
    CI = controls_index()
    th = {}
    for ds in ["realistic"]:
        for lvl in "ABC":
            pool = []
            for tid, m in CI.items():
                if m["calibration_level"] != lvl or m["condition"] == "motor_present":
                    continue
                d = load_csv(control_path(tid, ds))
                rv, _, _ = att_statistic(d["X"] - d["c"], d["c"], d["dt"], par)
                pool.append(rv[np.isfinite(rv)])
            pool = np.concatenate(pool)
            th[f"{ds}_{lvl}"] = float(f * np.median(pool))
    return th


def analyse(d, lvl, par=P, dataset="realistic", seed=0, thresh=None):
    t, X, c, dt = d["t"], d["X"], d["c"], d["dt"]
    y = X - c
    k = cal[lvl]["k_equipartition"]
    gamma = cal[lvl]["gamma_X_pNs_nm"]
    if thresh is None:
        thresh = THRESH[f"{dataset}_{lvl}"]
    att = find_attachment(y, c, dt, thresh, par)
    if att is None:
        return dict(attached=False, stroke=False)
    a0, a1 = att
    sm = settled_mask(c, dt, par["settle_ms"])
    idx = np.arange(len(t))
    inside = (idx >= a0 + int(round(par["skip_ms"] * 1e-3 / dt))) & (idx <= a1)
    use = inside & sm
    res = dict(attached=True, attach_time_s=float(t[a0]), detach_time_s=float(t[a1]),
               n_settled=int(use.sum()))

    # attached variance on settled samples, local means removed in 1.5 ms chunks
    def chunk_var(mask):
        ii = np.where(mask)[0]
        if len(ii) < 20:
            return np.nan, 0
        nch = max(10, int(round(1.5e-3 / dt)))
        splits = np.split(ii, np.where(np.diff(ii) > 1)[0] + 1)
        vs, ws = [], []
        for s in splits:
            for q in range(0, len(s) - nch + 1, nch):
                blk = s[q:q + nch]
                vs.append(np.var(y[blk], ddof=1))
                ws.append(len(blk) - 1)
        if not vs:
            return np.nan, 0
        return float(np.average(vs, weights=ws)), int(np.sum(ws))

    v_all, n_all = chunk_var(use)
    res["var_attached_all"] = v_all
    if not np.isfinite(v_all) or v_all <= 0:
        res["stroke"] = False
        return res
    Kapp = KT / v_all
    tau_att = gamma / Kapp

    sd = np.sqrt(v_all)
    # adaptive window: shorten (down to a floor of ~1.5 ms of settled data) when the
    # dwell is short, so that short dwells still get a step test rather than being
    # left undetermined.  The null uses the same nside, so the test stays calibrated.
    nside = int(np.clip((int(use.sum()) - 4) // 2, NSIDE_FLOOR[dataset], NSIDE[dataset]))
    b = step_scan(t[use], y[use], par, sd=sd, nside=nside)
    if b is None:
        res.update(stroke=False, reason="dwell too short for a step test")
        return res
    stat, tstep, s_hat = b
    res.update(step_stat=float(stat), step_time_s=float(tstep), step_nm=float(s_hat))

    # null distribution of the same statistic (OU, unit variance -> statistic is
    # in units of the attached sd, so one null serves all traces with the same
    # mask geometry and tau)
    key = (dataset, lvl, int(a0 / 5), int(a1 / 5), round(tau_att, 5), nside)
    if key not in NULL_CACHE:
        NULL_CACHE[key] = ou_null(use, dt, tau_att, par,
                                  seed=abs(hash(key)) % 100000, t_full=t,
                                  nside=nside)
    null = NULL_CACHE[key]
    p = float((null >= stat).mean()) if len(null) else np.nan
    res["p_stroke"] = p
    res["stat_crit_99"] = float(np.percentile(null, 99)) if len(null) else np.nan
    res["stroke"] = bool(p < par["p_stroke"])
    res["step_nm_local"] = float(s_hat)

    vpre, npre = chunk_var(use & (t < tstep))
    vpost, npost = chunk_var(use & (t > tstep))
    res.update(var_pre=vpre, n_pre=npre, var_post=vpost, n_post=npost,
               mean_pre=float(y[use & (t < tstep)].mean()) if (use & (t < tstep)).sum() > 5 else np.nan,
               mean_post=float(y[use & (t > tstep)].mean()) if (use & (t > tstep)).sum() > 5 else np.nan)
    # bead-1 vs bead-2 displacement across the step (polarity check), local windows
    fw = par["fit_ms"] * 1e-3
    pre = use & (t < tstep) & (t >= tstep - fw)
    post = use & (t > tstep) & (t <= tstep + fw)
    if pre.sum() > 10 and post.sum() > 10:
        res["d_bead1"] = float(d["b1"][post].mean() - d["b1"][pre].mean())
        res["d_bead2"] = float(d["b2"][post].mean() - d["b2"][pre].mean())
    return res


def main():
    global THRESH
    THRESH = calibrate_threshold()
    json.dump(THRESH, open(os.path.join(OUT, "att_threshold.json"), "w"), indent=1)
    print("attachment thresholds (nm^2, settled-sample var in a 6 ms window):")
    print("  " + "  ".join(f"{k}={v:.2f}" for k, v in THRESH.items()))
    idx = index("realistic")
    allrows = {}
    for ds in ["realistic"]:
        rows = []
        for tid, meta in idx.items():
            d = load_trace(tid, ds)
            r = analyse(d, meta["calibration_level"], P, ds)
            r.update(trace_id=tid, dataset=ds, level=meta["calibration_level"],
                     preload_nm=meta["preload_command_nm"])
            rows.append(r)
        allrows[ds] = rows
        print(f"{ds}: attached {sum(r['attached'] for r in rows)}/{len(rows)}  "
              f"stroke {sum(bool(r['stroke']) for r in rows)}/{len(rows)}")
    json.dump(allrows, open(os.path.join(OUT, "events.json"), "w"), indent=1, default=float)

    CI = controls_index()
    ctrl = []
    for ds in ["realistic"]:
        for tid, m in CI.items():
            d = load_csv(control_path(tid, ds))
            r = analyse(d, m["calibration_level"], P, ds)
            r.update(trace=tid, dataset=ds, level=m["calibration_level"], condition=m["condition"])
            ctrl.append(r)
    json.dump(ctrl, open(os.path.join(OUT, "controls_events.json"), "w"), indent=1, default=float)
    print("\n--- controls (attachment / stroke false positives) ---")
    for cond in ["no_motor", "no_motor_perturbation_calibration", "motor_present"]:
        for ds in ["realistic"]:
            sel = [r for r in ctrl if r["condition"] == cond and r["dataset"] == ds]
            print(f"{cond:35s} {ds:10s} n={len(sel):2d} attached={sum(r['attached'] for r in sel)} "
                  f"stroke={sum(bool(r.get('stroke')) for r in sel)}")


if __name__ == "__main__":
    main()
