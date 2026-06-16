#!/bin/bash
# Soft Box increment 5a+5b — passive crosslinker static spring + double-ended fil↔fil gather (5a)
# + Bell-model unbinding / link-lifecycle death half (5b). Pre-placed crosslinkers (no formation —
# that's 5c). Validates 5a: rest hold, stretch-relaxation decay constant (vs the analytic from v1's
# exact arithmetic, + dt-independence), two-pass CSR gather == brute, CPU≡GPU, all-OFF≡HEAD; and 5b:
# P_break+EWMA arithmetic vs v1 (gate), empirical off-rate vs k_off·dt, death→inert, CPU≡GPU break
# path bit-identical, all-OFF≡HEAD (unbinding off ≡ 5a).
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
