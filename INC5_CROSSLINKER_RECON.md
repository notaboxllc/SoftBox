# Increment 5 (crosslinkers / Arp2/3) — code-state reconnaissance

**Purpose.** Ground-truth survey for the planner to write the increment-5 implementation plan. **Survey
only — no crosslinker code, components, or systems were written.** All file:line refs verified
2026-06-16. v2 = SoftBox (`softbox/`); v1 oracle = `BoA-v1ref` (read-only, byte-clean — `boxOfActin/`).

**Headline flag for the planner:** the existing race-free cross-entity gather (motor→filament) is
**single-ended** (one `boundSeg` per motor, motor self-writes to one body slot). A crosslinker couples
**two filaments** (both endpoints receive reactions), so fil↔fil coupling **needs a new gather pattern —
the CSR-inverse template does not cover it as-is.** Flagged in §2; deliberately not designed here.

---

## 1. SoftBox plug-in points (SoA component/system + integer-ID model)

**Entity model.** Entities are integer slot IDs `[0, n)`; state is **planar-SoA** `FloatArray`/`IntArray`
(x-plane | y-plane | z-plane, stride = n). The SoA arrays are the source of truth; systems are
free-standing `static` methods over those arrays with `@Parallel` loops, identical on the GPU TaskGraph
and the `-cpu` runner.

- **Filaments — `FilamentStore.java`.** Canonical pose `coord`/`uVec`/`yVec` (`:40–42`); derived
  `zVec`/`end1`/`end2` (`:45–47`, recomputed by `DerivedGeometrySystem`); geometry `monomerCount`/
  `segLength` (`:50–51`); drag `bTransGam`/`bRotGam` + diffusion (`:54–57`); per-step accumulators
  `forceSum`/`torqueSum` (`:63–64`, zeroed each step, `+=` by force systems); Brownian draws/scales
  (`:65–70`); **chain topology** `end1NbrSlot`/`end1NbrSide`/`end2NbrSlot`/`end2NbrSide` (`:73–76`,
  sentinel `-1`). `planeX/Y/Z(i)` indexing `:137–139`. Publishes into the broad-phase via
  `publishToBodyView` (`:172–192`).
- **Motors — `MotorStore.java`.** Articulated 3-body (rod=3m, lever=3m+1, head=3m+2) embedding a shared
  `RigidRodBody`; binding interface `head`/`uVec`/`rodUVec`/`reach` (`:62–68`); **bound-state encoding**
  `boundSeg[m]` (`:70–71`, `≥0`=segment slot, `-1`=FREE_BINDABLE, `-2`=FREE_COOLDOWN); `bindArc`,
  nucleotide state, `kinParams`, `cooldown`, race-free `stats`.
- **Shared systems run over `RigidRodBody` arrays** (`BrownianForceSystem`,
  `RigidRodLangevinIntegrationSystem`, `DerivedGeometrySystem`, `DragTensorSystem`) — both
  `FilamentStore` and `MotorStore` embed/alias a `RigidRodBody`, so these run over either with **zero
  class-identity coupling**. Entity-specific physics lives in dedicated systems
  (`ChainBendingForceSystem`, `MotorJointSystem`, `CrossBridgeSystem`, `NucleotideCycleSystem`).
- **System signature pattern** (representative — `ChainBendingForceSystem.chainForces`): a `static` method
  taking the component arrays + a packed-scalar `*Params` `FloatArray` + `counts` `IntArray`; `int N =
  coord.getSize()/3; @Parallel for (i…N)` reads own+neighbor state and `+=` into `forceSum`/`torqueSum`
  (self-write ⇒ race-free).

