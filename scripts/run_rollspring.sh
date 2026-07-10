#!/bin/bash
# Azimuthal-binding Increment 1 — torsional-roll-spring prototype (aorus, TornadoVM PTX backend).
# Isolated roll spring + thermostat on a single long filament (no motors/binding). Answers: is there a
# stiffness giving a COHERENT helical twist STABLE at production dt=1e-5?  Args pass through to RollSpringHarness:
#   [-cpu] [-n nSeg] [-M steps] [-dt dt] [-rollstiff f] [-rolldamp d] [-brot b] [-rollhooke k] [-straight] [-sweep] [-3js dir] [-seed s]
#   ./scripts/run_rollspring.sh                 # GPU coherence+stability run + CPU≡GPU parity
#   ./scripts/run_rollspring.sh -sweep          # stiffness sweep (fraction-per-step) + raw-Hooke blow-up contrast
#   ./scripts/run_rollspring.sh -cpu            # CPU runner only (triage)
#   ./scripts/run_rollspring.sh -3js threejs_rollspring   # dump viewer frames (a twisting filament)
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx4G \
     -Dtornado.tvm.maxbytecodesize=16384 \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.RollSpringHarness "$@"
