# Head chain on the GPU — port notes (2026-10-26)

Working copy `~/Code/SoftBox-dock-gpu` (copied from `~/Code/SoftBox-dock` so the running CPU campaign's class files are
untouched). Nothing committed. Merge back into SoftBox-dock after `CHAIN_GLIDE` (CPU) finishes.

## What changed

- `HeadChainSystem` rewritten lowering-safe, one implementation for both runners:
  - no in-kernel arrays: the 5x5 mobility, its Cholesky factor, the noise vector, the 8-DOF Jacobians and the
    theta/beta gradients live in a 128-double scratch block appended to each motor's system in `hsys`
    (`HeadChainSystem.sysStride(M)`);
  - no array-returning helpers (`rotC`, `dockTq` return one component);
  - `@Parallel` motor loop in `solve` and `stericUndo` (now a kernel taking `counts`);
  - no `Math.atan2 / asin / IEEEremainder` (`angSC`, `betaOf`, `wrapPi`);
  - **double-precision `dsin / dcos / dlog`** (fdlibm kernel polynomials, Cody-Waite reduction) and the trig-free
    fdlibm rational `asin / acos`; every trig call in the chain kernels, including the copied S2 blocks
    (`dacosH`, `a1H`, `a2H`, `brownH`), uses them. Accuracy vs `java.lang.Math`: sin 2.2e-16, cos 1.1e-16,
    log 2.6e-16 (relative), acos 4.4e-16, asin 2.2e-16.
- `buildGlidingGraph` wires the chain: `hcGuard` (first task), `beamGeom = HeadChainSystem.geom`, `hcSync`,
  `siteCouple = HeadChainSystem.siteCouple` (headDock and swivel skipped), `s2solve = HeadChainSystem.solve`,
  steric redo `hcUndo` + `s2solve2`. `hc / hp / hsys` are device-resident; `hc` is read back on demand (lean) or
  every step (validation mode).
- `SiteNormalLongGlideHarness`: the "-head-chain is CPU only" refusal removed; new `-chain-equiv <steps>`
  (CPU vs device, same scene, production cull on both, per-step comparison) with `CHAIN_PROBE=1|nobrown`
  (isolated one-task graphs for solve / geom+sync on the real scene state) and `CHAIN_CPUCTL=<eps>` (CPU vs CPU with a
  perturbed head frame: the intrinsic divergence yardstick).
- `TornadoWriteFirstProbe`: reproducer for the upload trap below.

## Two TornadoVM 4.0.1-dev traps (both silent)

1. **A buffer whose FIRST-touching task only writes it is never uploaded.** `TornadoGraphBuilder` turns a
   `WRITE_ONLY` argument into an `AllocateNode` (no copy-in), regardless of `transferToDevice`. Every element that
   task does not write is zero on the device. Reproducer: `TornadoWriteFirstProbe` (variant 0: y = 0, expected 7).
   In the chain graph `hcSync` wrote `q` and `frame` first, so `thetaS` (q row 2) and `eup / g4E / g4Tan`
   (frame rows 6..14) were zero on the device. Fix: `HeadChainSystem.uploadGuard`, a first task that reads one element
   of each host-initialised buffer.
   **The existing production graph has the same defect:** with `-devicecull`, `MatSoaSlice.matCullTag` is the first
   task to touch `restC` and only writes row 8, so restC rows 0..2 (the detached head target, straight rest
   (1,0,0)) are zero on the device and `matS2SolveStepTilt` sees `c1 = c2 = c3 = 0`: no detached head-orientation
   restoring torque on GPU site-normal runs with `-devicecull` (e.g. STROKE_B). Not fixed in the legacy graph
   (changing it re-baselines those runs; jba's call). It may also explain why the site-normal bound-branch CPU/GPU
   gate never went green (not checked).
2. **`Math.sin / cos / log / exp / tan / tanh` on doubles run in f32 on PTX** (`PTXFPUnaryIntrinsicNode
   .shouldConvertInput` -> `sin.approx.f32`). The chain has a zero-stiffness, high-mobility mode (the head's roll
   about the lever), which turned ~1e-7 trig errors into O(0.1) CPU/GPU differences in one step. Fixed by the
   polynomial functions above. Every other device kernel in the project still uses the f32 trig, including the
   Box-Muller noise in `brownTorqueD`.

## Validation

| test | result |
|---|---|
| isolated `solve`, real scene state (4800 motors, d1600 3x1 um, eta 0.1, dt 1.25e-6), Brownian off / on | max |d nodes| 1.4e-12 / 1.6e-13 um, max |d head frame| 2.8e-10 / 4.5e-11, 0 / 4800 motors above 1e-9 |
| isolated `geom + sync` | exact (0.0) on every written row |
| full step, CPU runner vs device graph, same scene, production cull | step 0: head frame 5e-6, nodes 5e-7 um, filament 3e-10 um; decisions (boundSeg, nucleotide) identical through step 4640 including ~1600 bound motor-steps (capture, stroke, release); filament within 1e-6 um through step 3600; then chaotic decorrelation |
| CPU vs CPU, head frame perturbed by 1e-12 (control) | step 0: 4e-6 already; step 200: head frame 3e-4 (CPU/GPU 4e-8); step 1400: head frame 0.46, filament 5.3e-4 um (CPU/GPU 3e-7, 6e-8). The CPU/GPU residual is far below the model's own sensitivity |
| campaign scene (eta 0.01, dt 1.25e-7, d400 6x4 um, 9600 motors), device-resident | 7 ms/step (CPU single worker 75-77 ms/step), 0 invalid / 0 solver failures over 20000 steps; step-2000 observables equal the unmodified CPU code's to printed precision |

The step-0 residual (~1e-6 on a few motors) sits on motors near a steric contact switch, where a 1e-12 change already
moves the result by 4e-6 (the control). Upstream kernels (gates, head axis, sterics) still use f32 trig on the device.

Chaotic many-body standard (CLAUDE.md): the device path is judged by aggregate statistics against the CPU campaign:
`CHAIN_GLIDE_GPU` (3 lawns, same flags) vs `CHAIN_GLIDE` (CPU).

## Open

- Resume/checkpoint does not save `hc` (chain runs cannot `-resume`, either runner).
- The legacy `restC` upload defect above.
- The free head roll diffuses ~0.27 rad per step at dt = 1.25e-7, eta = 0.01 (D_roll ~ 3e5 rad^2/s from the head sphere's
  drag): the linearly-implicit step is exact for free diffusion but coarse for contacts (sterics, floor). Not changed.
- Debug scaffolding in `chainKernelProbe` (single-mask loop) can be trimmed.
