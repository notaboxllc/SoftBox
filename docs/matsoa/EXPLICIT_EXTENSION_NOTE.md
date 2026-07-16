# Explicit-model extension of the calibrated mat-SoA slice (Part 9 — design only)

The vertical slice (`MatSoaSlice`) is calibrated-only. This note maps it to the explicit S2 beam so the
architecture stays extensible (no explicit code integrated this increment). Reference: `HOST_SEMANTICS_
FROZEN.md`, `SOA_SCHEMA.md`, and the single-motor probe finding that `explicitBeamStep` did NOT lower.

## What is shared (already built, model-agnostic)
Stages 1–6, 8, 11 are identical for both models: `matCull`, `matGeomGate` (geom2D+nearestSeg2D+gate2D),
`matBind` (all built + gated here), chemistry (`cycleLymnTaylor`, existing kernel), `bondForces`, CSR
gather, filament Langevin, reductions. **Only stage 10 (Step-7) and the per-motor pivot/beam state differ.**
So the explicit path reuses Stages 1–6/8/11 verbatim and swaps a different stage-10 kernel + state arrays.

## Calibrated → explicit state mapping (SoA)
| calibrated (built) | explicit equivalent | layout |
|---|---|---|
| `anchor` (pivot P = 3N) | `g4Node` FLAT `3(M+1)·N` DoubleArray (node j comp k of m at `(3j+k)*N+m`); pivot = node M | flat planar, NO `double[][]` |
| `phi,psi` (F) | `phi,psi` (D) — same DOF | planar N |
| `supP0`, `supParams` | `g4E` (3N, node-0 re-pin), `g4Tan` (scalar/3N), `g4Params{M,l0,ks,kb,gammaNode,kfloor,floorZ}` | planar / global |
| 5×5 solve = 30 named scalars, NO scratch | `(3M+2)²+(3M+2)` augmented system + `3(M+1)` node-force scratch | **per-thread FLAT DoubleArray slice** `beamMat[N·(3M+2)(3M+3)]`, `beamForce[N·3(M+1)]` — NOT `new double[][]` |
| — | `beamIters,beamConv,beamContourErr` | R diagnostics, planar N |

Precision: explicit beam is DOUBLE (the nested central-FD tangent needs it); calibrated pose is FLOAT for
Step-7 throughput. Discrete stages 1–6 are DOUBLE in both (bit-for-decision).

## Where `beamRelaxAnalytic`/`s2SolveM` replaces calibrated Step-7 (stage 10)
`matS2Solve` = the mat port of `s2SolveM` (`TwoBodyConverterMotor` L6736): free nodes 1..M, node M = pivot,
node 0 re-pinned to `g4E`; RHS = `s2NodeForcesM` (analytic stretch + central-diff bending + floor) + node
drag + F8/converter/bind Gauss–Newton on {pivot,φ,ψ}; `solveLin(3M+2)`. **MAT salts** (non-negotiable, per
HOST_SEMANTICS §Salt reconciliation): node `0x4811L + (m·1009 + j·131 + k)·7919L`, φ `0x4841L+m·7919`,
ψ `0x4842L+m·7919` — NOT the single-motor `0x4711/0x4741/0x4742`.

## The blocker to resolve FIRST (re-probe before building)
The single-motor `explicitBeamStep` FAILED to lower on PTX: `TornadoInliningException — s2NodeForcesK node
count 973 > 600 inline cap`, plus `double[][]` scratch (`NewMultiArray`). The mat `matS2Solve` must:
1. Flatten ALL `double[][]` (nodes, `Msys`, node-force output) to per-thread FLAT `DoubleArray` slices.
2. Split/inline `s2NodeForcesM` under the 600-node inline cap (the heaviest sub-routine; the nested
   central-FD calls it ~`(3M+2)·2 + 1` times per motor — a large fused graph).
3. Apply the reinterpret-free substitutions already used calibrated-side (`fabs`, `Math.max`→ternary,
   `log1p`→`log1pC`, `toDegrees` inlined, `Math.pow(,2)`→`x*x` — none flipped a gate).
**Outcome is uncertain — matS2Solve may not lower even flattened** (the inline cap is structural). Re-probe
`matS2Solve` lowering in isolation (bailout=false) BEFORE committing to the explicit device path; if it
won't fit, the explicit Step-7 legitimately stays CPU (calibrated GPU + explicit CPU is a valid split).

## Memory footprint (per motor, M=4 ⇒ n=14 for L40)
- Calibrated: ~ (phi,psi,thetaS,psiActin,anchor3,C3,xF8,xH,bondData13,forceDotFil,forceMag,supP0 3) ≈ 34
  scalars ≈ 200 B/motor (mostly float). 5×5 solve is register-resident (no scratch).
- Explicit: adds `g4Node 3(M+1)=15` D + `g4E 3` D + `beamMat n(n+1)=210` D + `beamForce 3(M+1)=15` D
  ≈ 243 doubles ≈ **1.95 KB/motor** — the augmented `beamMat` scratch dominates. At 4000 motors ≈ 7.8 MB
  beam scratch (device-resident, fine on 11.5 GB). The scratch is the reason explicit is ~40× the
  calibrated per-step cost (the 5.2× per-motor beam solve + the FD tangent).

## Batch layout
Same planar SoA (`c*N+m`); the beam scratch is `beamMat[m*(n*(n+1)) + row*(n+1) + col]` (per-motor tile).
Model dispatch stays OUTSIDE the hot kernel: build two TaskGraphs (calibrated stage-10 vs explicit
stage-10) sharing stages 1–6/8/11 buffers; select by `MotorModel` at graph build. No per-motor `if(model)`.

## Shortest path to a device-resident single-head explicit TRAP assay (not the mat)
The optical-trap explicit assay (`-exp4g`, single motor) is simpler than the mat: no cull/nearest/bind
(the head is trap-held, not gliding). The shortest device-resident explicit slice is:
1. Reuse the flat `g4Node`/`beamMat` SoA at N=1 (or a small trap batch).
2. Port `s2SolveM` → `matS2Solve` (the lowering re-probe above is the gate) + the trap force
   (`LaserTrapSystem` analog) instead of the gliding cull/bind chain.
3. Device-resident across steps; host reads only the trap-force/extension reduction.
This isolates the ONE explicit risk (beam-solve lowering) without the gliding-mat complexity, and is the
recommended first explicit device milestone before attempting the explicit gliding mat.
