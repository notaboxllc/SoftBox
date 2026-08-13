#!/bin/bash
# CPU-vs-GPU one-step gate for the PRODUCTION explicit-S2 solver matS2SolveStep
# (softbox.ExplicitMatSolveHarness). This is the triggered CPU/GPU confirmation that
# docs/CPU_GPU_VALIDATION_POLICY.md requires after a structural change to a hot kernel.
# ALWAYS launch through scripts/run_gpu_monitored.sh.
#
# FLAGS MATCH scripts/run_chiral_sites.sh EXACTLY — in particular -Dtornado.enable.fma=false, which is a
# PRE-EXISTING, DOCUMENTED workaround: matS2SolveStep FMA-lowers to an ArithmeticLIRLowerable NPE with FMA
# ON. Verified 2026-08-12 to be independent of the mechanics repair (the pristine pre-repair kernel fails
# identically), so it is a property of the TornadoVM PTX backend, not of this change.
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx4G \
     -Dtornado.recover.bailout=false -Dtornado.enable.fma=false -Dtornado.tvm.maxbytecodesize=65536 \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.ExplicitMatSolveHarness "$@"
