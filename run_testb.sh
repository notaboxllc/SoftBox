#!/bin/bash
# Soft Box increment-6c Test B — the SCPR primitive (two nodes capture-and-pull).
# Args pass through to TestBScprHarness: [-cpu] [-reach r] [-nsing n] [-ndim n].
#   ./run_testb.sh                 # GPU + CPU cross-check (Stage 0 gate, then Stage 1 if it passes)
#   ./run_testb.sh -cpu            # CPU runner only (triage)
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx4G \
     -Dtornado.tvm.maxbytecodesize=16384 \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.TestBScprHarness "$@"
