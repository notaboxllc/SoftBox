#!/bin/bash
# Viscosity (aeta) sensitivity of gliding speed — d1000/coltol10/60k, GPU, canonical default.
# Fills a 3-aeta × 3-seed grid: aeta 0.1 (baseline, already in the density sweep), 0.05, 0.2.
# This script runs the MISSING cells: aeta=0.05 seeds{1,2}, aeta=0.2 seeds{0,1,2}.
cd /home/jba/Code/SoftBox
LOG=RUN_LOGS/2026-07-09_aeta_sensitivity.txt   # aeta=0.05 seed0 already appended here
STEPS=60000
echo "== aeta grid (append) $(date) — d1000 coltol10 steps=$STEPS ==" >> "$LOG"
for A in 0.05 0.2; do
  for S in 0 1 2; do
    # skip cells already measured: aeta 0.05 seed 0
    if [ "$A" = "0.05" ] && [ "$S" = "0" ]; then continue; fi
    echo "[$(date +%H:%M:%S)] AETA$A d1000 seed$S" >> .last_run_status
    echo "== GPU aeta=$A seed=$S d=1000 coltol=10 steps=$STEPS ==" >> "$LOG"
    scripts/run_gliding.sh -gpu -full -grid -coltol 10 -density 1000 -seed $S -aeta $A $STEPS 2>&1 \
      | grep -E "GRID_ROW|STATS_STEADY_ROW|NaN|nan|Exception|blow|Infinity|CUDA" >> "$LOG"
  done
done
echo "[$(date +%H:%M:%S)] AETA GRID DONE" | tee -a .last_run_status >> "$LOG"
echo "AETA_DONE" >> "$LOG"
