#!/bin/bash
# Soft Box increment-6c actin polymerization (barbed-end elongation: lengthen + split) test (aorus, TornadoVM PTX backend).
# Args pass through to GrowthHarness: [-cpu].
#   ./run_growth.sh        # GPU + CPU cross-check (lengthen, split@64, rate+pool, growing-end, no-op, drag-clamp, participates, CPU≡GPU)
#   ./run_growth.sh -cpu   # CPU runner only (triage)
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx4G \
     -Dtornado.tvm.maxbytecodesize=16384 \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.GrowthHarness "$@"
