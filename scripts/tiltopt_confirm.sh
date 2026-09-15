#!/usr/bin/env bash
# CONFIRM the corrected-gate tilt optimum, then test it at the CANONICAL density.
#
# PHASE A (rho 500, matches the existing curve): 3rd seed on -10/-20/-30, new points -15/-25 at n=3 to bracket
#   the peak, and +20 at n=3 as a SYMMETRY CONTROL. The artefact was antisymmetric in tilt by construction
#   (the free swing's sign follows sign(b)); if the GATE-FIXED curve is symmetric in |b| instead, the surviving
#   effect is NOT directional and the structural reading weakens. That control is the point of gp20.
# PHASE B (rho 2000, the canonical density, where avgBound ~ 2.1 instead of ~0.68): baseline + the two optima.
#   Both optima so far were measured in a LOW-DUTY regime; an optimum that moves with duty ratio is not a
#   structural statement. One baseline serves both axes (convaz 0 == headtilt 0 == canonical).
# All arms: -tiltgate (consistent capture gate), triad, snapshot classpath.
set -u
OUT=${OUT:-RUN_LOGS/motor_audit/campaigns_2026-09/TILTOPT}
JOBS=${JOBS:-3}
mkdir -p "$OUT"
A500="-run -gpu -devicecull -nohires -noviz -density 500 -matx 10.0 -maty 2.0 -filx 2.5 -eta 0.10 -dt 1.25e-6 -steps 200000 -target 9.0 -filsegs 1 -triad"
B2000="-run -gpu -devicecull -nohires -noviz -density 2000 -matx 14.0 -maty 2.0 -filx 3.5 -eta 0.10 -dt 1.25e-6 -steps 200000 -target 9.0 -filsegs 1 -triad"
run(){ rm -rf "$OUT/$1"; ./scripts/run_gpu_monitored.sh ./scripts/run_site_normal_glide_gpu_snapshot.sh \
       $2 -seed $4 $3 -out "$OUT/$1" > "$OUT/$1.log" 2>&1; echo "done $1"; }
slot(){ while [ "$(jobs -rp | wc -l)" -ge "$JOBS" ]; do wait -n; done; }
echo "### PHASE A: rho 500 confirmatory sweep ###"
slot; run "A_gm10_20260903" "$A500" "-headtilt -10 -tiltgate" 20260903 &
slot; run "A_gm20_20260903" "$A500" "-headtilt -20 -tiltgate" 20260903 &
slot; run "A_gm30_20260903" "$A500" "-headtilt -30 -tiltgate" 20260903 &
for S in 20260901 20260902 20260903; do
  slot; run "A_gm15_$S" "$A500" "-headtilt -15 -tiltgate" $S &
  slot; run "A_gm25_$S" "$A500" "-headtilt -25 -tiltgate" $S &
  slot; run "A_gp20_$S" "$A500" "-headtilt 20 -tiltgate"  $S &
done
wait; echo "### PHASE A complete ###"
echo "### PHASE B: rho 2000 canonical-density check ###"
for S in 20260901 20260902 20260903; do
  slot; run "B_base_$S"  "$B2000" ""                          $S &
  slot; run "B_gm20_$S"  "$B2000" "-headtilt -20 -tiltgate"    $S &
  slot; run "B_cazp60_$S" "$B2000" "-convaz 60 -convaz-nocomp" $S &
done
wait; echo "=== TILTOPT complete (phase A + phase B) ==="
