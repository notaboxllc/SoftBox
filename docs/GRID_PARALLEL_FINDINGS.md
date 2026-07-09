# Parallel GPU grid build + the REAL dense-gliding bottleneck — FINDINGS

**Date:** 2026-06-22. **Branch:** `grid-parallel-build`. **Status:** DONE — v2's ECS/GPU dense-gliding
path now **beats BoA's GPU at every scale** (was 2–4× slower).

## TL;DR

The task ("retire the single-threaded grid HISTOGRAM + SCATTER bottleneck") rested on the 2026-06-18
diagnosis that `SpatialGrid.gridHistogram`/`gridScatter` dominated the dense-gliding step. **The
profiler refuted that diagnosis** (the `-brute` probe that would have caught it was broken at the time,
so the diagnosis was an *unconfirmed inference*). Three single-threaded `@Parallel(gid<1)` passes were
hiding in the pipeline; the grid hist/scatter were the *smallest* of them:

| single-threaded pass | share of kernel time @1× (before) | what it does |
|---|---|---|
| `BindingDetectionSystem.invertCandidates` | **94 %** (195 ms/step) | body-keyed candidates → motor-keyed lists |
| `CrossBridgeSystem.csrHistogram`+`csrScatter` | ~4 % (8.3 ms, then 80 % once invert was fixed) | segment→bound-motors CSR-inverse |
| `SpatialGrid.gridHistogram`+`gridScatter` | **<0.2 %** (the named target) | grid counting-sort build |

