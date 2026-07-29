#!/bin/bash
# Vilfan-COMPLETE reference mode — CPU ONLY, low priority, resumable.
#
#   NO CUDA. NO TornadoVM. NO TaskGraph. NO device context.
#   The GPU is owned by the low-ATP study; this launcher deliberately puts nothing on it.
#
# Concurrency budget: at most 3 logical cores in total (each JVM is pinned to one).
#
#   ./scripts/run_vilfan_complete.sh -gates          Stage 3 unit + analytical gates
#   ./scripts/run_vilfan_complete.sh -landscape      static target-zone landscape diagnostic
#   ./scripts/run_vilfan_complete.sh -paper          Stage 4 paper-exact positive control
#   ./scripts/run_vilfan_complete.sh -nodep          Stage 5 no-depletion shadow control
#   ./scripts/run_vilfan_complete.sh -sweep          Stage 5 kD/kA sweep
#   ./scripts/run_vilfan_complete.sh -native         Stage 6 native-lattice transfer
#   ./scripts/run_vilfan_complete.sh -alpha-check    Stage 7 alpha = 6, 8
#   ./scripts/run_vilfan_complete.sh -report         re-report from stored records
#   ./scripts/run_vilfan_complete.sh -arm <id>       one named arm (used by the parallel driver)
set -euo pipefail
cd "$(dirname "$0")/.."

# single-threaded JVM: one compute thread, minimal GC threads
JVMFLAGS=(-XX:ActiveProcessorCount=1 -XX:+UseSerialGC -Xss16m -Xmx2g --enable-preview)

exec nice -n 17 java "${JVMFLAGS[@]}" -cp . softbox.VilfanCompleteHarness -vilfan-complete "$@"
