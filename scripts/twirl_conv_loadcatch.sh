#!/bin/bash
# CONVERTER SKEW + LOAD-GATED S2 CATCH-STIFFENING (factor x10, F0 = 12 pN) — does the channel open at all?
#
# THE CHAIN THAT LED HERE.
#  * TWIRL_CONV_SKEW: converter channel NULL at matched conditions, odd +0.324 +- 0.339 (1.0 sigma).
#  * Proposed cause (§7.3.2): SERIES COMPLIANCE -- the skewed swing pushes the head circumferentially and the
#    reaction runs back through a laterally soft S2 (~0.037 pN/nm) rather than turning the filament (~1 pN/nm).
#  * First probe (-s2-catch 20, BINDING-gated) BROKE THE MOTOR: gliding REVERSED (travel -0.20..-0.41 um),
#    avgBound 0.79-0.86 vs 0.99. Preserved at /tmp/CONV_S2CATCH_X20_motorbroken_*. Uninterpretable.
#  * Second probe (-s2-loadcatch, LOAD-gated) is physically motivated -- Scholz 2005 Biophys J 88:360 MEASURED
#    a 10x extension/compression stiffness asymmetry in a myosin tether, localised OUTSIDE the head -- and it
#    passes all five validation gates INCLUDING forward gliding, which the binding gate failed.
#
# WHY F0 = 12 pN. The F0 sweep (x10 factor, 300k steps, matched seed) is monotonic with NO clean window:
#      F0 (pN):   base    2      5      8     12
#      v/base:    1.00   0.21   0.39   0.40   0.59
#   Velocity recovers only in proportion to how little stiffening is applied. F0 = 12 pN (the force cap, so
#   only strongly loaded heads stiffen) is the MILDEST gate and costs least glide -- still 41% slower.
#   This is the Exp 4E compliance trade-off for the THIRD time: transmission gain and glide loss share one beam.
#
# WHAT THIS RUN CAN AND CANNOT SAY.
#   CAN: is there a DISCERNIBLE chiral twirl at all? The eps-odd estimator (pos-neg)/2 is a valid chirality
#        test regardless of the velocity penalty -- a 1/v artifact inflates turns/um but cannot manufacture a
#        SIGN REVERSAL out of nothing.
#   CANNOT: compare the MAGNITUDE to epsStroke. turns/um has travel in the denominator, and this gate glides
#        1.7x slower, so any apparent gain is partly the 1/v inflation that has already caught this project
#        twice (low-ATP; randbase). A magnitude claim REQUIRES epsStroke run under the IDENTICAL gate as a
#        control. THAT IS NOT RUN HERE -- do not quote a ratio against the ungated epsStroke number.
#
# Matched to TWIRL_CONV_SKEW otherwise: aligned base, eta 0.10, dt 1.25e-6, d500, target 1.2 um, linear ramp,
# same 3 seeds. ~5 h/arm at the reduced speed => ~10 h.
cd /home/jba/Code/SoftBox
OUT=/home/jba/Code/SoftBox/RUN_LOGS/motor_audit/campaigns_2026-09/CONV_LOADCATCH_F12
mkdir -p "$OUT"; R=$OUT/REPORT.txt
say(){ echo "[$(date '+%m-%d %H:%M')] $*" | tee -a $R; }
DENS=500; TARGET=1.2; MX=10.0; MY=2.0; FILX=2.5; STEPS=6000000; DT=1.25e-6; ETA=0.10; FAC=10; F0=12
SEEDS="20260901 20260902 20260903"
nvidia-smi -L >/dev/null 2>&1 || { say "ABORT: GPU not on the bus at launch."; exit 1; }
say "CONVERTER SKEW +/-15 with LOAD-GATED S2 catch (x$FAC ramped over |F8| 0..$F0 pN)"
say "  6 runs, 3-concurrent, ~10 h. Baseline (no gate) conv odd = +0.324 +- 0.339 (1.0 sigma)."
say "  Screen for a DISCERNIBLE reversal only; magnitude vs epsStroke needs the same gate on epsStroke (not run)."
one(){ local tag=$1_s$2 rf=""
  [ -f "$OUT/$tag/checkpoint.bin" ] && rf="-resume" && say "    $tag: resuming"
  ./scripts/run_gpu_monitored.sh ./scripts/run_site_normal_glide_gpu.sh -run -gpu -devicecull -nohires -noviz \
     -density $DENS -matx $MX -maty $MY -seed $2 -filx $FILX -eta $ETA -dt $DT -steps $STEPS \
     -target $TARGET -conv-skew $3 -s2-loadcatch $FAC -s2-loadf0 $F0 $rf -out $OUT/$tag >> $OUT/$1_s$2.log 2>&1; }
for ARM in "pos 15" "neg -15"; do
  set -- $ARM
  nvidia-smi -L >/dev/null 2>&1 || { say "ABORT before $1: GPU off the bus."; exit 2; }
  say "  arm $1 (conv-skew=$2) starting — 3 seeds concurrent"
  for S in $SEEDS; do one $1 $S $2 & done
  wait
  say "  arm $1 done"
done
say "runs finished"
python3 scripts/twirl_skew_analyse.py "$OUT" skew 2>&1 | tee -a $R
say "DONE."
