#!/bin/bash
# Soft Box increment 5d — O(N) crosslinker FORMATION via the entity-agnostic grid + a fused per-segment
# 27-cell neighborhood query (FormationGrid + CrosslinkerSystem.gridForm*), retiring the O(N²) all-pairs
# filFilCandidates (the last quadratic/serial kernel). Validates the FORMATION==BRUTE gate (O(N) grid links
# == O(N²) brute links, BIT-IDENTICAL, under churn) and CPU≡GPU bit-identical formation.
#
#   ./run_xlinkform.sh            # GPU TaskGraph + CPU cross-check (FORMATION==BRUTE + CPU≡GPU)
#   ./run_xlinkform.sh -cpu       # CPU runner only (FORMATION==BRUTE)
#   ./run_xlinkform.sh -nfil 400 -steps 600   # larger/longer churn
set -e
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx2G \
     -Dtornado.tvm.maxbytecodesize=16384 \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.XlinkFormationHarness "$@"
