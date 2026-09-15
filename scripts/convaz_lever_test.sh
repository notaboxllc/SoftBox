#!/usr/bin/env bash
# IS THE convaz VELOCITY GAIN A "SECOND LEVER" AT BINDING, OR THE STROKE BEING BETTER AIMED?
#
# THE CONCERN (jba). The head's ORIENTATION target is genuinely unchanged by convaz (xHeadHat = R(psi)*rF8 is
# independent of rConv), so the head-tilt artefact cannot recur. BUT with rF8 pinned at the site and psi pinned
# by the latch, the CONVERTER JOINT is forced to C = xF8 - R(psi)(rF8 - rConv): convaz DISPLACES C by |d rConv|
# (3.5 nm at alpha=60). The lever+S2 must accommodate that, and if it stores strain, the strain relaxes once per
# attachment and pushes the filament -- sign set by sign(alpha). g5 charges the converter spring and the head
# orientation but NOT S2 beam strain, so that strain is UNCHARGED: the same accounting gap as the tilt case.
#
# WHY NOT TEST IT WITH VELOCITY. The alpha-difference is ~1.5 um/s; resolving it against the filament's wander
# needs ~0.5 s x 3 seeds x 2 arms, ~20 h/arm under current contention. Too slow for a control.
#
# THE CHEAP DECISIVE TEST: state-resolved axial force. Strain stored AT BINDING is already pushing in the
# PRE-stroke state (ADP.Pi); the stroke can only act AFTER the ADP.Pi->ADP transition. So:
#   pre-stroke fax alpha-dependent  => BINDING-GEOMETRY LEVER (jba's concern confirmed)
#   pre-stroke fax alpha-INDEPENDENT, post-stroke fax alpha-dependent => STROKE AIMING (legitimate)
# Plus the stroke controls: -dtheta 0 (no stroke => nothing to aim) and -dtheta 60 (REVERSED => an aiming
# effect must flip sign, a binding-strain effect must not).
set -u
OUT=${OUT:-RUN_LOGS/motor_audit/campaigns_2026-09/CONVAZ_LEVER}
# s_a00 / s_a60 are REUSED from the first (canonical-stroke) pass -- not re-run.
STEPS=${STEPS:-400000}      # 0.05 s: plenty for a force statistic conditioned on state
JOBS=${JOBS:-2}
mkdir -p "$OUT"
BASE="-run -gpu -devicecull -nohires -noviz -density 200 -matx 10.0 -maty 2.0 -filx 2.5 -eta 0.01 -dt 1.25e-7 -steps $STEPS -target 9.0 -filsegs 1 -triad -convaz-nocomp -seed 20260901"
run(){ rm -rf "$OUT/$1"; ./scripts/run_gpu_monitored.sh ./scripts/run_site_normal_glide_gpu_dtheta.sh \
       $BASE $2 -out "$OUT/$1" > "$OUT/$1.log" 2>&1; echo "done $1"; }
slot(){ while [ "$(jobs -rp | wc -l)" -ge "$JOBS" ]; do wait -n; done; }
slot; run "n_a00" "-stroke-swing 0"               &   # NO stroke, alpha=0
slot; run "n_a60" "-convaz 60 -stroke-swing 0"   &   # NO stroke, alpha=+60
slot; run "r_a00" "-stroke-swing -60"            &   # REVERSED stroke, alpha=0
slot; run "r_a60" "-convaz 60 -stroke-swing -60" &   # REVERSED stroke, alpha=+60
slot; run "g_a00" "-stroke-swing 60"             &   # GATE: explicit canonical must equal the s_* arms
slot; run "g_a60" "-convaz 60 -stroke-swing 60"  &
wait
echo "=== CONVAZ_LEVER done ==="
printf "%-8s%10s%12s%14s%14s%10s\n" arm v_um_s faxMean preStroke postStroke avgB
for a in s_a00 s_a60 g_a00 g_a60 n_a00 n_a60 r_a00 r_a60; do
  L="$OUT/$a.log"
  v=$(grep -oE "full-run LS velocity = [-+0-9.]+" $L | grep -oE "[-+0-9.]+$")
  fm=$(grep -oE "mean axial force per bound head = [-+0-9.]+" $L | grep -oE "[-+0-9.]+$")
  pre=$(grep -oE "PRE-stroke ADP.Pi [-+0-9.]+" $L | grep -oE "[-+0-9.]+$")
  post=$(grep -oE "POST-stroke ADP [-+0-9.]+" $L | grep -oE "[-+0-9.]+$")
  b=$(grep -oE "avgBound = [0-9.]+" $L | head -1 | grep -oE "[0-9.]+")
  printf "%-8s%10s%12s%14s%14s%10s\n" "$a" "$v" "$fm" "$pre" "$post" "$b"
done
