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
- **Answer, in one line.** **No — and the premise is refuted, not merely unconfirmed.** At 1° the trajectory
  is indistinguishable from its own achiral null; at 2° the ±ε deviation is fully common-mode. Meanwhile the
  suppression revealed that **bound-state thermal forcing supplies ≈77 % of the per-head axial torque and
  ~41 % of the gliding speed** — the mechanical noise was not a veil over the chiral torque, it was a
  contributor to it.
- **Classification: C** — bound-motor Brownian motion changes the mean mechanism (§16).
- **What did improve:** the roll-noise floor fell **5.4×**, and the ε = 15° positive control became a
  **2.0× more significant** and **1.7× tighter-closing** measurement (11.79σ vs 5.91σ) — at 2.7× smaller
  amplitude. The suppressed mode is a better *detector* of an already-resolved twirl, but it measures a
  **different mechanism**, not the same one more precisely.
- **Grand-parent reference (not reopened):** `docs/twirling/LOW_ATP_GLIDING_TWIRLING_FINDINGS.md`.
- **Raw records** `RUN_LOGS/chiral_sites/lowatp/*_bdso_*` (this worktree); **derived tables + gate logs**
  `docs/twirling/data/boundbrown_*`.
- **No parameter was tuned after seeing any result.** See §20.

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

## 6. Arm inventory and numerical health

**14 arms, all complete, one attempt each, no retries.** Campaign 2026-07-29 10:53 → 20:04 (**9 h 11 m**),
GPU device-resident throughout (≈340 steps/s, 800 000 steps per arm), no CPU fallback.

| ε | signs | seeds | arms |
|---|---|---|---|
| +1°, −1° | both | 101, 102 | 4 |
| +2°, −2° | both | 101, 102 | 4 |
| +15°, −15° | both | 101, 102 | 4 |
| 0 (null) | — | 101, 102 | 2 |

**Every arm: 0 invalid states, 0 solver failures, 0 rate-cap warnings, 0 rupture events.** Derived tables:
`docs/twirling/data/boundbrown_arms.csv`, `..._analysis.txt`, `..._campaign_driver.txt`. Raw records
`RUN_LOGS/chiral_sites/lowatp/*_bdso_*` (this worktree).

The stored Brownian-ON arms (ε = 0/1/2 in the parent worktree, ε = 15 in the main tree) are read **in place,
read-only**, as the comparison set. No stored record was copied, moved, rewritten or re-run. Every record's
`brownMode` field was checked against its id token — all 32 agree.

---

## 7. Paired observables

γ_filament = 3.241935×10⁻²⁴ N·m·s, derived per record as `τ·qΩ/Ω` and cross-checked, not assumed (§17).

### 7.1 Suppressed (DETACHED_SEARCH_ONLY)

| ε | seed | v_even | Ω_odd | Ω_even | τ_odd | turns/µm | pitch µm | R²(+) | R²(−) |
|---|---|---|---|---|---|---|---|---|---|
| 15° | 101 | −0.0809 | **−16.11** | −3.56 | −4.220e-23 | −31.68 | −0.0316 | 0.759 | 0.484 |
| 15° | 102 | −0.0841 | **−21.87** | +0.62 | −5.453e-23 | −41.36 | −0.0242 | 0.729 | 0.929 |
| 2° | 101 | −0.1043 | +1.09 | −0.52 | −4.076e-24 | +1.66 | +0.601 | 0.001 | 0.035 |
| 2° | 102 | −0.0609 | +4.61 | +11.76 | +1.142e-23 | +12.06 | +0.083 | 0.817 | 0.505 |
| 1° | 101 | −0.0922 | +0.11 | −2.18 | +8.03e-26 | +0.19 | +5.298 | 0.051 | 0.056 |
| 1° | 102 | −0.0783 | +2.30 | −0.38 | +1.136e-23 | +4.67 | +0.214 | 0.197 | 0.117 |

### 7.2 Per-skew summary, both modes

| mode | ε | Ω_odd ± SEM | spread | sign | τ_odd ± SEM | sign | turns/µm | pitch µm | σ vs floor |
|---|---|---|---|---|---|---|---|---|---|
| **suppressed** | 15° | **−18.99 ± 2.88** | 1.36× | same | **−4.836e-23 ± 6.2e-24** | same | −36.52 | −0.0274 | **11.79** |
| **suppressed** | 2° | +2.85 ± 1.76 | 4.23× | same | +3.672e-24 ± 7.7e-24 | **SPLIT** | +6.86 | +0.146 | 1.77 |
| **suppressed** | 1° | +1.20 ± 1.10 | 21.0× | same | +5.720e-24 ± 5.6e-24 | same | +2.43 | +0.411 | 0.75 |
| canonical | 15° | −51.69 ± 5.67 | 1.57× | same | −1.879e-22 ± 1.7e-23 | same | −60.42 | −0.0166 | 5.91 |
| canonical | 2° | +8.91 ± 9.24 | 56× | **SPLIT** | +8.752e-24 ± 1.9e-23 | **SPLIT** | +8.59 | +0.116 | 1.02 |
| canonical | 1° | −13.52 ± 0.62 | 1.10× | same | −2.315e-23 ± 8.8e-24 | same | −12.81 | −0.0780 | 1.55 |

