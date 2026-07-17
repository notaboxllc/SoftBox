# Explicit vs Calibrated computational cost (Phase B) — matched no-cull free-binding throughput

Both models: genuine free binding (all unbound at t=0), NO-CULL (all N processed), production-residency GPU graph,
CPU-analytic runner, identical densities/dt (2.5e-6)/seed. Explicit Step-10 = matS2SolveStep (14-DOF beam);
calibrated Step-10 = matStep7 (analytic 5-DOF movable pivot). Shared stages (bond/CSR/filament/reduce) identical.

## B1. Primary comparison table
| N | density | cal CPU ms/step | exp CPU ms/step | exp/cal CPU | cal GPU ms/step | exp GPU ms/step | exp/cal GPU | cal meanBound | exp meanBound |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 600 | 200 | 0.676 | 14.242 | 21.07× | 1.326 | 1.872 | 1.41× | 0.576 | 0.646 |
| 2100 | 700 | 2.318 | 49.627 | 21.41× | 1.599 | 1.897 | 1.19× | 3.663 | 1.852 |
| 4500 | 1500 | 4.930 | 104.107 | 21.12× | 2.032 | 2.004 | 0.99× | 7.022 | 4.087 |
| 6000 | 2000 | 6.451 | 138.796 | 21.51× | 2.296 | 1.988 | 0.87× | 8.081 | 8.946 |
| 7500 | 2500 | 8.038 | 174.704 | 21.74× | 2.497 | 2.168 | 0.87× | 12.684 | 10.907 |
| 9000 | 3000 | 9.641 | 211.624 | 21.95× | 2.739 | 2.192 | 0.80× | 12.049 | 11.773 |

## Memory & GPU speedup
| N | exp GPU speedup | cal GPU speedup | exp state MiB | cal state MiB | exp bytes/motor | cal bytes/motor |
|---:|---:|---:|---:|---:|---:|---:|
| 600 | 7.6× | 0.5× | 1.24 | 0.10 | 2167 | 184 |
| 2100 | 26.2× | 1.4× | 4.33 | 0.37 | 2162 | 184 |
| 4500 | 52.0× | 2.4× | 9.27 | 0.79 | 2160 | 184 |
| 6000 | 69.8× | 2.8× | 12.36 | 1.05 | 2160 | 184 |
| 7500 | 80.6× | 3.2× | 15.45 | 1.32 | 2160 | 184 |
| 9000 | 96.5× | 3.5× | 18.54 | 1.58 | 2160 | 184 |

## B2. Cost scaling (ms/step = a + b·N, least squares over measured N)
- **explicit GPU**: 1.8229 + 3.983e-05·N ms  ⇒ launch floor ≈ 1.823 ms, per-1000-motors ≈ 0.0398 ms
- **explicit CPU**: -0.1883 + 2.337e-02·N ms  ⇒ intercept ≈ -0.188 ms, per-1000-motors ≈ 23.3748 ms
- **calibrated GPU**: 1.2480 + 1.684e-04·N ms  ⇒ launch floor ≈ 1.248 ms, per-1000-motors ≈ 0.1684 ms
- **calibrated CPU**: 0.0742 + 1.064e-03·N ms  ⇒ intercept ≈ 0.074 ms, per-1000-motors ≈ 1.0643 ms

## B2. crossover + explicit/calibrated ratio at low vs high N
- explicit: GPU faster than CPU from N≈600
- calibrated: GPU faster than CPU from N≈2100
- explicit/calibrated GPU cost ratio: N=600 → 1.41×, N=9000 → 0.80×
- explicit/calibrated CPU cost ratio: N=600 → 21.07×, N=9000 → 21.95×

## Interpretation — the five central questions
1. **Explicit vs calibrated on CPU:** ~**21×** more expensive, remarkably constant across N (21.1–22.0×). The explicit
   14-DOF beam solve (`matS2SolveStep`) vs the calibrated analytic 5-DOF movable-pivot (`matStep7`) dominates on one core.
2. **Explicit vs calibrated on GPU:** **1.41× at N=600 → 0.80× at N=9000** — the penalty collapses; at large N the full
   explicit graph is actually *cheaper* than the calibrated default graph.
3. **Does GPU parallelism compress the ratio?** **Dramatically — from ~21× (CPU) to ~1× (GPU).** The explicit beam solve
   is FP64-heavy but embarrassingly parallel per motor; the RTX 5070 absorbs it into the launch-floor-dominated regime.
4. **GPU throughput-efficient crossover:** explicit GPU beats explicit CPU from **N≈600**; calibrated GPU beats calibrated
   CPU from **N≈2100** (calibrated CPU is so cheap that small-N calibrated is faster on the CPU — GPU 0.51× at N=600).
5. **Which kernel limits explicit scaling at N=9000?** **`matS2SolveStep`** (0.86 ms of ~2.19 ms wall; the sole
   N-growing explicit stage). For calibrated at N=9000 the limiter is split between `matStep7` (0.75 ms) and its
   **serial CSR** (`csrHist`+`csrScatter` = 0.77 ms combined).

## Caveat — CSR-implementation confound (why the GPU crossover is partly incidental)
The explicit **gliding** graph uses the **parallel chunk CSR** (`csrChunk*`, flat ~0.018 ms at N=9000); the calibrated
`MatSoaSlice.buildTrajGraph` default uses the **serial CSR** (`csrHist`/`csrScatter`, single-GPU-thread O(N), growing
0.031→0.383 ms — the known CLAUDE.md serial-scan bottleneck). So ~0.77 ms of the calibrated N=9000 cost is incidental
to the CSR choice, not the motor model. **The fair per-model Step-10 solve comparison (from the per-stage tables):**

| N | matStep7 (calibrated solve) ms | matS2SolveStep (explicit solve) ms | explicit/calibrated solve |
|---:|---:|---:|---:|
| 600  | 0.056 | 0.507 | 9.1× |
| 4500 | ~0.28 | 0.610 | ~2.2× |
| 9000 | 0.748 | 0.860 | **1.15×** |

So on the GPU the explicit *solve itself* is only **1.15×** the calibrated solve at N=9000 (vs 9× at N=600 — the beam
solve amortizes into full SM occupancy as N grows). Enabling `-parcsr` on the calibrated graph would drop its full-graph
GPU cost to ~matStep7-bound (~2.0 ms at N=9000), making the full-graph GPU ratio ≈ 1.1× rather than 0.80×. Either way the
conclusion stands: **GPU parallelism reduces the explicit-vs-calibrated cost multiple from ~21× (CPU) to ≈1× (GPU).**

## Health (both arms): 0 invalid states, activeEqN=true (all N processed), production residency, no silent fallback.
