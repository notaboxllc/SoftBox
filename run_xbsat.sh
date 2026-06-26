#!/bin/bash
# SATURATED_CROSSBRIDGE_DIAGNOSTIC (MEASUREMENT-ONLY, additive flag -xbsat, default-off).
# Use the 2 pN/nm @ 1e-5 catch-explosion COLLAPSE as a clean bench: what saturation shape/magnitude lets
# 2 pN/nm RECOVER its OWN faithfully-integrated (fine-dt) self — the SIGNED-LOAD DISTRIBUTION, not just
# the bound count. F8 translational ONLY; binding search / catch-slip FORMULA / F9-F10 / 12 pN cap untouched.
# Scene = the 0.5x dt-study contractile scene (V2OneX -dtconv): box 5.0, 200 nodes x 24 singlet, 500 fil.
# v2-GPU device-resident (the 5-graph chained split, ~84 steps/s @ 0.5x). BoA-v1ref untouched.
#   ./run_xbsat.sh stage1 | stage2 | all
cd /home/jba/Code/SoftBox
S1=RUN_LOGS/2026-06-26_xbsat_stage1_finedt.txt
S2=RUN_LOGS/2026-06-26_xbsat_stage2_sweep.txt
SCENE="-nodes 200 -nfil 500 -box 5.0"
GREP="xbsat|DTROW|RELROW|SLHIST|steps/s|GPU:|NON-FINITE|FAIL|STABILITY|Exception|unimplemented"
status() { echo "[$(date +%H:%M)] xbsat $1" > .last_run_status; }

# ---- STAGE 1: the fine-dt 2 pN/nm REFERENCE (the slow part). 2 pN/nm faithfully integrated at small dt.
#      By k.dt scaling, k=2@2e-6 == k=1@4e-6 (near plateau); k=2@1e-6 == k=1@2e-6 (deep plateau).
#      Matched sim-time 0.06 s ⇒ steps = 0.06/dt (binding equilibrium fills in << this). ----
run_stage1() {
  : > "$S1"; echo "XBSAT STAGE 1 — fine-dt 2 pN/nm REFERENCE (k=2, plain Hookean, dt small) — started $(date)" | tee -a "$S1"
  for cfg in "2e-6 30000" "1e-6 60000"; do
    set -- $cfg; dt=$1; M=$2
    for s in 1 2; do
      status "STAGE1 k=2 dt=$dt seed=$s ($M steps, simT 0.06)"
      echo "=== STAGE1 myospring=2.0 dt=$dt seed=$s steps=$M simT=0.06  ($(date +%H:%M)) ===" | tee -a "$S1"
      ./run_1x.sh -gpu -dtconv $SCENE -dt "$dt" -myospring 2.0 -seed "$s" -steps "$M" 2>&1 \
        | grep -E "$GREP" | sed "s/^/[k2 dt=$dt s=$s] /" | tee -a "$S1"
    done
  done
  status "DONE stage1"; echo "stage1 done $(date)" | tee -a "$S1"
}

# ---- STAGE 2: saturation sweep at 2 pN/nm @ dt=1e-5 (the COLLAPSE bench). Each run simT 0.08 = 8000 steps.
#      Decisive metric = does the SLHIST signed-load distribution (p10/fracNeg/meanNeg) + bound match Stage 1?
#      modes: 1 sym-tanh, 2 sym-hardclip, 3 asym(comp)-tanh, 4 asym(comp)-hardclip.  -xbsat <mode> <Fmax_pN> <onset_pN> ----
sat_run() {  # mode Fmax onset seed
  local mode=$1 fmax=$2 onset=$3 s=$4
  status "STAGE2 mode=$mode Fmax=$fmax onset=$onset seed=$s"
  echo "=== STAGE2 k=2 dt=1e-5 xbsat mode=$mode Fmax=$fmax onset=$onset seed=$s steps=8000  ($(date +%H:%M)) ===" | tee -a "$S2"
  ./run_1x.sh -gpu -dtconv $SCENE -dt 1e-5 -myospring 2.0 -xbsat "$mode" "$fmax" "$onset" -seed "$s" -steps 8000 2>&1 \
    | grep -E "$GREP" | sed "s/^/[m=$mode F=$fmax o=$onset s=$s] /" | tee -a "$S2"
}
run_stage2() {
  : > "$S2"; echo "XBSAT STAGE 2 — saturation sweep @ 2 pN/nm dt=1e-5 (recover the fine-dt distribution) — started $(date)" | tee -a "$S2"
  # reference: plain 2 pN/nm @ 1e-5 (the collapse) for direct comparison
  status "STAGE2 collapse reference (no sat)"
  echo "=== STAGE2 k=2 dt=1e-5 NO-SAT (collapse reference) seed=1 steps=8000  ($(date +%H:%M)) ===" | tee -a "$S2"
  ./run_1x.sh -gpu -dtconv $SCENE -dt 1e-5 -myospring 2.0 -seed 1 -steps 8000 2>&1 \
    | grep -E "$GREP" | sed "s/^/[m=0 collapse s=1] /" | tee -a "$S2"
  # hard clips (modes 2,4): onset ignored ⇒ onset=0
  for mode in 2 4; do for F in 4 5 6 8; do sat_run $mode $F 0 1; done; done
  # smooth tanh (modes 1,3): Fmax x onset
  for mode in 1 3; do for F in 4 5 6 8; do for o in 0 3; do sat_run $mode $F $o 1; done; done; done
  status "DONE stage2"; echo "stage2 sweep done $(date)" | tee -a "$S2"
}

# ---- finalists: multi-seed confirm of a chosen (mode,Fmax,onset). Args after 'final': mode Fmax onset ----
run_final() {
  local mode=$1 fmax=$2 onset=$3
  echo "=== STAGE2 FINALIST mode=$mode Fmax=$fmax onset=$onset (seeds 1-3, 10000 steps)  ($(date)) ===" | tee -a "$S2"
  for s in 1 2 3; do
    status "FINAL mode=$mode Fmax=$fmax onset=$onset seed=$s"
    ./run_1x.sh -gpu -dtconv $SCENE -dt 1e-5 -myospring 2.0 -xbsat "$mode" "$fmax" "$onset" -seed "$s" -steps 10000 2>&1 \
      | grep -E "$GREP" | sed "s/^/[FINAL m=$mode F=$fmax o=$onset s=$s] /" | tee -a "$S2"
  done
}

case "${1:-all}" in
  stage1) run_stage1 ;;
  stage2) run_stage2 ;;
  final)  shift; run_final "$@" ;;
  all)    run_stage1; run_stage2 ;;
  *) echo "usage: $0 [stage1|stage2|all|final <mode> <Fmax> <onset>]"; exit 1 ;;
esac
status "DONE"; echo "xbsat done $(date)"
