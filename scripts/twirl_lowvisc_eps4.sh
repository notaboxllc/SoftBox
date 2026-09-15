#!/bin/bash
# LOW-VISCOSITY STROKE SKEW at eps = 4 deg, 1 um travel — CPU, parked. Follow-on to LOWVISC_STROKE_CPU.
#
# WHY eps = 4 AND WHY 1 um (both jba's call, and both are well-motivated):
#  * eps=4 is where Klebl 2025's MEASURED azimuthal lever displacement sits, and with the low-viscosity slope
#    measured by the eps=15 pilot (k = 0.537 turns/um/deg) it PREDICTS pitch 0.465 um against the measured
#    myosin-II value 0.47 +- 0.19 um. This is therefore a TEST, not a demonstration -- report what comes out.
#  * 1 um because signal grows ~ travel while the diffusive roll grows ~ sqrt(t), so SNR improves as
#    sqrt(travel): 0.3 -> 1.0 um is a 1.83x gain. At eta=0.01 the roll drag is 10x lower and D_roll = 1270
#    rad^2/s, so a single arm accumulates ~5.9 turns of PURE DIFFUSION over a 1 um run -- against a predicted
#    2.15 turns of signal. Raw SNR is 0.37; the measurement only works because all arms share seed AND
#    randbase-seed, making most of that roll COMMON-MODE and cancelling it in (pos-neg)/2.
#
# EXPECT ~2 SIGMA AT n=1, NOT A DECISIVE RESULT. Extrapolating the eps=15 pilot's non-cancelling residual
# (~0.4 turns at 0.3 um) as sqrt(t) gives ~1.0 turns at 1 um against a 2.15-turn signal. If the outcome is
# promising it should be EXTENDED WITH MORE SEEDS, not quoted as-is.
#
# THE eps=0 ARM IS RETAINED deliberately: at eta=0.01 the achiral/diffusive roll behaviour is not
# characterised, and this arm measures that floor empirically rather than by the analytic estimate above.
#
# dt = 1.25e-7 (MANDATORY: dt = dt0*eta/eta0). Randomised base. Same seed/lawn across arms.
cd /home/jba/Code/SoftBox
OUT=/home/jba/Code/SoftBox/RUN_LOGS/motor_audit/campaigns_2026-09/LOWVISC_EPS4_CPU
mkdir -p "$OUT"; R=$OUT/REPORT.txt
say(){ echo "[$(date '+%m-%d %H:%M')] $*" | tee -a $R; }
ETA=0.01; DT=1.25e-7; TARGET=1.0; STEPS=16000000
DENS=500; MX=10.0; MY=2.0; FILX=2.5
SEEDS="20260901 20260902 20260903"
# THREE MATCHED PAIRS instead of one pair + an eps=0 arm (jba, 2026-09-01). Memory is not the constraint --
# a CPU arm is only ~0.8 GB RSS of 31 GB -- so the six available threads are better spent on SEEDS than on
# an eps=0 control. Rationale: within a pair the shared seed+lawn makes the diffusive roll common-mode and it
# cancels in (pos-neg)/2; ACROSS pairs the residuals are independent, so the mean odd improves as 1/sqrt(n).
# n=1 -> ~2 sigma, n=3 -> ~3.5 sigma, and n=3 gives 2 dof for the SEM where n=2 would give 1 (an unreliable
# error bar). The eps=0 arm is dropped as largely redundant: the achiral part is already measured by the EVEN
# component (pos+neg)/2 -- which is how every eta=0.10 campaign did it -- and the diffusive floor also has an
# analytic value (D_roll = 1270 rad^2/s at eta=0.01). Add it back if the even component looks anomalous.
# COST: 1 worker per run instead of 2. Worker scaling is sub-linear (3w = 26.3 steps/s, 2w = 20.8), so expect
# ~14-15 steps/s => ~3.4 days for the fastest arm, ~4.7 for the slowest.
say "LOW-VISCOSITY eps=+/-4 pilot (CPU, parked) — eta $ETA, dt $DT, target ${TARGET} um, randbase"
say "  3 MATCHED PAIRS (seeds $SEEDS), 1 worker each = 6 threads"
say "  predicted odd ~2.15 turns/um -> pitch ~0.465 um vs experiment 0.47 +- 0.19. Expect ~3.5 sigma at n=3."
for S in $SEEDS; do
  for A in "pos 4" "neg -4"; do
    set -- $A
    nice -n 15 ./scripts/run_site_normal_long_glide.sh -run -nohires -noviz \
       -density $DENS -matx $MX -maty $MY -seed $S -filx $FILX -eta $ETA -dt $DT -steps $STEPS \
       -target $TARGET -stroke-skew $2 -randbase -randbase-seed $S -workers 1 \
       -out $OUT/$1_s$S >> $OUT/$1_s$S.log 2>&1 &
    say "  arm $1 seed $S (eps=$2) launched pid=$!"
  done
done
wait
say "all arms finished"
python3 scripts/twirl_skew_analyse.py "$OUT" skew 2>&1 | tee -a $R
say "DONE."
