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
| 14 | lattice選択 | `-lattice 1\|4\|5\|6`, `-site-rise-nm`, `-site-stair-deg` | 6 (fixture) | `siteRise`/`siteStairPhase` |

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

[RESULTS PENDING]

## 8. Stage 5 — deterministic proof of mechanism

[RESULTS PENDING]

## 9. Native versus idealized lattice

[RESULTS PENDING]

## 10. Controls: mirror and target-zone-off

[RESULTS PENDING]

## 11. Bounded sensitivity check

[RESULTS PENDING]

## 12. Numerical health

[RESULTS PENDING]

## 13. Limitations

[RESULTS PENDING]

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
