#!/bin/bash
# CROSS-BRIDGE STIFFNESS SENSITIVITY SWEEP (MEASUREMENT-ONLY) — is a softer (still-measured-valid)
# myoSpring behaviorally indistinguishable from the default 1 pN/nm? The cheap cut before any PAIRS build.
# Vary ONLY myoSpring (Hookean F8 unchanged) at the validated dt=1e-5, on two scenes:
#   (1) gliding assay   — GlidingHarness -grid (NET velocity, avgBound) — the clean interpretable observable
#   (2) contractile/dt-study scene — V2OneXHarness -dtconv (release internals: off-rate, signed load,
#       catch/slip, bound, lifetime + RgXY extent)
# Production default (1 pN/nm) UNCHANGED; BoA-v1ref untouched. v2-GPU device-resident both scenes.
#   ./run_xbstiff.sh gliding | contractile | all
cd /home/jba/Code/SoftBox
G=RUN_LOGS/2026-06-26_xbstiff_gliding.txt
C=RUN_LOGS/2026-06-26_xbstiff_contractile.txt
SCENE="-nodes 200 -nfil 500 -box 5.0"
SPRINGS="0.5 1.0 2.0"
status() { echo "[$(date +%H:%M)] xbstiff $1" > .last_run_status; }

run_gliding() {
  : > "$G"; echo "XBSTIFF GLIDING (v1box 4x1, 2000 heads, dt=1e-5, -grid) — started $(date)" | tee -a "$G"
  for k in $SPRINGS; do
    for s in 1 2 3 4; do
      status "gliding myospring=$k seed=$s"
      echo "=== GLIDING myospring=$k seed=$s steps=10000  ($(date +%H:%M)) ===" | tee -a "$G"
      ./run_gliding.sh -gpu -v1box -grid -myospring "$k" -seed "$s" 10000 2>&1 \
        | grep -E "GRID_ROW|netΔx|NON-FINITE|FAIL" | sed "s/^/[k=$k s=$s] /" | tee -a "$G"
    done
  done
  status "DONE gliding"; echo "gliding done $(date)" | tee -a "$G"
}

run_contractile() {
  : > "$C"; echo "XBSTIFF CONTRACTILE (0.5x dt-study scene, dt=1e-5, -dtconv, simT 0.10 = 10000 steps) — started $(date)" | tee -a "$C"
  for k in $SPRINGS; do
    for s in 1 2 3; do
      status "contractile myospring=$k seed=$s"
      echo "=== CONTRACTILE myospring=$k seed=$s steps=10000 simT=0.10  ($(date +%H:%M)) ===" | tee -a "$C"
      ./run_1x.sh -gpu -dtconv $SCENE -dt 1e-5 -myospring "$k" -seed "$s" -steps 10000 2>&1 \
        | grep -E "DTROW|RELROW|SLHIST|steps/s|GPU:|NON-FINITE|FAIL|STABILITY" | sed "s/^/[k=$k s=$s] /" | tee -a "$C"
    done
  done
  status "DONE contractile"; echo "contractile done $(date)" | tee -a "$C"
}

case "${1:-all}" in
  gliding)     run_gliding ;;
  contractile) run_contractile ;;
  all)         run_gliding; run_contractile ;;
  *) echo "usage: $0 [gliding|contractile|all]"; exit 1 ;;
esac
status "DONE all"; echo "xbstiff ALL done $(date)"
