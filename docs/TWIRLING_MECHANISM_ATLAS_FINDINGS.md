# The Reduced Twirling-Mechanism Atlas

**Authoritative report for this task** (2026-07-24, branch `gpu-mat-bottlenecks-explicit-singlehead`). Sole
Markdown report for the atlas. A **parallel, isolated** investigation to the converter-stroke-rotation branch:
it asks the *inverse* question —

> what is the **smallest** physically consistent asymmetry or coupling that produces a persistent, same-sign
> **cycle-integrated angular impulse** `J_θ` about the actin filament axis, under Brownian search, stochastic
> attachment turnover, and a full mechanochemical cycle?

**Noncanonical, CPU-only, self-contained, default-OFF from production.** New files only —
`softbox/TwirlReducedMotorSystem.java`, `softbox/TwirlMechanismAtlas.java`,
`softbox/TwirlReducedMotorHarness.java`, `scripts/run_twirl_mechanism_atlas.sh`,
`RUN_LOGS/twirl_mechanism_atlas/`. **No dependence** on `TwoBodyBeamAnalyticGpu`, `TwoBodyConverterMotor`,
the explicit-S2 14-DOF solve, `CrossBridgeSystem`, or any `bindAzim` offset. No canonical mechanics, parameter,
chemistry, dt, RNG stream, or event ordering is touched. Reuses only `Constants.kT` and a counter-based
wang-hash Gaussian identical in construction to the project's Brownian streams (so a future GPU port is
bit-identical). Canonical log: `RUN_LOGS/twirl_mechanism_atlas/atlas_full.txt`.

