# Phase-2 step 4a — fix the Jensen/t_on over-release by time-averaging the catch input. THE GATE: averaging WORKS on the Config-1 signal.

**2026-06-27. Flag-gated (`-tauavg <ms>` on `GlidingHarness`, Config-1 path); default = instantaneous, byte-
identical. NO default flipped.** Step 3 (`PHASE2_KON_CALIBRATION`) flagged Guard 2: t_on ≈ 3.85 ms vs the ~10 ms
target — the **Jensen effect** (the catch reads the instantaneous J1-strain, ±RMS fluctuation at mean ≈ 0; the
convex exponential rate ⇒ ⟨e^(−Fx)⟩ > e^(−⟨F⟩x) ⇒ over-releases even at zero mean). This step tests whether
time-averaging the catch input (the banked `RELEASE_FORCE_INPUT`/`-tauavg`, which FAILED on the OLD overshoot-
contaminated signal) **works now that Config-1 removed the overshoot**. Catch constants + kOn (5×10⁵) + κ HELD —
only instantaneous→averaged changes.

## TL;DR — FORK VERDICT: **AVERAGING WORKS.** Recommended window **τ ≈ 0.5 ms** (plateau 0.1–1 ms).
- **Test 1 (t_on recovery, zero load):** averaging recovers **t_on 3.85 → ~9.5 ms** (→ the 1/kOff = 10 ms target)
  over a **wide τ ∈ [0.1, 1] ms plateau**. Jensen over-release FIXED.
- **Test 2 (force-response GUARD):** the force-dependence is **PRESERVED** under averaging — at τ = 0.5 ms,
  t_on rises monotonically **1.0 ms (−3 pN) → 9.5 ms (0) → 33.5 ms (+3 pN)** (a 33× range across ±3 pN), the whole
  curve shifted up ~2.5× vs instantaneous (the Jensen fix applied uniformly, NOT by smoothing the load).
- **Test 3 (timescale separation):** **τ_thermal ≈ 0.01 ms** (the J1-strain autocorrelation is white beyond ~1
  step) **≪ τ_avg (0.1–1 ms) ≪ τ_load (~ms, the engagement/stroke)** — a CLEAN separation ⇒ a window exists that
  suppresses thermal while tracking the real load. (The plateau confirms it; only at τ ≥ 2 ms does t_on dip
  — the window starting to touch τ_load.)
- **Why it works now (vs the dt-arc failure):** the OLD signal had stiff-spring **overshoot spikes** (large,
  contaminating transients) no window could suppress without killing the load; Config-1 removed the overshoot ⇒
  the signal is **clean white thermal fluctuation around a real mean** (τ_thermal 0.01 ms, RMS ~1.5 pN) ⇒ a
  well-posed averaging problem.
