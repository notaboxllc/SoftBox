# Actin Helical Binding and Twirling Audit

**Authoritative consolidated report (audit 2026-07-23).** Branch `gpu-mat-bottlenecks-explicit-singlehead`.
Read-only audit — **no code modified, no runs executed, no model parameter changed.**

> **This document supersedes ACTIN_AZIMUTH_AND_HELICAL_SITE_AUDIT.md, ACTIN_BINDING_PATH_TRACE.md,
> TWIRLING_DOF_AND_OBSERVABLE_AUDIT.md, ACTIN_AZIMUTH_GIT_HISTORY.md, and EXISTING_HELICAL_WORK_VERDICT.md. The
> superseded source reports are retained under archive/audit_2026-07-23 for provenance.**

Structured file/line/lineage/state references live in the maintained companion
`ACTIN_HELICAL_BINDING_CODE_MAP.csv` (not repeated exhaustively in prose here).

---

## 1. Executive verdict

**Primary category C**, qualified by **B** and **D**:

> **C — a continuous azimuthal gate exists, but no discrete helical-site model exists.**
> Qualified by **B** (azimuth *state* — the roll DOF / rod frame — exists and is preserved on both runners, but
> the **canonical** explicit-S2 binding path does not read it) and **D** (the continuous gate + roll spring live
> in the **lumped/gliding** lineage; reaching the canonical explicit-S2 path requires a **port/reconciliation**,
> not a revival).

Stated clearly:
- **A continuous azimuthal gate exists** (Inc-2 hard cutoff + Inc-3 graded falloff), flag-gated and default-off.
- **No discrete helical-site model exists** — no monomer index, no 13/6 lattice, no per-site occupancy, no
  retained site ID, no stored per-filament φ₀.
- **The filament roll DOF exists and is dynamically integrated** — a real material-frame twist coordinate,
  integrated via `torqueSum·u → bwx`, preserved under frame re-orthogonalization, on both runners.
- **The canonical explicit-S2 binding path does not read filament azimuth** — actin enters the gate as
  centerline + polarity axis + segment length only; `filYVec` is absent from the gate signature.
- **The existing azimuthal gate lives only in the lumped/gliding lineage** — not in the canonical explicit-S2 /
  HMM-dimer production path.
- **The twirl drive is absent because attachments remain on-axis** — bonds attach on the centerline in *both*
  lineages, so the cross-bridge lever is ∥ the filament axis and exerts zero axial torque.
- **All existing azimuthal/roll infrastructure is default-off and noncanonical** — byte-identical to the
  canonical path when off; introduced in one foundational commit (`9c0f1d9`, 2026-07-10) present identically on
  the current branch and `main`.

**Why not the other categories:** *Not A* (complete/reusable) — the twirl drive is unbuilt, no discrete sites
exist, the gate is not in the canonical path. *Not pure B* — more than inert state exists (a full continuous
gate + a torsional-roll coherence spring are built and flag-runnable). *Not E* (nothing reliable) — a validated
(bit/event-identical CPU↔GPU), default-off, byte-identical-canonical gate + roll spring genuinely exist.

---

## 2. What exists today

| Design piece / feature | Status | Location |
|---|---|---|
| **Filament body-frame roll DOF** (free, integrated, frame-preserved) | **Present (always)** | `RigidRodBody`/`FilamentStore` frame + integrator + `DerivedGeometrySystem` |
| **Roll spring** — inter-segment torsional-roll coherence (Piece 3) | **Built**, flag-gated default-off | `RollSpringSystem.rollForces` |
| **Roll thermostat** — quiets the ‖u Brownian kick (Piece 4, observability) | **Built**, flag-gated default-off | `RollSpringSystem.dampRoll` |
| **Continuous HARD azimuthal gate** (Inc-2 throttle) | **Built (continuous)**, flag-gated default-off, **lumped lineage only** | `BindingDetectionSystem.bindNearestAzim` (`kinParams[25]`) |
| **Continuous GRADED azimuthal falloff** (Inc-3 throttle) | **Built (continuous)**, flag-gated default-off, lumped lineage only | `BindingDetectionSystem.bindNearestFalloff` (`kinParams[26]`) |
| **Continuous axial-gap occupancy veto** (5.4 nm steric co-occupancy) | **Built**, CPU-only, default-off, **non-canonical-gate** | `ExplicitHmmDimer*` `occupancyVeto` / `STANDING_EXCLUSION_NM` |
| **Off-axis bond placement** (the twirl DRIVE, Piece 2) | **NOT built** — bonds attach on-axis in both lineages | — |
| **Discrete helical-site model** (monomer index / 13/6 / occupancy grid / retained site ID) | **NOT built** | — |
| **Retained per-attachment site identity** | **NOT built** — bond state is `(boundSeg, bindArc)` = (segment, continuous arc-length) | — |
| **Filament cumulative-turns + angular-velocity (ω) observables** | **NOT built** — no net-winding accumulator, no filament-ω meter | — |
| **Canonical explicit-S2 gate reads azimuth?** | **No** | `matGeomGate` / `matBindGateExplicit` / `dimerBindGate` |

