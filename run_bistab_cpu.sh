#!/bin/bash
# BISTABILITY_ORIGIN CPU (basin-stable arbiter). -full 15k, dt=1e-5.
#  PART A: force CPU swing k = the GPU value (0x3ecccccd = 0.4f, probe-confirmed bit-identical to raw) ⇒ HIGH?
#  SWING-K SWEEP (seed 0): lower k ⇒ does a distinct LOW basin appear, or smooth decline? (is LOW CPU-reachable)
#  PART B1: natural occupancy — seeds 1,2 at exact 0.4 ⇒ do any land LOW without a nudge?
cd /home/jba/Code/SoftBox
LOG=RUN_LOGS/2026-07-08_bistab_cpu.txt
BASE="-full -grid -lymntaylor -adppibind -xbimplicit2 -coltol 10 -density 1000 -dt 1e-5"
N=15000
: > "$LOG"
echo "BISTABILITY_ORIGIN CPU (basin-stable) -full ${N}  $(date)" | tee -a "$LOG"
run() { # label seed bits
  echo "[$(date +%H:%M:%S)] CPU $1" >> .last_run_status
  echo "== $1  seed=$2 swingk_bits=$3 ==" >> "$LOG"
  ./run_gliding.sh $BASE -seed $2 -swingkbits $3 $N 2>&1 | grep -E "GRID_ROW|STATS_STEADY_ROW" >> "$LOG"
}
# PART A: CPU forced to the GPU swing coefficient (= raw = 0.4f); expect HIGH
run "A_GPUcoeff_k0.40" 0 0x3ecccccd
# SWING-K SWEEP seed 0 (is a LOW basin reachable by lowering the stroke coeff?)
run "SWEEP_k0.38" 0 0x3ec28f5c
run "SWEEP_k0.30" 0 0x3e99999a
run "SWEEP_k0.20" 0 0x3e4ccccd
run "SWEEP_k0.10" 0 0x3dcccccd
# PART B1: natural occupancy at exact 0.4, other seeds
run "B1_seed1_k0.40" 1 0x3ecccccd
run "B1_seed2_k0.40" 2 0x3ecccccd
echo "[$(date +%H:%M:%S)] CPU bistab DONE" | tee -a .last_run_status >> "$LOG"
