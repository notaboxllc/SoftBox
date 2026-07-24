#!/bin/bash
# FINE-dt FREE-GLIDING DENSITY SWEEP — orchestration (2026-07-13).
#
# Directly measures the canonical free-gliding velFitX-density curve at production dt=1e-5 and
# fine dt=5e-6 (+ dt=2.5e-6 spot checks), matched physical duration 0.6 s, matbox-50 chamber,
# coltol=8 nm, over the canonical UNMODIFIED stack (SPHEREHEAD+AXLOCK+DIRSWING+XB_IMPLICIT2+
# LYMN_TAYLOR, all default-on).
#
# Runs from a PRISTINE build of commit f537972 (the "FINE-dt V0 reference") in an isolated git
# worktree, so the canonical numerics match the clamp V0_fine=13.3 reference exactly and jba's
# uncommitted WIP (J2 torsion + orthogonalizeY) is untouched.
#
# Each matrix cell -> its own file under RUN_LOGS/finedt_cells/. RESTARTABLE: a cell whose file
# already contains a valid GRID_ROW is SKIPPED. Interleaves the two main timesteps so machine
# heating is balanced across dt.
#
# Invocation per cell (CANONICAL = bare run_gliding.sh; only swept conditions passed explicitly):
#   scripts/run_gliding.sh [-gpu] -full -grid -coltol 8 -matbox 50 -density <D> -seed <s> -dt <dt> <M>
#
# Usage:
#   scripts/finedt_free_glide_sweep.sh main      # Part 2 main sweep (GPU, dt 1e-5 & 5e-6, 4 seeds)
#   scripts/finedt_free_glide_sweep.sh spot      # Part 3 dt=2.5e-6 spot checks
#   scripts/finedt_free_glide_sweep.sh arbiter   # Part 1/5 CPU basin-arbiter cells (d2000,d8000)
#   scripts/finedt_free_glide_sweep.sh control    # matbox on/off + dt-order controls
set -u
# WT = pristine build of commit f537972 (the FINE-dt V0 reference; the canonical numerics that match
# the clamp reference — no uncommitted J2/orthogonalizeY WIP). To recreate after cleanup:
#   git worktree add --detach /home/jba/Code/SoftBox-finedt-canon f537972
#   ( cd /home/jba/Code/SoftBox-finedt-canon && bash scripts/build.sh )
WT=/home/jba/Code/SoftBox-finedt-canon
MAIN=/home/jba/Code/SoftBox
CELLS=$MAIN/RUN_LOGS/finedt_cells
mkdir -p "$CELLS"
COLTOL=8
PHYS=0.6                         # target physical duration (s)
SEEDS_MAIN="0 1 2"              # 3 paired seeds (benchmark: 4 impractical — high-density fine-dt dominates)
SEEDS_SPOT="0 1"                # spot-check seeds (>=2; 3rd via mode spot3 if 5->2.5 drift material)
DENS_MAIN="500 1000 2000 4000 6000 8000"

steps_for_dt () { python3 -c "print(int(round($PHYS/float('$1'))))"; }

# run one cell if not already complete. args: runner density seed dt
cell () {
  local runner=$1 D=$2 S=$3 DT=$4
  local M gpuflag="" tag
  M=$(steps_for_dt "$DT")
  [ "$runner" = "gpu" ] && gpuflag="-gpu"
  tag="${runner}_dt${DT}_d${D}_s${S}"
  local f="$CELLS/${tag}.log"
  if [ -f "$f" ] && grep -q "GRID_ROW" "$f" && grep -q "COV_ROW" "$f"; then
    echo "[skip] $tag (complete)"; return 0
  fi
  echo "[$(date +%H:%M:%S)] RUN $tag M=$M" | tee -a "$MAIN/RUN_LOGS/finedt_progress.txt"
  # 6 h hang-safety timeout (only trips on a genuine hang; worst real cell is CPU d8000@1e-5 ~4.5 h)
  ( cd "$WT" && timeout 21600 scripts/run_gliding.sh $gpuflag -full -grid -coltol $COLTOL -matbox 50 \
      -density "$D" -seed "$S" -dt "$DT" "$M" ) > "$f" 2>&1
  if grep -q "GRID_ROW" "$f"; then
    grep -E "GRID_ROW|STATS_STEADY_ROW|COV_ROW" "$f" | sed "s/^/  [$tag] /"
  else
    echo "  [$tag] !! NO GRID_ROW — check $f"; grep -iE "Exception|CUDA|NaN|Infinity|Error" "$f" | head -3
  fi
}

