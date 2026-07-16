# Device-resident motor-mat SoA schema + stage dependency graph (design)

Design-only. Implements the contract in `HOST_SEMANTICS_FROZEN.md`. Targets the TornadoVM PTX backend under
the constraints proven in the calibratedStep unblock: **no local multidimensional arrays** (`NewMultiArray`
does not lower), **no in-kernel dynamic allocation** (per-thread scratch = fixed-size caller buffers),
**no float↔int bit-reinterpret** (`Math.abs(float)`→`fabs`, `Math.max(double)`→ternary, `Math.log1p`→
compensated `log1pC`), and inline callee node-count ≤ ~600. All state is **planar SoA** (component `c` of
entity `m` at index `c*N + m`) so kernels index with `@Parallel int m`.

## Legend
`F`=FloatArray, `D`=DoubleArray, `I`=IntArray. `N`=#motors (`g4NMot(density)`), `nSeg`=#segments,
`M`=`g4M` beam segments (explicit; nodes `M+1`). Residency: **P**=persistent device-resident across steps
(FIRST_EXECUTION upload, never re-transferred); **S**=per-step scratch (device-only, never host-read);
**R**=reduction/diagnostic read to host on cadence only. Precision matches the host: calibrated pose/geometry
FLOAT (matches `calibratedStep`); explicit beam DOUBLE (the nested-FD tangent needs it); indices/flags INT.

---

## 1. Shared motor state (both models)

| array | type | size | res | meaning / host source |
|---|---|---|---|---|
| `phi` | F | N | P | converter lever angle φ — `G.phi[]` |
| `psi` | F | N | P | head roll angle ψ — `G.psi[]` |
| `thetaS` | F | N | P | converter rest-angle target θ_s (−30/+30) — `G.thetaS[]` |
| `psiActin` | F | N | P | stereospecific bound orientation — `G.psiActin[]` (0) |
| `anchor` | F | 3N | P | pivot/anchor `A[m]` (planar x,y,z) — `G.A[][]`; = movable pivot P for cal, = beam node M for expl |
| `site` | F | 2N | P | ideal head site (x,y) for the cull — `G.siteX/siteY[]` (fixed per motor) |
| `active` | I | N | S | cull flag (0/1) — `G.active[]` (recomputed each step) |
| `boundSeg` | I | N | P | binding state: `≥0` seg id, `−1` FREE_BINDABLE, `−2` FREE_COOLDOWN — `MotorStore.boundSeg` |
| `bindArc` | F | N | P | bind-arc position along seg — `MotorStore.bindArc` |
| `nucState` | I | N | P | nucleotide 0/1/2/3 — `MotorStore.nucleotideState` |
| `cooldown` | I | N | P | refractory counter — `MotorStore.cooldown` |
| `noBind` | I | N | P | control flag (never bind) — `G.noBind[]` |
| `C_` | F | 3N | P/S | derived converter point (geom2D) — `G.C_[][]` |
| `xH_` | F | 3N | P/S | derived head center (geom2D) — `G.xH_[][]` |
| `xF8_` | F | 3N | P/S | derived F8 point (geom2D) — `G.xF8_[][]` |
| `bondData` | F | 13N | P | cross-bridge stride-13 rows — `G.bondData` (EXISTS in the harness) |
| `forceDotFil` | F | N | P | along-fil load (bondData[·][12]); **cross-step** input to chemistry — `MotorStore.forceDotFil` |
| `forceMag` | F | N | P | |F8h| diagnostic — `MotorStore.forceMag` |
| `forceDotAvg`,`avgInit` | F,I | N | P | chemistry load-gate running average — `MotorStore` |
| `candDist`,`candOrient` | F | N | S | (diagnostic) gate `surf`/orientation score cached for A2 counters — `gate2D` outputs |
| `motCounts` | I | ~4 | P/EVERY | `mot.counts` = {N, step t, seed, nSeg} — re-uploaded EVERY_EXECUTION |
| `nucParams`,`kinParams`,`xbParams` | F | small | P | frozen chemistry/binding/cross-bridge params — `MotorStore`/`G.xbParams` |

Head sub-body pose (`placeHead2D` target) lives in the existing `MotorStore.body` SoA (`b.coord/uVec/yVec`,
planar over `3N` sub-bodies) — reuse it; `bondForces` already consumes it.

## 2. Calibrated-specific (movable pivot + 5×5 solve)

