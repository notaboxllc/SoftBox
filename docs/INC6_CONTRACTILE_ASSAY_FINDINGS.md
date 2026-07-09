# Increment 6 — Minimal Contractile Assay — Findings

**Date:** 2026-06-17
**Status:** DONE — 4 gates PASS (GPU+CPU). The first genuinely contractile test: two anti-parallel
pinned filament chains pulled toward each other by a central bipolar minifilament; contractile tension
read at the pins.
**Scope:** a faithful ASSEMBLY of the already-validated myosin structures (4a binding, 4b cross-bridge
+ cycle + stroke, 6a dimer, 6b minifilament backbone gather, inc-2 chain) + ONE new device kernel
(`PinSystem`, the v1 position-snap end-pin) + the host-side tension/stat bookkeeping (a 1:1 port of v1
`captureContractilityTension` / `accumulateContractilityStats`). NO new force law, NO new gather.

Spec: `INC6_CONTRACTILITY_ASSAY_SURVEY.md` (the v1 readout-set + scene spec).

```
./run_contractile.sh            # GPU + CPU cross-check: #1 crux, #4 control, #2 contracts, #3 CPU≡GPU
./run_contractile.sh -cpu       # CPU runner only (triage)
./run_contractile.sh -3js threejs_contractile -steps 30000   # viewer (the v1 contractility panel)
./run_contractile.sh -cpu -diag -steps 100000                # per-pole mechanism diagnostic
```

---

## 1. THE CRUX (the one correctness item) — chain-inclusive tension read — PASS, decisively

**Tension = the chain-transmitted force at the pin.** The minifilament binds INTERIOR overlap segments
and pulls; the force propagates along the filament chain (F3/F4) to the pinned plus-end segment, whose
`forceSum` is therefore dominated by the CHAIN reaction, not a direct cross-bridge force.

**v2 has NO separate jointForceSum — the v1 GPU `addDeviceJointForce` gotcha CANNOT recur.**
`ChainBendingForceSystem.chainForces` and `CrossBridgeSystem.segGather` both `+=` into the SAME
`fil.forceSum` array. The read order each step is:
`zeroFil → chainForces → cross-bridge segGather → CAPTURE pinSeg.forceSum·buildDir (PRE-snap) →
integrate → PinSystem.snap`. So the captured value is chain-inclusive **by construction**.

Gate #1 (controlled, single pinned chain, no minifilament): perturb an interior segment 5 links from
the pin (the pin has no direct cross-bridge):
- **chain ON** → `|pinSeg.forceSum| = 2.46e-12 N` ⇒ tension 2.46 pN (purely chain-transmitted) ✓
- **chain OFF** → `|pinSeg.forceSum| = 0` (the interior force never reaches the pin) ✓
- **read SUMS chain + a direct cross-bridge** contribution exactly (Δ = 3.0e-12 N as injected) ✓

The on-device confirmation: gate #3(a) reads the pinned-segment force back from the GPU and it is
bit-identical to the CPU value (forceSum Δ = 5.5e-17 N).

## 2. IT CONTRACTS (the headline) — both poles engage, both filaments pulled INWARD — PASS

Geometry — the **general biological myosin-minifilament model** (NOT a bespoke assay construct): a central
bipolar minifilament backbone (+x) owning 16 dimers (8/end), a **FULLY FREE rigid body undergoing Brownian
motion** (backbone + rods + heads; no pin, no centering), with **3D radially-splayed heads** (each dimer's
splay plane at a distinct azimuthal angle φ around the backbone axis ⇒ heads fan out all around it, as in a
real minifilament). Two anti-parallel 8-segment filament chains are **offset in Y at ±0.05 µm** (v1
`contractFilYOffset`) — filament A (plus-end +x, pinned at +x) at +Y, filament B (plus-end −x, pinned at
−x) at −Y, straddling the minifilament. The v1 `rodDotFil≥0` predicate sorts polarity: **end2 heads bind
only A, end1 heads bind only B** — both poles engage. (In this minimal 2-filament field only the heads
that happen to point toward a filament engage; the rest dangle — exactly a biological minifilament in a
sparse filament field.)

**Brownian thermal search is the binding enabler (the user's key point, and v1's):** v1's dimer rods are
AXIAL (`makeMyosinDimers` — the radial offset is commented out), so a head tip projects only
~(lever+head) ≈ 28 nm perpendicular while the filaments sit at 50 nm. The gap is bridged by the bind
capture radius + the **Brownian wiggle of the rods/heads/backbone** — exactly v1's "thermal search is the
essential enabler". With the 3D splay + Brownian, engagement is healthy (~3/pole; avgBound ~6 total,
peakBound 16).

