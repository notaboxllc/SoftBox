# GPU motor increment 2 — results for the planner

**Scope:** (A) integrate + validate the full calibrated GPU gliding loop; (B) assemble + validate the CPU
double analytic Newton solver for `explicit-s2-l40`; (C) build the flat analytic GPU beam kernel and
measure whether it lowers + its throughput. Numerical-implementation project — **no motor physics,
chemistry, binding, canonical parameters, or model identity changed.** Baseline `main` = `2215ec0`
(untouched). `MotorGpuParams.DEVICE_VALIDATED = false` (unchanged — nothing promoted). All work on two
feature branches (per-part detail docs cited at the end).

## Final statuses
- **Calibrated:** `CALIBRATED GPU BLOCKED` — the full device gliding loop is not buildable as-is (structural).
- **Explicit:** analytic Newton solver **VALIDATED** (CPU, converged-state-equivalent to FD); the analytic
  **GPU beam kernel LOWERS + runs + is ≥10× isolated**; *end-to-end* explicit gliding on GPU is **gated on
  the same mat-SoA prerequisite** as calibrated.

---

## Part A — calibrated full-loop GPU: BLOCKED (bail-out-and-report)
The two-body gliding harness (`TwoBodyConverterMotor`, 8354 lines) is **100% host-sequential** — zero
TaskGraph/`@Parallel`/device code (`grep = 0`). The per-motor pivot/converter state (`phi/psi/A/C/xH/xF8`)
lives in `Glide2D` as **host `double[]`/`double[][]`**, never in device SoA; stages **cull, gate, bind,
Step-7 solve** run host-scalar with heap allocation. Only chemistry, the CSR gather, and parts of the
mechanics/integrate are real kernels. The device-lowerable `calibratedStep` from increment 1 is a
**separate single-motor kernel** (RNG salts `0x4F1..0x4F5`), **not** the mat's `supSolveM` (`0x5F1..0x5F5`)
— never wired to the mat. A partial device path would need a full per-motor host↔device round-trip every
step → transfer-bound, **slower than CPU** → not built. **What it needs:** a device-resident **mat-SoA
store + 5 new bit-faithful `@Parallel` SoA kernels** (matCull, matGeom/matPlaceHead, matNearestGate,
matBind, matStep7), each device-gated for lowering + CPU≡GPU — a dedicated increment, not a "wire it up"
task. Detail: `docs/gpu_port/06_CALIBRATED_FULLLOOP_A1_BLOCKER.md`.

## Part B — explicit CPU analytic Newton solver: VALIDATED (converged-state-equivalent to FD)
New `ExplicitBeamSolver` on the increment-1 derivative-validated analytic Jacobian. `s2Solve` (FD)
untouched — stays the authoritative oracle + production default; new files only.
- **B1/B2:** `FD_REPLICA` reproduces `s2Solve` **bit-for-bit** (2800 configs, Δ=0). The `ANALYTIC` mode =
  frozen residual + exact analytic Hessian ⇒ identical fixed point. Flat allocation-free 14×14 solve, no
  extra regularization beyond the FD path's `γ/dt` diagonal.
- **B3 single-step:** residual/RHS Δ=0; analytic−FD tangent 1.4e-6 (the removed FD truncation); per-step
  ΔE agrees 1.6e-14 J.
- **B4 converged-state gate — PASS** over **5,110 configs** (all regimes + 10 golden fixtures): 0 basin
  mismatches, 0 new failures, **bending-dominated load-bearing population preserved** (4646/464 ≡),
  full-stepper settle Δpose 7.4e-12 µm. Every apparent mismatch individually benign (un-converged snapshots
  / soft-mode drift, same basin).
- **B5 dynamics:** FD≡ANALYTIC stroke/recoil/force/torque (max|Δ|~1e-5 nm, 0 analytic failures).
- **B6 CPU throughput:** isolated beam solve **×9.4** (68.0→7.2 µs), full step **×7.9** (58.1→7.4 µs) —
  eliminates 750 `acos`/`sqrt` evals per solve.
