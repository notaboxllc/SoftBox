# Gliding-speed sensitivity to viscosity (aeta) — a 3×3 probe

**Date:** 2026-07-09 · **Runner:** GPU (canonical default; springs transcendental-free ⇒ GPU-trustworthy).
**Scope:** rough sensitivity probe, **not** a validated law — 3 viscosity points × 3 seeds, 0.6 s window.
**Operating point (fixed):** d1000, coltol 10 nm, M=60000 (dt=1e-5), the capstone gliding operating point.
**Lever:** `-aeta <Pa·s>` (filament/medium viscosity; default 0.1). A **diagnostic** knob — it rescales the
drag AND, FDT-consistently, the Brownian amplitude; it is **not a faithful speed lever** (cf. the dt-ceiling
finding). Used here only to measure drag sensitivity.

Raw: `RUN_LOGS/2026-07-09_aeta_sensitivity.txt` · drivers: the single seed-0 point was run inline; seeds 1–2
(0.05) and 0,1,2 (0.2) via `scripts/run_aeta_sensitivity.sh`.

## Result (mean ± SEM over 3 seeds)

| aeta (Pa·s) | vs default | **velFitX** (µm/s) | inst (µm/s) | avgBsteady | per-bound |
|---:|:--:|---:|---:|---:|---:|
| 0.05 | ½× | **3.266 ± 0.360** | 9.77 ± 0.26 | 2.598 ± 0.517 | 1.26 |
| 0.10 | 1× (baseline) | **2.825 ± 0.054** | 6.96 ± 0.01 | 3.317 ± 0.119 | 0.85 |
| 0.20 | 2× | **1.579 ± 0.078** | 4.87 ± 0.02 | 3.113 ± 0.132 | 0.51 |

Per-seed detail (velFitX): 0.05 → 2.662 / 3.908 / 3.228; 0.10 → 2.895 / 2.718 / 2.861;
0.20 → 1.470 / 1.537 / 1.730.

## What it says

**Net glide *is* drag-sensitive, and the sensitivity is REGIME-DEPENDENT — with a knee right at the default
operating point (η≈0.1):**

- **At/below the default (η 0.05 → 0.10):** WEAK. velFitX 3.27 → 2.83, exponent **p ≈ −0.2** (velFitX ∝ η^p).
  This reproduces the standing "drag-insensitive, cycle/tug-of-war-limited, v ∝ η⁻⁰·¹⁸" verdict.
- **Above the default (η 0.10 → 0.20):** STRONG. velFitX 2.83 → 1.58, exponent **p ≈ −0.84**, approaching the
  fully drag-limited η⁻¹ law.

So glide is **NOT a single power law** in viscosity: it is engagement/cycle-limited at low viscosity (the prior
finding holds there) but becomes **drag-limited at higher viscosity**. The endpoint-to-endpoint exponent
(−0.52) averages over the knee and hides it — do not quote it as "the" exponent.

### Supporting structure (which channel moves)
- **avgBound is ~viscosity-INDEPENDENT** (p ≈ +0.13, flat 2.6–3.3): engagement is set by kinetics/geometry, not
  drag. The velocity change is carried by *speed*, not *duty*.
- **inst ∝ η⁻⁰·⁵** cleanly (9.77 / 6.96 / 4.87, tiny SEM). This √-law is the signature of a
  **thermal-jitter-dominated** centroid speed (Brownian v ∝ √(kT/γ) ∝ η⁻⁰·⁵), which is why `inst` runs high
  (7–10) vs the directed `velFitX`. **Treat `velFitX` (LS-slope directed transport) as the real number; `inst`
  is jitter-contaminated** and not a clean transport metric.

## Caveats
- **The aeta=0.05 point is the soft one:** its SEM (±0.36) is inflated by a seed-0 low-engagement outlier
  (velFitX 2.66, avgB 1.57 vs ~3.2/3.6 for seeds 1–2). Excluding it, velFitX@0.05 ≈ 3.57, which makes the
  low-η half even flatter (p ≈ −0.3). The **η=0.10 and 0.20 points are tight**; the knee rests on those.
