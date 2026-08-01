# Rigid-Filament Low-Skew Torque — Quick Look at 5°

**Question.** At a converter skew of 5°, does the fully thermalized rigid-filament model produce a
discernible chirality-odd axial motor torque capable of driving filament twirling — and roughly how large
are `tau_odd` and `Omega_drive_odd = tau_odd / gamma_roll`?

This is an exploratory first estimate, not a validation programme.

**Answer: NO — not at this design. Classification Q3.** At 5° the chirality-odd deterministic motor torque is
**not discernible** with 2 matched seeds at either 100 ms or 200 ms per arm. The 100 ms stage looked suggestive
(Q2); doubling the window collapsed it to 0.05σ with the two seeds disagreeing in sign. Bound:
**|τ_odd| ≲ 1.3e−22 N·m**, i.e. **|Ω_drive_odd| ≲ 40 rad/s** (1 SEM). Stopped, per the Q3 rule; no
multi-second campaign, no 2°/1° checks.

---

## 1. Commit and configuration

| item | value |
|---|---|
| worktree | `/home/jba/Code/softbox-rigid-filament-skew-twirling` |
| branch | `feature/rigid-filament-skew-twirling` |
| commit (production) | `30e8891` |
| runner | **GPU device-resident**, single `glide` TaskGraph (`buildGlidingGraph(prod)`), PTX / RTX 5070 |
| monitoring | `run_gpu_monitored.sh` outermost; external recorder pid 1988, session `20260728T071146Z` |

Production command:

```
./scripts/run_gpu_monitored.sh ./scripts/run_chiral_sites.sh -atp-map -gpu -eta 0.01 \
    -atp-points 10 -atp-eps-deg 5 -density 400 -seed 101 -seeds 2 \
    -atp-duration-ms 100 -filament-segments 1 -fil-thermostat fdt -rot-decomp
```

Resolved scene (from the record provenance line):

```
filSegs=1  filModel=RIGID-1seg  thermostat=FDT  rupture_mode=0
atpUM=10.00  atpOn=100.0 /s  density=400 heads/µm²  nMotors=1200
eps=±5.0°  mirror=1  eta=0.01 Pa·s  dt=2.5000e-07 s  durationS=0.100  equil=0.25
```

`gamma_roll = 3.241935e-24 N·m·s`, `M_roll = 1/gamma_roll = 3.084577e+23 rad/(s·N·m)`.

**Duration accounting.** Each arm simulates **100 ms**; the campaign-standard 25 % equilibration is
discarded, so the **measured window is 75 ms** and the four non-overlapping blocks are **18.75 ms** each.
The 25 % equilibration is the standing convention of this ladder (it is recorded per-arm as `equilFrac`);
it was not changed for this study, so these arms remain directly comparable with the rest of the campaign.
Total simulated production time: 4 arms × 100 ms = **400 ms**, within the 800 ms cap.

---

## 2. Minimal estimator fix

### 2.1 The recorded diagnosis was wrong

`RIGID_FILAMENT_SKEW_VALIDATION.md` §10 attributed the 1.9 % gate-4 failure to `matS2SolveStep` rewriting
`bondData` at the end of the step, so that the host compared a `torqueSum` built from pre-solve `bondData`
against a post-solve snapshot.

**`matS2SolveStep` never writes `bondData`.** It reads it, at indices 0..2 (the F8 reaction) and 12
(`forceDotFil`), and writes neither. No kernel between `segGather` and the end of the step writes
`bondData` at all. The post-step host copy therefore holds **exactly** the values the gather consumed, and
the ordering hypothesis is refuted by the code.

### 2.2 The actual defect: the projection axis, not the data

`segGather` sums `bondData[9..11]` into `torqueSum`; the integrator then forms `torqueSum·û` using the
material frame **as it stood before this step's orientation update**. `RigidRollDecomposition.accumulate`
already takes that pre-update axis — that is why it has `ux,uy,uz` parameters.

