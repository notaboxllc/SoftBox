# Removing the shared serial GPU bottlenecks from the calibrated mat-SoA device path (Parts A–E)

**Priority 1 of the increment: DONE + validated.** The three serial-over-N kernels that dominated the
device-resident calibrated mat-SoA step (`matReduce`, `csrHist`, `csrScatter`) are replaced with parallel,
atomic-free, **bit-identical** kernels. No motor physics / chemistry / binding / parameters / RNG semantics /
model identity changed. `MotorGpuParams.DEVICE_VALIDATED` stays **false** (experimental slice).

All runs: `-Dtornado.recover.bailout=false` (a lowering failure throws — no silent CPU fallback),
`-Dtornado.tvm.maxbytecodesize=16384`, RTX 5070 / PTX. Box = 3.0 µm² ⇒ density 200/700/1500/3000 → **N =
600/2100/4500/9000** exactly; **nSeg is constant = 12** for all N.

---

## Part A — frozen baseline (`-baseline`, `RUN_LOGS/matsoa/BASELINE.csv`)

Device-only, PRODUCTION residency (mutable SoA uploaded FIRST_EXECUTION; per step only the step/seed
counters cross UP and the 6-double `redOut` reduction crosses DOWN — measured 576 B in / 64 B out per step).
Per-kernel time = TornadoVM SILENT profiler `TASK_KERNEL_TIME`; wall = clean no-profiler loop (no CPU
reference in the measured interval).

| N | active/step | bound/step | wall ms/step | device-kernel ms/step | top-3 kernels (% of device-kernel) |
|---:|---:|---:|---:|---:|---|
| 600  | 80   | 4  | 2.115 | 0.306 | matReduce 18.2, matStep7 11.1, csrHist 9.6 |
| 2100 | 301  | 13 | 2.100 | 0.546 | matReduce 33.7, csrScatter 17.3, csrHist 17.2 |
| 4500 | 657  | 20 | 2.526 | 0.938 | matReduce 40.4, csrHist 20.8, csrScatter 20.7 |
| 9000 | 1297 | 46 | 3.034 | 1.711 | **matReduce 43.7, csrScatter 22.4, csrHist 22.4** |

**Confirms the target breakdown at N=9000 exactly: matReduce ≈ 43.7 %, csrHist ≈ 22.4 %, csrScatter ≈ 22.4 %
(88.5 % of device-kernel time in three single-thread-over-N kernels).** matReduce's share climbs 18→44 % as
N grows (600→9000) — the signature of an O(N) serial cost.

**Structural note (load-bearing for interpreting the wins):** wall (2.1→3.0 ms/step) ≫ device-kernel
(0.31→1.71 ms). The ~1.3–2 ms gap is the fixed 20-kernel-launch overhead (the ~8000 launch/s PTX ceiling).
So parallelizing the serial kernels cuts the *device-kernel* time (the part that grows with N); the launch
floor stays. ⇒ **the wall-clock win grows with N** (small/negative at N=600, large at N=9000).

---

## Part B — CSR topology + invalidation (`-csranalyze`, `RUN_LOGS/matsoa/CSR_INVALIDATION.csv`)

The CSR inverse is **segment → bound-motors**, rebuilt each step from the SOURCE array `mot.boundSeg[m]`
(per-motor segment index; <0 = unbound). DEST/KEYS = filament **segment** indices; **nSeg = 12, constant**.
Ordering = ascending motor-index within each segment bin (serial and the parallel counting-sort are stable
and bit-identical). Downstream `segGather` sums each bin's reactions (commutative fp add).

| N | mean bound | max bound | changed/step | frac of N changed | **no-change steps** | binds/step | unbinds/step |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 600  | 3.1  | 6  | 0.02 | 0.0033 % | **98.0 %** | 0.008 | 0.006 |
| 2100 | 8.6  | 17 | 0.06 | 0.0029 % | **94.1 %** | 0.023 | 0.020 |
| 4500 | 15.6 | 29 | 0.11 | 0.0025 % | **89.5 %** | 0.041 | 0.035 |
| 9000 | 25.3 | 49 | 0.20 | 0.0022 % | **82.4 %** | 0.071 | 0.065 |

