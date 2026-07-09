#!/bin/bash
# Soft Box increment-6 glide part 1 — dimer-glide test (aorus, TornadoVM PTX backend).
# Args:  [-cpu] [-3js dir].
#   ./run_dimerglide.sh              # GPU + CPU cross-check: #1 transmission, #2 gate, #3 translocation, #5 all-OFF
#   ./run_dimerglide.sh -cpu         # CPU runner only (triage)
#   ./run_dimerglide.sh -3js threejs_dimerglide   # viewer (free dimers walking on a pinned filament)
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx4G \
     -Dtornado.tvm.maxbytecodesize=16384 \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.DimerGlideHarness "$@"