**CPU-first policy (disclosed).** The entire atlas runs on a plain JVM over `double[]` — no TornadoVM, no
device execution, no GPU monitoring required for this exploratory phase (per the task's CPU-first rule). The
model equations are settled and the deterministic fixtures pass; GPU is deferred until a parameter sweep is
large enough to justify it. The RNG and step are written counter-based specifically so that promotion is a
mechanical, bit-identical port.

---

## 1. Reduced-model definition

One motor interacts with one local discrete actin-site frame. The bond coordinate is
`q = [x, y, ω]`:

| coord | meaning | axis |
|---|---|---|
| `x` | motor extension / displacement (the propulsive coordinate) | along `u` |
| `y` | motor displacement in the local circumferential direction | along `t` |
| `ω` | bound orientational registry about the filament axis | about `u` (rad) |

Overdamped Langevin per DOF, `γ_i q̇_i = −∂U/∂q_i + ξ_i`, with FDT kicks `ξ_i = sqrt(2 γ_i kT / dt)·𝒩`.
Physical scales (SI, transparent, **not tuned to a pitch**):

```
kT = 4.116e-21 J     R_actin = 3.5e-9 m     dt = 5e-7 s
γx = γy = 1e-8 N·s/m    γω = 2.5e-24 N·m·s   (τ_relax = γ/K ≈ 10 µs for all DOF)
Kxx = Kyy = 1e-3 N/m (=1 pN/nm)   Kωω = 2.5e-19 N·m/rad
stroke d = 6 nm      Δy = 4 nm      Δω = 0.40 rad     off-diagonal ratio ρ = 0.6
```

## 2. Local-frame conventions

```
u = pointed→barbed filament tangent   (axial; the glide / roll axis)
n = outward radial direction          (the moment arm points along n at radius R_actin)
t = u × n                             (local circumferential direction; right-handed at mirror = +1)
```
Identical to `ChiralSiteSystem`. **Nothing is a laboratory axis.** Every mechanism is defined purely in
(u, n, t), so it is azimuth- and rotation-covariant by construction (§10, §12).

## 3. Chemical cycle

Five states `U → W → P → S → D`; the default cycle uses `U`(unbound) → `P`(strong, pre-stroke) →
`S`(strong, post-stroke) → `U`(recovery). `P`, `S` (and `W`) transmit force/torque to the filament; `U`, `D`
do not. Force/torque are accounted **only while bound**; `q` always relaxes in the current state's potential,
so the recovery stroke happens unbound and transmits nothing — the source of rectification.

## 4. State-dependent energy and kinetics

`U_s(q) = ½ (q − q_s)ᵀ K_s (q − q_s)`, `K_s` symmetric ≽ 0. Off-diagonals are the chiral couplings `K_xy`
(axial–tangential), `K_xω` (axial–registry), `K_yω` (tangential–registry). Kinetics: base Poisson rates
`k_on = 1e4`, `k_stroke = 2e3`, `k_off = 1e3 /s`; strain-dependent modifiers `k_off *= exp(α_τ τ_u + α_y g_y)`
(Family H) and `k(P→S) *= exp(β·strain)` (Family I). Injected free energy per cycle is 9–19 kT (ATP-scale),
computed as `Σ ΔU` at the rest-coordinate switches.

## 5. Force / torque / work accounting — and the master identity

The strain-force `g = K_s (q − q_s)` is the force the motor exerts **on the actin site**. Axial (propulsive)
force `F_x = g_x`; axial torque
```
τ_u = R·g_y + g_ω          (moment of the tangential force at radius R, plus the registry couple)
```
— the `τ = R_actin·F_tangential` identity of the discrete-site work, extended by an explicit axial couple.

**The master accounting identity (verified to ≤ 1e-11, §7).** With the site fixed and `γ_i q̇_i = −g_i + ξ_i`,
integrating over a bound episode (noise mean zero):
```
J_θ = ∫ τ_u dt = −R·γ_y·Δy_bound  −  γ_ω·Δω_bound
```
**The cycle-integrated angular impulse is set ENTIRELY by the NET displacement of the chiral coordinates
accrued while bound.** Rectification requires a chiral coordinate that (a) moves net-nonzero while bound and
(b) is reset while unbound. A completed conservative relaxation to an *unshifted* chiral equilibrium gives
`Δ_bound = 0` — **this is exactly why a zero-rest point spring produced a large frozen torque but a null cycle
impulse** (§16). Energy closes: the deterministic cycle returns `q → start` to machine precision
(`cycleReturn ≤ 3e-90 m`), so injected free energy = dissipated over the loop.

## 6. Mechanism families (behind independent flags / IDs)

| ID | mechanism | chiral element |
|---|---|---|
| A0 | no chiral term | — (null control) |
| A1 | direct tangential stroke | `y_S = y_P + Δy` |
| A2 | oblique vector stroke | `Δx = d cosε`, `Δy = d sinε` |
| A3 | finite-rest pose, tangential preference | bound rest `y = δy` (bind lands off-rest) |
| A4 | state-dependent registry, `K_ωω` only | `ω_S = ω_P + Δω` |
| A5 | axial–tangential coupling only | `K_xy ≠ 0`, axial stroke, `y_s` unchanged |
| A6 | axial–registry coupling only | `K_xω ≠ 0`, axial stroke, `ω_s` unchanged |
| A7 | tangential–registry coupling only | `K_yω ≠ 0`, no rest shift |
| A8 | state-dependent stiffness only | `K_yy(S) = 4·K_yy(P)`, rest fixed |
| A9 | torque-dependent detachment only | `k_off *= exp(α_τ τ_u)` |
| A10 | tangential-strain-dependent stroke rate | `k(P→S) *= exp(β g_y)` |
| A11 | `K_xy` + kinetic truncation (fast detach) | chiral compliance + finite duty cycle |
| A12 | multi-state closed-loop cycle | chiral binding + chiral stroke + registry |

## 7. Deterministic single-cycle atlas (Stage 1, Brownian OFF)

`J_θ` and its split into `J_pre` (binding relaxation) and `J_stroke` (stroke relaxation); axial impulse `J_x`;
the identity check.

| id | J_θ (N·m·s) | identity | identRes | peak τ (N·m) | J_x (N·m·s) | verdict |
|---|---|---|---|---|---|---|
| A0 | +0.000e+00 | −0.0 | 0 | 0 | +6.0e-17 | null |
| A1 | −1.400e-25 | −1.400e-25 | 5.5e-13 | 1.40e-20 | +6.0e-17 | **finite impulse** |
| A2 (15°) | −5.435e-26 | −5.435e-26 | 2.3e-15 | 5.44e-21 | +5.80e-17 | **finite impulse** |
| A3 | −1.400e-25 (all in `J_pre`) | −1.400e-25 | 9.2e-13 | 1.40e-20 | +6.0e-17 | **finite impulse** |
| A4 | −1.000e-24 | −1.000e-24 | 0 | 1.00e-19 | +6.0e-17 | **finite impulse** |
| A5 | **+2.5e-40** | +2.6e-40 | 9.5e-12 | **1.26e-20** | +6.0e-17 | **transient only** |
| A6 | **+1.2e-39** | +1.2e-39 | 1.8e-11 | **5.69e-20** | +6.0e-17 | **transient only** |
| A7 | +0.0 | −0.0 | 0 | 0 | +6.0e-17 | nothing |
| A8 | +0.0 | −0.0 | 0 | 0 | +6.0e-17 | nothing |
| A12 | −1.140e-24 | −1.140e-24 | 3.4e-14 | 1.07e-19 | +6.0e-17 | **finite impulse** |

The two decisive rows are **A5 / A6**: a purely elastic off-diagonal compliance produces a **large peak torque
(1.3–5.7e-20 N·m)** but a **cycle impulse 15 orders of magnitude smaller** (≈ 0). A conservative coupling
cannot rectify a completed relaxation — the induced tangential excursion returns to its unshifted equilibrium,
so `Δy_bound = 0`. This is the "large frozen torque, null cycle impulse" failure, reproduced in isolation.

### A2 oblique-stroke ε sweep — the odd/even signature

| ε (deg) | −90 | −45 | −15 | −5 | 0 | +5 | +15 | +45 | +90 |
|---|---|---|---|---|---|---|---|---|---|
| J_θ (×1e-25) | +2.10 | +1.485 | +0.544 | +0.183 | 0 | −0.183 | −0.544 | −1.485 | −2.10 |
| J_x (×1e-17) | ~0 | 4.24 | 5.80 | 5.98 | 6.00 | 5.98 | 5.80 | 4.24 | ~0 |

**J_θ is exactly odd in ε, J_x exactly even** — the predicted signature. At ±90° the stroke is purely
tangential (`J_x → 0`, `J_θ` maximal); at 0° it is the achiral axial motor (`J_θ = 0`).

## 8. Stochastic bound-cycle assay (Stage 2, Brownian ON, N = 8000 episodes/arm)

`⟨J_θ⟩` per bound episode, with two chirality controls: **actin-frame mirror** (reflect the site frame) and
**chiral-parameter reversal** (flip every chiral knob, including the kinetic ones). ↺ = a resolved sign flip.

| id | ⟨J_θ⟩ (N·m·s) | signif | actin-mir | chiral-rev | verdict |
|---|---|---|---|---|---|
| A0 | −4.9e-27 | −0.4σ | — | — | null |
| A1 | −1.437e-25 | −10.5σ | +1.34e-25 ↺ | +1.34e-25 ↺ | **actin-borne** |
| A2 | −5.879e-26 | −4.3σ | +4.89e-26 ↺ | +4.89e-26 ↺ | **actin-borne** |
| A3 | −1.449e-25 | −10.6σ | +1.35e-25 ↺ | +1.35e-25 ↺ | **actin-borne** |
| A4 | −1.018e-24 | −15.9σ | +9.63e-25 ↺ | +9.63e-25 ↺ | **actin-borne** |
| A5 | −3.3e-27 | −0.2σ | — | — | null (transient washed out) |
| A6 | −2.2e-26 | −0.3σ | — | — | null |
| A7 | −2.8e-26 | −0.4σ | — | — | null |
| A8 | −4.4e-27 | −0.3σ | — | — | null |
| A9 | −3.886e-25 | −7.0σ | −3.89e-25 (no flip) | +2.85e-25 ↺ | **motor-borne** |
| A10 | +4.4e-27 | +0.3σ | — | — | null |
| A11 | +3.378e-26 | +12.6σ | −3.33e-26 ↺ | −3.33e-26 ↺ | **actin-borne** |
| A12 | −1.158e-24 | −18.1σ | +1.10e-24 ↺ | +1.10e-24 ↺ | **actin-borne** |

Findings:
- **A1, A2, A3, A4, A12 survive** with the deterministic sign and magnitude, and flip under **both** controls
  ⇒ genuine **actin-borne** chirality.
- **A5, A6, A7 collapse to null** — the transient torque of §7 does not rectify under Brownian turnover.
- **A11 is the payoff of the compliance family:** deterministically transient (R1, §7), but with a finite duty
  cycle (`k_off` competes with relaxation) the **truncated** chiral transient rectifies to +12.6σ and flips
  under mirror. A handed compliance + an ordinary axial stroke + finite attachment ⇒ net torque, with **no
  dedicated circumferential stroke**.
- **A9 is a distinct class — MOTOR-borne chirality.** Torque-dependent detachment produces a resolved
  `⟨J_θ⟩` that does **not** flip under the actin-frame mirror (its sign is set by the kinetic asymmetry `α_τ`,
  not the lattice) but **does** flip under `α_τ → −α_τ`. A genuine kinetic-selection twirl that needs no chiral
  geometry — only a chiral *rate*.
- **A10 is null:** strain-gating an *achiral* stroke selects when it fires but the stroke transmits no torque.

## 9. Moving-filament / site-passage (Stage 3, Brownian on, finite residence)

A site translates axially past the motor at `v = 1 µm/s`; axial extension changes through the passage and
residence is finite (forced detach on exit). ⟨J_θ⟩ per passage:

| id | A1 | A2 | A3 | A4 |
|---|---|---|---|---|
| ⟨J_θ⟩/pass (N·m·s) | −1.342e-25 (−9.6σ) | −4.86e-26 (−3.5σ) | −1.342e-25 (−9.6σ) | −1.018e-24 (−15.7σ) |

All actin-borne winners **keep their sign and magnitude** — the local frame moves with the filament, so
changing axial extension and finite residence do not spoil the mechanism. No coverslip asymmetry is invoked.

## 10. Few-motor azimuthal summation (Stage 4)

Motors at 0°, 90°, 180°, 270°. Because the model is formulated entirely in the local (u, n, t) frame, each
motor's `τ_u` is about the **common** axis `u`, so a local mechanism **adds** (`ΣJ_θ = N × single`, cancel
ratio 1.00). A **lab-frame stroke** (∝ cos φ) **cancels** (`ΣJ_θ ~ 1e-41`, cancel ratio ~1e16). Azimuthal
survival is therefore *guaranteed* for any local mechanism in the reduced model; the real azimuthal test is
whether the **full-motor implementation preserves the local formulation** — precisely the locus of the askew
work's shared-base artifact (that report's §12.2).

