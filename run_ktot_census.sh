#!/bin/bash
# STEP-3 K_tot census — per-segment collective cross-bridge stiffness vs the EOM ~1.4/~3.8 pN/nm thresholds.
# Read-only over EXPLICIT gliding dynamics at dt=1e-5, v1box (fast; same per-segment load as -full at a given
# density), seed 0, 60k steps (warmStep=20k ⇒ ~400 steady samples). Single sequential batch, no concurrency.
cd /home/jba/Code/SoftBox
LOG=RUN_LOGS/2026-07-05_ktot_census_clean.txt
: > $LOG
echo "# STEP-3 K_tot census (v1box) $(date)" >> $LOG
echo "# K_tot=k_s·myoSpring pN/nm; marginal>=1.4, blow-up>=3.8 (EOM_STABILITY). dt=1e-5, seed 0, 60k steps." >> $LOG
cens() {
  echo "=== $* ===" >> $LOG
  ./run_gliding.sh -gpu -v1box -grid -ktotcensus -seed 0 "$@" 60000 2>&1 | grep -E "KTOT_ROW|KTOT_KS_HIST|GRID_ROW" >> $LOG
  echo "  [$(date +%H:%M:%S)] $* done" >> .last_run_status
}
echo "## STEP-2 kinetics: -lymntaylor -adppibind (low binding duty, the dt-ladder regime)" >> $LOG
cens -lymntaylor -adppibind -coltol 10 -density 1000
cens -lymntaylor -adppibind -coltol 10 -density 2000
cens -lymntaylor -adppibind -coltol 14 -density 1000
echo "## DEFAULT kinetics (high binding duty — max-load gliding case)" >> $LOG
cens -coltol 10 -density 1000
cens -coltol 10 -density 2000
cens -coltol 14 -density 2000
echo "# CENSUS DONE" >> $LOG
echo "CENSUS DONE $(date)" >> .last_run_status
