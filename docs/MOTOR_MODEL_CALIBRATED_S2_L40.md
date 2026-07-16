# Motor model: `CALIBRATED_S2_L40` — the production surrogate

**id:** `calibrated-s2-l40` · **status:** canonical production surrogate · **source:** Experiment 4I
(`docs/TWOBODY_4G_TO_4F_CALIBRATION.md`) · **reference model:** `EXPLICIT_S2_L40` · **runner:** CPU now,
GPU-friendly.

A cheap analytic movable-pivot law (the validated 4F `supForce`) whose parameters are fitted **directly to
`EXPLICIT_S2_L40`**. One pivot `P` with anisotropic restoring forces (axial tensile/compressive, soft
transverse, finite-extension, floor); no internal S2 nodes; analytic force + analytic diagonal tangent.
This is the default MD-linked reduced motor for large production runs.

## Frozen canonical parameters (calibrated at L = 40 nm)

Single source of truth: `MotorModel.CalibratedS2Params.frozenL40()`. These are the **documented 4I fit
outputs** (Phase B `fitted_params`), NOT the stale line-7127 code seed — the canonical selection path
applies these via `TwoBodyConverterMotor.applyCalibratedFrozen()`.

| parameter | symbol | value | note |
|---|---|---:|---|
| axial tensile stiffness | `k_ax` (tension) | **105 pN/nm** | `= ks/M = EA/L` exactly (matches the reference beam) |
| axial compressive stiffness | `k_ax` (compression) | **105 pN/nm** | symmetric-stiff |
| compression branch state | — | **symmetric-stiff** | Euler buckling disabled in-range; `F_crit ≈ 4.44 pN` is an L60 feature |
| transverse stiffness | `k_tr` | **0.026 pN/nm** | free-axial bending reaction slope ≈ `3EI/L³` |
| finite-extension stiffness | `k_feTr` | **20 pN/nm** | runaway limiter; inactive over visited states |
| finite-extension onset | `rMax` | **20.3 nm** | ≈ 2× search RMS (frozen to the documented value) |
| axial smoothing | `s_ax` | 0.5 nm | C∞ softplus width |
| transverse smoothing | `s_tr` | 1.5 nm | narrow so the FE tail does not leak inside visited states |
| buckle smoothing | `s_buck` | 0.8 nm | (branch disabled at L40) |
| substrate floor | `k_floor` | 20 pN/nm | one-sided, no penetration |
| reference free length | `L_ref` | 40 nm | |
| slack | δ | **0** | no-slack anisotropic law (4G proved a stiff-stretch beam holds no rest slack) |

Calibration source: Experiment 4I (2026-07-15), fit to the `EXPLICIT_S2_L40` **relaxed-pivot reaction**
(the `s2RelaxHold` generalized coordinate: pivot displacement `d = P − P0` and pivot reaction force). The
effective potential is separable and conservative (`Ueff = U_ax + U_tr`, symmetric tangent by
construction; passive closed-loop work ≈ 0). Molecular reference (via the beam): Adamovic, Mijailović &
Karplus 2008.

## Known differences from `EXPLICIT_S2_L40` (the honest caveats)

**Close agreement** (Outcome-A-clean) in: axial force and axial tangent (`k_ax = ks/M = EA/L`, to ~1e-10),
unloaded stroke (≈5 %), pivot recoil (identical, 0.007 nm), force-clamp tracking, continuity, reduced-mat
recruitment rate (exact), and average binding (+13 %, within ±15 %).

**Documented drift** (the unbound-search ensemble):

- **search RMS ≈ 29 % smaller** (7.6 nm vs 10.8 nm) — a tighter search envelope.
- **capture footprint ≈ 38 % smaller** (357 nm² vs 576 nm²).
- **over-counts load-bearing:** the symmetric-stiff axial makes every bound motor taut (100 %), vs ~67 %
  taut for the explicit beam (which lets ~33 % sit in the bending regime under transverse load).

The `k_tr` calibrated to the mechanical bending reaction gives a tighter envelope than the beam's actual
wandering — a genuine ~1.5× ambiguity between the mechanical bending reaction and the search observable
(softening `k_tr` toward ~0.017 pN/nm would recover the search RMS at a few % of force accuracy).
`k_ext` 0.64 vs 0.99 is the **fixture-observable offset** (a mobile-pivot fixture reports a different
whole-crossbridge stiffness), NOT a series-compliance loss — both are stiff/load-bearing.

**Not a validated surrogate for L60 by simple length scaling** (axial `1/L` overpredicts ~2×; class C).
Retain a distinct L60 fixture for exposed long-tail assays.

## Role and recommendation

**ADOPT for production LOAD/force-dominated ensembles** — gliding, contraction, network, ring, force-clamp
studies — with documented uncertainty and periodic 4G spot-checks. Use it wherever the force output
governs the result. For **recruitment / search-dominated** studies, bracket against `EXPLICIT_S2_L40` (or
soften `k_tr`). New project templates may default to `calibrated-s2-l40`.

## Cost / memory / GPU

~1.34 µs per motor-step (analytic pivot force + analytic diagonal tangent + one 5×5 implicit pivot step),
≈43× cheaper than `EXPLICIT_S2_L40` at L40 and identical in cost to the original 4F. Memory: one pivot +
parameters per motor (no beam nodes). **GPU-friendly** (no per-motor inner beam solve); the two-body arc
harness is presently CPU-only.

## Serialization / viewer

- serialize token: `motorModel=calibrated-s2-l40;canonVersion=1;refFreeLenNm=40`
- checkpoints serialize the **pivot state + fixture parameters** (no internal nodes).
- viewer: an **effective tether/pivot glyph labelled "reduced surrogate"** — **never** an explicit
  segmented beam; `fixtureKind = "reduced-pivot"`, `reducedSurrogate = true`.

## Reproduction

```
./scripts/run_lasertrap.sh -motor calibrated-s2-l40       # production selection (characterization)
./scripts/run_lasertrap.sh -exp4i                         # historical Experiment 4I calibration (full, unchanged)
./scripts/run_lasertrap.sh -exp4f                         # historical 4F supported-tail base (unchanged)
```
