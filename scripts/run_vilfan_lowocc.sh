#!/bin/bash
# Vilfan low-occupancy Brownian study — CPU ONLY. No CUDA/TornadoVM/TaskGraph/device context.
set -euo pipefail
cd "$(dirname "$0")/.."
exec nice -n 17 java -XX:ActiveProcessorCount=1 -XX:+UseSerialGC -Xss64m -Xmx2g --enable-preview \
     -cp . softbox.VilfanLowOccHarness -vilfan-lowocc "$@"