The component reconstruction did not. It called `ChiralSiteSystem.axialTorque`, which reads the filament's
**current** `uVec`, i.e. the **post**-update axis. So the two sides of the check were projections of the
same torque vector onto two different axes, one step apart, leaving a systematic residual of order
`dt·|T_perp|` — about 2 % here, because the transverse bond moment is much larger than its axial part.

### 2.3 The fix

`ChiralSiteSystem.axialTorqueOnAxis(bondData, boundSeg, m, ax, ay, az)` — identical arithmetic, caller-supplied
axis — invoked with the same pre-update axis `tau_det` uses.

No graph change, no kernel, no new task, no snapshot buffer, no extra transfer, no change to motor forces,
chemistry, integration, RNG draws, kernel order, mobility or binding. The diagnostic remains default-off and
read-only.

**No missing torque contribution was found.** At n = 1 there is no chain torsion and z-confinement enters
`forceSum` only, so the bond-force moment *is* the whole deterministic axial torque, exactly as preregistered.
Nothing was tuned or rescaled to force closure.

### 2.4 A second defect, found by the sanity check rather than by a gate

`-rot-decomp` set `ROT_DECOMP` (allocate the accumulator) but **not** `ROT_DECOMP_TRANSFER` (add the
device→host read-back of `torqueSum`/`randTorque`). `runRotDecompValidate` sets both explicitly, so all four
validation gates passed while the **production** path was silently broken: on the GPU those two host buffers
were never written, `tau_det` and the Brownian increment read as exactly `0.0`, and the entire realized roll
landed in `dPhiGeom`. The run completed, wrote records, and reported 100 % seed-sign agreement.

`-rot-decomp` now sets both, and `runTwirlArm` throws if the accumulator exists on the device path without the
read-back, so the mismatched combination is unreachable. The four zero-decomposition 10 ms records written by
the broken build were deleted, not reused.

---

## 3. Decomposition validation (re-run after the fix)

`-rot-decomp-validate -gpu -eta 0.01`, log `RUN_LOGS/rigid/decomp_validate_fixed.txt`. **4 PASS, 0 FAIL.**

| gate | before | after |
|---|---|---|
| 1 · trajectory inertness (diagnostics OFF vs ON) | PASS | **PASS** — bit-identical |
| 2 · body-frame update closure | PASS — rel. accumulated residual 5.968e−19 | **PASS**, unchanged |
| 3 · torque-drift closure `Ω_drive = M_roll·⟨τ_det⟩` | PASS — 5.626e−15 | **PASS**, unchanged |
| 4 · deterministic torque-component sum | **FAIL — 1.902e−02** | **PASS — 3.000e−08** |

Gate 1 detail, identical in both runs and identical to the pre-fix log:

```
glide -2.460132043e-02   omega 3.158166267e+02   omegaFit 2.292855695e+02   avgBound 3.106193333e+01
tau   -8.032577038e-22   turns 1.884891647e-01   rollR2 2.731464221e-01   strokeRate 1.866666667e+03
```

Every pre-existing observable is bit-identical to the pre-fix log, so **the estimator repair is itself
trajectory-inert**. Gate 4's residual fell from 1.9 % to float32 roundoff with no change to any force.

---

## 4. Minimal rigid-scene sanity check

One 10 ms arm pair on the production path (`RUN_LOGS/rigid/sanity_10ms.txt`, records tagged `d00010000`, a
distinct namespace from the 100 ms production tag so they cannot be confused or reused).

