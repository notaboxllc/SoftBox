# Increment 6c — faithfulness fix: port v1's node-held binding exclusion — FINDINGS

**Date:** 2026-06-18. **Status:** **Rule ported (v1-faithful, tip-only); self-capture REDUCED; cross-capture +
approach SURVIVE; regressions byte-unchanged; CPU≡GPU agree.** A **residual self-capture persists on OUTER
segments — the flagged GEOMETRY caveat — reported, NOT fixed** (a separate planner decision). Additive change
to `BindingDetectionSystem` (new overloads) + Test B wiring; existing bind methods + all other assays +
production byte-unchanged; `BoA-v1ref` byte-clean.

## What was restored
v1 excludes any **node-held** filament segment from myosin binding (`BoA-v1ref/boxOfActin/MyoMotor.java:391-392`,
`if (FilSegment.soaNodeAtEnd2[filId]) return;` — the rule jba remembered, originally `&& myNode`). v2's
`reachTestDistSq` dropped this in inc 4a (correct then — no nodes) and never restored it (audit:
`INC6C_V1_SELFCAPTURE_AUDIT_FINDINGS.md`). Restored as a **data-driven, tip-only** guard.

### The guard (port, not redesign)
New `BindingDetectionSystem` overloads `bruteReachableNodeAware` / `bindNearestNodeAware` add ONE line in the
candidate loop:
```java
if (seedNode.get(s) >= 0) continue;   // v1 nodeAtEnd2 exclusion — node-held tip, not bindable
```
- **Tip-only, faithful to v1's per-segment `nodeAtEnd2`.** v2 carries `seedNode[s] ≥ 0` on **exactly the
  node-held tip** (verified: `placeAimedChain` tags only `i==0`; warm-start seeds are single-segment tips;
  nucleation births tag the born tip; `GrowthSystem.splitWire` sets split children `seedNode = -1`). So the
  excluded set = the node-held tips, exactly as v1 excludes only the barbed segment. **Outer / split / released
  (`seedNode < 0`) segments stay bindable** ⇒ cross-capture on the overshoot survives.
- **Additive, no-op-when-not-node-held.** The original `bruteReachable` / `bindNearest` are byte-unchanged;
  gliding / contractile / Test A / motor / stroke call them and are unaffected (their filaments are not
  node-held). Only node-bearing binding (Test B) calls the node-aware overloads. No new kernel, no atomics, no
  force-law change.

## Validation — Test B′ (`-aimed`), before vs after the rule
| metric (capture-phase) | before (no rule) | **after (rule)** |
|---|---|---|
| self-capture transmitted force | 20.0 pN | **12.4 pN** |
| cross-capture transmitted force | 14.5 pN | 11.6 pN |
| **self / cross force ratio** | **1.38** | **1.07** |
| self-capture count | 4.63 | **2.88** |
| cross-capture count (avg / peak) | 3.49 / 8 | 2.92 / 7 |
| **bound on a node-held TIP (`seedNode≥0`)** | (n/a) | **0** ✓ rule fires |
| initial approach (start→min) | 0.176 µm | **0.174 µm** (EXCEEDS noise) ✓ |
| CPU≡GPU windowed avgBound | 2.50=2.50 | **1.90=1.90** ✓ aggregate-agree |

- **The rule FIRES correctly:** **0** motors bound to a node-held tip (the v1 exclusion holds).
- **Self-capture REDUCED** (force 20.0→12.4 pN, ratio 1.38→1.07, count 4.63→2.88): the tip-capture portion
  (~38 %) is now excluded.
- **Cross-capture + the beyond-noise approach SURVIVE** (the partner still captures the aimed filament's OUTER
  `seedNode<0` overshoot segments; approach Δ=0.174 µm ≈60× noise) — `STAGE 1 demonstrates SCPR
  capture-and-pull`.

