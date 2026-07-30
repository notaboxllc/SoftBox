#!/usr/bin/env python3
"""Low-ATP BOUND-BROWNIAN-OFF skew pilot analysis.

Compares the DETACHED_SEARCH_ONLY (mechanical-Brownian-suppressed) arms against the stored
Brownian-ON arms at the SAME [ATP], duration and skews.

Three record vintages are read, and the Brownian mode is taken from the id, never guessed:
  * suppressed   atp_u<uM>_e<deg*10>_bdso_d<dur_us>_[m_]{p,n,z}_<seed>     (this pilot)
  * Brownian-on  atp_u<uM>_e<deg*10>_d<dur_us>_[m_]{p,n,z}_<seed>          (the small-skew pilot: 0/1/2 deg)
  * Brownian-on  atp_u<uM>_d<dur_us>_[m_]{p,n,z}_<seed>                    (the legacy 15 deg campaign)
The legacy untagged form is accepted ONLY as eps = 15, Brownian ON -- the condition it was run at.

Nothing is copied, moved or rewritten: the reference directories are read in place, read-only.

Usage:
  python3 scripts/lowatp_boundbrown_analysis.py \
      --rec-dir RUN_LOGS/chiral_sites/lowatp \
      --ref-dir /home/jba/Code/softbox-lowatp-small-skew/RUN_LOGS/chiral_sites/lowatp \
      --ref-dir /home/jba/Code/SoftBox/RUN_LOGS/chiral_sites/lowatp
"""
import argparse, glob, math, os, re, sys
from collections import defaultdict

DSO_RE = re.compile(r"atp_u(?P<uM>[0-9.]+)_e(?P<eps>\d{4})_bdso_d(?P<dur>\d+)_(?P<mir>m_)?(?P<sgn>[pnz])_(?P<seed>\d+)$")
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


def load(dirs, dur_us, only_uM):
    """-> {(brown, eps, sgn, seed): rec}. brown in {'off','on'}; first directory wins on collision."""
    out = {}
    for d in dirs:
        for path in sorted(glob.glob(os.path.join(d, "*.tsv"))):
            base = os.path.basename(path)[:-4]
            if base.endswith(".nested") or base.endswith(".trace"):
                continue
            brown, eps, m = None, None, None
            m = DSO_RE.match(base)
            if m:
                brown, eps = "off", int(m.group("eps")) / 10.0
            else:
                m = NEW_RE.match(base)
                if m:
                    brown, eps = "on", int(m.group("eps")) / 10.0
                else:
                    m = OLD_RE.match(base)
                    if m:
                        brown, eps = "on", LEGACY_EPS
            if not m or m.group("mir") or int(m.group("dur")) != dur_us:
                continue
            if abs(float(m.group("uM")) - only_uM) > 1e-9:
                continue
            vals, prov = read_record(path)
            if vals is None:
                continue
            key = (brown, eps, m.group("sgn"), int(m.group("seed")))
            if key in out:
                continue
            out[key] = dict(v=vals, prov=prov, base=base, dir=d,
                            nested=read_nested(os.path.join(d, base + ".nested.tsv")))
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


def pairs_of(arms, brown, eps):
    """Matched +eps / -eps seed pairs -> list of dicts of the odd/even decomposition."""
    out = []
    for sd in sorted({k[3] for k in arms if k[0] == brown and k[1] == eps}):
        P, N = arms.get((brown, eps, "p", sd)), arms.get((brown, eps, "n", sd))
        if not P or not N:
            continue
        p, n = P["v"], N["v"]
        v_even = 0.5 * (p["glide"] + n["glide"]); v_odd = 0.5 * (p["glide"] - n["glide"])
        om_odd = 0.5 * (p["omegaFit"] - n["omegaFit"]); om_even = 0.5 * (p["omegaFit"] + n["omegaFit"])
        tau_odd = 0.5 * (p["tau"] - n["tau"]); tau_even = 0.5 * (p["tau"] + n["tau"])
        turns = om_odd / (TWOPI * abs(v_even)) if v_even else float("nan")
        out.append(dict(brown=brown, eps=eps, seed=sd, v_even=v_even, v_odd=v_odd,
                        om_odd=om_odd, om_even=om_even, tau_odd=tau_odd, tau_even=tau_even,
                        turns=turns, pitch=1.0 / turns if turns else float("nan"), P=P, N=N))
    return out


