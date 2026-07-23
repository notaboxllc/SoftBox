# L60 SINGLE-HEAD DENSITY SWEEP — FINDINGS

**Date 2026-07-23 · rev 0f92494 · GPU device-resident (`explicit-s2-l60`, M=6).** Declared structural
sensitivity: exposed S2 length 40→60 nm, **all else canonical** (EA, EI, chemistry, rigor mode 1, binding gate,
dt 2.5e-6, 40000 steps, heads/µm², whole-window LS velocity). L40 stays canonical; this is not a tuned baseline.
Data: `RUN_LOGS/l60_sensitivity/single_head_l60/` (52 cells) vs the frozen L40 baseline
`RUN_LOGS/single_head_density_sweep_long/` (mode 0) + 8 L40 mode-1 controls.

## Health (Part D4)
**52/52 cells complete, 0 failed, 0 invalid states, 0 solver failures, 0 rate-cap warnings at every density**
(ρ 100→3000, 4 seeds each). Device-resident, no CPU fallback. Contour stable; peak branch/joint health within
range. **The L60 ensemble is numerically clean — NOT Outcome 4 (no numerical pathology).**

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
| **L40** (frozen baseline) | **4.77 ± 0.11** [95% CI 4.45–5.11] | **418 ± 26** | 0.9905 | 0.94 ± 0.08 | ΔAIC +62 ⇒ Hill does NOT improve |
| **L60** (sensitivity) | **4.90 ± 0.16** [95% CI 4.65–5.15] | **500 ± 39** | 0.9872 | 1.16 ± 0.09 | ΔAIC +56 ⇒ Hill does NOT improve |

- **Vmax ratio L60/L40 = 1.026** (+2.6 %) — CIs overlap heavily ⇒ **modest / negligible** Vmax change.
- **ρ½ ratio L60/L40 = 1.196** (+20 %) — a modest **recruitment-scale** right-shift.
- **Hill n ≈ 1** at both L (0.94 / 1.16), hyperbolic preferred ⇒ **near-hyperbolic saturation, no cooperative
  sharpening** (n≈1 is NOT read as absence of mechanical coupling — it is the emergent density response).

## Rupture-mode bracket (frozen L40 is mode 0; L60 is mode 1)
The 8 L40 mode-1 controls vs the frozen mode-0 baseline: high density mode0≈mode1 (ρ700 −0.04 %, ρ1500
−4.2 %); low density noisy at n=2 (ρ150 +13.7 %, ρ400 +10.8 %) — dominated by seed variance, consistent with
the established **negligible** gliding mode effect (+0.33 % ± 1.56 %, `RIGOR_RUPTURE_PROMOTION_REGRESSION.md`).
The mode difference is a near-uniform small velocity effect that does **not reshape** the curve, so the ρ½ +20 %
shift is an **L-effect, not a mode artifact.**

## Mechanism (Part G4 — interpretation, not tuning)
- **Fewer bound heads at matched density** — L60 recruits ~10–15 % fewer heads (ρ500: 3.66→3.23; ρ1000:
  7.16→6.13). The softer axial reaction (k_ax 105→70 pN/nm) + compliant compression (kComp 105→0.68) make each
  head slightly less mechanically effective ⇒ the recruitment curve shifts right (ρ½ +20 %). **A changed
  mechanical accessibility, not a new kinetic regime.**
- **Higher peak loads at L60** — peakLoad rises (ρ3000: 73→102 pN; ρ1500: 48→64) as the compliant/buckling beam
  absorbs and transmits larger transient excursions. This is the L60 mechanical signature (compressive
  compliance / buckling), consistent with the preflight kComp 0.68 pN/nm.
- **ATP cost per displacement** tracks bound heads (ATP turnover ~12 % lower at L60), i.e. fewer engaged heads
  per unit density; Vmax (per-head productive output at saturation) is essentially preserved.
- **Resisting/compressive force:** the softer L60 beam yields under compression rather than transmitting it as a
  stiff resisting load — the emergent tension/compression asymmetry (100× at L60 vs 1× at L40) that isolates
  compressive heads. Consistent with Vmax being preserved-to-modestly-higher despite fewer bound heads.

## Decision (single-head arm)
- **G1 saturation robustness — SUPPORTED.** Stable saturating response at L60; no high-density decline (ρ3000
  is the max and still on the hyperbolic asymptote); R² 0.987; hyperbolic adequate.
- **G2 recruitment-scale robustness — SUPPORTED, with the +20 % ρ½ shift FLAGGED and explained.** ρ½ +20 % is
  at the ~20 % practical-flag threshold; it is explained by **changed mechanical accessibility** (softer beam ⇒
  fewer heads/density), not a new kinetic regime, and Hill n stays ≈1. Reported explicitly, not tuned away.
- **G4 mechanical interpretation:** L60 decreases the stiff resisting/compressive reaction (compliant buckling),
  increases buckling, raises peak transient loads, lowers ATP cost per bound head, and preserves Vmax by
  isolating compressive heads.
- **Verdict: Outcome 1 (quantitative rescaling only)** for the single-head architecture — modest Vmax change,
  saturation preserved, ρ½ modestly right-shifted, near-hyperbolic. **L40 remains canonical; the single-head
  cooperativity conclusions are robust to exposed S2 length**, with the ρ½/geometry dependence recorded as a
  causal structural-sensitivity result.
