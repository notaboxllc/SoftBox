# Vilfan-like Target-Zone Twirling — Build and Deterministic Validation

**Single controlling report for this task.** Branch `feature/vilfan-target-zone`, worktree
`../softbox-vilfan-target-zone`, base revision `9683598`. **CPU only** — no GPU call, no TaskGraph, no
device context, at any point. Noncanonical, flag-gated, default-off; no canonical default, parameter,
chemistry, force law, stiffness, stroke, rate, catch/slip law, rupture rule, motor density or viscosity was
changed.

> Status placeholders marked **[RESULTS PENDING]** are filled by the campaign sections below.

---

## 1. Executive conclusion

[RESULTS PENDING]

## 2. The exact scientific question

Does adding *assay-level* target-zone accessibility to the existing SoftBox explicit-S2 discrete-actin-site
motor — with **zero handed component in the working stroke** — reproduce the Vilfan target-zone twirling
mechanism under favourable, deterministic assumptions?

The load-bearing causal chain, and the only thing that counts as reproducing it:

```
helical site passage through a surface-facing target zone
    -> biased attachment BEFORE versus AFTER the zone centre        (A_TZ)
    -> signed axial torque                                          (<tau>)
    -> signed filament rotation                                     (Omega, turns/µm)
```

"The filament rotated" is **not** the endpoint. The asymmetry, the torque and the rotation must agree
causally and must reverse together when the actin lattice is mirrored.

---

## 3. Stage 0 — source, history and literature audit

### 3.1 What already existed (and is REUSED, not duplicated)

The repository already contained a large amount of directly relevant machinery. Nothing below was
re-implemented.

| Concern | Existing code reused | Status found |
|---|---|---|
| Discrete helical actin sites | `ChiralSiteSystem.siteSnap` (+ `siteOccupancyResolve`) | working, flag-gated (`SITE_MODE`) |
| Site azimuth / local site frame | `siteSnap` frame block: `uSite = filUVec`, `nSite = cos φ·segY + sin φ·segZ`, `tSite = mirror·(uSite×nSite)` | working |
| Filament body-fixed roll | `DerivedGeometrySystem` material `yVec`; roll readout `ChiralSiteHarness.rollIncrementTransported` | working, validated |
| Actin-lattice mirroring | `ExplicitCompleteMatHarness.MIRROR_SIGN` → `chiP[2]/[3]` twist-rate + `chiP[13]` tangential sense | working, validated in the viscosity mirror control |
| Off-axis (surface) attachment ⇒ axial moment arm | `SURFACE_ON` + `CrossBridgeSystem.bondForcesSurface` at `Ractin = 3.5 nm` | working |
| Axial-torque readout | `ChiralSiteSystem.axialTorque` (bond segment-side torque · û) | working |
| Converter stroke skew (the *existing* chirality source) | `CONV_SKEW_DEG` → `ChiralSiteSystem.convFrameStep` | working; **held at 0 throughout this task** |
| Old actin-side binding/interface skew | `EPS_BIND_DEG`, `EPS_STROKE_DEG` | working; **held at 0 throughout** |
| Filament Brownian channel switches | `BR_FIL_AXIAL/TRANS/ROLL/OTHROT` → `BrownianForceSystem.brownChannelMask` | working, default-off |
| Motor Brownian (binding-state) switch | `matc[3]` bits 0/1 in `matS2SolveStep` | working, default-0 |
| Head rotational DOF + bound registry | `HEAD_ROLL`, `REG_K` → `ChiralSiteSystem.headRollStep` | working; **held OFF** (no registry torque) |
| A prior *continuous* target-zone hazard | `TwoBodyBeamAnalyticGpu.matTargetZone`, `TZ_ON/TZ_ALPHA` | working; **held OFF** — see §3.3 |
| Prior deterministic kinematic rig | `VilfanTargetZoneHarness` fixtures 14–16 | working, but dynamics-free (no torque, no rotation) |

**Existing prior verdict (essential context).** `docs/VILFAN_TARGET_ZONE_BINDING_AND_TWIRLING_FINDINGS.md`
implemented a *continuous* Vilfan angular-mismatch hazard `w = exp(−½·α·Δψ²)` on the explicit-S2 gliding
assay and classified the outcome **A2**: target zones narrowed binding but produced **no** signed flux bias,
**no** reproducible torque sign, and **no** resolved rotation. Its §10.4 root-caused this quantitatively: with
`twistRate = 1076 rad/µm`, ~1 nm of *ordinary thermal axial jitter* moves the helical phase by ~1.1 rad, so
the target-zone phase decorrelated ~105× faster than it drifted, and the ratio got **worse** at finer dt. Its
own dynamics-free kinematic rig, driven by a deterministic sweep, *did* reproduce the predicted asymmetry
(`⟨Δψ⟩ = +0.057`, reversing with velocity, vanishing without depletion) — but measured no torque and no
rotation.

**This task is the direct follow-on**: it removes exactly the confound that produced the A2 null (mechanical
Brownian motion), and it closes the chain the kinematic rig left open (asymmetry → torque → rotation), on the
real dynamic motor rather than a dynamics-free rig.

### 3.2 Audit answers (the ten required determinations)

1. **Site axial coordinate and azimuth.** A site is identified by its filament-global integer index
   `k = round(globalArc/rise)`, `globalArc = segCumArc[s] + localArc`. Its material coordinates are
   `localArc = k·rise − segCumArc[s]`, and its azimuth is either the analytic native helix
   `φ = twistRate·(localArc − ½segLength)` or the staircase `φ = k·stairPhase`. Its 3-D position is
   `xSite = segCentre + (localArc − half)·uSite + Ractin·nSite`. The lattice is generated analytically from
   the filament's own material frame each step — it bends, translates, rotates and **rolls** with the
   filament and stores no per-site state.
