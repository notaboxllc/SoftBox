#!/usr/bin/env bash
# Extend the derived-handedness test to n=8 seeds, both arms (native and MIRRORED lattice).
set -u
OUT=${OUT:-RUN_LOGS/motor_audit/campaigns_2026-09/TRIAD_CHIRALITY}
mkdir -p "$OUT"
BASE="-run -gpu -devicecull -nohires -noviz -density 500 -matx 10.0 -maty 2.0 -filx 2.5 -eta 0.10 -dt 1.25e-6 -steps 200000 -target 9.0 -filsegs 1 -triad"
run(){ [ -s "$OUT/$1/trajectory_summary.csv" ] && return 0
       ./scripts/run_gpu_monitored.sh ./scripts/run_site_normal_glide_gpu.sh $BASE -seed $3 $2 -out "$OUT/$1" > "$OUT/$1.log" 2>&1; }
for S in 20260905 20260906 20260907 20260908; do
  run "NAT_$S" ""             $S &
  run "FLP_$S" "-flip-helix"  $S & wait
done
echo "=== DERIVED HANDEDNESS at n=8 ==="
python3 - "$OUT" <<'PY'
import csv,os,sys,statistics as st
O=sys.argv[1]
S=[f"2026090{i}" for i in range(1,9)]
def t(n):
    f=os.path.join(O,n,"trajectory_summary.csv")
    if not os.path.exists(f): return None
    r=list(csv.DictReader(open(f),delimiter='\t'))
    if not r: return None
    x=r[-1]; d=abs(float(x["fwd_um"]))
    return float(x["rollTurns"])/d if d>0.02 else None
print(f"{'seed':<10}{'native':>10}{'mirrored':>11}")
a=[];b=[]
for s in S:
    p,q=t("NAT_"+s),t("FLP_"+s)
    if p is None or q is None: print(f"{s:<10}   incomplete"); continue
    a.append(p); b.append(q); print(f"{s:<10}{p:10.3f}{q:11.3f}")
if len(a)>2:
    ma,mb=st.mean(a),st.mean(b); sa,sb=st.stdev(a)/len(a)**.5, st.stdev(b)/len(b)**.5
    print(f"\nnative   {ma:+.3f} +/- {sa:.3f}  ({abs(ma/sa):.1f} sigma, n={len(a)})  signs {[1 if x>0 else -1 for x in a]}")
    print(f"mirrored {mb:+.3f} +/- {sb:.3f}  ({abs(mb/sb):.1f} sigma, n={len(b)})  signs {[1 if x>0 else -1 for x in b]}")
    d=[(x-y)/2 for x,y in zip(a,b)]
    print(f"\nANTISYMMETRIC part (nat-flp)/2 = {st.mean(d):+.3f} +/- {st.stdev(d)/len(d)**.5:.3f}"
          f"  ({abs(st.mean(d)/(st.stdev(d)/len(d)**.5)):.1f} sigma)")
    print(f"symmetric residue  (nat+flp)/2 = {(ma+mb)/2:+.3f}   <- lab-frame artefact would sit HERE")
PY
