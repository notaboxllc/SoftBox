# CPU analytic Newton solver for the explicit-S2 beam — B1–B6 gate report

Follows the derivative gate (`ANALYTIC_JACOBIAN_DERIVATION.md`, `BEAM_EQUATIONS_FROZEN.md`). Assembles a CPU
double-precision analytic Newton solver on the validated exact residual+Jacobian and proves it is
**converged-state-equivalent** to the frozen finite-difference `s2Solve` — the gate that unblocks the
explicit GPU kernel (Part C). CPU-only; the frozen `s2Solve` is **not modified or removed** (still the
authoritative oracle and production default); **new files only**; no beam physics / energy / topology /
params / chemistry / binding / model-ID changed.

## New-file inventory (how FD stays the default)
- `softbox/ExplicitBeamSolver.java` — the analytic solver. `assemble(cm,…,Mode,Mout,Fout)` builds the
  (3M+2)=14-DOF system for any `Mode`; `solveFlat` is the flat allocation-free 14×14 solve; `s2SolveMode`
  runs one Newton step; `stepS2Mode` is a full-step replica of `stepS2` that swaps ONLY the beam solve.
  Selection = `Mode{tangent∈{FD,ANALYTIC}, residual∈{FD,ANALYTIC}}`:
  - `FD_REPLICA` = `{FD,FD}` — reproduces `s2Solve` **bit-for-bit** (the replica-faithfulness control).
  - `ANALYTIC` = `{ANALYTIC,FD}` — the **B1 accepted analytic solver**: frozen residual + analytic Hessian
    ⇒ IDENTICAL fixed point to the FD path, only the tangent (and its cost) changes.
  - `FULLY_ANALYTIC` = `{ANALYTIC,ANALYTIC}` — for the eventual GPU path (removes ALL finite differences).
  There is **no call site change**: `TwoBodyConverterMotor.stepS2`/`s2Solve` still run FD unconditionally;
  the analytic path is reached only through the NEW `ExplicitBeamSolver`/harnesses. Production default = FD.
- `softbox/ExplicitBeamSolverGateHarness.java` — B3 single-step + B4 converged-state gate.
- `softbox/ExplicitBeamDynamicHarness.java` — B5 dynamics + B6 throughput.
- `scripts/run_explicitsolver.sh`, `scripts/run_explicitdyn.sh`.
- Outputs: `RUN_LOGS/explicit_jac/solver_gate.txt`, `RUN_LOGS/explicit_jac/dyn_throughput.txt`.

## B2 — fixed-size, allocation-free linear solve
`solveFlat` (Gauss–Jordan, partial pivoting) operates on a caller-provided FLAT `double[n*(n+1)]` augmented
buffer — no multidimensional local arrays, no per-step allocation (a `Scratch` holds all buffers). Arithmetic
is identical to the frozen `solveLin`, so a matching tangent yields a bit-identical increment (verified in
B3.0). It adds **deterministic singularity DETECTION** (a failure flag when `|pivot|` underflows) but **no
ridge/regularization** beyond the node-drag diagonal `aN=γ_node/dt` the FD path already carries — detection
does not alter valid states (0 singular failures were observed on either solver across the whole ensemble).

## B3 — single-step equivalence
| quantity | result | interpretation |
|---|---|---|
| `FD_REPLICA` vs frozen `s2Solve` (2800 configs) | max\|Δstate\| = **0** | the replica is bit-identical to the oracle |
| residual vector (ANALYTIC vs FD) | max\|Δ\| = **0 N** | identical by construction (both use the frozen residual) |
| Newton RHS | max\|Δ\| = **0** | identical |
| tangent (analytic − FD) normwise | **1.40e-6** | the FD `h=1e-5` truncation being REMOVED (not a bug) |
| Newton increment `dq` | max\|Δ\| 0.26 (rel 0.20 on stiff far-from-eq configs) | the different tangent ⇒ a different but consistent step |
| per-step ΔE (FD vs analytic) | max\|Δ\| = **1.6e-14 J** | both steps decrease energy by the same amount |

The `dq` difference is expected (the analytic Hessian legitimately differs from the truncated FD tangent) —
the question of whether it heads to the same basin is B4.

## B4 — converged-state equivalence (THE GATE)
Both solvers relaxed from identical initial states over **5110 configs** (10 golden fixtures + 5100
synthesized spanning low/high axial strain, taut, bending-dominated, near-floor, pre/post-stroke,
assist/resist load, buckled, and difficult random valid states), plus a full-stepper dynamic settle.

- **Failure rate:** 0 singular-matrix failures on BOTH solvers. "Not-settled-within-budget" = 2325 (FD) /
  2325 (ANALYTIC), Δ=0 — these are the FAITHFUL damped dynamics' slow soft modes (a 40 nm beam with
  Lp≈180 nm is floppy), un-settled IDENTICALLY on the same configs in both solvers.
