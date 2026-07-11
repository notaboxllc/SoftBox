#!/bin/bash
# FORCE_VELOCITY_TEST — velocity-clamp force–velocity sweep (CPU kinematic clamp).
# Sweeps clamp glide-speed v ∈ [-12..+12] µm/s at two densities, Variant A (full binding) by default.
#   scripts/run_fvtest.sh                 # Variant A, d2000 + d4000, v grid, 30k steps
#   scripts/run_fvtest.sh -episode 500    # Variant B (one-attachment episodes, re-arm every 500 steps)
# Emits the FVROW / XBROW lines each point; grep them from the log. CPU-only, deterministic.
set -u
STEPS=${STEPS:-12000}
SEED=${SEED:-0}
VS=${VS:-"-12 -8 -6 -4 -2 0 2 4 6 8 10 12"}
DENS=${DENS:-"2000 4000"}
EPISODE=""
if [ "${1:-}" = "-episode" ]; then EPISODE="-fvepisode ${2:-500}"; shift 2 2>/dev/null || shift 1; fi
OUT=${OUT:-RUN_LOGS/$(date +%F)_fvtest$( [ -n "$EPISODE" ] && echo _variantB ).txt}
mkdir -p RUN_LOGS
echo "# FORCE_VELOCITY_TEST sweep  steps=$STEPS seed=$SEED  ${EPISODE:-VariantA}" | tee "$OUT"
for D in $DENS; do
  for V in $VS; do
    echo "### density=$D v=$V" | tee -a "$OUT"
    scripts/run_gliding.sh -matbox 50 -density "$D" -vclamp "$V" $EPISODE -seed "$SEED" "$STEPS" 2>&1 \
      | grep -E "clamp v|WARNING|N_reach|f̄_avail|f̄_bound|J_attach|work/attach|x_bind \(|FVROW|XBROW" | tee -a "$OUT"
  done
done
echo "# done → $OUT"
echo; echo "=== FVROW summary ==="; grep "^FVROW" "$OUT"
