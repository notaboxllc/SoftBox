#!/usr/bin/env bash
# FIRM READING at eps ~ 1.5 deg on the RIGID single-segment filament (no joints => no chain/roll-spring
# contamination). Multi-seed paired pos/neg so the eps-odd estimator has a real SEM. Distance is traded for
# seeds deliberately: eps-odd noise is diffusive (turns/um noise ~ 1/sqrt(d)) but seed scatter is the risk
# this project keeps getting bitten by, so n matters more than per-arm length.
set -u
OUT=${OUT:-RUN_LOGS/motor_audit/campaigns_2026-09/RIGID_EPS15}
EPS=${EPS:-1.5}; TARGET=${TARGET:-0.6}
SEEDS=${SEEDS:-"20260901 20260902 20260903 20260904 20260905 20260906"}
mkdir -p "$OUT"
run(){ rm -rf "$OUT/$1"; ./scripts/run_gpu_monitored.sh ./scripts/run_site_normal_glide_gpu.sh \
  -run -gpu -devicecull -nohires -noviz -density 2000 -matx 14.0 -maty 2.0 -filx 3.5 -eta 0.10 -dt 1.25e-6 \
  -steps 4000000 -target $TARGET -seed $2 -randbase -randbase-seed $2 -filsegs 1 -stroke-skew $EPS $3 \
  -out "$OUT/$1" > "$OUT/$1.log" 2>&1; }
for S in $SEEDS; do
  run "s${S}_pos" "$S" ""        &
  run "s${S}_neg" "$S" "-mirror" &
  wait
done
echo "=== RIGID eps=${EPS} deg, multi-seed (target ${TARGET} um) ==="
python3 - "$OUT" <<'PY'
import csv,os,sys,statistics as st
O=sys.argv[1]
def last(n):
    f=os.path.join(O,n,"trajectory_summary.csv")
    if not os.path.exists(f): return None
    r=list(csv.DictReader(open(f),delimiter='\t'))
    if not r: return None
    x=r[-1]; d=abs(float(x["fwd_um"]))
    return (d, float(x["rollTurns"])/d if d>0 else float('nan'), float(x["vfit_um_s"]), float(x["avgBound"])) if d>0 else None
seeds=[d.split("_")[0][1:] for d in sorted(os.listdir(O)) if d.startswith("s") and d.endswith("_pos") and os.path.isdir(os.path.join(O,d))]
print(f"{'seed':<10}{'pos t/um':>10}{'neg t/um':>10}{'eps-odd':>10}{'eps-even':>10}")
odd=[];even=[]
for s in seeds:
    p,n=last(f"s{s}_pos"),last(f"s{s}_neg")
    if not p or not n: print(f"{s:<10}  incomplete"); continue
    o=(p[1]-n[1])/2; e=(p[1]+n[1])/2
    odd.append(o); even.append(e)
    print(f"{s:<10}{p[1]:10.3f}{n[1]:10.3f}{o:10.3f}{e:10.3f}")
if len(odd)>1:
    m=st.mean(odd); sem=st.stdev(odd)/len(odd)**.5
    print(f"\neps-ODD  = {m:+.3f} +/- {sem:.3f} turns/um   (n={len(odd)} pairs, t={abs(m/sem):.1f})")
    print(f"pitch    = {1/abs(m):.3f} um        experiment: 0.47 +/- 0.19 um (2.1 turns/um)")
    print(f"eps-EVEN = {st.mean(even):+.3f} +/- {st.stdev(even)/len(even)**.5:.3f}  (achiral residue)")
    print(f"odd/eps  = {m/1.5:+.3f} turns/um/deg   |  eps for 2.1 turns/um = {1.5*2.1/abs(m):.2f} deg")
PY
