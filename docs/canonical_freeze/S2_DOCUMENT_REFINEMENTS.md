# S2 DOCUMENT REFINEMENTS — Part A (freeze-doc wording corrections)

**Date 2026-07-23 · canon v2.** Records the wording corrections applied to the canonical-freeze documents so
they distinguish *demonstrated* from *predicted* L-robustness, standardize the L40 status, and remove the last
stale/overstated statements. **No parameter changed; the canonical v2 baseline is not reopened.**

---

## A1 — Question 9 in `MODEL_FREEZE_DECISION.md` (completed tasks removed)
The "minimum experiments" list wrongly still recommended reopening S2 EI, re-resolving branchEA, and listed GPU
promotion and rigor-rupture promotion as incomplete. **Replaced** with the actual remaining paper-stage §6
studies: (1) L60 exposed-S2 gliding sensitivity; (2) HMM branch/fork geometry sensitivity; (3) converter/
binding/anchor compliance panel; (4) surface-height/gap bracket; (5) crossbridge substep convergence — stated
explicitly as **causal/robustness studies, not prerequisites for the frozen chemical core.**

## A2 — Standardized L40 status (everywhere)
The exact conceptual distinction now used consistently:
- **Canonical value:** L = 40 nm.
- **Freeze status:** CONDITIONALLY FROZEN — GEOMETRY.
- **Uncertainty class:** bounded exposed-contour + surface-boundary uncertainty (40–60 nm).
- **Permitted variation:** declared structural sensitivity only.
- **Not permitted:** changing L to improve gliding agreement and then silently replacing the canonical baseline.

> L40 is the canonical reference geometry and is conditionally frozen. L60 is a declared alternative
> boundary-condition sensitivity, not a competing tuned baseline.

Fixed the contradiction (L40 called both "open before cooperativity" and "frozen enough to proceed"):
- `MODEL_FREEZE_DECISION.md` summary-table row `OPEN — GEOMETRY` → `CONDITIONALLY FROZEN — GEOMETRY`; the
  four-way category "Open geometry" → "Bounded geometry / boundary (CONDITIONALLY FROZEN — GEOMETRY;
  declared-sensitivity variation only)"; "open items" → "remaining §6 sensitivity studies".
- `PARAMETER_FREEZE_CLASSIFICATION.md` §G header `OPEN — GEOMETRY / BOUNDARY` → `BOUNDED GEOMETRY / BOUNDARY —
  CONDITIONALLY FROZEN — GEOMETRY (declared-sensitivity variation only)`.
- Manifest `finalStatusCategories` key `open_geometry_boundary` →
  `bounded_geometry_boundary_conditionally_frozen_declared_sensitivity_only`.
- **Dimer total-contour entry** (`CANONICAL_MOTOR_PARAMETER_INVENTORY.md`): `inherits L (OPEN)` →
  `inherits the canonical L40 geometry; L60 available as a declared structural sensitivity`.

## A3 — Demonstrated vs predicted S2-length conclusions (the overstatement fix)
Production gliding ran **only at L40** through canon v2, so the ensemble-level S2-length conclusions were
*predicted*, not demonstrated at L60. The S2 docs (`S2_LENGTH_FREEZE_DECISION.md`,
`CANONICAL_FREEZE_CLOSURE_FINDINGS.md` Q6, `OPEN_BIOPHYSICAL_PARAMETERS.md` §1,
`CANONICAL_MOTOR_PARAMETER_INVENTORY.md` §8, `PARAMETER_FREEZE_CLASSIFICATION.md` §G) now split:

- **Already demonstrated across L** (beam/single-molecule): contour conservation, beam numerical stability,
  axial-stiffness scaling, bending-stiffness scaling, buckling-threshold scaling, working-stroke robustness,
  tension/compression asymmetry.
- **Previously predicted, NOT yet demonstrated at L60 ensemble scale** (each moves to demonstrated / supported /
  not-supported after the L60 sweep): hyperbolic saturation, ρ½, modest Vmax change, recruitment beyond velocity
  saturation, dimer slowdown, dimer/single-head velocity ratio, cooperativity interpretation.

## A4 — Why L40 is the reference (added rationale, no unique-truth claim)
> L40 is used as the canonical reference because it represents a plausible partially-supported surface-bound HMM
> geometry, is the shortest tested contour that clearly expresses anisotropic S2 mechanics, avoids the near-rigid
> behavior of short exposed lengths, and is the geometry used by the validated force-clamp fixture, the
> calibrated surrogate, and the completed canonical gliding sweeps.

With the explicit ladder: L10–L20 = strongly-supported / near-rigid limits; L60 = fully exposed-tail limit; L40
= intermediate partially-supported reference; and the note that **biochemical S2 contour length (~60 nm) ≠
mechanically free contour length** in a surface assay (some fraction is adsorbed/supported).

## Files touched (Part A)
`MODEL_FREEZE_DECISION.md`, `CANONICAL_MOTOR_PARAMETER_INVENTORY.md`, `PARAMETER_FREEZE_CLASSIFICATION.md`,
`OPEN_BIOPHYSICAL_PARAMETERS.md`, `S2_LENGTH_FREEZE_DECISION.md`, `CANONICAL_FREEZE_CLOSURE_FINDINGS.md`,
`CANONICAL_PARAMETER_MANIFEST.json`, `PARAMETER_PROVENANCE_TABLE.csv`. (`PARAMETER_DEPENDENCY_MATRIX.csv`
carries only dependency letters — no prose to correct.) After the L60 sweep, the demonstrated/predicted items
are reclassified (Part I).
