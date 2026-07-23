# ACTIN_AZIMUTH_AND_HELICAL_SITE_AUDIT

**Read-only audit (2026-07-23).** Branch `gpu-mat-bottlenecks-explicit-singlehead`. No code modified, no
runs executed. Sources: direct code read + three parallel investigation passes (git-history, lumped/gliding
lineage, explicit-S2 lineage). Companion files: `ACTIN_AZIMUTH_CODE_MAP.csv`, `ACTIN_AZIMUTH_GIT_HISTORY.md`,
`ACTIN_BINDING_PATH_TRACE.md`, `TWIRLING_DOF_AND_OBSERVABLE_AUDIT.md`, `EXISTING_HELICAL_WORK_VERDICT.md`.

---

## 0. Headline

There **is** substantial azimuth/roll infrastructure, but it must be read in the right frame:

1. **It lives entirely in the LUMPED / GLIDING lineage** (`GlidingHarness` + `BindingDetectionSystem` +
   `MotorStore` + `RollSpringSystem`/`RollSpringHarness`) — **not** the canonical explicit-S2 / HMM-dimer mat
   path this branch is built on.
2. **It is a CONTINUOUS azimuthal acceptance gate + a torsional-roll coherence spring** — **not** a discrete
   helical-site model. There is no monomer index, no per-site occupancy, no retained per-attachment site ID,
   no two-start 13/6 enumeration, and no stored per-filament φ₀.
3. **The twirl loop is OPEN.** The design's Piece 3 (roll spring) and Piece 4 (roll thermostat) are built;
   **Piece 2 — off-axis bond placement, the only link that lets binding exert an axial torque — is NOT
   built.** Bonds still attach on the centerline. The gate therefore only *throttles engagement*; it drives
   **zero** twirl.
4. **The canonical explicit-S2 binding path reads no azimuth at all.** Actin enters binding as a 1-D
   centerline (`filCoord`) + polarity axis (`filUVec`) + segment length (`filSegLen`). The filament `yVec`
   frame exists and is even device-resident, but the bind gate signature omits it; only the post-binding
   cross-bridge *force* kernel reads it.
5. **All of it is default-OFF, flag-gated, byte-identical-canonical-when-off**, introduced in one commit
   (`9c0f1d9`, 2026-07-10), present identically on both the current branch and `main`.

**Primary verdict: C** (a continuous azimuthal gate exists; no discrete helical-site model exists), qualified
by **B** (azimuth *state* exists and is preserved, but the canonical explicit binding path does not use it)
and **D** (the gate lives in the lumped lineage and would require a port/reconciliation to reach the
canonical explicit-S2 path). See `EXISTING_HELICAL_WORK_VERDICT.md`.

---

## 1. Primary-question answers (quick reference)

| # | Question | Answer |
|---|---|---|
| 1 | Does each filament store an azimuthal phase / rotational start coord? | **Partially.** Each rigid rod carries a full body frame `uVec/yVec/zVec`; azimuth = roll of `yVec` about `uVec`. There is **no separate stored φ₀ scalar**; the intra-segment phase `φ(s)=twistRate·(arc−½segLen)` is computed on the fly. |
| 2 | Which branch/commit introduced it? | `9c0f1d9` (2026-07-10), a single foundational drop. See `ACTIN_AZIMUTH_GIT_HISTORY.md`. |
| 3 | Present on the current canonical branch? | **Yes** — and identically on `main`/`origin/main` (below the fork point). No branch-exclusive azimuth work anywhere. |
| 4 | Which CPU/GPU paths preserve/update it? | The rod frame (`yVec`) is updated by `DerivedGeometrySystem` and integrated by `RigidRodLangevinIntegrationSystem` on both runners, and is transferred to and kept resident on the GPU in **both** lineages. The roll *spring* (`RollSpringSystem`) runs on CPU and in the gliding GPU TaskGraph — **gliding lineage only, and only when flagged on**. |
| 5 | Does the explicit-S2 binding path read it? | **No.** The gate consumes `filCoord`/`filUVec`/`filSegLen` only; `filYVec` is never passed to `matGeomGate`/`matBindGateExplicit`/`dimerBindGate`. |
| 6 | Discrete-site model, or continuous coord + optional azimuthal gate? | **Continuous coordinate + optional continuous azimuthal gate.** No discrete-site representation in either lineage. |
| 7 | Twirling harnesses / rotation observables / ω estimators? | A standalone roll-coherence prototype (`RollSpringHarness`) measures static per-joint twist mean/std. There is **no** cumulative-turns / filament-ω meter. An `omega()` estimator exists in `GlidingHarness` but is applied to the **motor** body u-vector (J2 study), not filament roll. |
| 8 | Which earlier azimuthal/site experiments were run, and why judged insufficient? | Inc-2 (hard orientational gate) and Inc-3 (graded falloff) — **both a decisive negative**: an orientational throttle at any plausible sharpness *scales* engagement but cannot *cap* co-occupancy, so it does not saturate the velocity–density curve. The docs conclude the real lever is **steric co-occupancy exclusion**, deferred. |

