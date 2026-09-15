import csv,os,sys,statistics as st,math
O="RUN_LOGS/motor_audit/campaigns_2026-09/HEADTILT_SWEEP"; S=["20260901","20260902","20260903"]
def v(n):
    f=os.path.join(O,n,"trajectory_summary.csv")
    if not os.path.exists(f): return None
    r=list(csv.DictReader(open(f),delimiter='\t'))
    if not r: return None
    x=r[-1]; d=abs(float(x["fwd_um"]))
    return (float(x["vfit_um_s"]),float(x["avgBound"]),float(x["rollTurns"])/d if d>0.02 else float('nan'))
arms=[("tm60",-60),("tm45",-45),("tm30",-30),("tm20",-20),("tm10",-10),("t000",0),
      ("tp10",10),("tp20",20),("tp30",30),("tp45",45),("tp60",60)]
# SIGN (verified 2026-09-10, empirically from frame geometry): converter axial offset = -7.0*sin(tilt) nm,
# positive = BARBED. So NEGATIVE tilt = converter barbed-side = the BIOLOGICAL pose. vfit>0 is pointed-
# leading (fwd = -bhat, SiteNormalLongGlideHarness:595) = the correct glide direction.
print(f"{'tilt':>6}{'conv nm(+=barbed)':>19}{'mean v':>9}{'ratio':>7}{'avgB':>7}{'turns/um':>10}{'n':>3}{'signs':>7}")
base=None; res={}
for tag,deg in arms:
    ok=[x for x in (v(f"{tag}_{s}") for s in S) if x]
    if not ok: print(f"{deg:+6d}   (pending)"); continue
    m=st.mean(x[0] for x in ok); res[deg]=m
    if deg==0: base=m
    print(f"{deg:+6d}{-7.0*math.sin(math.radians(deg)):>10.2f}nm{m:9.3f}"
          f"{(m/base if base else float('nan')):7.3f}{st.mean(x[1] for x in ok):7.2f}"
          f"{st.mean(x[2] for x in ok):10.2f}{len(ok):3d}"
          f"{''.join('+' if x[0]>0 else '-' for x in ok):>7}")
print("\nBARBED-side (neg tilt, BIOLOGICAL) vs POINTED-side (pos tilt) at matched |tilt|:")
for d in (10,20,30,45,60):
    if d in res and -d in res:
        print(f"  |{d:2d}| deg:  barbed {res[-d]:+.3f}   pointed {res[d]:+.3f}   ratio {res[-d]/res[d] if res[d] else float('nan'):7.2f}x")
