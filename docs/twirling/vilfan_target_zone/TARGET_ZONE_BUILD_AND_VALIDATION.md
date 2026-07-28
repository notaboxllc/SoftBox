# Vilfan-like Target-Zone Twirling — Build and Deterministic Validation

**Single controlling report for this task.** Branch `feature/vilfan-target-zone`, worktree
`../softbox-vilfan-target-zone`, base revision `9683598`. **CPU only** — no GPU call, no TaskGraph, no
device context, at any point. Noncanonical, flag-gated, default-off; no canonical default, parameter,
chemistry, force law, stiffness, stroke, rate, catch/slip law, rupture rule, motor density or viscosity was
changed.

---

> **FOLLOW-ON — this report's native-lattice conclusion has been superseded in part.**
> `VILFAN_GRADED_BINDING_VALIDATION.md` implements Vilfan's *actual* graded competing-site attachment law
> (Biophys J 97(4):1130–1137, Eqs 1–2) and finds that **native 13/6 actin does support deep periodic target
> zones (96 % modulation, one per 13 subunits)**. The §9 conclusion below — that actin's real lattice cannot
> provide coherent zone passage — is correct *for the hard-gate rule tested here*, but is **not** a property
> of the lattice. Read §9 as a statement about the rule, and see the follow-on report for the native-lattice
> result.

## 1. Executive conclusion

**Verdict: the target-zone attachment asymmetry is reproduced inside SoftBox under favourable deterministic
assumptions, on an idealized monotone helix; it is NOT reproduced on actin's true 13/6 lattice; and the
axial rotation it accompanies is NOT solely attributable to it.** All three parts are established with
converter skew pinned at exactly 0°, all mechanical Brownian motion off, prescribed translation, constrained
geometry and free axial roll — the most permissive conditions the mechanism will ever see in this model.

### What was reproduced

On the idealized monotone lattice (actin's real azimuthal quantization 360/13, real rise 2.7 nm, real
35.1 nm repeat — but visited in monotone order; a declared idealization of the *ordering* only):

| link in the chain | result |
|---|---|
| biased attachment before vs after the zone centre | `A_TZ = +0.171 ± 0.025` (**6.8σ**); mirrored `+0.176 ± 0.018` (9.8σ) |
| the bias is **caused by the gate** | zone OFF: `−0.040 ± 0.030` (1.3σ) and `−0.025 ± 0.033` (0.8σ) |
| the sign is the predicted one | positive `A_TZ` = capture on the **entering** edge = first-passage + pool depletion |
| signed axial torque | `⟨τ⟩ = +4.702e-21 ± 8.0e-22 N·m` (5.9σ) |
| signed filament rotation | `Ω = +134.5 ± 29 rad/s` (4.6σ); turns/µm `+10.70 ± 2.28` |
| **mirror reversal** | `⟨δ⟩`, `⟨τ⟩`, `Ω`, turns/µm **all reverse** (4.6–17σ); `A_TZ` invariant by construction (§8.2) |
| per-seed reproducibility | torque and rotation signs unanimous **8/8 seeds** in every control arm |
| starting-azimuth robustness | six azimuths across a full repeat: `A_TZ` +0.156…+0.212, all resolved |
| the fixture itself | exerts **exactly zero** axial torque (kinematic; gate D) |
| achiral-lattice null | `τ` 0.14σ, `Ω` 0.79σ (gates F3/F4) — no fixture bias anywhere |

### The two limits that qualify it

1. **Actin's real lattice destroys the mechanism.** On the native 13/6 helix `A_TZ` is −0.037, −0.085,
   −0.095, +0.065 across the same four arms — small, inconsistent in sign, and **not separating zone-ON from
   zone-OFF**; across six starting azimuths it is never positive. The filament still twirls, strongly and
   chirally (`Ω` = −219 rad/s, mirror-reversing), but by a different channel. The cause is structural and
   was **predicted in advance** (§5.2): actin advances −166.5° per monomer, so consecutive sites are
   near-antipodal, a site's zone offset is constant under axial translation, and accessible sites arrive in
   scrambled azimuthal order — there is no coherent passage for a first-passage bias to act on.
2. **The rotation is dominated by azimuthal restriction, not by the flux asymmetry.** The bounded width
   sweep (§11) shows the largest torque of the whole study at a 70° zone, where `A_TZ` is exactly zero.
   The accessibility rule has two separable consequences: **(a)** restricting which azimuthal band may bind
   at all — which needs no coherent passage and drives most of the torque — and **(b)** the Vilfan
   first-passage before/after bias, which needs monotone passage and a narrow enough zone. The causal chain
   is internally consistent and fully mirror-reversing at the primary operating point, but it is **not
   isolated**.

### Two methodological findings worth carrying forward

