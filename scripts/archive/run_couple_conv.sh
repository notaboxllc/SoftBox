#!/bin/bash
# STEP 2 dt-convergence batch: coupled (-xbimplicit2) vs explicit vs head-only, matched to NUCDETACH
# (coltol10, d1000, -full -grid, -lymntaylor -adppibind, seed 0). ≥1 s on-bed window per dt.
set -u
LOG=RUN_LOGS/2026-07-04_coupled_conv.txt
mkdir -p RUN_LOGS
: > "$LOG"
run () {  # $1=label $2=dt $3=steps $4..=extra flags
  local label="$1" dt="$2" steps="$3"; shift 3
  echo "[$(date +%H:%M:%S)] START $label dt=$dt steps=$steps $*" | tee -a "$LOG" .last_run_status
  ./run_gliding.sh -gpu -full -grid -lymntaylor -adppibind -coltol 10 -density 1000 \
      -dt "$dt" -seed 0 "$@" "$steps" 2>&1 \
    | grep -E "GRID_ROW|STATS_STEADY_ROW|velFitX|avgBound " | sed "s/^/$label /" | tee -a "$LOG"
  echo "[$(date +%H:%M:%S)] DONE  $label" | tee -a "$LOG" .last_run_status
}
# window ≥1 s: dt=1e-5→120k, 2.5e-6→480k, 1.25e-6→960k
run coupled_1e-5    1e-5    120000 -xbimplicit2
run explicit_1e-5   1e-5    120000
run coupled_2.5e-6  2.5e-6  480000 -xbimplicit2
run explicit_2.5e-6 2.5e-6  480000
run coupled_1.25e-6 1.25e-6 960000 -xbimplicit2
echo "[$(date +%H:%M:%S)] ALL DONE" | tee -a "$LOG" .last_run_status
