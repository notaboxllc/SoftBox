#!/bin/bash
# BOUND_THERMAL_CORRELATION study (MEASUREMENT-ONLY) — constraint-aware thermal forcing of bound pairs:
# correlate the bound head's thermal kick with its filament contact's (BondThermalCorrelationSystem),
# to tame the cross-bridge signed-load distribution at coarse dt (toward 1e-4). Changes ONLY the
# thermal-noise correlation on bound heads; no search / spring-law / catch-slip edit. Same 0.5× dt-study
# scene (box 5.0, 200 nodes, 500 fil), v2-GPU device-resident. SLHIST = the signed-load distribution
# (negative tail), the decisive target.
#   ./run_bondcorr.sh verify     # α=0 byte-identity (no-flag ≡ -bondcorr 0) — the regression guard
#   ./run_bondcorr.sh stage1     # fine-dt SLHIST reference (1e-5 overshot + 2e-6 converged), instantaneous
#   ./run_bondcorr.sh stage2     # α-sweep @ dt=1e-4, CAP OFF — does an α reproduce the fine-dt negative tail?
#   ./run_bondcorr.sh stage2cap  # α-sweep @ dt=1e-4, CAP ON (production-safety confirm)
#   ./run_bondcorr.sh dtweak     # best-α at dt 1e-4 / 5e-5 / 1e-5 (the dt-weakness check)
#   ./run_bondcorr.sh cmp        # CPU≡GPU spot-check (bondcorr path)
cd /home/jba/Code/SoftBox
SCENE="-nodes 200 -nfil 500 -box 5.0"
LOG=RUN_LOGS/2026-06-25_bondcorr.txt
status() { echo "[$(date +%H:%M)] bondcorr $1" > .last_run_status; }

# matched simT; steps = simT/dt
run() {  # tag dt simT extraflags
  local tag="$1" dt="$2" simT="$3" extra="$4" M; M=$(python3 -c "print(int($simT/$dt))")
  status "$tag dt=$dt $extra ($M steps)"
  echo "=== $tag dt=$dt $extra steps=$M simT=$simT  ($(date +%H:%M)) ===" | tee -a "$LOG"
  ./run_1x.sh -gpu -dtconv $SCENE -dt "$dt" $extra -seed 1 -steps "$M" 2>&1 \
    | grep -E "SLHIST|RELROW|DTROW|steps/s|NON-FINITE|FAIL|bondcorr" | sed "s/^/[$tag] /" | tee -a "$LOG"
}

case "${1:-stage1}" in
  verify)
    echo "BONDCORR VERIFY — α=0 byte-identity (no-flag ≡ -bondcorr 0) — $(date)" | tee -a "$LOG"
    echo "--- CPU no-flag ---"   | tee -a "$LOG"; ./run_1x.sh -cpu -dtconv $SCENE -dt 1e-5 -seed 1 -steps 300 2>&1 | grep -E "DTROW|SLHIST" | tee -a "$LOG"
    echo "--- CPU bondcorr 0 ---"| tee -a "$LOG"; ./run_1x.sh -cpu -dtconv $SCENE -dt 1e-5 -bondcorr 0 -seed 1 -steps 300 2>&1 | grep -E "DTROW|SLHIST" | tee -a "$LOG"
    echo "--- GPU no-flag ---"   | tee -a "$LOG"; ./run_1x.sh -gpu -dtconv $SCENE -dt 1e-5 -seed 1 -steps 300 2>&1 | grep -E "DTROW|SLHIST" | tee -a "$LOG"
    echo "--- GPU bondcorr 0 ---"| tee -a "$LOG"; ./run_1x.sh -gpu -dtconv $SCENE -dt 1e-5 -bondcorr 0 -seed 1 -steps 300 2>&1 | grep -E "DTROW|SLHIST" | tee -a "$LOG"
    status "DONE verify" ;;
  stage1) : > "$LOG"; echo "BONDCORR STAGE 1 — fine-dt SLHIST reference (instantaneous, no correlation) — $(date)" | tee -a "$LOG"
    run s1_1e-5 1e-5 0.10 ""        # the overshot reference (fast)
    run s1_2e-6 2e-6 0.10 ""        # the converged reference (~50k steps)
    status "DONE stage1" ;;
  stage2) echo "BONDCORR STAGE 2 — α-sweep @ dt=1e-4, CAP OFF (-nocap) — $(date)" | tee -a "$LOG"
    run s2_a0.00 1e-4 0.10 "-nocap"
    for a in 0.30 0.50 0.70 0.85 0.95 0.99; do run "s2_a$a" 1e-4 0.10 "-bondcorr $a -nocap"; done
    status "DONE stage2" ;;
  stage2cap) echo "BONDCORR STAGE 2 CAP-ON — α-sweep @ dt=1e-4, 12 pN cap ON — $(date)" | tee -a "$LOG"
    run s2c_a0.00 1e-4 0.10 ""
    for a in 0.50 0.70 0.85 0.95; do run "s2c_a$a" 1e-4 0.10 "-bondcorr $a"; done
    status "DONE stage2cap" ;;
  dtweak) echo "BONDCORR dt-WEAKNESS — best-α at dt 1e-4 / 5e-5 / 1e-5 (CAP OFF) — $(date)" | tee -a "$LOG"
    for dt in 1e-4 5e-5 1e-5; do
      for a in 0.00 0.70 0.85 0.95; do run "dw_${dt}_a$a" "$dt" 0.10 "-bondcorr $a -nocap"; done
    done
    status "DONE dtweak" ;;
  ceiling) echo "BONDCORR dt-CEILING — α-rescue vs the deterministic spring-stability ceiling (k·dt/γ<2 ⇒ dt<3.8e-5), CAP OFF — $(date)" | tee -a "$LOG"
    # at each dt, α=0 (baseline) and the γ/dt-anchor α_pred = k·dt/(k·dt+γ): 2e-5→0.51, 3e-5→0.61, 4e-5→0.68, 5e-5→0.73, 1e-4→0.84
    run ceil_2e-5_a0    2e-5 0.10 "-nocap";              run ceil_2e-5_aPred 2e-5 0.10 "-bondcorr 0.51 -nocap"
    run ceil_3e-5_a0    3e-5 0.10 "-nocap";              run ceil_3e-5_aPred 3e-5 0.10 "-bondcorr 0.61 -nocap"
    run ceil_4e-5_a0    4e-5 0.10 "-nocap";              run ceil_4e-5_aPred 4e-5 0.10 "-bondcorr 0.68 -nocap"
    run ceil_5e-5_a0    5e-5 0.10 "-nocap";              run ceil_5e-5_aPred 5e-5 0.10 "-bondcorr 0.73 -nocap"
    run ceil_1e-4_aPred 1e-4 0.10 "-bondcorr 0.84 -nocap"; run ceil_1e-4_a99 1e-4 0.10 "-bondcorr 0.99 -nocap"
    status "DONE ceiling" ;;
  cmp) echo "BONDCORR CPU≡GPU spot-check — $(date)"
    ./run_1x.sh -cmp -dtconv $SCENE -dt 1e-4 -bondcorr 0.85 -nocap -seed 1 -steps 300 2>&1 | grep -E "Δ|delta|max|bound|CPU|GPU|SLHIST|RESULT" | tail -25 ;;
  *) echo "usage: $0 [verify|stage1|stage2|stage2cap|dtweak|cmp]"; exit 1 ;;
esac
