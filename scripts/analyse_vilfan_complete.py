#!/usr/bin/env python3
"""Analysis of the Vilfan-COMPLETE reference records -> the tables used in
docs/twirling/vilfan_target_zone/VILFAN_COMPLETE_REFERENCE_VALIDATION.md

Reads RUN_LOGS/vilfan_complete/*.json (atomic records written by VilfanCompleteHarness).
Pure stdlib, CPU only.
"""
import json, glob, math, os, sys, statistics as st
from collections import defaultdict

RUNDIR = "RUN_LOGS/vilfan_complete"

def load():
    recs = {}
    for p in sorted(glob.glob(os.path.join(RUNDIR, "*.json"))):
        with open(p) as f:
            r = json.load(f)
        recs[r["armId"]] = r
    return recs

def group(recs, tag):
    """(kd, alpha, seed) -> {'native':rec, 'mirror':rec} for arms with the given tag."""
    g = defaultdict(dict)
    for k, r in recs.items():
        if not k.startswith(tag + "_"):
            continue
        hand = "mirror" if k.endswith("_mirror") else "native"
        g[(round(r["kDoverKA"], 6), r["alpha"], r["seed"])][hand] = r
    return g

def ms(v):
    """mean +- sem"""
    v = [x for x in v if x is not None and not (isinstance(x, float) and math.isnan(x))]
    if not v: return (float('nan'), float('nan'))
    if len(v) == 1: return (v[0], float('nan'))
    return (st.mean(v), st.stdev(v) / math.sqrt(len(v)))

def f(x, n=4):
    if x is None or (isinstance(x, float) and math.isnan(x)): return "  n/a"
    return f"{x:.{n}g}"

# ---------------------------------------------------------------- per-arm table
def per_arm(recs, tag, title):
    print(f"\n### {title}\n")
    print("| arm | v (µm/s) | ω (rad/s) | pitch (µm/turn) | turns | ⟨x_A⟩ (nm) | ⟨ξ_A⟩ (nm) | ⟨θ_A⟩ (rad) | M_A (pN·nm) | duty | events |")
    print("|---|---|---|---|---|---|---|---|---|---|---|")
    for k in sorted(recs):
        if not k.startswith(tag + "_"): continue
        r = recs[k]
        print(f"| `{k}` | {f(r['velUmPerS'])} | {f(r['omegaRadPerS'])} | {f(r['pitchUmPerTurn'])} | "
              f"{f(r['turns'],3)} | {f(r['meanXaNm'])} | {f(r['meanXiANm'])} | {f(r['meanThARad'])} | "
              f"{f(r['meanAttachTorquePnNm'])} | {f(r['dutyRatio'],3)} | {r['nEvents']} |")

# ------------------------------------------------------- mirror decomposition
def mirror_table(recs, tag, title):
    g = group(recs, tag)
    print(f"\n### {title}\n")
    print("| kD/kA | α | seed | Ω_native | Ω_mirror | Ω_even | Ω_odd | M_A native | M_A mirror | ⟨x_A⟩ nat | ⟨x_A⟩ mir |")
    print("|---|---|---|---|---|---|---|---|---|---|---|")
    for key in sorted(g):
        d = g[key]
        if "native" not in d or "mirror" not in d: continue
        n, m = d["native"], d["mirror"]
        on, om = n["omegaRadPerS"], m["omegaRadPerS"]
        print(f"| {key[0]:g} | {key[1]:g} | {key[2]} | {f(on,5)} | {f(om,5)} | {f(0.5*(on+om),3)} | "
              f"{f(0.5*(on-om),5)} | {f(n['meanAttachTorquePnNm'])} | {f(m['meanAttachTorquePnNm'])} | "
              f"{f(n['meanXaNm'])} | {f(m['meanXaNm'])} |")

