#!/bin/bash
# SoftBox — Explicit HMM dimer DYNAMIC one-dimer -3js visualization assay (CPU-ONLY).
# One anchored explicit-hmm-dimer-s2-l40 + one real dynamic actin filament; each head runs the
# PRODUCTION per-head bind gate + cycleLymnTaylor chemistry + power stroke + detachment, coupled
# through the shared forked S2 tail (ExplicitHmmDimer.solve replaces the two independent s2SolveM).
#
#   ./scripts/run_hmm_dimer_3js.sh -scan                                  # seed/placement scan
#   ./scripts/run_hmm_dimer_3js.sh -3js threejs_hmm_dimer -seed N -steps M  # export the movie
# Flags: -seed -steps -stride -splay -gap -nseg
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx2G \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.ExplicitHmmDimer3jsHarness "$@"
