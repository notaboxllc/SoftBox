#!/bin/bash
# NEW STUDY §11 — dt cross-check: does the candidate (reduced margin) introduce a stronger dt-dependence than default?
# density 700, default (segMargin 0.05) vs candidate (segMargin 0.0125), dt÷{1,2}, seeds {101,202,303}. recruit assay.
set -u
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
OUT=RUN_LOGS/binddiag; mkdir -p "$OUT/logs"
run_one() {
  local seed=$1 name=$2 sm=$3 dtdiv=$4
  local tag="dtchk_d700_s${seed}_${name}_dt${dtdiv}"
  java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx2G \
    -Dtornado.recover.bailout=false -Dtornado.enable.fma=false -Dtornado.tvm.maxbytecodesize=65536 \
    -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
    softbox.ExplicitBindDiagHarness -mode recruit -density 700 -seed $seed -dtdiv $dtdiv \
    -equilms 5 -measms 15 -segmargin $sm -tag "$tag" > "$OUT/logs/$tag.log" 2>&1
  echo "done $tag"
}
export -f run_one; export TORNADOVM_HOME TDIR OUT
{
for seed in 101 202 303; do for cfg in "def 0.05" "cand 0.0125"; do for dt in 1 2; do echo "$seed $cfg $dt"; done; done; done
} | xargs -P 4 -n 4 bash -c 'run_one "$@"' _
echo "ALL DTCHK DONE"
