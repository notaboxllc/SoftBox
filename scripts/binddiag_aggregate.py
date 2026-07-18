#!/usr/bin/env python3
"""Aggregate binddiag summary CSVs into markdown tables. No headers in the CSVs (data rows only)."""
import sys, os, csv, math
from collections import defaultdict
OUT = "RUN_LOGS/binddiag"

ABC_COLS = ["tag","mode","density","seed","dtdiv","f8rms","f8med","f8p95","headrms",
            "dphi_rms","dpsi_rms","dtheta_rms","dneardist_rms","eligSteps","allPass","nearMiss",
            "missedFrac","substepOnly","endpointEpis","binds","meanBound",
            "loo_dist","loo_phi","loo_psi","loo_theta","loo_preload","loo_energy","loo_headside","loo_inseg",
            "cum_dist","cum_phi","cum_psi","cum_theta","cum_preload","cum_energy","cum_headside","cum_inseg"]
REC_COLS = ["tag","mode","density","seed","phi","psi","theta","binds","detaches","meanBound","bindRate",
            "newlyAdmitted","immediateDetach","invalid","phiErrMed","phiErrP95","surfMed","onsetF8Med","onsetF8P95","onsetF8P99"]
GL_COLS  = ["tag","mode","density","seed","phi","psi","theta","slope","meanBound","binds","detaches","invalid"]

def load(fn, cols):
    p = os.path.join(OUT, fn); rows=[]
    if not os.path.exists(p): return rows
    for line in open(p):
        line=line.strip()
        if not line: continue
        parts=line.split(",")
        if len(parts)<len(cols): continue
        d={}
        for i,c in enumerate(cols):
            v=parts[i]
            try: d[c]=float(v)
            except: d[c]=v
        rows.append(d)
    return rows

def mean(xs): return sum(xs)/len(xs) if xs else float('nan')
def sem(xs):
    if len(xs)<2: return 0.0
    m=mean(xs); return math.sqrt(sum((x-m)**2 for x in xs)/(len(xs)-1)/len(xs))

def agg_abc():
    rows=load("summary_abc.csv", ABC_COLS)
    if not rows: return "_(no abc data)_\n"
    out=["## Part A — unbound motor-domain per-step increments (mean±sem over seeds, at production dt)\n"]
    out.append("| density | dt÷ | F8 rms (nm) | F8 med (nm) | F8 p95 (nm) | head rms | |Δphi| rms° | |Δpsi| rms° | |Δtheta| rms° |")
    out.append("|--:|--:|--:|--:|--:|--:|--:|--:|--:|")
    by=defaultdict(list)
    for r in rows: by[(int(r['density']),int(r['dtdiv']))].append(r)
    for k in sorted(by):
        rs=by[k]
        def col(c): return f"{mean([r[c] for r in rs]):.3f}"
        out.append(f"| {k[0]} | {k[1]} | {col('f8rms')} | {col('f8med')} | {col('f8p95')} | {col('headrms')} | {col('dphi_rms')} | {col('dpsi_rms')} | {col('dtheta_rms')} |")
    out.append("\n## Part B — temporal resolution vs dt (matched physical duration; missedFraction + bind flux)\n")
    out.append("_At fixed density the physical duration and N are identical across dt÷, so the binds ratio vs dt÷=1 IS the flux ratio._\n")
    out.append("| density | dt÷ | eligSteps | binds(sum) | endpointEpis | substepOnly | missedFrac | flux vs dt1 |")
    out.append("|--:|--:|--:|--:|--:|--:|--:|--:|")
    base_binds={}
    for k in sorted(by):
        rs=by[k]
        if k[1]==1: base_binds[k[0]]=sum(r['binds'] for r in rs)
    for k in sorted(by):
        rs=by[k]
        binds=sum(r['binds'] for r in rs); ss=sum(r['substepOnly'] for r in rs); ep=sum(r['endpointEpis'] for r in rs)
        mf=mean([r['missedFrac'] for r in rs]); elig=sum(r['eligSteps'] for r in rs)
        bb=base_binds.get(k[0],0); ratio=f"{binds/bb:.3f}×" if bb else "—"
        out.append(f"| {k[0]} | {k[1]} | {elig:.0f} | {binds:.0f} | {ep:.0f} | {ss:.0f} | {mf:.4f} | {ratio} |")
    out.append("\n## Part C — gate funnel + leave-one-out (production dt, summed over seeds)\n")
    gates=["dist","phi","psi","theta","preload","energy","headside","inseg"]
    for dens in sorted(set(int(r['density']) for r in rows)):
        rs=[r for r in rows if int(r['density'])==dens and int(r['dtdiv'])==1]
        if not rs: continue
        elig=sum(r['eligSteps'] for r in rs)
        out.append(f"\n### density {dens} (production dt, {len(rs)} seeds, eligible motor-steps={elig:.0f})\n")
        out.append("| gate | cumulative pass | cum % | leave-one-out (sole-fail binds) |")
        out.append("|--|--:|--:|--:|")
        for g in gates:
            cp=sum(r[f'cum_{g}'] for r in rs); loo=sum(r[f'loo_{g}'] for r in rs)
            out.append(f"| {g} | {cp:.0f} | {100*cp/max(1,elig):.4f}% | {loo:.0f} |")
    return "\n".join(out)+"\n"

