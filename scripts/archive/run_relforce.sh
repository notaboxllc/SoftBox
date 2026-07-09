#!/bin/bash
# RELEASE_FORCE_INPUT study (MEASUREMENT-ONLY) — characterize the dt→0 lumped catch-slip release
# (Stage 1) and test a time-averaged release-force input (Stage 2). Same 0.5× dt-study scene
# (box 5.0, 200 nodes, 500 fil), v2-GPU device-resident. dt / τ_avg the only variables; no physics edit.
#   ./run_relforce.sh stage1            # fine-dt sweep, instantaneous force (the converged reference)
#   ./run_relforce.sh stage2            # τ_avg sweep at dt=1e-5 (+nocap), the time-averaged release
#   ./run_relforce.sh stage2cap         # τ_avg sweep at dt=1e-5 with the 12 pN cap ON
#   ./run_relforce.sh collapse          # τ_avg at the COLLAPSED dt=2e-5 (where overshoot suppression matters)
#   ./run_relforce.sh cmp               # CPU≡GPU spot-check (short, averaged path)
cd /home/jba/Code/SoftBox
SCENE="-nodes 200 -nfil 500 -box 5.0"
S1=RUN_LOGS/2026-06-25_relforce_stage1.txt
S2=RUN_LOGS/2026-06-25_relforce_stage2.txt
status() { echo "[$(date +%H:%M)] relforce $1" > .last_run_status; }

# matched simT; steps = simT/dt
run1() {  # dt simT
  local dt="$1" simT="$2" M; M=$(python3 -c "print(int($simT/$dt))")
  status "stage1 dt=$dt ($M steps, simT $simT)"
  echo "=== STAGE1 dt=$dt steps=$M simT=$simT  ($(date +%H:%M)) ===" | tee -a "$S1"
  ./run_1x.sh -gpu -dtconv $SCENE -dt "$dt" -seed 1 -steps "$M" 2>&1 \
    | grep -E "RELROW|DTROW|DTHIST|steps/s|NON-FINITE|FAIL" | sed "s/^/[dt=$dt] /" | tee -a "$S1"
}

run2() {  # dt tauavg simT extraflag
  local dt="$1" tau="$2" simT="$3" extra="$4" M; M=$(python3 -c "print(int($simT/$dt))")
  status "stage2 dt=$dt tau=$tau $extra ($M steps)"
  echo "=== STAGE2 dt=$dt tauavg=$tau $extra steps=$M simT=$simT  ($(date +%H:%M)) ===" | tee -a "$S2"
  ./run_1x.sh -gpu -dtconv $SCENE -dt "$dt" $extra -seed 1 -steps "$M" 2>&1 \
    | grep -E "RELROW|DTROW|DTHIST|tauavg|steps/s|NON-FINITE|FAIL" | sed "s/^/[dt=$dt tau=$tau $extra] /" | tee -a "$S2"
}
# tauavg helper: pass -tauavg only when tau != 0
run2t() { local dt="$1" tau="$2" simT="$3" cap="$4"
  if [ "$tau" = "0" ]; then run2 "$dt" "inst" "$simT" "$cap"; else run2 "$dt" "$tau" "$simT" "-tauavg $tau $cap"; fi; }

case "${1:-stage1}" in
  stage1) : > "$S1"; echo "RELFORCE STAGE 1 (fine-dt, instantaneous release) — $(date)" | tee -a "$S1"
    run1 1e-5 0.10; run1 5e-6 0.10; run1 2e-6 0.10; run1 1e-6 0.06
    status "DONE stage1"; echo "stage1 done $(date)" | tee -a "$S1" ;;
  stage2) : > "$S2"; echo "RELFORCE STAGE 2 (tauavg sweep @1e-5, CAP OFF) — $(date)" | tee -a "$S2"
    # cap off (-nocap) isolates the catch-slip averaging channel; inst baseline first
    for tau in 0 1e-4 2e-4 5e-4 1e-3 2e-3 5e-3 1e-2; do run2t 1e-5 "$tau" 0.10 "-nocap"; done
    status "DONE stage2"; echo "stage2 done $(date)" | tee -a "$S2" ;;
  shortwin) echo "RELFORCE SHORT-τ probe (2..5 steps @1e-5, CAP OFF — is there a narrow window at 160/s?) — $(date)" | tee -a "$S2"
    for tau in 2e-5 3e-5 5e-5; do run2t 1e-5 "$tau" 0.10 "-nocap"; done
    status "DONE shortwin"; echo "shortwin done $(date)" | tee -a "$S2" ;;
  stage2cap) echo "RELFORCE STAGE 2 (tauavg sweep @1e-5, CAP ON) — $(date)" | tee -a "$S2"
    for tau in 0 5e-4 1e-3 2e-3 5e-3; do run2t 1e-5 "$tau" 0.10 ""; done
    status "DONE stage2cap"; echo "stage2cap done $(date)" | tee -a "$S2" ;;
  collapse) echo "RELFORCE COLLAPSE PROBE (tauavg @ dt=2e-5, where overshoot collapses binding) — $(date)" | tee -a "$S2"
    for tau in 0 1e-3 2e-3 5e-3 1e-2; do run2t 2e-5 "$tau" 0.10 "-nocap"; done
    status "DONE collapse"; echo "collapse done $(date)" | tee -a "$S2" ;;
  cmp) echo "RELFORCE CPU≡GPU spot-check (averaged path) — $(date)"
    ./run_1x.sh -cmp -dtconv $SCENE -dt 1e-5 -tauavg 1e-3 -nocap -seed 1 -steps 300 2>&1 | grep -E "Δ|delta|max|bound|CPU|GPU|RELROW" | tail -20 ;;
  *) echo "usage: $0 [stage1|stage2|stage2cap|collapse|cmp]"; exit 1 ;;
esac
