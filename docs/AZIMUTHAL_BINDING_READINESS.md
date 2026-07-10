# AZIMUTHAL_BINDING_READINESS — code-read for site-limited, azimuthally-aware myosin binding

**Read-only survey (2026-07-09).** No edits, no new source files, no runs. Classifies what v2 already
carries so the planner can scope the build. Target design (context only, NOT built here): each filament
carries a pointed-end helix phase φ₀; local site azimuth `φ(s) = φ₀ + (2π/P)·s` at axial distance s from the
pointed end (actin 13/6: subunit azimuth advance −167.14°, axial rise 2.75 nm, ~36 nm recurrence). Binding
gets two independent gates — (1) an **azimuthal-acceptance** gate (head binds only where local site azimuth is
within its approach window, ~30–60° half-window; the ~36 nm recurrence *emerges*), and (2) a short-range
**steric-exclusion** gate (no two bound heads within a few nm axially, azimuth-independent). Filament roll is a
free DOF so net binding torque can produce *emergent* twirling.

---

## SCOPE VERDICT (headline)

| Piece | Classification | Rough size |
|---|---|---|
| **Filament roll DOF** | **FREE** — present, torque-integrated, thermally excited, unfrozen | **already there** (per-segment); *coherent per-filament φ₀-carrier* is a modelling add |
| **Azimuthal-acceptance gate** | hook point exists, azimuth computable-but-discarded; `filYVec` not threaded into binding | **medium** (thread the frame in; replicate across 5 predicate copies) |
| **Steric-exclusion gate** | CSR-inverse bound-head gather exists; per-head axial index not yet exposed; needs propose→admit ordering | **medium** (reuse CSR template; add axial-gap kernel + two-phase admit) |
| **φ₀ bookkeeping** | no per-filament store, no axial-distance array | **small–medium** (one per-segment `FloatArray`; growth/split/depoly clean; **severing hard**) |
| **Turnover φ₀ update** | growth/split no-op, depoly single-scalar, **severing needs a chain walk** | **small except severing** |

**Bottom line: this is NOT a DOF-add and NOT a DOF-unlock — the filament axial-roll DOF is already FREE and
faithfully integrated (v1-parity).** The work is a **gate-injection + a φ₀ bookkeeping layer**: thread the
filament body frame into the (duplicated) bind predicate, add one per-segment phase scalar propagated by the
turnover kernels, and add a steric axial-gap gate over the existing bound-head CSR-inverse. The one genuinely
hard sub-problem is maintaining φ₀ continuity across **cofilin severing** (a new pointed end appears mid-chain).

---

## PART 1 — the filament axial-roll DOF: **FREE**

### Representation — a full body frame per rigid rod (roll IS represented)
Each rigid-rod body (`RigidRodBody`, aliased by `FilamentStore`) carries an orthonormal frame, not just an
axis: `uVec` (long axis) + `yVec` (reference perpendicular) + derived `zVec = uVec × yVec`
(`RigidRodBody.java:32-38`, `FilamentStore.java:40-47`). **Roll about the long axis is the azimuth of `yVec`
about `uVec`** — a real, tracked coordinate, not a degenerate/absent one. `DerivedGeometrySystem.derive`
re-orthogonalizes `yVec' = zVec × uVec` each step (`DerivedGeometrySystem.java:57-61`), which *cleans Euler
drift while preserving the roll azimuth* (it does not reset roll to a canonical value).

### Roll is torque-integrated (a genuine DOF, not carried inert-by-fiat)
In `RigidRodLangevinIntegrationSystem.integrate` the body frame is `[u; y; z]`, so the body-x angular velocity
is rotation **about the long axis** = the roll rate:
- `float bwx = btx / bRotGam.get(i);` (`:79`) — roll rate = (torque·u)/roll-drag.
- `yVec` update `yTransInZ = bwx * dt; nyZ = … + zz*yTransInZ` (`:103-110`) rotates `yVec` toward `zVec` by
  `bwx·dt` — **this is the roll rotation**. (`uVec` is correctly untouched by `bwx` — rolling about u doesn't
  move u.)

So any torque with a component along `u` drives roll; the coordinate advances and persists across steps.

