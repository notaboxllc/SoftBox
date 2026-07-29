#!/bin/bash
# Parallel, resumable driver for the Vilfan-COMPLETE reference campaigns.
#
#   CPU ONLY. Hard budget: 3 concurrent single-threaded JVMs => at most 3 logical cores.
#   Nothing here touches CUDA, TornadoVM, a TaskGraph or a device context; the GPU is
#   owned by the low-ATP study.
#
# Resumable: an arm whose record already exists is skipped, so re-running after an
# interruption completes only what is missing. Records are written atomically
# (write .tmp, then rename), so a killed run can never leave a half-written record.
#
#   ./scripts/run_vilfan_complete_campaign.sh paper|nodep|sweep|native|alpha|all
set -euo pipefail
cd "$(dirname "$0")/.."

STAGE="${1:-all}"
JOBS="${2:-3}"

mkdir -p RUN_LOGS/vilfan_complete
LOG="RUN_LOGS/vilfan_complete/driver_${STAGE}.log"

echo "=== vilfan-complete campaign: stage=${STAGE} jobs=${JOBS} $(date -Is) ===" | tee -a "$LOG"

PENDING=$(./scripts/run_vilfan_complete.sh -list "$STAGE")
if [ -z "$PENDING" ]; then
  echo "nothing pending for stage ${STAGE}." | tee -a "$LOG"
else
  echo "$PENDING" | tee -a "$LOG"
  echo "$PENDING" | xargs -P "$JOBS" -I{} \
      nice -n 17 java -XX:ActiveProcessorCount=1 -XX:+UseSerialGC -Xss16m -Xmx2g --enable-preview \
           -cp . softbox.VilfanCompleteHarness -vilfan-complete -arm {} 2>&1 | tee -a "$LOG"
fi

echo "=== done $(date -Is) ===" | tee -a "$LOG"
./scripts/run_vilfan_complete.sh -report 2>&1 | tee -a "$LOG"
