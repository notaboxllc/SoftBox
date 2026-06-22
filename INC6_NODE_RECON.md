# Increment 6c — protein node: v1 BEHAVIOR reconnaissance (for a build-fresh ECS node)

**Purpose.** A **behavior** study (not a class-port map) to let the planner scope a *fresh* ECS
protein-node, per jba's instruction: study v1's node behaviors, but build the node as a distinctly
separate ECS thing — NO v1 object inheritance. **Survey only — no code, no runs, no edits.** `BoA-v1ref`
read byte-clean (read-only oracle); active `BoA` consulted only for currency. All file:line refs verified
2026-06-17. v2 = SoftBox (`softbox/`); v1 = `BoA-v1ref/boxOfActin/`.

Companion to `INC6_MYOSTRUCT_RECON.md` (which mapped the dimer/minifilament couplings and first flagged
the node settledness gate). This doc goes one level deeper on the **node-specific** behaviors that the
fresh build must reproduce, and on the **one genuinely-new architectural element** (runtime filament
birth).

---

## Headline findings for the planner

1. **jba's decomposition is CONFIRMED behaviorally — with one precise refinement.** v1's myosin node
   is **NOT literally a minifilament**: the node body is a **sphere with orientation** (`ProteinNode
   extends Thing`, a rigid body with a `radius`), and it owns BOTH singlet myosins AND dimers **tethered
   radially to its surface**. BUT the *mechanism* — a rigid body owning motor-children via a `fracMove`
   tether spring + a **backbone-side single-ended gather** — is **exactly the minifilament's mechanism**
   (§1). So "node = minifilament-structure + a different geometry + nucleation" holds: the **reuse base is
   the minifilament tether+gather machinery** (`MiniFilamentSystem` + the §2 single-ended CSR gather);
   the **geometry** is radial sphere-surface splay instead of bipolar-axial; the **one new mechanism** is
   actin **nucleation**.

2. **The one genuinely-new architectural element is RUNTIME FILAMENT BIRTH.** v1's node "nucleate-not-
   search": filaments are *born already bonded* to the node (no proximity search). This is the **first
   dynamic filament birth in SoftBox**, and `FilamentStore` is **today completely static** (no lifecycle
   sentinel, no allocator — §2.4). The inc-5 crosslinker **scan-rank allocator + stochastic-formation
   pipeline is the EXACT template** (§2.2–2.3); the work is to **add a lifecycle layer to FilamentStore**
   (a `filState` sentinel + free-list + a per-system active guard). This is the node's §2-equivalent of
   the crosslinker fil↔fil gather risk — **flag, don't design.**

3. **The core node behaviors extract CLEANLY from the entangled membrane/branching code.** Nucleation,
   the myosin/dimer surface tether, and fixed-point anchoring live in methods that reference **no**
   membrane/cortex/branching state (§3). The entangled, unsettled bits (Rho hot-spot signaling, Arp2/3
   branching, spherical-membrane confinement, `NodeLink` node↔node topology, the Listeria `ActA` model)
   are all in `StickyNode`/`Arp23`/`ActA` and are **deliberately NOT ported**. `AnchorNode` proves a
   **fully fixed anchor with zero membrane dependency**.

4. **Settledness gate still applies (carried from `INC6_MYOSTRUCT_RECON.md` §4).** `ProteinNode`/
   `NodeLink`/`StickyNode` carry post-06-13 *physics* churn and active v1 self-flags nodes "not yet
   stable." The **tether/anchor/lifetime** behaviors we port are the *settled* `fracMove`/Langevin
   patterns (shared with the already-ported minifilament). But the **nucleation specifics** (rate,
   pre-bond geometry, formin release) should be **re-confirmed against a fresh targeted snapshot** of
   `ProteinNode.java` at build time, since that file is the one with active physics churn.

---

## 1. Core myosin-node behaviors (the portable part)

### 1a. Geometry — a sphere body owning surface-tethered myosins/dimers (a *layout* difference, not new physics)

