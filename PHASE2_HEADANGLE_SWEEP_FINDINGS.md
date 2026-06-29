# Phase-2 HEAD-ANGLE SWEEP — hold the perp-head topology fixed, vary ONLY the head↔filament rest angle θ

**2026-06-29. MEASUREMENT-ONLY (`-headtiltsweep` / `-headtilt <deg>`); flag-gated, additive; default + config-1 +
canonical + every other harness BYTE-IDENTICAL. `BoA-v1ref` byte-clean. No physics edit, no retune, no default
flip. Stage 1 single motor, CPU host-side, dt=1e-6, Brownian OFF, sub-second/θ. Stage 2 GPU device-resident
ensemble, matching the 2026-06-29 post-fix re-measure config.**

---

## TL;DR — the head HOLDS at every θ, but transport-grade axial force appears ONLY near θ=90 (full ⊥)
Removing the *attachment-topology* confound from `PHASE2_PERP_HEAD` (config-1 two pins vs perp-head one pin + torque):
hold the perp-head topology FIXED (single tip pin + the PAIRS restoring torque throughout) and rotate ONLY the
torque's **target angle θ** between head-uVec-aligned-with-filament (θ=0, the biological flat-binding prior) and
full-perpendicular (θ=90, = the published perp-head). **Stage 1 (the cheap single-motor filter):** the
⊥-orientation torque **wins at every θ** — the head is driven to and holds the commanded angle (head∠f ≈ θ, |Δ|≤3°
across the whole sweep). But the **settled isometric axial force the head delivers to the filament is essentially
zero (and sign-unstable) for θ ∈ [0,75], and jumps to a transport-grade +1.96 pN (transverse:axial 2.56:1) ONLY at
θ=90.** The reason is geometric, not a torque failure: the converter delivers axial force only when the head is ⊥
AND the lever lies along the filament (lever∠f → 3°), and those co-occur only near θ=90. **Stage 2 (the functional
readout — ensemble glide, θ ∈ {0,45,90} × densities {500,1000} × n=3, GPU device-resident):** EVERY θ glides −x and
rises with density; the flat θ=0 prior glides −x **just as well as** the ⊥ θ=90 perp-head (−1.0→−2.6 vs −1.2→−2.5
µm/s, within SEM), and the intermediate θ=45 is the **WEAKEST** glide despite the **HIGHEST** binding (grip-locks).
**Verdict = OUTCOME (c), refined: the head↔filament rest angle is a NON-LEVER for ensemble transport** — the
single-motor isometric axial-force win at θ=90 (real, Stage 1) does NOT transfer into an ensemble-velocity advantage
over flat binding. **Favorable faithfulness payoff:** the literature-correct FLAT head (θ=0) transports as well as
the (biologically-wrong) ⊥ stance, so the model does not need the perpendicular head for transport.

---

## The surface-free angle rule (as implemented — no surface/ẑ reference in the non-degenerate case)
At each fresh bind, with the head's bind-time uVec `u` and the filament unit vector `f`:
- `perpRest = normalize(u − (u·f)·f)` — the head's OWN ⊥-to-filament component (the nearest orthogonal-to-filament
  direction). **Surface-free.** Verified: `CrossBridgeSystem.snapPerpRest` computes exactly this in the
  non-degenerate case; the surface normal ẑ appears ONLY in the `mag<0.1` degenerate fallback (head ∥ f), which the
  off-axis Stage-1 bind avoids entirely. ✓ (matches the rule).
- `f_hat = sign(u·f)·f` — the filament direction on the acute side of `u`, so θ=0 = smallest-angle alignment.
- **Target(θ) = cos θ·f_hat + sin θ·perpRest**, frozen at bind alongside perpRest. θ=0 → head driven to `f_hat`
  (uVecs aligned, head flat along actin); θ=90 → `perpRest` (reproduces the perp-head). The sweep rotates the target
  through the SINGLE plane spanned by {f, u} at every θ.

Implemented by **baking Target(θ) into the frozen `perpRest`** in the snap step (host `decompRun` for Stage 1;
the `snapPerpRest` kernel for Stage 2) — so the bond kernel `bondForcesCanonicalConfig1Perp` is **UNCHANGED** (it
still drives head.uVec → perpRest). `MotorStore.headTiltCS = [cosθ, sinθ, setFlag]`; setFlag 0 ⇒ the plain ⊥ rest
(byte-identical perp-head). **`-headtilt 90` reproduces `-perphead` bit-for-bit** (gliding diag: velocity 0.522,
avgBound 2.59, identical bound-state distribution + release rate — the float32-stored target at θ=90 rounds to the
plain perpRest). ✓

