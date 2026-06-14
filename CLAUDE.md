# CLAUDE.md — Soft Box

## What this is
Soft Box (working title; software + soft-matter physics) is a ground-up, data-oriented (ECS-style)
re-implementation of BoA's cytoskeletal physics — actin filaments, myosin motors, crosslinkers,
protein-node contractile rings, deformable membranes. Internally this is "BoA v2". The goal is to
break the ~16x host-RAM ceiling that bounds v1: the v1 scaling study showed the OOP object graph
(Thing / Mesh / FilSegment / MyoMotor on the JVM heap) is the ceiling, not the GPU. Soft Box makes
SoA primitive component arrays the canonical state and keeps that state device-resident across steps.

## Relationship to v1 (BoA) — READ THIS BEFORE TOUCHING THE REFERENCE
- v1 lives at `~/Code/BoA` and remains the active research instrument. **Soft Box work never edits v1.**
- Frozen reference: a read-only worktree at `~/Code/BoA-v1ref`, detached at tag
  `softbox-filref-2026-06-13`. This is the physics + residency-pattern reference AND the source for
  regression fixtures. **Never edit or commit into it.** (When the v1 finish line is reached this
  re-points to `biology-production-v1`.)
- Two distinct reference roles, pulling opposite ways:
  - **Oracle** (numbers Soft Box must reproduce): pinned to a tag, captured as stored *fixture data*.
    Stable.
  - **Physics source** (what you read while porting a subsystem): read v1's *current* `main`, so Soft
    Box inherits any biology-driven force/rate corrections.
- **"Frozen" means the fixtures are frozen, not v1's code.** v1 keeps evolving. Only changes to v1
  *physics or numerics* (a force law, the integrator, a rate constant, an IC) move a regression
  target — tuning knobs and visualization/analysis tooling do not.
- **Reconciliation convention:** when a v1 physics/numerics change touches a subsystem Soft Box has
  already ported, re-tag v1, regenerate that one fixture, re-validate that one system. Flag it.

## Architecture invariants (do not violate without flagging the planner)
- Entities are integer IDs. State lives in SoA primitive component arrays — separate `xPos[]`,
  `yPos[]`, `zPos[]` (and orientation, force, drag, length arrays), NOT interleaved AoS, NOT
  per-object Java fields. **The SoA arrays are the source of truth.**
- Behaviors are **systems**: free-standing functions over component arrays, not methods on classes.
  Each system implements one identifiable physical process (rigid-rod overdamped Langevin
  integration; actin chain/bending forces; binding detection; ...), findable by name.
