# Vilfan-Style Stereospecific Target-Zone Binding in Explicit-S2 Gliding

**Authoritative report for this increment** (2026-07-23, branch `gpu-mat-bottlenecks-explicit-singlehead`). Sole
Markdown report for this task. Tests whether a continuous, *stereospecific* actomyosin orientation constraint —
Vilfan's moving-target-zone mechanism — generates a biased attachment flux and a nonzero mean axial torque during
explicit-S2 gliding. **Noncanonical, flag-gated, default-off, byte-identical when disabled. No canonical default,
parameter, chemistry, S2/stroke mechanics, dt, RNG stream, event ordering, or `MotorModel.CANON_VERSION` changed.
No parameter was tuned to produce rotation.**

---

## 1. Baseline revision and lowering status

- **Baseline commit:** `0ead849` (`matsoa(helical): off-axis actin-surface binding + twirling …`), branch
  `gpu-mat-bottlenecks-explicit-singlehead`.
- **Lowering prerequisite: SATISFIED.** `docs/EXPLICIT_S2_GPU_LOWERING_REGRESSION_FINDINGS.md` root-caused the
  earlier "graph does not lower" claim as a **launch-flag regression** (`matS2SolveStep` FMA-lowers to an
  `ArithmeticLIRLowerable` NPE on the PTX backend unless `-Dtornado.enable.fma=false`). The full explicit-S2
  gliding graph — **surface OFF and surface ON** — lowers and runs **device-resident with no silent fallback**.
  This increment therefore ran its equivalence gate on the **full** graph, not on isolated kernels.
- **Dirty at start:** pre-existing unrelated edits to `docs/CURRENT_STATE.md` and five `scripts/*sweep*.sh`
  (untouched here), plus the lowering task's fix to `scripts/run_explicit_twirl.sh` /
  `ExplicitTwirlGlidingHarness.runEquiv`.

## 2. Runner, hardware, and device flags

- **aorus**; Java 21 (OpenJDK 21.0.11, `--enable-preview`), TornadoVM 4.0.1-dev **PTX** backend, **NVIDIA RTX
  5070**, driver 595.71.05.
- **Required device flags** (encoded in `scripts/run_vilfan_targetzone.sh`):
  `-Dtornado.enable.fma=false` (the upstream PTX FMA lowering defect), `-Dtornado.recover.bailout=false`
  (a lowering failure THROWS — **no silent sequential fallback**), `-Dtornado.tvm.maxbytecodesize=65536`, `-Xmx8G`.
- **Runner disclosure.** The Stage-A deterministic fixtures run on the CPU runner plus a device batch (fixture 11).
  The **Stage-A dynamic campaign runs device-resident on the GPU** (`-gpu`), which is now the validated path for
  this assay class. The timestep study runs on the CPU runner (disclosed in §14).

## 3. Literature mechanism being tested

Vilfan's actin-twirling model represents each actin presentation by a continuous axial coordinate and a helical
material azimuth, and weights the attachment rate by an angular-mismatch energy:

```
Ubind        = U(existing reach and geometry) + 0.5 * Kpsi * deltaPsi^2
angularWeight= exp(-0.5 * alphaPsi * deltaPsi^2),      alphaPsi = Kpsi / (kB*T)
```

The attachment-rate profile around a target zone is **smooth**: a motor approaching a zone acquires a finite
chance to bind before perfect alignment, and once bound it is removed from the unbound pool. That finite
attachment kinetics plus pool depletion is what is supposed to create a **leading-edge versus trailing-edge
attachment-flux asymmetry**, hence biased angular strain, hence net torque.

`alphaPsi ∈ {4, 6, 8}` are used here purely as literature-grounded experimental values. **None is canonical.**
The mechanism is implemented as a hazard, **never** as an explicit torque-sign preference.

## 4. Binding-frame definitions

### 4.1 Local actin binding frame (material, rotationally covariant)

At the canonical attachment arc `bindArc` on the bound segment `s`:

