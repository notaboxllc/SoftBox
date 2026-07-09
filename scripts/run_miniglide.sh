#!/bin/bash
# Soft Box increment-6 glide part 2 — minifilament-glide test (aorus, TornadoVM PTX backend).
# A pre-placed static minifilament whose heads bind/walk on a pinned filament; the 6b single-ended
# backbone gather carries the real cross-bridge load.
# Args:  [-cpu] [-3js dir].
#   ./run_miniglide.sh              # GPU + CPU cross-check: #1 gather-under-load, #2 gates, #3 bipolar, #5 all-OFF
#   ./run_miniglide.sh -cpu         # CPU runner only (triage)
#   ./run_miniglide.sh -3js threejs_miniglide   # viewer (a minifilament's heads walking on a pinned filament)
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx4G \
     -Dtornado.tvm.maxbytecodesize=16384 \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.MiniGlideHarness "$@"
