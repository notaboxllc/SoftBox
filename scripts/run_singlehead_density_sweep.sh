#!/bin/bash
# ============================================================================================
# Explicit SINGLE-HEAD (explicit-s2-l40) — GPU-ONLY density sweep, matched to the HMM-dimer campaign
# (run_hmm_density_sweep.sh). One fresh JVM per (density,seed) CELL ⇒ a GPU hang kills only that cell;
# the orchestrator resumes (skips *.done). Frozen config enforced + printed INSIDE each cell.
# Density = HEADS/µm² (single-head convention — NO 2× factor vs the dimer's dimers/µm²).
#
#   ./scripts/run_singlehead_density_sweep.sh                  # run/resume the default 52-cell campaign (40000 steps)
#   ./scripts/run_singlehead_density_sweep.sh -steps 40000     # (default) 0.1 s simulated / cell
#   ./scripts/run_singlehead_density_sweep.sh -cells "200:101 300:102"   # explicit cell list (density:seed)
#
# Live GPU monitor (separate terminal):
#   watch -n 2 'nvidia-smi --query-gpu=temperature.gpu,utilization.gpu,power.draw,memory.used --format=csv,noheader'
# ============================================================================================
cd "$(dirname "$0")/.." || exit 2

STEPS=40000
CELLS_ARG=""
OUT="RUN_LOGS/single_head_density_sweep_long"
CELL_TIMEOUT=1800          # per-cell wall ceiling (s); worst case (~ρ3000) is ~3 min, so 1800 is generous
NSEG=""                    # optional actin-length control (-nseg 11); empty ⇒ validated default (12)
for ((i=1;i<=$#;i++)); do case "${!i}" in
  -steps)   j=$((i+1)); STEPS="${!j}";;
  -cells)   j=$((i+1)); CELLS_ARG="${!j}";;
  -outdir)  j=$((i+1)); OUT="${!j}";;
  -timeout) j=$((i+1)); CELL_TIMEOUT="${!j}";;
  -nseg)    j=$((i+1)); NSEG="${!j}";;
esac; done
NSEG_ARG=(); [[ -n "$NSEG" ]] && NSEG_ARG=(-nseg "$NSEG")

LOGDIR="$OUT/logs"
PROG="$OUT/progress.txt"
mkdir -p "$LOGDIR"
REV="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
MAX_ATTEMPTS=2

# ---- build the target CELLS list (each entry "density:seed") ----
CELLS=()
if [[ -n "$CELLS_ARG" ]]; then
  for c in ${CELLS_ARG//,/ }; do CELLS+=("$c"); done
else
  # matched ladder + seeds (low→high), identical to the HMM-dimer long campaign
  DENSITIES=(100 150 200 250 300 400 500 600 700 750 1000 1500 3000)
  SEEDS=(101 102 103 104)
  for d in "${DENSITIES[@]}"; do for s in "${SEEDS[@]}"; do CELLS+=("$d:$s"); done; done
fi
NTOT=${#CELLS[@]}

progress() {
  local c pd ps done=0 pend=0 fail=0
  { echo "=== SINGLE-HEAD density-sweep progress @ $(date -Is) | rev=$REV | steps=$STEPS ==="
    for c in "${CELLS[@]}"; do pd="${c%:*}"; ps="${c#*:}"
      if   [[ -f "$OUT/cell_d${pd}_s${ps}.done" ]]; then done=$((done+1))
      elif [[ -f "$OUT/cell_d${pd}_s${ps}.failed" ]]; then fail=$((fail+1))
      else pend=$((pend+1)); fi
    done
    echo "COMPLETED=$done  PENDING=$pend  FAILED=$fail  (of $NTOT in this run)"
    echo "--- completed cells:"; ls "$OUT"/cell_d*_s*.done 2>/dev/null | sed 's#.*/##;s/\.done//' | tr '\n' ' '; echo
    echo "--- failed:";    ls "$OUT"/cell_d*_s*.failed 2>/dev/null | sed 's#.*/##;s/\.failed//' | tr '\n' ' '; echo
  } > "$PROG"
}

gpu_alive() { nvidia-smi --query-gpu=name --format=csv,noheader >/dev/null 2>&1; }

echo "=== SINGLE-HEAD DENSITY SWEEP: $NTOT cells, $STEPS steps/cell, rev=$REV ==="
echo "    cells: ${CELLS[*]}  |  out: $OUT"
progress

for cell in "${CELLS[@]}"; do
  d="${cell%:*}"; s="${cell#*:}"
  base="cell_d${d}_s${s}"
  if [[ -f "$OUT/$base.done" ]]; then echo "  [skip completed ρ$d s$s]"; continue; fi
  if [[ -f "$OUT/$base.failed" ]]; then echo "  [skip failed ρ$d s$s]"; continue; fi

  att_file="$OUT/$base.attempts"
  att=$(cat "$att_file" 2>/dev/null || echo 0)

  if ! gpu_alive; then
    echo "  *** GPU UNRESPONSIVE before ρ$d s$s — STOPPING (resume after recovery). ***"
    echo "STOP: gpu-unresponsive before ρ$d s$s @ $(date -Is)" >> "$PROG"; exit 10
  fi

  att=$((att+1)); echo "$att" > "$att_file"
  echo "  [run ρ$d s$s | attempt $att/$MAX_ATTEMPTS | $(date -Is)]"
  log="$LOGDIR/${base}.attempt${att}.log"

  timeout $CELL_TIMEOUT ./scripts/run_singlehead_gpu.sh -production-cell \
      -density "$d" -seed "$s" -steps "$STEPS" -outdir "$OUT" -rev "$REV" "${NSEG_ARG[@]}" \
      > "$log" 2>&1
  rc=$?
  tail -4 "$log" | sed 's/^/      /'

  if [[ -f "$OUT/$base.done" ]]; then
    echo "  [ok ρ$d s$s rc=$rc]"; rm -f "$att_file"
  else
    echo "  [INCOMPLETE ρ$d s$s rc=$rc (attempt $att)]"
    if [[ $att -ge $MAX_ATTEMPTS ]]; then
      echo "  *** ρ$d s$s failed $MAX_ATTEMPTS attempts — recording + STOPPING. ***"
      { echo "density=$d seed=$s attempts=$att last_rc=$rc time=$(date -Is)"
        echo "last-4-lines:"; tail -4 "$log"; } > "$OUT/$base.failed"
      echo "STOP: ρ$d s$s failed $MAX_ATTEMPTS attempts (rc=$rc) @ $(date -Is)" >> "$PROG"
      progress; exit 11
    fi
    echo "  [will retry ρ$d s$s once more]"
  fi
  progress
done

echo "=== CAMPAIGN COMPLETE — all $NTOT targeted cells done. Running analysis over $OUT ... ==="
progress
python3 scripts/single_head_density_analysis.py "$OUT" 2>&1 | tail -40 || echo "(analysis step failed; JSONs are intact in $OUT)"
echo "=== DONE. Per-cell JSON in $OUT ; density-response report in $OUT/ANALYSIS.md ==="