- **3 seeds / 0.6 s window / only 3 η-points** — enough to see the regime shift, not to pin the knee location.
- `-aeta` is a **diagnostic, non-faithful** lever (rescales the FDT Brownian amplitude too) — a sensitivity
  probe, not a claim about how fast the model *should* glide.

## Bottom line
Halving viscosity raises net glide only modestly (~+16 %, 2.83 → 3.27) while doubling it nearly halves it
(2.83 → 1.58). The asymmetry is the physics: **cycle-limited below the default, drag-limited above it.** This
**refines** (does not overturn) the standing "glide is drag-insensitive (η⁻⁰·¹⁸)" verdict — that holds
near/below η=0.1, but glide turns drag-limited at 2× viscosity.

**Follow-up if worth formalizing:** add η=0.4 (and maybe 0.025) to confirm the high-viscosity branch keeps
steepening toward η⁻¹ and to locate the knee; more seeds on the 0.05 point to kill the outlier.

---
---

# PART II — Solvent viscosity on the CURRENT explicit-S2 / discrete-site / linear-ramp motor

**Date:** 2026-07-27 · **Status:** Stages 0–2 COMPLETE and PASSING. Stages 3–5 (powered gliding and
twirling screens) NOT YET RUN. · **Runner:** GPU device-resident, monitored, no fallback (verified).
**Nothing about gliding or twirling viscosity sensitivity is claimed in this Part yet.**

> **Part I above (the 2026-07-09 three-point probe) is preserved as historical context and is NOT
> superseded in its own scope — but it does NOT transfer to the current motor.** It ran on the OLD
> `GlidingHarness` (springs path, dt = 1e-5), and its `-aeta` lever rescales the **filament drag only**
> (§3.2). It is a partial-rescale, filament-drag sensitivity probe, not a solvent-viscosity result.

## 1. The current scientific question

Whether the unusually high standing solvent viscosity (η₀ = `Constants.aeta` = 0.1 Pa·s, ~100× water)
mainly slows the simulation **clock**, or materially alters the gliding **mechanism** (capture,
mechanical relaxation, force sharing, attachment turnover) and the twirling **chirality** (rotation per
unit distance, generated vs retained chiral impulse). This is an **assay-fixture sensitivity study** —
not a gliding-speed calibration and not a twirling-strength optimisation. Viscosity is not a free
fitting parameter, and no canonical value is changed here.

## 2. Historical 0.05 / 0.10 / 0.20 probe

**Part I above, preserved verbatim.** Not superseded in its own scope, but it does **not** transfer to the
current motor: it ran on the old `GlidingHarness` (springs path, dt = 1e-5) and its `-aeta` rescales the
**filament drag only** (see §3.1). Treat it as a filament-drag sensitivity probe, not a solvent-viscosity
result, and do not quote its knee or exponents for the current motor.

## 3. Source-code viscosity and FDT audit (Stage 0 — COMPLETE, PASS)

### 3.1 There was NO coherent viscosity path, and none at all in the current assay

- `Constants.aeta = 0.1` is declared `public static final double`. That is a **compile-time constant**:
  javac **inlines** it at every use site, so a runtime override is impossible even by reflection. The
  only sound mechanism is **post-build scaling of the already-populated buffers**.
- **`ChiralSiteHarness` — the harness that drives the current explicit-S2 / discrete-site / linear-ramp
  assay — exposed no `-aeta` flag and no viscosity lever whatsoever.**
- The `-aeta` flags that do exist (`GlidingHarness`, `CrosslinkerBundleHarness`, `XlinkFormationHarness`,
  `FullSystemDemoHarness`) all call an `applyAeta(FilamentStore, …)` that scales **only the filament
  drag tensors**. Every motor-side drag stays frozen at 0.1 Pa·s. **That is a partial diagnostic
  rescaling and must not be called physical solvent viscosity.**

