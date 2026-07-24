#!/bin/bash
# CANONICAL velocity–density sweep at coltol=8 nm (2026-07-09).
# The azimuthally-unaware canonical model's gliding envelope out to high density — the
# PRE-REFINEMENT baseline (jba adds specific helical/azimuthal actin binding sites next, which
# will re-baseline gliding). This is the CONTROL measured before that refinement, NOT biology
# validation. Companion to scripts/run_canonical_density_sweep.sh (coltol=10 first stab).
#
# CANONICAL invocation = BARE run_gliding.sh (springs + Lymn-Taylor + ADP·Pi-bind + xbimplicit2
# all DEFAULT-ON post-canonical-collapse). density + coltol passed EXPLICITLY.
#   scripts/run_gliding.sh -gpu -full -grid -coltol 8 -density <D> -seed <s> 60000
#
# Sweep: density {100,250,500,1000,2000,4000,6000,8000} µm^-2, coltol=8 nm fixed, 3 seeds,
# M=60000 (0.6 s @ dt=1e-5). coltol=8 is a TIGHTER capture radius than the coltol=10 curve ⇒
# a NEW curve (lower engagement), not an extension. Cheapest (sparsest) density first.
#
# CPU spot check: 1 seed at d4000 (a HIGH-density point — docs/CPU_GPU_VALIDATION_POLICY.md §3/§5,
# doubly important in the dense collective-load regime). Runs LAST.
cd /home/jba/Code/SoftBox
LOG=RUN_LOGS/2026-07-09_canonical_density_sweep_coltol8.txt
STEPS=60000
COLTOL=8
: > "$LOG"
echo "CANONICAL DENSITY SWEEP coltol=8  $(date)" | tee -a "$LOG"
echo "invocation: scripts/run_gliding.sh -gpu -full -grid -coltol $COLTOL -density <D> -seed <s> $STEPS" | tee -a "$LOG"

# GPU sweep, cheapest (sparsest) density first.
for D in 100 250 500 1000 2000 4000 6000 8000; do
  for S in 0 1 2; do
    echo "[$(date +%H:%M:%S)] SWEEP8 GPU d${D} seed${S}" >> .last_run_status
    echo "== [$(date +%H:%M:%S)] GPU d=${D} seed=${S} coltol=${COLTOL} steps=${STEPS} ==" >> "$LOG"
    scripts/run_gliding.sh -gpu -full -grid -coltol $COLTOL -density $D -seed $S $STEPS 2>&1 \
      | grep -E "GRID_ROW|STATS_STEADY_ROW|COV_ROW|NaN|nan|Exception|blow|Infinity|CUDA|out of mem" >> "$LOG"
  done
done

# CPU spot check: 1 seed at d4000 (docs/CPU_GPU_VALIDATION_POLICY.md §3/§5, dense chaotic-ensemble regime).
echo "[$(date +%H:%M:%S)] SWEEP8 CPU-arbiter d4000 seed0" >> .last_run_status
echo "== [$(date +%H:%M:%S)] CPU spot check (policy §3/§5) d=4000 seed=0 coltol=${COLTOL} steps=${STEPS} ==" >> "$LOG"
scripts/run_gliding.sh -full -grid -coltol $COLTOL -density 4000 -seed 0 $STEPS 2>&1 \
  | grep -E "GRID_ROW|STATS_STEADY_ROW|COV_ROW|NaN|nan|Exception|blow|Infinity" >> "$LOG"

echo "[$(date +%H:%M:%S)] SWEEP8 DONE" | tee -a .last_run_status >> "$LOG"
echo "SWEEP_DONE" | tee -a "$LOG"
