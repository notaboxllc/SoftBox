#!/bin/bash
# COLTOL_REGIME_SWEEP PART 1 — isolate the capture-radius (coltol) lever with the mat-sized chamber ON.
# Q: does shrinking coltol cross the supply→release regime threshold and SATURATE velocity at ≈ d/τ_on?
# The regime indicator is OCCUPANCY = avgBound/meanReach: does it FALL from ~0.85 toward the duty (~0.05)?
#   - FALLS + velFitX flattens (density-independent, ≈ d/τ_on ~6-8 µm/s) ⇒ CROSSOVER to release-limited (the win).
#   - Filament de-engages (avgBound→0, stalls) ⇒ OVER-RESTRICTION (threshold collapse), distinguish from crossover.
#   - Stays ~0.85, velFitX keeps climbing with density ⇒ coltol just SCALES (no crossover; rate top-up deferred).
# coltol {8,6,4,3,2} nm × density {2000,4000,8000}, single-seed 30k, -full box, chamber -matbox 50 ON.
# coltol=8 = baseline anchor. Report velFitX AND avgBound SEPARATELY (the recurring trap) + meanReach + occupancy.
cd /home/jba/Code/SoftBox
LOG=RUN_LOGS/2026-07-10_coltol_regime_sweep.txt
STEPS=30000; SEED=0
mkdir -p RUN_LOGS
: > "$LOG"
echo "COLTOL_REGIME PART1 sweep (coltol x density, -matbox 50 ON) $(date)" | tee -a "$LOG"
echo "invocation: scripts/run_gliding.sh -gpu -full -grid -matbox 50 -coltol <C> -density <D> -seed 0 $STEPS" | tee -a "$LOG"

for D in 2000 4000 8000; do
  for C in 8 6 4 3 2; do
    echo "[$(date +%H:%M:%S)] REGIME d${D} coltol${C}" > .last_run_status
    echo "== [$(date +%H:%M:%S)] d=${D} coltol=${C} seed=${SEED} matbox50 ==" >> "$LOG"
    scripts/run_gliding.sh -gpu -full -grid -matbox 50 -coltol $C -density $D -seed $SEED $STEPS 2>&1 \
      | grep -E "GRID_ROW|STATS_STEADY_ROW|COV_ROW|NaN|Exception|CUDA|blow|Infinity" >> "$LOG"
  done
done

echo "[$(date +%H:%M:%S)] REGIME SWEEP DONE" > .last_run_status
echo "REGIME_SWEEP_DONE" | tee -a "$LOG"
