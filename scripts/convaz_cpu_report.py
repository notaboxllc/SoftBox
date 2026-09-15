#!/usr/bin/env python3
"""CONVAZ_CPU readout: head long-axis obliquity alpha, signed grid, CPU sequential runner.

Aggregates completed arms over seeds and splits the alpha response into its ODD part (the POSE effect) and
its EVEN part (the arm-shortening / stroke-arc-radius consequence, which is even in alpha by construction).
Incomplete arms are EXCLUDED, never averaged with completed ones (a partial vfit is a different, noisier,
transient-contaminated measurement)."""
import csv, os, re, sys, math, statistics as st

OUT   = sys.argv[1] if len(sys.argv) > 1 else "RUN_LOGS/motor_audit/campaigns_2026-09/CONVAZ_CPU"
STEPS = int(os.environ.get("STEPS", "200000"))
MIN   = int(0.9 * STEPS)
SEEDS = ["20260901", "20260902", "20260903"]
ALPHAS = [0, 30, -30, 60, -60, 90, -90]

def name(a, s):
    return (f"caz000_{s}" if a == 0 else (f"cazm{-a}_{s}" if a < 0 else f"cazp{a}_{s}"))

def arm(n):
    f = os.path.join(OUT, n, "trajectory_summary.csv")
    if not os.path.exists(f): return None
    rows = [r for r in csv.DictReader(open(f), delimiter='\t') if r.get("step", "").isdigit()]
    if not rows: return None
    r = rows[-1]
    if int(r["step"]) < MIN: return ("partial", int(r["step"]))
    d = {k: float(r[k]) for k in ("vfit_um_s", "avgBound", "faxMean_pN", "fwd_um", "rollTurns")}
    d["invalid"], d["solverFail"] = int(float(r["invalid"])), int(float(r["solverFail"]))
    return ("done", d)

def agg(a):
    vals = []
    for s in SEEDS:
        r = arm(name(a, s))
        if r and r[0] == "done": vals.append(r[1])
    if not vals: return None
    def ms(k):
        xs = [v[k] for v in vals]
        return st.mean(xs), (st.stdev(xs)/len(xs)**0.5 if len(xs) > 1 else float('nan'))
    return dict(n=len(vals), v=ms("vfit_um_s"), b=ms("avgBound"), f=ms("faxMean_pN"),
                fwd=ms("fwd_um"), roll=ms("rollTurns"),
                bad=sum(v["invalid"] + v["solverFail"] for v in vals),
                signs=''.join('+' if v["vfit_um_s"] > 0 else '-' for v in vals))

print(f"\nCONVAZ_CPU  {OUT}   {STEPS} steps = {STEPS*1.25e-6:.3f} s simulated, CPU sequential runner\n")
hdr = (f"{'alpha':>6}{'arm nm':>8}{'n':>3}{'v um/s':>9}{'SEM':>7}{'avgB':>7}{'SEM':>7}"
       f"{'fax pN':>9}{'SEM':>7}{'roll turns':>12}{'inv+fail':>10}{'signs':>7}")
print(hdr); print("-"*len(hdr))
A = {}
for a in sorted(ALPHAS):
    g = agg(a)
    if not g:
        parts = [arm(name(a, s)) for s in SEEDS]
        run = [p[1] for p in parts if p and p[0] == "partial"]
        print(f"{a:>6}{7.0*math.cos(math.radians(abs(a))/2):>8.2f}" +
              (f"   (running: steps {run})" if run else "   (not started)"))
        continue
    A[a] = g
    print(f"{a:>6}{7.0*math.cos(math.radians(abs(a))/2):>8.2f}{g['n']:>3}"
          f"{g['v'][0]:>9.3f}{g['v'][1]:>7.3f}{g['b'][0]:>7.3f}{g['b'][1]:>7.3f}"
          f"{g['f'][0]:>9.4f}{g['f'][1]:>7.4f}{g['roll'][0]:>12.3f}{g['bad']:>10d}{g['signs']:>7}")

pairs = [(abs(a), A[abs(a)], A[-abs(a)]) for a in ALPHAS if a > 0 and abs(a) in A and -abs(a) in A]
if pairs:
    print(f"\n  ODD (pose) / EVEN (stroke-arc-radius) decomposition of the velocity")
    print(f"  {'|alpha|':>8}{'odd':>10}{'even':>10}{'even vs alpha=0':>18}")
    v0 = A[0]['v'][0] if 0 in A else float('nan')
    for m, p, n in pairs:
        vp, vn = p['v'][0], n['v'][0]
        print(f"  {m:>8}{(vp-vn)/2:>10.3f}{(vp+vn)/2:>10.3f}{(vp+vn)/2/v0:>18.3f}")
    print("  (odd != 0 => the POSE of the long axis matters per se; even != v(0) => the shortened converter arm")
    print("   matters. Both are real consequences of the same canonical-parts construction -- neither is an artefact.)")
