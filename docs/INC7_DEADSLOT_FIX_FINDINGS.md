# Increment 7 — DEAD-SLOT REUSE FIX: nucleation fully initializes a recycled slot — FINDINGS

**Status: DONE (2026-06-21). 5 gates PASS GPU + CPU; the first TURNOVER + NUCLEATION coexistence (the ring
precondition) validated; conservation EXACT through the recycle; CPU≡GPU bit-identical lifecycle; turnover-only /
nucleation-only regressions unchanged.** A correctness fix (committed, not a flagged experiment).
Run: `./run_deadslot.sh [-cpu]`.

## The bug (recap, flagged in INC7_STAGE1_FINDINGS.md §"Reused-slot monomerCount")
Through inc 6 the `FilamentStore` was static, so B1/B2 nucleation rode a **STATIC invariant**: every slot is
pre-initialised at scene build to a fixed seed (`monomerCount=actinSeed`, `segLength`, drag) and `markFree`'d, so
the nucleation `allocate` (FilamentBirthSystem) need only write pose + Brownian-scale + `filState`. Inc 7 turnover
broke that invariant: `DepolySystem.applyDeath` **frees + wipes** a dying slot (`monomerCount→0`, Brownian 0,
neighbors −1, `seedNode −1`) and depoly shrank its `segLength`; aging froze the corpse's `nucFrac` at a mostly-ADP
composition. A nucleation-**reused dead slot** therefore births a filament with:
- `monomerCount = 0` — a **zero-length, conservation-violating phantom** (the pool was debited `actinSeed` for the
  birth, but the filament carries 0 ⇒ monomers vanish), and
- a **stale ADP `nucFrac`** — not the fresh, unhydrolyzed ATP a real seed starts with (and a high `f_ADP` would
  drive the nucleotide-dependent depoly rate far too fast).

Split-reuse was always fine (`GrowthSystem.splitWire` sets the child's `monomerCount`; `AgingSystem.splitInheritNuc`
sets its `nucFrac`); **only nucleation assumed the pre-init value.** Not exercised before inc 7 (no harness ran
turnover + nucleation together) — this is the FIRST coexistence and the ring precondition.

## The audit — exactly which newborn fields a recycled slot leaves stale
Per the discipline ("nucleation FULLY initializes the newborn, never inherits from a corpse"), every field a
newborn ACTIVE seed depends on was audited against what a dead slot leaves / who sets it:

| field | dead-slot value | who sets it for a nucleated seed | action |
|---|---|---|---|
| `coord` / `uVec` / `yVec` | corpse pose | `FilamentBirthSystem.allocate` (request pose) | ✓ already set |
| `brownTransScale` / `brownRotScale` | 0 (markFree) | `allocate` (birthParams) | ✓ already set |
| `filState` | FREE | `allocate` (→ ACTIVE) | ✓ already set |
| `seedNode` | −1 (applyDeath) | `NodeNucleationSystem.tagSeeds` | ✓ already set |
| `end1/end2` neighbors | −1 (applyDeath; build inits −1) | — (a born seed IS a free rod) | ✓ correct as −1 |
| `forceSum` / `torqueSum` / `extForce` | n/a | zeroed every step (`zeroAccumulators`) / init 0 | ✓ not stale-dependent |
| `zVec` | stale | `DerivedGeometrySystem.derive` (from u×y, every step) | ✓ re-derived |
| **`monomerCount`** | **0** (applyDeath wipe) | **nothing** | **← FIX (set actinSeed)** |
| **`nucFrac`** | **stale ADP** (frozen corpse) | **nothing** | **← FIX (reset fresh ATP)** |
| `segLength` | corpse-shrunk | `recomputeDrag` re-derives from `monomerCount` each cadence | **← FIX sets it too** (see below) |
| drag (`bTransGam`/…) | corpse | `GrowthSystem.recomputeDrag` from `monomerCount` each cadence | ✓ recompute (clamp-correct) |

**Verdict: exactly TWO stale-inherited fields the newborn depends on — `monomerCount` and `nucFrac`** — plus the
geometry-derived `segLength`. It is NOT broader than that ⇒ **no unified `initNewbornSlot` routine is warranted**;
two small scattered sets (matching the split precedent) suffice. **Drag is left to `recomputeDrag`** (which runs
every turnover cadence over all slots, re-deriving `segLength`+drag from `monomerCount`): an at-end seed and the
at-end corpse it reused both clamp to the SAME std-segment drag (FilSegment:409-419), so even the pre-recompute
transient is std-correct — exactly as `splitWire` leaves drag to `recomputeDrag`. **`segLength` IS set explicitly**
(unlike split) because the nucleation seed has an extra same-cadence consumer a split child does not:
`NodeNucleationSystem.seedTether` reads `segLength` for the tethered-end position BEFORE `recomputeDrag` runs.

## The fix (additive; mirrors the split precedent `splitWire` + `splitInheritNuc`)
Two new post-allocate kernels, each running the SAME rank→slot iteration as `tagSeeds` (per accepted nucleation
request; distinct ranks → distinct slots ⇒ **one writer per born slot, race-free, no atomics/KernelContext**):
- **`NodeNucleationSystem.initNewborn`** (lifecycle side) — `monomerCount = actinSeed`,
  `segLength = (actinSeed+1)·actinMonoRadius`. Params from the new `NodeNucleationStore.seedParams`
  (`[0]=actinSeedMon [1]=seedLenUm`, set in `setNucParams`).