### 3.2 Every drag channel in the current assay, and whether it scales

All eight solvent-derived channels are built at scene-construction time from `Constants.aeta`, via
`DragTensorSystem` (filament + motor bodies) or an explicit Stokes expression (motor internals).

| # | Channel | Formula / source | Where it lives | Scales with η? |
|---|---|---|---|---|
| 1 | filament translational ∥ | `2πηL/(ln+a_par)` (`DragTensorSystem`) | `fil.bTransGam[0]` | yes |
| 2 | filament translational ⊥ | `4πηL/(ln+a_orth)` | `fil.bTransGam[nSeg]` | yes |
| 3 | filament axial roll | `4πηR²L` | `fil.bRotGam[0]` | yes |
| 4 | filament bend/tumble | `πηL³/(3(ln+a_turn))` | `fil.bRotGam[nSeg]` | yes |
| 5 | S2 beam-node | `6πη·R_node` | `params[16]` = `g4gammaNode` | yes |
| 6 | converter φ | `6πη·R_head·L_B² + …` | `params[8]` = `gammaPhi` | yes |
| 7 | head ψ | `8πη·R_head³ + 6πη·R_head·\|r_conv\|²` | `params[9]` = `gammaPsi` | yes |
| 8 | head roll | motor-body sphere `bRotGam` | `mot.body.bRotGam` | yes |

**No other damping exists in this assay.** `matZConfine` is a pure spring (`F -= k_z·z`), `g4kfloor` is a
floor **stiffness**, and the surface/steric/target-zone paths are geometric gates. There is **no
numerical damping not physically derived from solvent viscosity**.

**Only 3 of the 17 per-motor `params` entries are viscosity-bearing** (`[8]`, `[9]`, `[16]`); the rest
are stiffnesses, geometry, and dt. Both runners read them **per motor** (`params.get(8*nM+m)` etc.), so —
exactly as for the S2-length study — viscosity is a **DATA-ONLY** change: no kernel edit, no buffer
resize, no TaskGraph change.

### 3.3 Every Brownian channel, and why FDT is preserved for free

| Brownian channel | Amplitude as coded | Derives from |
|---|---|---|
| filament trans/rot | `params[1]·sqrt(bTransGam/bRotGam)`, `params[1]=sqrt(2kT/dt)` | the scaled γ |
| converter φ, head ψ | `brownTorque(gammaPhi/gammaPsi, dt, …)` = `sqrt(2kT·γ/dt)` | the scaled γ |
| S2 beam nodes | `brownTorque(g4gammaNode, dt, …)` | the scaled γ |
| head roll | `sqrt(2·kT·gam/dt)`, `gam = mot.body.bRotGam` | the scaled γ |

Every amplitude is constructed as `sqrt(2kT·γ/dt)` **from the same γ buffer** the viscosity change
scales. So a pure γ rescale keeps `D = kT/γ ∝ 1/η` **by construction** — no noise term is hand-scaled,
which is what the brief requires.

### 3.4 The coherent path that was built

`-eta <Pa·s>` (noncanonical, **default-off**) scales all eight channels by `r = η/η₀` in `applyEta(G)`,
inserted beside the existing `applyS2Lawn` data-only hook, before `packExMat` reads `paramArr`.
Stiffnesses are deliberately untouched; chemistry rates are per-second constants consumed as `k·dt`, so
they are fixed in **physical** time under the dt rescale. `r == 1` is an **exact early-return no-op**.

**Stage 0 gates — all PASS** (`run_chiral_sites.sh -eta-audit`):

