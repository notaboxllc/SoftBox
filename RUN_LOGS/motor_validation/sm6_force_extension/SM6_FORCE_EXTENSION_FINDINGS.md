# SM6 — Quasistatic force-extension of the explicit S2 motor and HMM dimer

**Status: COMPLETE. Single-head 1341 points + hysteresis; dimer 1217 points. 0 solver failures.**
**CPU-only, deterministic (Brownian OFF), chemistry FROZEN. Code rev `73d82bf`, build `e667650b54f9`.**
**NO mechanics parameter was retuned.**

---

## 1. Method

Displacement control. A bound motor is built through the centralized builder, its nucleotide state
and converter target `thetaS` are pinned (the chemistry kernel is **never called**), and the system is
relaxed deterministically. Each point then imposes a displacement and re-relaxes to equilibrium:

- **single head** — the filament is pinned with the existing `filFullClamp` (position *and*
  orientation) at `c0 + d·axis`; reaction force is the F8 force on the segment, projected on the axis.
- **dimer** — there is no position clamp on the dimer path, so displacement is imposed on the
  **actin anchor** of the fixed-actin spring and the 19-DOF forked beam is relaxed with
  `ExplicitHmmDimer.solve(..., brownian=false)`. Dimer curves therefore include the F8 spring
  (1 pN/nm) **in series**; reported dimer stiffnesses are SERIES stiffnesses. This is stated rather
  than removed, because stiffening the bond would have meant retuning mechanics.

Convergence: relaxation to `maxMove < 3e-7 µm`, and the whole single-head sweep was run at both 400
and 4000 relax steps with agreeing results (k(0) = 0.9906 both times) — the curves are converged.

Sign convention (identical to SM4): **+d = toward the BARBED end = TENSION** (the SM4 "opposing"
sense); **−d = COMPRESSION** (the SM4 "assisting" sense).

Dimer configuration is the **frozen standing** one: `Ms=3, Ma=Mb=1, splay 16°, α=10°, branchEI=0.25,
branchEA=0.03, branchLen=10 nm, forkK=1.0`. Note `branchEA = 0.03` is the standing value, **not** the
bare `build()` default of 1.0 — using the default would have characterised a model the project does
not run.

---

## 2. Headline: the mechanical explanation of the SM4 assisting saturation

### 2a. Single-head axial curves

| fixture | state | k(0) (pN/nm) | F(+20 nm) | F(−20 nm) | buckling onset | max bend |
|---|---|---:|---:|---:|---:|---:|
| **explicit-s2-l40** | adp | **0.9906** | −20.54 | **+7.04** | **−7.5 nm** | **157°** |
| explicit-s2-l40 | rigor | 0.9906 | −20.54 | +7.04 | −7.5 nm | 157° |
| explicit-s2-l40 | cycling (ADP·Pi) | 0.9906 | −19.81 | +7.07 | −7.0 nm | 161° |
| fixed-anchor | adp | 0.6296 | −13.39 | +14.91 | **none** | 0.00° |
| fixed-anchor | cycling | 0.6983 | −16.92 | +11.57 | none | 0.00° |
| calibrated-s2-l40 | adp | 0.6194 | −13.10 | +14.66 | **none** | 0.00° |

**Tension is linear and faithful on every fixture. Compression is not.** On `explicit-s2-l40` the
compressive branch is linear only to about −7 nm; beyond that the beam bend angle jumps from ~0.1° to
72° → 157°, the beam shortens end-to-end by up to 12.4 nm, and **the transmitted axial force plateaus
at ≈ 7 pN and never rises again**. `fixed-anchor` and `calibrated-s2-l40` show *zero* bending at any
displacement and keep transmitting to −20 nm.

### 2b. The load-path anisotropy that causes it

| fixture | axial k(0) | transverse k(0) (êconv / êup) | axial:transverse |
|---|---:|---:|---:|
| **explicit-s2-l40** | 0.9906 | **0.0421 / 0.0421** | **23.5×** |
| fixed-anchor | 0.6296 | 1.0000 / 0.8971 | 0.66× |

The explicit S2 beam is **23.5× softer transversely than axially** — the designed MD-informed
anisotropy (stretch ∝ 1/L, bending ∝ 1/L³). A slender element that is stiff in tension and very soft
in bending is an Euler column: under axial compression it does not resist, it **buckles sideways**.
`fixed-anchor` is nearly isotropic (in fact slightly softer axially) and has no such instability.

