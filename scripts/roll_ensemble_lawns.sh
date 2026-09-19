#!/usr/bin/env bash
# STEP 4 of the agreed plan (jba, 2026-09-19): measure ABSOLUTE roll across several INDEPENDENT LAWNS
# using the CORRECTED model. This replaces the abandoned 5-6 pair conform-vs-frustrated campaign --
# that would have spent a statistically decisive effort on whether fixing a known geometry defect
# changes an effect that may itself be zero. The question that matters is whether the corrected model
# rolls at all.
#
# WHY THIS CONDITION. alpha=60 is where the roll was largest and where every retracted claim was made,
# so it is the efficient place to test for existence: if it is null here it is null in weaker settings.
# convaz remains a MECHANISM PROBE, not a canonical setting (reviewer §8 -- its biological sign and
# magnitude are still uncited), so a null here does NOT license tuning it.
#
# WHY 5 ARMS. Three corrected-model arms at alpha=60 already exist on lawns 20260901/02/03
# (TRIAD_CONFORM/conform, CONFORM_PAIRED/conform_2026090{2,3}). Adding lawns 04-08 gives n=8 lawns.
# At the measured per-lawn sd of ~9.7 turns/s that is SE ~3.4, enough to resolve a 10 turns/s mean.
#
# ANALYSE WITH THE LAWN AS THE UNIT OF REPLICATION. The retraction that motivated this run was pooling
# arms that shared a lawn as if they were independent (six of eight were seed 20260901). One arm per
# lawn here, so mean +/- sd/sqrt(n) over arms IS the lawn-level statistic. Use a t interval at n=8.
#
# The triad is canonical-conforming by default as of 2026-09-19; no -triad-conform flag is needed and
# passing one is a no-op. -triad-flat would opt back out.
set -u
OUT=${OUT:-RUN_LOGS/motor_audit/campaigns_2026-09/ROLL_ENSEMBLE}
mkdir -p "$OUT"
STEPS=${STEPS:-8000000}       # 1.0 s at dt 1.25e-7, matching every arm it will be pooled with
JOBS=${JOBS:-5}
BASE="-run -gpu -devicecull -nohires -noviz -density 200 -matx 10.0 -maty 2.0 -filx 2.5 -eta 0.01 -dt 1.25e-7 -steps $STEPS -target 9.0 -filsegs 1 -triad -convaz-nocomp -convaz 60"
export SOFTBOX_XMX=${SOFTBOX_XMX:-4G}
run(){ rm -rf "$OUT/$1"; ./scripts/run_gpu_monitored.sh ./scripts/run_site_normal_glide_gpu.sh \
       $BASE -seed $2 -out "$OUT/$1" > "$OUT/$1.log" 2>&1; echo "done $1"; }
slot(){ while [ "$(jobs -rp | wc -l)" -ge "$JOBS" ]; do wait -n; done; }
for s in 20260904 20260905 20260906 20260907 20260908; do slot; run "lawn_$s" $s & done
wait
echo "=== ROLL_ENSEMBLE complete ==="