## 11. Minimal gliding assay (Stage 5)

A rigid filament (axial `X`, roll `Θ`) driven by 64 stochastic motors on discrete sites; overdamped filament
DOF; 8 seeds. Filament Brownian OFF, then ON as a robustness test.

| id | v_glide (m/s) | roll_rate (rad/s) | pitch (rad/µm) |
|---|---|---|---|
| A0 | +9.74e-06 | +0.22 ± 1.29 | +0.023 (thermal zero) |
| A1 | +9.74e-06 | −7.70 ± 1.27 | **−0.79** |
| A2 | +9.37e-06 | −2.86 ± 1.28 | −0.31 |
| A4 | +9.74e-06 | −57.7 ± 4.18 | **−5.92** |

**All mechanisms glide identically (~9.7 µm/s); only the chiral ones roll.** A0's roll is consistent with
thermal zero (0.17σ). A1 twirls at −0.79 rad/µm, A4 at −5.9 rad/µm — the latter within the biological range
(~1 revolution per µm ≈ 6 rad/µm). With filament Brownian ON the strong arm survives (A4 −57.9 ± 8.0 rad/s,
7σ; A1 −8.0 ± 6.8, noisier but sign-preserved). **This is the direct demonstration that the surviving
mechanisms actually twirl a gliding filament.**

