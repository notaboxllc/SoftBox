#!/usr/bin/env python3
"""FINE-DT V0 REFERENCE analysis.
Parses tagged FVROW lines, computes per-point force means/SEM, fixed-velocity paired
convergence, joint local linear force-velocity fits -> V0(dt) with paired-seed bootstrap CI,
leave-one-out robustness, and dt-limit model comparison (A plateau / B first-order / C power).
Re-runnable; pass result files/globs as argv (default: the pass1/pass2 dirs).
"""
import sys, glob, re, math
from collections import defaultdict

FIELD = re.compile(r'(\w+)=([-\d.eE+]+)')

def parse(paths):
    # rows keyed (dt, v) -> {seed: fbar_avail}; also keep full field dict per (dt,v,seed)
    fbar = defaultdict(dict)      # (dt,v) -> seed -> fbar_avail
    full = {}                     # (dt,v,seed) -> dict of all fields
    for p in paths:
        with open(p) as fh:
            for line in fh:
                if 'FVROW' not in line: continue
                kv = dict(FIELD.findall(line))
                # dt / seed come from the sed-prepended tags
                dt = float(kv['DT']); seed = int(float(kv['SEED']))
                v = float(kv['v']); f = float(kv['fbar_avail'])
                fbar[(dt, v)][seed] = f
                full[(dt, v, seed)] = {k: float(x) for k, x in kv.items()
                                       if k not in ('variant',)}
    return fbar, full

def mean(xs): return sum(xs)/len(xs)
def sd(xs):
    if len(xs) < 2: return 0.0
    m = mean(xs); return math.sqrt(sum((x-m)**2 for x in xs)/(len(xs)-1))
def sem(xs): return sd(xs)/math.sqrt(len(xs)) if len(xs) >= 2 else 0.0

# ---- deterministic bootstrap RNG (no external deps; reproducible) ----
class LCG:
    def __init__(self, seed=12345): self.s = seed & 0xFFFFFFFFFFFFFFFF
    def next(self):
        self.s = (self.s*6364136223846793005 + 1442695040888963407) & 0xFFFFFFFFFFFFFFFF
        return self.s
    def randint(self, n): return (self.next() >> 33) % n   # high bits (low LCG bits have short period)

def wls_fit(vs, fs):
    """ordinary least squares f = a + b v; returns (a, b)."""
    n = len(vs); sv = sum(vs); sf = sum(fs)
    svv = sum(v*v for v in vs); svf = sum(v*f for v,f in zip(vs,fs))
    den = n*svv - sv*sv
    if abs(den) < 1e-30: return None
    b = (n*svf - sv*sf)/den
    a = (sf - b*sv)/n
    return a, b

def fit_v0(fbar, dt, vwin, seeds):
    """seed-mean force at each v in vwin, OLS fit, V0=-a/b. Returns (a,b,V0,points)."""
    vs, fs = [], []
    for v in vwin:
        vals = [fbar[(dt, v)][s] for s in seeds if s in fbar.get((dt, v), {})]
        if not vals: return None
        vs.append(v); fs.append(mean(vals))
    r = wls_fit(vs, fs)
    if not r: return None
    a, b = r
    if abs(b) < 1e-12: return None
    return a, b, -a/b, list(zip(vs, fs))

def bootstrap_v0(fbar, dt, vwin, seeds, nboot=5000):
    rng = LCG(98765 + int(dt*1e9))
    v0s = []
    ns = len(seeds)
    for _ in range(nboot):
        rs = [seeds[rng.randint(ns)] for _ in range(ns)]   # paired resample of seed IDs
        vs, fs = [], []
        ok = True
        for v in vwin:
            vals = [fbar[(dt, v)][s] for s in rs if s in fbar.get((dt, v), {})]
            if not vals: ok = False; break
            vs.append(v); fs.append(mean(vals))
        if not ok: continue
        r = wls_fit(vs, fs)
        if not r or abs(r[1]) < 1e-12: continue
        v0s.append(-r[0]/r[1])
    v0s.sort()
    if not v0s: return None
    lo = v0s[int(0.025*len(v0s))]; hi = v0s[min(len(v0s)-1, int(0.975*len(v0s)))]
    return mean(v0s), lo, hi, len(v0s)

