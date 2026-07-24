#!/bin/bash
# Vilfan-style stereospecific target-zone binding in the explicit-S2 gliding assay (noncanonical, default-off).
#   -fixtures   Stage-A deterministic fixtures (mismatch/weight/symmetry + the kinematic moving-target-zone rig)
#   -equiv      FULL target-zone gliding graph device-resident + CPU/GPU event identity
#   -stageA     the dynamic alphaPsi experiment (0,4,6,8) + symmetry controls
#   -dt         timestep check (matched sim time)
#   -3js <dir>  movies: <dir>_blind / <dir>_targetzone
#   -all        fixtures + equiv + stageA + dt
# Flags: -target-zone-alpha <v> -density <v> -seed <n> -seeds <n> -steps <n> -stride <n> -gpu
#        -actin-bind-radius-nm <v> -surface-exclusion-nm <v>
#
# DEVICE FLAGS (the known-good explicit-S2 set — see docs/EXPLICIT_S2_GPU_LOWERING_REGRESSION_FINDINGS.md):
#   -Dtornado.enable.fma=false        REQUIRED: matS2SolveStep FMA-lowers to an ArithmeticLIRLowerable NPE on PTX
#   -Dtornado.recover.bailout=false   a lowering failure THROWS (no silent sequential CPU fallback)
#   -Dtornado.tvm.maxbytecodesize=65536
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx8G \
     -Dtornado.recover.bailout=false -Dtornado.enable.fma=false -Dtornado.tvm.maxbytecodesize=65536 \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.VilfanTargetZoneHarness "$@"
