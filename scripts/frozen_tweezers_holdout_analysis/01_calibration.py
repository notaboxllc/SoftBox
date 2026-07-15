"""Task 1: verify / re-derive the per-trap trap stiffness for levels A/B/C.

Two independent estimators on the long no-motor calibration traces:
  (i)  equipartition on the common-mode dumbbell coordinate:  k = kT/(2 var(X-c))
  (ii) Lorentzian PSD fit of X-c  ->  fc, D  ->  k = 2*pi*fc*(kT/D)/2

Also: the differential coordinate u = x2-x1 gives the actin-link stiffness,
and the PSD gives the common-mode drag gamma_X (needed for the fast-pulse
relaxation correction later).
"""
import json
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from scipy.optimize import curve_fit
from lib import KT, OUT, ROOT, load_csv, perturbation_segments, reported_k
import json as _json
import os

# The calibration trace filenames + supplied stiffnesses come from the package's own
# calibration/calibration_summary.json (data-driven; not hardcoded to any dataset).
_calsum = _json.load(open(os.path.join(ROOT, "calibration", "calibration_summary.json")))
CAL = {r["level"]: r["calibration_trace"] for r in _calsum["levels"]}
REPORTED = reported_k()


def psd(x, dt, nseg=8):
    n = len(x) // nseg
    w = np.hanning(n)
    P = np.zeros(n // 2 + 1)
    for i in range(nseg):
        s = x[i * n:(i + 1) * n]
        s = s - s.mean()
        F = np.fft.rfft(s * w)
        P += (np.abs(F) ** 2) * 2 * dt / np.sum(w ** 2)
    P /= nseg
    f = np.fft.rfftfreq(n, dt)
    return f[1:], P[1:]


def lorentz(f, D, fc):
    return D / (np.pi ** 2 * (fc ** 2 + f ** 2))


rows = []
fig, axes = plt.subplots(1, 3, figsize=(13, 4))
for ax, (lvl, fn) in zip(axes, CAL.items()):
    d = load_csv(os.path.join(ROOT, "calibration", fn))
    dt = d["dt"]
    y = d["X"] - d["c"]
    # exclude perturbation excursions from the equipartition estimate
    base, segs = perturbation_segments(d["c"], dt)
    mask = np.ones(len(y), bool)
    relax = int(round(0.003 / dt))          # drop the pulse + a relaxation tail
    for i0, i1, a in segs:
        mask[max(0, i0 - 2):min(len(y), i1 + relax)] = False
    y_eq = y[mask] - np.mean(y[mask])
    var_eq = y_eq.var(ddof=1)
    k_eq = KT / (2 * var_eq)

    f, P = psd(y[mask][:len(y[mask]) // 8 * 8], dt) if mask.all() else psd(y, dt)
    sel = (f > 20) & (f < 10000)
    p, cov = curve_fit(lorentz, f[sel], P[sel], p0=[1e5, 200], sigma=P[sel], maxfev=20000)
    D, fc = p
    gamma_X = KT / D                        # pN s/nm, common-mode drag
    k_psd = 2 * np.pi * fc * gamma_X / 2.0  # per trap  (k_X = 2k)

    var_u = d["u"].var(ddof=1)
    k_link = KT / var_u - k_eq / 2.0        # u-mode stiffness = k/2 + k_link

    # statistical uncertainty on equipartition: var of variance for N_eff
    tau = gamma_X / (2 * k_eq)
    neff = len(y_eq) * dt / (2 * tau)
    se_eq = k_eq * np.sqrt(2.0 / neff)
    rows.append(dict(level=lvl, k_equipartition=k_eq, k_equipartition_se=se_eq,
                     k_psd=k_psd, fc_Hz=fc, gamma_X_pNs_nm=gamma_X, tau_ms=tau * 1e3,
                     var_X_nm2=var_eq, var_u_nm2=var_u, k_actin_link_pNnm=k_link,
                     reported=REPORTED[lvl],
                     ratio_equip_over_reported=k_eq / REPORTED[lvl]))
    ax.loglog(f, P, ".", ms=1, alpha=.3, color="0.6")
    # log-binned
    b = np.logspace(np.log10(f[0]), np.log10(f[-1]), 40)
    ib = np.digitize(f, b)
    fb = np.array([f[ib == i].mean() for i in range(1, len(b)) if (ib == i).any()])
    Pb = np.array([P[ib == i].mean() for i in range(1, len(b)) if (ib == i).any()])
    ax.loglog(fb, Pb, "o", ms=3, color="C0")
    ax.loglog(f, lorentz(f, *p), "r-", lw=1.5)
    ax.set_title(f"level {lvl}: fc={fc:.0f} Hz\nk_eq={k_eq:.4f}  k_psd={k_psd:.4f}  (rep {REPORTED[lvl]})")
    ax.set_xlabel("f (Hz)")
axes[0].set_ylabel("PSD of X-c  (nm^2/Hz)")
plt.tight_layout()
plt.savefig(os.path.join(OUT, "fig1_calibration_psd.png"), dpi=130)

json.dump(rows, open(os.path.join(OUT, "calibration.json"), "w"), indent=1)
hdr = "level k_equip se k_psd fc_Hz gamma_X tau_ms var_X var_u k_link reported ratio"
print(hdr)
for r in rows:
    print(f"{r['level']} {r['k_equipartition']:.5f} {r['k_equipartition_se']:.5f} {r['k_psd']:.5f} "
          f"{r['fc_Hz']:8.1f} {r['gamma_X_pNs_nm']:.3e} {r['tau_ms']:.3f} {r['var_X_nm2']:8.2f} "
          f"{r['var_u_nm2']:.4f} {r['k_actin_link_pNnm']:8.1f} {r['reported']:.4f} {r['ratio_equip_over_reported']:.3f}")
