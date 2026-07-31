# Rigid-Filament Skew Twirling — Validation

**Branch** `feature/rigid-filament-skew-twirling`, worktree `../softbox-rigid-filament-skew-twirling`,
baseline `3119000`. Started 2026-07-31.

**Status: Stages 0–4 COMPLETE and PASSING (9/0 + inertness). Stage 5 IMPLEMENTED and RUNNING; Stage 6 implemented, not run. No production arm has
been launched.**

The rigid-filament assay isolates motor-generated chiral torque in a single-temperature, FDT-consistent
rigid-body rotational system. It is a **new thermodynamically controlled model variant**, not a correction of
the flexible records; the legacy flexible results remain historical, cross-model descriptive comparisons.

Replaces the aborted flexible 5° pilot (`docs/twirling/FIVE_DEGREE_SKEW_DURATION_PILOT.md`, branch
`feature/lowatp-5deg-duration-pilot`, commit `70a260a`).

- Logs: `RUN_LOGS/rigid/stage234.txt`, `RUN_LOGS/rigid/inertness/{new,baseline}/`
- Commits: `59926d1` (thermostat + namespace + Stages 2–4)

---

## 1. Stage 0 — code and path audit

### 1.1 The defect the rigid programme exists to remove

`TwoBodyConverterMotor.buildGlide2D`, flexible branch:

```java
boolean interior = (k>0 && k<nSeg-1);
f.brownRotScale.set(k, interior ? 0f : (float)Constants.BRotCoeff);   // BRotCoeff = 0.5
```

The Brownian kernel builds `randTorque_i = scale · sqrt(2kT·γ_i/dt)·g` from the **same** γ the integrator
divides by, so `scale` is exactly the square root of the reservoir temperature ratio. In the 12-segment
flexible filament that gives:

| segments | rotational Brownian scale | rotational reservoir | rotational drag |
|---|---|---|---|
| interior (10 of 12) | **0** | **0 kT** | full |
| ends (2 of 12) | 0.5 | **0.25 kT** | full |
| all 12, translation | 1.0 (`BTransCoeff`) | kT | full |

Ten of twelve segments carry rotational **drag with no matching noise**; the two ends sit at a quarter of the
translational temperature; and chain torsion (F4) couples them, transporting rotational energy between DOFs
held at different effective temperatures. That is a non-equilibrium steady state on precisely the coordinate
the twirling assay measures, and it is consistent with the ε = 0 flexible control rolling at ≈12 rad/s per arm
with no chirality imposed. `BRotCoeff` is documented in `Constants.java:54-58` as a **v1 persistence-length
tuning knob, explicitly not part of the FDT relation**.

### 1.2 Rigid-body state representation — found, not built

A true rigid-body single-filament branch already existed and is selected by `-filament-segments 1`
(`ChiralSiteHarness.build` → `buildS2Mat(..., rigidFil)` → `buildGlide2D(..., rigid)`):

| requirement | how the existing branch satisfies it |
|---|---|
| true rigid-body state and update | one `RigidRodBody` (n = 1), advanced by the FDT-validated `RigidRodLangevinIntegrationSystem` (overdamped explicit Euler, body-frame diagonal drag). **Not** penalty stiffness — see §4 |
| no internal DOF | n = 1 ⇒ no neighbour slots, no joints, no internal coordinate. Bending/extension/relative rotation are **absent**, not stiff |
| full rigid-body motion | translation in x/y/z and rotation about all three body axes; the material triad `(uVec, yVec, zVec)` is integrated, so **axial roll is a tracked, free, measurable coordinate** |
| mobility not a segment-drag reuse | `DragTensorSystem.rodDragSI(L_full, R)` at the **full 2.106 µm contour**, not a sum of short-segment drags |
| helical sites as material points | sites are stored as `bindArc` (axial) + `bindAzim` (azimuth) and reconstructed each step from the body's own material triad, so filament roll rotates the helical lattice and every bound anchor together. The helical registry is preserved; no smooth-cylinder substitution |

### 1.3 What rigid mode does **not** touch

Proven by construction at n = 1 and by gate 3 (`RUN_LOGS/rigid/stage234.txt`):

