#!/bin/bash
# Faithful dense-gliding GPU sweep (grid binding) — matches BoA BENCHMARK_dense.md box schedule.
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
cd ~/Code/SoftBox
OUT=RUN_LOGS_densesweep.txt
: > "$OUT"
run() {  # scale M xmx
  echo "===== scale $1 (M=$2) =====" | tee -a "$OUT"
  timeout 1200 java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx$3 \
       -Dtornado.tvm.maxbytecodesize=65536 -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
       softbox.DenseGlidingHarness -scale $1 $2 2>&1 \
    | grep -iE "config|THROUGHPUT|avgBound|overflow|NaN|error|exception|out of mem|killed|cuda 7" | tee -a "$OUT"
}
run 0.5 400 16G
run 2   400 22G
run 4   300 26G
run 8   240 28G
echo "SWEEP_DONE" | tee -a "$OUT"
