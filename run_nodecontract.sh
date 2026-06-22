#!/bin/bash
# Soft Box increment 6 — the NODE in the minimal contractile assay (node ⇄ minifilament swap).
# A free (box-confined) protein node at the overlap centre; its radial myosins bind two anti-parallel
# pinned filaments and pull them together. Nucleation OFF. Qualitative + instrumentation.
# Args pass through to NodeContractileHarness: [-cpu] [-3js dir] [-steps N] [-anchor] [-diag]
#                                              [-reach r] [-koff k] [-yoff y] [-nsing n] [-ndim n].
#   ./run_nodecontract.sh                 # GPU + CPU cross-check: #2 contracts, #3 CPU≡GPU, #4 control, #5 containment
#   ./run_nodecontract.sh -cpu            # CPU runner only (triage)
#   ./run_nodecontract.sh -cpu -diag      # per-pole engagement diagnostic
#   ./run_nodecontract.sh -3js threejs_nodecontract -steps 30000   # viewer (the v1 contractility panel, node centre)
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx4G \
     -Dtornado.tvm.maxbytecodesize=16384 \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.NodeContractileHarness "$@"
