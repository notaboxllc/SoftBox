# Part C — flat analytic explicit-S2 beam GPU kernel: lowering + throughput

The central question: **does the analytic explicit beam kernel LOWER to PTX now that the 973-node nested-FD
helper is gone, and what is its GPU throughput?** Answer: **YES — it lowers and executes on the RTX 5070,
bit-faithful to the CPU analytic solver, and the batched beam solve is ~70× the CPU analytic solver / ~900×
the CPU FD solver.** Work in `/home/jba/Code/SoftBox-explicitjac` (branch `explicit-analytic-jac`); no beam
physics/energy/topology/params changed; FD stays the production default + physical oracle; `DEVICE_VALIDATED`
not flipped; new files only.

## New-file inventory
- `softbox/TwoBodyBeamAnalyticGpu.java` — the FLAT `@Parallel` device kernel `beamRelaxAnalytic`: per-thread
  beam relaxation (analytic residual + exact energy Hessian + flat 14×14 Gauss–Jordan), NO `double[][]`, NO
  `new`/allocation (per-thread scratch is a caller-provided flat `DoubleArray sys` slice), runtime-bound
  loops, small inlineable helpers (`dacos`/`a1`/`a2`/`addK`/`addF`), explicit convergence + singular flags.
  Written in the "one method, two runners" idiom (plain-Java loop = CPU mirror = bit-faithful to device).
- `softbox/ExplicitBeamGpuHarness.java` — C2 lowering probe / C3 device fixture gate / C4 microbench +
  CPU-mirror validation (+ `-debug1`/`-debugasm` assembly bisection diagnostics).
- `scripts/run_explicitgpu.sh` — device runner (`-Dtornado.enable.fma=false -Dtornado.recover.bailout=false`).
- Logs: `RUN_LOGS/explicit_jac/gpu_{cpuvalidate,probe,gate,bench}.txt`.

## C1 — the kernel, and the one bug found
The kernel mirrors `ExplicitBeamAnalytic` exactly (same units, same formulas), using the Newton-refined
device-safe `accurateAcos` (`Math.acos` does not lower on PTX). One porting bug was found and fixed by the
assembly-diff diagnostic (`-debugasm`, entry-by-entry vs the validated `ExplicitBeamSolver.assemble`): the
fixed-node row sentinel `-1` aliased onto node-1's rows (`-1+p` with `p=1,2` gives `0,1`, which passed the
`r≥0` free-DOF guard) — a spurious cross-coupling. Fixed with a large-negative sentinel (`-1000`) so
`sentinel+p` stays negative. After the fix the flat assembly matches the CPU solver to **ΔF ~1e-24,
ΔK ~1e-15** (machine), and the full relaxation matches CPU-FULLY_ANALYTIC to **4.3e-14 µm**.

## C2 — LOWERING PROBE (the headline)
N=1 device TaskGraph, `-Dtornado.recover.bailout=false` (no silent fallback).

- **First attempt FAILED** — but NOT on node count / inline cap: the PTX backend crashed in
  `PTXFMANode.generate` (`NullPointerException`, a null operand in fused-multiply-add LIR lowering). This is
  a TornadoVM PTX backend FMA bug, independent of the beam equations (the docs even warn
  `tornado.enable.fma` "may cause issues on some platforms").
- **Fix = a FLAG, not an equation change:** `-Dtornado.enable.fma=false` disables the PTX FMA phase.
- **Result: LOWERS + EXECUTES on the RTX 5070: YES.** `status=0`, finite outputs, and **GPU vs CPU-mirror
  max|Δnode| = 0.000e+00 (bit-identical)** on the same config. Build+execute wall ≈ 1.8 s (first-compile).

The whole point is confirmed: **removing the nested finite difference collapses the kernel below TornadoVM's
600-node inline cap** — the FD `explicitBeamStep` (973 inlined nodes, `double[][]`, `Math.acos` inner loop)
could not lower; the analytic form (flat scratch, no nested FD, runtime-bound loops) does. As a bonus,
FMA-off makes the device arithmetic match the CPU runner even more closely.

## C3 — DEVICE FIXTURE GATE (CPU-FD / CPU-analytic / GPU-analytic)
All 10 explicit golden fixtures, each relaxed on device and on both CPU references:

| metric | result |
|---|---|
| GPU-analytic vs CPU-analytic | **max 2.5e-9 µm** (7/10 bit-identical `0.0`; the 2 soft-mode 800-iter fixtures differ ~2.5e-9 from float op-ordering over 800 steps) |
| GPU-analytic vs CPU-FD (physical) | **max 9.1e-9 µm**, energyRel ≤ 1.2e-4, contourΔ ≤ 2.6e-8 µm |
| solver failures (GPU) | **0** |
| iteration counts | match CPU (1 for settled fixtures; 5 for axial; 800-cap for the soft `bend`/`mixed`) |

