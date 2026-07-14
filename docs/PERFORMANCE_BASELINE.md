# Fine-timestep velocity-clamp performance baseline

## Provenance and environment

The baseline is the unmodified branch tip `df310f5aa5cfd2ced851ae7ea01014d20e179ca8` on
`dt-convergence-study`, measured on 2026-07-12. Raw benchmark records are under
`RUN_LOGS/performance/baseline_short` and `RUN_LOGS/performance/baseline_science`.

| Item | Value |
|---|---|
| Host CPU | Intel Core i9-9900K at 3.60 GHz, 1 socket, 8 cores, 16 hardware threads |
| Memory | 31 GiB |
| OS | Ubuntu 22.04.5, Linux 5.15.0-181-generic x86-64 |
| Java | OpenJDK 21.0.11+10, 64-bit Server VM |
| TornadoVM | 4.0.1-dev PTX distribution |
| Relevant JVM flags | `-server`, JVMCI enabled, preview enabled, Parallel GC, `-Xmx6G`, `-Dtornado.tvm.maxbytecodesize=65536` |
| Simulation execution | one CPU main thread; compiler/JFR helper threads account for CPU totals above 100% |

The wrapper loads the TornadoVM module/export argfile even though `-vclamp` deliberately calls the sequential
CPU `stepOrig` path. No GPU work occurs in the measured clamp runs. The full command and environment are captured
by `scripts/benchmark_vclamp.sh` in each output directory's `environment.txt`.

## Benchmark definitions

The short profiling case was repeated three times:

```text
./scripts/run_gliding.sh -matbox 50 -density 2000 -dt 2.5e-6 -seed 0 -vclamp 14 8000
```

This is 0.020 s simulated time, 2,400 motors, 7,200 articulated motor bodies, and an 11-segment clamped filament.
The scientific validation case used the same state and flags for 32,000 steps (0.080 s):

```text
./scripts/run_gliding.sh -matbox 50 -density 2000 -dt 2.5e-6 -seed 0 -vclamp 14 32000
```

`/usr/bin/time` supplied wall time, process CPU time, and maximum RSS. Throughput is wall-time based. “Reach pair
tests/s” counts the exact 2,400 x 11 brute-reach tests per step; it does not include candidate rechecks in
`bindNearest`.

## Timing baseline

| Case | Repetitions | Wall time | User / system time | Max RSS | Steps/s | Motor updates/s | Reach pair tests/s |
|---|---:|---:|---:|---:|---:|---:|---:|
| short, rep 1 | 1 | 56.10 s | 61.50 / 0.10 s | 200,780 KiB | 142.60 | 342,246 | 3.765 M |
| short, rep 2 | 1 | 60.31 s | 65.70 / 0.14 s | 178,292 KiB | 132.65 | 318,355 | 3.502 M |
| short, rep 3 | 1 | 53.48 s | 59.05 / 0.16 s | 185,000 KiB | 149.59 | 359,013 | 3.949 M |
| **short mean** | **3** | **56.63 s** (SD 3.45, CV 6.08%) | **62.08 / 0.13 s** | **188,024 KiB** | **141.61** | **339,871** | **3.739 M** |
| scientific | 1 | 145.76 s | 151.72 / 0.13 s | 183,436 KiB | 219.54 | 526,894 | 5.796 M |

Startup and tiered compilation are a larger fraction of the 8,000-step case, which explains both its lower
throughput and noisier repetitions. The retained effects were therefore checked in both the repeated short case
and the longer case.

## Scientific baseline

| Observable | Short, v=14 | Scientific, v=14 |
|---|---:|---:|
| `fbar_avail` (pN) | +0.04072 | -0.05791 |
| `fbar_bound` (pN) | +0.05235 | -0.07148 |
| mean bound / available | 6.484 / 8.34 | 4.746 / 5.86 |
| `Jattach` (/s/available) | 1322.299 | 1389.089 |
| mean attachment impulse (pN ms) | +0.02290 | -0.04099 |
| completed episodes / measured attachments | 145 / 147 | 438 / 434 |
| episode lifetime (ms) | 0.5963 | 0.5779 |
| stroke rate (/s/bound) | 1700.1 | 1718.4 |
| work/attachment (pN nm) | +0.3206 | -0.5738 |
| J1 completion samples / angle | 25,217 / 61.05235 deg | 76,488 / 59.71651 deg |
| x-bind mean / SD (nm) | 0.03500 / 0.00003 | 0.03500 / 0.00004 |

