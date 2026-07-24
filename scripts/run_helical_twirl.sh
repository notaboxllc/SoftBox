#!/bin/bash
# Helical surface binding + minimal twirling drive (noncanonical, default-off prototype).
#   (no args)   deterministic fixtures (28) + verdict           [CPU compute; add -cpu to force CPU banner]
#   -equiv      CPU/GPU kernel equivalence (device-resident)    [needs GPU]
#   -campaign   small dynamic twirl campaign (CPU sequential)
#   -all        fixtures + equivalence + campaign
# Flags: -helical-surface-bind -actin-bind-radius-nm <v> -surface-exclusion-nm <v> -no-surface-exclusion
#        -rollspring -dt <v> -seed <n>
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx4G \
     -Dtornado.tvm.maxbytecodesize=16384 \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.HelicalSurfaceTwirlHarness "$@"
