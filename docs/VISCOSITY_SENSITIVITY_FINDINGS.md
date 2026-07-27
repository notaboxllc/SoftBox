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

**Date:** 2026-07-27 · **Status:** Stages 0–2 COMPLETE and PASSING; the **viscosity premise test
COMPLETE** (one gliding assay, 4 η × 8 seeds × both ε signs, 64/64 records, 0 invalid, 1.85 h).
· **Runner:** GPU device-resident, monitored, no fallback (verified).

> **HEADLINE — the premise is CONFIRMED, but not as mobility scaling.** Gliding **speed** is nearly
> viscosity-insensitive (`v ∝ η^−0.203`: a 10× drop in viscosity buys only **1.60×** in speed), yet
> `η·v` collapses 6.3× at **7.84σ**, so pure mobility rescaling (V1) is **refuted**. The gliding
> **mechanism** *is* viscosity-sensitive — engagement +126 %, attachment flux +184 % — carried by
> attachment **flux**, not residence (pre-stroke lifetime is viscosity-invariant in physical time).
> **Twirling is where viscosity really bites:** at the canonical η₀ = 0.1 the twirl is **unresolved
> (0.61σ)**; by η = 0.02 it is 3.83σ with 100 % seed sign agreement, and turns-per-µm rises **14×**.
> **CONFIRMED AT n = 24:** turns-per-µm differs by **−2.347 ± 0.661 (3.55σ)**, and the η₀ twirl is
> **still unresolved (1.06σ, 58 % seed sign)** — at the canonical viscosity twirling is not merely weak,
> it is **undetectable with 24 seeds**. The mechanism is clean: **rotation is drag-limited
> (`Ω ∝ η^−1.2`, τ_odd flat) while translation is not (`v ∝ η^−0.232`)**, so turns-per-distance rises as
> the ratio — **mobility, not chirality**. Pre-stroke lifetime is viscosity-invariant in physical time to
> **0.2 %**. **⇒ V1 refuted for gliding, V2 confirmed, V3 NOT supported (twirling obeys V1), V5 excluded,
> V6 refuted.** The mirror control at η = 0.01 remains required before crediting a *chiral* origin.
> **No canonical value is changed.**

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

## 7. Current gliding assay configuration (the SINGLE assay — both phenotypes)

**One assay answers both questions.** Gliding is the ε-EVEN response and twirling the ε-ODD response of
the *same* runs — the S2-study pattern. **No separate twirling scene was run**, which both halves the
compute and removes a confound: any twirling difference cannot be an artifact of a different scene.

Canonical gliding scene, unmodified: 12-segment filament, filament Brownian **ON**, homogeneous free
S2 = **40 nm**, density **400 heads/µm²**, N = 1200, discrete actin sites + surface bond, **linear**
converter-skew ramp, target-zone OFF, no roll spring, 25 % equilibration, **8 matched seeds**, **both ε
signs**, GPU **device-resident** (monitored, no fallback). Nothing in the frozen model was touched.

**Matched PHYSICAL duration, not matched steps** — `dt(η) = dt₀·η/η₀` **and** `steps(η) = steps₀·η₀/η`,
so every arm simulates the same **20 ms** and the dimensionless integration factors stay invariant (§4):

| η (Pa·s) | dt (s) | steps | simulated |
|---:|---:|---:|---:|
| 0.10 | 2.5e-06 | 8 000 | 20 ms |
| 0.05 | 1.25e-06 | 16 000 | 20 ms |
| 0.02 | 5.0e-07 | 40 000 | 20 ms |
| 0.01 | 2.5e-07 | 80 000 | 20 ms |

64/64 records (4 η × 2 ε × 8 seeds), 0 reused, **0 invalid, 0 solver failures**, 1.85 h wall.
Raw: `RUN_LOGS/2026-07-27_eta_premise_map.txt`. Driver: `run_chiral_sites.sh -eta-map`.

## 8. Gliding viscosity results

| η | v_even ± SEM (µm/s) | σ | v/v(η₀) | **pure-drag would be** | **η·v_even** |
|---:|---:|---:|---:|---:|---:|
| 0.10 | −2.915 ± 0.295 | 9.87 | 1.000 | 1.00 | 0.2915 |
| 0.05 | −3.692 ± 0.338 | 10.93 | 1.266 | 2.00 | 0.1846 |
| 0.02 | −4.364 ± 0.201 | 21.69 | 1.497 | 5.00 | 0.0873 |
| 0.01 | −4.652 ± 0.273 | 17.01 | **1.596** | **10.00** | **0.0465** |

