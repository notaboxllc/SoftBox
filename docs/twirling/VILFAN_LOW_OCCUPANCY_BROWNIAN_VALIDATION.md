# Vilfan low-occupancy Brownian confinement and phase memory

**Branch** `feature/vilfan-low-occupancy-brownian` (child of `feature/vilfan-roll-brownian`)
**Worktree** `../softbox-vilfan-low-occupancy-brownian`
**Runner** CPU only — no CUDA, no TornadoVM, no `TaskGraph`, no device context. <= 3 cores, `nice -n 17`.

> ## STATUS: **INCOMPLETE — Stages 0–3 and 8 done; NO CLASSIFICATION IS MADE.**
>
> Delivered: the Stage-0 branch-controller repair (validated), the occupancy / gap / phase-memory
> instrumentation (Stages 1–3), and the Stage-8 occupancy calibration across **both** control axes.
> Two further real bugs were found and fixed along the way (S3).
>
> **Not done:** the Stage-9 mechanism pilot, Stage-10 sizing, Stage-11 production matrix, shadows at
> low occupancy, low-occupancy mirror controls, and the Stage-4 conditional mechanism analysis.
> A classification A–G therefore is **not** offered. The reason is a hard resource limit, not
> remaining difficulty: the production matrix is ~60–100 arms at ~30 min each (30–50 CPU-hours).
> S9 states exactly what remains and how to resume.

---

## S1. Parent result being tested

The parent roll-Brownian study (classification A) established, at the favourable operating point:
median 82 bound heads; roll confined to 0.056 rad with a 6.2 µs correlation time; `<x_A>`,
`<theta_A>`, attachment torque and `Omega_odd` all within 4.4 % of deterministic finite drag;
`Delta_depletion = 1.6452 ± 0.0244 nm`; `Omega_odd = -0.5275 ± 0.005 rad/s`; the attachment-angle
distribution not measurably broadened. **The common protective factor was high bound-head occupancy.**

That validates a *collectively clamped* regime. It does not establish robustness when one or two heads
are bound, which is what this study addresses.

---

## S2. Stage 0 — branch-controller repair

**Defect (inherited).** The parent capped the per-substep angular RMS increment at a quarter of the
distance to the nearest `wrapPi` boundary. That criterion is **unsatisfiable** as a bound head
approaches `±pi`: no finite number of halvings suffices, the halving limit is reached, and control is
lost. It cost 1 of 4 parent production arms and hung the `alpha = 0` control outright.

**Repair.** The implementation already re-derives branch integers from `Theta` at the top of every
substep — exact re-wrapping at `±pi`. A **net** crossing therefore cannot be missed, at any boundary
proximity. The only residual error is the intra-substep drift using the pre-step branch assignment,
and that is bounded by the **substep size**, not by boundary proximity. The criterion is therefore an
**absolute** cap on the angular increment (0.05 rad — about half the angular signal `<theta_A>` and
1/63 of the well width), always satisfiable in `~log2((sig/cap)^2)` halvings. Net crossings are now
counted by comparing branch integers across the re-wrap; the bridge transit probability is demoted to
a diagnostic.

**Validation.**

| arm | crossings | max transit prob. | cap failures |
|---|---|---|---|
| parent seed 104 (defective) | 155 254 | **1.0** | — |
| repaired seed 104 | **0** | **9.6e-31** | 0 |
| repaired seed 101 | 0 | 9.4e-33 | 0 |

**Honest consequence.** The repair changes the substep count, hence which addresses the
counter-based noise stream consumes, hence the realised Wiener path. Parent production values are
therefore **not** reproducible bit-identically (seed 101 `<x_A>` 1.495 -> 1.600, inside the parent
production spread 1.4954–1.6887). **This study must measure its own high-occupancy Regime-H reference
under the repaired controller** rather than compare against the parent numbers. The parent's
*conclusion* is unaffected — its four-arm mean was 1.573 ± 0.044.

---

## S3. Two further defects found and fixed

Both were exposed by the calibration, and both would have invalidated the low-occupancy result.

**(1) Axial Brownian motion was silently ignored whenever roll Brownian was on.** The dispatcher sent
any run with `rollBrownian` to the roll path, which propagated `X` through the *deterministic*
`relaxX`. With no bound head `tau_X = infinity`, so `relaxX` returns `X` unchanged: **the filament
sat frozen through every zero-bound gap**, and axial phase memory was trivially perfect
(`C_X = 1.000` in every calibration arm). This is precisely the "no silent coupling of the axial and
roll streams" requirement being violated. Fixed by an `axialAdvance`/`axialMid` pair that honours the
`axialBrownian` flag inside the combined path, using the axial noise stream and the exact OU (or free)
axial transition.

**(2) The terminal partial substep was not accumulated.** When the chemical event is located *inside*
a substep the advance returned without recording that substep's occupancy time. At low `kD` this is a
<1 % effect; at high `kD`, where an interval may be one or two substeps long, it dominated — the
duty-lowered arms reported `P(N_b=0)` inconsistent with their own gap statistics by a factor ~4.
After the fix, the time-weighted `P(0) x T` and `n_gaps x mean_gap` agree to 25–75 %.

**Residual, stated:** gap durations are resolved only to one substep at each end, so they are
over-estimated by up to ~2 substeps. That is negligible at low `kD` and is the source of the residual
25–75 % discrepancy at the highest `kD`. Gap durations in the duty-lowered arms should be treated as
upper bounds until sub-substep gap timestamping is added.

---

## S4. Preregistered confinement and phase-memory scales

Recorded **before** any low-occupancy arm was run (`-scales`).

