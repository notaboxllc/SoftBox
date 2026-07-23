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

## 4. Evidence classification (demonstrated / supported / suggestive / unresolved)

Before the declared L60 sensitivity study, production gliding had been run only at L40, so the ensemble-level
S2-length conclusions were predicted from the L-robust beam mechanics. **The completed L60 study now permits the
following evidence reclassification** (single-head demonstrated; dimer qualitative). Separating the layers:

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

| conclusion | classification | evidence |
|---|---|---|
| single-head hyperbolic saturation | **DEMONSTRATED** | single-head hyperbolic R²0.99; no high-density decline |
| single-head ρ½ shift | **DEMONSTRATED modest geometry-dependent recruitment-scale shift** | ρ½ 418→500 (+20 %, mechanical accessibility) |
| single-head Vmax robustness | **DEMONSTRATED negligible** | Vmax ratio 1.026 (+2.6 %, CIs overlap) |
| single-head continued recruitment | **DEMONSTRATED** | bound heads keep rising past the velocity plateau |
| dimer remains slower than single-head | **QUALITATIVELY SUPPORTED** | dimer/single 0.67–0.95 per-density; ~0.59 Vmax-level at L40 |
| dimer slowdown at L60 | **QUALITATIVELY SUPPORTED** | two-head frac ~0.0002 ⇒ fork-opposition Vmax effect, mechanism not qualitatively changed |
| dimer ρ½ right-shift | **SUGGESTIVE / DIRECTIONAL** | same direction as single-head; magnitude UNRESOLVED |
| dimer saturation | **SATURATING TENDENCY SUPPORTED, not quantitatively demonstrated** | reduced, excursion-affected grid |
| dimer Vmax and ρ½ values | **UNRESOLVED QUANTITATIVELY / not freeze-grade** | fitted ratios 1.76 / 5.84 are artifacts, not results |
| increased buckling-prone mechanics | **DEMONSTRATED AT BEAM LEVEL** | preflight kComp 105→0.68, buckle 4.4→2.0 |
| increased buckled-head population | **PREDICTED, NOT COUNTED** | head-resolved taut/buckled not instrumented |
| reduced internal opposition | **MECHANISTICALLY CONSISTENT INFERENCE** | preserved velocity vs lower occupancy/ATP; not measured head-resolved |
| cooperativity interpretation | **DEMONSTRATED (single-head); QUALITATIVELY SUPPORTED (dimer)** | saturation + Vmax-effect + n≈1 persist |

**Status of 4b: single-head robustness DEMONSTRATED; dimer arm QUALITATIVE supporting evidence (Outcome 1).**
L40 stays canonical; L60 is a supporting §6.2 causal-sensitivity result. The **dimer Vmax/ρ½ are NOT freeze-grade**
— the reduced CPU grid (4 ρ × 2 seeds × 10k) is quantitatively under-constrained, and several L60 dimer cells show
**physically-inadmissible branch excursions** (maxGap ~3700 nm, peak branch force ~2.2e4 pN; the solver recovered,
0 invalid/solver, but the affected velocities are physically contaminated) — making the reduced dimer arm a
**qualitative stress test, not a definitive ensemble fit** (`L60_HMM_DIMER_DENSITY_SWEEP_FINDINGS.md`). Flagged
follow-ups (paper-strengthening, non-blocking): physically-admissible longer dimer runs (substep / NDOF=25 GPU
kernel + seeds), head-resolved telemetry, and the L40-comparator provenance reconciliation (Part F).

**Rigor-mode provenance:** the historical L40 baseline is `rupture_mode=0`; the L60 sweep is `rupture_mode=1`
(canonical); the validated negligible, non-reshaping mode difference permits the mode-0 baseline as the canon-v2
comparison without re-running it. Mode 1 is canonical; mode 0 is only the immutable historical baseline /
legacy-disable, NOT canonical.

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