# ------------------------------------------------------------------ sweep table
def sweep_table(recs, tag, title, alpha=None):
    g = group(recs, tag)
    byk = defaultdict(list)
    for key, d in g.items():
        if "native" not in d: continue
        if alpha is not None and key[1] != alpha: continue
        byk[(key[0], key[1])].append((d["native"], d.get("mirror")))
    print(f"\n### {title}\n")
    print("| kD/kA | α | n | ⟨x_A⟩ (nm) | ⟨θ_A⟩ (rad) | M_A,odd (pN·nm) | Ω_odd (rad/s) | pitch (µm/turn) | v (µm/s) | duty | occ det/pre/post/rigor | cap |")
    print("|---|---|---|---|---|---|---|---|---|---|---|---|")
    for (kd, al) in sorted(byk):
        arms = byk[(kd, al)]
        xa  = ms([a["meanXaNm"] for a, _ in arms])
        tha = ms([a["meanThARad"] for a, _ in arms])
        odd = ms([0.5*(a["omegaRadPerS"] - b["omegaRadPerS"]) for a, b in arms if b])
        mao = ms([0.5*(a["meanAttachTorquePnNm"] - b["meanAttachTorquePnNm"]) for a, b in arms if b])
        pit = ms([a["pitchUmPerTurn"] for a, _ in arms])
        vel = ms([a["velUmPerS"] for a, _ in arms])
        dut = ms([a["dutyRatio"] for a, _ in arms])
        occ = [ms([a[k] for a, _ in arms])[0] for k in ("occDetached","occPrePS","occPostPS","occRigor")]
        cap = sum(1 for a, _ in arms if a["travelCapHit"])
        print(f"| {kd:g} | {al:g} | {len(arms)} | {f(xa[0],4)}±{f(xa[1],2)} | {f(tha[0],4)}±{f(tha[1],2)} | "
              f"{f(mao[0],4)} | {f(odd[0],4)}±{f(odd[1],2)} | {f(pit[0],4)}±{f(pit[1],2)} | "
              f"{f(vel[0],4)} | {f(dut[0],3)} | "
              + "/".join(f(o,2) for o in occ) + f" | {cap}/{len(arms)} |")

# ------------------------------------------------------------- no-depletion
def nodep_table(recs):
    print("\n### No-depletion (shadow) control\n")
    print("| seed | ⟨x_A⟩ real (nm) | ⟨x_A⟩ no-depletion (nm) | difference | ω (rad/s) |")
    print("|---|---|---|---|---|")
    real, shad = [], []
    for k in sorted(recs):
        if not k.startswith("nodep_"): continue
        r = recs[k]
        if not r["haveShadow"]: continue
        real.append(r["meanXaNm"]); shad.append(r["shadowMeanXaNm"])
        print(f"| {r['seed']} | {f(r['meanXaNm'],5)} | {f(r['shadowMeanXaNm'],5)} | "
              f"{f(r['meanXaNm']-r['shadowMeanXaNm'],4)} | {f(r['omegaRadPerS'],5)} |")
    if real:
        mr, sr = ms(real); msh, ssh = ms(shad)
        print(f"| **mean** | **{f(mr,5)} ± {f(sr,2)}** | **{f(msh,5)} ± {f(ssh,2)}** | "
              f"**{f(mr-msh,4)}** | |")

# ----------------------------------------------------------------- health
def health(recs):
    print("\n### Numerical health (all arms)\n")
    mf = max(r["maxForceResidPn"] for r in recs.values())
    mt = max(r["maxTorqueResidPnNm"] for r in recs.values())
    nc = sum(r["equilNonConverged"] for r in recs.values())
    nz = sum(r["nbZeroEvents"] for r in recs.values())
    br = sum(r["equilBranchChanges"] for r in recs.values())
    ev = sum(r["nEvents"] for r in recs.values())
    cap = [k for k, r in recs.items() if r["travelCapHit"]]
    notes = {k: r["note"] for k, r in recs.items() if r["note"].strip()}
    print(f"- arms: **{len(recs)}**, total Gillespie events: **{ev:,}**")
    print(f"- max force-balance residual over every equilibration: **{mf:.3g} pN**")
    print(f"- max torque-balance residual: **{mt:.3g} pN·nm**")
    print(f"- branch reassignments: {br:,} ({100.0*br/max(1,ev):.1f} % of events); **non-converged: {nc}**")
    print(f"- equilibrations with zero bound heads: **{nz}**")
    print(f"- travel-cap hits: **{len(cap)}**" + (f" — {', '.join(sorted(cap))}" if cap else ""))
    if notes:
        print("- notes:")
        for k, v in sorted(notes.items()): print(f"    - `{k}`: {v}")
    # stationarity
    print("\n**Stationarity (analysed window split in two equal halves of simulated time):**\n")
    print("| tag | n | ω first half | ω second half | ratio | v first | v second |")
    print("|---|---|---|---|---|---|---|")
    for tag in ("paper", "native", "alpha"):
        arms = [r for k, r in recs.items() if k.startswith(tag + "_") and k.endswith("_native")]
        if not arms: continue
        o1 = ms([r["omegaFirstHalf"] for r in arms]); o2 = ms([r["omegaSecondHalf"] for r in arms])
        v1 = ms([r["velFirstHalf"] for r in arms]);  v2 = ms([r["velSecondHalf"] for r in arms])
        print(f"| {tag} | {len(arms)} | {f(o1[0],4)} | {f(o2[0],4)} | {f(o2[0]/o1[0],4)} | {f(v1[0],4)} | {f(v2[0],4)} |")

