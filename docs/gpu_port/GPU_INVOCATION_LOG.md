# GPU-invocation log (concurrent phase — while the production sweep runs)

Every GPU call made during the concurrent phase is logged here with runtime, memory, and observed
effect on the production sweep. Policy: tiny smoke tests only; abort if GPU util/mem climbs or the
sweep slows.

**Concurrent-phase total GPU invocations: ZERO.** All Phase 0–5 work (provenance, maps, design, replay
harness + 30 fixtures, 3 GPU kernels, kernel validation) was done CPU-only — the kernels were validated on
the CPU runner (`@Parallel` methods as sequential loops), no `@tornado-argfile`, no device execution. The
GPU stayed idle (0–1 % util, ~184–218 MiB desktop-only) throughout. Device execution (T2/T6/T7, all
benchmarks) is postponed until the sweep frees the hardware; the guarded scripts self-refuse until then.

| timestamp | invocation | GPU util before/after | GPU mem | runtime | sweep effect | notes |
|-----------|------------|-----------------------|---------|---------|--------------|-------|
| — | (none) | 0–1 % throughout | ~184–218 MiB | — | none | CPU-only: builds (nice -19 javac), CPU-runner kernel validation |
