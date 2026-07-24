#!/usr/bin/env bash
# ==============================================================================================================
# SoftBox — external GPU/system state recorder (Layer 2 of the TornadoVM GPU crash monitoring).
#
# Runs INDEPENDENTLY of the JVM and keeps writing kernel-journal, NVIDIA telemetry, memory and PCIe state to
# persistent files, so that after a hard freeze + reboot the system-side timeline around the last durable Java
# marker is available. Purely observational: it NEVER resets the GPU, never changes driver/BIOS/power settings,
# and never needs root for normal use.
#
#   ./scripts/gpu-crash-recorder.sh              # run in the foreground (Ctrl-C to stop)
#   ./scripts/gpu-crash-recorder.sh --status     # is a recorder running? where is it writing?
#   ./scripts/gpu-crash-recorder.sh --stop       # stop the running recorder
#
# Env overrides: GPU_CRASH_RECORD_ROOT (default ~/gpu-crash-records), SMI_INTERVAL (1), MEM_INTERVAL (5),
#                EXT_INTERVAL (60), GPU_CRASH_RETAIN_DAYS (14; 0 disables pruning)
# ==============================================================================================================
set -uo pipefail

ROOT="${GPU_CRASH_RECORD_ROOT:-$HOME/gpu-crash-records}"
SMI_INTERVAL="${SMI_INTERVAL:-1}"
MEM_INTERVAL="${MEM_INTERVAL:-5}"
EXT_INTERVAL="${EXT_INTERVAL:-60}"
RETAIN_DAYS="${GPU_CRASH_RETAIN_DAYS:-14}"
PIDFILE="$ROOT/recorder.pid"
CURRENT="$ROOT/current"

mkdir -p "$ROOT" || { echo "cannot create $ROOT" >&2; exit 2; }

# ------------------------------------------------------------------ status / stop
recorder_pid() { [[ -f "$PIDFILE" ]] && head -n1 "$PIDFILE" 2>/dev/null || true; }
recorder_alive() { local p; p="$(recorder_pid)"; [[ -n "$p" ]] && kill -0 "$p" 2>/dev/null; }

case "${1:-}" in
  --status)
      if recorder_alive; then
        echo "gpu-crash-recorder RUNNING pid=$(recorder_pid)"
        [[ -L "$CURRENT" || -d "$CURRENT" ]] && echo "  session: $(readlink -f "$CURRENT")"
        [[ -f "$CURRENT/status" ]] && sed -n '1,20p' "$CURRENT/status"
        exit 0
      fi
      echo "gpu-crash-recorder NOT running (pidfile: $PIDFILE)"; exit 1 ;;
  --stop)
      if recorder_alive; then p="$(recorder_pid)"; echo "stopping recorder pid=$p"; kill -TERM "$p"; exit 0; fi
      echo "no running recorder"; exit 1 ;;
  --help|-h)
      sed -n '2,20p' "$0"; exit 0 ;;
esac

# ------------------------------------------------------------------ single-instance lock
if recorder_alive; then
  echo "a gpu-crash-recorder is already running (pid=$(recorder_pid)); refusing to start a second one" >&2
  exit 3
fi
echo $$ > "$PIDFILE"

RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)"
OUT_DIR="$ROOT/$RUN_ID"
mkdir -p "$OUT_DIR"
ln -sfn "$OUT_DIR" "$CURRENT"
echo "gpu-crash-recorder: writing to $OUT_DIR"

STATUS="$OUT_DIR/status"
write_status() {
  { echo "recorder_pid=$$"
    echo "session=$OUT_DIR"
    echo "started_utc=$RUN_ID"
    echo "updated_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "host=$(hostname)"
    echo "journal_ok=$JOURNAL_OK"
    echo "nvidia_smi_ok=$SMI_OK"
    echo "gpu_bus_id=$BUS_ID"
    echo "intervals_sec=smi:$SMI_INTERVAL,mem:$MEM_INTERVAL,ext:$EXT_INTERVAL"
  } > "$STATUS.tmp" && mv "$STATUS.tmp" "$STATUS"
}