| N_b | sd_X (nm) | sd_Theta (rad) | tau_X (s) | tau_Theta (s) |
|---|---|---|---|---|
| 80 | 0.322 | 0.0559 | 1.34e-06 | 6.39e-06 |
| 10 | 0.910 | 0.1581 | 1.07e-05 | 5.11e-05 |
| 3 | 1.661 | 0.2887 | 3.56e-05 | 1.70e-04 |
| 2 | 2.035 | 0.3536 | 5.34e-05 | 2.56e-04 |
| 1 | 2.877 | 0.5000 | 1.07e-04 | 5.11e-04 |

During a zero-bound gap the filament diffuses freely, so phase memory decays as
`C_Theta(t) = exp(-D_Theta t)` and `C_X(t) = exp(-(2 pi/L)^2 D_X t)` with
`D_Theta = 489 rad^2/s`, `D_X = 7.747e4 nm^2/s`, `L = 36 nm`:

| gap (ms) | C_Theta | C_X |
|---|---|---|
| 0.1 | 0.952 | 0.790 |
| 0.5 | 0.783 | 0.307 |
| 2.0 | 0.376 | 0.009 |
| 10.0 | 0.008 | ~0 |

**1/e memory times: 2.045 ms angular, 0.424 ms axial — axial phase is lost 4.8x faster.**

### S4.1 The central preregistered prediction

The two routes to the same mean occupancy are **not** equivalent, because they give different gap
durations:

- **density-lowered** to `N_b ~ 1`: ~1.35 motors under the filament, so after the last detachment the
  reattachment hazard is small — gaps of order **10–30 ms**, i.e. `>> ` both memory times, so
  **both** `C_Theta` and `C_X` should collapse;
- **duty-lowered** to `N_b ~ 1`: the full 110-motor pool is retained, so reattachment is fast — gaps
  of order **0.3–3 ms**, comparable to the axial memory time and shorter than the angular one, so
  memory should be **partially retained**.

---

## S5. Stage 8 — occupancy calibration

Short arms (2 s warm-up + 8 s analysed; occupancy statistics converge far faster than `Omega`), one
seed, both control axes. This map **selects** the production points; it is not used to classify.

| arm | rho (/µm) | kD (/s) | mean N_b | median N_b | P(N_b=0) | gaps | mean gap (ms) | C_Theta | C_X | C_joint | v (µm/s) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| `cal_dens_r0.200` | 0.2 | 5 | 0.000 | 0 | 0.0000 | 0 | 0.00 | n/a | n/a | n/a | 0.0000 |
| `cal_dens_r0.350` | 0.35 | 5 | 1.631 | 2 | 0.0278 | 16 | 17.61 | 0.135 | -0.309 | 0.067 | 0.0652 |
| `cal_dens_r0.600` | 0.6 | 5 | 2.494 | 3 | 0.0059 | 5 | 11.78 | -0.355 | 0.136 | -0.439 | 0.0664 |
| `cal_dens_r1.00` | 1 | 5 | 4.004 | 4 | 0.0025 | 2 | 12.66 | 0.219 | 0.223 | -0.603 | 0.0384 |
| `cal_dens_r2.00` | 2 | 5 | 12.092 | 12 | 0.0002 | 1 | 2.21 | -0.050 | -0.920 | 0.437 | 0.0410 |
| `cal_dens_r20.0` | 20 | 5 | 82.234 | 83 | 0.0000 | 1 | 0.28 | 0.918 | 0.828 | 0.538 | 0.0419 |
| `cal_dens_r6.00` | 6 | 5 | 24.171 | 24 | 0.0001 | 1 | 0.82 | 0.872 | -0.179 | -0.637 | 0.0412 |
| `cal_duty_kd150.0` | 20 | 150 | 17.320 | 18 | 0.0000 | 1 | 0.28 | 0.918 | 0.828 | 0.538 | 1.1600 |
| `cal_duty_kd1800` | 20 | 1800 | 3.299 | 5 | 0.1807 | 410 | 2.43 | 0.579 | 0.253 | 0.225 | 3.8676 |
| `cal_duty_kd3600` | 20 | 3600 | 3.073 | 4 | 0.1226 | 478 | 1.15 | 0.713 | 0.365 | 0.357 | 4.9585 |
| `cal_duty_kd40.00` | 20 | 40 | 38.213 | 38 | 0.0000 | 1 | 0.28 | 0.918 | 0.828 | 0.538 | 0.3244 |
| `cal_duty_kd400.0` | 20 | 400 | 7.755 | 9 | 0.0559 | 105 | 5.33 | 0.398 | 0.010 | 0.068 | 2.4711 |
| `cal_duty_kd5.000` | 20 | 5 | 82.234 | 83 | 0.0000 | 1 | 0.28 | 0.918 | 0.828 | 0.538 | 0.0419 |
| `cal_duty_kd900.0` | 20 | 900 | 4.942 | 6 | 0.0687 | 188 | 2.04 | 0.598 | 0.225 | 0.178 | 4.0807 |

### S5.1 What the map establishes

1. **The density axis reproduces the parent at `rho = 20`** (mean `N_b` 82.2, median 83) and spans the
   requested regimes: `rho` 6 -> 24, 2 -> 12, 1 -> 4.0, 0.6 -> 2.5, 0.35 -> 1.6.
2. **A feasibility boundary exists and is sharp.** At `rho = 0.2` the filament never binds:
   `mean N_b = 0`, `v = 0.0000` exactly, zero gaps. **Gliding ceases entirely** — the Regime-F
   condition, reached between `rho = 0.35` and `rho = 0.2`.