2. **Stored starting azimuth.** There was no explicit stored starting azimuth. One was added as an assay
   fixture (`-az0`), applied by rotating every segment's material `yVec` about its own `uVec` at build. It is
   part of the material frame, so it survives the whole run and is logged in the manifest. Gate A4 verifies
   it is applied exactly (37.000003° for a commanded 37°). **CPU-only fixture; no serialization path is
   involved in this task.**
3. **Sign conventions.** Collected in one place (`VilfanTargetZoneDeterministicHarness` class javadoc):
   filament polarity `axis = uVec` (pointed→barbed); axial translation `v` signed along `+axis`; axial roll
   from the body-fixed transported material `yVec`; actin helical handedness from the signed phase gradient
   `dφ/darc` (native `twistRate = −1076 rad/µm`, LEFT-handed); mirror state `MIRROR_SIGN = ±1` negating both
   the twist rate/stair phase and the site tangential sense; converter skew `CONV_SKEW_DEG` (held at 0).
4. **Site azimuth → laboratory frame.** `nSite = cos φ·segY + sin φ·segZ` with `segZ = uSite × segY`, where
   `segY` is the rolling material reference maintained by `DerivedGeometrySystem`. Gate A5 verifies that
   rolling the filament by δ shifts a site's laboratory azimuth by exactly δ.
5. **Substrate-facing direction.** The scene's lab-fixed frame is `bhat = +x`, `eup = +z`, `econv = +y`;
   `eup` points from the motor pivot up toward the filament, so the substrate-facing direction on the
   filament is `−eup`. The target-zone gate does **not** hard-code it: it uses the radial direction from the
   filament axis toward each motor's own **fixed substrate tether point** (`g4E[m]`, where that motor's S2
   emerges from the coverslip). Gate C5 *measures* that the two coincide for the motors that actually attach
   (mean `cHat·(−eup) = 0.9856`, min 0.9088) rather than assuming it.
6. **Pre-existing target-zone code.** Yes — the continuous `matTargetZone` hazard (§3.1). It is
   **physically active** when `TZ_ALPHA > 0`, but it is a *hazard on the continuous perpendicular-foot
   azimuth*, not an accessibility rule on discrete sites, and it is **held OFF** for this task (`TZ_ON =
   false`) so that exactly one accessibility mechanism is under test. See §3.3 for why a new gate was needed.
7. **Independently disableable Brownian channels (found).** Filament axial / transverse / roll /
   other-rotation (`brownChannelMask`); motor bound / unbound (`matc[3]` bits 0–1); head-roll
   (`HEAD_ROLL_BROWN`). **Missing:** per-body motor channels. Added (§4).
8. **Imposed translation.** None existed in the dynamic assay (only the dynamics-free rig). Added as a
   *kinematic* fixture that overwrites the pose after integration and therefore injects **no force and no
   torque** — see §6 and gate D.
9. **Chemistry that stays active.** The full Lymn–Taylor nucleotide cycle, the load-dependent catch/slip
   detachment and the rigor rupture pathway are **untouched and remain stochastic**. Mechanical Brownian
   forcing and stochastic chemistry are different things and are separated here: the fixture silences the
   former and keeps the latter. Seeds are therefore *chemical* seeds.
10. **CPU/GPU shared code.** `stepGlidingCPU` and `buildGlidingGraph` call the *same* kernel methods. Both
    call sites of the modified `siteSnap` were updated together, and the whole tree compiles; **no device
    execution was performed in this task**, per the stopping boundary.

### 3.3 Literature assumptions taken, and where SoftBox differs

Only the assumptions needed to reproduce the mechanism were taken from the target-zone literature; no attempt
was made to reproduce a particular simulator.

**Faithful mechanism assumptions (taken):**
- Myosin heads tethered to a planar substrate can bind actin only over a restricted range of *azimuth* — the
  target zone — because the actin-binding interface must face the head.
- The zone is fixed in the laboratory (it is set by where the motor is), while the *sites* are carried by the
  helical filament, so translation sweeps sites through the zone.
- Attachment kinetics are finite and the free-head pool depletes, so which part of the zone a head catches is
  not symmetric.
- The resulting attachment offset acts at the actin radius, giving a moment about the filament axis.

**SoftBox-specific implementation choices (declared):**
- The zone-centre direction is the *covariant* radial direction toward each head's own F8 anchor, not a
  hard-coded laboratory axis (so the rule survives an arbitrary rigid rotation of the scene — gate B/C).
- Accessibility is a **hard geometric admissibility rule** on the candidate site set, not a graded hazard.
  This is the more conservative choice: it adds no rate, no parameter that can be tuned into a bias, and no
  RNG stream. A rejected head simply has no accessible site and does not attach (no renormalisation).
- The bond, the stroke, the chemistry and the moment arm are the *existing validated* ones.

**Parameters from geometry / literature:**
- `Ractin = 3.5 nm` (existing, untouched) — the moment arm.
- Actin azimuthal quantization `360/13 = 27.6923°` and axial rise 2.7 nm (one monomer) — actin's real
  geometry.
- Target-zone half-width **40°**. Rationale: the conventional actomyosin target zone spans ~2–3 adjacent
  monomers of the 13-monomer repeat, i.e. an accessible azimuthal fraction of ≈2.5/13 ≈ 19 %, which is a
  half-width of ≈35–40°. **It was not tuned to reproduce any pitch.** Gate C6 confirms the realised
  accessible fraction is `zone/180° = 0.222` (measured 0.2244).