- **Peek (the velocity tension begins to resolve):** restoring t_on **raises the gliding velocity** |v| 0.2 → 1.0–1.5
  µm/s (~4×) and avgBound 3.7 → ~7 (into v1's 7.6 ballpark) — confirming "one problem (short t_on), two observables
  (dwell + ensemble velocity)."
- **CPU≡GPU** within-SEM (avgBound 7.49/7.80); **default byte-identical** (default gliding 8.65, `run_stroke` 6.96 nm).

---

## The mechanism (reused `RELEASE_FORCE_INPUT`)
The catch reads a per-head **EMA of the J1-strain** `⟨F⟩_τ` (window τ, weight α = dt/τ) BEFORE the exponential,
instead of the instantaneous F (`NucleotideCycleSystem.catchSlipReleaseAvg`, already built/validated). Averaging the
INPUT before exponentiating is the Jensen fix: `e^(−⟨F⟩x) < ⟨e^(−Fx)⟩` ⇒ slower (correct mean-load) release. Wired
into the Config-1 single-molecule (`singleStep`) + gliding (CPU `stepOrig` + the GPU `buildPlan` default branch) when
`-tauavg` is set; the seed-at-bind + free-reset logic is the existing kernel's. A **sustained-load injection**
`kinParams[18] = F_ext` (added to the catch input, `-fext <pN>`) imposes a controlled mean load for the guard
(measurement only; 0 ⇒ byte-identical). Catch constants + kOn + κ HELD.

## Test 1 — t_on vs τ at ZERO mean load (single-molecule, Config-1, kOn 5e5, dt 1e-5)
| τ (ms) | 0 (inst) | 0.1 | 0.3 | 0.5 | 1.0 | 2.0 | 5.0 |
|---|---|---|---|---|---|---|---|
| **t_on (ms)** | **3.85** | 9.46 | 9.72 | **9.45** | 9.40 | 8.66 | 7.81 |
| duty | 0.0134 | 0.038 | 0.042 | 0.039 | 0.038 | 0.033 | 0.029 |

⇒ averaging recovers t_on → ~9.5 ms (the 10 ms zero-load dwell) over τ ∈ [0.1, 1] ms; the dip at τ ≥ 2 ms is the
window beginning to smooth the real load (τ_avg → τ_load). **The duty rises with t_on** (1.34 % → ~3.9 %) — the
expected consequence of the fix, and the trigger for the step-4b joint kOn re-tune.

## Test 2 — the force-response GUARD (t_on vs sustained F_ext, τ = 0.5 ms vs instantaneous)
| F_ext (pN) | −3 | −2 | −1 | 0 | +1 | +2 | +3 |
|---|---|---|---|---|---|---|---|
| t_on inst (ms) | 0.53 | 0.97 | 1.86 | 3.85 | 7.50 | 12.96 | 19.14 |
| **t_on τ=0.5 (ms)** | **1.00** | **2.25** | **4.47** | **9.45** | **15.18** | **22.14** | **33.50** |

⇒ the averaged catch **STILL detaches faster under assisting load and slower under resisting load** — the
force-dependence survives (a 33× monotonic range across ±3 pN). Averaging lengthens t_on ~2.5× at EVERY load (the
uniform Jensen fix), it does NOT flatten the response. **GUARD PASS.** (Structurally expected: the EMA passes DC —
the sustained mean load — while suppressing the AC thermal fluctuation.)

## Test 3 — timescale separation diagnostic
J1-strain (forceDotFil) autocorrelation C(lag): **C(1 step = 0.01 ms) = 0.20 < 1/e**, ~0 by 2 steps ⇒ **τ_thermal ≈
0.01 ms** (essentially white — sub-step correlation; var ≈ 2.2 pN²). τ_load ≈ the engagement/stroke timescale (bound
lifetime ~ms; powerstroke on the nucleotide cycle). **τ_thermal (0.01 ms) ≪ τ_avg (0.1–1 ms) ≪ τ_load (~ms)** — a
clean separation ⇒ a window suppresses thermal while tracking the real load. This is exactly the condition the
dt-arc lacked (there the "fluctuation" was overshoot spikes overlapping the signal).

## Secondary peek — does restored t_on raise the gliding velocity?
gliding (v1box, Config-1, kOn 5e5), instantaneous vs τ = 0.5 ms:
| | τ=0 (inst) | τ=0.5 ms |
|---|---|---|
| steady \|v\| (µm/s) | 0.18 / 0.43 | **1.05 / 1.47** |
| avgBound | 3.65 / 3.75 | **8.18 / 5.43** |

⇒ restoring t_on (~2.5× longer dwell) **raises the gliding velocity ~4×** (|v| 0.2 → 1.0–1.5) and avgBound ~2× (3.7 →
~7, into v1's 7.6 ballpark). The velocity tension (step 3) **begins to resolve once t_on is fixed** — confirming the
single-problem/two-observable hypothesis. (Still below v1's 8.33; the full joint kOn re-tune is 4b.)

## Bail boundaries — outcome
- **A working τ recovers dwell only by killing the force-response** → did NOT happen — the force-response is fully
  preserved (33× range at τ = 0.5 ms). **Averaging WORKS.**
- **τ_thermal and τ_load overlap (no separation)** → did NOT happen — τ_thermal 0.01 ms ≪ τ_load ~ms (100×+ apart).
- **t_on short for a DIFFERENT reason** → confirmed Jensen: averaging the input (not the rate) recovers it, and the
  separation diagnostic explains it.

## FORK verdict + what's next (step 4b)
**AVERAGING WORKS** — τ ≈ 0.5 ms (window 0.1–1 ms) recovers t_on → ~9.5 ms at zero mean AND preserves the
sustained-load response. The dt-arc's banked tool pays off on the cleaned-up (overshoot-free) Config-1 signal.
**Step 4b follows:** the catch force-dependence calibration (xCatch/d to Veigel d ≈ 2.7 nm) ON the averaged input +
the **joint kOn re-tune** (the t_on fix raised the single-molecule duty to ~3.9 % ⇒ lower kOn to re-hit 1–2 %) +
the gliding-velocity transferability re-check (the peek shows it climbing). **τ reported for sign-off; no default
flipped** (`TAU_AVG` = 0 ⇒ instantaneous, byte-identical).

## CPU-fallback disclosure
The single-molecule assay (`-single`) and the τ/F_ext sweeps run on the **`-cpu` sequential runner** (256 motors,
sub-second/step — a measurement instrument). The velocity peek + CPU≡GPU ran the gliding path on both runners (the
device-resident GPU TaskGraph builds with the averaged-catch task). No long ensemble.

## What changed (additive; default byte-identical; `BoA-v1ref` untouched)
- `MotorStore.java`: `kinParams` grown 18 → 20 (`[18]` = F_ext sustained-load injection, default 0 ⇒ byte-identical);
  `setExtLoad(pN)`. `setReleaseForceAvg` (the EMA window) already existed.
- `NucleotideCycleSystem.java`: the F_ext sustained-load injection added to the catch input in BOTH
  `catchSlipRelease` + `catchSlipReleaseAvg` (size-gated `[18]`; 0 ⇒ byte-identical).
- `GlidingHarness.java`: `-tauavg` (wire `catchSlipReleaseAvg` into the Config-1 single-molecule + gliding CPU/GPU
  paths), `-fext` (the guard), `-acorr` (the τ_thermal autocorrelation diagnostic).
```
./run_canongliding.sh -single -singlez -0.075 -kon 5e5 -tauavg 0.5 30000      # t_on recovery (zero load)
./run_canongliding.sh -single -singlez -0.075 -kon 5e5 -tauavg 0.5 -fext -2 20000   # force-response guard
./run_canongliding.sh -single -singlez -0.075 -kon 5e5 -acorr 30000           # τ_thermal autocorrelation
./run_canongliding.sh -config1 -kon 5e5 -tauavg 0.5 6000                       # gliding velocity peek
```
