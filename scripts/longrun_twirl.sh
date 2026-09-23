#!/usr/bin/env bash
# SECONDS-SCALE TWIRL RUNS (jba, 2026-09-22) -- reproduce the experimental measurement.
#
# WHY LONG RUNS, AND WHY THIS IS THE RIGHT INSTRUMENT. The eps=0 roll is DIFFUSIVE: measured over the
# 3 s CPU arms, |rate| ~ T^-0.50 across a 24x range of T (19.44 at 0.125 s -> 3.93 at 3.0 s), exactly
# the random-walk exponent. It decorrelates at ~0.5-1 s, roughly the time to traverse a correlated
# patch of lawn -- jba's objection that the EFFECTIVE lawn is the LOCAL one, confirmed.
#
# A genuine twirl is a DRIFT: constant rate, T^0. The diffusive background falls as T^-1/2. So the
# signal-to-background ratio improves as sqrt(T) and a real twirl EMERGES as the run lengthens,
# instead of being teased out of short-run averages. The diagnostic is not a single number, it is the
# RATE-vs-T CURVE: still falling as T^-1/2 => no twirl; flattening to a constant => that constant is
# the pitch. Readable continuously, so these arms are informative long before they finish.
#
# LAWN LENGTH IS THE BINDING CONSTRAINT. matx 10 allows only ~3.95 um of travel (0.5*MX - 0.5*contour),
# i.e. ~2.6-3.9 s -- a 5 s run would glide off the lawn and lose engagement. matx 24 gives ~10.9 um,
# ~8-10 s. Motor count rises to 9600 but the cull keeps only ~71 active per step, so per-step cost
# should barely move; WATCH THE THROUGHPUT, this mat size is untested.
#
# SIZED FROM THE 2026-09-23 BENCHMARK (RUNNER_BENCH, one config at a time on an idle machine):
#   GPU  4000 -> 242 / 9600 -> 175 / 20000 -> 132 steps/s   (rate ~ N^-0.38: the device cull MASKS)
#   CPU  4000 ->  84 /             / 20000 ->  61 (w8), 73 (w4)   (rate ~ N^-0.19: the CPU cull COMPACTS)
# jba was right that the GPU pays more for mat size, but its ~2.9x baseline still wins everywhere
# realistic (crossover ~1e6 motors). So: GPU, and keep the mat only as large as the travel needs.
# mat 20 um = 8000 motors, usable travel 8.95 um, ~7.5 s at the observed ~1.2 um/s.
#
# WHAT 8 s CAN AND CANNOT RESOLVE. Background: accumulated diffusive turns ~ 6.87*sqrt(T); the odd
# estimator halves that to 4.86*sqrt(T), so 3-sigma needs S_odd*T/(4.86*sqrt(T)) = 3:
#   S_odd 8 turns/s -> 3.3 s ; 5 -> 8.5 s ; 3 -> 23.6 s ; 1.5 (the BIOLOGICAL 1 turn/um) -> 94.5 s.
# So this batch tests |S_odd| >= ~5 turns/s (3.3 turns/um). The biological scale needs ~95 s on a
# ~286 um mat and is NOT reachable this way -- say so rather than let 8 s masquerade as a null.
# Cost also argues against going longer here: cost ~ T^1.38 while significance only ~ sqrt(T), so
# MORE PAIRS at moderate T beats fewer long ones, provided T stays well above the ~0.5-1 s
# correlation time.
#
# CONFIGURATION. alpha = 0 (canonical), conforming triad (canonical since 2026-09-19), eta 0.01,
# filsegs 1 (rigid rod, per jba). One complete unit per lawn: eps = 0 (the background), +4, -4 (the
# twirl test). eps-odd = (R(+4) - R(-4))/2 still available per lawn, but the primary read is the curve.
set -u
OUT=${OUT:-RUN_LOGS/motor_audit/campaigns_2026-09/LONGRUN_TWIRL}
mkdir -p "$OUT"
STEPS=${STEPS:-64000000}      # 8.0 s at dt 1.25e-7
LAWN=${LAWN:-20260901}
BASE="-run -gpu -devicecull -nohires -noviz -density 200 -matx 20.0 -maty 2.0 -filx 2.5 -eta 0.01 -dt 1.25e-7 -steps $STEPS -target 8.5 -filsegs 1 -triad -convaz-nocomp"
export SOFTBOX_XMX=${SOFTBOX_XMX:-6G}
run(){ rm -rf "$OUT/$1"; ./scripts/run_gpu_monitored.sh ./scripts/run_site_normal_glide_gpu.sh \
       $BASE -seed $2 -stroke-skew $3 -out "$OUT/$1" > "$OUT/$1.log" 2>&1; echo "done $1"; }
run "eps0_$LAWN"   $LAWN 0   &
run "epsP4_$LAWN"  $LAWN 4   &
run "epsM4_$LAWN"  $LAWN -4  &
wait
echo "=== LONGRUN_TWIRL lawn $LAWN complete ==="
