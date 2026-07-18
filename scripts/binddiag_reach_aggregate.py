#!/usr/bin/env python3
"""Aggregate the NEW STUDY (reach/preload/in-segment) from per-job SUMMARY log lines. Avoids shared-CSV races."""
import sys, os, glob, math
from collections import defaultdict
OUT = "RUN_LOGS/binddiag/logs"

RC = ["tag","mode","density","seed","phi","psi","theta","rBind","preloadMax","segMargin",
      "binds","detaches","rebinds","meanBound","bindRate","continuity","newlyAdmitted","immediateDetach","invalid",
      "eligSteps","distPass","preloadPass","insegPass","allPass","loo_dist","loo_preload","loo_inseg",
      "onsetF8med","onsetF8p95","onsetF8p99","torqMed","extMed","attachEmed","lifetimeMed","dragFrac","meanFdot","surfMed"]
GL = ["tag","mode","density","seed","phi","psi","theta","rBind","preloadMax","segMargin",
      "slope","meanBound","continuity","bindRate","detachRate","dragFrac","meanFdot","binds","detaches","invalid"]

def load(prefix, cols, mode):
    rows=[]
    for fn in glob.glob(os.path.join(OUT, prefix+"*.log")):
        for line in open(fn):
            if line.startswith("SUMMARY,"):
                p=line.strip()[len("SUMMARY,"):].split(",")
                if len(p)<len(cols) or p[1]!=mode: continue
                d={}
                for i,c in enumerate(cols):
                    try: d[c]=float(p[i])
                    except: d[c]=p[i]
                rows.append(d)
    return rows

def mean(xs): return sum(xs)/len(xs) if xs else float('nan')
def setname(tag): return tag.split("_")[-1]

def one_at_a_time(rows, dens, axis_prefix, axis_key, label):
    sub=[r for r in rows if int(r['density'])==dens and setname(r['tag']).startswith(axis_prefix)]
    by=defaultdict(list)
    for r in sub: by[setname(r['tag'])].append(r)
    out=[f"\n### density {dens} — {label} one-at-a-time\n"]
    out.append(f"| {label} | binds | bindRate | meanBound | continuity | newlyAdm | onsetF8 med/p99 | XBext med | attachE | drag frac | lifetime | invalid | loo(d/pl/seg) |")
    out.append("|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|")
    def kf(n):
        import re
        m=re.findall(r"[-+]?\d*\.?\d+", n); return float(m[0]) if m else 0
    base=None
    for nm in sorted(by, key=kf):
        rs=by[nm]
        def m(c): return mean([r[c] for r in rs])
        br=m('bindRate')
        if abs(kf(nm)-(2.0 if axis_prefix=='pl' else 3.0 if axis_prefix=='di' else 0.05))<1e-6: base=br
        out.append(f"| {kf(nm):g} | {m('binds'):.1f} | {br:.3f} | {m('meanBound'):.3f} | {m('continuity'):.3f} | {m('newlyAdmitted'):.1f} | {m('onsetF8med'):.2f}/{m('onsetF8p99'):.2f} | {m('extMed'):.2f} | {m('attachEmed'):.3f} | {m('dragFrac'):.3f} | {m('lifetimeMed'):.0f} | {m('invalid'):.0f} | {m('loo_dist'):.0f}/{m('loo_preload'):.0f}/{m('loo_inseg'):.0f} |")
    if base:
        fold=[]
        for nm in sorted(by, key=kf):
            fold.append(f"{kf(nm):g}={mean([r['bindRate'] for r in by[nm]])/base:.2f}×")
        out.append(f"\n_bindRate fold vs default: "+" ".join(fold)+"_")
    return "\n".join(out)

def glide_panel(rows):
    out=["\n## §8 gliding density panel (velocity µm/s [meanBound]; mean over seeds)\n"]
    by=defaultdict(list)
    for r in rows: by[(setname(r['tag']), int(r['density']))].append(r)
    settings=sorted(set(k[0] for k in by)); dens=sorted(set(k[1] for k in by))
    out.append("| setting | "+" | ".join(f"d{d}" for d in dens)+" |")
    out.append("|--|"+"--:|"*len(dens))
    for s in settings:
        cells=[]
        for d in dens:
            rs=by.get((s,d))
            cells.append(f"{mean([r['slope'] for r in rs]):.2f} [{mean([r['meanBound'] for r in rs]):.2f}]" if rs else "—")
        out.append(f"| {s} | "+" | ".join(cells)+" |")
    # continuity + drag + invalid at high density
    out.append("\n_continuity / dragFrac / invalid at each density (default row check):_")
    for d in dens:
        rs=[r for r in rows if int(r['density'])==d]
        if rs: out.append(f"  d{d}: continuity={mean([r['continuity'] for r in rs]):.3f} dragFrac={mean([r['dragFrac'] for r in rs]):.3f} invalid={sum(r['invalid'] for r in rs):.0f}")
    return "\n".join(out)

if __name__=="__main__":
    rc=load("reach_", RC, "recruit")
    print(f"# NEW STUDY aggregation — {len(rc)} recruit runs\n")
    for dens in [200,700]:
        for pre,key,lab in [("pl","preloadMax","preload (pN)"),("di","rBind","distance (nm)"),("sm","segMargin","segMargin (µm)")]:
            print(one_at_a_time(rc, dens, pre, key, lab))
    gl=load("rgl_", GL, "glide")
    if gl: print(glide_panel(gl))
