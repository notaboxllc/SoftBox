#!/usr/bin/env python3
"""
SM4 RIGOR MECHANICAL-RUPTURE fit + cause accounting.

    python3 scripts/sm4_rigor_fit.py <rundir> [<rundir2> ...]

Fits the force-dependent RIGOR rupture law to the mechanical-rupture waiting times measured by
softbox.Sm4ForceLifetimeHarness with `-rigor-rupture`, holding the (frozen) ADP catch-slip and ATP
kinetics fixed. Writes, per run directory:
  fit_results.json            fitted rigor params + SE + CI + covariance + chi2 + residuals + model comparison
  parameter_covariance.csv    the fitted-parameter covariance matrix
  detachment_cause_summary.csv per-cell competing-risk decomposition (atp_release / rigor_rupture / censored)

WHAT IS FITTED
--------------
Rigor detachment is a COMPETING-RISK process:
    bound rigor (NUC_NONE) --[ atpOn ]------------> detached (ATP-triggered)      [force-INDEPENDENT]
    bound rigor (NUC_NONE) --[ k_rigor(F) ]-------> detached (mechanical rupture) [force-DEPENDENT]
The CAUSE-SPECIFIC hazard for the rupture channel is estimated censoring-aware as
    k_rigor_hat = (# rigor_rupture events in the cell) / (total bound exposure time in the cell),
so ATP-triggered detachments and right-censored events act as competing/censoring for the rupture
channel. In the ATP-free condition (atpOn=0) every detachment IS a rupture, so this reduces to the
single-exponential MLE 1/mean-lifetime.

    k_rigor(F) = k0 * [ aCatch * exp(-F * xCatch / kT) + aSlip * exp(+F * xSlip / kT) ]   (model 0)
    k_rigor(F) = k0 *   exp(+F * xSlip / kT)                                              (model 1, Bell slip)

All fits use the REALIZED mean bond load per cell (never the requested clamp force). The frozen ADP
catch-slip parameters are NOT touched — this constrains ONLY the new rigor pathway from rigor data.

TARGET (Guo & Guilford 2006, PNAS 103:26, Table 2 rigor two-pathway Bell fit, mapped to this form):
    k0=140/s, aCatch=0.9071 (xCatch=1.5 nm), aSlip=0.0929 (xSlip=0.5 nm), f_crit ~= 7.1 pN.
"""
import sys, os, json, csv, glob, math
import numpy as np
from scipy.optimize import curve_fit

KT_PN_NM = 4.141947e-21 / (1e-12 * 1e-9)      # Constants.kT at 300 K, in pN*nm
DT = 2.5e-6
MIN_STEPS = 20

# Guo & Guilford 2006 Table-2 rigor fit mapped to k(F)=k0[aC e^{-F xC/kT}+aS e^{+F xS/kT}]
TARGET = dict(k0=140.0, aCatch=0.9071, xCatch=1.5, aSlip=0.0929, xSlip=0.5)


def load_events(d):
    out = []
    for f in sorted(glob.glob(os.path.join(d, "sm4_batch_*.json"))):
        b = json.load(open(f))
        fx = b.get("fixture", b.get("motor_model", "?"))
        atpfree = b.get("atp_free", False)
        for e in b["events"]:
            if not e.get("valid", True):
                continue
            if e["state"] != "rigor":
                continue
            out.append(dict(
                fixture=fx, atpfree=atpfree,
                req=float(e["requested_force_pn"]), dirn=e["force_dir"],
                realized=float(e["realized_force_mean_pn"]) if e.get("realized_force_mean_pn") not in (None, "") else float("nan"),
                realized_sd=float(e.get("realized_force_sd_pn") or "nan"),
                life=float(e["lifetime_s"]), cens=int(e["censored"]),
                cause=e.get("detach_cause", ""),
                trup=(float(e["t_rigor_rupture_s"]) if e.get("t_rigor_rupture_s") not in (None, "", "null") else float("nan")),
                frup=(float(e["force_at_rupture_pn"]) if e.get("force_at_rupture_pn") not in (None, "", "null") else float("nan")),
                capwarn=int(e.get("rate_cap_warnings", 0) or 0),
            ))
    return out


