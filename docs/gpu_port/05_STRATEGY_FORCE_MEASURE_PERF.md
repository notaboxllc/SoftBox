# Force-accumulation, measurement & performance strategy

## 1. Force-accumulation strategy (task: evaluate ≥2 approaches)

The CPU path ALREADY uses staged-output + segmented gather (Phase-1 §5). Two GPU options to compare:

**(A) Staged per-motor output + CSR-inverse segmented gather (the incumbent, recommended default).**
`bondForces` writes each motor's seg-side reaction into its private `bondData` row (no contention);
`csrHistogramChunked/csrScanChunked/csrScatterChunked` build the seg→motors map; `segGather` (`@Parallel`
over the 12 segments) sums each segment's own motors. **Atomics-free, deterministic, summation order fixed
by motor index ⇒ CPU≡GPU bit-identical** (the standing inc-5/6 result). Cost: 4 small kernels; the CSR is
O(N)+O(nSeg); with only 12 segments the final gather is 12 threads (tiny). The chunked CSR removes the
single-thread-scan bottleneck.

**(B) Direct atomic accumulation into `f.forceSum/torqueSum`.** Each bound-motor thread atomically adds
its reaction to its segment's 6 accumulators. Fewer kernels (no CSR build). BUT: atomic float add is
**non-deterministic in summation order ⇒ not bit-reproducible** (fails T2; violates the CPU-arbiter
determinism the gliding bistability needs), TornadoVM atomic support on the PTX backend is limited, and
with only 12 segments and ~400 bound motors the contention is high (many motors per segment). 

**Verdict:** default to **(A)** — it is deterministic, already validated, and the 12-segment gather is
trivially cheap. Benchmark **(B)** only as a scaling reference (correctness via T2-relaxed tolerance, not
bit-identity) and only if a future many-filament scene makes the CSR build dominate. Document any order
sensitivity. For the single-filament gliding assay, (A) wins on every axis (correctness, determinism,
perf) — the density-scaling crossover that would favor (B) does not occur here (nSeg is fixed at 12).

## 2. Measurement strategy (task: online reductions, no full per-motor export)

Never export full per-motor state each step. Implement device-side online reductions (or pull a handful
of per-step scalars). Reductions to provide (all derivable from the SoA arrays the motor kernel touches):
`boundCount`, `loadBearingCount` (via `matTaut`), `propulsiveCount`/`draggingCount` + `sumPropulsive`/
`sumDragging`/`netAxial` (project `bondData[6..8]·uSeg`), `atpCycles` (ADPPI→ADP transitions),
`continuity` (steps with boundCount==0), `handoff` (fresh-bind while bound), `activeCandidates`,
`solverFailures` (explicit contour-error > threshold). Implementation: a per-step reduction kernel writing
~12 scalars + the cadence-sampled 12-element filament COM to a small host buffer (the COM feeds `lsSlope`
velocity host-side at seed end). This is a few hundred bytes/step host traffic — negligible vs keeping the
N-motor SoA device-resident. **Detailed per-motor traces only in an opt-in diagnostic mode with sparse
(e.g. every-1000-step) sampling.**

## 3. Performance targets & wall-time estimates

Anchored on the sweep's measured CPU cost (§00 provenance): explicit **≈10,500 s wall / sim-s** (matx12
d1000, ~370 active motors); calibrated **≈540 s/sim-s**. Estimates below are **CPU baseline**, then GPU
at the milestone 10× and at a conservative 3–4× (the plausible floor if the faithful double-FD beam is
FP64-throttled on the RTX 5070 before the analytic-Jacobian rewrite lands).

| workload | CPU wall | GPU @10× | GPU @3–4× (FP64-limited beam) |
|----------|----------|----------|-------------------------------|
| one 0.15 s reduced explicit seed | ~1,600 s (27 min) | ~2.7 min | ~7–9 min |
| one 0.5 s explicit seed | ~5,300 s (88 min) | ~9 min | ~22–29 min |
| one 2.0 s explicit seed | ~21,000 s (5.8 h) | ~35 min | ~1.5–2 h |
| 8-seed reduced tier (matx4, 0.15 s ea) | ~2.4 h (driver-measured) | ~14 min | ~35–50 min |
| three-density explicit sweep ({200,700,1500}, matx4, 0.12 s, 3 seeds) | ~3 h (driver-measured) | ~18 min | ~45–60 min |

**Milestone:** ≥10× end-to-end for explicit-s2-l40; correctness first. The beam solve is ≥95 % of the
explicit per-motor cost, so the beam kernel's speedup ≈ the end-to-end speedup. Two independent speedup
sources: (i) parallelism across ~400 active (or 4000 total) motors; (ii) removing the ~750-eval nested-FD
tangent via an analytic Jacobian (a large constant-factor win AND it unlocks float32, which on a consumer
GPU is ~64× the FP64 rate). **The realistic path to ≥10× likely REQUIRES the analytic Jacobian** — the
faithful double-FD kernel may land at 3–5× because (a) FP64 throttling and (b) only ~400 active motors
under-fill the device. Levers to raise occupancy: step all N (skip device cull), batch seeds concurrently
(8 seeds × 400 motors = 3200 threads), and fuse the shared Steps 1–6 into few kernels (launch-bound at
this scale). Calibrated's win is almost entirely occupancy/launch-amortization (its 5×5 is already cheap):
expect a modest end-to-end speedup unless N-stepped + seed-batched.

**To REPORT once hardware frees:** kernel-level beam speedup; calibrated motor-step speedup; explicit
motor-step speedup; full gliding end-to-end speedup; scaling with active-motor count & density; occupancy;
memory bandwidth; transfer overhead; time outside kernels (the launch-bound fraction).
