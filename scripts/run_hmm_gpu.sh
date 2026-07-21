#!/bin/bash
# SoftBox — Explicit HMM dimer GPU backend (Phase G1a CPU↔flat-kernel validation).
# Builds, then runs the deterministic (Brownian-off) fixture suite V1–V6 + 100-step lockstep comparisons
# of the flat forked-mechanics kernel (ExplicitHmmDimerGpu) against the object CPU oracle
# (ExplicitHmmDimer.solve). Exits nonzero on any failed fixture.  No TornadoVM device execution (G1a is CPU).
#   ./scripts/run_hmm_gpu.sh -g1a-validate
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
cd "$(dirname "$0")/.." || exit 2

if [[ "$1" == "-g1a-validate" || "$1" == "" ]]; then
    ./scripts/build.sh || exit 2
    java @"$TORNADOVM_HOME"/tornado-argfile --enable-preview -Xmx2G \
         -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
         softbox.ExplicitHmmDimerGlidingHarness -backend gpu-validate -gpu-g1a-validate -branchEA 0.03
    exit $?
fi

if [[ "$1" == "-g1b-validate" || "$1" == "-g23-validate" || "$1" == "-g4a-validate" || "$1" == "-g4b-validate" || "$1" == "-g4c-validate" || "$1" == "-g4d-validate" || "$1" == "-g5-active-validate" || "$1" == "-rupture-validate" ]]; then
    case "$1" in
      -g23-validate) MODEFLAG="-gpu-g23-validate";;
      -g4a-validate) MODEFLAG="-gpu-g4a-validate";;
      -g4b-validate) MODEFLAG="-gpu-g4b-validate";;
      -g4c-validate) MODEFLAG="-gpu-g4c-validate";;
      -g4d-validate) MODEFLAG="-gpu-g4d-validate";;
      -g5-active-validate) MODEFLAG="-gpu-g5-active-validate";;
      -rupture-validate) MODEFLAG="-gpu-rupture-validate";;
      *)             MODEFLAG="-gpu-g1b-validate";;
    esac
    shift
    ./scripts/build.sh || exit 2
    # Device path: no silent CPU fallback (bailout=false), PTX FMA off, raised bytecode cap for the FD-tangent kernel.
    # fullInlining=true bypasses the 600-node per-callee inline cap (the FD-tangent solve inlines to ~984 nodes).
    java @"$TORNADOVM_HOME"/tornado-argfile --enable-preview -Xmx8G \
         -Dtornado.recover.bailout=false -Dtornado.enable.fma=false -Dtornado.tvm.maxbytecodesize=262144 \
         -Dtornado.compiler.fullInlining=true \
         -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
         softbox.ExplicitHmmDimerGlidingHarness -backend gpu-validate "$MODEFLAG" -gpu-experimental -branchEA 0.03 "$@"
    exit $?
fi

# passthrough for other GPU-backend flags later (G1b+)
java @"$TORNADOVM_HOME"/tornado-argfile --enable-preview -Xmx8G \
     -Dtornado.recover.bailout=false -Dtornado.enable.fma=false -Dtornado.tvm.maxbytecodesize=65536 \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.ExplicitHmmDimerGlidingHarness "$@"
