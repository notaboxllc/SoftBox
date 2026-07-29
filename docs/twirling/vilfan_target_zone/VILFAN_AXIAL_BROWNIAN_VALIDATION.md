# Vilfan axial Brownian motion — build and validation

**Branch** `feature/vilfan-axial-brownian` (child of `feature/vilfan-overdamped-drag`)
**Worktree** `../softbox-vilfan-axial-brownian`
**Runner** CPU only — no CUDA, no TornadoVM, no `TaskGraph`, no device context. ≤ 3 logical cores, `nice -n 17`.
**Status** **COMPLETE — classification A** (S20). Stage 10 (optional paper-lattice) still running.

---

## S1. Executive conclusion

> ### **A. AXIAL-BROWNIAN VILFAN MECHANISM SURVIVES QUANTITATIVELY**

Adding FDT-consistent axial Brownian motion — and nothing else — to the validated deterministic
finite-drag reference leaves Vilfan's target-zone depletion mechanism intact. Eight matched
native/mirror seed pairs with independent axial noise, native lattice, 5 um analysed travel each:

| quantity | deterministic drag | axial Brownian | ratio |
|---|---|---|---|
| `<x_A>` | +1.6445 nm | **+1.6407 +- 0.021 nm** | **0.998** |
| before-centre | 58.98 % | **58.95 +- 0.12 %** | **0.999** |
| `<theta_A>` | +0.1061 rad | **+0.10756 +- 0.0014 rad** | **1.014** |
| attachment torque `M_A` | -1.757 pN·nm | **-1.7812 +- 0.023 pN·nm** | **1.014** |
| `Omega_odd` | -0.51936 rad/s | **-0.5439 +- 0.0071 rad/s** | **1.047** |
| `lambda^-1` | -1.982 um^-1 | **-2.0489 +- 0.035 um^-1** | **1.034** |
| v | 0.041709 um/s | **0.041831 +- 6.9e-05 um/s** | **1.003** |

`Omega_even = 0.0054 +- 0.0048` rad/s, consistent with zero. Nothing moves by more than 5 %.

**The result is established on the attachment statistics first, not inferred from `Omega`.**

1. **Backtracking is real and was measured.** At one-actin-subunit resolution the filament makes
   **1.22 backward crossings of a zone centre per zone passage**, with a largest single backward
   excursion of **19.63 nm = 54.5 % of the zone period**. The sequence the failure hypothesis
   requires does occur.
2. **It does not disrupt depletion.** The no-depletion shadow control, run on the same realised
   stochastic trajectories, gives
   `Delta_depletion = <x_A>_real - <x_A>_shadow = 1.7088 +- 0.0075 nm`, **resolved at 227 sigma** and
   accounting for **104 %** of the total bias (the residual static asymmetry mildly opposes it). The
   deterministic study measured 96 %; the two agree.
3. **Why it survives.** Axial motion is a **rigid translation**: a thermal excursion shifts every
   motor's zone coordinate equally, so it cannot reorder which motors have already been depleted. It
   only blurs the recorded attachment position, by `sd(X) = 0.316 nm` on a 36 nm period (0.88 %),
   with zero mean. Depletion is established over a bound-head lifetime `1/kD = 0.2 s`, during which
   the filament advances 8.3 nm deterministically against a 0.3 nm thermal sd — signal-to-noise ~28
   in exactly the quantity that matters.
4. **Raw crossing counts are a trap.** There are **2 271 339** raw zone-centre sign changes
   (~1010 per zone passage) which, taken at face value, would suggest total loss of ordered passage.
   They are sub-nanometre jitter. The hysteresis ladder (327 / 69 / **1.22** per passage at
   0.5 / 1.0 / 2.7 nm) separates jitter from physical revisitation.

**Controls** (all under identical axial-Brownian dynamics): `alpha = 0` gives `Omega_odd = 0` exactly
while gliding survives; `d = 0` gives neither gliding nor twirl while axial diffusion continues;
the achiral lattice gives `Omega_odd = 0` exactly; Brownian-OFF through the same infrastructure
reproduces the deterministic control.

**Validation.** 18/18 Stage-4 gates, including OU stationary variance = `kBT/(Nb K)` (Boltzmann) and
autocorrelation `exp(-t/tauX)` at `Nb` = 1…83, free-diffusion variance `2 DX t` over five decades,
exact event continuity, and pathwise mirror antisymmetry under common noise. A methodological note
worth carrying: **ensemble statistics cannot test stochastic convergence here** — refining shifts
event times and decorrelates the trajectory, so `<x_A>` scatters with no trend. The quadrature error
was isolated per substep on a fixed bridge-nested path: it falls only as `~sqrt(dt)` (rough-path
integration, so pointwise convergence is unattainable) but its **signed bias is 0.5-1.8 sigma from
zero at ~1e-5** against a per-substep error of ~2e-3 — random, not biased, and it cannot manufacture
a systematic shift.

**Runner:** CPU only — no CUDA, TornadoVM, `TaskGraph` or device context. 52 arms, 12.6 M chemical
events, 1.27 billion stochastic substeps, 1.57 billion bridge draws, ~15 CPU-hours, <= 3 cores at
`nice -n 17`.

---

## S2. The single restored realism, stated exactly

The deterministic overdamped reference (`VILFAN_OVERDAMPED_DRAG_VALIDATION.md`, classification A)
propagates the filament by
```
gammaX dX/dt = sum_j F_j            gammaTheta dTheta/dt = sum_j M_j
```
This study adds **one** ingredient: an FDT-consistent thermal force on the **axial** coordinate only.
```
gammaX dX/dt = sum_j F_j + sqrt(2 kBT gammaX) xi(t)
   <=>   dX = [sum_j F_j / gammaX] dt + sqrt(2 DX) dW ,    DX = kBT/gammaX
gammaTheta dTheta/dt = sum_j M_j          <-- ROLL UNCHANGED, still deterministic
```
`gammaX` is the same whole-filament axial drag audited by the drag study (`bTGx`, slender-body,
`eta = 0.01 Pa*s`, `5.34401e-05 pN*s/nm`). No random term is added to `Theta` anywhere.