- **The task's literal zero-chirality null (F1/F2) fails, and correctly so.** Turning the zone off leaves a
  helical lattice and off-axis attachment in place, which are already chiral (3.5σ). The **achiral control**
  added here — 180° per site, a mirror-invariant site set — is what actually isolates fixture bias, and it
  passes at 0.14σ/0.79σ. Future studies should use that as their null, not the zone-off arm.
- **Rotation here is chemistry-limited, not transit-limited.** Attachment counts are flat across a 16× speed
  range, so `Ω` is speed-insensitive and turns/µm falls as 1/v — a qualitative difference from the published
  picture, reported rather than tuned away. **No biological pitch is claimed** and no parameter was tuned
  toward one.

### Bottom line for the next step

The mechanism is real and implementable, and its **requirement** is now identified precisely: *monotone
helical passage through a zone narrow enough to force first-passage capture.* Actin's real lattice supplies
neither on its own. The open question is therefore not "will thermal noise destroy it" — noise can only make
coherence harder, and the prior increment already measured the thermal phase decorrelation that does exactly
that — but **"does anything physical (axial compliance, multi-site reach, a wider effective footprint)
restore coherent passage on a 13/6 lattice?"** The §14 restoration ladder is built and smoke-tested (11/11)
and is ready either way; §14.1 gives the exact recommended first rung.

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

### 8.3 Results — the 2×2 control design

8 matched chemical seeds per arm, 20 000 steps (50 ms), first 2 000 discarded. ~1300 attachments per arm.
Uncertainties are SEM over seeds; σ is |mean|/SEM.

| arm | attach | `A_TZ` | `⟨δ⟩` (rad) | `⟨τ⟩` (N·m) | `Ω` (rad/s) | turns/µm |
|---|---|---|---|---|---|---|
| **zone ON, native** | 1321 | **+0.171 ± 0.025** (6.8σ) | −0.0925 ± 0.0132 (7.0σ) | +4.702e-21 ± 8.0e-22 (5.9σ) | +134.5 ± 29 (4.6σ) | +10.70 ± 2.28 |
| **zone ON, MIRRORED** | 1300 | **+0.176 ± 0.018** (9.8σ) | +0.0983 ± 0.0070 (14.0σ) | −6.403e-21 ± 4.8e-22 (13.3σ) | −222.8 ± 13 (17.1σ) | −17.73 ± 1.05 |
| zone OFF, native | 1330 | **−0.040 ± 0.030** (1.3σ) | +0.0632 ± 0.0551 (1.1σ) | −1.681e-21 ± 5.1e-22 (3.3σ) | −53.6 ± 15 (3.6σ) | −4.27 ± 1.22 |
| zone OFF, MIRRORED | 1312 | **−0.025 ± 0.033** (0.8σ) | −0.0771 ± 0.0659 (1.2σ) | +2.357e-21 ± 4.2e-22 (5.6σ) | +67.3 ± 12 (5.6σ) | +5.36 ± 0.96 |

Attachment counts are matched across all four arms (1300–1330), so nothing below is an engagement artefact.

**Reading the table.**

1. **The target zone creates the attachment asymmetry, and nothing else does.** `A_TZ` is +0.171 and +0.176
   (6.8σ, 9.8σ) with the zone on, and −0.040 and −0.025 (1.3σ, 0.8σ — both consistent with zero) with it
   off. The before/after split is not something the lattice produces on its own; it appears if and only if
   the accessibility gate is present.
2. **The sign is the one the mechanism predicts.** `A_TZ > 0` means attachments are caught preferentially
   *before* the zone centre — on the entering edge. That is exactly what finite attachment kinetics plus
   free-head-pool depletion must do to a site sweeping into an accessibility window, and it is the same
   sign the prior increment's dynamics-free kinematic rig produced.
3. **The torque and the rotation follow.** With the zone on, `⟨δ⟩` is displaced to the entering side and the
   bond's restoring moment about the axis is correspondingly signed: `τ` and `Ω` are resolved at 4.6–17σ and
   carry the sign opposite to `⟨δ⟩`, i.e. the bond turns the filament in the direction that brings the
   captured site *toward* the zone centre.
4. **Everything signed reverses under mirroring; `A_TZ` does not.** Exactly as committed to in §8.2, and in
   **both** zone states — the zone-OFF pair reverses too (−1.681e-21 → +2.357e-21; −53.6 → +67.3).
5. **There are TWO chirality channels, and they are separable.** The zone-OFF arms are not null in torque:
   they carry −1.681e-21 N·m (3.3σ). That is the *lattice* channel identified in §7.1 — a helical lattice
   plus off-axis attachment is chiral on its own. Critically it is **smaller and of the OPPOSITE sign** to
   the zone-on result, so the target zone does not merely amplify a pre-existing bias: it **overwhelms and
   reverses** it.

**The zone's own contribution** (ON − OFF, same lattice, same seeds, matched attachments):