```
uActin = filUVec[s]                                        (pointed→barbed material tangent)
phi    = twistRate * (bindArc - 0.5*segLength[s])          (analytic helical material azimuth)
nActin = cos(phi)*segY + sin(phi)*segZ ,  segZ = uActin × segY
tActin = uActin × nActin                                   ⇒ (nActin, tActin, uActin) right-handed
```

`twistRate = -1076.3 rad/µm` (actin 13/6, 166.5°/monomer, **LEFT-handed**, derived fresh — v1's render-screw sign
is not authoritative). `segY` is the **rolling material reference** maintained by `DerivedGeometrySystem`, so the
frame translates, bends, rotates and **rolls** with the filament and transforms correctly under polarity reversal.
`nActin` is the outward radial surface normal of exactly the presentation whose azimuth is retained as `bindAzim`
and at which the off-axis cross-bridge site is reconstructed (`Ractin = 3.5 nm`).

### 4.2 Motor binding frame, and the honest degeneracy

```
eBind = normalize(xF8 - xH)      (head long axis; xF8, xH from outGeom, i.e. from the explicit-S2 beam pose)
mHat  = -eBind                   (the direction a compatible actin surface normal must FACE)
```

Both points are real simulated orientation state produced by the beam solve (`matBeamGeom` closed form from the
motor base frame `bhat/econv/eup`, the pivot node `P`, and the generalized coordinates `phi`, `psi`) — **not** an
invented laboratory direction.

**Stated limitation, not concealed.** The explicit-S2 head has **no free rotational degree of freedom about its
own long axis**. Its only two rotational generalized coordinates are `phi` and `psi`, and the production solver
(`matS2SolveStep`, a faithful port of `TwoBodyConverterMotor.s2SolveM`) parameterises **both** about the single
base axis `eup`. Downstream, `matPlaceHeadExplicit` synthesises the head `yVec` from a **lab-fixed** perpendicular
(`perp3`), so there is no head material frame to register against either. Consequently **full three-degree-of-
freedom stereospecific compatibility is not representable in this model**, and the *smallest valid mismatch
coordinate* is used: the **one-angle azimuthal mismatch in the plane perpendicular to `uActin`**. Head **pitch**
(the `eBind·uActin` component) is explicitly discarded and is **not** part of the compatibility rule. This
limitation is load-bearing for Stage B (§12).

### 4.3 Signed angular mismatch

```
mPerp    = normalize( mHat - (mHat·uActin) * uActin )
cross    = nActin × mPerp
deltaPsi = sign(cross·uActin) * angle(|cross|^2, nActin·mPerp)          ∈ (-pi, pi]
```

`deltaPsi = 0` is the preferred (perfectly registered) state. The magnitude uses the project's validated
float32-stable form (`asin(|cross|)` near 0 and π, `acos(dot)` mid-range — the `ChainBendingForceSystem
.angleFromSinCos` construction), **not** `Math.atan2` (absent on PTX) and not raw `acos` (ill-conditioned at both
ends because the `dacos` Newton step divides by `sin y`).

Properties, all verified as deterministic fixtures (§9): signed; wrapped to (−π,π] and continuous across ±π;
**invariant** under a proper rigid rotation of the whole scene; **sign-flipping** under filament polarity reversal
and under mirroring; shifted by −δ under a filament roll of δ; identical CPU and GPU. It is **never** an absolute
actin azimuth.

## 5. Attachment-hazard formulation

Layered on top of the canonical bind: **`matBindExplicit` is byte-unchanged**, so the 8-gate geometric candidate
set and the axial `bindArc` are byte-identical to the canonical path. The new kernel
`TwoBodyBeamAnalyticGpu.matTargetZone` runs **immediately after** the bind, on every head that just went
FREE→bound, and applies

```
wPsi = exp(-0.5 * alphaPsi * deltaPsi^2)
keep = ( u < wPsi ),   u ~ U(0,1) from a DEDICATED counter-based wang hash keyed (seed, step, motor)
```

