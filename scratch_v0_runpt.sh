#!/bin/bash
# args: dt M seed v outdir
dt="$1"; M="$2"; seed="$3"; v="$4"; outdir="$5"
f="$outdir/dt${dt}_v${v}_s${seed}.txt"
./scripts/run_gliding.sh -matbox 50 -density 2000 -dt "$dt" -seed "$seed" -vclamp "$v" "$M" 2>&1 \
  | grep -E 'FVROW' | sed "s/^/DT=$dt SEED=$seed Mv=$M /" > "$f"
