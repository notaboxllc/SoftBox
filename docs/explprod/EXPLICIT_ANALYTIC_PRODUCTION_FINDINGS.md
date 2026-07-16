# Explicit analytic solver wired into production CPU gliding — validation + promotion

Wires the derivative-/converged-state-validated analytic explicit-S2 beam solver into the PRODUCTION CPU
explicit steppers behind an `explicitSolver=fd|analytic` selector, validates FD-vs-analytic equivalence IN
the gliding loop across 200/700/1500 µm⁻² × (production + half dt), and — all gliding gates passing — makes
analytic the CPU default with FD retained as the permanent oracle. Branch `explicit-analytic-production`;
CPU-only. No change to model ID / beam energy / residual / constraints / chemistry / binding / parameters.

## 1.1 Wiring (diff: 3 files, +1 test)
- `TwoBodyConverterMotor.java`: `enum ExplicitSolver{FD,ANALYTIC}` + `static ExplicitSolver explicitSolver`.
  In **`s2Solve`** (single-motor) and **`s2SolveM`** (mat/gliding, called from `stepGlideS2`) the beam TANGENT
  block branches: ANALYTIC calls `ExplicitBeamAnalytic.beamTangentFree(...)` (the exact energy Hessian),
  FD keeps the frozen nested central difference. **Only the tangent is swapped** — the residual
  (`s2NodeForces`/`s2NodeForcesM`, frozen FD-bend), node drag, F8/converter/bind coupling, Brownian salts +
  order, `solveLin`, increment application, node-0 re-pin, endpoint force/torque extraction and load-bearing
  classification are byte-identical between paths.
- `ExplicitBeamAnalytic.java`: `beamTangentFree` refactored to a **param-based single implementation**
  (`beamTangentFree(M,ks,kb,l0,kfloor,floorZ,eup,g4Tan,nd)`); the `Cmot` overload delegates. Both the harness
  and both production paths call the SAME code — **no duplicated analytic code**. The refactor is
  behavior-preserving: the derivative gate reproduces its exact pre-refactor numbers.
- `LaserTrapHarness.java`: `-explicitsolver fd|analytic` pre-scan (order-independent vs `-motor`/`-glide`).
- **New** `ExplicitFdRegressionTest.java` (1.5 regression, below).

## 1.2 Failure semantics preserved
Identical: single linearly-implicit Newton step per timestep, no inner iteration, `solveLin` (no added
singular detection in the production path), no state rollback, the `Double.isFinite` break in `measureS2Mat`,
the taut/load-bearing classification, and the `forceDotFil`/`forceMag` endpoint extraction. Nothing in the
failure path was improved or simplified.

## 1.3 Production FD-vs-analytic gliding validation (matched seeds, identical IC/RNG)
Explicit-S2 mat, 3×1 µm lawn, matched seed base 4321. `FD ≡ ANALYTIC` to displayed precision on EVERY channel:

| ρ (µm⁻²) | dt | velocity (µm/s) | avgBound | load-bearing | continuity | handoff | pDirected | stable |
|---|---|---|---|---|---|---|---|---|
| 200 | prod | −1.406 ± 0.456 | 1.364 | 68 % | 0.698 | 0.692 | 1.00 | 3/3 |
| 700 | prod | −3.074 ± 1.540 | 5.293 | 68 % | 0.998 | 0.965 | 1.00 | 3/3 |
| 1500 | prod | −4.732 ± 0.651 | 10.162 | 68 % | 0.999 | 0.984 | 1.00 | 3/3 |
| 200 | half | −1.929 ± 0.595 | 1.728 | 63 % | 0.787 | 0.727 | 1.00 | 2/2 |
| 700 | half | −2.902 ± 2.976 | 7.568 | 62 % | 0.999 | 0.968 | 1.00 | 2/2 |
| 1500 | half | −4.560 ± 0.766 | 13.747 | 61 % | 0.999 | 0.981 | 1.00 | 2/2 |

Each row: FD and ANALYTIC give the SAME value (velocity, net, avgBound±sd, continuity, handoff, pDirected,
transWander, angWander, load-bearing/bending fraction all match; ATP/µm matches to ~1e-4). The largest visible
divergence is the d1500-half aggregate load-bearing (8.328 FD vs 8.327 AN — the tangent difference ~1e-6 over
the most steps at the largest scale). **Bending-dominated fraction (32–39 %) preserved and identical.**
- 0 new analytic failures (all seeds stable, all finite).
- No basin mismatch, no systematic velocity/avgBound/continuity/handoff/load-bearing/contour bias.
- Deep converged-state gate (5110 configs, `ExplicitBeamSolverGateHarness`): FD_REPLICA ≡ s2Solve **Δ=0**;
  0 physical basin mismatches; 0 singular failures; bending-dominated FD 464 = AN 464 — **PASS**.
- Matched-seed determinism: the identical residual ⇒ identical fixed point ⇒ FD/analytic track step-for-step,
  decorrelating only at the ~1e-6 tangent level (chaotic), which keeps the ensemble observables identical.

## 1.4 CPU throughput (FD → analytic)
| measurement | FD | ANALYTIC | speedup |
|---|---|---|---|
| isolated beam solve (assemble + 14×14) | 70.0 µs | 7.6 µs | **×9.23** |
| full explicit step (bond+gather+traps+integrate+beam) | 59.3 µs | 7.5 µs | **×7.96** |
| gliding ρ=200 (wall s/sim-s) | 2169.6 | 325.1 | **×6.67** |
| gliding ρ=700 | 7657.1 | 1084.9 | **×7.05** |
| gliding ρ=1500 | 16066.4 | 2324.1 | **×6.91** |

The beam solve is the dominant explicit-step cost, so removing the nested FD (~25 `s2NodeForcesM` evals ⇒
~750 transcendental bend-energy evals per motor per step) gives ~7× on the full CPU gliding loop. Iteration
count is unchanged (single Newton step per timestep, both paths); 0 failures both.

## 1.5 Promotion decision — **EXPLICIT CPU ANALYTIC SOLVER PROMOTED**
All production gliding gates (1.3) pass: 0 new failures, no basin mismatch, bending-dominated population
preserved, no systematic bias, ensemble observables identical within uncertainty. → the CPU default is flipped
to `ExplicitSolver.ANALYTIC`. **FD retained as the permanent oracle/debug path** (`-explicitsolver fd`).

Regression guard `ExplicitFdRegressionTest` (**OVERALL PASS**):
- Gate 1 FD selectable as oracle (production default = ANALYTIC) — PASS.
- Gate 2 FD `s2Solve` ≡ `ExplicitBeamSolver` FD_REPLICA over 200 configs — **max|Δ|=0 (bit-identical)** ⇒ the
  FD branch is byte-unchanged by the wiring.
- Gate 3 selector round-trips (FD→ANALYTIC→FD), FD deterministic — PASS.
- Gate 4 ANALYTIC 0 non-finite; single-step FD↔AN Δ=4.6e-4 (tangent-only, same basin) — PASS.

Post-flip regressions all green: derivative gate (identical numbers), motor-regression Gates A–F PASS
(GateE stroke 7.27 unchanged under the analytic default; GateC/D bit-identical; GateF serialize/restart),
default gliding confirmed running analytic (wall 389 vs FD 2170 s/sim-s).

**Run:** `-motor explicit-s2-l40 -glide …` (now analytic by default); `-explicitsolver fd` forces the oracle.
Logs: `RUN_LOGS/explprod/` (glide_*, throughput_dynamic, solver_gate_regression, fd_regression,
motor_regression_postflip).
