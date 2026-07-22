#!/usr/bin/env python3
"""
SM4 kinetic fitting: recover the two-pathway catch-slip law from production lifetime data.

    python3 scripts/sm4_fit.py <rundir> [<rundir2> ...]

Writes <rundir>/fit_results.json for each run directory, and prints a summary.

WHAT IS FITTED
--------------
The model's detachment is a TWO-STEP chain (Lymn-Taylor):

    ADP --[ onADP * g(F) ]--> NONE --[ atpOn ]--> ATP == detached

    g(F) = aCatch * exp(-F * xCatch / kT) + aSlip * exp(+F * xSlip / kT)

Only the FIRST step is force-dependent. The harness timestamps it separately
(`t_adp_release_s`), so the primary fit targets that waiting time directly -- a pure
single-exponential whose rate is onADP*g(F). This is a far cleaner identification of
xCatch/xSlip than fitting total lifetime, which is contaminated by the force-independent
terminal step (and which SATURATES at 1/atpOn once the ADP step becomes fast).

FORCE AXIS
----------
All fits use the REALIZED mean bond load per event, not the requested clamp force. The two
differ: fixtures transmit load with a fixture-specific ratio, and explicit-S2 buckles in
compression. Residuals are reported against realized load.

CENSORING
---------
Per-cell rate uses the censoring-aware exponential MLE  rate = n_events / total_exposure,
with SE = rate / sqrt(n_events). Cells with zero observed events are dropped from the fit
(rate not identifiable) and reported.
"""
import sys, os, json, csv, math
import numpy as np
from scipy.optimize import curve_fit

KT_PN_NM = 4.141947e-21 / (1e-12 * 1e-9)      # Constants.kT at 300 K, in pN*nm

# coded (frozen) values -- the ground truth the apparatus must recover
CODED = dict(aCatch=0.92, aSlip=0.08, xCatch=2.5, xSlip=0.4, onADP=1.0e3, atpOn=2.0e4)


def g_law(F, aCatch, aSlip, xCatch, xSlip):
    return aCatch * np.exp(-F * xCatch / KT_PN_NM) + aSlip * np.exp(F * xSlip / KT_PN_NM)


def load_events(d):
    p = os.path.join(d, "event_table.csv")
    if not os.path.exists(p):
        return []
    out = []
    with open(p) as fh:
        for r in csv.DictReader(fh):
            if r.get("valid", "True") not in ("True", "true", "1"):
                continue
            try:
                out.append(dict(
                    fixture=r.get("fixture", "?"),
                    state=r["state"],
                    req=float(r["requested_force_pn"]),
                    dirn=r["force_dir"],
                    realized=float(r["realized_force_mean_pn"]),
                    realized_sd=float(r["realized_force_sd_pn"] or "nan"),
                    life=float(r["lifetime_s"]),
                    cens=int(r["censored"]),
                    tadp=float(r["t_adp_release_s"]) if r.get("t_adp_release_s") else float("nan"),
                ))
            except (ValueError, KeyError):
                continue
    return out


def cell_rates(events, which="adp_step"):
    """Censoring-aware exponential MLE rate per (requested force, direction) cell.

    which='adp_step'  -> the ADP->NONE waiting time (force-dependent step, primary)
    which='lifetime'  -> total attachment lifetime (secondary, saturating)
    """
    cells = {}
    for e in events:
        cells.setdefault((e["req"], e["dirn"]), []).append(e)
    rows = []
    for (req, dirn), es in sorted(cells.items()):
        realized = np.array([e["realized"] for e in es], float)
        rsd = np.array([e["realized_sd"] for e in es], float)
        if which == "adp_step":
            t = np.array([e["tadp"] for e in es], float)
            cens = np.array([e["cens"] for e in es], int)
            # an event censored before ADP->NONE fired, or with no timestamp, is censored for this step
            obs = np.where(np.isfinite(t), t, np.array([e["life"] for e in es], float))
            ev = np.isfinite(t) & (cens == 0)
        else:
            obs = np.array([e["life"] for e in es], float)
            ev = np.array([e["cens"] for e in es], int) == 0
        expo = float(np.sum(obs))
        n_ev = int(np.sum(ev))
        rate = n_ev / expo if expo > 0 and n_ev > 0 else float("nan")
        se = rate / math.sqrt(n_ev) if n_ev > 0 else float("nan")
        rows.append(dict(req=req, dirn=dirn, n=len(es), n_events=n_ev, exposure_s=expo,
                         rate=rate, rate_se=se,
                         realized_mean=float(np.mean(realized)),
                         realized_sd_across=float(np.std(realized, ddof=1)) if len(realized) > 1 else 0.0,
                         realized_sd_within=float(np.nanmean(rsd)) if rsd.size else float("nan"),
                         signed_realized=float(np.mean(realized))))
    return rows


