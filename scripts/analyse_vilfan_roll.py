#!/usr/bin/env python3
"""Analysis for the Vilfan roll-Brownian study.

Reads RUN_LOGS/vilfan_roll (roll arms), DRAG_RECORDS (deterministic finite drag) and
AXIAL_RECORDS (axial Brownian) for the three-way comparison. Pure stdlib, CPU only.
"""
import json, glob, math, os, sys, statistics as st
from collections import defaultdict

RB, DR, AX = "RUN_LOGS/vilfan_roll", "DRAG_RECORDS", "AXIAL_RECORDS"

def load(d):
    r = {}
    for p in sorted(glob.glob(os.path.join(d, "*.json"))):
        try:
            with open(p) as f: j = json.load(f)
        except Exception: continue
        r[j["armId"]] = j
    return r

def ms(v):
    v=[x for x in v if x is not None and not (isinstance(x,float) and math.isnan(x))]
    if not v: return (float('nan'),float('nan'),0)
    if len(v)==1: return (v[0],float('nan'),1)
    return (st.mean(v), st.stdev(v)/math.sqrt(len(v)), len(v))

def f(x,n=4):
    if x is None or (isinstance(x,float) and (math.isnan(x) or math.isinf(x))): return "n/a"
    return f"{x:.{n}g}"

def before(r):
    h=r["xaHist"]; n=sum(h); return 100.0*sum(h[20:])/max(1,n)
def sbefore(r):
    h=r.get("shadowHist") or []; s=sum(h)
    return 100.0*sum(h[20:])/s if s>0 else float('nan')
def sel(rec,pre,suf="_native"):
    return [r for k,r in rec.items() if k.startswith(pre) and k.endswith(suf)]

def odd(rec,pre):
    """(native, mirror, Omega_odd, Omega_even) per matched seed pair."""
    out=[]
    for k,n in sorted(rec.items()):
        if not (k.startswith(pre) and k.endswith("_native")): continue
        m=rec.get(k.replace("_native","_mirror"))
        if m: out.append((n,m,0.5*(n["omegaRadPerS"]-m["omegaRadPerS"]),
                              0.5*(n["omegaRadPerS"]+m["omegaRadPerS"])))
    return out

# ------------------------------------------------------------------ per arm
def per_arm(rec,pre,title):
    arms=sorted(k for k in rec if k.startswith(pre))
    if not arms: return
    print(f"\n### {title}\n")
    print("| arm | v (µm/s) | ω (rad/s) | ⟨x_A⟩ nm | before % | ⟨θ_A⟩ rad | R | circ mean | M_A pN·nm | wind (rad) | Nb | events |")
    print("|---|---|---|---|---|---|---|---|---|---|---|---|")
    for k in arms:
        r=rec[k]
        print(f"| `{k}` | {f(r['velUmPerS'])} | {f(r['omegaRadPerS'])} | {f(r['meanXaNm'])} | "
              f"{f(before(r),4)} | {f(r['meanThARad'])} | {f(r.get('circResultant'),4)} | "
              f"{f(r.get('circMeanRad'),4)} | {f(r['meanAttachTorquePnNm'])} | "
              f"{f(r.get('windingRad'),5)} | {f(r['medianNb'],3)} | {r['nEvents']} |")

