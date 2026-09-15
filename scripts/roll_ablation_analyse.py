import sys, os, math, statistics as st
OUT = sys.argv[1]
def load(d):
    f = os.path.join(OUT, d, "trajectory_summary.csv")
    if not os.path.exists(f): return None
    L = [l.split('\t') for l in open(f).read().splitlines()]
    hd = L[0]
    if 'rollTurns' not in hd: return None
    it, ir, ia, iv = hd.index('t_s'), hd.index('rollTurns'), hd.index('avgBound'), hd.index('vfit_um_s')
    t = [float(r[it]) for r in L[1:]]; th = [float(r[ir]) for r in L[1:]]
    ab = float(L[-1][ia]); v = float(L[-1][iv])
    return t, th, ab, v
def fit(t, th):                      # OLS slope of theta vs t, with R^2
    n=len(t); mt=sum(t)/n; mh=sum(th)/n
    sxx=sum((x-mt)**2 for x in t); sxy=sum((x-mt)*(y-mh) for x,y in zip(t,th))
    b=sxy/sxx; a=mh-b*mt
    ss=sum((y-mh)**2 for y in th); rs=sum((y-(a+b*x))**2 for x,y in zip(t,th))
    return b, (1-rs/ss if ss>0 else float('nan'))
def msd(t, th):                      # diffusivity from lag-1 increments
    d=[th[i+1]-th[i] for i in range(len(th)-1)]
    dt=[t[i+1]-t[i] for i in range(len(t)-1)]
    return st.mean([x*x/(2*y) for x,y in zip(d,dt) if y>0])
print(f"{'arm':>4} {'seed':>9} {'drift turns/s':>14} {'R^2':>6} {'D turns^2/s':>12} {'net turns':>10} {'avgBound':>9} {'v um/s':>8}")
res={}
for arm in ('off','on'):
    for s in ('20260901','20260902','20260903'):
        r = load(f"{arm}_s{s}")
        if r is None: print(f"{arm:>4} {s:>9}   (no data)"); continue
        t, th, ab, v = r
        b, r2 = fit(t, th); D = msd(t, th)
        res.setdefault(arm, []).append((b, r2, D, th[-1], ab, v))
        print(f"{arm:>4} {s:>9} {b:+14.3f} {r2:6.3f} {D:12.4f} {th[-1]:+10.4f} {ab:9.3f} {v:+8.3f}")
print()
for arm in ('off','on'):
    if arm not in res: continue
    B=[x[0] for x in res[arm]]; R=[x[1] for x in res[arm]]; D=[x[2] for x in res[arm]]; A=[x[4] for x in res[arm]]
    sem=lambda z: st.stdev(z)/len(z)**0.5 if len(z)>1 else 0.0
    print(f"arm {arm.upper():3}  drift {st.mean(B):+.3f} +- {sem(B):.3f} turns/s"
          f"   meanR^2 {st.mean(R):.3f}   D {st.mean(D):.4f}   avgBound {st.mean(A):.3f} +- {sem(A):.3f}")
if 'off' in res and 'on' in res:
    ao=[x[4] for x in res['off']]; an=[x[4] for x in res['on']]
    Do=st.mean([x[2] for x in res['off']]); Dn=st.mean([x[2] for x in res['on']])
    bo=[x[0] for x in res['off']]
    semo=st.stdev(bo)/len(bo)**0.5 if len(bo)>1 else 0
    print()
    print(f"  CONTROL  avgBound OFF/ON = {st.mean(ao)/st.mean(an):.3f}   (must be ~1.00, else the ablation perturbs recruitment)")
    print(f"  NOISE    D_roll OFF/ON   = {Do/Dn:.4f}   (should collapse; residual = motor shot noise)")
    print(f"  SIGNAL   drift OFF       = {st.mean(bo):+.3f} +- {semo:.3f} turns/s"
          + (f"  ({abs(st.mean(bo))/semo:.1f} sigma)" if semo>0 else ""))
    print()
    print("  READ: high R^2 in the OFF arm = theta(t) is a straight line = persistent motor-driven twirl.")
    print("        low R^2 with near-zero drift = no twirl at this bound number, whatever the noise.")
