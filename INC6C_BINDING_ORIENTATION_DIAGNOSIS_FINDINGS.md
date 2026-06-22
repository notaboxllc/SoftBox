# Increment 6c — DIAGNOSIS: why v2 admits wrong-orientation node-myosin bindings v1 rejects — FINDINGS

**Date:** 2026-06-19. **Mode:** DIAGNOSTIC ONLY. Read-only on `BoA-v1ref` (byte-clean). v2 change limited to
**diagnostic logging** in `TestBScprHarness.diagnoseSelfCapture` (a `[orient]` bind-orientation log) — **no gate /
force / head-placement change.** No fix. The fix is a separate, regression-heavy task.

## VERDICT — it is (i) the GATE, specifically the filament-polarity CONVENTION flip (a sign inversion)
The bind-gate **formula and thresholds are byte-identical** between v1 and v2. The discrepancy is the **end1/end2
(barbed-end) CONVENTION**: v1 attaches a node's formin to the filament's **barbed end = end2**, so the filament's
`uVec = (end2−end1)` points **INWARD, toward the node**; v2 attaches the barbed end = **end1**, so v2's stored
`uVec = (end2−end1)` points **OUTWARD, away from the node**. For a node-nucleated filament the two polarities are
**exact negatives**. Because the node's own radial myosins point **outward**, this flips the sign of the decisive
polarity gate `rodDotFil ≥ 0` (and of `motDotFil`) for a node's OWN filament:

| | own myosin rod | own filament uVec | `rodDotFil` | gate `≥ 0`? |
|---|---|---|---|---|
| **v1** (barbed=end2 at node) | outward | **INWARD** (toward node) | **< 0** | **REJECT** ✓ no self-grab |
| **v2** (barbed=end1 at node) | outward | **OUTWARD** (away from node) | **> 0** | **ADMIT** ✗ self-grab |

So v2 admits the self-binding orientation v1 rejects. **Not (ii):** v2's node-myosin heads DO point outward (same
as the natural radial config), but the head orientation is not the discriminating cause — substituting v1's inward
filament polarity rejects those same heads regardless of head distribution (see §(ii) below).

## The gate — v1 vs v2, line by line (presence / threshold / vector / convention)
**v1** `BoA-v1ref/boxOfActin/MyoMotor.java:381-423` `checkFilSegCollision`, gates in order:
```java
motDotFil = soaUX[motorId]·FilSegment.soaUX[filId] ... ;   if (motDotFil < myoMotorAlignWithFilTolerance) return;  // :388-391  align
rodDotFil = soaRodUX[motorId]·FilSegment.soaUX[filId] ...;  if (rodDotFil < 0) return;                              // polarity
if (FilSegment.soaNodeAtEnd2[filId]) return;                                                                        // node-held exclusion (:391-392)
... alpha∈[0,1], conDistSq < myoColTol² ...
```
**v2** `softbox/BindingDetectionSystem.java:59-85` `reachTestDistSq` (+ the `…NodeAware` overloads):
```java
fux,fuy,fuz = (e2−e1)/|e2−e1|;                              // filament axis, ON THE FLY
motDotFil = mux·fux+...;  if (motDotFil < alignTol) return -1;   // :80-81  align
rodDotFil = rux·fux+...;  if (rodDotFil < 0f)  return -1;        // :82-83  polarity
// node-held tip exclusion added inc 6c as the …NodeAware overloads (seedNode>=0 ⇒ skip)
... alpha∈[0,1], conDistSq < myoColTol² ...
```
- **Presence:** identical — both gates present (align + polarity + node-held exclusion). Same ORDER of *decisions*
  (the v2 NodeAware skip is `seedNode≥0`, the analog of `soaNodeAtEnd2`, already ported in the prior fix).
- **Threshold:** identical. `alignTol = myoMotorAlignWithFilTolerance = −0.4` (v1 `Env.java:784` ≡ v2
  `TestBScprHarness.ALIGN_TOL = −0.4`); polarity gate `≥ 0` in both. **No threshold drift.**
- **Vectors:** v2's predicate computes the filament axis on the fly as `(e2−e1)/|.|`; v2's `DerivedGeometrySystem`
  sets `end1=coord−½·segLen·uVec`, `end2=coord+½·segLen·uVec` ⇒ `(e2−e1)/|.| == stored uVec` **exactly**. So
  v2's gate and v2's cross-bridge stroke (`CrossBridgeSystem.java:86`, which reads the stored `filUVec`) use the
  **same** filament axis — v2 is internally self-consistent. The motor rod/head axes are the same physical
  vectors v1 uses (`soaRodU*`/`soaU*`). **No vector-source mismatch.**
