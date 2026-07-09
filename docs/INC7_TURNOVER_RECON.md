# Increment 7 — RECON: actin turnover (hydrolysis + cofilin severing + depolymerization + filament death)

**Purpose.** A read-only **behavior** survey of v1's actin-turnover layer to let the planner scope a minimal
SoftBox (v2) build: the deferred capability that lets filaments **shrink, age, sever, and die** — the direct
fix for Test B′'s monotonic-growth **overrun** and the prerequisite for **sustained contraction** in the ring
(treadmilling steady state). Build **from behavior**, not by porting code; **flag v1 drift, don't copy it**.

**Survey only — no code, no runs, no edits.** `BoA-v1ref` read byte-clean (read-only oracle). All `file:line`
refs verified 2026-06-19 against `~/Code/BoA-v1ref/boxOfActin/`. v2 = SoftBox (`softbox/`). Companion to
`INC6C_POLYMERIZATION_RECON.md` (growth, the forward half) and `INC6C_CONVENTION_SWAP_FINDINGS.md`
(**barbed = end2 settled** ⇒ pointed = end1, where depolymerization happens).

---

## Headline findings for the planner

1. **THE REPRESENTATION FORK IS RESOLVED — and the news is good: v2 does NOT need a per-monomer SoA.** v1
   DOES carry a per-monomer object (`Monomer.java`, a `frontMon/backMon` linked list with a 3-state
   `nucleotideState` ATP→ADP-Pi→ADP — the polymerization recon's "no per-monomer object" referred to the
   `noMonomersSimd` **bypass** path). BUT v1's turnover **consumes that state only coarsely**: (a) the depoly
   rate reads **only the single terminal monomer's** `isADP()` (`FilSegment.java:1016,1029`); (b) the en-masse
   dissolve reads a **per-segment aggregate ratio** `cofilinCt/monomerCt` (`FilSegment.java:3742`); and (c)
   **every inc-6 assay ran `noMonomersSimd=true`** (no monomers at all — `BoxOfActin.java:2893`). ⇒ a **single
   per-segment ADP-fraction / age scalar** (advanced by the hydrolysis cascade) reproduces every load-bearing
   turnover decision. jba's hint is confirmed by the code: the removal threshold classifies a **segment** as
   old/ADP (`notADPFraction()`, `FilSegment.java:3730`), implying per-segment suffices.

2. **SEVERING NEEDS NO MID-SEGMENT CUT AND NO NEW SLOT — the biggest bookkeeping worry evaporates.** v1's
   cofilin "sever" (`checkCofilinDissolve`, `FilSegment.java:3741`) **dissolves a WHOLE segment en masse**
   (returns all its monomers to the pool, removes the segment), NOT an arbitrary mid-filament cut. Because a v1
   filament is **already a chain of ≤64-monomer segments**, dissolving one interior segment **leaves the two
   neighbor sub-chains as valid, already-separate filaments** — the only rewire is breaking the dissolved
   segment's two end-links. This is **simpler than split@64** (split *adds + inserts* a slot; dissolve only
   *removes + unlinks*). The recon-prompt worry ("a rewire harder than split@64 … slot allocation, two valid
   chains") **does not apply** — segment granularity is the simplifier jba flagged.

3. **SLOT RECYCLING IS (MOSTLY) ALREADY BUILT.** The recon prompt's "allocate-only, slots never freed, a
   changed invariant" is **partly stale**: `FilamentStore.markFree()` exists and the scan-rank free-list
   reclaims any `filState < 0` slot (`FilamentBirthSystem.freeFlags/freeScatter`, `FilamentStore.java:97-126`).
   Death = **call `markFree(slot)` + return monomers to the pool** — a self-write of the sentinel (like
   crosslinker `linkState<0`, motor `boundSeg<0`), race-free, **no swap-compaction**. v2's slot-stable Design A
   **structurally avoids** v1's `packRange` ClassCast desync hazard (the 2026-06-11 v1 turnover-residency bug —
   `JOURNAL` v1ref:791) — a genuine v2 advantage. **Genuinely new = (a) `ActinPool.put()`/restore (the pool is
   take-only) + a conservation gate; (b) the per-segment ADP-fraction proxy; (c) the pointed-end shrink (the
   reverse of growth's end1 lengthen).**

4. **v1's turnover is SETTLED, not churning.** Hydrolysis / depoly / cofilin-sever / split were validated +
   merged in v1's **2026-06-11 GPU residency-under-turnover campaign** ("CAMPAIGN CLOSER", turnover-stress
   fixtures `boa10-64Seg-dyn`/`-turnoverstress`; `JOURNAL` v1ref:791,793). The **membrane formin nucleation is
   the churning one and is OUT** (per the polymerization recon). Portable.