- **`brownRotScale` / `BRotCoeff`** — set to exactly 1.0 in FDT mode; the constant is not read on this path;
- **segment-level rotational Brownian forcing** — there is one body, so "per-segment" has no referent;
- **internal bending forces** — `ChainBendingForceSystem.chainForces` is a structural no-op (both neighbour
  slots are the no-neighbour sentinel; gate 3 asserts `filAtEnd1(0) && filAtEnd2(0)`);
- **internal torsional forces** — same;
- **roll spring / registry** — off in this scene.

Motors, converter geometry, chemistry, binding gates and motor Brownian channels are **unchanged**. No change
was mathematically required at the rigid-body interface: the integrator already consumes a wrench
(`forceSum`, `torqueSum`) and the cross-bridge gather already reduces per-bond forces to that form.

### 1.4 The one change made

`-fil-thermostat fdt|legacy` (default **legacy** ⇒ byte-identical). On the rigid branch only:

```java
f.brownTransScale.set(0, FIL_THERMOSTAT_FDT ? 1.0 : Constants.BTransCoeff);
f.brownRotScale  .set(0, FIL_THERMOSTAT_FDT ? 1.0 : Constants.BRotCoeff);
```

FDT mode gives **one rigid-body thermal reservoir** at kT for translation and rotation alike: every
drag-carrying DOF receives exactly the noise its own γ demands. There is no per-segment scale and no
persistence-length knob on this path.

### 1.5 Record namespace

Rigid records live in their own namespace and cannot collide with, alias onto, or overwrite any
flexible-filament record. There is deliberately **no fallback**: nothing legacy exists to read.

```
rigid_th{fdt|leg}_u<ATP>_e<skew×10>_r<density>_d<duration µs>_[m_]{p|n|z}_<seed>
e.g.  rigid_thfdt_u0010.00_e0050_r0400.0_d01000000_p_101
```

Model and thermostat also enter the provenance line (`filSegs`, `filModel`, `thermostat`).

---

## 2. Stage 1 — default inertness

Rigid mode off (default `-filament-segments 12`, `-fil-thermostat legacy`): a short flexible arm was run on
the changed build, then the two changed files were reverted to `3119000`, rebuilt, and the same arm rerun.

| artefact | result |
|---|---|
| record `.tsv` (115 data lines) | **byte-identical**, both ε signs |
| dense `.trace.tsv` (4001 lines) | **byte-identical**, both ε signs |
| `.nested.tsv` prefix readout | **byte-identical** |
| `.nbhist.tsv` P(N_b) | **byte-identical** |
| provenance comment line | **differs** — gains `filSegs=12 filModel=flexible-chain thermostat=legacy-BRotCoeff` |

