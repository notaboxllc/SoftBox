#!/bin/bash
# Soft Box EOM-STABILITY probe (aorus, TornadoVM PTX backend).
# Relaxation/impulse response of the filament EOM under an external elastic load — a NUMERICAL-STABILITY
# test, NOT a transport measurement. Single filament + one permanently-bound motor + external COM spring;
# Brownian off, deterministic. Classifies the decay + measures dt_crit over the k_ext × dt grid.
#   ./run_eomstab.sh                 # default: rigid single seg, frozen motor, clean translational mode
#   ./run_eomstab.sh -rot            # unfreeze motor body + filament rotation (rotational couplings)
#   ./run_eomstab.sh -chain 10       # multi-segment filament + F3/F4 (filament internal DOFs)
#   ./run_eomstab.sh -nof8           # F8 cross-bridge off (size F8's stiffness contribution)
#   ./run_eomstab.sh -extimplicit    # implicit external spring (the cure control)
#   ./run_eomstab.sh -drive          # secondary: driven-stroke settled-displacement convergence
#   ./run_eomstab.sh -gpu            # CPU≡GPU parity on the default frozen config
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx4G \
     -Dtornado.tvm.maxbytecodesize=16384 \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.EomStabilityHarness "$@"
