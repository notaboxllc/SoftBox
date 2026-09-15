#!/usr/bin/env bash
# TILT-AWARE CAPTURE GATE A/B -- is the head-tilt velocity gain a POSE effect or the attachment-time
# reorientation artefact (jba's diagnosis)?
#
# THE ARTEFACT. g1' admitted a head within 25 deg of -n_site and g5 charged 0.5*k_bind*theta^2 measured to
# -n_site; siteCoupleStep then HELD it at eTarget = -n*cos(b) + u*sin(b). So at b != 0 the head was admitted
# radial, charged ~0 kT, and then dragged b degrees -- displacing the bound point by 2*R_F8*sin(b/2) and doing
# directed work on the filament ONCE PER ATTACHMENT, sign set by sign(b). Evidence already in hand: over the
# whole -60..+60 curve the velocity excess per attachment equals that chord to within 0.65-1.42x, at FLAT
# capture count (231-312 while v spans -1.33..+7.39 um/s). And the strain the latch relaxes through at b=30 is
# 17.1 kT -- ABOVE the model's own 15 kT binding budget, charged as zero.
#
# THE TEST. -tiltgate evaluates BOTH the admission cone and the g5 energy charge at the TILTED target, so a
# head can only bind if it ALREADY holds the pose the latch will impose. Nothing reorients after binding.
#   artefact  => the gain collapses toward the tilt-0 baseline (and capture rate falls, since the pose is now
#                required rather than granted: a 2k-step bite gave avgBound 0.475 -> 0.150 at b=-30)
#   real pose => the gain survives
#
# ANGLES. -10 / -20 / -30 spans the energy budget: the latch strain at the held pose costs 1.9 / 7.6 / 17.1 kT
# of the 15 kT budget, so -10 and -20 remain admissible under the consistent gate while -30 does not. If a
# GENUINE tilt optimum exists it must live at small |b|, which is exactly where this brackets it.
#
# MATCHED CONTROLS ALREADY EXIST (same scene, same runner, n=3): HEADTILT_SWEEP t000 = 1.163 um/s,
# tm10 = 1.584, tm20 = 2.364, tm30 = 3.822. Only the gate-fixed arms are run here.
# Runs on the .build-tiltgate SNAPSHOT classpath so no live run's class files are touched.
set -u
OUT=${OUT:-RUN_LOGS/motor_audit/campaigns_2026-09/TILTGATE_AB}
STEPS=${STEPS:-200000}
JOBS=${JOBS:-2}
mkdir -p "$OUT"
# Wait for the TRIAD_EPS8 GPU pair to finish -- never share the device with the decisive twirl run.
waitfor(){ while :; do n=0
    for l in /proc/*/exe; do t=$(readlink "$l" 2>/dev/null) || continue
      case "$t" in *java) p=${l#/proc/}; p=${p%/exe}
        tr '\0' ' ' < /proc/$p/cmdline 2>/dev/null | grep -q TRIAD_EPS8 && n=$((n+1));; esac; done
    [ "$n" -eq 0 ] && break; sleep 120; done; }
echo "waiting for TRIAD_EPS8 to release the GPU..."; waitfor; echo "GPU free at $(date -u +%FT%TZ); starting"
BASE="-run -gpu -devicecull -nohires -noviz -density 500 -matx 10.0 -maty 2.0 -filx 2.5 -eta 0.10 -dt 1.25e-6 -steps $STEPS -target 9.0 -filsegs 1 -triad"
run(){ rm -rf "$OUT/$1"; ./scripts/run_gpu_monitored.sh ./scripts/run_site_normal_glide_gpu_snapshot.sh \
       $BASE -seed $3 $2 -out "$OUT/$1" > "$OUT/$1.log" 2>&1; echo "done $1"; }
for S in 20260901 20260902; do
  for B in 10 20 30; do
    while [ "$(jobs -rp | wc -l)" -ge "$JOBS" ]; do wait -n; done
    run "gm${B}_$S" "-headtilt -$B -tiltgate" $S &
  done
done
wait
echo "=== TILTGATE_AB complete ==="