mode=${1:-main}
case "$mode" in
  main)
    # Interleave dt within each (density,seed) so heating is balanced across timestep.
    for D in $DENS_MAIN; do
      for S in $SEEDS_MAIN; do
        cell gpu "$D" "$S" 1e-5
        cell gpu "$D" "$S" 5e-6
      done
    done
    echo "MAIN_SWEEP_DONE" | tee -a "$MAIN/RUN_LOGS/finedt_progress.txt"
    ;;
  spot)
    for D in 2000 8000; do
      for S in $SEEDS_SPOT; do
        cell gpu "$D" "$S" 2.5e-6
      done
    done
    echo "SPOT_DONE" | tee -a "$MAIN/RUN_LOGS/finedt_progress.txt"
    ;;
  spot4000)   # conditional extra: d4000 + 3rd seed if 5->2.5us drift is material
    for S in $SEEDS_SPOT; do cell gpu 4000 "$S" 2.5e-6; done
    echo "SPOT4000_DONE" | tee -a "$MAIN/RUN_LOGS/finedt_progress.txt"
    ;;
  arbiter)
    # CPU cross-check. RETAINED and JUSTIFIED under docs/CPU_GPU_VALIDATION_POLICY.md §5: this is the arbiter
    # set that REFUTED a GPU result (d8000 dt=1e-5: CPU 9.93 vs GPU 13.84), changing the conclusion.
    # CPU is ~11x slower than GPU at d2000, so a full CPU
    # matrix is impossible. LEAN set that resolves the basin at both dt and in the dense regime:
    #   d2000 dt=1e-5 seeds 0,1   (production basin, 2 seeds, full 0.6 s)  ~84 min each
    #   d2000 dt=5e-6 seed 0      (fine-dt basin, full 0.6 s)              ~168 min
    #   d8000 dt=1e-5 seed 0      (DENSE-regime basin, full 0.6 s)         ~4.5 h  (the key dense check)
    # All full-length ⇒ directly comparable to the matched GPU cell (same dt, density, seed).
    cell cpu 2000 0 1e-5
    cell cpu 2000 1 1e-5
    cell cpu 2000 0 5e-6
    cell cpu 8000 0 1e-5
    echo "ARBITER_DONE" | tee -a "$MAIN/RUN_LOGS/finedt_progress.txt"
    ;;
  control)
    # matbox on/off control at one density (verify the chamber does not shift the baseline) + dt-order.
    # matbox-OFF variant: bare canonical without -matbox 50 at d2000 & d8000, dt 1e-5, seed 0.
    for D in 2000 8000; do
      f="$CELLS/gpu_dt1e-5_d${D}_s0_nomatbox.log"
      if [ -f "$f" ] && grep -q GRID_ROW "$f"; then echo "[skip] nomatbox d$D"; else
        echo "[$(date +%H:%M:%S)] RUN nomatbox d$D"
        ( cd "$WT" && scripts/run_gliding.sh -gpu -full -grid -coltol $COLTOL -density "$D" -seed 0 -dt 1e-5 60000 ) > "$f" 2>&1
        grep -E "GRID_ROW|COV_ROW" "$f" | sed "s/^/  [nomatbox d$D] /"
      fi
    done
    echo "CONTROL_DONE" | tee -a "$MAIN/RUN_LOGS/finedt_progress.txt"
    ;;
  *) echo "unknown mode: $mode"; exit 1;;
esac
