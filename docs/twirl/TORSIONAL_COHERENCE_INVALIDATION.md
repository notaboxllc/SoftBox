# TORSIONAL COHERENCE — INVALIDATION NOTICE (2026-09-06)

**Every twirl MAGNITUDE measured before this date was taken on a filament with ZERO inter-segment torsional
coupling.** Segments rolled independently. The filament did not rotate as a body, which is the assumption every
twirling EXPERIMENT makes and the assumption behind every pitch comparison we have published internally.

## 1. The defect

`ChainBendingForceSystem` never receives `yVec` — the roll-carrying vector is not even a kernel parameter. Its
F4 "torsion" term is `cross(u_i, u_j)`, a ⊥-axis bending straightener that applies **zero torque about the
shared axis**. The name is misleading; there is no torsional constraint.

This is **inherited, not a porting error**: v1 has the same hole and documents it
(`AZIMUTHAL_BINDING_BUILD_READ.md` Verdict A — *"segment roll is a free, uncoupled, random-at-birth DOF"*).

`RollSpringSystem` (built 2026-07-09, Azimuthal-binding Increment 1, `docs/ROLL_SPRING_PROTOTYPE.md`) is the
fix and was validated in isolation — but it was **never wired into the site-normal gliding lineage**.
`ChiralSiteHarness` asserts its absence as check 108: *"RollSpringSystem / filament torsion spring / bound
registry all OFF"*.

## 2. How it was found

Three fluorescent probes placed on DIFFERENT segments of the same filament in the 5 µm movie. If the filament
rotated as a body their lab-frame phases would advance together. Measured over 959 frames:

| | probe0 | probe1 | probe2 |
|---|---|---|---|
| total rotation | −12.25 turns | −3.11 turns | −8.64 turns |

Internal twist drift **+9.14 turns** (probe1−probe0) and **+3.61 turns** (probe2−probe0). The filament was
accumulating unbounded internal twist. This is invisible in every aggregate scalar we log; it took material
markers on separate segments to see it.

## 3. INVALIDATED — all absolute twirl magnitudes

| campaign | reported pitch | status |
|---|---|---|
| `TWIRL_SKEW15` (ε=15, η=0.1) | 0.248 µm | magnitude VOID |
| `TWIRL_EPS_LADDER` (ε=5/10/15) | 0.640 / 0.417 / 0.248 µm | magnitude VOID |
| `TWIRL_RANDBASE_LADDER` (ε=15) | 0.145 µm | magnitude VOID |
| `LOWVISC_STROKE_CPU` (ε=15, η=0.01) | 0.136 µm | magnitude VOID |
| `LOWVISC_EPS4_CPU` (ε=4, η=0.01) | 0.695 µm | magnitude VOID |
| 5 µm movie (ε=4, ρ=2000) | 0.556 µm | magnitude VOID |

**Downstream claims that fall with them:**

1. **"Twirling of the same order as experiment."** Every comparison against Beausang 2008
   (0.47 ± 0.19 µm, ≈2.1 turns/µm) is void.
2. **The ε calibration** — "ε ≈ 4° lands inside the experimental band" — the basis for choosing ε for the movie.
3. **The structural-ε argument** (`ACTIN_SITE_LATTICE_LITERATURE_BASIS.md` §7.4): the "3–7° required" figure was
   back-derived from matching experimental pitch on an uncoupled filament. The NEGATIVE conclusion (no structural
   channel supplies ε) is not weakened — but its numbers are wrong and must be re-derived.
4. **`SITE_LATTICE_TWIRL_NULL.md`** — rigid register-tracking (13.9 turns/µm, 72 nm) "excluded at 82σ" is a
   magnitude comparison against an uncoupled measurement.
5. **`VISCOSITY_SENSITIVITY_FINDINGS.md` Part II — hit hardest.** Its mechanism claim, *"rotation is drag-limited
   (Ω ∝ η^−1.2, τ_odd flat) while translation is not"*, rests on a **per-segment** rotational drag. With coupling
   the rotating object is ~12× longer with a different drag law. `Ω_odd ∝ η^−1.358`, `turns/µm ∝ η^−0.97`, the
   V1/V2/V3 classification, and the *"canonical η is blind to twirling"* verdict all require re-derivation.
6. **Vilfan target-zone twirling** pitch comparisons and the `TWIRLING_MECHANISM_ATLAS_FINDINGS.md` pitch entries.

## 4. SURVIVES — sign and relative structure

- **Chirality itself**: the ε-odd sign structure, the mirror-control SIGN REVERSAL (75 % seed sign, 4.5σ from
  no-reversal), and the monotonic rise of twirl with ε. These are antisymmetry results, independent of roll
  magnitude.
