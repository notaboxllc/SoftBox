#!/bin/bash
# TIMESTEP_SERVO_AUDIT Part 6 — V0 dt-ladder. Stochastic velocity-clamp (motors bind via thermal search),
# f̄_available(v) zero-crossing = V0. Step count scales ∝1/dt to hold physical window (~80 ms) constant.
cd "$(dirname "$0")/.."
OUT=RUN_LOGS/v0_ladder.txt
: > "$OUT"
# anchor dt=1e-5: fuller curve; finer dt: bracket around the ~16 crossing
run() { # dt  M  v  seed
  ./scripts/run_gliding.sh -matbox 50 -density 300 -dt "$1" -seed "$4" -vclamp "$3" "$2" 2>&1 \
    | grep -E 'FVROW' | sed "s/^/DT=$1 SEED=$4 /" >> "$OUT"
}
for seed in 0 1 2 3; do
  for v in 0 8 12 14 16 18 20; do run 1e-5 8000 $v $seed; done
done
for dt in 5e-6 2.5e-6 1.25e-6; do
  M=$(python3 -c "print(int(round(8000*1e-5/$dt)))")
  for seed in 0 1 2; do
    for v in 12 14 16 18 20; do run $dt $M $v $seed; done
  done
done
echo "V0 LADDER DONE" >> "$OUT"
