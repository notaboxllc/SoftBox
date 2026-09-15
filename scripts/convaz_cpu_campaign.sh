#!/usr/bin/env bash
# CONVERTER-AZIMUTH (head long-axis obliquity) CAMPAIGN — PARKED ON CPU (2026-09-11).
#
# RUNNER DISCLOSURE (CLAUDE.md): the CPU SEQUENTIAL runner, one arm per core, NO GPU work. This is not a
# fallback -- the site-normal / chi-dynamic-head GPU graph is EXPERIMENTAL by its own launcher's declaration
# (bound-branch CPU/GPU gate not green), so CPU is the only runner whose output is a MEASUREMENT here.
# Measured cost on this scene: 16.6 steps/s/arm => 200k steps = 0.25 s simulated = ~3.3 h of one core.
#
# GEOMETRY. -convaz alpha moves the CONVERTER to a non-antipodal point on the 3.5 nm head sphere, so the
# head's long axis (converter<->interface line) turns from PERPENDICULAR to the filament (alpha=0, canonical)
# toward PARALLEL (alpha=90) while the interface stays latched radially: head-centre radius is held at
# 7.00 nm at every alpha, so nothing is buried. All part dimensions are canonical; the converter ARM is the
# chord 7*cos(alpha/2) nm (7.00 / 6.06 / 4.95 at 0 / 60 / 90) and the stroke arc radius with it.
# -convaz-nocomp leaves DTHETA_MOTOR at its canonical -60 deg rather than inflating it to hide that.
#
# WHY BOTH SIGNS. The arm-shortening (hence stroke-amplitude) consequence is EVEN in alpha; the pose effect is
# ODD. Only a signed grid separates them: odd = (v(+a) - v(-a))/2, even = (v(+a) + v(-a))/2.
#
# Triad attachment ON, material patch basis (the 2026-09-11 fix; -triad-labpatch is the legacy control).
set -u
OUT=${OUT:-RUN_LOGS/motor_audit/campaigns_2026-09/CONVAZ_CPU}
STEPS=${STEPS:-200000}
JOBS=${JOBS:-6}
export TORNADOVM_HOME="${TORNADOVM_HOME:-$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx}"
mkdir -p "$OUT"
BASE="-run -nohires -noviz -density 500 -matx 10.0 -maty 2.0 -filx 2.5 -eta 0.10 -dt 1.25e-6 -steps $STEPS -target 9.0 -filsegs 1 -triad -convaz-nocomp -workers 1"
run(){   # $1 = arm name, $2 = extra flags, $3 = seed
  rm -rf "$OUT/$1"                     # the trajectory writer APPENDS: a stale dir gives a duplicate-step file
  ./scripts/run_site_normal_long_glide.sh $BASE -seed $3 $2 -out "$OUT/$1" > "$OUT/$1.log" 2>&1
  echo "done $1"
}
for S in 20260901 20260902 20260903; do
  for A in 0 30 -30 60 -60 90 -90; do
    case $A in 0) NAME=caz000;; -*) NAME=cazm${A#-};; *) NAME=cazp$A;; esac
    [ "$A" = 0 ] && FLAGS="" || FLAGS="-convaz $A"
    while [ "$(jobs -rp | wc -l)" -ge "$JOBS" ]; do wait -n; done
    run "${NAME}_$S" "$FLAGS" $S &
  done
done
wait
echo "=== CONVAZ_CPU campaign complete (21 arms) ==="
