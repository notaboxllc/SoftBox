#!/bin/bash
# Soft Box increment-4b-iv gliding assay (aorus, TornadoVM PTX backend).
#   ./run_gliding.sh [M]          # CPU cheap probe (default 2000 steps)
#   ./run_gliding.sh -3js threejs_gliding   # viewer
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx6G \
     -Dtornado.tvm.maxbytecodesize=65536 \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.GlidingHarness "$@"
