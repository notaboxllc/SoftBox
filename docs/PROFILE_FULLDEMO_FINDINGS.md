# PROFILE — where does each step of the maximal-composition device path actually go? (MEASUREMENT-ONLY)

**Date:** 2026-06-23. **Branch:** `profile-fulldemo` (instrumentation only, off `xlink-formation-on` @ 371c300).
**Status:** Stage A + B + GPU-utilization DONE; Stage C (decay) — see §4.
**Scope:** MEASUREMENT-ONLY. No kernel / force-law / gather / ordering / default edit. The instrumentation is a
new `-profile` path in `FullSystemDemoHarness` (`profStep`/`profileRun`/`nvidiaStat`, the `-frozen` growth-cap
control) that mirrors `stepSplit` EXACTLY and wraps each section in `System.nanoTime()` + reads the TornadoVM
per-graph profiler (`ProfilerMode.SILENT` → `getDeviceKernelTime`/`getKernelDispatchTime`/`getDataTransfersTime`/
`getTotalBytesCopyIn|Out`). `stepSplit`/`overnightRun`/`gpuScaleCheck`/`-cpu` byte-unaffected; `BoA-v1ref`
byte-clean; production untouched. Candidate fixes are RECOMMENDED (§5), NOT applied.

```
./run_profile.sh -profwarm 100 -profsteps 600 -proflog 0                 # Stage A — default scene
./run_profile.sh -dense -boxxy 4.4 -profwarm 100 -profsteps 1500 -proflog 0   # Stage B — dense scene
./run_profile.sh -dense -boxxy 4.4 -profsteps 120000 -proflog 20000      # Stage C — decay slope (per-graph over the run)
./run_profile.sh -dense -boxxy 4.4 -frozen -profsteps 60000 -proflog 20000    # Stage C control — frozen turnover (flat seg count)
```
Logs: `RUN_LOGS/2026-06-23_profile_*.txt`.

---

## TL;DR — the regime verdict

**LAUNCH / OCCUPANCY-BOUND. Not compute-bound, not transfer-bound, not host-serial.** Of the ~12–15 ms step,
**GPU kernel-compute is only 16–21 %**; **~77–82 % is the per-task launch/sync/interpreter overhead of the
~106 tiny kernels** dispatched each step across the 5 graphs; **host bookkeeping + pull is <1 %**; **per-step
host↔device transfer is 3 KB** (the §1 residency claim is CONFIRMED — there is no hidden full-state copy). The
GPU sits at **~42 % sm-util with ~0 % memory-controller util** during the steady run — idle the majority of the
wall, waiting on the host to launch the next of ~106 sub-millisecond kernels. The anomaly ("20× less work than
DenseContractile but only ~70 steps/s") is explained: this composition's cost is the **kernel COUNT (~106
launches/step), not the kernel WORK** — at the small/default scene each kernel is mostly launch latency, so more
heads barely move the wall.

**Second headline (the 70→19 decay, §4): NOT growth-driven broad-phase** (the overnight guess) — it is a
**state-independent TornadoVM per-execution accumulation** localized to the `fdTurn` graph (verified: a FROZEN
control with all turnover off and the actin pool pinned decays *identically* to the live run; `fdBind`/broad-phase
is dead flat; thermal, VRAM, and JVM-GC all ruled out by measurement). The single biggest lever (§5) is
cadence-gating `fdTurn` to the biochem fire-step (it launches 32 kernels every step but turnover fires only every
100 steps): it cuts the per-step latency floor AND throttles the decay accumulation ~100×.

---

## §1. Stage A — per-step time + transfer budget (the decisive cut)

Default scene (672 active segments, 288 node-shell + 1920 free-minifil heads), 100-step warmup then **600
measured steps**, KIN=1, dt=1e-5. Profiler + `nanoTime` buckets:

| # | bucket | ms/step | % of step |
|---|---|---:|---:|
| — | **composed step wall** (Σ exec-wall + host) | **12.07** | 100 |
| 1 | **GPU kernel-compute** (Σ `getDeviceKernelTime`) | **1.93** | **16.0 %** |
| 2 | per-graph dispatch wall (Σ of the 5 `execute()`) | 11.98 | 99.2 % |
| — |   of which data-transfer time | 0.15 | 1.3 % |
| 4 |   of which **launch / sync / GPU-idle gap** (exec-wall − kernel − xfer) | **9.89** | **82.0 %** |
| 3 | host bookkeeping (counts + pool ledger update) | 0.005 | 0.0 % |
| — | host pull (G0 pool-ledger `transferToHost`) | 0.09 | 0.8 % |
| 5 | **transfer per step** | **copyIn 3.10 KB / copyOut 0.00 KB** | — |
| 6 | **GPU utilization** (nvidia-smi dmon, steady) | **sm ~42 %, mem-ctrl ~0 %** | — |