3. **The duty axis lowers occupancy but simultaneously raises velocity ~100x** (`v = k_D (d + xi_A)`),
   from 0.042 µm/s at `k_D = 5` to 4.96 µm/s at `k_D = 3600`. **This is a confound the brief's design
   does not remove:** at matched `N_b` the duty-lowered filament also traverses target zones ~100x
   faster. Any density-vs-duty comparison must control for it, and the cleanest way is to compare at
   matched *zone-passage time* rather than matched `N_b`.
4. **Time-weighted and event-weighted occupancy diverge at high `k_D`.** At `k_D = 1800`,
   `mean N_b = 3.3` (time-weighted) but the event-weighted median is 5: most *time* is spent at low
   occupancy while most *events* occur at higher occupancy. Both are reported; neither alone
   characterises the regime.
5. **The preregistered density-vs-duty divergence is CONFIRMED on the angular channel.** At comparable
   low occupancy the density route has ~7x longer gaps and loses angular phase memory, while the duty
   route retains it:

| route | mean N_b | mean gap (ms) | C_Theta | C_X |
|---|---|---|---|---|
| density `rho = 0.35` | 1.63 | **17.6** | **0.135** | **-0.309** |
| duty `k_D = 1800` | 3.30 | **2.4** | **0.579** | **0.253** |
| high-occupancy reference `rho = 20` | 82.2 | 0.28 | 0.918 | 0.828 |

   The measured `C_Theta` values track the free-diffusion prediction of S4 closely: 17.6 ms predicts
   `exp(-489 x 0.0176) = 1.8e-4` (measured 0.135 on 16 gaps — consistent with zero at this sample
   size), and 2.4 ms predicts 0.31 (measured 0.579 on 410 gaps, the difference being that not all of
   the gap is spent fully unconfined).

**Interpretation, stated carefully.** These are single short arms with small gap counts in the density
arms (2–16 gaps), so the phase-memory numbers there carry large uncertainty and the negative `C_X`
values are not distinguishable from zero. What the map does establish firmly is the **ordering and
the scale**: gaps grow ~60x from the high-occupancy reference to the sparse density arms, and phase
memory falls monotonically with gap duration in the direction and by roughly the magnitude the
free-diffusion calculation predicts.

---

## S6. Selected production points (frozen)

Chosen from S5 and frozen in code (`VilfanLowOccHarness.REGIMES`) before any pilot arm runs:

| regime | axis | value | mean N_b | P(N_b=0) | mean gap (ms) |
|---|---|---|---|---|---|
| H | density | `rho = 20` | 82.2 | 3e-5 | 0.28 |
| M | density | `rho = 2` | 12.1 | 2e-4 | 2.2 |
| S1 | density | `rho = 0.35` | 1.63 | 0.028 | 17.6 |
| S1d | duty | `k_D = 1800` | 3.30 | 0.181 | 2.4 |

S1 and S1d are the matched-occupancy density-vs-duty pair. Regime H is re-measured here under the
repaired controller rather than taken from the parent (S2).

---

## S7. Instrumentation delivered (Stages 1–3)

`softbox/VilfanOccupancy.java`: full time-weighted `P(N_b = n)` for n = 0..5 plus tail; mean, median
and variance; `P(N_b = 0)`; zero-bound and one-bound interval counts and log-binned duration
histograms; occupancy transition counts (0->1, 1->0, 1->2, 2->1, up, down); attachment and detachment
counts conditional on occupancy; travel and net roll partitioned by `N_b` in {0, 1, 2, >=3}; and the
phase-memory measures `C_Theta`, `<sin dTheta>`, `C_X`, `C_joint` — globally and binned by gap
duration — plus first-post-gap versus continuously-tethered attachment statistics with their
before-centre fractions. The helical coupling `m` in `C_joint` is fixed from the lattice geometry and
is never fitted.

---

## S8. Paper-facing statement (do not over-generalise the parent)

> At high occupancy, collective motor stiffness suppresses axial and rotational Brownian motion and
> preserves the Vilfan mechanism. The low-occupancy regime tests whether this robustness survives when
> collective confinement is lost and zero-bound intervals permit free filament diffusion.

What this study can already say: the free-diffusion phase-memory times are **0.42 ms axial and 2.0 ms
angular**, and zero-bound gaps in the sparse density regime are **17.6 ms** — more than an order of
magnitude longer. Phase memory is therefore expected to be, and is measured to be, largely erased
there, while the duty-lowered route at comparable occupancy retains it. **Whether the depletion
mechanism itself follows the phase memory is exactly what the unrun pilot would establish**, and no
claim about it is made here.

---

## S9. What remains, and how to resume

```
./scripts/run_vilfan_lowocc.sh -scales     # preregistered confinement / memory scales
./scripts/run_vilfan_lowocc.sh -calib      # Stage 8 occupancy map            (DONE)
./scripts/run_vilfan_lowocc.sh -pilot      # Stage 9 two-seed mechanism pilot (NOT RUN)
```

| stage | work outstanding |
|---|---|
| gap timestamping | resolve gap boundaries below one substep, removing the 25–75 % over-estimate at high `k_D` (S3) |
| 9 | two-seed mechanism pilot at H / M / S1 / S1d, with shadow and independent-noise mirror arms |
| 10 | sizing for `Delta_depletion`, `<theta_A>`, torque, `Omega_odd`, `C_Theta`, `C_X`, and the density-vs-duty difference |
| 11 | production matrix, including axial-only / roll-only / combined arms at the sparse regimes |
| 4/5 | conditional mechanism analysis by instantaneous `N_b` and by gap history; matched-path shadows |
| 6 | steady drift versus intermittent burst characterisation |
| 7 | low-occupancy mirror correctness (axial noise even, roll noise odd) |
| 12 | classification A–G |

