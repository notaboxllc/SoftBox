#!/bin/bash
# CEILING test: 4-arm A/B — how much of the per-bound dt-bias can the over-fluctuation mechanism recover
# if we correct EVERY constrained mode? baseline / head-trans-only / +seg-trans (real-spring ceiling) /
# +fracMove (re-baseline). per-bound = velFitX / avgBsteady. GPU device-resident.
cd /home/jba/Code/SoftBox
CFG="-gpu -full -grid -lymntaylor -adppibind -coltol 10 -density 1000 -xbimplicit2 -ratefix -dt 1e-5 -seed 0"
STEPS=60000
LOG=RUN_LOGS/2026-07-06_allnoise_ab.txt
: > $LOG
run_arm () {
  local name="$1"; shift
  echo "==================== ARM: $name  (extra: $*) ====================" | tee -a $LOG
  echo "  [$(date +%H:%M:%S)] arm $name start" > .last_run_status
  ./run_gliding.sh $CFG "$@" $STEPS 2>&1 \
    | grep -E "bondnoise:|allnoise:|GRID_ROW|velFitX \(|avgBound  |STATS_STEADY_ROW|steps/s|Exception|Error|resize" | tee -a $LOG
  echo "" | tee -a $LOG
}
run_arm baseline
run_arm bondnoise -bondnoise
run_arm allnoise  -allnoise
run_arm fracnoise -fracnoise
echo "ALL ARMS DONE" | tee -a $LOG
echo "  [$(date +%H:%M:%S)] all arms done" > .last_run_status
