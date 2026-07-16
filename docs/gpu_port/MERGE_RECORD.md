# GPU motor-port — infrastructure merge record

**Decision: `MERGED — infrastructure only; GPU execution remains gated`.**

Merged 2026-07-16 into `dt-convergence-study` (the canonical development line containing base
`67892a4` and the canonical-gliding study `0efe527`). NOT pushed to origin (local merge; push on request).
This is an infrastructure merge, NOT a production promotion and NOT a motor-model recalibration. No
canonical motor is production-ready on GPU.

## Reviewed commit list (base `0efe527` → tip `456e135`)
Ordered so each commit compiles (dependencies — kernels, then gates/packer — precede the harness that uses them):
1. `e16c5bb` docs: add GPU motor-port architecture and validation plan
2. `1aecc34` gpu: add canonical two-body Step-7 kernels
3. `048096b` gpu: add closed support gates and registry parameter packing
4. `177e26b` test: add deterministic motor replay harness and fixtures
5. `456e135` tools: add guarded GPU benchmark scripts

Each of the code-bearing commits (2, 3, 4) was checked out and **compiled clean** in isolation; the tip builds.

## Files added / modified
- Added: `docs/gpu_port/*` (10 md), `docs/GPU_PORT_PLANNER_HANDOFF.md`, `softbox/TwoBodyGpuKernels.java`,
  `softbox/MotorGpuParams.java`, `softbox/MotorReplayHarness.java`, `fixtures/gpu_port/*` (30 golden
  fixtures + inventory + validation report), `scripts/gpu_port/{_guard,bench_beam_microbench,bench_gliding_gpu}.sh`.
- Modified (surgical, `-gpu` error path only — NO mechanics): `softbox/TwoBodyConverterMotor.java`
  (runMotorModel routes ALL `-gpu` through the closed gate), `softbox/LaserTrapHarness.java` (`-gpu`
  message de-staled + model-aware).
- NOT committed: `UNBLIND_KEY_DO_NOT_GIVE_ANALYST.json`, `unblind_comparison.txt` (pre-existing, unrelated).
  `*.class` and `RUN_LOGS/` are gitignored (no binaries/run-outputs staged).

## Post-merge CPU regression (in the canonical tree, at the merge tip)
- `-motor-regression` Gates A–F: **VERDICT PASS** — A frozen-param PASS; B common-core identity max|Δ|=**0**;
  C explicit registry≡frozen max|Δpose|=**0**; D calibrated registry≡frozen max|Δpose|=**0**;
  E live stroke explicit=**7.27**(7.27), calibrated=**6.90**(6.90); F serialize/restart PASS.
- `MotorReplayHarness -genfixtures` self-test: **30/30 bit-identical**.
- `MotorReplayHarness -validatekernels`: **30/30** — fixed/calibrated float32 maxAbsΔ ≤ 2.6e-8 (T3),
  explicit double **bit-identical Δ=0** (T4).
- `MotorReplayHarness -verifyparams`: **T0 registry→device params PASS**.
- ⇒ **CPU canonical behavior is unchanged** (mechanics/chemistry/params bit-identical; strokes unchanged).

## GPU support remains CLOSED behind explicit gates
`MotorGpuParams.DEVICE_VALIDATED=false`. Any `-gpu` request is refused clearly, naming the model and the
missing device gates, with **no silent CPU fallback and no silent model swap** (verified for calibrated
and explicit). This also **fixed a latent silent fallback**: `calibrated-s2-l40 -gpu` previously would have
run on CPU (its `gpuSupported()` is true) — now refused. `gpuSupported()` (capability) is unchanged;
device-validation is a separate gate that opens only when all device gates pass.

## Post-merge device-validation plan (NOT run — GPU work deferred)
Phase A device primitives (registry→device param exactness, RNG key identity, CSR gather identity,
fixed/calibrated/explicit device replay) → Phase B explicit beam microbench (thousands of configs;
distributions for node/pose/force/torque/contour/energy/iterations/failure/throughput/utilization; measure
the faithful-double speedup BEFORE approving an analytic Jacobian) → Phase C calibrated promotion (chemistry/
binding/force-transfer/gliding/continuity/bound-count/ATP-per-µm/force-balance/half-dt/measurement, promote
only on CPU/GPU ensemble agreement within SEM + CPU-double arbiter) → Phase D explicit integration.
Full plan: `docs/gpu_port/FINAL_STATUS_REPORT.md` §10 + `docs/GPU_PORT_PLANNER_HANDOFF.md` §6.

## Unresolved risks
- Explicit beam GPU throughput unknown until measured (faithful double-FD may be FP64/occupancy-limited on
  the RTX 5070; analytic-Jacobian rewrite is the identified exact lever — decide from the MEASURED speedup).
- The Step-7 kernels are source-only, not yet wired into a device TaskGraph; Steps 1–6 device systems are
  validated elsewhere but the integrated gliding graph + online-reduction kernel remain to be built.
- CPU-double stays the authoritative arbiter for the chaotic/bistable gliding steady state.

## Recommendation
The branch is **ready for hardware validation** (Phase A once the GPU is free). Do NOT begin a large
laser-tweezers campaign; run the primitives → beam microbench → calibrated promotion sequence, keeping
the CPU explicit-beam solver as the oracle and CPU-double as the basin arbiter. No canonical motor is to
be described as production-ready on GPU until its device + ensemble gates actually pass.
