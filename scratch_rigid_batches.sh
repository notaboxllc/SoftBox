#!/usr/bin/env bash
# Sequential matched-seed BATCHES for the rigid skew study.
#
# Each batch is one monitored invocation covering 2 seeds (4 arms), so a batch closes two complete
# matched pairs before the next begins -- which is what the sequential stopping rules need.
# atpArm() reuses COMPLETE records, so re-entering a batch is idempotent.
#
#   usage: scratch_rigid_batches.sh <wait_pid|0> <eps_deg> <logprefix> <seed0> [seed1 ...]
set -uo pipefail
WAITPID=$1; EPS=$2; PREFIX=$3; shift 3

if [[ "$WAITPID" != "0" ]]; then
  while kill -0 "$WAITPID" 2>/dev/null; do sleep 30; done
fi

for S in "$@"; do
  ./scripts/run_gpu_monitored.sh ./scripts/run_chiral_sites.sh \
      -atp-map -gpu -eta 0.01 -atp-points 10 \
      -atp-eps-deg "$EPS" -density 400 -seed "$S" -seeds 2 \
      -atp-duration-ms 100 -filament-segments 1 -fil-thermostat fdt -rot-decomp \
      > "${PREFIX}_s${S}.txt" 2>&1
  echo "batch seed0=$S exit=$? $(date -u +%H:%M:%SZ)" >> "${PREFIX}_batches.log"
done