- **`alphaPsi = 0` ⇒ `wPsi ≡ 1` ⇒ the draw is skipped entirely ⇒ every canonical bind is kept.** No RNG stream is
  consumed and none is shifted, so the `alphaPsi = 0` path is **bit-identical to the canonical trajectory**
  (fixture 1).
- The hazard is applied **before commitment**: a rejected head is returned to the FREE pool (`boundSeg = -1`) in
  the same step, before `matCock` / `matPlaceHeadExplicit` / the bond-force stage, so **no force is ever applied**
  for a rejected candidate. It may retry on the next step — this is the finite attachment kinetics that produces
  depletion.
- **No renormalisation.** A finite `alphaPsi` genuinely loses binds; the engagement loss is measured and reported
  (§11), not hidden.
- **No binary discontinuity** in the production path. An optional hard `|deltaPsi|` cutoff exists solely as a
  diagnostic (`TZ_HARD_RAD`, default 0 = off).
- Salt `0x545A4244` ("TZBD") is private to this kernel; the Brownian and chemistry streams are untouched.
- On accept the kernel retains `bindAzim = phi` (the true material azimuth of the attachment site) and
  `bindPsi0 = deltaPsi` (the registry reference Stage B would need).

**Modelling choice (stated, and deliberately conservative).** `deltaPsi` is evaluated at the **canonical
attachment arc** — there is **no axial search** over nearby presentations. The head attaches at its perpendicular
foot, exactly as the canonical gate decides; the target zone is therefore swept past a fixed motor by **relative
sliding** (the temporal moving-target-zone picture). The alternative — scanning ±4 monomers and taking the
best-registered presentation, as the legacy `matSurfaceAzim` does for the azimuth — was rejected because adjacent
monomer presentations differ by ~166.5°, so a ±4 scan almost always finds a well-registered site and the hazard
would be ≈1 everywhere, destroying the mechanism under test. The consequence is that axial-compliance-assisted
site selection is **not** represented; this is recorded as a limitation (§16).

## 6. Retained registry formulation (state only; Stage B not executed — see §12)

`MotorStore.bindPsi0[m]` — one float per motor, the signed mismatch at the moment of attachment, written once at
the FREE→bound transition and never recomputed while bound. Default 0, read by no canonical kernel ⇒ allocating it
is byte-identical for every existing harness. Together with `bindAzim` (the material-latched attachment azimuth)
it is a complete minimal representation of the preferred relative motor–actin orientation.

## 7. Torque-coverage audit (before adding any new torque)

Every angular / orientational interaction present in the explicit-S2 single-head gliding model:

| Interaction | Coordinate | Between | Reaction on the filament? | Overlaps a bound angular registry about `uActin`? |
|---|---|---|---|---|
| F8 cross-bridge spring (`kF8Code`) | translational bond `xF8 ↔ xSite` | head ↔ actin | **YES** — `−F` at `xSite`, `TS = RS×(−F)` gathered into `filament.torqueSum` | No (a force, not a torsional registry). Its `‖uActin` projection **is** the Stage-A off-axis axial drive. |
| Converter spring `kc·(psi−phi−thetaS)` | `theta = psi − phi` | lever ↔ converter (both motor-internal) | No | No |
| **Bind spring `kbnd·(psi − psiActin)`** | `psi`, about the base axis `eup` | head ↔ a per-motor **constant** `psiActin` | **No** | **Partial, but not a conflict.** This is the model's existing "stereospecific bound head orientation" spring. Its reference is a *constant*, not the actin helical phase; it acts about `eup`, which is **perpendicular** to `uActin ≈ bhat`; and it exerts **no reaction on the filament** — it is a motor-internal / base-frame torsional anchor. |
| Beam stretch / bend / floor (`ks`, `kb`, `kfloor`) | S2 beam nodes | motor-internal | No | No |
| F9 head-`uVec` alignment | head `û` vs seg `û` | head ↔ seg | **identically zero** — the explicit model's `xbParams[2] = j1FMT = 0` (align OFF) | No |
| F10 head-`yVec` alignment | head `yVec` vs seg `yVec` | head ↔ seg | **identically zero** (same `j1FMT = 0`); and the head `yVec` is a lab-fixed synthetic perpendicular, not a material frame | No |
| Node drag / Brownian | — | — | No | No |

