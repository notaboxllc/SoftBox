#!/bin/bash
# Soft Box increment 5a — passive crosslinker static spring + double-ended fil↔fil gather.
# Two parallel single-segment filaments + one STATIC crosslinker (pre-placed; no formation/
# unbinding/torsion — those are 5b/5c/later). Validates: rest hold, perpendicular-stretch
# relaxation decay constant (vs the analytic from v1's exact arithmetic, + dt-independence),
# two-pass CSR gather == brute (bit-identical), CPU≡GPU, all-OFF≡HEAD.
#
#   ./run_xlink.sh            # GPU TaskGraph + CPU cross-check (default M=4000)
#   ./run_xlink.sh -cpu       # CPU runner only (triage)
#   ./run_xlink.sh -3js threejs_xlink   # dump viewer frames (off-rest pair relaxing)
set -e
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx800M \
     -Dtornado.tvm.maxbytecodesize=16384 \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.CrosslinkerHarness "$@"
