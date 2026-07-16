# GPU motor-port — concurrent-phase FINAL STATUS REPORT

Scope: engineering + validation groundwork for porting the canonical two-body motor models to the GPU,
executed **concurrently with, and provably isolated from, the authoritative overnight CPU sweep.** No
motor mechanics/chemistry/binding/parameters changed. Detailed docs: `docs/gpu_port/{00..05,README}.md`.

## 1. Architecture summary
- **Shared Steps 1–6 + a model-dispatched Step-7.** All three models (fixed-anchor / calibrated-s2-l40 /
  explicit-s2-l40) share the validated bind-gate → Lymn–Taylor chemistry → F8 `bondForces` → atomics-free
  CSR `segGather` → filament rigid-rod Langevin chain, and differ ONLY in the per-motor coordinate solve:
  rigid 2×2 (fixed) / analytic 5×5 movable pivot (calibrated) / 14-DOF implicit beam (explicit).
- **The GPU port = reuse the existing device systems for Steps 1–6 + one new Step-7 kernel per model**,
  wired into the TaskGraph by `MotorModel` id at plan-build time (no runtime cross-model branch), with
  constants packed from the frozen `MotorModel` registry (never forked; `==`-guarded).
- **Precision:** calibrated/fixed → float32 (analytic solve); explicit beam → double (nested-FD tangent).
- **Determinism:** counter-based wang-hash RNG ⇒ the random stream is bit-identical CPU↔GPU by key; force
  gather is a deterministic atomics-free CSR ⇒ bit-identical. So isolated-step gates are exact/tight-float;
  only long chaotic gliding needs ensemble-within-SEM with the CPU-double basin arbiter.

## 1b. Isolation outcome (empirically confirmed)
The sweep ran undisturbed. During this work the driver finished the `tierBx` explicit run (8/8 seeds,
measured **10,570 s/sim-s** — confirming the §00 estimate) and **launched a fresh `denssx` JVM (PID
31911, 00:56)**. That new sweep JVM loaded the sweep dir's `softbox/*.class`, which remained the **frozen
`09af9ae7…` fingerprint (mtime 21:15)** throughout — my worktree builds never touched them. A live sweep
JVM spawning mid-work and loading untouched classes is the isolation design working exactly as intended.
Zero GPU invocations during the concurrent phase (`GPU_INVOCATION_LOG.md`).

## 2. Files added / changed (isolated worktree `/home/jba/Code/SoftBox-gpu`, branch `gpu-motor-port`)
- Docs: `docs/gpu_port/00_PROVENANCE_AND_ISOLATION.md`, `01_DESIGN_FACTS.md`,
  `02_PHASE1_CPU_CALLGRAPH.md`, `03_PHASE2_GPU_LAYOUT_AND_DISPATCH.md`,
  `04_VALIDATION_GATES_AND_TOLERANCES.md`, `05_STRATEGY_FORCE_MEASURE_PERF.md`, `README.md`,
  `GPU_INVOCATION_LOG.md`, this report.
- Code: `softbox/MotorReplayHarness.java` (CPU replay + fixture generator; new file, no existing file
  edited). `softbox/TwoBodyGpuKernels.java` + kernel-validation mode (kernel-authoring pass — see §6).
- Scripts: `scripts/gpu_port/{_guard.sh,bench_beam_microbench.sh,bench_gliding_gpu.sh}` (guarded; refuse
  to run while the sweep is active).
- No change to any existing source; the running uncommitted `TwoBodyConverterMotor.java` diff was
  replicated into the worktree so the port references exactly what runs.

## 3. Build & run commands
```
# build (isolated worktree only — NEVER in /home/jba/Code/SoftBox)
cd /home/jba/Code/SoftBox-gpu && nice -n19 ./scripts/build.sh
# CPU replay harness + fixtures (CPU-only)
TDIR="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx/share/java/tornado"
nice -n19 java --enable-preview -cp "$TDIR/tornado-api-4.0.1-dev.jar:." softbox.MotorReplayHarness -genfixtures
nice -n19 java --enable-preview -cp "$TDIR/tornado-api-4.0.1-dev.jar:." softbox.MotorReplayHarness -replay <fixture>
nice -n19 java --enable-preview -cp "$TDIR/tornado-api-4.0.1-dev.jar:." softbox.MotorReplayHarness -validatekernels   # CPU-runner kernel check
# GPU benchmarks (POSTPONED until the sweep frees the HW; scripts self-refuse if it hasn't)
scripts/gpu_port/bench_beam_microbench.sh
scripts/gpu_port/bench_gliding_gpu.sh -motor calibrated-s2-l40 -density 1000 -matx 4 -maty 1 -dur 0.15 -seeds 8 -cpuarbiter
```

## 4. CPU profiling results (existing logs + code cost model)
Per-model wall (sweep logs): explicit-s2-l40 ≈ **10,500 s/sim-s** (matx12, ~370 active motors),
calibrated ≈ **540**, fixed < calibrated; ~linear in active-motor count. Per-phase split (code-derived;
no per-phase profiler exists): **Step-7 dominates** — explicit beam ≈ 95 %+ of the explicit per-motor
cost (the ~750-eval nested-FD tangent), calibrated Step-7 ≈ 1.34 µs vs shared Steps 1–6 a comparable
minority. N = round(density·matX·matY) = 4000 motors, 40k steps; ~400 active/step (occupancy concern).