def cause_summary(ev):
    cells = {}
    for e in ev:
        cells.setdefault((e["fixture"], e["req"], e["dirn"]), []).append(e)
    rows = []
    for (fx, req, dirn), es in sorted(cells.items()):
        n = len(es)
        nrup = sum(1 for e in es if e["cause"] == "rigor_rupture")
        natp = sum(1 for e in es if e["cause"] == "atp_release")
        ncen = sum(1 for e in es if e["cause"] == "censored")
        ncap = sum(e["capwarn"] for e in es)
        frups = [e["frup"] for e in es if e["cause"] == "rigor_rupture" and np.isfinite(e["frup"])]
        rows.append(dict(fixture=fx, requested_force_pn=req, force_dir=dirn, n=n,
                         realized_mean_pn=float(np.nanmean([e["realized"] for e in es])),
                         n_rigor_rupture=nrup, n_atp_release=natp, n_censored=ncen,
                         frac_rigor_rupture=nrup / n if n else 0.0,
                         frac_atp_release=natp / n if n else 0.0,
                         frac_censored=ncen / n if n else 0.0,
                         mean_force_at_rupture_pn=float(np.mean(frups)) if frups else float("nan"),
                         rate_cap_warning_steps=ncap))
    return rows


def rupture_rates(ev):
    """Cause-specific rupture hazard per cell: n_rupture / total-bound-exposure (censoring-aware)."""
    cells = {}
    for e in ev:
        cells.setdefault((e["req"], e["dirn"]), []).append(e)
    rows = []
    for (req, dirn), es in sorted(cells.items()):
        expo = float(sum(e["life"] for e in es))            # every bound-second is at risk for rupture
        nrup = sum(1 for e in es if e["cause"] == "rigor_rupture")
        rate = nrup / expo if (expo > 0 and nrup > 0) else float("nan")
        se = rate / math.sqrt(nrup) if nrup > 0 else float("nan")
        realized = np.array([e["realized"] for e in es], float)
        rsd = np.array([e["realized_sd"] for e in es], float)
        rows.append(dict(req=req, dirn=dirn, n=len(es), n_rupture=nrup, exposure_s=expo,
                         rate=rate, rate_se=se,
                         realized_mean=float(np.nanmean(realized)),
                         realized_sd_within=float(np.nanmean(rsd)) if rsd.size else float("nan")))
    return rows


def resolvable(r):
    return np.isfinite(r["rate"]) and r["rate"] * DT <= 1.0 / MIN_STEPS and r["n_rupture"] >= 3


