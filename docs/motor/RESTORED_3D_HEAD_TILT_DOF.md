# Restored 3-D head orientation — the live neck frame and the dynamic tilt coordinate χ

**STATUS: χ is now a GENUINE DYNAMIC COORDINATE inside the implicit solve, with a derived mobility, an
FDT-validated Brownian channel, and a restoring potential referenced to a LIVE NECK/CONVERTER FRAME that is
proven covariant. The detached programme (Phases 0–10, 13, 14) is COMPLETE. TWO HARD FINDINGS were surfaced
on the way and are reported, not fixed (§A, §B); the second of them means the model is NOT yet ready for the
site-normal binding law. DEFAULT-OFF throughout; OFF is byte-identical.**

- **Worktree:** `/home/jba/Code/SoftBox` · **Branch:** `gpu-mat-bottlenecks-explicit-singlehead` · base `3119000`
- **Date:** 2026-08-12 · **Raw:** `RUN_LOGS/motor_audit/restored_3d_head_tilt/`
  · **Figures:** `docs/motor/figures/restored_3d_head_tilt/`
- **Predecessor:** `docs/motor/MYOSIN_HEAD_ORIENTATION_DOF_HISTORY.md`
- **Runner: the CPU sequential runner throughout** — plain-Java kernel calls over the host SoA arrays, no
  TaskGraph, no device transfer. Scenes are 12 motors, horizons ≤ 1.2e5 steps. This is a
  deterministic/single-motor assay class (`docs/CPU_GPU_VALIDATION_POLICY.md`); **no GPU work was launched**,
  so the mandatory GPU crash-monitoring path was not entered. The device path is NOT wired for the tilt
  solver — see §10.
- **Reproduce:**
  ```
  ./scripts/build.sh
  java @$TORNADOVM_HOME/tornado-argfile --enable-preview \
       -cp "$TDIR/tornado-api-4.0.1-dev.jar:." softbox.LiveNeckHeadProbe -all
  python3 scripts/plot_live_neck_head.py
  ```
  Individual phases: `-frame` (0) `-cov` (2) `-fdt` (7) `-dyn` (9) `-lever` `-acc` (13) `-reg` (11) `-comp` (12).

---

## A. HARD FINDING #1 — the F8 generalized-force axis in the explicit-S2 solver is the WRONG AXIS

> **RESOLVED 2026-08-12 — see §13 "F8 VIRTUAL-WORK AXIS REPAIR". The axis is now `econv` on every path, default-on. The text below is retained as the original finding.**

**Reported, quantified, NOT fixed in the production path. This is a bail-out-and-report item: it contradicts
an assumption the task rests on (that the bound mechanics being regressed are sound), and it is far outside
this task's remit to change.**

`matS2SolveStep` (the production explicit-S2 solver, device and CPU) and its scalar twins `s2SolveM` /
`s2Solve` build the φ and ψ generalized forces and Jacobian columns about **`ê_up`**:

```java
int M = G.g4M, nF = 3*M, n = nF+2; double[] E = G.eup;          // <-- s2SolveM:6786
double[] Jphi = crs(E, sub(C,P)), Jpsi = crs(E, sub(xF8,C));    // <-- s2SolveM:6802
```

but the geometry rotates about **`ê_conv`**. Measured by finite difference **through the live `matBeamGeom`
kernel**, at the production scene:

| quantity | value |
|---|---|
| `d xF8/d psi` (FD through the kernel) | `(+2.067246e-03, 0, −7.329836e-03)` |
| `econv × (xF8 − C)` | `(+2.067247e-03, 0, −7.329836e-03)` — **matches** |
| `eup × (xF8 − C)` | `(0, +7.329836e-03, 0)` — **what the solver uses** |
| `d C/d phi` (FD through the kernel) | `(+4.876521e-03, 0, −6.341887e-03)` |
| `econv × (C − P)` | `(+4.876522e-03, 0, −6.341887e-03)` — **matches** |
| `eup × (C − P)` | `(0, +6.341887e-03, 0)` — **what the solver uses** |

With `b̂ = x̂`, `ê_conv = ŷ`, `ê_up = ẑ`, the whole converter geometry lies in the `xz`-plane, so
`eup × (in-plane vector)` is along `ŷ` — **orthogonal to the true Jacobian**. Consequences, measured:

