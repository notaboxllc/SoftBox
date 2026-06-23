#!/bin/bash
# Dense contractile COMPUTE benchmark (v2 vs BoA GPU). Matches BoA dense v5 box schedule
# (boxXY=10·√scale, 4000·scale filaments + minifils, ~0.84 xlinks/fil). Args pass to the harness.
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
cd ~/Code/SoftBox
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx${XMX:-16G} \
     -Dtornado.tvm.maxbytecodesize=65536 \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.DenseContractileHarness "$@"
