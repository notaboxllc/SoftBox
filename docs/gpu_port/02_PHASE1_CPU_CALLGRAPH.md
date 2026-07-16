# Phase 1 — CPU call graph & per-step data-flow (canonical two-body motor)

Source of truth: `softbox/TwoBodyConverterMotor.java` (8354 lines, worktree byte-matches the running
build), `softbox/LaserTrapHarness.java`, `softbox/MotorObservable.java`, `softbox/NucleotideCycleSystem.java`,
`softbox/CrossBridgeSystem.java`. This is a MAP for the GPU port — no optimization proposed here.

## 0. Top-line structure (the single most important fact)

The **gliding hot path does NOT step `Cmot` objects.** `Cmot` (single motor, `double[]` fields) is the
tweezers / characterization body. The **gliding assay steps a `Glide2D` "mat"** — parallel arrays over
`N` motors (`G.phi[m]`, `G.psi[m]`, `G.A[m]`, `G.thetaS[m]`, embedded `FilamentStore`/`MotorStore` SoA).
`Cmot` and `Glide2D` carry the **identical validated core physics**; the mat is the array-of-motors the
GPU maps one-motor-per-thread over. **All GPU work targets the `Glide2D`/mat step paths.**

The three models are **identical through Steps 1–6** and diverge ONLY in the per-motor coordinate update
(Step 7):

| step | FIXED_ANCHOR (`stepGlide2D`, 4549) | CALIBRATED (`stepGlideSup`, 5835) | EXPLICIT (`stepS2`/mat, 6333/6736) |
|------|-----------|-----------|-----------|
| 1 active-set cull | `unionActive` grid CSR | same | same |
| 2 bind (8-gate) | `nearestSeg2D`+`gate2D` | same | same |
| 3 chemistry | `cycleLymnTaylor` + `thetaS4a` | same | same |
| 4 head place + F8 | `bondForces` (STRIDE-13) | same | same |
| 5 gather→actin | CSR `segGather` | same | same |
| 6 filament integrate | chain+z+Brownian+Langevin | same | same |
| **7 coord update** | **2×2 (φ,ψ)** rigid pivot | **5×5 (P_b,P_e,P_up,φ,ψ)** analytic pivot | **14-DOF (3M+2)** implicit beam step |

⇒ **The GPU port is: one shared Steps 1–6 kernel chain + a model-dispatched Step-7 kernel.** Steps 1–6
are the already-validated device systems (BindingDetection, NucleotideCycle, CrossBridge, CSR gather,
BrownianForce, RigidRodLangevin) — largely reusable. Only Step 7 is new per-model GPU code.

## 1. Dispatch — `buildBoundMotor(MotorModel, dt)` (7804)

```
FIXED_ANCHOR      → buildSup(0, supOn=false, …)          // pivot pinned; stepSup delegates to 2×2 stepC
EXPLICIT_S2_L40   → buildBoundS2(freeLenNm, dt, settle)  // 5-node beam; g4On=true
CALIBRATED_S2_L40 → applyCalibratedFrozen(m.calibrated()); CAL_ON=true; buildBoundSup(0,…)  // movable pivot 5-DOF
```
The frozen calibrated params come from `MotorModel.CalibratedS2Params.frozenL40()`; explicit from
`ExplicitS2Params.frozenL40()`; both cross-checked by `assertFrozenParamsConsistent` (exact `==`).
⇒ **GPU constants are lifted from the registry, never re-hardcoded** (preserve EA/EI arithmetic form:
`kAxRef·1e-3·lRef·1e-9`, `kLatRef·1e-3·pow(lRef·1e-9,3)/3` — else the `==` gate breaks).

## 2. Per-motor STATE inventory (for the SoA layout, Phase 2)

**Shared across ALL models (the validated core):**
- pose/frame: `phi, psi` (generalized angles), `A`/pivot position (3), converter point `C`, head `xH`,
  F8 material `xF8`, frame axes `bhat, eup, econv` (each 3) — most are *derived* each step by `geom2D`.
- chemistry/binding (in embedded `MotorStore`): `nucleotideState` (int: NONE/ATP/ADPPI/ADP),
  `boundSeg` (int: ≥0 site / FREE_BINDABLE / FREE_COOLDOWN), `bindArc` (arc-length along seg),
  `forceDotFil/forceDotAvg/forceMag/cooldown` (load-gating history), `thetaS` (rest-angle target).
- F8/bond output: `bondData` STRIDE-13 row per motor (head force+torque | seg reaction force+torque | …).
- solver constants (uniform): `dt, kF8Code, kconvCode, kbindCode, gammaPhi, gammaPsi, gammaPar, gammaPerp`.

