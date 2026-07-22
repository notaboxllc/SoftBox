#!/bin/bash
# Seed expansion to resolve systematic-vs-chaos at the resolved densities (velocity well-averaged).
# d1500 (cleanest signal) seeds 103-110; d3000 seeds 103-106; d700 seeds 103-106. ON/OFF paired.
cd "$(dirname "$0")/.." || exit 2
REV="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
OUT="${1:-RUN_LOGS/motor_validation/rigor_gliding_singlehead}"
STEPS="${STEPS:-40000}"
cell() { local d=$1 s=$2 cond=$3; local base="cell_d${d}_s${s}"; local dir="$OUT/$cond"; mkdir -p "$dir"
  [[ -f "$dir/$base.done" ]] && { echo "[skip $cond d$d s$s]"; return; }
  local extra=(); [[ "$cond" == "on" ]] && extra=(-rigor-rupture)
  echo "=== expand $cond d=$d s=$s ==="
  ./scripts/run_singlehead_gpu.sh -production-cell -density "$d" -seed "$s" -steps "$STEPS" \
      "${extra[@]}" -outdir "$dir" -rev "$REV" 2>&1 | grep -E "RESULT|RIGOR RUPTURE|status="
}
for s in 103 104 105 106 107 108 109 110; do for c in off on; do cell 1500 $s $c; done; done
for s in 103 104 105 106; do for c in off on; do cell 3000 $s $c; done; done
for s in 103 104 105 106; do for c in off on; do cell 700 $s $c; done; done
echo "=== expansion DONE ==="
python3 scripts/rigor_gliding_analysis.py "$OUT" single-head