**Everything else is frozen and was verified unchanged**: the four-state irreversible cycle;
continuously attachment-competent detached motors; graded competing-site attachment;
`kA = 50`, `kPS = 1e4`, `k_-ADP = 1e3` s^-1; `kD/kA = 0.1`, `kD = 5 s^-1`, `[ATP] = 1 uM`;
`d = 8 nm`; `K = 0.5 pN/nm`; `alpha = 4`; `kBT = 4.14 pN*nm`; `rho = 20 um^-1`; `l = 5.5 um`;
static Poisson 1-D motor field; single-site occupancy; Vilfan's force and torque laws; whole-filament
axial and roll drag; native SoftBox lattice; zero converter skew; zero SoftBox motor mechanics; zero
strain dependence of any chemical rate; no transverse, height, tilt or bending degree of freedom. The
filament still has exactly two coordinates, `X` and `Theta`.

**Selection.** `Config.axialBrownian` (default **false**). `-mechanics quasistatic` and
`-mechanics overdamped` with Brownian off are untouched; gate F1 shows a Brownian-OFF arm reproduces
the committed deterministic record to its printed precision and draws **zero** stochastic substeps.

---

## S3. Stage 0 — FDT and diffusion-scale audit

`DX = kBT/gammaX = 7.74700e4 nm^2/s = 0.07747 um^2/s`, matching the preregistered expectation.

**Free-filament RMS displacement `sqrt(2 DX t)`** — every value reproduces the brief's independent estimate:

| t | RMS | note |
|---|---|---|
| 1 us | 0.3936 nm | |
| 10 us | 1.245 nm | |
| 100 us | 3.936 nm | |
| 1 ms | 12.45 nm | |
| 646 us | 10.00 nm | mean chemical inter-event interval |
| 0.2 s | 176.0 nm | bound-head lifetime `1/kD` |
| 0.756 s | 342.2 nm | target-zone passage |

**These are FREE-filament values and are NOT the production fluctuation.** While motors are bound the
axial springs confine `X` to an Ornstein-Uhlenbeck process:

| Nb | sd(X) = sqrt(kBT/(Nb K)) | tauX = gammaX/(Nb K) |
|---|---|---|
| 1 | 2.8775 nm | 1.069e-4 s |
| 2 | 2.0347 nm | 5.344e-5 s |
| 5 | 1.2869 nm | 2.138e-5 s |
| 10 | 0.9099 nm | 1.069e-5 s |
| 25 | 0.5755 nm | 4.275e-6 s |
| 50 | 0.4069 nm | 2.138e-6 s |
| **83 (production median)** | **0.3158 nm** | **1.288e-6 s** |
| 74 (production lower decile) | 0.3345 nm | 1.444e-6 s |

### S3.1 The quantity that decides the study

The target-zone period on the native lattice is `L = 36.0 nm`.

- **Free** diffusion over one zone passage would be **342 nm = 9.5 zone periods** — more than enough
  to scramble target-zone passage order completely and destroy the mechanism.
- **Constrained** fluctuation at the production median occupancy is **0.316 nm = 0.88 % of L**.

So survival is decided entirely by **how effectively the bound heads confine X**, i.e. by the
occupancy statistics — not by `DX`. Occupancy is high (duty ratio 0.74, median `Nb = 83` of ~110
motors under the filament) and the binomial spread is narrow, so `Nb` never approaches zero: across
the whole campaign the number of free-diffusion substeps (`Nb = 0`) is reported in S19.

**Preregistered expectation, recorded before the pilot:** with `sd(X)` at 0.9 % of the zone period,
the mechanism should survive with `<x_A>` essentially unchanged, while the *raw* count of
zone-centre crossings should be enormous and physically meaningless — because a 0.3 nm jitter about
a slowly-drifting centre crosses it thousands of times without ever revisiting a materially different
part of the zone. That expectation drove the scale-aware diagnostic design in S7.

---

## S4. Stage 1 — exact stochastic axial dynamics

For a fixed bound set the drift is linear, `sum_j F_j = Nb K (Xeq - X)`, so `X` is an exact OU
process and is propagated with the **exact finite-time transition** — there is no discretisation
error in the marginal law at any step size:
```
X(t+dt) = Xeq + [X(t)-Xeq] exp(-dt/tauX) + sqrt[ kBT/(Nb K) (1 - exp(-2 dt/tauX)) ] Z ,  Z ~ N(0,1)
```
With `Nb = 0` or `K = 0` the drift vanishes and the step is free diffusion,
`X(t+dt) = X(t) + sqrt(2 DX dt) Z`. `Theta` keeps the validated deterministic finite-drag
propagation, including analytic location of `+-pi` branch crossings. `X` and `Theta` are **continuous
through every chemical event**: an event changes the state, `Xeq`, `ThetaEq`, `tauX`, `tauTheta` and
the force and torque, but applies no displacement and redraws no increment (gate E).

---

## S5. Stage 2 — stochastic path / hazard coupling, and Brownian-bridge refinement

Attachment hazards depend on the **realised** path, `kA_i[X(t), Theta(t)]`, so the deterministic
cumulative-hazard solver cannot be reused. The model is integrated as a coupled stochastic-path /
hazard system: draw `H* = -ln r`; step the realised axial OU path and the deterministic roll path
together on an anchor grid; accumulate `dH/dt = k_total[X(t), Theta(t), states]` along **that**
path; stop at `H = H*`; evaluate all legal rates there; select by instantaneous rate fraction;
continue from the same continuous `X` and `Theta`.

