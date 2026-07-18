#!/bin/bash
# NEW STUDY §7 — selected two-parameter combinations (recruit assay). CPU-only, deterministic, production dt.
set -u
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
OUT=RUN_LOGS/binddiag; mkdir -p "$OUT/logs"
PAR=${PAR:-8}
run_one() {
  local dens=$1 seed=$2 name=$3 rb=$4 pl=$5 sm=$6
  local tag="combo_d${dens}_s${seed}_${name}"
  java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx2G \
    -Dtornado.recover.bailout=false -Dtornado.enable.fma=false -Dtornado.tvm.maxbytecodesize=65536 \
    -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
    softbox.ExplicitBindDiagHarness -mode recruit -density $dens -seed $seed -dtdiv 1 \
    -equilms 5 -measms 15 -rbind $rb -preload $pl -segmargin $sm -tag "$tag" > "$OUT/logs/$tag.log" 2>&1
  echo "done $tag"
}
export -f run_one; export TORNADOVM_HOME TDIR OUT
# name rBind preload segMargin   — the four sanctioned combinations
SETTINGS=(
 "c1sm0125 3.0 2.0 0.0125"        # sweet-spot margin alone (low-risk recommendation)
 "c2sm025pl25 3.0 2.5 0.025"      # modest margin + modest preload
 "c3di4pl2 4.0 2.0 0.05"          # larger distance, unchanged preload (no-op control)
 "c4sm0pl3 3.0 3.0 0.0"           # aggressive boundary case
)
{
for dens in 200 700; do for seed in 101 202 303; do for s in "${SETTINGS[@]}"; do echo "$dens $seed $s"; done; done; done
} | xargs -P $PAR -n 6 bash -c 'run_one "$@"' _
echo "ALL COMBO DONE"
