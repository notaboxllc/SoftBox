#!/usr/bin/env bash
# Per-process device throughput from the crash-diagnostic heartbeat (executeIndex vs elapsedMs).
# Analysis only: reads the trace files the monitored wrapper already writes.
#   usage: scratch_gpu_rate.sh [window_seconds]   (default 300)
W=${1:-300}
NOWMS=$(date +%s%3N)
for f in $(ls -1t "$HOME"/tornado-crash-traces/*.log 2>/dev/null | head -6); do
  pid=$(basename "$f" | sed -E 's/.*-pid([0-9]+)-.*/\1/')
  kill -0 "$pid" 2>/dev/null || continue
  # start-of-run wall clock, so elapsedMs can be converted to absolute time
  t0=$(grep -m1 -oE '^[0-9T:.Z-]+' "$f")
  t0ms=$(date -u -d "$t0" +%s%3N 2>/dev/null) || continue
  # heartbeats inside the window
  awk -v w="$W" -v now="$NOWMS" -v t0="$t0ms" -v pid="$pid" -v f="$f" '
    /phase=HEARTBEAT/ {
      match($0, /elapsedMs=[0-9]+/);  e = substr($0, RSTART+10, RLENGTH-10)+0
      match($0, /executeIndexStarted=[0-9]+/); x = substr($0, RSTART+20, RLENGTH-20)+0
      abs = t0 + e
      if (abs >= now - w*1000) {
        # executeIndex RESTARTS at 0 for every new arm (new plan) — begin a fresh segment on a reset
        if (n == 0 || x < xprev) { e0=abs; x0=x; n=1 } else { n++ }
        e1=abs; x1=x; xprev=x
      }
    }
    END {
      if (n >= 2 && e1 > e0)
        printf "  pid %-8s steps/s %7.1f  over %5.1f s  (%d -> %d)  %s\n",
               pid, (x1-x0)/((e1-e0)/1000.0), (e1-e0)/1000.0, x0, x1, f
      else
        printf "  pid %-8s (insufficient heartbeats in window)  %s\n", pid, f
    }' "$f"
done
