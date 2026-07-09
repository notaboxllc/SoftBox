#!/bin/bash
# Soft Box — the "1× contractility standard" v1↔v2 parity benchmark (aorus, TornadoVM PTX backend).
# Args pass through to V2OneXHarness:
#   [-cpu]            CPU sequential runner (the priority + v1-comparable baseline; default)
#   [-gpu]            GPU device-resident path (5 chained TaskGraphs: fdBind·fdStruct·fdFil·fdInteg·fdXForm[gated])
#   [-devicecsr]      revert the dynamic seg CSR to the device single-thread scan (default = CSR-host)
#   [-cmp]            run BOTH runners from an identical IC + report Δ (CPU≡GPU gate); add -brownoff for the
#                     deterministic bit-check IC (bound-set must match; coord Δ is bounded scheme noise)
#   [-steps N]        number of steps (default 200)
#   [-nodes N] [-nfil N] [-nsing N] [-xlconc V] [-noxlink] [-box V]
#   [-3js <dir>]      host-side Three.js viewer-frame output (~300 frames; off by default)
#
#   ./run_1x.sh                 # the 1× scene, 200-step CPU smoke + steps/s
#   ./run_1x.sh -gpu -steps 2000            # GPU device-resident benchmark (~50 steps/s @ 1×)
#   ./run_1x.sh -gpu -cmp -brownoff -steps 50   # deterministic CPU≡GPU bit-check
#   ./run_1x.sh -cpu -steps 3000 -3js threejs_output_v2_1x   # watchable run (~300 frames)
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx8G \
     -Dtornado.tvm.maxbytecodesize=16384 \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.V2OneXHarness "$@"