**The decisive comparison: kernel-compute (1.93 ms) is 16 % of the step; the remaining 84 % is overhead, and the
breakdown attributes it almost entirely to bucket 4 (launch/sync/idle), not transfer (1.3 %) and not host (0.8 %).**

**Per-graph (default scene), averaged over 600 steps:**

| graph | tasks | exec-wall | devKernel | xfer | copyIn |
|---|---:|---:|---:|---:|---:|
| fdTurn (turnover+nucleation) | 32 | **4.14 ms** | **0.000 ms** | 0.00 | 0.00 KB |
| fdBind (binding+grid) | 20 | 1.90 ms | 0.51 ms | 0.04 | 0.79 KB |
| fdStruct (zero/Brownian+structure) | 28 | 2.32 ms | 0.42 ms | 0.05 | 0.98 KB |
| fdFil (chain+seg-gathers) | 13 | 2.24 ms | 0.95 ms | 0.03 | 0.70 KB |
| fdInteg (containment+integrate) | 13 | 1.38 ms | 0.06 ms | 0.03 | 0.63 KB |

**The single most striking line: `fdTurn` (32 tasks) is the largest graph by wall (4.14 ms, ~34 % of the step)
yet has ZERO measurable GPU kernel time** — it is *entirely* per-task launch/interpreter overhead. Its 32 tasks
are mostly single-thread CSR scans (1 GPU thread) + tiny `@Parallel` kernels over the 1536-slot filament
capacity doing trivial work; the GPU compute is negligible, so the whole 4.14 ms is the host-side cost of
issuing 32 kernel launches. Average per-task dispatch cost ≈ 11.98 ms / ~106 tasks ≈ **~115 µs/task** (fdTurn:
4.14 / 32 ≈ 130 µs/task), i.e. a fixed ~8 000 kernel-launches/s ceiling on this toolchain.

**Profiler caveat (load-bearing for reading the table):** `getKernelDispatchTime()` returns **0** on this PTX /
SILENT path (a TornadoVM limitation — the launch-latency counter is not populated), so the host-side launch
latency is NOT separately attributed; it lands in bucket 4 ("launch / sync / GPU-idle gap"). Bucket 4 is
therefore "everything inside `execute()` that is not kernel math or measured transfer" = per-task launch
submission + TornadoVM bytecode-interpreter dispatch + inter-kernel synchronization. The `fdTurn` 4.14 ms-wall /
0 ms-kernel row is the clean proof that this bucket is real and dominant.

## §2. The residency claim (§1 of TASKGRAPH_SPLIT_FINDINGS) — VERIFIED

**Per-step host↔device transfer = 3.10 KB copy-in, 0 KB copy-out** (default and dense identical). This is exactly
the §1 prediction: after warmup the only per-step uploads are the small `EVERY_EXECUTION` counts/rates buffers
(11 tiny `IntArray`/`FloatArray`s re-uploaded in each of the 5 graphs ≈ 3 KB total) and the only per-step
download is the 5 pool-ledger offset `IntArray`s (timed separately as the 0.09 ms "host pull", whose bytes the
profiler captures at execute-time before the explicit `transferToHost`). **There is NO hidden full-state
copy-in/out** (a full-state round-trip would be MB/step). The nvidia-smi **memory-controller utilization is ~0 %**
during the steady run, independently confirming negligible device memory traffic. **The path is NOT
transfer-bound; the split is genuinely device-resident.**

## §3. Stage B — scaling confirms launch-boundedness

Default vs dense (each 1500-step measured window after 100-step warmup):

| scene | active segs | total heads | kernel ms | step ms | launch/idle ms | steps/s |
|---|---:|---:|---:|---:|---:|---:|
| default | 672 | 2 208 | 1.93 | 12.07 | 9.89 | 83 |
| dense   | 1 050 | 5 570 | 3.14 | 14.83 | 11.42 | 67 |
| ratio   | 1.56× | **2.52×** | **1.63×** | **1.23×** | **1.15×** | 0.81× |

