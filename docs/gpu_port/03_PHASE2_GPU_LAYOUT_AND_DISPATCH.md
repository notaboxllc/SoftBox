# Phase 2 — GPU SoA layout & canonical dispatch design

Goal: a device-resident SoA representation that preserves stable model identity across FIXED_ANCHOR /
CALIBRATED_S2_L40 / EXPLICIT_S2_L40, with constants generated from (never forked from) the `MotorModel`
registry. Follows the established SoftBox device idiom (planar `FloatArray`/`IntArray`, `@Parallel` over
motors, ≤15 task args, `WorkerGrid1D` localWork 64, wang-hash RNG, atomics-free CSR gather).

## 1. Dispatch: model identity is a compile-time-selected kernel, params are uniform buffers

The three models share Steps 1–6 and differ only at Step 7 (§Phase1). Dispatch design:

- **One `MotorModel` id** selects, at plan-build time, WHICH Step-7 kernel is wired into the TaskGraph:
  `stepFixed2x2` / `stepCalib5x5` / `stepExplicitBeam`. There is NO per-thread `switch(model)` in the hot
  kernel — the model is fixed for the whole run (a gliding run is single-model), so it is a plan-build
  branch, not a warp-divergent runtime branch. This preserves the semantic distinction cleanly.
- **`gpuSupported()` gate:** the harness refuses `-gpu` for EXPLICIT_S2_L40 in the *production* path
  (registry `gpu=false`) unless the explicit-beam GPU kernel has passed its Phase-5 gates and is
  explicitly promoted. A `-gpu` request for a CPU-only model must **fail with a clear message, never
  silently fall back to CPU or swap to the surrogate** (registry limitation text; CLAUDE.md rule).
- **Constants come from the registry:** a build-time `MotorGpuParams.from(MotorModel)` reads
  `m.calibrated()` / `m.explicit()` and packs the frozen scalars into a uniform `FloatArray`/`DoubleArray`
  param buffer uploaded `FIRST_EXECUTION`. `assertFrozenParamsConsistent` already guards CPU-side; the GPU
  packer asserts the same `==` equalities so a divergent GPU constant fails fast. **No hardcoded motor
  constants in any kernel.**
- **Serialization identity** rides along unchanged: `MotorModel.serialize()` tokens tag every GPU output
  file / checkpoint; `isRestartCompatible` still rejects cross-model restart (explicit node state vs
  reduced pivot are not interchangeable).

## 2. SoA buffers (device-resident, per motor unless noted)

Naming: `f*` = FloatArray, `i*` = IntArray, `d*` = DoubleArray. `N` = allocated motors, `nSeg` = 12.
Vectors packed **planar** (x = [m], y = [N+m], z = [2N+m]) to respect the 15-arg task limit.

### 2a. Shared core (all three models)
| buffer | type | size | contents |
|--------|------|------|----------|
| `fA` (pivot/anchor pos) | Float | 3N | movable pivot `A` (fixed = anchor; explicit = node M) |
| `fPhi`, `fPsi` | Float | N each | generalized angles φ, ψ |
| `fThetaS`, `fPsiActin` | Float | N each | converter rest target + bound-head target |
| `fFrame` (b̂,ê_up,ê_conv) | Float | 9N | body frame axes (derived each step by `derive`) |
| `fSiteXY` | Float | 2N | fixed lawn site (for cull/bind), static |
| `iBoundSeg` | Int | N | ≥0 site / FREE_BINDABLE / FREE_COOLDOWN (sentinel) |
| `fBindArc` | Float | N | arc-length along bound segment |
| `iNuc` | Int | N | nucleotide state NONE/ATP/ADPPI/ADP |
| `fLoadHist` | Float | N·W | forceDot boxcar (load-gating window) |
| `iActive` | Int | N | cull result (or drop: step all N) |
| `fBondData` | Float | 13N | STRIDE-13 F8 bond rows (head force+torque | seg reaction | …) |
| `fMotParams` (uniform) | Float | ~16 | dt, kF8, kconv, kbind, γφ, γψ, γpar, γperp, kT, … |
| `iCounts` (uniform) | Int | 4 | {N, step, seed, nSeg} — the wang-hash keys |

### 2b. CALIBRATED-only (movable pivot, analytic)
| buffer | type | size | contents |
|--------|------|------|----------|
| `fSupP0` | Float | 3N | rest pivot P0 (per motor) |
| `fSupAxes` (ûL, ê_conv, ê_up) | Float | 9N | load/transverse axes (or derive from frame) |
| `fCalParams` (uniform) | Float | ~14 | kAxTension/Compression, kTr, kFeTr, rMax, smoothings(ax/tr/buck), kFloor, buckleCrit, kCompPost, δ, refFreeLen — **from `m.calibrated()`** |