def gamma_fil(arms):
    """Whole-filament roll drag, derived per record and cross-checked, NOT assumed.

    Each record stores tau, omega and qOmega with qOmega = omega/omegaPred and omegaPred = tau/gamma, so
    tau*qOmega/omega == gamma identically. WHICH gamma depends on the record's vintage: records written
    after the whole-filament-roll-drag fix carry gamma_FILAMENT, older ones carry
    gamma_SEGMENT = gamma_filament / NSEG.

    Taking min() over the set and multiplying by NSEG -- the parent pilot's method -- only lands on the
    right answer when at least one OLD-vintage record is present to be the minimum. On an all-new-vintage
    set, which is exactly what this pilot produces, it inflates gamma by NSEG and corrupts every closure
    number. So: cluster the per-record values, take the LARGEST cluster centre as gamma_filament, and
    report any record that matches neither gamma_filament nor gamma_filament/NSEG."""
    graw = {}
    for k, d in arms.items():
        v = d["v"]
        if v.get("omega"):
            graw[k] = v["tau"] * v["qOmega"] / v["omega"]
    if not graw:
        return float("nan"), ([], []), []
    hi = max(graw.values())
    fil, seg, odd = [], [], []
    for k, g in graw.items():
        if abs(g / hi - 1.0) < 1e-3:
            fil.append(k)
        elif abs(g * NSEG / hi - 1.0) < 1e-3:
            seg.append(k)
        else:
            odd.append((k, g))
    return hi, (fil, seg), odd


