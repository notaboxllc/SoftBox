#!/usr/bin/env bash
# 2x2 HANDEDNESS DECOMPOSITION -- the reviewer's §7, completing a square that is already 3/4 run.
#
# WHY. -flip-helix flips ONLY TWIST_PER_MON_DEG (the actin lattice). -convaz rotates the converter
# azimuthally on the HEAD, so +alpha and -alpha are mirror images: the MOTOR carries its own handedness.
# Mirroring the lattice alone is therefore NOT a parity operation on the actomyosin system, and
# "same sign under -flip-helix => achiral => artifact" does not follow. Reviewer note §6.
#
# THE SQUARE (all at matched seed 20260901, same lawn, same runner, frustrated triad):
#                    convaz +60      convaz -60
#     native            -9.53          -16.42      ALPHA_LONG/ap60, ALPHA_LONG/am60
#     mirror           -10.99           THIS ARM   ALPHA_LONG/ap60_flp
#
# DECOMPOSITION once complete:
#   odd under ACTIN handedness      -> lattice-chiral channel
#   odd under CONVERTER handedness  -> motor geometry supplies the handedness
#   odd under the PRODUCT           -> actin-motor chiral coupling
#   even under BOTH                 -> parity-invariant: artifact / rest-state defect
#
# CAVEAT carried forward: this square is on the PRE-FIX (frustrated) triad, because that is what the
# other three arms used. It is a symmetry diagnostic, not a production measurement, and it is n=1 per
# cell -- a sign pattern to orient the search, not an effect size.
set -u
OUT=${OUT:-RUN_LOGS/motor_audit/campaigns_2026-09/PARITY_2X2}
mkdir -p "$OUT"
STEPS=${STEPS:-8000000}       # 1.0 s at dt 1.25e-7, matching the other three cells
BASE="-run -gpu -devicecull -nohires -noviz -density 200 -matx 10.0 -maty 2.0 -filx 2.5 -eta 0.01 -dt 1.25e-7 -steps $STEPS -target 9.0 -filsegs 1 -triad -convaz-nocomp -seed 20260901"
export SOFTBOX_XMX=${SOFTBOX_XMX:-4G}
rm -rf "$OUT/am60_flp"
./scripts/run_gpu_monitored.sh ./scripts/run_site_normal_glide_gpu.sh \
    $BASE -convaz -60 -flip-helix -out "$OUT/am60_flp" > "$OUT/am60_flp.log" 2>&1
echo "done am60_flp"
