#!/bin/bash
# Soft Box increment-4b-ii cross-bridge + cross-entity gather test (aorus, TornadoVM PTX backend).
# Args pass through to MotorXBridgeHarness:  [-cpu] [-b brownScale] [-dt dt] [-3js dir] [M].
#   ./run_xbridge.sh               # GPU + CPU cross-check, default M=2000, dt=1e-5
#   ./run_xbridge.sh -cpu          # CPU runner only (triage)
#   ./run_xbridge.sh -3js threejs_xbridge   # dump viewer frames (motors bound to a pinned filament)
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx4G \
     -Dtornado.tvm.maxbytecodesize=16384 \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.MotorXBridgeHarness "$@"
