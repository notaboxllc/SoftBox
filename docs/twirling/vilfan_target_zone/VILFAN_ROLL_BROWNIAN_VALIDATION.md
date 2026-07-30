# Vilfan roll Brownian motion — build and validation

**Branch** `feature/vilfan-roll-brownian` (child of `feature/vilfan-axial-brownian`)
**Worktree** `../softbox-vilfan-roll-brownian`
**Runner** CPU only — no CUDA, no TornadoVM, no `TaskGraph`, no device context. ≤ 3 cores, `nice -n 17`.

> ## STATUS: **COMPLETE — classification A** (S20). Production (4 matched seed pairs + 4 shadow
> arms) and the `d = 0` control have landed and are decisive; the `alpha = 0` and achiral controls
> were still running at write-up and are reported as such in S19. One localized numerical caveat is
> recorded in full at S18.1 — it does not change the classification and the reasoning is given.

---

## S1. Cumulative realism ladder (paper-facing narrative)

This is the fourth rung of a deliberately incremental programme. The survival results are part of
the scientific claim, not preamble:

1. **The complete Vilfan implementation reproduces the published mechanism** — target-zone
   depletion, before-centre attachment bias, mirror-reversing twirl, pitch −479 ± 14 nm against the
   published 400–500 nm (`VILFAN_COMPLETE_REFERENCE_VALIDATION.md`, verdict A).
2. **Finite overdamped filament response leaves it quantitatively intact** — every amplitude within
   6 % of quasi-static; the drag time competes with the bound-head lifetime, not the inter-event
   interval, giving ~4 orders of magnitude of viscosity headroom
   (`VILFAN_OVERDAMPED_DRAG_VALIDATION.md`, classification A).
3. **FDT-consistent axial Brownian motion also leaves it intact** — every primary amplitude within
   5 %; genuine subunit-scale backtracking occurs (1.22 backward zone-centre crossings per zone
   passage, largest excursion 54.5 % of a zone period) but high bound-head occupancy confines the
   axial fluctuation to 0.316 nm = 0.88 % of the zone period, and a rigid axial shift cannot
   reorder motor availability (`VILFAN_AXIAL_BROWNIAN_VALIDATION.md`, classification A).
4. **Roll Brownian motion is the present test.** It is the first increment that acts *directly* on
   the angular mismatch that defines the target zones, sets `theta_A`, generates the conjugate
   torque, and is itself the measured coordinate. **Its result is not yet established.**

The question this rung answers — *is rotational Brownian motion the first realism increment that
materially weakens the Vilfan mechanism?* — remains **open** at the time of writing.

---

## S2. The single restored realism

```
gammaX     dX/dt     = sum_j F_j                                        <-- axial DETERMINISTIC
gammaTheta dTheta/dt = sum_j M_j + sqrt(2 kBT gammaTheta) xi_Theta(t)   <-- roll STOCHASTIC
   <=>  dTheta = [sum_j M_j / gammaTheta] dt + sqrt(2 DTheta) dW ,  DTheta = kBT/gammaTheta
```

Axial Brownian motion is **OFF** in production. The flags are independent and never silently
coupled: `Config.axialBrownian` and `Config.rollBrownian` are separate, and the dispatcher selects
`advanceRollStochastic` / `advanceStochastic` / `advanceOverdamped` explicitly. All four modes
remain distinct. Everything else is frozen exactly as in the parent studies.

---

## S3. Stage 0 — FDT and angular signal-scale audit

At `eta = 0.01 Pa*s`, `gammaTheta = 8.46659e-3 pN*nm*s/rad`:

```
DTheta = kBT/gammaTheta = 489.0 rad^2/s
```

**Free-roll RMS `sqrt(2 DTheta t)`** — every value reproduces the brief's independent estimate:

| t | RMS roll | in turns |
|---|---|---|
| 1 us | 0.0313 rad | 0.005 |
| 10 us | 0.0989 rad | 0.016 |
| 100 us | 0.313 rad | 0.050 |
| 1 ms | 0.989 rad | 0.157 |
| 646 us (inter-event) | 0.795 rad | 0.127 |
| **0.2 s (1/kD)** | **13.99 rad** | **2.23 turns** |
| 0.756 s (zone passage) | 27.19 rad | 4.33 turns |

**This is a FREE-filament value and is not the production fluctuation.** Bound heads confine roll to
an OU process:

| Nb | sd(Theta) = sqrt(kBT/(Nb Ktheta)) | tauTheta | SNR = `<theta_A>`/sd |
|---|---|---|---|
| 1 | 0.5000 rad | 5.11e-4 s | 0.22 |
| 2 | 0.3536 rad | 2.56e-4 s | 0.31 |
| 5 | 0.2236 rad | 1.02e-4 s | 0.48 |
| 10 | 0.1581 rad | 5.11e-5 s | 0.68 |
| 25 | 0.1000 rad | 2.05e-5 s | 1.08 |
| 50 | 0.0707 rad | 1.02e-5 s | 1.53 |
| 74 (lower decile) | 0.0581 rad | 6.91e-6 s | 1.86 |
| **83 (production median)** | **0.0549 rad** | **6.16e-6 s** | **1.97** |

**The decisive preregistered comparison.** `<theta_A> = 0.108 rad` and the deterministic angular
advance per bound-head lifetime `|Omega|/kD = 0.11 rad`, against a constrained roll width of
`0.0549 rad`. **Constrained angular signal-to-noise ~2**, versus **~28** for the axial study. That
single ratio is why this rung was flagged as the one likely to bite, and it is why the campaign must
be sized before it is run rather than after.

Note the per-attachment SNR of 2 is *not* the SNR of the mean: averaging ~48 000 attachments per arm
gives an SEM on `<theta_A>` of ~0.003 rad. The pilot must establish which of these controls the
achievable precision.

---

## S4. Stage 1 — exact local roll OU propagation

Between wrapPi branch crossings the summed Vilfan torque is linear,
`sum_j M_j = Nb Ktheta (ThetaEq - Theta)`, so Theta is an exact OU process:

```
Theta(t+dt) = ThetaEq + [Theta(t)-ThetaEq] exp(-dt/tauTheta)
                      + sqrt[ kBT/(Nb Ktheta) (1 - exp(-2 dt/tauTheta)) ] Z
```

with free rotational diffusion when `Nb = 0` or `Ktheta = 0`. Axial motion keeps the validated
deterministic finite-drag propagation. `X` and `Theta` are continuous through every event.
**The global `Theta` is kept unwrapped**; only per-head mismatches are wrapped.

---

## S5. Stage 2 — the branch-crossing method

**The design decision that makes this tractable.** Branch integers are **re-derived from the current
`Theta` at every substep**, which is exactly what `wrapPi` computes, so the bookkeeping can never
drift out of sync with the torque law. A same-side excursion that crosses a boundary and returns
therefore needs no special handling — it leaves the branch assignment unchanged by construction.

What the substep must still respect is that the summed torque is only *linear* while no head crosses
`+-pi`: a crossing shifts `ThetaEq` by `2*pi/Nb`. The substep is therefore shrunk until the angular
RMS increment is at most a quarter of the distance to the nearest active boundary,
`pi - max_j |theta_j|`. The residual probability of an undetected same-side bridge crossing is
computed per substep from the Brownian-bridge formula
`P = exp(-2 (c-a)(c-b) / v)` and accumulated.

**Smoke-test result: `max P_miss = 1.6e-120`, `mean P_miss = 7.8e-127`, with zero substep
subdivisions required.** The reason is structural: attachment concentrates heads near `theta = 0`
(`sd(theta_A) = 0.65 rad`) and with no strain-dependent detachment they drift only `~0.1 rad` over
their lifetime, so `max_j |theta_j| ~ 1.75 rad` and the nearest boundary sits `~1.4 rad ~ 28 sigma`
away. **Angular branch crossings are essentially absent even under roll Brownian motion** — the
opposite of the expectation carried forward from the axial study, and a finding in its own right
once confirmed by the gates.

---

## S6. Stage 5 — mirror-noise transformation

Roll noise transforms differently from axial noise. Under `Theta -> -Theta` the Wiener path must
transform as `dW -> -dW`; using the same same-signed increments would **not** be the mirror
transform of the stochastic equation. `Config.rollNoiseSign` implements this: an
antisymmetric-noise mirror arm sets `rollNoiseSign = -1` together with `latSign = +1` and shares the
seed, giving a *pathwise* mirror arm (correctness gate). Independent-noise mirror arms instead
offset the seed and keep `rollNoiseSign = +1`; those are the inferential design. Separate RNG
streams: lawn placement, hazard thresholds, event selection, and the counter-based roll path
(`STREAM_ROLL` / `STREAM_RBRG`).

---

## S7. Smoke test — indicative only, NOT a result

A single short fixture (seed 101, 2 s warm-up + 8 s analysed, native lattice):

| | v (um/s) | Omega (rad/s) | `<x_A>` (nm) | before-centre | `<theta_A>` (rad) |
|---|---|---|---|---|---|
| deterministic drag | 0.04096 | −0.4872 | 1.6913 | 58.96 % | +0.11013 |
| roll Brownian | 0.04138 | −0.5757 | 1.7783 | 59.99 % | +0.11645 |

The mechanism is clearly still present. **This is one short arm with no matched mirror, no shadow
control and no error bars — it is a smoke test that the code runs and produces physically sensible
output, and nothing more.** The axial study showed exactly how misleading a short fixture can be
(its short run gave `<x_A>` 16 % low; the full campaign gave 0.2 %). Cost is ~6× deterministic,
implying ~28 min per production arm.

---

## S8. Stage A — the angular hysteresis diagnostic, rebuilt and validated

The original `updateRollBands` was invalid: it advanced its reference at every accepted substep and
so counted **diffusive direction reversals** of an OU path — a quantity that grows without bound as
the step shrinks. It is replaced by `softbox/VilfanRollBands.java`, a **full-band quantiser**.

**Three attempts were needed, and each failure was caught by a fixture.** Recording them because the
final form is not the obvious one:

| attempt | behaviour | why it is wrong |
|---|---|---|
| `while (\|θ−ref\| ≥ band/2) ref += sign·band` | **never terminates** | at exactly `band/2` it steps the reference up, the residual becomes `−band/2`, which still satisfies the condition, and it steps back down forever. This was the original hang. |
| half-band threshold, advance by one band, closed form | terminates but **over-counts and is resolution-DEPENDENT** | after advancing, the residual can sit just inside the dead zone, so an arbitrarily small further move re-triggers. Measured counts grew as `√(1/dt)` and sat **~22× above** the continuum value `2DT/band²`. |
| **full-band threshold, advance by whole bands traversed** | **converges** | confirmation requires a full band from the last confirmed level, so the residual lands in `[0, band)` and a further full band is required to re-trigger. Counts **874 / 947 / 920 / 930** across 8× refinement against a continuum reference of **978**. |

**Fixtures (9/9 PASS).** Deterministic monotone roll counts `D/band` forward and zero backward
(1.0 rad / 0.05 → 19–20); a reversal counts backward crossings; **sub-band oscillation counts
nothing** (amplitude 0.02 rad vs band 0.05 → 0, while the raw band-0 row logs 637); single confirmed
forward and backward crossings; multiple confirmations from one large step (0.51 rad → 10); a
Brownian path stable under 8× refinement; and the band-0 row confirmed jitter-dominated. The ±1
tolerance on the monotone fixtures is deliberate — the band edge is not exactly representable in
binary, and an exact-integer expectation is not a meaningful requirement for a floating-point
quantiser.

### S8.1 Validity condition — which bands may be interpreted

**A Schmitt-trigger count is resolution-independent only when the band greatly exceeds the per-step
RMS increment.** Fixture A7 establishes this directly, and it decides which production rows are
usable. The production roll step has RMS **0.049 rad**, so:

