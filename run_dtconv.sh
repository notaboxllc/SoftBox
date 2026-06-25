#!/bin/bash
# dt-CONVERGENCE STUDY (MEASUREMENT-ONLY) — the largest FAITHFUL (no-tuning) mechanics dt.
# Same 0.5× scene (density-preserving half of the V2OneX 1× standard: box 5.0, 200 nodes, 500 fil),
# sweep dt to the SAME total simulated time (0.3 s) ⇒ matched-sim-time checkpoints, NOT matched steps.
# dt is the ONLY variable — NO tuning. v2-GPU device-resident path.
#   ./run_dtconv.sh <stage>   stage = 1 (coarse: 1 seed × 4 dt) | env (1e-5 envelope seeds) | ceil (ceiling-region seeds)
cd /home/jba/Code/SoftBox
RAW=RUN_LOGS/2026-06-24_dt_convergence.txt
SCENE="-nodes 200 -nfil 500 -box 5.0"
RUNNER="-gpu"
status() { echo "[$(date +%H:%M)] dtconv $1" > .last_run_status; }

# matched 0.3 s sim-time: steps(dt) = 0.3/dt
steps_for() { case "$1" in 1e-5) echo 30000;; 1.2e-5) echo 25000;; 1.5e-5) echo 20000;; 2e-5) echo 15000;; 5e-5) echo 6000;; 1e-4) echo 3000;; esac; }

run_one() {  # dt seed
  local dt="$1" seed="$2" M; M=$(steps_for "$dt")
  status "dt=$dt seed=$seed ($M steps)"
  echo "=== dt=$dt seed=$seed steps=$M  ($(date +%H:%M)) ===" | tee -a "$RAW"
  $RUNNER_PFX ./run_1x.sh $RUNNER -dtconv $SCENE -dt "$dt" -seed "$seed" -steps "$M" 2>&1 \
    | grep -E "DTROW|steps/s|BLOW|NON-FINITE|FAIL|STABILITY" \
    | sed "s/^/[dt=$dt seed=$seed] /" | tee -a "$RAW"
}

# --- BELOW-1e-5 wing (the binding-formulation investigation, 2026-06-25): matched SIM-TIME 0.2 s,
#     steps(dt)=0.2/dt. Sweeps dt below the 1e-5 reference to characterize the LOWER (search) wing and
#     test whether 1e-5 is a converged fixed point. Writes its own raw log. ---
BELOW_RAW=RUN_LOGS/2026-06-25_dt_below.txt
below_steps() { case "$1" in 2e-6) echo 100000;; 5e-6) echo 40000;; 1e-5) echo 20000;; 2e-5) echo 10000;; esac; }
run_below() {  # dt seed
  local dt="$1" seed="$2" M; M=$(below_steps "$dt")
  status "BELOW dt=$dt seed=$seed ($M steps, simT 0.2)"
  echo "=== BELOW dt=$dt seed=$seed steps=$M simT=0.2  ($(date +%H:%M)) ===" | tee -a "$BELOW_RAW"
  ./run_1x.sh $RUNNER -dtconv $SCENE -dt "$dt" -seed "$seed" -steps "$M" 2>&1 \
    | grep -E "DTROW|steps/s|BLOW|NON-FINITE|FAIL|STABILITY" \
    | sed "s/^/[dt=$dt seed=$seed] /" | tee -a "$BELOW_RAW"
}

# --- SUB-2e-6 plateau hunt (2026-06-25): does the geometric search CONVERGE? matched SIM-TIME 0.10 s
#     (cheaper at the lowest dt; 1e-5 is already steady by 0.10 so the checkpoint is representative),
#     steps(dt)=0.10/dt. dt ∈ {1e-6, 5e-7} (+ 2e-7 conditionally). DTHIST emits the stretch distribution. ---
BELOW2_RAW=RUN_LOGS/2026-06-25_dt_below2.txt
below2_steps() { case "$1" in 1e-5) echo 10000;; 2e-6) echo 50000;; 1e-6) echo 100000;; 5e-7) echo 200000;; 2e-7) echo 500000;; esac; }
run_below2() {  # dt seed
  local dt="$1" seed="$2" M; M=$(below2_steps "$dt")
  status "BELOW2 dt=$dt seed=$seed ($M steps, simT 0.10)"
  echo "=== BELOW2 dt=$dt seed=$seed steps=$M simT=0.10  ($(date +%H:%M)) ===" | tee -a "$BELOW2_RAW"
  ./run_1x.sh $RUNNER -dtconv $SCENE -dt "$dt" -seed "$seed" -steps "$M" 2>&1 \
    | grep -E "DTROW|DTHIST|steps/s|BLOW|NON-FINITE|FAIL|STABILITY" \
    | sed "s/^/[dt=$dt seed=$seed] /" | tee -a "$BELOW2_RAW"
}