| check | result |
|---|---|
| realized filament segment count = 1 | **yes** — `filSegs=1 filModel=RIGID-1seg`; `atpArm`'s structural guard throws otherwise |
| rigid FDT thermostat active | **yes** — `thermostat=FDT` |
| binding occurs | **yes** — avgBound 29.4 (+ε) / 31.8 (−ε) of 1200 heads |
| occupancy nonzero and stationary | **yes** — mean N_b ≈ 30.6, stable across blocks |
| gliding direction | **−0.197 µm/s** (pointed-leading, the expected sign) |
| NaN / invalid / solver failures | **none** — `invalid=0`, `solverFail=0` |
| torque and decomposition records populated | **yes** — `rdSteps = measSteps = 30000`, `rdBlockSteps = 7500` |
| rigor rupture off | **yes** — `rupture_mode=0`, `detachAtp` fraction 1.000 |

Legacy flexible occupancy agreement was neither required nor checked.

### 4.1 A structural property of the matched-pair design, observed here

`Om_Brown_odd` came out **exactly 0.0**. This is not a coincidence and not a bug: the rigid body's Brownian
torque is drawn from the counter-based hash keyed by (body, step, seed), which is **state-independent**, so
the +ε and −ε arms at the same seed draw a **bit-identical** Brownian sequence. The odd combination cancels it
identically, and

```
Omega_total_odd = Omega_drive_odd + Omega_geom_odd     (exactly)
```

Confirmed on the sanity arm: −19.10689 = −19.13592 + 0.02903.

**Consequence.** The §4.2 / §6 sensitivity argument in `RIGID_FILAMENT_SKEW_VALIDATION.md` — that the full-kT
rotational reservoir puts a 169 rad/s floor under `Omega_odd` at 100 ms — applies to a **single arm's absolute
Ω**, and to a passive null where there is no matched partner sharing the stream. It does **not** apply to the
matched-pair ±ε odd estimator on a shared seed, where the Brownian roll is common-mode and cancels exactly.
The residual uncertainty on `Omega_drive_odd` is **between-seed motor-ensemble scatter**, not filament
rotational diffusion. This does not retract the §4.2 calculation; it bounds where it applies.

---

## 5. Arm inventory and runtime

Log `RUN_LOGS/rigid/prod_5deg_100ms.txt`. All four arms ran to completion; the process exited 0.

| # | record | glide µm/s | Ω_fit rad/s | avgBound | invalid | solverFail | wall |
|---|---|---|---|---|---|---|---|
| 1 | `rigid_thfdt_u0010.00_e0050_r0400.0_d00100000_p_101` | −0.310 | −19.99 | 21.70 | 0 | 0 | 1436.6 s |
| 2 | `..._p_102` | −0.360 | +57.37 | 23.10 | 0 | 0 | 1458.6 s |
| 3 | `..._n_101` | −0.255 | +17.67 | 22.48 | 0 | 0 | 1485.7 s |
| 4 | `..._n_102` | −0.275 | +85.19 | 24.45 | 0 | 0 | 1496.3 s |

`records: 0 reused, 4 newly run, 4 expected`. Total ≈ **1 h 38 m** GPU wall, ≈ 275 steps/s, contended
throughout with an unrelated torsional-ratchet campaign owned by the repository owner (left alone). Gliding is
directed and negative in every arm; occupancy is stable across arms (21.7–24.5 of 1200 heads); zero invalid,
zero solver failures.

---

## 6. 5° result — τ_odd and Ω_drive_odd

**Primary (deterministic motor torque):**

```
tau_odd  = -6.986e-23 ± 2.869e-23 N.m     |m|/SEM 2.43   seed-sign 100%   [-4.117e-23, -9.856e-23]
tau_even = -3.762e-22 ± 4.827e-22 N.m     |m|/SEM 0.78   seed-sign  50%
```

**Secondary (derived motor-driven drift), via the integrator's own axial mobility
`M_roll = 3.084577e+23 rad/(s·N·m)`:**

```
Om_drive_odd  = -21.55 ± 8.85 rad/s       |m|/SEM 2.43   seed-sign 100%   [-12.70, -30.40]
Om_drive_even = -116.0 ± 148.9 rad/s      |m|/SEM 0.78   seed-sign  50%
```

