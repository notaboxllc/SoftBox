# v1 (BoA) vs v2 (SoftBox) — the MAXIMAL node-centric composition on GPU: ceiling + matched curve (MEASUREMENT-ONLY)

**Date:** 2026-06-23. **Branch:** `cadence-gate-fdturn`. **Status:** DONE.
**Scope:** MEASUREMENT-ONLY — benchmark existing v1 + v2 physics, no model edits to either. `BoA-v1ref` **byte-clean**
(read-only; `.class`/`.jar` gitignored, verified `git status` clean before+after); v1 config + outputs live in `/tmp/v1max`.
The only code change is a one-line fix to v2's measurement-only `-scale` flag (let `N_MINI=0` survive scaling, for the
node-centric `-mini 0` scenes) — production physics untouched. v1 ran from `BoA-v1ref` via `BoxOfActin -r -gpu -pf /tmp/...`.

---

## TL;DR — the four answers

1. **CAN v1 carry the biologically-typical node-centric maximal composition? YES — and it does NOT wall early.** v1-GPU
   ran the full node-centric SCPR composition (protein nodes + node-formin actin nucleation + node-bound myosins +
   crosslinkers + full turnover) clean from 1× to **64×** (1 600 nodes, ~48 k actin segments, 7 200→… node-myosin heads),
   no crash/OOM, host RSS only 6.0 GB of 31 GB. The prior "v1 can't run the full composition" assertion is **refuted** for
   this composition — it is far lighter per scale-unit than the dense *gliding* scene that walled v1 at 16× (1.5 M motors).
2. **The matched curve is COUNTER-NARRATIVE: v1-GPU is 1.3–2.5× FASTER per-step than v2-GPU on this composition** (not
   slower). v2's win here is **host-RAM, not throughput**.
3. **Host-RAM: v2 uses 2.1–3.8× less than v1 — NOT the 7.6× of the dense workload, and the ratio SHRINKS with scale.** The
   light node-centric scene is dominated by v1's **fixed static-array floor** (~3.4 GB, from the gliding-study cap-raises),
   not the per-element object graph that drives the 7.6× at dense.
4. **Neither engine is memory-walled in the tested range; both are compute(steps/s)-bound.** v1's practical floor (~5 steps/s)
   is ~40–64×; its host-RAM wall extrapolates to several-hundred×. v2's VRAM ceiling is ~200× (`SCALE_SWEEP_FINDINGS.md`).

**The honest qualifier (load-bearing — read §4):** the scene *definition* is matched (nodes / formins / node-myosins / box
/ dt / turnover rates), but the two engines do **different amounts of actual work** with it: **v2 forms 29–374 crosslinks and
runs its node tethers on-device; v1 forms ZERO crosslinks and ZERO myosin binding in this sparse node-centric layout and runs
node-surface tethers on CPU.** So v1's faster per-step partly reflects *lighter actual work*, not purely a faster engine — see §4.

---

## §1. The makeup (signed off by jba) + its literature basis

**Node-centric fission-yeast SCPR** (`RING_ASSEMBLY_LITERATURE.md`: Vavylonis 2008, Laplante 2016 node stoichiometry):
a band/grid of protein nodes, each carrying formins (Cdc12) that nucleate actin + node-bound myosin-II (Myo2), with
crosslinkers (Ain1/Fim1) + actin turnover; **filaments and myosin tied to nodes, no free-floating minifilaments.**

**1× scene (matched both engines):**
| element | per node | 1× total (25 nodes) | v2 param | v1 param |
|---|---|---|---|---|
| nodes (planar grid/band, spacing 0.6 µm, box 4×4×0.5 µm) | — | 25 | `GX·GY=5×5` | `initialNodes:25` |
| formins (Cdc12) → nucleated actin | 6 | 150 filaments / ~1050 segs | `FORMINS=6` | `forminsPerNode:6`,`kNodeNuc:10` |
| node myosin singlets (Myo2) | 6 | 150 | `N_SING=6` | `numNodeMyos:6` |
| node myosin dimers (Myo2) | 6 | 150 (→ 450 heads total/node-shell) | `N_DIM=6` | `numNodeMyoDimers:6` |
| crosslinkers (Ain1/Fim1) | ON | — | `XLINK_CONC` | `xLinkOnRate:40`,`maxLinksOnSeg:10`,`sideBonds:0` |
| turnover (growth+depoly+aging+sever) | ON, KIN=1 | — | `AGING/SEVER` | `kATPOn/capRate/cofilinRate:0.4/kHydrolysis` |
| free minifilaments | **0** | **0** | `-mini 0` | `initialMyoMiniFils:0` |
| dt / drag | 1e-5 / aeta 1.0 | — | `dt=1e-5` | `deltaT:1e-5`,`aeta:1.0` |

