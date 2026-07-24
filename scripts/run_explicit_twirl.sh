#!/bin/bash
# Explicit-S2 gliding + helical surface binding / twirling (noncanonical, default-off port).
#   -fixtures   port-specific deterministic fixtures (CPU)
#   -equiv      isolated new-kernel CPU/GPU equivalence (device-resident matSurfaceAzim+matSurfaceStericPrune)
#   -campaign   small dynamic gliding twirl campaign (CPU sequential runner — the full gliding device graph does
#               NOT lower on this box, a pre-existing matS2SolveStep PTX fault reproduced by the canonical path)
#   -3js <dir>  dynamic-cycle movies: <dir>_control / <dir>_surface / <dir>_surface_steric
# Flags: -actin-bind-radius-nm <v> -surface-exclusion-nm <v> -density <v> -seed <n> -steps <n> -stride <n>
# DEVICE FLAGS (match the known-good run_singlehead_gpu.sh — REQUIRED for the explicit-S2 gliding graph to lower):
#   -Dtornado.enable.fma=false     ← the explicit-S2 solver matS2SolveStep FMA-lowers to an ArithmeticLIRLowerable
#                                     NPE on the PTX backend with FMA ON; OFF ⇒ lowers device-resident. (regression:
#                                     an earlier version of this script OMITTED it ⇒ the full gliding graph bailed.)
#   -Dtornado.recover.bailout=false  ← a lowering failure THROWS (no silent sequential CPU fallback)
#   -Dtornado.tvm.maxbytecodesize=65536  ← the large solver kernel needs the raised cap
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx8G \
     -Dtornado.recover.bailout=false -Dtornado.enable.fma=false -Dtornado.tvm.maxbytecodesize=65536 \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.ExplicitTwirlGlidingHarness "$@"
