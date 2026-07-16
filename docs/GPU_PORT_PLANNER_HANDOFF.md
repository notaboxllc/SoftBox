# GPU motor-port — planner handoff (consolidated summary)

**Date:** 2026-07-16 · **Author:** concurrent-phase engineering pass · **Branch/worktree:**
`gpu-motor-port` @ `/home/jba/Code/SoftBox-gpu` (off `67892a4`, source synced to the running build).
**Full dossier:** `docs/gpu_port/{README,00–05,FINAL_STATUS_REPORT}.md`. This file is the standalone,
shareable summary — everything a planner needs to schedule the next increment.

---

## 1. What this was
Port the canonical two-body optical-trap motor models — **`calibrated-s2-l40`**, **`explicit-s2-l40`**,
**`fixed-anchor`** (`softbox/MotorModel.java` registry) — from the CPU-only two-body arc to the GPU, while
the authoritative overnight CPU canonical-gliding sweep kept running. **Engineering + validation only —
NO motor mechanics / chemistry / binding / parameters changed.**

Constraint honored throughout: the sweep was **never touched**. Its `softbox/*.class` fingerprint stayed
frozen (`09af9ae7…`, mtime 21:15); a fresh sweep JVM that spawned mid-work loaded those untouched classes
(the isolation working as designed). **Zero GPU invocations** in the concurrent phase — the GPU stayed idle.

---

## 2. Architecture (the one picture the planner needs)

The gliding hot path steps a **`Glide2D` SoA mat** over N motors (not `Cmot` objects). All three models
are **identical through Steps 1–6** and differ ONLY in the per-motor **Step 7** solve:

```
Step 1 cull → 2 bind-gate → 3 Lymn–Taylor chemistry → 4 F8 bondForces →
5 CSR segGather (force→actin) → 6 filament rigid-rod Langevin →
7 PER-MOTOR SOLVE  ← the only model-divergent part
     fixed-anchor    : rigid 2×2 (φ,ψ), pivot pinned          (cheapest)
     calibrated-s2   : analytic 5×5 movable pivot (softplus)  ~1.34 µs/motor/step, float32-friendly
     explicit-s2     : 14-DOF implicit beam Newton step        ~58 µs/motor/step, DOUBLE, the cost sink
```

⇒ **The GPU port = reuse the already-device-validated systems for Steps 1–6 + one new Step-7 kernel per
model**, dispatched by `MotorModel` id **at plan-build time** (no runtime cross-model branch), with all
constants packed from the frozen registry (never forked; `==`-guarded by `assertFrozenParamsConsistent`).

**Key enablers (make this port low-risk):**
- Force→actin is already an **atomics-free CSR gather** → bit-identical CPU↔GPU (reuse verbatim; use the
  parallel `csrHistogramChunked` variant, not the single-thread scan).
- RNG is **counter-based wang-hash** keyed `(motor, step, seed, salt)` → the random stream is bit-identical
  CPU↔GPU by key. So isolated-step gates are exact/tight-float; only long chaotic gliding needs
  ensemble-within-SEM + the CPU-double basin arbiter (per the CLAUDE.md GPU-number-trust rule).

---

## 3. Delivered (all CPU-only, verified)

| artifact | what | status |
|----------|------|--------|
| `docs/gpu_port/00–05 + README + FINAL_STATUS_REPORT` | provenance, Phase-1 call-graph, Phase-2 SoA layout+dispatch, gates+tolerances, force/measure/perf strategy | ✅ |
| `softbox/MotorReplayHarness.java` | CPU deterministic replay + fixture generator; `-genfixtures / -replay / -validatekernels` | ✅ |
| 30 golden fixtures (`RUN_LOGS/gpu_port_dev/fixtures/`) | 10/model across binding/stroke/strain/beam regimes | ✅ determinism **30/30 bit-identical** |
| `softbox/TwoBodyGpuKernels.java` | 3 `@Parallel` kernels: `fixedStep`/`calibratedStep` (float32), `explicitBeamStep` (double) | ✅ compiles |
| kernel CPU-runner validation | kernels run as sequential loops, diffed vs golden fixtures | ✅ **T3 20/20 PASS (float32, maxΔ~1e-8); T4 explicit 10/10 bit-identical (Δ=0)** |
| `scripts/gpu_port/bench_*.sh` + `_guard.sh` | benchmark scripts, **self-refuse while the sweep runs** | ✅ prepared, not run |

