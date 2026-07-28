#!/bin/bash
# Sequential driver for the Vilfan graded-binding study. CPU only, one stage at a time.
set -u
cd "$(dirname "$0")/.."
R=./scripts/run_vilfan_graded.sh
BASE="-steps 16000 -warmup 3000 -seeds 8"
D=RUN_LOGS/vilfan_graded_binding
mkdir -p $D
echo "###### 1/3  STAGE 4  binding-only first-passage (roll CLAMPED) ######"
$R -binding $BASE -out $D > $D/binding.txt 2>&1; echo "binding done"
echo "###### 2/3  STAGE 5  dynamic torque closure (roll FREE) ######"
$R -dynamic $BASE -out $D > $D/dynamic.txt 2>&1; echo "dynamic done"
echo "###### 3/3  STAGE 6  bounded speed ladder ######"
$R -ladder $BASE -out $D > $D/ladder.txt 2>&1; echo "ladder done"
echo "ALL GRADED STAGES COMPLETE"