The hazard over each substep uses Simpson with the midpoint supplied by the **exact OU bridge**
```
X(T/2) | X(0)=xa, X(T)=xb :   mean = Xeq + a(xa+xb-2Xeq)/(1+a^2) ,  var = varInf (1-a^2)/(1+a^2)
                              a = exp(-T/(2 tauX))
```
so subdividing a step **never redraws independent noise** — the coarse and refined estimates are
integrals of literally the same stochastic path. The terminal event is located inside its substep by
recursive bridge halving (`bridgeMaxLevel = 14`).

### S5.1 Why convergence could not be tested through ensemble statistics — and what was done instead

The obvious test (tighten the step, watch `<x_A>` converge) **does not work here**, and this is a
methodological point worth recording. Refining the step shifts event times; once an event time
shifts the trajectory decorrelates, so each refinement level is effectively an independent sample.
Measured on a short fixture, `<x_A>` scattered 1.464 / 1.698 / 1.514 / 1.445 / 1.664 nm across five
levels **with no trend** — that is sampling noise (SEM ~0.16 nm at ~4000 events), not discretisation
error, and it would be a mistake to read it as non-convergence.

The quadrature error was therefore isolated **per substep on a fixed, bridge-nested path**, where
the sampling noise is absent by construction. Two facts came out:

1. The per-substep error falls only as **~sqrt(dt)** — 2.21e-3, 1.68e-3, 1.02e-3 at
   dt = 5.0 / 2.5 / 1.25 us. That is the expected rate for integrating along a rough path, and it
   means driving the per-substep error to 1e-4 would need dt ~ 1e-8 s: **infeasible, and it is not
   the relevant question.**
2. The **signed bias is consistent with zero**: `+2.01e-5 +- 3.39e-5` (0.59 sigma),
   `+2.94e-5 +- 1.61e-5` (1.83 sigma), `+3.62e-6 +- 6.94e-6` (0.52 sigma), against a per-substep
   |error| of ~2e-3. **The quadrature error is random, not biased.** It averages down over the ~130
   substeps in a chemical interval (to ~2e-4) and cannot manufacture a systematic shift in any
   reported statistic.

Production therefore runs at the base anchor step `dt = 5 us` (`refineLevel = 0`), justified by the
bias measurement rather than by an unattainable pointwise-convergence criterion.

---

## S6. Stage 3 — RNG streams and mirror pairing

Four independent streams: motor-lawn placement, chemical hazard thresholds, event selection
(all `SplittableRandom`, unchanged from the parent studies), and **axial Brownian increments**
(new, counter-based). The axial stream is addressed by `(seed, stream, index)` through a splitmix64
hash rather than by sequence position, which is what makes bridge refinement possible without
disturbing the anchor draws.

Two pairing modes, **reported separately and never pooled**:

- **common axial noise (`_cn`)** — native and mirror share the seed and therefore the identical
  axial Wiener path. Axial noise is mirror-even, so antisymmetry must hold **pathwise**. This is a
  correctness gate (and a legitimate common-random-number variance reducer), **not** an independent
  sample.
- **independent axial noise (`_in`)** — the mirror arm's seed is offset by 500 000, so the two axial
  paths are independent. **Every inferential result in this report uses this mode.**

---

## S7. Stage 5 — target-zone crossing diagnostics, and why a raw count is misleading

A naive count of zone-centre sign changes is dominated by sub-nanometre thermal jitter about a
slowly drifting centre and is physically meaningless. The pilot makes the point quantitatively:
**592 556 raw sign changes, i.e. ~1040 per zone passage**, none of which corresponds to revisiting a
materially different part of the zone.

The diagnostics are therefore **scale-aware**: a crossing is confirmed only after the filament has
moved half a hysteresis band beyond the centre. Bands of 0 (raw), 0.5, 1.0, **2.7 (one actin
subunit)** and 5.0 nm are recorded, alongside the maximum backward excursion, net forward
displacement and sampled path length.

**Path length is resolution-dependent** — an OU path has unbounded variation, so the measured length
grows without limit as the substep shrinks (pilot: 21 003 514 nm sampled against 10 034 nm net, a
ratio of 2093). It is quoted at the production substep and must never be compared across step sizes.
Net forward displacement and the hysteretic counts are the resolution-independent quantities.

---

## S8. Stage 4 — numerical, thermodynamic and regression gates

`./scripts/run_vilfan_brownian.sh -brown-gates` — **18 PASS, 0 FAIL.**

| gate | result |
|---|---|
| A1 free diffusion variance = `2 DX t` | PASS — var/expected 1.0002 at dt = 1e-6 … 1e-2 (five decades) |
| A2 mean displacement = 0 | PASS |
| A3 increments uncorrelated | PASS — r = −1.36e−03 (n = 400 000) |
| B1 OU stationary variance = `kBT/(Nb K)` (= Boltzmann) | PASS — ratio 1.001 / 0.999 / 1.001 / 1.009 at Nb = 1, 5, 25, 83 |
| B2 mean = `Xeq` | PASS |
| B3 autocorrelation = `exp(-t/tauX)` | PASS — ratio 1.001 / 1.001 / 0.998 / 1.002 |
| D1 hazard quadrature **unbiased** | PASS — 0.59 sigma from zero at the production step (S5.1) |
| E1 `X` continuous at every event | PASS — max |dX| = 0 over 9606 events |
| E2 `Theta` continuous at every event | PASS — max |dTheta| = 0 |
| E3 roll dynamic closure still holds | PASS — 2.65e−12 pN·nm |
| F1 Brownian-OFF reproduces the committed drag record | PASS — rel dev v 2.8e−11, omega 9.0e−11, `<x_A>` 2.6e−10 |
| F2 Brownian-OFF draws no axial noise | PASS — 0 stochastic substeps |
| G1 common-noise mirror: `X` paths identical | PASS — dX = 0 |
| G2 common-noise mirror: `Theta` exact negatives | PASS — ∓5.101111363 |
| G3 event counts and `<x_A>` identical | PASS — 13874 vs 13874, `<x_A>` 1.724902 vs 1.724902 |
| G4 `<theta_A>` reversed, `Omega_even` numerical zero | PASS — ±0.109740, `Omega_even` = 0 exactly |
| H1 same seed bit-reproducible | PASS |
| H2 noise addressed, not sequenced | PASS — counter-based |

