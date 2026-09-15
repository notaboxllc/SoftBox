#!/usr/bin/env bash
# RIGID single-segment epsilon ladder. -filsegs 1 => no joints, no chain forces, no roll spring:
# the ONLY torque on the filament is from motors, so neither the (uncharacterised-at-this-dt) PAIRS
# flexural coefficients nor the (uncalibrated) torsional coupling can contaminate the measurement.
# Each arm also reports LOCAL turns/um in windows, so twirl ACCELERATION is visible per arm.
set -u
OUT=${OUT:-RUN_LOGS/motor_audit/campaigns_2026-09/RIGID_EPS_LADDER}
TARGET=${TARGET:-1.0}; SEED=${SEED:-20260901}
mkdir -p "$OUT"
BASE="-run -gpu -devicecull -nohires -noviz -density 2000 -matx 14.0 -maty 2.0 -filx 3.5 -eta 0.10 -dt 1.25e-6 -steps 4000000 -target $TARGET -seed $SEED -randbase -randbase-seed $SEED -filsegs 1"
run(){ rm -rf "$OUT/$1"; ./scripts/run_gpu_monitored.sh ./scripts/run_site_normal_glide_gpu.sh $BASE $2 -out "$OUT/$1" > "$OUT/$1.log" 2>&1; }
for E in 1 2 4 8; do
  run "e${E}_pos" "-stroke-skew $E" &
  run "e${E}_neg" "-stroke-skew $E -mirror" &
  wait
done
echo "=== RIGID EPSILON LADDER (single segment, eta=0.1, rho=2000, target ${TARGET}um) ==="
python3 scripts/rigid_ladder_analyse.py "$OUT"
