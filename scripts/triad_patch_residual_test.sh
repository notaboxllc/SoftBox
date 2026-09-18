#!/usr/bin/env bash
# IS THE ACHIRAL ROLL THE TRIAD PATCH'S ARC-vs-CHORD RESIDUAL?
#
# bondForcesSurfaceTriad places the ACTIN contacts ON THE CYLINDER (azimuth = az0 + offT/Ractin) but the HEAD
# anchors in a FLAT plane (ht + offA*hy + offT*hz). The docstring claims "they coincide when the head sits on
# the site -- a relaxed state". They do NOT: arc vs chord leaves a per-vertex tangential mismatch, and because
# the vertex azimuths are ASYMMETRIC (+37.0, -18.5, -18.5 deg) the residuals do not cancel:
#     v0 head 2.260 vs actin 2.106 => -0.154 nm ; v1,v2 +0.020 each  =>  NET -0.115 nm
# At k/3 per spring that is -0.038 pN tangential at R=3.5 nm = -0.134 pN.nm about the FILAMENT AXIS per bound
# head. With gamma_roll = 4*pi*eta*L*r^2 = 3.24e-24 N.m.s and avgBound 1.44 that predicts -9.5 turns/s.
# MEASURED in the rho=200 eps=0 arms: -9.7 to -14.5 turns/s. Right sign, right magnitude.
#
# Predicts: motor-dependent (absent at rho=1,20 -- confirmed), ACHIRAL (confirmed: native -9.65 / flipped
# -10.42 at alpha=60), alpha-independent (confirmed: alpha=0 flipped rolls too).
#
# TWO CONTROLS, both at the alpha=60 native config that rolled -9.65 turns/s:
#   notriad  : single F8 spring, no patch at all  => roll must VANISH
#   rho_half : -triad-rho 1.13 (half)             => the mismatch goes as rho^3/R^2, so roll must drop ~8x
# A third possibility the pair also covers: if notriad still rolls, the residual is NOT the triad and the
# hypothesis is dead regardless of how well the arithmetic matched.
#
# NOTE FOR THE TWIRL RESULTS: an ACHIRAL bias CANCELS in the mirror-antisymmetry estimator (native-flipped)/2,
# so the eps=8 triad-vs-single odd/eps = 0.991 comparison is NOT contaminated. Any RAW roll number from a
# triad run IS.
set -u
OUT=${OUT:-RUN_LOGS/motor_audit/campaigns_2026-09/TRIAD_PATCH_RESIDUAL}
STEPS=${STEPS:-4000000}
export SOFTBOX_XMX=${SOFTBOX_XMX:-4G}
mkdir -p "$OUT"
BASE="-run -gpu -devicecull -nohires -noviz -density 200 -matx 10.0 -maty 2.0 -filx 2.5 -eta 0.01 -dt 1.25e-7 -steps $STEPS -target 9.0 -filsegs 1 -convaz-nocomp -convaz 60 -seed 20260901"
run(){ rm -rf "$OUT/$1"; ./scripts/run_gpu_monitored.sh ./scripts/run_site_normal_glide_gpu.sh \
       $BASE $2 -out "$OUT/$1" > "$OUT/$1.log" 2>&1; echo "done $1 (exit $?)"; }
run "notriad"  ""                          &
run "rho_half" "-triad -triad-rho 1.13"    &
wait
echo "=== TRIAD_PATCH_RESIDUAL done ==="
for a in notriad rho_half; do echo "--- $a"; grep -E "TWIRL: net roll|avgBound = |invalid =" "$OUT/$a.log"; done
