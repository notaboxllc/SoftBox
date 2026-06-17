# Increment 6 (myosin structures: dimers / minifilaments / nodes) — code-state reconnaissance

**Purpose.** Ground-truth survey for the planner to write the increment-6 (myosin-structure) implementation
plan, mirroring how `INC5_CROSSLINKER_RECON.md` opened the crosslinker increment. **Survey only — no
myosin-structure code, components, or systems were written.** All file:line refs verified 2026-06-17.
v2 = SoftBox (`softbox/`); v1 oracle = `BoA-v1ref` (read-only, byte-clean — `boxOfActin/`, HEAD `8e23789`,
tag `softbox-filref-2026-06-13`); active v1 = `BoA` (`boxOfActin/`, HEAD `bab3c07`, 2026-06-16) — referenced
only for the snapshot-currency diff (§4).

**Headline flags for the planner (three couplings, three different costs):**
1. **Minifilament (N dimers → one backbone) is SINGLE-ENDED** — it reuses the existing motor→segment
   CSR-inverse gather (`CrossBridgeSystem.csr*` + `segGather`) **one pass**, keyed by a backbone slot, the
   backbone gathering its heads. **Less machinery than the crosslinker two-pass** (§2). This is the central
   architectural finding and it is *favorable*.
2. **Dimer (motor↔motor, 1:1) needs NO gather at all** — each rod sub-body is uniquely owned by exactly one
   dimer, so the dimer self-writes both rod reactions directly to two distinct slots (race-free, no CSR).
   Simpler than both motor→segment and crosslinker (§2).
3. **Nodes are architecturally reachable WITHOUT the membrane subsystem** (a fixed anchor / `AnchorNode`
   suffices for search–capture–pull–release; membrane confinement is an optional orthogonal layer) — **BUT
   the v1 node code FAILS the settledness gate**: `ProteinNode`/`NodeLink`/`StickyNode` carry post-snapshot
   physics churn (06-15/06-16) and active v1 HEAD self-flags "nodes not yet stable" (§3, §4). Dimers +
   minifilaments are settled and current; **nodes should wait on a fresh post-stabilization snapshot.**