### Nothing freezes it — the chain applies only ⊥-axis torques
The chain "torsion" (F4) is **not** an axial-roll spring. In `ChainBendingForceSystem.chainForces` the torsion
torque is `tv = uVec × neighbourUVec` (`:218`, `:304`), a cross product that is **⊥ uVec** ⇒ its projection
onto `u` (which is what drives `bwx`) is **zero**. The F3 link-force torque is `R × F` with `R ∝ uVec`
(`:211-213`, `:296-298`), also ⊥ u. **The chain therefore couples neither bending-alignment torque nor any
torque into the roll DOF — neighbour rolls are torsionally decoupled.** There is no torsional-rigidity lock,
no axial lock, no constraint on `yVec`'s azimuth.

### Roll is thermally excited (small drag, matched Brownian kick)
Axial roll drag `bRGx = 4πη·R²·L` (`DragTensorSystem.rodDragSI:41`) is small (∝ R²) but **nonzero**, and the
Brownian system injects a matching FDT roll torque `randTorque_x = rScale·√(2kT/dt)·√(bRotGam_x)·g`
(`BrownianForceSystem.java:102`). Because `bRGx` is tiny, `D_roll = kT/bRGx` is large ⇒ **roll diffuses fast**
(free, hot). This is the mechanism that would let net binding torque twirl the filament — and the caveat that
any azimuthal coupling torque competes against fast thermal roll randomization.

### v1ref comparison — v2 did NOT drop or freeze it (byte-faithful port)
`BoA-v1ref/boxOfActin/FilSegment.moveThing` integrates the identical roll term: `bAngVeloc.div(bTorqueSum,
bRotGam)` then `yVecTransInZ = bAngVeloc.x * dt` (FilSegment.java:662, 678) — the exact analog of v2's
`bwx`/`yTransInZ`. v1's `bRotGam.x = 4πη·R²·L` (FilSegment.java:426) matches v2. v1's torsion is likewise
`torsionVec.cross(uVec, neighbour.uVec)` (FilSegment.java:1816), ⊥ u — v1 also has **no axial torsional
coupling**. So the v2 rewrite reproduced v1's roll DOF faithfully; there is nothing to restore.

### Do NOT conflate with the motor head axial-swing-lock
The `-axlock`/directed-swing machinery (memory: axial-lock necessary-not-sufficient; stroke-effective-lever)
locks the **myosin head** swing — a *different* `RigidRodBody` (the `MotorStore` head sub-body), a distinct
entity. It has no bearing on the **filament segment** roll surveyed here.

### The three load-bearing caveats (why FREE ≠ "just add a gate for free")
1. **Currently physically inert / unobservable.** No force law reads `yVec`'s azimuth about `u`, so the
   coordinate free-diffuses with zero consequence today. Azimuthal binding would be the *first consumer* that
   makes filament roll observable — that's the point, but it means there is no existing torsional stiffness to
   hold a phase against the fast thermal roll.
2. **Per-segment and torsionally decoupled.** Roll lives on each segment's own `yVec`; neighbours don't share
   it (no F4 roll coupling, above). A *coherent per-filament* helix phase φ₀ is therefore **not a single
   existing coordinate**. The design's "one φ₀ per filament + analytic φ(s)" sidesteps this, but forces a
   decision (see PART 3 / verdict): where does φ₀ live and what body does the summed axial binding torque
   twirl — a representative segment's `yVec`, a new per-filament roll coordinate, or do you add torsional-roll
   coupling so per-segment rolls cohere?
3. **Fast thermal roll.** `bRGx ∝ R²` is tiny ⇒ roll randomizes quickly; the azimuthal-acceptance coupling
   must be stiff enough to compete (a physics-tuning note, not a scope blocker).

---

## PART 2 — binding-model geometry: where the two gates hook in

Standard gliding/dense params: `myoColTol = 0.006 µm` (6 nm capture radius), `alignTol = −0.4`
(`MotorStore.setKinParams:256-266`, consumed as `kinParams[7]/[8]`). (FullSystemDemo's "faithful" 0.025 µm
reach is the exception, not the standard.)