- **Node body = a rigid sphere with orientation.** `ProteinNode extends Thing` — pose (`coord`, `uVec`/
  `yVec`/`zVec`), a variable `radius`, body-fixed drag tensors, `forceSum`/`torqueSum`, integrated by the
  standard Langevin `moveThing()` (`ProteinNode.java:113–124` ctor, `:163–204` drag/Einstein, `:212–270`
  integrate). Mechanically this is **a `RigidRodBody` instance with sphere drag** — the geometry
  difference (sphere vs rod) is a *localized drag/placement init* exactly like the motor head's
  sphere-drag init, **not a forked system**.
- **Owns BOTH singlet myosins AND dimers, tethered radially to the surface.** `myoCt` singlets in
  `myosins[]` at body-relative `myoPtsInx[]` (`:55,57`); `myoDimerCt` dimers in `myodimers[]` at
  `myoDimerPtsInx[]` (`:61,63`); placed by `makeMyosinSinglets`/`makeMyosinDimers` (`:348–361, 443–475`),
  world poses refreshed by `updateMyosinPositions` (`:384–389`).
- **The tether is the same `fracMove` damping-limited spring as the minifilament's.**
  `keepMyosinsOnSurface` (`:391–415`) and `keepMyosinDimersOnSurface` (`:484–504`):
  `F = attnForce·1e-6·strainDist / (dt·(1/myoRod.bTransGam.y + 1/bTransGam.y))`, `+F` on the motor rod,
  `−F` on the node (`:410,413` and `:499,502`). This is **bit-for-pattern the minifilament backbone↔
  dimer tether** (`MyoMiniFilament.constrainEnd1/End2Dimers`, coeff `myoDimerFracMove`) — the
  reaction accumulates **node-side**, the **single-ended gather** shape.

  **Verdict on geometry:** a *layout difference over the same structure* — a rigid body owning dimers via
  the validated tether + single-ended gather. **Confirmed: not new physics.** The node-specific bits are
  (i) sphere body vs bipolar rod (a drag/placement init), (ii) **radial surface splay** of children
  vs the minifilament's two axial end-clusters, (iii) the node *also* carries singlet myosins (the
  minifilament carries only dimers). All three are *placement*, reusing the same tether+gather law.

### 1b. Anchoring — reachable on a FIXED ANCHOR with zero membrane dependency

- **`AnchorNode extends ProteinNode` is a ready, fully-fixed anchor** (`AnchorNode.java:3–20`): drag set
  `1e6,1e6,1e6`, `step()`/`moveThing()` are **no-ops** → the node is immobilized as a pure force-reaction
  point, inheriting all myosin/tether physics. **No membrane, no cortex, no `NodeLink` needed.**
- Confirmed (and re-confirmed from `INC6_MYOSTRUCT_RECON.md` §5): the SCPR mechanics read only node
  pose+force; membrane confinement is a **conditional orthogonal layer** gated behind `sphericalGeometry`
  in `StickyNode.moveThing` (`StickyNode.java:261–276`), never touched by `AnchorNode`. **A node
  sub-increment runs on a fixed anchor / pinned filament exactly like 4b-iii.**
- Free and movement-gated modes also exist (`xMove/yMove/zMove`, `fixedNode` flag, `ProteinNode.java:24,
  39–44, 272–279`) — a fixed anchor is the simplest and the right first target.

### 1c. Turnover / lifetime — stochastic node death (simple, settled pattern)

- **Birth:** explicit construction, appended to a static `theNodes[]` (`ProteinNode.java:962–966`).
- **Death:** stochastic per biochem step — `if (rng < dt/Env.nodeLifetime) removeMe=true`
  (`:282–286`), cleaned by `cleanUpNodes` (`:947–959`); cascading `removeAllMyosins` purges its
  children (`:912–920`). This is the **same lifecycle-sentinel + cleanup shape** as the crosslinker
  `linkState` (a per-node ACTIVE/DEAD sentinel + a cull pass) — reuse that pattern, no new machinery.

### 1d. Verdict on jba's decomposition + what v1's node does BEYOND {minifilament + geometry + nucleation}

**Core = minifilament-structure (tether+gather) + sphere/radial geometry + nucleation — CONFIRMED.**
v1's node additionally does the following; everything past the first item is **entangled/deferred (§3),
NOT part of the fresh node port:**

