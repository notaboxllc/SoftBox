#!/bin/bash
# §6.11 Phase C: 3-cell bundled A/B (cap × refractory), GPU 14x2 -full, dt=1e-5, 10k steps.
#   cell1: cap OFF + 1-step block (HEAD)          = (no toggles)
#   cell2: cap OFF + rate-faithful refractory     = -faithfulrefractory
#   cell3: cap ON  + rate-faithful refractory     = -faithfulrelease -faithfulrefractory
# grid (netXY/netSteady/instSteady/avgBound[/CAP_ROW]) n=16; assist (assistFrac/occ) n=3.
cd /home/jba/Code/SoftBox
DIR=RUN_LOGS/2026-06-16_4biv_refractory_bundle
RAW=$DIR/grid.txt
ALOG=$DIR/assist.txt
STEPS=10000
GRIDSEEDS="1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16"
ASSISTSEEDS="1 2 3"
: > "$RAW"; : > "$ALOG"
status() { echo "[$(date +%H:%M)] PhaseC $1" > .last_run_status; }
T0=$(date +%s)

declare -A FLAGS
FLAGS[cell1]=""
FLAGS[cell2]="-faithfulrefractory"
FLAGS[cell3]="-faithfulrelease -faithfulrefractory"

# ---- grid: primary net/avgBound stats (+ cap rate on cell3) ----
for cell in cell1 cell2 cell3; do
  for s in $GRIDSEEDS; do
    status "grid $cell seed $s / 16 (elapsed $(( ($(date +%s)-T0)/60 ))m)"
    echo "=== GRID $cell seed=$s ${FLAGS[$cell]} ===" >> "$RAW"
    ./run_gliding.sh -gpu -full ${FLAGS[$cell]} -grid -seed $s $STEPS 2>&1 \
      | grep -E "GRID_ROW|CAP_ROW" >> "$RAW"
  done
done

# ---- assist: directedness check (assist-fraction + occupancy), n=3 ----
for cell in cell1 cell2 cell3; do
  for s in $ASSISTSEEDS; do
    status "assist $cell seed $s / 3 (elapsed $(( ($(date +%s)-T0)/60 ))m)"
    echo "=== ASSIST $cell seed=$s ${FLAGS[$cell]} ===" >> "$ALOG"
    ./run_gliding.sh -gpu -full ${FLAGS[$cell]} -assistlog -seed $s $STEPS 2>&1 \
      | grep -E "ASSIST_SUMMARY" >> "$ALOG"
  done
done

status "DONE ($(( ($(date +%s)-T0)/60 ))m)"
echo "Phase C done $(date +%H:%M)"
