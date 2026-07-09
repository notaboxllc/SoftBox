#!/bin/bash
# Part B — explicit convergence reference ladder for ROTIMPLICIT_FEASIBILITY.
# Config: -full -grid -lymntaylor -adppibind -xbimplicit2 -ratefix, coltol10, density 1000, seed 0.
# dt ladder 1e-5 -> 2.5e-6 -> 1.25e-6 -> 6.25e-7 (>=1 s sim window each). GPU device-resident.
set -u
cd /home/jba/Code/SoftBox
LOG=RUN_LOGS/2026-07-05_rotimpl_conv.txt
STAT=.last_run_status
mkdir -p RUN_LOGS
COMMON="-gpu -full -grid -lymntaylor -adppibind -xbimplicit2 -ratefix -coltol 10 -density 1000 -seed 0"

run() {
  local dt=$1 steps=$2 tag=$3
  echo "[$(date +%H:%M:%S)] START $tag dt=$dt steps=$steps" | tee -a "$STAT" >> "$LOG"
  echo "===== $tag dt=$dt steps=$steps $(date) =====" >> "$LOG"
  ./run_gliding.sh $COMMON -dt $dt $steps >> "$LOG" 2>&1
  echo "[$(date +%H:%M:%S)] DONE  $tag" | tee -a "$STAT" >> "$LOG"
  grep -E "GRID_ROW|STATS_STEADY_ROW" "$LOG" | tail -2 | tee -a "$STAT"
}

echo "=== ROTIMPL Part-B ladder start $(date) ===" | tee -a "$STAT"
run 1e-5    120000  dt_1e-5
run 2.5e-6  480000  dt_2.5e-6
run 1.25e-6 960000  dt_1.25e-6
run 6.25e-7 1920000 dt_6.25e-7
echo "=== ROTIMPL Part-B ladder complete $(date) ===" | tee -a "$STAT"