DT = 2.5e-6                 # production timestep
MIN_STEPS = 20              # a waiting time must span >= this many steps to be resolvable


def resolvable(r, dt=DT, min_steps=MIN_STEPS):
    """A per-cell rate is only meaningful if the mean waiting time spans enough timesteps.

    As rate -> 1/dt the transition fires in the first step and the measured 'rate' is pinned at
    1/dt, carrying NO information about the underlying law. Such cells must be excluded from the
    fit, not fitted through -- otherwise they dominate chi2 and bias every parameter.
    """
    return np.isfinite(r["rate"]) and r["rate"] * dt <= 1.0 / min_steps and r["n_events"] >= 3


def fit_law(rows, fix_amplitudes=True, free=("xCatch",)):
    """Weighted fit of rate(F) = k0 * g(F) against REALIZED load.

    Returns dict with params, SEs, correlation matrix, chi2/dof, residuals.
    `free` selects which of (k0, aCatch, xCatch, xSlip) float; amplitudes are constrained
    to aCatch+aSlip=1 when fix_amplitudes (so g(0)=1 and k0 is the unloaded rate).
    """
    use = [r for r in rows if resolvable(r)]
    dropped = [dict(req=r["req"], realized=r["signed_realized"], rate=r["rate"],
                    reason=("unresolved: mean waiting time < %d steps (rate*dt=%.3f)" % (MIN_STEPS, r["rate"] * DT))
                            if np.isfinite(r["rate"]) else "no observed events")
               for r in rows if not resolvable(r)]
    if len(use) < 3:
        return dict(error="fewer than 3 resolvable cells", dropped_cells=dropped)
    F = np.array([r["signed_realized"] for r in use])
    y = np.array([r["rate"] for r in use])
    sy = np.array([r["rate_se"] for r in use])
    sy = np.where(sy > 0, sy, np.max(sy[sy > 0]) if np.any(sy > 0) else 1.0)

    # fit in log space: log rate = log k0 + log g(F); errors ~ symmetric in log for Poisson counts
    ly = np.log(y)
    sly = sy / y

    names = ["k0", "aCatch", "xCatch", "xSlip"]
    p0 = [CODED["onADP"], CODED["aCatch"], CODED["xCatch"], CODED["xSlip"]]
    lo = [1e0, 0.05, 0.05, 0.01]
    hi = [1e6, 0.999, 20.0, 20.0]
    freemask = [n in free or n == "k0" for n in names]

    fixed = dict(zip(names, p0))

    def model(F_, *theta):
        vals = dict(fixed)
        for n, v in zip([n for n, m in zip(names, freemask) if m], theta):
            vals[n] = v
        aC = vals["aCatch"]
        aS = (1.0 - aC) if fix_amplitudes else CODED["aSlip"]
        return np.log(vals["k0"] * g_law(F_, aC, aS, vals["xCatch"], vals["xSlip"]))

    th0 = [p for p, m in zip(p0, freemask) if m]
    blo = [p for p, m in zip(lo, freemask) if m]
    bhi = [p for p, m in zip(hi, freemask) if m]
    try:
        popt, pcov = curve_fit(model, F, ly, p0=th0, sigma=sly, absolute_sigma=True,
                               bounds=(blo, bhi), maxfev=400000)
    except Exception as ex:
        return dict(error=str(ex))

    perr = np.sqrt(np.diag(pcov))
    fitnames = [n for n, m in zip(names, freemask) if m]
    resid = ly - model(F, *popt)
    dof = max(1, len(F) - len(popt))
    chi2 = float(np.sum((resid / sly) ** 2))

    with np.errstate(invalid="ignore", divide="ignore"):
        d = np.sqrt(np.diag(pcov))
        corr = pcov / np.outer(d, d)

    out = dict(
        fitted={n: float(v) for n, v in zip(fitnames, popt)},
        stderr={n: float(v) for n, v in zip(fitnames, perr)},
        ci95={n: [float(v - 1.96 * e), float(v + 1.96 * e)] for n, v, e in zip(fitnames, popt, perr)},
        correlation={fitnames[i]: {fitnames[j]: float(corr[i, j]) for j in range(len(fitnames))}
                     for i in range(len(fitnames))},
        chi2=chi2, dof=dof, chi2_per_dof=chi2 / dof,
        n_cells=len(F), n_cells_dropped=len(dropped), dropped_cells=dropped,
        residuals=[dict(realized_pn=float(f), log_resid=float(r)) for f, r in zip(F, resid)],
    )
    # peak-lifetime force from the FITTED law (only meaningful if both branches identified)
    vals = dict(fixed); vals.update(out["fitted"])
    aC = vals["aCatch"]; aS = 1.0 - aC if fix_amplitudes else CODED["aSlip"]
    try:
        out["peak_force_pn"] = float(math.log((aC * vals["xCatch"]) / (aS * vals["xSlip"]))
                                     / ((vals["xCatch"] + vals["xSlip"]) / KT_PN_NM))
    except Exception:
        out["peak_force_pn"] = None
    return out


