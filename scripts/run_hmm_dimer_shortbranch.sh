#!/bin/bash
# SoftBox — Explicit HMM dimer SHORT-BRANCH study (CPU-ONLY).
# Varies the proximal branch length (3/5/6/8/10 nm; shared S2 = 40 − Lb) at the relaxed fork (α=10°)
# and compares branch bending compliance (branchEI × {0.25,0.5}) for the emergent doubly-bound ADP
# axial geometry, preserved stroke, second-head attachment, moderate coupling, and numerical health.
# The distal shared-S2 material (EA/EI) is FROZEN. Reduces EXACTLY to the reference model at Lb=10 nm.
#
#   ./scripts/run_hmm_dimer_shortbranch.sh -sweep                                   # matrix + controls + scorecard + status
#   ./scripts/run_hmm_dimer_shortbranch.sh -static  -branchlen 6 -alpha 10 -branchei 0.25
#   ./scripts/run_hmm_dimer_shortbranch.sh -offsets -branchlen 6 -alpha 10 -branchei 0.25
#   ./scripts/run_hmm_dimer_shortbranch.sh -optionb                                 # Option-A vs Option-B feasibility
# Findings: docs/matsoa/EXPLICIT_HMM_DIMER_SHORT_BRANCH_FINDINGS.md
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx2G \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.ExplicitHmmDimerShortBranchHarness "$@"
