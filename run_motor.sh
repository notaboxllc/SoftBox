#!/bin/bash
# Soft Box increment-4a motor-binding test (aorus, TornadoVM PTX backend). Args pass
# through to MotorBindingHarness:  [-cpu] [-b brownScale] [-3js dir] [M].
#   ./run_motor.sh            # GPU + CPU cross-check, default M=3000
#   ./run_motor.sh -cpu       # CPU runner only (triage)
#   ./run_motor.sh -3js threejs_motor   # dump viewer frames (CPU runner)
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx4G \
     -Dtornado.tvm.maxbytecodesize=16384 \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.MotorBindingHarness "$@"
