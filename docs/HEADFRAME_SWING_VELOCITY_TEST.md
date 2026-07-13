# HEADFRAME_SWING_VELOCITY_TEST — does a head-frame stroke lose axial productivity with sliding velocity?

**Debate-accepted proposal (hash e9328f…), executed 2026-07-11.** Single-factor: replace the
canonical filament-directed power stroke (`directedSwing`, DIRSWING) with the already-existing
head-frame stroke (`directedSwingHeadFrame`), holding binding/chemistry/release/springs/head
constraints fixed, and measure whether the head-frame stroke loses **axial** productivity as clamp
velocity rises (⇒ lower force–velocity zero V₀), or whether AXLOCK re-axializes it into a no-op.

**Answer (one line):** the head-frame stroke does **NOT** lose axial productivity as sliding
velocity rises, and AXLOCK does **NOT** hold it axial either. It carries a **large, velocity-
INDEPENDENT static penalty** — its swing reference is chronically ~87° off the filament axis at
**every** velocity — which shifts the whole force–velocity curve down and so lowers V₀ (16.1→11.7,
ΔV₀≈−4.4 µm/s), but the off-axis angle is flat, q_HF is flat, and the velocity contrast D(v) is
**positive** (head-frame is *less* velocity-sensitive, not more). This is **H2 (static penalty),
not H1 (velocity-dependent geometric loss)**, and not H3 (not a no-op). A lower-V₀-by-being-worse
result, not the fidelity-increasing path the Stage-2 deferral pointed at.

---

## STEP-0 code audit (gate) — see `docs/HEADFRAME_SWING_CODE_AUDIT.md`

- **Single factor (H5 excluded):** `directedSwing` and `directedSwingHeadFrame` differ in exactly
  ONE line — the swing reference in the `−sin θ` term: `f̂ = filUVec[s]` (DIRSWING) vs
  `ŷ_head × û_head` (head frame). θ-selection, stiffness k, the compliant torque form (+lever/−head),
  strokerate/spring handling, and every other system (F8/F9/F10/AXLOCK/release/binding/cycle) are
  byte-identical. The head-frame branch reads one extra array (motorYVec), drops one (filUVec).
- **AXLOCK does NOT override the swing target.** F9 (head.uVec ⊥ hold), AXLOCK/F10 (head.yVec→ŝ),
  and the swing (lever torque) are **three distinct torque channels on different DOFs**, applied in
  the same step; none writes or overwrites the swing target. They shape the head POSE that the
  head-frame swing reads on the next step — an indirect coupling, not a within-step override. **⇒ H3
  is not foreordained by ordering; the run is licensed.**
- Both swing targets are **live-recomputed every step** (neither is frozen at the chemical
  transition) — tested as implemented.
- **Byte-identity verified:** the no-flag path is character-for-character identical before/after the
  change (`-vclamp 16 -seed 0 12000`, FVROW `fbar_avail=-0.05347…` identical via `git stash` diff).

---

## Method

`GlidingHarness -vclamp` (CPU deterministic; thermal OFF; rigid straight filament dragged at clamp
velocity v; `f̂ ≡ +x̂` at all v; glide axis ĝ = −x̂). Fixed stack = the canonical default
(SPHEREHEAD + AXLOCK + DIRSWING + LYMN_TAYLOR + XB_IMPLICIT2; θ_cocked = NECK_ANGLE = 60°). Bed
`-matbox 50 -density 2000`. Full-binding VARIANT A (continuous recruitment) — **not** `-fvepisode`.
15 000 steps/run (warm-up 5 000; steady window 10 000 steps = 0.1 s). Paired common-random-number
seeds 0–3.

- **PRIMARY:** `f̄_available(v)` (FVROW; unbound available motors = 0; fixed occupancy denom
  reach∪bound), per-mode zero V₀ by linear interpolation within the first +→− bracket, paired
  ΔV₀ = V₀,HF − V₀,DIR. Bootstrap 5000 paired-seed resamples.
- **Geometry (`-swingdiag`, additive, default-off):** on the current bound pose, p̂_DIR = f̂,
  p̂_HF = normalize(ŷ_head×û_head); q = p̂·ĝ; axialProd a = p̂·x̂; off-axis = ∠(p̂_HF, f̂); per-episode
  transverse impulse + axialProd onset/min/final. Both references logged in both modes (counterfactual).
