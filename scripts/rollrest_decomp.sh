#!/usr/bin/env bash
# CPU 4-way: is the coupled chain's 3x-over-rigid twirl due to torsional MECHANICS or to the helical REST
# STATE the roll spring imposes on the segment frames (which sets where binding sites sit)?
#   A rigid          : 1 segment, no joints            -> the touchstone
#   B chain, no roll : 12 seg, torsionally free        -> all prior work
#   C chain + roll   : 12 seg, coupled, helical rest   -> the suspect
#   D chain + roll0  : 12 seg, coupled, rest = 0       -> coupling WITHOUT the helical rest state
# C vs D isolates geometry from mechanics. rho=500 keeps it CPU-tractable; the comparison is what matters.
set -u
OUT=${OUT:-RUN_LOGS/motor_audit/campaigns_2026-09/ROLLREST_DECOMP}
TARGET=${TARGET:-0.3}; SEED=${SEED:-20260901}
mkdir -p "$OUT"
BASE="-run -devicecull -nohires -noviz -density 500 -matx 14.0 -maty 2.0 -filx 3.5 -eta 0.10 -dt 1.25e-6 -steps 2000000 -target $TARGET -seed $SEED -stroke-skew 4 -randbase -randbase-seed $SEED -workers 1"
run(){ rm -rf "$OUT/$1"; ./scripts/run_site_normal_glide_gpu.sh $BASE $2 -out "$OUT/$1" > "$OUT/$1.log" 2>&1; }
run A_rigid       "-filsegs 1"                  &
run B_chain_noroll ""                           &
run C_chain_roll  "-rollspring"                 &
run D_chain_roll0 "-rollspring -rollrest 0"     &
wait
echo "=== ROLL-REST DECOMPOSITION (CPU, rho=500, eps=4, target ${TARGET}um) ==="
python3 - "$OUT" <<'PY'
import csv,os,sys
O=sys.argv[1]
print(f"{'arm':<18}{'fwd_um':>9}{'roll':>9}{'turns/um':>10}{'v':>7}{'avgB':>7}{'inv':>5}")
for n,l in (("A_rigid","rigid 1-seg"),("B_chain_noroll","chain, no roll"),
            ("C_chain_roll","chain+roll, helix rest"),("D_chain_roll0","chain+roll, rest=0")):
    f=os.path.join(O,n,"trajectory_summary.csv")
    if not os.path.exists(f): print(f"{l:<18}  none"); continue
    r=list(csv.DictReader(open(f),delimiter='\t'))
    if not r: print(f"{l:<18}  empty"); continue
    x=r[-1]; d=abs(float(x["fwd_um"])); ro=float(x["rollTurns"])
    print(f"{l:<18}{d:9.4f}{ro:9.3f}{(ro/d if d>0 else 0):10.3f}{float(x['vfit_um_s']):7.3f}{float(x['avgBound']):7.2f}{int(float(x['invalid'])):5d}")
print("\nC vs D: if D falls toward A, the gap is the helical REST STATE (binding geometry).")
print("        if D stays with C, the gap is torsional MECHANICS.")
PY
