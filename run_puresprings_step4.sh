#!/bin/bash
# PURE_SPRINGS STEP-4 the MOVED LIMIT (CPU v1box = basin-STABLE arbiter, avoids GPU bistability).
# At dt=1e-5=refDt all 3 arms coincide; at dt=1e-6 they DIVERGE:
#   raw  = frozen fraction-per-step (α=frac, dt-flat, skeleton stiffens ∝1/dt)
#   springs = fixed stiffness  springify(k)=k·dt/refDt  (α∝dt, forward-Euler)
#   ratefix = geometric rate   rateFix(k)=1−(1−k)^(dt/refDt)  (α∝dt, exact decay)
# The springs↔ratefix gap = the continuum factor −ln(1−k)/k (expected re-baseline, not a regression).
cd /home/jba/Code/SoftBox
LOG=RUN_LOGS/2026-07-08_puresprings_step4_movedlimit.txt
BOX="-v1box -grid -lymntaylor -adppibind -xbimplicit2 -coltol 10 -density 1000 -seed 0"
: > "$LOG"
echo "PURE_SPRINGS STEP-4 moved limit (CPU v1box, seed 0, matched sim-time 0.06s)  $(date)" | tee -a "$LOG"
# production-dt anchor (1e-5, 6000 steps = 0.06s): all 3 arms bit-identical here
echo "== ANCHOR raw dt=1e-5 6000 (=0.06s) ==" >> "$LOG"
./run_gliding.sh $BOX -dt 1e-5 6000 2>&1 | grep -E "GRID_ROW|STATS_STEADY_ROW" >> "$LOG"
# fine-dt divergence point (1e-6, 60000 steps = 0.06s matched)
for ARM in raw springs ratefix; do
  case $ARM in
    raw)     F="" ;;
    springs) F="-pairsprings -alignsprings -structsprings" ;;
    ratefix) F="-ratefix -structrate" ;;
  esac
  echo "[$(date +%H:%M:%S)] STEP4 dt=1e-6 arm=$ARM" >> .last_run_status
  echo "== dt=1e-6 arm=$ARM flags=[$F] 60000 (=0.06s) ==" >> "$LOG"
  ./run_gliding.sh $F $BOX -dt 1e-6 60000 2>&1 | grep -E "GRID_ROW|STATS_STEADY_ROW|springs|ratefix|structrate" >> "$LOG"
done
echo "[$(date +%H:%M:%S)] STEP4 moved-limit DONE" | tee -a .last_run_status >> "$LOG"
