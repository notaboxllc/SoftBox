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
  this assay class. The timestep study also runs device-resident (§15).

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

## 10. Stage A dynamic experiment

`./scripts/run_vilfan_targetzone.sh -stageA -steps 6000 -seeds 10 -density 800 -target-zone-alpha 6 -gpu`
— **device-resident**, 10 seeds, 6000 steps (15 ms of simulated time) per arm, matched duration and matched
parameters across arms, `Ractin = 3.5 nm`, roll spring OFF, no bound registry, steric OFF (plus one steric-ON
control). Log: `RUN_LOGS/vilfan_targetzone/stageA_gpu_d800_big.txt`.

```
arm                     glide µm/s      avgB  accFrac    ⟨Δψ⟩acc ± SEM   ⟨Δψ⟩cand   leadAcc−leadCand ± SEM  τnet N·m ± SEM    cancel
baseline (no surf/no TZ)  -3.487±0.180  7.21    —              —             —                 —          -9.1e-24±2.9e-24    107
surface only (blind)      -3.160±0.226  7.35    —              —             —                 —          +3.4e-22±3.0e-22    114
target zone alphaPsi=0    -3.555±0.181  7.13   1.000   -0.0374 ± 0.0536   -0.0374     +0.0000 ± 0.0000    -2.4e-22±2.8e-22    167
target zone alphaPsi=4    -3.542±0.083  6.36   0.178   -0.0084 ± 0.0079   -0.0269     -0.0037 ± 0.0093    +5.0e-22±4.2e-22     67
target zone alphaPsi=6    -3.394±0.229  5.89   0.139   +0.0012 ± 0.0167   +0.0077     -0.0024 ± 0.0228    +3.9e-22±2.5e-22     78
target zone alphaPsi=8    -3.437±0.178  5.90   0.128   +0.0081 ± 0.0097   -0.0207     -0.0081 ± 0.0106    +1.8e-22±2.8e-22    174
target zone a=6 + steric  -3.362±0.210  6.06   0.143   +0.0077 ± 0.0093   +0.0080     -0.0094 ± 0.0069    +2.1e-22±3.4e-22    151
```

Per-phase attachment kinetics, `alphaPsi = 6`, pooled over 10 seeds (8875 geometric candidates, 1239 attachments):

```
   Δψ bin   candidates   mean w   accepted     flux    lead/trail
    -2.88          822   0.0000          0   0.0000    LEAD
    -2.36          845   0.0000          0   0.0000    LEAD
    -1.83          754   0.0001          0   0.0000    LEAD
    -1.31          734   0.0106          6   0.0007    LEAD
    -0.79          641   0.1879        128   0.0144    LEAD
    -0.26          627   0.7736        479   0.0540    LEAD
    +0.26          627   0.7768        498   0.0561    TRAIL
    +0.79          671   0.1806        122   0.0137    TRAIL
    +1.31          714   0.0105          6   0.0007    TRAIL
    +1.83          771   0.0001          0   0.0000    TRAIL
    +2.36          844   0.0000          0   0.0000    TRAIL
    +2.88          825   0.0000          0   0.0000    TRAIL
  drift sign sgn(D) = +1  ⇒  LEADING = sgn(D)·Δψ < 0
  accepted-azimuth histogram (8 bins, −π..π): [10972 22723 18403 8293 2720 1045 1391 4807]
```

### 10.1 What the target zone DID do

- **Stereospecificity is real and acts as designed.** The angular hazard is smooth (§9 weight profile), narrows
  with `alphaPsi`, and strongly concentrates *which actin face* is attached: the accepted-azimuth histogram is
  sharply peaked (`[10972 22723 18403 8293 2720 1045 1391 4807]`) where the azimuth-blind surface arm of the prior
  increment was flat (`[69 251 149 0 326 78 272 66]`,
  `docs/EXPLICIT_GLIDING_HELICAL_SURFACE_TWIRLING_FINDINGS.md` §10). Off-axis attachment is no longer distributed
  over all sides of the filament.
- **The pool-depletion notch is visible.** The candidate histogram is *depressed near registry* (627–671 in the
  four bins with `|Δψ| < 1`) and *elevated far from it* (754–845 at `|Δψ| > 1.5`): near-registry heads bind and
  leave the free pool. Finite attachment kinetics are demonstrably operating.
