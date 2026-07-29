#!/usr/bin/env python3
"""Analysis for the Vilfan axial-Brownian study.

Reads RUN_LOGS/vilfan_brownian/*.json (Brownian arms) and DRAG_RECORDS/*.json (the committed
deterministic finite-drag baseline). Pure stdlib, CPU only.
"""
import json, glob, math, os, sys, statistics as st
from collections import defaultdict

BR, DR = "RUN_LOGS/vilfan_brownian", "DRAG_RECORDS"

def load(d):
    r = {}
    for p in sorted(glob.glob(os.path.join(d, "*.json"))):
        with open(p) as f: j = json.load(f)
        r[j["armId"]] = j
    return r

def ms(v):
    v = [x for x in v if x is not None and not (isinstance(x, float) and math.isnan(x))]
    if not v: return (float('nan'), float('nan'), 0)
    if len(v) == 1: return (v[0], float('nan'), 1)
    return (st.mean(v), st.stdev(v) / math.sqrt(len(v)), len(v))

def f(x, n=4):
    if x is None or (isinstance(x, float) and (math.isnan(x) or math.isinf(x))): return "n/a"
    return f"{x:.{n}g}"

def before(r):
    h = r["xaHist"]; n = sum(h)
    return 100.0 * sum(h[20:]) / max(1, n)

def shadow_before(r):
    h = r.get("shadowHist") or []
    s = sum(h)
    return 100.0 * sum(h[20:]) / s if s > 0 else float('nan')

def sel(recs, pre, suf="_native"):
    return [r for k, r in recs.items() if k.startswith(pre) and k.endswith(suf)]

def odd_pairs(recs, pre):
    """Omega_odd per matched seed pair."""
    out = []
    for k, n in sorted(recs.items()):
        if not (k.startswith(pre) and k.endswith("_native")): continue
        m = recs.get(k.replace("_native", "_mirror"))
        if m: out.append((n, m, 0.5 * (n["omegaRadPerS"] - m["omegaRadPerS"]),
                          0.5 * (n["omegaRadPerS"] + m["omegaRadPerS"])))
    return out

# ------------------------------------------------------------------ per arm
def per_arm(recs, pre, title):
    arms = sorted(k for k in recs if k.startswith(pre))
    if not arms: return
    print(f"\n### {title}\n")
    print("| arm | v (µm/s) | ω (rad/s) | ⟨x_A⟩ nm | before % | ⟨θ_A⟩ rad | M_A pN·nm | net (nm) | max back (nm) | Nb | events |")
    print("|---|---|---|---|---|---|---|---|---|---|---|")
    for k in arms:
        r = recs[k]
        print(f"| `{k}` | {f(r['velUmPerS'])} | {f(r['omegaRadPerS'])} | {f(r['meanXaNm'])} | "
              f"{f(before(r),4)} | {f(r['meanThARad'])} | {f(r['meanAttachTorquePnNm'])} | "
              f"{f(r.get('netFwdNm'),5)} | {f(r.get('maxBackNm'),3)} | {f(r['medianNb'],3)} | {r['nEvents']} |")

# ------------------------------------------------- recrossing diagnostics
def recross(recs, pre, title):
    arms = sel(recs, pre)
    if not arms: return
    print(f"\n### {title}\n")
    bands = arms[0].get("recBands") or []
    print("| hysteresis band (nm) | forward crossings | backward crossings | per zone passage |")
    print("|---|---|---|---|")
    # zone passages = net travel / zone period
    L = arms[0]["zonePeriodNm"]
    passes = sum(abs(r.get("netFwdNm", 0)) for r in arms) / L
    for i, b in enumerate(bands):
        fw = sum(r["recFwd"][i] for r in arms); bw = sum(r["recBwd"][i] for r in arms)
        note = "  <- raw jitter, not physical" if b == 0 else ("  <- one actin subunit" if abs(b - 2.7) < .01 else "")
        print(f"| {b:g}{note} | {fw} | {bw} | {bw/max(1,passes):.3g} |")
    raw = sum(r.get("zoneCentreCrossRaw", 0) for r in arms)
    pl = sum(r.get("pathLenNm", 0) for r in arms); net = sum(abs(r.get("netFwdNm", 0)) for r in arms)
    print(f"\n- raw zone-centre sign changes: **{raw:,}** (jitter-dominated — see the band-0 row)")
    print(f"- net forward travel {net:,.0f} nm over {passes:.0f} zone passages; "
          f"max backward excursion **{max(r.get('maxBackNm',0) for r in arms):.2f} nm** "
          f"= {100*max(r.get('maxBackNm',0) for r in arms)/L:.2f} % of the zone period")
    print(f"- sampled axial path length {pl:,.0f} nm vs net {net:,.0f} nm (ratio {pl/max(1,net):.1f}). "
          "Path length is RESOLUTION-DEPENDENT (an OU path has unbounded variation); it is quoted at "
          "the production substep and must not be compared across step sizes.")

