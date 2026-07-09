#!/bin/bash
# dt-VANISHING confirm: at dt=6.25e-7 the correction factors are ≈0.98 (20× smaller than @1e-5), so
# corrected≈uncorrected — a dt-FIX, not a re-baseline. Single-seed velFitX is chaos-decorrelated, so
# compare 3-seed MEANS. Also runs @1e-5 for the effect-size contrast (correction big there).
cd /home/jba/Code/SoftBox
CFG="-gpu -v1box -grid -lymntaylor -adppibind -coltol 10 -xbimplicit2 -ratefix"
LOG=RUN_LOGS/2026-07-06_thermcorr_dtvanish.txt
: > $LOG
for dt in 1e-5 6.25e-7; do
  steps=20000; [ "$dt" = "6.25e-7" ] && steps=320000   # match ~0.2s sim-time window
  for arm in "uncorr:" "thermcorr4:-thermcorr 4"; do
    name=${arm%%:*}; flags=${arm#*:}
    for s in 0 1 2; do
      echo "  [$(date +%H:%M:%S)] dt=$dt $name seed=$s" > .last_run_status
      v=$(./run_gliding.sh $CFG -dt $dt -seed $s $flags $steps 2>&1 | grep GRID_ROW | grep -oE "velFitX=[0-9.]+ .*avgBsteady=[0-9.]+")
      echo "dt=$dt $name seed=$s $v" | tee -a $LOG
    done
  done
done
echo "DTVANISH DONE" | tee -a $LOG