### 2c. Where the work goes — energy partition

Single head, ADP, axial (kT):

| fixture | at **+15 nm** (tension): F8 / conv / bind / stretch / bend | at **−15 nm** (compression) |
|---|---|---|
| **explicit-s2-l40** | **29.32** / 0.00 / 0.00 / 0.28 / **0.00** | 6.08 / 0.01 / 0.00 / 0.04 / **11.72** |
| fixed-anchor | 11.62 / 5.34 / 1.86 / 0.00 / 0.00 | 13.78 / 1.29 / 1.93 / 0.00 / 0.00 |
| calibrated-s2-l40 | 11.29 / 5.32 / 1.97 / 0.00 / 0.00 | 12.97 / 1.72 / 1.73 / 0.00 / 0.00 |

This is the mechanism in one line: **in tension the explicit beam stays taut and the F8 spring carries
everything (29.3 kT, zero bending); in compression the imposed work is stored as BEAM BENDING
(11.7 kT) instead of cross-bridge load (6.1 kT).** The catch–slip law reads the axial bond load, so
once the beam buckles the kinetics stop seeing the applied force.

### 2d. Reconciliation with SM4

In SM4 the load is applied to a *free* filament (force control), not to a pinned one, so the operating
point differs from the displacement-controlled sweep. The measured SM4 signature is consistent and
adds one detail: on `explicit-s2-l40` at zero applied load the total |F8| is already **6.5 pN** while
the axial component is only 0.74 pN — i.e. the bond force is largely **off-axis** even at rest, and
added assisting load is absorbed by further lateral deflection rather than appearing in the axial
channel (axial saturates at −2…−3 pN while |F8| stays 6.5–8 pN). On `fixed-anchor` the filament does
not move at all (0.000 nm) and the axial load tracks linearly to −21.6 pN.

**⇒ The SM4 assisting saturation on explicit-S2 is compressive buckling of a highly anisotropic
slender beam, not a kinetics artefact and not an apparatus defect.** Using `fixed-anchor` for the
slip-side grid was the correct decision.

---

## 3. Tension/compression asymmetry and large-strain behaviour

- `explicit-s2-l40`: strongly asymmetric — |F(+20)| / |F(−20)| = 20.54 / 7.04 = **2.92×**.
- `fixed-anchor`: asymmetric the *other* way — 13.39 / 14.91 = 0.90× (slightly stiffer in compression).
- Small-signal vs large-strain: on the anchored fixtures the compressive branch **stiffens**
  (k rises from 0.63 near 0 to ~0.75 pN/nm secant at −20 nm), i.e. mild tensile-stiffening-type
  nonlinearity; on explicit the compressive branch **softens to zero** past buckling.

`fixed-anchor`'s k(0) = **0.6296 pN/nm** independently reproduces the project's established
whole-cross-bridge stiffness of ≈ 0.63 pN/nm from the blinded tweezers work — a useful external check
that this assay is measuring the right quantity.

---

## 4. Hysteresis and recovery

Loading cycle 0 → +20 → −20 → 0:

| fixture | max branch-to-branch |ΔF| | residual force at return to 0 |
|---|---:|---:|
| explicit-s2-l40 | **0** | −0.727 pN |
| fixed-anchor | **0** | −0.691 pN |

**No hysteresis at all, and full recovery** — including a complete traverse through the buckled
regime and back. The residual at d = 0 is the fixture's built-in preload (`PRE_NM = 2.0 nm`), which
matches the +0.74 / +0.69 pN offsets seen in SM4. The buckling is therefore **elastic and fully
reversible**, not damage: the beam recovers its straight configuration on unloading.

---

## 5. Surrogate divergence: calibrated-s2-l40 vs explicit-s2-l40

| | explicit-s2-l40 | calibrated-s2-l40 | fixed-anchor |
|---|---:|---:|---:|
| axial k(0) | 0.9906 | **0.6194** | 0.6296 |
| buckles? | **yes, −7.5 nm** | **no** | no |
| F(−20 nm) | +7.04 (plateau) | +14.66 | +14.91 |
| bending energy at −15 nm | 11.72 kT | 0.00 | 0.00 |