| lattice | Δ`⟨τ⟩` (N·m) | Δ`Ω` (rad/s) | Δ turns/µm |
|---|---|---|---|
| native | **+6.383e-21** | **+188.1** | **+14.97** |
| MIRRORED | **−8.760e-21** | **−290.1** | **−23.09** |

The contribution attributable to the target zone is large, resolved, and **itself reverses under
mirroring** — which is the strongest single statement in this report.

### 8.4 Starting-azimuth robustness — the result is not one lucky phase

Six stored starting filament azimuths spanning one full actin repeat, zone ON, native lattice, 8 seeds each
(48 independent runs). Every arm is re-built from scratch with the material `yVec` pre-rotated by `az0`.

| `az0` | attach | `A_TZ` | `⟨δ⟩` (rad) | `⟨τ⟩` (N·m) | `Ω` (rad/s) | turns/µm |
|---|---|---|---|---|---|---|
| 0° | 1321 | +0.171 ± 0.025 (6.8σ) | −0.0925 ± 0.0132 | +4.702e-21 (5.9σ) | +134.5 ± 29 | +10.70 ± 2.28 |
| 60° | 1314 | +0.199 ± 0.034 (5.8σ) | −0.1171 ± 0.0121 | +4.901e-21 (5.8σ) | +139.1 ± 29 | +11.07 ± 2.33 |
| 120° | 1339 | +0.166 ± 0.022 (7.7σ) | −0.0966 ± 0.0071 | +4.322e-21 (3.7σ) | +123.1 ± 45 | +9.79 ± 3.61 |
| 180° | 1336 | +0.212 ± 0.028 (7.4σ) | −0.1103 ± 0.0130 | +4.054e-21 (3.5σ) | +109.4 ± 42 | +8.70 ± 3.37 |
| 240° | 1344 | +0.201 ± 0.029 (7.0σ) | −0.1185 ± 0.0138 | +5.168e-21 (4.6σ) | +155.0 ± 38 | +12.33 ± 3.04 |
| 300° | 1296 | +0.156 ± 0.044 (3.6σ) | −0.0926 ± 0.0228 | +4.337e-21 (5.6σ) | +133.7 ± 30 | +10.64 ± 2.36 |

**Every one of the six azimuths gives the same answer**: `A_TZ` between +0.156 and +0.212 (all resolved,
3.6–7.7σ), `⟨δ⟩` between −0.093 and −0.119, `⟨τ⟩` between +4.05e-21 and +5.17e-21, `Ω` between +109 and
+155 rad/s. The spread across a whole actin repeat is smaller than the seed SEM within an arm. **Success
criterion 9 is satisfied outright — the result does not depend on one accidental starting azimuth**, and no
starting phase was excluded (all six are reported).

### 8.5 Per-seed sign consistency

`sign_agree` counts (+1 per seed with a positive value, −1 per negative; ±8 means unanimous over 8 seeds):

| arm | `⟨τ⟩` | `Ω` |
|---|---|---|
| zone ON, native | **+8/8** | **+8/8** |
| zone ON, MIRRORED | **−8/8** | **−8/8** |
| zone OFF, native | −8/8 | −8/8 |
| zone OFF, MIRRORED | +8/8 | +8/8 |

**The torque and rotation signs are unanimous across all eight chemical seeds in every control arm**, and
they flip as a block under mirroring. This is a stronger statement than the SEM-based σ values: the effect
is not a mean pulled by outliers, it is present in every single run.

### 8.7 The prescribed-speed ladder

Five speeds spanning 16× (0.5 → 8 µm/s), zone ON, native lattice, 8 seeds each, plus one reversed-direction
control. Speeds were chosen to span the stable range of the fixture, not fitted.

| `v` (µm/s) | attach | `A_TZ` | `Ω` (rad/s) | turns/µm | equivalent pitch (µm) |
|---|---|---|---|---|---|
| +0.5 | 1243 | +0.169 (3.9σ) | +115.5 (3.5σ) | +36.77 | 0.027 |
| +1.0 | 1316 | +0.256 (8.6σ) | +120.6 (4.0σ) | +19.19 | 0.052 |
| +2.0 | 1321 | +0.171 (6.8σ) | +134.5 (4.7σ) | +10.70 | 0.093 |
| +4.0 | 1372 | +0.209 (12.1σ) | +109.0 (3.6σ) | +4.34 | 0.231 |
| +8.0 | 1297 | +0.145 (5.7σ) | +61.3 (2.7σ) | +1.22 | 0.820 |
| **−2.0 (reversed)** | 1336 | +0.153 (5.0σ) | +111.7 (9.4σ) | +8.89 | 0.113 |

