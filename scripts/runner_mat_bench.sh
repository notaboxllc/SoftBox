#!/usr/bin/env bash
# RUNNER x MAT-SIZE BENCHMARK (2026-09-23) -- decide where the seconds-scale runs should live.
#
# WHY. The device cull MASKS but does not COMPACT: MatSoaSlice.matCull iterates the FULL mat every
# step (@Parallel over N), and the ~30 tasks after it also launch over N and early-return on
# active[m]. The CPU path builds a worker partition over the ACTIVE set (~71 motors), so it is
# nearly mat-size-insensitive. jba remembered this; an earlier measurement of mine suggested the mat
# was almost free, but it was taken with 11 arms on 8 cores and was bottlenecked elsewhere.
#
# RUNS STRICTLY SEQUENTIALLY, ONE AT A TIME. Contention is exactly what corrupted the last attempt.
# Rate is measured from the LATER rows only, so JIT warm-up is excluded.
set -u
OUT=${OUT:-RUN_LOGS/motor_audit/campaigns_2026-09/RUNNER_BENCH}
rm -rf "$OUT"; mkdir -p "$OUT"
STEPS=${STEPS:-12000}
COMMON="-run -nohires -noviz -density 200 -maty 2.0 -filx 2.5 -eta 0.01 -dt 1.25e-7 -steps $STEPS -target 10.0 -filsegs 1 -triad -convaz-nocomp -seed 20260901"
export SOFTBOX_XMX=${SOFTBOX_XMX:-8G}
one(){ # tag, matx, extra
  echo "  running $1 ..."
  ./scripts/run_gpu_monitored.sh ./scripts/run_site_normal_glide_gpu.sh \
      $COMMON -matx $2 $3 -out "$OUT/$1" > "$OUT/$1.log" 2>&1
}
one gpu_mat10  10.0 "-gpu -devicecull"
one gpu_mat24  24.0 "-gpu -devicecull"
one gpu_mat50  50.0 "-gpu -devicecull"
one cpu_w8_mat10  10.0 "-workers 8"
one cpu_w8_mat50  50.0 "-workers 8"
one cpu_w4_mat50  50.0 "-workers 4"
one cpu_w1_mat50  50.0 "-workers 1"
echo "=== RUNNER_BENCH complete ==="
