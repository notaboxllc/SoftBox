#!/usr/bin/env bash
# DOES THE 3-CONTACT PATCH DISRUPT TWIRLING?  All established twirl results (the rigid eps ladder, the
# Beausang match at eps~1.4-1.6) were measured with a SINGLE spring. The triad changed the head-actin
# connection, so the twirl result has to be re-earned on it, not assumed to carry over.
# Protocol is BYTE-MATCHED to scripts/twirl_rigid_eps_ladder.sh (density 2000, target 1.0 um, randbase,
# filsegs 1) so triad numbers sit on the same axis as the single-spring ladder. Only -triad differs.
# Runs terminate on DISTANCE (target 1.0 um), not step count: ~4.4 h GPU per arm.
# Seed 1 runs first and completely, so a 4-arm readout lands before seed 2 starts.
set -u
OUT=${OUT:-RUN_LOGS/motor_audit/campaigns_2026-09/TRIAD_EPS15}
mkdir -p "$OUT"
E=${E:-1.5}
BASE_COMMON="-run -gpu -devicecull -nohires -noviz -density 2000 -matx 14.0 -maty 2.0 -filx 3.5 -eta 0.10 -dt 1.25e-6 -steps 4000000 -target 1.0 -filsegs 1"
run(){ rm -rf "$OUT/$1"; ./scripts/run_gpu_monitored.sh ./scripts/run_site_normal_glide_gpu.sh \
       $BASE_COMMON -seed $3 -randbase -randbase-seed $3 -stroke-skew $E $2 -out "$OUT/$1" > "$OUT/$1.log" 2>&1; }
for S in 20260901 20260902; do
  run "TRI_pos_$S" "-triad"          $S &  run "TRI_neg_$S" "-triad -mirror" $S &  wait
  run "SGL_pos_$S" ""                $S &  run "SGL_neg_$S" "-mirror"        $S &  wait
  echo "--- seed $S complete ---"; python3 scripts/triad_eps15_analyse.py "$OUT" "$E"
done