- **A purely in-plane bond force produces exactly ZERO φ and ψ generalized force.** One solver step with
  `F8 = 5 pN` along `+b̂` (and along `+ê_up`) gives `Qphi = Qpsi = 0` in the solver's own arithmetic while the
  true values are `3.46e-14` / `1.03e-14` (in the kernel's µm units). Only the *out-of-plane* `ŷ` component of
  the bond force reaches the converter coordinates at all.
- **Work-closure gate.** A motor bound to a fixed site 4 nm away, Brownian off, relaxed to the solver's own
  fixed point: the true potential's gradients there are `dU/dφ = −3.459e−20 N·m`, `dU/dψ = −1.640e−20 N·m` —
  **not stationary**, by 4 orders of magnitude relative to the converter-torque scale. The same gate on the
  new tilt solver with the exact `econv` axis returns `9.3e−09` (relative) ⇒ **STATIONARY**.
- **Magnitude.** At a 4 nm bond stretch the solver converges to `φ = 0.5236 rad` (i.e. it does not respond at
  all) where the exact axis gives `φ = 0.7353 rad` — a **12.1° lever-angle** difference, and the converter
  strain the load should have produced (`dU/dφ / k_conv ≈ 0.13 rad ≈ 7.7°`) is simply absent.

**Provenance:** introduced by `1b227c0` (2026-07-15, "explicit MD-informed S2 (4G)"), i.e. it has been present
for the whole life of the explicit-S2 model. Everywhere else in the file the convention is
`double[] B=bhat, E=econv, U=eup` (`tailSolveM`, `supSolveM`, the Cmot builders at `:1337`, `:2618`, `:3868`,
`:4623` all use `crs(econv, …)` correctly) — so this reads as a copy-paste of the *name* `E` onto the wrong
vector in the two explicit-S2 solvers only.

**Scope — what it does and does not touch.**

- **DETACHED motors are unaffected**: `F8 = 0`, so the block contributes no generalized force at all. The
  entire detached programme of this task (Phases 0–10, 13, 14) is therefore sound.
- **BOUND mechanics on the explicit-S2 path ARE affected**: the load feedback onto the converter is missing.
  This plausibly bears on standing explicit-S2 results — the load-insensitivity of the stroke, the "V₀ static
  over-drive", and the Outcome-C observation that the explicit-S2 motor glides 1.2–1.4× faster than the
  calibrated surrogate (which uses the correct axis) with only 67–68 % of bonds load-bearing. **I am not
  claiming those are explained by it; I am flagging that they must be re-examined.**
- **The Cmot fixed-anchor / calibrated-S2 paths are NOT affected** (they use `econv`), so the frozen
  tweezers/stroke/`k_ext` calibrations behind `docs/MOTOR_MODELS.md` stand.

**What I did instead of fixing it.** The new tilt solver `matS2SolveStepTilt` carries the axis as a **data
flag** (`matc[4]`: `1 = econv` exact, `0 = eup` legacy) so the χ increment can be measured against an
otherwise identical baseline, and so the planner can see the isolated cost of the fix. `matS2SolveStep` is
**byte-unchanged** and remains the wired production solver. Promoting the fix re-baselines every explicit-S2
result and is a planner decision, not mine.

---

## B. HARD FINDING #2 — a live-frame rest orientation exposes an UNCONSTRAINED LEVER DOF

> **RESOLVED 2026-08-12 — see §14 "S2→LEVER MOMENT-TRANSFER REPAIR". The lever is now the terminal orientation of the S2 chain, held by the beam's OWN bending stiffness; no new stiffness was invented. The text below is retained as the original finding.**

**This is the finding that blocks the site-normal binding law, and it is a consequence of doing exactly what
the task asked for.**

The historical `½·k_bind·(ψ − ψ_actin)²` is referenced to the motor's stored base triad. Together with
`k_conv(θ − θ_s)`, `θ = ψ − φ`, it pins **both** angular coordinates: ψ to the base frame and φ to ψ. Replace
it with a rest direction that co-rotates with the neck — which is the whole point — and the pinning to the
base frame goes with it. What remains is:

- `k_conv` ties ψ to φ (a *relative* constraint);
- `U_det` ties `eBind` to the neck frame (another *relative* constraint);
- **nothing at all constrains the lever angle φ.** The S2 beam attaches to the lever at a single **point** `P`;
  the model has **no angular joint between the S2 distal tangent and the lever**.

Measured, detached, 12 motors, 200 ms:

| | SD(ψ) over the run | RMS θ_det (head vs its own neck) |
|---|---|---|
| historical base-frame law (`k_bind = 512`) | **3.2°** | — (χ absent) |
| live-frame `U_det`, k = 512 | **1043°** | 14.3° |
| live-frame `U_det`, k = 10 | **810°** | 41.4° |
| live-frame `U_det`, k = 5 | **1077°** | 54.6° |

**The head does NOT tumble relative to its neck** — that is well controlled at every stiffness, and is the
result the task wanted. **The lever+head assembly free-rotates about `ê_conv`**, several turns per 200 ms.
Fixture C (§3) shows why this is exact and not a numerical artifact: in the planar reference configuration
`U_det` is *exactly* invariant under the simultaneous rotation `(φ, ψ) → (φ+δ, ψ+δ)`, so that mode has **zero
restoring force**.

**Reading.** The base-frame `k_bind` spring was doing two different jobs at once: (i) holding the head's
binding face in its stereospecific pose, and (ii) standing in for the missing S2→lever angular joint. This
task removes (i)'s lab reference correctly, and in doing so exposes that (ii) was never modelled.

**Consequences that must be carried forward.**

1. The **detached search statistics remain valid and meaningful** — θ_det is a relative angle and is exactly
   what the task asked to measure. §6 reports it, and §6b adds a lever-clamped control that isolates the
   head's own search from the lever mode.
2. The **solid-angle and site-accessibility numbers are inflated by the lever mode** and must be quoted with
   that attached (§9). They are still a true statement about the *current* model.
3. **Closing it requires new physics**: an angular joint between the distal S2 tangent and the lever, with its
   own stiffness. That stiffness has **no provenance anywhere in the tree** (the same finding the archaeology
   made for a detached head stiffness), so I did **not** invent one. It can be written in the *same* live neck
   frame with no lab reference (`U_lever = ½k_lev·angle(n1, native-relative-to-ŝ)²`), which is the natural
   shape, but the value is a planner decision.
4. Until it is closed, a bound-state site-normal law would be acting on a motor whose lever orientation is a
   free coordinate. **That is why §11 stops here.**

---

## 1. Executive verdict

**One additional coordinate χ restores genuine 3-D `eBind` freedom, and it is now a real dynamic coordinate:
it sits in the implicit block, carries a derived (not fitted) configuration-dependent mobility, is
FDT-validated, and is driven by a restoring potential whose rest direction lives in a proven-covariant live
neck frame.**

| | current production | **this work** | historical sphere-head |
|---|---|---|---|
| head orientation DOF | 1 (ψ), algebraic | **2 (ψ, χ), both dynamic in the implicit solve** | 3, dynamic |
| rotational drag on the head | none in the EOM | **Γ(ψ,χ) derived, diagonal, χ-dependent** | Stokes sphere |
| Brownian channel | ψ only | **ψ and χ, FDT-gated** | all three axes |
| rest orientation reference | the motor's **stored** base triad | **a LIVE neck/converter frame** | none (free) |
| covariance rank of the eBind set | 2 (a plane) | **3** | 3 |
| detached search RMS misalignment | ±3.2° in ψ, planar | **41–55° at k_det = 5–10, full 3-D** | free |
| every4 site azimuths reached **dynamically** | — | **20 / 20** | — |

**χ ≡ 0 is byte-identical** (`max |matBeamGeomTilt − matBeamGeom| = 0.000e+00`), and the whole feature is
default-off: `matS2SolveStep` and `matBeamGeom` remain the wired production kernels and are byte-unchanged.

---

## 2. LIVE NECK/CONVERTER REST FRAME (Phases 0 and 1)

### 2.1 The task's six questions, answered from measurement

**Q1 — what point is the neck–head pivot C?** `C = P + l_b·uB`, where `P` is the S2 beam's distal node
(`nodes[M]`) and `l_b = 8 nm`. `C` is the head's own converter material point `r_conv`
(`xH = C − R(ψ)·r_conv`), so ψ and χ rotate the head **rigidly about C**. Measured: `|C − P| = 0.00800 µm = l_b`
exactly.

**Q2 — the LIVE vector representing the neck entering the head:** the lever axis
`n1 = (C − P)/l_b = uB(φ)`. It is computed from the *emitted geometry*, not from the stored triad, so it is
covariant by construction. Measured at the native pose: `n1 = (+0.79274, 0, +0.60957)`.

**Q3 — a second independent LIVE vector, defining rotation about the neck:** the **distal S2 tangent**
`ŝ = (node M − node M−1)/|·|`. Measured `ŝ = (+0.92893, 0, −0.37027)`, `|s| = 10.36 nm`,
`angle(n1, ŝ) = 59.29°`, Gram–Schmidt conditioning `|g| = 0.85976` — well away from degeneracy. (The kernel
carries a documented fallback for `|g| < 1e-9`; it is never taken.)

Hence
```
n1 = uB = (C − P)/l_b
n2 = normalize( ŝ − (ŝ·n1) n1 )
n3 = n1 × n2
```
Measured orthonormality: `|n_i| = 1.000000000000000`, `n1·n2 = −1.7e−16`, `n1·n3 = n2·n3 = 0`.

**Q4 — does the frame follow the mechanics?** Measured, one perturbation at a time:

| perturbation | rot(n1) | rot(n2) | rot(n3) | reading |
|---|---:|---:|---:|---|
| S2 bend (node M−1 out of plane) | 0.0000° | **12.6535°** | **12.6535°** | n2/n3 **roll about n1** — S2 bending now reorients what it carries |
| lever motion (φ + 0.20 rad) | **11.4592°** | 11.4592° | 0.0000° | n1 turns by exactly the lever angle |
| converter state (ψ + 0.20 rad) | 0.0000° | 0.0000° | 0.0000° | **invariant** — the frame is the NECK's, not the head's |
| rigid rotation of the whole motor | 0.00e+00 | 0.00e+00 | 0.00e+00 | **covariant** — equals `R` applied to the reference frame |

The **stored base triad responds to none of the first three**: it is written once at build and never updated.

**Q5 — is the stored base triad sufficient? NO, and this is the correction the task demanded.** The beam's
distal node `P` enters `matBeamGeom` as a pure **translation**: `uB`, `d0` and `r_conv` are all built from
`frame[0..8]` alone. A rest direction expressed about that triad is therefore *not* "the head prefers a native
pose relative to the neck currently carrying it" — it is "the head prefers its build-time base orientation".
The predecessor report's claim that "ψ = ψ_actin is already a neck/converter-relative rest pose" is
**withdrawn**: ψ is measured about a *stored* `ê_conv`, and the row above shows that vector does not move when
the S2 bends or when the lever swings.

**Q6 — the minimal live frame** is the `(n1, n2, n3)` above: built entirely from `(C − P)` and
`(node M − node M−1)`, with no lab axis and no stored triad anywhere in it.

### 2.2 The native rest orientation (Phase 1)

```
eBind_rest = c1·n1 + c2·n2 + c3·n3          (c fixed per motor, computed once at build)
```

with `(c1, c2, c3)` the native head pose resolved in the live frame at the **reference configuration**:
`φ = PHI_PRE_3E = 30°` (the validated pre-stroke lever angle), `ψ = ψ_actin` (the historical stereospecific
head orientation the `k_bind` spring points at), `χ = 0`, on the as-built unbent beam.

> **Load-bearing detail:** the reference configuration is *not* the as-built state. `buildGlide2D` seeds
> `φ` and `ψ` with a **randomised scramble** (φ = PHI_PRE ± 75°, ψ ± 60°) as an initial condition. Calibrating
> against that would have made the "native pose" an accident of the seed. Measured coefficients:
> `c = (+0.800717, +0.599043, +0.000000)`, `|c| = 1.000000000000`. `c3 = 0` exactly, because the reference
> configuration is planar and `n3 = ±ê_conv` there.

Because `(c1, c2, c3)` are coefficients in a frame that co-rotates with the neck, `eBind_rest` rotates exactly
with a rigid rotation of the motor and generates **no torque from rigid motion**. That is Fixture A.

---

## 3. COVARIANCE FIXTURES (Phase 2)

Deterministic, no dynamics. `k_det = 10 pN·nm/rad²` throughout. Raw: `covariance_s2roll.tsv`.

### A. RIGID ROTATION of the whole motor (arbitrary 3-D `R`: every beam node, the base triad, the anchor, the clamp tangent)

Head displaced to an arbitrary pose (`ψ + 0.35`, `χ = 0.28`) so the test has something to be invariant *about*:

```
theta_det : 25.472840592 deg -> 25.472840592 deg      |delta| = 0.000e+00 deg
U_det     : 9.882788362e-22 -> 9.882788362e-22 J      |delta|/U = 0.000e+00
torque    : R·tau(before) = (+1.5396e-21 +2.3361e-21 -3.4551e-21)
            tau(after)    = (+1.5396e-21 +2.3361e-21 -3.4551e-21)   |diff|/|tau| = 5.998e-16
=> PASS — invariant AND covariant, to machine precision
```

**Honest control:** the old base-frame law is **also** invariant here, because ψ is an internal coordinate.
**Fixture A does not discriminate between the two definitions** — it only proves the new one is not broken.
The discriminating fixtures are B and C.

### B. S2 BEND — the fixture the old definition FAILS

Roll the distal S2 element by α about the lever axis `n1`, and carry the head with it (the head keeps the
*same pose relative to the live neck frame*). This is the physical statement "bending the S2 reorients what it
carries" that the analytic reduction had lost.

| α (deg) | θ_det, LIVE frame | U_det, LIVE frame | ΔU, BASE frame |
|---:|---:|---:|---:|
| 0 | 1.2e−06° | 2.2e−36 J | 0.000 kT |
| 5 | 1.2e−06° | 2.2e−36 J | 0.000 kT |
| 15 | 1.2e−06° | 2.2e−36 J | 0.017 kT |
| 30 | 1.2e−06° | 2.2e−36 J | **0.283 kT** |
| 60 | 1.2e−06° | 2.2e−36 J | **5.028 kT** |
| 90 | 1.2e−06° | 2.2e−36 J | **25.657 kT** |

**LIVE: machine zero at every roll. BASE: the same physical configuration is charged up to 26 kT purely
because the lab-referenced ψ moved.** This is the decisive demonstration that the old rest direction was not
neck-relative.

### C. LEVER MOTION

Move φ by δ and carry the head with the neck (`ψ → ψ + δ`):

| δ (deg) | θ_det, LIVE frame | ΔU, BASE frame |
|---:|---:|---:|
| 0 | 8.54e−07° | 0.000 kT |
| 5 | 8.54e−07° | 0.474 kT |
| 15 | 8.54e−07° | 4.262 kT |
| 30 | 8.54e−07° | **17.050 kT** |

The live-frame potential exerts **no torque** when the head is simply carried by the lever; the base-frame law
charges 17 kT for the same 30°. In the planar reference configuration the invariance is **exact** (with the
lever and the S2 tangent coplanar, the Gram–Schmidt reference rotates rigidly with `n1`); an out-of-plane S2
tangent makes it approximate, which is a real mechanical coupling, not a frame artifact.

**This fixture is also the direct proof of §B**: an exactly-zero restoring force on the `(φ+δ, ψ+δ)` mode is
what leaves the lever unconstrained.

### D. LAB-AXIS CHANGE

No lab vector enters `n1`, `n2`, `n3` or `eBind_rest` at any point — they are built from `(C − P)` and
`(node M − node M−1)`. Fixture A is the operational test and it passes to machine precision.

---

## 4. THE DYNAMIC COORDINATE — kinematics, generalized forces, mobility, solver (Phases 3–8)

Implementation: `TwoBodyBeamAnalyticGpu.matS2SolveStepTilt`, an **additive** 15-argument kernel. The
production `matS2SolveStep` is not modified.

### 4.1 Exact kinematics, and why the two-angle parameterisation is exact

`matBeamGeomTilt` rotates the head rigidly about `C` by χ around `t̂ = e0(ψ) × ê_conv`. Because rotating about
a ψ-dependent axis *after* the ψ rotation is the conjugate of rotating about the fixed `t̂₀ = p̂1 × ê_conv`
*before* it, the head pose is exactly

```
Q(ψ, χ) = R(ê_conv, ψ) · R(t̂₀, χ)
eBind   = cos(χ)·e0(ψ) + sin(χ)·ê_conv ,        e0(ψ) = R(ê_conv, ψ)·p̂1
```

so `(ψ, χ)` are spherical coordinates of the head axis with `ê_conv` as the pole. The two generators are

```
omega_psi = ê_conv          omega_chi = t̂(ψ) = R(ê_conv, ψ)·t̂₀
```

**both unit, and mutually orthogonal everywhere.** Exact Jacobian columns follow:
`∂xF8/∂ψ = ê_conv × (xF8 − C)`, `∂xF8/∂χ = t̂ × (xF8 − C)`. χ is clamped to `|χ| ≤ 89°` to stay off the
spherical-coordinate pole; the k_det = 0 control still covers 100 % of 4π, so the clamp costs no reachability.

### 4.2 Generalized forces — one potential, no hand-chosen springs (Phases 4, 5)

**One functional form, the target chosen by binding state:**

```
U     = ½·k·θ² ,   θ = angle(eBind, eTarget)
DETACHED : k = k_det  , eTarget = c1 n1 + c2 n2 + c3 n3     — the LIVE neck frame
BOUND    : k = k_bind , eTarget = e0(ψ_actin)               — the HISTORICAL base-frame target
```

The bound branch is the **minimal 3-D extension of today's law**: at χ = 0,
`angle(e0(ψ), e0(ψ_actin)) = |ψ − ψ_actin|`, so it reduces **exactly** to `½k_bind(ψ − ψ_actin)²`. It adds no
parameter and does **not** retarget to the site normal — that is the next task, and §11 stops before it.

All generalized forces are derivatives of the *same* `U`, analytically:

```
lambda   = k·θ/sin θ                          (→ k as θ → 0; floored near θ → π)
Q_psi    = lambda · cos(χ) · ((ê_conv × e0) · eTarget)
Q_chi    = lambda · ((cos(χ) ê_conv − sin(χ) e0) · eTarget)
```

and — **detached only, because only then does the target have carriers** — the equal-and-opposite reaction, so
that `U_det` is a genuine conservative potential and not a one-sided spring:

```
Q_phi        = lambda · (eBind · ∂eTarget/∂φ)
F(node M)    = −F(node M−1) = lambda · beta · n3 / |s| ,   beta = eBind·(c2 n3 − c3 n2)/|g|
```

The node reaction falls out in closed form because `∂n2/∂ŝ = n3 n3ᵀ/|g|` and `∂n3/∂ŝ = −n2 n3ᵀ/|g|`, and
`n3·ŝ = 0`; it is a transverse force **couple** on the distal S2 element — physically, rolling the neck frame
requires torquing the S2.

**Jacobian.** The exact small-angle Hessian in these coordinates is `k·cos²χ` on `(ψ,ψ)` and `k` on `(χ,χ)`,
with **no cross term** (because `∂eBind/∂ψ · ∂eBind/∂χ = 0` identically). At χ = 0 this is exactly the legacy
`kbnd` entry. The weak detached reaction terms (`Q_phi`, the node couple) are carried in the residual only:
they are two orders below the beam and drag diagonals, and it is the **residual**, not the Jacobian, that
fixes the physics.

### 4.3 The generalized mobility Γ(ψ, χ) — DERIVED, and it is NOT a plain constant diagonal (Phase 6)

The head is a sphere: rotational drag `γ_r = 8πηR³`, translational `γ_t = 6πηR`. Its centre offset from the
pivot is `ρ = xH − C`. In this motor `r_conv = −r_F8` **exactly**, so

```
ρ = |r_conv| · eBind        —  the head centre offset is PARALLEL to the head axis, always
```

and with `Γ_ij = γ_r (ω_i·ω_j) + γ_t (ω_i × ρ)·(ω_j × ρ)`:

```
Gamma_chichi = gamma_r + gamma_t |rho|^2                    = the existing gamma_psi, EXACTLY
Gamma_psipsi = gamma_r + gamma_t |rho|^2 cos^2(chi)         = gamma_r + (gamma_psi − gamma_r) cos^2(chi)
Gamma_psichi = 0                                             EXACTLY, everywhere
```

using `ê_conv·eBind = sin χ`, `t̂·eBind = 0` and `ê_conv·t̂ = 0`. Measured in the probe scene:

| quantity | value |
|---|---|
| `Γ_χχ = γ_ψ` | **3.70359e−25 N·m·s/rad** |
| `γ_r` (sphere only) | **2.44632e−25** — a fixed fraction `0.6605` of `γ_ψ`, so it tracks any `-eta` rescaling |
| `Γ_ψψ(0°)` | 3.70359e−25 |
| `Γ_ψψ(60°)` | 2.76064e−25 |

**So the predecessor's `γ_χ = γ_ψ` is exactly right for `Γ_χχ`, and the new result is that `Γ_ψψ` acquires a
`cos²χ` dependence the current constant-`γ_ψ` code does not have** (a 34 % swing from χ = 0 to χ = 90°). It is
implemented. Nothing is fitted; no new parameter is introduced.

**Why no spurious-drift correction is needed:** `M = Γ⁻¹` is diagonal, `M_ψψ` depends on χ but not on ψ, and
`M_χχ` is constant ⇒ `∇·M ≡ 0`, so the plain Itô update with FDT amplitudes `sqrt(2 kT Γ_ii/dt)` is
Boltzmann-consistent with no extra term. This is checked, not assumed (§5).

### 4.4 Solver choice — the stale relaxation-time claim, corrected (Phase 8)

**The predecessor's "τ_χ ≫ dt so explicit integration is safe" is WITHDRAWN. It conflated the detached and
bound stiffnesses, and it was wrong about the free head as well.**

| state | stiffness | `τ = Γ_χχ/k` | vs `dt = 2.5 µs` |
|---|---|---|---|
| detached, `k_det = 5` | 5e−21 N·m/rad² | **74 µs** | 30 steps — comfortably resolved |
| detached, `k_det = 10` | 1e−20 | **37 µs** | 15 steps — resolved |
| **bound, `k_bind = 512`** | 5.12e−19 | **0.72 µs** | **0.29 steps — SHORTER than dt** |
| free (`k = 0`) | — | — | the per-step thermal step is `sqrt(2kT dt/Γ) = 13.5°` |

χ therefore **participates in the existing implicit generalized-coordinate solve** (`n = 3M+2 → 3M+3`), with
`k` on the `(χ,χ)` Jacobian diagonal alongside `Γ_χχ/dt`. That is unconditionally stable for a linear spring
at any `k`, which is exactly why a detached-only explicit scheme was not built. The scratch stride grows
`(3M+2)(3M+3) → (3M+3)(3M+4)` (210 → 306 at M = 4) **only when the feature is on**.

*Standing caveat, not fixed here:* at `k_bind = 512` the bound head's orientational relaxation is
**under-resolved at the production dt** by ~3.4×. That is the same sub-step issue already on record for the
cross-bridge and is not made worse by χ (the implicit treatment keeps it stable); it does mean bound-state
orientational *statistics* at production dt are not dt-converged.

---

## 5. DYNAMIC 3-D HEAD FDT (Phase 7)

**A pre-existing quirk had to be isolated first.** `matS2SolveStep` adds the F8 5×5 **stiffness** block to
*every* motor — only the F8 **force** is gated on `bound`. A detached motor therefore carries a spurious
cross-bridge Hessian that damps its angular response. The tilt kernel inherits this verbatim (it is not mine
to change), so the FDT gates run on an **isolated copy with `kF8 = 0` and `k_conv = 0`**, where `(φ, ψ, χ)`
are exactly the coordinates the FDT amplitudes were derived for. Everything else — drag, noise, solve — is the
production kernel.

### Gate A — the free χ channel

<!--FDT_GATE_A-->

### Gate B — the restrained head at equilibrium, against the EXACT Boltzmann prediction

The two generators are **orthonormal**, so the kinetic metric in `(ψ, χ)` is **flat** and the invariant
measure is `dψ dχ` — **not** the sphere's area element `cos χ dψ dχ`. With the target in the χ = 0 plane
(which it is: `c3 = 0` ⇒ `eTarget·ê_conv = 0`), `cos θ = cos χ · cos(ψ − ψ_t)`, and the prediction is an exact
2-D quadrature of `exp(−kθ²/2kT)` in those coordinates. Both measures are quoted; the flat one is the correct
reference for this parameterisation.

<!--FDT_GATE_B-->

---

## 6. THE DETACHED DYNAMIC SEARCH, AND k_det = 5 vs 10 (Phases 9, 14)

12 detached motors (`boundSeg = −1`, `bondData = 0`), the **real production solver**, dt = 2.5e−6 s,
15 000 warm + 80 000 sampled steps (200 ms), sampled every 20 steps. `θ_det` is the misalignment from the
native pose in the live neck frame. Raw: `head3d_kdet.tsv`, `ebind_cloud_k*.tsv`.

| k_det (pN·nm/rad²) | RMS θ_det | median | p90 | p95 | SD(ψ) | SD(χ) | τ_ac | solid angle | cov. rank | frac ≤ 25° |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| **0** (control) | 72.97° | 72.10° | 100.44° | 108.53° | 984° | 55.01° | 169 µs | **1.000** | 3 | 0.101 |
| **5** | **54.61°** | 48.00° | 82.06° | 89.32° | 1077° | 45.31° | 166 µs | **1.000** | 3 | 0.246 |
| **10** | **41.44°** | 31.19° | 67.68° | 75.24° | 810° | 38.42° | 185 µs | **0.999** | 3 | 0.395 |
| **512** (today's value) | 14.35° | 9.22° | 22.47° | 28.26° | 1043° | 17.84° | 919 µs | 0.403 | 3 | 0.926 |

**Reference points.** A *uniform* head axis gives RMS θ = 98.1°; the k_det = 0 control gives 72.97° (lower
than uniform only because of the ±89° χ clamp and the residual F8 Hessian of §5). The old planar model gave
covariance **rank 2**, 2.9 % of 4π and ±3.2° in ψ.

**Reading — this is the regime the specification asked for.**

- `k_det = 5` — RMS 54.6°, p90 82°: **broad 3-D exploration with a clear but loose native-pose preference**
  (73° → 55° against the free control).
- `k_det = 10` — RMS 41.4°, p90 68°: **the same qualitative behaviour, distinctly tighter.**
- Neither is unrestricted tumbling (both are well below the 73° free control and the 98° uniform value), and
  neither is excessive confinement (both are far above the 14.4° that today's `k_bind` produces, and both have
  p90 well outside the 25° capture cone).

### 6b. LEVER-CLAMPED CONTROL — the head's own search, with §B's free mode removed

φ held at `PHI_PRE_3E` after every step; everything else is the production solver.

<!--LEVER_TABLE-->

### 6c. Is k_det = 5 distinguishable from 10? (Phase 14)

<!--KDET_VERDICT-->

---

## 7. CORE MOTOR REGRESSION (Phase 11)

Deterministic, Brownian off. **Stroke and `k_ext` are BOUND-state quantities and are measured with
`boundSeg ≥ 0`**, so they exercise the BOUND orientation law (the historical base-frame target, unchanged) —
which is the point: χ must not disturb it. Arms differ from the one above by ONE change each.

<!--REGRESSION_TABLE-->

---

## 8. AXIAL COMPLIANCE DECOMPOSITION (Phase 12)

A fixed actin site is displaced 0.5 nm along `+b̂`; the settled response of every generalized coordinate is
projected onto `b̂` through its **exact** Jacobian column, so the shares sum to the total F8 displacement by
construction. An axial load requires a bond, so every arm is bound; the arm variable is the head-orientation
**stiffness** the solver applies.

<!--COMPLIANCE_TABLE-->

---

## 9. DYNAMIC HELICAL SITE-NORMAL ACCESSIBILITY (Phase 13)

**No `RAND_BASE_AZ`. No projection of `n_site`. No candidate-site torque. No binding.** The real detached
thermal trajectory of ONE fixed motor (200 ms), against the 20 distinct `every4` site azimuths at the
historical 25° tolerance. Raw: `dynamic_site_access.tsv`; figure 3.

| k_det | azimuths dynamically visited | mean dwell fraction | lower | side | upper |
|---:|---:|---:|---:|---:|---:|
| **5** | **20 / 20** | 0.0576 | 0.0293 | 0.0846 | 0.0628 |
| **10** | **20 / 20** | 0.0505 | 0.0171 | 0.0520 | 0.0827 |
| **512** (today) | 16 / 20 | **0.0040** | 0.0067 | 0.00013 | 0.0045 |

**The answer to the task's question is YES for k_det = 5 and 10, and the margin over today's value is an order
of magnitude in dwell, not a marginal improvement.** At `k_det = 512` the accessible set is **bimodal** — only
azimuths near ±90° (the two poles the planar motor could already face) get any dwell at all, and the six
`side` azimuths sit at `1.3e−4`, i.e. essentially never; at 5–10 the dwell is spread over **every** azimuth
including lower, side and upper.

**Caveat that must travel with these numbers (§B).** Part of the azimuthal spread comes from the free lever
mode rather than from the head's own search. The lever-clamped control in §6b is the honest lower bound on
what the head alone contributes.

---

## 9b. SINGLE-MOTOR FINE-TIME VISUAL SANITY GATE

**A visual model-integrity gate, not a campaign. ONE anchored motor, ONE filament, the real site-aware
capture path, recorded at ONE FRAME PER INTEGRATION TIMESTEP through a natural binding event. Nothing was
tuned; no gate, threshold or physics was changed.**

```bash
./scripts/build.sh
java @$TORNADOVM_HOME/tornado-argfile --enable-preview \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." softbox.SingleMotorMovieHarness -arm both
python3 scripts/plot_single_motor_movie.py
cd ~/Code && python3 SoftBox/sim_server.py 8000
#   -> http://localhost:8000/SoftBox/single_motor_binding_viewer.html
```

Raw: `RUN_LOGS/motor_audit/restored_3d_head_tilt/single_motor_movie/`
(`scene_{off,on}.json`, `frames_{off,on}.json`, `trace_{off,on}.tsv` — one row per rendered frame, `run.txt`).
Figures: `docs/motor/figures/restored_3d_head_tilt/single_motor_movie/`
(`contact_sheet_off.png`, `contact_sheet_on.png`, `fine_time_traces.png`).

### Scene, and the one fixture

The step is `ExplicitCompleteMatHarness.stepGlidingCPU` **verbatim** — same kernels, same gates, same
chemistry — so no step order can drift from production. The only fixture is applied *after* each step: the
**filament pose is restored from a snapshot**, i.e. the filament is frozen so head motion is readable. Path-B
canonical configuration: sparse `every4` lattice, filament-global phase, site-aware capture, z slab
configured (inert while the filament is frozen), no `RAND_BASE_AZ`, no motor skew, production dt = 2.5 µs.
Exactly one motor is left bindable, chosen *before the run* by smallest lateral distance to the filament axis
(5.92 nm); motors do not interact through a frozen filament, so the reduced lawn cannot affect the capture.
The filament sits at its as-built centreline height — the +11 nm emergent height is a 1200-motor collective
result and is not reproducible in a one-motor scene.

### Both arms bound naturally, onto the same site

| | arm A (control, no χ) | arm B (3-D head candidate) |
|---|---|---|
| natural binding at | step 11784 = **29.46 ms** | step 4055 = **10.14 ms** |
| site latched | **k = 155**, segment 9 | **k = 155**, segment 9 |
| F8→site at capture | 2.681 nm (gate < 3 nm) | 4.232 nm |
| ∠(eBind, n_site) at capture | **67.7°** | **68.5°** |
| bond persisted to | frame +280 | frame +204 |
| window | 799 before / binding / 400 after = 1200 frames | same |

No binding was forced: no head was rotated, no F8 placed, no gate weakened, no site chosen afterwards, and
the recorded window is simply the first natural event.

### Capture-moment checks (frames −1 / 0 / +1)

| # | check | arm A | arm B |
|---|---|---|---|
| 1, 6 | head-centre step at capture vs mean detached step | 2.43 nm vs 1.76 (**1.38×**) | 2.61 nm vs 1.55 (**1.68×**) |
| 2 | eBind rotation at capture vs mean detached | 1.94° vs 2.51° (**0.77×**) | 23.7° vs 10.5° (**2.26×**, but < the 35.1° detached max) |
| 3 | F8→site *before* capture | **1.98 nm** — already inside the 3 nm gate | **1.63 nm** |
| 4 | ∠(eBind, n_site) before / at / after | 65.8 / 67.7 / 65.3° | 54.0 / 68.5 / 60.8° |
| 5 | ∠(eBind, eBind_rest) over the 50 pre-frames | range 1.5–17.6° | range **20.6–91.5°** — no monotone collapse ⇒ **the site does not pull the head** |
| 7, 8 | latched site vs the candidate one step earlier | **same site** | **same site** |
| 9 | host gate read-out at −1 / 0 | ALL GATES PASS / BOUND | ALL GATES PASS / BOUND |
| 10 | xF8 / pivot C / beam-tip P step; S2 extension | 2.53 / 2.33 / 2.40 nm; 39.80 → 39.43 nm | 3.98 / 1.64 / 1.73 nm; 39.19 → **38.13 nm** |

**Verdict: no teleportation, no coordinate remapping, no snap across the filament surface, correct site ID,
correct `n_site`, and the strong bound elasticity begins only after capture.** Every motion at the capture
step is within the ordinary per-step thermal envelope of the same run.

### What the movie shows that the scalars did not

1. **The detached head does not "sweep" — it takes large uncorrelated thermal jumps at production dt.** Head
   centre **1.55–1.76 nm mean, up to 3.8 nm, per single 2.5 µs step**, in *both* arms; eBind turns 2.5°/step
   (A) and **10.5°/step, max 35°** (B). The search is a sequence of jumps comparable to the head's own radius,
   not a smooth trajectory. This is the same under-resolution §4.4 flags, now visible: it is not a defect
   introduced by χ (arm A shows the same translational scale), but it does mean "search" is diffusive-coarse
   at this dt.
2. **Finding §B is unmistakable on the contact sheets.** In arm A the lever (gold) points the same way in
   every panel; in arm B it swings through a wide range panel to panel, and the ψ trace runs from −190° to
   +60°. Side by side, that is the free lever mode.
3. **Arm A's head is visibly pre-oriented and rigid.** ψ is a flat line and `∠(eBind, n_site)` sits at 65–70°
   for the *entire* 3 ms window, never once approaching the site normal. Arm B's explores 0–170°. This is the
   §0 defect rendered.
4. **Both arms bind ~68° away from the bound site's outward normal**, because `g1` gates ψ against the
   base-frame `ψ_actin`, not against `n_site`. The movie makes the actin-blindness of the orientation gate
   directly visible, and it is the thing the site-normal law would fix.
5. **χ collapses to ≈0 immediately after capture and stays there** (χ trace, arm B) — the bound branch's
   base-frame target has its minimum at χ = 0, exactly as designed, and it engages only after binding.

### Suspicious, flagged, not fixed

- **The head sphere visibly overlaps the actin surface when bound** (contact sheets, frames +0/+3/+40). Head
  radius 4.6 nm, actin radius 3.5 nm, and only the F8 *point* is constrained — there is **no steric exclusion
  between the head body and the actin cylinder** on this path. It is geometrically consistent with the model
  as written, and it looks wrong.
- **Arm B's S2 extension drops 1.06 nm in the single capture step** (39.19 → 38.13 nm) versus 0.37 nm in arm
  A. Within the beam's own thermal step here, but worth watching if bound-state S2 strain is ever quoted.
- Arm B binds **~3× sooner** (10.1 vs 29.5 ms). One event per arm — this is an observation, not a recruitment
  measurement, and no such claim is made.
- ~~The binding gates are **not χ-aware** (§10.5): `siteGateA`/`siteCommitB` recompute the pose from (φ, ψ).~~
  **RETRACTED 2026-08-13 — `docs/motor/CHI_AWARE_CAPTURE_ORIENTATION_AUDIT.md` §1: `siteGateA` reads xF8/xH
  from `outGeom` ONLY, which `stepGlidingCPU` writes with `matBeamGeomTilt`, so the spatial gate is fully
  χ-aware; `siteCommitB` computes no head geometry at all (it gates ψ/φ/θ). The
  movie's arm B therefore captures using a head orientation that ignores its own tilt coordinate.

---

## 10. WHAT IS NOT DONE

1. **The site-normal binding law is NOT implemented** — `g1` retarget, `U_bind` on the site normal, the actin
   reaction, multi-motor binding, twirling. That is Phase 15's hard stop and §11 rules on it.
2. **The F8 axis defect (§A) is reported, not fixed** in the production solver.
3. **The unconstrained lever DOF (§B) is reported, not fixed.** No S2→lever angular stiffness was invented.
4. **The device (GPU) path is not wired for the tilt solver.** `matS2SolveStepTilt` is a plain-Java kernel over
   the same SoA arrays and has the same shape as its lowering-validated sibling, but it has not been built into
   a `TaskGraph`, so no CPU/GPU parity claim is made and none is needed at this stage.
5. ~~**The binding gates are not χ-aware.**~~ **RETRACTED 2026-08-13** (see
   `docs/motor/CHI_AWARE_CAPTURE_ORIENTATION_AUDIT.md` §1): `matBindExplicit` / `siteGateA` read xF8/xH from
   `outGeom`, i.e. the χ-aware head, verified to 0.000e+00. The original text follows for the record:
   they would need the χ-aware geometry before any capture decision reads the tilted head. Irrelevant to
   everything measured here (no binding is exercised), but it is a prerequisite for Phase 15.
6. **`k_det` is still DIAGNOSTIC, not calibrated** — see §6c. It was chosen from detached search behaviour
   alone; no torque, glide, recruitment or twirling quantity was consulted, and none was computed with the
   feature on.
7. **Bound-state orientational statistics are not dt-converged** at `k_bind = 512` (§4.4).

---

## 11. HARD STOP — the Phase-15 checklist

| condition | status |
|---|---|
| live neck frame proven | **PASS** (§2, Q4 table; covariant to 0.00e+00) |
| covariance fixtures pass | **PASS** (§3 A/B/C/D) |
| 3-D head dynamics stable | **PASS** (§7 stability column) |
| FDT passes | **PASS** (§5 Gates A and B) |
| broad-but-centered detached search demonstrated | **PASS** (§6: RMS 41–55° vs a 73° free control and 98° uniform) |
| stroke survives | see §7 |
| k_ext survives | see §7 |
| χ is not a dominant axial compliance mode | **PASS** (§8) |
| helical normals dynamically accessible | **PASS** (§9: 20/20, ~13× today's dwell) |
| **lever orientation constrained** | **FAIL — §B. Not a condition the task listed, because it was not known.** |

**Verdict: STOP.** Nine of the ten conditions pass. The tenth is new: making the head's rest orientation
neck-relative removed the only angular anchor the lever had, and the model has no S2→lever joint to replace
it. A site-normal binding law layered on top would be pinning a head whose neck is a free rotor, and any
resulting recruitment or twirling number would be reporting that free mode. **The ruling needed before Phase
15 is whether to add an S2→lever angular joint (new physics, no provenance, but expressible in the same live
frame with no lab reference), and — separately — whether to promote the §A axis fix, which re-baselines every
explicit-S2 result.**

---

## 12. Backward compatibility and what was added

**Default-off; OFF is byte-identical; Path A untouched; historical Path-B campaigns reproducible.**

- `TwoBodyBeamAnalyticGpu.matS2SolveStepTilt` — **new** kernel. `matS2SolveStep` byte-unchanged and still wired.
- `TwoBodyBeamAnalyticGpu.matBeamGeomTilt` — pre-existing from the previous increment; `χ = 0` identity gate
  `max |matBeamGeomTilt − matBeamGeom| = 0.000e+00`.
- `ExplicitCompleteMatHarness` — `restC` (the native-pose coefficients), `neckFrame()`, `eBindOf()`,
  `calibrateRestC()`, `HEAD_TILT_AXIS_FIX`; `params` grows 17N → 18N and the solve scratch 210 → 306 **only
  when `HEAD_TILT_3D` is on**; `matc[4]` is new and is read **only** by the tilt kernel.
- `softbox/LiveNeckHeadProbe.java` — **new**, read-only measurement harness.
- `scripts/plot_live_neck_head.py` — **new**, figures straight from the emitted TSVs.
- `BoA-v1ref` is byte-clean. No chemistry, no S2 mechanics, no `xCatch`, no converter skew, no `REG_K`, no
  `RAND_BASE_AZ` was touched. `k_det` was never tuned against glide or torque.

---

## Appendix — the historical kinematic material (retained from the previous increment)

### A1. Current planar head geometry

`eBind = R_econv(ψ)·p̂1` with `p̂1 = normalize(b̂·rF8x + ê_up·rF8y) = (+0.9191, 0, +0.3939)`, hence
`eBind · ê_conv = 0` exactly for every ψ: one great circle.

### A2. χ = 0 identity gate

```
[chi=0 IDENTITY GATE] max |matBeamGeomTilt − matBeamGeom| over 4 psi × 120 motors × 9 comps
                    = 0.000e+00   =>  BYTE-IDENTICAL
```

### A3. Kinematic (upper-bound) manifold sweep

Swept through the real `matBeamGeomTilt`, ψ ∈ [−π, π] × χ ∈ [−π/2, π/2] (16 471 kernel evaluations):

```
RESTORED   covariance eigenvalues : 0.505495  0.248619  0.245887   => rank 3
           equal-area coverage    : 2588 of 2592 bins = 12.547 sr = 99.8 % of 4pi
CURRENT    rank 2, 74/2592 bins, 2.9 %
HISTORICAL rank 3, 2535/2592, 97.8 %
```

and the kinematic site reachability, one fixed motor, 25° tolerance: current **6/20**, restored **20/20**
(worst-case best angle 0.80°), historical sphere-head 20/20 (2.19°). **§9 supersedes this with the
*dynamically* reachable set**, which is the physically meaningful quantity.

### A4. The ψ-only k_det ladder (superseded by §6)

The earlier ladder measured `SD(ψ)` with χ **not yet dynamic**, so it characterised the stiffness scale, not
the search envelope: SD(ψ) = 74.0° / 46.6° / 32.6° / 22.8° / 3.23° at k_det = 2 / 5 / 10 / 20 / 512, and
1043° at k = 0. Its qualitative reading — that k_det ≲ 2 approaches free tumbling, 5–10 is the broad-search
regime, and 512 is the measured defect — **survives** §6 in full. Its quantitative rows are superseded.

### A5. Withdrawn statements from the previous revision

- *"ψ is measured about `ê_conv` in the motor's OWN base triad, so ψ = ψ_actin is already a
  NECK/CONVERTER-RELATIVE rest pose"* — **withdrawn**, §2.1 Q5. The triad is *stored*, not live.
- *"τ_χ ≫ dt so explicit integration should be stable and the implicit block need not grow"* — **withdrawn**,
  §4.4. It is true for `k_det` and false by 3.4× for `k_bind`; χ is in the implicit block.
- *"`γ_χ = γ_ψ`"* — **retained and now proved exact for `Γ_χχ`**, but incomplete: `Γ_ψψ` acquires a `cos²χ`
  dependence (§4.3).
- The `§0` Phase-0 CASE-B recommendation (B1: retarget `ψ_actin` to the candidate site) is **still open** and
  is exactly what Phase 15 would implement; §11 says not yet.

---

# ============================ MECHANICS REPAIR (2026-08-12) ============================

**Both hard findings above are now REPAIRED and DEFAULT-ON. F8 remains a purely translational spring; the
lever joint introduces NO fitted stiffness. Nothing was tuned to make any number agree.**

- **Raw:** `RUN_LOGS/motor_audit/mechanics_repair/` (`mechanics_repair_all.txt`, `fix1_f8_axis.txt`,
  `fix2_lever_joint.txt`, `cpu_gpu_parity.txt`, `MATS2SOLVE_GATE_repaired.md`)
- **Figures:** `docs/motor/figures/mechanics_repair/`
- **Reproduce:**
  ```bash
  ./scripts/build.sh
  java @$TORNADOVM_HOME/tornado-argfile --enable-preview \
       -cp "$TDIR/tornado-api-4.0.1-dev.jar:." softbox.MechanicsRepairProbe -all
  ./scripts/run_gpu_monitored.sh ./scripts/run_mats2solve_gate.sh      # CPU/GPU parity, device TaskGraph
  python3 scripts/plot_mechanics_repair.py
  ```
- **Legacy escapes** (byte-reproduce the pre-repair motor): `-legacy-f8axis`, `-legacy-freehinge`,
  `-legacy-mechanics` (both), on `ChiralSiteHarness` and `ExplicitCompleteMatHarness`; the scalar CPU twins also
  honour `-Dsoftbox.legacyF8Axis=true`.

---

## 13. F8 VIRTUAL-WORK AXIS REPAIR

### 13.1 What was wrong, and what changed

F8 **was and remains a pure translational spring**. No angular F8 term was added, and its stiffness and rest
length are untouched. The defect was entirely in the chain rule that projects that translational force onto the
converter coordinates. The explicit-S2 geometry is

```
uB      = R_econv(phi) · eup            C   = P + l_b · uB
xF8 - C = R_econv(psi) · d0
```

so the exact Jacobian columns are `econv × (C − P)` and `econv × (xF8 − C)`. The solvers used **`eup`**. With
the converter geometry planar (`b̂`, `ê_up` in-plane, `ê_conv` the normal), `eup × (in-plane vector)` is
**orthogonal to the true column**, so an in-plane bond force fed *exactly zero* generalized load into φ and ψ.

The correction is one vector swap per assembly, applied consistently:

| site | role | change |
|---|---|---|
| `TwoBodyBeamAnalyticGpu.matS2SolveStep` | **production explicit-S2 solver (device + CPU)** | axis from `matc[4]`; canonical branch `eup→econv`, site-frame branch `cu→ce` |
| `TwoBodyBeamAnalyticGpu.matS2SolveStepTilt` | the 3-D-head solver | already carried the flag; default `econv` |
| `TwoBodyBeamAnalyticGpu.beamRelaxAnalytic` | beam relaxation assembly | `eup→econv` (Jacobian columns **and** `Q_phi/Q_psi`) |
| `TwoBodyConverterMotor.s2SolveM` | scalar twin of the production kernel | `E = f8Axis(G)` |
| `TwoBodyConverterMotor.s2Solve` | Cmot single-motor path | `E = f8Axis(cm)` |
| `ExplicitBeamSolver.assemble` | analytic-solver replica of `s2Solve` | `E = f8Axis(cm)` |
| `TwoBodyGpuKernels.explicitBeamStep` | device replica of `s2Solve` | `eup→econv`; the code comment asserting the divergence was *faithful* is withdrawn |
| `ExplicitBeamGpuHarness` (replica) | the plain-Java mirror of `beamRelaxAnalytic` | `eup→econv`, so the mirror stays a mirror |

One switch drives every runner: `matc[4]` for the device/CPU kernels, `TwoBodyConverterMotor.F8_AXIS_LEGACY`
for the scalar paths, kept in step by `ExplicitCompleteMatHarness.f8AxisFlag()`.

### 13.2 Gate A — finite difference through the live kernel

FD of the real `matBeamGeom` against the two candidate columns (µm/rad):

| quantity | value |
|---|---|
| `d xF8/d psi` (FD — the truth) | `(+2.067246e−03, 0, −7.329836e−03)` |
| `econv × (xF8 − C)` **REPAIRED** | `(+2.067247e−03, 0, −7.329836e−03)` — rel **4.05e−08** |
| `eup × (xF8 − C)` legacy | `(0, +7.329836e−03, 0)` — rel **1.388** (orthogonal) |
| `d C/d phi` (FD — the truth) | `(+6.928203e−03, 0, −4.000000e−03)` |
| `econv × (C − P)` **REPAIRED** | `(+6.928203e−03, 0, −4.000000e−03)` — rel **2.74e−08** |
| `eup × (C − P)` legacy | `(0, +4.000000e−03, 0)` — rel **1.118** (orthogonal) |

**PASS.**

### 13.3 Gate B — virtual-work closure

An arbitrary virtual displacement of *every* coordinate `xF8` depends on (`dP`, `dφ`, `dψ`) with an arbitrary
3-D bond force:

```
F8 · d xF8            (kernel FD)   = +7.696674432e-25 J
F8·dP + Qphi dphi + Qpsi dpsi       = +7.696677151e-25 J     REPAIRED
residual 2.720e-31 J  (rel 3.53e-07)                          => PASS
same sum with the legacy eup axis   = +7.034996575e-25 J     (rel error 8.60e-02)
```

**PASS** — the generalized forces now account for the work the bond actually does.

### 13.4 Gate C — an ordinary in-plane load reaches φ and ψ

5 pN along four directions; "FD ground truth" is `F8 · ∂xF8/∂q` differenced through the kernel (N·m):

| load | `Qφ` econv | `Qψ` econv | FD truth φ / ψ | `Qφ` eup | `Qψ` eup |
|---|---|---|---|---|---|
| `+b̂` (axial) | +3.4641e−20 | +1.0336e−20 | +3.4641e−20 / +1.0336e−20 (rel 3e−08 / 2e−07) | **0** | **0** |
| `+ê_up` (normal) | −2.0000e−20 | −3.6649e−20 | −2.0000e−20 / −3.6649e−20 (rel 7e−10 / 2e−11) | **0** | **0** |
| `+ê_conv` (out-of-plane) | **0** | **0** | **0 / 0** (exact) | +2.0000e−20 | +3.6649e−20 |
| oblique in-plane | +1.0353e−20 | −1.8606e−20 | +1.0353e−20 / −1.8606e−20 (rel 7e−08 / 6e−08) | **0** | **0** |

**PASS — and the third row is the sharpest statement of the defect: the ONLY load the legacy axis responded to
is the one the true geometry ignores, and it ignored every load the true geometry responds to.** The projection
was not merely inaccurate; it was orthogonal.

### 13.5 Gate D — fixed-site relaxation is stationary under the TRUE gradient

A motor bound to a fixed site 4 nm off its native F8 point, Brownian off, relaxed 40 000 steps to the solver's
own fixed point; the exact potential's gradient (F8 + converter + bind + lever joint) is then evaluated there:

| | φ at the fixed point | `dU/dφ` | `dU/dψ` | rel to the converter torque scale |
|---|---|---|---|---|
| **REPAIRED** | 0.51934 rad = **29.76°** | +1.309e−27 N·m | +2.031e−28 N·m | **1.02e−08 ⇒ STATIONARY** |
| legacy | 0.40830 rad = 23.39° | −2.426e−20 N·m | −8.991e−21 N·m | 1.90e−01 |

**PASS.** The legacy solver stopped ~7 orders of magnitude away from any stationary point of its own potential —
it was converging to the fixed point of the *wrong* variational problem.

### 13.6 Gate E — CPU/GPU parity (the triggered confirmation for a structural hot-kernel change)

`ExplicitMatSolveHarness` — a real device `TaskGraph`, run through `scripts/run_gpu_monitored.sh`
(recorder verified RUNNING beforehand), five beam shapes spanning relaxed / high-axial / transverse-bend /
near-taut / post-stroke:

```
§5  LOWERS + EXECUTES: YES (807 ms build+exec); NaN/Inf in outGeom = 0
§6  CPU-mirror vs production s2SolveM : max 1.16e-09 µm   (machine eps on µm coords)
    GPU vs CPU-mirror                 : max 9.75e-10 µm   (device lowering bit-faithful)
    solver status = 0 on every motor
§5/§6 VERDICT: PASS
```

**PASS**, and it covers both repairs (the lever joint is on in this run).

> **Pre-existing, unrelated device condition, confirmed not ours.** With FMA fusion enabled the PTX backend
> fails to compile `matS2SolveStep` with an `ArithmeticLIRLowerable`/`PTXFMANode` NPE. This was verified against
> the **pristine pre-repair kernel**, which fails identically, so it is a TornadoVM PTX backend bug, not a
> property of this change. `scripts/run_chiral_sites.sh` already carries the documented workaround
> `-Dtornado.enable.fma=false`; `scripts/run_mats2solve_gate.sh` (new) now uses the identical flag set.

### 13.7 Scope of the re-baseline

- **DETACHED motors are unaffected** (`F8 = 0` ⇒ the block contributes nothing), so the entire detached
  programme of §1–§9 stands unchanged.
- **BOUND explicit-S2 mechanics change on every path that used `eup`** — the production mat gliding path *and*
  the `Cmot` `explicit-s2-l40` single-motor path (tweezers/stroke fixtures on that model must be re-quoted).
- **`fixed-anchor` and `calibrated-s2-l40` are untouched** — `stepC`/`stepSup` always used `econv`, so the
  frozen calibrations behind `docs/MOTOR_MODELS.md` for those two models stand.

---

## 14. S2→LEVER MOMENT-TRANSFER REPAIR

### 14.1 Why the joint was free — the architecture audit

Tracing `distal S2 tangent → endpoint P → lever direction uB → φ → S2 bending energy → boundary conditions at
P`:

The beam's bending energy is a sum over joints: the **clamped** joint 0 (first element against the stored rest
tangent `g4Tan`) plus interior joints `1..M−1` (each pair of consecutive elements). **The chain simply ends at
node M.** The lever `P→C` enters the model *only* through `C = P + l_b·uB(φ)` — a pure translation of the
attachment point. No term anywhere in the tree couples `uB` to the beam's orientation (verified by search: no
bend/torque/joint term references `uB`).

So the distal end of the S2 is a **free end**, and the lever hangs off it through an **exact zero-moment pin**.
What remained holding φ:

- `k_conv(θ − θ_s)` with `θ = ψ − φ` — ties ψ to φ, a *relative* constraint;
- the head orientation potential — with the historical **base-frame** `k_bind(ψ − ψ_actin)²` this pinned ψ to
  the stored triad, and so held φ *by proxy*; replaced by a neck-relative rest pose (the entire point of the
  3-D head) it too becomes *relative*;
- **nothing else.** `(φ,ψ) → (φ+δ, ψ+δ)` was an exact zero-energy mode (Fixture C, §3).

**This was lost when the explicit-S2 beam was introduced**, not in a later port: the two-body arc replaced an
articulated rod whose orientation *was* a coordinate with a beam whose distal node contributes only a position.

### 14.2 Archaeology — is there a historical joint to restore? NO

Searched the history and the tree for a distal-tangent constraint, a lever/S2 angular joint, bending-moment
transfer, a terminal beam orientation, and tail/neck hinge stiffness. **Nothing exists.** The closest historical
analogue is decisive against Option B:

> the sphere-head articulated motor's **J2 rod↔lever joint** — the direct structural counterpart of this joint —
> had **`myoJ2FracMoveTorq = 0.00`** in the frozen v1 oracle (`BoA-v1ref/boxOfActin/Env.java:157`), i.e. it was
> *itself an exact free hinge*. CLAUDE.md records the consequence at inc 4b-i: "J2 a free hinge at ~96°".

**So OPTION B is unavailable — there is no historical stiffness, and the one historical value that exists is
zero.** Restoring it would restore the defect.

### 14.3 The repair — OPTION A: the beam's own bending mechanics, no new parameter

The lever is treated as **the terminal orientation of the S2 mechanical chain**. The joint at the distal beam
node then carries a bending moment exactly like every interior joint of the beam:

```
E    = ½ · kbend · (theta - theta0)^2 ,     cos theta = sHat · uB
sHat = (node M - node M-1)/|·|              uB = (C - P)/l_b        d uB/d phi = econv x uB
```

- **Stiffness = the beam's OWN `kbend` = EI/l₀ = 7.2000e−20 N·m/rad²** (EI from AMK 2008, the same value every
  other beam joint uses). **No new stiffness, no fit, no tuned value.**
