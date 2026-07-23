# S2-LENGTH FREEZE DECISION — Part D of the freeze-closure

**Date 2026-07-22 · canon v2.** Re-reads the completed short S2-length studies (L ∈ {10, 20, 40, 60} nm),
separates the mechanistic axes, tests whether the main biological conclusions are robust across plausible
exposed S2 lengths, and applies the decision gate. **DECISION: GATE A — existing evidence is sufficient.**
EA and EI are unchanged (MD/literature constrained); no new run is performed.

---

## 1. The completed L-sweep, by axis (Experiment 4G; `S2_LENGTH_EXISTING_EVIDENCE.md`, `docs/TWOBODY_MD_INFORMED_S2.md`)

L ∈ {10, 20, 40, 60} nm is a preregistered set (`EXP4G_L_NM`), the explicit fixed-contour beam built at each.

| axis | finding across L ∈ {10,20,40,60} |
|---|---|
| **Explicit beam mechanics** | axial k falls exactly as 1/L (420/210/**105**/70 pN/nm); soft-transverse k falls ≈ 1/L³ (8.84→0.052), approaching MD k_lat ≈ 0.01 at L60 — emergent anisotropy matches MD, no prescribed Cartesian spring. |
| **Step-size behavior** | **stroke is L-robust** — contour drift +0.00…+0.01 nm at every L (the working stroke comes from the head converter, not the beam); dt-invariant (7.27 nm at L20 across dt {5e-6, 2.5e-6, 1.25e-6}). |
| **Force-clamp behavior** | the force-clamp FXB fixture is the L40 compliant explicit beam; rigor/ADP catch-slip recovered on it (Part B). |
| **Surrogate calibration** | `CALIBRATED_S2_L40` is fit at L40; re-fits per length trivially (analytic movable-pivot law). |
| **Gliding behavior** | production ensemble sweeps run at L40 (partially-supported gliding surface); the L-robust mechanics ⇒ expected ensemble effect of changing L is a modest Vmax amplitude rescale with ρ½ preserved. |
| **Dimer geometry** | branch/fork geometry (branchLen, SPLAY, ALPHA, BREI) is separate from S2 length (its own OPEN-geometry item); the dimer conclusions do not hinge on L. |
| **Numerical health** | contour conserved ≤ 0.01 nm under stroke + bending at every L; coupled full-tangent solve stable; dt = 2.5e-6 stable at every L; **0 invalid** at every L. |

Buckling: Euler critical ∝ 1/L² (71→2.0 pN); at L60 compression buckles cleanly (100× tension/compression
asymmetry, emergent); at L40 the beam is on the compressed-straight branch. The decoupling
(search-mobile transverse + stiff axial) **emerges for L ≥ 40**; L < 40 is the (correct) strongly-supported
regime.

## 2. Are the main biological conclusions robust across plausible exposed S2 lengths?

| conclusion | robust across L 40–60? | basis |
|---|---|---|
| Gliding velocity saturates with density | **YES** | saturation existence is dt- and mechanism-robust; L only rescales the Vmax amplitude, not the presence of a plateau. |
| Bound-head recruitment continues beyond velocity saturation | **YES** | recruitment is set by binding reach + in-segment placement, independent of S2 exposed length. |
| Dimerization lowers Vmax | **YES** | a fork-opposition (internal-load) effect; the branch geometry, not S2 length, carries it. |
| Dimerization does not materially shift ρ½ | **YES** | ρ½ is preserved under the L-rescale (the same pattern the margin/ownership and dimerization studies show). |
| Tension/compression asymmetry remains | **YES** | emergent and *grows* with L (100× at L60); present at L40, stronger at L60 — the asymmetry is not a length artifact. |
| No qualitative change to the cooperativity interpretation | **YES** | every mechanism above is L-robust at the beam / material level. |

The only axis that changes with L is the **Vmax amplitude** (via k_ax = EA/L, 105→70 pN/nm) and the buckling
threshold — both quantitative rescales, neither a qualitative flip.

## 3. Decision gate

- **NOT Gate C** (qualitative dependence): none of the main conclusions changes qualitatively across 40–60 nm.
- **NOT Gate B** (insufficient only for ensemble gliding): the single-molecule / beam-level robustness is
  established; the one ungathered datapoint is an *ensemble* L60 gliding density sweep, but the L-robust
  mechanics make its outcome predictable (a modest Vmax rescale, ρ½ preserved) and it is not required to
  declare qualitative robustness.

**⇒ GATE A — existing evidence is sufficient.**
- **Declare L = 40 nm the canonical reference geometry** (partially-supported gliding surface).
- **Exposed S2 contour length is CONDITIONALLY FROZEN** — the S2 **material** (EA + EI) is frozen and
  MD/literature-constrained (`S2_MD_PROVENANCE_CORRECTION.md`); what remains is the geometry/boundary choice
  (how much of the ~60 nm S2 is exposed above the coverslip, 40–60 nm), which is bounded, mechanically
  characterized, and L-robust in every conclusion above.
- **Retain L60 as a structural sensitivity condition for the cooperativity paper** (§6.2 compliance panel) —
  the one **optional** confirmatory ensemble L60 gliding density sweep lives there, as a reported sensitivity,
  not a freeze blocker.
- **No new run.** EA and EI are **not** altered — they remain MD/literature constrained.

## 4. Answers to the Part D questions (freeze status)

- **S2 EA:** CONDITIONALLY FROZEN — MD/literature constrained (AMK-2008 axial 60–80 pN/nm; SoftBox 70 mid-band).
- **S2 EI:** CONDITIONALLY FROZEN — MD/literature constrained (AMK-2008 lateral 0.008–0.012; SoftBox 0.01
  dead-center; Lp ≈ 175 nm in-band). **Not unknown.**
- **Exposed S2 length:** **CONDITIONALLY FROZEN** — canonical reference L = 40 nm; 40–60 nm geometry bounded
  and L-robust; optional L60 gliding sweep retained as a §6.2 structural sensitivity, not required.