def main():
    paths = []
    for a in sys.argv[1:]:
        paths += glob.glob(a)
    if not paths:
        paths = glob.glob('RUN_LOGS/v0fine/pass1/*.txt') + glob.glob('RUN_LOGS/v0fine/pass2/*.txt')
    fbar, full = parse(paths)
    dts = sorted({dt for (dt, v) in fbar}, reverse=True)
    print(f"# parsed {len(paths)} files ; dts={dts}")

    # ---- inventory ----
    print("\n## SEED INVENTORY (n seeds per (dt,v))")
    allv = sorted({v for (dt, v) in fbar})
    hdr = "dt\\v      " + "".join(f"{v:6.0f}" for v in allv)
    print(hdr)
    for dt in dts:
        row = f"{dt:<9.2e}" + "".join(f"{len(fbar.get((dt,v),{})):6d}" for v in allv)
        print(row)

    # ---- per-point force mean +/- SEM ----
    print("\n## f_bar_available  mean +/- SEM (pN)   [Nbound in brackets]")
    print(hdr)
    for dt in dts:
        cells = []
        for v in allv:
            d = fbar.get((dt, v), {})
            if not d: cells.append("    -  "); continue
            xs = list(d.values())
            cells.append(f"{mean(xs):+.3f}")
        print(f"{dt:<9.2e}" + "".join(f"{c:>7}" for c in cells))
    print("SEM:")
    for dt in dts:
        cells = []
        for v in allv:
            d = fbar.get((dt, v), {})
            cells.append(f"{sem(list(d.values())):.3f}" if len(d) >= 2 else "  -")
        print(f"{dt:<9.2e}" + "".join(f"{c:>7}" for c in cells))

    # ---- diagnostics: Nbound, Jattach, Iattach, life_ms (seed-mean) ----
    for fld in ('Nbound', 'Jattach', 'Iattach', 'life_ms'):
        print(f"\n## {fld} (seed-mean)")
        print(hdr)
        for dt in dts:
            cells = []
            for v in allv:
                vals = [full[(dt, v, s)][fld] for s in range(8) if (dt, v, s) in full and fld in full[(dt,v,s)]]
                cells.append(f"{mean(vals):.3f}" if vals else "  -")
            print(f"{dt:<9.2e}" + "".join(f"{c:>8}" for c in cells))

    # ---- fixed-velocity paired convergence Delta f(dt -> dt/2) ----
    print("\n## FIXED-VELOCITY PAIRED CONVERGENCE  Delta f = f(dt/2) - f(dt)  (paired over seeds)")
    print("   pairs of adjacent dt arms; mean [95% paired bootstrap CI], n paired seeds")
    for v in [12, 14, 16]:
        print(f"  v = {v} um/s:")
        for i in range(len(dts)-1):
            dtc, dtf = dts[i], dts[i+1]   # coarse, fine (dts sorted desc)
            common = sorted(set(fbar.get((dtc,v),{})) & set(fbar.get((dtf,v),{})))
            if not common: continue
            diffs = [fbar[(dtf,v)][s] - fbar[(dtc,v)][s] for s in common]
            # paired bootstrap
            rng = LCG(555 + v + int(dtf*1e9)); bs = []
            for _ in range(5000):
                samp = [diffs[rng.randint(len(diffs))] for _ in diffs]
                bs.append(mean(samp))
            bs.sort()
            lo = bs[int(0.025*len(bs))]; hi = bs[int(0.975*len(bs))]
            sig = "" if (lo <= 0 <= hi) else "  *SIGNIFICANT*"
            print(f"    {dtc:.2e}->{dtf:.2e}:  Df={mean(diffs):+.4f}  [{lo:+.4f},{hi:+.4f}]  n={len(common)}{sig}")

    # ---- joint local linear fit + V0 per dt ----
    print("\n## JOINT LOCAL LINEAR FIT  f = a + b v  ->  V0 = -a/b")
    # choose the near-crossing linear window: use velocities present at all arms within [10,18]
    for dt in dts:
        seeds = sorted({s for (ddt, v, s) in full if ddt == dt})
        present = sorted(v for v in allv if len(fbar.get((dt, v), {})) >= 2)
        # linear window: 12..18 (drop 8,10 if curved); fall back to all present
        win = [v for v in present if 12 <= v <= 18] or present
        r = fit_v0(fbar, dt, win, seeds)
        if not r:
            print(f"  dt={dt:.2e}: insufficient"); continue
        a, b, v0, pts = r
        bs = bootstrap_v0(fbar, dt, win, seeds)
        cistr = f"[{bs[1]:.2f}, {bs[2]:.2f}]" if bs else "n/a"
        print(f"  dt={dt:.2e}  win={win}  slope b={b:+.5f} pN/(um/s)  a={a:+.4f}  V0={v0:.2f}  95%CI {cistr}")

    # ---- robustness: leave-one-velocity-out & leave-one-seed-out on V0 ----
    print("\n## ROBUSTNESS  (V0 under leave-one-out)")
    for dt in dts:
        seeds = sorted({s for (ddt, v, s) in full if ddt == dt})
        present = sorted(v for v in allv if len(fbar.get((dt, v), {})) >= 2)
        win = [v for v in present if 12 <= v <= 18] or present
        base = fit_v0(fbar, dt, win, seeds)
        if not base: continue
        # drop outer velocity points
        lo_out = fit_v0(fbar, dt, win[1:], seeds) if len(win) > 2 else None
        hi_out = fit_v0(fbar, dt, win[:-1], seeds) if len(win) > 2 else None
        # leave-one-seed-out spread
        los = []
        for s in seeds:
            rr = fit_v0(fbar, dt, win, [x for x in seeds if x != s])
            if rr: los.append(rr[2])
        losstr = f"{min(los):.2f}..{max(los):.2f}" if los else "n/a"
        print(f"  dt={dt:.2e}  V0_base={base[2]:.2f}  drop-lowv={lo_out[2] if lo_out else float('nan'):.2f}"
              f"  drop-highv={hi_out[2] if hi_out else float('nan'):.2f}  LOSO range={losstr}")

    # ---- dt-limit model comparison ----
    print("\n## dt-LIMIT MODELS  (V0 point estimates per dt)")
    v0pt = {}
    for dt in dts:
        seeds = sorted({s for (ddt, v, s) in full if ddt == dt})
        present = sorted(v for v in allv if len(fbar.get((dt, v), {})) >= 2)
        win = [v for v in present if 12 <= v <= 18] or present
        r = fit_v0(fbar, dt, win, seeds)
        if r: v0pt[dt] = r[2]
    for dt in dts:
        if dt in v0pt: print(f"    V0({dt:.2e}) = {v0pt[dt]:.2f}")
    fine = [dt for dt in dts if dt <= 5e-6 and dt in v0pt]
    if len(fine) >= 2:
        vals = [v0pt[dt] for dt in fine]
        print(f"  Model A (plateau over finest {len(fine)}): mean={mean(vals):.2f}  spread={max(vals)-min(vals):.2f}")
    if len(v0pt) >= 3:
        # Model B: V0 = V0* + c*dt  (linear regression on dt)
        xs = [dt for dt in dts if dt in v0pt]; ys = [v0pt[dt] for dt in xs]
        r = wls_fit(xs, ys)
        if r:
            a, b = r  # here a=intercept=V0*, b=c (slope vs dt)
            print(f"  Model B (V0 = V0* + c*dt):     V0*={a:.2f}  c={b:.3e} per s")
        # Model C p=2: regress on dt^2
        xs2 = [dt*dt for dt in xs]
        r2 = wls_fit(xs2, ys)
        if r2:
            print(f"  Model C p=2 (V0 = V0* + c*dt^2): V0*={r2[0]:.2f}  c={r2[1]:.3e}")

