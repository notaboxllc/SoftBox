#!/bin/bash
# Vilfan-like target-zone twirling — the DETERMINISTIC build-and-validation fixture.
#
# ============================ CPU ONLY — NO GPU, NO DEVICE EXECUTION ============================
# This launcher runs the plain sequential JVM runner. It builds NO TaskGraph, opens NO device
# context and makes NO TornadoVM device call: the @Parallel loops execute as ordinary Java for
# loops when the kernels are invoked directly. @tornado-argfile is present ONLY for classpath and
# --enable-preview parity with every other harness. It therefore cannot contend with the GPU work
# running in the primary worktree.
#   -XX:ActiveProcessorCount=4  caps JVM-internal (GC/JIT) parallelism conservatively.
#   nice -n 15                  yields CPU to the active study.
# ================================================================================================
#
# Modes
#   -gates      Stage 4 unit + fixture validation (A helical geometry, B mirror, C zone gate,
#               D imposed translation, E default identity, F zero-chirality null, G CPU health)
#   -campaign   Stage 5 deterministic proof of mechanism (zone ON/OFF x native/mirrored + azimuth span)
#   -ladder     the compact prescribed-speed ladder + a reversed-direction control
#   -sens       Stage 6 bounded sensitivity grid (zone width x speed) — only if the primary setup is null
#   -smoke      Stage 7 restoration-switch smoke tests
#   -all        gates + campaign + ladder + smoke
#
# Fixture flags (all default-off features; the fixture defaults are printed in every run manifest)
#   -lattice <1|4|5|6>      1=native actin 13/6, 4=stair9-45, 5=stair9-90, 6=parametric monotone staircase
#   -site-rise-nm <v>       mode 6 axial rise            (default 2.7 nm = one actin monomer)
#   -site-stair-deg <v>     mode 6 azimuthal advance     (default -27.6923 deg = 360/13)
#   -zone-deg <v>           target-zone accessibility HALF-width (default 40)
#   -no-zone                target-zone accessibility OFF (the control)
#   -mirror <+1|-1>         actin-lattice mirror state
#   -v <um/s>               prescribed axial speed (signed)
#   -az0 <deg>              stored starting filament azimuth
#   -conv-skew-deg <v>      converter stroke skew — MUST remain 0 for the zero-skew test
#   -no-prescribe           release the prescribed translation (native motor-driven gliding)
#   -no-clamp-tilt          release the tilt/height constraint
#   -no-rigid               flexible chain filament instead of the rigid-rod fixture
#   -filament-brownian-axial|-transverse|-roll|-other-rotation on|off
#   -s2-node-brownian|-converter-brownian|-motor-head-brownian|-head-roll-brownian on|off
#   -density <v> -dt <v> -steps <n> -warmup <n> -seeds <n> -seed <n> -out <dir> -no-events
#
# Examples
#   ./scripts/run_vilfan_tz_deterministic.sh -gates
#   ./scripts/run_vilfan_tz_deterministic.sh -campaign -steps 20000 -seeds 8
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
exec nice -n 15 java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx4G \
     -XX:ActiveProcessorCount=4 \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.VilfanTargetZoneDeterministicHarness "$@"
