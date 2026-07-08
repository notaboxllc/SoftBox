#!/bin/bash
# STEP 3 row 3: -ratefix (motor rotational + FILAMENT per-step fractions → per-time). seed 0 ladder (staging probe).
# vs row1 coupled (2.02/6.28/10.06) and row2 motor-only allrate (2.46/5.50/5.88).
set -u
LOG=RUN_LOGS/2026-07-05_ratefix_conv.txt
mkdir -p RUN_LOGS
: > "$LOG"
run () {
  echo "[$(date +%H:%M:%S)] START $1 dt=$2 steps=$3" | tee -a "$LOG" .last_run_status
  ./run_gliding.sh -gpu -full -grid -lymntaylor -adppibind -coltol 10 -density 1000 -xbimplicit2 -ratefix \
      -dt "$2" -seed "${4:-0}" "$3" 2>&1 \
    | grep -E "GRID_ROW|STATS_STEADY_ROW|filrate:" | sed "s/^/$1 /" | tee -a "$LOG"
  echo "[$(date +%H:%M:%S)] DONE  $1" | tee -a "$LOG" .last_run_status
}
run ratefix_1e-5    1e-5    120000
run ratefix_1.25e-6 1.25e-6 960000
run ratefix_2.5e-6  2.5e-6  480000
echo "[$(date +%H:%M:%S)] ALL DONE" | tee -a "$LOG" .last_run_status
