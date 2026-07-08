#!/bin/bash
# SPRINGS_PROMOTION GATE 2 (CPU side): springs on the deterministic CPU, 3 seeds, full config.
# The CPU is the basin arbiter; GPU-springs must land in the SAME basin within aggregate-SEM.
cd /home/jba/Code/SoftBox
LOG=RUN_LOGS/2026-07-08_promo_gate2_cpu.txt
SP="-pairsprings -alignsprings -structsprings"
BASE="-full -grid -lymntaylor -adppibind -xbimplicit2 -coltol 10 -density 1000 -dt 1e-5"
: > "$LOG"
echo "GATE2 CPU-springs (basin arbiter)  $(date)" | tee -a "$LOG"
for S in 0 1 2; do
  echo "[$(date +%H:%M:%S)] GATE2 CPU springs seed=$S" >> .last_run_status
  echo "== CPU springs seed=$S ==" >> "$LOG"
  ./run_gliding.sh $SP $BASE -seed $S 20000 2>&1 | grep -E "GRID_ROW|STATS_STEADY_ROW|NaN|nan|Exception|blow|Infinity" >> "$LOG"
done
echo "[$(date +%H:%M:%S)] GATE2 CPU DONE" | tee -a .last_run_status >> "$LOG"
