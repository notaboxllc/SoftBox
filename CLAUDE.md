# CLAUDE.md — Soft Box

## What this is
Soft Box (working title; software + soft-matter physics) is a ground-up, data-oriented (ECS-style)
re-implementation of BoA's cytoskeletal physics — actin filaments, myosin motors, crosslinkers,
protein-node contractile rings, deformable membranes. Internally this is "BoA v2". The goal is to
break the ~16x host-RAM ceiling that bounds v1: the v1 scaling study showed the OOP object graph
(Thing / Mesh / FilSegment / MyoMotor on the JVM heap) is the ceiling, not the GPU. Soft Box makes
SoA primitive component arrays the canonical state and keeps that state device-resident across steps.

## Relationship to v1 (BoA) — READ THIS BEFORE TOUCHING THE REFERENCE
- **Three repos, three names (use them precisely — the §6.10 Phase-A slip motivated naming this):**
  - **BoA-active** = `~/Code/BoA`, the live v1 research instrument (evolves). **Never in a v2 validation loop.**
  - **BoA-v1ref** = `~/Code/BoA-v1ref`, the frozen, **byte-clean** read-only oracle worktree. **Every v2
    validation compares SoftBox vs BoA-v1ref — and ONLY this one.**
  - **SoftBox** = this repo, v2.
  All "v1 oracle" numbers come from BoA-v1ref; a scratch instrumented copy (e.g. `/tmp/v1scratch`) may be
  built for probes, but BoA-v1ref itself stays byte-clean and BoA-active never enters validation.
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

## Document map & oracle posture

**Governing docs (define/record Soft Box):**
- `CLAUDE.md` — cross-session context, invariants, build/run, status (this file)
- `JOURNAL.md` — terse newest-first log
- `GLIDING_4biv_FINDINGS.md` — gliding payoff (increment 4b-iv) findings + benchmarks

**v1-reference-only (inherited background — NEVER a Soft Box task):** these describe v1's code,
biology, or measurements and do NOT live in this repo — they sit in the v1 tree (`~/Code/BoA`, frozen
mirror `~/Code/BoA-v1ref`) and ride along only as oracle/lessons reference. Do not action them as
increments.
- `GPU_STRATEGY.md` — Sim3D→BoA GPU lessons + acceleration strategy
- `MYOSIN_VALIDATION.md` — v1 lumped-motor validation vs experiment
- `NMII_BIOLOGY.md` — non-muscle myosin II kinetics/mechanics, biological context for v1 tuning
- `PT3D_SOA_MIGRATION.md` — a *v1* OOP-heap (Pt3D) refactor design doc; Soft Box is SoA-from-scratch and
  has no Pt3D graph. Kept as the host-heap-ceiling rationale that motivates Soft Box, not a Soft Box task.
- `BENCHMARK_dense.md` — a *v1* dense CPU-vs-GPU gliding benchmark (`glidingDense_demo_smoke`); a v1
  measurement, not a Soft Box gate.

The one v1-reference doc that *does* live in this repo is `fixtures/filament_characterization_v1.md`
(under `fixtures/`, not root) — the frozen v1 filament measurement the Lp/τ instruments replay.

**Oracle posture (read before scoping any new validation).** v1 (BoA-v1ref) remains the measured
oracle, but its role changes across the migration:
- **Through increment 4 (filament / motor / gliding):** v1 supplied *frozen measured fixtures* —
  gliding velocity & avgBound, myosin binding/force statistics, filament deflection/τ/Lp benchmarks.
  These are now **largely consumed**; the standing validations replay them, no new capture expected.
- **Increment 5 onward (crosslinkers, nodes/contractile ring, membrane):** the frozen-fixture well is
  dry. v1 becomes a **porting-equivalence oracle**, not a biological-truth oracle. Fidelity is
  established by **co-developed small-scale mechanical checks** — capture a v1 micro-behavior at port
  time and show v2 reproduces it — proving v2 ported *the same physics*, NOT that v1 is biologically
  correct. Where v1 itself is new/unsettled (membrane, contractile ring), there may be **no mature v1
  measurement at all**; fidelity is matched at small scale as the machinery moves over, and any such
  co-developed check is recorded in JOURNAL.md as a porting-equivalence fixture (and flagged as such —
  distinct from a biological-target gate).
- The reconciliation convention above still applies to the *frozen* fixtures; porting-equivalence
  checks are versioned with the increment that introduces them, not against a v1 tag.

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
- **Single-source dt — no hardcoded dt in any force law (structurally enforced, 2026-06-22).** Every
  dt-dependent quantity derives from the *caller's stepping dt*, never a hardcoded `Constants.deltaT`.
  The two force-law dt-carriers REQUIRE dt: `Constants.brownianForceMag(double dt)` (FDT amplitude
  `sqrt(2kT/dt)`; the no-arg form is deleted) and `FilamentStore.setChainParams(double dt)` (writes
  `chainParams[0]=dt`). This eliminates the chain-dt class (a stale `Constants.deltaT` silently
  rescaling the physics when a harness steps at 1e-5 — the Test B / `DELTAT_AUDIT_FINDINGS.md`
  precedent). Faithful to v1 (`GPUMoveThing.java:6789` `sqrt(2kT/Env.deltaT)`; `chainParams[0]=dt`).
  Biochem rates use the declared cadence `biochemDeltaT` (= `N·deltaT`), KIN scales rate constants not
  the clock. Formin/node-anchored seed filaments get the **full FDT Brownian** (`BTransCoeff`, held by
  the tether — v1 `FilSegment.java:621-642`), NOT a per-seed damping hack. See `CHAIN_DT_FIX_FINDINGS.md`.
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
   4b-iv — **DONE + CLOSED** (2026-06-16): glides −x, stable, avgBound + instantaneousSpeed match v1; the
   net-glide velocity is a small **0.874× (−13%/−4σ) box-uniform residual** accepted as the **irreducible
   parallel-scheme remainder** (one-step-stale SoA forces vs v1's sequential fresh-force update), real but
   within v1's chaotic envelope. Exclusion chain §6.8–6.12 (`GLIDING_4biv_FINDINGS.md`); consolidated in
   `GLIDING_4biv_RESIDUAL_DOSSIER.md`.
5. **Crosslinkers / Arp2/3 branching — ACTIVE/NEXT.** Recon: `INC5_CROSSLINKER_RECON.md`.
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
2026-06-14 (inc 4b-iii).

**Increment 4b-iv (gliding assay) — CLOSED (2026-06-16). Residual accepted.** Assembled 4a–4b-iii + the
inc-2 chain filament into v1's gliding assay. **Works end-to-end — glides −x, stable, avgBound +
instantaneousSpeed matching v1.** The earlier "0.51× velocity miss" was a **measurement-method conflation**:
v2's net-displacement glide was compared against v1's `longWindowSpeedXY`-at-end-of-a-0.1 s-run ("8.33"),
which is inflated by window-length + an initial settling jump and is NOT v1's net glide. Measured the SAME
way (v2's harness ports v1's `GlidingAssayEvaluator`), multi-seed (n=8), at matched boxes, **v2 = 0.87× v1**
(NET 4×1: 4.02 vs 4.61; 14×2: 4.10 vs 4.69) — a small, **box-uniform** residual (NOT 2×, NOT box-scaling:
v2 reproduces v1's weak ~+2 % net box-scaling; v1's own NET @14×2 is **4.7, not 8.33**). The residual is
specifically in **net directedness** (total motion/instantaneous + avgBound match; v2 converts less of it
to forward glide — the §5 co-bound tug-of-war, now correctly sized ~0.87× not ~0.5×; the n=3→n=8 ensemble
regressed the ratio from 0.76×/>5σ to 0.87×/~3–4σ). **No physics edits.**
Full report + grid: `GLIDING_4biv_FINDINGS.md`; raw `RUN_LOGS/2026-06-14_4biv_grid_reconciliation.txt`.
New: `GlidingHarness` (+ `-grid` v1-style measurement), `BindingDetectionSystem.bindNearest`, `run_gliding.sh`:
```
./run_gliding.sh -gpu -v1box -grid -seed <n> 10000   # 4×1 box: v1-style grid (inst+net+longWindow)
./run_gliding.sh -gpu -full  -grid -seed <n> 10000   # 14×2 box (~13.4k motors)
./run_gliding.sh -gpu -full 10000    # full v1 14×2 box — probe + GPU throughput
./run_gliding.sh -diag 10000         # mechanism instrument (state dist, force balance, advance/stroke)
./run_gliding.sh -gpu -3js threejs_gliding 20000   # viewer (full motor carpet)
```
**Full-scale GPU TaskGraph (23 kernels, device-resident, no per-step host pull):** full 14×2 box (~13.4k
motors), stable; GPU throughput **386 steps/s @ 13.4k motors (~7.3× the CPU runner — measured: GPU 386 vs
CPU 52.6 steps/s, difference-method)**. Binding + assembly +
residency validated at scale; the gliding velocity is now a **small, sharp, correctly-sized ~0.87×
box-uniform residual** in net directedness (re-scoped from the mis-framed 0.51×). See JOURNAL 2026-06-14
(inc 4b-iv RECONCILED) + `GLIDING_4biv_FINDINGS.md`.

**4b-iv CLOSE (2026-06-16, §6.7–6.12).** The −13% / −4σ net-glide residual (v2 4.000 vs v1 4.578, 0.874×,
box-uniform; n=24/16) is **accepted as the irreducible parallel-scheme remainder** — v2's one-step-stale
SoA forces (Jacobi-like) vs v1's sequential fresh-force update (Gauss–Seidel-like) on a chaotic many-body
trajectory; real but **within v1's chaotic envelope** (22/24 v2 runs inside v1's seed range; v1 same-seed
assist SD 3.3 pp; v2 bit-reproducible). The full exclusion chain (§6.8 precision RULED OUT, floor ≲0.1%;
§6.9 no localized assist-balance constant; §6.10 force-cap net-flat; §6.11 refractory-rate ±0.16/≤1σ; §6.12
refractory-race confound — gate failed + bounded 0.0σ by the v1-CPU 4.581 vs v1-GPU 4.578 control) all
excluded or bounded small; net is decoupled from assist-balance, avgBound, and refractory rate.
**Consolidated reference: `GLIDING_4biv_RESIDUAL_DOSSIER.md`** (Part 1 residual; Part 2 the BoA `bindTimer`
bug for a future BoA-active fix). **Architectural, not a bug/precision/tunable-constant — not reopened
without a >0.1% systematic signal.**

- **Pending faithfulness step (decided, NOT executed):** promote v1's 12 pN break-force release
  (`-faithfulrelease`, §6.10 — a real v1 feature, faithfully ported, CPU≡GPU) to default-on. Deferred to
  its **own task** because flipping it re-baselines §7 (avgBound 7.6→6.5, the §6.7/§6.9 distributions). Do
  not flip it as a side-effect of other work.
