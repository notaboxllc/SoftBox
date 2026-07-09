# DECAY RESET — does a periodic ExecutionPlan reset flush the chained-split per-execute() creep? (MEASUREMENT-FIRST)

**Date:** 2026-06-23. **Branch:** `cadence-gate-fdturn` (the profiler path; production `stepSplit` byte-identical to
`xlink-formation-on`). **Scope:** MEASUREMENT-ONLY probe. A new `-planreset N -planresetmode device|rebuild` path in
`profileRun`/`planReset` (default OFF ⇒ production byte-unchanged). No rate/default/kernel/ordering edits.

```
./run_profile.sh -dense -boxxy 4.4 -profwarm 100 -profsteps 30000 -proflog 2000 -noprof -planreset 8000 -planresetmode rebuild
./run_profile.sh -dense -boxxy 4.4 -profsteps 30000 -proflog 2500 -noprof -planreset 10000 -planresetmode device
```
Logs: `RUN_LOGS/2026-06-23_planreset_*.txt`.

---

## TL;DR — VERDICT: the creep is PLAN-LEVEL (a full rebuild FLUSHES it) — but the rebuild mechanism is NOT production-robust

The PROFILE §4 / SPLIT §8 per-`execute()` creep (fdNuc climbing ~0.105 µs/step, unbounded) is **plan-level
TornadoVM-internal state**: tearing down and rebuilding the `TornadoExecutionPlan` **flushes it cleanly** — a
repeatable sawtooth back to baseline at every reset, **state-preserving and VRAM-bounded**. BUT the repeated
`close()`+rebuild **reproducibly crashes with CUDA 700** (`cuModuleLoadDataEx` PTX-module-load failure) after a few
rebuilds. So the cure exists in principle but the **mechanism is unstable on TornadoVM 4.0.1-dev PTX** — wiring it into
production would trade the decay's graceful slowdown for a hard crash. **⇒ Stage B (production wiring) SKIPPED;** the
robust fix reverts to the §5 launch-count levers (fuse fdNuc / host-side CSR scans / `withCUDAGraph()`) or an upstream
TornadoVM fix (the per-execute accumulation root cause, or stable repeated plan rebuild).

The cheaper in-place reset **`resetDevice()` does NOT flush the creep** — so the accumulation is below the
streams/events/code-cache it clears, in the per-plan device-context bookkeeping that only a full teardown resets.

---

## §1. Two reset mechanisms tested

| API | what it does (TornadoVM 4.0.1-dev source) | residency | flushes creep? |
|---|---|---|---|
| **`plan.resetDevice()`** | `device.clean()` → per-plan `reset(id)`: clean PTX streams + events, release kernel stack frame, **reset the PTX code cache** (forces re-JIT); data buffers **survive** | intact (no host round-trip) | **NO** |
| **full rebuild** | `pullFullState` (device→host, every persisted buffer) → `plan.close()` (freeDeviceMemory) → `new TornadoExecutionPlan(buildPlanSplit)` (FIRST_EXECUTION re-uploads the pulled state) | re-established from host | **YES** (but unstable) |

## §2. `resetDevice()` (mode A) — does NOT flush. Dense scene, reset every 10k:

| step | fdNuc ms/step | note |
|---:|---:|---|
| 7 600 | 2.65 | climbing |
| 10 100 | 2.95 | climbing (reset fires here) |
| 12 600 | **3.09** | **kept climbing across the reset — NO drop** |
| 20 100 | 3.66 | climbing (worse than no-reset 3.17 — the code-cache re-JIT piled on top) |

`resetDevice()` cleans streams/events/code-cache but the creep persists ⇒ the accumulation is NOT there. (It also
forces a full PTX recompile per reset — fdTurnFire spiked 0.03→0.46 — so it is net-negative even ignoring the
non-flush.)

## §3. Full rebuild (mode B) — FLUSHES the creep (clean sawtooth), state-preserving, VRAM-bounded

