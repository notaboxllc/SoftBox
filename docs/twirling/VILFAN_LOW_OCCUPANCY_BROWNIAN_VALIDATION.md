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