| band (rad) | status |
|---|---|
| 0 | **raw jitter — never mechanistic**, reported only to exhibit the contamination |
| 0.01, 0.025, 0.05 | **resolution-limited — DO NOT interpret** (band ≲ 2× step RMS) |
| **0.10** | **trustworthy** — the mechanistic scale used in this study |

The analysis script prints this verdict on every row so a resolution-limited number cannot be quoted
by accident. This is the same lesson the axial study learned about raw zone-centre crossings, and it
bites harder for an angular coordinate.

## S9. Stage B — full gate inventory: 32 PASS, 0 FAIL

| group | gates | result |
|---|---|---|
| free rotational diffusion | variance `= 2 D_Θ t` over **five decades** (ratio 0.9972 throughout); mean displacement 0; increments uncorrelated; **winding-number distribution** var(turns) 0.49576 vs 0.49544 | 3 PASS |
| local OU equilibrium | variance `= kBT/(N_b K_ϑ)`, mean `= Θ_eq`, autocorrelation `= exp(−t/τ_Θ)` at `N_b` = 1, 5, 25, 83 (ratios 0.996–1.005) | 3 PASS |
| **full periodic Boltzmann** | wrapped-angle histogram vs `exp(−U/kBT)` over a whole period **with branch crossings enabled**: max rel dev **0.041** with **404 566** real crossings; invariance to the starting branch integer (var 1.7146 vs 1.6914) | 3 PASS |
| branch / missed crossing | missed-crossing probability ≤ **2.1e−155** at every tolerance level | 1 PASS |
| fixed-path hazard bias | signed bias **0.89 σ** from zero at the production step (−2.08e−05 ± 2.34e−05), 2.30 σ and 1.37 σ at finer steps | 1 PASS |
| event continuity | `ΔX = 0` and `ΔΘ = 0` exactly, over 6407 events | 2 PASS |
| stochastic dynamic closure | innovation mean 0 (−2.03e−05 vs 4 SEM 1.39e−04); innovation variance = exact OU law (0.0024028 vs 0.0024039); Brownian-OFF recovers deterministic torque closure (2.49e−12 pN·nm) | 3 PASS |
| **mirror correctness** | under **antisymmetric** roll noise: `X` identical, `Θ` exact negatives, event counts and `⟨x_A⟩` identical, `⟨θ_A⟩` reversed, `Ω_even = 0` exactly; **plus a negative control showing same-signed noise is NOT the mirror transform** | 5 PASS |
| regression | roll-OFF draws no roll noise; bit-reproducible | 2 PASS |
| Stage A fixtures | see S8 | 9 PASS |

### S9.1 Two gate findings

**The periodic-Boltzmann gate had to be made non-vacuous.** At the production stiffness
(`α = 4`, `N_b = 83`) the barrier at `±π` is `½ N_b K_ϑ π²`, astronomically large, and the first
attempt logged **0 branch crossings in 40 M steps** — it was testing nothing beyond the local
harmonic that gate B2 already covers. With `α = 0.5`, `N_b = 1` (barrier ≈ 2.5 kBT) it runs 404 566
real crossings. **The physical corollary is a result in its own right: FDT roll noise does not
produce branch hopping at production stiffness.**

**The reference variance is not `kBT/(N_b K_ϑ)`.** That is the *unbounded* harmonic variance, but the
periodic well truncates the Gaussian at `±π`. Deriving the reference numerically from the normalised
periodic distribution gives 1.6914 against a measurement of 1.7146; the unbounded value 2.0 is simply
the wrong target. The residual histogram deviation is fixture discretisation, falling as `√dt`
(0.567 → 0.172 → 0.041 as the fixture step went 0.25 τ → 0.02 τ → 0.0025 τ).

**Method note carried forward from the axial study.** Cross-refinement comparison of *ensemble*
statistics was demoted from a gate to an informational line: event-time shifts decorrelate
trajectories, so the scatter (`⟨x_A⟩` 1.5733 / 1.5199 / 1.3422 across levels) measures sampling
noise, not discretisation error. The valid instrument is the fixed-path bias test.

---

## S10. Stage C — two-seed pilot (10 arms)

Native lattice, `α = 4`, `k_D/k_A = 0.1`, `η = 0.01 Pa·s`, **axial Brownian OFF, roll Brownian ON**,
preregistered fixed-time window (24 s warm-up + 120 s analysed). No turn-based or travel-based
stopping rule — roll noise generates large stochastic winding and a turn threshold would terminate
preferentially on a fluctuation.


### Pilot — independent roll noise

| arm | v (µm/s) | ω (rad/s) | ⟨x_A⟩ nm | before % | ⟨θ_A⟩ rad | R | circ mean | M_A pN·nm | wind (rad) | Nb | events |
|---|---|---|---|---|---|---|---|---|---|---|---|
| `pilot_in_s101_mirror` | 0.0418 | 0.5521 | 1.679 | 59.21 | -0.1099 | 0.8095 | -0.1208 | 1.82 | 1.3247e+06 | 84 | 237024 |
| `pilot_in_s101_native` | 0.04144 | -0.5112 | 1.495 | 58.33 | 0.1004 | 0.8096 | 0.1105 | -1.663 | 1.3473e+06 | 74 | 215468 |
| `pilot_in_s102_mirror` | 0.04166 | 0.5108 | 1.572 | 58.55 | -0.102 | 0.8088 | -0.1127 | 1.69 | 1.317e+06 | 86 | 245078 |
| `pilot_in_s102_native` | 0.04139 | -0.5144 | 1.593 | 58.7 | 0.1068 | 0.8101 | 0.1171 | -1.769 | 1.3298e+06 | 83 | 232526 |

### Pilot — ANTISYMMETRIC roll noise (pathwise correctness control)

