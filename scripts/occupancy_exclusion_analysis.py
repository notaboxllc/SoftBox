#!/usr/bin/env python3
"""Analyze the continuous local actin co-occupancy exclusion paired density diagnostic.
Reads <out>/off/*.json and <out>/on/*.json, pairs by (density, seed), writes:
  docs/helical_binding/CONTINUOUS_OCCUPANCY_EXCLUSION_CELLS.csv    (per-cell OFF & ON physics + health)
  docs/helical_binding/CONTINUOUS_OCCUPANCY_EXCLUSION_EVENTS.csv   (per-cell occupancy event counts)
and prints a density-response summary (OFF vs ON, seed-averaged).
Usage: occupancy_exclusion_analysis.py <experiment_out_dir>
"""
import sys, json, glob, os, statistics
from collections import defaultdict

DOCDIR = "docs/helical_binding"

def load(d):
    out = {}
    for fn in glob.glob(os.path.join(d, "cell_d*_s*.json")):
        try:
            j = json.load(open(fn))
        except Exception as e:
            print("  (skip %s: %s)" % (fn, e)); continue
        out[(int(j["density"]), int(j["seed"]))] = j
    return out

def g(j, k, default=0.0):
    v = j.get(k, default)
    return v if v is not None else default

def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "RUN_LOGS/occupancy_exclusion_experiment"
    off = load(os.path.join(out, "off")); on = load(os.path.join(out, "on"))
    os.makedirs(DOCDIR, exist_ok=True)
    keys = sorted(set(off) | set(on))
    cells_p = os.path.join(DOCDIR, "CONTINUOUS_OCCUPANCY_EXCLUSION_CELLS.csv")
    events_p = os.path.join(DOCDIR, "CONTINUOUS_OCCUPANCY_EXCLUSION_EVENTS.csv")
    cols = ["density","seed","condition","occupancy_nm","velProd_umPerS","meanBoundHeads","boundFrac",
            "continuity","vel_per_bound_head","atp_turnover","net_force_pn","peak_load_pn",
            "invalid_states","solver_failures","rigor_ruptures","steps","wall_s","steps_per_s"]
    with open(cells_p, "w") as f:
        f.write(",".join(cols) + "\n")
        for src, cond in ((off, "off"), (on, "on")):
            for k in sorted(src):
                j = src[k]
                row = [k[0], k[1], cond, g(j,"occupancy_exclusion_nm"), g(j,"velProd_umPerS", g(j,"vel_prod")),
                       g(j,"mean_bound_heads"), g(j,"bound_fraction"), g(j,"continuity"),
                       g(j,"vel_per_bound_head"), int(g(j,"atp_turnover")), g(j,"net_force_pn"), g(j,"peak_load_pn"),
                       int(g(j,"invalid_states")), int(g(j,"solver_failures")), int(g(j,"rigor_ruptures")),
                       int(g(j,"steps")), g(j,"wall_s"), g(j,"steps_per_s")]
                f.write(",".join(str(x) for x in row) + "\n")
    with open(events_p, "w") as f:
        f.write("density,seed,condition,occupancy_nm,geometric_candidates,rejects,accepted,same_step_conflicts,rejection_fraction,mean_local_regions,max_local_regions\n")
        for src, cond in ((off, "off"), (on, "on")):
            for k in sorted(src):
                j = src[k]
                f.write(",".join(str(x) for x in [k[0], k[1], cond, g(j,"occupancy_exclusion_nm"),
                        int(g(j,"occupancy_geometric_candidates")), int(g(j,"occupancy_rejects")),
                        int(g(j,"occupancy_accepted")), int(g(j,"occupancy_same_step_conflicts")),
                        g(j,"occupancy_rejection_fraction"), g(j,"occupancy_mean_local_regions"),
                        int(g(j,"occupancy_max_local_regions"))]) + "\n")

    # seed-averaged density response
    def avg(src, dens, key):
        vs = [g(src[k], key) for k in src if k[0] == dens]
        return (statistics.mean(vs), statistics.pstdev(vs), len(vs)) if vs else (float("nan"), 0, 0)
    def velkey(j): return "velProd_umPerS" if "velProd_umPerS" in j else "vel_prod"
    dens = sorted(set(k[0] for k in keys))
    print("\n=== OCCUPANCY-EXCLUSION PAIRED DENSITY DIAGNOSTIC (seed-averaged; OFF vs ON 5.4 nm) ===")
    print("%-7s | %-15s | %-15s | %-15s | %-8s | %-8s" % (
        "density", "velProd OFF|ON", "avgBound OFF|ON", "vel/bnd OFF|ON", "atp O|N", "rej.frac"))
    print("-" * 84)
    for d in dens:
        vk = "vel_prod"
        vo = avg(off, d, vk); von = avg(on, d, vk)
        bo = avg(off, d, "mean_bound_heads"); bon = avg(on, d, "mean_bound_heads")
        vpo = avg(off, d, "vel_per_bound_head"); vpon = avg(on, d, "vel_per_bound_head")
        ao = avg(off, d, "atp_turnover"); an = avg(on, d, "atp_turnover")
        rf = avg(on, d, "occupancy_rejection_fraction")
        print("%-7d | %5.2f | %5.2f   | %5.2f | %5.2f   | %5.3f | %5.3f  | %5.0f|%5.0f | %6.4f" % (
            d, vo[0], von[0], bo[0], bon[0], vpo[0], vpon[0], ao[0], an[0], rf[0]))
    inv = sum(int(g(src[k],"invalid_states")) for src in (off,on) for k in src)
    sf = sum(int(g(src[k],"solver_failures")) for src in (off,on) for k in src)
    print("\nHEALTH: total invalid_states=%d solver_failures=%d across %d OFF + %d ON cells" % (inv, sf, len(off), len(on)))
    print("wrote %s" % cells_p); print("wrote %s" % events_p)

if __name__ == "__main__":
    main()
