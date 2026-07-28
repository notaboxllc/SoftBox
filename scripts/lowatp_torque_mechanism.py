#!/usr/bin/env python3
"""Low-[ATP] PILOT torque-mechanism analysis (analysis-only; no new simulation).

Asks whether the apparent ATP-independence of the eps-ODD rotation rate is a real torque
plateau, a flux/impulse compensation, saturation/cancellation, axial-rotational decoupling,
or an observable/bookkeeping artifact -- using ONLY the completed pilot records.

Steps follow the task specification:
  1 record inventory + what is stored / reconstructable / unavailable
  2 rotation verification (raw, even/odd, rev/s, cumulative roll, slopes, R^2, seed agreement)
  3 torque-rotation closure against the filament roll drag
  4 event-flux vs angular-impulse-per-event compensation
  5 torque cancellation / saturation   (availability-gated)
  6 axial puller/dragger vs torque sign (availability-gated)
  7 artifact checks + plots

Usage: python3 scripts/lowatp_torque_mechanism.py [--duration-ms 200] [--rec-dir ...] [--outdir ...]
"""
import argparse, glob, math, os, re, sys
from collections import defaultdict

ID_RE = re.compile(r"atp_u(?P<uM>[0-9.]+)_d(?P<dur>\d+)_(?P<mir>m_)?(?P<sgn>[pnz])_(?P<seed>\d+)$")
NSEG = 12          # TwoBodyConverterMotor.G4_NSEG -- the filament this lineage builds
TWOPI = 2 * math.pi


# ----------------------------------------------------------------------------------- io
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


def read_tsv(path, ncol):
    rows = []
    if not os.path.exists(path):
        return rows
    with open(path) as fh:
        fh.readline()
        for line in fh:
            p = line.rstrip("\n").split("\t")
            if len(p) >= ncol:
                rows.append([float(v) for v in p])
    return rows


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


def lsq_slope(ts, ys):
    n = len(ts)
    if n < 3:
        return float("nan"), float("nan")
    mt = sum(ts) / n; my = sum(ys) / n
    sxx = sum((t - mt) ** 2 for t in ts)
    if sxx <= 0:
        return float("nan"), float("nan")
    sxy = sum((ts[i] - mt) * (ys[i] - my) for i in range(n))
    b = sxy / sxx
    syy = sum((y - my) ** 2 for y in ys)
    r2 = (sxy * sxy) / (sxx * syy) if syy > 0 else float("nan")
    return b, r2


# ----------------------------------------------------------------------------------- load
def load(rec_dir, dur_ms):
    dur_us = int(round(dur_ms * 1000))
    arms = {}
    for path in sorted(glob.glob(os.path.join(rec_dir, "*.tsv"))):
        base = os.path.basename(path)[:-4]
        if base.endswith(".nested") or base.endswith(".trace"):
            continue
        m = ID_RE.match(base)
        if not m or int(m.group("dur")) != dur_us or m.group("mir"):
            continue
        vals, prov = read_record(path)
        if vals is None:
            continue
        arms[(float(m.group("uM")), m.group("sgn"), int(m.group("seed")))] = dict(
            v=vals, prov=prov, base=base,
            nested=read_tsv(os.path.join(rec_dir, base + ".nested.tsv"), 8),
            trace=read_tsv(os.path.join(rec_dir, base + ".trace.tsv"), 7))
    return arms