| v1 node behavior | port? | where |
|---|---|---|
| Surface myosin/dimer tether + node-side gather | **PORT** (= minifilament reuse) | `ProteinNode.java:391–415, 484–504` |
| Stochastic lifetime death | **PORT** (= crosslinker sentinel+cull) | `:282–286, 947–959` |
| Fixed-anchor mode | **PORT** (= `AnchorNode`) | `AnchorNode.java:3–20` |
| Formin actin nucleation (nucleate-not-search) | **PORT — the one new mechanism (§2)** | `ProteinNode.java:696–751` |
| `NodeLink` node↔node spring network + collision linking | **DEFER** (entangled, unsettled) | `NodeLink.java`, `:625–654`, `StickyNode.ckToLink:353–382` |
| Rho hot-spot signaling cascade | **DO NOT PORT** | `StickyNode.java:186–227` |
| Arp2/3 branching trigger (`makeArp23NucFilament`) | **DEFER → inc 5d** | `StickyNode.java:229–236`, `Arp23.java`, `FilSegment.java:1182–1248` |
| Spherical-membrane confinement / internal pressure | **DEFER → inc 7** | `StickyNode.java:261–299` |
| Listeria `ActA` surface nucleation/motility | **NEVER** (v1-app-specific) | `ActA.java` |

---

## 2. Implicit-formin actin nucleation (the one genuinely-new mechanism)

### 2a. v1's node nucleation behavior — "nucleate-not-search", born pre-bonded

- **Rate (per node, stochastic, [actin]-independent):** `P = Env.kNodeNuc · dt` per node per step,
  `kNodeNuc = 10.0 /node·s` (`ProteinNode.java:701`; `Env.java:895–896`). Capped by `Env.forminsPerNode`
  (`:735`). (Contrast the surface/`ActA` path `actANucProb·[actin]·dt` and the background
  `kRdmNuc·[actin]^actinSeed·dt` (default 0) — the **node path is a fixed per-node rate**, the cleanest.)
- **Pre-bonded (no search):** the filament is **created and attached in the same operation** —
  `newFil = new FilSegment(node.coord, nucVec, -1); newFil.nodeAtEnd2 = true; newFil.end2Node = node`
  (`:706–713`). The plus-end (end2) is born anchored to the node; there is **no proximity/capture
  predicate** (unlike crosslinker formation). `spawnNodeFilaments`/`bespokeNodeFilament`/`getNucleationVec`
  at `:696–730`.
- **Initial state = a short seed, not length 0:** `monomerCt = Env.actinSeed` (default **3**,
  `Env.java:540`), `length = (monomerCt+1)·actinMonoRadius` ≈ 4·2.7 nm ≈ **10.8 nm**
  (`FilSegment.java:278–279`), populated as a monomer linked-list (`:480–485`).
- **The pull/release after birth = the settled `fracMove` law** (already characterized in
  `INC6_MYOSTRUCT_RECON.md` §3c): tether `FilSegment.addNodeForces` (gate `if(nodeAtEnd2)`), release on
  strain / stochastic `nodeTetherDetachRate` / `forminCanHold` load-break. These are **the same spring +
  Bell/log-stretch shapes already ported** for motors/crosslinkers.

### 2b. Growth / polymerization — a SECOND new capability, distinct from birth (and a granularity mismatch)

- After nucleation v1 **grows the filament monomer-by-monomer** via a per-end biochem cycle:
  `addMonomerSim` (`FilSegment.java:2764–2773`), `end1/end2BiochemSim` (`:1041–1086, 1088–1117`), rate
  `onRate·[actin]·biochemDeltaT`; depolymerization `removeMonomerSim` (`:2786–2793`), floored at the
  seed length. No hard length cap; capping is a separate `end2Capped` flag.
- **🚩 FLAG — granularity mismatch + a whole new subsystem.** SoftBox "filaments" are **chains of
  rigid-rod *segments*** (inc-2), whereas v1 grows at **monomer** granularity inside one `FilSegment`.
  SoftBox has **no biochem/polymerization system at all** today. So growth is a *second* new element
  beyond birth, and would require either (a) a coarse **segment-addition** growth on the chain, or (b) a
  monomer-count sub-state per segment. **Recommendation to flag (not decide):** a minimal node can
  **birth a fixed-length seed filament and skip growth** (birth alone exercises the allocator + the
  tether), deferring polymerization to its own step — exactly the "build the focused thing first" rule.

### 2c. Implicit-species representation — a depletable SCALAR pool (grounds seam #2)