**Gliding is untouched by skew, as required**: v_even = −0.0852, −0.0826, −0.0825 µm/s at 1°, 2°, 15°
(R(2/1) = 0.969). That internal control still holds under suppression.

---

## 8. The noise floor, and why the parent's formula is only an upper bound here

| mode | ε = 0 per-arm Ω | \|Ω\| RMS | σ(spurious Ω_odd) = 0.5√2·RMS |
|---|---|---|---|
| **suppressed** | −2.10, −2.44 | 2.278 | **1.611 rad/s** |
| canonical | −11.51, −13.17 | 12.365 | 8.743 rad/s |

**The roll-noise floor fell 5.4×.** The suppression did exactly what it was built to do.

**But this formula is an upper bound in the suppressed mode, not an estimate.** It assumes the two arms of a
±ε pair **decorrelate** over the window, so their achiral rolls add in quadrature. That is true with thermal
forcing. With mechanical Brownian removed the paired arms share a lawn and RNG streams and stay strongly
**correlated**, so much of the achiral roll *cancels* in the odd combination and the true floor is smaller.
Using it is therefore conservative for claiming a signal, and it is the same estimator as the canonical mode,
so the two σ columns are comparable. §9 is the assumption-free version.

---

## 9. Each arm against its own achiral null — the floor-free test

At a fixed seed, a ±ε arm and the ε = 0 arm share the seed, the lawn and every RNG stream. So the deviation
of each skewed arm from *its own* null is directly interpretable, with no noise model at all.

### 9.1 ε = 1° is indistinguishable from the achiral null

| suppressed, seed 101 | Ω | Δ vs null | τ | Δ vs null | glide | Δ vs null |
|---|---|---|---|---|---|---|
| ε = 0 | −2.103 | — | −4.3669e-23 | — | −0.09217 | — |
| ε = +1° | −2.071 | **+0.033** | −4.3587e-23 | **+8.11e-26** | −0.09215 | **+0.00002** |
| ε = −1° | −2.289 | **−0.186** | −4.3748e-23 | **−7.95e-26** | −0.09219 | **−0.00002** |

At seed 101 the ±1° arms are, to four decimals in glide and 0.2 % in torque, **the same run as the achiral
null**. A 1° converter skew barely perturbs the trajectory. Ω_odd = +0.109 rad/s is the difference between two
runs that are both ≈ the null.

### 9.2 Antisymmetry — the discriminator that needs no floor

A genuine chiral response moves +ε and −ε in **opposite** directions away from the null they share. A
common-mode effect (drift, achiral mechanics, trajectory scatter) moves them the **same** way. With
`A = (Δ⁺ + Δ⁻)/(|Δ⁺| + |Δ⁻|)`: `A ≈ 0` ⇒ antisymmetric ⇒ chiral; `|A| ≈ 1` ⇒ common-mode ⇒ not chiral.

| mode | ε | seed | ΔΩ(+) | ΔΩ(−) | **A_Ω** | Δτ(+) | Δτ(−) | **A_τ** |
|---|---|---|---|---|---|---|---|---|
| **suppressed** | 15° | 101 | −17.563 | +14.652 | **−0.090** | −2.781e-23 | +5.658e-23 | +0.341 |
| **suppressed** | 15° | 102 | −18.809 | +24.928 | **+0.140** | −3.178e-23 | +7.727e-23 | +0.417 |
| **suppressed** | 2° | 101 | +2.676 | +0.496 | **+1.000** | +3.012e-24 | +1.117e-23 | +1.000 |
| **suppressed** | 2° | 102 | +18.814 | +9.587 | **+1.000** | +5.552e-23 | +3.268e-23 | +1.000 |
| **suppressed** | 1° | 101 | +0.033 | −0.186 | −0.699 | +8.11e-26 | −7.95e-26 | **+0.010** |
| **suppressed** | 1° | 102 | +4.365 | −0.234 | +0.898 | +1.100e-23 | −1.173e-23 | **−0.032** |
| canonical | 15° | 101 | −59.692 | +33.092 | −0.287 | −1.738e-22 | +1.365e-22 | −0.120 |
| canonical | 15° | 102 | −65.700 | +71.091 | +0.039 | −2.925e-22 | +1.602e-22 | −0.292 |
| canonical | 2° | 101 | −10.502 | −9.854 | −1.000 | −3.011e-23 | −1.029e-23 | −1.000 |
| canonical | 2° | 102 | +27.999 | −8.298 | +0.543 | +5.074e-23 | −4.096e-24 | +0.851 |
| canonical | 1° | 101 | −22.946 | +5.336 | −0.623 | −5.446e-23 | +9.392e-24 | −0.706 |
| canonical | 1° | 102 | −0.382 | +25.412 | +0.970 | −5.610e-24 | +2.314e-23 | +0.610 |