def agg_recruit():
    rows=load("summary_recruit.csv", REC_COLS)
    if not rows: return "_(no recruit data)_\n"
    out=["## Part D1/E — angular-gate recruitment sensitivity (mean±sem over seeds)\n"]
    # group by (density, setting-name derived from tag suffix)
    def setname(tag): return tag.split("_")[-1]
    by=defaultdict(list)
    for r in rows: by[(int(r['density']), setname(r['tag']))].append(r)
    order=["sym0.6","sym0.8","sym1.0","sym1.2","sym1.5","sym2.0","phi0.8","phi1.2","phi1.5","psi0.8","psi1.2","psi1.5","theta0.8","theta1.2","theta1.5"]
    for dens in sorted(set(k[0] for k in by)):
        out.append(f"\n### density {dens}\n")
        out.append("| setting | phi/psi/theta | binds | meanBound | bindRate/motor/s | newlyAdmitted | onsetF8 med (pN) | onsetF8 p99 | immDetach |")
        out.append("|--|--|--:|--:|--:|--:|--:|--:|--:|")
        base=None
        for nm in order:
            rs=by.get((dens,nm))
            if not rs: continue
            g=rs[0]
            def m(c): return mean([r[c] for r in rs])
            row=f"| {nm} | {g['phi']:.0f}/{g['psi']:.0f}/{g['theta']:.0f} | {m('binds'):.1f} | {m('meanBound'):.3f} | {m('bindRate'):.4f} | {m('newlyAdmitted'):.1f} | {m('onsetF8Med'):.3f} | {m('onsetF8P99'):.3f} | {m('immediateDetach'):.1f} |"
            out.append(row)
            if nm=="sym1.0": base=m('bindRate')
        if base:
            out.append(f"\n_bindRate fold-change vs default (sym1.0={base:.4f}):_")
            fold=[]
            for nm in ["sym0.6","sym0.8","sym1.2","sym1.5","sym2.0"]:
                rs=by.get((dens,nm))
                if rs: fold.append(f"{nm}={mean([r['bindRate'] for r in rs])/base:.3f}×")
            out.append("  "+" ".join(fold))
    return "\n".join(out)+"\n"

def agg_glide():
    rows=load("summary_glide.csv", GL_COLS)
    if not rows: return "_(no glide data)_\n"
    out=["## Part D2 — reduced gliding velocity panel (centroid-x LS slope, µm/s; mean±sem over seeds)\n"]
    def setname(tag): return tag.split("_")[-1]
    by=defaultdict(list)
    for r in rows: by[(setname(r['tag']), int(r['density']))].append(r)
    settings=sorted(set(k[0] for k in by)); dens=sorted(set(k[1] for k in by))
    out.append("| setting | "+" | ".join(f"d{d} v (mB)" for d in dens)+" |")
    out.append("|--|"+ "--:|"*len(dens))
    for s in settings:
        cells=[]
        for d in dens:
            rs=by.get((s,d))
            if rs: cells.append(f"{mean([r['slope'] for r in rs]):.2f} ({mean([r['meanBound'] for r in rs]):.2f})")
            else: cells.append("—")
        out.append(f"| {s} | "+" | ".join(cells)+" |")
    return "\n".join(out)+"\n"

if __name__=="__main__":
    print(agg_abc()); print(agg_recruit()); print(agg_glide())