- Velocity grid v = 0,2,4,8,12,16,20 µm/s, **refined to ≤1 µm/s** around each zero (added
  10,11,13,14,15,17). 4 seeds × 2 modes × 13 velocities = **104 runs** (`RUN_LOGS/swing_*.txt`;
  analysis `RUN_LOGS/2026-07-11_headframe_swing_analysis.txt`).

---

## Results

### 1. Force–velocity curves and V₀ (PRIMARY)

Mean `f̄_available(v)` (pN; ± = half seed range):

| v | 0 | 2 | 4 | 8 | 11 | 12 | 15 | 16 | 17 | 20 |
|---|---|---|---|---|---|---|---|---|---|---|
| **DIR** | +0.655 | +0.545 | +0.472 | +0.280 | +0.186 | +0.141 | +0.035 | +0.002 | −0.028 | −0.094 |
| **HF** | +0.435 | +0.368 | +0.286 | +0.153 | +0.036 | −0.015 | −0.102 | −0.134 | −0.160 | −0.243 |

Head-frame is lower at **every** v, including v=0 (−0.220 pN, **−34%**). Bootstrap (5000, paired seed):

| quantity | 2.5% | median | 97.5% |
|---|---|---|---|
| **V₀,DIR** | 15.80 | **16.07** | 17.12 |
| **V₀,HF** | 11.43 | **11.70** | 12.45 |
| **ΔV₀ = HF − DIR** | −4.95 | **−4.35** | −3.88 |

ΔV₀ interval fully below zero; |median shift| = 4.35 µm/s (≥2). Neither mode is positive at v=20,
so no v=24 was needed. Both zeros bracketed at ≤1 µm/s (DIR 16→17: +0.002→−0.028; HF 11→12:
+0.036→−0.015).

### 2. Commanded swing geometry — the mechanism (COMMANDED, not realized)

- **q_DIR = −1.00000 at every v and step** (exact): DIRSWING commands a perfectly axial reference at
  all velocities (f̂ ≡ +x̂ by clamp construction). The fixed baseline.
- **head-frame reference is chronically ~perpendicular to f̂, velocity-INDEPENDENTLY.** In the
  head-frame run's own trajectory, off-axis angle = **86.8° (v=0) → 87.6° (v=20)** — flat, range
  ~2°, non-monotonic; axialProd a ≈ +0.03…+0.05 (≈0, non-axial) throughout. The counterfactual
  head-frame reference in the DIRSWING trajectory is likewise ~90–95° off — i.e. **in both modes**
  ŷ_head×û_head sits ≈⊥ f̂. The recast's "fully-locked ⇒ ŷ_head×û_head = f̂" ideal is **not** what
  the sphere-head's F9(90°)+AXLOCK actually produce; the head's û_head is not ±ẑ.
- **off-axis angle does NOT increase ≥5°** from v=0 to any pre-zero high-v point (it is flat, and if
  anything ~1° lower at v=20 than v=0). **q_HF slope ≈ 0.**

### 3. D(v) difference-in-differences (the H1↔H2 discriminator)

D(v) = [I_HF(v) − I_HF(0)] − [I_DIR(v) − I_DIR(0)] on per-episode net axial impulse (Iattach, pN·ms),
paired seeds, bootstrap 95%:

| v | 2 | 4 | 8 | 11 | 13 | 15 | 17 | 20 |
|---|---|---|---|---|---|---|---|---|
| D(v) | +0.049 | +0.035 | +0.106 | +0.099 | +0.122 | +0.120 | +0.126 | +0.121 |
| excl 0? | ✔ | – | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ |

**D(v) is POSITIVE at every velocity** (11/12 intervals exclude zero on the positive side) — the
opposite of H1's required *negative*. After removing each mode's static v=0 offset, the head-frame
stroke loses **less** axial impulse per unit velocity than DIRSWING. The matching force contrast
D_F(v) is likewise positive (+0.03…+0.10). **The lower V₀ is entirely a static-offset effect**, not
a steeper velocity decay.

### 4. Episode counts, censoring, covariates

- Complete episodes per run: **555–697** (min across all cells 555) — far above the ≥100 threshold;
  no cell extended.
- Right-censored (still bound at t=M) ≈ ⟨N_bound⟩ ≈ 3.7 per run ⇒ **<0.6%** of episodes; negligible.
- Lifetime is essentially mode- and v-matched (life ≈ 0.48–0.68 ms, falling with v identically in
  both modes) ⇒ the force deficit is per-episode drive, not fewer/shorter attachments. ⟨N_bound⟩ and
  transverse impulse track together across modes (transverse falls monotonically with v in both).

