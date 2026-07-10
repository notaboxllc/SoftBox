#!/bin/bash
# COLTOL_REGIME_SWEEP PART 0 — isolation check for the mat-sized loose z-chamber (-matbox 50).
# The chamber must ISOLATE (suppress the out-of-plane disengagement tail + keep the filament over the
# lawn) WITHOUT intervening on the engaged state. Gate: confined (-matbox 50) vs unconfined engaged-seed
# velFitX/avgBound must MATCH within chaotic SEM. If confinement CHANGES the engaged state ⇒ it is
# intervening ⇒ BAIL (loosen/report) before the PART-1 sweep.
# Anchor: coltol=8 nm, d2000, -full box, 3 seeds, 30k (0.3 s). z-half-width 50 nm = loose (order motor z-reach).
cd /home/jba/Code/SoftBox
LOG=RUN_LOGS/2026-07-10_coltol_regime_part0.txt
STEPS=30000; COLTOL=8; D=2000
mkdir -p RUN_LOGS
: > "$LOG"
echo "COLTOL_REGIME PART0 isolation (chamber vs unconfined engaged) $(date)" | tee -a "$LOG"
echo "coltol=$COLTOL d=$D -full 3seeds ${STEPS}steps; matbox z-half=50nm" | tee -a "$LOG"

for S in 0 1 2; do
  echo "[$(date +%H:%M:%S)] PART0 UNCONFINED d${D} seed${S}" > .last_run_status
  echo "== [$(date +%H:%M:%S)] UNCONFINED coltol=${COLTOL} d=${D} seed=${S} ==" >> "$LOG"
  scripts/run_gliding.sh -gpu -full -grid -coltol $COLTOL -density $D -seed $S $STEPS 2>&1 \
    | grep -E "GRID_ROW|STATS_STEADY_ROW|COV_ROW|NaN|Exception|CUDA" >> "$LOG"
done
for S in 0 1 2; do
  echo "[$(date +%H:%M:%S)] PART0 MATBOX50 d${D} seed${S}" > .last_run_status
  echo "== [$(date +%H:%M:%S)] MATBOX50 coltol=${COLTOL} d=${D} seed=${S} ==" >> "$LOG"
  scripts/run_gliding.sh -gpu -full -grid -coltol $COLTOL -density $D -seed $S -matbox 50 $STEPS 2>&1 \
    | grep -E "GRID_ROW|STATS_STEADY_ROW|COV_ROW|NaN|Exception|CUDA" >> "$LOG"
done

echo "[$(date +%H:%M:%S)] PART0 DONE" > .last_run_status
echo "PART0_DONE" | tee -a "$LOG"