**Gliding speed is nearly viscosity-INSENSITIVE.** A **10× drop in viscosity buys only 1.60× in speed**
— `v ∝ η^−0.203`, nowhere near the `η^−1` of a drag-limited system. The sign is preserved and every
arm is highly significant individually.

Consequently **η·v_even is NOT flat — it collapses 6.3×**, and the paired per-seed test against η₀ is
overwhelming: **−0.107 ± 0.022 (4.84σ)**, **−0.204 ± 0.029 (7.14σ)**, **−0.245 ± 0.031 (7.84σ)** at
η = 0.05 / 0.02 / 0.01. **Pure mobility rescaling (class V1) is refuted at 7.8σ.**

This **independently reproduces the standing "glide is cycle/tug-of-war-limited, not drag-limited"
verdict** (`v ∝ η^−0.18`) — and it **confirms Part I's low-viscosity branch** (p ≈ −0.2 at/below 0.1)
*despite* Part I's lever being filament-only. Part I's claimed **knee above 0.1 was not tested here** and
remains unverified for the current motor.

## 9. Force, recruitment and turnover attribution

| η | avgBound | strokes/s | episode rate /s | preLife (steps) | postLife (steps) |
|---:|---:|---:|---:|---:|---:|
| 0.10 | 2.778 | 3 658 | 3 442 | 41.0 | 255.4 |
| 0.05 | 3.434 | 4 633 | 4 383 | 85.2 | 483.5 |
| 0.02 | 4.520 | 7 000 | 6 717 | 202.6 | 1 050.7 |
| 0.01 | **6.284** | **10 125** | **9 767** | 397.4 | 2 010.5 |

**Engagement and flux are strongly viscosity-sensitive** — avgBound **+126 %** (`η^−0.355`), strokes/s
**+177 %** (`η^−0.442`), episode rate **+184 %** (`η^−0.453`) across the 10× span.

**Lifetimes converted to PHYSICAL time (the columns above are in steps, and steps ≠ time across arms):**

| η | preLife (µs) | postLife (µs) | total residence (µs) |
|---:|---:|---:|---:|
| 0.10 | 102.5 | 638.5 | 741.0 |
| 0.05 | 106.5 | 604.4 | 710.9 |
| 0.02 | 101.3 | 525.4 | 626.7 |
| 0.01 | 99.4 | 502.6 | 602.0 |

**Pre-stroke lifetime is viscosity-INVARIANT in physical time (~100 µs, ±4 %)** — it is chemistry-limited,
exactly as it should be with rates fixed per second. **Post-stroke lifetime falls modestly (−21 %)** —
the mechanically-limited phase, consistent with faster strain relaxation permitting earlier detachment.

**⇒ The carrier is attachment FLUX, not residence.** Residence *falls* 19 % while avgBound *rises* 126 %,
so the bound population can only be rising because heads attach ~2.8× more often per second — matching
the measured 2.84× episode rate. Faster mechanical relaxation at low viscosity lets heads reach binding
sites sooner, which is coherent with the standing "recruitment is REACH-limited, not angular-gate-limited"
finding.

**Caveat, stated plainly:** the stored records carry no propulsive/opposing force decomposition, so the
natural reading — that 2.3× more bound heads yields only 1.6× more speed because the extra recruits are
substantially *dragging* rather than propulsive (the force-velocity tug-of-war) — is **consistent with
these data but not demonstrated by them.** Confirming it needs the force columns added to the record.

## 10. Twirling assay configuration

**Identical runs to §7** — twirling is the ε-ODD component, `Ω_odd = ½(Ω(+ε) − Ω(−ε))`, filament Brownian
ON, converter skew ±15°. Because both phenotypes come from the *same* trajectories, no cross-scene
confound is possible.

## 11. Torque, angular velocity and impulse results

| η | τ_odd ± SEM (N·m) | Ω_odd ± SEM (rad/s) | σ | seed-sign % | J_total_odd |
|---:|---:|---:|---:|---:|---:|
| 0.10 | −1.564e-22 ± 2e-22 | **−4.01 ± 6.54** | **0.61** | 75 | −6.84e-26 |
| 0.05 | −5.453e-22 ± 1e-22 | −24.60 ± 9.71 | 2.53 | 63 | −1.16e-25 |
| 0.02 | −3.574e-22 ± 9e-23 | −53.24 ± 13.89 | **3.83** | **100** | −4.25e-26 |
| 0.01 | −2.727e-22 ± 1e-22 | −91.34 ± 39.84 | 2.29 | 75 | −2.97e-26 |