**CALIBRATED-only (movable pivot, `supOn=true`):** rest pivot `supP0`(3), load axis `supUL=b̂`(3),
transverse axes `supUT1=ê_conv, supUT2=ê_up`(3 each); anisotropic stiffness scalars
`supKsoftAx/supKtautAx/supDelta/supKsoftTr/supKfeTr/supRmax/supKfloor`; smoothings
`supSmoothAx/supSmoothTr/supSmoothBuck`; buckling `supBuckleCrit/supKcompPost`; drags `supGammaP`;
geometry `supLdist/supLs2/supFloorZ/supCompFrac`. **All fixed-size scalars/3-vectors.**

**EXPLICIT-only (`g4On=true`):** `g4Node[M+1][3]` node coordinates (**the only dynamic-size field**;
M=4 ⇒ 5 nodes × 3 = 15 scalars, 14 free DOF), `g4E`(3) clamped emergence, `g4Tan`(3) clamp tangent,
scalars `g4M/g4l0/g4ks/g4kb/g4gammaNode/g4kfloor/g4floorZ/g4Lc/g4slack`. Node coords are the ONLY
persistent solver state (no carried Jacobian; tangent rebuilt from scratch each step).

**FIXED-only:** none beyond the shared core (pivot pinned to `A`; `sup*` fields inert/for-measurement).

## 3. Step 7 physics — the model-divergent kernels

### 3a. CALIBRATED — analytic 5-DOF movable-pivot (`supSolveM` 5791, `supForceM` 5772)
- `supForceM`: pivot displacement `d = A[m] − supP0[m]`; axial `qL = d·b̂`, transverse `rT`. Anisotropic
  **softplus** force law: tension `Frest = ksoft·q + ktaut·softpos(q−δ)`; compression either the 4I
  Euler-buckling branch (`supBuckleCrit>0`: stiff k0 below crit, soft kpost above) or `compFrac`; radial
  transverse `Frad = ksoftTr·r + kfeTr·softpos(r−rMax)`; one-sided floor. Returns `{F(3), kAxTan, kTrTan,
  kFloorTan}`. `softpos`/`softpos_d` = softplus + logistic (`Math.log1p(exp)`; **PTX-lowerable**).
- `supSolveM`: builds 3×5 Jacobian `J` (∂world/∂{P_b,P_e,P_up,φ,ψ}), F8 Gauss-Newton `kfSI·JᵀJ` (5×5),
  converter+bind springs on the φ,ψ block, the **diagonal** anisotropic tangent `K[0][0]+=kAxTan,
  K[1][1]+=kTrTan, K[2][2]+=kTrTan+kFloorTan`, adds `γ/dt·I`, RHS = F8 + pivot force + converter/bind +
  **5 Brownian draws** (`brownTorque` salts `0x5F1+m·7919 … 0x5F5+m·7919`), one `solveLin(M,F,5)`
  Gauss-Jordan, updates `A[m], phi, psi`, `geom2D`. **No inner iteration; analytic; float32-tolerable.**

### 3b. FIXED — rigid 2×2 (`stepC`/`stepSup`): pivot pinned, only φ,ψ solved. Cheapest.

### 3c. EXPLICIT — 14-DOF implicit beam (`s2Solve` 6358 / `s2SolveM` 6736)
- Single **branch-free linearly-implicit Newton step** (NO inner iteration/tolerance/failure flag).
- Assembles the 14×14 system: **numeric beam tangent = central FD** of `s2NodeForces` over every free
  node coord (`hh=1e-5`), and `s2NodeForces` is *itself* a central FD of `s2BendEnergy` (`h=1e-5`) ⇒
  **nested FD ≈ 25 force-evals × 30 energy-evals ≈ 750 `acos`-bearing energy evals / motor / step** — the
  dominant cost; + node drag on the diagonal; + F8/converter Gauss-Newton block on the pivot+φ+ψ DOF.
- One `solveLin` **14×14 dense Gauss-Jordan** with partial pivoting (only data-dependent branch — tiny).
- Node 1..M get FDT Brownian (`brownTorque`, per-(m,node,component) salt); node 0 clamped (no Brownian).
- Pivot node M ≡ converter pivot; beam reaction + F8 solved **simultaneously** (no explicit hand-off).
- **All double.** Nested FD needs ~1e-10 resolution ⇒ **float32 destroys the tangent.** Also near-taut
  stretch cancellation + small-angle `acos`. ⇒ port keeps double OR (better, exact) replaces the FD
  tangent with an **analytic** stretch+bending Jacobian to become float32-tolerable. `s2RelaxHold`
  (iterative, data-dependent trip count) is **diagnostic-only, OUT OF SCOPE** for the stepper.

