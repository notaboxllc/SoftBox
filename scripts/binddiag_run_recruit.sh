#!/bin/bash
# Part D1/E — angular-gate sensitivity recruitment matrix. CPU-only, deterministic, production dt.
# symmetric panel {0.6,0.8,1.0,1.2,1.5,2.0} + one-at-a-time phi/psi/theta {0.8,1.2,1.5}, × density {200,700} × seed {101,202,303}.
set -u
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
OUT=RUN_LOGS/binddiag; mkdir -p "$OUT/logs"
rm -f "$OUT/summary_recruit.csv"
run_one() {
  local dens=$1 seed=$2 name=$3 phi=$4 psi=$5 th=$6
  local tag="recruit_d${dens}_s${seed}_${name}"
  java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx2G \
    -Dtornado.recover.bailout=false -Dtornado.enable.fma=false -Dtornado.tvm.maxbytecodesize=65536 \
    -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
    softbox.ExplicitBindDiagHarness -mode recruit -density $dens -seed $seed -dtdiv 1 \
    -equilms 5 -measms 15 -phideg $phi -psideg $psi -thetadeg $th -tag "$tag" > "$OUT/logs/$tag.log" 2>&1
  echo "done $tag"
}
export -f run_one; export TORNADOVM_HOME TDIR OUT
# settings: name phi psi theta
SETTINGS=(
 "sym0.6 15 15 12" "sym0.8 20 20 16" "sym1.0 25 25 20" "sym1.2 30 30 24" "sym1.5 37.5 37.5 30" "sym2.0 50 50 40"
 "phi0.8 20 25 20" "phi1.2 30 25 20" "phi1.5 37.5 25 20"
 "psi0.8 25 20 20" "psi1.2 25 30 20" "psi1.5 25 37.5 20"
 "theta0.8 25 25 16" "theta1.2 25 25 24" "theta1.5 25 25 30"
)
{
for dens in 200 700; do for seed in 101 202 303; do for s in "${SETTINGS[@]}"; do echo "$dens $seed $s"; done; done; done
} | xargs -P 6 -n 6 bash -c 'run_one "$@"' _
echo "ALL RECRUIT DONE"
