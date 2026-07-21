# Explicit-HMM dimer — GPU backend findings (device-resident forked-mechanics port)

**Status: IN PROGRESS.** Scaffolding + validation contract landed; mechanical kernel (Phase G1) under construction.
`ExplicitHmmDimerGpuParams.DEVICE_VALIDATED = false` (CPU remains the default and the permanent oracle).

This document is the standing record for the GPU port of the explicit-HMM dimer gliding assay. It does **not**
rewrite or supersede the CPU density-sweep results — those are **CPU-REFERENCE and scientifically definitive**
(`EXPLICIT_HMM_DIMER_COMPLIANT_DENSITY_SWEEP_FINDINGS.md`). The GPU port exists for **scalability and production
integration**, not to repair the CPU results.

## 0. Corrected backend framing (task §24)

Superseding earlier loose phrasing:
- A **device-resident single-head explicit-S2 beam implementation exists** (`TwoBodyBeamAnalyticGpu.beamRelaxAnalytic`,
  driven by `ExplicitSingleHeadHarness`) but is **not production-promoted** (`MotorGpuParams.DEVICE_VALIDATED=false`).
- The **production single-head gliding path is CPU** (`TwoBodyConverterMotor.s2Solve`).
- The **dimer reuses production binding, chemistry, bond-force, and CSR-gather logic**, but has its **own forked
  mechanical solve** (`ExplicitHmmDimer.solve` — a 19-DOF FD-tangent Newton step over a Y-shaped shared-S2 beam),
  which is **not** the single-head `s2Solve` core and, before this task, had **no GPU path**.
- The completed **CPU dimer density sweep is definitive**; this GPU port is numerically equivalent by construction
  (same equations) and is for scale/throughput, not correctness repair.

## 1. Frozen topology the first kernel is specialized to (task §2, §7)

Standing assay dimer: `Ms=3` shared-S2 segments, `Ma=Mb=1` branch segment/head, symmetric Y fork.
- free nodes `NF = Ms+Ma+Mb = 5`; total nodes `NODES = 6` (node 0 = clamped emergence, not integrated).
- solve dimension `NDOF = 3·NF+4 = 19` (15 node DOFs + φ_A,ψ_A,φ_B,ψ_B).
- augmented width `W = 20`; per-work-item flat scratch `SYS_STRIDE = 19·20 = 380` doubles.
- **Specialization limitation (documented, task §2):** the first kernel is hard-specialized to this fixed 19-DOF
  forked topology (compile-time `Ms/Ma/Mb`), not a generic graph solver. A different branch/shared count needs a
  recompiled kernel. `ExplicitHmmDimerGpuParams.assertStandingConfig` trips if the harness build drifts.
- **Standing config:** dt=2.5e-6, α=10° fork rest half-angle, splay 16°, branchLen 10 nm, **branchEA=0.03**
  (eff. proximal-branch axial ≈ 12.6 pN/nm), branch EI ×0.25 (reference), forkK 1.0, D0, 5.4 nm exclusion ON.
- **Dead code in the standing assay:** the gliding harness sets `dirMech=0, dirAct=0, boundHead=−1` every step, so
  every directional-mechanism branch (`forkCant`/`rotToward`/M1–M9/M3–M6 converter cant/M5 shared bend) is inert.
  The GPU kernel is specialized to `dirMech=0`: it keeps only the fixed clamped-emergence bend term and the ±α
  **directional fork-rest-angle** hinges (standing config), dropping the survey branches → less register pressure.

## 2. Term-by-term CPU→GPU mapping (task §4)

Source CPU: `ExplicitHmmDimer.solve/nodeForces/bendEnergy/headBlock` + `TwoBodyConverterMotor.solveLin/geomC/brownTorque`.
GPU section = the corresponding block in the flat SoA kernel (`ExplicitHmmDimerGpu`, Phase G1). Precision: **double**
throughout (matches the stiff CPU solve; float/mixed evaluated later per §5).

| CPU function / term | source | GPU kernel section | precision | validation fixture |
|---|---|---|---|---|
| stretch force `segKs·(len−l0)·û` | `nodeForces` L274–279 | `nodeForcesK` stretch loop (per fixed seg) | double | V1 detached, V2 one-head |
| bending force = central-FD of `bendEnergy`, h=1e-5 | `nodeForces` L280–285 | `nodeForcesK` FD-bend sweep over node coords | double | V1, V2 |
| clamped-emergence bend (g4Tan vs bond, fixed) | `bendEnergy` L232–236 | `bendEnergyK` emergence term | double | V1 |
| interior/fork hinge bend, ±α directional fork rest | `bendEnergy` L237–266 | `bendEnergyK` hinge loop (dirMech=0 path only) | double | V1 (fork angle) |
| floor penalty `kfloor·pen·eup` | `nodeForces` L286–287 | `nodeForcesK` floor loop | double | V1 |
| beam RHS pack on free nodes | `solve` L307–309 | residual pack into `sys` col NDOF | double | V3 |
| FD beam tangent = central-FD of `nodeForces`, h=1e-5 | `solve` L310–318 | tangent assembly into flat `sys` (FD variant) | double | V3, V4 |
| node drag implicit `gammaNode/dt` on diagonal | `solve` L319–322 | drag diagonal add | double | V1 |
| node Brownian `brownTorque(SALT_NODE)` | `solve` L321–322 | `brownTorqueD` keyed **persistent dimerID** | double | Tier-3 aggregate |
| head F8 Gauss–Newton `kfSI·JᵀJ` (5×5) ×2 | `headBlock` L349–356 | headBlock A@pivot pA, B@pivot pB | double | V2, V4 stroke |
| converter spring `kc`, bind `kb`, angle drag | `headBlock` L357,366 | angle-DOF diagonal block | double | V2 |
| F8 torque RHS `Qφ/Qψ` + converter/bind RHS | `headBlock` L368–370 | RHS angle entries | double | V4 stroke |
| head angle Brownian `brownTorque(SALT_ANG)` | `headBlock` L371–372 | `brownTorqueD` keyed dimerID+head | double | Tier-3 |
| dense solve `solveLin` (Gauss elim, partial pivot) | `TwoBodyConverterMotor.solveLin` | flat Gauss–Jordan partial-pivot in `sys` | double | V1–V5, min-pivot logged |
| coord update `nd += dq·1e6`, angles `+= dq` | `solve` L333–334 | update loop | double | V1–V5 |
| geometry refresh `geomC` (C,xF8,xH ← φ,ψ,A,P) | `pinHead`/`geomC` L336 | `geomCK` post-solve | double | V2 |

