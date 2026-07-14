# Fine-timestep velocity-clamp performance results

## Outcome

Four default-path optimizations were retained. All remove work that is provably redundant for exact-zero
coefficients or for an already-scheduled second geometry publication. They do not change force laws, parameters,
binding eligibility/order, kinetics, RNG keys or active draws, accumulation order, update order, timestep
semantics, clamp timing, or model defaults.

On the repeated 8,000-step, `dt=2.5e-6` benchmark, mean wall time fell from 56.63 s to 24.01 s: **2.359x
faster**. On the 32,000-step scientific case, wall time fell from 145.76 s to 93.58 s: **1.558x faster**. The
entire long-case stdout is byte-identical before and after.

## Retained changes

### 1. Do not compute disabled J1/J2 angular springs

`DIRSWING` sets the legacy J1 angular coefficient to exactly zero, and canonical J2's angular coefficient is
also exactly zero. `MotorJointSystem.joints` previously still formed cross products, normalized them, called the
two-Newton-step `accurateAcos`, and multiplied the result by zero. It now takes that angular path only when its
coefficient is nonzero. Config-1's active Hookean J1 path is unchanged.

This is Level 1 for the deterministic CPU workflow: all short output was byte-identical. It reduced mean short
wall time from 56.63 s to 28.747 s, a **1.970x** speedup.

### 2. Evaluate each articulated motor's two joints once

The old race-free traversal launched one iteration per sub-body, so each endpoint independently reloaded all
three poses and recomputed its shared joint. The new traversal launches one iteration per motor, loads the rod,
lever, and head poses once, evaluates J2 and J1 once each, and scatters equal-and-opposite contributions to that
motor's three disjoint slots. No atomics or cross-motor writes were introduced. Each body's additions retain the
old J2-then-J1 order.

This is Level 1: stdout remained byte-identical. Mean short wall time fell from 28.747 s to 25.887 s,
**1.110x incremental** and **2.188x cumulative**.

### 3. Publish only geometry needed before the coupled-implicit correction

With `XB_IMPLICIT2`, both motor and filament bodies are fully re-derived after the coupled head/site center
correction. Before that correction, the intervening kernels consume the integrated center and the
re-orthogonalized `uVec`/`yVec`, but not the newly computed `zVec` or endpoints. The first full derive was replaced
by `DerivedGeometrySystem.orthogonalizeY`, which is the exact arithmetic prefix of `derive`. The second derive
still publishes all final geometry, and non-implicit paths still use the original full derive.

This is Level 1: stdout remained byte-identical. Mean short wall time fell from 25.887 s to 25.000 s,
**1.035x incremental** and **2.265x cumulative**.

### 4. Skip RNG transforms for bodies with both Brownian scales zero

`BrownianForceSystem` now detects an exactly zero translational and rotational scale before hashing and
Box-Muller transforms, and explicitly writes zero force/torque. The random generator is a stateless function of
body slot, step, seed, and subsystem key; there is no stream position to advance. Active bodies execute the
unchanged code. This benefits the zero-Brownian lever bodies and the prescribed zero-temperature clamp filament.

This is Level 1: stdout remained byte-identical. Mean short wall time fell from 25.000 s to 24.010 s,
**1.041x incremental** and **2.359x cumulative**.

## Incremental timing

Each short stage has three fresh JVM repetitions.

| Stage | Mean wall (s) | SD (s) | CV | Steps/s | Incremental speedup | Cumulative speedup | Validation |
|---|---:|---:|---:|---:|---:|---:|---|
| baseline | 56.630 | 3.446 | 6.08% | 141.61 | - | 1.000x | baseline hash |
| zero angular coefficients | 28.747 | 0.360 | 1.25% | 278.32 | 1.970x | 1.970x | Level 1 |
| one joint traversal/motor | 25.887 | 0.345 | 1.33% | 309.08 | 1.110x | 2.188x | Level 1 |
| partial first derive | 25.000 | 0.095 | 0.38% | 320.00 | 1.035x | 2.265x | Level 1 |
| zero-scale Brownian guard | 24.010 | 0.209 | 0.87% | 333.21 | 1.041x | 2.359x | Level 1 |

Final short throughput is 799,707 motor updates/s and 8.797 million brute-reach pair tests/s.

The longer-case result is less affected by startup/JIT noise but also shows a smaller total speedup:

| 32,000-step case | Wall (s) | User / system (s) | Steps/s | Motor updates/s | Max RSS |
|---|---:|---:|---:|---:|---:|
| baseline, v=14 | 145.76 | 151.72 / 0.13 | 219.54 | 526,894 | 183,436 KiB |
| retained changes, v=14 | 93.58 | 97.85 / 0.09 | 341.95 | 820,688 | 228,200 KiB |
| retained changes, v=12 | 93.84 | 97.98 / 0.11 | 341.01 | 818,414 | 225,048 KiB |

