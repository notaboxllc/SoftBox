#!/bin/bash
# AZIMUTHAL GRADED-FALLOFF exploration (Increment 3, 2026-07-09). The decisive test: does a graded orientational
# affinity (a=b^n, MAX-combine) CAP avgBound (flatten vs density) at high n, where the hard cutoff couldn't?
#   canonical + -azimbind -azfalloff <n>  (implies the roll spring; density+coltol explicit)
# A. density-response at STEEP n (16,32) across d1000..d8000 — does avgB flatten (cap) or scale (~baseline×const)?
# B. n-response at d4000 — the throttle curve vs steepness.
# C. GPU-vs-CPU cross-check at d4000 n=16 (bind-path change on the bistability-sensitive path).
# Single-seed, 30k (avgB equilibrates in the 2nd-half window; velFitX noisier but the TREND is the question).
cd /home/jba/Code/SoftBox
LOG=RUN_LOGS/2026-07-09_azimuthal_falloff_sweep.txt
STEPS=30000; COLTOL=8
mkdir -p RUN_LOGS
: > "$LOG"
echo "AZIMUTHAL FALLOFF SWEEP $(date)" | tee -a "$LOG"

echo "== A. DENSITY-RESPONSE at steep n (does avgB CAP?) ==" >> "$LOG"
for N in 16 32; do
  for D in 1000 2000 4000 6000 8000; do
    echo "[$(date +%H:%M:%S)] FALLOFF n${N} d${D}" >> .last_run_status
    echo "-- [$(date +%H:%M:%S)] n=${N} d=${D} seed=0 --" >> "$LOG"
    scripts/run_gliding.sh -azfalloff $N -gpu -full -grid -coltol $COLTOL -density $D -seed 0 $STEPS 2>&1 \
      | grep -E "GRID_ROW|STATS_STEADY_ROW|NaN|nan|Exception|CUDA" >> "$LOG"
  done
done

echo "== B. n-RESPONSE at d4000 (throttle vs steepness; n=0 baseline check) ==" >> "$LOG"
for N in 0 1 2 4 8 64; do
  echo "[$(date +%H:%M:%S)] FALLOFF n-resp d4000 n${N}" >> .last_run_status
  echo "-- [$(date +%H:%M:%S)] n=${N} d=4000 seed=0 --" >> "$LOG"
  scripts/run_gliding.sh -azfalloff $N -gpu -full -grid -coltol $COLTOL -density 4000 -seed 0 $STEPS 2>&1 \
    | grep -E "GRID_ROW|STATS_STEADY_ROW" >> "$LOG"
done

echo "== C. GPU-vs-CPU cross-check at d4000 n=16 (CPU basin arbiter) ==" >> "$LOG"
echo "[$(date +%H:%M:%S)] FALLOFF CPU-arbiter d4000 n16" >> .last_run_status
echo "-- [$(date +%H:%M:%S)] CPU n=16 d=4000 seed=0 --" >> "$LOG"
scripts/run_gliding.sh -azfalloff 16 -full -grid -coltol $COLTOL -density 4000 -seed 0 $STEPS 2>&1 \
  | grep -E "GRID_ROW|STATS_STEADY_ROW" >> "$LOG"

echo "[$(date +%H:%M:%S)] FALLOFF SWEEP DONE" | tee -a .last_run_status >> "$LOG"
echo "FALLOFF_SWEEP_DONE" | tee -a "$LOG"
