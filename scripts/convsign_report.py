#!/usr/bin/env python3
"""Converter axial offset from viewer frames: (C - xF8) . barbed, per BOUND motor.

Frame schema (SiteNormalLongGlideHarness.frameJson): entry id=m carries lever.end2 = C (the converter joint)
and motor.end1 = xH; a second entry id=N+m carries motor.end1 = xF8. Barbed = +uVec = +x in this scene (the
run banner prints polarity barbed(end2) = (1,0,0); the rigid filament holds that to within ~0.3 deg)."""
import json,glob,os,sys,statistics as st
OUT=sys.argv[1] if len(sys.argv)>1 else "RUN_LOGS/motor_audit/campaigns_2026-09/CONVSIGN"
ARMS=[("ctl_tm20","PARSER CONTROL: headtilt -20 (sign known: BARBED)",+2.39),
      ("caz_p60","convaz +60",None),("caz_m60","convaz -60",None)]
print(f"\nConverter axial offset (C - xF8).barbed, nm   [+ = BARBED-proximal]\n")
print(f"{'arm':<12}{'frames':>7}{'bound samples':>15}{'mean nm':>10}{'SEM':>8}{'sd':>7}{'predicted':>11}  note")
res={}
for tag,label,pred in ARMS:
    fs=sorted(glob.glob(f"{OUT}/{tag}/frames/*.json"))
    vals=[]
    for f in fs:
        try: d=json.load(open(f))
        except Exception: continue
        mys=d.get("myosins",[])
        C={}; F={}
        for e in mys:
            i=e.get("id")
            if e.get("bound") is True: C[i]=e["lever"]["end2"]
            F[i]=e["motor"]["end1"]
        for i,c in C.items():
            for cand in (i+10000,i+20000,i+50000):  # N unknown here; resolved below by exact search
                pass
        # N is the motor count: the F8 marker for motor m has id = N+m. Recover N as min(id) of entries whose
        # motor.end1 != lever.end2 ... simpler: for each bound id i, the marker is the entry whose lever.end1
        # equals the bound entry's motor.end1 (both are xH).
        xh={i:e["motor"]["end1"] for e in mys if e.get("id") in C for i in [e["id"]]}
        for e in mys:
            l1=e.get("lever",{}).get("end1")
            if l1 is None: continue
            for i,h in xh.items():
                if l1==h and e["id"]!=i:
                    c=C[i]; f8=e["motor"]["end1"]
                    vals.append((c[0]-f8[0])*1000.0)
    if not vals:
        print(f"{tag:<12}{len(fs):>7}{0:>15}   (no bound samples yet)"); continue
    m=st.mean(vals); sd=st.stdev(vals) if len(vals)>1 else float('nan'); sem=sd/len(vals)**.5
    res[tag]=(m,sem,len(vals))
    pr=f"{pred:+.2f}" if pred is not None else "--"
    print(f"{tag:<12}{len(fs):>7}{len(vals):>15}{m:>10.2f}{sem:>8.2f}{sd:>7.2f}{pr:>11}  {label}")
if "ctl_tm20" in res:
    m,sem,n=res["ctl_tm20"]
    ok = abs(m-2.39) < max(3*sem,0.6)
    print(f"\n  PARSER CONTROL: {'PASS' if ok else 'FAIL'} -- measured {m:+.2f} +/- {sem:.2f} nm vs predicted +2.39")
    if not ok: print("  => do NOT read the convaz rows; the parser or the frame schema assumption is wrong.")
if "caz_p60" in res and "caz_m60" in res:
    p,ps,_=res["caz_p60"]; n,ns,_=res["caz_m60"]
    print(f"\n  convaz +60 -> {p:+.2f} +/- {ps:.2f} nm ; convaz -60 -> {n:+.2f} +/- {ns:.2f} nm")
    print(f"  antisymmetry (p+n)/2 = {(p+n)/2:+.2f} (should be ~0) ; realized amplitude (p-n)/2 = {(p-n)/2:+.2f} nm vs nominal 3.03")
    fav = "+alpha" if p>n else "-alpha"
    side = "BARBED" if (p>n and p>0) or (n>p and n>0) else "POINTED"
    print(f"  => the FAVOURABLE convaz sign ({fav}, the faster one) puts the converter on the {side} side")
