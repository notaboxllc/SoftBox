#!/bin/bash
# SPRINGS_PROMOTION GATE 2 (GPU side): springs on GPU, 3 seeds + stability variants.
# Compare basin to CPU-springs (other job). Springs = -pairsprings -alignsprings -structsprings.
cd /home/jba/Code/SoftBox
LOG=RUN_LOGS/2026-07-08_promo_gate2_gpu.txt
SP="-pairsprings -alignsprings -structsprings"
BASE="-gpu -full -grid -lymntaylor -adppibind -xbimplicit2 -dt 1e-5"
: > "$LOG"
echo "GATE2 GPU-springs  $(date)" | tee -a "$LOG"
# 3 seeds, default production config (density 1000, coltol 10), 20k (matched to CPU)
for S in 0 1 2; do
  echo "[$(date +%H:%M:%S)] GATE2 GPU springs seed=$S" >> .last_run_status
  echo "== GPU springs seed=$S density=1000 coltol=10 ==" >> "$LOG"
  ./run_gliding.sh $SP $BASE -coltol 10 -density 1000 -seed $S 20000 2>&1 | grep -E "GRID_ROW|STATS_STEADY_ROW|NaN|nan|Exception|blow" >> "$LOG"
done
# stability variants (density + coltol) — NaN/blowup check
for V in "-density 500 -coltol 10" "-density 2000 -coltol 10" "-density 1000 -coltol 6"; do
  echo "== GPU springs seed=0 $V (stability) ==" >> "$LOG"
  ./run_gliding.sh $SP $BASE $V -seed 0 20000 2>&1 | grep -E "GRID_ROW|NaN|nan|Exception|blow|Infinity" >> "$LOG"
done
echo "[$(date +%H:%M:%S)] GATE2 GPU DONE" | tee -a .last_run_status >> "$LOG"