Three things this settles:

1. **At 15° the rotation is cleanly antisymmetric, and suppression SHARPENED it** — `|A_Ω|` 0.09/0.14
   suppressed vs 0.29/0.04 canonical. This is a genuine chiral response in both modes and a cleaner one under
   suppression.
2. **At 2° the deviation is FULLY common-mode in both channels on both seeds** (`A = +1.000`): both signs of
   skew push Ω and τ the *same* way. Whatever moves the 2° arms off their null is not chirality.
3. **At 1° the TORQUE deviation is almost perfectly antisymmetric** (`A_τ` = +0.010, −0.032) — the correct
   chiral *structure* — but its magnitude differs **135×** between the two seeds (8.1×10⁻²⁶ vs 1.1×10⁻²³), and
   the rotation it predicts is not the rotation observed (§10). So the structure is right and the amplitude is
   not reproducible: the signature of a quantity at the resolution limit, not of a measured mean.

---

## 10. Torque–rotation closure

| mode | ε | meas/pred | per-arm |
|---|---|---|---|
| **suppressed** | 15° | **1.269 ± 0.031** | 1.238, 1.300 |
| **suppressed** | 2° | 0.221 ± 1.088 | −0.867, 1.310 |
| **suppressed** | 1° | 2.534 ± 1.877 | 4.411, 0.656 |
| canonical | 15° | 0.893 ± 0.052 | 0.970, 0.980, 0.865, 0.760 |
| canonical | 2° | 1.126 ± 1.020 | 0.106, 2.146 |
| canonical | 1° | 2.172 ± 0.736 | 1.436, 2.908 |

At 15° closure holds in both modes, and the **suppressed scatter is 1.7× tighter** (±0.031 vs ±0.052) — the
suppressed 15° rotation is the tightest torque-accounted rotation in either pilot. It sits 27 % *above* unity
rather than 11 % below; with no stochastic mechanical torque left, that offset is a deterministic accounting
residual, not roll noise. At 1° and 2° closure is meaningless (it spans −0.87 to +4.41) because both
numerator and denominator are near zero.

---

## 11. Mechanism decomposition — the headline result

| mode | ε | Σ τ⁺ | Σ τ⁻ | net | n(τ⁺) | n(τ⁻) | net/Σ τ⁺ | **\|τ\| per head⁺** |
|---|---|---|---|---|---|---|---|---|
| **suppressed** | 15° | +2.019e-20 | −2.020e-20 | −6.27e-24 | 15.70 | 15.82 | 0.00031 | **1.286e-21** |
| **suppressed** | 2° | +1.869e-20 | −1.869e-20 | +7.58e-25 | 15.40 | 15.34 | 0.00004 | **1.214e-21** |
| **suppressed** | 1° | +2.281e-20 | −2.284e-20 | −2.50e-23 | 16.87 | 15.82 | 0.00110 | **1.352e-21** |
| **suppressed** | 0° | +2.031e-20 | −2.034e-20 | −2.48e-23 | 15.78 | 15.64 | 0.00122 | **1.287e-21** |
| canonical | 15° | +1.041e-19 | −1.043e-19 | −1.413e-22 | 19.02 | 19.09 | 0.00136 | 5.476e-21 |
| canonical | 2° | +9.359e-20 | −9.355e-20 | +3.91e-23 | 17.25 | 17.29 | 0.00042 | 5.426e-21 |
| canonical | 1° | +9.733e-20 | −9.729e-20 | +3.07e-23 | 17.74 | 17.76 | 0.00032 | 5.487e-21 |
| canonical | 0° | +9.601e-20 | −9.597e-20 | +3.76e-23 | 17.24 | 17.23 | 0.00039 | 5.570e-21 |