**No force/energy term is omitted.** The only terms dropped are the `dirMech≠0` survey branches, which are
provably inert in the standing assay (`dirMech=0` every step) — documented above, not a silent omission.

## 3. Reference gate table — numbers the GPU must preserve (task §14–§16, §19)

STANDING config (branchEA=0.03, D0, 5.4 nm exclusion ON). Source: `COMPLIANT_DENSITY_SWEEP` §6/§11,
`COMPLIANCE_SENSITIVITY` §6/§11/§17, `GLIDING_DIRECTIONALITY` §5/§7/§13, `BACKEND_AUDIT` §4/§7.

| ρ (dimers/µm²) | seeds | velProd µm/s (95% CI) | mean bound heads | worst-seed maxGap | exc>50/>100 nm | CPU steps/s |
|---|---|---|---|---|---|---|
| 100 | 8 | +0.99 [+0.10,+1.68] | 0.94 | 21.5 nm | 0 / 0 | — |
| 200 | 8 | +1.49 [+0.91,+2.11] | 2.21 | 21.5 nm | 0 / 0 | — |
| 500 | 6 | +1.49±0.86 (D0) | 4.46 | **11.8 nm** | 0 / 0 | **~19** (~240 active) |
| 700 | 8 | +2.35 [+1.71,+2.97] | 6.45 | 28.5 nm | 0 / 0 | — |
| 750 | 8 | +2.41 [+1.86,+2.94] | 6.82 | 29.8 nm | 0 / 0 | — |
| 1500 | 6 | +2.94 [+2.55,+3.38] | 13.19 | **162 nm** (1/6 seeds) | 1 / 1 | — |
| 3000 | 4 | +2.85 [+2.57,+3.15] | 26.08 | **32 149 nm** (seed 102) | 6 / 4 | **~3.2** (~1440 active) |

Compliance health (ρ500, standing): p99.9 gap 4.36 nm, peak branch force **103 pN**, peak F8 ~3.4 pN/head.
Stroke: intrinsic converter **8.0 nm**, static head **7.98 nm** (anchor-relative variant 7.60 nm — secondary).
Directionality (ρ500, D0): fwd/bwd second binds 33/41; **backward binds self-filter** (dwell 0.34 vs 1.09 ms;
force −2.39 vs +1.14 pN; vel-at-bind −2.24 vs +7.98 µm/s). D0≈D2: +1.49 vs +1.86 µm/s (within ±0.8 seed SD),
maxGap D0=D2=11.8 nm. **Invalid states / solver failures: 0 / 0 at every density and setting.**

**Load-bearing caveats for the GPU contract:**
- The **"0 exc>50 nm" invariant is a production-range (ρ≤750) property, NOT all-density.** At ρ≥1500 the joint-gap
  tail re-emerges (seed-intermittent); at ρ3000 seed 102 blows up. GPU high-density equivalence must reproduce the
  **same seed-intermittent blow-up under matched RNG**, not a clean tail → RNG keyed to **persistent dimer ID** (§12).
- Equivalence bar (task §10): for the dimer, CPU and GPU solve the **same equations**, so deterministic
  (Brownian-off) fixtures V1–V8 target near-bit agreement (Tier-1 coord <1e-4 nm, force <1e-3 pN, double); stochastic
  mats target seed-clustered CI agreement (Tier-3), not bitwise identity.
- Mean bound **dimers** and two-head fraction are not tabulated per density (only bound-heads); adjudicate those on
  CPU↔GPU self-consistency, not an external number.

## 3a. Phase G1a — RESULT: primary FLOAT kernel (scaled) validated; DOUBLE retained as oracle only (2026-07-20)

**Precision correction (supersedes the initial "double mandatory" call).** The primary device kernel is
`ExplicitHmmDimerGpu.solveOneDimerFloat` — **float**, working in **physically scaled units (nm, pN, rad)**. The
double flat kernel (`solveOneDimer`) is kept ONLY as (a) a diagnostic that is bit-identical to the object oracle
(isolating any float error from a port bug) and (b) alongside the object `ExplicitHmmDimer.solve` as the permanent
CPU oracle. Run: `./scripts/run_hmm_gpu.sh -g1a-validate` (builds + runs; nonzero exit on any failed fixture).

**Why the earlier "double mandatory" was WRONG (an ill-scaling artifact, not a float limit).** In raw SI/µm units
the 19×19 system mixes translational blocks (~1e-3 N/m) with rotational blocks (~1e-19 N·m/rad²) — a ~1e16
disparity — so the min pivot was 3.87e-19. Rescaling to (nm, pN, rad) makes every entry O(1)–O(500) (branch axial
12.6, shared axial 420 pN/nm; converter 128, bind 512 pN·nm/rad²; node drag 3.77 pN/nm; F8 spring 1 pN/nm), and
**the min pivot becomes ≈ 4.75–5.02** — squarely float-safe. Conversions (1e6/1e-6/1e12) are folded into the scaled
params at pack time; no conversion factor survives inside the kernel.

