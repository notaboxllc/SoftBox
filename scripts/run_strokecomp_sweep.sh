#!/bin/bash
# STROKE_COMPLETION_STRAIN_TEST PART 1+2 sweep: f̄_available(v) + converter completion(v) for the canonical
# stroke and the strain-completion modifier at several E*. CPU velocity clamp, d2000, matbox 50, seed 0.
# Locates V₀ (zero of f̄) per arm ⇒ zero-moves-vs-amplitude-scales verdict.
set -e
OUT="RUN_LOGS/2026-07-11_strokecomp_sweep.txt"
: > "$OUT"
VS="0 4 8 10 12 13 14 15 16 18"
STEPS=10000
run() { # arm-label  extra-flags
  local label="$1"; shift
  for v in $VS; do
    echo "### arm=$label v=$v" >> "$OUT"
    ./scripts/run_gliding.sh -matbox 50 -density 2000 -vclamp "$v" -seed 0 "$@" "$STEPS" 2>&1 \
      | grep -E "CMPLROW|FVROW" >> "$OUT"
  done
}
run canonical
run comp_E1  -strokecomp 1
run comp_E2  -strokecomp 2
run comp_E4  -strokecomp 4
echo "DONE" >> "$OUT"