- **Physical basin mismatches** (energyRel>1e-3 OR contourΔ>1e-4 µm OR reaction-force relΔ>1e-2 OR
  load-class differ, among configs BOTH solvers converged): **0**.
- **Soft-mode drift** (both converged, node position differs >1e-4 µm but energy/contour/reaction/class
  identical): 1 config (`random#60616`: \|Δpose\|=1.8e-3 µm but energyRel=1.8e-6, reaction relΔ=1.9e-5,
  same class) — the SAME basin reached along a near-zero-stiffness transverse bending mode, not a distinct
  basin. Investigated individually; benign.
- **Un-converged tracking:** the 2325 still-relaxing configs track between solvers to max \|Δpose\|=1.06e-4
  µm and reaction relΔ=2.37e-4 — i.e. FD and ANALYTIC are at the same state even mid-relaxation.
- **Load-bearing classification:** FD 4646 load-bearing / 464 bending-dominated = ANALYTIC 4646 / 464,
  **0 class mismatches** — the bending-dominated bound population is preserved exactly.
- **Full-stepper dynamic settle** (bound fixtures, `stepS2` vs `stepS2Mode`-ANALYTIC, 3000 steps): max
  \|Δ motor pose (P,C,xF8)\| = **7.4e-12 µm**, 0 basin mismatches.

Per-regime: `assist/resist/taut/highAxial/lowAxial/poststroke` converge in 5–6 steps with maxPoseΔ ≤ 1e-14
µm; the analytically-soft `bending/buckled/prestroke/nearFloor` regimes relax slowly (identically in both).

**GATE VERDICT: PASS** — no increased failure rate; same physical solution basin (0 mismatches);
load-bearing classification kept; bending-dominated population preserved; no systematic energy/contour/force
bias. The CPU analytic solver is converged-state-equivalent to FD.

## B5 — short dynamic validation (do NOT promote on static fixtures alone)
Isolated bound explicit-S2 motor, Pi-release power stroke, **Brownian ON** (FD and ANALYTIC share the same
hashed noise), 40 seeds × {assist +2, zero, resist −2 pN}. FD vs ANALYTIC distributions:

| load | working stroke (nm) | pivot recoil (nm) | F8 axial on actin (pN) | paired max\|Δ\| stroke | AN failures |
|---|---|---|---|---|---|
| assist +2pN | 2.667±0.258 (both) | 0.323±0.631 (both) | −6.255±1.234 (both) | 9.1e-6 nm | 0 |
| zero 0pN | 2.661±0.262 (both) | 0.347±0.662 (both) | −5.605±1.234 (both) | 1.3e-5 nm | 0 |
| resist −2pN | 2.653±0.267 (both) | 0.376±0.695 (both) | −4.958±1.233 (both) | 9.3e-6 nm | 0 |

Mean-stroke relΔ ≤ 1.1e-6; the load dependence (stroke slightly ↓ and F8 force ↓ with resisting load) is
reproduced identically. Rapid 10 nm trap displacement: FD and ANALYTIC settle to the same COM (6.688 nm,
max\|Δ\|=0). **B5: PASS.** (Note: the isolated `stepS2` has no binding-kinetics call, so bound-lifetime /
gliding-count distributions are a multi-motor gliding-driver concern — the natural next step; the per-motor
mechanism they are built from is validated here, and B4 covered thousands of bound/loaded beam states.)

## B6 — CPU throughput (the expected GPU-benefit indicator)
| measurement | FD | ANALYTIC | speedup |
|---|---|---|---|
| isolated beam solve (assemble+14×14 solve), 60k iters | 67.96 µs | 7.23 µs | **×9.4** |
| full `stepS2` (bond+gather+traps+integrate+beam solve), 20k steps | 58.1 µs | 7.4 µs | **×7.9** |

Attribution: the FD tangent needs **25 `s2NodeForces` evaluations ⇒ 750 `s2BendEnergy` (acos/sqrt)
evaluations per solve**; the analytic tangent does **0** nested evaluations (closed-form algebra + 2 finite
scalars). Removing the nested FD is both the ~9× cost reduction AND the collapse of the inlined-node count /
transcendental count that made the merged `explicitBeamStep` GPU kernel exceed TornadoVM's 600-node inline
cap — i.e. this is exactly the reduction that unblocks the GPU path.

## Verdict
The CPU double analytic solver is **converged-state-equivalent to the FD solver** (B4 PASS), single-step
consistent (B3), dynamically equivalent under load with Brownian noise (B5), and ~8–9× cheaper by removing
the nested finite difference (B6). **The gate that unblocks the explicit GPU kernel (Part C) is passed.**
The frozen `s2Solve` remains the production default until the analytic solver is formally accepted.
