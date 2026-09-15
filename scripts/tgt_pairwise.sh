#!/usr/bin/env bash
# Completes the 2^3 factorial. Singles gave 0.950 (az) / 0.340 (arc) / 0.886 (rad) but the triple gives 0.026 --
# 11x worse than the product 0.286. The interaction is real; these three pairs locate it.
set -u
OUT=${OUT:-RUN_LOGS/motor_audit/campaigns_2026-09/TGT_PAIRWISE}
mkdir -p "$OUT"
BASE="-run -gpu -devicecull -nohires -noviz -density 500 -matx 10.0 -maty 2.0 -filx 2.5 -eta 0.10 -dt 1.25e-6 -steps 200000 -target 9.0 -filsegs 1"
run(){ rm -rf "$OUT/$1"; ./scripts/run_gpu_monitored.sh ./scripts/run_site_normal_glide_gpu.sh $BASE -seed $3 $2 -out "$OUT/$1" > "$OUT/$1.log" 2>&1; }
for S in 20260901 20260902 20260903; do
  run "azarc_$S" "-tgt-daz 13.5 -tgt-darc -2.70"        $S &
  run "azrad_$S" "-tgt-daz 13.5 -tgt-rscale 0.9724"     $S & wait
  run "arcrad_$S" "-tgt-darc -2.70 -tgt-rscale 0.9724"  $S & wait
done
echo "=== 2^3 FACTORIAL: locating the interaction ==="
python3 - "$OUT" <<'PY'
import csv,os,sys,statistics as st
O=sys.argv[1]; P=os.path.join(os.path.dirname(O),"TGT_OFFSET_DECOMP"); S=["20260901","20260902","20260903"]
def v(d,n):
    f=os.path.join(d,n,"trajectory_summary.csv")
    if not os.path.exists(f): return None
    r=list(csv.DictReader(open(f),delimiter='\t'))
    return float(r[-1]["vfit_um_s"]) if r else None
def mean(d,tag):
    xs=[v(d,f"{tag}_{s}") for s in S]; ok=[x for x in xs if x is not None]
    return (st.mean(ok), "".join("+" if x>0 else "-" for x in ok)) if ok else (None,"")
base,_=mean(P,"base")
rows=[("none (control)",P,"base"),("az",P,"az"),("arc",P,"arc"),("rad",P,"rad"),
      ("az+arc",O,"azarc"),("az+rad",O,"azrad"),("arc+rad",O,"arcrad"),("az+arc+rad",P,"all")]
print(f"{'offsets applied':<18}{'mean v':>9}{'ratio':>8}{'signs':>8}")
r={}
for lbl,d,tag in rows:
    m,sg=mean(d,tag)
    if m is None: print(f"{lbl:<18}   incomplete"); continue
    r[tag]=m/base
    print(f"{lbl:<18}{m:9.3f}{m/base:8.3f}{sg:>8}")
if all(k in r for k in ("az","arc","rad","azarc","azrad","arcrad","all")):
    print(f"\nindependence would predict:")
    print(f"  az+arc     {r['az']*r['arc']:.3f}   measured {r['azarc']:.3f}")
    print(f"  az+rad     {r['az']*r['rad']:.3f}   measured {r['azrad']:.3f}")
    print(f"  arc+rad    {r['arc']*r['rad']:.3f}   measured {r['arcrad']:.3f}")
    print(f"  all three  {r['az']*r['arc']*r['rad']:.3f}   measured {r['all']:.3f}")
PY