**Single reset (N=8k):** fdNuc climbs 2.10→2.26→2.44→2.69 (steps 2.1k–8.1k), **reset → 2.14** (10.1k), re-climbs
2.14→2.49→2.60→2.80 (10.1k–16.1k). A textbook sawtooth.

**Multi-reset (N=8k, resets at 8k/16k/24k):** fdNuc resets to ~2.27 at EVERY rebuild —
`2.84→[reset]→2.28→…→2.82→[reset]→2.27→…`. **VRAM flat 473–494 MiB across all rebuilds ⇒ NO per-rebuild leak** (the
single-reset run's apparent 405→514 bump was a transient sampled mid-rebuild; the multi-reset run shows it settles).

**State preservation (the round-trip is lossless):** at step 10100 the rebuild run reads **conc=0.2443, active=1050**
— **bit-matching the no-reset baseline trajectory** at the same step (the device→host→device round-trip is exact for
float32, and the RNG is step-indexed) ⇒ the reset is invisible to physics.

**Per-reset cost:** negligible — the rebuild window's steps/s ≈ its neighbours (88 vs 86/81 @ N=8k), so
pull-full-state + close + rebuild + re-JIT amortized over N=8k steps is sub-millisecond/step.

## §4. The blocker — repeated rebuild is UNSTABLE (reproducible CUDA 700)

Both multi-reset runs crashed **reproducibly at step ~24k** with:
```
[TornadoVM-PTX-JNI] ERROR : cuModuleLoadDataEx -> Returned: 700
PTX to cubin JIT compilation using cuModuleLoadDataEx failed! (700)
[Bailout] Running the sequential implementation.  ⇒  IndexOutOfBoundsException (sequential fallback)
```
- N=8k (resets 8k/16k/24k): 2 clean rebuilds, crashed on the **3rd** (24k).
- N=6k (resets 6k/12k/18k/24k): 3 clean rebuilds, crashed on the **4th** (24k).

CUDA 700 (illegal address) on `cuModuleLoadDataEx` during the rebuild's re-JIT is a **context/module corruption from
repeated plan teardown+rebuild** — a TornadoVM 4.0.1-dev limitation, not a SoftBox kernel bug (the same scene runs
30k+ steps cleanly with no reset). Both crashes landing at step ~24k (different rebuild counts) suggests the rebuild
exposes a latent state-correlated edge somewhere around that point; either way the mechanism is **not robust for
arbitrary-length runs.**

## §5. Verdict + recommendation

- **The §4 creep is plan-level** (confirmed: a full `TornadoExecutionPlan` rebuild flushes it cleanly; `resetDevice()`
  does not). This pins the root cause to per-plan device-context bookkeeping that survives `resetDevice()` and only a
  full teardown clears — consistent with PROFILE §4b hypothesis (B), a TornadoVM-internal per-execution accumulation.
- **But periodic rebuild is NOT a viable production mitigation** on this toolchain: it crashes (CUDA 700) reproducibly
  after ~3 rebuilds. Wiring it would convert the decay's graceful slowdown into a hard crash. **Stage B SKIPPED.**
- **Recommended path forward (unchanged from SPLIT §8 / PROFILE §5):** the launch-count levers — **fuse fdNuc's
  co-indexed kernels**, run the single-thread CSR scans host-side, or `withCUDAGraph()` — which cut both the launch
  floor and (by shrinking the always-run first graph) the creep's carrier, without a plan teardown. The deeper fix is
  upstream: either TornadoVM's per-execute accumulation root cause, or making repeated plan rebuild stable.

## §6. Files
`softbox/FullSystemDemoHarness.java` (additive, measurement-only, default-OFF): `-planreset`/`-planresetmode` args,
`planReset` (resetDevice | rebuild), `pullFullState` + the conditional full-persist UNDER_DEMAND registration in
`buildPlanSplit` (fires ONLY under `-planreset …rebuild`; production byte-unchanged when off), the per-reset cost
print. `BoA-v1ref` byte-clean; production `stepSplit`/`cpuStep`/constituents untouched.