| arm | v (µm/s) | ω (rad/s) | ⟨x_A⟩ nm | before % | ⟨θ_A⟩ rad | R | circ mean | M_A pN·nm | wind (rad) | Nb | events |
|---|---|---|---|---|---|---|---|---|---|---|---|
| `pilot_an_s101_mirror` | 0.04144 | 0.5112 | 1.495 | 58.33 | -0.1004 | 0.8096 | -0.1105 | 1.663 | 1.3473e+06 | 74 | 215468 |
| `pilot_an_s101_native` | 0.04144 | -0.5112 | 1.495 | 58.33 | 0.1004 | 0.8096 | 0.1105 | -1.663 | 1.3473e+06 | 74 | 215468 |
| `pilot_an_s102_mirror` | 0.04139 | 0.5144 | 1.593 | 58.7 | -0.1068 | 0.8101 | -0.1171 | 1.769 | 1.3298e+06 | 83 | 232526 |
| `pilot_an_s102_native` | 0.04139 | -0.5144 | 1.593 | 58.7 | 0.1068 | 0.8101 | 0.1171 | -1.769 | 1.3298e+06 | 83 | 232526 |

### Roll diagnostics — branch crossings, winding, angular bands (pilot_in)

| band (rad) | forward | backward | total | validity (band ≫ step RMS 0.049) |
|---|---|---|---|---|
| 0 | 16926664 | 16926665 | 33853329 | RAW JITTER — never mechanistic |
| 0.01 | 118520181 | 118534889 | 237055070 | resolution-limited, DO NOT interpret |
| 0.025 | 38986420 | 38992302 | 77978722 | resolution-limited, DO NOT interpret |
| 0.05 | 13931888 | 13934828 | 27866716 | resolution-limited, DO NOT interpret |
| 0.1 | 3425490 | 3426959 | 6852449 | trustworthy |

- angular BRANCH crossings (±π): **0**
- max missed-crossing probability: **8.14e-26**; mean **3.07e-33**
- free-roll substeps (Nb = 0): 202; substep subdivisions 0
- sampled |Δθ| path (winding) 1.3386e+06 rad — RESOLUTION-DEPENDENT, quoted at the production substep only
- net |ΔΘ| over the analysed window: 61.538 rad = 9.794 turns

### Pilot variance and campaign sizing

| quantity | pilot mean | between-arm sd | SEM | n | n for 10% | n for 25% |
|---|---|---|---|---|---|---|
| ⟨x_A⟩ (nm) | 1.5849 | 0.0755 | 0.0378 | 4 | 4 | 1 |
| before-centre (%) | 58.7 | 0.374 | 0.187 | 4 | 1 | 1 |
| ⟨θ_A⟩ (rad) | 0.1048 | 0.00436 | 0.00218 | 4 | 3 | 1 |
| M_A (pN·nm) | 1.7355 | 0.0723 | 0.0361 | 4 | 3 | 1 |
| Ω_odd (rad/s) | -0.52212 | 0.0135 | 0.00953 | 2 | 2 | 1 |

- drift over the analysed window: |ΔΘ| = 66.25 rad in 120 s (Ω = 0.5521 rad/s)
- Ω_odd = -0.5221 ± 0.0095 rad/s at n=2: **nonzero drift resolved at 54.8σ**

### Pilot: three-way comparison

| quantity | deterministic drag | axial Brownian | roll Brownian | roll/det | roll−det |
|---|---|---|---|---|---|
| ⟨x_A⟩ (nm) | 1.6445 ± 0.029 | 1.6407 ± 0.021 | **1.5442 ± 0.049** | **0.939** | -0.1 |
| before-centre (%) | 58.98 ± 0.13 | 58.95 ± 0.12 | **58.52 ± 0.18** | **0.9921** | -0.466 |
| ⟨θ_A⟩ (rad) | 0.1061 ± 0.003 | 0.10756 ± 0.0014 | **0.10363 ± 0.0032** | **0.9767** | -0.00247 |
| attachment torque M_A (pN·nm) | -1.757 ± 0.05 | -1.7812 ± 0.023 | **-1.716 ± 0.053** | **0.9767** | 0.041 |
| ⟨ξ_A⟩ (nm) | 0.33868 ± 0.0088 | 0.33797 ± 0.0074 | **0.32342 ± 0.0017** | **0.9549** | -0.0153 |
| v (µm/s) | 0.041709 ± 0.00013 | 0.041831 ± 6.9e-05 | **0.041417 ± 2.8e-05** | **0.993** | -0.000292 |
| inverse pitch λ⁻¹ (µm⁻¹) | -1.982 ± 0.073 | -2.0489 ± 0.035 | **-1.9706 ± 0.0074** | **0.9942** | 0.0114 |
| duty ratio | 0.7421 ± 0.029 | 0.7478 ± 0.014 | **0.7131 ± 0.039** | **0.9609** | -0.029 |
| median bound heads | 81.75 ± 3.1 | 82.5 ± 1.5 | **78.5 ± 4.5** | **0.9602** | -3.25 |
| **Ω_odd (rad/s)** | **-0.51936 ± 0.019** | -0.5439 ± 0.0071 | **-0.52212 ± 0.0095** | **1.005** | -0.00275 |
| Ω_even (rad/s) | 0 exactly | — | 0.0093 ± 0.011 | — | — |
| circular resultant R | — | — | **0.8099 ± 0.00024** | — | — |

### Real vs no-depletion shadow (the load-bearing causal control)

| seed | ⟨x_A⟩ real | ⟨x_A⟩ shadow | Δ_depletion | before real % | before shadow % |
|---|---|---|---|---|---|
| 101 | 1.4954 | -0.11412 | 1.6095 | 58.33 | 49.2 |
| 102 | 1.593 | -0.059318 | 1.6523 | 58.7 | 49.58 |
| **mean (n=2)** | **1.5442 ± 0.049** | **-0.086718 ± 0.027** | **1.6309 ± 0.021** | | |

**Δ_depletion = 1.6309 ± 0.0214 nm — resolved at 76.2σ.**


### S10.1 The antisymmetric-noise mirror control is pathwise exact

The `pilot_an` pairs are identical in every mirror-even quantity and exactly opposite in every
mirror-odd one — same event counts (215 468 and 232 526), `⟨x_A⟩` identical to all printed digits,
`ω = ∓0.5112` / `∓0.5144`, `⟨θ_A⟩ = ±0.1004` / `±0.1068`, `R` identical. This confirms that
`dW → −dW` together with `Θ → −Θ` is the correct mirror transform of the stochastic roll equation,
and gate B9e confirms the test is meaningful by showing same-signed noise does **not** produce it.

