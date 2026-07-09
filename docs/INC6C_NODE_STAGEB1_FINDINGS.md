# Increment 6c Stage B1 — the FilamentStore runtime-birth lifecycle (findings)

**Date:** 2026-06-18. **Status:** DONE, all gates PASS GPU+CPU, all prior harnesses bit-identical. Committed on green.

## What B1 is
The **first dynamic filament creation in SoftBox.** `FilamentStore` was fully **static** through inc 6 (fixed
`n`, every slot a live segment, no lifecycle). B1 adds a per-slot **birth lifecycle** so a filament slot can be
allocated at runtime — the recon §2 risk (a lifecycle on the *foundational* entity everything binds to). It is
**v2-side infrastructure, independent of v1's (churning) nucleation specifics**, so it proceeds now and is
validated with a **synthetic** birth (a test driver allocates a seed at a chosen step). **B2** later wires the
node's real nucleation emitter as the birth *source* (it depends on a fresh `ProteinNode.java` snapshot).

**Out of scope (B2 / deferred):** the node nucleation emitter (birth rate / pre-bond geometry / formin
release), the actin pool/accessor (seam #2), growth/polymerization (the seed is **fixed-length**), membrane,
branching.

## Design — three decisions

### 1. `filState` sentinel, default all-ACTIVE (mirrors the crosslinker `linkState`)
`FilamentStore.filState[i]`: `>=0` ⇒ **ACTIVE** (a live segment), `<0` ⇒ **FREE** (empty, allocatable). The
constructor inits it to **all-ACTIVE (0)**. Every existing harness fills `[0,n)` with live segments and never
frees a slot, so it sees `filState` all-ACTIVE and is unaffected. A store gains FREE slots only if a harness
explicitly calls `markFree()`.

### 2. The scan-rank allocator — inc-5 reused VERBATIM, one level up (`FilamentBirthSystem`)
The crosslinker scan-rank free-list (inc 5c-i, Design A — no compaction, slot-stable) is reused with the
allocated thing being a **whole filament slot** instead of a crosslink. The **two prefix sums run
`CrossBridgeSystem.csrScan` VERBATIM**; the two thin companions (`freeFlags`, `freeScatter`) are the standard
prefix-sum-compaction idiom, identical in shape to `CrosslinkerSystem.freeFlags/freeScatter`. Per phase:
`freeFlags → csrScan(free) → freeScatter → csrScan(rank) → allocate`. `allocate` claims `freeList[rank<nFree]`
(overflow-clamped), writes the seed pose, turns ON the Brownian scale, flips `filState` FREE→ACTIVE. Distinct
ranks → distinct slots ⇒ one writer/slot, race-free, no atomics/KernelContext ⇒ **bit-identical CPU↔GPU** by
construction.

The seed is **fixed-length** (v1 `actinSeed=3` monomers ⇒ ≈10.8 nm): `segLength`/`monomerCount`/drag are
pre-initialised identically in **every** slot at scene build (length-agnostic), so a FREE slot already carries
the seed geometry+drag and `allocate` writes only **pose + Brownian scale + state**. Chain neighbors stay
sentinel `-1` — a born seed is a **free rod** (v1 nucleates a single `FilSegment`, born bonded to the *node*,
not to another segment).

### 3. The active-guard is **DATA-DRIVEN, not a per-kernel branch** (the load-bearing decision)
The recon flagged "add one `if(filState<0) continue;` guard to the shared systems." We chose the equivalent
guarantee with a **smaller, safer footprint**: a FREE slot is held inert by its **data**, so **no shared device
kernel is touched** (integrate / Brownian / derive / chain / containment / gather are all byte-unchanged):

- `markFree()` zeroes `brownTransScale = brownRotScale = 0` ⇒ **zero Brownian drive**.
- FREE slot has chain neighbors `-1` ⇒ a **free rod** contributing zero chain force, and **not a neighbor** of
  any active segment ⇒ it cannot perturb others.
- Parked inside the box ⇒ `ContainmentSystem` **no-ops** (its own no-op-when-inside property).
- With `forceSum = torqueSum = 0` and `randForce = 0`, the integrator yields **v = 0** ⇒ the slot never moves;
  `derive` harmlessly recomputes its frozen pose.

So every shared system treats a FREE slot as a **quiescent free rod contributing exactly zero** — and the
**no-op-when-all-active** guarantee is then *by construction* (no FREE slots ⇒ nothing zeroed ⇒ prior harnesses
**byte-unchanged**). This is the strongest form of the regression guard and matches the project ethos
(`ContainmentSystem`'s no-op-when-not-binding; Stage A's "no shared file touched").

**The ONE branch B2 will add (flagged):** keeping a FREE slot **out of the broad-phase** so a motor can't bind a
not-yet-born filament. B1's synthetic harness handles this by **geometry** — FREE slots are parked off the
candidate set (gate C proves a parked FREE filament is *not* bound; once born at a reachable location it *is*).
When B2 births into a live broad-phase scene, the publish path (`FilamentStore.publishToBodyView`) will gain a
single `filState` guard. That is the only shared-kernel touch deferred to B2, and it is itself a
no-op-when-all-active (the guard never fires when every slot is ACTIVE).

## Validation (`run_filbirth.sh`, both runners — all PASS)

**Gate A — allocator correctness + CPU≡GPU.** C=16, ACTIVE odd / FREE even slots:
- free-list = `[0,2,4,…,14]` (FREE indices in **index order**), `nFree=8`.
- 3 birth requests → born slots `{0,2,4}` (distinct, no double-alloc), each with the requested pose + born
  Brownian scale, `filState` flipped ACTIVE.
- **slot-stability** (Design A): the 8 ACTIVE odd slots unchanged; un-claimed FREE evens still FREE+parked.
- **overflow clamp**: `nFree=2`, `K=5` ⇒ exactly 2 born.
- **same-step reuse**: synthetically `markFree(slot 1)` (a B2 death) → free-list `[0,1,2,4]`, rank0 reuses
  slot 0.
- **CPU≡GPU**: the 5 allocator kernels (GPU TaskGraph vs CPU host) — `max|Δcoord|=0`, 0 filState/freeList
  mismatches, `max|ΔbrownScale|=0` — **bit-identical**.

**Gate B — born ≡ preplaced + inert FREE slot + CPU≡GPU.** Shared filament step
`{zero, Brownian, chain, integrate, derive, containment}` over 6 free-rod filaments (3 driven against a wall ⇒
deterministic containment force):
- **born@0 ≡ preplaced** (CPU, 300 steps): `max|Δpose| = 0` — a filament born at step 0 evolves **bit-identically**
  to the same filament preplaced.
- **born@0 ≡ preplaced with Brownian ON**: `max|Δpose| = 0` (same per-slot RNG key ⇒ identical draw).
- **inert FREE slot**: slot J free+parked at origin stays **exactly** at origin after 150 steps; the **other 5
  filaments are bit-identical** (`max|Δ|=0`) to the preplaced run — a FREE slot **never perturbs** the rest.
- **participates after birth**: born@150 then J moves (0.21000 → 0.18961 µm, pushed by the wall).
- **CPU≡GPU** (born scene, 300 steps, deterministic): `max|Δpose| = 0` — bit-identical (the
  containment-driven path is float32-stable to the bit).
- *Note:* a born **seed is a free rod**, so `chain` has no neighbor to act on; chain participation is
  not applicable to a single-segment seed (faithful to v1). Integrate/Brownian/derive/containment participation
  is shown identical to a preplaced filament; binding/gather is gate C.

**Gate C — binding + gather participation.** A born filament + 8 motors:
- **pre-birth** (filament parked FREE far above the heads): bound motors = **0** — the parked FREE slot is
  excluded from binding by **geometry** (the data-driven guard).
- **post-birth** (born ACTIVE at the bindable z): bound motors = **8** — the born filament enters the
  broad-phase / binding pathway.
- **gather**: the cross-bridge load lands on the born slot (`|F| = 2.4e-11 N > 0`) and **equals the brute
  per-bond sum bit-identically** (`max|Δ| = 0`) — the integer-keyed gather treats a born slot as an ordinary
  index (CPU≡GPU of binding/gather is the standing 4b-ii validation).

**The headline regression guard (no-op-when-all-active).** B1 touches **no shared device kernel** and only adds
inert fields to `FilamentStore`, so every prior harness is byte-unchanged ⇒ bit-identical. Re-ran and **all
PASS**: `node, minifil, dimer, dimerglide, miniglide, stroke, xbridge, motor, contractile, xlink`, plus the
foundational **FDT** (D_par −2.52% etc., within 5%).

## Files
- **New:** `FilamentBirthSystem` (freeFlags/freeScatter/allocate; csrScan reused verbatim), `FilamentBirthHarness`,
  `run_filbirth.sh`.
- **Edited (purely additive, defaults inert):** `FilamentStore` — `filState` sentinel + scan-rank allocator
  scratch + birth-request payload + `markFree`/`setBirthRequest`/`setBirthRequestCount`/`setBirthParams`. The
  `FilamentStore(int n)` constructor delegates to `FilamentStore(int n, int reqCap)`; existing callers unchanged.
- **Untouched:** every shared device kernel; `BoA-v1ref` byte-clean; production a no-op (all-active).

## For the planner — B2 (the nucleation emitter)
B2 replaces ONLY the synthetic birth driver with the node's nucleation: a per-node emitter (rate `kNodeNuc·dt`,
born pre-bonded at the node, seed length ≈10.8 nm) fills the SAME `acceptFlag`/`reqCoord/reqUVec/reqYVec` request
arrays; the scan-rank allocator rides underneath **unchanged**. Two items to settle at B2 build:
1. **Re-confirm the nucleation specifics** (rate, pre-bond geometry, formin release) against a fresh
   `ProteinNode.java` snapshot (the recon settledness gate — that file still has physics churn).
2. **Add the publish-time `filState` guard** (the one deferred shared-kernel touch) so a FREE slot is kept out
   of the broad-phase in a live binding scene; itself a no-op-when-all-active.
Growth/polymerization remains a **second** new capability (deferred); seam #2 (actin pool behind one accessor)
is flagged for then.
