#!/bin/bash
# ============================================================================================
# Explicit-HMM dimer — DEFINITIVE GPU-ONLY density sweep (scientific density-response campaign).
# One fresh JVM per (density,seed) CELL ⇒ a GPU hang kills only that cell; the orchestrator resumes.
# Frozen config (§1) is enforced + printed INSIDE each cell (runProductionCell); this script only
# schedules cells in the §3 decision-informative order, checkpoints, and is GPU-hang-resilient.
#
#   ./scripts/run_hmm_density_sweep.sh                 # run/resume the full 36-cell campaign
#   ./scripts/run_hmm_density_sweep.sh -steps 5000     # (default) production steps per cell
#
# Live GPU monitor (optional, separate terminal):
#   watch -n 2 'nvidia-smi --query-gpu=temperature.gpu,utilization.gpu,power.draw,memory.used,memory.total --format=csv,noheader'
# ============================================================================================
cd "$(dirname "$0")/.." || exit 2

STEPS=5000
for ((i=1;i<=$#;i++)); do case "${!i}" in -steps) j=$((i+1)); STEPS="${!j}";; esac; done

OUT="RUN_LOGS/hmm_density_sweep"
LOGDIR="$OUT/logs"
PROG="$OUT/progress.txt"
mkdir -p "$LOGDIR"
REV="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
CELL_TIMEOUT=2400          # per-cell wall ceiling (s); a hang trips this
MAX_ATTEMPTS=2             # §10: resume a hung cell ONCE; a 2nd hang on the same cell STOPS the campaign

# §3 decision-informative order: biological operating range → saturation → high-density → low → fill-in
DENSITIES=(500 750 1500 3000 100 200 400 700 1000)
SEEDS=(101 102 103 104)

progress() {
  local pd ps done=0 pend=0 fail=0     # LOCAL loop vars — must not clobber the main loop's d/s
  { echo "=== HMM density-sweep progress @ $(date -Is) | rev=$REV | steps=$STEPS ==="
    for pd in "${DENSITIES[@]}"; do for ps in "${SEEDS[@]}"; do
      if   [[ -f "$OUT/cell_d${pd}_s${ps}.done" ]]; then done=$((done+1))
      elif [[ -f "$OUT/cell_d${pd}_s${ps}.failed" ]]; then fail=$((fail+1))
      else pend=$((pend+1)); fi
    done; done
    echo "COMPLETED=$done  PENDING=$pend  FAILED=$fail  (of 36)"
    echo "--- completed:"; ls "$OUT"/cell_d*_s*.done 2>/dev/null | sed 's#.*/##;s/\.done//' | tr '\n' ' '; echo
    echo "--- failed:";    ls "$OUT"/cell_d*_s*.failed 2>/dev/null | sed 's#.*/##;s/\.failed//' | tr '\n' ' '; echo
  } > "$PROG"
}

gpu_alive() { nvidia-smi --query-gpu=name --format=csv,noheader >/dev/null 2>&1; }

echo "=== HMM-DIMER DENSITY SWEEP: 36 cells (9 densities × 4 seeds), $STEPS steps/cell, rev=$REV ==="
echo "    order: ${DENSITIES[*]}  |  seeds: ${SEEDS[*]}  |  out: $OUT"
progress

for d in "${DENSITIES[@]}"; do
  for s in "${SEEDS[@]}"; do
    base="cell_d${d}_s${s}"
    if [[ -f "$OUT/$base.done" ]]; then echo "  [skip completed ρ$d s$s]"; continue; fi
    if [[ -f "$OUT/$base.failed" ]]; then echo "  [skip failed ρ$d s$s (see $base.failed)]"; continue; fi

    att_file="$OUT/$base.attempts"
    att=$(cat "$att_file" 2>/dev/null || echo 0)

    if ! gpu_alive; then
      echo "  *** GPU UNRESPONSIVE before ρ$d s$s — STOPPING campaign (resume after recovery). ***"
      echo "STOP: gpu-unresponsive before ρ$d s$s @ $(date -Is)" >> "$PROG"
      exit 10
    fi

    att=$((att+1)); echo "$att" > "$att_file"
    echo "  [run ρ$d s$s | attempt $att/$MAX_ATTEMPTS | $(date -Is)]"
    log="$LOGDIR/${base}.attempt${att}.log"

    timeout $CELL_TIMEOUT ./scripts/run_hmm_gpu.sh -production-cell \
        -density "$d" -seed "$s" -steps "$STEPS" -outdir "$OUT" -rev "$REV" \
        > "$log" 2>&1
    rc=$?

    tail -4 "$log" | sed 's/^/      /'

    if [[ -f "$OUT/$base.done" ]]; then
      echo "  [ok ρ$d s$s rc=$rc]"; rm -f "$att_file"
    else
      # incomplete: timeout (124), driver reset, or runtime error — NOT a physics failure (§10)
      echo "  [INCOMPLETE ρ$d s$s rc=$rc (attempt $att)]"
      if [[ $att -ge $MAX_ATTEMPTS ]]; then
        echo "  *** ρ$d s$s failed $MAX_ATTEMPTS attempts — recording + STOPPING (do not hammer). ***"
        { echo "density=$d seed=$s attempts=$att last_rc=$rc time=$(date -Is)"
          echo "last-4-lines:"; tail -4 "$log"; } > "$OUT/$base.failed"
        echo "STOP: ρ$d s$s failed $MAX_ATTEMPTS attempts (rc=$rc) @ $(date -Is)" >> "$PROG"
        progress
        if ! gpu_alive; then echo "  (GPU also unresponsive — likely a driver hang on this cell.)"; fi
        exit 11
      fi
      echo "  [will retry ρ$d s$s once more]"
    fi
    progress
  done
done

echo "=== CAMPAIGN COMPLETE — all cells done. Running analysis ... ==="
progress
python3 scripts/hmm_density_analysis.py "$OUT" 2>&1 | tail -60 || echo "(analysis step failed; JSONs are intact in $OUT)"
echo "=== DONE. Per-cell JSON in $OUT ; density-response report in $OUT/ANALYSIS.md ==="