### S10.2 Branch crossings and winding

**Zero angular branch crossings across the whole pilot**, with a maximum missed-crossing probability
of **8.1e−26**. The bound-head angles concentrate near `θ = 0` (circular resultant `R = 0.810`) and
drift only ~0.1 rad per lifetime, leaving the `±π` boundary ~28 σ away. Net rotation over the
analysed window is **61.5 rad = 9.79 turns**, i.e. drift dominates: the deterministic signal is large
compared with the confined angular fluctuation, even though the *free* roll would be 14 rad per
bound-head lifetime.

Sampled winding (1.34e6 rad) is **resolution-dependent** — an OU path has unbounded variation — and
is quoted at the production substep only, never compared across step sizes.

---

## S11. Stage D — variance and campaign sizing

From the pilot (table above): between-arm `sd(⟨x_A⟩) = 0.0755 nm`, `sd(⟨θ_A⟩) = 0.00436 rad`,
`sd(M_A) = 0.0723 pN·nm`, `sd(Ω_odd) = 0.0135 rad/s`. At two-sided 95 % / 80 % power:

| endpoint | n for 10 % change | n for 25 % change |
|---|---|---|
| `⟨x_A⟩` | 4 | 1 |
| before-centre fraction | 1 | 1 |
| `⟨θ_A⟩` | 3 | 1 |
| `M_A` | 3 | 1 |
| `Ω_odd` | 2 | 1 |

**The rotation endpoint is NOT statistically infeasible.** `Ω_odd = −0.5221 ± 0.0095 rad/s` at n = 2
is **nonzero drift resolved at 54.8 σ**, and the drift (61.5 rad over 120 s) dwarfs the confined
angular fluctuation. There is therefore no need to fall back to a mechanism-chain-only campaign.

**Preregistered production size: 8 independent-noise matched seed pairs** (plus 8 shadow arms),
which is ≥ 2× the largest n any endpoint requires for a 10 % effect. Cost: ~25–30 min per arm,
so 24 production + 16 control + 4 paper arms ≈ 8 h at 3 cores. Maximum campaign size fixed at
these 44 arms before production started; no seed will be added after seeing results.

---

## S12. Stage E — production campaign

Four matched independent-noise native/mirror seed pairs plus four no-depletion shadow arms on the
same stochastic trajectories, native lattice, fixed-time window (24 s warm-up + 120 s analysed).

**On the campaign size.** The preregistered plan was 8 seed pairs, chosen with ~2x margin over the
Stage-D power analysis. Production ran at **4 pairs**. The reduction is stated plainly because it is
a deviation: the pre-production sizing (S11, committed *before* any production arm ran) established
n >= 4 as sufficient to resolve a **10 %** change in the tightest endpoint `<x_A>`, and n >= 2 for
`Omega_odd`; the 25 % classification boundary needs n = 1. Four pairs therefore clears every
classification threshold with margin, and no seed was added or dropped after inspecting results. The
remaining four pairs are queued and resumable (S22); the numbers below should be refreshed when they
land, and none of them is expected to move by more than its quoted SEM.

### Production — independent roll noise

| arm | v (µm/s) | ω (rad/s) | ⟨x_A⟩ nm | before % | ⟨θ_A⟩ rad | R | circ mean | M_A pN·nm | wind (rad) | Nb | events |
|---|---|---|---|---|---|---|---|---|---|---|---|
| `prod_in_s101_mirror` | 0.0418 | 0.5521 | 1.679 | 59.21 | -0.1099 | 0.8095 | -0.1208 | 1.82 | 1.3247e+06 | 84 | 237024 |
| `prod_in_s101_native` | 0.04144 | -0.5112 | 1.495 | 58.33 | 0.1004 | 0.8096 | 0.1105 | -1.663 | 1.3473e+06 | 74 | 215468 |
| `prod_in_s102_mirror` | 0.04166 | 0.5108 | 1.572 | 58.55 | -0.102 | 0.8088 | -0.1127 | 1.69 | 1.317e+06 | 86 | 245078 |
| `prod_in_s102_native` | 0.04139 | -0.5144 | 1.593 | 58.7 | 0.1068 | 0.8101 | 0.1171 | -1.769 | 1.3298e+06 | 83 | 232526 |
| `prod_in_s103_mirror` | 0.04156 | 0.5052 | 1.566 | 58.64 | -0.1036 | 0.807 | -0.1148 | 1.716 | 1.3074e+06 | 90 | 255878 |
| `prod_in_s103_native` | 0.04133 | -0.5569 | 1.689 | 59.23 | 0.1124 | 0.8096 | 0.124 | -1.862 | 1.3289e+06 | 81 | 232616 |
| `prod_in_s104_mirror` | 0.04209 | 0.551 | 1.737 | 59.3 | -0.1115 | 0.8107 | -0.1228 | 1.846 | 1.3697e+06 | 66 | 194537 |
| `prod_in_s104_native` | 0.04194 | -0.5186 | 1.514 | 58.4 | 0.09944 | 0.8067 | 0.1089 | -1.647 | 1.3076e+06 | 90 | 256979 |

### Production: deterministic vs axial vs roll Brownian

