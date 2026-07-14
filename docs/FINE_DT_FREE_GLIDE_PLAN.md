# FINE-dt FREE-GLIDING DENSITY SWEEP — PLAN

**Branch:** `dt-convergence-study`  ·  **Started:** 2026-07-13 (autonomous)
**Canonical build commit:** `f537972` ("FINE-dt V0 reference") built pristine in an isolated
git worktree `/home/jba/Code/SoftBox-finedt-canon`.

## Why a pristine-HEAD worktree (not the working tree)
The working tree carries uncommitted WIP: (a) J2-torsion instrumentation (`J2_TORSION=0.0`,
`J2_AUDIT=false` — default-off, inert) and (b) an `orthogonalizeY` re-normalization **added to the
canonical XB_IMPLICIT2 derive path**. (b) alters the canonical numerics and is uncommitted/unvalidated.
`orthogonalizeY` has **0 occurrences at HEAD** — proving the WIP changes the canonical path. HEAD
`f537972` is exactly the commit that established the clamp reference V₀_fine=13.3 µm/s, so building it
makes the free-glide sweep directly comparable to that reference and honors "unmodified canonical
stack." The worktree leaves jba's WIP completely untouched (non-destructive).

## Scientific objective
Directly measure the canonical free-gliding `velFitX`–density curve at production dt=1e-5 and fine
dt=5e-6 (matched 0.6 s physical duration), find whether the fine-dt curve shows an operational
plateau, and state honestly whether it is resolved / bounded / not-yet-reached — **without** inferring
from the V₀/1.4 force-balance closure.

## Protected canonical configuration (all DEFAULT-ON at HEAD — verified)
`SPHEREHEAD=true · AXLOCK=true · DIRSWING=true · XB_IMPLICIT2=true · LYMN_TAYLOR=true` (+ springs
default-on, ADP·Pi-bind welded in). A **bare** `run_gliding.sh` invocation is the canonical stack;
only swept conditions are passed explicitly. **Nothing** in the force laws / kinetics / release /
binding / capture radius / anchor / J1-J2 / RNG / step order / defaults is changed. `BoA-v1ref`
untouched.

## Fixed conditions (matched to the established density sweep)
- Geometry / measurement: `-full` (v1 14×2 bed, ~26.7 µm², ~13.4k motors at d500) `-grid`
  (v1-style inst+net+longWindow + velFitX). Measurement definitions identical to
  `run_canonical_density_sweep_coltol8.sh`.
- `coltol = 8 nm` (the established coltol-8 baseline; a tighter capture radius than the coltol-10 curve).
- `matbox 50` chamber: z half-width 50 nm (loose isolation, not a plane pin), y-walls at mat ±1.0 µm
  (keeps the filament over the lawn, fixes the high-density y-coverage escape), **x free** (preserves
  the −x glide runway). Default-off byte-identical when off; a **matbox-on/off control** at d2000 &
  d8000 verifies the chamber does not otherwise shift the baseline.
- `velFitX` = −LS slope of cₓ over the 2nd-half steady window (physical-time-proportional ⇒ auto-scales
  across dt at fixed 0.6 s). Warmup for STATS_STEADY = 0.20 s (physical, `round(0.20/dt)` steps) —
  identical physical warm-up across dt.

## Execution runner
The deterministic **CPU is the basin arbiter** (GPU-number trust rule; the gliding steady state is
chaotic+bistable). But CPU is ~7× slower ⇒ a full CPU matrix is impractical. Plan (per the study's
allowance):
- **GPU** for the full paired sweep (single RTX 5070; one run at a time — no concurrent GPU jobs).
- **Matched CPU arbiter** cells at d2000 & d8000, both main dts, 2 seeds — explicit CPU/GPU basin
  comparison. CPU and GPU values are **never mixed in one curve**.

## Timesteps & steps (equal 0.6 s physical duration, steps ∝ 1/dt)
| dt (s) | steps M | role |
|---|---|---|
| 1e-5 | 60000 | production main arm |
| 5e-6 | 120000 | fine main arm |
| 2.5e-6 | 240000 | Part-3 spot checks (d2000, d8000; +d4000/seed3 if 5→2.5 drift material) |

## Density grid
ρ = **500, 1000, 2000, 4000, 6000, 8000** motors/µm² (the study grid). 3 paired seeds (0,1,2),
identical across density / dt / runner. dt interleaved within each (density,seed) so machine heating is
balanced across timestep. Every cell is a separate file under `RUN_LOGS/finedt_cells/` and is
**restartable** (a cell with a valid GRID_ROW+COV_ROW is auto-skipped).

## Benchmark (Part 1) — GPU throughput (dt-independent: same kernels/step)
| density | GPU steps/s | wall @1e-5 (60k) | wall @5e-6 (120k) |
|---|---|---|---|
| 500  | 306 | 3.3 min | 6.5 min |
| 2000 | 130 | 7.7 min | 15.4 min |
| 8000 | _(benchmark pending)_ | | |

Estimated main-sweep matrix cost (3 seeds, both main dts) and CPU arbiter/spot-check cost are
finalized in RESULTS after the benchmark completes. 4 seeds ruled out as impractical (high-density
fine-dt cells dominate).

## Observables retained per cell (Part 4)
Primary `velFitX`; plus `netX`, `instSteady`, `avgBsteady`/`avgB` (bound count), `meanReach`,
`dwellMs` (lifetime), `detachRatePerS`, `duty`, `velPerBound = velFitX/avgBsteady` (per-bound
efficiency proxy), `fullMat`/`runMinMargin` (bed coverage), NaN/instability scan. Points where the
filament leaves the bed / coverage is violated / the run is unstable are flagged and classified
separately.

## Analysis (Parts 5–7)
- **Part 5** paired within-seed ΔV(ρ)=V(5e-6)−V(1e-5): seed values, mean±SEM, paired bootstrap CI,
  %change, ΔavgB, ΔvelPerBound → attribute the shift to occupancy vs per-bound efficiency.
- **Part 6** per-dt fits: MM (Hill n=1), Hill (free n), linear, log, power; AICc model comparison,
  seed-bootstrap CI on V∞/ρ½, leave-one-out (drop 500 / drop 8000), 4000→6000 & 6000→8000
  increments. Classify each curve A–E.
- **Part 7** biological comparison kept distinct from numerical convergence; state whether an xCatch
  or J2 intervention is still motivated (**no parameter is changed in this study**).

## Deliverables
`FINE_DT_FREE_GLIDE_PLAN.md` (this) · `FINE_DT_FREE_GLIDE_RESULTS.md` · `FINE_DT_FREE_GLIDE_figure.png`
(V–ρ both dts / paired ΔV / bound-count) · raw `RUN_LOGS/finedt_cells/*.log` + `RUN_LOGS/finedt_*.txt`
· scripts `finedt_free_glide_sweep.sh` / `finedt_benchmark.sh` / `finedt_analyze.py` · a JOURNAL entry.
