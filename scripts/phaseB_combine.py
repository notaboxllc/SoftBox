#!/usr/bin/env python3
"""Phase B: combine explicit + calibrated no-cull free-binding throughput CSVs into the cost table + scaling analysis.
Reads RUN_LOGS/explicit_completemat/COMPLETEMAT_THROUGHPUT.csv (explicit Phase A) and
COMPLETEMAT_THROUGHPUT_CAL.csv (calibrated arm); writes docs/matsoa/EXPLICIT_VS_CALIBRATED_COST.md."""
import csv, os, sys
from statistics import median

D = "RUN_LOGS/explicit_completemat"
def load(fn):
    rows = {}
    with open(os.path.join(D, fn)) as f:
        for r in csv.DictReader(f):
            rows[int(r["N"])] = r
    return rows

exp = load("COMPLETEMAT_THROUGHPUT.csv")
cal = load("COMPLETEMAT_THROUGHPUT_CAL.csv")
Ns = sorted(set(exp) & set(cal))
if not Ns:
    sys.exit("no matched N between explicit and calibrated CSVs")

def fit(xs, ys):  # least-squares y = a + b x
    n = len(xs); sx = sum(xs); sy = sum(ys); sxx = sum(x*x for x in xs); sxy = sum(x*y for x,y in zip(xs,ys))
    b = (n*sxy - sx*sy) / (n*sxx - sx*sx); a = (sy - b*sx)/n
    return a, b

out = ["# Explicit vs Calibrated computational cost (Phase B) — matched no-cull free-binding throughput\n",
       "Both models: genuine free binding (all unbound at t=0), NO-CULL (all N processed), production-residency GPU graph,",
       "CPU-analytic runner, identical densities/dt (2.5e-6)/seed. Explicit Step-10 = matS2SolveStep (14-DOF beam);",
       "calibrated Step-10 = matStep7 (analytic 5-DOF movable pivot). Shared stages (bond/CSR/filament/reduce) identical.\n",
       "## B1. Primary comparison table",
       "| N | density | cal CPU ms/step | exp CPU ms/step | exp/cal CPU | cal GPU ms/step | exp GPU ms/step | exp/cal GPU | cal meanBound | exp meanBound |",
       "|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|"]
for N in Ns:
    e, c = exp[N], cal[N]
    ecpu, ccpu = float(e["cpu_ms_step_med"]), float(c["cpu_ms_step_med"])
    egpu, cgpu = float(e["gpu_ms_step_med"]), float(c["gpu_ms_step_med"])
    dens = e["density"]
    out.append(f"| {N} | {dens} | {ccpu:.3f} | {ecpu:.3f} | {ecpu/ccpu:.2f}× | {cgpu:.3f} | {egpu:.3f} | {egpu/cgpu:.2f}× | {c['meanBound']} | {e['meanBound']} |")

# memory + GPU speedup + dominant stage
out += ["\n## Memory & GPU speedup",
        "| N | exp GPU speedup | cal GPU speedup | exp state MiB | cal state MiB | exp bytes/motor | cal bytes/motor |",
        "|---:|---:|---:|---:|---:|---:|---:|"]
for N in Ns:
    e, c = exp[N], cal[N]
    em = float(e["gpu_beamMiB"]); cm = float(c["gpu_stateMiB"])
    out.append(f"| {N} | {float(e['speedup']):.1f}× | {float(c['speedup']):.1f}× | {em:.2f} | {cm:.2f} | {em*1048576/N:.0f} | {cm*1048576/N:.0f} |")

# B2 scaling
out += ["\n## B2. Cost scaling (ms/step = a + b·N, least squares over measured N)"]
for label, rows, gk, ck in [("explicit", exp, "gpu_ms_step_med", "cpu_ms_step_med"), ("calibrated", cal, "gpu_ms_step_med", "cpu_ms_step_med")]:
    xs = [float(rows[N]["N"]) for N in Ns]
    ag, bg = fit(xs, [float(rows[N][gk]) for N in Ns])
    ac, bc = fit(xs, [float(rows[N][ck]) for N in Ns])
    out.append(f"- **{label} GPU**: {ag:.4f} + {bg:.3e}·N ms  ⇒ launch floor ≈ {ag:.3f} ms, per-1000-motors ≈ {bg*1000:.4f} ms")
    out.append(f"- **{label} CPU**: {ac:.4f} + {bc:.3e}·N ms  ⇒ intercept ≈ {ac:.3f} ms, per-1000-motors ≈ {bc*1000:.4f} ms")

# crossover + ratios low/high N
loN, hiN = Ns[0], Ns[-1]
def gpu_cross(rows_g, rows_c):
    for N in Ns:
        if float(rows_g[N]["cpu_ms_step_med"]) > float(rows_g[N]["gpu_ms_step_med"]):
            return N
    return None
out += ["\n## B2. crossover + explicit/calibrated ratio at low vs high N"]
for label, rows in [("explicit", exp), ("calibrated", cal)]:
    cx = gpu_cross(rows, rows)
    out.append(f"- {label}: GPU faster than CPU from N≈{cx if cx else '(all measured N)'}")
out.append(f"- explicit/calibrated GPU cost ratio: N={loN} → {float(exp[loN]['gpu_ms_step_med'])/float(cal[loN]['gpu_ms_step_med']):.2f}×, N={hiN} → {float(exp[hiN]['gpu_ms_step_med'])/float(cal[hiN]['gpu_ms_step_med']):.2f}×")
out.append(f"- explicit/calibrated CPU cost ratio: N={loN} → {float(exp[loN]['cpu_ms_step_med'])/float(cal[loN]['cpu_ms_step_med']):.2f}×, N={hiN} → {float(exp[hiN]['cpu_ms_step_med'])/float(cal[hiN]['cpu_ms_step_med']):.2f}×")

os.makedirs("docs/matsoa", exist_ok=True)
with open("docs/matsoa/EXPLICIT_VS_CALIBRATED_COST.md", "w") as f:
    f.write("\n".join(out) + "\n")
print("wrote docs/matsoa/EXPLICIT_VS_CALIBRATED_COST.md")
print("\n".join(out))