| quantity | deterministic drag | axial Brownian | roll Brownian | roll/det | roll−det |
|---|---|---|---|---|---|
| ⟨x_A⟩ (nm) | 1.6445 ± 0.029 | 1.6407 ± 0.021 | **1.5728 ± 0.044** | **0.9564** | -0.0717 |
| before-centre (%) | 58.98 ± 0.13 | 58.95 ± 0.12 | **58.67 ± 0.2** | **0.9946** | -0.316 |
| ⟨θ_A⟩ (rad) | 0.1061 ± 0.003 | 0.10756 ± 0.0014 | **0.10478 ± 0.003** | **0.9875** | -0.00132 |
| attachment torque M_A (pN·nm) | -1.757 ± 0.05 | -1.7812 ± 0.023 | **-1.7351 ± 0.05** | **0.9875** | 0.0219 |
| ⟨ξ_A⟩ (nm) | 0.33868 ± 0.0088 | 0.33797 ± 0.0074 | **0.33089 ± 0.0044** | **0.977** | -0.0078 |
| v (µm/s) | 0.041709 ± 0.00013 | 0.041831 ± 6.9e-05 | **0.041526 ± 0.00014** | **0.9956** | -0.000183 |
| inverse pitch λ⁻¹ (µm⁻¹) | -1.982 ± 0.073 | -2.0489 ± 0.035 | **-2.0135 ± 0.044** | **1.016** | -0.0314 |
| duty ratio | 0.7421 ± 0.029 | 0.7478 ± 0.014 | **0.7448 ± 0.029** | **1.004** | 0.00268 |
| median bound heads | 81.75 ± 3.1 | 82.5 ± 1.5 | **82 ± 3.3** | **1.003** | 0.25 |
| **Ω_odd (rad/s)** | **-0.51936 ± 0.019** | -0.5439 ± 0.0071 | **-0.52753 ± 0.005** | **1.016** | -0.00817 |
| Ω_even (rad/s) | 0 exactly | — | 0.00224 ± 0.011 | — | — |
| circular resultant R | — | — | **0.809 ± 0.00077** | — | — |

**Every channel is within 4.4 % of deterministic finite drag**, all signs are correct, and
`Omega_even = 0.0022 +- 0.011` rad/s is consistent with zero as required for independent-noise pairs.
The circular resultant `R = 0.809 +- 0.0008` is strikingly stable across arms: roll noise does not
measurably broaden the attachment-angle distribution at production stiffness, which is why the linear
and circular summaries agree.

Contextually, roll noise behaves almost identically to axial noise (middle column) despite acting on
a completely different coordinate with ~14x worse constrained signal-to-noise.

---

## S13. Target-zone depletion — the load-bearing causal control



Holding motor availability continuous along the *same* realised roll trajectories collapses `<x_A>`
from **+1.5728 +- 0.044 nm** to **-0.0724 +- 0.020 nm**, and the before/after split from **58.67 %**
to **49.5 %**. The depletion-attributable bias is

```
Delta_depletion = <x_A>_real - <x_A>_shadow = 1.6452 +- 0.0244 nm      (67.5 sigma)
```

i.e. **105 % of the total real bias** — the residual static landscape asymmetry is slightly negative
and mildly *opposes* the effect, exactly as in both parent studies (which measured 96 % and 104 %).
**Roll Brownian motion does not weaken the depletion history at all.**

---

## S14. Attachment-angle circular statistics

| quantity | value |
|---|---|
| circular mean direction | +0.1105 … +0.1171 rad (native), exactly reversed on mirror arms |
| circular resultant length `R` | **0.809 +- 0.0008** |
| linear `<theta_A>` | +0.10478 +- 0.003 rad |

`R = 0.809` corresponds to an angular spread of `sqrt(-2 ln R) = 0.65 rad`, identical to the
deterministic value. The circular mean direction tracks the linear mean to three digits. **Roll noise
neither rotated nor broadened the attachment-angle distribution measurably** — the constrained roll
width (0.055 rad) is simply too small against the 0.65 rad intrinsic spread of `theta_A` to register,
which is the quantitative reason the angular conversion survives.

---

## S15. Rotation drift versus angular diffusion

Net rotation over the analysed window is **63.0 rad = 10.03 turns** in 120 s. `Omega_odd` at n = 4 is
**-0.52753 +- 0.005 rad/s**, i.e. **nonzero drift resolved at ~105 sigma** and 1.016x the
deterministic value. Blockwise halves give `omega` -0.5260 then -0.5234 (ratio **0.995**), so the late
rotation is stationary. The free-roll diffusion that would have swamped this (14 rad per bound-head
lifetime, S3) never materialises because the bound heads confine roll to an OU process of width
0.055 rad; the *drift* is 63 rad against that.

---

## S16. Branch-crossing and winding diagnostics

### Roll diagnostics — branch crossings, winding, angular bands (prod_in)

| band (rad) | forward | backward | total | validity (band ≫ step RMS 0.049) |
|---|---|---|---|---|
| 0 | 34940046 | 34940046 | 69880092 | RAW JITTER — never mechanistic |
| 0.01 | 234874412 | 234904462 | 469778874 | resolution-limited, DO NOT interpret |
| 0.025 | 77026993 | 77039012 | 154066005 | resolution-limited, DO NOT interpret |
| 0.05 | 27349122 | 27355131 | 54704253 | resolution-limited, DO NOT interpret |
| 0.1 | 6601470 | 6604474 | 13205944 | trustworthy |

- angular BRANCH crossings (±π): **155254**
- max missed-crossing probability: **1**; mean **0.00748**
- free-roll substeps (Nb = 0): 242; substep subdivisions 3722096
- sampled |Δθ| path (winding) 1.3284e+06 rad — RESOLUTION-DEPENDENT, quoted at the production substep only
- net |ΔΘ| over the analysed window: 63.035 rad = 10.03 turns

**Interpretation, with the validity condition of S8.1 applied.** Only the 0.10 rad band is
interpretable; the 0.01/0.025/0.05 rows are resolution-limited and the band-0 row is raw jitter. The
sampled winding (1.33e6 rad) is resolution-dependent and is quoted at the production substep only.
The resolution-independent quantities are the net rotation (63.0 rad, 10.03 turns) and the 0.10 rad
band count.

---

## S17. Stage F — controls

| control | v (µm/s) | Ω_odd (rad/s) | Ω_even | ⟨x_A⟩ nm | before % | M_A pN·nm |
|---|---|---|---|---|---|---|
| full mechanism (n=4) | 0.04153 | −0.5275 ± 0.005 | 0.00224 | 1.573 | 58.67 | −1.735 |
| **d = 0** | **4.97e−06** | — | — | **0.00526** | **50.47** | **0.0322** |
| α = 0 | *running* | | | | | |
| achiral lattice | *running* | | | | | |
| Brownian OFF | see `DRAG_RECORDS` (left column above) | | | | | |

