#!/bin/bash
# CONVERTER SKEW + SPECULATIVE S2 CATCH-STIFFENING (x20 on BOUND motors only).
#
# THE QUESTION. TWIRL_CONV_SKEW measured the converter channel NULL at matched conditions:
# eps-odd +0.324 +- 0.339 turns/um (1.0 sigma, 2-sigma bound < 1.00), against epsStroke's -4.043 +- 0.305.
# The proposed explanation (§7.3.2) is SERIES COMPLIANCE: the skewed swing pushes the head circumferentially and
# the reaction runs back through a laterally SOFT S2 (~0.037 pN/nm at our L=40 nm) instead of turning the
# filament, which is stiff-bonded at ~1 pN/nm (F8). Parity needs ~25-30x. This tests that explanation directly.
#
# WHAT IS ASSUMED, AND IT IS NOT JUSTIFIED. `-s2-catch 20` multiplies the S2 BENDING stiffness by 20 for BOUND
# motors only. No measurement of a state-dependent S2 stiffness is known to us. It ASSUMES an unknown
# biochemical effect at binding. This is a "what would the motor need" probe, NOT a proposed model change, and
# no result from it may be quoted as a property of the motor.
#
# WHY BOUND-ONLY (this is the load-bearing design choice). Free motors keep the canonical soft S2, so the
# DIFFUSIVE SEARCH IS UNTOUCHED. That matters because Exp 4E established the trade-off this probe is built on:
# recruitment and stroke transmission share ONE compliance, a recruitment-optimal free tail collapses k_ext by
# 98%, and "no PASSIVE tail both recruits well AND preserves mechanics -- what would work (not built): a
# state-dependent catch-STIFFENING tail". This is that untested prescription made concrete.
#
# THREE THINGS TO CHECK BESIDES THE TWIRL NUMBER:
#   1. invalid / solverFail MUST stay 0. A 20x stiffer beam is a stiffer Newton solve; if it destabilises,
#      that BOUNDS the probe rather than answering it.
#   2. avgBound must be UNCHANGED vs the canonical converter run (~0.99). Only bound motors stiffen, so
#      recruitment should not move. If it does, the gate is leaking into the search and the comparison is not
#      single-factor.
#   3. If this DOES produce twirl, the differential control is to run epsStroke with the same catch: epsStroke
#      transmits directly across F8, NOT through S2, so it should be RELATIVELY UNAFFECTED. If both jump
#      equally, series compliance is not the mechanism and §7.3.2 is wrong.
#
# Matched to TWIRL_CONV_SKEW in every other respect: aligned base, eta 0.10, dt 1.25e-6, d500, target 1.2 um,
# linear converter ramp, same 3 seeds. The ONLY difference is -s2-catch 20.
cd /home/jba/Code/SoftBox
OUT=/home/jba/Code/SoftBox/RUN_LOGS/motor_audit/campaigns_2026-09/CONV_S2CATCH_X20
mkdir -p "$OUT"; R=$OUT/REPORT.txt
say(){ echo "[$(date '+%m-%d %H:%M')] $*" | tee -a $R; }
DENS=500; TARGET=1.2; MX=10.0; MY=2.0; FILX=2.5; STEPS=6000000; DT=1.25e-6; ETA=0.10; CATCH=20
SEEDS="20260901 20260902 20260903"
nvidia-smi -L >/dev/null 2>&1 || { say "ABORT: GPU not on the bus at launch."; exit 1; }
say "CONVERTER SKEW +/-15 with S2 CATCH x$CATCH (bound-only) — SPECULATIVE probe of the series-compliance null"
say "  6 runs, 3-concurrent, ~8 h. Compare against TWIRL_CONV_SKEW odd = +0.324 +- 0.339 (1.0 sigma)."
one(){ local tag=$1_s$2 rf=""
  [ -f "$OUT/$tag/checkpoint.bin" ] && rf="-resume" && say "    $tag: resuming"
  ./scripts/run_gpu_monitored.sh ./scripts/run_site_normal_glide_gpu.sh -run -gpu -devicecull -nohires -noviz \
     -density $DENS -matx $MX -maty $MY -seed $2 -filx $FILX -eta $ETA -dt $DT -steps $STEPS \
     -target $TARGET -conv-skew $3 -s2-catch $CATCH $rf -out $OUT/$tag >> $OUT/$1_s$2.log 2>&1; }
for ARM in "pos 15" "neg -15"; do
  set -- $ARM
  nvidia-smi -L >/dev/null 2>&1 || { say "ABORT before $1: GPU off the bus."; exit 2; }
  say "  arm $1 (conv-skew=$2, s2-catch x$CATCH) starting — 3 seeds concurrent"
  for S in $SEEDS; do one $1 $S $2 & done
  wait
  say "  arm $1 done"
done
say "runs finished"
python3 scripts/twirl_skew_analyse.py "$OUT" skew 2>&1 | tee -a $R
say "DONE."
