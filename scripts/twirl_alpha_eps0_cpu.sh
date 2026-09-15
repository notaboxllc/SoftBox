#!/usr/bin/env bash
# DOES CONVERTER PLACEMENT ALONE PRODUCE TWIRLING?  eps = 0: no imposed stroke skew, no imposed chirality.
#
# THE OBSERVATION. In the eta=0.01 / rho=200 long pair (eps=0), alpha=+60 accumulated -7.55 turns of roll over
# 0.861 s while alpha=0 accumulated -0.25. If real, the ONLY chirality available at eps=0 is the ACTIN SITE
# LATTICE itself, so this would be twirling DERIVED from the lattice geometry via the converter placement --
# the derived-handedness route, not an imposed epsStroke.
#
# THE CONTROL. -flip-helix mirrors the lattice twist and is, per the code, the only valid control at eps=0: if
# the roll REVERSES with the lattice, the chirality comes from the actin geometry; if it does not, it is an
# artefact of the alpha geometry. alpha=0 arms are the internal null (no converter displacement => no effect).
#
# WHY LONG CPU RUNS. At the observed rate (~8.7 turns/s at alpha=+60) a 1 s arm accumulates ~9 turns and 3 s
# ~26 -- ACTUAL ROTATIONS, not a marginal drift teased out of noise. Roll has fooled us twice (net roll over a
# window is NOT a rotation rate), so the readout is (a) accumulated turns and (b) the per-arm drift-vs-diffusion
# z = net / (sd(dRoll)*sqrt(N)) with its increment sign fraction. Trajectory rows carry rollTurns, so these are
# READ AS THEY GO -- nothing waits on the closing summary. -steps is set to 3 s; stop early once it is decided.
set -u
OUT=${OUT:-RUN_LOGS/motor_audit/campaigns_2026-09/TWIRL_ALPHA_EPS0}
STEPS=${STEPS:-24000000}      # 3.0 s at dt 1.25e-7
mkdir -p "$OUT"
export SOFTBOX_XMX=${SOFTBOX_XMX:-2G}   # CPU arms: measured RSS well under this; 4 x 2G = 8 GB
export TORNADOVM_HOME="${TORNADOVM_HOME:-$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx}"
BASE="-run -nohires -noviz -density 200 -matx 10.0 -maty 2.0 -filx 2.5 -eta 0.01 -dt 1.25e-7 -steps $STEPS -target 9.0 -filsegs 1 -triad -convaz-nocomp -workers 1"
run(){ rm -rf "$OUT/$1"; ./scripts/run_site_normal_long_glide.sh $BASE -seed $3 -randbase -randbase-seed $3 $2 -out "$OUT/$1" > "$OUT/$1.log" 2>&1; echo "done $1"; }
# alpha=+60: the effect arm, native vs mirrored lattice, 2 seeds  (4 arms)
run "a60_nat_20260901" "-convaz 60"              20260901 &
run "a60_flp_20260901" "-convaz 60 -flip-helix"  20260901 &
run "a60_nat_20260902" "-convaz 60"              20260902 &
run "a60_flp_20260902" "-convaz 60 -flip-helix"  20260902 &
# alpha=0: the internal null, native vs mirrored  (2 arms)
# alpha=0 nulls are supplied by the GPU ALPHA_LONG a000 arm (also eps=0) -- not duplicated here.
wait
echo "=== TWIRL_ALPHA_EPS0 complete ==="
