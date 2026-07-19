#!/bin/bash
# SoftBox — Explicit HMM dimer EMERGENT FORWARD-vs-BACKWARD second-head accessibility study (CPU-ONLY).
# With the 5.4 nm same-filament occupancy exclusion enforced, does first-head binding + power stroke naturally
# shift the free head's accessible search volume / second-head binding toward the barbed end? Purely diagnostic:
# NO explicit forward gate / preferred sign / processivity / inter-head coordination is added.
#
#   ./scripts/run_hmm_dimer_forward.sh -maps                       # deterministic accessibility maps + conditions + validation
#   ./scripts/run_hmm_dimer_forward.sh -natural -seeds 40 -steps 16000   # natural-event aggregation + continuous search
#   ./scripts/run_hmm_dimer_forward.sh                             # both (natural at a modest default)
# Findings: docs/matsoa/EXPLICIT_HMM_DIMER_FORWARD_ACCESSIBILITY_FINDINGS.md
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx2G \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.ExplicitHmmDimerForwardHarness "$@"
