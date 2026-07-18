# Canonical two-body motor models

**Status:** canonical (2026-07-15). **Scope:** the two-body S2-tail motor family (the `-exp3*/-exp4*`
optical-trap arc), CPU-only. **Selection interface:** `softbox/MotorModel.java` (the registry) +
`-motor <id>` in `run_lasertrap.sh`. **Single source of truth for frozen parameters:** `MotorModel`.

## The seven things to know first

1. **Neither model is universal truth.** Each is a fixture-conditioned representation of the myosin S2
   tail; the "right" one depends on what your run is sensitive to.
2. **`EXPLICIT_S2_L40` is the mechanistic reference** — an explicit fixed-contour MD-informed S2 beam
   (Experiment 4G). Molecularly grounded, costly.
3. **`CALIBRATED_S2_L40` is the production surrogate** — a cheap analytic movable-pivot law fitted
   directly to `EXPLICIT_S2_L40` (Experiment 4I). ~43× cheaper, GPU-friendly.
4. **`FIXED_ANCHOR` is an assay-conditioned strongly-supported fixture** — the rigid-anchor limit
   (tweezers-like assays, regression baselines, historical comparisons).
5. **Free S2 length is a fixture / boundary parameter**, not a property of the motor core. The canonical
   S2 models are frozen at **L = 40 nm**.
6. **`CALIBRATED_S2_L40` differs from `EXPLICIT_S2_L40` in search reach and load-bearing-state fraction:**
   ~29 % smaller search RMS, ~38 % smaller capture footprint, and a higher fraction of bound motors
   classified load-bearing (always-taut vs ~67 % for the beam).
7. **Results sensitive to those differences should be bracketed or 4G-spot-checked** — recruitment /
   search-dominated studies especially. Load/force-dominated ensembles (gliding, contraction) are where
   the surrogate is essentially exact.

## The three models at a glance

| | `fixed-anchor` | `explicit-s2-l40` | `calibrated-s2-l40` |
|---|---|---|---|
| role | strongly-supported assay fixture | mechanistic reference | **production surrogate** |
| tail fixture | rigid anchor (pivot pinned) | explicit MD-informed S2 beam | analytic movable pivot |
| free S2 length | — | 40 nm | 40 nm (ref) |
| internal DOF / motor | 0 | 14 (=3M+2, M=4) | 5 (movable pivot) |
| nodes / motor | 1 | 5 (M+1) | 1 |
| cost / motor-step | ~1.0 µs | ~58 µs | ~1.34 µs |
| CPU / GPU | CPU / — | CPU only (**no GPU**) | CPU / GPU-friendly |
| source experiment | 3A–3F, 4H | 4G | 4I |
| default? | never; historical | never (cost) | new templates may default here |

## The shared motor core (what all three have in common)

All three models use the **exact same validated motor core** — head, converter, neck–lever, F8 spring,
stereospecific binding gate, Lymn–Taylor biochemical cycle + kinetic constants, force ordering, RNG
conventions, and actin interaction. **Only the tail-support fixture differs** (rigid anchor / explicit
beam / calibrated pivot), which is a branch inside the `Cmot` representation in
`softbox/TwoBodyConverterMotor.java`. The registry proves this: the regression's **common-core identity**
gate shows the `{kF8, kconv, kbind, lb, γφ, γψ, ψ_actin}` signature is bit-identical across all three
(`max|Δ| = 0`).

**Filament-segment binding ownership is CANONICAL half-open by default (2026-07-18)** — shared by all three
models. Each material point on the discretized actin is assigned to exactly one segment via
`footC=clamp(foot,−half,half)` + half-open `foot∈[−half,half)`, with `bindArc=footC+half∈[0,segLength]` and a
**machine-ε** in-segment tolerance (the former 50 nm segment-end exclusion is REMOVED — a discretization/ownership
correction, not an affinity/rate change). Toggle `TwoBodyConverterMotor.LEGACY_OWNERSHIP` (default `false`);
`-legacy` / `-Dsoftbox.legacyOwnership=true` restores the deprecated 50 nm behaviour byte-identically for regression.
This changes explicit-model recruitment numbers vs pre-2026-07-18 baselines (which were legacy-margin). See
`docs/matsoa/EXPLICIT_SEGMENT_MARGIN_{PROVENANCE,ROLLOUT}_FINDINGS.md`.

## Canonicalization scope

Canonical status applies to the **exact frozen L40 configurations only**, NOT the whole experimental code
path. NOT canonical: arbitrary 4F parameter combinations, the former short-slack δ = 1.5 nm condition,
a simple length-scaled L60 surrogate, experimental buckling branches not validated for the selected
fixture, and mutable parameter sweeps. The canonical configurations are **immutable after construction**.

## Deferred extraction (a full `MotorFixture` OOP hierarchy)

This canonicalization is deliberately a **descriptor + registry layer**, not a `CommonMotorCore` +
`MotorFixture` object hierarchy. The core is already shared and the three fixtures are already branches in
`Cmot`, so a literal interface extraction would re-wrap already-shared code at real cost to the frozen
byte-identity. A full extraction is **deferred until one of** these arises:

- a fourth substantially different fixture is added;
- tail implementations begin duplicating logic;
- GPU backends require polymorphic separation;
- checkpoint serialization becomes difficult;
- testing cannot isolate fixture mechanics cleanly;
- `Cmot` branching becomes unmaintainable.

If duplicated tail dispatch appears first, the intermediate step is lightweight helpers
(`applyTailForce` / `addTailTangent` / `stepTailInternalState` / `getTailDiagnostics`) over the existing
`Cmot`, not a new object hierarchy.

## See also

- `docs/MOTOR_MODEL_EXPLICIT_S2_L40.md` — the mechanistic reference in full.
- `docs/MOTOR_MODEL_CALIBRATED_S2_L40.md` — the production surrogate in full.
- `docs/MOTOR_MODEL_SELECTION.md` — how to select, log, serialize, and restart.
- `docs/MOTOR_MODEL_VALIDATION_MATRIX.md` — the regression matrix + acceptance criteria.
- `docs/TWOBODY_CANONICAL_MODELS.md` — the canonicalization report + exact commands.
- `docs/TWOBODY_MD_INFORMED_S2.md` (4G), `docs/TWOBODY_4G_TO_4F_CALIBRATION.md` (4I),
  `docs/TWOBODY_TWEEZERS_4H_PRODUCER.md` (4H) — the underlying experiments.
