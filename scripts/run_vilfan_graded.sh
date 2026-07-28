#!/bin/bash
# Vilfan GRADED competing-site attachment on native 13/6 actin.
#
# ======================= CPU ONLY — NO GPU, NO DEVICE EXECUTION =======================
# Plain sequential JVM runner: no TaskGraph, no device context, no TornadoVM device call.
# @tornado-argfile is present only for classpath / --enable-preview parity.
#   -XX:ActiveProcessorCount=3   caps JVM-internal parallelism (2-4 logical processors)
#   nice -n 17                   yields CPU to the active low-ATP GPU study
# =====================================================================================
#
# Modes:  -gates      graded-hazard unit gates (Stage 8)
#         -window     candidate-window convergence (Stage 2)
#         -landscape  static attachment-rate landscape, native + mirrored (Stage 3)
#         -binding    binding-only first-passage test, roll CLAMPED (Stage 4)
#         -dynamic    dynamic torque closure, roll FREE (Stage 5)
#         -ladder     bounded prescribed-speed ladder (Stage 6)
#         -all        everything, in order
#
# Flags:  -attach-mode vilfan-graded|vilfan-graded-hybrid|angular-neutral|longitudinal-neutral|hard-zone
#         -alpha <v>  -win <sites>  -v <um/s>  -mirror <+1|-1>  -az0 <deg>
#         -lattice <1|6>  -seeds <n> -steps <n> -warmup <n> -density <v> -maty <v> -shadow -out <dir>
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
exec nice -n 17 java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx4G \
     -XX:ActiveProcessorCount=3 \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.VilfanGradedBindingHarness "$@"