**`A_TZ` is resolved and positive at every speed** (3.9–12.1σ) and roughly flat at ~+0.15…+0.26 over a 16×
range. `Ω` is also roughly flat (~110–135 rad/s) up to 4 µm/s, falling to +61 at 8 µm/s. Consequently
**turns/µm falls essentially as 1/v** and the equivalent pitch grows roughly linearly with speed.

**Why, and what it means.** The attachment counts are flat across the whole ladder (1243–1372). The
attachment *rate* is therefore set by the chemistry, **not** by how fast sites sweep through the zone. Each
attachment delivers a roughly fixed angular impulse, so `Ω ≈ (attachment rate) × (impulse per attachment)`
is speed-insensitive and the rotation *per unit distance* must fall as 1/v.

This is a **qualitative difference from the published target-zone picture**, where the zone-transit rate
scales with sliding speed and the pitch is correspondingly closer to speed-independent. In this fixture the
mechanism is **chemistry-limited rather than transit-limited**, and that is a property of the SoftBox motor's
own kinetics, not of the accessibility rule. It is reported as a difference, not smoothed over; no attempt
was made to tune toward a Vilfan-like pitch-versus-speed curve, and **no biological pitch is claimed** — the
equivalent pitches here (0.03–0.82 µm) are fixture numbers.

### 8.8 The reversed-direction control — a partial reversal, and what it teaches

Reversing the prescribed translation (`v = +2 → −2 µm/s`, native lattice, zone ON) gives:

| observable | `v = +2` | `v = −2` | reversed? |
|---|---|---|---|
| `A_TZ` | +0.171 | +0.153 | invariant (expected — drift-referenced) |
| `⟨δ⟩` (rad) | −0.0925 | **+0.0762** | **YES** |
| `⟨τ⟩` (N·m) | +4.702e-21 | +3.602e-21 | **NO** |
| `Ω` (rad/s) | +134.5 | +111.7 | **NO** |

**The kinetic half of the chain reverses; the mechanical half does not.** This is not a contradiction, and
it is worth stating plainly because a careless reading would treat it as one.

Reversing the imposed translation is **not a parity operation**. It flips the direction in which sites sweep
through the zone — so the entering edge swaps and `⟨δ⟩` duly flips, exactly as the gate was designed to do —
but it *also* reverses the mechanical loading of every bound head, because the motor stroke polarity is
fixed by the actin polarity and does not flip with the imposed velocity. The bond force direction, and hence
the moment it exerts about the axis, is therefore governed by a different variable than `⟨δ⟩` alone in this
comparison.

**Mirroring the lattice is the clean parity operation**, and under it the entire chain reverses together
(§10.1). The relation `τ ∝ −⟨δ⟩` holds in all four mirror/zone control arms at fixed loading:

| arm (v = +2) | `⟨δ⟩` | `⟨τ⟩` | `τ ∝ −⟨δ⟩`? |
|---|---|---|---|
| native, zone OFF | +0.0632 | −1.681e-21 | ✓ |
| native, zone ON | −0.0925 | +4.702e-21 | ✓ |
| mirrored, zone OFF | −0.0771 | +2.357e-21 | ✓ |
| mirrored, zone ON | +0.0983 | −6.403e-21 | ✓ |

The restoring relation therefore holds whenever the loading regime is held fixed, and the reversed-velocity
arm is the case where it is not. **The useful positive statement from that arm is that the mechanism
operates in both translation directions**: `A_TZ` is +0.153 (5.0σ) and `Ω` is +111.7 (9.4σ) with the
translation reversed, so nothing about the effect depends on the sign of the imposed motion.

### 8.6 Where the covariation is, and where it is not

Success criterion 8 asks that the attachment asymmetry and the twirling strength covary. They do so
**across conditions** — zone ON versus OFF (§10.2), native versus mirrored (§10.1), and across the speed
ladder (§8.7) — with large, resolved changes moving together.

They do **not** covary cleanly *within* an arm across seeds: the per-arm Pearson `r(A_TZ, τ)` over the eight
seeds ranges from −0.71 to +0.82 with no consistent sign. That is expected and is reported rather than
hidden — within a single arm every seed shares the same mean, so the seed-to-seed scatter is dominated by
chemical noise on both quantities rather than by a causal link between them. The causal claim rests on the
between-condition covariation and on the mirror reversal, not on the within-arm correlation.

## 9. Native actin 13/6 versus the idealized lattice — the decisive scope limit

The same 2×2 design, the same zone width, the same speeds, the same seeds, the same everything — run on the
**faithful native actin 13/6 lattice** (`-lattice 1`, analytic helix, −166.5° per monomer). Log:
`RUN_LOGS/vilfan_target_zone_native/`.