- v1's actin source is **`Env.actinConc`, a scalar global** (default ~15 µM), **depletable but not
  spatial**: `takeMonomer`/`putMonomer` add/subtract `µM-per-monomer` from the scalar
  (`Crucible.java:171–176`); growth is **mass-action** (rate ∝ `[actin]`). It is **not** a diffusing
  field. (A second scalar `actinConcNonHydro` exists for controls.)
- **Seam #2 (flag, don't build):** the v2 nucleation/growth reads the actin pool as a **scalar parameter
  / constant** now, but should be wired behind a single accessor so it can later become a **depletable
  counter** and eventually a **diffusing scalar field** without rewiring the nucleation function. Do
  **not** build the field/diffusion subsystem now (it emerges at the second implicit-species instance).

### 2d. Filament birth mechanics — v1 is dynamic append+swap; SoftBox `FilamentStore` is fully STATIC (the key gap)

- **v1:** `FilSegment` storage is runtime-dynamic — `addFilSegment` appends to `theFilSegments[]`
  (`:3525–3529`), `removeFilSegment` swap-pops to compact (`:3530–3539`). **No free-list reuse** (append
  + compaction).
- **SoftBox `FilamentStore` (`FilamentStore.java:29–135`): completely static** — fixed `n` at
  construction, **no lifecycle sentinel** (cf. crosslinker `linkState`), **no allocator**, no runtime
  add. Every harness builds `new FilamentStore(nSeg)` once; nothing births a filament. The chain
  topology fields (`end1NbrSlot/Side`, `end2NbrSlot/Side`, sentinel `-1`) are *topology pointers*, not
  lifecycle markers.
- **🚩 KEY NEW ARCHITECTURAL ELEMENT — runtime filament birth on `FilamentStore`.** The inc-5 crosslinker
  stack is the **exact template** (verified reusable, §2 below):
  - **Allocator (reuse VERBATIM):** the scan-rank free-list — `freeFlags` → reused `CrossBridge.csrScan`
    prefix-sum → `freeScatter` compaction → `allocate` claims `freeList[rank]`, writes payload, flips a
    sentinel ACTIVE; distinct ranks → distinct slots (race-free, no atomics), overflow-clamped,
    slot-stable (`CrosslinkerSystem.java:298–345`, `CrosslinkerStore.java:80–98`).
  - **Stochastic rate (reuse the pattern):** the formation pipeline (`fillRequests`/`formGates`/
    `P_form = 1−exp(−rate·dt)` via wang-hash / admission, `CrosslinkerSystem.java:401–500`) — for the
    node, **replace the broad-phase candidate generator with a per-node nucleation emitter** (rate
    `kNodeNuc·dt`, born pre-bonded at the node), and ride the **same allocator underneath unchanged**.
  - **The new work on `FilamentStore`:** add a `filState` lifecycle sentinel (`≥0` ACTIVE / `<0` FREE,
    mirroring `linkState`), size the store to `capacity ≥ preplaced + birthed`, init `[preplaced,
    capacity)` FREE, and add **one `if(filState<0) continue;` guard** to the shared systems
    (`RigidRodLangevinIntegration`/`Brownian`/`DerivedGeometry`/`DragTensor`) and the chain/gather
    consumers. The integer-keyed gathers are already gap-resilient (no class identity). This is the
    crosslinker `if(active)`-guard pattern, one level up.

  **This is the node's central architectural risk — the §2-equivalent of the crosslinker fil↔fil gather.
  Flag it up front in the 6c plan and gate it like the allocator was (distinct-slot/no-double-alloc,
  free-list correctness, same-step reuse, overflow clamp, slot-stability, CPU≡GPU bit-identical,
  all-OFF≡static-HEAD).**

---

## 3. The entanglement separation (the value of building fresh)

The core node behaviors (§1, §2) reference **no** membrane/cortex/branching state and extract cleanly:

- **Cleanly separable (port):** formin nucleation (`ProteinNode.java:696–751` — no `iAmHotRho`/`Arp23`/
  branching refs), myosin/dimer surface tether (`:391–415, 484–504` — geometry-only), fixed-anchor
  (`AnchorNode.java`, `fixedNode` gate), stochastic lifetime (`:282–286`). Boundary confinement is
  already a SoftBox primitive (`ContainmentSystem`, §4).
