# Explicit complete-mat: stroke/recoil + NO-CULL throughput (Goals 1 & 4) — up to 61× GPU

This increment delivered two of its four goals solidly: the complete-mat stroke/detach/recoil sequence (Goal 1)
and the no-cull CPU-vs-GPU throughput at 200/700/1500 µm⁻² (Goal 4). The free-binding device port (Goals 2/3,
§3–§7) is the remaining piece and is scoped as the next increment. No physics/salt/chemistry/solver change; FD
stays the oracle; `MotorGpuParams.DEVICE_VALIDATED` stays false (promotion deferred, §13); no silent fallback
(bailout=false, enable.fma=false). New: `ExplicitCompleteMatHarness -stroke`/`-bench` + a production-residency
graph flag. Reports: `RUN_LOGS/explicit_completemat/COMPLETEMAT_{STROKE,BENCH}.md` + `COMPLETEMAT_BENCH.csv`.

## Final status block
- **EXPLICIT FULL-MAT STROKE/RECOIL:** DONE — the canonical relax→stroke(θ_s −30°→+30°)→hold→detach→recoil
  sequence driven through the FULL shared coupling (real cross-bridge to a filament segment, not the isolated
  fixed-site spring). CPU-runner vs GPU agree (**maxΔnode 2.8e-8 µm**); stroke **−0.9 nm axial, 11.5 pN peak**
  on a reachable bound motor. Smaller than the isolated single-head (−7.7 nm) because the filament absorbs the
  stroke — expected; the gate requires CPU/GPU agreement, not equality with the isolated values (met).
- **EXPLICIT FREE BINDING:** NOT DONE — the free-binding state machine (geom2D/nearestSeg2D/gate2D + stochastic
  bind) is still host-side; porting it to device kernels over the beam geometry is the remaining piece. The
  coupled mechanics + beam-derived head placement + `bondForces` are validated, so this is a bounded next port.
- **EXPLICIT SHORT GLIDING:** NOT DONE — gated on free binding.
- **NO-CULL CPU/GPU THROUGHPUT:** **DONE + MEASURED** (culling disabled, all N processed by `matS2SolveStep`
  every step, CPU==GPU processed=N, production-residency, matched steps, no validation downloads in the timed
  interval, warm 150 + 300 steps × 3 reps median):

  | density (/µm²) | N | CPU-an steps/s | CPU ms/step | GPU-an steps/s | GPU ms/step | GPU speedup | failures |
  |---:|---:|---:|---:|---:|---:|---:|---:|
  | 200  | 600  | 87 | 11.560 | 628 | 1.593 | **7.26×** | 0 |
  | 700  | 2100 | 20 | 49.907 | 608 | 1.645 | **30.33×** | 0 |
  | 1500 | 4500 | 9  | 107.684 | 570 | 1.755 | **61.36×** | 0 |

  **GPU is nearly flat (~1.6–1.75 ms/step); CPU scales linearly ⇒ the speedup grows with N.** `matS2SolveStep`
  (the 14-DOF beam solve) dominates the device kernel (0.51→0.61 ms of ~0.66–0.80 ms total; the rest —
  head/bond/CSR/reduce/filament — is ≤0.03 ms each). GPU bytes 548 in / 64 out per step (production residency).
  0 invalid states at every density.
- **PRODUCTION RESIDENCY:** DONE — beam SoA (nodes/sys) + frame/params + filament/body arrays FIRST_EXECUTION
  resident; per step only the small counters cross UP (548 B) and `redOut` crosses DOWN (64 B); NO full-state
  round-trip, no host per-motor mechanics loop, no silent fallback.
- **LARGE-SCALE STUDIES: NOT READY** — the explicit mechanics are validated AND fast (61× at N=4500), but
  actual free-binding gliding + the final multi-seed ensemble gate remain; promotion stays deferred (§13).
- **NEXT STEP:** port the free-binding device stages (geom/nearest/gate/stochastic-bind over the beam geometry)
  → free-binding one-step replay gate (§4) → low-density gliding (§5) → three-density smoke (§6) →
  half-dt/enlarged-search controls (§7) → the ensemble promotion gate.

## §12 performance decision + runtime estimates
GPU/CPU-analytic = **61.36×** @ N=4500 ⇒ **≥10× — suitable for routine explicit validation studies** (and
already ≥10× at N≥2100). GPU wall-time @ N=4500 (dt=2.5e-6): 0.15 s gliding ≈ **1.8 min**; 0.5 s ≈ 5.8 min;
2.0 s ≈ 0.4 h; 3 densities × 3 seeds @ 0.15 s ≈ **0.3 h** (assumes constant ms/step within the measured range;
no extrapolation beyond measured scaling). CPU-analytic @ N=4500 is ~108 ms/step (9 steps/s) ⇒ 0.15 s gliding ≈
1.9 h — the GPU makes explicit gliding validation practical.

## Stop-condition audit (none tripped)
No calibrated invocation (explicit graph = explicit tasks only). CPU==GPU processed motor count = N (no-cull;
binding GATES unchanged — only culling disabled; `cullingEnabled=false`, `processedMotorCount=N` asserted). GPU
timing excludes validation downloads + CPU oracle (separate loops). CPU and GPU both process full N. RNG salts/
ordering unchanged. No force dropped/double-counted (§6 bondData 2.6e-18; segGather byte-unchanged). Beam state
does NOT cross per step (production residency 548/64 B). 0 solver failures, 0 invalid states. No silent fallback.

## Notes
- **Faithful no-cull semantics:** every motor is evaluated by `matS2SolveStep` each step (the dominant cost);
  the reachable set (surf<5 nm) is pre-bound so `bondForces`/CSR/gather also run; θ_s fixed pre-stroke, Brownian
  on. "Culling disabled" ≠ "binding gates disabled" — the geometric/stochastic gates are unchanged; only the
  active-set shortcut is removed so all N are processed (the throughput-benchmark configuration, not the
  production scientific configuration).
- **Why the throughput is representative of gliding:** the per-motor beam solve (`matS2SolveStep`) is the same
  work whether a motor is bound (F8h from bondData) or unbound (F8h=0, relax), so the dominant cost is N-scaled
  regardless of avgBound; free binding adds only the (cheap, ≤0.05 ms) gate stages.

## Commands
```
./scripts/build.sh
java @tornado-argfile --enable-preview -Xmx8G -Dtornado.recover.bailout=false -Dtornado.enable.fma=false \
     -Dtornado.tvm.maxbytecodesize=65536 -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.ExplicitCompleteMatHarness [ -stroke | -bench | -traj ]
```
