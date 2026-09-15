import csv,os,math,statistics as st
O="RUN_LOGS/motor_audit/campaigns_2026-09/HEADTILT_SWEEP"; S=["20260901","20260902","20260903"]
MINSTEP=180000   # arms below this are still RUNNING: a partial arm's vfit is a different (noisier,
                 # transient-contaminated) measurement and must NOT be averaged with completed arms.
def v(n):
    f=os.path.join(O,n,"trajectory_summary.csv")
    if not os.path.exists(f): return None
    r=list(csv.DictReader(open(f),delimiter='\t'))
    if not r: return None
    x=r[-1]
    if int(x["step"]) < MINSTEP: return None      # incomplete -> excluded, not silently averaged
    d=abs(float(x["fwd_um"]))
    tw=float(x["rollTurns"])/d if d>0.05 else float('nan')
    return (float(x["vfit_um_s"]),float(x["avgBound"]),tw)
def pending(n):
    f=os.path.join(O,n,"trajectory_summary.csv")
    if not os.path.exists(f): return False
    r=list(csv.DictReader(open(f),delimiter='\t'))
    return bool(r) and int(r[-1]["step"]) < MINSTEP
def agg(tag):
    ok=[x for x in (v(f"{tag}_{s}") for s in S) if x]
    if not ok: return None
    m=st.mean(x[0] for x in ok)
    sem=(st.stdev(x[0] for x in ok)/len(ok)**.5) if len(ok)>1 else float('nan')
    tws=[x[2] for x in ok if x[2]==x[2]]
    return m,sem,st.mean(x[1] for x in ok),len(ok),''.join('+' if x[0]>0 else '-' for x in ok),(st.mean(tws) if tws else float('nan'))
base=agg("t000")
b0=base[0] if base else float('nan')

print("\n=== AXIS 1 (PRIMARY): CONVERTER AZIMUTH -- head depth FIXED at 7.00 nm ===")
print(f"{'beta':>6}{'conv axial':>12}{'arm nm':>8}{'mean v':>9}{'SEM':>7}{'vs 0':>7}{'avgB':>7}{'twirl':>9}{'n':>3}{'signs':>6}")
if base: print(f"{0:+6.0f}{0.0:>12.2f}{7.000:>8.3f}{b0:9.3f}{base[1]:7.3f}{1.0:7.2f}{base[2]:7.2f}{base[5]:9.2f}{base[3]:3d}{base[4]:>6}")
caz={}
for d in (15,30,45,60,90):
    for sgn,tag in ((+1,f"cazp{d}"),(-1,f"cazm{d}")):
        a=agg(tag)
        if not a:
            if pending(f"{tag}_20260901"): print(f"{sgn*d:+6d}   (still running)")
            continue
        caz[sgn*d]=a
        print(f"{sgn*d:+6d}{3.5*math.sin(math.radians(sgn*d)):>12.2f}"
              f"{7.0*math.cos(0.5*math.radians(d)):>8.3f}{a[0]:9.3f}{a[1]:7.3f}"
              f"{a[0]/b0:7.2f}{a[2]:7.2f}{a[5]:9.2f}{a[3]:3d}{a[4]:>6}")
print("\n  EVEN/ODD decomposition  (odd = converter displacement; even = reangling side-effect)")
print(f"  {'|beta|':>7}{'odd (p-m)/2':>14}{'even (p+m)/2':>15}{'even vs v(0)':>14}")
for d in (15,30,45,60,90):
    if d in caz and -d in caz:
        p,m=caz[d][0],caz[-d][0]
        print(f"  {d:>7}{(p-m)/2:>14.3f}{(p+m)/2:>15.3f}{((p+m)/2)/b0:>14.2f}")

print("\n=== AXIS 2 (CONTRAST): HEAD TILT -- reaches further but SINKS the head ===")
print(f"{'tilt':>6}{'conv axial':>12}{'headCtr r':>11}{'mean v':>9}{'SEM':>7}{'vs 0':>7}{'avgB':>7}{'twirl':>9}{'n':>3}  steric")
for tag,deg in [("tm60",-60),("tm45",-45),("tm30",-30),("tm20",-20),("tm10",-10),("t000",0),
                ("tp10",10),("tp20",20),("tp30",30),("tp45",45),("tp60",60)]:
    a=agg(tag)
    if not a: continue
    ctr=3.5+3.5*math.cos(math.radians(deg))
    flag="DEEP - judge as possibly unphysical" if ctr<6.0 else ("sinking" if ctr<6.5 else "")
    print(f"{deg:+6d}{-7.0*math.sin(math.radians(deg)):>12.2f}{ctr:>11.2f}{a[0]:9.3f}{a[1]:7.3f}"
          f"{a[0]/b0:7.2f}{a[2]:7.2f}{a[5]:9.2f}{a[3]:3d}  {flag}")
print("\n  MATCHED-DISPLACEMENT TEST (same converter axial offset, different head depth):")
print("    convaz +90 = +3.50 nm @ head 7.00 nm   vs   headtilt -30 = +3.50 nm @ head 6.53 nm")
a,b=caz.get(90),agg("tm30")
if a and b:
    print(f"    convaz+90 v={a[0]:.3f}+/-{a[1]:.3f}   headtilt-30 v={b[0]:.3f}+/-{b[1]:.3f}")
    print("    => gain survives at FIXED depth: it is CONVERTER GEOMETRY"
          if a[0] > b[0]-2*max(a[1],b[1],1e-9) else
          "    => gain does NOT survive at fixed depth: the tilt gain owes something to head BURIAL")

print("\n  NOTE: every arm above runs at eps=0 (no imposed stroke skew), so the twirl column is the")
print("  UNDRIVEN roll -- established as noise-dominated (the eps=0 mirror runs). It is shown for")
print("  completeness, NOT as a measure of how head pose affects twirling. That needs tilt x eps>0.")
