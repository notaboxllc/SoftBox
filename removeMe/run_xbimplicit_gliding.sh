#!/bin/bash
# IMPLICIT_CROSSBRIDGE — GLIDING wing. v1 4×1 box (-v1box, exactly 2000 heads), CPU runner (GPU is busy on
# the contractile wing; gliding CPU is independent). Probe reports NET velocity + avgBound. dt sweep,
# explicit vs implicit, matched SIM-TIME 0.05 s ⇒ steps(dt)=0.05/dt. Fine-dt = the converged reference.
cd /home/jba/Code/SoftBox
RAW=RUN_LOGS/2026-06-26_xbimplicit_gliding.txt
status() { echo "[$(date +%H:%M)] xbimpl-gliding $1" > .last_run_status.gliding; }
steps_for() { case "$1" in 1e-6) echo 50000;; 2e-6) echo 25000;; 5e-6) echo 10000;; 1e-5) echo 5000;; 2e-5) echo 2500;; 5e-5) echo 1000;; 1e-4) echo 500;; esac; }

run() {  # label dt extra seed
  local label="$1" dt="$2" extra="$3" seed="$4" M; M=$(steps_for "$dt")
  status "$label dt=$dt seed=$seed ($M steps)"
  echo "=== $label dt=$dt seed=$seed steps=$M simT=0.05 $extra  ($(date +%H:%M)) ===" | tee -a "$RAW"
  ./run_gliding.sh -v1box -dt "$dt" -seed "$seed" $extra "$M" 2>&1 \
    | grep -E "velocity\(net\)|STEADY|avgBound\(all\)|NaN|xbimplicit" \
    | sed "s/^/[$label dt=$dt s=$seed] /" | tee -a "$RAW"
}

: > "$RAW"; echo "IMPLICIT_CROSSBRIDGE gliding (v1box, CPU) — started $(date)" | tee -a "$RAW"
# fine-dt converged reference (explicit), 3 seeds for the envelope
for s in 1 2 3; do run EXPL-FINE 1e-6 "" "$s"; done
for s in 1 2 3; do run EXPL-FINE 2e-6 "" "$s"; done
# dt sweep: explicit vs implicit, 3 seeds each
for dt in 1e-5 2e-5 5e-5 1e-4; do
  for s in 1 2 3; do run IMPL "$dt" "-xbimplicit" "$s"; done
  for s in 1 2 3; do run EXPL "$dt" "" "$s"; done
done
status "DONE"; echo "xbimpl-gliding done $(date)" | tee -a "$RAW"
