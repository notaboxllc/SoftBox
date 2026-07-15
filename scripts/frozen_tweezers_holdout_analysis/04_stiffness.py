"""Tasks 5, 6, 7: incremental attached (cross-bridge) stiffness km, pre- and
post-stroke, by three independent estimators.

Common-mode dynamics when attached:
      gamma dX/dt = -2k (X - c(t)) - km (X - xm) + thermal
  =>  K_eff = 2k + km ;  var(X) = kT/K_eff ;  tau = gamma/K_eff
      steady-state gain to a trap-command step:  dX/dc = 2k/K_eff

E1  VARIANCE.  km = kT/var - 2k, with var measured on settled samples in
    1.5 ms chunks (local mean removed).  The forward model of what that
    estimator actually measures is inverted numerically:
        var_meas(K) = kT/K * g(w K/gamma) * Ffilt(K) [+ sigma_det^2]
    g() = OU windowing loss (short chunks miss low-frequency power),
    Ffilt = flp/(fc+flp) for the 1st-order 3 kHz anti-alias filter (realistic only),
    sigma_det^2 = white detector-noise variance on X (measured from the PSD floor
    of the realistic no-motor controls).

E2  PERTURBATION / LINEAR RESPONSE.  The full deterministic response of the ODE
    above to the measured command c(t) is integrated and fitted to X(t) over each
    dwell, with free parameters (km, xm, offset, linear drift).  This uses the
    pulse amplitudes AND the relaxation transients, and is independent of any
    assumption about where the motor sits.

E3  MEAN SHIFT vs PRELOAD (ensemble).  <y> = <X-c> = -c km/(2k+km) (+ step term
    post-stroke), so a regression of the dwell mean on the preload command gives
    km.  Assumes the motor's rest position coincides with the trap-command origin;
    that assumption is *tested* by agreement with E1/E2 and by the preload=0
    intercept.

E0  Applied to the no-motor controls, E1/E2 return the *instrument* added
    stiffness (should be 0) -> compliance correction / bound (task 6).
"""
import json
import os
import numpy as np
from scipy.optimize import curve_fit
from lib import (KT, OUT, load_trace, load_csv, control_path, controls_index,
                 index, perturbation_segments, bootstrap_ci)

cal = {r["level"]: r for r in json.load(open(os.path.join(OUT, "calibration.json")))}
EV = json.load(open(os.path.join(OUT, "events.json")))
FLP = 3000.0          # Hz, stated 1st-order low-pass of the realistic instrument
CHUNK_MS = 1.5


def g_ou(r):
    return 1 - 2 / r + 2 / r ** 2 * (1 - np.exp(-r))


# ---------- detector noise on X, measured from realistic no-motor controls ----------
def detector_noise_var():
    CI = controls_index()
    floors = []
    for tid, m in CI.items():
        if m["condition"] != "no_motor":
            continue
        dr = load_csv(control_path(tid, "realistic"))   # (3G-A loaded an unused ideal twin here; removed)
        # high-frequency PSD floor of the realistic trace (f > 5 kHz, above the
        # thermal corner and above the 3 kHz filter knee)
        y = dr["X"] - dr["c"]
        y = y - y.mean()
        dt = dr["dt"]
        f = np.fft.rfftfreq(len(y), dt)
        P = np.abs(np.fft.rfft(y * np.hanning(len(y)))) ** 2 * 2 * dt / np.sum(np.hanning(len(y)) ** 2)
        sel = f > 7000
        floors.append(np.median(P[sel]) * (1 / (2 * dt)))   # white variance = floor * f_Nyq
    return float(np.median(floors))


SIGMA2_X = detector_noise_var()


def var_model(K, gamma, dt, dataset, chunk_ms=CHUNK_MS, sig2=None):
    w = chunk_ms * 1e-3
    tau = gamma / K
    v = KT / K * g_ou(w / tau)
    if dataset == "realistic":
        fc = K / (2 * np.pi * gamma)
        v *= FLP / (fc + FLP)
        nch = int(round(w / dt))
        v += (sig2 if sig2 is not None else SIGMA2_X) * (1 - 1.0 / nch)
    return v


