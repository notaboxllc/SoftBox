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
(`$TORNADOVM_HOME`, `@tornado-argfile`, `--enable-preview`, `-g`). Concrete build/run commands to be
filled in once the first kernel exists (increment 1).

## Documentation conventions
Same as v1: `CLAUDE.md` = cross-session context (this file); `JOURNAL.md` = terse, newest-first,
what-was-done / what-was-learned / what's-open. Do not archive JOURNAL entries autonomously.

## Status
Increment 0 (scaffold) complete. Next: increment 1 (filament slice) — the planner designs the
component-array layout + system list before implementation begins.
