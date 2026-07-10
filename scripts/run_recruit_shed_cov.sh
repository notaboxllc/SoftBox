#!/bin/bash
# RECRUIT/SHED coverage re-run (2026-07-10): re-capture d4000 + d8000 slow-shed points WITH the COV_ROW/fullMat
# flag (the first sweep grep dropped it). Classifies whether the high-density velFitX rise is real glide or a
# filament-off-bed coverage artifact (baseline doc flagged d8000 marginal at 9.4 µm/s; slow-shed hit 22-27).
cd /home/jba/Code/SoftBox
LOG=RUN_LOGS/2026-07-10_recruit_shed_cov.txt
STEPS=30000; COLTOL=8
mkdir -p RUN_LOGS
: > "$LOG"
echo "RECRUIT/SHED COVERAGE RE-RUN $(date)" | tee -a "$LOG"
for D in 4000 8000; do
  for S in 1.0 0.1 0.03; do
    echo "[$(date +%H:%M:%S)] COV s${S} d${D}" > .last_run_status
    echo "-- [$(date +%H:%M:%S)] brakehold=${S} d=${D} seed=0 --" >> "$LOG"
    scripts/run_gliding.sh -brakehold $S -gpu -full -grid -coltol $COLTOL -density $D -seed 0 $STEPS 2>&1 \
      | grep -E "GRID_ROW|STATS_STEADY_ROW|COV_ROW|COVERAGE VIOLATED|NaN|nan|Exception|CUDA" >> "$LOG"
  done
done
echo "[$(date +%H:%M:%S)] COV RE-RUN DONE" > .last_run_status
echo "COV_RERUN_DONE" | tee -a "$LOG"
