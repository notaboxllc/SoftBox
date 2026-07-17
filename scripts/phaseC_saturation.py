#!/usr/bin/env python3
"""Phase C: saturation fit + explicit-vs-calibrated comparison over the GPU density sweep.
Reads RUN_LOGS/explicit_completemat/COMPLETEMAT_SWEEP.csv; writes docs/matsoa/EXPLICIT_DENSITY_SWEEP_FINDINGS.md.
Fits |v|(rho) = vmax*rho/(Krho+rho) (Michaelis-Menten) per model without scipy (coordinate descent),
reports vmax, half-saturation Krho, rho@90%/95% plateau, a descriptive plateau criterion, and the ratio table."""
import csv, os, math

D = "RUN_LOGS/explicit_completemat"
rows = {}
with open(os.path.join(D, "COMPLETEMAT_SWEEP.csv")) as f:
    for r in csv.DictReader(f):
        rows.setdefault(r["model"], []).append(r)

def mm_fit(rho, v):
    """|v| = vmax*rho/(K+rho). Coordinate descent on SSE (no scipy)."""
    v = [abs(x) for x in v]
    vmax = max(v) * 1.3 if v else 1.0
    K = (max(rho) + min(rho)) / 2 if rho else 1.0
    def sse(vm, k): return sum((vm * r / (k + r) - y) ** 2 for r, y in zip(rho, v))
    step_vm, step_k = vmax * 0.5, K * 0.5
    for _ in range(4000):
        best = sse(vmax, K); improved = False
        for dvm in (step_vm, -step_vm):
            if vmax + dvm > 0 and sse(vmax + dvm, K) < best: vmax += dvm; best = sse(vmax, K); improved = True
        for dk in (step_k, -step_k):
            if K + dk > 0 and sse(vmax, K + dk) < best: K += dk; best = sse(vmax, K); improved = True
        if not improved: step_vm *= 0.7; step_k *= 0.7
        if step_vm < 1e-6 and step_k < 1e-6: break
    # residual RMS
    rms = math.sqrt(sse(vmax, K) / len(v)) if v else 0
    return vmax, K, rms

out = ["# Explicit density-saturation sweep (Phase C) — GPU gliding velocity, explicit vs calibrated\n",
       "Both models run device-resident on the GPU at matched geometry (same buildGlide2D density → same N/area),",
       "free binding, dt=2.5e-6. Velocity = LS slope of the resident filament centroid·b̂ over the measured window",
       "(negative = pointed-first = correct). Signed velocity reported; |speed| used for the saturation fit.\n"]

# per-model observable table
out += ["## C3. Primary observables by density",
        "| model | density | N | vel µm/s (±SE) | |speed| | avgBound | continuity | boundFrac | netForce pN | invalid |",
        "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|"]
for m in rows:
    for r in rows[m]:
        out.append(f"| {m} | {r['density']} | {r['N']} | {float(r['vel_umPerS']):+.3f}±{float(r['vel_se']):.3f} | "
                   f"{float(r['speed_umPerS']):.3f} | {float(r['avgBound']):.2f} | {float(r['continuity']):.2f} | "
                   f"{float(r['boundFrac']):.4f} | {float(r['netForce_pN']):.2f} | {r['invalid']} |")

# saturation fit per model
out += ["\n## C4. Saturation characterization (|v|(ρ) = v_max·ρ/(K_ρ+ρ))"]
fits = {}
for m in rows:
    rho = [float(r["density"]) for r in rows[m]]
    v = [float(r["speed_umPerS"]) for r in rows[m]]
    vmax, K, rms = mm_fit(rho, v)
    fits[m] = (vmax, K)
    rho90 = 9 * K   # v/vmax=0.9 -> rho=9K ; 0.95 -> 19K
    rho95 = 19 * K
    # descriptive plateau: first density where |Δspeed| across consecutive increments < 5%
    plateau_at = None
    sr = sorted(rows[m], key=lambda r: float(r["density"]))
    for i in range(1, len(sr)):
        v0, v1 = float(sr[i-1]["speed_umPerS"]), float(sr[i]["speed_umPerS"])
        if v0 > 0 and abs(v1 - v0) / v0 < 0.05:
            plateau_at = float(sr[i]["density"]); break
    out.append(f"- **{m}**: v_max ≈ {vmax:.3f} µm/s, half-saturation K_ρ ≈ {K:.0f} /µm², "
               f"ρ@90%plateau ≈ {rho90:.0f}, ρ@95% ≈ {rho95:.0f}, fit RMS {rms:.3f} µm/s; "
               f"descriptive 5%-plateau onset ≈ {plateau_at if plateau_at else '(not reached in sampled range)'} /µm².")

# C5 explicit/calibrated comparison by density
out += ["\n## C5. Explicit vs calibrated speed ratio by density",
        "| density | explicit |speed| | calibrated |speed| | exp/cal ratio | exp avgBound | cal avgBound |",
        "|---:|---:|---:|---:|---:|---:|"]
def by_dens(m):
    return {float(r["density"]): r for r in rows.get(m, [])}
E, C = by_dens("explicit-s2-l40"), by_dens("calibrated-s2-l40")
for d in sorted(set(E) & set(C)):
    e, c = E[d], C[d]
    es, cs = float(e["speed_umPerS"]), float(c["speed_umPerS"])
    out.append(f"| {d:.0f} | {es:.3f} | {cs:.3f} | {es/cs:.2f}× | {float(e['avgBound']):.2f} | {float(c['avgBound']):.2f} |")

if "explicit-s2-l40" in fits and "calibrated-s2-l40" in fits:
    ev, ek = fits["explicit-s2-l40"]; cv, ck = fits["calibrated-s2-l40"]
    out += [f"\n- Plateau speed: explicit v_max ≈ {ev:.3f} vs calibrated {cv:.3f} µm/s (ratio {ev/cv:.2f}×).",
            f"- Half-saturation: explicit K_ρ ≈ {ek:.0f} vs calibrated {ck:.0f} /µm² (approach steepness).",
            "- Interpretation: differences that track avgBound/continuity are binding/chemistry-driven, not purely mechanical."]

os.makedirs("docs/matsoa", exist_ok=True)
with open("docs/matsoa/EXPLICIT_DENSITY_SWEEP_FINDINGS.md", "w") as f:
    f.write("\n".join(out) + "\n")
print("wrote docs/matsoa/EXPLICIT_DENSITY_SWEEP_FINDINGS.md")
print("\n".join(out))
