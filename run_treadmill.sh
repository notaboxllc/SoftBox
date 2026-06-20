#!/bin/bash
# Soft Box increment-7 treadmilling steady-state validation (growth+depoly coupling vs first-principles C_c).
#   ./run_treadmill.sh        # GPU + CPU cross-check
#   ./run_treadmill.sh -cpu   # CPU runner only (the physics measurement)
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx4G \
     -Dtornado.tvm.maxbytecodesize=16384 \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.TreadmillHarness "$@"
