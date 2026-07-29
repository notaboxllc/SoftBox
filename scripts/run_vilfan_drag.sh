#!/bin/bash
# Vilfan overdamped finite-drag study — CPU ONLY, low priority, resumable.
#   NO CUDA. NO TornadoVM. NO TaskGraph. NO device context.
#   ./scripts/run_vilfan_drag.sh -drag-audit | -drag-gates | -od-paper | -od-native | -od-controls | -od-visc
set -euo pipefail
cd "$(dirname "$0")/.."
exec nice -n 17 java -XX:ActiveProcessorCount=1 -XX:+UseSerialGC -Xss64m -Xmx2g --enable-preview \
     -cp . softbox.VilfanDragHarness -vilfan-drag "$@"