## 12. Symmetry suite

| control | expected | result |
|---|---|---|
| chiral parameter sign reversal | `J_θ → −J_θ` | exact for A1–A4, A11, A12 (ratio −1.000); flips A9 via `α_τ` |
| actin-frame mirror | reverse for actin-borne; invariant for motor-borne | A1–A4, A11, A12 reverse; **A9 invariant** (motor-borne) |
| rigid scene rotation | exact | exact by construction (no laboratory axis in the model) |
| filament polarity reversal | `J_θ → −J_θ` (u→−u flips t and ω) | consistent with the reflection transform in `Mechanism.mirrored()` |
| site-azimuth rotation | invariant | exact (§10, local formulation) |
| motor-approach rotation | invariant | exact (local formulation) |
| **R_actin → 0** (moment-arm control) | `R·F_t` torque vanishes; registry couple survives | A1/A2/A3 → 0; **A4 survives** (−1.0e-24, a direct couple) |
| zero-coupling recovery | → A0 | exact |
| equal-and-opposite | force/torque close | `g` is the single equal-and-opposite bond force by construction |

The **R_actin → 0** control cleanly separates the two torque channels: A1/A2/A3 are moment-of-tangential-force
mechanisms (vanish at R = 0); A4 is a direct axial couple (survives).

