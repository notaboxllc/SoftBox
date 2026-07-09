#!/bin/bash
# -allnoise DIAGNOSIS STEP 1 (decisive): at the A/B-table window (60k=0.6s, GPU device-resident),
# does factor-1.0 (code path active, pure graph-split perturbation) reproduce the REAL -allnoise offset?
# 3 arms × 3 seeds. OFF = arm A; scale1.0 = code path @ factor 1.0 (isolates the graph-split artifact);
# allnoise = the real per-mode √((2−α)/2) factor (total effect). Config = RECONVERGENCE arm A/B.
cd "$(dirname "$0")"
CFG="-gpu -full -grid -lymntaylor -adppibind -xbimplicit2 -ratefix -structrate -coltol 10 -density 1000 -dt 1e-5"
OUT=scratch_allnoise/step1_60k.txt
: > $OUT
for s in 0 1 2; do
  for arm in "OFF:" "SCALE1:-allnoisescale 1.0" "ALLNOISE:-allnoise"; do
    name=${arm%%:*}; flag=${arm#*:}
    echo "### arm=$name seed=$s ###" | tee -a $OUT
    ./run_gliding.sh $CFG $flag -seed $s 60000 2>&1 | grep -E "GRID_ROW|STATS_STEADY_ROW" | sed "s/^/$name s$s /" | tee -a $OUT
  done
done
echo "DONE" | tee -a $OUT