**A design point for whoever continues:** the duty axis raises velocity ~100x while lowering
occupancy (S5.1 item 3). Comparing density-lowered and duty-lowered arms at matched mean `N_b`
therefore confounds occupancy with zone-passage rate. Consider adding a third axis — lowering `k_A`
at fixed `k_D` and fixed density — which lowers occupancy while leaving velocity closer to native,
or compare at matched zone-passage time instead.

---
---

# Part II — decisive mechanism pilot

Continuation of the same study. Nothing below re-tunes the model: the Vilfan parameter freeze,
the lattice, the drag law, the noise construction and the roll controller repair of S2 are all
unchanged. Three numerical defects were found and fixed on the way, each reported here in full.

---

## S10. Substep-resolved gap timestamps (required prerequisite)

S3 recorded that the summed zero-bound gap time disagreed with the time-weighted `P(N_b = 0)` by
25–75 % at high `k_D`. The repair had two parts.

**S10.1 Gap boundaries are now exact event times.** Occupancy can only change at a chemical event, so
`VilfanOccupancy.transition` is called *at* the event with the exact event time and the exact
continuous `X`, `Theta`; `substep` no longer infers transitions by comparing `N_b` between accepted
substeps (which placed every boundary one substep late, over-estimating each interval by up to two
substeps). The terminal partial substep, previously discarded, is now accumulated.

**S10.2 A genuine accounting defect this exposed.** With exact boundaries the ratio was still
`0.912` at `k_D = 900`. The three clocks localise it exactly:

| clock | value (s) |
|---|---|
| residence, accumulated per substep | 0.4118009428 |
| interval durations, from event times | 0.3756809428 |
| gap durations, from the release/reattach edges | 0.3756809428 |

The last two agree to the last digit, so gap detection was correct and the *residence* clock was
long — by `0.03612 s`, which is exactly `7224` unsubdivided 5 us substeps. Cause: residence was
credited as it accrued, but an interval that never closes has its `tAcc` **discarded** by
`advanceRollStochastic`. That happens when the filament reaches the end of the motor field and the
total hazard drops to zero — and those substeps are almost all at `N_b = 0`, so they inflated
`P(N_b = 0)` while adding nothing to any gap. Residence is now **buffered per interval and committed
only when an event closes it**, and the interval straddling the warm-up boundary is credited to
neither clock.

**Gate OCC-1.** Preregistered tolerance: the two clocks are the same interval decomposition summed in
a different order, so the admissible deviation is floating-point summation error, `1e-12` relative —
not an empirical bound. Result: **ratio exactly 1, zero mismatched intervals, in every arm with at
least one gap**. Regime H has no gaps at all, where the gate is vacuous and is reported as such.

The gate is not tautological: it compares the gap-edge logic against the occupancy histogram through
two independent code paths, and in this exact form it is what caught the orphaned residence.

---

## S11. A blocker found while running: zero total hazard is not a terminal condition

Two S1 arms halted with `HALT: ktotal = 0 during overdamped advance`, one before the warm-up boundary.
This is on the study's critical path and was fixed rather than worked around.

At `rho = 0.35 /um` the 5.5 um filament has only `rho * l = 1.93` motors under it, so a Poisson field
leaves it **motor-free** `e^-1.93 = 15 %` of the time. With no motor in reach the total transition rate
is exactly zero, and the advance treated that as terminal. The correct piecewise-deterministic
semantics is that the cumulative hazard simply stops growing while the filament free-diffuses, with the
*same* exponential threshold `Hrem` still pending until a motor comes back within reach. The previous
behaviour truncated precisely the longest zero-bound intervals this study exists to measure.

Fixed in `advanceRollStochastic` (zero-hazard substeps are counted, not fatal) and in the pre-advance
guard, which now halts only on the quasi-static path, where there is no dynamics to carry the filament
out of a motor-free stretch. The count of motor-free substeps is reported per arm.

**S11.1 The bail existed in BOTH advance paths.** The axial-only and the roll/combined advance carry
independent copies of the substep loop. The first repair patched the axial path; the roll path — the one
every arm in this study actually uses — still bailed, and the same S1 arm still halted at t = 56.9 s
with the identical message. Fixed in both.

**S11.2 A second, subtler stall: a small but non-zero hazard.** With the exact-zero branch fixed the
arm *still* halted, with zero motor-free substeps recorded. The hazard in a motor-free stretch is not
exactly zero — it is the Gaussian tail, and at `rho = 0.35 /um` it can sit around `1e-3 /s`, so the true
waiting time is tens of seconds. The advance loop's fixed `20e6` substep guard corresponds to only ~50 s
at the 2.5 us motor-free substep, so the guard, not the physics, ended the run. The guard is now a bound
on **simulated time** (`Config.maxAdvanceTimeS`, default `1e4 s`) with a loop-count backstop. Integrating
those waits honestly is the point of the study: they are its longest zero-bound intervals.

A modest speedup is retained for the genuinely negligible case: when no head is bound and the total rate
is below `1e-9 /s`, the substep grows so the axial RMS increment stays within a quarter of the distance
to the nearest motor's reach edge, but never coarser than **2 nm** — well inside the lattice period
`a = 2.7 nm`, so an appreciable-hazard region cannot be stepped over. The band and recrossing quantisers
are excluded during those enlarged steps, because their resolution-independence requires the band to
greatly exceed the per-step RMS increment (parent S12), and the excluded time is reported
(`motorFreeTimeS`).

**Verification on the arm that failed.** `pil_S1_s101_mirror` previously halted at `t = 56.87 s`. It now
completes the full 120 s analysed window with no note, and the prediction is confirmed quantitatively:
**motor-free time 16.56 s of 120.13 s = 13.8 %** against the predicted `e^-1.93 = 15 %`. It records 443
zero-bound gaps, mean 76.7 ms, and a **maximum gap of 5.93 s** — three orders of magnitude beyond the
2.0 ms angular phase-memory time.