**The per-head axial torque magnitude collapses 4.3× under suppression** — 1.21–1.35×10⁻²¹ N·m at every skew
including zero, against 5.43–5.57×10⁻²¹ canonical. The two opposing populations shrink 5.2× (Σ\|τ⁺\|
2.0×10⁻²⁰ vs 1.04×10⁻¹⁹).

This **explains the parent pilot's central puzzle.** The parent found per-head torque ≈5.5×10⁻²¹ N·m at every
skew *including zero* and concluded it "is not set by the imposed skew at this scale". Now we know why:
**≈77 % of that per-head torque was bound-state thermal forcing.** The enormous ±10⁻¹⁹ N·m cancelling
populations whose 0.0004 residual the parent had to interpret were largely a thermal artifact of the bound
heads themselves.

**The cancellation residual does not separate the conditions in either mode.** Suppressed: 0.00031 (15°),
0.00004 (2°), 0.00110 (1°), 0.00122 (0°) — the 15° value is now *below* the achiral one, reversing the
canonical ordering. It carries no usable information at n = 2.

### 11.1 Puller / dragger decomposition

| mode | ε | n pull | n drag | τ pull | τ drag | τ/head pull | τ/head drag | same sign |
|---|---|---|---|---|---|---|---|---|
| **suppressed** | 15° | 12.90 | 13.24 | +6.666e-23 | −7.194e-23 | +5.166e-24 | −5.435e-24 | no |
| **suppressed** | 2° | 11.93 | 12.11 | −1.109e-23 | +1.202e-23 | −9.293e-25 | +9.929e-25 | no |
| **suppressed** | 1° | 13.04 | 13.16 | −1.332e-22 | +1.088e-22 | −1.022e-23 | +8.271e-24 | no |
| **suppressed** | 0° | 12.66 | 12.76 | −6.127e-23 | +3.665e-23 | −4.839e-24 | +2.873e-24 | no |
| canonical | 15° | 19.69 | 18.42 | −5.168e-23 | −8.966e-23 | −2.625e-24 | −4.868e-24 | YES |
| canonical | 0° | 17.80 | 16.66 | +1.507e-23 | +2.244e-23 | +8.463e-25 | +1.347e-24 | YES (chance) |

The canonical study's same-sign puller/dragger observation at 15° **does not survive suppression**. Since the
parent already showed the test "passes" at ε = 0 by chance, and it now fails at the one skew that *is*
resolved, this metric carries no information at this sample size. Reported for completeness; no weight
placed on it.

### 11.2 Torque by nucleotide state and stroke phase

| mode | ε | τ NONE | τ ATP | τ ADP·Pi | τ ADP | τ pre | τ post |
|---|---|---|---|---|---|---|---|
| **suppressed** | 15° | −4.655e-22 | 0 | −2.17e-25 | +4.595e-22 | +1.136e-22 | −1.199e-22 |
| **suppressed** | 0° | +9.519e-23 | 0 | −3.380e-23 | −8.623e-23 | +6.591e-23 | −9.074e-23 |
| canonical | 15° | −4.107e-22 | 0 | −2.129e-23 | +2.906e-22 | +6.074e-22 | −7.488e-22 |
| canonical | 0° | +2.734e-22 | 0 | −3.723e-23 | −1.986e-22 | −1.816e-21 | +1.854e-21 |

The pre/post-stroke opposing pair shrinks ~5–6× under suppression (±1.1×10⁻²² vs ±6–7×10⁻²²), consistent
with §11's per-head collapse: most of the pre/post torque amplitude was thermal.

---

## 12. Engagement, occupancy and kinetics

| mode | ε | avgBound | binds/s | detach/s | strokes/s | preLife s | postLife s | residence s | occ NONE | occ ADP·Pi | occ ADP |
|---|---|---|---|---|---|---|---|---|---|---|---|
| **suppressed** | 15° | 31.52 | 1470 | 1453 | 1467 | 9.68e-05 | 1.864e-02 | 2.178e-02 | 0.9144 | 0.0045 | 0.0811 |
| **suppressed** | 2° | 30.74 | 1420 | 1368 | 1413 | 9.16e-05 | 1.862e-02 | 2.245e-02 | 0.9150 | 0.0044 | 0.0807 |
| **suppressed** | 1° | 32.69 | 1512 | 1455 | 1507 | 9.36e-05 | 1.844e-02 | 2.249e-02 | 0.9162 | 0.0044 | 0.0794 |
| **suppressed** | 0° | 31.42 | 1430 | 1377 | 1427 | 9.38e-05 | 1.860e-02 | 2.282e-02 | 0.9244 | 0.0043 | 0.0713 |
| canonical | 15° | 37.40 | 1746 | 1726 | 1743 | 9.93e-05 | 1.856e-02 | 2.165e-02 | 0.9388 | 0.0047 | 0.0565 |
| canonical | 0° | 34.47 | 1633 | 1603 | 1630 | 1.06e-04 | 1.785e-02 | 2.137e-02 | 0.9336 | 0.0049 | 0.0615 |