**Where a new store + system registers.** In `GlidingHarness.java`: construct the store + init pose in
`buildScene` (`~:110–171`, call `DragTensorSystem` for drag); wire systems into **both** runners — the
CPU step sequence (`stepOrig`/`stepFresh`, `~:176–248`, ordered zero→Brownian→forces/gather→integrate→
derived) and the GPU `buildPlan` TaskGraph (`~:255–333`, one `.task(name, Class::method, arrays…)` per
system + a `GridScheduler` `WorkerGrid1D` localWork=64 for RNG/trig-heavy kernels). A crosslinker store
adds a `STORE_CROSSLINKER` constant + a `publishToBodyView` and slots its systems into these two lists.

**Existing scaffolding:** **none.** grep for crosslink/xlink/Arp/FilLink finds only passing comment
mentions (e.g. `CrossBridgeSystem.java` notes the gather infra is "general") — **no crosslinker
component, system, or store exists.** Greenfield.

---

## 2. The cross-entity coupling template — and the fil↔fil gap (the key planner question)

**Current motor→filament coupling (`CrossBridgeSystem.java`, the 4b-ii centerpiece) — race-free, no
atomics, no `KernelContext`:**
1. `bondForces` (`~:58–142`): each motor computes its bond reaction **once**, stores head-side force/torque
   + **seg-side reaction** + load into `bondData[m·STRIDE…]`.
2. `applyHeadForce` (`~:144–157`): each motor `+=` its head-side reaction into its **own** head sub-body
   slot — a one-index **self-write** (trivially race-free).
3. CSR-inverse build keyed by `boundSeg`: `csrHistogram` (count motors per segment) → `csrScan` (offsets)
   → `csrScatter` (motors in deterministic index order), `~:190–216` (single-threaded, bit-identical
   CPU↔GPU).
4. `segGather` (`~:219–239`): each segment (parallel) reads its CSR slice and **sums its bound motors'
   stored seg-side reactions** into its own `forceSum`/`torqueSum`. Gated exact vs a brute per-bond sum
   (`bruteGather`).

The pattern is fundamentally **2-party and single-ended**: a motor has one binding target (`boundSeg[m]`),
writes its own side directly, and **one** segment gathers all motors keyed to it.

**🚩 FLAG — fil↔fil coupling is NOT covered by this template as-is.** A crosslinker binds an endpoint of
filament A to an endpoint of filament B; **both** filament endpoints must receive the crosslinker's
reaction, and a given endpoint may receive reactions from **multiple** crosslinkers. There is no single
"owner" that can do a one-sided self-write, and the gather key is **double** (filA-slot *and* filB-slot),
not the single `boundSeg`. So the motor→segment CSR-inverse cannot be reused verbatim. A **new gather
pattern is required** (the natural shape is a double-ended / two-pass gather that accumulates each
filament side from the crosslinkers bound to it — but the **specific design is the planner's call**; not
designed in this recon per scope). This is the single most important thing for the increment-5 plan to
resolve up front; everything else (store, broad-phase, schema) is incremental.

**Broad-phase reuse — `SpatialBodyView.java` + `SpatialGrid.java`.** Entity-agnostic: publishers register
bounding spheres with `ownerStore`/`ownerSlot` back-pointers; the grid/broad-phase read **only** the view
(center+radius), zero knowledge of filament/motor classes. Constants `STORE_FILAMENT=0`, `STORE_MOTOR=1`
(`SpatialBodyView.java:31–32`) — a crosslinker adds `STORE_CROSSLINKER=2`. `BindingDetectionSystem.
invertCandidates` (`~:95–128`) already filters broad-phase pairs **by `ownerStore`** to motor↔segment;
the same broad-phase **can emit segment↔segment candidate pairs** for crosslinker binding by adding a
`FILAMENT×FILAMENT` branch to the consumer. **Caveats for the plan:** (a) the broad-phase emits unordered
pairs (handle (i,j) vs (j,i)); (b) **same-filament segment pairs** will appear as candidates — v1 excludes
them explicitly (`filID != filID`, see §3) so v2's consumer must too.

---

## 3. v1's crosslinker model (`BoA-v1ref`, read-only) — for port-equivalence checks

