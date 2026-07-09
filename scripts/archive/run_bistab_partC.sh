#!/bin/bash
# BISTABILITY_ORIGIN PART C: GPU determinism run-to-run. Same config/seed 3x each for raw and ratefix.
# Bit-identical run-to-run ⇒ deterministic PTX (the flip is a deterministic compiler-scheduling artifact);
# varies ⇒ GPU nondeterminism (race/reduction-order). Plus reconfirm the raw(HIGH) vs ratefix(LOW) split.
cd /home/jba/Code/SoftBox
LOG=RUN_LOGS/2026-07-08_bistab_partC_gpudeterminism.txt
C="-gpu -full -grid -lymntaylor -adppibind -xbimplicit2 -coltol 10 -density 1000 -dt 1e-5 -seed 0 60000"
: > "$LOG"
echo "BISTABILITY_ORIGIN PART C — GPU determinism (raw 3x, ratefix 3x), -full 60k seed 0  $(date)" | tee -a "$LOG"
for ARM in raw ratefix; do
  case $ARM in raw) F="" ;; ratefix) F="-ratefix -structrate" ;; esac
  for R in 1 2 3; do
    echo "[$(date +%H:%M:%S)] PARTC arm=$ARM run=$R" >> .last_run_status
    echo "== arm=$ARM run=$R ==" >> "$LOG"
    ./run_gliding.sh $F $C 2>&1 | grep -E "GRID_ROW|STATS_STEADY_ROW" >> "$LOG"
  done
done
echo "[$(date +%H:%M:%S)] PARTC DONE" | tee -a .last_run_status >> "$LOG"
