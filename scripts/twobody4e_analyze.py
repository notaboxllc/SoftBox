#!/usr/bin/env python3
"""Experiment 4E — passive myosin-tail geometry as a recruitment mechanism.
Summary figure from the 4E CSVs (single-molecule mechanics, capture volume, recruitment).

Usage: python3 scripts/twobody4e_analyze.py <csv_dir> <out.png>
"""
import sys, csv, math
from collections import defaultdict

def readcsv(path):
    with open(path) as f:
        return list(csv.DictReader(f))

def num(x):
    try: return float(x)
    except: return math.nan

def main():
    if len(sys.argv) < 3:
        print("usage: twobody4e_analyze.py <csv_dir> <out.png>"); sys.exit(1)
    d, out = sys.argv[1], sys.argv[2]
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except Exception as e:
        print("matplotlib unavailable:", e); sys.exit(0)

    sm = readcsv(f"{d}/single_molecule_tail.csv")
    cv = readcsv(f"{d}/capture_volume.csv")
    rc = readcsv(f"{d}/recruitment_mat.csv")

    fig, ax = plt.subplots(2, 2, figsize=(12, 9))
    fig.suptitle("Experiment 4E — passive myosin-tail geometry as a recruitment mechanism", fontsize=13, weight="bold")

    # (1) single-molecule: delivered stroke vs lTail, for stiff-bend (κ=400) and free (κ=4), at k_tail=2
    a = ax[0, 0]
    for kap, lab, col in [(400.0, "stiff-bend κ=400", "tab:blue"), (4.0, "free κ=4", "tab:red")]:
        pts = [(num(r["lTail_nm"]), num(r["deliveredStroke_nm"])) for r in sm
               if num(r["kappaTail_pNnmrad2"]) == kap and (r["kTail_pNnm"] in ("2.000", "2")) ]
        pts = sorted(pts)
        if pts: a.plot([p[0] for p in pts], [p[1] for p in pts], "o-", color=col, label=lab)
    base = [num(r["deliveredStroke_nm"]) for r in sm if r["lTail_nm"] == "0"]
    if base: a.axhline(base[0], ls="--", color="k", lw=1, label=f"fixed anchor ({base[0]:.1f} nm)")
    a.set_xlabel("tail rest length lTail (nm)"); a.set_ylabel("delivered stroke (nm)")
    a.set_title("Q5 — stroke absorption vs tail (k_tail=2)"); a.legend(fontsize=8); a.grid(alpha=.3)

    # (2) single-molecule: whole-crossbridge k_ext vs lTail
    a = ax[0, 1]
    for kap, lab, col in [(400.0, "stiff-bend κ=400", "tab:blue"), (4.0, "free κ=4", "tab:red")]:
        pts = [(num(r["lTail_nm"]), num(r["kExt_pNnm"])) for r in sm
               if num(r["kappaTail_pNnmrad2"]) == kap and (r["kTail_pNnm"] in ("2.000", "2"))]
        pts = sorted(pts)
        if pts: a.plot([p[0] for p in pts], [p[1] for p in pts], "o-", color=col, label=lab)
    kb = [num(r["kExt_pNnm"]) for r in sm if r["lTail_nm"] == "0"]
    if kb: a.axhline(kb[0], ls="--", color="k", lw=1, label=f"fixed anchor ({kb[0]:.2f})")
    a.axhspan(0.5, 2.0, color="green", alpha=.08, label="skeletal band")
    a.set_xlabel("tail rest length lTail (nm)"); a.set_ylabel("k_ext (pN/nm)")
    a.set_title("Q4 — series compliance (whole-crossbridge stiffness)"); a.legend(fontsize=8); a.grid(alpha=.3)

    # (3) capture volume: footprint area + lateral reach vs lTail (free tail)
    a = ax[1, 0]
    pts = sorted((num(r["lTail_nm"]), num(r["footprintArea_nm2"]), num(r["lateralReach_nm"]))
                 for r in cv if num(r["kappaTail_pNnmrad2"]) == 4.0)
    if pts:
        a.plot([p[0] for p in pts], [p[1] for p in pts], "o-", color="tab:purple", label="footprint area (nm²)")
        a.set_xlabel("tail rest length lTail (nm)"); a.set_ylabel("footprint area (nm²)", color="tab:purple")
        a2 = a.twinx(); a2.plot([p[0] for p in pts], [p[2] for p in pts], "s--", color="tab:orange", label="lateral reach (nm)")
        a2.set_ylabel("lateral reach (nm)", color="tab:orange")
    a.set_title("Q2 — capture volume grows with tail (free swing)"); a.grid(alpha=.3)

    # (4) recruitment: avgBound + continuity vs lTail
    a = ax[1, 1]
    order = {"fixed_anchor": 0}
    xs, avg, cont = [], [], []
    for r in rc:
        lt = num(r["lTail_nm"]); xs.append(lt); avg.append(num(r["avgBound"])); cont.append(num(r["contFrac"]))
    z = sorted(zip(xs, avg, cont))
    xs = [p[0] for p in z]; avg = [p[1] for p in z]; cont = [p[2] for p in z]
    a.plot(xs, avg, "o-", color="tab:green", label="avgBound")
    a2 = a.twinx(); a2.plot(xs, cont, "s--", color="tab:brown", label="continuity")
    a.set_xlabel("tail rest length lTail (nm)  [0 = fixed anchor]"); a.set_ylabel("mean # bound", color="tab:green")
    a2.set_ylabel("continuity (frac ≥1 bound)", color="tab:brown")
    a.set_title("Q3 — recruitment vs tail (free swing, moving-filament mat)"); a.grid(alpha=.3)

    fig.tight_layout(rect=[0, 0, 1, 0.96])
    fig.savefig(out, dpi=110)
    print("wrote", out)

if __name__ == "__main__":
    main()
