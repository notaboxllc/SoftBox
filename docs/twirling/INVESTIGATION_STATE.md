# Skew-Twirling Investigation — State of Play

**As of 2026-08-01.** Single-page status across both branches of this investigation. The detailed reports are
`RIGID_15DEG_TORQUE_REVALIDATION.md` (the power anchor), `RIGID_5DEG_HIGH_SEED_TORQUE.md` (the high-seed 5°
measurement, **in progress**), `RIGID_LOW_SKEW_TORQUE_QUICKLOOK.md` (the superseded 2-seed 5° answer),
`RIGID_FILAMENT_SKEW_VALIDATION.md` (the validation programme) and `FIVE_DEGREE_SKEW_DURATION_PILOT.md`
(aborted predecessor, on its own branch).

---

## 0. CAMPAIGN COMPLETE — rigid ±15° anchor + high-seed ±5° (2026-08-01, commit `fc39db2`)

**Phase 1 — ±15° power anchor: DONE, A15 PASS.** 4 matched seeds, 8 arms, 0 invalid / 0 solver failures.

```
tau_odd      = -4.558e-22 +- 1.963e-22 N.m   |m|/SEM 2.32   seed-sign 3 of 4
Om_drive_odd = -140.59 +- 60.57 rad/s        = -22.4 turns/s ; -63.5 turns/um ; pitch 16 nm
Om_Brown_odd = 0 exactly   mobility closure 2.35e-11   component closure 3.25e-11
```

All eight A15 conditions plus the preferred |m|/SEM ≥ 2 criterion met, so the borderline seed extension
(105–108) did **not** trigger. At df = 3 the 95 % t interval contains zero — this is a **power anchor, not a
significance claim**, exactly as the gate specifies. The estimator demonstrably resolves a chiral torque it is
known to contain, which is what licensed the 5° stage.

**Phase 2 — high-seed ±5°: DONE, L5-PRESENT.** 12 matched seeds, 24 arms, 0 invalid / 0 solver failures.

```
tau_odd      = -1.037e-22 +- 3.503e-23 N.m   |m|/SEM 2.96   seed-sign 10 of 12
                                             95% t CI [-1.808e-22, -2.664e-23]  EXCLUDES ZERO
Om_drive_odd = -32.00 +- 10.80 rad/s = -5.09 turns/s ; -15.0 turns/um ; signed pitch 66.6 nm
tau per bound head (odd) = -3.790e-24 +- 1.124e-24 N.m   (3.37 sigma)
```

Every L5-PRESENT criterion met, so the optional seeds 113–116 extension was **not justified and not run**.
**This overturns the predecessor's Q3 "no discernible torque"**, exactly along the route that report itself
identified (*more seeds, not longer arms*).

**The qualification is load-bearing and must travel with the number: the signal is NOT stationary.** At 5°
the odd torque decays across the measured window (Q1 −3.086e−22 → Q4 **+4.357e−23**; paired Q1−Q4 2.18σ,
per-seed trend 2.04σ, 8 of 12 seeds decaying), while at 15° it is flat (0.01σ, 2 of 4). Both 5° trend
statistics sit just under t₁₁ = 2.201 ⇒ **suggestive, not established** — but they are corroborated by the
100–200 ms windows carrying no resolved signal (§0.1 item 2). **The resolved quantity is a decaying chiral
torque and must not be quoted as a steady-state twirling torque.** Most economical account: the standing 25 %
equilibration convention is insufficient at 5°. Testing that needs longer arms at high seed count — outside
authorized scope, and the top follow-up.

### 0.1 Sequential convergence — the strongest methodological result here

Fixed seed order, chosen before any result was seen:

| n | 2 | 3 | **4** | 5 | 6 | **7** | 8 | 9 | 10 | 11 | **12** |
|---|---|---|---|---|---|---|---|---|---|---|---|
| \|m\|/SEM | 2.43 | 3.73 | **5.30** | 1.92 | 2.14 | **1.13** | 1.64 | 2.09 | 2.38 | 2.81 | **2.96** |

The estimator passed through **5.30σ at n = 4** and **1.13σ at n = 7** en route to 2.96σ at n = 12. Either
point quoted alone would have been badly wrong — one claiming a result 3× too strong, the other declaring a
null. The n ≥ 8 evaluation floor and the fixed seed order prevented both. The early-success rule was
evaluated at n = 8 and n = 10 and **correctly never fired**.

### 0.2 Results that stand independently of the 5° verdict