Peak RSS did not improve: the three-run short mean increased from 188,024 to 225,516 KiB (+19.9%), and the
long v=14 observation increased by 44,764 KiB (+24.4%). None of the retained patches adds a persistent array or
per-step allocation, the final JFR still reports no GC, and its only sampled allocation remains the startup drag
tensor `double[]`. The RSS increase is therefore not accompanied by evidence of Java heap churn, but it is a
measured regression and should not be hidden; native/JIT/code-cache variability is a plausible explanation, not
a demonstrated one.

## Scientific and deterministic validation

### Byte identity and observables

Every three-repetition stage produced the exact baseline `FVROW`. The final 32,000-step v=14 stdout compares
equal with `cmp` and has the same SHA-256 on both versions:

```text
3111b6dadb9c09b09a6e5610a8e988dd2e379e36d8caa625f026ac1184a4cd2f
```

Consequently the printed event sequence summaries and every ordinary diagnostic-off result are Level 1, not a
statistical-equivalence claim.

| Observable, 32k steps at v=14 | Before | After | Difference |
|---|---:|---:|---:|
| `fbar_avail` | -0.05791 | -0.05791 | 0 |
| `fbar_bound` | -0.07148 | -0.07148 | 0 |
| mean available / raw-reachable / bound | 5.86 / 4.97 / 4.746 | 5.86 / 4.97 / 4.746 | 0 / 0 / 0 |
| `Jattach` | 1389.089 | 1389.089 | 0 |
| attachment impulse | -0.04099 | -0.04099 | 0 |
| completed episodes / measured binds | 438 / 434 | 438 / 434 | 0 / 0 |
| lifetime / stroke rate | 0.5779 ms / 1718.4 | 0.5779 ms / 1718.4 | 0 / 0 |
| work/attachment | -0.5738 pN nm | -0.5738 pN nm | 0 |
| J1 angle / samples | 59.71651 deg / 76,488 | 59.71651 deg / 76,488 | 0 / 0 |
| x-bind mean / SD | 0.03500 / 0.00004 nm | 0.03500 / 0.00004 nm | 0 / 0 |

There is no standalone RNG-stream checksum in this route. The active RNG formulas are untouched, and full stdout
identity plus identical attachment, detachment, episode, binding, and completion counts gives an executed event
sequence check for the deterministic benchmark. The skipped Brownian bodies have exactly zero scale and a
stateless keyed generator, so their unused draws cannot affect any active body.

The second fine-timestep endpoint also matches the pre-change condensed row in
`RUN_LOGS/v0fine/pass1/dt2.5e-6_v12_s0.txt` exactly:

| Seed-0 endpoint at `dt=2.5e-6` | `fbar_avail` | `fbar_bound` | mean bound | episodes | impulse |
|---|---:|---:|---:|---:|---:|
| v=12, 32k | +0.03518 | +0.04285 | 4.889 | 425 | +0.03094 |
| v=14, 32k | -0.05791 | -0.07148 | 4.746 | 438 | -0.04099 |

Thus the seed-0 force sign and local V0 bracket remain **12 to 14 um/s**. No broader statistical tolerance was
used or silently accepted.

### Harness checks

| Check | Result |
|---|---|
| `./scripts/build.sh` | clean build; only the two pre-existing varargs warnings in `DiffusionHarness` and `RollSpringHarness` |
| `./scripts/run_motorbody.sh -cpu 1200` | articulated motor validation PASS |
| `./scripts/run_xbridge.sh -cpu 1200` | exact gather and cross-bridge validation PASS |
| `./scripts/run_motorbody.sh 1200` | CPU and GPU articulated motor validation PASS; aggregate deltas below 1 nm / 1 deg gates |
| `./scripts/run_stroke.sh` | dwell, catch, 6.96-nm unloaded stroke, GPU/CPU direction, force, occupancy, and checkpoint gates PASS |
| canonical CPU gliding probe, 800 steps | exact recorded `velFitX=4.583`, `netSteady=5.589`, `netX=-5.264`, `avgBsteady=5.400` |
| canonical GPU gliding probe, 800 steps | exact recorded `velFitX=4.584`, `netSteady=5.590`, `netX=-5.265`, `avgBsteady=5.400` |
| fine v=14, 8k and 32k | complete stdout byte-identical |

