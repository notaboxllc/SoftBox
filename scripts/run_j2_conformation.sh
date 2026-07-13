#!/usr/bin/env bash
# Reproducible PASSIVE_J2_CONFORMATION study driver. All variants are explicit and default-off.
set -euo pipefail
cd "$(dirname "$0")/.."

mode=${1:-help}
out=RUN_LOGS/j2_conformation
mkdir -p "$out"

run_clamp() {
  local dir=$1 k=$2 v=$3 seed=$4 steps=$5 extra=${6:-}
  mkdir -p "$out/$dir"
  # shellcheck disable=SC2086 # extra is an intentional flag bundle owned by this script
  ./scripts/run_gliding.sh -matbox 50 -density 300 -dt 5e-6 -seed "$seed" -vclamp "$v" \
    -j2torsion "$k" $extra "$steps" > "$out/$dir/k${k}_v${v}_s${seed}.log" 2>&1
}

case "$mode" in
  native)
    mkdir -p "$out/native_clamp"
    for v in 0 8 12 14 16; do
      for seed in 0 1 2 3; do run_clamp native_clamp 0 "$v" "$seed" 16000 -j2audit; done
    done
    ;;
  native_glide)
    # CPU-only detailed free-glide episode census. Unlike the 1 ms GPU density snapshots, this samples every
    # physical timestep and can resolve the ~sub-ms attachment-age and impulse relationships.
    steps=${2:-12000}
    mkdir -p "$out/native_glide"
    for density in 50 500 2000; do
      for seed in 0 1; do
        ./scripts/run_gliding.sh -full -grid -matbox 50 -coltol 10 -density "$density" -seed "$seed" \
          -j2audit "$steps" > "$out/native_glide/d${density}_s${seed}.log" 2>&1
      done
    done
    ;;
  single)
    mkdir -p "$out/single_motor"
    for k in 0 0.01 0.03 0.1 0.3 1 3 10 30 100 300; do
      ./scripts/run_gliding.sh -servoaudit -j2audit -density 1 -dt 1e-5 -seed 1 -j2torsion "$k" 4000 \
        > "$out/single_motor/k${k}_dt1e-5.log" 2>&1
    done
    for k in 0 0.1 3 10 30 100; do
      ./scripts/run_gliding.sh -servoaudit -j2audit -density 1 -dt 5e-6 -seed 1 -j2torsion "$k" 8000 \
        > "$out/single_motor/k${k}_dt5e-6.log" 2>&1
      ./scripts/run_gliding.sh -servoaudit -j2audit -density 1 -dt 2.5e-6 -seed 1 -j2torsion "$k" 16000 \
        > "$out/single_motor/k${k}_dt2.5e-6.log" 2>&1
    done
    ;;
  fv)
    for k in 0 0.1 1 3; do
      for v in 0 8 12 14 16 18; do
        for seed in 0 1 2 3; do run_clamp fv_screen "$k" "$v" "$seed" 16000 -j2audit; done
      done
    done
    # The four-seed crossing was shallow: paired refinement around the baseline/kappa=3 zero.
    for k in 0 3; do
      for v in 12 14 16; do
        for seed in 4 5 6 7; do run_clamp fv_screen "$k" "$v" "$seed" 16000 -j2audit; done
      done
    done
    ;;
  fv_powered)
    # Higher-occupancy eight-seed zero bracket used after the density-300 screen proved shallow.
    mkdir -p "$out/fv_powered"
    for k in 0 3; do
      for v in 10 12 14 16; do
        for seed in 0 1 2 3 4 5 6 7; do
          ./scripts/run_gliding.sh -matbox 50 -density 1000 -dt 5e-6 -seed "$seed" -vclamp "$v" \
            -j2torsion "$k" -j2audit 16000 > "$out/fv_powered/k${k}_v${v}_s${seed}.log" 2>&1
        done
      done
    done
    ;;
  kernel)
    for k in 0 3; do
      for v in 0 8 12 14 16 18; do
        for seed in 0 1 2 3; do run_clamp kernel "$k" "$v" "$seed" 16000 -epkernel; done
      done
    done
    ;;
  density)
    steps=${2:-30000}
    mkdir -p "$out/density"
    # Baseline and the smallest arm traverse the broad grid. The weak-arm d8000 gate was coverage-violated and
    # seed-unstable (18.83 vs 8.62 um/s), so the stronger arm stops at d4000 by the preregistered fidelity rule.
    for k in 0 0.1; do
      for density in 50 100 250 500 1000 2000 4000 8000; do
        for seed in 0 1; do
          ./scripts/run_gliding.sh -gpu -full -grid -matbox 50 -coltol 10 -density "$density" -seed "$seed" \
            -j2torsion "$k" -j2audit "$steps" > "$out/density/k${k}_d${density}_s${seed}.log" 2>&1
        done
      done
    done
    for density in 50 100 250 500 1000 2000 4000; do
      for seed in 0 1; do
        ./scripts/run_gliding.sh -gpu -full -grid -matbox 50 -coltol 10 -density "$density" -seed "$seed" \
          -j2torsion 3 -j2audit "$steps" > "$out/density/k3_d${density}_s${seed}.log" 2>&1
      done
    done
    ;;
  consistency)
    # GlidingHarness steady kinetics warm-up is at least 20,000 steps; stay beyond it so STATS_STEADY_ROW is valid.
    steps=${2:-30000}
    mkdir -p "$out/consistency"
    common=(-full -grid -matbox 50 -coltol 10 -density 500 -seed 7 -j2torsion 3 -j2audit "$steps")
    ./scripts/run_gliding.sh "${common[@]}" > "$out/consistency/cpu.log" 2>&1
    ./scripts/run_gliding.sh -gpu "${common[@]}" > "$out/consistency/gpu.log" 2>&1
    ;;
  *)
    echo "usage: $0 {native|native_glide [steps]|single|fv|fv_powered|kernel|density [steps]|consistency [steps]}" >&2
    exit 2
    ;;
esac