- **Device residency from day one.** SoA state lives on the GPU across steps (TornadoVM PTX backend,
  same toolchain as v1's `-gpu` path); the host reads only at output-frame boundaries. Per-step
  CPU<->GPU transfer is the bottleneck to *eliminate*, not optimize (v1 GPU_STRATEGY lesson).
- A thin CPU-side OOP "view" layer may exist for topology operations only — convenience, never the
  source of truth.
- **Abstract from the second instance, not from anticipation.** General *names* and *shared
  infrastructure* (body view, grid, broad-phase, integration, RNG) are entity-agnostic. But
  entity-specific *physics* stays concrete and localized in per-entity systems + stored
  *parameters*, named honestly (the actin PAIRS law, the myosin catch-slip — not "generic"
  anything). Do not generalize a subsystem's physics from a single instance: the correct
  abstraction is the *diff* between two instances and can't be seen until the second exists. SoA +
  the v1 fixtures make "generalize when the second instance arrives" a cheap, behavior-safe
  refactor, so deferring is free, not a debt. Never hardcode entity-specific constants inside the
  shared systems — that's the leak that makes the "general" layer secretly actin-shaped. (No
  plugin loader / component registry yet — that's a later phase.)
- **One physics implementation; device-agnostic systems.** Each system is written once as a kernel
  method over the SoA arrays. The GPU TaskGraph is the production path; the *same* system methods
  run sequentially on the CPU (plain Java, no TaskGraph) as a debugging/validation runner —
  identical arithmetic, identical RNG. **Never hand-write a CPU or double-precision reimplementation
  of a system's physics** — that recreates v1's two-sources-of-truth drift. CPU execution = the same
  code on a different runner. Stay single-precision; fix float problems with better algorithms (cf.
  the `asin(|cross|)` bending angle), not a parallel double path. One-off double checks for a
  specific diagnosis are fine if thrown away.
- **CPU≡GPU validation standard.** Bit-identical (to printed precision) for non-chaotic or
  short-horizon checks (FDT, broad-phase set, static deflection, joint geometry). For **chaotic
  many-body dynamics over long horizons** (gliding, contractile networks), float32 op-ordering
  decorrelates the microstate (Lyapunov divergence) — bit-identity is unattainable and is not the
  test. The standard there is **aggregate-statistical agreement within SEM**, matching how v1's own
  CPU-vs-GPU runs agree.

## Porting discipline (per v1 GPU_MIGRATION_LESSONS.md)
- **Force-coverage audit** for every ported subsystem: every force applied on exactly one path —
  never zero (silent drop), never two (double-applied). Produce the audit table.
- Build the **minimal reproduction first**; one validation probe per subsystem; **watch it run** in
  the viewer before number-staring.
- Heed dimensional sanity checks (a magnitude mismatch kills a hypothesis class early). A partial
  fix that only improves a number can be masking the real cause.
- Bail-out-and-report on anything that contradicts the plan's assumptions; commit nothing.

## Increment sequence (proposed — increment 1 is the agreed ungated start; 2+ are provisional)
0. Scaffold — dir, repo, frozen-v1 worktree, docs. **(done)**
1. **Filament slice** — FilSegment component arrays + rigid-rod overdamped Langevin integration
   system; validate against v1 deflection / relaxation-time / LP-persistence fixtures. Ungated:
   filaments touch neither nodes nor membrane.
2. Actin chain / bending-force system.
3. **Spatial grid + broad-phase — entity-agnostic. DONE.** (anticipating surfaces/membranes).
4. Binding detection + myosin motors — validate against the v1 gliding-assay fixture.
   **Split into 4a (binding) / 4b (gliding); 4b split into 4b-i/ii/iii** (a bail-out finding: v1's
   "power stroke" is not a force but emergent from an articulated 3-body motor + a load-coupled
   nucleotide cycle + alignment torques, so it re-architects 4a's point-motor and is staged). 4a —
   DONE (binding, no force). **4b-i — DONE: the articulated motor BODY** (rod→lever→head + 2 joints +
   bed anchor, shared rigid-rod integration, validated isometrically). **4b-ii — DONE: cross-bridge
   spring + alignment torques + the cross-entity force+torque gather** (head ↔ pinned filament, fixed
   rest angles). **4b-iii (new physics) — DONE: nucleotide cycle + rest-angle switching (the stroke) +
   F-catch-slip, validated on a pinned filament (stroke checkpoint).** 4b-iv (the gliding payoff,
   deferred): unpin + surface + the chain filament + dynamic binding → gliding velocity/avgBound vs the
   v1 fixture (8.33/8.23 µm/s, avgBound 7.64/7.21).
5. Crosslinkers / Arp2/3 branching.
6. Protein-node contractile path — validate against the v1 node-tension fixture.
7. Membrane — StickyNode bodies + NodeLink springs + the iterative relaxation solver (the
   iterative-constraint-solver-as-a-system design case; v1 RULE_NODE covers only the single-eval
   body+tether half).

## Build / run (aorus)
Java 21 + TornadoVM 4.0.1-dev PTX backend, same environment as v1's `-gpu` path
(`$TORNADOVM_HOME`, `@tornado-argfile`, `--enable-preview`, `-g`). Sources live in the `softbox/`
package. Two helper scripts:

```
./build.sh                 # javac -g --release 21 --enable-preview, tornado-api on the classpath
./run_gpu.sh [N [M_trans]] # java @tornado-argfile … softbox.DiffusionHarness   (FDT validation)
```

**CPU validation runner (`-cpu`).** Any harness mode accepts `-cpu`, which runs the *same* system
methods sequentially over the host SoA arrays — no TaskGraph, no device transfers (the `@Parallel`
loops execute as plain Java for-loops when called directly). This is the device-agnostic invariant
made executable: one physics implementation, two runners. It is a debug/triage instrument for
increment 3+ (physics-logic bug vs PTX-lowering bug), not a production path. Append `-cpu` to any
invocation:
```
./run_gpu.sh -cpu                  # FDT on the CPU runner
./run_gpu.sh -deflect -cpu         # static deflection ratio on the CPU runner
./run_gpu.sh -chain <dir> -cpu     # free chain / connectivity on the CPU runner
```
GPU≡CPU agreement (validated): FDT D's bit-identical to printed precision; deflection ratio
0.99831(GPU)/0.99832(CPU); chain joint-gap/end-to-end/bend-RMS bit-identical — all within float32
last-bit tolerance (see JOURNAL 2026-06-13, CPU validation runner).

**Characterize a filament (inc 2b, manual tuning).** One command → `{deflection ratio, τ_meas/τ_theo,
Lp_meas}` for the current coefficients (override `-fracR <v>`/`-fmt <v>`; BRotCoeff via Constants):
```
./run_gpu.sh -characterize                 # ~40s; ratio + tau (Brownian off) + Lp (Brownian on)
./run_gpu.sh -deflect 11 60000 -fracR 0.1  # just deflection ratio + tau
./run_gpu.sh -lp 539 60000 -fmt 0.05       # just Lp (tangent-correlation C(s) + weighted log-fit)
```
Measurement/reporting only — the v1 auto-tune coefficient-search loop is deliberately NOT ported.
Lp/τ are instruments validated against v1's *measurement* (fixtures/filament_characterization_v1.md),
not biological-target gates.

**Watch the rods (inc 1.5, file-based Three.js playback).** `-3js <dir>` dumps per-frame JSON in the
v1 viewer's schema; off by default, the FDT path is byte-for-byte unaffected.

```
./view_run.sh [N [M]]      # dump a small free-rod viz run (default N=200, M=20000) to threejs_output/
./run_gpu.sh -chain <dir> [nSeg [M]]   # inc 2a: free Brownian filament chain (default 16 seg, 40000)
                                       #   dumps frames to <dir> + reports the joint-continuity gap
python3 sim_server.py 8000 # serve from ~/Code/SoftBox; then open
                           #   http://localhost:8000/sim_viewer_boa.html  (Recent picker, newest)
```

**Broad-phase test (inc 3, entity-agnostic spatial grid).** Device-resident uniform grid (CSR) +
broad-phase over the `SpatialBodyView` (bounding spheres), gated by exact set-equality vs an O(N²)
brute force on both GPU and `-cpu`, CSR bit-identical CPU↔GPU, O(N) vs O(N²) scaling.
**Infrastructure only — writes no forces** (first narrow-phase consumer is motors, inc 4):
```
./run_grid.sh [N [M]]        # default 512 2000; GPU run + CPU cross-check (grid==brute, CSR bit-identity)
./run_grid.sh -cpu [N [M]]   # CPU runner only (triage mode)
```
**Motor-binding test (inc 4a, first narrow-phase consumer).** Myosin motors as a second entity type +
publisher into the SAME `SpatialBodyView`; `BindingDetectionSystem` consumes the broad-phase candidate
pairs, FILTERS by `ownerStore` to motor↔segment pairs, applies the exact v1 bind-reach predicate, and
runs faithful v1 kinetics (deterministic bind, catch-slip release = kOff·dt at zero force). Gated by
reachable-set exactness vs brute force (both runners), the analytic off-rate, and CPU≡GPU bit-identity.
**Bound motors apply NO force this increment** (no power stroke/surface/gliding — all 4b):
```
./run_motor.sh                  # GPU + CPU cross-check (default M=3000): reachable exact, off-rate, CPU≡GPU
./run_motor.sh -cpu             # CPU runner only (triage)
./run_motor.sh -3js threejs_motor   # dump viewer frames (bound motors red + link to segment)
```
The broad-phase + grid + FilamentStore are UNCHANGED — only new files (`MotorStore`,
`BindingDetectionSystem`, `MotorBindingHarness`) + one `SpatialBodyView` constant (`STORE_MOTOR`).

**Articulated-motor isometric test (inc 4b-i, the motor BODY).** Re-architects `MotorStore` into v1's
3-body articulated myosin (rod→lever→head) held by two joints + a bed anchor, integrated by the SHARED
rigid-rod systems (the second instance of the `RigidRodBody` abstraction). Validated isometrically: a
bed of anchored motors holds its articulated shape under Brownian — NO filament/cross-bridge/nucleotide/
gliding (those are 4b-ii/4b-iii). Gated by bounded+non-growing joint gaps, J1 angle about its rest, and
CPU≡GPU on the aggregate joint statistics:
```
./run_motorbody.sh              # GPU + CPU cross-check (64 motors, M=5000, dt=1e-5)
./run_motorbody.sh -cpu         # CPU runner only (triage)
./run_motorbody.sh -3js threejs_motorbody -n 25   # dump viewer frames (articulated motors)
```
The SHARED systems (`BrownianForceSystem`/`RigidRodLangevinIntegrationSystem`/`DerivedGeometrySystem`)
run over `MotorStore.body` UNCHANGED; only `MotorJointSystem` + `TailAnchorSystem` are motor-specific.
`RigidRodBody` is the factored shared layout (FilamentStore + MotorStore each embed one).

**Cross-bridge + cross-entity gather test (inc 4b-ii, the design centerpiece).** Articulated motors
bind a PINNED filament (4a binding, re-exercised via the head sub-body); the cross-bridge spring +
alignment torques (`CrossBridgeSystem`: F8/F9/F10, fixed uncocked rest angle) transmit force+torque to
the segments through the **segment-side CSR-inverse gather** (the race-free, atomics-free motor→segment
coupling — the template every future multi-store coupling reuses). FIXED rest angles + pinned filament
⇒ no stroke/motion/gliding (4b-iii). Gated by the gather exactly equalling a brute-force per-bond sum:
```
./run_xbridge.sh               # GPU + CPU cross-check (4 pinned segs, 12 motors, 3/seg)
./run_xbridge.sh -cpu          # CPU runner only (triage)
./run_xbridge.sh -3js threejs_xbridge -b 0.3   # viewer (motors bound to the pinned filament)
```
The gather (`CrossBridgeSystem.csrHistogram/csrScan/csrScatter` → `segGather`) is general infrastructure
— it builds the segment→bound-motors CSR-inverse (inc-3 pattern keyed by `boundSeg`) and each segment
sums its motors' stored bond reactions into its own forceSum/torqueSum (no atomics, no `KernelContext`).

**Power-stroke checkpoint (inc 4b-iii, the new physics; gliding deferred).** The nucleotide cycle
(`NucleotideCycleSystem`: 4-state NONE→ATP→ADPPi→ADP, load-gated ADP→NONE) + the state-dependent
rest-angle switch (J1 0°↔60° in `MotorJointSystem`, motor–actin 90°↔120° in `CrossBridgeSystem`, keyed
by `isCocked()=!isADPPi`) generate the power stroke (it EMERGES, not a force law) + the full
force-dependent catch-slip release. Validated on a PINNED filament (no unpinning/gliding yet):
```
./run_stroke.sh                    # 5 gates: dwell times, catch-slip F-dependence, stroke, force dir, CPU≡GPU
./run_stroke.sh -3js threejs_stroke   # viewer (cycling motors colored by state, stroking on a pinned filament)
```
Constant-ADPPi reproduces 4b-i/ii exactly (the regression guard). The stochastic machine is exact
(dwell times 5.0/984/9.95/98.8 vs 5/1000/10/100 steps); the unloaded stroke is ~7 nm (a realistic
myosin working stroke); the cycling motors pulse a net −x force into the pinned filament (the glide
direction). The gliding run (unpin + surface + velocity/avgBound vs the v1 fixture) is the next increment.

`SpatialBodyView` is the extensible seam (center+boundingRadius+ownerStore/ownerSlot); two publishers
now register into it (`FilamentStore.publishToBodyView` + `MotorStore.publishToBodyView`). `SpatialGrid`
kernels read ONLY the view (no FilSegment, no Motor).
The grid uses center-cell binning with cellSize=2·maxRadius+cutoff so the 27-cell stencil is provably
complete; every kernel runs on both GPU and `-cpu` (no KernelContext/atomics — see JOURNAL inc 3).

`sim_server.py` + `sim_viewer_boa.html` are copied **verbatim** from v1 (do not fork/modify the
viewer). `softbox/FrameWriter.java` emits `segments` only (no myosins/minifilaments/nodes — deferred
to the planner pre-motors); it reads the already-pulled host pose, adds no device sync, and is gated
behind `-3js`. Frame output (`threejs_output*/`) is gitignored.

The increment-1 entry point is `softbox.DiffusionHarness` (free-rod diffusion harness + FDT check).
Two TornadoVM gotchas, both load-bearing (see JOURNAL 2026-06-13 inc 1): a kernel method may **not**
be named `kernel` (collides with an OpenCL/PTX token); and these RNG-/trig-heavy kernels need an
explicit `WorkerGrid1D` localWork = 64 via a `GridScheduler` keyed `"<graph>.<task>"`, else the
default block size overflows the register file → CUDA 701 (LAUNCH_OUT_OF_RESOURCES). TornadoVM's
`task()` tops out at 15 args, which is why each vector quantity is one planar-SoA buffer rather than
three per-component arrays (see the FilamentStore layout note + JOURNAL).

## Documentation conventions
Same as v1: `CLAUDE.md` = cross-session context (this file); `JOURNAL.md` = terse, newest-first,
what-was-done / what-was-learned / what's-open. Do not archive JOURNAL entries autonomously.

## Status
Increment 1 (rigid-rod Langevin slice) FDT-validated; 1.5 (Three.js frame output) done.

**Increment 2 is split into 2a (connectivity) and 2b (deflection assay):**
- **2a — DONE.** `ChainBendingForceSystem` ports v1's PAIRS chain force law (F3 link spring + F4
  bending/torsion) from the device `chainPairForcesKernel`; the inert `end1Nbr*/end2Nbr*` topology is
  now actively + correctly read (side decode verified by code check, bounded joint-gap, and a
  negative control). A free Brownian 16-segment chain holds together as a connected, semiflexible
  filament (max joint gap 0.069 µm bounded+stationary; end-to-end/contour 0.98). TaskGraph: zero →
  brownian + chain → integrate → derived. No pins, no applied force, no ratio/τ. FDT path unchanged.
  See JOURNAL 2026-06-13 (inc 2a).
- **2a chain force law cross-validated against v1's deflection benchmark** (`-deflect`,
  `softbox/DeflectionSupport.java`): v2 reproduces v1's deflection ratio to ≤0.04% across fracR
  (0.025→0.8), proving the F3/F4 force+torque coding is identical. Confirmed bigger fracR = softer.
  Found+fixed a float32 limit at very low fracR (stiff filaments): the bending angle now uses
  `asin(|cross|)` (hand-rolled `accurateAsin` poly) instead of `acos(dot)` to dodge small-angle
  cancellation — important for microtubules (Lp~mm). See JOURNAL 2026-06-13 (deflection benchmark).
