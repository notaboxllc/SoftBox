#!/bin/bash
# Parallel resumable driver. CPU ONLY, at most 3 concurrent single-threaded JVMs.
set -euo pipefail
cd "$(dirname "$0")/.."
STAGE="${1:-all}"; JOBS="${2:-3}"
mkdir -p RUN_LOGS/vilfan_lowocc
LOG="RUN_LOGS/vilfan_lowocc/driver_${STAGE}.log"
echo "=== vilfan-lowocc stage=${STAGE} jobs=${JOBS} $(date -Is) ===" | tee -a "$LOG"
P=$(./scripts/run_vilfan_lowocc.sh -list "$STAGE" 2>/dev/null)
if [ -z "$P" ]; then echo "nothing pending." | tee -a "$LOG"; else
  echo "$P" | tee -a "$LOG"
  echo "$P" | xargs -P "$JOBS" -I{} nice -n 17 java -XX:ActiveProcessorCount=1 -XX:+UseSerialGC \
       -Xss64m -Xmx2g --enable-preview -cp . softbox.VilfanLowOccHarness -vilfan-lowocc -arm {} 2>&1 | tee -a "$LOG"
fi
echo "=== done $(date -Is) ===" | tee -a "$LOG"