# ------------------------------------------------------- attachment histogram
def hist_table(recs, arm_prefix, title):
    arms = [r for k, r in recs.items() if k.startswith(arm_prefix) and k.endswith("_native")]
    if not arms: return
    L = arms[0]["zonePeriodNm"]; nb0 = len(arms[0]["xaHist"])
    raw = [sum(a["xaHist"][i] for a in arms) for i in range(nb0)]
    rsh = [sum(a["shadowHist"][i] for a in arms) for i in range(nb0)]
    # coarsen 40 -> 10 bins for the report
    grp, nb = nb0 // 10, 10
    tot = [sum(raw[i*grp:(i+1)*grp]) for i in range(nb)]
    sh  = [sum(rsh[i*grp:(i+1)*grp]) for i in range(nb)]
    n = sum(tot); shn = sum(sh)
    print(f"\n### {title}\n")
    print(f"Zone period L = {L:.3f} nm; {n:,} attachment events pooled over {len(arms)} arms. "
          f"Positive x = BEFORE the zone centre (S3.5).\n")
    print("| x bin (nm) | attachments | fraction | " + ("no-depletion flux |" if shn else ""))
    print("|---|---|---|" + ("---|" if shn else ""))
    for i in range(nb):
        lo = -L/2 + L*i/nb; hi = -L/2 + L*(i+1)/nb
        row = f"| {lo:+.2f} … {hi:+.2f} | {tot[i]} | {tot[i]/max(1,n):.4f} |"
        if shn: row += f" {sh[i]/shn:.4f} |"
        print(row)
    # first/second-half split about the zone centre
    half = nb // 2
    before = sum(tot[half:]); after = sum(tot[:half])
    print(f"\n**Before the centre (x>0): {before:,} ({100*before/n:.2f} %) — "
          f"after (x<0): {after:,} ({100*after/n:.2f} %).**")
    if shn:
        b2 = sum(sh[half:]); a2 = sum(sh[:half])
        print(f"**No-depletion flux: before {100*b2/shn:.2f} %, after {100*a2/shn:.2f} %.**")

# --------------------------------------------------------- lattice comparison
def transfer(recs):
    print("\n### Paper lattice vs SoftBox native lattice (α = 4, kD/kA = 0.1)\n")
    print("| quantity | paper (13/28, a=2.75) | native (37/80, a=2.70) | ratio native/paper |")
    print("|---|---|---|---|")
    P = [r for k, r in recs.items() if k.startswith("paper_") and k.endswith("_native")]
    N = [r for k, r in recs.items() if k.startswith("native_") and k.endswith("_native")]
    if not P or not N: return
    Pm = [r for k, r in recs.items() if k.startswith("paper_") and k.endswith("_mirror")]
    Nm = [r for k, r in recs.items() if k.startswith("native_") and k.endswith("_mirror")]
    def row(name, fn, fmt=5):
        p = ms([fn(r) for r in P]); n = ms([fn(r) for r in N])
        rt = n[0]/p[0] if p[0] else float('nan')
        print(f"| {name} | {f(p[0],fmt)} ± {f(p[1],2)} | {f(n[0],fmt)} ± {f(n[1],2)} | {f(rt,4)} |")
    row("target-zone period L (nm)", lambda r: r["zonePeriodNm"], 5)
    row("⟨x_A⟩ (nm)", lambda r: r["meanXaNm"])
    row("⟨ξ_A⟩ (nm)", lambda r: r["meanXiANm"])
    row("⟨θ_A⟩ (rad)", lambda r: r["meanThARad"])
    row("M_A (pN·nm)", lambda r: r["meanAttachTorquePnNm"])
    row("ω (rad/s)", lambda r: r["omegaRadPerS"])
    row("v (µm/s)", lambda r: r["velUmPerS"])
    row("pitch (µm/turn)", lambda r: r["pitchUmPerTurn"])
    row("duty ratio", lambda r: r["dutyRatio"], 4)
    # Omega_odd
    op = ms([a["omegaRadPerS"] for a in P]); on = ms([a["omegaRadPerS"] for a in N])
    print(f"| **Ω_odd (rad/s)** | **{f(op[0],5)} ± {f(op[1],2)}** | **{f(on[0],5)} ± {f(on[1],2)}** | "
          f"**{f(on[0]/op[0],4)}** |")

