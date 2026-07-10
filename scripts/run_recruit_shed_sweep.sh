#!/bin/bash
# RECRUIT/SHED BALANCE sweep (2026-07-09). Does SLOWING the shedding of back-strained (brake) heads
# create the missing velocity ceiling? -brakehold <s> scales the SIGNED catch-slip CATCH term (kinParams[1]=αCatch,
# the term that EXPLODES for a post-stroke back-strained head F<0 ⇒ sheds resisting heads). s<1 = brakes persist.
# Anchor s=1.0 (skeletal) ⇒ canonical byte-identical. Report velFitX AND avgBsteady SEPARATELY (the recurring trap).
#   Ceiling signature: velFitX-vs-density FLATTENS + at fixed density velFitX↓/avgBound↑ (brakes biting).
#   Gumming up:        velFitX and avgBound DROP TOGETHER.
# PART A — slow the brake shed: s ∈ {1.0(anchor), 0.3, 0.1, 0.03} × d ∈ {2000,4000,8000}, coltol=8.
# PART B — CONTROL (recruitment reduction, should NOT cap): -azfalloff 16 × same 3 densities.
# Single-seed, 30k (avgB equilibrates in the 2nd-half window; velFitX noisier but the cap/no-cap TREND is the Q).
cd /home/jba/Code/SoftBox
LOG=RUN_LOGS/2026-07-09_recruit_shed_sweep.txt
STEPS=30000; COLTOL=8
mkdir -p RUN_LOGS
: > "$LOG"
echo "RECRUIT/SHED BALANCE SWEEP $(date)" | tee -a "$LOG"

echo "== PART A. SLOW-SHED grid: brakehold s x density (does velFitX-vs-density FLATTEN?) ==" >> "$LOG"
for S in 1.0 0.3 0.1 0.03; do
  for D in 2000 4000 8000; do
    echo "[$(date +%H:%M:%S)] SLOWSHED s${S} d${D}" > .last_run_status
    echo "-- [$(date +%H:%M:%S)] brakehold=${S} d=${D} seed=0 --" >> "$LOG"
    scripts/run_gliding.sh -brakehold $S -gpu -full -grid -coltol $COLTOL -density $D -seed 0 $STEPS 2>&1 \
      | grep -E "GRID_ROW|STATS_STEADY_ROW|NaN|nan|Exception|CUDA" >> "$LOG"
  done
done

echo "== PART B. CONTROL: recruitment reduction (-azfalloff 16) x density (should SCALE, not CAP) ==" >> "$LOG"
for D in 2000 4000 8000; do
  echo "[$(date +%H:%M:%S)] FALLOFF-CTRL n16 d${D}" > .last_run_status
  echo "-- [$(date +%H:%M:%S)] azfalloff=16 d=${D} seed=0 --" >> "$LOG"
  scripts/run_gliding.sh -azfalloff 16 -gpu -full -grid -coltol $COLTOL -density $D -seed 0 $STEPS 2>&1 \
    | grep -E "GRID_ROW|STATS_STEADY_ROW|NaN|nan|Exception|CUDA" >> "$LOG"
done

echo "[$(date +%H:%M:%S)] RECRUIT/SHED SWEEP DONE" > .last_run_status
echo "RECRUIT_SHED_SWEEP_DONE" | tee -a "$LOG"
