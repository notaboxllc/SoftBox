#!/bin/bash
# Soft Box increment-4b-iii power-stroke checkpoint (aorus, TornadoVM PTX backend).
# Validates: cycle dwell times, catch-slip F-dependence, stroke displacement, directional force, CPU≡GPU.
#   ./run_stroke.sh
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx4G \
     -Dtornado.tvm.maxbytecodesize=16384 \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.MotorStrokeHarness "$@"
