#!/usr/bin/env python3
"""FINE-dt free-gliding density sweep — analysis (stdlib + numpy; no scipy).

Parses per-cell logs under RUN_LOGS/finedt_cells/ (filenames encode runner/dt/density/seed),
extracts the GRID_ROW / STATS_STEADY_ROW / COV_ROW observables, and produces:
  * per-cell table (TSV)
  * per-(dt,density) aggregates (mean/SEM over seeds)
  * Part-5 paired timestep comparison (within-seed dV, paired bootstrap CI, driver decomposition)
  * Part-6 saturation fits per dt (MM n=1, Hill free-n, linear, log, power) with AICc, LOO sensitivity
  * saturation classification A-E
  * the 3-panel figure

Nonlinear fits use multistart Gauss-Newton (numerical Jacobian); CIs use seed-resampling bootstrap.
"""
from __future__ import annotations
import argparse, glob, math, os, re, sys
import numpy as np

CELLS_DEFAULT = os.path.join(os.path.dirname(__file__), "..", "RUN_LOGS", "finedt_cells")
FNAME_RE = re.compile(r"(?P<runner>cpu|gpu)_dt(?P<dt>[0-9.eE+-]+)_d(?P<density>\d+)_s(?P<seed>\d+)(?P<sfx>_\w+)?\.log$")
NUM = r"[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?"

def kv(line):
    out = {}
    for k, v in re.findall(r"(\w+)=(-?[\w.+-]+)", line):
        vv = v.rstrip(",")
        try: out[k] = float(vv)
        except ValueError: out[k] = vv
    return out

def parse_cell(path):
    """Return a dict of observables for one cell, or None if incomplete."""
    rec = {}
    m = FNAME_RE.search(os.path.basename(path))
    if not m: return None
    rec["runner"] = m["runner"]; rec["dt"] = float(m["dt"])
    rec["density"] = float(m["density"]); rec["seed"] = int(m["seed"])
    rec["variant"] = (m["sfx"] or "").lstrip("_") or "canon"
    grid = stats = cov = None
    with open(path, encoding="utf-8", errors="replace") as fh:
        for ln in fh:
            if "GRID_ROW" in ln and grid is None: grid = kv(ln)
            elif "STATS_STEADY_ROW" in ln: stats = kv(ln)
            elif "COV_ROW" in ln and cov is None: cov = kv(ln)
    if grid is None or cov is None: return None
    rec["velFitX"] = grid.get("velFitX"); rec["netX"] = grid.get("netX")
    rec["instSteady"] = grid.get("instSteady"); rec["netSteady"] = grid.get("netSteady")
    rec["avgBsteady"] = grid.get("avgBsteady"); rec["avgB"] = grid.get("avgB")
    rec["nMot"] = grid.get("nMot")
    if stats:
        rec["avgBoundSteady"] = stats.get("avgBoundSteady"); rec["meanReach"] = stats.get("meanReach")
        rec["detachRatePerS"] = stats.get("detachRatePerS"); rec["dwellMs"] = stats.get("dwellMs")
        rec["duty"] = stats.get("duty")
    rec["fullMat"] = 1.0 if str(cov.get("fullMat")) in ("YES", "1.0") else 0.0
    rec["minMargin"] = cov.get("minMargin"); rec["runMinMargin"] = cov.get("runMinMargin")
    # per-bound efficiency proxy: forward advance rate per bound head
    if rec["avgBsteady"] and rec["avgBsteady"] > 1e-9 and rec["velFitX"] is not None:
        rec["velPerBound"] = rec["velFitX"] / rec["avgBsteady"]
    else:
        rec["velPerBound"] = float("nan")
    # NaN / instability scan
    with open(path, encoding="utf-8", errors="replace") as fh:
        body = fh.read()
    rec["bad"] = bool(re.search(r"NaN|Infinity|Exception|CUDA error|out of mem", body))
    return rec

def load(cells_dir, runner="gpu", variant="canon"):
    recs = []
    for p in sorted(glob.glob(os.path.join(cells_dir, "*.log"))):
        r = parse_cell(p)
        if r and r["runner"] == runner and r["variant"] == variant:
            recs.append(r)
    return recs

