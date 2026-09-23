#!/usr/bin/env bash
# TWIRL WITH THE DIFFUSIVE BACKGROUND REMOVED, AT TWO ENGAGEMENT REGIMES (jba, 2026-09-23).
#
# WHY. The eps=0 "diffusive twirl" is NOT an artefact -- it is free rotational Brownian motion, and
# our measurement reproduces the analytic value:
#     gamma_roll = 4*pi*eta*L*R^2 = 3.24e-24 N.m.s   (R^2: actin is 3.5 nm thin)
#     D_roll     = kT/gamma       = 1278 rad^2/s  ->  8.05 turns RMS at 1 s   (we measure 6.87)
# A 2.1 um filament held by ~1 head genuinely tumbles several turns per second. Chasing a ~1 turn/um
# twirl under that needs ~95 s on a ~286 um mat -- unreachable.
#
# THE REAL ASSAY DOES NOT HAVE THIS PROBLEM, and not because of solvent drag: a 20 um filament in
# water has about the same gamma_roll as ours (10x length cancels 10x viscosity). It is ENGAGEMENT --
# at full lawn density 10-50 heads are bound at once and the cross-bridges clamp roll to a rigid
# surface. That clamp is both what makes twirl observable and what generates it. We deliberately run
# avgBound ~1, which is precisely the regime where roll is unconstrained and twirl is unmeasurable.
#
# TWO CHANGES, ONE DIAGNOSTIC AND ONE PHYSICAL:
#   -norollbrownian  kills ONLY the Brownian torque about the body-fixed axial direction, keeping the
#                    drag. Leaves roll rate = tau_motor / gamma_roll: a CLEAN deterministic readout of
#                    the chiral torque with NO diffusive background. Non-FDT, diagnostic only.
#   density 800      avgBound ~6-10 (scales ~density^1.28 from the measured d050..d400 probe), the
#                    engaged regime the experiment actually occupies -- and jba's publication density.
#
# The d800 eps=0 arm is run BOTH ways: roll-Brownian off gives tau cleanly, roll-Brownian on shows
# what that torque is worth against real thermal noise in the engaged regime.
set -u
OUT=${OUT:-RUN_LOGS/motor_audit/campaigns_2026-09/ROLLCLAMP_TWIRL}
mkdir -p "$OUT"
STEPS=${STEPS:-8000000}       # 1.0 s at dt 1.25e-7
BASE="-run -gpu -devicecull -nohires -noviz -matx 10.0 -maty 2.0 -filx 2.5 -eta 0.01 -dt 1.25e-7 -steps $STEPS -target 3.5 -filsegs 1 -triad -convaz-nocomp -seed 20260901"
export SOFTBOX_XMX=${SOFTBOX_XMX:-8G}
run(){ rm -rf "$OUT/$1"; ./scripts/run_gpu_monitored.sh ./scripts/run_site_normal_glide_gpu.sh \
       $BASE -density $2 -stroke-skew $3 $4 -out "$OUT/$1" > "$OUT/$1.log" 2>&1; echo "done $1"; }
# few-head regime (our current one), background removed
run "d200_eps0"  200 0  "-norollbrownian" &
run "d200_epsP4" 200 4  "-norollbrownian" &
run "d200_epsM4" 200 -4 "-norollbrownian" &
# ENGAGED regime, avgBound ~6-10, background removed
run "d800_eps0"  800 0  "-norollbrownian" &
run "d800_epsP4" 800 4  "-norollbrownian" &
run "d800_epsM4" 800 -4 "-norollbrownian" &
# FDT-correct control: what the torque is worth against real thermal noise when engaged
run "d800_eps0_fdt" 800 0 "" &
wait
echo "=== ROLLCLAMP_TWIRL complete ==="
