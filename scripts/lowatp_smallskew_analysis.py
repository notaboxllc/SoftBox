#!/usr/bin/env python3
"""Low-ATP SMALL-SKEW pilot analysis (eps = 1, 2 deg vs the stored 15 deg reference, at 5 uM).

Reads BOTH record vintages:
  * new, eps-tagged ids   atp_u<uM>_e<deg*10>_d<dur_us>_[m_]{p,n,z}_<seed>
  * legacy untagged ids   atp_u<uM>_d<dur_us>_[m_]{p,n,z}_<seed>   (the eps = 15 campaign)
so the small-skew arms in this worktree can be compared against the 15 deg arms in the main tree
WITHOUT copying, moving or rewriting a single stored record.

Usage:
  python3 scripts/lowatp_smallskew_analysis.py \
      --rec-dir RUN_LOGS/chiral_sites/lowatp \
      --ref-dir /home/jba/Code/SoftBox/RUN_LOGS/chiral_sites/lowatp
"""
import argparse, glob, math, os, re, sys
from collections import defaultdict

NEW_RE = re.compile(r"atp_u(?P<uM>[0-9.]+)_e(?P<eps>\d{4})_d(?P<dur>\d+)_(?P<mir>m_)?(?P<sgn>[pnz])_(?P<seed>\d+)$")
OLD_RE = re.compile(r"atp_u(?P<uM>[0-9.]+)_d(?P<dur>\d+)_(?P<mir>m_)?(?P<sgn>[pnz])_(?P<seed>\d+)$")
LEGACY_EPS = 15.0
NSEG = 12
TWOPI = 2 * math.pi
T95 = {1: 12.706, 2: 4.303, 3: 3.182, 4: 2.776, 5: 2.571, 6: 2.447, 7: 2.365}


def read_record(path):
    vals, complete, prov = {}, False, ""
    with open(path) as fh:
        for line in fh:
            if line.startswith("#"):
                prov = line[1:].strip(); continue
            p = line.rstrip("\n").split("\t")
            if len(p) != 2:
                continue
            if p[0] == "COMPLETE":
                complete = True; continue
            try:
                vals[p[0]] = float(p[1])
            except ValueError:
                pass
    return (vals if complete else None), prov


def read_nested(path):
    rows = []
    if not os.path.exists(path):
        return rows
    with open(path) as fh:
        fh.readline()
        for line in fh:
            p = line.rstrip("\n").split("\t")
            if len(p) >= 8:
                rows.append([float(v) for v in p])
    return rows


def load(rec_dir, dur_us, legacy_eps=None, only_uM=None):
    """-> {(eps, sgn, seed): {v, prov, nested}}. legacy_eps tags untagged records with that skew.

    only_uM RESTRICTS to one [ATP]. This is load-bearing: the reference directory holds the whole
    4-concentration ladder under ids that differ only in the uM field, and the key here does not carry
    uM -- so without the filter the 2000/20/10 uM records silently overwrite the 5 uM ones and the
    comparison is made against the wrong condition entirely."""
    out = {}
    for path in sorted(glob.glob(os.path.join(rec_dir, "*.tsv"))):
        base = os.path.basename(path)[:-4]
        if base.endswith(".nested") or base.endswith(".trace"):
            continue
        m = NEW_RE.match(base)
        eps = None
        if m:
            eps = int(m.group("eps")) / 10.0
        elif legacy_eps is not None:
            m = OLD_RE.match(base)
            if m:
                eps = legacy_eps
        if not m or eps is None or int(m.group("dur")) != dur_us or m.group("mir"):
            continue
        if only_uM is not None and abs(float(m.group("uM")) - only_uM) > 1e-9:
            continue
        vals, prov = read_record(path)
        if vals is None:
            continue
        out[(eps, m.group("sgn"), int(m.group("seed")))] = dict(
            v=vals, prov=prov, base=base,
            nested=read_nested(os.path.join(rec_dir, base + ".nested.tsv")))
    return out


def msn(xs):
    xs = [x for x in xs if x is not None and math.isfinite(x)]
    n = len(xs)
    if n == 0:
        return float("nan"), float("nan"), 0
    m = sum(xs) / n
    if n < 2:
        return m, float("nan"), n
    v = sum((x - m) ** 2 for x in xs) / (n - 1)
    return m, math.sqrt(v / n), n


