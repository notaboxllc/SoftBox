#!/bin/bash
# ETA PILOT — d500, canonical stack (two-strand + straight-rest), MATCHED 50 ms physical.
# Resumable: any run whose summary.txt exists is skipped.
cd /home/jba/Code/SoftBox
OUT=/home/jba/Code/SoftBox/RUN_LOGS/motor_audit/campaigns_2026-08/ETA_PILOT
mkdir -p "$OUT"
R=$OUT/REPORT.txt; TSV=$OUT/points.tsv; VER=$OUT/verdict.py
say(){ echo "[$(date '+%m-%d %H:%M')] $*" | tee -a $R; }
[ -f "$TSV" ] || printf "eta\tseed\tvelocity\tavgBound\tcaptures\tP0bound\trollTurns\tomega\tturns_per_um\tex12pN\tinvalid\n" > "$TSV"

# --- GPU health guard: abort the campaign rather than burn 10 runs against a dead device ---
gpu_ok(){ nvidia-smi -L >/dev/null 2>&1; }
gpu_ok || { say "ABORT: nvidia-smi cannot see the GPU at launch."; exit 1; }

say "ETA PILOT (resumed post-reboot) — d500, canonical stack, MATCHED 50 ms physical"
say "  eta 0.10 : dt 1.25e-6 x  40000 steps      eta 0.01 : dt 1.25e-7 x 400000 steps"
say "  dt is SCALED with eta (mandatory: the Class-II fracMove laws are gamma-independent at fixed dt)"
for CFG in "0.10 1.25e-6 40000" "0.01 1.25e-7 400000"; do
  set -- $CFG; E=$1; DT=$2; NS=$3
  for S in 20260901 20260902 20260903 20260904 20260905 20260906; do
    tag=e${E}_s${S}
    [ -f $OUT/$tag/summary.txt ] && { say "  $tag done, skip"; continue; }
    gpu_ok || { say "ABORT at $tag: GPU vanished from the bus (nvidia-smi -L failed)."; exit 2; }
    say "  $tag (eta=$E dt=$DT steps=$NS) ..."
    ./scripts/run_gpu_monitored.sh ./scripts/run_site_normal_glide_gpu.sh -run -gpu -devicecull -nohires -noviz \
       -density 500 -seed $S -filx 2.30 -eta $E -dt $DT -steps $NS -out $OUT/$tag > $OUT/$tag.log 2>&1
    f=$OUT/$tag/summary.txt
    if [ -f "$f" ]; then
      python3 - "$f" "$E" "$S" "$TSV" <<'PY'
import re,sys
s=open(sys.argv[1]).read()
g=lambda p,d="nan": (re.search(p,s).group(1) if re.search(p,s) else d)
open(sys.argv[4],'a').write("\t".join([sys.argv[2],sys.argv[3],
  g(r'full-run LS velocity = \+?(-?[\d.]+)'), g(r'avgBound = ([\d.]+)'), g(r'captures = (\d+)'),
  g(r'P\(0 bound\)=([\d.]+)'), g(r'\(([-+][\d.]+) turns\)'), g(r'Omega = ([-+][\d.]+)'),
  g(r'turns/um = ([-+][\d.nae]+)'), g(r'>12pN = (\d+)'), g(r'invalid = (\d+)')])+"\n")
PY
      say "$(tail -1 $TSV | awk -F'\t' '{printf "    v=%s aB=%s turns=%s om=%s inv=%s",$3,$4,$7,$8,$11}')"
    else
      say "    $tag FAILED — see $OUT/$tag.log"
      gpu_ok || { say "ABORT: GPU is gone; stopping so the remaining runs are not wasted."; exit 2; }
    fi
  done
done

say ""; say "================ ETA PILOT VERDICT ================"
cat > "$VER" <<'PY'
import sys,statistics as st
rows=[l.split('\t') for l in open(sys.argv[1]).read().splitlines()[1:] if l.strip()]
by={}
for r in rows: by.setdefault(r[0],[]).append(r)
sem=lambda x:(st.stdev(x)/len(x)**.5) if len(x)>1 else 0.0
res={}
for e in sorted(by,key=float,reverse=True):
    rs=by[e]; f=lambda i:[float(r[i]) for r in rs if r[i] not in('nan','')]
    v,a,o=f(2),f(3),f(7); res[e]=(st.mean(v),st.mean(a),st.mean(o),sem(o),len(o),sum(1 for x in o if x>0))
    print(f"eta {e}  n={len(v)}  v={st.mean(v):.3f}+-{sem(v):.3f}  avgBound={st.mean(a):.3f}+-{sem(a):.3f}  "
          f"Omega={st.mean(o):+.2f}+-{sem(o):.2f} rad/s  [{res[e][5]}/{len(o)} positive]")
if len(res)==2:
    hi,lo=sorted(res,key=float,reverse=True)
    print()
    print(f"  avgBound  {res[lo][1]/res[hi][1]:.2f}x at eta {lo} vs {hi}   (old motor gave 2.26x / +126%)")
    print(f"  velocity  {res[lo][0]/res[hi][0]:.2f}x")
    ohi,olo=abs(res[hi][2]),abs(res[lo][2])
    print(f"  |Omega|   {olo/ohi:.2f}x" if ohi>1e-9 else "  |Omega|   n/a")
    print(f"  Omega significance: eta {hi} = {abs(res[hi][2])/res[hi][3] if res[hi][3]>0 else 0:.2f} sigma, "
          f"eta {lo} = {abs(res[lo][2])/res[lo][3] if res[lo][3]>0 else 0:.2f} sigma")
    print()
    print("  DECISION: commit to the low-eta mirror campaign only if eta 0.01 shows a")
    print("            STRONGER and MORE COHERENT Omega. If avgBound rises a lot, note that")
    print("            the low-eta arm probes a DIFFERENT bound number - plot vs avgBound, not density.")
PY
python3 "$VER" "$TSV" 2>&1 | tee -a $R
say "DONE."
