#!/bin/bash
# BINDING-SEARCH REFORMULATION validation (MEASUREMENT-ONLY; geometric search stays the default).
# The rate search (BindingDetectionSystem.bindRate) replaces the per-step geometric capture with a
# per-sim-time ENCOUNTER RATE P=1-exp(-kOn*Δl*dt) at the SAME tight capture geometry (myoColTol), swept
# over the head path (formulation B). Validates dt-INVARIANCE (the decisive win) + the DISTRIBUTION vs
# the fine-dt reference + count B*≈1050 + CPU≡GPU. Same 0.5× scene as the dt-convergence study.
#   ./run_bindsearch.sh dtsweep   # ratesearch-B across dt {1e-5,5e-6,2e-6,1e-6}, matched simT 0.10 (the headline)
#   ./run_bindsearch.sh avsb      # formulation A (point) vs B (swept) at dt 1e-5 & 2e-6 (the fly-by argument)
#   ./run_bindsearch.sh cmp       # CPU≡GPU (-cmp) aggregate-within-SEM, short
cd /home/jba/Code/SoftBox
SCENE="-nodes 200 -nfil 500 -box 5.0"
KON=1e8                                       # diffusion-limited default (reproduces B*≈1050 + tight dist at fine dt; see findings)
RAW=RUN_LOGS/2026-06-25_bindsearch.txt
status() { echo "[$(date +%H:%M)] bindsearch $1" > .last_run_status; }
steps_for() { case "$1" in 1e-5) echo 10000;; 5e-6) echo 20000;; 2e-6) echo 50000;; 1e-6) echo 100000;; esac; }

run_rate() { # dt  [extra flags]
  local dt="$1"; shift; local M; M=$(steps_for "$dt")
  status "rate dt=$dt ($M steps) $*"
  echo "=== RATE-B dt=$dt steps=$M simT=0.10 kOn=$KON $*  ($(date +%H:%M)) ===" | tee -a "$RAW"
  ./run_1x.sh -gpu -ratesearch -kon "$KON" $SCENE -dt "$dt" -steps "$M" -dtconv "$@" 2>&1 \
    | grep -E "DTROW|DTHIST|steps/s|STABILITY|NON-FINITE|BLOW" | tail -4 \
    | sed "s/^/[rate dt=$dt $*] /" | tee -a "$RAW"
}

case "${1:-dtsweep}" in
  dtsweep) : > "$RAW"; echo "binding-search RATE-B dt-invariance sweep (matched simT 0.10) — $(date)" | tee -a "$RAW"
        for dt in 1e-5 5e-6 2e-6 1e-6; do run_rate "$dt"; done
        status "DONE dtsweep"; echo "bindsearch dtsweep done $(date)" | tee -a "$RAW"; exit 0 ;;
  avsb) echo "binding-search A(point) vs B(swept) — $(date)" | tee -a "$RAW"
        for dt in 1e-5 2e-6; do run_rate "$dt" -pointsearch; run_rate "$dt"; done
        status "DONE avsb"; exit 0 ;;
  cmp)  status "cmp CPU≡GPU"; echo "=== CPU≡GPU (-cmp) ratesearch-B dt=1e-5  ($(date)) ===" | tee -a "$RAW"
        ./run_1x.sh -cmp -ratesearch -kon "$KON" $SCENE -dt 1e-5 -steps 300 2>&1 \
          | grep -E "step|RESULT|bound=|within SEM|chaotic" | tee -a "$RAW"; exit 0 ;;
  *) echo "usage: $0 [dtsweep|avsb|cmp]"; exit 1 ;;
esac
