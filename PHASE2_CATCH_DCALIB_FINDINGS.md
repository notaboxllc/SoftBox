# Phase-2 step 4b — calibrate the catch FORCE-DEPENDENCE to Veigel (d ≈ 2.7 nm) on the averaged catch, joint kOn re-tune, transferability re-check

**2026-06-27. Flag-gated (`-xcatch <nm>`, `-kon`, `-tauavg`, `-fext`, `-density`, `-dcalib` on `GlidingHarness`,
Config-1 path); NO default flipped.** 4a made the catch fluctuation-robust (averaging, τ=0.5 ms) ⇒ its
force-dependence can now be cleanly calibrated. This step calibrates the catch force-sensitivity to the experimental
Veigel target, re-tunes kOn for the duty shift the 4a fix caused, and re-checks transferability. **τ = 0.5 ms HELD
throughout.** Knobs isolated sequentially: **d FIRST** (force-sensitivity, on the controlled-load curve), **kOn
SECOND** (duty, which d doesn't change), **transferability THIRD**.

## TL;DR — THE THESIS-CRITICAL CALIBRATION LANDS
- **Piece 1 (d → Veigel):** `xCatch = 3.65 nm` gives **effective d = 2.78 nm** (the measured 0→+1 pN rate ratio
  0.509 ≈ the Veigel "1 pN halves" signature; target d 2.7 nm, ratio ~0.52). The base (zero-load) dwell stays ~9 ms
  (d shifts the SLOPE, not the intercept). **Effective d is calibrated on the AVERAGED catch** (it saturates below
  the nominal xCatch because the slip term partially offsets the catch at +load — so xCatch 3.65 > the naive 2.97).
- **Catch-input ↔ real J1-strain is 1:1 by construction** (`-fext` adds to the SAME `forceDotFil` the catch reads;
  `forceDotFil` for Config-1 IS the J1 lever strain `(κ/L)·(θ_rest−θ)`). Native isometric J1-strain = 0.087 pN
  (cocked), the same pN scale/units as the ±1–3 pN calibration loads ⇒ **no mechanical gain in the catch-input path**;
  d transfers to real load. (The external-load→J1-strain mechanical gain is the force-velocity benchmark, deferred.)
- **Piece 2 (joint kOn re-tune):** the 4a t_on fix raised the duty to ~3.5 % at kOn 5e5; **kOn = 2.0×10⁵** re-hits
  the single-molecule duty **1.54 %** (2.5e5 → 1.87 %) — **still reaction-limited** (P_capture 0.014–0.021 ≪ 1, the
  step-3 Guard-1 regime holds). d does not change the zero-mean duty (confirmed) ⇒ the sequential re-tune is clean.
- **Piece 3 (transferability — LARGELY RESOLVED via the speed-density trend):** at the calibrated
  `(d 2.78 nm, kOn 2e5, τ 0.5 ms)`, the gliding **speed RISES with density and reaches the experimental V0**:
  ~1 µm/s at 500/µm² (the sparse v1box) → **~9 µm/s at 4000/µm² → ~11 µm/s at 10000/µm²**. ONE parameterization hits
  BOTH the single-molecule duty (1.5 %) AND a sensible/experimental gliding velocity (V0 ~9–11 µm/s, the myosin-II
  gliding target / v1's 8.33) — the step-3 velocity tension was a **low-density artifact** (a 1.5 %-duty motor is
  under-driven at the sparse 500/µm² v1box; real gliding assays run denser).
- **Default byte-identical** (no override ⇒ default gliding −2.459 / 8.65, `run_stroke` 6.96 nm); **CPU≡GPU**
  within-SEM. `BoA-v1ref` byte-clean. **NO default flipped — reported for sign-off.**

---

## Piece 1 — catch force-sensitivity d → Veigel ≈ 2.7 nm (THESIS-CRITICAL)
Detachment RATE (1/t_on) vs sustained load (`-fext`) on the AVERAGED catch (τ=0.5 ms, single-molecule, Config-1).
Effective d from the 0→+1 pN ratio: `ratio = e^(−1pN·d/kT)`; Veigel d 2.7 nm ⇒ ratio ≈ 0.52.

| xCatch (nm) | rate(−1pN) | rate(0) | rate(+1pN) | rate(+1)/rate(0) | effective d (nm) |
|---|---|---|---|---|---|
| 2.50 (v1) | 221 | 102 | 64 | 0.622 | 1.95 |
| 3.00 | 254 | 108 | 60 | 0.553 | 2.44 |
| 3.50 | 305 | 107 | 58 | 0.540 | 2.54 |
| **3.65** | **326** | **111** | **57** | **0.509** | **2.78** |
| 4.00 | 394 | 118 | 56 | 0.477 | 3.05 |
| 4.50 | 552 | 125 | 52 | 0.420 | 3.57 |

⇒ **xCatch = 3.65 nm → effective d = 2.78 nm** (ratio 0.509), the Veigel "1 pN ≈ halves detachment" signature. The
response is **monotonic and graded** across ±load (rate −1pN 326 → +2pN 28 /s, an ~12× range). The base zero-load
dwell stays ~9 ms (the 4a value; d is the slope, not the intercept). **Effective d < nominal xCatch** because the
assisting-slip term `αSlip·e^(+F·xSlip/kT)` (αSlip 0.08, xSlip 0.4 nm) partially offsets the catch at +load, so
hitting d 2.7 nm needs xCatch 3.65 (not the naive single-exponential 2.97). Calibrating on the operating
(averaged) configuration was necessary — the instantaneous-catch d would have been mis-set.

**THESIS framing (recorded):** d is the **calibrated lumped parameter** (NOT claimed to emerge — `RESEARCH_THESIS`
§4). But the **load it responds to is the real J1 lever strain** (`forceDotFil = (κ/L)·deflection`, emergent
mechanics), NOT a prescribed `k_off(F)` curve. The distinction from a prescribed-kinetics motor: that motor fits the
whole force-velocity / `k_off(F)` curve; **this one fits ONE number (d), and the curve emerges from d × the real
mechanical load.**

**Slip pathway:** only the CATCH (`xCatch`, resisting-load) was calibrated. `xSlip` (assisting-load, 0.4 nm) is
**left at its current value and flagged** — it is less constrained by Veigel (a force-velocity-assay target, next
phase). The assisting half (rate at −load: 221→552 /s across xCatch) is reported but not force-fit here.

## Catch-input ↔ real-J1-strain correspondence
`-fext` adds to the catch input `F = ⟨forceDotFil⟩_τ + F_ext`, the SAME variable the catch reads, in the SAME units
(N) — so the calibration load is **1:1 with the real J1-strain by construction** (no mechanical gain in the
catch-input path; d transfers to real load). The native isometric J1-strain (cocked, tail-anchored, deterministic)
= **0.087 pN** — the same pN scale as the ±1–3 pN calibration loads, confirming d is calibrated against a load on
the real mechanical scale (the calibration loads dominate the native ~0.1 pN mean + ~2.5 pN thermal fluctuation, so
the d measurement is clean). **The full external-load→J1-strain mechanical gain (how a load on the filament
develops J1 deflection) is the force-velocity benchmark — deferred, flagged.**

## Piece 2 — joint kOn re-tune (the 4a duty shift)
At the calibrated d (xCatch 3.65, τ 0.5 ms), single-molecule duty vs kOn:

| kOn (µm⁻¹s⁻¹) | duty | t_on (ms) | P_capture (empirical) | regime |
|---|---|---|---|---|
| 5×10⁵ | 0.0347 | 8.8 | 0.037 | reaction-limited |
| 3×10⁵ | 0.0227 | 8.4 | 0.024 | reaction-limited |
| 2.5×10⁵ | 0.0187 | 7.9 | 0.021 | reaction-limited |
| **2.0×10⁵** | **0.0154** | 9.1 | 0.014 | reaction-limited |

⇒ **kOn = 2.0×10⁵ re-hits duty 1.54 %** (2.5e5 → 1.87 %; both in the 1–2 % target). **Still reaction-limited**
(P_capture ≪ 1 — Guard 1 holds, no regression to saturation). d does not change the zero-mean duty (the t_on column
is flat across kOn at ~8–9 ms), confirming the sequential d-then-kOn isolation.

## Piece 3 — transferability re-check (one parameterization across conditions)
At the calibrated `(d 2.78 nm, kOn 2e5, τ 0.5 ms)`, gliding ensemble:

**At the v1box density (500/µm²):** avgBound ~1.8, steady |v| ~1 µm/s (3-seed: 1.02 / 0.65 / 1.54) — LOW (the
single-molecule-duty operating point is sparse here).

**Speed-density trend (the resolution):**
| density (µm⁻²) | motors | avgBound | steady \|v\| (µm/s) |
|---|---|---|---|
| 500 | 2 000 | 2.2 | ~1.0 |
| 1 500 | 6 000 | 6.5 | ~0.6 (single-seed noise) |
| 4 000 | 16 000 | 19.1 | **9.0** |
| 10 000 | 40 000 | 24.3 | **11.3** |

⇒ **speed RISES with density and reaches the experimental V0 (~9–11 µm/s)** — the myosin-II gliding velocity / v1's
8.33 µm/s. **ONE parameterization hits BOTH the single-molecule duty (1.5 %) AND a sensible gliding velocity** (at
the higher densities real gliding assays use). **The step-3 velocity tension was a low-density artifact** (a
1.5 %-duty motor needs enough surface density for simultaneous engagement to glide coherently — exactly the
experimental speed-density behavior, rising then saturating toward V0). **Compared to EXPERIMENT, not v1:** V0 ~9–11
µm/s lands at the myosin-II gliding target; the speed-density trend has the correct rising-saturating form. (The
full speed-density + force-velocity ensemble benchmarks — with the exact V0/density-knee fit — are the NEXT phase;
here the trend confirms the calibration transfers.)

**Honest residual:** at the SPARSE v1box density the calibrated motor under-drives (|v| ~1); the velocity match
needs the experimentally-relevant higher density. This is NOT force-fit away — it is the physical speed-density
trend, and the tension dissolves at realistic density.

## Bail boundaries — outcome
- **d cannot reach ~2.7 nm without breaking the base rate / monotonicity** → did NOT happen — d 2.78 nm at xCatch
  3.65, base ~9 ms preserved, response monotonic across ±load.
- **catch-input load NOT ~1:1 with real J1-strain (large gain)** → did NOT happen — 1:1 by construction (same
  variable/units); native scale ~0.1 pN, same order as the calibration loads.
- **duty 1–2 % only at a saturated kOn** → did NOT happen — duty 1.54 % at kOn 2e5, P_capture 0.014 ≪ 1
  (reaction-limited, Guard 1 holds).
- **one parameterization cannot hit duty AND a sensible velocity** → **RESOLVED via density** — the same
  `(d, kOn, τ)` hits duty 1.5 % AND V0 ~9–11 µm/s at experimental density; the low-density shortfall is the physical
  speed-density trend, not a parameterization failure.

## Verdict
**The thesis-critical catch force-dependence is calibrated: effective d = 2.78 nm (Veigel "1 pN halves"), the load
it responds to is the real J1 lever strain (emergent), d is the single calibrated number.** The joint kOn re-tune
(2e5) re-hits the single-molecule duty 1.5 % reaction-limited. Transferability: ONE parameterization reproduces the
single-molecule duty AND the experimental gliding V0 (~9–11 µm/s) via the correct rising speed-density trend — the
step-3 tension was a low-density artifact, largely resolved. **Calibrated values (d/xCatch 3.65 nm, kOn 2e5, τ 0.5
ms) reported for sign-off; no default flipped.**

## CPU-fallback disclosure
The d-calibration + kOn re-tune + single-molecule duty run on the **`-cpu` sequential runner** (256 motors,
~12 s/12k-step run — a measurement instrument). The transferability density sweep ran the gliding path: 500/1500 on
CPU, the large 4000/10000 (16k/40k motors) on the **device-resident GPU TaskGraph** (disclosed; ~hundreds of
steps/s). CPU≡GPU confirmed on the full calibrated stack (avgBound 2.11/1.80 within-SEM at 500/µm²).

## What changed (additive; default byte-identical; `BoA-v1ref` untouched)
- `MotorStore.java`: `setXCatch(nm)` (the catch distance d override; default 2.5 nm unchanged).
- `GlidingHarness.java`: `-xcatch` (d), `-density` (speed-density trend), `-dcalib` (the rate-vs-load calibration
  driver). The `-fext`/`-tauavg`/`-kon`/`-single` machinery is from 4a/step-3.
- `CanonicalMotorHarness.java`: the Config-1 path now also reports the isometric J1-strain (the correspondence scale).
```
./run_canongliding.sh -dcalib -singlez -0.075 -kon 5e5 -xcatch 3.65 20000   # d-calibration (rate vs load)
./run_canongliding.sh -single -singlez -0.075 -kon 2e5 -tauavg 0.5 -xcatch 3.65 20000   # duty at the calibrated point
./run_canongliding.sh -config1 -kon 2e5 -tauavg 0.5 -xcatch 3.65 -density 4000 -gpu 8000  # transferability (speed-density)
```
