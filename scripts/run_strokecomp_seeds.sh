#!/bin/bash
# Multi-seed confirmation near V₀ + at v=0 (amplitude): canonical vs comp_E1/E2. Firms the zero-vs-amplitude verdict.
set -e
OUT="RUN_LOGS/2026-07-11_strokecomp_seeds.txt"
: > "$OUT"
STEPS=10000
for seed in 1 2; do
 for v in 0 12 14 16; do
  for arm in "canonical:" "comp_E1:-strokecomp 1" "comp_E2:-strokecomp 2"; do
    label="${arm%%:*}"; flags="${arm#*:}"
    echo "### arm=$label seed=$seed v=$v" >> "$OUT"
    ./scripts/run_gliding.sh -matbox 50 -density 2000 -vclamp "$v" -seed "$seed" $flags "$STEPS" 2>&1 \
      | grep -E "CMPLROW|FVROW" >> "$OUT"
  done
 done
done
echo "DONE" >> "$OUT"
