# Mat-SoA vertical slice — status (calibrated, experimental)

`MotorGpuParams.DEVICE_VALIDATED` stays **false** (experimental slice). All GPU runs use
`-Dtornado.recover.bailout=false` (a lowering failure throws — no silent CPU fallback). Build:
`nice -n19 ./scripts/build.sh`. Run: `java @tornado-argfile --enable-preview -Xmx4G
-Dtornado.recover.bailout=false -Dtornado.tvm.maxbytecodesize=16384 -cp "$TDIR/tornado-api.jar:."
softbox.MatSoaSlice`. Report: `RUN_LOGS/matsoa/STAGE_GATES.md`.

## STATUS
- **MAT-SOA VERTICAL SLICE:** 7 kernels IMPLEMENTED + isolated CPU-vs-GPU gates PASS (RTX 5070,
  bailout=false): Stages 1/2/3/7 (`matCull`/`matGeomGate`/`matBind`/`matStep7`) + 3 bridges
  (`matPlaceHead`/`matZConfine`/`matReduce`, all maxΔ=0).
- **COMPOSITION-RISK PROBE (the front-loaded unknown): RESOLVED — the full 19-task double mat loop LOWERS
  + RUNS as a SINGLE TaskGraph** (1259 ms cold, N=600, no `Graph-resize`). Chaining NOT needed — 19 double
  tasks fit under TornadoVM's single-graph capacity (the ~100-task full-system graph is what hit resize).
  Order: matCull→matGeomGate→matBind→cycleLymnTaylor→matPlaceHead→bondForces→csrHist/Scan/Scatter/
  segGather→chainForces→matZConfine→brownianForce→integrate→orthogonalizeY→derive→matStep7→matReduce.
- **DEVICE RESIDENCY (through the full composed loop): PROVEN** — all motor+filament SoA uploaded
  FIRST_EXECUTION; only `mc`/`mot.counts`/`f.counts` (small) EVERY_EXECUTION; only `redOut` (6 doubles)
  read back. **No full per-motor mat transfer per step.**
- **CALIBRATED GPU TRAJECTORY:** NOT YET — the composition graph LOWERS/RUNS, but a *correct* multi-step
  trajectory + the stepwise CPU-vs-GPU comparison (Part 6) is the next increment.
- **NEXT BLOCKER (Part-6 correctness, not a lowering risk):** two wiring fixes the lowering probe
  deliberately deferred — (1) UNIFY φ/ψ into one buffer both `matGeomGate` and `matStep7` read/write (the
  probe used separate `pose3`/`pose4`); (2) route `matStep7`'s force diagnostic to `mot.forceDotFil`
  (float N) so the NEXT step's `cycleLymnTaylor` reads it (the probe used a separate `forceOut`). Then
  loop `plan.execute()` per step (device-resident, updating only the counters) + the stepwise CPU-vs-GPU
  comparison with first-divergence classification. Parts 7 (end-to-end) + 8 (throughput) follow.

## Stages built + gated (Part 5)
| stage | kernel | precision | gate (isolated CPU-vs-GPU, bailout=false) | result |
|---|---|---|---|---|
| 1 cull | `matCull` | double | active-set IDENTITY vs `unionActive` (200/700 density ×3 seeds, N≤2100) | **PASS** 0 mismatches |
| 2 geom+nearest+gate | `matGeomGate` | double | nearest-seg selection + 8-gate accept IDENTITY vs `geom2D+nearestSeg2D+gate2D` | **PASS** segMism=0, acceptMism=0; geomΔ~3e-9 (double last-bit) |
| 3 bind | `matBind` | int (deterministic) | bind-event IDENTITY (chained geom→bind); synthetic positive path 96 accepts | **PASS** boundSegMism=0 |
| 7 Step-7 | `matStep7` | **double**, mat salts `0x5F1..0x5F5+m·7919` | 5-DOF solve vs `supSolveM` (pose/geom/force) | **PASS** — CPU≡host 6.9e-18 (arithmetic FAITHFUL), GPU-vs-CPU poseΔ=1.68e-7 = FMA amplified by the ill-conditioned solve (physics-intrinsic; force Δ~1e-20 ⇒ no semantic error) |

### Stage-7 finding (the coordinator's DOUBLE decision, validated)
`matStep7` is a bit-faithful double port of `supSolveM` — the CPU runner (same method, plain loop)
reproduces host `supSolveM` to **~1e-17** (the compensated `log1pC` + hand-unrolled Gauss-Jordan are exact;
the `Math.max`→ternary/`log1p`→`log1pC` substitutions are negligible). The GPU shows a **1.68e-7 pose
divergence that is ENTIRELY GPU FMA op-order** (GPU-vs-CPU = 1.68e-7, and it equals GPU-vs-host), amplified
by the ill-conditioned angular 5-DOF solve — the same sensitivity the host solve has. F8h is bit-identical
(force Δ~1e-20). This is the expected "float-not-semantic" decorrelation the Part-6 trajectory classifies,
and confirms the double slice is uniform and clean (no salt/formula drift).

Reinterpret-free substitutions used (none flipped a gate decision): `Math.abs`→`fabs`/`dabs`,
`Math.max(double)`→ternary, `Math.toDegrees` inlined `x*180/PI`, `Math.pow(x,2)`→`x*x`. All new kernels
lower to PTX (bailout=false, no fallback).

## Residency / transfer instrumentation
Per the built stages: `boundSeg, site, anchor, pose, active, noBind, nuc, fil.coord/uVec/segLength,
params` upload **FIRST_EXECUTION** (once). Per step the ONLY host↔device traffic is `counts` (16 B up),
the small mutable `boundSeg`/`bindArc` (bind test), and the reduced outputs read back (`active` 4N,
`geomOut` 72N, `candInt` 8N — these are *gate diagnostics*; in the composed loop only reductions cross).
**The slice does NOT require full motor state to cross the bus each step** (the STOP condition is not hit).

## What remains for the full calibrated slice
- **Stage 4 chemistry:** reuse `NucleotideCycleSystem.cycleLymnTaylor` (existing device kernel over
  `MotorStore` SoA) — an adapter, mat salts `0x4E55/0x4D54/0x52465241` already correct in that kernel.
- **Stage 5–6 F8/CSR:** reuse `CrossBridgeSystem.bondForces` + `csr*`/`segGather` (existing kernels) +
  `matPlaceHead` (write head pose into `MotorStore.body`, small new kernel).
- **Stage 7 Step-7:** `calibratedStep` (validated, lowers) with the mat salt swap (see NEXT BLOCKER).
- **Stage 8 Langevin:** reuse `brownianForce`/`integrate`/`orthogonalizeY`/`derive` + a tiny `matZConfine`.
- **Stage 9 reductions:** `matReduce` (nBound, bind events, COM sample) — small new kernel.
- **Part 6–8:** compose the device trajectory, CPU-vs-GPU stepwise divergence classification, multi-density
  validation, throughput. Not reached this increment.
- **Part 9 explicit:** `EXPLICIT_EXTENSION_NOTE.md` (design; re-probe `matS2Solve` lowering first).

## Files
- `softbox/MatSoaSlice.java` (new) — kernels `matCull`/`matGeomGate`/`matBind` + isolated gates + harness.
- `docs/matsoa/EXPLICIT_EXTENSION_NOTE.md`, `docs/matsoa/SLICE_STATUS.md` (this).
- No existing file modified; no physics/param/RNG-salt/chemistry/binding change; `DEVICE_VALIDATED` false.
