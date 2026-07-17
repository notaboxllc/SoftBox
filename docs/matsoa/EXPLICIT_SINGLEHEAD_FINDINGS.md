# Explicit single-head device-resident vertical slice — findings (§1–§10)

**Goal (met):** the smallest complete explicit device-resident vertical slice — persistent explicit beam
SoA + explicit Step-7 dispatch + one validated dynamic single-head trajectory that stays on the GPU across
timesteps, unloaded and loaded, CPU-FD/CPU-analytic/GPU-analytic in agreement. **All gates PASS.** No beam
energy / topology / params / F8-converter mechanics / convergence criteria changed; the validated analytic
GPU beam kernel is reused (NO second solver); CPU FD stays the permanent physical oracle;
`MotorGpuParams.DEVICE_VALIDATED` stays false; no silent fallback (`-Dtornado.recover.bailout=false
-Dtornado.enable.fma=false`).

Harness `softbox/ExplicitSingleHeadHarness.java` (+ device kernel `TwoBodyBeamAnalyticGpu.beamObserve`),
`scripts/run_explicit_singlehead.sh`. Report `RUN_LOGS/explicit_singlehead/EXPLICIT_SINGLEHEAD.md`.

## Final status block
- **EXPLICIT PERSISTENT SOA:** DONE — flat planar device arrays (nodes 15, frame 15, q 4, F8h 3, params 17,
  sys 210 scratch, outGeom 9, obs 5; 2232 B/motor, sys dominates). No `double[][]`, no per-step alloc/reconstruction.
- **EXPLICIT STEP-7 DISPATCH:** DONE — build-time model dispatch; the explicit graph's only compute tasks are
  `beamRelaxAnalytic` (maxIt=1 = one production `s2Solve` step) + `beamObserve`; never invokes calibrated
  mechanics; an unsupported (calibrated) request fails clearly, no silent swap.
- **SINGLE-HEAD DEVICE TRAJECTORY:** PASS — 400-step device-resident trajectory (relax→stroke θ_s −30→+30→hold→
  detach→recoil); realistic **−7.70 nm working stroke, 7.92 pN peak force**; GPU vs CPU-analytic continuous
  observables agree to **2.3e-6** (head disp/force), totalE bit-identical (1e-22 J); **solver-status mismatches=0**;
  the 2 bendDom-flag flips are classification-boundary noise at stroke onset (both energies ~0, head disp agrees
  to 1e-6 nm — physically identical); shares the CPU-FD basin.
- **LOADED TRAJECTORY:** PASS — one modest 4 pN resisting load (validated trap range); plateau force 7.96 pN,
  disp-under-load −7.93 nm, effective stiffness ≈1.00 pN/nm, **0 solver failures**, status identical to CPU.
- **DEVICE RESIDENCY:** CONFIRMED — beam state (`nodes`, `sys`) uploaded FIRST_EXECUTION and NEVER downloaded
  per step; per step only small control up (q+F8h+params+counts) and reduced observables down (q+outGeom+obs+
  status+iters). Measured **320 B in / 232 B out per step**; no host per-motor loop in the hot path; no fallback.
- **NEXT STEP:** explicit GLIDING on the mat — the `matS2Solve` coupled Stage-10 (beam + cross-bridge/CSR
  gather over the SoA) wired into `MatSoaSlice` (re-probe its lowering in isolation first); the single-head
  slice proves the persistent beam mechanics + dispatch + residency the gliding path builds on.

## §-by-§ results
| § | gate | result |
|---|---|---|
| §2 | persistent SoA + memory (N=1/600/2100/4500 = 2.2 KB / 1.28 / 4.47 / 9.58 MiB) | DONE |
| §3 | CPU→device init, 6 ICs (prestroke_adppi, poststroke_adp, high_axial, bend, taut, mixed) | **PASS** (device==CPU EXACT: nodes/q/frame/F8h Δ=0) |
| §4 | build-time explicit dispatch (never calibrated; unsupported fails clearly) | **PASS** |
| §5 | persistent-plan lowering probe (relax+observe, N=1) | **PASS** (LOWERS; GPU vs CPU-mirror Δnode=1.1e-11 µm) |
| §6 | persistent-state relaxation gate (CPU-FD / CPU-analytic / GPU) | **PASS** (GPU-vs-FD 9.1e-9 µm; 0 class changes; 0 new failures) |
| §7/§8 | unloaded dynamic trajectory + CPU/GPU compare | **PASS** (−7.70 nm stroke, 7.92 pN; status exact; continuous 2.3e-6) |
| §9 | loaded trajectory (4 pN resist) | **PASS** (effective k≈1.00 pN/nm; 0 failures) |
| §10 | residency + throughput | **PASS** (nodes resident; 320/232 B/step; N=1 launch-bound — functional residency, not speedup) |

## Design notes (faithfulness)
- **One Newton step per timestep.** Production `stepS2` calls `s2Solve` ONCE per timestep (drag-limited
  implicit dynamics). The device trajectory calls `beamRelaxAnalytic` with **maxIt=1** ⇒ exactly one
  assembly+solve+increment = one `s2Solve` step; the beam relaxes over many steps (the "several post-stroke
  relaxation steps" / recoil emerge dynamically). The relaxation gate (§6) instead runs the kernel to full
  convergence (maxIt=800) to A/B against the CPU relax loop.
- **Imposed filament geometry.** The cross-bridge is a spring to a FIXED actin site (rest = the settled
  pre-stroke head): `F8h = couple·kF8·(xActin − xF8)`, recomputed host-side each step from the reduced
  head pose — the SAME F8h the production `bondForces` supplies to `s2Solve`, simplified to a fixed anchor.
  The converter swing (θ_s) stretches the spring ⇒ genuine force generation; detach ramps `couple→0` ⇒ recoil.
- **Reduced observables on device.** `beamObserve` (a diagnostic reduction, NOT a solver) computes
  stretch/bend/floor energy + contour from the RESIDENT nodes — an EXACT flat replica of
  `ExplicitBeamAnalytic.{stretch,bend,floor}Energy` using the device `dacos` — so the beam state never
  leaves the device to produce energy/contour observables.
- **Classification.** load-bearing = stretch-dominated with |F8h|≥0.5 pN; bending-dominated = bendE>stretchE.
  These are diagnostic; in the unstrained transient (both energies ~0) the split is FP-noise and the label is
  undefined — such flips are benign iff the continuous head trajectory agrees (it does, to 1e-6 nm).

## Stop-condition audit (none tripped)
explicit GPU invokes calibrated — NO (dispatch gate: only relax+observe; unsupported fails). Persistent layout
changes beam physics — NO (energy/topology/params unchanged; §6 shares the FD basin). Initialized states differ
semantically — NO (§3 EXACT). Kernel ≠ validated CPU analytic — NO (§5 Δnode=1.1e-11; §6 9.1e-9). Solver failure
rate up — NO (0 failures loaded+unloaded). Load-bearing/bending class changed — NO (§6 classChg=0; §8 flips are
undefined-regime noise, not class changes). Full beam state crosses per step — NO (nodes resident; 320/232 B).
Host per-motor loop in the hot path — NO. GPU silent fallback — NO (bailout=false). Dynamic transitions disagree
without explanation — NO (status exact; the 2 flag flips are documented classification-boundary noise).

## Commands
```
./scripts/run_explicit_singlehead.sh              # all: -mem -dispatch -init -probe -relaxgate -traj -load
./scripts/run_explicit_singlehead.sh -probe       # just the lowering probe   (-traj / -load / -relaxgate individually)
#   flags baked into the script: -Dtornado.recover.bailout=false -Dtornado.enable.fma=false
```