| arm (native 13/6) | attach | `A_TZ` | `⟨δ⟩` (rad) | `⟨τ⟩` (N·m) | `Ω` (rad/s) | turns/µm |
|---|---|---|---|---|---|---|
| zone ON, native | 1380 | **−0.037 ± 0.024** (1.5σ) | +0.0319 ± 0.0100 | −6.188e-21 (6.2σ) | −218.8 ± 27 | −17.41 |
| zone ON, MIRRORED | 1355 | **−0.085 ± 0.028** (3.0σ) | −0.0433 ± 0.0114 | +3.787e-21 (2.7σ) | +87.6 ± 46 | +6.97 |
| zone OFF, native | 1365 | **−0.095 ± 0.032** (3.0σ) | +0.1127 ± 0.0640 | +1.094e-21 (4.8σ) | +27.4 ± 8.0 | +2.18 |
| zone OFF, MIRRORED | 1373 | **+0.065 ± 0.033** (2.0σ) | +0.1002 ± 0.0791 | +4.484e-22 (1.9σ) | +9.6 ± 8.9 | +0.76 |

### 9.1 The attachment asymmetry does NOT survive on actin's real lattice

Compare directly with §8.3:

| | idealized monotone lattice | native actin 13/6 |
|---|---|---|
| `A_TZ`, zone ON, native | **+0.171 ± 0.025 (6.8σ)** | **−0.037 ± 0.024 (1.5σ)** |
| `A_TZ`, zone ON, mirrored | **+0.176 ± 0.018 (9.8σ)** | **−0.085 ± 0.028 (3.0σ)** |
| `A_TZ`, zone OFF, native | −0.040 ± 0.030 (1.3σ) | −0.095 ± 0.032 (3.0σ) |
| `A_TZ`, zone OFF, mirrored | −0.025 ± 0.033 (0.8σ) | +0.065 ± 0.033 (2.0σ) |

On the native lattice the four `A_TZ` values are small, of **inconsistent sign**, and — decisively — they do
**not separate zone-ON from zone-OFF**. There is no before/after attachment bias to speak of, and what
little there is does not track the presence of the gate. On the idealized lattice the same four numbers
separate cleanly and unambiguously (+0.17/+0.18 with the gate, ~0 without).

**This is the predicted consequence of §5.2, and it is the most important scope limit in this report.**
Actin's real lattice advances −166.5° per monomer, so consecutive sites are near-antipodal and the 13
azimuths of one repeat are visited in scrambled order. A site's zone offset `δ` is *constant* under pure
axial translation, and the accessible sites a motor meets arrive in azimuthally scrambled order. There is
therefore **no coherent passage through the zone** for a first-passage bias to act on: a motor that becomes
chemically ready binds at the next accessible encounter, and averaged over the lawn's lattice phases that
encounter is equally likely to be on either side of the zone centre. `A_TZ → 0` is what that predicts.

### 9.2 The filament still twirls — by the other channel

The native lattice nevertheless produces the *largest* rotations in the whole study
(`Ω = −218.8 rad/s`, 8.1σ; turns/µm = −17.41), and those rotations **reverse under mirroring**
(`⟨τ⟩` −6.188e-21 → +3.787e-21; `Ω` −218.8 → +87.6). So the twirl is real and genuinely chiral — it is
simply **not** the target-zone attachment-asymmetry mechanism. It is the lattice/registry channel already
isolated by gates F1–F4: a helical lattice plus off-axis attachment, with the head binding the
best-registered reachable site, twists the filament without any before/after flux bias.

Note also that on the native lattice the simple restoring relation `τ ∝ −⟨δ⟩` (which holds in all four
idealized-lattice control arms, §8.8) **fails**: zone-OFF and zone-ON native both have `⟨δ⟩ > 0` yet
`⟨τ⟩` flips sign between them (+1.094e-21 → −6.188e-21). The mean offset is not a sufficient description of
the mechanics on the scrambled lattice, which is consistent with there being no coherent zone passage there.

### 9.2b Six starting azimuths on the native lattice — the decoupling is systematic

The same six-azimuth robustness sweep, run on the native lattice (zone ON, 8 seeds each):

| `az0` | attach | `A_TZ` | `⟨τ⟩` (N·m) | `Ω` (rad/s) | turns/µm |
|---|---|---|---|---|---|
| 0° | 1380 | −0.037 ± 0.024 | −6.188e-21 | −218.8 ± 27 | −17.41 |
| 60° | 1369 | −0.048 ± 0.021 | −6.167e-21 | −222.2 ± 22 | −17.69 |
| 120° | 1361 | −0.096 ± 0.042 | −5.341e-21 | −200.9 ± 30 | −15.99 |
| 180° | 1379 | −0.074 ± 0.032 | −7.919e-21 | −269.1 ± 17 | −21.41 |
| 240° | 1372 | −0.016 ± 0.020 | −6.784e-21 | −241.0 ± 27 | −19.18 |
| 300° | 1364 | −0.016 ± 0.019 | −7.479e-21 | −260.1 ± 13 | −20.69 |