## 5. Replay-fixture inventory
30 golden fixtures (10 per model) under `RUN_LOGS/gpu_port_dev/fixtures/`: bound-baseline,
multistep-determinism (25 steps), pre-stroke (ADP·Pi), post-stroke (ADP), Brownian (RNG path),
unbound-search, and strain regimes (low/high axial, transverse, near-detachment; explicit adds
bending-dominated, nearly-taut, mixed bend+extension). Each stores model id + serialize token +
full input state + golden output (pose, reaction F/τ, and for explicit the 5 nodes + contour error).
**Determinism self-test: 30/30 bit-identical (Δ=0).** Skipped-and-logged: a mid-flight near-capture
geometry (the 3E binding-search state machine is a separate pathway); FIXED_ANCHOR is athermal (its
Brownian flag is inert). Note: `NucleotideCycleSystem` is NOT invoked in an isolated mechanics step
(nuc state only selects the F8 rest angle) ⇒ chemistry-transition validation is a full-loop gate.

## 6. Validation matrix — CPU-runner kernel checks (T3–T5) [DONE — 30/30 PASS]
`softbox/TwoBodyGpuKernels.java` (3 `@Parallel` kernels: `fixedStep`/`calibratedStep` float32,
`explicitBeamStep` double) validated on the CPU runner (kernel methods called as sequential loops — the
"one implementation, two runners" idiom) via `MotorReplayHarness -validatekernels` against all 30 golden
fixtures. Full table: `RUN_LOGS/gpu_port_dev/fixtures/KERNEL_VALIDATION.md`.

| gate | model | quantity | tolerance | RESULT |
|------|-------|----------|-----------|--------|
| T3 calib/fixed isolated step | fixed + calibrated (20 fx) | pose + reaction (float32 vs double golden) | rel ≤ 1e-4, abs ≤ 1e-6 | **20/20 PASS**, maxAbsΔ 4e-9…2.6e-8 (≪ gate) |
| T4 explicit isolated step | explicit (10 fx) | 38 fields: nodes/pose/C-xH-xF8/contourErr/F8h (double) | rel ≤ 1e-6 | **10/10 PASS, bit-identical (Δ=0)** |
| T5 short sequence | all | 25-step multistep terminal pose | accumulated T3/T4 | **PASS** (covered by the multistep fixtures) |
| — bug caught by the gate | explicit | `s2Solve` uses `eup` (not `econv`) as the generalized-force axis | — | transcription fixed → bit-identical (NO tolerance loosened) |
| T0 frozen params | all | GPU constants vs registry | exact `==` | pending the device-param packer (trivial; registry is SoT) |
| T1 RNG stream | all | wang-hash draw by key | bit-identical | inherited exact (kernels copy `brownTorque` integer arithmetic bit-for-bit) |
| T2 CSR gather | all | force onto actin | bit-identical | device pass, post-sweep (reuses the validated system) |
| T6 ensemble gliding | calibrated | vel/avgBound/… | within SEM + arbiter | device, post-sweep |

## 7. Known numerical differences (expected, not bugs)
- calibrated float32 vs CPU-double: last-bit-scale rounding in the 5×5 solve (T3, ≤1e-4). Long gliding
  decorrelates chaotically — compare by ensemble/SEM + CPU arbiter, never bit-identity.
- explicit beam float32 (IF the analytic-Jacobian path is pursued): larger (T4′ ≤1e-3); the faithful
  double-FD kernel stays ≤1e-6.
- The >0.1 % systematic = logic-bug signal, <0.1 % = float32 floor rule governs triage.

## 8. Performance (estimates; measured numbers pending HW)
Milestone ≥10× end-to-end for explicit. Estimates (§05): one 0.5 s explicit seed ~88 min CPU → ~9 min
@10× / ~25 min if FP64-limited; 8-seed reduced tier ~2.4 h → ~14 min @10×. **The ≥10× target for the
explicit beam likely REQUIRES the analytic stretch+bending Jacobian** (exact derivative of the same
energy — not a surrogate) to remove the nested FD and unlock float32 (consumer FP64 ≈ 1/64 FP32);
the faithful double-FD kernel is expected at ~3–5× (FP64 + ~400-motor occupancy). Calibrated's gain is
occupancy/launch-amortization (step all N + batch seeds).

## 9. Remaining blockers
1. **GPU hardware is owned by the sweep** — all device execution (T2/T6/T7, all perf) is postponed; the
   guarded benchmark scripts self-refuse until it frees.
2. **Explicit beam perf** — the faithful double-FD kernel may miss ≥10×; the analytic-Jacobian rewrite is
   the identified lever and is the main remaining engineering item for the explicit model.
3. **Step-7 kernel integration into the full gliding TaskGraph** (Steps 1–6 already device-validated
   elsewhere; wiring + the online-reduction kernel remain).
4. **Occupancy** — step-all-N + seed-batching to fill the device at ~400 active motors.

## 10. Recommendation — when the GPU path is safe for production explicit-S2 sweeps
- **Calibrated-s2-l40:** promote to `-gpu` after (a) the CPU-runner T3 kernel check passes on all
  fixtures (this pass), (b) a post-sweep T6 ensemble gliding run agrees within SEM with the CPU sweep AND
  the CPU-double arbiter, (c) T7 half-dt holds. Low risk (analytic, float32, registry-gated).
- **Explicit-s2-l40:** keep `-gpu` REFUSED until: (a) the Phase-5 beam microbench passes over thousands
  of fixtures (double kernel: node/pose/contour/energy distributions within T4, failure rate ≤ CPU), and
  (b) a T6 integrated ensemble agrees within SEM. Only then flip `gpuSupported()`. If the analytic-Jacobian
  float32 path is adopted for speed, it must first re-pass Phase 5 at T4′ and be shown to be the exact
  Jacobian (not an approximation). **Do not run a production explicit-S2 GPU sweep until both gates pass;
  until then the CPU sweep results remain the sole reference and must not be overwritten.**
