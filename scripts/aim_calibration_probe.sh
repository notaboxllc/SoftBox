#!/usr/bin/env bash
# AIM-CALIBRATION PROBE: how does the stroke's AIM respond to the assembly's relaxed angles, and does the
# required re-aim depend on the converter placement alpha?
#
# WHY. The stroke's direction relative to the filament axis is set JOINTLY by alpha and the assembly's rest
# angles, so an alpha sweep at fixed rest angles measures "move the converter and re-aim nothing". Before any
# re-aimed campaign, we need the RESPONSE FUNCTION -- is the aim controllable by the rest angles at the scale
# needed, and is the aim-optimal phase different at alpha=0 vs the biological alpha=90?
#
# READOUT. The POST-stroke (ADP) AXIAL FRACTION = axial/|F| of the cross-bridge force. |F| is ~3 pN in every
# state but only ~10% of it is axial, so this fraction IS the propulsive efficiency of the bond force, and it
# is a per-attachment statistic -- it resolves in 0.05 s, unlike velocity, which needs ~1 s here.
#   d(axial fraction)/d(delta) => the aim response function
#   the delta maximising it at each alpha => the re-aim that alpha requires
#
# DELIBERATELY NOT a fit of delta against gliding speed: that would be single-assay motor tuning. This measures
# a force-direction response so delta can later be SET from geometry/mechanics rather than tuned against v.
#
# CAVEAT BY CONSTRUCTION: delta shifts BOTH converter targets, so it moves the pre-stroke (binding) pose too
# and therefore also affects recruitment via the g3 converter gate. avgBound is reported alongside so an
# aim gain that is really a recruitment change cannot be mistaken for one.
set -u
OUT=${OUT:-RUN_LOGS/motor_audit/campaigns_2026-09/AIM_PROBE}
STEPS=${STEPS:-400000}       # 0.05 s -- a force-direction statistic, not a displacement measurement
JOBS=${JOBS:-3}
mkdir -p "$OUT"
BASE="-run -gpu -devicecull -nohires -noviz -density 200 -matx 10.0 -maty 2.0 -filx 2.5 -eta 0.01 -dt 1.25e-7 -steps $STEPS -target 9.0 -filsegs 1 -triad -convaz-nocomp -seed 20260901"
run(){ rm -rf "$OUT/$1"; ./scripts/run_gpu_monitored.sh ./scripts/run_site_normal_glide_gpu_dtheta.sh \
       $BASE $2 -out "$OUT/$1" > "$OUT/$1.log" 2>&1; echo "done $1"; }
slot(){ while [ "$(jobs -rp | wc -l)" -ge "$JOBS" ]; do wait -n; done; }
for A in 0 90; do
  AF=""; [ "$A" != 0 ] && AF="-convaz $A"
  for D in -30 0 30; do
    DF=""; [ "$D" != 0 ] && DF="-stroke-phase $D"
    N="a${A}_d${D}"; N=${N//-/m}
    slot; run "$N" "$AF $DF" &
  done
done
wait
echo "=== AIM_PROBE done ==="
printf "%-10s%8s%8s%12s%12s%14s%10s%9s\n" arm alpha delta "|F| post" "axial post" "axialFrac" avgBound capt
for A in 0 90; do for D in m30 0 30; do
  N="a${A}_d${D}"; L="$OUT/$N.log"
  [ -f "$L" ] || continue
  line=$(grep -A3 "FORCE DECOMPOSITION" "$L" | grep "POST-stroke ADP")
  f=$(echo $line | awk '{print $3}'); ax=$(echo $line | awk '{print $4}'); fr=$(echo $line | awk '{print $5}')
  b=$(grep -oE "avgBound = [0-9.]+" "$L" | head -1 | grep -oE "[0-9.]+")
  c=$(grep -oE "captures = [0-9]+" "$L" | head -1 | grep -oE "[0-9]+")
  printf "%-10s%8s%8s%12s%12s%14s%10s%9s\n" "$N" "$A" "${D/m/-}" "$f" "$ax" "$fr" "$b" "$c"
done; done
