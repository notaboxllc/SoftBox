# GPU device probe — Phase-1 empirical findings (throughput study)

Date 2026-07-16. Merged baseline `2215ec0` (main). Isolated worktree `/home/jba/Code/SoftBox-gpubench`
(branch `gpu-device-bench` off main). Probe harness: `softbox/GpuDeviceProbe.java`. Raw log:
`RUN_LOGS/gpu_device_probe/PROBE_LOG.md`. **The kernel arithmetic in `TwoBodyGpuKernels.java` was NOT
modified — this is a probe.**

## Hardware / software provenance
RTX 5070 · driver 595.71.05 · 12227 MiB (11544 free) · 250 W · SM 3120 MHz · persistence off.
TornadoVM 4.0.1-dev-ptx · JDK 21.0.11. Sweep finished; GPU free (no compute apps).

## Result — which merged Step-7 kernels execute on the GPU as written?

| kernel | lowers + runs on GPU | evidence |
|--------|----------------------|----------|
| positive control `vecAdd` (N=4) | **YES** | c=[11,22,33,44], 255 ms incl. first-launch compile ⇒ toolchain/harness sound |
| **`fixedStep`** (float32) | **YES** | ran on device (104.6 ms w/ compile); vs CPU-runner oracle **maxAbsΔ=1.47e-8**, NaN=0, Inf=0 (== the T3 float32 delta). Only kernel with NO 2D scratch (2×2 scalarized). |
| **`calibratedStep`** (float32) | **NO** | `GraalError: Node implementing Lowerable not handled: NewMultiArray` — the local `new float[5][5] K`, `new float[5][6] Msys`, `float[][] J`. The PTX backend cannot lower a local multi-dimensional array. |
| **`explicitBeamStep`** (double) | **NO** | `TornadoInliningException: Method s2NodeForcesK(double[][],…) cannot be inlined: node count (973) exceeds limit (600)`. TornadoVM must inline all callees; the beam-force helper is too large. Identical on both `bound_baseline` and `high_axial` ⇒ structural, not state-dependent. (Its `double[][]` scratch would also fail, downstream of this.) |

## Interpretation
The two device constraints the `TODO(GPU)` markers anticipated are both real and both hit:
1. **No local multi-dimensional array allocation** (`NewMultiArray`) — blocks `calibratedStep` (5×5) and
   `explicitBeamStep` (`double[M+1][3]` nodes, `double[n][n+1]` system).
2. **Callees must fit the 600-node inline cap** — blocks `explicitBeamStep`'s `s2NodeForcesK` (973 nodes),
   and the top-level beam kernel's ~25×(force-eval) × ~30×(energy-eval) nested finite-difference would
   almost certainly exceed the node budget even after the helper is split.

⇒ **The faithful explicit-S2 GPU path does not execute**, so its throughput is **unmeasurable** today.
Phases 2–6 (microbench, calibrated/explicit end-to-end, bottleneck attribution, throughput class) cannot
run for the two blocked kernels. `fixedStep` is a device-primitive validation success (correct on GPU),
but it is the regression/fixture baseline, not a production or mechanistic target.

## Load-bearing insight for the solver decision
The explicit blocker is **the nested finite-difference tangent itself** (node-count explosion), not FP64
throughput, occupancy, or divergence — none of which we could even reach. An **exact analytic Jacobian**
replaces that nested FD with a compact closed form, which would **simultaneously (a) collapse the node
count below the inline cap (enabling GPU execution at all) and (b) remove the dominant arithmetic cost.**
So for the explicit beam, the analytic Jacobian is potentially the **enabling** step for a GPU port, not
merely a speed optimization — but this MUST be confirmed by building and measuring it, not assumed
(porting discipline). The physical energy/residual/constraints/parameters stay unchanged; only the
tangent's construction changes.

## Scoped unblock plan (grounded in the exact errors)
- **`calibratedStep` (EASY, ~bounded):** flatten the 5×5 Gauss-Jordan scratch to scalar registers (25
  named locals) or a 1D per-thread buffer; remove the `float[][] J`/`K`/`Msys`. No algorithm change.
  Then it should lower (fixedStep proves scalarized float32 lowers). → unblocks Phase-3 **calibrated**
  end-to-end GPU (the production surrogate — the higher near-term value).
- **`explicitBeamStep` (HARD):** two options —
  (i) faithful port: split/inline `s2NodeForcesK` under the node cap AND flatten all `double[][]` to 1D
      per-thread scratch; likely still bumps the node cap on the nested FD → iterative restructuring, FP64.
  (ii) analytic-Jacobian port (the study's Phase 7): compact closed-form tangent → small node count,
      float32-tolerable, lowers cleanly. Higher up-front derivation cost, but likely the ONLY path that
      both lowers and is fast.
- Build the missing device harness (`BeamMicrobenchHarness`) once a kernel lowers.

## Deliverables status (this increment)
1 merged+hw provenance ✓ · 2 device-primitive report ✓ (this doc) · 3–6 **blocked — kernels don't lower**
· 7 analytic-Jacobian feasibility: **premature to decide, but the lowering blocker strengthens its case**
· 11 recommendation: **`GPU EXPLICIT PATH NOT YET VALIDATED`** (the path does not execute; the
analytic-Jacobian/solver decision cannot be made until the faithful path is at least lowerable + measured).