**At the canonical viscosity the twirl is NOT RESOLVED (0.61σ; Ω_odd = −4.01 ± 6.54 is consistent with
zero).** It becomes strongly resolved as viscosity falls (2.53σ / 3.83σ / 2.29σ), with the negative sign
preserved throughout and 100 % seed agreement at η = 0.02.

**Ω_odd rises 22.8× across a 10× viscosity drop — MORE than pure mobility would give (10×)**,
`Ω ∝ η^−1.358`.

**But τ_odd and J_total_odd show NO clean monotone trend** — the largest |τ_odd| is at η = 0.05, and
J_total_odd is flat-to-noisy. **The generated/retained chiral impulse is not demonstrably increasing.**

## 12. Turns-per-distance and normalized scaling

| η | η·v_even | η·Ω_odd | Ω_odd/v_even | **turns per µm** |
|---:|---:|---:|---:|---:|
| 0.10 | 0.2915 | −0.4014 | 1.377 | −0.2192 |
| 0.05 | 0.1846 | −1.2299 | 6.663 | −1.0605 |
| 0.02 | 0.0873 | −1.0647 | 12.199 | −1.9415 |
| 0.01 | 0.0465 | −0.9134 | 19.634 | **−3.1249** |

Paired per-seed differences vs η₀: **d(turns/µm)** = −0.635 ± 0.636 (**1.00σ**), −1.639 ± 0.672
(**2.44σ**), −3.188 ± 1.570 (**2.03σ**) at η = 0.05 / 0.02 / 0.01.

**Lower viscosity increases twirling BOTH in rad/s AND per unit distance** — turns-per-µm rises **14×**
(`η^−1.154`), resolved at η ≤ 0.02 (2.0–2.4σ) though not at 0.05. This is the brief's **case 3**:
Ω rises *more* than v.

**Answering brief question 4 — mobility, generated torque, or both?** **Predominantly MOBILITY**, plus
the engagement rise. Ω tracks `τ/γ_roll` with `γ_roll ∝ η`, while τ_odd itself shows no monotone
viscosity trend. The twirl is **not** limited by a viscosity-dependent deficit in generated chiral
torque; it is limited by rotational drag, and secondarily lifted by the 2.3× larger bound population.

## 12b. CONFIRMATION AT n = 24 (the adaptive-powering extension)

The brief's adaptive trigger was met for **twirling only** (gliding was already 4.8–7.8σ). η = 0.10 and
η = 0.01 were extended to **24 matched seeds**; the 32 existing 8-seed records were reused, 64 newly run,
96/96 total, **0 invalid, 0 solver failures**, 2.23 h.
Raw: `RUN_LOGS/2026-07-27_eta_twirl_n24.txt`.

**The twirling effect HELD AND SHARPENED — it did not regress.**

| quantity | n = 8 | **n = 24** | verdict |
|---|---:|---:|---|
| **d(turns per µm)** vs η₀ | −3.188 ± 1.570 (**2.03σ**) | **−2.347 ± 0.661 (3.55σ)** | **CONFIRMED** |
| Ω_odd @ η = 0.01 | −91.34 ± 39.84 (2.29σ) | **−76.99 ± 16.25 (4.74σ)** | sharpened |
| Ω_odd @ η = 0.10 | −4.01 ± 6.54 (0.61σ) | −4.89 ± 4.60 (**1.06σ**) | **still UNRESOLVED** |
| seed-sign @ η = 0.01 | 75 % | 83 % | strengthened |
| d(η·\|v_even\|) vs η₀ | −0.245 ± 0.031 (7.84σ) | **−0.240 ± 0.014 (17.07σ)** | overwhelming |

The central value of d(turns/µm) moved −3.19 → −2.35 (−26 %) while the SEM tightened 2.4×, which is the
signature of a **real effect being measured more precisely**, not of a small-sample artifact decaying.

**The η = 0.10 twirl remains consistent with zero even at n = 24** (1.06σ, 58 % seed sign — a coin flip).
This is now a strong statement rather than an underpowered one: **at the canonical viscosity the twirling
phenotype is not merely weak, it is undetectable with 24 seeds**, while at η = 0.01 the same measurement
on the same scene reaches 4.74σ.

### The mechanism, now decisive

| η | v_even (µm/s) | avgBound | strokes/s | preLife (µs, PHYSICAL) | postLife (µs) | turns per µm |
|---:|---:|---:|---:|---:|---:|---:|
| 0.10 | −2.895 ± 0.135 | 3.033 | 3 867 | **99.25** | 650.5 | −0.2690 |
| 0.01 | −4.942 ± 0.145 | 5.822 | 9 450 | **99.45** | 498.0 | −2.4794 |

