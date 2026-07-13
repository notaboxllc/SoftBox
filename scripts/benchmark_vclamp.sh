#!/bin/bash
# Reproducible CPU benchmark for the canonical fine-dt velocity clamp.
#
# Usage:
#   scripts/benchmark_vclamp.sh short [repetitions]
#   scripts/benchmark_vclamp.sh science [repetitions]
#
# Environment overrides: DT, STEPS, SEED, VELOCITY, DENSITY, MATBOX_NM, OUT_DIR.
set -euo pipefail

cd "$(dirname "$0")/.."

case_name=${1:-short}
repetitions=${2:-3}
case "$case_name" in
  short)
    default_dt=2.5e-6
    default_steps=8000
    ;;
  science)
    default_dt=2.5e-6
    default_steps=32000
    ;;
  *)
    echo "usage: $0 {short|science} [repetitions]" >&2
    exit 2
    ;;
esac

dt=${DT:-$default_dt}
steps=${STEPS:-$default_steps}
seed=${SEED:-0}
velocity=${VELOCITY:-14}
density=${DENSITY:-2000}
matbox_nm=${MATBOX_NM:-50}
out_dir=${OUT_DIR:-RUN_LOGS/performance/${case_name}}
if (( steps < 8000 )); then
  echo "STEPS must be at least 8000; GlidingHarness enforces that minimum for -vclamp" >&2
  exit 2
fi
mkdir -p "$out_dir"

summary="$out_dir/summary.tsv"
metadata="$out_dir/environment.txt"
command=(./scripts/run_gliding.sh -matbox "$matbox_nm" -density "$density" -dt "$dt" -seed "$seed" -vclamp "$velocity" "$steps")

{
  echo "case=$case_name"
  echo "commit=$(git rev-parse HEAD)"
  echo "branch=$(git branch --show-current)"
  echo "softbox_tracked_diff_sha256=$(git diff -- softbox | sha256sum | awk '{print $1}')"
  echo "git_status_begin"
  git status --short
  echo "git_status_end"
  echo "timestamp_utc=$(date -u +%FT%TZ)"
  echo "cwd=$(pwd)"
  printf 'command='; printf '%q ' "${command[@]}"; echo
  echo "available_processors=$(nproc)"
  echo "cpu=$(lscpu | sed -n 's/^Model name:[[:space:]]*//p')"
  echo "kernel=$(uname -srmo)"
  java -version 2>&1
  echo "tornado_argfile=$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx/tornado-argfile"
} > "$metadata"

printf 'rep\twall_s\tuser_s\tsystem_s\tmax_rss_kb\tsteps_per_s\tmotors_per_s\tpair_tests_per_s\tfvrow\n' > "$summary"
for rep in $(seq 1 "$repetitions"); do
  log="$out_dir/rep_${rep}.log"
  timing="$out_dir/rep_${rep}.time"
  /usr/bin/time -f '%e\t%U\t%S\t%M' -o "$timing" "${command[@]}" > "$log" 2>&1
  read -r wall user system rss < "$timing"
  fvrow=$(grep '^FVROW ' "$log")
  nmot=$(sed -n 's/.* nMot = \([0-9][0-9]*\) ;.*/\1/p' "$log" | head -1)
  awk -v rep="$rep" -v wall="$wall" -v user="$user" -v sys="$system" -v rss="$rss" \
      -v steps="$steps" -v nmot="$nmot" -v row="$fvrow" \
      'BEGIN {
         sps = steps / wall;
         mps = nmot * sps;
         pairs = nmot * 11 * sps;
         printf "%d\t%.6f\t%.6f\t%.6f\t%d\t%.3f\t%.3f\t%.3f\t%s\n", rep, wall, user, sys, rss, sps, mps, pairs, row;
       }' >> "$summary"
  tail -1 "$summary"
done

echo "wrote $summary"