**Surprising, decisive finding:** the topology is *slowly changing* — 82–98 % of steps leave `boundSeg`
UNCHANGED. So the serial CSR cost is NOT driven by the change rate; it is driven by the serial kernels
looping over ALL N motors on a single thread regardless of how few (~3–25) are bound.

**Verdict → C2 (parallel device rebuild).** A C1 host-cached CSR would need `boundSeg` (N ints) read to host
each step to detect invalidation — a per-step round-trip that defeats device residency. The existing,
validated `csrChunk*` atomic-free counting-sort rebuilds the CSR fully in parallel with ZERO host round-trip
and is bit-identical to serial. (The slowly-changing property is a noted *future* lever — a device-side
dirty-flag skip — but is not needed.)

---

## Part C — parallel CSR (`csrChunk*`) wired in; C3 gate (`-csrgate`, `PART_C3_CSR_GATE.md`)

The serial `csrHistogram → csrScan → csrScatter` is replaced by the atomic-free counting-sort
`csrChunkZero → csrChunkHistogram → csrChunkReduce → csrScan → csrChunkScatter` (per-motor-chunk private
histogram rows, column-prefix merge, stable scatter). Wired into both the device `buildTrajGraph` and the
CPU-runner `stepMatCPU` behind `USE_PARALLEL_CSR` (`-parcsr`). `segGather` and all other kernels are
byte-unchanged.

**C3 gate — parallel CSR ≡ serial CSR EXACTLY on every topology class (CPU-runner, both kernel sets):**

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

Exact offsets + counts + membership, no dropped/duplicated motor, bit-identical downstream `segGather`, on
empty/full/one-per-bin/endpoint/random/max/rapidly-changing topologies. (csrChunk*'s CPU↔GPU bit-identity is
separately validated by DenseGliding and re-confirmed by the Part-E GPU trajectory below.)

---

## Part D — parallel hierarchical `matReduce`; D4 gate (`-redgate`, `PART_D4_REDUCE_GATE.md`)

The O(N) motor loop (nBound / nActive / Σ forceDotFil) is blocked over ⌈N/128⌉ blocks (`matReduceBlocks`,
parallel; per-block partials) then a cheap final kernel (`matReduceFinal`) sums the ~N/128 block partials +
the nSeg-only COM. Atomic-free. Behind `USE_PARALLEL_REDUCE` (`-parreduce`).

- **Integer totals (nBound, nActive): EXACT** vs serial (order-independent).
- **COM (cx,cy,cz): bit-identical** (summed in the same 0..nSeg order in the final kernel).
- **Σ forceDotFil: bit-identical** in every gate case (block-order vs sequential; small magnitudes stay in
  the exactly-representable regime).

**D4 gate — all PASS:** N=1 bound; all-inactive/unbound; all-bound mixed propulsive/dragging (N=4000);
cancellation-dominated (N=6000); very-large N=9000; large-magnitude sums (N=5000); NaN-in-unbound-excluded
(N=300, verifies only bound motors enter Σload). Integer totals exact, COM Δ=0, Σload Δ=0 in all.

---

## Part E — structural throughput gate (`-partE`, `PART_E_THROUGHPUT.csv/.md`)

Device-only, production residency, four configs × N. Wall = clean loop; group ms = SILENT profiler.

| N | wall serial | wall parcsr | wall parreduce | **wall paropt** | dev-kernel serial→paropt | csrGroup | reduceGroup |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 600  | 2.014 | 2.126 (−5.6 %) | 1.960 (+2.7 %) | **2.152 (−6.9 %)** | 0.320 → 0.233 | 0.077→0.031 | 0.065→0.026 |
| 2100 | 1.752 | 1.634 (+6.8 %) | 1.509 (+13.9 %) | **1.388 (+20.8 %)** | 0.591 → 0.241 | 0.213→0.039 | 0.205→0.030 |
| 4500 | 2.162 | 1.756 (+18.8 %) | 1.797 (+16.9 %) | **1.462 (+32.4 %)** | 1.048 → 0.266 | 0.440→0.051 | 0.428→0.035 |
| 9000 | 3.012 | 2.309 (+23.3 %) | 2.676 (+11.2 %) | **1.528 (+49.3 %)** | 1.931 → 0.313 | 0.872→0.066 | 0.850→0.041 |

