#!/usr/bin/env bash
# ONE LONG, FDT-CORRECT TWIRL RUN AT THE STRUCTURAL SKEW (jba, 2026-09-24).
#
# WHY. ROLLCLAMP_TWIRL (d200/d800, eps +/-4, roll Brownian OFF, one lawn, stopped at ~0.32 s) showed the
# imposed skew is carried through to PERSISTENT filament roll: eps_odd -20.7 +/- 2.9 turns/s at d800 (7 sigma),
# ~5.6-6.3 turns per um glided, i.e. ~1.4-1.6 turns/um/deg -- and ~flat in density because the per-stroke
# glide AND roll both fall ~2.5x as avgBound rises 1.6 -> 6.2 (the tug-of-war constrains both equally).
# jba wants a single experiment-like trajectory on a SECONDS timescale, not a +/-eps pair to tease apart.
#
# CHOICES.
#   eps = -1.25 deg  STRUCTURAL, not fitted: the motor-side F8-anchor tangential shift between primed and
#                    post-stroke actomyosin-5a (docs/twirl/ACTIN_SITE_LATTICE_LITERATURE_BASIS.md s7.4.1;
#                    1.2 sigma, myosin-Va -- a weak structural basis, stated as such). Predicts ~1.7-2.0
#                    turns/um vs Beausang 2008's ~2.1 (twirlers-only, skeletal myosin II, unreplicated).
#                    SIGN: roll is right-handed about the barbed axis (+x) and the filament glides -x, so
#                    POSITIVE roll = omega antiparallel to v = LEFT-handed. eps<0 gave positive roll, so
#                    -1.25 is the left-handed choice. The sign is CHOSEN to match experiment, not predicted.
#   roll Brownian ON (FDT-correct): any >1-day production run must be (jba). Costs only ~10% more roll noise.
#   d800             publication density, avgBound ~6-8.
#   20 um mat        so the filament can glide for seconds (the harness has no wrap-around lawn).
#   probes           rhodamine-style viewer-only labels (no physics), radius exaggerated for legibility;
#                    the factor is written into every frame as probeExag.
#
# EXPECTATION (rough, from the one 0.32 s roll-Brownian-ON arm): drift ~6-7 turns/s, thermal roll noise
# ~3.6*sqrt(T) turns => single-trajectory SNR ~1.8*sqrt(T): ~3 sigma by 3 s.
# RUNNER: GPU device-resident EXPERIMENTAL site-normal graph (bound-branch CPU/GPU gate not green).
#
# REV 2 (2026-09-26). The first run (maty 2.0, d800_epsM1p25_fdt, kept on disk) drifted ~1 um sideways in
# 1.7 s and rode the lawn y-edge from 1.72 s on (glide 3.57 -> 2.61 um/s, avgBound falling), so it was
# stopped at 2.35 s. On-lawn result: +1.94 +/- 0.86 turns/um (2.3 sigma). Now: a 6 um-wide lawn (96000 motors)
# and -stop-offlawn so a run can never again burn days off the lawn. Measured-based estimate ~65 steps/s,
# ~5 days for 3.5 s. Heap raised to 10G (the 32000-motor arm already sat at its 4G cap; it runs alone).
# -resident (2026-09-26): motor/filament state stays on the GPU instead of being re-uploaded every step
# (profiled: ~57% of host time at 96k motors). Byte-identical to the historical transfers (gate: 9 rows).
# -chunkocc (2026-09-26): the two single-thread occupancy/steric resolvers skip empty 256-motor blocks
# (profiled: 10.5 of 12.4 ms/step of GPU kernel time at 96k motors). Byte-identical on GPU (43 captures).
# -leanreadback (2026-09-26): copy back per step only what the loop reads (~6 of 41 MB at 96k motors); frame
# and checkpoint state pulled on demand. Rows + frames byte-identical; 16k-motor scene 119 -> 162 steps/s.
set -u
MATY=${MATY:-6.0}
OUT=${OUT:-RUN_LOGS/motor_audit/campaigns_2026-09/TWIRL_LONG_STRUCTURAL/d800_epsM1p25_fdt_maty6}
STEPS=${STEPS:-28000000}      # 3.5 s at dt 1.25e-7
mkdir -p "$(dirname "$OUT")"
rm -rf "$OUT"
export SOFTBOX_XMX=${SOFTBOX_XMX:-10G}
exec ./scripts/run_gpu_monitored.sh ./scripts/run_site_normal_glide_gpu.sh \
    -run -gpu -devicecull -nohires -matx 20.0 -maty "$MATY" -filx 8.5 -eta 0.01 -dt 1.25e-7 \
    -steps "$STEPS" -target 16.0 -filsegs 1 -triad -convaz-nocomp -seed 20260901 \
    -density 800 -stroke-skew -1.25 \
    -probes 8 -probe-radius 0.04 -viz-stride 8000 -stop-offlawn 0.1 -resident -chunkocc -leanreadback \
    -out "$OUT"
