#!/usr/bin/env python3
"""
Matched-seed chirality-odd torque analysis for the RIGID-filament skew study.

Reads the durable per-arm .tsv records written by ChiralSiteHarness.atpWrite and computes the
statistics the campaign requires but the in-harness report does not print: Student-t intervals,
leave-one-seed-out means, robust (median/MAD) summaries, single-seed influence, and the running
ensemble estimate versus seed count in a FIXED seed order.

Analysis only. Reads records; writes nothing; never touches the simulation.

  usage: scratch_rigid_odd.py --eps 15 --dur-ms 100 --seeds 101,102,103,104
"""
import argparse, math, os, sys

ATP_DIR = "RUN_LOGS/chiral_sites/lowatp"

# two-sided 95% Student-t critical values, df = 1..30
TCRIT = {1: 12.706, 2: 4.303, 3: 3.182, 4: 2.776, 5: 2.571, 6: 2.447, 7: 2.365, 8: 2.306,
         9: 2.262, 10: 2.228, 11: 2.201, 12: 2.179, 13: 2.160, 14: 2.145, 15: 2.131,
         16: 2.120, 17: 2.110, 18: 2.101, 19: 2.093, 20: 2.086, 21: 2.080, 22: 2.074,
         23: 2.069, 24: 2.064, 25: 2.060, 26: 2.056, 27: 2.052, 28: 2.048, 29: 2.045, 30: 2.042}


def rec_id(eps, dens, dur_ms, sgn, seed, thermostat="fdt", uM=10.0, mirror=1):
    return ("rigid_th%s_u%07.2f_e%04d_r%06.1f_d%08d_%s%d"
            % (thermostat, uM, int(round(abs(eps) * 10)), dens,
               int(round(dur_ms * 1e3)), "p_" if sgn > 0 else "n_", seed))


def read(path):
    if not os.path.exists(path):
        return None
    v, complete, prov = {}, False, ""
    with open(path) as f:
        for line in f:
            if line.startswith("#"):
                prov = line[1:].strip()
                continue
            p = line.rstrip("\n").split("\t")
            if len(p) != 2:
                continue
            if p[0] == "COMPLETE":
                complete = True
                continue
            try:
                v[p[0]] = float(p[1])
            except ValueError:
                pass
    if not complete:
        return None
    v["_prov"] = prov
    return v