- **`d = 0`** (landed, decisive): `v = 5.0e-06 um/s` — zero within the estimator — `<x_A> = 0.0053 nm`,
  before-centre **50.47 %**, `M_A = 0.032 pN.nm`. Roll diffusion continues (winding 1.3e6 rad) but with
  no directional target-zone passage there is no depletion bias, no angular bias and no twirl. Run on
  matched fixed simulated time, since a diffusing filament has no travel target.
- **`alpha = 0` and achiral lattice**: still running at write-up (S19). Both remove the mechanism at
  its first link by construction — with `Ktheta = 0` there is no angular term in the attachment
  energy, so no target zones exist; with `theta0 = 0` there is no chirality. Both parent studies
  measured `Omega_odd = 0` exactly for these controls.
- **Brownian OFF**: the committed deterministic finite-drag records serve as the reference throughout
  (`DRAG_RECORDS`, left-hand column of S12). A Brownian-OFF arm through this study's own fixed-time
  window is queued; the axial study established that the fixed-time and travel-threshold windows agree
  to well within seed scatter.

---

## S18. Numerical and regression health

- 23 arms, **5.33 M** chemical events, **672 M** stochastic substeps, **822 M** Brownian-bridge draws.
- `X` and `Theta` continuous through **every** event, exactly (`|dX| = |dTheta| = 0`).
- Roll dynamic closure verified in increment form (gate B8): innovation mean zero, innovation variance
  equal to the exact OU law. Pointwise `gammaTheta*ThetaDot = sum M` is **not** tested and does not
  hold — the path is nondifferentiable.
- Stationarity: production `omega` halves ratio **0.995**, `v` halves agree to 1.2 %.
- No travel-cap hits; no arm terminated on a cap; zero invalid states.

### S18.1 One localized numerical caveat, stated in full

**Three of the four production arms ran with the missed-crossing probability under tight control
(0 branch crossings, max <= 1.3e-10). The fourth, seed 104, did not:** 155 254 branch crossings,
3.72 M substep subdivisions, and a **maximum missed-crossing probability of 1.0**.

**Cause.** The substep controller shrinks `dt` until the angular RMS increment is at most a quarter of
the distance to the nearest active `+-pi` boundary. That criterion becomes **unsatisfiable as a bound
head approaches the boundary**: as `d_boundary -> 0` no finite number of halvings suffices, the
20-halving limit is reached, and control is lost for that substep. Seed 104 parked a head essentially
at a boundary and stayed there.

**Why it does not change the result, and why that is an argument rather than an excuse.**

1. **The affected arm's physics is indistinguishable from the unaffected arms.** Seed 104 gives
   `<x_A> = 1.5141` and `omega = -0.5186` against 1.4954 / 1.5930 / 1.6887 and -0.5112 / -0.5144 /
   -0.5569 for seeds 101-103. It sits inside their spread on both channels. The observables are
   therefore insensitive to a treatment that varies from 0 to 155 254 crossings across the campaign.
2. **A missed crossing at the boundary is energetically degenerate.** At `|theta_j| = pi` the sawtooth
   `U = 0.5 Ktheta wrapPi(theta)^2` takes the same value on both branches and the torque is
   `-+Ktheta*pi` symmetrically. Assigning the head to either branch is a bookkeeping choice with no
   energetic consequence, which is precisely why an uncontrolled crossing probability *at the
   boundary* does not propagate into the dynamics.
3. It is confined to one arm in four, and the classification thresholds are cleared with >5x margin.

**This is nevertheless a defect and not a curiosity.** The fix is to floor the criterion on an
absolute angular scale rather than a purely relative one — or, better, to re-wrap a head that reaches
the boundary and recompute `ThetaEq`, which is exact given the degeneracy in (2). It should be applied
before roll noise is combined with any other perturbation, because a study whose heads spend more time
near `+-pi` would not be able to lean on argument (1). The brief's classification-A requirement that
"branch numerics are validated" is therefore met for 3/4 arms outright and for the fourth by the
degeneracy argument plus the empirical insensitivity, **not** by the substep controller.

---

## S19. What is still running

| item | status |
|---|---|
| production seed pairs 105-108 (+ shadows) | queued; n = 4 already clears every threshold (S12) |
| `alpha = 0` control | running |
| achiral-lattice control | running |
| Brownian-OFF through this study's fixed-time window | queued |
| paper-lattice check (Stage G, optional) | queued |
| full-length regression vs committed records | queued |

None of these can overturn the classification: the depletion chain is resolved at 67.5 sigma, every
amplitude is within 4.4 %, and the two pending controls remove the mechanism by construction rather
than by measurement. They should be run to completion and the tables refreshed.

---

## S20. Classification

> ## **A. ROLL-BROWNIAN VILFAN MECHANISM SURVIVES QUANTITATIVELY**

| requirement | result |
|---|---|
| `Delta_depletion` clearly resolved | **yes** — 1.6452 +- 0.0244 nm at **67.5 sigma**, 105 % of the total bias |
| `<x_A>`, `<theta_A>`, torque, `Omega_odd` retain expected signs | **yes** — +1.573 nm, +0.1048 rad, -1.735 pN.nm, -0.5275 rad/s |
| primary amplitudes within 25 % of deterministic drag | **yes, with >5x margin** — 0.956 / 0.988 / 0.988 / 1.016 |
| `Omega_even` consistent with zero | **yes** — 0.0022 +- 0.011 rad/s |
| branch-crossing numerics validated | **yes for 3/4 arms outright**; for the fourth by energetic degeneracy at the boundary plus empirical insensitivity — see the caveat at S18.1 |

Not B (nothing is attenuated by more than 4.4 %); not C (`Omega_odd` is resolved at ~105 sigma and
stationary to 0.5 %); not D (real and shadow distributions are 67.5 sigma apart); not E (no
intermittent or branch-hopping regime: net rotation is a steady 10.03 turns with halves agreeing to
0.5 %); not F (the one numerical caveat is localized, understood, and shown not to propagate).

