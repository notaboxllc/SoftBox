#!/bin/bash
# Soft Box — the "1× contractility standard" v1↔v2 parity benchmark (aorus, TornadoVM PTX backend).
# Args pass through to V2OneXHarness:
#   [-cpu]            CPU sequential runner (the priority + v1-comparable baseline; default)
#   [-gpu]            GPU TaskGraph (NOT wired — falls back to CPU with a clear notice; see CLAUDE.md
#                     "Graph resize" blocker for the maximal composition)
#   [-steps N]        number of steps (default 200)
#   [-nodes N] [-nfil N] [-nsing N] [-xlconc V] [-noxlink] [-box V]
#   [-3js <dir>]      host-side Three.js viewer-frame output (~300 frames; off by default)
#
#   ./run_1x.sh                 # the 1× scene, 200-step CPU smoke + steps/s
#   ./run_1x.sh -steps 1000     # longer CPU run
#   ./run_1x.sh -cpu -steps 3000 -3js threejs_output_v2_1x   # watchable run (~300 frames)
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx8G \
     -Dtornado.tvm.maxbytecodesize=16384 \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.V2OneXHarness "$@"
