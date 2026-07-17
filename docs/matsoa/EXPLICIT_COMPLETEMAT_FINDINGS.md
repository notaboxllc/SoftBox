# Explicit coupled kernel → complete-mat integration — §1–§6 (prerequisites cleared; §7+ next)

This increment's goal is one pre-bound explicit motor advancing through the complete persistent mat for many
timesteps (§8). The **sanctioned prerequisites are DONE + validated** ("this stage must pass before composing
the full graph"): the explicit device head-placement + the bondForces coupling. The full-graph composition
(§7 one-step, §8 multi-step, §9 stroke/recoil, §10/§11 residency+timing) is the immediate next step — now
fully unblocked (every stage validated in isolation). No physics/salt/chemistry/solver change; FD stays the
oracle; `MotorGpuParams.DEVICE_VALIDATED` false; no silent fallback (bailout=false, enable.fma=false).

New: `TwoBodyBeamAnalyticGpu.matBeamGeom` + `matPlaceHeadExplicit`; harness `ExplicitCompleteMatHarness`.
Report: `RUN_LOGS/explicit_completemat/COMPLETEMAT_GATE.md`.

## Final status block
- **EXPLICIT MAT STATE:** DONE — the flat explicit SoA (nodes/frame/q/params/sys/outGeom, + shared
  bondData/boundSeg) validated by the single-head + coupled-kernel harnesses; reused here unchanged (no
  double[][], no per-step alloc, no second schema). ~2.2 KB/motor.
- **EXPLICIT HEAD PLACEMENT:** DONE + VALIDATED — `matBeamGeom` (device `geom2D`) + `matPlaceHeadExplicit`
  (device `placeHead2D`, head at xH, uVec=normalize(xF8−xH), yVec=perp3) LOWER to PTX; CPU-mirror reproduces
  production geom2D/placeHead2D **exactly (0.0)**; GPU vs CPU-mirror 1.9e-7 (float last-bit on the FLOAT body
  pose). The explicit `outGeom` layout ([3N]=xH, [6N]=xF8) is the OPPOSITE of the calibrated `matPlaceHead`
  ([3N]=xF8, [6N]=xH), so a distinct explicit kernel is required (documented).
- **EXPLICIT BONDFORCES COUPLING:** DONE + VALIDATED (§6) — the device head pose fed into the SHARED,
  BYTE-UNCHANGED `bondForces` yields bit-identical `bondData` vs production (**max|Δ| 2.6e-18**): the F8h that
  `matS2SolveStep` consumes matches production, no dropped/duplicated force.
- **EXPLICIT COMPLETE-MAT ONE STEP:** NOT YET — §7; the assembly = the calibrated `buildTrajGraph` with three
  stages swapped (matPlaceHead→matPlaceHeadExplicit, +matBeamGeom, matStep7→matS2SolveStep) over the explicit
  SoA + MotorStore body + FilamentStore; every constituent stage is now validated in isolation.
- **EXPLICIT COMPLETE-MAT TRAJECTORY:** NOT YET — §8.
- **DEVICE RESIDENCY:** the explicit stages are residency-ready (flat SoA mutated in place; head pose written
  to the body float arrays the shared bondForces reads; no host reconstruction).
- **NEXT STEP:** §7 — compose the full explicit graph
  `matCull → matBeamGeom → matPlaceHeadExplicit → bondForces → zeroAcc → csrChunk*(parallel) → segGather →
  chain → zconf → brownian → integrate → orthoY → derive → matS2SolveStep → matReduce`, one-step CPU-vs-GPU
  vs production `stepGlideS2` (pre-bound, binding disabled), then §8 the several-hundred-step quiet trajectory,
  §9 stroke/detach/recoil, §10/§11 residency + N=1 functional timing.

## §-by-§
| § | item | result |
|---|---|---|
| §1 | complete-mat stage contract (order, arrays, model-agnostic vs explicit-specific, the placeHead layout diff) | DONE (below + `EXPLICIT_MATS2SOLVE_CONTRACT.md`) |
| §2 | explicit mat state (reused flat SoA, ~2.2 KB/motor; N=1/600/2100/4500/9000 = 2.2 KB/1.28/4.47/9.58/19.2 MiB for the beam SoA) | DONE |
| §3 | init (pre-bound explicit motor from buildS2Mat, boundSeg set, bindArc=½segLen) | DONE (in the gate setup) |
| §4 | build-time explicit dispatch | the explicit stages are distinct tasks (never calibrated); graph-level dispatch lands with §7 |
| §5 | explicit head-placement CPU/GPU gate (relaxed/high-axial/bend/taut/post-stroke) | **PASS** — geom+pose mir-vs-prod 0.0; GPUvsMirror 1.9e-7 |
| §6 | bondForces coupling gate (device head pose → bondForces vs production) | **PASS** — bondData Δ 2.6e-18 |
| §7–§11 | full-graph one-step + multi-step + stroke + residency + timing | NEXT (unblocked) |

## §1 complete-mat stage contract (confirmed from `stepGlideS2`, L6803)
Order: cull (`unionActive`) → [bind gate — SKIPPED for pre-bound] → chemistry (`cycleLymnTaylor`) →
`thetaS=thetaS4a(nuc)` → **geom2D + placeHead2D** → `bondForces` → CSR (`csrHistogram/Scan/Scatter/segGather`)
→ `chainForces` → z-confine → `brownianForce`+`integrate`+`orthogonalizeY`+`derive` (filament) →
**per-motor `s2SolveM`**. Model-agnostic stages (identical to calibrated): cull, chemistry, bondForces, CSR,
filament Langevin, reductions. Explicit-specific: the head placement (beam-derived, opposite outGeom layout)
and Step-10 (`matS2SolveStep`). Force returns to shared outputs via `bondData` (→ `segGather` → filament
forceSum) and `forceDotFil`/`forceMag` (→ chemistry catch-slip). Pre-bound ⇒ `boundSeg` fixed, bind gate
skipped, `noBind` set for the rest.

## Commands
```
./scripts/build.sh
java @tornado-argfile --enable-preview -Xmx8G -Dtornado.recover.bailout=false -Dtornado.enable.fma=false \
     -Dtornado.tvm.maxbytecodesize=65536 -cp "$TDIR/tornado-api-4.0.1-dev.jar:." softbox.ExplicitCompleteMatHarness
```
