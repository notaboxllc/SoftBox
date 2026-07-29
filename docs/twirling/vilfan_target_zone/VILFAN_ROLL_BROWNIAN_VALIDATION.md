# Vilfan roll Brownian motion — build and validation

**Branch** `feature/vilfan-roll-brownian` (child of `feature/vilfan-axial-brownian`)
**Worktree** `../softbox-vilfan-roll-brownian`
**Runner** CPU only — no CUDA, no TornadoVM, no `TaskGraph`, no device context. ≤ 3 cores, `nice -n 17`.

> ## STATUS: **INCOMPLETE — Stages 0–3 implemented and smoke-tested; NO CLASSIFICATION IS MADE.**
>
> Stages 6–13 (gates, pilot, sizing, production, controls, paper lattice) have **not** been run.
> Nothing in this document may be cited as a result. The classification section is deliberately
> empty. See S9 for exactly what remains and how to resume.

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

## S8. KNOWN DEFECT — angular hysteresis diagnostic

`updateRollBands` currently updates its reference level at every accepted substep, which makes it a
**direction-reversal counter for a diffusive path** rather than a level-crossing counter with
hysteresis. Its output is therefore resolution-dependent and not mechanistically interpretable
(smoke test: 1 180 060 counts at band 0 and still 590 764 at band 0.10 rad — the signature of
counting OU direction reversals).

This must be rewritten before Stage 7 to mirror the axial implementation: a **fixed** reference
level with a confirmed crossing only after the path moves half a band beyond it. The axial study's
lesson applies with more force here, and the current numbers must not be reported.

---

## S9. What remains, and how to resume

Implemented and smoke-tested: Stage 0 audit, Stage 1 exact roll OU, Stage 2 branch-crossing control
with measured miss probability, Stage 3 path-dependent hazard coupling (OU-bridge Simpson, bridge
refinement, addressed counter-based noise), Stage 5 mirror-noise transform.

**Not done — required before any classification:**

| stage | work |
|---|---|
| S8 defect | rewrite the angular hysteresis counter (fixed reference + band confirmation) |
| 6 | full gate suite: free-roll diffusion over five decades incl. **winding-number distribution**; local OU equilibrium; **full periodic Boltzmann equilibrium permitting branch crossings**; branch-crossing fixtures vs a fine direct `wrapPi` reference; event continuity; **stochastic dynamic closure** (conditional mean drift + zero-mean innovation — note `gammaTheta ThetaDot = sum M` does NOT hold pointwise, the path is nondifferentiable); Brownian-OFF and axial-only regression; antisymmetric-noise mirror gate; reproducibility; enumeration invariance |
| 4 | fixed-path signed hazard-bias measurement (the axial lesson: ensemble scatter across refinement levels is decorrelation, not non-convergence) |
| 8 | two-seed pilot, 4 independent-noise arms + antisymmetric-noise controls, fixed-time 24 s + 120 s window |
| 10 | variance and campaign sizing — separately for `<x_A>`, `<theta_A>` and `Omega_odd`, and explicitly distinguishing **nonzero drift from zero drift plus rotational diffusion** |
| 11–13 | production, controls (shadow / `alpha=0` / `d=0` / achiral / Brownian-OFF), optional paper lattice |

```
./scripts/run_vilfan_brownian.sh -fdt-audit      # parent-study audit still valid
# roll-mode harness entry points are NOT yet written; the physics is reachable via
# Config.rollBrownian = true through the existing VilfanCompleteSystem
```

**No classification A–F is offered.** Per the brief, an underpowered or unvalidated study must not be
classified, and the numerics gates that would justify trusting any campaign have not been run.

---

## S10. Recommendation on combined axial + roll Brownian — NOT executed

Out of scope by the stopping boundary, and it should stay out until roll alone is classified. When
it is attempted, note that the two noises are **not** expected to compose trivially: axial noise is
mirror-even and rigid (it preserves motor ordering), roll noise is mirror-odd and acts directly on
the signal coordinate. The combined study's mirror gate must use `dW_axial -> +dW_axial` together
with `dW_roll -> -dW_roll`, which neither parent study exercises.