1. **Quarter-blocks are NOT valid replicates, and fail independence in opposite directions.** Measured
   block-vs-seed variance ratios: **5° 0.69** (anti-correlated, ρ̄ ≈ −0.18 ⇒ pooled-block SEM biased
   **conservative**) and **15° 1.29** (positively correlated ⇒ biased **anti**-conservative). Neither pooled
   figure is a valid uncertainty. **This corrects `RIGID_LOW_SKEW_TORQUE_QUICKLOOK.md` §8** ("the block one
   is the honest one" — it is not).
2. **The 200 ms "collapse" is a variance effect, not a decaying or reversing signal.** Window-resolved odd
   rotation from stored `meanRoll` traces at **zero GPU cost** (valid because matched ±ε arms share a
   bit-identical state-independent Brownian stream ⇒ Φ_Brown cancels exactly; validated to 5 significant
   figures against an independent record). Later windows are **sign-inconsistent and 3–5× larger**, not
   reversed.
3. **The estimator's power is calibrated.** The 15° anchor establishes that this pipeline resolves a chiral
   torque it is known to contain, which is what licensed reading the 5° result at all.

### 0.2a Interim claims RETRACTED — recorded, not overwritten

Three things asserted from partial data did not survive to n = 12:

| interim claim | at n = 8 | at n = 12 | status |
|---|---|---|---|
| occupancy asymmetry between ±ε | −0.744 ± 0.229 (**3.27σ**) | −0.359 ± 0.229 (**1.57σ**) | **dissolved** |
| ε-odd torque-population shifts | 3.64σ / 3.59σ | 1.44σ / 1.25σ | **retracted** — the "2.7 % residue of two *resolved* shifts" framing was an n = 8 artifact |
| 5° block variance ratio | 0.31 (n = 4) | 0.69 (n = 12) | direction held, **magnitude did not** |

The occupancy confound is nonetheless **answered**, and more strongly than by its own dissolution: the
occupancy-**normalised** signal (τ per bound head, odd) is **3.37σ**, stronger than the raw 2.96σ. The chiral
torque is not an artifact of one ε sign engaging more heads.

What survives of the near-cancellation picture is only the uncontroversial part: the net axial torque *is* a
small residue of opposed head populations (net/gross 0.71 %), but those population shifts are not separately
resolved.

### 0.2b 5° / 15° ratio

`R_tau = 0.2276 ± 0.1246`, against 0.3333 (linear) and 0.3367 (sine) — **0.85σ / 0.88σ**, i.e. consistent
with proportional small-angle scaling. Linear vs sine remain **unseparable** (1 % apart against 55 %
uncertainty); no scaling-law claim is made.

Rotationally: pitch **66.6 nm at 5°** vs **15.7 nm at 15°**, both far tighter than the ~0.47 µm myosin-II
comparator (~7× and ~30×). Post-hoc, cross-model, descriptive — not a target and not addressed here.

### 0.2d A prediction of mine that was wrong, recorded

From the 15° anchor I projected that if the 5° between-seed SD resembled the 15° one (3.93e−22), 12 seeds
would reach only ~1.3σ and might not resolve. **That was wrong**: the 5° between-seed SD is **1.213e−22**,
3.2× smaller, and 12 seeds reached 2.96σ. The noise is *not* ε-independent — it scales down with the signal,
so the coefficient of variation at 5° (1.17) is close to that at 15° (0.86) rather than blowing up. I flagged
the projection as spanning both outcomes at the time, which is why it did not change the design.

### 0.3 Scheduling, cost and GPU health

**Two-process concurrency trialled and REJECTED** (`RUN_LOGS/rigid/CONCURRENCY_TRIAL.md`). A second skew
process raised skew-aggregate throughput +40.6 % but total device throughput only +5.6 % — ~73 % of the gain
was taken from the repository owner's unrelated concurrent campaign (−29.7 %). Serial fits the budget, so the
campaign runs **one skew arm at a time**. GPU utilisation is flat at 53 % either way and is *not* diagnostic
here: this graph is kernel-launch-bound, so `nvidia-smi` utilisation pins at partial regardless; the steps/s
ledger is what decides it.

| | arms | GPU wall |
|---|---|---|
| 15° sanity (10 ms) + anchor (100 ms) | 10 | 3.53 h |
| 5° high-seed (seeds 103–112, 20 arms + 4 reused) | 20 | 7.81 h |
| **total** | **30 newly run** (hard max 44) | **11.33 h** against a 20 h cap |

Health across **all** campaign arms: `invalid = 0`, `solverFail = 0`, `rateCapWarns = 0`, `ruptureEvents = 0`;
no Xid, no NVRM, recorder healthy throughout. One `hs_err` file (`hs_err_pid3350710.log`) is from my own
deliberate `SIGTERM` when concurrency was reverted — the documented §5b mid-`execute()` teardown signature
(shutdown hook reached `SHUTDOWN_HOOK_COMPLETED`), **not** a device fault. `atpWrite` is temp-file + atomic
rename, so that interrupted arm left no partial record and was simply re-run.

**Arm ledger:** `RUN_LOGS/rigid/ARM_LEDGER.md`. **Analysis tooling:** `scratch_rigid_odd.py` (matched-seed
statistics; validated by reproducing the published 5° record set exactly), `scratch_rigid_window.py`
(window-resolved odd rotation), `scratch_gpu_rate.sh` (per-process throughput from the crash heartbeat).

---

## 1. One-paragraph summary

> **Superseded in its 5° verdict by §0.** The paragraph below describes the state *before* the
> ±15° anchor and the high-seed 5° campaign. Its "answered directly: NO discernible torque (Q3)" is a
> **two-seed** result; §0 supersedes it. Everything it says about the thermostat defect, the rigid
> replacement and the validation stages still stands.

The flexible-filament 5° duration pilot was **aborted before it ran** — no arms, no records, no GPU time —
because the inherited filament rotational thermostat is not FDT-consistent. It was replaced by a
**rigid-filament programme**: one rigid body spanning the whole filament, with a single thermal reservoir at
kT on every drag-carrying degree of freedom. Validation Stages 0–4 passed (9 gates), the noise decomposition
now passes all four of its gates, and the **driven 5° question has been answered directly: NO discernible
chirality-odd motor torque at this design (Q3)** — 4 arms at 100 ms looked suggestive (2.43σ on seeds, 1.01σ on
disjoint blocks) and the authorized extension of the same arms to 200 ms collapsed it to 0.05σ with the seeds
disagreeing in sign. Bound: **|τ_odd| ≲ 1.3e−22 N·m ⇒ |Ω_drive_odd| ≲ 40 rad/s**. The obstacle is identified:
the net axial torque is a ~0.2 % residue of a near-cancelling tug-of-war whose imbalance drifts on the
timescale of the whole window, so **more seeds, not longer arms**, is the lever. **Stage 5 (passive nulls) is
INCOMPLETE — DEFERRED**, and was not required for this answer.

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
| 4b · noise-decomposition gates | **4 PASS / 0 FAIL** | inertness bit-identical; body-frame closure 6e−19; `Ω_drive = M_roll·⟨τ_det⟩` 5.6e−15; **component sum now 3.0e−08** (was 1.9e−02) |
| 5 · passive nulls | **INCOMPLETE — DEFERRED** | outside the quick 5° torque scope; **zero arms, zero records**; see §5b |
| 6 · binding/gliding compatibility | implemented, **not run** | not required for the driven 5° question |
| **driven 5° production** | **DONE — Q3** | 8 arms (4 × 100 ms + 4 × 200 ms), 0 invalid, 0 solver failures; no discernible τ_odd |

**Stage 5 was NOT a prerequisite for the driven 5° measurement and is no longer claimed as one.** It is a
passive-null control for the *passive* claim; the driven question is answered by the ±ε matched-pair odd
estimator, whose gates (4b) all pass.

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

## 5b. Stage 5 disposition — INCOMPLETE — DEFERRED AS OUTSIDE QUICK 5° TORQUE SCOPE

Stopped; nothing running. Launched 2026-07-31 22:26:48Z (`-rigid-passive -gpu -eta 0.01 -passive-meas 400000
-passive-seeds 4`, commit `f382505`), ran **4190.3 s**, reached `executeIndex` 248 228 of the **first** arm's
422 000 steps = **58.8 % of one arm**, i.e. **62.06 ms simulated** of that arm's 105.5 ms. **Zero arms
completed; zero records written** — the arm table header printed and no arm row was ever emitted, so there is
no partial replicate and nothing to preserve beyond the three console logs in `RUN_LOGS/rigid/`.

**Correction to the log filenames.** `stage5_passive_KILLED_for_batching.txt` and
`..._ABANDONED_slowbuild.txt` describe deliberate stops. In fact every one of these processes died with
**SIGSEGV in `libcuda.so.1+0x345d6f`, core dumped, exit 134**, after a cascade of CUDA 709
(`CONTEXT_IS_DESTROYED`) / 400 errors; five `hs_err_pid*.log` files from 2026-07-31 carry the same frame at
1 m 25 s, 3 m 02 s, 10 m 00 s, 1 h 09 m 50 s and 1 h 12 m 18 s. Most likely reading: the **teardown signature
of killing a JVM blocked mid-`execute()`** — the shutdown hook ran inside an unmatched `EXECUTE_CALL_BEGIN`,
and `NORMAL_MAIN_RETURN` is absent. **No Xid, no NVRM fault**, no freeze, no reboot, and every run since (two
decomposition validations, one sanity pair, eight production arms) completed cleanly on the same device.
`collect_gpu_crash_case.sh` not invoked — CLAUDE.md scopes it to "after any hard freeze and reboot".

Also note the crash-trace `simulationTime` field is **10× high** on this path: it is stamped with `DT`, not
the viscosity-scaled `DTR`.

---

## 5c. The driven 5° result (2026-08-01) — **Q3 at n = 2; SUPERSEDED, see §0**

> **SUPERSEDED.** This section records the two-seed stage. Its seed statistics are superseded by the
> high-seed campaign in §0 (`RIGID_5DEG_HIGH_SEED_TORQUE.md`), and its §8 claim that the pooled-block SEM is
> "the honest one" is **corrected** by the block-correlation measurement in §0.1. Retained intact as the
> record of the first stage; do not cite its numbers without §0.

Full report: `docs/twirling/RIGID_LOW_SKEW_TORQUE_QUICKLOOK.md`. Commit `30e8891`; GPU device-resident, single
`glide` TaskGraph, monitored; 10 µM, 400 heads/µm², ±5°, seeds 101/102, η = 0.01 Pa·s, rigor rupture off,
`filSegs=1`, FDT thermostat, decomposition on.

| stage | τ_odd (N·m) | seed σ / sign | 8-block σ / sign | Ω_drive_odd (rad/s) |
|---|---|---|---|---|
| 4 arms × 100 ms | −6.986e−23 ± 2.869e−23 | 2.43 / 2 of 2 | 1.01 / 5 of 8 | −21.5 ± 8.9 |
| 4 arms × 200 ms | **−7.065e−24 ± 1.314e−22** | **0.05 / 1 of 2** | **0.09 / 4 of 8** | **−2.2 ± 40.5** |

**Bound: |τ_odd| ≲ 1.3e−22 N·m ⇒ |Ω_drive_odd| ≲ 40 rad/s.** This *contains* the ≈ 9e−23 N·m that linear
scaling from the campaign's 15° measurement predicts, so it is a statement about measurement power, not
evidence against a 5° chiral torque.

Three findings that outlive the null:

1. **The seed SEM grew 4.6× when the window doubled** (should shrink by √2 if averaging independent samples).
   The deterministic axial torque is a ~0.2 % residue of a near-cancelling tug-of-war (+7.1e−20 vs −7.1e−20
   against a +1.6e−22 net) whose imbalance drifts on the window timescale ⇒ each arm ≈ one effective sample.
   **The lever is more seeds, not longer arms.** The n = 2 seed SEM should not be quoted for this estimator;
   the disjoint-block SEM is the honest one and it flagged the 100 ms result correctly at the time.
2. **The 200 ms arms are not independent replicates of the 100 ms arms** — verified, not assumed. The RNG is
   counter-based and step-count-independent, so the 200 ms arm reproduces the 100 ms trajectory exactly:
   max |difference| in (meanRoll, glideProj) over all 2000 shared sample times = **0.000e+00**. Measured
   windows 25–100 ms and 50–200 ms overlap in 50–100 ms.
3. **`Om_Brown_odd` is exactly 0 by construction.** Matched ±ε arms at one seed draw a bit-identical
   Brownian sequence (state-independent counter hash), so the odd estimator cancels the rigid-body Brownian
   roll identically and `Ω_total_odd = Ω_drive_odd + Ω_geom_odd`. **This bounds where §4.2 applies:** the
   169 rad/s rotational floor governs a *single arm's absolute* Ω and the passive null, **not** the
   matched-pair odd estimator. §4.2 is not retracted; its scope is narrowed.

2° and 1° were **not run** — gated on 5° reaching Q1, which it did not. Recorded for the next designer: over
1–5°, `sin(ε)/ε` varies by 0.13 %, so linear vs sine-like scaling is **not separable** by this assay at any
duration; that needs a larger ε lever arm, not more time.

---

## 8. Next actions, in order

**Items 1 and 2 below have since been AUTHORIZED AND EXECUTED — see §0.** Item 1 (the rigid 15° anchor) is
DONE and passed; item 2 (more seeds at 5°) is in progress at n = 8 of 12. Items 3 and 4 remain unstarted.

1. **A rigid ±15° anchor under the identical rigid pipeline.** This is now the highest-value next run, and it
   is a *power calibration*, not a new question: 15° carries a signal ≈ 3× the 5° one, so it establishes
   whether this estimator can resolve a chiral torque it is known to contain, before any further low-skew
   work. §5c's open question — whether the 15° anchor is needed to interpret 5° — is **resolved: yes**, and
   for the torque, not only the rotation.
2. **More seeds at 5°, not longer arms** (§5c finding 1). 8–12 matched seeds at 100 ms costs roughly what the
   eight arms already run cost, and gives a genuine df; doubling duration demonstrably did not help.
3. **Stage 5 (passive nulls)** if and when a *passive* claim is to be made. It is not a prerequisite for
   driven measurements. If rerun, note it crashed twice on ~1 h arms (§5b) and should be batched so a stop
   costs one arm, not the campaign.
4. **Stage 6 (`-rigid-compat`, ~1 h)** — descriptive only; occupancy shift > 30 % is *flagged*, not failed,
   and no binding gate is to be tuned.

**Superseded — do not re-trust:** the earlier plan's "Tier-1 production at 1.0 s per arm, blocked until
Stages 5 and 6 pass". Stage 5 never gated the driven measurement; and 1.0 s arms are the wrong prescription —
§5c shows duration is not the lever for this estimator.

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

**RESOLVED 2026-08-01 at commit `8ea521c` — and the diagnosis recorded here was WRONG.** Re-run:
`RUN_LOGS/rigid/decomp_validate_fixed.txt`, **4 PASS / 0 FAIL**, gate 4 residual **3.000e−08**.

The original diagnosis — "the S2 solve rewrites `bondData` at the end of the step, so the host compares a
pre-solve `torqueSum` against a post-solve snapshot" — **is refuted by the code. `matS2SolveStep` never writes
`bondData`;** it reads it, at indices 0..2 and 12. No kernel between `segGather` and the end of the step writes
it either, so the post-step host copy holds exactly the values the gather consumed.

The real defect was the **projection axis**. `segGather` sums `bondData[9..11]` into `torqueSum`, and the
integrator forms `torqueSum·û` with the material frame **as it stood before the orientation update** — which is
why `accumulate` already takes a pre-update axis. The component reconstruction called `axialTorque`, which
reads the **current** `uVec`. Two projections of the same vector one step apart ⇒ a systematic
`O(dt·|T_perp|)` residual. Fixed by projecting the bond moments on that same pre-update axis
(`axialTorqueOnAxis`): no graph, kernel, task, snapshot, transfer, ordering, RNG, mobility or binding change,
and every pre-existing observable stayed bit-identical, so the repair is itself trajectory-inert. **No missing
torque contribution exists** — at n = 1 the bond moment *is* the whole deterministic axial torque, as
preregistered. Nothing was tuned to force closure.

**A second defect, found by the pre-production sanity check rather than by any gate** (fixed at `30e8891`):
`-rot-decomp` set `ROT_DECOMP` but not `ROT_DECOMP_TRANSFER`, while `runRotDecompValidate` set both. So all
four gates passed while the **production** path silently measured nothing — on the GPU the `torqueSum` /
`randTorque` host buffers were never written, `τ_det` and the Brownian increment read as exactly 0.0, and the
whole realized roll landed in `dPhiGeom`. The run completed, wrote records and reported 100 % seed-sign
agreement. `-rot-decomp` now sets both and `runTwirlArm` throws on the mismatch. The four zero-decomposition
records were deleted, not reused.

**Confirms the decomposition's rationale:** `|Ω_Brown| / |Ω_drive| = 2.3` even at 15° skew — the raw angle is
Brownian-dominated as predicted in §4.2. But see §5c finding 3: in the **matched-pair odd** estimator the
Brownian roll cancels identically, so that dominance does not carry over to `Ω_odd`.

**Production throughput now measured** on full-length rigid arms: **≈ 275 steps/s** device-resident under
contention ⇒ ≈ 24 min per 100 ms arm and ≈ 48 min per 200 ms arm. The eight production arms cost **≈ 4 h 51 m**
total. The 1.0 s-per-arm design in the earlier plan is superseded — §5c shows duration is not the lever.
