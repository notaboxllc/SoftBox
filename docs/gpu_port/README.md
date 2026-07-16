# GPU motor-port — engineering & validation dossier

Concurrent-phase work for porting the canonical two-body motor models (calibrated-s2-l40,
explicit-s2-l40, fixed-anchor) to the GPU, done in the isolated worktree `/home/jba/Code/SoftBox-gpu`
(branch `gpu-motor-port`) while the authoritative CPU canonical-gliding sweep runs untouched in
`/home/jba/Code/SoftBox`.

## Documents
- **00_PROVENANCE_AND_ISOLATION.md** — the running sweep's exact provenance (procs, hashes, cmdlines,
  env, outdirs), the classpath hazard + mitigation, the isolated worktree/build setup, and the per-model
  CPU cost anchors.
- **01_DESIGN_FACTS.md** — foundational facts locked early: the registry is the single source of truth;
  RNG is counter-based (bit-exact CPU≡GPU achievable); the TornadoVM kernel/TaskGraph idiom; constants.
- **02_PHASE1_CPU_CALLGRAPH.md** — the per-step call graph & data-flow for all three models (shared
  Steps 1–6 + model-divergent Step 7), state inventory, RNG, force-onto-actin, blockers, per-phase cost.
- **03_PHASE2_GPU_LAYOUT_AND_DISPATCH.md** — the device SoA buffer layout, model-dispatch design (constants
  from the registry, `gpuSupported()` gate, no runtime cross-model branch), kernel decomposition, precision.
- **04_VALIDATION_GATES_AND_TOLERANCES.md** — preregistered gates T0–T8 with numerical tolerances; the
  per-phase gate lists; stop/bail conditions.
- **05_STRATEGY_FORCE_MEASURE_PERF.md** — force-accumulation strategy (A CSR-gather vs B atomics),
  measurement (online reductions), performance targets + CPU/GPU wall-time estimates.
- **GPU_INVOCATION_LOG.md** — every GPU call during the concurrent phase (empty until the sweep frees the HW).

## Code (isolated worktree only)
- `softbox/MotorReplayHarness.java` — CPU deterministic replay harness + fixture generator (Phase 3).
- `softbox/BeamMicrobenchHarness.java` + calibrated/beam GPU kernels — Phase 4/5 (kernel-authoring pass).
- `scripts/gpu_port/` — guarded benchmark scripts (`bench_beam_microbench.sh`, `bench_gliding_gpu.sh`,
  `_guard.sh`). **The guard REFUSES to run while the sweep is active or from the sweep tree or on a busy GPU.**
- `RUN_LOGS/gpu_port_dev/` — all dev outputs (never the sweep's `RUN_LOGS/twobody_canonical_gliding/`).

## Invariants (do not violate)
1. Never build/run/write in `/home/jba/Code/SoftBox` or under its `RUN_LOGS/twobody_canonical_gliding/`.
2. GPU constants are generated/validated from `MotorModel`, never forked.
3. `-gpu` for a CPU-only model (explicit until promoted) FAILS clearly — never a silent fallback/swap.
4. Preserve the wang-hash RNG salts and the EA/EI arithmetic form bit-for-bit.
5. Force-onto-actin via the atomics-free CSR gather (bit-identical to CPU).
6. Never substitute an approximate mechanic for the explicit beam to gain speed.
