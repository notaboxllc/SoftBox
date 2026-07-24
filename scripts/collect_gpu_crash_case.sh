#!/usr/bin/env bash
# ==============================================================================================================
# SoftBox — post-crash evidence collector. Run this ONCE after a hard freeze + reboot.
#
#   ./scripts/collect_gpu_crash_case.sh
#
# Gathers (tolerating missing files and permission limits — it never fails hard, never deletes a source log):
#   ~/tornado-crash-traces           the durable Java lifecycle traces
#   ~/gpu-crash-records/<sessions>   external recorder sessions (kernel journal, NVIDIA telemetry, memory, PCIe)
#   ~/gpu-crash-records/monitored-runs   the monitored wrapper's commands + stdout/stderr
#   previous-boot kernel journal, previous-boot NVRM/Xid/GSP lines, previous-boot tornado-teardown markers
#   current system + GPU metadata, repo revision
#
# Output: ~/gpu-crash-case-YYYYMMDD-HHMMSS.tar.gz  (the case directory is kept too)
# ==============================================================================================================
set -uo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
STAMP="$(date +%Y%m%d-%H%M%S)"
CASE="$HOME/gpu-crash-case-$STAMP"
TRACES="${SOFTBOX_GPU_CRASH_TRACE_DIR:-$HOME/tornado-crash-traces}"
RECORDS="${GPU_CRASH_RECORD_ROOT:-$HOME/gpu-crash-records}"
BOOTS="${1:--1}"     # which previous boot to pull (default -1 = the boot before this one)

mkdir -p "$CASE" || { echo "cannot create $CASE" >&2; exit 2; }
echo "collecting into $CASE"

note() { echo "  $*"; echo "$*" >> "$CASE/collection-notes.txt"; }

# ---------------------------------------------------------------- Java traces
if [[ -d "$TRACES" ]]; then cp -a "$TRACES" "$CASE/tornado-crash-traces" 2>/dev/null && note "copied java traces from $TRACES"
else note "NO java traces at $TRACES"; fi

# ---------------------------------------------------------------- external recorder sessions + monitored runs
if [[ -d "$RECORDS" ]]; then
  mkdir -p "$CASE/gpu-crash-records"
  cp -a "$RECORDS"/. "$CASE/gpu-crash-records/" 2>/dev/null
  note "copied recorder sessions from $RECORDS"
else note "NO recorder records at $RECORDS"; fi

# ---------------------------------------------------------------- previous-boot kernel evidence
if command -v journalctl >/dev/null 2>&1; then
  if journalctl -b "$BOOTS" -k -n1 >/dev/null 2>&1; then
    journalctl -b "$BOOTS" -k -o short-precise > "$CASE/previous-boot-kernel.log" 2>&1
    grep -aiE 'xid|nvrm|gsp|nvidia|pcieport|AER|fell off the bus|GPU has fallen' \
        "$CASE/previous-boot-kernel.log" > "$CASE/previous-boot-nvidia-xid.log" 2>/dev/null
    note "captured previous boot ($BOOTS) kernel journal"
  else
    note "previous-boot kernel journal NOT readable by this user (try: sudo journalctl -b $BOOTS -k -o short-precise > $CASE/previous-boot-kernel.log)"
  fi
  journalctl -b "$BOOTS" -o short-precise -t tornado-teardown > "$CASE/previous-boot-tornado-markers.log" 2>&1 || true
  journalctl --list-boots > "$CASE/boots.txt" 2>&1 || true
else note "journalctl unavailable"; fi

# ---------------------------------------------------------------- current system / GPU metadata
{
  echo "=== collected $(date -u +%Y-%m-%dT%H:%M:%SZ) on $(hostname) ==="
  uname -a; echo
  command -v lsb_release >/dev/null && lsb_release -a 2>/dev/null; echo
  echo "--- nvidia driver ---"; cat /proc/driver/nvidia/version 2>/dev/null; echo
  echo "--- nvidia-smi ---"; nvidia-smi 2>&1; echo
  echo "--- nvidia-smi -q (full) ---"; nvidia-smi -q 2>&1
} > "$CASE/system-now.log" 2>&1