def stats(x):
    """mean, SD (sample), SEM, |m|/SEM, sign fraction of the modal sign, t95 half-width."""
    n = len(x)
    if n == 0:
        return dict(n=0)
    m = sum(x) / n
    if n > 1:
        sd = math.sqrt(sum((a - m) ** 2 for a in x) / (n - 1))
        sem = sd / math.sqrt(n)
    else:
        sd = sem = float("nan")
    sig = abs(m) / sem if sem and sem == sem and sem > 0 else float("nan")
    pos = sum(1 for a in x if a > 0)
    neg = sum(1 for a in x if a < 0)
    agree = max(pos, neg) / n
    # fraction agreeing with the MEAN's sign (the campaign's "sign agreement")
    ms = 1 if m > 0 else -1
    with_mean = sum(1 for a in x if (a > 0) == (ms > 0)) / n
    t = TCRIT.get(n - 1, 1.96) if n > 1 else float("nan")
    half = t * sem if sem == sem else float("nan")
    s = sorted(x)
    med = s[n // 2] if n % 2 else 0.5 * (s[n // 2 - 1] + s[n // 2])
    mad = sorted(abs(a - med) for a in x)
    madv = mad[n // 2] if n % 2 else 0.5 * (mad[n // 2 - 1] + mad[n // 2])
    tot = sum(abs(a) for a in x)
    infl = max(abs(a) for a in x) / tot if tot > 0 else float("nan")
    return dict(n=n, mean=m, sd=sd, sem=sem, sigma=sig, agree=agree, with_mean=with_mean,
                lo=m - half, hi=m + half, med=med, mad=madv, infl=infl, t=t)


def row(label, x, fmt="%+13.6e"):
    s = stats(x)
    if s["n"] == 0:
        print("    %-26s (no data)" % label)
        return s
    print(("    %-26s " + fmt + " +- " + fmt + "   |m|/SEM %6.2f   sign %3.0f%%   95%%CI [" + fmt + ", " + fmt + "]")
          % (label, s["mean"], s["sem"], s["sigma"], 100 * s["with_mean"], s["lo"], s["hi"]))
    return s


def loo(x):
    """leave-one-seed-out means."""
    n = len(x)
    return [(sum(x) - x[i]) / (n - 1) for i in range(n)] if n > 1 else []


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--eps", type=float, required=True)
    ap.add_argument("--dur-ms", type=float, required=True)
    ap.add_argument("--seeds", required=True)
    ap.add_argument("--density", type=float, default=400.0)
    ap.add_argument("--dir", default=ATP_DIR)
    a = ap.parse_args()
    seeds = [int(s) for s in a.seeds.split(",")]

    pairs, missing = [], []
    for sd in seeds:
        p = read(os.path.join(a.dir, rec_id(a.eps, a.density, a.dur_ms, +1, sd) + ".tsv"))
        m = read(os.path.join(a.dir, rec_id(a.eps, a.density, a.dur_ms, -1, sd) + ".tsv"))
        if p is None or m is None:
            missing.append((sd, p is None, m is None))
            continue
        pairs.append((sd, p, m))

    print("=" * 108)
    print("  RIGID matched-seed odd-torque analysis   eps = +-%.1f deg   duration = %.0f ms/arm"
          "   density = %.0f heads/um^2" % (a.eps, a.dur_ms, a.density))
    print("  matched seeds present: %d of %d   %s"
          % (len(pairs), len(seeds), "" if not missing else "MISSING: %s" % missing))
    if not pairs:
        sys.exit(1)
    print("  provenance (first arm): %s" % pairs[0][1]["_prov"][:190])
    print("=" * 108)

    def odd(k):
        return [0.5 * (p[k] - m[k]) for _, p, m in pairs]

    def even(k):
        return [0.5 * (p[k] + m[k]) for _, p, m in pairs]

    def arms(k):
        return [x[k] for _, p, m in pairs for x in (p, m)]

    # ---------------------------------------------------------------- health
    inv = sum(arms("invalid"))
    sf = sum(arms("solverFail"))
    print("\n  ---- NUMERICAL HEALTH (all %d arms) ----" % (2 * len(pairs)))
    print("    invalid = %.0f     solverFail = %.0f     rateCapWarns = %.0f     ruptureEvents = %.0f"
          % (inv, sf, sum(arms("rateCapWarns")), sum(arms("ruptureEvents"))))
    gr = pairs[0][1]["gammaRoll"]
    print("    gamma_roll = %.6e N.m.s     M_roll = %.6e rad/(s.N.m)" % (gr, 1.0 / gr))

    # ---------------------------------------------------------------- primary
    print("\n  ---- PRIMARY: chirality-odd deterministic motor torque ----")
    st_tau = row("tau_odd  (N.m)", odd("tauDet"))
    row("tau_even (N.m)", even("tauDet"))
    print("\n  ---- SECONDARY (derived): motor-driven angular drift ----")
    st_om = row("Om_drive_odd (rad/s)", odd("omDrive"), fmt="%+13.5f")
    row("Om_drive_even (rad/s)", even("omDrive"), fmt="%+13.5f")
    print("\n  ---- CORROBORATING ----")
    row("Om_total_odd (rad/s)", odd("omTotalDecomp"), fmt="%+13.5f")
    row("Om_Brown_odd (rad/s)", odd("omBrown"), fmt="%+13.5e")
    row("Om_geom_odd  (rad/s)", odd("omGeom"), fmt="%+13.5e")

    # mobility closure: Om_drive_odd == M_roll * tau_odd, per seed
    mroll = 1.0 / gr
    od_t, od_o = odd("tauDet"), odd("omDrive")
    worst = max(abs(od_o[i] - mroll * od_t[i]) / max(abs(od_o[i]), 1e-300) for i in range(len(od_t)))
    print("\n    mobility closure  max_seed |Om_drive_odd - M_roll*tau_odd| / |Om_drive_odd| = %.3e" % worst)

    # ---------------------------------------------------------------- robustness
    print("\n  ---- ROBUSTNESS of tau_odd ----")
    x = odd("tauDet")
    print("    median %+13.6e   MAD %13.6e   SD %13.6e" % (st_tau["med"], st_tau["mad"], st_tau["sd"]))
    print("    largest-|seed| share of sum|tau_odd_s| = %.1f%%  (dominance guard: < 35%%)" % (100 * st_tau["infl"]))
    L = loo(x)
    if L:
        print("    leave-one-out means: %s" % "  ".join("%+.4e" % v for v in L))
        print("    LOO sign retained in %d of %d   (min |LOO| %.4e, max |LOO| %.4e)"
              % (sum(1 for v in L if (v > 0) == (st_tau["mean"] > 0)), len(L),
                 min(abs(v) for v in L), max(abs(v) for v in L)))
    h = len(x) // 2
    if h >= 2:
        f, s2 = sum(x[:h]) / h, sum(x[h:]) / (len(x) - h)
        rel = abs(f - s2) / abs(0.5 * (f + s2)) if (f + s2) else float("nan")
        print("    first-half seeds %+.4e   second-half seeds %+.4e   relative split %.1f%%" % (f, s2, 100 * rel))

    print("\n  ---- RUNNING ENSEMBLE ESTIMATE (fixed seed order %s) ----" % ",".join(str(s) for s, _, _ in pairs))
    print("    %4s %16s %14s %8s %8s" % ("n", "mean(tau_odd)", "SEM", "|m|/SEM", "sign%"))
    for n in range(2, len(x) + 1):
        s = stats(x[:n])
        print("    %4d %+16.6e %14.6e %8.2f %8.0f" % (n, s["mean"], s["sem"], s["sigma"], 100 * s["with_mean"]))

    # ---------------------------------------------------------------- per-seed
    print("\n  ---- PER-SEED MATCHED PAIRS ----")
    print("    %6s %15s %15s %14s %13s %13s %10s %10s"
          % ("seed", "tau_det(+e)", "tau_det(-e)", "tau_odd", "Om_drv_odd", "Om_tot_odd", "Nb(+e)", "Nb(-e)"))
    for i, (sd, p, m) in enumerate(pairs):
        print("    %6d %+15.6e %+15.6e %+14.6e %+13.4f %+13.4f %10.3f %10.3f"
              % (sd, p["tauDet"], m["tauDet"], 0.5 * (p["tauDet"] - m["tauDet"]),
                 0.5 * (p["omDrive"] - m["omDrive"]), 0.5 * (p["omTotalDecomp"] - m["omTotalDecomp"]),
                 p["rdBound"], m["rdBound"]))

    # ---------------------------------------------------------------- components
    print("\n  ---- COMPONENTS AND POPULATIONS (even = both signs, odd = chirality-odd) ----")
    for k, lab in [("tauBondMean", "bond-force moment  (N.m)"),
                   ("tauOtherMean", "any other determ.  (N.m)"),
                   ("tauDetPerBound", "tau per bound head (N.m)"),
                   ("rdTauPos", "positive-torque population (N.m)"),
                   ("rdTauNeg", "negative-torque population (N.m)"),
                   ("rdBound", "mean occupancy N_b"),
                   ("avgBound", "avgBound"),
                   ("glide", "gliding velocity (um/s)")]:
        e, o = stats(even(k)), stats(odd(k))
        print("    %-34s even %+13.6e   odd %+13.6e +- %11.4e  (%.2f sigma)"
              % (lab, e["mean"], o["mean"], o["sem"], o["sigma"]))
    # torque-component closure, per arm
    cl = [abs(v["tauDet"] - v["tauBondMean"] - v["tauOtherMean"]) / max(abs(v["tauDet"]), 1e-300)
          for _, p, m in pairs for v in (p, m)]
    print("    torque-component closure   max over arms |tauDet - tauBond - tauOther| / |tauDet| = %.3e" % max(cl))
    # population imbalance
    imb = [abs(0.5 * (p["rdTauPos"] + p["rdTauNeg"] + m["rdTauPos"] + m["rdTauNeg"]))
           / (0.5 * (abs(p["rdTauPos"]) + abs(m["rdTauPos"]))) for _, p, m in pairs]
    print("    net / gross positive-population ratio (even): mean %.4f%%" % (100 * sum(imb) / len(imb)))

    # ---------------------------------------------------------------- blocks
    print("\n  ---- STATIONARITY: disjoint quarter-blocks (diagnostic only, NOT replicates) ----")
    tb = [odd("tauB%d" % k) for k in range(4)]
    print("    %-22s %14s %14s %14s %14s" % ("", "Q1", "Q2", "Q3", "Q4"))
    print("    %-22s %+14.6e %+14.6e %+14.6e %+14.6e"
          % ("tau_odd per block", *[sum(b) / len(b) for b in tb]))
    flat = [v for b in tb for v in b]
    sb = stats(flat)
    print("    pooled %d disjoint blocks: %+13.6e +- %13.6e   |m|/SEM %5.2f   sign %3.0f%%"
          % (len(flat), sb["mean"], sb["sem"], sb["sigma"], 100 * sb["with_mean"]))
    h1 = [0.5 * (tb[0][i] + tb[1][i]) for i in range(len(pairs))]
    h2 = [0.5 * (tb[2][i] + tb[3][i]) for i in range(len(pairs))]
    row("tau_odd first half", h1)
    row("tau_odd second half", h2)
    print()


if __name__ == "__main__":
    main()
