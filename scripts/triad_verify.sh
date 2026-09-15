#!/usr/bin/env bash
# Does the 3-contact surface patch glide like a single spring?
# 3 non-collinear contacts fix all 6 DOF, so unlike a 1-contact ball joint or a 2-contact hinge this bond can
# actually transmit AXIAL TORQUE -- the channel twirl lives in. First it has to gnot break gliding.
set -u
OUT=${OUT:-RUN_LOGS/motor_audit/campaigns_2026-09/TRIAD_VERIFY}
mkdir -p "$OUT"
BASE="-run -gpu -devicecull -nohires -noviz -density 500 -matx 10.0 -maty 2.0 -filx 2.5 -eta 0.10 -dt 1.25e-6 -steps 200000 -target 9.0 -filsegs 1"
run(){ rm -rf "$OUT/$1"; ./scripts/run_gpu_monitored.sh ./scripts/run_site_normal_glide_gpu.sh $BASE -seed $3 $2 -out "$OUT/$1" > "$OUT/$1.log" 2>&1; }
for S in 20260901 20260902 20260903 20260904; do
  run "SP_$S"  ""        $S &
  run "TRI_$S" "-triad"  $S & wait
done
echo "=== 3-CONTACT SURFACE PATCH vs SINGLE SPRING (rigid filament, eps=0) ==="
python3 - "$OUT" <<'PY'
import csv,os,sys,statistics as st
O=sys.argv[1]; S=["20260901","20260902","20260903","20260904"]
def v(n):
    f=os.path.join(O,n,"trajectory_summary.csv")
    if not os.path.exists(f): return None
    r=list(csv.DictReader(open(f),delimiter='\t'))
    if not r: return None
    x=r[-1]; d=abs(float(x["fwd_um"]))
    return (float(x["vfit_um_s"]), float(x["avgBound"]), float(x["rollTurns"])/d if d>0 else float('nan'))
print(f"{'seed':<10}{'single':>9}{'triad':>9}{'ratio':>8}{'avgB SP':>9}{'avgB TRI':>10}{'TRI turns/um':>14}")
a=[];b=[];t=[]
for s in S:
    p,q=v("SP_"+s),v("TRI_"+s)
    if not p or not q: print(f"{s:<10} incomplete"); continue
    a.append(p[0]); b.append(q[0]); t.append(q[2])
    print(f"{s:<10}{p[0]:9.4f}{q[0]:9.4f}{q[0]/p[0]:8.3f}{p[1]:9.2f}{q[1]:10.2f}{q[2]:14.3f}")
if len(a)>1:
    print(f"\nsingle  {st.mean(a):+.4f} +/- {st.stdev(a)/len(a)**.5:.4f}  signs {[1 if x>0 else -1 for x in a]}")
    print(f"triad   {st.mean(b):+.4f} +/- {st.stdev(b)/len(b)**.5:.4f}  signs {[1 if x>0 else -1 for x in b]}")
    print(f"TRI/SP = {st.mean(b)/st.mean(a):.3f}")
    print(f"\ntriad twirl at eps=0: {st.mean(t):+.3f} +/- {st.stdev(t)/len(t)**.5:.3f} turns/um  signs {[1 if x>0 else -1 for x in t]}")
    print("  (this is the DERIVED-handedness readout: twirl with NO imposed epsStroke offset)")
PY