### How a head selects + attaches (Q1) — one predicate, nearest-by-⊥-distance
`reachTestDistSq` (`BindingDetectionSystem.java:59-85`, port of v1 `MyoMotor.checkFilSegCollision`) is the sole
acceptance test; the binder picks the reachable segment with the smallest perpendicular drop. Four gates:
- **(a)** `alpha ∈ [0,1]` — head must project onto the segment (`:67-68`).
- **(b)** `conDistSq < myoColTol²` — capture radius, 6 nm (`:78`); `conDist` = ⊥ drop of the head tip onto the
  segment **centerline** (`cp = e1 + alpha·(e2−e1)`, `:74-76`).
- **(c)** `motDotFil ≥ alignTol` — head-axis vs filament-axis alignment (`:80-81`); permissive (~114°).
- **(d)** `rodDotFil ≥ 0` — the **polarity** gate (rod must not lean backward along the filament, `:82-83`);
  this is the gate the §6c convention swap flipped to kill self-grab. It is a *filament-polarity* test, NOT an
  azimuthal-approach test.

`bindNearest` (`:356-396`) keeps the min-`d` segment and records `bindArc = numer/√denom` (arc length from
end1, µm, `:391`). Bind is unconditional-on-contact by default; `kOn` gating exists only on the canonical
two-point (`:477-484`) and rate paths.

### Azimuthally FREE (Q2); continuous, not discretized (Q3)
No predicate term references the head's azimuth around the filament cylinder. The only directional gates are
the two dot-products, both against the filament **axis** — a head 6 nm below vs 6 nm above the axis (same
axial station, opposite azimuth) are treated identically. Attachment is **continuous**: `bindArc` is a
real-valued arc anywhere on the segment (`alpha ∈ [0,1]`); the segment is the index granularity (`boundSeg`),
the position within it is continuous. No monomer/site discretization.

### Where the head attaches + what's stored (Q4) — centerline, azimuth discarded
The F8 attach point is reconstructed purely from segment center + axis + arc, with **no radial/`yVec`/`zVec`
offset** — it lands on the **centerline** (`CrossBridgeSystem.bondForces:114-115`; same on
`bondForcesCanonical:495-496`, `bondForcesCanonicalConfig1:769-770`). Stored bond state per motor: `boundSeg`
(`MotorStore.java:74`), `bindArc` (axial, `:75`), `bindArc2` (rear-site arc, still axial, `:80,:206`).
**No field stores an azimuthal bond location.** Crucially, the ⊥-offset direction `(cp − head)` is *formed
inside the predicate* (`:74-76`) and **thrown away** — only its magnitude survives. Capturing its direction
relative to `filYVec` would give the bond azimuth with no new geometry.

### The two named hook points (Q5)
**(a) Azimuthal-acceptance** — slot it at the end of `reachTestDistSq`, right after the polarity gate and
before `return conDistSq` (`BindingDetectionSystem.java:82-84`): compute the ⊥-offset direction (already in
hand as `dx,dy,dz`, `:75`), express its azimuth relative to the segment `yVec`, and reject / fold an acceptance
weight. For a *probabilistic* accept, the natural multiply site is the bind commit (`bindNearest:394`, or
`totalRate`/`chordSum` in `bindRate:708`).
> **Flag — predicate is duplicated in 5 places** and cannot simply be routed through the helper: `reachTestDistSq`
> (helper), inlined in `gridReachable` (`:194-219`), `gridReachableWide` (`:614-636`), and the two node-aware
> brute loops (`bruteReachableNodeAware:791-795`). The inlining is deliberate — a TornadoVM PTX
> "invalid variable" lowering bug on helper-calls inside the deep grid nest (`:194-196`). **Any gate must be
> replicated across all copies.**
> **Flag — `filYVec` is not threaded into binding.** The segment `yVec`/`zVec` (the azimuthal frame) exist in
> the stores and are used by `CrossBridgeSystem.bondForces:110-111`, but are NOT passed to any bind kernel; the
> predicate takes only end1/end2. **A signature change adding `filYVec` (derive `zVec = u×y` in-kernel) is
> required.**

