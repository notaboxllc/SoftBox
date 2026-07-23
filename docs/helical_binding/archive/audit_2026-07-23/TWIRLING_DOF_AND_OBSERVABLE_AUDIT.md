# TWIRLING_DOF_AND_OBSERVABLE_AUDIT

**Read-only audit (2026-07-23).** Branch `gpu-mat-bottlenecks-explicit-singlehead`. Question: does the
filament carry an axial rotational (roll/twirl) DOF, is it driven, and can twirling be measured?

---

## 1. Does the filament carry an axial rotational DOF? — **YES, and it is FREE.**

Each rigid rod (`RigidRodBody`, aliased by `FilamentStore`) carries a **full orthonormal body frame**:
`uVec` (long axis) + `yVec` (reference ⊥) + derived `zVec = uVec×yVec` (`RigidRodBody.java:31-34`,
`FilamentStore.java:41-45`). **Roll (twirl) = the azimuth of `yVec` about `uVec`** — a real, tracked
coordinate, not a degenerate/absent one.

- **Integrated:** the roll rate is `bwx = (torqueSum·u)/bRotGam_x`, applied to `yVec` each step
  (`RigidRodLangevinIntegrationSystem.integrate`, roll channel). The rod does **not** carry only a centerline;
  it carries a material frame, so twirl is dynamical, not merely geometric.
- **Preserved, not reset:** `DerivedGeometrySystem.derive` re-orthogonalizes `yVec' = zVec×uVec` each step —
  cleaning Euler drift **while preserving the roll azimuth** (it does not snap roll to a canonical value).
- **Thermally excited:** the roll DOF receives the Brownian torque kick (`randTorque` x-plane). Because
  `bRotGam_x ∝ R²` is tiny, roll is the *hottest*, lowest-drag rotational mode.

⇒ Twirling is **dynamically representable and measurable** — the material-frame twist coordinate exists.
(Contrast: a centerline-only filament could measure twirl neither dynamically nor geometrically. That is not
this codebase.)

---

## 2. Is the roll DOF driven / coupled? 

### Inter-segment coherence: BUILT (`RollSpringSystem`, flag-gated, default-off)
`RollSpringSystem.rollForces` (`RollSpringSystem.java:107-170`) is an **inter-segment torsional-roll spring**:
each chain joint's neighbour roll difference is driven toward a **twisted rest** (166.5°/monomer, LEFT-handed,
negative about pointed→barbed; `:30-34`). Torque is `∥ u_owner`, equal-and-opposite between neighbours
(roll angular momentum conserved), each segment writes only its own `torqueSum` (race-free, bit-identical
CPU↔GPU). This makes the per-segment frames **cohere into one filament helix** so that summed axial torques
could twirl the whole filament rather than each segment independently. Springs-continuum form (mode 2) makes
it a dt-independent fixed-stiffness spring, the canonical object; default-off ⇒ byte-identical.

### Roll thermostat: BUILT (`RollSpringSystem.dampRoll`, `:179-185`)
Attenuates the ‖u component of the Brownian torque kick by `rollParams[5]` (default 0.1 in gliding). Takes
energy out of the low-drag roll mode (the one most prone to instability) **and** quiets roll so a coherent
twist would be observable over thermal noise. `rollDamp=1.0 ⇒` byte-identical; `0.0 ⇒` roll kick off.

### Binding-driven axial torque (the twirl DRIVE): **NOT BUILT**
There is **no** coupling from bound motors into the roll DOF that would make binding *twirl* the filament:
- `RollSpringSystem.rollForces` takes no motor/bond arrays — it is purely the internal coherence spring.
- Both azimuthal gates (`bindNearestAzim`/`bindNearestFalloff`) and the canonical explicit gate attach bonds
  **on the centerline** (`bindArc` scalar). The cross-bridge lever `RS ∥ u` ⇒ `TS = RS×F` has **zero ‖u
  component** ⇒ zero axial torque into `bwx`.
- The design's "off-axis bond placement" (Piece 2 — attach at radius R at the site azimuth, so F8 pulls
  tangentially and exerts a ‖u twirl torque) is the missing keystone. The torque *channel* is fully assembled
  and open (the full 3-vector seg torque is gathered and projected onto `u`); it is simply **fed zero**
  because the attach is on-axis.

⇒ **The twirl loop is OPEN.** Coherence spring + thermostat exist; the binding drive does not. Nothing today
makes a gliding filament accumulate net turns.

---

## 3. Twirling observables — sparse; no cumulative-turns / filament-ω meter

| Observable | Exists? | Where | Notes |
|---|---|---|---|
| Per-joint roll angle (signed) | Yes | `RollSpringHarness.jointRoll` (`RollSpringHarness.java:384-396`) | `atan2`-style, (−π,π]; **standalone prototype only** |
| Twist profile (mean + std across joints) | Yes | `RollSpringHarness.twistProfile` (`:398-406`) | static coherence / handedness of the helix; used for the coherence/stability gates |
| Filament cumulative turns | **No** | — | no net-winding accumulator anywhere |
| Filament roll angular velocity ω | **No** | — | not computed for filaments |
| Helical pitch (measured) | **No** | — | pitch is an input rest, not a measured output |
| Torque about filament axis (readout) | **No** | — | `torqueSum·u` is consumed by the integrator, never reported |
| Angular-velocity estimator | Yes, but **motor-side** | `GlidingHarness.omega` (`:2357`) | applied to the **motor** body `uVec` (the J2 conformation study), **not** filament roll |

- The gliding path (`GlidingHarness`) carries **no filament roll observable at all** — the azimuth flags only
  wire the spring + gate; they add no readout.
- `RollSpringHarness` measures **static twist coherence** (is the helix held? is it stable/bounded?), not
  **dynamic twirling** (net turns over time). It answers "does a torsional-roll stiffness hold a coherent
  visible helix", which is the Piece-3 feasibility question — not "does the filament spin under load".

---

## 4. Verdict

- **Axial rotational DOF:** present, free, thermally excited, integrated, and frame-preserved on both runners
  (CPU + GPU, both lineages). Twirling is **geometrically and dynamically measurable in principle** — the
  material-frame twist coordinate exists and is tracked.
- **Coherence + observability scaffolding (Pieces 3 + 4):** built, flag-gated, default-off, byte-identical
  when off.
- **Twirl drive (Piece 2, off-axis bond → axial torque):** **not built.** No binding torque reaches the roll
  channel; the loop is open.
- **Observables:** only a standalone static-twist-coherence prototype; **no cumulative-turns or ω meter** for
  a driven, twirling filament. Building the drive (Piece 2) should land with a net-turns / ω readout (the
  design's Piece 4 observability pairs with it).