- **Inseparably entangled — DO NOT PORT** (all in `StickyNode`/`Arp23`/`ActA`, all post-06-13 *physics*
  churn, active HEAD WIP):
  - **Rho hot-spot signaling** — stochastic activation + spread via `NodeLink` neighbors + lifetime
    decay (`StickyNode.java:186–227`); it *gates* branching (`:229`) ⇒ no branching model ⇒ no reason
    for Rho state.
  - **Arp2/3 branching** — `makeArp23NucFilament` (`StickyNode.java:234`), mother↔daughter geometry
    bound to the `Arp23` complex frame every step (`Arp23.java:146–200`, `FilSegment.java:1182–1248`).
    Deferred to **inc 5d** as a separate subsystem, never merged into node code.
  - **Spherical-membrane confinement / internal pressure** — baked into `StickyNode.moveThing`
    (`:261–299`), gated by the global `sphericalGeometry` flag. Deferred to **inc 7** (membrane).
  - **`NodeLink` node↔node topology** — adhesion springs + collision-driven linking (`NodeLink.java`,
    `ProteinNode.java:625–654`, `StickyNode.ckToLink:353–382`). Deferred to a later node-network step
    (it *will* reuse the full inc-5 lifecycle stack — sentinel + Bell `ckLinkBreak` + scan-rank
    allocator — when it arrives).
  - **Listeria `ActA`** — pathogen-surface nucleation/motility (`ActA.java`). v1-application-specific;
    **never ported**.

**Conclusion:** building fresh lets us take {geometry + nucleation + anchoring + lifetime} and leave the
entangled/unsettled membrane/branching code behind entirely. The separation is clean — the core methods
have no membrane/branching call edges.

---

## 4. ECS-fresh composition + post-node horizon

### 4a. The reuse base is broad — only geometry + nucleation are genuinely new

As a `RigidRodBody`-based body in an assay, a fresh node inherits the validated shared primitives, **none
rebuilt**:
- **Motor-function:** the minifilament structure (backbone/body + dimers + binding via `CrossBridge` +
  the single-ended `headBackboneSlot` gather, `MiniFilamentStore`/`MiniFilamentSystem:134–155`) — placed
  by the node geometry (radial splay) instead of the minifilament's axial clusters.
- **Containment:** `ContainmentSystem` (entity-agnostic over any `RigidRodBody`, no-op when inside,
  invoked once per store) — confines a node body unchanged.
- **Stiffness valve:** the 12 pN break-force cap (`MotorStore.setFaithfulRelease`, faithful to v1) —
  inherited by the node's motors, parameter-driven.
- **Lifecycle machinery:** the crosslinker sentinel + scan-rank allocator (for runtime filament birth,
  and later for node↔node links).

⇒ **Genuinely new = (i) the radial/sphere geometry layout, (ii) actin nucleation + runtime filament
birth (and optionally growth).** Everything else is inherited. The recon treats it that way.

### 4b. Sketch — a generic node entity composing separable functions (flag seam #1, don't build it)