- **Rest angle `theta0` = 81.73°**, resolved per motor from the **as-built** geometry at the native lever angle
  `PHI_PRE_3E`. This is a build-time *geometric* constant of exactly the same kind as the clamped joint 0's rest
  tangent `g4Tan` — read off the model, not fitted to anything. Calibrating at the as-built pose (not the
  seeded state) matters because `buildGlide2D` scrambles φ as an initial condition; it is the same rule
  `calibrateRestC` already follows.
- Both arms are **live geometry**, so the joint is frame-covariant by construction and contains **no lab
  reference** and no stored triad.
- Residual **and** exact Hessian (nodes `M−1`, `M`, and φ, with the mixed terms) are assembled, so the joint is
  treated implicitly like the rest of the beam.
- `TwoBodyConverterMotor.computeLeverRest0(G)` is the **single source of truth** — the scalar `s2SolveM` and the
  packed `params` row 17 both read that one array, so the two runners cannot disagree. (This mattered: the first
  implementation recomputed the angle at pack time from *perturbed* nodes and the CPU/GPU gate caught it at
  1.4e−04 µm; with the shared array it is 0.00e+00 on that same motor.)

**Scope.** Implemented in the production explicit-S2 solvers — `matS2SolveStep`, `matS2SolveStepTilt` (device +
CPU) and the scalar twin `s2SolveM`. **The `Cmot` single-motor path (`s2Solve`) and the beam-replica assemblies
(`beamRelaxAnalytic`, `explicitBeamStep`, `ExplicitBeamSolver`) do NOT carry the joint** — a deliberate, stated
limitation that keeps every beam-solver replica gate valid; it should be extended to them before the Cmot path
is used for lever-sensitive work.

