#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
java @"$TORNADOVM_HOME/tornado-argfile" --enable-preview -Xmx1G \
  -cp "$TDIR/tornado-api-4.0.1-dev.jar:." softbox.J2TorsionHarness