- **Pre-stroke lifetime is viscosity-invariant in physical time to 0.2 %** (99.25 vs 99.45 µs) — a
  near-exact confirmation of the n = 8 reading, and precisely what a chemistry-limited phase must do.
  Post-stroke falls 23 %, total residence falls 20 %, while avgBound rises 92 % ⇒ **the carrier is
  attachment flux** (episode rate +149 %), not residence.
- `v ∝ η^−0.232` (1.71× over a 10× span) — translation is **NOT** drag-limited.
- Ω_odd is consistent with `τ_odd/γ_roll` and `γ_roll ∝ η` — rotation **IS** drag-limited.
- **τ_odd (−1.6e-22 → −2.3e-22, overlapping) and J_total_odd (−4.2e-26 → −2.2e-26) still show no
  monotone increase** ⇒ **no evidence of increased generated chiral impulse.**

**⇒ The clean mechanistic statement:** *rotation is drag-limited while translation is not.* Lowering
viscosity therefore buys far more rotation (`η^−1.2`) than translation (`η^−0.23`), and turns-per-distance
rises as the **ratio** of those two exponents (`η^−0.97`). The twirl gain is **mobility**, not chirality.

**Still required before this twirling result is credited:** the **mirror control** at η = 0.01 (§18) —
the signed twirling quantities must reverse on a mirrored actin lattice. Until that is run, the
per-distance twirling result is **confirmed as a measurement but not yet established as chiral in origin.**

## 13. Brownian/coherence control

**NOT RUN** (optional arm). Not needed for the premise verdict: the *mean* normalized quantities moved
far beyond noise, so this is not a class-V4 coherence artifact. `rollR2` is recorded per run if a
coherence analysis is later wanted.

## 14. Fixed-dt versus scaled-dt check

**NOT RUN.** Per §3.5 this comparison is **physics-vs-physics, not resolution-vs-resolution**: at fixed
dt the `fracMove` family is drag-independent, so a fixed-dt arm would hold part of the model's mechanics
frozen against viscosity. The scaled-dt protocol used here is the coherent one; a fixed-dt arm would
measure a *different* system and is **not** required to defend these results.

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

**The premise is CONFIRMED: there IS real viscosity dependence, and it is NOT mere clock rescaling.**
Different classes apply to the two phenotypes.

| Class | Verdict | Evidence |
|---|---|---|
| **V1** mobility/time-rescaling dominant | **REFUTED (decisively)** | η·v collapses 6.3× at **7.84σ**; η·Ω not flat; engagement +126 %, flux +184 % |
| **V2** gliding mechanochemistry viscosity-sensitive | **CONFIRMED** | avgBound `η^−0.355`, strokes/s `η^−0.442`, episode rate `η^−0.453`, η·v at 7.84σ |
| **V3** twirling mechanochemistry viscosity-sensitive | **NOT SUPPORTED — the effect is V1-for-rotation** | turns-per-µm confirmed at **3.55σ (n=24)**, but τ_odd and J_total_odd show **no monotone trend** ⇒ the gain is **rotational mobility**, not generated chiral impulse. The *mechanochemistry* of twirling is NOT shown to be viscosity-sensitive |
| **V4** coherence/noise only | **NOT the explanation** | mean normalized quantities moved far beyond noise |
| **V5** low-viscosity numerical confound | **EXCLUDED to η = 0.01** | 0 invalid, 0 solver failures across 64 runs; Stage-1 factors exactly invariant; Stage-2 controls exact; CPU/GPU clean. *Caveat: the fixed-dt arm was not run — see §14 for why it is not required.* |
| **V6** weak sensitivity below 0.1 | **REFUTED** | sensitivity is strong and resolved throughout 0.10 → 0.01 |

**The sharpest way to state the whole result (n = 24).** The two phenotypes sit in *different* classes
because they are limited by different things:

- **Rotation is drag-limited** (`Ω ∝ η^−1.2`, τ_odd flat) ⇒ twirling obeys **V1 mobility scaling**.
- **Translation is NOT drag-limited** (`v ∝ η^−0.232`, engagement +92 %, flux +149 %) ⇒ gliding is **V2**.
- **Turns-per-distance is the RATIO of the two**, so it rises `η^−0.97` at **3.55σ** — a real, confirmed
  change in rotation per unit distance that is nonetheless **entirely accounted for by the two different
  limiting regimes**, with no appeal to viscosity-dependent chirality.

