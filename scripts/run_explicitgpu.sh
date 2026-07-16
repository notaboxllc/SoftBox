#!/bin/bash
# Part C — explicit-S2 analytic beam GPU kernel: lowering probe / device fixture gate / microbench.
# GPU device path (RTX 5070). No silent fallback for the probe.
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx8G \
     -Dtornado.tvm.maxbytecodesize=65536 -Dtornado.recover.bailout=false -Dtornado.enable.fma=false \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.ExplicitBeamGpuHarness "$@"