≈8 Myo2 dimers/node + a few formins/node is biologically typical; the makeup maps cleanly onto v2's `FullSystemDemo` defaults
so the scenes match physically. **N-series (both engines):** nodes ∝ N, actin ∝ N, **box AREA ∝ N** (`boxXY ∝ √N`), per-node
makeup fixed — the same size-scaling as v2's `-scale`.

## §2. Where v1 stands (Stage-0 recon)

- v1's universal harness is **`BoxOfActin`** (`-pf <ParameterFile>`, `-gpu`, `doLoop()`); every subsystem is a config toggle,
  and the general path (`makeInitialThings`) instantiates filaments + minifilaments + **nodes** together.
- **v1 had NO packaged node-centric maximal config** — its ready maximal config (`boa10-64Seg-dyn-dense-{0p5..8}x`) is
  **node-free** (free minifilaments + actin + xlink + turnover); its node configs (`singleNode_myosins`,
  `nodeContractilityAssay`) are single-node, turnover-OFF. So the node-centric scene needed a **new ParameterFile**
  (`/tmp/v1max/...`, config of the existing universal harness — not a new code harness). It assembled and ran first try.
- **v1 DOES have a GPU node path** (`GPUMoveThing.RULE_NODE` — device-integrated node sphere + device nodeTether kernel,
  `NODE_GPU_ENABLED` default-on); the `nodeContractilityAssay` "run on CPU" caveat is about its *assay IC/pin/readout*, not nodes.

## §3. The matched curve (v1-GPU `BoxOfActin` vs v2-GPU `FullSystemDemo`, RTX 5070, dt=1e-5)

> **⚠ RE-BASELINE NOTE (2026-06-24): the v2 steps/s column below was measured with CSR-host OFF.** CSR-host is now
> the **production default** (`MEGAKERNEL_PROBE_FINDINGS`), **+~7–11 %** (controlled 3-config back-to-back vs the
> old full-device path). The re-baselined new-default v2 steps/s are **67.7 / 47.9 / 30.4 / 16.9 / 9.0** at
> 1/2/4/8/16×. This **narrows but does NOT reverse** v1's per-step lead (new v2/v1 ≈ 0.86 / 0.69 / 0.54 / 0.45 /
> 0.45) — the megakernel probe confirmed the residual gap is the §4(b) WORK ASYMMETRY (v1 forms 0 crosslinks / 0
> binding here), not recoverable by recomposition. The §4–§7 conclusions stand. Add `-devicecsr` to revert the
> dynamic seg round-trip. See `RUN_LOGS/2026-06-24_csrhost_default_rebaseline.txt`.

v1 per-step = the `BENCHMARK_dense` two-step-count difference (wall(K2)−wall(K1))/(K2−K1) — cancels JVM/JIT/plan startup. v2
per-step = the faithful device-wall steps/s (`-sweep`, warmup-excluded). Host RSS = `/usr/bin/time -v` MaxRSS. VRAM = nvidia-smi.

| scale | nodes v1/v2 | actin segs v1/v2 | node-myo heads v1/v2 | **v1 steps/s** | **v2 steps/s** | **v1/v2** | **v1 RSS GB** | **v2 RSS GB** | RSS v1/v2 | v2 VRAM MiB | v2 xlinks (v1=0) |
|---|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 1×  | 25/25   | 1058/1050   | 450/450    | **78.8** | **62.1** | 1.27× | 3.41 | 0.90 | 3.79× | 439 | 29 |
| 2×  | 50/49   | 2023/2058   | 900/882    | **69.8** | **43.3** | 1.61× | 3.39 | 0.94 | 3.61× | 461 | 42 |
| 4×  | 100/100 | 3905/4200   | 1800/1800  | **55.8** | **26.7** | 2.09× | 3.37 | 1.08 | 3.12× | 515 | 96 |
| 8×  | 200/196 | 7013/8232   | 3600/3528  | **37.4** | **15.1** | 2.48× | 3.72 | 1.42 | 2.62× | 602 | 180 |
| 16× | 400/400 | 12994/16800 | 7200/7200  | **19.9** | **7.8**  | 2.55× | 4.37 | 2.08 | 2.10× | 832 | 374 |
| 32× | 800/—   | ~25 000/—   | —          | **8.2**  | —        | —     | 4.98 | —    | —     | —   | — |
| 64× | 1600/—  | ~48 000/—   | —          | **2.7**  | —        | —     | 5.96 | —    | —     | —   | — |

