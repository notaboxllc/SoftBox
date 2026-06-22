#!/bin/bash
# Soft Box increment 5c-iii Phase 2 — assembled moving crosslinker bundle.
# The full per-step crosslinker loop (formation ↔ force/torsion ↔ unbind ↔ integrate) over a
# many-filament bundle of free rods, + the confinement-free v1↔v2 substrate + the crosslinking demo.
# IMPORTANT: matches the boa-xlink-dense-nomotor fixture's aeta=1.0 viscosity (10x the v2 default;
# see INC5C-iii_PHASE2_FINDINGS.md §3 — the dominant v1↔v2 confound).
#
#   ./run_xlinkbundle.sh                       # CPU assembled run (200 fil) + stability
#   ./run_xlinkbundle.sh -cpu -nfil 200        # CPU runner, 200 filaments
#   ./run_xlinkbundle.sh -cpugpu -nfil 200     # GPU mechanics graph vs CPU (aggregate-within-SEM)
#   ./run_xlinkbundle.sh -disperse -nfil 200   # Part 2.1: bundle density vs t
#   ./run_xlinkbundle.sh -loadic RUN_LOGS/v1_ic_seed1.csv -offset 20   # Part 2.2: v2 from a v1 IC
#   ./run_xlinkbundle.sh -3js threejs_xlinkbundle -nfil 150 -conc 3    # the crosslinking demo
#   ./run_xlinkbundle.sh -twobundles -nfil 200 -conc 6 3000            # two antiparallel bundles, crosslinked (passive)
set -e
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx3G \
     -Dtornado.tvm.maxbytecodesize=32768 \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.CrosslinkerBundleHarness "$@"
