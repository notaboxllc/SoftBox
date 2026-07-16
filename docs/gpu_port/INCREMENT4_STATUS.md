# GPU-motor increment 4 — status (analytic production promotion + first mat-SoA kernels)

Two tracks from merged `main`. Part 1 is complete + on `main`; the mat-SoA vertical slice delivered its
4 hardest kernels (on branch `motor-mat-gpu-soa`), with composition (Part 6) as the bounded next step.
No motor physics/chemistry/binding/params/model-ID changed. GPU gate closed (`DEVICE_VALIDATED=false`).

## Status block
- `EXPLICIT CPU DEFAULT: ANALYTIC` — **PROMOTED, on `main` (`caffc19`)**; FD retained as the permanent
  oracle (`-explicitsolver fd`). ~6.9× faster explicit gliding.
- `MAT-SOA VERTICAL SLICE:` **4 new-physics kernels (Stages 1/2/3/7) implemented + isolated-gated PASS**
  (branch `motor-mat-gpu-soa`); composition (Part 6) is next.
- `CALIBRATED GPU TRAJECTORY:` not yet — Part-6 composition pending.
- `DEVICE RESIDENCY:` **PROVEN** — motor SoA uploaded FIRST_EXECUTION, only `counts`+compact state per step,
  compact outputs read back; **no full per-step mat transfer**.
- `NEXT BLOCKER:` compose Part 6 — the `matPlaceHead` bridge kernel + `matZConfine`/`matReduce` + wire the
  existing validated device kernels (`cycleLymnTaylor`, `bondForces`, `csr*`/`segGather`, `chainForces`,
  `brownianForce`, `integrate`, `derive`) into one chained residency-correct graph, then the stepwise
  CPU-vs-GPU trajectory + first-divergence classification, then multi-density validation + throughput.

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
