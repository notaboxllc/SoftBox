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

## 5. Stage 3 gates — results

`./scripts/run_chiral_sites.sh -bbrown-fixtures -eta 0.01` → **17 PASS, 0 FAIL** (CPU, deterministic).
`./scripts/run_gpu_monitored.sh ./scripts/run_chiral_sites.sh -bbrown-equiv -gpu -eta 0.01` → **PASS**.
Logs: `docs/twirling/data/boundbrown_stage3_gates.txt`, `..._gate_i_gpu.txt`,
`..._regression_fixtures_diff.txt`.

### 5.1 Gate A — detached-search preservation

Fixture: all 1200 motors forbidden to bind (so the bound mask can never fire), 400 steps, **filament
Brownian held ON in both arms** so the comparison isolates the bound mask alone.

| quantity | result |
|---|---|
| max\|Δ S2 beam nodes\| | **0** |
| max\|Δ (φ, ψ)\| | **0** |
| max\|Δ headRef\| , max\|Δ headOmega\| | **0** , **0** |
| nucleotide-state mismatches | **0** |
| bound-motor samples (must be none) | 0 |
| binding-gate probe: candidate segment **and** candidate arc mismatches | **0** of 19 200 evaluations |
| detached head MSD | 2.12×10⁻⁴ µm² at 200 steps → 2.60×10⁻⁴ µm² at 400 steps (RMS **14.6 → 16.1 nm**) |

The search is **bit-identical**, not merely statistically similar, and it is live and still growing. The MSD
is sub-diffusive because the head is tethered by its own 40 nm S2 beam — a bounded search, as intended.

Binding-opportunity and site-selection statistics were probed with `matBindGateOnly`, which *evaluates* the
full 8-gate geometric contract and writes the candidate segment + arc to scratch **without committing** —
so the probe cannot itself perturb the run.

### 5.2 Gates B and D — bound mechanical quietness, and no hidden bound kicks

**Method (the strongest form available, and enumeration-independent).** Warm up 600 steps with binding and
chemistry live so the bound population and its geometry are real, not synthetic. Then freeze: zero every
nucleotide transition rate in place (no state is reset, no pose is touched — the cycle kernel still runs and
still draws, it simply cannot fire), forbid new binds, and optionally thin the bound population. Run the
SAME prepared state twice with **different runSeeds**. Because every stream in the program is a counter-based
hash of a key containing `runSeed`, a seed change re-randomises *all* of them at once. If any stochastic
mechanical term still reached a bound motor or the filament — whether or not I enumerated it in §1 — the two
trajectories would separate.

| fixture | n bound | Δ filament coord | Δ filament uVec | Δ S2 nodes (bound) | Δ bondData (bound) | Σ\|randForce\|+\|randTorque\| |
|---|---|---|---|---|---|---|
| one bound motor | 1 | **0** | **0** | **0** | **0** | **0** |
| all bound motors | 12 | **0** | **0** | **0** | **0** | **0** |
| asymmetric (6 of 12) | 6 | **0** | **0** | **0** | **0** | **0** |
| *control: one bound, Brownian ON* | 1 | 9.24×10⁻³ | 1.77×10⁻² | 6.55×10⁻³ | 4.32×10⁻¹² | 9.10×10⁻⁸ |
| *control: all bound, Brownian ON* | 13 | 5.54×10⁻³ | 2.27×10⁻² | 8.57×10⁻³ | 6.56×10⁻¹² | 9.10×10⁻⁸ |
| *control: asymmetric, Brownian ON* | 7 | 4.89×10⁻³ | 1.41×10⁻² | 8.78×10⁻³ | 6.60×10⁻¹² | 9.10×10⁻⁸ |

**Gate D is satisfied exactly**: the accumulated filament stochastic impulse over the measurement window is
identically zero, and the seed-invariance result extends that to *every* source, audited or not. The
canonical controls diverge by ~10⁻² µm, so the gate has teeth.

### 5.3 Gate C — deterministic relaxation preserved

Same prepared frozen fixture, suppressed mode.

| check | result |
|---|---|
| `randForce` maximum | **0** (exactly) |
| one step reproduced from the integrator's own law `F·dt/γ`, using the buffers it read | max\|actual − predicted\| = **2.34×10⁻⁸ µm**, = 0.20 % of the 1.20×10⁻⁵ µm step, and **below the float32 coordinate floor** (4 ulp = 2.38×10⁻⁷ µm) |
| relaxation, 400 steps | per-step \|Δcoord\| 9.00×10⁻⁶ → 2.53×10⁻⁶ µm (**×0.28**); Σ\|F8\| 1.94×10⁻¹¹ → 1.34×10⁻¹¹ N (**×0.69**) |
| per-bond force closure, max\|head F + segment F\| | **0** (bit-for-bit), against \|F8\| up to 5.24×10⁻¹² N |

Drag is demonstrably active — the motion *is* `F·dt/γ` to the representational floor — and the strained
bound state relaxes toward a fixed point rather than freezing or drifting.

### 5.4 Gate E — binding and detachment continuity

| condition | arm | binds | detaches | \|ΔFil\| at transition | \|ΔFil\| ordinary | ratio | \|ΔPose\| at transition | \|ΔPose\| ordinary |
|---|---|---|---|---|---|---|---|---|
| 5 µM (production) | CANONICAL | 18 | 0 | 1.790×10⁻³ | 1.832×10⁻³ | 0.98 | 1.258×10⁻¹ | 2.717×10⁻¹ |
| 5 µM (production) | **SUPPRESSED** | 17 | 0 | **4.210×10⁻⁴** | 4.260×10⁻⁵ | 9.88 | **5.706×10⁻²** | 1.144×10⁻² |
| 2000 µM (fixture only) | CANONICAL | 18 | 6 | 1.845×10⁻³ | 1.826×10⁻³ | 1.01 | 1.075×10⁻¹ | 2.633×10⁻¹ |
| 2000 µM (fixture only) | **SUPPRESSED** | 17 | 5 | **2.982×10⁻⁴** | 4.193×10⁻⁵ | 7.11 | **6.998×10⁻²** | 1.093×10⁻² |

