#!/bin/bash
# ============================================================================================
# Part C — RIGOR-RUPTURE GLIDING IMPACT: explicit single-head, GPU device-resident.
# Paired old/new seeds: densities {250,700,1500,3000} heads/um^2 x seeds {101,102} x {OFF,ON}.
# dt=2.5e-6, 40000 steps (0.1 s/seed), whole-window LS velocity. Each cell = fresh JVM (GPU).
# ============================================================================================
cd "$(dirname "$0")/.." || exit 2
REV="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
OUT="${1:-RUN_LOGS/motor_validation/rigor_gliding_singlehead}"
STEPS="${STEPS:-40000}"
mkdir -p "$OUT"
for d in 250 700 1500 3000; do
  for s in 101 102; do
    for cond in off on; do
      base="cell_d${d}_s${s}"
      dir="$OUT/$cond"; mkdir -p "$dir"
      if [[ -f "$dir/$base.done" ]]; then echo "[skip $cond d$d s$s]"; continue; fi
      extra=(); [[ "$cond" == "on" ]] && extra=(-rigor-rupture)
      echo "=== single-head $cond density=$d seed=$s steps=$STEPS ==="
      ./scripts/run_singlehead_gpu.sh -production-cell -density "$d" -seed "$s" -steps "$STEPS" \
          "${extra[@]}" -outdir "$dir" -rev "$REV" 2>&1 | grep -E "RESULT|RIGOR RUPTURE|status=|ERROR"
    done
  done
done
echo "=== single-head rigor-gliding matrix DONE ==="
python3 scripts/rigor_gliding_analysis.py "$OUT" single-head 2>/dev/null || true
