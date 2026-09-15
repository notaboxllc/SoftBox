import sys, os, statistics as st
OUT = sys.argv[1]
SEEDS = ('20260901','20260902','20260903')
def load(d):
    f = os.path.join(OUT, d, "trajectory_summary.csv")
    if not os.path.exists(f): return None
    L = [l.split('\t') for l in open(f).read().splitlines()]
    if len(L) < 3 or 'rollTurns' not in L[0]: return None
    h = L[0]; g = lambda r,k: float(r[h.index(k)])
    return ([g(r,'t_s') for r in L[1:]], [g(r,'rollTurns') for r in L[1:]],
            [g(r,'fwd_um') for r in L[1:]], [g(r,'avgBound') for r in L[1:]])
def slope(x, y):
    n=len(x); mx=sum(x)/n; my=sum(y)/n
    sxx=sum((a-mx)**2 for a in x); sxy=sum((a-mx)*(b-my) for a,b in zip(x,y))
    return sxy/sxx if sxx>0 else float('nan')
print(f"{'arm':>4} {'seed':>9} {'travel um':>10} {'net turns':>10} {'turns/um':>9} {'turns/s':>9} {'avgBound':>9} {'pts':>5}")
res={}
for arm,tag in (('nat','NATIVE'),('mir','MIRROR')):
    for s in SEEDS:
        r = load(f"{arm}_s{s}")
        if r is None: print(f"{arm:>4} {s:>9}   (no data yet)"); continue
        t, th, fw, ab = r
        tpu = slope(fw, th)           # turns per um of TRAVEL - the experimental observable
        tps = slope(t, th)
        res.setdefault(arm, []).append((fw[-1], th[-1], tpu, tps, ab[-1]))
        print(f"{arm:>4} {s:>9} {fw[-1]:10.3f} {th[-1]:+10.3f} {tpu:+9.3f} {tps:+9.3f} {ab[-1]:9.3f} {len(t):5d}")
print()
sem=lambda z: st.stdev(z)/len(z)**0.5 if len(z)>1 else 0.0
for arm,tag in (('nat','NATIVE'),('mir','MIRROR')):
    if arm not in res: continue
    T=[x[2] for x in res[arm]]; N=[x[1] for x in res[arm]]
    print(f"{tag}  turns/um {st.mean(T):+.3f} +- {sem(T):.3f}   net turns {st.mean(N):+.3f} +- {sem(N):.3f}"
          f"   [{sum(1 for v in T if v<0)}/{len(T)} negative]")
if 'nat' in res and 'mir' in res and len(res['nat'])>1 and len(res['mir'])>1:
    a=[x[2] for x in res['nat']]; b=[x[2] for x in res['mir']]
    d=st.mean(a)-st.mean(b); sd=(sem(a)**2+sem(b)**2)**0.5
    print()
    print(f"  REVERSAL  native - mirror = {d:+.3f} +- {sd:.3f} turns/um" + (f"  ({abs(d)/sd:.1f} sigma)" if sd>0 else ""))
    # The eps-odd (chiral) component. Do NOT verdict on "the two means have opposite signs": two noisy
    # means straddling zero do that ~half the time by chance, and it read "REVERSES" on a 0.7-sigma null.
    chi, schi = d/2, sd/2
    sig = abs(chi)/schi if schi > 0 else 0.0
    print(f"  CHIRAL    (native-mirror)/2 = {chi:+.3f} +- {schi:.3f} turns/um  ({sig:.1f} sigma)")
    print(f"  VERDICT   {'REVERSAL DETECTED' if sig >= 3 else f'NO REVERSAL RESOLVED — 2-sigma upper bound |twirl| < {abs(chi)+2*schi:.2f} turns/um'}")
    print()
    print("  A genuine chiral twirl: opposite signs, |native| ~ |mirror|, and each theta(t) trace a visible ramp.")
    print("  Achiral artifact: same sign, or a difference consistent with zero.")