This is the cleanest possible statement of the decoupling. Across a full actin repeat the **rotation is
large, one-signed and highly reproducible** (`Ω` between −201 and −269 rad/s, every arm resolved) while the
**attachment asymmetry is uniformly small and never positive** (`A_TZ` −0.016 to −0.096, i.e. 0.8σ–2.3σ,
and of the *wrong* sign for the mechanism). A strong, reproducible twirl coexists with no target-zone
asymmetry at all: on actin's real lattice the rotation simply is not being produced by the target-zone
route.

### 9.3 What this means for the scientific question

> **The Vilfan target-zone mechanism — helical site passage through a surface-facing zone producing a
> before/after attachment bias — is reproduced inside SoftBox on an idealized monotone helix, and is NOT
> reproduced on actin's true 13/6 lattice, because the real lattice's azimuthal ordering destroys the
> coherent zone passage the mechanism requires.**

That is a statement about the *mechanism's requirements*, established under favourable, deterministic,
Brownian-free assumptions — the most permissive conditions the mechanism will ever see in this model. It is
therefore a strong constraint, not a provisional one: if the asymmetry does not appear here on the real
lattice, restoring thermal noise will not create it.

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

Success criterion 6 asks whether turning the gate off "removes or strongly suppresses the effect". The
answer is different for the two halves of the chain, and both halves are reported.

- **The attachment asymmetry is REMOVED.** `A_TZ` falls from +0.171 (6.8σ) to −0.040 (1.3σ) on the native
  lattice, and from +0.176 (9.8σ) to −0.025 (0.8σ) on the mirrored one. With the gate off the before/after
  split is statistically indistinguishable from zero. The zone is *necessary* for the asymmetry.
- **The rotation is strongly suppressed AND sign-reversed, but not abolished.** |τ| falls 2.8× (4.70e-21 →
  1.68e-21) and |Ω| falls 2.5× (134.5 → 53.6), with the sign flipping. The residue is a genuinely different
  mechanism — the helical-lattice channel of §7.1 — not a leak of the target-zone mechanism.

The achiral control (gates F3/F4) is what makes this decomposition safe: with a mirror-invariant lattice
(180°/site) both torque and rotation collapse to noise (0.14σ, 0.79σ), so neither channel is a fixture
artefact. The three-way comparison is therefore:

| configuration | `A_TZ` | `⟨τ⟩` (N·m) | interpretation |
|---|---|---|---|
| achiral lattice, zone OFF | — | −1.219e-22 (0.14σ) | no chirality anywhere ⇒ the fixture is unbiased |
| helical lattice, zone OFF | −0.040 (1.3σ) | −1.681e-21 (3.3σ) | the lattice channel alone |
| helical lattice, zone ON | +0.171 (6.8σ) | +4.702e-21 (5.9σ) | the lattice channel + the target-zone channel |

## 11. Bounded sensitivity check — run, but NOT as a rescue

Stage 6 of the task authorises a bounded sensitivity grid **only if the primary setup is null**. The primary
setup is **not** null (§8.3), so this grid is not a rescue and nothing in it was used to establish the
result. It was run anyway, at reduced cost (4 seeds), for one reason: the mechanism predicts that the
attachment asymmetry should depend on the zone width and on the sweep rate, and a mechanism that produced
the same asymmetry at every width would be suspicious.

Grid: zone half-width ∈ {20°, 40°, 70°} × prescribed speed ∈ {1, 2, 4} µm/s — three values per parameter,
one compact two-dimensional grid, exactly the bound the task sets. 4 seeds per cell. Converter skew,
nucleotide rates, S2 stiffness, catch/slip parameters, motor density and viscosity were **not** varied.

| zone half-width | `v` (µm/s) | attach | `A_TZ` | `⟨δ⟩` (rad) | `⟨τ⟩` (N·m) | `Ω` (rad/s) |
|---|---|---|---|---|---|---|
| 20° | 1.0 | 574 | +0.139 ± 0.040 | −0.0368 | +1.365e-21 (0.8σ) | +24.8 (0.5σ) |
| 20° | 2.0 | 610 | +0.197 ± 0.054 | −0.0430 | −3.603e-22 (0.3σ) | −30.4 (0.7σ) |
| 20° | 4.0 | 683 | +0.130 ± 0.052 | −0.0325 | −3.833e-22 (0.2σ) | −36.1 (0.5σ) |
| **40°** | 1.0 | 675 | **+0.251 ± 0.030** | −0.1469 | +3.747e-21 (2.3σ) | +104.4 (1.9σ) |
| **40°** | 2.0 | 676 | **+0.200 ± 0.045** | −0.0980 | +4.586e-21 (4.2σ) | +127.0 (3.2σ) |
| **40°** | 4.0 | 701 | **+0.214 ± 0.020** | −0.1128 | +4.169e-21 (4.3σ) | +109.8 (2.5σ) |
| 70° | 1.0 | 693 | +0.018 ± 0.041 | −0.0335 | +6.771e-21 (10.9σ) | +207.9 (8.7σ) |
| 70° | 2.0 | 693 | −0.013 ± 0.058 | −0.0052 | +7.284e-21 (5.6σ) | +225.5 (5.2σ) |
| 70° | 4.0 | 684 | +0.009 ± 0.015 | −0.0243 | +6.532e-21 (4.7σ) | +213.8 (3.7σ) |

