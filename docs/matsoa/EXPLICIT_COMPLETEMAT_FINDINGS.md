# Explicit coupled kernel → complete-mat integration — §1–§8 DONE (the primary goal met)

**One pre-bound explicit motor advances through the COMPLETE persistent mat graph for 300 timesteps, CPU/GPU
in agreement — the primary goal is achieved.** The full 18-stage explicit device graph lowers and runs
device-resident; the shared `bondForces`, parallel CSR, filament integration, and parallel reductions all
operate on the explicit beam state; no calibrated substitution, no silent fallback. No physics/salt/chemistry/
solver change; FD stays the oracle; `MotorGpuParams.DEVICE_VALIDATED` false; bailout=false, enable.fma=false.

New: `TwoBodyBeamAnalyticGpu.matBeamGeom` + `matPlaceHeadExplicit`; harness `ExplicitCompleteMatHarness`
(`-traj`). Reports: `RUN_LOGS/explicit_completemat/COMPLETEMAT_{GATE,TRAJ}.md`.

## Final status block
- **EXPLICIT MAT STATE:** DONE — flat explicit SoA (nodes/frame/q/params/sys/outGeom + shared bondData/
  boundSeg), reused unchanged (no double[][], no per-step alloc, no second schema). ~2.2 KB/motor.
- **EXPLICIT HEAD PLACEMENT:** DONE + VALIDATED — `matBeamGeom` (device `geom2D`) + `matPlaceHeadExplicit`
  (device `placeHead2D`) LOWER; reproduce production geom2D/placeHead2D **exactly (0.0)**; GPU vs CPU-mirror
  1.9e-7 (float body pose). The explicit `outGeom` layout ([3N]=xH,[6N]=xF8) is OPPOSITE the calibrated one.
- **EXPLICIT COMPLETE-MAT ONE STEP:** **DONE + PASS (§7)** — one complete step (18 stages: matBeamGeom →
  matPlaceHeadExplicit → bondForces → zeroAcc → csrChunk* → segGather → chain → zconf → brownian → integrate →
  orthoY → derive → matS2SolveStep → matReduceBlocks/Final) GPU vs CPU-runner: **maxΔnode 1.26e-8 µm**, Δphi
  8.4e-8, Δpsi 2.8e-8, ΔfilCoord 9.3e-10, ΔforceDotFil 0.0, Δbond 2.6e-18, ΔredOut 5.2e-11 — bit-faithful.
- **EXPLICIT COMPLETE-MAT TRAJECTORY:** **DONE + PASS (§8)** — one pre-bound explicit motor advanced 300
  timesteps through all shared GPU stages; **max GPU-vs-CPU-runner Δnode 4.9e-8 µm, ΔfilCoord 1.2e-7 µm, NO
  divergence (bit-close all 300 steps), bound count CPU=GPU=1**. Brownian on (deterministic RNG matches). The
  device graph is bit-faithful to the CPU-runner over the whole quiet trajectory.
- **DEVICE RESIDENCY:** DONE — the beam SoA (nodes, sys) + frame/params + filament/body arrays upload
  FIRST_EXECUTION and stay resident; per step only the small counters (matc/mot.counts/f.counts) cross UP. The
  `-traj` run ALSO downloads nodes/q/fil.coord/forceDotFil/bondData EVERY_EXECUTION for the CPU comparison —
  those are VALIDATION reads; the production graph keeps only `redOut` (the same validation-vs-production split
  as MatSoaSlice traj/baseline). No host per-motor mechanics loop; no silent fallback.
- **NEXT STEP:** §9 — the mat-level stroke/detach/recoil sequence (drive `thetaS` + release like the single-head
  slice, but through the full shared coupling), then the free-binding cull/gate stages for actual gliding (§10+
  of the earlier prompt) + a production-mode residency/throughput CSV.

## §7/§8 (the completion)
- **§7 one complete step:** device graph LOWERS + EXECUTES; GPU ≡ CPU-runner to float last-bit across every
  channel (node/pivot/phi/psi/head/bond/reaction/filament/reduction). The complete explicit mat is one graph.