def fit_law_jensen(rows, free=("aCatch", "xCatch", "xSlip")):
    """Thermal-fluctuation (Jensen) corrected fit.

    The catch-slip rate is evaluated on the INSTANTANEOUS bond load, which fluctuates thermally with
    per-event SD sigma. Because g is CONVEX, the time-averaged rate exceeds g evaluated at the mean
    load: <g(F)> > g(<F>). For F ~ Normal(mu, sigma^2) this is exact and analytic, since
    <exp(aF)> = exp(a*mu + a^2*sigma^2/2). Fitting g(<F>) instead therefore inflates the apparent
    unloaded rate k0. This fit uses the correct <g>, so k0 should return to the coded onADP.
    """
    use = [r for r in rows if resolvable(r) and np.isfinite(r["realized_sd_within"])]
    if len(use) < 3:
        return dict(error="insufficient resolvable cells with load-SD")
    mu = np.array([r["signed_realized"] for r in use])
    sg = np.array([r["realized_sd_within"] for r in use])
    y = np.array([r["rate"] for r in use])
    sy = np.array([r["rate_se"] for r in use])
    ly, sly = np.log(y), sy / y

    names = ["k0", "aCatch", "xCatch", "xSlip"]
    p0 = [CODED["onADP"], CODED["aCatch"], CODED["xCatch"], CODED["xSlip"]]
    lo = [1e0, 0.05, 0.05, 0.01]; hi = [1e6, 0.999, 20.0, 20.0]
    fm = [n in free or n == "k0" for n in names]
    fixed = dict(zip(names, p0))

    def model(_x, *theta):
        vals = dict(fixed)
        for n, v in zip([n for n, m in zip(names, fm) if m], theta):
            vals[n] = v
        aC = vals["aCatch"]; aS = 1.0 - aC
        cC = vals["xCatch"] / KT_PN_NM; cS = vals["xSlip"] / KT_PN_NM
        gbar = (aC * np.exp(-cC * mu + 0.5 * cC * cC * sg * sg)
                + aS * np.exp(cS * mu + 0.5 * cS * cS * sg * sg))
        return np.log(vals["k0"] * gbar)

    th0 = [p for p, m in zip(p0, fm) if m]
    blo = [p for p, m in zip(lo, fm) if m]; bhi = [p for p, m in zip(hi, fm) if m]
    try:
        popt, pcov = curve_fit(model, mu, ly, p0=th0, sigma=sly, absolute_sigma=True,
                               bounds=(blo, bhi), maxfev=400000)
    except Exception as ex:
        return dict(error=str(ex))
    perr = np.sqrt(np.diag(pcov))
    fn = [n for n, m in zip(names, fm) if m]
    resid = ly - model(mu, *popt)
    dof = max(1, len(mu) - len(popt))
    return dict(
        fitted={n: float(v) for n, v in zip(fn, popt)},
        stderr={n: float(v) for n, v in zip(fn, perr)},
        ci95={n: [float(v - 1.96 * e), float(v + 1.96 * e)] for n, v, e in zip(fn, popt, perr)},
        chi2_per_dof=float(np.sum((resid / sly) ** 2) / dof), dof=dof, n_cells=len(mu),
        mean_load_sd_pn=float(np.mean(sg)),
        residuals=[dict(realized_pn=float(m_), log_resid=float(r)) for m_, r in zip(mu, resid)],
        note="Rate model uses <g(F)> for F~N(mu,sigma^2) (exact), sigma = mean within-event load SD.",
    )


def analytic_peak(aC=CODED["aCatch"], aS=CODED["aSlip"], xC=CODED["xCatch"], xS=CODED["xSlip"]):
    return math.log((aC * xC) / (aS * xS)) / ((xC + xS) / KT_PN_NM)