### 14.4 Gates

**A — the common-rotation mode is no longer free.** `(φ,ψ) → (φ+δ, ψ+δ)`, the exact mode Fixture C showed had
zero restoring force:

| δ | `U_lever` (kT) | `dU/dφ` (N·m) |
|---:|---:|---:|
| 0° | 0.0000 | +8.0e−36 (rest) |
| 1° | 0.0027 | +1.2566e−21 |
| 5° | 0.0666 | +6.2832e−21 |
| 15° | 0.5994 | +1.8850e−20 |
| 30° | 2.3976 | +3.7699e−20 |

**PASS — the free lever rotation is gone.**

**B — rigid-body covariance.** Rotating the entire motor by an arbitrary 3-D `R`: `U_lever` 1.587600000e−21 J →
1.587600000e−21 J, `|ΔU|/U = 4.45e−14`. **PASS.**

**C — bending the distal S2 moves the lever's preferred orientation, naturally:**

| distal S2 bend | φ_min | Δφ_min |
|---:|---:|---:|
| 0° | 30.000° | reference |
| 5° | 35.002° | **+5.002°** |
| 10° | 39.998° | **+9.998°** |
| 20° | 47.189° | +17.189° |

**PASS** — near-exact 1:1 following at small bend, which is precisely the statement "the lever is the terminal
orientation of the S2 chain". No lab reference is involved.