Two distinct mechanisms exist (verified files: `boxOfActin/FilLink.java`, `boxOfActin/Arp23.java`):

### 3a. Passive crosslinker — `FilLink` (the increment-5 core)
- **Class/role.** `FilLink.java` links two `FilSegment`s at locations `loc1`/`loc2` along each
  (`:29–32`); static pool `filLinks[100000]`; records `orientSame` (parallel vs antiparallel) at
  formation. Analogous to `MyoFilLink` but **no biochemical state**.
- **Formation** (`FilSegment.checkToLink`, called from the mesh-collision phase
  `FilSegment.java:2053/2078`, gated `iSeg.filID != jSeg.filID & Env.xLinks.isActive() &
  GPUMoveThing.crosslinkFiresThisStep`): alignment gate (mode 0 both / 1 parallel / −1 antiparallel,
  angle ≤ `maxXLinkBondAngle` = π/12 ≈ 15°), closest-approach distance `< crossLinkGrabDist` =
  2·monomerDiam ≈ 0.0108 µm, per-segment saturation `maxXLinksOnSeg`, min spacing `minSepBetweenXLinks`.
  Stochastic: `P_form = 1 − exp(−xLinkOnRate·xLinkConc·dtCheck)`.
- **Unbinding** (`FilLink.ckLinkBreak`, `:182`): Bell strain model `k_off = linkOffConst +
  linkOffCoeff·exp(aveStrain·linkOffExp)`, `P_break = k_off·deltaT`, on an EWMA strain track; checked
  every step.
- **Force law** (`FilLink.applyTransForce`, `:198–217`): overdamped/damping-limited along the link vector,
  `F = fracMove·1e-6·(linkLength − restLength)/deltaT · 1/(γ1⁻¹+γ2⁻¹)` (`restLength=0.0125`, commented nm,
  `FilLink.java:28`), applied equal-and-opposite at both attachment points (auto-generates torque).
  Optional torsional spring `applyTorsionForce` (`:219–254`) when `filLinkTorqSpring` active
  (1e-19 N/rad), restoring the parallel/antiparallel rest orientation.

### 3b. Arp2/3 branching — `Arp23` (a distinct mechanism; likely a later sub-increment)
- **Class/role.** `Arp23.java` anchors a **daughter** filament at a fixed **70°** to a **mother**
  (`motherFil`/`daughterFil`, `momLoc`, `:31–32`); static pool. Parent→child topology (daughter born at
  the branch point, length 0), not a symmetric 2-body link.
- **Formation** `FilSegment.checkBranching → makeArpBranch` (`~:1174–1200`): stochastic
  `P_branch = branchRateNearArpFactors·arpConc·dtBiochem` (default `arpConc=0` ⇒ **disabled by default**);
  70° from `arp23AlphaAngle` (`Env.java:544`). No debranch rate (passive removal with parent).
- **Force** `Arp23.applyTransForce`/`applyTorsionForce` (`:210–254`): damping-limited pull of the
  daughter plus-end toward the anchor + torsional spring `arpTorqSpring` (1e-18 N/rad) holding 70°.

### Parameters (`Env.java`, defaults; verified file:line)
| param (label) | file:line | default | meaning |
|---|---|---|---|
| `xLinks` (label `"sideBonds"`) | :625 | 0 (0 both/1 ∥/−1 anti) | crosslinker enable/mode |
| `maxXLinkBondAngle` | :637 | π/12 (~15°) | formation alignment gate |
| `crossLinkGrabDist` | :646 | 2·monomerDiam ≈ 0.0108 µm | formation reach |
| `maxXLinksOnSeg` | :623 | 10 | per-segment saturation |
| `minSepBetweenXLinks` | :624 | 5·monomerDiam ≈ 0.027 µm | min spacing along a segment |
| `xLinkOnRate` / `xLinkConc` | :669 / :670 | 10 /(µM·s) / 1.0 µM | formation k_on / [xlink] |
| `linkOffConst`/`linkOffCoeff`/`linkOffExp` | :679 / :680 / :681 | 1 /s / 1 /s / 2 | Bell off-rate (k0/α/β) |
| `filLinkTorqSpring` | :634 | 1e-19 N/rad | optional crosslink torsion |
| `arp23AlphaAngle` / `arpTorqSpring` | :544 / :548 | 70° / 1e-18 N/rad | Arp2/3 branch angle / torsion |
| `branchRateNearArpFactors` / `arpConc` | :770 / :771 | 0.1 /s / **0** | Arp2/3 nucleation (off by default) |

