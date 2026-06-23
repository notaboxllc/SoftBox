#!/bin/bash
# MEASUREMENT-ONLY profiling of the FullSystemDemo device-resident split path (per-step time/transfer budget).
# Additive instrumentation on branch profile-fulldemo; production path untouched. Args pass through:
#   -profile -profwarm N -profsteps N -proflog N [-frozen] [-dense] [-boxxy f] ...
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx12G \
     -Dtornado.tvm.maxbytecodesize=16384 \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.FullSystemDemoHarness -profile "$@"