def by_dt_density(recs):
    d = {}
    for r in recs:
        d.setdefault((r["dt"], r["density"]), []).append(r)
    return d

def agg(vals):
    v = np.array([x for x in vals if x is not None and not (isinstance(x, float) and math.isnan(x))], float)
    if len(v) == 0: return (float("nan"), float("nan"), 0)
    sem = v.std(ddof=1) / math.sqrt(len(v)) if len(v) > 1 else 0.0
    return (float(v.mean()), float(sem), len(v))

# ---------- nonlinear fitting (no scipy) ----------
def gauss_newton(f, p0, x, y, iters=200, lam=1e-3):
    p = np.array(p0, float)
    for _ in range(iters):
        r = f(p, x) - y
        J = np.zeros((len(x), len(p)))
        for j in range(len(p)):
            dp = max(1e-8, abs(p[j]) * 1e-6)
            pj = p.copy(); pj[j] += dp
            J[:, j] = (f(pj, x) - f(p, x)) / dp
        JTJ = J.T @ J + lam * np.eye(len(p))
        try: step = np.linalg.solve(JTJ, J.T @ r)
        except np.linalg.LinAlgError: break
        pn = p - step
        if f(pn, x) is not None and np.sum((f(pn, x) - y) ** 2) < np.sum(r ** 2):
            p = pn; lam = max(lam * 0.7, 1e-9)
        else:
            lam *= 2.5
            if lam > 1e6: break
    return p

def mm(p, x): return p[0] * x / (p[1] + x)                    # Vinf, rho_half (Hill n=1)
def hill(p, x): return p[0] * x**p[2] / (p[1]**p[2] + x**p[2])  # Vinf, rho_half, n
def lin(p, x): return p[0] + p[1] * x
def logm(p, x): return p[0] + p[1] * np.log(x)
def powm(p, x): return p[0] * x**p[1]

def sse(f, p, x, y): return float(np.sum((f(p, x) - y) ** 2))
def aicc(n, k, sse_):
    if sse_ <= 0 or n - k - 1 <= 0: return float("nan")
    return n * math.log(sse_ / n) + 2 * k + (2 * k * (k + 1)) / (n - k - 1)

def fit_all(x, y):
    x = np.array(x, float); y = np.array(y, float); n = len(x)
    out = {}
    # MM (n=1)
    p = gauss_newton(mm, [max(y) * 1.3, np.median(x)], x, y)
    out["MM"] = dict(p=p, k=2, sse=sse(mm, p, x, y), f=mm,
                     Vinf=float(p[0]), rho_half=float(p[1]))
    out["MM"]["aicc"] = aicc(n, 2, out["MM"]["sse"])
    # Hill (free n) — multistart on n
    best = None
    for n0 in (0.7, 1.0, 1.5, 2.0, 3.0):
        ph = gauss_newton(hill, [max(y) * 1.3, np.median(x), n0], x, y)
        s = sse(hill, ph, x, y)
        if ph[2] > 0 and ph[0] > 0 and (best is None or s < best[1]): best = (ph, s)
    if best:
        ph, s = best
        out["Hill"] = dict(p=ph, k=3, sse=s, f=hill, Vinf=float(ph[0]),
                           rho_half=float(ph[1]), nhill=float(ph[2]), aicc=aicc(n, 3, s))
    # linear
    pl = np.polyfit(x, y, 1)[::-1]
    out["linear"] = dict(p=pl, k=2, sse=sse(lin, pl, x, y), f=lin, aicc=aicc(n, 2, sse(lin, pl, x, y)))
    # log
    pg = np.polyfit(np.log(x), y, 1)[::-1]
    out["log"] = dict(p=pg, k=2, sse=sse(logm, pg, x, y), f=logm, aicc=aicc(n, 2, sse(logm, pg, x, y)))
    # power
    pp = np.polyfit(np.log(x), np.log(np.maximum(y, 1e-9)), 1)
    ppar = np.array([math.exp(pp[1]), pp[0]])
    out["power"] = dict(p=ppar, k=2, sse=sse(powm, ppar, x, y), f=powm, aicc=aicc(n, 2, sse(powm, ppar, x, y)))
    return out

