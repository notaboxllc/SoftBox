# Increment 6c — v1 reference audit: does v1 prevent node-myosin self-capture? — FINDINGS

**Date:** 2026-06-18. **Mode:** READ-ONLY v1 oracle audit (`BoA-v1ref`, frozen at `8e23789`). **Nothing edited**
— not `BoA-v1ref`, not v2. No v1 run needed (the static trace is conclusive).

## VERDICT
**v1 DOES prevent node-myosin self-capture — Category 1: an explicit binding-candidate EXCLUSION RULE**
(`nodeAtEnd2`), with the candidate set otherwise global (so the rule is the *sole* filter). **v2 is UNFAITHFUL:
it dropped this exclusion** during the inc-4a binding port (before any node entity existed) and never restored it
when nodes arrived in inc 6c. jba's memory is correct — and the v1 comment even names the rule he remembers.

## The rule (exact v1 citation)
`BoA-v1ref/boxOfActin/MyoMotor.java:391-392`, inside `checkFilSegCollision(motorId, filId)` — the per-pair
myosin↔filament bind predicate (the exact method v2 ported as `BindingDetectionSystem.reachTestDistSq`):

```java
// Formin-bound filament excluded (dead `&& myNode` branch from prior code not ported — unreachable)
if (FilSegment.soaNodeAtEnd2[filId]) { return; }
```

A filament segment whose **barbed end (end2) is held by a node's formin** (`nodeAtEnd2 == true`) is **excluded
from ALL myosin binding** — `checkFilSegCollision` returns before the geometry/reach test. The bind gates in this
method are, in order: `motDotFil ≥ myoMotorAlignWithFilTolerance` (391 align gate) → `rodDotFil ≥ 0` (388 rod
gate) → **`!soaNodeAtEnd2` (the node-held exclusion)** → perpendicular drop + `alpha∈[0,1]` + `conDist < myoColTol`.

