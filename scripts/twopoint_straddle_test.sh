#!/usr/bin/env bash
# THE TEST: two springs of k/2 whose sites STRADDLE the bound site (n-2 and n+2) instead of trailing it
# (n and n-2). N springs of k/N are provably ONE spring of k at the site centroid; straddling puts that
# centroid back ON the bound site (arc 0, azimuth 0; only a 0.38 nm inward radial residue).
# PREDICTION: straddled two-point glides like the single-point control.
set -u
OUT=${OUT:-RUN_LOGS/motor_audit/campaigns_2026-09/TWOPOINT_STRADDLE}
mkdir -p "$OUT"
BASE="-run -gpu -devicecull -nohires -noviz -density 500 -matx 10.0 -maty 2.0 -filx 2.5 -eta 0.10 -dt 1.25e-6 -steps 200000 -target 9.0 -filsegs 1"
run(){ rm -rf "$OUT/$1"; ./scripts/run_gpu_monitored.sh ./scripts/run_site_normal_glide_gpu.sh $BASE -seed $3 $2 -out "$OUT/$1" > "$OUT/$1.log" 2>&1; }
for S in 20260901 20260902 20260903 20260904; do
  run "SP_$S"  ""                                                    $S &
  run "STR_$S" "-twopoint -twopoint-ancw 0.5 -twopoint-straddle"     $S & wait
done
echo "=== STRADDLED TWO-SPRING vs SINGLE SPRING (rigid filament) ==="
python3 - "$OUT" <<'PY'
import csv,os,sys,statistics as st
O=sys.argv[1]; S=["20260901","20260902","20260903","20260904"]
def v(n):
    f=os.path.join(O,n,"trajectory_summary.csv")
    if not os.path.exists(f): return None
    r=list(csv.DictReader(open(f),delimiter='\t'))
    return (float(r[-1]["vfit_um_s"]), float(r[-1]["avgBound"])) if r else None
print(f"{'seed':<10}{'single':>10}{'straddled':>11}{'ratio':>8}{'avgB SP':>9}{'avgB STR':>10}")
a=[];b=[]
for s in S:
    p,q=v("SP_"+s),v("STR_"+s)
    if not p or not q: print(f"{s:<10} incomplete"); continue
    a.append(p[0]); b.append(q[0])
    print(f"{s:<10}{p[0]:10.4f}{q[0]:11.4f}{q[0]/p[0]:8.3f}{p[1]:9.2f}{q[1]:10.2f}")
if len(a)>1:
    ma,mb=st.mean(a),st.mean(b)
    print(f"\nsingle     {ma:+.4f} +/- {st.stdev(a)/len(a)**.5:.4f}  signs {[1 if x>0 else -1 for x in a]}")
    print(f"straddled  {mb:+.4f} +/- {st.stdev(b)/len(b)**.5:.4f}  signs {[1 if x>0 else -1 for x in b]}")
    print(f"STR/SP = {mb/ma:.3f}")
    print("\nreference, one-sided pair (n, n-2): TP/SP = 0.072, signs scrambled")
PY
