#!/usr/bin/env bash
# DERIVED HANDEDNESS TEST. The triad glides and shows same-sign twirl at eps=0 -- no imposed offset anywhere.
# Is that chirality inherited from ACTIN'S HELIX, or a lab-frame artefact of the Gram-Schmidt head axis?
# -flip-helix mirrors the LATTICE (twistPerMon -166.5 -> +166.5). The usual -mirror acts through the imposed
# epsStroke term and is a NO-OP at eps=0, so it cannot answer this.
#   twirl REVERSES with the lattice  -> chirality is DERIVED from actin's geometry
#   twirl UNCHANGED                  -> lab-frame artefact; the result is void
set -u
OUT=${OUT:-RUN_LOGS/motor_audit/campaigns_2026-09/TRIAD_CHIRALITY}
mkdir -p "$OUT"
BASE="-run -gpu -devicecull -nohires -noviz -density 500 -matx 10.0 -maty 2.0 -filx 2.5 -eta 0.10 -dt 1.25e-6 -steps 200000 -target 9.0 -filsegs 1 -triad"
run(){ rm -rf "$OUT/$1"; ./scripts/run_gpu_monitored.sh ./scripts/run_site_normal_glide_gpu.sh $BASE -seed $3 $2 -out "$OUT/$1" > "$OUT/$1.log" 2>&1; }
for S in 20260901 20260902 20260903 20260904; do
  run "NAT_$S" ""             $S &
  run "FLP_$S" "-flip-helix"  $S & wait
done
echo "=== IS THE eps=0 TWIRL DERIVED FROM ACTIN'S HELIX? ==="
python3 - "$OUT" <<'PY'
import csv,os,sys,statistics as st
O=sys.argv[1]; S=["20260901","20260902","20260903","20260904"]
def t(n):
    f=os.path.join(O,n,"trajectory_summary.csv")
    if not os.path.exists(f): return None
    r=list(csv.DictReader(open(f),delimiter='\t'))
    if not r: return None
    x=r[-1]; d=abs(float(x["fwd_um"]))
    return (float(x["rollTurns"])/d, float(x["vfit_um_s"])) if d>0.02 else None
print(f"{'seed':<10}{'native turns/um':>17}{'MIRRORED lattice':>19}{'v nat':>8}{'v flp':>8}")
a=[];b=[]
for s in S:
    p,q=t("NAT_"+s),t("FLP_"+s)
    if not p or not q: print(f"{s:<10} incomplete"); continue
    a.append(p[0]); b.append(q[0])
    print(f"{s:<10}{p[0]:17.3f}{q[0]:19.3f}{p[1]:8.3f}{q[1]:8.3f}")
if len(a)>1:
    ma,mb=st.mean(a),st.mean(b)
    print(f"\nnative   {ma:+.3f} +/- {st.stdev(a)/len(a)**.5:.3f}  signs {[1 if x>0 else -1 for x in a]}")
    print(f"mirrored {mb:+.3f} +/- {st.stdev(b)/len(b)**.5:.3f}  signs {[1 if x>0 else -1 for x in b]}")
    print(f"\nantisymmetry (nat-flp)/2 = {(ma-mb)/2:+.3f}   residue (nat+flp)/2 = {(ma+mb)/2:+.3f}")
    print("  SIGN REVERSAL => chirality DERIVED from actin's helix (no imposed eps anywhere).")
    print("  same sign     => lab-frame artefact; void.")
PY
