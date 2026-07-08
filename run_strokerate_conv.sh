#!/bin/bash
# STEP 3 dt series: -strokerate (stroke as per-time rate) vs the coupled-no-rate references.
# coltol10/d1000/-full -grid/-lymntaylor -adppibind -xbimplicit2, seed 0. ≥1 s window per dt.
set -u
LOG=RUN_LOGS/2026-07-05_strokerate_conv.txt
mkdir -p RUN_LOGS
: > "$LOG"
run () {  # $1=label $2=dt $3=steps
  echo "[$(date +%H:%M:%S)] START $1 dt=$2 steps=$3" | tee -a "$LOG" .last_run_status
  ./run_gliding.sh -gpu -full -grid -lymntaylor -adppibind -coltol 10 -density 1000 -xbimplicit2 -strokerate \
      -dt "$2" -seed 0 "$3" 2>&1 \
    | grep -E "GRID_ROW|STATS_STEADY_ROW|strokerate:" | sed "s/^/$1 /" | tee -a "$LOG"
  echo "[$(date +%H:%M:%S)] DONE  $1" | tee -a "$LOG" .last_run_status
}
run strokerate_1e-5    1e-5    120000
run strokerate_2.5e-6  2.5e-6  480000
run strokerate_1.25e-6 1.25e-6 960000
echo "[$(date +%H:%M:%S)] ALL DONE" | tee -a "$LOG" .last_run_status
