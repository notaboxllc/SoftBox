# Motor model: `EXPLICIT_S2_L40` — the mechanistic reference

**id:** `explicit-s2-l40` · **status:** canonical mechanistic reference · **source:** Experiment 4G
(`docs/TWOBODY_MD_INFORMED_S2.md`) · **runner:** CPU only.

An explicit, fixed-contour, MD-informed S2 beam: a chain of `M = L/l0` extensible-elastica segments with
stiff axial stretch and finite bending rigidity, a clamped supported emergence point, and a distal node
that IS the converter pivot. This is the molecular reference the reduced surrogate is calibrated against.

## Frozen canonical parameters (L = 40 nm)

Single source of truth: `MotorModel.ExplicitS2Params.frozenL40()`. Cross-checked bit-for-bit against the
live code constants (`TwoBodyConverterMotor.EXP4G_*`) by `assertFrozenParamsConsistent()` (regression
Gate A).

| quantity | value | note |
|---|---:|---|
| free S2 length `L` | **40 nm** | fixture / boundary parameter |
| segment length `l0` | 10 nm | discretization; `M = L/l0 = 4` segments, `M+1 = 5` nodes |
| number of segments `M` | 4 | |
| axial stretch modulus `EA` | **4.20 × 10⁻⁹ N** | from `k_ax(L_ref=60) = 70 pN/nm` → `EA = 70 pN/nm · 60 nm` |
| bending rigidity `EI` | **7.20 × 10⁻²⁸ N·m²** | from `k_lat(L_ref=60) = 0.01 pN/nm` via `3EI/L³` |
| axial stiffness `k_ax = EA/L` | **105 pN/nm** | at L40 (= `ks/M`) |
| persistence length `Lp = EI/kT` | **175 nm** | |
| per-segment `ks = EA/l0` | 420 pN/nm | |
| per-segment `kb = EI/l0` | 7.20 × 10⁻²⁰ N·m | |
| node drag radius | 5 nm | Stokes drag per beam node (`6πη r`) |
| Brownian forcing | per-node overdamped Langevin FDT (kT) | clamped emergence node fixed; distal node = pivot |
| boundary conditions | clamped supported emergence (fixed point + tangent) at node 0; distal node = pivot | fixed contour (stiff stretch) |
| material-state switch | **none** | no binding-state, no nucleotide-state stiffness switch |
| direct S2 force on actin | **zero** | force reaches actin only through the F8 cross-bridge |
| solver | (3M+2)-DOF implicit beam solve with a numeric beam tangent (14 DOF at M=4) | |
| timestep | **dt ≤ 2.5 × 10⁻⁶ s** | bending-explicit stability needs the fine dt |

Molecular reference: Adamovic, Mijailović & Karplus 2008 (the MD-derived EA/EI). Validation experiment:
4H (blinded tweezers).

## Cost / memory

~58 µs per motor-step at L40 (the `(3M+2)`-DOF implicit solve with a numeric beam tangent), ≈43× the
calibrated surrogate. Memory: `M+1 = 5` beam nodes per motor. **Cost grows with M ∝ free length** (L20
≈ 12 µs, L60 ≈ 168 µs).

## CPU / GPU

**CPU only — there is no GPU implementation of the explicit beam solve.** A `-gpu` request for this model
must **fail clearly** (`runMotorModel` refuses with a message and never silently falls back or silently
swaps to the surrogate). Large explicit runs are permitted on CPU when scientifically justified — the
cost/DOF/memory are disclosed up front in the resolved-model log; the surrogate is never substituted
automatically.

## Serialization / viewer

- serialize token: `motorModel=explicit-s2-l40;canonVersion=1;refFreeLenNm=40`
- checkpoints must serialize the **internal S2-node coordinates** (5 nodes) — the reduced pivot cannot
  represent them, so a restart under a different model is rejected unless an explicit conversion is asked.
- viewer: render the **actual beam nodes and segments** (clamped emergence → distal pivot);
  `fixtureKind = "explicit-beam"`.

## Known limitations

- CPU-only; no GPU path.
- ~43× costlier per motor-step than `CALIBRATED_S2_L40` at L40; scales with M.
- Frozen at L = 40 nm. **Do not length-scale to L60 by a simple axial `1/L` law** — the longer beam's
  bending compliance softens the effective axial reaction ~2× below `ks/M` (class C; see 4I Phase D).
  Retain a distinct L60 fixture for exposed long-tail assays.

## Reproduction

```
./scripts/run_lasertrap.sh -motor explicit-s2-l40         # production selection (characterization)
./scripts/run_lasertrap.sh -exp4g                         # historical Experiment 4G (full, unchanged)
./scripts/run_lasertrap.sh -exp4g -smoke                  # quick (L40 only)
```