def floor_sigma(arms, brown):
    """sigma of the SPURIOUS Omega_odd: the width of the eps=0 distribution, not one draw of it.
    Omega_odd = 0.5*(Om(+) - Om(-)); two decorrelated arms each carry the eps=0 roll, so
    sigma = 0.5*sqrt(2)*RMS(Omega at eps=0)."""
    oms = [arms[k]["v"]["omegaFit"] for k in arms if k[0] == brown and k[1] == 0.0]
    if not oms:
        return None, None, 0
    rms = math.sqrt(sum(o * o for o in oms) / len(oms))
    return 0.5 * math.sqrt(2) * rms, rms, len(oms)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--rec-dir", default="RUN_LOGS/chiral_sites/lowatp")
    ap.add_argument("--ref-dir", action="append", default=[])
    ap.add_argument("--duration-ms", type=float, default=200.0)
    ap.add_argument("--atp-uM", dest="atp_uM", type=float, default=5.0)
    a = ap.parse_args()
    dur_us = int(round(a.duration_ms * 1000))
    arms = load([a.rec_dir] + a.ref_dir, dur_us, a.atp_uM)
    if not arms:
        print("no records"); return 1

    epss = sorted({k[1] for k in arms}, reverse=True)
    print("=" * 112)
    print("LOW-ATP BOUND-BROWNIAN-OFF SKEW PILOT — %g µM, %g ms, eps in %s deg" % (a.atp_uM, a.duration_ms, epss))
    print("  brown='off' = DETACHED_SEARCH_ONLY (this pilot)   brown='on' = the stored canonical arms")
    print("=" * 112)

    # ---------------------------------------------------------------- 1. arm inventory + health
    print("\n\n### 1. ARM INVENTORY\n")
    print("%-46s %6s %7s %5s %6s %11s %11s %9s %6s %6s %6s"
          % ("record", "brown", "eps", "sgn", "seed", "glide", "OmegaFit", "avgBound",
             "inval", "solv", "rateW"))
    health = defaultdict(float)
    for k in sorted(arms, key=lambda x: (x[0], -x[1], x[2], x[3])):
        d = arms[k]; v = d["v"]
        if k[0] == "off":
            for h in ("invalid", "solverFail", "rateCapWarns"):
                health[h] += v.get(h, 0.0)
        print("%-46s %6s %7.1f %5s %6d %+11.4f %+11.2f %9.2f %6.0f %6.0f %6.0f"
              % (d["base"], k[0], k[1], k[2], k[3], v["glide"], v["omegaFit"], v["avgBound"],
                 v["invalid"], v["solverFail"], v.get("rateCapWarns", float("nan"))))
    print("\n  SUPPRESSED-arm health totals: invalid=%.0f  solverFail=%.0f  rateCapWarn=%.0f"
          % (health["invalid"], health["solverFail"], health["rateCapWarns"]))
    nbrown = defaultdict(int)
    for k in arms:
        nbrown[k[0]] += 1
    print("  arms: %s" % dict(nbrown))
    # provenance self-check: the mode recorded IN the record must match the mode in the id
    bad = []
    for k, d in arms.items():
        bm = d["v"].get("brownMode")
        if bm is not None and math.isfinite(bm):
            want = 1.0 if k[0] == "off" else 0.0
            if bm != want:
                bad.append(d["base"])
    print("  id/record Brownian-mode consistency: %s"
          % ("OK (every tagged record agrees with its stored brownMode field)" if not bad
             else "MISMATCH in " + ", ".join(bad)))

    gfil, (gfil_recs, gseg_recs), godd = gamma_fil(arms)

    # ---------------------------------------------------------------- 2. paired observables
    for brown in ("off", "on"):
        S = {e: pairs_of(arms, brown, e) for e in epss if e != 0.0}
        S = {e: v for e, v in S.items() if v}
        if not S:
            continue
        print("\n\n### 2%s. PAIRED OBSERVABLES — Brownian %s\n" % ("a" if brown == "off" else "b", brown.upper()))
        print("%7s %6s | %10s %10s | %11s %11s | %12s %12s | %11s %11s | %9s %9s"
              % ("eps", "seed", "v_even", "v_odd", "Omega_odd", "Omega_even", "tau_odd", "tau_even",
                 "turns/µm", "pitch µm", "R2(+)", "R2(-)"))
        for e in sorted(S, reverse=True):
            for d in S[e]:
                print("%7.1f %6d | %+10.4f %+10.4f | %+11.2f %+11.2f | %+12.4e %+12.4e | %+11.2f %+11.4f | %9.3f %9.3f"
                      % (e, d["seed"], d["v_even"], d["v_odd"], d["om_odd"], d["om_even"],
                         d["tau_odd"], d["tau_even"], d["turns"], d["pitch"],
                         d["P"]["v"]["rollR2"], d["N"]["v"]["rollR2"]))

    # ---------------------------------------------------------------- 3. eps = 0 null + noise floor
    print("\n\n### 3. EPS = 0 NULL AND THE NOISE FLOOR (never enters the odd decomposition)\n")
    print("%6s %6s | %11s %13s %9s %11s %10s" % ("brown", "seed", "OmegaFit", "tau", "rollR2", "glide", "avgBound"))
    for brown in ("off", "on"):
        for k in sorted(k for k in arms if k[0] == brown and k[1] == 0.0):
            v = arms[k]["v"]
            print("%6s %6d | %+11.2f %+13.4e %9.3f %+11.4f %10.2f"
                  % (brown, k[3], v["omegaFit"], v["tau"], v["rollR2"], v["glide"], v["avgBound"]))
    sig = {}
    for brown in ("off", "on"):
        s, rms, n = floor_sigma(arms, brown)
        sig[brown] = s
        if s is not None:
            print("\n  brown=%s : eps=0 per-arm |Omega| RMS = %.3f rad/s over n=%d  =>  "
                  "sigma(spurious Omega_odd) = 0.5*sqrt(2)*RMS = %.3f rad/s" % (brown, rms, n, s))
    if sig.get("off") and sig.get("on"):
        print("  ROLL-NOISE FLOOR REDUCTION (suppressed vs canonical): %.1fx" % (sig["on"] / sig["off"]))
    print("\n  CAVEAT ON THIS FLOOR FORMULA, which matters for the suppressed mode. It assumes the two arms of")
    print("  a +/-eps pair DECORRELATE over the window, so their eps=0 rolls add in quadrature. That holds with")
    print("  thermal forcing. With mechanical Brownian suppressed the paired arms share a lawn and RNG streams")
    print("  and stay strongly CORRELATED, so much of the achiral roll CANCELS in the odd combination and the")
    print("  true floor is SMALLER than this. The number above is therefore an UPPER BOUND on the suppressed")
    print("  floor -- conservative for claiming a signal, and the same estimator as the canonical mode, so the")
    print("  two are comparable. The same-seed table in 3b is the assumption-free version.")

    # ------------------------------------------------- 3b. SAME-SEED comparison against the achiral null
    # The sharpest test available, and it needs no floor model: at a FIXED seed, how far does a +/-eps arm
    # sit from the eps=0 arm that shares its seed, its lawn and its RNG streams? If a skewed arm is
    # indistinguishable from its own achiral null, there is nothing at that skew to resolve.
    print("\n\n### 3b. EACH ARM vs THE ACHIRAL NULL AT THE SAME SEED\n")
    print("%6s %7s %5s %6s | %11s %11s | %13s %13s | %11s %11s"
          % ("brown", "eps", "sgn", "seed", "Omega", "d vs null", "tau", "d vs null", "glide", "d vs null"))
    for brown in ("off", "on"):
        nulls = {k[3]: arms[k]["v"] for k in arms if k[0] == brown and k[1] == 0.0}
        if not nulls:
            continue
        for eps in sorted([x for x in epss if x != 0.0], reverse=True):
            for sgn in ("p", "n"):
                for sd in sorted({k[3] for k in arms if k[0] == brown and k[1] == eps}):
                    d = arms.get((brown, eps, sgn, sd))
                    z = nulls.get(sd)
                    if not d or not z:
                        continue
                    v = d["v"]
                    print("%6s %7.1f %5s %6d | %+11.3f %+11.3f | %+13.4e %+13.4e | %+11.4f %+11.4f"
                          % (brown, eps, sgn, sd, v["omegaFit"], v["omegaFit"] - z["omegaFit"],
                             v["tau"], v["tau"] - z["tau"], v["glide"], v["glide"] - z["glide"]))
    print("\n  'd vs null' is the SAME-SEED difference from the eps = 0 arm. A skew that changes nothing")
    print("  relative to its own achiral null cannot be producing a resolvable chiral response.")

    # ---- the floor-free discriminator: is the +/-eps deviation from the shared null ANTISYMMETRIC? -------
    # A genuine chiral response moves +eps and -eps in OPPOSITE directions away from the achiral null they
    # both share. A common-mode effect (drift, achiral mechanics, trajectory scatter) moves them the SAME
    # way. Define  A = (d+ + d-) / (|d+| + |d-|):  A ~ 0 => antisymmetric => chiral;
    # |A| ~ 1 => both deviations share a sign => common-mode, NOT chiral. No noise model is needed.
    print("\n  ANTISYMMETRY OF THE +/-eps DEVIATION FROM THE SHARED NULL  (A ~ 0 chiral, |A| ~ 1 common-mode)\n")
    print("%6s %7s %6s | %11s %11s %9s | %13s %13s %9s"
          % ("brown", "eps", "seed", "dOmega(+)", "dOmega(-)", "A_Omega", "dtau(+)", "dtau(-)", "A_tau"))
    for brown in ("off", "on"):
        nulls = {k[3]: arms[k]["v"] for k in arms if k[0] == brown and k[1] == 0.0}
        for eps in sorted([x for x in epss if x != 0.0], reverse=True):
            for sd in sorted({k[3] for k in arms if k[0] == brown and k[1] == eps}):
                P, N, z = arms.get((brown, eps, "p", sd)), arms.get((brown, eps, "n", sd)), nulls.get(sd)
                if not P or not N or not z:
                    continue
                dop = P["v"]["omegaFit"] - z["omegaFit"]; don = N["v"]["omegaFit"] - z["omegaFit"]
                dtp = P["v"]["tau"] - z["tau"];           dtn = N["v"]["tau"] - z["tau"]
                ao = (dop + don) / (abs(dop) + abs(don)) if (abs(dop) + abs(don)) > 0 else float("nan")
                at = (dtp + dtn) / (abs(dtp) + abs(dtn)) if (abs(dtp) + abs(dtn)) > 0 else float("nan")
                print("%6s %7.1f %6d | %+11.3f %+11.3f %+9.3f | %+13.4e %+13.4e %+9.3f"
                      % (brown, eps, sd, dop, don, ao, dtp, dtn, at))

    # ---------------------------------------------------------------- 4. per-skew summary vs floor
    print("\n\n### 4. PER-SKEW SUMMARY AND THE FLOOR TEST\n")
    print("%6s %7s | %22s %7s %6s | %24s %7s | %12s %12s | %8s"
          % ("brown", "eps", "Omega_odd mean±SEM", "spread", "sign", "tau_odd mean±SEM", "sign",
             "turns/µm", "pitch µm", "sigma"))
    summ = {}
    for brown in ("off", "on"):
        for e in sorted([x for x in epss if x != 0.0], reverse=True):
            S = pairs_of(arms, brown, e)
            if not S:
                continue
            om = [d["om_odd"] for d in S]; ta = [d["tau_odd"] for d in S]; tu = [d["turns"] for d in S]
            mo, so, no = msn(om); mt, st, _ = msn(ta); mu, su, _ = msn(tu)
            mags = [abs(x) for x in om]
            same_o = all(x * mo > 0 for x in om); same_t = all(x * mt > 0 for x in ta)
            nsig = abs(mo) / sig[brown] if sig.get(brown) else float("nan")
            summ[(brown, e)] = dict(om=mo, om_sem=so, tau=mt, tau_sem=st, turns=mu, turns_sem=su,
                                    same_o=same_o, same_t=same_t, n=no, nsig=nsig,
                                    v=msn([d["v_even"] for d in S])[0],
                                    v_sem=msn([d["v_even"] for d in S])[1], S=S)
            print("%6s %7.1f | %+13.2f±%8.2f %7.2f %6s | %+15.4e±%8.1e %7s | %+12.2f %+12.4f | %8.2f"
                  % (brown, e, mo, so, max(mags) / min(mags) if min(mags) > 0 else float("nan"),
                     "same" if same_o else "SPLIT", mt, st, "same" if same_t else "SPLIT",
                     mu, 1.0 / mu if mu else float("nan"), nsig))
    print("\n  sigma column = |Omega_odd| / sigma(spurious Omega_odd) of the SAME Brownian mode.")

    # ---------------------------------------------------------------- 5. torque-rotation closure
    print("\n\n### 5. TORQUE-ROTATION CLOSURE (whole-filament roll drag)\n")
    print("  gamma_filament = %.6e N·m·s   (derived per record as tau*qOmega/omega, not assumed)" % gfil)
    print("    records carrying gamma_filament: %d ; records carrying gamma_filament/NSEG (old vintage): %d"
          % (len(gfil_recs), len(gseg_recs)))
    if godd:
        print("    *** records matching NEITHER (closure for these is not trustworthy):")
        for k, g in godd:
            print("        %s eps=%g %s seed=%d -> gamma = %.6e" % (k[0], k[1], k[2], k[3], g))
    print("%6s %7s %6s | %14s %13s %13s %10s" % ("brown", "eps", "seed", "tau_odd", "Om_odd meas",
                                                 "Om_odd pred", "meas/pred"))
    closure = defaultdict(list)
    for brown in ("off", "on"):
        for e in sorted([x for x in epss if x != 0.0], reverse=True):
            for d in pairs_of(arms, brown, e):
                pred = d["tau_odd"] / gfil
                r = d["om_odd"] / pred if pred else float("nan")
                closure[(brown, e)].append(r)
                print("%6s %7.1f %6d | %+14.4e %+13.2f %+13.2f %10.3f"
                      % (brown, e, d["seed"], d["tau_odd"], d["om_odd"], pred, r))
    print()
    for key in sorted(closure, key=lambda x: (x[0], -x[1])):
        m, s, n = msn(closure[key])
        print("  brown=%-3s eps=%4.1f : meas/pred = %.3f ± %.3f (n=%d)" % (key[0], key[1], m, s, n))
    print("\n  closure ~1 means the measured rotation is accounted for by the accumulated torque;")
    print("  >>1 means rotation is present that the torque cannot explain (residual roll noise).")

    # ---------------------------------------------------------------- 6. mechanism decomposition
    print("\n\n### 6. MECHANISM DECOMPOSITION (per-head, arm-averaged)\n")
    print("%6s %7s | %14s %14s %14s | %8s %8s | %10s %14s"
          % ("brown", "eps", "sum tau+", "sum tau-", "net", "n(tau+)", "n(tau-)", "net/sum+", "|tau|/head+"))
    for brown in ("off", "on"):
        for e in epss:
            g = [arms[k]["v"] for k in arms if k[0] == brown and k[1] == e
                 and math.isfinite(arms[k]["v"].get("tauPos", float("nan")))]
            if not g:
                continue
            f = lambda kk: sum(x[kk] for x in g) / len(g)
            tp, tn, net, npos = f("tauPos"), f("tauNeg"), f("tau"), f("nTauPos")
            print("%6s %7.1f | %+14.4e %+14.4e %+14.4e | %8.2f %8.2f | %10.5f %14.4e"
                  % (brown, e, tp, tn, net, npos, f("nTauNeg"),
                     abs(net) / tp if tp else float("nan"), tp / npos if npos else float("nan")))
    print("\n%6s %7s | %8s %8s | %13s %13s | %14s %14s %10s"
          % ("brown", "eps", "n pull", "n drag", "tau pull", "tau drag", "tau/head pull", "tau/head drag", "same sign"))
    for brown in ("off", "on"):
        for e in epss:
            g = [arms[k]["v"] for k in arms if k[0] == brown and k[1] == e
                 and math.isfinite(arms[k]["v"].get("nPull", float("nan")))]
            if not g:
                continue
            f = lambda kk: sum(x[kk] for x in g) / len(g)
            npl, ndr = f("nPull"), f("nDrag")
            print("%6s %7.1f | %8.2f %8.2f | %+13.4e %+13.4e | %+14.4e %+14.4e %10s"
                  % (brown, e, npl, ndr, f("tauPull"), f("tauDrag"),
                     f("tauPull") / npl if npl else float("nan"),
                     f("tauDrag") / ndr if ndr else float("nan"),
                     "YES" if f("tauPull") * f("tauDrag") > 0 else "no"))
    print("\n%6s %7s | %12s %12s %12s %12s | %12s %12s | %10s %10s"
          % ("brown", "eps", "tau NONE", "tau ATP", "tau ADP·Pi", "tau ADP", "tau pre", "tau post",
             "residence", "attach/s"))
    for brown in ("off", "on"):
        for e in epss:
            g = [arms[k]["v"] for k in arms if k[0] == brown and k[1] == e
                 and math.isfinite(arms[k]["v"].get("tauSAdpPi", float("nan")))]
            if not g:
                continue
            f = lambda kk: sum(x[kk] for x in g) / len(g)
            print("%6s %7.1f | %+12.3e %+12.3e %+12.3e %+12.3e | %+12.3e %+12.3e | %10.3e %10.1f"
                  % (brown, e, f("tauSNone"), f("tauSAtp"), f("tauSAdpPi"), f("tauSAdp"),
                     f("tauPre"), f("tauPost"), f("residenceS"), f("bindsPerS")))

    # ---------------------------------------------------------------- 7. engagement / occupancy
    print("\n\n### 7. ENGAGEMENT, OCCUPANCY, KINETICS (arm-averaged)\n")
    print("%6s %7s | %9s %10s %10s %10s | %10s %10s %10s | %11s %11s %11s %11s"
          % ("brown", "eps", "avgBound", "binds/s", "detach/s", "strokes/s", "preLife s",
             "postLife s", "resid s", "occ NONE", "occ ATP", "occ ADPPi", "occ ADP"))
    for brown in ("off", "on"):
        for e in epss:
            g = [arms[k]["v"] for k in arms if k[0] == brown and k[1] == e]
            if not g:
                continue
            f = lambda kk: sum(x.get(kk, float("nan")) for x in g) / len(g)
            print("%6s %7.1f | %9.3f %10.1f %10.1f %10.1f | %10.3e %10.3e %10.3e | %11.4f %11.4f %11.4f %11.4f"
                  % (brown, e, f("avgBound"), f("bindsPerS"), f("detachPerS"), f("strokeRatePerS"),
                     f("preLifeS"), f("postLifeS"), f("residenceS"),
                     f("occNoneB"), f("occAtpB"), f("occAdpPiB"), f("occAdpB")))

    # ---------------------------------------------------------------- 8. nested windows
    print("\n\n### 8. NESTED-WINDOW STABILITY (prefixes of the SAME trajectory)\n")
    print("%6s %7s %9s | %12s %12s %12s | %10s %10s"
          % ("brown", "eps", "win ms", "v_even", "Omega_odd", "turns/µm", "rollR2", "sign"))
    for brown in ("off", "on"):
        for e in sorted([x for x in epss if x != 0.0], reverse=True):
            S = pairs_of(arms, brown, e)
            S = [d for d in S if d["P"]["nested"] and d["N"]["nested"]]
            if not S:
                continue
            nw = min(len(d["P"]["nested"]) for d in S)
            seq = []
            for k in range(nw):
                ve, oo, tu, r2 = [], [], [], []
                for d in S:
                    Pn, Nn = d["P"]["nested"][k], d["N"]["nested"][k]
                    v = 0.5 * (Pn[1] + Nn[1]); o = 0.5 * (Pn[2] - Nn[2])
                    ve.append(v); oo.append(o)
                    tu.append(o / (TWOPI * abs(v)) if v else float("nan"))
                    r2.append(0.5 * (Pn[7] + Nn[7]))
                mo = sum(oo) / len(oo)
                seq.append(mo)
                print("%6s %7.1f %9.0f | %+12.4f %+12.2f %+12.2f | %10.3f %10s"
                      % (brown, e, S[0]["P"]["nested"][k][0] * 1e3, sum(ve) / len(ve), mo,
                         sum(tu) / len(tu), sum(r2) / len(r2), "+" if mo > 0 else "-"))
            flips = sum(1 for i in range(1, len(seq)) if seq[i] * seq[i - 1] < 0)
            print("        -> sign flips across nested windows: %d ; last/first = %s"
                  % (flips, "%.3g" % (seq[-1] / seq[0]) if seq[0] else "n/a"))

    # ---------------------------------------------------------------- 9. Brownian-on vs suppressed
    print("\n\n### 9. BROWNIAN-ON vs BROWNIAN-SUPPRESSED\n")
    print("%7s %-16s %16s %16s %10s" % ("eps", "quantity", "Brownian ON", "SUPPRESSED", "ratio"))
    def cmp_row(e, name, getter):
        A = summ.get(("on", e)); B = summ.get(("off", e))
        if not A or not B:
            return
        a, b = getter(A), getter(B)
        print("%7.1f %-16s %+16.5g %+16.5g %10s"
              % (e, name, a, b, "%.3f" % (b / a) if a else "n/a"))
    for e in sorted([x for x in epss if x != 0.0], reverse=True):
        cmp_row(e, "v_even", lambda s: s["v"])
        cmp_row(e, "Omega_odd", lambda s: s["om"])
        cmp_row(e, "Omega_odd SEM", lambda s: s["om_sem"])
        cmp_row(e, "tau_odd", lambda s: s["tau"])
        cmp_row(e, "tau_odd SEM", lambda s: s["tau_sem"])
        cmp_row(e, "turns/µm", lambda s: s["turns"])
        cmp_row(e, "sigma vs floor", lambda s: s["nsig"])
        A, B = closure.get(("on", e)), closure.get(("off", e))
        if A and B:
            print("%7.1f %-16s %+16.5g %+16.5g %10.3f"
                  % (e, "closure", msn(A)[0], msn(B)[0], msn(B)[0] / msn(A)[0] if msn(A)[0] else float("nan")))
        for key, lab in (("tauPos", "sum tau+"), ("avgBound", "avgBound"), ("residenceS", "residence s"),
                         ("bindsPerS", "attach/s")):
            gs = {}
            for brown in ("on", "off"):
                g = [arms[k]["v"] for k in arms if k[0] == brown and k[1] == e
                     and math.isfinite(arms[k]["v"].get(key, float("nan")))]
                gs[brown] = sum(x[key] for x in g) / len(g) if g else float("nan")
            if math.isfinite(gs["on"]) and math.isfinite(gs["off"]):
                print("%7.1f %-16s %+16.5g %+16.5g %10.3f"
                      % (e, lab, gs["on"], gs["off"], gs["off"] / gs["on"] if gs["on"] else float("nan")))
        # cancellation residual
        res = {}
        for brown in ("on", "off"):
            g = [arms[k]["v"] for k in arms if k[0] == brown and k[1] == e
                 and math.isfinite(arms[k]["v"].get("tauPos", float("nan")))]
            if g:
                tp = sum(x["tauPos"] for x in g) / len(g); net = sum(x["tau"] for x in g) / len(g)
                res[brown] = abs(net) / tp if tp else float("nan")
        if len(res) == 2:
            print("%7.1f %-16s %+16.5g %+16.5g %10.3f"
                  % (e, "cancel residual", res["on"], res["off"],
                     res["off"] / res["on"] if res["on"] else float("nan")))
        print()

    # ---------------------------------------------------------------- 10. small-angle response
    print("\n### 10. SMALL-ANGLE RESPONSE (suppressed mode)\n")
    print("%-24s %14s %14s %14s | %10s %10s" % ("quantity", "eps=1", "eps=2", "eps=15", "R(2/1)", "R(15/1)"))
    def srow(name, f):
        vals = []
        for e in (1.0, 2.0, 15.0):
            s = summ.get(("off", e))
            vals.append(f(s) if s else float("nan"))
        v1, v2, v15 = vals
        print("%-24s %+14.5g %+14.5g %+14.5g | %10s %10s"
              % (name, v1, v2, v15,
                 "%.3f" % (v2 / v1) if v1 else "n/a", "%.3f" % (v15 / v1) if v1 else "n/a"))
    srow("Omega_odd (rad/s)", lambda s: s["om"])
    srow("tau_odd (N·m)", lambda s: s["tau"])
    srow("turns per µm", lambda s: s["turns"])
    srow("v_even (µm/s)", lambda s: s["v"])
    srow("sigma vs floor", lambda s: s["nsig"])
    print("\n  linear small-angle response would give R(2/1) = 2 and R(15/1) = 15.")
    print("\n  per-seed sign table (a resolved small-skew response needs a consistent sign AND torque support):")
    print("%7s | %-28s | %-28s" % ("eps", "Omega_odd per seed", "tau_odd per seed"))
    for e in sorted([x for x in epss if x != 0.0], reverse=True):
        s = summ.get(("off", e))
        if not s:
            continue
        print("%7.1f | %-28s | %-28s"
              % (e, " ".join("%+.2f" % d["om_odd"] for d in s["S"]),
                 " ".join("%+.2e" % d["tau_odd"] for d in s["S"])))

    # t-based CI on Omega_odd, suppressed
    print("\n  95%% CI on Omega_odd (t-based, seed as the independent unit), suppressed mode:")
    for e in sorted([x for x in epss if x != 0.0], reverse=True):
        s = summ.get(("off", e))
        if not s or s["n"] < 2:
            continue
        t = T95.get(s["n"] - 1, 2.0)
        lo, hi = s["om"] - t * s["om_sem"], s["om"] + t * s["om_sem"]
        print("    eps=%4.1f : %+8.2f  [%+8.2f, %+8.2f]  excludes zero: %s"
              % (e, s["om"], lo, hi, "YES" if lo * hi > 0 else "no"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
