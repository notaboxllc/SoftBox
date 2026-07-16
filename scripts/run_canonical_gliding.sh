#!/bin/bash
# SoftBox — CANONICAL GLIDING VALIDATION STUDY driver (CPU-only, two-body arc).
# Runs the matched -motor <id> -glide assay across models/phases cheap→expensive so partial
# completion still yields the full calibrated+fixed-anchor story; explicit-s2-l40 (≈43× costlier)
# is staged + reduced (Outcome E). All results land under RUN_LOGS/twobody_canonical_gliding/.
# Usage: ./scripts/run_canonical_gliding.sh [phase1|baseline|tierB|denss|phase2|tierBx|denssx|lensweep|tscull|all]
set -u
cd "$(dirname "$0")/.."
ROOT=RUN_LOGS/twobody_canonical_gliding
SUM=$ROOT/SUMMARY.log
run(){ # run <outdir> <motor> <flags...>
  local out="$ROOT/$1"; shift; local motor="$1"; shift
  echo "### $(date +%H:%M:%S)  -motor $motor -glide $*  → $out" | tee -a "$SUM"
  ./scripts/run_lasertrap.sh -motor "$motor" -glide -out "$out" "$@" 2>&1 \
    | grep -vE 'WARNING|tornado|Picked up|preview' \
    | grep -E '#   (calibrated|explicit|fixed)|transWander|# wrote' | tee -a "$SUM"
}
PHASE="${1:-all}"

# ---- Phase 1: calibrated full production assay (matx12, dur2.0, 3 seeds) ~30min ----
if [ "$PHASE" = phase1 ] || [ "$PHASE" = all ]; then
  run phase1 calibrated-s2-l40 -density 1000 -matx 12 -maty 1 -dur 2.0 -seeds 3
fi
# ---- Baseline: fixed-anchor full assay (matched) ~15min ----
if [ "$PHASE" = baseline ] || [ "$PHASE" = all ]; then
  run baseline fixed-anchor -density 1000 -matx 12 -maty 1 -dur 2.0 -seeds 3
fi
# ---- Tier B reduced matched (matx4, dur0.15, 8 seeds) calibrated + fixed ----
if [ "$PHASE" = tierB ] || [ "$PHASE" = all ]; then
  run tierB calibrated-s2-l40 -density 1000 -matx 4 -maty 1 -dur 0.15 -seeds 8
  run tierB fixed-anchor      -density 1000 -matx 4 -maty 1 -dur 0.15 -seeds 8
fi
# ---- Phase 4 density sweep (matx4, dur0.15, 4 seeds) calibrated + fixed full set ----
if [ "$PHASE" = denss ] || [ "$PHASE" = all ]; then
  for d in 100 200 400 700 1000 1500; do
    run denssweep calibrated-s2-l40 -density $d -matx 4 -maty 1 -dur 0.15 -seeds 4
  done
  for d in 100 200 400 700 1000 1500; do
    run denssweep fixed-anchor -density $d -matx 4 -maty 1 -dur 0.15 -seeds 4
  done
fi
# ---- Phase 5 filament-length sweep (calibrated, saturated d1000) ~15min ----
if [ "$PHASE" = lensweep ] || [ "$PHASE" = all ]; then
  for ns in 6 12 24 46; do   # ~1.06 / 2.11 / 4.22 / 8.10 µm
    run lensweep calibrated-s2-l40 -density 1000 -matx 12 -maty 1 -dur 0.2 -seeds 3 -nseg $ns
  done
fi
# ---- Phase 7 timestep + cull validation (calibrated: half dt) ~10min ----
if [ "$PHASE" = tscull ] || [ "$PHASE" = all ]; then
  run ts_cull calibrated-s2-l40 -density 1000 -matx 4 -maty 1 -dur 0.15 -seeds 4 -dt 1.25e-6
fi
# ================= explicit-s2-l40 (Outcome E): staged + reduced, runs LAST =================
# ---- Phase 2: explicit staged feasibility (matx12, 1 seed) 0.05/0.2/0.5 s ~2.2h ----
if [ "$PHASE" = phase2 ] || [ "$PHASE" = all ]; then
  for dur in 0.05 0.2 0.5; do
    run phase2 explicit-s2-l40 -density 1000 -matx 12 -maty 1 -dur $dur -seeds 1
  done
fi
# ---- Tier B explicit reduced matched (matx4, dur0.1, 8 seeds) ~2.4h ----
if [ "$PHASE" = tierBx ] || [ "$PHASE" = all ]; then
  run tierB explicit-s2-l40 -density 1000 -matx 4 -maty 1 -dur 0.1 -seeds 8
fi
# ---- Phase 4 explicit density subset (matx4, dur0.12, 3 seeds) {200,700,1500} ~3h ----
if [ "$PHASE" = denssx ] || [ "$PHASE" = all ]; then
  for d in 200 700 1500; do
    run denssweep explicit-s2-l40 -density $d -matx 4 -maty 1 -dur 0.12 -seeds 3
  done
fi
echo "### DONE $(date +%H:%M:%S)" | tee -a "$SUM"
