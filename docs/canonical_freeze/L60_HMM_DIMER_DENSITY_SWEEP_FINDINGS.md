# L60 HMM-DIMER DENSITY SWEEP — FINDINGS (reduced CPU **qualitative stress test**)

**Date 2026-07-23 · rev 0f92494 · CPU object solver (`explicit-hmm-dimer-l60`, Ms=5, NF=7, NDOF=25).**
Declared structural sensitivity: exposed S2 total contour 40→60 nm, **all else canonical** (EA, EI, chemistry,
rigor mode 1, branchEA 0.03, dt 2.5e-6, dimers/µm²). **Runner note:** the GPU forked-dimer kernel is compile-time
specialized to (3,1,1)/NDOF=19 and correctly REFUSES L60 (Ms=5/NDOF=25); the dimension-generic CPU object solver
runs it. A full 40k-step grid is infeasible on CPU (~50 min/cell at ρ1500), so this is the user-approved
**reduced representative grid**: 4 ρ {150,400,700,1500} × 2 seeds × {L40,L60}, **10000 steps**, paired.
Data: `RUN_LOGS/l60_sensitivity/dimer_cpu/dimer_l60_cells.csv`. **Scope: this reduced arm is a QUALITATIVE STRESS
TEST, not a definitive ensemble fit** — its quantitative saturation parameters are NOT freeze-grade (see §Health
and §Fits).

## Health — numerical completion vs physical admissibility (Part C)
Two distinct notions must not be conflated:
- **Numerical completion — YES.** 16/16 cells completed with **0 invalid states, 0 solver failures** (both L40 and
  L60); the solver **recovered** in every cell. No hard solver pathology (**NOT Outcome 4**).
- **Physical admissibility — VIOLATED in several L60 cells.** The L60 dimer (longer Ms=5 shared S2 at the canonical
  branchEA=0.03) drives a **branch-excursion tail** with **maxGap up to ~3735 nm and peak branch force up to
  ~2.2e4 pN** (ρ ≥ 400). These magnitudes are **outside plausible HMM geometry and force** — a 3.7 µm joint gap and
  a ~22 nN branch force are not physically admissible for a ~10 nm S1–S2 fork. The solver tolerates and recovers
  from them (0 invalid/solver), but the **velocity estimates in the affected cells are physically contaminated /
  variance-inflated** — these are geometric/force excursions, not merely stochastic "noise."

Consequence: the reduced L60 dimer sweep is a **qualitative stress test** of the L60 dimer geometry, **not a
definitive ensemble fit.** Per the hard constraints, **branchEA is NOT changed**; a physically-admissible L60
dimer (cross-bridge substep, or a re-specialized NDOF=25 GPU kernel + more seeds) is the flagged follow-up.
(At L40, branchEA=0.03 keeps the excursions suppressed until ρ3000 — `BRANCHEA_CALIBRATION_FINDINGS.md`.)

## Density response (productive speed µm/s; 2 seeds/cell)

| ρ (dimers/µm²) | v_L40 (s101,s102) | v_L40 mean | v_L60 (s101,s102) | v_L60 mean | bound_L40 | bound_L60 | two-head frac | L60 maxGap (nm) |
|---:|---|---:|---|---:|---:|---:|---:|---:|
| 150 | 1.95, 1.23 | 1.59 | 0.87, 0.97 | 0.92 | 1.63 | 1.44 | ~0.0002 | 45–85 |
| 400 | 2.04, 1.81 | 1.92 | 1.06, 1.92 | 1.49 | 3.82 | 3.92 | ~0.0002 | 98 / **3736** |
| 700 | 2.26, 2.92 | 2.59 | 2.61, 2.74 | 2.67 | 6.25 | 5.97 | ~0.0002 | 192 / **3736** |
| 1500 | 2.51, 2.54 | 2.53 | 2.99, 3.52 | 3.26 | 12.55 | 12.22 | ~0.0002 | **2010 / 3736** |

The bold maxGap values (≫ the 10 nm branch length) mark the physically-inadmissible branch excursions — the
velocity in those cells is contaminated.

## Density-response fits (Part F1/F2) — NOT FREEZE-GRADE (report only as fit artifacts)

| curve | Vmax | ρ½ | R² | Hill n | status |
|---|---:|---:|---:|---:|---|
| L40 dimer | 2.80 ± 0.26 | 124 ± 53 | 0.862 | 0.84 ± 1.19 | under-constrained (4 pts, 2 seeds) |
| L60 dimer | 4.93 ± 0.96 | 726 ± 303 | 0.959 | 1.09 ± 0.75 | under-constrained + excursion-contaminated |

**The 4-point / 2-seed / 10k-step fits are under-constrained AND (L60) excursion-contaminated.** The printed
**hyperbolic Vmax ratio 1.76 and ρ½ ratio 5.84 are FIT ARTIFACTS — they are NOT reliable biological quantities and
must not be quoted as results** except to state explicitly that they are artifacts (e.g. L40 ρ½=124 extrapolates
below the data range; the L60 curve is still climbing at ρ1500 so its Vmax is an unconstrained extrapolation
inflated by the excursion cells). **Dimer Vmax and ρ½ are UNRESOLVED QUANTITATIVELY / NOT FREEZE-GRADE.**

## What the raw curves DO support (qualitative)
- **Dimer remains slower than single-head — SUPPORTED.** Dimer productive speed is below the single-head speed at
  every measured density (dimer/single per-density 0.67–0.95; L40 Vmax-level ratio ~0.59). (Cross-architecture
  caveat: dimer is 10k-step CPU vs single 40k-step GPU.)
- **Dimer slowdown at L60 — QUALITATIVELY SUPPORTED.** The dimer stays slower than single-head at L60; two-head
  fraction ≈0.0002 at every density and both L ⇒ the slowdown is the **single-head-bound fork-opposition Vmax
  effect**, not a two-head cooperativity — mechanism **not qualitatively changed** at L60.
- **Dimer right-shift tendency — SUGGESTIVE / DIRECTIONALLY SUPPORTED.** The L60 curve rises later than L40 (same
  direction as the single-head +20 % ρ½ shift), but the magnitude is not quantifiable from this reduced,
  excursion-affected grid.
- **Dimer saturation — SATURATING TENDENCY SUPPORTED, NOT QUANTITATIVELY DEMONSTRATED.** Both L approach saturation;
  the L60 curve had not clearly plateaued by ρ1500.
- **Not a filament-length artifact** — the frozen matched-length control (dimer 11-seg vs single 12-seg, ratio
  1.034) already established this; unchanged here.

## Decision (dimer arm)
- **Dimer slowdown preserved at L60 — QUALITATIVELY SUPPORTED** (not a length artifact; mechanism = fork-opposition
  Vmax effect; two-head negligible). **NOT Outcome 3** (no qualitative change in the dimer slowdown or the
  single-head-bound saturation mechanism).
- **Dimer Vmax / ρ½ — UNRESOLVED QUANTITATIVELY / NOT FREEZE-GRADE** (limited sampling + physically implausible
  branch excursions). The fitted ratios are artifacts.
- **Verdict: the reduced L60 dimer study qualitatively supports Outcome 1** (dimer slowdown preserved, saturating
  tendency, right-shift direction) **but contributes no freeze-grade dimer ensemble parameters.** It is retained as
  **qualitative supporting evidence.** **L40 stays canonical.** Flagged follow-up (paper-strengthening,
  non-blocking): physically-admissible longer dimer runs (substep / NDOF=25 GPU kernel) + more seeds; head-resolved
  telemetry.
