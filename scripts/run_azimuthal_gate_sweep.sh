#!/bin/bash
# AZIMUTHAL GATE — velocity–density sweep with the orientational bind gate ON (Increment 2, 2026-07-09).
# The pre-refinement baseline is docs/DENSITY_SWEEP_coltol8.md (azimuthally-UNAWARE canonical). This sweep is
# the SAME canonical model + the roll spring (Inc 1/1b) + the orientational gate (-azimbind), Δ=45°, to test:
# does the orientational throttle CAP avgBound and bend velFitX toward saturation?
#   canonical + -azimbind -azaccept 45  (springs/Lymn-Taylor/adppibind/xbimplicit2 all default-on; density+coltol explicit)
# Densities {100..8000} µm^-2, coltol=8 nm, 3 seeds, M=60000 (0.6 s @ dt=1e-5). Cheapest-first.
# + Δ-probe at d4000 (60k, seed0) Δ∈{15,30,60,90} (45 from the sweep). + CPU basin-arbiter at d4000 Δ=45 (30k).
cd /home/jba/Code/SoftBox
LOG=RUN_LOGS/2026-07-09_azimuthal_gate_sweep.txt
STEPS=60000; COLTOL=8
mkdir -p RUN_LOGS
: > "$LOG"
echo "AZIMUTHAL GATE SWEEP (Δ=45) $(date)" | tee -a "$LOG"

for D in 100 250 500 1000 2000 4000 6000 8000; do
  for S in 0 1 2; do
    echo "[$(date +%H:%M:%S)] AZGATE GPU d${D} s${S}" >> .last_run_status
    echo "== [$(date +%H:%M:%S)] GATE d=${D} seed=${S} Δ=45 coltol=${COLTOL} steps=${STEPS} ==" >> "$LOG"
    scripts/run_gliding.sh -azimbind -azaccept 45 -gpu -full -grid -coltol $COLTOL -density $D -seed $S $STEPS 2>&1 \
      | grep -E "GRID_ROW|STATS_STEADY_ROW|NaN|nan|Exception|blow|Infinity|CUDA" >> "$LOG"
  done
done

echo "== Δ-PROBE d=4000 seed=0 (60k): Δ ∈ {15,30,60,90} (45 from the sweep above) ==" >> "$LOG"
for DELTA in 15 30 60 90; do
  echo "[$(date +%H:%M:%S)] AZGATE Δ-probe d4000 Δ${DELTA}" >> .last_run_status
  echo "-- [$(date +%H:%M:%S)] Δ=${DELTA} d=4000 seed=0 --" >> "$LOG"
  scripts/run_gliding.sh -azimbind -azaccept $DELTA -gpu -full -grid -coltol $COLTOL -density 4000 -seed 0 $STEPS 2>&1 \
    | grep -E "GRID_ROW|STATS_STEADY_ROW" >> "$LOG"
done

echo "== CPU BASIN-ARBITER d=4000 Δ=45 seed=0 (30k) — the graph-changed, reported dense point ==" >> "$LOG"
echo "[$(date +%H:%M:%S)] AZGATE CPU-arbiter d4000 Δ45" >> .last_run_status
scripts/run_gliding.sh -azimbind -azaccept 45 -full -grid -coltol $COLTOL -density 4000 -seed 0 30000 2>&1 \
  | grep -E "GRID_ROW|STATS_STEADY_ROW" >> "$LOG"

echo "[$(date +%H:%M:%S)] AZGATE SWEEP DONE" | tee -a .last_run_status >> "$LOG"
echo "AZGATE_SWEEP_DONE" | tee -a "$LOG"
