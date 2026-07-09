# [PTX] Linear per-`execute()` slowdown on a chained multi-graph plan (cleared only by full rebuild, not `resetDevice()`), and `cuModuleLoadDataEx` CUDA 700 after a few `close()`+rebuild cycles

> Paste-ready draft for https://github.com/beehive-lab/TornadoVM/issues — file from a GitHub account.

## Summary

On a long-running, device-resident **chained multi-`TaskGraph`** `TornadoExecutionPlan` (graphs share buffers via
`persistOnDevice`/`consumeFromDevice`, executed in a per-step loop), we observe two linked symptoms on the **PTX
backend**:

1. **A linear per-`execute()` time accumulation** — each step gets slowly slower (≈0.1 µs/step added, **no
   plateau**) — that is **state-independent** (a frozen workload decays identically) and **cleared by a full plan
   teardown + rebuild**, but **NOT by `resetDevice()`**. This places the accumulation *below* the streams / events /
   code-cache that `resetDevice()` clears, in per-plan / device-context bookkeeping that only a full plan
   reconstruction resets.

2. Using that rebuild as a workaround, **repeated `plan.close()` + `new TornadoExecutionPlan(...)` reproducibly
   fails with `cuModuleLoadDataEx -> 700` (CUDA_ERROR_ILLEGAL_ADDRESS)** during the new plan's PTX JIT, after only
   ~3 rebuild cycles — so the rebuild is not a viable long-run mitigation.

## Environment

- TornadoVM **4.0.1-dev**, **PTX backend**
- GPU: NVIDIA GeForce RTX 5070, driver 595.71.05
- CUDA toolkit nvcc 11.5 (PTX JIT via the driver)
- Java: OpenJDK 21.0.11 (`--enable-preview`)
- OS: Ubuntu 22.04.5 LTS

## Workload shape

One `TornadoExecutionPlan` built from **6 chained `ImmutableTaskGraph`s** (~74–106 small kernels/step total).
Buffers are uploaded `FIRST_EXECUTION` in the first graph that uses each, then carried across graphs and across
steps with `persistOnDevice` (producer) / `consumeFromDevice` (consumer); only a few small `IntArray` offsets are
pulled `UNDER_DEMAND` per step. Per step we call, for `gi` in 0..5:

```java
plan.withGraph(gi).withGridScheduler(sched).execute();
```

The plan is device-resident and correct throughout (conservation invariants hold; VRAM flat at ~0.4 GiB; per-step
host↔device transfer ~3 KB). The only problem is the throughput creep.

## Symptom 1 — linear per-`execute()` creep, NOT cleared by `resetDevice()`

Per-graph wall time, measured with `System.nanoTime()` around each `execute()` (profiler OFF), on a fixed-size
scene over a long run. The first always-executed / full-state-persisting graph climbs linearly; every other graph
is flat:

| step   | first-graph ms/step | others |
|-------:|--------------------:|--------|
| 10 100 | 2.20 | flat |
| 20 100 | 3.17 | flat |
| 30 100 | 4.31 | flat |

≈ **+0.1 µs per step, with no plateau** (extrapolates to a large slowdown over 10⁵–10⁶ steps; we have seen a
single long run fall from ~70 to ~19 steps/s over ~370k steps).

**Ruled out by measurement** (so this is not application state or environment):
- **GC** — `-Xlog:gc`: cumulative pause 0.24% of wall, pauses bounded and *shrinking*, heap bounded.
- **VRAM leak** — device memory flat/declining over the run.
- **Thermal/clock** — SM clock high and non-monotonic while the per-step time rises monotonically; temp plateaus.
- **Application state** — a *frozen* workload (all data-dependent work disabled, buffers pinned, but every kernel
  still launched every step) decays **identically** to the live workload.
- **Profiler retention** — reproduces with the profiler OFF (`ProfilerMode` not enabled); with `SILENT` enabled
  it is *worse* and OOMs unless `clearProfiles()` is called each step (a separate, known retention).

**`resetDevice()` does NOT clear it.** Calling `plan.resetDevice()` periodically (which, on PTX, cleans the
streams + events + resets the code cache + releases the kernel stack frame) leaves the creep untouched — the
first-graph time climbs straight across the reset (e.g. 2.95 → 3.09 → … → 3.66 ms, no drop), plus it now pays a
full PTX recompile each reset. So the accumulating state is **below** what `resetDevice()` resets.

## Symptom 2 — a full plan rebuild clears the creep, but repeated rebuild crashes `cuModuleLoadDataEx` 700

A full teardown + rebuild **does** clear it cleanly — a sawtooth back to baseline at each rebuild:

```java
// at each reset boundary (every N steps):
//   1. pull the full device state to host (every persisted buffer, UNDER_DEMAND)
//   2. plan.close();                       // AutoCloseable → freeDeviceMemory
//   3. plan = new TornadoExecutionPlan(g0.snapshot(), …, g5.snapshot());  // FIRST_EXECUTION re-uploads
```

The first-graph time drops back to its baseline (~2.1–2.3 ms) at every rebuild, state is preserved bit-exactly
(the round-trip is lossless and our RNG is step-indexed), and **VRAM stays flat across rebuilds (no per-rebuild
leak)**. So the creep is plan-level state that a fresh plan does not inherit.

**But repeated rebuild reproducibly crashes** during the *new* plan's PTX JIT, after ~3 rebuild cycles:

```
[TornadoVM-PTX-JNI] ERROR : cuModuleLoadDataEx -> Returned: 700
PTX to cubin JIT compilation using cuModuleLoadDataEx failed! (700)
[Bailout] Running the sequential implementation.
  ⇒ java.lang.IndexOutOfBoundsException (in the sequential-Java deopt fallback)
```

Two configurations both died on the rebuild that lands at ~24k steps: reset-every-8k crashed on the 3rd rebuild,
reset-every-6k on the 4th. CUDA 700 is `CUDA_ERROR_ILLEGAL_ADDRESS`, which is sticky — it suggests the CUDA
context/module state is corrupted by the repeated `close()` + rebuild (e.g. module-handle or context-resource
churn), independent of our kernels (the same scene runs 30k+ steps cleanly with no reset).

## Expected vs actual

- **Expected:** per-`execute()` wall time on a fixed chained plan is stable over arbitrarily many steps; and
  `close()` + `new TornadoExecutionPlan(...)` can be repeated indefinitely.
- **Actual:** per-`execute()` climbs linearly with step count (Symptom 1), and repeated plan rebuild corrupts the
  CUDA context after ~3 cycles (Symptom 2).

## Questions for the maintainers

1. Is there per-plan / per-`ExecutionPlan` bookkeeping on the PTX path (event lists, per-execution metadata,
   bytecode-interpreter state, device-context registration) that grows with the number of `execute()` calls and is
   **not** released by `resetDevice()`? Is there an intended API to flush it in-place?
2. Is repeated `plan.close()` + `new TornadoExecutionPlan(...)` expected to be safe on PTX, or does it leak CUDA
   module/context handles (the `cuModuleLoadDataEx` 700)?

## Repro notes

We can reproduce both symptoms from our application harness (a chained 6-graph residency loop) and have the
measurement logs above. A **minimal standalone repro is not yet built** — happy to build one (a synthetic N-graph
persist/consume plan executed in a long loop, with optional periodic `resetDevice()` / `close()`+rebuild) if that
would help triage.