**Parameters introduced only as bounded assay fixtures (declared as such):**
- The prescribed axial speed `v` and its ladder.
- The stored starting filament azimuth `az0`.
- The rigid single-rod filament, the fixed height and the fixed tilt.
- **The monotone staircase lattice (mode 6)** — see §5, the single most important declared idealization.

---

## 4. Stage 1 — modular feature architecture

Every control is independent and **default-off**; none is bundled behind a single `-vilfan-mode`. The run
manifest records every underlying switch explicitly.

| # | Control | Flag | Default | Where |
|---|---|---|---|---|
| 1 | target-zone accessibility | `-zone-deg <v>` / `-no-zone` | OFF (`chiP[25] ≤ 0`) | `ChiralSiteSystem.siteSnap` |
| 2 | prescribed filament translation | `-v <µm/s>` / `-no-prescribe` | OFF | `AssayConstraintSystem` |
| 3 | filament axial-translation Brownian | `-filament-brownian-axial on\|off` | ON (canonical) | `brownChannelMask` |
| 4 | filament axial-roll Brownian | `-filament-brownian-roll on\|off` | ON | `brownChannelMask` |
| 5 | filament bending/tumbling Brownian | `-filament-brownian-other-rotation on\|off` | ON | `brownChannelMask` |
| 5b | filament transverse Brownian | `-filament-brownian-transverse on\|off` | ON | `brownChannelMask` |
| 6 | motor-head Brownian | `-motor-head-brownian on\|off` | ON | `matS2SolveStep` bit 4 (**new**) |
| 7 | converter Brownian | `-converter-brownian on\|off` | ON | `matS2SolveStep` bit 3 (**new**) |
| 8 | S2-node Brownian | `-s2-node-brownian on\|off` | ON | `matS2SolveStep` bit 2 (**new**) |
| 8b | head-roll Brownian | `-head-roll-brownian on\|off` | ON | `chiP[10]` (existing) |
| 9 | filament height constraint | `-no-prescribe` releases | OFF | `AssayConstraintSystem` |
| 10 | filament tilt/orientation constraint | `-no-clamp-tilt` | OFF | `AssayConstraintSystem` |
| 11 | rigid-filament fixture | `-no-rigid` | rigid single rod in the fixture | `buildS2Mat(..., rigid)` |
| 12 | converter skew amplitude | `-conv-skew-deg <v>` | **0.0** | existing `convFrameStep` |
| 13 | actin-lattice mirror state | `-mirror ±1` | +1 | existing `MIRROR_SIGN` |
| 14 | lattice selection | `-lattice 1\|4\|5\|6`, `-site-rise-nm`, `-site-stair-deg` | 6 (fixture) | `siteRise`/`siteStairPhase` |

**Not altered** (verified by inspection and by gate E): nucleotide kinetics, load-dependent kinetic laws,
working-stroke magnitude, S2 stiffness/geometry, binding stiffness, catch/slip, rigor rupture, motor density
defaults, canonical viscosity, and every Brownian amplitude when a channel is enabled.

### 4.1 Diff shape (additive only)

- `softbox/ChiralSiteSystem.java` — `siteSnap` gains one output array (`tzOff`) and a `chiP[25]/[26]`-driven
  accessibility filter. `tzHalf ≤ 0 && tzRec == 0` ⇒ **not one extra arithmetic operation executes and
  `tzOff` is never written**.
- `softbox/TwoBodyBeamAnalyticGpu.java` — `matS2SolveStep` gains policy bits 2/3/4 splitting the existing
  motor Brownian mask into S2-node / converter / head channels. Policy 0 ⇒ arithmetically bit-identical.
- `softbox/ExplicitCompleteMatHarness.java` — new default-off statics, two new `chiP` slots, the `tzOff`
  buffer, lattice mode 6, extended `motorBrownPolicy()`; both `siteSnap` call sites updated.
- **new** `softbox/AssayConstraintSystem.java`, `softbox/VilfanTargetZoneDeterministicHarness.java`,
  `scripts/run_vilfan_tz_deterministic.sh`, `ANALYSIS/vilfan_target_zone/analyze_target_zone.py`, this report.
- `softbox/ChiralSiteHarness.java` — one call-site argument added (no behaviour change).

---

## 5. Stage 2 — target-zone geometry

### 5.1 The gate

For a fresh bind, `siteSnap` already scans the `searchHalf` sites either side of the head's perpendicular
foot and keeps the one nearest in 3-D to the head's beam-solved F8 anchor. The accessibility gate acts on
that same candidate set:

```
cHat  = normalize( P_perp( E_m − axisPoint ) )     E_m = motor m's fixed SUBSTRATE TETHER POINT (g4E[m])
nSite = cos(φ_k)·segY + sin(φ_k)·segZ              the site's outward material normal
delta = signed_angle(cHat → nSite about u)  ∈ (−π, π]        the signed target-zone coordinate
ACCESSIBLE  ⇔  |delta| ≤ Δ                                   Δ = the zone half-width
```

`cHat` is built from the motor's own tether point and the filament's material frame — simulated quantities,
not a hard-coded laboratory axis — so the rule carries no chosen torque sign. Because it is referenced to
the axis *line*, it is invariant under the fixture's prescribed axial translation and under filament roll,
and the fixture clamps the tilt, so it is constant for the whole run. `delta = 0` is the zone centre. The
angle magnitude uses the project's validated float32-stable `tzAngle(|cross|², dot)` form, not `atan2` and
not raw `acos`.

