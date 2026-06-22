#!/bin/bash
# Soft Box increment-7 → Ring EXPERIMENT: a 3×3 net of nucleating, treadmilling protein nodes.
# Do they find each other and coalesce? Composes the validated mechanisms (capture-and-pull, treadmilling,
# nucleation, dead-slot recycle) at scale. CPU-primary (the experiment); -gpu adds a device scale/no-crash check.
# Args pass through to Ring3x3Harness:
#   [-spacing µm] [-formins n] [-cap n] [-box µm] [-segmono n] [-warmchain n] [-kin f] [-steps n]
#   [-nsing n] [-ndim n] [-nodebrown s] [-gpu] [-3js dir]
#   ./run_ring3x3.sh                       # CPU experiment (default 3×3, 5 formins, 30000 steps)
#   ./run_ring3x3.sh -steps 3000           # cheap staging run
#   ./run_ring3x3.sh -gpu -steps 30000     # CPU experiment + GPU device scale/no-crash check
#   ./run_ring3x3.sh -3js threejs_ring3x3  # viewer frames
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx6G \
     -Dtornado.tvm.maxbytecodesize=16384 \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.Ring3x3Harness "$@"
