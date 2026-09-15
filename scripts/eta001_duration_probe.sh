#!/usr/bin/env bash
# DURATION PROBE before the eta=0.01 converter grid. "1 s is enough" is a DERIVED SNR claim
# (D ~ 1/eta => wander x3.2 per unit time, v only x1.6 => 3.9x the eta=0.1 duration); this measures it.
# PASS = the velocity is RESOLVED (class not C/E) and vfit is stable over the last third of the run.
# Two arms: alpha=0 baseline and alpha=+60 (the favourable, barbed-proximal sign from the eta=0.1 campaign),
# so the probe also gives a first look at the effect at the publication viscosity.
# rho 200: avgBound 2.32, matching the engagement of the rho=2000 eta=0.1 scene, and 1667 steps/s alone.
set -u
OUT=${OUT:-RUN_LOGS/motor_audit/campaigns_2026-09/ETA001_DURATION}
STEPS=${STEPS:-7800000}      # 0.975 s physical at dt 1.25e-7
mkdir -p "$OUT"
BASE="-run -gpu -devicecull -nohires -noviz -density 200 -matx 10.0 -maty 2.0 -filx 2.5 -eta 0.01 -dt 1.25e-7 -steps $STEPS -target 9.0 -filsegs 1 -triad -convaz-nocomp -seed 20260901"
run(){ rm -rf "$OUT/$1"; ./scripts/run_gpu_monitored.sh ./scripts/run_site_normal_glide_gpu_snapshot.sh \
       $BASE $2 -out "$OUT/$1" > "$OUT/$1.log" 2>&1; echo "done $1"; }
run "a000" ""           &
run "ap60" "-convaz 60" &
wait
echo "=== duration probe done ==="
for a in a000 ap60; do echo "--- $a ---"; grep -E "STOP=|net forward|LS velocity|avgBound = |captures|mean axial|invalid =|CLASS" "$OUT/$a.log"; done