Suggested staging (planner's call): **6a dimer → 6b minifilament → 6c node** (§6).

---

## 1. SoftBox plug-in points (SoA component/system + integer-ID model)

**Entity model** (unchanged from inc 5): integer slot IDs `[0, n)`; planar-SoA `FloatArray`/`IntArray`
(x|y|z planes, stride n) are the source of truth; systems are free-standing `static` methods over those
arrays with `@Parallel` loops, identical on the GPU TaskGraph and the `-cpu` runner.

**The existing motor is SINGLE-HEADED — a dimer/minifilament is a NEW higher-level assembly, not a
reshaping of motors.** `MotorStore.java`:
- One motor slot = one articulated **3-body** rod→lever→head chain embedding a shared `RigidRodBody`;
  sub-body flat index `3m`=rod, `3m+1`=lever, `3m+2`=head (`MotorStore.java:37–42`). The **head is the only
  binding end** ("bindTip"); `head`/`uVec`/`rodUVec`/`anchor` are a published projection of the body
  (`publishHeadFromBody`), the head sub-body being the source of truth (`:40–42`).
- Bound state in **one int field** `boundSeg[m]` (`≥0` segment slot / `-1` free-bindable / `-2` rebind
  refractory, `:27–29`); nucleotide state, `forceDotFil`/`forceDotHist` ring, `bindArc` per motor.
- `public final RigidRodBody body` = 3·nMotors sub-bodies (`:42`).

**Shared rigid-rod layout — `RigidRodBody.java`** (entity-agnostic; FilamentStore embeds n, MotorStore
embeds 3n): pose `coord`/`uVec`/`yVec`/`zVec`/`end1`/`end2`, geometry `segLength`, body-frame drag
`bTransGam`/`bRotGam`/`bTransDiff`/`bRotDiff`, accumulators `forceSum`/`torqueSum`/`randForce`/`randTorque`,
Brownian scales `brownTransScale`/`brownRotScale`. The shared systems (`BrownianForceSystem`,
`RigidRodLangevinIntegrationSystem`, `DerivedGeometrySystem`, `DragTensorSystem`) read **only** these
generic fields — never entity-specific state. **A dimer/minifilament backbone is a third instance of this
abstraction** (a rigid-rod body integrated by the same shared systems, exactly as MotorStore was the second
instance in 4b-i) — the abstraction-leak rule holds as long as the only entity-specific bits (head-sphere
drag init, structural joints) stay in localized init + per-entity systems.

**Where a new store + systems register.** `GlidingHarness.java` is the orchestration template:
- `buildScene` (`:110`) constructs stores + inits pose + calls `DragTensorSystem`.
- CPU step: `stepOrig` (`:177`) / `stepFresh` (`:215`) — the per-step sequence (zero → publish → reach →
  release → bind → cycle → forces/joints/cross-bridge → head self-write → integrate → derive → register
  load → filament forces → CSR gather → integrate → derive).
- GPU `buildPlan` (`:255`) — the 23-kernel device-resident TaskGraph; one `.task(name, Class::method,
  arrays…)` per system + a `GridScheduler` `WorkerGrid1D` localWork=64 for RNG/trig-heavy kernels.
A myosin-structure store adds its systems into these three lists (or, given the structural-bed harnesses
4b-i `MotorBodyHarness` and 4b-ii `MotorXBridgeHarness`, a **dedicated structure harness** is the cleaner
first-validation vehicle — pre-placed bed, isometric/small-scale, before wiring into the full glide).

**Existing scaffolding for myosin STRUCTURES: none.** grep finds only passing mentions of "node"/"mini"/
"dimer" in comments (`CrossBridgeSystem`, `FrameWriter`, `SpatialBodyView`, JOURNAL). The single-motor +
articulated-body + crosslinker-coupling infra is the substrate; the structures are greenfield.

---

## 2. Coupling-template map — the key architectural question (flag, don't design)

Three reusable templates exist. Each new structural coupling maps onto one, OR is flagged new:

| existing template | shape | where |
|---|---|---|
| **PAIRS joint** (link spring F3 + bend/align torque F4) | 2-party, both ends uniquely owned | `MotorJointSystem` (J1/J2), `TailAnchorSystem` (anchor), `ChainBendingForceSystem` |
| **single-ended CSR-inverse gather** | many-to-one (N writers → 1 owner gathers) | `CrossBridgeSystem.bondForces`:58 / `applyHeadForce`:145 / `csrHistogram`:190 / `csrScan`:198 / `csrScatter`:206 / `segGather`:221 (gated vs `bruteGather`:242) |
| **double-ended two-pass gather** | symmetric, shared endpoints (many-to-each-of-two) | `CrosslinkerSystem.segGatherA`:129 / `segGatherB`:153, the CSR run VERBATIM twice keyed by `linkFilA`/`linkFilB` |

### head↔backbone (a head jointed to a shared tail/lever) — **COVERED by the PAIRS-joint template.**
The intra-motor rod→lever→head articulation (`MotorJointSystem` J1/J2, the PAIRS pattern with rest angles;
one thread per sub-body, self-write, race-free) already *is* head↔backbone within one motor. v1's dimer
lever-alignment and the minifilament backbone↔dimer tether are the **same PAIRS spring + alignment torque
law** (§3) — reuse, no new gather.

### dimer (motor↔motor 1:1 structural link) — **NEW, but the SIMPLEST: no gather.** 🚩 FLAG
v1's `MyosinDimer` is exactly **two `Myosin` objects** (`myo1`,`myo2`, verified `MyosinDimer.java:12,45–58`)
joined by rod↔rod coupling springs + a lever-alignment torque — a **1:1 pairing**. In SoftBox terms each
motor (hence each rod/lever/head sub-body) belongs to **exactly one dimer**. Therefore the dimer computes
its coupling reaction once and **writes both sides directly** into its two uniquely-owned rod sub-body
slots — two self-writes to distinct slots, **race-free with no CSR gather at all** (unlike the crosslinker,
where a filament endpoint is shared by *many* links and so needs a gather). The dimer is the *least*
machinery of the three couplings. (The specific kernel layout — a `DimerStore` carrying `(motorA,motorB)`
slot pairs + a `DimerCouplingSystem` doing the four rod-coupling variants + lever align — is the planner's
call; not designed here.)

### minifilament (N dimers → one backbone) — **SINGLE-ENDED; reuse the CSR-inverse ONE pass.** 🚩 CENTRAL FLAG
This is the §2-equivalent of the crosslinker fil↔fil question, and the answer is *favorable*: it is
**single-ended like motor→segment, NOT double-ended like crosslinker.** v1's `MyoMiniFilament` is a rigid-rod
backbone that **owns** `2·numMyoDimersEachEnd` dimers and gathers their reactions backbone-side
(`constrainEnd1Dimers`/`constrainEnd2Dimers`, §3) — one consumer (the backbone), many writers (its dimers),
each dimer keyed to exactly one backbone. That is precisely the motor→segment shape (`boundSeg[m]` = one
owner; segment gathers all motors keyed to it). So the plan is: add a `headBackboneSlot[child]` int (which
backbone each dimer/head attaches to), build the CSR-inverse with `CrossBridgeSystem.csrHistogram/csrScan/
csrScatter` **keyed by `headBackboneSlot`** (one pass), and have each backbone sum its children's stored
reactions into its own `forceSum`/`torqueSum` exactly as `segGather` does. **No second pass, no compound
key, no new gather machinery** — the crosslinker two-pass is *not* needed here (the asymmetry of "backbone
owns its heads" is what saves the second pass). This should be stated up front in the 6b plan and gated the
same way 4b-ii was (gathered == `bruteGather`, bit-identical CPU↔GPU).

### node anchoring — a tether spring + a fixed/static anchor (§5 reachability).
A node↔filament tether and node↔node link are PAIRS springs (`FilSegment.addNodeForces` /
`NodeLink.applyTransForce`, §3). A filament-end is owned by one node-tether; a node may carry many tethers
⇒ if multiple filaments tether one node, that side is again the **single-ended** many-to-one gather (reuse
the CSR template, node-side). The anchor itself can be a **fixed point** (`TailAnchorSystem`-style or
`AnchorNode`), needing no membrane (§5).

**Broad-phase reuse — `SpatialBodyView`/`SpatialGrid`.** Entity-agnostic; `STORE_FILAMENT=0`,
`STORE_MOTOR=1`, `STORE_CROSSLINKER=2` (`SpatialBodyView.java:31–33`). Myosin structures add the next
constants (`STORE_MINIFILAMENT`/`STORE_NODE` = 3,4…) + a `publishToBodyView`. Note dimers/minifilaments are
**statically assembled** (§3) — like 4b-i/5a the first sub-increment can pre-place them and skip the
broad-phase publisher entirely (wire it only when dynamic assembly/binding arrives).

---

## 3. v1's myosin-structure model (`BoA-v1ref`, read-only) — for port-equivalence checks

### 3a. Dimer — `MyosinDimer.java` (396 lines)
- **Structure.** Two independent `Myosin` (`myo1`,`myo2`; `:12`, constructed `:45–58`), each a full
  rod→lever→head articulation. **No shared rigid backbone** — the "dimer" is a pair + external coupling
  springs. `parallel` flag, `ownerMiniFil` back-pointer (`:14`, set when part of a minifilament; used only
  for a GPU cohesion gate). `leverAngle = 160°` target (`:9`).
- **Coupling mechanics (component-port).** Four rod↔rod PAIRS springs — `applyRodCouplingEnd1`/`End2`/
  `End1End2`/`End2End1` (`:163–273`), each damping-limited `F = fracMove·1e-6·strain/(dt·(moveC1+moveC2))`
  with anisotropic `moveCoeff` projection (the same PAIRS law as the intra-motor J1/J2 joints) — coeff
  `Env.myoDimerFracMove`. Plus `alignUVecLeversTorque` (`:111–135`) restoring the 160° lever angle, coeff
  `Env.myoDimerLeverFracMoveTorq`. **No new force law** — identical pattern to the ported joints.
- **Assembly: STATIC.** Two myosins created in the constructor; removal nulls both (`:101–102`). No runtime
  dimer formation/dissolution.
- **Port class:** pure component-port (PAIRS springs + alignment torque). Emergent-quantitative: the
  steady-state head height/tilt and any two-head cooperativity during gliding (cross-check vs physics, not
  just v1-matching, per the §8 posture).

### 3b. Minifilament — `MyoMiniFilament.java` (681 lines)
- **Structure.** `extends Thing` ⇒ a **rigid-rod backbone** with pose (the third `RigidRodBody` instance);
  `length = 0.180 µm`, `radius = 0.005 µm` (`:23–24`). Owns `numMyoDimersEachEnd` dimers per end in two
  arrays `myoDimersEnd1[]`/`myoDimersEnd2[]` (`:57–59`), placed in body-local coords near each end
  (`makeMyosinDimers`, `:393–424`); `numMyoDimersEachEnd = Env.numMyoDimersEachEndOfMiniFil` (init **8**,
  `Env.java:371`) ⇒ 2N=16 dimers, 4N=32 heads default.
- **Backbone↔dimer coupling (component-port, BACKBONE-SIDE GATHER).** `constrainEnd1Dimers`/
  `constrainEnd2Dimers` (`:436–528`): for each owned dimer, a PAIRS tether spring (`Env.myoMiniFilFracMove`)
  between the dimer rod end and the backbone attachment point + an alignment torque (`Env.myoMiniFilAlign`)
  to the backbone axis; **all reactions accumulate into the backbone's `forceSum`/`torqueSum`** — the
  asymmetric one-consumer gather that maps onto the single-ended CSR template (§2). `updateMyosinDimer
  Positions` (`:426–434`) refreshes child poses.
- **Assembly: STATIC.** `makeMyosinDimers()` called inline at construction; fixed N; only cascading removal
  (`removeAllMyoDimers`, `:659–672`) when the backbone dies (stochastic `myoMiniLifetime`). `countBoundMotors`
  (`:317–332`) is diagnostic only.
- **Port class:** component-port (PAIRS tether + align). Emergent-quantitative: per-minifilament binding
  rate (N heads), force transmission to the backbone during gliding.

### 3c. Nodes / contractile ring — `ProteinNode.java` (983), `NodeLink.java` (300), `StickyNode.java` (790), `AnchorNode.java` (39)
- **Node structure.** `ProteinNode extends Thing` — a body (pose, variable `radius`, drag tensors,
  `forceSum`/`torqueSum`), with `fixedNode` flag (`:24`) + `xMove`/`yMove`/`zMove` (`:39–44`) and surface
  myosin/dimer arrays. `AnchorNode extends ProteinNode` is a **fully fixed node** (drag `1e6,1e6,1e6`, empty
  `step()`/`moveThing()` — `AnchorNode.java:3–20`) — a ready proof of a static anchor.
- **SCPR is "nucleate-not-search".** A node does **not** run a search/capture predicate over a filament
  pool. It **nucleates** filaments at its surface with end2 pre-bonded (`spawnNodeFilaments`, rate
  `Env.kNodeNuc·dt`; `ProteinNode.java:696–717`, sets `newFil.nodeAtEnd2=true; end2Node=curP`). **Pull** =
  a damping-limited tether spring `F = fracMove·1e-6·strain/(Σγ⁻¹·dt)` applied at the filament end2 and
  `-F` on the node (`FilSegment.addNodeForces`, `:2389`+; gate `if(nodeAtEnd2)`). **Release** = three paths
  (`addNodeForces` tail, `:2428–2439`): strain `> Env.maxNodeTetherStrainDist` (init 2·monomerDiam,
  `Env.java:600`), stochastic `Env.nodeTetherDetachRate·dt` (init 0.001/s, `:603`), or formin break under
  compressive load `forminCanHold` (log-stretch exponential `Env.forminRelease·dt·exp(...)`,
  `FilSegment.java:2619`, gated at `:1162`).
- **Node↔node links — `NodeLink`.** PAIRS spring `applyTransForce` (`:198–211`) + Bell-strain
  `ckLinkBreak` (`:182`) — the same crosslinker/`FilLink` lifecycle shape. Formed **dynamically** on
  collision (`StickyNode.ckToLink`, `:353`, fired from `ProteinNode` when two sticky nodes are within
  contact) OR pre-placed by the sphere/cylinder/icosahedron setup builders.
- **Assembly.** Nodes themselves are **pre-placed** (setup builders) with optional stochastic lifetime
  culling; filaments are nucleated dynamically; node↔node links form dynamically on collision.
- **Port class.** Component-port: the tether spring, `forminCanHold` load-release, `NodeLink` spring + Bell
  break. Emergent-quantitative: nucleation-rate-driven filament population, network valence/topology,
  whole-ring contractile force balance — adjudicate vs physics (the §8 posture; v1's contractile ring is
  itself new/unsettled, see §4).

**Suggested co-developed port-equivalence checks** (small-scale, capture a v1 micro-behavior; not frozen
fixtures — the inc-≤4 well is dry, per the oracle posture). The >0.1%-is-logic rule applies to deterministic
matches; near-cancelling Brownian balances are physics-cross-checked (§8 posture):
1. **Dimer rod-coupling hold/relax** — a dimer at rest holds its two-rod geometry (no drift, equal-opposite);
   displace one rod tangentially, release → exponential relaxation at the damping-limited τ. (component-port)
2. **Dimer lever angle** — Brownian-on steady lever-to-lever angle fluctuates about 160° with no drift. (port)
3. **Minifilament isometric bed** (the 4b-i analog) — a pre-placed backbone + its 2N dimers holds its
   articulated shape under Brownian; backbone-side gathered force == `bruteGather`, bit-identical CPU↔GPU. (port + gate)
4. **Minifilament force transmission** — a bound head's cross-bridge reaction reaches the backbone through the
   single-ended gather (gathered == brute per-bond sum). (port + gate)
5. **Node tether pull** — a fixed `AnchorNode` + one filament: end2 relaxes to the tether rest at the
   damping-limited τ; pull force == `Env.fracMove` law. (component-port)
6. **Node tether release** — strain-threshold + stochastic-rate + `forminCanHold(load)` each fire at the
   ported rate/threshold (the Bell/log-stretch curve), like the 5b off-rate check. (component-port)

---

## 4. Snapshot currency (settledness gate)

Anchors: **BoA-v1ref** `8e23789` @ tag `softbox-filref-2026-06-13` (byte-clean); **active BoA** `bab3c07`
@ `membrane-render-and-dense-v5`, 2026-06-16 (3 days ahead).

| file | changed since snapshot? | physics or tooling? | verdict |
|---|---|---|---|
| `MyosinDimer.java` | yes (1 commit, `0a95bc2`) | **tooling** (static-array cap 300k→600k for a dense benchmark) | **CURRENT — no fresh snapshot** |
| `MyoMiniFilament.java` | yes (same `0a95bc2`) | **tooling** (cap 10k→40k) | **CURRENT — no fresh snapshot** |
| `Myosin/MyoMotor/MyoRod/MyoLever/MyoFilLink/GPUMyosinJoints/MyosinFixed.java` | no | — | identical / current |
| `FillNode.java`, `AnchorNode.java` | no | — | identical / current |
| `ProteinNode.java` | yes (newest `5fc9cb5`, **06-15**) | **PHYSICS** (NPF-patch branching, membrane-node displacement cap, StickyNode turnover disabled) | **NOT current** |
| `NodeLink.java` | yes (newest `bab3c07`, **06-16**) | **PHYSICS** (rest-length elastic-mesh spring vs legacy zero-rest, parameterized stiffness, valence-averaged Jacobi relax, edge-split growth) | **NOT current** |
| `StickyNode.java` | yes (6 commits, newest `bab3c07`, **06-16**) | **PHYSICS** (Arp2/3 depletion field, formin pool, vesicle pressure, cortex-alignment torque) | **NOT current** |

**Verdict.**
- **Dimers + minifilaments: CURRENT in the 06-13 snapshot** — the only post-snapshot touch is benchmark
  array-cap sizing, which moves no port target. **6a/6b can port directly against BoA-v1ref; no fresh
  snapshot needed.**
- **Nodes (ProteinNode/NodeLink/StickyNode): NOT current AND NOT settled** — substantial post-snapshot
  *physics* through 06-16, and `bab3c07` is explicitly WIP ("nodes not yet stable"). Per the settledness
  gate, **6c needs a fresh targeted snapshot of these three files, taken once the active membrane/cortex
  work stabilizes.** The node force laws are still in flux today.

**Separate reconciliation flags (already-ported components).** No physics drift: `FilLink` (crosslinker)
**0 diff**; the single myosin motor (`MyoMotor`/`MyoRod`/`MyoLever`/`MyoFilLink`/`GPUMyosinJoints`)
**0 diff**. `FilSegment.java` changed (464 lines, to 06-16) but the changes are new biology (Arp2/3,
formin nucleation, membrane-cortex, Brownian-ratchet) — the ported inc-2 PAIRS chain force (F3/F4) and base
rigid-rod Langevin are **untouched**; one Brownian-adjacent `heldBrown` factor is default-off
(`cortexBrownianZone>0`). **No inc-2/inc-4/inc-5 reconciliation required.**

---

## 5. Schema / extensibility + the node↔membrane reachability call

- **Observation schema — `FrameWriter.java`.** Emits `segments` only today (`writeFrame`/`appendSegment`,
  host-pose-only, viewer copied verbatim — do not fork). Dimers/minifilaments/nodes add entity-agnostic
  append blocks (a backbone is a rod ⇒ reuses `appendSegment`; dimer/node poses + tethers derive from host
  pose, no new device transfer). The v1 viewer already has `myosins`/`minifilaments`/`nodes`/`contractility`
  `if(...)`-guarded blocks to mirror (JOURNAL note `:1009`).
- **Couple via store + integer slots, never class identity.** The shared infra is already class-agnostic
  (`ownerStore`/`ownerSlot` ints). Keep the dimer pairing (`motorA`,`motorB`), the minifilament
  `headBackboneSlot`, and the node tether keys as **integer slots**, not `instanceof`/downcasts.
- **🚩 Node↔membrane reachability — NODES ARE REACHABLE WITHOUT THE MEMBRANE SUBSYSTEM.** The SCPR
  mechanics read only node pose + force, never membrane geometry: `FilSegment.addNodeForces` (`:2389`) and
  `forminCanHold` (`:2619`) reference no `sphericalGeometry`/`membraneCellRadius`. Membrane confinement is a
  **conditional, orthogonal layer**: `StickyNode.moveThing` gates `addSphericalConstraintForce`/
  `internalPressure` behind the `sphericalGeometry` flag (`StickyNode.java:19,261–276`), and `AnchorNode`
  proves a fully fixed, drag-immobilized node. **A node sub-increment can run on a fixed anchor / static
  ring** (capture-pull-release on a pinned filament, à la 4b-iii on a pinned filament) — the membrane
  (inc 7: StickyNode + NodeLink mesh + iterative relaxation) is a *separate* later subsystem, not a
  prerequisite. **Caveat:** this makes 6c *architecturally* reachable now, but §4's settledness gate still
  defers it on the *v1-code-stability* axis (node physics not yet frozen in active BoA).

---

## 6. Dependency / ordering

Build order **6a dimer → 6b minifilament → 6c node** matches v1's structural containment (a minifilament
*owns* dimers; a node *carries* surface myosins/dimers).

| level | reuses (verbatim / template) | genuinely adds |
|---|---|---|
| **6a dimer** | `RigidRodBody` + shared integration/Brownian/derived; `MotorStore` motor as the unit; PAIRS spring + alignment-torque pattern (`MotorJointSystem`) | a `DimerStore` of `(motorA,motorB)` slot pairs; a `DimerCouplingSystem` (4 rod-coupling variants + lever align) — **direct two-slot self-write, NO gather** (§2) |
| **6b minifilament** | all of 6a; **the single-ended CSR-inverse gather** (`CrossBridgeSystem.csr*`+`segGather`) ONE pass; `RigidRodBody` backbone (3rd instance) | a backbone store; `headBackboneSlot` keying; backbone-side gather of its dimers; static assembly placement |
| **6c node** | PAIRS tether spring; the single-ended gather (node-side, if many tethers/node); the crosslinker/`NodeLink` lifecycle-sentinel + Bell-break + scan-rank allocator (`linkState`, `csrScan` free-list) for dynamic node↔node links; `AnchorNode`-style fixed anchor | `ProteinNode` body store; nucleate-not-search tether (`addNodeForces`); strain/stochastic/formin release; (deferred) dynamic collision-link formation; **gated on a fresh v1 node snapshot (§4)** |

**Cross-dependencies / flags.**
- 6b depends on 6a (a minifilament's children are dimers). 6a and 6b are both settled + current (§4) and
  use only the *favorable* couplings (no gather / single-ended gather) ⇒ **clean, low-risk first targets.**
- 6c is architecturally independent of membrane (§5) but **blocked by the settledness gate** (§4) — port it
  only after a fresh `ProteinNode`/`NodeLink`/`StickyNode` snapshot once active BoA's node/membrane work
  stabilizes. Its dynamic node↔node link lifecycle will reuse the **full inc-5 lifecycle stack** (sentinel
  field + Bell `ckLinkBreak` + scan-rank allocator), so it inherits that machinery for free.
- The §8 posture (CLAUDE.md): v1 is the **component-port** oracle for the force laws/rates/joints here
  (matched bit-for-decision), but for **emergent** structure behavior (dimer cooperativity, minifilament
  force transmission, whole-ring contractile balance) adjudicate against **physics** (equipartition/FDT,
  conservation, scaling) — especially for nodes, where v1's contractile ring is itself new/uncalibrated.

---

## TL;DR for the planner

- **Plug-in points ready:** SoA + integer-ID model, `RigidRodBody` (a dimer/minifilament/node backbone is
  its next instance), the `GlidingHarness` buildScene/CPU-step/buildPlan template (or a dedicated structure
  harness for isometric first-validation), and the `STORE_*`/`SpatialBodyView` seam. Greenfield for
  structures — no existing scaffolding.
- **The three couplings, three costs (the architectural core):** dimer (1:1) = **direct two-slot
  self-write, no gather**; minifilament (N→1 backbone) = **single-ended CSR-inverse, ONE pass** (reuse
  `CrossBridge` gather, *not* the crosslinker two-pass — the central favorable finding); node tether =
  PAIRS spring + (node-side, if needed) single-ended gather. None needs a new gather pattern — a contrast
  with the inc-5 fil↔fil gap.
- **v1 model:** dimer = two `Myosin` + rod-coupling springs + 160° lever align (static); minifilament =
  rigid-rod backbone owning 2N dimers, backbone-side gather (static, N=8/end); node = nucleate-not-search
  tether (`addNodeForces`) with strain/stochastic/formin release + dynamic `NodeLink` springs. Six suggested
  co-developed port-equivalence checks.
- **Snapshot currency / settledness:** dimers + minifilaments **CURRENT** (06-13 snapshot faithful, port
  now). Nodes **NOT current AND not settled** (post-06-13 physics churn, active HEAD self-flags unstable) —
  **fresh node snapshot required, defer 6c.** No drift in already-ported motor/crosslinker/chain physics.
- **Node↔membrane:** nodes are **reachable WITHOUT membrane** (fixed anchor / `AnchorNode`; SCPR reads only
  node pose+force) — membrane is a separate inc-7 layer, not a prerequisite. So 6c's only blocker is the
  settledness gate, not membrane.
- **Suggested staging:** 6a dimer → 6b minifilament (both clean/settled/low-risk) → 6c node (after a fresh
  v1 snapshot).
- **Survey only — no code written.** `BoA-v1ref` byte-clean; couple via store+int slots, schema
  entity-agnostic.