**Every value is unchanged; the only difference is added metadata in a comment line.** Stated explicitly
rather than claimed as blanket byte-identity. No RNG stream changes (the thermostat scale is a multiplier on
an already-drawn Gaussian; the flexible branch's scales are untouched). Evidence:
`RUN_LOGS/rigid/inertness/{new,baseline}/`.

---

## 3. Stage 2 — free rigid-body FDT

### 3.1 The exact discrete law

For a free body (`forceSum = torqueSum = 0`) the integrator's one-step body-frame increments are

```
dx_i  = 1e6 · dt · randForce_i /γT_i = 1e6 · sqrt(2 kT dt / γT_i) · g     (µm)
dθ_i  =       dt · randTorque_i/γR_i =       sqrt(2 kT dt / γR_i) · g     (rad)
```

so the **exact** discrete one-step variance is `2 D_i dt` with `D_i = kT/γ_i`. There is no discretisation
correction for a free body — unlike a confined AR(1) coordinate, where the `1/(1−c/2)` factor applies. The
gates compare against that exact law, not the continuum limit.

### 3.2 The rigid body and its mobility

Slender-body cylinder, one body, L = 2.106 µm (779 monomers), R = 3.5 nm, η = 0.01 Pa·s:

| quantity | parallel / roll | perpendicular / tumbling |
|---|---|---|
| γ_trans (N·s/m) | 2.402990e−08 | 4.042500e−08 |
| γ_rot (N·m·s) | **3.241935e−24** | 1.938983e−20 |
| D = kT/γ | 1.713051e−01 µm²/s | 1.018291e−01 µm²/s |
| D_rot = kT/γ | 1.269749e+03 rad²/s | 2.122991e−01 rad²/s |

`rodDragSI`: `γ_par = 2πηL/(ln(L/2R)+a_∥)`, `γ_perp = 4πηL/(ln(L/2R)+a_⊥)`, `γ_roll = 4πηR²L`,
`γ_tumb = πηL³/(3(ln(L/2R)+a_turn))`.

**Translation–rotation coupling is neglected**: the mobility is diagonal in the body frame. For a straight
cylinder the coupling tensor vanishes at the centre of mobility, which is the body centre used here, so this
is exact for the modelled shape rather than an approximation.

> **Note for closure comparisons.** `γ_roll = 4πηR²L` is **extensive in L**, so one rod of length L and twelve
> of length L/12 give the *same* axial roll drag (3.241935e−24 either way). Rigid and flexible closure numbers
> are therefore directly comparable on the roll axis. Tumbling and translation carry `ln(L/2R)` factors and are
> **not** comparable in that way.

### 3.3 Results — 12 seeds × 20 000 steps per timestep

| dt (s) | DOF | measured D | exact kT/γ | meas/pred | mean/SEM | kurtosis |
|---|---|---|---|---|---|---|
| 2.500e−07 | roll (axial) | 1.273882e+03 | 1.269749e+03 | **1.0033** | 0.18 | 2.976 |
| 2.500e−07 | tumble | 2.119649e−01 | 2.122991e−01 | **0.9984** | n/a | n/a |
| 2.500e−07 | trans ∥ | 1.705794e−01 | 1.713051e−01 | **0.9958** | 1.19 | 3.003 |
| 2.500e−07 | trans ⊥ | 1.019497e−01 | 1.018291e−01 | **1.0012** | n/a | n/a |
| 1.250e−07 | roll (axial) | 1.274687e+03 | 1.269749e+03 | 1.0039 | 0.18 | 2.978 |
| 6.250e−08 | roll (axial) | 1.275091e+03 | 1.269749e+03 | 1.0042 | 0.18 | 2.980 |

**All four DOF are within 0.4 % of the exact law** — well inside the 5 % gate. Signed increments are
zero-mean (0.18σ for roll ⇒ **no preferred roll direction**; 1.19σ for parallel translation ⇒ no spurious
drift) and Gaussian (kurtosis 2.98–3.00).

Tumbling and perpendicular translation are **magnitudes of 2-D Gaussian vectors**, so a mean and a kurtosis
are not defined for them (marked n/a); their variance gate is the informative one, and the sign/Gaussianity
evidence is carried by the two signed DOF. Reported this way rather than printing a synthetic value.

**dt convergence.** Three of the four DOF are *exactly* dt-invariant by construction — dt cancels in
`D = var/(2dt)` and, on a common RNG stream, reproduces to every digit. The dt gate is therefore only
informative for **axial roll**, whose parallel-transport estimator carries the O(dt²) orientation-
renormalisation nonlinearity: spread **0.0010** over a 4× range.

| gate | result |
|---|---|
| 1 · FDT thermostat: both Brownian scales exactly 1.0, no BRotCoeff on the rigid path | **PASS** |
| 2 · legacy mode still reproduces the inherited knob (0.5) | **PASS** |
| 3 · n = 1 ⇒ no chain topology (both neighbour slots sentinel) | **PASS** |
| 4 · diffusion within 5 % of exact `D = kT/γ`, four DOF × three timesteps | **PASS** |
| 5 · signed increments zero-mean and Gaussian | **PASS** |
| 6 · axial-roll D dt-converged over 4× (spread 0.0010) | **PASS** |

---

## 4. Stage 3 — rigidity

Under the full production scene (motors bound and cycling, 4000 steps):

| quantity | max |
|---|---|
| \|ΔL\|/L (contour) | **0.000e+00** |
| \|\|u\|−1\|, \|\|y\|−1\| (frame norm) | 2.230e−07 |
| \|u·y\| (orthogonality) | 8.513e−10 |
| \|Δ(end2−end1)\| vs L | 1.620e−07 |

Bending, extension and relative segment rotation are **absent at n = 1, not stiff**: there is no second body
to bend against and no internal coordinate to deform. This is a true rigid-body state, **not** a
penalty-stiffness approximation, so no stiffness value, timestep-stability argument or residual-deformation
tolerance has to be preregistered. What remains measurable — that the body frame stays orthonormal and the
contour fixed under load — is at float32 roundoff. **Gate 7 PASS.**

---

## 5. Stage 4 — force/torque closure

Deterministic, Brownian off, against the same mobility the integrator uses:

| fixture | measured | predicted | meas/pred |
|---|---|---|---|
| pure axial force (1 pN) | 4.161480e+01 µm/s | 4.161483e+01 | 0.999999 |
| pure transverse force (1 pN) | 2.473718e+01 µm/s | 2.473717e+01 | 1.000000 |
| pure axial torque (1e−21 N·m) | 3.084577e+02 rad/s | 3.084577e+02 | 1.000000 |
| pure tumbling torque | 5.157349e−02 rad/s | 5.157343e−02 | 1.000001 |

Off-centre force at `r = 0.31L·û + R·ŷ`, `F = 1 pN·ẑ`:

| check | result |
|---|---|
| axial torque vs `r × F` | 3.500000e−15 vs 3.500000e−15 — **ratio 1.000000** |
| origin shift along the axis (d = 0.137 µm) | relative change **0.000e+00** |
| mirrored placement (`r = 0.31L·û − R·ŷ`) | torque ratio **−1.000000** |

**Gates 8, 9 PASS.**

### 5.1 Failure and correction

Gate 9 **failed on first run**. Measured axial torque was `+3.5e−15`; the preregistered expectation was
`−F·R`. The correct reduction is `(arc·û + R·ŷ) × (F·ẑ) = −arc·F·ŷ + R·F·û`, since `ŷ × ẑ = +û`, so the axial
part is `+F·R`. **The expectation was wrong, not the code** — the magnitude, the origin-shift invariance
(exactly 0) and the mirror sign flip (exactly −1) were all correct on the first run. The expectation was
corrected and the fixture rerun. Recorded here because a sign convention caught by a fixture is exactly what
Stage 4 exists to catch, and because the same sign enters every ε-odd torque statement downstream.

---

## 6. A load-bearing consequence of fixing the thermostat

**Correcting the thermostat RAISES the rotational noise floor.** This was computed before running Stage 5, and
it reshapes what the rigid programme can and cannot measure.

The flexible model gave interior segments *zero* rotational Brownian torque and the two ends only `0.5×`
amplitude — roughly 4 % of a full kT rotational reservoir. That is precisely why its rotational floor looked
small (σ ≈ 8.74 rad/s on Ω_odd at 150 ms). The FDT-correct rigid body carries the **full** kT reservoir on
axial roll, with `D_roll = kT/γ_roll = 1.270×10³ rad²/s`.

Achievable 3σ bound on a passive Ω_odd, n = 4 matched pairs:

| window per arm | σ(Ω) per arm | 3σ bound on Ω_odd | vs the ~50 rad/s driven reference |
|---|---|---|---|
| 100 ms | 159 rad/s | **169 rad/s** | 3.4× too weak |
| 300 ms | 92 rad/s | **98 rad/s** | 2.0× too weak |
| 1000 ms | 50 rad/s | **53 rad/s** | still ≈ 1.1× too weak |

**No feasible duration makes the rotational null informative.** A σ-gate on Ω would therefore have passed
because the measurement is insensitive, not because the null holds — the third vacuous-pass construction found
in this stage (see §8). The passive chiral null is consequently **gated on τ_odd**, a direct time-average of a
bounded quantity that resolves well, and the rotation is **reported as an explicit bound** carrying an
INCONCLUSIVE verdict whenever the bound exceeds the reference scale.

**Consequence for production:** rotation is diffusion-limited in the rigid model and torque is the observable
that carries the physics. **R3 (rigid torque resolved, rotation diffusion-limited) is the a-priori likely
classification**, and that expectation is recorded here *before* the production arms run.

This is not an argument that the flexible model was better. Its smaller rotational floor was an artefact of
missing noise on a coordinate that carried full drag — the defect itself. The rigid model trades an
artificially quiet, thermodynamically wrong observable for a noisy, correct one.

## 7. Stages 5–6 — IN PROGRESS

**Stage 5, passive nulls — implemented and RUNNING** (`-rigid-passive`, log `RUN_LOGS/rigid/stage5_passive.txt`).

The passive step is `stepGlidingCPU` / the production TaskGraph with **exactly two things removed and nothing
else**: binding, and the nucleotide cycle. The bound set and nucleotide states are frozen, so `matCock` returns
a constant rest coordinate and every cross-bridge is a passive spring. What remains is springs plus thermal
noise in a fixed topology — an equilibrium system, in which detailed balance forbids a sustained rotational
current however chiral the geometry. `PASSIVE_MODE` defaults false ⇒ the production graph is byte-unchanged.

| arm | construction | seeds |
|---|---|---|
| A no motors | every bond stripped | 1 (Stage 2 carries the quantitative free-body null) |
| B passive achiral | bound, ε = 0 | 4 |
| C± passive chiral | bound, ε = ±15°, matched seeds | 4 |
| D thermal off | bound, ε = +15°, Brownian off | 1 (deterministic) |

Each arm: 20 000 driven warm-up steps at the production condition → freeze → 2 000 relax → 400 000 measured
(100 ms). Gate D is written on the measurement **halves**, because a deterministic non-zero mean is either a
decaying mechanical transient or a sustained non-conservative current and the mean alone cannot distinguish
them. Gate C is written on **τ_odd** for the reason in §6.

**Stage 6, binding/gliding compatibility — implemented, not yet run** (`-rigid-compat`): rigid vs legacy
flexible at 10 µM, 400 heads/µm², 0° skew, same seeds. Descriptive by construction — equality is not required
and **no binding gate is tuned**. Gates are usability only (attachment viable, occupancy stable across seeds,
gliding directed, zero invalid/solver). An occupancy change > 30 % is **flagged** for geometry/surface-height
investigation, not failed.

No Tier-1 production arm may be launched until both pass.

## 8. Failures and corrections

Recorded because all four were caught by construction rather than by luck, and three of them would have
produced a **false pass on a gate**.

| # | defect | how it surfaced | resolution |
|---|---|---|---|
| 1 | Stage 4 gate 9's preregistered axial sign was `−F·R` | fixture disagreed on sign only, magnitude exact | the **expectation** was wrong (`ŷ × ẑ = +û`); code unchanged |
| 2 | `passiveArm` never called `cfg()`, so the discrete-site / head-roll / surface machinery stayed off | N_b = 1 of 1200 and **every observable identically 0.0000** — all four gates "passed" | configure the scene exactly as `runTwirlArm` does |
| 3 | with one seed the SEM is 0, so every σ evaluated to 0.00 | all gates passed regardless of data | the stage now **refuses to run** below two seeds; gate A rewritten to test what a single arm can establish |
| 4 | a σ-gate on passive Ω passes because the rotational measurement is insensitive | sensitivity computed **before** the run (§6) | chiral null re-gated on τ_odd; Ω reported as a bound with an INCONCLUSIVE verdict |

A fifth, not a false pass but a monitoring gap: the first Stage-5 launch called `plan.execute()` directly, so
the crash heartbeat reported `state=STARTING` for the whole run and a fault could not have been localised.
CLAUDE.md requires `TornadoCrashDiagnostic` tracing on any new GPU entry point; the run was stopped after ~3
minutes and relaunched through a traced helper rather than running blind.

## 9. GPU health and contention

External crash recorder running throughout (pid 1988, session `20260728T071146Z`, `journal_ok=yes`,
`nvidia_smi_ok=yes`). No Xid or NVRM fault observed. **Stage 5 shares the GPU with an unrelated
torsional-ratchet campaign** in `../softbox-rigid-filament-torsional-ratchet`, launched outside the monitored
wrapper by the repository owner. Measured throughput under contention is ≈24–76 steps/s against ≈277 steps/s
uncontended, so **all Stage-5 wall-clock figures are contended and are not a throughput measurement.**

---

## 7. Scope and honesty statements

- No motor, chemistry, binding-gate, Brownian-amplitude, viscosity or geometry parameter was changed. The one
  change is the rigid branch's filament thermostat scale, default-off.
- No parameter was tuned after seeing any result. The one correction made after seeing a result was to a
  *preregistered expectation* that was algebraically wrong (§5.1), not to the model.
- The flexible implementation is retained and reachable (`-fil-thermostat legacy`, any `-filament-segments`
  > 1) for reproduction only.
- The rigid assay is **not** evidence that filament flexibility is irrelevant. It removes internal bending,
  segment material roll, persistence-length calibration, end-only rotational forcing, nonuniform effective
  temperature and internal rotational energy transport from the measurement — by construction, not by test.
