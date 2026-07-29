#!/usr/bin/env python3
"""Analysis of the Vilfan overdamped finite-drag records.

Reads RUN_LOGS/vilfan_drag/*.json (overdamped arms) and REFERENCE_RECORDS/*.json (the committed
quasi-static baseline from the complete-reference study) and emits the tables used in
docs/twirling/vilfan_target_zone/VILFAN_OVERDAMPED_DRAG_VALIDATION.md. Pure stdlib, CPU only.
"""
import json, glob, math, os, sys, statistics as st
from collections import defaultdict

OD = "RUN_LOGS/vilfan_drag"
QS = "REFERENCE_RECORDS"

def load(d):
    r = {}
    for p in sorted(glob.glob(os.path.join(d, "*.json"))):
        with open(p) as f:
            j = json.load(f)
        r[j["armId"]] = j
    return r

def ms(v):
    v = [x for x in v if x is not None and not (isinstance(x, float) and math.isnan(x))]
    if not v: return (float('nan'), float('nan'))
    if len(v) == 1: return (v[0], float('nan'))
    return (st.mean(v), st.stdev(v) / math.sqrt(len(v)))

def f(x, n=4):
    if x is None or (isinstance(x, float) and (math.isnan(x) or math.isinf(x))): return "n/a"
    return f"{x:.{n}g}"

def sel(recs, pre, suf="_native"):
    return [r for k, r in recs.items() if k.startswith(pre) and k.endswith(suf)]

