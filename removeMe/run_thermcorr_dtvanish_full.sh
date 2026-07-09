#!/bin/bash
# Robust dt-vanishing on the -full scene (26740 motors averages out small-box chaos): uncorr vs thermcorr4
# at 1e-5 (correction ACTIVE) and 6.25e-7 (correction should VANISH ⇒ corr≈uncorr). Same window per dt.
cd /home/jba/Code/SoftBox
CFG="-gpu -full -grid -lymntaylor -adppibind -coltol 10 -density 1000 -xbimplicit2 -ratefix -seed 0"
LOG=RUN_LOGS/2026-07-06_thermcorr_dtvanish_full.txt
: > $LOG
for pair in "1e-5:60000" "6.25e-7:150000"; do
  dt=${pair%%:*}; steps=${pair#*:}
  for arm in "uncorr:" "thermcorr4:-thermcorr 4"; do
    name=${arm%%:*}; flags=${arm#*:}
    echo "  [$(date +%H:%M:%S)] full dt=$dt $name" > .last_run_status
    v=$(./run_gliding.sh $CFG -dt $dt $flags $steps 2>&1 | grep GRID_ROW | grep -oE "velFitX=[0-9.]+ .*avgBsteady=[0-9.]+")
    echo "full dt=$dt $name $v" | tee -a $LOG
  done
done
echo "FULL DTVANISH DONE" | tee -a $LOG
