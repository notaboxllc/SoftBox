#!/bin/bash
# SoftBox — Explicit HMM dimer ENSEMBLE gliding assays (CPU-ONLY).
# Assay A (mobile actin over anchored dimers) + Assay B (fixed actin, mobile assembly);
# directional modes D0/D1/D2 (5.4 nm exclusion always on); tests whether ensemble motion
# supplies directional bias with NO imposed rearward rule and NO new conformational mechanism.
#   ./scripts/run_hmm_gliding.sh -smoke
#   ./scripts/run_hmm_gliding.sh -density [-assayB] -seeds N -steps M
#   ./scripts/run_hmm_gliding.sh -e3 | -load | -events | -dtconv
#   ./scripts/run_hmm_gliding.sh -3js <dir> -dmode 0 -seed N -steps M [-assayB]
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx2G \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.ExplicitHmmDimerGlidingHarness "$@"
