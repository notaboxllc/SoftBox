# Execution-backend audit — explicit-S2 single head vs the explicit-HMM dimer density sweep

Correcting an over-broad claim I made mid-sweep ("the `explicit-s2-l40` motor core is CPU-only"). This audit
traces the three execution paths **directly from the code** (not from harness names or CLAUDE.md prose) and verifies
with `nvidia-smi`. The correction: **a GPU device-resident implementation of the explicit-S2 beam DOES exist**
(`TwoBodyBeamAnalyticGpu.beamRelaxAnalytic`, run device-resident by `ExplicitSingleHeadHarness`); it is simply
**not production-promoted** (`MotorGpuParams.DEVICE_VALIDATED = false`), so the *production gliding assay* and the
*dimer sweep* both run on the CPU. "CPU-only" was correct for the production/MotorModel path but wrong as a blanket
statement about the model.

## 1–3. The three paths, traced from source

| path | entry | mechanics integrator | binding | chemistry | force gather | device? (evidence) |
|---|---|---|---|---|---|---|
| **(1) production single-head explicit-S2 gliding** | `run_lasertrap.sh -motor explicit-s2-l40 -glide` → `LaserTrapHarness` → `TwoBodyConverterMotor.runMotorGliding`/`stepGlideS2`/`s2Solve` | CPU (`s2Solve`, plain Java) | CPU (`nearestSeg2D`/`gate2D`) | CPU (`cycleLymnTaylor`) | CPU (`CrossBridgeSystem.csr*` as method calls) | **CPU.** `stepGlideS2`/`s2Solve` contain **0** `TaskGraph`/`@Parallel`/`.execute()`. `run_lasertrap.sh` header: "plain JVM, single-threaded harness (no TornadoVM device execution)". `MotorModel.EXPLICIT_S2_L40` doc: "CPU-only … a GPU request must fail clearly" (`-gpu` refused). |
| **(1-GPU) explicit-S2 device slice** | `run_explicit_singlehead.sh` → `ExplicitSingleHeadHarness` (`-Dtornado.recover.bailout=false`) | **GPU** (`TwoBodyBeamAnalyticGpu.beamRelaxAnalytic`, maxIt=1 = one `s2Solve` step, device-resident across steps; `beamObserve` reads resident nodes) | n/a (single motor slice) | n/a | n/a | **GPU (device-resident).** `ExplicitSingleHeadHarness` has **9** `TaskGraph`/`TornadoExecutionPlan`/`.execute()` sites; `nvidia-smi` shows a compute-app + 15 % util when it runs (§4). Gated off from production by `MotorGpuParams.DEVICE_VALIDATED=false`. |
| **(2/3) explicit-HMM dimer mat** | `run_hmm_gliding.sh` → `ExplicitHmmDimerGlidingHarness.step` per timestep | **CPU** — per-head converter/F8 + fork/branch/shared-S2 solved by `ExplicitHmmDimer.solve` (own `headBlock`+`nodeForces`+`solveLin`, a (3·NF+4)-DOF FD-tangent Newton step, plain Java) | CPU (`nearestSeg2D`/`gate2D`) | CPU (`cycleLymnTaylor`) | CPU (`CrossBridgeSystem.csrHistogram/Scan/Scatter/segGather` as method calls) | **CPU (pure).** `ExplicitHmmDimerGlidingHarness` + `ExplicitHmmDimer` contain **0** device references. `run_hmm_gliding.sh` has no `-Dtornado` device flags (the `@tornado-argfile` is classpath/preview parity only). |

Per-component backend for the dimer (all **CPU**): per-head converter/F8 mechanics = `ExplicitHmmDimer.headBlock`;
proximal branches + shared S2 = `ExplicitHmmDimer.nodeForces`/`solve` (analytic stretch + FD bending + FD beam
tangent + dense `solveLin`); binding search = `nearestSeg2D`/`gate2D`; chemistry = `cycleLymnTaylor`; actin
integration = `RigidRodLangevinIntegrationSystem.integrate` (plain method); force reduction =
`CrossBridgeSystem.segGather` (plain method). Arrays are host `FloatArray`/`double[]`; **no host↔device copy** occurs
(there is no device buffer).

## 4. Runtime verification (nvidia-smi)
- **Dimer sweep (running):** every `java` process at ~104 % CPU (one core each), `nvidia-smi` **1 %, 279 MiB, no
  compute-app** — CPU, GPU idle.
- **GPU device slice (`run_explicit_singlehead.sh -traj`):** `nvidia-smi` showed **compute-app PID 903103 (164 MiB),
  util 15 %**, then the slice finished (`EXPLICIT SINGLE-HEAD SLICE: PASS`, stroke −7.7 nm). GPU utilization **does**
  rise for this path.
- **Single-head explicit-S2 mat (`glideSpeedS2`, my reference / `-exp4g`):** 0 device references in `stepGlideS2` →
  CPU (GPU stays idle), consistent with `run_lasertrap.sh`'s own "no TornadoVM device execution" note.

