#!/bin/bash
# OVERSHOOT_DIAGNOSIS Part A — is the fine-dt thermcorr4 "overshoot" a WINDOWING artifact (A1),
# an IMPLEMENTATION artifact (A2 uniform control), or genuine noise-amplitude hypersensitivity?
#
# A1: the prior dtvanish_full 6.25e-7 arms ran 150k steps = 0.094 s sim-time (vs 60k×1e-5 = 0.6 s at
#     production). Re-run the 6.25e-7 arms at MATCHED sim-time 0.6 s = 960000 steps.
# A2: a FLAT uniform x0.98 on ALL Brownian (-uninoise 0.98) as the disambiguation control.
# A3: 2 seeds fine (0,1), 3 seeds coarse (0,1,2).  ~234 steps/s on -full ⇒ 960k ≈ 68 min/run.
cd /home/jba/Code/SoftBox
CFG="-gpu -full -grid -lymntaylor -adppibind -coltol 10 -density 1000 -xbimplicit2 -ratefix"
LOG=RUN_LOGS/2026-07-06_overshoot_A.txt
TMP=/tmp/claude-1000/-home-jba-Code-SoftBox/5194b040-d9c6-41a9-9f3d-afe0b394f22a/scratchpad/oa_run.txt
: > $LOG
run() {  # $1=dt $2=steps $3=seed $4=armname $5=armflags
  echo "  [$(date +%H:%M:%S)] dt=$1 steps=$2 seed=$3 $4" > .last_run_status
  ./run_gliding.sh $CFG -dt $1 -seed $3 $5 $2 > $TMP 2>&1
  g=$(grep GRID_ROW $TMP | grep -oE "velFitX=[0-9.]+|avgBsteady=[0-9.]+" | tr '\n' ' ')
  st=$(grep STATS_ROW $TMP | grep -oE "detachRatePerS=[0-9.]+|dwellMs=[0-9.]+" | tr '\n' ' ')
  echo "dt=$1 seed=$3 $4 $g $st" | tee -a $LOG
}

# COARSE (1e-5, 60k = 0.6 s): effect-size contrast + byte-identity (uncorr must reproduce velFitX~2.112)
echo "== COARSE dt=1e-5 60k (0.6 s) ==" | tee -a $LOG
for s in 0 1 2; do
  run 1e-5 60000 $s uncorr ""
  run 1e-5 60000 $s thermcorr4 "-thermcorr 4"
  run 1e-5 60000 $s uninoise0.98 "-uninoise 0.98"
done

# FINE (6.25e-7, 960k = 0.6 s, MATCHED sim-time): the A1/A2 decider
echo "== FINE dt=6.25e-7 960k (0.6 s, MATCHED) ==" | tee -a $LOG
for s in 0 1; do
  run 6.25e-7 960000 $s uncorr ""
  run 6.25e-7 960000 $s thermcorr4 "-thermcorr 4"
  run 6.25e-7 960000 $s uninoise0.98 "-uninoise 0.98"
done
echo "OVERSHOOT_A DONE" | tee -a $LOG
