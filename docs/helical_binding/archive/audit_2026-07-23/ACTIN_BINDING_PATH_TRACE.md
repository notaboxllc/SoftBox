# ACTIN_BINDING_PATH_TRACE

**Read-only trace (2026-07-23).** Branch `gpu-mat-bottlenecks-explicit-singlehead`. Two distinct binding
lineages traced separately. The question at each stage: **where could a filament azimuth / helical-site
enter, and does it?**

---

## LINEAGE 1 — CANONICAL explicit-S2 / HMM-dimer "mat" path (this branch's production path)

Two byte-parallel implementations of the identical gate; both canonical.

### Call chain (CPU/host-device slice: `MatSoaSlice.matGeomGate`, `MatSoaSlice.java:314-391`)

Signature reads actin as **centerline + axis + length only** — `filYVec` is **not** a parameter
(`:314-316`):
```java
public static void matGeomGate(DoubleArray anchor, DoubleArray pose,
    FloatArray filCoord, FloatArray filUVec, FloatArray filSegLen,
    DoubleArray params, IntArray counts, ...)   // <- no filYVec
```

1. **`geom2D`** (`:331-346`) — head geometry `C`, `xF8`, `xH` from the **motor** anchor + pose angles φ,ψ +
   the **motor** base frame `bhat/econv/eup`. *No filament read.* → azimuth cannot enter.
2. **`nearestSeg2D` over `xF8`** (`:347-364`) — projects the head tip onto each segment axis:
   `foot = dx·su + dy·sv + dz·sw`, `(su,sv,sw)=filUVec` (`:352,354`); canonical mode clamps `footC` and takes
   the **perpendicular distance to the clamped closest point on the centerline** (`:360-361`). Consumes
   `filCoord`, `filUVec`, `filSegLen`. *Reduces actin to a 1-D foot + perpendicular scalar.* → **azimuth
   COULD enter here** (resolve the perpendicular offset against `filYVec`/`filZVec` and a helical `φ(bindArc)`)
   — **it does not**.
3. **`gate2D` on the best segment** (`:366-387`) — computes:
   - `bindArc = footC + half` → continuous arc-length on the centerline (`:376`)
   - `conDist` = tip→clamped-centerline distance (`:375`); `surf = (conDist − FIL_R)·1e3` (`:377`) — radial
     reach to a **cylinder of radius `FIL_R`**, i.e. actin as an **azimuth-symmetric rod**. → **azimuth COULD
     enter** (replace the isotropic `FIL_R` with an angle-dependent presented reach) — **it does not**.
   - `psiErr=|ψ−psiAct|`, `phiErr=|φ−PHI_PRE|`, `thetaErr=|(ψ−φ)−pth|` (`:378-381`) — all **motor pose angles
     vs motor reference constants**, none derived from the filament frame.
   - `preload=kF8·conDist` (`:382`), `eKt` (`:383`), `headSide` (proj on motor `eup`, `:384`).
   - **8-gate AND** (`:385-387`): g0 `surf<dBind`, g1 `psiErr<psiDeg`, g2 `phiErr<phiDeg`, g3
     `thetaErr<thetaDeg`, g4 `preload<preloadPn`, g5 `eKt<energyKt`, g6 `headSide<A2`, g7 `bindArc` margin.
4. **`matBind` attachment** (`:85-95`) — on `active ∧ !noBind ∧ boundSeg==-1 ∧ nuc==ADPPI ∧ candAccept==1`
   writes `boundSeg=candSeg`, `bindArc=candBindArc`. **Retained per-attachment state = `(boundSeg int,
   bindArc float)`** — a segment index + a continuous arc-length. *No azimuth retained.*

### Explicit-beam twin: `TwoBodyBeamAnalyticGpu.matBindGateExplicit` (`:430-475`)
Identical structure — `nearestSeg2D` over `filCoord/filUVec/filSegLength` (`:433-447`), `bindArcV=footC+half`
(`:457`), `surf/conDist` on the cylinder (`:456-458`), the same g0–g7 on motor angles (`:459-474`),
attachment `boundSeg.set(m,s); bindArc.set(m,bindArcV)` (`:474`).