The complete short stdout SHA-256 is
`d0dfff8b58615e7db5334a43cab570992774306d1726f99ba2e9671273338daf`; the scientific stdout SHA-256 is
`3111b6dadb9c09b09a6e5610a8e988dd2e379e36d8caa625f026ac1184a4cd2f`.

## Baseline profile

The JFR profile used the same short command with:

```text
JDK_JAVA_OPTIONS=-XX:StartFlightRecording=filename=/tmp/softbox_vclamp_baseline.jfr,settings=profile,dumponexit=true
```

It contains 3,347 execution samples over 46 s. The percentages below attribute a sample in a foreign-memory
access helper or a small math helper back to its first simulation subsystem caller. “Calls/step” is exact from
the executed timestep; JFR is sampling rather than an instrumentation call counter.

| Subsystem | CPU samples | Exact volume | Allocation | Likely cause | Category |
|---|---:|---:|---|---|---|
| articulated J1/J2 joints | 38.09% | 1 call; 7,200 sub-body iterations; each joint evaluated twice | none sampled | repeated SoA reads, duplicated joint evaluation, and zero-coefficient angular `acos` work | physical kernel |
| rigid-body integration | 19.36% | 2 calls; 7,211 bodies | none sampled | many scalar SoA reads/writes and frame normalization | physical kernel |
| clamp measurement/other host loop | 10.94% | 2,400 motor records | no steady allocation sampled | per-motor availability, episode, force, and completion accounting | analysis |
| full geometry derivation | 8.22% | 5 calls; 14,433 body/segment records | none sampled | repeated cross products, normalization, and endpoint publication | object/geometry management |
| Brownian force | 6.30% | 2 calls; 7,211 bodies | none sampled | stateless hashes plus Box-Muller square roots and trig | physical kernel |
| brute reachable search | 5.71% | 26,400 exact motor-segment tests | none sampled | all-segment candidate enumeration with eligibility predicates | binding |
| anchor | 2.63% | 1 call; 2,400 motors | none sampled | move-coefficient calculation and SoA access | physical kernel |
| head publication | 1.91% | 1 call; 2,400 motors | none sampled | articulated head-tip/frame publication | object management |
| nucleotide cycle | 1.37% | 1 call; 2,400 motors | none sampled | state dispatch, keyed random draw, load-modulated ADP rate | kinetics |
| F8/F9/AXLOCK bond force | 1.22% | 1 call; 2,400 slots | none sampled | occupancy-dependent cross-bridge force/torque | physical kernel |
| nearest binding | 1.11% | 1 call; 2,400 motors | none sampled | eligible-candidate distance rechecks | binding |
| remaining apply/gather/chain/register/startup | 3.14% | fixed calls in `stepOrig` | startup only | small kernels and stable CSR gather | mixed |

At the leaf-method level, 38.87% of samples were in `MemorySegment.checkBounds`, 4.96% in memory-session validity
checks, and 1.85% in unaligned integer access. These are distributed across every TornadoVM `FloatArray` and
`IntArray` kernel rather than being a separate scientific calculation. The joint subsystem remained the dominant
caller after that cost was attributed.

Allocation was not a timestep-loop bottleneck. JFR reported 40.9 MB cumulatively allocated by `main` at the
initial statistics event, after scene and diagnostic-array construction, and only one sampled allocation site:
the startup `double[]` returned by `DragTensorSystem.rodDragSI`. It recorded zero garbage collections during the
profile. Thus the steady-state allocation rate was below JFR's sampling resolution; the workload is dominated by
primitive off-heap array access and arithmetic, not temporary Java objects, formatting, or GC.