- **The CONVENTION — the actual discrepancy.** v1 `uVec=(end2−end1)`, **end2 = plus/barbed**
  (`FilSegment.java:3986-3987, 877-881`); a node holds the **barbed end2**
  (`FilSegment.checkForminBinding:2367` — *"only barbed-end (end2Pt) can bind to formin at node"*). ⇒ v1's
  node-filament uVec points **toward the node**. v2 chose **barbed = end1** (the polymerization convention flip,
  `INC6C_POLYMERIZATION_FINDINGS.md`) and attaches end1 to the node (`TestBScprHarness.placeAimedChain:385-391` —
  *"tip end1 at the node center (barbed)"*, `setUVec = aim direction = OUTWARD`; nucleation/growth keep end1 at
  the node, extend end2 outward). ⇒ v2's node-filament uVec points **away from the node**. **The sign is
  inverted** for every node-nucleated filament — and `rodDotFil`/`motDotFil` invert with it.

## Why the radial node is the FIRST scene to expose it — and why prior assays passed
The gate references ONLY `uVec=(e2−e1)`. The barbed (end1-vs-end2) label matters for **growth** (which end
elongates) and **node attachment** (which end the formin holds) — NOT for the bind gate per se. So in any scene
whose filament polarity is **not coupled to a node**, the convention is a free relabeling: v2's gate and stroke
share `uVec`, directed motion emerges, and absolute polarity is invisible.
- **Gliding (4b-iv):** free/surface filaments, no node attachment. ✓ passed within-SEM.
- **Contractile assay + node-contractile (inc 6):** the minifilament / node binds **pinned, pre-placed** filaments
  — also not node-nucleated. ✓ passed within-SEM.