| array | type | size | res | meaning |
|---|---|---|---|---|
| `supP0` | F | 3N | P | rest pivot P0 — `G.supP0[][]` |
| (φ,ψ,anchor,C_,xH_,xF8_ shared above) | | | | the 5 DOF are {anchorΔ(3), φ, ψ} |
| `supParams` | F | ~15 | P | global sup-tail law scalars (`supKsoftAx…supSmoothBuck`, `supGammaP`, frame) — scalar, not per-motor |
| `mat5` (scratch) | — | — | S | **NO array** — the 5×5 augmented solve is the 30 NAMED SCALARS + hand-unrolled Gauss-Jordan from `TwoBodyGpuKernels.calibratedStep` (already lowerable). No per-thread buffer needed. |

Outputs: updates `phi,psi,anchor` in place; writes `forceDotFil,forceMag`. `matStep7` = the scalarized
`calibratedStep` body operating over the mat SoA, **with the mat salt family** (`0x5F1..0x5F5 + m·7919`;
see HOST_SEMANTICS §Salt reconciliation) and `supForceM` inlined (the softplus tail law: reuse `softpos`/
`softpos_d`/`fabs`/`log1pC`).

## 3. Explicit-specific (flat beam + (3M+2) solve)

| array | type | size | res | meaning |
|---|---|---|---|---|
| `g4Node` | D | 3(M+1)·N | P | beam node coords, **FLAT planar** (node j comp k of motor m at `(3j+k)*N+m`) — replaces `G.g4Node[m][M+1][3]` |
| `g4E` | D | 3N | P | clamped emergence point (node-0 re-pin target) — `G.g4E[][]` |
| `g4Tan` | D | 3N or scalar | P | clamped emergence tangent — `G.g4Tan` (lab-fixed = b̂ ⇒ may be a scalar global) |
| `g4Params` | D | ~6 | P | `g4M, g4l0, g4ks, g4kb, g4gammaNode, g4kfloor, g4floorZ` — global scalars |
| `beamMat` (scratch) | D | (3M+2)² + (3M+2) | **S, per-thread** | the augmented `Msys[n][n+1]` — **MUST be a caller-provided per-thread slice**, NOT `new double[n][n]` (NewMultiArray). Flatten to a 1-D `D` of size `N·((3M+2)·(3M+3))` indexed per motor, or tile. |
| `beamForceScratch` | D | 3(M+1) per thread | S | `s2NodeForcesM` output (replaces `new double[M+1][3]`) — per-thread flat slice |
| `beamIters`,`beamConv` | I,F | N | R | (diagnostic) Newton/solve status, residual — new |
| `beamContourErr` | F | N | R | (diagnostic) `contour − endToEnd` (taut test) — from `measureS2Mat` |

Outputs: updates `g4Node` (free nodes + re-pin node 0), `phi,psi`, `anchor=nodeM`; writes force diagnostics.
`s2SolveM` is the heaviest kernel (M=... at L40 ⇒ M≈4 ⇒ n=14; the nested central-FD tangent = many
`s2NodeForcesM` evals) — this is the one that hit the inline node-count cap for the single-motor probe; it
needs the flat per-thread scratch AND likely helper-splitting to fit the inline limit (a device-port risk to
re-probe first, exactly as the single-motor explicitBeamStep did NOT lower).

## 4. Filament + segment state (existing `FilamentStore` — reference, unchanged)

`coord(3·nSeg), uVec, yVec, zVec, end1, end2, segLength, forceSum(3·nSeg), torqueSum, randForce, randTorque,
bTransGam, bRotGam, brownTransScale, brownRotScale, params, chainParams, counts, end1/2NbrSlot/Side`. These
already lower (used by MotorBindingHarness on GPU). Stages 10–13 reuse them verbatim.

## 5. Cull grid (device-resident CSR over sites)

| array | type | size | res | meaning |
|---|---|---|---|---|
| `cellStart` | I | nc+1 | P | grid CSR offsets (`initMatGrid`) — sites are static ⇒ build ONCE |
| `cellMotor` | I | N | P | grid CSR contents — build once |
| `gridDims`/`gridParams` | I/F | small | P | `gnx,gny,gcell,gx0,gy0,queryR` |

(Static site layout ⇒ the grid is built host-side once at buildup and uploaded FIRST_EXECUTION — no per-step
grid rebuild. The union is recomputed each step against the moved filament, kernel-side.)

## 6. Online reduction outputs (host reads on cadence ONLY)

