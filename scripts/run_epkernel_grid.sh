#!/bin/bash
# EPISODE_KERNEL_ACCOUNTING PART 1 — age-resolved episode kernel grid.
# CPU deterministic, full-binding velocity clamp, d2000, -matbox 50, 4 paired seeds.
# v = 0,4,8,12,14,16,18,20 µm/s. Emits FVROW/EPKSTAT/EPROW/EPKROW.
set -e
cd "$(dirname "$0")/.."
OUT=${1:-RUN_LOGS/2026-07-12_epkernel_grid.txt}
STEPS=${2:-12000}
: > "$OUT"
for v in 0 4 8 12 14 16 18 20; do
  for s in 0 1 2 3; do
    echo "### RUN v=$v seed=$s ###" >> "$OUT"
    ./scripts/run_gliding.sh -matbox 50 -density 2000 -vclamp "$v" -seed "$s" -epkernel "$STEPS" 2>/dev/null \
      | grep -E "^FVROW|^EPKSTAT|^EPROW|^EPKROW|COMPLETENESS" >> "$OUT"
  done
done
echo "DONE $OUT"
