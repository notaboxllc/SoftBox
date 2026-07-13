#!/bin/bash
# PLATEAU_ORIGIN_AUDIT — plateau-band force/torque/energy balance at v=0 and v≈16, × neck 40/60/80, paired seeds.
# Tests whether the same plateau/residual strain RE-EMERGES across neck angles (constraint-set vs stroke-set).
set -e
cd "$(dirname "$0")/.."
OUT=${1:-RUN_LOGS/2026-07-12_plateau_grid.txt}
STEPS=${2:-12000}
: > "$OUT"
for neck in 40 60 80; do
  for v in 0 16; do
    for s in 0 1 2 3; do
      echo "### neck=$neck v=$v seed=$s ###" >> "$OUT"
      ./scripts/run_gliding.sh -matbox 50 -density 2000 -vclamp "$v" -neckangle "$neck" -seed "$s" -epkernel "$STEPS" 2>/dev/null \
        | grep -E "^PLATROW|^EPKSTAT" >> "$OUT"
    done
  done
done
echo "DONE $OUT"
