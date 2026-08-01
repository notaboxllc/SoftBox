#!/usr/bin/env python3
"""
Window-resolved chirality-odd ROTATION from the dense per-arm traces. Zero GPU cost.

Why this is legitimate. The rigid-body Brownian torque is drawn from a counter-based hash keyed by
(body, step, seed) and is STATE-INDEPENDENT, so matched +eps / -eps arms at one seed draw a
bit-identical Brownian sequence. The accumulated Brownian roll Phi_Brown is therefore identical
between the two arms and cancels EXACTLY in the odd difference -- which the records confirm
independently (Om_Brown_odd = 0.0 exactly, and Om_total_odd = Om_drive_odd + Om_geom_odd with the
geometric term ~0.05-0.08% of the drift).

Hence for ANY sub-window [t1, t2]:

    Omega_odd(t1,t2) = 0.5 * [ (phi_+(t2)-phi_+(t1)) - (phi_-(t2)-phi_-(t1)) ] / (t2 - t1)

is a Brownian-free estimator of the motor-driven odd rotation over that window, computable from the
stored meanRoll traces without rerunning anything.

Use: test whether the 25-100 ms production window sits on a transient, by comparing it with later
windows on the seeds that also have 200 ms records.

  usage: scratch_rigid_window.py --eps 5 --dur-ms 100 --seeds 101,102 --windows 25:100,100:200
"""
import argparse, math, os

ATP_DIR = "RUN_LOGS/chiral_sites/lowatp"


def rid(eps, dur_ms, sgn, seed, dens=400.0, uM=10.0):
    return ("rigid_thfdt_u%07.2f_e%04d_r%06.1f_d%08d_%s%d"
            % (uM, int(round(abs(eps) * 10)), dens, int(round(dur_ms * 1e3)),
               "p_" if sgn > 0 else "n_", seed))


def trace(path):
    """-> (times[], meanRoll[])"""
    if not os.path.exists(path):
        return None
    t, r = [], []
    with open(path) as f:
        head = f.readline().split("\t")
        it, ir = head.index("tS"), head.index("meanRoll")
        for line in f:
            p = line.rstrip("\n").split("\t")
            if len(p) <= max(it, ir):
                continue
            try:
                t.append(float(p[it])); r.append(float(p[ir]))
            except ValueError:
                pass
    return (t, r) if t else None


def at(t, r, tq):
    """meanRoll at the sample nearest tq (traces are uniform; no interpolation needed)."""
    best, bi = None, 0
    for i, x in enumerate(t):
        d = abs(x - tq)
        if best is None or d < best:
            best, bi = d, i
    return r[bi], t[bi]


def stats(x):
    n = len(x)
    m = sum(x) / n
    sd = math.sqrt(sum((a - m) ** 2 for a in x) / (n - 1)) if n > 1 else float("nan")
    sem = sd / math.sqrt(n) if n > 1 else float("nan")
    return m, sem, (abs(m) / sem if sem and sem == sem and sem > 0 else float("nan"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--eps", type=float, required=True)
    ap.add_argument("--dur-ms", type=float, required=True)
    ap.add_argument("--seeds", required=True)
    ap.add_argument("--windows", required=True, help="comma list of t1:t2 in ms")
    ap.add_argument("--dir", default=ATP_DIR)
    a = ap.parse_args()
    seeds = [int(s) for s in a.seeds.split(",")]
    wins = []
    for w in a.windows.split(","):
        t1, t2 = w.split(":")
        wins.append((float(t1) * 1e-3, float(t2) * 1e-3))

    print("  window-resolved Omega_odd   eps = +-%.1f deg   arms = %.0f ms   seeds %s"
          % (a.eps, a.dur_ms, seeds))
    print("  (Brownian roll cancels identically in the odd difference -- see module docstring)\n")
    print("    %-16s %s" % ("window (ms)", "".join("%14s" % ("seed %d" % s) for s in seeds)
                            + "%16s %12s %8s" % ("mean", "SEM", "|m|/SEM")))
    for (t1, t2) in wins:
        vals = []
        for s in seeds:
            tp = trace(os.path.join(a.dir, rid(a.eps, a.dur_ms, +1, s) + ".trace.tsv"))
            tm = trace(os.path.join(a.dir, rid(a.eps, a.dur_ms, -1, s) + ".trace.tsv"))
            if tp is None or tm is None:
                vals.append(float("nan")); continue
            p1, ta = at(*tp, t1); p2, tb = at(*tp, t2)
            m1, _ = at(*tm, t1);  m2, _ = at(*tm, t2)
            dt = tb - ta
            vals.append(0.5 * ((p2 - p1) - (m2 - m1)) / dt if dt > 0 else float("nan"))
        good = [v for v in vals if v == v]
        m, sem, sig = stats(good) if len(good) > 1 else (good[0] if good else float("nan"),
                                                         float("nan"), float("nan"))
        print("    %-16s %s%16.4f %12.4f %8.2f"
              % ("%.0f-%.0f" % (t1 * 1e3, t2 * 1e3),
                 "".join("%14.4f" % v for v in vals), m, sem, sig))


if __name__ == "__main__":
    main()
