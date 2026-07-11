#!/bin/bash
# GLIDEKON_CROSSOVER — the minimal decisive finite-kOn sweep.
# Does the velFitX–density curve FLATTEN as kOn drops (release-limited crossover) or only SCALE (structural ceiling)?
# 2 densities (baseline climb) × {deterministic baseline, kOn ladder centered on refill≈τ_on}. Single-seed, SHORT.
#   kOn* ≈ 1/τ_on / Δl ≈ 1667 / 0.012 µm ≈ 1.4e5 µm⁻¹s⁻¹ ⇒ ladder {1.5e6, 1.5e5, 1.5e4} (10×/1×/0.1×) + baseline.
# Chamber -matbox 50 keeps d8000 coverage-clean (COLTOL_REGIME_SWEEP PART 0).
cd /home/jba/Code/SoftBox
OUT=RUN_LOGS/2026-07-10_glidekon_crossover.txt
STEPS=${STEPS:-15000}
: > "$OUT"
run() {  # $1=label  $2=density  $3=kOn (0 ⇒ deterministic baseline, no flag)
  echo "===== $1 : density=$2 kOn=$3 steps=$STEPS =====" | tee -a "$OUT"
  local FLAG=""
  [ "$3" != "0" ] && FLAG="-glidekon $3"
  ./scripts/run_gliding.sh -gpu -full -grid -matbox 50 $FLAG -density "$2" -seed 0 "$STEPS" 2>&1 \
     | grep -iE "GRID_ROW|STATS_ROW|STATS_STEADY_ROW|velFitX .*<==|Graph resize|Exception|Error|OutOfMemory" | tee -a "$OUT"
  echo "" | tee -a "$OUT"
}
for D in 2000 8000; do
  run "baseline-det" "$D" 0
  run "kon-1.5e4"    "$D" 1.5e4
  run "kon-1.5e5"    "$D" 1.5e5
  run "kon-1.5e6"    "$D" 1.5e6
done
echo "DONE" | tee -a "$OUT"