def bootstrap_fit(cells_by_seed, densities, model_fit_key, B=2000, rng=None):
    """Seed-resampling bootstrap of a fitted parameter set. cells_by_seed: {density:{seed:value}}."""
    rng = rng or np.random.default_rng(12345)
    seeds = sorted({s for d in densities for s in cells_by_seed.get(d, {})})
    Vinf_s, rho_s = [], []
    for _ in range(B):
        pick = rng.choice(seeds, size=len(seeds), replace=True)
        xs, ys = [], []
        for d in densities:
            vals = [cells_by_seed[d][s] for s in pick if s in cells_by_seed.get(d, {})]
            if vals: xs.append(d); ys.append(np.mean(vals))
        if len(xs) < 3: continue
        try:
            f = fit_all(xs, ys)
            if model_fit_key in f:
                Vinf_s.append(f[model_fit_key].get("Vinf", float("nan")))
                rho_s.append(f[model_fit_key].get("rho_half", float("nan")))
        except Exception:
            continue
    def ci(a):
        a = np.array([v for v in a if v is not None and np.isfinite(v)])
        if len(a) < 10: return (float("nan"), float("nan"))
        return (float(np.percentile(a, 2.5)), float(np.percentile(a, 97.5)))
    return ci(Vinf_s), ci(rho_s)