Gates B1 and B3 together are the thermodynamic statement: the axial degree of freedom sits at the
Boltzmann distribution of its own potential with the correct relaxation time, at occupancies from a
single bound head to the production median.

---

## S9. Stage 6 — two-seed pilot

Native lattice, `alpha = 4`, `kD/kA = 0.1`, `eta = 0.01 Pa*s`, axial Brownian ON, roll Brownian OFF.

**Stopping rule.** A travel-threshold window is biased when the filament can move backward — it stops
preferentially on a forward fluctuation. The window is therefore **fixed time, preregistered**:
24 s warm-up (1.0 um of deterministic travel) then **120 s analysed** (5.0 um of deterministic
travel). Net forward displacement is *recorded*, not used as the stopping rule. The pilot confirms
the rule is unbiased in practice: net displacement came out 4968–5028 nm, i.e. within 0.6 % of the
5000 nm target, and the maximum backward excursion (19.6 nm) is 0.4 % of it.

### Stage 6 — pilot, independent axial noise

| arm | v (µm/s) | ω (rad/s) | ⟨x_A⟩ nm | before % | ⟨θ_A⟩ rad | M_A pN·nm | net (nm) | max back (nm) | Nb | events |
|---|---|---|---|---|---|---|---|---|---|---|
| `pilot_in_s101_mirror` | 0.0414 | 0.5399 | 1.696 | 59.21 | -0.1068 | 1.768 | 4968.3 | 11.6 | 83 | 235451 |
| `pilot_in_s101_native` | 0.04171 | -0.484 | 1.582 | 58.66 | 0.1015 | -1.681 | 5005.5 | 6.2 | 74 | 214971 |
| `pilot_in_s102_mirror` | 0.04145 | 0.5204 | 1.52 | 58.24 | -0.1008 | 1.669 | 4974.4 | 6.12 | 85 | 244792 |
| `pilot_in_s102_native` | 0.0419 | -0.5604 | 1.693 | 59.12 | 0.1089 | -1.803 | 5028.1 | 19.6 | 83 | 232305 |

### Stage 6 — pilot, COMMON axial noise (correctness control, not an independent sample)

| arm | v (µm/s) | ω (rad/s) | ⟨x_A⟩ nm | before % | ⟨θ_A⟩ rad | M_A pN·nm | net (nm) | max back (nm) | Nb | events |
|---|---|---|---|---|---|---|---|---|---|---|
| `pilot_cn_s101_mirror` | 0.04171 | 0.484 | 1.582 | 58.66 | -0.1015 | 1.681 | 5005.5 | 6.2 | 74 | 214971 |
| `pilot_cn_s101_native` | 0.04171 | -0.484 | 1.582 | 58.66 | 0.1015 | -1.681 | 5005.5 | 6.2 | 74 | 214971 |
| `pilot_cn_s102_mirror` | 0.0419 | 0.5604 | 1.693 | 59.12 | -0.1089 | 1.803 | 5028.1 | 19.6 | 83 | 232305 |
| `pilot_cn_s102_native` | 0.0419 | -0.5604 | 1.693 | 59.12 | 0.1089 | -1.803 | 5028.1 | 19.6 | 83 | 232305 |

The common-noise pair is **pathwise identical**: same 214 971 events, `<x_A>` identical to all
printed digits, `omega` exactly `-+0.48403`, `<theta_A>` exactly `-+0.10150`. Axial noise is
mirror-even, so mirroring maps the system onto itself with `X -> X`, `Theta -> -Theta` even in the
presence of the thermal force. These arms are a **correctness gate only** and are excluded from every
inferential statistic.

---

## S10. Stage 7 — pilot variance and campaign sizing

### Stage 7 — pilot variance and campaign sizing

| quantity | pilot mean | between-seed sd | SEM | n |
|---|---|---|---|---|
| ⟨x_A⟩ (nm) | 1.6372 | 0.0787 | 0.0556 | 2 |
| before-centre (%) | 58.89 | 0.32 | 0.226 | 2 |
| ⟨θ_A⟩ (rad) | 0.10518 | 0.00522 | 0.00369 | 2 |
| Ω_odd (rad/s) | -0.52616 | 0.0201 | 0.0142 | 2 |

- between-seed sd(⟨x_A⟩) = **0.0787 nm**. To detect the H1/H2 boundary (a 25 % change = 0.411 nm) at two-sided 95 % / 80 % power needs **n ≈ 1 seeds per arm**.
    - a 10 % change (0.164 nm) needs n ≈ 4
    - a 25 % change (0.411 nm) needs n ≈ 1

The between-seed spread is small because each arm averages ~48 000 attachment events over 5 um of
travel. **n = 8 matched seed pairs** was preregistered on this basis — comfortably above the n = 4
needed to resolve a 10 % change and far above the n = 1 needed for the H1/H2 boundary. The
production campaign was then run exactly as sized; no seed was added or dropped after seeing results.

---

## S11. Stage 8 — production campaign

Eight matched native/mirror seed pairs, independent axial noise, plus eight no-depletion shadow arms
on the same stochastic trajectories.

### Stage 8 — production, independent axial noise