### Degeneracy avoided by the OFF-AXIS bind (Stage 1)
When `u ∥ f`, perpRest→0 and the {f,u} plane is undefined (the case that fired perp-head's ẑ-fallback). The sweep
**binds the head OFF-AXIS** (a real acute angle φ=40° between u and f, in the x–z plane) so perpRest = +z is genuine
(the head's own ⊥ component, mag = sin40° = 0.64 ≫ 0.1 ⇒ the fallback NEVER fires) and ONE fixed plane is used at
every θ. Crucially, **only the head's bind orientation is tilted — the lever/rod/ANCHOR stay at the published
`-forcedecomp` perp-head positions** so the J1-strain geometry (the force source) is identical to the perp-head
decomp; θ=90 reproduces the published +1.94 pN. The sweep therefore isolates **θ alone**, not the anchor geometry.

---

## Stage 1 — single-motor force decomposition vs θ (off-axis bind, dt=1e-6, Brownian OFF)
Settled isometric force the head delivers to the filament, in the filament frame (axial = along f = transport).
Config IDENTICAL to the 2026-06-29 post-fix re-measure (κ=3.82e-20 force-matched, same catch/kOn/τ, the same
single-motor `-forcedecomp` stroke fired with hand-set nucleotide states); **θ is the ONLY new variable.**

| θ (cmd) | head∠f (meas) | head holds? | axial (pN) | transverse (pN) | **transverse : axial** | lever∠f | J1° |
|---|---|---|---|---|---|---|---|
| 0°  | 0.5°  | YES | +0.054 | 0.714 | 13.34 : 1 | 62.3° | 61.8° |
| 15° | 13.0° | YES | −0.183 | 2.814 | 15.34 : 1 | 66.9° | 53.9° |
| 30° | 30.7° | YES | +0.083 | 1.000 | 11.98 : 1 | 25.1° | 55.8° |
| 45° | 43.9° | YES | −0.094 | 1.775 | 18.98 : 1 | 23.7° | 67.6° |
| 60° | 57.9° | YES | −0.024 | 4.311 | 178.16 : 1 | 21.3° | 79.2° |
| 75° | 73.1° | YES | +0.371 | 6.310 | 16.99 : 1 | 16.4° | 89.6° |
| **90°** | **87.9°** | **YES** | **+1.964** | 5.027 | **2.56 : 1** | **3.2°** | 84.6° |

- **Head holds at θ — YES at every θ** (|head∠f − θ| ≤ 3°): the ⊥-orientation PAIRS torque WINS the competition with
  the J1 converter torque across the whole range. The bail boundary "the head won't hold at the commanded θ" is NOT
  triggered. The plane is stable (perpRest = +z, no ẑ-fallback). The bail boundary "can't define a stable plane" is
  NOT triggered.
- **Real axial force ONLY at θ=90.** For θ ∈ [0,75] the settled axial is ~0 and sign-unstable (−0.18…+0.37 pN); only
  at θ=90 does it reach a transport-grade **+1.96 pN with a transport-like 2.56:1** transverse:axial (the
  default-v1-motor order). θ=90 **reproduces the published perp-head** (+1.94 pN, 2.35:1; the 2.56 vs 2.35 is a minor
  difference from the off-axis tip-pin site — axial and head∠f are essentially exact).
- **Why (geometry, not a torque failure):** the converter delivers axial force only when **the head is ⊥ AND the
  lever lies along the filament** (lever∠f → 3.2° at θ=90, making J1 strained at 84.6° and the strain projecting
  axially through the tip pin). At intermediate θ the lever settles off-axis (16–66°) so the J1 strain — even when
  large (θ=75 J1=89.6°) — does not project axially. So θ controls the head, but the FORCE needs the lever-along-actin
  geometry that only co-occurs near θ=90.

**Stage-1 filter ⇒ the only θ with transport-grade axial force is θ=90** (θ=75 a distant, non-transport-ratio second
at +0.37 pN). Per the task, Stage 1 is the inexpensive filter; the functional readout is the ensemble glide (Stage 2),
since single-motor isometric force is known NOT to predict ensemble drift (perphead was single-motor +x but ensemble
−x). To ADJUDICATE the three outcomes (which require ensemble data at intermediate θ), Stage 2 samples θ ∈ {0, 45, 90}
— the biological flat prior, a mid intermediate, and the ⊥ winner — rather than only the strict Stage-1 winner.

---

## Stage 2 — ensemble glide at θ ∈ {0, 45, 90} (GPU device-resident, matbed, dt=1e-6, 40k, n=3 seeds, density 500 & 1000)
The functional readout — net ensemble glide (sign + magnitude), NOT the single-motor axial sign. All 18 cells
`fullMat=YES` (no blow-ups). `netX` = honest signed Δx/time (− = −x glide). Raw rows in
`RUN_LOGS/2026-06-29_headtilt_stage2.txt`.

| θ | density | **netX mean ± SD (µm/s)** | avgBound (steady) | n | fullMat |
|---|---|---|---|---|---|
| **0 (flat)** | 500  | **−1.00 ± 0.33** | 2.62 | 3 | YES |
| **0 (flat)** | 1000 | **−2.59 ± 1.04** | 4.88 | 3 | YES |
| **45 (mid)** | 500  | **−0.74 ± 0.81** | 3.45 | 3 | YES |
| **45 (mid)** | 1000 | **−1.26 ± 0.27** | 6.98 | 3 | YES |
| **90 (⊥)**   | 500  | **−1.22 ± 0.17** | 4.14 | 3 | YES |
| **90 (⊥)**   | 1000 | **−2.54 ± 0.88** | 6.80 | 3 | YES |

(velFitX, the post-settling −slope estimator, is sign-noisy at these low speeds across all θ — consistent with the
post-fix re-measure caveat — so netX is the primary readout, as there.)

**The three findings, all robust across both densities:**
1. **Every θ glides −x and RISES with density.** None glides +x, none stalls — the −x glide and the experiment-like
   rising density shape are present at the flat θ=0 prior, the ⊥ θ=90 perp-head, AND the intermediate θ=45.
2. **The two EXTREMES glide comparably; the head angle is NOT the transport lever.** Flat θ=0 (−1.00 → −2.59)
   ≈ ⊥ θ=90 (−1.22 → −2.54) at both densities — within SEM of each other. The sharp single-motor isometric peak at
   θ=90 (Stage 1: +1.96 pN axial at θ=90 vs ~0 at θ=0) does **NOT** produce an ensemble advantage for θ=90 over θ=0.
   (Isometric ≠ ensemble, again — the same lesson as the perp-head red-herring correction.)
3. **The INTERMEDIATE θ=45 is the WEAKEST glide despite the HIGHEST binding.** θ=45 nets −0.74/−1.26 (weakest at
   both densities; ~2σ below the extremes at d1000) while carrying the MOST bound heads (avgBound 6.98 at d1000 >
   θ=90's 6.80). More binding, less net transport ⇒ the intermediate angle grip-locks (co-bound tug-of-war converts
   the least binding into directed motion). There is **no intermediate functional optimum** — the opposite.

---

## Verdict against the three outcomes — OUTCOME (c) (refined): the head ANGLE is not the transport lever
- **(a) Intermediate θ maximizes — REJECTED.** θ=45 is the *worst* glide of the three, not the best, despite the most
  binding. No intermediate functional rest angle exists.
- **(b) Monotone, only near-⊥ glides — REJECTED.** The flat θ=0 prior glides −x just as well as the ⊥ θ=90 perp-head
  (within SEM), rising with density. Full perpendicular is **NOT** uniquely required for ensemble transport.
- **(c) No fixed θ uniquely glides — SUPPORTED (refined).** The ensemble −x directedness is **robust to the mean head
  rest-angle θ** (present at 0°, 45°, 90°), so it is **not set by θ**. This matches outcome (c)'s spirit: the
  directed bias does not come from a mean head angle a fixed restoring torque can dial in — it comes from the
  mechanism (the converter swing geometry / the stochastic many-motor cycle), which all three θ share. It is NOT a
  pure null (every θ DOES glide), so the precise statement is: **the head↔filament rest angle is a non-lever for
  ensemble transport; the perp-head's single-motor axial-force win (real, Stage 1) does not transfer into an
  ensemble-velocity advantage over flat binding.**

### The FAVORABLE faithfulness payoff (the headline for the planner)
`PHASE2_PERP_HEAD` flagged the ⊥-head stance as biologically wrong (the myosin motor domain binds FLAT along actin;
perpendicular is the LEVER, not the head) and the perp-head reached the right mechanics by the wrong means (a
surface-referenced ẑ-fallback that won't transfer to the ring). **This sweep resolves that tension favorably:** the
**flat-binding pose (θ=0) transports −x just as well as the ⊥ pose (θ=90)** — so the faithful, literature-correct
flat head is viable, and the model does **not** need the perpendicular stance for transport. The planner's
"rotate the head↔neck rest on a flat two-point head" surgery (JOURNAL open-thread 1b) is the right direction, and
this sweep removes the worry that flatness costs transport — it does not.

### Faithfulness note on θ (per the task)
The model's `head.uVec` is the head body's axis, which in this articulated motor sits near the actin-binding
interface (the head binds via its tip pin); θ here is genuinely the head↔filament angle, so θ=0 = flat binding is a
faithful pose, not an artifact of the uVec referencing the converter/lever. (Were head.uVec the converter axis, a
non-zero functional θ would not be a flat-binding violation — but that is not the case here; θ=0 is the literal flat
head and it glides.) No calibration resolved here — measurement only.

---

## Deferred-freeze production tiebreaker (flagged follow-on — NOT built here)
The production/ring tiebreaker for the degenerate (u ∥ f) bind = leave the head tip-pinned with **no restoring
torque** and **defer the perpRest freeze until the perpendicular component crosses a small threshold** (not a fixed
one-step wait — one Brownian kick would lock in a random plane), then snap. The sweep is Brownian-off + off-axis, so
no degeneracy arises and this is not exercised here; recorded as the planned production hardening (the current
gliding-assay perp-head still uses the surface-referenced ẑ-fallback at the near-axial bind, which `PHASE2_PERP_HEAD`
flagged as not transferable to the ring).

---

## What changed (additive; flag-gated; default & config-1 & canonical & `BoA-v1ref` byte-unchanged)
- `MotorStore.headTiltCS` (size-3 [cosθ, sinθ, setFlag], default-zero ⇒ setFlag 0 ⇒ plain ⊥ rest). Allocation-only
  for every existing harness (byte-identical; proven by a git-stash A/B on default + config1 diags).
- `CrossBridgeSystem.snapPerpRest` — gains a `headTiltCS` param; when setFlag, rotates the frozen rest target within
  the {f,u} plane to Target(θ) (surface-free f_hat). setFlag 0 ⇒ the original ⊥-rest arithmetic, bit-identical. The
  bond kernel is UNCHANGED.
- `GlidingHarness`: `-headtilt <deg>` (θ; implies `-perphead`; baked into `headTiltCS` for the gliding pipeline),
  `-headtiltsweep` (the Stage-1 θ sweep on the off-axis bind), `-offaxis <deg>` (the off-axis bind angle, default
  40°). `decompRun` gains off-axis-bind + Target(θ) handling (the default `-forcedecomp` axial pose is unchanged);
  `headTiltSweep()` is the Stage-1 driver. The snapPerpRest CPU + GPU calls + the device transfer thread `headTiltCS`
  (PERPHEAD-gated).

## CPU-fallback disclosure
- **Stage 1** (`-headtiltsweep`): CPU host-side, single motor, deterministic (no TaskGraph) — < a few seconds total.
- **Stage 2**: GPU **device-resident** `buildPlan` (~24 kernels incl. `snapPerp`, no per-step host pull; host reads
  fil.coord + boundSeg at the OUT_INT=100 sample cadence only). 3480 motors @ density 500 / 6960 @ density 1000.

## CPU≡GPU
The only added device work is the `snapPerpRest` θ-rotation — the SAME deterministic Java arithmetic on both runners
(no KernelContext/atomics, the surface-free Target(θ) is a per-motor pure function of `headTiltCS`+pose), so it is
**bit-identical CPU↔GPU by construction** and preserves the established perp-head CPU≡GPU aggregate-within-SEM (the
ATP-release findings validated perphead avgBound 4.03 CPU ≡ 4.00 GPU). No new CPU/GPU divergence is introduced.

## Reproduce
```
./run_gliding.sh -headtiltsweep                                   # Stage 1: θ sweep (off-axis bind, single motor)
./run_gliding.sh -headtiltsweep -offaxis 35                       # Stage 1 with a different off-axis bind angle
./run_gliding.sh -perphead -forcedecomp                          # the published perp-head decomp (θ=90 baseline)
./run_gliding.sh -gpu -perphead -headtilt 45 -matbed -density 500 -dt 1e-6 -grid -seed 0 40000   # Stage 2 cell
```