**The subtlety worth stating explicitly.** Two true things that sound contradictory:
gliding **SPEED** is nearly viscosity-insensitive (`η^−0.203` — 10× less drag buys 1.6× speed), yet the
gliding **MECHANISM** is strongly viscosity-sensitive (engagement, attachment flux, stroke flux all
change by 2.3–2.8×). Viscosity is *not* just the clock, but neither is the system drag-limited: the
extra recruitment is largely absorbed rather than converted into transport.

## 17. Biological and assay interpretation

1. **η₀ = 0.1 Pa·s does NOT materially bias the model's gliding-SPEED conclusions.** Speed varies only
   1.6× over a 10× viscosity span, so gliding-velocity claims are robust to the fixture viscosity.
2. **It DOES materially bias engagement, attachment flux and stroke flux** — avgBound at 0.1 Pa·s is
   **2.3× lower** than at 0.01. Any claim about duty ratio, recruitment or bound population is
   viscosity-fixture-dependent and should be quoted with its η.
3. **It actively SUPPRESSES the twirling phenotype below detectability.** At η₀ the twirl is
   **unresolved (0.61σ)**; at η ≤ 0.02 it is strongly resolved with 100 % seed sign agreement. **0.1 Pa·s
   is a poor operating point for studying twirling**, and past inability to resolve twirling at the
   canonical viscosity is at least partly a *mobility* limitation, not evidence of weak chirality.
4. **Mechanistically**, lower viscosity acts through **reach/search speed**: pre-stroke lifetime is
   viscosity-invariant in physical time (chemistry-limited), while attachment flux rises 2.8×. This is
   consistent with the standing "recruitment is REACH-limited" finding.
5. **Biological framing.** Water is ~1e-3 Pa·s; this study reaches 0.01, still 10× above water and 10×
   below the fixture value. The trends are monotone with no sign of saturation at 0.01, so the fixture
   viscosity — not any intrinsic motor property — sets the twirling observability.

**No canonical value is changed by this study, and none is recommended on this evidence** (§18).

## 18. Exact next recommendation

**Gliding: SETTLED.** 17.07σ at n = 24. No further work.

**Twirling: the per-distance effect is CONFIRMED (3.55σ) but its CHIRAL ORIGIN is not yet established.**
The one remaining gating experiment is the **mirror control at η = 0.01**: run the same scene on a
**mirrored actin lattice** (`TArm` already carries a `mirror` field; the eta-map arms currently pass +1)
and require the signed twirling quantities to **reverse**. Ω_odd = −76.99 ± 16.25 at n = 24 implies a
mirror arm at **n = 8** would land near **+77 ± 28 (≈2.7σ)** — adequate for a *sign* test, at
**≈1 h** GPU. n = 24 would cost ≈3.1 h and is not needed for a sign test.

Until that runs, report the twirling result as **"a confirmed viscosity dependence of rotation per unit
distance, of mobility origin"** — and NOT as evidence about the motor's chirality.

**Lower priority:** add propulsive/opposing force columns to test the §9 tug-of-war reading directly;
probe η > 0.1 to check Part I's claimed knee on the current motor.
**Do NOT** descend below 0.01 Pa·s on this evidence.

## 19. Experiments deliberately not run

- **The separate twirling scene (the brief's Stage 4) — deliberately DROPPED**, not skipped. Twirling is
  the ε-ODD phenotype of the *same* gliding runs, so one assay answers both questions; this halved the
  compute and removed a cross-scene confound.
- **Fixed-dt vs scaled-dt (Stage 5)** — see §14: at fixed dt the `fracMove` family is drag-independent, so
  that arm measures a *different* system. Not required to defend these results.
- **Brownian-ON/OFF coherence control (§13)** — unnecessary; the mean effects far exceed noise.
- **Extension to 24 seeds — RUN** for twirling (η = 0.10 and η = 0.01); see §12b. Gliding needed none.
- **The twirling mirror control — NOT YET RUN.** It is now the single gating experiment (§18): the
  per-distance twirling effect is confirmed as a *measurement*, but its **chiral origin is unestablished**
  until the signed quantities are shown to reverse on a mirrored lattice.
- **η > 0.1** — Part I's claimed knee above the canonical value is **untested on the current motor**.
- **η = 0.003 / 0.001 Pa·s** — excluded by the brief for this task; not recommended on this evidence.
- **Force decomposition (propulsive vs opposing)** — not in the record; the §9 tug-of-war reading is
  consistent with but not demonstrated by these data.
- **Any change to the canonical viscosity** — explicitly out of scope, and not recommended.
