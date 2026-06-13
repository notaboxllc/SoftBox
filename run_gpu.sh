#!/bin/bash
# Soft Box GPU run (aorus, TornadoVM PTX backend). Args pass through to the harness
# (optional: N [M_trans]).  e.g.  ./run_gpu.sh 4096 20000
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx4G \
     -Dtornado.tvm.maxbytecodesize=16384 \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.DiffusionHarness "$@"
