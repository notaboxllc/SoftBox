#!/usr/bin/env python3
"""
SM4 survival-analysis core: Kaplan-Meier, Greenwood CI, RMST, empirical hazard.

Right-censoring aware. Deliberately dependency-light (numpy only) so the estimator
is fully auditable -- no `lifelines` install required.

Convention used throughout:
    t          : observed time (lifetime), seconds
    censored   : 1 if the event was still attached when observation ended (right-censored),
                 0 if a genuine detachment was observed.

NOTE (statistical discipline, per the SM4 brief): a simulation timestep is NOT an
independent sample. The unit of analysis here is the ATTACHMENT EVENT. Every routine
below consumes one row per event, never per step.
"""
import math
import numpy as np


def kaplan_meier(t, censored):
    """Kaplan-Meier survival estimate with Greenwood variance.

    Returns dict with arrays over the DISTINCT observed event times:
      times, n_risk, n_events, n_censored, surv, se, ci_lo, ci_hi
    CI is the log-log ("exponential Greenwood") interval, which respects [0,1]
    and behaves far better than the plain-linear one in small samples.
    """
    t = np.asarray(t, float)
    c = np.asarray(censored, int)
    if t.size == 0:
        return dict(times=np.array([]), n_risk=np.array([]), n_events=np.array([]),
                    n_censored=np.array([]), surv=np.array([]), se=np.array([]),
                    ci_lo=np.array([]), ci_hi=np.array([]), n=0, n_events_total=0,
                    n_censored_total=0)

    order = np.argsort(t, kind="mergesort")
    t, c = t[order], c[order]
    n = t.size

    uniq = np.unique(t[c == 0])           # KM steps only at observed DETACHMENT times
    times, n_risk, n_ev, n_cens = [], [], [], []
    surv, greenwood = [], []
    s = 1.0
    gw_cum = 0.0
    for ut in uniq:
        at_risk = int(np.sum(t >= ut))     # includes censored still under observation
        d = int(np.sum((t == ut) & (c == 0)))
        cens_here = int(np.sum((t == ut) & (c == 1)))
        if at_risk <= 0:
            continue
        s *= (1.0 - d / at_risk)
        if at_risk - d > 0:
            gw_cum += d / (at_risk * (at_risk - d))
        else:
            gw_cum = float("inf")
        times.append(ut); n_risk.append(at_risk); n_ev.append(d); n_cens.append(cens_here)
        surv.append(s); greenwood.append(gw_cum)

    times = np.array(times); surv = np.array(surv); greenwood = np.array(greenwood)
    n_risk = np.array(n_risk); n_ev = np.array(n_ev); n_cens = np.array(n_cens)

    with np.errstate(invalid="ignore", over="ignore"):
        se = np.where(np.isfinite(greenwood), surv * np.sqrt(np.maximum(greenwood, 0.0)), np.nan)

    # log-log CI: S^exp(+-1.96*sqrt(gw)/log S)
    ci_lo = np.full_like(surv, np.nan)
    ci_hi = np.full_like(surv, np.nan)
    with np.errstate(divide="ignore", invalid="ignore"):
        ok = (surv > 0) & (surv < 1) & np.isfinite(greenwood)
        theta = np.zeros_like(surv)
        theta[ok] = np.exp(1.96 * np.sqrt(greenwood[ok]) / np.log(surv[ok]))
        ci_lo[ok] = surv[ok] ** (1.0 / theta[ok])
        ci_hi[ok] = surv[ok] ** theta[ok]
    ci_lo = np.clip(ci_lo, 0.0, 1.0)
    ci_hi = np.clip(ci_hi, 0.0, 1.0)
    # S==1 plateau and S==0 floor are exact, not undefined
    ci_lo[surv >= 1.0] = 1.0; ci_hi[surv >= 1.0] = 1.0
    ci_lo[surv <= 0.0] = 0.0; ci_hi[surv <= 0.0] = 0.0

    return dict(times=times, n_risk=n_risk, n_events=n_ev, n_censored=n_cens,
                surv=surv, se=se, ci_lo=ci_lo, ci_hi=ci_hi,
                n=n, n_events_total=int(np.sum(c == 0)), n_censored_total=int(np.sum(c == 1)))


def km_quantile(km, q=0.5):
    """Smallest t with S(t) <= q. Returns nan when the curve never descends that far
    (i.e. the quantile is NOT identifiable from the observation window)."""
    if km["times"].size == 0:
        return float("nan")
    idx = np.where(km["surv"] <= q)[0]
    if idx.size == 0:
        return float("nan")
    return float(km["times"][idx[0]])


