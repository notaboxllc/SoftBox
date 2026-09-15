#!/bin/bash
# ETA PILOT (CPU runner) — d500, canonical stack, MATCHED 50 ms physical.
# GPU is out of service: Xid 79 "fallen off the bus", reproduced 2026-08-20 20:33 and 2026-08-21 11:33.
# Runs are independent -> parallelise ACROSS runs (thread scaling within a run is poor: 8x workers = 1.67x).
# Uniform -workers 2 for every run so worker count is never a confound.
cd /home/jba/Code/SoftBox
OUT=/home/jba/Code/SoftBox/RUN_LOGS/motor_audit/campaigns_2026-08/ETA_PILOT
mkdir -p "$OUT"
R=$OUT/REPORT_CPU.txt; TSV=$OUT/points.tsv; W=2
say(){ echo "[$(date '+%m-%d %H:%M')] $*" | tee -a $R; }
[ -f "$TSV" ] || printf "eta\tseed\tvelocity\tavgBound\tcaptures\tP0bound\trollTurns\tomega\tturns_per_um\tex12pN\tinvalid\n" > "$TSV"

one_run(){   # eta dt steps seed
  local E=$1 DT=$2 NS=$3 S=$4 tag=e${1}_s${4}
  [ -f $OUT/$tag/summary.txt ] && return 0
  ./scripts/run_site_normal_long_glide.sh -run -nohires -noviz \
     -density 500 -seed $S -filx 2.30 -eta $E -dt $DT -steps $NS \
     -workers $W -out $OUT/$tag > $OUT/$tag.log 2>&1
}
harvest(){   # eta seed
  local E=$1 S=$2 f=$OUT/e${1}_s${2}/summary.txt
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
    say "    e${E}_s${S}: $(tail -1 $TSV | awk -F'\t' '{printf "v=%s aB=%s turns=%s om=%s inv=%s",$3,$4,$7,$8,$11}')"
  else say "    e${E}_s${S} FAILED — see $OUT/e${E}_s${S}.log"; fi
}

SEEDS="20260901 20260902 20260903 20260904 20260905 20260906"
say "ETA PILOT on the CPU RUNNER — d500, canonical stack, MATCHED 50 ms physical, workers=$W"
say "  eta 0.10 : dt 1.25e-6 x  40000 steps      eta 0.01 : dt 1.25e-7 x 400000 steps"
say "  GPU unavailable (Xid 79, hardware). All 12 runs on ONE runner so the arms are not mixed."

say "PHASE 1: eta=0.10, 6 seeds concurrently (~25 min)"
for S in $SEEDS; do one_run 0.10 1.25e-6 40000 $S & done; wait
for S in $SEEDS; do harvest 0.10 $S; done

say "PHASE 2: eta=0.01, 6 seeds concurrently (400000 steps, ~4-5 h)"
for S in $SEEDS; do one_run 0.01 1.25e-7 400000 $S & done; wait
for S in $SEEDS; do harvest 0.01 $S; done

say ""; say "================ ETA PILOT VERDICT (CPU runner) ================"
python3 "$OUT/verdict.py" "$TSV" 2>&1 | tee -a $R
say ""; say "--- matched-seed GPU cross-check (pre-fault, eta 0.10) ---"
[ -f $OUT/points_gpu.tsv ] && cat $OUT/points_gpu.tsv | tee -a $R
say "DONE."