- A `NodeStore` (a `RigidRodBody` body + radius + drag) is the **composition point**; functions attach
  via **store + integer slots, never class identity** (the established `ownerStore`/`ownerSlot` /
  `headBackboneSlot` pattern):
  - **motor-function** = reuse the minifilament tether + single-ended gather + binding, with children
    placed by the node geometry (a free-standing system over the node's child arrays).
  - **nucleation-function** = the implicit-formin filament birth (a free-standing system: per-node rate
    → request → reuse the scan-rank allocator → write a pre-bonded seed filament + the node tether).
- **🚩 Seam #1 (flag only):** keep motor + nucleation factored as **separable attachable behaviors** so a
  future node-type ({nucleation+crosslink}, {MT-motor+cargo}) composes a different function-set without
  refactoring this one. **Do NOT build the generality now** — no node-framework, no function-registry, no
  scalar-field/diffusion subsystem. Build exactly the **myosin node = {motor + nucleation}**; the
  abstraction emerges at node #2.
- **🚩 Seam #2 (flag only):** the nucleation function reads the actin pool through **one accessor**
  (scalar constant now), shaped to become a depletable counter / diffusing field later (§2c).

### 4c. Where building fresh diverges from v1 behavior (behavior-faithful, not class-faithful)

- **Same-step vs deferred birth** — like the crosslinker (`5c-i` flag), v2 can do same-step death→reuse;
  v1 forms-at-event / frees-at-cleanup (reusable next step). A ≤1-step timing choice, not a correctness
  change.
- **Segment- vs monomer-granularity growth** (§2b) — a deliberate modeling choice; flag and likely defer
  growth.
- **No `NodeLink`/Rho/Arp2/3** — intentional behavior subset (§3), not a fidelity gap.
- **Emergent quantitative behavior is adjudicated vs physics, not v1** (the CLAUDE.md §8 posture): v1's
  contractile *ring* is itself new/uncalibrated, so nucleation-driven population, network topology, and
  whole-ring force balance are checked against conservation/scaling/equipartition, **not** by matching
  v1's numbers. v1 remains the **component-port** oracle for the tether/release/nucleation-rate force
  laws (matched bit-for-decision) — re-confirmed against a fresh `ProteinNode.java` snapshot (§ headline 4).

### 4d. Post-node horizon — is a minimal contractile RING reachable without membrane?

- **A minimal FIXED-ANCHOR ring is reachable without the membrane subsystem.** A ring of `AnchorNode`-
  style fixed nodes, each nucleating pre-bonded actin and carrying minifilament-style myosins that
  bind/pull neighbors' filaments, needs only: fixed anchors (proved, `AnchorNode`), nucleation +
  filament birth (§2), the motor gather (inherited), containment (inherited), the 12 pN cap (inherited),
  and tension read at the anchors (the contractile-assay `PinSystem` + bookkeeping, already built). This
  is the natural milestone right after the single node — the contractile assay generalized from one
  central minifilament to a ring of nucleating nodes.
- **A self-organizing / constricting ring needs the deferred subsystems.** Dynamic node↔node `NodeLink`
  topology and any real cortex confinement/constriction are the **entangled, unsettled** code (§3) →
  that ring sits at the migration edge with membrane (inc 7). So: **fixed-anchor minimal ring = reachable
  now; dynamic constricting ring = waits on membrane.**

---

## TL;DR for the planner

- **Build exactly one thing: the myosin node = {motor-function + nucleation-function}, fresh ECS, no v1
  inheritance.** jba's decomposition holds: reuse the **minifilament tether+gather** as the motor base,
  with a **radial/sphere geometry layout** and **actin nucleation** as the only new mechanism.
- **The one hard new element: runtime filament birth.** v1 nucleates pre-bonded (no search) at
  `kNodeNuc·dt`; `FilamentStore` is fully **static** today (no sentinel, no allocator). The inc-5
  **scan-rank allocator + stochastic-formation pipeline is the exact template** — the new work is a
  `filState` lifecycle layer + per-system active guards on `FilamentStore`. **Flag, don't design.**
- **Growth/polymerization is a SECOND new capability** (monomer-vs-segment granularity mismatch; no
  biochem system exists). A minimal node can **birth fixed-length seed filaments and defer growth**.
- **Implicit actin = a depletable scalar pool** (`Env.actinConc`, mass-action, not spatial). Wire it
  behind one accessor (seam #2) — scalar now, field-capable later. Don't build the field now.
- **Core behaviors extract cleanly** from the entangled `StickyNode`/`Arp23`/`ActA` membrane/branching/
  cortex code (Rho, Arp2/3, spherical confinement, `NodeLink`, Listeria) — all **deferred or never**.
  `AnchorNode` proves a fixed anchor with **zero membrane dependency**.
- **Two seams to keep (flag only, don't generalize):** (#1) motor + nucleation as separable attachable
  behaviors composed on a node entity; (#2) the actin pool behind one accessor.
- **Settledness:** the tether/anchor/lifetime are settled (shared with the ported minifilament); the
  **nucleation specifics need a fresh `ProteinNode.java` snapshot** at build time (that file still has
  active physics churn).
- **Horizon:** a **fixed-anchor minimal contractile ring is reachable without membrane** (the
  contractile assay generalized to nucleating nodes); a self-organizing/constricting ring waits on
  inc-7 membrane.
- **Survey only — no code written.** `BoA-v1ref` byte-clean.
