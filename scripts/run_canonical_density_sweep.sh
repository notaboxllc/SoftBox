#!/bin/bash
# CANONICAL velocity–density sweep (2026-07-08). Replaces the pre-collapse ghost run_densesweep.sh
# (which drove DenseGlidingHarness -scale, NOT the canonical gliding model).
#
# CANONICAL invocation = BARE run_gliding.sh (springs + Lymn-Taylor + ADP·Pi-bind + xbimplicit2 are
# all DEFAULT-ON post-canonical-collapse; verified byte-identical to the explicit GATE2 flag stack).
# density + coltol passed EXPLICITLY (swept conditions, placeholder defaults otherwise).
#
#   scripts/run_gliding.sh -gpu -full -grid -coltol 10 -density <D> -seed <s> 60000
#
# Sweep: density {100,250,500,1000,2000} µm^-2, coltol=10 nm fixed, 3 seeds, M=60000 (0.6 s @ dt=1e-5,
# velFitX/avgBsteady over the 2nd-half steady window — matches the capstone ≈2.83 baseline).
cd /home/jba/Code/SoftBox
LOG=RUN_LOGS/2026-07-08_canonical_density_sweep.txt
STEPS=60000
COLTOL=10
: > "$LOG"
echo "CANONICAL DENSITY SWEEP  $(date)" | tee -a "$LOG"
echo "invocation: scripts/run_gliding.sh -gpu -full -grid -coltol $COLTOL -density <D> -seed <s> $STEPS" | tee -a "$LOG"

# GPU sweep, cheapest (sparsest) density first.
for D in 100 250 500 1000 2000; do
  for S in 0 1 2; do
    echo "[$(date +%H:%M:%S)] DENSITY_SWEEP GPU d${D} seed${S}" >> .last_run_status
    echo "== GPU d=${D} seed=${S} coltol=${COLTOL} steps=${STEPS} ==" >> "$LOG"
    scripts/run_gliding.sh -gpu -full -grid -coltol $COLTOL -density $D -seed $S $STEPS 2>&1 \
      | grep -E "GRID_ROW|STATS_STEADY_ROW|NaN|nan|Exception|blow|Infinity|CUDA|out of mem" >> "$LOG"
  done
done

# CPU spot check: 1 seed at d1000. RETAINED under docs/CPU_GPU_VALIDATION_POLICY.md §3/§5 as the
# one periodic mid-range check for a chaotic-ensemble campaign reporting an absolute number.
# (1 of 15 GPU cells — a spot check, not a duplicate sweep.)
echo "[$(date +%H:%M:%S)] DENSITY_SWEEP CPU-arbiter d1000 seed0" >> .last_run_status
echo "== CPU spot check (policy §3/§5) d=1000 seed=0 coltol=${COLTOL} steps=${STEPS} ==" >> "$LOG"
scripts/run_gliding.sh -full -grid -coltol $COLTOL -density 1000 -seed 0 $STEPS 2>&1 \
  | grep -E "GRID_ROW|STATS_STEADY_ROW|NaN|nan|Exception|blow|Infinity" >> "$LOG"

echo "[$(date +%H:%M:%S)] DENSITY_SWEEP DONE" | tee -a .last_run_status >> "$LOG"
echo "SWEEP_DONE" | tee -a "$LOG"
