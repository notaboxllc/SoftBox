#!/bin/bash
# Soft Box increment-6c Stage-B1 FilamentStore runtime-birth lifecycle test (aorus, TornadoVM PTX backend).
# Args pass through to FilamentBirthHarness: [-cpu].
#   ./run_filbirth.sh        # GPU + CPU cross-check (allocator, born≡preplaced, inert free slot, binding+gather)
#   ./run_filbirth.sh -cpu   # CPU runner only (triage)
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx4G \
     -Dtornado.tvm.maxbytecodesize=16384 \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.FilamentBirthHarness "$@"