- **2b — DONE.** Filament-characterization toolkit (manual-tuning instrument): `-characterize` reports
  deflection ratio, τ_meas/τ_theo, Lp_meas. BRotCoeff=0.5 end-segment fix applied (FDT + static ratio
  unaffected). Cross-validated vs v1: ratio ≤0.05%, τ_theo exact + τ_meas/τ_theo=0.992, Lp's C(s)
  <0.05% (scalar Lp_meas ill-conditioned at uncalibrated coeffs — a diagnostic, not a gate). The
  **auto-tune coefficient-search loop was deliberately NOT ported** (left in v1; planner decision).
  See JOURNAL 2026-06-13 (inc 2b) + fixtures/.

Increment 2 (chain physics + manual-tuning instrument) is complete.

**Pre-3 interlude — DONE.** CPU validation runner (`-cpu`): a sequential runner that calls the same
system methods in the same per-step order over the host SoA arrays, no TaskGraph. Audit confirmed
every system body (Brownian / integrate / derived / chain / drag-init + the deflection seed/pin
support kernels) was already dispatch-agnostic — plain methods over `FloatArray`/`IntArray`, zero
TaskGraph/WorkerGrid/DataTransferMode references in any kernel body; **no refactor needed**. GPU≡CPU
on FDT / static ratio / connectivity within float32 last-bit tolerance. A CPU reference is now in hand
for triaging increment-3 broad-phase bugs as physics-logic vs PTX-lowering. See JOURNAL 2026-06-13.

