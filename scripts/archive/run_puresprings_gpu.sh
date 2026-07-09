#!/bin/bash
# PURE_SPRINGS STEP-3 GPU: pure-springs vs -ratefix -structrate vs raw, 3 seeds, dt=1e-5.
# Basin-flip check (per -allnoise lesson): CPU proved the 3 arms bit-identical; any GPU
# arm-difference the CPU doesn't show is a graph-structure basin flip, not a formulation effect.
cd /home/jba/Code/SoftBox
LOG=RUN_LOGS/2026-07-08_puresprings_gpu_step3.txt
STATUS=.last_run_status
COMMON="-gpu -full -grid -lymntaylor -adppibind -xbimplicit2 -coltol 10 -density 1000 -dt 1e-5"
STEPS=60000
: > "$LOG"
echo "PURE_SPRINGS STEP-3 GPU  dt=1e-5  $STEPS steps  $(date)" | tee -a "$LOG"
for SEED in 0 1 2; do
  for ARM in raw springs ratefix; do
    case $ARM in
      raw)     FLAGS="" ;;
      springs) FLAGS="-pairsprings -alignsprings -structsprings" ;;
      ratefix) FLAGS="-ratefix -structrate" ;;
    esac
    echo "[$(date +%H:%M:%S)] STEP3 seed=$SEED arm=$ARM" >> "$STATUS"
    echo "==== seed=$SEED arm=$ARM flags=[$FLAGS] ====" >> "$LOG"
    ./run_gliding.sh $FLAGS $COMMON -seed $SEED $STEPS 2>&1 \
      | grep -E "GRID_ROW|STATS_STEADY_ROW|-pairsprings|-alignsprings|-structsprings|-ratefix|-structrate" >> "$LOG"
  done
done
echo "[$(date +%H:%M:%S)] STEP3 GPU DONE" | tee -a "$STATUS" >> "$LOG"