GPU-analytic matches CPU-analytic tightly and shares the CPU-FD basin — the B4 converged-state-equivalence
carries to the device. (A 300-config CPU-mirror sweep also matched CPU-FULLY_ANALYTIC to 4.3e-14 µm.)

## C4 — BEAM MICROBENCHMARK (solves/s; a "solve" = a full per-config relaxation to convergence)
| batch | CPU-FD solv/s | CPU-analytic solv/s | GPU-analytic solv/s | GPU warm ms | it/solve (GPU avg) | fail |
|---:|---:|---:|---:|---:|---:|---:|
| 128 | 27 | 222 | 473 | 1201 | 33.4 | 0 |
| 256 | 26 | 332 | 942 | 473 | 35.9 | 0 |
| 512 | 26 | 339 | 1876 | 439 | 38.7 | 0 |
| 1024 | 26 | 339 | 3754 | 444 | 34.7 | 0 |
| 4096 | 26 | 335 | 13761 | 514 | 35.9 | 0 |
| 16384 | 26 | 341 | 17445 | 1237 | 36.0 | 0 |
| 65536 | 25 | 334 | **23205** | 3597 | 35.6 | 0 |

- **CPU-FD → CPU-analytic: ×12.8** (26→334 solves/s) — the nested-FD removal, consistent with Task-2 B6.
- **CPU-analytic → GPU-analytic: ×69** at 65536 (334→23205).
- **CPU-FD → GPU-analytic: ×892** at 65536 (26→23205).
- **Scaling:** GPU solves/s climbs 473→23205 across batch 128→65536 (×49) — the device needs a large batch
  to fill; it is still climbing at 65536 (FP64 is ~1/64 FP32 on the consumer RTX 5070, the ceiling). Warm
  kernel dominates; the ~35 avg iterations/solve (with a soft-mode tail toward the 800 cap) match the CPU.
- **0 solver failures at every batch.**

**Isolated beam-solve GPU speedup classification: ≥10× (dramatically) — ≈70× over the already-9–13×-faster
CPU analytic solver, ≈900× over the CPU FD solver, at production batch (65536).**

## C5 / C6 — end-to-end classification (GATED, honest)
The FULL explicit gliding loop (`stepGlideS2`) is host-sequential with the **same mat-SoA blocker Part A found
for the calibrated model**: per-motor state lives in host `double[]`/`double[][]`, and the cull/gate/bind
stages are host-scalar. So **end-to-end explicit gliding on device is GATED on a separate mat-SoA increment
and is not buildable here** — C5-full and C6 are classified **GATED (prerequisite: mat SoA)**, not failures.

The achievable, meaningful measurement is the **isolated beam solve (C4 above)** — which is exactly the
per-motor cost that dominated the explicit model and the kernel that would not lower. **Projection once the
mat is SoA:** the beam solve (previously the dominant per-motor stage AND a non-lowering kernel) becomes a
device-resident kernel at ~70× the CPU-analytic throughput; end-to-end explicit gliding then gains
substantially, bounded by the remaining (lighter) cull/gate/bind stages once they are SoA — i.e. the beam is
removed as the throughput bottleneck. A device single-head optical-trap replay (partial C5) is the same beam
solve with the trap as the boundary; its per-beam-solve device cost is the C4 number (the bond/gather/trap
stages it also needs are the host-scalar pieces gated on the mat-SoA work).

## Verdict
**Part C answer: the analytic explicit beam GPU kernel LOWERS to PTX and executes on the RTX 5070**
(bit-faithful to the CPU analytic solver; 0 failures; same basin as CPU-FD). The FD kernel could not — the
nested finite difference was both the 973-node inline-cap violation and the dominant cost; the analytic
Jacobian removes both. **Isolated beam-solve GPU throughput is ≥10× (≈70× vs CPU-analytic, ≈900× vs CPU-FD)
at production batch.** End-to-end explicit gliding is gated on the mat-SoA prerequisite (a separate
increment); the beam bottleneck itself is now unblocked. FD remains the production default + physical oracle;
`DEVICE_VALIDATED` unchanged.

One backend note for the planner: **`-Dtornado.enable.fma=false` is required** for this kernel on the PTX
backend (the default FMA phase NPEs in `PTXFMANode.generate`); it also tightens CPU↔GPU agreement.