| arm | v (µm/s) | ω (rad/s) | ⟨x_A⟩ nm | before % | ⟨θ_A⟩ rad | M_A pN·nm | net (nm) | max back (nm) | Nb | events |
|---|---|---|---|---|---|---|---|---|---|---|
| `prod_in_s101_mirror` | 0.0414 | 0.5399 | 1.696 | 59.21 | -0.1068 | 1.768 | 4968.3 | 11.6 | 83 | 235451 |
| `prod_in_s101_native` | 0.04171 | -0.484 | 1.582 | 58.66 | 0.1015 | -1.681 | 5005.5 | 6.2 | 74 | 214971 |
| `prod_in_s102_mirror` | 0.04145 | 0.5204 | 1.52 | 58.24 | -0.1008 | 1.669 | 4974.4 | 6.12 | 85 | 244792 |
| `prod_in_s102_native` | 0.0419 | -0.5604 | 1.693 | 59.12 | 0.1089 | -1.803 | 5028.1 | 19.6 | 83 | 232305 |
| `prod_in_s103_mirror` | 0.04184 | 0.5619 | 1.641 | 59.14 | -0.1106 | 1.831 | 5021.1 | 8.45 | 90 | 255749 |
| `prod_in_s103_native` | 0.04173 | -0.5504 | 1.699 | 59.21 | 0.1123 | -1.859 | 5008.1 | 7.42 | 81 | 232877 |
| `prod_in_s104_mirror` | 0.04151 | 0.5732 | 1.722 | 59.3 | -0.1141 | 1.889 | 4981.7 | 13.3 | 65 | 193131 |
| `prod_in_s104_native` | 0.04147 | -0.5479 | 1.596 | 58.78 | 0.1059 | -1.754 | 4976.3 | 14.2 | 89 | 256820 |
| `prod_in_s105_mirror` | 0.04196 | 0.5728 | 1.729 | 59.52 | -0.1141 | 1.889 | 5034.9 | 13.3 | 82 | 235408 |
| `prod_in_s105_native` | 0.04211 | -0.5529 | 1.689 | 59.1 | 0.1108 | -1.835 | 5053.4 | 9.94 | 84 | 239258 |
| `prod_in_s106_mirror` | 0.04157 | 0.5538 | 1.575 | 58.72 | -0.1081 | 1.79 | 4988.4 | 8.06 | 75 | 217495 |
| `prod_in_s106_native` | 0.04195 | -0.5412 | 1.571 | 58.53 | 0.105 | -1.739 | 5034.6 | 7.07 | 84 | 241540 |
| `prod_in_s107_mirror` | 0.04155 | 0.516 | 1.516 | 58.09 | -0.1029 | 1.704 | 4985.8 | 6.22 | 74 | 215627 |
| `prod_in_s107_native` | 0.04183 | -0.5144 | 1.591 | 58.69 | 0.1045 | -1.731 | 5019.4 | 6.26 | 85 | 240055 |
| `prod_in_s108_mirror` | 0.04146 | 0.5563 | 1.743 | 59.4 | -0.1145 | 1.897 | 4974.9 | 7.74 | 75 | 208022 |
| `prod_in_s108_native` | 0.04194 | -0.5569 | 1.705 | 59.49 | 0.1116 | -1.848 | 5032.7 | 8.19 | 80 | 227495 |

---

## S12. Deterministic drag versus axial Brownian

### Stage 8 — deterministic drag vs axial Brownian (production)

| quantity | deterministic drag | axial Brownian | ratio | difference |
|---|---|---|---|---|
| ⟨x_A⟩ (nm) | 1.6445 ± 0.029 | 1.6407 ± 0.021 | **0.9977** | -0.00377 |
| before-centre (%) | 58.98 ± 0.13 | 58.95 ± 0.12 | **0.9994** | -0.0363 |
| ⟨θ_A⟩ (rad) | 0.1061 ± 0.003 | 0.10756 ± 0.0014 | **1.014** | 0.00146 |
| attachment torque M_A (pN·nm) | -1.757 ± 0.05 | -1.7812 ± 0.023 | **1.014** | -0.0241 |
| ⟨ξ_A⟩ (nm) | 0.33868 ± 0.0088 | 0.33797 ± 0.0074 | **0.9979** | -0.000716 |
| v (µm/s) | 0.041709 ± 0.00013 | 0.041831 ± 6.9e-05 | **1.003** | 0.000122 |
| inverse pitch λ⁻¹ (µm⁻¹) | -1.982 ± 0.073 | -2.0489 ± 0.035 | **1.034** | -0.0668 |
| duty ratio | 0.7421 ± 0.029 | 0.7478 ± 0.014 | **1.008** | 0.00567 |
| median bound heads | 81.75 ± 3.1 | 82.5 ± 1.5 | **1.009** | 0.75 |
| **Ω_odd (rad/s)** | **-0.51936 ± 0.019** | **-0.5439 ± 0.0071** | **1.047** | -0.0245 |
| Ω_even (rad/s) | -0 | 0.00538 ± 0.0048 | — | — |

**Every channel is within 5 % of the deterministic reference**, and `Omega_even = 0.0054 +- 0.0048`
rad/s is consistent with zero (1.1 sigma) as it must be for independent-noise pairs. The cleanest
single comparison is against the Brownian-OFF control run through the **same** fixed-time window and
the **same** infrastructure (S15): `Omega_odd = -0.5439 +- 0.0071` with noise versus
`-0.526 +- 0.011` without — a ratio of **1.034**, i.e. no attenuation at all.

---

## S13. Target-zone crossing and recrossing — the proposed failure mode, tested directly

### Stage 5 — recrossing diagnostics (production)

| hysteresis band (nm) | forward crossings | backward crossings | per zone passage |
|---|---|---|---|
| 0  <- raw jitter, not physical | 1127364 | 1127364 | 1.01e+03 |
| 0.5 | 364397 | 364397 | 327 |
| 1 | 77046 | 77046 | 69.1 |
| 2.7  <- one actin subunit | 1359 | 1360 | 1.22 |
| 5 | 1346 | 1348 | 1.21 |