**(b) Steric-exclusion** — same commit site (`bindNearest:386-394`) but it needs data the per-motor binder
lacks: the axial positions (`bindArc`) of heads *already bound to* `bestSeg`. After `bestSeg` is chosen
(`:393`), before commit (`:394`), scan the bound-head bucket for `bestSeg` and reject if any bound head's
`|bindArc − bestArc| < X`. This is impossible inside the current parallel-over-motors binder without the
cross-motor gather below.

### Steric infrastructure (Q6) — the gather exists; axial index needs exposing
A **segment→bound-motors CSR-inverse is already built every step** (keyed by `boundSeg`):
`csrHistogram/csrScan/csrScatter` → `segMotorOffsets` + `segMotorMyo[]` (`CrossBridgeSystem.java:1182-1291`),
consumed by `segGather` (`:1295-1313`). `segMotorOffsets`+`segMotorMyo` are exactly "the bound heads on segment
s" — the structure a steric gate needs. It does **not** currently expose each head's axial station; a
`segGather`-shaped kernel reading `bindArc[segMotorMyo[k]]` would compute per-segment min-axial-gap (cheap
add). The CSR-inverse is general, race-free, atomics-free, CPU≡GPU-bit-identical (crosslinkers/nodes already
reuse it).
> **Flag — timing/ordering.** The CSR is built from `boundSeg` *after* binding commits (it feeds the force
> gather). A steric gate rejecting *before* commit must use either the **previous step's** bound set
> (`boundSeg` persists — cheapest) or a **two-phase propose→CSR→admit** mirroring the crosslinker one-per-segment
> admission (`CrosslinkerSystem.formAdmit`, inc 5c-ii) — a proven race-free template.

---

## PART 3 — φ₀ bookkeeping + turnover feasibility

### No axial position is stored (Q1)
Only `monomerCount` + `segLength` per segment (`FilamentStore.java:50-51`); **no cumulative arc-length /
distance-to-pointed-end array**. Distance-from-pointed-end must be **computed by walking the chain** (summing
`segLength`/`monomerCount` from the pointed tip). Chain ordering: `end2NbrSlot` → barbed/node; `end1NbrSlot` →
pointed/outward. Pointed tip = free `end1` (`DepolySystem.java:22-25,82`). **Barbed = end2** (post-2026-06-19
swap; `GrowthSystem.java:16-22`, `NodeNucleationSystem.java:78-79`); `uVec` points INWARD (pointed→barbed);
`end1 = coord − ½·segLength·uVec` (`FilamentStore.java:46-47`).
> **Flag — intra-segment resolution.** A segment holds up to `2·stdSegLength = 64` monomers (`GrowthSystem:113`),
> spanning several helical repeats. Evaluating φ(s) at a site *within* a segment needs contour-to-segment
> (the un-stored walk) + intra-segment offset (derivable from rigid-rod geometry).

### Everything is per-segment; φ₀ has no natural home (Q2)
There is **no filament-indexed store** — a filament is only an emergent equivalence class of chain-sharing
segments (`FilamentStore.java:31`). Even the nucleotide proxy `nucFrac` is per-segment (`AgingStore`). `filID`
is a transient per-segment scratch `IntArray(nSeg)` recomputed by pointer-doubling (barbed-terminal slot as
label, `FilIDSystem.java:11-16`) — not persisted, not an axial coordinate.
**Cheapest home = a new per-segment `FloatArray` in `FilamentStore`** (mirrors `monomerCount`/`segLength`/
`nucFrac`; `RigidRodBody` aliasing untouched — actin-specific like `monomerCount`). O(1) local read; must be
propagated by the turnover kernels (as `monomerCount`/`seedNode` already are). Keying φ₀ by the `filID`
terminal is *not* cheaper (still `IntArray(nSeg)`-backed + a walk per read). **No filament-level home exists to
extend.**

### Turnover interaction (Q3) — growth/split clean, depoly single-scalar, **severing hard**
Define s = **contour distance from the pointed end** (not world position):