FLOAT primary vs the DOUBLE object oracle — **judged by physical tolerances, not bit agreement** (near-bit is not
required):

| fixture | FLOAT result | coordErr (nm) | angleErr (rad) | forceErr (pN) | dbl-flat bit |
|---|---|---|---|---|---|
| V1 detached | PASS | 2.2e-5 | 1.5e-8 | 2.1e-4 | OK (0.0) |
| V2 one-head (3 pN) | PASS | 3.7e-4 | 1.5e-8 | 1.6e-3 | OK (0.0) |
| V3 double-bound | PASS | 2.7e-4 | 1.5e-8 | 2.8e-3 | OK (0.0) |
| V4 stroke (θ_s=ADP) | PASS | 2.5e-4 | 4.2e-7 | 8.0e-4 | OK (3.5e-15) |
| V5 moving-actin | PASS | 3.0e-4 | 2.4e-6 | 1.5e-3 | OK (1.7e-15) |
| V6 high-strain (8/−6 pN) | PASS | 1.7e-3 | 1.5e-8 | 7.3e-3 | OK (0.0) |
| 100-step one-head | PASS | 4.8e-4 | 1.5e-8 | 2.7e-3 | OK (0.0) |
| 100-step double-bound | PASS | 3.0e-4 | 1.5e-8 | 1.1e-3 | OK (0.0) |
| 100-step moving-actin | PASS | 2.3e-3 | 1.5e-8 | 7.3e-3 | OK (0.0) |

**MAX float↔oracle: coord 2.3e-3 nm (physical tol 0.5), angle 2.4e-6 rad (tol 5e-3), force 7.3e-3 pN (tol 0.5) —
220×/2000×/68× under the material thresholds. Min pivot scaled-float 4.75 vs raw-double 3.87e-19. Max post-step
residual 79.9 (finite ⇒ no pathological growth). Invalid 0, solve failures 0. → FLOAT SUFFICIENT: YES; no named
failure ⇒ NO mixed precision. READY FOR TORNADOVM WRAP (float): YES.**

The double-flat diagnostic remains bit-identical to the object oracle (max 3.5e-15 nm, last-ULP trig), proving the
port equations are exact — so the float errors above are pure float rounding, not a port bug. Existing CPU mat path
byte-unaffected. Files: `ExplicitHmmDimerGpu.java` (double `solveOneDimer` diagnostic + float `solveOneDimerFloat`
primary), `ExplicitHmmDimerGpuValidation.java`, `scripts/run_hmm_gpu.sh`, `ExplicitHmmDimerGpuParams` (scaling
constants, `DEFAULT_PRECISION=FLOAT`).

## 3b. Phase G1b — RESULT: FD-tangent float kernel LOWERS and RUNS on the GPU (2026-07-20)

`ExplicitHmmDimerGpuKernel.solveBatchFloat` — the batched (`@Parallel`, one work-item per dimer) TornadoVM form of
the scaled-float G1a solve — **lowers to PTX and executes on the RTX 5070**, matching the CPU float kernel within
physical tolerances. Run: `./scripts/run_hmm_gpu.sh -g1b-validate`.

**The flagged risk materialized and was resolved at implementation level (no math change, task §7).** First lowering
attempt threw `TornadoInliningException: solveOneK ... node count (984) exceeds limit (600)` — the exact FD-tangent-
inline-pressure risk. Resolved with **`-Dtornado.compiler.fullInlining=true`** (bypasses the per-callee 600-node cap)
+ `-Dtornado.tvm.maxbytecodesize=262144`. **No analytic-tangent fallback was needed — the exact finite-difference
tangent runs on the GPU.** Implementation-level PTX adaptations only: `FloatArray`/`IntArray` device types; fixed
topology passed as an `IntArray` (no static-array access); **runtime loop bounds from `counts`** (defeats unroll-
explosion); `Math.acos`→`facos` poly (Math.acos does not lower); `Math.abs`→manual `fabs`; `Math.cos/sin/sqrt` lower
natively. The MATH is unchanged.

| gate | result | gate | result |
|---|---|---|---|
| TORNADOVM LOWERING | PASS | GPU EXECUTION VERIFIED | YES (PTX, no bailout) |
| L1 detached / L2 one-head / L3 moving-actin | PASS | L4 heterogeneous(8) / L5 medium(128) | PASS |
| V1–V6 | PASS (all) | A/B relabel / polarity | PASS |
| 100-step lockstep / free-run | PASS | GPU status failures / invalid | 0 / 0 |

**Float GPU↔CPU errors (physical tol judged): coord 1.46e-3 nm (tol 0.01), angle 2.56e-6 rad (tol 1e-5), force
1.35e-2 pN (tol 0.05), gap 2.0e-4 nm (tol 0.02).** Min pivot 5.03 (scaled). Device: NVIDIA RTX 5070, peak util 34 %.
First-call compile 1.31 s; steady per-execute 9.0/9.6/10.3/10.3 ms at 1/8/64/256 dimers, 11.0 ms at 1024 (LAUNCH/
TRANSFER-bound at these sizes — EVERY_EXECUTION host↔device copy each step; real compute-scaling shows at G2/G3 with
active batching + device residency, the standing ~8000-launch/s ceiling lesson). Memory 2272 B/dimer (324 state +
1948 scratch) → 6.8 MB @ 3000 dimers. **READY FOR G2 ACTIVE-DIMER BATCHING: YES.** Files:
`ExplicitHmmDimerGpuKernel.java` (device kernel), `ExplicitHmmDimerGpuValidation.runG1b`, `scripts/run_hmm_gpu.sh -g1b-validate`.