5. **MINIMAL CUT = pointed-end depoly + filament death + pool-return + slot-recycle** (the reverse-of-growth
   foundation), with the **en-masse whole-segment dissolve as the faithful, lighter disposal** (§3b: threshold a
   tunable seam — default trims at segment granularity; settable to ~3 monomers for the disintegrating cloud).
   Nucleotide-dependent rates + treadmilling + the full per-monomer path are **later, dependency-ordered
   stages** (§7).

---

## 1. Mechanism, with `file:line` (all in `BoxOfActin.java` / `FilSegment.java` / `Monomer.java` / `Bug.java` / `Env.java`)

### 1a. The driver + per-step ordering
- **Driver:** `Thing.step()→moveThing()→biochemStep()→resetCounters()` per Thing (`Thing.java:358-375`, each
  gated `if (!removeMe)`); **then** the cleanup phase sweeps the dead (`BoxOfActin.java:1949-1967`,
  `Thing.removeDeadThings()` at `:1963`). So **forces/move FIRST, then biochem (poly/depoly/age/sever/split),
  then the death sweep.**
- **Cadence:** `biochemStep()` (`FilSegment.java:540`) fires once per **`biochemCheckInt`** steps
  (`= biochemDeltaT/deltaT`, `Env.java:1134`; biochem clock `biochemDeltaT = 1e-3 s`, `Env.java:111`). On `-gpu`
  a **global cadence** (`biochemGlobalCadence`/`biochemFiresThisStep`, `FilSegment.java:551`) phase-aligns all
  segments to the same step (so the relative-write `incCoord`/`setFirstHalf` land on a fresh-pose step) — v2
  already uses this step-gated pattern (B2/growth).