# -------------------------------------------------------------------- analytic
def analytic_check(recs):
    print("\n### Simulation vs Vilfan's own analytical relations (paper-exact arms)\n")
    P = [r for k, r in recs.items() if k.startswith("paper_") and k.endswith("_native")]
    if not P: return
    r0 = P[0]
    K, kT, al, L, kD, d = r0["K"], r0["kBT"], r0["alpha"], r0["zonePeriodNm"], r0["kD"], r0["dNm"]
    Kth = al*kT; Kthp = (math.pi**2/L**2)*Kth
    print(f"`K_ϑ = {Kth:.4f} pN·nm`, `K_ϑ' = (π²/L²)K_ϑ = {Kthp:.5f} pN/nm`, "
          f"`K_ϑ'/(K+K_ϑ') = {Kthp/(K+Kthp):.5f}`, `K/(K+K_ϑ') = {K/(K+Kthp):.5f}`, `L = {L:.3f} nm`\n")
    print("| relation | predicted from the measured input | measured | agreement |")
    print("|---|---|---|---|")
    xa  = ms([r["meanXaNm"] for r in P])[0]
    xia = ms([r["meanXiANm"] for r in P])[0]
    tha = ms([r["meanThARad"] for r in P])[0]
    vel = ms([r["velUmPerS"] for r in P])[0]
    om  = ms([r["omegaRadPerS"] for r in P])[0]
    pit = ms([r["pitchUmPerTurn"] for r in P])[0]
    def rowa(eq, pred, meas):
        print(f"| {eq} | {pred:.5g} | {meas:.5g} | {100*abs(pred-meas)/abs(meas):.2f} % |")
    rowa("Eq (16) ⟨ξ_A⟩ = [K_ϑ'/(K+K_ϑ')]⟨x_A⟩", Kthp/(K+Kthp)*xa, xia)
    rowa("Eq (12) ⟨θ_A⟩ = [K/(K+K_ϑ')](π/L)⟨x_A⟩", K/(K+Kthp)*(math.pi/L)*xa, tha)
    rowa("Eq (17) v = k_D(d + ⟨ξ_A⟩)  [µm/s]", kD*(d+xia)/1000.0, vel)
    rowa("Eq (18) ω = −k_D⟨θ_A⟩", -kD*tha, om)
    rowa("Eq (19) λ = 2πv/ω  [µm/turn]", 2*math.pi*(kD*(d+xia)/1000.0)/(-kD*tha), pit)

if __name__ == "__main__":
    os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    recs = load()
    if not recs:
        sys.exit("no records in " + RUNDIR)
    what = sys.argv[1] if len(sys.argv) > 1 else "all"
    if what in ("all", "paper"):
        per_arm(recs, "paper", "Stage 4 — paper-exact positive control, per arm")
        mirror_table(recs, "paper", "Stage 4 — mirror even/odd decomposition")
        analytic_check(recs)
        hist_table(recs, "paper", "Stage 4 — attachment position relative to the target-zone centre")
    if what in ("all", "nodep"):
        nodep_table(recs)
        hist_table(recs, "nodep", "No-depletion control — attachment position and shadow flux")
    if what in ("all", "sweep"):
        sweep_table(recs, "sweep", "Stage 5 — preregistered kD/kA sweep (α = 4)")
    if what in ("all", "native"):
        per_arm(recs, "native", "Stage 6 — native SoftBox lattice, per arm")
        mirror_table(recs, "native", "Stage 6 — mirror even/odd decomposition")
        transfer(recs)
        hist_table(recs, "native", "Stage 6 — attachment position, native lattice")
    if what in ("all", "alpha"):
        sweep_table(recs, "alpha", "Stage 7 — α = 6 and α = 8")
        mirror_table(recs, "alpha", "Stage 7 — mirror even/odd decomposition")
    if what in ("all", "health"):
        health(recs)
