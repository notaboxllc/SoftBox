#!/usr/bin/env bash
# BOUND-HEAD POSE. The model holds the head RADIAL ("sticking straight out", converter as far from the filament
# as possible and centred on the site). Biologically the motor domain sits at an angle with the neck/converter
# displaced ALONG the filament; the powerstroke swings the lever toward the BARBED end (lever ~90 deg to the
# axis pre-stroke, ~45 deg in rigor), so the converter is barbed-proximal in the strongly-bound states.
# Sign is swept BOTH ways rather than assumed. Magnitudes are kept modest: 45 deg is the LEVER's angle, while
# MOTOR-DOMAIN orientation is conserved (an 8 deg rotation between myosin-IB and IC is a notable difference).
# Run on the TRIAD bond, which -- unlike a 1-contact ball joint -- can actually HOLD a tilted pose.
set -u
OUT=${OUT:-RUN_LOGS/motor_audit/campaigns_2026-09/HEADTILT_SWEEP}
mkdir -p "$OUT"
BASE="-run -gpu -devicecull -nohires -noviz -density 500 -matx 10.0 -maty 2.0 -filx 2.5 -eta 0.10 -dt 1.25e-6 -steps 200000 -target 9.0 -filsegs 1 -triad"
run(){ rm -rf "$OUT/$1"; ./scripts/run_gpu_monitored.sh ./scripts/run_site_normal_glide_gpu.sh $BASE -seed $3 $2 -out "$OUT/$1" > "$OUT/$1.log" 2>&1; }
for S in 20260901 20260902 20260903; do
  run "t000_$S" ""                 $S & run "tp10_$S" "-headtilt 10"  $S & wait
  run "tp20_$S" "-headtilt 20"     $S & run "tp30_$S" "-headtilt 30"  $S & wait
  run "tm10_$S" "-headtilt -10"    $S & run "tm20_$S" "-headtilt -20" $S & wait
  run "tm30_$S" "-headtilt -30"    $S & wait
done
echo "=== BOUND-HEAD TILT SWEEP (triad bond, rigid filament) ==="
python3 - "$OUT" <<'PY'
import csv,os,sys,statistics as st
O=sys.argv[1]; S=["20260901","20260902","20260903"]
def v(n):
    f=os.path.join(O,n,"trajectory_summary.csv")
    if not os.path.exists(f): return None
    r=list(csv.DictReader(open(f),delimiter='\t'))
    if not r: return None
    x=r[-1]; d=abs(float(x["fwd_um"]))
    return (float(x["vfit_um_s"]), float(x["avgBound"]), float(x["rollTurns"])/d if d>0.02 else float('nan'))
arms=[("tm30","-30 (converter POINTED-side)"),("tm20","-20"),("tm10","-10"),
      ("t000"," 0  (radial - current model)"),("tp10","+10"),("tp20","+20"),("tp30","+30 (converter BARBED-side)")]
print(f"{'tilt (deg)':<32}{'mean v':>9}{'ratio':>8}{'avgB':>7}{'turns/um':>10}{'signs':>7}")
base=None
for tag,lbl in arms:
    xs=[v(f"{tag}_{s}") for s in S]; ok=[x for x in xs if x]
    if not ok: print(f"{lbl:<32}  incomplete"); continue
    m=st.mean([x[0] for x in ok])
    if tag=="t000": base=m
    print(f"{lbl:<32}{m:9.3f}{(m/base if base else float('nan')):8.3f}"
          f"{st.mean([x[1] for x in ok]):7.2f}{st.mean([x[2] for x in ok]):10.2f}"
          f"{''.join('+' if x[0]>0 else '-' for x in ok):>7}")
PY
