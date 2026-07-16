# Two-body motor canonicalization — final report

**Date:** 2026-07-15 · **Branch:** `dt-convergence-study` · **Runner:** CPU only (the two-body arc;
`run_lasertrap.sh` refuses `-gpu`). **Type:** code-architecture + validation (NOT a new calibration study).

Promotes the two frozen L40 motor models from default-off experimental prototypes to **stable, documented,
directly-selectable** motor implementations, and retains `FIXED_ANCHOR` as the strongly-supported assay
fixture — via a **descriptor + registry layer** over the existing validated motor core (no physics moved).

## What changed

**New files (self-contained):**
- `softbox/MotorModel.java` — the registry: three canonical models as immutable descriptors (frozen
  parameters + provenance + solver/cost metadata + CPU/GPU paths + serialization id + viewer representation
  + known limitations). The single source of truth for the frozen numbers. Pure (no `Cmot` dependency):
  resolves ids/aliases, logs, serializes, checks restart identity, emits viewer metadata.
- `softbox/MotorObservable.java` — canonical stiffness/force observable names (unambiguous;
  `k_ext` deprecated into three distinct generalized-coordinate measurements).

**Additive glue in `softbox/TwoBodyConverterMotor.java`** (no validated physics touched):
- `buildBoundMotor(MotorModel, dt)` — the one centralized model→`Cmot` builder; dispatches to the existing
  validated builders (`buildBoundS2` / `buildBoundSup`+frozen `CAL_*` / `buildSup(...,false)`) and rejects
  incompatible combinations.
- `applyCalibratedFrozen`, `assertFrozenParamsConsistent`, `coreSignature`, `runMotorModel`,
  `runMotorRegression`, `runMotorCompare`.

**Dispatch in `softbox/LaserTrapHarness.java`:** `-motor <id>`, `-motor-regression`, `-motor-compare`, plus
a one-line resolved-model banner on the historical `-exp4f/-exp4g/-exp4h/-exp4i` aliases (behavior
otherwise byte-identical).

**Docs:** `docs/MOTOR_MODELS.md`, `docs/MOTOR_MODEL_EXPLICIT_S2_L40.md`,
`docs/MOTOR_MODEL_CALIBRATED_S2_L40.md`, `docs/MOTOR_MODEL_SELECTION.md`,
`docs/MOTOR_MODEL_VALIDATION_MATRIX.md`, and this report.

## The three canonical models

- **`fixed-anchor`** — `FIXED_ANCHOR`: rigid substrate anchor (pivot pinned, never integrated). The
  strongly-supported assay fixture (tweezers, regression baselines, historical comparisons). Source
  3A–3F/4H.
- **`explicit-s2-l40`** — `EXPLICIT_S2_L40`: explicit fixed-contour MD-informed S2 beam, free length 40 nm,
  EA = 4.2e-9 N, EI = 7.2e-28 N·m², clamped emergence, 5 nodes, `(3M+2)`-DOF implicit solve, CPU-only,
  ~58 µs/step. The **mechanistic reference**. Source 4G; molecular ref Adamovic, Mijailović & Karplus 2008;
  validation 4H.
- **`calibrated-s2-l40`** — `CALIBRATED_S2_L40`: cheap analytic movable-pivot no-slack anisotropic law fit
  to the reference beam (k_ax 105, k_tr 0.026, rMax 20.3, symmetric-stiff, no state switch), 1 node,
  ~1.34 µs/step, GPU-friendly. The **production surrogate**. Source 4I (fit to `EXPLICIT_S2_L40`).

## Regression outputs (`RUN_LOGS/twobody_canonicalization/`)

- `motor_model_regression.txt` / `_full.log` — Gates A–F all PASS (see the matrix doc).
- `cross_model_comparison.md` / `_full.log` — the cross-model comparison table.
- `motor_{fixed-anchor,explicit-s2-l40,calibrated-s2-l40}_characterization.csv` — per-model
  single-motor characterization (with provenance headers).

## Acceptance criteria — all met

1. selection explicit + logged; 2. old runs unchanged (defaults untouched, `-exp4*` byte-identical);
3. common core unchanged (Gate B `max|Δ| = 0`); 4. frozen 4G-L40 reproduces (Gate C bit-identical);
5. frozen calibrated-4F-L40 reproduces (Gate D bit-identical); 6. observable names unambiguous
(`MotorObservable`); 7. checkpoint/restart preserves identity (Gate F); 8. viewers distinguish explicit vs
reduced (`viewerMetaJson`); 9. performance disclosed (cost model + table); 10. exact commands documented
(below).

## Exact reproduction commands

```
./scripts/build.sh

# Production selection (stable identifiers):
./scripts/run_lasertrap.sh -motor fixed-anchor
./scripts/run_lasertrap.sh -motor explicit-s2-l40
./scripts/run_lasertrap.sh -motor calibrated-s2-l40

# Canonicalization regression + cross-model comparison:
./scripts/run_lasertrap.sh -motor-regression      # Gates A–F
./scripts/run_lasertrap.sh -motor-compare         # comparison table

# GPU rejection (never silently changes the model):
./scripts/run_lasertrap.sh -motor explicit-s2-l40 -gpu    # REFUSED (CPU-only)

# Historical exact-reproduction aliases (unchanged behavior, + resolved-model banner):
./scripts/run_lasertrap.sh -exp4g   # EXPLICIT_S2_L40 (Experiment 4G)
./scripts/run_lasertrap.sh -exp4i   # CALIBRATED_S2_L40 (Experiment 4I calibration)
./scripts/run_lasertrap.sh -exp4f   # CALIBRATED_S2_L40 base (Experiment 4F)
./scripts/run_lasertrap.sh -exp4h   # FIXED_ANCHOR (Experiment 4H tweezers)
```

## Migration notes

- **No existing simulation configuration was rewritten and no historical default was changed.** Runs with
  no `-motor` keep their historical behavior.
- New configurations should declare `-motor <id>`; new templates may default to `calibrated-s2-l40`.
- The stale line-7127 `CAL_*` seed is superseded for the selection path by the frozen `MotorModel`
  descriptor (applied via `applyCalibratedFrozen`); `run4i`'s live fit is unaffected (it refits `CAL_*`
  before it measures).
- Reproducibility modes retained: 4F original no-slack (`-exp4f`), 4G L20/L40/L60 (`-exp4g`), 4I
  calibrated L40 (`-exp4i`), 4H tweezers (`-exp4h`). The canonical builders reproduce the frozen L40
  configurations bit-identically (Gates C/D).
- **Viewer:** frame metadata should carry `MotorModel.viewerMetaJson()` (`fixtureKind`,
  `reducedSurrogate`, render convention). The unified `sim_viewer_boa.html` is a verbatim v1 symlink; the
  actual glyph rendering (explicit beam nodes vs. a reduced-surrogate tether glyph) is documented as the
  convention here and flagged for a follow-on viewer edit — the metadata contract is in place so the
  viewer can distinguish them without re-deriving the fixture kind.
- **Not canonized:** arbitrary 4F parameter combinations, the former short-slack δ = 1.5 nm condition, a
  simple length-scaled L60 surrogate, experimental buckling branches not validated for the selected
  fixture, mutable parameter sweeps. Canonical status applies to the exact frozen L40 configurations only.

---

Canonicalized EXPLICIT_S2_L40 as the mechanistic reference and CALIBRATED_S2_L40 as the production
surrogate; retained FIXED_ANCHOR as the strongly-supported assay fixture.