### GPU device twin: `ExplicitHmmDimerGpuKernel.dimerBindGate` (`:398-406`)
Takes `filC, filU, filSeg, cumLen` — **no `filYVec`** (`:398`). `gateHead` consumes the packed motor head
geometry `D` (`O_HAXF8`, angles `O_PHIA/O_PSIA/O_PSIACTA/O_THSA`, `O_HAEUP`) + centerline/axis only
(`:403-404`). No azimuthal filament field is packed into `D`.

### Where azimuth COULD enter (currently does NOT)
1. Gate signatures would need `filYVec` added (omitted at `MatSoaSlice.java:315`,
   `ExplicitHmmDimerGpuKernel.java:398`).
2. `nearestSeg2D` (`:354,360-361`) would need the perpendicular offset direction resolved against
   `filYVec`/`filZVec` + a helical phase of `bindArc`.
3. `surf` gate g0 (`:377`) would replace the isotropic `FIL_R` with an angle-dependent reach.
4. `psiActin` gate g1 (`:378`) — a motor-side scalar (`TwoBodyConverterMotor.java:1285`) — is the natural
   coupling point *if* actin helical phase were ever to constrain the bound-head roll; today it is a per-motor
   config constant, invariant under the whole-dimer `rotateDimerZ`, not derived from `filYVec`.
5. GPU `D` packing (`ExplicitHmmDimerGpuValidation.java:107,152`) would need a new azimuthal filament field;
   none exists.

