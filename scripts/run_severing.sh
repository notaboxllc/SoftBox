#!/bin/bash
# Soft Box increment-7 SEVERING build (B): cofilin en-masse whole-segment dissolve + the combined turnover system
# (aorus, TornadoVM PTX backend). Args pass through to SeveringHarness: [-cpu] [-3js <dir>].
#   ./run_severing.sh                      # GPU + CPU cross-check (trigger, two-chains, conservation, CPU≡GPU, no-op, combined)
#   ./run_severing.sh -cpu                 # CPU runner only (triage)
#   ./run_severing.sh -cpu -3js threejs_severing   # dump the combined watchable render (growth+depoly+aging+severing)
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx4G \
     -Dtornado.tvm.maxbytecodesize=16384 \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.SeveringHarness "$@"