def relpct(a, b):
    d = max(abs(a), abs(b))
    return 100.0 * abs(a - b) / d if d > 0 else 0.0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--rec-dir", default="RUN_LOGS/chiral_sites/lowatp")
    ap.add_argument("--ref-dir", default="/home/jba/Code/SoftBox/RUN_LOGS/chiral_sites/lowatp")
    ap.add_argument("--duration-ms", type=float, default=200.0)
    ap.add_argument("--atp-uM", dest="atp_uM", type=float, default=5.0)
    ap.add_argument("--outdir", default="RUN_LOGS/lowatp_smallskew")
    a = ap.parse_args()
    dur_us = int(round(a.duration_ms * 1000))
    os.makedirs(a.outdir, exist_ok=True)

    arms = load(a.rec_dir, dur_us, only_uM=a.atp_uM)                       # small-skew arms (eps-tagged)
    ref = load(a.ref_dir, dur_us, legacy_eps=LEGACY_EPS, only_uM=a.atp_uM)  # stored 15 deg at the SAME [ATP]
    for k, v in ref.items():
        arms.setdefault(k, v)
    if not arms:
        print("no records"); return 1

    epss = sorted({k[0] for k in arms}, reverse=True)
    print("=" * 104)
    print("LOW-ATP SMALL-SKEW PILOT — %g µM, %g ms, eps in %s deg" % (a.atp_uM, a.duration_ms, epss))
    print("=" * 104)

    # ------------------------------------------------------------------ arm inventory
    print("\n### ARM INVENTORY\n")
    print("%-42s %7s %5s %6s %10s %11s %9s %6s %6s"
          % ("record", "eps", "sgn", "seed", "glide", "OmegaFit", "avgBound", "inval", "solv"))
    health = defaultdict(float)
    for k in sorted(arms, key=lambda x: (-x[0], x[1], x[2])):
        d = arms[k]; v = d["v"]
        for h in ("invalid", "solverFail", "rateCapWarns"):
            health[h] += v.get(h, 0.0)
        print("%-42s %7.1f %5s %6d %+10.4f %+11.2f %9.2f %6.0f %6.0f"
              % (d["base"], k[0], k[1], k[2], v["glide"], v["omegaFit"], v["avgBound"],
                 v["invalid"], v["solverFail"]))
    print("\n  health totals: invalid=%.0f  solverFail=%.0f  rateCapWarn=%.0f"
          % (health["invalid"], health["solverFail"], health["rateCapWarns"]))

    # ------------------------------------------------------------------ paired observables
    print("\n\n### PAIRED OBSERVABLES (per matched +/-eps seed pair)\n")
    print("%7s %6s | %10s %10s | %10s %10s | %10s %12s | %9s %9s"
          % ("eps", "seed", "v_even", "v_odd", "Omega_odd", "Omega_even", "turns/µm", "pitch µm",
             "R2(+)", "R2(-)"))
    pair = defaultdict(list)
    for eps in epss:
        if eps == 0.0:
            continue
        for sd in sorted({k[2] for k in arms if k[0] == eps}):
            P, N = arms.get((eps, "p", sd)), arms.get((eps, "n", sd))
            if not P or not N:
                continue
            p, n = P["v"], N["v"]
            v_even = 0.5 * (p["glide"] + n["glide"]); v_odd = 0.5 * (p["glide"] - n["glide"])
            om_odd = 0.5 * (p["omegaFit"] - n["omegaFit"]); om_even = 0.5 * (p["omegaFit"] + n["omegaFit"])
            tau_odd = 0.5 * (p["tau"] - n["tau"]); tau_even = 0.5 * (p["tau"] + n["tau"])
            turns = om_odd / (TWOPI * abs(v_even)) if v_even else float("nan")
            rec = dict(eps=eps, seed=sd, v_even=v_even, v_odd=v_odd, om_odd=om_odd, om_even=om_even,
                       tau_odd=tau_odd, tau_even=tau_even, turns=turns,
                       pitch=1.0 / turns if turns else float("nan"), P=P, N=N)
            pair[eps].append(rec)
            print("%7.1f %6d | %+10.4f %+10.4f | %+10.2f %+10.2f | %+10.2f %+12.4f | %9.3f %9.3f"
                  % (eps, sd, v_even, v_odd, om_odd, om_even, turns, rec["pitch"], p["rollR2"], n["rollR2"]))

    # ------------------------------------------------------------------ eps = 0 noise floor
    print("\n\n### EPS = 0 NOISE FLOOR (never included in the odd decomposition)\n")
    zero = [(k[2], arms[k]["v"]) for k in sorted(arms) if k[0] == 0.0]
    if zero:
        print("%6s | %11s %11s %9s %10s" % ("seed", "OmegaFit", "tau", "rollR2", "glide"))
        for sd, v in zero:
            print("%6d | %+11.2f %+11.4e %9.3f %+10.4f" % (sd, v["omegaFit"], v["tau"], v["rollR2"], v["glide"]))
        oms = [v["omegaFit"] for _, v in zero]
        print("\n  |Omega| at eps = 0 : %s  -> mean |Omega| = %.2f rad/s"
              % (", ".join("%.2f" % abs(o) for o in oms), sum(abs(o) for o in oms) / len(oms)))
        if len(oms) >= 2:
            pseudo = 0.5 * (oms[0] - oms[1])
            print("  PSEUDO-ODD from two independent eps=0 realizations treated as a fake +/- pair:")
            print("      0.5*(Omega_a - Omega_b) = %+.2f rad/s" % pseudo)
            print("  This is the spurious Omega_odd the estimator produces with NO chirality imposed;")
            print("  any |Omega_odd| at finite eps must clear it to be credible.")
    else:
        print("  (not run)")

    # ------------------------------------------------------------------ per-eps summary + floor test
    print("\n\n### PER-SKEW SUMMARY vs THE NOISE FLOOR\n")
    floor = None
    if zero and len(zero) >= 2:
        floor = abs(0.5 * (zero[0][1]["omegaFit"] - zero[1][1]["omegaFit"]))
    print("%7s | %22s %7s %6s | %22s %7s | %14s %12s"
          % ("eps", "Omega_odd mean ± SEM", "spread", "sign", "tau_odd mean ± SEM", "sign",
             "turns/µm", "pitch µm"))
    summ = {}
    for eps in epss:
        S = pair.get(eps, [])
        if not S:
            continue
        om = [d["om_odd"] for d in S]; ta = [d["tau_odd"] for d in S]; tu = [d["turns"] for d in S]
        mo, so, no = msn(om); mt, st, _ = msn(ta); mu, su, _ = msn(tu)
        mags = [abs(x) for x in om]
        same_o = all(x * mo > 0 for x in om); same_t = all(x * mt > 0 for x in ta)
        summ[eps] = dict(om=mo, om_sem=so, tau=mt, tau_sem=st, turns=mu, turns_sem=su,
                         same_o=same_o, same_t=same_t, n=no,
                         v=msn([d["v_even"] for d in S])[0])
        print("%7.1f | %+13.2f±%8.2f %7.2f %6s | %+13.4e±%7.1e %7s | %+14.2f %12.4f"
              % (eps, mo, so, max(mags) / min(mags) if min(mags) > 0 else float("nan"),
                 "same" if same_o else "SPLIT", mt, st, "same" if same_t else "SPLIT",
                 mu, 1.0 / mu if mu else float("nan")))
    if zero:
        # The floor is the WIDTH of the spurious-odd distribution, not one draw of it. A single pseudo-odd
        # sample can be arbitrarily small by chance (here both eps=0 arms happened to roll the same way), so
        # using it as the threshold would badly overstate significance. Omega_odd = 0.5*(Om(+) - Om(-)); with
        # two decorrelated arms each carrying the eps=0 roll, sigma(spurious odd) = 0.5*sqrt(2)*RMS(Om at 0).
        oms = [v["omegaFit"] for _, v in zero]
        rms = math.sqrt(sum(o * o for o in oms) / len(oms))
        sig = 0.5 * math.sqrt(2) * rms
        print("\n  eps = 0 per-arm |Omega| RMS = %.2f rad/s" % rms)
        if floor is not None:
            print("  one pseudo-odd draw = %.2f rad/s (a SAMPLE, not the threshold)" % floor)
        print("  => sigma(spurious Omega_odd) = 0.5*sqrt(2)*RMS = %.2f rad/s   <-- the floor used below" % sig)
        for eps in sorted(summ, reverse=True):
            st = summ[eps]
            r = abs(st["om"]) / sig if sig else float("nan")
            print("    eps=%4.1f : |Omega_odd| = %6.2f  -> %5.2f sigma   %s"
                  % (eps, abs(st["om"]), r,
                     "clears" if r > 2 else ("MARGINAL" if r > 1 else "AT/BELOW FLOOR")))

    # ------------------------------------------------------------------ closure
    print("\n\n### TORQUE-ROTATION CLOSURE (whole-filament roll drag)\n")
    graw = []
    for eps in epss:
        for d in pair.get(eps, []):
            p = d["P"]["v"]
            if p["omega"]:
                graw.append(p["tau"] * p["qOmega"] / p["omega"])
    gseg = min(graw) if graw else float("nan")
    gfil = NSEG * gseg
    print("  gamma_segment = %.6e   gamma_filament = NSEG*gamma_segment = %.6e N·m·s" % (gseg, gfil))
    print("%7s %6s | %14s %13s %13s %10s" % ("eps", "seed", "tau_odd", "Om_odd meas", "Om_odd pred", "meas/pred"))
    ratios = defaultdict(list)
    for eps in epss:
        for d in pair.get(eps, []):
            pred = d["tau_odd"] / gfil
            r = d["om_odd"] / pred if pred else float("nan")
            ratios[eps].append(r)
            print("%7.1f %6d | %+14.4e %+13.2f %+13.2f %10.3f" % (eps, d["seed"], d["tau_odd"], d["om_odd"], pred, r))
    for eps in sorted(ratios, reverse=True):
        m, s, n = msn(ratios[eps])
        print("  eps=%4.1f : meas/pred = %.3f ± %.3f (n=%d)" % (eps, m, s, n))

    # ------------------------------------------------------------------ scaling
    print("\n\n### SKEW SCALING (Stage 5)\n")
    if 1.0 in summ and 2.0 in summ:
        print("%-26s %12s %12s %12s | %10s %10s"
              % ("quantity", "eps=1", "eps=2", "eps=15", "R(2/1)", "R(15/1)"))
        def row(name, f):
            v1, v2 = f(summ[1.0]), f(summ[2.0])
            v15 = f(summ[15.0]) if 15.0 in summ else float("nan")
            print("%-26s %12.4g %12.4g %12.4g | %10.3f %10.3f"
                  % (name, v1, v2, v15, v2 / v1 if v1 else float("nan"), v15 / v1 if v1 else float("nan")))
        row("Omega_odd (rad/s)", lambda s: s["om"])
        row("tau_odd (N·m)", lambda s: s["tau"])
        row("turns per µm", lambda s: s["turns"])
        row("v_even (µm/s)", lambda s: s["v"])
        print("\n  linear small-angle response would give R(2/1) = 2 and R(15/1) = 15.")

    # ------------------------------------------------------------------ per-head decomposition
    print("\n\n### PER-HEAD DECOMPOSITION (Stage 4)\n")
    have = [k for k in arms if math.isfinite(arms[k]["v"].get("tauPos", float("nan")))]
    print("  arms carrying per-head fields: %d of %d\n" % (len(have), len(arms)))
    if have:
        print("%7s | %14s %14s %14s | %8s %8s | %10s %14s"
              % ("eps", "sum tau+", "sum tau-", "net", "n(tau+)", "n(tau-)", "net/sum+", "|tau|/head+"))
        for eps in epss:
            g = [arms[k]["v"] for k in have if k[0] == eps]
            if not g:
                continue
            f = lambda kk: sum(x[kk] for x in g) / len(g)
            tp, tn, net, npos = f("tauPos"), f("tauNeg"), f("tau"), f("nTauPos")
            print("%7.1f | %+14.4e %+14.4e %+14.4e | %8.2f %8.2f | %10.5f %14.4e"
                  % (eps, tp, tn, net, npos, f("nTauNeg"), abs(net) / tp if tp else float("nan"),
                     tp / npos if npos else float("nan")))
        print("\n%7s | %8s %8s | %13s %13s | %14s %14s %10s"
              % ("eps", "n pull", "n drag", "tau pull", "tau drag", "tau/head pull", "tau/head drag", "same sign"))
        for eps in epss:
            g = [arms[k]["v"] for k in have if k[0] == eps]
            if not g:
                continue
            f = lambda kk: sum(x[kk] for x in g) / len(g)
            npl, ndr = f("nPull"), f("nDrag")
            print("%7.1f | %8.2f %8.2f | %+13.4e %+13.4e | %+14.4e %+14.4e %10s"
                  % (eps, npl, ndr, f("tauPull"), f("tauDrag"),
                     f("tauPull") / npl if npl else float("nan"),
                     f("tauDrag") / ndr if ndr else float("nan"),
                     "YES" if f("tauPull") * f("tauDrag") > 0 else "no"))

    # ------------------------------------------------------------------ nested duration
    print("\n\n### NESTED-WINDOW DURATION CHECK (Stage 7)\n")
    print("%7s %9s | %12s %12s %12s | %10s"
          % ("eps", "win ms", "v_even", "Omega_odd", "turns/µm", "rollR2"))
    for eps in epss:
        S = pair.get(eps, [])
        if not S or not S[0]["P"]["nested"]:
            continue
        nw = min(len(d["P"]["nested"]) for d in S if d["P"]["nested"] and d["N"]["nested"])
        for k in range(nw):
            ve = []; oo = []; tu = []; r2 = []
            for d in S:
                if not d["P"]["nested"] or not d["N"]["nested"]:
                    continue
                Pn, Nn = d["P"]["nested"][k], d["N"]["nested"][k]
                v = 0.5 * (Pn[1] + Nn[1]); o = 0.5 * (Pn[2] - Nn[2])
                ve.append(v); oo.append(o)
                tu.append(o / (TWOPI * abs(v)) if v else float("nan"))
                r2.append(0.5 * (Pn[7] + Nn[7]))
            if ve:
                print("%7.1f %9.0f | %+12.4f %+12.2f %+12.2f | %10.3f"
                      % (eps, S[0]["P"]["nested"][k][0] * 1e3,
                         sum(ve) / len(ve), sum(oo) / len(oo), sum(tu) / len(tu), sum(r2) / len(r2)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
