#!/bin/bash
# Reduced twirling-mechanism atlas (aorus). CPU-ONLY, self-contained, noncanonical, default-OFF from production.
# This harness has NO TornadoVM dependency (pure Java over double[]), so it runs on a plain JVM — no GPU, no
# device monitoring required (CPU-first policy for the exploratory atlas). The tornado-api jar is only on the
# classpath so the softbox package (which other classes reference) resolves; no device code is loaded.
#   ./scripts/run_twirl_mechanism_atlas.sh            # -all : full atlas
#   ./scripts/run_twirl_mechanism_atlas.sh -stage1    # one stage
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
java --enable-preview -Xmx4G \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.TwirlReducedMotorHarness "$@"
