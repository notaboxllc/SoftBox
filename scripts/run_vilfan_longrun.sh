#!/bin/bash
# Stage A long-run steady-twirl discovery screen. CPU ONLY, <= 3 logical cores total.
# Two concurrent processes, ONE processor each, nice 17. Resumable: completed arms are skipped.
set -u
cd "$(dirname "$0")/.."
TRAVEL=${1:-3.0}
AZ=${2:-0}
D=RUN_LOGS/vilfan_graded_binding/longrun
mkdir -p $D
echo "=== Stage A: travel=${TRAVEL} um, az0=${AZ} deg, 4 arms (2 lattices x 2 seeds) ==="
VG_PROCS=1 ./scripts/run_vilfan_graded.sh -longrun -recycle -matx 2.2 -travel $TRAVEL -az0 $AZ \
    -arm-mirror 1 -arm-seed 101,102 -out RUN_LOGS/vilfan_graded_binding \
    > $D/log_native_az${AZ}_${TRAVEL}um.txt 2>&1 &
P1=$!
VG_PROCS=1 ./scripts/run_vilfan_graded.sh -longrun -recycle -matx 2.2 -travel $TRAVEL -az0 $AZ \
    -arm-mirror -1 -arm-seed 101,102 -out RUN_LOGS/vilfan_graded_binding \
    > $D/log_mirror_az${AZ}_${TRAVEL}um.txt 2>&1 &
P2=$!
wait $P1; wait $P2
echo "STAGE A ARMS COMPLETE (travel=${TRAVEL} az0=${AZ})"
