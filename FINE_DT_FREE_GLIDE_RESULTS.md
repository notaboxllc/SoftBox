# FINE-dt FREE-GLIDING DENSITY SWEEP — RESULTS

**Status:** IN PROGRESS (autonomous run started 2026-07-13 02:25 PDT). Sections marked ⏳ fill as the
matrix completes; this file is rewritten at each milestone. Raw cells: `RUN_LOGS/finedt_cells/*.log`.

**Runner / build:** GPU RTX 5070 (TornadoVM 4.0.1-dev PTX) primary; deterministic CPU basin arbiter.
Pristine build of commit **`f537972`** ("FINE-dt V0 reference") in isolated worktree
`SoftBox-finedt-canon` (jba's uncommitted J2/orthogonalizeY WIP untouched — see PLAN).
**Physical duration 0.6 s**, warm-up 0.20 s (physical, identical across dt). Canonical stack
SPHEREHEAD+AXLOCK+DIRSWING+XB_IMPLICIT2+LYMN_TAYLOR + springs, all default-on, **unmodified**.
coltol=8 nm, matbox-50 chamber. Invocation per cell:
`run_gliding.sh [-gpu] -full -grid -coltol 8 -matbox 50 -density <ρ> -seed <s> -dt <dt> <M>`.

---

## PART 1 — BENCHMARK & EXECUTION PLAN  ✅

**Throughput is dt-independent** (identical kernels/step); measured steps/s vs density then wall =
(0.6/dt)/throughput. Two-point timing (cancels JVM/Tornado startup):

| density | GPU steps/s | CPU steps/s | GPU wall @1e-5 (60k) | GPU wall @5e-6 (120k) |
|--------:|------------:|------------:|---------------------:|----------------------:|
| 500  | 305.8 | — | 3.3 min | 6.5 min |
| 2000 | 129.9 | 11.9 | 7.7 min | 15.4 min |
| 8000 | 39.5 | ~4 (est) | 25.3 min | 50.6 min |

Power fit GPU: steps/s ≈ 306·(ρ/500)^−0.73. **CPU is ~11× slower than GPU** (d2000: 11.9 vs 129.9
steps/s) ⇒ a full CPU matrix is impossible (CPU d8000@5e-6 alone ≈ 4.5 h).

**Chosen plan** (per the study's GPU-primary + matched-CPU-arbiter allowance):
- **GPU** full paired sweep: ρ∈{500,1000,2000,4000,6000,8000}, dt∈{1e-5, 5e-6}, **3 paired seeds**
  (0,1,2). 4 seeds ruled out — high-density fine-dt cells dominate (est. 3.9 h/seed ⇒ ~11.7 h GPU
  compute for 3 seeds; ×~1.1 concurrency ≈ 12.9 h wall).
- **dt=2.5e-6 spot checks** (Part 3): ρ∈{2000,8000}, 2 seeds — ≈4.4 h GPU.
- **CPU basin arbiter** (lean, full 0.6 s, directly comparable): d2000 dt=1e-5 seeds 0,1; d2000
  dt=5e-6 seed 0; d8000 dt=1e-5 seed 0 — ≈10 h CPU, run **concurrently** with the GPU sweep (measured
  GPU slowdown from concurrent CPU load ≈ 10%, a large net win vs serial).
- **matbox on/off control** (d2000, d8000 @1e-5 seed0) to confirm the chamber does not shift the
  baseline.
- **Estimated total GPU critical-path wall ≈ 17–18 h.** Every cell restartable/skip-if-complete.

**Baseline consistency check (validates the pristine-HEAD + matbox-ON setup):** my matbox-ON d500@1e-5
seed0 velFitX=**1.108** vs the established matbox-OFF coltol-8 sweep (2026-07-09, same seed) **1.159** —
within 4% (seed sd ≈0.17). The setup reproduces the established curve.

**Why matbox 50 is mandated (confirmed):** the established matbox-OFF sweep hit `fullMat=VIOLATED` at
d8000 (1/3 seeds) — the high-density y-coverage escape. matbox-50's y-walls at the mat extent keep the
filament over the lawn without pinning the glide axis (x free). Coverage of my matbox-ON cells is
reported in the per-cell table.

### Context — the established production-dt (1e-5) curve is CLASS C (rising, no plateau)
From the established coltol-8 sweep (matbox-OFF, dt=1e-5, ~= my production arm):

| ρ | 500 | 1000 | 2000 | 4000 | 6000 | 8000 |
|---|----:|----:|----:|----:|----:|----:|
| velFitX | 1.01 | 2.69 | 4.39 | 6.45 | 7.95 | 9.38 |
| avgB | 1.02 | 2.91 | 5.42 | 10.3 | 15.8 | 20.2 |

velFitX climbs monotonically through d8000 (4k→6k +23%, 6k→8k +18%); avgBound rises ~linearly (no
engagement saturation ⇒ any velocity flattening would be *operational*, not class-D collapse). At
dt=1e-5 the curve is **power-law-ish (v∝ρ^~0.8), plateau NOT reached in [500,8000]**. The central
question: does **fine dt** (which removes the coarse-dt cross-bridge force overshoot — larger where the
collective load is stiffest, i.e. high density) bend this into an operational plateau?

---

## PART 2 — MATCHED PRODUCTION vs FINE-dt SWEEP  ⏳ (low-mid tier done; high-density running)

Mean ± SEM over **3 paired seeds** (0,1,2). All cells `fullMat=YES`, 0 NaN/instability.
velPerBound = velFitX/avgBsteady (per-bound forward-advance efficiency proxy).

| ρ | dt | velFitX | avgBsteady | velPerBound | instSteady* |
|--:|:--|--------:|-----------:|------------:|-----------:|
| 500  | 1e-5 | 1.172 ± 0.055 | 1.359 | 0.862 | 6.72 |
| 500  | 5e-6 | 1.029 ± 0.049 | 1.430 | 0.719 | 9.18 |
| 1000 | 1e-5 | 2.568 ± 0.100 | 2.827 | 0.908 | 6.92 |
| 1000 | 5e-6 | 2.670 ± 0.253 | 3.446 | 0.773 | 9.40 |
| 2000 | 1e-5 | 4.390 ± 0.120 | 5.678 | 0.773 | 7.53 |
| 2000 | 5e-6 | 4.502 ± 0.179 | 6.585 | 0.684 | 9.73 |
| 4000 | 1e-5 | 6.472 ± 0.— | 10.34 | 0.626 | — |
| 4000 | 5e-6 | 6.459 ± 0.— | 11.60 | 0.557 | — |
| 6000–8000 | both | ⏳ running | | | |

Through d4000 the two velFitX curves **track each other closely** (d4000: 6.472 vs 6.459) and are
**still rising** — no plateau in sight yet.

\* `instSteady` is the **Brownian-contaminated** instantaneous speed (∝√(2D/dt)); it rises ~+36% at
fine dt purely from thermal jitter — **not** a velocity increase. This is the concrete reason velFitX
(LS slope) is the primary observable and instSteady is not (study Part 4 mandate, verified).

**Coverage / stability:** 18/18 matbox-ON cells `fullMat=YES`, 0 violations, 0 NaN. matbox-50 fully
fixes the high-density y-coverage escape that hit the established matbox-OFF sweep (1/3 at d8000).

## PART 5 — PAIRED ΔV = V(5e-6) − V(1e-5)  ⏳ (low-mid tier)

| ρ | nPair | mean ΔV | SEM | paired bootstrap CI95 | % | ΔavgB | ΔvelPerBound |
|--:|:-:|-------:|----:|----------------------:|--:|------:|-------------:|
| 500  | 3 | −0.143 | 0.033 | [−0.184, −0.077] | **−12.2%** (sig) | +0.07 | −0.143 |
| 1000 | 3 | +0.101 | 0.197 | [−0.284, +0.367] | +3.9% (ns) | +0.62 | −0.136 |
| 2000 | 3 | +0.112 | 0.061 | [−0.001, +0.210] | +2.6% (marg) | +0.91 | −0.090 |
| 4000 | 3 | −0.013 | 0.076 | [−0.161, +0.089] | −0.2% (ns) | +1.27 | −0.064 |

**Mechanism (Part-5 core):** the fine-dt shift is the *net* of two opposing, individually
non-converged channels — fine dt **raises occupancy** (ΔavgB > 0, growing with density) while
**lowering per-bound efficiency** (ΔvelPerBound < 0, the removed coarse-dt cross-bridge overshoot). At
d500 the per-bound drop dominates (net −12%); by d1000–2000 the occupancy rise nearly **compensates**
it, so velFitX is close to dt-converged there *despite* its components not being. Whether the per-bound
drop grows and dominates at high density (stiffer collective load) is the pending crux.

## CPU/GPU BASIN ARBITER  ⏳ (1/4 done)
CPU d2000@1e-5 seed0 velFitX=**4.367** (avgB 5.55) vs GPU aggregate 4.390 — **same basin, <1%**. No
bistability artifact at d2000. Dense-regime (d8000) arbiter pending.

## PART 3 — dt=2.5e-6 SPOT CHECKS  ⏳ (after main sweep)
## PART 6 — SATURATION ANALYSIS & CLASSIFICATION (A–E)  ⏳ (needs ≥4 densities)
## PART 7 — BIOLOGICAL INTERPRETATION  ⏳
## FINAL VERDICT  ⏳