# ------------------------------------------------------------------ capability probes (report, never fail hard)
JOURNAL_OK=no; SMI_OK=no; BUS_ID=""; SHORT_BUS=""; SYS_BUS=""
if command -v journalctl >/dev/null 2>&1 && journalctl -k -n1 >/dev/null 2>&1; then
  JOURNAL_OK=yes
else
  echo "WARNING: kernel journal not readable by this user (journalctl -k). NVRM/Xid capture will be EMPTY." | tee "$OUT_DIR/journal-unavailable.txt"
  echo "  fix (one-time, privileged, your choice):  sudo usermod -aG systemd-journal $USER  &&  re-login" >> "$OUT_DIR/journal-unavailable.txt"
fi
if command -v nvidia-smi >/dev/null 2>&1 && nvidia-smi -L >/dev/null 2>&1; then
  SMI_OK=yes
  BUS_ID="$(nvidia-smi --query-gpu=pci.bus_id --format=csv,noheader 2>/dev/null | head -n1 | tr -d ' ')"
  # nvidia-smi reports an 8-digit domain (00000000:01:00.0); lspci wants bb:dd.f and sysfs wants 0000:bb:dd.f
  SHORT_BUS="${BUS_ID: -7}"; [[ -n "$SHORT_BUS" ]] && SYS_BUS="0000:$SHORT_BUS"
  { echo "=== $(date -u +%Y-%m-%dT%H:%M:%S.%NZ) session start ==="; nvidia-smi -q; } > "$OUT_DIR/nvidia-smi-session-start.log" 2>&1
  { uname -a; echo; command -v lsb_release >/dev/null && lsb_release -a; echo; cat /proc/driver/nvidia/version 2>/dev/null; } \
      > "$OUT_DIR/system-info.log" 2>&1
else
  echo "WARNING: nvidia-smi unavailable — GPU telemetry will be EMPTY." | tee "$OUT_DIR/nvidia-unavailable.txt"
fi
write_status

# ------------------------------------------------------------------ retention (never touches the live session)
if [[ "$RETAIN_DAYS" -gt 0 ]]; then
  find "$ROOT" -maxdepth 1 -type d -name '20*' -mtime "+$RETAIN_DAYS" -print 2>/dev/null \
    | grep -v "$OUT_DIR" | while read -r old; do echo "pruning old session $old"; rm -rf "$old"; done
fi

PIDS=()

# ---- kernel journal (line-buffered follow; where NVRM Xid / GSP messages land) ----
if [[ "$JOURNAL_OK" == yes ]]; then
  stdbuf -oL -eL journalctl -kf -o short-precise >> "$OUT_DIR/kernel-live.log" 2>&1 &
  PIDS+=($!)
fi

# ---- NVIDIA telemetry (~1 s) ----
if [[ "$SMI_OK" == yes ]]; then
  (
    while true; do
      ts="$(date -u +%Y-%m-%dT%H:%M:%S.%NZ)"
      out="$(nvidia-smi \
        --query-gpu=timestamp,index,pci.bus_id,pstate,temperature.gpu,power.draw,power.limit,utilization.gpu,utilization.memory,memory.used,memory.total,clocks.current.graphics,clocks.current.memory,pcie.link.gen.current,pcie.link.width.current,clocks_throttle_reasons.active \
        --format=csv,noheader 2>&1)"; rc=$?
      if [[ $rc -ne 0 ]]; then   # a failed field set must not kill the sampler — retry a reduced query
        out="$(nvidia-smi --query-gpu=timestamp,index,pci.bus_id,pstate,temperature.gpu,power.draw,utilization.gpu,memory.used,memory.total \
              --format=csv,noheader 2>&1)"; rc=$?
      fi
      printf '%s | rc=%d | %s\n' "$ts" "$rc" "$(printf '%s' "$out" | tr '\n' ';')"
      sleep "$SMI_INTERVAL"
    done
  ) >> "$OUT_DIR/nvidia-smi-live.log" 2>&1 &
  PIDS+=($!)
