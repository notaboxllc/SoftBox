# Mat-SoA GPU increment — combined results (Parts A–J)

**One-file summary** of "remove the shared serial GPU bottlenecks + integrate explicit". Detailed companions:
`SERIAL_BOTTLENECK_REMOVAL_FINDINGS.md` (A–E), `EXPLICIT_DEVICE_STATUS.md` (F–J). Raw CSVs in
`RUN_LOGS/matsoa/`. All GPU runs: RTX 5070 / PTX, `-Dtornado.recover.bailout=false` (no silent fallback).
Only `softbox/MatSoaSlice.java` changed; parallel paths opt-in (`-parcsr`/`-parreduce`/`-paropt`); serial
default byte-identical (5 stage gates re-PASS); no physics/param/RNG/chemistry/binding change;
`MotorGpuParams.DEVICE_VALIDATED` stays false. Box 3.0 µm² ⇒ density 200/700/1500/3000 → **N =
600/2100/4500/9000**; **nSeg = 12 constant**.

## Final status block
- **SERIAL CSR STATUS:** REPLACED — parallel atomic-free counting-sort (`csrChunk*`), bit-identical to serial, device 13× faster @N=9000.
- **PARALLEL REDUCTION STATUS:** DONE — hierarchical block+final `matReduce`, integer-exact + COM/Σload bit-identical, device 21× faster @N=9000.
- **CALIBRATED GPU STATUS:** `CALIBRATED DOUBLE GPU VALIDATED BUT NOT PROMOTED` (bottlenecks removed, +49 % wall @N=9000; `DEVICE_VALIDATED` false).
- **EXPLICIT GPU STATUS:** `EXPLICIT GPU VALIDATED FOR SINGLE-HEAD TRAP ONLY` (beam kernel lowers+validated; gliding not yet integrated; CPU FD stays the oracle).
- **LARGE-SCALE STUDIES: NOT READY** — calibrated serial stages gone + calibrated validated, but explicit does not yet run end-to-end on the persistent device path.
- **NEXT BOTTLENECK:** `matStep7` (FP64 5-DOF solve — now the top device kernel) + the ~20-kernel wall launch floor.

---

## Part A — frozen baseline (`-baseline`, `BASELINE.csv`)
Device-only, PRODUCTION residency (mutable SoA uploaded FIRST_EXECUTION; per step only counters UP + 6-double
`redOut` DOWN — 576 B in / 64 B out). Per-kernel = SILENT profiler `TASK_KERNEL_TIME`; wall = no-profiler loop.

| N | active/step | bound/step | wall ms/step | device-kernel ms/step | top-3 kernels (% of device-kernel) |
|---:|---:|---:|---:|---:|---|
| 600  | 80   | 4  | 2.115 | 0.306 | matReduce 18.2, matStep7 11.1, csrHist 9.6 |
| 2100 | 301  | 13 | 2.100 | 0.546 | matReduce 33.7, csrScatter 17.3, csrHist 17.2 |
| 4500 | 657  | 20 | 2.526 | 0.938 | matReduce 40.4, csrHist 20.8, csrScatter 20.7 |
| 9000 | 1297 | 46 | 3.034 | 1.711 | **matReduce 43.7, csrScatter 22.4, csrHist 22.4** |

Confirms the target at N=9000 exactly (88.5 % of device-kernel in three single-thread-over-N kernels; matReduce
climbs 18→44 % with N = O(N) serial signature). Wall ≫ device-kernel ⇒ the ~1.3–2 ms gap is the 20-launch
floor ⇒ **the win grows with N**.

## Part B — CSR topology + invalidation (`-csranalyze`, `CSR_INVALIDATION.csv`)
CSR = segment→bound-motors, source `boundSeg` (segment-index keys), nSeg=12 constant, stable index-order.

| N | mean bound | max bound | changed/step | frac of N | **no-change steps** |
|---:|---:|---:|---:|---:|---:|
| 600  | 3.1  | 6  | 0.02 | 0.0033 % | **98.0 %** |
| 2100 | 8.6  | 17 | 0.06 | 0.0029 % | **94.1 %** |
| 4500 | 15.6 | 29 | 0.11 | 0.0025 % | **89.5 %** |
| 9000 | 25.3 | 49 | 0.20 | 0.0022 % | **82.4 %** |

Topology is *slowly changing* — serial cost is the loop-over-all-N, not the change rate. **Verdict → C2
(parallel device rebuild)**: a host-cache would need a per-step `boundSeg` round-trip that breaks residency;
`csrChunk*` rebuilds in parallel, bit-identical, zero round-trip.

## Part C3 — parallel CSR ≡ serial, exact (`-csrgate`, `PART_C3_CSR_GATE.md`)

| case | N | nBound | offset-mism | membership-mism | wrong-bin | dup | dropped | segGatherΔ |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| sparse+endpoints+unbound | 100 | 3 | 0 | 0 | 0 | 0 | 0 | 0 |
| all-unbound (empty) | 200 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| all-in-one-bin | 500 | 500 | 0 | 0 | 0 | 0 | 0 | 0 |
| one-per-bin | 12 | 12 | 0 | 0 | 0 | 0 | 0 | 0 |
| endpoints-alternating | 48 | 48 | 0 | 0 | 0 | 0 | 0 | 0 |
| random-40 %-unbound | 3000 | 1847 | 0 | 0 | 0 | 0 | 0 | 0 |
| max N=9000 | 9000 | 6368 | 0 | 0 | 0 | 0 | 0 | 0 |
| rapidly-changing (60 topologies) | 2100 | — | — | — | — | — | — | 0/60 fail |