**Verdict: no existing term implements an angular registry between the head and the actin material frame with a
filament reaction ⇒ a Stage-B registry would not double-count.** The `kbnd` spring is the nearest analogue but
lives on an orthogonal coordinate with no filament reaction. Stage A itself adds **no** force or torque at all —
it only changes *which* candidates persist — so its force-coverage is identical to the surface-binding port's
(F8 applied once, `TS` gathered once, no alignment moment to double-count).

## 8. GPU lowering, residency, and CPU/GPU equivalence

`scripts/run_vilfan_targetzone.sh -equiv` builds the **full** `buildGlidingGraph` with the target-zone task wired
in and executes it device-resident against the CPU runner, with `-Dtornado.recover.bailout=false` (any lowering
failure throws — no silent fallback).

```
surface OFF, alphaPsi=0 (canonical identity)  200 device-resident steps:
    bindMism=0  acceptMism=0  max|Δpsi0|=2.9e-06  max|ΔfilCoord|=2.83e-02 µm
    firstDiv=t=4 (chaotic float op-order)  bound CPU=2 GPU=1                        ⇒ PASS
surface ON, alphaPsi=6 (target zone)          200 device-resident steps:
    bindMism=0  acceptMism=0  max|Δpsi0|=5.2e-06  max|ΔfilCoord|=1.82e-02 µm
    firstDiv=t=181 (chaotic float op-order)  bound CPU=1 GPU=1                      ⇒ PASS
```

Isolated-kernel batch (fixture 11), 64 candidates spanning the full (−π,π] mismatch circle, device-resident:

```
Δbound=0  Δaccept=0  max|ΔdeltaPsi|=1.91e-06  max|Δregistry|=1.49e-06  max|Δw|=2.32e-06  max|Δazim|=0.00e+00
```

