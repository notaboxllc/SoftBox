#!/bin/bash
# Soft Box build (aorus, Java 21 + TornadoVM 4.0.1-dev PTX). Same toolchain as v1's -gpu path.
set -e
TDIR="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx/share/java/tornado"
javac -g --release 21 --enable-preview -XDignore.symbol.file \
      -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
      softbox/*.java
echo "build ok"