# ------------------------------------------------- Brownian vs deterministic
def compare(br, dr, pre, title):
    B = sel(br, pre); D = sel(dr, "odnative_e0.01")
    if not B or not D: return
    print(f"\n### {title}\n")
    print("| quantity | deterministic drag | axial Brownian | ratio | difference |")
    print("|---|---|---|---|---|")
    def row(name, fn, n=5):
        d = ms([fn(r) for r in D]); b = ms([fn(r) for r in B])
        rt = b[0]/d[0] if d[0] else float('nan')
        print(f"| {name} | {f(d[0],n)} ± {f(d[1],2)} | {f(b[0],n)} ± {f(b[1],2)} | **{f(rt,4)}** | {f(b[0]-d[0],3)} |")
    row("⟨x_A⟩ (nm)", lambda r: r["meanXaNm"])
    row("before-centre (%)", before, 4)
    row("⟨θ_A⟩ (rad)", lambda r: r["meanThARad"])
    row("attachment torque M_A (pN·nm)", lambda r: r["meanAttachTorquePnNm"])
    row("⟨ξ_A⟩ (nm)", lambda r: r["meanXiANm"])
    row("v (µm/s)", lambda r: r["velUmPerS"])
    row("inverse pitch λ⁻¹ (µm⁻¹)", lambda r: r["invPitchPerUm"])
    row("duty ratio", lambda r: r["dutyRatio"], 4)
    row("median bound heads", lambda r: r["medianNb"], 4)
    # Omega_odd from matched pairs
    ob = ms([o for _, _, o, _ in odd_pairs(br, pre)])
    od = ms([o for _, _, o, _ in odd_pairs(dr, "odnative_e0.01")])
    if ob[2] and od[2]:
        print(f"| **Ω_odd (rad/s)** | **{f(od[0],5)} ± {f(od[1],2)}** | **{f(ob[0],5)} ± {f(ob[1],2)}** | "
              f"**{f(ob[0]/od[0],4)}** | {f(ob[0]-od[0],3)} |")
        eb = ms([e for _, _, _, e in odd_pairs(br, pre)])
        print(f"| Ω_even (rad/s) | {f(od[0]*0,3)} | {f(eb[0],3)} ± {f(eb[1],2)} | — | — |")

# ------------------------------------------------- depletion (real - shadow)
def depletion(br, pre="prodshadow"):
    arms = [r for k, r in br.items() if k.startswith(pre)]
    if not arms: return
    print("\n### Real vs no-depletion shadow attachment bias (the load-bearing control)\n")
    print("| seed | ⟨x_A⟩ real (nm) | ⟨x_A⟩ shadow (nm) | Δ_depletion (nm) | before real % | before shadow % |")
    print("|---|---|---|---|---|---|")
    ds = []
    for r in sorted(arms, key=lambda z: z["seed"]):
        d = r["meanXaNm"] - r["shadowMeanXaNm"]; ds.append(d)
        print(f"| {r['seed']} | {f(r['meanXaNm'],5)} | {f(r['shadowMeanXaNm'],5)} | {f(d,5)} | "
              f"{f(before(r),4)} | {f(shadow_before(r),4)} |")
    m, s, n = ms(ds)
    rm = ms([r["meanXaNm"] for r in arms]); sm = ms([r["shadowMeanXaNm"] for r in arms])
    print(f"| **mean (n={n})** | **{f(rm[0],5)} ± {f(rm[1],2)}** | **{f(sm[0],5)} ± {f(sm[1],2)}** | "
          f"**{f(m,5)} ± {f(s,2)}** | | |")
    if s and not math.isnan(s):
        print(f"\n**Δ_depletion = {m:.4f} ± {s:.4f} nm — resolved at {abs(m/s):.1f}σ.**")

# ------------------------------------------------------------------ controls
def controls(br):
    print("\n### Stage 9 — controls, all under identical axial-Brownian dynamics\n")
    print("| control | v (µm/s) | Ω_odd (rad/s) | Ω_even | ⟨x_A⟩ nm | before % | verdict |")
    print("|---|---|---|---|---|---|---|")
    for pre, name, verdict in (
            ("prod_in", "full mechanism", "twirls, mirror-reversing"),
            ("ctl_alpha0", "α = 0", "no angular energy ⇒ no twirl"),
            ("ctl_d0", "d = 0", "no stroke ⇒ no gliding, no twirl"),
            ("ctl_achiral", "achiral lattice (ϑ₀ = 0)", "no chirality ⇒ no mirror-odd rotation"),
            ("ctl_broff", "Brownian OFF", "deterministic positive control")):
        arms = sel(br, pre)
        if not arms: continue
        pr = odd_pairs(br, pre)
        v = ms([r["velUmPerS"] for r in arms])[0]
        o = ms([x for _, _, x, _ in pr]); e = ms([x for _, _, _, x in pr])
        xa = ms([r["meanXaNm"] for r in arms])[0]; bc = ms([before(r) for r in arms])[0]
        print(f"| {name} | {f(v,4)} | {f(o[0],4)} ± {f(o[1],2)} | {f(e[0],3)} | {f(xa,4)} | {f(bc,4)} | {verdict} |")

