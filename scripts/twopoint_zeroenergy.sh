#!/usr/bin/env bash
# Does a multi-contact attachment WITH A ZERO-ENERGY RELAXED STATE glide like a single spring?
# Every earlier two-point test was FRUSTRATED by construction:
#   foot=0            -> both feet on one point, sites 5.64 nm apart: 2.82 nm permanent stretch per spring
#   foot=2.82 (hyVec) -> right spacing, but hyVec sits ~16.9 deg off the chord and the head cannot roll to it
#                        -> 0.82 nm residual per foot
#   -twopoint-alignfeet -> feet placed ALONG the chord at half its length: a1==pA, a2==pB exactly. ZERO ENERGY.
# Spring constants are scaled k/2 each, so the total translational stiffness matches the single-point bond.
set -u
OUT=${OUT:-RUN_LOGS/motor_audit/campaigns_2026-09/TWOPOINT_ZEROENERGY}
mkdir -p "$OUT"
BASE="-run -gpu -devicecull -nohires -noviz -density 500 -matx 10.0 -maty 2.0 -filx 2.5 -eta 0.10 -dt 1.25e-6 -steps 200000 -target 9.0 -filsegs 1"
run(){ rm -rf "$OUT/$1"; ./scripts/run_gpu_monitored.sh ./scripts/run_site_normal_glide_gpu.sh $BASE -seed $3 $2 -out "$OUT/$1" > "$OUT/$1.log" 2>&1; }
for S in 20260901 20260902 20260903 20260904; do
  run "SP_$S"    ""                                                              $S &
  run "ZERO_$S"  "-twopoint -twopoint-ancw 0.5 -twopoint-alignfeet"              $S & wait
  run "HYV_$S"   "-twopoint -twopoint-ancw 0.5 -twopoint-foot 2.82"              $S &
  run "FRUST_$S" "-twopoint -twopoint-ancw 0.5 -twopoint-foot 0"                 $S & wait
done
echo "=== ZERO-ENERGY MULTI-CONTACT vs FRUSTRATED vs SINGLE ==="
python3 - "$OUT" <<'PY'
import csv,os,sys,statistics as st
O=sys.argv[1]; S=["20260901","20260902","20260903","20260904"]
def v(n):
    f=os.path.join(O,n,"trajectory_summary.csv")
    if not os.path.exists(f): return None
    r=list(csv.DictReader(open(f),delimiter='\t'))
    return (float(r[-1]["vfit_um_s"]), float(r[-1]["avgBound"])) if r else None
rows=[("SP","single spring (control)","0.00 nm"),
      ("ZERO","2 springs, chord-aligned","0.00 nm  <- zero-energy"),
      ("HYV","2 springs, foot=2.82 on hyVec","0.82 nm"),
      ("FRUST","2 springs, foot=0","2.82 nm")]
print(f"{'configuration':<32}{'residual/spring':>18}{'mean v':>9}{'ratio':>8}{'avgB':>7}{'signs':>8}")
base=None
for tag,lbl,res in rows:
    xs=[v(f"{tag}_{s}") for s in S]; ok=[x for x in xs if x]
    if not ok: print(f"{lbl:<32}{res:>18}   incomplete"); continue
    m=st.mean([x[0] for x in ok]); ab=st.mean([x[1] for x in ok])
    if tag=="SP": base=m
    sg="".join("+" if x[0]>0 else "-" for x in ok)
    print(f"{lbl:<32}{res:>18}{m:9.3f}{(m/base if base else float('nan')):8.3f}{ab:7.2f}{sg:>8}")
print("\nIf ZERO ~ SP, then N springs of k/N behave as one spring of k -- and every earlier")
print("two-point 'failure' was frustration, not the mechanism.")
PY