def run(d):
    ev = load_events(d)
    if not ev:
        print(f"  (no events in {d})")
        return None
    fixtures = sorted({e["fixture"] for e in ev})
    dirs = sorted({e["dirn"] for e in ev})
    rows_adp = cell_rates(ev, "adp_step")
    rows_life = cell_rates(ev, "lifetime")

    arm = "opposing" if any(d_ == "opposing" for d_ in dirs) else "assisting"
    # WHICH PARAMETER EACH ARM ACTUALLY CONSTRAINS.
    #   g(F) = aCatch*exp(-F*xCatch/kT) + aSlip*exp(+F*xSlip/kT)
    #   F > 0 (opposing/tensile): the CATCH term decays and the SLIP term grows => the rising limb
    #       identifies xCatch and the falling limb beyond the optimum identifies xSlip. BOTH.
    #   F < 0 (assisting/compressive): the CATCH exponential DIVERGES and the slip term is negligible
    #       => this arm constrains xCatch steeply, and carries almost no xSlip information.
    free = ("xCatch", "xSlip") if arm == "opposing" else ("xCatch",)
    fit = fit_law(rows_adp, fix_amplitudes=False, free=free)
    fit_both = fit_law(rows_adp, fix_amplitudes=True, free=("aCatch", "xCatch", "xSlip"))
    jfree = ("aCatch", "xCatch", "xSlip") if arm == "opposing" else ("xCatch",)
    fit_jensen = fit_law_jensen(rows_adp, free=jfree)

    res = dict(
        run_dir=d, fixtures=fixtures, directions=dirs, arm=arm,
        n_events=len(ev),
        kT_pN_nm=KT_PN_NM,
        coded_params=CODED,
        coded_analytic_peak_pn=analytic_peak(),
        cells_adp_step=rows_adp,
        cells_total_lifetime=rows_life,
        fit_primary=fit,
        fit_primary_free=list(free),
        fit_all_free=fit_both,
        fit_jensen_corrected=fit_jensen,
        notes=("Primary fit targets the ADP->NONE waiting time (the force-dependent step), against "
               "REALIZED bond load. Total-lifetime rates are reported for reference but saturate at "
               "atpOn once the ADP step is fast, which destroys identifiability at high |F|."),
    )
    with open(os.path.join(d, "fit_results.json"), "w") as fh:
        json.dump(res, fh, indent=2, default=float)

    print(f"\n=== {d}")
    print(f"  fixtures={fixtures} arm={arm} events={len(ev)}")
    print(f"  coded analytic peak = {analytic_peak():.3f} pN")
    print(f"  {'req':>6} {'realized':>9} {'n_ev':>5} {'ADP-step rate 1/s':>18} {'total-life rate 1/s':>20}")
    for a, b in zip(rows_adp, rows_life):
        print(f"  {a['req']:6.1f} {a['signed_realized']:9.3f} {a['n_events']:5d} "
              f"{a['rate']:18.1f} {b['rate']:20.1f}")
    if fit and "fitted" in fit:
        print(f"  FIT (free={free}, aSlip fixed at coded): chi2/dof={fit['chi2_per_dof']:.2f}")
        for k in fit["fitted"]:
            print(f"    {k:8s} = {fit['fitted'][k]:9.4f} +/- {fit['stderr'][k]:.4f}  "
                  f"95% CI [{fit['ci95'][k][0]:.4f}, {fit['ci95'][k][1]:.4f}]   coded={CODED.get(k,'-')}")
    if fit_both and "fitted" in fit_both:
        print(f"  FIT (all free): chi2/dof={fit_both['chi2_per_dof']:.2f}  peak={fit_both.get('peak_force_pn')}")
        for k in fit_both["fitted"]:
            print(f"    {k:8s} = {fit_both['fitted'][k]:9.4f} +/- {fit_both['stderr'][k]:.4f}   coded={CODED.get(k,'-')}")
    if fit_jensen and "fitted" in fit_jensen:
        print(f"  FIT (JENSEN-corrected, load SD={fit_jensen['mean_load_sd_pn']:.3f} pN): "
              f"chi2/dof={fit_jensen['chi2_per_dof']:.2f}")
        for k in fit_jensen["fitted"]:
            print(f"    {k:8s} = {fit_jensen['fitted'][k]:9.4f} +/- {fit_jensen['stderr'][k]:.4f}   coded={CODED.get(k,'-')}")
    return res


if __name__ == "__main__":
    ds = sys.argv[1:] or ["RUN_LOGS/motor_validation/sm4_adp_opposing_production"]
    for d in ds:
        run(d)
