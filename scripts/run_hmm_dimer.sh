#!/bin/bash
# SoftBox — Explicit HMM-like myosin dimer characterisation (CPU-ONLY).
# A two-headed HMM dimer built from the flagship explicit S2 motor (explicit-s2-l40): two explicit
# converter heads whose pivots tie to a common junction via a shared paired-S2 beam forked from the
# 4G beam. NEW default-off architecture; the single-head explicit model is preserved byte-identical.
# CPU-only (the explicit two-body arc is CPU-only). Refuses -gpu by construction (no device path yet).
#
#   ./scripts/run_hmm_dimer.sh        # all 5 characterisation gates + report
#
# Design + audit: docs/matsoa/EXPLICIT_HMM_DIMER_DESIGN.md ; report: RUN_LOGS/explicit_hmm_dimer/
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
# CPU sequential runner: plain JVM (no TornadoVM device execution). @tornado-argfile only for
# classpath/preview parity.
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx2G \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.ExplicitHmmDimerHarness "$@"
