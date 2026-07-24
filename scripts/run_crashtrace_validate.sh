#!/usr/bin/env bash
# Validation harness for the GPU crash-trace instrumentation (no GPU work, no physics).
#   ./scripts/run_crashtrace_validate.sh              # emit a synthetic lifecycle, then verify it in a 2nd JVM
#   ./scripts/run_crashtrace_validate.sh <traceFile>  # verify an existing trace (e.g. from a real monitored run)
set -uo pipefail
cd "$(dirname "$0")/.." || exit 2

TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
JAVA_CP="$TDIR/tornado-api-4.0.1-dev.jar:."
# The same device flags the explicit-S2 launchers use, so the LAUNCH_CONFIG_WARNING check is exercised as it
# will be in production. (No TaskGraph is built here — the flags are only read and logged.)
FLAGS="-Dtornado.recover.bailout=false -Dtornado.enable.fma=false -Dtornado.tvm.maxbytecodesize=65536"

if [[ $# -ge 1 ]]; then
  exec java --enable-preview -cp "$JAVA_CP" softbox.CrashTraceValidationHarness -verify "$1"
fi

OUT="$(mktemp -d)/emit.log"
java --enable-preview $FLAGS -cp "$JAVA_CP" \
     softbox.CrashTraceValidationHarness -emit -gpu-crash-trace -gpu-crash-heartbeat-sec 2 -hold 7 \
     > "$OUT" 2>/dev/null
RC=$?
TRACE="$(grep -m1 '^TRACE_PATH=' "$OUT" | cut -d= -f2-)"
echo "emit exit=$RC trace=$TRACE"
[[ -f "$TRACE" ]] || { echo "*** no trace file produced ***"; exit 1; }

echo
java --enable-preview -cp "$JAVA_CP" softbox.CrashTraceValidationHarness -verify "$TRACE"
