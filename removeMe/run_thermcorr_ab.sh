#!/bin/bash
# COMPREHENSIVE thermal correction — cumulative 5-arm A/B. Each arm adds one physical layer:
#  baseline / -allnoise (single-bond ref) / -thermcorr 3 (load-aware seg α=N·k_F8) /
#  -thermcorr 4 (+chain on ALL segs) / -thermcorr 5 (+motor rot/rod). per-bound = velFitX/avgBsteady.
cd /home/jba/Code/SoftBox
CFG="-gpu -full -grid -lymntaylor -adppibind -coltol 10 -density 1000 -xbimplicit2 -ratefix -dt 1e-5 -seed 0"
STEPS=60000
LOG=RUN_LOGS/2026-07-06_thermcorr_ab.txt
: > $LOG
run_arm () {
  local name="$1"; shift
  echo "==================== ARM: $name  (extra: $*) ====================" | tee -a $LOG
  echo "  [$(date +%H:%M:%S)] arm $name start" > .last_run_status
  ./run_gliding.sh $CFG "$@" $STEPS 2>&1 \
    | grep -E "allnoise:|thermcorr|GRID_ROW|velFitX \(|avgBound  |STATS_STEADY_ROW|Exception|Error|resize|clamp" | tee -a $LOG
  echo "" | tee -a $LOG
}
run_arm baseline
run_arm allnoise   -allnoise
run_arm thermcorr3 -thermcorr 3
run_arm thermcorr4 -thermcorr 4
run_arm thermcorr5 -thermcorr 5
echo "ALL ARMS DONE" | tee -a $LOG
echo "  [$(date +%H:%M:%S)] thermcorr A/B done" > .last_run_status
