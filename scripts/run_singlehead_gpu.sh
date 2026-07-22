#!/bin/bash
# SoftBox — Explicit SINGLE-HEAD (explicit-s2-l40) gliding density-sweep PRODUCTION CELL on the GPU.
# One (density,seed) cell → per-cell JSON, matched to the HMM-dimer campaign (run_hmm_gpu.sh -production-cell).
# GPU device-resident TornadoVM TaskGraph (buildGlidingGraph, explicit-s2-l40); no CPU fallback (bailout=false).
# Frozen config enforced + printed + abort-gated inside runProductionCell.
#   ./scripts/run_singlehead_gpu.sh -production-cell -density 300 -seed 101 -steps 40000 -outdir RUN_LOGS/single_head_density_sweep_long -rev <hash>
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
cd "$(dirname "$0")/.." || exit 2

java @"$TORNADOVM_HOME"/tornado-argfile --enable-preview -Xmx8G \
     -Dtornado.recover.bailout=false -Dtornado.enable.fma=false -Dtornado.tvm.maxbytecodesize=65536 \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.ExplicitCompleteMatHarness "$@"
exit $?
