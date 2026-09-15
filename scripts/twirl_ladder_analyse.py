# Stroke-skew eps LADDER: collect the eps-odd (chiral) and eps-even twirl at each rung and test the
# scaling shape. Uses the SAME observable and the SAME slope definition as twirl_skew_analyse.py --
# turns per um of TRAVEL, least-squares slope of rollTurns vs fwd_um over the whole trajectory.
#
# Two questions this answers, and neither is decidable from a single eps:
#   1. Is the population twirl LINEAR in eps, or sub-sin as the converter channel was (Outcome B)?
#      A sub-sin ladder means you cannot read off "the eps that reproduces a given pitch".
#   2. Does the unexplained eps-EVEN residue fall as eps^2? A genuine second-order term must.
import sys, os, math, statistics as st
OUT = sys.argv[1]
SEEDS = ('20260901', '20260902', '20260903')
def load(d):
    f = os.path.join(d, "trajectory_summary.csv")
    if not os.path.exists(f): return None
    L = [l.split('\t') for l in open(f).read().splitlines()]
    if len(L) < 3 or 'rollTurns' not in L[0]: return None
    h = L[0]; g = lambda r, k: float(r[h.index(k)])
    return ([g(r, 'fwd_um') for r in L[1:]], [g(r, 'rollTurns') for r in L[1:]],
            [g(r, 'vfit_um_s') for r in L[1:]], [g(r, 'avgBound') for r in L[1:]])
def slope(x, y):
    n = len(x); mx = sum(x)/n; my = sum(y)/n
    sxx = sum((a-mx)**2 for a in x); sxy = sum((a-mx)*(b-my) for a, b in zip(x, y))
    return sxy/sxx if sxx > 0 else float('nan')
sem = lambda z: st.stdev(z)/len(z)**0.5 if len(z) > 1 else 0.0
rungs = []
for D in sorted(d for d in os.listdir(OUT) if d.startswith('e') and d[1:].isdigit()):
    eps = int(D[1:]); arm = {}
    for sign in ('pos', 'neg'):
        tpu, v, ab = [], [], []
        for s in SEEDS:
            r = load(os.path.join(OUT, D, f"{sign}_s{s}"))
            if r is None: continue
            fw, th, vf, b = r
            tpu.append(slope(fw, th)); v.append(vf[-1]); ab.append(b[-1])
        if tpu: arm[sign] = (tpu, v, ab)
    if len(arm) == 2 and len(arm['pos'][0]) > 1 and len(arm['neg'][0]) > 1:
        rungs.append((eps, arm))
if not rungs:
    print("  ladder: no complete rung yet"); sys.exit(0)
print()
print(f"{'eps':>5} {'n':>3} {'turns/um +eps':>16} {'turns/um -eps':>16} {'ODD':>18} {'EVEN':>16} {'pitch um':>9} {'v um/s':>8}")
tab = []
for eps, arm in rungs:
    a, b = arm['pos'][0], arm['neg'][0]
    ma, mb, sa, sb = st.mean(a), st.mean(b), sem(a), sem(b)
    odd, sodd = (ma-mb)/2, ((sa**2+sb**2)**0.5)/2
    even, seven = (ma+mb)/2, ((sa**2+sb**2)**0.5)/2
    vv = st.mean(arm['pos'][1] + arm['neg'][1])
    pitch = 1.0/abs(odd) if odd else float('nan')
    sig = abs(odd)/sodd if sodd > 0 else 0.0
    print(f"{eps:>5} {len(a):>3} {ma:>10.3f}+-{sa:<5.3f} {mb:>10.3f}+-{sb:<5.3f}"
          f" {odd:>10.3f}+-{sodd:<5.3f} {even:>9.3f}+-{seven:<5.3f} {pitch:>9.3f} {vv:>8.3f}   ({sig:.1f}s)")
    tab.append((eps, odd, sodd, even, seven, vv))
if len(tab) < 2:
    print("\n  (one rung — scaling test needs >= 2)"); sys.exit(0)