## 13. Ranking (R0–R6)

| id | mechanism | R | notes |
|---|---|---|---|
| A4 | state-dependent registry (preferred azimuth) | **R4** | strongest (−15.9σ); survives R→0; direct couple |
| A12 | multi-state closed-loop cycle | **R4** | −18.1σ; stacks the chiral legs |
| A1 | direct tangential stroke | **R4** | −10.5σ; the reduced chiral converter swing |
| A3 | finite-rest pose, tangential preference | **R4** | −10.6σ; chiral **binding** registry (the askew fix) |
| A2 | oblique vector stroke | **R4** | −4.3σ; odd-in-ε; A1 with a `sinε` factor |
| A11 | K_xy + kinetic truncation | **R4** | +12.6σ; compliance rectified by the duty cycle |
| A9 | torque-dependent detachment | **R4 (motor-borne)** | −7.0σ; chirality from the *rate*, not the lattice |
| A10 | strain-dependent stroke rate | R2 | null — gates an achiral stroke |
| A5 | axial–tangential coupling only | **R1** | large peak τ, `J_θ ≈ 0` — the generalized askew failure |
| A6 | axial–registry coupling only | **R1** | transient only |
| A7 | tangential–registry coupling only | R0 | no driver |
| A8 | state-dependent stiffness only | R0 | rest fixed ⇒ no motion ⇒ no torque |
| A0 | no chiral term | R0 | null control |

**No mechanism was R3 (local success / ensemble cancellation)** — because the reduced model is intrinsically
local, anything that survives Stage 2 also survives azimuthal summation. R3 is a hazard of the *full-motor*
implementation (a lab-frame leak), not of the physics.