## 3c. Phase G2/G3 — RESULT: persistent device-resident state + active-only batching (2026-07-20)

Persistent lawn state lives on the GPU across timesteps and only active dimers are solved. Backend labelled
**HYBRID — GPU mechanics, CPU assay orchestration** (binding/chemistry/gather/actin stay CPU, per scope). Run:
`./scripts/run_hmm_gpu.sh -g23-validate [-total N -steps M -yspan10 K]`.

**Design.** `ExplicitHmmDimerGpuState` uploads the full lawn state (`D`, scratch) ONCE (`FIRST_EXECUTION`) and it
evolves in place on the device; each step re-uploads only the compact control (`activeIds` + `inF8` + `inThs` +
`counts`, `EVERY_EXECUTION`) and pulls the full state only on demand (`UNDER_DEMAND` + `res.transferToHost(D)` at a
report stride). `ExplicitHmmDimerGpuKernel.solveActiveFloat` runs one work-item per ACTIVE dimer, indexing resident
state/scratch by the **persistent dimer id** `activeIds[a]` (never the compact slot `a`); `WorkerGrid.setGlobalWork`
is resized to the padded active count per step. Per-step F8/θ_s inputs arrive compact (active-indexed) and are
injected into resident `D` before the solve. Inactive dimers are never visited ⇒ state + scratch preserved; distinct
active ids ⇒ disjoint blocks ⇒ race-free.

**Ownership contract (this milestone):** GPU-authoritative after init — node coords, head angles, converter/F8
geometry, scratch, mechanical diagnostics. CPU-authoritative — active-cull decision, binding, chemistry, occupancy
exclusion, gathering, actin, and the injected F8/θ_s inputs. No two independently-evolving authoritative copies (the
CPU float mirror is a validation oracle, not a second authority).

| criterion | result (total=1200, 60 steps, 17 % active) |
|---|---|
| init state pack == CPU objects | PASS |
| persistent state resident across steps | YES (uploaded once, evolves on device) |
| only active dimers solved | YES (kernel over activeCount, persistent-id indexed) |
| active-list valid (in-range / sorted / no-dup) | PASS |
| inactive state preserved | PASS (drift **0.0** exactly) |
| reactivation observed + correct | PASS |
| CPU↔GPU mechanics valid | PASS — coord 1.9e-3 nm, angle 0.0, force 4.3e-3 pN |
| no full-state transfer per step | YES — 7408 B/step vs 777 600 B G1b full state (**105× less**) |
| throughput vs G1b | **1.87×** (resident-active 10.0 ms/step / 100 steps/s vs dense-all+full-transfer 18.7 ms/step / 54 steps/s) |

**Throughput honesty.** At these active counts the resident path is **launch-bound** (10.0 ms/step is flat from 137→
206 active — the fullInlining'd kernel's per-launch cost dominates the actual solve compute). The realized **1.87×**
therefore comes mainly from **transfer elimination** (resident state never re-uploaded; ~105× less per-step copy);
active-only batching is correct and adds compute headroom that becomes the dominant win only at much larger active
counts (compute-bound regime). This matches the standing "~8000-launch/s / launch-count-bound" device lesson.
**READY FOR G4: YES.** Files: `ExplicitHmmDimerGpuState.java`, `ExplicitHmmDimerGpuKernel.solveActiveFloat`,
`ExplicitHmmDimerGpuValidation.runG23`, `scripts/run_hmm_gpu.sh -g23-validate`. `DEVICE_VALIDATED` still false.

## 3d. Phase G4 — the CPU timestep-order ORACLE (task §2) + scope

**The exact CPU per-step order** (`ExplicitHmmDimerGlidingHarness.step`, the semantic oracle the GPU timestep must
match). Note the mechanics solve is the LAST force stage (consumes the F8h computed mid-step); actin integrates
BEFORE the dimer solve:

| # | stage | CPU function | GPU status |
|---|---|---|---|
| 0 | sync head angles + pivot positions into the bind view | `G.phi/psi/A` refresh | trivial |
| 1 | active-cull update (near-filament dimers) | `updateActive` (perp-dist ≤ cullR) | G2/G3 = CPU list; G4 target |
| 2 | binding proposal: search + gate | `geom2D`→`nearestSeg2D`→`gate2D` (g0–g7) | reuse `MatSoaSlice.matGeomGate`/`matBind` (TBD) |
| 3 | same-filament 5.4 nm occupancy exclusion + D0/D1/D2 | partner-mat-coord check | intra-dimer (partner head) — no global atomic |
| 4 | commit bind (boundSeg, bindArc, bindStep) | `mot.boundSeg/bindArc.set` | device write |
| 5 | chemistry (nucleotide cycle) | `NucleotideCycleSystem.cycleLymnTaylor` | reuse GPU kernel |
| 6 | stroke rest-angle (θ_s) | `thetaS4a` per motor | device map |
| 7 | place head + bond forces (F8h) | `geom2D`→`placeHead2D`→`CrossBridgeSystem.bondForces` | reuse GPU `bondForces` |
| 8 | CSR gather + chain forces | `csrHistogram/Scan/Scatter/segGather` + `chainForces` | reuse GPU CSR |
| 9 | net axial force + per-head stats | host reduction | device reduction |
| 10 | actin dynamics: confine + load + Brownian + integrate + derive | `BrownianForceSystem`+`RigidRodLangevinIntegrationSystem`+`DerivedGeometrySystem` | reuse GPU integrator |
| 11 | **per-dimer coupled forked-tail MECHANICS solve** | `ExplicitHmmDimer.solve` | **DONE (G1b/G2/G3 kernel)** |
| 12 | writeback forceDotFil + forceMag (next-step catch-slip) | `mot.forceDotFil/forceMag.set` | device write |

