#!/usr/bin/env bash
# WHERE IS THE GLIDING REGIME AT eta = 0.01?  The rho=800 pilot came back STALLED (avgBound 8.68, 13
# simultaneous, force balance 51.8% OPPOSING, class E) -- over-engaged, and also deep into the known
# Jacobi/stale co-bound-gather hazard, which deepens the tug-of-war exactly when many heads co-bind.
# Low viscosity raises engagement far more than density does, so the usable density at 0.01 is well BELOW
# the 500-2000 used at 0.1. Scout it before siting a 21-arm study.
# READ: pick the density where avgBound lands ~1-3 AND the mean axial force per bound head is clearly
# NEGATIVE (propulsive, i.e. <50% positive samples) AND the class is not E.
set -u
OUT=${OUT:-RUN_LOGS/motor_audit/campaigns_2026-09/ETA001_DENSITY}
STEPS=${STEPS:-50000}
mkdir -p "$OUT"
run(){ rm -rf "$OUT/$1"; ./scripts/run_gpu_monitored.sh ./scripts/run_site_normal_glide_gpu_snapshot.sh \
       -run -gpu -devicecull -nohires -noviz -density $2 -matx 10.0 -maty 2.0 -filx 2.5 -eta 0.01 -dt 1.25e-7 \
       -steps $STEPS -target 9.0 -filsegs 1 -triad -seed 20260901 -out "$OUT/$1" > "$OUT/$1.log" 2>&1; echo "done $1"; }
run "d050" 50  & run "d100" 100 & run "d200" 200 & wait
run "d400" 400 & wait
echo "=== density scout done ==="
printf "%-6s%9s%12s%10s%14s%10s  %s\n" rho avgBound "fax pN" "pos frac" "fwd um" "capt" class
for a in d050 d100 d200 d400; do
  L="$OUT/$a.log"
  b=$(grep -oE "avgBound = [0-9.]+" $L | head -1 | grep -oE "[0-9.]+")
  f=$(grep -oE "mean axial force per bound head = [-+0-9.]+" $L | grep -oE "[-+0-9.]+$")
  pp=$(grep -oE "\(pos [0-9]+ / neg [0-9]+" $L | grep -oE "[0-9]+ / neg [0-9]+")
  fw=$(grep -oE "net forward = [-+0-9.]+" $L | grep -oE "[-+0-9.]+$")
  c=$(grep -oE "CLASS = [A-Z]" $L | grep -oE "[A-Z]$")
  cp=$(grep -oE "captures = [0-9]+" $L | grep -oE "[0-9]+$")
  p=$(echo $pp | awk -F' / neg ' '{printf "%.3f", $1/($1+$2)}')
  printf "%-6s%9s%12s%10s%14s%10s  %s\n" "${a#d}" "$b" "$f" "$p" "$fw" "$cp" "$c"
done
