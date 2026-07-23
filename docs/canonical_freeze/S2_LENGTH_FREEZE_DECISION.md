# S2-LENGTH FREEZE DECISION — canonical L40, L60 declared structural sensitivity

**Date 2026-07-22 · canon v2 (L60 sweep completed + reclassified 2026-07-23).** Records the S2 exposed-length
freeze status and the demonstrated-vs-predicted evidence split. **L = 40 nm is the canonical reference geometry,
CONDITIONALLY FROZEN — GEOMETRY.** L60 is a **declared alternative boundary-condition sensitivity, not a
competing tuned baseline.** EA and EI are unchanged (MD/literature constrained). **The L60 gliding sensitivity
sweep is COMPLETE (single-head GPU full grid + dimer reduced CPU) — Outcome 1 (quantitative rescaling only): the
gliding phenotype is robust to exposed S2 length; §4b below is reclassified from predicted to demonstrated /
supported** (`L40_VS_L60_GLIDING_COMPARISON.md`). **This does not reopen the canonical v2 baseline; L40 stays
canonical.**

---

## 1. L40 status — the exact conceptual distinction

- **Canonical value:** L = 40 nm.
- **Freeze status:** CONDITIONALLY FROZEN — GEOMETRY.
- **Uncertainty class:** bounded exposed-contour + surface-boundary uncertainty (40–60 nm).
- **Permitted variation:** declared structural sensitivity only.
- **Not permitted:** changing L to improve gliding agreement and then silently replacing the canonical baseline.

> L40 is the canonical reference geometry and is conditionally frozen. L60 is a declared alternative
> boundary-condition sensitivity, not a competing tuned baseline.

## 2. Why L40 is the reference (not a claim of unique biological truth)

> L40 is used as the canonical reference because it represents a plausible partially-supported surface-bound HMM
> geometry, is the shortest tested contour that clearly expresses anisotropic S2 mechanics, avoids the near-rigid
> behavior of short exposed lengths, and is the geometry used by the validated force-clamp fixture, the
> calibrated surrogate, and the completed canonical gliding sweeps.

- **L10–L20** represent strongly-supported / near-rigid limits (stiff-line; adsorbed tail).
- **L60** represents a fully exposed-tail limit (bends + buckles).
- **L40** is the intermediate partially-supported reference.
- The **biochemical S2 contour length (~60 nm) is not identical to the mechanically free contour length** in a
  surface assay — some fraction is adsorbed/supported — so the exposed length is a boundary-condition choice,
  not a molecular constant.

## 3. The completed L-sweep, by axis (Experiment 4G; `S2_LENGTH_EXISTING_EVIDENCE.md`, `docs/TWOBODY_MD_INFORMED_S2.md`)

L ∈ {10, 20, 40, 60} nm is a preregistered set (`EXP4G_L_NM`), the explicit fixed-contour beam built at each.

| axis | finding across L ∈ {10,20,40,60} |
|---|---|
| **Explicit beam mechanics** | axial k falls exactly as 1/L (420/210/**105**/70 pN/nm); soft-transverse k falls ≈ 1/L³ (8.84→0.052), approaching MD k_lat ≈ 0.01 at L60 — emergent anisotropy matches MD. |
| **Step-size behavior** | **stroke is L-robust** — contour drift +0.00…+0.01 nm at every L; dt-invariant. |
| **Force-clamp behavior** | the FXB fixture is the L40 compliant explicit beam; rigor/ADP catch-slip recovered on it. |
| **Surrogate calibration** | `CALIBRATED_S2_L40` fit at L40; the analytic movable-pivot law re-fits per length. |
| **Numerical health** | contour conserved ≤ 0.01 nm at every L; coupled full-tangent solve stable; dt = 2.5e-6 stable; **0 invalid** at every L. |

Buckling: Euler critical ∝ 1/L² (71→2.0 pN); at L60 compression buckles cleanly (100× tension/compression
asymmetry, emergent); at L40 the beam is on the compressed-straight branch. The decoupling (search-mobile
transverse + stiff axial) **emerges for L ≥ 40**; L < 40 is the (correct) strongly-supported regime.

## 4. Demonstrated vs predicted (the honest evidence split — corrected)

Production gliding was run **only at L40** through canon v2, so the ensemble-level S2-length conclusions were
**predicted from the L-robust mechanics, not directly demonstrated at L60 ensemble scale.** Separating them:

