# Explicit coupled mat Stage-10 (`matS2SolveStep`) — §1–§6 findings (the gating milestone)

**The central uncertainty is RESOLVED: the production gliding-coupled explicit Step-7 LOWERS to PTX and
reproduces production `s2SolveM` to floating-point.** This is the sanctioned first milestone ("do not proceed
to full mat wiring until the isolated coupled kernel lowers"). No beam energy / topology / node count /
F8-converter law / motor params / chemistry / binding / convergence / salt change; the canonical
`ExplicitBeamAnalytic` derivative math is reused (no second derivative implementation); FD stays the permanent
oracle; `MotorGpuParams.DEVICE_VALIDATED` false; no silent fallback
(`-Dtornado.recover.bailout=false -Dtornado.enable.fma=false`).

New: `TwoBodyBeamAnalyticGpu.matS2SolveStep` + `brownTorqueD`; harness `ExplicitMatSolveHarness`.
Report: `RUN_LOGS/explicit_mats2/MATS2SOLVE_GATE.md`. Contract: `docs/matsoa/EXPLICIT_MATS2SOLVE_CONTRACT.md`.

## Final status block
- **EXPLICIT COUPLED MAT KERNEL:** **LOWERS + VALIDATED.** `matS2SolveStep` (= the validated
  `beamRelaxAnalytic` assembly for one implicit Newton step + the mat Brownian forcing at salts
  `0x4811/0x4841/0x4842+m·7919` via the already-lowering `brownTorqueD` + the `forceDotFil`/`forceMag`
  reaction writeback) lowers to PTX and executes on the RTX 5070; one-step port-vs-`s2SolveM` = **1.1e-9 µm**
  (FP op-order, Brownian on), GPU-vs-CPU-mirror = **9.3e-10 µm** (bit-faithful), **0 solver failures**.
- **EXPLICIT COMPLETE-MAT SINGLE HEAD:** NOT YET — the isolated coupled kernel + one-step gate pass; wiring
  into the full `MatSoaSlice` graph (shared stages 1–6/8/11 + a device explicit head-placement so `bondForces`
  sees the beam-derived head pose) is the next step (§7).
- **EXPLICIT SHORT GLIDING:** NOT YET (§9; gated on §7).
- **DEVICE RESIDENCY:** the coupled kernel is residency-ready — it mutates the flat beam SoA in place and reads
  F8h from the shared `bondData` (no `double[][]`, no per-step host reconstruction); the single-head slice
  already proved persistent across-step residency of the same beam SoA. Full-mat residency is validated when wired.
- **END-TO-END THROUGHPUT:** not yet measured (needs §7 full-mat wiring).
- **NEXT STEP:** §7 — wire `matS2SolveStep` into `MatSoaSlice` behind build-time model dispatch (calibrated
  `matStep7` vs explicit `matS2SolveStep`), add the explicit mat SoA + a device `placeHead2D` (head body pose
  from the beam geom, for `bondForces`), run a pre-bound single motor through the complete mat, then §8
  stroke/recoil and §9 short low-density gliding.

## §-by-§
| § | item | result |
|---|---|---|
| §1 | freeze `s2SolveM` coupling contract (coords, residual/Hessian, F8/converter, MAT salts, update order, failure, shared arrays) | DONE (`EXPLICIT_MATS2SOLVE_CONTRACT.md`) |
| §2/§3 | `matS2SolveStep` kernel + flat explicit mat SoA (nodes/frame/q/params/sys, bondData→F8h, boundSeg) | DONE (no double[][], no per-step alloc) |
| §4 | build-time dispatch | the kernel is dispatch-ready (a distinct task, never calibrated); the graph-level dispatch lands with §7 wiring |
| §5 | isolated lowering probe (5 states: relaxed/high-axial/bend/taut/post-stroke, real `buildS2Mat` scene) | **PASS** — LOWERS + EXECUTES, 0 NaN/Inf; `brownTorqueD` 64-bit long hash lowers (already proven in `matStep7`) |
| §6 | one-step gate: GPU vs CPU-mirror (bit-faithful) + CPU-mirror vs production `s2SolveM` (port faithful) | **PASS** — port 1.1e-9 µm, GPU 9.3e-10 µm, Δphi/Δpsi ~1e-15, forceDotFil/forceMag exact, status 0 |
| §7–§13 | full-mat wiring, stroke/recoil, short gliding, smoke, half-dt/cull, throughput | NEXT INCREMENT (unblocked by §5) |

## Notes
- **Faithfulness of the one-step port.** Production `stepGlideS2` calls `s2SolveM` ONCE per timestep (drag-limited
  implicit dynamics). `matS2SolveStep` with `counts[1]=1` (maxIt=1) = exactly one assembly+solve+increment = one
  `s2SolveM` step. The `§6` gate drives the REAL production `s2SolveM` on the REAL `buildS2Mat` gliding mat and
  compares the resulting node/phi/psi increments + reaction writeback — with **Brownian ON** (the mat salts +
  `brownTorque` RNG stream match to fp, confirming the RNG port).
- **Why 1.1e-9 µm, not 0.** `s2SolveM` assembles the RHS via `s2NodeForcesM` and the Hessian via
  `beamTangentFree` (two functions); `matS2SolveStep` assembles both INLINE (the same closed-form expressions
  as the validated `beamRelaxAnalytic`). The two orderings differ only at machine epsilon on µm-scale
  coordinates amplified through the solve — the same FP-op-order floor the single-head gate showed (9.1e-9 vs FD).
- **FD oracle.** `s2SolveM` here runs the ANALYTIC path (the production default). The analytic-vs-FD physical
  basin equivalence is already established (single-head §6: GPU-vs-FD 9.1e-9 µm; the beam fixture gate); the
  coupled port inherits it since the mechanics are byte-shared. A dedicated coupled FD arbitration lands with §7.

## Commands
```
./scripts/build.sh
java @tornado-argfile --enable-preview -Xmx8G -Dtornado.recover.bailout=false -Dtornado.enable.fma=false \
     -Dtornado.tvm.maxbytecodesize=65536 -cp "$TDIR/tornado-api-4.0.1-dev.jar:." softbox.ExplicitMatSolveHarness
```
