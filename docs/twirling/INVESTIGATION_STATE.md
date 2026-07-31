# Skew-Twirling Investigation — State of Play

**As of 2026-07-31, 21:15 local.** Single-page status across both branches of this investigation. The detailed
reports are `RIGID_FILAMENT_SKEW_VALIDATION.md` (active programme) and `FIVE_DEGREE_SKEW_DURATION_PILOT.md`
(aborted predecessor, on its own branch).

---

## 1. One-paragraph summary

The flexible-filament 5° duration pilot was **aborted before it ran** — no arms, no records, no GPU time —
because the inherited filament rotational thermostat is not FDT-consistent. It was replaced by a
**rigid-filament programme**: one rigid body spanning the whole filament, with a single thermal reservoir at
kT on every drag-carrying degree of freedom. **Validation Stages 0–4 are complete and passing (9 gates, 0
failures, plus default inertness). Stage 5 — the passive-null stop gate — is running now. Stage 6 is
implemented but not run. No production arm has been launched.** The most consequential result so far is not a
gate but a calculation: fixing the thermostat *raises* the rotational noise floor enough that rotation cannot
be the load-bearing observable, so torque is.

---

## 2. Where the code and data live

| item | path |
|---|---|
| **Active worktree** | `/home/jba/Code/softbox-rigid-filament-skew-twirling` |
| Active branch | `feature/rigid-filament-skew-twirling` (baseline `3119000`) |
| Validation report | `docs/twirling/RIGID_FILAMENT_SKEW_VALIDATION.md` |
| Stage 2–4 log | `RUN_LOGS/rigid/stage234.txt` |
| Stage 1 inertness evidence | `RUN_LOGS/rigid/inertness/{new,baseline}/` |
| Stage 5 log (live) | `RUN_LOGS/rigid/stage5_passive.txt` |
| **Aborted predecessor** | `/home/jba/Code/softbox-lowatp-small-skew`, branch `feature/lowatp-5deg-duration-pilot`, commit `70a260a` |
| Aborted report | `docs/twirling/FIVE_DEGREE_SKEW_DURATION_PILOT.md` |

Commits on the active branch, newest first: `cd94328`, `ebb205e`, `18de825`, `41c01fa`, `479be86`, `7d61669`,
`59926d1`.

**Nothing outside these two worktrees was modified.** No flexible-filament record was created, changed, moved
or deleted. `BoA-v1ref` untouched. The torsional-ratchet branch and its records untouched.

---

## 3. Status by stage

| stage | state | result |
|---|---|---|
| 0 · code and path audit | **DONE** | defect located; rigid-body path audited and found already to exist |
| 1 · default inertness | **PASS** | record, 4001-line dense trace, nested readout, P(N_b) all byte-identical, both ε signs; only a provenance *comment* gains metadata |
| 2 · free rigid-body FDT | **PASS** | all four DOF within **0.4 %** of the exact discrete law `D = kT/γ`, three timesteps; signed increments zero-mean (roll 0.18σ) and Gaussian; roll dt-spread 0.0010 over 4× |
| 3 · rigidity | **PASS** | contour change exactly 0; frame norm 2.2e−7, orthogonality 8.5e−10 under motor load |
| 4 · force/torque closure | **PASS** | 1e−6 on four wrench fixtures; `r × F` exact, origin-shift invariance exactly 0, mirror sign exactly −1 |
| 5 · passive nulls (**stop gate**) | **RUNNING** | relaunched on the fixed binary; 100 ms x 4 seeds, 100 steps/s, ~16 h |
| 6 · binding/gliding compatibility | implemented, **not run** | blocked behind Stage 5 |
| Tier-1 production (4 arms) | **NOT LAUNCHED** | blocked until 5 and 6 pass |

---

## 4. The two findings that matter

### 4.1 The defect, precisely located

`TwoBodyConverterMotor.buildGlide2D`, flexible branch: interior segments (10 of 12) receive **rotational drag
with exactly zero rotational Brownian torque**; the two end segments get `BRotCoeff = 0.5` ⇒ a **0.25 kT**
rotational reservoir against a **kT** translational one. Chain torsion then couples degrees of freedom held at
different effective temperatures. That is a non-equilibrium steady state on precisely the coordinate the
twirling assay measures, and it is consistent with the flexible ε = 0 control rolling at ≈12 rad/s with no
chirality imposed. `BRotCoeff` is documented in the source as a persistence-length tuning knob, explicitly not
part of the FDT relation.

### 4.2 Fixing it makes twirling HARDER to see, not easier

