#!/bin/bash
# TWIRL REPRODUCTION on the GPU (device-resident site-normal graph).
#
# Same experiment as the CPU version: does a gliding filament accumulate SEVERAL VISIBLE TURNS about its own
# axis over microns of travel, and does that REVERSE on a mirrored actin lattice? theta(t) logged ~450x/run.
#
# GPU chosen after the 2026-08-21 20:17 freeze: 13x faster (233 vs 17.5 steps/s) AND much cooler -- a GPU run
# uses ~1 core to feed the device (47 C / 53 W) where 6 CPU runs pin all 8 cores for days, which is what had
# the fans screaming. Resumable: every run writes checkpoint.bin; -resume picks it up after any crash/reboot.
#
# LAWN SIZING (2026-08-22): MAT_Y must come from the MEASURED lateral wander, NOT from binding reach.
# -maty 0.5 looked safe (reach ~0.183 um) but the filament diffuses in y AND yaws; capture rate collapsed
# 1.01 -> 0.41 /ms exactly when the ends crossed |y|=0.25. sqrt(2*D_y*T)+yaw ~ 0.49 um over this run, so
# maty=2.0 keeps ~2x margin. The filament starts at +x (FILX) and glides toward -x, so the whole lawn lies
# ahead of it and the x-extent stays cheap. trajectory_summary.csv now carries yMargin_um; if it ever goes
# negative the run is binding nothing and the glide decay is an ARTIFACT, not motor failure.
#
# CAVEAT (from run_site_normal_glide_gpu.sh): the whole-step CPU/GPU gate is not green on the BOUND branch.
# Mitigated here because BOTH arms use the identical device path and differ ONLY in MIRROR_SIGN, so any
# device-side artifact is common-mode and cannot manufacture a SIGN REVERSAL between arms.
cd /home/jba/Code/SoftBox
OUT=/home/jba/Code/SoftBox/RUN_LOGS/motor_audit/campaigns_2026-08/TWIRL_SKEW15
mkdir -p "$OUT"; R=$OUT/REPORT.txt
say(){ echo "[$(date '+%m-%d %H:%M')] $*" | tee -a $R; }
DENS=500; TARGET=4.0; MX=10.0; MY=2.0; FILX=2.5; STEPS=6000000; DT=1.25e-6
nvidia-smi -L >/dev/null 2>&1 || { say "ABORT: GPU not on the bus at launch."; exit 1; }
say "TWIRL SKEW +/-15 deg (GPU) — d$DENS, target ${TARGET} um, lawn ${MX}x${MY}, dt $DT, cap $STEPS steps"
say "  eps=+15 x3 vs eps=-15 x3 (stroke skew), NATIVE every4 lattice, ~30 h"
one(){ # arm seed extraflag
  local tag=$1_s$2 rf=""
  [ -f "$OUT/$tag/checkpoint.bin" ] && rf="-resume" && say "  $tag: resuming from checkpoint"
  ./scripts/run_gpu_monitored.sh ./scripts/run_site_normal_glide_gpu.sh -run -gpu -devicecull -nohires -noviz \
     -density $DENS -matx $MX -maty $MY -seed $2 -filx $FILX -eta 0.10 -dt $DT -steps $STEPS \
     -target $TARGET $3 $rf -out $OUT/$tag >> $OUT/$tag.log 2>&1
}
# MATCHED PAIRS, one batch at a time. 6-concurrent oversaturates the device (99% util, 40 steps/s/run,
# 240 aggregate); 3-concurrent peaked at 385 aggregate. Pairs give a COMPLETE reversal test per batch,
# so the first native-vs-mirror comparison lands after ~1/3 of the campaign rather than at the end.
for S in 20260901 20260902 20260903; do
  nvidia-smi -L >/dev/null 2>&1 || { say "ABORT before seed $S: GPU off the bus. Completed pairs are intact; rerun to resume."; exit 2; }
  say "  pair seed $S starting"
  one pos $S "-stroke-skew 15"  &
  one neg $S "-stroke-skew -15" &
  wait
  say "  pair seed $S done"
  python3 scripts/twirl_skew_analyse.py "$OUT" skew 2>&1 | tee -a $R
done
say "runs finished"
python3 scripts/twirl_skew_analyse.py "$OUT" skew 2>&1 | tee -a $R
say "DONE."
