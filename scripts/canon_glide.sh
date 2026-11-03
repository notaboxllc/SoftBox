#!/bin/bash
# CANONICAL site-normal gliding/twirl run (canon preset from softbox/ModelCanon.java; docs/MODEL_CANON.md).
# usage: scripts/canon_glide.sh <lawn 02|03|..> <out_dir> [extra flags: assay conditions, -deviate <id> ..., -steps N]
# Scene = the 2026-11 reference assay: d1600, 7x3 um lawn, 24-segment flexible filament (4.2 um), 0.3 s.
# GPU device-resident, monitored. The model itself comes from the preset; do NOT restate model flags here --
# a model change goes through ModelCanon (a row), not through a campaign script.
set -e
lawn=$1; out=$2; shift 2
cd /home/jba/Code/SoftBox
./scripts/gpu-crash-recorder.sh --status | grep -q RUNNING || { echo "gpu-crash-recorder NOT running: refusing" >&2; exit 1; }
SOFTBOX_XMX=6G ./scripts/run_gpu_monitored.sh ./scripts/run_site_normal_glide_gpu.sh -run -gpu -devicecull -resident -leanreadback \
    -chunkocc -nohires -seed 202609$lawn -noise-seed 11 -density 1600 -filsegs 24 -matx 7.0 -maty 3.0 -filx 1.0 \
    -stop-offlawn 0.1 -target 16.0 -probes 1 -probe-radius 0.04 -episodes 10000 -viz-stride 8000 -steps 2400000 \
    "$@" -out "$out"