Averaged over the four skews, suppression lowers **avgBound 11 %** (31.59 → 35.47) and **attachment flux
13 %** (1458 → 1669); at 15° specifically both fall **16 %**. Residence changes **within 2 %**, and ADP
occupancy *rises* ≈30 %. Attachment is reach-limited in this model, so removing the search-independent thermal
agitation of *bound* heads reduces engagement modestly rather than dramatically — which is why the 1.7×
gliding loss cannot be attributed to occupancy.

---

## 13. Nested-window stability

| mode | ε | 50 ms | 100 ms | 150 ms | 200 ms | sign flips | roll R² |
|---|---|---|---|---|---|---|---|
| **suppressed** | 15° | −5.24 | +0.92 | −15.29 | **−19.18** | 2 | 0.375 → 0.730 |
| **suppressed** | 2° | −5.18 | +6.02 | +4.71 | +2.73 | 1 | 0.253 → 0.330 |
| **suppressed** | 1° | +0.06 | −1.31 | −1.03 | +1.32 | 2 | 0.078 → 0.106 |
| canonical | 15° | −36.60 | −62.08 | — | −51.40 | 0 | 0.554 → 0.821 |
| canonical | 2° | +28.66 | +10.32 | +10.55 | +8.57 | 0 | 0.448 → 0.670 |
| canonical | 1° | −1.45 | +7.06 | −10.50 | −13.61 | 2 | 0.114 → 0.460 |

**A load-bearing caveat on the 15° suppressed mean.** Its magnitude is still *growing* at the end of the
window (−5.2 → −19.2, last/first = 3.7) with R² rising 0.375 → 0.730. The suppressed 15° signal accumulates
coherently and is **not converged at 200 ms**; −18.99 rad/s is a lower bound on its magnitude. The canonical
15° arm is stable across windows (0 sign flips, last/first = 0.96). This matters for §14: the Ω-based
reduction factor may shrink with longer runs, so the class-C verdict is rested on the *per-head* and
*torque* quantities, which are window-length-insensitive means over the measurement window, not on the slope
ratio.

At 1° and 2° the nested estimates flip sign within the same trajectory and R² stays ≤0.33 — not converged in
any sense.

---

## 14. Brownian-ON versus Brownian-suppressed

| ε | quantity | Brownian ON | suppressed | ratio |
|---|---|---|---|---|
| 15° | v_even | −0.1406 | −0.0825 | **0.587** |
| 15° | Ω_odd | −51.69 | −18.99 | **0.367** |
| 15° | Ω_odd SEM | 5.666 | 2.881 | 0.508 |
| 15° | τ_odd | −1.879e-22 | −4.836e-23 | **0.257** |
| 15° | turns/µm | −60.42 | −36.52 | 0.604 |
| 15° | **σ vs floor** | 5.91 | **11.79** | **1.99** |
| 15° | closure | 0.893 | 1.269 | 1.42 |
| 15° | Σ τ⁺ | 1.041e-19 | 2.019e-20 | **0.194** |
| 15° | avgBound | 37.40 | 31.52 | 0.843 |
| 15° | residence s | 0.02165 | 0.02178 | 1.006 |
| 15° | attach/s | 1746 | 1470 | 0.842 |
| 15° | cancel residual | 0.001357 | 0.000310 | 0.229 |
| 2° | Ω_odd | +8.91 | +2.85 | 0.320 |
| 2° | Ω_odd SEM | 9.236 | 1.762 | **0.191** |
| 1° | Ω_odd | −13.52 | +1.20 | **−0.089** |
| 1° | σ vs floor | 1.546 | 0.748 | 0.484 |

**At 15° — the resolved condition — this is interpretation C, not A.**

- Same mean torque, lower variance (**A**) is **refuted**: τ_odd falls **3.9×**, Σ\|τ⁺\| falls **5.2×**,
  per-head torque falls **4.3×**.
- Changed mean torque and cancellation structure (**B/C**) is **confirmed**.
- Changed gliding and occupancy (**C**) is **confirmed**: gliding **1.7× slower**, avgBound and attachment
  flux each **−16 %**.

**Bound-state mechanical Brownian forcing is not merely obscuring the chiral signal — it participates in
generating it, and in generating the translation.** Gliding falls 1.7× while engagement falls only 16 %, so
the lost speed is not mainly an occupancy effect: thermal agitation of *bound* heads was contributing
directly to propulsion.

