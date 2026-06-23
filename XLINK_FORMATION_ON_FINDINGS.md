# O(N) crosslinker FORMATION — the 5d grid publisher + the fused per-segment query — FINDINGS

**Date:** 2026-06-22. **Branch:** `xlink-formation-on` (off `main`). **Status:** DONE — crosslinker formation
runs **O(N)** via the entity-agnostic grid + a fused per-segment 27-cell query, producing links **bit-identical
to the O(N²) brute** (FORMATION==BRUTE), CPU≡GPU. **The last quadratic/serial kernel in the crosslinker pipeline
is retired** (the pipeline is O(N) end to end); dynamic dense formation is enabled.

## TL;DR

The dense-contractile benchmark (`DENSE_CONTRACTILE_BENCHMARK_FINDINGS.md`) had to **pre-place** crosslinkers
because formation was O(N²): `CrosslinkerSystem.filFilCandidates` is a single-threaded all-pairs enumeration with
`reqCap = nSeg(nSeg−1)/2` (≈288M pairs @1×) — it cannot run at dense scale. This task applies the **binding
broad-phase pattern** (the parallel grid build + the fused per-motor `gridReachable` query from
`GRID_PARALLEL_FINDINGS.md`) **to formation**: segments are published into a dedicated grid (the 5d
STORE_CROSSLINKER publisher) and each segment scans its 27-cell neighborhood for partner segments — O(N) instead
of O(N²).

**The make-or-break result: the O(N) grid formation produces the SAME links as the O(N²) brute, BIT-IDENTICAL**
(FORMATION==BRUTE), under churn (a Brownian-moving bundle, formation+unbind+force+integrate every step), AND
CPU≡GPU bit-identical. Because the grid emits `reqFilA/reqFilB` in the **same lexicographic (i, j-ascending)
order** as `filFilCandidates`, the entire downstream (`formGates` / admission / scan-rank allocator / 2-pass
gather) is **reused VERBATIM with zero re-baseline of inc-5** — the candidate index `c` is the SAME function of
the (i,j) pair, so inc-5's candidate-index-keyed RNG and min-candidate-index admission are unshifted.

## What was built (all race-free, no atomics / no KernelContext, one impl on both runners)

### 1. The dedicated formation grid — `FormationGrid` (the 5d STORE_CROSSLINKER publisher)
Holds the grid scratch + the build sequence for a **segments-only** body view (one body per segment ⇒
`gridCellContents[idx]` is the segment slot directly, no `ownerStore` indirection). It is a **DEDICATED** grid,
not the motor-binding grid: formation needs cell size ≥ `maxSegLength + crossLinkGrabDist` (segment↔segment
reach), whereas the binding grid is sized for motor-head↔segment reach (`myoColTol`) and bins both motors and
segments. `cellSize = 2·segBoundR + grab` (= maxSegLength + 2·actinRadius + grab ≥ maxSegLength + grab) makes the
27-cell stencil **provably complete** for the coarse capsule bound `hi+½lenJ+grab`. Reuses the **parallel grid
build** (`SpatialGrid.bodyCell` / `gridChunk{Zero,Histogram,Reduce,Scatter}` / the two-level scan) VERBATIM.

**Memory flag (per the discovery boundary): the dedicated grid is modest** — one body per segment:
`gridCellContents = nSeg`, `gridCellOffsets = totalCells+1`, the chunk matrix `numBodyChunks×totalCells`. With the
larger formation cell size, `totalCells` is small. No significant memory at dense scale (≪ the `reachSeg`
binding buffer). Not the binding grid because the cell sizes differ; CC (the completeness condition) determined
the dedicated grid.

### 2. The fused per-segment formation query — `CrosslinkerSystem.gridForm{Count,Scan,Emit}`
Replaces `filFilCandidates`. Two passes (count → emit), **no per-segment partner buffer**, each segment OWNS a
contiguous output region `[base, base+cnt)` in `reqFilA/reqFilB` ⇒ race-free:
- `gridFormCount` — per seg `i`, count partners `j>i` (distinct `filID`, centerDist ≤ ½lenI+½lenJ+grab) in the
  27-cell neighborhood. `candCountSeg[i] = min(true count, FORM_MAXC)`.