**Second, related fix.** The motor field was laid over `[-margin, travelCap + n_sites*a + margin]`, so a
*backward* diffusive excursion left it immediately. A configurable pad (`Config.fieldPadUm`, default 0,
so every earlier run is byte-unchanged) extends the field on both sides. It is enabled only for the
sparse regime — `sqrt(2 D_X t) ~ 4.7 um` over the 144 s window there — because at `rho = 20 /um` there
are ~110 motors under the filament at all times, a motor-free stretch is impossible, and the pad would
add ~800 motors to every `O(n_M)` per-substep scan for no effect.

---

## S12. Regime K: bounded `k_A` calibration and the frozen selection

Preregistered acceptance, fixed before the arms ran: mean `N_b` in [1, 3], and zone-passage time (or
velocity) within 2x of S1. Density and duty are held at `rho = 20`, `k_D = 5`.

| `k_A` | mean `N_b` | `P(N_b=0)` | mean gap (ms) | `C_Theta` | `C_X` | v (um/s) | zone passage (s) | verdict |
|---|---|---|---|---|---|---|---|---|
| 50 (native) | 80.79 | 0 | — | — | — | 0.0415 | 0.755 | reference |
| 5 | 33.99 | 0 | — | — | — | 0.0423 | 0.788 | `N_b` too high |
| 1 | 10.11 | 0 | — | — | — | 0.0410 | 0.925 | `N_b` too high |
| 0.5 | 5.20 | 0.014 | 45.5 | -0.089 | -0.312 | 0.0471 | 0.849 | `N_b` too high |
| 0.25 | 2.28 | 0.094 | 75.7 | -0.011 | -0.264 | 0.0009 | 4.127 | rejected: 5.4x zone passage |
| **0.12** | **1.40** | **0.216** | **140.2** | **-0.006** | **-0.175** | **0.0641** | **0.454** | **SELECTED** |
| 0.06 | 0.55 | 0.564 | 311.2 | 0.043 | 0.099 | -0.0170 | 1.705 | `N_b` too low |

`k_A = 0.12` passes on both preregistered channels: `N_b = 1.40` in [1, 3]; velocity `0.0641` against
S1's `0.0652 um/s` = **0.98x**; zone passage `0.454` against `0.767 s` = **1.69x**. Frozen in
`VilfanLowOccHarness.REGIMES` before any mechanism arm. No twirling or depletion quantity was
consulted in the selection.

**Structural finding, reported rather than tuned around.** Lowering `k_A` *lengthens* the zero-bound
gap (140 ms) instead of shortening it, so **K is not the "fast reattachment" control the brief
anticipated — and no such control exists in this model.** The reattachment flux while unbound is
`rho_reach * k_A`: `20 * 0.12 = 2.4` in K against `0.35 * 50 = 17.5` in S1. Low occupancy at native
velocity requires *low* attachment flux (via `rho` or `k_A`), short gaps require *high* attachment
flux, and the only lever that shortens bound lifetime without touching flux is `k_D`, which fixes the
velocity through `v = k_D (d + <xi_A>)`. The three constraints cannot be met together.

What K does deliver is better matched to the question than what was asked for: a **matched-occupancy,
matched-velocity** regime whose gap is **7x longer** than S1's. K versus S1 therefore isolates gap
duration — hence phase memory — at fixed occupancy and fixed velocity, which is the comparison the
phase-memory hypothesis actually needs.

---

## S13. Arm inventory, and why 12 arms carry the 24-arm design

The brief specifies 24 arms: 3 regimes x {native real, mirror real, native shadow, mirror shadow} x
2 seeds. The shadow is implemented as a **read-out on the real arm** rather than a separate run, so the
same information is delivered by 12 arms.

`noDepletionControl` evaluates the availability-blind attachment hazard for every motor under the
filament and accumulates it; it draws no random number, removes no motor from the pool and applies no
force. Enabling it therefore leaves the realised trajectory untouched, and the shadow is measured on
**the same realised `X` and `Theta`** as the real arm, labelled with that arm's own gap history and
occupancy state. This is strictly stronger than the separate-run design, which would have compared an
independently generated path; the brief's own requirement is that the shadow follow the same realised
trajectory.

Gate **MP-1** verifies the claim rather than asserting it: with the read-out on and off, every arm
quantity is bit-identical.

| regime | v | Omega | `<x_A>` | mean `N_b` | analysed events | winding | verdict |
|---|---|---|---|---|---|---|---|
| H | identical | identical | identical | identical | identical | identical | PASS |
| S1 | identical | identical | identical | identical | identical | identical | PASS |
| K | identical | identical | identical | identical | identical | identical | PASS |

Core arms: `pil_{H,S1,K}_s{101,102}_{native,mirror}`, native `latSign = -1`, mirror `latSign = +1`
with an independent noise seed (`seed + 500000`), warm-up 24 s and analysed 120 s of **fixed simulated
time** in every arm — no travel, turn, gap or attachment stopping criterion.

---

## S14. Pathwise mirror gate

Short arms (2 s warm-up, 6 s analysed), same seed, lattice mirrored, roll noise sign flipped
(mirror-odd) and axial noise left alone (mirror-even).

| regime | Omega native | Omega mirror | Omega_odd | Omega_even | verdict |
|---|---|---|---|---|---|
| H | -0.48008 | +0.48008 | -0.48008 | exactly 0 | PASS |
| S1 | +0.44112 | -0.44112 | +0.44112 | exactly 0 | PASS |
| K | -0.033844 | +0.033844 | -0.033844 | exactly 0 | PASS |