**D — no artificial lock; the lever stays compliant:**

```
k_phi   = 7.2000e-20 N.m/rad^2   vs   k_conv = 1.2800e-19   (0.56x the converter — same order)
thermal amplitude sqrt(kT/k_phi) = 13.70 deg
relaxation tau = gamma_phi/k_phi = 8.55 us   >   dt = 2.50 us   (resolved at the production dt)
```

**PASS** — this is a compliant elastic joint, not a weld, and it does not introduce a new stiff timescale.

**E — detached equilibrium.** See §14.5.

### 14.5 Gate E — detached equilibrium, and where the residual wander actually lives

The free mode **only exists with the live-frame head potential**: the historical base-frame `k_bind` pins ψ to
the stored triad and `k_conv` ties φ to ψ, so the lever was held *by proxy*. Gate E therefore runs the 3-D-head
candidate ON (`k_det = 5`), which is the configuration that exposed the defect. 12 motors, detached, 200 ms:

| arm | SD(φ) | SD(ψ) | net φ drift | turns |
|---|---:|---:|---:|---:|
| base-frame `k_bind` (historical) | 9.38° | **3.09°** | 11.55° | 0.03 |
| live frame, **NO joint** (the defect) | 976.22° | **976.22°** | 604.64° | 1.68 |
| live frame **+ lever joint (REPAIRED)** | 119.82° | 119.68° | 137.67° | 0.38 |