The flexible model's rotational floor looked small (σ ≈ 8.74 rad/s) because roll was under-thermostatted. The
FDT-correct rigid body carries the full reservoir: `D_roll = kT/γ_roll = 1.270×10³ rad²/s`.

| window per arm | 3σ bound on passive Ω_odd (n = 4) | vs ~50 rad/s driven reference |
|---|---|---|
| 100 ms | 169 rad/s | 3.4× too weak |
| 300 ms | 98 rad/s | 2.0× too weak |
| 1000 ms | 53 rad/s | still ≈1.1× too weak |

**No feasible duration makes the rotational null informative.** The chiral null is therefore gated on **τ_odd**
(a direct time-average, which resolves) and rotation is reported as an explicit bound with an INCONCLUSIVE
verdict. This was computed *before* Stage 5 ran, and Stage 5 was sized at 100 ms accordingly — longer windows
buy nothing on the observable that was driving the cost.

**Recorded prediction, made before any production arm runs: R3 — rigid torque resolved, rotation
diffusion-limited — is the a-priori likely classification.**

Note for later closure work: `γ_roll = 4πηR²L` is *extensive* in length, so one rod and twelve give the same
axial roll drag (3.241935e−24). Rigid and flexible closure are directly comparable on the roll axis; tumbling
and translation are not, because of the `ln(L/2R)` factors.

---

## 5. What was built (and what was already there)

The rigid-body filament **was not built from scratch** — `-filament-segments 1` already provided one
`RigidRodBody` over the full 2.106 µm contour: true rigid-body state and update, slender-body mobility at full
length (not a sum of segment drags), helical actin sites already stored as body-frame material points so roll
rotates the lattice and every bound anchor together, free measurable axial roll, **no penalty stiffness**. It
had inherited the same `BRotCoeff`.

Changes made, all default-off:

1. `-fil-thermostat fdt|legacy` — sets both Brownian scales to exactly 1.0 on the rigid branch.
2. Rigid record namespace `rigid_th{fdt|leg}_u<ATP>_e<skew×10>_r<density>_d<µs>_{p|n|z}_<seed>`, which cannot
   collide with any flexible record; model and thermostat also enter the provenance line.
3. `-rigid-validate` (Stages 2–4), `-rigid-passive` (Stage 5), `-rigid-compat` (Stage 6).
4. `PASSIVE_MODE` — removes exactly two things from the production step, binding and the nucleotide cycle, and
   nothing else.

---

## 6. Failures and corrections so far

Four defects found in the validation's *own* construction. **Three would have produced a false pass on a gate.**

| # | defect | how it surfaced | resolution |
|---|---|---|---|
| 1 | Stage 4 gate 9's preregistered axial sign was `−F·R` | fixture disagreed on sign only, magnitude exact | the **expectation** was wrong (`ŷ × ẑ = +û`); code unchanged |
| 2 | `passiveArm` never called `cfg()` | N_b = 1 of 1200, **every observable identically 0.0000**, all four gates "passed" | configure the scene exactly as a production arm does |
| 3 | one seed ⇒ SEM = 0 ⇒ every σ = 0.00 | gates passed regardless of data | stage now **refuses to run** below two seeds |
| 4 | σ-gate on passive Ω passes because the measurement is insensitive | sensitivity computed **before** the run | re-gated on τ_odd; Ω reported as a bound |

Plus a monitoring gap: the first Stage-5 launch called `plan.execute()` directly, so the crash heartbeat sat at
`state=STARTING` and a fault could not have been localised. Stopped after ~3 minutes and relaunched through a
traced helper rather than running ~19 h blind.

---

## 6b. Two defects found from OUTSIDE my own gates (2026-07-31)

Both produce runs that complete and look plausible, so no gate of mine would have caught either.

**1. `-filament-segments` silently discarded by the ATP production path** (relayed from the ratchet branch,
`9d6da60`; confirmed present here, fixed at `9bd31aa`). Two independent overrides hardcoded the flexible
segment count: `runAtpMap` opened by assigning `G4_NSEG` to `FIL_SEGS`, and `atpArm` built its `TArm` with
`G4_NSEG`, which `runTwirlArm` then assigns back into `FIL_SEGS`. **My Tier-1 production runs through exactly
this path.** Worse here than on the ratchet branch: `atpId` selects the rigid namespace on `FIL_SEGS == 1`, so
the four 5° arms would have run the 12-segment FLEXIBLE chain *and* been written into the flexible `atp_`
namespace — a flexible run labelled as the rigid pilot, on the very thermostat defect this programme exists to
remove. Both sites now use `FIL_SEGS` (which defaults to `G4_NSEG`, so flexible runs are byte-unaffected), plus
a **structural guard**: `atpArm` throws if the realized scene's `nSeg` differs from the requested `FIL_SEGS`.