**And yet the suppressed mode is the better detector at 15°**: significance rises 5.91σ → **11.79σ** (2.0×)
because the floor fell 5.4× while the signal fell only 2.7×, and closure scatter tightened 1.7×.

**For 1° and 2°, per the task, unresolved point estimates are not compared as means.** Comparing only what is
comparable:

| criterion | canonical | suppressed |
|---|---|---|
| sign consistency across seeds, 1° | same | same |
| sign consistency across seeds, 2° | **SPLIT** | **same** |
| Ω_odd SEM, 2° | 9.24 | **1.76** (5.2× tighter) |
| sign vs the 15° positive control | 1° agrees, 2° disagrees | **both disagree** |
| torque closure, 1°/2° | 2.17 / 1.13, scatter ±0.74/±1.02 | 2.53 / 0.22, scatter ±1.88/±1.09 |
| nested-window sign flips, 1°/2° | 2 / 0 | 2 / 1 |
| cancellation structure vs ε = 0 | indistinguishable | indistinguishable |
| antisymmetry `A_Ω` | 0.62–0.97 | 0.70–1.00 (2° = 1.000) |

Suppression **improved sign consistency and cut variance 5×** at 2°, and left both skews **unresolved** and
now **wrongly signed relative to their own positive control**.

---

## 15. Small-angle response

| quantity | ε = 1° | ε = 2° | ε = 15° | R(2/1) | R(15/1) |
|---|---|---|---|---|---|
| Ω_odd (rad/s) | +1.205 | +2.852 | **−18.99** | 2.368 | **−15.76** |
| τ_odd (N·m) | +5.720e-24 | +3.672e-24 | **−4.836e-23** | 0.642 | **−8.45** |
| turns per µm | +2.431 | +6.863 | −36.52 | 2.823 | −15.02 |
| v_even (µm/s) | −0.0852 | −0.0826 | −0.0825 | 0.969 | 0.968 |
| σ vs floor | 0.748 | 1.770 | 11.79 | 2.368 | 15.76 |

Testing the task's five conditions:

| condition | verdict |
|---|---|
| τ_odd(0°) = 0 | n/a — ε = 0 has no ± pair; its per-arm τ is the achiral reference, and §9.1 shows the ±1° arms sit within 0.2 % of it |
| τ_odd(1°) correctly signed | **NO** — +5.72e-24, opposite to the −4.84e-23 control |
| τ_odd(2°) correctly signed | **NO** — +3.67e-24, opposite sign, and SPLIT across seeds |
| \|τ_odd(2°)\| > \|τ_odd(1°)\| | **NO** — 3.67e-24 < 5.72e-24 (R(2/1) = 0.64, and Ω gives 2.37) |
| Ω_odd consistent with τ_odd/γ_roll | **NO at 1°/2°** (closure 2.53, 0.22); **YES at 15°** (1.269 ± 0.031) |

**R(15/1) is negative in both channels** — the ratio changes sign, which is not a scaling exponent but the
signature of an unresolved quantity, exactly as in the parent pilot.

⇒ **Regime 4: STILL UNRESOLVED.** Not regime 1 (linear), not 2 (sublinear monotone), not 3 (threshold) — a
threshold verdict would require the small-skew values to be resolvably *zero* while 15° is finite; instead
they are unresolved and wrongly signed. No curve is fitted, per the task's instruction.

**On the t-based CI.** At n = 2, t₀.₉₅ = 12.7, so the 95 % CI excludes zero for *no* condition — not even the
15° arm that sits 11.8σ above the floor with matched-sign seeds and 1.269 ± 0.031 closure. The t-CI at n = 2
has essentially no power and is **not** the discriminating test here; it is reported for completeness only.
The resolving evidence for 15° is the floor ratio, the matched-sign seeds, the closure, and the antisymmetry.

**Comparison with experiment (post-hoc only).** Experimental pitch 0.47 ± 0.20 µm. Suppressed: 15° gives
−0.027 µm (resolved, 17× too short); 1° gives +0.41 µm and 2° +0.15 µm — 1° is numerically closest to the
experimental value, **but it is unresolved and wrongly signed, so the proximity is meaningless** and is not
offered as agreement.

---

## 16. Classification

### **C — BOUND-MOTOR BROWNIAN MOTION CHANGES THE MEAN MECHANISM**

Basis: at 15°, the one resolved condition, τ_odd falls **3.9×**, Σ\|τ⁺\| falls **5.2×**, per-head axial
torque falls **4.3×** (at every skew including zero), gliding falls **1.7×**, and avgBound and attachment
flux each fall **16 %**. Bound-state thermal forcing supplies ≈77 % of the per-head axial torque and a
substantial part of the propulsion. This is a mechanism change, not an observation-noise manipulation.

