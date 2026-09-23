#!/usr/bin/env bash
# (b) PILOT -- eps-odd twirl at eps=4 deg, eta=0.01, CANONICAL model (jba, 2026-09-21).
#
# PURPOSE. This is the SENSITIVITY CALIBRATION / POSITIVE CONTROL for the eps=0 native-twirl test.
# If the model transmits an IMPOSED chiral bias at small eps but shows no parity-odd signal at eps=0,
# that is a real result: the machinery works, the native geometry does not supply chirality. If this
# pilot is ALSO null, then an eps=0 null is uninformative and we have only learned the assay lacks
# sensitivity. So this runs FIRST.
#
# WHAT IT ESTIMATES (reviewer note section 18 -- state the claim to match the estimator):
#   R_odd = (R(+eps) - R(-eps)) / 2   at a MATCHED LAWN
# = the response to a DELIBERATELY IMPOSED chiral perturbation. EPS_STROKE_DEG is an explicit model
# input. This is NOT a measurement of native structural twirling; that is test (a).
#
# WHY MATCHED-LAWN PAIRS. ~73% of the roll variance is quenched in the motor lawn and is immune to run
# length. Pairing at a matched lawn cancels it (measured 2.4x sd reduction). Absolute roll at eps=0
# across 8 independent lawns gave +1.90 +/- 3.05 turns/s -- no effect, and no power to see one.
#
# CONFIGURATION. alpha = 0 (convaz default): the CANONICAL model, and what the historical eps ladder
# used. Conforming triad is canonical since 2026-09-19. filsegs 1 -- a RIGID single-segment rod, per
# jba: this measures whether net chiral TORQUE is generated, not the torsional mechanics that turn
# distributed torque into whole-filament twirl (that needs multi-segment + -rollspring).
#
# PILOT, NOT A CAMPAIGN (reviewer note section 17). Our paired-variance estimate comes from n=3 and its
# 95% chi-square interval spans ~6x (sigma in [2.75, 33.2]), which moves a 1 turn/um campaign from 16
# to 2203 pairs. Six lawns here; re-estimate the paired sd from THESE arms before sizing anything.
#
# Lawns 20260901..06 are reused deliberately -- their quenched offsets are already characterised from
# the ROLL_ENSEMBLE, so the pairing can be checked against a known per-lawn baseline.
set -u
OUT=${OUT:-RUN_LOGS/motor_audit/campaigns_2026-09/EPS_ODD_PILOT}
mkdir -p "$OUT"
STEPS=${STEPS:-8000000}       # 1.0 s at dt 1.25e-7
EPS=${EPS:-4}
JOBS=${JOBS:-4}
BASE="-run -gpu -devicecull -nohires -noviz -density 200 -matx 10.0 -maty 2.0 -filx 2.5 -eta 0.01 -dt 1.25e-7 -steps $STEPS -target 9.0 -filsegs 1 -triad -convaz-nocomp"
export SOFTBOX_XMX=${SOFTBOX_XMX:-4G}
run(){ rm -rf "$OUT/$1"; ./scripts/run_gpu_monitored.sh ./scripts/run_site_normal_glide_gpu.sh \
       $BASE -seed $2 -stroke-skew $3 -out "$OUT/$1" > "$OUT/$1.log" 2>&1; echo "done $1"; }
slot(){ while [ "$(jobs -rp | wc -l)" -ge "$JOBS" ]; do wait -n; done; }
for s in 20260901 20260902 20260903 20260904 20260905 20260906; do
  slot; run "pos_$s" $s "$EPS"   &
  slot; run "neg_$s" $s "-$EPS"  &
done
wait
echo "=== EPS_ODD_PILOT complete ==="