- **Engagement and gliding stay healthy.** `avgBound` 7.13 → 5.89 at `alphaPsi = 6` (83 % retained) with
  `accFrac = 0.14`; glide −3.56 → −3.39 µm/s, well within the ±0.2 seed SEM. No collapse, no instability, 0
  invalid states, 0 solver failures. This is **not** outcome A3 or A4.

### 10.2 What the target zone did NOT do — the primary endpoints

1. **Attachment phase biased? NO.** `⟨Δψ⟩` at attachment is +0.0012 ± 0.0167 (`alphaPsi = 6`), −0.0084 ± 0.0079
   (4), +0.0081 ± 0.0097 (8) — every value within ~1σ of zero, and the sign is not even consistent across
   `alphaPsi`.
2. **Attachment flux asymmetric despite a symmetric hazard? NO.** Leading attachments 6+128+479 = 613, trailing
   498+122+6 = 626 (Poisson σ ≈ 35 on the difference of 13). The paired statistic `leadAcc − leadCand`, which
   cancels any candidate-pool asymmetry, is −0.0037 ± 0.0093, −0.0024 ± 0.0228, −0.0081 ± 0.0106 — consistent with
   zero at every `alphaPsi`, and *negative* (trailing) where the mechanism predicts positive.
3. **Mean signed mismatch nonzero? NO** (endpoint 1 above).
4. **Reproducible axial-torque sign? NO.** `τnet` = +5.0e-22 ± 4.2e-22, +3.9e-22 ± 2.5e-22, +1.8e-22 ± 2.8e-22 —
   all ≤ 1.6σ, with the `alphaPsi = 0` control at −2.4e-22 ± 2.8e-22 of the opposite sign.
5. **Cancellation improved? NO.** `Σ|τ|/|Στ|` is 67–174 across the target-zone arms versus 114 for the
   azimuth-blind surface arm — no improvement, and no better than the previous increment's 7.2 at a different
   density. Concentrating the *azimuth* did not stop the torques cancelling.
6. **Symmetry reversal:** see §14 — the decisive reversal controls are in the deterministic kinematic rig, where
   they pass cleanly; the dynamic handedness control is consistent with the null and is underpowered.

### 10.3 The root cause: the target-zone phase is thermally re-randomised faster than it drifts

The kinematic rig (§9.1) proves the mechanism *works* when the phase is swept deterministically. The dynamic assay
does not reproduce it. The reason is quantitative and was measured directly.

`phi = twistRate · (bindArc − ½segLength)` with `twistRate = 1076 rad/µm`, so **1 nm of axial motion of the head's
perpendicular foot is 1.08 rad of target-zone phase** (the helical repeat is only 5.84 nm of arc). Tracking a head
that is a geometric candidate on two consecutive steps:

```
arm            deterministic drift |D|·dt   observed |ΔΔψ| per step   axial-jitter part   pairs
alphaPsi=4              0.01186                   1.09099                 1.09405           592
alphaPsi=6              0.01086                   1.15636                 1.15916           696
alphaPsi=8              0.01097                   1.12488                 1.12742           759   (rad/step)
```

**The phase moves ~1.15 rad per step from thermal axial jitter, versus 0.011 rad per step of deterministic sliding
drift — a factor of ~105, and the `twistRate·Δ(bindArc)` term accounts for essentially all of it** (1.159 of the
1.156 observed). One step displaces the phase by ~18 % of a full helical turn, so successive attachment attempts
sample an effectively **uncorrelated** phase. Vilfan's leading-versus-trailing asymmetry requires the motor to
experience the target zone *approaching* coherently; here the zone is resampled at random before the drift can
move it appreciably. The observed jitter corresponds to ≈1.07 nm of relative axial head/filament motion per
2.5 µs step, which is ordinary FDT Brownian motion — not a numerical artifact.

This also explains the near-flat candidate histogram: away from the depletion notch the candidate phase
distribution is uniform to within counting noise, exactly as an uncorrelated resampling predicts.

## 11. Stage A decision gate and classification

The gate required **all** of: a reproducible signed attachment-phase bias; a nonzero mean signed mismatch; a
consistent axial-torque sign or a clear reduction in torque cancellation; correct sign reversal under symmetry
controls; adequate engagement; numerical health. Engagement and numerical health pass; **the phase bias, the
mismatch, the torque sign and the cancellation ratio all fail.**

