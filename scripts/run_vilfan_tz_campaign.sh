#!/bin/bash
# Sequential driver for the Vilfan target-zone deterministic study. CPU only.
# Runs one stage at a time so the stages never contend for CPU and never race on an output file.
set -u
cd "$(dirname "$0")/.."
R=./scripts/run_vilfan_tz_deterministic.sh
BASE="-maty 0.01 -density 6000 -warmup 2000"
STEPS=20000
SEEDS=8

echo "############ 1/5  validation gates (idealized monotone-staircase lattice) ############"
$R -gates $BASE -steps 12000 -seeds 4 -out RUN_LOGS/vilfan_target_zone \
   > RUN_LOGS/vilfan_target_zone/gates.txt 2>&1
echo "gates: $(grep -c '\[PASS\]' RUN_LOGS/vilfan_target_zone/gates.txt) pass, $(grep -c '\[FAIL\]' RUN_LOGS/vilfan_target_zone/gates.txt) fail"

echo "############ 2/5  proof-of-mechanism campaign (idealized lattice) ############"
$R -campaign $BASE -steps $STEPS -seeds $SEEDS -lattice 6 -out RUN_LOGS/vilfan_target_zone \
   > RUN_LOGS/vilfan_target_zone/campaign.txt 2>&1
echo "campaign done"

echo "############ 3/5  prescribed-speed ladder (idealized lattice) ############"
$R -ladder $BASE -steps $STEPS -seeds $SEEDS -lattice 6 -no-events -out RUN_LOGS/vilfan_target_zone \
   > RUN_LOGS/vilfan_target_zone/ladder.txt 2>&1
echo "ladder done"

echo "############ 4/5  FAITHFUL native actin 13/6 lattice, same design ############"
mkdir -p RUN_LOGS/vilfan_target_zone_native
$R -campaign $BASE -steps $STEPS -seeds $SEEDS -lattice 1 -out RUN_LOGS/vilfan_target_zone_native \
   > RUN_LOGS/vilfan_target_zone_native/campaign.txt 2>&1
echo "native done"

echo "############ 5/5  bounded sensitivity grid (zone width x speed) ############"
$R -sens $BASE -steps $STEPS -seeds 4 -lattice 6 -no-events -out RUN_LOGS/vilfan_target_zone \
   > RUN_LOGS/vilfan_target_zone/sensitivity.txt 2>&1
echo "sensitivity done"
echo "ALL STAGES COMPLETE"
