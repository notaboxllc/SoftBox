# SCALE/DENSITY SWEEP — where the maximal composition crosses into compute-bound, and how it performs at LARGE scale (MEASUREMENT-ONLY)

**Date:** 2026-06-23. **Branch:** `cadence-gate-fdturn` (off the §9.3 live-bundling device path).
**Status:** DONE. **Scope:** MEASUREMENT-ONLY — a `-scale F` size-scaling flag + a `-sweep` short-window probe
in `FullSystemDemoHarness` (profiler-on for the kernel fraction, + VRAM + physical-sanity + a capped CPU
comparison). **No physics / rate / kernel / ordering / default edit.** The production `stepSplit` / constituents
/ `-cpu` runner are byte-unaffected; `BoA-v1ref` byte-clean. Driver: `run_scalesweep.sh`; log
`RUN_LOGS/2026-06-23_scale_sweep.txt`.

```
./run_scalesweep.sh 2000                                  # the whole sweep (default + dense 0.5×…16×), gpusteps=2000
./run_fulldemo.sh -dense -scale 4 -sweep -gpusteps 2000 -cpucap 150   # one point
```

---

## TL;DR — the regime verdict

**The maximal composition crosses from launch-bound into compute-bound just above the dense baseline (~1.5× the
dense scene), and at LARGE scale it is a clean, linearly-weak-scaling, VRAM-cheap compute-bound GPU path.** The
GPU/CPU ratio climbs monotonically with scale (1.1× → 17× measured) and **never plateaus within the VRAM budget**;
the conservative warm-up-excluded read of it is a steady **~5× compute-bound speedup** (the DenseContractile
regime), with the measured number running higher because the serial CPU runner degrades super-linearly. **The
binding constraint at scale is GPU steps/s (usability), NOT VRAM** — even the 16× scene (16,800 segments / 89,120
heads / 400 nodes) uses only **1.1 GB of 12 GB** (9 %); steps/s hits the ~5/s usability floor near **24×** while
VRAM would not fill 75 % until **~200×**. A ring-scale MORPHOLOGY run (hundreds of nodes, seconds of sim-time) is
overnight-GPU-viable; full biological-DURATION (minutes of sim) at faithful dt=1e-5 is not (mechanics-dt bound,
not throughput-bound).

---

## §1. Method — clean SIZE-scaling at constant density

`-scale F` multiplies the (optionally `-dense`) baseline so it is a **size-scaling, not a crowding-scaling**:

- **box law:** node spacing (0.6 µm) and slab depth (BOX_Z=0.5 µm) are FIXED; the box **AREA ∝ F** (BOX_XY ∝ √F),
  so entity *density* (per volume) is held ~constant while the domain grows.
- **entities ∝ F:** nodes ×F (GX,GY ×√F), free minifilaments ×F, FilamentStore capacity ×F. Crosslink slots and
  heads follow (≈ 4·cap, ≈ 32·minifils). Node-grid extent ∝ √F and the box ∝ √F ⇒ the wall margin is preserved
  (no crowding-induced wall-escape onset; the few escapes seen are the pre-existing warm-start transient).

Each point: a fresh JVM → `-sweep`: **100-step warmup** (FIRST_EXECUTION uploads + PTX JIT, excluded) then a
**SHORT 2000-step measured window** — short enough that the §8 per-execute creep is negligible (≈0.13 µs/step ⇒
sub-0.3 ms drift over the window), so this is the per-step rate AT a fixed scale, not a long run. The window is
profiler-on (`SILENT`+`clearProfiles`) so the **kernel-compute fraction** is read AND the **production-faithful
per-step device wall** (`Σ exec-wall + host`, which EXCLUDES the profiler-read overhead) is the reported steps/s.
CPU is the unaltered `-cpu` `cpuStep`, capped short (400 → 40 steps as N grows; serial ∝N, see §4 caveat) for the
ratio. The launch-bound low end is the non-dense **default** scene.

## §2. The curves vs scale (the sweep table)

Per-step window = 2000 steps after 100-step warmup; dt=1e-5; KIN=1; RTX 5070, 12 GB.

