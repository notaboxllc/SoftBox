#!/bin/bash
# FROZEN realistic-only tweezers analysis pipeline (3G-B holdout).
# Runs the 8 stages in dependency order from THIS directory (which must sit as
# <package>/analysis/ so that ROOT = <package> and the calibration/, controls/ and
# instrument_realistic/ subtrees are found).  Produces analysis/out/* and, at the
# package root, analyst_results_holdout.json.
#
# DO NOT EDIT any stage script.  This pipeline is frozen and hashed
# (see FROZEN_ANALYSIS_SPEC.md + FROZEN_ANALYSIS_SHA256.txt).
set -e
cd "$(dirname "$0")"
for s in 01_calibration.py 02_controls.py 03_detect.py 04_stiffness.py \
         04b_pool_stiffness.py 05_figures.py 06_stepmodel.py 07_sensitivity.py 08_results.py; do
    echo "=== $s ==="
    python3 "$s"
done
# The schema emitter writes analyst_results.json at the package root; the holdout
# task names it analyst_results_holdout.json.  Copy it there if present.
ROOT="$(cd .. && pwd)"
if [ -f "$ROOT/analyst_results.json" ]; then
    cp "$ROOT/analyst_results.json" "$ROOT/analyst_results_holdout.json"
    echo "wrote $ROOT/analyst_results_holdout.json"
fi