### 4a. Already demonstrated across L (beam / single-molecule)
- contour conservation;
- beam numerical stability;
- axial-stiffness scaling (1/L);
- bending-stiffness scaling (≈1/L³);
- buckling-threshold scaling (1/L²);
- working-stroke robustness (L-independent);
- tension/compression asymmetry (emergent, grows with L).

### 4b. Ensemble conclusions — now DIRECTLY TESTED at L60 (RESOLVED 2026-07-23)
The L60 single-head (52-cell GPU full grid) + HMM-dimer (16-cell reduced CPU) sweeps were run
(`L40_VS_L60_GLIDING_COMPARISON.md`). Reclassification — **Outcome 1 (quantitative rescaling only)**:

| conclusion | verdict | evidence |
|---|---|---|
| hyperbolic density saturation | **DEMONSTRATED** | single-head hyperbolic R²0.99 at both L; dimer approaches saturation; no high-density decline |
| ρ½ (half-saturation density) | **DEMONSTRATED to shift modestly** | single-head ρ½ 418→500 (+20 %, a recruitment-scale right-shift via mechanical accessibility); dimer right-shifts same direction |
| modest change in Vmax | **DEMONSTRATED** | single-head Vmax ratio 1.026 (+2.6 %, CIs overlap) — negligible |
| bound-head recruitment beyond velocity saturation | **DEMONSTRATED** | recruitment continues at high ρ (bound heads keep rising past the velocity plateau) at both L |
| dimer slowdown | **SUPPORTED** (dimer arm noise-limited) | dimer Vmax < single-head Vmax at L60 (~0.7–0.8×); preserved |
| dimer/single-head velocity ratio | **SUPPORTED** | dimer/single ~0.59 (L40) → ~0.7–0.8 (L60); dimer stays slower |
| cooperativity interpretation | **DEMONSTRATED robust** | saturation + dimer-Vmax-effect + near-hyperbolic (n≈1) all persist; the exposed-length⇒ρ½ dependence is a causal sensitivity result, not a reinterpretation |

**Status of 4b: RESOLVED — robust to exposed S2 length (Outcome 1).** L40 stays canonical; L60 is a supporting
§6.2 causal-sensitivity result. The dimer quantitative precision is noise-limited (reduced CPU grid + the L60
branch-excursion tail at branchEA=0.03) — a flagged optional follow-up (substep / NDOF=25 GPU kernel + seeds),
not a freeze blocker.

## 5. Freeze decision

- **L = 40 nm is the canonical reference geometry, CONDITIONALLY FROZEN — GEOMETRY.** Not reopened; not replaced
  by L60 on the basis of better gliding agreement.
- **EA and EI are frozen** (MD/literature constrained; `S2_MD_PROVENANCE_CORRECTION.md`) — the L60 study changes
  only the exposed contour length and quantities derived from it, never the material moduli.
- **L60 is a declared structural sensitivity**, implemented as a separate named configuration
  (`explicit-s2-l60` / `explicit-hmm-dimer-l60`; `L60_CANONICAL_SENSITIVITY_MANIFEST.json`), run at the standard
  density grid vs the frozen L40 baseline. **Result: Outcome 1** (quantitative rescaling only — modest Vmax
  change, ρ½ +20 % via mechanical accessibility, saturation + dimer-slowdown preserved). **L40 remains
  canonical**; L60 is a supporting §6.2 causal-sensitivity result, not a competing baseline or canonical v3.

## 6. Answers (freeze status)

- **S2 EA:** CONDITIONALLY FROZEN — MD/literature constrained (AMK-2008 axial 60–80 pN/nm; SoftBox 70 mid-band).
- **S2 EI:** CONDITIONALLY FROZEN — MD/literature constrained (AMK-2008 lateral 0.008–0.012; SoftBox 0.01
  dead-center; Lp ≈ 175 nm in-band). **Not unknown.**
- **Exposed S2 length:** **CONDITIONALLY FROZEN — GEOMETRY**, canonical reference L = 40 nm; bounded 40–60 nm;
  declared-sensitivity variation only; L60 is the alternative boundary-condition sensitivity being tested, not a
  competing tuned baseline.

*(This document is updated with the ensemble results after the L60 sweep — Part I.)*
