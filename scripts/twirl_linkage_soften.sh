#!/usr/bin/env bash
# Two-point bond + UPSTREAM linkage softening. Does relaxing the converter / S2 connections
# (NOT the site-normal U_bind, which is reserved as the power-stroke conformational channel)
# recover the gliding the two-point actin attachment destroys?
#
# Pre-stated pass criterion: glide within ~2x of the matched single-point baseline (arm A),
# with avgBound comparable. Arm F is the confound control: softening alone, no two-point.
set -u
OUT=${OUT:-RUN_LOGS/motor_audit/campaigns_2026-09/LINKAGE_SOFTEN}
STEPS=${STEPS:-40000}
SEED=${SEED:-20260901}
F=${F:-0.1}
mkdir -p "$OUT"
BASE="-run -gpu -devicecull -nohires -noviz -density 500 -matx 10.0 -maty 2.0 -seed $SEED -filx 2.5 -eta 0.10 -dt 1.25e-6 -steps $STEPS -target 9.0"
declare -A ARM=(
  [A_singlepoint]=""
  [B_twopoint]="-twopoint"
  [C_2pt_conv]="-twopoint -soft-conv $F"
  [D_2pt_s2bend]="-twopoint -soft-s2b $F"
  [E_2pt_s2axial]="-twopoint -soft-s2a $F"
  [F_conv_only]="-soft-conv $F"
)
run_arm(){ local n=$1; rm -rf "$OUT/$n"; \
  ./scripts/run_gpu_monitored.sh ./scripts/run_site_normal_glide_gpu.sh $BASE ${ARM[$n]} -out "$OUT/$n" > "$OUT/$n.log" 2>&1; }
i=0
for n in "${!ARM[@]}"; do run_arm "$n" & i=$((i+1)); [ $((i%3)) -eq 0 ] && wait; done
wait
echo "=== LINKAGE SOFTENING (factor $F, $STEPS steps, seed $SEED) ==="
printf "%-16s %10s %10s %10s %10s\n" arm v_um_s avgBound turns fwd_um
for n in A_singlepoint B_twopoint C_2pt_conv D_2pt_s2bend E_2pt_s2axial F_conv_only; do
  f="$OUT/$n/trajectory_summary.csv"
  [ -s "$f" ] && awk -F'\t' -v n="$n" 'END{printf "%-16s %10.4f %10.4f %10.4f %10.5f\n", n, $9, $11, $39, $4}' "$f" \
    || printf "%-16s %10s\n" "$n" "NO DATA"
done
