#!/bin/bash
# Explicit single-head device-resident vertical slice.
# Device path: -Dtornado.recover.bailout=false (no silent fallback) + -Dtornado.enable.fma=false (PTX FMA NPEs otherwise).
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx8G \
     -Dtornado.recover.bailout=false -Dtornado.enable.fma=false -Dtornado.tvm.maxbytecodesize=65536 \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.ExplicitSingleHeadHarness "$@"
