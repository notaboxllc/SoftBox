#!/usr/bin/env bash
# FIRST TEST of the HEAD-SIDE conformational origin of the stroke skew.
# -f8tan <nm> shifts the head's actin-binding point TANGENTIALLY at the stroke. Zero-rest spring =>
# force-identical to epsStroke, but stated as a conformational change of the MYOSIN HEAD.
# eps=1.5 deg at the 3.5 nm actin radius == 0.092 nm == ~0.9 Angstrom.
# Rigid single-segment filament so nothing uncalibrated (chain coeffs, torsion) can contaminate it.
# PREDICTION: 0.092 nm should reproduce the epsStroke ladder near eps=1.5 => eps-odd ~ -2.0 turns/um.
set -u
OUT=${OUT:-RUN_LOGS/motor_audit/campaigns_2026-09/F8TAN_FIRST}
mkdir -p "$OUT"
BASE="-run -gpu -devicecull -nohires -noviz -density 2000 -matx 14.0 -maty 2.0 -filx 3.5 -eta 0.10 -dt 1.25e-6 -steps 4000000 -target 0.6 -seed 20260901 -randbase -randbase-seed 20260901 -filsegs 1"
run(){ rm -rf "$OUT/$1"; ./scripts/run_gpu_monitored.sh ./scripts/run_site_normal_glide_gpu.sh $BASE $2 -out "$OUT/$1" > "$OUT/$1.log" 2>&1; }
# gate: 0 nm must be byte-identical to no flag at all
run "gate_off"  ""              & run "gate_zero" "-f8tan 0"      & wait
# 1 Angstrom-ish, paired mirror
run "d092_pos"  "-f8tan 0.092"  & run "d092_neg" "-f8tan 0.092 -mirror" & wait
# a second amplitude for linearity
run "d184_pos"  "-f8tan 0.184"  & run "d184_neg" "-f8tan 0.184 -mirror" & wait
echo "=== HEAD-SIDE F8 TANGENTIAL SHIFT (rigid filament) ==="
python3 - "$OUT" <<'PY'
import csv,os,sys,math
O=sys.argv[1]
def g(n):
    f=os.path.join(O,n,"trajectory_summary.csv")
    if not os.path.exists(f): return None
    r=list(csv.DictReader(open(f),delimiter='\t'))
    if not r: return None
    x=r[-1]; d=abs(float(x["fwd_um"]))
    return (d, float(x["rollTurns"])/d if d>0 else float('nan'), float(x["vfit_um_s"]), float(x["avgBound"])) if d>0 else None
a,b=g("gate_off"),g("gate_zero")
print("GATE  -f8tan 0 vs no flag:", "MATCH" if (a and b and abs(a[1]-b[1])<1e-9) else f"differ {a} {b}")
print(f"\n{'delta_nm':>9}{'eps_equiv':>11}{'pos t/um':>10}{'neg t/um':>10}{'ODD':>9}{'pitch_um':>10}{'v':>7}{'avgB':>7}")
for tag,dnm in (("d092",0.092),("d184",0.184)):
    p,n=g(tag+"_pos"),g(tag+"_neg")
    if not p or not n: print(f"{dnm:9.3f}   incomplete"); continue
    odd=(p[1]-n[1])/2
    eps=math.degrees(dnm*1e-3/3.5e-3)
    print(f"{dnm:9.3f}{eps:11.3f}{p[1]:10.3f}{n[1]:10.3f}{odd:9.3f}{(1/abs(odd) if odd else float('nan')):10.3f}{p[2]:7.3f}{p[3]:7.2f}")
print("\nepsStroke ladder for comparison (rigid, same scene): eps=1 -> -1.321 ; eps=2 -> -2.818 turns/um")
print("If d=0.092nm (eps_equiv 1.5) lands near -2.0, the head-side conformational version reproduces epsStroke.")
PY