**Corroborating:**

```
Om_total_odd  = -21.54 ± 8.84 rad/s       |m|/SEM 2.44   seed-sign 100%
Om_Brown_odd  =  0.000 exactly            (common-mode; see §4.1)
Om_geom_odd   = +0.0104 ± 0.0079 rad/s
```

The sign is the **native chiral sign** — negative, matching the standing chiral-site campaign's native
τ_odd/Ω_odd at ε = 15° (which its mirror control reversed). Magnitude is consistent with roughly linear
scaling in ε: the campaign's 15° values were τ_odd ≈ −2.7e−22 N·m and Ω_odd ≈ −91 rad/s, which scale to
≈ −9e−23 and ≈ −30 rad/s at 5°. That is a **cross-model** comparison (flexible campaign vs rigid model), not a
gate — but it is the right order and the right sign.

Because `Om_Brown_odd` cancels identically (§4.1), `Om_total_odd = Om_drive_odd + Om_geom_odd` to three
figures, and the geometric term is 0.05 % of the drift. So the raw-angle odd estimator carries essentially the
same information as the deterministic one here — the Brownian roll never entered it.

---

## 7. Component breakdown

| quantity | ε-even | ε-odd |
|---|---|---|
| bond-force moment | −3.7617e−22 N·m | −6.9862e−23 N·m |
| any other deterministic contribution | +2.06e−30 N·m | −2.98e−31 N·m |
| τ per bound head | −1.728e−23 N·m | −3.286e−24 N·m |
| positive-torque head population | +6.617e−20 N·m | −2.178e−21 N·m |
| negative-torque head population | −6.655e−20 N·m | +2.108e−21 N·m |
| mean occupancy N_b | 22.93 | −0.533 |
| gliding velocity | −0.300 µm/s | −0.035 µm/s |

Accumulated angle over the measured window (even): `Phi_drive −8.703`, `Phi_Brown +9.623`,
`Phi_geom −0.0119`, `Phi_total +0.909` rad.

**The bond-force moment is the entire deterministic axial torque**, per arm, to
`|tau_other/tau_det|` = 8.7e−9, 5.4e−7, 3.2e−9, 3.6e−8. There is no fourth contribution and no missing channel.

Two features worth stating plainly:

- The signed head populations are **~1000× larger than their sum** (+6.6e−20 and −6.7e−20 against a −3.8e−22
  net). The net axial torque is a small residue of a near-cancelling tug-of-war between heads driving the
  filament in opposite roll senses. That is the source of the variance, and it is physical, not numerical.
- τ_even is **not** resolved (0.78σ, seeds disagreeing in sign) while τ_odd is the cleaner of the two. The
  chirality-odd projection is doing real work: it removes a large, seed-dependent achiral component that
  swings by two orders of magnitude between arms (per-arm τ_det ranges from −9.0e−22 to +8.0e−24).

---

## 8. Block and seed stability

Blocks are the four **disjoint** 18.75 ms quarters of each arm's 75 ms measured window. Halves are sums of
disjoint block pairs. No nested prefix is used as a replicate anywhere.

Per-seed, per-block τ_odd (N·m):

| | Q1 | Q2 | Q3 | Q4 |
|---|---|---|---|---|
| seed 101 | −3.284e−22 | −2.092e−22 | **+2.925e−22** | +8.039e−23 |
| seed 102 | −1.662e−22 | **+3.025e−23** | −9.710e−23 | −1.612e−22 |

| estimator | value | |m|/SEM | sign agreement |
|---|---|---|---|
| full window, 2 seeds | −6.986e−23 ± 2.869e−23 | **2.43** | 2 of 2 |
| **8 disjoint blocks** | −6.986e−23 ± 6.924e−23 | **1.01** | **5 of 8** |
| first half | −1.684e−22 ± 1.004e−22 | 1.68 | 2 of 2 |
| second half | +2.865e−23 ± 1.578e−22 | 0.18 | 1 of 2 |

