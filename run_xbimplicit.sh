#!/bin/bash
# IMPLICIT_CROSSBRIDGE — locally-implicit cross-bridge spring (the integrator lever the five force-law
# failures pointed at). MEASUREMENT-ONLY, flag-gated -xbimplicit (default-off ⇒ explicit Hookean, byte-id).
# 0.5× dt-study scene (box 5.0, 200 nodes, 500 fil), matched SIM-TIME, GPU device-resident (sole aorus).
#   Stage 0 : fine-dt EXPLICIT reference (the converged target: bound B*, signed-load SLHIST negative tail).
#   Stage 1 : implicit @ 1e-5 vs explicit @ 1e-5  — does implicit reproduce fine-dt, as well/better than explicit?
#   Stage 2 : implicit dt SWEEP {1e-5,2e-5,5e-5,1e-4} vs explicit same — stable AND faithful? how far up?
cd /home/jba/Code/SoftBox
SCENE="-nodes 200 -nfil 500 -box 5.0"
RAW=RUN_LOGS/2026-06-26_xbimplicit_contractile.txt
GREP="DTROW|SLHIST|RELROW|steps/s|BLOW|NON-FINITE|FAIL|STABILITY|xbimplicit"
status() { echo "[$(date +%H:%M)] xbimplicit $1" > .last_run_status; }

# matched SIM-TIME 0.10 s ⇒ steps(dt)=0.10/dt
steps_for() { case "$1" in 5e-7) echo 200000;; 1e-6) echo 100000;; 2e-6) echo 50000;; 5e-6) echo 20000;; 1e-5) echo 10000;; 2e-5) echo 5000;; 5e-5) echo 2000;; 1e-4) echo 1000;; esac; }

run() {  # label dt extraflags
  local label="$1" dt="$2" extra="$3" M; M=$(steps_for "$dt")
  status "$label dt=$dt ($M steps)"
  echo "=== $label dt=$dt steps=$M simT=0.10 $extra  ($(date +%H:%M)) ===" | tee -a "$RAW"
  ./run_1x.sh -gpu -dtconv $SCENE -dt "$dt" -seed 1 -steps "$M" $extra 2>&1 \
    | grep -E "$GREP" | sed "s/^/[$label dt=$dt] /" | tee -a "$RAW"
}

case "${1:-all}" in
  ref)   : > "$RAW"; echo "IMPLICIT_CROSSBRIDGE contractile — started $(date)" | tee -a "$RAW"
         run EXPL-FINE 1e-6 ""        # the converged reference (bound + signed-load tail)
         run EXPL-FINE 2e-6 ""        # convergence check
         status "DONE ref" ;;
  sweep) echo "--- Stage 1+2 sweep $(date) ---" | tee -a "$RAW"
         for dt in 1e-5 2e-5 5e-5 1e-4; do run IMPL "$dt" "-xbimplicit"; done
         for dt in 1e-5 2e-5 5e-5 1e-4; do run EXPL "$dt" ""; done
         status "DONE sweep" ;;
  all)   : > "$RAW"; echo "IMPLICIT_CROSSBRIDGE contractile — started $(date)" | tee -a "$RAW"
         run EXPL-FINE 1e-6 ""
         run EXPL-FINE 2e-6 ""
         for dt in 1e-5 2e-5 5e-5 1e-4; do run IMPL "$dt" "-xbimplicit"; done
         for dt in 1e-5 2e-5 5e-5 1e-4; do run EXPL "$dt" ""; done
         status "DONE all" ;;
  *) echo "usage: $0 [all|ref|sweep]"; exit 1 ;;
esac
echo "xbimplicit ${1:-all} done $(date)" | tee -a "$RAW"