Detail: `docs/explicit_jac/ANALYTIC_SOLVER_GATE_FINDINGS.md`.

## Part C — explicit analytic GPU beam kernel: LOWERS + runs, ≥10× isolated
New flat `@Parallel` kernel `beamRelaxAnalytic` (analytic residual + exact Hessian + flat 14×14 solve;
no `double[][]`/no `new`; per-thread flat scratch; explicit convergence/singular flags). Bit-faithful to
the CPU analytic solver.
- **C2 lowering (the headline):** the FD kernel could NOT lower (973 inline nodes > 600 cap); the analytic
  form does. First device attempt hit a **TornadoVM PTX FMA-lowering NPE** — fixed by a **flag, not an
  equation** (`-Dtornado.enable.fma=false`, **required** for this kernel on the PTX backend). Then: **LOWERS
  + EXECUTES on the RTX 5070, GPU vs CPU-mirror bit-identical (Δ=0).**
- **C3 device fixture gate:** 10 fixtures — GPU-analytic vs CPU-analytic ≤2.5e-9 µm (7/10 bit-identical),
  vs CPU-FD ≤9.1e-9 µm (same basin, enRel ≤1.2e-4), 0 failures. The B4 equivalence carries to the device.
- **C4 microbench (full-relaxation solves/s):** CPU-FD ~26 → CPU-analytic ~334 (×12.8) → **GPU-analytic
  473 → 23,205** across batch 128→65536. At 65536: **×69 vs CPU-analytic, ×892 vs CPU-FD**, 0 failures,
  ×49 batch scaling. Class: **≥10×** (FP64 ≈ 1/64 FP32 is the consumer-RTX-5070 ceiling).
- **C5-full/C6 (end-to-end) GATED:** the full `stepGlideS2` gliding loop has the SAME host-sequential
  mat-SoA blocker as Part A. The isolated beam-solve throughput (C4) is the achievable measurement; an
  on-device single-head trap replay is feasible as a partial. Detail: `docs/explicit_jac/GPU_KERNEL_FINDINGS.md`.

---

## The single highest-leverage next step (unblocks BOTH models)
Both end-to-end gliding paths share one prerequisite: a **device-resident `Glide2D` mat-SoA store + per-stage
`@Parallel` SoA kernels** (cull/gate/bind/geom/Step-7). It promotes calibrated (Part A) AND turns the
explicit GPU beam win into an end-to-end one (Part C). Projection: once the mat is SoA, the explicit beam
solve — previously the dominant per-motor cost *and* the non-lowering kernel — becomes a ~70×-faster
device kernel and stops being the bottleneck; end-to-end is then bounded by the lighter cull/gate/bind.

## On the laser-tweezers campaign
The gate was "no long campaign until explicit GPU feasibility is measured." **It is now measured and
favorable** (kernel lowers, runs correctly, ≥10× isolated). Hold the long campaign until end-to-end
explicit gliding exists (needs the mat-SoA increment); the isolated-beam result substantially de-risks it.

## Operational note for the planner
`-Dtornado.enable.fma=false` is **required** to run the analytic explicit GPU kernel on the TornadoVM PTX
backend (the default FMA phase NPEs); it also tightens CPU↔GPU agreement.

## Branch / commit / doc inventory
- `gpu-device-bench` `2d6fa86`: Part A blocker (`docs/gpu_port/06_CALIBRATED_FULLLOOP_A1_BLOCKER.md`).
- `explicit-analytic-jac` `3b79cc1`: Part B solver (`ExplicitBeamSolver.java`, `ANALYTIC_SOLVER_GATE_FINDINGS.md`).
- `explicit-analytic-jac` `5d4a076`: Part C GPU kernel (`TwoBodyBeamAnalyticGpu.java`,
  `ExplicitBeamGpuHarness.java`, `GPU_KERNEL_FINDINGS.md`).
- `main` `2215ec0`: untouched; GPU gate closed. Nothing promoted.
