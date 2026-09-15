#!/usr/bin/env bash
# LONG, HIGH-DENSITY, FULL-MAT SITE-NORMAL GLIDING ASSAY — CPU sequential runner ONLY.
#
# The site-normal + 3-D-head path has NO validated device lowering (buildGlidingGraph REFUSES it), so this
# assay is CPU-only by construction; no GPU work is launched and the old non-chi GPU motor is never
# substituted. That is why this script does NOT go through scripts/run_gpu_monitored.sh.
#
#   ./scripts/run_site_normal_long_glide.sh -gates            # Phase 0/3 pre-run gates (fast)
#   ./scripts/run_site_normal_long_glide.sh -gates -gate-a    # + the culled-vs-all-active control (~25 min)
#   ./scripts/run_site_normal_long_glide.sh -run              # the long trajectory (up to 1e6 steps)
#   ./scripts/run_site_normal_long_glide.sh -run -resume      # resume from RUN_LOGS/.../checkpoint.bin
#   ./scripts/run_site_normal_long_glide.sh -report           # re-print the summary
set -euo pipefail
cd "$(dirname "$0")/.."
TDIR="$TORNADOVM_HOME/share/java/tornado"
exec java --enable-preview -Xmx12g -cp "$TDIR/tornado-api-4.0.1-dev.jar:/home/jba/Code/SoftBox/.build-dtheta" \
     softbox.SiteNormalLongGlideHarness "$@"