| Gate | Result |
|---|---|
| all 8 drag channels exactly ∝ η at η = 0.10/0.05/0.02/0.01 | PASS (rel < 1e-5) |
| all stiffnesses/geometry invariant (`kF8`, `kconv`, `kbind`, `g4ks`, `g4kb`, `g4kfloor`, `g4l0`, `kz`) | PASS (exact) |
| η = 0.1 zero-feature identity (exact no-op) | PASS |

### 3.5 The load-bearing structural finding — two force-law families

The assay's force laws split into two families that respond to viscosity **differently**:

- **Class I — true Langevin / damping-limited.** S2 beam, φ/ψ, head roll, filament rigid-body
  translation and rotation, and the F8 spring's loading. Relaxation time `τ = γ/k ∝ η`. Correct under
  any dt.
- **Class II — the `fracMove` family.** Chain PAIRS F3 link + F4 torsion, and the F10 alignment torque.
  These are coded as `F = coeff·strain·γ_eff/dt`, so the displacement per step is `coeff·strain` — a
  **fixed fraction per step, independent of γ**. Their relaxation rate is `k/dt`, **viscosity-independent**.

**Consequence.** Under **fixed dt**, Class II channels do not respond to viscosity *at all* — a fixed-dt
low-viscosity run is a **different physical system** for those channels, not merely an under-resolved
one. Under the **mechanically similar timestep** `dt(η) = dt₀·η/η₀`, Class II relaxes at `k/dt ∝ 1/η`
and **matches Class I**. So the scaled-dt protocol is **required for physical coherence**, not merely
numerical hygiene — and this sharpens how the Stage-5 fixed-vs-scaled comparison must be read.

## 4. Dimensionless timestep audit (Stage 1 — COMPLETE, PASS)

With `dt(η) = dt₀·η/η₀` (2.5e-6 / 1.25e-6 / 5.0e-7 / 2.5e-7 s at η = 0.10 / 0.05 / 0.02 / 0.01), the
per-step relaxation factors `k·dt/γ` are **exactly invariant**:

| η | dt (s) | `kconv·dt/γφ` | `kbind·dt/γψ` | `ks·dt/γ_node` | `kb/l₀²·dt/γ_node` |
|---:|---:|---:|---:|---:|---:|
| 0.10 | 2.500e-06 | 0.52011 | 3.4561 | 111.41 | 0.19099 |
| 0.05 | 1.250e-06 | 0.52011 | 3.4561 | 111.41 | 0.19099 |
| 0.02 | 5.000e-07 | 0.52011 | 3.4561 | 111.41 | 0.19099 |
| 0.01 | 2.500e-07 | 0.52011 | 3.4561 | 111.41 | 0.19099 |

**The integrator's operating point is unchanged down to 0.01 Pa·s — no new numerical regime is entered.**
The two factors above 1 sit on the **linearly-implicit** beam-Newton channels (`s2Solve` assembles
`γ/dt` on the diagonal and solves), so they are not explicit-stability limits.

**The Brownian step does not grow.** The brief's concern that `RMS(δx) ~ sqrt(dt/η)` grows as η falls is
answered directly: the per-step Brownian displacement is `1e6·sqrt(2kT·dt/γ)·g`, and `dt/γ` is invariant
under the scaled dt, so the step size is **exactly invariant**. Under **fixed** dt it would grow ×3.16 at
η = 0.01.

## 5. Bare-filament drag and diffusion controls (Stage 2 — COMPLETE, PASS)

`run_chiral_sites.sh -eta-controls`. Motors never stepped in A–C; chemistry frozen in D; matched
physical duration; one rigid rod (no chain/joint confound). "vs" = measured ratio ÷ ideal scaling.

