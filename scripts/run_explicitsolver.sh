#!/bin/bash
# B3/B4 — EXPLICIT_S2_L40 analytic Newton solver equivalence gate (CPU-only, double). No GPU, no model change.
#   ./scripts/run_explicitsolver.sh | tee RUN_LOGS/explicit_jac/solver_gate.txt
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx2G \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.ExplicitBeamSolverGateHarness "$@"