`Omega_even = 0.5 (Omega_nat + Omega_mir)` is **exactly** zero in double precision, which requires the
mirrored roll trajectory to be the bit-exact negation of the native one; `v`, mean `N_b` and `<x_A>`
are bit-identical (mirror-even) in all three regimes. The mirror map is exact, not approximate.

The S1 gate arm's own motor-field realisation happens to place `N_b = 3.66` with no zero-bound gap at
all, which is itself part of the S1 finding recorded in S15: at this density the occupancy is set by
where the ~7 motors happen to sit, not by the density alone.

---

## S15. The sparse regime is realisation-dominated, and what that forced

At `rho = 0.35 /um` the 5.5 um filament has `rho * l = 1.93` motors under it. That mean is small enough
that **the motor-field realisation, not the density, sets the arm's occupancy**. Across eight Poisson
fields at identical parameters:

| field | mean `N_b` | `P(N_b=0)` | gaps | mean gap (ms) | max gap (s) | motor-free % | v (um/s) |
|---|---|---|---|---|---|---|---|
| 101 | 2.37 | 0.054 | 635 | 41.0 | 2.76 | 1.2 | 0.0363 |
| 102 | 2.16 | 0.034 | 608 | 26.7 | 0.21 | 0.0 | 0.0358 |
| 103 | 0.74 | 0.452 | 898 | 241.8 | 81.5 | 38.5 | 0.0243 |
| 104 | 2.21 | 0.016 | 429 | 18.2 | 0.16 | 0.0 | 0.0427 |
| 105 | 0.69 | 0.334 | 1497 | 108.0 | 84.1 | 22.3 | 0.0252 |
| 106 | 1.48 | 0.204 | 820 | 119.5 | 25.6 | 14.8 | 0.0402 |
| 107 | 0.81 | 0.436 | 978 | 216.5 | 72.6 | 36.5 | 0.0236 |
| 108 | 1.54 | 0.203 | 667 | 150.2 | 35.2 | 16.3 | 0.0216 |

(native arms, 480 s each). Mean `N_b` spans **0.69 to 2.37**, `P(N_b = 0)` spans **0.016 to 0.45**, and
the longest zero-bound gap spans **0.16 s to 84 s** — one field left the filament unbound for a stretch
comparable to the whole analysed window. Motor-free time — no motor anywhere within binding reach —
runs from 0 % to 43 %.

**Consequence for the pilot.** The two briefed seeds, 101 and 102, both landed on dense realisations:
at 120 s they gave `N_b = 3.74` and `3.57` with **6 and 9 gaps**, against a required 100. The
first-post-gap sample was 6 and 9 attachments. That fails the S1 sufficiency gate, and it fails it for a
reason no amount of extra seeds of *noise* would fix, because both arms and both their mirrors sit on
the same two fields.

**What was run instead, and why it deviates from the brief's remedy.** The brief's remedy is to extend
all three regimes to the same longer fixed duration. This study extended the duration 4x (to 480 s) *and*
the number of field realisations to 8, **in S1 only**:

1. what limits S1 is the field, not the clock. A longer run on one field measures one local environment
   more precisely — the wrong quantity. (A longer run does help somewhat, because the filament translates
   through the field and averages over it: field 101 reads `N_b = 3.74` over 120 s and `2.37` over 480 s.
   That is an argument for doing both, which is what was done.)
2. Regime K costs ~3 h of CPU per 120 s of simulated time, so a matched 4x extension in all three regimes
   would have cost ~50 h for no gain in the load-bearing comparison, which is entirely within S1.
3. H and K already pass their own sufficiency gates at 120 s (H: 44,000-49,000 attachments against a
   required 10,000).

The extension is 16 arms — 8 fields x {native, independent-noise mirror on the SAME field} x 480 s — and
its gap counts are 370 to 1497 per arm. OCC-1 passes exactly on all 16.

**A second consequence, for how the mirror pairs were built.** Because the field dominates, an
independent-noise mirror must sit on the *same* field as its native partner. Driving placement from the
same seed as the noise gave an S1 native/mirror pair with `N_b = 3.74` against `0.72` — two different
physical situations, not one situation measured twice. `Config.fieldSeed` now separates the two streams
(default 0 = use `seed`, so every earlier run is unchanged), and every mirror arm shares its native
partner's field.

---

## S16. A reporting defect found in the reduction, not the model

The first conditional tables were internally inconsistent: the ">10 ms" gap bin showed a mean gap of
0.29 ms. Two causes, both now fixed, neither in the physics:

1. **Call order.** `attachment()` is invoked inside the transition-application block, *before*
   `transition()` closes the interval. `lastGapDur` therefore still held the PREVIOUS gap and
   `awaitingFirst` was stale, so every first-post-gap attachment was binned by the wrong gap. The gap
   being closed is `tEvent - gapT`, and `inGap` is the exact test for "this attachment ends a zero-bound
   interval"; both are now used.
2. **Mean-versus-sum.** `catXa`, `binXa`, `binGap` and `shadowXaByLabel` are stored in the record already
   divided by their counts, while `catXa2` / `binXa2` are raw sums of squares. The reduction divided a
   second time. Pooling across arms now re-multiplies each mean by its count.

Both defects were in instrumentation and reduction only; no arm was re-run for the second one.

---

## S17. Occupancy and phase memory at the three regimes

Native arms; H and K at 120 s, S1 pooled over the 8-realisation 480 s extension.

