# Low-[ATP] Bound-Brownian-OFF Small-Skew Pilot — DETACHED_SEARCH_ONLY at 5 µM

**Controlling report for this pilot.** Branch `feature/lowatp-bound-brownian-off-pilot`, worktree
`../softbox-lowatp-bound-brownian-off`. Date 2026-07-29.

- **Question.** With filament Brownian motion and *all* bound-motor mechanical Brownian forcing removed — but
  motor search, binding and chemistry left stochastic — does the converter-skew mechanism show a resolved,
  correctly signed small-angle response at 1° and 2°?
- **Parent study (not reopened):** `docs/twirling/LOW_ATP_SMALL_SKEW_PILOT.md` (branch
  `feature/lowatp-small-skew-pilot`), whose result was that 1° and 2° are UNRESOLVED and that the experimental
  pitch scale sits **below** the achiral Brownian roll floor of the assay. Its arms are used here as the
  Brownian-ON comparison set, read in place, read-only.
- **Grand-parent reference (not reopened):** `docs/twirling/LOW_ATP_GLIDING_TWIRLING_FINDINGS.md`.

*(RESULTS SECTIONS ARE FILLED IN AFTER THE CAMPAIGN — see the section markers.)*

---

## 1. Stage 0 — complete Brownian-source audit

Every stochastic contribution that can influence filament translation, filament roll, filament orientation,
bound-motor position or orientation, bond force, converter force/torque, lever force/torque or S2
force/torque. The audit was done by enumerating **every** call site of a random draw in the kernels that the
assay's step actually executes (`ExplicitCompleteMatHarness.stepGlidingCPU` / `buildGlidingGraph`), not by
searching for things named "brownian".

### 1.1 The step, and the four RNG families in it

The assay step is: `convFrame → beamGeom → bind → chemistry → strokeSkew → surfAzim/surfPrune → cock →
place → bond → headRoll → zeroAcc → CSR → segGather → chain → zConfine → brownianForce (→ brChan) →
integrate → orthoY → derive → s2solve → reduce`.

Exactly four RNG families appear anywhere in it:

| family | function | keying |
|---|---|---|
| filament Brownian | `BrownianForceSystem.wangHash` (six 32-bit hashes) | `(segment, step, runSeed)` |
| motor mechanical Brownian | `TwoBodyBeamAnalyticGpu.brownTorqueD` (64-bit mixer) | `(runSeed, step, salt)` |
| head-roll thermostat | `ChiralSiteSystem.gauss` (64-bit mixer) | `(runSeed, step, salt)` |
| chemistry | `NucleotideCycleSystem.wangHash` (32-bit) | `(motor, step, runSeed, salt)` |

Every stream is **counter-based and stateless** — a pure function of its key. This is the property the whole
design rests on (§4).

`MatSoaSlice.matStep7` (salts `0x5F1`–`0x5F5`) and `TwoBodyGpuKernels` (salts `0x4F*` / `0x47*`) also contain
`brownTorqueD` calls but are **not on this assay's path** — they belong to the calibrated-surrogate motor and
the two-body optical-trap harness respectively. Verified by listing the tasks of `buildGlidingGraph`: neither
appears.

### 1.2 The per-source table

