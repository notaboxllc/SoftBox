#!/bin/bash
# ISOLATED LOWERING PROBE for matS2SolveStepTilt — the chi-dynamic 3-D head solver
# (softbox.ExplicitMatSolveTiltHarness). Answers ONE question before any device wiring:
# does the 15x16 tilt solver lower and execute on the TornadoVM PTX backend?
#
# ALWAYS launch through scripts/run_gpu_monitored.sh.
#
# FLAGS MATCH scripts/run_mats2solve_gate.sh EXACTLY. In particular -Dtornado.enable.fma=false is a
# PRE-EXISTING, DOCUMENTED workaround: the SMALLER non-tilt matS2SolveStep FMA-lowers to an
# ArithmeticLIRLowerable NPE with FMA ON, verified 2026-08-12 to be a property of the TornadoVM PTX
# backend rather than of any mechanics change. The tilt kernel inherits it; if the tilt solver fails
# to lower, that is a SEPARATE finding from this known FMA issue and the exception text will say so.
#
# -Dtornado.recover.bailout=false is load-bearing here: without it a lowering failure silently falls
# back to the CPU and the probe would report a false PASS.
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx4G \
     -Dtornado.recover.bailout=false -Dtornado.enable.fma=false -Dtornado.tvm.maxbytecodesize=65536 \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.ExplicitMatSolveTiltHarness "$@"