**The two uncertainty estimates disagree, and the block one is the honest one.** With n = 2 seeds the SEM is
just |a − b|/2 — a one-degree-of-freedom estimate that happened to come out small; at df = 1 even 2.43σ is far
from significant (the 95 % t critical value is 12.7). The 8-block SEM uses the variation the measurement
actually exhibits and gives **1.01σ**. Three of eight blocks carry the wrong sign, and the whole second half is
consistent with zero (0.18σ, seeds disagreeing).

Occupancy is stationary and usable (21.7–24.5 across arms, no drift); gliding is directed and negative in all
four arms. The instability is in the torque estimator, not in the scene.

---

## 8b. The 200 ms extension — the signal does not survive

Log `RUN_LOGS/rigid/prod_5deg_200ms.txt`; records `d00200000`. All four arms completed, exit 0, ≈ 2890 s each
(**3 h 13 m** total), `invalid = 0`, `solverFail = 0`, occupancy 22.8–27.0, gliding directed and negative in
every arm.

```
tau_odd       = -7.065e-24 ± 1.314e-22 N.m   |m|/SEM 0.05   seed-sign 50%   [+1.243e-22, -1.384e-22]
Om_drive_odd  = -2.18 ± 40.52 rad/s          |m|/SEM 0.05   seed-sign 50%
tau_even      = +1.604e-22 ± 1.655e-22 N.m   |m|/SEM 0.97   seed-sign 50%
```

Side by side:

| | 100 ms (measured 25–100 ms) | 200 ms (measured 50–200 ms) |
|---|---|---|
| τ_odd | −6.986e−23 | **−7.065e−24** |
| seed SEM / σ / sign | 2.869e−23 / **2.43** / 2 of 2 | 1.314e−22 / **0.05** / **1 of 2** |
| 8-block SEM / σ / sign | 6.924e−23 / **1.01** / 5 of 8 | 7.722e−23 / **0.09** / **4 of 8** |
| Ω_drive_odd | −21.5 ± 8.9 rad/s | −2.2 ± 40.5 rad/s |
| per-arm τ_det (p101, p102, n101, n102) | −9.00e−22, +8.02e−24, −8.18e−22, +2.05e−22 | +1.19e−22, +1.88e−22, −1.29e−22, +4.64e−22 |

**The seed SEM grew 4.6× when the window doubled.** For an average of independent samples it should have
shrunk by √2. That is the diagnostic: the deterministic axial torque has a correlation time comparable to or
longer than these windows, so each 75–150 ms arm supplies roughly **one** effective sample and time-averaging
does not reduce the variance. The 100 ms seed agreement (2 of 2, 2.43σ) was a small-n coincidence, exactly as
the 8-block estimate (1.01σ) warned at the time.

**The two stages are not independent replicates — verified, not assumed.** The counter-based RNG is keyed by
(body/motor, step, seed) and never by the total step count, and the scene is built from the seed alone, so the
200 ms arm reproduces the 100 ms arm's trajectory exactly for its first 100 ms. Compared directly on the dense
prefix traces at all 2000 shared sample times over 0–0.09995 s: **max |difference| in (meanRoll, glideProj) =
0.000e+00**. The measured windows are 25–100 ms and 50–200 ms, overlapping in 50–100 ms. So the disagreement
between the stages is genuine within-trajectory drift of one system, not two independent draws — which
strengthens rather than weakens the long-correlation-time reading.

---

## 9. Classification — **Q3, NO DISCERNIBLE 5° TORQUE**

The 100 ms stage alone was **Q2** (mean carrying the expected native sign at 2 of 2 seeds, but only 1.01σ on
disjoint blocks with 3 of 8 blocks and the whole second half disagreeing). Per the Q2 branch the same four arms
were extended to 200 ms and re-adjudicated. Against the Q3 rule:

| criterion | status |
|---|---|
| signs are inconsistent | **yes** — seeds split 1/2, blocks 4/8 at 200 ms |
| estimate remains near the zero-skew / numerical floor | **yes** — −7.06e−24, i.e. 1/18 of its own SEM and consistent with zero |
| extension to 200 ms does not improve stability | **yes** — it degraded: 2.43σ → 0.05σ (seed), 1.01σ → 0.09σ (block) |

**Stop.** No multi-second campaign is launched.

Not Q4: the assay is healthy on every structural axis — the filament is rigid (n = 1, `filSegs=1`,
`filModel=RIGID-1seg`), the FDT thermostat is active, all four decomposition gates pass, the diagnostics are
trajectory-inert, the bond moment accounts for the whole deterministic axial torque to ~1e−8, binding and
occupancy are stable and usable, gliding is directed, and there were zero invalid states and zero solver
failures across all eight production arms. **The measurement is sound; the quantity is simply not resolved.**

### 9.1 Bound, and why the variance is what it is

The defensible statement is a bound, not a value:

```
|tau_odd|      <~ 1.3e-22 N.m       (1 SEM, n = 2 matched seeds, 200 ms arms)
|Om_drive_odd| <~ 40 rad/s
```

For scale, the cross-model expectation from the standing chiral-site campaign at ε = 15° (τ_odd ≈ −2.7e−22 N·m,
Ω_odd ≈ −91 rad/s, mirror-reversed) scales linearly to ≈ −9e−23 N·m and ≈ −30 rad/s at 5° — **inside this
bound**. So the result does not contradict a real 5° chiral torque of that size; it says this design cannot
see one.

The variance has a clear physical source, visible in §7: the signed head populations are
**+7.1e−20 and −7.1e−20 N·m against a net of +1.6e−22** — the net axial torque is a ~0.2 % residue of a
near-cancelling tug-of-war between heads rolling the filament in opposite senses. A ~7e−23 chiral bias is
therefore a part in ~10³ of the gross opposing torques, and it must be extracted from an ensemble whose
imbalance wanders slowly and by two orders of magnitude between arms.

---

## 9c. Superseded reading — do not re-trust

The 100 ms numbers in §6 (τ_odd = −6.99e−23 ± 2.87e−23, "2.43σ, both seeds negative") are **retained as the
record of the first stage and are NOT a result.** They did not survive the authorized extension. Any future
citation must carry the 200 ms adjudication with them. In particular, the n = 2 seed SEM should not be quoted
for this estimator at all — the disjoint-block SEM is the honest one, and it disagreed with the seed SEM at
100 ms in exactly the direction the 200 ms stage then confirmed.

Against the decision rule:

| criterion | status |
|---|---|
| both matched seeds give the expected sign | **yes** — both negative |
| Ω_drive_odd has the corresponding sign | **yes** |
| torque-component and update closure pass | **yes** — §3, 4 PASS / 0 FAIL |
| occupancy usable and reasonably stationary | **yes** |
| mean separated from zero relative to **between-seed and block** variation | **NO** — 1.01σ on disjoint blocks |
| a major time block disagrees | **yes** — Q3, and the entire second half |

Q1 requires separation from zero relative to *both* between-seed *and* block variation. Block variation fails
it. Q2's description — "the mean has the expected sign; one seed or major time block disagrees; uncertainty
overlaps zero substantially" — matches exactly.

Not Q3: the sign is consistent across both seeds and the estimate sits well above the zero-skew/numerical floor
(τ_other is 1e−8 of τ_det, i.e. seven orders below the signal). Not Q4: the filament is rigid, closure passes,
the diagnostics are trajectory-inert, binding and occupancy are healthy, and there were no invalid or solver
failures.

---

## 10. Extension and small-angle decisions

**200 ms extension: required by Q2, and RUN.** Result in §8b.