def km_from_var(var, k, gamma, dt, dataset):
    """Invert var_model for K, return km = K - 2k."""
    Ks = np.exp(np.linspace(np.log(0.005), np.log(50), 4000))
    vs = np.array([var_model(K, gamma, dt, dataset) for K in Ks])
    if var >= vs.max() or var <= vs.min():
        return np.nan
    K = float(np.interp(-var, -vs, Ks))   # vs is decreasing in K
    return K - 2 * k


# ---------------- E2: linear-response ODE fit ----------------
def simulate(c, dt, k2, km, xm, gamma, lowpass=False):
    """Deterministic X(t) for gamma X' = -k2 (X-c) - km (X-xm), exact for
    piecewise-constant c (zero-order hold):  X[i] = a X[i-1] + (1-a) Xss[i]."""
    from scipy.signal import lfilter
    K = k2 + km
    a = np.exp(-K * dt / gamma)
    Xss = (k2 * c + km * xm) / K
    X = lfilter([1 - a], [1.0, -a], Xss, zi=[a * Xss[0]])[0]
    if lowpass:
        alpha = dt / (dt + 1 / (2 * np.pi * FLP))
        X = lfilter([alpha], [1.0, -(1 - alpha)], X, zi=[(1 - alpha) * X[0]])[0]
    return X


def fit_km_response(t, X, c, dt, k, gamma, mask, dataset):
    """1-D scan over km; offset/drift/xm handled linearly at each km."""
    k2 = 2 * k
    kms = np.exp(np.linspace(np.log(0.02), np.log(20), 90))
    best = (np.inf, np.nan, None)
    tt = t - t[mask][0]
    for km in kms:
        # X = A * Xresp_c + B * 1 + C * t   where Xresp_c is the response to c with xm=0,
        # and the xm term contributes km/K * xm (a constant) -> absorbed in B.
        Xc = simulate(c, dt, k2, km, 0.0, gamma, lowpass=(dataset == "realistic"))
        A = np.column_stack([Xc[mask], np.ones(mask.sum()), tt[mask]])
        beta, *_ = np.linalg.lstsq(A, X[mask], rcond=None)
        r = X[mask] - A @ beta
        # constrain the gain on the modelled response to ~1 (the model is complete);
        # penalise deviation to keep the scan identifiable
        ss = float(r @ r) + 1e6 * (beta[0] - 1.0) ** 2 * mask.sum() * 0
        ss = float(r @ r)
        if abs(beta[0] - 1) > 0.35:          # reject fits that rescale the response
            continue
        if ss < best[0]:
            best = (ss, km, beta)
    return best[1]


def dwell_masks(r, d, par_settle=0.6):
    t, c, dt = d["t"], d["c"], d["dt"]
    n = len(t)
    m = np.ones(n, bool)
    _, segs = perturbation_segments(c, dt)
    ns = int(round(par_settle * 1e-3 / dt))
    settled = np.ones(n, bool)
    for (i0, i1, a) in segs:
        settled[max(0, i0 - 1):min(n, i1 + ns)] = False
    inside = (t >= r["attach_time_s"] + 1e-3) & (t <= r["detach_time_s"])
    if r.get("stroke"):
        ts = r["step_time_s"]
        pre = inside & (t < ts - 1e-3)
        post = inside & (t > ts + 2e-3)
    else:
        pre = inside
        post = np.zeros(n, bool)
    return pre, post, settled, inside


def run():
    idx = index("realistic")
    rows = []
    for ds in ["realistic"]:
        for r in EV[ds]:
            if not r.get("attached"):
                continue
            tid = r["trace_id"]
            lvl = r["level"]
            k = cal[lvl]["k_equipartition"]
            gamma = cal[lvl]["gamma_X_pNs_nm"]
            d = load_trace(tid, ds)
            t, dt = d["t"], d["dt"]
            y = d["X"] - d["c"]
            pre, post, settled, inside = dwell_masks(r, d)
            out = dict(trace_id=tid, dataset=ds, level=lvl, preload_nm=r["preload_nm"],
                       stroke=bool(r.get("stroke")), k_trap=k)
            for name, m in [("pre", pre), ("post", post)]:
                ms = m & settled
                if ms.sum() < 40:
                    continue
                v = r.get(f"var_{name}") if r.get("stroke") else r.get("var_attached_all")
                if v and np.isfinite(v):
                    out[f"km_var_{name}"] = km_from_var(v, k, gamma, dt, ds)
                    out[f"var_{name}"] = v
                # response fit needs the pulses -> use the full dwell segment (not settled)
                if m.sum() > 100:
                    km_r = fit_km_response(t, d["X"], d["c"], dt, k, gamma, m, ds)
                    out[f"km_resp_{name}"] = km_r
                out[f"mean_{name}"] = float(y[ms].mean())
            rows.append(out)
    json.dump(rows, open(os.path.join(OUT, "stiffness_per_trace.json"), "w"), indent=1, default=float)
    return rows