### 2c. EXPLICIT-only (beam) — **DoubleArray** (precision, §4)
| buffer | type | size | contents |
|--------|------|------|----------|
| `dNode` | Double | 3·(M+1)·N | node coords (M=4 ⇒ 15N), the ONLY persistent solver state |
| `dEmerge`, `dTan` | Double | 3N each | clamped emergence point + tangent |
| `dBeamParams` (uniform) | Double | ~8 | l0, ks=EA/l0, kb=EI/l0, γnode, kfloor, floorZ, M, dt — **from `m.explicit()`** (preserve EA/EI arithmetic form for the `==` gate) |
| per-thread scratch (registers/local) | Double | 14×15 aug + 14 rhs + (M+1)×3 forces | rebuilt each step; **no heap** |

### 2d. Filament (shared, reuse existing device stores unchanged)
`FilamentStore` SoA (`coord`, `uVec`, `yVec`, `zVec`, `end1/2`, `segLength`, `forceSum`, `torqueSum`,
`bTransGam`, `bRotGam`, `randForce`, `randTorque`, `params`, `counts`) + CSR scratch (`segCount`,
`segOff`, `segMyo`). These are the already-validated device kernels; the port REUSES them verbatim.

## 3. Kernel decomposition (TaskGraph tasks) — shared chain + model Step-7

```
zero(f.forceSum,f.torqueSum) →
[cull]  markActive           (or omit: step all N to raise occupancy)
bind    bindGate             (@Parallel over motors; per-motor gather over nSeg; wang-hash chemistry salt)
cycle   cycleLymnTaylor      (existing NucleotideCycleSystem kernel; wang-hash)
place   geomDerive+placeHead (frame + head pose from φ,ψ,A)
bond    bondForces           (existing CrossBridgeSystem kernel; STRIDE-13)
gatherA csrHistogramChunked  (PARALLEL chunked CSR — not the serial csr*)
gatherB csrScanChunked
gatherC csrScatterChunked
gatherD segGather            (existing; @Parallel over segments; atomics-free)
chain   chainForces + zConfine
brown   brownianForce        (filament; existing)
integr  integrate            (existing rigid-rod Langevin)
derive  orthogonalizeY+derive(existing)
STEP7   stepCalib5x5 | stepFixed2x2 | stepExplicitBeam   ← the model-dispatched kernel
reduce  onlineReductions     (bound count, ATP events, class counts; §Phase1 measurement)
```
Cadence-gated / host-pulled: the small per-step scalars for measurement (§4 measurement plan). Keep the
motor SoA device-resident across steps (`persistOnDevice`/`consumeFromDevice`); host reads only the
reduction scalars + cadence-sampled 12-element COM.

## 4. Precision policy (the load-bearing decision)
- **CALIBRATED + FIXED: float32.** The 5×5 solve is analytic (no nested FD); float32-tolerable. Matches
  the registry `gpu=true` and the all-FloatArray SoftBox precedent. Bit-exactness vs CPU-double will NOT
  hold (the CPU uses double locals), so the gate is a preregistered float tolerance (Phase-4 gate doc),
  cross-checked by the CPU-double arbiter for basin decisions (CLAUDE.md GPU-number-trust rule).
- **EXPLICIT: DoubleArray required for a faithful FD port** (nested central difference needs ~1e-10
  resolution; float32 destroys the tangent — Phase-1 §3c). BUT a consumer RTX 5070 runs FP64 at a small
  fraction of FP32, so the faithful-double beam kernel may NOT hit 10× at 400 motors. **The exact lever
  is an ANALYTIC stretch+bending Jacobian** (the derivative of the SAME energy — not a surrogate, not an
  approximation) which removes both FD layers and becomes float32-tolerable. Phase 5 ports the faithful
  double-FD version FIRST (bit-comparable, correctness), benchmarks it, then evaluates the analytic-
  Jacobian float32 path as the perf route. **Never** replace the beam with a cheaper surrogate to gain
  speed (that is what CALIBRATED_S2_L40 already is, and it is a distinct model).

## 5. What preserves model identity (the invariant checklist)
1. Constants packed from `MotorModel.{calibrated,explicit}()` with the same `==`-guarded arithmetic.
2. Step-7 kernel selected by id at plan build — never a runtime cross-model branch.
3. `gpuSupported()` refuses CPU-only models on `-gpu` (no silent swap/fallback).
4. Wang-hash RNG salts reproduced bit-for-bit (per-motor `+m·7919`, chemistry `0x4E55` etc.).
5. Force-onto-actin via the same atomics-free CSR gather (bit-identical to CPU).
6. `serialize()`/`viewerMetaJson()`/`isRestartCompatible()` tags on every GPU output; explicit drawn as a
   beam, calibrated as a "reduced surrogate" glyph, fixed as a rigid anchor (registry `viewerRepresentation`).