- raw zone-centre sign changes: **2,271,339** (jitter-dominated — see the band-0 row)
- net forward travel 40,158 nm over 1115 zone passages; max backward excursion **19.63 nm** = 54.52 % of the zone period
- sampled axial path length 82,121,118 nm vs net 40,158 nm (ratio 2045.0). Path length is RESOLUTION-DEPENDENT (an OU path has unbounded variation); it is quoted at the production substep and must not be compared across step sizes.

**This is the substantive mechanistic result, and it needs stating carefully in both directions.**

*Backtracking is real.* At one-actin-subunit resolution the filament makes **1.22 backward
crossings of a zone centre per zone passage**, and the largest single backward excursion in the whole
campaign was **19.63 nm = 54.5 % of the zone period**. The filament genuinely does advance through a
target zone, reverse, and re-cross the centre — the sequence the failure hypothesis requires.

*It does not disrupt depletion.* Despite that, `<x_A>` is 0.998 of its deterministic value and the
before-centre fraction is 58.95 % against 58.98 %.

The reason is visible in the audit (S3.1) and is worth making explicit. Axial motion is a **rigid
translation of the whole filament**: a thermal excursion shifts *every* motor's zone coordinate by
the same amount, so it cannot reorder which motors have already been depleted — it only blurs the
coordinate at which each attachment is recorded, by `sd(X) = 0.316 nm` on a 36 nm period (0.88 %),
and that blur is zero-mean. Depletion is established over a bound-head lifetime `1/kD = 0.2 s`,
during which the filament advances 8.3 nm deterministically against a 0.3 nm thermal standard
deviation — a signal-to-noise of ~28 in exactly the quantity the mechanism depends on.

**The raw crossing count is a trap.** 2 271 339 raw zone-centre sign changes — ~1010 per zone
passage — would, taken at face value, suggest catastrophic loss of ordered passage. They are
sub-nanometre jitter about a slowly drifting centre. The hysteresis ladder (327 / 69 / 1.22 per
passage at 0.5 / 1.0 / 2.7 nm) separates jitter from physical revisitation, and only the
subunit-scale number is mechanistically meaningful.

---

## S14. Real versus no-depletion attachment distributions

### Real vs no-depletion shadow attachment bias (the load-bearing control)

| seed | ⟨x_A⟩ real (nm) | ⟨x_A⟩ shadow (nm) | Δ_depletion (nm) | before real % | before shadow % |
|---|---|---|---|---|---|
| 101 | 1.5815 | -0.1218 | 1.7033 | 58.66 | 49.08 |
| 102 | 1.6928 | -0.045997 | 1.7388 | 59.12 | 49.7 |
| 103 | 1.6987 | -0.027419 | 1.7261 | 59.21 | 49.79 |
| 104 | 1.5963 | -0.087727 | 1.684 | 58.78 | 49.34 |
| 105 | 1.6892 | -0.026614 | 1.7158 | 59.1 | 49.83 |
| 106 | 1.571 | -0.11676 | 1.6878 | 58.53 | 49.11 |
| 107 | 1.5909 | -0.096118 | 1.6871 | 58.69 | 49.3 |
| 108 | 1.7054 | -0.022305 | 1.7277 | 59.49 | 49.83 |
| **mean (n=8)** | **1.6407 ± 0.021** | **-0.068092 ± 0.015** | **1.7088 ± 0.0075** | | |

**Δ_depletion = 1.7088 ± 0.0075 nm — resolved at 227.2σ.**

**The depletion signal is untouched by axial Brownian motion.** Holding motor availability
continuous — the same realised stochastic trajectory, no motor removed from the pool, no physical
force — collapses `<x_A>` from `+1.6407 +- 0.021` nm to `-0.0681 +- 0.015` nm and the before/after
split from **58.95 %** to **49.5 %**. The depletion-attributable bias is

```
Delta_depletion = <x_A>_real - <x_A>_shadow = 1.7088 +- 0.0075 nm     (227 sigma)
```

which is **104 % of the total real bias** (the residual static landscape asymmetry is slightly
negative and mildly *opposes* the effect, exactly as in the deterministic reference). The
deterministic study measured the same quantity as 96 % of the bias; the two agree.

This is the answer to the study's primary question, and it is answered on the attachment statistics
directly rather than inferred from `Omega`.

---

## S15. Stage 9 — controls, all under identical axial-Brownian dynamics

### Stage 9 — controls, all under identical axial-Brownian dynamics

| control | v (µm/s) | Ω_odd (rad/s) | Ω_even | ⟨x_A⟩ nm | before % | verdict |
|---|---|---|---|---|---|---|
| full mechanism | 0.04183 | -0.5439 ± 0.0071 | 0.00538 | 1.641 | 58.95 | twirls, mirror-reversing |
| α = 0 | 0.04016 | 0 ± 0 | 0 | 0.04983 | 50.18 | no angular energy ⇒ no twirl |
| d = 0 | -1.478e-05 | -0.0001283 ± 0.00084 | -0.00275 | -0.0367 | 50.85 | no stroke ⇒ no gliding, no twirl |
| achiral lattice (ϑ₀ = 0) | 0.04016 | 0 ± 0 | 0 | 0 | 100 | no chirality ⇒ no mirror-odd rotation |
| Brownian OFF | 0.04173 | -0.526 ± 0.011 | 0.0059 | 1.645 | 58.98 | deterministic positive control |

- **`alpha = 0`** removes the angular energy: axial Brownian translation and gliding both survive
  (`v = 0.0402 um/s`), while `Omega_odd = 0` **exactly** and the attachment bias collapses
  (`<x_A> = 0.050 nm`, before-centre 50.18 %). With `K_theta = 0` there are no target zones at all,
  so the chain is cut at its first link.