**Backend modes (task §22) — landed:** `-backend cpu | gpu-mech | gpu-full-experimental | gpu-validate` (+`gpu`
alias) in `ExplicitHmmDimerGpuParams.Backend`; `gpu-mech` = the G2/G3 hybrid, `gpu-full-experimental` = the full G4
device timestep. All device modes refused unless `DEVICE_VALIDATED` or `-gpu-experimental` (never silent fallback).

**Reuse map (from the device-pipeline audit — the single-head mat timestep already exists device-resident).** A
complete device-resident single-head gliding timestep is built + validated in `MatSoaSlice.buildTrajGraph` (calibrated)
and `ExplicitCompleteMatHarness.buildGlidingGraph` (explicit beam) — 43-task FIRST_EXECUTION-resident graphs, actin
advanced on-device across steps, CPU≡GPU-validated. The MotorStore bind-state arrays (`boundSeg`/`bindArc`/
`nucleotideState`/`forceDotFil`) are the SAME buffers the CPU path uses ("one impl, two runners").

| oracle row | reuse for the dimer |
|---|---|
| 1 cull | REUSE `MatSoaSlice.matCull` (or keep the CPU G2/G3 list) |
| 2 bind search + gate | ADAPT `matGeomGate`/`matBindExplicit` — per-HEAD already (dimer = 2·nDim motor slots), minor |
| 3 5.4 nm exclusion | **NEW** small intra-dimer constraint kernel (partner head, no global atomic) |
| 4 bind commit | REUSE `matBind`/`matBindExplicit` |
| 5 chemistry | REUSE `NucleotideCycleSystem.cycleLymnTaylor` (unchanged) |
| 6 stroke θ_s | REUSE `matCock` |
| 7 place + bond force | REUSE `matPlaceHead` + `CrossBridgeSystem.bondForces` (per-head, unchanged) |
| 8 CSR gather + chain | REUSE `csr*`/`segGather` + `ChainBendingForceSystem.chainForces` (unchanged) |
| 10 actin integrate + Brownian + derive | REUSE `BrownianForceSystem`+`RigidRodLangevinIntegrationSystem`+`DerivedGeometrySystem` (unchanged) |
| 11 **forked mechanics** | **DONE** — `solveActiveFloat` (needs a bridge: read `bondData`/`nuc`→D inputs, write D head geom→MotorStore body) |
| 12 forceDotFil writeback | REUSE (the mechanics kernel already emits it) |

So the genuinely NEW work is: (a) a device **bridge** between the forked-dimer float state (`D`) and the MotorStore
body pose the reused bind/bondforce/place kernels consume; (b) the intra-dimer 5.4 nm exclusion kernel; (c) unify into
one persistent graph. Everything else is reused validated device kernels.

**Honest scope + sub-milestone plan.** Even with this reuse, G4 = a large integration (the `D`↔MotorStore bridge is the
crux) + a large validation matrix (T1–T10, 100/1000/5000-step, 4-density mats × 4 seeds, directionality, benchmark) —
materially bigger than any prior single-kernel phase. Structured, separately-verifiable sub-milestones (mirroring how
G1a/G1b/G2/G3 were sized):
- **G4a — DONE (2026-07-20).** `D`↔head-geometry bridge + dimer bind-search/gate + 5.4 nm exclusion on device,
  validated vs the CPU `nearestSeg2D`/`gate2D`/`filMatCoordUm` oracle. `ExplicitHmmDimerGpuKernel.dimerBindGate` (one
  work-item/dimer, head A then B sequentially ⇒ intra-dimer exclusion is race-free); reads resident `D` head xF8 (nm→
  µm) so gate2D is verbatim; writes shared `MotorStore.boundSeg`/`bindArc`. Run `./scripts/run_hmm_gpu.sh -g4a-validate`.
  Results: **bridge round-trip PASS** (geomCK==geomC, 1.6e-4 nm — the crux de-risked); **GPU kernel lowered+executed
  PASS** (small kernel, no fullInlining); **T4 PASS** (900-dimer mat, 3 real binds, device CPU+GPU == oracle on all
  1800 heads, bindArc 1.1e-8 µm); **T5 exclusion PASS** (0→reject, 5.3→reject, 5.4→accept, 5.5→accept). Binding is a
  dynamic search (static pose binds rarely); negative decisions on 1797 heads validated too. `DEVICE_VALIDATED` false.
- **G4b — DONE (2026-07-20).** Chemistry + stroke + catch-slip + bondForces + CSR gather on device, wired to the
  resident dimer state via two bridge kernels: `ExplicitHmmDimerGpuKernel.cockAndPlaceFromD` (D head geom nm→µm →
  `MotorStore.body` pose, the `placeHead2D` analog, + θ_s from nucleotide) and `bondDataToD` (head-side F8 N→pN →
  `D.O_F8A/B`; along-fil load → `MotorStore.forceDotFil`). The reused device kernels (`cycleLymnTaylor`, `bondForces`,
  `csrHistogram/Scan/Scatter`, `segGather`, `zeroAccumulators`) are chained UNCHANGED into one 10-task TaskGraph. Run
  `./scripts/run_hmm_gpu.sh -g4b-validate`. Results: **bridge #1 == placeHead2D** (pose max 2.4e-4 nm); **full GPU
  sequence lowered+executed**; **T1/T2/T3 PASS** (unbound / one-head / double-bound force+gather == CPU, bond err
  1.5e-4 pN, gather err 2.7e-4 pN); **T6/T7 PASS** — chemistry + catch-slip **bit-identical** CPU↔GPU (0 nuc mismatch;
  the deterministic per-motor wang-hash RNG gives event-identical transitions). `DEVICE_VALIDATED` false. Next: G4c
  (actin integrate + Brownian + derive + unified persistent graph; T8/T9/T10).
