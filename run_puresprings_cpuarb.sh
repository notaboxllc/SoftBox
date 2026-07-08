#!/bin/bash
# PURE_SPRINGS STEP-3 CPU ARBITER (mandatory, basin-stable). At dt=1e-5 springs≡raw bit-identical
# (springify exact ×1.0), so springs-vs-ratefix on CPU == raw-vs-ratefix. If CPU keeps the three arms
# together (unlike the GPU where ratefix split to the 2.11 basin), the GPU split is a basin flip
# (ratefix's exp/log ULP), NOT a formulation effect. -full, seed 0, 20k steps (0.2s window).
cd /home/jba/Code/SoftBox
LOG=RUN_LOGS/2026-07-08_puresprings_cpuarbiter.txt
C="-full -grid -lymntaylor -adppibind -xbimplicit2 -coltol 10 -density 1000 -dt 1e-5 -seed 0 20000"
: > "$LOG"
echo "PURE_SPRINGS STEP-3 CPU ARBITER (basin-stable)  dt=1e-5  20k  $(date)" | tee -a "$LOG"
for ARM in raw springs ratefix; do
  case $ARM in
    raw)     F="" ;;
    springs) F="-pairsprings -alignsprings -structsprings" ;;
    ratefix) F="-ratefix -structrate" ;;
  esac
  echo "[$(date +%H:%M:%S)] CPUARB arm=$ARM" >> .last_run_status
  echo "== arm=$ARM flags=[$F] ==" >> "$LOG"
  ./run_gliding.sh $F $C 2>&1 | grep -E "GRID_ROW|STATS_STEADY_ROW" >> "$LOG"
done
echo "[$(date +%H:%M:%S)] CPUARB DONE" | tee -a .last_run_status >> "$LOG"
