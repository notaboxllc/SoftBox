#!/bin/bash
# Soft Box increment-3 broad-phase test (aorus, TornadoVM PTX backend). Args pass
# through to BroadPhaseHarness:  [-cpu] [N [M]].  e.g.  ./run_grid.sh 512 2000
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx4G \
     -Dtornado.tvm.maxbytecodesize=16384 \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.BroadPhaseHarness "$@"