---

## Hypothesis classification

| H | prediction | observed | verdict |
|---|---|---|---|
| **H1** velocity-dependent geometric penalty | ΔV₀<0 **and** q_HF slope<0 **and** off-axis +≥5° **and** D(v)<0 (≥2 v) | only ΔV₀<0; off-axis flat; q_HF flat; **D(v)>0** | **REJECTED** (3 of 4 fail/reverse) |
| **H2** static penalty | differs at v=0; D(v) flat/no velocity feedback | large −34% v=0 deficit; D(v) positive (less velocity-sensitive) | **SUPPORTED** (dominant effect) |
| **H3** AXLOCK equivalence / no-op | q_HF≈axial, targets≈identical, ΔV₀≈0 | q_HF ~87° off; targets very different; ΔV₀=−4.4 | **REJECTED** |
| **H4** geometry changes, doesn't control zero | q_HF declines yet ΔV₀ small | q_HF doesn't decline; ΔV₀ large-and-static-driven | **REJECTED** |
| **H5** implementation contamination | flag changes chemistry/timing/springs/no-flag output | single-factor (audit §3) + byte-identity verified | **EXCLUDED** |

**Primary classification: H2 (static penalty).** With the one nuance that a *pure* static downshift
of a monotone-decreasing f̄(v) **does** move the zero-crossing left, so H2 here lowers V₀ (it is not
"V₀ unchanged"). The velocity contrast is not merely absent but mildly **reversed** (positive D(v):
the head-frame stroke is *less* velocity-sensitive). There is **no velocity-dependent axial loss**.

---

## Interpretation

- The head-frame recast is **less efficient, not a ceiling mechanism.** Per the proposal's H2 branch:
  it may lower finite-density gliding speed, but there is **no velocity feedback** to claim as
  improved force–velocity saturation, and **no fidelity gain** — it does the opposite of the
  Stage-2-deferred goal (it worsens the stroke rather than making it more physically faithful).
- **Root geometric finding (static, flagged):** under the canonical F9(90°)+AXLOCK locks, the bound
  head does **not** settle into the configuration the head-frame recast assumed (û_head = ±ẑ ⇒
  ŷ_head×û_head = f̂). Instead ŷ_head×û_head sits ≈⊥ f̂ at **all** velocities. So the head-frame
  swing sweeps in a largely non-glide direction; the residual ~65% of DIRSWING's drive it retains
  comes mainly from the `cos θ·û_head` term, not the `−sin θ·p̂` sweep. This is a property of the
  **static** bound pose, independent of sliding velocity — which is exactly why the penalty is static.
- **Neither the clean H1 nor the clean H3.** AXLOCK does not re-axialize the head-frame reference
  (H3 rejected: it is ~87° off, not ≈0), but the off-axis-ness it fails to fix is velocity-
  independent (H1 rejected: no velocity feedback).
- **No follow-on licensed by this outcome.** H1 (which would have justified a free-gliding
  validation and a fidelity claim) is rejected; H3 (which would have licensed a separate AXLOCK-
  relaxation experiment) is rejected. The head-frame stroke as implemented is a worse stroke, not a
  candidate canonical correction. If the head-frame swing is ever revisited, the *static* geometry
  (why the sphere-head's locked û_head is not ±ẑ, so ŷ_head×û_head ⊄ f̂) is the thing to fix first —
  and that is a v=0 question, not a force–velocity one.

**Constraints honored:** single-factor (no AXLOCK edit, no new state, no calibration, no target fit,
no free-gliding scope creep); `f̄_available` primary (not `-fvepisode`); clamp V₀ reported as clamp
V₀ (no V₀/1.4 free-glide conversion); commanded geometry logged separately from realized force;
CPU deterministic; paired CRN seeds; default-off byte-identical; `BoA-v1ref` untouched.

---

## Artifacts

- Code: `-headswing` (selector) + `-swingdiag` (additive diagnostics) in `GlidingHarness`;
  `CrossBridgeSystem.directedSwingHeadFrame` (pre-existing) now wired. Default-off byte-identical.
- Runs: `RUN_LOGS/swing_{dir,headswing}_v*_s*.txt` (104 runs). Analysis:
  `RUN_LOGS/2026-07-11_headframe_swing_analysis.txt` + `…_analyze.py`.
- Audit: `docs/HEADFRAME_SWING_CODE_AUDIT.md`.
