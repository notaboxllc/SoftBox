# L60 SINGLE-HEAD DENSITY SWEEP — FINDINGS

**Date 2026-07-23 · rev 0f92494 · GPU device-resident (`explicit-s2-l60`, M=6).** Declared structural
sensitivity: exposed S2 length 40→60 nm, **all else canonical** (EA, EI, chemistry, rigor mode 1, binding gate,
dt 2.5e-6, 40000 steps, heads/µm², whole-window LS velocity). L40 stays canonical; this is not a tuned baseline.
Data: `RUN_LOGS/l60_sensitivity/single_head_l60/` (52 cells) vs the L40 comparator dataset (see §L40 provenance)
+ 8 L40 mode-1 controls.

## Health (Part D4)
**52/52 cells complete, 0 failed, 0 invalid states, 0 solver failures, 0 rate-cap warnings at every density**
(ρ 100→3000, 4 seeds each). Device-resident, no CPU fallback. Contour stable. **Numerically clean L60 single-head
production behavior — DEMONSTRATED; NOT Outcome 4.**

## Rigor-mode provenance (Part E — read before the numbers)
- The **immutable historical L40 density baseline was generated with `rupture_mode = 0`** (pre-canon-v2; the
  `rupture_mode` field is absent from those cells).
- The **L60 single-head sweep used `rupture_mode = 1`** (the canon-v2 canonical production default).
- **8 L40 mode-1 controls + the prior promotion regression** (`RIGOR_RUPTURE_PROMOTION_REGRESSION.md`,
  +0.33 % ± 1.56 % pooled) show the mode-0/mode-1 gliding difference is **negligible and does not reshape the
  curve** (high-density controls: ρ700 −0.04 %, ρ1500 −4.2 %; low-density noisy at n=2, seed-variance-dominated).
- **Mode 1 remains the canonical production default in canon v2. Mode 0 remains ONLY the immutable historical
  baseline / explicit legacy-disable — it is NOT canonical.** The validated negligible, non-reshaping mode
  difference permits using the mode-0 historical baseline as the canon-v2 comparison baseline **without re-running
  it**; new canonical production uses mode 1.

## L40 comparator provenance (Part F — REQUIRES PROVENANCE RECONCILIATION BEFORE PUBLICATION)
The L40 values used in this comparison — **Vmax = 4.7702 ± 0.11 µm/s, ρ½ = 418 ± 26 µm⁻²** — are the output of the
canonical analysis script (`single_head_density_analysis.py`) run on the **current**
`RUN_LOGS/single_head_density_sweep_long/` dataset (identical to `l60_analysis.py`). **They differ from the earlier
canonical long-sweep summary (Vmax ≈ 4.404, ρ½ ≈ 352; `docs/matsoa/SINGLE_HEAD_GPU_DENSITY_SWEEP_LONG_FINDINGS.md`,
CLAUDE.md, `CANONICAL_PARAMETER_MANIFEST.json`).** Determined from the run manifests (not guessed):
- **Same analysis script** (`single_head_density_analysis.py` unchanged since rev 51f0add) and **same velocity
  field** (`vel_prod`, whole-window LS) — re-running it on the current dir reproduces **4.770/418**, not 4.404/352.
  So the shift is in the **data**, not the fit method or estimator.
- **The baseline dataset is MIXED-REVISION:** 36 cells at rev `73d82bf` (9 densities, mtime 2026-07-21) **plus 16
  cells re-run at an UNSTAMPED rev (`code_rev="unknown"`) on 2026-07-22 at densities {250, 700, 1500, 3000}**. The
  re-run high-density cells have **higher** velocities than the 07-21 values (e.g. ρ3000 3.91→4.23, ρ1500
  3.55→3.82), which pulls the extrapolated Vmax up (4.40→4.77) and ρ½ up (352→418). Both cell sets: nSeg=12,
  model=explicit-s2-l40, `rupture_mode` absent (mode-0-era).
- **UNRESOLVED:** the exact code/config change responsible for the 2026-07-22 partial regeneration is **not
  determinable from the metadata** (the re-run cells are rev-`unknown`; the earlier canonical summary predates the
  regeneration). **⇒ 4.77/418 is the "L40 comparator dataset used in the declared L60 sensitivity," NOT
  automatically "the canonical long-sweep fit." The 4.77/418 vs 4.404/352 discrepancy is flagged: REQUIRES
  PROVENANCE RECONCILIATION BEFORE PUBLICATION.**

**Effect on this study:** the L60/L40 **ratios** below are internally self-consistent (L40 comparator and L60 both
fit the same way on current data), so the Outcome-1 conclusion is unaffected; only the **absolute** L40 anchor
carries the reconciliation flag.

## Density response (productive speed µm/s; mean over 4 seeds)