- **G4c — DONE (2026-07-20).** Actin integrate + Brownian + derive + y/z confine (`yzConfine`, new) + **one unified
  persistent device-resident task graph** for the complete timestep. `ExplicitHmmDimerGpuValidation.buildG4cGpu` chains
  17 stages — bind (G4a `dimerBindGate`) → chem (`cycleLymnTaylor`) → cock+place (`cockAndPlaceFromD`) → `bondForces` →
  feedback (`bondDataToD`) → zero → CSR (`csrHistogram/Scan/Scatter`) → `segGather` → `chainForces` → `yzConfine` →
  `brownianForce` → `integrate` → `orthogonalizeY` → `derive` → mechanics (`solveBatchFloat`) — FIRST_EXECUTION-resident
  state (filament + dimers advance on-device across steps), EVERY_EXECUTION step counters, UNDER_DEMAND pull. Run
  `./scripts/run_hmm_gpu.sh -g4c-validate [-density N -steps M]`. Results: **unified GPU graph lowered + executed
  device-resident PASS**; **T8 moving-actin feedback PASS** (motors bind 2, drive the actin, glide −1.13 nm, invalid 0,
  solveFail 0); **CPU-runner ↔ GPU-graph trajectory PASS** — the entire deterministic timestep is bit-close (max Δ
  1.2e-4 nm over 40 steps, bound GPU=CPU=2). Mechanics is full-batch (dense) here — the G2/G3 active-list is the
  optimization, combined in G4d. T9 polarity / T10 permutation deferred to G4d. `DEVICE_VALIDATED` false.
- **G4d — CORE DONE (2026-07-20); full promotion matrix REMAINING.** CPU↔GPU aggregate equivalence of the unified
  device timestep (velocity + bound heads): device CPU-runner vs GPU-graph **PASS** (Δvel 5.4e-7 µm/s, Δbound 0). **Perf/
  sync audit** (§21): 17 kernel launches/step, **1 sync/step**, **32 B/step host→device** (step counters only — all state
  resident), 1.36 MB resident; **GPU 85.6 steps/s vs CPU-runner 2.8 (~30×)**. Run `./scripts/run_hmm_gpu.sh -g4d-validate`.
  **HONEST — NOT completed this session** (compute-heavy standing runs, the promotion gate keeping `DEVICE_VALIDATED`
  false): device-path-vs-PRODUCTION-CPU aggregate equivalence at ρ100/500/750/1500 × ≥4 seeds × 5000 steps (velocity,
  mean bound, continuity, fwd/bwd 2nd binds, gap p99.9, peak branch force); D0≈D2 directionality + backward-bind
  self-filter + T9 polarity; fold the G2/G3 active-list into the unified graph (currently full-batch dense mechanics);
  preserve the ρ1500 seed-intermittent high-gap tail.

`DEVICE_VALIDATED` stays false throughout G4. The mechanics stage (row 11) is device-resident from G1b/G2/G3.

### G4a–G4d final status: BINDING/CHEMISTRY/FORCE-GATHER/ACTIN-INTEGRATION BACKEND = **GPU** (full device timestep = YES).

## 3f. High-strain bond-rupture failsafe — MECHANISM done (2026-07-20); threshold promotion pending the sweep

A physical constitutive rupture rule + a separate emergency gap failsafe, both device-resident float, inserted into
the unified timestep AFTER actin motion + bound-site refresh and BEFORE the forked solve (so a large imposed
displacement is released before it becomes an extreme branch force / joint gap). `ExplicitHmmDimerGpuKernel.ruptureCheck`
(one work-item/dimer): per bound head computes **B1 bondDisp = |actinSite(post-move) − headTip|**, **B2 branchExt/strain**
(head A seg {3,4}, head B seg {3,5}), **B3 force = myoSpring·bondDisp** (R4 diagnostic). Modes: **R0** disabled (default),
**R1** bond-displacement, **R2** branch-extension/strain, **R3** combined (R1∨R2, the candidate), **R4** force (diagnostic),
**R5** emergency joint-gap (releases the most-strained still-bound head, counted separately). Per-head independent release
(both only if both qualify); score `bondDisp/bondNm + posBranchExt/branchNm + posStrain/strainNm` ranks the R5 emergency
pick. Release = FREE_BINDABLE + clear bindArc/forceDotFil + nucleotide→NONE (no invented ATP); a dedicated per-motor
event code (1 physical / 2 emergency). Run `./scripts/run_hmm_gpu.sh -rupture-validate`.

Validation: **F1/F2/F6/F9 threshold PASS** — the release set matches `{bondDisp>threshold}` EXACTLY per head (moving-actin
fixture; actin shift ≠ bondDisp because heads carry a nonzero residual stretch, so the rule is tested against its own
metric); **R0 preservation PASS** (mode 0 ⇒ 0 releases even at 40 nm — byte-identical, gated so the kernel isn't even
called); **CPU↔GPU event-identical PASS** (CPU 4 == GPU 4 releases at shift 25). Controls (§22): `-ruptureMode 0..5`,
`-bondReleaseNm`, `-branchReleaseNm`, `-branchReleaseStrain`, `-ruptureForcePn`, `-emergencyGapNm`, `-ruptureEmergency`
(startup prints all thresholds when active). **Default RUPTURE_MODE=0 (disabled) — NOT promoted.**

