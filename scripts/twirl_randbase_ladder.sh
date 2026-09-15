#!/bin/bash
# RANDOMISED-BASE stroke-skew ladder — the strongest alternative explanation for the 13.2 sigma headline.
#
# THE QUESTION. Every twirl run to date (TWIRL_SKEW15, TWIRL_EPS_LADDER, TWIRL_CONV_SKEW) had
# `rand_base_azimuth: false` — every motor shares one base azimuth, so concentrating the accepted MATERIAL
# azimuth also concentrates it around a common LABORATORY direction. A real gliding assay has myosin randomly
# oriented on nitrocellulose. This is the Stage-A "shared motor base frame" confound, and history says it is
# where comparable signals die: in the actin-side channels randomising the base took the eps-odd torque from
# resolved to 0.3 sigma and 0.6 sigma (DISCRETE_ACTIN_SITE 13), and in the converter channel from -6.5 shared
# to -1.80 +- 3.0 (0.61 sigma) randomised (22.5). The stroke-skew channel has NEVER been tested this way.
#
# THE SEEDING HAZARD, and the fix. RAND_BASE_SEED defaults to a FIXED 20260724 and the per-motor base hash
# (ExplicitCompleteMatHarness:768) keys on (motor index, RAND_BASE_SEED) ONLY — NOT the run seed. Left alone,
# all three "matched seeds" would share ONE lawn realisation: n=1 in the geometry that matters, with an SEM
# computed as if n=3. This script therefore passes `-randbase-seed <run seed>`, giving three INDEPENDENT lawns
# while keeping the lawn IDENTICAL within each +/-eps pair, so the eps-odd estimator stays matched.
#
# ORDER: eps 15 -> 10 -> 5. The decisive rung is 15 (the 13.2 sigma headline); it lands first, in ~8 h. If the
# signal dies there, the lower rungs have nothing to ladder and the campaign can be stopped early.
#
# Everything else is identical to TWIRL_EPS_LADDER: same lattice, binding law, eta, dt, filament, density,
# travel target, runner, seeds, 3-concurrent batching.
cd /home/jba/Code/SoftBox
OUT=/home/jba/Code/SoftBox/RUN_LOGS/motor_audit/campaigns_2026-08/TWIRL_RANDBASE_LADDER
mkdir -p "$OUT"; R=$OUT/REPORT.txt
say(){ echo "[$(date '+%m-%d %H:%M')] $*" | tee -a $R; }
DENS=500; TARGET=1.2; MX=10.0; MY=2.0; FILX=2.5; STEPS=6000000; DT=1.25e-6; ETA=0.10
SEEDS="20260901 20260902 20260903"
nvidia-smi -L >/dev/null 2>&1 || { say "ABORT: GPU not on the bus at launch."; exit 1; }
say "RANDOMISED-BASE stroke-skew ladder (GPU) — eps 15/10/5 x +/- x 3 seeds, d$DENS, eta $ETA, target ${TARGET} um"
say "  -randbase ON, -randbase-seed = run seed (independent lawns; matched within each +/- pair)"
say "  18 runs, 3-concurrent, ~25 h. Decisive eps=15 rung reports first (~8 h)."
one(){ # epsdir sign seed signedeps
  local tag=$1/$2_s$3 rf=""
  [ -f "$OUT/$tag/checkpoint.bin" ] && rf="-resume" && say "    $tag: resuming from checkpoint"
  ./scripts/run_gpu_monitored.sh ./scripts/run_site_normal_glide_gpu.sh -run -gpu -devicecull -nohires -noviz \
     -density $DENS -matx $MX -maty $MY -seed $3 -filx $FILX -eta $ETA -dt $DT -steps $STEPS \
     -target $TARGET -stroke-skew $4 -randbase -randbase-seed $3 $rf -out $OUT/$tag >> $OUT/$1_$2_s$3.log 2>&1
}
for E in 15 10 5; do
  D=e$(printf %02d $E); mkdir -p $OUT/$D
  for SIGN in pos neg; do
    [ $SIGN = pos ] && EPS=$E || EPS=-$E
    nvidia-smi -L >/dev/null 2>&1 || { say "ABORT before $D/$SIGN: GPU off the bus. Completed rungs intact; rerun to resume."; exit 2; }
    say "  $D $SIGN (eps=$EPS, randbase) starting — 3 seeds concurrent"
    for S in $SEEDS; do one $D $SIGN $S $EPS & done
    wait
    say "  $D $SIGN done"
  done
  say "  --- rung $D complete ---"
  python3 scripts/twirl_skew_analyse.py "$OUT/$D" skew 2>&1 | tee -a $R
  python3 scripts/twirl_ladder_analyse.py "$OUT" 2>&1 | tee -a $R
done
say "all rungs finished"
python3 scripts/twirl_ladder_analyse.py "$OUT" 2>&1 | tee -a $R
say "DONE."
