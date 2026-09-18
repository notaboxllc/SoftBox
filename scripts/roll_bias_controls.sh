#!/usr/bin/env bash
# WHAT IS CAUSING THE NEGATIVE ROLL BIAS?  Three hypotheses, two controls that separate them.
#
# OBSERVATION (2026-09-16): at eta=0.01, 7 of 8 eps=0 arms accumulate NEGATIVE roll (-8 to -12 turns over
# ~0.9 s) REGARDLESS of lattice handedness -- native and flipped give -8.00 and -8.20 at alpha=60, i.e. the
# same number in mirrored lattices. That is achiral, so it is NOT the derived-handedness mechanism.
# The Brownian generator is EXCLUDED: 10M draws with production keying give every channel's mean within
# 1.2 sigma of zero and sd 0.9996-1.0000 (force AND torque branches).
#
# REMAINING HYPOTHESES and how these two arms separate them:
#   H1 INTEGRATOR/ORTHOGONALISATION precession -- DerivedGeometrySystem.orthogonalizeY re-orthogonalises yVec
#      against uVec every step; a fixed-order Gram-Schmidt can leave a systematic residual rotation about u.
#      ACHIRAL, scales with STEPS. => BARE arm (no motors) rolls.
#   H2 SYSTEMATIC ACHIRAL MOTOR TORQUE -- heads approach from below, so the bound-site azimuth distribution is
#      one-sided. ACHIRAL, scales with BINDING EVENTS. => BARE arm does NOT roll, NOBROWN arm DOES.
#   H3 GENUINE CHIRALITY, merely unresolved at eta=0.01. => neither control rolls.
#
# -density 0 is NOT supported (packExMat throws on N=0), and there is no -nobind flag, so the discriminator
# is a DENSITY SCALING test instead: a motor-driven roll scales with density; an integrator bias does not.
# density 200 is already measured (the eps=0 sweep arms: ~-8 turns over 0.9 s).
# ARM 1/2 "d1"/"d20" : 20 and 400 motors (vs 4000) => ~1/200 and ~1/10 the binding events, Brownian ON.
# ARM 2 "nobrown" : motors ON, filament Brownian OFF (-nofilbrownian -norollbrownian). Isolates deterministic
#                   torque. NOTE this also removes the thermal search, so binding will be reduced -- read the
#                   roll PER BINDING EVENT, not per second.
#
# IF THE BARE ARM ROLLS, every twirl number in this project sits on an achiral drift and mirror antisymmetry
# is the only estimator that was ever measuring chirality. That is the point of running this first.
set -u
OUT=${OUT:-RUN_LOGS/motor_audit/campaigns_2026-09/ROLL_BIAS_CONTROLS}
STEPS=${STEPS:-4000000}       # 0.5 s at dt 1.25e-7 -- the eps=0 arms show several turns by then
export SOFTBOX_XMX=${SOFTBOX_XMX:-4G}
mkdir -p "$OUT"
BASE="-run -gpu -devicecull -nohires -noviz -matx 10.0 -maty 2.0 -filx 2.5 -eta 0.01 -dt 1.25e-7 -steps $STEPS -target 9.0 -filsegs 1 -triad -convaz-nocomp -seed 20260901"
run(){ rm -rf "$OUT/$1"; ./scripts/run_gpu_monitored.sh ./scripts/run_site_normal_glide_gpu.sh \
       $BASE $2 -out "$OUT/$1" > "$OUT/$1.log" 2>&1; echo "done $1"; }
run "d1"      "-density 1"                                  &
run "d20"     "-density 20"                                 &
run "nobrown" "-density 200 -nofilbrownian -norollbrownian" &
wait
echo "=== ROLL_BIAS_CONTROLS done ==="
for a in d1 d20 nobrown; do echo "--- $a"; grep -E "TWIRL: net roll|avgBound = |captures|net forward|invalid =" "$OUT/$a.log" 2>/dev/null; done
