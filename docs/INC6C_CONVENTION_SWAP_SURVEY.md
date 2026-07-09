# Increment 6c — SURVEY: the end1/end2 (barbed-end) convention footprint for a v1-matching swap — READ-ONLY

**Date:** 2026-06-19. **Mode:** READ-ONLY survey. No code change; `BoA-v1ref` untouched. Scopes the next build
(adopt v1's nomenclature, **barbed = end2**) so it can be done in one consistent pass.

## HEADLINE — v2 is INCONSISTENT, not globally barbed=end1 (pause+document; it SHRINKS the swap)
The diagnosis framed v2 as "barbed = end1." The survey refines that: **v2 is *split*.**
- **Every SHARED system and every NON-NODE assay already use barbed = end2** (uVec → plus/barbed end), i.e.
  **already at v1's convention / the target.** Explicit: `PinSystem.java:20-21` ("end2 = the plus/barbed tip …
  uVec → plus"); `GlidingHarness:129`, `ContractileAssayHarness:144-145`, `NodeContractileHarness:112-113`,
  `DimerGlideHarness:89/433-444`, `MiniGlideHarness:141`, `MotorStrokeHarness:229` ("plus-end (end2)", uVec +x).
  These were validated within-SEM vs v1 — they glide/contract correctly **because** they are barbed = end2.
- **ONLY the node / growth / nucleation subsystem uses barbed = end1** (uVec points OUTWARD, away from the node):
  `GrowthSystem` ("growing/barbed/formin end = end1", :16), `NodeNucleationSystem.emit/seedTether` ("end1 at the
  node center", :78/:124), `TestBScprHarness.placeAimedChain` ("tip end1 at the node center (barbed)", :385),
  the warm-start, `GrowthHarness` (:101). This is the inc-6c polymerization-era convention
  (`INC6C_POLYMERIZATION_FINDINGS.md`), and it is the **sole** divergence from v1.

**Consequence for the swap:** it is **NOT a global flip**. It is **"fix the node/growth subsystem to match the
barbed = end2 convention already used everywhere else (and by v1)."** The shared systems and all non-node assays
need **ZERO edits** and must regress **bit-identical** (the consistency check). This is lower-scope and
lower-risk than a global swap — and is the "currently-inconsistent convention site" the discovery boundary asked
to surface.

## The convention is DISTRIBUTED — there is no single constant (why the swap must be atomic)
The "which physical end is barbed / which end attaches to the node / which way uVec points" is **not** a central
flag. It is encoded per-site by **filament placement + growth direction + nucleation attach-end + chain
wiring**. The *systems* (`DerivedGeometry`, `Binding`, `CrossBridge`, `Chain`, `FrameWriter`) are
**convention-AGNOSTIC** — they operate on `uVec`/`end1`/`end2` mechanically. So a *partial* swap (flip placement
but not growth's sign, or the gate but not the chain wiring) is itself a fresh polarity bug — exactly what this
arc chased. **The fix is achieved entirely upstream** (placement + growth + nucleation set the node-filament's
`uVec` to point INWARD); the gate/stroke then reject self-binding with **no gate edit**.

## SITE MAP (file:line, classified)

### A. PURE RELABEL / convention-neutral — UNCHANGED (bit-identical by not touching)
These define geometry from `uVec` mechanically; they never read polarity by end-label. No edit; they are the
consistency check.
| site | file:line | role | why neutral |
|---|---|---|---|
| derivation | `DerivedGeometrySystem.java:16-17,63-70` | `end1=coord−½L·uVec`, `end2=coord+½L·uVec` | DEFINES end2 = the +uVec end; the convention is which physical end is placed at +uVec, set elsewhere |
| store layout | `FilamentStore.java:46-47,73-76` | end1/end2 derived; `end?NbrSlot/Side` | mechanical storage |
| **bind gate** | `BindingDetectionSystem.java:67,79-83` | `uVec=(e2−e1)`, `motDotFil`, `rodDotFil≥0` | mechanical dots; **the LOCUS of the fix but needs NO edit** — the fix comes from the upstream uVec flip |
| tip exclusion | `BindingDetectionSystem.java:326,359` | `seedNode≥0 ⇒ skip` | keyed by `seedNode`, **not** by end ⇒ untouched by the relabel (still excludes the node-held tip) |
| cross-bridge stroke | `CrossBridgeSystem.java:86,90-91` | stroke/bound-site use stored `uVec` (toward +uVec=end2) | mechanical; node-scene stroke DIRECTION flips with the upstream uVec flip (intended, see §C) |
| chain force | `ChainBendingForceSystem.java:21-24,170-179,217,302` | side-decode `Side==0→nbr.end1 / ==1→nbr.end2` | mechanical; reads whatever wiring placement sets |
| viewer | `FrameWriter.java:23-25,93-99` | reconstructs end1/end2 from `coord,uVec` | reads polarity by computing from uVec, never by label |
| pin | `PinSystem.java:20-21` | snaps end2 (plus/barbed) | only in barbed=end2 contractile assays; unchanged |
| **non-node assays** | `GlidingHarness:129`, `ContractileAssayHarness:144-145,249`, `NodeContractileHarness:112-113,179-180`, `DimerGlideHarness:89,433-444`, `MiniGlideHarness:141`, `MotorStrokeHarness:229`, `MotorXBridgeHarness`, motor/xlink | place barbed=end2 (uVec→plus) | **already at target**; unchanged ⇒ bit-identical |

### B. BEHAVIOR-CHANGING — the swap (node/growth/nucleation: barbed=end1 → barbed=end2)
The intended fix + the supporting consistent flips. After the swap the node-attached filament's `uVec` points
**INWARD** (toward the node), so `rodDotFil < 0` for the node's own outward myosins ⇒ self-binding rejected as in
v1.
| # | site | file:line | what it currently encodes | flip to |
|---|---|---|---|---|
| 1 | nucleation seed pose | `NodeNucleationSystem.emit:78` | tethered **end1** at node center; `coord=node+halfLen·dir`, `uVec=dir` (OUTWARD) | **end2** at node; `uVec = −dir` (INWARD); coord unchanged (still outward of node) |
| 2 | seed tether | `NodeNucleationSystem.seedTether:124` | tethered end = `end1 = coord−half·uVec` | tethered end = `end2 = coord+half·uVec` |
| 3 | grow (lengthen) | `GrowthSystem.grow:87-91` | `coord += ½mono·uVec` (end1/node fixed, end2 extends outward) | `coord −= ½mono·uVec` (end2/node fixed, end1 extends outward) — SAME physical "far end extends, node end fixed" |
| 4 | split stage | `GrowthSystem.markSplits:118-129` | child = OUTER half on the **end2** side; `shift = −½Lold+Lpar+½Lchild−mono` | child = outer half on the **end1** side (negate the axial shift) |
| 5 | split wire | `GrowthSystem.splitWire:158-180` | parent keeps **end1** fixed; rewire `G.end2↔C.end1`, `C.end2→Mold` | parent keeps **end2** fixed; rewire `G.end1↔C.end2`, `C.end1→Mold` (mirror the 3-slot rewire) |
| 6 | aimed placement | `TestBScprHarness.placeAimedChain:385-398` | `end1` at node, `uVec=dir` (outward), `end1Nbr←prev/end2Nbr←next` | `end2` at node, `uVec=−dir` (inward), mirror the endNbr wiring |
| 7 | warm-start placement | `TestBScprHarness:460-470` | same barbed=end1 seed placement | mirror as #1/#6 |
| 8 | node→tip chain walk | `TestBScprHarness.filNodeOf:608-616` | walks `end1NbrSlot` toward the node tip | walk `end2NbrSlot` (diagnostic only) |
| 9 | growth harness setup | `GrowthHarness.java:101-104` | "end1 at the node; +x outward", `uVec=+x`, `seedNode=k` | end2 at node, `uVec=−x`, mirror |
| 10 | nucleation harness | `NodeNucleationHarness` (emit-driven + tether gate double-ref) | inherits #1/#2 | follows the system flip (update the analytic double-ref) |
| 11 | comments / labels | `GrowthSystem:16-21,51`, `placeAimedChain:375,385`, `filNodeOf:608`, `TestBScprHarness:690-691`, `GrowthHarness:26-27`, diagnosis doc | "barbed = end1", "tip end1 at node" | "barbed = end2" |

**Note on #3:** growth is **physically preserved** — currently "node end fixed, far end extends outward" with
end1=node; after the swap the same physical behavior with end2=node. It is a label + uVec-sign mirror, not a
direction change. (It also becomes *more* v1-faithful: v1's barbed-end-at-formin picture is now matched in
nomenclature.)

## SAFETY PROPERTY — confirmed
For **non-node-coupled** filaments the convention is a **free relabel**: `uVec` is the stored source of truth and
the gate (`(e2−e1)`) + stroke (stored `uVec`) + chain (mechanical side-decode) all share it (verified
`DerivedGeometrySystem:63-70` ⇒ `(e2−e1)/L == uVec` exactly). Because the swap touches **only** the node/growth
subsystem (§B) and leaves every shared system + non-node assay **unedited**, those assays are **bit-identical by
construction**. The **only** intended behavior change is the node-binding polarity (the fix). ✔ matches the
reassurance the swap rests on.

**Flagged: GrowthSystem is a SHARED kernel whose sign flips (the one site to watch).** It is gated by
`seedNode≥0` (only node-bonded tips grow) and no non-node assay invokes growth on a barbed=end2 filament
(confirmed: only `GrowthHarness`/`TestBScprHarness`/`NodeNucleationHarness` run `GrowthSystem`, all node scenes).
So flipping `grow`'s sign + `splitWire`'s rewire cannot leak into a non-node assay **as long as that invariant
holds** — but it is the site where a leak *would* occur, so the bit-identical growth-free regressions are the
guard.

## RECOMMENDED ATOMIC SWAP (scope only — do NOT execute)
Flip **all of §B together in one pass**, so the node-attached filament's `uVec` ends up INWARD and the chain
stays linear:
1. **Nucleation** (#1,#2): attach **end2** to the node; seed `uVec` points **inward**.
2. **Growth** (#3,#4,#5): keep **end2** (node) fixed, extend **end1** outward (negate `grow`'s coord shift +
   `markSplits`' axial shift); mirror the `splitWire` 3-slot rewire (`G.end1↔C.end2`, `C.end1→Mold`, parent keeps
   end2 fixed).
3. **Placement** (#6,#7,#9,#10): every node-attached filament setup puts **end2** at the node, `uVec` inward,
   with endNbr wiring mirrored.
4. **Diagnostics/comments** (#8,#11): walk `end2NbrSlot` to the node tip; relabel "barbed = end2".
5. **Shared systems + non-node assays (§A): NO EDIT.**
Result: node filament `uVec` INWARD ⇒ own outward myosin `rodDotFil < 0` ⇒ self-binding rejected (the fix);
cross-capture by the partner's near-facing heads (no overshoot needed); the whole codebase consistently
barbed = end2.

## REGRESSION PLAN
- **BIT-IDENTICAL (consistency check — nothing leaked into shared code):** `run_gliding`, `run_contractile`,
  `run_nodecontract`, `run_dimer`, `run_dimerglide`, `run_minifil`, `run_miniglide`, `run_stroke`,
  `run_xbridge`, `run_motor`, `run_xlink` — all call unchanged systems with unchanged setup ⇒ must reproduce
  their baselines bit-for-bit (GPU + `-cpu`). **Any diff ⇒ the swap leaked; bail.**
- **BEHAVIOR-EQUIVALENT (gates pass, NOT bit-identical — poses mirror):** `run_growth`, `run_nodenuc`,
  `run_filbirth`, `run_node` — re-validate the gates (monomer conservation, chain reciprocity, rate, allocator,
  tether double-ref, CPU≡GPU bit-identical lifecycle). Pose arrays are mirrored (uVec flipped) ⇒ not bit-identical
  to the old baseline; the gates are convention-neutral and must still PASS.
- **BEHAVIORAL TARGET (the fix):** `run_testb -aimed` — node filament `uVec` now inward ⇒ the `[orient]` log
  should show self-capture `rodDotFil < 0` (now REJECTED by v2's own gate, v1-consistent), self-grab eliminated
  or at v1 level; **cross-capture + the beyond-noise approach must survive**; reproduce v1's clean-coalescing
  twoNodeFormin (2 nodes, no self-grab). CPU≡GPU agree.

## RISK SITES (where a partial/inconsistent swap bites)
1. **`GrowthSystem.splitWire` 3-slot chain rewire (#5)** — the highest geometric risk (the original A1-trap). The
   `G.end2↔C.end1 / C.end2→Mold` rewire + the parent "keep end1 fixed" shrink must ALL mirror to the end1/end2-
   swapped form together, or the chain delinks / the child lands on the wrong side. Re-gate chain reciprocity.
2. **`GrowthSystem.grow` sign (#3)** — flip `+½mono·uVec` → `−½mono·uVec`. If the sign is NOT flipped with the
   uVec flip, growth extends INWARD (through the node) — a silent direction bug. (Shared kernel — see the flagged
   invariant.)
3. **`markSplits` child geometry (#4)** must use the SAME flipped sign as `grow`/`splitWire`, else the child
   spawns on the wrong side.
4. **endNbr wiring in placement (#6,#7,#9)** — `end1Nbr/end2Nbr/Side` must mirror with the end relabel, or the
   chain force (neutral, mechanical) reads a broken topology.
5. **`filNodeOf` walk direction (#8)** — diagnostic-only, but if not flipped it mis-attributes self vs cross and
   corrupts the `[orient]`/diag readout (the very instrument validating the fix).
6. **Distributed convention ⇒ atomicity** — there is no single constant; the swap MUST flip every §B site in one
   pass. A partial swap is a new polarity bug.

## Bottom line
The barbed-end convention footprint is **localized to the node/growth/nucleation subsystem** (§B, ~11 sites) —
NOT global. Every shared system + non-node assay is **already barbed = end2 (= v1) and stays untouched**, giving
a **bit-identical consistency check** for free. The atomic swap flips placement + growth + nucleation + chain
wiring so the node-attached filament's `uVec` points INWARD, fixing the self-grab at the root with **no gate
edit**, and adopting v1's nomenclature codebase-wide. Highest risk = the `splitWire` chain rewire + the `grow`
sign (a shared kernel). READ-ONLY survey; the swap is the next scoped build.
</content>
