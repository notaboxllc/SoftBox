#!/bin/bash
# ============================================================================================
# SM4 — force-dependent attachment lifetime (the xCatch assay). CPU-ONLY, single bound head.
#
# Measures actomyosin ATTACHMENT LIFETIME under a CONSTANT axial load from a NAMED PREPARED
# nucleotide state (rigor | adp | cycling), using the frozen explicit motor and the existing
# chemistry kernel. No physics is retuned; the harness only prepares, clamps, and observes.
#
#   ./scripts/run_sm4.sh -selftest                          # apparatus gates (A/B/C), fast
#   ./scripts/run_sm4.sh -smoke                             # the CPU smoke matrix (n=40/cell)
#   ./scripts/run_sm4.sh -states rigor,adp -forces 0,3,6 -dirs opposing,assisting -events 40
#   ./scripts/run_sm4.sh -grid                              # the LITERATURE-READY production grid
#
# Sign convention: +force = toward the BARBED end = OPPOSING (catch side);
#                  -force = toward the POINTED end = ASSISTING (slip side).
#
# NOTE ON BUILDING: while a long GPU campaign is running, do NOT run ./scripts/build.sh --
# it rewrites .class files in the repo tree that the campaign's per-cell JVMs load. Compile to a
# scratch dir and point SM4_CLASSES at it:
#   javac -g --release 21 --enable-preview -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
#         -d /tmp/sm4classes softbox/Sm4ForceLifetimeHarness.java
#   SM4_CLASSES=/tmp/sm4classes ./scripts/run_sm4.sh -selftest
# ============================================================================================
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
cd "$(dirname "$0")/.." || exit 2

OUT="RUN_LOGS/motor_validation/sm4_force_lifetime"
REV="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
CP="${SM4_CLASSES:+$SM4_CLASSES:}$TDIR/tornado-api-4.0.1-dev.jar:."

ARGS=()
MODE="matrix"
GRID=0
for ((i=1;i<=$#;i++)); do
  case "${!i}" in
    -grid)  GRID=1;;
    -smoke) ;;                       # default matrix already is the smoke matrix
    -outdir) j=$((i+1)); OUT="${!j}";;
    *) ARGS+=("${!i}");;
  esac
done

if [[ $GRID -eq 1 ]]; then
  # The literature-ready force grid (Guo & Guilford span). Deliberately NOT the smoke default:
  # this is a long CPU production run and should be launched knowingly.
  OUT="RUN_LOGS/motor_validation/sm4_force_lifetime_grid"
  ARGS+=(-states rigor,adp
         -forces 0,1,2,3,4,5,6,8,10,15,20,25
         -dirs opposing,assisting
         -events 200 -maxdwell 40
         -runclass "PRODUCTION GRID (CPU) - literature-ready force ladder")
  echo "=== SM4 PRODUCTION GRID: 2 states x 12 forces x 2 dirs, 200 events/cell ==="
  echo "    This is a LONG CPU run. Output: $OUT"
fi

mkdir -p "$OUT"
set -x
nice -n 10 java --enable-preview -Xmx4G -cp "$CP" \
     softbox.Sm4ForceLifetimeHarness "${ARGS[@]}" -outdir "$OUT" -rev "$REV"
rc=$?
set +x
if [[ $rc -eq 0 ]] && ls "$OUT"/sm4_batch_*.json >/dev/null 2>&1; then
  python3 scripts/sm4_analysis.py "$OUT"
fi
exit $rc
