#!/bin/bash
# Soft Box — DENSITY/SCALE SWEEP driver (MEASUREMENT-ONLY).
# Runs the maximal-composition device-resident split at a series of SIZE-scaled scenes (constant density:
# box AREA ∝ scale, node spacing + slab depth fixed) and collects one SWEEP_ROW per point.
# Each point: a fresh JVM → -dense -scale F -sweep (short GPU window, profiler-on for kernel%, + VRAM + sanity
# + a capped CPU comparison). The launch-bound low end is the non-dense default scene (scale tag "default").
# Usage: ./run_scalesweep.sh [gpusteps]    (default gpusteps=2000)
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
GPUSTEPS="${1:-2000}"
LOG="RUN_LOGS/$(date +%Y-%m-%d)_scale_sweep.txt"
mkdir -p RUN_LOGS
run() { # $1=label  $2=cpucap  $3...=harness args
  local label="$1"; local cpucap="$2"; shift 2
  echo "######## SWEEP POINT: $label (cpucap=$cpucap, gpusteps=$GPUSTEPS) ########" | tee -a "$LOG"
  java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx8G \
       -Dtornado.tvm.maxbytecodesize=16384 \
       -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
       softbox.FullSystemDemoHarness -sweep -gpusteps "$GPUSTEPS" -cpucap "$cpucap" "$@" 2>&1 \
    | grep -v -E "^(WARNING|Picked up|.*--enable-preview)" | tee -a "$LOG"
  echo | tee -a "$LOG"
}
echo "=== SCALE SWEEP $(date) — gpusteps=$GPUSTEPS ===" | tee -a "$LOG"
# launch-bound low end (non-dense default scene) + dense baseline, then size-scale the dense scene up.
run "default(non-dense)" 400         # 4x4 nodes, 60 minifils, FIL_CAP 1536
run "dense-0.5x"         400 -dense -scale 0.5
run "dense-1x"          400 -dense -scale 1
run "dense-2x"          300 -dense -scale 2
run "dense-4x"          150 -dense -scale 4
run "dense-8x"           80 -dense -scale 8
run "dense-16x"          40 -dense -scale 16
echo "=== SWEEP DONE $(date) ===" | tee -a "$LOG"
echo "--- collected rows ---" | tee -a "$LOG"
grep "SWEEP_ROW" "$LOG" | tee -a "$LOG"
