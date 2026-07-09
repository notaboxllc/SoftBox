#!/bin/bash
# Soft Box increment-7 Stage 0+1 actin TURNOVER (pointed-end depoly + filament death + slot-recycle) test
# (aorus, TornadoVM PTX backend). Args pass through to DepolyHarness: [-cpu].
#   ./run_depoly.sh        # GPU + CPU cross-check (behavior, conservation, slot-recycle, link-break, no-op, rate, CPU≡GPU)
#   ./run_depoly.sh -cpu   # CPU runner only (triage)
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx4G \
     -Dtornado.tvm.maxbytecodesize=16384 \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.DepolyHarness "$@"