**Increment 3 — DONE.** Entity-agnostic device-resident spatial grid (CSR) + broad-phase over the
`SpatialBodyView` (bounding spheres; `FilamentStore.publishToBodyView` the sole publisher).
Infrastructure, no forces written. Broad-phase candidate set == O(N²) brute force EXACTLY on both GPU
and `-cpu` (N=512, 2048; all sampled steps); CSR bit-identical CPU↔GPU; O(N) grid vs O(N²) brute
demonstrated (work + timing). Center-cell binning + cellSize=2·maxR+cutoff makes the 27-cell stencil
provably complete; histogram/scatter kept single-threaded (no KernelContext atomics) so every kernel
runs on both runners. FDT/deflection/chain numbers unchanged. See JOURNAL 2026-06-13 (inc 3).

**Increment 4a — DONE.** Myosin motors as a second entity type + the first narrow-phase consumer of the
broad-phase (binding detection + bind/unbind kinetics). **No motion, no force this increment** (power
stroke + surface confinement + gliding velocity are 4b). Entity-agnostic design VALIDATED: the grid/
broad-phase needed ZERO changes; motors register into the existing `SpatialBodyView` via a second
publisher and the consumer (`BindingDetectionSystem`) filters broad-phase candidates by `ownerStore` —
all motor/segment type logic lives in the consumer. Reachable motor↔segment set == brute force EXACTLY
on GPU and `-cpu` (negative-control motors never reachable); faithful v1 kinetics (deterministic bind +
catch-slip release, which at zero force = kOff·dt) reproduce the analytic off-rate (empirical p_off
0.00999 vs kOff·dt 0.01000, 0.10%); CPU≡GPU bit-identical (bound-state + stats). v1's k_on/(k_on+k_off)
does NOT apply (v1 binds deterministically) and v1's avgBound≈7.6 needs the 4b power-stroke force —
neither is a 4a gate (planner decision: faithful mechanism). FDT/deflection/chain/broad-phase all
reproduce their pre-inc-4 numbers (bound motors apply no force — verified). New: `MotorStore`,
`BindingDetectionSystem`, `MotorBindingHarness`, `run_motor.sh`; +`SpatialBodyView.STORE_MOTOR`. See
JOURNAL 2026-06-14 (inc 4a).

