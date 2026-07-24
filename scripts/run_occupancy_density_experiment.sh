#!/bin/bash
# ============================================================================================
# CONTINUOUS LOCAL ACTIN CO-OCCUPANCY EXCLUSION — bounded paired density diagnostic (§7).
# Single-head explicit-s2-l40, GPU device-resident. OFF (canonical) vs ON (5.4 nm) at PAIRED seeds.
# Densities {150,400,700,1500,3000} heads/µm², seeds {101..104}, 40000 steps, dt=2.5e-6, rigor mode 1.
# NOT a canonical sweep — a 5-density paired diagnostic. Resumable (skips *.done).
#   ./scripts/run_occupancy_density_experiment.sh                 # full 40-cell paired run
#   ./scripts/run_occupancy_density_experiment.sh -steps 8000     # shorter (quick look)
#   ./scripts/run_occupancy_density_experiment.sh -dens "400 3000" -seeds "101 102"
# Findings: docs/helical_binding/CONTINUOUS_OCCUPANCY_EXCLUSION_FINDINGS.md
# ============================================================================================
cd "$(dirname "$0")/.." || exit 2
STEPS=40000; EXCL=5.4
DENS="150 400 700 1500 3000"; SEEDS="101 102 103 104"
OUT="RUN_LOGS/occupancy_exclusion_experiment"; TIMEOUT=1800
for ((i=1;i<=$#;i++)); do case "${!i}" in
  -steps) j=$((i+1)); STEPS="${!j}";; -dens) j=$((i+1)); DENS="${!j}";;
  -seeds) j=$((i+1)); SEEDS="${!j}";; -outdir) j=$((i+1)); OUT="${!j}";;
  -excl) j=$((i+1)); EXCL="${!j}";;
esac; done
REV="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
OFFDIR="$OUT/off"; ONDIR="$OUT/on"; mkdir -p "$OFFDIR" "$ONDIR"
echo "=== OCCUPANCY-EXCLUSION PAIRED DENSITY DIAGNOSTIC | rev=$REV | steps=$STEPS | excl=$EXCL nm ==="
echo "    densities: $DENS  |  seeds: $SEEDS  |  out: $OUT"

run_cell() {  # $1=condition(off|on) $2=density $3=seed $4=exclArg $5=outdir
  local cond=$1 d=$2 s=$3 exclnm=$4 dir=$5
  local base="cell_d${d}_s${s}"
  if [[ -f "$dir/$base.done" ]]; then echo "  [skip $cond ρ$d s$s]"; return; fi
  echo "  [run $cond ρ$d s$s | $(date +%H:%M:%S)]"
  timeout $TIMEOUT ./scripts/run_singlehead_gpu.sh -production-cell \
      -density "$d" -seed "$s" -steps "$STEPS" -occupancy-exclusion-nm "$exclnm" \
      -outdir "$dir" -rev "$REV" > "$dir/${base}.log" 2>&1
  if [[ -f "$dir/$base.done" ]]; then grep -E "^  RESULT|occupancy_rejection" "$dir/${base}.log" | head -1 | sed 's/^/      /'
  else echo "      [INCOMPLETE $cond ρ$d s$s rc=$? — see $dir/${base}.log]"; tail -3 "$dir/${base}.log" | sed 's/^/      /'; fi
}

for d in $DENS; do for s in $SEEDS; do
  run_cell off "$d" "$s" 0   "$OFFDIR"
  run_cell on  "$d" "$s" "$EXCL" "$ONDIR"
done; done
echo "=== PAIRED DIAGNOSTIC COMPLETE. Building CELLS.csv ... ==="
python3 scripts/occupancy_exclusion_analysis.py "$OUT" 2>&1 | tail -30 || echo "(analysis step failed; JSONs intact)"