**The calibrated surrogate does not reproduce the explicit beam it was fitted to.** In this
quasistatic test it behaves like the *rigid* fixed-anchor fixture (0.6194 vs 0.6296 pN/nm, no
buckling) rather than like the explicit beam (0.9906, buckles). The 4I calibration matched the
explicit beam's *relaxed-pivot axial reaction*; it evidently does not carry the beam's compressive
instability or its small-signal axial stiffness in this configuration.

**Consequence:** the surrogate is not a valid stand-in for the explicit beam under compressive or
assisting load, and any production result that depends on the compressive branch must state which
fixture produced it. This does not by itself invalidate the surrogate for load-dominated (tensile)
production use, which is what it was adopted for — but it bounds its domain of validity, and that
bound was not previously measured.

---

## 6. Dimer force-extension

All dimer stiffnesses are SERIES (dimer ⊕ 1 pN/nm F8 spring); magnitudes quoted.

| binding | mode | sep (nm) | |k(0)| pN/nm | max fork angle | max joint gap | solver fails |
|---|---|---:|---:|---:|---:|---:|
| one-head | — | — | 0.848 | 132.9° | 1.45 nm | 0 |
| two-head | symmetric | 0.00 | **1.784** | 27.4° | 1.44 nm | 0 |
| two-head | symmetric | 2.75 | 1.721 | 31.1° | 1.55 nm | 0 |
| two-head | symmetric | 5.50 | 1.542 | 42.4° | 1.65 nm | 0 |
| two-head | symmetric | 8.25 | 1.285 | 56.6° | 1.75 nm | 0 |
| two-head | symmetric | 11.00 | 1.022 | 72.3° | 1.85 nm | 0 |
| two-head | asymmetric (A only) | 5.50 | 0.912 | 92.4° | 1.65 nm | 0 |
| two-head | transverse sym | 5.50 | 0.033 | 25.9° | 0.31 nm | 0 |
| two-head | transverse anti | 5.50 | 0.101 | 93.2° | 0.49 nm | 0 |
| one-head | transverse | — | 0.028 | 21.1° | 0.13 nm | 0 |

Findings:

- **Two-head-bound is 2.10× stiffer than one-head-bound at zero separation** (1.784 vs 0.848) — the
  clean parallel-spring result — and that advantage **decays with head separation**, falling to 1.21×
  at 11 nm as the shared S2 and fork take up more of the deformation (fork angle grows 27° → 72°).
- **Loading one head only recovers the one-head stiffness** (0.912 vs 0.848), i.e. the partner
  contributes little when it is not itself displaced: the two heads are mechanically in parallel
  through the fork, not in series.
- The dimer inherits the same strong **axial:transverse anisotropy** as the single head
  (0.848 vs 0.028 one-head ⇒ 30×; 1.542 vs 0.033 two-head ⇒ 47×), i.e. the buckling-prone load path
  is a property of the S2/branch architecture, not of the single-head fixture.
- **Head-label exchange:** loading A only vs B only differs by **4.73 pN (25.5 % of peak)**. Exact
  symmetry is *not* expected — the dimer is built with a directional fork rest angle (+α on branch A,
  −α on branch B) — so this number quantifies the built-in asymmetry rather than revealing a bug.
- **0 solver failures across all 1217 points**; joint gaps stay bounded at 1.2–1.9 nm.
- Caveat: fork angles reach 92–144° in the asymmetric and one-head sweeps at ±20 nm. That is a large
  deformation for a hinge whose rest half-angle is 10°, and those far-field points should be treated
  as qualitative.

---

## 7. Health and provenance

0 solver failures and 0 invalid points across single-head and dimer sweeps. Every row carries
`code_rev` and `build_id`. Rupture/emergency are `0`/`false` and are structurally absent from the CPU
object solve (`ExplicitHmmDimer.solve` contains no reference to `ExplicitHmmDimerGpuParams`); the
harness aborts if either global is non-default.

Outputs: `force_extension_singlehead.csv`, `force_extension_dimer.csv`, `tangent_stiffness.csv`,
`component_energy.csv`, `plots/singlehead_axial.png`, `plots/dimer_axial.png`, `ANALYSIS.md`.