def controls_km():
    """E0: apply the same estimators to the no-motor controls (expect km = 0)."""
    CI = controls_index()
    out = []
    for ds in ["realistic"]:
        for tid, m in CI.items():
            if m["condition"] == "motor_present":
                continue
            lvl = m["calibration_level"]
            k = cal[lvl]["k_equipartition"]
            gamma = cal[lvl]["gamma_X_pNs_nm"]
            d = load_csv(control_path(tid, ds))
            dt = d["dt"]
            y = d["X"] - d["c"]
            n = len(y)
            _, segs = perturbation_segments(d["c"], dt)
            settled = np.ones(n, bool)
            ns = int(round(0.6e-3 / dt))
            for (i0, i1, a) in segs:
                settled[max(0, i0 - 1):min(n, i1 + ns)] = False
            # chunk variance on settled samples
            ii = np.where(settled)[0]
            nch = int(round(CHUNK_MS * 1e-3 / dt))
            splits = np.split(ii, np.where(np.diff(ii) > 1)[0] + 1)
            vs, ws = [], []
            for s in splits:
                for q in range(0, len(s) - nch + 1, nch):
                    blk = s[q:q + nch]
                    vs.append(np.var(y[blk], ddof=1))
                    ws.append(len(blk) - 1)
            v = float(np.average(vs, weights=ws))
            kmv = km_from_var(v, k, gamma, dt, ds)
            mask = np.ones(n, bool)
            kmr = fit_km_response(d["t"], d["X"], d["c"], dt, k, gamma, mask, ds) \
                if m["condition"] == "no_motor_perturbation_calibration" else np.nan
            out.append(dict(trace=tid, dataset=ds, level=lvl, condition=m["condition"],
                            var=v, km_var=kmv, km_resp=kmr, k_trap=k))
    json.dump(out, open(os.path.join(OUT, "controls_km.json"), "w"), indent=1, default=float)
    return out


if __name__ == "__main__":
    print(f"detector noise variance on X (from realistic no-motor PSD floor): "
          f"{SIGMA2_X:.3f} nm^2  (=> {np.sqrt(2*SIGMA2_X):.2f} nm per bead)")
    co = controls_km()
    print("\n=== E0: no-motor controls, apparent added stiffness (should be ~0) ===")
    for ds in ["realistic"]:
        for cond in ["no_motor", "no_motor_perturbation_calibration"]:
            sel = [r for r in co if r["dataset"] == ds and r["condition"] == cond]
            kv = np.array([r["km_var"] for r in sel], float)
            kr = np.array([r["km_resp"] for r in sel], float)
            print(f"{ds:10s} {cond:35s} n={len(sel)}  km_var={np.nanmean(kv):+.4f} "
                  f"+- {np.nanstd(kv):.4f}   km_resp={np.nanmean(kr):+.4f} +- {np.nanstd(kr):.4f} pN/nm")
    rows = run()
    print(f"\nfitted {len(rows)} trace-dwells -> out/stiffness_per_trace.json")
    for ds in ["realistic"]:
        for lvl in "ABC":
            for w in ["pre", "post"]:
                v = [r.get(f"km_var_{w}") for r in rows if r["dataset"] == ds and r["level"] == lvl
                     and r.get("stroke") and r.get(f"km_var_{w}") is not None]
                q = [r.get(f"km_resp_{w}") for r in rows if r["dataset"] == ds and r["level"] == lvl
                     and r.get("stroke") and r.get(f"km_resp_{w}") is not None]
                v = np.array(v, float); q = np.array(q, float)
                if len(v) == 0:
                    continue
                print(f"{ds:10s} {lvl} {w:4s} n={len(v):3d}  km_var={np.nanmedian(v):.3f}  "
                      f"km_resp={np.nanmedian(q):.3f}")
