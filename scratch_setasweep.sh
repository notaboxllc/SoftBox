#!/bin/bash
# SET-A speed-density sweep driver. Args: <M> <out_file> <seeds csv> <densities csv>
# Runs the calibrated canonical config-1 motor (force-matched κ default) on the full-mat bed,
# GPU device-resident, grid measurement (velFitX = post-settling slope). Logs GRID_ROW/COV_ROW.
set -u
M="$1"; OUT="$2"; SEEDS="$3"; DENS="$4"
STACK="-matbed -config1 -kon 2e5 -tauavg 0.5 -xcatch 2.5 -grid -gpu"
echo "# SET-A sweep  M=$M  stack: $STACK  $(date)" >> "$OUT"
for d in $(echo "$DENS" | tr ',' ' '); do
  for s in $(echo "$SEEDS" | tr ',' ' '); do
    echo "[$(date +%H:%M:%S)] density=$d seed=$s ..." >> .last_run_status
    line=$(timeout 1200 ./run_gliding.sh $STACK -density $d -seed $s $M 2>&1 | grep -E 'GRID_ROW|COV_ROW|VIOLATED|Exception|NaN=true')
    echo "density=$d seed=$s" >> "$OUT"
    echo "$line" >> "$OUT"
    echo "  -> $(echo "$line" | grep GRID_ROW | sed 's/.*velFitX/velFitX/' | cut -d' ' -f1-2)" >> .last_run_status
  done
done
echo "# DONE $(date)" >> "$OUT"
echo "[$(date +%H:%M:%S)] SWEEP DONE -> $OUT" >> .last_run_status
