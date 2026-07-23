#!/bin/bash
# ============================================================================================
# L60 SINGLE-HEAD structural-sensitivity density sweep — GPU device-resident (explicit-s2-l60).
# DECLARED sensitivity vs the frozen L40 canonical baseline: SAME EA/EI, canonical rigor mode 1,
# branchEA n/a (single head), dt 2.5e-6, 40000 steps; only the exposed S2 length L=60 (M=6) changes.
# One fresh JVM per (density,seed) cell. Density = HEADS/µm² (single-head convention).
#   ./scripts/run_l60_singlehead_sweep.sh                 # L60 full grid (13 ρ × 4 seeds), 40000 steps
#   ./scripts/run_l60_singlehead_sweep.sh -L 40 -outdir RUN_LOGS/l60_sensitivity/single_head_l40_ctrl -cells "150:101 150:102 400:101 400:102 700:101 700:102 1500:101 1500:102"
# ============================================================================================
cd "$(dirname "$0")/.." || exit 2
STEPS=40000; L=60; OUT="RUN_LOGS/l60_sensitivity/single_head_l60"; CELL_TIMEOUT=1800; CELLS_ARG=""
for ((i=1;i<=$#;i++)); do case "${!i}" in
  -steps)   j=$((i+1)); STEPS="${!j}";;
  -L)       j=$((i+1)); L="${!j}";;
  -cells)   j=$((i+1)); CELLS_ARG="${!j}";;
  -outdir)  j=$((i+1)); OUT="${!j}";;
  -timeout) j=$((i+1)); CELL_TIMEOUT="${!j}";;
esac; done
LOGDIR="$OUT/logs"; PROG="$OUT/progress.txt"; mkdir -p "$LOGDIR"
REV="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"; MAX_ATTEMPTS=2
CELLS=()
if [[ -n "$CELLS_ARG" ]]; then for c in ${CELLS_ARG//,/ }; do CELLS+=("$c"); done
else DENSITIES=(100 150 200 250 300 400 500 600 700 750 1000 1500 3000); SEEDS=(101 102 103 104)
  for d in "${DENSITIES[@]}"; do for s in "${SEEDS[@]}"; do CELLS+=("$d:$s"); done; done; fi
NTOT=${#CELLS[@]}
gpu_alive() { nvidia-smi --query-gpu=name --format=csv,noheader >/dev/null 2>&1; }
progress() { local c pd ps done=0 pend=0 fail=0
  { echo "=== L$L SINGLE-HEAD sweep progress @ $(date -Is) | rev=$REV | steps=$STEPS ==="
    for c in "${CELLS[@]}"; do pd="${c%:*}"; ps="${c#*:}"
      if [[ -f "$OUT/cell_d${pd}_s${ps}.done" ]]; then done=$((done+1)); elif [[ -f "$OUT/cell_d${pd}_s${ps}.failed" ]]; then fail=$((fail+1)); else pend=$((pend+1)); fi; done
    echo "COMPLETED=$done PENDING=$pend FAILED=$fail (of $NTOT)"; } > "$PROG"; }
echo "=== L$L SINGLE-HEAD SENSITIVITY SWEEP: $NTOT cells, $STEPS steps, rev=$REV, out=$OUT ==="
progress
for cell in "${CELLS[@]}"; do
  d="${cell%:*}"; s="${cell#*:}"; base="cell_d${d}_s${s}"
  [[ -f "$OUT/$base.done" ]] && { echo "  [skip done ρ$d s$s]"; continue; }
  [[ -f "$OUT/$base.failed" ]] && { echo "  [skip failed ρ$d s$s]"; continue; }
  att_file="$OUT/$base.attempts"; att=$(cat "$att_file" 2>/dev/null || echo 0)
  if ! gpu_alive; then echo "  *** GPU UNRESPONSIVE before ρ$d s$s — STOP ***"; exit 10; fi
  att=$((att+1)); echo "$att" > "$att_file"; echo "  [run ρ$d s$s L$L attempt $att | $(date -Is)]"
  log="$LOGDIR/${base}.attempt${att}.log"
  timeout $CELL_TIMEOUT ./scripts/run_singlehead_gpu.sh -production-cell -L "$L" \
      -density "$d" -seed "$s" -steps "$STEPS" -outdir "$OUT" -rev "$REV" > "$log" 2>&1
  rc=$?; grep -E "RESULT|invalid=|SENSITIVITY" "$log" | tail -3 | sed 's/^/      /'
  if [[ -f "$OUT/$base.done" ]]; then echo "  [ok ρ$d s$s rc=$rc]"; rm -f "$att_file"
  else echo "  [INCOMPLETE ρ$d s$s rc=$rc]"
    if [[ $att -ge $MAX_ATTEMPTS ]]; then { echo "density=$d seed=$s attempts=$att last_rc=$rc"; tail -4 "$log"; } > "$OUT/$base.failed"; echo "  *** ρ$d s$s failed — STOP ***"; exit 11; fi
  fi; progress
done
echo "=== L$L SINGLE-HEAD SWEEP COMPLETE ($NTOT cells) → $OUT ==="; progress
