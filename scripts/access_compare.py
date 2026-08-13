#!/usr/bin/env python3
"""Paired old-boundary vs new-boundary comparison for the Path-B accessibility audit.

Analysis only. Reads the per-seed TSVs written by ChiralSiteHarness -access-audit.

Usage:
  python3 scripts/access_compare.py --old <old.tsv> [<old2.tsv> ...] --new <new.tsv> [<new2.tsv> ...]
"""
import csv, sys, math

CLS = ["NEAR", "SIDE", "FAR"]


def load(paths):
    rows = []
    for p in paths:
        with open(p) as fh:
            rows += list(csv.DictReader((l for l in fh if not l.startswith("#")), delimiter="\t"))
    return {(r["arm"], r["seed"]): r for r in rows}


def msn(v):
    n = len(v)
    if n == 0:
        return float("nan"), float("nan"), 0
    m = sum(v) / n
    if n < 2:
        return m, float("nan"), n
    s = math.sqrt(sum((x - m) ** 2 for x in v) / (n - 1))
    return m, s / math.sqrt(n), n


def paired(a, b):
    """mean +/- SEM of the per-seed DIFFERENCE (b - a); seed is the unit."""
    d = [y - x for x, y in zip(a, b)]
    return msn(d)


def seeds_of(D):
    return sorted({k[1] for k in D}, key=int)


def frac(r, pref):
    v = [float(r[pref + c]) for c in CLS]
    t = sum(v)
    return [x / t for x in v] if t > 0 else [float("nan")] * 3


def collect(D, arms, pref, i):
    out = {}
    for s in seeds_of(D):
        vals = [frac(D[(a, s)], pref)[i] for a in arms if (a, s) in D]
        if vals:
            out[s] = sum(vals) / len(vals)
    return out


def scalar(D, arms, key):
    out = {}
    for s in seeds_of(D):
        vals = [float(D[(a, s)][key]) for a in arms if (a, s) in D]
        if vals:
            out[s] = sum(vals) / len(vals)
    return out


def odd_tau(D, i):
    out = {}
    for s in seeds_of(D):
        if ("epsP", s) in D and ("epsM", s) in D:
            p, m = D[("epsP", s)], D[("epsM", s)]
            out[s] = 0.5 * (float(p["tau" + CLS[i]]) - float(m["tau" + CLS[i]]))
    return out


def line(name, o, n, unit="", scale=1.0, pct=True):
    ks = sorted(set(o) & set(n), key=int)
    if not ks:
        print(f"  {name:34} {'n/a':>34}")
        return
    a = [o[k] * scale for k in ks]
    b = [n[k] * scale for k in ks]
    ma, ea, _ = msn(a)
    mb, eb, _ = msn(b)
    md, ed, nn = paired(a, b)
    rel = (100.0 * md / ma) if ma not in (0.0,) and ma == ma else float("nan")
    sig = abs(md / ed) if ed and ed == ed and ed != 0 else float("nan")
    relstr = f"{rel:+7.1f}%" if pct and abs(ma) > 1e-30 else "      -"
    print(f"  {name:34} {ma:+10.4f}±{ea:7.4f} {mb:+10.4f}±{eb:7.4f} {md:+10.4f}±{ed:7.4f} {relstr} {sig:6.2f}  {unit}")


def main(argv):
    if "--old" not in argv or "--new" not in argv:
        print(__doc__)
        return 2
    io, jn = argv.index("--old"), argv.index("--new")
    old_p = argv[io + 1:jn]
    new_p = argv[jn + 1:]
    O, N = load(old_p), load(new_p)
    arms = sorted({k[0] for k in O} & {k[0] for k in N})
    seeds = sorted(set(seeds_of(O)) & set(seeds_of(N)), key=int)
    print(f"# OLD (harmonic kz=2 pN/nm): {', '.join(old_p)}")
    print(f"# NEW (hard slab)          : {', '.join(new_p)}")
    print(f"# arms {arms}   matched seeds ({len(seeds)}): {', '.join(seeds)}")
    ho = sum(int(float(r['invalid'])) + int(float(r['solverFail'])) for r in O.values())
    hn = sum(int(float(r['invalid'])) + int(float(r['solverFail'])) for r in N.values())
    print(f"# HEALTH invalid+solverFail: old {ho}, new {hn}")
    print()
    print(f"  {'quantity':34} {'OLD (mean±SEM)':>19} {'NEW (mean±SEM)':>19} {'PAIRED DIFF':>19} {'rel':>8} {'sigma':>6}")
    print("  " + "-" * 112)
    for i, c in enumerate(CLS):
        line(f"attachment fraction {c}", collect(O, arms, "bind", i), collect(N, arms, "bind", i))
    for i, c in enumerate(CLS):
        line(f"bound-occupancy fraction {c}", collect(O, arms, "occ", i), collect(N, arms, "occ", i))
    print("  " + "-" * 112)
    line("avgBound", scalar(O, arms, "avgBound"), scalar(N, arms, "avgBound"))
    line("glide (um/s)", scalar(O, arms, "glide"), scalar(N, arms, "glide"))
    line("Omega_fit (rad/s)", scalar(O, arms, "omegaFit"), scalar(N, arms, "omegaFit"))
    # attachment flux and residence, pooled over classes
    fo = {s: sum(float(O[(a, s)]["bind" + c]) for a in arms for c in CLS) for s in seeds}
    fn = {s: sum(float(N[(a, s)]["bind" + c]) for a in arms for c in CLS) for s in seeds}
    line("attachment events (total)", fo, fn)
    ro = {s: sum(float(O[(a, s)]["resid" + c]) for a in arms for c in CLS) /
             max(1e-30, sum(float(O[(a, s)]["det" + c]) for a in arms for c in CLS)) for s in seeds}
    rn = {s: sum(float(N[(a, s)]["resid" + c]) for a in arms for c in CLS) /
             max(1e-30, sum(float(N[(a, s)]["det" + c]) for a in arms for c in CLS)) for s in seeds}
    line("mean residence (ms)", ro, rn, scale=1e3)
    print("  " + "-" * 112)
    if "epsP" in arms and "epsM" in arms:
        for i, c in enumerate(CLS):
            line(f"tau_odd {c} (1e-18 N*m)", odd_tau(O, i), odd_tau(N, i), scale=1e18)
        to = {s: sum(odd_tau(O, i).get(s, 0.0) for i in range(3)) for s in seeds}
        tn = {s: sum(odd_tau(N, i).get(s, 0.0) for i in range(3)) for s in seeds}
        line("tau_odd TOTAL (1e-18 N*m)", to, tn, scale=1e18)
        # FAR share of the odd torque, ratio of means (per-seed share is unstable when the total crosses 0)
        mo = msn([odd_tau(O, 2)[s] for s in seeds])[0] / msn([to[s] for s in seeds])[0]
        mn = msn([odd_tau(N, 2)[s] for s in seeds])[0] / msn([tn[s] for s in seeds])[0]
        print(f"\n  FAR share of tau_odd (ratio of means): OLD {mo:+.4f}   NEW {mn:+.4f}")
        retO = 1 - mo
        retN = 1 - mn
        print(f"  retained fraction if FAR removed     : OLD {retO:+.4f}   NEW {retN:+.4f}   (instantaneous accounting only)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