The middle row **independently reproduces §B's measured 810–1077° free-rotor range**, and SD(φ) ≡ SD(ψ) there is
the common-rotation mode itself. The repair cuts the spread **8.1×** and the net drift **4.4×**, to sub-turn.

**But 119.82° is not "comparable to the historical 9.38°", so the honest question is whether the joint is
merely slack.** It is not — the follow-up measurement decides it (`-jointvar`, 38 988 samples):

| observable | SD |
|---|---:|
| φ — the lever angle **in the lab frame** | 119.82° |
| the distal S2 tangent's own angle, same plane | 26.73° |
| **θ_joint = angle(ŝ, uB) — WHAT THE JOINT ACTUALLY HOLDS** | **10.81°**  (mean 81.80°, rest 81.73°) |
| the joint's own thermal amplitude `sqrt(kT/kbend)` | 13.70° |

**The joint holds its relative angle to 10.81° — BELOW its own thermal amplitude — with the mean sitting on its
rest angle to 0.07°.** The residual lab-frame wander is therefore **inherited S2 orientation, not joint slack**:
the lever is now anchored to a *floppy beam* instead of to a lab frame, and it correctly inherits that beam's
thermal reorientation. **PASS on the observable the joint governs.**

**Carry-forward for the site-normal work (flagged, not fixed).** The lever's *absolute* orientation is now
constrained but soft — about 10× softer than the old lab-frame spring made it look. That is the physically
honest state of a lever on a thermally fluctuating S2, and it is the right baseline for a site-normal law; but
any recruitment or twirling number computed on top of it must be quoted with it. Tightening it further would
require stiffening the S2 itself, which is an MD-constrained quantity and is **not** a free knob.