fi

# ---- system memory (~5 s) ----
(
  while true; do
    printf '\n===== %s =====\n' "$(date -u +%Y-%m-%dT%H:%M:%S.%NZ)"
    cat /proc/meminfo
    sleep "$MEM_INTERVAL"
  done
) >> "$OUT_DIR/memory-live.log" 2>&1 &
PIDS+=($!)

# ---- extended NVIDIA + PCIe state (~60 s) ----
(
  while true; do
    printf '\n===== %s =====\n' "$(date -u +%Y-%m-%dT%H:%M:%S.%NZ)"
    if [[ "$SMI_OK" == yes ]]; then
      # -d PCIE is not accepted by every driver branch (595.71.05 rejects it) — query it separately and
      # tolerate the failure rather than losing the whole extended sample.
      nvidia-smi -q -d POWER,TEMPERATURE,CLOCK,PERFORMANCE 2>&1
      printf -- '--- nvidia-smi -q -d PCIE ---\n'; nvidia-smi -q -d PCIE 2>&1 | head -40
    fi
    if [[ -n "$SHORT_BUS" ]]; then
      # PCIe link state WITHOUT root (lspci's LnkSta lives under "Capabilities: <access denied>" for non-root).
      printf -- '--- sysfs PCIe link state (%s) ---\n' "$SYS_BUS"
      for f in current_link_speed current_link_width max_link_speed max_link_width power/runtime_status; do
        [[ -r "/sys/bus/pci/devices/$SYS_BUS/$f" ]] && printf '%s=%s\n' "$f" "$(cat "/sys/bus/pci/devices/$SYS_BUS/$f" 2>/dev/null)"
      done
      for f in aer_dev_correctable aer_dev_fatal aer_dev_nonfatal; do
        [[ -r "/sys/bus/pci/devices/$SYS_BUS/$f" ]] && { printf -- '--- %s ---\n' "$f"; cat "/sys/bus/pci/devices/$SYS_BUS/$f" 2>/dev/null; }
      done
      if command -v lspci >/dev/null 2>&1; then
        printf -- '--- lspci -vv -s %s ---\n' "$SHORT_BUS"
        lspci -vv -s "$SHORT_BUS" 2>&1 | sed -n '1,80p'   # LnkSta/AER only when permitted (root)
      fi
    fi
    sleep "$EXT_INTERVAL"
  done
) >> "$OUT_DIR/nvidia-extended.log" 2>&1 &
PIDS+=($!)

# ---- recorder health (~30 s): proves the recorder itself was alive across the crash window ----
(
  while true; do
    printf '%s recorder alive pid=%d children=%s load=%s\n' \
      "$(date -u +%Y-%m-%dT%H:%M:%S.%NZ)" "$$" "${PIDS[*]:-}" "$(cut -d' ' -f1-3 /proc/loadavg)"
    sleep 30
  done
) >> "$OUT_DIR/recorder-health.log" 2>&1 &
PIDS+=($!)

cleanup() {
  trap - EXIT INT TERM
  echo "gpu-crash-recorder: stopping (session $OUT_DIR)"
  for p in "${PIDS[@]:-}"; do kill "$p" 2>/dev/null || true; done
  sleep 0.2
  for p in "${PIDS[@]:-}"; do kill -9 "$p" 2>/dev/null || true; done
  printf '%s recorder stopped\n' "$(date -u +%Y-%m-%dT%H:%M:%S.%NZ)" >> "$OUT_DIR/recorder-health.log"
  [[ "$(recorder_pid)" == "$$" ]] && rm -f "$PIDFILE"
  exit 0
}
trap cleanup EXIT INT TERM

# Keep the status file's updated_utc fresh so coverage windows are provable.
# `sleep & wait` (not a bare `sleep`) so SIGTERM runs the cleanup trap IMMEDIATELY — bash defers a trap until a
# foreground child exits, which would otherwise make --stop take up to the full interval.
while true; do
  sleep 30 & wait $! 2>/dev/null
  write_status
done
