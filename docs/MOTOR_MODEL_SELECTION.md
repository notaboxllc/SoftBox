# Motor model selection, logging, serialization, and restart

The stable interface for choosing a two-body motor model. Production runs use the **model identifiers**,
not the experimental `-exp4*` names.

## Selecting a model

```
./scripts/run_lasertrap.sh -motor fixed-anchor
./scripts/run_lasertrap.sh -motor explicit-s2-l40
./scripts/run_lasertrap.sh -motor calibrated-s2-l40
```

`-motor <id>` resolves through the one registry (`softbox/MotorModel.java`), logs the resolved model and
the complete fixture configuration, builds the motor via the centralized builder
(`TwoBodyConverterMotor.buildBoundMotor`), and runs a standard single-motor characterization.

- `-motor-compare` — the cross-model comparison report (all three models).
- `-motor-regression` — the canonicalization regression (registry reproduces the frozen paths; core
  unchanged).

## Default policy (do NOT silently change history)

- **Old scripts with no motor declaration retain their historical default.** Nothing about the existing
  `-exp3*/-exp4*` runs changes.
- **New simulation configurations must declare the motor model explicitly** (`-motor <id>`).
- **New project templates may default to `calibrated-s2-l40`** (the production surrogate).
- `EXPLICIT_S2_L40` is **never** the universal default (its cost).
- **Logs always print the resolved motor model and the complete fixture configuration** (see below).

## Historical aliases (exact reproduction)

The experiment flags remain available and dispatch to their **existing** builders, unchanged — they are
reproduction aliases, not rewrites. They now also print a one-line resolved-model banner:

| alias flag | resolves to | historical builder |
|---|---|---|
| `-exp4g` / `-twobody-explicit-s2` | `EXPLICIT_S2_L40` | `run4g` (Experiment 4G) |
| `-exp4f` / `-twobody-supported-s2-tail` | `CALIBRATED_S2_L40` (surrogate base) | `run4f` (Experiment 4F) |
| `-exp4i` / `-twobody-s2-surrogate-calibration` | `CALIBRATED_S2_L40` (calibration) | `run4i` (Experiment 4I) |
| `-exp4h` / `-twobody-tweezers-blinded` | `FIXED_ANCHOR` | `run4h` (Experiment 4H) |

Aliases resolve through the same registry (`MotorModel.fromId` / `MotorModel.scan`), so the logged model
identity is consistent whether you use `-motor` or the historical flag.

## The resolved-model log (always printed)

`MotorModel.logResolved` emits a block naming the model, provenance, cost/memory, CPU/GPU support, viewer
representation, the full fixture configuration, the known limitations, and the serialization token, e.g.:

```
# MOTOR MODEL RESOLVED: Calibrated S2 surrogate (L = 40 nm) — production surrogate  [id=calibrated-s2-l40, ...]
#   provenance: source=Experiment 4I; ... referenceModel=EXPLICIT_S2_L40; calibrationCoord=relaxed pivot ...
#   cost/memory: 1.34 us/motor-step, internalDOF=5, nodes/motor=1 — Analytic ... GPU-friendly ...
#   CPU=yes  GPU=yes (surrogate)
#   viewer: reduced surrogate: effective tether/pivot glyph labelled "reduced surrogate" ...
#   fixture: calibrated pivot surrogate (no-slack): k_ax(tension)=105.0 pN/nm, ... k_tr=0.026 ...
#   known limitations: ...
#   serialize: motorModel=calibrated-s2-l40;canonVersion=1;refFreeLenNm=40
```

## Rejecting incompatible combinations

- **GPU for a CPU-only model:** `-motor explicit-s2-l40 -gpu` is refused with a clear message; the model is
  **never** silently changed or silently downgraded to CPU. (`run_lasertrap.sh` also globally refuses
  `-gpu` for the whole two-body arc.)
- **Unknown id:** `MotorModel.fromId` throws listing the valid ids.

## Serialization and restart identity

Every model has a compact identity token and round-trips:

```
motorModel=fixed-anchor;canonVersion=1;refFreeLenNm=na
motorModel=explicit-s2-l40;canonVersion=1;refFreeLenNm=40
motorModel=calibrated-s2-l40;canonVersion=1;refFreeLenNm=40
```

- `MotorModel.serialize()` / `MotorModel.parseSerialized()` — write / read the token in configs,
  checkpoints, restart files, trajectory exports, and viewer metadata.
- `MotorModel.viewerMetaJson()` — the JSON fragment for viewer frame metadata (`fixtureKind`,
  `freeS2LenNm`, `reducedSurrogate`, render convention).
- `MotorModel.isRestartCompatible(requested)` — a checkpoint may be resumed only under the **same** model
  (explicit-beam node coordinates and a reduced pivot are not interchangeable); a mismatch is **rejected**
  unless an explicit conversion is requested. What to serialize per model:
  - `EXPLICIT_S2_L40`: the internal S2-node coordinates (5 nodes).
  - `CALIBRATED_S2_L40`: the pivot state + fixture parameters.
  - `FIXED_ANCHOR`: the (static) anchor.

The regression's Gate F exercises the round-trip and the mismatch rejection.
