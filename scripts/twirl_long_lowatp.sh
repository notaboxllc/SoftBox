#!/usr/bin/env bash
# THE LONG STRUCTURAL TWIRL RUN AT BEAUSANG 2008's [ATP] (jba, 2026-09-30).
#
# WHY. d800_epsM1p25_fdt_maty6 (scripts/twirl_long_structural.sh) twirls ~11 turns/s left-handed at 3.6 um/s --
# too fast for any real polTIRF setup. Beausang 2008 slowed filaments to 0.1-0.5 um/s with 5-20 uM MgATP (most
# data at 10 uM) so their 80 ms orientation cycle could follow the rotation. The July low-ATP study
# (docs/twirling/LOW_ATP_GLIDING_TWIRLING_FINDINGS.md, eps=15, ChiralSite lineage) found rotation did NOT slow
# with ATP (pitch collapsed 4-30x below Beausang). This repeats the check on the CURRENT structural model.
#
# SAME AS THE HIGH-ATP RUN except: -atp-uM 10 (scales only atpOn, 2e4 -> 100 /s), a 6 x 4 um lawn (travel is
# ~1 um, not ~13), filament centroid at x=+1.5, and DENSITY (env DENS).
# PILOTS (50 ms, RUN_LOGS/motor_audit/campaigns_2026-09/TWIRL_LOWATP/): d800 STALLS (rigor tug-of-war, ~34 rigor
# heads dragging +0.15 pN each vs ~4 post-stroke heads pushing -1.35 pN; rupture off as in the high-ATP run);
# d400 +0.11 um/s; d200 +0.24 um/s. Identity gate: no flag == -atp-uM 2000, byte-identical rows.
# RUNNER: GPU device-resident EXPERIMENTAL site-normal graph (bound-branch CPU/GPU gate not green), ~210 steps/s
# per arm (two arms in parallel cost the same) => 28M steps ~36 h.
# RUPTURE (2026-10-01): rigor rupture is now DEFAULT ON in this harness (CLAUDE.md). The original d200 arm (seed
# 20260901, launched 2026-09-30) ran rupture OFF; reproduce it with EXTRA=-no-rupture. New arms are rupture ON.
# MORE LAWNS (2026-10-01): d400 stalled (~0.02 um/s at 1.5 s) and was stopped; three more d200 lawns via SEED
# (each seed lays a different motor lawn -- the existence gate). The default seed keeps the original output path.
# PROBES (2026-10-04): one rhodamine-style probe per filament segment (default 1 for the rigid -filsegs 1 rod;
# on a rigid rod extra probes all rotate identically). Runs before this date used 8.
PROBES=${PROBES:-1}
set -u
DENS=${DENS:-200}
SEED=${SEED:-20260901}
EXTRA=${EXTRA:-}
# rupture ON (default) => "_rup" suffix; -no-rupture keeps the ORIGINAL path (the 2026-09-30 rupture-off arms).
RUPSUF="_rup"; case "$EXTRA" in *-no-rupture*) RUPSUF="";; esac
if [ "$SEED" = 20260901 ]; then SUF=""; else SUF="_s$SEED"; fi
OUT=${OUT:-RUN_LOGS/motor_audit/campaigns_2026-09/TWIRL_LOWATP/d${DENS}_atp10_epsM1p25_fdt$SUF$RUPSUF}
STEPS=${STEPS:-28000000}      # 3.5 s at dt 1.25e-7
mkdir -p "$(dirname "$OUT")"
rm -rf "$OUT"
export SOFTBOX_XMX=${SOFTBOX_XMX:-4G}
exec ./scripts/run_gpu_monitored.sh ./scripts/run_site_normal_glide_gpu.sh \
    -run -gpu -devicecull -nohires -matx 6.0 -maty 4.0 -filx 1.5 -eta 0.01 -dt 1.25e-7 \
    -steps "$STEPS" -target 16.0 -filsegs 1 -triad -convaz-nocomp -seed "$SEED" \
    -density "$DENS" -stroke-skew -1.25 -atp-uM 10 \
    -probes "$PROBES" -probe-radius 0.04 -viz-stride 8000 -stop-offlawn 0.1 -resident -chunkocc -leanreadback \
    $EXTRA -out "$OUT"
