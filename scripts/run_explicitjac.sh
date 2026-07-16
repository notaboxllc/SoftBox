#!/bin/bash
# B4 — EXPLICIT_S2_L40 analytic beam residual+Jacobian derivative-validation gate (CPU-only, double).
# Compares the analytic force + tangent (softbox.ExplicitBeamAnalytic) against high-accuracy central
# differences of the SAME frozen beam energy/force over a large ensemble of poses. No GPU, no model change.
#
#   ./scripts/run_explicitjac.sh                 # run the gate, print report to stdout
#   ./scripts/run_explicitjac.sh | tee RUN_LOGS/explicit_jac/gate.txt
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx2G \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.ExplicitBeamJacobianHarness "$@"