All three are the SAME pattern (a serial counting-sort / CSR-inversion, written race-free for inc-3's
N=512, never re-parallelized). All three were parallelized with the SAME atomic-free, no-KernelContext,
CPU≡GPU-bit-identical body-chunked counting sort. The decisive one was `invertCandidates`, replaced by a
**fused per-motor grid query** (`gridReachable`, faithful to BoA's own `bindKernel`).

**Result (RTX 5070, faithful dense-gliding sweep, warmup-windowed ms/step):**

| scale | motors | v2 BEFORE (ms) | **v2 AFTER (ms)** | BoA GPU (ms) | **v2 vs BoA** | speedup vs before | avgBound (==serial) |
|---|---|---|---|---|---|---|---|
| 0.5× | 49 000 | 113.6 | **5.80** | 53.4 | **9.2× faster** | 19.6× | 805 |
| 1× | 98 000 | 259.3 | **7.40** | 89.7 | **12.1× faster** | 35.0× | 1689 |
| 2× | 196 000 | 539.1 | **11.91** | 168.7 | **14.2× faster** | 45.3× | 3327 |
| 4× | 392 000 | 1171.0 | **27.92** | 343.7 | **12.3× faster** | 41.9× | 4659 |
| 8× | 784 000 | 2515.6 | **53.99** | 659.1 | **12.2× faster** | 46.6× | 6663 |

`avgBound` is **identical to the serial baseline at every scale** (805/1689/3327/4659/6663) ⇒ the parallel
build is a **bit-identical no-regression** — same reachable set, same binding, same trajectory, just built
in parallel. (1× = 6.15 ms in an isolated run, 7.40 ms inside the full sweep; both numbers reflect normal
run-to-run variation, well within the 12× margin over BoA.)

**Per-step scaling is now ~linear in motor count** (weak-scaling ratios per 2× motors: 1.28 / 1.61 / 2.34 /
1.93 — sub-linear at small scale where the GPU is under-utilised, ~2.0× = ideal-linear once saturated). The
earlier **super-linear ∝N^1.1 term is gone** — it was the single-threaded passes (∝N on one GPU core), now
all parallel.

## What was built (all atomic-free, no KernelContext, one impl on both runners)

### 1. Parallel grid build — `SpatialGrid.gridChunk{Zero,Histogram,Reduce,Scatter}`
The named task. Bodies are partitioned into `numBodyChunks` contiguous chunks; each chunk gets a PRIVATE
per-cell counter row in `chunkCellCount[numBodyChunks × totalCells]` (a segmented histogram with no shared
writes — BoA's histogram uses `atomicAdd`, forbidden here because it breaks the `-cpu` runner). A per-cell
column reduce gives the cell totals AND each chunk's exclusive base-within-cell; the existing two-level scan
(`gridScanLocal`/`gridScanChunks`/`gridScanAdd`, already parallel, REUSED VERBATIM) turns totals into CSR
offsets; a counting-sort scatter writes each body at `offset[cell] + chunkBase[cell]++` (a private
per-(chunk,cell) cursor). Bodies visited in index order within each chunk + chunks in index order ⇒
within-cell order is the SAME body-index order the serial scatter produced ⇒ the CSR is **bit-identical** to
the serial build, not merely multiset-equal. `bodyChunkSize ≈ max(√bodyCount, bodyCount·totalCells/budget)`
balances histogram/scatter (∝ chunkSize) vs reduce (∝ numChunks) while capping the matrix to 256 MB.

### 2. The REAL fix — `BindingDetectionSystem.gridReachable` (fused per-motor grid query)
Replaces `broadPhase` + `invertCandidates` + `computeReachable` for the binding path. Parallel over MOTORS
(98k–784k threads — great occupancy): each motor computes its center cell (from its head, the SAME
center+binning the body view publishes), scans the 27-cell neighborhood of the device-resident grid, and
applies the EXACT reach predicate to every SEGMENT body found, writing its own `reachSeg`/`reachCount` slice
— **no inversion, no atomics**. Faithful to v1 `GPUMotorBinding.bindKernel` (the fused broad+narrow per-motor
grid walk). This retired the single-threaded `invertCandidates` (incl. a 98k-element serial zeroing loop):
**205 → 14 ms/step at 1× (15×), the decisive change.** Reachable set IDENTICAL to the old path (same 27
cells, same predicate) — gated grid==brute.

The reach predicate is **inlined** (no helper call inside the deep loop nest) — a method call with
early-returns nested 4-deep triggers TornadoVM's `failed guarantee: invalid variable` PTX lowering bug
(the same error the 2026-06-18 `-brute` probe hit; `broadPhase` inlines for this reason). A second
instance of that error came from `transferToHost` of a buffer (`candCount`) that no task writes in the
fused path — pruned from the transfer.

### 3. Parallel CSR-inverse — `CrossBridgeSystem.csrChunk{Zero,Histogram,Reduce,Scatter}`
Once `invert` was fused away, the single-threaded `csrHistogram`+`csrScatter` (segment→bound-motors gather)
became 80 % of the now-tiny step. Same body-chunked counting sort, keyed by `boundSeg` over motors (key
space = nSeg). **ADDITIVE** — the serial `csr*` are byte-unchanged for the ~10 other harnesses that reuse
them VERBATIM; only DenseGliding wires the parallel variants. Produces a CSR bit-identical to serial (motors
in index order within each segment) ⇒ `segGather` unaffected. **14 → 6.15 ms/step at 1× (2.3× more).**

## Validation

- **GRID==BRUTE gate (`-gridcheck`)**: `gridReachable` reachable set == `bruteReachable` (every
  motor×segment), bit-exact, pre-integrate, on the dense scene geometry. PASS.
- **BroadPhaseHarness (`run_grid.sh`, N=512…4096)**: parallel grid build == brute on GPU AND CPU; CSR
  bit-identical CPU↔GPU; **parallel build == serial build bit-identical** (offsets AND within-cell order,
  evolved cluster). PASS.
- **No regression (bit-identical trajectory at scale)**: `avgBound` is identical to the serial baseline at
  every scale (the A/B `-oldbind`/`-serialcsr`/`-serialgrid` toggles reproduce the old kernels; `-oldbind`
  @1× = 205 ms / avgBound 1689 == fused 14 ms / avgBound 1689). Any CSR difference would diverge the chaotic
  trajectory and move `avgBound`; it doesn't.
- **Other harnesses**: structurally unaffected — they call only the UNCHANGED serial kernels; the parallel
  variants are new methods wired only into DenseGliding (+ BroadPhase's grid build).

## Verdict

**Yes — the ECS/device-resident architecture now demonstrably beats v1's GPU path** (9.2–14.2× faster across
the faithful dense-gliding sweep, vs 2–4× slower before — a 20–47× swing in v2's own per-step time). The win came NOT from the named target (grid
hist/scatter were <0.2 %) but from finding and fixing the actual single-threaded passes — chiefly
`invertCandidates`, replaced by the per-motor fused grid query. The grid build, csr-inverse, and binding
consumer are now all parallel, race-free, CPU≡GPU bit-identical, and grid==brute faithful. The named
single-threaded grid build was retired too (validated bit-identical) even though it was never the
bottleneck.

## Flags (DenseGlidingHarness A/B / diagnostics)
- `-oldbind` — old `broadPhase`+`invertCandidates`+`computeReachable` (single-threaded invert).
- `-serialcsr` — old single-threaded `csrHistogram`+`csrScatter`.
- `-serialgrid` — old single-threaded `gridHistogram`+`gridScatter`.
- `-prof` (with `-Dtornado.profiler=True`) — per-task kernel-time breakdown.
</content>
</invoke>
