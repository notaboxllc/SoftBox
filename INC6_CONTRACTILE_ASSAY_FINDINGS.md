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

Geometry (v2 adaptation of v1 `makeContractilityAssay`, flagged — see §5): a central bipolar
minifilament backbone (+x) owning 16 dimers (8/end, 6b-splayed), with two anti-parallel 8-segment
filament chains at the +z up-head plane — filament A (plus-end +x, pinned at +x) over the end2 up-head
field, filament B (plus-end −x, pinned at −x) over the end1 up-head field. The v1 `rodDotFil≥0`
predicate sorts polarity: **end2 heads bind only A, end1 heads bind only B** — both poles engage.

Mechanism (dynamic binding + the nucleotide cycle drives the repeated power stroke; catch-slip release
sheds strain ⇒ stable; the backbone is a fixed central anchor — the clean isometric setup):

| quantity (steady, 60k-step average) | anchor A | anchor B | want |
|---|---|---|---|
| force ON filament, x-component | −5.7e-14 N | +1.13e-13 N | A −x / B +x (**both inward** ✓) |
| anchor tension (Dot(force, inward)·1e12) | +0.057 pN | +0.113 pN | both **positive = contractile** ✓ |
| bound heads (of 8 up-heads/pole) | 1.28 | 1.44 | both poles engage ✓ |

Mean steady tension **0.085 pN vs the no-motor baseline 0.00033 pN — 261× above baseline.** Both
poles pull their filament toward its minus (inner) end → both anchors register positive contractile
tension. The bound heads stroke (200 power strokes / 100k steps; 70–88% ADP occupancy = post-stroke
cocked-and-pulling).

**The contractile signal is weak (~0.1 pN) and requires a long average to converge** — see §4.

## 3. CPU≡GPU — PASS

Per the CLAUDE.md standard (bit-identity for non-chaotic short-horizon; aggregate-within-SEM for
chaotic many-body):
- **(a) deterministic chain + PIN (no-motor path, 600 steps): bit-identical** to float32 last-bit —
  coord Δ = 1.2e-7 µm, pinned end2 Δ = 1.2e-7 µm, tension forceSum Δ = 5.5e-17 N. This validates the
  **new `PinSystem` kernel on the device** (the chain/integrate are already validated; the cross-bridge
  gather + tether are bit-identical-validated in 6a/6b/mini-glide).
- **(b) chaotic dynamic-binding path (800 steps): aggregate-agree** — float32 op-ordering decorrelates
  the microstate (Lyapunov; bit-identity unattainable, the CLAUDE.md standard), bound count GPU=2 CPU=2.

## 4. The weak-signal finding (surfaced, not force-fit)

The net contractile tension from this **minimal single-small-minifilament** geometry is small (~0.1 pN)
and noise-dominated at short horizons:
- Only ~1.5 of 8 up-heads per pole are bound at once (avgBound ≈ 2.8 total). The duty ratio is low
  because **a released head must rebind**, and there is **no fresh-motor reservoir** (unlike the gliding
  strip's thousands of motors). After a head strokes ~7 nm and releases (catch-slip), it relaxes to a
  rest orientation; the bind alignment gate (`motDotFil ≥ alignTol`) admits it only within a narrow
  window, so rebinding is slow.
- With ~1.5 bound heads each generating ~3–7e-14 N, the per-step net fluctuates in sign; the **mean
  converges to clean bilateral contraction only over ≳40k steps** (verified: 40k and 100k both give
  A −x / B +x with both tensions positive; ≤30k windows are noisy and can flip pole B's sign). Gate #2
  uses a 60k-step average.
- **Held-bound (no release) is intrinsically unstable on a pinned filament** — the cross-bridge strain
  cannot relax (both the backbone and the filament plus-end are anchored, the filament cannot
  translocate), so it accumulates and the forward-Euler integration diverges. This is **physically
  correct** (a myosin that can neither move its substrate nor detach builds unbounded strain) and is
  exactly why v1 requires catch-slip release. So the stable, faithful path is dynamic binding, and the
  duty-ratio-limited avgBound is the cause of the weak signal — a real property of the minimal geometry,
  not a bug.

**Implication for the next increment:** a strong, sharp contractile plateau needs more co-engaged heads
— a fuller minifilament carpet (engaging the down-heads too, e.g. filaments on both ±z), multiple
minifilaments, or the protein-node carrier (6c). The mechanism and the readout are correct; the
magnitude scales with engaged-head count.

## 5. Geometry adaptation vs v1 (flagged)

| aspect | v1 `makeContractilityAssay` | v2 (this assay) | why |
|---|---|---|---|
| filament offset | Y = ±0.05 µm, 3D-splayed dimers reach both | both filaments in the +z up-head plane | v2's 6b minifilament splays dimers in the x–z plane (heads project ±z); placing both filaments in that plane is the faithful adaptation that lets both poles engage. Polarity is still sorted by `rodDotFil`. |
| minifilament | free, small thermal (centres itself) | fixed central anchor, Brownian off | the deterministic-test adaptation (CPU≡GPU bit-identical); the tether still couples the dimers to it. |
| binding | dynamic catch-slip (v1) | dynamic catch-slip (faithful) + bind reach widened to 12 nm | a released head must rebind after its ~7 nm stroke with no fresh-motor reservoir. |
| filaments | 13-seg, 2.28 µm, pinned plus-ends | 8-seg chains, pinned plus-ends (`PinSystem`) | same pin mechanism (v1 `applyBenchmarkPins` port); fewer segments for a fast test, still interior-bind → chain → pin. |

## 6. v1 cross-check posture

The v1 **readout SET is reproduced 1:1** — the viewer panel emits v1's exact schema
(`ThreeJSWriter.java:262-277`): `contractility{tensionA_pN, tensionB_pN, anchorA/B}` +
`stats{step, simTime, boundHeads, peakBound, avgBound, ewmaBound, meanTension_pN, avgTension_pN,
ewmaTension_pN, peakTension_pN, firstBindStep, hasMotor}`, with every quantity computed by the ported
definitions (mean tension `0.5(|A|+|B|)`, EWMA α=0.005, cumulative avg, peak, first-bind).

A tight **numeric value** comparison vs a v1 run is **deliberately not attempted** and flagged for a
follow-up, because: (a) the v2 geometry is adapted (§5) so absolute magnitudes are not comparable; (b)
v1's minifilament contractility was itself **never experimentally calibrated** (the §8 crosslinker
posture applies — v1 is a component-port oracle here, not a quantitative emergent-behaviour oracle); (c)
forcing a magnitude match would invite the force-fitting the spec forbids. The physics is adjudicated
by first principles: contraction direction (both inward ✓), positive-only above baseline (✓), no-motor
control ≈ 0 (✓), and the chain-inclusive read (✓). **jba's qualitative eye on the viewer panel is the
final sign-off.**

## 7. Force-coverage audit (every force on exactly one path)

| force | applied to | path | sign |
|---|---|---|---|
| chain F3/F4 | filament segments | `ChainBendingForceSystem.chainForces` → `fil.forceSum` | self-write |
| cross-bridge F8/F9/F10 (head) | motor head sub-body | `bondForces` → `applyHeadForce` | +F head |
| cross-bridge reaction (segment) | filament segment | `bondForces` → CSR `segGather` → `fil.forceSum` | −F segment |
| dimer coupling | motor rods/levers | `DimerCouplingSystem.couple` (boundSeg-gated align) | two-slot self-write |
| backbone tether | motor rods (+ backbone, discarded — fixed) | `MiniFilamentSystem.tether` → backbone CSR gather | self-write + gather |
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