{
  echo "repo=$REPO"
  echo "git_commit=$(git -C "$REPO" rev-parse HEAD 2>/dev/null || echo unknown)"
  echo "git_branch=$(git -C "$REPO" rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)"
  git -C "$REPO" status --porcelain 2>/dev/null | head -50
} > "$CASE/repo-revision.txt" 2>&1

# ---------------------------------------------------------------- summary
LATEST_TRACE="$(ls -1t "$CASE/tornado-crash-traces"/*.log 2>/dev/null | head -n1 || true)"
FINAL_MARKER=""; [[ -n "${LATEST_TRACE:-}" ]] && FINAL_MARKER="$(tail -n1 "$LATEST_TRACE" 2>/dev/null)"
# kernel format: "NVRM: Xid (PCI:0000:01:00): 79, GPU has fallen off the bus."
XIDS="$(sed -nE 's/.*Xid \([^)]*\): *([0-9]+).*/\1/p' "$CASE/previous-boot-nvidia-xid.log" 2>/dev/null | sort -un | tr '\n' ' ')"
GSP="$(grep -aciE 'gsp|heartbeat' "$CASE/previous-boot-nvidia-xid.log" 2>/dev/null || echo 0)"
COVER_START="$(ls -1d "$CASE/gpu-crash-records"/20* 2>/dev/null | head -n1 | xargs -r basename)"
LAST_SESSION="$(ls -1d "$CASE/gpu-crash-records"/20* 2>/dev/null | tail -n1)"
COVER_END="$(sed -n 's/^updated_utc=//p' "$LAST_SESSION/status" 2>/dev/null | tail -n1)"
[[ -z "$COVER_END" ]] && COVER_END="$(basename "${LAST_SESSION:-none}")"

ARCHIVE="$HOME/gpu-crash-case-$STAMP.tar.gz"
tar -czf "$ARCHIVE" -C "$HOME" "$(basename "$CASE")" 2>/dev/null

cat <<EOF

================= GPU CRASH CASE =================
archive            : $ARCHIVE
case directory     : $CASE   (sources NOT deleted)
latest java trace  : ${LATEST_TRACE:-none}
final durable marker: ${FINAL_MARKER:-none}
detected Xid codes : ${XIDS:-none found in previous-boot log}
GSP/heartbeat lines: ${GSP:-0} (previous boot)
recorder coverage  : ${COVER_START:-none} .. ${COVER_END:-none}

Interpretation of the final durable marker (window in which the failure occurred):
  PLAN_EXECUTE_BEGIN / EXECUTE_CALL_BEGIN  -> inside kernel execution / device transfer / execute() internals
  EXECUTE_CALL_END / PLAN_EXECUTE_END      -> execute() returned; after GPU work
  RESULT_PROCESSING_BEGIN                  -> host-side result handling / serialization
  GPU_WORK_DECLARED_FINISHED               -> after declared GPU completion, before teardown
  PLAN_CLOSE_BEGIN                         -> inside TornadoExecutionPlan.close() / native cleanup
  PLAN_CLOSE_END / POST_CLOSE_PAUSE_BEGIN  -> after close returned; delayed driver cleanup
  NORMAL_MAIN_RETURN                       -> late JVM shutdown / native context destruction
  SHUTDOWN_HOOK_ENTERED                    -> during or after shutdown hooks
  HEARTBEAT (last line)                    -> process was alive in that state; nothing else reached
Timing evidence localises the WINDOW. It is NOT root-cause proof: Xid 79 can also come from the driver/GSP
firmware, PCIe link, power delivery, the motherboard, or the GPU itself.
==================================================
EOF