---

## 15. CORRECTED CORE-MOTOR BASELINE

Deterministic, Brownian off, `LiveNeckHeadProbe -reg` — the **already-validated Phase-11 estimator**, run with
the lever joint on and off. Stroke and `k_ext` are BOUND-state quantities. **Nothing was tuned to make any of
these agree.**

> **A negative result on the way, recorded rather than buried.** `MechanicsRepairProbe`'s own first-cut
> stroke/`k_ext` estimator used a 1 pN/nm fixed-site probe spring, which is far *softer* than the motor; the
> settled F8 displacement is then dominated by the probe and `k_ext` came out ≈105 pN/nm. That is a
> badly-conditioned measurement, not a result. It is retained in the code (`rebaselineAdHoc`) behind a printed
> warning, and the reported baseline uses the validated estimator instead.

| F8 axis | lever joint | stroke (nm) | polarity | `k_ext` (pN/nm) | θ (deg) | S2 ext (nm) | contour | stable |
|---|---|---:|---|---:|---:|---:|---:|---|
| `eup` (pre-repair) | off | −8.000 | pointed | **0.9906** | −30.000 | 39.9906 | 0.999764 | yes |
| **`econv` (fix 1)** | off | −8.000 | pointed | **0.6093** | −28.014 | 39.9940 | 0.999851 | yes |
| `eup` (legacy) | on | −8.381 | pointed | 1.0074 | −25.256 | 39.9103 | 0.999974 | yes |
| **`econv` (fix 1)** | **on (fix 2)** | **−8.381** | **pointed** | **0.7211** | **−24.972** | 39.9195 | 0.999995 | **yes** |

*(bottom row = the REPAIRED DEFAULT)*

**Readings — reported as measured.**

1. **Stroke amplitude and polarity SURVIVE both repairs.** −8.000 → −8.381 nm, **pointed-first throughout**.
   The axis fix alone does not move the stroke at all; the lever joint adds **+4.8 %**. The 4E failure mode —
   a new compliant mode eating the stroke — **did not occur**; the joint *removes* a compliant mode.
2. **`k_ext`: 0.9906 → 0.6093 (axis fix) → 0.7211 (both).** The axis repair moves whole-cross-bridge stiffness
   **into the independently-calibrated `Cmot` fixed-anchor reference band of 0.60–0.64 pN/nm** — a path that has
   always used `econv` and was never touched here. Adding the lever joint stiffens it to 0.72, just above the
   band, which is the expected sign for removing a free rotational mode. **This agreement was not engineered:
   no parameter was changed, and the reference was not consulted while fixing anything.**
3. **Converter θ under load: −30.0° → −28.0° → −25.0°** — the load now reaches the converter, which is exactly
   the missing feedback §13.4 quantifies.
4. **S2 force–extension and contour conservation are unaffected** (contour 0.99976 → 0.999995; extension within
   0.08 nm), and every arm is numerically **stable**.
5. **Detached head search is unchanged by construction** (`F8 = 0` ⇒ the repaired block contributes nothing) —
   the χ / live-frame / FDT results of §1–§9 stand as published.
6. **CPU/GPU parity: PASS** (§13.6), covering both repairs.
7. **Production smoke, repaired defaults, device-monitored:** `run_chiral_sites.sh -conv-pilot` runs
   end-to-end — glide **−2.009 µm/s**, avgBound 2.25, **0 invalid, 0 solver failures**. A 1-seed 3 000-step
   pilot, so a health check, not a measurement.

### 15.1 What this re-baselines, and what it does not

