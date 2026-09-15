#!/usr/bin/env bash
# Wait until no JAVA process has <pattern> in its cmdline. Only inspects processes whose exe is java, so it can
# never match the calling shell -- the self-match trap that silently stalled three queued campaigns.
busy(){ for p in $(pgrep -u "$USER" java 2>/dev/null); do
          readlink /proc/$p/exe 2>/dev/null | grep -q java || continue
          tr '\0' ' ' < /proc/$p/cmdline 2>/dev/null | grep -q -- "$1" && return 0
        done; return 1; }
while busy "$1"; do sleep 60; done
