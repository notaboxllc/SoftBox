#!/usr/bin/env bash
# IS THE TWO-SPRING COLLAPSE NUMERICAL? The bond mode sits at tau/dt ~ 7.5 at production dt -- the regime where
# an explicit integrator rings. A numerical oscillation SOFTENS as dt falls; a geometric effect does not.
# Steps are scaled with dt so every arm covers the SAME physical time.
set -u
OUT=${OUT:-RUN_LOGS/motor_audit/campaigns_2026-09/TWOPOINT_DT}
mkdir -p "$OUT"
run(){ rm -rf "$OUT/$1"; ./scripts/run_gpu_monitored.sh ./scripts/run_site_normal_glide_gpu.sh \
  -run -gpu -devicecull -nohires -noviz -density 500 -matx 10.0 -maty 2.0 -filx 2.5 -eta 0.10 \
  -dt $3 -steps $4 -target 9.0 -seed 20260901 -filsegs 1 $2 -out "$OUT/$1" > "$OUT/$1.log" 2>&1; }
TP="-twopoint -twopoint-foot 0 -twopoint-ancw 0.5"
run sp_dt1  ""     1.25e-6  200000  & run tp_dt1  "$TP" 1.25e-6  200000  & wait
run sp_dt2  ""     6.25e-7  400000  & run tp_dt2  "$TP" 6.25e-7  400000  & wait
run sp_dt4  ""     3.125e-7 800000  & run tp_dt4  "$TP" 3.125e-7 800000  & wait
echo "=== dt DISCRIMINATOR: numerical ringing, or geometry? ==="
python3 - "$OUT" <<'PY'
import csv,os,sys
O=sys.argv[1]
def v(n):
    f=os.path.join(O,n,"trajectory_summary.csv")
    if not os.path.exists(f): return None
    r=list(csv.DictReader(open(f),delimiter='\t'))
    return (float(r[-1]["vfit_um_s"]), float(r[-1]["avgBound"])) if r else None
print(f"{'dt (s)':>10}{'tau/dt':>8}{'single-pt':>11}{'two-point':>11}{'TP/SP':>9}")
for tag,dt,td in (("dt1",1.25e-6,7.5),("dt2",6.25e-7,15.1),("dt4",3.125e-7,30.2)):
    a,b=v("sp_"+tag),v("tp_"+tag)
    if not a or not b: print(f"{dt:10.3e}{td:8.1f}   incomplete"); continue
    print(f"{dt:10.3e}{td:8.1f}{a[0]:11.4f}{b[0]:11.4f}{b[0]/a[0]:9.3f}")
print("\nTP/SP rising toward 1 as dt falls => NUMERICAL (the springs were ringing).")
print("TP/SP flat and small                => GEOMETRIC (a real property of the two-site bond).")
PY
