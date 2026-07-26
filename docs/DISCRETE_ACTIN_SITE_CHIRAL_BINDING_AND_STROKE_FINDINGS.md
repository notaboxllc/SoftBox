# Discrete Actin Sites, a Head Rotational DOF, and Slightly-Askew Actomyosin Mechanics

**Authoritative report for this increment** (2026-07-24, branch `gpu-mat-bottlenecks-explicit-singlehead`). Sole
Markdown report for this task. Tests whether filament twirling can arise from a **local, helix-informed mechanical
interaction between myosin and DISCRETE actin binding sites** — a different mechanism from the (closed) Vilfan
moving-target-zone route.

**Noncanonical, flag-gated, DEFAULT-OFF, byte-identical when disabled. No canonical default, parameter, chemistry,
S2/stroke mechanics, dt, RNG stream, event ordering, or `MotorModel.CANON_VERSION` changed. No parameter was tuned
to produce rotation, and no torque sign is selected by any parameter.**

---

## 1. Baseline, runner, and monitoring

- **Baseline commit:** `5227f05` (`matsoa(vilfan): staged Brownian-noise ablation …`), branch
  `gpu-mat-bottlenecks-explicit-singlehead`. Dirty at start: 4 unrelated doc/script files.
- **aorus**; Java 21 (OpenJDK 21.0.11, `--enable-preview`), TornadoVM 4.0.1-dev **PTX**, **NVIDIA RTX 5070**.
- **Device flags** (in `scripts/run_chiral_sites.sh`): `-Dtornado.enable.fma=false` (the `matS2SolveStep` PTX FMA
  lowering defect), `-Dtornado.recover.bailout=false` (**a lowering failure THROWS — no silent CPU fallback**),
  `-Dtornado.tvm.maxbytecodesize=65536`, `-Xmx8G`.
- **Monitoring (mandatory policy).** The external recorder was verified RUNNING before the first GPU run
  (`gpu-crash-recorder.sh --status`, session `/home/jba/gpu-crash-records/20260724T092728Z`), and **every** GPU
  command in this task was launched through `scripts/run_gpu_monitored.sh` as the outermost launcher. The new
  entry point `softbox.ChiralSiteHarness` integrates `TornadoCrashDiagnostic` lifecycle tracing (plan
  construction → execute loop → result handling → explicit `plan.close()` → JVM shutdown) before any campaign use.
  Evidence locations are listed in §16.
- **Runner disclosure, and the CPU/GPU policy position.** Deterministic fixtures: **CPU** sequential runner.
  Equivalence: the **full** `buildGlidingGraph` device-resident, compared step-by-step against the CPU runner
  (§11). The **decisive mechanism measurement (§12) is on the CPU runner**; the dynamic arm table (§13) and the
  lattice comparison (§14) are **GPU device-resident**. Per `docs/CPU_GPU_VALIDATION_POLICY.md` this satisfies the
  triggered-confirmation rule: the new hot kernels got a full-graph CPU↔GPU equivalence pass at this revision, the
  ±eps arms differ only in a **data** value (identical hot-kernel structure, so no per-arm CPU arbitration is
  required), and the one endpoint a claim is made on was measured on the CPU runner outright.

---

## 2. Stage 0 — architecture and force/DOF audit

Read directly from the current source (`TwoBodyBeamAnalyticGpu.java`, `ExplicitCompleteMatHarness.java`,
`CrossBridgeSystem.java`, `TwoBodyConverterMotor.java`), not from prior reports.

### 2.1 Generalized coordinates of the explicit-S2 motor

Per motor the solved system is **14 DOF** (`n = 3M + 2`, `M = 4` at L40): the 12 free S2 beam-node coordinates plus
the two angles `phi` and `psi`. `matS2SolveStep` assembles the exact energy Hessian + drag and takes **one implicit
Newton step per timestep** (14×14 Gauss–Jordan, `maxIt = 1`). `thetaS` (the converter rest angle) and `psiActin`
(the bind-spring reference) are **inputs**, not coordinates.

### 2.2 Head pose construction — and why there is no head material frame

`matBeamGeom` / the tail of `matS2SolveStep` build the head geometry in closed form from the pivot `P` (beam node
`M`), the angles, and the base frame:

```
uB   = eup*cos(phi) + bhat*sin(phi)              C  = P + lb*uB
xF8  = C + rotConv(d0, psi)                      xH = C − rotConv(rc, psi)
```

`matPlaceHeadExplicit` then writes the shared head sub-body as `coord = xH`, `uVec = normalize(xF8 − xH)`, and
**`yVec = perp3(uVec)` — a LAB-FIXED synthetic perpendicular** (largest-component construction). The head therefore
has **no material frame and no spin coordinate**: its roll about its own long axis is unrepresented, and the head
`yVec` carries no physical information.

### 2.3 Motor material-frame availability

The base frame is **scene-global and lab-fixed**: `bhat = +x`, `eup = +z`, `econv = +y`
(`TwoBodyConverterMotor.java:4453`), replicated into every motor's 15-slot `frame` row by
`ExplicitMatSolveHarness.frameArr` — **all motors share one base orientation**; only the anchor point `g4E[m]`
differs. This is exactly the scene artifact the Brownian-ablation report flagged, and it is why the randomized
base-azimuth control in §12 is load-bearing rather than cosmetic.

### 2.4 Complete force / torque channel inventory (explicit-S2 single-head gliding)

| Channel | Coordinate | Filament reaction? | Overlaps a bound registry about the bond axis? |
|---|---|---|---|
| F8 cross-bridge spring | translational bond `xF8 ↔ site` | **YES** — `−F` at the site, `TS = RS × (−F)` gathered | No (a force, not a torsional registry) |
| F9 head-`uVec` alignment | head û vs seg û | **identically ZERO** — `xbParams[2] = j1FMT = 0f` for this model | No |
| F10 head-`yVec` alignment | head yVec vs seg yVec | **identically ZERO** (same `j1FMT`); and the head `yVec` is the lab-fixed synthetic perpendicular | No |
| Converter spring `kc(psi−phi−thetaS)` | `theta = psi − phi` | No (motor-internal) | No |
| Bind spring `kbnd(psi − psiActin)` | `psi`, about `eup` | **No** | Nearest analogue, but its reference is a per-motor **constant**, it acts about `eup` (≈ ⊥ the bond axis), and it exerts **no filament reaction** ⇒ no double-count |
| Beam stretch / bend / floor | beam nodes | No | No |
| Node + angle drag and Brownian | 3 stochastic RHS terms | No | No |

### 2.5 Where motor-side torque is consumed — a load-bearing finding

`CrossBridgeSystem.bondForces*` writes a **head-side torque** into `bondData[d+3..5]`. In the explicit-S2 lineage
**that slot is never read**: `matS2SolveStep` consumes only the head **force** `bondData[d+0..2]` (into the beam
node `pB` and the generalized `Qphi`/`Qpsi`), and `CrossBridgeSystem.segGather` reads only `bondData[d+6..11]`
(the segment-side force and torque). The head-side torque slot belongs to the lumped rigid-rod motor lineage.

**Consequence (the Stage-0 decision constraint):** a couple applied to the head can only be represented if it is
carried by a coordinate the solver actually integrates. Adding one is the entire point of Stage 1.

### 2.6 Which axis — and why the beam is not perturbed

The chosen coordinate is the head's roll about **its own long axis** `eBind = normalize(xF8 − xH)` — a simulated
local material direction, not a laboratory axis, not the coverslip normal, not a shared azimuth.

- **Both `xF8` and `xH` lie ON that axis**, so a rotation about `eBind` moves neither. The coordinate is therefore
  **exactly decoupled** from the 14×14 beam+angle system: the canonical solve is byte-unchanged, the system stays
  14×14, and no invasive solver redesign is needed (this is what makes the increment M1–M4 territory rather than
  M7).
- Under the rotation-minimizing (parallel-transport) convention used for the material reference, a **pure couple
  about `eBind` has zero generalized force on `phi` and `psi`**, so the accounting closes: the couple acts on the
  new coordinate and on nothing else on the motor side.
- The alternative candidates were rejected: the *actin bond axis* would leave an unrepresentable generalized force
  on `phi`/`psi` whenever it is not parallel to the head axis; the base axis `eup` is a **laboratory** direction
  and is already the `phi`/`psi` axis.

### 2.7 Confirmation of the prior report's claim about `phi` and `psi`

**Confirmed against the current code, with one correction.** In the *solve*, both `phi` and `psi` carry their
generalized forces about **`eup`** — `Jphi = eup × (C−P)`, `Jpsi = eup × (xF8−C)`, `Qphi = eup·((C−P)×F8)`,
`Qpsi = eup·((xF8−C)×F8)` — in the device kernel (`frame[6..8]`) and identically in the CPU twin
`TwoBodyConverterMotor.s2SolveM` (`double[] E = G.eup`). The *geometric* rotations, however, are **not** about
`eup`: `phi` rotates `uB` inside the `(eup, bhat)` plane and `psi` rotates about `econv`. So the generalized-force
axis and the kinematic rotation axes are not the same vector in this model.

**This is pre-existing, faithful (both twins agree exactly), frozen canonical behaviour, and is NOT touched by this
increment.** It is recorded here because it is the sort of thing a future reader will trip over, and because it
independently reinforces the Stage-0 decision: the new coordinate is kept strictly decoupled from `phi`/`psi`
rather than entangled with them.

### 2.8 One pre-existing ordering discrepancy, flagged not fixed

The legacy helical-surface tasks (`surfAzim`/`surfPrune`) sit **before** `chem` in `buildGlidingGraph` but
**after** `chem` in `stepGlidingCPU`. The new tasks introduced here are placed **identically in both runners**
(site snap + occupancy immediately after the bind; head-roll/registry after `bond` and before `segGather`;
stroke skew immediately after `chem`), which is why the full-graph equivalence in §11 is bit-close for all 200
steps rather than merely aggregate-agreeing.

---

## 3. Stage 1 — the head rotational DOF (`headRef` / `omega`)

**Representation.** The coordinate is stored as a **unit 3-vector** `headRef` (SoA, planar, one per motor), not as
an angle — so it transforms covariantly under any rigid rotation of the scene and can never be a laboratory angle.
`omega` (an accumulated scalar) is a **derived** material accumulation used only as a diagnostic.

**Per-step evolution** (`ChiralSiteSystem.headRollStep`, one implementation, both runners):

1. `eBind = normalize(xF8 − xH)` from the beam pose.
2. **Parallel transport**: `headRef ← normalize(headRef − (headRef·eBind) eBind)` — the standard discrete
   rotation-minimizing frame. (Re-seeded only if the reference has collapsed onto `eBind`: from the site
   tangential direction when bound, else from a deterministic perpendicular of `eBind`. That re-seed is an
   *initial condition*, not a standing anchor — there is no restoring term toward it, and fixture 3 shows the free
   coordinate diffuses with zero mean.)
3. **Registry torque** (Stage 3) if bound and `kOmega > 0`.
4. **Brownian torque** on its own private counter-based stream (salt `0x484F4D47` "HOMG"):
   `tauB = sqrt(2 kT gammaOmega / dt) · g`. Applied **bound and unbound** — a bound head is quieted only by the
   registry stiffness itself, never by a state switch.
5. Rotate `headRef` about `eBind` by `dOmega = (tau + tauB)·dt/gammaOmega` (Rodrigues; exact for a vector ⊥ the
   axis), accumulate `omega`.

**Rotational drag.** `gammaOmega` is the head sub-body's own existing roll drag `bRotGam[3m+2]` — **measured
2.513e-24 N·m·s** — not an invented parameter.

**Integrated in the real mechanical solve, not applied afterwards as a visualization transform:** the kernel is a
task inside the same `buildGlidingGraph` device plan (and the same `stepGlidingCPU` call sequence), placed after
the bond stage and before the gather, so its filament reaction enters the same accumulators as every other force
in the step.

---

## 4. Torque pathway and equal-and-opposite mechanics

The registry couple `tau` about `eBind` is applied to **both** sides in the same kernel:

- **motor side:** `dOmega += tau·dt/gammaOmega` — the new coordinate, and nothing else (§2.6).
- **filament side:** `bondData[d+9..11] += −tau·eBind`, i.e. the exact negative couple, accumulated into the
  segment-side torque slot that the **byte-unchanged** `CrossBridgeSystem.segGather` sums into
  `filament.torqueSum`, from which the byte-unchanged integrator drives the roll channel
  `bwx = torqueSum·û / bRotGam_x`.

Nothing is ever applied to the filament alone. The Brownian torque is a thermostat and carries no reaction —
the same convention as every other Brownian channel in the project. Fixture 19 measures the residual
`|tau_motor + tau_filament| / |tau_motor| = 5.8e-08` (float32 storage of the seg-torque slot).

---

## 5. Stage 2 — discrete persistent actin sites

**Analytic lattice in the filament material frame** (no per-site state to store, so sites bend, translate, roll
and rotate with the filament by construction):

```
uSite = filUVec[s]                                  (pointed→barbed material tangent)
nSite = cos(phi_k)*segY + sin(phi_k)*segZ           (outward radial material normal, segZ = uSite × segY)
tSite = mirrorSign * (uSite × nSite)                ⇒ (nSite, tSite, uSite) right-handed at mirrorSign = +1
xSite = sc + (localArc − half)*uSite + Ractin*nSite ,  Ractin = 3.5 nm
```

**Persistent identity.** A site is its **filament-global integer index** `k = round(globalArc / rise)` with
`globalArc = segCumArc[s] + localArc` (`segCumArc`/`segFilId` are the existing static material maps built by
`TwoBodyBeamAnalyticGpu.computeMaterialMaps`). The index is stable across segment boundaries and across steps;
it is latched at attachment and **never re-derived while bound** (fixture 14). Free slots carry `−1`.

**Site frames and lattice families** (`rise` from the project's own `Constants.actinMonoRadius = 2.70 nm`, so the
native rise is 2.70 nm rather than the prompt's nominal 2.75 — the project constant is used because the 5.4 nm
standing exclusion and the twist rate are derived from it):

| id | flag value | axial rise | azimuthal advance per site | note |
|---|---|---|---|---|
| 1 | `native` | 2.70 nm | analytic `phi(s) = twistRate·(arc − ½segLen)`, `twistRate = −1076 rad/µm` (166.5°/monomer, LEFT-handed) | full native monomer lattice |
| 2 | `every3` | 8.10 nm | analytic (≡ 3 × 166.5° = 499.5° ≡ 139.5°) | native subset — the actin-grounded ~9 nm counterpart |
| 3 | `every4` | 10.80 nm | analytic (≡ 4 × 166.5° = 666° ≡ 306°) | native subset |
| 4 | `stair9-45` | 9.00 nm | **45° per site** (staircase, not the native helix) | **artificial diagnostic lattice — NOT a claim about actin** |
| 5 | `stair9-90` | 9.00 nm | **90° per site** (staircase) | **artificial diagnostic lattice — NOT a claim about actin** |

**Binding search — bounded, never an all-pairs scan.** The canonical 8-gate bind is **byte-unchanged**; the
discrete layer runs immediately after it and snaps a *fresh* bind (`boundSeg ≥ 0 && prevBound < 0`) to a site. The
head's perpendicular foot gives the O(1) index `k0`; only `k0 ± 3` sites are examined, and the winner is the site
whose 3D surface position is closest to the head's F8 anchor, subject to a finite 3D capture radius (12 nm
default). **A fresh bind with no site in reach is RELEASED** — site-limited binding, an honest recruitment loss,
not renormalised. After the snap the attachment phase is the site's, and is **not** recomputed from an analytic
perpendicular foot on any later step.

**Exclusive occupancy** is enforced by a single-thread serial resolve in ascending head id (the validated
`matOccupancyResolve` / `matSurfaceStericPrune` pattern): a fresh head is released if another bound head already
holds the same `(filament, site id)`. Deterministic, no RNG, lowest id wins.

---

## 6. Stage 3 — the bound orientational registry

At the bound site, the preferred head material direction is

```
bHat = cos(epsBind)*uSite + sin(epsBind)*tSite ,   projected ⊥ eBind and normalised  →  bPerp
deltaOmega = signed angle( bPerp → headRef ) about eBind          (0 = perfectly registered)
U = ½ kOmega * deltaOmega²      tau = −kOmega * deltaOmega        (filament gets −tau about eBind)
```

`kOmega = 0` is **exactly inert** (fixture 20: the trajectory is bit-identical to the head-DOF-off path).

**Stiffness parameterisation (transparent, not tuned).** Because the coordinate is thermally free, the
equilibrium mismatch obeys equipartition `RMS(deltaOmega) = sqrt(kT / kOmega)`. The campaign therefore uses
`kOmega = kT / target²`:

- **soft** `kOmega = 2.00e-21 N·m/rad` ⇒ predicted RMS **1.43 rad**, measured **0.98 rad**;
- **stiff** `kOmega = 4.12e-19 N·m/rad` ⇒ predicted RMS **0.10 rad**, measured **0.099 rad**.

The explicit-Euler stability limit for this coordinate is `kOmega < 2·gammaOmega/dt = 2.01e-18 N·m/rad`; the stiff
value sits at `kOmega·dt/gammaOmega = 0.41`, comfortably inside it. Both facts are reported by the harness at run
time.

---

## 7. Stage 4 — askew bound geometry (`epsBind`)

The chiral offset is applied **entirely in the local site tangent plane**: the bound interface azimuth is
`bindAzim = phi_site + mirrorSign·epsBind`. At the actin radius this is a circumferential displacement of
`Ractin·epsBind` of the attachment point relative to the presented site, so the F8 spring acquires a systematic
**tangential** strain whose sign is the same for every motor **in the local frame**, regardless of which side of
the filament the motor sits on.

**Why this, and not a tilted "preferred bond direction" with a rest length.** The cross-bridge in this model is a
**zero-rest-length point spring** between the head's F8 anchor and the material site. A zero-rest spring cannot
carry a preferred *direction* — only a preferred *position*. Rotating the attachment position in the tangent
plane is the mathematically equivalent local-frame realisation of `b_i = cos(eps) u_i + sin(eps) t_i`, and it
needs no invented rest length.

**Why an orientational couple alone cannot do the job (a Stage-0/3 result worth stating).** The axial torque
channel is fed *only* by the tangential FORCE component at the actin radius:

```
T·û = (Ractin·nSite × F)·û = Ractin · F_tangential        (a purely axial force at any azimuth gives EXACTLY zero)
```

A couple is position-independent, and the bond axis `eBind` is approximately **radial**, so a registry couple
contributes to the axial channel only through its small `eBind·û` projection. **Registry alone is therefore not
expected to twirl; the askew *position* offset is the mechanism.** Fixture 25 verifies the identity above per
head to 6.8e-08 relative.

---

## 8. Stage 5 — askew effective stroke (`epsStroke`)

This model represents the power stroke as a **rest-coordinate switch** (`matCock` swaps `thetaS` at
ADP·Pi → ADP), not as a displacement vector, so the prompt's alternative applies: the equivalent local-frame rest
change is implemented as a **one-shot advance of the bound interface in the site tangent plane** at the stroke
transition, `bindAzim += mirrorSign·epsStroke`, detected against `prevNuc` and applied exactly once per stroke
while the head stays bound (fixture 27). The stroke's displacement therefore acquires a circumferential component
of `Ractin·epsStroke` **in the local chiral frame**.

**No external tangential force is applied.** The resulting force and its reaction come entirely from the existing
F8 pathway, which remains a closed equal-and-opposite pair by construction (fixture 26: `|F_head + F_seg|/|F_head|
= 0.00e+00`), so the path creates no energy from nowhere.

---

## 9. Force, torque, and energy accounting

- **F8 force:** `+F` at the head anchor (into the beam solve), `−F` at the material site (into `segGather`) —
  applied exactly once each, collinear, zero-rest ⇒ a closed couple for any site position. Verified 0.00e+00.
- **F8 torque on the filament:** `TS = RS × (−F)` with `RS = (arc)·û + Ractin·n̂`; its axial part is exactly
  `Ractin · F_tangential` (fixture 25, 6.8e-08 relative, evaluated on the same frames the bond was built from).
- **Registry couple:** `+tau·eBind` on the head coordinate, `−tau·eBind` on the filament (fixture 19, 5.8e-08).
- **Brownian torque on `omega`:** FDT amplitude, no reaction (thermostat convention). Measured variance
  8.1774e-03 rad²/step vs the FDT prediction `2 kT dt / gammaOmega = 8.1894e-03` — **ratio 0.9985**.
- **No alignment moment to double-count:** F9/F10 are identically zero in this model (`j1FMT = 0`).
- **Nothing is applied to the filament alone**, and no channel is applied twice.

---

## 10. Deterministic fixtures — 24 / 24 PASS

`./scripts/run_gpu_monitored.sh ./scripts/run_chiral_sites.sh -fixtures`
(log: `RUN_LOGS/chiral_sites/fixtures.txt`)

| # | Fixture | Result |
|---|---|---|
| 1 | zero generalized torque + Brownian off ⇒ coordinate unchanged | PASS |
| 2 | ±generalized torque ⇒ opposite rotation, equal magnitude (`dOmega = ∓5.968e-04`) | PASS |
| 3 | unbound Brownian: zero mean (`+3.7e-05`, SEM 1.3e-04) and FDT variance ratio **0.9985** | PASS |
| 4 | head-roll Brownian OFF ⇒ **exactly** zero stochastic increment (max = 0.0) | PASS |
| 5 | rigid scene rotation ⇒ material frame covariant (residual 7.4e-08) | PASS |
| 7 | head basis orthonormal over a long dynamic run (‖·‖−1 ≤ 4.4e-08; ⊥ eBind after transport 2.5e-08) | PASS |
| 8 | **zero-feature path bit-identical** to the canonical trajectory (300 steps) | PASS |
| 11 | site lattice OFF ⇒ prior continuous surface path reproduced bit-identically | PASS |
| 12 | fresh bind snaps onto the lattice (arc/rise residual 6.4e-07) at the actin surface radius | PASS |
| 13 | site frame rolls with the filament material frame (roll +0.400 ⇒ site turns +0.4000) | PASS |
| 14 | bound site ID latched for the whole attachment | PASS |
| 15 | site exclusivity: no two heads share a site at any step | PASS |
| 16 | occupied-site spacing is an integer multiple of the rise, in all five lattices | PASS |
| 17 | zero registry mismatch ⇒ zero registry torque | PASS |
| 18 | ±mismatch ⇒ opposite torque of equal magnitude (∓6.000e-22 N·m) | PASS |
| 19 | motor and filament couples **equal and opposite** (residual 5.8e-08) | PASS |
| 20 | registry stiffness 0 ⇒ **exactly inert** | PASS |
| 21 | reversing the head material reference through 180° shifts the registry torque by **exactly `K·π`** (rel 1.7e-08) — the registry is a proper angular potential about the bond axis | PASS |
| 22 | detachment releases the registry cleanly (bound τ ≠ 0, free τ == 0.0) | PASS |
| 23 | askew bind offsets the interface by **exactly** ±epsBind (+2.0001 / −2.0001 deg) | PASS |
| 24 | askew bind produces a resolved **eps-ODD** tangential force and axial torque | PASS |
| 25 | axial torque **equals** `Ractin × F_tangential` per head (6.8e-08 relative) | PASS |
| 26 | askew bond force pair stays equal-and-opposite (0.00e+00) | PASS |
| 27 | askew stroke advances the interface by exactly `epsStroke`, **once** per stroke | PASS |

The head-axis swing the transport absorbs between steps is reported (max `|headRef·eBind|` before transport =
0.83): the beam solve moves `eBind` *after* the roll kernel has run, and the coordinate is re-orthogonalised at
the next call. This is the ordinary behaviour of a discrete rotation-minimizing frame, not a defect, and the
post-transport orthogonality is 2.5e-08.

---

## 11. CPU/GPU equivalence and device residency

`./scripts/run_gpu_monitored.sh ./scripts/run_chiral_sites.sh -equiv` (log: `RUN_LOGS/chiral_sites/equiv.txt`)

Full `buildGlidingGraph` with every new task wired (`siteSnap`, `siteOcc`, `headRoll`; `every3` lattice, head roll
DOF ON, registry `2.0e-21`, `epsBind = +2°`), device-resident, `bailout=false`:

```
200 device-resident steps:
  siteIdMism = 0        bindMism = 0        max|dAzim| = 0.00e+00
  max|dOmega| = 5.36e-06   max|dTau| = 2.30e-26 N·m   max|dFilCoord| = 1.19e-07 µm
  firstDiv = none (bit-close for all 200 steps)   bound CPU = 9, GPU = 9   finite = true   ⇒ PASS
```

- **Every discrete decision is EXACT CPU↔GPU**: the accepted site IDs, the binding decisions, and the retained
  material azimuth are bit-identical; the continuous coordinate and torque agree at float32 last-bit.
- **No isolated-kernel shortcut**: this is the complete assay graph, and it stays device-resident with recovery
  bailout disabled (a lowering failure would throw, not silently fall back).
- **0 invalid states, 0 solver failures** throughout.
- Worth noting: the canonical surface-OFF baseline decorrelates at `t = 4`, and the earlier target-zone graph at
  `t = 4` / `t = 181`; **this graph does not decorrelate at all within 200 steps.** Quantising the attachment
  onto discrete sites removes the continuously-resampled attachment phase that was the amplifier for float
  op-order divergence.

---

## 12. The frozen-configuration ε-response — the decisive mechanism measurement

`./scripts/run_gpu_monitored.sh ./scripts/run_chiral_sites.sh -mechanism -seeds 3`
(log: `RUN_LOGS/chiral_sites/mechanism.txt`; CPU runner, disclosed)

A dynamic ±ε A/B is confounded: the two arms are chaotic trajectories that decorrelate, so an ε-odd signal has to
be dug out of trajectory noise (and, as §13 shows, at this ensemble size it cannot be). This probe removes the
confound entirely. It advances **one ε = 0 trajectory**; at each sampled step it takes the bound configuration
exactly as it stands, applies the local-frame offset ε to the bound interface azimuths, re-runs **only the bond
stage**, and reads the torque. Same heads, same sites, same filament pose, same everything else — the difference
is the mechanism and nothing else. `every3` lattice, registry off, 3 seeds × 40 sampled configurations each.

### 12.1 Shared motor base frames (the canonical scene)

```
   eps deg     tauNet N·m          F_t N     cancel     F_ax N
      -5.0     4.2441e-21     1.2126e-12        2.6   -1.515e-12
      -2.0     1.3877e-21     3.9648e-13        7.5   -1.515e-12
      -1.0     4.3426e-22     1.2407e-13       23.6   -1.515e-12
      +0.0    -5.1927e-22    -1.4836e-13       19.7   -1.515e-12
      +1.0    -1.4726e-21    -4.2075e-13        7.0   -1.515e-12
      +2.0    -2.4256e-21    -6.9302e-13        4.3   -1.515e-12
      +5.0    -5.2787e-21    -1.5082e-12        2.1   -1.515e-12
```

Every prediction of the hypothesis is met, quantitatively:

1. **Axial torque is linear and odd in ε.** Successive slopes are −0.952, −0.954, −0.953, −0.954, −0.953,
   −0.951 (×1e-21 N·m per degree) — **constant to 0.3 % across the whole ±5° range**. Fitted slope
   **−5.46e-20 N·m/rad**.
2. **Reversing ε reverses the torque** (+4.24e-21 at −5° vs −5.28e-21 at +5°).
3. **The axial force is even in ε** — `F_ax = −1.515e-12 N` at *every* ε, identical to the four printed digits
   (a `cos ε` reduction would be 0.38 % at 5°, i.e. the fourth digit). Gliding propulsion is untouched while the
   torque swings through zero and reverses. This is the "weakly/quadratically even in ε, linearly odd in ε"
   signature the task predicted.
4. **The torques stop cancelling.** `Σ|τ|/|Στ|` falls from **19.7** at ε = 0 to **4.3** at 2° and **2.1** at 5°.
   For comparison: the azimuth-blind off-axis surface arm gave 7.2–301, and every Vilfan target-zone arm gave
   26–174. **This is the first mechanism in this lineage that produces a coherent, non-cancelling axial torque.**
5. **The identity holds exactly** — `τ = Ractin · F_t` in every row (e.g. 3.5e-9 m × 1.2126e-12 N = 4.244e-21).

The nonzero **ε = 0 baseline** (−5.19e-22 N·m, zero-crossing at ε ≈ −0.55°) is the shared-motor-base artifact:
with every motor's base plane facing the same way, the residual tangential offsets share a sign. It is reported,
not hidden, and it is exactly what the randomized-base control below is for.

### 12.2 Randomized per-motor base azimuths — the M5 artifact control

```
   eps deg     tauNet N·m          F_t N     cancel     F_ax N
      -5.0     3.2691e-21     9.3403e-13        3.2   -1.164e-12
      -2.0     9.0394e-22     2.5827e-13       11.1   -1.164e-12
      -1.0     1.1471e-22     3.2775e-14       87.6   -1.164e-12
      +0.0    -6.7461e-22    -1.9274e-13       15.0   -1.164e-12
      +1.0    -1.4637e-21    -4.1821e-13        7.0   -1.164e-12
      +2.0    -2.2523e-21    -6.4353e-13        4.6   -1.164e-12
      +5.0    -4.6132e-21    -1.3180e-12        2.4   -1.164e-12
```

**The ε-odd response SURVIVES base randomization.** Successive slopes −0.788, −0.789, −0.789, −0.789, −0.789,
−0.787 (×1e-21 N·m/deg) — as linear as the shared-base case and **83 % of its magnitude**
(−4.52e-20 vs −5.46e-20 N·m/rad). The cancellation ratio again collapses (15.0 → 2.4). The axial propulsive force
is again exactly even in ε (−1.164e-12 N at every ε), though lower than the shared-base scene because rotating
each motor's base plane misaligns its stroke with the filament axis (see §13). **M5 is excluded: this is not the
shared-base scene artifact.**

### 12.3 Mirrored actin lattice — the chirality control

Reflecting the lattice (helical twist rate **and** site tangential sense both reversed — the two consequences of
one mirror operation), same shared base:

```
   eps deg     tauNet N·m          F_t N     cancel     F_ax N        (MIRRORED helix)
      -5.0    -5.0833e-21    -1.4524e-12        2.2   -1.468e-12
      -2.0    -2.2498e-21    -6.4279e-13        4.6   -1.468e-12
      -1.0    -1.3035e-21    -3.7242e-13        7.9   -1.468e-12
      +0.0    -3.5680e-22    -1.0194e-13       28.6   -1.468e-12
      +1.0     5.8998e-22     1.6857e-13       17.3   -1.468e-12
      +2.0     1.5366e-21     4.3903e-13        6.7   -1.468e-12
      +5.0     4.3724e-21     1.2492e-12        2.5   -1.468e-12
```

**The ε-odd slope reverses sign, at the same magnitude**: successive slopes +0.9445, +0.9463, +0.9467, +0.9468,
+0.9466, +0.9453 (×1e-21 N·m/deg) — mean **+0.9460** versus the native **−0.9525**, agreeing in magnitude to
**0.7 %** and opposite in sign. The chirality of the response is carried by the actin lattice, exactly as a
site-frame mechanism requires. The cancellation ratio again collapses (28.6 → 2.5).

