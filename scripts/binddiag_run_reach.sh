#!/bin/bash
# NEW STUDY §3/§4 — one-at-a-time reach/preload/in-segment recruitment matrix. CPU-only, deterministic, production dt.
# preload{1,1.5,2,2.5,3,4} · distance{2,2.5,3,3.5,4,5} · segMargin{0.05,0.0375,0.025,0.0125,0} × density{200,700} × seed{101,202,303}.
set -u
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
OUT=RUN_LOGS/binddiag; mkdir -p "$OUT/logs"
PAR=${PAR:-8}
run_one() {
  local dens=$1 seed=$2 name=$3 rb=$4 pl=$5 sm=$6
  local tag="reach_d${dens}_s${seed}_${name}"
  java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx2G \
    -Dtornado.recover.bailout=false -Dtornado.enable.fma=false -Dtornado.tvm.maxbytecodesize=65536 \
    -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
    softbox.ExplicitBindDiagHarness -mode recruit -density $dens -seed $seed -dtdiv 1 \
    -equilms 5 -measms 15 -rbind $rb -preload $pl -segmargin $sm -tag "$tag" > "$OUT/logs/$tag.log" 2>&1
  echo "done $tag"
}
export -f run_one; export TORNADOVM_HOME TDIR OUT
# name rBind preload segMargin
SETTINGS=(
 "pl1.0 3.0 1.0 0.05" "pl1.5 3.0 1.5 0.05" "pl2.0 3.0 2.0 0.05" "pl2.5 3.0 2.5 0.05" "pl3.0 3.0 3.0 0.05" "pl4.0 3.0 4.0 0.05"
 "di2.0 2.0 2.0 0.05" "di2.5 2.5 2.0 0.05" "di3.0 3.0 2.0 0.05" "di3.5 3.5 2.0 0.05" "di4.0 4.0 2.0 0.05" "di5.0 5.0 2.0 0.05"
 "sm0.050 3.0 2.0 0.05" "sm0.0375 3.0 2.0 0.0375" "sm0.025 3.0 2.0 0.025" "sm0.0125 3.0 2.0 0.0125" "sm0.000 3.0 2.0 0.0"
)
{
for dens in 200 700; do for seed in 101 202 303; do for s in "${SETTINGS[@]}"; do echo "$dens $seed $s"; done; done; done
} | xargs -P $PAR -n 6 bash -c 'run_one "$@"' _
echo "ALL REACH DONE"