def km_quantile_ci(km, q=0.5):
    """CI for a KM quantile by inverting the log-log pointwise band."""
    if km["times"].size == 0:
        return (float("nan"), float("nan"))
    lo = np.where(km["ci_hi"] <= q)[0]      # upper band crosses q -> lower conf bound on time
    hi = np.where(km["ci_lo"] <= q)[0]
    lo_t = float(km["times"][lo[0]]) if lo.size else float("nan")
    hi_t = float(km["times"][hi[0]]) if hi.size else float("nan")
    return (lo_t, hi_t)


def rmst(km, tau=None):
    """Restricted mean survival time over [0, tau] = area under the KM curve.

    RMST is the censoring-correct location statistic: unlike the naive mean it stays
    valid when the largest observations are censored. Returns (rmst, se, tau).
    Variance uses the standard Klein-Moeschberger integral form.
    """
    if km["times"].size == 0:
        return (float("nan"), float("nan"), float("nan"))
    times = km["times"]; surv = km["surv"]
    if tau is None:
        tau = float(times[-1])
    keep = times <= tau
    tt = np.concatenate(([0.0], times[keep]))
    ss = np.concatenate(([1.0], surv[keep]))
    if tt[-1] < tau:
        tt = np.concatenate((tt, [tau])); ss = np.concatenate((ss, [ss[-1]]))
    widths = np.diff(tt)
    area = float(np.sum(ss[:-1] * widths))

    # variance: sum over event times of [ integral_{t_i}^{tau} S du ]^2 * d_i/(n_i (n_i-d_i))
    var = 0.0
    d = km["n_events"][keep]; nr = km["n_risk"][keep]; et = times[keep]
    for i, ti in enumerate(et):
        if nr[i] - d[i] <= 0:
            continue
        m = tt >= ti
        if not np.any(m):
            continue
        sub_t = tt[m]; sub_s = ss[m]
        if sub_t.size < 2:
            tail = 0.0
        else:
            tail = float(np.sum(sub_s[:-1] * np.diff(sub_t)))
        var += (tail ** 2) * (d[i] / (nr[i] * (nr[i] - d[i])))
    return (area, float(math.sqrt(max(var, 0.0))), float(tau))


def naive_mean(t, censored):
    """Mean of observed times, and whether it is VALID to report.

    Valid only when there is no censoring. With any censored observation the naive
    mean is biased LOW and must not be reported as a lifetime -- use RMST instead.
    """
    t = np.asarray(t, float); c = np.asarray(censored, int)
    if t.size == 0:
        return (float("nan"), float("nan"), False, 0)
    n_cens = int(np.sum(c == 1))
    valid = (n_cens == 0)
    m = float(np.mean(t))
    sd = float(np.std(t, ddof=1)) if t.size > 1 else 0.0
    sem = sd / math.sqrt(t.size) if t.size > 1 else 0.0
    return (m, sem, valid, n_cens)


def empirical_hazard(t, censored, n_bins=8, t_max=None):
    """Piecewise-constant hazard h_j = d_j / (person-time at risk in bin j), 1/s.

    Person-time (not event count) in the denominator, so censored events contribute
    their partial exposure -- the correct estimator under right-censoring.
    Returns list of dicts: bin_lo, bin_hi, n_events, exposure_s, hazard, se.
    """
    t = np.asarray(t, float); c = np.asarray(censored, int)
    out = []
    if t.size == 0:
        return out
    if t_max is None:
        t_max = float(np.max(t))
    if t_max <= 0:
        return out
    edges = np.linspace(0.0, t_max, n_bins + 1)
    for j in range(n_bins):
        lo, hi = edges[j], edges[j + 1]
        # exposure: time each event spent inside [lo,hi)
        expo = float(np.sum(np.clip(np.minimum(t, hi) - lo, 0.0, None)))
        d = int(np.sum((t >= lo) & (t < hi) & (c == 0)))
        h = (d / expo) if expo > 0 else float("nan")
        se = (math.sqrt(d) / expo) if (expo > 0 and d > 0) else float("nan")
        out.append(dict(bin_lo=float(lo), bin_hi=float(hi), n_events=d,
                        exposure_s=expo, hazard=h, se=se))
    return out


