#!/usr/bin/env bash
# IS THE TWIRL LOST ON THE TRIAD BASE?  Decisive version of the TRIAD_EPS15 question, at eps=8 where the
# single-spring ladder resolves the twirl per-arm (z = net roll / diffusive spread = -5.88 / +8.16, increment
# sign fraction 0.26 / 0.89) instead of eps=1.5 where BOTH configurations sit under the diffusion floor
# (|z| <= 2.6, sign fraction ~0.5) and the published 12x "suppression" was a single low draw.
#
# MATCHED CONTROL ALREADY EXISTS: RIGID_EPS_LADDER/e8_pos + e8_neg -- same scene (mat 14x2, rho 2000,
# dt 1.25e-6, rigid nSeg=1, target 1.0 um), same runner (GPU device-resident), same -randbase-seed 20260901
# => the SAME motor lawn. So this launches ONLY the two triad arms; re-running the single-spring pair would
# reproduce e8_pos/e8_neg. Read the pair against those.
#
# EXPECTATION if the triad preserves the chiral channel: odd/eps ~ -1.5 (the ladder's saturated value) and
# |z| ~ 6-8. If the triad destroys it: odd collapses toward 0 and z falls to ~1 with sign fraction ~0.5.
# Analysis note: eps enters the triad as a RIGID rotation of the whole 3-contact actin triangle about the
# filament axis, which preserves the axial couple k*R^2*eps EXACTLY and attenuates only the net tangential
# force (to 0.898x, the cosine of the +37/-18.5/-18.5 deg vertex azimuth spread). So a large suppression, if
# found, has to live in the rotational RESPONSE (the head is orientationally clamped by 3 contacts), not in
# the drive.
set -u
OUT=${OUT:-RUN_LOGS/motor_audit/campaigns_2026-09/TRIAD_EPS8}
E=${E:-8}
S=${S:-20260901}
mkdir -p "$OUT"
BASE="-run -gpu -devicecull -nohires -noviz -density 2000 -matx 14.0 -maty 2.0 -filx 3.5 -eta 0.10 -dt 1.25e-6 -steps 4000000 -target 1.0 -filsegs 1"
run(){ rm -rf "$OUT/$1"; ./scripts/run_gpu_monitored.sh ./scripts/run_site_normal_glide_gpu.sh \
       $BASE -seed $S -randbase -randbase-seed $S -stroke-skew $E $2 -out "$OUT/$1" > "$OUT/$1.log" 2>&1; }
run "TRI_pos_$S" "-triad"          &
run "TRI_neg_$S" "-triad -mirror"  &
wait
echo "=== TRIAD_EPS8 pair complete ==="
