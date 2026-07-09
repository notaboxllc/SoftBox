#!/bin/bash
# STEP-2 staged single-seed A/B: does -segimplicit flatten the residual dt-climb that -ratefix left (~1.8×)?
# Clean same-seed A/B: {ratefix} vs {ratefix+segimplicit} at dt 1e-5 and 2.5e-6 (the exact dt-pair the ~1.8×
# residual was measured at, PAIRS_RATE_AUDIT). Full stack -lymntaylor -adppibind -xbimplicit2, coltol10/d1000,
# -full -grid, seed 0. Matched ~1.2 s window (COUPLED cadence: 1e-5→120k, 2.5e-6→480k).
cd /home/jba/Code/SoftBox
LOG=RUN_LOGS/2026-07-05_segimpl_probe.txt
BASE="-gpu -full -grid -lymntaylor -adppibind -xbimplicit2 -coltol 10 -density 1000 -seed 0"
run() { # $1=label $2=dt $3=steps  $4..=extra flags
  local label=$1 dt=$2 steps=$3; shift 3
  echo "$(date +%H:%M:%S) START $label" >> .last_run_status
  echo "### $label  dt=$dt steps=$steps  extra=$*" >> $LOG
  ./run_gliding.sh $BASE -ratefix "$@" -dt $dt $steps 2>&1 | grep -E "GRID_ROW|Exception|CUDA|NaN" >> $LOG
  echo "$(date +%H:%M:%S) DONE  $label" >> .last_run_status
}
: > $LOG
echo "# STEP-2 A/B probe $(date)" >> $LOG
run "ratefix_1e-5"            1e-5    120000
run "ratefix+segimpl_1e-5"   1e-5    120000 -segimplicit
run "ratefix_2.5e-6"         2.5e-6  480000
run "ratefix+segimpl_2.5e-6" 2.5e-6  480000 -segimplicit
echo "# probe done" >> $LOG
echo "$(date +%H:%M:%S) PROBE COMPLETE" >> .last_run_status
