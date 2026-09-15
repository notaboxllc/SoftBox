#!/usr/bin/env bash
# Does the TWO-POINT negative survive on a torsionally COHERENT filament?
# All prior two-point work (docs/twirl/TWOPOINT_ATTACHMENT_SCOPE.md §3-§7) ran on the 12-segment chain, which
# has NO inter-segment torsional coupling. The two-point bond's distinguishing feature is that it delivers a
# COUPLE into the filament (reactions at two material points 5.5 nm apart) -- and on a floppy chain that couple
# just spins the bound segment, rotating its material frame and hence the site azimuths that set the direction
# of the next force. That is a candidate cause of the measured de-rectification (forces unchanged, net ~ 0,
# seed-dependent sign). -filsegs 1 removes per-segment roll entirely.
set -u
OUT=${OUT:-RUN_LOGS/motor_audit/campaigns_2026-09/TWOPOINT_RIGID}
mkdir -p "$OUT"
BASE="-run -gpu -devicecull -nohires -noviz -density 500 -matx 10.0 -maty 2.0 -filx 2.5 -eta 0.10 -dt 1.25e-6 -steps 200000 -target 9.0"
run(){ rm -rf "$OUT/$1"; ./scripts/run_gpu_monitored.sh ./scripts/run_site_normal_glide_gpu.sh $BASE -seed $3 $2 -out "$OUT/$1" > "$OUT/$1.log" 2>&1; }
for S in 20260901 20260902 20260903 20260904; do
  run "SPr_$S" "-filsegs 1"                                              $S &
  run "TPr_$S" "-filsegs 1 -twopoint -twopoint-foot 0 -twopoint-ancw 0.5" $S &
  wait
done
echo "=== TWO-POINT on a RIGID (torsionally coherent) filament ==="
python3 - "$OUT" <<'PY'
import csv,os,sys,statistics as st
O=sys.argv[1]; S=["20260901","20260902","20260903","20260904"]
def v(n):
    f=os.path.join(O,n,"trajectory_summary.csv")
    if not os.path.exists(f): return None
    r=list(csv.DictReader(open(f),delimiter='\t'))
    return (float(r[-1]["vfit_um_s"]), float(r[-1]["avgBound"])) if r else None
print(f"{'seed':<10}{'single-pt':>11}{'two-point':>11}{'avgB SP':>9}{'avgB TP':>9}")
a=[];b=[]
for s in S:
    p,q=v("SPr_"+s),v("TPr_"+s)
    if not p or not q: print(f"{s:<10} incomplete"); continue
    a.append(p[0]); b.append(q[0])
    print(f"{s:<10}{p[0]:11.4f}{q[0]:11.4f}{p[1]:9.2f}{q[1]:9.2f}")
if len(a)>1:
    print(f"\nSP  mean {st.mean(a):+.4f} +/- {st.stdev(a)/len(a)**.5:.4f}  signs {[1 if x>0 else -1 for x in a]}")
    print(f"TP  mean {st.mean(b):+.4f} +/- {st.stdev(b)/len(b)**.5:.4f}  signs {[1 if x>0 else -1 for x in b]}")
    print(f"TP/SP = {st.mean(b)/st.mean(a):.3f}")
    print("\nFLOPPY-CHAIN REFERENCE (docs §7.2): SP +0.794+/-0.112 [+,+,+,+] ; TP -0.117+/-0.191 [+,-,-,-] ; ratio -0.15")
    print("If TP now tracks SP with consistent sign, the two-point negative was a torsional-decoherence artefact.")
PY
