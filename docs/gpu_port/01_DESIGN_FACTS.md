# GPU-port foundational design facts (established before the CPU map completes)

## Registry is already the single source of truth (Phase 2 dispatch)
`softbox/MotorModel.java` is a PURE enum descriptor: frozen params (`ExplicitS2Params.frozenL40()`,
`CalibratedS2Params.frozenL40()`, `FixedAnchorParams`), provenance, `CostModel(cpu,gpu)`, aliases,
`serialize()`/`parseSerialized()`, `isRestartCompatible()`. `TwoBodyConverterMotor.assertFrozenParamsConsistent`
already cross-checks the registry numbers bit-for-bit against the live `EXP4G_*` code constants.
⇒ **GPU constants are GENERATED FROM / VALIDATED AGAINST this registry — never forked.** The dispatch
key is the `MotorModel` id; `gpuSupported()` is `true` only for CALIBRATED_S2_L40 (and trivially
FIXED_ANCHOR); EXPLICIT_S2_L40 is `gpu=false` and a `-gpu` request must FAIL, never silently swap.

## RNG is counter-based (wang-hash) ⇒ bit-exact CPU≡GPU is achievable
The two-body path draws randomness statelessly via wang-hash keyed by integer coordinates, e.g.
`TwoBodyConverterMotor.gaussianTorque`: `h = ((seed*2654435761L) ^ (t*40503L) ^ 0x33445566L)` then
avalanche → gaussian; `gaussDet(level,i,which)` similarly keys `(level,i,which)` with distinct salts.
There is NO stateful `java.util.Random` in the hot path. This matches the shared SoftBox RNG idiom
(BrownianForceSystem/NucleotideCycleSystem/BindingDetection all wang-hash keyed by (entity,step,seed,salt)).
⇒ A GPU kernel keying the SAME wang-hash by `(motorId, step, transitionSalt)` reproduces the identical
random stream **independent of thread order**. Isolated-step and short prescribed-sequence gates can be
**bit-exact**; only long chaotic gliding trajectories need statistical (SEM) comparison (float op-order).

## TornadoVM kernel + TaskGraph idiom to follow (from MotorStrokeHarness / MotorJointSystem)
- Kernel = a `static void` method over planar SoA `FloatArray`/`IntArray` buffers + an `IntArray counts`
  trailer, body is `for (@Parallel int m = 0; m < nM; m++) { ... }`. No `KernelContext`, no atomics.
- Imports: `uk.ac.manchester.tornado.api.{TaskGraph,GridScheduler,WorkerGrid1D,TornadoExecutionPlan,
  enums.DataTransferMode}`, `...types.arrays.{FloatArray,IntArray}`, `...annotations.Parallel`.
- Plan: `new TaskGraph("name").transferToDevice(FIRST_EXECUTION, staticBufs...)
  .transferToDevice(EVERY_EXECUTION, counts).task("t", Class::method, args...)
  .transferToHost(UNDER_DEMAND, outBufs...)`; `.snapshot()` → `new TornadoExecutionPlan(...)`.
- `GridScheduler` with one `WorkerGrid1D(nThreads)` per task, `setLocalWork(64,1,1)` (the RNG/trig
  register-file rule — CLAUDE.md); single-thread scan tasks use `WorkerGrid1D(1)` localWork 1.
- Execute: `plan.withGridScheduler(sched).execute()`; `res.transferToHost(...)`.
- Hard limits: **≤15 args per `task()`** (⇒ pack vectors as one planar buffer, not 3 component arrays);
  a kernel method may not be named `kernel`; `Math.log`/`Math.exp`/`asin`-poly LOWER on PTX but
  `Math.acos` does NOT (use `accurateAcos`).

## Constants
`Constants.kT` (SI), `Constants.deltaT=1e-4` default (NOT the two-body dt), `Constants.brownianForceMag(dt)=
sqrt(2kT/dt)` (dt is the CALLER's stepping dt — single-source-dt invariant). Two-body dt: explicit
requires **2.5e-6** (`ExplicitS2Params.dtRequired`), calibrated production uses its own (ts_cull used 1.25e-6).

## Cost anchors (from sweep logs, for perf targets)
explicit-s2-l40 ≈ 10.5k s wall / sim-s (≈2.9 h per simulated second) @ ~370 active motors, matx12;
calibrated ≈ 540 s/sim-s; ~linear in active-motor count. Explicit beam solve = the dominant target.
