#!/usr/bin/env python3
"""Phase-12 velocity analysis for the long site-normal gliding assay.

Reads RUN_LOGS/motor_audit/site_normal_long_glide/trajectory_summary.csv and reports:
  - the full-run least-squares forward velocity (NOT a start/end quotient)
  - rolling least-squares velocity over 25 / 50 / 100 ms windows
  - displacement chunk timings (0->0.5, 0.5->1.0, 1.0->1.5, 1.5->2.0 um) where reached
  - the Brownian noise floor sqrt(2 D t) and the running signal-to-floor ratio
  - a steady / accelerating / slowing / pausing / bursting verdict from the window series

Usage:  python3 scripts/site_normal_long_glide_analysis.py [run_dir]
"""
import csv, math, sys, os

RUN = sys.argv[1] if len(sys.argv) > 1 else "RUN_LOGS/motor_audit/site_normal_long_glide"
CSV = os.path.join(RUN, "trajectory_summary.csv")

# Parallel translational diffusion of the 12-segment filament at the run's viscosity.
# D_par = kT/gamma_par; the value below is the canonical-eta figure recorded in the assay report.
D_PAR = {0.1: 0.0193, 0.01: 0.19289}


def lsq(ts, xs):
    n = len(ts)
    if n < 3:
        return float("nan")
    st, sx = sum(ts), sum(xs)
    stt = sum(t * t for t in ts)
    stx = sum(t * x for t, x in zip(ts, xs))
    den = n * stt - st * st
    return float("nan") if abs(den) < 1e-30 else (n * stx - st * sx) / den


