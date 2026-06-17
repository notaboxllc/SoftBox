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

Geometry (faithful to v1 `makeContractilityAssay`; the §5 adaptation is the splay plane only): a central
bipolar minifilament backbone (+x) owning 16 dimers (8/end), **FREE and undergoing Brownian motion**,
with two anti-parallel 8-segment filament chains **offset in Y at ±0.05 µm** (v1 `contractFilYOffset`) —
filament A (plus-end +x, pinned at +x) at +Y, filament B (plus-end −x, pinned at −x) at −Y, both
straddling the minifilament. The dimers splay in the x–Y plane so the heads project ±Y toward the two
offset filaments; the v1 `rodDotFil≥0` predicate sorts polarity: **end2 heads bind only A, end1 heads
bind only B** — both poles engage.

**Brownian thermal search is the binding enabler (the user's key point, and v1's):** v1's dimer rods are
AXIAL (`makeMyosinDimers` — the radial offset is commented out), so a head tip projects only
~(lever+head) ≈ 28 nm perpendicular while the filaments sit at 50 nm. The gap is bridged by the bind
capture radius + the **Brownian wiggle of the rods/heads/backbone** — exactly v1's "thermal search is the
essential enabler". With Brownian on, engagement rises (avgBound ~1.5→2.3/pole) and the signal is ~5×
stronger and symmetric vs the earlier deterministic placement.

Mechanism (dynamic binding + the nucleotide cycle drives the repeated power stroke; catch-slip release
sheds strain ⇒ stable; the FREE backbone is softly centered — see below):

| quantity (steady, 2nd-half of a 30k-step run) | anchor A | anchor B | want |
|---|---|---|---|
| force ON filament, x-component | −3.1e-13 N | +4.7e-13 N | A −x / B +x (**both inward** ✓) |
| anchor tension (Dot(force, inward)·1e12) | +0.27 pN | +0.46 pN | both **positive = contractile** ✓ |
| bound heads (of 8 up-heads/pole) | 2.28 | 2.33 | both poles engage ✓ |

Mean steady tension **0.37 pN vs the no-motor baseline 0.00033 pN — ~1100× above baseline.** Both poles
pull their filament toward its minus (inner) end → both anchors register positive contractile tension;
the result is symmetric (A≈B) and converges by ~20k steps. Peak ~2.6 pN; avgBound (both poles) ~4.6.

**Keeping the free Brownian minifilament "positioned in the middle":** a fully free body diffuses
unboundedly (it wandered ~185 nm off-centre — more than its own 180 nm length — making the contraction
asymmetric). v1 confines its minifilament with a thin box (0.3×0.2 µm); `BackboneAnchorSystem` is the
minimal stand-in — a soft harmonic centering (k≈3e-4 N/m) that lets the backbone still wiggle (Brownian,
~3–13 nm) but keeps it centred (excursion bounded to ~16 nm), so the bipolar contraction stays symmetric.

## 3. CPU≡GPU — PASS

Per the CLAUDE.md standard (bit-identity for non-chaotic short-horizon; aggregate-within-SEM for
chaotic many-body):
- **(a) deterministic chain + PIN (no-motor path, 600 steps): bit-identical** to float32 last-bit —
  coord Δ = 1.2e-7 µm, pinned end2 Δ = 1.2e-7 µm, tension forceSum Δ = 5.4e-17 N. This validates the
  **new `PinSystem` kernel on the device** (the chain/integrate are already validated; the cross-bridge
  gather + tether are bit-identical-validated in 6a/6b/mini-glide).
- **(b) chaotic dynamic-binding path (800 steps): aggregate-agree** — the full Brownian path (incl. the
  new `brownMot`/`brownBb`/`center` kernels) float32-op-orders to a decorrelated microstate (Lyapunov;
  bit-identity unattainable, the CLAUDE.md standard), bound count GPU=4 CPU=4.

## 4. Signal strength + the held-bound finding (surfaced, not force-fit)

