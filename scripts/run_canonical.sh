#!/bin/bash
# Canonical lever-arm motor — build + native-behavior characterization (aorus, TornadoVM PTX backend).
# Two-point head pin + J1-carried stroke at the tail + lever-strain load. Calibration DEFERRED (phase 2).
#   ./run_canonical.sh            # full characterization (CPU measurements + GPU CPU≡GPU cross-check)
#   ./run_canonical.sh -cpu       # CPU only (skip the GPU cross-check)
#   ./run_canonical.sh -3js DIR   # viewer frames (canonical motors cycling on a pinned filament)
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx4G \
     -Dtornado.tvm.maxbytecodesize=16384 \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.CanonicalMotorHarness "$@"