def paired_bootstrap(diffs, B=10000, rng=None):
    rng = rng or np.random.default_rng(999)
    d = np.array(diffs, float)
    if len(d) < 2: return (float("nan"), float("nan"))
    means = [d[rng.integers(0, len(d), len(d))].mean() for _ in range(B)]
    return (float(np.percentile(means, 2.5)), float(np.percentile(means, 97.5)))

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cells", default=CELLS_DEFAULT)
    ap.add_argument("--fig", default=os.path.join(os.path.dirname(__file__), "..", "FINE_DT_FREE_GLIDE_figure.png"))
    ap.add_argument("--runner", default="gpu")
    args = ap.parse_args()

    recs = load(args.cells, runner=args.runner)
    if not recs:
        print("no cells parsed under", args.cells); sys.exit(1)
    dts = sorted({r["dt"] for r in recs})
    print(f"# parsed {len(recs)} {args.runner} canonical cells; dt values: {dts}")

    # ---- per-cell table ----
    print("\n## PER-CELL TABLE")
    hdr = ["dt","density","seed","velFitX","netX","instSteady","avgBsteady","velPerBound","meanReach","dwellMs","detachRatePerS","duty","fullMat","runMinMargin","bad"]
    print("\t".join(hdr))
    for r in sorted(recs, key=lambda z:(z["dt"],z["density"],z["seed"])):
        print("\t".join(f"{r.get(k):.4g}" if isinstance(r.get(k),(int,float)) and r.get(k) is not None else str(r.get(k)) for k in hdr))

    # ---- aggregates per (dt,density) ----
    bdd = by_dt_density(recs)
    print("\n## AGGREGATES (mean ± SEM over seeds)")
    print("dt\tdensity\tnSeed\tvelFitX\tSEM\tavgBsteady\tSEM\tvelPerBound\tSEM\tnetX\tSEM\tfullMat_frac\tinstSteady")
    aggtab = {}
    for (dt, dens) in sorted(bdd):
        rs = bdd[(dt, dens)]
        vf = agg([r["velFitX"] for r in rs]); ab = agg([r["avgBsteady"] for r in rs])
        vpb = agg([r["velPerBound"] for r in rs]); nx = agg([r["netX"] for r in rs])
        fm = np.mean([r["fullMat"] for r in rs]); iss = agg([r["instSteady"] for r in rs])
        aggtab[(dt, dens)] = dict(velFitX=vf, avgB=ab, vpb=vpb, netX=nx, fullMat=fm, nSeed=vf[2])
        print(f"{dt:.1e}\t{dens:.0f}\t{vf[2]}\t{vf[0]:.3f}\t{vf[1]:.3f}\t{ab[0]:.3f}\t{ab[1]:.3f}\t{vpb[0]:.4f}\t{vpb[1]:.4f}\t{nx[0]:.3f}\t{nx[1]:.3f}\t{fm:.2f}\t{iss[0]:.3f}")

    # ---- Part 5: paired dt comparison (5e-6 vs 1e-5) ----
    print("\n## PART 5 — PAIRED TIMESTEP COMPARISON (dt=5e-6 minus dt=1e-5), within seed")
    if 1e-5 in dts and 5e-6 in dts:
        print("density\tnPair\tmeanΔvelFitX\tSEM\tbootCI95\tpct\tΔavgB\tΔvelPerBound")
        for dens in sorted({d for (t,d) in bdd if t in (1e-5,5e-6)}):
            prod = {r["seed"]: r for r in bdd.get((1e-5,dens),[])}
            fine = {r["seed"]: r for r in bdd.get((5e-6,dens),[])}
            common = sorted(set(prod)&set(fine))
            if not common: continue
            dv = [fine[s]["velFitX"]-prod[s]["velFitX"] for s in common]
            dab = [fine[s]["avgBsteady"]-prod[s]["avgBsteady"] for s in common]
            dvpb = [fine[s]["velPerBound"]-prod[s]["velPerBound"] for s in common]
            m = np.mean(dv); sem = np.std(dv,ddof=1)/math.sqrt(len(dv)) if len(dv)>1 else 0.0
            lo,hi = paired_bootstrap(dv)
            base = np.mean([prod[s]["velFitX"] for s in common])
            pct = 100*m/base if base else float("nan")
            print(f"{dens:.0f}\t{len(common)}\t{m:+.3f}\t{sem:.3f}\t[{lo:+.3f},{hi:+.3f}]\t{pct:+.1f}%\t{np.mean(dab):+.3f}\t{np.mean(dvpb):+.4f}")

    # ---- Part 6: saturation fits per dt ----
    print("\n## PART 6 — SATURATION FITS (per dt)")
    for dt in dts:
        pts = sorted([(d, aggtab[(dt,d)]["velFitX"][0]) for (t,d) in aggtab if t==dt])
        if len(pts) < 4:
            print(f"\ndt={dt:.1e}: only {len(pts)} densities — skip fit"); continue
        xs = [p[0] for p in pts]; ys = [p[1] for p in pts]
        print(f"\ndt={dt:.1e}  densities={xs}\n  velFitX={['%.3f'%v for v in ys]}")
        fits = fit_all(xs, ys)
        order = sorted([m for m in fits], key=lambda m: (fits[m]["aicc"] if fits[m]["aicc"]==fits[m]["aicc"] else 1e9))
        best = order[0]
        for m in ["MM","Hill","linear","log","power"]:
            if m not in fits: continue
            fm = fits[m]; extra = ""
            if m=="MM": extra=f" Vinf={fm['Vinf']:.2f} rho_half={fm['rho_half']:.0f}"
            if m=="Hill": extra=f" Vinf={fm['Vinf']:.2f} rho_half={fm['rho_half']:.0f} n={fm['nhill']:.2f}"
            star = " <== best AICc" if m==best else ""
            print(f"  {m:8s} SSE={fm['sse']:.4f} AICc={fm['aicc']:.2f}{extra}{star}")
        # high-density increments
        dmap = dict(pts)
        for a,b in [(4000,6000),(6000,8000)]:
            if a in dmap and b in dmap:
                print(f"  increment {a}->{b}: ΔvelFitX={dmap[b]-dmap[a]:+.3f} ({100*(dmap[b]-dmap[a])/dmap[a]:+.1f}%)")
        # LOO sensitivity on MM Vinf
        for drop in (500, 8000):
            sub = [(d,v) for d,v in pts if d!=drop]
            if len(sub)>=4:
                f2 = fit_all([s[0] for s in sub],[s[1] for s in sub])
                print(f"  MM Vinf w/o d{drop}: {f2['MM']['Vinf']:.2f} (full {fits['MM']['Vinf']:.2f})")
        # bootstrap CI on MM
        cbs = {}
        for (t,d) in bdd:
            if t==dt:
                cbs[d] = {r["seed"]: r["velFitX"] for r in bdd[(t,d)]}
        (vlo,vhi),(rlo,rhi) = bootstrap_fit(cbs, xs, "MM", B=1500)
        print(f"  MM bootstrap95: Vinf[{vlo:.2f},{vhi:.2f}] rho_half[{rlo:.0f},{rhi:.0f}]")

    # ---- figure ----
    make_figure(bdd, aggtab, dts, args.fig)
    print(f"\n# figure -> {args.fig}")

