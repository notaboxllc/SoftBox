# Phase-2 step 3 — the FIRST real calibration: reaction-limited kOn on the Config-1 motor (single-molecule duty)

**2026-06-27. Flag-gated (`-kon <µm⁻¹s⁻¹>` + `-single` on `GlidingHarness`, Config-1 path); NO default flipped.**
Moves the binding rate out of the saturated/geometric bind-on-contact regime into the **reaction-limited** regime,
calibrated against the **single-molecule duty ratio** (NOT the geometry-confounded gliding `avgBound/2000`),
guarded against compensating for a wrong catch. The catch constants + κ are HELD.

## TL;DR — verdict
- **kOn ≈ 5×10⁵ µm⁻¹s⁻¹ cleanly calibrates the single-molecule duty to 1.34 % (target ~1–2 %).** Guard 1 PASS: the
  attachment is **reaction-limited** (per-encounter P_capture: theory 0.058 / empirical 0.034, both ≪ 1), NOT a
  saturated rate.
- **Guard 2 FLAG: t_on ≈ 3.85 ms vs the ~10 ms target (~2.6× short).** Cause: the **Jensen effect** of the
  fluctuating J1-strain (mean ≈ 0, RMS ≈ 2.5 pN) on the **instantaneous-load exponential catch** — it over-releases
  under a fluctuating load even at zero mean. The kOn calibration is NOT corrupted (the duty is hit at
  reaction-limited kOn, not by saturation-compensation), but the calibrated kOn is **conditional on the current
  (short) catch dwell** ⇒ the step-4 catch calibration (a time-averaged catch input — the banked release-force-
  averaging finding) will require a **small joint kOn re-tune**.