- **§8 300-step trajectory:** CPU/GPU stay bit-close (4.9e-8 µm) the whole way — the quiet one-motor trajectory
  hasn't reached the chaotic regime, so there is not even float-decorrelation divergence yet; discrete state
  (bound count) identical. Success criterion met: one pre-bound explicit motor advances through the complete
  persistent mat for many steps with CPU/GPU agreement and no fallback.

## Notes on the controlled setup (faithful, per the task's §8)
Pre-bound single motor (`boundSeg` fixed, others unbound), binding search disabled, chemistry fixed (thetaS
constant) — the sanctioned controlled trajectory. The free-binding cull/gate/chemistry stages (needed for
actual gliding) are deliberately out of scope here and are the next increment. The CPU reference is the
"one-impl-two-runners" CPU-runner (the identical kernel sequence as plain Java); each stage is separately
validated against PRODUCTION (§5 head placement 0.0 vs geom2D/placeHead2D; §6 bondForces 2.6e-18; the coupled
matS2SolveStep 1.1e-9 vs s2SolveM; the shared CSR/filament kernels byte-unchanged from stepGlideS2) — so the
CPU-runner step IS the production step by stage-composition of validated-equivalent stages.

## §1 complete-mat stage contract (confirmed from `stepGlideS2`, L6803)

## §-by-§
| § | item | result |
|---|---|---|
| §1 | complete-mat stage contract (order, arrays, model-agnostic vs explicit-specific, the placeHead layout diff) | DONE (below + `EXPLICIT_MATS2SOLVE_CONTRACT.md`) |
| §2 | explicit mat state (reused flat SoA, ~2.2 KB/motor; N=1/600/2100/4500/9000 = 2.2 KB/1.28/4.47/9.58/19.2 MiB for the beam SoA) | DONE |
| §3 | init (pre-bound explicit motor from buildS2Mat, boundSeg set, bindArc=½segLen) | DONE (in the gate setup) |
| §4 | build-time explicit dispatch | the explicit stages are distinct tasks (never calibrated); graph-level dispatch lands with §7 |
| §5 | explicit head-placement CPU/GPU gate (relaxed/high-axial/bend/taut/post-stroke) | **PASS** — geom+pose mir-vs-prod 0.0; GPUvsMirror 1.9e-7 |
| §6 | bondForces coupling gate (device head pose → bondForces vs production) | **PASS** — bondData Δ 2.6e-18 |
| §7–§11 | full-graph one-step + multi-step + stroke + residency + timing | NEXT (unblocked) |

## §1 complete-mat stage contract (confirmed from `stepGlideS2`, L6803)
Order: cull (`unionActive`) → [bind gate — SKIPPED for pre-bound] → chemistry (`cycleLymnTaylor`) →
`thetaS=thetaS4a(nuc)` → **geom2D + placeHead2D** → `bondForces` → CSR (`csrHistogram/Scan/Scatter/segGather`)
→ `chainForces` → z-confine → `brownianForce`+`integrate`+`orthogonalizeY`+`derive` (filament) →
**per-motor `s2SolveM`**. Model-agnostic stages (identical to calibrated): cull, chemistry, bondForces, CSR,
filament Langevin, reductions. Explicit-specific: the head placement (beam-derived, opposite outGeom layout)
and Step-10 (`matS2SolveStep`). Force returns to shared outputs via `bondData` (→ `segGather` → filament
forceSum) and `forceDotFil`/`forceMag` (→ chemistry catch-slip). Pre-bound ⇒ `boundSeg` fixed, bind gate
skipped, `noBind` set for the rest.

## Commands
```
./scripts/build.sh
java @tornado-argfile --enable-preview -Xmx8G -Dtornado.recover.bailout=false -Dtornado.enable.fma=false \
     -Dtornado.tvm.maxbytecodesize=65536 -cp "$TDIR/tornado-api-4.0.1-dev.jar:." softbox.ExplicitCompleteMatHarness
```