| η | dt (s) | ζ_axial (N·s/m) | vs 1/η | γ_roll (N·m·s) | vs 1/η | D_∥ (µm²/s) | vs 1/η | τ_S2 (s) | vs η |
|---:|---:|---:|:--:|---:|:--:|---:|:--:|---:|:--:|
| 0.10 | 2.500e-06 | 2.4030e-07 | 1.0000 | 3.2419e-23 | 1.0000 | 3.7338e-04 | 1.0000 | 4.1480e-04 | 1.0000 |
| 0.05 | 1.250e-06 | 1.2015e-07 | 1.0000 | 1.6210e-23 | 1.0000 | 7.4676e-04 | 1.0000 | 2.0740e-04 | 1.0000 |
| 0.02 | 5.000e-07 | 4.8068e-08 | 1.0002 | 6.4839e-24 | 1.0000 | 1.8669e-03 | 1.0000 | 8.2960e-05 | 1.0000 |
| 0.01 | 2.500e-07 | 2.4035e-08 | 1.0002 | 3.2419e-24 | 1.0000 | 3.7338e-03 | 1.0000 | 4.1480e-05 | 1.0000 |

- **(A) forced axial translation** — ζ ∝ η exactly; **(B) forced axial rotation** — γ_roll ∝ η exactly,
  Ω ∝ 1/η, read from the body-fixed roll observable.
- **(C) Brownian diffusion** — `D ∝ 1/η`. **Stated honestly: this is an exact scaling *identity*, not an
  independent statistical estimate of D.** Because the per-step Brownian displacement is invariant under
  the scaled dt (§4) and the RNG stream is shared, the realised path is identical and
  `D = ⟨x²⟩/(2·M·dt)` scales as `1/dt` exactly. As an *implementation-coherence* check that is stronger
  than a noisy statistical estimate; it is not a measurement of the physical D.
- **(D) passive S2-beam relaxation**, chemistry frozen, Brownian off — `τ ∝ η` exactly, confirming the
  motor-internal mechanical clock rescales end-to-end through the real beam solver.

## 6. Passive motor/S2 relaxation scaling

Covered by control (D) above: `τ_S2` = 414.8 / 207.4 / 82.96 / 41.48 µs at η = 0.10 / 0.05 / 0.02 / 0.01
— exactly proportional to η, i.e. the S2 mechanical relaxation clock is pure mobility.

## 7. Current gliding assay configuration

**NOT RUN.**

## 8. Gliding viscosity results

**NOT RUN.** No `v_even`, `v_odd`, trajectory-fit or blockwise velocity, pause/stall fraction, or
displacement variance exists. No claim is made.

## 9. Force, recruitment and turnover attribution

**NOT RUN.** No avgBound, bind/detach/stroke flux, residence times, or force-sharing attribution.

## 10. Twirling assay configuration

**NOT RUN.**

## 11. Torque, angular velocity and impulse results

**NOT RUN.** No `tauOdd`, `OmegaOdd`, accumulated odd roll, or `J_*_odd` phase budget.

## 12. Turns-per-distance and normalized scaling

**NOT RUN.** No `eta*v`, `eta*Omega`, `Omega/v` or `P_turn`.

## 13. Brownian/coherence control

**NOT RUN.** (Optional arm, contingent on the primary screen.)

## 14. Fixed-dt versus scaled-dt check

**NOT RUN.** Note §3.5: because the `fracMove` family is drag-independent at fixed dt, this comparison is
physics-vs-physics, not resolution-vs-resolution.

## 15. CPU/GPU and numerical health (Stage 2F)

Recorder verified active before all GPU work; every run through `run_gpu_monitored.sh`; all runs
**device-resident with no fallback**, all states finite, zero solver failures.

| assay | η | steps | site/bind mismatch | max\|dFilCoord\| | verdict |
|---|---:|---:|:--:|---:|:--:|
| gliding equiv | 0.10 | 200 | 0 / 0 | 1.19e-07 µm | PASS |
| gliding equiv | 0.05 | 200 | 0 / 0 | 5.96e-08 µm | PASS |
| gliding equiv | 0.02 | 200 | 0 / 0 | 5.96e-08 µm | PASS |
| gliding equiv | 0.01 | 200 | 0 / 0 | 5.96e-08 µm | **trips one gate** (below) |
| **twirl equiv** | 0.10 | 300 | 0 / 0 | 5.17e-08 µm | PASS |
| **twirl equiv** | **0.01** | **300** | **0 / 0** | 8.29e-08 µm | **PASS** |

