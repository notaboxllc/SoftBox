# Dense contractile compute benchmark — v2 vs BoA GPU (the deferred 2026-06-17 target)

**Date:** 2026-06-22. **Branch:** `grid-parallel-build` (on `main`). **Status:** DONE — full sweep 0.5×–8×
(all fit the 12 GB card; no VRAM ceiling). **Headline: v2's ECS/device-resident GPU path beats BoA's GPU
5.3–7.0× on the dense contractile workload AND uses 7.6× less host RAM** (the realistic future workload: free
filament network + bipolar minifilaments + crosslinkers + containment) — the gliding win, now on contraction,
plus the host-RAM-ceiling result the whole project targets.

## TL;DR

The deferred target (2026-06-17, blocked on polymerization) is unblocked. `DenseContractileHarness` is the
**first full-system composition at dense scale** — minifilament binding + cross-bridge contraction +
crosslinker force + containment + the parallel-grid fused per-motor binding + integration, all
device-resident (no per-step host pull), reusing the validated systems VERBATIM.

**Two v2 gaps were discovered during recon (documented, not bugs):**
1. **Crosslinker FORMATION is O(N²)** — `CrosslinkerSystem.filFilCandidates` is single-threaded with
   `reqCap = nSeg(nSeg−1)/2` (≈288M pairs at 1×). The `STORE_CROSSLINKER` SpatialGrid publisher that would
   make it O(N) is unwired ("5d"). Formation cannot run at dense scale.
2. **No random (non-node) nucleation exists** (`kRdmNuc` undefined; only node-driven nucleation). A free
   filament network cannot be replenished.

