#!/bin/bash
# Soft Box increment-4b-i articulated-motor isometric test (aorus, TornadoVM PTX backend).
# Args pass through to MotorBodyHarness:  [-cpu] [-n nMotors] [-b brownScale] [-dt dt] [-3js dir] [M].
#   ./run_motorbody.sh                 # GPU + CPU cross-check, default 64 motors, M=5000, dt=1e-5
#   ./run_motorbody.sh -cpu            # CPU runner only (triage)
#   ./run_motorbody.sh -3js threejs_motorbody   # dump viewer frames (articulated motors)
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx4G \
     -Dtornado.tvm.maxbytecodesize=16384 \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.MotorBodyHarness "$@"