| # | source | owning class / kernel | RNG stream / salt | active detached? | active bound? | couples to filament? | controlling flag / buffer | action in this study |
|---|---|---|---|---|---|---|---|---|
| 1 | filament translational Brownian (axial) | `BrownianForceSystem.brownianForce` → `randForce[i]` | filament wang hash, `base=(seg,step,seed)`, `h1..h3` | n/a (per segment) | n/a | **YES — directly drives `coord`** | `brChan[0]` (via `BR_FIL_AXIAL`) | **OFF** |
| 2 | filament translational Brownian (transverse ×2) | same → `randForce[N+i], randForce[2N+i]` | same hashes | n/a | n/a | **YES** | `brChan[1]` (`BR_FIL_TRANS`) | **OFF** |
| 3 | filament **roll** Brownian (body-fixed axial torque) | same → `randTorque[i]` | filament wang hash, `h4..h6` | n/a | n/a | **YES — this is the twirl observable's own noise** | `brChan[2]` (`BR_FIL_ROLL`) | **OFF** |
| 4 | filament bend/tumble Brownian (other rotation ×2) | same → `randTorque[N+i], randTorque[2N+i]` | same hashes | n/a | n/a | **YES** | `brChan[3]` (`BR_FIL_OTHROT`) | **OFF** |
| 5 | per-segment Brownian scale | `FilamentStore.brownTransScale / brownRotScale` | — (deterministic multiplier) | n/a | n/a | gates 1–4 | scene build | unchanged (1.0); suppression is done by the mask, not by rescaling |
| 6 | S2 beam-node Brownian force (3 per node × M nodes) | `TwoBodyBeamAnalyticGpu.matS2SolveStep` RHS | `0x4811 + (m·1009 + j·131 + k)·7919` | YES (the search) | YES | via the bond, once bound | `matc[3]` bit0/bit1 → in-kernel `brownM` | **OFF when bound, ON when detached** |
| 7 | converter (φ) generalized Brownian torque | same, RHS row `iPhi` | `0x4841 + m·7919` | YES | YES | via the bond | `matc[3]` | **OFF when bound** |
| 8 | lever / actin-bond (ψ) generalized Brownian torque | same, RHS row `iPsi` | `0x4842 + m·7919` | YES | YES | via the bond | `matc[3]` | **OFF when bound** |
| 9 | head-roll thermostat (rotation of the head's material reference about the bond axis) | `ChiralSiteSystem.headRollStep` | `0x484F4D47 ("HOMG") + m·7919` | YES | YES | only through the **registry couple**, which is `−k_Ω·Δ` with `k_Ω = REG_K = 0` in this study ⇒ **no coupling** | `chiP[10]` (global) — **and now `matc[3]`** | **OFF when bound** (added here; see §3) |
| 10 | motor-body Brownian (`MotorStore.body`) | — | — | **none: `brownianForce` is called on the filament store only**; the motor body pose is *placed* deterministically by `matPlaceHeadExplicit` | — | — | — | nothing to suppress |
| 11 | bond / attachment-point Brownian | — | — | **none: `CrossBridgeSystem.bondForcesSurface` is deterministic** (no RNG in the class) | — | — | — | nothing to suppress |
| 12 | thermal force added inside the mechanics solver | `matS2SolveStep` | rows 6–8 above | — | — | — | `matc[3]` | covered by 6–8; the Hessian, drag diagonal, F8 reaction, solve and writeback carry **no** further stochastic term |
| 13 | Brownian displacement applied *before* force evaluation | — | — | **none**: `brownianForce` writes force/torque accumulators only; the single position update is `integrate` | — | — | — | n/a |
| 14 | stochastic projection / constraint correction | `DerivedGeometrySystem.orthogonalizeY`, `derive`, `ChainBendingForceSystem`, `CrossBridgeSystem` CSR + `segGather`, `MatSoaSlice.matZConfine` | **no RNG in any of these classes** | — | — | — | — | nothing to suppress |
| 15 | nucleotide cycle (ATP uptake, hydrolysis, Pi release, ADP release with catch-slip) | `NucleotideCycleSystem.cycleLymnTaylor` | 32-bit wang hash, salt `0x4E55` | YES | YES | indirectly (state → stroke → force) | `nucParams` rates | **KEPT STOCHASTIC** |
| 16 | catch-slip release / detachment bookkeeping | same | salt `0x4D54` | — | YES | indirectly | `kinParams` | **KEPT STOCHASTIC** |
| 17 | refractory (rebind cooldown) | same | salt `0x52465241` | YES | — | indirectly | `kinParams[10]` | **KEPT STOCHASTIC** |
| 18 | binding decision | `matBindExplicit` | **no RNG — the gate is deterministic given the pose** | — | — | — | `bindP` | **UNTOUCHED.** The stochasticity of binding is inherited entirely from the detached motor's Brownian search (rows 6–8) and from chemistry (row 15, the ADP·Pi requirement) |
| 19 | site selection / snap | `ChiralSiteSystem.siteSnap`, `siteOccupancyResolve` | no RNG | — | — | — | `chiP` | **UNTOUCHED** |
| 20 | motor-lawn placement | `TwoBodyConverterMotor.buildS2Mat`, `applyS2Lawn` | scene-build stream, `S2_LAWN_SEED` | build-time | build-time | initial condition | scene build | **UNTOUCHED** |

### 1.3 The audit's two load-bearing findings

1. **A detached motor's Brownian search and a bound motor's mechanical Brownian are the SAME code and, before
   this change, the same flag.** Rows 6–8 are one branch in `matS2SolveStep`; row 9 is one branch in
   `headRollStep`. The task's requirement — suppress the bound one, keep the search — is therefore *only*
   expressible as a **binding-state-dependent** gate, never as a global off switch. This is exactly what
   `matc[3]` provides for rows 6–8 (it already existed, from the Vilfan target-zone ablation increment) and
   what was added here for row 9.
2. **Binding is deterministic given the pose.** The "stochastic binding" the task asks to preserve is not a
   coin flip inside the bind kernel — it is the randomness of *where the searching head is* and *what
   nucleotide state it is in*. Preserving it therefore means preserving rows 6–8 for detached motors and rows
   15–17 for everyone, which is what DETACHED_SEARCH_ONLY does.

---

## 2. Stage 1 — the DETACHED_SEARCH_ONLY mode, exactly

`-mech-brownian-mode canonical|detached-search-only` on `ChiralSiteHarness` (alias
`-mechanical-brownian-mode`). Default `canonical`.

```
CANONICAL              every mechanical Brownian channel active in every binding state (the frozen model)

DETACHED_SEARCH_ONLY   filament translational Brownian (axial + transverse) ......... OFF   (rows 1,2)
                       filament rotational Brownian (roll + bend/tumble) ............ OFF   (rows 3,4)
                       BOUND motor  S2 beam-node force ............................. OFF   (row 6)
                       BOUND motor  converter phi torque ........................... OFF   (row 7)
                       BOUND motor  lever / actin-bond psi torque .................. OFF   (row 8)
                       BOUND motor  head-roll thermostat ........................... OFF   (row 9)
                       UNBOUND motor: all four of the above ........................ ON    (the search)
                       nucleotide cycle / release / refractory ..................... STOCHASTIC
                       binding gate, site selection, motor-lawn placement .......... untouched
                       deterministic drag, every elastic law, the Hessian, the F8
                       reaction, the solve and the writeback ...................... untouched
```

A quieted bound motor still relaxes elastically, still strokes, still bears load and still detaches. The
suppression removes RHS forcing terms and nothing else.

**Wiring.** One place: `ChiralSiteHarness.cfg()` calls
`ExplicitCompleteMatHarness.setBrownianPolicy(fb, fb, fb, fb, motUnbound=true, motBound=!dso)` with
`fb = FIL_BROWN && !dso`. That sets `brChan[0..3]` (filament) and `matc[3]` (per-motor, binding-state), which
are the only two buffers the kernels read for this.

**Reporting.** The mode is printed in the run banner, is written into the record provenance line, and is
stored in the record itself as `brownMode` / `filBrownOn` / `motBrownBound` / `motBrownUnbound`.

---

## 3. Stage 2 — bound-state switching rules

The gate is evaluated **per motor, per step**, from `boundSeg` — which already carries this step's bind,
site-snap and chemistry decisions by the time `s2solve` and `headRoll` run:

```java
int brownM = brownOn;
if (bound) { if ((mPolicy & 1) != 0) brownM = 0; }   // suppress when bound
else       { if ((mPolicy & 2) != 0) brownM = 0; }   // (unused here: unbound stays ON)
```

**Detached → bound.** The motor configuration is carried through unchanged. Only the subsequent Brownian
increments stop. No position or orientation is reset. No snap to equilibrium beyond the existing binding
operation. No RNG draw is consumed or skipped in a way that could change chemistry or site selection (§4).

**Bound → detached.** Configuration carried through unchanged, the detached-search Brownian resumes on the
next step, no release kick, nucleotide state untouched.

**One change was required in the code**: `ChiralSiteSystem.headRollStep` previously applied its thermostat
"bound AND unbound … never by a state switch". It now reads the same `matc[3]` mask. `matc[3] == 0` (the
canonical default) leaves it arithmetically identical. *(Note: in this study the head-roll DOF is decoupled
from the filament anyway, because the registry stiffness `REG_K` is 0 — see row 9 — so this is a
completeness change, not a numerically load-bearing one. It is made so that the claim "no bound mechanical
degree of freedom receives stochastic torque" is literally true rather than true-by-consequence.)*

---

## 4. RNG isolation design

**Streams are separated by purpose and are counter-based, so state-dependent suppression is free.**

Every stream is a pure hash of its key — `(slot, step, runSeed, salt)` — with no carried state and no
sequence position. Therefore:

- a draw that is **not taken** cannot shift any other draw, in any stream, ever;
- there is no "consume but ignore" bookkeeping to get right, and none is used;
- chemistry, binding and site-selection ordering are unaffected **by construction**, not by inspection.

The salts are disjoint, and chemistry uses a 32-bit `wangHash` over an `int` key while the mechanical streams
use a 64-bit mixer over a `long` key — two different functions, so aliasing is impossible even at numerically
equal arguments.

The mode is applied as a branch on an RHS accumulation. It writes no state, advances no counter, re-seeds
nothing.

---

## 5–19

*(filled in after the gates and the campaign)*
