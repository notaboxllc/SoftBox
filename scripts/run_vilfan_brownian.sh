#!/bin/bash
# Vilfan axial-Brownian study — CPU ONLY, low priority, resumable.
#   NO CUDA. NO TornadoVM. NO TaskGraph. NO device context.
set -euo pipefail
cd "$(dirname "$0")/.."
exec nice -n 17 java -XX:ActiveProcessorCount=1 -XX:+UseSerialGC -Xss64m -Xmx2g --enable-preview \
     -cp . softbox.VilfanBrownianHarness -vilfan-brownian "$@"
