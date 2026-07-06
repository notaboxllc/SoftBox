# Is the explicit EOM integration numerically UNSTABLE at production dt=1e-5 under realistic load — and which coupling sets the limit?

**Date:** 2026-07-05. New files only (`ExternalSpringSystem`, `EomStabilityHarness`, `run_eomstab.sh`);
**default byte-identical** (ExternalSpringSystem is referenced by no other harness / no production path);
`BoA-v1ref` untouched; no kinetics/stroke retune. This is a **NUMERICAL-STABILITY probe, NOT a transport
measurement** — no gliding speed is inferred or reported. Follows `COUPLED_IMPLICIT_XB_FINDINGS.md` /
`STROKE_DT_RATE_DIAGNOSIS.md` (which localized the residual glide climb to "the still-explicit
stroke/rotation/force couplings" but on the confounded dense assay where `net = avgBound × per-bound-drift`
and every factor carries its own dt-dependence). This harness strips all that away.

---

## PLAIN ANSWER (headline)

**YES — the explicit forward-Euler integration of the filament EOM is numerically UNSTABLE at production
dt=1e-5 under a realistic ELASTIC load, and the stiff coupling is the LOADED force itself — NOT the motor's
F8, its rotational couplings, or the filament chain.**

- **`dt_crit ∝ γ / (k_ext + k_F8)`** — the extracted `k_eff/γ` is **perfectly LINEAR in k_ext** (slope
  4.19e4 per pN/nm, intercept = the 1 pN/nm bound-F8), the **textbook explicit-Euler load-stiffness
  fingerprint**. Under a **rigid, pure-elastic** load the explicit filament EOM goes **MARGINAL (ring onset)
  at k_ext ≈ 1.4 pN/nm and UNSTABLE (blows up) at k_ext ≈ 3.8 pN/nm** at dt=1e-5. A rigid dense-ensemble
  load (~15–20 pN/nm) sits at α = k_eff·dt·1e6/γ ≈ 7–11 ≫ 2 — **deep in the growing-oscillation regime**.
