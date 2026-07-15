"""Tasks 2 (false positives) & 6 (instrument compliance).

(a) no_motor_perturbation_calibration: the bare-dumbbell response to the trap
    common-mode pulses.  With no motor the steady-state response MUST be
    dX/dc = 1 (bead follows the trap).  Any deficit is instrument compliance /
    a systematic error in the pulse analysis.  We fit

        dX(t) = A (1 - exp(-t/tau))              (single-exponential, step response)

    and report A/amplitude (should be 1) and tau (should be gamma_X/(2k)).
    This validates the estimator that will be used on the attached dwells.

(b) no_motor + motor_present controls run through the SAME event detector used
    on the raw traces -> false-positive rate.
"""
import json
import os
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from scipy.optimize import curve_fit
from lib import KT, OUT, ROOT, load_csv, controls_index, control_path, perturbation_segments

cal = {r["level"]: r for r in json.load(open(os.path.join(OUT, "calibration.json")))}
CI = controls_index()


def pulse_responses(d, k_trap, gamma_X, pre_ms=1.0, tag=""):
    """Fit each perturbation pulse; return list of dicts."""
    t, X, c, dt = d["t"], d["X"], d["c"], d["dt"]
    base, segs = perturbation_segments(c, dt)
    npre = max(3, int(round(pre_ms * 1e-3 / dt)))
    out = []
    for (i0, i1, amp) in segs:
        if i0 - npre < 0 or i1 + 2 > len(t):
            continue
        b = np.mean(X[i0 - npre:i0])          # baseline just before the pulse
        seg = X[i0:i1] - b
        tt = (t[i0:i1] - t[i0])
        dur = tt[-1] + dt

        def f(x, A, tau):
            return A * (1 - np.exp(-x / max(tau, 1e-6)))
        try:
            p, cov = curve_fit(f, tt, seg, p0=[amp, gamma_X / (2 * k_trap)],
                               bounds=([-20, 2e-5], [20, 0.05]), maxfev=20000)
        except Exception:
            continue
        A, tau = p
        out.append(dict(amp=amp, dur_ms=dur * 1e3, A=A, tau_ms=tau * 1e3,
                        ratio=A / amp, n=int(i1 - i0), tag=tag))
    return out


# ---------------- (a) bare-dumbbell perturbation calibration ----------------
rows = []
for dataset in ["realistic"]:
    for tid, m in CI.items():
        if m["condition"] != "no_motor_perturbation_calibration":
            continue
        lvl = m["calibration_level"]
        d = load_csv(control_path(tid, dataset))
        k = cal[lvl]["k_equipartition"]
        g = cal[lvl]["gamma_X_pNs_nm"]
        for r in pulse_responses(d, k, g):
            r.update(trace=tid, level=lvl, dataset=dataset)
            rows.append(r)

print("=== bare-dumbbell (no motor) perturbation response: steady-state gain should be 1.0 ===")
tab = []
for dataset in ["realistic"]:
    for lvl in "ABC":
        for slow in [True, False]:
            sel = [r for r in rows if r["dataset"] == dataset and r["level"] == lvl
                   and (r["dur_ms"] > 1.5) == slow]
            if not sel:
                continue
            g = np.array([r["ratio"] for r in sel])
            ta = np.array([r["tau_ms"] for r in sel])
            pred = cal[lvl]["gamma_X_pNs_nm"] / (2 * cal[lvl]["k_equipartition"]) * 1e3
            tab.append(dict(dataset=dataset, level=lvl, pulse="slow" if slow else "fast",
                            n=len(sel), gain_mean=float(g.mean()), gain_sd=float(g.std(ddof=1)),
                            tau_ms_mean=float(np.median(ta)), tau_ms_predicted=float(pred)))
            print(f"{dataset:9s} {lvl} {'slow' if slow else 'fast'}  n={len(sel):3d}  "
                  f"gain={g.mean():6.3f} +- {g.std(ddof=1):.3f}   tau_fit={np.median(ta):5.2f} ms "
                  f"(predicted {pred:5.2f} ms)")
json.dump(tab, open(os.path.join(OUT, "control_perturbation_gain.json"), "w"), indent=1)

# figure: average bare response
fig, axes = plt.subplots(1, 3, figsize=(12, 3.6), sharey=True)
for ax, lvl in zip(axes, "ABC"):
    for tid, m in CI.items():
        if m["condition"] != "no_motor_perturbation_calibration" or m["calibration_level"] != lvl:
            continue
        d = load_csv(control_path(tid, "realistic"))
        base, segs = perturbation_segments(d["c"], d["dt"])
        npre = int(round(1e-3 / d["dt"]))
        for (i0, i1, amp) in segs:
            if i0 - npre < 0 or abs(amp) < 0.5:
                continue
            b = d["X"][i0 - npre:i0].mean()
            nn = min(int(round(6e-3 / d["dt"])), len(d["t"]) - i0)
            ax.plot((d["t"][i0:i0 + nn] - d["t"][i0]) * 1e3,
                    (d["X"][i0:i0 + nn] - b) / amp, lw=.6, alpha=.5)
    ax.axhline(1, color="k", ls="--", lw=1)
    ax.set_title(f"level {lvl} (no motor)")
    ax.set_xlabel("time from pulse (ms)")
axes[0].set_ylabel("(X - baseline)/amplitude")
axes[0].set_ylim(-1.5, 2.5)
plt.tight_layout()
plt.savefig(os.path.join(OUT, "fig2_bare_dumbbell_response.png"), dpi=130)
print("wrote fig2")
