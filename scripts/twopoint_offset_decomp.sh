#!/usr/bin/env bash
# WHICH part of the midpoint offset destroys rectification?
# PROVEN: two-point (foot=0, w=0.5) == ONE spring of the same stiffness at the MIDPOINT of sites n and n-2
# (the couple term vanishes identically). The midpoint differs from site n in exactly three ways:
#     azimuth +13.50 deg | arc -2.70 nm | radius x0.9724
# These knobs move the BOND TARGET ONLY -- bindAzim, and so the kbind latch / site normal / stroke geometry,
# stay on site n. That mismatch (oriented to one site, pulled toward another) is the remaining suspect.
set -u
OUT=${OUT:-RUN_LOGS/motor_audit/campaigns_2026-09/TGT_OFFSET_DECOMP}
mkdir -p "$OUT"
BASE="-run -gpu -devicecull -nohires -noviz -density 500 -matx 10.0 -maty 2.0 -filx 2.5 -eta 0.10 -dt 1.25e-6 -steps 200000 -target 9.0 -filsegs 1"
run(){ rm -rf "$OUT/$1"; ./scripts/run_gpu_monitored.sh ./scripts/run_site_normal_glide_gpu.sh $BASE -seed $3 $2 -out "$OUT/$1" > "$OUT/$1.log" 2>&1; }
for S in 20260901 20260902 20260903; do
  run "base_$S" ""                                            $S &
  run "az_$S"   "-tgt-daz 13.5"                               $S & wait
  run "arc_$S"  "-tgt-darc -2.70"                             $S &
  run "rad_$S"  "-tgt-rscale 0.9724"                          $S & wait
  run "all_$S"  "-tgt-daz 13.5 -tgt-darc -2.70 -tgt-rscale 0.9724" $S & wait
done
echo "=== WHICH OFFSET BREAKS IT? (single-point kernel, rigid filament) ==="
python3 - "$OUT" <<'PY'
import csv,os,sys,statistics as st
O=sys.argv[1]; S=["20260901","20260902","20260903"]
def v(n):
    f=os.path.join(O,n,"trajectory_summary.csv")
    if not os.path.exists(f): return None
    r=list(csv.DictReader(open(f),delimiter='\t'))
    return float(r[-1]["vfit_um_s"]) if r else None
arms=[("base","site n (control)"),("az","azimuth +13.5 deg"),("arc","arc -2.70 nm"),
      ("rad","radius x0.9724"),("all","all three (== two-point)")]
print(f"{'arm':<26}" + "".join(f"{s[-2:]:>9}" for s in S) + f"{'mean':>10}{'signs':>14}")
base=None
for tag,lbl in arms:
    xs=[v(f"{tag}_{s}") for s in S]; ok=[x for x in xs if x is not None]
    if not ok: print(f"{lbl:<26}  incomplete"); continue
    m=st.mean(ok)
    if tag=="base": base=m
    sg="".join("+" if x>0 else "-" for x in ok)
    print(f"{lbl:<26}" + "".join(f"{(x if x is not None else float('nan')):9.3f}" for x in xs) + f"{m:10.3f}{sg:>14}")
if base:
    print(f"\nratios vs control:")
    for tag,lbl in arms[1:]:
        xs=[v(f"{tag}_{s}") for s in S]; ok=[x for x in xs if x is not None]
        if ok: print(f"  {lbl:<26} {st.mean(ok)/base:+.3f}")
    print("\ntwo-point reference (rigid, rho=500): TP/SP = 0.072, signs scrambled")
PY