| regime | mean `N_b` | `P(N_b=0)` | gaps | mean gap (ms) | max gap (s) | motor-free % | `C_Theta` | `C_X` | v (um/s) |
|---|---|---|---|---|---|---|---|---|---|
| H | 74.2 / 82.6 | 0 exactly | 0 | — | — | 0 | undefined | undefined | 0.0416 / 0.0420 |
| S1 | 0.69 – 2.37 | 0.016 – 0.45 | 429 – 1497 | 18 – 242 | 0.16 – 84 | 0 – 43 | ~0.05 | ~0 | 0.022 – 0.043 |
| K | 1.26 / 1.38 | 0.297 / 0.248 | 222 / 216 | 161 / 138 | 0.83 | **0** | 0.081 / 0.083 | 0.037 / -0.045 |0.051 / 0.030 |

Regime H has **no zero-bound interval at all** in 120 s — `P(N_b = 0)` is exactly zero and every one of
its 93,451 attachments is continuously-tethered-multihead. The phase-memory measures are undefined
there, which is the cleanest possible statement of the parent condition.

The two low-occupancy regimes reach nearly the same mean occupancy by different routes and differ
sharply in one respect: **K never leaves the filament without a motor** (motor-free 0 %, because
`rho = 20 /um` puts ~110 motors under it at all times), whereas S1 spends **up to 43 %** of the window
with no motor anywhere in binding reach. Both have `C_Theta ~ 0.05-0.08`, i.e. angular phase memory is
essentially gone across a typical gap in both. That contrast is what makes the pair informative.

---

## S18. Regime H, re-measured under the repaired controller

| seed | `N_b` | attachments | `<x_A>` nm | shadow nm | Delta nm | sigma | before-centre % | `<theta_A>` | torque pN nm | Omega |
|---|---|---|---|---|---|---|---|---|---|---|
| 101 native | 74.2 | 44,059 | +1.5254 | -0.1028 | **+1.6282** | 40.6 | 58.53 | +0.0981 | -1.625 | -0.4966 |
| 101 mirror | 74.1 | 44,037 | +1.5218 | -0.1046 | **+1.6264** | — | 58.45 | -0.0977 | +1.618 | +0.5142 |
| 102 native | 82.6 | 49,392 | +1.6742 | -0.0443 | **+1.7185** | 45.3 | 58.97 | +0.1085 | -1.796 | -0.5453 |
| 102 mirror | 82.5 | 49,189 | +1.5733 | -0.0491 | **+1.6223** | — | 58.62 | -0.0999 | +1.655 | +0.5177 |

**H retains the parent mechanism**, re-measured rather than imported: before-centre **58.5-59.0 %**
against the parent's 58.78 %, torque **-1.63 to -1.80** against -1.759, `Omega_odd` **-0.505 / -0.532**
against -0.526. `<x_A>` is mirror-even and `<theta_A>`, torque and `Omega` are mirror-odd, with
`Omega_even` = +0.009 / -0.014, i.e. 2-3 % of the odd part on independent noise.

Rotation is **steady drift**: roll `R^2` = 0.9990, axial `R^2` = 1.0000, 20/20 blocks of the same sign,
half-window `Omega` -0.4945 / -0.4996 and -0.5455 / -0.5448. First/second-half consistency passes.

---

## S19. A pooling artefact, and the estimator that replaces it

Pooling all sparse-regime attachments gave an apparently decisive result: long-gap first-post-gap
before-centre 49.05 % against continuously tethered 52.41 %, a difference of -3.36 pp = **-5.82 sigma**.

**That contrast is an artefact of pooling and does not survive the correct estimator.** Within each arm
the long-gap fraction is remarkably stable — 46.7 % to 52.2 %, i.e. ~50 % everywhere — while the
tethered fraction swings from **18.95 % to 68.94 %** between motor-field realisations. Pooling
attachments across arms therefore compares a stable ~49 % against a count-weighted mixture of a wildly
varying quantity, and manufactures a difference that is present in no individual arm. It is a textbook
Simpson reversal, and the sign of the pooled contrast is set by which realisations happen to contribute
the most attachments.

The correct estimator is the **within-arm paired contrast**, which cancels the realisation offset
because both categories are measured on the same filament in the same motor field:

| regime | arms | paired before-centre difference | paired `<x_A>` difference |
|---|---|---|---|
| S1 | 16 | **-1.91 +- 3.53 pp = -0.54 sigma** | -0.215 +- 0.472 nm = -0.46 sigma |
| K | 4 | **-2.51 +- 2.12 pp = -1.19 sigma** | -0.003 +- 0.303 nm = -0.01 sigma |

**The load-bearing test is therefore NOT resolved in the direction the pooled number suggested.**
First-post-gap attachments after gaps longer than 10 ms are not distinguishable from continuously
tethered ones, in either low-occupancy regime.

---

## S20. What IS resolved: occupancy, not the gap

| regime | tethered before-centre, per-arm mean | per-arm sd | separation from H (58.75 %) |
|---|---|---|---|
| H | 58.75 % | — | reference |
| S1 | 51.23 +- 3.47 % | 13.86 pp | 2.16 sigma |
| K | **51.24 +- 0.89 %** | 1.77 pp | **8.42 sigma** |

Lowering occupancy from `N_b ~ 78` to `N_b ~ 1.3` collapses the depletion bias of *continuously
tethered* attachments from 58.75 % to **51.2 %**, and the two low-occupancy regimes agree to **0.01 pp**
despite reaching low occupancy by completely different routes. In K the collapse is resolved at
**8.4 sigma**; in S1 it is the same value but with 16x the per-arm scatter, so only 2.2 sigma.

This is the decisive comparison, because **K never leaves the filament without a motor**. If the loss
were caused by what happens during a zero-bound gap — phase decorrelation of `X` and `Theta` — K, whose
gaps are 138-161 ms with motors present throughout, ought to differ from S1, whose gaps run to 84 s
with up to 43 % of the window motor-free. They do not differ at all.

Two further observations point the same way:

