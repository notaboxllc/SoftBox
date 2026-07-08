#!/bin/bash
# -allnoise DIAGNOSIS STEP 2 (deterministic factor-response, CPU): on the faithful sequential runner
# (where scale-1.0 ≡ OFF byte-identically, STEP 1), sweep the APPLIED scale and measure the GENUINE
# noise-factor effect — uncontaminated by the GPU graph-split. Smooth-from-zero ⇒ proportional/faithful
# (the GPU factor-insensitivity is the artifact); jump ⇒ threshold/bistable even on the deterministic runner.
# dt=1e-5 (where 30k=0.3s is CPU-feasible), seed 0. Scale 1.0=OFF(proven); 0.90; real -allnoise≈0.857/0.889.
cd "$(dirname "$0")"
CFG="-full -grid -lymntaylor -adppibind -xbimplicit2 -ratefix -structrate -coltol 10 -density 1000 -dt 1e-5"
OUT=scratch_allnoise/step2_cpu_10k.txt
: > $OUT
for arm in "OFF:" "SCALE0.90:-allnoisescale 0.90" "ALLNOISE:-allnoise"; do
  name=${arm%%:*}; flag=${arm#*:}
  echo "### arm=$name (CPU, 10k) ###" | tee -a $OUT
  ./run_gliding.sh $CFG $flag -seed 0 10000 2>&1 | grep -E "GRID_ROW|STATS_STEADY_ROW" | sed "s/^/$name /" | tee -a $OUT
done
echo "DONE" | tee -a $OUT