**Why C and not D.** D's second and third clauses hold — noise *was* reduced (floor 5.4×, 2° SEM 5.2×
tighter, 2° sign consistency recovered) and 1°/2° *do* remain unresolved. But D's first clause requires
"15° mean preserved", and it is not. C is therefore the correct single label, with D's noise-reduction and
small-skew-unresolved findings recorded inside it.

Not A or B: no small-skew response is resolved, and both central values are wrongly signed relative to their
own positive control. Not E: all 18 gates pass, CPU/GPU decision channels are exact, and every arm reports
0 invalid / 0 solver / 0 rate-cap / 0 rupture.

**Answer to the scientific question posed.** No. Removing filament and bound-motor mechanical Brownian
forcing does **not** reveal a resolved, correctly signed small-angle response at 1° or 2°. The study's premise
— that an intrinsic small-skew mean was being *hidden* by mechanical noise — is **refuted rather than
confirmed**: the noise was not a veil over the chiral torque, it was a contributor to it.

Distinguishing the four possibilities the task set out:

1. *An intrinsic small-skew mean hidden by mechanical Brownian noise* — **not found.** At 1° the arms are
   indistinguishable from their own achiral null (§9.1).
2. *A genuinely nonlinear or threshold-like skew response* — **not established.** R(2/1) = 2.37 in Ω but 0.64
   in τ, and both are unresolved; the 2° deviation is fully common-mode (`A = +1.000`).
3. *Residual stochasticity from search, binding and chemistry* — **this is what remains, and it is
   sufficient to explain the small-skew scatter.** Roll R² at 1° is 0.05–0.20 with all mechanical Brownian
   off, so the surviving stochastic channels alone still produce roll wander comparable to the 1° signal.
4. *Accidental residual mechanical noise reaching the filament* — **excluded.** Gate D: filament stochastic
   impulse identically zero; gate B: the filament and every bound motor are *exactly* seed-invariant, a test
   independent of the source enumeration.

---

## 17. Numerical and regression health

- **14/14 arms**: 0 invalid states, 0 solver failures, 0 rate-cap warnings, 0 rupture events, 0 retries.
- **Stage 3**: gates A–H 17 PASS / 0 FAIL; gate I PASS at ε = 0 and +15, device-resident, no fallback, with
  every decision channel (binding, site, nucleotide) exact CPU↔GPU.
- **Brownian-ON regression**: `-fixtures` output **byte-identical** to the parent build across 23 gates.
- **Record integrity**: all 32 records' stored `brownMode` agrees with the mode token in the id; no id
  collides across modes or skews.
- **Two analysis defects found and fixed during this pilot, both before they reached a conclusion**, both
  mine:
  1. An MSD probe took its baseline before `outGeom` was first written, reporting the absolute head
     coordinate (914 nm) instead of a displacement (true value 14.6 nm RMS).
  2. The closure γ was inherited from the parent as `min(τ·qΩ/Ω) × NSEG`. That ratio **is** γ, and which γ
     depends on record vintage — post-fix records carry γ_filament, older ones γ_filament/NSEG. `min()×NSEG`
     only works when an old-vintage record happens to be present to be the minimum (3 of 32 here). On this
     pilot's all-new-vintage set it would have inflated γ **12×** and silently corrupted every closure number,
     since a 12× closure still reads as plausible "rotation the torque cannot explain". Replaced with an
     explicit cluster + vintage report. γ_filament = 3.241935×10⁻²⁴ N·m·s, and the parent's published 15°
     closure still reproduces at 0.893 ± 0.052, so **no previously published number moves.**

---

## 18. Motor search and chemistry remained stochastic — explicit statement

**Throughout every one of the 14 arms:**

- **Random motor-lawn placement** — untouched (gate F: initial condition bit-identical across modes).
- **Motor search while detached** — the established binding-search mechanism, retained in full and proven
  **bit-identical** to the canonical path (gate A: 0 difference in S2 nodes, φ/ψ, headRef, headOmega over 400
  steps × 1200 motors), and live (head MSD 14.6 → 16.1 nm RMS, still growing).
- **Stochastic site selection** — untouched; candidate segment *and* arc bit-equal over 19 200 gate
  evaluations (gate A).
- **Stochastic binding events** — the binding gate is deterministic *given the pose*, so binding
  stochasticity is inherited from the search and from the nucleotide state; both preserved. No deterministic
  nearest-site capture was substituted.