**The η = 0.01 gliding-equiv anomaly, adjudicated.** One gate trips: the **absolute** tolerance
`max|dOmega| < 1e-5` on the *accumulated head-roll diagnostic* (1.37e-05, versus a flat 5.36e-06 at
η ≥ 0.02). Everything else is clean — `bindMism = 0`, `siteIdMism = 0`, `max|dAzim| = 0` (**every
decision channel exact**), coordinates at the float32 floor, all finite, device-resident.

`|Ω|max = 4.10 rad` is **identical at every η**, so this is *not* a representation-floor effect. The
**relative** divergence is 1.31e-06 for η ≥ 0.02 and 3.34e-06 at η = 0.01 — about **11 vs 29 float32
ULP** on an accumulator: bounded rounding on a **diagnostic readout**, not a decision.

**The gate was deliberately NOT relaxed to make the run pass.** It is recorded as a bounded,
reproducible, decision-exact anomaly. The weight of evidence says **η = 0.01 is numerically
trustworthy and needs no new integration architecture**:

1. Stage-1 dimensionless factors exactly invariant;
2. Stage-2 A–D exact at η = 0.01;
3. the **twirl** equivalence — the assay in which head roll is the *primary observable* — **PASSES at
   η = 0.01 on a longer horizon (300 steps) and cleaner than at η = 0.10** (`max|dCumRoll|` 6.06e-06 vs
   1.45e-05);
4. all gliding decision channels exact.

Note the analogous twirl quantity is **already** gated *relative* to its own magnitude in this same
file (`axTol = max(1e-25, 0.02·max|axialTau|)`), with a comment giving exactly this float32-floor
reasoning — so relative gating of an accumulated diagnostic is established practice here, not a
convenience invented to pass this run.

**Lowest numerically trustworthy viscosity: 0.01 Pa·s** (the brief's floor for this task). Descent to
0.003 / 0.001 Pa·s remains **out of scope** and is not recommended on this evidence alone.

## 16. Decision classes V1-V6

**NONE ASSIGNED.** Every class (V1 mobility/time-rescaling, V2 gliding mechanochemistry, V3 twirling
mechanochemistry, V4 coherence/noise, V5 low-viscosity numerical confound, V6 weak sensitivity) requires
powered data from Stages 3-5. Assigning one now would be unsupported.

Partial evidence exists for **V5 only in the negative**: the numerical-confound class is *not* triggered
down to 0.01 Pa*s (Stage 1 exactly invariant, Stage 2 exact, twirl CPU/GPU clean) - so a low-viscosity
result, when measured, will not be dismissible as an integration artifact at these viscosities.

## 17. Biological and assay interpretation

**DEFERRED** until Stages 3-5 produce results.

## 18. Exact next recommendation

Run **Stage 3 (gliding screen)** first: 4 viscosities x 8 matched seeds x both epsilon signs, scaled dt,
matched 20 ms physical duration, GPU device-resident. ~2.4 h. Then Stage 4 (twirling) and Stage 5
(fixed-dt vs scaled-dt), since the twirling interpretation depends on the gliding `eta*v` result.
Do **not** extend to 24 seeds except under the brief's adaptive-powering trigger.

## 19. Experiments deliberately not run (so far)

- **Stages 3–5 (the powered gliding and twirling screens) — NOT RUN.** No viscosity sensitivity of
  gliding or twirling is claimed anywhere in Part II.
- η = 0.003 / 0.001 Pa·s — excluded by the brief for this task.
- Any change to the canonical viscosity — explicitly out of scope.
- Re-running Part I's assay: the old `-aeta` is a filament-only rescale and is not worth powering.