def combined(paths=None):
    """Combined fine-dt reference + production bias, with paired-seed bootstrap across arms.
    Seeds are shared identities across dt arms => resample seed IDs ONCE, apply to all arms."""
    import glob as _g
    if paths is None:
        paths = _g.glob('RUN_LOGS/v0fine/pass1/*.txt') + _g.glob('RUN_LOGS/v0fine/pass2/*.txt')
    fbar, full = parse(paths)
    dts = sorted({dt for (dt, v) in fbar}, reverse=True)
    prod = 1e-5
    fine = [dt for dt in dts if dt <= 5e-6]

    def v0_on(dt, win, seedset):
        vs, fs = [], []
        for v in win:
            vals = [fbar[(dt, v)][s] for s in seedset if s in fbar.get((dt, v), {})]
            if not vals: return None
            vs.append(v); fs.append(mean(vals))
        r = wls_fit(vs, fs)
        if not r or abs(r[1]) < 1e-12: return None
        return -r[0]/r[1]

    allseeds = sorted({s for (dt, v, s) in full})

    print("\n## MULTI-WINDOW V0 (fit-window sensitivity)  point estimates")
    windows = {'[12,18]': [12,14,16,18], '[12,16]': [12,14,16],
               '[13,15] tight': [13,15], '[12,15]': [12,13,14,15], '[13,16]': [13,14,15,16]}
    present_by_dt = {dt: {v for (ddt, v) in fbar if ddt == dt} for dt in dts}
    hdr = "dt        " + "".join(f"{k:>15}" for k in windows)
    print(hdr)
    for dt in dts:
        cells = []
        for k, w in windows.items():
            w2 = [v for v in w if v in present_by_dt[dt]]
            if len(w2) < 2: cells.append("      -"); continue
            r = v0_on(dt, w2, allseeds)
            cells.append(f"{r:.2f}" if r else "  -")
        print(f"{dt:<10.2e}" + "".join(f"{c:>15}" for c in cells))

    # combined fine reference — paired seed bootstrap across the fine arms
    win = [12, 14, 16, 18]
    rng = LCG(424242)
    combos, prods, biases = [], [], []
    ns = len(allseeds)
    for _ in range(8000):
        rs = [allseeds[rng.randint(ns)] for _ in range(ns)]
        fv = [v0_on(dt, win, rs) for dt in fine]
        fv = [x for x in fv if x is not None]
        pv = v0_on(prod, win, rs)
        if len(fv) == len(fine) and pv is not None:
            c = mean(fv); combos.append(c); prods.append(pv); biases.append(pv - c)
    def ci(xs):
        xs = sorted(xs); return (mean(xs), xs[int(0.025*len(xs))], xs[min(len(xs)-1,int(0.975*len(xs)))])
    cm, clo, chi = ci(combos); pm, plo, phi = ci(prods); bm, blo, bhi = ci(biases)
    print("\n## COMBINED FINE-dt REFERENCE (paired-seed bootstrap across 3 finest arms, win [12,18])")
    print(f"  V0_fine   = {cm:.2f}  95%CI [{clo:.2f}, {chi:.2f}]")
    print(f"  V0_prod   = {pm:.2f}  95%CI [{plo:.2f}, {phi:.2f}]   (dt=1e-5)")
    print(f"  bias dV0  = V0_prod - V0_fine = {bm:+.2f}  95%CI [{blo:+.2f}, {bhi:+.2f}]")
    # is production within pre-registered +/-2 tolerance?
    print(f"  |bias| vs pre-registered tolerance 2.0 um/s => "
          f"{'WITHIN (Outcome E candidate)' if abs(bm) < 2 else 'EXCEEDS => production NOT adequately close'}")

if __name__ == '__main__':
    main()
    combined()