> **A design error caught by gate C5, recorded because it matters.** The first implementation referenced the
> zone centre to the head's *instantaneous* beam-solved F8 anchor. That is covariant, but it is not a zone:
> at the moment of binding the head sits essentially on the actin surface, so the "zone centre" followed the
> head around the filament. Gate C5 measured `cHat·(−eup) = −0.05` (min −0.997) — the supposed
> surface-facing zone was pointing in every direction, including straight up — and the measured
> offset→torque slope even carried the opposite sign (`−6.28e-21` vs `+9.44e-21 N·m/rad`). Re-anchoring the
> zone on the motor's fixed substrate tether point fixed it (`cHat·(−eup) = 0.9856`). The gate is reported
> as a gate, not as a formality: it caught a substantive modelling error.

**Before / after.** The helical phase presented to a fixed motor drifts at

```
D = −(dφ/darc)·v ,      dφ/darc = stairPhase/rise  (staircase)  or  twistRate  (native helix)
BEFORE (approaching the centre)  ⇔  sign(D)·delta < 0
AFTER  (receding from the centre)⇔  sign(D)·delta > 0
A_TZ = (N_before − N_after) / (N_before + N_after)          (reported as NaN at zero denominator)
```

Mirroring negates `dφ/darc`, hence `D`, hence the before/after labelling — verified in gates B1/B2.

The **full attachment-offset distribution** is retained (24-bin histogram per seed plus the complete
per-attachment event record), not only the binary statistic.

### 5.2 The declared idealization: why a monotone staircase

This is the most important modelling decision in the task and it is stated plainly.

Actin's real 13/6 lattice advances **−166.5° per monomer**. Consecutive monomers are therefore nearly
antipodal, and the 13 azimuths of one repeat are visited in a *scrambled* order. Under pure axial
translation a given site's `delta` is **constant**, and the sequence of accessible sites a motor meets is
azimuthally scrambled rather than monotone. On such a lattice "a site passing through the zone", and hence
"before versus after the zone centre", is not well posed as a *temporal* ordering — the first-passage bias
the mechanism needs has nothing to act on.

Mode 6 (`stair-fixture`) therefore keeps actin's real azimuthal **quantization** (`360/13 = 27.6923°`), its
real axial **rise** (one monomer, 2.7 nm) and its real **repeat** (13 monomers ≈ 35.1 nm) but visits those
same 13 azimuths in **monotone** order — i.e. the single genuine long-pitch helix of 13 subunits per turn.
It is an idealization of the *ordering only*. It is exactly the "favorable assumption" the task asks for, and
the native 13/6 lattice is run alongside it as the faithful comparison (§9).

---

## 6. Stage 3 — the prescribed-translation fixture

`AssayConstraintSystem.kinematicFixture` runs **after** the integrator and overwrites the pose:

- **position** — every segment is placed on `coord0_s + (v·t)·axis`, which simultaneously prescribes the
  axial translation, pins the height and removes lateral drift;
- **tilt** — `uVec` is reset to the fixed `axis`;
- **roll — FREE** — `yVec` is only Gram–Schmidt-orthogonalized against `axis`, which removes the axial
  component and renormalizes but does **not** touch `yVec`'s azimuth in the plane ⊥ `axis`. The roll produced
  by the gathered axial torque survives intact.

Because it is kinematic it adds nothing to `forceSum`/`torqueSum` and therefore **cannot** exert an axial
torque by construction — the property gate D verifies directly. Both the commanded and the actual
translation are recorded every step; the maximum discrepancy over a run is reported.

---

## 7. Stage 4 — validation gates

`./scripts/run_vilfan_tz_deterministic.sh -gates -maty 0.01 -density 6000 -steps 12000 -seeds 4`
Log: `RUN_LOGS/vilfan_target_zone/gates.txt`. **36 PASS / 2 FAIL.** The two failures are F1/F2, and they
fail for a *physical* reason that the F3/F4 control isolates — see §7.1.

### A. Helical geometry — 5/5 PASS

| Gate | Result |
|---|---|
| A1 site rise matches the declared lattice | 2.700000 nm |
| A2 azimuthal advance matches the declared value | −27.692308° per site |
| A3 wrapping continuous through ±π | 2.000e-03 (expected 2.000e-03) |
| A4 stored starting filament azimuth applied exactly | 37.000003° for a commanded 37° |
| A5 body-fixed → laboratory: roll δ shifts lab azimuth by δ | Δ = 0.400000 rad for a commanded 0.400000 |

### B. Mirror transformation — 7/7 PASS

| Gate | Result |
|---|---|
| B1 handedness reverses | dφ/darc −179.0081 → +179.0081 rad/µm |
| B2 zone-phase drift reverses | D +358.0163 → −358.0163 rad/s |
| B3 filament pose and segment geometry unchanged | max\|Δcoord\| = 0.000e+00 µm |
| B4 motor anchor positions unchanged | max\|Δanchor\| = 0.000e+00 µm |
| B5 axial site spacing unchanged | 2.700000 / 2.700000 nm |
| B6 imposed velocity and laboratory axis unchanged | v = 2.000, axis = (1,0,0) |
| B7 mirror sign is the only chiral input | chiP[13] +1 / −1 |

RNG assignment is untouched by mirroring by construction: the mirror flips only `chiP[2]/[3]/[13]`, and no
RNG stream is keyed on them.

### C. Target-zone gate — 7/7 PASS

| Gate | Result |
|---|---|
| C1 site normal facing the motor ⇒ δ = 0 | max\|δ\| = 0.000e+00 rad over 17 azimuths |
| C2 site normal facing away ⇒ \|δ\| = π (occluded) | verified over 17 azimuths |
| C3 δ changes sign through the centre; before/after exchange | δ(−0.30) = −0.3000 BEFORE, δ(+0.30) = +0.3000 AFTER, sign(D) = +1 |
| C4 gate periodic and continuous across ±π | δ(π−) = +3.141493, δ(−π+) = −3.141493, wrapped Δ = 2.0e-04 |
| C5 zone centre **is** the substrate-facing direction (measured) | mean cHat·(−eup) = **0.9856** over 41 attachments, min 0.9088 |
| C6 accessible azimuthal fraction = zone/180° | measured 0.2244, expected 0.2222 |
| C7 **the causal link δ → torque, measured** | **dτ/dδ = +9.4390e-21 N·m/rad, r = +0.6403** over 41 attachments |