---

## 3. Canonical versus lumped binding lineages

| | **Canonical explicit-S2 / HMM-dimer path** (this branch's production path) | **Lumped / gliding path** (experimental, default-off) |
|---|---|---|
| Gate entry point | `MatSoaSlice.matGeomGate` · `TwoBodyBeamAnalyticGpu.matBindGateExplicit` · `ExplicitHmmDimerGpuKernel.dimerBindGate` | `BindingDetectionSystem.bindNearest` / `bindNearestAzim` (Inc-2) / `bindNearestFalloff` (Inc-3) |
| Filament fields read in gate | `filCoord` / `filUVec` / `filSegLen` (**no `filYVec`**) | `segYVec` threaded (Inc-2/Inc-3 only) + centerline/axis |
| Azimuth used to accept? | **No** | **Yes** (hard cutoff / graded affinity) |
| Azimuth used to place bond? | No | No (on-axis `bindArc` only) |
| Retained bond state | `(boundSeg, bindArc = footC+half)` — continuous half-open ownership arc | `(boundSeg, bindArc)` — continuous arc |
| Occupancy handling | axial-gap 5.4 nm veto (`occupancyVeto`), CPU-only, default-off, **not in the production gate** | none |
| Off-axis attachment | **Not built** (on-axis) | **Not built** (on-axis) |
| Production status | **canonical device-resident production path** | experimental gliding assay, default-off |

---

## 4. Filament roll degree of freedom

Each rigid rod (`RigidRodBody`, aliased by `FilamentStore`) carries a **full orthonormal body frame**: `uVec`
(long axis), `yVec` (reference ⊥), derived `zVec = uVec×yVec`.

- **Material frame + roll definition.** **Roll (twirl) = the azimuth of `yVec` about `uVec`** — a real, tracked
  coordinate in the rod's own material frame (not a lab-frame absolute; there is no stored φ₀ scalar — the
  intra-segment phase `φ(s)=twistRate·(arc−½segLen)` is computed on the fly). Internally radians; handedness is
  **LEFT-handed**, negative about pointed→barbed, derived fresh (v1's render-screw sign is non-authoritative);
  twist magnitude 166.5°/monomer (actin 13/6; `twistRatePerUm ≈ −1076 rad/µm`).
- **Integration.** The roll rate is `bwx = (torqueSum·u)/bRotGam_x`, applied to `yVec` each step
  (`RigidRodLangevinIntegrationSystem`, roll channel). The rod carries a material frame, so twirl is
  **dynamical**, not merely geometric.
- **Preservation under re-orthogonalization.** `DerivedGeometrySystem.derive` re-orthogonalizes
  `yVec' = zVec×uVec` each step — cleaning Euler drift **while preserving the roll azimuth** (it does not snap
  roll to a canonical value). The frame rides the rod under bending and rotates rigidly with the body.
- **Brownian excitation.** The roll DOF receives the Brownian torque kick (x-plane). Because `bRotGam_x ∝ R²` is
  tiny, roll is the **hottest, lowest-drag rotational mode** (also the most prone to instability — hence the
  thermostat).
- **Inter-segment roll coherence.** Frames are **per-segment**, coupled into one coherent filament helix by the
  inter-segment roll spring (`RollSpringSystem`, joint rest = `twistRate·segLen`); the sub-segment 166.5°/mon
  helix aliases at frame scale and is recovered by the analytic `φ(s)` interpolation.
- **GPU parity.** `yVec` is a planar-SoA `FloatArray`, transferred and kept device-resident in both lineages;
  frame/spring are bit/last-bit identical CPU↔GPU.

⇒ **Twirling is dynamically representable in principle** — the material-frame twist coordinate exists, is free,
thermally excited, integrated, and frame-preserved on both runners. (A centerline-only filament could measure
twirl neither dynamically nor geometrically; that is *not* this codebase.)

---

## 5. Binding path trace (call-chain summary)

*(Exhaustive file/line references are in `ACTIN_HELICAL_BINDING_CODE_MAP.csv`.)*

### Canonical explicit-S2 path (two byte-parallel twins + the GPU device kernel)
Gate = `MatSoaSlice.matGeomGate` (host/CPU-device slice) · `TwoBodyBeamAnalyticGpu.matBindGateExplicit`
(explicit-beam twin) · `ExplicitHmmDimerGpuKernel.dimerBindGate` (GPU device twin). Signatures read actin as
**centerline + axis + length only** — `filYVec` is not a parameter. Call chain:
1. **Candidate geometry** (`geom2D`) — head `C`, `xF8`, `xH` from the **motor** anchor + pose angles φ,ψ + the
   motor base frame `bhat/econv/eup`. *No filament read.*
2. **Nearest-segment projection** (`nearestSeg2D` over `xF8`) — projects the head tip onto each segment axis
   (`foot = dx·su + dy·sv + dz·sw`, `(su,sv,sw)=filUVec`); canonical clamps `footC` and takes the perpendicular
   distance to the clamped closest point on the centerline. **← azimuth COULD enter** (resolve the ⊥ offset
   against `filYVec`/`filZVec` + a helical `φ(bindArc)`) — **it does not.**
3. **Continuous `bindArc`** = `footC + half` — a continuous arc-length on the centerline (half-open ownership).
4. **Isotropic cylindrical reach** — `conDist` = tip→clamped-centerline distance; `surf = (conDist − FIL_R)·1e3`,
   a radial reach to a cylinder of radius `FIL_R` (actin as an **azimuth-symmetric rod**). **← azimuth COULD
   enter** (angle-dependent presented reach replacing isotropic `FIL_R`) — **it does not.**
5. **Motor-pose gates** (`gate2D`, the **8-gate AND**) — g0 `surf<dBind`, g1 `psiErr<psiDeg`, g2
   `phiErr<phiDeg`, g3 `thetaErr<thetaDeg`, g4 `preload<preloadPn`, g5 `eKt<energyKt`, g6 `headSide<A2`, g7
   `bindArc` margin. **All angle errors are motor pose vs motor reference constants** — none from the filament
   frame. (`psiActin`, g1's motor-side scalar, is the natural coupling point *if* actin helical phase were ever
   to constrain the bound-head roll; today it is a per-motor config constant, invariant under `rotateDimerZ`.)
6. **Attachment state** (`matBind`) — writes `boundSeg=candSeg`, `bindArc=candBindArc`. **Retained state =
   `(boundSeg int, bindArc float)`; no azimuth retained.**

**Exact locations where azimuth could enter but currently does not:** the gate signatures (add `filYVec`);
`nearestSeg2D` (resolve the ⊥ direction + helical phase); the `surf` gate g0 (angle-dependent reach); the GPU
`D` packing (a new azimuthal filament field — none exists). The filament frame `filYVec` **is** allocated,
maintained by `DerivedGeometrySystem`, and GPU-resident, but is read **only post-binding** by
`CrossBridgeSystem.bondForces` (the cross-bridge force) and the integrator/derive — **the bind gate never sees
it.** The per-dimer `dimerBhat/dimerEconv` ("random azimuth about vertical") are **motor** anchored-orientation
frames, not the actin helical phase, and are not the filament frame.

**Canonical verdict:** the canonical explicit-S2 binding path uses filament azimuth **nowhere**.

### Lumped / gliding path
- **`bindNearest` baseline** — reach predicate `reachTestDistSq` (α-foot + `conDist<myoColTol` +
  `motDotFil≥alignTol` + `rodDotFil≥0`); nearest reachable segment wins; stores `(boundSeg, bindArc)`. **Note:**
  `reachTestDistSq` computes the head's perpendicular offset `(dx,dy,dz)=(cp−head)` — *the head's azimuthal
  position around the filament* — and **discards it.** This is exactly the quantity a real azimuthal gate /
  off-axis attach would need (a reuse candidate, §10).
- **`bindNearestAzim` (Inc-2, hard)** — `kinParams[25]=1`. Threads `segYVec`; per reachable candidate,
  reconstructs `u`, perp-foot `footArc`, axial half-window; reads cohered `segYVec`, `segZ=u×segY`; **site scan**
  `j=−4..4` (`AZ_NJMAX=4`) at `arc=footArc+j·monoSp`, `φ=twistRate·(arc−½segLen)`, presented radial
  `n̂=cosφ·segY+sinφ·segZ`; **accepts** iff any in-window site has `headU·n̂ < −cos(Δ)`; nearest qualifying segment
  wins; **stores `(boundSeg, bindArc)` on-axis.**
- **`bindNearestFalloff` (Inc-3, graded)** — `kinParams[26]=n`. Same scan; per site `b=½(1−headU·n̂)`;
  **MAX-combine** over reachable sites; affinity `a=b_best^n`; race-free wang-hash draw `u<a` (salt "AZBD").
  **Stores `(boundSeg, bestArc)` on-axis.**
- **`segYVec` + analytic helical phase** — the filament frame `segYVec` and the analytic
  `φ(s)=twistRate·(arc−½segLen)` are what these gates read. **Why the gate throttles but cannot drive twirling:**
  azimuth enters the *accept decision* only; the *attachment point* is the same on-axis `bindArc` as
  `bindNearest` (no radial offset, no ψ, no moment arm retained). Downstream, `CrossBridgeSystem.bondForces`
  reads the on-axis attach (`ap=sc+aOff·su`) ⇒ segment lever `RS ∥ u` ⇒ `TS=RS×F` has **zero ‖u component.** The
  full 3-vector torque *is* gathered and the integrator *does* project onto `u` (`bwx`) — the channel is open —
  but it is fed zero by construction.

---

## 6. Why the current model cannot twirl

The load-bearing mechanical reason (the twirl loop is **open**):

- **Attachments are stored on the filament centerline** (`bindArc` scalar; on-axis in both lineages).
- **The bond lever arm is parallel to the filament axis** (`RS ∥ u`).
- **Cross-bridge torque therefore has zero axial component** — `TS = RS×F` has no ‖u projection.
- **The roll torque channel exists but is fed zero** — the full 3-vector seg torque is gathered and the
  integrator projects onto `u` (`bwx`), fully assembled and unblocked, but receives no axial drive.
- **Off-axis bond placement (Piece 2) is the missing keystone** — the one link that would let a bound motor
  exert a ‖u twirl torque. With the gate + spring but no off-axis attach, the gate throttles *which* heads bind
  while every bond drives **zero** net filament rotation.

---

## 7. Discrete-site and occupancy status

Precisely — **there is no discrete-site model in either lineage:**
- **No monomer index.**
- **No 13/6 (or equivalent) discrete lattice** (no indexed 2.75 nm axial rise, no indexed per-monomer azimuthal
  increment, no two-start enumeration).
- **No site ID** (no per-attachment retained site identity across segment boundaries).
- **No site-specific occupancy** (no per-site occupancy/exclusion grid).
- **No nearest-site lookup** (candidate selection is nearest-*segment* + continuous foot, not nearest-site).
- **No retained site orientation** (bond state is `(boundSeg, bindArc)` only).
- **Existing site-like behavior is continuous sampling only** — the Inc-2/Inc-3 "site scan" is a fixed ±4-point
  sampling (`AZ_NJMAX=4`, `arc=footArc+j·monoSp`) of the **continuous** phase `φ(s)`; `monoSp` is a sample
  spacing, not an index.
- **The 5.4 nm occupancy rule is a continuous axial-gap veto, not a discrete lattice** — same-filament
  `STANDING_EXCLUSION_NM = 5.4 nm` implemented as `|Δarc| < 5.4 nm` along the continuous material coordinate
  `filMatCoordUm` (`occupancyVeto`), CPU-only, default-off, **not** in the canonical production gate, carrying no
  site index — a running minimum-separation check, the continuous realization of steric co-occupancy.

> If only a continuous azimuthal acceptance gate exists, say so: **it does.** This is a continuous azimuthal
> acceptance gate, not a discrete-site model.

---

## 8. Prior experiments and negative results (Inc-2 / Inc-3)

- **Inc-2 — hard azimuthal gate** (`bindNearestAzim`): an orientational acceptance cutoff (accept iff a sampled
  site presents antiparallel to the head within Δ).
- **Inc-3 — graded falloff** (`bindNearestFalloff`): a graded orientational affinity `a=b^n`, MAX-combined over
  reachable sites, with a stochastic accept draw.
- **Engagement scaling:** a per-head orientational throttle **scales** engagement (~0.55–0.85× avgBound
  depending on sharpness/steepness).
- **Failure to cap co-occupancy:** the throttle cannot *cap* how many heads co-occupy a filament — it only lowers
  the per-head accept probability.
- **Failure to create a velocity–density plateau:** because it cannot cap co-occupancy, it never bends the
  velocity–density curve to a plateau at any plausible acceptance window / steepness — **a decisive negative.**
- **Conclusion (recorded):** the real capping lever is **steric co-occupancy exclusion**, not orientation.
  **Orientation-only gating should not be repeated as a saturation strategy** — do not re-run the
  orientation-only sweep expecting a plateau. (Sources: `AZIMUTHAL_GATE_INCREMENT2.md`,
  `AZIMUTHAL_FALLOFF_INCREMENT3.md`.)

---

## 9. Git provenance

*(Detailed history is in the archive; summary here.)*

- **Foundational commit `9c0f1d9`** (2026-07-10) introduced **all** azimuthal / roll-spring / helical-binding
  infrastructure in one drop (its subject is a gliding velocity-ceiling investigation and does not mention
  azimuth — the drop is findable only by file/token search). File-set: `RollSpringSystem.java` (+186),
  `RollSpringHarness.java` (+431), `BindingDetectionSystem.java` (+159, the azim methods), `GlidingHarness.java`
  (+99), `MotorStore.java` (+4, `kinParams[22–26]`), the azimuthal docs + sweep scripts. `kinParams`: [22]=cos(Δ
  accept), [23]=twistRate rad/µm (signed, LEFT-handed), [24]=monomer spacing µm, [25]=azGate(0/1), [26]=falloff
  steepness n; **defaults 0 ⇒ off ⇒ byte-identical.**
- **Present on the current branch AND `main`** (and `origin/main`) — identically. The infrastructure sits *below*
  the current branch's ~20-commit post-fork divergence; none of those 20 commits touch the azimuth/roll files.
- **No branch-exclusive azimuthal work** — `main..HEAD` and `HEAD..main` filtered to the azimuth files both
  return empty; `RollSpring*.java` was never modified on any branch since `9c0f1d9`.
- **Later adjacent commits** (both on branch + main): `bdc5019` (`-glidekon` finite-rate binder, +113 to
  BindingDetection; does not touch RollSpring) and `2170aff` (fine-dt / J2 conformation study, GlidingHarness
  only; the RollSpring token hit is a build-note, not physics).
- **No hidden twirling implementation elsewhere** — the keyword `twirl` matches **nothing** in history (the
  concept is spelled `twist`/`twistRate`/`helical`; a `twist` hit in the unrelated `fd47f47` sphere-head commit
  is flagged only so it isn't mistaken for azimuth work). **No abandoned/unmerged azimuth work anywhere.**

---

## 10. Reuse / do-not-revive decisions

**Reuse:**
- **`RollSpringSystem`** (Pieces 3 + 4) verbatim — validated, dt-robust springs-continuum form, bit-identical
  CPU↔GPU, default-off.
- **The existing continuous gate machinery** (`bindNearestAzim` / `bindNearestFalloff`) as the throttle template
  — including the PTX-safe fixed-bound site scan and the analytic `φ(s)=twistRate·(arc−½segLen)`.
- **The discarded perpendicular-offset calculation** in `reachTestDistSq` (`BindingDetectionSystem.java:74-76`) /
  the `nearestSeg2D` perpendicular — this is exactly the head azimuthal position ψ needed for both a real gate
  and off-axis attach.
- **The continuous axial-gap occupancy veto** (`occupancyVeto`, 5.4 nm) for co-occupancy capping.
- **The existing filament material frame** (`uVec/yVec/zVec`, roll-preserving derive) — the twirl coordinate is
  already there.

**Do NOT revive:**
- The **orientation-only gate as a saturation mechanism** — Inc-2/Inc-3 closed this as a decisive negative.
- **v1 helix handedness sign without re-derivation** — v1's rendering screw sign is non-authoritative; derive
  handedness fresh.
- **v1 render offset as a physical moment arm** — v1's `helixMonOffset` (0.8 nm render offset) is not a moment
  arm; use the physical filament radius.
- **A discrete-site lattice as the first implementation step** — nothing needs one yet; both the gate and the
  twirl drive are expressible on the continuous coordinate (abstract-from-the-second-instance).

---

## 11. Smallest safe next build

**The two goals have different smallest steps and must not be conflated.**

**A. For site-limited co-occupancy** (throttle how many heads share a filament) **in the canonical explicit-S2
path:**
- **Port the continuous axial-gap occupancy veto** (`occupancyVeto`, 5.4 nm) from the CPU-only diagnostic into
  the canonical `matGeomGate` / `dimerBindGate`, reusing the continuous `bindArc` / `filMatCoordUm` material
  coordinate already computed.
- **Keep it flag-gated, default-off, and noncanonical.** This is the lever the gate experiments identified, needs
  **no azimuth and no new state**, and is the recommended first step if the target is capping engagement.

**B. For twirling:**
- **Prototype off-axis bond placement (Piece 2) in the lumped/gliding lineage first** — where the gate + roll
  spring already exist.
- **Use the physical filament radius** for the moment arm (not v1's render offset).
- **Store the binding azimuth** needed to reconstruct the off-axis site (one per-motor material ψ relative to the
  rolling `seg.yVec`).
- **Add cumulative-turns, filament angular velocity (ω), and axial-torque observables** (the Piece-4
  observability), and confirm the roll rate `bwx` responds.
- **Keep it flag-gated and default-off.** Prototype the drive **before** any port to the canonical explicit path.

**The two goals should not be conflated:** goal A (co-occupancy capping) needs no azimuth; goal B (twirling) needs
the off-axis drive. Either way, **do not start with a discrete-site (13/6) model.**

---

## 12. Canonical-status decision

- **No canonical version bump is required for the next prototype.** Both recommended next steps are additive,
  default-off, and byte-identical-when-off.
- **All new work remains a named noncanonical extension** — the canonical explicit-S2 production path must stay
  azimuth-agnostic and byte-identical unless/until a twirl or site-limited-binding deliverable is actually
  required and validated.
- **Promotion to canonical would require validation and CPU/GPU equivalence on the affected path** (per the
  per-assay-class GPU sign-off) — a fresh CPU↔GPU equivalence pass on the changed hot kernel, only after a
  validated deliverable.

---

## Appendix A. Detailed evidence map

- **Structured file / line / lineage / read-write / status / canonical-status references:**
  `ACTIN_HELICAL_BINDING_CODE_MAP.csv` (the maintained companion — do not duplicate its file/line detail in
  prose).
- **Superseded source reports (provenance only), under `archive/audit_2026-07-23/`:**
  - `ACTIN_AZIMUTH_AND_HELICAL_SITE_AUDIT.md` — the primary audit (headline, primary-question answers,
    azimuth-state convention table, discrete-site audit, lineage separation, four-piece plan, validation
    supportability).
  - `ACTIN_BINDING_PATH_TRACE.md` — the full two-lineage bind-path trace (exact call chains + where azimuth could
    enter).
  - `TWIRLING_DOF_AND_OBSERVABLE_AUDIT.md` — the roll-DOF + drive + observables audit.
  - `ACTIN_AZIMUTH_GIT_HISTORY.md` — full git provenance (commit `9c0f1d9`, cross-branch spread, no
    branch-exclusive work).
  - `EXISTING_HELICAL_WORK_VERDICT.md` — the verdict synthesis + smallest-safe-next-step + reuse/do-not-revive.
- **Prior-experiment source docs (not part of this audit set; referenced):** `AZIMUTHAL_GATE_INCREMENT2.md`,
  `AZIMUTHAL_FALLOFF_INCREMENT3.md`, `AZIMUTHAL_BINDING_BUILD_READ.md` (the four-piece build plan),
  `ROLL_SPRING_PROTOTYPE.md`.
