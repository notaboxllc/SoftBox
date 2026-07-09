#!/bin/bash
# Soft Box — FULL-SYSTEM DEMONSTRATION: a mid-sized biochemically-active contractile network in a shallow chamber.
# Composes protein nodes (nucleating biochemically-active formin filaments: growth+depoly+aging+severing) + free
# myosin minifilaments (parallel-grid binding + contraction) + O(N) crosslinker bundling + containment, all in one
# shallow box at faithful (KIN=1) rates. WATCH + HUNT for aberrations before the ring. CPU-primary; -gpu adds a
# device scale/no-crash + CPU≡GPU-aggregate probe.
# Args pass through to FullSystemDemoHarness:
#   [-boxxy µm] [-boxz µm] [-gx n] [-gy n] [-spacing µm] [-formins n] [-cap n] [-mini n] [-segmono n] [-warmchain n]
#   [-kin f] [-polyboost n] [-pool µM] [-nucboost f] [-noxlink] [-xlconc f] [-xlcheck n] [-noaging] [-nosever]
#   [-cofratio f] [-nodebrown s] [-steps n] [-smoke] [-gpu] [-3js dir]
#   ./run_fulldemo.sh -smoke                          # cheap short assembly/sanity run (1500 steps)
#   ./run_fulldemo.sh -steps 30000                    # CPU demo + aberration hunt
#   ./run_fulldemo.sh -gpu -steps 30000               # + GPU device scale / no-crash / CPU≡GPU-aggregate probe
#   ./run_fulldemo.sh -3js threejs_fulldemo -steps 30000   # render frames (top-down shallow slab)
#   ./run_fulldemo.sh -kin 5 -steps 30000             # viewing-speed knob (KIN=1 is the faithful hunt)
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx6G \
     -Dtornado.tvm.maxbytecodesize=16384 \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.FullSystemDemoHarness "$@"