- **`d = 0`** removes the power stroke: `v = -1.5e-05 um/s` (zero within the estimator),
  `Omega_odd = -0.00013 +- 0.00084` (zero), `<x_A> = -0.037 nm`, before-centre 50.85 %. The filament
  still diffuses axially — that is the point of the control — but with no directional zone passage
  there is no depletion bias and no twirl. Run on a matched fixed simulated time, not net travel,
  since a diffusing filament has no travel target.
- **achiral lattice (`theta0 = 0`)** gives `Omega_odd = 0` exactly. *Diagnostic caveat:* with
  `theta0 = 0` the effective groove slope is zero, so the zone period is undefined and the reported
  `<x_A> = 0` / before-centre = 100 % are degenerate artefacts of a coordinate that does not exist in
  this control, **not** physical measurements. The meaningful output of this arm is `Omega_odd = 0`.
- **Brownian OFF** through the same infrastructure and the same fixed-time window reproduces the
  deterministic positive control (`<x_A> = 1.645`, before-centre 58.98 %, `Omega_odd = -0.526`),
  confirming that the comparison in S12 is not an artefact of the changed stopping rule.

---

## S16. Mirror even/odd decomposition

| pairing | `Omega_odd` (rad/s) | `Omega_even` (rad/s) | role |
|---|---|---|---|
| common axial noise | `-0.48403` (pathwise) | **0 exactly** | correctness gate (S8 G1-G4); NOT an independent sample |
| independent axial noise, n = 8 | **`-0.5439 +- 0.0071`** | `0.0054 +- 0.0048` (1.1 sigma) | **the inferential result** |
| deterministic drag, n = 4 | `-0.51936 +- 0.019` | 0 exactly | reference |

Under common noise, mirror antisymmetry remains **exact** even with the thermal force, because axial
noise is mirror-even and the hazard depends on the angular mismatch only through `theta^2`. Under
independent noise `Omega_even` becomes a statistical residual and is consistent with zero, as
required. The two are reported separately and never pooled.

---

## S17. Full causal-chain observables

| link | deterministic drag | axial Brownian | ratio |
|---|---|---|---|
| ordered target-zone passage | no backtracking | **1.22 subunit-scale backward crossings per zone passage**; max excursion 54.5 % of L | — |
| -> before-centre depletion bias | `<x_A>` +1.6445 nm / 58.98 % | **+1.6407 nm / 58.95 %** | 0.998 / 0.999 |
| -> depletion is the CAUSE | `Delta_depletion` 96 % of bias | **1.7088 +- 0.0075 nm, 104 % of bias, 227 sigma** | — |
| -> signed angular bias | `<theta_A>` +0.1061 rad | **+0.10756 rad** | 1.014 |
| -> conjugate Vilfan torque | `M_A` -1.757 pN·nm | **-1.7812 pN·nm** | 1.014 |
| -> mirror-reversing twirl | `Omega_odd` -0.51936 rad/s | **-0.5439 rad/s** | 1.047 |
| (transport) | v 0.041709 um/s | **0.041831 um/s** | 1.003 |
| (chirality per distance) | `lambda^-1` -1.982 um^-1 | **-2.0489 um^-1** | 1.034 |
| necessity | — | `alpha = 0`, `d = 0`, achiral all remove the twirl | — |

---

## S18. Numerical and regression health

### Numerical health

- arms **52**, events **12,627,053**, stochastic substeps **1,272,624,555**, bridge draws **1,573,921,103**
- max event discontinuity |ΔX| = **0 nm**, |ΔΘ| = **0 rad**
- max roll closure residual |γ_Θ Θ̇ − ΣM| = **1.3e-10 pN·nm**
- free-diffusion substeps (Nb = 0): **2,199**; zero-bound equilibrations 2,207
- angular branch crossings: **0**
- travel-cap hits: 0

- **12.6 million chemical events, 1.27 billion stochastic substeps, 1.57 billion Brownian-bridge
  draws.**
- `X` and `Theta` are continuous through **every** event, exactly (`|dX| = |dTheta| = 0`).
- Roll dynamic closure `|gamma_Theta Theta_dot - sum M|` stays below **1.3e-10 pN·nm**, so adding
  axial noise did not perturb the deterministic roll channel.
- **2 199 free-diffusion substeps** (`Nb = 0`) out of 1.27e9 — a fraction of 1.7e-6. The filament is
  essentially never unconfined, which is the quantitative content of S3.1.
- **Zero angular branch crossings**, as in the deterministic study: `Theta` still moves only
  ~0.0012 rad per event and axial noise does not couple to it. The branch machinery remains
  validated-but-unexercised in production (flagged again for the roll-Brownian study, which *will*
  exercise it).
- No travel-cap hits; no arm terminated on a cap.
- **Regression**: Brownian-OFF reproduces the committed deterministic drag record to its printed
  precision and draws zero stochastic substeps (gates F1/F2); the deterministic drag gates and the
  complete-reference gates are untouched by this branch, which adds two new files and additive
  changes only.
- **Runner**: CPU only — no CUDA, TornadoVM, `TaskGraph` or device context. Single-threaded JVMs at
  `nice -n 17`, at most 3 concurrent. 52 arms, ~15 CPU-hours.

---

## S19. Stage 10 — optional paper-lattice confirmation

