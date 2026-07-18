#!/bin/bash
# NEW STUDY §8 — gliding density panel. CPU (deterministic, arbiter-grade) 100/200/400/700 + GPU 1500/3000 (paired,
# bistability-flagged). Settings: default + margin candidates + preload contrast. Prefix rgl_ (read by the aggregator).
set -u
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
OUT=RUN_LOGS/binddiag; mkdir -p "$OUT/logs"
EQ=${EQ:-15000}; MS=${MS:-25000}
runj() { java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx4G \
    -Dtornado.recover.bailout=false -Dtornado.enable.fma=false -Dtornado.tvm.maxbytecodesize=65536 \
    -cp "$TDIR/tornado-api-4.0.1-dev.jar:." softbox.ExplicitBindDiagHarness "$@"; }
run_cpu() { local dens=$1 seed=$2 name=$3 rb=$4 pl=$5 sm=$6; local tag="rgl_d${dens}_s${seed}_${name}"
  runj -mode glide -density $dens -seed $seed -glequil $EQ -glmeas $MS -rbind $rb -preload $pl -segmargin $sm -tag "$tag" > "$OUT/logs/$tag.log" 2>&1; echo "done cpu $tag"; }
run_gpu() { local dens=$1 seed=$2 name=$3 rb=$4 pl=$5 sm=$6; local tag="rgl_d${dens}_s${seed}_${name}"
  runj -mode glide -gpu -density $dens -seed $seed -glequil $EQ -glmeas $MS -rbind $rb -preload $pl -segmargin $sm -tag "$tag" > "$OUT/logs/$tag.log" 2>&1; echo "done gpu $tag"; }
export -f runj run_cpu run_gpu; export TORNADOVM_HOME TDIR OUT EQ MS
# name rBind preload segMargin
SETTINGS=(
 "def 3.0 2.0 0.05" "sm0125 3.0 2.0 0.0125" "sm025 3.0 2.0 0.025"
)
# CPU tiers (deterministic) — parallel
{
for dens in 100 200 400 700; do for seed in 101 202 303; do for s in "${SETTINGS[@]}"; do echo "$dens $seed $s"; done; done; done
} | xargs -P 8 -n 6 bash -c 'run_cpu "$@"' _
# GPU tiers (single GPU ⇒ sequential; bistability-flagged, paired seeds)
# GPU plateau moved to gpu_plateau.sh
false && for dens in 1500 3000; do for seed in 101 202 303; do for s in "${SETTINGS[@]}"; do run_gpu $dens $seed $s; done; done; done
echo "ALL GLIDE8 DONE"
