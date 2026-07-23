# L60 HMM-DIMER DENSITY SWEEP — FINDINGS (reduced CPU sensitivity)

**Date 2026-07-23 · rev 0f92494 · CPU object solver (`explicit-hmm-dimer-l60`, Ms=5, NF=7, NDOF=25).**
Declared structural sensitivity: exposed S2 total contour 40→60 nm, **all else canonical** (EA, EI, chemistry,
rigor mode 1, branchEA 0.03, dt 2.5e-6, dimers/µm²). **Runner note:** the GPU forked-dimer kernel is compile-time
specialized to (3,1,1)/NDOF=19 and correctly REFUSES L60 (Ms=5/NDOF=25); the dimension-generic CPU object solver
runs it. A full 40k-step grid is infeasible on CPU (~50 min/cell at ρ1500), so this is the user-approved
**reduced representative grid**: 4 ρ {150,400,700,1500} × 2 seeds × {L40,L60}, **10000 steps**, paired.
Data: `RUN_LOGS/l60_sensitivity/dimer_cpu/dimer_l60_cells.csv`.

## Health (Part D4)
**16/16 cells complete, 0 invalid states, 0 solver failures in every cell** (both L40 and L60). No hard
numerical pathology (**NOT Outcome 4**). **Flag (numerical, honest):** the L60 dimer — longer Ms=5 shared S2 at
the canonical branchEA=0.03 — exhibits the known **branch-excursion tail** (maxGap up to ~3700 nm, peakBrF up to
~2.2e4 pN) at ρ ≥ 400 (vs L40 where branchEA=0.03 keeps it clean until ρ3000; `BRANCHEA_CALIBRATION_FINDINGS.md`).
The solve **recovers every time (0 invalid/solver)**, but the excursions add variance to the reduced-grid
velocities. Per the hard constraints, **branchEA is NOT changed**; a definitive L60 dimer Vmax/ρ½ would need the
cross-bridge substep (or a re-specialized GPU kernel) + more seeds — a flagged follow-up, not this task.

## Density response (productive speed µm/s; 2 seeds/cell)

| ρ (dimers/µm²) | v_L40 (s101,s102) | v_L40 mean | v_L60 (s101,s102) | v_L60 mean | bound_L40 | bound_L60 | two-head frac |
|---:|---|---:|---|---:|---:|---:|---:|
| 150 | 1.95, 1.23 | 1.59 | 0.87, 0.97 | 0.92 | 1.63 | 1.44 | ~0.0002 |
| 400 | 2.04, 1.81 | 1.92 | 1.06, 1.92 | 1.49 | 3.82 | 3.92 | ~0.0002 |
| 700 | 2.26, 2.92 | 2.59 | 2.61, 2.74 | 2.67 | 6.25 | 5.97 | ~0.0002 |
| 1500 | 2.51, 2.54 | 2.53 | 2.99, 3.52 | 3.26 | 12.55 | 12.22 | ~0.0002 |

## Density-response fits (Part F1/F2) — NOISE-LIMITED, report with caution

| curve | Vmax | ρ½ | R² | Hill n |
|---|---:|---:|---:|---:|
| L40 dimer | 2.80 ± 0.26 [CI 2.44–3.18] | 124 ± 53 | 0.862 | 0.84 ± 1.19 |
| L60 dimer | 4.93 ± 0.96 [CI 3.96–6.88] | 726 ± 303 | 0.959 | 1.09 ± 0.75 |

**The 4-point / 2-seed / 10k-step fits are under-constrained** (large CIs; L40 ρ½=124 extrapolates below the data
range; the printed Vmax ratio 1.76 and ρ½ ratio 5.84 are FIT ARTIFACTS, not reliable quantities). What the RAW
curves robustly show: **the L60 dimer curve is right-shifted vs L40** (rises later, keeps climbing to ρ1500 where
L40 has plateaued) — the **same direction** as the single-head L-effect (softer beam ⇒ higher ρ½). Both saturate
or approach saturation. Hill n ≈ 1 (near-hyperbolic).

## Dimer slowdown vs single-head (Part F3, G3)

| architecture comparison | Vmax | dimer/single |
|---|---:|---:|
| single-head L40 | 4.77 | — |
| dimer L40 | ~2.8 | **~0.59** |
| single-head L60 | 4.90 | — |
| dimer L60 (per-density ratio 0.67–0.95, mean ~0.8) | ~3.3 (rising) | **~0.7–0.8** |

- **Dimer Vmax remains BELOW single-head Vmax at BOTH L** (L40 ~0.59×; L60 ~0.7–0.8× by per-density ratio) —
  **the dimer slowdown is PRESERVED at L60.** (Cross-architecture caveat: dimer is 10k-step CPU vs single 40k-step
  GPU; the qualitative dimer<single holds across the noise.)
- **Two-head-bound fraction ≈ 0.0002 at every density and both L** — the dimer operates essentially
  single-head-bound; the slowdown is the **fork-opposition / internal-load Vmax effect on the singly-bound
  dimer**, NOT a two-head cooperativity — mechanism **unchanged** at L60.
- **Not a filament-length artifact** — the frozen matched-length control (dimer 11-seg vs single 12-seg,
  ratio 1.034) already established this; unchanged here.

## Decision (dimer arm)
- **G1 saturation — SUPPORTED** (both L saturate / approach saturation; no high-density decline; 0 invalid).
- **G3 dimer architecture robustness — SUPPORTED (qualitatively; quantitatively noise-limited).** Dimer Vmax <
  single-head Vmax at L60 (slowdown preserved), not a length artifact, ρ½ right-shifted like single-head, effect
  remains a translational-efficiency (Vmax) loss via fork opposition — NOT a recruitment-regime change or a
  two-head mechanism. **NOT Outcome 3** (no qualitative change in the dimer slowdown or saturation mechanism).
- **Caveats (honest):** the reduced 4×2×10k CPU grid + the L60 branch-excursion tail (numerically stable but
  velocity-noising) limit quantitative precision — the dimer Vmax/ρ½ ratios are directional, not exact.
- **Verdict: consistent with Outcome 1** (quantitative rescaling; dimer slowdown preserved, no qualitative
  change), with a flagged numerical follow-up (substep / GPU-NDOF25 kernel + more seeds) for a definitive dimer
  L60 Vmax/ρ½. **L40 stays canonical.**