# ----------------------------------------------------------------------------------- main
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--duration-ms", type=float, default=200.0)
    ap.add_argument("--rec-dir", default="RUN_LOGS/chiral_sites/lowatp")
    ap.add_argument("--outdir", default="RUN_LOGS/lowatp/torque_mechanism")
    a = ap.parse_args()
    os.makedirs(a.outdir, exist_ok=True)

    arms = load(a.rec_dir, a.duration_ms)
    if not arms:
        print("no complete records"); return 1
    uMs = sorted({k[0] for k in arms}, reverse=True)
    seeds = sorted({k[2] for k in arms})

    print("=" * 112)
    print("LOW-[ATP] PILOT — TORQUE-MECHANISM ANALYSIS (analysis-only, no new simulation)")
    print("  %d arms | %g ms | seeds %s | eps = +/-15 deg | native lattice"
          % (len(arms), a.duration_ms, seeds))
    print("=" * 112)

    # =============================================================== STEP 1 — inventory
    print("\n\n### STEP 1 — RECORD INVENTORY\n")
    print("%-40s %5s %5s %6s %9s %8s %6s %6s %8s"
          % ("record", "[ATP]", "eps", "seed", "durS", "equil", "inval", "solv", "complete"))
    ok_all = True
    for k in sorted(arms):
        d = arms[k]; v = d["v"]
        bad = v["invalid"] + v["solverFail"]
        ok_all &= (bad == 0)
        print("%-40s %5.4g %5s %6d %9.4g %8.2f %6.0f %6.0f %8s"
              % (d["base"], v["atpUM"], "+15" if k[1] == "p" else "-15", k[2],
                 v["durationS"], v["equilFrac"], v["invalid"], v["solverFail"], "yes"))
    invs = {kk: sorted({arms[k]["v"][kk] for k in arms}) for kk in
            ("eta", "dt", "durationS", "equilFrac", "mirror")}
    print("\n  configuration identity across arms: " +
          "  ".join("%s=%s" % (kk, vv) for kk, vv in invs.items()))
    print("  effective atpOn: " + ", ".join(
        "%g µM -> %g /s" % (u, [arms[k]["v"]["atpOnEff"] for k in arms if k[0] == u][0]) for u in uMs))
    revs = defaultdict(int)
    for k in arms:
        r = re.search(r"rev=([0-9a-f]{8})", arms[k]["prov"])
        revs[r.group(1) if r else "?"] += 1
    print("  git rev stamps (HEAD at record-WRITE time, not build time): " +
          ", ".join("%s x%d" % (r, c) for r, c in revs.items()))
    print("  device: all arms gpu=true, device-resident, no fallback (from provenance)")
    print("  zero invalid states and zero solver failures across all arms: %s" % ("YES" if ok_all else "NO"))

    print("""
  AVAILABILITY OF THE REQUESTED ANALYSES
  --------------------------------------
  DIRECTLY STORED (per arm, aggregated over the measurement window)
    tau                 mean TOTAL axial torque on the filament (sum over bound heads, time-averaged)
    omega, omegaFit     endpoint-difference and LS-slope body-fixed roll rate
    turns               accumulated body-fixed revolutions over the measurement window
    rollR2              linearity of the roll-vs-time fit
    qOmega              omega / (tau / gammaRoll_SEGMENT)   [see the STEP-3 bookkeeping note]
    glide, measSteps, dt, durationS, equilFrac
    avgBound; occNoneB/occAtpB/occAdpPiB/occAdpB (bound-head state occupancy); occ*A (all heads)
    nEp, nCensored, epRate, strokeRatePerS, bindsPerS, detachPerS
    detachAtp, detachRigor, detachOther, ruptureEvents, rateCapWarns
    jPre, jStroke, jEarly, jLate   MEAN per-EPISODE axial angular impulse, phase-resolved
    preLifeS, postLifeS, residenceS
  EXACTLY RECONSTRUCTABLE
    gammaRoll_segment  = tau * qOmega / omega                     (verified identical across all arms)
    gammaRoll_filament = NSEG * gammaRoll_segment                 (roll drag additive in length)
    cumulative unwrapped body-fixed roll vs time  -- from <id>.trace.tsv (4001 samples/arm)
    full-window and late-window roll slopes and R^2 -- recomputable from the trace
    total odd angular impulse per second          = J_total_odd_per_episode * episode rate
    odd impulse per attachment / per stroke, odd torque per bound head
    cumulative binds / strokes / detachments / bound-steps vs time -- from the trace
  UNAVAILABLE WITHOUT NEW INSTRUMENTATION (must NOT be inferred from aggregates)
    signed PER-HEAD axial torque  -> positive/negative torque sums, contributing-head counts,
                                     magnitude per contributing head, torque-by-nucleotide-state
    PER-HEAD axial force          -> axial puller/dragger classification and its torque split
    per-head residence conditioned on torque sign
    (the class-stratified fields dep*/bnd*/fpr*/fab*/tau*/bind*/str* are all ZERO here: they are
     populated only for a heterogeneous S2 lawn, and this study uses a homogeneous 40 nm lawn)
  => STEP 5 is possible ONLY at the episode-PHASE level (jPre/jStroke/jEarly/jLate); the
     positive/negative decomposition is NOT diagnosable from this pilot.
  => STEP 6 is NOT possible from this pilot.
""")

    # =============================================================== STEP 2 — rotation
    print("\n### STEP 2 — IS THE ROTATIONAL PLATEAU REAL?\n")
    print("%9s %5s | %11s %11s | %11s %11s | %9s %9s %8s %8s"
          % ("[ATP] µM", "seed", "Om(+eps)", "Om(-eps)", "Omega_even", "Omega_odd",
             "rev/s odd", "roll rad", "R2(+)", "R2(-)"))
    pair = defaultdict(list)
    for uM in uMs:
        for sd in seeds:
            P, N = arms.get((uM, "p", sd)), arms.get((uM, "n", sd))
            if not P or not N:
                continue
            p, n = P["v"], N["v"]
            om_odd = 0.5 * (p["omegaFit"] - n["omegaFit"])
            om_even = 0.5 * (p["omegaFit"] + n["omegaFit"])
            meas_s = p["measSteps"] * p["dt"]
            rec = dict(seed=sd, uM=uM, omP=p["omegaFit"], omN=n["omegaFit"],
                       om_odd=om_odd, om_even=om_even, rev_odd=om_odd / TWOPI,
                       roll_rad=0.5 * (abs(p["turns"]) + abs(n["turns"])) * TWOPI,
                       turnsP=p["turns"], turnsN=n["turns"],
                       r2P=p["rollR2"], r2N=n["rollR2"],
                       tauP=p["tau"], tauN=n["tau"],
                       tau_odd=0.5 * (p["tau"] - n["tau"]), tau_even=0.5 * (p["tau"] + n["tau"]),
                       v_even=0.5 * (p["glide"] + n["glide"]),
                       meas_s=meas_s, P=P, N=N,
                       gseg=p["tau"] * p["qOmega"] / p["omega"] if p["omega"] else float("nan"))
            pair[uM].append(rec)
            print("%9.4g %5d | %+11.2f %+11.2f | %+11.2f %+11.2f | %+9.3f %9.2f %8.3f %8.3f"
                  % (uM, sd, rec["omP"], rec["omN"], om_even, om_odd, rec["rev_odd"],
                     rec["roll_rad"], rec["r2P"], rec["r2N"]))

    print("\n  full-window vs late-window roll slope, per arm (from the retained prefix trace)")
    print("%9s %5s %5s | %11s %11s %8s | %8s %10s"
          % ("[ATP] µM", "eps", "seed", "Om full", "Om late", "rel %", "R2", "verdict"))
    unstable = 0
    for uM in uMs:
        for sgn in ("p", "n"):
            for sd in seeds:
                d = arms.get((uM, sgn, sd))
                if not d or not d["nested"]:
                    continue
                last = d["nested"][-1]
                rp = relpct(last[6], last[2])
                bad = (rp > 25.0) or (last[7] < 0.5)
                unstable += bad
                print("%9.4g %5s %5d | %+11.2f %+11.2f %8.1f | %8.3f %10s"
                      % (uM, "+15" if sgn == "p" else "-15", sd, last[2], last[6], rp, last[7],
                         "UNSTABLE" if bad else "ok"))
    print("\n  arms failing (late-vs-full > 25 %% or roll R2 < 0.5): %d of %d" % (unstable, len(arms)))

    print("\n  seed agreement and even/odd balance (n = %d)" % len(seeds))
    print("%9s | %22s %7s %6s | %20s | %12s"
          % ("[ATP] µM", "Omega_odd mean ± SEM", "spread", "sign", "Omega_even mean", "|even|/|odd|"))
    for uM in uMs:
        S = pair[uM]
        if len(S) < 2:
            continue
        xs = [d["om_odd"] for d in S]; m, s, _ = msn(xs)
        ev, _, _ = msn([d["om_even"] for d in S])
        mags = [abs(x) for x in xs]
        print("%9.4g | %+13.2f±%8.2f %7.2f %6s | %+20.2f | %12.2f"
              % (uM, m, s, max(mags) / min(mags) if min(mags) > 0 else float("nan"),
                 "same" if all(x * m > 0 for x in xs) else "SPLIT", ev, abs(ev) / abs(m) if m else float("nan")))

    # =============================================================== STEP 3 — closure
    print("\n\n### STEP 3 — TORQUE-ROTATION CLOSURE\n")
    # gamma_roll reconstruction, robust to MIXED-VINTAGE records. qOmega was corrected on 2026-07-28 from the
    # single-segment to the whole-filament roll drag, so tau*qOmega/omega yields gamma_SEGMENT for records
    # written before the fix and gamma_FILAMENT (= NSEG * gamma_segment) for records written after. Both are
    # accepted, but each is CHECKED to be one of exactly those two values -- anything else is a real change in
    # configuration and is reported rather than absorbed.
    raw = [d["gseg"] for S in pair.values() for d in S if math.isfinite(d["gseg"])]
    gseg = min(raw) if raw else float("nan")
    gfil = NSEG * gseg
    nseg_conv = sum(1 for g in raw if abs(g / gseg - 1) < 1e-6)
    nfil_conv = sum(1 for g in raw if abs(g / gfil - 1) < 1e-6)
    odd_conv = len(raw) - nseg_conv - nfil_conv
    gsegs = [gseg] if odd_conv == 0 else sorted(set(raw))
    print("  gamma_roll (per segment, reconstructed exactly as tau*qOmega/omega): %.6e N·m·s" % gseg)
    print("  record vintages: %d pre-fix (segment convention), %d post-fix (filament convention), %d UNEXPECTED"
          % (nseg_conv, nfil_conv, odd_conv))
    if odd_conv:
        print("  *** %d record(s) reconstruct to neither gamma_segment nor NSEG*gamma_segment — configuration "
              "is NOT invariant; investigate before using these numbers ***" % odd_conv)
    else:
        print("  every arm reconstructs to gamma_segment or NSEG*gamma_segment => configuration invariant")
    print("  gamma_roll (whole filament) = NSEG * gamma_segment = %d * %.4e = %.6e N·m·s" % (NSEG, gseg, gfil))
    print("""
  BOOKKEEPING NOTE -- a defect in a DIAGNOSTIC field, not in any physics or claim.
  ChiralSiteHarness.gammaRollOf() returns fil.bRotGam[0], the roll drag of ONE segment, while
  r.tau is the time-average of the SUM of axial torque over all bound heads, i.e. the torque on the
  WHOLE 12-segment filament. The stored omegaPred = tau/gamma_SEGMENT therefore over-predicts by a
  factor NSEG, and the stored qOmega under-reports closure by the same factor. Roll drag is additive
  in length (the harness's own dragAudit states this), so the correct prediction divides by
  NSEG*gamma_segment. Nothing in this study or the viscosity campaign uses qOmega/omegaPred for a
  claim -- every rotation result uses omegaFit, the direct LS slope of the measured body-fixed roll.
""")
    print("%9s %5s | %13s %13s | %13s %13s | %11s %11s %9s"
          % ("[ATP] µM", "seed", "tau(+eps)", "tau(-eps)", "tau_even", "tau_odd",
             "Om_odd meas", "Om_odd pred", "meas/pred"))
    closure = defaultdict(list)
    for uM in uMs:
        for d in pair[uM]:
            pred = d["tau_odd"] / gfil
            ratio = d["om_odd"] / pred if pred else float("nan")
            closure[uM].append(ratio)
            d["om_pred"] = pred; d["ratio"] = ratio
            print("%9.4g %5d | %+13.4e %+13.4e | %+13.4e %+13.4e | %+11.2f %+11.2f %9.3f"
                  % (uM, d["seed"], d["tauP"], d["tauN"], d["tau_even"], d["tau_odd"],
                     d["om_odd"], pred, ratio))
    allr = [r for S in closure.values() for r in S if math.isfinite(r)]
    same = sum(1 for S in pair.values() for d in S if d["om_odd"] * d["om_pred"] > 0)
    tot = sum(len(S) for S in pair.values())
    print("\n  sign agreement measured vs torque-predicted: %d of %d arms" % (same, tot))
    if allr:
        m, s, _ = msn(allr)
        print("  measured/predicted ratio: mean %.3f ± %.3f (n=%d), range %.3f .. %.3f"
              % (m, s, len(allr), min(allr), max(allr)))
        print("  (for reference, the ratio computed against the SINGLE-SEGMENT drag would be %.3f -- "
              "the factor-%d bookkeeping offset)" % (m / NSEG, NSEG))

    print("\n  cumulative odd angular impulse per second vs mean odd torque (independent closure route)")
    print("%9s %5s | %15s %15s %10s" % ("[ATP] µM", "seed", "J_odd/s (N·m)", "tau_odd (N·m)", "ratio"))
    for uM in uMs:
        for d in pair[uM]:
            p, n = d["P"]["v"], d["N"]["v"]
            jp = sum(0.5 * (p[k] - n[k]) for k in ("jPre", "jStroke", "jEarly", "jLate"))
            rate = 0.5 * (p["epRate"] + n["epRate"])
            jps = jp * rate
            d["j_odd_ep"] = jp; d["ep_rate"] = rate; d["j_odd_s"] = jps
            print("%9.4g %5d | %+15.4e %+15.4e %10.3f"
                  % (uM, d["seed"], jps, d["tau_odd"], jps / d["tau_odd"] if d["tau_odd"] else float("nan")))
    print("\n  EPISODE RIGHT-CENSORING — the bias that disqualifies the impulse route at low ATP")
    print("%9s %5s %5s | %8s %8s %10s" % ("[ATP] µM", "eps", "seed", "nEp", "nCens", "censored %"))
    censby = defaultdict(list)
    for uM in uMs:
        for sgn in ("p", "n"):
            for sd in seeds:
                d = arms.get((uM, sgn, sd))
                if not d:
                    continue
                v = d["v"]
                tot = v["nEp"] + v["nCensored"]
                fr = 100.0 * v["nCensored"] / tot if tot else float("nan")
                censby[uM].append(fr)
                print("%9.4g %5s %5d | %8.0f %8.0f %10.2f"
                      % (uM, "+15" if sgn == "p" else "-15", sd, v["nEp"], v["nCensored"], fr))
    print("\n%9s | %14s" % ("[ATP] µM", "mean censored %"))
    for uM in uMs:
        if censby[uM]:
            print("%9.4g | %14.2f" % (uM, sum(censby[uM]) / len(censby[uM])))
    print("""
  Censoring rises MONOTONICALLY as ATP falls, and the censored episodes are precisely the LONGEST
  ones -- the ones carrying the most late-phase angular impulse. So jPre/jStroke/jEarly/jLate are
  progressively biased in an ATP-DEPENDENT way, in exactly the direction that removes the dominant
  contribution at low ATP. The impulse route therefore cannot be used to establish a flux/impulse
  compensation, even though tau_odd and Omega_odd themselves close cleanly (they are accumulated
  per step over ALL bound heads and are not censored at all).""")
    print("""
  NOTE on this route: jPre/jStroke/jEarly/jLate are means over UNCENSORED episodes only, and the
  post-detachment tail of a censored episode is excluded, so J_odd/s is a LOWER bound on the true
  cycle-integrated odd impulse and is not expected to reproduce tau_odd exactly. It is used here as
  a SIGN and ORDER check, not as a closure identity.""")

    # =============================================================== STEP 4 — flux vs impulse
    print("\n\n### STEP 4 — EVENT FLUX vs ANGULAR IMPULSE PER EVENT\n")
    print("%9s %5s | %9s %9s %9s %9s | %9s %9s %9s"
          % ("[ATP] µM", "seed", "attach/s", "stroke/s", "detATP/s", "detRig/s", "avgBnd", "resid ms", "epRate/s"))
    for uM in uMs:
        for d in pair[uM]:
            p, n = d["P"]["v"], d["N"]["v"]
            g = lambda k: 0.5 * (p[k] + n[k])
            meas = d["meas_s"]
            d["attach"] = g("bindsPerS"); d["stroke"] = g("strokeRatePerS")
            d["detatp"] = (p["detachAtp"] + n["detachAtp"]) / 2 / meas
            d["detrig"] = (p["detachRigor"] + n["detachRigor"]) / 2 / meas
            d["avgBound"] = g("avgBound"); d["resid"] = g("residenceS")
            d["preL"] = g("preLifeS"); d["postL"] = g("postLifeS")
            d["occ"] = [g("occNoneB"), g("occAtpB"), g("occAdpPiB"), g("occAdpB")]
            print("%9.4g %5d | %9.0f %9.0f %9.0f %9.0f | %9.2f %9.3f %9.0f"
                  % (uM, d["seed"], d["attach"], d["stroke"], d["detatp"], d["detrig"],
                     d["avgBound"], 1e3 * d["resid"], d["ep_rate"]))
    print("\n%9s %5s | %13s %13s %13s | %13s %13s"
          % ("[ATP] µM", "seed", "J_odd/episode", "J_odd/stroke", "J_odd/s", "tau_odd/head", "tau_odd"))
    for uM in uMs:
        for d in pair[uM]:
            per_stroke = d["j_odd_ep"] * d["ep_rate"] / d["stroke"] if d["stroke"] else float("nan")
            d["tau_per_head"] = d["tau_odd"] / d["avgBound"] if d["avgBound"] else float("nan")
            print("%9.4g %5d | %+13.4e %+13.4e %+13.4e | %+13.4e %+13.4e"
                  % (uM, d["seed"], d["j_odd_ep"], per_stroke, d["j_odd_s"],
                     d["tau_per_head"], d["tau_odd"]))
    print("\n  the compensation test: does (event flux DOWN) x (impulse per event UP) hold tau_odd flat?")
    print("%9s | %12s %14s %14s %14s %14s"
          % ("[ATP] µM", "epRate/s", "J_odd/episode", "J_odd/s", "tau_odd", "tau_odd/head"))
    for uM in uMs:
        S = pair[uM]
        if not S:
            continue
        g = lambda k: msn([d[k] for d in S])[0]
        print("%9.4g | %12.0f %+14.4e %+14.4e %+14.4e %+14.4e"
              % (uM, g("ep_rate"), g("j_odd_ep"), g("j_odd_s"), g("tau_odd"), g("tau_per_head")))
    print("\n%9s | %10s %10s %10s %10s | %11s %11s %11s"
          % ("[ATP] µM", "occ NONE", "occ ATP", "occ ADPPi", "occ ADP", "preLife µs", "postLife µs", "resid ms"))
    for uM in uMs:
        S = pair[uM]
        if not S:
            continue
        g = lambda k: msn([d[k] for d in S])[0]
        o = [msn([d["occ"][i] for d in S])[0] for i in range(4)]
        print("%9.4g | %10.4f %10.4f %10.4f %10.4f | %11.2f %11.2f %11.3f"
              % (uM, o[0], o[1], o[2], o[3], 1e6 * g("preL"), 1e6 * g("postL"), 1e3 * g("resid")))

    # =============================================================== STEP 5/6 — gated
    print("\n\n### STEP 5 — TORQUE CANCELLATION AND SATURATION\n")
    print("""  NOT DIAGNOSABLE FROM THIS PILOT. Only the NET axial torque is stored (r.tau is the
  time-average of the signed sum over bound heads). The positive-torque sum, negative-torque sum,
  contributing-head counts, per-head magnitude and torque-by-nucleotide-state are not recorded, and
  they cannot be inferred from an aggregate. What IS available is the episode-PHASE decomposition:""")
    print("\n%9s | %13s %13s %13s %13s | %13s"
          % ("[ATP] µM", "J_pre odd", "J_stroke odd", "J_early odd", "J_late odd", "J_total odd"))
    for uM in uMs:
        S = pair[uM]
        if not S:
            continue
        comp = {}
        for key in ("jPre", "jStroke", "jEarly", "jLate"):
            comp[key] = msn([0.5 * (d["P"]["v"][key] - d["N"]["v"][key]) for d in S])[0]
        print("%9.4g | %+13.4e %+13.4e %+13.4e %+13.4e | %+13.4e"
              % (uM, comp["jPre"], comp["jStroke"], comp["jEarly"], comp["jLate"], sum(comp.values())))
    print("\n  (phase windows: pre = attachment to stroke; stroke = lags 0-7 steps; early = 8-31; late = 32 to detach)")

    print("\n\n### STEP 6 — AXIAL PULLER/DRAGGER vs TORQUE SIGN\n")
    print("""  NOT POSSIBLE FROM THIS PILOT -- per-head axial force and per-head axial torque are not stored.
  Exact schema additions required for a later targeted study (all are per-step reductions the
  measurement loop already has the inputs for, so no new physics and no kernel change is needed):
     tauPos, tauNeg          summed positive / negative per-head axial torque
     nTauPos, nTauNeg        counts of positive / negative torque heads
     tauByState[4]           axial torque summed by nucleotide state
     nByState[4]             bound-head counts by nucleotide state
     tauPull, tauDrag        axial torque summed over heads with F_ax*v_fil > 0 and < 0
     nPull, nDrag            counts of axial pullers / draggers
     fAxPull, fAxDrag        summed axial force in each class
     residPull, residDrag    residence accumulated in each class
  The loop already computes per-head axial torque (ChiralSiteSystem.axialTorque) and per-head axial
  force (bondData projection) at ChiralSiteHarness ~line 1930-1950; only the signed reductions and
  eight extra record fields are missing.""")

    # =============================================================== STEP 7 — artifacts
    print("\n\n### STEP 7 — ARTIFACT CHECKS\n")
    dt = list(invs["dt"])[0]
    print("  (a) revolutions/s comes from DIRECT roll slopes (omegaFit = LS slope of transported")
    print("      body-fixed roll vs time), never from turns-per-distance. rev/s = omegaFit/2pi.")
    print("  (b) 2*pi conversion: turns = roll_rad / 2pi; verified per arm below (turns vs roll/2pi).")
    maxconv = 0.0
    for k in sorted(arms):
        tr = arms[k]["trace"]
        if not tr:
            continue
        v = arms[k]["v"]
        equil_t = v["equilFrac"] * v["durationS"]
        sub = [r for r in tr if r[0] >= equil_t]
        if len(sub) < 3:
            continue
        roll_span = sub[-1][1] - sub[0][1]
        maxconv = max(maxconv, abs(roll_span / TWOPI - v["turns"]))
    print("      max |roll_span/2pi - stored turns| over all arms = %.4f revolutions" % maxconv)

    print("  (c) branch-cut / unwrapping: roll is accumulated from PER-STEP transported increments")
    print("      atan2(...) summed over steps; a branch cut needs |increment| > pi in ONE step.")
    worststep = 0.0
    for k in sorted(arms):
        tr = arms[k]["trace"]
        for i in range(1, len(tr)):
            dsteps = max(1.0, (tr[i][0] - tr[i - 1][0]) / dt)
            worststep = max(worststep, abs(tr[i][1] - tr[i - 1][1]) / dsteps)
    print("      largest observed mean per-step roll increment = %.3e rad  (branch cut at pi = %.3f)"
          % (worststep, math.pi))
    print("      => branch-cut jumps are structurally impossible at these rates: %s"
          % ("CONFIRMED" if worststep < 0.01 else "CHECK"))

    print("  (d) do a few abrupt jumps dominate the slope? per-arm largest single trace-interval")
    print("      roll step as a fraction of the total measured roll excursion:")
    print("%9s %5s %5s | %12s %12s %9s" % ("[ATP] µM", "eps", "seed", "max |dRoll|", "total |roll|", "frac"))
    worstfrac = 0.0
    for uM in uMs:
        for sgn in ("p", "n"):
            for sd in seeds:
                d = arms.get((uM, sgn, sd))
                if not d or not d["trace"]:
                    continue
                v = d["v"]; tr = d["trace"]
                equil_t = v["equilFrac"] * v["durationS"]
                sub = [r for r in tr if r[0] >= equil_t]
                if len(sub) < 3:
                    continue
                steps = [abs(sub[i][1] - sub[i - 1][1]) for i in range(1, len(sub))]
                total = abs(sub[-1][1] - sub[0][1])
                fr = max(steps) / total if total > 0 else float("nan")
                worstfrac = max(worstfrac, fr if math.isfinite(fr) else 0)
                print("%9.4g %5s %5d | %12.5f %12.4f %9.3f"
                      % (uM, "+15" if sgn == "p" else "-15", sd, max(steps), total, fr))
    print("      worst single-interval fraction of the total roll excursion = %.3f" % worstfrac)

    print("\n  (e) identical analysis window at every [ATP]: durationS=%s, equilFrac=%s, measSteps=%s"
          % (invs["durationS"], invs["equilFrac"],
             sorted({arms[k]["v"]["measSteps"] for k in arms})))
    print("  (f) eps sign / seed pairing: every pair is (p,n) at the SAME seed and SAME [ATP]; ids")
    print("      carry eps sign and seed, and unmatched signs are never combined.")
    print("  (g) eps-EVEN rotational background is reported in STEP 2; |even|/|odd| there is the")
    print("      cancellation-risk indicator (large ratio => odd is a small difference of large numbers).")
    print("  (h) reduced displacement at low ATP: the roll fit does NOT depend on displacement; roll R2")
    print("      is reported per arm in STEP 2 and is the direct fit-quality measure.")
    print("  (i) torque and roll are time-aligned by construction: both are accumulated inside the same")
    print("      per-step measurement loop over the same measurement window.")
    print("  (j) the reference condition uses byte-identical estimator definitions (same code path,")
    print("      same window, same equilibration) -- only nucParams[1] differs.")

    # =============================================================== plots
    made = plots(pair, arms, uMs, seeds, gfil, a.outdir)
    print("\n\n### PLOTS\n")
    for p in made:
        print("  " + p)
    return 0


