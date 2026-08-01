#!/usr/bin/env bash
# Serial arm chain for the rigid skew re-validation.
#
# Waits for an already-running arm process to exit, then runs the requested seed block through the
# MONITORED wrapper. atpArm() reuses any record that is already COMPLETE, so re-entering a block is
# idempotent: finished arms are skipped instantly and only the outstanding ones are simulated.
#
#   usage: scratch_rigid_chain.sh <wait_pid|0> <eps_deg> <seed0> <nseeds> <logfile>
set -uo pipefail
WAITPID=$1; EPS=$2; SEED0=$3; NSEEDS=$4; LOG=$5

if [[ "$WAITPID" != "0" ]]; then
  while kill -0 "$WAITPID" 2>/dev/null; do sleep 30; done
fi

exec ./scripts/run_gpu_monitored.sh ./scripts/run_chiral_sites.sh \
     -atp-map -gpu -eta 0.01 -atp-points 10 \
     -atp-eps-deg "$EPS" -density 400 -seed "$SEED0" -seeds "$NSEEDS" \
     -atp-duration-ms 100 -filament-segments 1 -fil-thermostat fdt -rot-decomp \
     > "$LOG" 2>&1