def fit_twopath(rows):
    use = [r for r in rows if resolvable(r)]
    dropped = [dict(req=r["req"], realized=r["realized_mean"], rate=r["rate"],
                    reason=("unresolved rate*dt=%.3f > 1/%d" % (r["rate"] * DT, MIN_STEPS)
                            if np.isfinite(r["rate"]) else "no rupture events"))
               for r in rows if not resolvable(r)]
    if len(use) < 3:
        return dict(error="fewer than 3 resolvable cells", dropped_cells=dropped)
    F = np.array([r["realized_mean"] for r in use])
    y = np.array([r["rate"] for r in use])
    sy = np.array([r["rate_se"] for r in use])
    ly, sly = np.log(y), sy / y

    # k(F)=k0[aC e^{-F xC/kT}+aS e^{+F xS/kT}], aC+aS=1 (g(0)=1) so k0 is the unloaded rate
    def model(F_, k0, aC, xC, xS):
        aS = 1.0 - aC
        return np.log(k0 * (aC * np.exp(-F_ * xC / KT_PN_NM) + aS * np.exp(F_ * xS / KT_PN_NM)))

    p0 = [TARGET["k0"], TARGET["aCatch"], TARGET["xCatch"], TARGET["xSlip"]]
    lo = [1e0, 0.05, 0.05, 0.01]
    hi = [1e6, 0.999, 20.0, 20.0]
    popt, pcov = curve_fit(model, F, ly, p0=p0, sigma=sly, absolute_sigma=True,
                           bounds=(lo, hi), maxfev=400000)
    names = ["k0", "aCatch", "xCatch", "xSlip"]
    perr = np.sqrt(np.diag(pcov))
    resid = ly - model(F, *popt)
    dof = max(1, len(F) - len(popt))
    chi2 = float(np.sum((resid / sly) ** 2))
    with np.errstate(invalid="ignore", divide="ignore"):
        dsc = np.sqrt(np.diag(pcov)); corr = pcov / np.outer(dsc, dsc)
    vals = dict(zip(names, popt))
    aC, aS = vals["aCatch"], 1 - vals["aCatch"]
    try:
        peak = float(math.log((aC * vals["xCatch"]) / (aS * vals["xSlip"]))
                     / ((vals["xCatch"] + vals["xSlip"]) / KT_PN_NM))
    except Exception:
        peak = None
    return dict(
        model="two-pathway catch-slip",
        fitted={n: float(v) for n, v in zip(names, popt)},
        stderr={n: float(v) for n, v in zip(names, perr)},
        ci95={n: [float(v - 1.96 * e), float(v + 1.96 * e)] for n, v, e in zip(names, popt, perr)},
        covariance={names[i]: {names[j]: float(pcov[i, j]) for j in range(4)} for i in range(4)},
        correlation={names[i]: {names[j]: float(corr[i, j]) for j in range(4)} for i in range(4)},
        param_names=names, covariance_matrix=[[float(pcov[i, j]) for j in range(4)] for i in range(4)],
        chi2=chi2, dof=dof, chi2_per_dof=chi2 / dof, npar=4,
        aic=float(2 * 4 + chi2), peak_force_pn=peak,
        n_cells=len(F), n_cells_dropped=len(dropped), dropped_cells=dropped,
        residuals=[dict(realized_pn=float(f), log_resid=float(r)) for f, r in zip(F, resid)],
    )


def fit_bell(rows):
    """One-path Bell (slip only) k(F)=k0 exp(+F x/kT). Comparison model — cannot make a catch limb."""
    use = [r for r in rows if resolvable(r)]
    if len(use) < 2:
        return dict(error="insufficient cells")
    F = np.array([r["realized_mean"] for r in use])
    y = np.array([r["rate"] for r in use]); sy = np.array([r["rate_se"] for r in use])
    ly, sly = np.log(y), sy / y

    def model(F_, k0, x):
        return np.log(k0) + F_ * x / KT_PN_NM
    popt, pcov = curve_fit(model, F, ly, p0=[TARGET["k0"], 0.3], sigma=sly, absolute_sigma=True,
                           bounds=([1e0, -20], [1e6, 20]), maxfev=400000)
    perr = np.sqrt(np.diag(pcov))
    resid = ly - model(F, *popt)
    dof = max(1, len(F) - 2)
    chi2 = float(np.sum((resid / sly) ** 2))
    return dict(model="one-path Bell (slip)",
                fitted=dict(k0=float(popt[0]), xSlip=float(popt[1])),
                stderr=dict(k0=float(perr[0]), xSlip=float(perr[1])),
                chi2=chi2, dof=dof, chi2_per_dof=chi2 / dof, npar=2, aic=float(2 * 2 + chi2),
                n_cells=len(F))