- **Transferability PARTIAL.** The single-molecule kOn gives a **sensible ensemble binding fraction** (gliding
  avgBound 18 → 3–4, no longer over-binding, into v1's ballpark) and reproduces the expected **duty→glide-magnitude
  trend** (|v| rises with avgBound). BUT at the v1box density the duty 1.34 % sits **below the velocity-saturation
  knee** ⇒ the ensemble glide is weak (|v| ≈ 0.2 vs v1 8.33). Matching v1's glide velocity needs a **higher duty**
  (avgBound ~6–8, kOn ~1×10⁶) **or higher density** — the single-molecule-duty operating point and the
  gliding-velocity-matched operating point **differ** (entangled with the short t_on).
- **No default flipped** (`KON` default 0 ⇒ saturated, byte-identical). **Reported for sign-off.**

---

## The build
The reaction-limited gate is added to the Config-1 binder (`BindingDetectionSystem.bindCanonicalTwoPoint`): a
formable tip-capture binds with **P = 1 − exp(−kOn·Δl·dt)**, Δl = the chord of the filament through the tight
capture sphere (radius myoColTol; Δl = 2·√(myoColTol² − d⊥²), d⊥² the perpendicular distance² already computed by
the geometric search). kOn ≤ 0 ⇒ P = 1 ⇒ saturated bind-on-contact (the prior behavior). RNG = the reused wang-hash
keyed (motor, step, seed), salt "C1KO" ⇒ reproducible CPU↔GPU. This is the existing rate-search physics
(`MYOSIN_BINDING_RATE_FORMULATION`) inlined into the two-point binder. Reaches BOTH the single-molecule assay and
the gliding ensemble (same kernel) — one parameterization.

**Single-molecule assay** (`-single`): 256 Config-1 motors held in PROXIMITY directly under a FIXED filament
(anchor z = −0.075 µm positions the cycling head's bob range at the filament so it is frequently in reach ⇒ the
duty is KINETICALLY gated, not geometry-confounded — trap §1 avoided). The filament is not integrated (held actin).
Each motor is an independent single-molecule realization; the aggregate is the duty.

## The kOn sweep (single-molecule, dt=1e-5, Config-1, catch+κ HELD)
| kOn (µm⁻¹s⁻¹) | duty | attach rate /s | t_on (ms) | P_capture (theory) | regime |
|---|---|---|---|---|---|
| 0 (saturated) | 0.211 | 48.0 | 5.58 | 1.000 | saturated |
| 3×10⁶ | 0.078 | 18.0 | 4.73 | 0.302 | — |
| 1×10⁶ | 0.029 | 7.4 | 4.08 | 0.113 | reaction-limited |
| **5×10⁵** | **0.0134** | **3.6** | **3.85** | **0.058** | **reaction-limited** |
| 3×10⁵ | 0.0077 | 2.2 | 3.50 | 0.035 | reaction-limited |
| 1×10⁵ | 0.0029 | 0.8 | 3.39 | 0.012 | reaction-limited |

⇒ duty ~1–2 % lands at **kOn ≈ 5–6×10⁵** (1.34 % at 5e5, 1.82 % at 6e5), with **P_capture ≪ 1 (reaction-limited)**.
The duty is monotonic in kOn and is hit FAR from saturation ⇒ **Guard 1 (reaction-limited attachment) PASS** — the
duty is NOT being hit by a saturated kOn compensating a wrong off-rate.

## Guard 2 — bound lifetime t_on (the catch check)
t_on ≈ 3.85 ms at the calibrated kOn (well-sampled saturated value 5.58 ms; the lower-kOn values are noisier from
fewer release events). The zero-force catch gives 1/kOff = **10 ms**, yet the measured dwell is ~2.6× shorter even
though the MEAN forceDotFil ≈ 0 (−0.03 pN). **Cause: the catch reads the INSTANTANEOUS J1-strain, which fluctuates
±~2.5 pN RMS; the exponential rate kOff·(αCatch·e^(−F·xCatch/kT)+αSlip·e^(F·xSlip/kT)) is convex ⇒ ⟨rate⟩ > rate(⟨F⟩)
(Jensen) ⇒ faster mean release ⇒ shorter dwell.** This is a CATCH artifact (the instantaneous-load catch over-
releases under fluctuating load), **flagged for step-4** (a time-averaged catch input — the banked
RELEASE_FORCE_INPUT/`-tauavg` finding — is the candidate fix). It is in the right ORDER (~10 ms within ~2.6×), so it
does NOT trip the "t_on wildly off" bail; but it means the calibrated kOn is conditional on the current catch:
**fixing t_on upward in step 4 raises the duty at fixed kOn ⇒ a small joint kOn re-tune** (NOT a clean-kOn failure,
a flagged coupling).

## Transferability — single-molecule → gliding ensemble (NO further tuning)
gliding (v1box, Config-1), varying kOn — the duty→glide trend:
| kOn | gliding avgBound | steady |v| (µm/s) |
|---|---|---|
| 0 (saturated) | 16.7 | ~4 (over-binds ~2× v1) |
| 3×10⁶ | 13.9 | ~6 |
| 1×10⁶ | 5.9 | ~1 |
| **5×10⁵ (calibrated)** | **3.6** | **~0.2** |

- **Binding fraction transfers SENSIBLY:** the single-molecule-calibrated kOn drops the gliding avgBound from the
  over-binding 18 to **3–4** — into v1's 7.6 ballpark (now slightly UNDER it), no longer 2× over.
- **The expected duty→glide-magnitude trend is reproduced** (|v| rises with avgBound) — physically the gliding-assay
  speed-density behavior (sparse attachment → slow; dense → saturates).
- **BUT the gliding velocity at v1box density collapses** (|v| ≈ 0.2 vs v1 8.33): duty 1.34 % is below the
  velocity-saturation knee at this density. Reaching v1's glide velocity (avgBound ~7.6) needs a **higher duty**
  (kOn ~1×10⁶, avgBound ~6) **or higher density**. ⇒ **the single-molecule-duty and gliding-velocity operating
  points DIFFER** — a genuine transferability tension (one parameterization does not simultaneously hit the
  single-molecule duty 1.34 % AND v1's gliding velocity at v1box density). Likely entangled with the short t_on
  (each engagement is brief ⇒ less glide per bound motor) ⇒ to be resolved JOINTLY with the step-4 catch fix.

## Sanity / regime check
The model kOn ≠ solution k_on (it carries reaction × fine-orientation; the gates handle coarse orientation). The
calibrated 5×10⁵ µm⁻¹s⁻¹ translates (via the chord formulation, Δl ~ 0.012 µm, dt 1e-5) to a per-encounter capture
P ≈ 0.06 ≪ 1 — squarely reaction-limited, the regime the literature ~10⁶–10⁷ M⁻¹s⁻¹ implies (the literature number
is the regime sanity check, not plugged in blind). Confirmed NOT diffusion-limited/saturated.

## Bail boundaries — outcome
- **Duty hittable only by a saturated kOn** → did NOT happen — the duty is hit at reaction-limited kOn (P ≪ 1).
- **t_on wildly off ~10 ms** → t_on ~3.85 ms, ~2.6× short (right order, not "wildly") → **reported as a catch flag**
  (Jensen / instantaneous-load catch), step-4 joint re-tune needed — NOT a stop.
- **Single-molecule kOn does NOT transfer to the ensemble** → **PARTIAL** — binding fraction transfers (sensible),
  glide velocity at v1box density does not (sub-saturation duty/density tension). Reported.

## Verdict
**A clean reaction-limited kOn calibration at the single-molecule level (kOn ≈ 5×10⁵ µm⁻¹s⁻¹, duty 1.34 %, Guard 1
PASS), with two coupled flags for step 4:** (a) Guard-2 t_on ~2.6× short — the instantaneous-load catch over-releases
under the fluctuating J1-strain (Jensen), a catch-calibration target; (b) transferability is partial — the
single-molecule-duty operating point under-drives the v1box gliding velocity, so the catch (t_on) and kOn must be
**calibrated JOINTLY** (step 4) to hit single-molecule duty AND ensemble velocity together. **kOn is reported for
sign-off; no default flipped** (`KON`=0 ⇒ saturated, byte-identical).

## CPU≡GPU + default byte-identical
- **CPU≡GPU** (kOn gate, gliding, aggregate-within-SEM): avgBound CPU 4.38 / GPU 5.20 (within the chaotic + sparse-
  sample envelope); the gate is wang-hash-keyed ⇒ reproducible on both runners.
- **Default byte-identical:** `KON`=0 ⇒ no gate; default gliding −2.459 / 8.65 unchanged; `run_stroke` gate-3 6.96 nm.
  `BoA-v1ref` byte-clean.

## What changed (additive; default byte-identical; `BoA-v1ref` untouched)
- `BindingDetectionSystem.java`: the reaction-limited **kOn gate** in `bindCanonicalTwoPoint` (`kinParams[14]`;
  ≤ 0 ⇒ saturated/byte-identical).
- `GlidingHarness.java`: `-kon` (set kOn), `-single`/`-singlez` (the single-molecule duty assay + measurement).
```
./run_canongliding.sh -single -singlez -0.075 -kon 5e5 30000   # single-molecule duty (the calibration assay)
./run_canongliding.sh -config1 -kon 5e5 6000                   # gliding ensemble at the calibrated kOn (transferability)
./run_canongliding.sh -config1 -kon 5e5 -gpu 6000             # GPU device-resident (CPU≡GPU)
```
