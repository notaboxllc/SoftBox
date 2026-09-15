#!/bin/bash
# ATLAS MECHANISM A4 -- STATE-DEPENDENT REGISTRY (AXIAL CHANNEL) -- the second, mechanically INDEPENDENT
# leg of the twirl-capable mechanism class.
#
# ** THIS IS THE SECOND ATTEMPT. Read this before quoting anything. **
# The first attempt (2026-08-31/09-01, preserved at /tmp/A4_ebind_null_*) switched the rest orientation of the
# EXISTING registry, whose couple is applied about eBind = normalize(xF8 - xH). Under site-normal binding that
# is approximately the site NORMAL -- RADIAL -- so its projection on the filament axis is ~0 and it cannot
# twirl. Measured, and it is a real result worth keeping: eps=+23 arm ran to full travel at -0.262 +- 0.862
# turns/um (2/3 sign), eps=-23 at 46% travel gave the SAME sign, eps-odd +0.135 +- 0.481 (0.3 sigma) -- i.e.
# NO REVERSAL. That code path is left intact so the null stands.
# This run uses the ADDED AXIAL channel: a 1-DOF registry coordinate about uSite whose reaction on the filament
# is purely axial -- the atlas's `g_omega` in tau_u = R*g_y + g_omega. Smoke test shows the sign flip the
# bond-axis version lacked (+23 -> -0.025 turns, -23 -> +0.016 turns relative to the kOmega-only control).
#
# WHY. The class claim (docs/twirl/ACTIN_SITE_LATTICE_LITERATURE_BASIS.md §7.3) currently rests on ONE
# implemented mechanism, `epsStroke`, which twists the filament via a TANGENTIAL FORCE AT THE MOMENT ARM
# (tau_u = R*g_y). The reduced atlas (docs/TWIRLING_MECHANISM_ATLAS_FINDINGS.md) proves torque reaches actin by
# TWO independent routes, tau_u = R*g_y + g_omega, and that the second -- a DIRECT AXIAL COUPLE from a
# state-dependent registry (A4) -- was the STRONGEST candidate it tested (<J_theta> -1.018e-24, -15.9 sigma;
# roll -57.7 rad/s ~ 0.94 turns/um). A4 has NEVER been run in the full model: REG_K was held at 0 throughout.
#
# WHY IT MATTERS MORE THAN "a second example". A4's chiral element is an ORIENTATIONAL switch of 0.40 rad =
# 22.9 deg, which sits on top of Arakelian 2015's MEASURED motor-domain roll of 26 +- 9 deg. By contrast §7.4
# showed the interface TRANSLATION that `epsStroke` requires is ~0.2 deg in the one structure pair we could
# check. So A4 is the class member with the BETTER structural warrant, not merely another one.
#
# THE CAVEAT, STATED. A4 carries TWO free parameters (dOmega and kOmega) where epsStroke carries one. kOmega is
# run at the fixture-precedent stiff value 4.12e-19 N.m/rad (DISCRETE_ACTIN_SITE §12-17, alongside soft
# 2.00e-21); if A4 resolves, kOmega should be re-run at the soft value to show it is not load-bearing.
#
# ARMS (3), matched to the randbase eps=15 rung in every other respect so the two mechanisms are comparable:
#   pos   dOmega = +23 deg
#   neg   dOmega = -23 deg      -> eps-odd estimator (pos-neg)/2, cancels the achiral background
#   ctrl  dOmega =   0 deg, kOmega ON  -> THE NULL CONTROL. Registry stiffness present but no state switch.
#                                         Must be consistent with zero, or the couple is not the source.
# Randomised base (physical orientation disorder), eta 0.10, full Brownian, 3 matched seeds, target 1.2 um.
# NOTE the randbase runs glide ~0.32 um/s, so an arm is ~12 h, not ~4. Three arms ~ 36 h.
cd /home/jba/Code/SoftBox
OUT=/home/jba/Code/SoftBox/RUN_LOGS/motor_audit/campaigns_2026-08/TWIRL_REGISTRY_A4
mkdir -p "$OUT"; R=$OUT/REPORT.txt
say(){ echo "[$(date '+%m-%d %H:%M')] $*" | tee -a $R; }
DENS=500; TARGET=1.2; MX=10.0; MY=2.0; FILX=2.5; STEPS=6000000; DT=1.25e-6; ETA=0.10; KOMEGA=4.12e-19
SEEDS="20260901 20260902 20260903"
nvidia-smi -L >/dev/null 2>&1 || { say "ABORT: GPU not on the bus at launch."; exit 1; }
say "A4 STATE-DEPENDENT REGISTRY (GPU) — dOmega +/-23 deg + null control, kOmega $KOMEGA, randbase, eta $ETA"
say "  9 runs, 3-concurrent, ~36 h. Arms: pos(+23) / neg(-23) / ctrl(0, kOmega ON)."
one(){ # armdir seed dOmega
  local tag=$1_s$2 rf=""
  [ -f "$OUT/$tag/checkpoint.bin" ] && rf="-resume" && say "    $tag: resuming from checkpoint"
  ./scripts/run_gpu_monitored.sh ./scripts/run_site_normal_glide_gpu.sh -run -gpu -devicecull -nohires -noviz \
     -density $DENS -matx $MX -maty $MY -seed $2 -filx $FILX -eta $ETA -dt $DT -steps $STEPS \
     -target $TARGET -registry-k $KOMEGA -registry-switch $3 -randbase -randbase-seed $2 $rf \
     -out $OUT/$tag >> $OUT/$1_s$2.log 2>&1
}
for ARM in "pos 23" "neg -23" "ctrl 0"; do
  set -- $ARM
  nvidia-smi -L >/dev/null 2>&1 || { say "ABORT before $1: GPU off the bus. Completed arms intact; rerun to resume."; exit 2; }
  say "  arm $1 (dOmega=$2) starting — 3 seeds concurrent"
  for S in $SEEDS; do one $1 $S $2 & done
  wait
  say "  arm $1 done"
done
say "all arms finished"
python3 scripts/twirl_skew_analyse.py "$OUT" skew 2>&1 | tee -a $R
say "DONE."