**DEFERRED (compute-heavy standing runs, the threshold-promotion gate):** §12 reproduce the ρ1500 ~162 nm / ρ3000 ~32149
nm R0 runaway; §13 threshold screen ρ750/1500/3000 × ≥4 seeds × 5000 steps; §14 physical-success gate (0 >50 nm
excursions @1500, no µm runaway @3000, 0 emergency, 0 invalid/solveFail); §15 biological-range false-positive ρ100–750
(velocity/bound Δ<10%, D0 unchanged); §16 lifetime-tail; §17 D0≈D2; §18 dt. The likely promoted rule is R3 with bond
~20–30 nm + branch ~7.5–15 nm + emergency gap ~50 nm, but the actual values must come from the sweep.

## 3e. Phase G5 — active-list fold + promotion decision (2026-07-20)

**Active-list fold (§2/§3) DONE + validated.** `ExplicitHmmDimerGpuKernel.cullDimers` writes a per-dimer active
flag (either head xF8 within cullR perp of any segment — the `updateActive` predicate); `solveGuarded` runs the
forked mechanics ONLY on active dimers, indexed by **persistent dimer id** (loop index, never a compact slot) ⇒
permutation-invariant + inactive state preserved. Folded into the unified timestep (`cull` + guarded `mech` replace
dense `solveBatchFloat`). Run `./scripts/run_hmm_gpu.sh -g5-active-validate`. **A1–A6 dense≡active PASS** (identical
RNG; inactive mechanics is a no-op — unbound dimer F8=0 ⇒ dq≈0 at equilibrium — so dense and active agree: max Δcoord
1.0e-3 nm, Δangle 2.2e-6 rad, Δactin 0.0 nm, 0 solveFail; avg active 16%). **GPU cull + guarded-mechanics lowered +
executed PASS.**

**DIMER-MECHANICS BROWNIAN — ADDED + RE-GATED (the former code gap, now CLOSED).** `solveOneK` gains the FDT thermal
noise: node force `SP_BRN_NODE·g(SALT_NODE+(j·131+k)·7919)` on the RHS + angle torque `SP_BRN_{PHI,PSI}·g(SALT_ANG+hi·2)`,
where `g` is the deterministic wang-hash Box–Muller Gaussian (`brownGaussF`) and `ep = seed + persistentDimerId·101`
(persistent-id RNG, §8). Amplitudes `sqrt(2kT·gamma/dt)` pre-scaled into `sp` (pN / pN·nm). Because the hash draws are
IDENTICAL to the CPU oracle, it stays deterministically comparable (not stochastic-aggregate). Re-gate (`-g5-active-
validate`): **one-step device(Brownian) == object-oracle(Brownian) PASS** (coordErr 8.2e-3 nm, angleErr 1.5e-5 rad —
float-vs-double of matched draws); **dynamic binding at gap=0: Brownian-ON bound 11 vs Brownian-OFF bound 3** ⇒ the
thermal head-search now drives realistic binding (the under-binding gap is closed). Default `brownOn=0` (counts slot
C_BROWN) ⇒ all prior Brownian-off gates unchanged.

**PROMOTION DECISION: `DEVICE_VALIDATED` stays FALSE — now blocked ONLY by the production matrix (the code gap is
closed).** Remaining (compute-heavy standing runs, §5–§13): production-CPU-vs-GPU aggregate equivalence at
ρ100/200/400/500/700/750/1500 × ≥4 seeds × 5000 steps (all §6 observables); D0≈D2 + backward-bind self-filter;
T9 polarity (hard gate); long-run ≥20000-step stability; ρ3000 stress (seed 102).

GPU is NOT default; CPU remains default + the permanent oracle. Commands `-g5-production-matrix|-directionality|
-stress|-benchmark` are the standing-run drivers (the matrix above); not run this session.

**G4 bottom line.** The complete explicit-HMM dimer gliding timestep — bind + gate + 5.4 nm exclusion + chemistry +
stroke + catch-slip + bond forces + CSR gather + chain + confinement + Brownian + actin integration + forked mechanics
— **exists and runs as one device-resident TaskGraph on the GPU**, CPU↔GPU validated per-step (1.2e-4 nm) and aggregate
(Δvel 5e-7). The remaining work is the compute-heavy production-physics-equivalence-at-scale that gates the
`DEVICE_VALIDATED` promotion — a standing run, not new device code.

## 4. Phase status

- [x] Foundation — `ExplicitHmmDimerGpuParams` (gate + backend selector + frozen topology + standing-config self-check).
- [x] §4 term-by-term mapping table (this doc).
- [x] Reference gate table (this doc).
- [x] **G1a — flat SoA forked-mechanics kernel; float primary + CPU validation vs `ExplicitHmmDimer.solve` (9/9 PASS, §3a).**
- [x] **G1b — TornadoVM float kernel lowered to PTX + GPU-executed; CPU↔GPU physical validation (all gates PASS, §3b). FD tangent lowers via fullInlining — no analytic fallback needed.**
- [x] **G2/G3 — persistent device-resident state + active-only batching (all criteria PASS, §3c). 1.87× vs G1b, 105× less per-step transfer; HYBRID (GPU mechanics, CPU assay).**
- [x] **G4a — device bind search + gate + 5.4 nm exclusion + the D↔geometry bridge (§3d G4a). CPU-oracle-matched.**
- [x] **G4b — chemistry + stroke + catch-slip + bondForces + CSR gather on device (§3d G4b). Chem bit-identical CPU↔GPU.**
- [x] **G4c — unified 17-stage full device-resident timestep (§3d G4c). Glides; CPU↔GPU bit-close (1.2e-4 nm).**
- [x] **G4d CORE — CPU↔GPU aggregate equivalence + perf/sync audit (§3d G4d).**
- [x] **G5 — active-list folded into the unified graph + dense-vs-active validation A1–A6 (§3e).**
- [ ] G5 promotion matrix — production-CPU-vs-GPU equivalence at ρ100/500/750/1500 × ≥4 seeds × 5000 steps +
      directionality + long-run stability (compute-heavy standing runs; the remaining `DEVICE_VALIDATED` gate).