## 5. Did the dimer reuse the validated motor core?
- **Does each HMM head use the exact production `explicit-s2-l40` core?** **PARTLY.** The **binding gate**
  (`nearestSeg2D`/`gate2D`), **chemistry** (`cycleLymnTaylor`), **bond forces** (`CrossBridgeSystem.bondForces`), and
  **CSR gather** are the exact production system functions, reused verbatim as CPU method calls. But the **per-head
  converter/F8 + beam solve is a REIMPLEMENTATION** — `ExplicitHmmDimer.headBlock`/`nodeForces`/`solve` generalize
  the `s2Solve` equations to a **forked graph** (shared fork node coupling the two heads); the dimer does **not**
  call `TwoBodyConverterMotor.s2Solve` and does **not** call the GPU `beamRelaxAnalytic` kernel.
- **Same backend as the single-head mat?** The single-head *mat* is CPU (`s2Solve`) and the dimer is CPU — same
  *device class* (CPU), but **different code** (single-head unforked `s2Solve` vs dimer forked `solve`).
- **Reimplemented in CPU-only Java?** Yes — the forked-beam coupling is new CPU Java (necessarily: no single-head
  solver handles a fork).
- **Chemistry/state transitions identical?** Yes — `cycleLymnTaylor` + `thetaS4a` are the exact production calls.
- **Backend-identical numerics?** N/A — there is no GPU dimer path to compare; and the dimer's forked solve is not
  bit-comparable to the unforked `s2Solve`/`beamRelaxAnalytic` anyway (different DOF set + FD tangent).

> **Correct framing:** the dimer uses the **same model equations** (and reuses the production binding/chemistry/
> gather *code*), but the per-head **beam/converter solve is a CPU reimplementation generalized to a fork** — it is
> **not** "reusing the production motor core," and it is **not** on the GPU. (This softens the "runs the exact
> production per-head machinery" phrasing in the directionality/compliance findings — accurate for
> binding/chemistry/gather, imprecise for the beam solve.)

## 6. The running wide sweep
- **Command:** `run_hmm_gliding.sh -matsmoke -mdensity <d> -branchEA 0.03 -dmode 0 -steps 5000 …` (66 cells,
  9 densities × 4–8 seeds), driven 14-wide via `xargs`.
- **Backend:** **CPU** (0 device references; `nvidia-smi` 1 %).
- **CPU util:** ~100 % × 14 processes; **GPU util:** ~1 %.
- **Status at audit time:** 66/66 complete (the density-3000 cells were the long tail). Aux (dt/D2/cull) launched.
- **Results classification:** **CPU-REFERENCE** — and, because the dimer has **no** GPU path, these are the
  *definitive* dimer numbers, not provisional-pending-GPU. A future GPU dimer port should be numerically equivalent
  (same equations), so **the sweep does not need to be re-run for correctness** (only to go bigger/faster).

## 7. Performance scaling — the bottleneck
Cost is dominated by the **per-active-dimer forked-beam solve** (`ExplicitHmmDimer.solve`): per active dimer per
step it does one dense `solveLin` over `3·NF+4 = 19` DOF **plus** an FD beam tangent that evaluates `nodeForces`
~`2·(3·NF)=30`× per step, each `nodeForces` doing an FD bending sweep over `~18` coords. This scales with the number
of **active** (near-filament) dimers, not total dimers. Measured wall ∝ density: ~260 s/5000-steps at 500 dimers/µm²
(~240 active) → ~1560 s at 3000 dimers/µm² (~1440 active). **At 3000 dimers/µm² on the 3 µm² lawn:** ~9000 total
dimers / ~18 000 heads, of which **~1440 dimers active** (cull ≤120 nm) are actually solved each step; binding search
+ CSR gather + actin integrate are minor next to the coupled solve. **Primary bottleneck: the CPU per-dimer coupled
forked-beam solve (FD tangent + dense solve).**

## 8. Correction plan (if a GPU dimer is wanted)
The single-head GPU beam kernel (`beamRelaxAnalytic`) solves an **unforked** beam; the dimer's fork couples the two
heads, so it cannot be dropped in unchanged. Two options:

- **Option A — hybrid (GPU per-head beam + CPU fork coupling).** Batch each active dimer's two heads through
  `beamRelaxAnalytic` (existing kernel) conditioned on the current fork position, then a CPU block-update of the
  fork/branch/shared-S2 coupling; iterate 1–2 blocks/step. *Effort:* medium (block-Gauss–Seidel wrapper + fork
  reconciliation; the fork node lives *inside* the beam, so the split is not clean). *Speedup:* modest — the CPU
  fork step + per-step host↔device sync likely caps it at ~2–4× until the coupling also moves to device.
- **Option B — full-GPU forked-beam kernel.** Port `ExplicitHmmDimer.solve` (nodeForces + FD tangent + 19-DOF
  `solveLin`) to a single `@Parallel` kernel over active dimers — each dimer is **independent** (embarrassingly
  parallel), so one kernel/step over ~1440 threads at high density, then reuse the existing GPU system kernels
  (`CrossBridge`/`Chain`/`Brownian`/`integrate`) already validated in the full-system demo. *Effort:* high (a fixed
  19×19 dense solve + FD bending in PTX — register pressure; double vs float). *Speedup:* large at high density
  (work-bound, thousands of independent solves), modest at low density (launch-bound, few active dimers). This is the
  right target if the dimer mat becomes a standing quantitative assay at ≥1000 dimers/µm².