*Exact continuation is not supported* — this harness has no checkpoint or restart for a device-resident arm
(no serialization on this path), and records are duration-tagged. The extension is therefore a re-run of the
same four arms at 200 ms, not a resumption; because the RNG is counter-based and step-count-independent, the
re-run reproduces the original trajectory exactly and then continues it (§8b). Records land in the
`d00200000` namespace; the 100 ms records are preserved untouched.

Cost note: cumulative simulated production is 1200 ms (400 ms at the 100 ms stage + 800 ms at the 200 ms
stage) because continuation was unavailable. The **final authorized design — 4 arms × 200 ms = 800 ms — is
exactly saturated and not exceeded**; no arm exceeds 200 ms and no fifth arm was run.

**2° and 1°: NOT justified, and NOT run.** The optional small-angle check is gated on 5° reaching Q1 with the
estimator comfortably resolved. 5° reached **Q3**. Quantitatively, at 200 ms the 5° estimator has
SEM ≈ 1.3e−22 N·m against an expected linear-scaled signal of ≈ 9e−23 at 5°, ≈ 3.6e−23 at 2° and ≈ 1.8e−23 at
1° — i.e. ≈ 0.7σ, ≈ 0.3σ and ≈ 0.14σ. Both smaller angles sit far below the floor.

Worth recording for whoever designs the next attempt: **linear and sine-like scaling are not separable here
under any circumstances.** Over 1–5°, `sin(ε)/ε` ranges from 0.99995 to 0.99873 — a 0.13 % difference between
the two candidate laws, against an estimator whose 1σ uncertainty exceeds 100 % of the signal. Distinguishing
them is not a duration problem; it needs a much larger ε lever arm.

---

## 11. Conclusion

At 5° converter skew, the fully thermalized rigid-filament model does **not** produce a chirality-odd axial
motor torque that this design can discern. Four matched arms at 100 ms gave τ_odd = −6.99e−23 ± 2.87e−23 N·m
with both seeds on the native negative sign — suggestive (Q2) on the seed statistic but only 1.01σ on disjoint
blocks — and the authorized extension of the same four arms to 200 ms collapsed it to
−7.06e−24 ± 1.31e−22 N·m (0.05σ) with the seeds disagreeing in sign. The seed SEM *grew* 4.6× when the window
doubled, which identifies the obstacle: the deterministic axial torque is a ~0.2 % residue of a near-cancelling
tug-of-war between oppositely-rolling heads, and its imbalance drifts on a timescale comparable to the whole
measurement window, so each arm contributes about one effective sample and longer averaging buys almost
nothing. The defensible result is a bound, **|τ_odd| ≲ 1.3e−22 N·m ⇒ |Ω_drive_odd| ≲ 40 rad/s**, which
comfortably contains the ≈ 9e−23 N·m that linear scaling from the campaign's 15° measurement would predict —
so this is a statement about the measurement's power, not evidence against a 5° chiral torque. The assay
itself is sound throughout: rigid single body, FDT thermostat, four decomposition gates passing, the bond
moment accounting for the entire deterministic axial torque to ~1e−8, stable occupancy, directed gliding, and
zero invalid or solver failures across all eight arms. Per the Q3 rule, work stops here; no multi-second
campaign and no 2°/1° arms were launched. Two by-products are worth carrying forward independently of the null:
the torque-component gate's recorded diagnosis was wrong and the real defect was a one-step-stale **projection
axis** (§2), and a production-path flag combination silently made the whole decomposition read zero (§2.4) —
found by a sanity check, not by any gate.

### 11.1 If this is pursued further

Not authorized here, and not started. Recorded so the next attempt is not designed against the wrong obstacle:
**more seeds, not longer arms.** The variance is between-arm and slowly-drifting, so n is the lever and
duration is not — 8–12 matched seeds at 100 ms would cost about what these 8 arms already cost and would give
a genuine df, whereas doubling duration demonstrably did not help. A rigid 15° anchor under the identical
rigid pipeline would also establish the estimator's power on a signal known to be ~4× larger before any
further small-angle work is attempted.