| Event | Segment-set change | φ₀ (pointed-anchored) update | per-seg s |
|---|---|---|---|
| Barbed growth (`GrowthSystem.grow`) | tip lengthens at barbed end | **none** (φ₀ invariant) | unchanged |
| Split @64 (`GrowthSystem.splitWire`) | 1→2, contour conserved, chain rewired | **none** (child copies parent φ₀ — 1 added line) | unchanged |
| Pointed depoly (`DepolySystem.depoly`) | tip loses 1 monomer / tip seg dies | **single-scalar φ₀ += Δ/monomer** (`returnedMon` known) | s=0 relocates inward |
| **Severing** (`SeveringSystem`+`applyDeath`) | interior seg removed → 2 fragments | pointed fragment clean; **barbed fragment needs walk-derived new φ₀** | new pointed end mid-chain — **HARD** |
| Nucleation/birth (`initNewborn`) | fresh 3-mono seed | **assign at birth** (0 or random) | s from 0 |
| Coalescence (SCPR) | force coupling only, **no chain merge** | **n/a** | n/a |

Detail: barbed growth adds material only at the tip and keeps the pointed reference monomer's contour-s fixed
(`GrowthSystem.java:87-95`) ⇒ φ₀ anchored at the pointed end is **invariant** — the added monomers just extend
φ to larger s. Split conserves contour and rewires 3 slots (`GrowthSystem.splitWire:148-189`) ⇒ child copies
parent φ₀. Pointed depoly retracts the pointed reference monomer (`DepolySystem.java:87-100`) ⇒ φ₀ advances by
(2π/P)·rise per removed monomer — a clean per-event scalar (segment death jumps by `returnedMon`). **Severing**
(`SeveringSystem:58-74` → `DepolySystem.applyDeath:170-183` breaks BOTH links, two sub-chains) exposes a fresh
pointed end mid-chain on the barbed fragment; its new φ₀ = old φ₀ + (2π/P)·(contour-s of the sever point), and
**contour-s is not stored** ⇒ a chain walk is required. This is the one operation where a single-scalar update
is insufficient. Coalescence is a non-issue: crosslinks/binding never concatenate backbones and there is no
branching (`FilIDSystem.java:11-20`), so no chain-merge φ₀ reconciliation.
> **Flag — stale comment.** `GrowthSystem.java` header (`:16-22`) and the `grow` method comment (`~:69,:89-91`)
> describe the growth coord-shift inconsistently (header: end2/node fixed, end1 extends outward, `coord −=`;
> method-local: "end1/node fixed, end2 extends," `coord +=`). The header is the authoritative post-swap form;
> the method-local wording reads as a pre-swap remnant. Physical behavior (barbed growth, φ₀-invariant) is
> unaffected, but resolve the comment before building on it.

---

## Scope verdict (restated for the planner)

Azimuthal-aware, site-limited binding is a **gate-injection + φ₀-bookkeeping** task, **not** a DOF-add or
DOF-unlock — the filament axial-roll DOF is already FREE, integrated, thermally live, and v1-parity. Pieces,
sized:
- **Roll DOF** — *already present.* Zero cost to have a free axial-roll coordinate. The only decision is the
  PART-1 caveat #2: whether φ₀ rides a representative segment's `yVec`, a new per-filament roll coordinate, or
  a torsional-roll coupling that makes per-segment rolls cohere (design fork, not a missing-DOF problem).
- **Azimuthal gate — medium.** Thread `filYVec` into the bind signatures; add the accept test at the named
  hook (`reachTestDistSq:82-84`); **replicate across the 5 predicate copies** (PTX inlining constraint).
- **Steric gate — medium.** Reuse the `boundSeg` CSR-inverse (`CrossBridgeSystem:1182-1291`); add a
  min-axial-gap `segGather`-shaped kernel over `bindArc`; resolve propose→admit ordering (previous-step
  bound set, or a two-phase admit à la `CrosslinkerSystem.formAdmit`).
- **φ₀ store — small.** One per-segment `FloatArray` in `FilamentStore`.
- **Turnover φ₀ update — small except severing.** Growth/split no-op (child copies φ₀); nucleation assigns at
  birth; pointed depoly is a single-scalar increment; **severing needs a chain-walk to derive the new
  fragment's φ₀** — the one genuinely hard piece.

**No implementation performed. `BoA-v1ref` read-only throughout.**
