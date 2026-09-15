#!/usr/bin/env bash
# ALPHA SWEEP -- SECONDS-LONG ARMS (jba's gold standard: one long run demonstrating sustained velocity/twirl,
# rather than an ensemble of short noisy ones).
#
# THERE IS NO SETTLING TRANSIENT. Disjoint-window analysis of the 0.86 s pair: the trend across windows is
# 0.4 sigma (alpha=0) and 0.7 sigma (alpha=60) -- no resolved decline. The earlier apparent "decay" was the
# CUMULATIVE LS fit slowly averaging down an early high draw, not physics. In an overdamped model with sub-us
# bond/latch relaxation and a ~0.6 ms attachment cycle, nothing takes 0.5 s to settle. So the accumulators are
# left running from t=0 and no burn-in is excluded.
#
# WHAT A 1 s ARM BUYS. Window scatter is sd ~1.0 um/s over 0.125 s windows => velocity uncertainty ~0.35/sqrt(T).
#   1 s -> +-0.35 um/s ; 2 s -> +-0.25 ; resolving a ~0.3 um/s alpha difference needs ~12 s/arm (~9 days).
# So these arms DEMONSTRATE sustained motion and accumulate turns; they do NOT resolve small alpha differences
# by velocity. The FORCE channel (~600 attachments per 0.25 s) is what resolves the alpha dependence, and it
# comes free in the same summary block.
#
# eps = 0 throughout, so the roll these accumulate is also the eps=0 twirl question on the GPU side.
set -u
OUT=${OUT:-RUN_LOGS/motor_audit/campaigns_2026-09/ALPHA_LONG}
STEPS=${STEPS:-8000000}       # 1.0 s at dt 1.25e-7  (~18 h/arm at the 121 steps/s seen 3-wide)
JOBS=${JOBS:-3}
mkdir -p "$OUT"
BASE="-run -gpu -devicecull -nohires -noviz -density 200 -matx 10.0 -maty 2.0 -filx 2.5 -eta 0.01 -dt 1.25e-7 -steps $STEPS -target 9.0 -filsegs 1 -triad -convaz-nocomp -seed 20260901"
export SOFTBOX_XMX=${SOFTBOX_XMX:-4G}   # 2026-09-15: 5 arms x 4G = 20 GB ceiling on 31 GB.
       # The Sep-13/15 losses were libcuda SIGSEGV (hs_err), twice alongside host-RAM exhaustion that OOM-killed
       # Chrome 2-3 s earlier. Measured RSS at 4G is 0.68 GB, so this cap costs nothing and converts a runaway
       # into a clean Java OOM instead of an OOM-killer sweep. Watch for NEW hs_err_pid*.log in the repo root.
run(){ rm -rf "$OUT/$1"; ./scripts/run_gpu_monitored.sh ./scripts/run_site_normal_glide_gpu.sh \
       $BASE $2 -out "$OUT/$1" > "$OUT/$1.log" 2>&1; echo "done $1"; }
slot(){ while [ "$(jobs -rp | wc -l)" -ge "$JOBS" ]; do wait -n; done; }
slot; run "a000" ""              &
slot; run "ap30" "-convaz 30"    &
slot; run "ap60" "-convaz 60"    &
slot; run "ap90" "-convaz 90"    &
slot; run "am60" "-convaz -60"   &
wait
echo "=== ALPHA_LONG complete ==="
