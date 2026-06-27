#!/bin/bash
# SUBSTEP_FEASIBILITY diagnostic (MEASUREMENT-ONLY) — the two numbers that decide whether to build the
# bound-cross-bridge sub-step inner loop: (1) the SPEEDUP CEILING (bound fraction × bound-slice CPU-time
# fraction X ⇒ implied net speedup @10× inner) and (2) the SITE-HANDLING tier (per-outer-dt site motion vs
# the cross-bridge stretch). CPU runner (the host arrays must be current each step). Sole occupant of aorus.
#   ./run_substep.sh smoke      # quick sanity on all three scenes
#   ./run_substep.sh all        # the full matrix (operating dt + fine-dt converged), background-friendly
cd /home/jba/Code/SoftBox
RAW=RUN_LOGS/2026-06-26_substep_feasibility.txt
status() { echo "[$(date +%H:%M)] substep $1" > .last_run_status; }
mkdir -p RUN_LOGS

DENSE="-nodes 400 -nfil 1000 -box 7.071"     # the 1× dense contractile flagship (benchmark-1x standard)
HALF="-nodes 200 -nfil 500 -box 5.0"         # the 0.5× dt-study scene

gl() {  # label dt steps
  echo "=== GLIDING $1 (dt=$2, $3 steps) $(date +%H:%M) ===" | tee -a "$RAW"
  status "gliding $1"
  ./run_gliding.sh -v1box -substep -dt "$2" -outerdt 1e-4 "$3" 2>&1 \
    | grep -E "SUBSTEP|MEASURE|CORE|FULL|SITE-MOTION|net |decomposition|operating length|VERDICT|====|STEADY|avgBound" | tee -a "$RAW"
}
ct() {  # label scene dt steps
  echo "=== CONTRACTILE $1 (dt=$3, $4 steps) $(date +%H:%M) ===" | tee -a "$RAW"
  status "contractile $1"
  ./run_1x.sh -cpu -substep -dtconv $2 -dt "$3" -outerdt 1e-4 -seed 1 -steps "$4" 2>&1 \
    | grep -E "SUBSTEP|MEASURE|CORE|FULL|SITE-MOTION|net |decomposition|operating length|VERDICT|====|DTROW|steps/s" | tee -a "$RAW"
}

case "${1:-smoke}" in
  smoke)
    gl SMOKE 1e-5 2000
    ct SMOKE-HALF "$HALF" 1e-5 2000
    status "DONE smoke" ;;
  all)
    : > "$RAW"; echo "SUBSTEP_FEASIBILITY — started $(date)" | tee -a "$RAW"
    gl OPERATING 1e-5 20000
    gl FINE      1e-6 40000
    ct HALF-OPERATING "$HALF"  1e-5 20000
    ct DENSE-OPERATING "$DENSE" 1e-5 20000
    ct HALF-FINE      "$HALF"  1e-6 50000
    ct DENSE-FINE     "$DENSE" 1e-6 50000
    status "DONE all"; echo "substep all done $(date)" | tee -a "$RAW" ;;
  *) echo "usage: $0 [smoke|all]"; exit 1 ;;
esac
