#!/usr/bin/env bash
# CHARACTERIZE the bound-head geometry on TWO axes that the first sweep conflated.
#
#   -convaz b : rotate the CONVERTER to a different material point ON the spherical head. rF8 untouched
#               (|rF8|=3.5nm => head centre stays 3.5nm from the contact => SAME DEPTH, same steric relation);
#               |rConv| held at 3.5nm => gammaPsi unchanged; converter arm 7*cos(b/2) compensated by
#               dtheta x 1/cos(b/2) so a shorter arm cannot masquerade as a velocity change.
#               Converter axial offset = 3.5*sin(b) nm. THIS IS THE CLEAN AXIS.
#   -headtilt b: tilts the whole head. Reaches 7.0*sin(b) nm axial -- TWICE the reach -- but SINKS the head
#               (centre radius 3.5+3.5cos(b): 7.00 -> 5.25 at -60). With NO excluded-volume force and the g6
#               steric gate RETIRED, the model will happily reward a pose the head could not occupy. Kept as
#               the deliberate CONTRAST, not as the primary axis.
#
# A 10k-step bite test showed avgBound falling 0.75 -> 0.39/0.46 at BOTH convaz +/-90 -- a SYMMETRIC (even in b)
# engagement cost that cannot be the axial displacement (odd in b). So the analysis decomposes v(b) into even and
# odd parts: the ODD part is the converter-displacement effect, the EVEN part is the reangling side-effect.
# Both signs are therefore run at every magnitude -- the decomposition requires it.
set -u
OUT=${OUT:-RUN_LOGS/motor_audit/campaigns_2026-09/HEADTILT_SWEEP}
mkdir -p "$OUT"
BASE="-run -gpu -devicecull -nohires -noviz -density 500 -matx 10.0 -maty 2.0 -filx 2.5 -eta 0.10 -dt 1.25e-6 -steps 200000 -target 9.0 -filsegs 1 -triad"
run(){ rm -rf "$OUT/$1"; ./scripts/run_gpu_monitored.sh ./scripts/run_site_normal_glide_gpu.sh $BASE -seed $3 $2 -out "$OUT/$1" > "$OUT/$1.log" 2>&1; }
for S in 20260901 20260902 20260903; do
  # --- PRIMARY: converter azimuth at FIXED head depth (both signs, for the even/odd split) ---
  run "cazp15_$S" "-convaz 15"   $S & run "cazm15_$S" "-convaz -15"  $S & wait
  run "cazp30_$S" "-convaz 30"   $S & run "cazm30_$S" "-convaz -30"  $S & wait
  run "cazp45_$S" "-convaz 45"   $S & run "cazm45_$S" "-convaz -45"  $S & wait
  run "cazp60_$S" "-convaz 60"   $S & run "cazm60_$S" "-convaz -60"  $S & wait
  run "cazp90_$S" "-convaz 90"   $S & run "cazm90_$S" "-convaz -90"  $S & wait
  # --- CONTRAST: the tilt axis, wide points only (the -30..+30 grid is already measured) ---
  run "tm60_$S" "-headtilt -60"  $S & run "tm45_$S" "-headtilt -45"  $S & wait
  run "tp45_$S" "-headtilt 45"   $S & run "tp60_$S" "-headtilt 60"   $S & wait
  echo "--- seed $S done ---"; python3 scripts/headtilt_curve.py
done
