#!/bin/bash
# Soft Box increment-6c Stage-A protein-node test (aorus, TornadoVM PTX backend).
# Args pass through to ProteinNodeHarness: [-cpu] [-n nNodes] [-s nSinglets] [-d nDimers] [-3js dir].
#   ./run_node.sh                 # GPU + CPU cross-check (gather, gather-under-load, binding, cap, containment, anchor)
#   ./run_node.sh -cpu            # CPU runner only (triage)
#   ./run_node.sh -3js threejs_node -n 3   # dump viewer frames (radially-splayed nodes)
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx4G \
     -Dtornado.tvm.maxbytecodesize=16384 \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.ProteinNodeHarness "$@"