## The GEOMETRY CAVEAT — residual self-capture persists (PAUSE + REPORT, not fixed)
Self-capture did **not collapse to ≪1**; a residual remains (self/cross ≈ 1.07). The diagnostic
(`diagnoseSelfCapture`) pins it exactly:
```
[diag] bound on node-held TIP (seedNode>=0; rule MUST exclude => 0): 0
[diag] residual SELF-captures (all on OUTER seedNode<0 segments): 2; mean dist from own node=0.1243 µm,
       max=0.1310 (own-myosin reach ~ 0.1830 µm)
[diag] cross-captures (on the partner's outer segments): 1
```
**Every residual self-capture is on an OUTER (`seedNode<0`) segment, ~0.124 µm from the own node — within the
own-myosin reach (~0.183 µm = NODE_RADIUS + ROD+LEVER+HEAD + myoColTol).** The node's own articulated myosins
reach ~0.18 µm and so capture the **2nd–3rd segments** of their own filament, which are NOT node-held tips and
are therefore (faithfully) not excluded.

**This is a v2 GEOMETRY divergence, NOT a rule miss.** v1's exclusion is *also* tip-only (only the barbed
segment carries `nodeAtEnd2`), so v1's own myosins would *likewise* see outer segments — UNLESS v1's
node-myosin reach / placement, or the rapid formin **release** (which clears the whole filament off the node and
lets it drift away), keep the own myosins off the outer segments in jba's v1 scenes. Per the discovery
boundary, the residual is **reported, not fixed**: no geometry hack, no whole-filament exclusion (that would
break cross-capture and diverge from v1's per-segment rule). The candidate levers for the planner:
1. **The force-dependent formin RELEASE** (v1 `forminCanHold` Bell → `seedNode → -1`; Test B set
   `detachRate = 0`) — the flagged immediate follow-on. In v1 a held filament is short-lived on the node; once
   released it drifts free of the own shell, so own-myosin outer-segment capture is transient. This likely
   accounts for most of the v1↔v2 gap and is the natural next port.
2. **Node-myosin reach / placement** vs the filament — a pure-geometry question (does v1's geometry keep own
   myosins from reaching the 2nd–3rd own segments?), separable from the rule.

## Regression (byte-unchanged)
Original `bruteReachable`/`bindNearest` untouched ⇒ callers unaffected: **contractile assay PASS, protein-node
Stage A PASS** (re-run). Gliding/motor/stroke use the original path (byte-unchanged by construction).

## `seedNode` / `nodeAtEnd2` — the THIRD role (recorded)
`seedNode ≥ 0` (v1 `nodeAtEnd2`) now has **three** roles in v2, the third newly restored:
1. **Nucleation bond** — which node nucleated/holds this tip (B2).
2. **The tether** — the elastic node-center↔tip spring (`NodeNucleationSystem.seedTether`).
3. **Binding exclusion** — a node-held tip is NOT a myosin binding candidate (this fix; v1
   `MyoMotor.checkFilSegCollision:391`). **This third role was the one the node recon missed**
   (`INC6_NODE_RECON.md:128,136` captured roles 1–2 only — which is how the inc-4a port slip survived).

## Out of scope (not built here)
The force-dependent formin **release** (`forminCanHold` → `seedNode→-1`; the SCPR "Release" — the immediate,
separable follow-on; the rule alone preserves cross-capture on outer segments); whole-filament exclusion; any
geometry/reach change; the chain-walk exclusion variant; >2 nodes / ring; the overrun fix (turnover/depoly).

## Verdict
v2's bind path now excludes node-held **tip** segments, **v1-faithful** (`MyoMotor.java:391-392`); the
internal-vs-net patch is replaced by the real rule. Self-capture force drops (20→12.4 pN, ratio 1.38→1.07) and
cross-capture + the beyond-noise approach survive; prior assays byte-unchanged; CPU≡GPU agree. The remaining
self-capture is a **flagged geometry residual** on outer segments (own-myosin reach > tip), most likely closed
by the **force-dependent formin release** (the next piece) — reported, not fixed.