**Equivalence standard applied (stated explicitly).** Within the bit-close window the **attachment events, the
accept/reject decisions, and the retained material azimuth are EXACTLY identical** CPU↔GPU; the continuous
mismatch/weight agree to float32 last-bit. Literal bit-identity of the *angle* is not attainable on this toolchain
because `deltaPsi` is refined by a `sin`/`cos` Newton step whose host-JIT and PTX libm implementations differ in
the last bits — this is the project's documented standard for a transcendental-bearing kernel (cf.
`bondForcesSurface` at ΔF 4.3e-19). Switching from raw `acos(dot)` to the stable `asin(|cross|)`/`acos` form
improved the residual 7× (1.35e-05 → 1.91e-06 rad). Beyond `firstDiv` the trajectory decorrelates by float
op-order — the documented explicit-S2 chaotic standard, present identically in the canonical surface-OFF baseline
(`firstDiv = t = 4`, matching the lowering report's G2).

0 invalid states, 0 solver failures throughout.

## 9. Deterministic Stage-A fixtures (16/16 PASS)

`./scripts/run_vilfan_targetzone.sh -fixtures`

| # | Fixture | Result |
|---|---|---|
| 1 | `alphaPsi = 0` ⇒ trajectory **bit-identical** to the canonical surface-OFF path (300 steps) | PASS |
| 2 | perfectly aligned frames ⇒ `deltaPsi = 0` (0.000e+00) | PASS |
| 3 | ±0.7 rad imposed rotation ⇒ signed opposite mismatch (+0.7000 / −0.7000) | PASS |
| 4 | wrapping near ±π continuous (3.14059 → −3.14059; wrapped Δ = 2.0e-03) | PASS |
| 5 | rigid laboratory rotation ⇒ `deltaPsi` invariant (0.550000 vs 0.550000) | PASS |
| 6 | filament roll δ = 0.4 ⇒ target-zone phase shifts by −δ through the material frame (0.5500 → 0.1500) | PASS |
| 7 | polarity reversal `û → −û` ⇒ `deltaPsi` flips sign (+0.5500 / −0.5500) | PASS |
| 8 | mirroring the scene (z → −z) ⇒ handedness reversal, `deltaPsi` flips sign (+0.5500 / −0.5500) | PASS |
| 9 | angular weight symmetric in mismatch magnitude | PASS |
| 10 | angular weight decreases monotonically with `|deltaPsi|` | PASS |
| 11 | CPU ≡ GPU: decisions **exact**, mismatch/weight at float32 last-bit (device-resident) | PASS |
| 12 | `alphaPsi = 0` ⇒ identical binding decisions (bound-set hash, 300 steps) | PASS |
| 13 | large `alphaPsi` narrows the target zone **without** an absolute-laboratory-azimuth preference | PASS |
| 14 | **no translation ⇒ zero mean signed attachment phase** (⟨Δψ⟩ = −0.0003 rad) | PASS |
| 15 | **reversing translation reverses the flux asymmetry** (⟨Δψ⟩ +0.0570 → −0.0581 rad) | PASS |
| 16 | **disabling attachment depletion removes the asymmetry** (\|⟨Δψ⟩\| 0.0570 → 0.0002 rad) | PASS |

Measured `alphaPsi = 6` weight profile at `|Δψ| = 0, 0.2, 0.4, 0.8, 1.2, 1.8, 2.6` rad:
`1.000 0.887 0.619 0.147 0.013 0.000 0.000`.

### 9.1 The kinematic moving-target-zone rig (fixtures 14–16)

A dynamics-free rig isolates the kinetics: a straight filament is translated at a **constant imposed velocity**
past a fixed bed of 64 motors with fixed head geometry; every step each free motor is offered as a geometric
candidate, `matTargetZone` applies the hazard, and an accepted head is held for a fixed dwell (depletion ON) or
released immediately (depletion OFF). 60 000 steps per condition, `alphaPsi = 6`, `v = 2.5 µm/s`:

```
                    ⟨Δψ⟩ at attachment   leading fraction   accepted
  v = +2.5 µm/s          +0.0570 rad          0.530           23 658
  v = −2.5 µm/s          −0.0581 rad          0.530           23 575
  v =  0                 −0.0003 rad            —             33 082
  v = +2.5, no depletion +0.0002 rad          0.500          272 309
```

This is the **decisive controlled demonstration of the mechanism**: a symmetric hazard plus finite attachment
kinetics plus pool depletion produces a signed, translation-direction-locked attachment-flux asymmetry that
vanishes when either the translation or the depletion is removed. The leading fraction stays at 0.530 under
velocity reversal (as it must — "leading" is defined relative to the drift), while the signed mean flips.

### 9.2 Leading/trailing phase convention

The presented azimuth at a fixed motor drifts at `dphi/dt = twistRate * ds/dt = -twistRate * (v·uActin)`, so the
mismatch drifts at

```
D = d(deltaPsi)/dt = twistRate * (v · uActin)
LEADING  (approaching registry)  ⇔  sign(D) * deltaPsi < 0
TRAILING (receding from registry)⇔  sign(D) * deltaPsi > 0
```

`v·uActin` is the measured filament translation along its own axis (a least-squares slope of centroid·b̂ against
time), **not** laboratory x. The convention accounts for filament polarity (through `uActin`), gliding direction
(through `v`), and helical handedness (through `twistRate`), and is validated in the deterministic translated-
filament fixture above: with `v = +2.5` and `twistRate < 0`, `sign(D) = −1` and the measured `⟨Δψ⟩ = +0.057 > 0`
is on the leading side.

<!-- STAGE_A_RESULTS -->

<!-- STAGE_B_C -->

<!-- SYMMETRY_DT_3JS -->

<!-- CANONICAL_LIMITS_NEXT -->