| array | type | size | res | meaning |
|---|---|---|---|---|
| `redScalars` | I/F | ~8 | R | per-step: nBound, nBindEvents, candCount, nLoadBearing, nNaN, plus A2 diagnostics |
| `comSample` | F | 3 | R | cadence-sampled filament COM (for the LS velocity estimator) — the ONLY pose read |

**Host transfers only `redScalars` + `comSample` per sampled step.** Full per-motor/filament SoA stays
device-resident (the invariant). Velocity = host LS slope of `comSample·b̂` vs t (measurement stays host).

---

## 7. Stage dependency graph (11 kernels; chained TaskGraphs, device-resident)

```
 (per step t)
 ┌───────────────────────────────────────────────────────────────────────────────────────┐
 │ [1] matCull ── active[]  ←  boundSeg, site, cellStart/Motor, fil.coord/uVec/segLength    │  no RNG
 │        │                                                                                  │
 │        ▼                                                                                  │
 │ [2] matGeom ── C_,xF8_,xH_  ←  anchor(A), phi, psi, frame       (geom2D; thetaS=PRESTROKE │  no RNG
 │        │           for the bind-eligible; geom2D ignores thetaS)                          │
 │        ▼                                                                                  │
 │ [3] matNearestGate ── nearestSeg + gate metrics  ←  xF8_,xH_, fil pose                    │  no RNG
 │        │            (bounded loop over nSeg; writes candSeg, candDist/orient)             │
 │        ▼                                                                                  │
 │ [4] matBind ── boundSeg,bindArc  ←  8-gate AND (deterministic), guard active∧bindable∧ADP │  no RNG
 │        │                                                                                  │
 │        ▼                                                                                  │
 │ [5] chemistry (cycleLymnTaylor) ── nucState,boundSeg,cooldown,stats ← forceDotFil, params │  wangHash 0x4E55/0x4D54/0x52465241
 │        │            (over ALL N; sets thetaS-driver via nucState)                         │
 │        ▼                                                                                  │
 │ [5b] matCock ── thetaS[] = thetaS4a(nucState)   (all m)                                   │  no RNG   (fuse into [5] or [6])
 │        ▼                                                                                  │
 │ [6] matPlaceHead ── MotorStore.body pose  ←  xF8_,xH_ (re-geom2D for active, then place)  │  no RNG
 │        ▼                                                                                  │
 │ [7] bondForces ── bondData ← body pose, fil pose, boundSeg, nucState                      │  no RNG   (EXISTS)
 │        ▼                                                                                  │
 │ [8] CSR gather (histogram→scan→scatter→segGather) ── fil.forceSum/torqueSum ← bondData    │  no RNG   (EXISTS; chunked-parallel scan on device)
 │        ▼                                                                                  │
 │ [9] filament Langevin ── fil pose ← forceSum (+chainForces if flex, +z-confine),          │  fil FDT (f.counts)
 │        │            brownianForce, integrate, orthogonalizeY, derive                      │           (EXISTS; +matZConfine)
 │        ▼                                                                                  │
 │ [10] matStep7  { calibrated: matSupSolve (5×5 scalar)  |  explicit: matS2Solve (beam) }   │  brownTorque 0x5F*+m | 0x4811/0x4841/0x4842+m
 │        │       ← bondData(F8h, pre-integrate), C_/xF8_ (pre-integrate), sup/beam params    │
 │        │       → phi,psi,anchor,(g4Node), forceDotFil,forceMag                            │
 │        ▼                                                                                  │
 │ [11] matReduce ── redScalars, comSample  ←  boundSeg,forceDotFil,fil.coord (cadence)      │  no RNG (R)
 └───────────────────────────────────────────────────────────────────────────────────────┘
```

