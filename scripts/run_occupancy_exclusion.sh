#!/bin/bash
# CONTINUOUS LOCAL ACTIN CO-OCCUPANCY EXCLUSION — deterministic unit/geometry tests + CPU/GPU event-identity.
# Named noncanonical experimental extension (default OFF); ported into the canonical explicit-S2 single-head path.
#   ./scripts/run_occupancy_exclusion.sh -fixtures   # 12 deterministic unit + geometry tests (CPU-only)
#   ./scripts/run_occupancy_exclusion.sh -equiv      # single-head explicit-S2 CPU/GPU event-identity (GPU)
#   ./scripts/run_occupancy_exclusion.sh             # both
# Findings: docs/helical_binding/CONTINUOUS_OCCUPANCY_EXCLUSION_FINDINGS.md
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
cd "$(dirname "$0")/.." || exit 2
java @"$TORNADOVM_HOME"/tornado-argfile --enable-preview -Xmx8G \
     -Dtornado.recover.bailout=false -Dtornado.enable.fma=false -Dtornado.tvm.maxbytecodesize=65536 \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.ContinuousOccupancyExclusionHarness "$@"