**Sanity (every point):** v2 conservation EXACT, 0 phantoms, 0 NaN (1 transient warm-start wall-escape at 8×/16×); v1 ran clean,
no crash/OOM/NaN at any scale incl. 32×/64×. v1 `usedHeapMB` (live object graph) 1.24→1.78 GB across 1×→16× (the RSS above
includes JVM + v1's static-array floor on top of that).

**Shape.** v2 weak-scales ~linearly (steps/s ∝ 1/N; kernel-compute % rises 43→80 % — it crosses into compute-bound near 2×,
matching `SCALE_SWEEP`). v1 scales **sub-linearly at small N** (per-step ~fixed-overhead-bound: 12.7→14.3→17.9 ms over 1×→4×)
then ~linearly at large N (26.7→50→122→368 ms over 8×→64×). v1 stays **ahead at every matched scale**, the gap widening
1.27×→2.55× then plateauing.

## §4. WHY v1 is faster here — and why it is NOT the whole story (the load-bearing caveat)

v1's faster per-step is real but has three explanatory (and partly confounding) causes — **disclosed so the curve is read honestly:**

1. **v2's maximal device path is LAUNCH-BOUND** (`PROFILE_FULLDEMO` / `SCALE_SWEEP`): ~74–106 small kernels/step across 7
   chained TaskGraphs; kernel-compute is only 43 % of the step at 1× (rising to 80 % at 16×). v1 integrates the bodies in a
   **single monolithic `gpuMoveThing` kernel** (far fewer launches), so at small/medium scale v1 pays less per-step launch overhead.
   This is an architecture property of the *maximal composition's breadth*, exactly the launch-ceiling `SCALE_SWEEP` documents.
2. **v2 does crosslinker work v1 SKIPS.** v2 forms **29→374 crosslinks** and runs its O(N) formation pipeline + 2-pass gather
   over up to 163 840 link slots every formation cadence; **v1 forms ZERO crosslinks** in this node-centric layout (`filLinks=0`
   at every scale — node-tied filaments are too sparse/aligned for v1's `checkToLink` to fire). So v2 is doing real bundling work
   that v1's sparse scene never triggers — part of v2's slower steps/s is *more work*, not a slower engine.
3. **v1 binds ZERO myosin + runs node tethers on CPU.** `meanBoundMotors=0.000` and node-surface tethers fall back to CPU
   (`cpuNodeTetherApplyCt`>0 — the node *bodies* are device-classified `RULE_NODE`, but the surface-myosin tethers run host-side
   in the multi-graph turnover+xlink config). So v1's myosin-binding compute path is not exercised either.

**Bottom line for §3:** at *matched scene definition* v1-GPU completes a step faster, but because (in this sparse SCPR layout)
v1 does **less actual work** (no crosslink formation, no binding) and v2's broad device graph is launch-bound. A scene dense
enough to make both bind + crosslink (i.e. a percolating bundle, not a sparse node band) would close or reverse the gap — that
is the `DENSE_CONTRACTILE_BENCHMARK` regime, where v2-GPU beats v1-GPU 5–7×. **The two benchmarks together say: v2's throughput
win is workload-dependent (dense/percolating ⇒ v2 wins big; sparse node-centric ⇒ v1's leaner kernel wins).**

## §5. Ceilings — v1 vs v2 on the maximal composition

| | v1-GPU (BoA) | v2-GPU (SoftBox) |
|---|---|---|
| **memory wall reached in 1–64×?** | **NO** — RSS 3.4→6.0 GB of 31 GB at 64× (1600 nodes, ~48 k segs); clean | **NO** — VRAM 0.44→0.83 GB of 12 GB at 16× |
| **extrapolated memory ceiling** | host-heap `Mesh.<init>`/`Thing[32M]` ~several-hundred× (light scene; the dense *gliding* scene hit it at 16× = 1.5 M motors) | VRAM ~**200×** (`SCALE_SWEEP`: ~470 + 39·N MiB) |
| **practical compute floor (~5 steps/s)** | ~**40–64×** (32× = 8.2/s, 64× = 2.7/s) | ~**24×** (`SCALE_SWEEP`: ~5/s at 24×) |
| **binding constraint** | compute (steps/s) — NOT memory | compute (steps/s) — NOT memory |

**The premise test:** "v1 walls early on the heavier full composition" — **refuted**. The node-centric maximal composition is
*light* (≤7 200 heads / 17 k segs at 16×, vs the dense gliding's 1.5 M motors), so it is nowhere near v1's host-heap wall;
v1 carries it past 64×. Both engines are throughput-bound, not memory-bound, in any practically-runnable range.

## §6. Host-RAM footprint — the 7.6× premise does NOT hold here (workload-dependent)

v1 RSS = a **fixed ~3.4 GB floor** (the gliding-study static-array cap-raises — `Thing[32M]`, `MyoMotor.soa[8M]`×10,
`ProteinNode[100k]`, `FilLink[100k]` — pre-allocated regardless of scene) **+ slow per-element growth** (3.41→4.37→5.96 GB over
1×→16×→64×). v2 RSS = JVM + right-sized SoA, scaling with elements (0.90→2.08 GB over 1×→16×). So:
- **Ratio v1/v2 SHRINKS with scale: 3.79× (1×) → 2.10× (16×)** — v1's fixed floor amortizes while v2 grows. It does **not**
  reproduce the **7.6×** of `DENSE_CONTRACTILE_BENCHMARK` (8×, 1 M heads), because that ratio is set by the **per-element OOP
  object graph** (Thing/FilSegment/MyoMotor on the heap) which only dominates at *heavy* per-element scenes. At this *light*
  node-centric scene v1's RAM is dominated by its fixed static-array floor, not the object graph.
- v1's *live* object graph (`usedHeapMB` 1.24→1.78 GB) is closer to v2's RSS; the extra ~2 GB of v1 RSS is the oversized static
  arrays + JVM. (Those caps are `static final` in v1 source — not shrinkable without editing `v1ref`, so v1 ships them; disclosed.)

**Verdict:** the SoA host-RAM advantage is **real but workload-scaled** — large (≈7.6×) on dense/heavy per-element workloads,
modest (≈2–4×) on the light node-centric SCPR composition, and shrinking as the scene grows toward v1's fixed floor.

## §7. Flagged matching caveats (so the numbers are read correctly)

1. **Work asymmetry (§4): v1 forms 0 crosslinks / 0 binding; v2 forms 29–374 crosslinks** + device node tethers. The biggest
   confound — v1's steps/s reflects lighter actual work in this sparse layout.
2. **Count drift at scale.** Element counts match at 1× (segs 1058 vs 1050; motors 450 vs 450) but **diverge at high N** as the
   two turnover dynamics differ (16×: v1 12 994 vs v2 16 800 segs — v1's nucleation/growth net-grows ~differently). The per-step
   numbers are at the *actual* counts shown; v1 carries *more* segs at 16× yet is still faster (strengthening §4's "leaner kernel").
3. **Crosslinker formation calibration differs** between engines (v1 `maxFilLinkDist:0.02`/`checkToLink` vs v2 `GRAB_DIST`/`P_form`)
   — why v1 finds no pairs where v2 finds some; a real cross-engine divergence, not tuned here (measurement-only).
4. **dt matched (1e-5)**; biochem cadence approximately matched (v1 `biochemDeltaT:0.001` ≈ v2's 100-step cadence). Residual
   cadence differences are a minority of the per-step cost (the every-step mechanics dominate).
5. **v1 node-surface tethers run CPU-side** in the multi-graph turnover+xlink config (node *bodies* on device). Partial GPU.

## §8. Files & reproduction

- v1 config: `/tmp/v1max/ParameterFiles/v1max_nodecentric_1x` (+ per-scale `v1max_{1,2,4,8,16,32,64}x`), built from
  `BoA-v1ref/ParameterFiles/boa10-64Seg-dyn-dense` (turnover+xlink base) + the node-population/node-myosin knobs.
- driver: `/tmp/v1max/run_maxsweep.sh` (per scale: v1 K1/K2 GPU runs + RSS/VRAM; v2 `FullSystemDemo -dense -mini 0 -scale F
  -sweep`). Log: `/tmp/v1max/RUN_LOGS/maxsweep.txt`.
- v2: `softbox.FullSystemDemoHarness -dense -mini 0 -scale F -sweep` (node-centric: `-mini 0`). One-line `-scale` fix
  (`N_MINI=0` survives scaling) — measurement-flag only; production physics untouched; `SCALE_SWEEP` numbers (N_MINI>0) unaffected.
- `BoA-v1ref` **byte-clean** (read-only; outputs to `/tmp/v1max`). MEASUREMENT-ONLY: no physics edits to either engine.
</content>