**Read the absolute columns, not the ratio.** Suppression *reduces* transition-step motion — filament by
4.2× at 5 µM and 6.2× at saturating ATP, motor pose by 2.2× and 1.5× — which is the opposite of a kick. The
transition/ordinary **ratio** rises (0.98 → 9.9) only because suppression collapses the *ordinary-step
denominator* ~40×. What that rise exposes is the model's own **physical force onset at attachment**
(documented for this motor: zero coordinate discontinuity, first step ∝ dt), which was previously buried
under thermal motion. Structurally there is nothing else it could be: the gate multiplies an RHS term,
writes no state, resets no pose and re-seeds nothing.

**Why the second condition exists.** At 5 µM the mean rigor lifetime is `1/atpOn` = 20 ms = 80 000 steps, so
a 1500-step fixture sees *zero* detachments and the detachment half of the gate would be untested. The
saturating-ATP arm rescales **only** the ATP-uptake hazard, is labelled fixture-only, and changes nothing
about the production condition.

### 5.5 Gate F — RNG isolation

- chemistry trajectory **bit-identical** across the mode (gate A, 0 mismatches over 400 steps × 1200 motors);
- binding-gate and site-selection **bit-identical** (0 of 19 200);
- motor-lawn placement and the initial condition **identical** across the mode;
- packed policy: `matc[3]` = 0 (canonical) / 1 (suppressed; bit0 = bound-off);
- salts disjoint, and chemistry uses a 32-bit `wangHash` on an `int` key while the mechanical streams use a
  64-bit mixer on a `long` key — different functions, so aliasing is impossible even at equal arguments;
- no state write, no re-seed, no counter advance, no draw-count dependence.

**Whether draws are "consumed but ignored" while bound: neither.** The draw is simply not taken. With
counter-based streams that is exactly equivalent to consuming and discarding it, and it costs nothing.

### 5.6 Gate G — the Brownian-ON default is untouched

- `matc[3] == 0` in canonical mode ⇒ `matS2SolveStep` and `headRollStep` take the verbatim path;
- all four filament channel masks are 1.0 (an IEEE identity multiply), and the mask task is not even wired;
- **cross-build regression:** `./scripts/run_chiral_sites.sh -fixtures -eta 0.01` is **byte-identical**
  between this build and the parent build (`feature/lowatp-small-skew-pilot`, 81d2909) — 23 gates, including
  every `headRollStep` and registry gate. `diff` returns zero lines
  (`docs/twirling/data/boundbrown_regression_fixtures_diff.txt`);
- record ids: canonical `atp_u0005.00_e0150_d00200000_p_101`, suppressed
  `atp_u0005.00_e0150_bdso_d00200000_p_101` — no aliasing in either direction.

### 5.7 Gate H — zero-skew mechanical null

| check | result |
|---|---|
| `chiP[16]` (converter skew, rad) | **exactly 0.0**; `convSkewOn()` = OFF |
| `epsBind` (actin-side binding skew) | exactly 0 |
| `epsStroke` (interface step skew) | exactly 0 |
| registry stiffness `k_Ω` | exactly 0 ⇒ max\|head registry couple\| = **0** |
| prepared achiral fixture, 3600 bound samples | Σ τ_axial = −9.53×10⁻¹⁹ N·m, Σ\|τ_axial\| = 4.53×10⁻¹⁸ N·m |

No imposed chiral parameter is active and no stochastic mechanical torque source remains. The nonzero net in
the *frozen prepared* fixture is the deterministic achiral residue of one particular frozen geometry with 12
held motors — it is not a chirality and not a noise source. The meaningful null is the production ε = 0 arm
(§9), which is run with the full stochastic search and chemistry.

### 5.8 Gate I — CPU/GPU equivalence, device-resident

Full pilot scene (12-segment chain, discrete native lattice, surface bond, 5 µM, 1200 motors), 300
device-resident steps, **no fallback**.

| ε | bind mism | site mism | nucleotide mism | max\|ΔbondData\| | max\|Δτ_axial\| (tol) | max\|Δ cumulative roll\| | max\|Δ filament coord\| | filament \|rand\| CPU/GPU | bound CPU/GPU |
|---|---|---|---|---|---|---|---|---|---|
| 0° | **0** | **0** | **0** | 1.20×10⁻¹⁶ N | 1.21×10⁻²⁵ (4.76×10⁻²²) | 1.72×10⁻¹¹ rad | 5.96×10⁻⁸ µm | **0 / 0** | 10 / 10 |
| +15° | **0** | **0** | **0** | 1.20×10⁻¹⁶ N | 1.24×10⁻²⁵ (4.78×10⁻²²) | 1.93×10⁻¹¹ rad | 5.96×10⁻⁸ µm | **0 / 0** | 10 / 10 |

Every **decision** channel — binding state, site selection, nucleotide state — is exact on both runners; the
mechanical channels agree to float32 last-bit; the Brownian-mode state itself is confirmed to have crossed to
the device (`matc[3] = 1`, `brChan = [0 0 0 0]`).

---

## 6–19

*(filled in after the campaign)*
