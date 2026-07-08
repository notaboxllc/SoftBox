#!/bin/bash
# XBTRAP_PROBE STEP 2 — does a trapezoidal (Crank–Nicolson midpoint) re-timing of the coupled F8 star (-xbtrap)
# recover the converged per-bound glide at production dt=1e-5, or is the residual kinetics/event-timing (sub-step)?
# Paired short-window comparison (matband + earlystop cap 0.2 s), seed 0, coltol10, d1000,
# -full -grid -lymntaylor -adppibind -ratefix. Three arms at dt=1e-5 (explicit / -xbimplicit2 / -xbtrap)
# + one -xbtrap @2.5e-6 (confirm same continuum). Read against the converged per-bound ≈1.78 (ROTIMPLICIT Part B).
set -u
LOG=RUN_LOGS/2026-07-06_xbtrap_step2.txt
STAT=.last_run_status
: > "$LOG"
COMMON="-gpu -full -grid -lymntaylor -adppibind -ratefix -coltol 10 -density 1000 -matband 1.2 -earlystop -escap 0.2 -seed 0"

run_arm () {   # $1=label $2=dt $3=steps $4..=extra flags
  local label="$1"; local dt="$2"; local steps="$3"; shift 3
  echo "XBTRAP STEP2 [$(date +%H:%M:%S)] running arm=$label dt=$dt steps=$steps" > "$STAT"
  echo "===== ARM $label (dt=$dt, steps=$steps, flags: $*) =====" >> "$LOG"
  ./run_gliding.sh $COMMON -dt "$dt" "$@" "$steps" 2>&1 \
     | grep -vE "WARNING|tornado|Picked|^Using" >> "$LOG"
  # extract the reported metrics
  local row=$(grep "EARLYSTOP_ROW" "$LOG" | tail -1)
  local grid=$(grep "GRID_ROW" "$LOG" | tail -1)
  local vf=$(echo "$grid" | grep -oE "velFitX=[-0-9.]+" | head -1 | cut -d= -f2)
  local ab=$(echo "$grid" | grep -oE "avgBsteady=[-0-9.]+" | cut -d= -f2)
  local rel=$(echo "$row" | grep -oE "relSEM=[-0-9.eNA]+" | cut -d= -f2)
  local pb=$(awk "BEGIN{ if ($ab+0>0) printf \"%.3f\", $vf/$ab; else print \"NA\" }")
  echo "RESULT arm=$label dt=$dt velFitX=$vf avgBsteady=$ab per-bound=$pb relSEM=$rel" | tee -a "$LOG"
}

echo "XBTRAP STEP2 starting $(date)" > "$STAT"
run_arm explicit    1e-5   20000                 # arm 1: explicit F8 (coupled star OFF)
run_arm xbimplicit2 1e-5   20000  -xbimplicit2   # arm 2: backward-Euler coupled F8 star
run_arm xbtrap      1e-5   20000  -xbtrap        # arm 3: trapezoidal/CN coupled F8 star
run_arm xbtrap_fine 2.5e-6 80000  -xbtrap        # arm 4: -xbtrap @2.5e-6 (same-continuum confirm)
run_arm explicit_fine    2.5e-6 80000               # arm 5: explicit @2.5e-6 (climb-factor control)
run_arm xbimplicit2_fine 2.5e-6 80000 -xbimplicit2  # arm 6: -xbimplicit2 @2.5e-6 (climb-factor control)
echo "XBTRAP STEP2 DONE $(date)" > "$STAT"
echo "==== SUMMARY ====" | tee -a "$LOG"
grep "^RESULT" "$LOG" | tee -a "$LOG"
