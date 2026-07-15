#!/bin/bash
# SoftBox — Experiment 0: virtual optical-trap FILAMENT calibration assay (CPU-ONLY diagnostic).
# No motor / chemistry / binding — a single rigid actin rod between two harmonic endpoint traps.
# The GPU is reserved for the fine-dt gliding sweep; this harness runs on the CPU sequential runner
# ONLY (it refuses -gpu). Default-off: new files only, nothing on the canonical path references it.
#
#   ./run_lasertrap.sh                              # full assay: Phase A + B + C + energy + gate summary
#   ./run_lasertrap.sh -A                           # Phase A deterministic mechanics only
#   ./run_lasertrap.sh -B -seeds 4 -dur 0.2         # Phase B thermal equilibrium only
#   ./run_lasertrap.sh -C                           # Phase C step recovery only
#   ./run_lasertrap.sh -energy                      # Gate 7 energy accounting only
#   ./run_lasertrap.sh -out RUN_LOGS/lasertrap/csv  # also write CSV artifacts for the figure/analysis
#   ./run_lasertrap.sh -3js ~/Code/SoftBox/threejs_lasertrap   # write viewer frames (centered→displaced→relax)
# Flags: -k <pN/nm> -L <µm> -dur <s> -seeds <n> -kprep <N·m>
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
# CPU sequential runner: plain JVM, single-threaded harness (no TornadoVM device execution), so it
# cannot contend with the GPU sweep. @tornado-argfile is only for classpath/preview flag parity.
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx2G \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.LaserTrapHarness "$@"
