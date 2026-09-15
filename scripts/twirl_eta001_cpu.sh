#!/usr/bin/env bash
# TWIRLING AT THE PUBLICATION VISCOSITY (eta = 0.01 Pa.s) -- PARKED ON CPU, multi-second horizon.
#
# WHY THIS IS A HOLE. EVERY twirl result we have -- the rigid eps ladder, the eps=8 triad-vs-single comparison,
# the 0.991 ratio -- is at eta = 0.1, which VISCOSITY_SENSITIVITY Part II established is BLIND to twirling
# (1.06 sigma, 58% seed sign, even at n=24). The paper's frame is two emergent behaviours from one motor, and
# one of them has never been measured at the settings the paper will quote.
#
# WHY IT SHOULD WORK BETTER HERE. Part II: Omega_odd rises 22.8x and turns/um 14x from eta 0.1 -> 0.01. At
# eta=0.1 eps=1.5 was UNRESOLVABLE (both configurations under the diffusion floor); at 0.01 the same eps should
# be strongly resolved. So this doubles as the test of whether low viscosity rescues the low-eps measurement.
#
# RUNNER: CPU sequential, one arm per core, NO GPU -- deliberately, so the alpha work keeps the device. The
# site-normal GPU graph is also self-declared experimental, so CPU is the measurement-grade runner anyway.
# HORIZON: -steps is set long (3 s) and the arms are READ FROM THE TRAJECTORY CSV as they go (rollTurns and
# fwd_um are per-row), so nothing waits on the closing summary. Kill or extend at will.
# SCENE: rigid single segment (filsegs 1) => no chain joints => the missing inter-segment torsional constraint
# (2026-09-06) cannot contaminate these, which is exactly why the ladder used a rigid filament.
set -u
OUT=${OUT:-RUN_LOGS/motor_audit/campaigns_2026-09/TWIRL_ETA001}
STEPS=${STEPS:-24000000}     # 3.0 s at dt 1.25e-7; read intermediate rows, do not wait for the end
EPS=${EPS:-1.5}
mkdir -p "$OUT"
export TORNADOVM_HOME="${TORNADOVM_HOME:-$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx}"
BASE="-run -nohires -noviz -density 200 -matx 10.0 -maty 2.0 -filx 2.5 -eta 0.01 -dt 1.25e-7 -steps $STEPS -target 9.0 -filsegs 1 -triad -convaz-nocomp -workers 1 -stroke-skew $EPS"
run(){ rm -rf "$OUT/$1"; ./scripts/run_site_normal_long_glide_dtheta.sh $BASE -seed $3 -randbase -randbase-seed $3 $2 -out "$OUT/$1" > "$OUT/$1.log" 2>&1; echo "done $1"; }
for S in 20260901 20260902; do
  run "pos_$S" ""         $S &
  run "neg_$S" "-mirror"  $S &
done
wait
echo "=== TWIRL_ETA001 complete ==="
