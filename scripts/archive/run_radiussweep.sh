#!/bin/bash
# GLIDING_RADIUS_SWEEP — dt=5e-7 converged-dt glide vs capture radius, early-stopped + shrunk-mat.
# Config held fixed (only -coltol varies): -full -grid -lymntaylor -adppibind -xbimplicit2 -ratefix, density 1000, seed 0.
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
run() { java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx8G \
     -Dtornado.tvm.maxbytecodesize=65536 \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.GlidingHarness "$@"; }

BASE="-gpu -full -grid -lymntaylor -adppibind -xbimplicit2 -ratefix -density 1000 -dt 5e-7 -seed 0"
CAP=300000     # 0.15 s / 5e-7
LOG=RUN_LOGS/2026-07-06_radiussweep.txt
mkdir -p RUN_LOGS
stamp(){ echo "[$(date +%H:%M:%S)] $1" | tee -a "$LOG" | tee .last_run_status; }

MODE="$1"
if [ "$MODE" = "step2" ]; then
  stamp "STEP2-A shrunk-mat + early-stop, coltol4"
  run $BASE -coltol 4 -matband 1.2 -earlystop $CAP 2>&1 | tee -a "$LOG" | grep -E "EARLYSTOP_ROW|MATBAND_ROW|GRID_ROW|STATS_STEADY_ROW|grid measurement"
  stamp "STEP2-B shrunk-mat NO early-stop (full 0.15s), coltol4"
  run $BASE -coltol 4 -matband 1.2 $CAP 2>&1 | tee -a "$LOG" | grep -E "EARLYSTOP_ROW|MATBAND_ROW|GRID_ROW|STATS_STEADY_ROW|grid measurement"
  stamp "STEP2-C FULL-mat + early-stop (parity control), coltol4"
  run $BASE -coltol 4 -earlystop $CAP 2>&1 | tee -a "$LOG" | grep -E "EARLYSTOP_ROW|GRID_ROW|STATS_STEADY_ROW|grid measurement"
  stamp "STEP2 done"
elif [ "$MODE" = "step3" ]; then
  for CT in 2 3 4 5 6; do
    stamp "STEP3 sweep coltol=$CT"
    run $BASE -coltol $CT -matband 1.2 -earlystop -ktotcensus $CAP 2>&1 | tee -a "$LOG" | grep -E "EARLYSTOP_ROW|MATBAND_ROW|GRID_ROW|STATS_STEADY_ROW|KTOT_ROW|grid measurement"
  done
  stamp "STEP3 done"
fi
