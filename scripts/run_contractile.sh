#!/bin/bash
# Soft Box increment 6 — minimal contractile assay (aorus, TornadoVM PTX backend).
# Two anti-parallel pinned filament chains + a central bipolar minifilament; tension read at the pins.
# Args:  [-cpu] [-3js dir] [-steps N].
#   ./run_contractile.sh                 # GPU + CPU cross-check: #1 crux, #2 contracts, #3 CPU≡GPU, #4 control
#   ./run_contractile.sh -cpu            # CPU runner only (triage)
#   ./run_contractile.sh -3js threejs_contractile   # viewer (the v1 contractility panel)
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx4G \
     -Dtornado.tvm.maxbytecodesize=16384 \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.ContractileAssayHarness "$@"
