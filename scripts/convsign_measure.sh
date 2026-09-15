#!/usr/bin/env bash
# WHICH PHYSICAL DIRECTION IS +convaz?  The -headtilt sign was wrong in-code until measured from frame
# geometry; -convaz has never been measured at all, so no "barbed-proximal" claim can rest on it yet.
#
# Measures the converter joint C relative to the bound point xF8, projected on the BARBED direction, from the
# viewer frames (which already export C = lever.end2 and xF8 = the id=N+m marker's motor.end1).
#
# PARSER POSITIVE CONTROL: tilt -20 is run in the same batch. Its sign IS established (converter axial offset
# = -7*sin(tilt), so negative tilt = BARBED) and its magnitude is predicted: +2.39 nm. If the parser does not
# return ~+2.4 nm there, the parser is wrong and the convaz numbers are not to be trusted.
# It also measures the REALIZED convaz displacement (nominal 3.03 nm at 60 deg); the earlier BARE-convaz frame
# decomposition found only ~1/3 of nominal, and the triad may now hold the head closer to it.
set -u
OUT=${OUT:-RUN_LOGS/motor_audit/campaigns_2026-09/CONVSIGN}
STEPS=${STEPS:-20000}
mkdir -p "$OUT"
BASE="-run -gpu -devicecull -nohires -noviz -density 2000 -matx 14.0 -maty 2.0 -filx 3.5 -eta 0.10 -dt 1.25e-6 -steps $STEPS -target 9.0 -filsegs 1 -triad -seed 20260901 -viz-stride 100"
run(){ rm -rf "$OUT/$1"; ./scripts/run_gpu_monitored.sh ./scripts/run_site_normal_glide_gpu_snapshot.sh \
       $BASE $2 -3js "$OUT/$1/frames" -out "$OUT/$1" > "$OUT/$1.log" 2>&1; echo "done $1"; }
run "ctl_tm20" "-headtilt -20"            &
run "caz_p60"  "-convaz 60 -convaz-nocomp" &
run "caz_m60"  "-convaz -60 -convaz-nocomp" &
wait
echo "=== CONVSIGN frames written ==="