# ------------------------------------------------------------------ sizing
def sizing(br, pre="pilot_in"):
    arms = sel(br, pre)
    if len(arms) < 2: return
    print("\n### Stage 7 — pilot variance and campaign sizing\n")
    xa = [r["meanXaNm"] for r in arms]; bc = [before(r) for r in arms]
    th = [r["meanThARad"] for r in arms]
    pr = odd_pairs(br, pre); od = [x for _, _, x, _ in pr]
    def line(nm, v):
        m, s, n = ms(v)
        sd = s * math.sqrt(n) if n > 1 else float('nan')
        print(f"| {nm} | {f(m,5)} | {f(sd,3)} | {f(s,3)} | {n} |")
    print("| quantity | pilot mean | between-seed sd | SEM | n |")
    print("|---|---|---|---|---|")
    line("⟨x_A⟩ (nm)", xa); line("before-centre (%)", bc)
    line("⟨θ_A⟩ (rad)", th); line("Ω_odd (rad/s)", od)
    # power: detect a 25% change in <x_A> from the deterministic 1.6445
    m, s, n = ms(xa); sd = s * math.sqrt(n) if n > 1 else float('nan')
    if sd and not math.isnan(sd) and sd > 0:
        delta = 0.25 * 1.6445
        need = 2 * ((1.96 + 0.84) * sd / delta) ** 2   # two-sample, two-sided 95%, 80% power
        print(f"\n- between-seed sd(⟨x_A⟩) = **{sd:.4f} nm**. To detect the H1/H2 boundary "
              f"(a 25 % change = {delta:.3f} nm) at two-sided 95 % / 80 % power needs "
              f"**n ≈ {math.ceil(need)} seeds per arm**.")
        for pct in (10, 25):
            d2 = pct/100.0 * 1.6445
            print(f"    - a {pct} % change ({d2:.3f} nm) needs n ≈ {math.ceil(2*((1.96+0.84)*sd/d2)**2)}")

# ------------------------------------------------------------------- health
def health(br):
    v = list(br.values())
    if not v: return
    print("\n### Numerical health\n")
    print(f"- arms **{len(v)}**, events **{sum(r['nEvents'] for r in v):,}**, "
          f"stochastic substeps **{sum(r.get('nSubsteps',0) for r in v):,}**, "
          f"bridge draws **{sum(r.get('nBridgeDraws',0) for r in v):,}**")
    print(f"- max event discontinuity |ΔX| = **{max(r.get('maxEventJumpX',0) for r in v):.3g} nm**, "
          f"|ΔΘ| = **{max(r.get('maxEventJumpTheta',0) for r in v):.3g} rad**")
    print(f"- max roll closure residual |γ_Θ Θ̇ − ΣM| = **{max(r['maxDynResidMpNnm'] for r in v):.3g} pN·nm**")
    print(f"- free-diffusion substeps (Nb = 0): **{sum(r.get('nFreeDiffSteps',0) for r in v):,}**; "
          f"zero-bound equilibrations {sum(r['nbZeroEvents'] for r in v):,}")
    print(f"- angular branch crossings: **{sum(r['branchCrossings'] for r in v):,}**")
    caps = [k for k, r in br.items() if r["travelCapHit"]]
    print(f"- travel-cap hits: {len(caps)}" + (f" — {', '.join(sorted(caps))}" if caps else ""))
    notes = {k: r["note"] for k, r in br.items() if r["note"].strip()}
    for k, n in sorted(notes.items()): print(f"    - `{k}`: {n}")

if __name__ == "__main__":
    os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    br, dr = load(BR), load(DR)
    if not br: sys.exit("no Brownian records")
    w = sys.argv[1] if len(sys.argv) > 1 else "all"
    if w in ("all", "pilot"):
        per_arm(br, "pilot_in", "Stage 6 — pilot, independent axial noise")
        per_arm(br, "pilot_cn", "Stage 6 — pilot, COMMON axial noise (correctness control, not an independent sample)")
        recross(br, "pilot_in", "Stage 5 — target-zone crossing and recrossing diagnostics (pilot)")
        sizing(br)
        compare(br, dr, "pilot_in", "Pilot: deterministic drag vs axial Brownian")
    if w in ("all", "prod"):
        per_arm(br, "prod_in", "Stage 8 — production, independent axial noise")
        recross(br, "prod_in", "Stage 5 — recrossing diagnostics (production)")
        compare(br, dr, "prod_in", "Stage 8 — deterministic drag vs axial Brownian (production)")
        depletion(br)
    if w in ("all", "controls"): controls(br)
    if w in ("all", "health"):   health(br)
