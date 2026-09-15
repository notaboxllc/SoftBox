#!/bin/bash
# SITE-NORMAL GLIDING ASSAY on the DEVICE-RESIDENT graph (the newly wired chi-dynamic 3-D head stack).
#
# EXPERIMENTAL. The five kernels matBeamGeomTilt / headAxis / siteCouple / kbindGate / matS2SolveStepTilt are
# wired into buildGlidingGraph and the tilt solver's lowering is proven (ExplicitMatSolveTiltHarness), but the
# WHOLE-STEP CPU/GPU gate has NOT gone green on the BOUND branch — natural capture is too rare for the
# equivalence scene to reach it. Treat output from this path as a device smoke / visualisation, NOT a
# measurement, until docs record a passing bound-path gate.
#
# ALWAYS launch through scripts/run_gpu_monitored.sh.
#
# The CPU-only sibling (run_site_normal_long_glide.sh) deliberately carries NO tornado argfile; this script is
# the GPU counterpart and uses the same flag set as run_chiral_sites.sh:
#   -Dtornado.enable.fma=false           matS2SolveStep{,Tilt} FMA-lowers to an ArithmeticLIRLowerable NPE
#   -Dtornado.recover.bailout=false      a lowering failure THROWS — no silent sequential CPU fallback
#   -Dtornado.tvm.maxbytecodesize=65536  the large solver kernel needs the raised cap
#
#   ./scripts/run_gpu_monitored.sh ./scripts/run_site_normal_glide_gpu.sh -run -gpu -steps 20000 -3js <dir>
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
exec java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx${SOFTBOX_XMX:-12G} \
     -Dtornado.recover.bailout=false -Dtornado.enable.fma=false -Dtornado.tvm.maxbytecodesize=65536 \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.SiteNormalLongGlideHarness "$@"
