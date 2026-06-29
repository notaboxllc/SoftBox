#!/bin/bash
# Runs AFTER the multi-seed sweep finishes: (1) dt-convergence test (numerical-overshoot vs physical no-glide),
# (2) CPU≡GPU cross-check at one mid density. Waits for the multi-seed "# DONE" marker first.
cd /home/jba/Code/SoftBox
echo "[after] waiting for multi-seed to finish ..." >> .last_run_status
for i in $(seq 1 240); do grep -q "# DONE" PHASE2_SETA_multiseed.txt 2>/dev/null && break; sleep 30; done
echo "[after] multi-seed done; starting dt-test + CPU≡GPU" >> .last_run_status
STK="-matbed -config1 -kon 2e5 -tauavg 0.5 -xcatch 2.5 -density 2000 -grid -gpu -seed 0"
OUT=PHASE2_SETA_dttest.txt
{
echo "# dt-convergence @ density 2000 (matched ~0.3 s sim time): is the no-glide NUMERICAL (overshoot) or PHYSICAL?"
echo "=== dt=1e-5  M=30000 ==="; timeout 900 ./run_gliding.sh $STK -dt 1e-5 30000  2>&1 | grep -E 'GRID_ROW|VIOLATED|NaN=true'
echo "=== dt=5e-6  M=60000 ==="; timeout 1200 ./run_gliding.sh $STK -dt 5e-6 60000  2>&1 | grep -E 'GRID_ROW|VIOLATED|NaN=true'
echo "=== dt=2e-6  M=150000 ==="; timeout 2400 ./run_gliding.sh $STK -dt 2e-6 150000 2>&1 | grep -E 'GRID_ROW|VIOLATED|NaN=true'
} > "$OUT" 2>&1
echo "[after] dt-test done -> $OUT" >> .last_run_status
# CPU≡GPU at density 1000 (6960 motors), M=20000
CG=PHASE2_SETA_cpugpu.txt
STK2="-matbed -config1 -kon 2e5 -tauavg 0.5 -xcatch 2.5 -density 1000 -grid -seed 0"
{
echo "=== CPU≡GPU @ density 1000, M=20000 ==="
echo "--- GPU ---"; timeout 900 ./run_gliding.sh $STK2 -gpu 20000 2>&1 | grep -E 'GRID_ROW|COV_ROW'
echo "--- CPU ---"; timeout 1800 ./run_gliding.sh $STK2 20000      2>&1 | grep -E 'GRID_ROW|COV_ROW'
} > "$CG" 2>&1
echo "[after] CPU≡GPU done -> $CG ; ALL DONE" >> .last_run_status
