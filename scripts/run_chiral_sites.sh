#!/bin/bash
# Discrete actin sites + head rotational DOF + bound orientational registry + askew bind/stroke
# (noncanonical, flag-gated, DEFAULT-OFF).  Report: docs/DISCRETE_ACTIN_SITE_CHIRAL_BINDING_AND_STROKE_FINDINGS.md
#
#   -fixtures    deterministic Stage-0/1/2/3/4/5 gates (CPU runner)
#   -equiv       FULL chiral-site gliding graph, device-resident, vs the CPU runner (no silent fallback)
#   -campaign    the arm table  C0 / C1 / C2 / B± / S±  (+ randomized-base-azimuth and mirrored controls)
#   -lattice     lattice comparison (native / every3 / every4 / stair9-45 / stair9-90) at fixed askew angle
#   -3js <dir>   movies for the two most informative arms
#
#   SINGLE-SEGMENT, FILAMENT-BROWNIAN-OFF DYNAMIC TWIRLING ASSAY (§20 of the report):
#   -twirl-audit  one-segment geometry + drag audit, Brownian-channel accounting (filament channels EXACTLY
#                 zero / motor+head-roll channels still nonzero), Ractin=0 torque-arm control, roll-spring-off
#   -twirl-equiv  FULL one-segment Brownian-off gliding graph, device-resident, vs the CPU runner
#   -twirl-pilot  short health/sign pilot (R0 / R+ / R-)
#   -twirl        the powered arm matrix R0/R±/RM± + S0/S± + filament-Brownian-ON + multisegment controls
#   -twirl-dt     R± at the current dt and at dt/2 (matched simulated time)
#   -twirl -3js <dir>   movies for R+, R-, RM+ (material roll ticks + per-head torque sign + total torque bar)
# Flags: -density <v> -seed <n> -seeds <n> -steps <n> -stride <n> -actin-bind-radius-nm <v>
#        -bound-registry-k <N·m/rad> -binding-skew-deg <deg> -discrete-actin-sites <off|native|every3|every4|
#        stair9-45|stair9-90> -randomize-motor-base-azimuth <on|off>
#        -filament-segments <n>  -filament-brownian <on|off>  -twirl-skew-deg <deg>  -halfdt
#        -equil-frac <f>  -blocks <n>
#
# MONITORED EXECUTION IS MANDATORY (CLAUDE.md): launch through scripts/run_gpu_monitored.sh, e.g.
#     ./scripts/run_gpu_monitored.sh ./scripts/run_chiral_sites.sh -equiv
#
# DEVICE FLAGS (the known-good explicit-S2 set — see docs/EXPLICIT_S2_GPU_LOWERING_REGRESSION_FINDINGS.md):
#   -Dtornado.enable.fma=false           matS2SolveStep FMA-lowers to an ArithmeticLIRLowerable NPE with FMA ON
#   -Dtornado.recover.bailout=false      a lowering failure THROWS — no silent sequential CPU fallback
#   -Dtornado.tvm.maxbytecodesize=65536  the large solver kernel needs the raised cap
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx8G \
     -Dtornado.recover.bailout=false -Dtornado.enable.fma=false -Dtornado.tvm.maxbytecodesize=65536 \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.ChiralSiteHarness "$@"
