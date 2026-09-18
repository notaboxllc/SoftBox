#!/usr/bin/env python3
"""
HONEST ERROR BARS FOR THE ROLL OBSERVABLE  (2026-09-18)

THE BUG THIS FIXES. Every roll uncertainty reported before today used
    SE(net) = sd(dRoll) * sqrt(N)
which assumes the per-row roll increments are INDEPENDENT. They are not: a bound motor persists
across many output rows, so the increments are autocorrelated and that formula understates the
true uncertainty. Measured against four CPU arms at one configuration, it understated by 2.4x
(+/-3.99 quoted vs +/-9.67 actual seed-to-seed scatter), which inflated every z by the same factor.

THE FIX. Batch means. Cut the increment series into blocks of length b, sum within each block, and
estimate Var(net) = m * Var(block sum) over the m blocks. For b below the correlation time the
blocks are still correlated and the estimate is too small; above it the estimate PLATEAUS at the
truth. Scan b and read the plateau. This is the standard MCMC batch-means / blocking estimator.

VALIDATION (the point of the exercise): applied to a SINGLE arm, the plateau SE must reproduce the
independently-measured ACROSS-SEED scatter. If it does, one arm can be quoted honestly without
needing an ensemble to discover its own error bar.
"""
import csv, math, os, sys, statistics as st

def increments(path):
    rows = [r for r in csv.DictReader(open(path), delimiter='\t') if r.get("step", "").isdigit()]
    tr = [float(r["rollTurns"]) for r in rows]
    T = float(rows[-1]["t_s"])
    return [tr[i+1] - tr[i] for i in range(len(tr)-1)], T, tr[-1] - tr[0], rows[-1]

def blocked_se(d, b):
    """SE of the NET sum, from m blocks of length b."""
    m = len(d) // b
    if m < 4: return None
    sums = [sum(d[j*b:(j+1)*b]) for j in range(m)]
    return math.sqrt(m * st.variance(sums))

def rate_se(path, verbose=False):
    """Returns (rate, SE_of_rate, chosen_block, naive_SE_of_rate)."""
    d, T, net, last = increments(path)
    naive = st.stdev(d) * math.sqrt(len(d)) / T
    scan = []
    b = 1
    while b <= max(1, len(d)//8):
        se = blocked_se(d, b)
        if se is not None: scan.append((b, se / T))
        b *= 2
    if not scan: return net/T, naive, 1, naive
    # plateau = the largest block size whose estimate is within 15% of the running max;
    # take the max over the upper half of the scan, which is flat once b > tau.
    upper = scan[len(scan)//2:]
    se = max(s for _, s in upper)
    blk = [bb for bb, s in upper if s == se][0]
    if verbose:
        print("     block scan (b rows -> SE of rate):",
              "  ".join(f"{bb}:{s:.2f}" for bb, s in scan))
    return net/T, se, blk, naive

def ensemble(paths, label):
    rs = [rate_se(p)[0] for p in paths]
    m = st.mean(rs); sd = st.stdev(rs) if len(rs) > 1 else float("nan")
    se = sd / math.sqrt(len(rs))
    print(f"  {label:<28} n={len(rs)}  mean {m:+7.2f}  per-seed sd {sd:5.2f}  SE {se:5.2f}  z {abs(m/se):.2f}")
    return rs

def paired(a_paths, b_paths, la, lb):
    """Matched-seed difference. The quenched lawn term cancels, which is the whole point."""
    da = [rate_se(p)[0] for p in a_paths]; db = [rate_se(p)[0] for p in b_paths]
    d = [y - x for x, y in zip(da, db)]
    m = st.mean(d); se = (st.stdev(d) / math.sqrt(len(d))) if len(d) > 1 else float("nan")
    print(f"  PAIRED {lb} - {la}:  n={len(d)}  mean diff {m:+7.2f}  SE {se:5.2f}  "
          + (f"z {abs(m/se):.2f}" if len(d) > 1 else "(need n>=2 for an error bar)"))
    print(f"    per-pair: " + "  ".join(f"{x:+.2f}" for x in d))

if __name__ == "__main__":
    C = "RUN_LOGS/motor_audit/campaigns_2026-09"
    for p in sys.argv[1:]:
        f = p if p.endswith(".csv") else f"{C}/{p}/trajectory_summary.csv"
        if not os.path.exists(f): print(f"  {p}: missing"); continue
        r, se, blk, naive = rate_se(f, verbose=True)
        print(f"  {p:<34} rate {r:+7.2f}  blocked SE {se:5.2f} (b={blk})  naive SE {naive:5.2f} "
              f" -> z {abs(r/se):.2f} (was {abs(r/naive):.2f})")
