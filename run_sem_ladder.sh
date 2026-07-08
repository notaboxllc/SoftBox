#!/bin/bash
# STEP 3 SEM: row1 (coupled baseline) + row3 (ratefix) at dt 1e-5 & 2.5e-6, seeds 1 & 2 (seed 0 already in hand).
# For error bars on the decision-critical residual climb (1e-5→2.5e-6).
set -u
LOG=RUN_LOGS/2026-07-05_sem_ladder.txt
mkdir -p RUN_LOGS
: > "$LOG"
run () {  # $1=label $2=dt $3=steps $4=seed $5=extraflags
  echo "[$(date +%H:%M:%S)] START $1 dt=$2 seed=$4" | tee -a "$LOG" .last_run_status
  ./run_gliding.sh -gpu -full -grid -lymntaylor -adppibind -coltol 10 -density 1000 -xbimplicit2 $5 \
      -dt "$2" -seed "$4" "$3" 2>&1 \
    | grep -E "GRID_ROW|STATS_STEADY_ROW" | sed "s/^/$1 /" | tee -a "$LOG"
  echo "[$(date +%H:%M:%S)] DONE  $1" | tee -a "$LOG" .last_run_status
}
for s in 1 2; do
  run coupled_1e-5_s$s    1e-5    120000 $s ""
  run ratefix_1e-5_s$s    1e-5    120000 $s "-ratefix"
  run coupled_2.5e-6_s$s  2.5e-6  480000 $s ""
  run ratefix_2.5e-6_s$s  2.5e-6  480000 $s "-ratefix"
done
echo "[$(date +%H:%M:%S)] ALL DONE" | tee -a "$LOG" .last_run_status
