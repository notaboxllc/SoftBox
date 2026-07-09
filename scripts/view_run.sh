#!/bin/bash
# Dump a small free-rod viz run to Three.js frames, then print how to view it.
#   ./view_run.sh [N [M]]      (defaults: N=200 rods, M=20000 steps)
# Output-only: uses the -3js frame-dump path; does not run / affect the FDT validation.
set -e
cd "$(dirname "$0")"
./build.sh
OUTDIR="threejs_output"
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx4G \
     -Dtornado.tvm.maxbytecodesize=16384 \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.DiffusionHarness -3js "$OUTDIR" "$@"
echo
echo "To view:  cd ~/Code/SoftBox && python3 sim_server.py 8000"
echo "Then open http://localhost:8000/sim_viewer_boa.html  (use the Recent picker, newest first)"
