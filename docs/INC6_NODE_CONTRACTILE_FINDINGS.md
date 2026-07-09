# Increment 6 — the NODE in the minimal contractile assay (node ⇄ minifilament swap)

**Date:** 2026-06-18
**Status:** DONE — 4 gates PASS (GPU+CPU). Qualitative "the node does contractile work": a free,
box-confined protein node at the overlap centre binds two anti-parallel pinned filaments with its
radial myosins and pulls them into contraction, reading tension through the existing instrumentation.
**Scope:** a harness COMPOSITION over already-validated pieces — the minimal contractile assay scene
(`PinSystem` pins + the chamber box + the 12 pN break-force cap + the chain-inclusive pre-snap tension
read) with the Stage-A protein NODE (NodeStore tether LAW + single-ended CSR gather, byte-unchanged)
standing in for the central minifilament. **NO new force law, NO new gather, NO shared-kernel change.**
**Nucleation OFF** (this exercises the node's MOTOR-function, not nucleation).

```
./run_nodecontract.sh                 # GPU + CPU cross-check: #2 contracts, #3 CPU≡GPU, #4 control, #5 containment
./run_nodecontract.sh -cpu            # CPU runner only (triage)
./run_nodecontract.sh -cpu -diag      # per-pole engagement diagnostic
./run_nodecontract.sh -3js threejs_nodecontract -steps 30000   # viewer (the v1 contractility panel, node centre)
./run_nodecontract.sh -cpu -anchor    # the fixed-anchor (ring-mode) variant
```

---

## 1. The swap (what changed vs ContractileAssayHarness)

| element | contractile assay | this harness |
|---|---|---|
| central contractile unit | a free bipolar **minifilament** (backbone + axial dimer clusters) | a free **protein node** (sphere body + radial singlets + dimers) |
| coupling | `MiniFilamentSystem.tether` + backbone CSR gather | `NodeSystem.tether` (radial) + the SAME CSR gather (`backboneGather`, byte-unchanged) |
| filaments / pins / box / cap / tension read | two anti-parallel pinned chains, `PinSystem`, chamber box, 12 pN cap, chain-inclusive pre-snap read | **identical, reused** |
| nucleation | n/a | OFF (no `NodeNucleationSystem`) |

Everything except the central unit is reused; the node pieces (`NodeStore`, `NodeSystem`,
`DimerStore`/`DimerCouplingSystem`) are byte-unchanged from Stage A / 6a. The harness is the only new
code (`NodeContractileHarness.java` + `run_nodecontract.sh`), so **prior harnesses are bit-identical by
construction** (confirmed: node / minifilament / contractile / dimer all re-run PASS).

## 2. Geometry — both poles engage naturally (the radial-splay payoff)

The node sits at the overlap centre (0,0,0). Its singlet + dimer heads splay **radially** over the
sphere surface (Fibonacci). The two anti-parallel filaments straddle it in ±Y (`contractFilYOffset
0.05 µm`) and are pinned at opposite outer plus-ends (+x for A, −x for B). They **overlap across the
node** (A spans x∈[−0.38, +0.84], B spans x∈[−0.84, +0.38], offset ±0.05 in Y).

The v1 `rodDotFil≥0` predicate sorts polarity automatically: a node head whose rod points **+x** can
bind filament A (uVec +x) and a **−x** head can bind filament B (uVec −x). So the **+x hemisphere binds
A, the −x hemisphere binds B** — both poles engage with no bespoke per-pole placement (the radial node
is intrinsically bipolar in every direction). Heads pointing toward neither filament dangle (biological,
sparse-field). The inner (minus-end) segments sit in the node's head shell at
`fieldXc = R + ROD_LEN + (LEVER_LEN+HEAD_LEN)·cos80 ≈ 0.135 µm`.

Each pole pulls its filament toward its minus (inner) end ⇒ both filaments translate toward the node ⇒
both plus-end pins feel an inward (contractile) reaction. Seg-side force on A is −x, on B is +x (both
inward) — confirmed.

## 3. The headline — IT CONTRACTS (gate #2) — PASS

Free node, dynamic catch-slip binding + the nucleotide-cycle power stroke + the 12 pN cap, 50k-step run
(2nd-half steady):

| quantity | anchor A | anchor B | want |
|---|---|---|---|
| steady anchor tension (chain-transmitted) | **+1.24 pN** | **+1.79 pN** | both positive = contractile ✓ |
| steady bound heads | 3.28 | 3.81 | both poles engage ✓ |

- mean steady tension **1.52 pN** vs the no-motor baseline 0.00033 pN — **4660× above baseline**.
- peak 4.99 pN, avgBound 6.47, first bind @ step 1.
- **Same regime as the minifilament** (v1 ref 1.84 pN / v2 minifilament ~2.0 pN). The node is a
  different geometry, so a same-regime value — not an exact match — is the expected, healthy outcome
  (the minifilament's ~1.84 pN is a SANITY BALLPARK, not a target: v1's assay used a minifilament, so
  there is **no v1 numeric target for a node** — §8 component-port-vs-emergent posture; jba's viewer eye
  is the final sign-off).
- The free node drifts only ±0.03 µm about the origin (held by its bipolar bonds + confined by the box),
  so the signal fluctuates mildly but the long-run net is cleanly contractile.

## 4. Instrumentation populates + sane (gate #2 + the viewer panel) — PASS

The existing v1 contractility readout (`ThreeJSWriter` schema) is reused 1:1: `contractility{tensionA_pN,
tensionB_pN, anchorA/B}` + `stats{boundHeads, peakBound, avgBound, ewmaBound, meanTension_pN,
avgTension_pN, ewmaTension_pN, peakTension_pN, firstBindStep, hasMotor}`. The 30k-step viewer run reports
avgTension 1.28 pN, peakTension 3.70 pN, avgBound 6.03, firstBind@1 — all populated and sane.

## 5. CPU≡GPU (gate #3) — PASS

- **(a) deterministic chain + PIN** (no-motor, 600 steps): **bit-identical** to float32 last-bit —
  coord/end2(pin) Δ = 7.1e-8 µm, tension forceSum Δ = 2.7e-17 N. (Validates the assembled device
  TaskGraph's filament + new PinSystem path; the node tether + CSR gather are bit-identical-validated in
  Stage A / 6b.)
- **(b) chaotic dynamic-binding** (3000 steps, windowed avgBound over 10 samples): GPU **2.10** = CPU
  **2.10** — aggregate-agree (float32 op-ordering decorrelates the microstate; the CLAUDE.md
  aggregate-within-SEM standard for chaotic many-body).

## 6. The chamber confines the free node (gate #5) + the cap fires faithfully

- **Containment (gate #5):** the entity-agnostic `ContainmentSystem` confines the free **node** body
  exactly as it confines the minifilament — a no-op inside (|force|+|torque| = 0 ⇒ adding it is
  bit-identical), an inward force when pushed past a wall (Fy = −2.8e-11 N, y 0.130→0.127 µm). It
  confines POSITIONS, not class identities (the same kernel over the node's `RigidRodBody`).
- **The 12 pN break-force cap** is enabled (`setFaithfulRelease`, faithful to v1) and inherited
  byte-unchanged from the contractile assay / Stage A (where its firing is gated directly). It bounds
  every node cross-bridge ≤ 12 pN ⇒ steady, no force-insanity (the same valve that makes the
  minifilament assay steady).
- **No-motor control (gate #4):** the pinned tips hold exactly (Δ = 0) and the bare-chain tension
  relaxes to 0.00033 pN — the contraction signal is entirely the node's doing.

## 7. Free vs fixed-anchor node (the flagged choice)

**FREE (default)** — the faithful swap for the *free* minifilament: the node body is integrated under all
forces + Brownian and confined by the chamber box. Drifts ±0.03 µm, tension A/B = 1.24/1.79 pN.

**FIXED-ANCHOR (`-anchor`)** — the ring's mode (the node never integrated, v1 `AnchorNode`
immobilization): node excursion exactly 0, tension A/B = 1.28/1.66 pN — **same contractile regime**. A
valid alternative; the free node's drift is small enough that both give essentially the same result.

Recommended: **free** for this swap (mirrors the free minifilament); **fixed-anchor** is the natural mode
for the post-node contractile RING (a ring of anchored nucleating nodes).

## 8. Force-coverage (every force on exactly one path)

| force | applied to | path |
|---|---|---|
| chain F3/F4 | filament segments | `ChainBendingForceSystem.chainForces` → `fil.forceSum` (self-write) |
| cross-bridge F8/F9/F10 (head) | motor head sub-body | `bondForces` → `applyHeadForce` (+F head) |
| cross-bridge reaction (segment) | filament segment | `bondForces` → CSR `segGather` → `fil.forceSum` (−F segment) |
| dimer coupling | motor rods/levers | `DimerCouplingSystem.couple` (boundSeg-gated align) |
| node radial tether | motor rods + node body | `NodeSystem.tether` → node CSR `backboneGather` (self-write + gather) |
| Brownian (search) | rods + heads + node body | `BrownianForceSystem` (FDT self-write) |
| containment | node body (free) | `ContainmentSystem.confine` (no-op inside) |
| pin snap | pinned filament segments | `PinSystem.snap` (position, after integrate) |
| tension read | host | `pinSeg.forceSum · buildDir` (PRE-snap, chain-inclusive — readout only) |

No force applied twice; no force dropped. The tension read is a pure readout of the complete `forceSum`
(chain + gather, filaments Brownian-off ⇒ no Brownian term), captured before integrate/snap.

## 9. New files (no existing file touched)
- `softbox/NodeContractileHarness.java` — the assay + gates + viewer + diagnostic.
- `run_nodecontract.sh`.

Regression: node / minifilament / contractile / dimer all re-run PASS; `BoA-v1ref` byte-clean;
production byte-unchanged; nucleation off.

## 10. Posture / what this is NOT
- Qualitative + physical-plausibility validation (§8 CLAUDE.md: v1's assay used a minifilament ⇒ no v1
  numeric oracle for a node; the contraction is adjudicated by first principles — both poles net-inward,
  positive above baseline, no-motor control ≈ 0, chain-inclusive read).
- Foreshadows the post-node fixed-anchor contractile RING (a ring of nodes + this tension read — all
  primitives now exist). Test B (two nodes finding each other via polymerizing nucleated actin) follows
  once polymerization lands.
