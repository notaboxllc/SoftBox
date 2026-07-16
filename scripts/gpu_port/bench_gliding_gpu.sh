#!/bin/bash
# Phase 4/6 — end-to-end GPU gliding benchmark (PREPARED; DO NOT run while the sweep is active).
# Runs the device-resident gliding assay for calibrated-s2-l40 (Phase 4) or explicit-s2-l40 (Phase 6,
# only once the beam kernel passes Phase 5) and cross-checks against the CPU-double arbiter (T6 ensemble).
#
# Intended entry: the existing LaserTrapHarness gliding path extended with a -gpu device runner for the
# gpuSupported() models (calibrated). explicit-s2-l40 -gpu stays REFUSED until its beam kernel is promoted.
#   -motor <id>  calibrated-s2-l40 | explicit-s2-l40 (refused until promoted)
#   -density -matx -maty -dur -dt -seeds   (matched to the CPU sweep config for a like-for-like T6 gate)
#   -cpuarbiter   also run the smallest-scale CPU-double cross-check and diff (basin arbiter, per CLAUDE.md)
set -u
cd "$(dirname "$0")/../.." || exit 2
source scripts/gpu_port/_guard.sh
sweep_guard
OUT=RUN_LOGS/gpu_port_dev/gliding_gpu
mkdir -p "$OUT"
TDIR="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx/share/java/tornado"
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
echo "# gliding GPU bench → $OUT.  Disclose the runner (device-resident vs CPU) up front — see CLAUDE.md."
echo "# NOTE: a -gpu request for explicit-s2-l40 MUST be refused (registry gpu=false) until Phase-5/6 promotion."
exec java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx4G \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.LaserTrapHarness "$@" -out "$OUT"