**Work rises 2.5× (heads) but the step wall rises only 1.23 %×** — the signature of a launch-bound path: the
~10–11 ms launch/idle overhead is **roughly constant** (it is the ~106 fixed per-step kernel launches,
independent of head count), and kernel time rises **sub-linearly** (1.6× for 2.5× heads — the small kernels do
not saturate the 5070's SMs, so adding heads partly fills idle SM capacity rather than extending wall). The
fraction of the step that is kernel-compute rises (16 % → 21 %) precisely because the work grows while the
launch overhead is fixed — exactly why TASKGRAPH_SPLIT §4 saw the *CPU-relative* speedup grow with scale
(1.0×→2.0×→3.0×) even though the absolute steps/s stays pinned at ~70.

**Kernel-count cross-check (the launch-throughput invariant).** Ring3x3 (~58 kernels/step) runs 143 steps/s ⇒
143 × 58 ≈ **8 290 launches/s**; the full composition (~106 kernels/step) runs ~75 steps/s ⇒ 75 × 106 ≈
**7 950 launches/s**. The two land within ~4 % of the same **~8 000 launches/s** ceiling — i.e. steps/s ≈
8000 / (kernels per step). This matches the per-task ~115–130 µs measured directly in §1. **Launch-boundedness
confirmed: throughput is set by the kernel COUNT, not the work.** A 20×-lighter workload than DenseContractile
(which is a lean ~tens-of-kernels graph at 128k heads, compute-bound) is slower here only because it issues ~106
launches/step.

## §4. Stage C — the 70→19 steps/s decay: NOT growth-driven broad-phase; a state-INDEPENDENT runtime accumulation in `fdTurn`

The TASKGRAPH_SPLIT §6 overnight hypothesis ("growth-driven broad-phase cost") is **REFUTED**. The decay is
real (it reproduces the overnight 70→19 trajectory) but its cause is the opposite of the guess: it is entirely
in the **`fdTurn` graph (gi=0, executed FIRST each step)**, it is **independent of the simulation state**, and
**`fdBind` (the broad-phase) is dead flat**.

**LIVE dense run (`-noprof`, production-faithful per-step `nanoTime`; 120k steps started, stopped at ~100k once the
trajectory was unambiguous):**

| step | steps/s | **fdTurn** | fdBind | fdStruct | fdFil | fdInteg | active | conc µM | smClk |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| ~1.6k (Stage B) | 67 | 5.45 | 2.15 | 2.67 | 3.04 | 1.43 | 1050 | 0.248 | — |
| 40 100 | 53 | 10.63 | 1.87 | 2.16 | 2.88 | 1.19 | 1050 | 0.231 | 2535 |
| 60 100 | 44 | 14.85 | 1.85 | 2.11 | 2.72 | 1.21 | 1050 | 0.221 | 2865 |
| 80 100 | 37 | 19.18 | 1.88 | 2.12 | 2.71 | 1.21 | 1050 | 0.213 | 2842 |
| 100 100 | 31 | 24.00 | 1.96 | 2.19 | 2.76 | 1.27 | 1050 | 0.208 | 2820 |

**`fdTurn` rises 5.45 → 24.0 ms (4.4×); every other graph is flat to ±0.3 ms.** The steps/s drop (67→31) is
entirely `fdTurn`. **`active` segment count is constant (1050)** the whole run.

**The FROZEN control is decisive (`-frozen`: fires=0 ⇒ no growth/depoly/aging/severing, nuc rate 0 ⇒ no births;
conc stays pinned at 0.2483, segment count flat; every kernel still launches every step):**

| step | steps/s | **fdTurn** | fdBind | conc µM | — vs LIVE |
|---:|---:|---:|---:|---:|---|
| 20 100 | 70 | 6.56 | 1.64 | 0.2483 | — |
| 40 100 | 56 | 10.46 | 1.58 | 0.2483 | LIVE 10.63 |
| 60 100 | 44 | **14.92** | 1.62 | 0.2483 | **LIVE 14.85 — identical** |

**FROZEN ≡ LIVE.** With all turnover/biochemistry frozen and the pool pinned, `fdTurn` decays the SAME (60k:
14.92 vs 14.85 ms). This rules out, in turn:
1. **growth-driven broad-phase** — REFUTED: `fdBind` is flat; the decay is in `fdTurn`, not the grid/binding.
2. **turnover / biochemical activity** (aging cascade, severing, growth/split allocator work) — REFUTED: FROZEN
   does none of it yet decays identically.
3. **GPU thermal/clock throttling** — REFUTED: smClk is high and *non-monotonic* (2535–2902 MHz) while `fdTurn`
   rises monotonically; temp plateaus at ~54 °C.
4. **device VRAM leak** — REFUTED: fb memory is flat/declining (491→433 MiB), matching the overnight "VRAM flat".

**What remains: a per-step/per-execution accumulation in the runtime that grows with step COUNT (not state) and
lands on `fdTurn`** — the first `plan.withGraph(0).withGridScheduler(sched).execute()` of each step. It is present
with the profiler OFF (so it is NOT the profiler-result accumulation of §4a). Two candidates remain, disambiguated
by the `-Xlog:gc` run (§4b):
- (A) **JVM garbage-collection pressure** — the per-step Java allocation (5× `withGraph`/`withGridScheduler` plan
  views + 5× `TornadoExecutionResult`/`TornadoProfilerResult` per step) fills the heap; lengthening GC pauses
  land at the start of each step, i.e. inside gi=0 (`fdTurn`)'s `execute()` wall.
- (B) **a TornadoVM-internal per-execution accumulation** scaling with task count — `fdTurn` has the most tasks
  (32 vs 13–28), so internal per-task event/timer state accumulating across executions would slow `fdTurn` first.

### §4a. A profiler-accumulation OOM (instrumentation caveat, found + fixed)
The FIRST decay attempt used `ProfilerMode.SILENT` without `clearProfiles()` and **died with
`java.lang.OutOfMemoryError: Java heap space` at ~step 90k** — TornadoVM retains a profiler result per execution
(400k+ over the run) on the heap. This *amplified* the decay (profiler-on `fdTurn` 11.77 vs `-noprof` 10.63 at
40k) and eventually OOM'd. **Fixed** by `plan.clearProfiles()` each step + a `-noprof` mode (profiler off, pure
`nanoTime`, matching production `stepSplit`). The clean `-noprof` runs above are the production-faithful
measurement. **This is an instrumentation artifact, separate from the real production decay** (which the
profiler-free overnight + these `-noprof` runs both show).