**Status: launched, not yet complete at the time of writing.** This stage is explicitly optional and
conditional ("run only after the native-lattice result is classified"; "do not expand into a second
full campaign unless the two lattices differ materially"). The native-lattice result is complete and
decisive, and **the classification below does not depend on this stage**.

The relevant prior is that the deterministic drag study found the two lattices behave identically
because `<x_A>/L` is invariant; there is no mechanism by which a rigid axial displacement of
`0.88 % of L` would break that invariance on one lattice and not the other. Resume with:

```
./scripts/run_vilfan_brownian_campaign.sh paper 3
```

---

## S20. Classification

> ## **A. AXIAL-BROWNIAN VILFAN MECHANISM SURVIVES QUANTITATIVELY**

| requirement | result |
|---|---|
| target-zone recrossing measured but does not materially alter depletion | **yes** — 1.22 subunit-scale backward crossings per zone passage, max excursion 54.5 % of L, yet `<x_A>` ratio 0.998 |
| `<x_A>`, `<theta_A>`, `Omega_odd` retain expected signs | **yes** — +1.6407 nm, +0.10756 rad, -0.5439 rad/s |
| `<x_A>` and `Omega_odd` within 25 % of the deterministic values | **yes, with large margin** — 0.998 and 1.047 |
| real-minus-shadow bias clearly resolved | **yes** — 1.7088 +- 0.0075 nm at **227 sigma** |

Not B (nothing falls by more than 5 %, let alone 25 %); not C (neither the bias nor the twirl is
reduced); not D (the real and shadow distributions are 227 sigma apart); not E (no downstream channel
failed); not F (the quadrature is unbiased, the stopping rule is unbiased by construction, and the
campaign completed as preregistered).

**Answer to the scientific question.** Thermally driven axial backtracking and target-zone
revisitation **do not** erase the history-dependent depletion bias. They occur — genuinely, at
subunit scale, roughly once per zone passage — but they are *rigid-body* excursions of amplitude
0.88 % of the zone period against a deterministic advance of 8.3 nm per bound-head lifetime, and a
rigid shift cannot reorder which motors have already been consumed. Depletion is a property of the
*ordering* of motor availability, and axial noise preserves ordering while merely blurring position.

---

## S21. Recommendation for the roll-Brownian study — NOT executed here

**This task stops here.** No roll, transverse or motor Brownian motion was added; no SoftBox
chemistry, explicit S2, strain-dependent detachment, converter skew, tilt, height or GPU work.

**Roll Brownian motion is a categorically harder test than axial, and the numbers say why.**

1. **Roll noise is not mirror-even.** Axial noise survived partly because it commutes with the
   mirror operation, keeping `Omega_even` exactly zero under common noise. A random torque on
   `Theta` is mirror-**odd**: it injects directly into the very channel the measurement lives in.
   The common-noise correctness gate used throughout this study **will no longer hold pathwise** and
   must be replaced; `Omega_even` becomes a genuine variance estimate rather than a zero check.
2. **Roll noise is not a rigid shift in the mechanism's coordinate.** Axial displacement moves all
   motors' zone coordinates together; a roll fluctuation changes every bound head's angular mismatch
   `theta_j` directly, which is the quantity that carries the signal.
3. **Size it before running it.** From this study's constants: `gamma_Theta = 8.47e-03 pN·nm·s/rad`
   gives a free rotational diffusion `D_Theta = kBT/gamma_Theta ~ 489 rad^2/s`, i.e. `~14 rad`
   (>2 full turns) of free roll per bound-head lifetime against a deterministic signal of
   `omega/kD ~ 0.11 rad`. The mechanism can only survive if the bound heads' angular springs confine
   it: the constrained roll width is `sqrt(kBT/(Nb K_theta)) ~ 0.054 rad` at `Nb = 83`, versus
   `<theta_A> = 0.108 rad` — **a signal-to-noise of only ~2 in the constrained regime, against ~28
   for the axial case.** That single comparison is the reason to expect roll to be the rung that
   bites, and it should be computed and stated before any campaign is designed.
4. **Reuse what worked.** The exact-OU propagator, the counter-based addressed RNG, the
   Brownian-bridge refinement and the per-substep *unbiasedness* gate (S5.1) all transfer directly to
   the roll coordinate — but note that `Theta`'s torque law is a **sawtooth** (`wrapPi`), so the OU
   propagator is exact only between branch crossings, and roll noise will make branch crossings
   common where this study saw **zero**. The branch machinery is already implemented and validated
   against RK4; it will finally be exercised.
5. **Keep the scale-aware diagnostics.** The raw-versus-hysteretic lesson of S13 applies with more
   force to an angular coordinate, where a naive wrap-crossing count will be even more misleading.

**Suggested order**: (i) roll Brownian alone at FDT with `gamma_Theta`; (ii) measure `sd(Omega_odd)`
on two seeds and size the campaign from it *before* committing; (iii) only then combine axial and
roll; (iv) leave transverse motion, tilt and SoftBox mechanics to later rungs.

---

## S22. Reproducing this study

```
./scripts/run_vilfan_brownian.sh -fdt-audit         # Stage 0 FDT and diffusion-scale audit
./scripts/run_vilfan_brownian.sh -brown-gates       # Stage 4: 18/18 gates
./scripts/run_vilfan_brownian_campaign.sh pilot 3   # Stage 6 two-seed pilot (8 arms)
./scripts/run_vilfan_brownian_campaign.sh campaign 3  # Stage 8 production (24 arms)
./scripts/run_vilfan_brownian_campaign.sh controls 3  # Stage 9 controls (16 arms)
./scripts/run_vilfan_brownian_campaign.sh paper 3     # Stage 10 optional (4 arms)
python3 scripts/analyse_vilfan_brownian.py all
```

Records: `RUN_LOGS/vilfan_brownian/*.json`, one atomic record per arm (written `.tmp` then renamed,
skipped if present, so an interrupted campaign resumes exactly where it stopped). Derived tables:
`ANALYSIS/vilfan_brownian/tables.md`. `DRAG_RECORDS/` and `REFERENCE_RECORDS/` hold the parent
studies' records for comparison and are not modified. `RUN_LOGS/` is gitignored by repo convention;
every arm is deterministic in its declared seed (gate H1), so the records regenerate exactly.