- **There is no gradation with gap duration.** Attachments ending gaps of 0.5-2 ms, where `C_Theta ~ 0.5`
  and half the angular phase memory survives, show the same ~50 % before-centre fraction as those
  ending gaps beyond 10 ms, where `C_Theta < 0.01`. A phase-memory mechanism predicts a graded loss.
- **The downstream chain is unresolved in both low-occupancy regimes.** `Omega_odd` over 8 S1 mirror
  pairs is **+0.030 +- 0.305 rad/s = 0.10 sigma**, against H's tightly reproduced -0.52;
  `Omega_even` = +0.126 +- 0.182 (0.69 sigma) confirms mirror correctness at the ensemble level. Most
  sparse arms classify as **symmetric diffusion** or **intermittent signed bursts** rather than steady
  drift, so **no pitch is quoted for the sparse regime** — the trajectories are diffusion-dominated.

---

## S21. Pilot outcome: **P2**

> **P2 — LOW OCCUPANCY ATTENUATES DEPLETION WITHOUT A SPECIFIC GAP EFFECT.**
> S1 and K both lose depletion similarly; conditional first-post-gap and continuously tethered
> attachments are not clearly different; occupancy itself, not phase erasure, appears primary.

Every clause is met, and met sharply:

| P2 clause | evidence |
|---|---|
| S1 and K lose depletion similarly | tethered before-centre **51.23 %** (S1) and **51.24 %** (K) against H's 58.75 % — agreement to 0.01 pp by two different routes |
| first-post-gap and tethered not clearly different | paired contrast **-0.54 sigma** (S1, 16 arms) and **-1.19 sigma** (K, 4 arms) |
| occupancy, not phase erasure, is primary | K loses the same amount with **zero motor-free time**; no gradation with gap duration; the collapse is resolved at 8.4 sigma against occupancy |

The competing outcomes are excluded rather than merely unpreferred. **P1** requires the long-gap
first-post-gap contrast to be strongly reduced *relative to tethered* — it is not (-0.54 sigma), and its
apparent -5.8 sigma was a pooling artefact (S19). **P5** requires the mechanism to survive after long
gaps — the tethered bias itself is already collapsed, so there is no surviving chain to inherit.
**P3** would require depletion to survive while angular conversion fails — depletion does not survive.
**P6** is excluded: every regime sustains binding and directed travel, with no halts and 429-1497 gaps
per sparse arm. **P7** is excluded for the question actually asked: all five sufficiency gates pass, by
factors of 15x to 65x.

**The phase-memory hypothesis is not defended here.** The pilot was designed to disprove it if
first-post-gap attachments retained the tethered bias; instead it fails in the other direction — there
is no gap-specific effect to attribute to phase memory at all, because the bias is already gone from
the tethered attachments themselves once occupancy is low. The correct statement is that the parent
result's protective factor is **collective motor occupancy**, and that its loss is a property of the
instantaneous bound population, not of what the filament does while unbound.

---

## S22. Sizing the reduced production campaign

The design variable is the **number of motor-field realisations**, not run length: per-arm scatter is
set by which motors the filament sits on, and a longer arm on one field measures one local environment
more precisely.

| regime | per-arm sd, paired before-centre | arms for SEM 2 pp | SEM 1 pp | per-arm sd, `<x_A>` | arms for SEM 0.3 nm |
|---|---|---|---|---|---|
| S1 (480 s) | 14.10 pp | 50 | 199 | 1.887 nm | 40 |
| K (120 s) | 4.23 pp | 4 | 18 | 0.607 nm | 4 |

| regime | per-arm sd, tethered before-centre | arms for SEM 2 pp |
|---|---|---|
| S1 | 13.86 pp | 48 |
| K | 1.77 pp | 1 |

Cost: an S1 arm at 480 s is ~3 min of CPU; a K arm at 120 s is **~3.6 h** (at `rho = 20` with almost
every motor detached, the attachment-hazard sum runs over ~110 motors x 25 sites every substep).

**Recommended campaign — about 30 CPU-hours, not a full matrix:**

1. **S1 to 50 field realisations x 480 s, native + mirror** (~5 CPU-h). Brings the paired contrast to
   SEM 2 pp and the tethered-versus-H comparison to ~3.8 sigma, converting S20's 2.16 sigma into a
   resolved statement.
2. **K to 18 realisations x 120 s, native + mirror** (~26 CPU-h at 3.6 h/arm; halve it by dropping to
   9 realisations at SEM 1.4 pp). K is the decisive arm — it is the one that separates occupancy from
   gap effects — and it is already at 8.4 sigma, so this is confirmation, not discovery.
3. **An occupancy ladder** at `N_b` ~ 3, 6, 12, 25 on the `k_A` axis at fixed `rho = 20`, 4 realisations
   each, to locate where the 58.75 % bias falls to 51 %. This is the natural follow-on question and
   the pilot has made it cheap to ask.
4. **Do not** extend the gap-duration binning: with no gradation across three decades of gap duration,
   more statistics in those bins buy nothing.

---

## S23. Recommendation

Run step 1 and step 3 first; they are cheap and they convert the two soft numbers (S1's 2.16 sigma, and
the location of the occupancy threshold) into hard ones. Step 2 is confirmation of an already-resolved
result and can be deferred or halved.

The paper-facing statement from S8 should be revised. What the parent established is not that
"collective stiffness preserves the mechanism against Brownian motion" but something narrower and
better supported:

> Target-zone depletion is a property of the **instantaneous bound-motor population**. At high
> occupancy it is strong and drives a steady mirror-reversing twirl. Below `N_b ~ 1.5` it is
> attenuated to near-nothing, and equally so whether the motors are absent (sparse lawn) or merely
> slow to attach (low `k_A`) — the loss tracks occupancy, not the filament's freedom to diffuse while
> unbound.
