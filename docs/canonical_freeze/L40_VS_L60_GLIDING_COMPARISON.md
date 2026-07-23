# L40 vs L60 GLIDING COMPARISON — the exposed-S2-length sensitivity verdict

**Date 2026-07-23 · canon v2.** The declared structural sensitivity: change ONLY the exposed S2 contour length
40→60 nm and ask whether the gliding phenotype or its interpretation changes. **No parameter tuned; L40 stays
canonical; canon v2 is not reopened.** Sources: `L60_SINGLE_HEAD_DENSITY_SWEEP_FINDINGS.md` (52-cell GPU full
grid), `L60_HMM_DIMER_DENSITY_SWEEP_FINDINGS.md` (16-cell reduced CPU qualitative stress test),
`L60_S2_MECHANICAL_PREFLIGHT.md`, `L60_GLIDING_CELLS.csv`, `L60_DENSITY_FITS.csv`.

## Final verdict (use this wording consistently)

> **L40 remains the conditionally frozen canonical exposed-S2 geometry. The clean single-head L60 sweep
> demonstrates that the qualitative gliding phenotype is robust to a 40-to-60 nm change in exposed S2 length, with
> negligible Vmax change and a modest approximately 20 % right shift in the recruitment scale. The reduced L60
> dimer study qualitatively supports preservation of the dimer slowdown, but its quantitative saturation
> parameters are not freeze-grade because of limited sampling and physically implausible branch excursions. The
> overall freeze outcome is therefore Outcome 1: quantitative rescaling only, with the dimer arm retained as
> qualitative supporting evidence.**

## Headline numbers

| quantity | single-head L40 comparator | single-head L60 | L60/L40 | dimer L40 | dimer L60 |
|---|---:|---:|---:|---:|---:|
| **Vmax (µm/s)** | 4.77 ± 0.11 | 4.90 ± 0.16 | **1.026** | 2.80 (fit artifact) | 4.93 (fit artifact) |
| **ρ½ (µm⁻²)** | 418 ± 26 | 500 ± 39 | **1.196** | 124 (fit artifact) | 726 (fit artifact) |
| **Hill n** | 0.94 | 1.16 | ≈1 | 0.84 | 1.09 |
| **saturation** | hyperbolic R²0.99 | hyperbolic R²0.99 | preserved | tendency only | tendency only |

**Single-head fits are tight and freeze-grade** (4-seed, 40k GPU). **Dimer fits are NOT freeze-grade** — 4-point /
2-seed / 10k CPU + a physically-inadmissible L60 branch-excursion tail. **The dimer hyperbolic Vmax ratio 1.76 and
ρ½ ratio 5.84 are FIT ARTIFACTS, not biological results, and must not be quoted as such.**

**L40 comparator provenance (Part F — flagged):** the L40 anchor 4.77/418 is the canonical script on the CURRENT
`single_head_density_sweep_long` dataset; it differs from the earlier canonical summary 4.404/352 because that
dataset was **partially regenerated (4 densities re-run at an unstamped rev on 2026-07-22)**. The L60/L40 ratios
are internally self-consistent, but the absolute L40 anchor **REQUIRES PROVENANCE RECONCILIATION BEFORE
PUBLICATION** (see `L60_SINGLE_HEAD_DENSITY_SWEEP_FINDINGS.md` §L40 provenance).

**Rigor-mode provenance (Part E):** the immutable historical L40 baseline was generated with `rupture_mode = 0`;
the L60 sweep used `rupture_mode = 1` (canonical); 8 L40 mode-1 controls + the promotion regression show the mode
difference is negligible and does not reshape the curve, permitting the historical mode-0 baseline as the canon-v2
comparison baseline without re-running it. **Mode 1 is canonical; mode 0 is only the immutable historical baseline
/ legacy-disable — not canonical.**

## The ten required answers

1. **Does L60 preserve density-dependent saturation?** **Single-head: DEMONSTRATED** (hyperbolic R²0.99, no
   high-density decline). **Dimer: SATURATING TENDENCY SUPPORTED, not quantitatively demonstrated** (reduced,
   excursion-affected grid).
2. **Does L60 materially shift ρ½?** **Single-head: DEMONSTRATED modest right-shift** (418→500, +20 %). **Dimer:
   SUGGESTIVE / DIRECTIONALLY SUPPORTED** right-shift (same direction; magnitude UNRESOLVED — the fitted ρ½ ratio
   5.84 is an artifact).
3. **Does L60 change Vmax?** **Single-head: DEMONSTRATED negligible** (+2.6 %, CIs overlap). **Dimer: UNRESOLVED
   QUANTITATIVELY** (the fitted Vmax ratio 1.76 is an artifact).
4. **Does L60 preserve the dimer slowdown?** **QUALITATIVELY SUPPORTED** — dimer stays slower than single-head at
   L60 (dimer/single 0.67–0.95 per-density; two-head fraction ≈0.0002 ⇒ single-head-bound fork-opposition Vmax
   effect, mechanism not qualitatively changed).
5. **Does L60 preserve the similar single-head/dimer recruitment scale?** **Single-head DEMONSTRATED; dimer
   SUGGESTIVE.** Both ρ½ shift right at L60 (single-head cleanly +20 %; dimer same direction, magnitude unresolved).