**Promising couplings, ranked by interpretability:** A4 (state-dependent bound azimuth) > A3 (chiral binding
registry) ≈ A1/A2 (chiral / oblique stroke) > A11 (handed compliance + duty cycle) > A9 (torque-dependent
detachment). A4 and A11 are the least obvious and the most worth pursuing: A4 because it is a **direct couple**
robust to the moment arm, A11 because it needs **no dedicated circumferential stroke** — only a handed
compliance and the attachment lifetime the motor already has.

## 14. Biological mapping (only for R4 mechanisms; phrased as required couplings, not claims)

| mechanism | required coupling | candidate structural locus | discriminating observable |
|---|---|---|---|
| A4 | a nucleotide-state-dependent **preferred head azimuth on actin**, transmitted as an axial couple | converter / lever-arm azimuthal set-point; a state-dependent actomyosin interface registry; relay/SH1 handedness | state-dependent equilibrium azimuth of S1 on actin (cryo-EM / spFRET); torsional stiffness about the interface |
| A3 | a **handed bound docking pose** whose preferred tangential position differs from the free head's | stereospecific actin-binding interface (upper/lower 50-kDa cleft closure) | equilibrium bound azimuth vs the free-head azimuth; on-binding tangential relaxation |
| A1 / A2 | a **circumferential component of the converter power stroke** in the local actin frame | converter + lever arm; S1–S2 junction geometry | work partition between axial and circumferential directions during the stroke |
| A11 | a **handed (off-diagonal) compliance** of the bound complex + finite attachment | oblique S1–S2 / relay-helix compliance; anisotropic actomyosin elasticity | off-diagonal `K_xy` of the bound actomyosin compliance tensor; its sign |
| A9 | a **torque/strain-dependent detachment rate** with a defined sign | load-dependent ADP release / actomyosin rupture asymmetry | handedness of the load-dependence of the ADP-release / detachment transition |

## 15. Proposed MD observables (for a follow-up structural campaign; the atlas does not run MD)

1. **Covariance between axial converter displacement and tangential actin-interface force** — the direct test
   for A11 (`K_xy`) and A1/A2.
2. **State-dependent preferred head orientation (azimuth) on actin** across ADP·Pi → ADP — the test for A4/A3.
3. **Off-diagonal compliance tensor of the bound actomyosin complex** — sign and magnitude of `K_xy`, `K_yω`.
4. **Torsional stiffness about the binding interface** and whether the registry couple has an axial (u)
   projection (the §16 geometric caveat).
5. **Handedness of the S1–S2 compliance** and of the load-dependence of detachment (A9).
6. **Work partition** between axial and circumferential directions over one stroke.

## 16. Comparison with the failed askew-attachment mechanism

