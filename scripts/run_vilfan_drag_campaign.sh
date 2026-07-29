#!/bin/bash
# Parallel resumable driver for the Vilfan overdamped finite-drag campaigns.
#   CPU ONLY. Hard budget: 3 concurrent single-threaded JVMs. No CUDA/TornadoVM/TaskGraph.
#   ./scripts/run_vilfan_drag_campaign.sh paper|native|controls|visc|all [jobs]
set -euo pipefail
cd "$(dirname "$0")/.."
STAGE="${1:-all}"; JOBS="${2:-3}"
mkdir -p RUN_LOGS/vilfan_drag
LOG="RUN_LOGS/vilfan_drag/driver_${STAGE}.log"
echo "=== vilfan-drag campaign stage=${STAGE} jobs=${JOBS} $(date -Is) ===" | tee -a "$LOG"
PENDING=$(./scripts/run_vilfan_drag.sh -list "$STAGE" 2>/dev/null)
if [ -z "$PENDING" ]; then echo "nothing pending." | tee -a "$LOG"; else
  echo "$PENDING" | tee -a "$LOG"
  echo "$PENDING" | xargs -P "$JOBS" -I{} \
    nice -n 17 java -XX:ActiveProcessorCount=1 -XX:+UseSerialGC -Xss64m -Xmx2g --enable-preview \
         -cp . softbox.VilfanDragHarness -vilfan-drag -arm {} 2>&1 | tee -a "$LOG"
fi
echo "=== done $(date -Is) ===" | tee -a "$LOG"