- **The nulls** — converter skew, A4 registry, S2 catch-stiffening, load-gating. Coupling cannot turn a null into
  a positive. Their stated statistical POWER is now unreliable, the verdicts are not.
- **The gliding work** — two-point attachment, linkage softening, kbind. Translation results with several-fold
  effect sizes; the roll spring shifts avgBound ~15 %, flipping no conclusion.

## 5. First re-measurement (in flight, ε=4, ρ=2000, η=0.1, `-rollspring`)

| | spring OFF | spring ON (f=0.5) |
|---|---|---|
| probe turns | −12.25 / −3.11 / −8.64 | **−1.43 / −1.59 / −1.82** |
| internal twist drift | +9.14, +3.61 turns | **−0.16, −0.39 turns** |
| turns/µm | −2.40 | **−3.39** |
| v (µm/s) | 1.02 | 1.41 |

Coherence improves **20–50×**. **Twirl per micron INCREASES** (pitch 0.417 → 0.295 µm): with segments coupled,
motor torques distributed along the filament ADD coherently instead of cancelling into independent rolls.

**A forecast recorded here as refuted:** on the strength of an 18 000-step smoke (roll −0.026 ON vs −0.374 OFF)
it was predicted that pitch would grow to several µm and fall OUTSIDE the experimental band. That was an early
transient and the direction is opposite. Re-measure; do not adjust old numbers by any assumed factor.

## 6. SCOPE — torsional calibration

**Priority (jba, 2026-09-06): the requirement is that the filament rotates AS A WHOLE, not that the torsional
modulus matches actin's measured value.** Coherence is the gate; physical stiffness calibration is secondary and
explicitly deferred.

### 6.1 Primary gate — COHERENCE (blocking)

Observable: **residual internal twist drift per micron**, from material probes on separated segments
(`-probes N`, differential unwrapped phase). Not an aggregate scalar — the defect was invisible to every scalar
we log.

| state | drift over 0.5 µm |
|---|---|
| spring OFF | +9.14 / +3.61 turns |
| f = 0.5 | −0.16 / −0.39 turns |
| target | ≲0.05 turns/µm, i.e. residual ≪ 10 % of total rotation |

At f = 0.5 the residual is ~0.3–0.8 turns/µm against ~3.4 turns/µm of bulk rotation — **roughly 10–20 %
incoherent**. Good, not yet rigid.

**Action:** sweep `-rollstiff` f ∈ {0.5, 1.0, 1.5} at fixed seed/ε/ρ/η, 1 µm each, and report drift per micron,
turns/µm, v, avgBound, and invalid/solverFail. Pick the smallest f whose drift meets the target. Stability bound
is f < 2 by construction (α = f, fraction-per-step); f ≥ 2 is expected to be unstable and is the negative control.

### 6.2 Secondary — physical stiffness (DEFERRED, not blocking)

`ROLL_SPRING_PROTOTYPE.md` contains no reference to actin torsional rigidity: the prototype was validated for
coherence and stability, never against a modulus. `f = 0.5, mode = 2` are prototype defaults, **uncalibrated**.

Reference values if this is ever taken up: C ≈ 2.8 × 10⁻²⁶ N·m² for Mg-actin (F-Ca²⁺ 8.5 × 10⁻²⁶, ~3× stiffer —
Yasuda 1996; physiological Mg-actin is the SOFTER form and most models wrongly adopt the Ca²⁺ value). Torsional
persistence length `C/kT ≈ 7 µm`, so over a 2.1 µm filament real actin is torsionally stiff — consistent with
the coherence requirement above. Mapping mode-2 springs-continuum `k = f·γ_red/refDt` onto C is the open task.

**Standing caveat until 6.2 is done:** we have replaced *no constraint* with an *uncalibrated* one. Re-measured
pitch is a number obtained under a stated, reproducible torsional coupling — it is NOT yet a physical prediction
of actin's twirl pitch, and must not be compared to Beausang as though it were.

### 6.3 Re-measurement queue (after the f sweep)

1. ε-ladder at η = 0.1 (ε = 5/10/15) — restores the ladder and its monotonicity with correct magnitudes.
2. ε = 4 at η = 0.1, matched to the current 3 µm run — the new reference pitch.
3. Low-viscosity ε = 4 at η = 0.01 — the publication condition; also the only clean viscosity/occupancy separation.
4. `VISCOSITY_SENSITIVITY` Part II Ω(η) — the largest single re-derivation; its drag law changes qualitatively.
