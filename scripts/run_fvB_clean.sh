#!/bin/bash
# Clean powered Variant-B confirmation (no concurrent runs). Pools seeds; checks K-robustness.
# f̄_bound should be K-independent if K >> episode lifetime ⇒ validates the B measurement.
set -u
STEPS=20000
OUT=RUN_LOGS/2026-07-10_fvB_clean.txt
: > "$OUT"
run() { # v K seed
  scripts/run_gliding.sh -matbox 50 -density 2000 -vclamp "$1" -fvepisode "$2" -seed "$3" "$STEPS" 2>&1 | grep "^FVROW" \
    | sed -E "s/.*fbar_avail=([-0-9.]+) fbar_bound=([-0-9.]+).*/v=$1 K=$2 seed=$3 fbar_bound=\2 fbar_avail=\1/"
}
echo "# clean Variant-B  steps=$STEPS d2000" | tee -a "$OUT"
echo "## pooled: v in {6 12}, K=300, seeds 0..4" | tee -a "$OUT"
for V in 6 12; do for S in 0 1 2 3 4; do run "$V" 300 "$S" | tee -a "$OUT"; done; done
echo "## K-robustness: v=12, seed 0, K in {300 700 1500}" | tee -a "$OUT"
for K in 300 700 1500; do run 12 "$K" 0 | tee -a "$OUT"; done
echo "=== pooled means (K=300) ===" | tee -a "$OUT"
awk '/K=300 /{for(i=1;i<=NF;i++){split($i,a,"=");k[a[1]]=a[2]}; key="v="k["v"]; s[key]+=k["fbar_bound"]; q[key]+=k["fbar_bound"]^2; n[key]++}
END{for(x in s){m=s[x]/n[x];sd=sqrt(q[x]/n[x]-m*m);printf "%-6s fbar_bound=%+.3f ± %.3f (n=%d)\n",x,m,sd,n[x]}}' "$OUT" | sort | tee -a "$OUT"
echo "FVB CLEAN DONE"
