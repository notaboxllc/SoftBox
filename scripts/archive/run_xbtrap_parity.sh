#!/bin/bash
set -u
LOG=RUN_LOGS/2026-07-06_xbtrap_parity.txt; : > "$LOG"
CFG="-v1box -grid -lymntaylor -adppibind -ratefix -coltol 10 -density 1000 -xbtrap -seed 0 20000"
echo "xbtrap parity: GPU $(date +%H:%M:%S)" > .last_run_status
echo "===== GPU =====" >> "$LOG"; ./run_gliding.sh -gpu $CFG 2>&1 | grep -vE "WARNING|tornado|Picked|^Using" | grep -E "GRID_ROW|velFitX \(" >> "$LOG"
echo "xbtrap parity: CPU $(date +%H:%M:%S)" > .last_run_status
# CPU runner = default (omit -gpu); -cpu is NOT a GlidingHarness flag
echo "===== CPU =====" >> "$LOG"; ./run_gliding.sh $CFG 2>&1 | grep -vE "WARNING|tornado|Picked|^Using" | grep -E "GRID_ROW|velFitX \(" >> "$LOG"
echo "xbtrap parity DONE $(date)" > .last_run_status
echo "==== PARITY ===="; cat "$LOG"