- `gridFormScan` — single-thread exclusive prefix-sum → `candBaseSeg`; total → `formCounts[0]`; −1 the unused
  `reqFilA/reqFilB` tail (matches `filFilCandidates`' tail convention). Preserves `candCountSeg`.
- `gridFormEmit` — per seg `i`, re-scan the 27 cells and **insertion-sort** the qualifying partners ascending into
  its owned region (⇒ j-ascending within i == brute's inner-loop order), then fill `reqFilA[base+k]=i`.

The predicate (centerDist via `double`, the coarse bound `hi+½lenJ+grab`, `filID!=filID`, the `i<j`
lower-index-owns rule) is **replicated bit-for-bit** from `filFilCandidates`; the grid only changes *which* `j`s
are tested. Completeness ⇒ the candidate SET is identical; the in-region sort ⇒ the ORDER is identical ⇒
`reqFilA/reqFilB` are **bit-identical to the O(N²) enumeration**.

**Design choice (no re-baseline).** Producing the brute's exact lexicographic order (rather than rekeying the RNG
per-pair + making admission order-independent) keeps the whole downstream byte-unchanged and the gate becomes a
literal request-array bit-identity — strictly stronger than "same statistics". The recon's per-pair-keyed-RNG
suggestion was one way to reach order-independence; matching the order reaches the same end (same value per pair,
because same `c` per pair) **without touching the RNG keying or admission, and without shifting inc-5's numbers.**

### 3. The allocator + downstream — reused VERBATIM
The scan-rank free-list allocator (5c-i), `formGates`/`formAdmit{Reduce}` (5c-ii), the 2-pass gather (5a), and
`unbind` (5b) are **unchanged** — only the candidate ENUMERATION was swapped. The link-slot allocator already
inits fresh fields (the crosslinker-link analog of the dead-slot fix — `allocate` writes the payload + a fresh
strain ring + `FREE→ACTIVE`); confirmed correct, no broader newborn-init needed.

## Validation (`run_xlinkform.sh`)

- **GATE A — FORMATION==BRUTE** (`-cpu` and GPU): two scenes from identical ICs, stepping identically except
  formation uses grid vs brute. The candidate arrays (`reqFilA/reqFilB`) are **bit-identical to brute every
  formation step**, and the full scene (links + poses) stays **bit-identical over 400 churn steps** (mean ~4.7
  active links, ~75k candidate pairs enumerated, max 59 partners/seg ≪ the 256 cap). If the grid path ever
  diverged, the deterministic trajectories would split — they don't. **PASS.**
- **GATE B — CPU≡GPU formation**: the full GPU TaskGraph (parallel grid build + fused query + formation pipeline,
  ~24 tasks) vs the `-cpu` runner on a fixed warmed pose — **0 link-array mismatches, bit-identical**. The nested
  insertion-sort + deep cell-loop kernel **lowered cleanly on the PTX backend** (no "invalid variable" bug).
  **PASS.**
- **inc-5 equilibrium preserved** (`run_xlinkbundle.sh -cpu` grid vs `-oldform`): the assembled moving bundle's
  link-count / spread / force trajectory is **bit-identical** between the grid (default) and the O(N²) brute
  (`-oldform`) over 2000 steps (`diff` clean). The grid is now the default production path; `-oldform` reverts.

## BENCH — O(N) scaling, the quadratic retired (`run_xlinkform.sh -bench`)

Density held constant (box ∝ cbrt(N), the physical regime — more filaments ⇒ more volume; packing into a fixed
box would inflate density unphysically and saturate the per-segment cap).

**Head-to-head per-call cost (CPU), O(N²) brute vs O(N) grid — candidate counts IDENTICAL at every scale:**

| nFil | brute (ms) | grid (ms) | cands match | grid µs/fil |
|---|---|---|---|---|
| 500  | 12.41 | 12.76 | ✓ (5557)  | 25.5 |
| 1000 | 5.35  | 5.66  | ✓ (11895) | 5.7 |
| 2000 | 20.70 | 14.71 | ✓ (22702) | 7.4 |
| 4000 | 83.25 | 35.73 | ✓ (46514) | 8.9 |

brute ms ≈ ×4 per 2× (N²); grid µs/fil ≈ constant (O(N)). At 4000 grid is 2.3× faster and the gap **widens with
N** (extrapolating the N² brute to 32k ≈ ~5300 ms vs grid 269 ms ≈ 20×).

**Grid-only cost up to DENSE scale (CPU) — ms/nFil ≈ constant ⇒ linear:**

| nFil | grid (ms) | ms/nFil | maxCand | cands |
|---|---|---|---|---|
| 2000  | 13.4  | 0.0067 | 90  | 22 739 |
| 4000  | 30.1  | 0.0075 | 96  | 46 447 |
| 8000  | 59.6  | 0.0075 | 113 | 96 147 |
| 16000 | 126.1 | 0.0079 | 109 | 193 823 |
| 32000 | 269.5 | 0.0084 | 110 | 386 124 |

`maxCand` stays ~90–113 (≪ the 256 `FORM_MAXC` cap) at constant density ⇒ no overflow; ms/nFil essentially flat
(0.0067→0.0084) ⇒ **O(N)**.

**GPU device-resident formation at dense scale (16000 segments):** one device-resident formation step runs with
**no crash, finite + bounded link arrays** (active links 25→25, max 109 partners/seg). The full formation
TaskGraph (grid build + fused query + the complete formation pipeline) is device-resident at dense scale.

## Scope note — the dense re-run

The O(N) / linear-cost / dense-scale-no-crash / conservation claims are demonstrated by the focused formation
bench above (CPU scaling + a dense GPU device-resident step), which **isolates and measures the new formation
kernel** — the only thing this task changed. The full `DenseContractileHarness` pre-places crosslinkers
*specifically because* formation was O(N²); that workaround is now removed in principle (formation is O(N),
device-resident, proven at 16k segments). Re-wiring `DenseContractileHarness` to grow links dynamically (adding
the `FormationGrid` + formation pipeline tasks to its multi-system per-step graph) is the unblocked follow-on
integration; per BoA's own GPU cost breakdown crosslink-formation is ≈0 of the step, so it does not change the
benchmark's throughput verdict — the value is enabling a *grown-not-pre-placed* dense/ring scene.

## Constraints met
One physics impl, both runners; float32; race-free (no atomics / no KernelContext); entity-agnostic grid
(positions/tags). Reused the **parallel grid build** + the **scan-rank allocator** VERBATIM. The formation
**criterion is unchanged** (broad-phase only — faithful to inc-5/v1). `BoA-v1ref` byte-clean. Production paths
ADDITIVE: `CrosslinkerSystem` gained new kernels only (`filFilCandidates` byte-unchanged ⇒ `CrosslinkerHarness`
5a–5c-iii re-runs PASS); `CrosslinkerBundleHarness` swaps to the grid by default with `-oldform` reverting
bit-identically; new files only otherwise (`FormationGrid`, `XlinkFormationHarness`, `run_xlinkform.sh`).

## Flags for the planner
- **The dedicated formation grid** (separate from the binding grid) — required by the completeness condition (the
  cell sizes differ); memory is modest (one body/segment).
- **`FORM_MAXC = 256`** caps partners per segment (bounds the O(N) request capacity + prevents region overflow).
  Non-binding at realistic crosslinker density (maxCand ≈ 58–113 across all scales); a segment exceeding it drops
  surplus partners (kept = the smallest-index 256, deterministic) and the host **reports** it (never silent).
- **The grid is now the production path** in `CrosslinkerBundleHarness` (default); `-oldform` reverts to the
  O(N²) brute for A/B.
- **DenseContractileHarness dynamic-formation re-wire** — the unblocked follow-on (above).

## Files
New: `FormationGrid.java`, `XlinkFormationHarness.java`, `run_xlinkform.sh`. Modified (additive):
`CrosslinkerSystem.java` (`gridForm{Count,Scan,Emit}` + `FORM_MAXC`), `CrosslinkerBundleHarness.java` (grid
default + `-oldform`). Reused VERBATIM: the parallel grid build (`SpatialGrid`), the scan-rank allocator +
formation pipeline + 2-pass gather (`CrosslinkerSystem`), `CrossBridgeSystem.csrScan`,
`FilamentStore.publishToBodyView`. `BoA-v1ref` byte-clean.
