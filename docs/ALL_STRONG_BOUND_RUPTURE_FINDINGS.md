# All-Strong-Bound Mechanical Rupture — Sensitivity Experiment — Summary

**Date:** 2026-07-22 · flag-gated, default-OFF; no canonical default / ADP→NONE / rigor / gliding
parameter changed; SM6 untouched. Full study: `RUN_LOGS/motor_validation/sm4_allstrong_rupture/`
(read `ALL_STRONG_BOUND_RUPTURE_SENSITIVITY_FINDINGS.md`).

## What
A flag-gated 3-mode chemistry rupture (`NucleotideCycleSystem.cycleLymnTaylorRuptureAll`): 0 off, 1
rigor-only (current), **2 all-strong-bound** = direct mechanical rupture from bound `NUC_ADP` **and**
`NUC_NONE` (not ADP·Pi/ATP/unbound). ADP rupture uses independent Guo & Guilford ADP Table-2 params
(k0=191, xC 2.5 / xS 0.4 nm) in a dedicated `MotorStore.adpRuptureParams`; the bound-ADP exit becomes a
competing hazard (chemical ADP→NONE release vs direct rupture) resolved by a single-uniform partition (no
new RNG, no order bias, rate-cap, cause-resolved). Wired into SM4, single-head (GPU+CPU) and the
**GPU device-resident dimer** path (`ExplicitHmmDimerGpuValidation.buildProductionGpu`).

## Findings
- **Direct ADP rupture is only ~13–15 % of detachments** — the chemical ADP→NONE release (~1000/s) is ~5×
  faster than the biological ADP rupture (191/s) and out-competes it.
- **Biology NOT improved:** mode 2 *shortens* the ADP peak (28.0 vs mode-1 32.6 ms; G&G 31.7), *collapses*
  the ADP>rigor ordering (1.17→≈1.01; G&G 1.24), shifts peak force up (7.5 vs 6.4 pN), and stays a mixture
  (not the single-exponential G&G observed). The two-stage discrepancy is **not** resolved.
- **Gliding (ATP-rich, ADP occupancy ≫ rigor) — MATERIAL, architecture-dependent:** single-head GPU (40k,
  n=4) +5.5–6.3 % velProd (borderline sig); **dimer GPU (40k, n=4): −13 % bound heads, −14–15 % two-head
  (processive) fraction, −13 % continuity — statistically strong (t up to −49); velocity mixed ±4 %.** ADP
  rupture removes the dimer's partner/rescue head → lower processivity.
- **Regression:** mode 0/1 physics bit-identical (SM4 mode-1 0/300); additive; defaults OFF.

## Decision
**Remain OPTIONAL (flag-gated); MOTIVATE a separate BOND-STATE MODEL; do NOT replace rigor-only; do NOT
reject.** The direct ADP rupture is the biologically-faithful observable, but it cannot dominate while the
~5×-faster chemical ADP→NONE release competes in the same state — a faithful fix needs a bond-state model
(distinct strongly-bound sub-states / reconciled ADP off-rate), a separately-declared calibration phase
(no `onADP` retuning here).

**Correction noted:** the HMM dimer HAS a validated device-resident GPU path
(`run_hmm_gpu.sh -production-cell -backend gpu`); the dimer sweep runs on GPU (CPU cells supplemental only).