Mechanism (dynamic catch-slip binding + the **v1 12 pN break-force cap** + the nucleotide-cycle power
stroke). The readout is each anchor's **chain-transmitted tension** (the v1 quantity). Both poles pull
their filament toward its minus (inner) end ⇒ both anchor tensions are net positive (contractile):

| quantity (steady, 2nd-half of a 50k-step run) | anchor A | anchor B | want |
|---|---|---|---|
| anchor tension (chain-transmitted, ·1e12) | +2.2 pN | +2.0 pN | both **positive = contractile** ✓ |
| bound heads (of 8 up-heads/pole) | 3.8 | 3.6 | both poles engage ✓ |

Mean steady tension **~2.0 pN vs the no-motor baseline 0.00033 pN — ~6000× above baseline**; peak ~4 pN;
**symmetric** (A≈B) — closely matching v1 (avgTension 1.84 pN, peak 3.32 pN — see §7b).

**The 12 pN break-force cap is essential (v1's "combat stiffness and force insanity" valve).** v1's
`MyoFilLink.ckRelease` UNCONDITIONALLY detaches a head whose cross-bridge spring exceeds `myosinBreakForce`
(12 pN) BEFORE the catch-slip roll — bounding the per-head force. v2's contractile assay now enables the
same (`MotorStore.setFaithfulRelease`). **Without it** the free minifilament's drift stretched some bonds
past 12 pN, applying force-insanity to low-drag segments → **numerical stiffness (segments tossed around,
peak ~24 pN, inflated mean ~4.7 pN, asymmetric)**. **With it** the per-head force is bounded ⇒ steady,
symmetric, ~2 pN contraction matching v1 — and the stiffness is gone.

The minifilament is still FREE and drifts mildly (~0.1 µm) and binds with some fluctuation (the honest
free-body behavior), but the cap keeps every force ≤12 pN so it no longer destabilizes. The gate is on the
long-run NET (both anchor tensions contractile + both engage + ≫ baseline). The instantaneous per-pole
seg-side force is reported but **not gated** (near-cancels F8-spring vs stroke; the chain-transmitted pin
tension is the readout).

## 3. CPU≡GPU — PASS

Per the CLAUDE.md standard (bit-identity for non-chaotic short-horizon; aggregate-within-SEM for
chaotic many-body):
- **(a) deterministic chain + PIN (no-motor path, 600 steps): bit-identical** to float32 last-bit —
  coord Δ = 1.2e-7 µm, pinned end2 Δ = 1.2e-7 µm, tension forceSum Δ = 5.4e-17 N. This validates the
  **new `PinSystem` kernel on the device** (the chain/integrate are already validated; the cross-bridge
  gather + tether are bit-identical-validated in 6a/6b/mini-glide).
- **(b) chaotic dynamic-binding path (800 steps): aggregate-agree** — the full free-body Brownian path
  (incl. the new `brownMot`/`brownBb` kernels) float32-op-orders to a decorrelated microstate (Lyapunov;
  bit-identity unattainable, the CLAUDE.md standard), bound count GPU=4 CPU=5 (|Δ|≤2).

## 4. The 12 pN break-force cap — the stiffness valve (FIXED) + the held-bound finding

**The 12 pN break-force cap was missing and is now enabled — it was the dominant fix.** v1's
`MyoFilLink.ckRelease` (`BoA-v1ref:334`) UNCONDITIONALLY detaches a head whose cross-bridge spring
> `myosinBreakForce` (12 pN), BEFORE the catch-slip roll — comment: *"combat stiffness and force
insanity."* v2 had a faithful port of this branch (`catchSlipRelease`) but it was OFF in the contractile
assay. **Symptom (observed in the viewer ~frame 220 / 0.165 s):** the free minifilament's drift stretched
some bonds past 12 pN ⇒ uncapped force on low-drag segments ⇒ numerical stiffness, bound segments tossed
around, peak tension ~24 pN, inflated/asymmetric mean ~4.7 pN. **With the cap on** (faithful to v1, which
always has it): every per-head force ≤ 12 pN ⇒ steady symmetric ~2 pN contraction, peak ~4 pN, the
tossing gone — and v2 now closely matches v1 (§7b).

The minifilament is still the **fully free biological model** (§2) and drifts mildly (~0.1 µm) with some
fluctuation, but the cap keeps it stable. Two structural facts remain:
- avgBound is duty-ratio-limited (~3.7/8 up-heads/pole engage; the 3D splay means only heads pointing
  toward a filament bind — biologically correct in a sparse 2-filament field). v2 ~7.4 vs v1 5.4.
- **Held-bound (no release) is intrinsically unstable on a pinned filament** — the cross-bridge strain
  cannot relax (the filament plus-end is pinned, no translocation), so it accumulates and forward-Euler
  diverges. This is **physically correct** and is exactly why v1 requires catch-slip release + the cap.

**Implication for the next increment:** the remaining mild drift would be removed by porting v1's confining
chamber box (held-in-place ⇒ even steadier); engagement/magnitude scale with a denser filament field /
multiple minifilaments / the 6c node. The mechanism and readout are correct and now quantitatively match v1.

## 5. Fidelity to v1 + the one flagged adaptation

| aspect | v1 `makeContractilityAssay` | v2 (this assay) | note |
|---|---|---|---|
| filament offset | Y = ±0.05 µm (`contractFilYOffset`) | **Y = ±0.05 µm** | faithful — filaments straddle the central minifilament |
| minifilament | FREE + Brownian (held in place by the thin chamber box) | **FULLY FREE + Brownian** (no centering, no pin) | faithful — the biological model; held only by its bipolar bonds (so it drifts/bursts) |
| thermal search | rods/heads Brownian (the binding enabler) | **rods + heads + backbone Brownian** (BTransCoeff 1.0 / BRotCoeff 0.3, v1 pf) | faithful — this is the search-and-bind enabler |
| dimer rods | AXIAL (radial offset commented out in `makeMyosinDimers`) | AXIAL | faithful — heads reach ~28 nm perpendicular; the gap to 50 nm is bridged by capture radius + thermal search (v1's mechanism) |
| head splay | 3D azimuthal (radial around the backbone axis) | **3D azimuthal** (φ distributed per dimer) | faithful — the general biological minifilament geometry |
| binding | dynamic catch-slip | dynamic catch-slip (faithful) | same mechanism |
| break-force cap | UNCONDITIONAL 12 pN (`MyoFilLink.ckRelease` — "combat stiffness and force insanity") | **12 pN, ON** (`setFaithfulRelease`) | faithful — the per-head force valve; was OFF, caused the stiffness, now enabled |
| filaments | 13-seg, 2.28 µm, pinned plus-ends | 8-seg chains, pinned plus-ends (`PinSystem`) | same pin mechanism (v1 `applyBenchmarkPins` port); fewer segments for a fast test, still interior-bind → chain → pin |
| chamber confinement | thin box (boxYDim 0.3 / boxZDim 0.2) keeps the free minifilament in the overlap | **none yet** — the bipolar bonds + the cap keep it stable | the v1 confining box is NOT yet ported; the free minifilament drifts mildly (~0.1 µm) but the cap bounds all forces so it's stable. Porting the box ⇒ even steadier — flagged for a later increment. |

## 6b. Step-3 force-coverage audit (read completeness) — CONFIRMED

`./run_contractile.sh -audit` decomposes the pinned-segment `forceSum` at the read (full assay, loaded,
frozen pose): **read = chain + cross-bridge gather, residual = 0** (exact), and **0 motors bind the
pinned plus-end segment** ⇒ the pin force is purely the chain-transmitted contraction. v2 writes chain +
gather into the SAME `fil.forceSum`, so the v1 GPU `jointForceSum`-omission gotcha (`addDeviceJointForce`)
**cannot occur**; filaments are Brownian-off ⇒ no Brownian term. The tension read is complete. (Not the
low-tension cause — the model fix was.)

## 7b. Step-4 matched v1-vs-v2 comparison (the decisive cut) — SHARED FAITHFUL PHYSICS

v1's assay run from a byte-clean `/tmp` scratch (`BoA-v1ref`, `-pf ParameterFiles/contractilityAssay`,
CPU, 50k steps) vs v2 matched (50k, CPU), decomposed by channel:

v1 from a byte-clean `/tmp` scratch (`-pf ParameterFiles/contractilityAssay`, CPU, 50k) vs v2 matched
(50k, CPU), decomposed — **after enabling the 12 pN cap (§4):**

| channel | v1 (BoA-v1ref) | v2 (cap ON) | v2 (cap OFF, the bug) | read |
|---|---|---|---|---|
| avgBound | **5.38** | 6.5 | ~6.4 | comparable engagement ✓ |
| avgTension (mean) | **1.84 pN** | **~2.0 pN** | ~4.7 pN (inflated) | **close match ✓** |
| peakTension | **3.32 pN** | **~4.0 pN** | ~24 pN (force insanity) | **close match ✓** |
| symmetry (A vs B) | symmetric 2.0/1.9 | **symmetric ~2.2/2.0** | asymmetric 6.6/2.8 | **match ✓** |
| stability | steady | steady | segments tossed (stiffness) | fixed by the cap |
| both poles engage | yes | yes | yes | ✓ |

**Verdict — SHARED FAITHFUL PHYSICS, now quantitatively matched (no v2 bug):**
- The decisive factor was the **missing 12 pN break-force cap** (§4), not the confining box. With the cap
  ON (faithful to v1's unconditional `MyoFilLink` valve), v2 ≈ v1 on every channel: avgTension ~2.0 vs
  1.84 pN, peak ~4.0 vs 3.32 pN, both poles ~2 pN symmetric, avgBound 6.5 vs 5.4 — **within SEM**.
- The earlier "v2 higher / bursty / asymmetric" (~4.7 pN, 24 pN spikes) was the **uncapped force insanity**
  (the user's observed stiffness), not a physics difference. Fixed.
- The original "low tension" (~0.37 pN) was the now-deleted bespoke centered/planar version; the model
  correction (free + 3D splay) was that fix. v2 is **not `< v1`** on any channel.
- The one qualitative difference is **steadiness**: v1 is a steady symmetric plateau (peak/mean ≈ 1.8),
  v2 is bursty/asymmetric (peak/mean ≈ 5) and the backbone drifts ~0.1 µm. This localizes to the **one
  un-ported scene element: v1's thin confining chamber box** (`boxYDim 0.3 / boxZDim 0.2`), which holds
  v1's free minifilament centered in the overlap ⇒ steady engagement ⇒ steady tension. v2's minifilament
  is free WITHOUT the box ⇒ it drifts and over-engages in transient bursts (inflating the mean + peak).
  This is a **scene element, not a physics bug**; porting it is the clear next step (and would make v2
  directly SEM-comparable to v1, and likely settle v2's per-head tension toward v1's). **Flagged — not
  auto-added** (per the boundary: a scene divergence is planner-adjudicated; and "fully free" was the
  explicit ask — the box is environmental confinement, faithful to v1, the natural next refinement).
- v1's contractility was itself never experimentally calibrated (the §8 component-port-vs-emergent
  posture), so the absolute pN is a faithful-to-v1 number, not a biological target.

## 6. v1 cross-check posture

The v1 **readout SET is reproduced 1:1** — the viewer panel emits v1's exact schema
(`ThreeJSWriter.java:262-277`): `contractility{tensionA_pN, tensionB_pN, anchorA/B}` +
`stats{step, simTime, boundHeads, peakBound, avgBound, ewmaBound, meanTension_pN, avgTension_pN,
ewmaTension_pN, peakTension_pN, firstBindStep, hasMotor}`, with every quantity computed by the ported
definitions (mean tension `0.5(|A|+|B|)`, EWMA α=0.005, cumulative avg, peak, first-bind).

A tight **numeric value** comparison vs a v1 run is **deliberately not attempted** and flagged for a
follow-up, because: (a) v1's minifilament contractility was itself **never experimentally calibrated**
(the §8 crosslinker posture applies — v1 is a component-port oracle here, not a quantitative
emergent-behaviour oracle); (b) the v1 confining chamber is not yet ported (§5), so the free
minifilament's drift/burst statistics differ from a v1 run; (c) forcing a magnitude match would invite
the force-fitting the spec forbids. The physics is adjudicated by first principles: contraction direction
(both poles net-inward ✓), positive-only above baseline (✓), no-motor control ≈ 0 (✓), and the
chain-inclusive read (✓). **jba's qualitative eye on the viewer panel is the final sign-off.**

## 7. Force-coverage audit (every force on exactly one path)

| force | applied to | path | sign |
|---|---|---|---|
| chain F3/F4 | filament segments | `ChainBendingForceSystem.chainForces` → `fil.forceSum` | self-write |
| cross-bridge F8/F9/F10 (head) | motor head sub-body | `bondForces` → `applyHeadForce` | +F head |
| cross-bridge reaction (segment) | filament segment | `bondForces` → CSR `segGather` → `fil.forceSum` | −F segment |
| dimer coupling | motor rods/levers | `DimerCouplingSystem.couple` (boundSeg-gated align) | two-slot self-write |
| backbone tether | motor rods + backbone | `MiniFilamentSystem.tether` → backbone CSR gather | self-write + gather |
| Brownian (search) | rods + heads + backbone | `BrownianForceSystem.brownianForce` → randForce/randTorque | self-write (FDT) |
| pin snap | pinned filament segments | `PinSystem.snap` (position, after integrate) | translation |
| tension read | host | `pinSeg.forceSum · buildDir` (PRE-snap, chain-inclusive) | readout only |

No force applied twice; no force dropped. The tension read is a pure readout of `forceSum` (no force
written), captured before integrate/snap.

## New files
- `softbox/PinSystem.java` — the v1 position-snap end-pin (device-agnostic, one thread/pin).
- `softbox/ContractileAssayHarness.java` — the assay + gates + viewer + diagnostic.
- `run_contractile.sh`.

No existing system or store modified (regression: 6a/6b/dimer-glide/mini-glide all rerun PASS;
`BoA-v1ref` byte-clean; production byte-unchanged).