Both must preserve the validated CPU chemistry/binding semantics (reuse those system kernels unchanged) and be
gated behind a `DEVICE_VALIDATED`-style flag with a CPU≡GPU aggregate cross-check (the §CLAUDE.md standard).

## 9. Numerical-equivalence check
Not applicable as a CPU-vs-GPU *dimer* comparison — **there is no GPU dimer path**. For the single head, the GPU beam
(`beamRelaxAnalytic`) is cross-checked against CPU analytic + CPU FD oracles **inside `ExplicitSingleHeadHarness`
itself** (`ExplicitBeamSolver.FD_REPLICA` = the permanent physical oracle; the slice printed `PASS`, stroke −7.7 nm
≈ the CPU 7.6–8.0 nm). The dimer's forked solve is a different DOF set and is not bit-comparable to either
single-head solver; its validation is the CPU self-consistency + physics gates in the compliance/directionality
studies, not a backend cross-check.

## 10. FINAL STATUS BLOCK
```
SINGLE-HEAD EXPLICIT-S2-L40 PRODUCTION BACKEND: CPU  (MotorModel/glideSpeedS2/s2Solve; -gpu refused; DEVICE_VALIDATED=false)
  — but a GPU DEVICE-RESIDENT beam implementation EXISTS and runs (ExplicitSingleHeadHarness + TwoBodyBeamAnalyticGpu.beamRelaxAnalytic), just un-promoted.
DIMER PER-HEAD MOTOR BACKEND: CPU (ExplicitHmmDimer.headBlock — a reimplementation of the s2Solve F8/converter block, generalized to the fork)
DIMER BRANCH/FORK/S2 BACKEND: CPU (ExplicitHmmDimer.nodeForces/solve — FD beam tangent + dense solveLin)
DIMER BINDING BACKEND: CPU (nearestSeg2D/gate2D — exact production functions, run as CPU calls)
DIMER CHEMISTRY BACKEND: CPU (cycleLymnTaylor — exact production function, run as CPU call)
CURRENT WIDE SWEEP BACKEND: CPU (0 device references; nvidia-smi 1%)
GPU UTILIZATION, SINGLE-HEAD SMOKE: device slice 15% + compute-app present (run_explicit_singlehead); CPU mat (glideSpeedS2) ~1% (idle)
GPU UTILIZATION, DIMER SMOKE: ~1% (idle — pure CPU)
CPU UTILIZATION, SINGLE-HEAD SMOKE: ~100%/1 core (CPU mat); device slice offloads to GPU
CPU UTILIZATION, DIMER SMOKE: ~100%/1 core per cell (14 cores under the parallel sweep)
PRODUCTION MOTOR CORE REUSED EXACTLY: PARTLY (binding/chemistry/bondForces/CSR-gather reused verbatim as CPU calls; the per-head converter/F8/beam solve is a CPU reimplementation generalized to the fork — NOT the s2Solve core and NOT the GPU kernel)
EARLIER CPU-ONLY CORE CLAIM CORRECT: NO (a GPU device-resident explicit-S2 beam kernel exists — TwoBodyBeamAnalyticGpu; "CPU-only" is true only for the production/MotorModel path, which is gated by DEVICE_VALIDATED=false)
CURRENT SWEEP RESULTS CLASSIFICATION: CPU-REFERENCE (and definitive — the dimer has no GPU path; not provisional)
PRIMARY BOTTLENECK: the CPU per-active-dimer forked-beam coupled solve (FD tangent ~30 nodeForces evals + 19-DOF dense solveLin per active dimer per step); scales with ACTIVE dimers (~1440 at 3000/µm²)
RECOMMENDED BACKEND ARCHITECTURE: Option B (full-GPU per-dimer forked-beam @Parallel kernel over active dimers, reusing the existing GPU system kernels for bind/chem/gather/integrate) — if the mat becomes a standing assay at ≥1000 dimers/µm²; else keep CPU (correct, just slower)
WIDE SWEEP SHOULD BE RERUN AFTER BACKEND FIX: NO (a GPU port would be numerically equivalent; rerun only to scale up, not for correctness)
NEXT STEP: (a) correct the "CPU-only core" / "reuses the production core" phrasing in the compliance & directionality findings; (b) if scaling is needed, prototype Option B behind a DEVICE_VALIDATED-style flag with a CPU≡GPU aggregate cross-check; (c) finish the CPU density-sweep write-up, labeled CPU-REFERENCE.
```

**Bottom line:** my "explicit-s2-l40 is CPU-only" was **wrong** — a GPU device-resident beam kernel exists and runs
(it's just not production-promoted, `DEVICE_VALIDATED=false`). What is true: the **production gliding path and the
entire dimer sweep run on the CPU**, the dimer **reuses the production binding/chemistry/gather code but
reimplements the beam solve on CPU for the forked topology**, and the sweep numbers are **CPU-reference and
definitive** (no GPU dimer path exists to differ from).