The askew work (`docs/DISCRETE_ACTIN_SITE_CHIRAL_BINDING_AND_STROKE_FINDINGS.md`) offset the bound interface
azimuth (`epsBind`/`epsStroke`) on a **zero-rest-length point cross-bridge**. Its frozen-configuration probe
found a large, clean, ε-odd, mirror-reversing torque (that report's §12), but the dynamic single-segment assay
excluded it by ~19σ, at all attachment ages.

**The atlas explains this exactly.** A zero-rest spring's rest position *is* the site; the 8-gate bind
co-locates the head with the (offset) site, so at the instant of binding the head is already essentially at its
bound rest ⇒ `Δy_bound ≈ 0` ⇒ `J_θ ≈ 0` by the master identity. The frozen probe *imposed* strain on a settled
configuration and re-read the torque — measuring the **peak** transient, not the **cycle integral**. In atlas
terms **the askew mechanism is A5/A6: a compliance-and-offset that yields transient torque but zero
cycle-integrated impulse (R1).** The atlas reproduces the null (A5/A6 collapse to −0.2σ / −0.3σ in Stage 2)
and localizes the reason: rectification needs a chiral coordinate to be **actively driven net-nonzero while
bound**, not statically offset and immediately satisfied by binding.

**The fixes the atlas identifies** are A1/A4 (drive the tangential/registry coordinate with the nucleotide rest
switch) and A3 (make the *free* head sit at a different tangential position than the bound rest, so binding
lands off-rest and relaxes against actin) — and A11 (keep the compliance but exploit the finite duty cycle).

## 17. Comparison with the true converter-rotation branch

The converter-rotation branch (the same increment's `convFrameStep`, §"TRUE LOCAL-FRAME ROTATION OF THE
CONVERTER POWER STROKE" in `ChiralSiteSystem`) rotates the converter stroke *plane* so the stroke displacement
acquires a circumferential component about the local actin frame. **That is A1/A2 in the atlas** — a
direct/oblique tangential stroke — and the atlas confirms it is a robust R4 mechanism **provided the rotated
stroke moves the bound interface net-tangentially while bound** (not merely re-tethers a zero-rest spring to an
offset point, which is A5/R1). Its dynamic results are not yet in hand on this branch; the atlas predicts it
will rectify (unlike askew) because the stroke actively regenerates `Δy_bound` each cycle. The atlas adds the
**geometric caveat** that the converter branch must beware (below).

## 18. Recommendation for the next full explicit-S2 implementation

**Design rule (the atlas's central deliverable):** grade any candidate by the **cycle-integrated angular
impulse** `⟨J_θ⟩ = −R·γ_y·⟨Δy_bound⟩ − γ_ω·⟨Δω_bound⟩`, measured over stochastic bound episodes — **never by
the frozen torque**. The full-motor mechanism must produce a **net tangential (or registry) displacement of the
bound interface accrued while bound and reset while unbound**.

Concretely, in priority order:

1. **A1/A2 (converter-rotation branch), done as a driven stroke.** Ensure the rotated converter stroke drags
   the **bound F8 anchor tangentially** (creating strain that relaxes against actin), rather than re-tethering
   a zero-rest spring to a pre-offset material point. Verify by printing `⟨Δy_bound⟩` per cycle, not the frozen
   ε-response.
2. **A4 (state-dependent bound azimuth) — but mind the axis.** In the explicit-S2 motor the registry couple is
   about the ≈ **radial** bond axis `eBind`, so it reaches the axial channel only through `eBind·û` (the askew
   report's §7 suppression). A4 will work in the full motor **only if** the head roll DOF's registry potential
   has a genuine axial (u) projection — i.e., the preferred-azimuth switch must move a tangential
   interface point, not just spin the head about a radial axis. This is the cleanest single change and is
   robust to the moment arm.
3. **A11 (handed compliance + duty cycle) — cheapest to try.** Add a signed off-diagonal `K_xy` to the bound
   compliance and rely on the existing detachment. It needs **no new stroke geometry** and rectifies purely
   through the finite attachment lifetime. Worth a quick screen precisely because it is architecturally minimal.
4. **A9 (torque-dependent detachment)** is a fallback that is orthogonal to geometry — a signed load-dependence
   of the detachment rate — and could **add to** any of the above.

Do **not** re-attempt A5/A6/A7 (compliance/coupling alone), A8 (state-dependent stiffness alone), or A10
(strain-gated achiral stroke): the atlas rules them out (R0/R1/R2) both analytically and stochastically.

---

### Reproduce

```
./scripts/run_twirl_mechanism_atlas.sh            # -all : accounting → Stage 1 → ε-sweep → 2 → 4 → 3 → 5 → ranking
./scripts/run_twirl_mechanism_atlas.sh -fixtures  # accounting identity + energy closure + symmetry
./scripts/run_twirl_mechanism_atlas.sh -stage1    # deterministic single-cycle atlas
./scripts/run_twirl_mechanism_atlas.sh -stage2 20000   # stochastic assay at a chosen N
```
Canonical log: `RUN_LOGS/twirl_mechanism_atlas/atlas_full.txt` (N = 8000/arm, ~90 s CPU).