---

## Appendix A — Stage 5 disposition

**INCOMPLETE — DEFERRED AS OUTSIDE QUICK 5° TORQUE SCOPE.**

Stage 5 (the passive-null stop gate) was **not required** to answer the narrow driven 5° question, and is
stopped. No Stage-5 work is running.

| item | value |
|---|---|
| command | `-rigid-passive -gpu -eta 0.01 -passive-meas 400000 -passive-seeds 4` |
| commit | `f382505` |
| launched | 2026-07-31 22:26:48Z (pid 3005020) |
| ended | 2026-07-31 23:36:39Z, after **4190.3 s** (1 h 09 m 50 s) |
| design | arms A (no motors, 1 seed), B (passive achiral ε=0, 4 seeds), C± (passive chiral ±15°, 4 seeds), D (thermal off, 1 seed); per arm 20 000 warm-up → freeze → 2 000 relax → 400 000 measured = 422 000 steps |
| progress | reached `executeIndex` **248 228** of the **first** arm's 422 000 steps ⇒ **58.8 %** of one arm |
| simulated duration completed | **62.06 ms** of that arm's 105.5 ms (248 228 × 2.5e−7 s). Note the crash-trace `simulationTime` field is 10× high — it is stamped with `DT`, not the viscosity-scaled `DTR` |
| **arms completed** | **ZERO.** The arm table header printed; no arm row was ever emitted |
| **records written** | **NONE.** Stage 5 wrote no `.tsv`, no partial record, no per-arm output. The only artifacts are the three console logs in `RUN_LOGS/rigid/` |

**No partial arm is treated as a replicate**, because there are none — there is nothing partial to preserve
beyond the logs, which are retained unchanged.

### A.1 How it ended — correcting the file labels

The log filenames (`stage5_passive_KILLED_for_batching.txt`,
`stage5_passive_ABANDONED_slowbuild.txt`) say the runs were stopped deliberately. The logs say more than that:
**every one of these processes died with `SIGSEGV` inside `libcuda.so.1+0x345d6f`, core dumped, exit 134**,
preceded by a cascade of CUDA `709` (`CONTEXT_IS_DESTROYED`) and `400` (`INVALID_HANDLE`) errors. Five
`hs_err_pid*.log` files from 2026-07-31 all carry the same frame, at 1 m 25 s, 3 m 02 s, 10 m 00 s,
1 h 09 m 50 s and 1 h 12 m 18 s elapsed.

**Most likely reading: this is the teardown signature of killing a JVM that is blocked in a native CUDA call,
not a spontaneous GPU fault.** In `stage5_passive.txt` the shutdown hook ran (`SHUTDOWN_HOOK_ENTERED` /
`SHUTDOWN_HOOK_COMPLETED`) while the main thread was inside an unmatched `EXECUTE_CALL_BEGIN`, i.e. the
process was signalled mid-`execute()`; the context was then destroyed under the running call, producing the
709 cascade and the fault. `NORMAL_MAIN_RETURN` is absent from both logs.

Supporting evidence that the GPU itself is healthy: **no Xid and no NVRM fault** in the recorder's kernel log
for the whole session, the machine never froze or rebooted, and every run since — two decomposition
validations, one sanity pair and the production arms — completed cleanly on the same device.

`collect_gpu_crash_case.sh` was therefore **not** invoked: CLAUDE.md scopes it to "after any hard freeze and
reboot", and neither occurred.

**Worth knowing regardless:** stopping a monitored GPU run mid-`execute()` costs a core dump and leaves a
crash signature that reads like a driver fault. It is not one here, but it is indistinguishable at a glance
from the hard-freeze issue this repository monitors for, so the distinction is recorded rather than left to
the filenames.
