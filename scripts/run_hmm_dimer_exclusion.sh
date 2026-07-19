#!/bin/bash
# SoftBox — Explicit HMM dimer SAME-FILAMENT BOUND-SITE OCCUPANCY EXCLUSION study (CPU-ONLY).
# Once one head is bound, the partner may not bind < 5.4 nm (one actin-monomer spacing) away along the SAME
# filament (symmetric; no explicit forward bias). Deterministic fixtures + control(OFF)-vs-test(5.4nm) dynamic.
#
#   ./scripts/run_hmm_dimer_exclusion.sh -fixtures   # the 8 deterministic validation fixtures
#   ./scripts/run_hmm_dimer_exclusion.sh -compare    # control vs test dynamic + status block
#   ./scripts/run_hmm_dimer_exclusion.sh             # both
# A representative -3js trajectory with the exclusion on:
#   ./scripts/run_hmm_dimer_3js.sh -3js threejs_hmm_dimer_excl -seed 3 -steps 16000 -stride 15 -splay 16 -gap 3.0 -nseg 12 -alpha 10 -branchei 0.25 -branchlen 10 -excl 5.4
# Findings: docs/matsoa/EXPLICIT_HMM_DIMER_SITE_EXCLUSION_FINDINGS.md
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx2G \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.ExplicitHmmDimerSiteExclusionHarness "$@"
