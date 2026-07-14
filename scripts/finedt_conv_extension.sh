#!/bin/bash
# FINE-dt convergence extension: the 5e-6->2.5e-6 spot checks showed a MATERIAL, occupancy-driven
# positive drift at high density (d4000 +8%, all 5e-6 seeds below all 2.5e-6 seeds). Per the study's
# Part-3 rule, add a dt=1.25e-6 point at d4000 (2 seeds, seed-matched to the 2.5e-6 s0/s1) to establish
# whether the high-density velocity is converging (2.5->1.25 small) or still climbing.
# ABSOLUTE paths throughout (the earlier inline relative-path launch failed after `cd $WT`).
set -u
WT=/home/jba/Code/SoftBox-finedt-canon
MAIN=/home/jba/Code/SoftBox
CELLS=$MAIN/RUN_LOGS/finedt_cells
DT=1.25e-6
M=480000          # 0.6 s / 1.25e-6
for S in 0 1; do
  f=$CELLS/gpu_dt${DT}_d4000_s${S}.log
  if [ -f "$f" ] && grep -q GRID_ROW "$f"; then echo "[skip] d4000 s$S @$DT"; continue; fi
  echo "[$(date +%H:%M:%S)] RUN gpu_dt${DT}_d4000_s${S} M=$M" >> "$MAIN/RUN_LOGS/finedt_progress.txt"
  ( cd "$WT" && timeout 21600 scripts/run_gliding.sh -gpu -full -grid -coltol 8 -matbox 50 \
      -density 4000 -seed "$S" -dt "$DT" "$M" ) > "$f" 2>&1
  grep -E "GRID_ROW|COV_ROW" "$f" | sed "s/^/  [d4000 s$S @$DT] /"
done
echo "[$(date +%H:%M:%S)] CONV_EXTENSION COMPLETE" >> "$MAIN/RUN_LOGS/finedt_progress.txt"
cd "$MAIN" && python3 scripts/finedt_analyze.py > RUN_LOGS/finedt_analysis_final.txt 2>&1
echo "CONV_EXTENSION_DONE"
