#!/bin/bash
# Soft Box increment-7 AGING build (A): per-segment nucleotide proxy + nucleotide-dependent depoly rates
# (aorus, TornadoVM PTX backend). Args pass through to AgingHarness: [-cpu].
#   ./run_aging.sh        # GPU + CPU cross-check (aging-kinetics, asymmetric C_c, conservation, CPU≡GPU, fixed-baseline)
#   ./run_aging.sh -cpu   # CPU runner only (triage)
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx4G \
     -Dtornado.tvm.maxbytecodesize=16384 \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.AgingHarness "$@"
