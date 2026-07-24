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
