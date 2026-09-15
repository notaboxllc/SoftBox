#!/usr/bin/env bash
# DECISIVE: is the two-point failure entirely the COUPLE it applies to the filament?
# With foot=0 and equal weights the two springs are FORCE-IDENTICAL to one spring of the same stiffness at the
# site midpoint (F1+F2 = k(mid-a)); the head-side torque is identical too (R1=R2). The ONLY difference is the
# segment-side couple from reactions at two separated material points. -twopoint-nocouple applies the same
# total reaction at the midpoint, removing the couple and nothing else.
# PREDICTION: if the couple is the cause, TPnc should REPRODUCE SP closely -- not merely improve.
set -u
OUT=${OUT:-RUN_LOGS/motor_audit/campaigns_2026-09/TWOPOINT_COUPLE}
mkdir -p "$OUT"
BASE="-run -gpu -devicecull -nohires -noviz -density 500 -matx 10.0 -maty 2.0 -filx 2.5 -eta 0.10 -dt 1.25e-6 -steps 200000 -target 9.0 -filsegs 1"
TP="-twopoint -twopoint-foot 0 -twopoint-ancw 0.5"
run(){ rm -rf "$OUT/$1"; ./scripts/run_gpu_monitored.sh ./scripts/run_site_normal_glide_gpu.sh $BASE -seed $3 $2 -out "$OUT/$1" > "$OUT/$1.log" 2>&1; }
for S in 20260901 20260902 20260903 20260904; do
  run "SP_$S"   ""                          $S &
  run "TP_$S"   "$TP"                       $S &
  wait
  run "TPnc_$S" "$TP -twopoint-nocouple"    $S &
  wait
done
echo "=== TWO-POINT: is the failure the FILAMENT COUPLE? (rigid, rho=500) ==="
python3 - "$OUT" <<'PY'
import csv,os,sys,statistics as st
O=sys.argv[1]; S=["20260901","20260902","20260903","20260904"]
def v(n):
    f=os.path.join(O,n,"trajectory_summary.csv")
    if not os.path.exists(f): return None
    r=list(csv.DictReader(open(f),delimiter='\t'))
    return float(r[-1]["vfit_um_s"]) if r else None
print(f"{'seed':<10}{'single-pt':>11}{'two-point':>11}{'TP no-couple':>14}")
a=[];b=[];c=[]
for s in S:
    p,q,t=v("SP_"+s),v("TP_"+s),v("TPnc_"+s)
    if p is None: continue
    a.append(p)
    if q is not None: b.append(q)
    if t is not None: c.append(t)
    print(f"{s:<10}{p:11.4f}{(q if q is not None else float('nan')):11.4f}{(t if t is not None else float('nan')):14.4f}")
def rep(l,lab):
    if len(l)>1: print(f"{lab:<14} mean {st.mean(l):+.4f} +/- {st.stdev(l)/len(l)**.5:.4f}  signs {[1 if x>0 else -1 for x in l]}")
print()
rep(a,"single-point"); rep(b,"two-point"); rep(c,"TP no-couple")
if len(a)>1 and len(c)>1:
    print(f"\nTPnc/SP = {st.mean(c)/st.mean(a):.3f}   (1.0 => the couple was the ENTIRE cause)")
PY