**C7 is the gate that makes the campaign interpretable.** It establishes empirically — not by assertion —
that a signed attachment offset produces an axial torque of a definite sign, with `δ > 0 ⇒ τ > 0`. Every
sign prediction in §8 follows from this measured mapping rather than from a geometric argument.

### D. Imposed translation, motors disabled — 6/6 PASS

| Gate | Result |
|---|---|
| D1 translation follows the prescribed velocity | actual 0.020000000 vs commanded 0.020000000 µm (max error 9.31e-10 µm) |
| D2 no lateral drift | 0.000e+00 µm |
| D3 no tilt develops | 0.000e+00 rad |
| D4 axial roll remains zero | 0.000e+00 rad over 4000 steps |
| D5 axial torque remains zero | 0.000e+00 N·m |
| D6 the control is genuinely motor-free | 0 attachments |

**The prescribed-translation fixture exerts exactly zero axial torque** — not "small", zero, because it is
kinematic. Success criterion 7 is satisfied outright.

### E. Default identity — 3/3 PASS

| Gate | Result |
|---|---|
| E1 recording the offset alters no binding decision and no trajectory bit | max\|Δcoord\| = 0, bound mismatches = 0, Δroll = 0 |
| E2 per-channel motor Brownian bits = 0 ⇒ bit-identical to the canonical mask | max\|Δcoord\| = 0 over 1500 steps |
| E3 all three motor channels off ≡ the global motor-Brownian switch off | max\|Δcoord\| = 0, Δroll = 0 over 1500 steps |

### F. Zero-chirality nulls — 2 FAIL (literal) / 2 PASS (achiral control)

| Gate | Result | Verdict |
|---|---|---|
| F1 zone OFF, skew 0 ⇒ no axial torque | τ = **−1.9224e-21 ± 5.4514e-22 N·m (3.53σ)** | **FAIL** |
| F2 zone OFF, skew 0 ⇒ no axial rotation | Ω = **−7.7466e+01 ± 1.6990e+01 rad/s (4.56σ)** | **FAIL** |
| F3 **achiral** lattice (180°/site) ⇒ no axial torque | τ = −1.2186e-22 ± 8.5769e-22 N·m (**0.14σ**) | PASS |
| F4 **achiral** lattice (180°/site) ⇒ no axial rotation | Ω = +1.6239e+01 ± 2.0644e+01 rad/s (**0.79σ**) | PASS |

### 7.1 Why F1/F2 fail, and why that is a result rather than a defect

The task's zero-chirality null switches off the *target zone* and the *stroke skew*, but it leaves the
**helical lattice** and **off-axis attachment** in place — and those two together are already a chirality
source. On a monotone helical lattice the azimuth of the nearest accessible site advances systematically as
the filament slides, so nearest-site binding at radius `Ractin` produces a systematically signed moment
about the axis with no target zone involved. F1/F2 are therefore measuring real physics, not a fixture bias.

F3/F4 supply the null the task actually needs. Setting the azimuthal advance to 180° per site makes the site
set `{0°, 180°, 0°, …}` **mirror-invariant** (mirroring negates the advance and −180° ≡ +180°), so the
lattice carries no handedness whatever, while every other element of the fixture — prescribed translation,
constraints, off-axis attachment at the same `Ractin`, the same chemistry, the same seeds — is unchanged.
The torque and the rotation both collapse to noise (0.14σ, 0.79σ).

> **Conclusion from the F block: the fixture itself injects no chirality. Any signed torque observed with a
> helical lattice is genuine lattice chirality — but it is NOT, by itself, evidence for the target-zone
> mechanism, because it survives with the target zone switched off.** Separating those two is exactly what
> the §8 campaign is for.

### G. CPU health — 6/6 PASS

All states finite; no forbidden nucleotide state (all in [0,3]); roll and torque finite; the prescribed
translation held to 9.31e-10 µm; **exact repeat** (same seed ⇒ bit-identical trajectory, max\|Δcoord\| = 0
and Δroll = 0); no attachment-record collisions. Zero solver failures and zero invalid states throughout.
The gate run also reports the engagement cost of the accessibility rule directly: 64 attachments accepted
against **791 fresh binds released for having no accessible site** — the zone rejects ~92 % of geometric
candidates, and that loss is reported, never renormalised.

### 7.2 Internal consistency: is the rotation actually torque-driven?

The overdamped statement is `Ω = τ / γ_roll`. Taking the F1/F2 pair, `γ_roll = τ/Ω = (−1.92e-21)/(−77.5) =
2.48e-23 N·m·s`, against the analytic axial drag of the fixture rod
`4πηR²L = 4π(0.1)(3.5e-9)²(1.76e-6) = 2.71e-23 N·m·s` — agreement to ~9 %. The measured rotation is the
gathered axial torque divided by the roll drag, which confirms both that the roll coordinate is genuinely
free under the constraint and that the rotation observable is not picking up tumble. This check is emitted
per arm as `ANALYSIS/vilfan_target_zone/consistency_campaign.csv`.

## 8. Stage 5 — deterministic proof of mechanism

### 8.1 Configuration