**Increment 4b-i — DONE.** Re-architected `MotorStore` from 4a's single point into v1's 3-body
articulated myosin (rod→lever→head), held by two joints (`MotorJointSystem`: J1 lever-motor + J2
rod-lever, the chain F3/F4 PAIRS pattern with myosin rest angles) + a bed anchor (`TailAnchorSystem`,
v1 `applyRodFixedPtForce`), integrated by the SHARED `RigidRodLangevinIntegrationSystem` /
`BrownianForceSystem` / `DerivedGeometrySystem` over the factored `RigidRodBody` — the second instance
of the rigid-rod-body abstraction (the shared systems run over `MotorStore.body` UNCHANGED; the
abstraction-leak rule held — the only entity-specific drag formula, the sphere head, is a localized
init, not a forked system). FIXED uncocked rest angles (state switching is 4b-iii); NO cross-bridge,
nucleotide, surface, or gliding. Validated ISOMETRICALLY: a bed of 64 anchored motors holds its
articulated shape under Brownian (joint gaps ~13–18 nm, bounded + non-growing over 5000 steps; J1 angle
~15° about its 0° rest; J2 a free hinge at ~96° since v1 `myoJ2FracMoveTorq=0`) on GPU + `-cpu`; CPU≡GPU
on the aggregate joint statistics (microstate diverges only at float-noise level — chaotic op-ordering,
like v1's own CPU-vs-GPU gliding). FDT/deflection/chain/broad-phase/4a-binding all bit-identical
(FilamentStore now embeds `RigidRodBody` via array aliasing; `DragTensorSystem` shares the rod-drag
formula — verified D_par 1.11676e-1, deflection 0.99832 unchanged). New: `RigidRodBody`,
`MotorJointSystem`, `TailAnchorSystem`, `MotorBodyHarness`, `run_motorbody.sh`. See JOURNAL 2026-06-14
(inc 4b-i).

**Increment 4b-ii — DONE.** The myosin cross-bridge (`CrossBridgeSystem`: F8 spring + F9/F10 alignment
torques, faithful `MyoFilLink` port, FIXED uncocked 90° rest angle) connecting the articulated motor
head to a PINNED filament, + the **cross-entity motor→segment force+torque gather** (the design-risk
centerpiece). The gather is race-free WITHOUT atomics by a segment-side sum over a segment→bound-motors
CSR-inverse (inc-3 histogram/scan/scatter keyed by `boundSeg`); each bond is computed once (head-side
self-write + seg-side reaction stored), and each segment sums its motors' stored reactions into its own
forceSum/torqueSum. Gated EXACT: the gathered force+torque == a brute-force per-bond sum, **bit-identical**
(same values, same motor order) on GPU + `-cpu`; CPU≡GPU on the gathered cross-bridge bit-identical
(ΔF ~7e-19 N — float32 last-bit). 4a binding re-exercised on the new head sub-body (12/12 bound, 3/seg ⇒
multi-motor gather). Force-coverage: F8 +F head / −F segment, F9/F10 −T head / +T segment, each once.
Fixed rest angles + pinned filament ⇒ no stroke, no motion, no gliding. Existing paths unaffected (only
new files added). New: `CrossBridgeSystem`, `MotorXBridgeHarness`, `run_xbridge.sh`; +CLAUDE.md CPU≡GPU
validation-standard note. See JOURNAL 2026-06-14 (inc 4b-ii).

**Increment 4b-iii (new physics) — DONE.** The nucleotide cycle (`NucleotideCycleSystem`, 4-state
NONE→ATP→ADPPi→ADP, ~5 rates from Env.java, load-gated ADP→NONE via the forceDotFil 10-window average)
+ the state-dependent rest-angle switch (J1 0°↔60°, motor–actin 90°↔120°, by `isCocked()`) generate the
power stroke (emergent, not an invented force) + the full force-dependent catch-slip release. Validated
on a PINNED filament (the stroke checkpoint, `run_stroke.sh`), 5 sharpened gates PASS: (1) cycle dwell
times == rate·dt (5.0/984/9.95/98.8 vs 5/1000/10/100 steps; cycle ≈0.011 s) — the 4-state analog of 4a's
residence-time check; (2) regression guard — constant ADPPi reproduces 4b-i/ii exactly; (3) unloaded
stroke ≈7 nm (realistic myosin working stroke, lever-scale); (4) the cycling motors pulse a net −x force
into the pinned filament (the glide direction); (5) catch-slip unbind rate responds to forceDotFil load
(100→59→37→20 /s at 0→4 pN); (6) CPU≡GPU aggregate-within-SEM (force-gated cycle decorrelates). New:
`NucleotideCycleSystem` + state-switching in `MotorJointSystem`/`CrossBridgeSystem` + `MotorStrokeHarness`
+ `run_stroke.sh`. Existing paths bit-identical (FDT/deflection/broad-phase/4a/4b-i/ii). See JOURNAL
2026-06-14 (inc 4b-iii). **Deferred: the gliding run** (4b-iv) — unpin + surface + chain filament +
dynamic binding → velocity + avgBound vs the v1 fixture; everything physical is now validated, the
gliding run is the integration.