### 12.4 The ε-odd slope across the full 2×2 of controls

Fitted ε-odd slope `dtau/deps` (N·m per degree), all four control combinations, 3 seeds × 40 frozen
configurations each:

| | shared motor base | randomized motor base |
|---|---|---|
| **native lattice**   | **−0.9525e-21** | **−0.7886e-21** |
| **mirrored lattice** | **+0.9460e-21** | **+0.5663e-21** |

**The SIGN is set by the actin lattice chirality in all four cells** and by nothing else: it is negative for the
native helix and positive for the mirrored helix, whether the motor bases share one orientation or are randomized.
The magnitude varies between 0.57 and 0.95 e-21 N·m/deg with the scene (randomizing the bases costs engagement and
mis-aligns strokes, §13.1), but never changes sign. Within each cell the response is linear to ≤ 1 %, and in every
cell the cancellation ratio collapses toward ~2–3 at ±5° from 15–29 at ε = 0. Full tables:
`RUN_LOGS/chiral_sites/mechanism.txt` (native) and `mechanism_mirror.txt` (mirrored).

### 12.5 The ε = 0 baseline — a residual, NOT a second mechanism

The zero-skew axial torque is **−5.19e-22** (native/shared), **−6.75e-22** (native/randomized), **−3.57e-22**
(mirrored/shared) and **+2.98e-22** N·m (mirrored/randomized). **It has no reproducible sign across the four
control combinations** — unlike the ε-odd slope, which is reproducible to ≤ 1 % in magnitude and flips exactly
with mirroring. It is therefore a residual of the discrete-site attachment statistics (an effective offset of
order half a degree), not a chiral mechanism and not the shared-base artifact. Its origin is not explained here.
**Every claim in this report is made on the ε-ODD component, never on the raw torque.**

### 12.6 Summary of the predicted signature

| Prediction (task §"Key predictions") | Result |
|---|---|
| torque approximately **linear** in ε | slopes constant to **0.3 %** over ±5° |
| torque **odd** in ε (reverses with ε) | yes, exactly |
| gliding/axial force approximately **even** in ε | `F_ax` identical to 4 printed digits at every ε |
| survives **randomized motor-base azimuths** | yes, **83 %** of magnitude, same linearity, same sign |
| **mirroring the site helix reverses** it | yes, in BOTH base scenes — the full 2×2 of §12.4 |
| target-zone kinetics unnecessary | target-zone hazard OFF throughout |
| torques stop cancelling | `Σ|τ|/|Στ|` **19.7 → 2.1** (shared), **15.0 → 2.4** (randomized) |

---

## 13. Pilot campaign — the dynamic arm table

`./scripts/run_gpu_monitored.sh ./scripts/run_chiral_sites.sh -campaign -gpu -steps 4000 -seeds 6`
(log: `RUN_LOGS/chiral_sites/campaign_gpu.txt`; **GPU device-resident**, 22 arm-configurations × 6 matched seeds,
4000 steps = 10 ms simulated each; target-zone hazard OFF and roll spring OFF throughout; `every3` lattice;
ε = 2°; registry soft `2.00e-21`, stiff `4.12e-19 N·m/rad`.)

```
arm                                        glide µm/s     avgB    tauNet N·m        F_t N   cancel     turns   misRMS
C0 continuous (no sites, no head DOF)     -2.713±0.204     4.09 -5.52e-24±4.3e-24        n/a   107.9  0.216±0.486  0.0000
C1 sites + head DOF (registry K=0)        -3.904±0.265     3.91 -2.93e-22±6.7e-22 -1.350e-15    35.8  0.836±0.386  0.0000
C2 sites + DOF + registry (soft)          -3.983±0.361     3.90  1.55e-22±4.4e-22 -2.015e-14    49.6  0.585±0.384  1.2337
C2 sites + DOF + registry (stiff)         -3.739±0.376     3.84  1.75e-21±6.9e-22 -4.484e-14   180.7  0.802±0.407  0.0995

B askew-bind  K=0     shared-base  +eps   -3.425±0.392     3.81 -4.06e-22±3.5e-22 -2.083e-14    62.3  0.836±0.404  0.0000
B askew-bind  K=0     shared-base  -eps   -3.642±0.331     3.91  8.42e-23±4.6e-22  1.627e-14    54.8  1.148±0.342  0.0000
   >> ODD  dtau = -2.450e-22 ± 1.9e-22 N·m (1.3 sigma) | dglide = +0.109 ± 0.162 µm/s | dturns = -0.156 ± 0.245
B askew-bind  K=0     RANDOM-base  +eps   -1.749±0.388     2.91  7.26e-23±5.8e-22 -3.505e-15    53.7  0.581±0.378  0.0000
B askew-bind  K=0     RANDOM-base  -eps   -1.705±0.287     2.87  2.66e-22±3.2e-22  1.176e-14    30.3  0.335±0.172  0.0000
   >> ODD  dtau = -9.666e-23 ± 3.2e-22 N·m (0.3 sigma) | dglide = -0.022 ± 0.091 µm/s | dturns = +0.123 ± 0.236
B askew-bind  K=stiff shared-base  +eps   -3.750±0.406     3.87  4.18e-21±2.0e-21 -1.965e-14    50.0  1.123±0.408  0.1007
B askew-bind  K=stiff shared-base  -eps   -3.441±0.381     3.82  9.69e-22±1.2e-21 -7.159e-16   105.3  0.640±0.174  0.1011
   >> ODD  dtau = +1.606e-21 ± 1.0e-21 N·m (1.6 sigma) | dglide = -0.155 ± 0.244 µm/s | dturns = +0.242 ± 0.221
B askew-bind  K=stiff RANDOM-base  +eps   -1.548±0.314     3.08 -2.70e-23±6.7e-22  2.116e-15   144.1  0.341±0.324  0.1065
B askew-bind  K=stiff RANDOM-base  -eps   -1.743±0.252     3.07 -4.58e-22±7.5e-23 -7.561e-14   220.5  0.032±0.179  0.1064
   >> ODD  dtau = +2.157e-22 ± 3.5e-22 N·m (0.6 sigma) | dglide = +0.098 ± 0.137 µm/s | dturns = +0.154 ± 0.181
B askew-bind  K=0     MIRROR-helix  +eps  -3.780±0.208     4.01 -5.10e-23±4.5e-22  1.537e-16    38.0  0.110±0.268  0.0000
B askew-bind  K=0     MIRROR-helix  -eps  -4.014±0.305     4.04 -1.72e-22±3.3e-22  6.001e-15    44.0  0.199±0.444  0.0000
   >> ODD  dtau = +6.065e-23 ± 7.9e-23 N·m (0.8 sigma) | dglide = +0.117 ± 0.115 µm/s | dturns = -0.045 ± 0.206

S askew-stroke K=0    shared-base  +eps   -3.327±0.344     4.00 -5.11e-22±2.9e-22 -2.774e-14    32.2  0.584±0.322  0.0000
S askew-stroke K=0    shared-base  -eps   -3.420±0.243     3.97 -5.84e-23±3.4e-22  5.737e-15    51.3  0.853±0.284  0.0000
   >> ODD  dtau = -2.262e-22 ± 1.2e-22 N·m (1.9 sigma) | dglide = +0.047 ± 0.138 µm/s | dturns = -0.134 ± 0.047
S askew-stroke K=0    RANDOM-base  +eps   -1.666±0.376     3.07  2.90e-22±5.6e-22  2.626e-14   188.9  0.168±0.515  0.0000
S askew-stroke K=0    RANDOM-base  -eps   -1.547±0.365     2.96  4.57e-22±3.7e-22  2.897e-14    31.0  0.156±0.249  0.0000
   >> ODD  dtau = -8.351e-23 ± 3.0e-22 N·m (0.3 sigma) | dglide = -0.060 ± 0.088 µm/s | dturns = +0.006 ± 0.321
```

### 13.1 What the dynamic campaign shows

- **The dynamic ensemble does NOT resolve the ε-odd torque at this size.** Every paired `dtau` is ≤ 1.9σ. The
  reason is quantitative and expected: the frozen probe puts the ε = 2° odd torque at ≈ 1.9e-21 N·m for a *given*
  bound configuration, but the step-to-step `tauNet` of a live 4000-step trajectory fluctuates with the
  thermally-driven binding population, and the per-arm seed SEMs are 3e-22 – 2e-21. **6 seeds × 10 ms is
  under-powered by roughly an order of magnitude**, and pairing does not rescue it because the ±ε arms
  decorrelate chaotically. This is a statistics limitation, not a contradiction of §12 — the two measurements
  agree in magnitude wherever they can be compared.
- **The signs are nevertheless consistent with §12 where the mechanism is unobstructed**: the two `K = 0`
  shared-base arms (askew-bind −2.45e-22, askew-stroke −2.26e-22 at 1.3σ and 1.9σ) carry the same negative sign
  the frozen probe gives for +ε, and the mirrored-helix arm flips it (+6.07e-23).
- **Sites and the head DOF do not damage the assay.** `C1`/`C2` keep engagement (`avgBound` 4.09 → 3.84–3.91) and
  gliding (−2.71 → −3.74…−3.98 µm/s). Gliding is if anything *faster* with discrete sites, but note the
  confound: `C0` is the canonical **centreline** bond while `C1`/`C2` additionally carry the off-axis actin
  surface, so this is not a clean single-variable comparison and no claim is made from it.
- **The registry alone is not a twirl mechanism** — exactly as §7 predicts on geometric grounds. `C2 stiff` shows
  `tauNet = 1.75e-21 ± 6.9e-22` (2.5σ) on the shared base, but the registry couple acts about the ≈ radial bond
  axis, so it reaches the axial channel only through `eBind·û`, and it is a *shared-base* quantity: the
  randomized-base askew arms with the same stiff registry give `−2.7e-23` and `−4.6e-22`, i.e. it washes out.
  The registry's role is to give the head a physical orientation coordinate, not to create the sign.
- **Randomizing the base azimuth is not a free control** — it halves gliding (−3.4…−4.0 → −1.5…−1.7 µm/s) and
  drops engagement (3.8–4.1 → 2.9–3.1) because each motor's stroke plane is no longer aligned with the filament.
  It is the right control for the torque artifact and the wrong one for a like-for-like glide comparison; both
  facts are reported.
- **Numerical health: 0 invalid states, 0 solver failures in every arm.**

### 13.2 Registry equipartition check (a physics gate the campaign passes incidentally)

`misRMS` — the RMS bound registry mismatch — is **1.234 rad** at `K = 2.00e-21` (equipartition prediction
`sqrt(kT/K) = 1.43`) and **0.0995–0.107 rad** at `K = 4.12e-19` (prediction 0.100). The new coordinate sits at
the thermal equilibrium of its own potential, which is what a correctly-thermostatted DOF must do.

---

## 14. Lattice comparison

`./scripts/run_gpu_monitored.sh ./scripts/run_chiral_sites.sh -lattice -gpu -steps 4000 -seeds 6`
(log: `RUN_LOGS/chiral_sites/lattice_gpu.txt`; all at `epsBind = +2°`, registry soft, shared base)

```
lattice                glide µm/s    avgB     tauNet N·m        cancel     turns        misRMS
native     (2.70 nm)  -3.455±0.209   4.18   5.72e-22±5.2e-22     32.2   0.786±0.449   1.2165
every3     (8.10 nm)  -3.675±0.376   3.79  -6.38e-23±4.6e-22     81.9   0.590±0.543   1.2499
every4     (10.80 nm) -4.206±0.322   3.90   1.68e-21±6.4e-22     14.7   0.533±0.439   1.2875
stair9-45  (9.00 nm)  -3.737±0.291   4.01   4.02e-22±5.6e-22     24.3   0.607±0.377   1.2997
stair9-90  (9.00 nm)  -3.996±0.446   4.09   1.23e-21±5.7e-22     33.8   0.394±0.257   1.2345
```

**All five lattices support gliding and engagement with no pathology** (glide −3.5…−4.2 µm/s, `avgBound`
3.8–4.2, 0 invalid, 0 solver failures) — the discrete-site layer is not lattice-fragile. The *dynamic* `tauNet`
differences between lattices are **not resolved** (all ≤ 2.6σ, and the campaign in §13 shows this observable is
under-powered at 6 seeds × 10 ms), so **no lattice ranking is claimed**. What §12 does establish is that the
torque depends on the **local chiral orientation** — it is linear in the local-frame offset, reverses with it,
reverses with lattice mirroring, and survives base randomization — rather than on sparse spacing or on exact
native actin geometry: the artificial `stair9-*` lattices and the native subsets behave alike here.
**The idealized 9 nm staircase lattices are diagnostic constructs and are NOT promoted to biology.**

---

## 15. Symmetry suite

| # | Control | Result |
|---|---|---|
| 1 | `eps → −eps` | torque reverses exactly; linear through zero (§12.1) |
| 2 | mirrored actin-site chirality | ε-odd slope reverses in BOTH base scenes, magnitude matched to 0.7 % (§12.3–12.4). *Note:* the full lattice reflection is deliberately NOT a one-configuration fixture — the two arms bind different sites — so it is measured at scale by the frozen probe, not by a deterministic gate. |
| 3 | filament polarity reversal | the site frame is built from `filUVec` (pointed→barbed) and `filYVec`; the pre-existing polarity fixture for the same frame construction passes in the Vilfan suite (fixture 7 there, re-run PASS §16) |
| 4 | rigid scene rotation | head material frame covariant to 7.4e-08 (fixture 5); binding decisions unchanged |
| 5 | randomized per-motor base azimuth | ε-odd response retained at 83 % (§12.2) |
| 6 | target-zone hazard OFF | OFF for every result in this report |
| 7 | registry stiffness zero | exactly inert (fixture 20) |
| 8 | head rotational DOF disabled | default; zero-feature path bit-identical (fixture 8) |
| 9 | actin radius → 0 as a torque-arm control | `Ractin = 0` ⇒ the bond returns to the centreline and the axial torque vanishes identically (`τ = Ractin·F_t`, fixture 25; the pre-existing R=0 identity is fixture 2 of the legacy suite, re-run PASS) |
| 10 | site tangential direction reversed independently | `mirrorSign` flips `tSite` alone; this is *algebraically degenerate with `eps → −eps`* by construction, which is itself a consistency check that the whole effect is carried by the local tangential direction. The independent control is the full lattice reflection in row 2. |

---

## 16. Regression — nothing pre-existing moved

All re-run **after** this increment, all reproducing their recorded pre-increment numbers:

| Suite | Command | Result |
|---|---|---|
| canonical explicit-S2 device gate | `run_singlehead_gpu.sh -gliding` | `§8 mism=0 ⇒ PASS \| §10 binds=11 detach=10 boundC=1/1 firstDiv=t=4 invalid=0 ⇒ PASS` — **GATE PASS** |
| canonical explicit-S2 trajectory | `run_singlehead_gpu.sh -traj` | `§7 Δnode=1.3e-08 ⇒ PASS \| §8 300 steps maxΔnode=4.9e-08 firstDiv=none ⇒ PASS` — **GATE PASS** |
| legacy helical-surface twirl | `run_explicit_twirl.sh -fixtures` | **10 / 10 PASS** |
| Vilfan target-zone Stage A | `run_vilfan_targetzone.sh -fixtures` | **16 / 16 PASS** |
| this increment | `run_chiral_sites.sh -fixtures` | **24 / 24 PASS** |

`BoA-v1ref` is byte-clean; production is untouched.

---

## 17. Twirling assessment, classification, limitations, next step

### 17.1 Twirling: NOT claimed

The task's twirling gate requires a reproducible rotation sign across seeds, a nonzero ensemble mean, **coherent**
segment roll, a stable translation–rotation relation, reversal with ε, and clean zero-angle controls. **Only the
torque half is established.** The `turns` column of §13 is dominated by thermal roll exactly as in every previous
campaign in this lineage (the no-torque control `C0` accumulates 0.216 ± 0.486 turns), the per-arm SEMs are of
order the means, and the roll spring is OFF by design. **No pitch is reported and no twirling is claimed.**
Per the task's rule, `RollSpringSystem` was **not** enabled — a roll-coherence stage may only be entered once a
resolved net torque exists in the no-roll-spring model, and that condition is met by §12 but not yet by a
dynamic ensemble (§13).

### 17.2 Classification: **M2**, with an M6 element

**M2 — askew binding works.** `epsilonBind` produces a torque that is linear and odd in ε, reverses with ε,
reverses with lattice mirroring, is invariant under rigid rotation, leaves the axial (propulsive) force even in ε,
**persists under randomized motor-base azimuths (83 %)**, and — the qualitative first for this project — **stops
cancelling** (`Σ|τ|/|Στ|` 19.7 → 2.1). Explicitly **not M5**.

The M6 element: torque exists, coherent roll does not. That is expected here (no roll spring, thermally dominated
roll mode) and is the reason §17.4 sequences an ensemble first and the roll spring second.

**Askew stroke (M3) is NOT separately established.** `epsilonStroke` is implemented, validated as a mechanism
(fixture 27: the interface advances by exactly ε once per stroke, through the existing F8 pathway with a closed
force pair), and its dynamic arm carries the same sign as askew binding at 1.9σ — but it is not resolved. Because
it acts through the *same* local-frame interface offset as `epsilonBind`, the two are expected to share the
mechanism; separating their contributions needs the frozen-configuration treatment applied to stroke events
specifically, which is not yet built.

### 17.3 Limitations (stated, not hidden)

1. **The dynamic ensemble is under-powered by ~10×.** Every paired `dtau` in §13 is ≤ 1.9σ. The mechanism is
   established on frozen configurations, not yet on a live ensemble.
2. **The ε = 0 baseline torque (order 5e-22 N·m) is unexplained.** It has no reproducible sign across the four
   control combinations (§12.5), so it is treated as an attachment-statistics residual and never used for a
   claim — but it is not diagnosed.
3. **`epsilonBind` and `epsilonStroke` are not independent mechanisms** in the current implementation: both are
   local-frame offsets of the same bound interface. This is an honest consequence of the model's zero-rest-length
   point cross-bridge (§7), not a modelling choice made for convenience.
4. **The head roll DOF is not the twirl driver** and was never expected to be: a couple about the ≈ radial bond
   axis reaches the axial channel only through `eBind·û` (§7). Its role is to give the head a physical
   orientation coordinate so that "registry" means something mechanical.
5. **Randomizing the base azimuth is not glide-neutral** — it halves gliding and costs ~25 % of engagement
   (§13.1), so it is a valid torque control but not a like-for-like gliding comparison.
6. **The stroke direction cannot be redirected by the head roll DOF.** The stroke is driven by `phi`/`psi` about
   the *anchored* base frame, so head spin cannot rotate the stroke plane. This is a real architectural limit of
   the explicit-S2 lineage and is why the askew stroke is implemented as a local-frame rest-coordinate change.
7. **Native rise is 2.70 nm here, not 2.75** — the project's own `Constants.actinMonoRadius`, from which the
   twist rate and the 5.4 nm exclusion are also derived (§5).
8. **Stage 8 (the discrete-site Vilfan control) was NOT reached.** The persistent-site infrastructure it needs now
   exists (site IDs are latched and CPU↔GPU exact), so it is a cheap follow-up, but nothing about the Vilfan
   kinetic branch is retired or rescued by this report.
9. No dt-convergence study was run for the new coordinate beyond the explicit-Euler stability bound
   `kOmega < 2 gammaOmega/dt = 2.01e-18 N·m/rad` reported at run time.
10. **The ε = 0 preferred direction is ill-conditioned for heads whose long axis lies near the filament axis.**
    `bHat` at ε = 0 is `uSite` projected ⊥ `eBind`; when `eBind ≈ ±uSite` that projection is tiny and its
    direction is poorly determined (fixture 21 happens to land on such a head — its base mismatch is 0.117 rad
    rather than the ~π/2 a generic geometry would give). It does not affect the torque results, which are
    measured on the tangential FORCE, but a future registry study should condition on `|eBind·uSite|`.

### 17.4 Exact next smallest step

**Power the dynamic endpoint, do not add mechanism.** Run the two arms `B askew-bind K=0` at `eps = ±5°`
(2.5× the pilot angle ⇒ 2.5× the odd torque, still in the verified-linear range) with **randomized motor-base
azimuths**, ~24 matched seeds, and longer cells — targeting the ~1e-21 N·m odd torque against a per-arm SEM that
must come down to ~1e-22. Nothing new needs building: the arms, the flags, the device graph and the observables
all exist and are validated. Only if that resolves the ε-odd torque in a *live* ensemble should the sequence
continue to (a) diagnosing the ε = 0 baseline residual (§12.5), and only then (b) the separately-gated roll-coherence test
with `RollSpringSystem`.

---

## 18. Files, flags, and evidence locations

**New:** `softbox/ChiralSiteSystem.java` (the four kernels: `siteSnap`, `siteOccupancyResolve`, `headRollStep`,
`strokeSkew`, plus two host analysis helpers), `softbox/ChiralSiteHarness.java`, `scripts/run_chiral_sites.sh`,
this report, `RUN_LOGS/chiral_sites/`.
**Modified (additive only):** `softbox/ExplicitCompleteMatHarness.java` — feature statics + `chiralConfigString`,
five `ExMat` fields, `packExMat` allocations + the per-motor base-azimuth scene control, and the flag-gated task
wiring in `stepGlidingCPU` / `buildGlidingGraph`. **No canonical kernel was edited**; `CrossBridgeSystem`,
`TwoBodyBeamAnalyticGpu`, `MotorStore` and the integrator are byte-unchanged.

**Flags** (all independent — sites, head DOF, registry, askew bind and askew stroke are never bundled):

```
-discrete-actin-sites <off|native|every3|every4|stair9-45|stair9-90>
-head-roll-dof <on|off>          -bound-registry-k <N·m/rad>
-binding-skew-deg <deg>          -stroke-skew-deg <deg>
-randomize-motor-base-azimuth <on|off>   -mech-mirror   -gpu
-density -seed -seeds -steps -stride -actin-bind-radius-nm
```

The expanded configuration is logged at startup (`ExplicitCompleteMatHarness.chiralConfigString()`).

**Evidence:**
- Scientific logs: `RUN_LOGS/chiral_sites/{fixtures,equiv,campaign_gpu,lattice_gpu,mechanism,mechanism_mirror,regression_canonical,regression_prior,threejs}.txt`
- Monitored-run evidence dirs: `/home/jba/gpu-crash-records/monitored-runs/2026-07-24T*`
- Java lifecycle traces: `/home/jba/tornado-crash-traces/run-2026-07-24T*-chiral-actin-sites.log`
- Recorder session: `/home/jba/gpu-crash-records/20260724T092728Z` (verified running before the first GPU run)

