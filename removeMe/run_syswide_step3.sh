#!/bin/bash
# SYSTEM-WIDE THERMAL CORRECTION — STEP 3: convergence with the FAITHFUL -syswide on.
# MATCHED SIM-TIME 0.6 s (NOT matched steps): 60k@1e-5, 240k@2.5e-6, 480k@1.25e-6, 960k@6.25e-7.
# Faithful -syswide (free-motor-chain re-baseline EXCLUDED). seed 0 ladder + anchor seeds at 1e-5 & 6.25e-7.
# Uncorrected ladder is REUSED from RUN_LOGS/2026-07-06_overshoot_A.txt (identical config; byte-identity
# reconfirmed by STEP2's uncorr arm) — per-bound uncorr @1e-5=0.774(3-seed), @6.25e-7=1.592(2-seed).
BASE="-gpu -full -grid -lymntaylor -adppibind -xbimplicit2 -ratefix -coltol 10 -density 1000"
LOG=RUN_LOGS/2026-07-07_syswide_step3.txt
: > "$LOG"
run() {  # $1=dt $2=steps $3=seed
  echo "[$(date +%H:%M:%S)] STEP3 syswide dt=$1 steps=$2 seed=$3" | tee -a .last_run_status
  echo "===== syswide dt=$1 seed=$3 steps=$2 =====" >> "$LOG"
  ./run_gliding.sh $BASE -syswide -dt $1 -seed $3 $2 2>&1 \
     | grep -E "GRID_ROW|STATS_STEADY_ROW" >> "$LOG"
}
# seed-0 ladder (matched 0.6 s)
run 1e-5     60000  0
run 2.5e-6  240000  0
run 1.25e-6 480000  0
run 6.25e-7 960000  0
# anchor seeds
run 1e-5     60000  1
run 1e-5     60000  2
run 6.25e-7 960000  1
echo "[$(date +%H:%M:%S)] STEP3 DONE" | tee -a .last_run_status
cat "$LOG"