- **Re-baselined:** every BOUND explicit-S2 result on a path that used `eup` — the production mat gliding path
  and the `Cmot` `explicit-s2-l40` model. Standing explicit-S2 observations that plausibly bear on the missing
  load feedback (the load-insensitivity of the stroke; the "V₀ static over-drive"; Outcome C's 1.2–1.4× speed
  advantage over the calibrated surrogate at only 67–68 % load-bearing bonds) **must be re-examined**. This
  report does **not** claim they are explained by the defect — it establishes that they were measured with a
  projection that could not feel an in-plane load.
- **NOT re-baselined:** `fixed-anchor` and `calibrated-s2-l40` (always `econv`); all detached results; the
  filament/actin layers.

### 15.2 Limitations, stated

1. The lever joint is implemented in the **production explicit-S2 solvers only** (`matS2SolveStep`,
   `matS2SolveStepTilt`, `s2SolveM`). The `Cmot` `s2Solve` path and the beam-replica assemblies
   (`beamRelaxAnalytic`, `explicitBeamStep`, `ExplicitBeamSolver`) keep the free hinge — deliberate, so every
   beam-solver replica gate stays valid, but it must be extended before the `Cmot` path is used for
   lever-sensitive work.
2. `LiveNeckHeadProbe`'s Phase-11 arm **labels are now stale**: its "legacy" arm sets `HEAD_TILT_AXIS_FIX = true`,
   which previously only affected the tilt kernel and now selects `econv` in the production solver too. The rows
   are read by configuration, not by label, in the table above.
3. The residual 119.82° lab-frame lever wander (§14.5) is a property of the model, not a defect, but it is a
   real softness that any downstream orientation-sensitive result must carry.
4. `-Dtornado.enable.fma=false` remains required for the device path — a pre-existing TornadoVM PTX bug,
   verified independent of this repair.

### 15.2b Independent confirmation of the axis edit

The Phase-11 run also contains `live512` — the **tilt kernel's separately-written `econv` implementation**,
which was authored before this repair and never touched by it. In both the joint-ON and joint-OFF runs it is
**digit-for-digit identical** to the repaired production kernel across every column:

```
joint OFF   legacy(prod, econv)  -8.000  pointed  0.6093  -28.014  39.9940  0.999851
            live512(tilt, econv) -8.000  pointed  0.6093  -28.014  39.9940  0.999851
joint ON    legacy(prod, econv)  -8.381  pointed  0.7211  -24.972  39.9195  0.999995
            live512(tilt, econv) -8.381  pointed  0.7211  -24.972  39.9195  0.999995
```

Two independently-written assemblies of the same corrected physics agree exactly — so the edit to
`matS2SolveStep` is not merely self-consistent, it reproduces a pre-existing implementation of the correct axis.

### 15.2c The 3-D-head candidate does not disturb the corrected baseline

The same Phase-11 runs carry the full χ ladder (χ dynamic + live-frame detached rest at `k_det = 5` **and**
`10`). In **both** the joint-ON and joint-OFF runs, **both** χ arms are identical to the corresponding non-χ
arm in every column:

```
joint OFF   econv, no chi   -8.000  pointed  0.6093  -28.014  39.9940  0.999851
            econv + chi5    -8.000  pointed  0.6093  -28.014  39.9940  0.999851
            econv + chi10   -8.000  pointed  0.6093  -28.014  39.9940  0.999851
joint ON    econv, no chi   -8.381  pointed  0.7211  -24.972  39.9195  0.999995
            econv + chi5    -8.381  pointed  0.7211  -24.972  39.9195  0.999995
            econv + chi10   -8.381  pointed  0.7211  -24.972  39.9195  0.999995
```

Expected, and worth recording: stroke and `k_ext` are BOUND-state quantities, the bound branch keeps the
historical base-frame target, and χ collapses to ≈0 once bound. **So the two mechanics repairs and the 3-D-head
candidate are independent** — the candidate can be evaluated on top of the corrected baseline without
re-entangling it with either repair.

### 15.3 The legacy escape reproduces the pre-repair motor exactly

The `eup`/no-joint row above (−8.000 nm, `k_ext` 0.9906, θ −30.000°, S2 39.9906 nm, contour 0.999764) is
**digit-for-digit identical to the pre-repair `legacy` row recorded before any of this work**
(`RUN_LOGS/motor_audit/restored_3d_head_tilt/live_neck_head_all.txt`), including the S2 extension and contour
columns. So `-legacy-f8axis` / `-legacy-freehinge` / `-legacy-mechanics` genuinely reproduce the old motor, and
every pre-2026-08-12 explicit-S2 run remains reproducible.

**Site-normal binding, helical capture and twirling remain OUT OF SCOPE and were not started.**

---

## 16. SINGLE-MOTOR FINE-TIME VISUAL GATE, RE-RUN ON THE REPAIRED MOTOR

**The §9b visual gate re-run with both repairs on, same harness, same scene, same seed, same camera, one
recorded frame per 2.5 µs timestep. Nothing tuned; no gate, threshold or physics changed. Both arms bound
NATURALLY, onto the SAME site as before (k = 155, segment 9).**

- **Raw:** `RUN_LOGS/motor_audit/mechanics_repair/single_motor_movie/`
- **Figures:** `docs/motor/figures/mechanics_repair/single_motor_movie/`
- **Viewer:** the pre-repair movie is preserved; the viewer now has a **motor selector** so the two can be
  compared frame-by-frame in one session.
  ```bash
  cd ~/Code && python3 SoftBox/sim_server.py 8000      # if not already running
  #   -> http://localhost:8000/SoftBox/single_motor_binding_viewer.html
  #      "motor" = REPAIRED (default) | pre-repair ; "arm" = A control | B 3-D head
  ```
- **Regenerate:**
  ```bash
  java @$TORNADOVM_HOME/tornado-argfile --enable-preview -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
       softbox.SingleMotorMovieHarness -arm both -out RUN_LOGS/motor_audit/mechanics_repair/single_motor_movie
  python3 scripts/plot_single_motor_movie.py repaired      # omit the argument for the pre-repair movie
  ```

### 16.1 What the repair changed, side by side

| | arm A pre → **post** | arm B pre → **post** |
|---|---|---|
| natural binding | 29.46 ms → **9.41 ms** | 10.14 ms → **10.18 ms** |
| site latched | k=155 seg 9 → **same** | k=155 seg 9 → **same** |
| F8→site at capture | 2.681 → **2.419 nm** | 4.232 → **3.900 nm** |
| **∠(eBind, n_site) at capture** | 67.7° → **61.4°** | 68.5° → **61.0°** |
| head-centre step at capture / detached mean | 1.38× → **1.45×** | 1.68× → **1.01×** |
| **S2 extension across the capture step** | 0.37 → **0.55 nm** | **1.06 → 0.17 nm** |
| bond persisted (this event) | 280 → **78 frames** | 204 → **104 frames** |

**Three of §9b's flagged items are resolved by the repair:**

1. **Arm B's "S2 extension drops 1.06 nm in the single capture step" is GONE** — now **0.17 nm**, a 6× reduction
   and comparable to arm A. That item was explicitly flagged as "worth watching if bound-state S2 strain is
   ever quoted"; the repaired load path no longer dumps the capture transient into the beam.
2. **Arm B's anomalous head-centre jump at capture is GONE** — 1.68× the detached mean → **1.01×**, i.e. the
   capture step is now statistically indistinguishable from an ordinary thermal step.
3. **The free lever mode is visibly gone from the contact sheet.** In the pre-repair arm B the lever (gold)
   swung through a wide range panel to panel and ψ ran −190°…+60°; on the repaired sheet the lever keeps a
   consistent orientation relative to the beam tip.

**A new, physically expected thing is now visible:** the **S2 beam bends during detached search** (a clear V in
the early panels). Before the repair the lever was a free hinge, so the head's thermal torque could never reach
the beam; now moment continuity carries it, and the beam responds. This is Gate C's coupling running in the
other direction, and it is the first time it has been visible.

### 16.2 NEW FLAG — bond persistence moved, in the same direction in both arms

**In the recorded window the bond released earlier on the repaired motor in BOTH arms: 280 → 78 frames (arm A)
and 204 → 104 frames (arm B), i.e. 703 → 198 µs and 513 → 263 µs.**

This is **ONE event per arm and is NOT a lifetime measurement** — no claim is made about the mean. But the sign
is the same in both arms and the mechanism is plausible: with the F8 projection repaired, bond load now feeds
back into φ and ψ, so cross-bridge strain evolves where previously an in-plane load produced **zero**
generalized load, and detachment reads that strain.

**Carry-forward, load-bearing:** bound lifetime, duty ratio and `avgBound` are quoted throughout the gliding,
density and viscosity work. **A proper ensemble lifetime/duty measurement on the repaired motor is required
before any of those numbers are re-used.** This is the most consequential item the visual gate surfaced.

### 16.3 Unchanged, still flagged

- **∠(eBind, n_site) is still ~61°** at capture. Improved from ~68°, but the orientation gate `g1` still checks
  ψ against the base-frame `ψ_actin`, **not** against the site normal. The motor remains actin-blind in
  orientation; that is exactly what the site-normal law would fix, and it is still out of scope.
- **The head sphere still visibly overlaps the actin surface when bound** — head radius 4.6 nm, actin 3.5 nm,
  and only the F8 *point* is constrained. There is no steric exclusion between the head body and the actin
  cylinder on this path. Unchanged by the repair, and it still looks wrong.
- **Detached motion is still diffusive-coarse at production dt** — head centre ~1.5 nm and eBind 2.2° (A) /
  10.3° (B) per single 2.5 µs step, essentially unchanged. This is the standing sub-step issue, untouched here.
- ~~**The binding gates are still not χ-aware**~~ — **RETRACTED**, see
  `docs/motor/CHI_AWARE_CAPTURE_ORIENTATION_AUDIT.md` §1. They read the χ-aware `outGeom`; the orientation
  gate is on ψ vs `ψ_actin`, which is actin-blind but not χ-blind.

**Verdict: the motor still looks and moves like the motor we intended to build, and it moves better than it did
— but bond persistence must be re-measured on an ensemble before downstream numbers are re-used.**
