#!/usr/bin/env bash
# ==============================================================================================================
# SoftBox — monitored GPU campaign wrapper.
#
# WRAPS an existing launcher without changing its arguments or its behaviour: it checks that the external
# recorder is running, establishes a run id, turns on the Java crash trace via the environment, tees stdout and
# stderr into the run's evidence directory, records the exact command + environment, and PRESERVES the child's
# exit status.
#
#   ./scripts/run_gpu_monitored.sh ./scripts/run_singlehead_gpu.sh -production-cell -density 300 -seed 101 ...
#   ./scripts/run_gpu_monitored.sh ./scripts/run_explicit_twirl.sh -equiv
#
# It does NOT duplicate launcher logic and adds no teardown pauses. Diagnostic-only options can still be passed
# through to the JVM by the usual environment knobs (SOFTBOX_GPU_PRE_CLOSE_PAUSE_SEC, SOFTBOX_GPU_CLOSE_POLICY,
# ...) — those are controlled experiments, not routine campaign settings.
# ==============================================================================================================
set -uo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
RECORDER="$REPO/scripts/gpu-crash-recorder.sh"
RUNS_ROOT="${SOFTBOX_GPU_RUNS_ROOT:-$HOME/gpu-crash-records/monitored-runs}"

if [[ $# -lt 1 ]]; then
  echo "usage: $0 <launcher> [args...]" >&2
  exit 2
fi

# ---------------------------------------------------------------- 1. external recorder present?
REC_STATE="not-running"
if "$RECORDER" --status >/dev/null 2>&1; then
  REC_STATE="running"
else
  cat >&2 <<EOF
*** WARNING: the external GPU crash recorder is NOT running. ***
    Kernel NVRM/Xid messages and NVIDIA telemetry will NOT be captured for this run.
    Start it with either:
        systemctl --user start softbox-gpu-crash-recorder.service
        $RECORDER            (foreground, in another terminal)
    Continuing anyway — the Java lifecycle trace will still be written.
EOF
fi

# ---------------------------------------------------------------- 2. run id + evidence directory
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-$$"
RUN_DIR="$RUNS_ROOT/$RUN_ID"
mkdir -p "$RUN_DIR" || { echo "cannot create $RUN_DIR" >&2; exit 2; }

# ---------------------------------------------------------------- 3. Java crash trace on (env, not argv:
#     the launcher's arguments are passed through untouched)
export SOFTBOX_GPU_CRASH_TRACE=1
export SOFTBOX_GPU_CRASH_TRACE_DIR="${SOFTBOX_GPU_CRASH_TRACE_DIR:-$HOME/tornado-crash-traces}"
export SOFTBOX_GPU_CRASH_HEARTBEAT_SEC="${SOFTBOX_GPU_CRASH_HEARTBEAT_SEC:-5}"
mkdir -p "$SOFTBOX_GPU_CRASH_TRACE_DIR"

# ---------------------------------------------------------------- 4. record the exact command + environment
{
  echo "run_id=$RUN_ID"
  echo "utc_start=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "host=$(hostname)"
  echo "cwd=$PWD"
  echo "repo=$REPO"
  echo "git_commit=$(git -C "$REPO" rev-parse HEAD 2>/dev/null || echo unknown)"
  echo "git_branch=$(git -C "$REPO" rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)"
  echo "git_dirty=$(git -C "$REPO" status --porcelain 2>/dev/null | wc -l) files"
  echo "recorder=$REC_STATE"
  echo "recorder_session=$(readlink -f "$HOME/gpu-crash-records/current" 2>/dev/null || echo none)"
  echo "trace_dir=$SOFTBOX_GPU_CRASH_TRACE_DIR"
  echo -n "command="; printf '%q ' "$@"; echo
} > "$RUN_DIR/run-meta.txt"
env | sort > "$RUN_DIR/environment.txt"
printf '%q ' "$@" > "$RUN_DIR/command.txt"; echo >> "$RUN_DIR/command.txt"

echo "[monitored] run_id=$RUN_ID"
echo "[monitored] evidence dir: $RUN_DIR"
echo "[monitored] recorder: $REC_STATE"
echo "[monitored] java traces: $SOFTBOX_GPU_CRASH_TRACE_DIR"

# ---------------------------------------------------------------- 5. run the launcher UNCHANGED, tee'ing output
#     Process substitution (not a pipeline) so $? IS the launcher's own exit status.
"$@" > >(tee "$RUN_DIR/stdout.log") 2> >(tee "$RUN_DIR/stderr.log" >&2)
RC=$?

# the process-substitution tees can lag a beat behind the child's exit
sleep 0.3

# The JVM prints its own trace path ("[gpu-crash-trace] <file>") — prefer that over "newest file", which would
# pick the wrong trace if two monitored runs overlap.
LATEST_TRACE="$(grep -m1 -oE '\[gpu-crash-trace\] .*' "$RUN_DIR/stdout.log" 2>/dev/null | cut -d' ' -f2- || true)"
[[ -z "${LATEST_TRACE:-}" || ! -f "${LATEST_TRACE:-}" ]] && \
  LATEST_TRACE="$(ls -1t "$SOFTBOX_GPU_CRASH_TRACE_DIR"/*.log 2>/dev/null | head -n1 || true)"
{
  echo "utc_end=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "exit_code=$RC"
  echo "java_trace=${LATEST_TRACE:-none}"
  [[ -n "${LATEST_TRACE:-}" ]] && echo "final_marker=$(tail -n1 "$LATEST_TRACE" 2>/dev/null)"
} >> "$RUN_DIR/run-meta.txt"

echo "[monitored] exit=$RC"
[[ -n "${LATEST_TRACE:-}" ]] && echo "[monitored] java trace: $LATEST_TRACE" \
  && echo "[monitored] final marker: $(tail -n1 "$LATEST_TRACE" 2>/dev/null)"
exit $RC