- **The three toggles all give the SAME load-stiffness scaling** (localization, decisive):
  - **`-rot`** (unfreeze the motor body + F9/F10/stroke-held rotational couplings): **identical** dt_crit map
    (slope 4.18e4, marginal 1.4 / 3.8 pN/nm) ⇒ the **motor's rotational couplings do NOT set the limit**.
  - **`-nof8`** (F8 cross-bridge off): same slope, intercept drops by exactly 1 pN/nm (marginal shifts
    1.4→2.4 / 3.8→4.8) ⇒ **F8 is a fixed +1 pN/nm additive term, NOT the stiff coupling** (so making F8
    implicit, as the prior coupled-star did, removes only ~1 pN/nm of a ~15–20 pN/nm load — negligible).
  - **`-chain 10`** (multi-segment filament + F3/F4 bending/torsion): same load scaling (slope 3.93e4). The
    chain's own internal bending mode is **soft** (dt_crit ~3e-4 at k_ext=0, well above production) ⇒ **the
    filament internal DOFs are NOT the stability bottleneck** (answers jba's filament-stiffening question).
- **CURE (`-extimplicit`, control):** making the LOADED force implicit (backward-Euler operator split)
  **REMOVES the k_ext dependence of the boundary** — the whole dt≤2e-5 region is MONO at **every** k_ext, and
  the entire **production dt=1e-5 column is STABLE at all realistic k_ext**. The only residual limit is the
  still-explicit F8 at dt≥5e-5 (dt_crit_F8 ~2.4e-5, k_ext-independent, far above production).
- **⇒ The stiff coupling is the total LOADED elastic force a filament segment sees. The actionable fix is an
  implicit / sub-step treatment of that LOADED force** (consistent with `substep-feasibility-verdict` and the
  prior arc's "sub-step the cross-bridge"). **Sharper than the prior arc:** the limiter is the *magnitude of
  the collective load*, not any one motor-internal coupling — so a stabilization scheme must cover the
  **collective** cross-bridge force, not just one motor's F8 stretch.
- **The nuance (do not over-read):** this is a **rigid, pure-elastic worst-case** load. The real gliding
  ensemble is compliant + mobile (Jacobi-coupled, `jacobi-cobound-scheme-risk`) and the load is **shared
  across the filament's segments**, so the *effective* rigid-equivalent per-segment stiffness is softer than
  a literal 15 pN/nm anchor. This harness proves the **mechanism is real and gives the k_ext threshold**;
  whether a given scene's effective load crosses ~4 pN/nm determines whether its dt-climb is this instability
  vs. the model-definition confounds (rate definitions + tug-of-war/binding-count scaling). It is **not the
  "phantom" verdict** (the mechanism is genuine and cheap to trigger), and it is **not motor-internal**.

---

## Setup

Single filament + one **permanently-bound** motor (no bind/unbind — the kinetics dt-dependence removed) +
an **external elastic COM tether** of stiffness `k_ext` anchored at the loaded equilibrium (zero rest
offset), standing in for "many other myosins" (a bound F8 ≈ 1 pN/nm ⇒ dense collective ~15–20 pN/nm).
**Brownian OFF ⇒ deterministic** (no seeds). Measured `γ_seg,∥ = 2.389e-8 N·s/m`, `k_F8 = 1 pN/nm`.

**PRIMARY — relaxation / impulse response:** settle to loaded equilibrium, anchor the spring there, displace
the filament +2 nm along the axis, release, integrate the overdamped relaxation (stroke held at rest,
Brownian off). Explicit Euler on the single linear translational mode has the textbook signature as
`α = 1e6·k_eff·dt/γ` crosses 1 then 2: **MONO** (monotone decay, α<1) → **RING** (damped ringing, 1<α<2) →
**GROW** (growing oscillation, α>2). Per (k_ext, dt) we classify the decay (first-step factor ρ = 1−α and
the envelope), and extract dt_crit. The **default** config freezes the filament rotation + the motor body ⇒
the clean single translational mode (the task's "avoid a rotational DOF that muddies the mode").

The external spring is one additive per-segment term (`ExternalSpringSystem`, planar SoA, disjoint writes,
no atomics) — device-capable and CPU≡GPU. It touches no existing kernel ⇒ default byte-identical.

## PRIMARY grid (default: rigid single segment, motor frozen, F8 on)

`γ_seg,∥ = 2.389e-8 N·s/m; k_F8 = 1.00 pN/nm`

| k_ext (pN/nm) | 1e-4 | 5e-5 | 2e-5 | **1e-5** | 5e-6 | 2.5e-6 | dt_crit ring / blow (s) | resid@eq |
|--:|:--:|:--:|:--:|:--:|:--:|:--:|--:|--:|
| 0    | GROW | GROW | MONO | **MONO** | MONO | MONO | 2.39e-5 / 4.78e-5 | 0.000 pN |
| 1    | GROW | GROW | RING | **MONO** | MONO | MONO | 1.19e-5 / 2.39e-5 | 0.000 pN |
| 5    | GROW | GROW | GROW | **GROW** | RING | MONO | 3.98e-6 / 7.96e-6 | 0.000 pN |
| 15   | GROW | GROW | GROW | **GROW** | GROW | RING | 1.49e-6 / 2.99e-6 | 0.000 pN |
| 30   | GROW | GROW | GROW | **GROW** | GROW | GROW | 7.70e-7 / 1.54e-6 | (grew) |
| 50   | GROW | GROW | GROW | **GROW** | GROW | GROW | 4.68e-7 / 9.37e-7 | (grew) |

**Reads:** (1) at production dt=1e-5, k_ext≥5 pN/nm already **GROW**; k_ext=1 is MONO; the boundary is
**k_ext ≈ 1.4 (ring) / 3.8 (blow-up)** pN/nm. (2) The **force-balance residual is exactly 0 pN** at every
settled (non-grown) equilibrium — the integrator's fixed point is the true force balance. (3) Equilibrium
**settled displacement → 0** (converges) for all stable cells.

## DECISIVE — dt_crit vs k_ext scaling (`k_eff/γ·1e6`, extracted single-mode-exact from the finest dt)

| k_ext (pN/nm) | dt_crit_ring | dt_crit_blow | k_eff/γ·1e6 |
|--:|--:|--:|--:|
| 0  | 2.389e-5 | 4.777e-5 | 4.187e4 |
| 1  | 1.194e-5 | 2.389e-5 | 8.373e4 |
| 5  | 3.981e-6 | 7.962e-6 | 2.512e5 |
| 15 | 1.493e-6 | 2.986e-6 | 6.699e5 |
| 30 | 7.705e-7 | 1.541e-6 | 1.298e6 |
| 50 | 4.683e-7 | 9.367e-7 | 2.135e6 |

`k_eff/γ` is **linear in k_ext** with slope 4.19e4 per pN/nm and intercept 4.19e4 (= the 1 pN/nm F8) ⇒
`k_eff = k_ext + k_F8`, `dt_crit ∝ γ/(k_ext + k_F8)`. **Textbook explicit-Euler load stiffness.**

## Toggle rows (localize the stiff coupling — full logs `RUN_LOGS/2026-07-05_eom_stability.txt`)

| config | slope k_eff/γ per pN/nm | marginal ring / blow @1e-5 | read |
|---|--:|--:|---|
| default (frozen) | 4.19e4 | 1.4 / 3.8 | load stiffness |
| **-rot** (motor + fil rotation free) | 4.18e4 | 1.4 / 3.8 | **rotational couplings do NOT move it** |
| **-nof8** (F8 off) | 4.19e4 | 2.4 / 4.8 | **F8 = fixed +1 pN/nm additive, not the limiter** |
| **-chain 10** (F3/F4 on) | 3.93e4 | 2.5 / 5.0 | **filament internal DOFs are soft, not the limiter** |

All three toggles reproduce the SAME `dt_crit ∝ γ/(k_ext+k_F8)` load-stiffness law. The stiff coupling is
the **loaded (external-spring) path**, invariant under which motor/filament couplings are active. (Two
k_ext=0 multi-mode classification artifacts — `-rot` @2e-5 and `-chain` @k_ext=0 — are a fast motor-body
transient and the chain's free rigid-body drift respectively; the *extracted* single-mode dt_crit is clean
and matches. They vanish for k_ext≥1 where the spring pins the drift.)

## CURE control — `-extimplicit` (loaded force implicit, backward-Euler operator split)

| k_ext | 1e-4 | 5e-5 | 2e-5 | **1e-5** | 5e-6 | 2.5e-6 |
|--:|:--:|:--:|:--:|:--:|:--:|:--:|
| 0…50 (every row) | GROW | RING | MONO | **MONO** | MONO | MONO |

Making the loaded force implicit **removes the k_ext dependence of the boundary entirely**: at dt≤2e-5 every
k_ext row is MONO (vs the explicit sweep where k_ext≥5 blew up at 1e-5), and the **whole production dt=1e-5
column is STABLE at every realistic k_ext**. The only residual limit is the still-explicit F8 (k_F8=1 pN/nm)
at the two coarsest dt — dt_crit_F8 ~2.4e-5 (≫ production) and identical across all rows (k_ext-independent).
⇒ **implicit/sub-step of the LOADED force is the actionable cure.**

## SECONDARY — driven power stroke against k_ext=15 pN/nm (confirmation only; NOT a velocity)

Explicit driven run **DIVERGES across the production dt range (2e-5 … 5e-6)** — exactly where the primary
map places k_ext=15 in the unstable regime. With the loaded force **implicit** the run stays **bounded and
settles sub-nm at every dt** ⇒ the loaded operating point sits in the STABLE region once the loaded force is
implicit. (The residual picometre-scale dt-variation of the settled displacement is the KNOWN stroke
per-step-fraction rate confound, `STROKE_DT_RATE_DIAGNOSIS` — a model-definition dt-dependence, NOT a solver
instability. This is why the harness's PRIMARY test drives no stroke.)

## CPU≡GPU parity (default frozen config, k_ext=15, dt=1e-5)

`max |d_n^CPU − d_n^GPU|` over the trajectory = **4.66e-7** (units of displacement) — bit-identical to float
precision. Both runners diverge **identically** (ρ = −5.69872 on both) ⇒ the instability is a genuine
property of the explicit scheme reproduced on both runners, not a runner artifact. The `ExternalSpringSystem`
kernels lower on the PTX backend; disjoint writes ⇒ race-free.

## Verdict (stated plainly)

**Is the explicit EOM integration numerically unstable at production dt=1e-5 under realistic ensemble load?**
**Yes — under a rigid elastic load it is unstable for k_ext ≳ 3.8 pN/nm, and a dense-ensemble-scale rigid
load (15–20 pN/nm) is deep in the growing regime.** **Which coupling sets the limit?** **The LOADED
(external-spring) force** — `dt_crit ∝ γ/(k_ext+k_F8)`, invariant under the motor rotational couplings
(`-rot` identical), F8 (`-nof8` a fixed +1 pN/nm), and the filament chain (`-chain` soft). Making that loaded
force implicit removes the instability at production dt for every k_ext (`-extimplicit`). **The fix is an
implicit/sub-step of the collective LOADED cross-bridge force** — not a rate redefinition, not a
motor-internal implicit. **Caveat:** the rigid pure-elastic load is the conservative worst case; the real
ensemble's effective per-segment stiffness is softer (compliant, mobile, load-shared), so this fixes the
*threshold and mechanism*, and whether a given scene's dt-climb is this instability depends on whether its
effective load crosses ~4 pN/nm.

## Reproduce

```
./run_eomstab.sh                 # default: rigid single seg, frozen motor, clean translational mode
./run_eomstab.sh -rot            # motor body + filament rotation free (rotational couplings)
./run_eomstab.sh -chain 10       # multi-segment filament + F3/F4 (filament internal DOFs)
./run_eomstab.sh -nof8           # F8 cross-bridge off (size F8's stiffness contribution)
./run_eomstab.sh -extimplicit    # implicit loaded force (the cure control)
./run_eomstab.sh -drive          # secondary: driven-stroke settled-displacement convergence
./run_eomstab.sh -gpu            # CPU≡GPU parity on the default frozen config
```
Files: `ExternalSpringSystem.{applyExternalSpring,applyExternalSpringImplicit,zeroTorque}`,
`EomStabilityHarness`, `run_eomstab.sh`. Log: `RUN_LOGS/2026-07-05_eom_stability.txt`. Default byte-identical;
`BoA-v1ref` byte-clean.