With the Brownian thermal search (§2), the contraction is **symmetric and ~5× stronger** than the
earlier deterministic placement: steady tension ~0.27/0.46 pN (A/B), ~1100× the no-motor baseline,
avgBound ~2.3/pole, converging by ~20k steps. It is still a **single small minifilament** (no
fresh-motor reservoir), so:
- avgBound is duty-ratio-limited (~2.3 of 8 up-heads/pole) — a released head must Brownian-search and
  rebind; with the thermal search this is now reasonably fast (vs slow/0 in the deterministic version),
  but it is not the high-duty regime of a dense carpet.
- **Held-bound (no release) is intrinsically unstable on a pinned filament** — the cross-bridge strain
  cannot relax (backbone softly anchored, filament plus-end pinned, no translocation), so it accumulates
  and the forward-Euler integration diverges. This is **physically correct** (a myosin that can neither
  move its substrate nor detach builds unbounded strain) and is exactly why v1 requires catch-slip
  release. So dynamic binding is the only stable, faithful path.

**Implication for the next increment:** a stronger, sharper contractile plateau scales with co-engaged
head count — engage the down-heads too (filaments on both ±Y), multiple minifilaments, or the
protein-node carrier (6c). The mechanism and the readout are correct; magnitude scales with engagement.

## 5. Fidelity to v1 + the one flagged adaptation

| aspect | v1 `makeContractilityAssay` | v2 (this assay) | note |
|---|---|---|---|
| filament offset | Y = ±0.05 µm (`contractFilYOffset`) | **Y = ±0.05 µm** | faithful — filaments straddle the central minifilament |
| minifilament | FREE + Brownian (thermal-centred by the thin box) | **FREE + Brownian**, softly centered by `BackboneAnchorSystem` | faithful mechanism (the thermal search); the soft spring stands in for v1's confining box |
| thermal search | rods/heads Brownian (the binding enabler) | **rods + heads + backbone Brownian** (BTransCoeff 1.0 / BRotCoeff 0.3, v1 pf) | faithful — this is the search-and-bind enabler |
| dimer rods | AXIAL (radial offset commented out in `makeMyosinDimers`) | AXIAL | faithful — heads reach ~28 nm perpendicular; the gap to 50 nm is bridged by capture radius + thermal search (v1's mechanism) |
| dimer splay plane | 3D azimuthal (all directions) | **x–Y plane only** (heads project ±Y) | **the one ADAPTATION (flagged):** the two filaments straddle in Y, so a ±Y splay engages both poles efficiently; v1's full 3D splay wastes heads where there is no filament. Polarity still sorted by `rodDotFil`. |
| binding | dynamic catch-slip | dynamic catch-slip (faithful) | same mechanism |
| filaments | 13-seg, 2.28 µm, pinned plus-ends | 8-seg chains, pinned plus-ends (`PinSystem`) | same pin mechanism (v1 `applyBenchmarkPins` port); fewer segments for a fast test, still interior-bind → chain → pin |

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
| backbone tether | motor rods + backbone | `MiniFilamentSystem.tether` → backbone CSR gather | self-write + gather |
| Brownian (search) | rods + heads + backbone | `BrownianForceSystem.brownianForce` → randForce/randTorque | self-write (FDT) |
| backbone centering | backbone COM | `BackboneAnchorSystem.center` → `bb.forceSum` | self-write (soft spring) |
| pin snap | pinned filament segments | `PinSystem.snap` (position, after integrate) | translation |
| tension read | host | `pinSeg.forceSum · buildDir` (PRE-snap, chain-inclusive) | readout only |

No force applied twice; no force dropped. The tension read is a pure readout of `forceSum` (no force
written), captured before integrate/snap.

## New files
- `softbox/PinSystem.java` — the v1 position-snap end-pin (device-agnostic, one thread/pin).
- `softbox/BackboneAnchorSystem.java` — soft harmonic centering of the free Brownian backbone (the v1
  confining-box stand-in).
- `softbox/ContractileAssayHarness.java` — the assay + gates + viewer + diagnostic.
- `run_contractile.sh`.

No existing system or store modified (regression: 6a/6b/dimer-glide/mini-glide all rerun PASS;
`BoA-v1ref` byte-clean; production byte-unchanged).