### 11.1 What the grid shows — and the qualification it forces

**The attachment asymmetry behaves exactly as the mechanism requires.** `A_TZ` is resolved and positive at
20° and 40° at every speed, and **collapses to zero at 70°** (+0.018, −0.013, +0.009). That is the correct
signature: a zone must be narrow enough to force first-passage capture. If it admits a wide azimuthal band,
the head can simply take the best-registered site available and no entering-edge bias survives. The primary
40° value (chosen from literature geometry, §3.3) sits in the middle of the working range, not at an edge.

**But the torque does not track `A_TZ` across widths, and that is a real qualification.** Laying the width
sweep against the zone-OFF arm at the same speed (`v = 2`, idealized lattice):

| zone half-width | `A_TZ` | `⟨τ⟩` (N·m) |
|---|---|---|
| 20° | +0.197 | −3.603e-22 |
| 40° | +0.200 | +4.586e-21 |
| 70° | −0.013 | **+7.284e-21** |
| OFF (=180°) | −0.040 | −1.681e-21 |

The **largest** torque in the sweep occurs at 70°, where the attachment asymmetry is **zero**. The torque is
therefore not a simple readout of `A_TZ`.

### 11.2 The resulting mechanistic account — two separable consequences of one rule

The accessibility rule has **two distinct consequences**, and only one of them is the Vilfan mechanism:

- **(a) Azimuthal restriction.** Excluding sites outside the zone changes *which* azimuthal band can be
  attached at all, hence the shape of the `δ` distribution and hence the net moment. **This requires no
  coherent passage**: it operates on any lattice, at any speed. It is what makes the 70° arm the strongest
  rotator (removing only the two most-misaligned candidates, at |δ| ≈ 83°, removes a large opposite-signed
  contribution and flips the net), and it is why the zone-OFF arm — which admits everything — has the
  opposite torque sign.
- **(b) First-passage before/after bias.** This is the Vilfan mechanism proper. It **requires** monotone
  azimuthal passage *and* a zone narrow enough to force capture on the entering edge. It is present on the
  idealized lattice at 20–40°, and absent at 70° and on the native lattice.

Every result in this report is consistent with that decomposition:

| configuration | (a) restriction | (b) first-passage bias | `A_TZ` | `⟨τ⟩` |
|---|---|---|---|---|
| achiral lattice | — (no handedness) | — | — | null (0.14σ) |
| native 13/6, zone ON | active | **absent** (scrambled order) | ~0 | large |
| idealized, zone OFF | absent | absent | ~0 | moderate, opposite sign |
| idealized, zone 70° | active | **absent** (too wide) | ~0 | largest |
| **idealized, zone 20–40°** | active | **active** | **+0.14…+0.25** | large, mirror-reversing |

**Consequence for the causal claim.** The chain *asymmetry → torque → rotation* is **internally consistent
and fully mirror-reversing at the primary operating point** (§8.3, §10.1), but it is **not isolated**: the
grid proves that a large signed torque can be produced by the same rule with no attachment asymmetry at
all. The honest statement is therefore that **the target zone demonstrably creates the before/after
attachment asymmetry, and demonstrably drives a mirror-reversing axial rotation, but the rotation is
dominated by the azimuthal-restriction consequence rather than by the flux asymmetry itself.**

This grid was not used to select any reported value, and the primary 40° width was fixed from geometry
before it was run.

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

## 12.3 Success-criteria assessment (the task's nine criteria, verdict by verdict)

