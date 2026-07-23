# L40 vs L60 GLIDING COMPARISON — the exposed-S2-length sensitivity verdict

**Date 2026-07-23 · canon v2.** The declared structural sensitivity: change ONLY the exposed S2 contour length
40→60 nm and ask whether the gliding phenotype or its interpretation changes. **No parameter tuned; L40 stays
canonical.** Sources: `L60_SINGLE_HEAD_DENSITY_SWEEP_FINDINGS.md` (52-cell GPU full grid vs frozen L40 baseline),
`L60_HMM_DIMER_DENSITY_SWEEP_FINDINGS.md` (16-cell reduced CPU), `L60_S2_MECHANICAL_PREFLIGHT.md`,
`L60_GLIDING_CELLS.csv`, `L60_DENSITY_FITS.csv`.

## Headline numbers

| quantity | single-head L40 | single-head L60 | L60/L40 | dimer L40 | dimer L60 |
|---|---:|---:|---:|---:|---:|
| **Vmax (µm/s)** | 4.77 ± 0.11 | 4.90 ± 0.16 | **1.026** | ~2.8 (noisy) | ~3.3 rising (noisy) |
| **ρ½ (µm⁻²)** | 418 ± 26 | 500 ± 39 | **1.196** | ~124 (noisy) | ~726 (noisy) |
| **Hill n** | 0.94 | 1.16 | ≈1 | ~0.8 | ~1.1 |
| **saturation** | hyperbolic R²0.99 | hyperbolic R²0.99 | preserved | approaches | approaches |
| **dimer/single Vmax** | — | — | — | ~0.59 | ~0.7–0.8 |

Single-head fits are tight (4-seed, 40k GPU). Dimer fits are **noise-limited** (4-point/2-seed/10k CPU + the L60
branch-excursion tail) — directional, not exact; the raw ratios (dimer Vmax 1.76, ρ½ 5.84) are fit artifacts.

## The ten required answers

1. **Does L60 preserve density-dependent saturation?** **YES** — both architectures retain a stable saturating
   density response (hyperbolic, single-head R²0.99, dimer R²0.86–0.96), no high-density decline (single-head
   ρ3000 still on the asymptote); hyperbolic is adequate at both L.
2. **Does L60 materially shift ρ½?** **Single-head: +20 %** (418→500) — a *modest* recruitment-scale right-shift,
   at the ~20 % practical flag, explained by mechanical accessibility (below). **Dimer: right-shifted in the same
   direction** (noise-limited magnitude). Not a new kinetic regime.
3. **Does L60 change Vmax?** **Single-head: +2.6 %** (negligible; 95 % CIs overlap). **Dimer: noise-limited** but
   consistent with a similar/modest change. **No material Vmax change.**
4. **Does L60 preserve the dimer slowdown?** **YES** — dimer Vmax stays below single-head Vmax at both L (L40
   ~0.59×; L60 ~0.7–0.8× per-density), two-head fraction ≈0.0002 (mechanism = single-head-bound fork opposition,
   unchanged).
5. **Does L60 preserve the similar single-head/dimer recruitment scale?** **Directionally yes** — both ρ½ shift
   right at L60 (single-head cleanly +20 %; dimer same direction, noise-limited). The architectures track each
   other; the dimer effect stays a **Vmax (translational-efficiency)** loss, not a recruitment-regime divergence.
6. **Does L60 increase the buckled/compressive population?** **YES (mechanically).** Preflight: kComp 105→0.68
   pN/nm (L60 buckles under compression, 100× tension/compression asymmetry vs 1× at L40); the emergent
   compliant-compression signature. Per-cell taut/buckled fractions are not instrumented (the deferred
   head-resolved layer — Part E scope), but the higher single-head **peak loads** (ρ3000: 73→102 pN) and the
   L60 dimer **branch excursions** are the ensemble signature of the compliant/buckling beam.
7. **Does it reduce resisting force or internal opposition?** **YES** — the softer L60 beam **yields under
   compression** (kComp 0.68) rather than transmitting it as a stiff resisting load, isolating compressive heads.
   Single-head Vmax is preserved despite ~10–15 % fewer bound heads and ~12 % lower ATP turnover ⇒ each engaged
   head is (if anything) slightly more productive because the beam absorbs opposition instead of resisting it.
8. **Are the existing L40 canonical sweeps still an appropriate immutable baseline?** **YES.** L40 is unchanged,
   canonical, and not replaced; L60 is a declared alternative-boundary-condition sensitivity, not a competing
   tuned baseline. The frozen L40 baseline stays immutable; the rigor-mode bracket (mode-1 controls) confirms the
   mode-0 baseline is a valid reference (mode delta negligible, non-shape-changing).
9. **Does the cooperativity paper's central interpretation remain robust?** **YES** — density-dependent
   saturation, the dimer slowdown (a Vmax effect), and near-hyperbolic response (n≈1, not read as absence of
   mechanical coupling) all persist across 40–60 nm. The **exposed-S2-length / ρ½ dependence** is a *causal
   structural-sensitivity result*, not a reinterpretation of the mechanism.
10. **Main-text causal perturbation or supplement?** **Supplement / supporting §6.2 sensitivity.** The effects
    are quantitative (modest Vmax, +20 % ρ½, higher peak loads, more compressive compliance) with the dimer arm
    noise-limited — this is **Outcome 1**, not Outcome 3, so L60 belongs as a supporting causal-sensitivity result
    (the geometry-dependent recruitment scale + compressive-compliance mechanism), not as a central biological
    variable. A tighter dimer L60 (substep / GPU-NDOF25 + more seeds) would elevate it if desired.

## Decision gates (Part G)
- **G1 Saturation robustness — SUPPORTED** (both L saturate, no decline, hyperbolic adequate).
- **G2 Recruitment-scale robustness — SUPPORTED, ρ½ +20 % FLAGGED + explained** (mechanical accessibility: softer
  beam ⇒ ~10–15 % fewer bound heads/density ⇒ right-shift; not a new kinetic regime; reported, not tuned away).
- **G3 Dimer architecture robustness — SUPPORTED** (dimer Vmax < single at L60; not a length artifact; ρ½
  right-shifts like single-head; remains a translational-efficiency loss; **not Outcome 3**). Quantitatively
  noise-limited (flagged).
- **G4 Mechanical interpretation** — L60 decreases the stiff resisting/compressive reaction (compliant buckling),
  increases buckling, raises peak transient loads, lowers ATP cost per bound head, and preserves Vmax by
  isolating compressive heads. Sister-head coupling: two-head fraction unchanged (~0.0002) ⇒ not weakened by L60
  at these densities. (Mechanistic interpretation, not tuning.)

## Verdict — OUTCOME 1 (quantitative rescaling only)
- **Retain L40 as canonical.** L60 did **not** change the gliding phenotype qualitatively.
- **Classify the paper conclusions as ROBUST to exposed S2 length**, with the **exposed-length ⇒ ρ½ /
  compressive-compliance dependence recorded as a causal structural-sensitivity result** (a supplement/§6.2
  supporting result, not a central variable, not a competing baseline).
- **No L is tuned; no baseline replaced.** The demonstrated/predicted items in the S2 freeze docs move from
  *predicted* to **demonstrated (single-head)** / **supported (dimer, noise-limited)** — see the Part I updates.
- Flagged follow-up (optional, not a freeze blocker): a definitive dimer L60 Vmax/ρ½ via the cross-bridge substep
  or a re-specialized NDOF=25 GPU kernel + more seeds; and per-cell taut/buckled head-resolved telemetry (the
  deferred cooperativity-analysis layer).
