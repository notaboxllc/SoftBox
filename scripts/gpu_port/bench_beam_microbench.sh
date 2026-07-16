#!/bin/bash
# Phase 5 — EXPLICIT-S2 beam-solver GPU microbenchmark (PREPARED; DO NOT run while the sweep is active).
# Runs the standalone fixed-contour L40 beam kernel over thousands of saved fixture configs and compares
# CPU vs GPU: node positions, end pose, reaction F/torque, contour preservation, bending energy, iteration
# count, convergence/failure rate. Reports DISTRIBUTIONS (median/p95/max), not only maxima.
#
# Intended harness: softbox.BeamMicrobenchHarness (created in the kernel-authoring phase).
#   -fixtures <dir>   fixture configs (default RUN_LOGS/gpu_port_dev/fixtures)
#   -n <count>        number of configs to sweep (default: all)
#   -cpu | -gpu       runner (default runs BOTH and diffs)
#   -prec float|double  precision sweep for the GPU beam (default double; float expected to FAIL the FD tangent)
set -u
cd "$(dirname "$0")/../.." || exit 2
source scripts/gpu_port/_guard.sh
sweep_guard          # refuses if the production sweep is active / cwd is the sweep tree / GPU busy
OUT=RUN_LOGS/gpu_port_dev/beam_microbench
mkdir -p "$OUT"
TDIR="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx/share/java/tornado"
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
echo "# beam microbench → $OUT (log every GPU call in docs/gpu_port/GPU_INVOCATION_LOG.md)"
exec java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx4G \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.BeamMicrobenchHarness -fixtures RUN_LOGS/gpu_port_dev/fixtures -out "$OUT" "$@"
