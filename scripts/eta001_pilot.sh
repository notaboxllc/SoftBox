#!/usr/bin/env bash
# PILOT for the eta=0.01 converter-position study. Measures cost + engagement + stability BEFORE committing
# the grid (CLAUDE.md: disclose runner and steps/s before spending wall-clock).
#
# SETTINGS (jba 2026-09-12): eta = 0.01 Pa.s, rho = 800 heads/um^2, and the dt the scaled-dt protocol REQUIRES.
#   dt SCALES LINEARLY WITH eta (VISCOSITY_SENSITIVITY Part II: the fracMove/Class-II force laws relax a FIXED
#   FRACTION PER STEP, so only dt(eta) = dt0*eta/eta0 makes them relax at the same PHYSICAL rate as the
#   Class-I laws -- never compare viscosities at fixed dt). So 1.25e-6 at 0.1  ->  1.25e-7 at 0.01.
#   That is also roughly where the explicit cross-bridge stability limit goes, since it scales as gamma/k.
# Two arms: the canonical baseline, and tilt -20 with the consistent gate (the candidate optimum). The second
# also answers whether the 7.6 kT capture cost is more affordable at low eta, where engagement is ~2.3x higher.
set -u
OUT=${OUT:-RUN_LOGS/motor_audit/campaigns_2026-09/ETA001_PILOT}
STEPS=${STEPS:-50000}
mkdir -p "$OUT"
BASE="-run -gpu -devicecull -nohires -noviz -density 800 -matx 10.0 -maty 2.0 -filx 2.5 -eta 0.01 -dt 1.25e-7 -steps $STEPS -target 9.0 -filsegs 1 -triad -seed 20260901"
run(){ rm -rf "$OUT/$1"; ./scripts/run_gpu_monitored.sh ./scripts/run_site_normal_glide_gpu_snapshot.sh \
       $BASE $2 -out "$OUT/$1" > "$OUT/$1.log" 2>&1; echo "done $1"; }
run "base"  ""                          &
run "tm20"  "-headtilt -20 -tiltgate"   &
wait
echo "=== pilot done ==="
for a in base tm20; do echo "--- $a ---"; grep -E "RUNNER|SCENE:|STOP=|net forward|LS velocity|avgBound|captures|invalid|mean axial|CLASS" "$OUT/$a.log" | head -9; done
