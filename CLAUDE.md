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
- **Do NOT build extensibility yet** (no plugin loader, no component registry). First reproduce the
  physics; generalization is a later phase.
- **One physics implementation; device-agnostic systems.** Each system is written once as a kernel
  method over the SoA arrays. The GPU TaskGraph is the production path; the *same* system methods
  run sequentially on the CPU (plain Java, no TaskGraph) as a debugging/validation runner —
  identical arithmetic, identical RNG. **Never hand-write a CPU or double-precision reimplementation
  of a system's physics** — that recreates v1's two-sources-of-truth drift. CPU execution = the same
  code on a different runner. Stay single-precision; fix float problems with better algorithms (cf.
  the `asin(|cross|)` bending angle), not a parallel double path. One-off double checks for a
  specific diagnosis are fine if thrown away.

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
3. Spatial grid + broad-phase — entity-agnostic (anticipating surfaces/membranes).
4. Binding detection + myosin motors — validate against the v1 gliding-assay fixture.
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

**Next: increment 3 — spatial grid + broad-phase** (entity-agnostic, anticipating surfaces/membranes).
