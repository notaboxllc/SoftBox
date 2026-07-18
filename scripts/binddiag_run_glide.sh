#!/bin/bash
# Part D2 — reduced gliding velocity panel (CPU, deterministic). densities {100,200,400,700} × selected settings × seeds.
# Rough onset velocity (centroid-x LS slope). NOT the final recalibrated density sweep.
set -u
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
OUT=RUN_LOGS/binddiag; mkdir -p "$OUT/logs"
rm -f "$OUT/summary_glide.csv"
EQUIL=${EQUIL:-15000}; MEAS=${MEAS:-25000}
run_one() {
  local dens=$1 seed=$2 name=$3 phi=$4 psi=$5 th=$6
  local tag="glide_d${dens}_s${seed}_${name}"
  java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx2G \
    -Dtornado.recover.bailout=false -Dtornado.enable.fma=false -Dtornado.tvm.maxbytecodesize=65536 \
    -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
    softbox.ExplicitBindDiagHarness -mode glide -density $dens -seed $seed \
    -glequil $EQUIL -glmeas $MEAS -phideg $phi -psideg $psi -thetadeg $th -tag "$tag" > "$OUT/logs/$tag.log" 2>&1
  echo "done $tag"
}
export -f run_one; export TORNADOVM_HOME TDIR OUT EQUIL MEAS
# settings chosen from D1: default, tighter, looser, + informative one-at-a-time (theta loosened)
SETTINGS=(
 "sym1.0 25 25 20" "sym0.8 20 20 16" "sym1.5 37.5 37.5 30" "theta1.5 25 25 30"
)
{
for dens in 100 200 400 700; do for seed in 101 202 303; do for s in "${SETTINGS[@]}"; do echo "$dens $seed $s"; done; done; done
} | xargs -P 4 -n 6 bash -c 'run_one "$@"' _
echo "ALL GLIDE DONE"
