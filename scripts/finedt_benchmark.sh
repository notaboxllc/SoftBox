#!/bin/bash
# FINE-dt free-glide study — PART 1 benchmark (reproducibility record; raw output in
# RUN_LOGS/finedt_benchmark.txt). Runs from the pristine f537972 worktree (see
# scripts/finedt_free_glide_sweep.sh header for the one-line recreation command).
#
# Throughput (steps/s) is dt-INDEPENDENT (same kernels/step), so we measure steps/s vs density+runner
# via a two-point timing (short vs long step count) that cancels JVM/Tornado startup, then extrapolate
# wall = (0.6/dt)/throughput for every matrix cell.
WT=/home/jba/Code/SoftBox-finedt-canon
cd "$WT" || { echo "recreate the worktree first (see finedt_free_glide_sweep.sh header)"; exit 1; }
LOG=RUN_LOGS/finedt_benchmark.txt
: > "$LOG"
echo "FINE-dt BENCHMARK  $(date)  commit=$(git rev-parse --short HEAD)" | tee -a "$LOG"

bench () {  # args: runner("gpu"/"cpu") density n_lo n_hi
  local runner=$1 D=$2 NLO=$3 NHI=$4 gpuflag=""
  [ "$runner" = "gpu" ] && gpuflag="-gpu"
  local t0 t1 tlo thi
  t0=$(date +%s.%N)
  scripts/run_gliding.sh $gpuflag -full -grid -coltol 8 -matbox 50 -density $D -seed 0 $NLO >/dev/null 2>&1
  t1=$(date +%s.%N); tlo=$(echo "$t1-$t0" | bc)
  t0=$(date +%s.%N)
  scripts/run_gliding.sh $gpuflag -full -grid -coltol 8 -matbox 50 -density $D -seed 0 $NHI >/dev/null 2>&1
  t1=$(date +%s.%N); thi=$(echo "$t1-$t0" | bc)
  local dsteps=$((NHI-NLO))
  local perstep=$(echo "scale=8; ($thi-$tlo)/$dsteps" | bc)
  local sps=$(echo "scale=2; $dsteps/($thi-$tlo)" | bc)
  local w1e5=$(echo "scale=1; 60000*$perstep" | bc)
  local w5e6=$(echo "scale=1; 120000*$perstep" | bc)
  local w25e6=$(echo "scale=1; 240000*$perstep" | bc)
  printf "BENCH runner=%s density=%s tlo(%d)=%.1fs thi(%d)=%.1fs perstep=%.6fs steps/s=%s | wall@dt1e-5(60k)=%ss @5e-6(120k)=%ss @2.5e-6(240k)=%ss\n" \
    "$runner" "$D" "$NLO" "$tlo" "$NHI" "$thi" "$perstep" "$sps" "$w1e5" "$w5e6" "$w25e6" | tee -a "$LOG"
}

echo "-- GPU density scaling --" | tee -a "$LOG"
bench gpu 500  2000 8000
bench gpu 2000 2000 8000
bench gpu 8000 2000 6000
echo "-- CPU arbiter throughput --" | tee -a "$LOG"
bench cpu 2000 500 2000
bench cpu 8000 500 1500
echo "BENCH_DONE" | tee -a "$LOG"