- **Deliberate de-racing divergence (keep):** v2 keeps its **clean per-motor 1-step rebind block**; v1's
  `bindTimer` is a non-physical **static-global race** (~0.31 GPU / 0% CPU, parameter-vestigial — §6.12,
  dossier Part 2). v2 not reproducing it is v2 correctly **not** inheriting a v1 bug, not a faithfulness gap.
- **Superseded leads (do NOT re-trust older entries):** §6.2's "v1 assist 54.4%" was an **n=4 draw** (≈52%
  at n=6, §6.9 — there is no assist deficit); §6.6's "~4–6% net from the refractory" **did not survive n=16**
  (§6.11; its primary verdict — refractory acts on binding quantity not directedness — does stand); §6.8's
  neat "1.37% coherent ≈ 2–3 pp assist deficit" cross-check is **coincidental** (the deficit was the small-n
  artifact). §6.8's **core stands** (precision ruled out; float32 floor ≲0.1%).
- **Carry-forward rule (crosslinker/contractile work):** a **>0.1% systematic** discrepancy vs BoA-v1ref is
  a **logic** signal, not float32 (the §6.8 precision floor). Below ~0.1% on near-cancelling force balances
  is expected float32 / chaotic-mean noise.
- **Component-port-correctness vs emergent-quantitative behavior (crosslinkers; §8) — a load-bearing
  distinction.** v1 (BoA-v1ref) is the oracle for **per-component port equivalence** (a force law, a rate, a
  gate — matched bit-for-decision, the standing 5a–5c-ii validations). But for **emergent quantitative
  behavior** (steady-state link count, strain, plateau), **v1's crosslinkers were NEVER calibrated to
  experiment** ⇒ v1 is **NOT a quantitative oracle** there. Adjudicate emergent crosslinker behavior against
  **first-principles physics** (equipartition/FDT, conservation, scaling), not by matching v1's numbers. A v2↔v1
  emergent mismatch where v2 is physics-correct (e.g. v2 at Boltzmann equilibrium, §8) is **v1's deviation, not a
  v2 bug** — do NOT chase it, do NOT import v1 artifacts to close it. (This posture is crosslinker-specific so
  far; filament/motor emergent behavior WAS frozen-fixture-validated through inc 4.)

**Increment 5 (crosslinkers / Arp2/3) — ACTIVE.** Recon: `INC5_CROSSLINKER_RECON.md`.

**Increment 5a — DONE (2026-06-16).** Passive crosslinker static translational spring + the **double-ended
filament↔filament gather** (the recon §2 design risk: the motor→segment CSR-inverse is single-ended).
Solved by a **two-pass single-ended gather** — the validated `CrossBridgeSystem` CSR template reused
**verbatim, run twice** (pass A keyed by `linkFilA`, pass B keyed by `linkFilB`); each crosslinker
self-writes both side reactions into its own `xlinkData` row, each pass sums the matching side into the
filament's `forceSum`/`torqueSum` — race-free, no atomics, **bit-identical CPU↔GPU** by construction.
Force law = faithful v1 `FilLink.applyTransForce` port (damping-limited; the `/dt` cancels the integrator
`·dt` ⇒ dt-independent relaxation — drag in both the force-law denominator and the integrator is v1's
design, not a double-count). Links **pre-placed + STATIC** this sub-increment (no formation/Bell-unbinding/
torsion/Arp2/3 — 5b/5c/later). Validated vs `BoA-v1ref` on two co-developed static checks: **rest hold**
(no spurious rest force; equal-and-opposite ⇒ COM fixed) and **stretch relaxation** (decay constant
τ=273.84 steps matches the analytic-from-v1-arithmetic to **0.0012 %**, **dt-invariant to 0.0000 %**);
gather==brute bit-identical; **CPU≡GPU** (force+gather bit-identical, pose float32 last-bit); **all-OFF≡HEAD**
(crosslinker pipeline over nLinks=0 ≡ bare filament path, bit-identical). `GlidingHarness`/production
**byte-unchanged**. New: `CrosslinkerStore`, `CrosslinkerSystem`, `CrosslinkerHarness`, `run_xlink.sh`,
`SpatialBodyView.STORE_CROSSLINKER=2`. Report: `INC5A_CROSSLINKER_FINDINGS.md`; JOURNAL 2026-06-16 (5a).
```
./run_xlink.sh              # GPU TaskGraph + CPU cross-check (rest hold, decay constant, gather, all-OFF≡HEAD)
./run_xlink.sh -cpu         # CPU runner only (triage)
./run_xlink.sh -3js threejs_xlink   # viewer (off-rest crosslinked pair relaxing)
```
**5a flag for the planner:** v1 `getLinkCt` accumulates per-step (order/thread-dependent for
multi-link-per-segment); 5a uses the total static count (=1 ⇒ `fracMove`=0.4 exact). Multi-link-per-segment
`fracMove` faithfulness is a 5c (formation) concern.

**Increment 5b — DONE (2026-06-16).** Bell-model crosslinker unbinding + the link-lifecycle **death half**,
on 5a's pre-placed scene. `CrosslinkerSystem.unbind` (faithful v1 `FilLink` strain-register + `ckLinkBreak`
port): a 10-slot **boxcar** strain track (v1 `ValueTracker(10)` — NOT an exponential EWMA;
`strainHist`/`strainPlace` ring), `k_off = linkOffConst + linkOffCoeff·exp(aveStrain·linkOffExp)`,
`P_break = k_off·dt`, drawn via the reused wang-hash keyed `(link,step,seed)` salt `0x584C4B42` ("XLKB",
distinct from the motor salts) — bit-identical CPU↔GPU, no atomics/KernelContext, `localWork=64`. Lifecycle =
ONE authoritative sentinel field `CrosslinkerStore.linkState` (`>=0` ACTIVE / `<0` FREE-DEAD, mirroring
motor `boundSeg`); **death = self-write** the sentinel; the 5a gather gained **exactly one** `if(active)`
guard (`segGatherA`/`B` + the `bruteGather` reference) — the CSR template stays reused VERBATIM. Default-off
(`unbindOn`) ⇒ all-OFF≡HEAD. Validated vs `BoA-v1ref` (analytic-oracle): #1 P_break+EWMA arithmetic
**Δ=0.000% / 2.6e-6%** (gate); #3 empirical off-rate matches `k_off·dt` at strain 0/0.5/1
(**Δ 1.37%/0.030%/0.0054%**, sampling-limited); death→inert (gathered force exactly 0 after a break, full
pipeline); **CPU≡GPU break path bit-identical** (854=854 dead, 0 mismatched); all-OFF≡HEAD (unbind off ≡
5a, bit-identical). `BoA-v1ref` byte-clean; production byte-unchanged. New: `CrosslinkerSystem.unbind` +
lifecycle/strain fields in `CrosslinkerStore` + 5b checks in `CrosslinkerHarness`. Report:
`INC5A_CROSSLINKER_FINDINGS.md` (§5b appended); JOURNAL 2026-06-16 (5b).
**5b flag for the planner:** the **running-v1 oracle stays DEFERRED to 5c** (formation steady-state — link-count
plateau / formation≈dissolution — is where a running v1 bundle is genuinely needed; 5a/5b were analytic-oracle).
`k_off(strain 0) = const+coeff = 2 /s` (not `linkOffConst` alone). fracMove-on-death deferred to 5c.

**Increment 5c-i — DONE (2026-06-16).** The link allocator in isolation — **Design A: scan-rank free-list,
no compaction**. Each formation phase: build a free-list (the FREE `linkState` slots compacted in index
order via `freeFlags` → the **reused `csrScan` prefix-sum VERBATIM** → a `freeScatter` stream-compaction);
rank accepted requests (`csrScan` over accept-flags); `allocate` — request rank r claims `freeList[r]`,
writes its payload, inits a fresh strain ring, flips `linkState` FREE→ACTIVE (distinct ranks → distinct
free slots ⇒ one writer/slot, **race-free, no atomics/KernelContext**); clamp `nAccepted` to `nFree`. A
**synthetic deterministic driver** (no RNG) feeds requests; step order **death(5b)→free-list→allocate**
(same-step reuse of 5b deaths). **Design A CONFIRMED** — existing ACTIVE links + their strain rings never
move (slot-stability holds; no compaction needed). The only gather adjustment is the §5b `if(active)` guard
(the link loops already iterate capacity `C`); `linkForces` gained a 1-line hole-skip (`linkFilA<0`,
OOB-safety on never-used FREE slots — not a gather change, bit-identical to 5b on active/dead-keyed scenes).
Validated by 7 self-consistency checks (no v1 oracle — synthetic): distinct-slot/no-double-alloc;
free-list correctness; death→same-step reuse + fresh ring; overflow clamp; slot-stability; **CPU≡GPU
bit-identical (0 mismatches, 400 churn steps)**; all-OFF≡HEAD (K=0 ≡ 5b path; 5a/5b gates reproduce).
`BoA-v1ref` byte-clean; production byte-unchanged. New: `CrosslinkerSystem.freeFlags/freeScatter/allocate`
+ formation block in `CrosslinkerStore` + 5c-i checks in `CrosslinkerHarness`. Report:
`INC5A_CROSSLINKER_FINDINGS.md` (§5c-i appended); JOURNAL 2026-06-16 (5c-i).
**5c-i flag for the planner:** 5c-ii replaces ONLY the synthetic `fillRequests` (broad-phase FIL×FIL +
`checkToLink` gates + `P_form` RNG fill the same `req*`/`acceptFlag` arrays; the allocator rides
underneath unchanged — RNG ⇒ localWork=64). v2 does **same-step** death→reuse vs v1's form-at-collision /
free-at-cleanup (reusable step N+1) — a ≤1-step timing choice, not a correctness change.

**Increment 5c-ii — DONE (2026-06-16).** Crosslinker FORMATION — real formation filling the 5c-i request
arrays (the scan-rank allocator underneath UNCHANGED). Pipeline/step (after 5b unbind): `filFilCandidates`
(broad-phase FIL×FIL: distinct unordered cross-filament pairs, same-filament excluded, coarse capsule
bound) → `formGates` (per-candidate-LOCAL, race-free: v1 `checkToLink` alignment mode 0/1/−1 via ported
`fastAcos`, `lineSegmentIntersectTest` closest-approach vs `crossLinkGrabDist`, `orientSame`, loc+jitter,
and `P_form = 1−exp(−xLinkOnRate·xLinkConc·dtCheck)` wang-hash) → `formAdmitReduce`/`countActiveLinks`/
`formAdmit` (admission = **cap one new link per segment per step** via a deterministic per-segment
min-candidate-index reduction + start-of-step saturation + spacing ⇒ exact, no same-step cross-candidate
dependency, race-free) → 5c-i allocator → `placeOrient` (persists `orientSame` to `linkOrientSame`).
Both runners; localWork=64 on the RNG/gate kernels. **Analytic-oracle only** (gate-by-gate vs v1).
Default-off (pForm=0). `BoA-v1ref` byte-clean; production byte-unchanged. Validated (all 6 PASS):
#1 broad-phase candidate set (same-fil excluded, complete); #2 gate arithmetic bit-exact vs v1 (828
candidates spanning both boundaries, 0 mismatches); #3 P_form formula (Δ=0%) + cadence + empirical
(Δ=0.10%); **#4 the one-per-seg cap is NON-BINDING — 0.93% of would-be formations dropped in a near-worst-
case dense focal bundle** (contention ∝ N²·P_form², realistic ≪ this); #5 CPU≡GPU bit-identical (full
pipeline, 400 churn steps, 0 mismatches); #6 all-OFF≡HEAD (pForm=0 ≡ 5b/5c-i path). New: 6
`CrosslinkerSystem` kernels + formation block in `CrosslinkerStore` + 5c-ii checks. Report:
`INC5A_CROSSLINKER_FINDINGS.md` (§5c-ii appended); JOURNAL 2026-06-16 (5c-ii).
**5c-ii flags for the planner:** (a) **`Math.acos` does NOT lower on the PTX backend** — `fastAcos`'s middle
branch uses the `accurateAcos` poly (decision-bit-exact for the default π/12 threshold, which lives in the
ported sqrt branch); reuse `accurateAcos`, not `Math.acos`, in any future GPU kernel. (b) v1's
`lineSegmentIntersectTest` is degenerate/ill-conditioned for (near-)parallel segments ⇒ formation happens
at near-parallel **crossings**, not stacked-parallel pairs. (c) The one-per-seg cap is a deliberate
non-binding divergence from v1; 5c-iii's steady-state should re-confirm at production density. (d) The
**running-v1 oracle + `fracMove`-on-count remain DEFERRED to 5c-iii**.

