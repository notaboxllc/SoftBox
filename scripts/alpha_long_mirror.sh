#!/usr/bin/env bash
# MIRROR PARTNERS for the eps=0 alpha sweep -- what makes ALPHA_LONG a twirl investigation.
#
# ALPHA_LONG runs alpha in {0,+30,+60,+90,-60} at eps=0, so its roll IS the eps=0 twirl question across
# alpha -- but every arm is NATIVE lattice. At eps=0 the actin lattice is the ONLY chirality in the model,
# so the discriminating estimator is MIRROR ANTISYMMETRY (the MSD/alpha line was retracted precisely because
# a per-arm statistic could not separate rotation from diffusion). A lattice-borne twirl MUST reverse when
# the helix is flipped; a random walk will not care.
#
# These two arms supply the flipped-lattice partners at the two alphas that matter:
#   a000_flp  -- the alpha=0 CONTROL we otherwise have no mirror for (does the canonical placement twirl?)
#   ap60_flp  -- the partner for the alpha=60 arm, where the (unresolved) -7.4 turns/0.86 s was seen
# Same scene, same seed, same duration as ALPHA_LONG => native/flipped pairs are directly comparable.
#
# Note the CPU TWIRL_ALPHA_EPS0 set is the same test at alpha=60 over 3 s with 2 seeds; these 1 s GPU arms
# are the fast version and add the alpha=0 control the CPU set does not cover.
set -u
OUT=${OUT:-RUN_LOGS/motor_audit/campaigns_2026-09/ALPHA_LONG}
STEPS=${STEPS:-8000000}       # 1.0 s, matching ALPHA_LONG
export SOFTBOX_XMX=${SOFTBOX_XMX:-4G}
mkdir -p "$OUT"
BASE="-run -gpu -devicecull -nohires -noviz -density 200 -matx 10.0 -maty 2.0 -filx 2.5 -eta 0.01 -dt 1.25e-7 -steps $STEPS -target 9.0 -filsegs 1 -triad -convaz-nocomp -seed 20260901"
run(){ rm -rf "$OUT/$1"; ./scripts/run_gpu_monitored.sh ./scripts/run_site_normal_glide_gpu.sh \
       $BASE $2 -out "$OUT/$1" > "$OUT/$1.log" 2>&1; echo "done $1"; }
run "a000_flp" "-flip-helix"             &
run "ap60_flp" "-convaz 60 -flip-helix"  &
wait
echo "=== mirror partners complete ==="
