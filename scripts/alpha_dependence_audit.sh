#!/usr/bin/env bash
# ALPHA DEPENDENCE at the publication settings (eta=0.01, rho=200, dt=1.25e-7), with the force-balance audit.
#
# FRAMING (jba 2026-09-13): we are NOT looking for an optimum to then claim it matches biology. The converter
# placement is chosen STRUCTURALLY (large alpha = the long axis toward parallel with the filament, the real
# acto-myosin arrangement); this sweep asks whether that choice COSTS gliding performance. At alpha=90 the
# converter arm shortens 7.00 -> 4.95 nm (-29%), which should cut stroke amplitude, so a cost is plausible.
#   flat in alpha  => adopting the biological geometry is free; report the insensitivity
#   degrades       => a real, reportable cost of the structural choice
#
# WHY NOT VELOCITY ALONE: velocity needs ~1 s/arm for 3 sigma here (D ~ 1/eta) and has ALREADY produced one
# spurious 1.8x effect at 0.29 s that vanished by 0.60 s. The force channel has per-attachment statistics and
# is the primary observable; velocity is the cross-check.
#   - FORCE BALANCE : time-mean net cross-bridge axial force vs gamma_par*v (must agree in a steady glide)
#   - AXIAL FRACTION: |F| is ~3 pN in every state but only ~10% is axial. If alpha changes AIMING, the axial
#                     FRACTION moves at similar |F|; if it changes STRAIN, |F| itself moves.
#   - alpha = -60   : the sign test on the FORCE channel, far better resolved than a velocity reversal.
set -u
OUT=${OUT:-RUN_LOGS/motor_audit/campaigns_2026-09/ALPHA_AUDIT}
STEPS=${STEPS:-2000000}      # 0.25 s -- the force decomposition (~600 attachments) is the primary
                             # observable; velocity does not resolve usefully at EITHER duration here, so the
                             # extra 0.25 s bought nothing we use. Cut 2026-09-13.
JOBS=${JOBS:-3}
mkdir -p "$OUT"
BASE="-run -gpu -devicecull -nohires -noviz -density 200 -matx 10.0 -maty 2.0 -filx 2.5 -eta 0.01 -dt 1.25e-7 -steps $STEPS -target 9.0 -filsegs 1 -triad -convaz-nocomp"
run(){ rm -rf "$OUT/$1"; ./scripts/run_gpu_monitored.sh ./scripts/run_site_normal_glide_gpu_dtheta.sh \
       $BASE -seed $3 $2 -out "$OUT/$1" > "$OUT/$1.log" 2>&1; echo "done $1"; }
slot(){ while [ "$(jobs -rp | wc -l)" -ge "$JOBS" ]; do wait -n; done; }
for S in 20260901 20260902; do
  slot; run "a000_$S" ""             $S &
  slot; run "ap30_$S" "-convaz 30"   $S &
  slot; run "ap60_$S" "-convaz 60"   $S &
  slot; run "ap90_$S" "-convaz 90"   $S &
  slot; run "am60_$S" "-convaz -60"  $S &
done
wait
echo "=== ALPHA_AUDIT complete ==="