| # | Criterion | Verdict | Evidence |
|---|---|---|---|
| 1 | Zone ON with zero stroke skew produces a **resolved** before/after asymmetry | **MET** (idealized lattice) | `A_TZ` = +0.171 ± 0.025 (6.8σ), +0.176 ± 0.018 (9.8σ); §8.3 |
| 2 | The asymmetry has the sign predicted by the direction of site passage | **MET** | positive `A_TZ` = capture on the entering edge, the first-passage/depletion prediction; §8.3 |
| 3 | Signed axial torque has the corresponding sign | **MET at the primary point, NOT ISOLATED** | consistent and mirror-reversing in all four control arms (§8.8), but the width sweep gives the largest torque at zero `A_TZ` (§11) |
| 4 | Axial rotation has the corresponding sign | **MET at the primary point, NOT ISOLATED** | `Ω` = +134.5 (4.6σ), unanimous 8/8 seeds; same qualification as #3 |
| 5 | Mirroring reverses `A_TZ`, torque, angular velocity, turns/distance | **MET, with a stated convention** | `⟨δ⟩`, `⟨τ⟩`, `Ω`, turns/µm all reverse at 4.6–17σ; `A_TZ` is drift-referenced and therefore invariant **by construction** (§8.2) |
| 6 | Turning the zone OFF removes or strongly suppresses the effect | **MET for the asymmetry; PARTIAL for the rotation** | `A_TZ` +0.171 → −0.040 (removed); \|τ\| falls 2.8× and flips sign, residue is the separate lattice channel (§10.2) |
| 7 | The imposed-translation fixture itself produces no torque | **MET outright** | exactly 0.000e+00 N·m, kinematic by construction; gate D |
| 8 | Attachment asymmetry and twirling strength covary across the ladder or phases | **PARTIAL** | they covary strongly *between conditions* (zone ON/OFF, native/mirror); they do **not** covary within an arm across seeds, nor monotonically across zone width (§8.6, §11) |
| 9 | The result does not depend on one accidental starting azimuth | **MET outright** | six azimuths over a full repeat, `A_TZ` +0.156…+0.212, all resolved, none excluded (§8.4) |

**Overall.** Criteria 1, 2, 7 and 9 are met outright. Criterion 5 is met under the convention stated in
advance. Criteria 3, 4, 6 and 8 are met at the primary operating point but are **not cleanly isolated** from
the second, azimuthal-restriction channel that the same rule also switches on. Per the task's own standard —
"the attachment asymmetry, torque, and rotation must agree causally and reverse under mirroring" — they do
agree and do reverse; what the study additionally shows is that agreement alone does not establish
exclusivity, and §11 is the evidence that forced that distinction into the open.

## 12.4 Files produced

**Report:** `docs/twirling/vilfan_target_zone/TARGET_ZONE_BUILD_AND_VALIDATION.md` (this file, the sole
controlling document).

**Raw run data + manifests** (`RUN_LOGS/vilfan_target_zone/`, and `…_native/` for the faithful lattice):

| file | contents |
|---|---|
| `gates.txt` | the full validation-gate log |
| `campaign.txt` / `campaign.csv` / `campaign_seeds.csv` | the 2×2 + azimuth campaign, arm-level and per-seed |
| `ladder.txt` / `ladder.csv` / `ladder_seeds.csv` | the prescribed-speed ladder |
| `sensitivity.txt` / `sensitivity.csv` / `sensitivity_seeds.csv` | the bounded width × speed grid |
| `events/*.csv` | **72 per-attachment event files** — site id, axial coordinate, body-fixed and laboratory azimuth, filament roll, helical phase, zone-centre phase, signed offset, before/after label, mirror state, translation direction, instantaneous axial torque, angular impulse, lifetime, detach nucleotide state, and the measured `cHat·(−eup)` |
| `manifest_*.json` | git revision, branch, JVM, processor count, backend declaration, and the full switch state |
| `regression/regression.txt` | the existing-suite regression output |

**Analysis** (`ANALYSIS/vilfan_target_zone/`, plus `native/` for the faithful lattice):
`analyze_target_zone.py`; `tidy_campaign.csv`, `tidy_ladder.csv`, `tidy_sensitivity.csv`;
`consistency_campaign.csv` (the `Ω = τ/γ_roll` check); and eleven figures —
`fig01` target-zone coordinate per attachment, `fig02` offset histograms zone ON vs OFF, `fig03` native vs
mirrored offset distributions, `fig04` control summary (`A_TZ`, τ, Ω, turns/µm), `fig05` speed ladder,
`fig06` torque and Ω versus `A_TZ` per seed, `fig07` starting-azimuth robustness, `fig08` offset→torque at
the binding step, `fig09` zone-centre versus substrate normal, `fig10` offset→angular-impulse (native and
mirrored).

**Backend, for every run:** CPU sequential Java runner — no TaskGraph, no device context, no GPU call. The
manifest records this explicitly, along with `availableProcessors`; the launcher pins
`-XX:ActiveProcessorCount=4` and `nice -n 15`.

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

7b. **The axial torque is not isolated to the flux asymmetry.** The width sweep (§11) produces the study's
   largest torque at a zone width where `A_TZ` is exactly zero. The accessibility rule drives rotation
   mainly through *which azimuthal band it admits* (a static restriction), not through the before/after
   capture bias. Any claim that "the target zone twirls the filament" must carry this qualification.
7c. **The primary lattice's zone-OFF arm is not a null**, so "zone ON minus zone OFF" is a difference of two
   non-null configurations rather than a signal-over-background. The achiral control is the only true null.

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