`./scripts/run_vilfan_tz_deterministic.sh -campaign -maty 0.01 -density 6000 -steps 20000 -warmup 2000
-seeds 8 -lattice 6`. Idealized monotone-staircase lattice (2.7 nm rise, −27.6923°/site); zone half-width
40°; converter skew **0°**; old binding/interface skew **off**; every mechanical Brownian channel **off**;
rigid single-rod filament; fixed height; fixed tilt; **free axial roll**; prescribed axial translation
+2 µm/s; ordinary stochastic attachment/detachment chemistry **retained**. 120 motors on a 2.00 × 0.010 µm
lawn strip; 8 matched chemical seeds; 20 000 steps (50 ms) with the first 2 000 discarded. Arms: zone ON/OFF
× native/mirrored lattice, plus six starting azimuths spanning one full actin repeat.

### 8.2 What "reverses under mirroring" means for each observable — stated before the numbers

This needs care, because two of the reported quantities transform differently and conflating them would
manufacture or destroy an apparent reversal.

- **`⟨δ⟩`, the mean signed zone offset, REVERSES.** `δ` is measured from a zone centre fixed in the
  laboratory to a site normal carried by the lattice. Mirroring negates the lattice's phase gradient, so
  the offsets at which attachment happens flip sign. Likewise `⟨τ⟩`, `Ω` and turns/µm reverse, because
  gate C7 established `τ` tracks `δ` with a positive, measured slope.
- **`A_TZ` is mirror-INVARIANT by construction, and that is the point.** `A_TZ` is referenced to the drift
  direction (`BEFORE ⇔ sign(D)·δ < 0`), and mirroring negates `D` as well as `δ`. A mechanism that catches
  sites on the *entering* edge of the zone does so on either lattice, so `A_TZ` should keep the *same*
  sign while everything it drives flips. **Its invariance is a check that the same mechanism is running on
  both lattices**, and it is the raw `⟨δ⟩` that carries the handedness.

The task's success criterion lists `A_TZ` among the quantities that reverse. Under the drift-referenced
definition given in §5.1 that is not the correct expectation, so this report tests the physically
meaningful pair explicitly: **`A_TZ` invariant, `⟨δ⟩` reversed** — and reports both, so either reading can
be checked against the data.

### 8.3 Results

[RESULTS PENDING]

## 9. Native versus idealized lattice

[RESULTS PENDING]

## 10. Controls: mirror and target-zone-off

### 10.1 Mirror reversal — the primary symmetry control

Idealized lattice, zone ON, 8 matched chemical seeds per arm, 20 000 steps, identical scene, identical
motor positions, identical seeds, identical prescribed velocity. **The only difference is the sign of the
lattice's azimuthal advance.**

| observable | native lattice | MIRRORED lattice | expectation (§8.2) | verdict |
|---|---|---|---|---|
| attachments | 1321 | 1300 | comparable | ✓ |
| `A_TZ` | **+0.171 ± 0.025** (6.8σ) | **+0.176 ± 0.018** (9.8σ) | **invariant** | ✓ |
| `⟨δ⟩` (rad) | **−0.0925 ± 0.0132** (7.0σ) | **+0.0983 ± 0.0070** (14.0σ) | **reverses** | ✓ |
| `⟨τ⟩` (N·m) | **+4.702e-21 ± 8.0e-22** (5.9σ) | **−6.403e-21 ± 4.8e-22** (13.3σ) | **reverses** | ✓ |
| `Ω` (rad/s) | **+134.5 ± 29** (4.6σ) | **−222.8 ± 13** (17.1σ) | **reverses** | ✓ |
| turns/µm | **+10.70 ± 2.28** (4.7σ) | **−17.73 ± 1.05** (16.9σ) | **reverses** | ✓ |

Every signed quantity reverses, each at high significance, while the drift-referenced asymmetry `A_TZ`
stays put at ~+0.17 on both lattices — precisely the pattern §8.2 committed to in advance. The invariance
of `A_TZ` is not a weakness of the control: it is the statement that *the same first-passage mechanism runs
on both lattices*, and that the handedness enters only through which side of the zone centre that mechanism
happens to catch.

Magnitudes are not symmetric (10.70 vs 17.73 turns/µm). Magnitude antisymmetry is therefore **not**
established, exactly as in the project's earlier chirality mirror control; the discriminating fact is the
sign reversal of all four signed observables together, at 4.6–17σ.

### 10.2 Target-zone OFF — is the zone necessary?

[RESULTS PENDING]

## 11. Bounded sensitivity check — run, but NOT as a rescue

Stage 6 of the task authorises a bounded sensitivity grid **only if the primary setup is null**. The primary
setup is **not** null (§8.3), so this grid is not a rescue and nothing in it was used to establish the
result. It was run anyway, at reduced cost (4 seeds), for one reason: the mechanism predicts that the
attachment asymmetry should depend on the zone width and on the sweep rate, and a mechanism that produced
the same asymmetry at every width would be suspicious.

Grid: zone half-width ∈ {20°, 40°, 70°} × prescribed speed ∈ {1, 2, 4} µm/s — three values per parameter,
one compact two-dimensional grid, exactly the bound the task sets. Converter skew, nucleotide rates, S2
stiffness, catch/slip parameters, motor density and viscosity were **not** varied.

[RESULTS PENDING]

## 12. Numerical health and regression

### 12.1 Numerical health of the fixture

Across every gate and every campaign arm: **all states finite**, **no forbidden nucleotide state** (all in
[0,3]), **zero solver failures**, **zero invalid states**, no NaN/Inf in `coord` or `bondData`, and no
attachment-record collisions. The prescribed translation is held to `9.31e-10 µm` maximum deviation from
the command over a full run. The configuration is reported deterministically (every switch in the manifest)
and the fixture is **exactly reproducible**: the same seed gives a bit-identical trajectory
(`max|Δcoord| = 0`, `Δroll = 0`, gate G5).

### 12.2 Regression of the EXISTING code paths (CPU)

