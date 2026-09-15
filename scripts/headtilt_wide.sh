#!/usr/bin/env bash
# WIDE-ANGLE extension of the bound-head tilt sweep. The +/-30 cap in the first pass rested on a MISREADING of
# the "8 deg between myosin-IB and IC" figure -- that is a difference BETWEEN ISOFORMS, not a bound on how far
# the absolute motor-domain pose sits from radial. Nothing in the structures puts the converter straight out
# from the filament; that was our modelling convenience. At +30 the converter is only 1.75 nm barbed-ward vs
# 3.03 nm radial height, so the premise (converter clearly barbed-SIDE) is not yet realised. Extend to +/-45/60.
set -u
OUT=${OUT:-RUN_LOGS/motor_audit/campaigns_2026-09/HEADTILT_SWEEP}
mkdir -p "$OUT"
BASE="-run -gpu -devicecull -nohires -noviz -density 500 -matx 10.0 -maty 2.0 -filx 2.5 -eta 0.10 -dt 1.25e-6 -steps 200000 -target 9.0 -filsegs 1 -triad"
run(){ rm -rf "$OUT/$1"; ./scripts/run_gpu_monitored.sh ./scripts/run_site_normal_glide_gpu.sh $BASE -seed $3 $2 -out "$OUT/$1" > "$OUT/$1.log" 2>&1; }
for S in 20260901 20260902 20260903; do
  run "tp45_$S" "-headtilt 45"  $S & run "tp60_$S" "-headtilt 60"  $S & wait
  run "tm45_$S" "-headtilt -45" $S & run "tm60_$S" "-headtilt -60" $S & wait
done