## 4. RNG — two stateless counter-based generators (order-independent ⇒ GPU-reproducible)
- Chemistry: `NucleotideCycleSystem.wangHash((m·1000003)^(step·999983)^(seed·7919)^salt)` → `u∈[0,1)`;
  salts `0x4E55` cycle, `0x52465241` refractory, `0x4D54` release. One draw/motor/step.
- Brownian: `brownTorque(γ,dt,ep,t,salt)` wang-mix → Box-Muller Gaussian × `sqrt(2kT·γ/dt)`. CALIBRATED
  draws 5/motor/step; FIXED active-unbound draws 2, bound draws 0; EXPLICIT draws 3·M/motor/step; each
  salt carries `+m·7919` (or `m·1009` in the beam) so every motor has an independent stream.
- **No `java.util.Random`, no per-motor RNG state.** A GPU kernel keying the same wang-hash by
  `(m, step, seed, salt)` reproduces the identical stream regardless of thread order → bit-exact.

## 5. Force accumulation onto actin — already race-free, atomic-free (ports cleanly)
`bondForces` (59) writes each bound motor's **seg-side reaction** into its private `bondData[d+6..d+11]`
(`d=m·13`) — per-motor private, no contention. Then `CrossBridgeSystem.segGather` (1370): build the
seg→bound-motors **CSR-inverse** (`csrHistogram/csrScan/csrScatter` keyed by `boundSeg`), and **each
segment thread sums its own motors** into its own `f.forceSum/f.torqueSum` slot (planar SoA: x=s,
y=nSeg+s, z=2·nSeg+s). One writer per segment ⇒ **no atomics, no reduction, CPU≡GPU bit-identical.**
The dense-scale `csrChunk*` variant produces a bit-identical CSR. The motor's own generalized-coord
reaction (φ,ψ,pivot) is applied in Step 7, never touching the filament arrays.
⇒ **Force-accumulation strategy #2 (staged per-motor output + segmented gather) is ALREADY the CPU
design** — the GPU port inherits it; atomic accumulation (strategy #1) is the alternative to benchmark.

## 6. GPU-porting blockers (per model)
- **Per-motor heap allocation** in `supSolveM`/`supForceM`/`geom2D` (calibrated) and `s2Solve`/
  `s2NodeForces`/`s2BendEnergy`/`solveLin` (explicit): dense `crs/sub/add/scl`→`double[3]` and
  `double[][]` matrices, thousands/motor/step. **Must scalarize to registers / per-thread local scratch;
  unroll the 5×5 (and 14×14) solves.** This is the single largest port cost.
- **Data-dependent branching (warp divergence):** the 8-gate bind short-circuit, the 4-state chemistry
  ladder, `supForceM` tension/compression/buckling/floor branches, `bondForces` state ternaries. Bounded
  and cheap individually; acceptable (SIMT executes all taken paths) but flag for occupancy.
- **Variable-length loop:** only `segGather`'s inner per-segment motor loop (bounded by motors/segment)
  and grid-cell occupancy in `unionActive` — both bounded, not convergence loops.
- **Precision:** calibrated = float32-friendly (analytic). Explicit beam = double-required (nested FD) on
  a consumer GPU whose FP64 ≈ 1/64 FP32 — the key perf risk; the analytic-Jacobian rewrite is the lever.

## 7. Harness orchestration, scene mapping & measurement

**Entry:** `LaserTrapHarness -motor <id> -glide` → `TwoBodyConverterMotor.runMotorModel` → `runMotorGliding`
→ `measureGlideModel` (7957); `-forcebalance` → `measureGlideForceBalance` (8045). `MotorObservable.java`
is a **naming-convention enum only — no reduction code.** The reductions live in those two functions.

**Scene mapping:**
- `N = round(density · matX · matY)` motors allocated. **Production (density 1000, 4×1) ⇒ N = 4000**;
  `steps = round(dur/dt)` = 0.1/2.5e-6 = **40 000**. Sites are hash-uniform over the lawn (fixed).
- **The log's "activeMotors/step ~369–447" is the CULLED active subset** (`candAcc/candSteps`) near the
  single ~2.1 µm, 12-segment filament — NOT N. So **~400 active of 4000 allocated per step.**
- Filament: one 12-segment chain (segLen ≈0.176 µm), integrated by the shared rigid-rod Langevin — tiny
  (12 elements). **The entire cost is the per-motor work over the active set.**

> **GPU-occupancy consequence (first-order design fact):** per-step motor parallelism is only ~400
> (culled) to 4000 (all). On a many-thousand-core GPU this UNDER-fills the device per kernel launch —
> the CLAUDE.md "launch-bound at small scale" regime. Strategy levers: **step all N (skip the cull on
> device), batch multiple seeds concurrently, and/or fuse the 7 phases into few kernels** to raise
> occupancy and amortize the ~115 µs/launch fixed cost. The explicit beam (750 evals/motor) has enough
> arithmetic intensity per thread to benefit even at 400 motors; the calibrated 5×5 does not — it will
> be launch/occupancy-bound unless N-stepped and seed-batched.

**Measurement — ALL online per-step reductions over the SoA arrays (no post-hoc trajectory scan):**
- **velMean** = `lsSlope` of the filament-COM-projected-on-b̂ samples recorded every `recEvery` steps
  (cadence-sampled 12-element `filComB`), then mean/sd over seeds. **net** = end−start COM.
- **avgBound / avgLB(load-bearing) / continuity / handoff / ATP-per-µm / transWander / angWander /
  bindRate / activePerStep** — each an online counter/Welford accumulator over a per-step full-N (or
  culled) scan of `boundSeg` / `nucleotideState` / `bondData`. Load-bearing test `matTaut` (7933):
  explicit = straightness `(contour−e2e)<1e-3`; calibrated = `supForceM[3]·1e3 ≥ 2.0`; fixed = always.
- **-forcebalance (`FBRes`, calibrated):** per bound motor project seg-side F8 onto glide dir
  `fp = −(bondData[d+6..8]·uSeg)`; accumulate propulsive (`fp>+band`) / dragging (`fp<−band`) / neutral
  (`|fp|<0.05 pN`) counts, impulses, work, per-class tail-strain/age/arc/nucleotide histograms,
  propulsive↔dragging switches; 42-col CSV. Sampled every 20 steps + per-step impulse/work integrals.

⇒ **GPU measurement plan:** compute these as **device-side reductions** (online counters over the motor
kernel), OR pull the handful of per-step scalars (bound count, ATP events, class counts) + the
cadence-sampled 12-element COM to host. Never export full per-motor state every step (task's measurement
rule). Detailed per-motor traces only in a diagnostic mode with sparse sampling.

**Timing:** only whole-loop `System.nanoTime()` around the seed loop → `secPerSimS`. **No per-phase
profiler exists** — a GPU port (and the Phase-1 per-phase timing breakdown) must add phase timers; the
per-phase split is derived here from the code cost model (beam ≈750 energy-evals/motor/step dominates;
calibrated ≈ one analytic 5×5; Steps 1–6 shared and cheap) + the log-measured per-model wall (§ provenance).

**Serialization / replay:** no `Glide2D` state serializer exists. Runs are **seed-deterministic** (all
RNG counter-based) — a fixture is fully specified by `(model, density, matX, matY, dur, dt, seed)` and
reconstructs bit-identically. **Isolated single-motor replay fixtures** are built by constructing a
`Cmot` (or minimal mat with N=1) in a prescribed pose/nucleotide/strain and stepping once — no serializer
needed. Add a `Glide2D` serializer only if mid-run many-motor GPU replay is later required.

## 8. Per-phase cost model (Phase-1 timing breakdown, code-derived + log-anchored)

No per-phase profiler exists, so the split is derived from structure × the measured per-model wall
(§provenance) and verified against arithmetic-op counts. Per **active motor per step**:

| phase | FIXED | CALIBRATED | EXPLICIT | notes |
|-------|-------|-----------|----------|-------|
| 1 cull (`unionActive`) | shared, ~O(nSeg·occupancy), cheap | | | grid CSR, built once |
| 2 bind gate | O(nSeg=12), 8 predicates | same | same | only unbound ADPPi motors |
| 3 chemistry | 1 wangHash + 4-state ladder | same | same | over all N |
| 4 bondForces (F8+F9/F10) | STRIDE-13 write | same | same | bound motors only |
| 5 CSR gather | O(N)+O(nSeg) | same | same | serial CSR today → chunked on GPU |
| 6 filament integrate | O(12) | same | same | negligible |
| **7 coord solve** | **2×2 (~tiny)** | **5×5 + analytic pivot (~1.34 µs)** | **14×14 + ~750 acos energy-evals (~58 µs)** | **the dominant term; ≈43× calibrated** |

⇒ **Phase-7 explicit beam solve ≈ 95 %+ of the explicit per-motor cost** (registry 58 of ~60 µs);
the shared Steps 1–6 are a few µs. For calibrated, Step 7 (1.34 µs) still dominates but the shared
Steps 1–6 are a comparable minority. **The GPU port's leverage is entirely in Step 7 for explicit, and
in occupancy/launch-amortization for calibrated.** A precise measured per-phase split will come from the
instrumented single-motor microbench built in Phase 5 (CPU-only, allowed during the sweep).