Note: the isolated-step gate **caught a real transcription point** — `s2Solve` uses `eup` (not `econv`)
as the generalized-force axis; fixing it made the beam kernel bit-identical, with no tolerance loosened.
(This is why the replay-gate approach matters: it catches port bugs before any GPU run.)

---

## 4. Findings that affect planning

1. **Explicit beam perf is the crux.** Measured cost **10,570 s wall / simulated-second** (≈2.9 h/sim-s);
   the beam solve is ~95% of it, via a **nested finite-difference tangent (~750 acos-evals/motor/step),
   all double.** On the RTX 5070, **FP64 ≈ 1/64 FP32**, and only ~400 motors are active/step (under-fills
   the device). ⇒ the faithful double kernel is expected **~3–5×**, likely **short of the 10× target**.
   The identified lever is an **analytic stretch+bending Jacobian** — the exact derivative of the *same*
   beam energy (NOT a surrogate) — which removes both FD layers and unlocks float32. **This is the main
   remaining engineering item and the biggest scope/decision for the explicit model.**
2. **Calibrated is easy and low-risk.** Analytic 5×5, float32, registry-gated; its GPU win is mostly
   occupancy/launch-amortization (step-all-N + batch seeds), not arithmetic.
3. **Occupancy:** ~400 active of 4000 motors/step → step-all-N on device + batch multiple seeds to fill it.
4. **Chemistry validation is a full-loop gate**, not an isolated-step gate (an isolated mechanics step
   doesn't call `NucleotideCycleSystem` — nuc state only selects the F8 rest angle).

---

## 5. Blocked only on hardware (postponed until the sweep frees the GPU)
Device execution: **T2** (CSR-gather bit-identity), **T6** (ensemble gliding within SEM + CPU-double
arbiter), **T7** (half-dt consistency), and **all benchmarks** (beam microbench over thousands of configs;
end-to-end gliding). The guarded scripts self-refuse until the sweep ends.

---

## 6. Recommended next increment sequence (for the planner)

**A. Calibrated-s2-l40 → GPU (low risk, do first).**
1. Wire the Step-7 `calibratedStep` kernel + Steps 1–6 device systems into one gliding TaskGraph
   (device-resident; online-reduction kernel for the measurements).
2. Add the registry→device param packer (T0 exact-`==`) and the `gpuSupported()` `-gpu` gate.
3. Post-sweep: T6 ensemble vs the CPU sweep (within SEM) + CPU-double arbiter, T7 half-dt.
4. **Promote `calibrated-s2-l40 -gpu`** once A3 passes.

**B. Explicit-s2-l40 → GPU (gated; the real work).**
1. Phase-5 beam microbench: run `explicitBeamStep` (double) on the GPU over thousands of fixtures;
   report **distributions** (node/pose/contour/energy/failure-rate) within T4, failure ≤ CPU rate.
2. **Decision point (see §7): analytic Jacobian?** If pursued, re-pass Phase-5 at T4′ (float32) and
   prove it's the exact Jacobian, then re-benchmark.
3. Integrate + T6 ensemble. **Keep `-gpu` REFUSED until B1+B3 pass.** Until then the CPU sweep stays
   the sole reference and must not be overwritten.

**C. Fixed-anchor:** trivially GPU-able as a byproduct of A (regression baseline).

---

## 7. Open decisions the planner should make
1. **Explicit beam: faithful double-FD (≈3–5×, correctness-first) vs the analytic-Jacobian float32
   rewrite (path to ≥10×)?** The rewrite is more engineering but is exact (not a surrogate). Recommend:
   ship the faithful double kernel first (it's done + bit-identical on CPU), measure its real GPU speedup,
   then decide the Jacobian based on the measured gap to 10×.
2. **Is 10× a hard requirement for explicit, or is any speedup with correctness acceptable** given the
   `calibrated` surrogate already exists as the cheap production path (explicit is the *validation*
   reference)? This changes whether B2 is in-scope now or deferred.
3. **Commit the dossier + code to `gpu-motor-port`** now, or hold? (Currently uncommitted.)
4. **Seed-batching / step-all-N** occupancy strategy — approve for the calibrated integration (A1)?

---

## 8. Invariants any continuation must keep
Never build/run/write in `/home/jba/Code/SoftBox` or under `RUN_LOGS/twobody_canonical_gliding/`. GPU
constants from the registry, never forked. `-gpu` for a CPU-only model FAILS clearly (no silent fallback/
swap). Preserve the wang-hash salts and the EA/EI arithmetic form bit-for-bit. Force→actin via the CSR
gather. **Never substitute an approximate mechanic for the explicit beam to gain speed.**
