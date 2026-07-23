#!/bin/bash
# ============================================================================================
# L60 HMM-DIMER structural-sensitivity sweep — CPU object solver (explicit-hmm-dimer-l60, Ms=5).
# REDUCED representative grid (the GPU forked-dimer kernel is Ms=3-specialized ⇒ L60 dimer is CPU-only;
# a full 40k-step grid is infeasible on CPU). Paired L40 (Ms=3) controls at the SAME reduced config so the
# L-effect is a matched pair (the frozen 40k GPU L40 baseline is the separate cross-reference).
# Canonical everything else: rigor mode 1, branchEA 0.03, dt 2.5e-6, saturating ATP, dimers/µm² density.
#   ./scripts/run_l60_dimer_cpu_sweep.sh                  # {150,400,700,1500} × {101,102} × {L40,L60}, 10000 steps
# ============================================================================================
cd "$(dirname "$0")/.." || exit 2
TH="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"; TDIR="$TH/share/java/tornado"
STEPS=10000; OUT="RUN_LOGS/l60_sensitivity/dimer_cpu"; DENS="150 400 700 1500"; SEEDS="101 102"; LS="40 60"; CELL_TIMEOUT=7200
for ((i=1;i<=$#;i++)); do case "${!i}" in
  -steps)  j=$((i+1)); STEPS="${!j}";;
  -dens)   j=$((i+1)); DENS="${!j}";;
  -seeds)  j=$((i+1)); SEEDS="${!j}";;
  -ls)     j=$((i+1)); LS="${!j}";;
  -outdir) j=$((i+1)); OUT="${!j}";;
esac; done
mkdir -p "$OUT/logs"; REV="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
CSV="$OUT/dimer_l60_cells.csv"
[[ -f "$CSV" ]] || echo "L,density,seed,steps,nDim,heads,velProd,meanBound,twoFrac,continuity,activeDim,netF_pN,Fhead,maxGap_nm,peakBrF_pN,rigorRup,invalid,solverFail,wall_s,rev" > "$CSV"
echo "=== L60 DIMER CPU REDUCED SWEEP: L∈{$LS} × ρ∈{$DENS} × seeds∈{$SEEDS}, $STEPS steps, rev=$REV → $OUT ==="
for L in $LS; do for d in $DENS; do for s in $SEEDS; do
  base="l${L}_d${d}_s${s}"
  [[ -f "$OUT/$base.done" ]] && { echo "  [skip done L$L ρ$d s$s]"; continue; }
  log="$OUT/logs/${base}.log"; echo "  [run L$L ρ$d s$s $STEPS steps | $(date -Is)]"
  t0=$(date +%s)
  timeout $CELL_TIMEOUT java @$TH/tornado-argfile --enable-preview -Xmx8G -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
      softbox.ExplicitHmmDimerGlidingHarness -matsmoke -mdensity "$d" -seed "$s" -steps "$STEPS" -L "$L" \
      > "$log" 2>&1
  rc=$?; wall=$(( $(date +%s) - t0 ))
  row=$(grep '^MATROW,' "$log" | head -1)
  if [[ -n "$row" ]]; then
    # MATROW,dmode,density,nDim,heads,seed,velRaw,velProd,meanBound,cont,activeDim,twoFrac,netF,Fhead,fwd,bwd,revHz,fracFwdT,maxGap,invalid,sf,branchEA,branchEI,forkK,gapP999,gapP99,gapP50,exc4,...,peakBrF(f31),...
    IFS=',' read -ra F <<< "$row"
    rr=$(grep '^RUPROW,' "$log" | head -1 | awk -F, '{print $9}'); rr=${rr:-0}
    echo "$L,$d,$s,$STEPS,${F[3]},${F[4]},${F[7]},${F[8]},${F[11]},${F[9]},${F[10]},${F[12]},${F[13]},${F[18]},${F[31]},$rr,${F[19]},${F[20]},$wall,$REV" >> "$CSV"
    echo "    velProd=${F[7]} meanBound=${F[8]} twoFrac=${F[11]} inv=${F[19]} sf=${F[20]} wall=${wall}s" ; touch "$OUT/$base.done"
  else echo "    *** no MATROW (rc=$rc, wall=${wall}s) — see $log ***"; fi
done; done; done
echo "=== L60 DIMER CPU SWEEP DONE → $CSV ==="; column -s, -t "$CSV" 2>/dev/null | head -40
