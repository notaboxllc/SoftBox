#!/bin/bash
# CANONICAL ROLLOUT §7 — quick GPU density sweep: legacy ownership/margin vs canonical half-open zero-margin.
# explicit-s2-l40 device-resident path. densities {100,200,400,700,1500,3000} × {legacy,canon} × seeds {101,202}.
# Screening only — short runtime, 2 seeds. Sequential on the single GPU.
set -u
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
OUT=RUN_LOGS/binddiag; mkdir -p "$OUT/logs"
EQ=${EQ:-8000}; MS=${MS:-20000}
runj() { java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx4G \
    -Dtornado.recover.bailout=false -Dtornado.enable.fma=false -Dtornado.tvm.maxbytecodesize=65536 \
    -cp "$TDIR/tornado-api-4.0.1-dev.jar:." softbox.ExplicitBindDiagHarness "$@"; }
t0=$(date +%s)
for dens in 100 200 400 700 1500 3000; do for seed in 101 202; do
  # legacy
  runj -mode glide -gpu -legacy -density $dens -seed $seed -glequil $EQ -glmeas $MS -tag roll_d${dens}_s${seed}_legacy > "$OUT/logs/roll_d${dens}_s${seed}_legacy.log" 2>&1; echo "done legacy d$dens s$seed"
  # canonical (default)
  runj -mode glide -gpu -density $dens -seed $seed -glequil $EQ -glmeas $MS -tag roll_d${dens}_s${seed}_canon > "$OUT/logs/roll_d${dens}_s${seed}_canon.log" 2>&1; echo "done canon d$dens s$seed"
done; done
echo "ALL ROLLOUT SWEEP DONE in $(( $(date +%s) - t0 ))s"
