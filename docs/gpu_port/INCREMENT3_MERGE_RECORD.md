# GPU-motor increment 3 — merge to main + analytic-solver promotion decision

`MERGE STATUS: MERGED TO MAIN — VALIDATED COMPONENTS ONLY; FULL GPU MODELS REMAIN GATED`

Merged the two validated feature branches into `main`. Numerical-implementation/architecture work — **no
motor physics, chemistry, binding, canonical parameters, or model identity changed.** GPU gate stays
closed (`MotorGpuParams.DEVICE_VALIDATED=false`); FD explicit solver remains the production default + oracle.

## Merge
- Safety tag `pre-gpu-motor-merge` at the old `main` `2215ec0`.
- `main` `2215ec0 → 5b0fb59`: fast-forward to `gpu-device-bench` (`2d6fa86`), then a merge commit for
  `explicit-analytic-jac` (`9a2b4ca`). **No conflicts** — the branches touch disjoint files
  (`explicit-analytic-jac` never modified the shared `TwoBodyGpuKernels.java`). Branch history preserved.
- Brings: calibrated device probe + scalarized/lowerable `calibratedStep`; the exact analytic beam
  residual+Jacobian; `ExplicitBeamSolver` (FD-replica bit-identical to `s2Solve`; analytic mode
  converged-state-equivalent); the flat analytic GPU beam kernel (`TwoBodyBeamAnalyticGpu`, lowers to
  PTX, ≥10× isolated); validation harnesses; findings docs.

## Post-merge regression (on `5b0fb59`) — all PASS, every number == pre-merge (no regression)
1 build · 2 `-motor-regression` Gates A–F (B core Δ=0; C/D registry≡frozen Δpose=0; E stroke 7.27/6.90; F
serialize/restart) · 3 serialization · 4 canonical-param identity · 5 fixed-anchor · 6 `-validatekernels`
30/30 · 7 explicit derivative gate (≤1.35e-8) · 8 FD-replica vs `s2Solve` max|Δ|=0 (2800 configs) · 9
FD-vs-analytic converged-state gate (5110 configs, 0 basin mismatch, 0 failures, bending-dominated FD 464
= AN 464) · 10 explicit dynamics (FD≡AN ≤1.3e-5 nm, ×8.1) · 11 calibrated GPU device gate 10/10
(bailout=false) · 12 explicit analytic GPU gate (fma=false; GPU vs CPU-analytic 2.5e-9 µm) · 13
no-silent-fallback (`-gpu` refuses) · 14/15/17 calibrated/explicit-FD/single-head smokes stable · 16
analytic gliding smoke NOT BUILDABLE (analytic not wired into `stepGlideS2` — a scope gap, not a regression).
**Bending-dominated explicit population intact (loadBearing 69/73/71 % at d=200/700/1500). GPU gate closed.**

## Analytic CPU explicit solver — decision: `EXPLICIT CPU ANALYTIC SOLVER VALIDATED BUT NOT DEFAULT`
The analytic solver (frozen residual + exact analytic Hessian) has the **identical fixed point to FD by
construction**; all promotion criteria are met **at the isolated beam-solver level** (0 basin mismatch, 0
new failures, force/torque/energy/contour equivalent, trap observables ≡ FD, bending-dominated population
unchanged, restart/serialization correct, ~8–9× faster). **BUT it is not wired into the production gliding
stepper** (`stepGlideS2`/`s2SolveM` use the FD tangent; the FD/analytic toggle lives only in the
`ExplicitBeamSolver` validation harness). Making it the production default requires **(a)** wiring it into
`s2Solve`/`s2SolveM` behind an `explicitSolver=fd|analytic` selector (FD default retained), and **(b)** a
**gliding-level FD-vs-analytic re-validation** at 200/700/1500 motors/µm². Both belong to the mat-SoA /
gliding-integration increment (where the analytic path is also the device Step-7). Promoting an
unvalidated-in-gliding path now would violate the stop conditions. **No default flipped; no production code
changed this increment.**

## Final status block
- `MERGE STATUS: MERGED TO MAIN — VALIDATED COMPONENTS ONLY; FULL GPU MODELS REMAIN GATED`
- `EXPLICIT CPU DEFAULT: FD (unchanged); ANALYTIC VALIDATED BUT NOT DEFAULT` (needs gliding wiring + re-validation)
- `CALIBRATED GPU STATUS: CALIBRATED GPU BLOCKED` (host-sequential mat; needs the mat-SoA store + SoA kernels)
- `EXPLICIT GPU STATUS: analytic beam kernel VALIDATED (lowers to PTX, ≥10× isolated); end-to-end GATED on mat-SoA`
- `NEXT BLOCKER: the device-resident motor-mat SoA architecture` — unblocks BOTH end-to-end GPU paths AND is
  where the analytic solver gets wired into gliding. Groundwork (frozen host semantics + SoA schema + 11-stage
  graph) is on branch `motor-mat-gpu-soa` (`docs/matsoa/{HOST_SEMANTICS_FROZEN,SOA_SCHEMA}.md`, `347caa6`).

Reference: `docs/explicit_jac/INCREMENT2_RESULTS_FOR_PLANNER.md`, `docs/gpu_port/STEP7_GPU_INCREMENT_RESULTS.md`.
