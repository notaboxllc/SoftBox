#!/bin/bash
# CROSSBRIDGE_DASHPOT (MEASUREMENT-ONLY, additive flag -xbdash, default byte-identical Hookean).
# A parallel Kelvin-Voigt dashpot on F8: F8 = k·stretch + γ_xb·d(stretch)/dt. Discriminates on stretch
# VELOCITY (history-aware), raising the stretch-mode effective drag γ_eff = γ_head+γ_xb ⇒ r=k·dt/γ_eff.
#   STAGE 1: at the calibrated dt=1e-5, is the damping BENIGN or DETUNING? (gliding NET + avgBound)
#   STAGE 2: does γ_xb extend the faithful dt toward 1e-4? (gliding NET + avgBound + stability vs Hookean)
# γ_xb = <mult>·γ_head. -xbdashmech: dashpot mechanical force only (catch reads spring load) — isolates the
# overshoot-suppression from the explicit dashpot's thermal-velocity catch-detonation artifact.
# Scene = v1 gliding 4×1 box (2000 heads), -grid NET velocity. v2-GPU device-resident (23+1 kernels).
#   ./run_xbdash.sh stage1 | stage2 | all
cd /home/jba/Code/SoftBox
S1=RUN_LOGS/2026-06-26_xbdash_stage1.txt
S2=RUN_LOGS/2026-06-26_xbdash_stage2.txt
GREP="GRID_ROW|DASHPOT ON|NON-FINITE|NaN=true|Exception|unimplemented"
status() { echo "[$(date +%H:%M)] xbdash $1" > .last_run_status; }

g_run() {  # dt mult mechflag seed   (mult=0 ⇒ Hookean)
  local dt="$1" mult="$2" mech="$3" s="$4" SAT=""
  [ "$mult" != "0" ] && SAT="-xbdash $mult $mech"
  ./run_gliding.sh -gpu -v1box -grid -dt "$dt" $SAT -seed "$s" 5000 2>&1 \
    | grep -E "$GREP" | sed "s/^/[dt=$dt g=$mult$mech s=$s] /"
}

run_stage1() {   # γ_xb sweep at the calibrated dt=1e-5
  : > "$S1"; echo "XBDASH STAGE 1 — γ_xb sweep @ dt=1e-5 (benign or detuning?) — Hookean ref: NET~3.8 avgB~6.9 — $(date)" | tee -a "$S1"
  for cfg in "0 ''" "0.1 ''" "0.25 ''" "0.5 ''" "1 ''" "2 ''" "4 ''" "0.25 -xbdashmech" "1 -xbdashmech" "4 -xbdashmech"; do
    set -- $cfg; mult=$1; mech=${2//\'/}
    for s in 1 2; do status "STAGE1 dt=1e-5 g=$mult$mech s=$s"
      echo "=== STAGE1 dt=1e-5 γ_xb=$mult mech=$mech seed=$s ($(date +%H:%M)) ===" | tee -a "$S1"
      g_run 1e-5 "$mult" "$mech" "$s" | tee -a "$S1"
    done
  done
  status "DONE stage1"; echo "stage1 done $(date)" | tee -a "$S1"
}

s2_one() {  # dt mult mech
  for s in 1 2; do status "STAGE2 dt=$1 g=$2$3 s=$s"
    echo "=== STAGE2 dt=$1 γ_xb=$2 mech=$3 seed=$s ($(date +%H:%M)) ===" | tee -a "$S2"
    g_run "$1" "$2" "$3" "$s" | tee -a "$S2"
  done
}
run_stage2() {   # dt-extension: at each coarse dt, Hookean (γ=0) vs the γ_xb bringing r_eff<1 (literal + mech)
  : > "$S2"; echo "XBDASH STAGE 2 — dt extension toward 1e-4 (does γ_xb keep the glide stable+faithful?) — $(date)" | tee -a "$S2"
  # r_hookean=53000·dt: 2e-5→1.06, 5e-5→2.65, 1e-4→5.30. γ_xb chosen so γ_eff brings r_eff≈0.5–0.9.
  s2_one 2e-5 0 "";  s2_one 2e-5 1 "";  s2_one 2e-5 1 -xbdashmech
  s2_one 5e-5 0 "";  s2_one 5e-5 4 "";  s2_one 5e-5 4 -xbdashmech
  s2_one 1e-4 0 "";  s2_one 1e-4 8 "";  s2_one 1e-4 8 -xbdashmech
  status "DONE stage2"; echo "stage2 done $(date)" | tee -a "$S2"
}

case "${1:-all}" in
  stage1) run_stage1 ;;
  stage2) run_stage2 ;;
  all)    run_stage1; run_stage2 ;;
  *) echo "usage: $0 [stage1|stage2|all]"; exit 1 ;;
esac
status "DONE"; echo "xbdash done $(date)"
