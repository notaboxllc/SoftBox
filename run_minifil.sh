#!/bin/bash
# Soft Box increment-6b myosin-minifilament test (aorus, TornadoVM PTX backend).
# Args pass through to MiniFilamentHarness:  [-cpu] [-n nBackbones] [-de dimersEachEnd] [-dt dt] [-3js dir] [M].
#   ./run_minifil.sh                 # GPU + CPU cross-check, default 8 backbones x 8 dimers/end, M=4000
#   ./run_minifil.sh -cpu            # CPU runner only (triage)
#   ./run_minifil.sh -3js threejs_minifil -n 4   # dump viewer frames (backbone + dimer carpet)
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx4G \
     -Dtornado.tvm.maxbytecodesize=16384 \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.MiniFilamentHarness "$@"
