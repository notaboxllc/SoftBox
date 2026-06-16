#!/bin/bash
# §6.8 Phase 1 — susceptibility pre-filter (multi-seed).
# Inject a uniform −x seg-side force bias per bound motor; measure net-glide response vs ε.
# ε unit = 5.4e-16 (= 1e-4 relative float32 force error × 5.4e-12 force scale).
# Small points {0,0.5,1,2} = the prompt's float32-scale bound; {100,300,1000} = resolvable slope anchors.
# Net statistic: |netX| from GRID_ROW. Raw -> RUN_LOGS/...; progress -> .last_run_status.
cd /home/jba/Code/SoftBox
RAW=RUN_LOGS/2026-06-15_4biv_fp64/phase1_sweep.txt
STEPS=10000
SEEDS="1 2 3 4 5"
MULTS="0 0.5 1 2 100 300 1000"
: > "$RAW"
echo "Phase1 sweep — n=5, 14x2, 10k, ε unit=5.4e-16 — started $(date +%H:%M)" | tee -a "$RAW"
status(){ echo "[$(date +%H:%M)] Phase1 sweep $1" > .last_run_status; }
for mult in $MULTS; do
  bias=$(python3 -c "print('%.6e' % ($mult * 5.4e-16))")
  for s in $SEEDS; do
    status "mult=$mult (bias=$bias) seed=$s"
    row=$(./run_gliding.sh -gpu -full -grid -seed $s -forcebias $bias $STEPS 2>&1 | grep -E "GRID_ROW")
    echo "mult=$mult bias=$bias seed=$s $row" >> "$RAW"
  done
done
status "Phase1 sweep DONE"
echo "Phase1 sweep done $(date +%H:%M)" | tee -a "$RAW"
# ---- summarize ----
python3 - "$RAW" <<'PY'
import sys,re,statistics as st
rows={}
for ln in open(sys.argv[1]):
    m=re.search(r'mult=(\S+) bias=(\S+) seed=(\d+).*netX=(-?[\d.]+).*avgB=([\d.]+)',ln)
    if not m: continue
    mult=float(m.group(1)); netx=abs(float(m.group(4))); avgb=float(m.group(5))
    rows.setdefault(mult,{'net':[],'avgb':[]}); rows[mult]['net'].append(netx); rows[mult]['avgb'].append(avgb)
UNIT=5.4e-16
print("\n%-8s %-12s %-9s %-7s %-7s %-7s"%("mult","bias","netMean","netSD","SEM","avgB"))
base=None
for mult in sorted(rows):
    nets=rows[mult]['net']; mean=st.mean(nets); sd=st.pstdev(nets) if len(nets)>1 else 0
    sem=sd/len(nets)**0.5 if len(nets)>1 else 0
    if mult==0: base=mean
    print("%-8s %-12.4e %-9.3f %-7.3f %-7.3f %-7.2f"%(mult,mult*UNIT,mean,sd,sem,st.mean(rows[mult]['avgb'])))
print("\nresponse Δnet vs mult=0 baseline (%.3f):"%base)
for mult in sorted(rows):
    if mult==0: continue
    mean=st.mean(rows[mult]['net']); d=mean-base; bias=mult*UNIT
    slope=d/bias if bias else 0
    print("  mult=%-7s bias=%.3e  Δnet=%+.3f  slope=%.3e µm/s per force-unit"%(mult,bias,d,slope))
PY
