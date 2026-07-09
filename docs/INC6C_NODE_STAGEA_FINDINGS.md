# Increment 6c — Stage A: the protein NODE entity (radial motor-bundle, fixed anchor)

**Status: DONE (2026-06-18). All 7 gates PASS GPU + CPU.** The node entity is a validated motor
structure reusing the settled minifilament machinery; seam #1 (separable motor/nucleation) kept open
for Stage B (nucleation + runtime filament birth).

## What was built
The protein node as a **motor-bundle** — a fixed-anchor sphere body owning radially-splayed singlet
myosins + dimers — composed almost entirely from settled, validated machinery. Per the recon
(`INC6_NODE_RECON.md` §1a): the node mechanism IS the minifilament's (a rigid body owning motor-children
via a fracMove tether + a backbone-side single-ended gather); the only differences are **geometry**
(radial sphere-surface splay vs the minifilament's axial end-clusters) and that the node **also** carries
singlet myosins. Both are *placement*.

- **`NodeStore`** — the node entity: the **4th `RigidRodBody` instance** (FilamentStore #1, MotorStore #2,
  MiniFilament backbone #3), isotropic SPHERE drag (`DragTensorSystem.sphereDragSI`, radius 0.05 µm =
  Env.nodeRadius), zero length, FIXED ANCHOR (never integrated — the v1 `AnchorNode` immobilization).
  Owns singlet + dimer children by integer slot + a body-frame radial offset. Reaction-data layout =
  the minifilament stride-6 (so the gather reuses `backboneGather`).
- **`NodeSystem.tether`** — the ONE genuinely new kernel: the **radial** surface tether. Faithful port of
  v1 `ProteinNode.keepMyosinsOnSurface` / `keepMyosinDimersOnSurface` (BoA-v1ref): the **same fracMove
  damping-limited spring LAW** as the minifilament — `F = coeff·1e-6·strain/(dt·(1/rod.bTransGam.y +
  1/node.bTransGam.y))`, toward the surface point, measured from the rod END1 — with **radial** attach
  geometry (`surface = node.coord + ru·uVec + ry·yVec + rz·zVec`, co-rotating; zVec = uVec×yVec in-kernel).
  Two faithful per-child differences from the minifilament (carried in a signed coeff):
  - **SINGLET** (v1 `keepMyosinsOnSurface`): coeff = `attnForce/numNodeMyos` (0.4/nSing); force at the rod
    CENTER (no rod torque), node reaction at the node CENTER (no node torque).
  - **DIMER** (v1 `keepMyosinDimersOnSurface`): coeff = `attnForce·myoDimerFracMove` (0.4·0.2); force at
    the rod END1 (R×F torque, arm in metres), node reaction at the surface point (node torque).
  - NO axis-alignment torque (unlike the minifilament's `miniFilAlign`) — the node tether is a pure
    positional surface spring (verified in BoA-v1ref).
- **Reused BYTE-UNCHANGED** (no fork): the single-ended gather (`CrossBridge.csrHistogram/csrScan/csrScatter`
  keyed by `attachNode` + `MiniFilamentSystem.backboneGather` over the stride-6 `nodeData`); binding
  (`BindingDetectionSystem` + `CrossBridge.bondForces/applyHeadForce/segGather/registerForceDot`); the
  12 pN break-force cap (`MotorStore.setFaithfulRelease`); containment (`ContainmentSystem`); the shared
  rigid-rod Brownian/integrate/derive/drag systems; `MotorStore`/`DimerStore`/`DimerCouplingSystem`.

The full per-step pipeline is a 23-task GPU TaskGraph (device-resident; node pose transferred once, never
pulled) + the identical `-cpu` sequential runner — one physics, two runners.

## Design decisions (flagged)
- **The radial tether is node-specific code, NOT a fork.** The minifilament tether is axial-only
  (`coord + ax·uVec`); radial splay genuinely needs yVec/zVec offsets + the per-child singlet/dimer
  torque asymmetry, which the axial kernel cannot express. So `NodeSystem.tether` is the node's
  *localized* physics (the established per-entity-system pattern: DimerCoupling, MiniFilament,
  Crosslinker each own their coupling). It reuses the tether **LAW** (identical spring arithmetic) and
  the **gather machinery byte-unchanged** — the "no-fork" constraint is about the gather/binding/cap, all
  honored. This is not the "pause" case (radial geometry is exactly the expected node layout the recon
  described), nor the "hard-bail" case (nothing forked).
- **Fixed anchor = a normal node (radius 0.05) that is never integrated**, NOT a literal v1 `AnchorNode`
  (which sets radius 0.005 AND disables its myosins). v1's AnchorNode proves the immobilization mode;
  Stage A composes that immobilization with the full motor-function at the physical node radius.
- **TornadoVM 15-arg `task()` cap:** the tether's per-attachment vector data is planar-packed (`attachKey`
  = node|motor, `radial` = X|Y|Z, signed `attachCoeffK` carries atEnd1) and the node zVec is computed
  in-kernel (orthonormal uVec×yVec) — 20 logical args → 15. (Same packing discipline as the FilamentStore
  planar-SoA note in CLAUDE.md.)
- **Seam #1 kept open:** the motor-function is a free-standing system over the node's child arrays; the
  Stage-B nucleation-function attaches separably (per-node rate → request → reuse the inc-5 scan-rank
  allocator → write a pre-bonded seed filament + a node tether). Nothing nucleation-shaped was built.

## Gates (co-developed small-scale vs BoA-v1ref; all PASS GPU + CPU)
| # | gate | result |
|---|---|---|
| #1a | geometry composes + node gather==brute (isolated, displaced rods) | Δ=0 bit-identical; momentum 3.4e-20 N; **12 singlet + 12 dimer** attachments owned + gathered |
| #1b | node gather UNDER LOAD (real cross-bridges) | node gather==brute Δ=0 at load 2.5e-12 N; fil gather==brute Δ=0; full-system momentum |Σmotor+Σnode+Σfil|=1.6e-19 N; **CPU≡GPU 2.1e-6 µm** (300 loaded steps) |
| #2 | radial binding through the real pathway | a radial singlet head (uVec.x=0.66) over a filament binds via publishHead+bruteReachable+bindKinetics |
| #3 | the inherited 12 pN cap fires on node cross-bridges | |F8|=13.0 pN bond detaches via the v1 break-force branch (capStats=1) |
| #4 | containment confines the NODE body | node past the +y wall pushed inward (0.180→0.167 µm) — entity-agnostic over positions |
| #5 | fixed anchor holds | node Δpose = 0 exactly after 300 loaded steps |
| #6 | all-OFF≡HEAD / one-impl | tether-off ⇒ bare motor bed bit-identical; tether-on differs (coupling real) |

**Regression:** only new files added (`NodeStore`, `NodeSystem`, `ProteinNodeHarness`, `run_node.sh`); no
shared system/store/harness touched ⇒ all prior harnesses byte-unchanged. Spot-checked minifilament +
dimer re-run PASS. `BoA-v1ref` read byte-clean; production untouched; node default-off in production.

## Run
```
./run_node.sh              # GPU + CPU cross-check: gather, gather-under-load, binding, cap, containment, anchor
./run_node.sh -cpu         # CPU runner only (triage)
./run_node.sh -3js threejs_node -n 3   # viewer (radially-splayed nodes + cycling heads)
```

## Stage B (next) — the nucleation-function (opens with a fresh `ProteinNode.java` snapshot re-confirm)
Per the recon: runtime filament BIRTH on `FilamentStore` (today fully static) — add a `filState`
lifecycle sentinel + per-system active guards + reuse the inc-5 scan-rank allocator + stochastic
formation pipeline, with a per-node nucleation emitter (rate `kNodeNuc·dt`, born pre-bonded). The
settledness gate (recon §4 headline) means re-confirming the nucleation specifics (rate, pre-bond
geometry, formin release) against a fresh `ProteinNode.java` snapshot at build time. Growth/polymerization
(monomer-vs-segment granularity) is a SECOND new capability, likely deferred. Seam #2 (the actin pool
behind one accessor) is flagged for then.
