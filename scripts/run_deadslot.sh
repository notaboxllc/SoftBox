#!/bin/bash
# Soft Box increment-7 DEAD-SLOT REUSE FIX: turnover + nucleation coexistence (the ring precondition).
# A recycled (previously-dead) slot reused by nucleation births a proper fresh-ATP seed of length actinSeed;
# conservation exact; CPU≡GPU. Args pass through to DeadSlotReuseHarness: [-cpu].
#   ./run_deadslot.sh        # GPU + CPU cross-check (newborn, conservation, fix-off control, regression, CPU≡GPU)
#   ./run_deadslot.sh -cpu   # CPU runner only (triage)
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx4G \
     -Dtornado.tvm.maxbytecodesize=16384 \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.DeadSlotReuseHarness "$@"
