# Increment 6c — actin POLYMERIZATION: barbed-end elongation (lengthen + split, growth-only) — FINDINGS

**Status: DONE (2026-06-18). 8 gates PASS GPU + CPU; B1/B2 regressions bit-identical; default-OFF.**
The FIRST dynamic actin GROWTH in SoftBox (filaments were static-length through inc 6). Built from the v1
BEHAVIOR (recon `INC6C_POLYMERIZATION_RECON.md`), not a class port. Run: `./run_growth.sh [-cpu]`.

## What was built
A growing filament now elongates at its **node-side barbed end** — `monomerCount++` at the
**[actin]-dependent rate** (`P = onRate·conc·biochemDeltaT`, first-order in [actin]), **splitting at 64
monomers** into two 32-monomer halves via B1's scan-rank allocator + a **correct, gated 3-slot chain
rewire**, **depleting the actin pool** per monomer — growth **device-resident** (a real device drag
recompute), **default-OFF** (every validated assay unchanged), **CPU≡GPU**. Unlocks **Test B** (two nodes'
filaments grow → bridge → captured → walk together) and the contractile ring. New files only +3 Constants
additions (no existing value changed) ⇒ prior harnesses byte-unchanged.

- **`GrowthSystem`** — 4 device-agnostic kernels: `grow` (lengthen), `markSplits` (stage child request),
  `splitWire` (parent shrink + 3-slot chain rewire), `recomputeDrag` (device per-slot port of
  `DragTensorSystem.run`).