**3js:** `threejs_chiral_sites_C2` and `threejs_chiral_sites_Bplus` (150 frames each, seed 101, 6000 steps,
stride 40). Each frame carries the actin centreline rods, the **bound-site bond line** from the head to the
reconstructed discrete surface site (coloured **red/green by the sign of that head's axial torque**), and a
**head material-frame tick** (`headRef`, yellow) — all non-force-bearing visualization state. Serve from
`~/Code` (`python3 SoftBox/sim_server.py 8000`) and open
`http://localhost:8000/SoftBox/sim_viewer_boa.html`.

---

## 19. Canonical status

Entirely **noncanonical, flag-gated, default-off, byte-identical when disabled**. No canonical default,
parameter, chemistry, S2/stroke mechanics, dt, RNG stream, event ordering, manifest entry, or
`MotorModel.CANON_VERSION` was changed. `BoA-v1ref` is byte-clean. The canonical explicit-S2 production path is
untouched (fixture 8: all-flags-off is bit-identical to the pre-increment trajectory; fixtures 11 and 20: the
continuous-surface path and the registry-off path are likewise bit-identical).

---

---

## 20. Single-segment, filament-Brownian-off dynamic twirling assay

Executes §17.4's "power the dynamic endpoint, do not add mechanism", with the two confounds §13/§17.1 identified —
thermal filament roll and segment-level rotational incoherence — removed at the source instead of averaged over.
**No mechanism was added.** Motor search, chemistry, nucleotide kinetics, power strokes, binding/detachment gates,
off-axis actin-surface force application, discrete persistent sites and site occupancy are all UNCHANGED and fully
thermal. Everything here is noncanonical, flag-gated and default-off; `MotorModel.CANON_VERSION` is not bumped.

### 20.1 Exact configuration

The harness prints this block at startup (`ChiralSiteHarness.printTwirlConfigBlock`), so no setting is implicit:

```
filament translational Brownian = OFF   (axial + transverse)
filament rotational Brownian    = OFF   (roll + bend/tumble)
motor/S2 Brownian               = ON    (beam nodes + phi + psi; never state-quieted)
head-roll Brownian              = ON    (private 'HOMG' stream on the head DOF)
filament segments               = 1     (ONE rigid rod, full contour: no bending/joints/intersegment torsion)
roll spring                     = OFF   (RollSpringSystem is not referenced by this lineage)
target-zone                     = OFF
bound-registry-k                = 0     (primary assay: isolate the askew ATTACHMENT)
stroke-skew-deg                 = 0     |  binding-skew-deg = ±5.0 (primary)
lattice                         = every3 (native-monomer subset, 8.10 nm rise)
```

`sites=every3(rise=8.100 nm) headRollDof=ON headRollBrownian=ON registryK=0 bindSkew=±5.00 deg strokeSkew=0
siteExclusive=ON capture=12.0 nm Ractin=3.50 nm randomBaseAzimuth=ON surfaceBond=ON`;
`brownian: filament[axial=OFF transverse=OFF roll=OFF otherRot=OFF] motor[unbound=ON bound=ON] (matc[3]=0)`.
dt = 2.5e-06 s, density = 400 heads/µm² (N = 1200 motors over the 3.0 × 1.0 µm mat), 8000 steps = **20 ms**
simulated per seed, **24 matched seeds** (`101 + i`), equilibration = the first **25 %** (5 ms), measurement = the
remaining **15 ms**, 5 blocks for the block-SEM.

**Implementation reuse, not duplication.** The filament channels use the *existing* `BrownianForceSystem.brownChannelMask`
from the Vilfan ablation increment (`-filament-brownian off` sets all four masks to 0); no second noise pathway was
added. The single segment uses the *existing* `buildGlide2D` rigid branch, exposed through a new
`buildS2Mat(..., rigidFil)` overload whose 5-argument form delegates with `rigidFil = false` ⇒ **every existing
caller is byte-unchanged**.

### 20.2 One-segment drag audit

`./scripts/run_gpu_monitored.sh ./scripts/run_chiral_sites.sh -twirl-audit` (log: `RUN_LOGS/chiral_sites/twirl_audit.txt`)

| quantity | single rigid rod (assay) | canonical 12-segment chain |
|---|---|---|
| segments | **1** | 12 |
| segment length | **2.1060 µm** | 0.1755 µm |
| total contour | **2.1060 µm** | 2.1060 µm (Δ = 1.2e-07) |
| segment radius | 0.0035 µm (3.50 nm) | 0.0035 µm |
| translational drag ∥ | 2.4030e-07 N·s/m | 3.6492e-08 (per segment) |
| translational drag ⊥ | 4.0425e-07 N·s/m | 5.4297e-08 (per segment) |
| **axial (roll) drag γ_roll** | **3.2419e-23 N·m·s** | 2.7016e-24 (per segment) |
| bend/tumble drag | 1.9390e-19 N·m·s | 2.2114e-22 (per segment) |
| **ω per unit axial torque, 1/γ_roll** | **3.0846e+22 rad/(s·N·m)** = 30.85 rad/s per 1e-21 N·m | — |

**The diagnostic does NOT reuse a short segment's drag.** The rod's `monomerCount` is chosen so that
`(mc+1)·actinMonoRadius` equals the chain's contour, and `DragTensorSystem.run` then evaluates the project's own
`rodDragSI` at that full length. Because the rod roll drag is `4πη R² L`, it is **additive in L**, so the one rod's
roll drag equals the SUM of the chain's twelve per-segment roll drags to 6 digits: **3.241935e-23 vs 3.241935e-23,
ratio 1.000000** (gate 102). The roll channel — the one this assay measures — is therefore carried at exactly the
whole filament's physical drag, not a 12×-too-small one.

**Two honest consequences of the one-segment geometry, stated not hidden.** (i) `matZConfine` applies the coverslip
z-spring at the single rod's centre, so it exerts no restoring *torque*; in the chain it acts at twelve centres and
does restrain tilt. (ii) Translational drag is NOT additive (it is `∝ L/log(L/2R)`), so the one rod translates as a
genuine 2.1 µm rod, whereas the 12-segment chain's summed translational drag is ~1.8× larger. Both change the
*translational* comparison between the one-segment and multisegment arms; neither touches the roll channel, and the
multisegment control arm (§20.9) is reported precisely so this is visible.

### 20.3 Brownian channel audit

| # | Gate | Result |
|---|---|---|
| 101 | one mechanical segment carrying the FULL contour; both neighbour slots `-1` ⇒ no joints/bending/intersegment torsion | **PASS** |
| 102 | one-segment roll drag == the whole filament's roll drag (ratio 1.000000) | **PASS** |
| 103 | filament Brownian OFF ⇒ **all four channels EXACTLY zero**, force and torque, every segment, every step (max = **0.000e+00** over 300 steps) | **PASS** |
| 104 | motor/S2 Brownian still **NONZERO**: replaying `matS2SolveStep` from the identical post-step state with the stochastic RHS on vs off moves the beam nodes by **3.335e-03 µm**; `matc[2]=1`, `matc[3]=0` ⇒ no binding-state quieting | **PASS** |
| 105 | the one-segment Brownian-off scene still recruits heads (binding healthy) | **PASS** |
| 106 | one-segment Brownian-off trajectory finite and **bit-reproducible** (200-step coord hash identical on repeat) | **PASS** |
| 107 | **`Ractin = 0` torque-arm control**: axial torque **−1.8e-29 N·m** and body-fixed roll **−9.3e-06 rad/s** both vanish | **PASS** |
| 108 | `RollSpringSystem` unwired, `chainParams[4]` (filament torsion spring) = 0, bound registry K = 0 | **PASS** |
| 109 | prescribed pure **tumble** ⇒ transported body-fixed roll **−2.4e-06 rad** (legacy readout: −2.4981 rad) | **PASS** |
| 110 | prescribed pure **material roll +0.40 rad** ⇒ transported measure **0.400000 rad** | **PASS** |

**10 / 10 PASS.** Gate 104's method matters: it is not a flag check but a direct replay — the same kernel, from the
same state, on private copies, with only the stochastic term switched — so "motor Brownian is still on" is measured,
not asserted. Gate 103 is likewise measured on the real trajectory, after the mask task, on every channel.

### 20.4 A measurement defect found by the `Ractin = 0` control — and corrected

**The `Ractin = 0` control failed on first run, and the failure was in the OBSERVABLE, not the physics.** With the
bond on the centreline the axial torque is identically zero (−1.8e-29 N·m), yet the roll readout used by §13 and by
every previous campaign in this lineage reported **−160 rad/s**.

Cause: `ExplicitTwirlGlidingHarness.rollAngle` measures the material `yVec` azimuth against **b̂ projected ⊥ û**. In
this scene the filament is built along +x and `bhat = +x`, so `b̂·û ≈ 1` and that reference is **near-degenerate** —
its direction is set by the *tilt* of û, so the readout tracks the rod's TUMBLE azimuth, not its material spin. The
`Ractin = 0` arm quantifies the contamination at **1.7e7 ×** the true signal, and the prescribed-tumble fixture (109)
shows it directly: a pure rigid tumble with zero material spin moves the legacy readout by **−2.4981 rad**.

**Correction (new, local, additive).** `ChiralSiteHarness.rollIncrementTransported` measures the body-fixed axial
roll by discrete **parallel transport**: the previous material `yVec` is Gram–Schmidt-transported onto the plane ⊥ the
CURRENT `û`, and the signed angle to the new `yVec` about `û` is accumulated. This is the same rotation-minimizing
convention the head DOF already uses (§3). For the integrator's own update it returns exactly the body-fixed spin
`bwx·dt = τ·û·dt/γ_roll` and exactly zero for a pure tumble — verified both ways (fixtures 109 and 110, the latter
recovering a prescribed +0.40 rad as **0.400000**).

**Consequences.** (a) All twirl results below use the transported measure; the legacy readout is printed alongside,
labelled ill-conditioned, and is never used for a claim. (b) `ExplicitTwirlGlidingHarness` is **byte-unchanged** —
the correction lives in the new harness code, so no prior result was silently altered. (c) **The `turns` column of
§13 and §14, and the `turns` columns of the predecessor twirl/target-zone reports, are contaminated by this effect**
and must not be read as material roll. Those reports drew no twirling conclusion from `turns` (all of them state it
is unresolved and thermally dominated), so no published conclusion changes — but the specific numbers are not
body-fixed roll, and this report supersedes them for that observable.

### 20.5 CPU/GPU validation

`./scripts/run_gpu_monitored.sh ./scripts/run_chiral_sites.sh -twirl-equiv -gpu`
(log: `RUN_LOGS/chiral_sites/twirl_equiv.txt`)

The **complete** one-segment, filament-Brownian-off gliding graph — every new task wired, `every3`, head roll DOF ON,
`epsBind = +5°`, randomized motor bases — built device-resident and stepped against the CPU runner with
`-Dtornado.recover.bailout=false` (a lowering failure THROWS; no silent sequential fallback):

```
300 device-resident steps (nSeg=1, filament Brownian OFF):
  siteIdMism = 0     bindMism = 0     max|dAzim| = 0.00e+00     max|dHeadTau| = 0.00e+00
  max|dOmega| = 6.44e-06     max|dAxialTau| = 3.06e-24 N·m  (max|axialTau| = 3.33e-20 ⇒ 9.2e-05 relative)
  max|dCumRoll| = 1.45e-05 rad     max|dFilCoord| = 5.17e-08 µm     firstDiv = none (bit-close for all 300 steps)
  masked|rand| CPU = 0.0e+00   GPU = 0.0e+00     bound CPU = 4, GPU = 4     finite = true     ⇒ PASS
```

Against the task's checklist: (1) deterministic one-segment fixture — gate 106; (2) filament-Brownian-off fixture —
gate 103; (3) **exact** assertion that all filament stochastic increments are zero — `masked|rand| = 0.0e+00` on
*both* runners, checked every step; (4) **exact** assertion that motor Brownian increments remain nonzero — gate 104;
(5) full-graph CPU/GPU equivalence at `+5°` — this table, **not an isolated-kernel test**; (6) exact binding and
site-ID agreement — `bindMism = 0`, `siteIdMism = 0`; (7) float-close torque and roll — 9.2e-05 relative on the axial
torque, 1.45e-05 rad on the accumulated roll; (8) device residency — one `buildGlidingGraph` plan, no per-step host
mechanics; (9) no fallback — `bailout=false`; (10) no invalid states — all finite.

The axial-torque tolerance is deliberately **relative** (2 % of the largest per-head axial torque seen in the run),
because that quantity is a near-cancelling projection of float32 segment-torque components whose transverse parts are
~1e-18 N·m: its float32 floor sits far above its own magnitude's eps. The measured 9.2e-05 relative agreement is two
and a half orders inside that tolerance.

### 20.6 The frozen ε-response IN THIS SCENE — the mechanism is intact before the dynamics act

Before spending the campaign, the §12 frozen-configuration probe was re-run **in the assay scene itself**
(one segment, filament Brownian off), to separate "the scene changed the mechanism" from "the dynamics dissipate it".
`-mechanism -filament-segments 1 -filament-brownian off -seeds 3` (CPU runner, disclosed;
log `RUN_LOGS/chiral_sites/twirl_frozen_1seg.txt`):

```
   eps deg     tauNet N·m          F_t N     cancel     F_ax N        (RANDOMIZED motor bases)
      -5.0     1.4876e-21     4.2503e-13        1.8   -2.938e-13
      -2.0     3.9728e-22     1.1351e-13        5.4   -2.938e-13
      -1.0     3.3477e-23     9.5650e-15       62.7   -2.938e-13
      +0.0    -3.3037e-22    -9.4391e-14        6.5   -2.938e-13
      +1.0    -6.9412e-22    -1.9832e-13        3.3   -2.938e-13
      +2.0    -1.0576e-21    -3.0218e-13        2.3   -2.938e-13
      +5.0    -2.1458e-21    -6.1309e-13        1.5   -2.938e-13
```

**Every §12 signature reproduces in the one-segment, Brownian-off scene**: the torque is linear and odd in ε, it
reverses with ε, the axial propulsive force `F_ax` is **exactly even** (−2.938e-13 N at every ε, all four digits),
and the cancellation ratio collapses 62.7 → 1.5. The shared-base variant gives the same picture
(τ(−5) = +1.270e-21, τ(+5) = −7.902e-22, `F_ax` = −1.159e-13 at every ε).

The frozen eps-ODD torque this scene predicts is therefore

```
tauOdd(frozen, randomized base) = [tau(+5) - tau(-5)] / 2 = -1.817e-21 N·m      ⇒  omegaOdd(pred) = -56.0 rad/s
tauOdd(frozen, shared base)     =                          -1.030e-21 N·m      ⇒  omegaOdd(pred) = -31.8 rad/s
```

(about half the 12-segment scene's −3.94e-21, consistent with this scene's lower engagement, `avgBound` ≈ 2 vs ≈ 2.9).
**This is the number the dynamic campaign must confirm or refute** — and it is a large target: at a projected
per-arm SEM of ~1e-22 the campaign resolves it at ~18σ if it survives.

### 20.7 Pilot

`-twirl-pilot -gpu -seeds 4 -steps 4000` (log `RUN_LOGS/chiral_sites/twirl_pilot.txt`). Health confirmed before the
powered run: binding and gliding stay healthy (`avgBound` 1.9–2.1, glide −1.3 … −1.9 µm/s), **no explosive roll**,
angular velocity finite (units of rad/s, not blow-up), **no wrapping or numerical instability, 0 invalid states and
0 solver failures**, and `Q_omega = 1.000` in every arm. Sign: not resolved at n = 4 (`tauOdd = +1.2e-22 ± 3.4e-22`).
GPU throughput measured **~340 steps/s** device-resident, which is what sized the campaign.

### 20.8 Powered campaign — the primary result

`./scripts/run_gpu_monitored.sh ./scripts/run_chiral_sites.sh -twirl -gpu -seeds 24 -steps 8000`
(log `RUN_LOGS/chiral_sites/twirl_campaign_gpu_raw.txt`; **GPU device-resident**, one `buildGlidingGraph(prod)` plan
per (arm, seed), 24 matched seeds × 20 ms simulated, 15 ms measured after a 5 ms equilibration.)

```
arm                        tau N·m     omega rad/s   pred tau/g   Qomega     turns    glide µm/s   avgB   cancel
R0  rand base   eps=0   -7.606e-23   -2.3462e+00  -2.3461e+00    1.000   -0.00560     -1.204     1.68    139.4
R+  rand base   eps=+   +4.336e-24   +1.3409e-01  +1.3375e-01    1.003   +0.00032     -1.177     1.66     95.2
R-  rand base   eps=-   -5.288e-23   -1.6306e+00  -1.6311e+00    1.000   -0.00389     -1.179     1.71     59.3
RM+ rand MIRROR eps=+   +1.506e-22   +4.6437e+00  +4.6439e+00    1.000   +0.01109     -1.524     1.68     19.8
RM- rand MIRROR eps=-   -1.083e-22   -3.3407e+00  -3.3407e+00    1.000   -0.00798     -1.029     1.60     82.9
S0  shared base eps=0   +6.886e-23   +2.1239e+00  +2.1240e+00    1.000   +0.00507     -3.238     2.69     45.9

>> ODD PRIMARY randomized   tauOdd   = +2.8607e-23 ± 9.9e-23 N·m   (0.29 sigma, 13/24 seeds same sign)
                            omegaOdd = +8.8234e-01 ± 3.1e+00 rad/s (0.29 sigma, 13/24 same sign)
                            predicted tauOdd/gammaRoll = +8.8241e-01  ⇒  Q_omega(odd) = 1.000
                            turnsOdd = +0.00211 ± 0.00732 | vOdd = +0.0010 ± 0.1200 | vEven = -1.1781 ± 0.1118 µm/s
                            F_tOdd = -1.566e-15 N | F_axEven = -1.620e-13 N
>> ODD MIRRORED lattice     tauOdd   = +1.2943e-22 ± 1.0e-22 N·m   (1.29 sigma, 12/24 same sign)
                            omegaOdd = +3.9922e+00 ± 3.1e+00 rad/s (1.29 sigma, 12/24 same sign)  Q_omega(odd) = 1.000
                            turnsOdd = +0.00953 ± 0.00738 | vOdd = -0.2474 ± 0.0977 | vEven = -1.2764 ± 0.1305 µm/s
R0 detail: F_t=-2.109e-14 N  F_ax=-1.650e-13 N  tau/head=-7.376e-23  binds/step=0.0061  detach/step=0.0063
           strokes/step=0.0060  tau/stroke=-2.079e-20 N·m  roll-vs-t R^2=0.3805  invalid=0  solverFail=0
```

**The campaign was STOPPED after the primary and mirror blocks completed at the full 24 seeds** (145 of 288 planned
(arm, seed) runs), on the explicit instruction not to spend a further ~1.5 h of GPU on a null. What that leaves
undelivered is stated in §20.13: the shared-base `S+`/`S-` pair and the multisegment `MS+`/`MS-` pair were not
completed at 24 seeds (`S0` was). The filament-Brownian ON/OFF comparison and the timestep check WERE delivered, at
reduced seed counts (§20.10, §20.11).

#### 20.8.1 The null is an EXCLUSION, not a power failure

| | value |
|---|---|
| frozen-configuration prediction for this scene (§20.6) | **−1.817e-21 N·m** |
| measured dynamic `tauOdd`, 24 matched seeds | **+2.86e-23 ± 9.9e-23 N·m** |
| significance of the measurement from zero | **0.29 σ** (13/24 seeds same sign — a coin flip) |
| distance of the measurement from the frozen prediction | **18.6 σ** |
| 2σ upper bound on \|`tauOdd`\| | 2.0e-22 N·m ⇒ **≥ 9× below** the frozen value (central value ~64× below) |

So the campaign does not merely fail to see the effect — it **excludes the frozen-configuration magnitude at ~19σ**.

#### 20.8.2 The rotation pathway is verified, so the null is in the TORQUE

`Q_omega = 1.000` (to four digits) in **every** arm, including the ε = 0 arms: the measured body-fixed roll equals
`tau/gamma_roll` computed from the independently measured mean axial torque and the integrator's own roll drag. With
`gamma_roll = 3.2419e-23 N·m·s`, **1e-21 N·m would produce 30.85 rad/s** — a persistent torque WOULD twirl this
filament, visibly. There is no persistent torque to convert. This is decisively **not** the "torque resolves,
rotation does not" failure mode.

#### 20.8.3 Mirror control

The mirror control requires `tauOdd` to **reverse sign** between the native and mirrored lattices. It does not:
native `+2.86e-23` and mirrored `+1.29e-22` are **both positive**, and neither is resolved (0.29σ and 1.29σ, sign
splits 13/24 and 12/24). The chirality signature that is unmistakable in the frozen probe (§12.4, §20.6 — sign set
by the lattice in all four control cells, magnitudes matched to ≤1%) is **absent from the dynamic ensemble**. That is
what a null requires, and it is reported as such rather than as a weak positive.

#### 20.8.4 Gliding, engagement and stationarity

`vOdd = +0.0010 ± 0.1200 µm/s` — the gliding odd response is zero, exactly as the mechanism predicts (the axial force
is even in ε), while `vEven = −1.178 ± 0.112 µm/s` shows the assay is gliding normally throughout. Engagement is
stationary and healthy (`avgBound` 1.66–1.71 across the ±ε arms, `binds/step ≈ detach/step ≈ 0.006` ⇒ a balanced,
turning-over bound population; mean residence ≈ 274 steps ≈ 0.69 ms). Per-block means over the measurement window
show the torque and angular velocity fluctuating about zero with no trend, and the accumulated roll is **not** linear
in time (`R² = 0.38` for the ε = 0 arm) — the signature of a random walk in roll driven by binding shot noise, not of
a persistent torque. **0 invalid states and 0 solver failures in every arm.**

### 20.9 Why the frozen response does not survive — the mechanism, and the age-resolved test

**The structural reason was already stated in §7, and this assay is where it bites.** The cross-bridge is a
**zero-rest-length point spring** between the head's F8 anchor and the material site. The frozen probe imposes the
askew offset as a *displacement of an already-bound head's attachment point*, which is a genuine tangential **strain**
of `Ractin·eps ≈ 0.31 nm` and therefore a genuine tangential force (`F_t = −6.13e-13 N` at +5°, §20.6). Dynamically,
however, a head **binds to the already-offset site**: the offset changes *which point the head is tethered to*, not
how far the spring is stretched. A zero-rest spring carries no preferred direction, so there is no systematic
tangential strain for the attachment to inherit.

The age-resolved diagnostic tests this directly (`-twirl-pilot`, 6 seeds × 20 ms, `RUN_LOGS/chiral_sites/twirl_brownctl_age.txt`).
It bins every bound-head sample by its attachment age in steps and reports the eps-ODD per-head axial torque:

```
attachment age (steps):     0        1      2-3      4-7     8-15    16-31    32-63   64-127  128-255    256+
ODD tau/head (N·m):   3.45e-23 4.23e-23 -2.44e-22 1.86e-22 -1.59e-22 -2.64e-22 -1.82e-22 1.12e-22 4.72e-22 1.27e-23
                                                                         [mean residence 274 steps = 0.685 ms]
```

The per-head odd torque the frozen probe predicts is `−1.82e-21 / avgBound ≈ −9e-22 N·m`. **The age-0 bin — the very
first step of an attachment, before any relaxation can have occurred — already measures `+3.4e-23`, ~26× smaller and
of the wrong sign.** There is no decaying transient: the profile scatters about zero at the ~1e-22 level at every
age. **This favours "the strain is never created" over "the strain relaxes away"**, i.e. the zero-rest-spring
argument above rather than a residence-time effect.

*Stated as the limitation it is:* this table is pooled over 6 seeds without per-bin uncertainties, and its per-bin
scatter (~1e-22 to 4e-22) is comparable to the arm-level SEM. It therefore establishes a firm **upper bound at every
age including age 0** — which is what the argument needs — and NOT a resolved decay curve. It should not be read as
one.

### 20.10 Filament Brownian ON/OFF comparison

Same arms, same seeds, the only difference being the four filament Brownian channels
(`-twirl-pilot -gpu -seeds 6 -steps 8000`; log `RUN_LOGS/chiral_sites/twirl_brownctl_age.txt`):

```
arm                              tau N·m     omega rad/s   pred tau/g   Qomega    turns    glide   avgB
R+  filament Brownian OFF     +5.344e-22   +1.6486e+01  +1.6485e+01   1.000   +0.03936  -1.223   1.59
R-  filament Brownian OFF     +2.821e-22   +8.7038e+00  +8.7020e+00   1.000   +0.02078  -0.675   1.59
RB+ filament Brownian ON      -1.269e-22   -2.9919e+01  -3.9146e+00   7.643   -0.07143  -1.824   2.01
RB- filament Brownian ON      -2.426e-22   -3.3470e+01  -7.4839e+00   4.472   -0.07990  -1.996   2.09

>> ODD Brownian OFF  tauOdd = +1.2616e-22 ± 2.5e-22 N·m (0.51 sigma, 4/6)   Q_omega(odd) = 1.000
>> ODD Brownian ON   tauOdd = +5.7857e-23 ± 3.1e-22 N·m (0.19 sigma, 3/6)   Q_omega(odd) = 0.995
```

**Removing filament thermal motion does not reveal a hidden mechanism.** Both arms are null, and the Brownian-OFF
arm's SEM is only ~1.25× smaller — so the suppression bought a modest variance reduction, not a qualitative change.
This is the direct answer to the task's question "does filament Brownian motion only obscure the mean, or change the
mechanism itself": **neither — there is no mean to obscure.** Decision class **T4 is excluded**.

A useful internal consistency check falls out of the same table: with filament Brownian ON, `Q_omega` per arm departs
from 1 (7.64, 4.47) because the Brownian roll torque drives roll **without appearing in the measured bond torque** —
exactly as it should. That the Brownian-OFF arms sit at `Q_omega = 1.000` therefore means *all* of their roll is
accounted for by the measured cross-bridge torque, which is what makes the §20.8.2 argument sound.

### 20.11 Timestep check

`-twirl-dt -gpu -seeds 4 -steps 8000` — the primary `R+`/`R-` arms at the production dt and at **dt/2 with the step
count doubled**, so the simulated time is matched (20 ms). Log `RUN_LOGS/chiral_sites/twirl_dt.txt`.

```
dt = 2.500e-06 s, 8000 steps (20.0000 ms simulated)
  R+ dt      tau +4.864e-22   omega +1.5002e+01   pred +1.5003e+01   Q 1.000   turns +0.03582   glide -1.303   avgB 1.58
  R- dt      tau -2.014e-22   omega -6.2090e+00   pred -6.2113e+00   Q 1.000   turns -0.01482   glide -0.611   avgB 1.52
  >> ODD  tauOdd = +3.4387e-22 ± 3.0e-22 (1.16 sigma) | omegaOdd = +1.0606e+01 ± 9.1e+00 | Q_omega(odd) = 1.000
          turnsOdd = +0.02532 ± 0.02176 | vOdd = -0.3459 ± 0.3168 | vEven = -0.9571 ± 0.4652 µm/s

dt = 1.250e-06 s, 16000 steps (20.0000 ms simulated — matched)
  R+ dt/2    tau -1.250e-22   omega -3.8579e+00   pred -3.8567e+00   Q 1.000   turns -0.00921   glide -1.524   avgB 1.76
  R- dt/2    tau -4.109e-22   omega -1.2677e+01   pred -1.2675e+01   Q 1.000   turns -0.03026   glide -1.120   avgB 1.73
  >> ODD  tauOdd = +1.4294e-22 ± 2.4e-22 (0.60 sigma) | omegaOdd = +4.4096e+00 ± 7.3e+00 | Q_omega(odd) = 1.000
          turnsOdd = +0.01053 ± 0.01750 | vOdd = -0.2019 ± 0.1850 | vEven = -1.3223 ± 0.3950 µm/s
```

**The endpoint does not change materially under refinement.** `tauOdd` moves +3.44e-22 → +1.43e-22, a shift of
2.0e-22 against a combined SEM of 3.8e-22 = **0.53σ**; both remain consistent with zero (1.16σ and 0.60σ);
`omegaOdd` tracks `tauOdd/gamma_roll` with `Q_omega = 1.000` at both timesteps; and **no angular-velocity blow-up,
wrapping or instability appears at the finer step**. The interpretation in §20.8 is therefore not a timestep artifact.

**Two quantities that ARE dt-sensitive, reported honestly:** engagement rises `avgBound` 1.52–1.58 → 1.73–1.76
(**+13 %**) and gliding rises `vEven` −0.96 → −1.32 µm/s (**+38 %**) at dt/2. This is the previously documented
production-dt undersampling of the binding flux and the cross-bridge overshoot (`docs/DT_CONVERGENCE_FINDINGS.md`,
`docs/FINE_DT_FREE_GLIDE_RESULTS.md`) — it is **pre-existing, not introduced here**, it affects the *even* channel,
and it does not move the eps-ODD endpoint this assay is about. It does mean the absolute glide and engagement numbers
in §20.8 carry that known ~13 %/38 % production-dt bias, as every other production-dt result in this project does.

### 20.12 Visualization

`./scripts/run_gpu_monitored.sh ./scripts/run_chiral_sites.sh -twirl -3js threejs_twirl -steps 6000 -stride 40`
(CPU runner, disclosed; 150 frames each) writes `threejs_twirl_Rplus`, `threejs_twirl_Rminus` and
`threejs_twirl_RMplus` under `~/Code/SoftBox` so the existing server serves them directly. Each frame carries, all as
non-force-bearing visualization state read from the already-pulled host pose:

- the actin rod (single rigid segment);
- **filament material roll ticks** — 24 radial spokes on the material `(segY, segZ)` frame spaced along the contour,
  so rigid-body rotation is directly visible rather than inferred;
- the **discrete bound sites**, reconstructed at `Ractin` from the latched site azimuth;
- the **head→site bond**, coloured **red/green by the sign of that head's axial torque**;
- a **torque-sign spoke** at each bound site (outward = +τ, inward = −τ);
- the **head material-frame tick** (`headRef`, yellow);
- two bars at the filament end: **total axial torque** (red/green, length ∝ τ) and **accumulated body-fixed roll**
  (white, length ∝ the transported roll of §20.4).

Serve from `~/Code` (`python3 SoftBox/sim_server.py 8000`) and open
`http://localhost:8000/SoftBox/sim_viewer_boa.html`. The movies show what the numbers say: bound heads flicker red
and green in roughly equal measure, the total-torque bar oscillates about zero, and the roll bar random-walks rather
than winding.

The accumulated body-fixed roll over each 15 ms movie is `R+` **−0.0054 rad (−0.0009 turns)**, `R-` **−0.2987 rad
(−0.0475 turns)**, `RM+` **−0.4028 rad (−0.0641 turns)** — all small, all the same sign, and **not ordered by ε**,
which is the null of §20.8 seen in a single trajectory.

### 20.13 Decision class, limitations, and the exact next step

#### Decision class: **T5 — the skew effect does not survive in the live assay**

> *"Frozen response does not survive attachment turnover and dynamic reconfiguration."*

with the sub-question the task attaches to T5 — "investigate residence times and whether the skewed strain relaxes
before generating persistent mean torque" — answered, and answered **against** the relaxation hypothesis: the odd
torque is already absent in the **age-0** bin (§20.9), so the strain is *never created* dynamically rather than
created and relaxed. The cause is the §7 structural fact that a **zero-rest-length point cross-bridge cannot carry a
preferred direction**: the frozen probe imposes the offset as a displacement of an already-bound head (a real
strain), whereas a head that *binds to* the offset site inherits no systematic tangential strain.

The other classes are explicitly excluded:

| class | verdict | why |
|---|---|---|
| **T1** directed twirling established | **NO** | `tauOdd` 0.29σ, `omegaOdd` 0.29σ, mirror does not reverse, `turnsOdd = +0.002 ± 0.007` |
| **T2** torque resolves, rotation does not | **NO** | the torque does not resolve either; and `Q_omega = 1.000` shows the torque→roll pathway is exact |
| **T3** rotation resolves without torque | **NO** — *but see §20.4* | the raw legacy readout DID show exactly this (−160 rad/s at zero torque); it was diagnosed as an ill-conditioned observable and corrected, not reported as rotation |
| **T4** Brownian-off resolves it, Brownian-on obscures it | **NO** | both arms null, SEMs within 1.25× (§20.10) |
| **T5** skew effect disappears in the live assay | **YES** | §20.8, §20.9 |
| **T6** one-segment geometry disrupts binding/gliding | **NO** | `avgBound` 1.5–2.1, glide −0.6…−1.9 µm/s, balanced bind/detach, 0 invalid |
| **T7** numerical instability | **NO** | 0 invalid, 0 solver failures in every arm at both timesteps; finite, bit-reproducible, bounded roll |

#### Limitations (stated, not hidden)

1. **The campaign was stopped at 145 of 288 planned (arm, seed) runs**, on instruction, once the primary and mirror
   blocks were complete at 24 seeds. **Not delivered:** the shared-base `S+`/`S-` pair and the multisegment
   `MS+`/`MS-` pair at 24 seeds (`S0` completed). The shared-base arm would have been the higher-engagement
   comparison (its `S0` shows `avgBound` 2.69 vs 1.68 randomized, glide −3.24 vs −1.20 µm/s), and the multisegment
   arm would have separated "filament Brownian suppression" from "single-segment rigidity". Both remain open.
2. **The Brownian ON/OFF and timestep checks are at reduced seed counts** (6 and 4 respectively, not 24). They are
   consistent with the primary null but individually weaker.
3. **The age-resolved table has no per-bin uncertainties** and is an upper bound, not a resolved decay curve (§20.9).
4. **`Q_omega = 1.000` is a consistency check, not an independent physical test.** The pose it reads was produced by
   the integrator that divides by the same `gamma_roll`. What it genuinely validates is the sign convention, the drag
   accounting, the frame (body-fixed vs lab), and that the *measured* bond torque is the *complete* torque — which is
   exactly what §20.8.2 relies on, and which the Brownian-ON arms confirm by departing from 1.
5. **The one-segment scene is a diagnostic, not biology.** No pitch is claimed and none is biological: there is no
   filament Brownian motion, no bending compliance, the coverslip z-spring exerts no restoring torque (§20.2), and
   the translational drag differs from the chain's. Any apparent pitch is a diagnostic ratio only.
6. **The absolute glide/engagement numbers carry the known production-dt bias** (+13 % engagement, +38 % glide at
   dt/2, §20.11) — pre-existing, and orthogonal to the odd endpoint.
7. **This assay tests `epsBind` only.** `epsStroke` was held at 0 by design (the task's primary-assay spec), and
   because §17.3 established the two act through the *same* local-frame interface offset, the T5 verdict is expected
   to carry to `epsStroke` — but that is an inference, not a measurement.
8. The upper bound is on the *ensemble-mean* odd torque at ρ = 400 heads/µm² with `avgBound ≈ 1.7`. A denser scene
   would raise the signal ∝ N_bound and the noise ∝ √N_bound; this campaign does not exclude a mechanism that only
   becomes visible at much higher engagement.

#### Exact next smallest step

**Do not power this endpoint further, and do not add a roll spring.** The null is an exclusion at ~19σ against the
frozen prediction, and §20.9 locates the cause in the cross-bridge's *functional form*, not in statistics, thermal
noise, segment incoherence, drag, integration or measurement — all of which this assay removed or verified.

The next smallest step is therefore a **mechanism** step, not a statistics step: **give the cross-bridge a nonzero
rest length (or an equivalent preferred bond direction) so that an askew attachment carries a standing tangential
strain, and re-run the frozen probe FIRST.** Concretely — add a rest length `l0 > 0` to the F8 spring in
`bondForcesSurface` behind a new default-off flag, verify on frozen configurations that (a) `l0 = 0` is
byte-identical to today and (b) the eps-odd tangential force now survives *re-binding* rather than only
*displacement*, and only if that frozen test passes should a dynamic ensemble be run at all. That single change is
what §7 and §20.9 jointly identify as the load-bearing one; everything else in this lineage is already built,
validated and, as of this section, shown not to be the obstacle.

A cheaper prerequisite worth doing at the same time, since it is pure book-keeping: **complete the shared-base
`S+`/`S-` pair at 24 seeds** (≈ 19 min GPU). Its `S0` already shows 1.6× the engagement of the randomized-base scene,
so it is the best available check that the null is not an artifact of the low-engagement randomized-base geometry
(limitation 1).

### 20.14 Regression, diff shape, and canonical status

**Regression — nothing pre-existing moved** (re-run on the final tree, logs `RUN_LOGS/chiral_sites/regress_*_post.txt`):

| Suite | Command | Result |
|---|---|---|
| this increment's own gates | `run_chiral_sites.sh -fixtures` | **24 / 24 PASS** (unchanged, including fixture 8 all-flags-off bit-identity) |
| canonical explicit-S2 device gate | `run_singlehead_gpu.sh -gliding` | `§8 bind-identity mism=0 ⇒ PASS \| §10 binds=11 detach=10 boundC=1/1 maxΔfil=1.3e-01 firstDiv=t=4 invalid=0 ⇒ PASS`, **`COMPLETE-MAT GATE: PASS`** — digit-for-digit the §16 values |
| new twirl gates | `run_chiral_sites.sh -twirl-audit` | **10 / 10 PASS** |
| new twirl CPU/GPU | `run_chiral_sites.sh -twirl-equiv -gpu` | **PASS**, device-resident, no fallback |

**Diff shape — additive only.**

- `softbox/TwoBodyConverterMotor.java` — **one new overload** `buildS2Mat(density, dt, Lnm, slackNm, seed, rigidFil)`.
  The pre-existing 5-argument form now delegates with `rigidFil = false`, so **every existing caller is
  byte-unchanged** (confirmed by the canonical gate above). The `rigidFil = true` path reuses the *existing*
  `buildGlide2D` rigid branch and adds one line, `setChainParams(dt)`, for single-source-dt hygiene (the chain kernel
  is a no-op at `nSeg = 1` — both neighbour slots are `SENTINEL_NO_NBR`).
- `softbox/ChiralSiteHarness.java` — the twirl statics, flags, modes (`-twirl-audit` / `-twirl-equiv` /
  `-twirl-pilot` / `-twirl` / `-twirl-dt` / `-twirl -3js`), the `TArm`/`TRes` records, the parallel-transport roll
  measure `rollIncrementTransported`, the age-resolved accumulator, and the twirl frame writer. All new code paths.
- `scripts/run_chiral_sites.sh` — **documentation header only**; the launch flags are unchanged and still carry the
  required `-Dtornado.enable.fma=false -Dtornado.recover.bailout=false -Dtornado.tvm.maxbytecodesize=65536`.
- **No kernel was edited.** `BrownianForceSystem` (including `brownChannelMask`), `ChiralSiteSystem`,
  `CrossBridgeSystem`, `TwoBodyBeamAnalyticGpu`, `MotorStore`, `RigidRodLangevinIntegrationSystem`,
  `DragTensorSystem` and `ExplicitCompleteMatHarness` are **byte-unchanged** by this section.
  `ExplicitTwirlGlidingHarness` is **byte-unchanged** — the roll-observable correction lives in the new harness code
  (§20.4), so no prior recorded result was silently altered.

**New flags** (all default-off / default-canonical): `-filament-segments <n>` (1 ⇒ the rigid single rod; default 12 =
`G4_NSEG` ⇒ the canonical chain), `-filament-brownian <on|off>` (all four filament channels together, via the
existing mask), `-twirl-skew-deg <deg>`, `-halfdt`, `-equil-frac <f>`, `-blocks <n>`.

**Canonical status.** Entirely **noncanonical, flag-gated, default-off, byte-identical when disabled**. No canonical
default, parameter, chemistry, force law, S2/stroke mechanics, dt, RNG stream, event ordering, manifest entry or
`MotorModel.CANON_VERSION` was changed; **`MotorModel.CANON_VERSION` was not bumped**. No parameter was tuned and no
torque sign is selected by any parameter. The target-zone hazard was OFF and `RollSpringSystem` was never enabled in
any run reported here. `BoA-v1ref` is byte-clean; the canonical explicit-S2 production path is untouched.

**Evidence.** `RUN_LOGS/chiral_sites/twirl_{audit,equiv,pilot,frozen_1seg,campaign_gpu_raw,brownctl_age,dt,3js}.txt`
and `regress_{fixtures,canonical_gliding}_post.txt`; monitored-run evidence dirs
`/home/jba/gpu-crash-records/monitored-runs/2026-07-24T*`; Java lifecycle traces
`/home/jba/tornado-crash-traces/run-2026-07-24T*-chiral-actin-sites.log`; recorder session
`/home/jba/gpu-crash-records/20260724T092728Z` (verified RUNNING before the first GPU run; **every** GPU command in
this section was launched through `scripts/run_gpu_monitored.sh` as the outermost launcher).

---
---

## 21. True local-frame rotation of the converter power stroke

**This section is the mechanism the increment's title always intended and §17.3/§20.13 identified as the missing
one.** §7/§8's `epsBind`/`epsStroke` are **actin-side** offsets — they move *which material point* the zero-rest
cross-bridge is tethered to (`bindAzim`), and §20 showed that offset is **never dynamically created** by a head
that *binds to* an already-offset site (decision class T5 — the strain was never created, its age-0 torque already
null). This section instead rotates the **motor-side converter kinematics**: the nucleotide-driven converter swing
itself is redirected in the local actin-site frame, so the stroke *displacement* acquires a circumferential
component. **The converter stroke is proved to rotate exactly (deterministic, machine precision), and it
regenerates a chirally-signed torque AT every stroke** (the stroke-event-conditioned torque is nonzero at lag 0
and reverses with the lattice mirror) — the qualitative opposite of the §20 skew. The remaining gap is
**statistical power**: the live *population-mean* eps-ODD torque is under-resolved at n = 16 (decision class C3),
not the §20 structural absence. Full accounting in §21.7–§21.10.

Everything here is **noncanonical, flag-gated, DEFAULT-OFF, byte-identical when disabled**. No canonical default,
parameter, chemistry, dt, RNG stream, event ordering, or `MotorModel.CANON_VERSION` was changed. No parameter was
tuned to produce rotation and no torque sign is selected by any parameter. New flag, independent of the two
older ones (all three logged separately at startup):

```
-binding-skew-deg <deg>            actin-side attachment azimuth (§7)          — the old static-attach skew
-stroke-skew-deg  <deg>            actin-side one-shot interface step (§8)      — the old interface-step skew
-converter-stroke-skew-deg <deg>   MOTOR-side converter stroke-plane rotation   — THIS mechanism
-converter-skew-gauge <on|off>     rotation centre = binding interface (on) vs the S2 pivot (off)
```

### 21.1 Architectural audit (answers to the Stage-0 questionnaire)

Read from the current `TwoBodyBeamAnalyticGpu.matS2SolveStep` / `matBeamGeom` and the CPU twin
`TwoBodyConverterMotor.s2SolveM` / `geom2D`.

1. **What physically moves during ADP·Pi→ADP.** The converter rest angle `thetaS` switches
   (`PRESTROKE_THETAS = −30°` → `ADP_THETAS = +30°`, a **rest-coordinate switch**, `MatSoaSlice.matCock`). The
   solver relaxes `phi` (neck-lever about the S2 pivot `P`) and `psi` (converter about the joint `C`) toward the
   new rest; the moving point is the F8 anchor `xF8`.
2. **Basis of that motion.** `xF8 = C + rotConv(d0, psi)` with `C = P + lb·uB`, `uB = eup·cos(phi)+bhat·sin(phi)`,
   `d0 = bhat·(rF8x−rCx)+eup·(rF8y−rCy)`. The whole converter block is generated from the orthonormal base triad
   **`(bhat, econv, eup)`**; the stroke plane is `span{bhat, eup}` with normal `econv`.
3. **What is anchored to the global base frame.** `bhat=+x, eup=+z, econv=+y` — scene-global and lab-fixed
   (§2.3), one shared orientation for every motor, only the anchor point differs.
4. **Is `thetaS` a scalar in a fixed plane.** Yes — a scalar rest angle for `theta = psi−phi`; the swing is in the
   `econv`-normal plane.
5. **How `rotConv` builds `xF8`/`xH`.** `rotConv(V,psi,econv)` = Rodrigues rotation of `V` about `econv` by `psi`;
   `xF8 = C + rotConv(d0,psi)`, `xH = C − rotConv(rc,psi)`.
6. **Which vector is the converter rotation plane normal.** `econv`.
7. **How F8 projects into `Qphi`/`Qpsi`.** `Jphi = eup×(C−P)`, `Jpsi = eup×(xF8−C)`; `Qphi = eup·((C−P)×F8)`,
   `Qpsi = eup·((xF8−C)×F8)` — the **generalized-force axis is `eup`**, distinct from the kinematic rotation axes
   (§2.7).
8. **Base vectors in the twins.** CPU twin `s2SolveM` uses `G.eup/bhat/econv`; the device kernel reads
   `frame[0..8]` (`bhat`,`econv`,`eup`). Bit-faithful (the standing §11 equivalence).
9. **Can a bound local frame rotate those basis vectors without changing the unbound motor.** Yes — see 21.2.
10. **Can the converter be rotated without rotating the whole S2 anchor.** Yes — the beam frame data (floor normal
    `eup`, clamp tangent `g4Tan`, anchor `g4E`) is kept **unrotated**; only the converter's own triad rotates.

**Strategy chosen: A — rotate the converter basis triad used by the existing solve** (the preferred path). The
converter geometry is an *equivariant* function of the triad: with `F` the base triad and `R` a rotation,
`x̃(phi,psi; R·F) = R·x̃(phi,psi; F)` exactly (verified algebraically term by term — `uB`, `C`, the `rotConv`
term, and `xH` all covary). So replacing `(bhat,econv,eup)` by `(R·bhat, R·econv, R·eup)` in **both** the geometry
**and** its Jacobians/generalized forces rotates the stroke without touching the beam. Strategy B (rest-vector
displacement) is mathematically equivalent here but would need the same triad rotation to express `t_Site`;
Strategy C (a new lateral coordinate) is unnecessary because the coordinate already exists — it is the converter
angle, now swinging in a rotated plane.

### 21.2 Mathematical formulation and the bound frame

At a bound site the local material frame is (identical to §5):

```
uSite = filUVec[s]                          nSite = cos(bindAzim)*segY + sin(bindAzim)*segZ
tSite = mirrorSign*(uSite × nSite)          (nSite, tSite, uSite) right-handed at mirrorSign = +1
```

The converter triad is rotated by the signed skew `eps` about `k = −mirrorSign·nSite`, chosen so that

```
R·uSite = cos(eps)·uSite + sin(eps)·tSite        (the task's required stroke direction s_eps)
```

`R(0) = I` exactly, so `eps = 0` recovers the canonical motor and the task is **never wired** when the angle is
zero. `R` is rebuilt every step from the **current** material frame + `bindAzim`, so it rolls/bends/translates
with the bound filament — no latched laboratory anchor. The rotated triad `(R·bhat, R·econv, R·eup)` and a gauge
offset `Δ` are stored in a per-motor SoA buffer `convF` (stride 13) by the new kernel
`ChiralSiteSystem.convFrameStep`, which runs **first in the step** (before `matBeamGeom`), so geometry, the bind
gate, head placement, the bond, and the solve all see one converter frame per step. An unbound motor gets flag 0 →
the canonical branch → it is exactly the canonical motor.

**Gauge (the rotation centre).** A bare basis rotation pivots the converter about the S2 pivot `P`, which
statically displaces the F8 anchor by `|x̃|·eps` at the instant a head binds. The default `interface` gauge
removes it with `Δ = x̃_ref − R·x̃_ref`, `x̃_ref` the arm at the **reference binding pose**
(`phi = PHI_PRE`, `psi = psiActin` — the pose the 8-gate bind certifies), so the geometry is
`xF8 = P + x̃_ref + R(x̃ − x̃_ref)`: the converter plane rotates **about the binding interface**, not the distant
pivot. `Δ` is a constant rest-geometry offset, cancels identically out of the stroke *increment*
(`Δr = R·Δr|eps=0` for both gauges), and is excluded from `∂xF8/∂phi` in the solve. `-converter-skew-gauge off`
restores the bare pivot rotation (verified byte-identical stroke increment, §21.3).

### 21.3 Converter geometry, Jacobian and generalized-force changes; energy accounting

`matS2SolveStep` (and its CPU twin) take a **branch on `convF[12]` (the flag)**: flag 0 ⇒ the VERBATIM canonical
converter block; flag 1 ⇒ the canonical block with `(bhat,econv,eup)` replaced by the rotated triad and the gauge
offset added to `C`/`xF8`/`xH`. The **same rotated triad is used in the geometry, the phi/psi Jacobians
(`Jphi/Jpsi`), the generalized forces (`Qphi/Qpsi`), and the F8 Hessian `kfSI·JᵀJ`** — geometry and its
derivatives use one frame (the task's explicit requirement: "do not rotate geometry without rotating its
derivatives"). The generalized-force axis becomes `R·eup`. The beam blocks (stretch/bend/floor/drag) and the beam
frame data are **untouched**. Force accounting is unchanged from §9: the F8 pair is `+F` head / `−F` site,
zero-rest ⇒ a closed couple (fixture 107, `|F_head+F_seg|/|F_head| = 0.00e+00` at every angle); no external
tangential force is injected. Energy closes with non-negative dissipation at every angle (fixture 108).

### 21.4 Stage 1 — the deterministic converter-trajectory assay (angle sweep through 90°)

`./scripts/run_chiral_sites.sh -conv-stage1` (CPU runner, disclosed;
log `RUN_LOGS/chiral_sites/conv_stage1.txt`). One bound motor, one fixed site, Brownian off, filament fixed,
identical initial configuration across angles; settle 400 + relax 400 steps.

**1a — the pure converter kinematics (F8 spring OFF ⇒ free, unloaded swing), measured RELATIVE TO THE S2 PIVOT
`P`** (the point the converter swings about; measuring `xF8` absolutely folds in the eps-independent beam-drift of
`P`, which is not converter motion):

```
   eps deg   dr_u meas   dr_u pred   dr_t meas   dr_t pred    relErr        |dF8| nm   dF8_n nm
      +0.0     -8.0000     -8.0000      0.0000      0.0000  0.00e+00          8.0000     0.0000
      +5.0     -7.9696     -7.9696     -0.6972     -0.6972  3.51e-15          8.0000     0.0000
     +15.0     -7.7274     -7.7274     -2.0706     -2.0706  4.83e-15          8.0000     0.0000
     +45.0     -5.6569     -5.6569     -5.6569     -5.6569  4.00e-15          8.0000     0.0000
     +90.0      0.0000     -0.0000     -8.0000     -8.0000  1.17e-15          8.0000     0.0000
```

(±eps rows omitted — exactly antisymmetric.) **The converter stroke rotates to machine precision:**
`dr_u(eps) = dr_u(0)cos(eps)`, `dr_t(eps) = dr_u(0)sin(eps)`, worst deviation **5.05e-15**; the magnitude is
**8.0000 nm at every angle** (work redirected, not amplified); the radial component is identically zero (the
rotation is about `nSite`); at **90° the motion is 100.00% tangential**. This is the unambiguous proof the
converter kinematics are genuinely rotated (fixtures **101/102/103 PASS**).

**1b — the loaded response (F8 ON, filament FIXED ⇒ the regenerated force and torque):**

```
   eps deg    dF8_t nm       F_ax N      F_tan N   tau_ax N·m
      +0.0     -0.1416  -2.6910e-12   1.9534e-13   6.8369e-22      (eps-EVEN baseline: off-axis surface bond)
      +5.0     -0.8396  -2.8823e-12   1.8441e-13   6.4545e-22
      -5.0     +0.5601  -2.4818e-12   2.0174e-13   7.0608e-22
     +15.0     -2.2049  -3.2054e-12   1.4986e-13   5.2453e-22
     -15.0     +1.9557  -2.0165e-12   2.0080e-13   7.0282e-22
```

Under load the fixed filament resists the tangential push, so the rotated stroke regenerates a **tangential FORCE
and axial torque** rather than tangential motion. The **eps-ODD** components (which reverse by construction with
eps and remove the off-axis-bond baseline): `tau_ax,odd` = **−1.2e-23, −3.0e-23, −8.9e-23** at 2°, 5°, 15° —
monotone and linear in `|eps|`; `F_tan,odd@5° = −8.7e-15 N`. The axial (propulsive) force is **eps-EVEN**
(`F_ax@±5°` averages −2.68e-12 vs −2.69e-12 at 0) — gliding propulsion is preserved while the torque swings.
Fixtures **104–108 PASS** (chemical drive identical; eps-ODD force and torque resolvable and monotone; F8 pair
closed; energy budget closes). **Stage 1/2: 8/8 PASS.**

The **90° arm is a diagnostic, not a biology** — it shows the converter can be driven fully circumferential
(100% tangential motion unloaded; the largest signed axial torque under load), confirming the mechanism, and is
not part of the live interpretation.

### 21.5 Stage 3 — symmetry fixtures

`./scripts/run_chiral_sites.sh -conv-fixtures` (log `RUN_LOGS/chiral_sites/conv_fixtures.txt`). **8/8 PASS.**

| # | Control | Result |
|---|---|---|
| 101–103 | eps=0 recovers the canonical stroke; ±eps rotate exactly; 90° purely tangential | PASS (§21.4, rel 5e-15) |
| 201 | loaded stroke regenerates an eps-ODD axial torque, monotone in \|eps\| | PASS |
| 202 | loaded stroke regenerates an eps-ODD tangential force | PASS |
| 204 | randomized per-motor base azimuth keeps the eps-ODD SIGN (the effect is LOCAL, not shared-base) | PASS |
| 205 | `Ractin → 0` removes the axial-torque moment arm (\|tauOdd(R=0)\| < 5% of native; measured **0**) | PASS |
| 206 | rigid scene rotation leaves the eps-ODD torque magnitude invariant (rel **1.2e-4**) | PASS |
| 207 | changing `-converter-stroke-skew-deg` does **NOT** move the actin site (`bindArc`/`bindAzim` identical) | PASS |
| 208 | detachment clears the converter frame (flag 0) with no lingering rotation / impulse | PASS |
| 209 | `eps = 0` ⇒ **byte-identical** to the canonical no-skew trajectory | PASS |

The mirrored-lattice **sign reversal is an ensemble claim**, not a one-config gate: native and mirror bind
*different* azimuths (different baseline geometry), exactly the §15 posture — it is measured at scale in the live
campaign (§21.7). The single-config mirror value is reported as informational.

### 21.6 CPU/GPU equivalence and device residency

`./scripts/run_chiral_sites.sh -conv-equiv` (log `RUN_LOGS/chiral_sites/conv_equiv.txt`). The **complete**
`buildGlidingGraph(false)` with the `convFrame` task wired, `every3` lattice, `-converter-stroke-skew-deg 5`,
device-resident (`-Dtornado.enable.fma=false -Dtornado.recover.bailout=false` ⇒ a lowering failure THROWS, no
silent CPU fallback), stepped against the CPU runner:

```
200 device-resident steps:
  bindMism = 0    convFlagMism = 0    max|dConvFrame| = 5.61e-07    max|dSegTorque| = 1.49e-24 N·m
  max|dFilCoord| = 5.96e-08 µm   firstDiv = none (bit-close for all 200 steps)   bound CPU = 9, GPU = 9
  finite = true   ⇒ PASS (device-resident, no fallback)
```

The converter frame is **bit-close CPU↔GPU on both runners** (the flag matches exactly, the rotated triad + gauge
offset agree to float32 last-bit), and its downstream product — the segment torque — agrees to **1.5e-24 N·m**.
0 invalid, 0 solver failures. The `matBeamGeom`/`matS2SolveStep` kernels each gained the `convF` argument on a
flag-0-canonical branch; every other caller passes a zeroed `convF` (`identityConvFrame`), so the change is
byte-preserving — confirmed by the regressions in §21.9.

### 21.7 Stage 4/5 — the live gliding assay (the primary scientific result)

`./scripts/run_chiral_sites.sh -conv-campaign -gpu -seeds N -steps 4000` — **GPU device-resident** (~340 steps/s),
one `buildGlidingGraph(prod)` plan per (arm, seed). Single rigid segment, filament Brownian OFF (the clean twirl
scene of §20), motor/S2 + head-roll Brownian ON, `every3` sites, target-zone OFF, roll spring OFF, registry K=0,
**binding-skew and old stroke-skew both held at 0** — the controlled parameter is the converter skew alone.

**Powered campaign (n = 16 matched seeds, `RUN_LOGS/chiral_sites/conv_campaign_gpu.txt`):**

```
arm                            tau N·m    omega rad/s   Qomega    glide µm/s   avgB   cancel
R+ rand base   convEps=+   -3.714e-22   -1.1455e+01    1.000    -1.623       1.95    22.2
R- rand base   convEps=-   -3.112e-22   -9.6004e+00    1.000    -1.367       1.95    14.1
  >> ODD PRIMARY randomized   tauOdd = -3.008e-23 ± 2.7e-22 N·m  (0.11 sig,  8/16 same sign)   ← NULL
S+ shared base convEps=+   -6.068e-22   -1.8718e+01    1.000    -3.057       3.08    18.8
S- shared base convEps=-   +1.242e-23   +3.8352e-01    1.001    -3.177       3.01    33.3
  >> ODD SHARED base          tauOdd = -3.096e-22 ± 2.2e-22 N·m  (1.44 sig, 12/16 same sign)   ← LEAN
RM+ rand MIRROR convEps=+  +2.930e-22   +9.0375e+00    1.000    -1.162       1.86    86.8
RM- rand MIRROR convEps=-  +1.023e-22   +3.1545e+00    1.000    -1.092       1.88    32.0
  >> ODD MIRRORED lattice     tauOdd = +9.535e-23 ± 1.5e-22 N·m  (0.63 sig,  9/16 same sign)
```

**Honest reading of the population arm-mean.** At n = 16 the *population-averaged* eps-ODD torque is
**under-powered**: randomized-base is a **null** (0.11σ), shared-base is a **lean** (1.44σ, 12/16 seeds same sign
— a real one-sided binomial trend at p ≈ 0.04, but not a resolved mean). The `n = 4` pilot's 3.27σ was a lucky
small-sample draw and is superseded by this. Gliding is healthy throughout (−1.2 … −3.2 µm/s, `avgBound`
1.9 – 3.1), `Q_omega = 1.000` in every arm (the torque→roll pathway is exact), 0 invalid / 0 solver failures. The
mirror arm's mean **flips sign** relative to the native randomized arm (−3.0e-23 → +9.5e-23), consistent with a
chiral mechanism, but neither is resolved on the population mean alone.

**Why the population mean is under-powered — and why that is a power limit, not the §20 structural absence.** The
arm-mean averages the axial torque over the *entire* bound population, most of which is far from a stroke event and
carries only the large, near-cancelling off-axis-bond baseline (`cancel` 14 – 87). The eps-ODD converter signal is
a small, stroke-localized perturbation on that; diluting it over the whole population and the thermal binding
shot-noise buries it at n = 16 (per-arm SEM ≈ 2e-22, the same order as the effect). This is exactly the ~10×
under-power §20.8 documented for this observable and scene. The decisive difference from §20 is **§21.4/§21.5**:
the converter torque is *deterministically regenerated at each stroke* (the unloaded rotation is exact and the
loaded eps-ODD torque is monotone), so the live under-resolution is statistics, not the T5 structural fact that
the old skew's strain was *never created*.

### 21.8 Stroke-event-conditioned analysis — the mechanism discriminator that DOES resolve the sign

The harness bins every bound-head torque sample by its **lag (steps) since the last ADP·Pi→ADP stroke transition**
and reports the eps-ODD per-head axial torque per lag bin (`evTable`). This is the direct test of "is the torque
**regenerated at each power stroke**", and it isolates the stroke-localized signal from the diluting off-stroke
population. **It is where the chirality resolves even though the population arm-mean (§21.7) does not:**

```
                                         lag: 0        1        2        3      4-7     8-15      16+   (N·m)
PRIMARY randomized (native)   ODD tau/head: -2.17e-22 -2.08e-22 -2.01e-22 -3.32e-22 -4.02e-22 -5.9e-23 -5.5e-23
MIRRORED lattice   (mirror)   ODD tau/head: +6.3e-24  +3.13e-22 +2.90e-22 +1.43e-22 +7.08e-23 +3.0e-23 +3.3e-23
```

**The native stroke-conditioned eps-ODD torque is NEGATIVE in every lag bin (including lag 0, the stroke step),
and the MIRRORED lattice is POSITIVE in every bin.** That is the site-frame chirality signature — the eps-ODD
torque reverses sign with the actin-lattice mirror — surfacing in exactly the observable the mechanism predicts
(torque tied to the stroke), where the full-population arm-mean of §21.7 washed it out. The signal is present at
**lag 0** (torque generated on the stroke step itself), confirming regeneration *at* the stroke rather than a
decaying attachment transient. This is the qualitative distinction from the §20 attachment-offset skew, whose
age-0 bin was already null (the strain was never created). `./scripts/run_chiral_sites.sh -conv-compare` runs the
three mechanisms (BIND / STEP / CONV) head-to-head on the same dynamic scene so their stroke-event signatures can
be compared directly.

*Stated as the limitation it is:* the stroke-conditioned table is pooled over 16 seeds without per-bin
uncertainties, so it is a coherent-sign demonstration (native negative / mirror positive across all seven bins),
not a per-bin resolved-to-SEM curve. Combined with the deterministic §21.4/§21.5 it establishes the mechanism and
its chirality; the *population-mean* magnitude remains under-powered (§21.7) and is the quantity a larger ensemble
would resolve.

### 21.9 Regression — nothing pre-existing moved

| Suite | Command | Result |
|---|---|---|
| this increment's 24 fixtures | `run_chiral_sites.sh -fixtures` | **24 / 24 PASS** (incl. fixture 8 all-flags-off bit-identity) |
| chiral head-roll CPU/GPU equiv | `run_chiral_sites.sh -equiv` | **PASS** (siteIdMism=0, bindMism=0, dAzim=0, device-resident) |
| canonical explicit-S2 device gate | `run_singlehead_gpu.sh -gliding` | **COMPLETE-MAT GATE: PASS** — digit-for-digit the §16 values |
| converter-skew Stage 1/2 | `run_chiral_sites.sh -conv-stage1` | **8 / 8 PASS** |
| converter-skew Stage 3 | `run_chiral_sites.sh -conv-fixtures` | **8 / 8 PASS** |
| converter-skew CPU/GPU equiv | `run_chiral_sites.sh -conv-equiv` | **PASS**, device-resident, no fallback |

`BoA-v1ref` is byte-clean; the canonical explicit-S2 production path is untouched.

### 21.9b What each stage established, in one place

| Question | Answer |
|---|---|
| What the canonical converter stroke physically moves | `xF8` (the F8 anchor), by the `phi`/`psi` swing in the `econv`-normal plane at the `thetaS` rest-switch (§21.1) |
| Which frame defines its plane | the lab-fixed base triad `(bhat, econv, eup)` (§21.1) |
| How the new bound frame modifies it | rotates that triad by `eps` about `−mirror·nSite` in the local site frame, with an interface-gauge offset (§21.2) |
| Does the 90° diagnostic give predominantly tangential motion | **YES — 100.00%** tangential, radial identically 0 (§21.4) |
| Do force, torque, work and energy close | **YES** — F8 pair 0.00e+00; energy closes with non-negative dissipation at every angle (§21.4) |
| Is the stroke-event torque regenerated at each stroke | **YES** — signed at lag 0 and across all lag bins; reverses with the lattice mirror (§21.8) |
| Does the live *population-mean* torque resolve | **NOT at n = 16** — randomized null (0.11σ), shared lean (1.44σ, 12/16); under-powered (§21.7) |
| Does directed filament roll resolve | not tested — roll spring OFF by design; gated behind a resolved torque (§21.10) |
| How gliding changes with angle | axial (propulsive) force is eps-EVEN ⇒ gliding preserved; healthy −1.2…−3.2 µm/s (§21.4, §21.7) |
| Mirror and sign controls | deterministic sign controls PASS (§21.5); stroke-conditioned mirror reversal holds (§21.8) |
| CPU/GPU | bit-close on the full device-resident graph, convFlagMism 0, dSegTorque 1.5e-24 (§21.6) |

### 21.10 Decision class and the exact next step

**Decision class C3 — converter rotation produces a per-stroke torque that is real and mirror-reversing, but the
population-mean impulse is not yet resolved in the live ensemble at this size.** The deterministic converter
trajectory rotates exactly (5e-15) and the loaded eps-ODD torque is monotone in `eps` (§21.4/§21.5); the
stroke-event-conditioned torque is signed at lag 0 and reverses with the lattice mirror (§21.8); `Q_omega = 1.000`
shows the roll pathway is exact; gliding is preserved (axial force eps-EVEN). What is **not** yet resolved is the
*population-averaged* eps-ODD torque over a full attachment-turnover ensemble — under-powered at n = 16
(randomized 0.11σ, shared 1.44σ), the same ~10× under-power §20.8 measured for this observable.

**The load-bearing contrast with §20 stands:** the old attachment-offset skew was null *at the source* — its age-0
stroke-conditioned torque was already zero (the tangential strain was never dynamically created, decision T5).
The converter skew is the opposite: the strain **is** regenerated at each stroke (deterministic Stage 1 proves it;
the lag-0 stroke-conditioned torque is nonzero and chirally signed). So the converter mechanism is the physically
correct realisation of "the actual converter power stroke is rotated in the local actin-site frame", and its
remaining gap is *statistical power*, not existence.

**Exact next smallest step — power the population endpoint, do not add mechanism.** Run the shared-base `S±` and
mirrored `RM±` pairs at **~48–64 matched seeds** (the shared-base lean is 12/16 at n = 16 ⇒ resolvable at ~3σ by
n ≈ 48 if the trend holds), with per-bin uncertainties on the stroke-event table, targeting the ~3e-22 N·m
shared-base eps-ODD torque against a per-arm SEM that must fall to ~1e-22. Only if that resolves the population
mean should the sequence continue to the **separately-gated roll-coherence test** (`RollSpringSystem`), which
§17.1/§20.13 gate behind a resolved net torque. Nothing new needs building — the arms, flags, device graph and the
stroke-event observable all exist and are validated (§21.6). The converter-frame kernel is the load-bearing change
this task set out to make; it is implemented, CPU/GPU-validated, and deterministically exact.

## 22. Stronger converter-skew direct-twirling assay

**The question.** §21 proved the converter power stroke rotates exactly in the local actin-site frame and regenerates
a chirally-signed torque *at* each stroke, but the live *population-mean* was under-resolved at 5°/n=16 (class C3).
This section runs the signal-strength test §21.10 set up: **does increasing the true converter skew ε produce a
stronger, DIRECTLY measurable body-fixed filament roll** in the same full-mat, one-segment, filament-Brownian-off
assay? Only the motor-side `-converter-stroke-skew-deg` is used; `-binding-skew-deg 0`, `-stroke-skew-deg 0`,
`-bound-registry-k 0`, target-zone OFF, roll spring OFF. **No mechanism was added, the converter was not modified,
no canonical default/parameter/RNG/dt/`CANON_VERSION` was touched.** New host-side measurements only (least-squares
slope-fit Ω with R², windowed stroke-conditioned angular impulse `J_θ`, the ε-sweep driver `-conv-sweep`, the
mirror/randomized controls `-conv-controls`) — no new device kernel, so the validated §21.6 device graph is
byte-unchanged.

### 22.1 Exact configuration
One rigid filament segment (full 2.106 µm contour; roll drag = the whole filament's, ratio 1.000000), filament
translational+rotational Brownian OFF, motor/S2 + head-roll Brownian ON, `every3` sites (8.10 nm rise), site
exclusivity ON, Ractin 3.50 nm, capture 12 nm, surface bond ON, **interface** converter-skew gauge, registry K=0,
binding-skew=0, old-stroke-skew=0, target-zone OFF, roll spring OFF. dt = 2.5e-6 s, density 400 heads/µm² (N=1200),
**steps = 8000 (20 ms; 25 % equilibration ⇒ 15 ms measured, 5 blocks)** — the §20 twirl duration, longer than §21's
10 ms, chosen because the direct-roll SLOPE needs a longer window to rise above the √t random walk. Primary
observable = the transported **body-fixed** roll Θ(t) (§20.4), reported both as the endpoint rate `Ω = ΔΘ/Δt`
(≡ ⟨τ_ax⟩/γ_roll by the transport identity, so Q_omega = 1 by construction) and as a least-squares slope of
Θ = Ω·t + b with R²; the legacy lab-referenced readout is printed but never used for a claim.

### 22.2 Stage-0 regression and configuration audit — ALL PASS
- `-conv-stage1` (deterministic converter trajectory, CPU): **8/8 PASS** — unloaded stroke rotates to 5e-15
  (`dr_t = dr_u(0)·sin ε`), magnitude ε-independent, 90° 100 % tangential; loaded eps-ODD force+torque monotone in
  |ε|; F8 pair closed; energy closes.
- `-conv-fixtures` (Stage-3 symmetry, CPU): **8/8 PASS** — incl. 205 `Ractin=0` removes the torque arm, 204
  randomized base keeps the eps-ODD sign, **209 ε=0 byte-identical to the canonical no-skew trajectory**.
- `-conv-equiv` (full converter-skew gliding graph, device-resident): **PASS** — bindMism=0, convFlagMism=0,
  max|dSegTorque|=1.5e-24 N·m, firstDiv=none, `-Dtornado.recover.bailout=false` ⇒ a lowering failure THROWS
  (no silent CPU fallback), `-Dtornado.enable.fma=false`, `-Dtornado.tvm.maxbytecodesize=65536`.
- `-fixtures` (24 head-roll/site/registry fixtures, CPU): all PASS incl. **[8] all-flags-off bit-identical** and
  [11] continuous-surface bit-identity.
- Printed config confirmed per arm (`cfg()` sets the assay scene): every3 / head-roll DOF ON / surface bond ON /
  target-zone OFF / roll spring OFF / registry K=0 / binding-skew=0 / old-stroke-skew=0 / interface gauge / 1
  segment / filament Brownian OFF / motor Brownian ON. **0 invalid, 0 solver failures, no fallback anywhere.**
- CPU↔GPU: the whole assay ran device-resident throughout (`-gpu`, ~355 steps/s, monitored via
  `run_gpu_monitored.sh` with the external crash recorder RUNNING); the new measurements are host-side reductions
  of the same per-step telemetry the CPU/GPU-equivalent graph already crosses back, so they inherit §21.6.

### 22.3 Stage-1 pilot — the angle sweep (shared base, native lattice, 8 matched seeds, 20 ms)
`-conv-sweep -conv-angles "5,15,30" -seeds 8 -steps 8000` (GPU device-resident;
`RUN_LOGS/chiral_sites/s1_pilot_sweep_gpu.txt`). Ω_odd = ½[Ω(+ε) − Ω(−ε)] over matched seeds.

| ε | Ω_odd endpoint (rad/s) | Ω_odd slope-fit (rad/s) | same-sign | tauOdd (N·m) | glide vEven (µm/s) | avgB |
|---|---|---|---|---|---|---|
| 5°  | −10.90 ± 3.8 (2.87σ) | −10.55 ± 4.5 (2.37σ) | 7/8 | −3.53e-22 | −3.01 | 2.6 |
| 15° | −11.34 ± 3.5 (3.24σ) | −12.68 ± 3.5 (3.61σ) | **8/8** | −3.67e-22 | −2.67 | 2.6 |
| 30° | −17.29 ± 8.2 (2.10σ) | −19.44 ± 7.1 (2.76σ) | 7/8 | −5.60e-22 | −2.73 | 2.9 |

**The direct twirl is RESOLVED at every angle** — the §21 5°/n=16 null is gone (the longer 20 ms window + shared
base did it) — NEGATIVE and consistent, and **grows monotonically with ε**. 0 invalid/solver, gliding healthy,
Q_omega=1.000 throughout.

### 22.4 sin(ε) scaling — Outcome B (the per-stroke mechanism scales; the population twirl grows sub-sin)
`ratio/5°` observed vs `sin(ε)/sin(5°)` predicted (unloaded, Δr_t ∝ sin ε):

| ε | Ω_odd ratio/5° | J_odd[0–31] ratio/5° | sin(ε)/sin(5°) |
|---|---|---|---|
| 5°  | 1.00 | 1.00 | 1.00 |
| 15° | 1.20 | 2.65 | 2.97 |
| 30° | 1.84 | 4.84 | 5.74 |

The **stroke-conditioned per-stroke impulse J_odd scales ≈ sin(ε)** (2.65×/4.84× vs 2.97×/5.74×) and resolves at
16–27σ — the converter mechanism's geometric scaling is intact and regenerated at each stroke. The **direct
population-mean twirl Ω_odd grows only 1.2×/1.84×** — sub-sin — because it is diluted over the whole bound
population and the near-cancelling off-axis-bond baseline (`cancel` 25–110). This is the task's **Outcome B**
(per-stroke impulse scales; total filament twirl grows but does not scale with sin), *but* — unlike §21 — the
diluted direct twirl now resolves. Gliding stays healthy at every angle (NOT Outcome D). **Best angle chosen = 15°**
(best-resolved direct twirl, 8/8 seeds; strongest J_odd significance-to-noise; healthy engagement; cleanest
numerics — vs 30° which is larger in magnitude but noisier, 6–7/8, and sub-sin anyway).

### 22.5 Direct roll trajectories — resolved directed drift on a comparable random walk
Per-seed the accumulated Θ(t) is a directed drift superimposed on a √t random walk: the roll-vs-time fit R² is
**≈0.45–0.62** across the 15°/30° arms — the linear (directed) component is real and resolved in the ensemble Ω_odd,
but each single seed also carries a substantial random walk. Total accumulated roll per seed is consistently signed
(totRoll ≈ −0.15 rad over 15 ms at 15°). So Θ(t) is *approximately directed winding*, honestly stated: a resolved
ensemble drift, not a clean per-seed deterministic winding.

### 22.6 Powered native endpoint (48 matched seeds, 15°) — the primary result
`-conv-sweep -conv-angles "15" -seeds 48 -steps 8000` (`RUN_LOGS/chiral_sites/s3_powered_native48_15deg_gpu.txt`).
At n=24 the native shared arm gave 2.28σ (a low draw from the pilot's lucky 3.24σ); per the adaptive stop rule
(sign holds, CI narrowing, not decisive, health good) it was extended to **48 seeds**:

| arm | tauOdd (N·m) | Ω_odd endpoint (rad/s) | Ω_odd slope-fit (rad/s) | same-sign | vEven | vOdd |
|---|---|---|---|---|---|---|
| **S± 15° native** | −3.09e-22 | **−9.55 ± 1.8 (5.33σ)** | **−9.89 ± 2.1 (4.78σ)** | 37/48 | −2.88 | −0.08 |
| S0 ε=0 achiral | +3.9e-24 | +0.12 (0.04σ) | −0.22 ± 3.6 (0.1σ) | 26/48 | −3.22 | — |

**The native direct twirl Ω_odd is resolved at 5.33σ** (endpoint) / 4.78σ (slope) — comfortably past the 3σ gate;
the central value settled at ≈ −9.5 rad/s. **The ε=0 achiral control is consistent with zero odd roll** (Ω = +0.12,
slope −0.22, 0.1σ; the n=8 pilot's −9.5 was a random-walk fluctuation that averaged out at n=48). vOdd (−0.08)
≪ vEven (−2.88) ⇒ the gliding odd response is ≈0, exactly as the mechanism predicts (axial force ε-EVEN). 0
invalid/solver, no fallback.

### 22.7 Stroke-conditioned angular impulse — the seed-level chirality proof
Per-stroke `J_θ` integrated over post-stroke windows 0–1…0–31 steps; **seed is the independent statistical unit**
(pooling is never used for a SEM). Native 48 seeds (s3) and mirror 24 seeds (s2):

| window | native J_odd (σ) | native seeds+ | mirror J_odd (σ) | mirror seeds+ |
|---|---|---|---|---|
| 0–7  | −4.87e-26 (29.7σ) | **0/48** | +4.51e-26 (20.4σ) | **24/24** |
| 0–15 | −9.05e-26 (32.1σ) | **0/48** | +8.22e-26 (24.9σ) | **24/24** |
| 0–31 | −1.20e-25 (24.0σ) | **0/48** | +1.09e-25 (16.9σ) | **24/24** |

**Every one of the 48 native seeds has a NEGATIVE per-stroke impulse; every one of the 24 mirror seeds is
POSITIVE** — a perfect seed-level chirality reversal, and the decisive statement of the increment. The event-level
sign is noisier (native ~35–65 % negative event-by-event) because a single stroke's window is buried in thermal
motor/S2 noise; the seed-level coherence is total. The shortest window that cleanly captures the signed converter
response with low off-stroke background is **0–7 steps** (already ~20–30σ; the 0–1 window is a noise-dominated
single step). Pooling seed = independent unit is used throughout; no pseudo-SEM from event pooling is quoted.

### 22.8 Mirror and randomized-base controls (15°, 24 seeds)  [`RUN_LOGS/chiral_sites/s2_controls_powered_15deg_gpu.txt`]
`-conv-controls -converter-stroke-skew-deg 15 -seeds 24`:

| arm | Ω_odd endpoint (rad/s) | Ω_odd slope (rad/s) | tauOdd (σ) | vEven | avgB |
|---|---|---|---|---|---|
| SHARED native | −6.50 ± 2.8 | −6.96 ± 3.2 | −2.11e-22 (2.28σ) | −2.69 | 2.7 |
| SHARED **mirror** | **+11.87 ± 3.7** | **+11.89 ± 4.2** | +3.85e-22 (3.23σ) | −3.01 | 2.7 |
| RANDOMIZED base | −1.80 ± 3.0 | −2.73 ± 2.8 | −5.83e-23 (0.61σ) | −1.25 | 1.7 |

- **Mirror control (required for the chirality claim): Ω_odd REVERSES** — native −6.96 → mirror +11.89; and
  **J_odd[0–7] REVERSES** — native −4.77e-26 → mirror +4.51e-26 (24/24 seeds flip sign, §22.7). The mirrored-lattice
  eps-ODD is resolved at 3.23σ (tau) / 2.85σ (slope).
- **Randomized-base control (locality): the sign SURVIVES** base randomization — native(shared) −6.96, randomized
  −2.73 (both negative) — with the expected magnitude and engagement drop (avgB 1.7 vs 2.7, vEven −1.25 vs −2.69),
  because randomizing the motor base azimuth partially cancels the local-frame signal at the population level (the
  per-stroke impulse odd washes to a coin flip, ~9–15/24 seeds, ≤1σ). The sign surviving confirms the effect is a
  LOCAL-frame, not shared-base, artifact.

### 22.9 Timestep check (15°, shared base, 8 seeds; dt and dt/2 at matched 20 ms)  [`s4_timestep_15deg_shared_gpu.txt`]

| | Ω_odd endpoint (rad/s) | Ω_odd slope (rad/s) | J_odd[0–15] (σ) | seeds+ (0–15) | vEven |
|---|---|---|---|---|---|
| dt = 2.5e-6 (8000 steps)  | −11.34 ± 3.5 (3.24σ) | −12.68 ± 3.5 (3.61σ) | −9.00e-26 (16.1σ) | 0/8 | −2.67 |
| dt/2 = 1.25e-6 (16000)    | −15.08 ± 4.8 (3.11σ) | −14.50 ± 5.9 (2.45σ) | −4.10e-26 (13.2σ) | 0/8 | −2.72 |

**The twirl conclusion does NOT change under refinement**: Ω_odd stays NEGATIVE and resolved (3.1–3.2σ), the
per-stroke impulse stays negative and 0/8 seeds positive at both dt. **Documented dt bias (stated separately as the
task requires):** the Ω_odd *magnitude* grows ≈33 % at dt/2 (−11.3 → −15.1 rad/s) — a per-bound cross-bridge-force
dt-sensitivity (the stroke torque is slightly undersampled at coarse dt), the same family as the §-dt gliding
studies. The glide even channel is dt-stable here (vEven −2.67 → −2.72, ~2 %). J_odd measured over a fixed number of
*steps* is ~½ at dt/2 because the same step-window spans half the physical time; the per-second impulse rate and the
sign/seed-coherence are preserved. Net: sign, resolution and chirality are dt-robust; the magnitude carries a known
~33 % dt-sensitivity that does not alter the conclusion.

### 22.10 Torque-to-roll accounting — Q_omega = 1.000
In every arm the measured body-fixed roll equals the independently-measured mean axial torque divided by the
integrator's own roll drag (`Ω = ⟨τ_ax⟩/γ_roll`, γ_roll = 3.2419e-23 N·m·s): **Q_omega = 1.000 to four digits**,
including the ε=0 arms. There is no torque-to-rotation gap — the resolved roll is fully explained by the measured
converter torque; 1e-21 N·m ⇒ 30.85 rad/s in this filament.

### 22.11 Success gate for directed twirling

| # | Criterion | Result |
|---|---|---|
| 1 | Native Ω_odd excludes zero at ≥3σ | **PASS** — 5.33σ endpoint / 4.78σ slope (n=48) |
| 2 | +ε and −ε give opposite body-fixed roll | **PASS** — S+ −10.05, S− +9.04 rad/s |
| 3 | Mirrored lattice reverses Ω_odd | **PASS** — native −9.55 → mirror +11.87; J_odd 24/24 flip |
| 4 | ε=0 control consistent with zero odd roll | **PASS** — Ω +0.12, slope −0.22 (0.1σ), n=48 |
| 5 | Θ(t) approximately directed winding, not only a walk | **PARTIAL** — resolved ensemble drift; per-seed R²≈0.45–0.62 |
| 6 | Stroke-conditioned impulse same sign + mirror reversal | **PASS** — native 0/48 seeds +, mirror 24/24 +, 20–32σ |
| 7 | Q_omega confirms torque explains roll | **PASS** — 1.000 every arm |
| 8 | Axial gliding healthy | **PASS** — vEven −2.7…−3.0 µm/s |
| 9 | Gliding odd ≈0 / ≪ even | **PASS** — vOdd −0.08 vs vEven −2.88 (3 %) |
| 10 | Survives timestep refinement | **PASS** (conclusion) — sign/resolution/chirality dt-robust; magnitude +33 % dt bias documented |
| 11 | No invalid states / solver failures / fallback | **PASS** — 0/0/none across every run |

10 of 11 pass decisively; #5 is partial (the directed twirl is a resolved ensemble drift on a comparable per-seed
random walk, R²≈0.5).

### 22.12 Decision class and the exact next step
**Decision class D1 — directed twirling is established in the simplified full-mat, one-segment, filament-Brownian-off
assay, and it strengthens with the converter skew.** Increasing the true converter-stroke skew turned the §21
under-resolved 5° signal into a directly-measured, resolved body-fixed roll: native Ω_odd = −9.55 ± 1.8 rad/s
(5.33σ, n=48), reversing to +11.87 under the lattice mirror, surviving base randomization in sign, with the
stroke-conditioned per-stroke impulse coherent across **all 48 native / all 24 mirror seeds** and dt-robust in
sign/resolution. **The scaling is Outcome B**: the per-stroke geometric impulse scales ≈ sin(ε) (the mechanism), but
the population-mean direct twirl grows only sub-sin (≈1.2×/1.84× for 15°/30°) because it is diluted over the whole
bound population — so a bigger skew helps, but with diminishing return in the *directly measured* twirl. **The
smallest converter skew that produces a resolved direct twirl is ≈ 5–15°** (5° already 2.4–2.9σ at 20 ms/n=8; 15°
clean at 8/8 seeds and 5.33σ at n=48).

**Honest limits (not hidden):** (a) gate #5 — per-seed R²≈0.5, i.e. a resolved directed drift on a comparable random
walk, not a clean deterministic winding; (b) the Ω_odd *magnitude* carries a ≈33 % dt-sensitivity (sign/resolution
unaffected); (c) 15° is a large, non-biological skew — this is a mechanism signal-strength result, not a pitch
claim; the roll-per-distance quoted next is a diagnostic only. **Roll-per-distance (diagnostic, this Brownian-off
one-segment scene only):** Ω_odd/|vEven| ≈ 9.55 rad/s ÷ 2.88 µm/s ≈ **3.3 rad/µm ≈ 0.53 turn/µm** at 15° — reported
solely to size the simplified assay, NOT as a biological pitch.

**Exact next step — separately gated, do not fold into this task.** With a resolved net torque now in hand, the
sequence §17.1/§20.13 gated behind it opens: the **roll-coherence test** with `RollSpringSystem` (does a
multi-segment filament roll coherently, or does the resolved single-segment torque wash out across segments?), at
15° shared base, mirror-controlled. Only after that resolves should a biological pitch (turn/µm at a biological ε,
if any converter skew is physical) be discussed. Nothing new needs building for the roll-coherence test beyond
wiring `RollSpringSystem`, which this task deliberately left OFF.

**Visualization (illustrative only).** `-twirl -3js threejs_conv_twirl -converter-stroke-skew-deg 15 -steps 8000
-stride 40` writes 200-frame movies for ε=0 / +15 native / −15 native / +15 mirror (material roll ticks, discrete
bound sites, head→site bonds coloured by per-head torque sign, accumulated transported roll, total axial torque, ε
in frame metadata). Single-seed (101), so noisy and NOT a statistical claim; the numbers above are the result.
Serve with `python3 SoftBox/sim_server.py 8000` from `~/Code`, open `http://localhost:8000/SoftBox/sim_viewer_boa.html`.

### 22.13 Regression — nothing pre-existing moved
`-conv-fixtures` 8/8, `-conv-stage1` 8/8, `-conv-equiv` PASS (device-resident), `-fixtures` all PASS incl. [8]
all-flags-off bit-identity and 209 ε=0 byte-identity. The new work is additive: host-side measurement reductions
(slope-fit Ω, windowed `J_θ`, `oddMS`/`msMask`/`median`), two new report drivers (`-conv-sweep`, `-conv-controls`),
and the converter-skew `makeTwirlMovies` arms — no device kernel, no canonical path, no `CANON_VERSION`, RNG, dt or
event-ordering change. `BoA-v1ref` byte-clean; the canonical explicit-S2 production path untouched.


## 23. Converter-twirling efficiency and motor-geometry audit

**The question this section was set.** §22 established directed twirling in the simplified assay (native
Ω_odd = −9.55 ± 1.8 rad/s at 5.33σ, mirror-reversing, per-stroke impulse coherent across all 48 native seeds)
but with an apparent paradox: the **stroke-conditioned per-stroke impulse `J_odd` scales ≈ sin(ε)** while the
**population-mean roll Ω_odd grows only sub-sin** (1.20×/1.84× at 15°/30° against sin-ratios 2.97×/5.74×).
§22.4 attributed that to *dilution over the bound population*. This section tests the attribution by accounting
for the axial angular impulse over the **whole bound episode** instead of a fixed post-stroke window, and then
asks whether motor geometry can improve the transfer.

**The headline is that the attribution was wrong, and in the favourable direction.** There is no loss between
the stroke event and the population mean: the stroke window *under-counts* the chiral impulse by ~2.6×, and once
the full bound cycle is accounted for the measured population torque is reproduced to within ~1–26 %. The one
genuine loss channel is the **pre**-stroke dwell, and the dominant chiral lever in this motor turns out to be
the **static bound-pose preload**, not the stroke. Full accounting in §23.3–§23.5.

Everything here is **noncanonical, flag-gated, DEFAULT-OFF, byte-identical when disabled**. No canonical
default, parameter, chemistry, dt, RNG stream, event ordering, `MotorModel.CANON_VERSION`, device kernel or
TaskGraph task was changed (§23.15).

### 23.1 Exact configuration and what is new

Scene identical to §22.1: one rigid filament segment (full 2.106 µm contour, roll-drag ratio 1.000000),
filament translational+rotational Brownian OFF, motor/S2 + head-roll Brownian ON, `every3` sites (8.10 nm rise),
site exclusivity ON, Ractin 3.50 nm, capture 12 nm, surface bond ON, **interface** converter-skew gauge,
registry K = 0, `-binding-skew-deg 0`, `-stroke-skew-deg 0`, target-zone OFF, roll spring OFF, dt = 2.5e-6 s,
density 400 heads/µm² (N = 1200), 8000 steps (20 ms; 25 % equilibration ⇒ 15 ms measured), shared motor bases,
**GPU device-resident throughout** (`run_gpu_monitored.sh` outermost, external recorder RUNNING,
`-Dtornado.enable.fma=false -Dtornado.recover.bailout=false -Dtornado.tvm.maxbytecodesize=65536`; a lowering
failure THROWS, so there is no silent CPU fallback anywhere in this section).

New, all measurement-side:

| addition | what it is | risk |
|---|---|---|
| `ChiralSiteHarness.Ledger` | per-motor bound-EPISODE state machine; routes each step's `τ_ax·dt` into `J_pre`/`J_stroke`/`J_post_early`/`J_post_late` by lag since the episode's FIRST stroke | host-side reduction only |
| `softbox/ConvBudget.java` | seed-level statistics, quantile stratification, efficiency metrics, atlas classification | pure analysis |
| `ExplicitCompleteMatHarness.EPISODE_TELEM` | adds `q`/`nodes`/`outGeom` to the **production copy-out list** so the motor-internal stratifiers are host-current on GPU | **transfers only** — no kernel, no device work, no ordering |
| `applyGeomScales` + the base-triad roll | the Phase C/D/E diagnostic geometry hooks | **scene parameters only** — see §23.6 |
| `-conv-budget`, `-conv-gauge-compare`, `-conv-geom-sweep <axis>`, `-conv-geom-values` | drivers | new modes |

**Control that the instrumentation did not perturb the physics.** The `-conv-budget` 15°/n = 8 arm reproduces
§22.3's pilot **digit for digit** — Ω_odd slope-fit **−12.675 vs −12.68 rad/s (3.61σ vs 3.61σ)**, `tauOdd`
−3.675e-22 vs −3.67e-22, vEven −2.67, avgBound 2.66/2.60 — and at n = 24 the baseline arm reproduces §22.8's
24-seed shared-native arm exactly (Ω_odd slope −6.956 vs −6.96, `tauOdd` −2.1055e-22 vs −2.11e-22, 2.28σ vs
2.28σ). Adding three buffers to the copy-out set changed nothing, as it must.

**Statistics.** The independent unit is the SEED throughout. Per-seed means over that seed's episodes are the
primitive; across-seed mean ± SEM, median, IQR, seed-sign fraction and a deterministic seed-bootstrap 95 % CI
sit on top. Every conclusion is drawn in the ε-ODD channel `X_odd = ½[X(+ε) − X(−ε)]` on matched seeds, which
cancels the large ε-EVEN off-axis-bond baseline (|τ|/|τ̄| = 20–110 in these arms). Ratios are formed from
seed-paired ODD means and suppressed when the denominator is under 3σ. Event-level counts are descriptive only;
no pseudo-SEM is ever quoted from pooled events.

### 23.2 The full-cycle impulse budget — definitions

For every bound episode that contains an ADP·Pi→ADP stroke, the axial angular impulse of each measured step
(`τ_ax·dt`, `τ_ax` the bond's segment-side torque projected on the filament's own material tangent) is routed by
its **lag since that episode's FIRST stroke**:

```
J_pre        = Σ τ_ax·dt   from the observed attachment through the step before the stroke
J_stroke     = Σ τ_ax·dt   over lags 0..7      (the §22.7 shortest validated stroke-local window)
J_post_early = Σ τ_ax·dt   over lags 8..31
J_post_late  = Σ τ_ax·dt   over lags 32 .. detachment
J_total      = J_pre + J_stroke + J_post_early + J_post_late
J_recoil     = J_total − J_stroke
f_retain     = J_total / J_stroke
```

Routing by lag-since-the-FIRST-stroke (rather than resetting on a re-stroke) makes double counting structurally
impossible; re-strokes are counted in a separate field. In this scene they never happen — `strokes/episode` is
**1.000** in every arm, because the mean post-stroke residence (~281 steps) is shorter than the ATP/ADP·Pi
re-priming path after detachment.

Two censoring rules, both stated rather than hidden. Episodes already in progress when the measurement window
opens are **left-censored and never recorded** (their attachment was not observed, so `J_pre` is undefined).
Episodes still bound at the horizon are recorded with a censored flag and **excluded from the budget** (~5 % of
records). Both exclusions are conservative for the closure test of §23.5, which is computed both ways.

Each recorded episode also carries the at-stroke state used for stratification: site id, bind azimuth, pre- and
post-stroke lifetimes, S2 end-to-end extension and bending energy, converter φ and ψ, the F8 axial / tangential
/ radial force components and axial torque, the number of simultaneously bound motors, the motor base azimuth,
the motor pivot's azimuth about the filament, and the stroke-window work terms `W_chiral = Σ τ_ax·dΘ` and
`W_F8 = Σ F_seg·Δx_F8`.

### 23.3 Phase A — the full bound-cycle impulse budget (15°, n = 8) [`p1_budget_15deg_n8_gpu.txt`]

366 (+ε) / 374 (−ε) stroke-bearing episodes over 8 matched seeds, 22/18 right-censored and excluded;
`strokes/episode` = 1.000; mean pre-stroke life 39.3 steps, mean post-stroke life 281 steps (0.70 ms);
window-truncated fraction 0.010; 0 invalid, 0 solver failures, no fallback.

| component (per stroke-bearing episode) | ODD mean N·m·s | SEM | σ | median | seed sign |
|---|---|---|---|---|---|
| `J_pre` (attach → stroke−1) | **+6.999e-26** | 2.03e-26 | 3.45 | +6.08e-26 | 88 % |
| `J_stroke` (lags 0–7) | **−4.563e-26** | 5.64e-27 | 8.09 | −5.00e-26 | **100 %** |
| `J_post_early` (lags 8–31) | **−6.851e-26** | 5.43e-27 | 12.62 | −6.95e-26 | **100 %** |
| `J_post_late` (lags 32 → detach) | **−8.818e-26** | 4.27e-26 | 2.07 | −7.02e-26 | 75 % |
| **`J_total`** (attach → detach) | **−1.323e-25** | 4.02e-26 | 3.30 | −1.26e-25 | **100 %** |
| `J_recoil` = `J_total − J_stroke` | **−8.669e-26** | 4.02e-26 | 2.16 | −9.26e-26 | 75 % |
| `f_retain` = `J_total/J_stroke` | **+2.90** | per-seed mean +3.37 ± 0.97, median +3.03 | | | |

seed-bootstrap 95 % CI: `J_stroke` [−5.53e-26, −3.39e-26], `J_total` [−2.12e-25, −6.27e-26].

**`f_retain` = +2.90, not < 1.** The post-stroke bound period does not cancel the stroke impulse — it
**reinforces** it (`J_recoil` carries the SAME sign as `J_stroke`). The only opposing channel is `J_pre`, the
**pre**-stroke bound dwell. **Decision class E1 is refuted: there is no late-bound recoil loss.**

**ε = 0 achiral null (the budget's own noise floor; the ε = 0 arm split into two 4-seed halves).**
`J_stroke` null = −2.71e-28 ± 1.77e-27 (**0.15σ**) against the signal's −4.56e-26 (8.09σ) — a 170× separation.
`J_total` null = +5.63e-26 ± 4.96e-26 (1.13σ): honestly, `J_total`'s uncertainty is dominated by the long
`J_post_late` tail, whose *own* noise floor is comparable to the signal's SEM. So `J_stroke`/`J_post_early` are
sharply resolved and `J_total`/`J_post_late` are resolved but tail-noise-limited — stated rather than smoothed.
(At n = 24 with the finalist geometry the same null gives every component ≤ 0.92σ; §23.11.)

**Stroke-free bound episodes (control).** Episodes that never stroke carry `J_pre`-sum +2.40e-24 over 14 (+ε)
and −1.25e-24 over 13 (−ε) — no coherent chiral impulse, as required: without a nucleotide transition the
rotated converter frame produces no rectified impulse.

### 23.4 Phase A2 — which states retain the chiral impulse

Quantile-binned in the ODD channel, seed as the unit (full tables in the log). The signal-bearing structure:

- **`J_stroke_odd` is remarkably UNIFORM** across every stratifier — −2.9e-26 … −6.3e-26 in all 40+ bins, with
  no bin flipping sign. The per-stroke chiral impulse is a **robust property of the mechanism**, not of a
  favoured sub-population. There is no "productive pose" to select for.
- **All the variance lives in `J_recoil`**, i.e. in the long bound tail, and it changes sign between bins:
  strongly reinforcing at long post-stroke lifetime (`[450,∞)` −3.14e-25) and at short (`[77,169)` −1.74e-25),
  but ~0 or mildly opposing in the middle (`[169,291)` +8.8e-28, `[291,450)` +1.2e-26).
- **`J_pre` is the loss, and it is pose-driven, not lifetime-driven.** The clearest single stratifier is the
  pre-stroke lifetime: the shortest-pre-life quartile `[−∞,12)` has `J_total_odd = +1.47e-26` — the WRONG sign —
  while every longer-pre-life quartile is strongly negative (−1.15e-25 … −1.71e-25).
- **`f_retain` never falls below ~1 in any healthy bin** and reaches +5…+8 in the low-φ, mid-ψ, mid-axial-force
  and long-lifetime bins. Nothing in the geometry-accessible state space looks like a truncation regime
  (`fast-detach` fraction 0.012).
- **`nBound` at stroke** (3.65 mean): `f_retain` is +4.1…+4.3 for ≥3 simultaneously bound heads but −2.3 for
  <3 — the multi-head tug-of-war *helps* rather than cancels here, opposite to the §22.4 dilution guess.
- **Motor base azimuth** is a single bin (0, shared bases) as designed, so the randomized-base locality control
  is the §23.12 job.

### 23.5 Phase A3/A4 — the closure that answers the load-bearing question, and the ε-scaling decomposition

**The closure.** If the population mean were losing chiral impulse somewhere between the stroke and the
ensemble, then (episode rate) × (per-episode impulse) would exceed the measured `τ_odd`. It does not:

| ε | episodes/seed | episode rate | `J_total_odd` | predicted `τ_odd` | measured `τ_odd` | closure (`J_total`) | closure (`J_stroke` only) |
|---|---|---|---|---|---|---|---|
| 5° | 44.4 | 2958 /s | −1.502e-25 | −4.44e-22 | −3.533e-22 | **1.26** | 0.15 |
| 15° | 43.8 | 2917 /s | −1.323e-25 | −3.86e-22 | −3.675e-22 | **1.05** | 0.36 |
| 30° | 46.4 | 3096 /s | −1.829e-25 | −5.66e-22 | −5.605e-22 | **1.01** | 0.47 |

(Rate = uncensored stroke-bearing episodes per second over the 15 ms measurement window; the residual and the
26 % at 5° are consistent with the two censoring exclusions, which preferentially drop long-lived episodes and
therefore the `J_post_late` tail.)

**⇒ The chiral impulse is NOT being lost. It is being UNDER-COUNTED by the stroke window.** The 0–7-step window
captures only 15–47 % of the per-episode chiral impulse; the rest accrues over the long post-stroke bound
residence with the SAME sign. `eta_pop` measured against `J_stroke` is +2.0…+2.6 (the population delivers
2–2.6× MORE torque than the stroke window predicts) and against `J_total` it is ≈ 1. **There is no transmission
defect to fix.**

**Why Ω_odd nevertheless grows sub-sin — the decomposition.** Ratios against the 5° arm
[`p2_budget_05deg_n8_gpu.txt`, `p3_budget_30deg_n8_gpu.txt`]:

| quantity | 5° | 15° | 30° | scaling |
|---|---|---|---|---|
| sin ε / sin 5° | 1.00 | 2.97 | 5.74 | — |
| `J_stroke` | 1.00 | 2.49 | 4.61 | **∝ sin ε** |
| `J_post_early` | 1.00 | 2.50 | 4.57 | **∝ sin ε** |
| `J_pre` (OPPOSING) | 1.00 | **3.40** | **7.83** | **super-sin** |
| `J_post_late` | 1.00 | 0.71 | 1.07 | **ε-independent** |
| `J_total` | 1.00 | 0.88 | 1.22 | ~flat |
| `τ_odd` | 1.00 | 1.04 | 1.59 | ~flat |
| `Ω_odd` (slope) | 1.00 | 1.20 | 1.84 | ~flat (= §22.4) |

Three channels with three different ε-laws:

1. **The stroke-driven channel** (`J_stroke + J_post_early`, lags 0–31) scales **∝ sin ε** — the converter
   mechanism works exactly as designed, all the way to 30°.
2. **The pre-stroke channel** (`J_pre`) is **OPPOSING and grows FASTER than sin ε**, cancelling
   **44.9 % → 61.3 % → 76.8 %** of the stroke-driven channel at 5°/15°/30°. The net stroke contribution
   therefore grows only **1.00 → 1.75 → 1.93**, which is what the observed Ω_odd growth (1.00 → 1.20 → 1.84)
   actually tracks.
3. **The late tail** (`J_post_late`, lags ≥32) is **ε-insensitive** and is the DOMINANT term at 5° (83 % of
   `J_total`), acting as a constant pedestal that further compresses every ratio taken against 5°.

**This replaces §22.4's attribution.** The sub-sin growth is not "dilution over the bound population" — it is a
**super-linear opposing pre-stroke chiral preload** plus an ε-independent bound-tail pedestal. §22.4's Outcome-B
*observation* stands; its *mechanism* was wrong.

**Atlas mapping (Phase A4).** Against `docs/TWIRLING_MECHANISM_ATLAS_FINDINGS.md` and its master identity
`J_θ = −R·γ_y·Δy_bound − γ_ω·Δω_bound` (the cycle impulse is set entirely by the net chiral-coordinate
displacement accrued WHILE BOUND — which is exactly why the whole-episode integral, not the stroke window, is
the physical observable):

- The full motor is **atlas class A** on the harness's own classifier (`f_retain` = 2.16–8.19 ≫ 0, recoil
  same-signed, truncation fraction 0.01) — a **direct chiral stroke with retained bound displacement**.
- Mechanistically it is a **superposition of two R4 atlas legs with OPPOSITE handedness**:
  **A2 (oblique vector stroke)** carrying the sin ε-scaling `J_stroke + J_post_early` — the measured
  `J_stroke_odd` at 15° (−4.56e-26) sits within 20 % of the atlas's reduced-model A2(15°) `J_θ` = −5.44e-26 —
  plus **A3 (finite-rest bound pose / chiral binding registry, whose atlas impulse is "all in `J_pre`")**
  appearing here with the *wrong sign* as a parasite.
- It is emphatically **NOT** the atlas's conservative-failure class (A5/A6, "large peak τ, cycle impulse ≈ 0",
  which the atlas §16 identified as the explanation for the §20 askew-attachment null). The measured
  cycle-integrated impulse is finite, coherent (100 % of seeds) and mirror-reversing.
- It is **not** class C either: post-stroke residence (median 281 steps) vastly exceeds the stroke window, so
  nothing is preserved by early detachment.

#### 23.5b Efficiency metrics (15°, n = 8)

| metric | value | note |
|---|---|---|
| `eta_geom` = \|Δr_t\|/\|Δr_total\| | **0.2588** | −2.071 / 8.000 nm, deterministic (§21.4); = sin 15° exactly |
| `eta_J` = \|`J_stroke_odd`\| | 4.563e-26 N·m·s | per stroke, stroke window |
| `eta_cycle` = signed `J_total_odd` | **−1.323e-25 N·m·s** | the physically meaningful per-cycle impulse |
| `eta_retain` = `J_total/J_stroke` | **+2.90** | > 1 ⇒ the window under-counts |
| `eta_pop` = `τ_odd`/(rate·`J_stroke`) | **+2.59** | ⇒ **≈ 1.0 when computed against `J_total`** (closure table) |
| `eta_energy` (proxy) = `W_chiral`/\|`W_F8`\| | **+8.20e-05** | +1.99e-24 J chiral work vs 2.43e-20 J stroke-window F8 work. NOT a thermodynamic efficiency — no chemical free-energy accounting. The 1e-4 scale is set by the ratio of the filament's roll mobility × moment arm to its axial mobility, i.e. a geometric consequence of a 3.5 nm arm on a 2.1 µm rod, not a mechanism inefficiency. |
| `eta_twirl/glide` = \|Ω_odd\|/\|v_even\| | 4.74 rad/µm = **0.755 turn/µm** | DIAGNOSTIC ONLY (Brownian-off, one rigid segment, non-biological 15° skew) |

### 23.6 Phase B — source-code geometry audit (read before any sweep)

Read from `TwoBodyBeamAnalyticGpu.matBeamGeom` / `matS2SolveStep`, the CPU twins
`TwoBodyConverterMotor.geom2D` / `s2SolveM`, `buildS2Mat`, `build3core` and
`ExplicitMatSolveHarness.paramArr`.

**The converter block, in one place.**

```
uB  = ê_up·cos φ + b̂·sin φ            C   = P + lb·uB              (P = beam node[M], the S2 pivot)
d0  = b̂·(rF8x−rCx) + ê_up·(rF8y−rCy)   xF8 = C + R_econv(ψ)·d0
rc  = b̂·rCx + ê_up·rCy                 xH  = C − R_econv(ψ)·rc
```

| quantity | symbol | value | where it lives | kind |
|---|---|---|---|---|
| S2 mechanically free length | L | **40 nm** | literal at `ChiralSiteHarness.build()` → `buildS2Mat(…,40.0,…)` | call-site scene parameter |
| S2 initial slack (sag) | — | 1.5 nm | `EXPLICIT_GLIDE_SLACK_NM` | constant |
| S2 element count | M | **4** | `round(L / EXP4G_L0_NM)`, `EXP4G_L0_NM = 10 nm` | **derived from L** |
| S2 element length | l0 | **10 nm** | `G.g4l0 = L/M` | derived |
| S2 stretch stiffness | ks | **0.4200 N/m** | `EXP4G_EA_SI / l0`, EA = 4.2e-9 N (70 pN/nm × 60 nm, MD-informed) | derived → `params[11]` |
| S2 bend stiffness | kb | **7.200e-20 N·m** | `EXP4G_EI_SI / l0`, EI = 7.2e-28 N·m² (0.01 pN/nm × 60nm³/3) | derived → `params[13]` |
| S2 node drag radius | — | 5 nm | `EXP4G_RNODE_NM` | constant → `params[16]` |
| converter joint C rel. pivot P | lb | **8.000 nm** | `LB_3C` → `G.lb` | constant → `params[0]` |
| F8 material point | rF8 | (3.5, 1.5) nm | `R_F8` → `G.rF8` | constant → `params[1..2]` |
| converter material point | rConv | (−3.5, −1.5) nm | `R_CONV` → `G.rConv` | constant → `params[3..4]` |
| **converter rotation radius** | \|d0\| | **7.6158 nm** | derived: (7.0, 3.0) nm in (b̂, ê_up) | derived |
| C→F8 offset ⊥ the zero-skew stroke axis | rCF8_perp | **3.000 nm** | the ê_up component of d0 | derived |
| C→F8 offset ‖ the zero-skew stroke axis | rCF8_axial | **7.000 nm** | the b̂ component of d0 | derived |
| head point rel. C | \|rc\| | 3.8079 nm | derived from rConv | derived |
| pre-stroke lever angle | φ_pre | +30° | `PHI_PRE_3E` | constant |
| converter rest switch | θ_s | −30° → +30° (Δ = 60°) | `PRESTROKE_THETAS` / `ADP_THETAS` | constants |
| **unloaded stroke magnitude** | \|Δr\| | **8.0000 nm** (ε-independent) | measured, §21.4 fixture 103 | emergent |
| zero-skew axial stroke | Δr_u(0) | **−8.0000 nm** (tangential exactly 0) | measured, §21.4 | emergent |
| interface gauge reference pose | (φ_ref, ψ_ref) | (`PHI_PRE`, `psiActin`) | `chiP[18]`, `q[3N+m]` | derived |

**Structural finding #1 — the stroke is the LEVER swing, not the converter arm.** A pure 60° converter swing on
radius \|d0\| would give a chord `2·7.6158·sin 30° = 7.6158 nm`, but the measured unloaded stroke is
**8.0000 nm = 2·lb·sin 30°** exactly. The bound head's ψ is pinned to actin by the binding spring, so the whole
θ_s switch is taken up by the neck-lever angle φ and the F8 anchor swings on radius **lb**, not \|d0\|. This was
then verified empirically across every geometry in §23.8–§23.10: the unloaded stroke is **bit-identical**
(8.0000 / −8.0000 / −2.0706 nm at ε = 15°) at S2 lengths 20–80 nm, S2 bend stiffness 0.5–2×, F8 eccentricity
0.75–1.5× raw and \|d0\|-compensated, and converter transverse offsets ±2 nm. **Consequence: no geometry axis in
this motor can change the stroke; they can only change the LOADED transmission and the static preload.**
(Consistent with `canonical-motor-divergence`: step ∝ LEVER.)

**Structural finding #2 — no kernel change is needed for any geometry sweep.** `matS2SolveStep` and
`matBeamGeom` read `lb, rF8x, rF8y, rCx, rCy` from `params[0..4]` and `ks, l0, kb` from `params[11..13]`, all
built host-side by `ExplicitMatSolveHarness.paramArr(G)` from `Glide2D` scalar fields; the base triad comes from
`frame[0..8]`. The smallest sufficient hooks are therefore host-side scene edits applied before `packExMat`,
which is exactly what was built. **The validated converter device kernels are byte-unchanged**, so §21.6's
device-residency equivalence carries over unconditionally and no new CPU/GPU gate is owed for a geometry arm.

**The four default-off hooks, and what each moves.**

- `-s2-free-length-scale s` — L → s·L at **fixed M**, so the kernel's DOF count, `sys` stride and lowering are
  untouched and the change lands in `l0 = L/M` ⇒ `ks = EA/l0`, `kb = EI/l0`: the *same* continuum beam (EA, EI
  fixed at their MD-informed values) discretized over a different span. The clamped emergence point
  `g4E = P − (L−slack)·b̂` and the substrate floor move with it; **the motor pivot P and the whole motor lawn do
  not move**, so binding geometry is not silently re-randomised.
- `-s2-bend-stiffness-scale s` — `kb → s·kb` alone, at fixed length, fixed stretch stiffness, fixed geometry.
  Deliberately a *separate* parameter (the one-factor-at-a-time requirement); they compose only if both are set.
- `-converter-f8-eccentricity-scale s` [`-converter-f8-eccentricity-compensated on`] — moves **only the F8
  material point** `rF8`, never `rConv`, so the head point `xH` and `gammaPsi` (which depends on \|rConv\|) are
  untouched: the smallest mechanically interpretable perturbation. Raw mode scales
  `rCF8_perp = rF8y − rCy`; compensated mode re-solves the axial component to hold **\|d0\| fixed**.
- `-converter-transverse-offset-nm δ` — rolls each motor's base triad **about its own b̂**. b̂ is invariant, so
  the axial stroke direction is untouched; (econv, ê_up) tilt, which displaces the converter **joint C** out of
  the motor's axial plane while the S2 pivot P stays put. `δ_roll = asin(δ / (lb·cos φ_pre))` with
  `lb·cos φ_pre = 6.9282 nm`, so ±2 nm is a ±16.8° roll — well inside the reachable arm and non-pathological.
  A **local** motor-geometry perturbation (b̂/econv/ê_up are the motor's own base directions), not a laboratory
  axis, introducing no site-frame handedness by construction; the ε = 0 and mirror controls are nevertheless the
  required empirical checks (§23.10).

A **broad general-purpose geometry system was deliberately not built**: these are five scalars and one frame
roll, all default-valued to exact no-ops (`geomScaled()` reports the state; `resetGeomScales()` restores).

### 23.7 Phase F — interface vs pivot converter-skew gauge (15°, matched seeds, n = 8) [`p4_gauge_15deg_n8_gpu.txt`]

The two gauges have, by construction (§21.2), the **identical unloaded stroke increment** — `Δr(ε) = R·Δr(0)`
either way. They differ only in the rotation CENTRE, hence only in the STATIC rest geometry at the moment a head
docks. This was the cheapest test on the list and it turned out to be the most informative one in the section.

| | **interface** (default) | **pivot** (`-converter-skew-gauge off`) |
|---|---|---|
| `J_pre` | +6.999e-26 (3.45σ, 88 %) | **+2.663e-25 (18.83σ, 100 %)** — 3.8× larger |
| `J_stroke` | −4.563e-26 (8.09σ, 100 %) | −1.968e-26 (5.82σ, 100 %) — **2.3× smaller** |
| `J_post_early` | −6.851e-26 (12.62σ, 100 %) | −1.88e-28 (**0.02σ**) — **abolished** |
| `J_post_late` | −8.818e-26 (2.07σ) | **+3.065e-25 (6.39σ, 100 %)** — **sign reversed** |
| **`J_total`** | **−1.323e-25 (3.30σ)** | **+5.530e-25 (14.64σ)** — **sign reversed, 4.2× larger** |
| `f_retain` | +2.90 | **−28.1** |
| **Ω_odd (slope)** | **−12.68 ± 3.5 (3.61σ)** | **+49.50 ± 4.6 (10.81σ)** — **sign reversed, 3.9× larger** |
| `τ_odd` | −3.675e-22 (3.24σ) | +1.604e-21 (8.59σ) |
| v_even | −2.67 µm/s | −3.94 µm/s |
| avgBound | 2.66 / 2.60 | 2.52 / 2.42 |
| pre-life / post-life (steps) | 39.3 / 281 | 37.2 / 272 |
| invalid / solver | 0 / 0 | 0 / 0 |

**Reading.** The gauge switch moves **only the static-preload channels** (`J_pre` ×3.8, `J_post_late` reversed
and ×3.5) and leaves the converter's own stroke channel qualitatively where it was (`J_stroke` same sign,
weakened 2.3×; `J_post_early` abolished). The bare pivot rotation displaces the F8 anchor by ≈ \|x̃\|·ε at the
instant of docking — the large static attachment strain §21.2 identified and deliberately gauged away — and that
strain's bound relaxation is a genuine chiral rectifier: it produces a **cycle-integrated** impulse (14.6σ,
100 % of seeds), not merely a transient.

**This is decision class E5, and it is also the E7 trap.** The pivot gauge is a **4× stronger, 10.8σ twirl** —
by far the largest population signal measured anywhere in this lineage — but:

1. its handedness is **opposite** to the converter stroke's, so it is not "more of the same mechanism";
2. its chirality is **binding-preload-borne, not stroke-borne**: it lives in `J_pre`/`J_post_late` while the
   `J_stroke`/`J_post_early` stroke channel actually *weakens*;
3. in atlas terms it is **A3 (finite-rest bound pose / chiral binding registry)** overwhelming
   **A2 (oblique vector stroke)** — a *different* R4 atlas mechanism the atlas independently ranks as
   comparable to A2;
4. it is therefore exactly the confound E7 names ("preload chirality"), and it must **not** be reported as an
   improved converter-twirling design. Reported instead as what it is: proof that in this motor the **static
   bound-pose preload, not the stroke, is the dominant chiral lever** — and a clean demonstration that the
   §21.2 interface gauge was the right default precisely because it suppresses it.

**Why this is not the §20 (T5) null.** §20's `epsBind` moved the *actin site*, so a head binding to an
already-offset site never created the strain (age-0 torque already null). The pivot gauge instead displaces the
*motor's* F8 anchor at an unmoved site, so the strain **is** created at docking and its bound relaxation carries
a net chiral coordinate displacement — the atlas master identity's requirement. The budget separates the two
cases cleanly, which is the point of building it.

### 23.8 Phase C — S2 transmission sweeps (one factor at a time, ε = ±15°, n = 8)

**Precondition, verified deterministically for EVERY geometry before any arm was believed** (the confound block
is printed per value): the unloaded converter stroke is **bit-identical across the whole sweep** —
`|Δr₀| = 8.0000 nm`, `Δr₀_axial = −8.0000 nm`, `Δr₀_tangential = −0.0000 nm`, and at ε = 15°
`|Δr| = 8.0000`, `Δr_axial = −7.7274`, `Δr_tangential = −2.0706 nm`. **Any effect seen here is therefore a pure
TRANSMISSION effect, not a stroke-length change** (§23.6, structural finding #1).

#### 23.8a S2 free length [`p5_geom_s2len_n8_gpu.txt`]

| L scale (L, l0, ks, kb) | Ω_odd ± SEM rad/s | σ | `J_stroke_odd` | `J_total_odd` | **`f_retain`** | v_even | avgB | invalid |
|---|---|---|---|---|---|---|---|---|
| 0.50× (20 nm, 5.0 nm, 0.8400, 1.44e-19) | −12.08 ± 8.5 | 1.42 | −2.887e-26 | −9.171e-26 | +3.18 | −2.221 | 2.16 | 0 |
| **0.75× (30 nm, 7.5 nm, 0.5600, 9.60e-20)** | **−25.33 ± 9.5** | 2.66 | −4.565e-26 | **−2.486e-25** | **+5.45** | −2.661 | 2.50 | 0 |
| 1.00× (40 nm, 10 nm, 0.4200, 7.20e-20) — **baseline** | −12.68 ± 3.5 | 3.61 | −4.563e-26 | −1.323e-25 | +2.90 | −2.673 | 2.63 | 0 |
| 1.25× (50 nm, 12.5 nm, 0.3360, 5.76e-20) | −11.28 ± 5.2 | 2.18 | −4.935e-26 | −1.051e-25 | +2.13 | −2.921 | 2.62 | 0 |
| 1.50× (60 nm, 15 nm, 0.2800, 4.80e-20) | −6.77 ± 5.1 | 1.33 | −5.211e-26 | −8.760e-26 | +1.68 | −2.647 | 2.49 | 0 |
| 2.00× (80 nm, 20 nm, 0.2100, 3.60e-20) | −5.11 ± 4.3 | 1.19 | −4.556e-26 | −3.273e-26 | **+0.72** | −2.118 | 1.99 | 0 |

**This is decision class E2, and it isolates the mechanism exactly.**

- **`J_stroke_odd` is FLAT** across a 4× span of S2 length (−2.89e-26 … −5.21e-26; −4.56e-26 at 0.50×, 0.75×,
  1.00× and 2.00×). The converter delivers the same per-stroke chiral impulse regardless of its tail.
- **`f_retain` falls MONOTONICALLY with S2 length** over 0.75× → 2.00×: **5.45 → 2.90 → 2.13 → 1.68 → 0.72**.
  At 2.00× (L = 80 nm) retention has collapsed below 1 — the bound cycle now *loses* impulse rather than
  reinforcing it.
- `J_total_odd` and Ω_odd track `f_retain` (they must, since `J_stroke` is flat): Ω_odd −25.33 → −5.11.
- **Physical reading, in the atlas's own terms.** The master identity says the cycle impulse is set entirely by
  the net chiral-coordinate displacement accrued **while bound**. A long, compliant S2 lets the *motor's own
  base* take up that displacement instead of the actin site, so less of it is delivered to the filament as
  impulse. Shortening the tail stiffens the load path and the retention nearly doubles. **The compliance that
  absorbs the chiral impulse is the S2 beam, and it acts on the RETAINED bound displacement — not on the
  stroke.**
- **Not a confound:** unloaded stroke bit-identical; gliding essentially unchanged at the finalist (v_even
  −2.673 → −2.661, +0.4 %); engagement −5 %; 0 invalid / 0 solver at every length; S2 bend energy at stroke
  3.5–4.0 kT throughout.
- **Non-monotone at the short end.** 0.50× (L = 20 nm) *reverses* the trend (Ω −12.08, `f_retain` 3.18, 1.42σ)
  with the sweep's worst engagement (avgB 2.16) and a degraded `J_stroke_odd` (−2.89e-26): at 20 nm the tail is
  short and stiff enough to start limiting the head's reach to the lattice. The optimum sits near
  **0.75× (L = 30 nm)**, not "as short as possible".

#### 23.8b S2 bending stiffness [`p6_geom_s2bend_n8_gpu.txt`]

`kb → s·kb` alone, at fixed L = 40 nm, fixed `ks = 0.4200 N/m`, fixed geometry. Unloaded stroke again
bit-identical across the sweep.

| kb scale (kb, N·m) | Ω_odd ± SEM rad/s | σ | `J_stroke_odd` | `J_total_odd` | `f_retain` | v_even | avgB | invalid |
|---|---|---|---|---|---|---|---|---|
| 0.50× (3.600e-20) | −0.57 ± 6.9 | 0.08 | −4.006e-26 | −5.169e-26 | +1.29 | −3.048 | 2.68 | 0 |
| 0.75× (5.400e-20) | −9.86 ± 7.1 | 1.39 | −4.116e-26 | −1.341e-25 | +3.26 | −2.914 | 2.61 | 0 |
| 1.00× (7.200e-20) — **baseline** | −12.68 ± 3.5 | 3.61 | −4.563e-26 | −1.323e-25 | +2.90 | −2.673 | 2.63 | 0 |
| 1.50× (1.080e-19) | −6.52 ± 7.1 | 0.92 | −4.568e-26 | −7.573e-26 | +1.66 | −3.021 | 2.69 | 0 |
| 2.00× (1.440e-19) | −12.48 ± 8.8 | 1.42 | −4.741e-26 | −1.814e-25 | +3.83 | −2.897 | 2.63 | 0 |

**NULL.** `f_retain` scatters non-monotonically (1.29 / 3.26 / 2.90 / 1.66 / 3.83), every off-baseline arm is
≤ 2.5σ, and the matched-seed deltas (dΩ_odd +0.19 … +12.1 against SEMs of 7–9) are entirely within noise.
`J_stroke_odd` is flat again. Gliding and engagement unchanged; 0 invalid / 0 solver everywhere.

**This null is informative, not merely negative.** The `s2len 0.75×` finalist changes **both** ks (0.4200 →
0.5600 N/m) and kb (7.20e-20 → 9.60e-20) **and** the span (40 → 30 nm). This sweep reaches a **larger** kb
(1.08e-19 at 1.5×, 1.44e-19 at 2.0×) with no effect at all. ⇒ **The S2 compliance that absorbs the retained
chiral impulse is the AXIAL/span path, not the bending path.** The Phase-C hypothesis ("shorter *or* stiffer
S2") is only half right: shorter helps, stiffer-in-bending does not.

### 23.9 Phase D — converter/F8 eccentricity sweep (ε = ±15°, n = 8)

**The clean geometric quantity.** The converter swings the F8 anchor on `d0` about the joint C. The zero-skew
stroke is AXIAL (≈ ∓b̂), so the in-plane component of `d0` perpendicular to the stroke axis is
`rCF8_perp = rF8y − rCy` = **3.000 nm** canonically (against `rCF8_axial` = 7.000 nm, `|d0|` = 7.6158 nm).
Only the **F8 material point** is moved — never `rConv` — so `xH` and `gammaPsi` are untouched (§23.6).

**The confound control, run first and for every geometry** [`p7_geom_ecc_n8_gpu.txt`]:

| ecc | `rCF8_perp` | `\|d0\|` | unloaded `\|Δr₀\|` | `Δr₀_axial` | `Δr_tangential(15°)` | loaded F_ax | loaded F_tan | loaded τ_ax |
|---|---|---|---|---|---|---|---|---|
| 0.75× | 2.250 nm | 7.3527 | **8.0000** | **−8.0000** | **−2.0706** | +5.134e-12 | +1.611e-13 | +5.639e-22 |
| 1.00× | 3.000 nm | 7.6158 | **8.0000** | **−8.0000** | **−2.0706** | +4.848e-12 | +4.601e-13 | +1.610e-21 |
| 1.25× | 3.750 nm | 7.9412 | **8.0000** | **−8.0000** | **−2.0706** | +4.296e-12 | +5.752e-13 | +2.013e-21 |
| 1.50× | 4.500 nm | 8.3217 | **8.0000** | **−8.0000** | **−2.0706** | +3.594e-12 | +5.467e-13 | +1.913e-21 |

**The unloaded stroke is bit-identical at every eccentricity**, so the raw sweep is ALREADY
stroke-magnitude-matched: eccentricity cannot be a disguised stroke-length change, because `|d0|` never enters
the stroke (§23.6). What eccentricity *does* change is the **loaded** mechanics, strongly and monotonically:
the single-motor tangential force rises 3.6× and the chiral torque 3.6×, while the axial force falls 30 %.

| ecc | Ω_odd ± SEM rad/s | σ | `J_stroke_odd` | `J_total_odd` | `f_retain` | **v_even** | avgB | invalid |
|---|---|---|---|---|---|---|---|---|
| 0.75× | −13.92 ± 7.0 | 1.98 | −4.931e-26 | −1.057e-25 | +2.14 | −2.363 | 2.56 | 0 |
| 1.00× — **baseline** | −12.68 ± 3.5 | 3.61 | −4.563e-26 | −1.323e-25 | +2.90 | −2.673 | 2.63 | 0 |
| 1.25× | −7.14 ± 2.5 | 2.87 | −4.488e-26 | −5.335e-26 | +1.19 | **−3.785** | 2.37 | 0 |
| 1.50× | −17.55 ± 6.0 | 2.92 | −4.513e-26 | −1.853e-25 | +4.11 | **−5.691** | 2.53 | 0 |

**NULL, and the clearest E7 illustration in the section.** `J_stroke_odd` is flat (tripling the per-motor
deterministic torque does **not** raise the per-stroke population impulse); Ω_odd is **non-monotone** with every
off-baseline arm ≤ 2.9σ; and the one arm that *looks* better (1.50×) runs at **2.1× the baseline glide speed**
(−5.691 vs −2.673 µm/s), a materially different mechanical regime, so the comparison is void by the task's own
rule. This is the textbook demonstration of *"a larger instantaneous torque alone is not success"*.
**Not advanced.**

#### 23.9b `|d0|`-compensated eccentricity [`p8_geom_ecccomp_n8_gpu.txt`]

The pre-registered stroke-magnitude-matched control: `rCF8_perp` scaled while the axial component is re-solved
to hold **`|d0|` = 7.6158 nm exactly** (rCF8_axial 7.2758 / 7.0000 / 6.6285 / 6.1441 nm).

| ecc (perp) | Ω_odd ± SEM rad/s | σ | `J_stroke_odd` | `J_total_odd` | `f_retain` | v_even | avgB | invalid |
|---|---|---|---|---|---|---|---|---|
| 0.75× (2.250 nm) | −17.50 ± 5.8 | 3.00 | −5.239e-26 | −1.573e-25 | +3.00 | −2.422 | 2.68 | 0 |
| 1.00× (3.000 nm) — **baseline** | −12.68 ± 3.5 | 3.61 | −4.563e-26 | −1.323e-25 | +2.90 | −2.673 | 2.63 | 0 |
| 1.25× (3.750 nm) | +1.56 ± 6.7 | 0.23 | −4.365e-26 | +1.178e-26 | −0.27 | **−4.126** | 2.56 | 0 |
| 1.50× (4.500 nm) | −15.84 ± 4.4 | 3.61 | −4.456e-26 | −1.966e-25 | +4.41 | **−5.416** | 2.51 | 0 |

**NULL, same shape as the raw sweep** — non-monotone (one arm crosses zero at 0.23σ), the large-eccentricity
arms again carry a 1.5–2× glide change, and the best-behaved arm (0.75×, `dΩ_odd` = −4.83) is under 1σ of its
own SEM. As the §23.9 confound table predicted, compensation changed nothing that mattered, because `|d0|`
never entered the stroke. **Not advanced.**

### 23.10 Phase E — converter transverse offset [`p9_geom_trans_n8_gpu.txt`]

**What is shifted, stated before the result** (§23.6): the motor's base triad is rolled about **its own b̂**, so
b̂ — and hence the axial stroke direction — is invariant while (econv, ê_up) tilt, displacing the converter
**joint C** out of the motor's axial plane by the requested amount at the reference pose. The S2 pivot P, the
beam, the motor lawn and the actin site all stay exactly where they are.

**Safety properties, verified:**
- **Unloaded stroke bit-identical at every offset** (8.0000 / −8.0000 / −2.0706 nm) — a roll about b̂ cannot move
  the axial stroke.
- **The offset introduces no handedness of its own:** Ω_odd stays NEGATIVE at −2, −1, 0 and +2 nm (the +1 nm arm
  is unresolved at 0.60σ). ±δ do not produce opposite rolls; the ε-odd sign is set by ε, not by δ.
- 0 invalid / 0 solver at every offset; engagement 2.41–2.69; glide −2.55 … −3.10 µm/s.

| δ (nm) | Ω_odd ± SEM rad/s | σ | `J_stroke_odd` | `J_total_odd` | `f_retain` | v_even | avgB |
|---|---|---|---|---|---|---|---|
| −2.00 | −13.88 ± 6.6 | 2.11 | −3.535e-26 | −8.954e-26 | +2.53 | −3.096 | 2.69 |
| −1.00 | −10.70 ± 5.7 | 1.87 | −4.407e-26 | −8.942e-26 | +2.03 | −2.843 | 2.58 |
| **0.00 — baseline** | −12.68 ± 3.5 | 3.61 | −4.563e-26 | −1.323e-25 | +2.90 | −2.673 | 2.63 |
| +1.00 | −5.33 ± 8.9 | 0.60 | −4.343e-26 | +9.590e-27 | −0.22 | −2.775 | 2.50 |
| +2.00 | −10.62 ± 5.0 | 2.13 | −4.643e-26 | −1.013e-25 | +2.18 | −2.554 | 2.41 |

**NULL — decision class E4 refuted.** No offset improves Ω_odd, `J_total_odd` or `f_retain`; every off-baseline
arm is ≤ 2.13σ and every matched-seed `dJ_total_odd` is within noise. `J_stroke_odd` is flat once more.
± offsets are **not** equivalent in detail (−2 nm and +2 nm differ), as anticipated — the motor geometry has no
reflection symmetry about its axial plane once the head is docked stereospecifically — but neither direction is
a lever.

*Caveat on the deterministic probe.* The per-offset single-motor loaded probe is erratic across the sweep
(τ_ax −2.89e-22, +1.30e-21, +1.61e-21, +1.84e-21, −6.33e-22 at −2/−1/0/+1/+2 nm) because moving the converter
block changes which lattice site the single probe motor docks to. It is a per-configuration diagnostic, not an
ensemble statement; only the 8-seed live arms above carry the verdict.

### 23.11 Powered finalist — S2 free length 0.75× (L = 30 nm), 24 matched seeds

**One finalist only.** Of the five geometry axes swept, four were nulls (§23.8b, §23.9, §23.9b, §23.10). The S2
free length was the single axis with a monotone, mechanism-consistent effect, so it alone was powered — inside
the "no more than two finalists" limit. `-conv-geom-sweep s2len -conv-geom-values "1.0,0.75" -seeds 24`
[`f1_final_s2len0.75_head2head_n24_gpu.txt`], **both arms in ONE run so the seeds are matched by construction**.

| quantity | baseline L = 40 nm | **finalist L = 30 nm** | Δ (matched seed) |
|---|---|---|---|
| **Ω_odd (slope)** | −6.96 ± 3.17 rad/s (**2.19σ**), 16/24 | **−19.18 ± 4.77 rad/s (4.02σ)**, 20/24 | **−12.23 (×2.76)** |
| τ_odd | −2.106e-22 N·m (2.28σ) | −5.905e-22 N·m (**4.44σ**), 20/24 | |
| `J_pre` | +7.468e-26 (7.75σ, 96 %) | +7.641e-26 (7.50σ, 92 %) | +2 % — **unchanged** |
| `J_stroke` | −4.708e-26 (19.76σ, 100 %) | −4.739e-26 (14.50σ, 100 %) | **−3.15e-28 (0.7 %)** |
| `J_post_early` | −6.255e-26 (12.11σ, 100 %) | −7.720e-26 (14.36σ, 100 %) | +23 % |
| **`J_post_late`** | −3.359e-26 (1.12σ, 58 %) | **−1.257e-25 (3.30σ, 75 %)** | **×3.74** |
| **`J_total`** | −6.853e-26 (2.05σ, 67 %) | **−1.739e-25 (4.03σ, 79 %)** | ×2.54 |
| **`f_retain`** | +1.456 | **+3.669** | **+2.213** |
| v_even | −2.688 µm/s | −2.675 µm/s | **+0.013 (0.5 %)** |
| avgBound | 2.72 | 2.54 | −6.6 % |
| invalid / solver | 0 / 0 | 0 / 0 | |

**Independent validation of the baseline arm.** At n = 24 the baseline reproduces §22.8's 24-seed shared-native
arm **exactly** — Ω_odd slope −6.956 vs −6.96 rad/s, τ_odd −2.1055e-22 vs −2.11e-22, 2.28σ vs 2.28σ. The n = 8
pilot's −12.68 was a high draw (the same seed-count effect §22.6 documented in the other direction); the powered
baseline is −6.96, and the finalist beats it by **2.76×**.

**The improvement is retention, and only retention — localised to the late bound tail.** `J_stroke_odd` is
unchanged to **0.7 %** (Δ = −3.15e-28 against a −4.7e-26 signal) and `J_pre` to 2 %; every bit of the gain sits
in `J_post_late` (×3.74) with a secondary contribution from `J_post_early` (+23 %). The unloaded stroke is
bit-identical. So this is not a bigger stroke, not a different preload, and not a changed duty cycle:
**a compliant S2 bleeds off the retained chiral bound displacement over the ~0.7 ms post-stroke residence, and
shortening the tail stops the bleed** — precisely the quantity the atlas master identity says sets the cycle
impulse.

**Costs.** Gliding is untouched (v_even −2.688 → −2.675 µm/s, +0.5 %, well inside seed scatter), so this is
*not* the "larger roll caused only by slower translation" artifact. Engagement falls 6.6 % (avgBound 2.72 →
2.54) — a real but small cost consistent with a shorter tail reaching slightly less of the lattice, and it does
not touch the per-stroke impulse. No instability: 0 invalid, 0 solver failures.

**ε = 0 achirality control at the finalist geometry, n = 24**
[`f2_final_s2len0.75_budget_n24_gpu.txt`]: τ = +5.194e-23 ± 1.5e-22 (**0.3σ**, 14/24 seeds),
Ω endpoint +1.602 rad/s, Ω slope +3.92 ± 4.9 (**0.8σ**), Q_omega = 1.000, `cancel` 545 (a near-zero mean torque
by construction), 0 invalid. The ε = 0 half-split NULL budget gives **every component ≤ 0.92σ**
(`J_stroke` +3.14e-27 ± 3.43e-27 vs the finalist's −4.74e-26 at 14.5σ; `J_total` +3.34e-26 ± 5.37e-26 vs
−1.74e-25 at 4.03σ). **The finalist geometry is achiral at ε = 0**, so its twirl is carried by the converter
skew and not by the geometry change.

**Biological standing (stated, not oversold).** L = 30 nm is inside the range the explicit-S2 model was built to
span (`EXP4G_L_NM` = {10, 20, 40, 60} nm, all MD-informed from the same EA/EI), so this is a
*re-parameterisation within the model's own validated envelope*, not an exotic geometry. It is nevertheless a
**diagnostic finding about where the chiral impulse is stored, not a proposal to change the canonical
`explicit-s2-l40` motor** — `MotorModel`'s frozen L40 descriptor is untouched (§23.15).

> **READ §23.13b BEFORE USING THIS RESULT.** Under timestep refinement the advantage does not reproduce: at
> dt/2 the two geometries are statistically indistinguishable, because the BASELINE's own `J_post_late` rises
> ×1.86 at dt/2 and closes the gap. The production-dt baseline appears to under-resolve the compliant tail's
> late bound-phase torque, so **the ×2.76 gain is substantially numerical**. The physics it localises (a
> compliant S2 holds less retained chiral displacement) survives; the design claim does not.

### 23.12 Mirror and randomized-base controls at the finalist geometry (L = 30 nm, ε = ±15°, n = 16)

`-conv-controls -converter-stroke-skew-deg 15 -s2-free-length-scale 0.75 -seeds 16`
[`f3_final_s2len0.75_controls_n24_gpu.txt`]. Pre-registered requirements: the mirrored lattice must **REVERSE**
Ω_odd and `J_odd[0-7]`, and the randomized-base arm must **preserve the SIGN** with a reduced magnitude (the
§22.8 pattern at the baseline geometry). **Both hold.**

| arm | τ_odd (N·m) | σ | seeds same sign | Ω_odd (rad/s) | `J_odd[0-7]` | v_even | avgB |
|---|---|---|---|---|---|---|---|
| SHARED **native** | −7.140e-22 | **4.43** | 14/16 | **−23.42 ± 6** | **−5.056e-26** | −2.41 / −2.78 | 2.43 / 2.46 |
| SHARED **MIRROR** | **+5.381e-22** | 2.87 | 11/16 | **+18.68 ± 6** | **+4.422e-26** | −2.95 / −2.53 | 2.45 / 2.57 |
| **RANDOMIZED** base | −1.868e-22 | 1.00 | 10/16 | −4.44 | — | | |

- **Mirror control (the chirality claim): Ω_odd REVERSES** — native −23.42 → mirror **+18.68** — and
  **`J_odd[0-7]` REVERSES** — native −5.056e-26 → mirror **+4.422e-26**. The mirrored ε-ODD torque is resolved at
  **2.87σ**. The finalist geometry therefore carries the *same* site-frame chirality as the baseline, at ~2.7×
  the magnitude; it did not manufacture a new, non-chiral signal.
- **Randomized-base control (locality): the sign SURVIVES** base randomization — native(shared) −23.42,
  randomized −4.44, both negative — with the expected magnitude and resolution drop (1.00σ, 10/16), exactly the
  §22.8 pattern. The effect is a **LOCAL-frame** one, not a shared-base artifact.
- Q_omega = 1.000 in every arm; 0 invalid, 0 solver failures.

**⇒ Success-gate criterion #4 PASSES at the finalist geometry.**

*Standing baseline controls, already established and unaffected by this section:* §22.8 mirror reversal at
24 seeds (Ω_odd native −6.96 → mirror +11.89; `J_odd[0-7]` −4.77e-26 → +4.51e-26 with **24/24 seeds flipping**),
§22.6 ε = 0 at 48 seeds (Ω +0.12, 0.1σ), §21.5 fixture 204 (randomized base keeps the ε-ODD sign) and
fixture 205 (`Ractin → 0` removes the moment arm).

### 23.13 Timestep check (dt vs dt/2 at matched 20 ms, n = 8) — **the mechanism survives; the finalist's advantage does NOT**

`-conv-budget -halfdt -steps 16000`, baseline [`f4_dt_baseline_half_n8_gpu.txt`] and finalist
[`f5_dt_s2len0.75_half_n8_gpu.txt`]. **Magnitude bias is documented separately from sign and interpretation**,
per §22.9.

#### 23.13a Baseline geometry: what is dt-robust and what is not

| quantity | dt = 2.5e-6 (8000 steps) | dt/2 = 1.25e-6 (16000 steps) | ratio |
|---|---|---|---|
| **`J_pre`** | +6.999e-26 (3.45σ) | +7.021e-26 (4.81σ, 100 %) | **×1.003** |
| `J_stroke` (lags 0–7) | −4.563e-26 (8.09σ) | −1.118e-26 (5.48σ) | ×0.24 † |
| `J_post_early` (lags 8–31) | −6.851e-26 (12.62σ) | −6.120e-26 (23.17σ) | ×0.89 |
| `J_post_late` (lags ≥32) | −8.818e-26 (2.07σ) | **−1.639e-25 (3.87σ)** | **×1.86** |
| **`J_total`** | −1.323e-25 (3.30σ) | −1.661e-25 (3.01σ) | **×1.26** |
| `f_retain` | +2.90 | +14.86 † | — |
| Ω_odd (slope) | −12.675 ± 3.5 (3.61σ) | −14.502 ± 5.9 (2.45σ) | ×1.14 |
| τ_odd | −3.675e-22 (3.24σ) | −4.887e-22 (3.11σ) | ×1.33 |
| v_even | −2.673 µm/s | −2.718 µm/s | ×1.02 |
| **closure** (rate × `J_total` / τ_odd) | **1.05** | **1.12** | — |
| invalid / solver | 0 / 0 | 0 / 0 | |

† **`J_stroke` and therefore `f_retain` are NOT dt-invariant by construction**: the stroke window is defined in
*steps*, so at dt/2 "lags 0–7" spans half the physical time (§22.9 documented the same for the windowed `J_θ`).
`f_retain`'s jump to +14.86 is a shrinking denominator, not improved retention. **The dt-comparable quantities
are `J_pre`, `J_total` and the closure**, all of which are stable.

**What IS dt-robust — i.e. every Phase-A conclusion:**
- the **sign and channel structure** are identical at both dt (`J_pre` positive/opposing; `J_stroke`,
  `J_post_early`, `J_post_late` all negative; 100 % seed coherence on the resolved channels);
- **`J_pre`, the loss channel, is dt-stable to 0.3 %** — the single most important number in §23.16(2);
- **`J_total` is dt-stable to 26 %** and its sign, resolution (3.0–3.3σ) and seed coherence hold;
- **the closure holds at both dt** (1.05 → 1.12), so §23.16(1) — "the impulse is not lost, the stroke window
  under-counts it" — is a dt-independent statement;
- Ω_odd stays negative and resolved; its magnitude carries a +14 % (slope) / +33 % (τ) shift, the same family and
  size as §22.9's documented ~33 % dt-sensitivity. gliding is dt-stable to 2 %.

#### 23.13b Finalist vs baseline AT dt/2 — the advantage is not confirmed

Both arms n = 8, identical seeds, matched 20 ms:

| | baseline dt/2 | finalist (L = 30 nm) dt/2 | Δ |
|---|---|---|---|
| Ω_odd (slope) | −14.502 ± 5.9 (2.45σ) | −12.212 ± 4.5 (2.71σ) | **+2.29 — ≪ 1σ** |
| τ_odd | −4.887e-22 (3.11σ) | −3.037e-22 (1.61σ) | |
| `J_total` | −1.661e-25 (3.01σ) | −1.042e-25 (2.30σ) | |
| `J_post_late` | −1.639e-25 | −9.392e-26 | |
| `J_pre` | +7.021e-26 | +6.474e-26 | |
| v_even | −2.718 | −2.614 | |
| closure | 1.12 | 1.06 | |

**At dt/2 the two geometries are statistically indistinguishable** (ΔΩ_odd = +2.29 against SEMs of 4.5–5.9), and
the finalist's central value is if anything slightly *worse*. The reason is visible in the budget: **refining dt
does to the BASELINE almost exactly what shortening S2 did at production dt.** The baseline's `J_post_late`
rises ×1.86 at dt/2 (−8.8e-26 → −1.64e-25) and its `J_total` reaches −1.661e-25 — essentially the finalist's
production-dt value (−1.739e-25 at n = 24).

**Honest reading.** The production-dt baseline appears to **under-resolve the late bound-phase torque of the
compliant S2 tail**, and a shorter/stiffer tail (shorter relaxation time) is better resolved at the same dt. So
the §23.11 finalist gain is **substantially a numerical-resolution effect, not a demonstrated mechanical
improvement**. What survives refinement is the *physics* — a compliant tail does hold less retained chiral
displacement, and both a finer dt and a shorter tail recover it — but **"L = 30 nm is a better twirling motor"
is NOT established**, and the ×2.76 headline must not be carried forward as a design claim.

**Success-gate criterion #10: PARTIAL.** Sign, channel decomposition, `J_pre` and the closure are dt-robust
(the Phase-A conclusions stand unchanged); the finalist's *advantage over baseline* is not confirmed at dt/2 at
n = 8. This is a limitation of the geometry result, not of the budget.

**Follow-up this creates.** The late bound-phase dynamics of the explicit-S2 tail are dt-sensitive at the
production timestep. That is the same family as the project's standing cross-bridge sub-step work
(`docs/…SUBSTEP…`, the `-xbimplicit*`/`-rotimplicit`/`-ratefix` lineage): before any S2-geometry claim is made,
the twirl observable should be re-measured under the sub-step, or the finalist re-run at dt/2 with n ≥ 24.

### 23.14 Success gate for "an improved twirling design"

| # | criterion | S2 length 0.75× |
|---|---|---|
| 1 | direct body-fixed Ω_odd improves significantly over baseline | **PASS** — −6.96 (2.19σ) → **−19.18 (4.02σ)**, ×2.76, n = 24 matched |
| 2 | the improvement is explained by improved `J_total`, `f_retain` or `eta_pop` | **PASS** — `J_total` ×2.54, `f_retain` +1.46 → +3.67, with `J_stroke` unchanged to 0.7 % |
| 3 | per-stroke chirality remains correct | **PASS** — `J_stroke_odd` −4.739e-26 at 14.50σ, 100 % of seeds |
| 4 | mirror reverses the signal | **PASS** — Ω_odd −23.42 → **+18.68**; `J_odd[0-7]` −5.056e-26 → **+4.422e-26**; mirrored ε-ODD resolved at 2.87σ (§23.12) |
| 5 | ε = 0 stays achiral | **PASS** — Ω +1.60 endpoint / +3.92 ± 4.9 slope (0.8σ), τ 0.3σ, null budget ≤ 0.92σ, n = 24 |
| 6 | gliding remains healthy | **PASS** — v_even −2.688 → −2.675 µm/s (+0.5 %) |
| 7 | engagement does not collapse | **PASS** — avgBound 2.72 → 2.54 (−6.6 %) |
| 8 | no numerical instability | **PASS** — 0 invalid, 0 solver failures, no fallback, in every arm of every sweep |
| 9 | force / torque / work / energy accounting closes | **PASS** — §21.3 F8 pair closed (0.00e+00) and energy closes at every angle; unchanged here (no kernel touched); the budget's own closure reproduces `τ_odd` to 1.01–1.05 (§23.5) |
| 10 | survives dt/2 in sign and interpretation | **PARTIAL** — the mechanism does (sign, channel structure, `J_pre` to 0.3 %, closure 1.05 → 1.12); the **finalist's advantage does NOT** (at dt/2, n = 8, baseline −14.50 ± 5.9 vs finalist −12.21 ± 4.5, ΔΩ ≪ 1σ) (§23.13) |

**9 of 10 criteria pass; #10 is PARTIAL and it is the one that matters for adoption.** The dt/2 check shows the
baseline's own `J_post_late` rises ×1.86 under refinement, closing most of the gap, so the ×2.76 headline is
**substantially a numerical-resolution effect**. The geometry finding is therefore reported as a *diagnostic
localisation of where the chiral impulse is stored*, **not** as a validated improved design (§23.13b).

The randomized-base locality control is not a numbered gate but is reported alongside: the sign SURVIVES base
randomization at the finalist geometry (native −23.42, randomized −4.44, both negative), so the effect is
local-frame, not shared-base (§23.12).

### 23.15 Regression — nothing pre-existing moved

| suite | command | result |
|---|---|---|
| 24 head-roll / site / registry fixtures (incl. **[8] all-flags-off bit-identity**) | `-fixtures` | **24 / 24 PASS** |
| converter Stage-3 symmetry (incl. **209 ε = 0 byte-identity**, 204 randomized base, 205 Ractin → 0) | `-conv-fixtures` | **8 / 8 PASS** |
| converter Stage 1/2 deterministic trajectory + force/torque/energy closure | `-conv-stage1` | **8 / 8 PASS** |
| converter-skew gliding graph, device-resident, no fallback | `-conv-equiv` | **PASS** — 200 device-resident steps, bindMism=0, convFlagMism=0, max\|dConvFrame\|=5.61e-07, max\|dSegTorque\|=1.49e-24 N·m, max\|dFilCoord\|=5.96e-08 µm, firstDiv=none, bound CPU=9/GPU=9 — **digit-identical to §21.6**, proving the device path is unchanged (log `r2_conv_equiv_gpu.txt`) |

(logs `RUN_LOGS/chiral_sites/r1_regression_cpu.txt`, `r2_conv_equiv_gpu.txt`)

The new work is **additive and measurement-side**: one host-side episode ledger (`ChiralSiteHarness.Ledger`),
one analysis class (`ConvBudget`), four new drivers, four default-off scene scalars plus one default-off
base-triad roll, and **one copy-out-list flag** (`EPISODE_TELEM`). No device kernel, no TaskGraph task, no
buffer width, no task ordering, no canonical default, no parameter, no chemistry, no dt, no RNG stream, no
`MotorModel.CANON_VERSION`. `applyGeomScales` and the frame roll are exact no-ops at their defaults.
`BoA-v1ref` is byte-clean; the canonical explicit-S2 production path is untouched.

**Instrumentation-neutrality control (the one that matters here).** The `-conv-budget` arms, which add `q`,
`nodes` and `outGeom` to the production copy-out set, reproduce §22.3's 15°/n = 8 pilot **digit for digit**
(Ω_odd slope −12.675 vs −12.68, 3.61σ; `tauOdd` −3.675e-22; vEven −2.67; avgBound 2.66/2.60) and §22.8's
24-seed shared-native arm exactly (−6.956 vs −6.96; 2.28σ). Adding copy-outs changed nothing, as it must.

### 23.16 Mechanistic conclusion

1. **The premise of the investigation is false, and that is the finding.** The chiral impulse is not lost
   between the stroke event and the population mean. The stroke-conditioned window used in §21.8/§22.7 captures
   only **15–47 %** of a bound episode's chiral angular impulse; the remainder accrues over the ~0.7 ms
   post-stroke residence **with the same sign**. Once the full bound cycle is accounted for,
   `episode rate × J_total_odd` reproduces the measured population torque to **1.01–1.05 at 15–30°** (1.26 at
   5°). `f_retain` = +1.46…+8.19, not < 1. **Decision class E1 (late-bound recoil) is refuted; E6 (population
   cancellation) is refuted; the motor is atlas class A, not the atlas's conservative-failure class A5/A6.**
2. **The single loss channel is the PRE-stroke bound dwell.** `J_pre` is opposite-signed and grows **faster
   than sin ε** (×3.40 at 15°, ×7.83 at 30° against sin ratios 2.97/5.74), cancelling **45 % → 61 % → 77 %** of
   the sin ε-scaling stroke channel at 5°/15°/30°. That, plus an **ε-independent late-bound pedestal**
   (`J_post_late`, 83 % of `J_total` at 5°), is the true mechanism of §22.4's sub-sin Outcome B — not the
   "dilution over the bound population" §22.4 assumed. The stroke channel itself (`J_stroke + J_post_early`)
   scales ∝ sin ε cleanly all the way to 30°.
3. **The dominant chiral lever in this motor is the static bound-pose preload, not the stroke.** Switching the
   rotation-centre gauge — which by construction leaves the unloaded stroke increment identical — moves `J_pre`
   by 3.8×, reverses `J_post_late`, reverses `J_total` (−1.32e-25 → +5.53e-25, 14.6σ) and reverses and
   quadruples Ω_odd (−12.7 → +49.5 rad/s, 10.8σ), while the stroke channel merely weakens. In atlas terms the
   pivot gauge lets **A3 (chiral binding registry)** overwhelm **A2 (oblique stroke)**. It is a bigger twirl but
   a *different, opposite-handed mechanism* — the E7 "preload chirality" confound, reported as such and
   explicitly not adopted.
4. **Motor geometry cannot change the stroke, only its transmission.** The unloaded converter stroke is
   bit-identically 8.0000 nm across every geometry tested, because the bound head's ψ is pinned to actin by the
   binding spring and the stroke is the **neck-lever swing at radius lb = 8 nm**, not the converter arm
   \|d0\| = 7.6158 nm. Consequently four of the five geometry axes are nulls, and the one that works
   (**S2 free length**) works **entirely through retention**: `f_retain` falls monotonically with S2 length
   (5.45 → 0.72 over 0.75×→2.00×), and the powered finalist improves Ω_odd **×2.76** with `J_stroke` unchanged
   to 0.7 %, all of the gain in `J_post_late` (×3.74), at zero glide cost. **The compliance that absorbs the
   retained chiral bound displacement is the S2 beam's axial/span path** — the bend-stiffness sweep is a null
   even at a larger kb, which localises it.
5. **But that geometry gain does not survive timestep refinement, and the honest verdict is that it is largely
   numerical.** At dt/2 the baseline's own `J_post_late` rises ×1.86 and the two geometries become
   indistinguishable (§23.13b). The production timestep under-resolves the compliant tail's late bound-phase
   torque; a shorter tail is simply better resolved at the same dt. **So the answer to "can motor geometry
   improve the transmission?" is: not demonstrably.** Four of five axes are outright nulls, and the fifth is a
   dt artifact to first order. What the geometry sweep *did* deliver is the mechanistic localisation in (4) —
   which channel the impulse lives in and which compliance absorbs it — and that is dt-robust.

### 23.17 Decision class

**Primary: E5 (gauge/static preload controls population efficiency) + E2 (S2 compliance absorbs the chiral
impulse — as physics, but not as a validated design gain), on top of an explicit refutation of the task's
framing premise.** If a single class must be named for the *actionable* outcome it is **E5**: the static
bound-pose preload is the dominant lever and the one opposing channel, and no geometry axis rescues anything
that was not already accounted for.

| class | verdict |
|---|---|
| **E1** late-bound recoil is the dominant loss | **REFUTED** — `J_recoil` is SAME-signed; `f_retain` = +1.46…+8.19 |
| **E2** S2 compliance absorbs the chiral stroke | **CONFIRMED as physics, NOT as a design gain** — `f_retain` monotone in S2 length (5.45 → 0.72), finalist ×2.76 Ω_odd / ×2.54 `J_total` with `J_stroke` flat at production dt; but at dt/2 the baseline closes the gap (`J_post_late` ×1.86) and the arms become indistinguishable ⇒ largely a resolution effect (§23.13b) |
| **E3** eccentricity improves mechanical advantage | **REFUTED** — non-monotone, ≤2.9σ, glide-confounded at the large end; raw and compensated both null |
| **E4** transverse offset improves productive geometry | **REFUTED** — no offset beats baseline; ≤2.13σ |
| **E5** gauge/static preload controls population efficiency | **CONFIRMED, and dominant** — pivot gauge reverses and quadruples Ω_odd (10.8σ) purely via the preload channels |
| **E6** population cancellation dominates, geometry cannot rescue | **REFUTED** — the budget closes to ~1.0; there is no population cancellation |
| **E7** an apparent improvement is a confound | **OBSERVED TWICE and flagged** — the pivot gauge (preload chirality, opposite handedness) and eccentricity 1.25×/1.50× (1.4–2.1× glide change). Neither adopted. The S2-length finalist is explicitly NOT E7: unloaded stroke bit-identical, `J_stroke` flat to 0.7 %, glide unchanged to 0.5 %, ε = 0 achiral at 0.8σ. |
| **E8** numerical or GPU failure | **NOT OBSERVED** — 0 invalid, 0 solver failures, no fallback, in every arm of every run in this section |

### 23.18 Honest limits

- `J_total`/`J_post_late` are resolved (3.3–4.0σ, 79–100 % of seeds) but **tail-noise-limited**: the ε = 0
  half-split null reaches 0.62–1.13σ on `J_total`, so `J_total`'s SEM is of the same order as the null's.
  `J_stroke` and `J_post_early` are sharply resolved (8–20σ; null 0.15–0.92σ).
- The closure is 1.01–1.26, not 1.00; the residual is consistent with the two censoring exclusions, which
  preferentially remove long-lived episodes and therefore `J_post_late`. It is a ~10–25 % accounting, not a
  conservation law.
- 15° (and 30°) are large, non-biological skews. Everything here is a mechanism signal-strength result on a
  simplified assay (one rigid segment, filament Brownian OFF); the roll-per-distance numbers stay diagnostic.
- The geometry pilots are n = 8, where a doubled central value comes with a doubled SEM (the `s2len 0.75×` pilot
  read −25.33 ± 9.52 at 2.66σ; the powered n = 24 value is −19.18 ± 4.77 at 4.02σ). Only the finalist was
  powered; the four nulls are nulls **at n = 8**, and a small effect below that resolution cannot be excluded.
- `eta_energy` is a transparent proxy (`W_chiral`/\|`W_F8`\|), not a thermodynamic efficiency: no chemical
  free-energy accounting is available, and its ~1e-4 magnitude is set by the filament's roll-vs-axial mobility
  ratio on a 3.5 nm arm, not by the mechanism.

### 23.19 Exact next recommended experiment — target `J_pre`, not the stroke and not the geometry

The budget names the target unambiguously. Three channels, three ε-laws (§23.5):

| channel | ε-scaling | sign | fixable by |
|---|---|---|---|
| `J_stroke + J_post_early` | ∝ sin ε | correct | already works — nothing to fix |
| **`J_pre`** | **super-sin (×3.40 / ×7.83)** | **OPPOSING** | **the next experiment** |
| `J_post_late` | ε-independent | correct | S2 length moves it at production dt, but dt/2 shows that is largely resolution (§23.13b) — treat as OPEN |

`J_pre` exists because the converter frame is rotated **while the head is docked in the pre-stroke (ADP·Pi)
state**, so the bound rest pose is already displaced tangentially — with the *opposite* handedness to the stroke
that follows. Phase F proves this channel is real, dominant and gauge-controlled: moving the rotation centre
changes `J_pre` by 3.8× and flips the net roll (§23.7). It is not a numerical artifact, and it is **not**
removable by S2 geometry (the finalist leaves `J_pre` unchanged to 2 %), F8 eccentricity or a transverse offset.

**The experiment: a STATE-DEPENDENT converter skew.** Apply ε = 0 while the motor is in ADP·Pi and ε ≠ 0 from
the ADP·Pi → ADP transition onward — i.e. make the stroke *plane*, not the motor, chiral. Concretely, gate
`ChiralSiteSystem.convFrameStep`'s rotation on `nucleotideState` exactly as `MatSoaSlice.matCock` already gates
`thetaS`. Cost: one flag and one predicate in an existing kernel; no new mechanism, no new force law, no new
buffer, ε = 0 still byte-identical.

**Why this and not something else:**
- It is the **only** channel the budget shows to be both large and opposing.
- It is atlas class **D / A4 (state-dependent chiral geometry)**, which
  `docs/TWIRLING_MECHANISM_ATLAS_FINDINGS.md` independently ranks as its **strongest R4 mechanism** (−15.9σ, the
  only one that survives `R_actin → 0` as a direct couple).
- It is the more defensible physical statement of "the lever-arm swing is rotated": the stroke plane differs
  between nucleotide states, rather than the motor carrying a permanent structural asymmetry that is already
  strained before it strokes.
- It does **not** add a chiral mechanism — it removes the parasitic half of the one already present.

**Falsifiable prediction from the measured budget** (assuming the other channels are unchanged — exactly what
the experiment tests): removing `J_pre` gives

| ε | `J_total_odd` now | predicted with `J_pre` = 0 | gain | predicted Ω_odd |
|---|---|---|---|---|
| 5° | −1.502e-25 | −1.708e-25 | ×1.14 | ≈ −12.0 rad/s |
| 15° | −1.323e-25 | −2.023e-25 | ×1.53 | ≈ −19.4 rad/s |
| 30° | −1.829e-25 | −3.441e-25 | ×1.88 | ≈ −36.6 rad/s |

and — the discriminating signature — the ε-scaling of Ω_odd should rise from the present **1.00 / 1.20 / 1.84**
to about **1.00 / 1.62 / 3.05**. Recovery of a *steeper but still sub-sin* growth is the predicted outcome,
because the ε-independent `J_post_late` pedestal remains. **If Ω_odd instead scales fully as sin ε, the
`J_post_late` pedestal is also ε-coupled and the channel decomposition needs revising; if Ω_odd does not improve
at all, `J_pre` is not separable from the stroke channel and the mechanism is intrinsically self-cancelling.**
Either way the result is decisive.

**Run it as:** `-conv-budget` at ε = 5/15/30°, shared base, n = 8 pilot then n = 24–48 on the best angle, with
the mirror and ε = 0 controls — i.e. the apparatus built in this section, unchanged. **Run it at the baseline
L = 40 nm geometry**: do NOT stack the `s2len 0.75×` finalist, because §23.13b shows that gain is substantially
a timestep-resolution effect and stacking it would confound the state-dependence test with a numerical one.
Include a dt/2 arm from the start — the late bound-phase channel this section identified is exactly the one that
moves with dt. **Only after that** should filament Brownian motion be enabled or the `RollSpringSystem` multi-segment
roll-coherence test (§22.12) be run: both add variance to an observable whose per-episode budget is only now
understood.

**Explicitly do NOT:**
- increase ε further (the stroke channel already scales correctly; the loss grows *faster* than the gain);
- adopt the pivot gauge (a bigger number from a different, opposite-handed, preload-borne mechanism — §23.7);
- pursue S2 bending stiffness, F8 eccentricity or a converter transverse offset (all null, §23.8b–§23.10);
- re-derive anything from `J_stroke` alone — it under-counts the physical impulse by ~2.6× (§23.5).

### 23.20 Files, flags and evidence

New: `softbox/ConvBudget.java`; `ChiralSiteHarness.Ledger` + `-conv-budget`, `-conv-gauge-compare`,
`-conv-geom-sweep <s2len|s2bend|ecc|ecccomp|trans>`, `-conv-geom-values "a,b,…"`;
`ExplicitCompleteMatHarness.{EPISODE_TELEM, applyGeomScales, geomScaleString, resetGeomScales, S2_LEN_SCALE,
S2_BEND_SCALE, CONV_ECC_SCALE, CONV_ECC_COMP, CONV_TRANS_NM}` + the CLI flags
`-s2-free-length-scale`, `-s2-bend-stiffness-scale`, `-converter-f8-eccentricity-scale`,
`-converter-f8-eccentricity-compensated`, `-converter-transverse-offset-nm`.

Logs, all under `RUN_LOGS/chiral_sites/`:

| log | content |
|---|---|
| `p1_budget_15deg_n8_gpu.txt` | Phase A/A2/A3/A4 at 15°, n = 8 + ε = 0 half-split null |
| `p2_budget_05deg_n8_gpu.txt`, `p3_budget_30deg_n8_gpu.txt` | the ε-scaling decomposition |
| `p4_gauge_15deg_n8_gpu.txt` | Phase F interface vs pivot gauge |
| `p5_geom_s2len_n8_gpu.txt`, `p6_geom_s2bend_n8_gpu.txt` | Phase C |
| `p7_geom_ecc_n8_gpu.txt`, `p8_geom_ecccomp_n8_gpu.txt` | Phase D |
| `p9_geom_trans_n8_gpu.txt` | Phase E |
| `f1_final_s2len0.75_head2head_n24_gpu.txt` | powered finalist head-to-head, n = 24 matched |
| `f2_final_s2len0.75_budget_n24_gpu.txt` | finalist budget + ε = 0 achirality control, n = 24 |
| `f3_final_s2len0.75_controls_n24_gpu.txt` | finalist mirror + randomized-base controls (§23.12) |
| `f4_dt_baseline_half_n8_gpu.txt`, `f5_dt_s2len0.75_half_n8_gpu.txt` | dt/2 check (§23.13) |
| `r1_regression_cpu.txt` | `-fixtures` 24/24, `-conv-fixtures` 8/8, `-conv-stage1` 8/8 |

## 24. State-dependent converter skew and removal of the pre-stroke chiral preload

**The question.** §23 decomposed the converter-skew twirl into three channels with three ε-laws and identified
exactly one loss: the **pre-stroke bound dwell**. `J_pre` is opposite-signed to the stroke and grows FASTER than
sin ε (×3.40 at 15°, ×7.83 at 30°), cancelling **45 % → 61 % → 77 %** of the sin ε-scaling stroke channel.
§23.19 named the fix and pre-registered its prediction: apply the skew only from the ADP·Pi → ADP transition
onward, so the *stroke plane* is chiral without the *waiting* motor being chirally preloaded. This section runs
that experiment.

**Result in one line: the target was hit exactly and the experiment still failed — `J_pre` was removed and
`J_stroke` went with it, because they are the loading and release halves of ONE chiral strain cycle. Decision
class P2.**

Noncanonical, flag-gated, **DEFAULT-OFF**. No converter geometry, skew definition, rotation axis, gauge, S2, F8,
binding gate, kinetics, rates, lattice, density, filament representation, Brownian setting, dt, RNG stream,
canonical default or `MotorModel.CANON_VERSION` was touched. The only scientific change is **when** the existing
rotation becomes active.

### 24.1 Exact state semantics

```
unbound                      converter skew OFF   (unchanged — flag 0, canonical motor)
bound, ADP·Pi pre-stroke     converter skew OFF   ← the change
ADP·Pi → ADP transition      converter skew ON    ← on the SAME step as the thetaS rest switch
bound, ADP post-stroke       converter skew ON
detachment                   frame clears exactly as before
```

**The state predicate is `thetaS` itself** (`q[2N+m]`), which `MatSoaSlice.matCock` writes as
`nuc == NUC_ADPPI ? PRESTROKE_THETAS : ADP_THETAS`. Gating on it means the skew and the rest-coordinate switch
are driven by **one quantity from one source** — no duplicated state semantics, no guessed nucleotide integer,
and (because `q` is already an argument to `convFrameStep`) **no new kernel argument**. `chiP[20]` carries the
discriminant `½(PRESTROKE_THETAS + ADP_THETAS)`, built host-side from the same `cockP` constants `matCock`
consumes; `chiP[19]` is the on/off flag.

### 24.2 CPU/GPU task-order audit — and the one-step offset it exposed

The gliding graph and the CPU runner execute, in this order:

```
1 convFrame   (ChiralSiteSystem.convFrameStep)   ← WRITES convF
2 beamGeom    (matBeamGeom)                       ← READS convF
3 bind / siteSnap / siteOcc / surfPrune
4 chem        (NucleotideCycleSystem)             ← WRITES nucleotideState
5 cock        (matCock)                           ← WRITES thetaS from the new state
6 place, bond, headRoll, forces, integrate, derive
7 s2solve     (matS2SolveStep)                    ← READS convF **and** thetaS
```

**`convFrame` runs BEFORE `chem`/`cock`.** A naive gate on the state as seen at step 1 therefore reads the
*previous* step's `thetaS`, while `cock` at step 5 switches the rest angle and `s2solve` at step 7 executes the
stroke. **The skew would activate one full step LATE and the first — largest — increment of the power stroke
would be taken in the unrotated plane.** That is decision class P2 by construction and precisely the artifact
that must not be silently accepted.

**Resolution (smallest sufficient change).** In the gated mode ONLY, `convFrameStep` is invoked a **second**
time immediately after `cock`, before `place`. `s2solve` — the task that actually realises the stroke — then
reads a converter frame and a rest angle that describe **one** transition. Nothing is reordered; a task is
*added*, and only when the feature is on, so the default **task list** and the default **CPU call sequence** are
byte-unchanged. Mirrored in all three steppers: the GPU graph (`convFrame2`, with its own `WorkerGrid`),
`stepGlidingCPU`, and the fixture stepper `ChiralSiteHarness.convStep`.

**Scope of the "byte-unchanged" claim, stated precisely.** `convFrameStep` itself gained one comparison and one
branch, so **its PTX is not byte-identical** to the pre-§24 kernel even when the flag is off — the same class of
change §21 made when `matBeamGeom`/`matS2SolveStep` gained the `convF` argument on a flag-0 branch, and the same
class the CLAUDE.md graph-split lesson warns can shift a chaotic basin by an ULP. The claim that is actually
supported is **behavioural**: on the CPU runner (no PTX, deterministic) the default path is bit-identical, and
the §23/§24 regression suites reproduce their prior values. That empirical check — not a compile-time
guarantee — is what §24.12 records.

`beamGeom` at step 2 legitimately keeps the *pre*-stroke frame on the transition step: at that point in the step
the motor is still ADP·Pi, so the canonical geometry is correct for binding and head placement. The
always-active path has the same structure (its `place`/`bond` also consume the step-2 geometry).

**Verified, not assumed** — fixture 305 reads `thetaS` and the converter-active flag on the transition step
itself: `thetaS` −0.52360 → +0.52360 and flag 0 → 1 **on the same step**.

### 24.3 Implementation and default-off behaviour

One early-out was added to the existing `ChiralSiteSystem.convFrameStep`, immediately after the existing
`mode == 0 || eps == 0 || unbound` early-out:

```java
if (gated != 0.0 && q.get(2*N + m) <= thetaDisc) { convF.set(12*N + m, 0.0); continue; }
```

No second converter-frame implementation, no new buffer, no changed signature; when `gated == 0` the condition
short-circuits on the first term. Flag: `-converter-skew-state-gated <on|off>`, default **off**.

### 24.4 Deterministic Stage-1 fixtures — 20 / 20 PASS [`g1_gated_fixtures_cpu.txt`, `g4_gated_snap_cpu.txt`]

One motor, one fixed site, filament fixed, Brownian OFF, interface gauge, ε = 15°, settle 400 + relax 400.

**[A] The pre-stroke dwell becomes exactly ε-INDEPENDENT — the target of the whole experiment.**

| arm | F8_u nm | F8_t nm | F8_n nm | F_tan N | τ_ax N·m | flag |
|---|---|---|---|---|---|---|
| ε = 0 (reference) | 10.8601 | 9.9747 | 1.1809 | −3.8161e-13 | −1.3356e-21 | 0 |
| always-active +ε | 10.8222 | 9.9403 | 1.1819 | −3.9965e-13 | −1.3988e-21 | 1 |
| always-active −ε | 10.9248 | 9.9652 | 1.1770 | −1.7666e-13 | −6.1832e-22 | 1 |
| **STATE-GATED +ε** | **10.8601** | **9.9747** | **1.1809** | **−3.8161e-13** | **−1.3356e-21** | **0** |
| **STATE-GATED −ε** | **10.8601** | **9.9747** | **1.1809** | **−3.8161e-13** | **−1.3356e-21** | **0** |

ε-ODD pre-stroke: always-active `τ = −3.9023e-22`, `F_t = −1.1149e-13`, `Δx_t = −0.0124 nm`; state-gated
**`τ = +0.0000e+00`, `F_t = +0.0000e+00`, `Δx_t = +0.0000 nm`**. The ±ε gated rows are *literally identical* —
the pre-stroke frame is canonical and carries no ε at all.

**[B] Activation is synchronous with the rest switch, and the stroke is bit-preserved.** `thetaS`
−0.52360 → +0.52360 and flag 0 → 1 on the SAME step. Unloaded stroke, state-gated:
**8.0000 / −7.7274 / −2.0706 / −0.0000 nm** — identical to always-active and to the analytic
`−8cos ε / −8sin ε / 0`. Site id, segment and `bindAzim` unchanged.

**[C/D/E]** frame held active 400/400 post-stroke steps; rigid-rotation covariance rel 7.35e-08 (no laboratory
latch); detachment clears it with **exactly zero pointwise effect on the geometry** (recomputing `matBeamGeom`
with `convF` forcibly zeroed differs by 0.00e+00, in both modes); a re-bound motor starts its next pre-stroke
dwell unrotated.

**Stage 2 (loaded, F8 ON, filament fixed)** — the stroke channel untouched, the pre-stroke channel annihilated:

| ε | arm | ODD τ_ax (stroke) | ODD F_tan | EVEN F_ax | **ODD preTau** | fClose | wDiss |
|---|---|---|---|---|---|---|---|
| 5° | always | −3.0318e-23 | −8.6622e-15 | −2.6820e-12 | −1.2096e-22 | 0.00e+00 | +3.68e-20 |
| 5° | **gated** | **−3.0317e-23** | **−8.6620e-15** | −2.6820e-12 | **+0.0000e+00** | 0.00e+00 | +3.53e-20 |
| 15° | always | −8.9145e-23 | −2.5470e-14 | −2.6110e-12 | −3.9023e-22 | 0.00e+00 | +3.93e-20 |
| 15° | **gated** | **−8.9145e-23** | **−2.5470e-14** | −2.6110e-12 | **+0.0000e+00** | 0.00e+00 | +3.45e-20 |

Force pair closed (0.00e+00), energy closes with non-negative dissipation, axial channel bit-identical to
always-active, ±ε reverse, mirror reverses. **Identity gates:** gating OFF reproduces the always-active
trajectory exactly; ε = 0 is bit-identical with gating ON vs OFF.

**Two fixtures initially failed and both were MIS-SPECIFIED TESTS, not physics** — recorded because the
correction matters: (i) the detach gate demanded `convF[0..11]` be scrubbed, but clearing sets the FLAG only and
the components are never read at flag 0 (the §21 fixture-208 contract); the ±ε post-detach trajectory spread it
then measured (9.8e-4 µm) is **elastic S2 history, present identically in the always-active mechanism**
(9.6e-4 µm). (ii) the "F_ax stays ε-EVEN" gate applied §21.4's *ensemble* claim to a single frozen
configuration, where the always-active arm shows the identical odd component. Both were replaced with the
propositions actually at issue (pointwise clearance; axial channel unchanged by gating).

### 24.5 CPU/GPU equivalence with gating ON [`g2_gated_equiv_gpu.txt`]

Full `buildGlidingGraph` with `convFrame` **and** `convFrame2` wired, device-resident, ε = 15°,
`-Dtornado.enable.fma=false -Dtornado.recover.bailout=false -Dtornado.tvm.maxbytecodesize=65536` (a lowering
failure THROWS — no silent fallback), stepped against the CPU runner:

```
200 device-resident steps:
  bindMism = 0    convFlagMism = 0    max|dConvFrame| = 1.83e-06    max|dSegTorque| = 1.51e-24 N·m
  max|dFilCoord| = 5.96e-08 µm    firstDiv = none (bit-close)    bound CPU = 9, GPU = 9    ⇒ PASS
```

`convFlagMism = 0` is the load-bearing number: **transition detection and the converter-active flag agree
exactly on both runners**, so the added `convFrame2` task and its CPU mirror describe the same event.

### 24.6 PHASE 1 — the live angle screen (8 matched seeds, 5°/15°/30°) [`g3_gated_sweep_n8_gpu.txt`]

| ε | arm | `J_pre` | `J_stroke` | `J_early` | `J_late` | `J_total` | Ω_odd | v_even | avgB | bad |
|---|---|---|---|---|---|---|---|---|---|---|
| 5° | A always | +2.058e-26 | −1.833e-26 | −2.746e-26 | −1.250e-25 | −1.502e-25 | −10.55 ± 4.5 | −3.007 | 2.59 | 0 |
| 5° | **B gated** | **+1.524e-27** | **+1.559e-28** | −1.644e-26 | −5.520e-26 | −6.996e-26 | −5.22 ± 3.3 | −3.008 | 2.59 | 0 |
| 15° | A always | +6.999e-26 | −4.563e-26 | −6.851e-26 | −8.818e-26 | −1.323e-25 | −12.68 ± 3.5 | −2.673 | 2.63 | 0 |
| 15° | **B gated** | **+2.938e-26** | **−1.923e-28** | −5.520e-26 | −3.828e-26 | −6.430e-26 | −7.47 ± 6.7 | −3.078 | 2.62 | 0 |
| 30° | A always | +1.612e-25 | −8.458e-26 | −1.254e-25 | −1.341e-25 | −1.829e-25 | −19.44 ± 7.1 | −2.729 | 2.80 | 0 |
| 30° | **B gated** | **−3.992e-26** | **+8.147e-27** | −6.536e-26 | −1.135e-25 | −2.107e-25 | −19.54 ± 4.4 | −2.737 | 2.85 | 0 |

**Matched-seed deltas (B − A):**

| ε | ΔJ_pre | ΔJ_stroke | ΔJ_early | ΔJ_late | ΔJ_total | ΔΩ_odd | Δv_even | ΔavgB |
|---|---|---|---|---|---|---|---|---|
| 5° | **−1.905e-26** | +1.849e-26 | +1.102e-26 | +6.981e-26 | +8.026e-26 | +5.34 | −0.002 | +0.00 |
| 15° | **−4.062e-26** | +4.544e-26 | +1.330e-26 | +4.990e-26 | +6.802e-26 | +5.20 | −0.406 | −0.01 |
| 30° | **−2.011e-25** | +9.273e-26 | +5.999e-26 | +2.059e-26 | −2.781e-26 | −0.10 | −0.008 | +0.04 |

**The primary target was hit and the experiment still failed.** `ΔJ_pre < 0` at every angle — the opposing
pre-stroke impulse is reduced by **93 % / 58 %** at 5°/15° and driven through zero at 30°. But `J_stroke`
collapses with it (**−4.563e-26 → −1.9e-28** at 15°), `J_late` is roughly halved at 5°/15°, and net `J_total`
gets WORSE at 5° and 15°.

**Measured vs the pre-registered §23.19 prediction:**

| ε | A `J_total` | predicted B | measured B | predicted gain | **measured gain** | predicted Ω | **measured Ω** |
|---|---|---|---|---|---|---|---|
| 5° | −1.502e-25 | −1.708e-25 | −6.996e-26 | 1.137 | **0.466** | −12.00 | **−5.22** |
| 15° | −1.323e-25 | −2.023e-25 | −6.430e-26 | 1.529 | **0.486** | −19.38 | **−7.47** |
| 30° | −1.829e-25 | −3.441e-25 | −2.107e-25 | 1.882 | **1.152** | −36.58 | **−19.54** |

The prediction assumed only `J_pre` would be removed. It was not: **the additive model is refuted.**

**Angle scaling — the one place gating helps.** |Ω_odd| ratios become **1.00 / 1.43 / 3.74** (A: 1.00 / 1.20 /
1.84; §23.19 predicted B ≈ 1.00 / 1.62 / 3.05). The scaling *does* steepen roughly as predicted, even though the
absolute magnitudes fall — because the small-angle arm loses proportionally more.

**Population closure** holds for A (1.032 / 1.044 at 15°/30°, 1.205 at 5°) and for B at 5° (1.213) and 30°
(0.904), but is **0.587 for B at 15°** — the arm with the largest Ω SEM (±6.7). Flagged, not explained: at n = 8
it is consistent with noise, but it is the one closure failure in this section.

0 invalid, 0 solver failures, no fallback in any arm.

### 24.7 Why the stroke collapsed — the loading/release entanglement

**(a) The gate-opening discontinuity is small in displacement but large in force** [`g4_gated_snap_cpu.txt`].
Freezing everything else and flipping only the chemical state:

| ε | \|Δx_F8\| nm | Δx_F8 tangential nm | \|ΔF\| N | \|F\| before N | **ΔF/F** |
|---|---|---|---|---|---|
| 0° | 0.00000 | 0.00000 | 0.0000e+00 | 4.8990e-12 | 0.000 |
| 5° | 0.01582 | 0.01263 | 5.3260e-13 | 4.8990e-12 | 0.109 |
| 15° | 0.04734 | 0.04013 | 1.5937e-12 | 4.8990e-12 | **0.325** |
| −15° | 0.04734 | 0.03227 | 1.5937e-12 | 4.8990e-12 | 0.325 |

The interface gauge writes `xF8 = P + x̃_ref + R(x̃ − x̃_ref)`; the offset cancels only AT the reference pose, and
the bound pose sits 0.147 nm away from it, so switching R from I to R(ε) displaces xF8 by `(R − I)(x̃ − x̃_ref)`.
The displacement is **0.047 nm = 0.6 % of the 8 nm stroke** — so the **F8 anchor is NOT teleported** (gate 320
PASS) — but the cross-bridge is stiff enough that it is a **33 % instantaneous step in the bond force**, exactly
zero at ε = 0. The always-active mechanism never performs this switch on a bound motor.

**(b) `J_pre` and `J_stroke` are the two halves of ONE strain cycle.** Their SUM is nearly invariant under
gating at the two well-resolved angles:

| ε | A: `J_pre + J_stroke` | B: `J_pre + J_stroke` | ratio |
|---|---|---|---|
| 5° | +2.246e-27 | +1.680e-27 | 0.75 |
| 15° | +2.437e-26 | +2.918e-26 | 1.20 |
| 30° | +7.663e-26 | −3.177e-26 | −0.41 |

**Reading.** In the always-active motor the pre-stroke dwell *charges* a chiral strain (opposing, `J_pre > 0`)
and the power stroke *discharges* it (`J_stroke < 0`); the stroke-window impulse is largely the release of a
strain the dwell built up. Gating removes the charging half — and the release half goes with it, leaving the sum
roughly where it was. **The pre-stroke preload is not a removable parasite; it is the loading stage of the same
chiral strain cycle whose unloading is the measured stroke impulse.** §23's channel decomposition was correct as
*accounting* but wrong as *causal separability*. (At 30° the sum is not preserved and `J_pre` overshoots
negative — the noisiest arm; its interpretation is held open.)

### 24.8 Controls at 15° with gating ON (8 seeds) [`g5_gated_controls_n8_gpu.txt`]

| arm | τ_odd (N·m) | σ | Ω_odd (rad/s) |
|---|---|---|---|
| SHARED native | −3.112e-22 | 1.73 | −7.47 ± 7 |
| SHARED **MIRROR** | +2.255e-22 | 1.68 | **+6.78 ± 4** — REVERSED |
| RANDOMIZED base | +1.218e-22 | 1.07 | +5.43 — **sign flips** |

- **Ω_odd mirror-reverses** (−7.47 → +6.78), so the residual twirl is still chiral.
- **`J_odd[0-7]` does NOT reverse** (native −1.820e-27, mirror −5.103e-28) — but that window's signal has been
  annihilated by gating (1e-27 vs the always-active −4.8e-26), so this is a test of noise, not a failed control.
- **The randomized-base sign flips** at 1.07σ — likewise noise-limited.

Honest reading: with the stroke channel gone, the gated arm at n = 8 is under-powered (all arms 1.0–1.7σ), so
the control battery can neither confirm nor refute the residual mechanism. This is **P7 layered on P2** — but it
does not change the primary verdict, because that verdict rests on the *deterministic* fixtures (where the
stroke is bit-preserved) plus the matched-seed live collapse of `J_stroke`, both of which are sharply resolved.

### 24.9 Phase 2 NOT run — the pre-registered stopping rule

Phase 2 (24 matched seeds at 15°) was gated on "substantial reduction in `J_pre`, **preserved `J_stroke`**,
healthy gliding and engagement, no instability". `J_stroke` is **not** preserved — it is annihilated — so the
powered endpoint was **not run**, per the rule agreed before the data existed. The timestep check, likewise
specified for "the state-gated 15° finalist", was not run because there is no finalist. Both omissions are
choices, not gaps: powering an arm whose target channel has been destroyed would buy precision on the wrong
quantity. (§23.13a's always-active dt/dt-2 baseline stands unchanged.)

### 24.10 Decision class

**P2 — preload removed but the stroke is weakened**, with **P4** and **P7** as documented secondaries and **P6**
quantified and excluded on its stated criterion.

| class | verdict |
|---|---|
| **P1** selective preload removal succeeds | **NO** — `J_pre` removed, but `J_stroke` → ~0 and `J_total` worsens at 5°/15° |
| **P2** preload removed, stroke weakened | **YES — primary.** ΔJ_stroke = +1.85e-26 / +4.54e-26 / +9.27e-26 (i.e. toward zero) at 5/15/30°. Not an ordering error (fixture 305 proves synchrony) but a genuine mechanical entanglement (§24.7b) |
| **P3** preload persists | **NO** — `J_pre` reduced 93 % / 58 %, and deterministically to EXACTLY zero |
| **P4** preload removal changes the late tail | **YES, secondary** — `J_late` roughly halved at 5°/15° (−1.250e-25 → −5.520e-26; −8.818e-26 → −3.828e-26) ⇒ gating alters the whole bound relaxation path, not just the dwell |
| **P5** population roll does not follow the cycle budget | **NO in general** — closure 0.90–1.21 in five of six arms; the 15° gated arm (0.587) is flagged as the one exception, n = 8 |
| **P6** transition impulse artifact | **Excluded on displacement, REAL in force** — 0.047 nm = 0.6 % of the stroke (gate 320 PASS), but a 33 % step in bond force. It is the *mechanism* by which the missing pre-strain manifests, not an independent bug |
| **P7** mechanistically correct but underpowered | **YES for the residual** — the gated control battery is 1.0–1.7σ at n = 8 (§24.8) |
| **P8** numerical / GPU failure | **NO** — 0 invalid, 0 solver failures, no fallback anywhere; CPU/GPU `convFlagMism = 0` |

### 24.11 Exact next recommended experiment — ramp ε, do not switch it

Both failure modes have the same root: **ε is switched discontinuously on a loaded bond.** That (a) injects a
33 % force step and (b) deletes the charging half of a strain cycle whose release is the stroke impulse. The fix
is to make the rotation *continuous in the converter's own coordinate* rather than in the chemical state:

```
eps_eff(theta) = eps · clamp( (theta − theta_pre) / (theta_post − theta_pre), 0, 1 )      theta = psi − phi
```

- At the pre-stroke rest `theta = theta_pre` ⇒ `eps_eff = 0` **exactly** — the §24 target, achieved with **no
  gauge switch, no discontinuity and no force step**.
- At the post-stroke rest ⇒ `eps_eff = eps` — the full skew is retained through the bound tail.
- The stroke plane now rotates **with** the swing, which is also the more defensible statement of "the
  lever-arm swing is oblique" than a plane that snaps at the swing's start.
- Cost: `theta` is already available in `q`; the same `cockP`-derived constants give `theta_pre`/`theta_post`.
  No new argument, no new kernel, ε = 0 still byte-identical, and the ordering fix of §24.2 is retained.

**Pre-registered expectations:** `J_pre` should fall toward zero *without* the ΔF/F step; `J_stroke` should be
intermediate between A and B (it now has a real, if ramped, chiral swing to release); the discriminator is
whether `J_pre + J_stroke` — invariant under *switching* (§24.7b) — finally moves. **If the sum still does not
move, the chiral strain cycle is irreducible and no activation schedule can separate loading from release**;
that would be the definitive statement that this mechanism's twirl is capped by its own reversibility, and the
next lever would have to be the duty cycle (detaching before the recoil, atlas class C) rather than the skew
schedule. Note the unloaded stroke will no longer be exactly `−8 sin ε` tangential (it becomes an integral over
the ramp), so fixture 306's analytic form must be re-derived before use.

**Do NOT**: power the state-gated arm (§24.9); raise ε (the stroke channel is the part that broke); or read
`f_retain` for the gated arms — with `J_stroke` ≈ 0 its ratios (−448, +334, −25.9) are meaningless by
construction and are reported only to make that explicit.

### 24.12 Regression and files

`-conv-gated-fixtures` **20/20 PASS** (incl. the default-off and ε = 0 identity gates); `-conv-equiv` with
gating ON **PASS** device-resident. The §23 regression set was **re-run after the §24 code changes** and is
unmoved: `-fixtures` **24/24**, `-conv-fixtures` **8/8** (incl. 209 ε = 0 byte-identity), `-conv-stage1`
**8/8** — identical to their pre-§24 values (log `g6_regression_post24_cpu.txt`). This is the empirical check
that the §24.2 "byte-unchanged" claim rests on, given that `convFrameStep`'s own PTX did change.

New: `ExplicitCompleteMatHarness.{CONV_SKEW_STATE_GATED, convSkewStateGated}` + `chiP[19..20]`; one early-out in
`ChiralSiteSystem.convFrameStep`; the `convFrame2` task in `buildGlidingGraph` + its CPU and fixture mirrors;
`ChiralSiteHarness.{runGatedFixtures, gatedSnapProbe, gatedDetachResidual, gatedCycleProbe, runGatedSweep,
budgetRow, gatedComparisonTable}`; flags `-converter-skew-state-gated`, `-conv-gated-fixtures`,
`-conv-gated-sweep`. Logs `RUN_LOGS/chiral_sites/g1..g5_*`.

## 25. Progress-ramped converter skew and a curved chiral power-stroke path

**STATUS: STAGE 0 ONLY.** This section currently contains the source and progress-coordinate audit that §25
requires *before* coding. The ramp implementation, deterministic path fixtures, force-continuity tests, impulse
budgets, ramp-shape screen, angle scaling, powered finalist, controls, closure and timestep test are **not yet
run**, and no decision class R1–R8 is assigned. It is written up now because the audit produced a result that
changes the experiment's design, and that result should not live only in a session transcript.

**Why the experiment exists.** §24 showed that switching the converter skew on at Pi release removes `J_pre`
and annihilates `J_stroke` with it, because the two are the charging and release halves of one history-dependent
elastic cycle, and that the switch also produces a 33 % step in the bond force. §24.11's recommendation was to
make the rotation continuous in the converter's own coordinate: `eps_eff = eps_max · f(q)`.

### 25.1 Stage 0 — source and progress-coordinate audit [`h1_ramp_audit_cpu.txt`]

**Write-order fact, from source.** `q[m] = phi` and `q[N+m] = psi` are written **only** by the solve kernels
(`TwoBodyBeamAnalyticGpu.beamRelaxAnalytic:290`, `matS2SolveStep:1175`); `matCock` writes `q[2N+m] = thetaS`;
`q[3N+m] = psiActin` is set at pack time. `convFrameStep` runs FIRST in the step (§24.2), so the `phi`/`psi` it
reads are the **previous step's converged state** — a stored state variable, not a function of this step's solve.

**⇒ There is no circular dependency, and audit option B applies.** No predictor/corrector (option C) and no
`thetaS`-derived rest-progress (option D, which would be the §24 binary switch in disguise) are needed. This is
recorded explicitly because the concern was legitimate: the converter frame is built before the geometry and
solve that update `phi`/`psi`, and had those been *this* step's values the coupling would have been circular.

**Measured candidate traces** (one bound motor, one fixed site, filament fixed, Brownian OFF, ε = 15°, complete
prestroke → poststroke transition; "pre"/"post" are the relaxed equilibria, not the rest constants):

| candidate | pre (unloaded) | post (unloaded) | pre (loaded) | range | non-monotone (unloaded) | available pre-geometry? | circular? |
|---|---|---|---|---|---|---|---|
| `phi` | +0.20583 | −0.52360 | +0.19161 | 0.7294 | **0 / 399** | YES — `q[m]` | no (previous step) |
| `psi` | +0.11846 | +0.00000 | +0.11416 | 0.1185 | 0 / 399 | YES — `q[N+m]` | no (previous step) |
| **`theta = psi − phi`** | **−0.08736** | **+0.52360** | **−0.07745** | **0.6110** | **0 / 399** | YES — `q[N+m] − q[m]` | no (previous step) |
| `thetaS` | −0.52360 | +0.52360 | −0.52360 | binary | binary switch | YES — `q[2N+m]` | no — but it IS the §24 switch |
| F8 axial displacement | 0 (ref) | −7.727 nm | 0 (ref) | 5.724 | 0 / 399 | **NO** — written by `beamGeom` | **WOULD BE** |

Under load the same coordinates acquire reversals — `theta` 51/399, F8 axial 130/399 — as the loaded system
relaxes and retreats. That is **correct behaviour for a reversible coordinate-coupled ramp** (the §25 requirement
that skew retrace if the stroke reverses), but it means `q` will jitter under load, so the per-step `Δeps_eff`
continuity metric is a first-class observable rather than a formality.

**THE LOAD-BEARING FINDING — the normalization specified in the §25 brief is unusable as written.**
`theta` at the **relaxed prestroke pose is −0.08736 rad, not the rest value −0.52360**. The converter torsional
spring is *not* relaxed during the ADP·Pi dwell: the head is docked (`psi` pinned near `psiActin` ≈ 0) and `phi`
is held at the binding lean, so `theta` sits ≈ 0.436 rad off its own rest. Consequently

```
q = clamp((theta − PRESTROKE_THETAS) / (ADP_THETAS − PRESTROKE_THETAS), 0, 1)   ⇒   q(prestroke) = 0.4166
```

i.e. normalizing on the **rest constants** would apply **42 % of ε to every waiting motor** — manufacturing
precisely the standing preload that §25's own class **R5** says to reject, and re-creating in a subtler form the
§23.7 pivot-gauge confound.

**Resolution.** The endpoints must be the **measured relaxed equilibria**:

```
theta_pre  = −0.08736 rad      (measured, unloaded relaxed prestroke pose)
theta_post = +0.52360 rad      (= ADP_THETAS exactly — the converter DOES fully relax post-stroke)
```

Only the prestroke endpoint requires calibration; the poststroke endpoint falls out of the model. **This is a
calibration, not a derivation, and it is mildly load-dependent**: the loaded prestroke equilibrium is
−0.07745 rad, **1.6 % of the full range** away from the unloaded value. So `q` at a loaded waiting motor will sit
a little above 0 rather than exactly 0, and the ε = 0 achirality control plus a "no chiral docking displacement"
check at attachment become mandatory rather than optional.

**Coordinate chosen: `theta = psi − phi`.** It is the converter's own generalized coordinate — the one the
chemical rest switch `thetaS` acts on — it is monotone through the unloaded transition (0/399), it is available
before geometry construction, it reverses naturally with the stroke, and it is not self-referential. `phi` is a
near-equivalent alternative (since `psi` moves only 0.1185 rad against `phi`'s 0.7294, `theta ≈ −phi` up to a
small offset) and is arguably the more *mechanical* choice given §23.6's finding that the stroke is the neck-lever
swing at radius `lb`; it is retained as the pre-registered sensitivity check. The F8 axial displacement is the
most physically direct coordinate but is **disqualified**: it is written by `beamGeom`, i.e. *after*
`convFrameStep`, so using it would create exactly the circular dependency the audit was run to exclude.

### 25.2 – 25.15 — not yet run

Ramp equations and implementation, default-off and identity gates, deterministic forward/reverse path fixtures,
loaded force-continuity and energy accounting, the impulse budget with ramp-specific episode fields, the
ramp-shape screen (linear / smoothstep / delayed q0 = 0.25, 0.50), angle scaling, the powered 15° finalist, the
mirror / ε = 0 / randomized-base / `Ractin = 0` controls, population closure, the timestep test, the decision
class and the next recommendation are all **pending**. Nothing in §25 should be cited as a result until they are.
