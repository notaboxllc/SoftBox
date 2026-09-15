#!/bin/bash
# TWIRL REPRODUCTION — the experimental observation, not a statistic.
#
# TARGET: an actin filament gliding several um on a myosin lawn accumulates SEVERAL VISIBLE TURNS about its
# own axis (experimentally ~1 revolution per um), and the accumulation REVERSES when the actin helix is
# mirrored. theta(t) is logged ~360 times per run, so persistence is READ OFF THE TRACE, not inferred.
#
# Canonical physics throughout: roll Brownian ON (an ablation would destroy a rectifying mechanism by
# construction and perturbs recruitment 1.21x -- see ROLL_ABLATION). The ONLY difference between arms is
# the lattice handedness (MIRROR_SIGN: chiTwist and the two-start partner phase both flip).
#
# d1000: Campaign 2's coherent regime, v~1.34 um/s, avgBound~1.9, ~0.61 turns/um, D~0.165 turns^2/s.
#   expected signal over 6 um : ~3.7 turns        diffusive noise over ~4.5 s : ~1.2 turns   => ~3x per run
cd /home/jba/Code/SoftBox
OUT=/home/jba/Code/SoftBox/RUN_LOGS/motor_audit/campaigns_2026-08/TWIRL_REPRO
mkdir -p "$OUT"; R=$OUT/REPORT.txt
say(){ echo "[$(date '+%m-%d %H:%M')] $*" | tee -a $R; }
DENS=500; TARGET=4.5; MX=18.0; MY=0.5; STEPS=6000000; DT=1.25e-6; W=2
say "TWIRL REPRODUCTION — d$DENS, travel target ${TARGET} um, lawn ${MX}x${MY} um, dt $DT, cap $STEPS steps"
say "  GPU is OFF THE BUS (Xid 79, needs a reboot nobody can power-cycle) => CPU, 6 concurrent x $W workers, ~62 h"
say "  arms: NATIVE x3  vs  MIRROR x3 (-mirror: helical handedness reversed)"
say "  roll Brownian ON (canonical). theta(t) logged every 10k steps."
for S in 20260901 20260902 20260903; do
  ./scripts/run_site_normal_long_glide.sh -run -nohires -noviz -density $DENS -seed $S -filx 0.0 \
     -matx $MX -maty $MY -target $TARGET -eta 0.10 -dt $DT -steps $STEPS -workers $W \
     -out $OUT/nat_s$S > $OUT/nat_s$S.log 2>&1 &
  ./scripts/run_site_normal_long_glide.sh -run -nohires -noviz -density $DENS -seed $S -filx 0.0 \
     -matx $MX -maty $MY -target $TARGET -eta 0.10 -dt $DT -steps $STEPS -workers $W -mirror \
     -out $OUT/mir_s$S > $OUT/mir_s$S.log 2>&1 &
done
wait
say "runs complete"
python3 scripts/twirl_repro_analyse.py "$OUT" 2>&1 | tee -a $R
say "DONE."
