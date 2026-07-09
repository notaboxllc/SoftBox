#!/bin/bash
# Paired A/B: does the bound-head √((2−α)/2) F8-bond-noise correction move the per-bound glide at dt=1e-5?
# Three arms, same scene/seed, GPU device-resident. per-bound = velFitX / avgBsteady (the ROTIMPLICIT convention).
cd /home/jba/Code/SoftBox
CFG="-gpu -full -grid -lymntaylor -adppibind -coltol 10 -density 1000 -xbimplicit2 -ratefix -dt 1e-5 -seed 0"
STEPS=60000
LOG=RUN_LOGS/2026-07-06_bondnoise_ab.txt
: > $LOG
run_arm () {
  local name="$1"; shift
  echo "==================== ARM: $name  (extra: $*) ====================" | tee -a $LOG
  ./run_gliding.sh $CFG "$@" $STEPS 2>&1 \
    | grep -E "bondnoise:|GRID_ROW|velFitX \(|avgBound  |STATS_STEADY_ROW|steps/s|Exception|Error" | tee -a $LOG
  echo "" | tee -a $LOG
}
run_arm baseline
run_arm bondnoise -bondnoise
run_arm fac534 -bondnoisefac 0.534
echo "ALL ARMS DONE" | tee -a $LOG
