# GPU-motor increment 4 — status (analytic production promotion + first mat-SoA kernels)

Two tracks from merged `main`. Part 1 is complete + on `main`; the mat-SoA vertical slice delivered its
4 hardest kernels (on branch `motor-mat-gpu-soa`), with composition (Part 6) as the bounded next step.
No motor physics/chemistry/binding/params/model-ID changed. GPU gate closed (`DEVICE_VALIDATED=false`).

## Status block
- `EXPLICIT CPU DEFAULT: ANALYTIC` — **PROMOTED, on `main` (`caffc19`)**; FD retained as the permanent
  oracle (`-explicitsolver fd`). ~6.9× faster explicit gliding.
- `MAT-SOA VERTICAL SLICE:` **8 kernels implemented + gated** (Stages 1/2/3/7 + `matCock` + 3 bridges),
  composed into a **single 20-task device graph** (branch `motor-mat-gpu-soa`).
- `CALIBRATED GPU TRAJECTORY:` **DONE + CLEAN** — device-resident single-graph per-step loop; CPU-runner vs
  GPU **t=0 IDENTICAL** (no bridge/salt/order/binding bug); the only divergence is a single boundary motor
  flipping its bind gate after float-scale pose drift (1 of 600 @ t=16; 1 of 2100 @ t=1) = **float-FMA
  chaotic decorrelation, not semantic** (hard-stop condition NOT hit).
- `DEVICE RESIDENCY:` **PROVEN through the full 20-task loop** — GPU mem flat (205→205 MiB), SoA uploaded
  FIRST_EXECUTION, only counters up + a small reduction down per step; **no full per-step mat transfer**.
- `THROUGHPUT (Part 8):` double is **FP64-limited** — 0.54× (device slower) at N=600, **1.15× (device
  faster) at N=2100** as parallelism starts to win. The double `matStep7` 5-DOF solve is FP64-bound ⇒ a
  future **float `matStep7`** is the throughput lever (the quantified motivating data).
- `NEXT BLOCKER:` **Part 7 ensemble validation** — the trajectory is chaotic (correctly float-decorrelates),
  so emergent observables (velocity/continuity/avgBound/handoff/ATP-per-µm/wander/net force) need an
  **ensemble-mean-within-SEM** CPU-vs-GPU check over multiple seeds (CPU-double arbiter) + half-dt + cull
  controls. Then the float `matStep7` for a real GPU throughput win at scale.

## UPDATE — the device-resident calibrated GPU trajectory is validated (Parts 6 + 8 done)
The first device-resident two-body gliding mat trajectory runs as a single TaskGraph and is validated clean
(t=0 identical → semantically faithful; only expected float-FMA chaotic decorrelation). A missing stage
`matCock` (rest-angle switch `thetaS=thetaS4a(nuc)`) was found + added. Deliverable #9 (first complete
calibrated GPU trajectory, no full round-trip) ACHIEVED. Part 7 (ensemble) is the remaining validation
layer before a calibrated-GPU promotion gate; `DEVICE_VALIDATED` stays false. Branch `motor-mat-gpu-soa`
`0a4e6d3`; report `docs/matsoa/SLICE_STATUS.md`, `RUN_LOGS/matsoa/TRAJECTORY.md`.

## Original status (pre-trajectory, for history)

## Part 1 — analytic explicit solver PROMOTED (on `main`)
`ExplicitSolver{FD,ANALYTIC}` selector; ANALYTIC swaps **only the beam tangent** in `s2Solve`/`s2SolveM`
(exact energy Hessian via the single `ExplicitBeamAnalytic` impl — no duplication); residual/drag/F8/
converter/bind/Brownian/`solveLin`/endpoint extraction byte-identical between paths. FD kept + regression-
guarded (`ExplicitFdRegressionTest`: FD `s2Solve ≡ FD_REPLICA` max|Δ|=0). Production gliding FD ≡ ANALYTIC
at 200/700/1500 µm⁻² + half-dt (identical observables, bending-dominated population preserved, 0 new
failures, no basin mismatch). Throughput: isolated beam ×9.2, full step ×8.0, gliding ×6.7–7.1 (389 vs
2170 s/sim-s by default). Detail: `docs/explprod/EXPLICIT_ANALYTIC_PRODUCTION_FINDINGS.md`.

## Mat-SoA slice — 4 new-physics kernels gated (branch `motor-mat-gpu-soa`)
Flat planar device SoA (`c*N+m`, no `double[][]`, no per-thread alloc, no per-motor reconstruction).
Isolated CPU-vs-GPU gates (`-Dtornado.recover.bailout=false`, no silent fallback):
- **Stage 1 `matCull`** — active-set IDENTITY vs `unionActive` (0 mismatches).
- **Stage 2 `matGeomGate`** — nearest-seg + 8-gate accept IDENTITY (segMism=0, acceptMism=0).
- **Stage 3 `matBind`** — deterministic bind-event IDENTITY (no RNG; boundSegMism=0).
- **Stage 7 `matStep7`** — DOUBLE port of `supSolveM` with MAT salts (`0x5F1..0x5F5+m·7919`): CPU-runner ≡
  host `supSolveM` **6.9e-18** (bit-faithful), GPU vs CPU **1.68e-7** (pure FMA op-order through the
  ill-conditioned angular 5-DOF solve), F8h Δ~1e-20 (no semantic error) — the anticipated float-FMA
  last-bit, correctly classified. (A float `matStep7` reusing `calibratedStep` is the throughput follow-on
  once the double slice is composed + correct; Part 8 will quantify the FP64 penalty.)
Design: `docs/matsoa/{HOST_SEMANTICS_FROZEN,SOA_SCHEMA,EXPLICIT_EXTENSION_NOTE,SLICE_STATUS}.md`;
code `softbox/MatSoaSlice.java`; commits `9ecc0ab`/`b686ae0`/`4e1a169`/`d001dab`/`6c1aae4`/`2698240`.

## What's NOT done (honest)
Deliverable #4 ("one complete calibrated GPU trajectory") is NOT yet achieved — the 4 hardest new kernels
are ready + gated and residency is proven, but the composed device trajectory (Part 6) + end-to-end
validation (7) + throughput (8) remain. The remaining stages are the small `matPlaceHead`/`matZConfine`/
`matReduce` + wiring existing validated kernels — a bounded next increment.
