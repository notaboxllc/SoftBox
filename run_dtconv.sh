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

case "${1:-1}" in
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