The two shared kernels this task touched — `ChiralSiteSystem.siteSnap` (one new argument, one gated filter)
and `TwoBodyBeamAnalyticGpu.matS2SolveStep` (three new default-0 policy bits) — are exercised by existing
suites, which were re-run on this branch. Log: `RUN_LOGS/vilfan_target_zone/regression/regression.txt`.

**`./scripts/run_chiral_sites.sh -fixtures` — 24 PASS / 0 FAIL, `ALL GATED CHECKS PASS`.** The two decisive
entries:

```
[ 8] zero-feature path bit-identical (all flags OFF ⇒ canonical trajectory)              PASS
[11] site lattice OFF ⇒ prior continuous surface path reproduced bit-identically         PASS
```

together with the discrete-site fixtures that cover the modified kernel directly — `[12]` fresh bind snaps
onto the lattice at the actin surface radius, `[13]` the site frame rolls/translates/bends with the material
frame, `[14]` the bound site id is latched for the whole attachment, `[15]/[16]` site exclusivity and
spacing, and `[25]` the axial-torque identity `τ = Ractin × F_tangential` (an identity, not a fit).

**`./scripts/run_lasertrap.sh -motor-regression` — Gates A–F PASS, `VERDICT: PASS`:**

```
GateB common-core identity:              max|Δ| = 0.00e+00  ⇒ PASS (shared core)
GateC EXPLICIT registry ≡ frozen builder: max|Δpose| = 0.00e+00 ⇒ PASS (bit-identical)
GateD CALIBRATED registry ≡ frozen builder: max|Δpose| = 0.00e+00 ⇒ PASS (bit-identical)
GateE live stroke: explicit = 7.27 (7.27), calibrated = 6.90 (6.90) ⇒ PASS
GateF serialize/restart identity: PASS
```

`max|Δ| = 0` on the common core is the direct check that splitting the motor Brownian mask into per-body
channels is arithmetically inert at policy 0.

Combined with gates E1–E3 of this task's own suite (offset recording alters no binding decision and no
trajectory bit; the per-channel bits at 0 are bit-identical to the canonical mask; all three channels off is
bit-identical to the global switch), **the default-off claim is verified rather than asserted.**

## 13. Limitations

**Design / physics**

1. **The primary lattice is an idealization of the site ORDERING.** Actin 13/6 advances −166.5° per monomer,
   so consecutive monomers are near-antipodal and the azimuths of one repeat are visited in scrambled order.
   The mode-6 fixture keeps actin's real quantization (360/13), rise (2.7 nm) and repeat (35.1 nm) but visits
   those azimuths monotonically. This is what makes "passage through a target zone" well posed; it is
   **not** actin's true lattice. The faithful native 13/6 lattice is run alongside it in §9.
2. **The target zone is a hard admissibility rule, not a graded hazard.** This was the conservative choice
   (it adds no rate and no tunable parameter that could manufacture a bias), but it means the smooth
   attachment-rate profile of the published mechanism is not represented; the prior increment's
   `exp(−½αΔψ²)` hazard is the graded alternative and is held OFF here.
3. **No axial site search beyond the existing ±3-site window**, and site selection among accessible sites is
   still nearest-in-3D. Axial-compliance-assisted selection of a better-registered site is not represented.
4. **The lawn is a narrow strip** (2.00 × 0.010 µm). With mechanical Brownian motion off there is no thermal
   search, so only motors whose relaxed deterministic pose already reaches the filament can ever engage; a
   full-width lawn spends ~95 % of the CPU on motors that can never bind. The strip changes no physics, no
   density and no rate, but it does mean the *transverse* disorder of a real coverslip is not sampled.
5. **One filament, one length, one motor model, one viscosity, one dt.** No dt-convergence study was run for
   the new gate. The prior increment's dt study (its §15) showed the *thermalized* phase-coherence ratio
   worsens at finer dt; that finding is about the Brownian assay and does not transfer to this deterministic
   fixture, which has no thermal phase noise at all.
6. **The rigid single-rod filament** removes bending and tumbling entirely. That is deliberate (it is one of
   the task's favourable assumptions) but it also removes any coupling between roll and bending modes.
7. **Head rotational DOF and the bound orientational registry are OFF.** The only torque channel is the
   off-axis F8 bond at `Ractin`. A registry spring would add a second, independent channel.

**Scope / validation**

8. **CPU only; no device equivalence for the new gate.** The modified kernels compile for both runners and
   both `siteSnap` call sites were updated together, but no GPU execution was performed, so CPU/GPU
   equivalence of the accessibility gate is **untested**. It must be established before any device-resident
   production use — the project's policy triggers a CPU/GPU check for exactly this case (a changed hot
   kernel).
9. **The literal zero-chirality null (F1/F2) fails**, because switching the target zone off leaves a helical
   lattice and off-axis attachment in place. The achiral control (F3/F4) is what isolates fixture bias, and
   it passes. Readers should take F3/F4, not F1/F2, as the statement that the fixture is unbiased.
10. **`A_TZ` is drift-referenced and therefore mirror-invariant by construction** (§8.2). The handedness is
    carried by `⟨δ⟩`, `⟨τ⟩` and `Ω`. This differs from a naive reading of the success criteria and is
    reported explicitly rather than silently.
11. **No claim is made about the biological twirling pitch.** The zone width came from the literature target
    zone geometry, not from pitch agreement, and was never tuned toward an experimental value.

## 14. Stage 7 — behaviour-restoration infrastructure (prepared, NOT executed as a study)

The restoration ladder is *built and smoke-tested*, not run. Each rung is an independent switch; the run
manifest records every one explicitly, so a later Brownian campaign can restore them one at a time and
attribute any loss of the effect to a named channel.