**Device-kernel gains are clean and additive:** at N=9000 serial 1.931 ms → paropt 0.313 ms (**6.2×**);
csrGroup **13×** (0.872→0.066), reduceGroup **21×** (0.850→0.041). The two serial-over-N bottlenecks are
eliminated (from 88.5 % of device-kernel to a small parallel remainder).

**Wall-clock:** meaningful improvement at N ≥ 2100 (+20.8 / +32.4 / +49.3 %); a small −6.9 % at N=600 because
the parallel paths add +3 kernel launches (3→5 CSR, 1→2 reduce) and at N=600 the launch overhead outweighs
the tiny device-kernel savings — the expected launch-bound tradeoff (Part A). N=600 is below the target
regime; the calibrated production scene is ≥ 2100.

**No scientific observable regression (two independent checks):**
- **Semantic no-op (CPU-runner serial ≡ CPU-runner paropt, 500 steps):** redOut mismatches = 0, boundSeg
  mismatches = 0, final-coord maxΔ = 0.0 — **BIT-IDENTICAL**. The optimization changes nothing semantically.
- **Device correctness (GPU-paropt vs CPU-paropt trajectory):** **t=0 IDENTICAL** at N=600 and N=2100 (the
  parallel device kernels reproduce the serial result bit-for-bit on identical IC); the only later divergence
  is the same float-FMA chaotic decorrelation as the serial path (1 boundary motor @ t=16, filament drift
  3.7e-9 µm) — **no hard-stop / no semantic bug.**

### Success criteria (all met)
- serial CSR stages no longer dominate — ✓ (csrGroup 0.872→0.066 ms, no longer O(N) serial)
- `matReduce` no longer one-thread-over-N — ✓ (hierarchical; reduceGroup 0.850→0.041 ms)
- meaningful end-to-end improvement at N ≥ 2100 — ✓ (+20.8 / +32.4 / +49.3 %)
- no scientific observable regression — ✓ (bit-identical CPU no-op + GPU t=0 identity)

---

## Modified files (Priority 1)
- `softbox/MatSoaSlice.java` — ONLY file changed: `buildTrajGraph(prod)` production-residency graph;
  `USE_PARALLEL_CSR`/`USE_PARALLEL_REDUCE` switches wiring `csrChunk*` + `matReduceBlocks`/`matReduceFinal`
  into both runners; new modes `-baseline` (A), `-csranalyze` (B), `-csrgate` (C3), `-redgate` (D4),
  `-partE` (E); flags `-parcsr`/`-parreduce`/`-paropt`. The parallel kernels themselves (`csrChunk*`) are
  reused BYTE-UNCHANGED from `CrossBridgeSystem`; `matReduceBlocks`/`matReduceFinal` are the two new kernels.
- No physics/param/RNG/chemistry/binding change; `DEVICE_VALIDATED` stays false; existing validated
  serial paths remain the default (parallel is opt-in via flags) and are byte-identical.

## Exact commands
```
# A: baseline profile         B: CSR invalidation      C3: CSR gate     D4: reduce gate
MatSoaSlice -baseline          -csranalyze              -csrgate         -redgate
# E: throughput gate (serial vs parcsr vs parreduce vs paropt) + semantic no-op
MatSoaSlice -partE
# device correctness of the optimized path (GPU-paropt vs CPU-paropt trajectory)
MatSoaSlice -traj -paropt
# all with:  java @tornado-argfile --enable-preview -Xmx8G -Dtornado.recover.bailout=false \
#            -Dtornado.tvm.maxbytecodesize=16384 -cp "$TDIR/tornado-api-4.0.1-dev.jar:." softbox.MatSoaSlice ...
```

## Remaining device bottleneck (post-optimization, N=9000, paropt)
device-kernel 0.313 ms/step; the largest remaining kernel is now **matStep7** (the double 5-DOF movable-pivot
solve — FP64-bound), with the launch floor (~1.2 ms of the 1.53 ms wall) the dominant wall cost. The two
levers that remain: (1) a **float `matStep7`** (Part-8 motivation — retires the FP64 solve as the top
kernel); (2) fewer kernel launches via fusion (the launch floor). Neither is a serial-over-N bottleneck —
**those are gone.**
