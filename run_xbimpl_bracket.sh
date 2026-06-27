#!/bin/bash
# IMPLICIT_CROSSBRIDGE — bracket the LARGEST FAITHFUL dt (where each scheme reaches the converged ~1050).
cd /home/jba/Code/SoftBox
SCENE="-nodes 200 -nfil 500 -box 5.0"
RAW=RUN_LOGS/2026-06-26_xbimplicit_contractile.txt
GREP="DTROW|SLHIST|RELROW|steps/s|BLOW|NON-FINITE|FAIL|STABILITY"
status() { echo "[$(date +%H:%M)] xbimpl-bracket $1" > .last_run_status; }
steps_for() { case "$1" in 2e-6) echo 50000;; 5e-6) echo 20000;; 1.5e-5) echo 6667;; esac; }
run() { local label="$1" dt="$2" extra="$3" M; M=$(steps_for "$dt"); status "$label dt=$dt ($M)"
  echo "=== $label dt=$dt steps=$M simT=0.10 $extra ($(date +%H:%M)) ===" | tee -a "$RAW"
  ./run_1x.sh -gpu -dtconv $SCENE -dt "$dt" -seed 1 -steps "$M" $extra 2>&1 | grep -E "$GREP" | sed "s/^/[$label dt=$dt] /" | tee -a "$RAW"; }
echo "--- bracket sweep $(date) ---" | tee -a "$RAW"
run IMPL 5e-6 "-xbimplicit"; run IMPL 1.5e-5 "-xbimplicit"; run EXPL 5e-6 ""; run IMPL 2e-6 "-xbimplicit"
status "DONE bracket"; echo "bracket done $(date)" | tee -a "$RAW"
