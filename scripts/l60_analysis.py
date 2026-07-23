#!/usr/bin/env python3
"""L60 vs L40 gliding sensitivity analysis.
Reads single-head GPU cells (L60, L40 frozen baseline, L40 mode-1 controls) + dimer CPU CSV (L40/L60),
fits hyperbolic + Hill density-response, and emits paired L40-vs-L60 comparison tables + CSVs.
Usage: python3 scripts/l60_analysis.py <outdir>
"""
import sys, os, json, glob, math, csv
import numpy as np
from scipy.optimize import curve_fit

OUT = sys.argv[1] if len(sys.argv) > 1 else "docs/canonical_freeze"
ROOT = "RUN_LOGS/l60_sensitivity"
L60_SH   = f"{ROOT}/single_head_l60"
L40_BASE = "RUN_LOGS/single_head_density_sweep_long"      # frozen mode-0 40k baseline
L40_CTRL = f"{ROOT}/single_head_l40_ctrl"                 # mode-1 controls (bracket the rupture-mode delta)
DIMER_CSV = f"{ROOT}/dimer_cpu/dimer_l60_cells.csv"

def load_sh(d):
    cells = []
    for f in sorted(glob.glob(f"{d}/cell_d*_s*.json")):
        try: j = json.load(open(f))
        except Exception: continue
        if j.get("status") not in (None, "ok"): continue
        if j.get("invalid_states", 0) or j.get("solver_failures", 0): continue
        cells.append(j)
    return cells

def by_density(cells, vkey="vel_prod"):
    """productive speed = -vel_prod (negative vel_prod = pointed-first = productive). Return {rho: (mean,sem,n, extra)}."""
    dd = {}
    for j in cells:
        rho = float(j["density"]); dd.setdefault(rho, []).append(j)
    out = {}
    for rho, js in sorted(dd.items()):
        v = np.array([float(x[vkey]) for x in js])           # productive (positive) speed
        mb = np.array([float(x.get("mean_bound_heads",0)) for x in js])
        atp = np.array([float(x.get("atp_turnover",0)) for x in js])
        life = np.array([float(x.get("lifetime_mean_ms",0)) for x in js])
        nf = np.array([float(x.get("net_force_pn",0)) for x in js])
        pk = np.array([float(x.get("peak_load_pn",0)) for x in js])
        cont = np.array([float(x.get("continuity",0)) for x in js])
        out[rho] = dict(v=v.mean(), vsem=v.std(ddof=1)/math.sqrt(len(v)) if len(v)>1 else 0.0, n=len(v),
                        vraw=v, bound=mb.mean(), atp=atp.mean(), life=life.mean(), netf=nf.mean(),
                        peak=pk.mean(), cont=cont.mean())
    return out

def load_dimer(csvf):
    """{ (L,rho): list of rows }"""
    if not os.path.exists(csvf): return {}
    rows = list(csv.DictReader(open(csvf)))
    dd = {}
    for r in rows:
        try:
            if int(r["invalid"]) or int(r["solverFail"]): continue
        except Exception: pass
        L = int(float(r["L"])); rho = float(r["density"])
        dd.setdefault((L,rho), []).append(r)
    return dd

def dimer_by_density(dd, L):
    out = {}
    for (LL,rho), rs in sorted(dd.items()):
        if LL != L: continue
        v = np.array([float(r["velProd"]) for r in rs])       # productive speed
        mb = np.array([float(r["meanBound"]) for r in rs])
        tf = np.array([float(r["twoFrac"]) for r in rs])
        gap = np.array([float(r["maxGap_nm"]) for r in rs])
        out[rho] = dict(v=v.mean(), vsem=v.std(ddof=1)/math.sqrt(len(v)) if len(v)>1 else 0.0, n=len(v),
                        vraw=v, bound=mb.mean(), two=tf.mean(), gap=gap.max())
    return out

def hyperbolic(rho, vmax, rhalf): return vmax*rho/(rhalf+rho)
def hill(rho, vmax, rhalf, n):    return vmax*rho**n/(rhalf**n+rho**n)

