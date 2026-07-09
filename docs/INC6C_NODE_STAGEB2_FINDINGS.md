# Increment 6c Stage B2 — the node nucleation-function (formin actin nucleation) — findings

**Date:** 2026-06-18. **Status:** DONE, all 8 gates PASS GPU+CPU, all prior harnesses bit-identical. Committed on green.

## What B2 is
The node **nucleation-function** — the protein node's implicit-formin actin nucleation, attached via **seam #1**
(a separable behavior, additive over Stage A). It is the **first dynamic actin CREATION in SoftBox** and
completes the node (motor-bundle + nucleation). Built **from jba's behavioral spec**, not by porting v1 code;
v1 (`BoA-v1ref`) was the reference for **clean specifics only** (rates, seed size, the tether law), with v1
drift flagged rather than copied.

Per node, per step: **(1)** birth a fixed-length seed (reusing B1's runtime-birth allocator, **unchanged**),
**(2)** hold it to the node with an **elastic fracMove tether** (node-center ↔ seed-end), **(3)** **dissolve**
the node↔seed bond at a constant rate → the seed becomes a free filament, **(4)** the born seed is
**Brownian-damped**. The actin pool is depleted through the **seam-#2** accessor.

## v1 clean specifics (reproduced) + flagged drift
| quantity | v1 value | B2 |
|---|---|---|
| nucleation rate `kNodeNuc` | 10 /node·s (`Env.java:895`) | reproduced; per-node `P=kNodeNuc·dt`, [actin]-independent |
| seed size `actinSeed` | 3 monomers → ≈10.8 nm (`Env:539`) | reproduced (fixed-length; growth deferred) |
| tether law | `fracMove·1e-6·strain/(dt·(1/fil.bTransGam.x + 1/node.bTransGam.x))`, `fracMove=0.5`, attach at node **center** (`FilSegment.addNodeForces:2389-2444`) | reproduced verbatim (the SAME spring as `NodeSystem.tether` / the minifilament tether) |
| dissolution rate `nodeTetherDetachRate` | 0.001 /s (`Env:602`) | reproduced as a **constant-rate** detach `P=rate·dt` |

**Flagged v1 drift / divergence (built jba's spec, did NOT copy v1):**
1. **Dissolution + max-strain triggers are INACTIVE by default in v1** (`Parameter(...,false)`, `Env:600,603`).
   jba's spec wants dissolution ON, so B2 **enables** the constant-rate detach (configurable; v1's 0.001/s value).
2. **v1's node tether release is a CONSTANT rate, NOT a Bell/log-stretch model** — the recon §2a's "Bell/
   log-stretch" wording was imprecise. B2 reproduces the actual v1 behavior (constant `rate·dt`). (The optional
   `maxNodeTetherStrainDist` strain-trigger is left off, matching v1's default; available if jba wants it.)
3. **v1 has an optional `nodeTorqSpring` filament-alignment torque** (1e-18 N/rad, active, `Env:596`) keeping the
   seed aligned to its nucleation direction. jba's spec is a **positional elastic tether only**, so B2 **omits**
   the alignment torque. Flagged for jba — addable later if radial alignment is wanted (the seed stays radially
   stable via the Brownian damping instead).
4. **`forminsPerNode` default 0** (`Env:433`) = nucleation OFF in v1 production; B2 keeps default-off (the whole
   function is a no-op at `forminsPerNode=0`) and enables it in the nucleation harness.

## The thermal-damping-as-dt-compensation principle (jba — generalizes to the membrane nucleation)
A short fixed-length seed at full thermal freedom **flails** (gate 8: undamped RMS wander 1.30e-3 µm vs damped
4.35e-5 µm). Biologically the formin **tightly holds** the seed — a *stiff* constraint, numerically
**inexpressible at the large production dt** (`1e-5`–`1e-4` s): stiff + large dt → instability, the **same
fracMove dt-stiffness family** the whole force model lives in (a fine-dt model would express the tight hold
directly and need no help). B2's two-part stand-in: the **elastic tether** (#2) gives a soft, dt-compatible
*positional* hold; **damping the seed's Brownian forces** (~30×, configurable) compensates for that softness ⇒
together they approximate the formin's tight hold at the production dt. This is a **legitimate dt-compensating
approximation**, deliberately **non-FDT for the seed only** (existing filaments keep scale 1.0). It does NOT
cause node-coupling stiffness (the tether handles coupling). The principle **generalizes to the deferred
membrane formin nucleation** (jba's in-development damped-filament work).

## Architecture (reuse + the one shared-kernel touch)
- **Seed birth = B1's allocator, UNCHANGED.** The emitter fills B1's request arrays
  (`acceptFlag`/`reqCoord`/`reqUVec`/`reqYVec`); `FilamentBirthSystem` (freeFlags/csrScan/freeScatter/csrScan/
  allocate) rides underneath byte-unchanged. `reqCap = nNodes` (a node nucleates ≤1 seed/step ⇒ request r ↔
  node r). The born seed's damped Brownian scale is written by B1's `allocate` from `birthParams` — **no
  Brownian-system edit**.
- **Lifecycle fields (orthogonal):** `filState[s]` (B1: slot alive?) and `seedNode[s]` (B2: if alive, tethered
  to which node? `<0` = a free, untethered filament). Dissolution sets `seedNode=-1` but keeps `filState`
  ACTIVE ⇒ the seed becomes a **free filament** (it does NOT free the slot; turnover deferred — freed seeds
  persist).
- **`NodeNucleationSystem`** (all dual-runner, wang-hash RNG, no atomics): `countBoundFil` (the per-node cap
  count), `emit` (stochastic per-node request + random radial seed pose), `tagSeeds` (post-allocate node bond,
  mirroring the allocator's rank→slot map), `seedTether` (the fracMove spring, node-specific placement:
  filament end1 ↔ node center; node is a fixed anchor ⇒ no node reaction), `dissolve` (constant-rate wang-hash
  detach).
- **The ONE shared-kernel touch (B1 flagged it):** a **guarded `publishToBodyView` overload** — a FREE slot
  (`filState<0`) publishes with `ownerStore = STORE_NONE` so the narrow-phase (which filters by `ownerStore`)
  never binds a not-yet-born filament. The 8-arg version is **byte-unchanged**; the 9-arg is **bit-identical to
  it when every slot is ACTIVE** (no-op-when-all-active). New `SpatialBodyView.STORE_NONE = -1`.
- **Seam #2 — the actin pool behind one accessor** (`ActinPool`): a depletable scalar (15 µM, v1
  `microMolarChangePerMonomer` from a chamber volume); the emitter reads/depletes via `available()`/`take()`
  only. Scalar now → depletable counter / diffusing field later, without rewiring nucleation.

## Validation (`run_nodenuc.sh`, both runners — all PASS)
1. **Rate** — 351 births over 64 nodes × 50 000 steps; empirical `P=1.097e-4` vs `kNodeNuc·dt=1.0e-4` (9.7%,
   within Poisson SEM ~5.6% on ~350 events).
2. **Elastic tether** — the displaced seed's force matches the v1 double-precision fracMove reference to
   `rel 2.1e-8` (bit-for-decision), points toward the node, and relaxes (strain 4.0e-3 → 1.9e-10 µm, bounded).
3. **Dissolution** — pre-tethered 4000 seeds, constant-rate detach: empirical `pDetach=2.0015e-2` vs
   `rate·dt=2.0e-2` (**0.1%**, 4000 events); freed seeds stay ACTIVE free filaments (`#active=4000`,
   `detached = N−tethered`). *(Tested at an elevated 2000/s; v1's production 0.001/s ⇒ `pDetach=1e-8` is
   unobservably slow at `dt=1e-5` — validated by the `rate·dt` formula; flag for jba.)*
4. **Actin pool (seam #2)** — births deplete `conc` by exactly `births·actinSeed·µMPerMonomer` (err 0); the
   `available()` gate stops emission at the budget (5 seeds) and leaves the pool dry.
5. **No-op-when-off** — `forminsPerNode=0` ⇒ 0 births, `seedNode` all −1, `max|Δcoord|=0` after 5000 steps
   (the nucleation-function is additive over the Stage-A node; seam #1 intact).
6. **CPU≡GPU** — full pipeline (emit + allocator + tag + tether + dissolve + integrate), Brownian off:
   `seedNode`/`filState` **0 mismatches** (the lifecycle is bit-identical — wang-hash + integer allocator);
   pose `max|Δ|=4.66e-10 µm` (float32 last-bit, < 5e-5).
8. **Seed Brownian damping** — RMS seed wander damped (×1/30) 4.35e-5 µm vs undamped (×1) 1.30e-3 µm (visibly
   stabilized); existing filaments untouched (scale 1.0; no Brownian-system edit).
- **P (publish guard)** — FREE slots → `STORE_NONE`, ACTIVE → `STORE_FILAMENT`; the 9-arg ≡ the 8-arg when all
  active (no-op-when-all-active).

**Regression (no-op-when-off / additive):** `filbirth (B1), node (Stage A), grid (broad-phase, the publish
caller), motor, minifil, dimerglide, miniglide, contractile` all re-run PASS — bit-identical (the 8-arg publish
is byte-unchanged; B2 added only new files + additive `Constants`/`FilamentStore` members). `BoA-v1ref`
byte-clean; production a no-op (`forminsPerNode=0`).

## Files
- **New:** `ActinPool` (seam #2), `NodeNucleationStore`, `NodeNucleationSystem`, `NodeNucleationHarness`,
  `run_nodenuc.sh`.
- **Edited (additive):** `SpatialBodyView` (+`STORE_NONE`), `FilamentStore` (+the guarded `publishToBodyView`
  overload), `Constants` (+nucleation/pool constants). No existing kernel/method modified.

## Status — the node is complete; the migration is at the edge
The protein node now nucleates actin (motor-bundle + nucleation). **The last entity port lands.** Deferred / at
the migration edge (waiting on v1 / membrane work): **growth/polymerization** (monomer-vs-segment granularity);
**filament death/turnover** (freed seeds persist; if a long run accumulates too many, that bounds run length —
flag); the **membrane formin nucleation** (the damped-filament work jba is developing — the damping principle
generalizes to it); branched networks; the dynamic cortex; the optional `nodeTorqSpring` alignment (flag for
jba). **Post-node horizon:** a fixed-anchor minimal contractile ring (a ring of nucleating nodes + the
contractile-assay tension read — all primitives now exist).
