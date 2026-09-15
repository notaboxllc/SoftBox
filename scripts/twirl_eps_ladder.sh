#!/bin/bash
# STROKE-SKEW EPSILON LADDER at the canonical viscosity (eta = 0.10 Pa.s), GPU device-resident.
#
# WHY. The TWIRL_SKEW15 campaign resolved the eps-odd twirl at 23.3 sigma from n=3 seeds, so more seeds at
# eps=15 buy nothing. The open question is the SHAPE of the eps-response, and there is direct evidence it may
# not be linear: on the CONVERTER-skew channel the per-stroke angular impulse scaled as sin(eps) but the
# population-mean twirl grew SUB-SIN (1.20x / 1.84x observed vs 2.97x / 5.74x predicted for 15/30 deg --
# DISCRETE_ACTIN_SITE_CHIRAL_BINDING_AND_STROKE_FINDINGS.md 22.4, "Outcome B"). If the STROKE-skew channel
# does the same, you cannot read the eps that reproduces a given pitch off the single eps=15 point.
#
# This ladder also tests the unexplained eps-EVEN residue (-0.865 +- 0.172 turns/um, 5.0 sigma, absent at
# eps=0). A genuine second-order term must fall as eps^2 => at eps=5 it should drop ~9x to about -0.10, below
# noise. If it does not, it is not second-order.
#
# DESIGN. eps in {5,10,15} x both signs x 3 matched seeds = 18 runs, ALL ON GPU so the ladder is
# runner-consistent (the existing eps=15 anchors are a CPU/GPU mix; the eps=0 control in TWIRL_REPRO_GPU is
# GPU). Reversing eps at FIXED chirality is the correct axis -- eps enters as bindAzim += MIRROR_SIGN*eps and
# is therefore mirror-COUPLED (SiteNormalLongGlideHarness STROKE_SKEW_DEG javadoc); the eps-odd difference
# cancels the achiral rotational diffusion that dominates the eps=0 null.
#
# DEVICE CAVEAT, and what mitigates it. run_site_normal_glide_gpu.sh's whole-step CPU/GPU gate is not green on
# the BOUND branch. Every arm here uses the IDENTICAL device path and differs only in the eps value, so a
# device-side artifact is common-mode across signs and cannot manufacture a sign reversal at any eps. It could
# in principle distort the ladder SHAPE if it interacted with eps -- so note that seeds 20260902/20260903 at
# eps=15 already exist on the CPU runner (TWIRL_SKEW15), giving this ladder a free CPU/GPU cross-check at its
# top point. Compare before trusting the shape.
#
# COST. target 1.2 um at ~0.72 um/s = 1.67 s = ~1.33M steps/run. 3 concurrent is the measured device sweet
# spot (385 steps/s aggregate; 6 concurrent oversaturates to 240). Batch = one eps, one sign, 3 seeds =>
# 6 batches x ~2.9 h = ~17 h. eps order 5,10,15 puts the NEW information first: a crash after two rungs still
# leaves a runner-consistent 0/5/10 ladder.
#
# Resumable: every run writes checkpoint.bin and is relaunched with -resume if one is present.
cd /home/jba/Code/SoftBox
OUT=/home/jba/Code/SoftBox/RUN_LOGS/motor_audit/campaigns_2026-08/TWIRL_EPS_LADDER
mkdir -p "$OUT"; R=$OUT/REPORT.txt
say(){ echo "[$(date '+%m-%d %H:%M')] $*" | tee -a $R; }
DENS=500; TARGET=1.2; MX=10.0; MY=2.0; FILX=2.5; STEPS=6000000; DT=1.25e-6; ETA=0.10
SEEDS="20260901 20260902 20260903"
nvidia-smi -L >/dev/null 2>&1 || { say "ABORT: GPU not on the bus at launch."; exit 1; }
say "STROKE-SKEW EPS LADDER (GPU) — eps 5/10/15 x +/- x 3 seeds, d$DENS, eta $ETA, target ${TARGET} um, dt $DT"
say "  18 runs, 3-concurrent, ~17 h. NATIVE every4 lattice, mirror=+1 throughout."
one(){ # epsdir signedeps seed
  local tag=$1/$2_s$3 rf=""
  [ -f "$OUT/$tag/checkpoint.bin" ] && rf="-resume" && say "    $tag: resuming from checkpoint"
  ./scripts/run_gpu_monitored.sh ./scripts/run_site_normal_glide_gpu.sh -run -gpu -devicecull -nohires -noviz \
     -density $DENS -matx $MX -maty $MY -seed $3 -filx $FILX -eta $ETA -dt $DT -steps $STEPS \
     -target $TARGET -stroke-skew $4 $rf -out $OUT/$tag >> $OUT/$1_$2_s$3.log 2>&1
}
for E in 5 10 15; do
  D=e$(printf %02d $E); mkdir -p $OUT/$D
  for SIGN in pos neg; do
    [ $SIGN = pos ] && EPS=$E || EPS=-$E
    nvidia-smi -L >/dev/null 2>&1 || { say "ABORT before $D/$SIGN: GPU off the bus. Completed rungs intact; rerun to resume."; exit 2; }
    say "  $D $SIGN (eps=$EPS) starting — 3 seeds concurrent"
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
