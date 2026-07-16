# Calibrated full gliding-loop GPU integration (Part A) — A1 STRUCTURAL BLOCKER (bail-out-and-report)

**Status: `CALIBRATED GPU BLOCKED` (full device-resident gliding loop).** `MotorGpuParams.DEVICE_VALIDATED`
stays `false` (not flipped). No physics / param / chemistry / binding / RNG change was made; this is a
survey + discovery report per the task's explicit bail-out-and-report instruction. No code committed to
the physics path.

## Runner disclosure (UP FRONT — the CPU-fallback-disclosure invariant)

**The calibrated two-body gliding assay CANNOT run device-resident as it exists today, and no partial
device path is worth building.** It is **100% host-sequential**: the entire `TwoBodyConverterMotor`
harness (8354 lines) contains **zero** `TaskGraph` / `TornadoExecutionPlan` / `@Parallel` /
`persistOnDevice` / `withGridScheduler` (verified: `grep -cE '…' = 0`). `runMotorGliding` prints
"CANONICAL GLIDING ASSAY (CPU-only)" and loops `stepGlideSup(G,t,…)` sequentially over host state.
Expected device steps/s: **N/A — there is no device path to run.** CPU baseline (this branch, calibrated
`-glide`, 200/µm², 2×1 µm mat): ≈73 s wall per simulated second.