- **`GrowthStore`** — growth params + the `ActinPool` (seam #2) + the grew-counter scratch (reused csrScan).
- **`GrowthHarness`** + **`run_growth.sh`** — the 8-gate validation, both runners.
- **`Constants`** — `biochemDeltaT=1e-3 s`, `kATPOn2WithFormin=11.6 µM⁻¹s⁻¹`, `minMonomerCt=30` (additions only).

## The granularity mapping — "lengthen the terminal segment, then split" (recon headline, realized)
v1 and SoftBox are the SAME shape: a length-mutable rod carrying `monomerCount`, with
`segLength=(monomerCount+1)·actinMonoRadius` and the drag-from-monomerCount recompute present on both
sides. So growth turned on a **dormant, shape-compatible** path, NOT a biochem layer:
- **Lengthen** (`grow`): per growing tip, draw wang-hash at `P`; on growth `monomerCount++`, `coord +=
  ½·actinMonoRadius·uVec` (end1/node FIXED, end2 extends outward — v1 `incCoord(halfmono/2,uVec)`), set
  `grewFlag` (the pool counter). Growing tips = node-bonded segments (`seedNode≥0`, reusing B2's bond).
- **Split @64** (`markSplits`→allocator→`splitWire`): at `monomerCount≥2·stdSegLength`, the **outer half**
  is born into a free slot via **B1's scan-rank allocator (REUSED VERBATIM)**; the node-side half stays on
  the existing slot (keeps the node bond, keeps growing); `splitWire` shrinks the parent (32, end1 fixed),
  sets the child (32), and rewires the chain neighbors of `{parent G, child C, G's old end2-neighbor Mold}`
  so the chain stays linear: `node—G(32,tip)—C(32)—Mold—…`. Distinct tips ⇒ distinct `{G,C,Mold}` ⇒
  race-free, no atomics.
- **Drag** (`recomputeDrag`): device per-slot port of `DragTensorSystem.run` (segLength + the min-length
  clamp + the SHARED rod-drag formula). **Math.log lowers on PTX** (`BrownianForceSystem` proves it) ⇒ a
  real device kernel (recon flag d resolved — NOT the host all-slots fallback).

## The four recon flags — addressed
- **(a) growing-end convention.** Resolved: the growing/barbed/formin end = **end1**, consistent with B2's
  `seedTether` (end1↔node). Growth keeps end1 at the node and extends end2 OUTWARD. A labeling choice, not
  physics; the success criterion ("grows at its node-side barbed end, extends outward") is met (gate 4:
  contour 0.086→2.50 µm, tip end1 held within 4e-5 µm of the node).
- **(b) the split's 3-slot chain rewire — the main correctness risk.** Built + gated as hard as the B1
  allocator (gate 2): the lone-tip case AND the inserted-between-`Mold` case both produce a valid linear
  chain (reciprocal links verified), monomers conserved (64→32+32), geometry consistent (child outward,
  end1 fixed), **CPU≡GPU bit-identical lifecycle**. Chain reciprocity re-verified over an 80k-step Brownian
  run (gate 8). **No paper-over needed — the rewire is correct.**
- **(c) drag clamp vs the 3-monomer seed.** Reported (gate 7): a 3-monomer free-rod seed (len 0.0108 µm)
  gets drag **clamped to `stdSegLength·mono`** (len 0.0864 µm) — `bTGx=2.35e-8` vs unclamped `2.90e-8`
  (0.81× lower). This is **faithful to v1 FilSegment:409-419** (short end-segments don't get unphysically
  low drag / over-diffuse); `recomputeDrag`'s clamp matches the host `DragTensorSystem` rule bit-for-bit.
  Intended, not a problem.
- **(d) device vs host drag recompute.** Resolved to **device** (`Math.log` lowers on PTX). Caveat (flagged
  for ring scale): `recomputeDrag` runs over ALL slots each cadence — cheap here (cadence = every
  `biochemCheckInt` steps); a single-slot/targeted variant is a ring-scale optimization, not needed now.

## Gates (all PASS, GPU + CPU)
1. **lengthen** — `recomputeDrag` == host `DragTensorSystem` over a monomerCount sweep (maxRel 1.1e-7);
   dynamic: 33371 monomers grown over 64 tips × 3000 cadences, **P_emp 0.1738 vs onRate·conc·biochemΔt
   0.1740 (0.1%)**; splits created children (64→1018 active).
2. **split @64 (headline)** — lone-tip: child allocated, conserved 32+32, links correct, end1 fixed, child
   outward; `Mold`-case: child inserted between (G.end2→C, C.end2→Mold, Mold→C), conserved; **CPU≡GPU
   bit-identical lifecycle, Δcoord 1.4e-9 µm**.
3. **rate + pool (seam #2)** — first-order: P(15µM)/P(7.5µM) = **2.005**; pool depletes conservatively
   (15.000→10.204 µm = grown·µMper) and the rate **slows as it drains** (grows first-half 30373 >
   second-half 25085). [actin]-dependent (B2 nucleation was [actin]-independent — growth adds the pool READ).
4. **growing-end** — contour Σ segLength **0.0864→2.5029 µm (29×)**; max tip end1↔node gap **4.2e-5 µm**
   (held by the B2 tether — extends OUTWARD).
5. **no-op-when-off** — growth OFF (fires gated to 0) ⇒ monomerCount/filState/seedNode/pose **bit-identical**
   to a static baseline (max|Δcoord| = 0.00).
6. **CPU≡GPU (full pipeline, 12000 steps)** — monomer/state/link/seed mismatches **all 0** (bit-identical
   lifecycle); **max|Δcoord| = 0.00 µm**.
7. **drag-clamp fidelity** — reported (flag c above); the clamp matches v1.
8. **participates + dt-stable** — an 80k-step Brownian run with growth ON: finite, bounded (max|coord|
   3.5 µm), **chain reciprocal valid**, integrates + chains a lengthening/splitting filament. **dt-stable.**

## Reuse vs new
- **Reused VERBATIM:** B1's scan-rank allocator (`FilamentBirthSystem` + `CrossBridge.csrScan`) for the
  child; the chain-bonding (`ChainBendingForceSystem` reads neighbors+segLength per step, tolerates a
  runtime-added terminal segment); `ActinPool` (`available`/`take`/`conc`); the wang-hash RNG; the
  integration/Brownian/derive systems; B2's `seedNode` + `seedTether`.
- **Genuinely new:** `grow` (lengthen + coord shift), `markSplits`+`splitWire` (split + 3-slot rewire),
  `recomputeDrag` (device drag), the [actin]-dependent rate (pool READ).

## Flagged v1 divergences (behavior-faithful, not class-faithful)
- **Formin stays on the stable tip slot** (we keep the node bond on G); v1 `transferEnd2Plasmid` moves it
  to the child. Topologically equivalent (`node—G—C—…`); avoids reshuffling `seedNode`/tether every split.
- **Depolymerization / treadmilling DEFERRED** (the next layer, tied to filament turnover; growth-only /
  monotonic is what Test B bridging needs). `ActinPool.put`/restore is NOT added yet (only `take`).
- **No per-monomer nucleotide (ATP→ADP) state** — v1 runs `noMonomersSimd` anyway; the load-bearing growth
  quantity is the scalar `monomerCount`. The force-modulated stall (`getPolyRateEnd2`) + the `nodeTorqSpring`
  align torque are also deferred (second-layer refinements).
- **Run-length bound:** split children + (B2) freed seeds persist (no turnover) — capacity bounds run length
  (flagged in B1/B2 too). A tip at 64 with no free slot simply doesn't split (graceful; `grow`'s maxMon
  guard prevents overshoot).

## TL;DR
Growth = **lengthen-then-split**, the v1 granularity match realized on the existing `monomerCount`/`segLength`
+ allocator + chain + pool. The split's 3-slot chain rewire — the main risk — is **correct and gated**
(CPU≡GPU bit-identical, valid linear chain incl. the inserted-between case). Growth is **device-resident**
(device drag recompute; `Math.log` lowers on PTX), **first-order in [actin]** (pool read + deplete),
**default-OFF** (prior assays bit-identical), **CPU≡GPU**. Depolymerization/treadmilling is the deferred
next layer.
