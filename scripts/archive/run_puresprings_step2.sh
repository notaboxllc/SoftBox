#!/bin/bash
# PURE_SPRINGS STEP-2 powerstroke-force check (CPU, deterministic): per-head axial force
# (forceDotFil, the unitary/stall load) in the gliding context. raw vs pure-springs vs ratefix
# at production dt=1e-5. Expect bit-identical (springify(k)=rateFix(k)=k at refDt).
cd /home/jba/Code/SoftBox
LOG=RUN_LOGS/2026-07-08_puresprings_step2_stroke.txt
C="-full -grid -lymntaylor -adppibind -xbimplicit2 -coltol 10 -density 1000 -dt 1e-5 -seed 0 -stretchcensus 4000"
: > "$LOG"
echo "PURE_SPRINGS STEP-2 stroke-force (fdFilPN = per-head axial load)  dt=1e-5  $(date)" | tee -a "$LOG"
echo "== RAW ==" >> "$LOG";     ./run_gliding.sh $C 2>&1 | grep -E "STRETCH_ROW|STATS_STEADY_ROW" >> "$LOG"
echo "== SPRINGS ==" >> "$LOG"; ./run_gliding.sh -pairsprings -alignsprings -structsprings $C 2>&1 | grep -E "STRETCH_ROW|STATS_STEADY_ROW" >> "$LOG"
echo "== RATEFIX ==" >> "$LOG"; ./run_gliding.sh -ratefix -structrate $C 2>&1 | grep -E "STRETCH_ROW|STATS_STEADY_ROW" >> "$LOG"
echo "[$(date +%H:%M:%S)] STEP2 stroke-force DONE" | tee -a .last_run_status >> "$LOG"