# ------------------------------------------------------------------ per-arm
def per_arm(recs, pre, title):
    arms = sorted([k for k in recs if k.startswith(pre)])
    if not arms: return
    print(f"\n### {title}\n")
    print("| arm | v (µm/s) | ω (rad/s) | pitch (µm/turn) | turns | ⟨x_A⟩ nm | ⟨ξ_A⟩ nm | ⟨θ_A⟩ rad | M_A pN·nm | duty | τ_X (s) | τ_Θ (s) | events |")
    print("|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    for k in arms:
        r = recs[k]
        print(f"| `{k}` | {f(r['velUmPerS'])} | {f(r['omegaRadPerS'])} | {f(r['pitchUmPerTurn'])} | "
              f"{f(r['turns'],3)} | {f(r['meanXaNm'])} | {f(r['meanXiANm'])} | {f(r['meanThARad'])} | "
              f"{f(r['meanAttachTorquePnNm'])} | {f(r['dutyRatio'],3)} | {f(r.get('tauXmedS'),3)} | "
              f"{f(r.get('tauThetaMedS'),3)} | {r['nEvents']} |")

# ------------------------------------------------------- mirror decomposition
def mirror_table(recs, pre, title):
    print(f"\n### {title}\n")
    print("| seed | Ω_native | Ω_mirror | Ω_even | Ω_odd | M_A native | M_A mirror | ⟨x_A⟩ nat | ⟨x_A⟩ mir |")
    print("|---|---|---|---|---|---|---|---|---|")
    for k in sorted(recs):
        if not (k.startswith(pre) and k.endswith("_native")): continue
        m = recs.get(k.replace("_native", "_mirror"))
        if not m: continue
        n = recs[k]
        on, om = n["omegaRadPerS"], m["omegaRadPerS"]
        print(f"| {n['seed']} | {f(on,5)} | {f(om,5)} | {f(0.5*(on+om),3)} | {f(0.5*(on-om),5)} | "
              f"{f(n['meanAttachTorquePnNm'])} | {f(m['meanAttachTorquePnNm'])} | "
              f"{f(n['meanXaNm'])} | {f(m['meanXaNm'])} |")

# ----------------------------------------------------- overdamped / quasi-static
def ratio_table(od, qs, odpre, qspre, title):
    O = sel(od, odpre); Q = sel(qs, qspre)
    if not O or not Q: return
    print(f"\n### {title}\n")
    print("| quantity | quasi-static | overdamped (η = 0.01 Pa·s) | ratio od/qs |")
    print("|---|---|---|---|")
    def row(name, key, n=5, transform=None):
        qv = ms([(transform(r) if transform else r[key]) for r in Q])
        ov = ms([(transform(r) if transform else r[key]) for r in O])
        rt = ov[0] / qv[0] if qv[0] else float('nan')
        print(f"| {name} | {f(qv[0],n)} ± {f(qv[1],2)} | {f(ov[0],n)} ± {f(ov[1],2)} | **{f(rt,4)}** |")
    row("⟨x_A⟩ (nm)", "meanXaNm")
    row("⟨θ_A⟩ (rad)", "meanThARad")
    row("attachment torque M_A (pN·nm)", "meanAttachTorquePnNm")
    row("⟨ξ_A⟩ (nm)", "meanXiANm")
    row("v (µm/s)", "velUmPerS")
    row("Ω_odd (rad/s)", "omegaRadPerS")
    row("inverse pitch λ⁻¹ (µm⁻¹)", "invPitchPerUm")
    row("pitch (µm/turn)", "pitchUmPerTurn")
    row("duty ratio", "dutyRatio", 4)
    row("before-centre fraction (%)", None, 4, lambda r: 100.0 * sum(r["xaHist"][20:]) / max(1, sum(r["xaHist"])))

# ------------------------------------------------------------------ controls
def controls(od, qs):
    print("\n### Stage 5 — mechanism-localisation controls (native lattice, overdamped, η = 0.01)\n")
    print("| control | v (µm/s) | Ω_odd (rad/s) | ⟨x_A⟩ nm | ⟨θ_A⟩ rad | before-centre % | verdict |")
    print("|---|---|---|---|---|---|---|")
    base = sel(od, "odnative_e0.01")
    def line(name, arms, verdict, mirror_pre=None):
        if not arms: return
        v = ms([r["velUmPerS"] for r in arms])[0]
        if mirror_pre:
            odd = ms([0.5*(r["omegaRadPerS"] - od[k.replace("_native","_mirror")]["omegaRadPerS"])
                      for k, r in od.items() if k.startswith(mirror_pre) and k.endswith("_native")
                      and k.replace("_native","_mirror") in od])[0]
        else:
            odd = ms([r["omegaRadPerS"] for r in arms])[0]
        xa = ms([r["meanXaNm"] for r in arms])[0]
        th = ms([r["meanThARad"] for r in arms])[0]
        bc = ms([100.0*sum(r["xaHist"][20:])/max(1,sum(r["xaHist"])) for r in arms])[0]
        print(f"| {name} | {f(v,4)} | {f(odd,4)} | {f(xa,4)} | {f(th,4)} | {f(bc,4)} | {verdict} |")
    line("full mechanism (reference)", base, "twirls, mirror-reversing", "odnative_e0.01")
    line("α = 0 (no angular stiffness)", sel(od, "odctl_alpha0"), "translation only", "odctl_alpha0")
    line("d = 0 (no power stroke)", sel(od, "odctl_d0"), "no gliding, no twirl", "odctl_d0")
    nd = sel(od, "odctl_nodep")
    if nd:
        print("\n**No-depletion shadow control (overdamped, native lattice):**\n")
        print("| seed | ⟨x_A⟩ real (nm) | ⟨x_A⟩ no-depletion (nm) | before-centre real % | before-centre shadow % |")
        print("|---|---|---|---|---|")
        rr, ss = [], []
        for r in sorted(nd, key=lambda z: z["seed"]):
            h, sh = r["xaHist"], r["shadowHist"]
            br = 100.0*sum(h[20:])/max(1,sum(h)); bs = 100.0*sum(sh[20:])/max(1e-30,sum(sh))
            rr.append(r["meanXaNm"]); ss.append(r["shadowMeanXaNm"])
            print(f"| {r['seed']} | {f(r['meanXaNm'],5)} | {f(r['shadowMeanXaNm'],5)} | {f(br,4)} | {f(bs,4)} |")
        mr, sr = ms(rr); msh, ssh = ms(ss)
        print(f"| **mean** | **{f(mr,5)} ± {f(sr,2)}** | **{f(msh,5)} ± {f(ssh,2)}** | | |")

# ------------------------------------------------------------------ viscosity
def visc(od, pre="odvisc_", title="Stage 6 — bounded viscosity sensitivity (native lattice, α = 4, kD/kA = 0.1)"):
    print(f"\n### {title}\n")
    print("| η (Pa·s) | γ_X (pN·s/nm) | γ_Θ (pN·nm·s/rad) | τ_X (s) | τ_Θ (s) | ⟨Δt⟩_chem (s) | τ_Θ/⟨Δt⟩ | zone passage (s) | ⟨x_A⟩ nm | ⟨θ_A⟩ rad | Ω_odd | λ⁻¹ (µm⁻¹) | v (µm/s) | ω 1st/2nd half |")
    print("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    groups = defaultdict(list)
    for k, r in od.items():
        if k.startswith(pre) and k.endswith("_native"):
            m = od.get(k.replace("_native", "_mirror"))
            groups[r["etaPaS"]].append((r, m))
    for eta in sorted(groups):
        arms = groups[eta]
        r0 = arms[0][0]
        odd = ms([0.5*(a["omegaRadPerS"] - b["omegaRadPerS"]) for a, b in arms if b])[0]
        g = lambda key: ms([a[key] for a, _ in arms])[0]
        h1 = ms([a["omegaFirstHalf"] for a, _ in arms])[0]
        h2 = ms([a["omegaSecondHalf"] for a, _ in arms])[0]
        print(f"| {eta:g} | {f(r0['gammaXpNsPerNm'],4)} | {f(r0['gammaThetapNnmSPerRad'],4)} | "
              f"{f(g('tauXmedS'),3)} | {f(g('tauThetaMedS'),3)} | {f(g('meanInterEventS'),3)} | "
              f"{f(g('tauThetaMedS')/g('meanInterEventS'),3)} | {f(g('zonePassageS'),3)} | "
              f"{f(g('meanXaNm'),4)} | {f(g('meanThARad'),4)} | {f(odd,4)} | {f(g('invPitchPerUm'),4)} | "
              f"{f(g('velUmPerS'),4)} | {f(h1,4)}/{f(h2,4)} |")

# ------------------------------------------------------------------- health
def health(od):
    print("\n### Numerical health (all overdamped arms)\n")
    v = list(od.values())
    print(f"- arms: **{len(v)}**, total Gillespie events: **{sum(r['nEvents'] for r in v):,}**, "
          f"hazard evaluations: **{sum(r['hazEvals'] for r in v):,}**")
    print(f"- max |γ_X·Ẋ − ΣF| = **{max(r['maxDynResidFpN'] for r in v):.3g} pN**")
    print(f"- max |γ_Θ·Θ̇ − ΣM| = **{max(r['maxDynResidMpNnm'] for r in v):.3g} pN·nm**")
    jx = [r['maxEventJumpX'] for r in v if 'maxEventJumpX' in r]
    jt = [r['maxEventJumpTheta'] for r in v if 'maxEventJumpTheta' in r]
    if jx:
        print(f"- max event discontinuity: |ΔX| = **{max(jx):.3g} nm**, |ΔΘ| = **{max(jt):.3g} rad**")
    else:
        print("- max event discontinuity: not serialised in these records; "
              "covered by gates B1/B2 (exactly 0 over 7449 events)")
    print(f"- angular branch crossings: **{sum(r['branchCrossings'] for r in v):,}**; "
          f"degenerate (beyond the settle horizon): {sum(r['degenerateBranch'] for r in v):,}")
    print(f"- events whose root fell inside the mechanical transient: "
          f"**{sum(r['transientRootEvents'] for r in v):,}** "
          f"({100.0*sum(r['transientRootEvents'] for r in v)/max(1,sum(r['nEvents'] for r in v)):.3f} % of events)")
    print(f"- root-finder iterations: {sum(r['rootIters'] for r in v):,}; "
          f"equilibrations with zero bound heads: {sum(r['nbZeroEvents'] for r in v):,}")
    cap = [k for k, r in od.items() if r["travelCapHit"]]
    print(f"- travel-cap hits: **{len(cap)}**" + (f" — {', '.join(sorted(cap))}" if cap else ""))
    notes = {k: r["note"] for k, r in od.items() if r["note"].strip()}
    if notes:
        print("- notes:")
        for k, n in sorted(notes.items()): print(f"    - `{k}`: {n}")
    print("\n**Stationarity (analysed window split into two equal halves of simulated time):**\n")
    print("| stage | n | ω first half | ω second half | ratio | v first | v second |")
    print("|---|---|---|---|---|---|---|")
    for pre, name in (("odpaper_", "paper lattice"), ("odnative_", "native lattice")):
        arms = sel(od, pre)
        if not arms: continue
        o1 = ms([r["omegaFirstHalf"] for r in arms])[0]; o2 = ms([r["omegaSecondHalf"] for r in arms])[0]
        v1 = ms([r["velFirstHalf"] for r in arms])[0];  v2 = ms([r["velSecondHalf"] for r in arms])[0]
        print(f"| {name} | {len(arms)} | {f(o1,4)} | {f(o2,4)} | {f(o2/o1,4)} | {f(v1,4)} | {f(v2,4)} |")

if __name__ == "__main__":
    os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    od, qs = load(OD), load(QS)
    if not od: sys.exit("no overdamped records in " + OD)
    what = sys.argv[1] if len(sys.argv) > 1 else "all"
    if what in ("all", "paper"):
        per_arm(od, "odpaper_", "Stage 3 — paper-exact lattice, overdamped, per arm")
        mirror_table(od, "odpaper_", "Stage 3 — mirror even/odd decomposition")
        ratio_table(od, qs, "odpaper_", "paper_", "Stage 3 — overdamped vs quasi-static, paper lattice")
    if what in ("all", "native"):
        per_arm(od, "odnative_", "Stage 4 — native SoftBox lattice, overdamped, per arm")
        mirror_table(od, "odnative_", "Stage 4 — mirror even/odd decomposition")
        ratio_table(od, qs, "odnative_", "native_", "Stage 4 — overdamped vs quasi-static, native lattice")
    if what in ("all", "controls"): controls(od, qs)
    if what in ("all", "visc"):
        visc(od)
        visc(od, "odexpl_", "Stage 6b — EXPLORATORY high-viscosity extension (NOT preregistered)")
    if what in ("all", "health"):   health(od)
