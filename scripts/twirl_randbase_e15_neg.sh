#!/bin/bash
# FINISH the randomised-base eps=15 rung, then STOP. (jba 2026-08-30: stop after e15; move on to low viscosity.)
#
# The parent ladder script (twirl_randbase_ladder.sh) was terminated mid-campaign by PID so it would not roll on
# into the e10/e5 rungs; its already-running eps=+15 batch was deliberately left alive. This script waits for that
# batch to finish, runs the matching eps=-15 batch, analyses the completed rung, and exits.
#
# WHY THE LADDER WAS TRUNCATED. Randomised base roughly HALVES engagement (avgBound ~1.00 -> ~0.55) and slows the
# glide ~3x (~0.74 -> ~0.26 um/s). Since the stop condition is TRAVEL, that turned a ~4 h arm into a ~12.3 h arm
# and the full 18-run ladder into ~78 h. eps=15 alone answers the question the campaign exists for -- whether the
# aligned-base 13.2 sigma survives realistic orientational disorder -- and the ladder SHAPE is already established
# from the aligned campaign (k = 0.2639 +- 0.0174 turns/um/deg, chi2 1.07/2, power-law exponent 0.96 +- 0.22).
cd /home/jba/Code/SoftBox
OUT=/home/jba/Code/SoftBox/RUN_LOGS/motor_audit/campaigns_2026-08/TWIRL_RANDBASE_LADDER
R=$OUT/REPORT.txt
say(){ echo "[$(date '+%m-%d %H:%M')] $*" | tee -a $R; }
DENS=500; TARGET=1.2; MX=10.0; MY=2.0; FILX=2.5; STEPS=6000000; DT=1.25e-6; ETA=0.10
SEEDS="20260901 20260902 20260903"
say "  [follow-on] waiting for the running eps=+15 batch to finish (ladder truncated after e15 per jba)"
for p in 6106 6107 6108; do while kill -0 $p 2>/dev/null; do sleep 60; done; done
say "  e15 pos done"
nvidia-smi -L >/dev/null 2>&1 || { say "ABORT before e15/neg: GPU off the bus. pos arm intact; rerun to resume."; exit 2; }
say "  e15 neg (eps=-15, randbase) starting — 3 seeds concurrent"
for S in $SEEDS; do
  tag=e15/neg_s$S; rf=""
  [ -f "$OUT/$tag/checkpoint.bin" ] && rf="-resume" && say "    $tag: resuming from checkpoint"
  ./scripts/run_gpu_monitored.sh ./scripts/run_site_normal_glide_gpu.sh -run -gpu -devicecull -nohires -noviz \
     -density $DENS -matx $MX -maty $MY -seed $S -filx $FILX -eta $ETA -dt $DT -steps $STEPS \
     -target $TARGET -stroke-skew -15 -randbase -randbase-seed $S $rf -out $OUT/$tag >> $OUT/e15_neg_s$S.log 2>&1 &
done
wait
say "  e15 neg done"
say "  --- rung e15 complete (LADDER ENDS HERE BY DESIGN) ---"
python3 scripts/twirl_skew_analyse.py "$OUT/e15" skew 2>&1 | tee -a $R
python3 scripts/twirl_ladder_analyse.py "$OUT" 2>&1 | tee -a $R
say "DONE."
