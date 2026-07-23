# SM4 Blinded Force-Dependent Lifetime Study — Summary

**Date:** 2026-07-22 · **Runner:** CPU-only (SM4 has no device path) · **Code rev:** `0d7966e` ·
production defaults untouched (scratch build; rupture stays flag-gated `-rigor-rupture`, default OFF);
`BoA-v1ref` byte-clean.

Full study + all deliverables: **`RUN_LOGS/motor_validation/sm4_blind_study/`**
(reports/, blind_primary/, sealed_truth/, blinded_analysis/, controls/, plots/, generator/).
Read first: `reports/SM4_BLIND_STUDY_FINDINGS.md` and `reports/SM4_BLIND_UNBLINDING_SCORECARD.md`.

## Question
Would the canonical SoftBox motor, interrogated through a laser-tweezers-style force-clamp lifetime
assay, yield the same experimentally inferred force-dependent actomyosin behavior as the biological
motor (Guo & Guilford 2006)? Tested by a **genuinely blinded** analysis: a separate agent (ROLE B)
received only stripped, experiment-realistic traces (realized-force clamp readout, lifetime, censoring,
condition label, opaque fixture/dataset codes) with **no planted parameters, no model identity, no
sealed truth**, and had to infer the kinetics.

## Design
7 coded datasets analyzed blind, uniformly: **EXP_A** primary rigor (canonical rupture-on, ATP-free),
**EXP_B** primary ADP, **EXP_C** P1 one-path Bell control, **EXP_D** P2 alternative catch–slip control
(distinct params), **EXP_E** N1 rigor-disabled (force-independent), **EXP_F** N2 shuffled force labels,
**EXP_G** N3 within-cell trace scramble. Fixtures FXA=fixed-anchor (rigid), FXB=explicit-s2-l40
(compliant); never pooled, rigor/ADP never pooled. 14-force ladder (0–25 pN), n=300 (500 near turnover),
realized-force clamp, honest right-censoring. 69,500 events total.

## Result — STRONG PASS (rigor + controls); PARTIAL (ADP)

- **Blinding preserved** (analyst code touched no source/sealed/constant; lockfile attested +
  pre-declared exclusion rule; blind files re-hash to manifest).
- **Rigor recovered blind:** catch–slip decisively (ΔAIC +7240 vs Bell); **xCatch 1.51 nm** (planted
  1.50, z=0.70 — ~exact on the rigid fixture); **peak 7.09 pN [6.93,7.23]** (planted 6.99).
- **Controls all behaved:** Bell control → catch correctly **rejected** (xSlip 1.016 vs 1.0); alternative
  catch–slip (P2) → **all 5 params within 2σ**, peak 3.33 vs 3.31; N1 → **flat** (k0 19940 vs 20000);
  N2 → **no force dependence**. (N3 within-cell scramble is near-inert under a tight clamp — a
  control-design lesson: the informative decoupling is cross-cell, i.e. N2.)
- **Compliant-fixture k0 +24%** = Jensen thermal convexity (σ_F≈1.4 pN); rigid fixture (σ_F=0) unbiased.
- **ADP:** structure (catch–slip) recovered on both fixtures; FXB **xCatch 2.50** (planted 2.5, exact) +
  peak in CI; FXA biased (xCatch 1.28, peak 9.38) because the ADP lifetime is **two-step
  hypoexponential** (force-dependent ADP→NONE then fast force-independent NONE→ATP) — a single-barrier
  fit can't capture it, and the blinded analyst **self-flagged** it (spline ≫ catch by ΔAIC 2236 on that
  cell only).

## Biological agreement vs Guo & Guilford (kept separate from assay recovery)
- **Rigor — quantitative** on shape, peak, and distances: recovered xCatch 1.5 nm, xSlip ≈0.5 nm, peak
  ~7 pN = Table-2 (`x_c 1.5, x_s 0.5, f_crit≈7.1 pN`). Absolute lifetime **not** claimed (perpendicular
  biological load vs axial SoftBox `forceDotFil`; Fig-4A vs Table-2 ambiguity — both preserved caveats).
- **ADP — qualitative only** (catch–slip + ~6 pN peak). Its `onADP=1000/s` is the un-calibrated
  cycling-motor rate, so SoftBox's **rigor(~25 ms) > ADP(~6 ms)** contradicts Guo & Guilford's
  ADP>rigor ordering. The ADP arm was never calibrated to Guo & Guilford; that is a separate calibration
  question, not a defect of the rupture pathway.

## Verdict
The canonical motor **passes** the SM4 force-dependent single-molecule validation for the **rigor
mechanical-rupture pathway** (recovered blind, correct structure/xCatch/peak, decisively catch-slip,
matches Guo & Guilford shape/peak/distances) and all controls; the **ADP arm passes on structure and on
the compliant fixture** but its absolute scale is un-calibrated. No parameter was tuned, the force ladder
was fixed before unblinding, no gliding physics was modified, and the **production default was not
flipped** — promoting rupture-to-default is a separate re-baselining decision this study now supports.

**Next assay:** SM5 dynamic force spectroscopy (loading-rate rupture — tests whether the mapped barrier
also predicts rupture-force distributions), plus an explicit decision on calibrating the ADP arm.
