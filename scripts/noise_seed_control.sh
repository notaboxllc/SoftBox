#!/usr/bin/env bash
# NOISE-SEED CONTROL (jba's objection, 2026-09-22): is L a property of the LAWN or of the PATH?
#
# The matched-lawn paired design assumes roll = L(lawn) + S(treatment), L shared within a pair. jba:
# the effective lawn is the LOCAL one, so two arms that decorrelate sample different local motor
# arrangements and L does not fully cancel.
#
# THE CONTROL: same lawn, same treatment, DIFFERENT noise stream (-noise-seed keys the per-step RNG
# independently of build(seed), which lays the lawn). The spread of such a pair is the floor that
# pairing can NEVER remove.
#   floor ~ 3.7  => the residual is thermal, pairing works as advertised
#   floor ~ 9    => L is trajectory-local, pairing buys nothing, the eps-odd design is dead
#
# The RUN-LENGTH dependence of that floor is jba's mechanism test and comes FREE by truncating these
# same arms: if the floor grows with T, the two trajectories are diverging chaotically as predicted.
set -u
OUT=${OUT:-RUN_LOGS/motor_audit/campaigns_2026-09/NOISE_SEED_CONTROL}
mkdir -p "$OUT"
STEPS=${STEPS:-8000000}
BASE="-run -gpu -devicecull -nohires -noviz -density 200 -matx 10.0 -maty 2.0 -filx 2.5 -eta 0.01 -dt 1.25e-7 -steps $STEPS -target 9.0 -filsegs 1 -triad -convaz-nocomp"
export SOFTBOX_XMX=${SOFTBOX_XMX:-4G}
run(){ rm -rf "$OUT/$1"; ./scripts/run_gpu_monitored.sh ./scripts/run_site_normal_glide_gpu.sh \
       $BASE -seed $2 -noise-seed $3 -out "$OUT/$1" > "$OUT/$1.log" 2>&1; echo "done $1"; }
for s in 20260901 20260902; do
  run "lawn${s}_nA" $s 77700001 &
  run "lawn${s}_nB" $s 77700002 &
done
wait
echo "=== NOISE_SEED_CONTROL complete ==="
