#!/bin/bash
# CPU≡GPU aggregate-within-SEM parity for -allnoise (chaotic gliding ⇒ not bit-identical; compare seed-means).
cd /home/jba/Code/SoftBox
BASE="-v1box -grid -lymntaylor -adppibind -coltol 10 -xbimplicit2 -ratefix -dt 1e-5 -allnoise"
LOG=RUN_LOGS/2026-07-06_allnoise_parity.txt
: > $LOG
for runner in gpu cpu; do
  gflag=""; [ "$runner" = "gpu" ] && gflag="-gpu"
  for s in 0 1 2; do
    echo "[$(date +%H:%M:%S)] $runner seed $s" > .last_run_status
    row=$(./run_gliding.sh $gflag $BASE -seed $s 20000 2>&1 | grep GRID_ROW)
    echo "$runner seed=$s $row" | tee -a $LOG
  done
done
echo "PARITY DONE" | tee -a $LOG
