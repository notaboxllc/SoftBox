#!/bin/bash
# SYSTEM-WIDE THERMAL CORRECTION — STEP 2: does cooling the free myosins move binding?
# 4 arms at dt=1e-5, seed 0, 60k (0.6 s), GPU device-resident. per-bound = velFitX/avgBsteady.
#   1 uncorr        (baseline; byte-identity check → velFitX≈2.112)
#   2 -allnoise     (faithful bond-only, the ~63% reference)
#   3 -syswide      (faithful: bond + F9/F10 head-rot + chain-links; free-motor chain EXCLUDED)
#   4 -syswiderb    (+ free-motor chain, the re-baseline arm) → arm4-arm3 = the free-motor-chain effect
BASE="-gpu -full -grid -lymntaylor -adppibind -xbimplicit2 -ratefix -coltol 10 -density 1000 -seed 0"
STEPS=60000
LOG=RUN_LOGS/2026-07-07_syswide_step2.txt
: > "$LOG"
row() {  # $1=arm label, $2..=extra flags
  local label="$1"; shift
  echo "[$(date +%H:%M:%S)] STEP2 arm=$label" | tee -a .last_run_status
  echo "===== ARM $label =====" >> "$LOG"
  ./run_gliding.sh $BASE "$@" $STEPS 2>&1 \
     | grep -E "GRID_ROW|STATS_STEADY_ROW|syswide|FAITHFUL|RE-BASELINE" >> "$LOG"
}
row uncorr
row allnoise -allnoise
row syswide  -syswide
row syswiderb -syswiderb
echo "[$(date +%H:%M:%S)] STEP2 DONE" | tee -a .last_run_status
echo "===== SUMMARY =====" >> "$LOG"
grep -E "ARM |GRID_ROW" "$LOG" >> "$LOG"
cat "$LOG"
