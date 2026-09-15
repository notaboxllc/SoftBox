#!/bin/bash
# CPU REPLICATION of the +/-15 deg stroke-skew twirl, seeds 902/903 (seed 901 done on GPU before Xid 79).
#
# GPU is off the bus (Xid 154: "Node Reboot Required") and cannot be recovered remotely. The effect is ~9 sigma
# per run, so the CPU runner is amply sufficient to replicate it.
#
# Travel target 1.5 um (the GPU pair reached 1.24 um at ~9 sigma) -- turns/um is a SLOPE, so a shorter run
# costs precision, not validity. 4 concurrent x 3 workers = 12 threads on 8 physical cores.
cd /home/jba/Code/SoftBox
OUT=/home/jba/Code/SoftBox/RUN_LOGS/motor_audit/campaigns_2026-08/TWIRL_SKEW15
R=$OUT/REPORT_CPU.txt
say(){ echo "[$(date '+%m-%d %H:%M')] $*" | tee -a $R; }
say "CPU REPLICATION — +/-15 deg stroke skew, seeds 20260902/20260903, target 1.5 um, workers=3"
for S in 20260902 20260903; do
  for A in "pos 15" "neg -15"; do
    set -- $A
    ./scripts/run_site_normal_long_glide.sh -run -nohires -noviz -density 500 -matx 10.0 -maty 2.0 \
       -seed $S -filx 2.5 -eta 0.10 -dt 1.25e-6 -steps 6000000 -target 1.5 -workers 3 \
       -stroke-skew $2 -out $OUT/$1_s$S > $OUT/$1_s$S.log 2>&1 &
  done
done
wait
say "CPU replication finished"
python3 scripts/twirl_skew_analyse.py "$OUT" skew 2>&1 | tee -a $R
say "DONE."
