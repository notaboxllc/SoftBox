# Increment 6c — actin polymerization: v1 BEHAVIOR reconnaissance (the foundational growth capability)

**Purpose.** A **behavior** survey (not a class-port map) to let the planner scope a minimal SoftBox
(v2) actin-**growth** build — the deferred capability that makes SoftBox's static, pre-placed,
fixed-length filaments actually **grow**, and the bridge to **Test B** (two nodes' nucleated filaments
grow long enough to find each other and walk together) and the **contractile ring**. Build **from
behavior**, not by porting code: jba confirms the **node** formin/nucleation is settled, so the
**node-seed growth is too**; the **membrane** polymerization (jba's in-development damped-filament work)
is **OUT**. v1 is the reference for clean specifics; **flag drift, don't copy it**.

**Survey only — no code, no runs, no edits.** `BoA-v1ref` read byte-clean (read-only oracle); the live
`BoA` tree was consulted only for currency (it is **byte-identical in logic** to the frozen ref on every
constant + the node-nucleation/growth path — no drift). All file:line refs verified 2026-06-18.
v2 = SoftBox (`softbox/`); v1 = `BoA-v1ref/boxOfActin/`. Companion to `INC6_NODE_RECON.md` (§2b first
flagged growth as "a SECOND new capability beyond birth"); this doc resolves that flag.

---

## Headline findings for the planner

1. **THE GRANULARITY FORK IS RESOLVED — and the news is very good. v1's model and SoftBox's
   representation are already the SAME shape: "lengthen the terminal segment, then split."** v1 does NOT
   create a per-monomer object. A v1 `FilSegment` is a **length-mutable rod carrying an integer
   `monomerCt`** (FilSegment.java:268); growth = `monomerCt++; length += 0.0027 µm` (`addMonomerSim`,
   :2764). When a segment grows past **2× `stdSegLength` = 64 monomers** it **splits in half** into a new
   `FilSegment` (parent keeps 32, child gets 32, chain-linked) — `splitSegment` (:863), threshold check
   (:584). SoftBox already carries the **identical** per-segment pair `monomerCount` +
   `segLength = (monomerCount+1)·actinMonoRadius` (`FilamentStore.java:50-51`), and `DragTensorSystem` is
   **already written to recompute `segLength`+drag from `monomerCount`** — its own doc says it "would
   re-run on a length change" (`DragTensorSystem.java:16,77-79`). ⇒ The mapping is **confirmed against
   v1**, not a from-scratch biochem layer: **bump `monomerCount` at the growing end + re-run drag; split
   into a new B1-allocated segment at 64.**

2. **The genuinely-new work is small and localized — three pieces.** (i) a runtime **lengthen** step
   (mutate `monomerCount`, re-run drag **per-event** — today `DragTensorSystem` runs once at init,
   host-side, over all slots); (ii) the **split** = the B1 scan-rank allocator (reused VERBATIM to birth
   the child segment) + **chain-neighbor rewiring** of three slots (the allocator does NOT touch the
   topology arrays — this host-side rewire is the main correctness risk); (iii) a **concentration-
   dependent growth rate** that **reads** the actin pool (B2's nucleation was deliberately
   [actin]-*independent*; elongation is first-order in [actin] — `ActinPool` exposes `conc()` but nothing
   reads it for a rate yet). Everything else — the allocator, chain-bonding, `ActinPool` take/restore,
   the wang-hash rate RNG, integration/Brownian/derive — is **reused**.

3. **The minimal cut = barbed-end elongation only; DEFER depolymerization/treadmilling.** v1's depoly is
   real and active-by-default (nucleotide- + end-asymmetric, returns monomers to the pool), but it
   **connects to the deferred filament turnover** (depol shrinks/removes filaments; without it filaments
   grow monotonically — which is exactly what Test B's *bridging* needs). Recommend: ship growth-only
   first; depol arrives with turnover at ring scale (§6, §8).

4. **The node-seed growth path is SETTLED but has been entirely DORMANT.** Live `BoA` byte-matches the
   frozen ref; no in-flux markers on this path (the *membrane* polymerization is the churning one — jba
   confirmed). BUT it is **off by two switches**: `forminsPerNode = 0` (the master nucleation gate) and
   **`noMonomersSimd = true` in the static/contractile configs skips ALL biochem** (`biochemStep` early-
   returns). So **every inc-6 node/contractile assay ran with growth OFF (static rods)** — this is
   genuinely-new, never-exercised machinery, and the validated assays are unaffected by adding it.

---

## 1. v1 polymerization behavior

### 1a. The growth machinery (what lengthens a filament)

- **Per-event growth — `FilSegment.addMonomerSim(onRate)`** (FilSegment.java:2764): draws
  `rng < onRate · theBox.getMonomerConc() · biochemDeltaT`; on accept `monomerCt++`,
  `length += halfmono`, `theBox.takeMonomer(1)` (pool depletion), `lengthChanged = true`.
  `halfmono = actinMonoRadius = 0.0027 µm` (Env.java:528-529) — each monomer adds **2.7 nm**.
- **Driver — `biochemStep()`** (FilSegment.java:540) runs every `biochemCheckInt` steps on a separate
  **biochem clock `biochemDeltaT = 1e-3 s`** (coarser than the mechanical `deltaT`). It calls
  `end1BiochemSim()` (:1041) and `end2BiochemSim()` (:1088). **Only true filament ends polymerize** —
  guarded `if (!filAtEnd1)` / `if (!filAtEnd2)` (:558-559); **interior segments never grow.**
- **Node seeding** (already ported as B2) — `ProteinNode.spawnNodeFilaments()` (ProteinNode.java:696)
  births a 3-monomer seed at `kNodeNuc·deltaT`, end2 anchored to the node, pool depleted by 3.

### 1b. On-rate — CONCENTRATION-DEPENDENT (elongation), [actin]-INDEPENDENT (nucleation)

- **Elongation is first-order in [actin]:** `P = onRate · getMonomerConc() · biochemDeltaT`
  (FilSegment.java:2765). `getMonomerConc()` returns the live global scalar `Env.actinConc`
  (Bug.java:260), so growth **reads and depletes the same pool**.
- **On-rate constants** (Env.java, µM⁻¹s⁻¹; **end2 = barbed**):

  | constant | default | role |
  |---|---|---|
  | `kATPOn2WithFormin` | **11.6** | barbed on-rate **at a formin/node** (:718) |
  | `kATPOn2` | 11.6 | barbed on-rate, free (:706) |
  | `kATPOn1` | 1.3 | pointed (end1) on-rate (:687) |
  | `actinConc` | **15 µM** | pool concentration (`actinConc_init`, :405) |
  | `actinCritConc` | 0.12 µM | critical conc (Pollard 1986; only in the stall cap, §1f) |

- **Nucleation (B2, done) is NOT concentration-dependent** — fixed `kNodeNuc = 10/node·s` (:895), no
  `[actin]` factor. (Contrast `spawnRdmFilaments`: `kRdmNuc·conc^actinSeed·deltaT`, but `kRdmNuc=0` —
  off.) ⇒ **growth adds the pool-*read* that B2 didn't need.**

### 1c. Off-rate / depolymerization — present, active-by-default, nucleotide+end-asymmetric

- `removeMonomerSim(offRate)` (FilSegment.java:2786): `P = offRate · biochemDeltaT`, `monomerCt--`,
  `length -= halfmono`, `theBox.putMonomer(1)` (**restores the pool**). Runs unconditionally each biochem
  step (no enable flag), floored at `monomerCt ≥ actinSeed = 3` (:1072, :1120) so a seed can't vanish.
- **Off-rate constants** (s⁻¹): pointed `kATPOff1/kADPOff1 = 0.8 / 2.7`; barbed `kATPOff2/kADPOff2 =
  1.4 / 7.2`; barbed-at-formin = free barbed (1.4/7.2) — **the formin does NOT suppress dissociation via
  the rate** (it suppresses formin *release*, §1e). Treadmilling is **emergent** (fast barbed-on / slow
  pointed-on + ADP-fast-off) — no `treadmill` flag. `joinSegments` (segment-*merge* on shrink) is
  **commented out** (:601) — a shrinking filament loses monomers but never re-merges segments in v1.

### 1d. Barbed vs pointed; the geometry of where growth happens

- **end2 = barbed/plus** (fast); **end1 = pointed/minus** (slow). Confirmed
  `// end2 = plus/barbed end` (FilSegment.java:3986).
- **The node/formin sits at end2 (barbed), at the node CENTER.** The seed is born with `nucVec` reversed
  (`nucVec.scale(-1)`, ProteinNode.java:705) so **end2 faces into the node**; `end2BiochemSim` inserts
  monomers **at end2 (the node side)** — so the filament grows **at the held barbed tip** and the
  **pointed end (end1) trails radially outward** as the contour lengthens.
- **On split, the barbed end + formin MIGRATE to the new child segment** (`transferEnd2Plasmid`,
  FilSegment.java:334) — the parent becomes interior, the child carries the growing tip + node bond.

### 1e. The formin's role — processive barbed-end elongation, load-dependent release

- The formin/node **holds end2 (barbed) and stays at the tip as monomers insert** (processive). The
  hold = the same `fracMove` spring SoftBox already ported (`forceMag = fracMove·1e-6·strainDist /
  ((1/bTransGam.x + 1/end2Node.bTransGam.x)·deltaT)`, FilSegment.java:2397) + an optional alignment
  torque `nodeTorqSpring = 1e-18 N/rad` (:2410, **active by default** — B2 omitted this; flagged below).
- **Release is a load-attenuated catch-bond**: `checkForminRelease`/`forminCanHold` (:1160, :2619),
  `P = forminRelease·biochemDeltaT`, `×= exp(-ln10·(-nodeForce/2pN))` under compression; `forminRelease
  = 1/s` (:606). (Hydrolysis-dependent + random-release variants are commented out — vestigial.)

### 1f. Force-modulated (stall-force) barbed growth — a refinement, recommend DEFER

- `getPolyRateEnd2()` (FilSegment.java:980) attenuates the barbed on-rate under node compression:
  `rateMod = exp(-polyLogFactor·(-nodeForce/maxPolyForce))`, with `maxPolyForce =
  kTOverDelta·ln(actinConc/actinCritConc)` (:402) and `polyLogFactor = ln(100)` (:593). This is the only
  use of `actinCritConc`. It is the actin-stall-force physics (growth slows as it pushes the node) — a
  **second-layer refinement**; the minimal build can grow at the unmodulated `kATPOn2WithFormin·[actin]`.

### 1g. Pool coupling (seam #2) — a conservative global scalar, read AND written

- The "pool" is the **global concentration scalar `Env.actinConc`** (µM), NOT a spatial field. Routed
  through `theBox`: **read** `getMonomerConc()→actinConc.getValue()` (Bug.java:260); **decrement**
  `takeMonomer(n)→actinConc.addToValue(-n·microMolarChangePerMonomer)` (Bug.java:268); **restore**
  `putMonomer(n)→+n·…` (Bug.java:276). `microMolarChangePerMonomer = (1e5³·1e6)/(bugVolume·Avogadro)`
  (Bug.java:110) — the µM-per-monomer in the box volume. **Genuinely conservative + concentration-
  coupled**: every added monomer reads then lowers global [actin]; depoly returns it; `[actin]` falls as
  the network polymerizes. (A non-hydrolyzable mirror `actinConcNonHydro` exists but is inert by default.)

### 1h. Settledness / default on-off

- **Master off-switch `forminsPerNode = 0`** (Env.java:433): `canNucleateFilament()` returns true only if
  `boundFilaments < forminsPerNode` (ProteinNode.java:732) ⇒ with 0, no node ever nucleates or grows.
- **`noMonomersSimd`** (Env.java:610, default false but **set true by the CPU contractile/static
  configs**, BoxOfActin.java:2893): `biochemStep` early-returns (:554) ⇒ **all poly/depoly/hydrolysis
  skipped.** The inc-6 node/contractile assays ran here — **growth never exercised.**
- Other defaults: `actinSeed=3`, `stdSegLength=32` (split at 64), `actinConc=15 µM`, `kRdmNuc=0` (random
  nucleation off), `forminMoves=false`, `twoForminsOpposite=false`. **Vestigial code (build jba's spec,
  don't copy):** `getNucleationVec()` has dead code — computes a `twoForminsOpposite` branch then
  unconditionally returns a random unit vec (ProteinNode.java:739) ⇒ nucleation direction is **always
  random**; `joinSegments` and the alt `forminCanHold` variants are commented out. **Stable, not in-flux.**

---

## 2. Geometry (where growth happens) — and a SoftBox end-convention flag

- **v1:** end2 (barbed) anchored at the node center; growth inserts at end2 (node side); the filament
  extends outward with end1 (pointed) trailing. On split, barbed+formin migrate to the child.
- **🚩 SoftBox end-convention reconciliation.** B2's `seedTether` tethers the seed's **end1** to the node
  center (`NodeNucleationSystem.java:131-141`), whereas v1 anchors **end2 (barbed)**. SoftBox has **no
  barbed/pointed nucleotide asymmetry yet** (no per-monomer ATP/ADP state — and v1 runs `noMonomersSimd`
  anyway, so the load-bearing growth quantity is the *scalar* `monomerCt`, not the nucleotide list). ⇒
  This is a **labeling choice, not new physics**: pick the **node-tethered end as the growing end** so
  monomers insert at the node side and the filament extends outward, and document which SoftBox end maps
  to v1's "end2/barbed." Keep it **consistent with B2's existing seed tether** (grow at end1, the
  node-side) to avoid re-plumbing the tether. Flag for jba; trivial to flip if the convention should
  match v1's end2 literally.

---

## 3. THE granularity mapping (the design fork — RESOLVED, recommendation below)

**v1's mechanism (verified):** one `FilSegment` lives between `stdSegLength`=32 and `2·stdSegLength`=64
monomers; `monomerCt++`/`length+=0.0027µm` per growth event; at ≥64 → `splitSegment` halves it
(parent→32, child→32, the child chain-linked at parent.end2 and inheriting the barbed tip + node bond).
A seed starts at `actinSeed`=3 and grows up to 64 as a **single** segment before its first split.

**The SoftBox mapping — RECOMMENDED: "lengthen the terminal segment, then split."** This is the exact
v1 mechanism and it lands almost entirely on machinery that already exists:

| Step | How | Reuse vs new |
|---|---|---|
| **Lengthen** | At the growing (node-side) terminal segment: `monomerCount.set(slot, +1)`, recompute `segLength`+drag | **`monomerCount`/`segLength` fields + `DragTensorSystem` (already recomputes both from `monomerCount` — "would re-run on a length change")**; NEW = call it **per-event for one slot** (today: once at init, host-side, all slots) |
| **Grow rate** | `P = kOn·pool.conc()·biochemDeltaT`, wang-hash draw, on a biochem-cadence step-gate | **wang-hash rate pattern + `ActinPool`**; NEW = `pool.conc()` **read** for the rate + the `pool.take(1)` per monomer |
| **Split @ 64** | Allocate a child segment slot; parent `monomerCount -= 32`, child = 32; **rewire chain neighbors** of {parent, child, parent's old end2-neighbor} | **B1 scan-rank allocator (verbatim) births the child slot**; NEW = the **host-side chain-neighbor rewire** (allocator does NOT touch `end*Nbr*`) — the main correctness risk |
| **Integrate** | unchanged | **integration/Brownian/derive are capacity-iterating + data-driven** — a heterogeneous/runtime-changing length needs no kernel change |

**Why the chain kernel already tolerates this (the good news, verified):** `ChainBendingForceSystem.
chainForces` iterates **full capacity**, reads each segment's **neighbor indices and `segLength` fresh
every step**, gates each side on the `-1` sentinel, and assigns Newton's-3rd-law ownership by **index
comparison** (`if (i < e2Slot)`), each segment self-writing only its own slot
(`ChainBendingForceSystem.java:105,149-150,153,182,238`). ⇒ A runtime-added terminal segment, correctly
wired, is picked up the next step **with no kernel change**; heterogeneous lengths are already supported
(harnesses set varying `monomerCount`).

**🚩 The split's correctness risk (flag, gate it like B1 was):** the chain kernel assumes **one neighbor
per end** + ownership-by-index; a split must update **three slots' topology arrays consistently on the
host before the next step** (parent.end2 → child.end1, child.end2 → parent's former end2-neighbor, and
that neighbor's back-pointer). The allocator gives a race-free child slot but writes only pose+Brownian+
state — **the rewire is entirely new code.** Gate it like the B1 allocator: distinct-slot/no-double,
neighbor-consistency, Newton-sum-zero across the rewire, CPU≡GPU bit-identical, all-OFF≡static-HEAD.

**🚩 The drag-clamp subtlety (flag, fidelity check vs v1):** SoftBox `DragTensorSystem` floors drag at
`minMonomerCt = 30` (`DragTensorSystem.java:73`), and the "free rod at-end on both ends" branch assumes
the seed geometry. v1's seed is `actinSeed=3` monomers (≈10.8 nm) — **well below 30** — and grows through
that boundary; the drag changes discontinuously at the clamp. Worth a small fidelity check (does v1
floor seed drag similarly?) and a decision on whether a 3→30 monomer seed should use clamped or actual
drag. Note also `DragTensorSystem.run` is **host-side over all slots** (`:75`); a per-growth-event call
is cheap at init/node scale but wants a single-slot or device variant if growth is hot at ring scale.

**dt-stability** (the dt-stiffness family): (a) v1 grows on a **separate coarse biochem clock**
(`biochemDeltaT=1e-3 s`, every `biochemCheckInt` steps) — reuse the **step-gated cadence** (like B2
nucleation / the crosslinker `checkInt=100`) so the GPU graph stays fixed. (b) The split is **balanced**
(both halves land at 32 monomers — never a tiny/huge sliver), so the chain spring absorbs a same-size
neighbor, not a singular one — the least-stiff way to add a segment. The B2 "damping-as-dt-compensation"
principle (`INC6C_NODE_STAGEB2_FINDINGS.md:38-48`) is flagged as generalizing if a freshly-split segment
flails, but the balanced split makes that unlikely; **flag, measure, don't pre-build damping.**

---

## 4. Pool coupling (seam #2) — confirmed, both directions

- **Growth depletes per monomer**: reuse `ActinPool.take(1)` per accepted monomer (the exact monomer-
  consumption point B2 nucleation already uses, `ActinPool.java:35`).
- **Growth READS the pool** (because elongation is concentration-dependent, §1b): `P ∝ pool.conc()` —
  **new** (`ActinPool.java:40` exposes `conc()`; nothing reads it for a rate yet; B2 only gated on
  `available()`). Keep the rate behind the same accessor so the pool can later become a depletable
  counter / diffusing field without rewiring growth (seam #2 unchanged).
- **Depol restores** (`putMonomer`): SoftBox `ActinPool` has `take()`/`available()` but **no `put()`/
  restore** yet — **add only when depol is built** (deferred, §6). The minimal growth-only build needs
  read+take, not restore.

---

## 5. Reuse map vs genuinely-new

**Reuse (already exists, growth-ready):**
- `monomerCount` + `segLength` per-segment fields — growth = mutate `monomerCount` (`FilamentStore.java:50-51`).
- `DragTensorSystem` — **explicitly designed to "re-run on a length change"**; recomputes `segLength`+drag from `monomerCount` (`DragTensorSystem.java:16,77-79`).
- `ChainBendingForceSystem` — reads `segLength`+neighbor indices per step, full-capacity, sentinel-gated, ownership-by-index → tolerates runtime-added terminal segments + heterogeneous lengths, no kernel change.
- **B1 scan-rank allocator** (`FilamentBirthSystem`) — segment-granular runtime birth into a free slot; reused VERBATIM to birth the **child segment** on a split.
- **`ActinPool`** seam #2 (`available()`/`take()`) — the monomer-consumption point.
- **Wang-hash RNG rate pattern** (`NodeNucleationSystem.java:68`) — template for the stochastic monomer-add probability (fresh salt, key on slot/step/seed, `u < P`).
- Integration / Brownian / derive over `RigidRodBody` — capacity-iterating, data-driven; a growing segment needs no integrator change, only its drag updated.
- `filState` + `markFree` — slot reuse, for the deferred death/turnover half.

**Genuinely-new (no code today):**
1. **Lengthen-terminal-segment** — runtime `monomerCount++` + **per-event drag re-run** for one slot (today host-side, once, all slots).
2. **Split @ 64 + chain-neighbor rewire** — B1 allocator for the child, then the **3-slot host-side topology rewire** (the allocator doesn't touch `end*Nbr*`). The main correctness risk.
3. **Concentration-dependent growth RATE** — read `pool.conc()` and scale the rate (B2's nucleation was [actin]-independent).
4. **dt-stable chain growth** — the step-gated biochem cadence + (if needed) split-seam damping.
5. **Formin/barbed-end tracking** — B2 tracks `seedNode` (which node a *seed* is tethered to) but there is **no growing-tip/formin-at-barbed state**; a processive grower must know which end grows and (on split) migrate the tip+formin to the child.

---

## 6. Minimal-viable scope (for Test B + the ring)

- **Essential — barbed-end (node-side) elongation at the pool-dependent rate.** `P =
  kATPOn2WithFormin·pool.conc()·biochemDeltaT` on a biochem-cadence step-gate → `monomerCount++` +
  `take(1)` + per-slot drag re-run; split at 64 via the B1 allocator + chain rewire. Filaments grow long
  enough to **bridge nodes** — the Test B prerequisite.
- **DEFER — depolymerization / treadmilling** (pointed-end shrink). It is real + active-by-default in v1,
  but: (a) it **connects to the deferred filament turnover** (depol shrinks/removes filaments; freed
  seeds already accumulate without turnover — a known run-length bound flagged in B1/B2); (b) **growth-
  only is monotonic, which is exactly what Test B bridging wants**; (c) it adds the pool `put()`/restore
  + the nucleotide-state asymmetry. **Recommend: growth-only first; depol arrives with the turnover/
  treadmilling layer at ring scale** (§8), where a steady state (formation≈dissolution, growth≈shrink)
  becomes the physically-meaningful quantity.
- **DEFER — the stall-force modulation** (`getPolyRateEnd2`, §1f), the **`nodeTorqSpring` align torque**
  (active in v1, omitted by B2 — flagged there; revisit with growth since a growing filament's
  orientation matters more), and any **per-monomer nucleotide (ATP→ADP) biochem** (SoftBox has no
  per-monomer state; v1 runs `noMonomersSimd` — the scalar `monomerCt` is the load-bearing quantity).

---

## 7. Settledness / drift

- **The node-seed polymerization is SETTLED** in v1: live `BoA` is byte-identical-in-logic to the frozen
  ref on every growth constant + the node-nucleation/growth path; no in-flux markers. This matches jba's
  "the node formin works fine."
- **The MEMBRANE polymerization is the churning one and is OUT** (jba's in-development damped-filament
  work) — do not read or port it.
- **Build jba's behavioral spec, flag v1 drift, don't copy it** (as with the B2 nucleation): the
  vestigial bits (§1h — `getNucleationVec` dead code / always-random direction, commented-out
  `joinSegments` segment-merge, the alt `forminCanHold` release variants) are **stable but vestigial** —
  reproduce the *behavior* (random nucleation direction; no segment re-merge on shrink), not the dead
  branches.
- **The growth path has been fully DORMANT** through inc 6 (`forminsPerNode=0` + `noMonomersSimd=true`) —
  so it is never-exercised new machinery, and adding it default-off leaves every validated assay
  byte-unchanged (the established no-op-when-off guarantee).

---

## 8. The Test B / ring horizon (informational)

- **Growth unlocks Test B** — the minimal **search-capture-pull-release (SCPR)** primitive: two
  fixed-anchor nodes nucleate pre-bonded seeds (B2, done) that **grow** (this build) long enough to
  **bridge the gap**, get **captured by each other's myosins** (the node motor-function, Stage A, done),
  and **walk together** (the dimer/minifilament glide, done). Growth is the missing middle.
- **The contractile ring** — a ring of nucleating nodes growing actin, crosslinked (inc 5, done),
  myosin-pulled (done) → condense; tension read at the anchors (the contractile-assay `PinSystem`,
  done). The fixed-anchor minimal ring is reachable without membrane (`INC6_NODE_RECON.md` §4d) once
  growth lands.
- **🚩 Turnover/depol interplay at ring scale.** Without depol, filaments **and** freed seeds accumulate
  (the run-length bound) — fine for a short Test B, a problem for a long ring run. With depol →
  **treadmilling steady state** (growth≈shrink, the pool buffers it). So depol becomes **necessary at
  ring scale**, which is why it pairs with the turnover layer (§6) rather than the first growth build.
- **Adjudication posture (CLAUDE.md §8):** ring-scale **emergent** quantities (filament-length
  distribution, network density, whole-ring force/condensation) are checked against **first-principles
  physics** (mass conservation of the pool, length-vs-[actin] scaling, treadmilling steady state) — NOT
  by matching v1's numbers (v1's contractile ring is itself new/uncalibrated). v1 remains the
  **component-port** oracle for the growth **rate laws + the split mechanism** (matched bit-for-decision),
  re-confirmed against a fresh `ProteinNode.java`/`FilSegment.java` snapshot at build time.

---

## TL;DR for the planner

- **Granularity fork RESOLVED — recommend "lengthen the terminal segment, then split,"** which is exactly
  v1's mechanism (a length-mutable `FilSegment` carrying `monomerCt`; `monomerCt++`/`length+=2.7 nm` per
  monomer; split in half at 64 monomers). SoftBox already carries the matching `monomerCount`/`segLength`
  pair and a `DragTensorSystem` written to recompute them on a length change. **Not a from-scratch
  biochem layer.**
- **Genuinely-new = three small pieces:** runtime lengthen (mutate `monomerCount` + per-event drag
  re-run), split-via-B1-allocator + **chain-neighbor rewire** (the correctness risk — gate it like the
  B1 allocator), and a **concentration-dependent growth rate** that reads the pool (B2's nucleation was
  [actin]-independent). Everything else is reused (allocator, chain-bonding, `ActinPool`, the wang-hash
  rate, integration/Brownian/derive).
- **On-rate is first-order in [actin]** (`kATPOn2WithFormin=11.6 µM⁻¹s⁻¹`, pool=15 µM); growth at the
  **node-side barbed end (end2)**, filament extends outward; pool **read + depleted** conservatively.
- **Minimal cut = barbed-end elongation only; DEFER depolymerization/treadmilling** (it connects to the
  deferred filament turnover and matters at ring scale — growth-only/monotonic is what Test B bridging
  needs). Also defer the stall-force modulation, the `nodeTorqSpring` torque, and per-monomer nucleotide
  biochem.
- **dt-stability:** grow on a step-gated biochem cadence (GPU graph stays fixed); the split is balanced
  (32+32) so the chain spring absorbs a same-size neighbor — least-stiff; measure before adding seam
  damping.
- **Settled + dormant:** the node-seed growth path is settled (live `BoA` byte-matches) but has been OFF
  through all of inc 6 (`forminsPerNode=0` + `noMonomersSimd=true`) — never-exercised; default-off keeps
  every validated assay byte-unchanged. The **membrane** polymerization is the churning one and is OUT.
- **🚩 Flags:** (a) reconcile SoftBox's growing-end convention with B2's seed tether (grow at the
  node-side end) vs v1's end2/barbed label — a labeling choice, not physics; (b) the split's 3-slot chain
  rewire is the main new correctness risk; (c) the `minMonomerCt=30` drag clamp vs the 3-monomer seed
  needs a fidelity check; (d) `DragTensorSystem.run` is host-side over all slots — wants a single-slot/
  device variant if growth gets hot.
- **Horizon:** growth unlocks **Test B** (grow → bridge → capture → walk) and the **fixed-anchor
  contractile ring**; depol/turnover pairs with the ring (treadmilling steady state); ring-scale emergent
  behavior adjudicated vs **physics**, not v1's uncalibrated numbers.
- **Survey only — no code written.** `BoA-v1ref` byte-clean.