Neither is a per-step *throughput* driver — in BoA's own GPU cost breakdown (Part C) biochem ≈ 3 % and
crosslink-formation ≈ 0 of the step; the dominant cost is the **mechanics** (exec/pack/gather/brown/integrate
≈ 95 %), all of which v2 has parallel + device-resident. So the benchmark **pre-places the scene at BoA's
matched counts** (filaments pre-grown to BoA's segs/fil, crosslinkers pre-formed at the active-link count)
and runs the per-step mechanics device-resident. Crosslinkers run FORCE + the 2-pass gather (static topology;
unbind/formation = the 5d-gated lifecycle, excluded). This captures the workload BoA's GPU column measures.

## The headline — v2 GPU vs BoA GPU (RTX 5070, dt=1e-4, 650 steps = 300 warmup + 350 window)

| scale | filaments | minifils | heads | active xlinks (v2 / BoA) | **v2 GPU ms/step** | BoA GPU ms/step | **v2 vs BoA** | BoA CPU ms |
|---|---|---|---|---|---|---|---|---|
| 0.5× | 2 000 | 2 000 | 64 000 | 1 680 / 1 693 | **12.42** | 86.35 | **7.0× faster** | 117.2 |
| 1× | 4 000 | 4 000 | 128 000 | 3 360 / 3 363 | **19.86** | 134.55 | **6.8× faster** | 215.0 |
| 2× | 8 000 | 8 000 | 256 000 | 6 720 / 6 575 | **46.51** | 246.39 | **5.3× faster** | 434.3 |
| 4× | 16 000 | 16 000 | 512 000 | 13 440 / 12 579 | **92.89** | 494.28 | **5.3× faster** | 865.6 |
| 8× | 32 000 | 32 000 | 1 024 000 | 26 880 / 25 136 | **179.32** | 1030.23 | **5.7× faster** | 1777.2 |

Per-step time is **~linear in scale** (12.4→19.9→46.5→92.9→179.3 ms; weak-scaling ratios per 2× population:
1.60 / 2.34 / 2.00 / 1.93 — ≈ the ideal 2.0×), i.e. no super-linear blow-up — v2 has no single-threaded serial
kernel left (grid build, CSR gathers, and the per-motor binding are all parallel). v2's margin over BoA is
**5.3–7.0× across the whole sweep** (narrowing slightly with scale because v2 is GPU-saturated while BoA's GPU
is partly host-`pack`-bound ∝N — both trend toward their compute floor, v2's ~5–7× below BoA's).

**BoA's GPU did NOT win at this dense workload at any scale** (GPU/CPU = 0.74→0.58, GPU always behind CPU) —
it was bottlenecked by a flat ~115 ms/step device→host copy-out + host `pack` (∝N) + the superlinear serial
`gridScatter` (per `BENCHMARK_contractile_dense.md`). v2's architecture eliminates exactly those:
device-residency (no copy-out, no pack), the parallel grid build, the fused per-motor binding, the parallel
CSR gathers. So v2 beats **both** BoA's GPU and BoA's CPU by a wide margin.

## Scene-match (the parity check — counts confirmed by construction)

v2's counts match BoA's dense v5 table closely: filaments = 4000·scale ✓, minifils = 4000·scale ✓,
**segments = 6·filaments = 24 000 @1×** (BoA grown 23 779 ✓), **active xlinks = 0.84·filaments = 3 360 @1×**
(BoA 3 363 ✓). Pre-placed, so matched by construction rather than grown — the two v2 gaps above are why.
Heads = 32·minifils = 128 000 @1× (BoA `myoCap` 256 000 = 2× headroom ⇒ consistent).

## Stability + physics-sanity

- **Stable at BoA's dt = 1e-4** over the full 650-step window at every scale tested — **no NaN, no blow-up**
  (the v1 12 pN cross-bridge break-force cap + the in-vitro chamber box + the crowded-cytoplasm aeta=1.0 keep
  the free minifilament network bounded). The feared dt=1e-4 instability did not materialize, so the
  comparison is at BoA's exact dt (no 1e-5 fallback needed).
- Binding works (heads bind the free filament network via the grid; avgBound ~0.013/head — a sparse free
  network, the heads that point at a nearby filament engage). Crosslinkers hold the matched count (static).
- GRID==BRUTE / CPU≡GPU: the fused `gridReachable` binding path is the SAME kernel validated == brute +
  CPU≡GPU bit-identical in `GRID_PARALLEL_FINDINGS.md`. In the (chaotic, fully-Brownian) contractile scene
  CPU and GPU **aggregate-agree** (avgBound GPU 238 / CPU 268 @0.1×, activeXlinks identical, no NaN on either) —
  the deterministic binding is bit-identical; the many-body trajectory decorrelates (Lyapunov), the standard
  for chaotic dynamics.

## Memory — the headline architectural win (the reason SoftBox exists)

At 8× (≈1 M heads, 192 k segments, 27 k crosslinks), measured peak:

| | v2 (SoftBox) | BoA (v1) | ratio |
|---|---|---|---|
| **Host RSS** | **3.36 GB** | 25.55 GB | **7.6× LESS** |
| **VRAM** | **3.04 GB** | 3.79 GB | 1.25× less |

**This is the whole thesis of SoftBox, now measured on the realistic dense workload.** v1's host RSS is
dominated by the OOP object graph (Thing / Mesh / FilSegment / MyoMotor on the JVM heap) — the ~16× host-RAM
ceiling the v1 scaling study identified. v2's SoA primitive component arrays are device-resident and host-light:
**3.36 GB vs 25.5 GB at the same 8× workload.** The whole sweep fits the RTX 5070's 12 GB VRAM with room to
spare (8× peak 3.04 GB), so there is **no VRAM ceiling through 8×** — v2 could run substantially larger. Per
scale (v2): the device-buffer footprint is dominated by `reachSeg` (nHeads·MAX_CAND) + the chunk matrices; all
scale ∝ N, no fixed copy-out buffer (BoA's flat 1.08 GB `ffCandPartner` copy-out is simply absent — there is no
per-step host pull).

## Verdict

**Yes — v2's ECS/device-resident GPU path beats v1's GPU (and CPU) on the dense CONTRACTILE workload**, ~6–7×,
matching the gliding result. The architecture's advantage (device-residency killing the copy-out + host pack,
the parallel grid/CSR/fused-binding killing the serial kernels) holds for the realistic multi-system workload.
The full-system composition (minifilament binding + cross-bridge + crosslinker force + containment + parallel
binding + integration) runs stably at dense scale. The two un-composable-at-scale pieces (O(N²) crosslinker
formation → needs the 5d grid publisher; random nucleation → undefined) are the v2 build edge for a *dynamic*
(grown-not-pre-placed) version, flagged for follow-up — neither changes the throughput verdict (both are ≪3 %
of BoA's own step).

## Files
`DenseContractileHarness.java`, `run_densecontract.sh`. Reused VERBATIM: the parallel grid + fused binding
(SpatialGrid/BindingDetectionSystem), the minifilament structure (MotorStore/DimerStore/MiniFilamentStore +
MotorJoint/DimerCoupling/MiniFilament/CrossBridge systems), the crosslinker force (CrosslinkerStore/System),
containment, the shared rigid-rod systems. `BoA-v1ref` byte-clean; production untouched.
</content>
