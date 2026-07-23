# L60 GLIDING SENSITIVITY — RUN PLAN (runner disclosure)

**Date 2026-07-23.** A DECLARED structural-sensitivity study: change ONLY the exposed S2 contour length
40 → 60 nm and measure whether the gliding phenotype or its interpretation changes. **L40 stays canonical; EA,
EI, chemistry, release model, binding gate, crossbridge stiffness, branchEA, ATP, dt, density convention, and
velocity estimator are all unchanged.** No parameter is tuned to a target.

## Runner disclosure (CPU-fallback invariant)

| arm | runner | why | grid | wall-clock (est.) |
|---|---|---|---|---|
| **Single-head L60** | **GPU device-resident** (`ExplicitCompleteMatHarness -production-cell -L 60`, `buildGlidingGraph`) | the device beam solver is segment-count-parameterized; L60⇒M=6 runs on the validated single-head gliding assay class **without an override** (0 invalid/solver confirmed) | 13 ρ × 4 seeds = **52 cells**, 40000 steps | ~326 steps/s @ ρ500 ⇒ ~2 min/cell ⇒ **~2–3 h** |
| **Single-head L40 controls** | GPU device-resident | bracket the rigor-mode-1 vs frozen-mode-0 baseline difference (≤0.33%, established) so the L-effect is isolated | 4 ρ × 2 seeds = **8 cells** | ~20 min |
| **HMM-dimer L60 + L40** | **CPU object solver** (`ExplicitHmmDimerGlidingHarness -matsmoke -L 60`, `ExplicitHmmDimer.solve`) — EXPLICIT, disclosed | **the GPU forked-dimer kernel is compile-time specialized to topology (3,1,1)/NDOF=19; L60 ⇒ Ms=5/NDOF=25 = a NEW un-validated kernel** the per-assay-class gate must refuse. The CPU object solver is dimension-generic and runs L60 as-is. This is NOT a silent fallback — it is the only faithful path for L60 dimer; a full-grid CPU sweep is infeasible (~50 min/cell at 40k), so a **reduced representative grid** is run (user-approved). | 4 ρ {150,400,700,1500} × 2 seeds × {L40,L60} = **16 cells**, 10000 steps | ~10–64 min/cell ⇒ **~5–8 h CPU** (runs concurrently with the GPU arm) |

**Not silent, not a shortcut:** the dimer-L60 CPU choice is forced by the kernel topology and is reported as a
targeted CPU structural sensitivity; L40 dimer controls are run at the SAME reduced config so the L40-vs-L60
comparison is a matched pair, with the frozen 40k-step GPU L40 baseline as the cross-reference.

## Frozen / canonical settings (unchanged; every cell)
- dt = 2.5e-6 s; duration 0.1 s (single-head 40000 steps; dimer reduced 10000 steps).
- rigor rupture mode 1 (canonical default ON); branchEA = 0.03 (dimer); saturating ATP; canonical binding gate;
  canonical surface geometry; canonical velocity estimator (whole-window LS slope of filament centroid·b̂).
- density convention: single-head = heads/µm²; dimer = dimer molecules/µm² (never doubled).
- filament lengths: single-head 12-seg (contour 2.106 µm), dimer 11-seg (contour 1.931 µm) — the frozen
  matched-length caveat (ratio 1.034) is preserved; the prior matched-length control is the reference, not re-run.

## Only-L-derived changes (both architectures)
- L = 60 nm; segment count M = round(L/10) = 6 (single-head) / shared Ms = round((60−10)/10) = 5 (dimer).
- derived axial stiffness k_ax = EA/L = 70 pN/nm (L40: 105); derived bending response (softer); Euler buckling
  F_crit ≈ 2.0 pN (L40: 4.4); total HMM contour E→pivot = 60 nm (L40: 40). **EA/EI unchanged.**
- The production single-head path uses the EXPLICIT beam (not the calibrated surrogate), so no L60 surrogate
  fit is required. (The calibrated-s2-l40 surrogate is NOT touched; a separate L60 surrogate would be needed only
  if a surrogate production path were used.)

## Per-cell manifest fields (Part D4)
model id (`explicit-s2-l60` / `explicit-hmm-dimer-l60`), exposed_s2_nm, ea_si, ei_si, branchEA (dimer),
rupture_mode, dt, steps, seed, density, backend + device-resident, invalid/solver/rate-cap, contour/gap/branch
health, wall. Outputs: `RUN_LOGS/l60_sensitivity/{single_head_l60,single_head_l40_ctrl,dimer_cpu}/`.

## Preflight gate (Part C) — must pass before the sweep (PASSED)
See `L60_S2_MECHANICAL_PREFLIGHT.md`: contour conserved, k_ax≈70 pN/nm, Euler buckle≈2 pN, compliant
compressive buckling, stroke L-robust (7.26 nm), 0 invalid/solver on the device gliding cells, L40 byte-identity
preserved. **PASS ⇒ sweep authorized.**

## Analysis (Parts F–J)
Hyperbolic + Hill fits per curve; paired L40-vs-L60 ratios (Vmax, ρ½, per-density velocity, occupancy,
taut/buckled, ATP turnover, force distributions); dimer-vs-single at L60; decision gates G1–G4; outputs
`L60_SINGLE_HEAD_DENSITY_SWEEP_FINDINGS.md`, `L60_HMM_DIMER_DENSITY_SWEEP_FINDINGS.md`,
`L40_VS_L60_GLIDING_COMPARISON.md`, `L60_GLIDING_CELLS.csv`, `L60_DENSITY_FITS.csv`,
`L60_CANONICAL_SENSITIVITY_MANIFEST.json`.
