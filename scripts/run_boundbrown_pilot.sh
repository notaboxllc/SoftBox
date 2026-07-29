#!/usr/bin/env bash
# ==============================================================================================================
# SoftBox — low-[ATP] BOUND-BROWNIAN-OFF small-skew pilot: the 14-arm campaign, in one resumable driver.
#
#   14 arms = eps 0 (seeds 101,102)  +  eps +/-1, +/-2, +/-15  x  seeds 101,102
#   all at [ATP] = 5 uM, 200 ms physical, 25% equilibration, eta = 0.01 Pa.s, dt = 2.5e-7 s,
#   MECHANICAL BROWNIAN MODE = DETACHED_SEARCH_ONLY.
#
# RUNNER: GPU device-resident (ExplicitCompleteMatHarness.buildGlidingGraph), through the MANDATORY monitored
# launcher, via the crash-resilient campaign driver. There is no CPU fallback: -gpu builds the device graph and
# a lowering failure aborts the arm rather than silently running on the CPU runner.
#
# Each arm writes ONE ATOMIC RECORD (temp file + rename), so a fault costs at most the arm in flight and every
# completed arm is reused on relaunch. Record ids carry [ATP], |eps|, the Brownian mode ('_bdso'), duration,
# eps sign and seed, so no arm of this pilot can alias onto a Brownian-ON record.
#
#   ./scripts/run_boundbrown_pilot.sh [logdir]
# ==============================================================================================================
set -uo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
LOGDIR="${1:-$REPO/RUN_LOGS/lowatp_boundbrown}"
mkdir -p "$LOGDIR"
MODE=(-mech-brownian-mode detached-search-only)
COMMON=(-gpu -eta 0.01 -seeds 2 -atp-duration-ms 200 -atp-nested-ms 50,100,150,200 "${MODE[@]}")

run () {   # run <tag> <args...>
  local tag="$1"; shift
  echo "=== ARM GROUP $tag  $(date -Is) ===" | tee -a "$LOGDIR/driver.txt"
  "$REPO/scripts/run_lowatp_campaign.sh" "$LOGDIR/driver.txt" -- "$@" >> "$LOGDIR/pilot_$tag.txt" 2>&1
  local rc=$?
  echo "=== ARM GROUP $tag exit=$rc  $(date -Is) ===" | tee -a "$LOGDIR/driver.txt"
  return $rc
}

run e1  -atp-map  "${COMMON[@]}" -atp-points 5 -atp-eps-deg 1  || exit 1
run e2  -atp-map  "${COMMON[@]}" -atp-points 5 -atp-eps-deg 2  || exit 1
run e15 -atp-map  "${COMMON[@]}" -atp-points 5 -atp-eps-deg 15 || exit 1
run e0  -atp-null "${COMMON[@]}" -atp-uM 5                     || exit 1

echo "ALL-BOUND-BROWNIAN-OFF-ARMS-DONE $(date -Is)" | tee -a "$LOGDIR/driver.txt"
