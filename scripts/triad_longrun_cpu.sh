#!/usr/bin/env bash
# LONG CPU run: is the eps=0 triad rotation SUSTAINED on the seconds timescale, or a startup transient?
# Extra seeds cannot answer this; only duration can. Reports turns/um in windows so a transient is visible.
set -u
OUT=${OUT:-RUN_LOGS/motor_audit/campaigns_2026-09/TRIAD_LONG_CPU}
mkdir -p "$OUT"
STEPS=${STEPS:-1600000}   # 2.0 s simulated at dt=1.25e-6
BASE="-run -devicecull -nohires -noviz -density 500 -matx 14.0 -maty 2.0 -filx 3.5 -eta 0.10 -dt 1.25e-6 -steps $STEPS -target 9.0 -filsegs 1 -triad -workers 1"
run(){ rm -rf "$OUT/$1"; ./scripts/run_site_normal_glide_gpu.sh $BASE -seed $3 $2 -out "$OUT/$1" > "$OUT/$1.log" 2>&1; }
run "LNAT_20260901" ""            20260901 &
run "LFLP_20260901" "-flip-helix" 20260901 &
run "LNAT_20260902" ""            20260902 &
run "LFLP_20260902" "-flip-helix" 20260902 &
wait
echo "=== SUSTAINED ROTATION? (CPU, 2.0 s simulated, eps=0) ==="
python3 - "$OUT" <<'PY'
import csv,os,sys
O=sys.argv[1]
for n in ("LNAT_20260901","LFLP_20260901","LNAT_20260902","LFLP_20260902"):
    f=os.path.join(O,n,"trajectory_summary.csv")
    if not os.path.exists(f): continue
    r=list(csv.DictReader(open(f),delimiter='\t'))
    if len(r)<4: continue
    pts=[(float(x["t_s"]),abs(float(x["fwd_um"])),float(x["rollTurns"])) for x in r if abs(float(x["fwd_um"]))>0]
    x=r[-1]; d=abs(float(x["fwd_um"]))
    print(f"\n{n}: t={float(x['t_s']):.3f}s fwd={d:.3f}um turns={float(x['rollTurns']):+.2f} overall={float(x['rollTurns'])/d:+.2f} turns/um")
    prev=(0.,0.,0.); out=[]
    for frac in [0.2*k for k in range(1,6)]:
        tgt=pts[-1][0]*frac
        sel=[p for p in pts if p[0]<=tgt]
        if not sel: continue
        cur=sel[-1]
        if cur[1]-prev[1]>1e-6: out.append(f"{(cur[2]-prev[2])/(cur[1]-prev[1]):+7.2f}")
        prev=cur
    print("   local turns/um by fifths: "+" ".join(out))
PY