def make_figure(bdd, aggtab, dts, path):
    import matplotlib; matplotlib.use("Agg"); import matplotlib.pyplot as plt
    fig, ax = plt.subplots(1, 3, figsize=(15, 4.5))
    colors = {1e-5: "#1f77b4", 5e-6: "#d62728", 2.5e-6: "#2ca02c"}
    # panel 1: velFitX vs density
    for dt in dts:
        pts = sorted([(d, aggtab[(dt,d)]) for (t,d) in aggtab if t==dt])
        if not pts: continue
        x=[p[0] for p in pts]; y=[p[1]["velFitX"][0] for p in pts]; e=[p[1]["velFitX"][1] for p in pts]
        ax[0].errorbar(x,y,yerr=e,marker="o",capsize=3,label=f"dt={dt:.1e}",color=colors.get(dt,"gray"))
    ax[0].set_xlabel("density (motors/µm²)"); ax[0].set_ylabel("velFitX (µm/s)")
    ax[0].set_title("Free-glide velocity vs density"); ax[0].legend(); ax[0].grid(alpha=.3)
    ax[0].axhspan(3.0,5.0,color="gold",alpha=.15,label="bio ~4 µm/s")
    # panel 2: paired ΔvelFitX vs density
    if 1e-5 in dts and 5e-6 in dts:
        ds=[]; dv=[]; de=[]
        for dens in sorted({d for (t,d) in bdd if t in (1e-5,5e-6)}):
            prod={r["seed"]:r for r in bdd.get((1e-5,dens),[])}; fine={r["seed"]:r for r in bdd.get((5e-6,dens),[])}
            common=sorted(set(prod)&set(fine))
            if not common: continue
            diffs=[fine[s]["velFitX"]-prod[s]["velFitX"] for s in common]
            ds.append(dens); dv.append(np.mean(diffs)); de.append(np.std(diffs,ddof=1)/math.sqrt(len(diffs)) if len(diffs)>1 else 0)
        ax[1].errorbar(ds,dv,yerr=de,marker="s",capsize=3,color="purple")
        ax[1].axhline(0,color="k",lw=.8)
    ax[1].set_xlabel("density (motors/µm²)"); ax[1].set_ylabel("ΔvelFitX (5e-6 − 1e-5) µm/s")
    ax[1].set_title("Paired timestep difference"); ax[1].grid(alpha=.3)
    # panel 3: avgBsteady vs density
    for dt in dts:
        pts = sorted([(d, aggtab[(dt,d)]) for (t,d) in aggtab if t==dt])
        if not pts: continue
        x=[p[0] for p in pts]; y=[p[1]["avgB"][0] for p in pts]; e=[p[1]["avgB"][1] for p in pts]
        ax[2].errorbar(x,y,yerr=e,marker="^",capsize=3,label=f"dt={dt:.1e}",color=colors.get(dt,"gray"))
    ax[2].set_xlabel("density (motors/µm²)"); ax[2].set_ylabel("avgBsteady (bound heads)")
    ax[2].set_title("Bound count vs density"); ax[2].legend(); ax[2].grid(alpha=.3)
    fig.tight_layout(); fig.savefig(path, dpi=130)

if __name__ == "__main__":
    main()