**The comment is decisive for jba's question:** the rule **originally had an own-node form** — `if (soaNodeAtEnd2
&& myNode) return;` — i.e. *skip a filament held by MY OWN node* (the self-capture rule jba remembers). That
`&& myNode` branch is now "dead / not ported / unreachable"; the live rule excludes **every** node-held filament
(a strict superset that still prevents self-capture, and additionally prevents cross-capture of a *still-held*
filament).

## The `nodeAtEnd2` lifecycle (set / release / transfer)
- **Field:** `FilSegment.java:242` `boolean nodeAtEnd2`; mirrored to the bind SoA each step at
  `FilSegment.java:39,70` (`soaNodeAtEnd2[i] = fs.nodeAtEnd2`).
- **Set (formin hold):** `FilSegment.checkForminBinding():2367-2384` — when a filament's barbed end (end2) lands
  within a node's radius, `nodeAtEnd2 = true; end2Node = curNode; curNode.filamentOn()`. Also `linkEnd2Node()`
  `:2806-2810`. The formin attaches at the **node centre** (`end2PAttachPt.zero()`).
- **Released (→ becomes bindable):** the formin lets go stochastically via the **force-dependent Bell release**
  `FilSegment.forminCanHold():2619-2633` (`releaseProb = forminRelease·biochemDeltaT`, increased under tension /
  suppressed under compression); on release `nodeAtEnd2 = false` (`:1161-1170`, `releasedByFormin()`).
- **Transferred on split:** `FilSegment.java:334` `if (splitFromFil.nodeAtEnd2) transferEnd2Plasmid(...)` — the
  node-held flag follows the barbed-end segment that stays at the node (per-segment, like v2's tip `seedNode`).

## The candidate set is GLOBAL — the rule is the only filter
`MotorBindGrid3D` (a 3D spatial hash) feeds spatially-near motor↔filament pairs straight into
`MyoMotor.checkFilSegCollision` (`MotorBindGrid3D.java:260`). There is **no node-scoping** of a node-myosin's
candidate set: a node's own filaments ARE candidates; the ONLY thing that stops the bind is `nodeAtEnd2`.
`Myosin`/`MyosinDimer` carry an `ownerNode` (`MyosinDimer.java:16,338-341`) — but it is used **only** for the
node-surface-cohesion tether gate (`tethersOnDevice()`, `:353`), **never referenced in the bind decision**. So
there is no own-node-id compare in the live bind path (consistent with the dead `&& myNode` comment).

⇒ Categories 2 (placement geometry), 3 (a gate that fails own-filaments), 4 (role separation) are **NOT** how v1
does it. v1 nodes both nucleate AND capture on the same body (no role split), the candidate set is global, and the
bind gates (`motDotFil`, `rodDotFil`) are the same ones v2 ported and pass own-node geometry. The **rule** does it.

## Why v2 self-captures (the divergence, precisely)
1. v2's `BindingDetectionSystem.reachTestDistSq` ports `checkFilSegCollision`'s align gate, rod gate, alpha, and
   `conDist` — but **omits the `if (soaNodeAtEnd2) return;` line**. This omission was *correct* in inc 4a (the
   binding port predates the node entity — there were no node-held filaments to exclude), but it was **never
   restored** when nodes + nucleation arrived (inc 6c). The node recon noted `nodeAtEnd2` only for nucleation +
   the tether (`INC6_NODE_RECON.md:128,136`), **not** its binding-exclusion role — so the port missed it.
2. v2 already has the **exact analog of `nodeAtEnd2`**: `NodeNucleationStore.seedNode[s] ≥ 0` ⇔ the filament's tip
   is held by a node (the v1 "node at the barbed end"); `< 0` ⇔ free / released. The bind path simply ignores it
   (binding is `seedNode`-agnostic — the Gate-0 finding). So v2 binds node-held filaments (self AND cross).
3. **Deeper divergence (compounding):** in v1 a filament is **unbindable while held** and must be **released by
   the formin** (force-dependent) before any myosin — own or foreign — can capture it. v2's Test B/B′ kept
   filaments **permanently tethered** (`detachRate = 0`) **AND** bindable — divergent on *both* counts. v1's SCPR
   is nucleate→hold(unbindable)→**release**→capture; v2 currently is nucleate→hold(still bindable)→capture.

## Is v2 a faithful port, or missing something?
**Missing something — a real, citable divergence**, not "faithful." The missing piece is the `nodeAtEnd2`
binding-candidate exclusion (`MyoMotor.java:391-392`). It is load-bearing for the contractile **ring**: without
it, every node's own nucleated/held filaments are self-capture targets for its own myosins (and held foreign
filaments are captured before release), which v1 does not do — the ring would inherit a self-interaction v1
never had.

## Scoping a faithful v2 port (for the planner — NOT executed here)
- **The rule:** in v2's bind candidate test (`reachTestDistSq` / `bruteReachable` / `bindNearest`), skip a segment
  whose filament is **node-held**. The per-segment analog of v1's `soaNodeAtEnd2` is "this segment's filament is
  currently tethered to a node": for the tip, `seedNode[s] ≥ 0` directly; for an outer/split segment, resolve via
  the chain to the tip (the existing `filNodeOf` walk) — matching v1, where only the barbed segment carries
  `nodeAtEnd2` (so v1 too leaves *outer* segments bindable; the faithful v2 exclusion is the **tip / node-held
  segment**, not the whole filament). A 1-line guard in the predicate, data-driven (no new kernel).
- **The lifecycle (to make capture possible at all, faithfully):** a held filament must be **releasable** —
  port the formin release (`forminCanHold`, force-dependent) so `seedNode → -1` frees the filament for capture
  (v2's `setDissolveParams` already exists but Test B set it to 0; the faithful value + the force dependence are
  the port). Then SCPR is nucleate→hold(unbindable)→release→capture, as in v1.
- **Caveat for the planner (geometry, not a v1 rule):** v1's exclusion covers only the barbed (node-held) segment;
  whether porting just the tip exclusion fully matches jba's "no self-capture in v1" depends on node-myosin REACH
  — in v1 a node's own myosins (surface, reach ≈ nodeRadius+myoColTol) primarily reach the central barbed
  (excluded) segment, so the rule suffices there; v2's `-aimed` REACH/geometry should be checked against that.
  This is a v2-side geometry question, separable from the (now-answered) faithfulness question.

## Bottom line
- **v1:** prevents node-myosin self-capture by an **explicit rule** — `MyoMotor.checkFilSegCollision:391-392`
  excludes any node-held (`nodeAtEnd2`) filament from binding; the comment confirms the original own-node
  (`&& myNode`) form. Candidate set global; `ownerNode` not used in the bind. Held ⇒ unbindable until the formin
  releases (`forminCanHold`).
- **v2:** **missing it** — `reachTestDistSq` dropped the `nodeAtEnd2` line (correctly in inc 4a; not restored in
  inc 6c), and Test B kept filaments permanently held *and* bindable. The fix is a v1-faithful port: exclude
  node-held (tip `seedNode ≥ 0`) segments from binding + make the hold releasable. **The internal-vs-net argument
  was a v2-only patch for a missing v1 rule, not a v1 behavior.**
- **No v1 run required** — the trace is static and unambiguous.
