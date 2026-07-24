#!/bin/bash
# Explicit-S2 gliding + helical surface binding / twirling (noncanonical, default-off port).
#   -fixtures   port-specific deterministic fixtures (CPU)
#   -equiv      isolated new-kernel CPU/GPU equivalence (device-resident matSurfaceAzim+matSurfaceStericPrune)
#   -campaign   small dynamic gliding twirl campaign (CPU sequential runner — the full gliding device graph does
#               NOT lower on this box, a pre-existing matS2SolveStep PTX fault reproduced by the canonical path)
#   -3js <dir>  dynamic-cycle movies: <dir>_control / <dir>_surface / <dir>_surface_steric
# Flags: -actin-bind-radius-nm <v> -surface-exclusion-nm <v> -density <v> -seed <n> -steps <n> -stride <n>
# For -equiv, add -Dtornado.recover.bailout=false so a lowering failure THROWS (no silent sequential fallback).
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx4G \
     -Dtornado.tvm.maxbytecodesize=16384 -Dtornado.recover.bailout=false \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.ExplicitTwirlGlidingHarness "$@"