### bhat / econv / dimerBhat / dimerEconv — motor-side, NOT filament
`bhat`/`econv` are **motor frame** vectors (`TwoBodyConverterMotor.java:1263-1265`; comment "barbedDir used
ONLY for the binding pose + analysis"). The dimer mat's per-dimer azimuth is a **whole-motor** rotation about
vertical: `dimerBhat/dimerEconv` = "per-dimer base frame (random azimuth about vertical) for the mat bind
path" (`ExplicitHmmDimerGlidingHarness.java:113`), set via `rotateDimerZ(d, az)` then cloned from the motor
head frame (`:256-261`), fed to the gate as the motor base frame (`:344`). **These are which horizontal
direction the anchored motor points — not the actin filament's helical phase.** The filament frame is
untouched by this and, regardless, not read by the gate.

### Occupancy (steric, not azimuth, not canonical-gate)
Same-filament **bound-site occupancy exclusion** `STANDING_EXCLUSION_NM = 5.4 nm`
(`ExplicitHmmDimerGpuParams.java:101`) = an **axial-gap veto on a continuous material coordinate**
(`occupancyVeto = candFil==partnerFil ∧ |sCand−sPartner| < 5.4−tol`, `ExplicitHmmDimer3jsHarness`), applied
*after* geometry. CPU-only, default-OFF, confined to `ExplicitHmmDimer3jsHarness` — **not** in the canonical
`matGeomGate`/`dimerBindGate` production gate. No discrete site index; a running minimum-separation check.

### Filament frame is consumed only POST-binding
`filYVec` **is** allocated/initialized (`f.setYVec(k,0,1,0)`, `ExplicitHmmDimerGlidingHarness.java:140,220`),
maintained by `DerivedGeometrySystem`, and transferred to the GPU (`ExplicitHmmDimerGpuValidation.java:1713`,
resident) — but read only by `CrossBridgeSystem.bondForces` (the post-binding cross-bridge **force**, e.g.
`MatSoaSlice.java:849`, GPU `:1722`) and the integrator/derive. **The bind gate never sees it.**

**LINEAGE 1 verdict: the canonical explicit-S2 binding path uses filament azimuth NOWHERE.** Actin = polarity
axis + centerline + length; retained bond state = (segment, continuous arc-length).

---

## LINEAGE 2 — LUMPED / GLIDING path (experimental, default-off; NOT this branch's production path)

### Baseline: `bindNearest` (`BindingDetectionSystem.java:~360-395`)
Reach predicate `reachTestDistSq` (α-foot + `conDist<myoColTol` + `motDotFil≥alignTol` + `rodDotFil≥0`);
nearest reachable segment wins; stores `(boundSeg, bindArc)`. No frame read. **Note:** `reachTestDistSq`
(`:74-76`) computes the head's ⊥-offset `(dx,dy,dz)=(cp−head)` — *the head's azimuthal position around the
filament* — and **discards it**. This is exactly the quantity a real azimuthal gate / off-axis attach would
need.

### Inc-2 hard gate: `bindNearestAzim` (`:398-478`) — flag `kinParams[25]=1`
Reuses `reachTestDistSq` (`:441-442`); threads `segYVec` (`GlidingHarness.java:822/2418`). Per reachable
candidate:
- reconstruct segment axis `u` from `end2−end1` (`:445-449`), perp-foot arc `footArc` (`:450`), axial
  half-window `w=√(myoColTol²−d)`, `nj=w/monoSp` (`:451-453`);
- read cohered `segYVec` (`:454`), `segZ=u×segY` (`:455`);
- **site scan** `j=−4..4` (`AZ_NJMAX`): `arc=footArc+j·monoSp`, `φ=twistRate·(arc−½segLen)`, presented radial
  `n̂=cosφ·segY+sinφ·segZ` (`:459-463`);
- **accept** iff any in-window site has `headU·n̂ < −cos(Δ)` (antiparallel within Δ; accumulate, no early exit,
  `:464-465`);
- nearest qualifying segment wins; **stores `(boundSeg, bindArc)` on-axis** (`:468-476`).

### Inc-3 graded gate: `bindNearestFalloff` (`:480-555`) — flag `kinParams[26]=n`
Same scan/interp; per site `b=½(1−headU·n̂)∈[0,1]` (`:541`); **MAX-combine** over reachable sites (`:542`);
attach at best-registered arc; affinity `a=b_best^n` via `exp(n·log b)` (`:548`); **race-free wang-hash draw**
`u<a` (salt `0x415A4244` "AZBD", `:549-552`). Stores `(boundSeg, bestArc)` **on-axis**.

### Where azimuth enters (Lineage 2) — throttle only, no drive
- **Enters:** the *accept decision* (Inc-2 hard cutoff / Inc-3 graded affinity), via `segYVec` + the analytic
  `φ(s)`. This **throttles which heads engage**.
- **Does NOT enter:** the *attachment point*. Both methods store only `(boundSeg, bindArc)` — the same on-axis
  foot as `bindNearest`. **No radial offset, no ψ, no moment arm is retained** ⇒ the cross-bridge exerts **no
  axial torque** ⇒ **no twirl drive** (design "PART B crux" — off-axis attach — is unbuilt).

### Downstream (Lineage 2, post-binding)
`CrossBridgeSystem.bondForces` reads the on-axis attach (`ap = sc + aOff·su`) ⇒ segment lever `RS ∥ u` ⇒
`TS=RS×F` has **zero ‖u component**. The full 3-vector torque *is* gathered and the integrator *does* project
onto `u` (`bwx`) — the channel is open and unblocked — but it is fed zero by construction because the attach
is on-axis.

**LINEAGE 2 verdict: a continuous azimuthal *gate* is wired in (default-off), reading the filament frame to
throttle engagement; it neither creates a discrete site nor drives any filament rotation.**

---

## Side-by-side summary

| Stage | Lineage 1 (canonical explicit-S2) | Lineage 2 (lumped gliding, default-off) |
|---|---|---|
| Reach/candidate | `nearestSeg2D` (centerline foot + ⊥ dist) | `reachTestDistSq` (α-foot; ⊥-offset computed then discarded) |
| Filament frame read in gate? | **No** (`filYVec` not in signature) | **Yes** (`segYVec` threaded) — Inc-2/Inc-3 only |
| Azimuth used to accept? | No | **Yes** (hard cutoff / graded affinity) |
| Azimuth used to place bond? | No | **No** (on-axis `bindArc` only) |
| Discrete site? | No (continuous arc + half-open ownership) | No (continuous φ(s) sampled at monomer spacing) |
| Retained bond state | (boundSeg, bindArc) | (boundSeg, bindArc) |
| Steric occupancy | axial-gap 5.4 nm veto (CPU-only, default-off, non-gate) | none |
| Twirl drive (off-axis attach) | not built | not built |