The blocker is NOT "a kernel fails to lower" (that was the calibratedStep story). It is more fundamental:
**four of the nine per-step stages, plus the geom/head-placement updates, are not TaskGraph-compatible
kernels at all — they are sequential host scalar code over `Glide2D`'s host `double[]`/`double[][]`
state, with heap allocation and multi-dim arrays.** There is nothing to "wire"; those stages would have
to be *written* as SoA `@Parallel` kernels first, which is a physics-touching SoA port (out of this
task's "NO physics/binding change" scope), and each new kernel carries the same lowering risks
calibratedStep hit (heap `double[]` returns, `double[][]`, `Math.abs`/`Math.max` reinterpret, inline
node-count caps, data-dependent search loops).

## Per-step kernel-readiness map of `stepGlideSup` (the calibrated gliding step, lines 5835–5863)

| # | Stage | Implementation | Device-ready? |
|---|---|---|---|
| 1 | cull (`unionActive`, `siteSegDist2`) | host scalar over host `int[] cellStart/cellMotor`, `boolean[] active` (4721) | **NO** (host arrays, host loop) |
| 2 | bind geometry+gate (`geom2D`→`nearestSeg2D`→`gate2D`) | host scalar; heap `return new double[]{…}`, `double[][]`, `add/scl/sub/rotConv/crs/dot` allocate; `nearestSeg2D` is a data-dependent per-segment search (4504/4520/4531) | **NO** |
| 3 | stochastic binding | host `for(m)` 8-gate acceptance + `mot.boundSeg.set` (5838–5843) | **NO** |
| 4 | nucleotide chemistry | `NucleotideCycleSystem.cycleLymnTaylor` — a real `@Parallel` SoA kernel over `MotorStore` | YES |
| 5 | F8 + converter mechanics | `geom2D`+`placeHead2D` **host scalar** (4504/4512), then `CrossBridgeSystem.bondForces` (kernel) | **PARTIAL** (geom/placeHead host) |
| 6 | force→actin CSR gather | `csrHistogram/Scan/Scatter/segGather` — real kernels (serial single-thread CSR scans) | YES (scaling caveat) |
| 7 | calibrated Step-7 pivot solve | **`supSolveM` — host scalar** (`double[][] K`, `new double[5][5]`, `solveLin`, heap `crs/sub/add/scl`), operating on `G.A[m]`/`G.phi[m]`; RNG salts `0x5F1..0x5F5+m*7919L`. **This is NOT the SoA `calibratedStep`** (salts `0x4F1..0x4F5`) — that is a separate single-motor reimplementation with no mat wiring. (5xxx) | **NO** (host; calibratedStep is unwired) |
| 8 | chain + z-confine + Brownian + integrate + derive | `zeroAccumulators`/`chainForces`/`brownianForce`/`integrate`/`orthogonalizeY`/`derive` are kernels; the **z-confine is a host `for(s)` loop** (5855) | MOSTLY (z-confine host loop) |
| 9 | online measurement | host reductions in `measureSupMat` (5867–5896) | host (by design) |

## Root cause — the mat's two-body state is host-resident

`Glide2D` (lines 4425–4446) stores the per-motor two-body pivot/converter state as **host** arrays:
`double[] phi, psi, thetaS, psiActin`; `double[][] A, C_, xH_, xF8_`; `boolean[] noBind, active`;
plus host `int[] cellStart, cellMotor` for the cull grid. This state is **never** in device SoA. The
head pose reaches the device only via `placeHead2D` (host scalar) writing into `MotorStore.body` right
before `bondForces`. So even the device kernels that DO exist (stages 4/5-bond/6/8) are fed by, and feed
back into, host-side two-body state each step. A device-resident loop is impossible until that state is
SoA and stages 1/2/3/7 + geom2D/placeHead2D exist as kernels producing/consuming it.

A "partial" graph (only stages 4/5-bond/6/8 on device, 1/2/3/7 on host) is **not viable**: it would
require a full per-step host↔device round-trip of the motor+filament SoA (violating the invariant "Host
reads ONLY the small per-step reduction scalars … do NOT transfer full per-motor state each step"), and
would be transfer-bound and *slower than the CPU*. It is not a device-resident path and was not built.

## What a real A1 requires (scoped follow-up — a separate large increment)

1. A device-resident **mat-SoA store** for the two-body state: `phi[N], psi[N], thetaS[N], psiActin[N]`
   (FloatArray), `A[3N]/C[3N]/xH[3N]/xF8[3N]` (planar), `active[N]`, per-motor `bondData[N·13]`, the cull
   grid CSR — mirroring `MotorGpuParams` packing but mat-wide and device-persistent across steps.
2. Five new `@Parallel` SoA kernels (none exist today), each bit-faithful to the host code and each to be
   device-gated (lowering + CPU≡GPU) like calibratedStep was:
   - `matCull` (unionActive over a device CSR grid) — host `int[]`→`IntArray`, scalarized.
   - `matGeom` (geom2D) + `matPlaceHead` (placeHead2D) — scalarize the heap `double[]`/`rotConv`.
   - `matNearestGate` (nearestSeg2D + gate2D) — remove heap returns + `double[][]`; the data-dependent
     per-segment nearest search must be bounded/scalarized (lowering risk: dynamic loop, `Math.abs`).
   - `matBind` (the 8-gate stochastic acceptance) — scalarize, preserve gate ordering + wang-hash keys.
   - `matStep7` (the mat calibratedStep) — reuse the scalarized/lowerable `calibratedStep` body over the
     mat-SoA pivot state, preserving the mat RNG salts `0x5F1..0x5F5+m·7919L` (NOT `0x4F1..0x4F5`).
3. Chain stages 4/5/6/8 into the graph via `persistOnDevice`/`consumeFromDevice`; move the z-confine host
   loop into a kernel (or fold into integrate); keep measurement host-side on cadence-sampled reductions.

Each step 2 kernel is a bit-faithful SoA port of two-body **binding/gate/solve physics** — i.e. it *is*
touching the binding/solve implementation, which this task forbids ("NO physics/param/chemistry/binding
change"; "do not force it by changing physics"). It should be scoped and approved as its own increment,
kernel-by-kernel with device gates, exactly as calibratedStep was.

## A2–A6: not reached (blocked on A1)

No device loop exists to add diagnostics to (A2), gate CPU/GPU trajectories against (A3), run ensembles
(A4), dt/cull controls (A5), or benchmark throughput (A6). The one device-validated two-body kernel is
the single-motor **`calibratedStep`** (prior task: lowers + runs 10/10 on the RTX 5070, N=1, all T3-PASS,
CPU-bit-identical) — but it is not wired into the mat and cannot be until the mat-SoA state + stages
1/2/3 exist.

## A7 — promotion decision

**`CALIBRATED GPU BLOCKED`** — full device-resident gliding loop is not integrable without a
physics-touching SoA port of stages 1/2/3/7 + geom2D/placeHead2D (which today are host-sequential over
`Glide2D` host `double[]`/`double[][]`; the two-body harness has zero device execution). Exact blocker:
the two-body gliding loop is not composed of TaskGraph kernels — only stages 4/5-bond/6/8 have device
forms, and the per-motor two-body state is host-resident. `DEVICE_VALIDATED` remains `false`.

**Recommendation:** scope the mat-SoA store + the five kernels above as a dedicated increment (each
device-gated for lowering + CPU≡GPU like calibratedStep). Do not promote calibrated `-gpu` gliding; the
existing clear refusal (`MotorGpuParams.refuseGpu`) is correct.