# --------------------------------------------------- three-way comparison
def compare(rb,dr,ax,pre,title):
    R=sel(rb,pre); D=sel(dr,"odnative_e0.01"); A=sel(ax,"prod_in")
    if not A: A=sel(ax,"pilot_in")
    if not R or not D: return
    print(f"\n### {title}\n")
    print("| quantity | deterministic drag | axial Brownian | roll Brownian | roll/det | roll−det |")
    print("|---|---|---|---|---|---|")
    def row(name,fn,n=5):
        d=ms([fn(r) for r in D]); a=ms([fn(r) for r in A]) if A else (float('nan'),)*3
        b=ms([fn(r) for r in R])
        rt=b[0]/d[0] if d[0] else float('nan')
        print(f"| {name} | {f(d[0],n)} ± {f(d[1],2)} | {f(a[0],n)} ± {f(a[1],2)} | "
              f"**{f(b[0],n)} ± {f(b[1],2)}** | **{f(rt,4)}** | {f(b[0]-d[0],3)} |")
    row("⟨x_A⟩ (nm)", lambda r:r["meanXaNm"])
    row("before-centre (%)", before, 4)
    row("⟨θ_A⟩ (rad)", lambda r:r["meanThARad"])
    row("attachment torque M_A (pN·nm)", lambda r:r["meanAttachTorquePnNm"])
    row("⟨ξ_A⟩ (nm)", lambda r:r["meanXiANm"])
    row("v (µm/s)", lambda r:r["velUmPerS"])
    row("inverse pitch λ⁻¹ (µm⁻¹)", lambda r:r["invPitchPerUm"])
    row("duty ratio", lambda r:r["dutyRatio"],4)
    row("median bound heads", lambda r:r["medianNb"],4)
    ob=ms([x for _,_,x,_ in odd(rb,pre)]); od=ms([x for _,_,x,_ in odd(dr,"odnative_e0.01")])
    oa=ms([x for _,_,x,_ in odd(ax,"prod_in")]) or None
    if ob[2] and od[2]:
        print(f"| **Ω_odd (rad/s)** | **{f(od[0],5)} ± {f(od[1],2)}** | {f(oa[0],5)} ± {f(oa[1],2)} | "
              f"**{f(ob[0],5)} ± {f(ob[1],2)}** | **{f(ob[0]/od[0],4)}** | {f(ob[0]-od[0],3)} |")
        eb=ms([x for _,_,_,x in odd(rb,pre)])
        print(f"| Ω_even (rad/s) | 0 exactly | — | {f(eb[0],3)} ± {f(eb[1],2)} | — | — |")
    # circular statistics only exist for roll arms
    cb=ms([r.get("circResultant") for r in R if r.get("circResultant") is not None])
    if cb[2]: print(f"| circular resultant R | — | — | **{f(cb[0],4)} ± {f(cb[1],2)}** | — | — |")

# ------------------------------------------------------------- depletion
def depletion(rb,pre="prodshadow"):
    arms=[r for k,r in rb.items() if k.startswith(pre)]
    if not arms: arms=[r for k,r in rb.items() if k.startswith("pilotshadow")]
    if not arms: return
    print("\n### Real vs no-depletion shadow (the load-bearing causal control)\n")
    print("| seed | ⟨x_A⟩ real | ⟨x_A⟩ shadow | Δ_depletion | before real % | before shadow % |")
    print("|---|---|---|---|---|---|")
    ds=[]
    for r in sorted(arms,key=lambda z:z["seed"]):
        d=r["meanXaNm"]-r["shadowMeanXaNm"]; ds.append(d)
        print(f"| {r['seed']} | {f(r['meanXaNm'],5)} | {f(r['shadowMeanXaNm'],5)} | {f(d,5)} | "
              f"{f(before(r),4)} | {f(sbefore(r),4)} |")
    m,s,n=ms(ds); rm=ms([r["meanXaNm"] for r in arms]); sm=ms([r["shadowMeanXaNm"] for r in arms])
    print(f"| **mean (n={n})** | **{f(rm[0],5)} ± {f(rm[1],2)}** | **{f(sm[0],5)} ± {f(sm[1],2)}** | "
          f"**{f(m,5)} ± {f(s,2)}** | | |")
    if s and not math.isnan(s) and s>0:
        print(f"\n**Δ_depletion = {m:.4f} ± {s:.4f} nm — resolved at {abs(m/s):.1f}σ.**")