- **Master gate:** `if (!noMonomersSimd && biochemFires)` (`FilSegment.java:554`) — `noMonomersSimd=true` skips
  ALL turnover (the inc-6 assays' default; `Env.java:610`, set true in CPU contractile/static configs).

### 1b. biochemStep body — the exact turnover order (`FilSegment.java:554-598`)
1. `hydrolizeInFilaments()` (`:555`, def `:3710`) — walk minus→plus monomers, each rolls **hydrolysis**
   (ATP→ADP-Pi at `kHydrolysis=0.3/s`) / **dissociation** (ADP-Pi→ADP at `kDissociation=1/s`) / **cofilin
   binding** (ADP monomers only) / tropomyosin (`Monomer.checkHydrolysisCofilinTropo`, `:166`); accumulates
   per-segment `cofilinCt`.
2. `checkCofilinDissolve()` (`:556`, def `:3741`) — **the en-masse dissolve** (§3b below).
3. `if (!filAtEnd1) end1BiochemSim()` (`:558`, def `:1041`) — **pointed-end** poly + depoly.
4. `if (!filAtEnd2) end2BiochemSim()` (`:559`, def `:1088`) — **barbed-end** formin-release/cap/branch + poly +
   depoly. (Only **true filament ends** turn over; interior segments only hydrolyze/dissolve.)
5. `if (lengthChanged) calculateProperties()+initialize()` (`:564`) — recompute drag/pose on any length change.
6. `if (monomerCt >= 2*stdSegLength) splitSegment(this)` (`:584`) — the growth split (already ported).

### 1c. Hydrolysis / nucleotide aging (`Monomer.java`)
- States `ATPstate=10 / ADPPistate=11 / ADPstate=12` (`:22-24`); a fresh polymerized monomer starts **ATP**
  (`:62`). Aging is **per-monomer Poisson** each biochem step: `hydrolize` ATP→ADP-Pi at `kHydrolysis·dt`
  (`:182`), `dissociate` ADP-Pi→ADP at `kDissociation·dt` (`:189`). So a filament ages **barbed(new,ATP) →
  pointed(old,ADP)**; the pointed end is the oldest/most-ADP, which is why it depolymerizes fastest.
- **Aggregate proxy already exists:** `notADPFraction()` (`:3730`) = fraction NOT-yet-ADP (drives the viewer's
  age coloring) — the natural **per-segment** age scalar.

### 1d. Depolymerization / treadmilling (`FilSegment.java`)
- `removeMonomerSim(offRate, endMon)` (`:2786`): `P = offRate·biochemDeltaT`; on fire `monomerCt--`,
  `length-=halfmono`, `theBox.putMonomer(1)` (**pool restore**), `lengthChanged=true`.
- **Pointed end (end1):** `end1BiochemSim` depolymerizes at `getDepolyRateEnd1()` from `minusMon` then
  `incCoord(+halfmono/2,uVec)` (`:1073-1079`). **Barbed end (end2):** `end2BiochemSim` at `getDepolyRateEnd2()`
  from `plusMon`, `incCoord(-halfmono/2,uVec)` (`:1122-1127`); skipped if `end2Capped`.
- **Floor → death:** both ends guard `if (monomerCt >= actinSeed) {…depoly…} else cleanup(this,true,true)`
  (`:1072/1080`, `:1120/1129`). A segment depolymerizing below **`actinSeed=3`** monomers **dies**.
- **Rates are nucleotide- and end-asymmetric** (`getDepolyRateEnd1/2`, `:1012/1022`), reading **only the
  terminal monomer's** `isADP()`:

  | end | ATP-off | ADP-off | with formin (barbed) |
  |---|---|---|---|
  | pointed (end1) `kATPOff1/kADPOff1` | 0.8 | 2.7 | — |
  | barbed (end2) `kATPOff2/kADPOff2` | 1.4 | 7.2 | 1.4 / 7.2 (no suppression) |

  `sideBondsStabilize` (`Env.java:627`, default 1.0 ⇒ `pow(1,n)=1` = inert) optionally slows depoly of
  crosslinked segments (`linkCt>0`). **Treadmilling is EMERGENT** (fast barbed-on / slow pointed-on +
  ADP-fast-off) — no `treadmill` flag. `joinSegments` (segment re-merge on shrink) is **commented out**
  (`:600-604`) — a shrinking filament loses monomers but never re-merges segments.

### 1e. Severing / cofilin — **whole-segment en-masse dissolve** (`FilSegment.java:3741`) — jba's §3b hack
- `checkCofilinDissolve()`: `curCofilinRatio = cofilinCt/monomerCt`; **if `> cofilinRatio` → `cleanup(this,
  true, isACut=true)` + `removeMe=true`** — the **entire segment** dissolves at once.
- Cofilin decorates only **ADP** monomers (`cofilinBinding` reached only in the ADP case of
  `checkHydrolysisCofilinTropo`, `Monomer.java:169-170`), at `cofilinConc·cofilinRate·dt`
  (`cofilinRate=0.1, cofilinConc=3.0`); bundled (crosslinked) filaments **resist** cofilin
  (`/(bundleStableFactor·linkCt)`, `:243`; `bundleStableFactor=2.0`). So `cofilinCt/monomerCt` is effectively an
  **ADP-aged fraction** ⇒ the dissolve fires on **old, ADP-rich, un-bundled** segments — exactly "remove the
  old-ADP segment en masse."
- **Default `cofilinRatio = 1.0` (`Env.java:754`) ⇒ dissolve is effectively OFF** (a ratio can't exceed 1).
  This IS jba's tunable seam: a threshold **< 1** enables en-masse disposal; the smaller it is set, the more
  aggressively whole segments are trimmed. (The "settable to ~3 monomers / disintegrating cloud" mode = instead
  rely on the §1d monomer-by-monomer depoly-to-death, which fragments down to `actinSeed` — the fine-grained
  path.)
- **Why this is the lighter path:** the smallest disposable unit is the **segment** (32–64 monomers), not the
  monomer ⇒ **no cloud of ever-smaller fragments to track.**

### 1f. Filament death / cleanup (`FilSegment.java:2861` `cleanup(cleanF, swapOut, isACut)`)
Triggered by (i) depoly below `actinSeed` (`:1081,1130`, `isACut=true`) or (ii) cofilin dissolve (`:3744`,
`isACut=true`). It:
1. **Returns all monomers to the pool:** `theBox.putMonomer(cleanF.monomerCt)` (`:2862`) — the **en-masse
   conservation point**.
2. `filCt--` only if unattached (`:2863`); releases a node formin (`end?Node.filamentOff()`, `:2869/2876`) —
   with `isACut=true` the formin is **released**, not transferred (the `!isACut` join-transfer branch is
   skipped).
3. Detaches Arp2/3 branches (`:2880`).
4. **Breaks both end links:** `breakAtEnd1()/breakAtEnd2()` (`:2922-2923`, defs `:1428/1439`) — clears the
   dissolved segment's links AND the **neighbors' back-pointers** (`removeEnd1/2Links`). This is the entire
   sever rewire: an interior segment's death leaves its two neighbor sub-chains as two valid filaments.
5. Depolymerizes the monomer list (`:2925-2933`).
6. `if (swapOut) removeFilSegment(cleanF)` (`:2935`, def `:3530`) — v1's **swap-compaction** slot recycle
   (swap the array tail into the hole; `removeMe=true`). **v2 will NOT swap-compact** — it `markFree(slot)`
   instead (slot-stable; see §3).

### 1g. Pool coupling (seam #2) — conservative global scalar (`Bug.java`)
- The pool is the global scalar `Env.actinConc` (µM). `takeMonomer(n) → actinConc -= n·µMperMon`
  (`Bug.java:268`); `putMonomer(n) → actinConc += n·µMperMon` (`:276`); `getMonomerConc()=actinConc`
  (`:260`). `µMperMon = microMolarChangePerMonomer` (the box-volume quantum, `Bug.java:110`). **Genuinely
  conservative**: every grow takes, every depoly/dissolve/death puts.

---

## 2. The representation fork — RESOLVED: per-segment ADP-fraction proxy, NOT a per-monomer SoA

| consumer of nucleotide state | what it actually reads | v2 proxy that suffices |
|---|---|---|
| depoly rate (`getDepolyRateEnd1/2`) | the **single terminal monomer's** `isADP()` | per-segment age ⇒ end-monomer ADP **probability** (or a terminal-age scalar) |
| en-masse dissolve (`checkCofilinDissolve`) | per-segment **ratio** `cofilinCt/monomerCt` (≈ ADP fraction) | per-segment **ADP-fraction** scalar vs a threshold |
| the inc-6 assays | nothing (`noMonomersSimd=true`) | nothing |

**Resolution.** Add **one per-segment scalar** — an **ADP-fraction** (or mean monomer age in seconds) — advanced
each biochem step by the hydrolysis/dissociation cascade (a deterministic AR-style aging, e.g.
`adpFrac += rate·dt·(1−adpFrac)` with newly-added barbed monomers diluting it toward ATP, depoly removing aged
end mass). This is a **new v2 representation** (v1 has no per-segment proxy — `noMonomersSimd=true` simply skips
turnover), faithful to v1's per-monomer aging **in aggregate**. Per the **§8 component-vs-emergent posture**: the
**rate laws + thresholds** are component-port faithful (matched bit-for-decision against v1's formulas), but the
**per-monomer microstate is NOT bit-reproduced** — adjudicate the aged distribution against v1's **aggregate**
ADP-fraction / length statistics and first-principles (treadmilling steady state), not a per-monomer match. The
full per-monomer `Monomer` list is the **optional high-fidelity Stage 4**, only if an assay needs the microstate
(cofilin spatial patterning, tropomyosin) — not the default.

---

## 3. The bookkeeping map (jba's flag) — precise

### 3a. Filament death + slot RECYCLING — mostly already built
- **Already present:** `FilamentStore.markFree(slot)` + the scan-rank free-list (`FilamentBirthSystem.
  freeFlags/freeScatter`, reclaims any `filState<0`). Death = **`markFree` + pool `put`** — a **self-write of
  the sentinel** (one writer/slot, race-free, no atomics; same shape as crosslinker `linkState<0`).
- **Changed invariant (the real new bit):** until now `markFree` was **never called in a running loop** (B2
  dissolution kept `filState` ACTIVE). Turnover is the **first dynamic free**, so the free-list — built every
  formation/growth cadence — now genuinely churns. The **same-step ordering** must be: **death (markFree) →
  rebuild free-list → allocate (nucleation/growth)** so a slot freed this step is reclaimable this step (the
  5c-i `death→free-list→allocate` order, already proven).
- **v2 advantage:** **no swap-compaction.** v1's `removeFilSegment` swap caused the `packRange` MyoMiniFilament→
  FilSegment ClassCast desync (`JOURNAL` v1ref:791). v2's slot-stable Design A (segments never move) makes that
  class of bug **structurally impossible**.

### 3b. Pool return + CONSERVATION — the new gate
- **New code:** `ActinPool.put(n)`/`restore` (today only `take`/`available`/`conc`, `GrowthStore.java:98`).
- **Invariant (a checkable gate):** `pool_monomers + Σ monomerCount(active segments) = const` across
  grow / depoly / sever / death — the en-masse `put(monomerCount)` at death is the conservation-critical line.
  Make it a hard validation gate (exact in integer-monomer counting; the µM quantum is fixed).

### 3c. Sever/death chain rewire — the SIMPLE case, not the hard one
- **Death/dissolve removes a WHOLE segment ⇒ NO new slot, NO split.** Rewire = set the dissolved segment's two
  neighbors' end-sentinels to `-1` (break both links; v1 `breakAtEnd1/2`). An interior dissolve fragments a
  filament into two **already-valid** sub-chains for free. **The 3-slot split@64 rewire is a strict superset**
  of what death needs — reuse its neighbor-update discipline, race-free (distinct dying tips → distinct neighbor
  triples). Node-tethered death must also clear `seedNode` + decrement the node's bound-filament count (v1
  `filamentOff`).

### 3d. End convention
Confirmed **barbed = end2 (settled)** ⇒ **pointed-end depoly is at end1**; the depoly coord-shift is the
**reverse of growth's end1 lengthen** (growth shifts to grow at the node-side end; depoly shifts back). Reuse
`GrowthSystem`'s coord-shift + `recomputeDrag` (the device drag port) with the opposite sign.

### 3e. Ordering / concurrency (one biochem cadence step)
`age (ADP-fraction)` → `dissolve check (whole-segment)` → `pointed-end depoly (+ death-on-floor)` →
`barbed-end grow (existing)` → `recompute drag/pose on length change` → `split@64 (existing)` →
**death sweep: markFree + pool put** → `rebuild free-list → allocate (nucleation/growth)`. All race-free via
self-write sentinels + the CSR-inverse pattern; biochem-cadence step-gated so the GPU graph stays fixed (the
established B2/growth discipline).

---

## 4. Settledness assessment (per part)

| part | settled? | note |
|---|---|---|
| hydrolysis / nucleotide aging | **settled** | `Monomer` state machine stable; validated in the v1 turnover-stress fixtures |
| pointed/barbed depolymerization | **settled** | active-by-default, nucleotide+end-asymmetric; merged in the 2026-06-11 residency campaign |
| cofilin en-masse dissolve | **settled** (default-off via `cofilinRatio=1.0`) | the lighter disposal; `bundleStableFactor` resist is stable |
| filament death / cleanup / slot recycle | **settled** in v1 (swap-compaction) | v2 **deliberately diverges** to slot-stable `markFree` (an improvement, not a port gap) |
| **membrane formin nucleation** | **CHURNING — OUT** | jba's in-development damped-filament work; do not read/port |
| tropomyosin, Arp2/3 branch transfer-on-death | settled but **vestigial/deferred** | `tropoOffRate=0` (tropo sticks); Arp branching is inc 5d |

**jba adjudicates the biology.** The turnover layer (hydrolysis/depoly/sever) is portable; only the membrane
nucleation is excluded.

---

## 5. Recommended STAGED MINIMAL-CUT build order

**Stage 0 (prereq, tiny) — pool return + conservation gate.** `ActinPool.put()`/restore; the
`pool + Σ monomerCount = const` invariant as a standing gate. *Highest risk: none — a counter + an assertion.*

**Stage 1 (THE FOUNDATION — smallest decisive increment) — pointed-end depoly + filament DEATH + slot-recycle.**
The reverse-of-growth. Constant (or ATP) depoly rate, **no nucleotide proxy yet**. Pointed-end (end1)
`monomerCount--` + coord-shift (reverse of growth) + `recomputeDrag`; on `monomerCount < actinSeed=3` →
**death**: `markFree(slot)` + `pool.put(monomerCount)` + break both end-links + clear `seedNode`/node count.
Default-off. **Gates:** conservation exact; a freed slot is reclaimed (same-step death→realloc, 5c-i order);
interior-death leaves two valid reciprocal chains; CPU≡GPU bit-identical lifecycle; **no-op-when-off ≡ growth
baseline** bit-identical. *Highest-risk bookkeeping: the same-step death-free → free-list-rebuild → realloc
ordering, and the death link-break (two neighbors' sentinels) staying race-free.*

**Stage 2 — en-masse old-ADP-segment removal (§3b) + the per-segment ADP-fraction proxy.** Add one per-segment
ADP-fraction scalar aged by the `kHydrolysis/kDissociation` cascade; whole-segment dissolve when it crosses a
**tunable threshold** (default trims at segment granularity — the lighter path; settable small ⇒ rely on Stage-1
monomer-by-monomer depoly for the fine-grained cloud). Dissolve = the **same death path as Stage 1** (markFree +
en-masse `put` + link-break) ⇒ an interior dissolve fragments a filament. *Highest-risk: the proxy aging vs v1's
aggregate ADP-fraction (adjudicate vs statistics, §8); validating that an interior whole-segment dissolve yields
two reciprocal valid chains.*

**Stage 3 — nucleotide-dependent rates + treadmilling steady state.** Wire the ADP-fraction proxy into
`getDepolyRateEnd1/2` (terminal-age → ADP-off vs ATP-off); barbed-grow (done) + pointed-shrink (Stage 1) ⇒
treadmilling. **Adjudicate vs physics** (treadmilling steady state, pool buffering, length-vs-[actin] scaling) —
NOT v1's uncalibrated ring numbers (§8). *Highest-risk: proxy-driven end-rate fidelity; steady-state convergence
at ring scale.*

**Stage 4 (OPTIONAL high-fidelity) — full per-monomer `Monomer` path + cofilin decoration + tropomyosin.** Only
if a specific assay needs the spatial microstate. A per-monomer SoA is a large addition; defer unless required.
*Highest-risk: the per-monomer SoA itself (size, the linked-list→SoA mapping); keep behind the proxy as the
default.*

---

## TL;DR for the planner

- **Representation fork RESOLVED — a single per-segment ADP-fraction/age scalar suffices** (depoly reads only the
  terminal monomer; dissolve reads a per-segment ratio; the inc-6 assays run `noMonomersSimd`). A full
  per-monomer SoA is optional Stage 4. Adjudicate the aged distribution vs v1 **aggregate** statistics, not
  per-monomer (§8).
- **Severing is a WHOLE-SEGMENT en-masse dissolve, not a mid-segment cut** — no new slot, no split; an interior
  dissolve fragments a filament into two already-valid sub-chains. **Simpler than split@64.** This is jba's §3b
  lighter disposal; the threshold (`cofilinRatio`, default-off at 1.0) is the tunable seam.
- **Slot recycling is mostly built** (`markFree` + the scan-rank free-list); death = `markFree` + pool `put`, a
  race-free sentinel self-write, **no swap-compaction** (structurally avoids v1's `packRange` desync). New code
  = `ActinPool.put()`/conservation gate + the ADP proxy + pointed-end shrink (reverse of growth).
- **Minimal cut = Stage 1: pointed-end depoly + filament death + pool-return + slot-recycle** (reverse-of-
  growth), then en-masse dissolve (Stage 2), nucleotide rates/treadmilling (Stage 3), full per-monomer
  (optional Stage 4).
- **Ordering:** age → dissolve → pointed-depoly(+death) → barbed-grow → recompute → split → death-sweep(markFree
  + put) → free-list rebuild → allocate. Biochem-cadence step-gated (GPU graph fixed).
- **Settled + portable** (the 2026-06-11 v1 turnover-residency campaign closed it); the **membrane formin
  nucleation is the churning one and is OUT.** jba adjudicates the biology.
- **Survey only — no code written. `BoA-v1ref` byte-clean.**