### §4b. GC vs TornadoVM-internal — VERDICT: not GC ⇒ a TornadoVM per-execution accumulation
`-Xlog:gc` over a 50k-step `-noprof` dense run (which reproduced the decay: `fdTurn` 5.80 → 14.09 ms,
~+1.9 ms per 10k steps, linear):

| metric | value |
|---|---|
| total Young GCs | 1 415 |
| **cumulative GC pause** | **2 168 ms over ~890 s wall = 0.24 %** |
| avg pause | 1.53 ms |
| pause growth (early <25 s → late) | 2.44 → 1.52 ms/GC — **NOT lengthening** |
| heap | bounded 105M→65M(376M) — **not growing** |

**GC is NOT the cause** (0.24 % of wall, pauses bounded and *shrinking*, heap bounded — the `-noprof` path has no
heap leak, confirming the §4a OOM was profiler-specific). So **hypothesis (B) stands: a TornadoVM-internal
per-execution accumulation.** The decay is linear in step count at **~0.19 µs/step added to the step cost**
(1.9 ms / 10k steps), landing on `fdTurn`'s `execute()` — the first, largest (32-task), full-state-persisting
graph. Extrapolated: +19 ms/step at 100k, +70 ms/step at 370k ⇒ ~14 steps/s, matching the overnight 70→19.
**Root-causing the specific TornadoVM internal (per-execution event/timer list, or the persist/consume residency
bookkeeping growing across the 400k+ executions) is beyond this measurement task** — it requires reading the
TornadoVM 4.0.1-dev runtime and is the recommended follow-up (§5.II), alongside the cadence-gate that sidesteps
it. It is split-path-specific: the single-graph DenseContractile (`execute()` once/step) is stable at ~50 steps/s
with no decay.

## §5. Recommended candidate fixes (NOT applied — pick together from the budget)

Two distinct problems, two distinct levers:

### (I) The per-step LATENCY floor (Stages A/B): kernel COUNT, not work
The lever is the **~106 kernel launches/step**, not the math (16–21 %) and not transfer (3 KB). In order of leverage:

1. **Gate `fdTurn` to the biochem cadence.** `biochemDeltaT=1e-3` ⇒ at dt=1e-5 turnover fires **every 100
   steps**, yet `fdTurn`'s 32 kernels launch EVERY step. On the 99/100 non-fire steps they are near-no-ops paying
   pure launch overhead. *Conditionally executing `fdTurn` only on fire-steps* removes ~32 launches from 99 % of
   steps — and `fdTurn` is the single largest graph by wall (~34 % of the step at ~0 kernel work). This is a
   dispatch-scheduling change (a 6th, cadence-gated, graph), the single biggest latency win. **NB:** it also
   directly attacks the §4 decay if (B) holds (fewer `fdTurn` executions ⇒ less accumulation), and bounds it if
   (A) holds (less per-step allocation).