# ----------------------------------------------------- roll diagnostics
def rolldiag(rb,pre):
    arms=sel(rb,pre)
    if not arms: return
    print(f"\n### Roll diagnostics — branch crossings, winding, angular bands ({pre})\n")
    bands=arms[0].get("rollBands") or []
    sig=0.049
    print("| band (rad) | forward | backward | total | validity (band ≫ step RMS 0.049) |")
    print("|---|---|---|---|---|")
    for i,b in enumerate(bands):
        fw=sum(r["rollFwd"][i] for r in arms); bw=sum(r["rollBwd"][i] for r in arms)
        if b==0: v="RAW JITTER — never mechanistic"
        elif b < 2*sig: v="resolution-limited, DO NOT interpret"
        else: v="trustworthy"
        print(f"| {b:g} | {fw} | {bw} | {fw+bw} | {v} |")
    print()
    print(f"- angular BRANCH crossings (±π): **{sum(r['nRollCross'] for r in arms)}**")
    print(f"- max missed-crossing probability: **{max(r['maxMissProb'] for r in arms):.3g}**; "
          f"mean **{max(r['meanMissProb'] for r in arms):.3g}**")
    print(f"- free-roll substeps (Nb = 0): {sum(r['nFreeRollSteps'] for r in arms)}; "
          f"substep subdivisions {sum(r['nRollSubdiv'] for r in arms)}")
    w=ms([r["windingRad"] for r in arms])
    print(f"- sampled |Δθ| path (winding) {f(w[0],5)} rad — RESOLUTION-DEPENDENT, quoted at the "
          f"production substep only")
    th=ms([abs(r["thetaEndRad"]-r["thetaWarmRad"]) for r in arms])
    print(f"- net |ΔΘ| over the analysed window: {f(th[0],5)} rad = {f(th[0]/(2*math.pi),4)} turns")

# ----------------------------------------------------------- controls
def controls(rb):
    print("\n### Controls, all under identical roll-Brownian dynamics\n")
    print("| control | v (µm/s) | Ω_odd (rad/s) | Ω_even | ⟨x_A⟩ nm | before % | M_A pN·nm |")
    print("|---|---|---|---|---|---|---|")
    for pre,name in (("prod_in","full mechanism"),("ctl_alpha0","α = 0"),
                     ("ctl_d0","d = 0"),("ctl_achiral","achiral lattice"),
                     ("ctl_broff","Brownian OFF")):
        arms=sel(rb,pre)
        if not arms: continue
        pr=odd(rb,pre)
        v=ms([r["velUmPerS"] for r in arms])[0]
        o=ms([x for _,_,x,_ in pr]); e=ms([x for _,_,_,x in pr])
        xa=ms([r["meanXaNm"] for r in arms])[0]; bc=ms([before(r) for r in arms])[0]
        t=ms([r["meanAttachTorquePnNm"] for r in arms])[0]
        print(f"| {name} | {f(v,4)} | {f(o[0],4)} ± {f(o[1],2)} | {f(e[0],3)} | {f(xa,4)} | "
              f"{f(bc,4)} | {f(t,4)} |")

# ------------------------------------------------------------- sizing
def sizing(rb,pre="pilot_in"):
    arms=sel(rb,pre)
    if len(arms)<2: return
    # mirror arms are also valid samples of the mirror-EVEN quantities
    allarms=[r for k,r in rb.items() if k.startswith(pre)]
    print("\n### Pilot variance and campaign sizing\n")
    print("| quantity | pilot mean | between-arm sd | SEM | n | n for 10% | n for 25% |")
    print("|---|---|---|---|---|---|---|")
    def line(nm,vals,ref):
        m,s,n=ms(vals); sd=s*math.sqrt(n) if n>1 else float('nan')
        out=[]
        for pct in (10,25):
            d=pct/100.0*abs(ref)
            out.append(math.ceil(2*((1.96+0.84)*sd/d)**2) if sd and d>0 else float('nan'))
        print(f"| {nm} | {f(m,5)} | {f(sd,3)} | {f(s,3)} | {n} | {out[0]} | {out[1]} |")
        return sd
    sd_xa = line("⟨x_A⟩ (nm)", [r["meanXaNm"] for r in allarms], 1.6445)
    line("before-centre (%)", [before(r) for r in allarms], 58.98)
    sd_th = line("⟨θ_A⟩ (rad)", [abs(r["meanThARad"]) for r in allarms], 0.1061)
    line("M_A (pN·nm)", [abs(r["meanAttachTorquePnNm"]) for r in allarms], 1.757)
    o=[x for _,_,x,_ in odd(rb,pre)]
    if len(o)>1: line("Ω_odd (rad/s)", o, 0.51936)
    # drift vs rotational diffusion
    print()
    for r in allarms[:1]:
        dT=abs(r["thetaEndRad"]-r["thetaWarmRad"]); T=r["tEnd"]-r["tWarm"]
        print(f"- drift over the analysed window: |ΔΘ| = {dT:.2f} rad in {T:.0f} s "
              f"(Ω = {dT/T:.4f} rad/s)")
    if len(o)>1:
        m,s,n=ms(o)
        print(f"- Ω_odd = {m:.4f} ± {s:.4f} rad/s at n={n}: **nonzero drift resolved at "
              f"{abs(m/s):.1f}σ**" if s and s>0 else "")