*Stage 5 was NOT affected*: `runRigidPassive` sets `FIL_SEGS = 1` and `passiveArm` calls `build(seed)` directly,
never touching `runAtpMap`/`atpArm`/`TArm`. Positive evidence the body is genuinely rigid — the Stage 2–4 log
reports contour L = 2.106 µm / 779 monomers (a flexible segment is 64 monomers / 0.176 µm) and
γ_roll = 3.241935e−24 at full length.

**2. GPU utilisation ~5%** (user observation). Stage 5 is **host-latency-bound, not device-bound**. Per-step
`clearProfiles()` in the traced execute helper was one contributor (fixed at `f382505`, now on a 1024-step
cadence). *I mis-attributed this number twice* — first to contention from a concurrent campaign (wrong: the
rate was identical before and after that job paused), then predicting a 4.6× recovery from the profile fix
(wrong in magnitude). Settled rate after the fix is **100 steps/s against 58 before**. The residual bottleneck
is the per-step launch + host-readback pattern (~50 kernels and ~14 `EVERY_EXECUTION` transfers per step)
against the ~8000 launches/s ceiling CLAUDE.md documents for this codebase — **plausible but not proven, and
recorded as such.** Low GPU utilisation is expected for this graph; what was anomalous was only the extra cost.

**CPU runtime:** bounded at **< 68 steps/s**, so Stage 5 on CPU is **> 24 h and likely several days**. The
probe was stopped before completing an arm because it competes for the host CPU that is the actual bottleneck.
Any CPU/GPU ratio in the final report must be re-derived against the corrected GPU baseline.


## 7. GPU health and contention

External crash recorder running throughout (pid 1988, session `20260728T071146Z`, `journal_ok=yes`,
`nvidia_smi_ok=yes`). No Xid or NVRM fault observed. Stage 5 **shares the GPU** with an unrelated
torsional-ratchet campaign in `../softbox-rigid-filament-torsional-ratchet`, launched outside the monitored
wrapper by the repository owner and left strictly alone. Throughput is ≈24–86 steps/s contended against ≈277
uncontended, so **all Stage-5 wall-clock is contended and is not a throughput measurement.**

---

## 7b. Noise-decomposed rotation (added 2026-07-31, commit `c8921da`)

`softbox/RigidRollDecomposition.java` + `-rot-decomp` / `-rot-decomp-validate`. **Source committed but
deliberately NOT built**, so the running Stage-5 binary is untouched; it compiles cleanly (verified against a
scratch output directory).

Separates, along the **actual fully thermalized trajectory**, the motor-driven angular increment from the
independently sampled Brownian one. No forcing removed, no replay with noise deleted.

The split is **exact at the body-frame angular-velocity level**, because the integrator itself forms
`bwx = (torqueSum·û + randTorque_x)/γ_roll`:

```
dPhiDrive = dt·(torqueSum·û)/γ_roll        dPhiBrown = dt·randTorque_x/γ_roll
```

read from the *same arrays the integrator consumes* and the *same pre-update material frame* (`torqueSum` is a
lab-frame vector, so the pre-step axis is required — a subtle but load-bearing detail).

**A scalar additive identity on the realized roll would be false.** The orientation update composes all three
body-frame components at once and renormalises, and finite rotations do not commute. The genuine O(dt²)
remainder is recorded explicitly as `dPhiGeom`, and the closure gate validates the relation that *is* exact.

Gates in `-rot-decomp-validate`: trajectory inertness (hard stop if any pre-existing observable moves),
body-frame update closure, torque-drift closure `Ω_drive = M_roll·⟨τ_det⟩`, and the deterministic
torque-component sum. Only graph change is a gated read-back of `torqueSum`/`randTorque` — no kernel, no task,
no ordering, no RNG draw.

Terminology fixed: **Ω_total** (realized), **Ω_drive** (motor-driven), **Ω_Brown**, **Ω_geom**. `Ω_drive` is
*resolved motor-driven twirling drift*, never "measured twirling" unqualified.

## 8. Next actions, in order

1. **Stage 5 completes** (~28 h contended, faster once the ratchet job ends). It runs to completion on the
   binary it was launched with — the decomposition is committed as source only and deliberately NOT built.
   Watch the τ_odd gate and the thermal-OFF halves gate; the Ω bound is expected INCONCLUSIVE by design.