def fit(dmap):
    x = np.array(sorted(dmap)); y = np.array([dmap[r]["v"] for r in x])
    if len(x) < 3: return None
    vmax0 = max(y.max(), 0.1); rhalf0 = x[len(x)//2]
    res = {}
    try:
        ph, ch = curve_fit(hyperbolic, x, y, p0=[vmax0, rhalf0], bounds=([0,1],[20*vmax0+1,2e5]), maxfev=200000)
        yh = hyperbolic(x, *ph); ss=((y-yh)**2).sum(); st=((y-y.mean())**2).sum()
        eh = np.sqrt(np.diag(ch))
        res["hyper"] = dict(vmax=ph[0], vmax_se=eh[0], rhalf=ph[1], rhalf_se=eh[1], r2=1-ss/st if st>0 else 0, k=2, ss=ss, n=len(x))
    except Exception as e: res["hyper"] = None
    try:
        pl, cl = curve_fit(hill, x, y, p0=[vmax0, rhalf0, 1.0], bounds=([0,1,0.3],[20*vmax0+1,2e5,6]), maxfev=200000)
        yl = hill(x, *pl); ss=((y-yl)**2).sum(); st=((y-y.mean())**2).sum()
        el = np.sqrt(np.diag(cl))
        res["hill"] = dict(vmax=pl[0], rhalf=pl[1], n=pl[2], n_se=el[2], r2=1-ss/st if st>0 else 0, k=3, ss=ss, nn=len(x))
    except Exception: res["hill"] = None
    # AIC (Gaussian): k params, n points
    for m in ("hyper","hill"):
        if res.get(m):
            n=res[m].get("n",res[m].get("nn")); ss=res[m]["ss"]; kk=res[m]["k"]
            res[m]["aic"] = n*math.log(ss/n) + 2*kk if ss>0 and n>0 else float("nan")
    return res

def bootstrap_ci(dmap, param="vmax", B=2000):
    """seed-bootstrap CI on Vmax/rhalf by resampling the per-density seed pools."""
    x = np.array(sorted(dmap));
    if len(x) < 3: return None
    vals=[]
    rng = np.random.default_rng(12345)
    for _ in range(B):
        y = np.array([rng.choice(dmap[r]["vraw"], size=len(dmap[r]["vraw"]), replace=True).mean() for r in x])
        try:
            p,_ = curve_fit(hyperbolic, x, y, p0=[max(y.max(),0.1), x[len(x)//2]], bounds=([0,1],[1e4,2e5]), maxfev=100000)
            vals.append(p[0] if param=="vmax" else p[1])
        except Exception: pass
    if not vals: return None
    return (np.percentile(vals,2.5), np.percentile(vals,97.5))

# ---------------- load ----------------
sh_l60 = by_density(load_sh(L60_SH))
sh_l40 = by_density(load_sh(L40_BASE))
sh_l40c = by_density(load_sh(L40_CTRL)) if os.path.isdir(L40_CTRL) else {}
dd = load_dimer(DIMER_CSV)
dm_l40 = dimer_by_density(dd, 40); dm_l60 = dimer_by_density(dd, 60)

print(f"# L60 sensitivity analysis")
print(f"single-head: L60 {len(sh_l60)} densities, L40-baseline {len(sh_l40)} densities, L40-ctrl {len(sh_l40c)}")
print(f"dimer: L40 {len(dm_l40)} densities, L60 {len(dm_l60)} densities")

def report(name, l40, l60):
    print(f"\n## {name}")
    f40 = fit(l40); f60 = fit(l60)
    if f40 and f60 and f40.get("hyper") and f60.get("hyper"):
        ci = bootstrap_ci(l40); ci60 = bootstrap_ci(l60)
        h40, h60 = f40["hyper"], f60["hyper"]
        print(f"  L40 hyper: Vmax={h40['vmax']:.3f}±{h40['vmax_se']:.3f}  rho_half={h40['rhalf']:.1f}±{h40['rhalf_se']:.1f}  R2={h40['r2']:.4f}" + (f"  Vmax95CI=[{ci[0]:.2f},{ci[1]:.2f}]" if ci else ""))
        print(f"  L60 hyper: Vmax={h60['vmax']:.3f}±{h60['vmax_se']:.3f}  rho_half={h60['rhalf']:.1f}±{h60['rhalf_se']:.1f}  R2={h60['r2']:.4f}" + (f"  Vmax95CI=[{ci60[0]:.2f},{ci60[1]:.2f}]" if ci60 else ""))
        print(f"  RATIO L60/L40: Vmax={h60['vmax']/h40['vmax']:.3f}  rho_half={h60['rhalf']/h40['rhalf']:.3f}")
        if f40.get("hill") and f60.get("hill"):
            print(f"  L40 Hill n={f40['hill']['n']:.2f}±{f40['hill'].get('n_se',0):.2f} (dAIC hill-hyper={f40['hill']['aic']-h40['aic']:+.1f})  L60 Hill n={f60['hill']['n']:.2f}±{f60['hill'].get('n_se',0):.2f} (dAIC={f60['hill']['aic']-h60['aic']:+.1f})")
    print(f"  {'rho':>6} | {'v_L40':>8} {'v_L60':>8} {'ratio':>6} | {'bnd40':>6} {'bnd60':>6} | notes")
    for rho in sorted(set(l40)|set(l60)):
        a=l40.get(rho); b=l60.get(rho)
        va=f"{a['v']:+.3f}" if a else "   —   "; vb=f"{b['v']:+.3f}" if b else "   —   "
        rt=f"{b['v']/a['v']:.3f}" if a and b and a['v']!=0 else "  —  "
        ba=f"{a['bound']:.2f}" if a else " — "; bb=f"{b['bound']:.2f}" if b else " — "
        print(f"  {rho:6.0f} | {va:>8} {vb:>8} {rt:>6} | {ba:>6} {bb:>6}")
    return f40, f60

sh_f40, sh_f60 = report("SINGLE-HEAD (L40 baseline vs L60)", sh_l40, sh_l60)
if sh_l40c:
    print("\n## SINGLE-HEAD L40 mode-1 controls vs frozen mode-0 baseline (rupture-mode bracket)")
    for rho in sorted(sh_l40c):
        a=sh_l40.get(rho); c=sh_l40c.get(rho)
        if a and c: print(f"  rho={rho:.0f}: mode0={a['v']:+.3f}  mode1={c['v']:+.3f}  delta={100*(c['v']-a['v'])/a['v'] if a['v'] else 0:+.2f}%")
if dm_l40 and dm_l60:
    dm_f40, dm_f60 = report("HMM-DIMER (L40 vs L60, CPU reduced)", dm_l40, dm_l60)
    # dimer vs single at L60
    print("\n## DIMER vs SINGLE-HEAD at L60 (matched density)")
    for rho in sorted(set(dm_l60)&set(sh_l60)):
        d=dm_l60[rho]; s=sh_l60[rho]
        print(f"  rho={rho:.0f}: single v={s['v']:+.3f}  dimer v={d['v']:+.3f}  dimer/single={d['v']/s['v'] if s['v'] else 0:.3f}  (two-head frac {d['two']:.4f})")

# ---------------- write CSVs ----------------
os.makedirs(OUT, exist_ok=True)
with open(f"{OUT}/L60_GLIDING_CELLS.csv","w") as f:
    w=csv.writer(f); w.writerow(["arch","L","rho","v_productive_mean","v_sem","n_seed","mean_bound","atp_turnover","lifetime_ms","net_force_pn","peak_load_pn","continuity","two_head_frac"])
    for rho,a in sorted(sh_l40.items()): w.writerow(["single",40,rho,f"{a['v']:.5f}",f"{a['vsem']:.5f}",a['n'],f"{a['bound']:.4f}",f"{a['atp']:.1f}",f"{a['life']:.4f}",f"{a['netf']:.4f}",f"{a['peak']:.3f}",f"{a['cont']:.4f}",""])
    for rho,a in sorted(sh_l60.items()): w.writerow(["single",60,rho,f"{a['v']:.5f}",f"{a['vsem']:.5f}",a['n'],f"{a['bound']:.4f}",f"{a['atp']:.1f}",f"{a['life']:.4f}",f"{a['netf']:.4f}",f"{a['peak']:.3f}",f"{a['cont']:.4f}",""])
    for rho,a in sorted(dm_l40.items()): w.writerow(["dimer",40,rho,f"{a['v']:.5f}",f"{a['vsem']:.5f}",a['n'],f"{a['bound']:.4f}","","","","",f"{a.get('gap',0):.2f}",f"{a['two']:.5f}"])
    for rho,a in sorted(dm_l60.items()): w.writerow(["dimer",60,rho,f"{a['v']:.5f}",f"{a['vsem']:.5f}",a['n'],f"{a['bound']:.4f}","","","","",f"{a.get('gap',0):.2f}",f"{a['two']:.5f}"])

with open(f"{OUT}/L60_DENSITY_FITS.csv","w") as f:
    w=csv.writer(f); w.writerow(["arch","L","model","Vmax","Vmax_se","rho_half","rho_half_se","hill_n","R2","AIC"])
    def wf(arch,L,ff):
        if ff and ff.get("hyper"): h=ff["hyper"]; w.writerow([arch,L,"hyperbolic",f"{h['vmax']:.4f}",f"{h['vmax_se']:.4f}",f"{h['rhalf']:.2f}",f"{h['rhalf_se']:.2f}","",f"{h['r2']:.4f}",f"{h['aic']:.2f}"])
        if ff and ff.get("hill"): h=ff["hill"]; w.writerow([arch,L,"hill",f"{h['vmax']:.4f}","",f"{h['rhalf']:.2f}","",f"{h['n']:.3f}",f"{h['r2']:.4f}",f"{h['aic']:.2f}"])
    wf("single",40,sh_f40); wf("single",60,sh_f60)
    if dm_l40 and dm_l60: wf("dimer",40,dm_f40); wf("dimer",60,dm_f60)
print(f"\nwrote {OUT}/L60_GLIDING_CELLS.csv and {OUT}/L60_DENSITY_FITS.csv")
