#!/bin/bash
# LOW-VISCOSITY STROKE-SKEW PILOT — parked on CPU, deliberately OFF the GPU.
#
# PURPOSE. eta = 0.10 Pa.s is ~100x water and is the weakest link in the realism case. This parks a minimal
# three-arm probe at eta = 0.01 (the lowest viscosity CLAUDE.md certifies as trustworthy, ~10x water) using the
# ONE mechanism that has solidly produced twirling -- the STROKE SKEW -- so the GPU stays free for the A4 work.
#
# THE MANDATORY dt RULE. dt(eta) = dt0*eta/eta0 => dt = 1.25e-7 s, TEN TIMES SMALLER than the eta=0.10 runs.
# This is not optional: at eta=0.01 with the eta=0.10 timestep the explicit F8 bond is past its stability limit
# (JOURNAL 2026-08-13: 11.6 nN from one head, filament 16 um out of a nanometre-deep chamber, every value finite
# so no NaN guard fired). Faster gliding claws back ~1.7x, so a run costs ~6x the steps of an eta=0.10 run.
#
# TRAVEL TARGET 0.3 um, NOT 1.2. At 1.2 um these would take ~11 days each. 0.3 um is ~2.8 days and is
# scientifically sufficient: IF the eta^-0.97 scaling transfers, turns/um rises ~10x (to ~65), so 0.3 um would
# accumulate ~19 turns -- far more than the ~8 turns the eta=0.10 runs fit a slope to over 1.2 um. If the
# scaling does NOT transfer, that is itself the headline result and it will be visible at this travel too.
#
# ARMS (3, one seed each, matched): eps = +15 / -15 / 0.
#   The eps=0 arm is the NULL CONTROL: same viscosity, same lawn, no chirality. The eps-odd estimator is
#   (pos-neg)/2; the eps=0 arm bounds the achiral drift at this viscosity, which is NOT known -- the eta=0.10
#   even residue was -0.600 +- 0.201, and there is no reason to assume it is the same at eta=0.01.
#
# CPU BUDGET. 3 arms x 2 workers = 6 threads on 8 physical / 16 logical cores, `nice -n 15` so the GPU feeder
# threads always win. Expect the GPU campaign to slow somewhat; that is the intended trade.
#
# CAVEAT ON READING THESE. Randomised base (the physical orientation model) at eta=0.01 has NEVER been run --
# engagement and glide speed are extrapolations (v ~ eta^-0.232 -> ~0.54 um/s). If avgBound or v come out far
# from that, the 2.8-day estimate is wrong and the target may not be reached inside the step cap. Check early.
cd /home/jba/Code/SoftBox
OUT=/home/jba/Code/SoftBox/RUN_LOGS/motor_audit/campaigns_2026-09/LOWVISC_STROKE_CPU
mkdir -p "$OUT"; R=$OUT/REPORT.txt
say(){ echo "[$(date '+%m-%d %H:%M')] $*" | tee -a $R; }
SEED=20260901; ETA=0.01; DT=1.25e-7; TARGET=0.3; STEPS=12000000
DENS=500; MX=10.0; MY=2.0; FILX=2.5
say "LOW-VISCOSITY stroke-skew pilot (CPU, parked) — eta $ETA, dt $DT, target ${TARGET} um, randbase, seed $SEED"
say "  arms eps=+15 / -15 / 0 ; 2 workers each, nice 15 ; expect ~2.8 days"
for A in "pos 15" "neg -15" "zero 0"; do
  set -- $A
  nice -n 15 ./scripts/run_site_normal_long_glide.sh -run -nohires -noviz \
     -density $DENS -matx $MX -maty $MY -seed $SEED -filx $FILX -eta $ETA -dt $DT -steps $STEPS \
     -target $TARGET -stroke-skew $2 -randbase -randbase-seed $SEED -workers 2 \
     -out $OUT/$1 >> $OUT/$1.log 2>&1 &
  say "  arm $1 (eps=$2) launched pid=$!"
done
wait
say "all arms finished"
python3 scripts/twirl_skew_analyse.py "$OUT" skew 2>&1 | tee -a $R
say "DONE."