**Increment 5c-iii — Phase 1 + Phase 2 DONE (2026-06-16).** Phase 1 (force law: dynamic `fracMove` +
torsion default-ON) analytic-gate green. **Phase 2 — the ASSEMBLED moving bundle + confinement-free v1
validation + demo.** `CrosslinkerBundleHarness` wires the full per-step loop (formation↔force/torsion↔
unbind↔integrate) over a many-filament free-rod bundle, both runners. Per-step order faithful to v1
(`BoxOfActin.doLoop`): zero→brownian→[checkInt=100] formation→unbind(ckLinkBreak, every step, BEFORE
force)→countActive(dynamic fracMove)→linkForces→torsion→2-pass gather→integrate→derive. **STABLE** (CPU
200 fil×6000; GPU mechanics 16-kernel graph 200 fil×3000, CPU≡GPU aggregate 0.000%). **The dominant v1↔v2
confound FOUND+FIXED: the fixture sets `aeta=1.0` Pa·s (10× the v2 `Constants.aeta=0.1`); v2 over-diffused
10× and dispersed.** Fixed by `applyAeta()` drag-scaling (FDT-consistent, not a physics change); post-fix
v2 diffusion matches v1. Validated against a walls-off `/tmp/v1xlink` scratch (`BoA-v1ref` byte-clean):
formation gate bit-faithful (funnel matches on identical config), **conc-scaling PASS** (halve
`xLinkConc`→halve formation, 2.0×). **Open/PAUSED: a residual ~3.5× walls-off link-count gap** (v1
22.5±1.3 vs v2 6.5±1.0 @ step 1500, 6-seed ensemble) — NOT within SEM; excluded gate/diffusion/unbinding/
conc-scaling as the cause; residual is in the crossing-population time-evolution (subtle coupling, not
root-caused). New: `CrosslinkerBundleHarness`, `run_xlinkbundle.sh`; report `INC5C-iii_PHASE2_FINDINGS.md`.
```
./run_xlinkbundle.sh -cpu -nfil 200            # CPU assembled run + stability
./run_xlinkbundle.sh -cpugpu -nfil 200         # GPU mechanics vs CPU (aggregate-within-SEM)
./run_xlinkbundle.sh -3js threejs_xlinkbundle -nfil 150 -conc 3   # crosslinking demo
./run_xlinkbundle.sh -singlelink               # Part B: single-link Brownian strain vs Boltzmann/equipartition
```