- **Nucleotide-cycle stochasticity, stochastic detachment, the refractory draw** — untouched; the chemistry
  trajectory is **bit-identical** across the Brownian mode (gate A/F, 0 mismatches).
- **All counter-based RNG streams** required for the above — intact, and structurally isolated (§4).
- **No filament velocity or rotation was prescribed** anywhere.

What was removed is exactly and only: direct filament Brownian force and torque, and the S2-node, converter-φ,
lever-ψ and head-roll Brownian increments **of bound motors**.

---

## 19. Recommendation for the next study — not executed

**Do not brute-force 1–2°, and do not run an intermediate skew ladder yet.** The parent pilot's reason
(the target sits below the noise floor) has been superseded by a better one: at 1° the trajectory is
*indistinguishable from its own achiral null*, and the 2° deviation is *fully common-mode*. More arms would
sharpen an estimate of a quantity whose chiral structure is absent at 2° and unreproducible at 1°.

**Primary recommendation — resolve which bound thermal channel supplies the torque.** The new finding is
that ≈77 % of the per-head axial torque and ~40 % of the gliding speed are contributed by bound-state
thermal forcing. The audit (§1.2) shows that comes from four separable channels — S2 beam-node force (row 6),
converter φ (row 7), lever ψ (row 8), head-roll thermostat (row 9) — which are currently suppressed together
by a single bit. Widening `matc[3]` from 2 bits to 5 is a **data-only** change of exactly the kind already
validated here (no kernel restructuring, no buffer resize, no TaskGraph change), and a 4-arm ablation at 15°
would identify the channel. That directly targets the mechanism this pilot uncovered, and it is cheap.

**Prerequisite — extend the 15° suppressed duration before quoting its mean.** §13 shows the suppressed 15°
Ω_odd is still growing at 200 ms (last/first = 3.7, R² rising 0.375 → 0.730). Two arms at 400–600 ms would
establish whether the 2.7× Ω reduction is the converged value or partly a window-length artifact. The
class-C verdict does **not** depend on this (it rests on the window-insensitive per-head and τ_odd means),
but any quoted Ω ratio should.

**Also worth recording as available, not recommended now:** the suppressed mode is a **2.0× better detector**
at large skew (11.79σ vs 5.91σ) with 1.7× tighter closure. If a future study needs a high-precision chiral
measurement at a skew that is *already resolved*, DETACHED_SEARCH_ONLY is the better instrument — provided
its class-C caveat travels with it: **it measures a different mechanism, not the same mechanism more
precisely.**

**Standing phrasing, both halves obligatory.** *"With filament and bound-motor mechanical Brownian forcing
removed, the converter-skew twirl at 15° remains resolved, correctly signed and antisymmetric — more
significant than canonical, at 2.7× smaller amplitude — while 1° and 2° remain unresolved and wrongly
signed. Bound-state thermal forcing is not a veil over the chiral torque; it supplies about three quarters
of the per-head axial torque and a substantial part of the gliding speed."*

---

## 20. Scope and honesty statements

- **No parameter was tuned after seeing any result.** ATP, density, viscosity, duration, timestep, S2 length,
  motor mechanics, Brownian amplitudes, detachment, geometry and analysis windows are exactly the frozen 5 µM
  values. The only changes are `-mech-brownian-mode detached-search-only` and `-atp-eps-deg`, whose four
  values (0, 1, 2, 15) were fixed before the first run.
- **No skew was fitted to experiment**, and no interpolation to a "best-fit" skew is offered. The fact that
  the unresolved 1° pitch happens to lie near the experimental value is reported as a coincidence and
  explicitly not as agreement.
- **No gate was relaxed to pass.** Two gate criteria were *corrected* (gate E's ratio test, which was not
  mode-comparable, and gate 8/10's tolerances and indices), each time making the gate measure the intended
  thing; the corrected gates then passed on their merits, and the corrections are recorded in §5.4 and §17.
- **The parent and grand-parent studies were not reopened.** Their conclusions are used as the comparison
  point. The parent's per-head-torque puzzle is now *explained* by this pilot (§11), which extends rather
  than contradicts it. No stored record was modified.
- **The default is unchanged.** `MECH_BROWN_MODE = CANONICAL`; the canonical path is byte-identical to the
  parent build; `BoA-v1ref` untouched.
- **Stopping boundary respected**: Brownian-source audit, validation gates, 14 pilot arms, nested-window
  analysis, Brownian-on comparison, classification, recommendation. No seed-count increase, no run
  lengthening, no ATP/density/viscosity/filament-length change, no rigor-rupture change, no broad skew sweep,
  and the recommended follow-ups were **not** executed.