| label | scale | nodes | minifils | active segs | heads | box µm | **GPU steps/s** | CPU steps/s | **GPU/CPU** | **kernel %** | **VRAM MiB** | xlinks |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| default (non-dense) | — | 16 | 60 | 672 | 2 208 | 3.6 | **75.6** | 66.5 | 1.14× | 33.9 | 488 | 2 |
| dense 0.5× | 0.5 | 16 | 80 | 672 | 2 848 | 2.8 | **77.2** | 54.4 | 1.42× | 32.1 | 484 | 13 |
| dense 1× | 1 | 25 | 160 | 1 050 | 5 570 | 4.0 | **59.3** | 28.1 | 2.11× | 43.9 | 508 | 22 |
| dense 2× | 2 | 49 | 320 | 2 058 | 11 122 | 5.7 | **41.6** | 11.8 | 3.53× | **57.1** | 540 | 42 |
| dense 4× | 4 | 100 | 640 | 4 200 | 22 280 | 8.0 | **25.7** | 4.4 | **5.82×** | 68.6 | 622 | 91 |
| dense 8× | 8 | 196 | 1 280 | 8 232 | 44 488 | 11.3 | **14.1** | 1.5 | 9.31× | 76.4 | 772 | 168 |
| dense 16× | 16 | 400 | 2 560 | 16 800 | 89 120 | 16.0 | **7.4** | 0.4 | 17.22× | **80.9** | **1 092** | 340 |

**Physical sanity (every point):** conservation EXACT, 0 phantoms, 0 NaN, plausible crosslink count (rising ∝ scale)
and contraction (shrink 0.02–0.9 %). Wall-escapes: 0 through 4×, then 1 (8×) / 2 (16×) — the pre-existing
warm-start transient (a couple of warm-placed filament tips pulled back in by containment), ≪ the bail threshold
`max(40, active/40)` (=420 at 16×); **no run was physically broken** — every perf number is for a clean trajectory.
Per-step host↔device copy is a flat **3.77 KB at every scale** (residency holds; no hidden full-state copy appears
at scale).

**GPU weak-scaling (the compute-bound signature).** Per-step device wall (ms) = 16.9 (1×) → 24.0 (2×) → 38.9 (4×)
→ 70.9 (8×) → 135.1 (16×). The wall-ratio per 2× of scale is **1.42 / 1.62 / 1.82 / 1.91 → 2.0** — it *approaches
ideal linear weak-scaling* as the fixed launch floor amortizes into the growing kernel work (exactly the
DenseContractile 1.6–2.0 signature). The GPU has no serial kernel left: grid build, CSR gathers, per-motor
binding, and the 2-pass crosslink gather are all parallel, so wall ∝ N and steps/s ∝ 1/N at large scale.

## §3. The crossover — where the launch floor stops dominating

- **kernel-compute reaches ~50 % between 1× (43.9 %) and 2× (57.1 %)** — linear-interpolated, the launch floor
  ceases to dominate at **≈1.5× the dense scene (≈1 500 active segments / ≈7 900 heads).** Below that the ~74–86
  fixed kernel launches/step (the §PROFILE ~8 000-launch/s ceiling) are the majority of the step; above it the
  kernel math is. By 16× the step is **80.9 % kernel-compute** — fully compute-bound (launch overhead is a ~19 %
  tail).
- **The GPU/CPU ratio enters the 5–7× DenseContractile compute-bound regime at ~4× scale** (measured 5.82× at 4×)
  and keeps climbing (9.3× at 8×, 17× at 16×). It does **NOT plateau** within the VRAM budget — see §4 for why,
  and for the conservative read.

So the launch-bound→compute-bound transition is **narrow and just above the hunt's dense scene**: the dense
baseline (§9.3, 57 steps/s, 1.9× CPU) sits right at the knee; one doubling puts the composition firmly
compute-bound.

## §4. The GPU/CPU ratio — climbs without plateau; the conservative compute-bound speedup is ~5×

The measured ratio climbs 1.1× → 17× because **the GPU weak-scales ~linearly while the serial CPU runner degrades
super-linearly.** Two effects inflate the measured large-scale ratio and must be disclosed:

1. **CPU super-linearity.** The `-cpu` runner executes the whole turnover+nucleation+node+binding stack serially;
   its per-step wall (ms): 15.0 → 35.6 → 84.7 → 227 → 667 → 2 500 grows *faster* than 2× per doubling at the top
   (serial broad-phase / single-thread CSR scans). The GPU parallelizes exactly these, so the ratio = GPU's
   linear ÷ CPU's super-linear ⇒ unbounded growth.
2. **Warm-start asymmetry (a measurement caveat).** GPU steps/s is warm-up-EXCLUDED (100-step warmup dropped); the
   short CPU caps at 8×/16× (80/40 steps) are NOT — they include proportionally more of the binding/containment
   warm-start transient, inflating CPU ms/step. So the **measured ratio at 8×/16× is an UPPER estimate.**

**Conservative cross-check (warm-up-excluded, linear-CPU extrapolation).** Fitting CPU ms/step ≈ 8.8e-3·heads from
the cleaner mid points (caps ≥150) and dividing into the GPU wall gives a ratio that **plateaus at ≈4.7–5.7×**
(4× → 4.7×, 8× → 5.4×, 16× → 5.7×) — i.e. the genuine compute-bound GPU/CPU speedup is **~5×**, squarely in the
DenseContractile 5–7× band, and the measured 9–17× is the CPU's serial degradation (real, but partly cap-transient).
**Verdict:** crossover into the **≥5× compute-bound regime by ~4× scale**; thereafter the *true* speedup is a
steady ~5× and the *observed* speedup grows because the CPU baseline collapses.

## §5. THE LARGE-SCALE HEADLINE

At the **largest scale run (16×)** — and note **16× was the chosen stop, NOT a VRAM/capacity limit**:

| metric | 16× value |
|---|---|
| scene | 400 nodes · 2 560 minifilaments · 16 800 active segments · **89 120 heads** · 340 crosslinks |
| **GPU steps/s** | **7.4** (per-step device wall 135 ms; 80.9 % kernel-compute) |
| GPU/CPU ratio | 17× measured / **~5× conservative** (warm-up-excluded) |
| **VRAM** | **1 092 MiB of 12 227 MiB — 9 %** (process ≈ 0.8 GB over a ~0.29 GB baseline) |
| sanity | conservation EXACT, 0 phantoms, 0 NaN, 2 transient wall-escapes (≪ bail) |

- **Sim-time per wall-clock hour** (dt=1e-5 ⇒ 1e-5 s/step): GPU steps/s × 0.036 s-sim/hr ⇒ **0.27 s-sim/hour at
  16×** (2.1 s/hr at dense 1×, 0.93 s/hr at 4×, 0.51 s/hr at 8×). An overnight (~8 h) 16× run reaches **~2 s of
  simulated time** — consistent with the §6 overnight (3.7 s at dense over 5.5 h).
- **VRAM headroom.** VRAM(F) ≈ 470 + 39·F MiB (measured 508→1092 over 1×→16×, ~+40 MiB per scale-unit). 75 % of
  12 GB is reached at **F ≈ 224×**; the usability floor (~5 steps/s, wall ≈ 200 ms; wall ≈ 6.8 + 8.0·F ms) is hit
  at **F ≈ 24×.** **steps/s binds ~9× before VRAM** — this composition is firmly **compute/throughput-bound, not
  memory-bound** at every scale that fits the card.
- **Is a ring-scale run GPU-viable for biological sim-time?** **For ring MORPHOLOGY: yes.** 16× already *is*
  ring-scale node counts (400 nodes — a ring of hundreds of nodes with their formin brushes + minifilaments +
  crosslinkers), device-resident, stable, conservation-EXACT at 7.4 steps/s ⇒ a **multi-second morphological
  assembly run is overnight-feasible.** **For biological DURATION: no, and the bottleneck is not the GPU.** Full
  cytokinetic-ring timescales are minutes; at faithful mechanics dt=1e-5 that is ~10⁷–10⁸ steps = weeks of
  wall-clock at any scale — a **dt/timescale-compression problem (KIN biochem-speedup + a larger mechanics dt),
  not a throughput problem.** The GPU path delivers the largest scene and the most sim-time-per-hour available;
  reaching biological duration needs the time-compression levers, not more compute.