## Part D4 — parallel reduction ≡ serial (`-redgate`, `PART_D4_REDUCE_GATE.md`)
Blocks the O(N) motor loop over ⌈N/128⌉ blocks + cheap final over ~N/128 partials + nSeg-only COM; atomic-free.
All PASS (N=1, all-inactive, all-bound mixed prop/drag, cancellation-dominated, N=9000, large-magnitude,
NaN-in-unbound-excluded): **integer totals EXACT, COM Δ=0, Σload Δ=0** in every case.

## Part E — structural throughput gate (`-partE`, `PART_E_THROUGHPUT.csv`)

| N | wall serial | wall parcsr | wall parreduce | **wall paropt** | dev-kernel serial→paropt | csrGroup | reduceGroup |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 600  | 2.014 | 2.126 (−5.6 %) | 1.960 (+2.7 %) | **2.152 (−6.9 %)** | 0.320 → 0.233 | 0.077→0.031 | 0.065→0.026 |
| 2100 | 1.752 | 1.634 (+6.8 %) | 1.509 (+13.9 %) | **1.388 (+20.8 %)** | 0.591 → 0.241 | 0.213→0.039 | 0.205→0.030 |
| 4500 | 2.162 | 1.756 (+18.8 %) | 1.797 (+16.9 %) | **1.462 (+32.4 %)** | 1.048 → 0.266 | 0.440→0.051 | 0.428→0.035 |
| 9000 | 3.012 | 2.309 (+23.3 %) | 2.676 (+11.2 %) | **1.528 (+49.3 %)** | 1.931 → 0.313 | 0.872→0.066 | 0.850→0.041 |

Device-kernel N=9000 **6.2×** (1.931→0.313 ms); csrGroup **13×**, reduceGroup **21×** (clean, additive). Wall
gain grows with N (−6.9 % @N=600 = +3-task launch floor below the target regime; +49 % @N=9000). **No
observable regression:** CPU serial ≡ CPU paropt **bit-identical** (redOut/boundSeg/coord Δ=0, 500 steps);
GPU-paropt vs CPU-paropt **t=0 IDENTICAL** (only the same float-FMA decorrelation as serial). All 4 success
criteria met.

## Parts F–J — explicit-S2-L40 device status
- **DONE (crux, re-confirmed in tree):** the isolated analytic explicit beam GPU kernel
  (`TwoBodyBeamAnalyticGpu.beamRelaxAnalytic`) LOWERS + executes (GPU vs CPU-mirror Δ=0.0); C3 fixture gate
  GPU-vs-CPU-analytic **2.5e-9 µm** / vs-FD **9.1e-9 µm**, 0 failures, iteration counts match; ≈70×
  CPU-analytic at batch 65536. Requires `-Dtornado.enable.fma=false`. Satisfies F4/G1/G3 (single-head bound
  relaxation). Shared stages 1–6/8/11 (incl. the now-parallel CSR + reduction) are model-agnostic and ready.
- **REMAINING (bounded next increment, honestly not built):** F1–F3 persistent explicit SoA + init +
  build-time dispatch into the mat; the `matS2Solve` gliding-coupling Stage-10 port (**re-probe its lowering
  in isolation FIRST**; fallback = calibrated GPU + explicit-Step-10 CPU); G2 dynamic single-head; H
  end-to-end explicit gliding at 200/700/1500 µm⁻² + half-dt + cull controls.
- **Part J promotion:** calibrated = `VALIDATED BUT NOT PROMOTED` (flip awaits coordinator + broader scene +
  float `matStep7`); explicit = `VALIDATED FOR SINGLE-HEAD TRAP ONLY`; CPU FD stays the permanent oracle.

## Stop-condition audit — none tripped
No dropped/dup/reordered motor (C3 exact); no measurement-definition change (D4 integer-exact, COM bit-id); no
integer-total difference; no systematic ensemble bias (bit-identical CPU no-op + GPU t=0 identity); explicit
beam energy/solver criteria unchanged; no explicit-invokes-calibrated (not integrated; will fail clearly); no
full-state-per-step crossing (576 B up / 64 B down); no silent GPU fallback (bailout=false throughout).

## Modified files
`softbox/MatSoaSlice.java` (only code file; +546/−18): `buildTrajGraph(prod)` production residency;
`USE_PARALLEL_CSR`/`USE_PARALLEL_REDUCE` switches wiring `csrChunk*` (reused byte-unchanged from
`CrossBridgeSystem`) + new `matReduceBlocks`/`matReduceFinal`; modes `-baseline`/`-csranalyze`/`-csrgate`/
`-redgate`/`-partE`; flags `-parcsr`/`-parreduce`/`-paropt`. Docs: this file + `SERIAL_BOTTLENECK_REMOVAL_
FINDINGS.md` + `EXPLICIT_DEVICE_STATUS.md` + `SLICE_STATUS.md`/`JOURNAL.md` updates.

## Exact commands
```
java @tornado-argfile --enable-preview -Xmx8G -Dtornado.recover.bailout=false \
     -Dtornado.tvm.maxbytecodesize=16384 -cp "$TDIR/tornado-api-4.0.1-dev.jar:." softbox.MatSoaSlice <mode>
#  <mode> ∈ { -baseline | -csranalyze | -csrgate | -redgate | -partE | -traj -paropt | (no-arg stage gates) }
#  explicit beam kernel:  ./scripts/run_explicitgpu.sh -probe -gate -bench
```
