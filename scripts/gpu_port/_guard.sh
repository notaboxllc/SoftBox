#!/bin/bash
# Shared safety guard for GPU-port benchmark scripts.
# REFUSES to run any GPU benchmark while the production canonical-gliding sweep is active,
# and refuses if invoked from the sweep's live directory. Sourced by every benchmark script.
sweep_guard() {
  # 1) never run from the sweep's live tree
  case "$PWD" in
    /home/jba/Code/SoftBox|/home/jba/Code/SoftBox/*)
      echo "REFUSING: cwd is the sweep's live directory. Run GPU-port benchmarks only from the isolated worktree /home/jba/Code/SoftBox-gpu." >&2
      exit 3 ;;
  esac
  # 2) never run a GPU benchmark while the sweep JVMs are alive
  if pgrep -fa 'LaserTrapHarness .*-glide' >/dev/null 2>&1; then
    echo "REFUSING: a LaserTrapHarness -glide sweep JVM is running. GPU benchmarks are postponed until the sweep releases the hardware (resource policy)." >&2
    echo "  running sweep procs:" >&2
    pgrep -fa 'LaserTrapHarness .*-glide' | sed 's/^/    /' >&2
    exit 4
  fi
  # 3) if the canonical driver is still orchestrating, refuse too (denssx phase pending)
  if pgrep -fa 'run_canonical_gliding.sh' >/dev/null 2>&1; then
    echo "REFUSING: run_canonical_gliding.sh driver is still active (more sweep phases queued)." >&2
    exit 5
  fi
  # 4) GPU must be near-idle to start a benchmark
  local util
  util=$(nvidia-smi --query-gpu=utilization.gpu --format=csv,noheader,nounits 2>/dev/null | head -1)
  if [ -n "$util" ] && [ "$util" -gt 15 ] 2>/dev/null; then
    echo "REFUSING: GPU utilization ${util}% > 15%; something else is using the GPU. Recheck." >&2
    exit 6
  fi
  echo "# guard OK: no sweep active, cwd is the worktree, GPU idle (util=${util:-?}%)." >&2
}