def run(d):
    ev = load_events(d)
    if not ev:
        print(f"  (no rigor events in {d})")
        return None
    fixtures = sorted({e["fixture"] for e in ev})
    dirs = sorted({e["dirn"] for e in ev})
    causes = cause_summary(ev)

    # detachment_cause_summary.csv
    with open(os.path.join(d, "detachment_cause_summary.csv"), "w", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=list(causes[0].keys()))
        w.writeheader(); [w.writerow(r) for r in causes]

    # fit the OPPOSING (catch) arm — it identifies BOTH branches (rising catch limb + falling slip limb)
    opp = [e for e in ev if e["dirn"] in ("opposing", "none")]
    rows = rupture_rates(opp)
    fit = fit_twopath(rows)
    bell = fit_bell(rows)

    # parameter_covariance.csv
    if "covariance_matrix" in fit:
        with open(os.path.join(d, "parameter_covariance.csv"), "w", newline="") as fh:
            w = csv.writer(fh)
            w.writerow([""] + fit["param_names"])
            for i, nm in enumerate(fit["param_names"]):
                w.writerow([nm] + fit["covariance_matrix"][i])

    res = dict(run_dir=d, assay="SM4-rigor-mechanical-rupture", fixtures=fixtures, directions=dirs,
               kT_pN_nm=KT_PN_NM, target_params=TARGET,
               target_peak_force_pn=float(math.log((TARGET["aCatch"] * TARGET["xCatch"]) /
                    (TARGET["aSlip"] * TARGET["xSlip"])) / ((TARGET["xCatch"] + TARGET["xSlip"]) / KT_PN_NM)),
               rupture_rate_cells=rows, cause_summary=causes,
               fit_two_pathway=fit, fit_one_path_bell=bell,
               model_comparison=dict(
                   two_pathway_aic=fit.get("aic"), bell_aic=bell.get("aic"),
                   preferred=("two-pathway catch-slip" if (fit.get("aic") is not None and bell.get("aic") is not None
                              and fit["aic"] < bell["aic"]) else "one-path Bell"),
                   delta_aic=(bell.get("aic", float("nan")) - fit.get("aic", float("nan")))),
               notes=("Rigor rupture fit against REALIZED bond load; ADP catch-slip + ATP kinetics held FROZEN. "
                      "The two-pathway catch-slip should dominate the one-path Bell because a Bell law cannot "
                      "reproduce the rising (catch) limb below the ~7 pN optimum."))
    with open(os.path.join(d, "fit_results.json"), "w") as fh:
        json.dump(res, fh, indent=2, default=float)

    print(f"\n=== {d}")
    print(f"  fixtures={fixtures} dirs={dirs}  target peak={res['target_peak_force_pn']:.2f} pN")
    print(f"  {'req':>6} {'realized':>9} {'n_rup':>6} {'rupture rate 1/s':>17} {'life_ms(1/rate)':>16}")
    for r in rows:
        life = 1e3 / r["rate"] if np.isfinite(r["rate"]) and r["rate"] > 0 else float("nan")
        print(f"  {r['req']:6.1f} {r['realized_mean']:9.3f} {r['n_rupture']:6d} {r['rate']:17.2f} {life:16.3f}")
    if "fitted" in fit:
        print(f"  TWO-PATHWAY fit: chi2/dof={fit['chi2_per_dof']:.2f}  peak={fit['peak_force_pn']:.2f} pN  AIC={fit['aic']:.1f}")
        for k in fit["fitted"]:
            tv = TARGET.get(k, "-")
            print(f"    {k:8s} = {fit['fitted'][k]:9.4f} +/- {fit['stderr'][k]:.4f}   target={tv}")
    if "fitted" in bell:
        print(f"  ONE-PATH BELL: chi2/dof={bell['chi2_per_dof']:.2f}  AIC={bell['aic']:.1f}  (delta_AIC={res['model_comparison']['delta_aic']:.1f} favors {res['model_comparison']['preferred']})")
    return res


if __name__ == "__main__":
    for d in (sys.argv[1:] or ["RUN_LOGS/motor_validation/sm4_rigor_fixedanchor"]):
        run(d)
