#!/bin/bash
# §6.10 Phase C — force-cap (break-force 12 pN) release A/B at power.
# Config: §6.7 — 14x2 box (-full, ~13.4k motors), dt=1e-5, 10k steps, GPU production path,
#   serial (no concurrent sims), post-transient handled by the -grid steady (2nd-half) stats.
# A/B: -faithfulrelease OFF vs ON, n seeds. Captures GRID_ROW (net glide + avgBound) + CAP_ROW
#   (firing rate). Raw -> RUN_LOGS/2026-06-16_4biv_forcecap/phaseC.txt. Progress -> .last_run_status.
cd /home/jba/Code/SoftBox
OUT=RUN_LOGS/2026-06-16_4biv_forcecap
RAW=$OUT/phaseC.txt
STEPS=10000
NSEED=${1:-16}
SEEDS=$(seq 1 "$NSEED")
: > "$RAW"
echo "§6.10 Phase C force-cap A/B — GPU -full 14x2, ${STEPS} steps, n=${NSEED}, started $(date +%H:%M)" | tee -a "$RAW"
TOTAL=$((NSEED*2)); i=0; t0=$(date +%s)

run() {   # $1=tog (OFF|ON) $2=flag $3=seed
  i=$((i+1))
  local now el eta
  now=$(date +%s); el=$((now-t0)); eta=$(( i>1 ? el*(TOTAL-i+1)/(i-1) : 0 ))
  echo "[$(date +%H:%M)] PhaseC run $i/$TOTAL: $1 seed $3 (elapsed ${el}s, eta ~${eta}s)" > .last_run_status
  echo "=== GPU grid $1 seed=$3 ===" >> "$RAW"
  ./run_gliding.sh -gpu -full -grid $2 -seed "$3" "$STEPS" 2>&1 \
    | grep -E "GRID_ROW|CAP_ROW" >> "$RAW"
}

for s in $SEEDS; do run OFF ""               "$s"; done
for s in $SEEDS; do run ON  "-faithfulrelease" "$s"; done

echo "[$(date +%H:%M)] PhaseC DONE" > .last_run_status
echo "Phase C done $(date +%H:%M)" | tee -a "$RAW"
echo "" | tee -a "$RAW"
echo "--- OFF net (netXY) ---" | tee -a "$RAW"; grep -A1 "grid OFF" "$RAW" | grep GRID_ROW | sed 's/.*netXY=\([0-9.]*\).*/\1/' | tee -a "$RAW"
echo "--- ON  net (netXY) ---" | tee -a "$RAW"; grep -A1 "grid ON"  "$RAW" | grep GRID_ROW | sed 's/.*netXY=\([0-9.]*\).*/\1/' | tee -a "$RAW"
