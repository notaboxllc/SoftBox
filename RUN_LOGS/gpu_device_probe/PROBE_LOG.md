# GPU DEVICE PROBE — TwoBodyGpuKernels PTX lowering/execution (Phase-1)
# device mem (used MiB) at start: 252

## Positive control: vecAdd (trivial, no local arrays)
- lowered+ran: YES; result correct; runtime 255.2 ms (incl first-launch compile); mem 252→424 MiB

## fixedStep  (fixture fixed-anchor_bound_baseline, FLOAT32)
- LOWERS+RUNS: **YES** — runtime 104.6 ms (incl first-launch compile); vs CPU-oracle: cmp=14 maxAbsΔ=1.47e-08 maxRelΔ=2.77e+02 NaN=0 Inf=0 (worst=xF8z); mem 424→426 MiB

## calibratedStep  (fixture calibrated-s2-l40_bound_baseline, FLOAT32)
- LOWERS+RUNS: **NO**
  - exception: `org.graalvm.compiler.debug.GraalError`
  - message: should not reach here: Node implementing Lowerable not handled: 201|NewMultiArray

## explicitBeamStep  (fixture explicit-s2-l40_bound_baseline, DOUBLE)
- LOWERS+RUNS: **NO**
  - exception: `uk.ac.manchester.tornado.api.exceptions.TornadoInliningException`
  - message: Method Invoke#softbox.TwoBodyGpuKernels.s2NodeForcesK(double[][], int, double, double, double, double, double, double, double, double, double, double, double) cannot be inlined: node count (973) exceeds limit (600)

## explicitBeamStep  (fixture explicit-s2-l40_high_axial, DOUBLE)
- LOWERS+RUNS: **NO**
  - exception: `uk.ac.manchester.tornado.api.exceptions.TornadoInliningException`
  - message: Method Invoke#softbox.TwoBodyGpuKernels.s2NodeForcesK(double[][], int, double, double, double, double, double, double, double, double, double, double, double) cannot be inlined: node count (973) exceeds limit (600)

# device mem (used MiB) at end: 426
