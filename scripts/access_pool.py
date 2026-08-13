#!/usr/bin/env python3
"""Pool the Path-B site-accessibility telemetry across seed batches and produce the final tables.

Telemetry/analysis only — reads the TSVs written by ChiralSiteHarness -access-audit.
Usage:  python3 scripts/access_pool.py <per_seed.tsv> [<per_seed.tsv> ...]
"""
import csv, sys, math

CLS = ["NEAR", "SIDE", "FAR"]


def load(paths):
    rows = []
    for p in paths:
        with open(p) as fh:
            rows += list(csv.DictReader((l for l in fh if not l.startswith("#")), delimiter="\t"))
    return rows


def msn(v):
    """mean, SEM, n — seed is the independent unit."""
    n = len(v)
    if n == 0:
        return float("nan"), float("nan"), 0
    m = sum(v) / n
    if n < 2:
        return m, float("nan"), n
    s = math.sqrt(sum((x - m) ** 2 for x in v) / (n - 1))
    return m, s / math.sqrt(n), n


def main(paths):
    rows = load(paths)
    D = {}
    for r in rows:
        D[(r["arm"], r["seed"])] = r
    seeds = sorted({r["seed"] for r in rows}, key=int)
    arms = sorted({r["arm"] for r in rows})
    f = lambda r, k: float(r[k])

    print(f"# pooled from {len(paths)} batch file(s): {', '.join(paths)}")
    print(f"# seeds ({len(seeds)}): {', '.join(seeds)}   arms: {', '.join(arms)}")
    health = sum(int(float(r["invalid"])) for r in rows), sum(int(float(r["solverFail"])) for r in rows)
    print(f"# HEALTH: invalid={health[0]} solverFail={health[1]}")

    # ---------------- 1. class fractions, seed as the independent unit ----------------
    print("\n=== ATTACHMENT-EVENT and BOUND-OCCUPANCY FRACTIONS (mean +/- SEM over seeds) ===")
    print(f"{'arm':6} {'quantity':10} " + " ".join(f"{c:>18}" for c in CLS))
    for a in arms:
        for kind, pref in (("bind", "bind"), ("occ", "occ")):
            per = []
            for s in seeds:
                r = D.get((a, s))
                if r is None:
                    continue
                v = [f(r, pref + c) for c in CLS]
                t = sum(v)
                per.append([x / t for x in v] if t > 0 else [float("nan")] * 3)
            cols = []
            for i in range(3):
                m, e, n = msn([p[i] for p in per])
                cols.append(f"{m:.4f} +/- {e:.4f}")
            print(f"{a:6} {kind:10} " + " ".join(f"{c:>18}" for c in cols))
    print("  (uniform-azimuth null = 0.3333 in every class, by construction of the 120-degree bins)")

    # ---------------- 2. eps-ODD torque by class, seed-paired ----------------
    if "epsP" in arms and "epsM" in arms:
        print("\n=== eps-ODD AXIAL TORQUE BY ACCESSIBILITY CLASS  tau_odd = 0.5*(tau(+eps) - tau(-eps)) ===")
        print("    seed is the independent unit; each seed is a MATCHED pair.")
        per = {c: [] for c in CLS}
        tot = []
        share = []
        for s in seeds:
            p, m = D.get(("epsP", s)), D.get(("epsM", s))
            if p is None or m is None:
                continue
            o = [0.5 * (f(p, "tau" + c) - f(m, "tau" + c)) for c in CLS]
            for i, c in enumerate(CLS):
                per[c].append(o[i])
            tot.append(sum(o))
            if sum(o) != 0:
                share.append(o[2] / sum(o))
        print(f"{'class':6} {'tau_odd mean +/- SEM (N*m)':>34} {'sigma':>8} {'sign agreement':>16}")
        for c in CLS:
            mm, ee, nn = msn(per[c])
            sg = sum(1 for x in per[c] if x < 0)
            print(f"{c:6} {mm:+.5e} +/- {ee:.5e} {abs(mm/ee) if ee and ee==ee and ee!=0 else float('nan'):8.2f} "
                  f"{sg}/{nn} negative")
        mm, ee, nn = msn(tot)
        print(f"{'TOTAL':6} {mm:+.5e} +/- {ee:.5e} {abs(mm/ee) if ee and ee==ee and ee!=0 else float('nan'):8.2f} "
              f"{sum(1 for x in tot if x < 0)}/{nn} negative")

        # counterfactual: instantaneous-contribution accounting only
        print("\n=== PHASE 7 COUNTERFACTUAL (instantaneous-contribution ACCOUNTING, not a rerun) ===")
        noFar = [per['NEAR'][i] + per['SIDE'][i] for i in range(len(tot))]
        mA, eA, _ = msn(tot)
        mN, eN, _ = msn(noFar)
        print(f"  tau_odd ALL            = {mA:+.5e} +/- {eA:.5e}")
        print(f"  tau_odd FAR REMOVED    = {mN:+.5e} +/- {eN:.5e}")
        mF, eF, nF = msn(per['FAR'])
        print(f"  tau_odd FAR CLASS      = {mF:+.5e} +/- {eF:.5e}   ({sum(1 for x in per['FAR'] if x<0)}/{nF} negative)")
        if mA != 0:
            print(f"  FAR share of odd torque = {mF/mA:.4f}   (ratio of means; NOT a resolved uncertainty)")
        # ratio with a paired estimator: per-seed share is unstable when the denominator crosses zero
        if share:
            ms, es, ns = msn(share)
            print(f"  per-seed FAR share      = {ms:+.4f} +/- {es:.4f} (n={ns})  <-- unstable if any seed's total crosses 0")
        # sign concordance: does FAR carry the SAME sign as the total, per seed?
        conc = sum(1 for i in range(len(tot)) if (per['FAR'][i] < 0) == (tot[i] < 0))
        print(f"  per-seed sign concordance FAR vs TOTAL: {conc}/{len(tot)}")

    # ---------------- 3. eps-EVEN background ----------------
    if "epsP" in arms and "epsM" in arms:
        ev = []
        for s in seeds:
            p, m = D.get(("epsP", s)), D.get(("epsM", s))
            if p and m:
                ev.append(0.5 * (sum(f(p, "tau" + c) for c in CLS) + sum(f(m, "tau" + c) for c in CLS)))
        mm, ee, nn = msn(ev)
        print(f"\n  eps-EVEN (achiral) background torque = {mm:+.5e} +/- {ee:.5e} (n={nn})")
    if "eps0" in arms:
        z = [sum(f(D[("eps0", s)], "tau" + c) for c in CLS) for s in seeds if ("eps0", s) in D]
        mm, ee, nn = msn(z)
        print(f"  eps=0 CONTROL total torque          = {mm:+.5e} +/- {ee:.5e} (n={nn})")

    # ---------------- 4. residence / axial force by class ----------------
    print("\n=== RESIDENCE and AXIAL FORCE BY CLASS (pooled over seeds and arms) ===")
    print(f"{'class':6} {'mean residence (ms)':>22} {'mean axial bond force (N)':>28}")
    for i, c in enumerate(CLS):
        rs = sum(f(r, "resid" + c) for r in rows)
        dt = sum(f(r, "det" + c) for r in rows)
        fx = sum(f(r, "fax" + c) for r in rows)
        oc = sum(f(r, "occ" + c) for r in rows)
        print(f"{c:6} {1e3*rs/dt if dt else float('nan'):22.4f} {fx/oc if oc else float('nan'):28.4e}")


if __name__ == "__main__":
    main(sys.argv[1:] or ["RUN_LOGS/attachment_audit/path_b_accessibility/per_seed.tsv"])