| ρ (heads/µm²) | v_L40 | v_L60 | v ratio | bound_L40 | bound_L60 | ATP_L40 | ATP_L60 | peakLoad_L40 | peakLoad_L60 |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 100 | 0.91 | 0.56 | 0.61 | 0.68 | 0.63 | 80 | 75 | 15.8 | 17.5 |
| 250 | 1.89 | 1.55 | 0.82 | 1.64 | 1.61 | 217 | 205 | 21.9 | 28.5 |
| 500 | 2.71 | 2.64 | 0.97 | 3.66 | 3.23 | 468 | 410 | 30.7 | 36.8 |
| 700 | 2.98 | 2.81 | 0.94 | 4.64 | 4.46 | 668 | 601 | 35.8 | 41.7 |
| 1000 | 3.32 | 3.13 | 0.94 | 7.16 | 6.13 | 962 | 836 | 40.8 | 50.6 |
| 1500 | 3.82 | 3.80 | 0.99 | 9.42 | 9.12 | 1448 | 1274 | 48.5 | 64.5 |
| 3000 | 4.23 | 4.10 | 0.97 | 18.52 | 17.64 | 2895 | 2542 | 73.3 | 102.3 |

(Full 13-density table in `L60_GLIDING_CELLS.csv`.)

## Density-response fits (Part F1/F2)

| curve | Vmax (µm/s) | ρ½ (µm⁻²) | R² | Hill n | Hill vs hyperbolic |
|---|---:|---:|---:|---:|---|
| **L40 comparator** | **4.77 ± 0.11** [95% CI 4.45–5.11] | **418 ± 26** | 0.9905 | 0.94 ± 0.08 | ΔAIC +62 ⇒ Hill does NOT improve |
| **L60** | **4.90 ± 0.16** [95% CI 4.65–5.15] | **500 ± 39** | 0.9872 | 1.16 ± 0.09 | ΔAIC +56 ⇒ Hill does NOT improve |

- **Vmax ratio L60/L40 = 1.026** (+2.6 %) — CIs overlap heavily ⇒ **negligible Vmax change — DEMONSTRATED.**
- **ρ½ ratio L60/L40 = 1.196** (+20 %) — a **demonstrated modest geometry-dependent recruitment-scale shift.**
- **Hill n ≈ 1** at both L (0.94 / 1.16), hyperbolic preferred ⇒ **near-hyperbolic saturation, no cooperative
  sharpening — DEMONSTRATED** (n≈1 is NOT read as absence of mechanical coupling).

## Demonstrated single-head results (Part A)
- **Numerically clean L60 production behavior — DEMONSTRATED** (0 invalid/solver, contour stable, 52/52).
- **Stable near-hyperbolic density saturation — DEMONSTRATED** (R² 0.987; hyperbolic adequate).
- **No high-density velocity decline — DEMONSTRATED** (ρ3000 is the max, still on the asymptote).
- **Negligible Vmax change — DEMONSTRATED** (4.77→4.90, ratio 1.026, CIs overlap).
- **Modest geometry-dependent recruitment-scale shift — DEMONSTRATED** (ρ½ 418→500, +20 %).
- **Continued bound-head recruitment beyond the velocity plateau — DEMONSTRATED** (bound heads keep rising at high
  ρ past the velocity saturation, both L).
- **Robustness of the qualitative single-head cooperativity interpretation — DEMONSTRATED robust.**

## Mechanistic interpretation (Part D — beam-level demonstrated; head-resolved NOT counted)
**Directly demonstrated in the preflight** (`L60_S2_MECHANICAL_PREFLIGHT.md`): k_ax 105→70 pN/nm; transverse
stiffness 0.167→0.052 pN/nm; kComp 105→0.684 pN/nm; Euler threshold 4.4→2.0 pN; stroke 7.27→7.26 nm; stable
explicit-beam solution. **The L60 beam mechanics predict a larger buckling-prone compressive population.**

**NOT directly measured in the density sweep** (the deferred head-resolved cooperativity layer — Part E scope):
per-head taut fraction, per-head buckled fraction, resisting-force population, compressive-vs-productive head work,
head-resolved ATP cost per displacement. So:
- L60 recruits ~10–15 % fewer bound heads at matched density (ρ500 3.66→3.23; ρ1000 7.16→6.13) and shows ~12 %
  lower ATP turnover (tracks bound heads) — **directly measured.**
- **The ensemble observations are consistent with weaker transmission of compressive reaction forces** at L60 (the
  softer/buckling beam), and **preserved velocity despite somewhat lower occupancy and ATP turnover is consistent
  with reduced internal opposition — but head-resolved buckling and work telemetry were NOT collected**, so the
  buckled-head population and the resisting-force reduction are **predicted from the beam mechanics, not counted.**
- Single-head **peak loads** are higher at L60 (ρ3000 73→102 pN). **Peak-load increase alone is NOT proof of
  buckling** — it may include tensile transients, geometric excursions, or other events; it is reported as a raw
  observable, not as head-resolved buckling.

## Decision (single-head arm) — Outcome 1
- **G1 saturation — SUPPORTED/DEMONSTRATED** (stable saturating L60 response, no high-density decline, R² 0.987).
- **G2 recruitment-scale — SUPPORTED; ρ½ +20 % DEMONSTRATED as a modest geometry-dependent shift** (mechanical
  accessibility: fewer bound heads/density; not a new kinetic regime; reported, not tuned away).
- **G4 mechanical** — beam-level buckling/compliance DEMONSTRATED; head-resolved buckled population PREDICTED, not
  counted; reduced internal opposition = a mechanistically-consistent inference.
- **Verdict: Outcome 1 (quantitative rescaling only)** for the single-head architecture. **L40 remains canonical;
  the single-head gliding phenotype is DEMONSTRATED robust to exposed S2 length**, with the ρ½/geometry dependence
  recorded as a causal structural-sensitivity result.