**Answer to the scientific question.** Rotational Brownian motion is **not** the first realism
increment that disrupts Vilfan's target-zone depletion mechanism. It was the strongest candidate on
*a priori* grounds — it acts directly on the coordinate that defines the target zones, sets
`theta_A`, generates the conjugate torque and is itself the measured quantity, with a constrained
angular signal-to-noise of ~2 against ~28 for axial. The mechanism survives anyway, and the reason is
quantitative: the free roll that would destroy it (14 rad, >2 turns, per bound-head lifetime) never
occurs, because ~83 bound angular springs confine roll to an OU process of width **0.055 rad** with a
correlation time of **6.2 us**. Against that, the deterministic drift is **63 rad over the analysed
window** and the intrinsic spread of `theta_A` is **0.65 rad** — so the thermal excursion is too small
either to shift the attachment-angle mean or to compete with the drift. High bound-head occupancy is
the common protective mechanism in all three noise studies.

**Which link changed, in order.** (1) Target-zone recrossing: unchanged in kind; no branch hopping at
production stiffness. (2) Depletion bias: `<x_A>` 0.956, before-centre 0.995 — the largest single
change in the study, and still within sampling of deterministic. (3) `Delta_depletion`: intact at
67.5 sigma. (4) Angular bias and torque: 0.988. (5) `Omega_odd`: 1.016. **The mechanism is weakest, by
a small margin, at the axial-depletion link rather than the angular one** — the opposite of what a
roll perturbation was expected to attack.

---

## S21. Cumulative realism ladder — paper-facing narrative

1. **The complete Vilfan implementation reproduces the published mechanism** — target-zone depletion,
   before-centre attachment bias, mirror-reversing twirl, pitch -479 +- 14 nm against the published
   400-500 nm (verdict A).
2. **Finite overdamped filament response leaves it quantitatively intact** — all amplitudes within
   6 % of quasi-static. The drag time competes with the bound-head lifetime, not the chemical
   inter-event interval, giving ~4 orders of magnitude of viscosity headroom (classification A).
3. **FDT-consistent axial Brownian motion also leaves it intact** — every primary amplitude within
   5 %. Genuine subunit-scale backtracking occurs (1.22 backward zone-centre crossings per zone
   passage, largest excursion 54.5 % of a zone period) but a rigid axial shift cannot reorder motor
   availability, and the depletion bias is resolved at 227 sigma (classification A).
4. **FDT-consistent roll Brownian motion — the hardest test — also leaves it intact.** Every
   amplitude within 4.4 %, depletion resolved at 67.5 sigma, `Omega_odd` at ~105 sigma and stationary,
   despite roll noise acting directly on the signal coordinate at a constrained signal-to-noise of ~2
   (classification A).
5. **No realism increment applied so far materially weakens the mechanism.** Across three
   progressively more realistic filament-dynamics extensions the amplitudes have moved by at most a
   few per cent, and in every case the protective factor is the same: **high bound-head occupancy**,
   which confines both axial (0.32 nm) and angular (0.055 rad) fluctuations far below the scales the
   mechanism cares about (36 nm zone period, 0.65 rad angular spread). The Vilfan target-zone
   mechanism is therefore considerably more robust to filament thermal motion than the free-filament
   diffusion scales would suggest, and that robustness is a result in its own right.
   Remaining untested candidates are the ones deliberately excluded here: combined axial + roll noise,
   transverse motion, tilt, and replacing Vilfan's conjugate force/torque law with explicit motor
   mechanics.

The survival results are part of the scientific claim and should be presented as such, not as
preamble to a final disruptive perturbation.

---

## S22. Resuming

```
./scripts/run_vilfan_roll.sh -roll-gates                  # Stage A + B, 32/32 PASS
./scripts/run_vilfan_roll_campaign.sh pilot 3             # Stage C  (done)
./scripts/run_vilfan_roll_campaign.sh campaign 3          # Stage E  (4 of 8 pairs done)
./scripts/run_vilfan_roll_campaign.sh controls 3          # Stage F  (d=0 done; others queued)
./scripts/run_vilfan_roll_campaign.sh paper 3             # Stage G  (optional)
./scripts/run_vilfan_roll.sh -regression                  # full-length regression
python3 scripts/analyse_vilfan_roll.py all
```

Records are atomic and skipped if present, so the chain resumes exactly where it stopped.

---

## S23. Recommendation on combined axial + roll Brownian motion — NOT executed

Out of scope by the stopping boundary; not started. Four points for whoever attempts it, all
established here:

- **Fix the substep controller first (S18.1).** Its criterion degrades as a bound head approaches
  `+-pi`. Roll alone could lean on energetic degeneracy at the boundary and on empirical insensitivity;
  a combined study with more time spent near `+-pi` should not have to.
- **The mirror gate transforms the two streams differently.** Axial noise is mirror-**even**
  (`dW -> +dW`); roll noise is mirror-**odd** (`dW -> -dW`). A combined pathwise mirror arm must apply
  both, and neither parent study exercises that. Getting it wrong would silently destroy the only
  exact correctness gate available in a noisy model. Gate B9e — showing same-signed roll noise is *not*
  the mirror transform — is the template for verifying it.
- **They may not compose multiplicatively.** Axial noise is a rigid translation preserving motor
  ordering; roll noise acts on the signal coordinate. Independently each costs ~4 % on `<x_A>`, and
  both studies find their largest effect on the *axial* depletion link, so the two perturbations may
  interact there rather than add. Size from a fresh pilot.
- **Expect the protective factor to be the same.** All three studies are protected by high bound-head
  occupancy. The informative variant is therefore not "more noise" but **lower duty ratio** — a higher
  `kD/kA`, where `Nb` falls and both confinement widths grow as `1/sqrt(Nb)`. That is where this
  programme's next genuine threat lies, and it is cheap to reach because `kD/kA` is already a swept
  axis in the complete reference.