Data-hazard notes (bit-for-decision): `bondData` and `C_/xF8_` produced at [6]/[7] must survive to [10]
(the filament integrate [9] must not clobber them — it doesn't; it writes `fil.*` only). `forceDotFil` from
[10] feeds [5] of the NEXT step (persistent). `thetaS` set at [5b] is used by [10] (via the rest-angle in the
solve) and by `bondForces`' rest angle (via `nucState`). The bind block's `thetaS=PRESTROKE` [2-pre] only
matters for gate metrics that read φ/ψ vs θ_s — reproduce by computing the gate's θ_s term with PRESTROKE for
the eligible set (host does `thetaS[m]=PRESTROKE` inside the bind loop then `gate2D`), i.e. `matNearestGate`
must use PRESTROKE_THETAS in its `thetaErr` gate, independent of the stored `thetaS[m]`.

## 8. Model dispatch — OUTSIDE the hot kernel

Calibrated vs explicit differ ONLY at stage [10] and in which state arrays exist (calibrated: pivot/5-DOF;
explicit: `g4Node`/beam). Stages [1]–[9] and [11] are model-agnostic. **Dispatch = two different stage-[10]
kernels selected at graph-BUILD time by the registry (`MotorModel`), NOT a per-motor `if(model)` inside a
kernel.** Two TaskGraphs (or one graph with the model-specific task chosen at build) share all shared buffers
via `persistOnDevice`/`consumeFromDevice`. This keeps the "no per-motor calibrated/explicit branch inside one
kernel" rule (a branch there would split PTX scheduling and risks the basin-tipping hazard). A mixed mat
(some calibrated, some explicit motors) is NOT a current requirement — do not build per-motor model dispatch
unless a mixed assay is scoped.

## 9. Transfer plan (the residency invariant)

- **FIRST_EXECUTION (once):** all P arrays (phi,psi,thetaS,psiActin,anchor,site,boundSeg,bindArc,nucState,
  cooldown,noBind,bondData,forceDotFil,supP0/supParams | g4Node/g4E/g4Params, cellStart/cellMotor/gridParams,
  fil.* , params).
- **EVERY_EXECUTION (tiny):** `motCounts`/`f.counts` = {N,t,seed,nSeg} (the per-step counter for RNG keys).
- **UNDER_DEMAND / cadence (tiny, host-read):** `redScalars`, `comSample`. Nothing else leaves the device.
- Chained graphs share buffers via `persistOnDevice(...)` on producers and `consumeFromDevice(...)` on
  consumers (the SoftBox residency pattern). Cadence-gate any graph that doesn't fire every step at a chain
  end (source/sink), never the middle (the `executeAlloc` NPE rule).

## 10. Device-port decisions / risks (carry from HOST_SEMANTICS + calibratedStep experience)

1. **Salt reconciliation** — `matStep7` uses MAT salts (`0x5F*`+m·7919 / `0x4811,0x4841,0x4842`+m·7919), not
   the single-motor `0x4F*`/`0x47*`. Wire the salt literals + the `m·7919` (and beam `m·1009`) offsets.
2. **`matS2Solve` (explicit) may not lower as-is** — the single-motor `explicitBeamStep` FAILED to lower
   (helper `s2NodeForcesK` node-count 973 > 600 inline cap, plus `double[][]` scratch). The mat version must
   (a) flatten all `double[][]` to per-thread flat `DoubleArray` slices, (b) split/inline `s2NodeForcesM`
   under the cap, (c) apply the same reinterpret-free substitutions. **Re-probe explicit lowering BEFORE
   committing to the explicit device path** — it may remain CPU-only (a legitimate outcome).
3. **`matNearestGate` data-dependent search** — bounded `for(s<nSeg)` argmin, no alloc, reproduce
   `|foot|≤half+0.02` reject + `<bd` first-min tie-break + the `foot·u` clamp.
4. **`matCull` race-free union** — per-motor gather (active iff bound OR min-seg siteSegDist2 ≤ queryR²) OR
   idempotent write-true scatter over the grid neighborhood. Preserve `cullMode=1` + `gcell=max(queryR,0.02)`.
5. **`matZConfine`** — the host `for(s) forceSum[2nSeg+s]-=G4_KZ·z` loop needs a kernel (or fold into
   integrate). Trivial.
6. **Deterministic binding** — `matBind` has NO RNG; do not add one.
7. **Serial CSR scan** — use the chunked-parallel CSR (histogram/scanLocal/scanChunks/scanAdd/scatter, as in
   MotorBindingHarness) if the single-thread scan dominates at scale.
8. **Cross-step `forceDotFil`** persistence — keep it device-resident; only Step-7 zeroes it (for inactive m).
9. **`beamMat`/`beamForceScratch` per-thread scratch** — size `N·(3M+2)(3M+3)` / `N·3(M+1)` flat DoubleArray;
   index by `m`. No `new double[][]` in the kernel.
10. **Precision** — calibrated FLOAT32 (matches `calibratedStep` gate); explicit DOUBLE. Reductions in the
    precision that matches the host accumulator; velocity LS on host in double.