1b. **On exit:** build → `-rot-decomp-validate` (inertness is a HARD STOP) → short passive decomposition
   confirmation only (τ_odd null, Ω_drive_odd null, zero-mean Brownian, closure) — **the full Stage 5 is NOT
   rerun**, since no force, mobility, RNG, orientation-update or passive-control code changed.
2. **If Stage 5 passes** → run Stage 6 (`-rigid-compat`, ~1 h). Occupancy shift > 30 % is *flagged*, not
   failed, and no binding gate is to be tuned.
3. **If both pass** → Tier-1 production, launched automatically: 10 µM, 400 heads/µm², ±5°, seeds 101/102,
   1.0 s per arm, four arms, device-resident and monitored, with the decomposition ON. Evidentiary hierarchy:
   τ_odd, then Ω_drive_odd, then torque components, then nested-window stability, with Ω_total_odd as a
   lower-powered corroborator. R3 does **not** require Ω_total_odd to resolve. Then a conditional rigid ±15°
   anchor if 5° reaches R3/R4 and the 36 GPU-h / 8-arm cap allows. Then matched-pair estimators, nested final windows, non-overlapping blocks,
   θ_odd(t) drift fit, closure, R1–R5 classification, cross-model descriptive comparison with the legacy 0°,
   1°, 2° and 15° results, and a rigid-15° anchor recommendation.
4. **If Stage 5 fails** — any passive chiral arm sustaining directed rotation — production does not run, and
   the finding is the result.

**Open question worth flagging now:** given §4.2, a rigid 15° anchor may be needed *before* the 5° result can
be interpreted at all, since the 5° rotational signal is very likely to sit under the noise floor while its
torque does not. That decision is deliberately deferred to the production report rather than pre-empted.

---

## 9. Standing interpretive language

Use: *the rigid-filament assay isolates motor-generated chiral torque in a single-temperature, FDT-consistent
rigid-body rotational system.*

Do not use: *the rigid assay proves that filament flexibility is irrelevant.* The rigid model removes internal
bending, segment material roll, persistence-length calibration, end-only rotational forcing, nonuniform
effective temperature and internal rotational energy transport **by construction, not by test**. The legacy
flexible results are historical **cross-model descriptive comparisons** and are not corrected or retracted by
this programme.

---

## 10. Decomposition validation result (2026-07-31, `RUN_LOGS/rigid/decomp_validate.txt`)

Rigid scene through `runTwirlArm` — the production path — GPU device-resident. **3 PASS, 1 FAIL.**

| gate | result |
|---|---|
| 1 · **inertness** | **PASS** — glide, omega, omegaFit, avgBound, tau, turns, rollR2, strokeRatePerS all **bit-identical** with diagnostics OFF vs ON at the same seed |
| 2 · **body-frame closure** | **PASS** — max step residual 2.8e−17 rad, RMS 2.8e−18, accumulated 1.8e−16. The additive split is exact, as derived |
| 3 · **torque-drift closure** | **PASS** — `Ω_drive = M_roll·⟨τ_det⟩` to 5.6e−15 relative (M_roll = 3.0846e23, ⟨τ_det⟩ = −8.19e−22 N·m, Ω_drive = −252.57 rad/s) |
| 4 · **component sum** | **FAIL** — bond moment vs total τ_det differs by **1.9 %** (−1.56e−23 N·m) |

**Gate 4 is left failing and unfixed, not tuned.** Diagnosis: an ordering defect in *my estimator*, not the
physics. `stepGlidingCPU` runs `bondForces → segGather` (which fills `forceSum`/`torqueSum`) `→ integrate →
matS2SolveStep`, and the S2 solve **rewrites `bondData` at the end of the step**. My per-step `tauBond` reads
`bondData` from the host *after* the step, so it compares `torqueSum` (built from pre-solve `bondData`) against
a post-solve snapshot. The component check must be taken before the solve, or re-specified. **Production must
not run until this is resolved**, since the torque-component decomposition is item 3 in the evidentiary
hierarchy.

**Confirms the decomposition's rationale:** `|Ω_Brown| / |Ω_drive| = 2.3` even at 15° skew — the raw angle is
Brownian-dominated exactly as predicted in §4.2.

**Production throughput still unmeasured** on a full-length rigid arm. At the Stage-5 rate (~70 steps/s) four
1.0 s arms would cost ~63 GPU-h, well past the 36 h cap; the historical 264 steps/s is a *flexible*-scene
figure. This must be measured before launching production, and if it does not fit, the design change is the
user's call — not a silent resize.
