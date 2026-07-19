#!/bin/bash
# SoftBox — Explicit HMM dimer PROXIMAL-FORK RELAXATION study (CPU-ONLY).
# Sweeps fork rest half-angle α × proximal-branch bending compliance (branchEI) to reduce head
# coincidence and populate structurally-plausible two-head-bound geometries WITHOUT touching the
# distal shared S2. Static fixtures + multi-seed production-path dynamic runs + scorecard.
#
#   ./scripts/run_hmm_dimer_fork.sh -sweep                          # α×branchEI matrix + scorecard + status block
#   ./scripts/run_hmm_dimer_fork.sh -static -alpha 10 -branchei 0.5 # 5 static fixtures for one config
#   ./scripts/run_hmm_dimer_fork.sh -offsets -alpha 10 -branchei 0.5
# Findings: docs/matsoa/EXPLICIT_HMM_DIMER_FORK_RELAXATION_FINDINGS.md
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx2G \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.ExplicitHmmDimerForkHarness "$@"
