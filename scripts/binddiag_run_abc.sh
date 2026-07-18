#!/bin/bash
# Fan-out driver for the Part A/B/C (abc) temporal-resolution matrix. CPU-only, deterministic.
# densities {200,700} × seeds {101,202,303} × dtdiv {1,2,4}. Concurrency-limited.
set -u
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
OUT=RUN_LOGS/binddiag; mkdir -p "$OUT/logs"
rm -f "$OUT/summary_abc.csv"
run_one() {
  local dens=$1 seed=$2 dtdiv=$3 meas=$4
  local tag="abc_d${dens}_s${seed}_dt${dtdiv}"
  java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx2G \
    -Dtornado.recover.bailout=false -Dtornado.enable.fma=false -Dtornado.tvm.maxbytecodesize=65536 \
    -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
    softbox.ExplicitBindDiagHarness -mode abc -density $dens -seed $seed -dtdiv $dtdiv \
    -equilms 5 -measms $meas -tag "$tag" > "$OUT/logs/$tag.log" 2>&1
  echo "done $tag"
}
export -f run_one
export TORNADOVM_HOME TDIR OUT
# jobs: density seed dtdiv measMs   (d200 meas 30ms, d700 meas 15ms)
{
for s in 101 202 303; do
  for dt in 1 2 4; do
    echo "200 $s $dt 30"
    echo "700 $s $dt 15"
  done
done
} | xargs -P 8 -n 4 bash -c 'run_one "$@"' _
echo "ALL ABC DONE"