### Suggested port-equivalence checks (co-developed small-scale, per the dry-fixture-well oracle posture)
1. **Rest hold:** two parallel segments crosslinked near `restLength` hold a constant separation under no
   external force (zero-velocity ⇒ zero force; Brownian-only fluctuation, no drift).
2. **Shear relaxation:** displace one crosslinked segment tangentially, release → exponential relaxation
   with the damping-limited time constant set by `1/(γ1⁻¹+γ2⁻¹)`; match v1's decay.
3. **Off-rate law:** at zero strain unbind rate ≈ `linkOffConst`; rises per the Bell `exp(aveStrain·
   linkOffExp)` — match the k_off(strain) curve.
4. **Formation steady state:** dense parallel bundle reaches a link-count plateau (formation≈dissolution);
   halving `xLinkConc` ~halves the plateau.
5. **(Arp2/3, if in scope)** a nucleated daughter settles at 70° ± small torsional fluctuation, no drift.

These are **co-developed porting-equivalence checks** (capture a v1 micro-behavior, show v2 reproduces it),
versioned with increment 5 — **not** replayed frozen fixtures (the inc-≤4 fixture well is dry; CLAUDE.md
oracle posture). The >0.1%-is-logic carry-forward rule (§6.8) applies to any quantitative match.

---

## 4. Schema / extensibility

- **Observation schema — `FrameWriter.java`.** The Three.js/WebSocket JSON currently emits `segments` only
  (`~:15–32`, `writeFrame`/`appendSegment` read host pose + derive `end1`/`end2`), copied **verbatim** from
  the v1 viewer (do not fork the viewer). It is **entity-agnostic by intent** (a future "bodies + links"
  schema is a localized append). A crosslinker link adds an `appendCrosslinker` block emitting endpoints
  derived from filament poses + arc — **same host-pose-only pattern as v1's link rendering, no new device
  transfer.** v1 already has `showXLinks`/`showArp23` viewer toggles to mirror.
- **Extensibility / class-identity flag.** The shared infra (grid, broad-phase, RigidRodBody systems) is
  already class-agnostic (works through `ownerStore`/`ownerSlot` ints + generic arrays). To stay aligned
  with the project goal (components/systems registerable, biology findable-by-name, **no premature plugin
  loader**), the crosslinker must couple through **store + integer slots**, never via `instanceof
  FilamentStore` / class downcasts. The one genuinely new design surface is the fil↔fil gather (§2) — keep
  it keyed by integer filament/endpoint slots, not class identity.

---

## TL;DR for the planner
- SoA + integer-ID model and broad-phase are ready; a crosslinker is a new store (`STORE_CROSSLINKER=2`) +
  systems wired into `GlidingHarness` buildScene/CPU-step/GPU-buildPlan. No existing scaffolding.
- **The one hard design decision: filament↔filament force coupling needs a NEW race-free gather** (the
  motor→segment CSR-inverse is single-ended; crosslinkers are double-ended). Flagged, not designed.
- v1 model: `FilLink` (stochastic formation, Bell strain off-rate, damping-limited spring ± torsion) is the
  core; `Arp23` (70° branch) is a distinct, default-off mechanism — likely a later sub-increment.
- Fidelity = co-developed small-scale port-equivalence checks vs `BoA-v1ref` (5 suggested), not fixtures.
- Schema stays entity-agnostic; couple via store+int slots, never class identity.
