# GPU Step-7 increment — results (calibrated unblock + explicit analytic Jacobian)

Consolidated results for the increment "unblock calibrated GPU execution + build an exact analytic-Jacobian
explicit solver." Baseline `main` = `2215ec0` (untouched; GPU gate `MotorGpuParams.DEVICE_VALIDATED=false`
stays closed). Work committed on two feature branches:
- **`gpu-device-bench`** (`0d5c6c1`): device probe + calibrated scalarization/lowering. Detail:
  `docs/gpu_port/DEVICE_PROBE_FINDINGS.md`, `RUN_LOGS/calibrated_device/CALIBRATED_DEVICE_GATE.md`.
- **`explicit-analytic-jac`** (`efe1b6e`): analytic Jacobian derivation + derivative gate. Detail:
  `docs/explicit_jac/{BEAM_EQUATIONS_FROZEN,ANALYTIC_JACOBIAN_DERIVATION}.md`, `RUN_LOGS/explicit_jac/gate.txt`.

## Starting point (device probe, `DEVICE_PROBE_FINDINGS.md`)
Of the three merged Step-7 kernels, only `fixedStep` lowered to PTX (correct on GPU). `calibratedStep`
failed on `NewMultiArray` (local 2D scratch); `explicitBeamStep` failed on the 600-node inline cap
(`s2NodeForcesK` = 973 nodes, the nested finite-difference tangent). Toolchain proven by a `vecAdd` control.

---

## Part A — calibrated: `CALIBRATED GPU VALIDATED BUT NOT PROMOTED`

`calibratedStep` now **lowers to PTX and executes correctly on the RTX 5070**. Four changes, all
storage/expression-form only — **no equation, parameter, RNG-salt, ordering, chemistry, binding, or
model-ID change**:
1. **Scalarized** the 5×5 solve — removed `float[][] J`, `new float[5][5] K`, `new float[5][6] Msys`,
   `gaussJordan5(float[][])` → named scalars + hand-unrolled Gauss-Jordan, same float ops/order.
2. `Math.abs(float)` → `fabs` branch (JDK lowers to an unimplementable reinterpret). Universally bit-identical.
3. `Math.max(double)` → ternary (same reinterpret issue). Universally bit-identical.
4. `Math.log1p(exp(z))` → **compensated log1p** `log1pC(x)=log(u)·x/(u−1)`, `u=1+x` (return `x` if `u==1`).
   Same softplus law `s·ln(1+eᶻ)`, universally ~1 ulp (no small-argument bias), PTX-lowerable.
   *(Coordinator-approved change to the protected softplus's evaluation; not the law.)*

**A2 bit-identity (CPU runner):** `-validatekernels` calibrated deltas UNCHANGED vs pre-scalarization
(1.66e-8, 1.64e-8, 1.66e-8, 1.66e-8, 1.75e-8, 1.35e-8, 2.62e-8, 6.00e-9, 1.66e-8, 2.00e-8); 30/30 PASS.
The log1p change shifted no fixture delta (< 1 float ulp, invisible at printed precision).

**A4 device gate (RTX 5070, `-Dtornado.recover.bailout=false` — no silent fallback):**

| metric | result |
|--------|--------|
| lowered to PTX + executed | **10/10** (no `NewMultiArray`, no `ReinterpretNode`, 0 failed) |
| T3 vs CPU oracle (maxAbsΔ ≤ 1e-6) | **10/10 PASS**, aggregate maxAbsΔ **3.96e-8** |
| RMS per fixture | 1.99e-9 … 1.06e-8 |
| NaN / Inf | 0 / 0 |
| cold compile | ~479 ms first task, 41–89 ms after |
| warm single-launch | min 0.210 ms / mean 0.273 ms (N=1 host-round-trip bound — NOT throughput) |

(The `maxRelΔ≈4.3` entries are a near-zero-denominator artifact on a ≈0-by-symmetry field, abs ≈ 1.6e-8.)

**Not promoted:** A5/A6 remain — wire the calibrated Step-7 kernel through the full device gliding loop
(Steps 1–6 + measurement reductions), then throughput + ensemble-within-SEM vs the CPU-double arbiter at
200/700/1500/3000 motors/µm². `DEVICE_VALIDATED` stays `false` until those pass.

---

## Part B — explicit analytic Jacobian: `EXPLICIT ANALYTIC SOLVER NOT YET VALIDATED` (derivative gate PASSED)

The exact analytic residual + Jacobian for the **unchanged** EXPLICIT_S2_L40 beam energy is validated at
the derivative level. New files only; the FD solver stays the authoritative oracle.
- **B2** froze the equations (`BEAM_EQUATIONS_FROZEN.md`): 14 DOF (5 nodes + φ,ψ), stretch `½ks(Δl)²`,
  bending `Σ½kb·acos(c)²`, floor penalty, node drag/Brownian, F8/converter Gauss-Newton block (E=eup axis).
- **B3** derived (`ANALYTIC_JACOBIAN_DERIVATION.md`): the tangent `s2Solve` builds **is the energy Hessian**
  `∂²E/∂x²`. Bending reduces to `∇E=g'(c)∇c`, `Hess=g''∇c⊗∇c+g'∇²c` with all transcendentals confined to two
  finite scalars `θ/sinθ→1`, `(1−θcotθ)/sin²θ→1/3` (exact gradient of the SAME `½kb·acos²` energy, finite
  at θ→0). Small-angle, θ→π cusp, and floor kink handled.

**B4 derivative gate (`ExplicitBeamJacobianHarness`, 701 poses, vs Richardson/central-difference oracle):**

| block | force normwise | Jacobian normwise |
|-------|---------------:|------------------:|
| stretch | 2.9e-11 | 4.2e-9 |
| bend | 8.4e-12 | 1.3e-8 |
| floor | 1.6e-11 | 2.3e-13 |
| **total** | **3.4e-11** | **4.1e-9** |

Plus: analytic residual vs frozen `s2NodeForces` = 8e-10; analytic tangent vs the code's own nested-FD
tangent = 1.1e-6 (limited by the code's h=1e-5 truncation — the analytic is strictly more accurate);
Hessian symmetry 7e-17; textbook Δ∝h² convergence over 4 decades. **All blocks ≤ 1e-6. OVERALL: PASS.**
No energy/residual alteration needed.

**Remaining:** B5 (assemble the analytic Newton solver, prove converged-state equivalence to the FD oracle)
→ B6/B7 (flat GPU kernel — the node-count collapse should finally let it lower) → B8–B11 (microbench,
precision study, trap + gliding end-to-end).

---

## Bottom line
The two-tier workflow is viable: calibrated has a correct GPU kernel (needs only end-to-end wiring to
promote); the explicit analytic Jacobian — which both makes the beam GPU-lowerable AND removes the FD cost
— is mathematically exact (derivative-validated). Nothing is promoted; `main` clean; GPU gate closed.