> **Stage A classification: A2 — target zones narrow binding, but no signed flux bias appears.**

Not A1 (no flux asymmetry, no torque-sign bias). Not A3 (engagement retained at 83 %, gliding preserved). Not A4
(all symmetry fixtures pass; 0 invalid, 0 solver failures; the device path is event-identical to the CPU runner).

**Per the task's instruction, Stage B was therefore NOT entered** ("if angular compatibility only suppresses
binding without producing a phase or torque bias, stop and diagnose the target-zone implementation before adding
a torsional spring"). The diagnosis is §10.3, and it is not an implementation defect: the same kernel, driven by
a deterministic sweep in the kinematic rig, produces the predicted asymmetry with the correct sign, the correct
reversal, and the correct depletion dependence (§9.1). The implementation is validated; the *dynamic regime*
lacks the phase coherence the mechanism needs.

## 12. Stage B — retained bound torsional registry: NOT ENTERED, and separately BLOCKED

Stage B was **not entered**, for two independent reasons. The first is procedural: the Stage-A gate failed (§11),
and the task's instruction in that case is explicit — stop and diagnose before adding a torsional spring. The
second is structural, was found by the §7 audit before any code was written, and is a stop condition the task
names ("the explicit motor lacks enough orientation state", "do not add a world-frame torsional anchor", "do not
apply torque only to the filament"). It would have blocked Stage B even if Stage A had passed, so it is recorded
here in full.

**The registry couple has nowhere to react against on the motor side.**

1. The explicit-S2 motor's only rotational generalized coordinates are `phi` and `psi`, and the production solver
   `matS2SolveStep` (a faithful port of `TwoBodyConverterMotor.s2SolveM`) parameterises **both about the single
   base axis `eup`**: `Jphi = eup × (C − P)`, `Jpsi = eup × (xF8 − C)`, and the cross-bridge reaction enters only
   as the generalized moments `QphiF8 = eup·((C−P)×F8h)`, `QpsiF8 = eup·((xF8−C)×F8h)`.
2. A bound angular registry acts about `uActin`. In this assay `uActin ≈ bhat`, and `econv = eup × bhat`, so
   **`uActin ⊥ eup`**. Projecting a couple `tau ∥ uActin` onto the motor's generalized coordinates gives
   `Q_phi = Q_psi = tau·eup ≈ 0` — **exactly zero by construction, not merely small**.
3. The head sub-body's torque slot `bondData[d+3..5]` — the only other motor-side torque channel — is **written
   but never consumed** in the explicit-S2 gliding path: `CrossBridgeSystem.segGather` reads only slots `[6..11]`
   (segment force and torque), the motor side is handled entirely by the generalized moments above, and the head
   pose is overwritten every step by `matPlaceHeadExplicit` from the beam solution.

An equal-and-opposite registry couple would therefore deliver its filament half in full and its motor half
**identically nowhere** — operationally a **world-frame torsional anchor on the filament**, which the task forbids
and which would manufacture a torque sign rather than measure one. Implementing it faithfully requires adding a
third rotational degree of freedom to the head **inside `matS2SolveStep`**, a canonical, frozen, production hot
kernel; that is out of scope for a noncanonical default-off mechanism increment and would invalidate the
explicit-S2 per-assay-class production sign-off.

> **Stage B classification: NOT REACHED — gate not met (§11) *and* blocked by insufficient motor rotational state.**
> Not B1/B2/B3/B4, since none of those presume an unimplementable registry.

The retained state Stage B needs is nevertheless **implemented, validated, and CPU/GPU decision-identical**:
`MotorStore.bindPsi0` (the signed mismatch at attachment) and `MotorStore.bindAzim` (the material-latched
attachment azimuth). Stage B becomes a small increment the moment the head acquires a rotational degree of freedom
about the bond.

For completeness, the model already contains the nearest analogue of a bound angular registry — the
`kbnd·(psi − psiActin)` "bind" spring, described in the source as the stereospecific bound-head orientation. It is
**not** a double-count (orthogonal coordinate, no filament reaction, constant reference), but it is precisely the
coupling point flagged by `docs/helical_binding/ACTIN_HELICAL_BINDING_AUDIT.md` §5, and making `psiActin`
actin-phase-dependent *with a proper reaction* is the principled Stage-B design once the degree of freedom exists.

## 13. Stage C — roll coherence: NOT REACHED

Stage C is gated on Stage A (or B) producing a reproducible nonzero mean axial torque. There is none (§10.2
endpoint 4: `τnet` ≤ 1.6σ at every `alphaPsi`, with the control of opposite sign). `RollSpringSystem` was
therefore **not** enabled on the explicit-S2 filament, exactly as instructed — the roll spring converts torque
into coherent whole-filament rotation and must never be credited with generating a torque sign.

> **Stage C classification: NOT REACHED.**

## 14. Symmetry controls

| Control | Result | Verdict |
|---|---|---|
| 1. Filament polarity reversal (`û → −û`) | `deltaPsi` flips sign exactly (fixture 7: +0.5500 / −0.5500) | PASS |
| 2. Gliding-direction reversal | Not physically available in the dynamic assay (glide direction is set by motor polarity). Realised in the **kinematic rig**: `v = +2.5 → −2.5 µm/s` flips `⟨Δψ⟩` +0.0570 → −0.0581 while the leading fraction stays 0.530 | PASS |
| 3. Mirrored helical handedness | `deltaPsi` flips sign under scene mirroring (fixture 8). The dynamic handedness diagnostic (`twistRate → −twistRate`) gave `⟨Δψ⟩` +0.0173 (LEFT) vs +0.0875 (MIRRORED) at 1 seed × 2000 steps — **underpowered** (the 10-seed SEM on this quantity is ±0.017) and consistent with the null; **not** interpreted as a signal | consistent with null |
| 4. Rigid laboratory rotation | `deltaPsi` invariant to 2e-4 rad over arbitrary axes/angles (fixture 5); the accept decision is identical over 12 rotations at `alphaPsi = 8` (fixture 13) | PASS |
| 5. No-translation control | `⟨Δψ⟩ = −0.0003 rad` over 33 082 attachments (fixture 14) | PASS |
| 6. `alphaPsi = 0` | Trajectory **bit-identical** to canonical; bound-set hash identical (fixtures 1, 12); campaign arm shows `accFrac = 1.000` and no bias beyond the candidate pool's own | PASS |
| 7. Registry stiffness zero | Stage B not entered; `bindPsi0` is written but read by nothing ⇒ zero registry torque by construction | PASS (trivially) |
| 8. Roll spring zero | `RollSpringSystem` never enabled on the explicit-S2 filament | PASS (trivially) |
| 9. Brownian-off deterministic control | The kinematic rig is fully deterministic (no Brownian, imposed kinematics) and reproduces the mechanism; fixtures 2–13 are Brownian-free unit evaluations | PASS |
| 10. Brownian-on ensemble | The 10-seed × 6000-step device-resident campaign (§10) | PASS (null, see §11) |

**No torque sign is fixed to the laboratory frame.** The compatibility rule is built from simulated physical
vectors only; fixtures 5 and 13 verify invariance under arbitrary rigid rotation, and fixtures 7/8 verify the
expected sign changes under polarity reversal and mirroring. The one place where the *scene* carries a laboratory
preference — all motors share one base frame `bhat/econv/eup` (§18 limitation 2) — is a property of the scene, not
of the rule, and rotating the whole scene rotates it with them.

## 15. Timestep result

`./scripts/run_vilfan_targetzone.sh -dt -steps 3000 -density 800 -target-zone-alpha 6 -gpu` (device-resident;
matched simulated time — the scene is constructed from dt-independent geometry, only the solver's dt-scaled
relaxation coefficients change). Log: `RUN_LOGS/vilfan_targetzone/dt_check.txt`.

```
dt = 2.50e-06 (3000 steps): glide=-4.799 µm/s  binds=73  ⟨Δψ⟩=+0.0449  τnet=-1.23e-21  turns=+1.6388
dt = 1.25e-06 (6000 steps): glide=-3.211 µm/s  binds=89  ⟨Δψ⟩=-0.0072  τnet=+1.57e-21  turns=+0.4426
phase: dt=2.50e-06  drift/step=0.01291  |ΔΔψ|=1.14514  axial=1.14637  noise/drift=88.7   accFrac=0.138
phase: dt=1.25e-06  drift/step=0.00432  |ΔΔψ|=0.88464  axial=0.88503  noise/drift=204.8  accFrac=0.115
```

- These are **single-seed** arms, so `glide`, `⟨Δψ⟩`, `τnet` and `turns` all sit inside the ±0.18 µm/s / ±0.017 rad
  / ±3e-22 N·m seed scatter measured at 10 seeds in §10. Their scatter is seed noise, **not** a resolved dt
  dependence, and no quantitative pitch is claimed from them (none is claimed at all — see §11).
- The **conclusion is dt-robust in the way that matters**: the phase-decorrelation ratio does not improve at finer
  dt — it gets **worse**, 88.7 → 204.8. This is the expected scaling: the deterministic drift per step is ∝ dt
  (0.01291 → 0.00432, exactly `|twistRate·v|·dt`) while the thermal phase jitter is diffusive, ∝ √dt
  (1.145 → 0.885 ≈ 1.145/√2). Refining the timestep therefore **cannot** recover the target-zone mechanism, and
  the A2 classification is not a coarse-dt artefact.
- Engagement is dt-stable (`accFrac` 0.138 → 0.115); no NaN/Inf, 0 invalid, 0 solver failures at either dt.

## 16. 3js output — directories and commands

Two matched dynamic movies (density 150, seed 101, 6000 steps, stride 40 ⇒ 151 frames each):

```
threejs_vilfan_tz_blind         surface binding ON, azimuth-blind  (target zone OFF)
threejs_vilfan_tz_targetzone    surface binding ON + target-zone hazard, alphaPsi = 6
```

Each frame carries: the actin **segments**; a per-segment **material-frame roll tick** (centre → centre +
0.02·`yVec`); the **cross-bridge line** (bound head → the reconstructed off-axis attachment site); the **local
actin surface-normal marker** at each attachment (axis → 3× the surface point — the presented binding face); the
**motor-side binding-direction marker** (head centre along its own long axis), **coloured by the angular
mismatch** at attachment (`notADPRatio = 1 − |deltaPsi|/π`, so well-registered heads render at one end of the
scale and poorly-registered at the other); and the **motors** (rod / lever / head, coloured bound vs unbound).
All frame markers are **visualisation only and never force-bearing**.

Stage C was not reached, so no roll-spring movie exists.

**Generate:**
```
./scripts/run_vilfan_targetzone.sh -3js threejs_vilfan_tz -steps 6000 -stride 40 -density 150 -seed 101 -target-zone-alpha 6
```
**View:** from `~/Code`: `python3 SoftBox/sim_server.py 8000`, then open
`http://localhost:8000/SoftBox/sim_viewer_boa.html` (Recent picker → the `vilfan_tz` runs).

## 17. Canonical-status statement

Entirely **noncanonical, flag-gated, default-off, byte-identical when disabled**. No canonical default, parameter,
chemistry, force law, S2/stroke mechanics, dt, RNG stream, event ordering, manifest, or `MotorModel.CANON_VERSION`
changed; **`MotorModel.CANON_VERSION` was not bumped**. `Ractin = 3.5 nm` and `surfaceExclusion = 5.5 nm` were kept
at their existing values and were **not** tuned. `alphaPsi ∈ {0, 4, 6, 8}` are literature-grounded experimental
values; **none is declared canonical**. `matBindExplicit`, `matS2SolveStep`, `matSurfaceAzim`,
`matSurfaceStericPrune`, `bondForces` and `bondForcesSurface` are byte-unchanged. `BoA-v1ref` was not touched.

Diff shape — additive only:
- `softbox/TwoBodyBeamAnalyticGpu.java`: new `matTargetZone`, `tzAsin`, `tzAngle`, `wangU01`, `TZ_SALT`. No
  existing method changed.
- `softbox/MotorStore.java`: new `bindPsi0` field + allocation + init (default 0, read by no canonical kernel).
- `softbox/ExplicitCompleteMatHarness.java`: `TZ_ON`/`TZ_ALPHA`/`TZ_HARD_RAD`/`TZ_DIAG`/`TELEMETRY` statics,
  `ExMat.tzP`/`tzDiag`, `packExMat` allocation, one `if (tzOn())` call in `stepGlidingCPU`, one gated task +
  gated transfers + one gated worker-grid entry in `buildGlidingGraph`, and one gated measurement-only host
  readback. The legacy `matSurfaceAzim` call gained an `if (!tzOn())` guard (target-zone mode retains the azimuth
  itself). With `TZ_ON = false` and `TELEMETRY = false`, every one of these is inert.
- **new**: `softbox/VilfanTargetZoneHarness.java`, `scripts/run_vilfan_targetzone.sh`, this report,
  `RUN_LOGS/vilfan_targetzone/`.

Off-path identity is **verified, not asserted**:
- fixture 1 — `alphaPsi = 0` gives a **bit-identical** 300-step trajectory vs the canonical surface-OFF path;
- fixture 12 — identical bound-set hash over the same window;
- the legacy surface-binding fixtures re-run unchanged, 10/10 PASS, with the same numbers as before this
  increment (`F8 axial torque R=3.5 nm 1.921e-21 N·m; R=0 7.527e-24 N·m`);
- the canonical explicit-S2 device gate re-runs unchanged: `§8 bind-identity mism=0 ⇒ PASS | §10 binds=11
  detach=10 boundC=1/1 maxΔfil=1.3e-01 firstDiv=t=4 invalid=0 ⇒ PASS`, `=== COMPLETE-MAT GATE: PASS ===` —
  identical to the values in `docs/EXPLICIT_S2_GPU_LOWERING_REGRESSION_FINDINGS.md` §11.

## 18. Limitations

1. **One-angle mismatch only.** The explicit-S2 head has no rotational degree of freedom about its own long axis
   and no material `yVec`, so the compatibility rule uses the azimuthal mismatch in the plane ⊥ `uActin` and
   discards head pitch. Full 3-DOF stereospecific compatibility is not representable in this model (§4.2). This
   is the same limitation that blocks Stage B (§12).
2. **All motors share one base orientation frame.** `frameArr` supplies the *same* `bhat/econv/eup` to every motor
   (only the anchor `g4E[m]` is per-motor), so every head's binding direction lies in the same `(bhat, eup)`
   plane. The compatibility rule is rotationally covariant (fixtures 5, 13) and the accepted azimuth is a
   *material* quantity, but the scene does not sample the azimuthal disorder of a real myosin-coated coverslip.
   A per-motor random base azimuth is the obvious next scene refinement.
3. **No axial site search.** `deltaPsi` is evaluated at the canonical perpendicular-foot attachment arc; axial
   compliance-assisted selection of a better-registered nearby presentation is not represented (§5). This was a
   deliberately conservative choice — a ±4-monomer scan would have made the hazard ≈1 everywhere.
4. **Stage B not implementable** in this lineage without a new head rotational degree of freedom inside the frozen
   canonical solver (§12); Stage C consequently not reached (§13).
5. **CPU/GPU agreement is decision-exact, not value-bit-exact** for the continuous mismatch (§8): a `sin`/`cos`
   Newton refinement is involved. Beyond `firstDiv` the trajectory decorrelates chaotically, exactly as the
   canonical baseline already does.
6. **Single density, single filament length, single motor model.** ρ = 800 heads/µm², explicit-s2-l40. The
   density dependence of the (null) flux asymmetry was not mapped; the phase-decorrelation ratio is set by
   `twistRate`, the axial diffusivity and the glide speed, none of which density changes strongly.
7. **The dt arms are single-seed** (§14) and are used only to test the phase-coherence scaling, not to make any
   quantitative claim.
8. The 3D surface steric rule remains a minimum attachment-footprint separation, not full motor-domain excluded
   volume (inherited limitation).

## 19. Exact next smallest step

**Do not add stiffness, torque, or a lateral power stroke to rescue the result.** The Stage-A verdict is A2 and
the cause is measured (§10.3): the target-zone phase decorrelates ~105× faster than it drifts, because
`twistRate = 1076 rad/µm` converts ~1 nm of ordinary thermal axial jitter into ~1.1 rad of helical phase.

**The exact next smallest step is a diagnostic, not a new mechanism: measure the axial phase-coherence budget of
the bound/attaching head, and determine whether any physically defensible constraint makes the phase persist.**
Concretely, in this same harness and with no new force law:

1. Record the *relative* axial displacement spectrum of the head's perpendicular foot on the filament
   (`bindArc(t)` for a persisting candidate) and split it into (a) filament longitudinal Brownian motion,
   (b) S2-beam node Brownian motion, (c) the `phi`/`psi` pose jitter. `phaseAxial ≈ phaseStep` already shows the
   axial channel is the whole story; this attributes it to a *body*.
2. From that, compute the coherence time `t_c` at which the diffusive phase spread `twistRate·√(2 D_ax t)` equals
   the drift `|twistRate·v|·t`, i.e. `t_c = 2 D_ax / v²`, and compare it to the mean candidate residence time.
   A leading/trailing asymmetry is only available if residence ≳ `t_c`.
3. Only if (2) says the budget is reachable, ask which single constraint closes the gap — and test that
   constraint on its own merits before wiring it to the target zone.

If (2) says the budget is **not** reachable at biological drag and temperature, the correct conclusion is that
Vilfan-style kinetic target-zone selection cannot be the twirl driver in a model whose head is free to diffuse
axially by ~1 nm on the cross-bridge timescale, and the twirl question should move to the *mechanical* branch —
which is where Stage B already points, and which is blocked on giving the explicit-S2 head a rotational degree of
freedom about the bond (§12). **That degree of freedom is the single highest-value structural change**, because it
unblocks Stage B, and only Stage B can produce a torque that does not rely on kinetic phase coherence at all.

Do **not** proceed to Stage C (roll spring) under any of these branches until a reproducible nonzero net axial
torque exists — there is none today.

---

## Completion summary

- **Question asked:** does a continuous, stereospecific actomyosin orientation constraint generate a biased
  attachment flux and a nonzero mean axial torque during explicit-S2 gliding?
- **Answer: NO in the dynamic assay, YES in a deterministic controlled rig — and the difference is measured.**
- **Stage A: A2** (target zones narrow binding; no signed flux bias). **Stage B: NOT REACHED** (gate not met, and
  separately blocked by insufficient motor rotational state). **Stage C: NOT REACHED.**
- **Files changed:** `softbox/TwoBodyBeamAnalyticGpu.java` (`matTargetZone`, `tzAsin`, `tzAngle`, `wangU01`),
  `softbox/MotorStore.java` (`bindPsi0`), `softbox/ExplicitCompleteMatHarness.java` (flag statics + `ExMat` fields
  + gated task/transfers/readback); **new** `softbox/VilfanTargetZoneHarness.java`,
  `scripts/run_vilfan_targetzone.sh`, this report, `RUN_LOGS/vilfan_targetzone/`.
- **Flags:** `-target-zone-alpha <v>` (default 0 = off), `-density`, `-seed`, `-seeds`, `-steps`, `-stride`,
  `-gpu`, `-actin-bind-radius-nm 3.5`, `-surface-exclusion-nm 5.5`. `-bound-registry-alpha` was **not** added —
  Stage B is blocked (§12) and adding a dead flag would misrepresent what exists.
- **Angular-hazard equation:** `wPsi = exp(-0.5 * alphaPsi * deltaPsi^2)`, applied to the eligible attachment
  before commitment, sampled with a private wang-hash variate; `alphaPsi = 0` skips the draw entirely.
- **Parameter values tested:** `alphaPsi = 0, 4, 6, 8` (full sequence reported; nothing tuned, nothing promoted).
- **Fixtures:** 16/16 PASS. **GPU:** full target-zone gliding graph device-resident, `bailout=false`, no fallback;
  attachment events and accept decisions **exactly** CPU≡GPU; mismatch/weight at float32 last-bit.
- **Engagement/glide:** `avgBound` 7.13 → 5.89 (83 % retained) and glide −3.56 → −3.39 µm/s (within SEM) at
  `alphaPsi = 6` — the constraint is affordable; it simply does not produce a directional bias.
- **Cumulative turns / turns per micron:** not reported as a result. There is no reproducible net axial torque
  (§10.2), no roll spring (§13), and the per-segment roll of this filament is incoherent, so any turns number
  would be thermal roll scatter. Quoting one would misrepresent a null.
- **Exact next smallest step:** §19 — measure the axial phase-coherence budget (`t_c = 2·D_ax/v²` versus the
  candidate residence time) before proposing any new constraint; and treat "give the explicit-S2 head a rotational
  degree of freedom about the bond" as the single highest-value structural change, since it is what unblocks the
  mechanical (Stage B) branch that does not depend on kinetic phase coherence at all.
