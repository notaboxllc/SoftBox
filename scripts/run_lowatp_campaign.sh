#!/usr/bin/env bash
# ==============================================================================================================
# SoftBox — low-[ATP] campaign driver: crash-resilient, resume-safe, self-continuing.
#
# WHY THIS EXISTS. The low-[ATP] ladder is a many-hour GPU campaign, and this machine intermittently takes a
# CUDA-719 / libcuda SIGSEGV fault (and occasionally a hard freeze + reboot) that kills the JVM mid-run. The
# harness already writes ONE ATOMIC RECORD PER ARM (temp file + rename), so a fault costs at most the arm in
# flight and every completed arm is reused on the next launch. This driver simply keeps relaunching the SAME
# monitored command until every expected record exists, so the campaign makes forward progress across faults
# without a human in the loop.
#
#   ./scripts/run_lowatp_campaign.sh <logfile> -- <chiral-site args...>
#
# It is a LOOP AROUND the mandatory monitored launcher — it does not replace or duplicate launcher logic, and
# every run still goes through scripts/run_gpu_monitored.sh with the crash recorder checked (CLAUDE.md).
#
# STOPPING RULES (so a deterministic failure cannot spin forever):
#   * exit 0 from the child                  -> campaign complete, exit 0
#   * MAX_ATTEMPTS attempts                  -> give up, exit 1
#   * MAX_STALL consecutive attempts that add ZERO new records -> give up, exit 2
#     (a fault that always dies on the same arm is a real defect, not a transient, and must be reported)
# ==============================================================================================================
set -uo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
REC_DIR="$REPO/RUN_LOGS/chiral_sites/lowatp"
MAX_ATTEMPTS="${LOWATP_MAX_ATTEMPTS:-40}"
MAX_STALL="${LOWATP_MAX_STALL:-3}"
COOLDOWN="${LOWATP_COOLDOWN_SEC:-60}"

if [[ $# -lt 3 || "$2" != "--" ]]; then
  echo "usage: $0 <logfile> -- <chiral-site args...>" >&2
  exit 2
fi
LOG="$1"; shift 2
mkdir -p "$(dirname "$LOG")" "$REC_DIR"

count_records() { find "$REC_DIR" -maxdepth 1 -name '*.tsv' ! -name '*.nested.tsv' ! -name '*.trace.tsv' | wc -l; }

stall=0
prev="$(count_records)"
echo "=== low-ATP campaign driver: $(date -Is) ===" | tee -a "$LOG"
echo "    args        : $*" | tee -a "$LOG"
echo "    records now : $prev" | tee -a "$LOG"

for (( attempt=1; attempt<=MAX_ATTEMPTS; attempt++ )); do
  echo "" | tee -a "$LOG"
  echo "=== attempt $attempt/$MAX_ATTEMPTS  $(date -Is)  (records before: $prev) ===" | tee -a "$LOG"

  # the GPU must be visible before we try; if it is not, wait rather than burn an attempt
  for (( w=0; w<30; w++ )); do
    nvidia-smi -L >/dev/null 2>&1 && break
    echo "    waiting for the GPU to become visible ($w)" | tee -a "$LOG"; sleep 20
  done

  "$REPO/scripts/run_gpu_monitored.sh" "$REPO/scripts/run_chiral_sites.sh" "$@" >> "$LOG" 2>&1
  rc=$?
  now="$(count_records)"
  echo "=== attempt $attempt exit=$rc  records: $prev -> $now ===" | tee -a "$LOG"

  if [[ $rc -eq 0 ]]; then
    echo "=== CAMPAIGN COMPLETE after $attempt attempt(s), $now records ===" | tee -a "$LOG"
    exit 0
  fi

  if [[ "$now" -gt "$prev" ]]; then stall=0; else stall=$((stall+1)); fi
  prev="$now"
  if [[ $stall -ge $MAX_STALL ]]; then
    echo "=== ABORT: $stall consecutive attempts added no records (deterministic failure, not a transient) ===" \
      | tee -a "$LOG"
    exit 2
  fi
  echo "    cooling down ${COOLDOWN}s before retry" | tee -a "$LOG"
  sleep "$COOLDOWN"
done

echo "=== ABORT: exhausted $MAX_ATTEMPTS attempts ===" | tee -a "$LOG"
exit 1
