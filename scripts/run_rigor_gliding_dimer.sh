#!/bin/bash
# ============================================================================================
# Part C — RIGOR-RUPTURE GLIDING IMPACT: explicit HMM DIMER, CPU-only (the faithful dimer path).
# Reduced set (dimer CPU-only is compute-bound at high density): densities {250,700,1500}
# dimers/um^2 x seeds x {OFF,ON}, 12000 steps. Paired seeds. Captures RIGORROW lines.
# CLASSES env overrides the harness classpath (scratch build) to avoid disturbing a running campaign.
# ============================================================================================
cd "$(dirname "$0")/.." || exit 2
TDIR="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx/share/java/tornado"
CP="${CLASSES:+$CLASSES:}$TDIR/tornado-api-4.0.1-dev.jar:."
OUT="${1:-RUN_LOGS/motor_validation/rigor_gliding_dimer}"
STEPS="${STEPS:-12000}"
mkdir -p "$OUT"
ROWS="$OUT/rigorrows.csv"; : > "$ROWS"
run_cell() { local d=$1 s=$2 cond=$3; local extra=(); [[ "$cond" == "on" ]] && extra=(-rigor-rupture)
  echo "=== dimer $cond density=$d seed=$s steps=$STEPS ==="
  java --enable-preview -Xmx2G -cp "$CP" softbox.ExplicitHmmDimerGlidingHarness \
       -matsmoke -mdensity "$d" -seed "$s" -steps "$STEPS" "${extra[@]}" 2>&1 \
    | grep -E "velRaw|RIGOR RUPTURE|RIGORROW" | tee -a "$OUT/$cond.log" | grep "^RIGORROW" >> "$ROWS" || true
}
for cell in "250 101" "250 102" "700 101" "700 102" "1500 101"; do
  set -- $cell; d=$1; s=$2
  run_cell "$d" "$s" off
  run_cell "$d" "$s" on
done
echo "=== dimer rigor-gliding reduced matrix DONE ==="
python3 scripts/rigor_gliding_analysis.py "$OUT" dimer 2>/dev/null || true
