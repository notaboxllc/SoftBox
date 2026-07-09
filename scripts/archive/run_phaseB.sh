#!/bin/bash
# Phase B: v2 rebind-refractory ON/OFF bracket, full 14x2 box, n=6, both runners.
# CPU -diag gives net(velocity)+avgBound+assist; GPU -grid gives netXY+avgBound.
# Progress -> .last_run_status; raw -> RUN_LOGS/2026-06-15_4biv_refractory.txt
cd /home/jba/Code/SoftBox
RAW=RUN_LOGS/2026-06-15_4biv_refractory.txt
STEPS=10000
SEEDS="1 2 3 4 5 6"
: > "$RAW"
echo "Phase B v2 ON/OFF bracket — full 14x2, n=6, both runners — started $(date +%H:%M)" | tee -a "$RAW"

status() { echo "[$(date +%H:%M)] PhaseB $1" > .last_run_status; }

# ---- CPU runner: -diag (net velocity + avgBound + assist) ----
for tog in ON OFF; do
  FLAG=""; [ "$tog" = OFF ] && FLAG="-norefractory"
  for s in $SEEDS; do
    status "CPU -diag $tog seed $s"
    echo "=== CPU diag $tog seed=$s ===" >> "$RAW"
    ./run_gliding.sh -full $FLAG -diag -seed $s $STEPS 2>&1 \
      | grep -E "velocity = |forceDotFil \(bound\)" >> "$RAW"
  done
done

# ---- GPU runner: -grid (netXY + avgBound) ----
for tog in ON OFF; do
  FLAG=""; [ "$tog" = OFF ] && FLAG="-norefractory"
  for s in $SEEDS; do
    status "GPU -grid $tog seed $s"
    echo "=== GPU grid $tog seed=$s ===" >> "$RAW"
    ./run_gliding.sh -gpu -full $FLAG -grid -seed $s $STEPS 2>&1 \
      | grep -E "GRID_ROW" >> "$RAW"
  done
done

status "DONE"
echo "Phase B done $(date +%H:%M)" | tee -a "$RAW"
