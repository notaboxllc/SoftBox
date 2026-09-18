#!/usr/bin/env bash
# TRIAD CONFORMITY TEST (jba, 2026-09-17): "shouldn't the triad base connections have a zero strain state
# regardless? Can we do that and test whether it's equivalent to REMOVING the triad?"
#
# THE DEFECT. The actin-side triad contacts are placed ON the filament cylinder (an ARC at radius Ractin);
# the head-side anchors were a FLAT triangle in the head's transverse plane. Not congruent => the bond had NO
# attainable zero-energy state. -triad-zerostrain measures it: with the head sitting EXACTLY on-site the three
# zero-rest springs read 1.0725 nm of summed extension and a NON-ZERO roll torque, of the same sign as the
# drift we are chasing. -triad-conform lays the head-side vertices on the same cylinder => exactly 0 on both.
#
# THE TEST. alpha=60 is the arm where the roll is resolved (-9.53 turns/s, z=-3.07). Three outcomes:
#   conform rolls like ap60 (~-9.5)   => the frustration is NOT the cause; look elsewhere.
#   conform rolls like notriad (~-1.3) => the roll WAS the built-in frustration, and we keep the triad.
#   conform lands between              => frustration is one contributor among several.
# NOTE the null is NOT "conform == notriad in every respect": the conforming triad must STILL resist relative
# rotation (that is its whole purpose). Only the roll DRIFT is expected to go.
#
# regress: the SAME config as ALPHA_LONG/ap60 with conform OFF, short. Its trajectory rows must match ap60's
# byte-for-byte -- the default path must be unchanged by this edit (the -dtheta inert-flag lesson, inverted).
set -u
OUT=${OUT:-RUN_LOGS/motor_audit/campaigns_2026-09/TRIAD_CONFORM}
mkdir -p "$OUT"
BASE="-run -gpu -devicecull -nohires -noviz -density 200 -matx 10.0 -maty 2.0 -filx 2.5 -eta 0.01 -dt 1.25e-7 -target 9.0 -filsegs 1 -triad -convaz-nocomp -seed 20260901 -convaz 60"
export SOFTBOX_XMX=${SOFTBOX_XMX:-4G}
run(){ rm -rf "$OUT/$1"; ./scripts/run_gpu_monitored.sh ./scripts/run_site_normal_glide_gpu.sh \
       $BASE -steps $2 $3 -out "$OUT/$1" > "$OUT/$1.log" 2>&1; echo "done $1"; }
run "regress" 20000   ""                &
run "conform" 8000000 "-triad-conform"  &
wait
echo "=== TRIAD_CONFORM complete ==="