---

## 2. Azimuth-state audit (exact convention)

| Property | Value | Source |
|---|---|---|
| Representation | Roll of body-frame `yVec` about `uVec` (a real tracked coordinate); derived `zVec = uVec×yVec` | `FilamentStore.java:41-45`, `RigidRodBody.java:31-34` |
| Units | **radians** internally (`rollParams[2]=restRad`, wrapped (−π,π]); degrees only for the human config constant | `RollSpringSystem.java:34,62,116` |
| Zero reference | Relative to the neighbour-joint rest twist (`e = phi − rest`, wrapped), **not** an absolute lab zero | `RollSpringSystem.java:136-137,155-156` |
| Handedness / sign | **LEFT-handed**, negative about pointed→barbed (`u`); derived fresh (v1's render screw sign is non-authoritative) | `RollSpringSystem.java:31-34`; `TWIST_PER_MON_DEG=-166.5` `RollSpringHarness.java:38` |
| Twist magnitude | 166.5°/monomer (v1 `helixAngInc=π/13.333 ⇒ π−helixAngInc`); as a rate `twistRatePerUm ≈ −1076 rad/µm` | `RollSpringSystem.java:30-31`; `GlidingHarness.java:553` |
| Relation to tangent / material frame | Azimuth measured *about* the tangent `uVec`, in the rod's own material frame; `DerivedGeometrySystem.derive` re-orthogonalizes `yVec' = zVec×uVec` each step, **preserving roll** while cleaning Euler drift | `DerivedGeometrySystem` (orthogonalizeY/derive) |
| Whole-filament vs per-segment | **Per-segment** frames, coupled into one coherent filament frame by the inter-segment roll spring | `RollSpringSystem.java:13-15,107-169` |
| Bending transports the frame? | Yes — the frame rides the rod; roll is a genuine rotational DOF integrated via `torqueSum·u → bwx` | integrator roll channel |
| Rigid rotation changes phase correctly? | Yes — the frame rotates rigidly with the body (frame vectors are body-attached) | frame is body-resident |
| Continuous across segment boundaries? | **Coarse** frame phase is made continuous by the roll spring (joint rest = `twistRate·segLen`); the sub-segment 166.5°/mon helix aliases at frame scale and is recovered by the analytic `φ(s)` interpolation | `RollSpringSystem.java:35-37`; `BindingDetectionSystem.java:408-409` |
| Survives GPU packing? | Yes — `yVec` is a planar SoA `FloatArray`, transferred and kept device-resident in both lineages | explicit `:1713`; gliding TaskGraph |

**Stored φ₀?** No per-filament pointed-end phase scalar exists anywhere. `φ(s)` is recomputed on the fly. (A
grep for `phi0`/`phase0`/`filamentPhase`/`rollPhase` finds only unrelated motor-head angle locals in
`TwoBodyConverterMotor.java`.)

---

## 3. Discrete-site audit — **NONE. Continuous only.**

Neither lineage represents any of: actin monomer index; a discrete axial monomer lattice (2.75 nm rise);
per-monomer azimuthal increment as an indexed site; two-start 13/6 helix; per-site polarity/identity across
segment boundaries; occupancy/exclusion per site; nearest-*site* lookup; site-specific binding orientation;
or a per-attachment retained site ID.

- **Lumped gliding gate:** the "Option-2 site scan" (`BindingDetectionSystem.java:458-465, 535-543`) is a
  **fixed ±4-point sampling of the continuous phase** `φ(s)=twistRate·(arc−½segLen)` at monomer-spaced axial
  points (`arc = footArc + j·monoSp`, `AZ_NJMAX=4`). `monoSp` is a sample spacing, not an index. The only
  retained bond state is `(boundSeg, bindArc)` = (segment id, **continuous** arc-length) — same as ordinary
  `bindNearest`.
- **Explicit-S2 gate:** actin is reduced to centerline + polarity + length; binding retains
  `(boundSeg, bindArc = footC+half)`, a continuous half-open segment-ownership arc.

> **If only a continuous azimuthal acceptance gate exists, say so:** it does. This is a **continuous
> azimuthal acceptance gate**, not a discrete-site model.

**The one steric "site" rule that exists** is in the explicit lineage and is *also* continuous: the
same-filament **bound-site occupancy exclusion** `STANDING_EXCLUSION_NM = 5.4 nm`
(`ExplicitHmmDimerGpuParams.java:101`), implemented as an **axial-gap veto on a continuous material
coordinate** (`|Δarc| < 5.4 nm` along `filMatCoordUm`) in `ExplicitHmmDimer3jsHarness.occupancyVeto` — CPU-only,
default-OFF, **not** in the canonical `matGeomGate`/`dimerBindGate` production gate, and carrying no discrete
site index. This is the "steric co-occupancy" lever the gliding-gate experiments pointed to — realized as a
continuous minimum-separation check, not a lattice.

---

## 4. Lineage separation (load-bearing)

| | LUMPED / GLIDING lineage | EXPLICIT-S2 / HMM-DIMER "mat" lineage (**canonical, this branch**) |
|---|---|---|
| Harnesses | `GlidingHarness`, `RollSpringHarness` | `ExplicitCompleteMatHarness`, `ExplicitHmmDimer*Harness` |
| Bind gate | `BindingDetectionSystem.bindNearest` / `bindNearestAzim` (Inc-2) / `bindNearestFalloff` (Inc-3) | `MatSoaSlice.matGeomGate`, `TwoBodyBeamAnalyticGpu.matBindGateExplicit`, `ExplicitHmmDimerGpuKernel.dimerBindGate` |
| Azimuth in binding | **Continuous gate present** (flag-gated, default-off); reads `segYVec` | **Absent** — `filYVec` not in the gate signature |
| Roll spring | `RollSpringSystem` (present, flag-gated) | not wired |
| Off-axis attach (twirl drive) | **Not built** (Piece 2) | **Not built** |
| Occupancy exclusion | none | axial-gap 5.4 nm (`occupancyVeto`), CPU-only, default-off, non-canonical-gate |
| Production status | experimental gliding assay | canonical device-resident production path |

---

## 5. What is built vs the design's four-piece twirl plan

The design plan (`docs/AZIMUTHAL_BINDING_BUILD_READ.md`, "Build-piece sizing") is the right yardstick:

| Piece | Design intent | Status |
|---|---|---|
| **1. Azimuthal gate** (throttle) | thread the frame in; accept iff site azimuth faces the head | **Built (continuous)** in the gliding lineage — `bindNearestAzim`/`bindNearestFalloff` |
| **2. Off-axis bond + axial torque** (twirl DRIVE) | attach at radius R at the site azimuth so F8 exerts a ‖u torque | **NOT built** — bonds attach on-axis; the twirl loop is open |
| **3. Torsional-roll spring** (coherence) | couple neighbour rolls to a twisted rest | **Built** — `RollSpringSystem` (Inc-1/1b), springs-continuum form |
| **4. Roll thermostat** (observability) | damp the ‖u Brownian kick so twirl is visible | **Built** — `RollSpringSystem.dampRoll` |

Piece 2 is the missing keystone: with the gate + spring but no off-axis attach, the gate throttles *which*
heads bind but every bond's lever is ∥ `u`, so `TS = RS×F` has zero axial component and nothing drives net
filament rotation.

---

## 6. Validation checks — supportability (no new runs executed)

This is a read-only audit; no harness was run (heavy gliding sweeps are production-scale under the
CPU-fallback disclosure rule, and the decisive check is answerable structurally). Supportability and expected
structural answers:

| Check | Code support | Structural answer / note |
|---|---|---|
| 1. Init reproducibility (same seed → same azimuth) | `run_rollspring.sh` seeds a twisted rest; RNG is counter-based Wang hash (bit-identical by construction) | Reproducible by construction; not run |
| 2. Rigid covariance (rotate system → identical eligibility) | Frame vectors are body-resident; gates use only dots/perp-distances | Covariant by construction (no lab-frame absolute) |
| 3. Segment-boundary continuity of phase/site | Roll spring makes coarse phase continuous; `φ(s)` analytic; no discrete site ID to jump | Continuous by construction; **no candidate-*site* identity exists to be continuous** |
| 4. Polarity reversal → reversed indexing | `twistRate` is signed; axis from `end2−end1` | Sign flips with polarity; but there is no discrete index to reverse |
| 5. CPU/GPU packing identity of azimuth | `yVec` is planar SoA, transferred both lineages | Identical by construction (recorded CPU↔GPU bit/event-identity of the gates) |
| 6. **Active-path confirmation** (perturb azimuth → do canonical stats change?) | — | **Answered structurally: NO.** The canonical explicit-S2 bind gate reads no azimuth field; perturbing filament `yVec` cannot change canonical binding statistics. In the gliding lineage the gate *does* read `yVec`, but that path is non-canonical and default-off. ⇒ **Azimuth is inert to canonical binding by construction.** |

---

## 7. Cross-references

- Git provenance & branch containment → `ACTIN_AZIMUTH_GIT_HISTORY.md`
- File/line/lineage/state table → `ACTIN_AZIMUTH_CODE_MAP.csv`
- Full bind-path traces (both lineages) → `ACTIN_BINDING_PATH_TRACE.md`
- Roll DOF + observables → `TWIRLING_DOF_AND_OBSERVABLE_AUDIT.md`
- Verdict + smallest safe next step → `EXISTING_HELICAL_WORK_VERDICT.md`
