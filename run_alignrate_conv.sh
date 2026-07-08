#!/bin/bash
# STEP 3b: -strokerate -alignrate (ALL rotational per-step fractions → per-time rates: directedSwing + F9/F10/axlock).
# Decisive A/B vs strokerate-only: if per-bound glide now FLATTENS across dt ⇒ residual was ALL rate (cheap fix, no
# sub-step). If it still climbs ⇒ genuine rotational stiffness (needs implicit/sub-step).
set -u
LOG=RUN_LOGS/2026-07-05_alignrate_conv.txt
mkdir -p RUN_LOGS
: > "$LOG"
run () {
  echo "[$(date +%H:%M:%S)] START $1 dt=$2 steps=$3" | tee -a "$LOG" .last_run_status
  ./run_gliding.sh -gpu -full -grid -lymntaylor -adppibind -coltol 10 -density 1000 -xbimplicit2 -strokerate -alignrate \
      -dt "$2" -seed 0 "$3" 2>&1 \
    | grep -E "GRID_ROW|STATS_STEADY_ROW|strokerate:|alignrate" | sed "s/^/$1 /" | tee -a "$LOG"
  echo "[$(date +%H:%M:%S)] DONE  $1" | tee -a "$LOG" .last_run_status
}
run allrate_1e-5    1e-5    120000
run allrate_2.5e-6  2.5e-6  480000
run allrate_1.25e-6 1.25e-6 960000
echo "[$(date +%H:%M:%S)] ALL DONE" | tee -a "$LOG" .last_run_status