def main():
    if not os.path.exists(CSV):
        sys.exit("no trajectory_summary.csv in " + RUN)
    rows = list(csv.DictReader(open(CSV), delimiter="\t"))
    if not rows:
        sys.exit("trajectory_summary.csv has no data rows")
    t = [float(r["t_s"]) for r in rows]
    x = [float(r["fwd_um"]) for r in rows]
    step = [int(r["step"]) for r in rows]

    eta = 0.1
    cfg = os.path.join(RUN, "run_config.json")
    if os.path.exists(cfg):
        import json
        eta = json.load(open(cfg)).get("eta_Pa_s", 0.1)
    D = D_PAR.get(round(eta, 4), D_PAR[0.1])

    print("run          : %s" % RUN)
    print("eta          : %g Pa.s   (D_par = %g um^2/s)" % (eta, D))
    print("checkpoints  : %d   last step %d   t = %.4f s" % (len(rows), step[-1], t[-1]))
    print()
    print("FULL-RUN least-squares forward velocity : %+.4f um/s" % lsq(t, x))
    print("start->end quotient (reported for contrast only) : %+.4f um/s"
          % ((x[-1] - x[0]) / (t[-1] - t[0]) if t[-1] > t[0] else float("nan")))
    print()

    floor = math.sqrt(2 * D * t[-1]) if t[-1] > 0 else float("nan")
    print("net forward displacement : %+.5f um" % x[-1])
    print("Brownian floor sqrt(2Dt) : %.5f um   ->  signal/floor = %.2f" %
          (floor, x[-1] / floor if floor else float("nan")))
    print()

    for win in (0.025, 0.050, 0.100):
        vs = []
        for i in range(len(t)):
            j = i
            while j + 1 < len(t) and t[j + 1] - t[i] <= win:
                j += 1
            if t[j] - t[i] >= win * 0.8 and j - i + 1 >= 3:
                vs.append((t[i], lsq(t[i:j + 1], x[i:j + 1])))
        if not vs:
            print("rolling %3.0f ms : not enough horizon yet" % (win * 1e3))
            continue
        v = [q for _, q in vs if q == q]
        mean = sum(v) / len(v)
        sd = math.sqrt(sum((q - mean) ** 2 for q in v) / max(1, len(v) - 1)) if len(v) > 1 else 0.0
        pos = sum(1 for q in v if q > 0)
        print("rolling %3.0f ms : n=%3d  mean %+.4f  sd %.4f  min %+.4f  max %+.4f  positive %d/%d (%.0f%%)"
              % (win * 1e3, len(v), mean, sd, min(v), max(v), pos, len(v), 100.0 * pos / len(v)))
    print()

    # ---- DRIFT vs PURE DIFFUSION -------------------------------------------------------------------
    # A constant drift v gives  fwd/sqrt(2Dt) = v*sqrt(t/2D), which GROWS as sqrt(t).
    # Pure diffusion gives a ratio that is O(1) and does not grow, and a fitted slope that DECAYS as
    # sqrt(2D/t). Reporting both discriminators is the whole reason this assay is long: a persistently
    # positive displacement is one correlated walk, not repeated independent evidence.
    print("drift vs pure diffusion:")
    print("  %8s %10s %8s %8s %8s" % ("t_s", "fwd_um", "floor", "ratio", "vfit/vdiff"))
    ratios = []
    for tt, xx, r in zip(t, x, rows):
        if tt <= 0:
            continue
        fl = math.sqrt(2 * D * tt)
        vd = math.sqrt(2 * D / tt)
        vf = float(r["vfit_um_s"])
        ratios.append((tt, xx / fl))
        print("  %8.4f %10.5f %8.5f %8.2f %8.2f" % (tt, xx, fl, xx / fl, vf / vd if vd else float("nan")))
    if len(ratios) >= 4:
        t0, r0 = ratios[0]
        t1, r1 = ratios[-1]
        grow = r1 / r0 if r0 else float("nan")
        expect = math.sqrt(t1 / t0)
        print("  ratio grew x%.2f over t x%.1f; a constant drift predicts x%.2f (sqrt t)"
              % (grow, t1 / t0, expect))
        # log-log slope of ratio vs t: 0.5 for a constant drift, 0 for pure diffusion. This is the
        # robust form -- the endpoint ratio above is hostage to whichever single early point it starts
        # from, and the earliest checkpoints have the largest relative walk noise.
        lt = [math.log(a) for a, _ in ratios if a > 0]
        lr = [math.log(b) for _, b in ratios if b > 0]
        slope = lsq(lt, lr)
        print("  log-log slope d(log ratio)/d(log t) = %+.3f   [drift = +0.50, pure diffusion = 0.00]" % slope)
        # ... and the same slope over the SECOND HALF only, where the walk noise is relatively smaller
        h = len(ratios) // 2
        s2 = lsq(lt[h:], lr[h:]) if len(ratios) - h >= 3 else float("nan")
        if s2 == s2:
            print("  second-half slope                     = %+.3f" % s2)
        best = s2 if s2 == s2 else slope
        if r1 >= 3.0 and best >= 0.30:
            print("  => DRIFT RESOLVED (ratio %.2f >= 3 and scaling tracks sqrt t)" % r1)
        elif best >= 0.30:
            print("  => trending as a genuine DRIFT, not yet at 3 sigma (ratio %.2f)" % r1)
        elif best <= 0.15:
            print("  => consistent with PURE DIFFUSION; no drift resolved at this horizon")
        else:
            print("  => AMBIGUOUS at this horizon; needs more time")
        v_now = lsq(t, x)
        if v_now and abs(v_now) > 1e-9:
            t3 = 18.0 * D / (v_now * v_now)
            print("  at the current fitted |v| = %.3f um/s, 3-sigma separation needs t ~ %.3f s (step ~%d)"
                  % (abs(v_now), t3, int(t3 / 2.5e-6)))
    print()

    print("displacement chunks (first crossing):")
    # Prefer the harness's PER-STEP milestone detector; the checkpoint rows below are 25 ms apart and will
    # miss a crossing that happens between them (they reported 0.5 um "NOT REACHED" while milestones.tsv
    # had it at step 180677).
    hit = {}
    ms = os.path.join(RUN, "milestones.tsv")
    if os.path.exists(ms):
        for r in csv.DictReader(open(ms), delimiter="\t"):
            m = float(r["milestone_um"])
            if m in (0.5, 1.0, 1.5, 2.0):
                hit[m] = (int(r["step"]), float(r["t_s"]))
    for m in (0.5, 1.0, 1.5, 2.0):
        if m in hit:
            continue
        for i in range(len(x)):
            if x[i] >= m:
                hit[m] = (step[i], t[i])
                break
    prev_t, prev_m = 0.0, 0.0
    for m in (0.5, 1.0, 1.5, 2.0):
        if m in hit:
            s, tt = hit[m]
            print("  %.1f -> %.1f um : step %d, t = %.4f s, chunk took %.4f s, chunk v = %+.4f um/s"
                  % (prev_m, m, s, tt, tt - prev_t, (m - prev_m) / (tt - prev_t) if tt > prev_t else float("nan")))
            prev_t, prev_m = tt, m
        else:
            print("  %.1f -> %.1f um : NOT REACHED" % (prev_m, m))
            break
    print()

    # shape verdict from the longest window series available
    for win in (0.100, 0.050, 0.025):
        vs = []
        for i in range(len(t)):
            j = i
            while j + 1 < len(t) and t[j + 1] - t[i] <= win:
                j += 1
            if t[j] - t[i] >= win * 0.8 and j - i + 1 >= 3:
                vs.append(lsq(t[i:j + 1], x[i:j + 1]))
        vs = [q for q in vs if q == q]
        if len(vs) >= 6:
            half = len(vs) // 2
            a = sum(vs[:half]) / half
            b = sum(vs[half:]) / (len(vs) - half)
            mean = sum(vs) / len(vs)
            sd = math.sqrt(sum((q - mean) ** 2 for q in vs) / (len(vs) - 1))
            cv = sd / abs(mean) if mean else float("inf")
            near_zero = sum(1 for q in vs if abs(q) < 0.2 * abs(mean)) if mean else 0
            print("velocity shape (%0.0f ms windows, n=%d): first half %+.4f, second half %+.4f um/s"
                  % (win * 1e3, len(vs), a, b))
            if cv > 1.5:
                verdict = "INTERMITTENT / BURSTING (window sd exceeds the mean)"
            elif near_zero > 0.3 * len(vs):
                verdict = "PAUSING (a third or more of windows near zero)"
            elif b > 1.3 * a:
                verdict = "ACCELERATING"
            elif b < 0.7 * a:
                verdict = "SLOWING"
            else:
                verdict = "APPROXIMATELY STEADY"
            print("  => %s" % verdict)
            break
    else:
        print("velocity shape: not enough horizon for a windowed verdict yet")


if __name__ == "__main__":
    main()
