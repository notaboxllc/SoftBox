#!/usr/bin/env bash
# BITE TEST (2026-09-11): the material head-patch basis fix + the converter-azimuth sweep at 0/45/90.
#
#   -triad -convaz-nocomp : CANONICAL PARTS. convaz rotates the converter to a non-antipodal point on the
#       3.5 nm head sphere, so the head's "long axis" (converter<->interface line) turns from PERPENDICULAR
#       to the filament (alpha=0) toward PARALLEL (alpha=90) while the interface stays latched radially and
#       the head-centre radius is held at 7.00 nm -- no burial at any alpha. The converter arm is
#       7*cos(alpha/2) nm (7.00 / 6.06 / 4.95) and the stroke arc radius with it; -convaz-nocomp leaves
#       DTHETA_MOTOR at its canonical -60 deg rather than inflating it to hide that, so the measured
#       velocity carries pose AND the stroke-radius consequence together, deliberately.
#   caz00lab : the SAME alpha=0 scene on the LEGACY lab-fixed patch basis = the control for the patch fix.
set -u
OUT=${OUT:-RUN_LOGS/motor_audit/campaigns_2026-09/PATCHBASIS_BITE}
STEPS=${STEPS:-20000}
SEED=${SEED:-20260901}
mkdir -p "$OUT"
BASE="-run -gpu -devicecull -nohires -noviz -density 500 -matx 10.0 -maty 2.0 -filx 2.5 -eta 0.10 -dt 1.25e-6 -steps $STEPS -target 9.0 -filsegs 1 -triad"
run(){ rm -rf "$OUT/$1"; ./scripts/run_gpu_monitored.sh ./scripts/run_site_normal_glide_gpu.sh $BASE -seed $SEED $2 -out "$OUT/$1" > "$OUT/$1.log" 2>&1; }
run "caz00"    "-convaz-nocomp"               &
run "caz00lab" "-convaz-nocomp -triad-labpatch" &
run "caz45"    "-convaz 45 -convaz-nocomp"    &
run "caz90"    "-convaz 90 -convaz-nocomp"    &
wait
echo "=== all four arms done ==="