6. **Does L60 increase the buckled/compressive population?** **Beam mechanics: DEMONSTRATED that L60 is more
   buckling-prone** (kComp 105→0.68 pN/nm; buckle 4.4→2.0 pN; 100× tension/compression asymmetry). **Increased
   buckled-HEAD population: PREDICTED, NOT COUNTED** — per-cell taut/buckled head fractions were not instrumented
   (deferred head-resolved layer). Higher single-head peak loads are a raw observable, **not proof of buckling**
   (may include tensile transients / geometric excursions).
7. **Does it reduce resisting force or internal opposition?** **Not directly measured; a mechanistically-consistent
   inference.** The ensemble observations (preserved velocity despite ~10–15 % lower occupancy and ~12 % lower ATP
   turnover) **are consistent with weaker transmission of compressive reaction forces / reduced internal
   opposition**, but head-resolved buckling and work telemetry were not collected.
8. **Are the existing L40 canonical sweeps still an appropriate immutable baseline?** **YES**, with the Part-F
   provenance flag noted. The immutable historical L40 baseline was generated with mode 0; the validated negligible,
   non-reshaping mode-0/mode-1 difference permits its use as the canon-v2 comparison baseline without re-running it;
   new canonical production uses mode 1. L40 is not replaced. (The 4.77/418-vs-4.404/352 anchor discrepancy is a
   documentation reconciliation item, not a change to the data or to L40's canonical status.)
9. **Does the cooperativity paper's central interpretation remain robust?** **DEMONSTRATED for single-head;
   QUALITATIVELY SUPPORTED for dimer.** Density-dependent saturation, the dimer slowdown (a Vmax effect), and
   near-hyperbolic response (n≈1) persist across 40–60 nm; the exposed-length/ρ½ dependence is a causal
   structural-sensitivity result, not a reinterpretation.
10. **Main-text causal perturbation or supplement?** **Supplement / supporting §6.2 sensitivity.** Single-head is
    clean quantitative rescaling; the dimer arm is qualitative supporting evidence (not freeze-grade). This is
    Outcome 1, not Outcome 3 — L60 belongs as a supporting causal-sensitivity result, not a central variable. A
    physically-admissible, tighter dimer L60 would elevate it if desired.

## Decision gates (Part G)
- **G1 Saturation robustness — SUPPORTED** (single-head DEMONSTRATED; dimer saturating-tendency).
- **G2 Recruitment-scale robustness — SUPPORTED; single-head ρ½ +20 % DEMONSTRATED + explained** (mechanical
  accessibility; reported, not tuned away).
- **G3 Dimer architecture robustness — QUALITATIVELY SUPPORTED** (dimer slower than single at L60; not a length
  artifact; a translational-efficiency Vmax effect; **not Outcome 3**). Quantitatively UNRESOLVED / not freeze-grade.
- **G4 Mechanical interpretation** — beam-level buckling/compliance DEMONSTRATED; head-resolved buckled population
  PREDICTED-not-counted; reduced internal opposition = mechanistically-consistent inference (not a direct
  measurement). Two-head fraction unchanged (~0.0002) ⇒ sister-head coupling not weakened at these densities.

## Evidence-classification table (Part J)

| Result | Classification |
|---|---|
| single-head saturation | **DEMONSTRATED** |
| single-head Vmax robustness | **DEMONSTRATED** |
| single-head ρ½ +20 % shift | **DEMONSTRATED** (modest geometry-dependent recruitment-scale shift) |
| single-head continued recruitment | **DEMONSTRATED** |
| dimer remains slower than single-head | **QUALITATIVELY SUPPORTED** |
| dimer saturation | **SUPPORTED TENDENCY** (not quantitatively demonstrated) |
| dimer ρ½ right shift | **SUGGESTIVE / DIRECTIONAL** |
| dimer Vmax and ρ½ values | **UNRESOLVED QUANTITATIVELY** (fitted ratios 1.76 / 5.84 are artifacts) |
| increased buckling-prone mechanics | **DEMONSTRATED AT BEAM LEVEL** |
| increased buckled-head population | **PREDICTED, NOT COUNTED** |
| reduced internal opposition | **MECHANISTICALLY CONSISTENT INFERENCE** |
| cooperativity interpretation robustness | **DEMONSTRATED FOR SINGLE-HEAD; QUALITATIVELY SUPPORTED FOR DIMER** |

## Outcome — OUTCOME 1 (quantitative rescaling only)
- **Retain L40 as canonical.** L60 did not change the gliding phenotype qualitatively.
- **Single-head: DEMONSTRATED robust** to exposed S2 length (negligible Vmax, +20 % ρ½ via mechanical
  accessibility, saturation + near-hyperbolic preserved).
- **Dimer: qualitative supporting evidence** (slowdown preserved; Vmax/ρ½ not freeze-grade; branch excursions
  physically inadmissible).
- **No L tuned; no baseline replaced; canon v2 not reopened.**
- Flagged follow-ups (paper-strengthening, non-blocking): physically-admissible longer dimer runs (cross-bridge
  substep / NDOF=25 GPU kernel + more seeds); head-resolved taut/buckled and work telemetry; **L40-comparator
  provenance reconciliation (4.77/418 vs 4.404/352) before publication.**
