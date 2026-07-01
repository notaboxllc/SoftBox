#!/bin/bash
# Sphere-head AXIAL SWING-LOCK (§9.4c) — single-motor gate + dense-mat comparison.
#   ./run_axlock.sh                 # single-motor gate (axial fraction lock off/on, Brownian off)
#   ./run_axlock.sh -mat [density]  # dense GPU mat: -spherehead vs -spherehead -axlock (grid velFitX)
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
J="java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx6G -Dtornado.tvm.maxbytecodesize=65536 -cp $TDIR/tornado-api-4.0.1-dev.jar:."
if [ "$1" == "-mat" ]; then
  D=${2:-500}
  echo "=== BASELINE -spherehead d$D ==="; $J softbox.GlidingHarness -gpu -matbed -grid -spherehead -density $D -seed 0 40000 | grep -E "GRID_ROW|COV_ROW"
  echo "=== -axlock d$D ==="; $J softbox.GlidingHarness -gpu -matbed -grid -axlock -density $D -seed 0 40000 | grep -E "GRID_ROW|COV_ROW"
else
  $J softbox.AxLockGateHarness "$@"
fi
