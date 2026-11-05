#!/bin/bash
# Unattended campaign queue + watchdog for canonical site-normal runs (jba 2026-11-04, parked while travelling).
# usage: scripts/park_queue.sh <campaign_dir>     (reads <campaign_dir>/jobs.txt; logs to <campaign_dir>/park.log)
# jobs.txt lines:  <tag> <lawn> <extra canon_glide.sh flags...>        ('#' comments ok; order = priority)
# Every POLL s: a job is DONE when <tag>/summary.txt exists (written only at the end of a run). A job whose launcher has
# died without a summary is RESUMED from checkpoint.bin (-resume) after its per-segment outputs are moved into
# <tag>/seg_NN/ (-resume rewrites trajectory/episodes and resets the roll/avgBound accumulators; scripts/stitch_resume.py
# joins the segments). At most MAXJ SiteNormal JVMs on the machine (counting runs this queue did not start).
# Gives up on a job after MAXR launches. Never kills anything. Stops when every job is done or given up.
C=${1:?campaign dir}; cd /home/jba/Code/SoftBox
POLL=${POLL:-300}; MAXJ=${MAXJ:-3}; MAXR=${MAXR:-6}
log() { echo "$(date '+%F %T') $*" >> "$C/park.log"; }
njvm() { ps -eo comm=,args= | awk '$1=="java" && /SiteNormalLongGlideHarness/' | wc -l; }
alive() { local p; p=$(cat "$C/$1.pid" 2>/dev/null) || return 1; { tr '\0' ' ' < "/proc/$p/cmdline"; } 2>/dev/null | grep -q "$(basename ${LAUNCHER:-canon_glide.sh}).*$C/$1 "; }
log "queue start (pid $$, POLL $POLL, MAXJ $MAXJ, MAXR $MAXR)"
while :; do
  pending=0
  while read -r tag lawn extra; do
    [[ -z "$tag" || "$tag" == \#* ]] && continue
    d="$C/$tag"
    if [ -f "$d/summary.txt" ]; then
      [ -f "$C/$tag.done" ] || { echo "done $(date '+%F %T')" > "$C/$tag.done"; log "$tag DONE: $(grep -m1 '^STOP' "$d/summary.txt")"; }
      continue
    fi
    n=$(cat "$C/$tag.launches" 2>/dev/null || echo 0)
    if alive "$tag"; then pending=1; continue; fi
    if [ "$n" -ge "$MAXR" ]; then [ -f "$C/$tag.gaveup" ] || { touch "$C/$tag.gaveup"; log "$tag GAVE UP after $n launches"; }; continue; fi
    pending=1
    [ "$(njvm)" -ge "$MAXJ" ] && continue
    ./scripts/gpu-crash-recorder.sh --status | grep -q RUNNING || { log "recorder NOT running: not launching $tag"; continue; }
    res=""
    if [ -f "$d/checkpoint.bin" ]; then
      k=1; while [ -e "$d/seg_$(printf %02d $k)" ]; do k=$((k+1)); done; sd="$d/seg_$(printf %02d $k)"; mkdir -p "$sd"
      for f in trajectory_summary.csv episodes.tsv summary.txt progress.json tether_curve.tsv milestones.tsv run_config.json; do
        [ -f "$d/$f" ] && mv "$d/$f" "$sd/"; done
      cp "$d/checkpoint.bin" "$sd/checkpoint_at_resume.bin"
      [ -f "$C/$tag.log" ] && mv "$C/$tag.log" "$sd/run.log"
      res="-resume"; log "$tag RESUME from step $(od -An -t d4 --endian=big -N4 "$d/checkpoint.bin" | tr -d ' ') (segment saved to $sd)"
    else
      log "$tag START fresh"
    fi
    mkdir -p "$d"; ln -sfn "$d/simviewer/longrun" "/home/jba/Code/SoftBox/threejs_$tag"
    # shellcheck disable=SC2086
    setsid nohup ${LAUNCHER:-./scripts/canon_glide.sh} "$lawn" "$d" $extra $res > "$C/$tag.log" 2>&1 < /dev/null &
    echo $! > "$C/$tag.pid"; echo $((n+1)) > "$C/$tag.launches"
    sleep "${GAP:-60}"
  done < "$C/jobs.txt"
  [ "$pending" -eq 0 ] && { log "queue finished"; exit 0; }
  sleep "$POLL"
done