## §6. The §8 per-execute creep AT scale — density softens the deferred decay (run-length ceiling)

The §8/PROFILE chained-split creep is a **TornadoVM-internal per-execution accumulation on the first always-run
graph (`fdNuc`), task-count-bound and FIXED at ≈0.13 µs/step** (§9.3.3 measured 0.127 µs/step at dense; `fdNuc`'s
11 tasks are scale-INVARIANT ⇒ the slope does not grow with scale — confirmed by a 2× decay probe, see below).
Because it adds a fixed absolute µs/step regardless of the base step wall, its **relative bite shrinks ∝ 1/F** as
density grows the step:

| | dense 1× | dense 16× |
|---|---|---|
| base per-step wall | 16.9 ms | 135 ms |
| creep added per-step after a 1 s-sim run (100k steps × 0.13 µs) | +13 ms | +13 ms |
| **creep's throughput-loss bite over a projected 1 s-sim run** | **~+76 %** | **~+9.6 %** |

**Density softens the deferred decay ~8× (∝1/F):** the creep that costs ~76 % of throughput over a 1 s-sim run at
the dense scale costs only **~10 % at 16×** (same absolute drift, an 8× larger step to hide in). So **long LARGE
runs are far more practical than long dense runs** — the run-length ceiling the creep imposes recedes as the scene
grows. (The cadence-gate already throttled the *gated* graph 100×; this is the residual `fdNuc` carrier, still the
open TornadoVM-internal follow-up — but at scale it is a ~10 % effect, not a wall.) **2× decay probe (`-noprof`,
per-graph ms/step):** `fdNuc` 2.99 (step 4.1k) → 3.48 (8.1k) → 3.86 (12.1k) ⇒ slope **≈0.10–0.12 µs/step**,
matching the dense ≈0.13 µs/step — the slope is **scale-INDEPENDENT** (task-count-bound; `fdNuc`'s 11 tasks don't
grow with N), confirming the ∝1/F relative-bite softening. Every other graph is flat (`fdFil` 10.6→11.6,
`fdBind`/`fdStruct`/`fdInteg` ±0.1) — `fdNuc` is the sole carrier at scale too.

## §7. Flagged / scope notes

- **Measured vs conservative ratio (§4):** the headline curve reports the *measured* GPU/CPU (what the harness
  timed); the genuine compute-bound speedup is the *conservative* ~5× (warm-up-excluded, linear-CPU). The gap is
  the CPU serial runner's super-linear degradation + the short-cap warm-start asymmetry — disclosed, not hidden.
- **Sweep stop at 16× was a budget choice, not a ceiling** — VRAM (1.1 GB) and stability (conservation EXACT)
  leave large headroom; the limiter is steps/s (7.4/s) and CPU comparison cost (0.4/s). 24×+ is runnable on the
  GPU alone (no CPU comparison) if a slower-but-larger morphology run is wanted.
- **The crosslink count rises cleanly with scale** (2→340) and contraction stays positive (the §9.3 live device
  bundling holds at scale) — the device path is not silently dropping the crosslinker payoff as N grows.
- **MEASUREMENT-ONLY:** `-scale`/`-sweep`/`-cpucap` are additive flags; `stepSplit`/`cpuStep`/constituents/viewer
  byte-unaffected; `BoA-v1ref` byte-clean.

## §8. Files

`softbox/FullSystemDemoHarness.java` — additive: `SCALE`/`sweep`/`cpuCap` fields, `-scale`/`-sweep`/`-cpucap`
args, the post-arg-loop size-scaling block, `vramUsedMB`, and `sweepRun` (the short-window probe; reuses
`profStep`/`buildPlanSplit`/`pullRenderState`/the sanity helpers verbatim). `run_scalesweep.sh` (driver).
Log: `RUN_LOGS/2026-06-23_scale_sweep.txt`. Production `stepSplit`/`overnightRun`/`gpuScaleCheck`/`-cpu` untouched.
</content>
