#!/bin/bash
# VMAX_SENSITIVITY (STAGE 1): clamp-V₀ one-at-a-time sensitivity screen.
# For each eligible (non-PINNED) parameter at low/high (baseline once), run the velocity clamp over an
# adaptive v-grid at d1000 (V₀ density-independent, ~2× faster than d2000), CPU, 1 seed. Emits SENS rows.
set -u
STEPS=${STEPS:-8000}; SEED=${SEED:-0}; DENS=${DENS:-1000}
VS=${VS:-"0 4 8 12 16 20"}
OUT=RUN_LOGS/$(date +%F)_vmax_sens.txt
mkdir -p RUN_LOGS
: > "$OUT"
run() { # param level flagargs...
  local p="$1" lvl="$2"; shift 2
  for V in $VS; do
    fa=$(scripts/run_gliding.sh -matbox 50 -density "$DENS" -vclamp "$V" "$@" -seed "$SEED" "$STEPS" 2>&1 \
         | grep "^FVROW" | sed -E 's/.*fbar_avail=([-0-9.]+).*/\1/')
    echo "SENS param=$p level=$lvl v=$V fbar_avail=$fa" | tee -a "$OUT"
  done
}
echo "# VMAX_SENSITIVITY screen  steps=$STEPS seed=$SEED dens=$DENS grid=[$VS]" | tee -a "$OUT"
run baseline base
run neckangle lo -neckangle 40 ; run neckangle hi -neckangle 80
run adprate   lo -adprate 300  ; run adprate   hi -adprate 3000
run pirate    lo -pirate 3000  ; run pirate    hi -pirate 30000
run atpdet    lo -atpdetrate 5000 ; run atpdet hi -atpdetrate 50000
run koff      lo -koff 30      ; run koff      hi -koff 300
run acatch    lo -acatch 0.46  ; run acatch    hi -acatch 1.0
run aslip     lo -aslip 0.032  ; run aslip     hi -aslip 0.40
run xcatch    lo -xcatch 1.0   ; run xcatch    hi -xcatch 5.0
run xslip     lo -xslip 0.16   ; run xslip     hi -xslip 2.0
run myospring lo -myospring 0.3 ; run myospring hi -myospring 2.0
echo "VMAX_SENS DONE" | tee -a "$OUT"
