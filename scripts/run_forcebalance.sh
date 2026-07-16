#!/bin/bash
# SoftBox — CANONICAL GLIDING FORCE-BALANCE overnight driver (calibrated-s2-l40 only; amendment).
# Full speed/force-density curve + high-density saturation + force-balance controls at 3000/µm².
# Runs on a spare core in parallel with the main gliding study (single-threaded, no contention).
set -u
cd "$(dirname "$0")/.."
OUT=RUN_LOGS/twobody_canonical_gliding/forcebalance
mkdir -p "$OUT"
SUM=$OUT/FB_SUMMARY.log
fb(){ echo "### $(date +%H:%M:%S)  $*" | tee -a "$SUM"
  ./scripts/run_lasertrap.sh -motor calibrated-s2-l40 -glide -forcebalance -out "$OUT" "$@" 2>&1 \
    | grep -vE 'WARNING|tornado|Picked up|preview' | grep -E '#   d=|# wrote|REFUSED' | tee -a "$SUM"; }

# ---- Full force-balance density curve (matx4, dur0.15, 4 seeds) incl. high-density saturation ----
for d in 100 200 400 700 1000 1500 2000 2500 3000 4000; do
  fb -density $d -matx 4 -maty 1 -dur 0.15 -seeds 4
done
# ---- Controls at 3000/µm²: half dt, enlarged cull radius (×2, ×4 ≈ near-brute) ----
fb -density 3000 -matx 4 -maty 1 -dur 0.15 -seeds 4 -dt 1.25e-6   -tag halfdt
fb -density 3000 -matx 4 -maty 1 -dur 0.15 -seeds 4 -queryscale 2.0 -tag cull2
fb -density 3000 -matx 4 -maty 1 -dur 0.15 -seeds 4 -queryscale 4.0 -tag cull4
# ---- Long plateau confirmation: 2.0 s at 3000/µm² (2 seeds) ----
fb -density 3000 -matx 4 -maty 1 -dur 2.0 -seeds 2 -tag long2s
echo "### FB DONE $(date +%H:%M:%S)" | tee -a "$SUM"