# ------------------------------------------------------------- health
def health(rb):
    v=list(rb.values())
    if not v: return
    print("\n### Numerical health\n")
    print(f"- arms **{len(v)}**, chemical events **{sum(r['nEvents'] for r in v):,}**, "
          f"stochastic substeps **{sum(r.get('nSubsteps',0) for r in v):,}**, "
          f"bridge draws **{sum(r.get('nBridgeDraws',0) for r in v):,}**")
    print(f"- max event discontinuity |ΔX| = **{max(r.get('maxEventJumpX',0) for r in v):.3g} nm**, "
          f"|ΔΘ| = **{max(r.get('maxEventJumpTheta',0) for r in v):.3g} rad**")
    print(f"- max missed-crossing probability across all arms: "
          f"**{max(r.get('maxMissProb',0) for r in v):.3g}**")
    print(f"- angular branch crossings total: **{sum(r.get('nRollCross',0) for r in v)}**; "
          f"free-roll substeps {sum(r.get('nFreeRollSteps',0) for r in v)}")
    caps=[k for k,r in rb.items() if r.get("travelCapHit")]
    print(f"- travel-cap hits: {len(caps)}")
    notes={k:r["note"] for k,r in rb.items() if r["note"].strip()}
    for k,n in sorted(notes.items()): print(f"    - `{k}`: {n}")
    print("\n**Stationarity (analysed window split into halves of simulated time):**\n")
    print("| stage | n | ω first half | ω second half | ratio | v first | v second |")
    print("|---|---|---|---|---|---|---|")
    for pre,nm in (("pilot_in","pilot"),("prod_in","production")):
        arms=sel(rb,pre)
        if not arms: continue
        o1=ms([r["omegaFirstHalf"] for r in arms])[0]; o2=ms([r["omegaSecondHalf"] for r in arms])[0]
        v1=ms([r["velFirstHalf"] for r in arms])[0];  v2=ms([r["velSecondHalf"] for r in arms])[0]
        print(f"| {nm} | {len(arms)} | {f(o1,4)} | {f(o2,4)} | {f(o2/o1,4)} | {f(v1,4)} | {f(v2,4)} |")

if __name__=="__main__":
    os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)),".."))
    rb,dr,ax=load(RB),load(DR),load(AX)
    if not rb: sys.exit("no roll records")
    w=sys.argv[1] if len(sys.argv)>1 else "all"
    if w in ("all","pilot"):
        per_arm(rb,"pilot_in","Pilot — independent roll noise")
        per_arm(rb,"pilot_an","Pilot — ANTISYMMETRIC roll noise (pathwise correctness control)")
        rolldiag(rb,"pilot_in"); sizing(rb)
        compare(rb,dr,ax,"pilot_in","Pilot: three-way comparison")
        depletion(rb,"pilotshadow")
    if w in ("all","prod"):
        per_arm(rb,"prod_in","Production — independent roll noise")
        rolldiag(rb,"prod_in")
        compare(rb,dr,ax,"prod_in","Production: deterministic vs axial vs roll Brownian")
        depletion(rb,"prodshadow")
    if w in ("all","controls"): controls(rb)
    if w in ("all","health"):   health(rb)