def summarize(t, censored, tau=None):
    """One-call survival summary for a single (state, force, direction) cell."""
    t = np.asarray(t, float); c = np.asarray(censored, int)
    km = kaplan_meier(t, c)
    med = km_quantile(km, 0.5)
    med_lo, med_hi = km_quantile_ci(km, 0.5)
    p90 = km_quantile(km, 0.10)     # time by which 90% have detached
    p99 = km_quantile(km, 0.01)
    r, r_se, r_tau = rmst(km, tau)
    m, m_sem, m_valid, n_cens = naive_mean(t, c)
    return dict(
        n=int(t.size), n_events=km["n_events_total"], n_censored=km["n_censored_total"],
        median=med, median_ci_lo=med_lo, median_ci_hi=med_hi,
        p90=p90, p99=p99,
        rmst=r, rmst_se=r_se, rmst_tau=r_tau,
        rmst_ci_lo=(r - 1.96 * r_se) if np.isfinite(r_se) else float("nan"),
        rmst_ci_hi=(r + 1.96 * r_se) if np.isfinite(r_se) else float("nan"),
        mean_naive=m, mean_naive_sem=m_sem, mean_naive_valid=bool(m_valid),
        km=km,
    )


# ------------------------------------------------------------------ self-test
def _selftest():
    """Validate the estimators against closed-form / textbook results."""
    rng = np.random.default_rng(12345)
    ok = True

    def chk(name, got, want, tol):
        nonlocal ok
        good = abs(got - want) <= tol
        ok &= good
        print(f"  [{'PASS' if good else 'FAIL'}] {name}: got {got:.6g}, want {want:.6g} (tol {tol:g})")

    # 1. No censoring -> KM must equal the empirical survival function exactly.
    t = np.array([1.0, 2.0, 3.0, 4.0, 5.0]); c = np.zeros(5, int)
    km = kaplan_meier(t, c)
    chk("KM uncensored S(t=3)", km["surv"][2], 2 / 5, 1e-12)
    chk("KM uncensored median", km_quantile(km, 0.5), 3.0, 1e-12)

    # 2. Textbook worked example (Klein & Moeschberger style), censoring interleaved.
    #    t = 1,2+,3,4+,5 ; '+' = censored
    t = np.array([1.0, 2.0, 3.0, 4.0, 5.0]); c = np.array([0, 1, 0, 1, 0])
    km = kaplan_meier(t, c)
    # S(1)=4/5=.8 ; S(3)=.8*(2/3)=.5333 ; S(5)=.5333*(0/1)=0
    chk("KM censored S(1)", km["surv"][0], 0.8, 1e-12)
    chk("KM censored S(3)", km["surv"][1], 0.8 * 2 / 3, 1e-12)
    chk("KM censored S(5)", km["surv"][2], 0.0, 1e-12)
    chk("KM censored n_censored", km["n_censored_total"], 2, 0)

    # 3. Exponential lifetimes, heavy censoring: RMST must stay ~unbiased while the
    #    naive mean is biased LOW. This is the whole reason RMST is the headline stat.
    lam = 50.0                       # 1/s  -> mean lifetime 20 ms
    N = 20000
    T = rng.exponential(1 / lam, N)
    Tcap = 0.02                      # observation window = 1 mean lifetime
    obs = np.minimum(T, Tcap); cen = (T > Tcap).astype(int)
    km2 = kaplan_meier(obs, cen)
    r, r_se, _ = rmst(km2, tau=Tcap)
    want_rmst = (1 - math.exp(-lam * Tcap)) / lam     # exact RMST for exponential
    chk("RMST vs exact (censored exp)", r, want_rmst, 4e-4)
    m, _, m_valid, _ = naive_mean(obs, cen)
    chk("naive mean flagged INVALID under censoring", float(m_valid), 0.0, 0)
    print(f"       (naive mean {m*1e3:.3f} ms is biased low vs true {1e3/lam:.3f} ms -- correctly withheld)")

    # 4. Hazard of an exponential is flat at lam.
    T2 = rng.exponential(1 / lam, N)
    hz = empirical_hazard(T2, np.zeros(N, int), n_bins=4, t_max=0.02)
    hs = [h["hazard"] for h in hz]
    chk("empirical hazard ~ lambda (bin0)", hs[0], lam, 3.0)
    chk("empirical hazard ~ lambda (bin3)", hs[3], lam, 6.0)

    # 5. All-censored cell must not crash and must yield no identifiable median.
    km3 = kaplan_meier(np.array([1.0, 2.0]), np.array([1, 1]))
    chk("all-censored -> no KM steps", float(km3["times"].size), 0.0, 0)
    chk("all-censored -> median nan", float(np.isnan(km_quantile(km3, 0.5))), 1.0, 0)

    print("\nSM4 survival self-test:", "ALL PASS" if ok else "FAILURES PRESENT")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(_selftest())