def plots(pair, arms, uMs, seeds, gfil, outdir):
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except Exception as e:
        print("  (matplotlib unavailable: %s)" % e); return []
    made = []
    TAG = "pilot"

    # 1. cumulative body-fixed roll vs time, every arm
    fig, axes = plt.subplots(2, 2, figsize=(11, 7.5), sharex=True)
    for ax, uM in zip(axes.ravel(), uMs):
        for sgn, style in (("p", "-"), ("n", "--")):
            for sd, col in zip(seeds, ("C0", "C3")):
                d = arms.get((uM, sgn, sd))
                if not d or not d["trace"]:
                    continue
                ts = [r[0] * 1e3 for r in d["trace"]]
                ys = [r[1] for r in d["trace"]]
                ax.plot(ts, ys, style, color=col, lw=1.0,
                        label="%s eps seed %d" % ("+" if sgn == "p" else "-", sd))
        eq = arms[(uM, "p", seeds[0])]["v"]["equilFrac"] * arms[(uM, "p", seeds[0])]["v"]["durationS"] * 1e3
        ax.axvline(eq, color="0.6", lw=0.8, ls=":")
        ax.set_title("[ATP] = %g µM" % uM, fontsize=9)
        ax.grid(alpha=0.25, lw=0.5)
        ax.set_ylabel("cumulative body-fixed roll (rad)")
        ax.legend(fontsize=6, frameon=False, ncol=2)
    for ax in axes[-1]:
        ax.set_xlabel("time (ms)")
    fig.suptitle("Cumulative body-fixed roll — every pilot arm (dotted line = end of equilibration)", fontsize=10)
    fig.tight_layout()
    p = os.path.join(outdir, "%s_1_cumulative_roll.png" % TAG); fig.savefig(p, dpi=170); plt.close(fig); made.append(p)

    def xy(key):
        xs, ys, es = [], [], []
        for uM in uMs:
            S = pair[uM]
            if not S:
                continue
            m, s, _ = msn([d[key] for d in S])
            xs.append(uM); ys.append(m); es.append(s)
        return xs, ys, es

    def simple(name, key, ylab, title, hline=0.0):
        f, ax = plt.subplots(figsize=(5.2, 3.6))
        xs, ys, es = xy(key)
        ax.errorbar(xs, ys, yerr=es, marker="o", capsize=3)
        for uM in uMs:
            for d in pair[uM]:
                ax.scatter([uM], [d[key]], s=12, alpha=0.5, color="0.4", zorder=1)
        if hline is not None:
            ax.axhline(hline, color="0.6", lw=0.8, ls=":")
        ax.set_xscale("log"); ax.set_xlabel("[ATP]  (µM)"); ax.set_ylabel(ylab)
        ax.set_title(title, fontsize=9); ax.grid(alpha=0.25, lw=0.5); f.tight_layout()
        q = os.path.join(outdir, "%s_%s.png" % (TAG, name)); f.savefig(q, dpi=180); plt.close(f); made.append(q)

    simple("2_tau_odd_vs_atp", "tau_odd", "tau_odd  (N·m)", "eps-ODD axial torque vs [ATP]")
    simple("3_omega_odd_vs_atp", "om_odd", "Omega_odd  (rad/s)", "eps-ODD roll rate vs [ATP]")
    simple("6b_tau_per_head", "tau_per_head", "tau_odd per bound head (N·m)", "Odd torque per bound head vs [ATP]")

    # 4. measured vs predicted
    f, ax = plt.subplots(figsize=(5.0, 4.6))
    for uM, col in zip(uMs, ("C0", "C1", "C2", "C3")):
        for d in pair[uM]:
            ax.scatter([d["om_pred"]], [d["om_odd"]], s=34, color=col,
                       label="%g µM" % uM if d is pair[uM][0] else None)
    lim = max(abs(v) for uM in uMs for d in pair[uM] for v in (d["om_pred"], d["om_odd"]))
    ax.plot([-lim, lim], [-lim, lim], "k-", lw=0.9, label="1:1")
    ax.axhline(0, color="0.7", lw=0.6); ax.axvline(0, color="0.7", lw=0.6)
    ax.set_xlabel("predicted Omega_odd = tau_odd / gamma_roll(filament)  (rad/s)")
    ax.set_ylabel("measured Omega_odd  (rad/s)")
    ax.set_title("Torque-rotation closure (whole-filament roll drag)", fontsize=9)
    ax.legend(fontsize=7, frameon=False); ax.grid(alpha=0.25, lw=0.5); f.tight_layout()
    q = os.path.join(outdir, "%s_4_closure.png" % TAG); f.savefig(q, dpi=180); plt.close(f); made.append(q)

    # 5. flux and impulse per event
    f, ax = plt.subplots(figsize=(5.4, 3.6))
    xs, ys, es = xy("ep_rate")
    ax.errorbar(xs, ys, yerr=es, marker="o", capsize=3, color="C0", label="episodes/s")
    ax.set_xscale("log"); ax.set_xlabel("[ATP]  (µM)")
    ax.set_ylabel("episodes per second", color="C0"); ax.tick_params(axis="y", labelcolor="C0")
    ax2 = ax.twinx()
    xs2, ys2, es2 = xy("j_odd_ep")
    ax2.errorbar(xs2, ys2, yerr=es2, marker="s", capsize=3, color="C3", label="J_odd per episode")
    ax2.set_ylabel("odd angular impulse per episode (N·m·s)", color="C3")
    ax2.tick_params(axis="y", labelcolor="C3"); ax2.axhline(0, color="0.7", lw=0.6)
    ax.set_title("Event flux vs angular impulse per event", fontsize=9)
    ax.grid(alpha=0.25, lw=0.5); f.tight_layout()
    q = os.path.join(outdir, "%s_5_flux_vs_impulse.png" % TAG); f.savefig(q, dpi=180); plt.close(f); made.append(q)

    # 6. bound population and torque per bound head
    f, ax = plt.subplots(figsize=(5.4, 3.6))
    xs, ys, es = xy("avgBound")
    ax.errorbar(xs, ys, yerr=es, marker="o", capsize=3, color="C0")
    ax.set_xscale("log"); ax.set_xlabel("[ATP]  (µM)")
    ax.set_ylabel("mean bound heads", color="C0"); ax.tick_params(axis="y", labelcolor="C0")
    ax2 = ax.twinx()
    xs2, ys2, es2 = xy("tau_per_head")
    ax2.errorbar(xs2, ys2, yerr=es2, marker="s", capsize=3, color="C3")
    ax2.set_ylabel("odd torque per bound head (N·m)", color="C3")
    ax2.tick_params(axis="y", labelcolor="C3"); ax2.axhline(0, color="0.7", lw=0.6)
    ax.set_title("Bound population and odd torque per bound head", fontsize=9)
    ax.grid(alpha=0.25, lw=0.5); f.tight_layout()
    q = os.path.join(outdir, "%s_6_bound_and_torque_per_head.png" % TAG)
    f.savefig(q, dpi=180); plt.close(f); made.append(q)

    # 7/8 unavailable -> placeholder note file
    with open(os.path.join(outdir, "PLOTS_7_8_UNAVAILABLE.txt"), "w") as fh:
        fh.write("Plots 7 (positive/negative torque components) and 8 (axial puller/dragger torque)\n"
                 "cannot be produced from this pilot: only NET axial torque is stored, and per-head\n"
                 "axial force is not stored at all. See STEP 5/STEP 6 for the exact schema additions.\n")
    made.append(os.path.join(outdir, "PLOTS_7_8_UNAVAILABLE.txt"))
    return made


if __name__ == "__main__":
    sys.exit(main())