- **`AgingSystem.nucleateFreshAtp`** (aging side) — `nucFrac = (1,0,0)` (pure ATP, v1 `Monomer:62`). The aging-side
  analog of `initNewborn`, mirroring how `splitInheritNuc` is the aging analog of `splitWire`; it reads the SAME
  `rankOffsets`/`freeList` arrays, so **`NodeNucleationSystem` gains no `nucFrac` coupling** (aging stays additive —
  touches only `nucFrac`). (Contrast `splitInheritNuc`, which COPIES the parent composition — a split child
  inherits; a nucleated seed is born fresh.)

**No existing kernel signature changed.** `tagSeeds`/`allocate`/`splitWire`/`splitInheritNuc` are byte-unchanged;
`NodeNucleationStore` gains one additive `seedParams` field. The two fix kernels fire ONLY on a nucleation birth
⇒ they are inert (never called) in existing nucleation-only harnesses (which never recycle a slot ⇒ are correct on
the pre-init), and inert in turnover-only paths (no nucleation) ⇒ those stay byte-unchanged.

## The coexistence assay (`DeadSlotReuseHarness`, `run_deadslot.sh`)
A single fixed-anchor node nucleates seeds (forced churn: `pNuc=1`/cadence, `forminsPerNode=filCap=8`) WHILE
aging + nucleotide-dependent depoly (`depolyProxy`) + death recycle those very slots — the FIRST time turnover
(frees slots) and nucleation (claims slots) coexist. TEST rates (fast death + fast aging) so a corpse is clearly
ADP-rich (the `nucFrac` reset is observable) and reuse happens on a short horizon. **Reuse detection reads the
kernel OUTPUTS** (`deathFlag` + the allocator `rankOffsets`/`freeList`/`freeOffsets`), which catches the
**SAME-cadence death→free-list→allocate** reuse (the 5c-i pattern) that a `filState` boundary-snapshot misses.

## Gates (all PASS, GPU + CPU)
1. **newborn correctness (FIX on)** — 6000 cadences, **2250 dead-slot reuses**, **0 bad newborns**, **0 ACTIVE
   slots with monomerCount 0**. Every reused newborn is a correct fresh seed (`monomerCount=actinSeed`,
   `segLength=seedLen`, `nucFrac=(1,0,0)`, `seedNode≥0`, neighbors −1, ACTIVE).
2. **conservation EXACT** — the integer ledger `Σ monomerCount(ACTIVE) == taken − returned` holds **every sampled
   cadence** and finally (23 == 6774 − 6751) — through nucleation TAKING, depoly RETURNING, and the slot recycle.
3. **fix-OFF control (the bug exposed)** — WITHOUT the fix: **5996 dead-slot reuses**, conservation **BROKEN by
   exactly 17988 = actinSeed·5996** (every reused birth debits 3 from the pool but adds 0 to filaments), plus
   ACTIVE zero-length (`monomerCount 0`) + stale-ADP (`f_ADP>0`) newborns. Documents that the fix closes a real,
   quantified bug.
4. **CPU≡GPU** — 4000-cadence coexistence: lifecycle **bit-identical** (filState/monomerCount/seedNode mismatches
   0/0/0), `max|ΔnucFrac| = 8.94e-8` (float32 last-bit), and reuse-count / active / Σmonomer / pool taken/returned
   all EQUAL (1499/1499, 8/8, 24/24, 4521·4497 both).
5. **regression** — (a) turnover-only (no nucleation): fix-on ≡ fix-off **bit-identical** (the fix kernels never
   fire). (b) nucleation-only (no turnover): 8 births, **0 reuses** (no slot ever frees), 0 bad newborns,
   conservation exact.

Plus, externally re-run **PASS**: `run_nodenuc` (GPU+CPU), `run_aging` (GPU+CPU), `run_depoly`, `run_growth`,
`run_treadmill`, `run_severing` — the nucleation / turnover / aging paths the change touches.

## Race-freedom / lock-step / discipline
`initNewborn` + `nucleateFreshAtp` are per-accepted-request self-writes to distinct slots (the validated
`tagSeeds`/`splitInheritNuc` rank→slot mapping) — no atomics, no KernelContext, both runners, `localWork` from the
existing scheme. Default-off discipline preserved (no production assay runs `run_deadslot`; the fix is forward
infrastructure for the contractile ring, validated here at the coexistence). `BoA-v1ref` byte-clean; production
untouched. New files only (`DeadSlotReuseHarness`, `run_deadslot.sh`) + two new kernels + one additive
`NodeNucleationStore.seedParams` field.

## TL;DR
The flagged dead-slot reuse hazard is **closed**: a recycled (previously-dead) slot reused by nucleation now
births a proper **fresh-ATP seed of length `actinSeed`** (the two stale fields — `monomerCount` wiped to 0,
`nucFrac` frozen ADP — reset explicitly by `NodeNucleationSystem.initNewborn` + `AgingSystem.nucleateFreshAtp`,
mirroring the split `splitWire`+`splitInheritNuc` precedent; `segLength` set for the same-cadence tether, drag via
`recomputeDrag`). Validated by the FIRST turnover + nucleation **coexistence** assay: 2250 reuses all correct,
**conservation EXACT** through the recycle, **CPU≡GPU bit-identical**, the fix-OFF control reproduces the exact
17988-monomer deficit, turnover-only / nucleation-only regressions unchanged. The ring precondition is met.
