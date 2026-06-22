#!/bin/bash
# Soft Box increment-6a myosin-dimer coupling test (aorus, TornadoVM PTX backend).
# Args pass through to MyosinDimerHarness:  [-cpu] [-n nDimers] [-dt dt] [-3js dir] [M].
#   ./run_dimer.sh                  # GPU + CPU cross-check, default 32 dimers (64 motors), M=5000
#   ./run_dimer.sh -cpu             # CPU runner only (triage)
#   ./run_dimer.sh -3js threejs_dimer -n 16   # dump viewer frames (Y-shaped dimers)
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx4G \
     -Dtornado.tvm.maxbytecodesize=16384 \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.MyosinDimerHarness "$@"
