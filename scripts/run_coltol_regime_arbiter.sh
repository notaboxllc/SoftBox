#!/bin/bash
# COLTOL_REGIME_SWEEP CPU cross-check at the EXTREME coltol (smallest capture radius, occupancy>1).
# POLICY (docs/CPU_GPU_VALIDATION_POLICY.md §3): coltol is a DATA-ONLY scalar (kinParams[7]) — all arms share
# identical hot-kernel structure, so this is NOT a mandated confirmation. It is retained as an OPT-IN
# outlier check (§3(7)) justified only by the extreme geometric operating point, not by the sweep itself.
# 30k to MATCH the GPU sweep window exactly (STATS_STEADY warmup = 0.20s = 20000 steps ⇒ a 20k run has no steady
# window; velFitX 2nd-half also needs the same M for apples-to-apples). coltol is a scalar (kinParams[7]) ⇒ all
# sweep arms share identical hot-kernel structure (springs default, no task/transcendental toggle) so the basin-flip
# hazard is low, but the extreme geometric point gets a deterministic CPU cross-check on velFitX + occupancy.
cd /home/jba/Code/SoftBox
LOG=RUN_LOGS/2026-07-10_coltol_regime_arbiter.txt
: > "$LOG"
echo "COLTOL_REGIME CPU-arbiter c2 d2000 30k (extreme point) $(date)" | tee -a "$LOG"
echo "[$(date +%H:%M:%S)] ARBITER CPU c2 d2000 30k" > .last_run_status
scripts/run_gliding.sh -full -grid -matbox 50 -coltol 2 -density 2000 -seed 0 30000 2>&1 \
  | grep -E "GRID_ROW|STATS_STEADY_ROW|COV_ROW|NaN|Exception|CUDA" >> "$LOG"
echo "[$(date +%H:%M:%S)] ARBITER DONE" > .last_run_status
echo "ARBITER_DONE" | tee -a "$LOG"