## 5. Final status block

_To be filled as gates pass. Current:_
```
GPU FORKED-DIMER KERNEL IMPLEMENTED: YES — TornadoVM @Parallel float kernel, LOWERED to PTX + GPU-EXECUTED (RTX 5070)
GPU TOPOLOGY: fixed Ms=3,Ma=1,Mb=1 (NF=5, 19 DOF), specialized (not generic); topology passed as IntArray
GPU PRECISION: FLOAT (scaled nm/pN/rad) — min pivot 5.03; double retained only as CPU oracle + diagnostic. NO mixed precision.
GPU TANGENT: FINITE-DIFFERENCE (exact) — runs on GPU via -Dtornado.compiler.fullInlining=true; NO analytic fallback needed
GPU LINEAR SOLVER: flat 19×19 Gauss–Jordan partial-pivot, float (per work-item scratch, SC_M stride 380)
GPU DEVICE-RESIDENT STATE: YES (FIRST_EXECUTION upload once, evolves on device; UNDER_DEMAND pull)
ACTIVE LIST BUILT ON: CPU (G2/G3 compact persistent-id list; unified G4c graph uses full-batch — active-list fold = G4d remaining)
MECHANICS/BINDING/CHEMISTRY/GATHER/ACTIN BACKEND: GPU (G4 — full device-resident timestep, one 17-task TaskGraph, CPU↔GPU validated)
FULL DEVICE TIMESTEP: YES (G4c); per-step host↔device 32 B, 1 sync, 17 launches (G4d audit); GPU 85.6 steps/s vs CPU-runner 2.8
BINDING BACKEND: GPU (dimerBindGate)  CHEMISTRY BACKEND: GPU (cycleLymnTaylor)  FORCE-GATHER BACKEND: GPU (CSR/segGather)  ACTIN-INTEGRATION BACKEND: GPU (RigidRodLangevin)
ACTIVE-LIST MECHANICS IN UNIFIED GRAPH: YES (G5 — cullDimers + solveGuarded; dense≡active validated A1–A6)
V1 DETACHED / V2 ONE-HEAD / V3 DOUBLE-BOUND / V4 STROKE / V5 MOVING-ACTIN / V6 HIGH-STRAIN: PASS (all, float physical tol)
100-STEP ONE-HEAD / DOUBLE-BOUND / MOVING-ACTIN: PASS (all)
MAX FLOAT↔ORACLE COORD ERROR: 2.26e-3 nm (tol 0.5)   ANGLE: 2.4e-6 rad (tol 5e-3)   FORCE: 7.3e-3 pN (tol 0.5)
MIN PIVOT scaled-FLOAT / raw-DOUBLE: 4.75 / 3.87e-19    MAX POST-STEP RESIDUAL (float): 79.9 (finite)
DOUBLE-FLAT PORT BIT-IDENTITY (diagnostic): OK (3.5e-15 nm)
INVALID STATES: 0   SOLVE FAILURES: 0   FLOAT SUFFICIENT: YES (no named failure ⇒ no mixed precision)
FULL DEVICE TIMESTEP (G4c): YES — bind+gate+exclusion+chem+stroke+catch-slip+bondforce+gather+chain+confine+brownian+integrate+derive+mechanics, ONE device-resident TaskGraph
ACTIVE-LIST FOLD (G5): YES — cullDimers + solveGuarded; dense≡active A1–A6 PASS; permutation-invariant (persistent-id)
CPU↔GPU DEVICE-PATH EQUIVALENCE: per-step 1.2e-4 nm (G4c), aggregate Δvel 5e-7 (G4d); chem bit-identical (G4b)
CPU ORACLE RETAINED: YES (double object solver)
DIMER-MECHANICS BROWNIAN: ADDED + re-gated (one-step matched to oracle 8e-3 nm; dynamic binding 3→11) — code gap CLOSED
DEVICE_VALIDATED: FALSE — now blocked ONLY by the production matrix (compute-heavy standing run); no remaining code gap
GPU DEFAULT ENABLED: NO
CPU DENSITY SWEEP NEEDS REINTERPRETATION: NO
READY FOR LARGE GPU DIMER SWEEPS: NO (device timestep exists + CPU↔GPU-validated; production-equivalence pending)
PRIMARY REMAINING LIMITATION: (a) device forked-mechanics is Brownian-OFF — add per-dimer thermal noise keyed to
           persistent id for production binding fidelity; (b) launch-bound at moderate active counts (fullInlining'd
           kernel per-launch cost); the resident/active win so far is transfer-elimination (32 B/step, 105× less copy).
NEXT STEP: (1) add dimer-mechanics Brownian (brownTorqueD node+angle, keyed persistent-id+step+seed) to solveOneK +
           re-gate; (2) run the production matrix (ρ100–1500 ×≥4 seeds ×5000 steps, device-vs-production-CPU, §5–§13);
           (3) T9 polarity + D0≈D2 + long-run stability; then the DEVICE_VALIDATED decision.
```