The attempted long `run_canongliding.sh -config1diag 12000` diagnostic ended in NaNs and was not counted as a
validation gate; no matched pre-change run was established for that separate phase-2 Config-1 diagnostic. Its
active J1 code is covered by the passing articulated-motor and stroke harnesses. The retained default
SPHEREHEAD/AXLOCK/DIRSWING workflow does not enable the `CANONICAL`/Config-1 variant flag.

## Final profile and remaining bottlenecks

The final short JFR contains 1,901 execution samples over 24 s. Caller-attributed shares are:

| Subsystem | Final CPU samples | Interpretation |
|---|---:|---|
| rigid-body integration | 23.36% | largest remaining arithmetic/SoA kernel; active physical update |
| articulated joints | 20.41% | reduced substantially, but active connection and lever-arm mechanics remain |
| clamp measurement/other | 14.73% | required per-step scientific accumulation and episode tracking |
| active/partly active Brownian force | 9.15% | active rod/head RNG and transforms remain scientifically required |
| brute reachable search | 7.31% | exact 11-segment ordered candidate scan |
| full derive | 5.68% | required final endpoint/frame publication |
| first-pass y orthogonalization | 3.68% | required integrated frame correction before the implicit solve |
| anchor | 3.73% | active structural mechanics |
| F8/F9/AXLOCK bond force | 3.26% | occupancy-dependent canonical force kernel |
| publish/cycle/bind/apply/chain/gather/register | 8.69% | individually small required kernels |

Leaf samples remain dominated by foreign-memory access checks: `MemorySegment.checkBounds` alone is 39.19%,
with session checks another 6.42%. This cost is spread across the physical and analysis kernels.

A heap-backed Tornado array experiment was measured and rejected. Although an isolated scalar-access probe was
about 3.1x faster, the full short benchmark regressed from the post-joint mean of 25.887 s to 28.293 s (-9.3%,
three runs, CV 0.25%). It was fully reverted. Replacing the repository's device-compatible SoA backend on the
strength of a microbenchmark would therefore be unjustified.

Logging, string formatting, Java allocation, and GC are not meaningful remaining targets in the ordinary
diagnostic-off route. There is no per-step formatting, no recorded GC, and no sampled steady-loop allocation.

## Recommendations

### Safe to retain

- exact-zero J1/J2 angular-path guards;
- one race-free joint evaluation per motor;
- first-pass y-only frame orthogonalization when a full post-implicit derive is guaranteed;
- exact-zero Brownian-scale guard for stateless RNG bodies;
- the benchmark and JFR scripts for future 5, 2.5, and 1.25 us reference runs.

These have preserved default flags and achieved Level-1 CPU velocity-clamp output validation. Relevant CPU/GPU
harnesses also pass.

### Optional work requiring broader validation

- A different TornadoVM/JDK array-access implementation could target the foreign-memory checks, but the attempted
  heap backend was slower end-to-end and should not be restored.
- Parallel per-motor scientific measurement or reductions would change floating-point accumulation order. It
  should be default-off and treated as Level 3 until paired multi-seed observable tolerances are preregistered.
- A GPU-resident clamp/measurement path could reduce latency but would need an explicit design for host clamp
  overwrites, stable candidate and gather ordering, keyed RNG equivalence, and exact diagnostic reductions.
- Independent seed/velocity processes may be scheduled concurrently to reduce ladder makespan without changing a
  simulation, provided machine contention is accounted for in performance claims.

### Bottlenecks that encode scientific semantics

Do not accelerate the remaining active Brownian, nucleotide, binding, F8/F9/AXLOCK/DIRSWING, joint/anchor,
integration, implicit-correction, or clamp-measurement work by changing formulas, draw counts for active bodies,
candidate order, force accumulation order, update timing, or measurement cadence. The 11-segment brute search is
not large enough to justify a semantic-risky spatial index in this workload. The stable CSR motor ordering and
the post-step clamp overwrite are part of the defined reference calculation.

## Reproduction

```text
scripts/benchmark_vclamp.sh short 3
scripts/benchmark_vclamp.sh science 1
OUT_DIR=RUN_LOGS/performance/science_v12 VELOCITY=12 scripts/benchmark_vclamp.sh science 1
OUT_DIR=RUN_LOGS/performance/dt5 DT=5e-6 STEPS=16000 scripts/benchmark_vclamp.sh science 3
OUT_DIR=RUN_LOGS/performance/dt1.25 DT=1.25e-6 STEPS=64000 scripts/benchmark_vclamp.sh science 3
scripts/profile_vclamp_jfr.sh
```

Each benchmark output directory contains the exact escaped command, commit, Java version, hardware/kernel
metadata, per-run stdout, `/usr/bin/time` data, and a tab-separated summary.