# SCALING. Fit |ODD| = k*eps through the ORIGIN (eps=0 is a measured null, so the origin is data, not an
# assumption), weighted by 1/sem^2, then fit a free power law |ODD| ~ eps^p to test for curvature.
#
# DO NOT normalise to the lowest rung. The previous version of this block reported ratios against eps=5 and
# called everything above it "sub-sin"; eps=5 is the NOISIEST point (largest sem) and a high draw there makes
# every later rung look sub-linear. It printed a misleading verdict on all three rungs of the 2026-08-27 ladder
# while the through-origin fit gave chi2 = 1.08 / 2 dof and a power-law exponent 0.96 +- 0.22.
Sxy = sum((1/se**2)*e*abs(o) for e, o, se, _, _, _ in tab)
Sxx = sum((1/se**2)*e*e     for e, o, se, _, _, _ in tab)
k, sk = Sxy/Sxx, math.sqrt(1/Sxx)
chi = sum(((abs(o)-k*e)/se)**2 for e, o, se, _, _, _ in tab)
dof = len(tab)-1
print(f"\n  SCALING — weighted fit |ODD| = k*eps through the origin (eps=0 is the measured null):")
print(f"    k = {k:.4f} +- {sk:.4f} turns/um per degree   chi2 = {chi:.2f} / {dof} dof")
print(f"{'eps':>5} {'obs':>9} {'pred':>9} {'resid':>9}")
for e, o, se, _, _, _ in tab:
    print(f"{e:>5} {abs(o):>9.3f} {k*e:>9.3f} {(abs(o)-k*e)/se:>+8.2f}s")
if len(tab) >= 3:
    lx = [math.log(e) for e, _, _, _, _, _ in tab]
    ly = [math.log(abs(o)) for _, o, _, _, _, _ in tab]
    w  = [(abs(o)/se)**2 for _, o, se, _, _, _ in tab]      # 1/var of ln(y)
    Sw = sum(w); Sx = sum(a*b for a, b in zip(w, lx)); Sy = sum(a*b for a, b in zip(w, ly))
    Sx2 = sum(a*b*b for a, b in zip(w, lx)); Sxy2 = sum(a*b*c for a, b, c in zip(w, lx, ly))
    den = Sw*Sx2 - Sx*Sx
    if den > 0:
        pexp, sp = (Sw*Sxy2 - Sx*Sy)/den, math.sqrt(Sw/den)
        note = ("consistent with LINEAR" if abs(pexp-1) < 2*sp else
                "SUB-linear" if pexp < 1 else "SUPER-linear")
        print(f"    free power law |ODD| ~ eps^p:  p = {pexp:.3f} +- {sp:.3f}  ({abs(pexp-1)/sp:.2f}s from 1) -> {note}")
        print(f"    NOTE: eps vs sin(eps) is NOT separable below ~15 deg (they differ <0.5%); p tests curvature, not sin.")
if k > 0:
    print(f"    pitch at the top rung: {1/(k*max(e for e,_,_,_,_,_ in tab)):.3f} um"
          f"   |  eps matching the experimental 2.13 turns/um: {2.13/k:.1f} deg")

# EVEN residue. Two competing models, both fitted: a genuine second-order response must scale as eps^2,
# while an offset that merely switches on with eps is CONSTANT. Report both chi2 so the reader can choose.
Swe = sum(1/se**2 for _, _, _, ev, se, _ in tab)
mE  = sum(ev/se**2 for _, _, _, ev, se, _ in tab)/Swe
sE  = math.sqrt(1/Swe)
chiC = sum(((ev-mE)/se)**2 for _, _, _, ev, se, _ in tab)
e1   = tab[0][0]
Sq   = sum((1/se**2)*(e/e1)**2*ev for e, _, _, ev, se, _ in tab)
Sqq  = sum((1/se**2)*(e/e1)**4    for e, _, _, _,  se, _ in tab)
kq   = Sq/Sqq if Sqq > 0 else float('nan')
chiQ = sum(((ev-kq*(e/e1)**2)/se)**2 for e, _, _, ev, se, _ in tab)
dofE = len(tab)-1
print(f"\n  EVEN (achiral) residue — is it a second-order response, or a constant offset?")
for e, _, _, ev, se, _ in tab:
    print(f"    eps={e:>3}: {ev:+.3f} +- {se:.3f} ({abs(ev)/se:.1f}s)")
print(f"    CONSTANT model: {mE:+.3f} +- {sE:.3f} ({abs(mE)/sE:.1f}s)   chi2 = {chiC:.2f} / {dofE} dof")
print(f"    eps^2    model: chi2 = {chiQ:.2f} / {dofE} dof")
print(f"    -> {'CONSTANT fits better; NOT a second-order term' if chiC < chiQ else 'eps^2 fits better'}")

print(f"\n  GLIDE SPEED should be eps-EVEN (the skew rotates, it does not propel):")
for eps, odd, sodd, even, seven, vv in tab:
    print(f"    eps={eps:>3}: v = {vv:.3f} um/s")
print("\n  Experimental comparator (POST-HOC, never a target): myosin-II twirling pitch 0.47 +- 0.20 um")
print("  => 2.13 turns/um (band 1.49-3.70). eps=0 control: +0.113 +- 0.168 turns/um, NULL.")