The convention flip first bites when a filament's polarity is **fixed by node attachment** AND the same node's
**own outward myosins** try to bind it — which first happens in **inc 6c Test B** (formin nucleation + own-shell
capture). That is the latent gate error the radial geometry exposes. (It is a *latent gate inversion*, per the
task's narrowing constraint — not a formula bug, which would have broken the prior assays too.)

## The EMPIRICAL bind-orientation log (measured, not inferred)
`diagnoseSelfCapture` now logs, at each current capture, the actual `motDotFil`/`rodDotFil` (v2's convention, on
the stored `uVec`) and asks **would v1's gate admit it?** — i.e. with the node-filament polarity flipped to v1's
(`−motDot ≥ alignTol && −rodDot ≥ 0`). Run: `./run_testb.sh -cpu -aimed`:
```
[orient] gate thresholds: motDotFil >= ALIGN_TOL(-0.40), rodDotFil >= 0 (v1==v2 formula)
[orient] SELF  captures n=2: mean motDotFil=-0.287 rodDotFil=+0.701 (v2 admits all) | would v1's gate admit? 0/2
[orient] CROSS captures n=1: mean motDotFil=-0.378 rodDotFil=+0.139 (v2 admits all) | would v1's gate admit? 0/1
```
- **SELF: `rodDotFil = +0.701 > 0` ⇒ v2 ADMITS.** The decisive polarity gate is satisfied because the own
  outward rod is parallel to the own OUTWARD filament. With v1's INWARD polarity, `rodDotFil = −0.701 < 0` ⇒
  **v1 REJECTS (0/2).** The smoking gun — the sign on the exact gate the convention flips.
  (`motDotFil = −0.287` is ≥ the lenient `−0.4` align tol, so the align gate alone wouldn't stop it in EITHER
  convention; the **rod polarity gate** is the discriminator, and it is the one that inverts.)
- **CROSS: `rodDotFil = +0.139 > 0` ⇒ v2 admits; v1 would admit 0/1.** v2's cross-capture is by **far-hemisphere
  heads on an OVERSHOT filament** (`INC6C_TESTB_AIMED_SCPR_FINDINGS.md`) — itself a *symptom* of the flip: in v1's
  inward convention the partner's **near-facing** heads grab the incoming filament directly (no overshoot). The
  log cleanly separates the two modes — both currently carry `rodDotFil>0` under v2's outward polarity.

(Counts are small-n at the sampled final step — 2 self / 1 cross — but the **sign is decisive and uniform**, and
the v1-would-admit count is exactly 0, matching the prediction. The capture-PHASE force averages —
self 12.4 pN vs cross 11.6 pN, self/cross 1.07 — show the self-grab is significant, not noise.)

## (ii) Node head orientation — compared, and ruled out as the root cause
- **v2:** node myosin heads/rods are assembled with `uVec = the radial outward unit vector` (Fibonacci splay,
  `TestBScprHarness.buildShells:131-137` → `assembleArticulated(..., ux,uy,uz, ...)`). Deterministically OUTWARD.
- **v1:** `ProteinNode.makeMyosinSinglets` (`:348`) uses `Pt3D.RandomUnitVec` (random head direction); the
  hemisphere constructor (`ProteinNode.java:103-110`, `makeMyosinSinglets(hemisphereNormal)`) forces heads into a
  given hemisphere. (Which constructor jba's twoNodeFormin used is the one scene detail not verifiable from
  `BoA-v1ref` statics; it is **not load-bearing** — see next.)
- **Not the discriminator:** with v1's INWARD filament polarity, a head must point **inward** to pass
  `rodDotFil ≥ 0` on its own filament — but an inward-pointing head's tip retracts toward the node center, away
  from the outward filament, so the **reach** test also fails. So v1 rejects own-filament self-capture whether its
  heads are random OR outward. The discriminating variable is the **filament polarity**, not the head
  distribution. (v2's deterministic-outward heads + outward filament make self-capture both aligned AND
  reachable, which is why it is rampant in v2 — head orientation *compounds* but does not *cause*.)

## Scene comparison — v2 Test B′ vs v1 twoNodeFormin (rule out a scene difference)
| | nodes | myosins/heads per node | filaments | node hold |
|---|---|---|---|---|
| **v1 twoNodeFormin** (jba) | 2 | 120 myosins | 18 segments | formin-bound (held) |
| **v2 Test B′ `-aimed`** | 2 | 18 motors/node = 6 singlet + 6 dimer×2 (×2 nodes = 36) | 1 aimed pre-grown chain/node | nucleated + tethered |
Scale differs (v1 denser), but both are **radial-shell myosin nodes nucleating/holding their own actin** — the
identical mechanism the gate acts on. The convention/gate is **scene-independent**, so the divergence is the
**binding polarity**, not the scene. (jba's decisive datum: v1 with HELD filaments **coalesces cleanly, no
self-grab** — consistent here: v1's `nodeAtEnd2` excludes held tips AND its inward polarity rejects own-myosin
binding on the outer segments; v2's outward polarity admits exactly those outer-segment self-grabs — the
`INC6C_SELFCAPTURE_RULE_FINDINGS.md` residual.)

## Reconciliation with the prior fix (`INC6C_SELFCAPTURE_RULE_FINDINGS.md`)
That fix ported v1's **tip exclusion** (`seedNode≥0 ⇒ skip`) and correctly drove **0 binds on node-held tips**,
but left a **residual self-capture on OUTER (`seedNode<0`) segments ~0.124 µm out**, flagged as a "geometry
caveat." **This diagnosis identifies that residual's root cause:** it is the **polarity-convention flip**, not a
reach/geometry coincidence. v1's outer segments share the filament's INWARD polarity ⇒ own outward myosins are
anti-parallel (`rodDotFil<0`) ⇒ rejected; v2's outer segments are OUTWARD ⇒ parallel (`rodDotFil=+0.701`) ⇒
admitted. The tip rule cannot reach it because the rule is correctly tip-only (faithful to v1); the outer-segment
self-grab is a *different* mechanism — the inverted polarity — that the rule was never meant to cover.

## Decisive discrepancy (for the fix-scoping task — NOT done here)
- **Where:** the filament end1/end2 (barbed) **polarity convention** for node-nucleated filaments — v2 attaches
  the barbed end (end1, `uVec` OUTWARD) to the node; v1 attaches the barbed end (end2, `uVec` INWARD).
- **Effect on the gate:** inverts `rodDotFil` (and `motDotFil`) for a node's OWN filament ⇒ v2's outward myosins
  pass `rodDotFil ≥ 0` on their own outward filament (measured `+0.701`); v1's pass it only on an inward filament.
- **v1 + v2 file:line:** v1 gate `MyoMotor.java:388` (`rodDotFil`), polarity source `FilSegment.java:3986-3987`
  + node-attach `FilSegment.checkForminBinding:2367` (barbed=end2 at node). v2 gate
  `BindingDetectionSystem.java:82-83`, polarity source `DerivedGeometrySystem.java:16-17`/`16-20` + node-attach
  `TestBScprHarness.placeAimedChain:385-391` (barbed=end1 at node) + the polymerization convention
  (`INC6C_POLYMERIZATION_FINDINGS.md`).
- **Candidate fix directions (for the planner, not evaluated here):** make v2's node-attached filament present
  the same polarity v1 does at the gate — either (a) flip the node attachment to the v2 *pointed* end so the
  node-filament `uVec` points inward like v1, or (b) negate the filament axis the bind gate sees for
  node-nucleated filaments. Either must be **regression-tested against gliding / contractile / dimer /
  minifilament / growth** (all of which currently rely on v2's self-consistent `uVec=(e2−e1)` and must stay
  byte-unchanged) and validated to reproduce v1's clean-coalescing twoNodeFormin behavior.

## Bottom line
v2 admits wrong-orientation node-myosin self-bindings because the **barbed-end convention is flipped** (v2 node =
end1/uVec-outward vs v1 node = end2/uVec-inward), which **inverts the `rodDotFil ≥ 0` polarity gate** for a node's
own filament. The gate formula + thresholds are identical; only the filament polarity the gate sees is inverted.
Empirically, v2's self-captures carry `rodDotFil = +0.701` (admitted) where v1's flipped convention gives
`−0.701` (**rejected; v1-would-admit 0/2**). Prior assays passed because their filaments aren't node-coupled, so
the convention is a free relabeling. This is **(i) the gate/convention**, not **(ii) node head orientation**
(v2's heads point outward, but that alone doesn't admit the bind under v1's inward polarity). **DIAGNOSTIC ONLY —
nothing fixed; `BoA-v1ref` byte-clean.**
</content>
</invoke>