# --- Part 2 HACK test (2026-06-25): widen the 1e-5 reach to recover the converged COUNT, then compare the
#     stretch DISTRIBUTION (DTHIST) vs the fine-dt reference. dt=1e-5, matched simT 0.10 (10000 steps). ---
HACK_RAW=RUN_LOGS/2026-06-25_dt_hack.txt
run_hack() {  # reach seed
  local reach="$1" seed="$2"
  status "HACK reach=$reach seed=$seed (1e-5, 10000 steps)"
  echo "=== HACK reach=$reach dt=1e-5 seed=$seed steps=10000 simT=0.10  ($(date +%H:%M)) ===" | tee -a "$HACK_RAW"
  ./run_1x.sh $RUNNER -dtconv $SCENE -dt 1e-5 -reach "$reach" -seed "$seed" -steps 10000 2>&1 \
    | grep -E "DTROW|DTHIST|steps/s|STABILITY" \
    | sed "s/^/[reach=$reach] /" | tee -a "$HACK_RAW"
}

case "${1:-1}" in
  below) : > "$BELOW_RAW"; echo "dt BELOW-1e-5 wing (matched simT 0.2 s) — started $(date)" | tee -a "$BELOW_RAW"
        for dt in 2e-5 1e-5 5e-6 2e-6; do run_below "$dt" 1; done
        status "DONE below"; echo "dt below done $(date)" | tee -a "$BELOW_RAW"; exit 0 ;;
  below2) : > "$BELOW2_RAW"; echo "dt SUB-2e-6 plateau hunt (matched simT 0.10 s) — started $(date)" | tee -a "$BELOW2_RAW"
        # 1e-5 + 2e-6 re-anchor at simT 0.10 (cheap), then the new low-dt points
        for dt in 1e-5 2e-6 1e-6 5e-7; do run_below2 "$dt" 1; done
        status "DONE below2"; echo "dt below2 done $(date)" | tee -a "$BELOW2_RAW"; exit 0 ;;
  below2-2e7) echo "dt 2e-7 plateau confirm (matched simT 0.10 s) — $(date)" | tee -a "$BELOW2_RAW"
        run_below2 2e-7 1; status "DONE 2e-7"; exit 0 ;;
  hack) : > "$HACK_RAW"; shift; echo "dt HACK reach sweep @1e-5 — started $(date)" | tee -a "$HACK_RAW"
        for r in "$@"; do run_hack "$r" 1; done
        status "DONE hack"; echo "dt hack done $(date)" | tee -a "$HACK_RAW"; exit 0 ;;
  1)    : > "$RAW"; echo "dt-convergence STAGE 1 (coarse, 1 seed × 4 dt) — started $(date)" | tee -a "$RAW"
        for dt in 1e-5 2e-5 5e-5 1e-4; do run_one "$dt" 1; done ;;
  env)  echo "dt-convergence ENVELOPE seeds @ 1e-5 — $(date)" | tee -a "$RAW"
        for s in 2 3; do run_one 1e-5 "$s"; done ;;
  ceil) shift; CDT="${1:-5e-5}"; echo "dt-convergence CEILING seeds @ $CDT — $(date)" | tee -a "$RAW"
        for s in 2 3; do run_one "$CDT" "$s"; done ;;
  stage2) echo "dt-convergence STAGE 2: envelope(1e-5 s2,s3) + cliff probes(1.2e-5,1.5e-5) — $(date)" | tee -a "$RAW"
        run_one 1e-5 2; run_one 1e-5 3
        run_one 1.5e-5 1; run_one 1.2e-5 1 ;;
  *)    echo "usage: $0 [1|env|ceil <dt>]"; exit 1 ;;
esac
status "DONE ($1)"
echo "dt-convergence $1 done $(date)" | tee -a "$RAW"
