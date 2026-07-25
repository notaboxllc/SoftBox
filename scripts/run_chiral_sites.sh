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
#
#   TRUE LOCAL-FRAME ROTATION OF THE CONVERTER POWER STROKE (§21 of the report; MOTOR-side, distinct from the
#   actin-side -binding-skew-deg / -stroke-skew-deg):
#   -conv-stage1   deterministic converter-trajectory angle sweep through 90° + force/torque/energy closure (CPU)
#   -conv-fixtures Stage-3 symmetry fixtures (eps-sign, randomized base, Ractin=0, rigid rotation, no-site-move, detach, default-off)
#   -conv-equiv    FULL converter-skew gliding graph, device-resident, vs the CPU runner (no silent fallback)
#   -conv-pilot    short live gliding pilot (R±, S±) — health + sign
#   -conv-campaign powered live campaign (R±, S±, mirror RM±) + stroke-event-conditioned torque
#   -conv-compare  BIND vs STEP vs CONV on the same dynamic scene (mechanism discriminator)
#   -conv-dt       CONV± at dt vs dt/2 (matched simulated time)
#   -conv-sweep    STAGE-1 direct-twirl angle sweep S0/S±5/S±15/S±30 (shared base, native) — direct body-fixed roll
#                  Ω(slope of Θ), windowed stroke impulse J_θ, and the sin(ε) scaling of Ω_odd/J_odd
#                  (angles via -conv-angles "5,15,30")
#   -conv-controls MIRROR (SM±) + RANDOMIZED-base (R±) controls at -converter-stroke-skew-deg <best> (+ S± native ref)
#
#   CONVERTER-TWIRLING EFFICIENCY + MOTOR-GEOMETRY AUDIT (§23 of the report):
#   -conv-budget         FULL BOUND-CYCLE angular-impulse budget: per-episode J_pre / J_stroke(lags 0-7) /
#                        J_post_early(8-31) / J_post_late(32→detach) / J_total / J_recoil / f_retain, seed as the
#                        independent unit; stratified loss tables; twirling-efficiency metrics; reduced-atlas
#                        classification; + the eps=0 achiral arm and its half-split NULL (the noise floor)
#   -conv-gauge-compare  interface vs pivot converter-skew gauge on matched seeds (the static-preload channel)
#   -conv-geom-sweep <axis>   one-factor-at-a-time motor-geometry sweep with a deterministic per-value CONFOUND
#                        block (unloaded stroke at eps=0 and eps, loaded forces) so a longer stroke can never
#                        masquerade as improved efficiency.  axis = s2len | s2bend | ecc | ecccomp | trans
#   -conv-geom-values "a,b,…"  override the axis's pilot list (powered finalist head-to-heads)
#   Geometry flags (ALL default-off, exact no-ops, scene parameters only — no kernel is touched):
#     -s2-free-length-scale <s>              L → s·L at fixed M (l0, ks=EA/l0, kb=EI/l0, emergence point rescale)
#     -s2-bend-stiffness-scale <s>           kb → s·kb alone
#     -converter-f8-eccentricity-scale <s>   |rCF8_perp| → s·|rCF8_perp| (moves the F8 material point only)
#     -converter-f8-eccentricity-compensated <on|off>   hold |d0| fixed (stroke-magnitude-matched control)
#     -converter-transverse-offset-nm <d>    roll the base triad about its own b̂ so the converter JOINT C moves
#                                            ⊥ the axial plane by d nm at the reference pose
#   New flags: -converter-stroke-skew-deg <deg>  -converter-skew-gauge <on|off>
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
