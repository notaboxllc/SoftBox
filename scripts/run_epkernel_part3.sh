#!/bin/bash
# EPISODE_KERNEL_ACCOUNTING PART 3 — two-perturbation decomposition (which kernel component carries ΔV0).
#   arm 1: -xcatch 1.0   (catch-shape perturbation KNOWN to move V0 down ~5.7; VMAX_SENSITIVITY)
#   arm 2: -neckangle 40 & 80  (neck-angle KNOWN to move nominal geometry but NOT V0 strongly)
# Paired seeds 0-3, d2000, -matbox 50, CPU. Emits FVROW/EPKSTAT/EPROW/EPKROW tagged per arm.
set -e
cd "$(dirname "$0")/.."
OUT=${1:-RUN_LOGS/2026-07-12_epkernel_part3.txt}
STEPS=${2:-12000}
: > "$OUT"
run() {  # $1=tag  $2=v  $3=seed  $4..=extra flags
  local tag=$1 v=$2 s=$3; shift 3
  echo "### ARM $tag v=$v seed=$s ###" >> "$OUT"
  ./scripts/run_gliding.sh -matbox 50 -density 2000 -vclamp "$v" -seed "$s" -epkernel "$@" "$STEPS" 2>/dev/null \
    | grep -E "^FVROW|^EPKSTAT|^EPROW|^EPKROW|COMPLETENESS" | sed "s/^/$tag /" >> "$OUT"
}
# arm 1 — xCatch 1.0 nm (V0 expected ~5-6): bracket with a low-v grid
for v in 0 2 4 6 8 10; do for s in 0 1 2 3; do run XCATCH1 "$v" "$s" -xcatch 1.0; done; done
# arm 2a — neck angle 40 deg (nominal stroke smaller): bracket ~11-16
for v in 0 4 8 12 14 16; do for s in 0 1 2 3; do run NECK40 "$v" "$s" -neckangle 40; done; done
# arm 2b — neck angle 80 deg (nominal stroke larger)
for v in 0 4 8 12 14 16; do for s in 0 1 2 3; do run NECK80 "$v" "$s" -neckangle 80; done; done
echo "DONE $OUT"