| Rung | What it restores | Flag | Smoke test |
|---|---|---|---|
| 1 | native motor-driven translation (release the prescribed velocity) | `-no-prescribe` | PASS |
| 2 | explicit S2 / converter compliance | never constrained in this fixture — nothing to restore | n/a |
| 3 | axial filament Brownian translation | `-filament-brownian-axial on` | PASS |
| 4 | filament axial-roll Brownian | `-filament-brownian-roll on` | PASS |
| 5 | filament bending and tumbling Brownian | `-filament-brownian-other-rotation on` (+ `-filament-brownian-transverse on`) | PASS |
| 6 | motor-head Brownian | `-motor-head-brownian on` | PASS |
| 7 | converter Brownian | `-converter-brownian on` | PASS |
| 8 | S2-node Brownian | `-s2-node-brownian on` | PASS |
| 8b | head-roll Brownian | `-head-roll-brownian on` | PASS |
| 9 | the complete ordinary gliding assay | `-no-prescribe -no-clamp-tilt -no-rigid` + every channel on | PASS |

`./scripts/run_vilfan_tz_deterministic.sh -smoke` exercises all eleven switches (300 steps each) and checks
that the run completes, the state stays finite and the realised policy string is logged. **11/11 PASS.**
No long Brownian ensemble and no GPU work was launched, per the stopping boundary.

### 14.1 The exact recommended next step

The prior increment's A2 null was root-caused (`docs/VILFAN_TARGET_ZONE_BINDING_AND_TWIRLING_FINDINGS.md`
§10.4) as **axial phase decorrelation**: ~1 nm of thermal axial jitter moves the helical phase by ~1.1 rad,
~105× faster than the sliding drift moves it, and the ratio worsens at finer dt. That number is the single
quantity that decides whether any of this survives thermalization.

**Restore rung 3 (filament axial Brownian) FIRST, alone, and measure the phase-coherence budget before
anything else** — specifically `t_c = 2·D_ax/v²` (the time at which diffusive phase spread equals drift
phase) against the mean candidate residence time. Every other rung is cheaper to interpret once that ratio
is known, because rung 3 is the channel the diagnosis names. Restoring rungs in the order 3 → 6/7/8 → 4 → 5
→ 1 keeps each step attributable. Do **not** start the powered ladder as a single all-channels-on run: the
informative result is *which* channel destroys the effect, not *that* it is destroyed.

---

## 15. Experiments deliberately NOT run

- **The powered Brownian restoration ladder** — prepared and smoke-tested only (§14). Out of scope.
- **Any GPU execution.** No device context, no TaskGraph, no `-gpu`. The modified kernels compile for both
  runners and both `siteSnap` call sites were updated together, but device equivalence for the new gate is
  **untested** and is listed as a limitation.
- **The 5–15° converter-skew map.** Converter skew was pinned at exactly 0° throughout — that is the whole
  point of the zero-stroke-skew test.
- **Any tuning of the target-zone width to an experimental pitch.** The primary width came from the
  literature target-zone geometry (§3.3) and the only width variation is the declared bounded sensitivity
  grid (§11), which is reported in full.
- **Changes to intrinsic motor kinetics or mechanics** — nucleotide rates, catch/slip, rigor rupture, stroke
  magnitude, S2 or binding stiffness, viscosity, and motor-density defaults are all untouched.
- **Re-opening the completed viscosity / mirror-control study.**
- **Merging this branch.**

---

## 16. Merge note — documents to update AFTER the low-[ATP] branch lands

This branch deliberately does **not** touch `docs/CURRENT_STATE.md`, `JOURNAL.md`,
`MotorCooperativityPaperBits/TWIRLING_MECHANISM_PAPER_SECTION_BRIEF.md` or `CLAUDE.md`, because the low-[ATP]
study is actively editing them in the primary worktree. When that study finishes, merge
`feature/vilfan-target-zone` and then update, in this order:

1. **`JOURNAL.md`** — one newest-first entry: the target-zone accessibility feature set, the validation-gate
   result, and the proof-of-mechanism verdict (§1).
2. **`CLAUDE.md`** — a short block in the twirling area recording (a) the new default-off controls and their
   flags, (b) that `siteSnap` gained a 15th argument and `matS2SolveStep` gained motor-Brownian policy bits
   2/3/4 (both exact no-ops at their defaults), and (c) the verdict and its scope.
3. **`docs/CURRENT_STATE.md`** — the standing twirling picture, and in particular how this result sits
   against the established converter-skew (ε-ODD) twirl and the viscosity mirror control.
4. **`MotorCooperativityPaperBits/TWIRLING_MECHANISM_PAPER_SECTION_BRIEF.md`** — only if the verdict changes
   the mechanism narrative; the brief currently rests on the converter-skew mechanism, which this task did
   not touch.
5. **`docs/VILFAN_TARGET_ZONE_BINDING_AND_TWIRLING_FINDINGS.md`** — add a forward pointer to this report as
   the deterministic follow-on to its §19 "exact next smallest step". Do **not** rewrite its A2 verdict: that
   verdict is about the *thermalized* assay and remains correct in its own scope.

**Merge hazards to check:** `softbox/ChiralSiteSystem.java`, `softbox/ExplicitCompleteMatHarness.java`,
`softbox/TwoBodyBeamAnalyticGpu.java` and `softbox/ChiralSiteHarness.java` are shared with the low-[ATP]
work. The changes here are additive (one new kernel argument, two new `chiP` slots, three new policy bits,
new default-off statics), but the `siteSnap` signature change means **any other call site added on the
low-[ATP] branch must gain the `tzOff`/`tzData` argument**. Re-run `./scripts/run_chiral_sites.sh -fixtures`
and this report's gate suite after merging.