2. **Fuse small co-indexed kernels** (zero→Brownian; per-store integrate/derive; the CSR scan/flag/scatter
   chains). Cuts launches without touching arithmetic.
3. **Run the single-thread CSR scans on the host.** The ~12 `csrScan` tasks are 1-GPU-thread launches over small
   offset arrays that already cross to the host per step; a host prefix-sum avoids ~12 launches/fire-step.
4. **`withCUDAGraph()`** (in the API) — capture the per-step launch sequence to amortize the ~115 µs/launch host
   cost; needs validation against the chained-graph residency + the `EVERY_EXECUTION` count re-uploads.

### (II) The long-run DECAY (Stage C): the `fdTurn`/per-step-execution accumulation
- If (A) JVM-GC: **hoist the per-step allocation** — cache the 5 `plan.withGraph(i)` views and the
  `withGridScheduler(sched)` binding once at build time instead of rebuilding them every step; reuse result
  handles. Optionally a larger young gen / a throughput GC. (The single-graph harnesses — DenseContractile — call
  `execute()` once/step and do NOT decay, consistent with the split's 5×/step allocation being the driver.)
- If (B) TornadoVM-internal: find the per-execution reset (akin to `clearProfiles`) or report upstream; gating
  `fdTurn` to fire-steps (I.1) cuts its execution count 100× and largely sidesteps it.

**What is NOT worth doing:** merging the 5 graphs back (the 5 dispatches are negligible vs ~106 kernel launches —
TASKGRAPH_SPLIT §5.2, now quantified 5 vs 106); optimizing transfer (3 KB/step) or host bookkeeping (<1 %);
chasing broad-phase/`fdBind` for the decay (it is flat).

## §5. Recommended candidate fixes (NOT applied — pick together from the budget)

The lever is the **kernel COUNT per step (~106 launches)**, not the math and not transfer. In rough order of
leverage:

1. **Collapse the `fdTurn` 32-task launch storm.** It is ~34 % of the step at ~0 kernel work — pure launch
   overhead. The ~12 single-thread CSR `csrScan` tasks (1 GPU thread each, a full launch apiece) and the many
   tiny per-cap kernels are the worst offenders. Options: (a) fuse the consecutive scan/flag/scatter kernels
   into fewer kernels; (b) run the trivially-serial CSR scans on the host (they touch only small offset arrays
   already crossing per step) instead of paying a GPU launch; (c) gate the whole turnover graph to the biochem
   cadence — turnover only *fires* every `biochemCheckInt` steps, yet `fdTurn`'s 32 launches run EVERY step.
   Conditionally executing `fdTurn` only on fire-steps would remove ~32 launches from the ~99 % of steps that do
   no turnover. **This is likely the single biggest win and is purely a dispatch-scheduling change.**
2. **Fuse small kernels generally.** Many `@Parallel` kernels over the same store iterate the same index space
   back-to-back (zero → Brownian; the per-store integrate/derive pairs). Fusing co-indexed kernels cuts launches
   without touching the arithmetic.
3. **Persisted CUDA graph / launch batching.** TornadoVM exposes `withCUDAGraph()` (seen in the API) — capturing
   the per-step launch sequence as a CUDA graph could amortize the ~115 µs/launch host cost. Needs validation
   that it composes with the chained-graph residency + the per-step `EVERY_EXECUTION` count uploads.
4. **Raise occupancy by scale, not by fixing launches.** Per §3 the path only ties the CPU at small scale; the
   win is already at dense/2×dense. If the production target is large (ring-scale), the launch overhead amortizes
   naturally and the GPU pulls ahead — so a launch-fix matters most for *small/medium* scenes and for the long
   *latency-per-step* (interactivity), less for the eventual large run.

**What is NOT worth doing:** merging the 5 graphs back into fewer (the 5 dispatches are negligible vs the ~106
kernel launches inside them — TASKGRAPH_SPLIT §5.2 already said this, now quantified: 5 vs 106); optimizing
transfer (3 KB/step); optimizing host bookkeeping (<1 %).

## §6. Files

`softbox/FullSystemDemoHarness.java` — additive `-profile`/`-frozen`/`-profwarm`/`-profsteps`/`-proflog` args,
`Acc`, `profStep`, `profileRun`, `nvidiaStat` (all measurement-only; the production `stepSplit`/`overnightRun`/
`gpuScaleCheck`/CPU paths are byte-unaffected). `run_profile.sh`. Logs under `RUN_LOGS/2026-06-23_profile_*`.
On branch `profile-fulldemo` so the instrumentation is cleanly separable from the split.
</content>
</invoke>
