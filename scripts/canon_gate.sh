#!/bin/bash
# CANON GATE (docs/MODEL_CANON.md): the canonical model must reproduce its GOLDEN trajectory bit-for-bit -- the first
# 10000 steps of lawn 02 through scripts/canon_glide.sh (GPU, this machine + toolchain; counter-based RNG, so a
# re-run is bit-identical unless the physics, a kernel, or the toolchain changed).
#   scripts/canon_gate.sh            compare against fixtures/canon/<version>_lawn02_10k.golden
#   scripts/canon_gate.sh -record    record the golden; ONLY after a deliberate model change that bumped
#                                    ModelCanon.VERSION and was journaled as a jba decision
# Run it after ANY edit to softbox/ (or a TornadoVM/driver update) and before launching a campaign on the result.
cd /home/jba/Code/SoftBox
ver=$(grep -oP 'static final String VERSION = "\K[^"]+' softbox/ModelCanon.java)
gold=fixtures/canon/${ver}_lawn02_10k.golden
out=RUN_LOGS/canon_gate/$(date +%Y%m%dT%H%M%S); mkdir -p $out
./scripts/canon_glide.sh 02 $out -steps 10000 > $out/run.log 2>&1 || { echo "canon gate: run FAILED (see $out/run.log)"; exit 2; }
grep -q "deviations: none" $out/run.log || { echo "canon gate: manifest shows deviations (see $out/run.log)"; exit 2; }
grep -E "^  step|twist:|steric:|MODEL MANIFEST|MANIFEST KERNEL" $out/run.log | sed 's/wall=[0-9]* s//; s/[0-9]* ms\/step//' > $out/trace.txt
if [ "$1" = "-record" ]; then cp $out/trace.txt $gold; echo "canon gate: golden RECORDED for canon $ver -> $gold ($(grep -c '^  step' $gold) step lines)"; exit 0; fi
[ -f $gold ] || { echo "canon gate: no golden for canon $ver ($gold); record one with -record"; exit 2; }
if diff $gold $out/trace.txt > $out/trace.diff; then echo "canon gate: PASS (canon $ver, bit-identical to golden, $(grep -c '^  step' $gold) step lines)";
else echo "canon gate: FAIL -- trajectory differs from the canon $ver golden (see $out/trace.diff)"; exit 1; fi
