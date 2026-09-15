#!/bin/bash
# CONVERTER-SKEW arm on the NEW lattice — the missing cell of the 2x2 that reconciles the twirl history.
#
# THE QUESTION. The stroke-skew ladder resolves the eps-odd twirl at 23 sigma (-4.0 turns/um at eps=15) on the
# every4+partner lattice with site-normal binding. The closest historical comparator -- CONVERTER skew, +/-15 deg,
# linear ramp, at the SAME canonical eta=0.10 -- was UNRESOLVED at 1.06 sigma with n=24 on the OLD every3 lattice
# (VISCOSITY_SENSITIVITY_FINDINGS.md 12b; turns/um -0.219). Four things differ between those two results: the
# chirality MECHANISM, the LATTICE, the binding law, and the assay (20 ms / ~0.06 um of travel vs ~2 s / 1.2 um,
# plus the DISCRETE_ACTIN 20.4 roll-observable fix). This run changes exactly ONE of them.
#
# THE DESIGN. Identical harness, lattice, binding law, eta, dt, filament, density, travel target, runner and seeds
# as TWIRL_EPS_LADDER. The ONLY difference from that campaign's eps=15 rung is the chirality channel:
# -conv-skew 15 (motor-side converter stroke-plane rotation, actin site untouched) instead of -stroke-skew 15
# (actin-side one-shot interface step on an ALREADY-BOUND head). That is a genuine single-factor contrast, which
# the historical comparison was not.
#
# LINEAR RAMP is deliberate: it matches the eta-map arm this is meant to compare against (CLAUDE.md records that
# campaign as "+/-15 deg converter skew, linear ramp"). The harness sets CONV_RAMP=RAMP_LINEAR whenever
# -conv-skew is non-zero; -conv-skew 0 stays an exact byte-identical no-op.
#
# WHAT EACH OUTCOME MEANS.
#   resolved, ~stroke-skew magnitude -> the mechanism is NOT the discriminator; the lattice + binding law + the
#       longer travel-based assay explain the whole history, and the old converter null was a measurement problem.
#   resolved but well below 23 sigma -> BOTH matter: the assay fix explains the cleanliness, the mechanism the
#       magnitude. This is the outcome the 20.13 "zero-rest spring carries no preferred direction" account predicts,
#       since 21.10 established the converter strain IS regenerated at each stroke (unlike epsBind).
#   still ~1 sigma -> the mechanism carries essentially all of it.
#
# PRE-QUEUE VALIDATION (2026-08-28, recorded because an inert flag would have produced a guaranteed false null):
# the first smoke comparison came back IDENTICAL, but that scene had captures=0 / avgBound=0 -- converter skew acts
# only on BOUND heads, so identical was correct there and uninformative. On a binding scene (d500, 20k steps,
# matched seed) conv-skew 15 vs no flag DIVERGE from the first logged step (avgB 0.821 vs 0.446, rollTurns -0.034
# vs -0.026) => convFrame is wired and active. -conv-skew 0 is byte-identical to no flag.
cd /home/jba/Code/SoftBox
OUT=/home/jba/Code/SoftBox/RUN_LOGS/motor_audit/campaigns_2026-08/TWIRL_CONV_SKEW
mkdir -p "$OUT"; R=$OUT/REPORT.txt
say(){ echo "[$(date '+%m-%d %H:%M')] $*" | tee -a $R; }
DENS=500; TARGET=1.2; MX=10.0; MY=2.0; FILX=2.5; STEPS=6000000; DT=1.25e-6; ETA=0.10
SEEDS="20260901 20260902 20260903"
nvidia-smi -L >/dev/null 2>&1 || { say "ABORT: GPU not on the bus at launch."; exit 1; }
say "CONVERTER SKEW +/-15 (GPU, LINEAR ramp) — d$DENS, eta $ETA, target ${TARGET} um, dt $DT"
say "  6 runs, 3-concurrent, ~8 h. Matched to TWIRL_EPS_LADDER e15 in every respect but the chirality channel."
one(){ # sign seed signedeps
  local tag=$1_s$2 rf=""
  [ -f "$OUT/$tag/checkpoint.bin" ] && rf="-resume" && say "    $tag: resuming from checkpoint"
  ./scripts/run_gpu_monitored.sh ./scripts/run_site_normal_glide_gpu.sh -run -gpu -devicecull -nohires -noviz \
     -density $DENS -matx $MX -maty $MY -seed $2 -filx $FILX -eta $ETA -dt $DT -steps $STEPS \
     -target $TARGET -conv-skew $3 $rf -out $OUT/$tag >> $OUT/$1_s$2.log 2>&1
}
for SIGN in pos neg; do
  [ $SIGN = pos ] && EPS=15 || EPS=-15
  nvidia-smi -L >/dev/null 2>&1 || { say "ABORT before $SIGN: GPU off the bus. Completed arms intact; rerun to resume."; exit 2; }
  say "  $SIGN (conv-skew=$EPS) starting — 3 seeds concurrent"
  for S in $SEEDS; do one $SIGN $S $EPS & done
  wait
  say "  $SIGN done"
done
say "runs finished"
python3 scripts/twirl_skew_analyse.py "$OUT" skew 2>&1 | tee -a $R
say "DONE."
