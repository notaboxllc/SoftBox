# Gliding-speed sensitivity to viscosity (aeta) — a 3×3 probe

**Date:** 2026-07-09 · **Runner:** GPU (canonical default; springs transcendental-free ⇒ GPU-trustworthy).
**Scope:** rough sensitivity probe, **not** a validated law — 3 viscosity points × 3 seeds, 0.6 s window.
**Operating point (fixed):** d1000, coltol 10 nm, M=60000 (dt=1e-5), the capstone gliding operating point.
**Lever:** `-aeta <Pa·s>` (filament/medium viscosity; default 0.1). A **diagnostic** knob — it rescales the
drag AND, FDT-consistently, the Brownian amplitude; it is **not a faithful speed lever** (cf. the dt-ceiling
finding). Used here only to measure drag sensitivity.

Raw: `RUN_LOGS/2026-07-09_aeta_sensitivity.txt` · drivers: the single seed-0 point was run inline; seeds 1–2
(0.05) and 0,1,2 (0.2) via `scripts/run_aeta_sensitivity.sh`.

## Result (mean ± SEM over 3 seeds)

| aeta (Pa·s) | vs default | **velFitX** (µm/s) | inst (µm/s) | avgBsteady | per-bound |
|---:|:--:|---:|---:|---:|---:|
| 0.05 | ½× | **3.266 ± 0.360** | 9.77 ± 0.26 | 2.598 ± 0.517 | 1.26 |
| 0.10 | 1× (baseline) | **2.825 ± 0.054** | 6.96 ± 0.01 | 3.317 ± 0.119 | 0.85 |
| 0.20 | 2× | **1.579 ± 0.078** | 4.87 ± 0.02 | 3.113 ± 0.132 | 0.51 |

Per-seed detail (velFitX): 0.05 → 2.662 / 3.908 / 3.228; 0.10 → 2.895 / 2.718 / 2.861;
0.20 → 1.470 / 1.537 / 1.730.

## What it says

**Net glide *is* drag-sensitive, and the sensitivity is REGIME-DEPENDENT — with a knee right at the default
operating point (η≈0.1):**

- **At/below the default (η 0.05 → 0.10):** WEAK. velFitX 3.27 → 2.83, exponent **p ≈ −0.2** (velFitX ∝ η^p).
  This reproduces the standing "drag-insensitive, cycle/tug-of-war-limited, v ∝ η⁻⁰·¹⁸" verdict.
- **Above the default (η 0.10 → 0.20):** STRONG. velFitX 2.83 → 1.58, exponent **p ≈ −0.84**, approaching the
  fully drag-limited η⁻¹ law.

So glide is **NOT a single power law** in viscosity: it is engagement/cycle-limited at low viscosity (the prior
finding holds there) but becomes **drag-limited at higher viscosity**. The endpoint-to-endpoint exponent
(−0.52) averages over the knee and hides it — do not quote it as "the" exponent.

### Supporting structure (which channel moves)
- **avgBound is ~viscosity-INDEPENDENT** (p ≈ +0.13, flat 2.6–3.3): engagement is set by kinetics/geometry, not
  drag. The velocity change is carried by *speed*, not *duty*.
- **inst ∝ η⁻⁰·⁵** cleanly (9.77 / 6.96 / 4.87, tiny SEM). This √-law is the signature of a
  **thermal-jitter-dominated** centroid speed (Brownian v ∝ √(kT/γ) ∝ η⁻⁰·⁵), which is why `inst` runs high
  (7–10) vs the directed `velFitX`. **Treat `velFitX` (LS-slope directed transport) as the real number; `inst`
  is jitter-contaminated** and not a clean transport metric.

## Caveats
- **The aeta=0.05 point is the soft one:** its SEM (±0.36) is inflated by a seed-0 low-engagement outlier
  (velFitX 2.66, avgB 1.57 vs ~3.2/3.6 for seeds 1–2). Excluding it, velFitX@0.05 ≈ 3.57, which makes the
  low-η half even flatter (p ≈ −0.3). The **η=0.10 and 0.20 points are tight**; the knee rests on those.
- **3 seeds / 0.6 s window / only 3 η-points** — enough to see the regime shift, not to pin the knee location.
- `-aeta` is a **diagnostic, non-faithful** lever (rescales the FDT Brownian amplitude too) — a sensitivity
  probe, not a claim about how fast the model *should* glide.

## Bottom line
Halving viscosity raises net glide only modestly (~+16 %, 2.83 → 3.27) while doubling it nearly halves it
(2.83 → 1.58). The asymmetry is the physics: **cycle-limited below the default, drag-limited above it.** This
**refines** (does not overturn) the standing "glide is drag-insensitive (η⁻⁰·¹⁸)" verdict — that holds
near/below η=0.1, but glide turns drag-limited at 2× viscosity.

**Follow-up if worth formalizing:** add η=0.4 (and maybe 0.025) to confirm the high-viscosity branch keeps
steepening toward η⁻¹ and to locate the knee; more seeds on the 0.05 point to kill the outlier.
