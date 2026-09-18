#!/usr/bin/env bash
# PAIRED CONFORM TEST -- the design the variance decomposition demands (2026-09-18).
#
# WHY PAIRED. 73% of the roll variance is QUENCHED: ChiralSiteHarness.build(seed) lays a different
# motor lawn per seed, and no single trajectory can see the spread over lawns (blocked within-arm SE
# +/-5.0 vs measured across-seed sd +/-9.67). Running arms LONGER barely helps -- a 16 s arm still
# carries SE 8.4. Running them at MATCHED SEEDS does, because the same seed is the same lawn, so the
# quenched term cancels in the difference:
#     unpaired difference sd 13.7  ->  17 pairs to resolve 10 turns/s at 3 sigma
#     PAIRED   difference sd  7.1  ->   4 pairs
#
# Both arms of a pair run the SAME runner (the experimental device path), so any device-path artifact
# also cancels in the difference. That is the only thing that makes using the un-gated path defensible
# here: we quote the DIFFERENCE, never the absolute level.
#
# Seed 20260901 is already done (ALPHA_LONG/ap60 frustrated + TRIAD_CONFORM/conform). These add two
# more pairs. Config is byte-identical to the ALPHA_LONG BASE so the frustrated arms pool with ap60.
set -u
OUT=${OUT:-RUN_LOGS/motor_audit/campaigns_2026-09/CONFORM_PAIRED}
mkdir -p "$OUT"
STEPS=${STEPS:-8000000}       # 1.0 s at dt 1.25e-7
BASE="-run -gpu -devicecull -nohires -noviz -density 200 -matx 10.0 -maty 2.0 -filx 2.5 -eta 0.01 -dt 1.25e-7 -steps $STEPS -target 9.0 -filsegs 1 -triad -convaz-nocomp -convaz 60"
export SOFTBOX_XMX=${SOFTBOX_XMX:-4G}
run(){ rm -rf "$OUT/$1"; ./scripts/run_gpu_monitored.sh ./scripts/run_site_normal_glide_gpu.sh \
       $BASE -seed $2 $3 -out "$OUT/$1" > "$OUT/$1.log" 2>&1; echo "done $1"; }
for s in 20260902 20260903; do
  run "frust_$s"   $s ""                &
  run "conform_$s" $s "-triad-conform"  &
done
wait
echo "=== CONFORM_PAIRED complete ==="