**5c-iii residual RESOLVED (2026-06-16, §8) — crosslinkers PHYSICALLY VALIDATED; v1 is NOT a quantitative
crosslinker oracle.** Per jba: v1's crosslinkers were **never calibrated to experiment** ⇒ v1 is a faithful
**component-port** reference but **NOT a quantitative emergent-behavior oracle**. The ~3.5× gap is adjudicated
against PHYSICS, and **both channels are v2-correct / v1-deviation:** (1) **formation ~1.9×** = a v1 mesh
double-draw ARTIFACT — v2's one-draw-per-crossing is correct; the calibration question is **DISSOLVED** (nothing
to recover ⇒ do NOT import the artifact, do NOT compensate `xLinkOnRate`). (2) **retention ~2×** — the decisive
single-link test (`-singlelink`): v2's Brownian steady-state strain MATCHES the **Boltzmann/equipartition**
prediction of its own (central, conservative) force law to **0.1%** (`P(L)∝L²exp(−U/kT)`, ratio 1.001;
drag-independent; CPU≡GPU bit-identical on the deterministic relaxation). v2 sits AT thermal equilibrium
(strain ~1.13 ON-COM / ~0.93 realistic ≈ the bundle's ~0.89); v1's ~0.42 is FAR BELOW it ⇒ **v1 is the
sub-thermal deviation, not v2.** ⇒ **ACCEPT v2, NO production fix.** The confined ≈49 plateau is **reframed**
as a future-increment **v2 self-consistency / physical-plausibility** check (formation≈dissolution at
confinement), **NOT a "hit 49" target** (v1 uncalibrated). Report `INC5C-iii_PHASE2_FINDINGS.md` §8.

Next: **5d (Arp2/3).** (The `STORE_CROSSLINKER` broad-phase publisher seam exists; 5c-ii/Phase-2 used a
self-contained FIL×FIL candidate generator over the filament pose — wiring the production SpatialGrid publisher
is an integration step.)

**Increment 6 (myosin structures: dimers / minifilaments / nodes) — recon DONE; 6a DONE.** Recon:
`INC6_MYOSTRUCT_RECON.md` (the coupling-cost map: **dimer = no gather**, **minifilament = single-ended one-pass
gather** — less than the crosslinker two-pass, **node = reachable WITHOUT membrane** on a fixed anchor but
fails the settledness gate; snapshot-currency: dimer/minifilament CURRENT @ 06-13, nodes NOT settled — fresh
snapshot needed). Suggested staging **6a dimer → 6b minifilament → 6c node**.

**Increment 6a — DONE (2026-06-17).** The myosin DIMER coupling (two motors), validated on a pre-placed
ISOMETRIC bed; static assembly, heads FREE. The **SIMPLEST** of the three structure couplings: each motor
belongs to exactly one dimer ⇒ the dimer **self-writes both sides directly into its two uniquely-owned
rod/lever sub-body slots — NO gather, no atomics** (disjoint pairing `motorA(d)=2d`, `motorB(d)=2d+1`).
Faithful port of v1 `MyosinDimer.enforceParallel/AntiParallel` (`MyosinDimer.java:163–273` rod couplings +
`:111–135` lever-align to 160°): the PAIRS spring (`moveC` reused VERBATIM from `MotorJointSystem`) + 4
rod-coupling variants + the lever-align torque; v1 defaults `myoDimerFracMove=0.2`,
`myoDimerLeverFracMoveTorq=0.4`. 6 gates PASS GPU+CPU: (A) force arithmetic isolated vs an independent
double reference **maxRel 6.6e-8** + exact equal-opposite; (B) rest hold (160.0000° exact fixed point); (C)
relaxation dt-invariant 8.4e-7; (D) lever angle Brownian-on stationary/bounded/FDT-thermal (mean 152.6° = a
fluctuation shift of the bounded θ coord, NOT drift — §8 posture: gated on FDT self-consistency not v1's
number); (E) CPU≡GPU det 3.5e-6 µm / Brownian Δ0.000°; (F) all-OFF≡HEAD bit-identical. **TornadoVM gotcha
(reuse for 6b):** the rod-link math must be inlined into the top-level @Parallel kernel — a helper with 2×
inlined `moveC` exceeds the 600-node inlined-callee cap. New: `DimerStore`, `DimerCouplingSystem`,
`MyosinDimerHarness`, `run_dimer.sh`. Report: `INC6A_DIMER_FINDINGS.md`; JOURNAL 2026-06-17 (6a).
```
./run_dimer.sh              # GPU + CPU cross-check (32 dimers / 64 motors): gates A–F
./run_dimer.sh -cpu         # CPU runner only (triage)
./run_dimer.sh -3js threejs_dimer -n 9   # viewer (Y-shaped dimers)
```
**Post-6a rotational-thermostat diagnostic (gate-D 1.40× RESOLVED).** Cut 1 (DECISIVE, `DiffusionHarness`
Config R): free-rod rotational diffusion `D_rot` −1.8% vs FDT `kT/bRotGam` ⇒ **thermostat at ½kT**. Cut 3
(`ThermostatDiag`): a directly-thermostatted confined rotational DOF sits at the **exact discrete-AR(1)
equipartition** `4kT·dt/(γ·c(2−c))` (0.992); the apparent 1.24× vs the continuum `2kT/k_θ` is the
`1/(1−c/2)=1.25` discrete correction for `c=coeff=0.4`. Cut 2: `⟨θ²⟩∝dt` (fracMove `k_θ=coeff·γ/dt`),
exactly the scheme's own equipartition at each fixed dt. ⇒ the dimer 1.40× = discrete-vs-continuum factor ×
residual gate-D AR(1) crudeness — **benign, no thermostat fix.** Report: `INC6A_DIMER_FINDINGS.md` §6a-thermo.

**Increment 6b — DONE (2026-06-17).** The myosin MINIFILAMENT (a rigid-rod backbone OWNING N dimers),
isometric bed, static, heads free. The **central favorable recon finding realized: SINGLE-ENDED, one pass**
— the backbone owns its dimers (each keyed to one backbone via `headBackboneSlot`, the motor→segment shape),
so the gather **REUSES `CrossBridge.csrHistogram/csrScan/csrScatter` VERBATIM** (keyed by `headBackboneSlot`,
`miniCounts[0]=nDimers/[3]=nBackbones`) + a `backboneGather` (the `segGather` pattern) — **one pass, no
crosslinker two-pass, no new gather machinery.** Backbone = the **3rd `RigidRodBody` instance** (shared
systems unchanged). Faithful port of v1 `MyoMiniFilament.constrainEnd1/End2Dimers` (`:436-528`): tether
`F=myoMiniFilFracMove·1e-6·strain/(dt·(1/rod.bTransGam.y+1/bb.bTransGam.y))` (plain perp drag, NO moveCoeff)
at `myo1.myoRod.end1` → an **axial** backbone attach point + an align torque to ±backbone axis;
`myoMiniFilFracMove=0.07`, `myoMiniFilAlign=0.01`, `numMyoDimersEachEnd=8`. 5 gates PASS GPU+CPU: (A)
**gather==brute bit-identical** + tether vs v1 double-ref maxRel 3.7e-8 + momentum 2e-19 N; (B) isometric
hold (Brownian-off exact fixed point, Brownian-on bounded thermal); (C) **CPU≡GPU** det 4.5e-6 µm; (D) FDT
self-consistency (stationary, dt-a-physics-param); (E) **all-OFF≡HEAD** bit-identical. `CrossBridgeSystem`
byte-unchanged (CSR reused verbatim); production byte-unchanged; `BoA-v1ref` byte-clean. New:
`MiniFilamentStore`, `MiniFilamentSystem`, `MiniFilamentHarness`, `run_minifil.sh`. Report:
`INC6B_MINIFILAMENT_FINDINGS.md`; JOURNAL 2026-06-17 (6b).
```
./run_minifil.sh            # GPU + CPU cross-check (8 backbones × 16 dimers): gates A–E
./run_minifil.sh -cpu       # CPU runner only (triage)
./run_minifil.sh -3js threejs_minifil -n 4   # viewer (backbone + dimer carpet)
```

**Increment 6 glide part 1 — DIMER-GLIDE — DONE (2026-06-17).** The dimer is now a FUNCTIONAL two-head
motor: a free dimer (NO anchor) whose two heads bind/walk on a pinned filament via `CrossBridge` (reused,
byte-unchanged), translocating under the head force. **The one new physics = the binding-dependent coupling
gate:** `DimerCouplingSystem.couple` gained a `boundSeg` param + a guard — the lever-align is **SUPPRESSED
when BOTH heads are bound** (v1 `MyosinDimer.java:276` `!myo1.onFil|!myo2.onFil`, `onFil⟺boundSeg≥0`), else
fires; rod-couplings unconditional (verified: that line is the ONLY binding gate). **One-impl: bit-identical
for 6a/6b** (their `boundSeg` is all `-1` ⇒ align always fires; 6a/6b re-ran bit-identical PASS). 4 gates
PASS GPU+CPU: (#1) force transmission — fil gather==brute bit-identical + momentum 2e-19 N + **CPU≡GPU**
4.4e-8 µm; (#2) **binding gate bit-for-decision vs v1** (fires both-free/one-bound rel 4.6e-10, suppressed
both-bound =0); (#3) translocation — free dimer walks **+9.38 nm +x toward the actin plus-end** (the Newton
reaction to 4b-iii's −x FILAMENT force; the free MOTOR walks opposite the surface-assay filament glide —
emergent, v1 informational); (#5) **all-OFF≡HEAD** dimer-off ≡ single-motor/4b-iii path bit-identical. New:
`DimerGlideHarness`, `run_dimerglide.sh`; modified `DimerCouplingSystem`/`MyosinDimerHarness`/
`MiniFilamentHarness` (+boundSeg gate, re-validated). `CrossBridge` + production byte-unchanged; `BoA-v1ref`
byte-clean. Report: `INC6_GLIDE_DIMER_FINDINGS.md`; JOURNAL 2026-06-17 (6-glide part 1).
```
./run_dimerglide.sh         # GPU + CPU cross-check: #1 transmission, #2 gate, #3 walk, #5 all-OFF
./run_dimerglide.sh -cpu    # CPU runner only (triage)
./run_dimerglide.sh -3js threejs_dimerglide   # viewer (free dimers walking on a pinned filament)
```
**Increment 6 glide part 2 — MINIFILAMENT-GLIDE — DONE (2026-06-17).** The 6b single-ended backbone gather
is now **LOAD-BEARING**: a static minifilament's heads bind/walk on a pinned filament via `CrossBridge`
(byte-unchanged), and the backbone gathers the collective cross-bridge load through the 6b tether. **The
headline = `backboneGather`==brute UNDER LOAD, bit-identical.** Combines 6b + dimer-glide (the
`boundSeg`-gated `DimerCoupling`) on the 4b-iii pinned-filament setup. **No existing file touched** — a new
harness only. **v1 verified: NO minifilament-level binding gate** (`MyoMiniFilament.constrainEnd1/End2Dimers`
tether unconditionally; `countBoundMotors` diagnostic-only) ⇒ the per-dimer `MyosinDimer:276` lever-align is
the only one (already ported). **Geometry:** backbone +x (FREE, no anchor), 6b-splayed dimers, +x filament
over the end2 up-head field ⇒ the v1 `rodDotFil≥0` predicate admits only end2 up-heads (one polarity engages
on a single filament — correct physics; bipolar stall/contraction needs the two-antiparallel-filament
geometry, next). Two INDEPENDENT single-ended gathers/step (backbone-keyed + segment-keyed, both
`CrossBridge.csr*` VERBATIM). 4 gates PASS GPU+CPU: (#1) UNDER LOAD — backbone gather==brute **bit-identical
(Δ0)** at load 2.81e-14 N + fil gather==brute (Δ0) + momentum |Σmotor+Σbb+Σfil|=9.8e-20 N + **CPU≡GPU**
7.4e-7/1.1e-7 µm (300 loaded steps); (#2) binding gates at population scale — 16 dimers mixed states, align
fires 11/suppressed 5, all match v1; (#3) bipolar collective (observe) — FREE backbone walks +10.85 nm, sign
tracks the gathered net; (#5) all-OFF≡HEAD bit-identical + control. Regression guard: 6a/6b/dimer-glide all
re-ran bit-identical PASS. New: `MiniGlideHarness`, `run_miniglide.sh`. Report:
`INC6_GLIDE_MINIFIL_FINDINGS.md`; JOURNAL 2026-06-17 (6-glide part 2).
```
./run_miniglide.sh         # GPU + CPU cross-check: #1 gather-under-load, #2 gates, #3 bipolar, #5 all-OFF
./run_miniglide.sh -cpu    # CPU runner only (triage)
./run_miniglide.sh -3js threejs_miniglide   # viewer (a minifilament's heads walking on a pinned filament)
```
**Increment 6 — MINIMAL CONTRACTILE ASSAY — DONE (2026-06-17).** The first genuinely contractile test: two
anti-parallel pinned filament chains pulled toward each other by a central bipolar minifilament; contractile
tension read at the pins. A faithful ASSEMBLY of the validated structures (4a/4b/6a/6b + inc-2 chain) + ONE new
device kernel (`PinSystem`, the v1 `applyBenchmarkPins` position-snap end-pin) + the host-side tension/stat
bookkeeping (1:1 port of v1 `captureContractilityTension`/`accumulateContractilityStats`). NO new force law, NO
new gather. **THE CRUX — chain-inclusive tension read:** the minifilament binds INTERIOR segments; the force
propagates via the chain (F3/F4) to the pinned plus-end. v2 has **NO separate jointForceSum** (the v1 GPU
`addDeviceJointForce` gotcha CANNOT recur) — `ChainBendingForceSystem.chainForces` + `CrossBridge.segGather` both
`+=` into the SAME `fil.forceSum`; read PRE-snap after both ⇒ chain-inclusive by construction. **The general
biological minifilament model (reworked per jba):** filaments offset in Y at **±0.05 µm** (v1 `contractFilYOffset`,
straddling); the minifilament is a **FULLY FREE rigid body undergoing Brownian motion** (backbone + rods + heads;
NO pin, NO centering — held only by its bipolar bonds) with **3D radially-splayed heads** (azimuthal φ per dimer).
The Brownian **thermal search** lets the heads find/bind the offset filaments (v1 dimer rods are axial ⇒ heads
reach ~28 nm, the Brownian wiggle bridges to 50 nm). ONLY the filament plus-ends are pinned; tension read there.
**Freeing the minifilament + 3D splay RAISED tension ~13× (~0.37→~4.7 pN) — the model correction was the fix.**
4 gates PASS GPU+CPU: (#1) crux — perturb interior seg 5 links from the pin: chain ON ⇒ pin 2.46 pN, chain OFF ⇒
0, read sums chain+direct cross-bridge; (#4) no-motor control — pinned tips held EXACTLY, tension relaxes to
3.3e-4 pN; (#2) **IT CONTRACTS** — both poles engage (avgBound ~3/pole), both anchor tensions net-contractile
(A≈+6.6 B≈+2.7 pN, asymmetric — free-body drift), mean ~4.7 pN = ~14000× the no-motor baseline; (#3) **CPU≡GPU** —
deterministic chain+PIN bit-identical float32 (validates `PinSystem` on device), chaotic full-Brownian path
aggregate-agrees. **Free-body finding (surfaced):** the FREE minifilament drifts (~0.1 µm) + engages in BURSTS
(peak ~24 pN) — honest biological behavior; per-pole tension fluctuates/asymmetric (averages over seeds); gate on
the long-run NET, not a stationary plateau. Held-bound is intrinsically unstable on a pinned filament (strain
can't relax ⇒ dynamic release mandatory — v1's reason). **The 12 pN break-force cap is ON (faithful to v1):**
v1's `MyoFilLink.ckRelease` UNCONDITIONALLY detaches a head whose cross-bridge > 12 pN, before the catch-slip
roll ("combat stiffness and force insanity"); v2's contractile assay now enables it (`setFaithfulRelease`). It
was the dominant fix — without it the free minifilament's drift over-stretched bonds ⇒ numerical stiffness
(segments tossed, peak ~24 pN, bursty/asymmetric ~4.7 pN mean); with it ⇒ steady symmetric ~2 pN, peak ~4 pN.
**Matched v1 comparison (BoA-v1ref `/tmp` scratch, CPU 50k, cap ON):** avgBound v1 5.38 / v2 6.5, avgTension v1
1.84 / v2 ~2.0 pN, peak v1 3.32 / v2 ~4.0 pN, both symmetric ⇒ **v2 ≈ v1 within SEM on every channel. Verdict:
SHARED FAITHFUL PHYSICS, quantitatively matched** (no bug; the original low-tension was the deleted bespoke
version, the bursty-high was the missing cap). Step-3 force-coverage audit (`-audit`): pin `forceSum` = chain +
gather, residual 0, pin force purely chain-transmitted (the `jointForceSum`-omission gotcha cannot occur). New:
`PinSystem`, `ContractileAssayHarness`, `run_contractile.sh`. Report: `INC6_CONTRACTILE_ASSAY_FINDINGS.md`
(§4/§6b/§7b); spec: `INC6_CONTRACTILITY_ASSAY_SURVEY.md`; JOURNAL 2026-06-17. Optional next: port v1's confining
chamber box (removes the residual mild drift).
```
./run_contractile.sh            # GPU + CPU cross-check: #1 crux, #4 control, #5 no-op, #6 general, #7 box CPU≡GPU, #2 contracts, #3 CPU≡GPU
./run_contractile.sh -cpu       # CPU runner only (triage)
./run_contractile.sh -cpu -drift 50000   # matched box OFF vs ON (drift + tension)
./run_contractile.sh -3js threejs_contractile -steps 30000   # viewer (the v1 contractility panel)
```

**Increment 6 — GENERAL IN-VITRO-CHAMBER CONTAINMENT BOX — DONE (2026-06-17).** A general,
entity-agnostic containment primitive (`ContainmentSystem`) — the simulation-domain boundary, the
stand-in for the **in vitro experimental chamber** (coverslip / flow-cell) that bounds every in vitro
assay. **It is SHARED INFRASTRUCTURE over `RigidRodBody`** (like the integrator / Brownian / derive
systems): one kernel over a body's pose+drag+accumulators, invoked **once per store**, so it confines
filament segments, motor sub-bodies, minifilament backbones, and (future) nodes with the SAME code —
confining **POSITIONS, not class identities**. **It is NOT the membrane subsystem** (the deferred
dynamic cell cortex, inc 7, is a distinct later thing — this is the simple static experimental
boundary). Faithful port of v1's **free-body box law**: detection `Chamber.amICollidingOuter`
(`Chamber.java:125-138`) + force `MyoMiniFilament.checkOuterBugCollision` (`:546-560`,
`mag=nodeFracMove·1e-6·delta·bTransGam.x/collisionDeltaT` at both endpoints ⇒ force+torque, every
`collisionCheckInt=10` steps via a step-gate so the GPU graph stays fixed; v1 `nodeFracMove=0.5`,
`collisionDeltaT=1e-4`, R=0.005). **The SAFETY property — no-op when not binding:** a body inside the
inset box yields zero penetration ⇒ the accumulators are NOT touched (no `+=0`, no write) ⇒ adding it to
an in-bounds harness is **bit-identical** (the regression guard, by construction). **NOT ported
(flagged — abstract-from-the-second-instance):** v1's SEPARATE `FilSegment.bugForcesFromInside` law
(`0.1·min(fturn,ftrans)`, an extra torque-drag clamp) — never exercised (the contractile filaments are
pinned+inset, never reach a wall); v1 also leaves individual `MyoMotor`s un-boxed (only the
minifilament backbone is actively confined; v2 matches). 7 contractile gates PASS GPU+CPU (#5 no-op
bit-identity: HUGE-box ≡ box-off bit-identical; #6 general: a filament seg / motor sub-body / backbone
each placed past a wall pushed back inward; #7 box CPU≡GPU: force ΔF=0.0 exact, torque float32 FMA
last-bit) + **9 prior harnesses re-run bit-identical**. **The box is a faithful QUIESCENT no-op in the
cap-ON contractile scene** (box ON ≡ box OFF bit-identical on every channel): the free minifilament's
residual ~0.12 µm drift is **AXIAL** (x; box half-wall 1.995 µm, 17× the drift — can't tighten it),
while the lateral Y/Z the thin box tightly confines (walls 0.145/0.095) stays at 0.070/0.045 — the 12 pN
cap (c7a2257) already keeps the minifilament inside the chamber. So the chamber is present, faithful,
and ready (gate #6 proves it fires the instant a body crosses a wall) but does not engage here — it
neither tightens the axial residual nor perturbs the within-SEM match (the safe outcome; the cap was the
steadiness fix, the box is the general primitive for its own sake). New: `ContainmentSystem` + contractile
`-drift` mode + gates #5/#6/#7. Report: `INC6_CONTAINMENT_FINDINGS.md`; JOURNAL 2026-06-17.

**Increment 6c Stage A — the protein NODE entity (radial motor-bundle, fixed anchor) — DONE (2026-06-18).**
The protein node built FRESH as a motor-bundle (recon `INC6_NODE_RECON.md`): a fixed-anchor sphere node —
the **4th `RigidRodBody`** (isotropic sphere drag, radius 0.05 µm, NEVER integrated = the v1 `AnchorNode`
immobilization) — owning **radially-splayed singlet myosins + dimers**. The node mechanism IS the
minifilament's (a rigid body owning motor-children via a fracMove tether + a single-ended backbone-side
gather); the only differences are GEOMETRY (radial sphere-surface splay vs the minifilament's axial
end-clusters) + the node also carries singlets — both PLACEMENT. **The ONE new kernel = `NodeSystem.tether`**
(radial surface tether): faithful port of v1 `ProteinNode.keepMyosinsOnSurface`/`keepMyosinDimersOnSurface`
— the SAME fracMove spring LAW as the minifilament (`F=coeff·1e-6·strain/(dt·(1/rod.bTransGam.y +
1/node.bTransGam.y))`, from the rod end1) with RADIAL attach (`surface=coord+ru·u+ry·y+rz·z`, zVec=u×y
in-kernel). SINGLET coeff `attnForce/numNodeMyos` (0.4/nSing), force at the rod CENTER (no torque); DIMER
coeff `attnForce·myoDimerFracMove` (0.08), force at the rod END1 (+torque), node reaction at the surface
point; NO axis-align torque (verified BoA-v1ref — unlike the minifilament). The radial tether is the node's
LOCALIZED physics (the per-entity-system pattern), NOT a fork: radial splay genuinely needs y/z offsets +
the singlet/dimer torque asymmetry, inexpressible by the axial minifilament tether — it reuses the tether
LAW + the gather machinery BYTE-UNCHANGED. **Reused byte-unchanged:** the single-ended gather
(`CrossBridge.csr*` keyed by `attachNode` + `MiniFilamentSystem.backboneGather` over a stride-6 `nodeData`);
binding + cross-bridge; the **12 pN cap** (`setFaithfulRelease`); `ContainmentSystem`; the shared rod
systems; Motor/Dimer stores + coupling. 7 gates PASS GPU+CPU: #1a gather==brute isolated (Δ0, momentum
3.4e-20 N, 12 singlet+12 dimer owned); #1b gather UNDER LOAD (node+fil gather==brute Δ0 at real cross-bridge
load, full-system momentum 1.6e-19 N, **CPU≡GPU 2.1e-6 µm**, 23-task TaskGraph); #2 a radial head binds via
the real pathway; #3 the 12 pN cap fires on a 13 pN node bond (capStats=1); #4 containment confines the node
body (0.180→0.167 µm); #5 fixed anchor Δpose=0 under load; #6 all-OFF≡HEAD bit-identical + control.
**TornadoVM:** 20 logical tether args → 15 via planar packing (`attachKey`=node|motor, `radial`=X|Y|Z,
signed `attachCoeffK` carries atEnd1) + in-kernel zVec. **Seam #1 (separable motor/nucleation) kept OPEN**
for Stage B. New files only: `NodeStore`, `NodeSystem`, `ProteinNodeHarness`, `run_node.sh`; no shared file
touched ⇒ prior harnesses byte-unchanged (minifil+dimer re-run PASS); `BoA-v1ref` byte-clean; production
untouched; node default-off. Report: `INC6C_NODE_STAGEA_FINDINGS.md`; JOURNAL 2026-06-18.
```
./run_node.sh              # GPU + CPU cross-check (gather, gather-under-load, binding, cap, containment, anchor)
./run_node.sh -cpu         # CPU runner only (triage)
./run_node.sh -3js threejs_node -n 3   # viewer (radially-splayed nodes)
```
**Increment 6c Stage B1 — the FilamentStore runtime-birth lifecycle — DONE (2026-06-18).** The **first dynamic
filament creation in SoftBox** (`FilamentStore` was fully static through inc 6; recon §2 risk). v2-side
infrastructure INDEPENDENT of v1's churning nucleation specifics, validated with a SYNTHETIC birth (B2 wires the
node's real nucleation as the birth SOURCE). **`filState` sentinel** (mirrors crosslinker `linkState`): `>=0`
ACTIVE / `<0` FREE, **default all-ACTIVE** ⇒ existing harnesses unaffected. **Allocator = the inc-5 scan-rank
free-list reused VERBATIM, one level up** (`FilamentBirthSystem`: `freeFlags`/`freeScatter` + `CrossBridge.csrScan`
×2 byte-unchanged; `allocate` claims `freeList[rank<nFree]`, writes the FIXED-LENGTH seed pose (v1 actinSeed=3 ⇒
≈10.8 nm; growth deferred), turns on Brownian, flips FREE→ACTIVE; race-free, no atomics ⇒ bit-identical CPU↔GPU).
A born seed = a free rod (neighbors -1; v1 nucleates one FilSegment born bonded to the NODE). **THE LOAD-BEARING
DECISION — the active-guard is DATA-DRIVEN, not a per-kernel branch:** a FREE slot is inert by its data
(`markFree` zeroes brownTransScale/brownRotScale ⇒ no Brownian; neighbors -1 ⇒ free rod; parked inside box ⇒
containment no-ops; forceSum=0 ⇒ integrator v=0), so **NO shared device kernel is touched**
(integrate/Brownian/derive/chain/containment/gather byte-unchanged) ⇒ the **no-op-when-all-active guarantee is BY
CONSTRUCTION** (prior harnesses byte-unchanged). The ONE branch B2 will add — keeping a FREE slot out of the
broad-phase (a publish-time `filState` guard) — is deferred; B1 parks FREE slots off the candidate set by geometry
(gate C proves a parked FREE filament is NOT bound). 3 gates PASS GPU+CPU: (A) allocator — free-list index order,
distinct-slot/no-double-alloc, born payload, slot-stability (Design A), overflow clamp, same-step reuse after a
synthetic free, CPU≡GPU Δ=0; (B) **born@0 ≡ preplaced bit-identical** (Brownian off AND on, max|Δpose|=0) + FREE
slot inert (stays exactly parked) + non-J filaments unperturbed (Δ=0) + participates after birth + CPU≡GPU Δ=0;
(C) a born filament is bound (0→8 motors) + gathers cross-bridge load (gather==brute Δ=0), a parked FREE filament
is not bound. **Regression (no-op-when-all-active):** node/minifil/dimer/dimerglide/miniglide/stroke/xbridge/
motor/contractile/xlink all re-run PASS + foundational FDT within 5%. New files only + 1 additive `FilamentStore`
edit; `BoA-v1ref` byte-clean; production untouched. Report: `INC6C_NODE_STAGEB1_FINDINGS.md`; JOURNAL 2026-06-18.
```
./run_filbirth.sh           # GPU + CPU cross-check (allocator, born≡preplaced, inert free slot, binding+gather)
./run_filbirth.sh -cpu      # CPU runner only (triage)
```
**Increment 6c Stage B2 — the node NUCLEATION-FUNCTION (formin actin nucleation) — DONE (2026-06-18).** The
node's implicit-formin nucleation (seam #1, additive over Stage A) — **the first dynamic actin CREATION in
SoftBox; the LAST entity port lands**, completing the node (motor-bundle + nucleation). Built from **jba's
behavioral spec** (NOT a v1 port; v1 = clean-specifics reference, drift flagged). Per node per step: birth a
fixed-length seed (B1 allocator, **UNCHANGED**) at `kNodeNuc·dt`; hold it with an ELASTIC fracMove tether
(node-center ↔ seed-end, the SAME spring as `NodeSystem.tether`/minifilament, attach-at-center); DISSOLVE the
bond at a **constant rate** → free filament; the born seed is **Brownian-DAMPED**. Deplete the actin pool
(seam #2). **v1 clean specifics:** kNodeNuc=10/node·s, actinSeed=3 (≈10.8 nm), nodeTetherDetachRate=0.001/s,
fracMove=0.5. **FLAGGED v1 drift (built jba's spec, did NOT copy):** v1's detach+max-strain are INACTIVE by
default (B2 enables the rate); the v1 node-tether release is a CONSTANT rate, NOT Bell/log-stretch (recon §2a
wording imprecise); v1 has an optional nodeTorqSpring align torque (active) NOT in jba's spec — B2 omits it
(positional tether only; flag for jba); forminsPerNode default 0 = off. **THE DAMPING-AS-dt-COMPENSATION
PRINCIPLE (jba; generalizes to the membrane nucleation):** a short fixed-length seed flails at full thermal;
the formin's TIGHT hold is a STIFF constraint inexpressible at the large production dt (the same fracMove
dt-stiffness family) — so a SOFT elastic tether (positional, dt-compatible) + artificial Brownian damping
(~30×, **the seed only**) compensating for the tether's softness approximate it. A legitimate dt-compensating
approximation, deliberately **non-FDT for the seed** (existing filaments keep scale 1.0 — NO Brownian-system
edit); NOT an FDT bug, NOT node-coupling stiffness (the tether handles coupling). **Architecture:**
`NodeNucleationSystem` (countBoundFil/emit/tagSeeds/seedTether/dissolve — wang-hash RNG, no atomics,
dual-runner). Lifecycle: `filState` (B1: slot alive?) ⟂ `seedNode` (B2: tethered to which node? `<0`=free);
dissolution sets seedNode=-1 but keeps filState ACTIVE ⇒ free filament (slot NOT freed; turnover deferred).
**The ONE shared-kernel touch (B1-flagged):** a guarded `publishToBodyView` **overload** — a FREE slot →
`STORE_NONE` (excluded from the narrow-phase, so a motor can't bind a not-yet-born filament); the 8-arg is
byte-unchanged, the 9-arg ≡ it when all-active. `ActinPool` = **seam #2** (scalar now / field later, behind
`available()`/`take()`). 8 gates PASS GPU+CPU: rate (1.097e-4 vs 1.0e-4, 9.7%/Poisson); tether (vs v1
double-ref rel 2.1e-8, relaxes/bounded); dissolution (pre-tethered 4000, empirical pDetach 0.1% vs rate·dt at
an elevated 2000/s — v1's 0.001/s ⇒ 1e-8 unobservable, validated by formula; freed seeds stay ACTIVE); pool
(depletes exactly + `available()` gate); no-op-when-off (forminsPerNode=0 ⇒ 0 births, Δcoord=0); CPU≡GPU
(seedNode/filState **0 mismatches** = bit-identical lifecycle, pose Δ 4.66e-10 µm); damping (wander 4.35e-5 vs
1.30e-3 undamped); publish-guard (FREE→STORE_NONE, no-op when all-active). Regression:
filbirth/node/grid/motor/minifil/dimerglide/miniglide/contractile bit-identical. New files + additive edits
only; `BoA-v1ref` byte-clean; production a no-op (`forminsPerNode=0`). Report:
`INC6C_NODE_STAGEB2_FINDINGS.md`; JOURNAL 2026-06-18.
```
./run_nodenuc.sh           # GPU + CPU cross-check (rate, tether, dissolution, pool, no-op, damping, publish-guard, CPU≡GPU)
./run_nodenuc.sh -cpu      # CPU runner only (triage)
```
**Increment 6 — the NODE in the MINIMAL CONTRACTILE ASSAY (node ⇄ minifilament swap) — DONE
(2026-06-18).** Qualitative "the node does contractile work": SWAP the free minifilament for a free,
box-confined protein NODE at the overlap centre of the contractile assay; its radial myosins bind the two
anti-parallel pinned filaments and pull them into contraction, tension read through the existing
instrumentation. **A harness COMPOSITION over validated pieces — NO new force law / gather / shared-kernel
change** (reused byte-unchanged: the contractile scene + `PinSystem` + chamber box + 12 pN cap +
chain-inclusive pre-snap read + the Stage-A node tether/gather + binding + containment). **Both poles
engage NATURALLY** — the node's radial Fibonacci splay + the v1 `rodDotFil≥0` polarity sort make the **+x
hemisphere bind filament A, the −x hemisphere bind filament B** (the radial node is intrinsically bipolar;
no bespoke per-pole placement). The two filaments straddle the node in ±Y, overlapping across it, pinned at
opposite +x/−x plus-ends. 4 gates PASS GPU+CPU: #2 IT CONTRACTS (steady tension A=+1.24 / B=+1.79 pN both
contractile, avgBound A=3.28 / B=3.81 both poles, mean 1.52 pN = 4660× baseline, peak 4.99 — **same regime
as the minifilament**, v1 ref 1.84 pN, the SANITY ballpark not a target since v1's assay used a
minifilament ⇒ no v1 numeric oracle for a node, §8); #3 CPU≡GPU (deterministic chain+PIN bit-identical Δ
7.1e-8 µm; chaotic windowed avgBound GPU 2.10 = CPU 2.10); #4 no-motor control (pins hold exactly, tension
→0.00033 pN); #5 the chamber confines the free node (entity-agnostic, no-op inside bit-identical, inward
past a wall). **Free (default) vs fixed-anchor (`-anchor`, the ring's mode)** both validated, same regime.
Nucleation OFF (exercises the MOTOR-function). New files only (`NodeContractileHarness`,
`run_nodecontract.sh`) ⇒ prior harnesses bit-identical (node/minifil/contractile/dimer re-run PASS);
`BoA-v1ref` byte-clean; production untouched. Report: `INC6_NODE_CONTRACTILE_FINDINGS.md`; JOURNAL
2026-06-18. **Foreshadows the post-node fixed-anchor contractile RING.**
```
./run_nodecontract.sh        # GPU + CPU: #2 contracts, #3 CPU≡GPU, #4 control, #5 containment
./run_nodecontract.sh -cpu -diag                              # per-pole engagement diagnostic
./run_nodecontract.sh -3js threejs_nodecontract -steps 30000  # viewer (v1 contractility panel, node centre)
```

**Increment 6c — actin POLYMERIZATION: barbed-end elongation (lengthen + split, growth-only) — DONE
(2026-06-18).** The **first dynamic actin GROWTH in SoftBox** (filaments were static-length through inc 6).
Filaments elongate at the **node-side barbed end** (recon `INC6C_POLYMERIZATION_RECON.md`, granularity fork
resolved favorably): **the granularity mapping is "lengthen the terminal segment, then split"** — v1 and
SoftBox are the SAME shape (a length-mutable rod carrying `monomerCount`, `segLength=(monomerCount+1)·
actinMonoRadius`, the drag-from-`monomerCount` recompute on both sides), so growth turned on a **dormant,
shape-compatible** path, NOT a biochem layer. Per biochem cadence: `grow` lengthens (`monomerCount++` at
**[actin]-dependent** `P=onRate·conc·biochemDeltaT`, `coord += ½·monoRadius·uVec` keeping end1/node FIXED, end2
OUTWARD — v1 `incCoord`); at 64 monomers `markSplits`→**B1 scan-rank allocator (REUSED VERBATIM)**→`splitWire`
shrinks the parent (32, end1 fixed), sets the child (32), and **rewires the chain neighbors of {parent G, child
C, G's old end2-neighbor Mold}** so the chain stays linear `node—G(tip)—C—Mold—…` (distinct tips ⇒ distinct
{G,C,Mold} ⇒ race-free, no atomics). Growing tips = node-bonded segments (`seedNode≥0`, reusing B2's bond).
**The device drag recompute decision = DEVICE** (recon flag d): `Math.log` LOWERS on the PTX backend
(`BrownianForceSystem` proves it), so `recomputeDrag` is a real device per-slot port of `DragTensorSystem.run`
(segLength + clamp + the SHARED rod-drag formula), not the host all-slots fallback. **The pool is seam #2**:
growth READS `ActinPool.conc()` (B2 nucleation was [actin]-INDEPENDENT — growth adds the read) + DEPLETES it
per monomer. **8 gates PASS GPU+CPU:** (1) lengthen (recomputeDrag==host DragTensorSystem maxRel 1.1e-7;
P_emp 0.1738 vs 0.1740); (2) **split@64 — the headline correctness gate** (lone + inserted-between-`Mold`
cases both give a valid reciprocal linear chain, conserved 64→32+32, child outward, end1 fixed, **CPU≡GPU
bit-identical lifecycle Δcoord 1.4e-9 µm**); (3) rate first-order (P(15µM)/P(7.5µM)=2.005) + pool depletes
conservatively + slows as it drains; (4) growing-end (contour 0.086→2.50 µm/29×, tip held 4e-5 µm from node);
(5) **no-op-when-off bit-identical to a static baseline**; (6) CPU≡GPU full pipeline 12000 steps (Δcoord
0.00); (7) drag-clamp fidelity (3-monomer seed clamps to stdSegLength·mono — **faithful to v1
FilSegment:409-419**, recon flag c); (8) participates + dt-stable (80k Brownian steps, bounded, chain
reciprocal valid). **Default-OFF; B1/B2/node regressions bit-identical.** New files only:
`GrowthSystem`/`GrowthStore`/`GrowthHarness`/`run_growth.sh` +3 Constants additions (no existing value
changed) ⇒ prior harnesses byte-unchanged; `BoA-v1ref` byte-clean; production untouched. Report:
`INC6C_POLYMERIZATION_FINDINGS.md`; JOURNAL 2026-06-18.
```
./run_growth.sh            # GPU + CPU cross-check (lengthen, split@64, rate+pool, growing-end, no-op, drag-clamp, participates, CPU≡GPU)
./run_growth.sh -cpu       # CPU runner only (triage)
```
**Flagged v1 divergences (behavior-faithful, not class-faithful):** formin kept on the stable tip slot G (v1
`transferEnd2Plasmid` moves it to the child — topologically equivalent); **DEPOLYMERIZATION/TREADMILLING
DEFERRED** (the next layer, tied to filament turnover; growth-only/monotonic is what Test B bridging needs —
`ActinPool.put`/restore NOT added yet); no per-monomer nucleotide (ATP→ADP) state; the stall-force modulation
(`getPolyRateEnd2`) + the `nodeTorqSpring` align torque deferred (second-layer refinements). Capacity bounds run
length (split children persist without turnover; a tip with no free slot simply doesn't split — graceful).

**Increment 6c — Test B: the SCPR primitive (two nodes capture-and-pull) — Gate 0 PASS; Stage 1 assembled
(2026-06-18).** The first **emergent** test (porting→emergence). **Pure COMPOSITION** of validated pieces —
NO new force law / gather / shared-kernel edit; new files only (`TestBScprHarness`, `run_testb.sh`) ⇒ prior
assays + production byte-unchanged, `BoA-v1ref` byte-clean. **Gate 0 (GATING) PASS — cross-node capture works:**
the one real unknown was whether binding rejects a foreign-node segment / filters on `seedNode`; it does NOT —
`seedNode` is **structurally absent** from `BindingDetectionSystem`/`CrossBridgeSystem`/`SpatialGrid`/
`SpatialBodyView` (lives only in nucleation/growth), the path is geometric + `ownerStore`-filtered only. Probe:
a filament tagged `seedNode=A` at node B's head ⇒ node-B motor binds it, **bit-identical CPU≡GPU**. **Stage 1 —
two FREE box-confined nodes formin-nucleate + grow + capture + (try to) pull**, full per-step loop on CPU + a
**45-task device-resident GPU TaskGraph**, **CPU≡GPU aggregate-agree** (avgBound 15.60=15.60, active-fil 56=56).
**Integration crux solved w/o a shared-kernel edit:** nucleation + growth both allocate from one `FilamentStore`
but `emit` clears only `acceptFlag[0..nNodes)` while `markSplits` clears all ⇒ **nucleation gets dedicated
request+rank arrays**, growth uses the store's; two sequential B1-allocator passes share the rebuilt free-list;
FREE slots **parked far** stay off the brute-reachable candidate set (no `filState` binding guard). **Readout:**
cross-node captures **OCCUR** stochastically (peak ~4); **self-capture DOMINATES** (~30 — own radial filaments
in own shell). **The clean net inter-node approach at n=2 is an OBSERVATION, not a clean positive** (the task's
predicted SCPR rarity): net distance is seed/geometry-dependent + tends to drift **apart** — **the partner steals
your near-side filaments ⇒ residual self-pull is toward your far side, away from the partner** (a real n=2
geometric artifact, NOT a sign bug — the pull direction is validated by Gate 0 + the contractile assay, same
`CrossBridgeSystem`; cross-capture needs the foreign filament to nearly bridge to the partner per `rodDotFil≥0`).
Many-node ring condensation + ensemble confirmation = follow-on. **`-nodebrown`** (default 0.05) damps the tiny
node sphere's thermal wander to resolve the directed regime (node = large/slow complex in vivo; node-body scale
only). Report: `INC6C_TESTB_SCPR_FINDINGS.md`; JOURNAL 2026-06-18.
```
./run_testb.sh             # GPU + CPU: Gate 0 → CPU≡GPU → Stage 1 (distance trace + cross/self-capture readout)
./run_testb.sh -cpu        # CPU runner only (triage)
./run_testb.sh -gate0      # Gate 0 only (the cross-node-capture gating probe)
./run_testb.sh -cpu -3js threejs_testb   # viewer (two nodes nucleating/growing/capturing)
```

**Increment 6c — Test B′: clean AIMED SCPR (sparse, separated, SPECIFIED placement) — SUCCESS (2026-06-18).**
The clean SCPR test (jba's design): two well-separated nodes, each growing ONE actin AIMED at the partner,
capture one another's filaments and **measurably approach over a gap**. Extends `TestBScprHarness` (`-aimed`
preset) — no new harness, no shared-kernel edit, no existing value changed ⇒ prior assays + production
byte-unchanged. **Realizes seam #3's SPECIFIED placement** (aim-at-partner). **HEADLINE:** inter-node distance
0.600 → **MIN 0.424 µm @ step 11812 (initial approach Δ=0.176 µm), EXCEEDS Brownian noise** (≈60×) ⇒ `STAGE 1
demonstrates SCPR capture-and-pull`; CPU≡GPU agree. The aimed filament is **pre-grown to OVERSHOOT** the partner
(the `rodDotFil≥0` gate needs the foreign filament to reach the captor's far hemisphere; then the barbed-end
stroke pulls the captor toward the partner). **jba's "self-capture negligible by LAYOUT" thesis — refuted in
magnitude, NON-BLOCKING in effect:** the aimed layout reduces self-capture (~30 random→~5 aimed) but doesn't
preclude it (the filament exits through its own node's partner-facing hemisphere; capture-phase self/cross force
20/14.5 pN) — yet the approach succeeds because self-capture is **internal to a node** (no net motion) while
cross-capture carries the net pull (jba's intuition holds operationally). **Post-min OVERRUN (OUT OF SCOPE):**
monotonic growth + no depoly ⇒ the filament overruns the closed gap and the nodes drift back apart — this is the
INITIAL-approach test; sustained contraction needs turnover (deferred; harness flags the overrun). Report:
`INC6C_TESTB_AIMED_SCPR_FINDINGS.md`; JOURNAL 2026-06-18. `./run_testb.sh -aimed` (`-cpu`, `-3js threejs_testb_aimed`).

**Increment 6c — faithfulness fix: v1's node-held binding exclusion restored (2026-06-18).** The v1 audit
(`INC6C_V1_SELFCAPTURE_AUDIT_FINDINGS.md`) found v2 unfaithful: v1 excludes any node-held filament segment from
myosin binding (`BoA-v1ref/boxOfActin/MyoMotor.java:391-392`, `if (soaNodeAtEnd2) return;`) and v2's
`reachTestDistSq` dropped it in inc 4a (no nodes then) and never restored it. **Restored** as additive
`BindingDetectionSystem.bruteReachableNodeAware`/`bindNearestNodeAware` — one data-driven line `if
(seedNode.get(s) >= 0) continue;`, **TIP-ONLY** (v2's `seedNode≥0` sits on exactly the node-held tip = v1's
barbed `nodeAtEnd2`; outer/released `seedNode<0` stay bindable ⇒ cross-capture survives). The ORIGINAL bind
methods are byte-unchanged; gliding/contractile/Test A/motor/stroke call them ⇒ unaffected (re-ran PASS). Test B′
re-run: self-capture force **20.0→12.4 pN** (self/cross 1.38→1.07), **0 binds on a node-held tip**, cross-capture
+ the beyond-noise approach SURVIVE, CPU≡GPU agree. **GEOMETRY CAVEAT (flagged, NOT fixed):** the residual
self-capture is entirely on OUTER (`seedNode<0`) segments ~0.124 µm from the own node, within own-myosin reach
(~0.183 µm) — a v2 geometry divergence (v1's exclusion is also tip-only; the gap is likely closed by the
**force-dependent formin RELEASE**, the flagged next piece — Test B set `detachRate=0`). So `seedNode`/v1
`nodeAtEnd2` now has **THREE roles**: (1) nucleation bond, (2) the elastic tether, (3) **binding exclusion** —
role 3 is the one the node recon missed (`INC6_NODE_RECON.md:128,136`). Report:
`INC6C_SELFCAPTURE_RULE_FINDINGS.md`; JOURNAL 2026-06-18.

**Increment 6c — BARBED-END CONVENTION SWAP: v2 now uniformly barbed=end2 (= v1) (2026-06-19).** The
self-grab's ROOT CAUSE (`INC6C_BINDING_ORIENTATION_DIAGNOSIS_FINDINGS.md`): v2 was internally **inconsistent** —
all shared systems + non-node assays used **barbed=end2** (uVec→plus, = v1), but the inc-6c node/growth/nucleation
subsystem used **barbed=end1** (node-filament uVec OUTWARD), which **inverted the `rodDotFil≥0` gate** for a node's
own filament ⇒ self-grab. **FIXED at the root** by realigning ONLY the node subsystem to barbed=end2 (the survey
`INC6C_CONVENTION_SWAP_SURVEY.md`): the node-attached filament's `uVec` now points **INWARD** (barbed end2 at the
node), so v2's **UNMODIFIED** bind gate computes `rodDotFil<0` for own outward myosins and rejects self-grab as v1
does — **NO gate / §A shared-system edit.** Atomic §B swap (`NodeNucleationSystem` emit/seedTether; `GrowthSystem`
grow/markSplits/splitWire — coord-shift signs negated, parent keeps end2 fixed, 3-slot rewire mirrored;
`TestBScprHarness` placeAimedChain/warm-start/filNodeOf; harness gates). **Coord-bit-identical** (each coord op's
sign flips WITH the uVec flip ⇒ cancels; only uVec negated + end labels swapped). Regression (3 tiers green): §A
**bit-identical** (gliding/contractile/dimer/minifil/motor/xlink/dimerglide/miniglide/xbridge/stroke — byte-unchanged
paths); §B gates re-pass (growth/nodenuc/filbirth/node; split@64 rewire valid + conserved + CPU≡GPU bit-identical
lifecycle); **Test B′ self-grab GONE** (self-capture 0.00 / 0.000 pN, was 12.4; cross-capture survives peak 10;
nodes approach 0.600→0.483 µm ~27× noise) — reproduces v1's clean-coalescing twoNodeFormin. The §6c
SELFCAPTURE_RULE "geometry caveat" (residual outer-segment self-grab) is **resolved by this swap** (not the deferred
formin release). Report: `INC6C_CONVENTION_SWAP_FINDINGS.md`; JOURNAL 2026-06-19. **The convention is now settled
codebase-wide before the contractile ring builds on it.**

**Seams registry (parameterized extension points kept OPEN):** **#1 motor/nucleation** (the node's
motor-function is separable from its nucleation-function — Stage A); **#2 the actin pool** (`ActinPool`, scalar
now / a depletable field later, behind `available()`/`take()`/`conc()`); **#3 formin-site placement**
(`TestBScprHarness.forminSiteDir(node,site)` + the `Placement` enum: RANDOM-radial default (Test B); **SPECIFIED
realized (Test B′, aim-at-partner)** — a general specifiable aim/target per site; specified-beyond-aim NOT built).

**Migration edge (the node + GROWTH are COMPLETE; these wait on v1 / membrane work):** depolymerization /
treadmilling (the next layer — pointed-end shrink + `ActinPool.put`/restore, tied to filament death/turnover);
filament death/turnover (freed seeds + split children persist — if a long run accumulates too many, that bounds
run length: flag); the **membrane formin nucleation** (jba's in-development damped-filament work — the damping
principle generalizes); branched networks; the dynamic cortex; the optional `nodeTorqSpring` alignment.
**Post-node horizon:** **Test B — Gate 0 PASS + Stage 1 assembled (2026-06-18, above):** cross-node capture is a
working primitive (CPU≡GPU); the clean net approach at n=2 is confounded by self-capture (an n=2 artifact —
ensemble / ring condensation is the follow-on). Remaining horizon: the many-node **fixed-anchor minimal
contractile RING** (a ring of nucleating + growing nodes + the contractile-assay tension read — all primitives
now exist; this is where SCPR condensation is a many-node statistical effect, not the n=2 artifact) and Test B
ensemble/aimed-placement confirmation.

Also pending within inc 6: **stronger engagement** for a sharp contractile plateau (down-head filaments /
multiple minifilaments — a tighter/denser scene would make the chamber box load-bearing) + dynamic
minifilament assembly/`myoMiniLifetime`.

**Increment 7 (actin turnover) — Stage 0/1 depoly+death, AGING proxy, cofilin SEVERING, viewer — DONE (see JOURNAL
2026-06-19/06-20). DEAD-SLOT REUSE FIX — DONE (2026-06-21).** The flagged hazard (a nucleation-reused dead slot
born `monomerCount=0` + stale-ADP `nucFrac`, INC7_STAGE1_FINDINGS.md §"Reused-slot monomerCount") is closed:
nucleation now FULLY initializes the newborn via `NodeNucleationSystem.initNewborn` (`monomerCount=actinSeed`,
`segLength=seedLen`) + `AgingSystem.nucleateFreshAtp` (`nucFrac=(1,0,0)`) — additive, mirroring the split
`splitWire`+`splitInheritNuc` precedent (the `tagSeeds` rank→slot iteration, race-free); drag via `recomputeDrag`.
Audit found EXACTLY those two stale fields (+ geometry `segLength`), not a broad newborn-init. Validated by the
**FIRST turnover + nucleation coexistence** (the ring precondition, `DeadSlotReuseHarness`/`run_deadslot.sh`): 2250
dead-slot reuses all correct, **conservation EXACT** through the recycle, **CPU≡GPU bit-identical**, a fix-OFF
control reproducing the exact `actinSeed·#reuse` deficit, turnover-only/nucleation-only regressions unchanged.
`BoA-v1ref` byte-clean; production untouched; default-off. Report: `INC7_DEADSLOT_FIX_FINDINGS.md`.
```
./run_deadslot.sh        # GPU + CPU (newborn correctness, conservation, fix-off control, regression, CPU≡GPU)
./run_deadslot.sh -cpu   # CPU runner only (triage)
```

**Increment 7 → Ring — EXPERIMENT: a 3×3 net of nucleating, treadmilling nodes — DONE (2026-06-22). The net
COALESCES.** The first multi-node SCPR coalescence test (`Ring3x3Harness`/`run_ring3x3.sh`): 9 free, box-confined
protein nodes in a 3×3 grid, each sprouting 4–6 **randomly-oriented treadmilling** formin filaments + the validated
myosin shell, capture one another's filaments and **contract into a single connected 9-node cluster (RMS extent
−41%)** via the **scheme-0 soft tether — SUFFICIENT (no scheme-1 signal)**. **Pure COMPOSITION** (generalises Test
B's two-node SCPR loop to 9 nodes + adds the treadmilling depoly cadence + the dead-slot `initNewborn`) — NO new
force law/gather/shared-kernel edit; every system reused byte-unchanged; `BoA-v1ref` byte-clean; production
untouched; exploratory, no default change. **Reach-vs-spacing calibration (sweep):** clean monotonic transition —
COALESCING for spacing ≤0.30 µm (−23…27%, all 9 connected), PARTIAL at 0.35–0.40; the capture cone needs **~1.35×
OVERSHOOT** (the foreign filament must reach the captor's FAR hemisphere, `rodDotFil≥0` — the Test B′ finding).
**Sanity AT SCALE:** conservation **EXACT** (integer pool ledger, every step/spacing), **0 phantoms** (dead-slot
`initNewborn` holds), brisk turnover (1364 taken/1377 returned monomers ≈ steady treadmilling near C_c), **no
crash/race** on CPU (616 steps/s) AND the device-resident **GPU** ~50-kernel TaskGraph (no race, conservation EXACT,
phantoms 0, bound-head aggregate 20≈CPU 19 — §8 standard). **Timescale compression (stated):** `KIN=100` scales k_on
AND k_off1 equally ⇒ C_c (hence reach) PRESERVED; warm-started at the pool-bounded reach (Test B′ overrun fixed by
turnover + the shared pool). **Reveals for the ring:** mechanisms compose at scale; scheme 0 is enough; the dominant
loss is **3D-random orientation in a planar net** ⇒ an in-plane/toward-neighbour nucleation bias (seam-#3 SPECIFIED)
is the cheapest next lever; FREE nodes clump into a BALL — turning it into a RING needs the membrane/cortex
**geometric constraint** (later increment; the net does NOT fly apart ⇒ constraint is geometry, not stability).
Report: `INC7_RING_3x3_FINDINGS.md`; JOURNAL 2026-06-22.
```
./run_ring3x3.sh                          # CPU experiment (default 3×3, spacing 0.25, 6 formins, 30000 steps) — COALESCES
./run_ring3x3.sh -gpu -steps 30000        # + GPU device-resident scale/no-crash/throughput check
./run_ring3x3.sh -spacing 0.40 -formins 6 # the SPARSE regime (partial coalescence — the reach-vs-spacing edge)
./run_ring3x3.sh -3js threejs_ring3x3     # viewer frames (the net sprouting, reaching, capturing, clumping)
```

**Increment 7 → Ring — 3×3 net + FULL turnover (treadmilling · aging · severing) + SPHERE nodes — DONE
(2026-06-22).** Two changes to `Ring3x3Harness`: (1) protein nodes render as **SPHERES** (the viewer's existing
grey-sphere `data.nodes` channel — **NO viewer edit**, BoA rendering untouched); (2) the net runs the **FULL
simplified turnover — growth + pointed depoly + AGING (cascade→ADP depoly) + SEVERING (cofilin en-masse dissolve),
formin-PINNED (release OFF)**. **Pure composition** (the SeveringHarness combined cadence generalised to 9 nodes;
`AgingSystem`/`SeveringSystem`/`depolyProxy` reused byte-unchanged). **THE FINDING (clean 3-way, same
KIN=100/spacing/formins):** growth+depoly **coalesces 49%**; +AGING **39%** (ADP depoly shortens filaments — aging
benign); +SEVERING **WIND-DOWN 2%** — severing is the qualitative lever. **Root cause:** formin-pinned single-tip
growth (1 mono/cadence) cannot sustain a filament against cofilin **whole-segment** severing (7215 mono / ~245
events vs depoly 707) once KIN-fast aging makes the body ADP ⇒ the population **runs away to ~0**; nucleation doesn't
rescue. **Bistable, no coexistence window** (cofilinRatio∈[0.5,0.95]×KIN∈[15,100]). **SURFACED + FIXED (additive):**
the cofilin **"poisoned slot"** — a dissolved slot keeps `cofFrac>ratio`, so a reused slot instantly re-dissolves;
closed with `SeveringSystem.nucleateFreshCofilin` (born slots→cofFrac=0; the dead-slot-fix pattern, the cofilin
analog), called after split AND nucleation allocs (no existing kernel touched). **Robust at scale (every regime):**
conservation **EXACT**, **0 phantoms** (initNewborn+nucleateFreshAtp+nucleateFreshCofilin), no crash/race on CPU
(624 steps/s) AND the device-resident GPU graph (~58 kernels), **CPU≡GPU aggregate-agree** (active 216=216, bound
16=16). **Watchable** (`-3js`): grow (barbed "+") / age (segments redden, notADPRatio 1.0→0.26 = the cascade) /
sever (aged segments vanish + fragment). **Flags (report, not added):** free-fragment **end2 depoly** still the
Stage-1 deferral (fragments turn over + conserve but shrink only from the pointed end); the wind-down is the
faithful machinery's honest behaviour (not a bug). **For the ring:** sustained severing + a persistent contractile
structure need a growth source that replenishes whole severed segments (multi-site/branched nucleation, faster
barbed growth, or end2-aware recycling) — the formin-pinned single-tip mode can't. Report:
`INC7_RING_3x3_TURNOVER_FINDINGS.md`; JOURNAL 2026-06-22.
```
./run_ring3x3.sh                 # full turnover (winds down — the finding)  | -nosever → coalesces 39% | -noaging -nosever → 49%
./run_ring3x3.sh -gpu -steps 30000           # + GPU device scale/no-crash/CPU≡GPU-aggregate
./run_ring3x3.sh -3js threejs_ring3x3_turnover -steps 15000   # sphere nodes, ADP gradient, severing
```

**FULL-SYSTEM DEMONSTRATION — the maximal-composition contractile network — DONE (2026-06-22); NEXT: GET IT ON THE
GPU.** `FullSystemDemoHarness` (+ `run_fulldemo.sh`) composes EVERY validated subsystem in one shallow in-vitro
chamber at faithful KIN=1 rates: protein NODES nucleating biochemically-active treadmilling formin filaments
(growth+depoly+aging+severing) + free myosin MINIFILAMENTS binding/contracting + O(N) CROSSLINKER bundling +
CONTAINMENT, all on one shared `FilamentStore.forceSum`. PURE COMPOSITION (no new force law/gather/shared-kernel
edit). Aberration hunt (KIN=1, 20k steps) CLEAN: conservation EXACT, 0 phantoms, no crash, gentle contraction;
behaviors surfaced+explained (t=0 warm-start transient ×aeta drag, rare wall-contact containment kicks,
sparse-network binding at faithful 0.025µm reach, the `filID`-must-be-chain-id crosslinker fix). Report:
`FULL_SYSTEM_DEMO_FINDINGS.md`; JOURNAL 2026-06-22. **Renders:** `threejs_fulldemo` (KIN=1), `threejs_fulldemo_lifecycle`
(KIN=30 viewing-speed), `threejs_fulldemo_dense` (`-dense`).
**⇒ THE GPU BLOCKER (the next task):** the FULL merged device graph (~100 tasks: turnover+nucleation+node-shell+
free-minifil+grid-binding) is **already written** in `FullSystemDemoHarness.buildPlan()`, but **TornadoVM throws
`TornadoInternalError: unimplemented: Tornado Graph resize not implemented yet`** at run — the maximal composition
exceeds TornadoVM's single-`TaskGraph` node capacity (independent of `-Dtornado.tvm.maxbytecodesize`). **FIX
DIRECTION: SPLIT into multiple chained `TaskGraph`s** (device-resident across them — keep SoA on-device, only the
integer pool ledger crosses host each step as `stepHostBookkeeping` already does). The constituent device graphs are
EACH validated device-resident at scale (turnover+nucleation+node = Ring3x3 GPU ~58 kernels; minifil+xlink-force =
DenseContractile GPU; O(N) xlink formation = XlinkFormation GATE B, bit-identical CPU↔GPU) — so the pieces lower
fine; only the monolithic single-graph assembly fails. **WHY IT MATTERS:** CPU is ~22 steps/s on the dense scene =
**~72 min wall-clock per 1 s of simulated time**; the GPU device-resident path is the whole point (DenseContractile
GPU beat BoA GPU 5–7×). The `-gpu` flag attempts the full graph + reports this blocker cleanly.
```
./run_fulldemo.sh -smoke                                       # cheap assembly/sanity
./run_fulldemo.sh -steps 20000                                 # CPU demo + aberration hunt (KIN=1)
./run_fulldemo.sh -dense -steps 200000 -3js threejs_fulldemo_dense   # dense render (~2 s sim time)
./run_fulldemo.sh -gpu -steps 20000                            # attempts the full device graph ⇒ hits the Graph-resize blocker (the next task: split it)
```
**Unified viewer (2026-06-22):** `sim_viewer_boa.html` is now ONE canonical file — `~/Code/BoA/sim_viewer_boa.html`
(the superset: membrane/DTS + an additive crosslink channel, `MAX_MYOSINS`=24000) — and SoftBox's is a **symlink**
to it. Run ONE `sim_server.py` from `~/Code` (`python3 SoftBox/sim_server.py 8000`); it recursively serves BOTH
repos' runs. Open `http://localhost:8000/SoftBox/sim_viewer_boa.html`. (Both viewer commits pushed: SoftBox
`xlink-formation-on`, BoA `membrane-render-and-dense-v5`.)
