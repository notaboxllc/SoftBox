# Increment 6 — General In-Vitro-Chamber Containment Box — Findings

**Date:** 2026-06-17
**Status:** DONE — 7 contractile-assay gates PASS (GPU+CPU) + 8 prior harnesses re-run bit-identical.
A general, entity-agnostic containment primitive (the in-vitro experimental chamber) confining all
players within a configurable box; the contractile assay consumes it with **zero regression**.

```
./run_contractile.sh            # GPU + CPU: #1 crux, #4 control, #5 no-op, #6 general, #7 box CPU≡GPU, #2 contracts, #3 CPU≡GPU
./run_contractile.sh -cpu       # CPU runner only (triage)
./run_contractile.sh -cpu -drift 50000   # matched box OFF vs ON: drift + tension comparison
```

---

## 1. What this is — and what it is NOT

A **general, named, entity-agnostic containment system** (`ContainmentSystem`) — the
simulation-domain boundary, a stand-in for the **in vitro experimental chamber** (coverslip /
flow-cell) that bounds every in vitro assay. It confines **positions, not class identities**: one
kernel over a `RigidRodBody`'s pose + drag + accumulator arrays, invoked once per store exactly as the
shared integrator / Brownian / derive systems are. So it confines filament segments, motor sub-bodies,
minifilament backbones, and (future) nodes with the **same code** (entity-agnostic — gate #6).

It is **NOT the membrane subsystem.** The deferred dynamic cell cortex (StickyNode + NodeLink +
iterative relaxation, increment 7) is a distinct, later thing. This is the simple **static experimental
boundary** — a reusable primitive every future in vitro assay (contractile, gliding, …) consumes.

**The safety property — no-op when not binding.** A body fully inside the inset box yields zero wall
penetration ⇒ the accumulators are **not touched at all** (no `+= 0`, no write). So adding the system
to any in-bounds harness is **bit-identical** to not having it (gate #5). This is the regression guard,
by construction.

## 2. Faithful to v1 — the free-body box law (and the one flagged non-port)

v1 (`BoA-v1ref`) confines all body types through a **shared detection** + a **per-type force**. Both
pieces ported faithfully; the detection is the entity-agnostic part.

**Detection** — `Chamber.amICollidingOuter` (`Chamber.java:125-138`), per endpoint `d` (box centered at
origin): for each axis `forceUVec_i = sign(d_i)·(halfDim_i − R) − d_i`, then **zero any axis whose push
still points inward** (`sign(forceUVec_i) == sign(d_i)` ⇒ endpoint safely inside that inset wall);
`delta = |forceUVec|`, then unit. This is where the no-op-inside property lives: inside ⇒ delta 0.

**Force** — `MyoMiniFilament.checkOuterBugCollision` (`MyoMiniFilament.java:546-560`), per penetrating
endpoint: `mag = nodeFracMove·1e-6·delta·bTransGam.x / collisionDeltaT`, `F = mag·forceUVec` (Newtons),
applied **at the endpoint** via `incForceSum(F, endpoint)` ⇒ `forceSum += F` and `torqueSum += r×F` with
`r = (endpoint − center)·1e-6` (µm→m). v1 fires this only every `collisionCheckInt = collisionDeltaT/dt
= 10` steps (the collision cadence) — reproduced by a step-gate on `counts[1]` inside the kernel, so the
GPU TaskGraph stays a fixed graph and the CPU runner matches bit-for-bit (a no-op on non-check steps).
v1 defaults: `nodeFracMove = 0.5`, `collisionDeltaT = 1e-4`, minifilament `radius = 0.005 µm`.

The `/collisionDeltaT` in the force cancels the integrator's `·dt` ⇒ a **dt-scaled fractional position
correction** toward inside (the FilLink/crosslinker `fracMove` pattern — drag in both the force
denominator and the integrator is v1's design, not a double-count).

**NOT ported (flagged — `abstract from the second instance`):** v1's SEPARATE FilSegment / ProteinNode
box force (`FilSegment.bugForcesFromInside`: `0.1·min(fturn, ftrans)`, an extra **torque-drag clamp**).
It is **never exercised** by any current v2 assay — the contractile filaments are pinned + inset by a
0.10 µm margin, so a free filament never reaches a box wall. Implementing an un-exercised second force
law would add untested code; it is left for whenever a future assay actually drives a free filament/node
into a wall (the second instance). `ContainmentSystem` uses v1's **free-body** law (the one the drifting
minifilament/node actually feels). v1 also leaves individual `MyoMotor`s un-boxed (`MyoMotor.java:183`
the call is commented out) — only the minifilament **backbone** is actively confined; v2 matches this
(the contractile assay confines the backbone).

## 3. The seven contractile-assay gates — all PASS (GPU+CPU)

| # | gate | result |
|---|---|---|
| #1 | CRUX: chain-inclusive tension read | pin reads chain-transmitted force (2.46 pN); chain OFF ⇒ 0 ✓ |
| #4 | no-motor control | pinned tips held exactly; steady tension → 3.3e-4 pN ✓ |
| **#5** | **SAFETY (no-op inside)** | inside ⇒ accumulators untouched (=0); pushed out ⇒ inward force; **HUGE-box ≡ box-off BIT-IDENTICAL** over 400 dynamic steps (Δcoord/Δforce = 0, 0 mismatched bonds) ✓ |
| **#6** | **GENERAL (entity-agnostic)** | a filament segment / motor sub-body / minifilament backbone each placed past the +y wall is pushed back inward (one kernel, all stores) ✓ |
| **#7** | **box CPU≡GPU** | 8-body wall bed straddling all walls: force ΔF = 0.0 (exact), torque ΔT = 7.7e-34 N·m on ~1e-18 torques (float32 FMA last-bit) ⇒ bit-identical to printed precision ✓ |
| #2 | HEADLINE: it contracts (box ON) | A = 2.20 / B = 1.99 pN (both contractile), avgBound 6.53, peak 3.97, ~6400× the no-motor baseline ✓ |
| #3 | CPU≡GPU full | deterministic chain+pin bit-identical (Δ 1.2e-7 µm); chaotic dynamic-bind aggregate-agree (bound 6=6) ✓ |

## 4. Regression — 8 prior harnesses bit-identical PASS

Only **new file** `ContainmentSystem.java` + the contractile harness consume the box; no shared system
or store touched. All prior structure/glide harnesses re-run PASS, unchanged:
`dimer`, `minifil`, `dimerglide`, `miniglide`, `motorbody`, `xbridge`, `stroke`, `motor`
(+ gliding smoke). `BoA-v1ref` byte-clean; production byte-unchanged.

## 5. The drift finding (gate #3) — the box is a FAITHFUL QUIESCENT no-op here (report, don't paper over)

The matched **box OFF vs ON** comparison (CPU, 50k steps, identical scene/dt/coeffs) is
**bit-identical on every channel:**

| channel | box OFF | box ON | v1 ref |
|---|---|---|---|
| steady mean tension | 2.094 pN | **2.094 pN** | 1.84 |
| peak tension | 3.972 pN | **3.972 pN** | 3.32 |
| avgBound | 7.43 | **7.43** | 5.38 |
| max \|y\| endpoint | 0.070 µm | 0.070 µm | (wall 0.145) |
| max \|z\| endpoint | 0.045 µm | 0.045 µm | (wall 0.095) |

**The box never fires in the cap-ON scene.** The free minifilament's residual drift (backbone center
`x ∈ [−0.119, +0.018]`, `y ∈ [−0.054, +0.010]`, `z ∈ [−0.040, +0.009]` µm over 50k) is **dominated by
the AXIAL (x) direction**, where the box is 4.0 µm wide (half-wall 1.995 µm — **17× the drift**) and so
cannot tighten it. The lateral (Y/Z) motion the thin box DOES tightly confine (walls 0.145 / 0.095)
stays at endpoint 0.070 / 0.045 µm — the **12 pN break-force cap already keeps the minifilament well
inside the chamber**. So the box neither tightens the (axial) residual nor perturbs the (already
within-SEM) tension match: **box ON ≡ box OFF, bit-identical** — the strongest possible "does not
regress the landed milestone."

This is consistent with the milestone history and the task framing: the cap (commit c7a2257) was the
dominant steadiness fix; the box was wanted **for its own sake as the general containment primitive**,
not as the steadiness fix. The honest outcome is that, in this cap-stabilized scene, the chamber is
**quiescent** — present, faithful, and ready to catch any wall excursion (gate #6 proves it fires on
real assay bodies the instant one crosses a wall), but the minifilament simply never approaches the
walls. A tighter chamber, a denser/less-stable scene, or a cap-OFF regime would engage it.

## 6. Force-coverage audit (containment)

| force | applied to | path | sign |
|---|---|---|---|
| wall force F1 (per penetrating endpoint) | the body | `ContainmentSystem.confine` → `forceSum` `+=` (self-write) | inward `+mag·f̂` |
| wall torque (per penetrating endpoint) | the body | `ContainmentSystem.confine` → `torqueSum` `+=` (self-write) | `r×F` |

One thread per body, each writing only its own slot (`+=`, race-free, no atomics, no `KernelContext`).
Zero force/torque when inside (no write). Identical on GPU TaskGraph and `-cpu` runner.

## New files
- `softbox/ContainmentSystem.java` — the general entity-agnostic in-vitro-chamber box.
- `softbox/ContractileAssayHarness.java` — modified: consumes the box on the free backbone +
  gates #5/#6/#7 + the `-drift` matched comparison. (No other file touched.)
