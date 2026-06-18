#!/bin/bash
# Soft Box increment-6c Stage-B2 node nucleation-function test (aorus, TornadoVM PTX backend).
# Args pass through to NodeNucleationHarness: [-cpu].
#   ./run_nodenuc.sh        # GPU + CPU cross-check (rate, tether, dissolution, pool, no-op, damping, publish-guard, CPU≡GPU)
#   ./run_nodenuc.sh -cpu   # CPU runner only (triage)
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx4G \
     -Dtornado.tvm.maxbytecodesize=16384 \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.NodeNucleationHarness "$@"
